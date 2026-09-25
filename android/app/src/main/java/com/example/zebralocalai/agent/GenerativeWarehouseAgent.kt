package com.example.zebralocalai.agent

import com.example.zebralocalai.inference.P1BInference
import kotlin.math.max
import kotlin.system.measureNanoTime

class GenerativeWarehouseAgent(private val repository: WarehouseRepository) {
  fun process(transcript: String, inference: P1BInference): P1AgentResult {
    val calls = inference.toolCalls
    if (inference.malformedToolCall || inference.finishReason == "length") {
      return rejectedBlock(
        transcript,
        inference,
        calls,
        "I couldn't form a safe action. Please restate the product, quantity, and location.",
      )
    }
    if (calls.isEmpty()) {
      return result(
        transcript,
        P1Tool.NONE,
        P1Risk.SAFE,
        inference.text.ifBlank { "I need a little more detail." },
        inference = inference,
      )
    }
    if (calls.size > MAX_CALLS) {
      return rejectedBlock(transcript, inference, calls, "I can handle at most three read requests at once.")
    }
    val writes = calls.filter { it.tool in WRITE_TOOLS }
    if (writes.isNotEmpty() && calls.size != 1) {
      return rejectedBlock(
        transcript,
        inference,
        calls,
        "I can prepare only one write action at a time. Please choose the issue or replenishment request.",
      )
    }
    if (writes.size == 1) {
      val call = writes.single()
      return when (call.tool) {
        P1Tool.REPORT_ISSUE -> proposeIssue(transcript, call, inference)
        P1Tool.REQUEST_REPLENISHMENT -> proposeReplenishment(transcript, call, inference)
        else -> rejectedBlock(transcript, inference, calls, "That write action is not supported.")
      }
    }
    return executeReads(transcript, calls, inference)
  }

  fun confirm(result: P1AgentResult): P1AgentResult {
    val proposal = requireNotNull(result.proposal) { "There is no action to confirm" }
    return when (proposal.tool) {
      P1Tool.REPORT_ISSUE -> confirmIssue(result, proposal)
      P1Tool.REQUEST_REPLENISHMENT -> confirmReplenishment(result, proposal)
      else -> error("Only write proposals can be confirmed")
    }
  }

  private fun confirmIssue(result: P1AgentResult, proposal: ToolProposal): P1AgentResult {
    lateinit var stored: StoredIssue
    val nanos = measureNanoTime { stored = repository.createIssue(proposal, result.sourceTranscript) }
    check(repository.getIssue(stored.issueId) == stored)
    return result.copy(
      message = "Issue ${stored.issueId} created locally · ${stored.priority} · ${stored.status}.",
      verification = "Verified from local issue store · ${stored.description}",
      toolCalls = result.toolCalls.map { it.copy(state = ToolCallState.VERIFIED, resultSummary = "${stored.issueId} · ${stored.status}") },
      timings = result.timings.copy(toolMillis = nanos / 1_000_000),
    )
  }

  private fun confirmReplenishment(result: P1AgentResult, proposal: ToolProposal): P1AgentResult {
    lateinit var stored: StoredReplenishment
    val nanos = measureNanoTime { stored = repository.createReplenishment(proposal, result.sourceTranscript) }
    check(repository.getReplenishment(stored.requestId) == stored)
    return result.copy(
      message = "Replenishment ${stored.requestId} created locally · ${stored.unitsToMove} units · ${stored.status}.",
      verification = "Verified from local replenishment store · ${stored.sku} to ${stored.destinationLocation}",
      toolCalls = result.toolCalls.map { it.copy(state = ToolCallState.VERIFIED, resultSummary = "${stored.requestId} · ${stored.status}") },
      timings = result.timings.copy(toolMillis = nanos / 1_000_000),
    )
  }

  private fun executeReads(
    transcript: String,
    calls: List<ParsedNativeToolCall>,
    inference: P1BInference,
  ): P1AgentResult {
    val executions = calls.map { executeRead(it, transcript) }
    return result(
      transcript,
      calls.first().tool,
      P1Risk.SAFE,
      executions.joinToString("\n") { it.message },
      candidates = executions.flatMap { it.candidates }.distinctBy(ProductCandidate::productId),
      toolCalls = executions.map { it.trace },
      inference = inference,
      searchMillis = executions.sumOf { it.elapsedMillis },
    )
  }

  private fun executeRead(call: ParsedNativeToolCall, transcript: String): ReadExecution =
    when (call.tool) {
      P1Tool.INVENTORY_SEARCH -> executeInventory(call, transcript)
      P1Tool.LOCATION_CONTENTS -> executeLocation(call)
      P1Tool.GET_TASK_STATUS -> executeTaskStatus(call)
      else -> ReadExecution("Unsupported read call ${call.tool.wireName}.", emptyList(), ToolCallTrace(call.tool, call.arguments, ToolCallState.CANCELLED, "Rejected by policy"), 0)
    }

