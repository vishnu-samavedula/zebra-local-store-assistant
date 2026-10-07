package com.example.zebralocalai.agent

data class P1ConversationTurn(
  val workerText: String,
  val assistantText: String,
  val tool: P1Tool,
  val arguments: Map<String, String>,
  val missingFields: List<String>,
  val verifiedFacts: String?,
)

data class P1ConversationRequest(
  val modelInput: String,
  val auditTranscript: String,
  val turnNumber: Int,
)

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
    if (turns.isEmpty()) return P1ConversationRequest(latest, latest, 1)

    val previous = turns.last()
    val workerHistory =
      (turns.map(P1ConversationTurn::workerText) + latest)
        .joinToString(" ") { "${it.compact(MAX_TEXT_CHARS).trimEnd('.', '?', '!')}." }
    val verified = previous.verifiedFacts?.compact(MAX_FACT_CHARS)
    val pendingAction =
      previous.arguments.filterKeys { it in previous.tool.contextArgumentNames }.takeIf { it.isNotEmpty() }?.entries
        ?.joinToString(", ") { (key, value) -> "$key=$value" }
        ?.let { "Current action context from the previous step: ${previous.tool.wireName}($it)" }
        ?.compact(MAX_FACT_CHARS)
    val modelInput =
      listOfNotNull(
          workerHistory,
          verified?.let { "Verified local result from the previous step: $it" },
          pendingAction,
        )
        .joinToString(" ")
        .take(MAX_MODEL_INPUT_CHARS)

    return P1ConversationRequest(
      modelInput = modelInput,
      auditTranscript = (turns.map(P1ConversationTurn::workerText) + latest).joinToString(" -> "),
      turnNumber = turns.size + 1,
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
    const val MAX_TURNS = 3
    private const val MAX_TEXT_CHARS = 240
    private const val MAX_FACT_CHARS = 360
    private const val MAX_MODEL_INPUT_CHARS = 1_800
    private const val MAX_CANDIDATES = 3

    private val P1Tool.contextArgumentNames: Set<String>
      get() =
        when (this) {
          P1Tool.INVENTORY_SEARCH -> setOf("semantic_query", "sku", "color", "size", "location", "minimum_quantity")
          P1Tool.LOCATION_CONTENTS -> setOf("location", "semantic_query")
          P1Tool.GET_TASK_STATUS -> setOf("task_id", "task_type", "status")
          P1Tool.REPORT_ISSUE -> setOf("description", "category", "semantic_query", "sku", "quantity", "location", "task_id")
          P1Tool.REQUEST_REPLENISHMENT -> setOf("semantic_query", "sku", "quantity", "quantity_mode", "destination_location", "reason", "task_id")
          P1Tool.NONE -> emptySet()
        }

    private fun String.compact(limit: Int) =
      replace(Regex("\\s+"), " ").trim().let { if (it.length <= limit) it else "${it.take(limit - 1)}…" }
  }
}
