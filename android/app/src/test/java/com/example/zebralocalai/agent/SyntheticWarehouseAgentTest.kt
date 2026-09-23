package com.example.zebralocalai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticWarehouseAgentTest {
  private val agent = SyntheticWarehouseAgent()

  @Test
  fun `search returns local inventory without confirmation`() {
    val result = agent.process("Where is SKU 1842?")

    assertEquals(P1Tool.INVENTORY_SEARCH, result.prediction.tool)
    assertEquals(P1Risk.SAFE, result.prediction.risk)
    assertTrue(result.candidates.isNotEmpty())
    assertTrue(result.message.contains("available"))
    assertEquals(P1Tool.INVENTORY_SEARCH, result.toolCalls.single().tool)
    assertEquals(ToolCallState.VERIFIED, result.toolCalls.single().state)
  }

  @Test
  fun `complete damage report produces confirmable proposal`() {
    val result = agent.process("Report 3 damaged cartons of SKU 5520 in B7")

    assertEquals(P1Tool.REPORT_ISSUE, result.prediction.tool)
    assertEquals(emptyList<String>(), result.prediction.missingFields)
    assertEquals("3", result.proposal?.arguments?.get("quantity"))
    assertEquals("B7", result.proposal?.arguments?.get("location"))
    assertEquals("damaged_stock", result.proposal?.arguments?.get("category"))
    assertEquals("OPEN", result.proposal?.arguments?.get("status"))
    assertEquals("MEDIUM", result.proposal?.arguments?.get("priority"))
    assertTrue(result.proposal?.arguments?.get("description").orEmpty().contains("Titan Shipping Carton"))
    assertEquals(ToolCallState.PROPOSED, result.toolCalls.single().state)
  }

  @Test
  fun `spoken bay number is not inferred as quantity`() {
    val result = agent.process("Report damaged cartons of SKU 5520 in bay B seven")

    assertEquals("B7", result.entities.location)
    assertTrue(result.prediction.missingFields.contains("quantity"))
  }

  @Test
  fun `confirmation stores and verifies report`() {
    val proposal = agent.process("Log 2 missing units of SKU 7311 in C4")
    val confirmed = agent.confirm(proposal)

    assertNotNull(confirmed.verification)
    assertTrue(confirmed.message.startsWith("Issue ISS-"))
    assertEquals(ToolCallState.VERIFIED, confirmed.toolCalls.single().state)
  }

  @Test
  fun `unsupported request executes no tool`() {
    val result = agent.process("Tell me a joke")

    assertEquals(P1Tool.NONE, result.prediction.tool)
    assertEquals(null, result.proposal)
  }
}
