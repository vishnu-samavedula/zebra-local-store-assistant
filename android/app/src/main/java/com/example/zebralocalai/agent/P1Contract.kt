package com.example.zebralocalai.agent

enum class P1Tool(val wireName: String) {
  INVENTORY_SEARCH("inventory_search"),
  LOCATION_CONTENTS("location_contents"),
  GET_TASK_STATUS("get_task_status"),
  REPORT_ISSUE("report_issue"),
  REQUEST_REPLENISHMENT("request_replenishment"),
  NONE("none"),
}

enum class P1Risk { SAFE, CONFIRM_REQUIRED, BLOCKED }

enum class ToolCallState { PROPOSED, EXECUTED, VERIFIED, CANCELLED }

data class ToolCallTrace(
  val tool: P1Tool,
  val arguments: Map<String, String>,
  val state: ToolCallState,
  val resultSummary: String? = null,
)

data class ParsedNativeToolCall(
  val tool: P1Tool,
  val arguments: Map<String, String>,
)

data class ProductCandidate(
  val productId: String,
  val sku: String,
  val name: String,
  val variant: String,
  val location: String,
  val onHand: Int,
  val reserved: Int,
  val score: Double,
  val category: String = "",
  val brand: String = "",
  val color: String = "",
  val size: String = "",
  val unit: String = "each",
  val barcode: String = "",
  val attributesJson: String = "{}",
) {
  val available: Int get() = onHand - reserved
}

data class ExtractedEntities(
  val sku: String? = null,
  val quantity: Int? = null,
  val location: String? = null,
  val issueCategory: String? = null,
)

/** Legacy P1A boundary retained while P1B replaces the simulator with the generative model. */
data class EncoderInput(
  val transcript: String,
  val entities: ExtractedEntities,
  val catalogCandidates: List<ProductCandidate>,
)

data class EncoderPrediction(
  val tool: P1Tool,
  val intent: String,
  val risk: P1Risk,
  val confidence: Double,
  val missingFields: List<String>,
)

data class ToolProposal(
  val tool: P1Tool,
  val arguments: Map<String, String>,
)

data class P1Timings(
  val encoderMillis: Long = 0,
  val searchMillis: Long = 0,
  val toolMillis: Long = 0,
)

data class P1AgentResult(
  val sourceTranscript: String,
  val normalizedQuery: String,
  val entities: ExtractedEntities,
  val prediction: EncoderPrediction,
  val candidates: List<ProductCandidate>,
  val proposal: ToolProposal?,
  val toolCalls: List<ToolCallTrace> = emptyList(),
  val message: String,
  val verification: String? = null,
  val timings: P1Timings,
)

data class StoredIssue(
  val issueId: String,
  val description: String,
  val category: String,
  val status: String,
  val priority: String,
  val productId: String,
  val quantity: Int,
  val location: String,
  val sourceTranscript: String,
  val payloadJson: String,
  val createdAtEpochMillis: Long,
)

data class WarehouseTask(
  val taskId: String,
  val taskType: String,
  val status: String,
  val sku: String,
  val quantity: Int,
  val sourceLocation: String,
  val destinationLocation: String,
  val assignedWorker: String,
)

data class StoredReplenishment(
  val requestId: String,
  val productId: String,
  val sku: String,
  val quantity: Int,
  val unitsToMove: Int,
  val quantityMode: String,
  val destinationLocation: String,
  val reason: String,
  val taskId: String?,
  val status: String,
  val sourceTranscript: String,
  val payloadJson: String,
  val createdAtEpochMillis: Long,
)

interface WarehouseRepository {
  fun catalog(): List<ProductCandidate>

  fun search(query: String, skuHint: String?): List<ProductCandidate>

  fun locationContents(location: String, semanticQuery: String? = null): List<ProductCandidate>

  fun findTasks(taskId: String?, taskType: String?, status: String?): List<WarehouseTask>

  fun createIssue(proposal: ToolProposal, sourceTranscript: String): StoredIssue

  fun getIssue(issueId: String): StoredIssue?

  fun createReplenishment(proposal: ToolProposal, sourceTranscript: String): StoredReplenishment

  fun getReplenishment(requestId: String): StoredReplenishment?
}

interface P1IntentEncoder {
  fun classify(input: EncoderInput): EncoderPrediction
}
