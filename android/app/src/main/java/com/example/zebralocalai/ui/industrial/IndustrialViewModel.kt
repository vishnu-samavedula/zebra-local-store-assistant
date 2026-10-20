package com.example.zebralocalai.ui.industrial

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.zebralocalai.audio.WavAudioRecorder
import com.example.zebralocalai.audio.AudioTranscriptProvider
import com.example.zebralocalai.audio.AudioTurnPipeline
import com.example.zebralocalai.audio.OfflineAudioTranscript
import com.example.zebralocalai.inference.ResidentCpuAudioRuntime
import com.example.zebralocalai.inference.InferenceResult
import com.example.zebralocalai.agent.DeviceWarehouseHarness
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class IndustrialProduct(
  val id: String, val parentId: String, val name: String, val category: String,
  val color: String, val size: String, val sku: String, val location: String,
  val onHand: Int, val reserved: Int, val spokenLabel: String,
  val section: String = "", val bin: Int = 0,
) { val available: Int get() = onHand - reserved }

data class IndustrialSop(
  val id: String, val title: String, val guidance: String,
  val workerSafety: String, val clarification: String, val reportTo: String,
  val escalation: String,
  val callScript: String = "", val locationLookup: String = "",
  val requiredSiteData: String = "", val demoRule: String = "",
)

data class IndustrialState(
  val darkTheme: Boolean = false,
  val workerMode: String = "general",
  val formRequired: Boolean = false,
  val readChoices: List<Pair<String, String>> = emptyList(),
  val reportOfferId: String? = null,
  val chats: List<IndustrialChat> = emptyList(),
  val activeChatId: String? = null,
  val products: List<IndustrialProduct> = emptyList(),
  val sops: List<IndustrialSop> = emptyList(),
  val taskSnapshot: IndustrialTaskSnapshot? = null,
  val taskLoadFailed: Boolean = false,
  val tickets: List<IndustrialTicket>? = null,
  val ticketLoadFailed: Boolean = false,
  val recording: Boolean = false,
  val labRecording: Boolean = false,
  val labPromptIndex: Int = 0,
  val labSavedCount: Int = 0,
  val latencySamples: List<LatencySample> = emptyList(),
  val latencyLoadFailed: Boolean = false,
  val recordingElapsedMillis: Long = 0,
  val waveform: List<Float> = List(32) { 0f },
  val busy: Boolean = false,
  val canStop: Boolean = false,
  val confirmable: ConfirmationReference? = null,
  val error: String? = null,
) {
  val messages: List<IndustrialMessage>
    get() = chats.firstOrNull { it.id == activeChatId }?.messages.orEmpty()
}

/** Chat shell using one local CPU audio-model runtime for both input modes. */
class IndustrialViewModel(application: Application) : AndroidViewModel(application) {
  companion object { const val MAX_RECORDING_MILLIS = 30_000L }
  private val preferences = application.getSharedPreferences("liquid-industrial-chats", Context.MODE_PRIVATE)
  private val recorder = WavAudioRecorder(application)
  private val voiceEvaluation = WarehouseVoiceEvaluation(application)
  private val runtime = ResidentCpuAudioRuntime.get(application)
  private val harness = DeviceWarehouseHarness(application, oneShot = true)
  private val turnReview = TurnReviewLogger(application)
  internal var audioTranscriptProvider: AudioTranscriptProvider = OfflineAudioTranscript(application)
  private val mutableState = MutableStateFlow(IndustrialState(darkTheme = preferences.getBoolean("dark_theme", false),
    workerMode = preferences.getString("worker_mode", "general").takeIf { it in setOf("general", "sop") } ?: "general"))
  val state: StateFlow<IndustrialState> = mutableState.asStateFlow()
  private var recordingTimeout: Job? = null
  private var generationJob: Job? = null
  private var audioStoppedAt = 0L
  private var transcriptionMillis = 0L

