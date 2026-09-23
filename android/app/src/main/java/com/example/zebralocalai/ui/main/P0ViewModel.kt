package com.example.zebralocalai.ui.main

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.zebralocalai.agent.P1AgentResult
import com.example.zebralocalai.agent.P1Risk
import com.example.zebralocalai.agent.ToolCallState
import com.example.zebralocalai.agent.GenerativeWarehouseAgent
import com.example.zebralocalai.agent.SqliteWarehouseRepository
import com.example.zebralocalai.audio.WavAudioRecorder
import com.example.zebralocalai.inference.AudioInferenceMode
import com.example.zebralocalai.inference.LiquidAudioRunner
import com.example.zebralocalai.inference.LiquidTextRunner
import com.example.zebralocalai.inference.ModelBundle
import com.example.zebralocalai.inference.P1BModel
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class P0Phase { MODEL_MISSING, READY, RECORDING, PROCESSING, COMPLETE, ERROR }

enum class Experience { P0, P1 }

enum class P1Phase {
  MODEL_MISSING,
  READY,
  RECORDING,
  PROCESSING,
  AWAITING_CONFIRMATION,
  COMPLETE,
  ERROR,
}

data class P0UiState(
  val phase: P0Phase = P0Phase.MODEL_MISSING,
  val status: String = "Import the matched LFM2.5-Audio Q4 model files.",
  val response: String = "",
  val microphonePermissionGranted: Boolean = false,
  val importedFiles: Set<String> = emptySet(),
  val elapsedMillis: Long? = null,
  val ttfsMillis: Long? = null,
  val decodeTokensPerSecond: Double? = null,
)

data class P1UiState(
  val phase: P1Phase = P1Phase.MODEL_MISSING,
  val status: String = "Import the matched LFM2.5-Audio Q4 model files.",
  val transcript: String = "",
  val result: P1AgentResult? = null,
  val audioElapsedMillis: Long? = null,
  val audioTtfsMillis: Long? = null,
  val audioDecodeTokensPerSecond: Double? = null,
  val p1bPromptTokensPerSecond: Double? = null,
  val p1bDecodeTokensPerSecond: Double? = null,
  val totalElapsedMillis: Long? = null,
  val modelsWarm: Boolean = false,
  val coldLoadMillis: Long? = null,
  val catalog: List<com.example.zebralocalai.agent.ProductCandidate> = emptyList(),
)

