package com.example.zebralocalai.inference

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zebralocalai.agent.DeviceWarehouseHarness
import com.example.zebralocalai.ui.industrial.HarnessReplyFormatter
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarehouseHarnessDeviceTest {
  @Test fun standaloneCoughObservationShowsGuidanceWithoutTicketOrValidationError() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = File(context.filesDir, "warehouse-guidance-only-test.db")
    database.delete()
    val harness = DeviceWarehouseHarness(context, database.name)
    try {
      val result = harness.handle("observation", "Hey I see a lot of my colleagues coughing",
        "<|tool_call_start|>[inventory_lookup(operation=\"find_location\", product_name=\"car\")]<|tool_call_end|>",
        "audio", "stop", "observation-event")
      assertEquals("guidance", result.getString("kind"))
      val formatted = HarnessReplyFormatter.format(result)
      assertTrue(formatted.contains("Worker coughing"))
      assertTrue(formatted.contains("Operating Procedures"))
      assertFalse(formatted.contains("validate"))
      assertFalse(formatted.contains("ticket"))
      assertNull(harness.confirmationPreview("observation"))
      assertEquals(0, harness.tickets().length())
      val approval = requireNotNull(harness.control("observation", "Yes I confirm", "text", "orphan"))
      assertFalse(approval.getBoolean("saved"))
    } finally { harness.shutdown(); database.delete() }
  }
  @Test fun procedureCardsAndRevisionBoundButtonWorkWithoutValidModelProse() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = File(context.filesDir, "warehouse-button-card-test.db")
    database.delete()
    val harness = DeviceWarehouseHarness(context, database.name)
    val text = "I want to report a concern somebody's coughing too much"
    val call = "<|tool_call_start|>[report_general_concern()]<|tool_call_end|>"
    try {
      val invalid = harness.handle("invalid", text, "not a valid model call", "audio", "stop", "invalid-event")
      assertEquals("failed", invalid.getString("kind"))
      val cards = invalid.getJSONObject("procedures").getJSONArray("cards")
      assertTrue("Cough card missing despite recognized report", (0 until cards.length()).any { cards.getJSONObject(it).getString("id") == "CARE-05" })
      assertTrue(HarnessReplyFormatter.format(invalid).contains("Operating Procedures"))
      assertFalse(invalid.getBoolean("saved"))
      harness.handle("button", text, call, "text", "stop", "preview-event")
      val first = requireNotNull(harness.confirmationPreview("button"))
      harness.handle("button", "It is beside receiving.", call, "text", "stop", "correction-event")
      val stale = harness.confirmPreview("button", first.getString("request_key"), first.getInt("revision"), "stale-button")
      assertFalse(stale.getBoolean("saved"))
      assertEquals(0, harness.tickets().length())
      val current = requireNotNull(harness.confirmationPreview("button"))
      val saved = harness.confirmPreview("button", current.getString("request_key"), current.getInt("revision"), "current-button")
      assertTrue(saved.getBoolean("saved"))
      assertNull(harness.confirmationPreview("button"))
      assertFalse(harness.confirmPreview("button", current.getString("request_key"), current.getInt("revision"), "duplicate-button").getBoolean("saved"))
      assertEquals(1, harness.tickets().length())
    } finally { harness.shutdown(); database.delete() }
  }
  @Test fun naturalWorkerConfirmationAndVagueReportGuardsWork() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = File(context.filesDir, "warehouse-worker-phrase-test.db")
    database.delete()
    val harness = DeviceWarehouseHarness(context, database.name)
    val call = "<|tool_call_start|>[report_general_concern()]<|tool_call_end|>"
    try {
      val text = "I want to report a concern somebody's coughing too much"
      val preview = harness.handle("concern", text, call, "text", "stop", "concern-event")
      assertEquals("preview", preview.getString("kind"))
      val saved = requireNotNull(harness.control("concern", "Yes I confirm", "text", "natural-confirm"))
      assertTrue(saved.getBoolean("saved"))
      assertEquals(text, saved.getJSONObject("ticket").getJSONObject("arguments").getString("report_text"))
      val duplicate = requireNotNull(harness.control("concern", "Yes I confirm", "text", "duplicate-confirm"))
      assertFalse(duplicate.getBoolean("saved"))
      assertNull(harness.control("concern", "Yes but change the quantity to five", "text", "edit-turn"))
      val orphan = requireNotNull(harness.control("no-preview", "Yes I confirm", "text", "orphan-confirm"))
      assertEquals("no_active_preview", orphan.getString("reason"))
      assertFalse(orphan.getBoolean("saved"))
      for ((chat, worker, question) in listOf(
        Triple("fragment", "Too much", "What did you notice?"),
        Triple("damage", "I want to report damage on the sneakers", "Which product do you mean?")
      )) {
        val result = harness.handle(chat, worker, call, "text", "stop", "$chat-event")
        assertEquals("conversation", result.getString("kind"))
        assertEquals(question, result.getString("message"))
        assertFalse(result.getBoolean("saved"))
      }
      assertEquals(1, harness.tickets().length())
    } finally { harness.shutdown(); database.delete() }
  }
  @Test fun variantCommaKeepsRequestedQuantitySeparateFromShoeSize() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = File(context.filesDir, "warehouse-variant-comma-device-test.db")
    database.delete()
    val harness = DeviceWarehouseHarness(context, database.name)
    val proposal = "<|tool_call_start|>[request_replenishment(product_name=\"Court Low\", variant={\"color\":\"White\",\"size\":\"9\"}, quantity=20)]<|tool_call_end|>"
    fun handle(chat: String, text: String, output: String) =
      harness.handle(chat, text, output, "text", "stop", UUID.randomUUID().toString())
    try {
      val text = "Can you replenish Court Low in color White, size 9 by 20 units?"
      val preview = handle("correct", text, proposal)
      assertEquals("preview", preview.getString("kind"))
      assertEquals(20, preview.getJSONObject("preview").getJSONObject("arguments").getInt("quantity"))
      assertEquals(0, harness.tickets().length())
      assertEquals("failed", handle("wrong-size", text, proposal.replace("quantity=20", "quantity=9")).getString("kind"))
      assertEquals("failed", handle("existing", "We have 20 Court Low in color White, size 9, request 5 more.", proposal).getString("kind"))
      assertEquals("failed", handle("negated", "Do not replenish Court Low in color White, size 9 by 20 units.", proposal).getString("kind"))
      assertEquals(0, harness.tickets().length())
      val saved = requireNotNull(harness.control("correct", "Yes, submit it.", "text", UUID.randomUUID().toString()))
      assertTrue(saved.getBoolean("saved"))
      assertEquals(20, saved.getJSONObject("ticket").getJSONObject("arguments").getInt("quantity"))
      assertEquals(1, harness.tickets().length())
    } finally { harness.shutdown(); database.delete() }
  }

  @Test fun confirmedTicketsSurviveHarnessRestartAndCedarResolves() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = File(context.filesDir, "warehouse-ticket-view-device-test.db")
    database.delete()
    var harness = DeviceWarehouseHarness(context, database.name)
    try {
      assertEquals(0, harness.tickets().length())
      val scenarios = listOf(
        Triple("concern", "The scanner is flickering near receiving.", "report_general_concern(observation=\"The scanner is flickering\", location=\"near receiving\")"),
        Triple("damage", "Cloudweight Hoodie in ink, medium has torn sleeves near the clothing racks.", "report_product_damage(product_name=\"Cloudweight Hoodie\", variant={\"color\":\"Ink\",\"size\":\"M\"}, damage_description=\"Torn sleeves\", location=\"near the clothing racks\")"),
        Triple("replenishment", "Request ten Cloudweight Hoodies in ink, medium.", "request_replenishment(product_name=\"Cloudweight Hoodie\", variant={\"color\":\"Ink\",\"size\":\"M\"}, quantity=10)")
      )
      val references = mutableSetOf<String>()
      scenarios.forEach { (chat, text, call) ->
        val preview = harness.handle(chat, text, "<|tool_call_start|>[$call]<|tool_call_end|>", "text", "stop", UUID.randomUUID().toString())
        assertEquals("preview", preview.getString("kind"))
        assertEquals(references.size, harness.tickets().length())
        val saved = requireNotNull(harness.control(chat, "Yes, submit it.", "text", UUID.randomUUID().toString()))
        assertTrue(saved.getBoolean("saved"))
        references.add(saved.getJSONObject("ticket").getString("reference"))
        val duplicate = requireNotNull(harness.control(chat, "Yes, submit it.", "text", UUID.randomUUID().toString()))
        assertFalse(duplicate.getBoolean("saved"))
      }
      harness.shutdown()
      harness = DeviceWarehouseHarness(context, database.name)
      val records = harness.tickets()
      assertEquals(3, records.length())
      assertEquals(references, (0 until records.length()).map { records.getJSONObject(it).getString("reference") }.toSet())
      val tickets = (0 until records.length()).map { com.example.zebralocalai.ui.industrial.IndustrialTicket.parse(records.getJSONObject(it)) }
      assertEquals(listOf("General concern", "Product damage", "Replenishment request"), tickets.map { it.title })
      assertTrue(tickets[1].fields.contains("Affected quantity" to "Unknown"))
      assertTrue(tickets[2].fields.contains("Additional quantity" to "10"))
      val cedar = harness.handle("cedar", "Do we have stock for Cedar?", "<|tool_call_start|>[inventory_lookup(operation=\"count_stock\", product_name=\"Cedar\")]<|tool_call_end|>", "text", "stop", UUID.randomUUID().toString())
      val row = cedar.getJSONArray("items").getJSONObject(0)
      assertEquals("Cedar", row.getString("name"))
      assertEquals(31, row.getInt("available"))
      assertEquals("ember-oud-50-ml", row.getString("product_id"))
    } finally { harness.shutdown(); database.delete() }
  }

  @Test fun spokenArgumentsAndExactLocationAreGrounded() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = File(context.filesDir, "warehouse-spoken-grounding-device-test.db")
    database.delete()
    val harness = DeviceWarehouseHarness(context, database.name)
    fun handle(chat: String, text: String, output: String) =
      harness.handle(chat, text, output, "text", "stop", UUID.randomUUID().toString())
    try {
      val pace = "<|tool_call_start|>[request_replenishment(product_name=\"Pace Runner\", variant={\"color\":\"White / Blue\",\"size\":\"8\"}, quantity=10)]<|tool_call_end|>"
      val spoken = handle("spoken", "Request ten Pace Runner in white and blue, size eight.", pace)
      assertEquals("preview", spoken.getString("kind"))
      assertEquals("8", spoken.getJSONObject("preview").getJSONObject("arguments").getJSONObject("variant").getString("size"))
      assertFalse(spoken.getBoolean("saved"))
      val readiness = spoken.getJSONObject("procedures").getJSONArray("cards")
      assertTrue((0 until readiness.length()).any { readiness.getJSONObject(it).getString("id") == "SOP-11" })

      val hoodie = "<|tool_call_start|>[request_replenishment(product_name=\"Cloudweight Hoodie\", variant={\"color\":\"Ink\",\"size\":\"M\"}, quantity=5)]<|tool_call_end|>"
      val requested = handle("requested", "There are twenty Cloudweight Hoodies in ink, medium on hand. I'm requesting five more.", hoodie)
      assertEquals("preview", requested.getString("kind"))
      assertEquals(5, requested.getJSONObject("preview").getJSONObject("arguments").getInt("quantity"))
      assertEquals("failed", handle("wrong-count", "There are twenty Cloudweight Hoodies in ink, medium on hand. I'm requesting five more.", hoodie.replace("quantity=5", "quantity=20")).getString("kind"))
      assertEquals("failed", handle("negated", "I'm not requesting five Cloudweight Hoodies in ink, medium.", hoodie).getString("kind"))
      assertEquals("failed", handle("ambiguous-size", "Request ten Pace Runner in white and blue.", pace).getString("kind"))

      val partial = "<|tool_call_start|>[inventory_lookup(operation=\"find_location\", product_name=\"Cloudweight Hoodie\")]<|tool_call_end|>"
      val question = handle("location", "Where is Cloudweight Hoodie?", partial)
      assertEquals("clarification", question.getString("kind"))
      val located = handle("location", "Ink, medium.", "<|tool_call_start|>[inventory_lookup(operation=\"find_location\", product_name=\"Cloudweight Hoodie\", variant={\"color\":\"Ink\",\"size\":\"M\"})]<|tool_call_end|>")
      assertEquals("result", located.getString("kind"))
      assertEquals("A1", located.getJSONArray("items").getJSONObject(0).getString("section"))
      assertEquals(3, located.getJSONArray("items").getJSONObject(0).getInt("bin"))
      assertEquals(0, harness.modelContext("location").getJSONArray("model_history").length())
      val gas = handle("gas", "I smell gas near receiving.", "Investigate the gas pipe.")
      assertEquals("failed", gas.getString("kind"))
      val safety = gas.getJSONArray("safety")
      assertTrue((0 until safety.length()).any { safety.getJSONObject(it).getString("id") == "SOP-10" })
      assertFalse(HarnessReplyFormatter.format(gas).contains("Investigate the gas pipe."))
      Log.i("WarehouseHarnessSmoke", "Spoken size/color, requested versus existing quantity, negated/ambiguous arguments and exact location passed")
    } finally {
      harness.shutdown()
      database.delete()
    }
  }

  @Test fun localInventoryReportsControlsAndRealProcedureRetrievalWork() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = File(context.filesDir, "warehouse-harness-device-test.db")
    database.delete()
    val harness = DeviceWarehouseHarness(context, database.name)
    val chat = "device-review"
    fun handle(text: String, output: String) = harness.handle(chat, text, output, "text", "stop", UUID.randomUUID().toString())
    try {
      val counted = handle("How many Cloudweight Hoodies in ink, size medium?",
        "<|tool_call_start|>[inventory_lookup(operation=\"count_stock\", product_name=\"Cloudweight Hoodie\", variant={\"color\":\"Ink\",\"size\":\"M\"})]<|tool_call_end|>")
      assertEquals("result", counted.getString("kind"))
      assertEquals(4, counted.getJSONArray("totals").getJSONObject(0).getInt("available"))
      assertTrue(HarnessReplyFormatter.format(counted).contains("4 each"))
      assertEquals(0, harness.modelContext(chat).getJSONArray("model_history").length())
      assertEquals(0, harness.modelContext(chat).getJSONArray("full_request_turns").length())
      val question = handle("Help with this.", "Do you want a stock count or location?")
      assertEquals("conversation", question.getString("kind"))
      assertFalse(question.getBoolean("saved"))
      val chatState = harness.modelContext(chat)
      assertEquals(2, chatState.getJSONArray("model_history").length())
      assertFalse(chatState.getJSONArray("model_history").toString().contains("Cloudweight Hoodie"))
      val assigned = harness.handle("tasks-fixture", "What are my tasks?",
        "<|tool_call_start|>[task_lookup(operation=\"list_assigned_tasks\")]<|tool_call_end|>",
        "text", "stop", "tasks-event")
      val snapshot = org.json.JSONObject(context.assets.open("industrial_task_snapshot.json").bufferedReader().use { it.readText() })
      val expectedTasks = snapshot.getJSONArray("tasks")
      assertEquals(expectedTasks.length(), assigned.getJSONArray("tasks").length())
      assertEquals(snapshot.getString("updated_at"), assigned.getString("snapshot_time"))

      val candidateIds = org.json.JSONArray(harness.retriever().retrieve("My teammate has been coughing a lot", 3))
      assertTrue((0 until candidateIds.length()).any { candidateIds.getJSONObject(it).getString("id") == "CARE-05" })

      val call = "<|tool_call_start|>[request_replenishment(product_name=\"Cloudweight Hoodie\", variant={\"color\":\"Ink\",\"size\":\"M\"}, quantity=10)]<|tool_call_end|>"
      val preview = handle("Request ten Cloudweight Hoodies in ink, size medium.", call)
      assertEquals("preview", preview.getString("kind"))
      assertFalse(preview.getBoolean("saved"))
      val pendingContext = harness.modelContext(chat)
      assertEquals("awaiting_confirmation", pendingContext.getString("phase"))
      listOf("Where's Irish paper located?", "Where’s Irish paper located?").forEach { text ->
        val fresh = harness.modelContext(chat, text)
        assertEquals("idle", fresh.getString("phase"))
        assertEquals(0, fresh.getJSONArray("model_history").length())
        assertTrue(fresh.isNull("focused_call"))
        assertEquals(pendingContext.toString(), harness.modelContext(chat).toString())
      }
      val separateRead = harness.modelContext(chat, "Where is irish paper?")
      assertEquals("idle", separateRead.getString("phase"))
      assertEquals(0, separateRead.getJSONArray("model_history").length())
      assertEquals(0, separateRead.getJSONArray("full_request_turns").length())
      assertTrue(separateRead.isNull("focused_call"))
      assertEquals(pendingContext.toString(), harness.modelContext(chat).toString())
      val saved = requireNotNull(harness.control(chat, "Yes, submit it.", "text", "confirm-fixture"))
      assertEquals("saved", saved.getString("kind"))
      assertEquals("created_locally", saved.getJSONObject("ticket").getString("status"))
      assertEquals(10, saved.getJSONObject("ticket").getJSONObject("arguments").getInt("quantity"))
      assertEquals(0, harness.modelContext(chat).getJSONArray("model_history").length())
      val duplicate = requireNotNull(harness.control(chat, "Yes, submit it.", "text", "confirm-fixture"))
      assertEquals(saved.getJSONObject("ticket").getString("reference"), duplicate.getJSONObject("ticket").getString("reference"))

      val withdrawalChat = "withdrawal-fixture"
      val damage = harness.handle(withdrawalChat,
        "Three Cloudweight Hoodies in ink, medium, have torn sleeves near the clothing racks.",
        "<|tool_call_start|>[report_product_damage(product_name=\"Cloudweight Hoodie\", variant={\"color\":\"Ink\",\"size\":\"M\"}, damage_description=\"Torn sleeves\", location=\"near the clothing racks\", quantity=3)]<|tool_call_end|>",
        "text", "stop", "damage-preview")
      assertEquals("preview", damage.getString("kind"))
      val withdrawn = requireNotNull(harness.control(withdrawalChat,
        "I do not know the quantity after all.", "audio", "quantity-withdrawal"))
      assertEquals("preview", withdrawn.getString("kind"))
      assertFalse(withdrawn.getJSONObject("preview").getJSONObject("arguments").has("quantity"))
      assertTrue(withdrawn.getJSONObject("preview").getInt("revision") > damage.getJSONObject("preview").getInt("revision"))
      val withdrawalSaved = requireNotNull(harness.control(withdrawalChat, "Yes, submit it.", "text", "withdrawal-confirm"))
      assertEquals("saved", withdrawalSaved.getString("kind"))
      assertFalse(withdrawalSaved.getJSONObject("ticket").getJSONObject("arguments").has("quantity"))

      val exposure = harness.handle("exposure-fixture", "A perfume bottle is leaking and the liquid got on my arm.",
        "<|tool_call_start|>[report_general_concern(observation=\"A perfume bottle is leaking and the liquid got on my arm\")]<|tool_call_end|>",
        "text", "stop", "exposure-event")
      assertEquals("preview", exposure.getString("kind"))
      assertEquals("A perfume bottle is leaking and the liquid got on my arm.",
        exposure.getJSONObject("preview").getJSONObject("arguments").getString("report_text"))
      assertTrue(exposure.getBoolean("safety_guidance_before_report"))
      val ids = exposure.getJSONArray("safety")
      assertTrue((0 until ids.length()).any { ids.getJSONObject(it).getString("id") == "CARE-02" })
      assertEquals("matched", exposure.getJSONObject("procedures").getString("state"))
      assertFalse(exposure.getBoolean("saved"))
      val review = harness.reviewReferenceJourneys()
      Log.i("WarehouseHarnessSmoke", "referenceReview=$review")
      assertEquals(review.getInt("turns"), review.getInt("passed_turns"))
      Log.i("WarehouseHarnessSmoke", "SQLite count=4; text clarification; real ColBERT; confirmed save and retry; exposure routing passed")
    } finally {
      harness.shutdown()
      database.delete()
    }
  }
}
