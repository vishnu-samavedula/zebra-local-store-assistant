package com.example.zebralocalai.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidAudioServerDeviceTest {
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
      val result = runner.infer(bundle, wav)
      assertTrue("Model returned blank text", result.text.isNotBlank())
      assertNotNull("No first streamed text timing was captured", result.ttfsMillis)
      assertTrue("Total inference time was not recorded", result.elapsedMillis > 0)

      val asrResult = runner.infer(bundle, wav, AudioInferenceMode.TRANSCRIPTION)
      assertTrue("Warm ASR inference returned blank text", asrResult.text.isNotBlank())
      assertNotNull("Warm ASR inference did not stream text", asrResult.ttfsMillis)
    } finally {
      runner.shutdown()
      wav.delete()
    }
  }
}