  init {
    mutableState.value = mutableState.value.copy(busy = true)
    viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) { ResidentCpuAudioRuntime.warmup(application) } }
        .onFailure { failure ->
          if (failure is CancellationException) throw failure
          mutableState.value = mutableState.value.copy(error = failure.message ?: "The audio model could not be loaded.")
        }
      mutableState.value = mutableState.value.copy(busy = false)
    }
    viewModelScope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          getApplication<Application>().assets.open("industrial_task_snapshot.json").bufferedReader().use {
            IndustrialTaskSnapshot.parse(JSONObject(it.readText()))
          }
        }
      }.onSuccess { mutableState.value = mutableState.value.copy(taskSnapshot = it) }
        .onFailure { mutableState.value = mutableState.value.copy(taskLoadFailed = true) }
    }
    runCatching { restoreChats() }.onFailure {
      mutableState.value = mutableState.value.copy(error = "Saved chats could not be opened.")
    }
    viewModelScope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          val catalog = harness.catalog()
          val products = (0 until catalog.length()).map { index ->
            val p = catalog.getJSONObject(index)
            IndustrialProduct(
              p.getString("product_id"), p.getString("parent_product_id"),
              p.getString("name"), p.getString("category"), p.getString("color"),
              p.getString("size"), p.getString("sku"), p.getString("location"),
              p.getInt("on_hand"), p.getInt("reserved"), p.getString("spoken_label"),
              p.getString("section"), p.getInt("bin"),
            )
          }
          val sops = jsonLines("sop_cards.jsonl").map { s ->
            IndustrialSop(
              s.getString("id"), s.getString("title"), s.getString("guidance"),
              s.optString("worker_safety"), s.optString("clarification"),
              s.optString("report_to"), s.getString("escalation"),
              s.optString("call_script"), s.optString("location_lookup"),
              s.optString("required_site_data"), s.optString("demo_rule"),
            )
          }
          products to sops
        }
      }.onSuccess { (products, sops) ->
        mutableState.value = mutableState.value.copy(products = products, sops = sops)
      }.onFailure { mutableState.value = mutableState.value.copy(error = "Local reference data could not be opened.") }
    }
    viewModelScope.launch {
      while (true) {
        delay(60_000)
        refreshRetention()
      }
    }
  }

  private fun jsonLines(name: String): List<JSONObject> =
    getApplication<Application>().assets.open(name).bufferedReader().useLines { lines ->
      lines.filter(String::isNotBlank).map(::JSONObject).toList()
    }

  fun refreshRetention() {
    val current = mutableState.value
    mutableState.value = current.copy(chats = current.chats.map { it.copy(messages = it.messages.takeLast(200)) })
    persistChats()
  }

  private fun feedId(mode: String = mutableState.value.workerMode) = "worker-feed-$mode"

  fun clearChat() = resetRequest(clearFeed = true)

  fun stopProcessing() {
    if (mutableState.value.canStop) resetRequest(clearFeed = false, stopped = true)
  }

  fun cancelPendingRequest() = resetRequest(clearFeed = false)

  private fun resetRequest(clearFeed: Boolean, stopped: Boolean = false) {
    val current = mutableState.value
    // Do not interrupt an atomic ticket save or another non-cancellable action.
    if (current.busy && !current.canStop) return
    if (current.recording) cancelRecording()
    val pending = generationJob
    pending?.cancel()
    mutableState.value = mutableState.value.copy(busy = true, canStop = false)
    viewModelScope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          runtime.cancelRequest()
          harness.retriever().cancelRequest()
        }
        // Wait for blocking processing to finish before resetting its harness state.
        pending?.join()
        withContext(Dispatchers.IO) { harness.restart(feedId()) }
      }.onSuccess {
        mutableState.value = mutableState.value.copy(
          chats = if (clearFeed) mutableState.value.chats.filterNot { it.id == feedId() } else mutableState.value.chats,
          activeChatId = if (clearFeed) feedId() else mutableState.value.activeChatId,
          confirmable = null, formRequired = false, readChoices = emptyList(),
          reportOfferId = null, error = null,
        )
        if (clearFeed) persistChats()
        else appendMessage("assistant", if (stopped) "Request stopped." else "Pending request cancelled. Ready for a new request.")
      }.onFailure {
        if (it is CancellationException) throw it
        mutableState.value = mutableState.value.copy(error = "Could not reset the request. Please try again.")
      }
      generationJob = null
      mutableState.value = mutableState.value.copy(busy = false, canStop = false)
    }
  }

  fun setWorkerMode(mode: String) {
    if (mode !in setOf("general", "sop") || mode == mutableState.value.workerMode || mutableState.value.recording || mutableState.value.busy) return
    mutableState.value = mutableState.value.copy(busy = true)
    viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) {
        harness.restart(feedId())
        harness.restart(feedId(mode))
        harness.setMode(feedId(mode), mode)
      } }
        .onSuccess {
          preferences.edit().putString("worker_mode", mode).apply()
          mutableState.value = mutableState.value.copy(workerMode = mode, activeChatId = feedId(mode), error = null, confirmable = null, formRequired = false, readChoices = emptyList(), reportOfferId = null)
        }.onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
      mutableState.value = mutableState.value.copy(busy = false)
    }
  }

  fun completeRequestForm(product: String, quantity: Int) {
    if (mutableState.value.busy || mutableState.value.recording) return
    mutableState.value = mutableState.value.copy(busy = true)
    viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val result = harness.completeForm(feedId(), product, quantity, UUID.randomUUID().toString())
        turnOutcome(feedId(), result, null, SystemClock.elapsedRealtime() - started)
      } }.onSuccess { showOutcome(feedId(), it) }
        .onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
      mutableState.value = mutableState.value.copy(busy = false)
    }
  }

  fun selectRead(product: String) {
    if (mutableState.value.busy || mutableState.value.recording) return
    mutableState.value = mutableState.value.copy(busy = true)
    viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val result = harness.selectRead(feedId(), product)
        turnOutcome(feedId(), result, null, SystemClock.elapsedRealtime() - started)
      } }
        .onSuccess { showOutcome(feedId(), it) }
        .onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
      mutableState.value = mutableState.value.copy(busy = false)
    }
  }

  fun chooseReport(offer: String, accepted: Boolean) {
    val state = mutableState.value
    if (state.busy || state.recording || state.reportOfferId != offer) return
    appendMessage("user", if (accepted) "Yes, prepare a report." else "No report.", feedId())
    mutableState.value = mutableState.value.copy(busy = true, reportOfferId = null)
    viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val result = harness.chooseReport(feedId(), offer, accepted)
        turnReview.append(feedId(), UUID.randomUUID().toString(), if (accepted) "Yes" else "No", "button", result, null)
        turnOutcome(feedId(), result, null, SystemClock.elapsedRealtime() - started)
      } }.onSuccess { showOutcome(feedId(), it) }
        .onFailure { mutableState.value = mutableState.value.copy(error = it.message) }
      mutableState.value = mutableState.value.copy(busy = false)
    }
  }

  fun submitText(text: String) {
    if (mutableState.value.recording || mutableState.value.busy || text.isBlank()) return
    val input = text.trim()
    val chatId = appendMessage("user", input)
    mutableState.value = mutableState.value.copy(busy = true, canStop = true, error = null, confirmable = null, readChoices = emptyList())
    generationJob = viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) { processTurn(chatId, input, "text") {
        runtime.replyText(input, modelHistory(chatId, input))
      } } }
        .onSuccess { outcome -> showOutcome(chatId, outcome) }
        .onFailure { failure ->
          if (failure is CancellationException) throw failure
          mutableState.value = mutableState.value.copy(error = failure.message ?: "The model could not answer.")
        }
      mutableState.value = mutableState.value.copy(busy = false, canStop = false)
    }
  }

  private data class TurnOutcome(val result: JSONObject, val inference: InferenceResult?,
    val confirmation: ConfirmationReference? = null, val processingMillis: Long? = null)

  private fun confirmationReference(chat: String): ConfirmationReference? = harness.confirmationPreview(chat)?.let {
    ConfirmationReference(it.getString("request_key"), it.getInt("revision"))
  }

  private fun turnOutcome(chat: String, result: JSONObject, inference: InferenceResult?, processingMillis: Long? = null): TurnOutcome =
    TurnOutcome(result, inference, if (result.optString("kind") == "failed") null else confirmationReference(chat), processingMillis)

  fun confirmTicket(reference: ConfirmationReference) {
    val current = mutableState.value
    val chat = current.activeChatId ?: return
    if (current.busy || current.recording || current.confirmable != reference) return
    appendMessage("user", "Confirm", chat)
    mutableState.value = mutableState.value.copy(busy = true)
    viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val event = UUID.randomUUID().toString()
        val result = harness.confirmPreview(chat, reference.requestKey, reference.revision, event)
        turnReview.append(chat, event, "Confirm", "button", result, null)
        turnOutcome(chat, result, null, SystemClock.elapsedRealtime() - started)
      } }.onSuccess { showOutcome(chat, it) }.onFailure {
        mutableState.value = mutableState.value.copy(error = it.message ?: "Confirmation could not complete.", confirmable = null)
      }
      mutableState.value = mutableState.value.copy(busy = false)
    }
  }

  private fun modelHistory(chatId: String, latestInput: String): List<Pair<String, String>> {
    val context = harness.modelContext(chatId, latestInput)
    val history = context.getJSONArray("model_history")
    val messages = (0 until history.length()).map { index -> history.getJSONObject(index).let {
      it.getString("role") to it.getString("content")
    } }.toMutableList()
    context.remove("model_history")
    messages += "tool" to JSONObject().put("harness_state", context).toString()
    return messages
  }

  private fun processTurn(chat: String, transcript: String, mode: String,
                          infer: () -> InferenceResult): TurnOutcome {
    val started = SystemClock.elapsedRealtime()
    val origin = if (mode == "audio") audioStoppedAt else started
    val timings = JSONObject().put("transcription_ms", if (mode == "audio") transcriptionMillis else 0)
    val event = UUID.randomUUID().toString()
    harness.setMode(chat, mutableState.value.workerMode)
    val control = harness.control(chat, transcript, mode, event)
    if (control != null) {
      timings.put("harness_ms", SystemClock.elapsedRealtime() - started)
        .put("processing_total_ms", SystemClock.elapsedRealtime() - origin).put("model_ms", 0)
      turnReview.append(chat, event, transcript, mode, control, null, timings)
      return turnOutcome(chat, control, null, timings.getLong("processing_total_ms"))
    }
    val result = infer()
    val harnessStarted = SystemClock.elapsedRealtime()
    val outcome = harness.handle(chat, transcript, result.text, mode,
      result.finishReason ?: "stop", event)
    timings.put("harness_ms", SystemClock.elapsedRealtime() - harnessStarted)
      .put("model_ms", result.elapsedMillis).put("processing_total_ms", SystemClock.elapsedRealtime() - origin)
    turnReview.append(chat, event, transcript, mode, outcome, result, timings)
    return turnOutcome(chat, outcome, result, timings.getLong("processing_total_ms"))
  }

  fun refreshTickets() {
    viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) {
        val rows = harness.tickets()
        (0 until rows.length()).map { IndustrialTicket.parse(rows.getJSONObject(it)) }.reversed()
      } }.onSuccess { mutableState.value = mutableState.value.copy(tickets = it, ticketLoadFailed = false) }
        .onFailure { failure ->
          if (failure is CancellationException) throw failure
          mutableState.value = mutableState.value.copy(ticketLoadFailed = true)
        }
    }
  }

  fun refreshLatency() {
    viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) { turnReview.latencySamples() } }
        .onSuccess { mutableState.value = mutableState.value.copy(latencySamples = it, latencyLoadFailed = false) }
        .onFailure {
          if (it is CancellationException) throw it
          mutableState.value = mutableState.value.copy(latencyLoadFailed = true)
        }
    }
  }

  fun resetLatency() {
    if (mutableState.value.busy || mutableState.value.recording) return
    viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) { turnReview.resetLatency() } }
        .onSuccess { mutableState.value = mutableState.value.copy(latencySamples = emptyList(), latencyLoadFailed = false) }
        .onFailure { mutableState.value = mutableState.value.copy(error = "Latency measurements could not be reset.") }
    }
  }

  private fun showOutcome(chat: String, outcome: TurnOutcome) {
    val choices = outcome.result.optJSONArray("choices")
    val readChoices = choices?.let { array -> (0 until array.length()).map { index ->
      val row = array.getJSONObject(index)
      row.getString("product_id") to "${row.getString("name")} · ${row.optString("color")} ${row.optString("size")}"
    } }.orEmpty()
    if (outcome.result.optBoolean("saved")) refreshTickets()
    outcome.result.optJSONObject("inventory_change")?.let { change ->
      mutableState.value = mutableState.value.copy(products = mutableState.value.products.map { product ->
        if (product.id == change.getString("product_id")) product.copy(onHand = change.getInt("after") + product.reserved)
        else product
      })
    }
    val tools = outcome.result.optJSONArray("tools")?.let { array ->
      (0 until array.length()).map(array::getString)
    }.orEmpty()
    appendMessage("assistant", HarnessReplyFormatter.format(outcome.result), chat, tools, outcome.confirmation, outcome.processingMillis)
    mutableState.value = mutableState.value.copy(confirmable = outcome.confirmation, formRequired = outcome.result.optBoolean("form_required"), readChoices = readChoices,
      reportOfferId = outcome.result.optJSONObject("report_offer")?.optString("id"))
    outcome.inference?.let(::reportLimit)
  }

  private fun reportLimit(result: InferenceResult) {
    if (result.finishReason == "length") mutableState.value = mutableState.value.copy(
      error = "The response reached its length limit. Try a shorter question.",
    )
  }

  private fun appendMessage(role: String, text: String, chatId: String? = null,
                            tools: List<String> = emptyList(), confirmation: ConfirmationReference? = null,
                            processingMillis: Long? = null): String {
    refreshRetention()
    val current = mutableState.value
    val existing = current.chats.firstOrNull { it.id == (chatId ?: current.activeChatId) }
    val chat = (existing ?: IndustrialChat(chatId ?: feedId(), if (current.workerMode == "sop") "SOP Advisor" else "General", System.currentTimeMillis()))
      .let { it.copy(messages = it.messages + IndustrialMessage(role, text, tools, confirmation, processingMillis)) }
    mutableState.value = current.copy(
      chats = current.chats.filterNot { it.id == chat.id } + chat.copy(messages = chat.messages.takeLast(200)),
      activeChatId = chat.id, error = null,
    )
    persistChats()
    return chat.id
  }

  fun toggleRecording() {
    val current = mutableState.value
    if (current.busy) return
    if (current.labRecording) return
    if (current.recording) {
      audioStoppedAt = SystemClock.elapsedRealtime()
      recordingTimeout?.cancel()
      mutableState.value = current.copy(recording = false, busy = true, canStop = true, confirmable = null, readChoices = emptyList(), recordingElapsedMillis = 0, waveform = List(32) { 0f })
      generationJob = viewModelScope.launch {
        val transcripts = audioTranscriptProvider
        var chatId: String? = null
        var recognizedText = ""
        runCatching {
          val file = withContext(Dispatchers.IO) { recorder.stop() }
          AudioTurnPipeline(transcripts, { audio, _ ->
            withContext(Dispatchers.IO) { processTurn(requireNotNull(chatId), recognizedText, "audio") {
              runtime.replyAudio(audio, modelHistory(requireNotNull(chatId), recognizedText))
            } }
          }).run(file, emptyList()) { transcript ->
            recognizedText = transcript
            transcriptionMillis = SystemClock.elapsedRealtime() - audioStoppedAt
            chatId = appendMessage("user", transcript)
          }
        }.onSuccess { result ->
          showOutcome(requireNotNull(chatId), result)
        }.onFailure { failure ->
          if (failure is CancellationException) throw failure
          recorder.cancel()
          mutableState.value = mutableState.value.copy(error = failure.message ?: "Audio could not be processed.")
        }
        mutableState.value = mutableState.value.copy(busy = false, canStop = false)
      }
    } else {
      runCatching { recorder.start(maxDurationMillis = MAX_RECORDING_MILLIS) }.onSuccess {
        mutableState.value = current.copy(recording = true, error = null, recordingElapsedMillis = 0, waveform = List(32) { 0f })
        val started = SystemClock.elapsedRealtime()
        recordingTimeout = viewModelScope.launch {
          while (mutableState.value.recording) {
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtMost(MAX_RECORDING_MILLIS)
            if (elapsed >= MAX_RECORDING_MILLIS || recorder.durationLimitReached) {
              toggleRecording()
              break
            }
            val snapshot = mutableState.value
            mutableState.value = snapshot.copy(recordingElapsedMillis = elapsed, waveform = snapshot.waveform.drop(1) + recorder.level)
            delay(80)
          }
        }
      }.onFailure { mutableState.value = current.copy(error = it.message ?: "Microphone unavailable.") }
    }
  }

  fun nextLabPrompt() {
    val current = mutableState.value
    if (!current.busy && !current.recording) mutableState.value = current.copy(
      labPromptIndex = (current.labPromptIndex + 1) % WarehouseVoiceEvaluation.prompts.size,
    )
  }

  fun toggleLabRecording() {
    val current = mutableState.value
    if (current.busy || (current.recording && !current.labRecording)) return
    if (current.labRecording) {
      recordingTimeout?.cancel()
      mutableState.value = current.copy(recording = false, labRecording = false, busy = true)
      viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) {
          voiceEvaluation.save(recorder.stop(), current.labPromptIndex)
        } }.onSuccess {
          mutableState.value = mutableState.value.copy(labSavedCount = current.labSavedCount + 1)
        }.onFailure {
          recorder.cancel()
          mutableState.value = mutableState.value.copy(error = it.message ?: "Recording could not be saved.")
        }
        mutableState.value = mutableState.value.copy(busy = false, recordingElapsedMillis = 0, waveform = List(32) { 0f })
      }
    } else {
      runCatching { recorder.start(maxDurationMillis = MAX_RECORDING_MILLIS) }.onSuccess {
        mutableState.value = current.copy(recording = true, labRecording = true, error = null, recordingElapsedMillis = 0)
        val started = SystemClock.elapsedRealtime()
        recordingTimeout = viewModelScope.launch {
          while (mutableState.value.labRecording) {
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed >= MAX_RECORDING_MILLIS || recorder.durationLimitReached) {
              toggleLabRecording()
              break
            }
            mutableState.value = mutableState.value.copy(recordingElapsedMillis = elapsed,
              waveform = mutableState.value.waveform.drop(1) + recorder.level)
            delay(80)
          }
        }
      }.onFailure { mutableState.value = current.copy(error = it.message ?: "Microphone unavailable.") }
    }
  }

  fun cancelRecording() {
    if (!mutableState.value.recording) return
    recordingTimeout?.cancel()
    recorder.cancel()
    mutableState.value = mutableState.value.copy(recording = false, labRecording = false, recordingElapsedMillis = 0, waveform = List(32) { 0f })
  }

  fun permissionDenied() { mutableState.value = mutableState.value.copy(error = "Allow microphone access to talk, or use Type instead.") }
  fun clearError() { mutableState.value = mutableState.value.copy(error = null) }
  fun toggleTheme() {
    val dark = !mutableState.value.darkTheme
    mutableState.value = mutableState.value.copy(darkTheme = dark)
    preferences.edit().putBoolean("dark_theme", dark).apply()
  }

  private fun restoreChats() {
    val array = JSONArray(preferences.getString("activity_feed", "[]"))
    val chats = (0 until array.length()).map { index ->
      val c = array.getJSONObject(index)
      val messages = c.getJSONArray("messages")
      IndustrialChat(c.getString("id"), c.getString("title"), c.getLong("created_at"),
        (0 until messages.length()).map { i -> messages.getJSONObject(i).let { message ->
          val tools = message.optJSONArray("tools")
          IndustrialMessage(message.getString("role"), message.getString("text"),
            tools?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty(),
            message.optJSONObject("confirmation")?.let { ConfirmationReference(it.getString("request_key"), it.getInt("revision")) },
            message.optLong("processing_ms", -1).takeIf { it >= 0 })
        } }.filterNot { message ->
          // Remove only the obsolete app-authored mode notices from saved feeds.
          message.role == "assistant" && message.tools.isEmpty() && message.confirmation == null &&
            message.text in setOf(
              "SOP Advisor: describe a concern for guidance, then choose whether to prepare a report.",
              "General: stock, locations, replenishment and assigned tasks.",
            )
        })
    }
    // Earlier releases stored a mixed feed without mode tags. Preserve it in
    // the last selected mode rather than guessing which request belonged where.
    val migrated = chats.map { if (it.id == "worker-feed") it.copy(id = feedId()) else it }
      .filter { it.id in setOf(feedId("general"), feedId("sop")) }
      .distinctBy { it.id }
    mutableState.value = mutableState.value.copy(chats = migrated, activeChatId = feedId())
    persistChats()
  }

  private fun persistChats() {
    val array = JSONArray()
    mutableState.value.chats.forEach { chat ->
      val messages = JSONArray()
      chat.messages.forEach { message ->
        val row = JSONObject().put("role", message.role).put("text", message.text).put("tools", JSONArray(message.tools))
        message.confirmation?.let { row.put("confirmation", JSONObject().put("request_key", it.requestKey).put("revision", it.revision)) }
        message.processingMillis?.let { row.put("processing_ms", it) }
        messages.put(row)
      }
      array.put(JSONObject().put("id", chat.id).put("title", chat.title).put("created_at", chat.createdAt).put("messages", messages))
    }
    preferences.edit().putString("activity_feed", array.toString()).apply()
  }

  override fun onCleared() { recordingTimeout?.cancel(); recorder.cancel(); runtime.cancelRequest(); harness.shutdown() }
}
