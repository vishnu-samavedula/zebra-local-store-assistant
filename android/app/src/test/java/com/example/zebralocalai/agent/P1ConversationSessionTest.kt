package com.example.zebralocalai.agent

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
        arguments = mapOf("semantic_query" to "Northline safety vest", "quantity_mode" to "add"),
        missing = listOf("a positive quantity", "destination location"),
      )
    val session = P1ConversationSession().record("We need more Northline safety vests", first)

    val followUp = session.request("Make it 12 to B2-04")

    assertTrue(followUp.modelInput.contains("We need more Northline safety vests."))
    assertTrue(followUp.modelInput.contains("Make it 12 to B2-04"))
    assertEquals("We need more Northline safety vests -> Make it 12 to B2-04", followUp.auditTranscript)
  }

  @Test
  fun `session stops after three turns or a confirmed write closes it`() {
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
