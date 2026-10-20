package com.example.zebralocalai.inference

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** Local ColBERT query encoding and exact MaxSim over the bundled procedure vectors. */
class DeviceColbertRetriever(context: Context) {
  private val context = context.applicationContext
  private val server = CpuModelServer(context, File(context.filesDir, "models/colbert"),
    MODEL, mapOf(MODEL to MODEL_HASH), embeddings = true, tag = "ColbertCpu")
  @Volatile private var request: HttpURLConnection? = null
  private var cards: List<Pair<String, FloatArray>>? = null

  companion object {
    const val MODEL = "zebra-colbert-350m-Q8_0.gguf"
    const val MODEL_HASH = "b01c5a492ef9f0b0994786a223d45cd936e218b8be83e7c1d4e61ce1c8e984f6"
  }

  @Synchronized fun warmup() { loadIndex(); server.ensureServer() }

  private fun loadIndex(): List<Pair<String, FloatArray>> {
    cards?.let { return it }
    fun asset(name: String) = context.assets.open("colbert-index/$name").use { it.readBytes() }
    val manifest = JSONObject(String(asset("manifest.json"), Charsets.UTF_8))
    check(manifest.getInt("dimension") == 128 && manifest.getBoolean("normalized") &&
      manifest.getString("similarity") == "sum_maxsim") { "Incompatible procedure index" }
    val source = manifest.getJSONObject("source")
    val sourceFile = File.createTempFile("index-source", ".json", context.cacheDir)
    try {
      listOf("sop_cards.jsonl" to "cards_sha256", "sop_policy.json" to "policy_sha256").forEach { (name, key) ->
        sourceFile.writeBytes(context.assets.open(name).use { it.readBytes() })
        check(CpuModelServer.sha256(sourceFile) == source.getString(key)) { "Procedure source/index mismatch" }
      }
      val hashes = manifest.getJSONObject("files")
      hashes.keys().forEach { name ->
        sourceFile.writeBytes(asset(name))
        check(CpuModelServer.sha256(sourceFile) == hashes.getString(name)) { "Procedure bundle hash mismatch: $name" }
      }
    } finally { sourceFile.delete() }
    val data = ByteBuffer.wrap(asset("token_vectors.f32")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val metadata = manifest.getJSONArray("cards")
    var expectedOffset = 0
    val loaded = (0 until metadata.length()).map { index ->
      val item = metadata.getJSONObject(index)
      val offset = item.getInt("vector_offset")
      val count = item.getInt("vector_count")
      check(offset == expectedOffset && count > 0 && (offset + count) * 128 <= data.limit())
      val vectors = FloatArray(count * 128)
      data.position(offset * 128); data.get(vectors)
      check(vectors.all { it.isFinite() })
      for (token in 0 until count) {
        var norm = 0.0
        for (dimension in 0 until 128) norm += vectors[token * 128 + dimension].toDouble().let { it * it }
        check(kotlin.math.abs(sqrt(norm) - 1.0) < 0.0001) { "Unnormalized procedure vector" }
      }
      expectedOffset += count
      item.getString("id") to vectors
    }
    check(expectedOffset * 128 == data.limit()) { "Unreferenced procedure vectors" }
    check(loaded.map { it.first }.distinct().size == loaded.size)
    cards = loaded
    return loaded
  }

  private fun post(path: String, payload: JSONObject): String {
    val connection = URL("http://127.0.0.1:${server.ensureServer()}$path").openConnection() as HttpURLConnection
    request = connection
    try {
      connection.requestMethod = "POST"; connection.doOutput = true
      connection.connectTimeout = 5000; connection.readTimeout = 60000
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
      check(connection.responseCode in 200..299) { "Local retrieval request failed (${connection.responseCode})" }
      return connection.inputStream.bufferedReader().use { it.readText() }
    } finally { request = null; connection.disconnect() }
  }

  private fun encode(text: String): FloatArray {
    val tokens = JSONObject(post("/tokenize", JSONObject().put("content", "[Q] $text").put("add_special", true)))
      .getJSONArray("tokens")
    require(tokens.length() <= 32) { "Query exceeds 32 tokens; split at explicit clause boundaries" }
    while (tokens.length() < 32) tokens.put(7)
    val rows = JSONArray(post("/embedding", JSONObject().put("content", tokens)))
      .getJSONObject(0).getJSONArray("embedding")
    check(rows.length() == 32) { "Missing query expansion vectors" }
    val vector = FloatArray(32 * 128)
    for (token in 0 until 32) {
      val row = rows.getJSONArray(token)
      check(row.length() == 128)
      var norm = 0.0
      for (dimension in 0 until 128) {
        val value = row.getDouble(dimension)
        check(value.isFinite())
        vector[token * 128 + dimension] = value.toFloat(); norm += value * value
      }
      check(norm > 0)
      val inverse = (1.0 / sqrt(norm)).toFloat()
      for (dimension in 0 until 128) vector[token * 128 + dimension] *= inverse
    }
    return vector
  }

  /** Called from the embedded harness; returns candidate IDs, never executable guidance. */
  @Synchronized fun retrieve(text: String, k: Int): String {
    require(text.isNotBlank() && k in 1..3)
    val started = System.nanoTime()
    val documents = loadIndex()
    val queries = try { listOf(encode(text)) } catch (failure: IllegalArgumentException) {
      if (!failure.message.orEmpty().contains("exceeds 32 tokens")) throw failure
      val clauses = text.split(Regex("[.;!?]|\\band\\b")).map(String::trim).filter(String::isNotBlank)
      require(clauses.size > 1) { "Retrieval context is too long; shorten or clarify it" }
      clauses.map(::encode)
    }
    val rankings = queries.map { query -> documents.map { (id, vectors) ->
      var score = 0.0
      for (q in 0 until 32) {
        var best = Float.NEGATIVE_INFINITY
        for (d in 0 until vectors.size / 128) {
          var dot = 0f
          for (dimension in 0 until 128) dot += query[q * 128 + dimension] * vectors[d * 128 + dimension]
          if (dot > best) best = dot
        }
        score += best
      }
      id to score
    }.sortedByDescending { it.second }.take(k) }
    val selected = linkedMapOf<String, Double>()
    for (position in 0 until k) rankings.forEach { rank -> selected.putIfAbsent(rank[position].first, rank[position].second) }
    val response = JSONArray()
    selected.entries.take(k).forEach { response.put(JSONObject().put("id", it.key).put("score", it.value)) }
    Log.i("ColbertCpu", "queryAndMaxSimMs=${(System.nanoTime() - started) / 1000000} candidates=${response.length()}")
    return response.toString()
  }

  fun cancelRequest() { request?.disconnect() }

  fun shutdown() = server.shutdown()
}
