package com.example.zebralocalai.ui.industrial

import kotlin.math.log10

/** Visual gain only: makes soft speech visible without modifying the recorded audio. */
internal object WaveformLevel {
  fun display(rms: Float): Float {
    if (!rms.isFinite() || rms <= 0f) return 0f
    // Map -60 to -12 dBFS into the ring's range, with extra visual sensitivity.
    return ((20f * log10(rms.coerceAtMost(1f)) + 60f) / 48f * 1.6f).coerceIn(0f, 1f)
  }
}
