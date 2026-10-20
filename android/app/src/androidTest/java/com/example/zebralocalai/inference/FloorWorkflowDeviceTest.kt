package com.example.zebralocalai.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zebralocalai.agent.DeviceWarehouseHarness
import com.example.zebralocalai.ui.industrial.HarnessReplyFormatter
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloorWorkflowDeviceTest {
  @Test fun suppliedCallsVerifyModesGuidanceAndWrites() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = File(context.filesDir, "floor-workflow-baseline.db")
    database.delete()
    val harness = DeviceWarehouseHarness(context, database.name, oneShot = true)
    val evidence = JSONArray()
    fun record(name: String, result: JSONObject) { evidence.put(JSONObject().put("case", name).put("result", result)) }
    try {
      harness.setMode("feed", "sop")
      val exposureText = "I think I have something in my eyes while stocking up the fragrances and perfumes"
      val exposure = requireNotNull(harness.control("feed", exposureText, "audio", "eye-exposure"))
      assertEquals("guidance", exposure.getString("kind"))
      assertTrue(HarnessReplyFormatter.format(exposure).contains("Perfume on skin or in eyes"))
      val exposureCards = exposure.getJSONObject("procedures").getJSONArray("cards")
      assertTrue((0 until exposureCards.length()).any { exposureCards.getJSONObject(it).getString("id") == "CARE-02" })
      val urgent = exposure.getJSONArray("safety")
      assertTrue((0 until urgent.length()).any { urgent.getJSONObject(it).getString("id") == "CARE-02" })
      assertNull(harness.confirmationPreview("feed"))
      assertEquals(0, harness.tickets().length())
      record("eye_exposure_reversed_plural_wording", exposure)
      harness.restart("feed")
      val ventilation = requireNotNull(harness.control("feed", "The ventilation is not working near receiving.", "text", "ventilation-fault"))
      assertEquals("guidance", ventilation.getString("kind"))
      val ventilationCards = ventilation.getJSONObject("procedures").getJSONArray("cards")
      assertTrue((0 until ventilationCards.length()).any { ventilationCards.getJSONObject(it).getString("id") == "SOP-07" })
      assertNull(harness.confirmationPreview("feed"))
      record("ventilation_not_working_is_a_fault", ventilation)
      harness.restart("feed")
      val start = System.nanoTime()
      val guidance = requireNotNull(harness.control("feed", "Hey I see my colleagues coughing", "text", "guide"))
      assertEquals("guidance", guidance.getString("kind"))
      assertTrue(HarnessReplyFormatter.format(guidance).contains("Worker coughing"))
      assertNull(harness.confirmationPreview("feed"))
      guidance.put("measured_harness_ms", (System.nanoTime() - start) / 1000000)
      record("sop_without_generation", guidance)
      val offer = guidance.getJSONObject("report_offer").getString("id")
      val offered = harness.chooseReport("feed", offer, true)
      assertEquals("preview", offered.getString("kind"))
      assertEquals("Hey I see my colleagues coughing", offered.getJSONObject("preview").getJSONObject("arguments").getString("report_text"))
      assertEquals(0, harness.tickets().length())
      assertFalse(harness.chooseReport("feed", offer, true).getBoolean("saved"))
      harness.restart("feed")
      val noOffer = requireNotNull(harness.control("feed", "My colleagues are coughing", "text", "no-guide"))
      assertFalse(harness.chooseReport("feed", noOffer.getJSONObject("report_offer").getString("id"), false).getBoolean("saved"))
      assertNull(harness.confirmationPreview("feed"))
      harness.control("feed", "My colleagues are coughing", "text", "spoken-guide")
      val spokenYes = requireNotNull(harness.control("feed", "Yes", "audio", "spoken-yes"))
      assertEquals("preview", spokenYes.getString("kind"))
      assertFalse(spokenYes.getBoolean("saved"))
      harness.restart("feed")
      val report = harness.handle("feed", "I want to report colleagues coughing near receiving.",
        "<|tool_call_start|>[create_report()]<|tool_call_end|>", "text", "stop", "report")
      assertEquals("preview", report.getString("kind")); record("report_preview", report)
      val preview = requireNotNull(harness.confirmationPreview("feed"))
      assertEquals(0, harness.tickets().length())
      val saved = harness.confirmPreview("feed", preview.getString("request_key"), preview.getInt("revision"), "confirm")
      assertTrue(saved.getBoolean("saved")); record("confirmed_report", saved)
      assertFalse(harness.confirmPreview("feed", preview.getString("request_key"), preview.getInt("revision"), "duplicate").getBoolean("saved"))
      harness.setMode("feed", "general")
      val recover = harness.handle("feed", "Can we add 15 more clip card case",
        "<|tool_call_start|>[request_replenishment(product_name=\"Clip Card Case\", variant={\"color\":\"Tan\"}, quantity=15)]<|tool_call_end|>", "audio", "stop", "recover-variant")
      assertEquals("clarification", recover.getString("kind"))
      assertEquals("variant.color", recover.getJSONArray("missing").getString(0))
      assertEquals(15, recover.getJSONObject("known_arguments").getInt("quantity"))
      assertFalse(recover.getJSONObject("known_arguments").has("variant"))
      assertNull(harness.confirmationPreview("feed"))
      record("replenishment_recovered_invented_color", recover)
      val clipColor = context.assets.open("warehouse_catalog.jsonl").bufferedReader().useLines { lines ->
        lines.map(::JSONObject).first { it.getString("name") == "Clip Card Case" }.getString("color")
      }
      val clarifiedCall = "<|tool_call_start|>[request_replenishment(product_name=\"Clip Card Case\", variant={\"color\":${JSONObject.quote(clipColor)}}, quantity=15)]<|tool_call_end|>"
      val clarified = harness.handle("feed", clipColor, clarifiedCall, "audio", "stop", "recover-color-answer")
      assertEquals("preview", clarified.getString("kind"))
      assertEquals(15, clarified.getJSONObject("preview").getJSONObject("arguments").getInt("quantity"))
      assertEquals(1, harness.tickets().length()) // Only the earlier explicitly confirmed report.
      harness.restart("feed")
      val locations = harness.handle("feed", "Where is Cloudweight Hoodie?",
        "<|tool_call_start|>[inventory_lookup(operation=\"find_location\", product_name=\"Cloudweight Hoodie\")]<|tool_call_end|>", "text", "stop", "locations")
      assertEquals("result", locations.getString("kind")); record("locations_without_followup", locations)
      assertEquals("idle", harness.modelContext("feed").getString("phase"))
      assertEquals(0, harness.modelContext("feed").getJSONArray("model_history").length())
      val pace = harness.handle("feed", "Where is the Pace Runner stock located at?",
        "<|tool_call_start|>[inventory_lookup(operation=\"find_location\", product_name=\"Pace Runner\")]<|tool_call_end|>", "text", "stop", "pace-colors")
      assertEquals(14, pace.getInt("matched_variants"))
      assertTrue(pace.getBoolean("has_more"))
      val paceItems = pace.getJSONArray("items")
      assertEquals(3, paceItems.length())
      assertEquals(setOf("Graphite", "White / Blue"), (0 until paceItems.length()).map { paceItems.getJSONObject(it).getString("color") }.toSet())
      assertTrue(HarnessReplyFormatter.format(pace).contains("Showing 3; open Product catalog for all."))
      record("pace_runner_all_colors", pace)
      val stock = harness.handle("feed", "How many Pace Runner do we have?",
        "<|tool_call_start|>[inventory_lookup(operation=\"count_stock\", product_name=\"Pace Runner\")]<|tool_call_end|>", "text", "stop", "stock-all")
      assertEquals("result", stock.getString("kind"))
      assertFalse(stock.getBoolean("saved"))
      record("product_only_stock_count", stock)
      val overLimit = harness.handle("feed", "Count Pace Runner, Clip Card Case and Daypack 18L.",
        "<|tool_call_start|>[inventory_lookup(operation=\"count_stock\", product_name=\"Pace Runner\"), inventory_lookup(operation=\"count_stock\", product_name=\"Clip Card Case\"), inventory_lookup(operation=\"count_stock\", product_name=\"Daypack 18L\")]<|tool_call_end|>", "text", "stop", "three-products")
      assertEquals("failed", overLimit.getString("kind"))
      record("three_product_batch_rejected", overLimit)
      val scope = harness.handle("feed", "Tell me a joke.", "I can help with warehouse tasks.", "text", "stop", "scope-reply")
      assertFalse(scope.getBoolean("saved"))
      assertNotEquals("failed", scope.getString("kind"))
      record("approved_scope_reply", scope)
      val partial = "<|tool_call_start|>[request_replenishment(product_name=\"Cloudweight Hoodie\", quantity=10)]<|tool_call_end|>"
      for ((i, text) in listOf("Request ten Cloudweight Hoodies.", "I am not sure.", "I still do not know.").withIndex()) {
        val result = harness.handle("feed", text, partial, "text", "stop", "partial-$i")
        assertEquals(if (i < 2) "clarification" else "paused", result.getString("kind"))
        record("bounded_followup-$i", result)
      }
      val product = context.assets.open("warehouse_catalog.jsonl").bufferedReader().useLines { lines ->
        lines.map(::JSONObject).first { it.getString("name") == "Cloudweight Hoodie" && it.getString("color") == "Ink" && it.getString("size") == "M" }
      }
      val form = harness.completeForm("feed", product.getString("product_id"), 10, "form")
      assertEquals("preview", form.getString("kind")); record("form_requires_confirmation", form)
      val ref = requireNotNull(harness.confirmationPreview("feed"))
      harness.setMode("feed", "sop")
      assertFalse(harness.confirmPreview("feed", ref.getString("request_key"), ref.getInt("revision"), "stale").getBoolean("saved"))
      assertEquals(1, harness.tickets().length())
      File(context.filesDir, "floor-workflow-harness-baseline.json").writeText(JSONObject()
        .put("generation_model_tested", false).put("supplied_call_checks", evidence).put("passed", true).toString(2))
    } finally { harness.shutdown(); database.delete() }
  }
}
