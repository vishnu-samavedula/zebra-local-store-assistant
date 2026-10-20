package com.example.zebralocalai.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zebralocalai.audio.OfflineAudioTranscript
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises only platform transcription: no microphone, generation or tickets. */
@RunWith(AndroidJUnit4::class)
class OfflineRecognitionInvestigationTest {
  @Test fun compareLeadingSilence() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val root = File(context.filesDir, "models/warehouse-test/warehouse-iteration-20261013g")
    val task = File(root, "T01-1-0.wav").readBytes()
    val rows = JSONArray()
    for (paced in listOf(false, true)) repeat(3) {
      val pcm = ByteArray(64000) + task.copyOfRange(44, task.size) + ByteArray(16000)
      val header = task.copyOfRange(0, 44)
      for (b in 0..3) { header[4 + b] = ((pcm.size + 36) ushr (8 * b)).toByte(); header[40 + b] = (pcm.size ushr (8 * b)).toByte() }
      val events = JSONArray()
      val recognizer = InvestigationTranscript(context, paced, true, { name, text -> events.put(JSONObject().put("callback", name).put("text", text)) }, 5)
      val file = File(context.cacheDir, "recognition-investigation.wav")
      file.writeBytes(header + pcm)
      val row = JSONObject().put("paced", paced).put("events", events)
      val started = System.nanoTime()
      try { row.put("transcript", recognizer.transcribe(file)) }
      catch (error: Exception) { row.put("error", error.message) }
      finally { file.delete() }
      row.put("elapsed_ms", (System.nanoTime() - started) / 1000000)
      rows.put(row)
      File(context.filesDir, "offline-recognition-leading-silence.json").writeText(rows.toString(2))
      delay(500)
    }
  }
  @Test fun compareFeedAndSessionModes() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val root = File(context.filesDir, "models/warehouse-test/warehouse-iteration-20261013g")
    val stock = File(root, "I01-1-0.wav").readBytes()
    val task = File(root, "T01-1-0.wav").readBytes()
    val pcm = stock.copyOfRange(44, stock.size) + ByteArray(32000) + task.copyOfRange(44, task.size)
    val header = stock.copyOfRange(0, 44)
    for (b in 0..3) { header[4 + b] = ((pcm.size + 36) ushr (8 * b)).toByte(); header[40 + b] = (pcm.size ushr (8 * b)).toByte() }
    val rows = JSONArray()
    for (paced in listOf(false, true)) for (segmented in listOf(true, false)) repeat(2) {
      val events = JSONArray()
      val recognizer = InvestigationTranscript(context, paced, segmented, { name, text -> events.put(JSONObject().put("callback", name).put("text", text)) })
      val file = File(context.cacheDir, "recognition-investigation.wav")
      file.writeBytes(header + pcm)
      val row = JSONObject().put("paced", paced).put("segmented", segmented).put("events", events)
      try { row.put("transcript", recognizer.transcribe(file)) }
      catch (error: Exception) { row.put("error", error.message) }
      finally { file.delete() }
      rows.put(row)
      File(context.filesDir, "offline-recognition-feed-comparison.json").writeText(rows.toString(2))
      delay(500)
    }
  }
  @Test fun compareFasterPacing() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val root = File(context.filesDir, "models/warehouse-test/warehouse-iteration-20261013g")
    val stock = File(root, "I01-1-0.wav").readBytes()
    val task = File(root, "T01-1-0.wav").readBytes()
    val pcm = stock.copyOfRange(44, stock.size) + ByteArray(32000) + task.copyOfRange(44, task.size)
    val header = stock.copyOfRange(0, 44)
    for (b in 0..3) { header[4 + b] = ((pcm.size + 36) ushr (8 * b)).toByte(); header[40 + b] = (pcm.size ushr (8 * b)).toByte() }
    val rows = JSONArray()
    for (pace in listOf(5L, 10L)) repeat(2) {
      val events = JSONArray()
      val recognizer = InvestigationTranscript(context, true, true, { name, text -> events.put(JSONObject().put("callback", name).put("text", text)) }, pace)
      val file = File(context.cacheDir, "recognition-investigation.wav")
      file.writeBytes(header + pcm)
      val row = JSONObject().put("pace_delay_ms", pace).put("events", events)
      try { row.put("transcript", recognizer.transcribe(file)) }
      catch (error: Exception) { row.put("error", error.message) }
      finally { file.delete() }
      rows.put(row)
      File(context.filesDir, "offline-recognition-fast-paced-comparison.json").writeText(rows.toString(2))
      delay(500)
    }
  }
  @Test fun repeatKnownAudioAndPausedPhrases() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val root = File(context.filesDir, "models/warehouse-test/warehouse-iteration-20261013g")
    val stock = File(root, "I01-1-0.wav").readBytes()
    val task = File(root, "T01-1-0.wav").readBytes()
    val rows = JSONArray()
    val recognizer = OfflineAudioTranscript(context)
    for (index in 0 until 10) {
      val paired = index >= 6
      val pcm = if (paired) stock.copyOfRange(44, stock.size) + ByteArray(32000) + task.copyOfRange(44, task.size)
        else if (index % 2 == 0) stock.copyOfRange(44, stock.size) else task.copyOfRange(44, task.size)
      val header = stock.copyOfRange(0, 44)
      fun number(offset: Int, value: Int) { for (b in 0..3) header[offset + b] = (value ushr (8 * b)).toByte() }
      number(4, pcm.size + 36); number(40, pcm.size)
      val file = File(context.cacheDir, "recognition-investigation.wav")
      file.writeBytes(header + pcm)
      val started = System.nanoTime()
      val row = JSONObject().put("index", index).put("fixture", if (paired) "stock_pause_task" else if (index % 2 == 0) "stock" else "task")
        .put("duration_ms", pcm.size / 32)
      try { row.put("transcript", recognizer.transcribe(file)) }
      catch (error: Exception) { row.put("error", error.message) }
      finally { file.delete() }
      row.put("elapsed_ms", (System.nanoTime() - started) / 1000000)
      rows.put(row)
      File(context.filesDir, "offline-recognition-investigation.json").writeText(rows.toString(2))
      if (InstrumentationRegistry.getArguments().getString("requireComplete") == "true") {
        val text = row.optString("transcript").lowercase()
        assertTrue("Missing words: $row", if (paired) text.contains("hoodies") && text.contains("how many tasks are there for me")
          else if (index % 2 == 0) text.contains("how many") && text.contains("hoodies") && text.contains("available")
          else text.contains("how many tasks are there for me"))
      }
      delay(500)
    }
    assertTrue(rows.length() == 10)
  }
}
