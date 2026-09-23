package com.example.zebralocalai.agent

import java.util.Locale
import kotlin.math.max
import kotlin.system.measureNanoTime

/**
 * Synthetic P1A backend. The classifier is deliberately behind [P1IntentEncoder], so the
 * trained checkpoint can replace the contract simulator without changing the UI or tools.
 */
class SyntheticWarehouseAgent(
  private val encoder: P1IntentEncoder = ContractSimulatorEncoder(),
  private val repository: WarehouseRepository = InMemoryWarehouseRepository(),
) {
  fun process(transcript: String): P1AgentResult {
    val normalized = normalize(transcript)
    val entities = extractEntities(normalized)
    var candidates = emptyList<ProductCandidate>()
    val searchNanos = measureNanoTime { candidates = repository.search(normalized, entities.sku) }
    lateinit var prediction: EncoderPrediction
    val encoderNanos =
      measureNanoTime {
        prediction = encoder.classify(EncoderInput(transcript, entities, candidates))
      }

    val proposal = proposalFor(prediction, entities, candidates)
    val message = messageFor(prediction, entities, candidates)
    val toolCalls = toolTraceFor(prediction, entities, candidates, proposal, normalized)
    return P1AgentResult(
      sourceTranscript = transcript,
      normalizedQuery = normalized,
      entities = entities,
      prediction = prediction,
      candidates = candidates,
      proposal = proposal,
      toolCalls = toolCalls,
      message = message,
      timings =
        P1Timings(
          encoderMillis = nanosToDisplayMillis(encoderNanos),
          searchMillis = nanosToDisplayMillis(searchNanos),
        ),
    )
  }

  fun confirm(result: P1AgentResult): P1AgentResult {
    val proposal = requireNotNull(result.proposal) { "There is no action to confirm" }
    require(proposal.tool == P1Tool.REPORT_ISSUE) { "Unsupported state-changing tool" }
    lateinit var stored: StoredIssue
    val toolNanos =
      measureNanoTime {
        stored = repository.createIssue(proposal, result.sourceTranscript)
      }
    check(repository.getIssue(stored.issueId) == stored) { "Issue verification failed" }
    return result.copy(
      message = "Issue ${stored.issueId} created locally · ${stored.priority} · ${stored.status}.",
      verification = "Verified from local issue store · ${stored.description}",
      toolCalls =
        result.toolCalls.map {
          if (it.tool == P1Tool.REPORT_ISSUE) {
            it.copy(state = ToolCallState.VERIFIED, resultSummary = "${stored.issueId} · ${stored.status}")
          } else {
            it
          }
        },
      timings = result.timings.copy(toolMillis = nanosToDisplayMillis(toolNanos)),
    )
  }

  private fun toolTraceFor(
    prediction: EncoderPrediction,
    entities: ExtractedEntities,
    candidates: List<ProductCandidate>,
    proposal: ToolProposal?,
    normalizedQuery: String,
  ): List<ToolCallTrace> =
    when (prediction.tool) {
      P1Tool.INVENTORY_SEARCH ->
        listOf(
          ToolCallTrace(
            tool = P1Tool.INVENTORY_SEARCH,
            arguments =
              linkedMapOf<String, String>().apply {
                entities.sku?.let { put("sku", it) }
                put("query", normalizedQuery)
              },
            state = ToolCallState.VERIFIED,
            resultSummary = "${candidates.size} local match${if (candidates.size == 1) "" else "es"}",
          ),
        )
      P1Tool.REPORT_ISSUE ->
        proposal?.let {
          listOf(
            ToolCallTrace(
              tool = it.tool,
              arguments = it.arguments,
              state = ToolCallState.PROPOSED,
              resultSummary = "Waiting for confirmation",
            ),
          )
        } ?: emptyList()
      else -> emptyList()
    }

  private fun proposalFor(
    prediction: EncoderPrediction,
    entities: ExtractedEntities,
    candidates: List<ProductCandidate>,
  ): ToolProposal? {
    if (prediction.tool != P1Tool.REPORT_ISSUE || prediction.missingFields.isNotEmpty()) return null
    val product = candidates.firstOrNull()
    val category = entities.issueCategory ?: "general"
    val quantity = entities.quantity.toString()
    val location = entities.location.orEmpty()
    val productLabel = product?.let { "${it.name} ${it.variant}" } ?: entities.sku.orEmpty()
    return ToolProposal(
      tool = P1Tool.REPORT_ISSUE,
      arguments =
        linkedMapOf(
          "description" to "$quantity ${category.replace('_', ' ')} unit(s) for $productLabel at $location",
          "category" to category,
          "status" to "OPEN",
          "priority" to issuePriorityFor(category),
          "product_id" to (product?.productId ?: entities.sku.orEmpty()),
          "sku" to (product?.sku ?: entities.sku.orEmpty()),
          "quantity" to quantity,
          "location" to location,
        ),
    )
  }

  private fun messageFor(
    prediction: EncoderPrediction,
    entities: ExtractedEntities,
    candidates: List<ProductCandidate>,
  ): String {
    if (prediction.missingFields.isNotEmpty()) {
      return "I need ${prediction.missingFields.joinToString()} before I can continue."
    }
    return when (prediction.tool) {
      P1Tool.INVENTORY_SEARCH -> {
        val product = candidates.firstOrNull()
          ?: return "I couldn't find a matching product in the local catalog."
        val tied = candidates.filter { it.score == product.score }
        if (tied.size > 1) {
          "${tied.size} matching variants: " + tied.joinToString { "${it.name} ${it.variant} · ${it.available} at ${it.location}" }
        } else {
          "${product.name} ${product.variant} · ${product.available} available at ${product.location} " +
            "(${product.onHand} on hand, ${product.reserved} reserved)."
        }
      }
      P1Tool.REPORT_ISSUE -> {
        val product = candidates.firstOrNull()
        "Ready to report ${entities.quantity} ${entities.issueCategory ?: "affected"} unit(s)" +
          " for ${product?.name ?: entities.sku} at ${entities.location}."
      }
      P1Tool.LOCATION_CONTENTS,
      P1Tool.GET_TASK_STATUS,
      P1Tool.REQUEST_REPLENISHMENT -> "The trained P1B model is required for this tool."
      P1Tool.NONE -> "That request is outside the two P1A tools. No action was taken."
    }
  }

  private fun extractEntities(query: String): ExtractedEntities {
    val sku = Regex("(?:sku\\s*)?(\\d{4})", RegexOption.IGNORE_CASE).find(query)?.groupValues?.get(1)
    val location = Regex("\\b([a-z])\\s*-?\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE).find(query)?.let {
      "${it.groupValues[1].uppercase()}${it.groupValues[2]}"
    }
    val quantityToken =
      Regex(
          "\\b(\\d{1,3}|${numberWords.keys.joinToString("|")})\\s+(?:damaged|broken|crushed|missing|cartons?|units?|boxes?)\\b",
          RegexOption.IGNORE_CASE,
        )
        .find(query)
        ?.groupValues
        ?.get(1)
        ?.lowercase()
    val quantity = quantityToken?.toIntOrNull() ?: numberWords[quantityToken]
    val category = when {
      Regex("\\b(damaged|broken|crushed)\\b").containsMatchIn(query) -> "damaged_stock"
      Regex("\\b(missing|short|shortage)\\b").containsMatchIn(query) -> "inventory_discrepancy"
      Regex("\\b(blocked|obstruction)\\b").containsMatchIn(query) -> "blocked_location"
      else -> null
    }
    return ExtractedEntities(sku = sku, quantity = quantity, location = location, issueCategory = category)
  }

  private fun normalize(value: String): String =
    value
      .lowercase(Locale.US)
      .replace("trail blazer", "trailblaze")
      .replace(Regex("\\bbay\\s+([a-z])\\s+(${numberWords.keys.joinToString("|")})\\b")) {
        "${it.groupValues[1]}${numberWords[it.groupValues[2]]}"
      }
      .replace(Regex("\\bbay\\s+([a-z])\\s+(\\d+)\\b")) { "${it.groupValues[1]}${it.groupValues[2]}" }
      .replace(Regex("\\s+"), " ")
      .trim()

  private fun nanosToDisplayMillis(nanos: Long): Long = max(1, nanos / 1_000_000)

  companion object {
    private val numberWords =
      linkedMapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "twelve" to 12)

  }
}

