package com.example.zebralocalai.ui.industrial

import kotlin.math.ceil

data class LatencySample(val mode: String, val totalMillis: Long, val firstTextMillis: Long?)

/** Nearest-rank empirical percentile; no smoothing or synthetic observations. */
internal fun latencyPercentile(sorted: List<Long>, percentile: Int): Long? {
  require(percentile in 0..100)
  if (sorted.isEmpty()) return null
  return sorted[(ceil(percentile / 100.0 * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
}
