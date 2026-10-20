package com.example.zebralocalai.inference

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zebralocalai.audio.AudioTurnPipeline
import com.example.zebralocalai.audio.OfflineAudioTranscript
import com.example.zebralocalai.agent.DeviceWarehouseHarness
import com.example.zebralocalai.ui.industrial.HarnessReplyFormatter
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineTranscriptDeviceTest {
  @Test fun capturedAudioShowsTranscriptBeforeSameServerInference() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val file = File(context.cacheDir, "offline-transcript-smoke.wav")
    File(context.filesDir, "models/warehouse-test/warehouse-iteration-20261013g/I01-1-0.wav").inputStream().use { input ->
      file.outputStream().use(input::copyTo)
    }
    val runtime = CpuAudioRuntime(context)
    val database = File(context.filesDir, "warehouse-g-transcription-test.db")
    database.delete()
    val harness = DeviceWarehouseHarness(context, database.name)
    var recognized = ""
    val events = mutableListOf<String>()
    try {
      val state = harness.modelContext("speech-review").apply { remove("model_history") }
      val history = listOf("tool" to JSONObject().put("harness_state", state).toString())
      val pipeline = AudioTurnPipeline(OfflineAudioTranscript(context)) { audio, prior ->
        assertEquals(history, prior)
        assertTrue("No transcript before inference", events.firstOrNull()?.startsWith("transcript:") == true)
        events += "inference"
        withContext(Dispatchers.IO) { runtime.replyAudio(audio, prior) }
      }
      val reply = pipeline.run(file, history) { transcript ->
        Log.i("Audio600MTranscriptSmoke", "transcript=$transcript")
        assertTrue("Unexpected transcript: $transcript", transcript.contains("hood", true))
        recognized = transcript
        events += "transcript:$transcript"
      }
      assertEquals(2, events.size)
      assertTrue("Wrong audio response: ${reply.text}", reply.text.contains("inventory_lookup(operation=\"count_stock\""))
      val outcome = harness.handle("speech-review", recognized, reply.text, "audio", reply.finishReason ?: "stop", "speech-event")
      assertEquals("result", outcome.getString("kind"))
      assertTrue(HarnessReplyFormatter.format(outcome).contains("4 each"))
      File(context.filesDir, "warehouse-g-transcription-review.json").writeText(JSONObject()
        .put("transcript", recognized).put("output", reply.text).put("outcome", outcome)
        .put("formatted_reply", HarnessReplyFormatter.format(outcome))
        .put("input", "prerecorded_clean_fixture_through_actual_offline_recognizer")
        .put("live_microphone_tested", false).toString(2))
      assertFalse("Temporary audio was retained", file.exists())
      Log.i("Audio600MTranscriptSmoke", "transcript-before-inference passed; reply=${reply.text}")
    } finally { runtime.shutdown(); harness.shutdown(); file.delete(); database.delete() }
    Unit
  }
}
