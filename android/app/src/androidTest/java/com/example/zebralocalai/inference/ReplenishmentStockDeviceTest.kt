package com.example.zebralocalai.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zebralocalai.agent.DeviceWarehouseHarness
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplenishmentStockDeviceTest {
  @Test fun confirmationUpdatesLocalVariantOnceAndSurvivesReopen() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = File(context.filesDir, "replenishment-stock-device-test.db")
    database.delete()
    var harness = DeviceWarehouseHarness(context, database.name, oneShot = true)
    val report = JSONObject().put("isolated_test_database", true)
    fun stock(): Int {
      val products = harness.catalog()
      return (0 until products.length()).map(products::getJSONObject)
        .single { it.getString("product_id") == "iris-paper-50-ml" }
        .let { it.getInt("on_hand") - it.getInt("reserved") }
    }
    try {
      val before = stock()
      harness.setMode("stock-test", "general")
      val preview = harness.handle("stock-test", "Can we add 10 to the stock of Irish paper",
        "<|tool_call_start|>[request_replenishment(product_name=\"irish paper\", quantity=10)]<|tool_call_end|>",
        "text", "stop", "propose")
      assertEquals("preview", preview.getString("kind"))
      assertEquals(before, stock())
      val reference = requireNotNull(harness.confirmationPreview("stock-test"))
      val key = reference.getString("request_key")
      val revision = reference.getInt("revision")
      val stale = harness.confirmPreview("stock-test", key, revision + 1, "stale")
      assertFalse(stale.getBoolean("saved"))
      assertEquals(before, stock())
      val saved = harness.confirmPreview("stock-test", key, revision, "submit")
      assertTrue(saved.getBoolean("saved"))
      assertEquals(before + 10, stock())
      assertFalse(harness.confirmPreview("stock-test", key, revision, "duplicate").getBoolean("saved"))
      assertEquals(before + 10, stock())
      assertEquals(1, harness.tickets().length())
      harness.shutdown()
      harness = DeviceWarehouseHarness(context, database.name, oneShot = true)
      assertEquals(before + 10, stock())
      assertEquals(1, harness.tickets().length())
      report.put("before", before).put("after", stock()).put("saved", saved)
        .put("duplicate_prevented", true).put("survives_reopen", true).put("passed", true)
      File(context.filesDir, "replenishment-stock-device-test.json").writeText(report.toString(2))
    } finally {
      harness.shutdown()
    }
  }
}
