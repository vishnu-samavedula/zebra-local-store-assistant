package com.example.zebralocalai.audio

/** Keeps committed recognizer segments when a trailing silent segment has no match. */
internal class TranscriptSegments {
  private val segments = mutableListOf<String>()
  val count: Int get() = segments.size
  fun append(text: String) { text.trim().takeIf(String::isNotEmpty)?.let(segments::add) }
  fun complete(finalText: String = ""): String {
    val accumulated = segments.joinToString(" ")
    val final = finalText.trim()
    return when {
      accumulated.isEmpty() -> final
      final.isEmpty() || segments.last() == final -> accumulated
      final.startsWith(accumulated) -> final
      else -> "$accumulated $final"
    }
  }
}
