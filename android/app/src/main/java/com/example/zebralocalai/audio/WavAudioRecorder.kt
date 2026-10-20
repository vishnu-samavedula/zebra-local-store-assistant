package com.example.zebralocalai.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class WavAudioRecorder(private val context: Context) {
  companion object {
    const val SAMPLE_RATE = 16_000
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
  }

  private val recording = AtomicBoolean(false)
  private var audioRecord: AudioRecord? = null
  private var writerThread: Thread? = null
  private var outputFile: File? = null
  private val writerFailure = AtomicReference<Throwable?>(null)
  @Volatile var level: Float = 0f
    private set
  @Volatile var durationLimitReached: Boolean = false
    private set

  init {
    // Normal completion and cancellation delete recordings immediately. This
    // also removes a partial file left behind if Android killed the process.
    context.cacheDir.listFiles().orEmpty().forEach { file ->
      if (
        file.name.startsWith("zebra-input-") ||
          file.name.startsWith("ignored-model-output-")
      ) {
        file.delete()
      }
    }
  }

  @SuppressLint("MissingPermission")
  fun start(maxDurationMillis: Long? = null): File {
    require(maxDurationMillis == null || maxDurationMillis > 0)
    check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      "Microphone permission is required"
    }
    check(!recording.get()) { "Recording is already active" }

    val minimum =
      AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    check(minimum > 0) { "The device does not support 16 kHz mono PCM capture" }
    val bufferSize = maxOf(minimum * 2, SAMPLE_RATE)
    val recorder =
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize,
      )
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      recorder.release()
      error("AudioRecord initialization failed")
    }

    val file = File(context.cacheDir, "zebra-input-${System.currentTimeMillis()}.wav")
    outputFile = file
    audioRecord = recorder
    recording.set(true)
    writerFailure.set(null)
    level = 0f
    durationLimitReached = false
    try {
      recorder.startRecording()
      writerThread =
        thread(name = "zebra-audio-recorder") {
          try {
            FileOutputStream(file).use { output ->
              output.write(ByteArray(44))
              val buffer = ByteArray(minOf(bufferSize, SAMPLE_RATE / 10 * 2))
              val maximumBytes = maxDurationMillis?.let { it * SAMPLE_RATE * 2 / 1000 }
              var writtenBytes = 0L
              while (recording.get()) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                  level = PcmAudioLevel.normalized(buffer, count)
                  val accepted = maximumBytes?.let { minOf(count.toLong(), it - writtenBytes).toInt() } ?: count
                  if (accepted > 0) { output.write(buffer, 0, accepted); writtenBytes += accepted }
                  if (maximumBytes != null && writtenBytes >= maximumBytes) {
                    recorder.stop()
                    durationLimitReached = true
                    break
                  }
                } else if (count < 0 && recording.get()) error("Microphone capture failed ($count)")
              }
            }
          } catch (failure: Throwable) {
            writerFailure.compareAndSet(null, failure)
          }
        }
    } catch (failure: Throwable) {
      recording.set(false)
      recorder.release()
      audioRecord = null
      outputFile = null
      file.delete()
      throw failure
    }
    return file
  }

  fun stop(): File {
    check(recording.getAndSet(false)) { "Recording is not active" }
    try {
      if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord?.stop()
      writerThread?.join(3_000)
      check(writerThread?.isAlive != true) { "Audio writer did not stop" }
    } finally {
      audioRecord?.release()
      audioRecord = null
      writerThread = null
      level = 0f
    }
    val file = checkNotNull(outputFile)
    try {
      writerFailure.getAndSet(null)?.let { throw IllegalStateException("Audio capture failed", it) }
      check(file.length() > 44) { "No audio was captured" }
      writeWavHeader(file)
      outputFile = null
      return file
    } catch (failure: Throwable) {
      file.delete()
      outputFile = null
      throw failure
    }
  }

  fun cancel() {
    if (recording.getAndSet(false)) {
      runCatching { if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord?.stop() }
      writerThread?.join(1_000)
    }
    audioRecord?.release()
    audioRecord = null
    writerThread = null
    outputFile?.delete()
    outputFile = null
    writerFailure.set(null)
    level = 0f
  }

  private fun writeWavHeader(file: File) {
    val dataSize = file.length() - 44
    val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
    val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
    RandomAccessFile(file, "rw").use { wav ->
      wav.seek(0)
      wav.writeBytes("RIFF")
      wav.writeLittleEndianInt((36 + dataSize).toInt())
      wav.writeBytes("WAVEfmt ")
      wav.writeLittleEndianInt(16)
      wav.writeLittleEndianShort(1)
      wav.writeLittleEndianShort(CHANNELS)
      wav.writeLittleEndianInt(SAMPLE_RATE)
      wav.writeLittleEndianInt(byteRate)
      wav.writeLittleEndianShort(blockAlign)
      wav.writeLittleEndianShort(BITS_PER_SAMPLE)
      wav.writeBytes("data")
      wav.writeLittleEndianInt(dataSize.toInt())
    }
  }
}

private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
  write(byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte()))
}

private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
  write(byteArrayOf(value.toByte(), (value ushr 8).toByte()))
}
