package com.example.zebralocalai.inference

import android.content.Context
import com.example.zebralocalai.BuildConfig
import com.example.zebralocalai.agent.LiquidNativeToolParser
import com.example.zebralocalai.agent.ParsedNativeToolCall
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

data class P1BInference(
  val text: String,
  val toolCalls: List<ParsedNativeToolCall>,
  val elapsedMillis: Long,
  val promptTokensPerSecond: Double?,
  val decodeTokensPerSecond: Double?,
  val rawOutput: String,
  val finishReason: String,
  val malformedToolCall: Boolean,
  val promptTokens: Int? = null,
  val predictedTokens: Int? = null,
  val cachedTokens: Int? = null,
  val promptMillis: Long? = null,
  val predictedMillis: Long? = null,
)

enum class ToolPromptMode { FULL_SCHEMA, COMPACT_SIGNATURES, SCHEMA_FREE }

class LiquidTextRunner(private val context: Context) {
  companion object {
    private const val SERVER_START_TIMEOUT_MS = 60_000L
    private const val TOOL_CALL_END = "<|tool_call_end|>"
    // Three Liquid-native read calls currently take about 160 tokens because the trained
    // checkpoint includes null optional arguments. Keep enough bounded headroom for all three.
    private const val MAX_GENERATED_TOKENS = 256
  }

  @Volatile private var serverProcess: Process? = null
  @Volatile private var serverPort: Int? = null
  @Volatile private var activeRequest: HttpURLConnection? = null
  private val serverLog = StringBuilder()
  private val nativeSystemPrompt by lazy {
    context.assets.open("warehouse_tool_agent_native_v1.md").bufferedReader().use { it.readText().trim() }
  }
  private val nativeToolDefinitions by lazy {
    JSONArray(context.assets.open("warehouse_tools_v1.json").bufferedReader().use { it.readText() })
  }

  fun warmup(model: P1BModel) {
    check(model.isRunnable) { "Import the trained P1B model before warming the store agent" }
    ensureServer(model)
  }

  fun infer(
    model: P1BModel,
    transcript: String,
    promptMode: ToolPromptMode = ToolPromptMode.FULL_SCHEMA,
  ): P1BInference {
    check(model.isRunnable) { "Import the trained P1B model before using the store agent" }
    val started = System.nanoTime()
    val port = ensureServer(model)
    val messages =
      JSONArray()
        .put(JSONObject().put("role", "system").put("content", systemPrompt(promptMode)))
        .put(JSONObject().put("role", "user").put("content", transcript))
    val templateRequest =
      JSONObject()
        .put("messages", messages)
        .put("add_generation_prompt", true)
        .also {
          if (promptMode == ToolPromptMode.FULL_SCHEMA) it.put("tools", nativeToolDefinitions)
        }
    // llama.cpp's completion tokenizer adds the GGUF-configured BOS token itself.
    val rendered = postJson(port, "/apply-template", templateRequest).getString("prompt")
    val request =
      JSONObject()
        .put("prompt", rendered)
        .put("n_predict", MAX_GENERATED_TOKENS)
        .put("temperature", 0)
        .put("repeat_penalty", 1.1)
        .put("repeat_last_n", 128)
        // A tool-call block is complete only after its full (possibly multi-call) array.
        // Stop there instead of decoding through the following assistant-turn boundary.
        .put("stop", JSONArray().put(TOOL_CALL_END).put("<|im_end|>"))
        .put("cache_prompt", true)
    val response = postJson(port, "/completion", request)
    val content = response.optString("content").takeUnless { it == "null" }.orEmpty().trim()
    val nativeOutput = normalizeLiquidNativeOutput(content)
    val calls = LiquidNativeToolParser.parse(nativeOutput)
    val stopType = response.optString("stop_type", "stop")
    val stoppedAtLimit = response.optBoolean("stopped_limit", false) || stopType == "limit"
    val attemptedToolCall =
      content.startsWith('[') || content.contains("<|tool_call_start|>") || content.contains("<|tool_call_end|>")
    val timings = response.optJSONObject("timings")
    return P1BInference(
      text = if (calls.isEmpty()) content else "",
      toolCalls = calls,
      elapsedMillis = (System.nanoTime() - started) / 1_000_000,
      promptTokensPerSecond = timings?.optDoubleOrNull("prompt_per_second"),
      decodeTokensPerSecond = timings?.optDoubleOrNull("predicted_per_second"),
      rawOutput = response.toString(),
      finishReason = if (stoppedAtLimit) "length" else stopType,
      malformedToolCall = stoppedAtLimit || (attemptedToolCall && calls.isEmpty()),
      promptTokens = timings?.optIntOrNull("prompt_n"),
      predictedTokens = timings?.optIntOrNull("predicted_n"),
      cachedTokens = response.optIntOrNull("tokens_cached"),
      promptMillis = timings?.optDoubleOrNull("prompt_ms")?.toLong(),
      predictedMillis = timings?.optDoubleOrNull("predicted_ms")?.toLong(),
    )
  }

