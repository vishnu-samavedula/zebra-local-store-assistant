package com.example.zebralocalai.ui.industrial

import org.junit.Assert.*
import org.junit.Test

class LatencyStatsTest {
  @Test fun emptyAndSingleSampleDoNotInventValues() {
    assertNull(latencyPercentile(emptyList(), 95))
    assertEquals(123L, latencyPercentile(listOf(123L), 50))
    assertEquals(123L, latencyPercentile(listOf(123L), 95))
  }
  @Test fun nearestRankPreservesTailLatency() {
    val values = (1L..20L).toList()
    assertEquals(10L, latencyPercentile(values, 50))
    assertEquals(19L, latencyPercentile(values, 95))
    assertEquals(1L, latencyPercentile(values, 0))
    assertEquals(20L, latencyPercentile(values, 100))
    assertEquals(900L, latencyPercentile(listOf(100L, 200L, 900L), 95))
  }
}
