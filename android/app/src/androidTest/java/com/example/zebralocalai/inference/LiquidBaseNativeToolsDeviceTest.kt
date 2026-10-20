package com.example.zebralocalai.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidBaseNativeToolsDeviceTest {
  private data class Case(val id: String, val prompt: String, val expectedTools: List<String>)

  @Test
  fun untouchedBaseUsesNativeSchemasOnDevice() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val model =
      P1BModel.baseline(
        File(context.filesDir, "models/lfm25-p1b-baseline/LFM2.5-350M-Q4_K_M.gguf"),
      )
    assertTrue("Untouched base GGUF is not staged", model.isRunnable)

    val cases =
      listOf(
        Case("inventory", "Check how many Blue TrailBlaze GTX size 10.5 are available.", listOf("inventory_search")),
        Case("location", "What's in D3-01?", listOf("location_contents")),
        Case("task", "What's the status of task T-122?", listOf("get_task_status")),
        Case("issue", "Generate a damage report for exactly 12 units of SKU-1809-BLK-090 at A3-05.", listOf("report_issue")),
        Case("replenish", "Add exactly 8 units of SKU-4103-BLK-100 to C1-02.", listOf("request_replenishment")),
        Case("multi_read", "Check both Tan Metro Zip Wallet and Clear DockPro Pallet Wrap.", listOf("inventory_search", "inventory_search")),
        Case("incomplete_replenishment", "Replenish the Northline safety vest.", emptyList()),
        Case("unsupported", "What will the weather be tomorrow?", emptyList()),
      )

    val runner = LiquidTextRunner(context)
    var exactToolSequences = 0
    val results = JSONArray()
    try {
      cases.forEach { case ->
        val inference = runner.infer(model, case.prompt, ToolPromptMode.FULL_SCHEMA)
        val names = inference.toolCalls.map { it.tool.wireName }
        if (names == case.expectedTools) exactToolSequences += 1
        results.put(
          JSONObject()
            .put("id", case.id)
            .put("expected", JSONArray(case.expectedTools))
            .put("actual", JSONArray(names))
            .put("arguments", JSONArray(inference.toolCalls.map { JSONObject(it.arguments) }))
            .put("malformed", inference.malformedToolCall)
            .put("prompt_tokens", inference.promptTokens)
            .put("predicted_tokens", inference.predictedTokens)
            .put("prompt_ms", inference.promptMillis)
            .put("decode_ms", inference.predictedMillis)
            .put("elapsed_ms", inference.elapsedMillis)
            .put("raw", inference.rawOutput.take(1_000)),
        )
      }
    } finally {
      runner.shutdown()
    }

    println("P1B_BASE_NATIVE exactToolSequences=$exactToolSequences/${cases.size} results=$results")
    assertEquals(cases.size, results.length())
  }
}
