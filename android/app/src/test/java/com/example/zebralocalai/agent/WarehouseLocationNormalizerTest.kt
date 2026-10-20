package com.example.zebralocalai.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class WarehouseLocationNormalizerTest {
  private val known = listOf("A3-01", "A3-04", "B2-01", "B7")

  @Test
  fun canonicalizesCompactLocationAgainstCatalog() {
    assertEquals("A3-01", WarehouseLocationNormalizer.canonicalizeOrOriginal("a301", known))
    assertEquals("B2-01", WarehouseLocationNormalizer.canonicalizeOrOriginal("B201", known))
  }

  @Test
  fun canonicalizesSpokenDigitsAgainstCatalog() {
    assertEquals("A3-01", WarehouseLocationNormalizer.canonicalizeOrOriginal("A three oh one", known))
    assertEquals("A3-04", WarehouseLocationNormalizer.canonicalizeOrOriginal("A three dash oh four", known))
  }

  @Test
  fun preservesUnknownAndAmbiguousValues() {
    assertEquals("Z901", WarehouseLocationNormalizer.canonicalizeOrOriginal("Z901", known))
    assertEquals("A301", WarehouseLocationNormalizer.canonicalizeOrOriginal("A301", listOf("A3-01", "A30-1")))
  }
}
