package com.example.zebralocalai.agent

import com.example.zebralocalai.inference.P1BInference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P1ConversationSessionTest {
  @Test
  fun `first turn passes through unchanged`() {
    val request = P1ConversationSession().request("Check the safety vests")

    assertEquals("Check the safety vests", request.modelInput)
    assertEquals("Check the safety vests", request.auditTranscript)
    assertEquals(1, request.turnNumber)
  }

  @Test
  fun `follow-up contains compact pending context`() {
    val first =
      result(
        message = "I need a positive quantity, destination location before I can prepare the replenishment.",
        tool = P1Tool.REQUEST_REPLENISHMENT,
        arguments =
          mapOf(
            "semantic_query" to "Northline safety vest",
            "quantity_mode" to "add",
            "units_to_move" to "12",
          ),
        missing = listOf("a positive quantity", "destination location"),
      )
    val session = P1ConversationSession().record("We need more Northline safety vests", first)

    val followUp = session.request("Make it 12 to B2-04")

    assertEquals(
      "Request replenishment: add exactly 12 units of Northline safety vest to B2-04.",
      followUp.modelInput,
    )
    assertFalse(followUp.modelInput.contains("units_to_move"))
    assertEquals(P1Tool.REQUEST_REPLENISHMENT, followUp.pendingCall?.tool)
    assertEquals("We need more Northline safety vests -> Make it 12 to B2-04", followUp.auditTranscript)
    assertEquals(listOf(P1ChatMessage("user", followUp.modelInput)), followUp.chatMessages)
  }

  @Test
  fun `partial write fields accumulate across clarification turns`() {
    val first =
      result(
        message = "Which product, quantity, and location are affected?",
        tool = P1Tool.REPORT_ISSUE,
        arguments = mapOf("category" to "damage"),
        missing = listOf("one exact product or SKU", "quantity", "location"),
      )
    val second =
      result(
        message = "Which Metro Zip wallet variant, quantity, and location?",
        tool = P1Tool.REPORT_ISSUE,
        arguments = mapOf("semantic_query" to "Metro Zip wallets"),
        missing = listOf("one exact product or SKU", "quantity", "location"),
      )
    val session =
      P1ConversationSession()
        .record("I see some damage", first)
        .record("Metro Zip wallets", second)

    val third = session.request("At A3-04")

    assertTrue(third.modelInput.contains("I see some damage."))
    assertTrue(third.modelInput.contains("Metro Zip wallets."))
    assertTrue(third.modelInput.contains("Pending action: report_issue("))
    assertTrue(third.modelInput.contains("category=damage"))
    assertFalse(third.modelInput.contains("description="))
    assertTrue(third.modelInput.contains("semantic_query=Metro Zip wallets"))
    assertTrue(third.modelInput.contains("Assistant previously asked: Which Metro Zip wallet variant, quantity, and location?"))
    assertTrue(third.modelInput.contains("Still needed: one exact product or SKU, quantity, location."))
    assertEquals(P1Tool.REPORT_ISSUE, third.pendingCall?.tool)
  }

  @Test
  fun `active incomplete write blocks a stray model tool switch`() {
    val session =
      P1ConversationSession().record(
        "Report damaged wallets",
        result(
          message = "How many units, and where did you find them?",
          tool = P1Tool.REPORT_ISSUE,
          arguments = mapOf("category" to "damage", "semantic_query" to "Metro Zip Wallet"),
          missing = listOf("quantity", "location"),
        ),
      )
    val request = session.request("About ten of them")
    val stray =
      P1BInference(
        text = "",
        toolCalls = listOf(ParsedNativeToolCall(P1Tool.INVENTORY_SEARCH, mapOf("semantic_query" to "Metro Zip Wallet"))),
        elapsedMillis = 10,
        promptTokensPerSecond = null,
        decodeTokensPerSecond = null,
        rawOutput = "{}",
        finishReason = "tool_calls",
        malformedToolCall = false,
      )

    val guarded = request.enforcePendingWorkflow(stray)

    assertEquals(P1Tool.REPORT_ISSUE, guarded.toolCalls.single().tool)
    assertEquals("Metro Zip Wallet", guarded.toolCalls.single().arguments["semantic_query"])
    assertEquals("workflow_guard", guarded.finishReason)
  }

  @Test
  fun `session stops after four turns or a confirmed write closes it`() {
    assertEquals(4, P1ConversationSession.MAX_TURNS)
    var session = P1ConversationSession()
    repeat(P1ConversationSession.MAX_TURNS) { index ->
      session = session.record("turn $index", result(message = "continue", tool = P1Tool.NONE))
    }
    assertFalse(session.canContinue)

    val closed =
      P1ConversationSession()
        .record("report damage", result(message = "proposal", tool = P1Tool.REPORT_ISSUE))
        .close()
    assertFalse(closed.canContinue)
  }

  private fun result(
    message: String,
    tool: P1Tool,
    arguments: Map<String, String> = emptyMap(),
    missing: List<String> = emptyList(),
  ) =
    P1AgentResult(
      sourceTranscript = "source",
      normalizedQuery = "source",
      prediction = EncoderPrediction(tool, "p1b_generation", P1Risk.SAFE, 1.0, missing),
      candidates = emptyList(),
      proposal = null,
      toolCalls =
        if (tool == P1Tool.NONE) emptyList()
        else listOf(ToolCallTrace(tool, arguments, ToolCallState.CANCELLED, "Missing: ${missing.joinToString()}")),
      message = message,
      timings = P1Timings(),
    )
}
