package com.example.zebralocalai.agent

import java.util.Locale

/** Canonicalizes a spoken or punctuation-free location only when it uniquely matches the catalog. */
object WarehouseLocationNormalizer {
  fun canonicalizeOrOriginal(value: String, knownLocations: Collection<String>): String {
    val trimmed = value.trim()
    val normalizedKey = key(trimmed)
    if (normalizedKey.isBlank()) return trimmed
    val matches = knownLocations.map(String::trim).distinctBy { it.uppercase(Locale.US) }.filter { key(it) == normalizedKey }
    return matches.singleOrNull() ?: trimmed
  }

  internal fun key(value: String): String {
    val expanded =
      value
        .uppercase(Locale.US)
        .split(Regex("[^A-Z0-9]+"))
        .filter(String::isNotBlank)
        .joinToString("") { token -> NUMBER_WORDS[token] ?: token }
    return expanded.replace(Regex("[^A-Z0-9]"), "")
  }

  private val NUMBER_WORDS =
    mapOf(
      "ZERO" to "0",
      "OH" to "0",
      "ONE" to "1",
      "TWO" to "2",
      "THREE" to "3",
      "FOUR" to "4",
      "FIVE" to "5",
      "SIX" to "6",
      "SEVEN" to "7",
      "EIGHT" to "8",
      "NINE" to "9",
      "DASH" to "",
      "HYPHEN" to "",
    )
}
