package com.example.zebralocalai.inference

import android.content.Context
import android.util.Base64
import com.example.zebralocalai.agent.LiquidNativeToolParser
import com.example.zebralocalai.agent.P1ChatMessage
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

data class InferenceResult(
  val text: String,
  val elapsedMillis: Long,
  val ttfsMillis: Long?,
  val decodeTokensPerSecond: Double?,
  val promptTokens: Int?,
  val completionTokens: Int?,
  val cachedPromptTokens: Int?,
  val rawOutput: String,
  val finishReason: String? = null,
)

enum class AudioInferenceMode(val systemPrompt: String, val maxGeneratedTokens: Int) {
  ASR("Perform ASR.", 192),
}

class LiquidAudioRunner(private val context: Context) {
  companion object {
    private const val MAX_TOOL_TOKENS = 96
    private const val SERVER_START_TIMEOUT_MS = 60_000L
  }

  @Volatile private var serverProcess: Process? = null
  @Volatile private var serverPort: Int? = null
  @Volatile private var activeRequest: HttpURLConnection? = null
  private val serverLog = StringBuilder()

  fun warmup(bundle: ModelBundle) {
    check(bundle.isRunnable) { "The complete matched Audio-600M Q8 bundle is required" }
    ensureServer(bundle)
  }

  fun infer(
    bundle: ModelBundle,
    audioFile: File,
    mode: AudioInferenceMode = AudioInferenceMode.ASR,
  ): InferenceResult {
    check(bundle.isRunnable) { "The complete matched Audio-600M Q8 bundle is required" }
    val audio = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
    val messages =
      JSONArray()
        .put(JSONObject().put("role", "system").put("content", mode.systemPrompt))
        .put(
          JSONObject()
            .put("role", "user")
            .put(
              "content",
              JSONArray().put(
                JSONObject()
                  .put("type", "input_audio")
                  .put("input_audio", JSONObject().put("data", audio).put("format", "wav")),
              ),
            ),
        )
    return streamChat(bundle, messages, mode.maxGeneratedTokens)
  }

  /** Runs the accepted warehouse text checkpoint through the already-resident Audio-600M server. */
  fun inferText(bundle: ModelBundle, transcript: String): P1BInference =
    inferText(bundle, listOf(P1ChatMessage("user", transcript)))

  /** Preserves the alternating message history used by the checkpoint's multi-turn training. */
  fun inferText(bundle: ModelBundle, history: List<P1ChatMessage>): P1BInference {
    check(history.isNotEmpty() && history.last().role == "user" && history.last().content.isNotBlank()) {
      "A non-empty worker turn is required"
    }
    val messages =
      JSONArray()
        .put(JSONObject().put("role", "system").put("content", "Respond in function calls."))
        .also { array ->
          history.forEach { message ->
            check(message.role == "user" || message.role == "assistant") { "Unsupported chat role ${message.role}" }
            array.put(JSONObject().put("role", message.role).put("content", message.content))
          }
        }
    val result = streamChat(bundle, messages, MAX_TOOL_TOKENS)
    val nativeOutput = normalizeLiquidNativeOutput(result.text)
    val calls = LiquidNativeToolParser.parse(nativeOutput)
    val attemptedToolCall =
      result.text.trimStart().startsWith('[') ||
        result.text.contains("<|tool_call_start|>") ||
        result.text.contains("<|tool_call_end|>")
    val stoppedAtLimit = result.finishReason == "length"
    return P1BInference(
      text = if (calls.isEmpty()) result.text else "",
      toolCalls = calls,
      elapsedMillis = result.elapsedMillis,
      promptTokensPerSecond = null,
      decodeTokensPerSecond = result.decodeTokensPerSecond,
      rawOutput = result.rawOutput,
      finishReason = result.finishReason ?: "stop",
      malformedToolCall = stoppedAtLimit || (attemptedToolCall && calls.isEmpty()),
      promptTokens = result.promptTokens,
      predictedTokens = result.completionTokens,
      cachedTokens = result.cachedPromptTokens,
      predictedMillis = result.ttfsMillis?.let { (result.elapsedMillis - it).coerceAtLeast(0) },
    )
  }

