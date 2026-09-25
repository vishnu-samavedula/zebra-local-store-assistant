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
import com.example.zebralocalai.agent.P1ConversationSession
import com.example.zebralocalai.agent.P1ConversationTurn
import com.example.zebralocalai.agent.P1Risk
import com.example.zebralocalai.agent.ToolCallState
import com.example.zebralocalai.agent.GenerativeWarehouseAgent
import com.example.zebralocalai.agent.SqliteWarehouseRepository
import com.example.zebralocalai.audio.WavAudioRecorder
import com.example.zebralocalai.inference.AudioInferenceMode
import com.example.zebralocalai.inference.InferenceResult
import com.example.zebralocalai.inference.LiquidAudioRunner
import com.example.zebralocalai.inference.LiquidTextRunner
import com.example.zebralocalai.inference.ModelBundle
import com.example.zebralocalai.inference.P1BModel
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class P1Phase {
  MODEL_MISSING,
  READY,
  RECORDING,
  PROCESSING,
  AWAITING_CONFIRMATION,
  COMPLETE,
  ERROR,
}

data class StoreAssistantUiState(
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
  val conversationTurns: List<P1ConversationTurn> = emptyList(),
  val canContinue: Boolean = true,
  val microphonePermissionGranted: Boolean = false,
  val importedAudioFiles: Set<String> = emptySet(),
)

class StoreAssistantViewModel(application: Application) : AndroidViewModel(application) {
  private val modelDirectory = File(application.filesDir, "models/lfm25-audio-q4")
  private val p1bDirectory = File(application.filesDir, "models/lfm25-p1b")
  private val recorder = WavAudioRecorder(application)
  private val runner = LiquidAudioRunner(application)
  private val p1bRunner = LiquidTextRunner(application)
  private val repository = SqliteWarehouseRepository(application)
  private val p1Agent = GenerativeWarehouseAgent(repository = repository)
  private val mutableState = MutableStateFlow(initialState())
  val state: StateFlow<StoreAssistantUiState> = mutableState.asStateFlow()
  private var work: Job? = null
  private var p1Warmup: Job? = null
  private var p1Conversation = P1ConversationSession()

  init {
    viewModelScope.launch {
      val catalog = withContext(Dispatchers.IO) { repository.catalog() }
      mutableState.value = mutableState.value.copy(catalog = catalog)
    }
  }

  fun onMicrophonePermissionResult(granted: Boolean) {
    mutableState.value = mutableState.value.copy(microphonePermissionGranted = granted)
    if (granted) startRecording() else fail("Microphone permission was denied.")
  }

  fun importModels(uris: List<Uri>) {
    if (mutableState.value.phase in setOf(P1Phase.PROCESSING, P1Phase.RECORDING)) return
    work =
      viewModelScope.launch {
        p1Warmup?.cancel()
        runner.shutdown()
        p1bRunner.shutdown()
        mutableState.value = mutableState.value.copy(modelsWarm = false)
        mutableState.value = mutableState.value.copy(status = "Importing model files…")
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
          .onSuccess {
            refreshModelState("Model import complete · preparing models…")
            prepare()
          }
          .onFailure { failure -> if (failure !is CancellationException) fail(failure.message ?: "Model import failed") }
      }
  }

  fun toggleRecording() {
    when (mutableState.value.phase) {
      P1Phase.READY, P1Phase.AWAITING_CONFIRMATION, P1Phase.COMPLETE, P1Phase.ERROR -> {
        if (p1Conversation.canContinue) startRecording()
      }
      P1Phase.RECORDING -> stopAndRoute()
      else -> Unit
    }
  }