  private fun postJson(port: Int, path: String, request: JSONObject): JSONObject {
    val connection =
      (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 5_000
        readTimeout = 180_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
    activeRequest = connection
    try {
      connection.outputStream.use { it.write(request.toString().toByteArray()) }
      val status = connection.responseCode
      val body =
        (if (status in 200..299) connection.inputStream else connection.errorStream)
          .bufferedReader()
          .use { it.readText() }
      check(status in 200..299) { "Local P1B server returned HTTP $status: ${body.take(500)}" }
      return JSONObject(body)
    } finally {
      activeRequest = null
      connection.disconnect()
    }
  }

  private fun systemPrompt(mode: ToolPromptMode): String =
    when (mode) {
      ToolPromptMode.FULL_SCHEMA -> nativeSystemPrompt
      ToolPromptMode.COMPACT_SIGNATURES -> COMPACT_SIGNATURE_PROMPT
      ToolPromptMode.SCHEMA_FREE -> SCHEMA_FREE_PROMPT
    }

  @Synchronized
  private fun ensureServer(model: P1BModel): Int {
    val existing = serverProcess
    val existingPort = serverPort
    if (existing != null && existing.isAlive && existingPort != null) return existingPort
    val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    val executableName =
      if (BuildConfig.USE_NEXT_TEXT_RUNTIME) {
        "libllama-text-server-next.so"
      } else {
        "libllama-text-server.so"
      }
    val executable = File(nativeDirectory, executableName)
    check(executable.canExecute()) { "The P1B text server is not installed in the APK" }
    val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val process =
      ProcessBuilder(
          executable.absolutePath,
          "-m", model.file.absolutePath,
          "--ctx-size", "4096",
          "-ngl", "0",
          "-t", "6",
          "-tb", "6",
          "--parallel", "1",
          "--host", "127.0.0.1",
          "--port", port.toString(),
          "--jinja",
        )
        .directory(nativeDirectory)
        .redirectErrorStream(true)
        .start()
    serverProcess = process
    serverPort = port
    synchronized(serverLog) { serverLog.clear() }
    thread(name = "p1b-server-log", isDaemon = true) {
      runCatching {
        process.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line ->
            synchronized(serverLog) {
              serverLog.appendLine(line)
              if (serverLog.length > 20_000) serverLog.delete(0, serverLog.length - 15_000)
            }
          }
        }
      }
    }
    val deadline = System.nanoTime() + SERVER_START_TIMEOUT_MS * 1_000_000
    while (System.nanoTime() < deadline) {
      check(process.isAlive) { "P1B server stopped during startup: ${logTail()}" }
      if (isReady(port)) return port
      Thread.sleep(100)
    }
    process.destroyForcibly()
    serverProcess = null
    serverPort = null
    error("Timed out loading P1B: ${logTail()}")
  }

  fun cancel() {
    activeRequest?.disconnect()
    activeRequest = null
  }

  fun shutdown() {
    cancel()
    serverProcess?.destroy()
    serverProcess = null
    serverPort = null
  }

  private fun isReady(port: Int): Boolean =
    runCatching {
        val connection = URL("http://127.0.0.1:$port/v1/models").openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = 250
          connection.readTimeout = 250
          connection.responseCode in 200..499
        } finally {
          connection.disconnect()
        }
      }
      .getOrDefault(false)

  private fun logTail() = synchronized(serverLog) { serverLog.takeLast(1_500).toString() }

  private fun Double.takeUnlessNaN(): Double? = takeUnless { it.isNaN() }
  private fun JSONObject.optDoubleOrNull(name: String): Double? = optDouble(name, Double.NaN).takeUnlessNaN()
  private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
}

private const val COMPACT_SIGNATURE_PROMPT = """You are an offline warehouse tool agent on a Zebra handheld.
Use these learned tools and emit Liquid-native Pythonic calls:
inventory_search(semantic_query, sku, color, size, location, minimum_quantity);
location_contents(location, semantic_query); get_task_status(task_id, task_type, status);
report_issue(description, category, semantic_query, sku, quantity, location, task_id);
request_replenishment(semantic_query, sku, quantity, quantity_mode, destination_location, reason, task_id).
Allow up to three independent reads. A write must be the only call and is only a proposal.
Ask one short clarification when required information is missing. Never emit SQL or reasoning."""

private const val SCHEMA_FREE_PROMPT = """You are the offline warehouse tool agent on a Zebra handheld.

Use the warehouse tools learned during training. When a tool is needed, emit only a
Liquid-native Pythonic call block. Emit at most three independent read calls. A write must be the
only call in its block, is only a proposal, and will be confirmed by the app. Ask one short
clarification when required information is missing or when the worker requests more than one
write. Reject requests outside warehouse inventory, locations, tasks, issues, and replenishment.
Never invent identifiers, quantities, locations, results, or arguments. Never emit SQL, shell
commands, or hidden reasoning. After read results, answer in at most two short sentences using
only returned facts.

For replenishment, `target_level` means the requested quantity is the desired total stock level;
`add` means add the stated number of units."""

/**
 * llama.cpp omits Liquid's special tokens from `content`. The native model may legally emit
 * short prose after the fenced call, so recover only the leading balanced Pythonic call list.
 */
internal fun normalizeLiquidNativeOutput(content: String): String {
  val value = content.trim()
  if (!value.startsWith('[')) return value
  var depth = 0
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
    } else if (quote == null) {
      when (char) {
        '[' -> depth++
        ']' -> {
          depth--
          if (depth == 0) {
            val callList = value.substring(0, index + 1)
            return "<|tool_call_start|>$callList<|tool_call_end|>"
          }
        }
      }
    }
  }
  return value
}
