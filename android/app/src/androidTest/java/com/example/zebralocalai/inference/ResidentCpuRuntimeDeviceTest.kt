package com.example.zebralocalai.inference

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zebralocalai.MainActivity
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResidentCpuRuntimeDeviceTest {
  @Test fun screenReopenReusesModelAndDeadChildRecovers() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val report = JSONObject()
    val prompt = "How many Cloudweight Hoodies in ink, medium are available?"
    var screen: ActivityScenario<MainActivity>? = ActivityScenario.launch(MainActivity::class.java)
    try {
      val runtime = ResidentCpuAudioRuntime.get(context)
      var started = SystemClock.elapsedRealtime()
      ResidentCpuAudioRuntime.warmup(context)
      report.put("initial_wait_ms", SystemClock.elapsedRealtime() - started)
      val firstServer = requireNotNull(runtime.serverInstance())
      val initial = runtime.replyText(prompt)
      report.put("initial_first_text_ms", initial.ttfsMillis).put("initial_request_ms", initial.elapsedMillis)
      screen!!.close()
      screen = null
      assertTrue("Finishing the screen killed the retained model", firstServer.isAlive)

      started = SystemClock.elapsedRealtime()
      screen = ActivityScenario.launch(MainActivity::class.java)
      val reopened = ResidentCpuAudioRuntime.get(context)
      ResidentCpuAudioRuntime.warmup(context)
      report.put("reopen_launch_and_ready_ms", SystemClock.elapsedRealtime() - started)
      assertSame("Screen created a second runtime", runtime, reopened)
      assertSame("Screen reloaded the native model", firstServer, reopened.serverInstance())
      val repeated = reopened.replyText(prompt)
      assertEquals("Screen recreation changed an independent request", initial.text, repeated.text)
      report.put("same_native_process_on_reopen", true)
        .put("reopen_first_text_ms", repeated.ttfsMillis).put("reopen_request_ms", repeated.elapsedMillis)

      firstServer.destroyForcibly().waitFor()
      started = SystemClock.elapsedRealtime()
      ResidentCpuAudioRuntime.warmup(context)
      report.put("child_restart_wait_ms", SystemClock.elapsedRealtime() - started)
      assertNotSame("Dead child was reused", firstServer, runtime.serverInstance())
      assertTrue(requireNotNull(runtime.serverInstance()).isAlive)
      val recovered = runtime.replyText(prompt)
      assertEquals("Child recovery changed the request", initial.text, recovered.text)
      report.put("dead_child_recovery_passed", true).put("output", recovered.text)
      File(context.filesDir, "resident-runtime-device-test.json").writeText(report.toString(2))
    } finally {
      screen?.close()
      // Keep the app-owned resident model alive; process death owns its lifetime.
    }
  }
}