class P0ViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application.applicationContext
  private val modelDirectory = File(app.filesDir, "models/lfm25-audio-q4")
  private val p1bDirectory = File(app.filesDir, "models/lfm25-p1b")
  private val recorder = WavAudioRecorder(app)
  private val runner = LiquidAudioRunner(app)
  private val p1bRunner = LiquidTextRunner(app)
  private val repository = SqliteWarehouseRepository(app)
  private val p1Agent = GenerativeWarehouseAgent(repository = repository)
  private val mutableState = MutableStateFlow(initialState())
  val state: StateFlow<P0UiState> = mutableState.asStateFlow()
  private val mutableP1State = MutableStateFlow(initialP1State())
  val p1State: StateFlow<P1UiState> = mutableP1State.asStateFlow()
  private var work: Job? = null
  private var p1Warmup: Job? = null

  init {
    viewModelScope.launch {
      val catalog = withContext(Dispatchers.IO) { repository.catalog() }
      mutableP1State.value = mutableP1State.value.copy(catalog = catalog)
    }
  }

  fun onMicrophonePermissionResult(granted: Boolean, experience: Experience) {
    mutableState.value = mutableState.value.copy(microphonePermissionGranted = granted)
    if (granted) startRecording(experience)
    else if (experience == Experience.P0) fail("Microphone permission was denied.")
    else failP1("Microphone permission was denied.")
  }

  fun importModels(uris: List<Uri>) {
    if (mutableState.value.phase == P0Phase.PROCESSING || mutableState.value.phase == P0Phase.RECORDING) return
    work =
      viewModelScope.launch {
        mutableState.value = mutableState.value.copy(status = "Importing model files…", response = "")
        runCatching {
            withContext(Dispatchers.IO) {
              for (uri in uris) {
                val name = displayName(uri) ?: continue
                when {
                  ModelBundle.specs.containsKey(name) -> copyAndVerify(uri, checkNotNull(ModelBundle.specs[name]), modelDirectory)
                  name == P1BModel.SPEC.name -> copyAndVerify(uri, P1BModel.SPEC, p1bDirectory)
                }
              }
            }
          }
          .onSuccess { refreshModelState("Model import complete.") }
          .onFailure { fail(it.message ?: "Model import failed") }
      }
  }

  fun toggleRecording() {
    when (mutableState.value.phase) {
      P0Phase.READY, P0Phase.COMPLETE, P0Phase.ERROR -> startRecording(Experience.P0)
      P0Phase.RECORDING -> stopAndInfer()
      else -> Unit
    }
  }

  fun toggleP1Recording() {
    when (mutableP1State.value.phase) {
      P1Phase.READY, P1Phase.COMPLETE, P1Phase.ERROR -> startRecording(Experience.P1)
      P1Phase.RECORDING -> stopAndRoute()
      else -> Unit
    }
  }

  fun submitP1Text(text: String) {
    val current = mutableP1State.value
    if (current.phase !in setOf(P1Phase.READY, P1Phase.COMPLETE, P1Phase.ERROR)) return
    if (!current.modelsWarm) {
      prepareP1()
      return
    }
    if (!P1BModel.from(p1bDirectory).isRunnable) {
      failP1("Import the trained P1B Q8_0 model before running a task.")
      return
    }
    mutableP1State.value =
      current.copy(
        phase = P1Phase.PROCESSING,
        status = "Running this task through trained P1B…",
        transcript = text,
        result = null,
        audioElapsedMillis = null,
        audioTtfsMillis = null,
        audioDecodeTokensPerSecond = null,
        p1bPromptTokensPerSecond = null,
        p1bDecodeTokensPerSecond = null,
        totalElapsedMillis = null,
      )
    work =
      viewModelScope.launch {
        val started = System.nanoTime()
        runCatching {
            withContext(Dispatchers.IO) {
              val inference = p1bRunner.infer(P1BModel.from(p1bDirectory), text)
              inference to p1Agent.process(text, inference)
            }
          }
          .onSuccess { (inference, agentResult) ->
            val needsConfirmation =
              agentResult.prediction.risk == P1Risk.CONFIRM_REQUIRED && agentResult.proposal != null
            mutableP1State.value =
              mutableP1State.value.copy(
                phase = if (needsConfirmation) P1Phase.AWAITING_CONFIRMATION else P1Phase.COMPLETE,
                status = if (needsConfirmation) "Review proposed action" else "Decision complete — direct P1B inference",
                transcript = text,
                result = agentResult,
                p1bPromptTokensPerSecond = inference.promptTokensPerSecond,
                p1bDecodeTokensPerSecond = inference.decodeTokensPerSecond,
                totalElapsedMillis = (System.nanoTime() - started) / 1_000_000,
                modelsWarm = true,
              )
          }
          .onFailure { failP1(it.message ?: "P1B inference failed") }
      }
  }

  fun prepareP1() {
    if (mutableP1State.value.modelsWarm || p1Warmup?.isActive == true) return
    val audio = ModelBundle.from(modelDirectory)
    val p1b = P1BModel.from(p1bDirectory)
    if (!audio.isRunnable || !p1b.isRunnable) {
      refreshP1ModelState()
      return
    }
    mutableP1State.value = mutableP1State.value.copy(phase = P1Phase.PROCESSING, status = "Cold start · loading audio and trained P1B models…")
    p1Warmup = viewModelScope.launch {
      val started = System.nanoTime()
      runCatching {
          withContext(Dispatchers.IO) {
            runner.warmup(audio)
            p1bRunner.warmup(p1b)
          }
        }
        .onSuccess {
          val elapsed = (System.nanoTime() - started) / 1_000_000
          mutableP1State.value =
            mutableP1State.value.copy(
              phase = P1Phase.READY,
              status = "Ready · both models warm and resident",
              modelsWarm = true,
              coldLoadMillis = elapsed,
            )
        }
        .onFailure { failP1(it.message ?: "P1 model warm-up failed") }
    }
  }

  fun cancel() {
    recorder.cancel()
    runner.cancel()
    work?.cancel()
    refreshModelState("Cancelled.")
  }

  fun cancelP1() {
    recorder.cancel()
    runner.cancel()
    p1bRunner.cancel()
    work?.cancel()
    mutableP1State.value = mutableP1State.value.copy(phase = P1Phase.READY, status = "Cancelled · models remain warm")
  }

  fun clearResult() {
    if (mutableState.value.phase in setOf(P0Phase.RECORDING, P0Phase.PROCESSING)) return
    refreshModelState()
    mutableState.value =
      mutableState.value.copy(
        response = "",
        elapsedMillis = null,
        ttfsMillis = null,
        decodeTokensPerSecond = null,
      )
  }

  fun clearP1Result() {
    if (mutableP1State.value.phase in setOf(P1Phase.RECORDING, P1Phase.PROCESSING)) return
    val current = mutableP1State.value
    mutableP1State.value =
      P1UiState(
        phase = if (current.modelsWarm) P1Phase.READY else P1Phase.READY,
        status = if (current.modelsWarm) "Ready · warm start" else "Ready for model preparation",
        modelsWarm = current.modelsWarm,
        coldLoadMillis = current.coldLoadMillis,
        catalog = current.catalog,
      )
  }

  fun confirmP1Action() {
    val current = mutableP1State.value
    if (current.phase != P1Phase.AWAITING_CONFIRMATION) return
    val result = current.result ?: return
    mutableP1State.value = current.copy(phase = P1Phase.PROCESSING, status = "Saving and verifying the action locally…")
    work =
      viewModelScope.launch {
        val started = System.nanoTime()
        runCatching { withContext(Dispatchers.IO) { p1Agent.confirm(result) } }
          .onSuccess { confirmed ->
            val confirmationMillis = (System.nanoTime() - started) / 1_000_000
            mutableP1State.value =
              current.copy(
                phase = P1Phase.COMPLETE,
                status = "Action complete and verified",
                result = confirmed,
                totalElapsedMillis = (current.totalElapsedMillis ?: 0) + confirmationMillis,
              )
          }
          .onFailure { failP1(it.message ?: "Tool execution failed") }
      }
  }

  fun cancelP1Action() {
    val current = mutableP1State.value
    if (current.phase != P1Phase.AWAITING_CONFIRMATION) return
    mutableP1State.value =
      current.copy(
        phase = P1Phase.COMPLETE,
        status = "Proposed action cancelled",
        result =
          current.result?.copy(
            message = "Cancelled. No local data was changed.",
            verification = "Verified · no action executed",
            toolCalls =
              current.result.toolCalls.map {
                if (it.state == ToolCallState.PROPOSED) it.copy(state = ToolCallState.CANCELLED) else it
              },
          ),
      )
  }

  override fun onCleared() {
    recorder.cancel()
    runner.shutdown()
    p1bRunner.shutdown()
  }

  private fun startRecording(experience: Experience) {
    if (!hasMicrophonePermission()) {
      mutableState.value = mutableState.value.copy(microphonePermissionGranted = false)
      return
    }
    if (!ModelBundle.from(modelDirectory).isRunnable) {
      if (experience == Experience.P0) fail("Import all four matched Q4 model files before recording.")
      else failP1("Import all four matched Q4 model files before recording.")
      return
    }
    if (experience == Experience.P1 && !P1BModel.from(p1bDirectory).isRunnable) {
      failP1("Import the trained P1B Q8_0 model before recording.")
      return
    }
    runCatching { recorder.start() }
      .onSuccess {
        if (experience == Experience.P0) {
          mutableState.value =
            mutableState.value.copy(
              phase = P0Phase.RECORDING,
              status = "Listening… Tap again to stop and send.",
              response = "",
              elapsedMillis = null,
              ttfsMillis = null,
              decodeTokensPerSecond = null,
            )
        } else {
          mutableP1State.value =
            mutableP1State.value.copy(
              phase = P1Phase.RECORDING,
              status = "Listening… Tap again to analyze.",
              transcript = "",
              result = null,
              audioElapsedMillis = null,
              audioTtfsMillis = null,
              audioDecodeTokensPerSecond = null,
              p1bPromptTokensPerSecond = null,
              p1bDecodeTokensPerSecond = null,
              totalElapsedMillis = null,
            )
        }
      }
      .onFailure {
        if (experience == Experience.P0) fail(it.message ?: "Recording failed")
        else failP1(it.message ?: "Recording failed")
      }
  }

  private fun stopAndInfer() {
    val audioFile = runCatching { recorder.stop() }.getOrElse { fail(it.message ?: "Recording failed"); return }
    mutableState.value = mutableState.value.copy(phase = P0Phase.PROCESSING, status = "Processing entirely on this TC501…")
    work =
      viewModelScope.launch {
        try {
          runCatching { withContext(Dispatchers.IO) { runner.infer(ModelBundle.from(modelDirectory), audioFile) } }
            .onSuccess { result ->
              mutableState.value =
                mutableState.value.copy(
                  phase = P0Phase.COMPLETE,
                  status = "Complete — local inference",
                  response = result.text,
                  elapsedMillis = result.elapsedMillis,
                  ttfsMillis = result.ttfsMillis,
                  decodeTokensPerSecond = result.decodeTokensPerSecond,
                )
            }
            .onFailure { fail(it.message ?: "Inference failed") }
        } finally {
          audioFile.delete()
        }
      }
  }

  private fun stopAndRoute() {
    val audioFile = runCatching { recorder.stop() }.getOrElse { failP1(it.message ?: "Audio capture failed"); return }
    mutableP1State.value = mutableP1State.value.copy(phase = P1Phase.PROCESSING, status = "Transcribing and deciding locally…")
    work =
      viewModelScope.launch {
        val overallStarted = System.nanoTime()
        try {
          runCatching {
              withContext(Dispatchers.IO) {
                val audioResult = runner.infer(ModelBundle.from(modelDirectory), audioFile, AudioInferenceMode.TRANSCRIPTION)
                val p1bInference = p1bRunner.infer(P1BModel.from(p1bDirectory), audioResult.text)
                val agentResult = p1Agent.process(audioResult.text, p1bInference)
                Triple(audioResult, p1bInference, agentResult)
              }
            }
            .onSuccess { (audioResult, p1bInference, agentResult) ->
              val needsConfirmation =
                agentResult.prediction.risk == P1Risk.CONFIRM_REQUIRED && agentResult.proposal != null
              mutableP1State.value =
                mutableP1State.value.copy(
                  phase = if (needsConfirmation) P1Phase.AWAITING_CONFIRMATION else P1Phase.COMPLETE,
                  status = if (needsConfirmation) "Review proposed action" else "Decision complete — local inference",
                  transcript = audioResult.text,
                  result = agentResult,
                  audioElapsedMillis = audioResult.elapsedMillis,
                  audioTtfsMillis = audioResult.ttfsMillis,
                  audioDecodeTokensPerSecond = audioResult.decodeTokensPerSecond,
                  p1bPromptTokensPerSecond = p1bInference.promptTokensPerSecond,
                  p1bDecodeTokensPerSecond = p1bInference.decodeTokensPerSecond,
                  totalElapsedMillis = (System.nanoTime() - overallStarted) / 1_000_000,
                  modelsWarm = true,
                )
            }
            .onFailure { failP1(it.message ?: "P1 inference failed") }
        } finally {
          audioFile.delete()
        }
      }
  }

  private fun refreshModelState(message: String? = null) {
    val imported = modelDirectory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
    val runnable = ModelBundle.from(modelDirectory).isRunnable
    mutableState.value =
      mutableState.value.copy(
        phase = if (runnable) P0Phase.READY else P0Phase.MODEL_MISSING,
        status = message ?: if (runnable) "Ready for local audio inference." else "Import the matched LFM2.5-Audio Q4 model files.",
        importedFiles = imported,
        microphonePermissionGranted = hasMicrophonePermission(),
      )
    refreshP1ModelState(message)
  }

  private fun refreshP1ModelState(message: String? = null) {
    val ready = ModelBundle.from(modelDirectory).isRunnable && P1BModel.from(p1bDirectory).isRunnable
    val current = mutableP1State.value
    mutableP1State.value =
      P1UiState(
        phase = if (ready) P1Phase.READY else P1Phase.MODEL_MISSING,
        status = message ?: if (ready) "Models installed · open P1 to warm them." else "Import both LFM2.5-Audio Q4 and trained P1B Q8_0.",
        modelsWarm = current.modelsWarm,
        coldLoadMillis = current.coldLoadMillis,
        catalog = current.catalog,
      )
  }

  private fun initialState(): P0UiState {
    val imported = modelDirectory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
    val ready = ModelBundle.from(modelDirectory).isRunnable && P1BModel.from(p1bDirectory).isRunnable
    return P0UiState(
      phase = if (ready) P0Phase.READY else P0Phase.MODEL_MISSING,
      status = if (ready) "Ready for local audio inference." else "Import the matched LFM2.5-Audio Q4 model files.",
      microphonePermissionGranted = hasMicrophonePermission(),
      importedFiles = imported,
    )
  }

  private fun initialP1State(): P1UiState {
    val ready = ModelBundle.from(modelDirectory).isRunnable
    return P1UiState(
      phase = if (ready) P1Phase.READY else P1Phase.MODEL_MISSING,
      status = if (ready) "Models installed · open P1 to warm them." else "Import both LFM2.5-Audio Q4 and trained P1B Q8_0.",
    )
  }

  private fun hasMicrophonePermission() =
    ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private fun displayName(uri: Uri): String? =
    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    }

  private fun copyAndVerify(uri: Uri, spec: ModelBundle.Companion.FileSpec, directory: File) {
    directory.mkdirs()
    val destination = File(directory, spec.name)
    val partial = File(directory, "${spec.name}.partial")
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    try {
      app.contentResolver.openInputStream(uri).use { input ->
        checkNotNull(input) { "Unable to open ${spec.name}" }
        partial.outputStream().buffered().use { output ->
          val buffer = ByteArray(1024 * 1024)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            bytes += count
          }
        }
      }
      check(bytes == spec.bytes) { "${spec.name} has the wrong size" }
      val actual = digest.digest().joinToString("") { "%02x".format(it) }
      check(actual == spec.sha256) { "${spec.name} failed SHA-256 verification" }
      check(partial.renameTo(destination)) { "Unable to activate ${spec.name}" }
    } finally {
      partial.delete()
    }
  }

  private fun fail(message: String) {
    mutableState.value = mutableState.value.copy(phase = P0Phase.ERROR, status = message)
  }

  private fun failP1(message: String) {
    mutableP1State.value = mutableP1State.value.copy(phase = P1Phase.ERROR, status = message)
  }
}
