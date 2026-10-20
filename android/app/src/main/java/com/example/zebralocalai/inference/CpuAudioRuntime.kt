package com.example.zebralocalai.inference

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** One app-owned llama.cpp process for the warehouse V2 test checkpoint, with text output only. */
class CpuAudioRuntime(context: Context) {
  private val appContext = context.applicationContext
  private val modelDirectory = File(appContext.filesDir, "models/warehouse-test/warehouse-v2-continue-20261019a")
  @Volatile private var request: HttpURLConnection? = null
  private val server = CpuModelServer(appContext, modelDirectory, MAIN, hashes, projector = PROJECTOR)

  companion object {
    private const val MAIN = "model-Q8_0.gguf"
    private const val PROJECTOR = "projector-Q8_0.gguf"
    private val hashes = mapOf(
      MAIN to "9bc63e89eaf2cb8cfbaf175fbb2f204d7f5cc0c2998cbd486744ab7858fee598",
      PROJECTOR to "171841e9e0485d08a4af4bcb999d9b7335feac5182990c4e794c7439e137ee2a",
    )
  }

  fun warmup() { ensureServer() }

  fun replyText(text: String, history: List<Pair<String, String>> = emptyList()): InferenceResult {
    require(text.isNotBlank())
    val messages = messages(history)
    messages.put(JSONObject().put("role", "user").put("content", text))
    return chat(messages)
  }

  fun replyAudio(audioFile: File, history: List<Pair<String, String>> = emptyList()): InferenceResult = chat(
    audioMessages(audioFile, history),
  )

  internal fun audioMessages(audioFile: File, history: List<Pair<String, String>>): JSONArray =
    messages(history).put(JSONObject().put("role", "user").put("content", JSONArray().put(
      JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
        .put("data", Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP))
        .put("format", "wav")),
    )))

  // This checkpoint was trained without a system prompt or a supplied tool list.
  private fun messages(history: List<Pair<String, String>> = emptyList()): JSONArray = JSONArray().also { messages ->
    history.takeLast(24).forEach { (role, content) ->
      require(role in setOf("user", "assistant", "tool"))
      require(content.isNotBlank())
      messages.put(JSONObject().put("role", role).put("content", content))
    }
  }

  @Synchronized
  private fun chat(messages: JSONArray): InferenceResult {
    val started = System.nanoTime()
    val serverPort = ensureServer()
    val payload = JSONObject().put("messages", messages).put("stream", true)
      .put("stream_options", JSONObject().put("include_usage", true))
      .put("max_tokens", 96).put("temperature", 0).put("cache_prompt", true)
    val connection = (URL("http://127.0.0.1:$serverPort/v1/chat/completions")
      .openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 5_000
      readTimeout = 180_000
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
    }
    request = connection
    val text = StringBuilder()
    val raw = StringBuilder()
    var firstTextAt: Long? = null
    var promptTokens: Int? = null
    var completionTokens: Int? = null
    var cachedTokens: Int? = null
    var finishReason: String? = null
    var decodeRate: Double? = null
    try {
      connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
      check(connection.responseCode in 200..299) {
        "Model request failed (${connection.responseCode}): " +
          connection.errorStream?.bufferedReader()?.use { it.readText().take(500) }.orEmpty()
      }
      connection.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
          if (!line.startsWith("data: ") || line == "data: [DONE]") return@forEach
          val event = JSONObject(line.removePrefix("data: "))
          raw.appendLine(event.toString())
          event.optJSONObject("usage")?.let { usage ->
            if (usage.has("prompt_tokens")) promptTokens = usage.getInt("prompt_tokens")
            if (usage.has("completion_tokens")) completionTokens = usage.getInt("completion_tokens")
            cachedTokens = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens")
          }
          event.optJSONObject("timings")?.let { timings ->
            if (timings.has("predicted_per_second")) decodeRate = timings.getDouble("predicted_per_second")
          }
          event.optJSONArray("choices")?.optJSONObject(0)?.let { choice ->
            choice.optString("finish_reason").takeUnless { it.isBlank() || it == "null" }
              ?.let { finishReason = it }
            val delta = choice.optJSONObject("delta")
            val chunk = if (delta == null || delta.isNull("content")) "" else delta.optString("content")
            if (chunk.isNotEmpty()) {
              if (firstTextAt == null) firstTextAt = System.nanoTime()
              text.append(chunk)
            }
          }
        }
      }
    } finally {
      request = null
      connection.disconnect()
    }
    check(text.isNotBlank()) { "The model returned no text. Try a shorter question." }
    val result = InferenceResult(
      text = text.toString().trim(), elapsedMillis = (System.nanoTime() - started) / 1_000_000,
      ttfsMillis = firstTextAt?.let { (it - started) / 1_000_000 },
      decodeTokensPerSecond = decodeRate, promptTokens = promptTokens,
      completionTokens = completionTokens, cachedPromptTokens = cachedTokens,
      rawOutput = raw.toString(), finishReason = finishReason,
    )
    Log.i("Audio600MCpu", "inputTokens=$promptTokens outputTokens=$completionTokens cachedTokens=$cachedTokens " +
      "firstTextMs=${result.ttfsMillis} totalMs=${result.elapsedMillis} decodeTps=$decodeRate finish=$finishReason")
    return result
  }

  private fun ensureServer(): Int = server.ensureServer()

  internal fun serverInstance(): Process? = server.serverInstance()
  internal fun diagnosticLog(): String = server.diagnosticLog()

  fun shutdown() { request?.disconnect(); server.shutdown() }
  fun cancelRequest() { request?.disconnect() }
}
