package com.example.zebralocalai.agent

import com.example.zebralocalai.inference.P1BInference

data class P1ConversationTurn(
  val workerText: String,
  val assistantText: String,
  val tool: P1Tool,
  val arguments: Map<String, String>,
  val missingFields: List<String>,
  val verifiedFacts: String?,
)

data class P1ChatMessage(val role: String, val content: String)

data class P1ConversationRequest(
  val modelInput: String,
  val auditTranscript: String,
  val turnNumber: Int,
  val pendingCall: ParsedNativeToolCall? = null,
  val chatMessages: List<P1ChatMessage> = listOf(P1ChatMessage("user", modelInput)),
) {
  /**
   * Once a worker is filling an incomplete operation, a stray model-selected tool must not execute.
   * Preserve the model-originated pending call and let policy ask for the still-missing fields.
   */
  fun enforcePendingWorkflow(inference: P1BInference): P1BInference {
    val pending = pendingCall ?: return inference
    if (inference.toolCalls.size == 1 && inference.toolCalls.single().tool == pending.tool) {
      val current = inference.toolCalls.single()
      return inference.copy(toolCalls = listOf(current.copy(arguments = pending.arguments + current.arguments)))
    }
    if (inference.toolCalls.isEmpty()) return inference
    return inference.copy(
      text = "",
      toolCalls = listOf(pending),
      finishReason = "workflow_guard",
      malformedToolCall = false,
    )
  }
}

