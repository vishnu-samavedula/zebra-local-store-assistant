package com.example.zebralocalai.inference

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zebralocalai.agent.GenerativeWarehouseAgent
import com.example.zebralocalai.agent.P1Risk
import com.example.zebralocalai.agent.SqliteWarehouseRepository
import com.example.zebralocalai.agent.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class LiquidTextServerDeviceTest {
  private data class AblationCase(
    val id: String,
    val prompt: String,
    val expectedTools: List<String>,
    val requiredArguments: List<Map<String, String>> = emptyList(),
  )

  @Test
  fun trainedCheckpointProducesAllFiveNativeToolsColdThenWarm() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val databaseName = "warehouse-model-device-test.db"
    context.deleteDatabase(databaseName)
    val model = P1BModel.from(File(context.filesDir, "models/lfm25-p1b"))
    assertTrue("Trained P1B Q8_0 model is not staged", model.isRunnable)

    val runner = LiquidTextRunner(context)
    val agent = GenerativeWarehouseAgent(SqliteWarehouseRepository(context, databaseName))
    try {
      val coldStarted = System.nanoTime()
      runner.warmup(model)
      val coldLoadMillis = (System.nanoTime() - coldStarted) / 1_000_000

      val cases =
        listOf(
          "Check how many Blue TrailBlaze GTX size 10.5 are available." to "inventory_search",
          "What's in D3-01?" to "location_contents",
          "What's the status of task T-122?" to "get_task_status",
          "Generate a damage report for exactly 12 units of SKU-1809-BLK-090 at A3-05." to "report_issue",
          "Can you add exactly 8 units of SKU-4103-BLK-100 to C1-02?" to "request_replenishment",
        )
      var last: P1BInference? = null
      cases.forEach { (prompt, expectedTool) ->
        val inference = runner.infer(model, prompt)
        assertTrue(
          "$expectedTool returned no native tool call: ${inference.rawOutput.take(800)}",
          inference.toolCalls.isNotEmpty(),
        )
        assertEquals(
          "$expectedTool mismatch for '$prompt': ${inference.rawOutput.take(800)}",
          expectedTool,
          inference.toolCalls.first().tool.wireName,
        )
        assertTrue("$expectedTool output was malformed", !inference.malformedToolCall)
        val result = agent.process(prompt, inference)
        assertEquals(expectedTool, result.toolCalls.single().tool.wireName)
        if (expectedTool in setOf("report_issue", "request_replenishment")) {
          assertEquals(P1Risk.CONFIRM_REQUIRED, result.prediction.risk)
          val confirmed = agent.confirm(result)
          assertEquals(ToolCallState.VERIFIED, confirmed.toolCalls.single().state)
          assertTrue("$expectedTool did not verify its local write", !confirmed.verification.isNullOrBlank())
        } else {
          assertEquals(P1Risk.SAFE, result.prediction.risk)
          assertEquals(ToolCallState.VERIFIED, result.toolCalls.single().state)
        }
        last = inference
      }

      val multiPrompt =
        "Check both Tan Metro Zip Wallet size One Size and Clear DockPro Pallet Wrap size 500 m."
      val multi = runner.infer(model, multiPrompt)
      assertEquals(2, multi.toolCalls.size)
      assertTrue(multi.toolCalls.all { it.tool.wireName == "inventory_search" })
      assertTrue("Multi-read output was malformed", !multi.malformedToolCall)
      val multiResult = agent.process(multiPrompt, multi)
      assertEquals(2, multiResult.toolCalls.size)
      assertTrue(multiResult.toolCalls.all { it.state == ToolCallState.VERIFIED })

      val catalogMultiPrompt =
        "Where can I find Harbor Classic T shirts and foundry straight jeans?"
      val catalogMulti = runner.infer(model, catalogMultiPrompt)
      assertEquals(2, catalogMulti.toolCalls.size)
      assertTrue(catalogMulti.toolCalls.all { it.tool.wireName == "inventory_search" })
      val catalogMultiResult = agent.process(catalogMultiPrompt, catalogMulti)
      val catalogResponses = catalogMultiResult.message.lines()
      assertEquals(2, catalogResponses.size)
      assertTrue("First read did not stay isolated: ${catalogMultiResult.message}", catalogResponses[0].contains("Harbor Classic T-Shirt"))
      assertTrue("First read leaked into the second product: ${catalogMultiResult.message}", !catalogResponses[0].contains("Foundry Straight Jean"))
      assertTrue("Second read did not stay isolated: ${catalogMultiResult.message}", catalogResponses[1].contains("Foundry Straight Jean"))
      assertTrue("Second read leaked into the first product: ${catalogMultiResult.message}", !catalogResponses[1].contains("Harbor Classic T-Shirt"))

      val vaguePrompt =
        "We are missing the North Line safety vests. I think we need to order more."
      val vagueResult = agent.process(vaguePrompt, runner.infer(model, vaguePrompt))
      assertTrue("Vague replenishment must not create an executable write", vagueResult.proposal == null)
      assertTrue("Vague replenishment must not request confirmation", vagueResult.prediction.risk != P1Risk.CONFIRM_REQUIRED)

      val correction =
        runner.infer(
          model,
          "Add 10 units of SKU-2202-NVY-M to B2-04, wait, actually exactly 12 units, please.",
        )
      assertEquals("request_replenishment", correction.toolCalls.single().tool.wireName)
      assertEquals("12", correction.toolCalls.single().arguments["quantity"])
      assertEquals("add", correction.toolCalls.single().arguments["quantity_mode"])
      assertEquals("B2-04", correction.toolCalls.single().arguments["destination_location"])

      val conditional =
        runner.infer(
          model,
          "Check SKU-5105-WHT-L at C2-03 and replenish to a target of exactly 21 if availability is lower.",
        )
      assertEquals("inventory_search", conditional.toolCalls.single().tool.wireName)
      assertEquals("SKU-5105-WHT-L", conditional.toolCalls.single().arguments["sku"])
      assertEquals("C2-03", conditional.toolCalls.single().arguments["location"])

      val warm = requireNotNull(last)
      println(
        "P1B_DEVICE coldLoadMs=$coldLoadMillis tools=${cases.size} " +
          "lastMs=${warm.elapsedMillis} lastDecode=${warm.decodeTokensPerSecond} " +
          "lastTool=${warm.toolCalls.first().tool.wireName} args=${warm.toolCalls.first().arguments}",
      )
    } finally {
      runner.shutdown()
      context.deleteDatabase(databaseName)
    }
  }

  @Test
  fun schemaAblationSmoke() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val model = P1BModel.from(File(context.filesDir, "models/lfm25-p1b"))
    assertTrue("Trained P1B Q8_0 model is not staged", model.isRunnable)
    val runner = LiquidTextRunner(context)
    val cases =
      listOf(
        AblationCase("inventory", "Check how many Blue TrailBlaze GTX size 10.5 are available.", listOf("inventory_search")),
        AblationCase("location", "What's in D3-01?", listOf("location_contents"), listOf(mapOf("location" to "D3-01"))),
        AblationCase("task", "What's the status of task T-122?", listOf("get_task_status"), listOf(mapOf("task_id" to "T-122"))),
        AblationCase(
          "issue",
          "Generate a damage report for exactly 12 units of SKU-1809-BLK-090 at A3-05.",
          listOf("report_issue"),
          listOf(mapOf("quantity" to "12", "location" to "A3-05")),
        ),
        AblationCase(
          "replenish",
          "Add exactly 8 units of SKU-4103-BLK-100 to C1-02.",
          listOf("request_replenishment"),
          listOf(mapOf("quantity" to "8", "quantity_mode" to "add", "destination_location" to "C1-02")),
        ),
        AblationCase("sku_lookup", "Where is SKU-5101-BLU-M?", listOf("inventory_search")),
        AblationCase(
          "multi_read",
          "Check both Tan Metro Zip Wallet size One Size and Clear DockPro Pallet Wrap size 500 m.",
          listOf("inventory_search", "inventory_search"),
        ),
        AblationCase("location_b7", "List everything stored at B7.", listOf("location_contents"), listOf(mapOf("location" to "B7"))),
        AblationCase("clarify_write", "Report a damaged Cedar Bifold Wallet.", emptyList()),
        AblationCase("reject", "What will the weather be tomorrow?", emptyList()),
      )
    try {
      runner.warmup(model)
      ToolPromptMode.entries.forEach { mode ->
        val elapsed = mutableListOf<Long>()
        var toolMatches = 0
        var argumentMatches = 0
        var argumentCases = 0
        var malformed = 0
        var promptTokens = 0
        var predictedTokens = 0
        cases.forEach { case ->
          val inference = runner.infer(model, case.prompt, mode)
          elapsed += inference.elapsedMillis
          val actualTools = inference.toolCalls.map { it.tool.wireName }
          val toolsMatch = actualTools == case.expectedTools
          if (toolsMatch) toolMatches++
          if (inference.malformedToolCall) malformed++
          inference.promptTokens?.let { promptTokens += it }
          inference.predictedTokens?.let { predictedTokens += it }
          var argsMatch = true
          if (case.requiredArguments.isNotEmpty()) {
            argumentCases++
            argsMatch =
              toolsMatch && case.requiredArguments.withIndex().all { (index, expected) ->
                val actual = inference.toolCalls.getOrNull(index)?.arguments.orEmpty()
                expected.all { (key, value) -> actual[key].equals(value, ignoreCase = true) }
              }
            if (argsMatch) argumentMatches++
          }
          Log.i(
            "P1B_AB",
            JSONObject()
              .put("mode", mode.name)
              .put("case", case.id)
              .put("ms", inference.elapsedMillis)
              .put("prompt_n", inference.promptTokens)
              .put("predicted_n", inference.predictedTokens)
              .put("cached_n", inference.cachedTokens)
              .put("tools", JSONArray(actualTools))
              .put("tool_match", toolsMatch)
              .put("args_match", argsMatch)
              .put("malformed", inference.malformedToolCall)
              .put("calls", JSONArray(inference.toolCalls.map { call -> JSONObject().put("name", call.tool.wireName).put("arguments", JSONObject(call.arguments)) }))
              .put("text", inference.text.take(240))
              .toString(),
          )
        }
        val sortedWarm = elapsed.drop(1).sorted()
        val warmMedian = sortedWarm.getOrNull(sortedWarm.size / 2)
        Log.i(
          "P1B_AB_SUMMARY",
          JSONObject()
            .put("mode", mode.name)
            .put("tool_matches", toolMatches)
            .put("cases", cases.size)
            .put("argument_matches", argumentMatches)
            .put("argument_cases", argumentCases)
            .put("malformed", malformed)
            .put("warm_median_ms", warmMedian)
            .put("average_prompt_n", promptTokens.toDouble() / cases.size)
            .put("average_predicted_n", predictedTokens.toDouble() / cases.size)
            .toString(),
        )
      }
    } finally {
      runner.shutdown()
    }
  }
}