  fun submitText(text: String) {
    val current = mutableState.value
    if (current.phase !in setOf(P1Phase.READY, P1Phase.AWAITING_CONFIRMATION, P1Phase.COMPLETE, P1Phase.ERROR)) return
    if (!p1Conversation.canContinue) return
    if (!current.modelsWarm) {
      prepare()
      return
    }
    if (!P1BModel.from(p1bDirectory).isRunnable) {
      fail("Import the trained P1B Q4_K model before running a task.")
      return
    }
    mutableState.value =
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
        val conversationRequest = p1Conversation.request(text)
        runCatching {
            withContext(Dispatchers.IO) {
              val inference = p1bRunner.infer(P1BModel.from(p1bDirectory), conversationRequest.modelInput)
              inference to p1Agent.process(conversationRequest.auditTranscript, inference)
            }
          }
          .onSuccess { (inference, agentResult) ->
            p1Conversation = p1Conversation.record(text, agentResult)
            val needsConfirmation =
              agentResult.prediction.risk == P1Risk.CONFIRM_REQUIRED && agentResult.proposal != null
            mutableState.value =
              mutableState.value.copy(
                phase = if (needsConfirmation) P1Phase.AWAITING_CONFIRMATION else P1Phase.COMPLETE,
                status = conversationStatus(needsConfirmation, agentResult),
                transcript = text,
                result = agentResult,
                p1bPromptTokensPerSecond = inference.promptTokensPerSecond,
                p1bDecodeTokensPerSecond = inference.decodeTokensPerSecond,
                totalElapsedMillis = (System.nanoTime() - started) / 1_000_000,
                modelsWarm = true,
                conversationTurns = p1Conversation.turns,
                canContinue = p1Conversation.canContinue,
              )
          }
          .onFailure { failure -> if (failure !is CancellationException) fail(failure.message ?: "P1B inference failed") }
      }
  }

  fun prepare() {
    if (mutableState.value.modelsWarm || p1Warmup?.isActive == true) return
    val audio = ModelBundle.from(modelDirectory)
    val p1b = P1BModel.from(p1bDirectory)
    if (!audio.isRunnable || !p1b.isRunnable) {
      refreshModelState()
      return
    }
    mutableState.value = mutableState.value.copy(phase = P1Phase.PROCESSING, status = "Cold start · loading audio and trained P1B models…")
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
          mutableState.value =
            mutableState.value.copy(
              phase = P1Phase.READY,
              status = "Ready · both models warm and resident",
              modelsWarm = true,
              coldLoadMillis = elapsed,
            )
        }
        .onFailure { failure -> if (failure !is CancellationException) fail(failure.message ?: "P1 model warm-up failed") }
    }
  }

  fun cancel() {
    recorder.cancel()
    runner.cancel()
    p1bRunner.cancel()
    work?.cancel()
    p1Warmup?.cancel()
    mutableState.value =
      mutableState.value.copy(
        phase = if (mutableState.value.modelsWarm) P1Phase.READY else P1Phase.ERROR,
        status = if (mutableState.value.modelsWarm) "Cancelled · models remain warm" else "Model preparation cancelled",
      )
  }

  fun clearResult() {
    if (mutableState.value.phase in setOf(P1Phase.RECORDING, P1Phase.PROCESSING)) return
    val current = mutableState.value
    p1Conversation = P1ConversationSession()
    mutableState.value =
      StoreAssistantUiState(
        phase = P1Phase.READY,
        status = if (current.modelsWarm) "Ready · warm start" else "Ready for model preparation",
        modelsWarm = current.modelsWarm,
        coldLoadMillis = current.coldLoadMillis,
        catalog = current.catalog,
        canContinue = true,
        microphonePermissionGranted = current.microphonePermissionGranted,
        importedAudioFiles = current.importedAudioFiles,
      )
  }

  fun confirmAction() {
    val current = mutableState.value
    if (current.phase != P1Phase.AWAITING_CONFIRMATION) return
    val result = current.result ?: return
    mutableState.value = current.copy(phase = P1Phase.PROCESSING, status = "Saving and verifying the action locally…")
    work =
      viewModelScope.launch {
        val started = System.nanoTime()
        runCatching { withContext(Dispatchers.IO) { p1Agent.confirm(result) } }
          .onSuccess { confirmed ->
            p1Conversation = p1Conversation.close()
            val confirmationMillis = (System.nanoTime() - started) / 1_000_000
            mutableState.value =
              current.copy(
                phase = P1Phase.COMPLETE,
                status = "Action complete and verified",
                result = confirmed,
                totalElapsedMillis = (current.totalElapsedMillis ?: 0) + confirmationMillis,
                conversationTurns = p1Conversation.turns,
                canContinue = false,
              )
          }
          .onFailure { failure -> if (failure !is CancellationException) fail(failure.message ?: "Tool execution failed") }
      }
  }

  fun cancelAction() {
    val current = mutableState.value
    if (current.phase != P1Phase.AWAITING_CONFIRMATION) return
    mutableState.value =
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
        conversationTurns = p1Conversation.turns,
        canContinue = p1Conversation.canContinue,
      )
  }

  override fun onCleared() {
    recorder.cancel()
    runner.shutdown()
    p1bRunner.shutdown()
  }

  private fun startRecording() {
    if (!hasMicrophonePermission()) {
      mutableState.value = mutableState.value.copy(microphonePermissionGranted = false)
      return
    }
    if (!ModelBundle.from(modelDirectory).isRunnable) {
      fail("Import all four matched Q4 model files before recording.")
      return
    }
    if (!P1BModel.from(p1bDirectory).isRunnable) {
      fail("Import the trained P1B Q4_K model before recording.")
      return
    }
    runCatching { recorder.start() }
      .onSuccess {
        mutableState.value =
          mutableState.value.copy(
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
      .onFailure { fail(it.message ?: "Recording failed") }
  }

  private fun stopAndRoute() {
    val audioFile = runCatching { recorder.stop() }.getOrElse { fail(it.message ?: "Audio capture failed"); return }
    mutableState.value = mutableState.value.copy(phase = P1Phase.PROCESSING, status = "Transcribing and deciding locally…")
    work =
      viewModelScope.launch {
        val overallStarted = System.nanoTime()
        try {
          runCatching {
              withContext(Dispatchers.IO) {
                val audioResult = runner.infer(ModelBundle.from(modelDirectory), audioFile, AudioInferenceMode.TRANSCRIPTION)
                val conversationRequest = p1Conversation.request(audioResult.text)
                val p1bInference = p1bRunner.infer(P1BModel.from(p1bDirectory), conversationRequest.modelInput)
                val agentResult = p1Agent.process(conversationRequest.auditTranscript, p1bInference)
                RoutedP1Result(audioResult, p1bInference, agentResult, audioResult.text)
              }
            }
            .onSuccess { routed ->
              val (audioResult, p1bInference, agentResult, workerText) = routed
              p1Conversation = p1Conversation.record(workerText, agentResult)
              val needsConfirmation =
                agentResult.prediction.risk == P1Risk.CONFIRM_REQUIRED && agentResult.proposal != null
              mutableState.value =
                mutableState.value.copy(
                  phase = if (needsConfirmation) P1Phase.AWAITING_CONFIRMATION else P1Phase.COMPLETE,
                  status = conversationStatus(needsConfirmation, agentResult),
                  transcript = audioResult.text,
                  result = agentResult,
                  audioElapsedMillis = audioResult.elapsedMillis,
                  audioTtfsMillis = audioResult.ttfsMillis,
                  audioDecodeTokensPerSecond = audioResult.decodeTokensPerSecond,
                  p1bPromptTokensPerSecond = p1bInference.promptTokensPerSecond,
                  p1bDecodeTokensPerSecond = p1bInference.decodeTokensPerSecond,
                  totalElapsedMillis = (System.nanoTime() - overallStarted) / 1_000_000,
                  modelsWarm = true,
                  conversationTurns = p1Conversation.turns,
                  canContinue = p1Conversation.canContinue,
                )
            }
            .onFailure { failure -> if (failure !is CancellationException) fail(failure.message ?: "P1 inference failed") }
        } finally {
          audioFile.delete()
        }
      }
  }

  private fun refreshModelState(message: String? = null) {
    val imported = modelDirectory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
    val ready = ModelBundle.from(modelDirectory).isRunnable && P1BModel.from(p1bDirectory).isRunnable
    val current = mutableState.value
    mutableState.value =
      StoreAssistantUiState(
        phase = if (ready) P1Phase.READY else P1Phase.MODEL_MISSING,
        status = message ?: if (ready) "Models installed · preparing local runtimes." else "Import both LFM2.5-Audio Q4 and trained P1B Q4_K.",
        modelsWarm = current.modelsWarm && ready,
        coldLoadMillis = current.coldLoadMillis,
        catalog = current.catalog,
        microphonePermissionGranted = hasMicrophonePermission(),
        importedAudioFiles = imported,
      )
  }

  private fun initialState(): StoreAssistantUiState {
    val imported = modelDirectory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
    val ready = ModelBundle.from(modelDirectory).isRunnable && P1BModel.from(p1bDirectory).isRunnable
    return StoreAssistantUiState(
      phase = if (ready) P1Phase.READY else P1Phase.MODEL_MISSING,
      status = if (ready) "Models installed · preparing local runtimes." else "Import both LFM2.5-Audio Q4 and trained P1B Q4_K.",
      microphonePermissionGranted = hasMicrophonePermission(),
      importedAudioFiles = imported,
    )
  }

  private fun hasMicrophonePermission() =
    ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private fun displayName(uri: Uri): String? =
    getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    }

  private fun copyAndVerify(uri: Uri, spec: ModelBundle.Companion.FileSpec, directory: File) {
    directory.mkdirs()
    val destination = File(directory, spec.name)
    val partial = File(directory, "${spec.name}.partial")
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    try {
      getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
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
      try {
        Files.move(
          partial.toPath(),
          destination.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      partial.delete()
    }
  }

  private fun fail(message: String) {
    mutableState.value = mutableState.value.copy(phase = P1Phase.ERROR, status = message)
  }

  private fun conversationStatus(needsConfirmation: Boolean, result: P1AgentResult): String =
    when {
      needsConfirmation -> "Review proposed action"
      result.prediction.missingFields.isNotEmpty() -> "More detail needed · turn ${p1Conversation.turns.size}/${P1ConversationSession.MAX_TURNS}"
      p1Conversation.canContinue -> "Complete · continue or start a new request"
      else -> "Complete · three-turn session limit reached"
    }

  private data class RoutedP1Result(
    val audio: InferenceResult,
    val inference: com.example.zebralocalai.inference.P1BInference,
    val agentResult: P1AgentResult,
    val workerText: String,
  )
}
