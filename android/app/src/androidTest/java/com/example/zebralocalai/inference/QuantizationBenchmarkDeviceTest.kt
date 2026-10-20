package com.example.zebralocalai.inference

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Paired CPU measurements; never activates Q4 or writes worker tickets. */
@RunWith(AndroidJUnit4::class)
class QuantizationBenchmarkDeviceTest {
  @Test fun compareQ8AndQ4() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val directory = File(context.filesDir, "models/warehouse-test/warehouse-iteration-20261013g")
    val q8 = JSONObject(File(directory, "manifest.json").readText()).getJSONObject("artifacts")
    val q4 = JSONObject(File(directory, "q4-manifest.json").readText())
    val all = JSONObject(File(directory, "device-inputs.json").readText()).getJSONArray("cases")
    val fixtures = (1..4).map { JSONObject(all.getJSONObject(it).toString()) }.toMutableList()
    fun add(id: String, text: String, tool: String, arguments: JSONObject) {
      val row = JSONObject(all.getJSONObject(1).toString()).put("id", id).put("mode", "text")
      row.getJSONArray("messages").getJSONObject(1).put("content", text)
      row.put("expected_calls", JSONArray().put(JSONObject().put("tool", tool).put("arguments", arguments)))
      fixtures += row
    }
    add("replenishment", "Request ten Cloudweight Hoodies in ink, medium.", "request_replenishment",
      JSONObject("""{"product_name":"Cloudweight Hoodie","variant":{"color":"Ink","size":"M"},"quantity":10}"""))
    add("concern", "The scanner is flickering near receiving.", "report_general_concern", JSONObject())
    add("damage", "Five Cloudweight Hoodies in ink, medium. Fabric is torn at the hem around the corner from the break room.",
      "report_product_damage", JSONObject("""{"product_name":"Cloudweight Hoodie","variant":{"color":"Ink","size":"M"},"quantity":5}"""))
    add("family-stock", "How many Transit Wallets do we have?", "inventory_lookup",
      JSONObject("""{"operation":"count_stock","product_name":"Transit Wallet"}"""))
    val report = JSONObject().put("active_checkpoint_changed", false).put("cpu_threads", 4)
      .put("context_size", 4096).put("max_tokens", 384).put("cache_prompt", true)
      .put("transcription_included", false).put("retrieval_included", false)
      .put("projector", "Q8_0").put("cases", JSONArray()).put("blocks", JSONArray())
    val output = File(context.filesDir, "warehouse-g-q4-comparison.json")
    for ((block, quant) in listOf("Q8_0", "Q4_K_M", "Q4_K_M", "Q8_0").withIndex()) {
      val model = "model-$quant.gguf"
      val hash = if (quant == "Q8_0") q8.getJSONObject(model).getString("sha256") else q4.getString("sha256")
      val server = CpuModelServer(context, directory, model, mapOf(model to hash,
        "projector-Q8_0.gguf" to q8.getJSONObject("projector-Q8_0.gguf").getString("sha256")),
        projector = "projector-Q8_0.gguf", tag = "QuantizationProbe")
      try {
        val started = System.nanoTime()
        val port = server.ensureServer()
        report.getJSONArray("blocks").put(JSONObject().put("block", block).put("quant", quant)
          .put("startup_hash_ms", (System.nanoTime() - started) / 1000000))
        request(port, JSONArray().put(JSONObject().put("role", "user").put("content", "How many tasks are there for me?")), false)
        for (fixture in fixtures) {
          val messages = JSONArray(fixture.getJSONArray("messages").toString())
          if (fixture.getString("mode") == "audio") {
            val bytes = File(directory, fixture.getString("audio_file")).readBytes()
            messages.getJSONObject(messages.length() - 1).put("content", JSONArray().put(JSONObject().put("type", "input_audio")
              .put("input_audio", JSONObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("format", "wav"))))
          }
          val result = request(port, messages, true).put("block", block).put("quant", quant)
            .put("id", fixture.getString("id")).put("mode", fixture.getString("mode"))
            .put("expected_calls", fixture.getJSONArray("expected_calls")).put("memory", memory(server))
          assertEquals(200, result.getInt("http_status"))
          val processes = ProcessBuilder("/system/bin/ps", "-A", "-o", "PID,ARGS").start()
            .inputStream.bufferedReader().use { it.readLines() }
            .filter { it.contains("libllama-audio-cpu-server.so") && !it.contains("--embedding") }
          result.put("generation_servers", processes.size)
          assertEquals(1, processes.size)
          report.getJSONArray("cases").put(result)
          output.writeText(report.toString(2))
        }
      } finally { val process = server.serverInstance(); server.shutdown(); process?.waitFor() }
      Thread.sleep(500)
    }
    assertEquals(32, report.getJSONArray("cases").length())
  }
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
        .put("max_tokens", 384).put("cache_prompt", cache).put("stream", true)
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

}