  private fun streamChat(bundle: ModelBundle, messages: JSONArray, maxTokens: Int): InferenceResult {
    check(bundle.isRunnable) { "The complete matched Audio-600M Q8 bundle is required" }
    val overallStarted = System.nanoTime()
    val port = ensureServer(bundle)
    val request =
      JSONObject()
        .put("model", "")
        .put("messages", messages)
        .put("modalities", JSONArray().put("text"))
        .put("stream", true)
        .put("stream_options", JSONObject().put("include_usage", true))
        .put("max_tokens", maxTokens)
        .put("temperature", 0)
        .put("reset_context", true)

    val connection =
      (URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 5_000
        readTimeout = 180_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
    activeRequest = connection

    val text = StringBuilder()
    val raw = StringBuilder()
    var firstTextAt: Long? = null
    var lastTextAt: Long? = null
    var promptTokens: Int? = null
    var completionTokens: Int? = null
    var cachedPromptTokens: Int? = null
    var finishReason: String? = null

    try {
      connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
      val status = connection.responseCode
      check(status in 200..299) {
        val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        "Local model server returned HTTP $status: ${error.take(500)}"
      }

      connection.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
          if (!line.startsWith("data: ")) return@forEach
          val payload = line.removePrefix("data: ").trim()
          if (payload == "[DONE]") return@forEach
          raw.appendLine(payload)
          val event = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
          event.optJSONObject("usage")?.let { usage ->
            if (usage.has("prompt_tokens")) promptTokens = usage.optInt("prompt_tokens")
            if (usage.has("completion_tokens")) completionTokens = usage.optInt("completion_tokens")
            usage.optJSONObject("prompt_tokens_details")?.let { details ->
              if (details.has("cached_tokens")) cachedPromptTokens = details.optInt("cached_tokens")
            }
          }
          val choices = event.optJSONArray("choices") ?: return@forEach
          if (choices.length() == 0) return@forEach
          val choice = choices.optJSONObject(0) ?: return@forEach
          choice.optString("finish_reason").takeUnless { it.isBlank() || it == "null" }?.let { finishReason = it }
          val content = choice.optJSONObject("delta")?.optString("content").orEmpty()
          if (content.isNotEmpty()) {
            val now = System.nanoTime()
            if (firstTextAt == null) firstTextAt = now
            lastTextAt = now
            text.append(content)
          }
        }
      }
    } finally {
      activeRequest = null
      connection.disconnect()
    }

    val response = LiquidRunnerOutput.response(text.toString())
    check(response.isNotBlank()) {
      "The model returned no visible text; streamed events: ${raw.toString().takeLast(2_000)}"
    }
    val finishedAt = System.nanoTime()
    val first = firstTextAt
    val last = lastTextAt
    val decodedTokens = completionTokens
    val decodeRate =
      if (first != null && last != null && last > first && decodedTokens != null && decodedTokens > 1) {
        (decodedTokens - 1) * 1_000_000_000.0 / (last - first)
      } else {
        null
      }

    return InferenceResult(
      text = response,
      elapsedMillis = (finishedAt - overallStarted) / 1_000_000,
      ttfsMillis = first?.let { (it - overallStarted) / 1_000_000 },
      decodeTokensPerSecond = decodeRate,
      promptTokens = promptTokens,
      completionTokens = completionTokens,
      cachedPromptTokens = cachedPromptTokens,
      rawOutput = raw.toString(),
      finishReason = finishReason,
    )
  }

  @Synchronized
  private fun ensureServer(bundle: ModelBundle): Int {
    val existing = serverProcess
    val existingPort = serverPort
    if (existing != null && existing.isAlive && existingPort != null) return existingPort

    val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    val executable = File(nativeDirectory, "libllama-liquid-audio-server.so")
    check(executable.canExecute()) { "Liquid audio server is not installed in the APK" }
    val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val command =
      listOf(
        executable.absolutePath,
        "-m",
        bundle.main.absolutePath,
        "-mm",
        bundle.projector.absolutePath,
        "-mv",
        bundle.vocoder.absolutePath,
        "--tts-speaker-file",
        bundle.tokenizer.absolutePath,
        "--ctx-size",
        "4096",
        "-ngl",
        "0",
        "-t",
        "6",
        "-tb",
        "6",
        "--host",
        "127.0.0.1",
        "--port",
        port.toString(),
        "--offline",
      )
    val process =
      ProcessBuilder(command)
        .directory(nativeDirectory)
        .redirectErrorStream(true)
        .also { it.environment()["LD_LIBRARY_PATH"] = nativeDirectory.absolutePath }
        .start()
    serverProcess = process
    serverPort = port
    synchronized(serverLog) { serverLog.clear() }
    thread(name = "liquid-audio-server-log", isDaemon = true) {
      runCatching {
        process.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line ->
            synchronized(serverLog) {
              serverLog.appendLine(line)
              if (serverLog.length > 16_000) serverLog.delete(0, serverLog.length - 12_000)
            }
          }
        }
      }
    }

    val deadline = System.nanoTime() + SERVER_START_TIMEOUT_MS * 1_000_000
    while (System.nanoTime() < deadline) {
      check(process.isAlive) { "Local model server stopped during startup: ${logTail()}" }
      if (isServerReady(port)) return port
      Thread.sleep(100)
    }
    process.destroyForcibly()
    serverProcess = null
    serverPort = null
    error("Timed out while loading the local audio model: ${logTail()}")
  }

  fun cancel() {
    activeRequest?.disconnect()
    activeRequest = null
  }

  fun shutdown() {
    cancel()
    val process = serverProcess
    serverProcess = null
    process?.destroy()
    serverPort = null
  }

  internal fun diagnosticLogTail(maxChars: Int = 8_000): String =
    synchronized(serverLog) { serverLog.takeLast(maxChars).toString() }

  private fun isServerReady(port: Int): Boolean =
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

  private fun logTail(): String = diagnosticLogTail(1_000)
}

internal object LiquidRunnerOutput {
  private const val MAX_RESPONSE_CHARS = 500

  fun response(output: String): String {
    val cleaned =
      output
        .replace("<|im_end|>", "")
        .replace(Regex("[\\p{Cc}\\p{Cf}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    return if (cleaned.length <= MAX_RESPONSE_CHARS) cleaned
    else cleaned.take(MAX_RESPONSE_CHARS - 1).trimEnd() + "…"
  }
}
