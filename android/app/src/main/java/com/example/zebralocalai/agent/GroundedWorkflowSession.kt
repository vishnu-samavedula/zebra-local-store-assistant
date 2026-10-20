package com.example.zebralocalai.agent

import com.example.zebralocalai.inference.P1BInference
import java.util.Locale

/**
 * App-owned state for a recipe-selected workflow.
 *
 * The model may propose fields, but it cannot switch the selected tool or make an ungrounded
 * identifier executable. Canonical catalog and location values come from the local repository.
 */
class GroundedWorkflowSession(
  val tool: P1Tool,
  private val repository: WarehouseRepository,
  seedArguments: Map<String, String> = emptyMap(),
) {
  private val knownArguments = seedArguments.toMutableMap()
  private val workerTurns = mutableListOf<String>()

  init {
    require(tool != P1Tool.NONE) { "A grounded workflow requires a warehouse tool" }
  }

  fun enforce(workerText: String, inference: P1BInference): P1BInference {
    workerTurns += workerText.trim()
    val fullWorkerText = workerTurns.joinToString(" ")
    val modelArguments = inference.toolCalls.singleOrNull { it.tool == tool }?.arguments.orEmpty()
    mergeGroundedModelArguments(modelArguments, fullWorkerText)
    extractDeterministicArguments(fullWorkerText, workerText)
    return inference.copy(
      text = "",
      toolCalls = listOf(ParsedNativeToolCall(tool, knownArguments.toMap())),
      finishReason = "grounded_workflow",
      malformedToolCall = false,
    )
  }

  fun arguments(): Map<String, String> = knownArguments.toMap()

  private fun mergeGroundedModelArguments(model: Map<String, String>, fullText: String) {
    val allowed = tool.argumentNames
    model.filterKeys { it in allowed }.forEach { (key, value) ->
      if (value.isBlank() || value.equals("none", ignoreCase = true)) return@forEach
      when (key) {
        "sku" -> repository.catalog().firstOrNull { it.sku.equals(value, true) && fullText.grounds(value) }?.let { knownArguments[key] = it.sku }
        "semantic_query" -> {
          val candidates = repository.search(value, null)
          val family = candidates.commonProductFamily()
          if (family != null && fullText.mentionsProductFamily(family)) {
            knownArguments[key] = family
          }
        }
        "location", "destination_location" -> {
          val canonical = WarehouseLocationNormalizer.canonicalizeOrOriginal(value, repository.catalog().map(ProductCandidate::location))
          if (fullText.grounds(canonical)) knownArguments[key] = canonical
        }
        "task_id" -> canonicalTaskId(value)?.takeIf { fullText.grounds(it) }?.let { knownArguments[key] = it }
        "task_type" -> value.lowercase(Locale.US).takeIf { it in TASK_TYPES && fullText.contains(it, true) }?.let { knownArguments[key] = it }
        "status" -> value.lowercase(Locale.US).takeIf { it in TASK_STATUSES && fullText.contains(it.replace('_', ' '), true) }?.let { knownArguments[key] = it }
        "quantity_mode" -> value.lowercase(Locale.US).takeIf { it in QUANTITY_MODES && fullText.containsMode(it) }?.let { knownArguments[key] = it }
        "category" -> if (knownArguments[key].isNullOrBlank()) knownArguments[key] = value
        "color", "size" -> if (fullText.contains(value, true)) knownArguments[key] = value
      }
    }
  }

  private fun extractDeterministicArguments(fullText: String, latestText: String) {
    val catalog = repository.catalog()
    val exactSku = catalog.firstOrNull { fullText.grounds(it.sku) }
    if (exactSku != null) knownArguments["sku"] = exactSku.sku

    val candidates =
      when {
        exactSku != null -> listOf(exactSku)
        !knownArguments["semantic_query"].isNullOrBlank() -> repository.search(knownArguments.getValue("semantic_query"), null)
        else -> repository.search(fullText, null)
      }
    val family = candidates.commonProductFamily()
    if (family != null && fullText.mentionsProductFamily(family)) {
      knownArguments.putIfAbsent("semantic_query", family)
    }

    val familyCandidates =
      knownArguments["semantic_query"]?.let { query -> catalog.filter { it.name.equals(query, true) } }
        .orEmpty()
        .ifEmpty { candidates }
    familyCandidates.map(ProductCandidate::color).distinct().singleOrNull { fullText.containsWordOrPhrase(it) }?.let {
      knownArguments["color"] = it
    }
    familyCandidates.map(ProductCandidate::size).distinct().singleOrNull { fullText.containsSize(it) }?.let {
      knownArguments["size"] = it
    }
    val exactProduct =
      familyCandidates.filter { candidate ->
        (knownArguments["color"].isNullOrBlank() || candidate.color.equals(knownArguments["color"], true)) &&
          (knownArguments["size"].isNullOrBlank() || candidate.size.equals(knownArguments["size"], true))
      }.singleOrNull()
    if (exactProduct != null && tool == P1Tool.REQUEST_REPLENISHMENT) knownArguments["sku"] = exactProduct.sku

    extractLocation(fullText)?.let { location ->
      val key = if (tool == P1Tool.REQUEST_REPLENISHMENT) "destination_location" else "location"
      knownArguments[key] = location
    }
    extractTaskId(fullText)?.let { knownArguments["task_id"] = it }

    if (tool in setOf(P1Tool.REPORT_ISSUE, P1Tool.REQUEST_REPLENISHMENT) && knownArguments["quantity"].isNullOrBlank()) {
      extractQuantity(latestText)?.let { knownArguments["quantity"] = it.toString() }
    }
    if (tool == P1Tool.REQUEST_REPLENISHMENT && knownArguments["quantity_mode"].isNullOrBlank()) {
      when {
        latestText.contains(Regex("(?i)\\btarget(?: level)?\\b")) -> knownArguments["quantity_mode"] = "target_level"
        latestText.contains(Regex("(?i)\\b(add|more|restock|replenish)\\b")) -> knownArguments["quantity_mode"] = "add"
      }
    }
  }

  private fun extractLocation(text: String): String? {
    val catalogLocations = repository.catalog().map(ProductCandidate::location).distinct()
    return catalogLocations.firstOrNull { text.grounds(it) }
      ?: modelLocationCandidate(text)?.let { WarehouseLocationNormalizer.canonicalizeOrOriginal(it, catalogLocations) }
        ?.takeIf { candidate -> catalogLocations.any { it.equals(candidate, true) } }
  }

  private fun modelLocationCandidate(text: String): String? =
    LOCATION.find(text)?.value

  companion object {
    fun seedArguments(tool: P1Tool, issueCategory: String? = null): Map<String, String> =
      buildMap {
        if (tool == P1Tool.REPORT_ISSUE && issueCategory != null) put("category", issueCategory)
        if (tool == P1Tool.REQUEST_REPLENISHMENT) put("quantity_mode", "add")
      }

    private val TASK_ID = Regex("(?i)\\bT\\s*(?:-|dash)?\\s*(\\d{1,4})\\b")
    private val SPOKEN_TASK_ID =
      Regex("(?i)\\bT\\s*(?:-|dash)?\\s*((?:zero|oh|one|two|three|four|five|six|seven|eight|nine)(?:[\\s-]+(?:zero|oh|one|two|three|four|five|six|seven|eight|nine)){0,3})\\b")
    private val LOCATION = Regex("(?i)\\b[A-D]\\s*\\d{1,4}(?:\\s*(?:-|dash)\\s*\\d{1,4})?\\b")
    private val QUANTITY_PATTERNS =
      listOf(
        Regex("(?i)^\\s*([a-z-]+|\\d+)\\s+(?:at|in)\\b"),
        Regex("(?i)\\b(?:add|target(?: level)?(?: of)?|make it)\\s+([a-z-]+|\\d+)\\b"),
        Regex("(?i)\\b([a-z-]+|\\d+)\\s+(?:units?|items?|of them|damaged)\\b"),
        Regex("(?i)^\\s*([a-z-]+|\\d+)\\s*[.!?]?\\s*$"),
      )
    private val TASK_TYPES = setOf("pick", "putaway", "replenish", "receive", "cycle_count")
    private val TASK_STATUSES = setOf("assigned", "in_progress", "blocked", "complete")
    private val QUANTITY_MODES = setOf("add", "target_level")
    private val DIGIT_WORDS =
      mapOf(
        "ZERO" to "0", "OH" to "0", "ONE" to "1", "TWO" to "2", "THREE" to "3",
        "FOUR" to "4", "FIVE" to "5", "SIX" to "6", "SEVEN" to "7", "EIGHT" to "8", "NINE" to "9",
      )
    private val NUMBER_WORDS =
      mapOf(
        "ZERO" to 0, "ONE" to 1, "TWO" to 2, "THREE" to 3, "FOUR" to 4,
        "FIVE" to 5, "SIX" to 6, "SEVEN" to 7, "EIGHT" to 8, "NINE" to 9,
        "TEN" to 10, "ELEVEN" to 11, "TWELVE" to 12, "THIRTEEN" to 13, "FOURTEEN" to 14,
        "FIFTEEN" to 15, "SIXTEEN" to 16, "SEVENTEEN" to 17, "EIGHTEEN" to 18, "NINETEEN" to 19,
        "TWENTY" to 20, "THIRTY" to 30, "FORTY" to 40, "FIFTY" to 50,
      )

    private val P1Tool.argumentNames: Set<String>
      get() =
        when (this) {
          P1Tool.INVENTORY_SEARCH -> setOf("semantic_query", "sku", "color", "size", "location", "minimum_quantity")
          P1Tool.LOCATION_CONTENTS -> setOf("location", "semantic_query")
          P1Tool.GET_TASK_STATUS -> setOf("task_id", "task_type", "status")
          P1Tool.REPORT_ISSUE -> setOf("category", "semantic_query", "sku", "quantity", "location", "task_id")
          P1Tool.REQUEST_REPLENISHMENT -> setOf("semantic_query", "sku", "quantity", "quantity_mode", "destination_location", "reason", "task_id")
          P1Tool.NONE -> emptySet()
        }

    private fun List<ProductCandidate>.commonProductFamily(): String? =
      map { it.name.trim() }.distinctBy(String::lowercase).singleOrNull()

    private fun canonicalTaskId(value: String): String? = extractTaskId(value)

    private fun extractTaskId(value: String): String? =
      TASK_ID.find(value)?.groupValues?.get(1)?.let { "T-$it" }
        ?: SPOKEN_TASK_ID.find(value)?.groupValues?.get(1)?.let { words ->
          val digits =
            words.split(Regex("[\\s-]+"))
              .mapNotNull { DIGIT_WORDS[it.uppercase(Locale.US)] }
              .joinToString("")
          digits.takeIf(String::isNotBlank)?.let { "T-$it" }
        }

    private fun extractQuantity(text: String): Int? =
      QUANTITY_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(text)?.groupValues?.get(1)?.let(::parseQuantity)
      }

    private fun parseQuantity(value: String): Int? =
      value.toIntOrNull()
        ?: NUMBER_WORDS[value.trim().uppercase(Locale.US).replace('-', ' ')]

    private fun String.containsMode(mode: String) =
      when (mode) {
        "target_level" -> contains(Regex("(?i)\\btarget(?: level)?\\b"))
        "add" -> contains(Regex("(?i)\\b(add|more|restock|replenish)\\b"))
        else -> false
      }

    private fun String.containsWordOrPhrase(value: String): Boolean =
      contains(Regex("(?i)(?:^|[^a-z0-9])${Regex.escape(value)}(?:$|[^a-z0-9])"))

    private fun String.mentionsProductFamily(family: String): Boolean {
      fun terms(value: String) =
        value.lowercase(Locale.US)
          .split(Regex("[^a-z0-9]+"))
          .filter { it.length >= 3 }
          .map { if (it.length > 4 && it.endsWith('s')) it.dropLast(1) else it }
          .toSet()
      return terms(this).intersect(terms(family)).isNotEmpty()
    }

    private fun String.containsSize(value: String): Boolean {
      if (contains(Regex("(?i)\\bsize\\s+${Regex.escape(value)}\\b"))) return true
      return trim().trim('.', ',', '!', '?').equals(value, true) || (value.equals("One Size", true) && contains("one size", true))
    }

    private fun String.grounds(value: String): Boolean {
      val source = WarehouseLocationNormalizer.key(this)
      val target = WarehouseLocationNormalizer.key(value)
      return target.isNotBlank() && source.contains(target)
    }
  }
}