  private fun executeInventory(call: ParsedNativeToolCall, transcript: String): ReadExecution {
    val sku = call.arguments["sku"]
    val hasCallSpecificProduct = !call.arguments["semantic_query"].isNullOrBlank() || !sku.isNullOrBlank()
    val query =
      listOfNotNull(
          transcript.takeUnless { hasCallSpecificProduct },
          call.arguments["semantic_query"],
          call.arguments["color"],
          call.arguments["size"],
          call.arguments["location"],
          sku,
        )
        .filter(String::isNotBlank)
        .joinToString(" ")
    lateinit var candidates: List<ProductCandidate>
    val nanos = measureNanoTime { candidates = repository.search(query, sku?.removePrefix("SKU-")?.take(4)) }
    return ReadExecution(
      inventoryMessage(candidates),
      candidates,
      ToolCallTrace(call.tool, call.arguments, ToolCallState.VERIFIED, matchSummary(candidates)),
      nanos / 1_000_000,
    )
  }

  private fun executeLocation(call: ParsedNativeToolCall): ReadExecution {
    val location = call.arguments["location"].orEmpty()
    if (location.isBlank()) {
      return ReadExecution("Which location should I inspect?", emptyList(), ToolCallTrace(call.tool, call.arguments, ToolCallState.CANCELLED, "Missing location"), 0)
    }
    lateinit var candidates: List<ProductCandidate>
    val nanos = measureNanoTime { candidates = repository.locationContents(location, call.arguments["semantic_query"]) }
    val message =
      if (candidates.isEmpty()) "No inventory was found at $location."
      else "$location: ${candidates.joinToString("; ") { "${it.name} ${it.color} ${it.size} · ${it.available} available" }}"
    return ReadExecution(message, candidates, ToolCallTrace(call.tool, call.arguments, ToolCallState.VERIFIED, matchSummary(candidates)), nanos / 1_000_000)
  }

  private fun executeTaskStatus(call: ParsedNativeToolCall): ReadExecution {
    lateinit var tasks: List<WarehouseTask>
    val nanos = measureNanoTime {
      tasks = repository.findTasks(call.arguments["task_id"], call.arguments["task_type"], call.arguments["status"])
    }
    val message =
      if (tasks.isEmpty()) "No matching local tasks were found."
      else tasks.joinToString("\n") {
        "${it.taskId} · ${it.taskType.replace('_', ' ')} · ${it.status.replace('_', ' ')} · ${it.quantity} of ${it.sku} · ${it.sourceLocation} to ${it.destinationLocation}"
      }
    return ReadExecution(
      message,
      emptyList(),
      ToolCallTrace(call.tool, call.arguments, ToolCallState.VERIFIED, "${tasks.size} local task${if (tasks.size == 1) "" else "s"}"),
      nanos / 1_000_000,
    )
  }

  private fun proposeIssue(transcript: String, call: ParsedNativeToolCall, inference: P1BInference): P1AgentResult {
    val args = call.arguments
    val category = args["category"] ?: "general"
    val quantity = args["quantity"]
    val location = args["location"]
    val productRequired = category in setOf("damage", "damaged_stock", "discrepancy", "inventory_discrepancy")
    val productQuery = args["sku"] ?: args["semantic_query"] ?: transcript.takeIf { productRequired }
    val candidates =
      if (productQuery == null) emptyList()
      else repository.search(productQuery, args["sku"]?.removePrefix("SKU-")?.take(4))
    val missing = buildList {
      if (productRequired && candidates.size != 1) add("one exact product or SKU")
      if (!productRequired && productQuery != null && candidates.size > 1) add("one exact product or SKU")
      if (productRequired && quantity.isNullOrBlank()) add("quantity")
      if (location.isNullOrBlank()) add("location")
    }
    if (missing.isNotEmpty()) return clarification(transcript, call, inference, candidates, missing, "report")
    val product = candidates.singleOrNull()
    val enriched = linkedMapOf(
        "description" to (args["description"] ?: "${quantity ?: "Reported"} affected unit(s) at $location"),
        "category" to category,
        "status" to "OPEN",
        "priority" to issuePriorityFor(category),
        "location" to checkNotNull(location),
      )
    product?.let {
      enriched["product_id"] = it.productId
      enriched["sku"] = it.sku
    }
    quantity?.let { enriched["quantity"] = it }
    args["task_id"]?.let { enriched["task_id"] = it }
    return proposalResult(transcript, call.tool, "Issue proposal ready for review.", enriched, candidates, inference)
  }

