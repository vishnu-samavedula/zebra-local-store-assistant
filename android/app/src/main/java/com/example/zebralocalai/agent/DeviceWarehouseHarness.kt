package com.example.zebralocalai.agent

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.zebralocalai.inference.DeviceColbertRetriever
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

/** The shared Python harness embedded in Android; all state and retrieval stay local. */
class DeviceWarehouseHarness(context: Context, private val databaseName: String = "warehouse-tickets.db", private val oneShot: Boolean = false) {
  private val context = context.applicationContext
  private val retrieval = DeviceColbertRetriever(context)
  private var initialized = false

  @Synchronized fun warmup() {
    if (initialized) return
    val root = File(context.filesDir, "warehouse-harness")
    listOf(
      "model_output.schema.json" to "contracts/model_output.schema.json",
      "reference_journeys.jsonl" to "contracts/reference_journeys.jsonl",
      "warehouse_catalog.jsonl" to "seed_data/warehouse_catalog.jsonl",
      "sop_cards.jsonl" to "seed_data/sop_cards.jsonl",
      "sop_policy.json" to "seed_data/sop_policy.json",
      "industrial_task_snapshot.json" to "seed_data/industrial_task_snapshot.json",
    ).forEach { (asset, relative) ->
      val destination = File(root, relative)
      destination.parentFile?.mkdirs()
      context.assets.open(asset).use { input -> destination.outputStream().use(input::copyTo) }
    }
    if (!Python.isStarted()) Python.start(AndroidPlatform(context))
    Python.getInstance().getModule("harness_bridge").callAttr("initialize", root.absolutePath,
      File(context.filesDir, databaseName).absolutePath, retrieval, oneShot)
    initialized = true
  }

  @Synchronized fun catalog(): JSONArray {
    warmup()
    return JSONArray(Python.getInstance().getModule("harness_bridge").callAttr("catalog").toString())
  }

  @Synchronized fun modelContext(chat: String, latestInput: String? = null): JSONObject {
    warmup()
    return JSONObject(Python.getInstance().getModule("harness_bridge").callAttr("context", chat, latestInput).toString())
  }

  @Synchronized fun setMode(chat: String, mode: String) {
    warmup()
    Python.getInstance().getModule("harness_bridge").callAttr("set_mode", chat, mode)
  }

  @Synchronized fun restart(chat: String) {
    warmup()
    Python.getInstance().getModule("harness_bridge").callAttr("restart", chat)
  }

  @Synchronized fun selectRead(chat: String, product: String): JSONObject {
    warmup()
    return JSONObject(Python.getInstance().getModule("harness_bridge").callAttr("select_read", chat, product).toString())
  }

  @Synchronized fun chooseReport(chat: String, offer: String, accepted: Boolean): JSONObject {
    warmup()
    return JSONObject(Python.getInstance().getModule("harness_bridge").callAttr("choose_report", chat, offer, accepted).toString())
  }

  @Synchronized fun completeForm(chat: String, product: String, quantity: Int, event: String): JSONObject {
    warmup()
    return JSONObject(Python.getInstance().getModule("harness_bridge").callAttr("complete_form", chat, product, quantity, event).toString())
  }

  @Synchronized fun control(chat: String, text: String, mode: String, eventId: String): JSONObject? {
    warmup()
    val output = Python.getInstance().getModule("harness_bridge").callAttr("control", chat, text, mode, eventId).toString()
    return if (output == "null") null else JSONObject(output)
  }

  @Synchronized fun handle(chat: String, transcript: String, output: String, mode: String,
                          finish: String, eventId: String): JSONObject {
    warmup()
    return JSONObject(Python.getInstance().getModule("harness_bridge")
      .callAttr("handle", chat, transcript, output, mode, finish, eventId).toString())
  }

  @Synchronized fun tickets(): JSONArray {
    warmup()
    return JSONArray(Python.getInstance().getModule("harness_bridge").callAttr("tickets").toString())
  }

  @Synchronized fun confirmationPreview(chat: String): JSONObject? {
    warmup()
    val value = Python.getInstance().getModule("harness_bridge").callAttr("confirmation_preview", chat).toString()
    return if (value == "null") null else JSONObject(value)
  }

  @Synchronized fun confirmPreview(chat: String, requestKey: String, revision: Int, eventId: String): JSONObject {
    warmup()
    return JSONObject(Python.getInstance().getModule("harness_bridge")
      .callAttr("confirm_preview", chat, requestKey, revision, eventId).toString())
  }

  fun retriever(): DeviceColbertRetriever = retrieval
  @Synchronized internal fun reviewReferenceJourneys(): JSONObject {
    warmup()
    return JSONObject(Python.getInstance().getModule("harness_bridge").callAttr("review_reference_journeys").toString())
  }
  @Synchronized fun forgetChat(chat: String) {
    if (initialized) Python.getInstance().getModule("harness_bridge").callAttr("forget_chat", chat)
  }
  fun shutdown() {
    // Kill a pending retrieval request before waiting for the serialized Python boundary.
    retrieval.shutdown()
    synchronized(this) {
      if (initialized) Python.getInstance().getModule("harness_bridge").callAttr("close")
      initialized = false
    }
  }
}
