package com.example.zebralocalai.inference

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zebralocalai.agent.GenerativeWarehouseAgent
import com.example.zebralocalai.agent.GroundedWorkflowSession
import com.example.zebralocalai.agent.P1ConversationSession
import com.example.zebralocalai.agent.P1Risk
import com.example.zebralocalai.agent.P1Tool
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
  fun recipeGroundingCompletesVagueMultiturnFlowsForAllFiveTools() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val databaseName = "warehouse-grounded-recipes-device-test.db"
    context.deleteDatabase(databaseName)
    val bundle = ModelBundle.from(File(context.filesDir, "models/lfm25-audio-q4"))
    assertTrue("Matched Audio-600M warehouse bundle is not staged", bundle.isRunnable)

    val runner = LiquidAudioRunner(context)
    val repository = SqliteWarehouseRepository(context, databaseName)
    val agent = GenerativeWarehouseAgent(repository)

    fun runFlow(tool: P1Tool, turns: List<String>, issueCategory: String? = null) =
      GroundedWorkflowSession(
        tool,
        repository,
        GroundedWorkflowSession.seedArguments(tool, issueCategory),
      ).let { workflow ->
        var auditTranscript = ""
        var result: com.example.zebralocalai.agent.P1AgentResult? = null
        turns.forEachIndexed { index, workerText ->
          auditTranscript = listOf(auditTranscript, workerText).filter(String::isNotBlank).joinToString(" -> ")
          val raw = runner.inferText(bundle, workerText)
          val grounded = workflow.enforce(workerText, raw)
          result = agent.process(auditTranscript, grounded, workflow.tool)
          Log.i(
            "P1B_RECIPE_FLOW",
            JSONObject()
              .put("tool", tool.wireName)
              .put("turn", index + 1)
              .put("worker", workerText)
              .put("raw_calls", JSONArray(raw.toolCalls.map { it.tool.wireName }))
              .put("grounded_arguments", JSONObject(workflow.arguments()))
              .put("assistant", result?.message)
              .put("missing", JSONArray(result?.prediction?.missingFields.orEmpty()))
              .toString(),
          )
        }
        requireNotNull(result)
      }

    try {
      runner.warmup(bundle)

      val inventory = runFlow(P1Tool.INVENTORY_SEARCH, listOf("Check TrailBlaze GTX stock.", "Black, size 10."))
      assertEquals(ToolCallState.VERIFIED, inventory.toolCalls.single().state)
      assertTrue(inventory.message.contains("TrailBlaze GTX Black 10"))

      val location = runFlow(P1Tool.LOCATION_CONTENTS, listOf("Show me what is in the bin.", "A three dash zero four."))
      assertEquals(ToolCallState.VERIFIED, location.toolCalls.single().state)
      assertTrue(location.message.startsWith("A3-04:"))

      val task = runFlow(P1Tool.GET_TASK_STATUS, listOf("Check my task.", "T one zero four."))
      assertEquals(ToolCallState.VERIFIED, task.toolCalls.single().state)
      assertTrue(task.message.contains("T-104"))

      val damage =
        runFlow(
          P1Tool.REPORT_ISSUE,
          listOf("I want to report damage to TrailBlaze GTX.", "Three at A three dash zero five."),
          issueCategory = "damage",
        )
      assertEquals(P1Risk.CONFIRM_REQUIRED, damage.prediction.risk)
      assertEquals("3", damage.proposal?.arguments?.get("quantity"))
      assertEquals("A3-05", damage.proposal?.arguments?.get("location"))

      val replenishment =
        runFlow(
          P1Tool.REQUEST_REPLENISHMENT,
          listOf("We need more TrailBlaze GTX.", "Black size 10, add four to A three dash zero five."),
        )
      assertEquals(P1Risk.CONFIRM_REQUIRED, replenishment.prediction.risk)
      assertEquals("SKU-1843-BLK-100", replenishment.proposal?.arguments?.get("sku"))
      assertEquals("4", replenishment.proposal?.arguments?.get("quantity"))
      assertEquals("A3-05", replenishment.proposal?.arguments?.get("destination_location"))
    } finally {
      runner.shutdown()
      context.deleteDatabase(databaseName)
    }
  }

  @Test
  fun exactVisibleDamageFlowStaysInPendingReport() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val databaseName = "warehouse-visible-damage-diagnostic.db"
    context.deleteDatabase(databaseName)
    val model = P1BModel.from(File(context.filesDir, "models/lfm25-p1b"))
    assertTrue("Trained P1B model is not staged", model.isRunnable)
    val runner = LiquidTextRunner(context)
    val agent = GenerativeWarehouseAgent(SqliteWarehouseRepository(context, databaseName))
    var session = P1ConversationSession()
    try {
      runner.warmup(model)
      listOf(
          "Here I want to record a damage.",
          "The Metro Zip Wallet",
          "About ten of them.",
        )
        .forEachIndexed { index, workerText ->
          val request = session.request(workerText)
          val rawInference = runner.infer(model, request.modelInput)
          val inference = request.enforcePendingWorkflow(rawInference)
          val result = agent.process(request.auditTranscript, inference)
          session = session.record(workerText, result)
          Log.i(
            "P1B_VISIBLE_FLOW",
            JSONObject()
              .put("turn", index + 1)
              .put("model_input", request.modelInput)
              .put("model_text", rawInference.text)
              .put(
                "model_calls",
                JSONArray(
                  rawInference.toolCalls.map { call ->
                    JSONObject().put("name", call.tool.wireName).put("arguments", JSONObject(call.arguments))
                  },
                ),
              )
              .put(
                "calls",
                JSONArray(
                  inference.toolCalls.map { call ->
                    JSONObject().put("name", call.tool.wireName).put("arguments", JSONObject(call.arguments))
                  },
                ),
              )
              .put("routed_tool", result.prediction.tool.wireName)
              .put("assistant", result.message)
              .put("missing", JSONArray(result.prediction.missingFields))
              .toString(),
          )
        }
      val final = session.turns.last()
      assertEquals("An active damage report must not switch tools", "report_issue", final.tool.wireName)
      assertTrue("Quantity should be extracted from the worker update", final.arguments["quantity"] == "10")
      assertTrue("Observed location should still be requested", "location" in final.missingFields)
    } finally {
      runner.shutdown()
      context.deleteDatabase(databaseName)
    }
  }

  @Test
  fun groundedDamageContinuationSmoke() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val databaseName = "warehouse-damage-continuation-smoke.db"
    context.deleteDatabase(databaseName)
    val model = P1BModel.from(File(context.filesDir, "models/lfm25-p1b"))
    assertTrue("Trained P1B model is not staged", model.isRunnable)

    val runner = LiquidTextRunner(context)
    val agent = GenerativeWarehouseAgent(SqliteWarehouseRepository(context, databaseName))
    var session = P1ConversationSession()
    val workerTurns =
      listOf(
        "I see some damage and I want to report it.",
        "The Metro Zip Wallets.",
        "Close to ten of them.",
        "At receiving dock Z9.",
      )
    try {
      runner.warmup(model)
      workerTurns.forEachIndexed { index, workerText ->
        val request = session.request(workerText)
        val inference = request.enforcePendingWorkflow(runner.infer(model, request.modelInput))
        val result = agent.process(request.auditTranscript, inference)
        session = session.record(workerText, result)
        Log.i(
          "P1B_GROUNDED_SMOKE",
          JSONObject()
            .put("turn", index + 1)
            .put("worker", workerText)
            .put("model_input", request.modelInput)
            .put("raw", inference.rawOutput.take(4_000))
            .put(
              "calls",
              JSONArray(
                inference.toolCalls.map { call ->
                  JSONObject().put("name", call.tool.wireName).put("arguments", JSONObject(call.arguments))
                },
              ),
            )
            .put("assistant", result.message)
            .put("missing", JSONArray(result.prediction.missingFields))
            .put(
              "candidates",
              JSONArray(
                result.candidates.map { candidate ->
                  JSONObject()
                    .put("name", candidate.name)
                    .put("sku", candidate.sku)
                    .put("color", candidate.color)
                    .put("location", candidate.location)
                },
              ),
            )
            .toString(),
        )
      }

      val final = session.turns.last()
      assertEquals("The grounded continuation must stay on report_issue", "report_issue", final.tool.wireName)
      assertTrue("The grounded continuation should have all required fields: ${final.missingFields}", final.missingFields.isEmpty())
    } finally {
      runner.shutdown()
      context.deleteDatabase(databaseName)
    }
  }

  @Test
  fun checkTwoItemsRecipeReturnsBothProducts() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val databaseName = "warehouse-check-two-items-device-test.db"
    context.deleteDatabase(databaseName)
    val model = P1BModel.from(File(context.filesDir, "models/lfm25-p1b"))
    assertTrue("Trained P1B model is not staged", model.isRunnable)

    val runner = LiquidTextRunner(context)
    val agent = GenerativeWarehouseAgent(SqliteWarehouseRepository(context, databaseName))
    val prompt = "Check stock for SKU-3201-TAN-OS and SKU-4902-CLR-500."
    try {
      val inference = runner.infer(model, prompt)
      val result = agent.process(prompt, inference)
      Log.i(
        "P1B_TWO_ITEMS",
        JSONObject()
          .put("raw", inference.rawOutput.take(2_000))
          .put("calls", JSONArray(inference.toolCalls.map { call -> JSONObject().put("name", call.tool.wireName).put("arguments", JSONObject(call.arguments)) }))
          .put("result", result.message)
          .toString(),
      )

      assertEquals(
        "Model did not emit two reads. raw=${inference.rawOutput.take(1_200)} calls=${inference.toolCalls}",
        2,
        inference.toolCalls.size,
      )
      assertTrue(inference.toolCalls.all { it.tool.wireName == "inventory_search" })
      assertEquals(2, result.toolCalls.size)
      assertTrue("Wallet missing from result: ${result.message}", result.message.contains("Metro Zip Wallet"))
      assertTrue("Pallet wrap missing from result: ${result.message}", result.message.contains("DockPro Pallet Wrap"))
    } finally {
      runner.shutdown()
      context.deleteDatabase(databaseName)
    }
  }

  @Test
  fun threeIndependentInventoryReadsCompleteWithoutTruncation() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val databaseName = "warehouse-three-read-device-test.db"
    context.deleteDatabase(databaseName)
    val model = P1BModel.from(File(context.filesDir, "models/lfm25-p1b"))
    assertTrue("Trained P1B model is not staged", model.isRunnable)

    val runner = LiquidTextRunner(context)
    val agent = GenerativeWarehouseAgent(SqliteWarehouseRepository(context, databaseName))
    try {
      val prompt =
        "Give me the location of Harbor Classic T-shirts, Metro Zip Wallets, and Metro Run Sneaker."
      val inference = runner.infer(model, prompt)

      assertTrue("Three-read output was truncated: ${inference.rawOutput.take(1000)}", inference.finishReason != "length")
      assertTrue("Three-read output was malformed: ${inference.rawOutput.take(1000)}", !inference.malformedToolCall)
      assertEquals(3, inference.toolCalls.size)
      assertTrue(inference.toolCalls.all { it.tool.wireName == "inventory_search" })

      val result = agent.process(prompt, inference)
      assertEquals(3, result.toolCalls.size)
      assertTrue(result.toolCalls.all { it.state == ToolCallState.VERIFIED })
      assertTrue("Harbor lookup failed. calls=${inference.toolCalls} result=${result.message}", result.message.contains("Harbor Classic T-Shirt"))
      assertTrue("Wallet lookup failed. calls=${inference.toolCalls} result=${result.message}", result.message.contains("Metro Zip Wallet"))
      assertTrue("Sneaker lookup failed. calls=${inference.toolCalls} result=${result.message}", result.message.contains("MetroRun Sneaker"))
    } finally {
      runner.shutdown()
      context.deleteDatabase(databaseName)
    }
  }

  @Test
  fun conversationalContinuationCompletesMissingReplenishmentFields() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val databaseName = "warehouse-conversation-device-test.db"
    context.deleteDatabase(databaseName)
    val model = P1BModel.from(File(context.filesDir, "models/lfm25-p1b"))
    assertTrue("Trained P1B model is not staged", model.isRunnable)

    val runner = LiquidTextRunner(context)
    val agent = GenerativeWarehouseAgent(SqliteWarehouseRepository(context, databaseName))
    try {
      val firstWorkerTurn = "Request replenishment for SKU-2202-NVY-M."
      val firstInference = runner.infer(model, firstWorkerTurn)
      val firstResult = agent.process(firstWorkerTurn, firstInference)
      assertTrue("Incomplete request must not create a proposal", firstResult.proposal == null)

      var session = P1ConversationSession().record(firstWorkerTurn, firstResult)
      val followUp = session.request("Add exactly 12 units to B2-04.")
      val secondInference = runner.infer(model, followUp.modelInput)
      val secondResult = agent.process(followUp.auditTranscript, secondInference)
      session = session.record("Add exactly 12 units to B2-04.", secondResult)

      assertEquals("request_replenishment", secondResult.toolCalls.single().tool.wireName)
      assertEquals(
        "Continuation did not produce a complete proposal. calls=${secondInference.toolCalls} missing=${secondResult.prediction.missingFields} raw=${secondInference.rawOutput.take(800)}",
        P1Risk.CONFIRM_REQUIRED,
        secondResult.prediction.risk,
      )
      assertEquals("SKU-2202-NVY-M", secondResult.proposal?.arguments?.get("sku"))
      assertEquals("12", secondResult.proposal?.arguments?.get("quantity"))
      assertEquals("B2-04", secondResult.proposal?.arguments?.get("destination_location"))
      assertEquals(2, session.turns.size)
      assertTrue("A third bounded turn should remain available", session.canContinue)

      val correction = session.request("Actually make that 14 units.")
      val correctionInference = runner.infer(model, correction.modelInput)
      val correctionResult = agent.process(correction.auditTranscript, correctionInference)
      session = session.record("Actually make that 14 units.", correctionResult)
      assertEquals(
        "Correction emitted the wrong number of calls. input=${correction.modelInput} " +
          "raw=${correctionInference.rawOutput.take(800)}",
        1,
        correctionResult.toolCalls.size,
      )
      assertEquals("request_replenishment", correctionResult.toolCalls.single().tool.wireName)
      assertEquals(
        "Correction did not produce a complete proposal. calls=${correctionInference.toolCalls} " +
          "missing=${correctionResult.prediction.missingFields} message=${correctionResult.message} " +
          "raw=${correctionInference.rawOutput.take(800)}",
        P1Risk.CONFIRM_REQUIRED,
        correctionResult.prediction.risk,
      )
      assertEquals("14", correctionResult.proposal?.arguments?.get("quantity"))
      assertEquals("B2-04", correctionResult.proposal?.arguments?.get("destination_location"))
      assertTrue("A fourth bounded turn should remain available", session.canContinue)

      val finalTurn = session.request("Keep the destination at B2-04.")
      val finalInference = runner.infer(model, finalTurn.modelInput)
      val finalResult = agent.process(finalTurn.auditTranscript, finalInference)
      session = session.record("Keep the destination at B2-04.", finalResult)
      assertTrue("A four-turn request must stop accepting follow-ups", !session.canContinue)

      val readWorkerTurn = "How many black GTX shoes size 10 do we have?"
      val readResult = agent.process(readWorkerTurn, runner.infer(model, readWorkerTurn))
      assertTrue(readResult.toolCalls.all { it.state == ToolCallState.VERIFIED })
      val readSession = P1ConversationSession().record(readWorkerTurn, readResult)
      val expandedRead = readSession.request("Also check blue GTX shoes size 10.5.")
      val expandedInference = runner.infer(model, expandedRead.modelInput)
      val expandedResult = agent.process(expandedRead.auditTranscript, expandedInference)
      assertTrue("Expanded read emitted no inventory call: ${expandedInference.rawOutput.take(800)}", expandedInference.toolCalls.isNotEmpty())
      assertTrue(expandedInference.toolCalls.all { it.tool.wireName == "inventory_search" })
      assertTrue("Expanded read did not resolve the blue variant: ${expandedResult.message}", expandedResult.message.contains("Blue"))
    } finally {
      runner.shutdown()
      context.deleteDatabase(databaseName)
    }
  }

  @Test
  fun trainedCheckpointProducesAllFiveNativeToolsColdThenWarm() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val databaseName = "warehouse-model-device-test.db"
    context.deleteDatabase(databaseName)
    val model = P1BModel.from(File(context.filesDir, "models/lfm25-p1b"))
    assertTrue("Trained P1B model is not staged", model.isRunnable)

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

      listOf(
        "File a damage report.",
        "Report damaged SKU-5102-BLU-L at C2-01.",
        "Report 3 damaged units of SKU-5103-BLU-XL.",
      ).forEach { incompleteIssue ->
        val issueResult = agent.process(incompleteIssue, runner.infer(model, incompleteIssue))
        assertTrue("Incomplete issue must not create an executable write: $incompleteIssue", issueResult.proposal == null)
        assertTrue("Incomplete issue must not request confirmation: $incompleteIssue", issueResult.prediction.risk != P1Risk.CONFIRM_REQUIRED)
        assertTrue("Incomplete issue must return a safe response: $incompleteIssue", issueResult.message.isNotBlank())
      }

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
    assertTrue("Trained P1B model is not staged", model.isRunnable)
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