/** Temporary no-generation implementation of the encoder contract used while its checkpoint is trained. */
class ContractSimulatorEncoder : P1IntentEncoder {
  override fun classify(input: EncoderInput): EncoderPrediction {
    val text = input.transcript.lowercase()
    val report = Regex("\\b(report|log|damaged|broken|crushed|missing|shortage|blocked)\\b").containsMatchIn(text)
    val search = Regex("\\b(where|find|stock|available|have|locate|check)\\b").containsMatchIn(text)
    val tool = when {
      report -> P1Tool.REPORT_ISSUE
      search -> P1Tool.INVENTORY_SEARCH
      else -> P1Tool.NONE
    }
    val missing = buildList {
      if (tool != P1Tool.NONE && input.catalogCandidates.isEmpty()) add("a recognizable product")
      if (
        tool == P1Tool.REPORT_ISSUE &&
          input.catalogCandidates.size > 1 &&
          input.catalogCandidates[0].score == input.catalogCandidates[1].score
      ) add("a specific product variant")
      if (tool == P1Tool.REPORT_ISSUE && input.entities.quantity == null) add("quantity")
      if (tool == P1Tool.REPORT_ISSUE && input.entities.location == null) add("location")
    }
    return EncoderPrediction(
      tool = tool,
      intent = when (tool) {
        P1Tool.INVENTORY_SEARCH -> "stock_check"
        P1Tool.REPORT_ISSUE -> "issue_report"
        P1Tool.LOCATION_CONTENTS -> "location_contents"
        P1Tool.GET_TASK_STATUS -> "task_status"
        P1Tool.REQUEST_REPLENISHMENT -> "replenishment"
        P1Tool.NONE -> "unsupported"
      },
      risk = if (tool == P1Tool.REPORT_ISSUE) P1Risk.CONFIRM_REQUIRED else P1Risk.SAFE,
      confidence = if (tool == P1Tool.NONE) 0.62 else 0.91,
      missingFields = missing,
    )
  }
}
