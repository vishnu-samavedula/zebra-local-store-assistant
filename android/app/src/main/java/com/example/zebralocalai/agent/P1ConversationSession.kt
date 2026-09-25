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
    val modelInput =
      if (verified == null) {
        workerHistory.take(MAX_MODEL_INPUT_CHARS)
      } else {
        "$workerHistory Verified local result from the previous step: $verified".take(MAX_MODEL_INPUT_CHARS)
      }

    return P1ConversationRequest(
      modelInput = modelInput,
      auditTranscript = (turns.map(P1ConversationTurn::workerText) + latest).joinToString(" -> "),
      turnNumber = turns.size + 1,
    )
  }

  fun record(workerText: String, result: P1AgentResult): P1ConversationSession {
    check(turns.size < MAX_TURNS) { "Conversation has reached its turn limit" }
    val arguments = result.proposal?.arguments ?: result.toolCalls.lastOrNull()?.arguments.orEmpty()
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

    private fun String.compact(limit: Int) =
      replace(Regex("\\s+"), " ").trim().let { if (it.length <= limit) it else "${it.take(limit - 1)}…" }
  }
}
