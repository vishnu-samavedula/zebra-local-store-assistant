package com.example.zebralocalai.agent

/** Parses Liquid's native tool marker format into the app's allowlisted action boundary. */
object LiquidNativeToolParser {
  private const val START = "<|tool_call_start|>"
  private const val END = "<|tool_call_end|>"
  private val allowedTools = P1Tool.entries.filterNot { it == P1Tool.NONE }.associateBy { it.wireName }
  private val allowedArguments =
    mapOf(
      P1Tool.INVENTORY_SEARCH to setOf("semantic_query", "sku", "color", "size", "location", "minimum_quantity"),
      P1Tool.LOCATION_CONTENTS to setOf("location", "semantic_query"),
      P1Tool.GET_TASK_STATUS to setOf("task_id", "task_type", "status"),
      P1Tool.REPORT_ISSUE to setOf("description", "category", "semantic_query", "sku", "quantity", "location", "task_id"),
      P1Tool.REQUEST_REPLENISHMENT to setOf("semantic_query", "sku", "quantity", "quantity_mode", "destination_location", "reason", "task_id"),
    )

  fun parse(output: String): List<ParsedNativeToolCall> {
    val start = output.indexOf(START)
    val end = output.indexOf(END, startIndex = (start + START.length).coerceAtLeast(0))
    if (start < 0 || end < 0 || end <= start) return emptyList()
    val block = output.substring(start + START.length, end).trim()
    if (!block.startsWith('[') || !block.endsWith(']')) return emptyList()
    return parseCalls(block.substring(1, block.length - 1)) ?: emptyList()
  }

  private fun parseCalls(value: String): List<ParsedNativeToolCall>? {
    val calls = mutableListOf<ParsedNativeToolCall>()
    var cursor = 0
    while (cursor < value.length) {
      while (cursor < value.length && (value[cursor].isWhitespace() || value[cursor] == ',')) cursor++
      if (cursor >= value.length) break
      val nameStart = cursor
      while (cursor < value.length && (value[cursor].isLetterOrDigit() || value[cursor] == '_')) cursor++
      val wireName = value.substring(nameStart, cursor)
      val tool = allowedTools[wireName] ?: return null
      while (cursor < value.length && value[cursor].isWhitespace()) cursor++
      if (cursor >= value.length || value[cursor] != '(') return null
      val argumentStart = ++cursor
      var quote: Char? = null
      var escaped = false
      while (cursor < value.length) {
        val char = value[cursor]
        if (escaped) {
          escaped = false
        } else if (char == '\\' && quote != null) {
          escaped = true
        } else if (quote == null && (char == '\'' || char == '"')) {
          quote = char
        } else if (quote == char) {
          quote = null
        } else if (quote == null && char == ')') {
          break
        }
        cursor++
      }
      if (cursor >= value.length || quote != null) return null
      val arguments = parseArguments(tool, value.substring(argumentStart, cursor)) ?: return null
      calls += ParsedNativeToolCall(tool, arguments)
      cursor++
    }
    return calls.takeIf { it.isNotEmpty() }
  }

  private fun parseArguments(tool: P1Tool, value: String): Map<String, String>? {
    if (value.isBlank()) return emptyMap()
    val parts = mutableListOf<String>()
    var start = 0
    var quote: Char? = null
    var escaped = false
    value.forEachIndexed { index, char ->
      if (escaped) {
        escaped = false
      } else if (char == '\\' && quote != null) {
        escaped = true
      } else if (quote == null && (char == '\'' || char == '"')) {
        quote = char
      } else if (quote == char) {
        quote = null
      } else if (quote == null && char == ',') {
        parts += value.substring(start, index).trim()
        start = index + 1
      }
    }
    if (quote != null) return null
    parts += value.substring(start).trim()

    val parsed = linkedMapOf<String, String>()
    for (part in parts) {
      val separator = part.indexOf('=')
      if (separator <= 0) return null
      val key = part.substring(0, separator).trim()
      if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
      if (key in parsed) return null
      val raw = part.substring(separator + 1).trim()
      if (raw.equals("none", ignoreCase = true) || raw.equals("null", ignoreCase = true)) continue
      if (key !in allowedArguments.getValue(tool)) return null
      parsed[key] = unquote(raw) ?: return null
    }
    return parsed
  }

  /** Parses the server's JSON-string arguments without allowing duplicate or unknown keys. */
  fun parseJsonArguments(tool: P1Tool, raw: String): Map<String, String>? {
    val keys = topLevelObjectKeys(raw) ?: return null
    if (keys.size != keys.distinct().size || keys.any { it !in allowedArguments.getValue(tool) }) return null
    val json = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
    if (json.length() != keys.size) return null
    val parsed = linkedMapOf<String, String>()
    keys.forEach { key ->
      val value = json.opt(key)
      when (value) {
        null, org.json.JSONObject.NULL -> Unit
        is String, is Number, is Boolean -> parsed[key] = value.toString()
        else -> return null
      }
    }
    return parsed
  }

  private fun topLevelObjectKeys(raw: String): List<String>? {
    var cursor = 0
    fun skipWhitespace() { while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++ }
    fun readString(): String? {
      if (cursor >= raw.length || raw[cursor] != '"') return null
      cursor++
      val value = StringBuilder()
      var escaped = false
      while (cursor < raw.length) {
        val char = raw[cursor++]
        if (escaped) {
          value.append(char)
          escaped = false
        } else if (char == '\\') {
          escaped = true
        } else if (char == '"') {
          return value.toString()
        } else {
          value.append(char)
        }
      }
      return null
    }
    fun skipValue(): Boolean {
      var quote = false
      var escaped = false
      var nested = 0
      while (cursor < raw.length) {
        val char = raw[cursor]
        if (quote) {
          cursor++
          if (escaped) escaped = false
          else if (char == '\\') escaped = true
          else if (char == '"') quote = false
        } else {
          when (char) {
            '"' -> { quote = true; cursor++ }
            '{', '[' -> { nested++; cursor++ }
            '}', ']' -> {
              if (nested == 0) return true
              nested--
              cursor++
            }
            ',' -> if (nested == 0) return true else cursor++
            else -> cursor++
          }
        }
      }
      return !quote && nested == 0
    }

    skipWhitespace()
    if (cursor >= raw.length || raw[cursor++] != '{') return null
    val keys = mutableListOf<String>()
    while (true) {
      skipWhitespace()
      if (cursor < raw.length && raw[cursor] == '}') {
        cursor++
        skipWhitespace()
        return keys.takeIf { cursor == raw.length }
      }
      val key = readString() ?: return null
      skipWhitespace()
      if (cursor >= raw.length || raw[cursor++] != ':') return null
      keys += key
      skipWhitespace()
      if (!skipValue()) return null
      skipWhitespace()
      if (cursor >= raw.length) return null
      when (raw[cursor++]) {
        ',' -> Unit
        '}' -> {
          skipWhitespace()
          return keys.takeIf { cursor == raw.length }
        }
        else -> return null
      }
    }
  }

  private fun unquote(value: String): String? {
    if (value.length >= 2 && (value.first() == '\'' || value.first() == '"')) {
      if (value.last() != value.first()) return null
      return value.substring(1, value.length - 1)
        .replace("\\${value.first()}", value.first().toString())
        .replace("\\\\", "\\")
    }
    if (value.contains('(') || value.contains(')') || value.contains('[') || value.contains(']')) return null
    return value
  }
}
