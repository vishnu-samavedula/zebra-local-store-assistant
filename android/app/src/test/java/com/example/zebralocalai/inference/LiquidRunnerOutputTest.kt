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
}
