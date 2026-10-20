package com.example.zebralocalai.inference

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.speech.SpeechRecognizer
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CpuAudioRuntimeDeviceTest {
  @Test
  fun audioRequestCarriesPriorTurnsAndTrustedToolContext() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val runtime = CpuAudioRuntime(context)
    val file = File(context.cacheDir, "history-audio-test.wav").apply { writeBytes(byteArrayOf(1, 2)) }
    try {
      val history = listOf("user" to "Request more hoodies", "assistant" to "Which variant?",
        "tool" to "{\"phase\":\"clarifying\",\"product_name\":\"Cloudweight Hoodie\"}")
      val messages = runtime.audioMessages(file, history)
      assertEquals(4, messages.length())
      history.forEachIndexed { index, (role, content) ->
        assertEquals(role, messages.getJSONObject(index).getString("role"))
        assertEquals(content, messages.getJSONObject(index).getString("content"))
      }
      assertEquals("input_audio", messages.getJSONObject(3).getJSONArray("content").getJSONObject(0).getString("type"))
      Log.i("Audio600MAppSmoke", "Offline recognizer available=${SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}")
    } finally { runtime.shutdown(); file.delete() }
  }

  @Test
  fun packagedWarehouseHandlesTextAndAudioInOneProcess() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val runtime = CpuAudioRuntime(context)
    val wav = File(context.cacheDir, "cpu-audio-runtime-question.wav")
    File(context.filesDir, "models/warehouse-test/warehouse-iteration-20261013g/I01-1-0.wav").inputStream().use { input ->
      wav.outputStream().use(input::copyTo)
    }
    try {
      runtime.warmup()
      val server = runtime.serverInstance()
      assertNotNull("No resident server", server)
      val text = runtime.replyText("How many Cloudweight Hoodies in ink, medium are available?")
      assertTrue("Wrong typed inventory command: ${text.text}", text.text.contains("inventory_lookup(operation=\"count_stock\""))
      assertSame(server, runtime.serverInstance())
      val repeated = runtime.replyText("How many Cloudweight Hoodies in ink, medium are available?")
      assertEquals("Caching changed the repeated command", text.text, repeated.text)
      assertTrue("App request did not reuse prompt tokens", (repeated.cachedPromptTokens ?: 0) > 0)
      Log.i("Audio600MAppSmoke", "Repeated app request cached=${repeated.cachedPromptTokens} firstTextMs=${repeated.ttfsMillis}")
      val stateReply = runtime.replyText("How many Cloudweight Hoodies in ink, medium are available?", listOf(
        "tool" to "{\"harness_state\":{\"phase\":\"idle\",\"focused_call\":null}}",
      ))
      assertTrue("Trusted state context rejected", stateReply.text.isNotBlank())
      assertSame(server, runtime.serverInstance())
      val audioReply = runtime.replyAudio(wav, listOf(
        "user" to "How many Cloudweight Hoodies in ink, medium are available?", "assistant" to text.text,
      ))
      assertTrue("Wrong spoken inventory command: ${audioReply.text}", audioReply.text.contains("inventory_lookup(operation=\"count_stock\""))
      assertSame(server, runtime.serverInstance())
      listOf("text" to text, "audioReply" to audioReply).forEach { (label, result) ->
        assertNotNull("No first-text timing for $label", result.ttfsMillis)
        assertNotNull("No token usage for $label", result.completionTokens)
        Log.i("Audio600MAppSmoke", "$label input=${result.promptTokens} output=${result.completionTokens} " +
          "firstTextMs=${result.ttfsMillis} totalMs=${result.elapsedMillis} decodeTps=${result.decodeTokensPerSecond}")
      }
    } finally {
      runtime.shutdown()
      wav.delete()
    }
  }
}
