package com.example.zebralocalai.ui.industrial

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModeFeedsDeviceTest {
  @Test fun modesKeepSeparateMessagesAndClearOnlySelectedFeed() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val prefs = app.getSharedPreferences("liquid-industrial-chats", Context.MODE_PRIVATE)
    val previousFeed = prefs.getString("activity_feed", null)
    val previousMode = prefs.getString("worker_mode", null)
    var store = ViewModelStore()
    lateinit var model: IndustrialViewModel
    fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    fun ready() {
      val end = System.currentTimeMillis() + 90000
      while (model.state.value.busy && System.currentTimeMillis() < end) Thread.sleep(30)
      assertFalse("Processing did not settle", model.state.value.busy)
    }
    fun load() {
      main { model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[IndustrialViewModel::class.java] }
      ready()
    }
    try {
      val seeded = JSONArray()
      for ((mode, message) in listOf("general" to "Where are Pace Runners?", "sop" to "A colleague is coughing.")) {
        seeded.put(JSONObject().put("id", "worker-feed-$mode").put("title", mode).put("created_at", System.currentTimeMillis())
          .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", message))))
      }
      prefs.edit().putString("worker_mode", "general").putString("activity_feed", seeded.toString()).commit()
      load()
      assertEquals(listOf("Where are Pace Runners?"), model.state.value.messages.map { it.text })
      main { model.setWorkerMode("sop") }; ready()
      assertEquals(listOf("A colleague is coughing."), model.state.value.messages.map { it.text })
      assertNull(model.state.value.confirmable)
      assertNull(model.state.value.reportOfferId)
      main { model.setWorkerMode("general") }; ready()
      assertEquals(listOf("Where are Pace Runners?"), model.state.value.messages.map { it.text })
      main { model.clearChat() }; ready()
      assertTrue(model.state.value.messages.isEmpty())
      main { model.setWorkerMode("sop") }; ready()
      assertEquals(listOf("A colleague is coughing."), model.state.value.messages.map { it.text })
      // Recreate the view model to verify both selection and feed persistence.
      main { store.clear() }
      store = ViewModelStore()
      load()
      assertEquals("sop", model.state.value.workerMode)
      assertEquals(listOf("A colleague is coughing."), model.state.value.messages.map { it.text })
      main { model.setWorkerMode("general") }; ready()
      assertTrue(model.state.value.messages.isEmpty())
    } finally {
      main { store.clear() }
      prefs.edit().apply {
        if (previousFeed == null) remove("activity_feed") else putString("activity_feed", previousFeed)
        if (previousMode == null) remove("worker_mode") else putString("worker_mode", previousMode)
      }.commit()
    }
  }
}
