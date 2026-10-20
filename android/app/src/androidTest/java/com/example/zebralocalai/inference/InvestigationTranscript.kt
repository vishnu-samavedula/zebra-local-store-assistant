package com.example.zebralocalai.inference

import com.example.zebralocalai.audio.AudioTranscriptProvider
import com.example.zebralocalai.audio.TranscriptSegments

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** Existing platform on-device recognition only: no cloud fallback or model download. */
class InvestigationTranscript(context: Context, private val paced: Boolean, private val segmented: Boolean, private val event: (String, String) -> Unit, private val paceDelayMs: Long = 20) : AudioTranscriptProvider {
  private val context = context.applicationContext

  override suspend fun transcribe(file: File): String = try { withTimeout(25_000) {
    withContext(Dispatchers.Main.immediate) {
      check(Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        "Offline transcription is unavailable on this device. Use Type instead."
      }
      suspendCancellableCoroutine { continuation ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val pipe = ParcelFileDescriptor.createPipe()
        val handler = Handler(Looper.getMainLooper())
        val segments = TranscriptSegments()
        fun close() {
          runCatching { recognizer.cancel() }
          runCatching { recognizer.destroy() }
          pipe.forEach { runCatching { it.close() } }
        }
        fun fail(message: String) {
          if (continuation.isActive) {
            close()
            continuation.resumeWithException(IllegalStateException(message))
          }
        }
        fun finish(text: String) {
          if (text.isBlank()) { fail("No speech was recognized. Try again or type instead."); return }
          if (continuation.isActive) { close(); continuation.resume(text.trim()) }
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) { event("ready", "") }
          override fun onBeginningOfSpeech() {}
          override fun onRmsChanged(rmsdB: Float) {}
          override fun onBufferReceived(buffer: ByteArray?) {}
          override fun onEndOfSpeech() {}
          override fun onError(error: Int) {
            event("error", error.toString())
            Log.w("OfflineTranscript", "recognizerError=$error committedSegments=${segments.count}")
            if (error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
              && segments.count > 0) {
              finish(segments.complete())
            } else {
              fail(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                  "I couldn't recognize that speech. Please retry or type instead."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Please retry."
                else -> "Offline transcription could not complete. Please retry or type instead."
              })
            }
          }
          override fun onResults(results: Bundle?) {
            event("results", results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            finish(segments.complete(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()))
          }
          override fun onPartialResults(partialResults: Bundle?) {}
          override fun onEvent(eventType: Int, params: Bundle?) {}
          override fun onSegmentResults(segmentResults: Bundle) {
            event("segment", segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
              ?.takeIf(String::isNotBlank)?.let(segments::append)
          }
          override fun onEndOfSegmentedSession() { event("end_segments", ""); finish(segments.complete()) }
        })
        continuation.invokeOnCancellation { handler.post { close() } }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
          .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
          .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
          .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
          .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
          .apply { if (segmented) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE) }
        recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
          override fun onError(error: Int) = fail("Cannot verify offline transcription support (code $error). Use Type instead.")
          override fun onSupportResult(support: RecognitionSupport) {
            if (support.installedOnDeviceLanguages.none { it.equals("en-US", true) || it.equals("en", true) }) {
              fail("Offline English speech recognition is not installed. Use Type instead.")
              return
            }
            if (!continuation.isActive) return
            try {
              recognizer.startListening(intent)
              thread(name = "offline-transcript-audio", isDaemon = true) {
                try {
                  // Audio capture uses a fixed, validated 44-byte mono PCM WAV header.
                  RandomAccessFile(file, "r").use { input ->
                    val header = ByteArray(44)
                    input.readFully(header)
                    check(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE" &&
                      String(header, 36, 4) == "data" && header[20].toInt() == 1 &&
                      header[22].toInt() == 1 && header[34].toInt() == 16) { "Unsupported captured audio format" }
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                      val buffer = ByteArray(if (paced) 640 else 8192)
                      while (continuation.isActive) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        if (paced) Thread.sleep(paceDelayMs)
                      }
                    }
                  }
                } catch (failure: Exception) {
                  handler.post { fail("Could not transcribe captured audio: ${failure.message}") }
                }
              }
            } catch (failure: Exception) {
              fail("Could not start offline transcription: ${failure.message}")
            }
          }
        })
      }
    }
  } } catch (failure: TimeoutCancellationException) {
    throw IllegalStateException("Offline transcription timed out. Try again or type instead.", failure)
  }
}
