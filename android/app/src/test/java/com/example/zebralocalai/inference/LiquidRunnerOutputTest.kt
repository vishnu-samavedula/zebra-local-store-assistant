package com.example.zebralocalai.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class LiquidRunnerOutputTest {
  @Test
  fun responseRemovesModelEndTokenAndNormalizesWhitespace() {
    assertEquals("I am here.", LiquidRunnerOutput.response("  I  am\n here.<|im_end|>"))
  }

  @Test
  fun responseRemovesInvisibleControlCharacters() {
    val output = "\u001B\u200BI am here."
    assertEquals("I am here.", LiquidRunnerOutput.response(output))
  }

  @Test
  fun nativeToolListIsRecoveredWhenLlamaCppStripsMarkers() {
    assertEquals(
      "<|tool_call_start|>[location_contents(location=\"D3-01\")]<|tool_call_end|>",
      normalizeLiquidNativeOutput("[location_contents(location=\"D3-01\")]"),
    )
  }

  @Test
  fun nativeToolListIsRecoveredBeforeFollowupProse() {
    assertEquals(
      "<|tool_call_start|>[get_task_status(task_id=\"T-122\")]<|tool_call_end|>",
      normalizeLiquidNativeOutput(
        "[get_task_status(task_id=\"T-122\")]Checking the task status.",
      ),
    )
  }

  @Test
  fun incompleteNativeToolListRemainsMalformed() {
    assertEquals(
      "[location_contents(location=\"D3-01\")",
      normalizeLiquidNativeOutput("[location_contents(location=\"D3-01\")"),
    )
  }
}
