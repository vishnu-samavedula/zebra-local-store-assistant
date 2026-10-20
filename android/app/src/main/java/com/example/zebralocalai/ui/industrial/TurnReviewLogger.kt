package com.example.zebralocalai.ui.industrial

import android.content.Context
import android.util.Log
import com.example.zebralocalai.inference.InferenceResult
import java.io.File
import org.json.JSONObject

/** Bounded private diagnostic records; never print worker content into Android logs. */
class TurnReviewLogger(context: Context) {
  private val file = File(context.filesDir, "warehouse-turn-review.jsonl")
  private val latencyReset = File(context.filesDir, "warehouse-latency-reset.txt")

  @Synchronized fun resetLatency() {
    latencyReset.writeText(System.currentTimeMillis().toString())
  }

  @Synchronized fun latencySamples(): List<LatencySample> {
    val resetAt = if (latencyReset.isFile) latencyReset.readText().toLong() else -1L
    return if (!file.isFile) emptyList() else file.readLines().takeLast(64).mapNotNull { line ->
      runCatching {
        val row = JSONObject(line)
        val total = row.optLong("total_ms", -1)
        val mode = row.optString("input_mode")
        if (row.optLong("time_ms", -1) <= resetAt || row.optBoolean("control_turn") || mode !in setOf("audio", "text") || total < 0) null
        else LatencySample(mode, total, row.optLong("first_text_ms", -1).takeIf { it in 0..total })
      }.getOrNull()
    }
  }

  @Synchronized fun append(chat: String, event: String, transcript: String, mode: String,
                           outcome: JSONObject, inference: InferenceResult?, timings: JSONObject? = null) {
    runCatching {
      val row = JSONObject().put("time_ms", System.currentTimeMillis()).put("chat", chat)
        .put("event", event).put("input_mode", mode).put("transcript", transcript)
        .put("control_turn", inference == null).put("outcome", outcome)
      timings?.let { row.put("processing_timings", it) }
      inference?.let {
        row.put("model_output", it.text).put("finish_reason", it.finishReason)
          .put("input_tokens", it.promptTokens).put("output_tokens", it.completionTokens)
          .put("first_text_ms", it.ttfsMillis).put("total_ms", it.elapsedMillis)
      }
      val previous = if (file.isFile) file.readLines().takeLast(63) else emptyList()
      file.writeText((previous + row.toString()).joinToString("\n", postfix = "\n"))
    }.onFailure { Log.w("WarehouseTurnReview", "Private diagnostic record unavailable", it) }
  }
}
