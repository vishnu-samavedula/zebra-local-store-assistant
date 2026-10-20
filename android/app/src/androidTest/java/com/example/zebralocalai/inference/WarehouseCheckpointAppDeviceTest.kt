package com.example.zebralocalai.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zebralocalai.agent.DeviceWarehouseHarness
import com.example.zebralocalai.ui.industrial.HarnessReplyFormatter
import com.example.zebralocalai.ui.industrial.IndustrialTicket
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual active runtime -> shared harness -> formatted reply/ticket, with isolated writes. */
@RunWith(AndroidJUnit4::class)
class WarehouseCheckpointAppDeviceTest {
  @Test fun warehouseCheckpointRunsBothInputsAndHarnessOwnedReports() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val runtime = CpuAudioRuntime(context)
    val db = File(context.filesDir, "warehouse-g-controlled-review.db")
    db.delete()
    val harness = DeviceWarehouseHarness(context, db.name)
    val directory = File(context.filesDir, "models/warehouse-test/warehouse-iteration-20261013g")
    val old = JSONObject(File(directory, "device-inputs.json").readText()).getJSONArray("cases")
    val fixtures = JSONArray()
    for (index in 0 until old.length()) {
      val row = old.getJSONObject(index)
      if (row.getString("id") in setOf("I01:1", "T01:1")) fixtures.put(row)
    }
    // Older fixtures can suffix their IDs by modality; select by expected tool too.
    if (fixtures.length() == 0) {
      for (index in 0 until old.length()) {
        val row = old.getJSONObject(index)
        val calls = row.getJSONArray("expected_calls")
        if (calls.length() == 1 && calls.getJSONObject(0).getString("tool") in setOf("inventory_lookup", "task_lookup")) fixtures.put(row)
      }
    }
    for ((id, text, tool, kind) in listOf(
      listOf("concern", "A burning smell beside the packing table.", "report_general_concern", "preview"),
      listOf("damage", "Five Cloudweight Hoodies in ink, medium. Fabric is torn at the hem around the corner from the break room.", "report_product_damage", "preview"),
      listOf("replenishment", "Request five Cloudweight Hoodies in ink, medium.", "request_replenishment", "preview"),
      listOf("unclear", "Handle that.", "", "conversation")
    )) fixtures.put(JSONObject().put("id", id).put("mode", "text").put("transcript", text).put("tool", tool).put("kind", kind))
    val checks = JSONArray()
    val report = JSONObject().put("checkpoint", "warehouse-iteration-20261013g").put("test_checkpoint", true)
      .put("writes_in_isolated_test_database", true).put("microphone_transcription_tested", false)
    try {
      runtime.warmup()
      val server = runtime.serverInstance()
      assertNotNull(server)
      for (index in 0 until fixtures.length()) {
        val fixture = fixtures.getJSONObject(index)
        val chat = "review-$index"
        val mode = fixture.getString("mode")
        val originalMessages = fixture.optJSONArray("messages")
        val transcript = fixture.optString("transcript").ifBlank {
          originalMessages!!.getJSONObject(originalMessages.length() - 1).getString("content")
        }
        val state = harness.modelContext(chat, transcript).apply { remove("model_history") }
        val history = listOf("tool" to JSONObject().put("harness_state", state).toString())
        val inference = if (mode == "audio") runtime.replyAudio(File(directory, fixture.getString("audio_file")), history)
          else runtime.replyText(transcript, history)
        assertSame("Generation process changed", server, runtime.serverInstance())
        val outcome = harness.handle(chat, transcript, inference.text, mode, inference.finishReason ?: "stop", UUID.randomUUID().toString())
        val formatted = HarnessReplyFormatter.format(outcome)
        assertTrue(formatted.isNotBlank())
        assertFalse("Raw tool syntax reached the worker reply", formatted.contains("<|tool_call_start|>"))
        val expectedTool = fixture.optString("tool").ifBlank {
          fixture.optJSONArray("expected_calls")?.optJSONObject(0)?.optString("tool").orEmpty()
        }
        val tools = outcome.optJSONArray("tools") ?: JSONArray()
        val check = JSONObject().put("id", fixture.getString("id")).put("mode", mode)
          .put("transcript", transcript).put("output", inference.text).put("outcome", outcome)
          .put("formatted_reply", formatted).put("input_tokens", inference.promptTokens)
          .put("output_tokens", inference.completionTokens).put("first_text_ms", inference.ttfsMillis)
          .put("total_ms", inference.elapsedMillis).put("decode_tps", inference.decodeTokensPerSecond)
          .put("expected_tool", expectedTool)
        val correctTool = if (expectedTool.isEmpty()) tools.length() == 0 else tools.toString().contains("\"$expectedTool\"")
        val expectedKind = fixture.optString("kind", "result")
        check.put("correct_tool", correctTool).put("expected_kind", expectedKind)
          .put("journey_passed", correctTool && outcome.getString("kind") == expectedKind)
        if (correctTool && outcome.getString("kind") == "preview") {
          val args = outcome.getJSONObject("preview").getJSONObject("arguments")
          if (expectedTool.startsWith("report_")) assertEquals(transcript, args.getString("report_text"))
          val before = harness.tickets().length()
          val saved = requireNotNull(harness.control(chat, "Yes, submit it.", "text", UUID.randomUUID().toString()))
          assertTrue(saved.getBoolean("saved"))
          assertEquals(before + 1, harness.tickets().length())
          val ticket = IndustrialTicket.parse(saved.getJSONObject("ticket"))
          assertTrue(ticket.fields.isNotEmpty())
          val duplicate = requireNotNull(harness.control(chat, "Yes, submit it.", "text", UUID.randomUUID().toString()))
          assertFalse(duplicate.getBoolean("saved"))
          assertEquals(before + 1, harness.tickets().length())
          check.put("ticket", saved.getJSONObject("ticket")).put("duplicate_prevented", true)
        }
        val processes = ProcessBuilder("/system/bin/ps", "-A", "-o", "PID,ARGS").start()
          .inputStream.bufferedReader().use { it.readLines() }
          .filter { it.contains("libllama-audio-cpu-server.so") && !it.contains("--embedding") }
        assertEquals("More than one generation server", 1, processes.size)
        check.put("generation_server_count", processes.size)
        checks.put(check)
      }
      report.put("checks", checks).put("completed", true).put("demo_ready", false)
      File(context.filesDir, "warehouse-g-app-review.json").writeText(report.toString(2))
    } finally {
      harness.shutdown()
      runtime.shutdown()
      db.delete()
    }
  }
}
