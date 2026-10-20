package com.example.zebralocalai.inference

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime comparison on the reference checkpoint, not warehouse accuracy. */
@RunWith(AndroidJUnit4::class)
class PromptCacheBenchmarkDeviceTest {
  private fun message(role: String, content: Any) = JSONObject().put("role", role).put("content", content)
  private fun call(volume: Int) = "<|tool_call_start|>[set_radio(volume=$volume)]<|tool_call_end|>"
  private fun state(revision: Int) = message("tool", JSONObject().put("harness_state", JSONObject()
    .put("phase", "idle").put("focused_call", JSONObject().put("tool", "set_radio")
      .put("arguments", JSONObject().put("volume", revision))).put("preview_revision", revision)).toString())

  private fun history(turns: Int): List<JSONObject> = (0 until turns).flatMap { index ->
    val volume = 20 + index
    listOf(message("user", "Set the radio volume to $volume."), message("assistant", call(volume)),
      message("tool", "{\"kind\":\"result\",\"saved\":false,\"message\":\"Radio volume updated.\"}"))
  }

  private fun messages(history: List<JSONObject>, current: Any, revision: Int): JSONArray =
    JSONArray().also { array ->
      (history + state(revision)).takeLast(24).forEach(array::put)
      array.put(message("user", current))
    }

  private fun audio(bytes: ByteArray) = JSONArray().put(JSONObject().put("type", "input_audio")
    .put("input_audio", JSONObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("format", "wav")))

  private fun request(port: Int, messages: JSONArray, cache: Boolean): JSONObject {
    val connection = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
    val started = System.nanoTime()
    val report = JSONObject()
    val output = StringBuilder()
    var first: Long? = null
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = 5_000
      connection.readTimeout = 120_000
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      val payload = JSONObject().put("messages", messages).put("temperature", 0)
        .put("max_tokens", 64).put("cache_prompt", cache).put("stream", true)
        .put("stream_options", JSONObject().put("include_usage", true))
      connection.outputStream.use { it.write(payload.toString().toByteArray()) }
      report.put("http_status", connection.responseCode)
      if (connection.responseCode != 200) {
        report.put("error", connection.errorStream?.bufferedReader()?.use { it.readText() })
      } else {
        connection.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
          if (line.startsWith("data: ") && line != "data: [DONE]") {
            val event = JSONObject(line.removePrefix("data: "))
            event.optJSONObject("usage")?.let { report.put("usage", it) }
            event.optJSONObject("timings")?.let { report.put("timings", it) }
            val choice = event.optJSONArray("choices")?.optJSONObject(0)
            choice?.optString("finish_reason")?.takeUnless { it.isBlank() || it == "null" }
              ?.let { report.put("finish_reason", it) }
            val delta = choice?.optJSONObject("delta")
            val chunk = if (delta == null || delta.isNull("content")) "" else delta.optString("content")
            if (chunk.isNotEmpty()) {
              if (first == null) first = (System.nanoTime() - started) / 1_000_000
              output.append(chunk)
            }
          }
        } }
      }
    } finally { connection.disconnect() }
    return report.put("first_text_ms", first).put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
      .put("output", output.toString().trim())
  }

  private fun memory(server: CpuModelServer): JSONObject {
    val description = server.serverInstance().toString()
    val pid = Regex("pid=(\\d+)").find(description)?.groupValues?.get(1)
    val report = JSONObject().put("process", description)
    if (pid != null) runCatching {
      File("/proc/$pid/status").readLines().filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }
        .forEach { line -> report.put(line.substringBefore(":"), line.substringAfter(":").trim()) }
    }
    return report
  }

  @Test fun cacheAndContextComparison() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val directory = File(context.filesDir, "models/excavator-v1")
    val hashes = mapOf("zebra-excavator-model-Q8_0.gguf" to "55479b4f7da7a931a3719db7d948d2fd08091c4bbab256977e6841585b894461",
      "zebra-excavator-projector-Q8_0.gguf" to "b5a082347231b7796758f0e9db168a8921a28bd5a6934c91bccc2a8811bb7e16")
    val wav = InstrumentationRegistry.getInstrumentation().context.assets.open("excavator-volume.wav").use { it.readBytes() }
    val results = JSONArray()
    val reportFile = File(context.filesDir, "prompt-cache-benchmark.json")
    val report = JSONObject().put("checkpoint", "excavator-v1").put("max_output_tokens", 64)
      .put("repetitions", 3).put("warehouse_quality_tested", false).put("results", results)
    var failures = 0
    for (capacity in listOf(1024, 2048, 4096)) {
      // Different modes have independent server/cache state, with alternating order.
      for (cache in if (capacity == 2048) listOf(true, false) else listOf(false, true)) {
        val server = CpuModelServer(context, directory, "zebra-excavator-model-Q8_0.gguf", hashes,
          projector = "zebra-excavator-projector-Q8_0.gguf", tag = "CacheBenchmark", contextSize = capacity)
        try {
          val started = System.nanoTime()
          val port = server.ensureServer()
          val startupMs = (System.nanoTime() - started) / 1_000_000
          request(port, messages(emptyList(), "Set the radio volume to 40.", 0), false)
          for (repetition in 0..2) {
            fun measure(name: String, primer: JSONArray, target: JSONArray) {
              request(port, primer, false) // Same uncached primer before each measured request.
              val result = request(port, target, cache).put("scenario", name).put("context_capacity", capacity)
                .put("cache_prompt", cache).put("repetition", repetition).put("startup_ms", startupMs)
                .put("memory", memory(server))
              if (result.getInt("http_status") != 200 || result.getString("output").isBlank()) failures++
              results.put(result)
              reportFile.writeText(report.toString(2))
              Log.i("PromptCacheBenchmark", "context=$capacity cache=$cache case=$name repeat=$repetition " +
                "first=${result.optLong("first_text_ms")} total=${result.getLong("elapsed_ms")} usage=${result.optJSONObject("usage")}")
            }
            val single = messages(emptyList(), "Set the radio volume to 40.", 0)
            measure("repeat_text", single, single)
            val audioSingle = messages(emptyList(), audio(wav), 0)
            measure("repeat_audio", audioSingle, audioSingle)
            val prefix = history(4)
            val primer = messages(prefix, "Set the radio volume to 40.", 1)
            val continued = prefix + listOf(message("user", "Set the radio volume to 40."),
              message("assistant", call(40)), message("tool", "{\"kind\":\"result\",\"saved\":false}"))
            measure("text_followup_changed_state", primer, messages(continued, "Set the radio volume to 35.", 2))
            measure("audio_followup_changed_state", primer, messages(continued, audio(wav), 2))
            val otherChat = messages(history(2), "Set the radio volume to 80.", 80)
            measure("switch_chat_return", otherChat, messages(continued, "Set the radio volume to 35.", 2))
            measure("history_window_shift", messages(history(8), "Set the radio volume to 40.", 1),
              messages(history(9), "Set the radio volume to 35.", 2))
          }
        } finally { server.shutdown() }
      }
    }
    report.put("request_failures", failures).put("completed", true)
    reportFile.writeText(report.toString(2))
    assertEquals("Some cache/context requests failed; inspect saved report", 0, failures)
  }
}
