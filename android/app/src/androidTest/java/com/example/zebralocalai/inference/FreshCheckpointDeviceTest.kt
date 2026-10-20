package com.example.zebralocalai.inference

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit test checkpoints only; compatibility/latency never imply demo acceptance. */
@RunWith(AndroidJUnit4::class)
class FreshCheckpointDeviceTest {
  @Test fun freshExportAcceptsTextAndAudioThroughPackagedCpuRuntime() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val relativeDirectory = InstrumentationRegistry.getArguments().getString("checkpointDirectory")
      ?: "models/feasibility/warehouse-pipeline-check-20261012a"
    require(relativeDirectory.matches(Regex("models/(feasibility|warehouse-test)/[A-Za-z0-9_-]+")))
    val trained = relativeDirectory.startsWith("models/warehouse-test/")
    val directory = File(context.filesDir, relativeDirectory)
    val manifest = JSONObject(File(directory, "manifest.json").readText())
    if (trained) require(manifest.getBoolean("test_checkpoint") && !manifest.getBoolean("quality_validated"))
    val artifacts = manifest.getJSONObject("artifacts")
    val hashes = listOf("model-Q8_0.gguf", "projector-Q8_0.gguf").associateWith {
      artifacts.getJSONObject(it).getString("sha256")
    }
    val server = CpuModelServer(context, directory, "model-Q8_0.gguf", hashes,
      projector = "projector-Q8_0.gguf", tag = "FreshCheckpointProbe")
    val report = JSONObject().put("kind", "fresh_export_device_compatibility")
      .put("optimizer_updates", manifest.optInt("optimizer_updates", 0)).put("quality_validated", false)
      .put("checkpoint_directory", relativeDirectory).put("active_checkpoint_changed", false)
      .put("artifacts", artifacts)
    try {
      val startup = System.nanoTime()
      val port = server.ensureServer()
      report.put("startup_with_hash_verification_ms", (System.nanoTime() - startup) / 1_000_000)
      val checks = JSONArray()
      val fixtures = if (trained) JSONObject(File(directory, "device-inputs.json").readText()).getJSONArray("cases")
        else JSONArray().put(JSONObject().put("id", "compatibility-text").put("mode", "text")
          .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "What is your name?"))))
          .put(JSONObject().put("id", "compatibility-audio").put("mode", "audio").put("audio_asset", "excavator-volume.wav")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", ""))))
      for (index in 0 until fixtures.length()) {
        val fixture = fixtures.getJSONObject(index)
        val mode = fixture.getString("mode")
        val messages = JSONArray(fixture.getJSONArray("messages").toString())
        if (mode == "audio") {
          val audio = if (trained) File(directory, fixture.getString("audio_file")).readBytes()
            else InstrumentationRegistry.getInstrumentation().context.assets.open(fixture.getString("audio_asset")).use { it.readBytes() }
          messages.getJSONObject(messages.length() - 1).put("content", JSONArray().put(JSONObject().put("type", "input_audio")
            .put("input_audio", JSONObject().put("data", Base64.encodeToString(audio, Base64.NO_WRAP)).put("format", "wav"))))
        }
        val maxOutput = if (trained) 384 else 16
        val payload = JSONObject().put("messages", messages)
          .put("max_tokens", maxOutput).put("temperature", 0).put("stream", true)
          .put("stream_options", JSONObject().put("include_usage", true)).put("cache_prompt", trained)
        if (fixture.has("grammar")) payload.put("grammar", fixture.getString("grammar"))
        val connection = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        val started = System.nanoTime()
        var firstTextMs: Long? = null
        val output = StringBuilder()
        val check = JSONObject().put("id", fixture.getString("id")).put("mode", mode).put("max_output_tokens", maxOutput)
          .put("cache_prompt", trained)
        if (fixture.has("grammar")) check.put("grammar_requested", true)
        if (trained) check.put("expected_calls", fixture.getJSONArray("expected_calls")).put("expected_target", fixture.getString("expected_target"))
        try {
          connection.requestMethod = "POST"
          connection.connectTimeout = 5_000
          connection.readTimeout = 180_000
          connection.doOutput = true
          connection.setRequestProperty("Content-Type", "application/json")
          connection.outputStream.use { it.write(payload.toString().toByteArray()) }
          assertEquals(200, connection.responseCode)
          connection.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
            if (line.startsWith("data: ") && line != "data: [DONE]") {
              val event = JSONObject(line.removePrefix("data: "))
              event.optJSONObject("usage")?.let { check.put("usage", it) }
              event.optJSONObject("timings")?.let { check.put("timings", it) }
              val choice = event.optJSONArray("choices")?.optJSONObject(0)
              choice?.optString("finish_reason")?.takeUnless { it.isBlank() || it == "null" }
                ?.let { check.put("finish_reason", it) }
              val delta = choice?.optJSONObject("delta")
              val chunk = if (delta == null || delta.isNull("content")) "" else delta.optString("content")
              if (chunk.isNotEmpty()) {
                if (firstTextMs == null) firstTextMs = (System.nanoTime() - started) / 1_000_000
                output.append(chunk)
              }
            }
          } }
          assertTrue("No decoded text for $mode", output.isNotBlank())
          assertTrue("Runtime died for $mode", server.serverInstance()?.isAlive == true)
          check.put("first_text_ms", firstTextMs).put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
            .put("output", output.toString()).put("passed", true)
          val processes = ProcessBuilder("/system/bin/ps", "-A", "-o", "PID,ARGS").start()
            .inputStream.bufferedReader().use { it.readLines() }
            .filter { it.contains("libllama-audio-cpu-server.so") && !it.contains("--embedding") }
          check.put("generation_server_count", processes.size)
          assertEquals("More than one generation server", 1, processes.size)
          runCatching {
            val pid = processes.single().trim().split(Regex("\\s+"), limit = 2).first().toInt()
            val status = File("/proc/$pid/status").readLines()
            for ((field, key) in listOf("VmHWM:" to "peak_rss_kib", "VmRSS:" to "rss_kib")) {
              status.firstOrNull { it.startsWith(field) }?.let {
                check.put(key, it.removePrefix(field).trim().substringBefore(" ").toLong())
              }
            }
          }.onFailure { check.put("memory_measurement_unavailable", it.javaClass.simpleName) }
          checks.put(check)
        } finally { connection.disconnect() }
      }
      report.put("checks", checks).put("passed", true)
      File(directory, "device-smoke.json").writeText(report.toString(2))
    } finally { server.shutdown() }
  }
}
