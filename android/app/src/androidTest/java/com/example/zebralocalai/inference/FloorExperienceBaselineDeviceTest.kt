package com.example.zebralocalai.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zebralocalai.agent.DeviceWarehouseHarness
import com.example.zebralocalai.audio.OfflineAudioTranscript
import com.example.zebralocalai.ui.industrial.HarnessReplyFormatter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloorExperienceBaselineDeviceTest {
  @Test fun measureCurrentCheckpointAndTwoModeProcessing() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = File(context.filesDir, "floor-experience-baseline.db")
    database.delete()
    val harness = DeviceWarehouseHarness(context, database.name, oneShot = true)
    val runtime = CpuAudioRuntime(context)
    val transcriber = OfflineAudioTranscript(context)
    val root = File(context.filesDir, "models/warehouse-test/warehouse-iteration-20261013g")
    val rows = JSONArray()
    val report = JSONObject().put("checkpoint", "warehouse-iteration-20261013g").put("quantization", "Q8_0")
      .put("new_training_run", false).put("screen_rendering_included", false).put("cases", rows)
    data class Case(val id: String, val mode: String, val text: String, val audio: String? = null)
    val cases = listOf(
      Case("guidance", "sop", "My colleagues are coughing."),
      Case("guidance-audio", "sop", "", "sop-cough.wav"),
      Case("report", "sop", "I want to report a broken scanner near receiving."),
      Case("report-audio", "sop", "", "sop-report.wav"),
      Case("stock-text", "general", "How many Cloudweight Hoodies in ink, medium are available?"),
      Case("stock-audio", "general", "", "I01-1-0.wav"),
      Case("task-text", "general", "How many tasks are there for me?"),
      Case("task-audio", "general", "", "T01-1-0.wav"),
      Case("replenishment", "general", "Request ten Cloudweight Hoodies in ink, medium."),
      Case("family-stock", "general", "How many Transit Wallets do we have?")
    )
    try {
      runtime.warmup()
      repeat(2) { repeat -> for (case in cases) {
        val chat = "baseline-${case.id}"
        harness.setMode(chat, case.mode)
        harness.restart(chat)
        val begin = System.nanoTime()
        var transcription = 0L
        val text = if (case.audio != null) {
          val start = System.nanoTime()
          transcriber.transcribe(File(root, case.audio)).also { transcription = (System.nanoTime() - start) / 1000000 }
        } else case.text
        val controlStarted = System.nanoTime()
        val controlled = harness.control(chat, text, if (case.audio == null) "text" else "audio", "control-$repeat-${case.id}")
        var harnessMillis = (System.nanoTime() - controlStarted) / 1000000
        var inference: InferenceResult? = null
        val result = controlled ?: run {
          val state = harness.modelContext(chat, text).apply { remove("model_history") }
          val history = listOf("tool" to JSONObject().put("harness_state", state).toString())
          inference = if (case.audio == null) runtime.replyText(text, history) else runtime.replyAudio(File(root, case.audio), history)
          val start = System.nanoTime()
          harness.handle(chat, text, requireNotNull(inference).text, if (case.audio == null) "text" else "audio",
            requireNotNull(inference).finishReason ?: "stop", "turn-$repeat-${case.id}").also {
            harnessMillis += (System.nanoTime() - start) / 1000000
          }
        }
        val row = JSONObject().put("id", case.id).put("repeat", repeat).put("mode", case.mode)
          .put("input", if (case.audio == null) "text" else "audio").put("transcript", text)
          .put("transcription_ms", transcription).put("harness_ms", harnessMillis)
          .put("processing_total_ms", (System.nanoTime() - begin) / 1000000)
          .put("generation_called", inference != null).put("model_ms", inference?.elapsedMillis ?: 0)
          .put("first_text_ms", inference?.ttfsMillis).put("model_output", inference?.text)
          .put("input_tokens", inference?.promptTokens).put("output_tokens", inference?.completionTokens)
          .put("result", result).put("formatted_reply", HarnessReplyFormatter.format(result))
        rows.put(row)
        File(context.filesDir, "floor-experience-baseline.json").writeText(report.toString(2))
        if (case.id.startsWith("guidance")) {
          assertNull(inference)
          assertTrue(HarnessReplyFormatter.format(result).contains("Worker coughing"))
        }
        assertFalse(result.optBoolean("saved"))
        assertEquals(0, harness.tickets().length())
      } }
    } finally { runtime.shutdown(); harness.shutdown(); database.delete() }
  }
}