  private fun proposeReplenishment(transcript: String, call: ParsedNativeToolCall, inference: P1BInference): P1AgentResult {
    val args = call.arguments
    val suppliedSku = args["sku"]
    val skuHint = suppliedSku?.removePrefix("SKU-")?.take(4)
    val searched = repository.search(args["semantic_query"] ?: suppliedSku ?: transcript, skuHint)
    val candidates =
      if (!suppliedSku.isNullOrBlank() && suppliedSku.startsWith("SKU-")) {
        searched.filter { it.sku.equals(suppliedSku, ignoreCase = true) }
      } else {
        searched
      }
    val quantity = args["quantity"]?.toIntOrNull()
    val mode = args["quantity_mode"]
    val destination = args["destination_location"]
    val missing = buildList {
      if (candidates.size != 1) add("one exact product or SKU")
      if (quantity == null || quantity <= 0) add("a positive quantity")
      if (mode !in setOf("add", "target_level")) add("quantity mode add or target level")
      if (destination.isNullOrBlank()) add("destination location")
    }
    if (missing.isNotEmpty()) return clarification(transcript, call, inference, candidates, missing, "replenishment")
    val product = candidates.single()
    val requested = checkNotNull(quantity)
    val unitsToMove = if (mode == "target_level") max(0, requested - product.available) else requested
    if (unitsToMove == 0) {
      return result(
        transcript,
        call.tool,
        P1Risk.SAFE,
        "${product.name} already has ${product.available} available at ${product.location}, meeting the target of $requested.",
        candidates = candidates,
        toolCalls = listOf(ToolCallTrace(call.tool, call.arguments, ToolCallState.CANCELLED, "Target already satisfied")),
        inference = inference,
      )
    }
    val enriched =
      linkedMapOf(
        "product_id" to product.productId,
        "sku" to product.sku,
        "product" to "${product.name} ${product.color} ${product.size}",
        "quantity" to requested.toString(),
        "units_to_move" to unitsToMove.toString(),
        "quantity_mode" to checkNotNull(mode),
        "destination_location" to checkNotNull(destination),
        "reason" to (args["reason"] ?: "Worker requested replenishment"),
        "status" to "REQUESTED",
      )
    args["task_id"]?.let { enriched["task_id"] = it }
    return proposalResult(transcript, call.tool, "Replenishment proposal ready for review.", enriched, candidates, inference)
  }

  private fun clarification(transcript: String, call: ParsedNativeToolCall, inference: P1BInference, candidates: List<ProductCandidate>, missing: List<String>, action: String) =
    result(
      transcript,
      call.tool,
      P1Risk.SAFE,
      "I need ${missing.joinToString()} before I can prepare the $action.",
      candidates = candidates,
      toolCalls = listOf(ToolCallTrace(call.tool, call.arguments, ToolCallState.CANCELLED, "Missing: ${missing.joinToString()}")),
      inference = inference,
      missing = missing,
    )

  private fun proposalResult(transcript: String, tool: P1Tool, message: String, arguments: Map<String, String>, candidates: List<ProductCandidate>, inference: P1BInference) =
    result(
      transcript,
      tool,
      P1Risk.CONFIRM_REQUIRED,
      message,
      candidates = candidates,
      proposal = ToolProposal(tool, arguments),
      toolCalls = listOf(ToolCallTrace(tool, arguments, ToolCallState.PROPOSED, "Waiting for confirmation")),
      inference = inference,
    )

  private fun rejectedBlock(transcript: String, inference: P1BInference, calls: List<ParsedNativeToolCall>, message: String) =
    result(
      transcript,
      calls.firstOrNull()?.tool ?: P1Tool.NONE,
      P1Risk.BLOCKED,
      message,
      toolCalls = calls.map { ToolCallTrace(it.tool, it.arguments, ToolCallState.CANCELLED, "Rejected by policy") },
      inference = inference,
    )

  private fun result(
    transcript: String,
    tool: P1Tool,
    risk: P1Risk,
    message: String,
    candidates: List<ProductCandidate> = emptyList(),
    proposal: ToolProposal? = null,
    toolCalls: List<ToolCallTrace> = emptyList(),
    inference: P1BInference? = null,
    missing: List<String> = emptyList(),
    searchMillis: Long = 0,
  ) =
    P1AgentResult(
      sourceTranscript = transcript,
      normalizedQuery = transcript,
      prediction = EncoderPrediction(tool, "p1b_generation", risk, 1.0, missing),
      candidates = candidates,
      proposal = proposal,
      toolCalls = toolCalls,
      message = message,
      timings = P1Timings(encoderMillis = inference?.elapsedMillis ?: 0, searchMillis = searchMillis),
    )

  private fun inventoryMessage(candidates: List<ProductCandidate>) =
    if (candidates.isEmpty()) "No matching local inventory was found."
    else candidates.joinToString("; ") { "${it.name} ${it.color} ${it.size} · ${it.available} available at ${it.location}" }

  private fun matchSummary(candidates: List<ProductCandidate>) =
    "${candidates.size} local match${if (candidates.size == 1) "" else "es"}"

  private data class ReadExecution(
    val message: String,
    val candidates: List<ProductCandidate>,
    val trace: ToolCallTrace,
    val elapsedMillis: Long,
  )

  companion object {
    private const val MAX_CALLS = 3
    private val WRITE_TOOLS = setOf(P1Tool.REPORT_ISSUE, P1Tool.REQUEST_REPLENISHMENT)
  }
}
