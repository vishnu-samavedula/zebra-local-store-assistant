package com.example.zebralocalai.agent

import com.example.zebralocalai.inference.P1BInference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeWarehouseAgentTest {
  private val agent = GenerativeWarehouseAgent(InMemoryWarehouseRepository())

  @Test
  fun `executes every independent read call`() {
    val inference =
      inference(
        ParsedNativeToolCall(P1Tool.INVENTORY_SEARCH, mapOf("sku" to "SKU-1843-BLK-100")),
        ParsedNativeToolCall(P1Tool.LOCATION_CONTENTS, mapOf("location" to "A3-04")),
      )

    val result = agent.process("Check both", inference)

    assertEquals(2, result.toolCalls.size)
    assertTrue(result.toolCalls.all { it.state == ToolCallState.VERIFIED })
    assertTrue(result.message.contains("TrailBlaze GTX"))
  }

  @Test
  fun `isolates each inventory query in a parallel read`() {
    val transcript = "Check both TrailBlaze GTX and DockPro Pallet Wrap."
    val result =
      agent.process(
        transcript,
        inference(
          ParsedNativeToolCall(P1Tool.INVENTORY_SEARCH, mapOf("semantic_query" to "TrailBlaze GTX")),
          ParsedNativeToolCall(P1Tool.INVENTORY_SEARCH, mapOf("semantic_query" to "DockPro Pallet Wrap")),
        ),
      )

    val responses = result.message.lines()
    assertEquals(2, responses.size)
    assertTrue(responses[0].contains("TrailBlaze GTX"))
    assertTrue(!responses[0].contains("DockPro Pallet Wrap"))
    assertTrue(responses[1].contains("DockPro Pallet Wrap"))
    assertTrue(!responses[1].contains("TrailBlaze GTX"))
    assertTrue(result.toolCalls.all { it.resultSummary == "2 local matches" || it.resultSummary == "1 local match" })
  }

  @Test
  fun `task status reads local task adapter`() {
    val result =
      agent.process(
        "What is the status of T-104?",
        inference(ParsedNativeToolCall(P1Tool.GET_TASK_STATUS, mapOf("task_id" to "T-104"))),
      )

    assertTrue(result.message.contains("T-104"))
    assertTrue(result.message.contains("in progress"))
    assertEquals(ToolCallState.VERIFIED, result.toolCalls.single().state)
  }

  @Test
  fun `replenishment target computes shortfall then persists on confirmation`() {
    val proposal =
      agent.process(
        "Replenish black GTX to 12 at A3-05",
        inference(
          ParsedNativeToolCall(
            P1Tool.REQUEST_REPLENISHMENT,
            mapOf(
              "sku" to "SKU-1843-BLK-100",
              "quantity" to "12",
              "quantity_mode" to "target_level",
              "destination_location" to "A3-05",
            ),
          ),
        ),
      )

    assertEquals(P1Risk.CONFIRM_REQUIRED, proposal.prediction.risk)
    assertEquals("4", proposal.proposal?.arguments?.get("units_to_move"))
    val confirmed = agent.confirm(proposal)
    assertTrue(confirmed.message.startsWith("Replenishment REP-"))
    assertNotNull(confirmed.verification)
    assertEquals(ToolCallState.VERIFIED, confirmed.toolCalls.single().state)
  }

  @Test
  fun `malformed length output is blocked with safe response`() {
    val result =
      agent.process(
        "We are missing safety vests",
        P1BInference("", emptyList(), 10, null, null, "{}", "length", true),
      )

    assertEquals(P1Risk.BLOCKED, result.prediction.risk)
    assertTrue(result.message.contains("couldn't form a safe action"))
    assertTrue(result.toolCalls.isEmpty())
  }

  @Test
  fun `length limited output is blocked even when one call parsed`() {
    val result =
      agent.process(
        "Check several items",
        P1BInference(
          "",
          listOf(ParsedNativeToolCall(P1Tool.INVENTORY_SEARCH, mapOf("sku" to "SKU-1843-BLK-100"))),
          10,
          null,
          null,
          "{}",
          "length",
          true,
        ),
      )

    assertEquals(P1Risk.BLOCKED, result.prediction.risk)
    assertEquals(ToolCallState.CANCELLED, result.toolCalls.single().state)
  }

  @Test
  fun `mixed read and write block executes nothing`() {
    val result =
      agent.process(
        "Check and replenish",
        inference(
          ParsedNativeToolCall(P1Tool.INVENTORY_SEARCH, mapOf("sku" to "SKU-1843-BLK-100")),
          ParsedNativeToolCall(
            P1Tool.REQUEST_REPLENISHMENT,
            mapOf("sku" to "SKU-1843-BLK-100", "quantity" to "2", "quantity_mode" to "add", "destination_location" to "A3-05"),
          ),
        ),
      )

    assertEquals(P1Risk.BLOCKED, result.prediction.risk)
    assertTrue(result.toolCalls.all { it.state == ToolCallState.CANCELLED })
    assertEquals(null, result.proposal)
  }

  @Test
  fun `ambiguous issue product asks for an exact variant`() {
    val result =
      agent.process(
        "Report one damaged TrailBlaze GTX at A3",
        inference(
          ParsedNativeToolCall(
            P1Tool.REPORT_ISSUE,
            mapOf(
              "semantic_query" to "TrailBlaze GTX",
              "category" to "damage",
              "quantity" to "1",
              "location" to "A3",
            ),
          ),
        ),
      )

    assertEquals(P1Risk.SAFE, result.prediction.risk)
    assertTrue(result.prediction.missingFields.contains("one exact product or SKU"))
    assertEquals(null, result.proposal)
  }

  @Test
  fun `location blockage does not invent a product`() {
    val proposal =
      agent.process(
        "Report location Z9 blocked by a fallen pallet",
        inference(
          ParsedNativeToolCall(
            P1Tool.REPORT_ISSUE,
            mapOf("description" to "Fallen pallet blocks access", "category" to "blocked_location", "location" to "Z9"),
          ),
        ),
      )

    assertEquals(P1Risk.CONFIRM_REQUIRED, proposal.prediction.risk)
    assertTrue("product_id" !in checkNotNull(proposal.proposal).arguments)
    val confirmed = agent.confirm(proposal)
    assertTrue(confirmed.message.startsWith("Issue ISS-"))
  }

  private fun inference(vararg calls: ParsedNativeToolCall) =
    P1BInference("", calls.toList(), 25, 100.0, 30.0, "{}", "tool_calls", false)
}
