package com.example.zebralocalai.ui.industrial

import org.json.JSONArray
import org.json.JSONObject

/** Worker-facing text comes from validated harness results, never raw call syntax. */
object HarnessReplyFormatter {
  const val PROCEDURE_FOOTER = "For more safety procedures, open Operating Procedures on this device."

  fun format(outcome: JSONObject): String {
    var guidanceShown = false
    val reply = formatBody(outcome) { guidanceShown = true }
    return if (guidanceShown) "$reply\n\n$PROCEDURE_FOOTER" else reply
  }

  private fun formatBody(outcome: JSONObject, onGuidance: () -> Unit): String = buildString {
    val safety = outcome.optJSONArray("safety")
    appendGuidance(safety, this, onGuidance)
    val nested = outcome.optJSONArray("results")
    if (nested != null) {
      for (index in 0 until nested.length()) {
        if (isNotEmpty()) append("\n\n")
        append(formatBody(nested.getJSONObject(index), onGuidance))
      }
    } else {
      if (isNotEmpty()) append("\n\n")
      append(outcome.optString("message", "Please clarify your request."))
      outcome.optJSONObject("preview")?.let { preview ->
        append("\n")
        val arguments = preview.getJSONObject("arguments")
        arguments.keys().forEach { key ->
          append("\n${key.replace('_', ' ')}: ")
          val value = arguments.get(key)
          if (value is JSONObject) append(value.keys().asSequence().joinToString(", ") { "${it}: ${value.get(it)}" })
          else append(value)
        }
      }
      outcome.optJSONArray("items")?.let { items ->
        for (index in 0 until items.length()) {
          val item = items.getJSONObject(index)
          if (item.has("available")) {
            append("\n${item.getString("name")}")
            val variant = listOf(item.optString("color"), item.optString("size")).filter(String::isNotBlank)
            if (variant.isNotEmpty()) append(" · ${variant.joinToString(", ")}")
            append(" — ${item.getInt("available")} ${item.getString("unit")} available")
            append("; section ${item.getString("section")}, bin ${item.getInt("bin")}")
          } else if (item.has("variants")) append("\n${item.getString("name")}")
        }
      }
      outcome.optJSONArray("tasks")?.let { tasks ->
        append("\n${tasks.length()} assigned task${if (tasks.length() == 1) "" else "s"} in the local snapshot.")
        for (index in 0 until minOf(2, tasks.length())) {
          val task = tasks.getJSONObject(index)
          append("\n${task.getString("description")} · ${task.getString("status")}")
        }
      }
      if (outcome.has("tasks")) {
        append("\nSnapshot updated ${outcome.optString("snapshot_time", "time unavailable")}. No live synchronization.")
        append("\nOpen Task View for all assigned work and details.")
      }
      appendGuidance(outcome.optJSONObject("procedures")?.optJSONArray("cards"), this, onGuidance,
        safety?.let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet() }.orEmpty())
      outcome.optJSONObject("next_request")?.let { append("\n\n"); append(formatBody(it, onGuidance)) }
    }
  }.trim()

  private fun appendGuidance(cards: JSONArray?, text: StringBuilder, onGuidance: () -> Unit, alreadyShown: Set<String> = emptySet()) {
    if (cards == null) return
    for (index in 0 until cards.length()) {
      val card = cards.getJSONObject(index)
      if (card.getString("id") in alreadyShown) continue
      if (text.isNotEmpty()) text.append("\n\n")
      text.append(card.getString("title")).append("\n").append(card.getString("guidance"))
      onGuidance()
    }
  }
}
