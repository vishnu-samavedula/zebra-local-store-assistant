package com.example.zebralocalai.agent

import com.example.zebralocalai.inference.P1BInference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedWorkflowSessionTest {
  private val repository = InMemoryWarehouseRepository()
  private val agent = GenerativeWarehouseAgent(repository)

  @Test
  fun `inventory workflow clarifies variant and ignores a model tool switch`() {
    val workflow = GroundedWorkflowSession(P1Tool.INVENTORY_SEARCH, repository)
    val firstText = "Check exact stock for TrailBlaze GTX."
    val first = agent.process(firstText, workflow.enforce(firstText, inference()), workflow.tool)

    assertEquals(P1Tool.INVENTORY_SEARCH, first.prediction.tool)
    assertTrue(first.prediction.missingFields.containsAll(listOf("color", "size")))

    val secondText = "Black, size 10."
    val wrongTool = inference(ParsedNativeToolCall(P1Tool.REPORT_ISSUE, mapOf("category" to "damage")))
    val second = agent.process("$firstText -> $secondText", workflow.enforce(secondText, wrongTool), workflow.tool)

    assertEquals(ToolCallState.VERIFIED, second.toolCalls.single().state)
    assertTrue(second.message.contains("TrailBlaze GTX Black 10"))
  }

  @Test
  fun `location workflow asks for and grounds an exact bin`() {
    val workflow = GroundedWorkflowSession(P1Tool.LOCATION_CONTENTS, repository)
    val firstText = "Show me the TrailBlaze GTX bin."
    val hallucinatedFilter =
      inference(ParsedNativeToolCall(P1Tool.LOCATION_CONTENTS, mapOf("semantic_query" to "Titan Shipping Carton")))
    val first = agent.process(firstText, workflow.enforce(firstText, hallucinatedFilter), workflow.tool)
    assertEquals(listOf("location"), first.prediction.missingFields)
    assertTrue("An unspoken model filter must not be retained", workflow.arguments()["semantic_query"] != "Titan Shipping Carton")

    val secondText = "A3-04."
    val second = agent.process("$firstText -> $secondText", workflow.enforce(secondText, inference()), workflow.tool)
    assertEquals(ToolCallState.VERIFIED, second.toolCalls.single().state)
    assertTrue(second.message.startsWith("A3-04:"))
  }

  @Test
  fun `task workflow requires a grounded task selector`() {
    val workflow = GroundedWorkflowSession(P1Tool.GET_TASK_STATUS, repository)
    val firstText = "Check my TrailBlaze task."
    val first = agent.process(firstText, workflow.enforce(firstText, inference()), workflow.tool)
    assertTrue(first.prediction.missingFields.isNotEmpty())

    val secondText = "Task T-104."
    val second = agent.process("$firstText -> $secondText", workflow.enforce(secondText, inference()), workflow.tool)
    assertEquals(ToolCallState.VERIFIED, second.toolCalls.single().state)
    assertTrue(second.message.contains("T-104"))
  }

  @Test
  fun `damage workflow retains product and grounds quantity and location`() {
    val workflow =
      GroundedWorkflowSession(
        P1Tool.REPORT_ISSUE,
        repository,
        GroundedWorkflowSession.seedArguments(P1Tool.REPORT_ISSUE, "damage"),
      )
    val firstText = "I want to report damage to TrailBlaze GTX."
    val first = agent.process(firstText, workflow.enforce(firstText, inference()), workflow.tool)
    assertTrue(first.prediction.missingFields.containsAll(listOf("quantity", "location")))

    val secondText = "3 at A3-05."
    val second = agent.process("$firstText -> $secondText", workflow.enforce(secondText, inference()), workflow.tool)
    assertEquals(P1Risk.CONFIRM_REQUIRED, second.prediction.risk)
    assertEquals("3", second.proposal?.arguments?.get("quantity"))
    assertEquals("A3-05", second.proposal?.arguments?.get("location"))
  }

  @Test
  fun `replenishment workflow resolves exact product before proposing`() {
    val workflow =
      GroundedWorkflowSession(
        P1Tool.REQUEST_REPLENISHMENT,
        repository,
        GroundedWorkflowSession.seedArguments(P1Tool.REQUEST_REPLENISHMENT),
      )
    val firstText = "We need more TrailBlaze GTX."
    val first = agent.process(firstText, workflow.enforce(firstText, inference()), workflow.tool)
    assertTrue(first.proposal == null)
    assertTrue(first.prediction.missingFields.isNotEmpty())

    val secondText = "Black size 10, add 4 to A3-05."
    val second = agent.process("$firstText -> $secondText", workflow.enforce(secondText, inference()), workflow.tool)
    assertEquals(P1Risk.CONFIRM_REQUIRED, second.prediction.risk)
    assertEquals("SKU-1843-BLK-100", second.proposal?.arguments?.get("sku"))
    assertEquals("4", second.proposal?.arguments?.get("quantity"))
    assertNotNull(second.proposal)
  }

  private fun inference(vararg calls: ParsedNativeToolCall) =
    P1BInference("", calls.toList(), 10, null, null, "{}", "stop", false)
}
