package com.example.zebralocalai.ui.industrial

import com.example.zebralocalai.audio.AudioTranscriptProvider
import com.example.zebralocalai.audio.AudioTurnPipeline
import com.example.zebralocalai.inference.InferenceResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioTurnPipelineTest {
  private fun reply() = InferenceResult("Which size?", 1, null, null, null, null, null, "")

  @Test fun transcriptIsPublishedBeforeInferenceAndHistoryIsPreserved() = runBlocking {
    val file = File.createTempFile("audio-turn", ".wav")
    val events = mutableListOf<String>()
    val history = listOf("user" to "Request hoodies", "assistant" to "Which color?")
    val pipeline = AudioTurnPipeline(AudioTranscriptProvider { "Ink" }) { audio, prior ->
      assertTrue(audio.exists())
      assertEquals(history, prior)
      events += "inference"
      reply()
    }
    val result = pipeline.run(file, history) { events += "transcript:$it" }
    assertEquals(listOf("transcript:Ink", "inference"), events)
    assertEquals("Which size?", result.text)
    assertFalse(file.exists())
  }

  @Test fun emptyTranscriptionRunsNoInferenceAndDeletesAudio() = runBlocking {
    val file = File.createTempFile("audio-turn", ".wav")
    var called = false
    val pipeline = AudioTurnPipeline(AudioTranscriptProvider { " " }) { _, _ -> called = true; reply() }
    try { pipeline.run(file, emptyList()) { called = true }; fail("Expected no-speech failure") }
    catch (_: IllegalStateException) { }
    assertFalse(called)
    assertFalse(file.exists())
  }

  @Test fun transcriptRemainsVisibleWhenInferenceFailsAndAudioIsDeleted() = runBlocking {
    val file = File.createTempFile("audio-turn", ".wav")
    var visible = ""
    val pipeline = AudioTurnPipeline(AudioTranscriptProvider { "Ink" }) { _, _ -> error("Model unavailable") }
    try { pipeline.run(file, emptyList()) { visible = it }; fail("Expected inference failure") }
    catch (_: IllegalStateException) { }
    assertEquals("Ink", visible)
    assertFalse(file.exists())
  }
}
