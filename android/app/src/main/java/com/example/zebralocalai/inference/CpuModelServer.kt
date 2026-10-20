package com.example.zebralocalai.inference

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Shared lifecycle for app-owned CPU audio-generation and retrieval processes. */
internal class CpuModelServer(
  context: Context,
  private val directory: File,
  private val model: String,
  private val hashes: Map<String, String>,
  private val projector: String? = null,
  private val embeddings: Boolean = false,
  private val tag: String = "Audio600MCpu",
  private val contextSize: Int = if (embeddings) 512 else 4096,
) {
  private val context = context.applicationContext
  @Volatile private var process: Process? = null
  @Volatile private var port: Int? = null
  @Volatile private var closed = false
  private var verified = false
  private val log = StringBuilder()

  @Synchronized fun ensureServer(): Int {
    check(!closed) { "The local runtime has been closed." }
    if (process?.isAlive == true && port != null) return port!!
    if (!verified) {
      hashes.forEach { (name, expected) ->
        val file = File(directory, name)
        check(file.isFile) { "Required local model is not installed: $name" }
        check(sha256(file) == expected) { "Installed model hash mismatch: $name" }
      }
      verified = true
    }
    val native = File(context.applicationInfo.nativeLibraryDir)
    val executable = File(native, "libllama-audio-cpu-server.so")
    check(executable.canExecute()) { "The packaged CPU runtime is missing." }
    val selected = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val arguments = mutableListOf(executable.absolutePath, "-m", File(directory, model).absolutePath)
    if (projector != null) arguments += listOf("--mmproj", File(directory, projector).absolutePath, "--no-mmproj-offload")
    if (embeddings) arguments += listOf("--embedding", "--pooling", "none", "-b", "512", "-ub", "512")
    arguments += listOf("--device", "none", "-ngl", "0", "-t", "4", "-tb", "4", "-c",
      contextSize.toString(), "-np", "1", "--host", "127.0.0.1", "--port", selected.toString(), "--offline")
    val child = ProcessBuilder(arguments).directory(native).redirectErrorStream(true).also {
      it.environment()["LD_LIBRARY_PATH"] = "${native.absolutePath}:/vendor/lib64"
    }.start()
    process = child
    port = selected
    synchronized(log) { log.clear() }
    thread(name = "$tag-native-log", isDaemon = true) {
      runCatching { child.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line -> synchronized(log) {
          log.appendLine(line)
          if (log.length > 16_000) log.delete(0, log.length - 12_000)
        } }
      } }
    }
    try {
      val deadline = System.nanoTime() + 60_000_000_000L
      while (System.nanoTime() < deadline) {
        check(!closed) { "The local runtime has been closed." }
        check(child.isAlive) { "Local runtime startup failed: ${diagnosticLog()}" }
        val ready = runCatching {
          val health = URL("http://127.0.0.1:$selected/health").openConnection() as HttpURLConnection
          try { health.connectTimeout = 250; health.readTimeout = 250; health.responseCode == 200 }
          finally { health.disconnect() }
        }.getOrDefault(false)
        if (ready) { Log.i(tag, "App-owned CPU runtime ready port=$selected"); return selected }
        Thread.sleep(100)
      }
      error("Local runtime startup timed out: ${diagnosticLog()}")
    } catch (failure: Throwable) {
      child.destroyForcibly(); process = null; port = null; throw failure
    }
  }

  fun serverInstance(): Process? = process?.takeIf { it.isAlive }
  fun diagnosticLog(): String = synchronized(log) { log.takeLast(2000).toString() }
  fun shutdown() { closed = true; process?.destroy(); process = null; port = null }

  companion object {
    fun sha256(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }
  }
}
