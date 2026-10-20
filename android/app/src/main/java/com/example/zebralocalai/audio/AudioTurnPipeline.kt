package com.example.zebralocalai.audio

import com.example.zebralocalai.inference.InferenceResult
import java.io.File

/** Publishes recognized words before inference; never fabricates a transcript. */
class AudioTurnPipeline<T>(
  private val transcripts: AudioTranscriptProvider,
  private val infer: suspend (File, List<Pair<String, String>>) -> T,
) {
  suspend fun run(
    file: File,
    history: List<Pair<String, String>>,
    onTranscript: suspend (String) -> Unit,
  ): T = try {
    val transcript = transcripts.transcribe(file).trim()
    check(transcript.isNotBlank()) { "No speech was recognized. Try again or type instead." }
    onTranscript(transcript)
    infer(file, history)
  } finally { file.delete() }
}
