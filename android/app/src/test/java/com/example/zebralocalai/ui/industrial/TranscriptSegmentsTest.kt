package com.example.zebralocalai.ui.industrial

import com.example.zebralocalai.audio.TranscriptSegments
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptSegmentsTest {
  @Test fun committedWordsSurviveAnEmptyFinalSegment() {
    val segments = TranscriptSegments()
    segments.append("Request hoodies")
    segments.append("ink medium")
    assertEquals("Request hoodies ink medium", segments.complete())
    assertEquals("Request hoodies ink medium", segments.complete("ink medium"))
    assertEquals("Request hoodies ink medium ten", segments.complete("ten"))
  }
  @Test fun fullTranscriptIsNotDuplicated() {
    val segments = TranscriptSegments()
    segments.append("Request hoodies")
    assertEquals("Request hoodies ink medium", segments.complete("Request hoodies ink medium"))
  }
  @Test fun silenceDoesNotBecomeInventedWords() {
    val segments = TranscriptSegments()
    segments.append("  ")
    assertEquals(0, segments.count)
    assertEquals("", segments.complete())
  }
}
