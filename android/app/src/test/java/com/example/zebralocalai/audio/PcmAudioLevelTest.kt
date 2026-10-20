package com.example.zebralocalai.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAudioLevelTest {
  @Test fun silenceHasZeroLevel() { assertEquals(0f, PcmAudioLevel.normalized(ByteArray(20), 20), 0f) }
  @Test fun negativeFullScaleIsOne() { assertEquals(1f, PcmAudioLevel.normalized(byteArrayOf(0, 0x80.toByte()), 2), 0.0001f) }
  @Test fun halfScaleIsHalf() { assertEquals(.5f, PcmAudioLevel.normalized(byteArrayOf(0, 0x40), 2), 0.0001f) }
  @Test fun ignoresTrailingBytesOutsideTheRead() { assertEquals(0f, PcmAudioLevel.normalized(byteArrayOf(0, 0, 0, 0x80.toByte()), 2), 0f) }
  @Test fun incompleteSampleIsSilence() { assertEquals(0f, PcmAudioLevel.normalized(byteArrayOf(0x7f), 1), 0f) }
}
