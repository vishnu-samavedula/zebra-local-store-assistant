package com.example.zebralocalai.ui.industrial

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zebralocalai.inference.InferenceResult
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LatencyResetDeviceTest {
  @Test fun resetStartsFreshWindowWithoutDeletingTurnRecords() {
    val app = ApplicationProvider.getApplicationContext<Context>()
    val directory = File(app.cacheDir, "latency-reset-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(app) { override fun getFilesDir(): File = directory }
    try {
      val logger = TurnReviewLogger(context)
      fun append(milliseconds: Long) {
        logger.append("test", "event-$milliseconds", "Test request", "text", JSONObject().put("kind", "result"),
          InferenceResult("Test reply", milliseconds, 10, null, 5, 3, 0, "", "stop"))
      }
      append(123)
      assertEquals(listOf(123L), logger.latencySamples().map { it.totalMillis })
      val file = File(directory, "warehouse-turn-review.jsonl")
      val previous = file.readText()
      logger.resetLatency()
      assertTrue(logger.latencySamples().isEmpty())
      assertEquals(previous, file.readText())
      assertTrue(TurnReviewLogger(context).latencySamples().isEmpty())
      Thread.sleep(5)
      append(456)
      assertEquals(listOf(456L), TurnReviewLogger(context).latencySamples().map { it.totalMillis })
      assertEquals(2, file.readLines().size)
    } finally { directory.deleteRecursively() }
  }
}
