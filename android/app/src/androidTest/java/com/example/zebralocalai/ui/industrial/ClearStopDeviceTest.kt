package com.example.zebralocalai.ui.industrial

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClearStopDeviceTest {
  @Test fun stopPreservesFeedAndClearRemovesPersistedContext() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val prefs = app.getSharedPreferences("liquid-industrial-chats", Context.MODE_PRIVATE)
    val previousFeed = prefs.getString("activity_feed", null)
    val previousMode = prefs.getString("worker_mode", null)
    val store = ViewModelStore()
    lateinit var model: IndustrialViewModel
    fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    fun ready() {
      val end = System.currentTimeMillis() + 90000
      while (model.state.value.busy && System.currentTimeMillis() < end) Thread.sleep(50)
      assertFalse("Processing did not settle", model.state.value.busy)
    }
    try {
      prefs.edit().putString("worker_mode", "general").commit()
      main { model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[IndustrialViewModel::class.java] }
      ready()
      main { model.submitText("How much stock is available for Cloudweight Hoodie in Ink size M?") }
      Thread.sleep(100)
      assertTrue("Request should expose Stop", model.state.value.canStop)
      main { model.stopProcessing() }
      ready()
      assertFalse(model.state.value.canStop)
      assertEquals("Request stopped.", model.state.value.messages.last().text)
      val count = model.state.value.messages.size
      Thread.sleep(1000)
      assertEquals("Cancelled response must not appear later", count, model.state.value.messages.size)
      main { model.clearChat() }
      ready()
      assertTrue(model.state.value.messages.isEmpty())
      assertNull(model.state.value.confirmable)
      assertNull(model.state.value.reportOfferId)
      assertTrue(model.state.value.readChoices.isEmpty())
      assertFalse(model.state.value.formRequired)
      val savedFeeds = JSONArray(prefs.getString("activity_feed", "[]"))
      assertFalse((0 until savedFeeds.length()).any { savedFeeds.getJSONObject(it).getString("id") == "worker-feed-general" })
      assertEquals("general", model.state.value.workerMode)
    } finally {
      main { store.clear() }
      prefs.edit().apply {
        if (previousFeed == null) remove("activity_feed") else putString("activity_feed", previousFeed)
        if (previousMode == null) remove("worker_mode") else putString("worker_mode", previousMode)
      }.commit()
    }
  }
}