class P1ConversationSession private constructor(
  val turns: List<P1ConversationTurn>,
  val closed: Boolean,
) {
  constructor() : this(emptyList(), false)

  val canContinue: Boolean get() = !closed && turns.size < MAX_TURNS

  fun request(workerText: String): P1ConversationRequest {
    val latest = workerText.trim()
    require(latest.isNotBlank()) { "Worker input cannot be blank" }
    check(canContinue || turns.isEmpty()) { "Start a new request before adding another turn" }
    if (turns.isEmpty()) {
      return P1ConversationRequest(
        modelInput = latest,
        auditTranscript = latest,
        turnNumber = 1,
        chatMessages = listOf(P1ChatMessage("user", latest)),
      )
    }

    val priorWorkerHistory =
      turns
        .map(P1ConversationTurn::workerText)
        .joinToString(" ") { "${it.compact(MAX_TEXT_CHARS).trimEnd('.', '?', '!')}." }
    val previous = turns.last()
    val activeTool = turns.lastOrNull { it.tool != P1Tool.NONE && it.missingFields.isNotEmpty() }?.tool
    val activeTurns = activeTool?.let { tool -> turns.filter { it.tool == tool } }.orEmpty()
    val accumulatedArguments =
      activeTool
        ?.let { tool ->
          buildMap {
            activeTurns.forEach { turn ->
              turn.arguments
                .filterKeys { it in tool.contextArgumentNames }
                .filterValues(String::isNotBlank)
                .forEach { (key, value) -> put(key, value) }
            }
          }
        }
        .orEmpty()
    val latestActiveTurn = activeTurns.lastOrNull()
    val verified = previous.verifiedFacts?.compact(MAX_FACT_CHARS)
    val pendingAction =
      accumulatedArguments.takeIf { it.isNotEmpty() }?.entries
        ?.joinToString(", ") { (key, value) -> "$key=$value" }
        ?.let { "Pending action: ${activeTool?.wireName}($it)." }
        ?.compact(MAX_FACT_CHARS)
    val lastClarification =
      latestActiveTurn
        ?.takeIf { it.missingFields.isNotEmpty() }
        ?.assistantText
        ?.compact(MAX_CLARIFICATION_CHARS)
        ?.let { "Assistant previously asked: $it" }
    val missingFields =
      latestActiveTurn
        ?.missingFields
        ?.takeIf(List<String>::isNotEmpty)
        ?.joinToString(", ")
        ?.compact(MAX_MISSING_CHARS)
        ?.let { "Still needed: $it." }
    val modelInput =
      if (activeTool == null) {
        "$priorWorkerHistory ${latest.compact(MAX_TEXT_CHARS)}".trim()
      } else if (activeTool == P1Tool.REQUEST_REPLENISHMENT) {
        replenishmentContinuation(accumulatedArguments, latest)
      } else {
        listOfNotNull(
            priorWorkerHistory.takeIf(String::isNotBlank)?.let { "Worker request: $it" },
            pendingAction,
            "Continue only the pending ${activeTool.wireName}; do not switch tools.",
            lastClarification,
            missingFields,
            verified?.let { "Verified local context: $it" },
            "Worker update: ${latest.compact(MAX_TEXT_CHARS)}",
          )
          .joinToString(" ")
      }.take(MAX_MODEL_INPUT_CHARS)

    return P1ConversationRequest(
      modelInput = modelInput,
      auditTranscript = (turns.map(P1ConversationTurn::workerText) + latest).joinToString(" -> "),
      turnNumber = turns.size + 1,
      pendingCall = activeTool?.let { ParsedNativeToolCall(it, accumulatedArguments) },
      chatMessages =
        if (activeTool == P1Tool.REQUEST_REPLENISHMENT) {
          // This checkpoint is substantially more reliable when incomplete write slots are
          // consolidated into the same natural single-turn shape used by its strong eval slice.
          listOf(P1ChatMessage("user", modelInput))
        } else {
          buildList {
            turns.forEach { turn ->
              add(P1ChatMessage("user", turn.workerText))
              add(P1ChatMessage("assistant", turn.modelHistoryContent()))
            }
            add(P1ChatMessage("user", latest))
          }
        },
    )
  }

  fun record(workerText: String, result: P1AgentResult): P1ConversationSession {
    check(turns.size < MAX_TURNS) { "Conversation has reached its turn limit" }
    val arguments =
      result.proposal?.arguments
        ?: result.toolCalls.lastOrNull()
          ?.takeUnless { it.state == ToolCallState.VERIFIED }
          ?.arguments
          .orEmpty()
    val verifiedFacts =
      when {
        result.toolCalls.any { call -> call.state == ToolCallState.VERIFIED } || result.verification != null -> result.message
        result.prediction.tool == P1Tool.REPORT_ISSUE && result.candidates.commonProductFamily() != null ->
          "Recognized catalog product: ${result.candidates.commonProductFamily()}."
        result.candidates.isNotEmpty() ->
          "Candidate options: " +
            result.candidates.take(MAX_CANDIDATES).joinToString("; ") {
              "${it.name} ${it.color} ${it.size} (${it.sku}) at ${it.location}"
            }
        else -> null
      }
    val turn =
      P1ConversationTurn(
        workerText = workerText.trim(),
        assistantText = result.message,
        tool = result.prediction.tool,
        arguments = arguments,
        missingFields = result.prediction.missingFields,
        verifiedFacts = verifiedFacts,
      )
    return P1ConversationSession(turns + turn, closed = false)
  }

  fun close(): P1ConversationSession = P1ConversationSession(turns, closed = true)

  companion object {
    const val MAX_TURNS = 4
    private const val MAX_TEXT_CHARS = 240
    private const val MAX_FACT_CHARS = 360
    private const val MAX_CLARIFICATION_CHARS = 240
    private const val MAX_MISSING_CHARS = 180
    private const val MAX_MODEL_INPUT_CHARS = 1_800
    private const val MAX_CANDIDATES = 3

    private fun List<ProductCandidate>.commonProductFamily(): String? =
      map { it.name.trim() }.distinctBy(String::lowercase).singleOrNull()

    private val P1Tool.contextArgumentNames: Set<String>
      get() =
        when (this) {
          P1Tool.INVENTORY_SEARCH -> setOf("semantic_query", "sku", "color", "size", "location", "minimum_quantity")
          P1Tool.LOCATION_CONTENTS -> setOf("location", "semantic_query")
          P1Tool.GET_TASK_STATUS -> setOf("task_id", "task_type", "status")
          P1Tool.REPORT_ISSUE -> setOf("category", "semantic_query", "sku", "quantity", "location", "task_id")
          P1Tool.REQUEST_REPLENISHMENT -> setOf("semantic_query", "sku", "quantity", "quantity_mode", "destination_location", "reason", "task_id")
          P1Tool.NONE -> emptySet()
        }

    private fun String.compact(limit: Int) =
      replace(Regex("\\s+"), " ").trim().let { if (it.length <= limit) it else "${it.take(limit - 1)}…" }

    private fun P1ConversationTurn.modelHistoryContent(): String {
      if (tool == P1Tool.NONE) return assistantText.compact(MAX_CLARIFICATION_CHARS)
      val contextArguments = arguments.filterKeys { it in tool.contextArgumentNames }
      val renderedArguments =
        contextArguments.entries.joinToString(", ") { (key, value) ->
          val renderedValue =
            if (value.matches(Regex("-?\\d+(?:\\.\\d+)?"))) value
            else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
          "$key=$renderedValue"
        }
      return "<|tool_call_start|>[${tool.wireName}($renderedArguments)]<|tool_call_end|>"
    }

    private fun replenishmentContinuation(known: Map<String, String>, workerUpdate: String): String {
      val locationMatch = LOCATION_IN_UPDATE.find(workerUpdate)
      val destination = locationMatch?.value ?: known["destination_location"]
      var details = workerUpdate
      locationMatch?.let { details = details.removeRange(it.range) }

      val knownQuantity = known["quantity"]?.toIntOrNull()
      val updateQuantity = if (knownQuantity == null) NUMBER_IN_UPDATE.find(details)?.value?.toIntOrNull() else null
      val quantity = knownQuantity ?: updateQuantity
      if (knownQuantity == null && updateQuantity != null) {
        NUMBER_IN_UPDATE.find(details)?.let { details = details.removeRange(it.range) }
      }
      details =
        details
          .replace(UPDATE_FILLER, " ")
          .replace(Regex("\\s+"), " ")
          .trim(' ', '.', ',', ':', ';')

      val knownProduct = known["sku"] ?: known["semantic_query"]
      val product =
        listOf(details, knownProduct.orEmpty())
          .filter(String::isNotBlank)
          .distinctBy(String::lowercase)
          .joinToString(" ")
          .ifBlank { "the requested product" }
      val mode = known["quantity_mode"] ?: "add"
      val action =
        when {
          mode == "target_level" && quantity != null -> "set the target level to $quantity units of $product"
          quantity != null -> "add exactly $quantity units of $product"
          else -> "add $product"
        }
      return "Request replenishment: $action${destination?.let { " to $it" }.orEmpty()}."
    }

    private val LOCATION_IN_UPDATE = Regex("\\b[A-D]\\s*\\d{1,4}(?:\\s*(?:-|dash)\\s*\\d{1,4})?\\b", RegexOption.IGNORE_CASE)
    private val NUMBER_IN_UPDATE = Regex("\\b\\d+\\b")
    private val UPDATE_FILLER = Regex("(?i)\\b(make it|add|units?|send|destination|to|at|please)\\b")
  }
}
