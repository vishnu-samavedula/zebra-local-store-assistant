package com.example.zebralocalai.ui.industrial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformLevelTest {
  @Test fun silenceAndInvalidInputStayAtRest() {
    for (input in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, .0001f)) {
      assertEquals(0f, WaveformLevel.display(input), 0f)
    }
  }
  @Test fun softSpeechIsVisibleAndPeaksRemainOrdered() {
    val soft = WaveformLevel.display(.003f)
    val normal = WaveformLevel.display(.01f)
    val peak = WaveformLevel.display(.03f)
    assertTrue(soft > .3f)
    assertTrue(soft < normal && normal < peak)
  }
  @Test fun loudInputIsBounded() {
    assertEquals(1f, WaveformLevel.display(1f), 0f)
    assertEquals(1f, WaveformLevel.display(2f), 0f)
  }
}
