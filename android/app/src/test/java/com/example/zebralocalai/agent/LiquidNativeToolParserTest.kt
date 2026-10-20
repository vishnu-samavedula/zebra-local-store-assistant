package com.example.zebralocalai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidNativeToolParserTest {
  @Test
  fun `parses trained replenishment output and removes null optionals`() {
    val output =
      "<|tool_call_start|>[request_replenishment(semantic_query=None, color=None, " +
        "sku='SKU-1812-BLU-090', quantity=13, quantity_mode='target_level', " +
        "destination_location='A3-04', reason='Available quantity is below requested target')]<|tool_call_end|>"

    val call = LiquidNativeToolParser.parse(output).single()

    assertEquals(P1Tool.REQUEST_REPLENISHMENT, call.tool)
    assertEquals("SKU-1812-BLU-090", call.arguments["sku"])
    assertEquals("13", call.arguments["quantity"])
    assertEquals("target_level", call.arguments["quantity_mode"])
    assertTrue("semantic_query" !in call.arguments)
    assertTrue("color" !in call.arguments)
  }

  @Test
  fun `parses multiple independent read calls`() {
    val output =
      "<|tool_call_start|>[inventory_search(sku='SKU-1')," +
        "location_contents(location='B2-01')]<|tool_call_end|>"

    val calls = LiquidNativeToolParser.parse(output)

    assertEquals(listOf(P1Tool.INVENTORY_SEARCH, P1Tool.LOCATION_CONTENTS), calls.map { it.tool })
  }

  @Test
  fun `rejects non allowlisted tool`() {
    val output = "<|tool_call_start|>[delete_logs(path='/')]<|tool_call_end|>"

    assertTrue(LiquidNativeToolParser.parse(output).isEmpty())
  }

  @Test
  fun `rejects duplicate native arguments`() {
    val output =
      "<|tool_call_start|>[get_task_status(task_id='T-104', task_id='T-122')]<|tool_call_end|>"

    assertTrue(LiquidNativeToolParser.parse(output).isEmpty())
  }

  @Test
  fun `rejects duplicate and unknown structured JSON arguments`() {
    assertTrue(
      LiquidNativeToolParser.parseJsonArguments(
        P1Tool.GET_TASK_STATUS,
        "{\"task_id\":\"T-104\",\"task_id\":\"T-122\"}",
      ) == null,
    )
    assertTrue(
      LiquidNativeToolParser.parseJsonArguments(
        P1Tool.GET_TASK_STATUS,
        "{\"task_id\":\"T-104\",\"invented\":\"value\"}",
      ) == null,
    )
  }

}
