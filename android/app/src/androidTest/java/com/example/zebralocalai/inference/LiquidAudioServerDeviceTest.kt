package com.example.zebralocalai.inference

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zebralocalai.agent.GenerativeWarehouseAgent
import com.example.zebralocalai.agent.P1ConversationSession
import com.example.zebralocalai.agent.P1Risk
import com.example.zebralocalai.agent.P1Tool
import com.example.zebralocalai.agent.SqliteWarehouseRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidAudioServerDeviceTest {
  @Test
  fun vagueAddStockClarifiesThenCompletes() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val databaseName = "audio600m-vague-restock-test.db"
    context.deleteDatabase(databaseName)
    val bundle = ModelBundle.from(File(context.filesDir, "models/lfm25-audio-q4"))
    val runner = LiquidAudioRunner(context)
    val agent = GenerativeWarehouseAgent(SqliteWarehouseRepository(context, databaseName))
    try {
      val firstText = "Request replenishment: add exactly 10 units of sneakers."
      val firstInference = runner.inferText(bundle, firstText)
      val firstResult = agent.process(firstText, firstInference)
      Log.i(
        "Audio600MVague",
        "turn=1 calls=${firstInference.toolCalls} risk=${firstResult.prediction.risk} " +
          "missing=${firstResult.prediction.missingFields} message=${firstResult.message}",
      )

      var session = P1ConversationSession().record(firstText, firstResult)
      val secondText = "White size 9 to A4-01."
      val request = session.request(secondText)
      val secondInference = request.enforcePendingWorkflow(runner.inferText(bundle, request.chatMessages))
      val secondResult = agent.process(request.auditTranscript, secondInference)
      session = session.record(secondText, secondResult)
      Log.i(
        "Audio600MVague",
        "turn=2 input=${request.modelInput} calls=${secondInference.toolCalls} malformed=${secondInference.malformedToolCall} " +
          "text=${secondInference.text} risk=${secondResult.prediction.risk} " +
          "missing=${secondResult.prediction.missingFields} message=${secondResult.message} raw=${secondInference.rawOutput.take(1200)}",
      )

      assertTrue(firstResult.proposal == null)
      assertTrue(firstResult.prediction.missingFields.isNotEmpty())
      assertEquals(P1Risk.CONFIRM_REQUIRED, secondResult.prediction.risk)
      assertTrue(secondResult.prediction.missingFields.isEmpty())
      assertEquals(2, session.turns.size)
    } finally {
      runner.shutdown()
      context.deleteDatabase(databaseName)
    }
  }

  @Test
  fun warehouseCheckpointProducesNativeToolCallFromText() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bundle = ModelBundle.from(File(context.filesDir, "models/lfm25-audio-q4"))
    assertTrue("Matched model bundle is not staged", bundle.isRunnable)

    val runner = LiquidAudioRunner(context)
    try {
      val result = runner.inferText(bundle, "Check stock for blue trail shoes.")
      Log.i("Audio600MText", "calls=${result.toolCalls} output=${result.rawOutput}")
      assertEquals(1, result.toolCalls.size)
      assertEquals(P1Tool.INVENTORY_SEARCH, result.toolCalls.single().tool)
      assertEquals("blue trail shoes", result.toolCalls.single().arguments["semantic_query"])
    } finally {
      runner.shutdown()
    }
  }

  @Test
  fun multiReadRecipePromptProbe() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bundle = ModelBundle.from(File(context.filesDir, "models/lfm25-audio-q4"))
    assertTrue("Matched model bundle is not staged", bundle.isRunnable)

    val runner = LiquidAudioRunner(context)
    try {
      val prompts =
        listOf(
          "Check stock for SKU-3201-TAN-OS and SKU-4902-CLR-500.",
          "Check both Harbor Classic T-Shirt and Foundry Straight Jean.",
          "Check stock for Harbor Classic T-Shirt, and also check stock for Foundry Straight Jean.",
          "Check inventory for Harbor Classic T-Shirt. Also check inventory for Foundry Straight Jean.",
        )
      prompts.forEachIndexed { index, prompt ->
        val result = runner.inferText(bundle, prompt)
        Log.i(
          "Audio600MMulti",
          "case=$index prompt=$prompt calls=${result.toolCalls} malformed=${result.malformedToolCall}",
        )
      }
    } finally {
      runner.shutdown()
    }
  }

  @Test
  fun replenishmentTemplatePromptProbe() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bundle = ModelBundle.from(File(context.filesDir, "models/lfm25-audio-q4"))
    assertTrue("Matched model bundle is not staged", bundle.isRunnable)

    val runner = LiquidAudioRunner(context)
    try {
      val prompts =
        listOf(
          "Add inventory: 8 black slides to C1-02",
          "Request replenishment: add 12 navy shirts to B2-04",
          "Add exactly 8 units of SKU-4103-BLK-100 to C1-02.",
          "Request replenishment: add exactly 12 units of SKU-2202-NVY-M to B2-04.",
          "Add 8 Black Coastline Slide size 10 to C1-02.",
          "Replenish B2-04 with 12 Navy Harbor Classic T-Shirt size M.",
          "Request replenishment: add exactly 12 units of Navy Harbor Classic T-Shirt size M to B2-04.",
        )
      prompts.forEachIndexed { index, prompt ->
        val result = runner.inferText(bundle, prompt)
        Log.i("Audio600MRestock", "case=$index prompt=$prompt calls=${result.toolCalls} malformed=${result.malformedToolCall}")
      }
    } finally {
      runner.shutdown()
    }
  }

  @Test
  fun warehouseZeroShotProbeDirectory() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bundle = ModelBundle.from(File(context.filesDir, "models/lfm25-audio-q4"))
    val probeDirectory = File(context.filesDir, "audio600m-zero-shot-v1")
    val probes = probeDirectory.listFiles { file -> file.isFile && file.extension == "wav" }.orEmpty().sortedBy(File::getName)
    assertTrue("Matched model bundle is not staged", bundle.isRunnable)
    assertTrue("Expected 10 staged warehouse probes, found ${probes.size}", probes.size == 10)

    val runner = LiquidAudioRunner(context)
    try {
      val loadStarted = System.nanoTime()
      runner.warmup(bundle)
      val coldLoadMs = (System.nanoTime() - loadStarted) / 1_000_000
      Log.i("Audio600MSuite", "coldLoadMs=$coldLoadMs cases=${probes.size}")
      probes.forEach { wav ->
        val result = runner.infer(bundle, wav, AudioInferenceMode.ASR)
        Log.i(
          "Audio600MProbe",
          "case=${wav.nameWithoutExtension} output=${result.text} " +
            "ttfsMs=${result.ttfsMillis} totalMs=${result.elapsedMillis}",
        )
      }
      val performanceLog =
        runner
          .diagnosticLogTail(20_000)
          .lineSequence()
          .filter { line ->
            line.contains("audio_tokens->") ||
              line.contains("tokens  per second") ||
              line.contains("LIQUID_AUDIO_PERF")
          }
          .joinToString("\n")
      Log.i("Audio600MSuitePerf", performanceLog)
    } finally {
      runner.shutdown()
    }
  }

  @Test
  fun knownWavProducesStreamedText() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bundle = ModelBundle.from(File(context.filesDir, "models/lfm25-audio-q4"))
    assertTrue("Matched model bundle is not staged", bundle.isRunnable)

    val wav = File(context.cacheDir, "device-test-question.wav")
    InstrumentationRegistry.getInstrumentation().context.assets.open("question.wav").use { input ->
      wav.outputStream().use(input::copyTo)
    }
    val runner = LiquidAudioRunner(context)
    try {
      val loadStarted = System.nanoTime()
      runner.warmup(bundle)
      val coldLoadMs = (System.nanoTime() - loadStarted) / 1_000_000
      val result = runner.infer(bundle, wav, AudioInferenceMode.ASR)
      Log.i(
        "Audio600MBaseline",
        "output=${result.text} coldLoadMs=$coldLoadMs promptTokens=${result.promptTokens} " +
          "completionTokens=${result.completionTokens} cachedPromptTokens=${result.cachedPromptTokens} " +
          "ttfsMs=${result.ttfsMillis} totalMs=${result.elapsedMillis} " +
          "decodeTps=${result.decodeTokensPerSecond}",
      )
      val performanceLog =
        runner
          .diagnosticLogTail()
          .lineSequence()
          .filter { line ->
            line.contains("audio_tokens->") ||
              line.contains("tokens  per second") ||
              line.contains("LIQUID_AUDIO_PERF")
          }
          .joinToString("\n")
      Log.i("Audio600MServer", performanceLog)
      assertTrue("Model returned blank text", result.text.isNotBlank())
      assertNotNull("No first streamed text timing was captured", result.ttfsMillis)
      assertTrue("Total inference time was not recorded", result.elapsedMillis > 0)
    } finally {
      runner.shutdown()
      wav.delete()
    }
  }
}
