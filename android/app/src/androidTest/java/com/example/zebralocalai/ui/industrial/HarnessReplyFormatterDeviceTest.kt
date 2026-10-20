package com.example.zebralocalai.ui.industrial

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessReplyFormatterDeviceTest {
  private fun card(id: String) = JSONObject()
    .put("id", id).put("title", "Trusted card $id").put("guidance", "Bundled guidance $id")

  @Test fun taskReplyShowsCountFirstTwoAndSnapshotWithoutInventingPriority() {
    val tasks = JSONArray()
    listOf("First recorded task", "Second recorded task", "Third recorded task").forEach {
      tasks.put(JSONObject().put("description", it).put("status", "Assigned"))
    }
    val outcome = JSONObject().put("message", "Current assigned tasks from the local snapshot.")
      .put("tasks", tasks).put("snapshot_time", "2026-10-12T08:00:00Z")
    val reply = HarnessReplyFormatter.format(outcome)
    assertTrue(reply.contains("3 assigned tasks"))
    assertTrue(reply.contains("First recorded task"))
    assertTrue(reply.contains("Second recorded task"))
    assertFalse(reply.contains("Third recorded task"))
    assertTrue(reply.contains("2026-10-12T08:00:00Z"))
    assertTrue(reply.contains("No live synchronization"))
    assertTrue(reply.contains("Open Task View"))
    outcome.put("tasks", JSONArray())
    assertTrue(HarnessReplyFormatter.format(outcome).contains("0 assigned tasks"))
    val failure = HarnessReplyFormatter.format(JSONObject().put("message", "Task snapshot could not be opened."))
    assertFalse(failure.contains("0 assigned tasks"))
  }

  @Test
  fun urgentGuidancePrecedesClarificationAndFooterAppearsOnce() {
    val gas = card("SOP-10")
    val outcome = JSONObject().put("message", "Where did you notice this?")
      .put("safety", JSONArray().put(gas))
      .put("procedures", JSONObject().put("cards", JSONArray().put(gas)))

    val reply = HarnessReplyFormatter.format(outcome)

    assertTrue(reply.indexOf("Bundled guidance SOP-10") < reply.indexOf("Where did you notice this?"))
    assertEquals(1, reply.split("Bundled guidance SOP-10").size - 1)
    assertEquals(1, reply.split(HarnessReplyFormatter.PROCEDURE_FOOTER).size - 1)
    assertTrue(reply.endsWith(HarnessReplyFormatter.PROCEDURE_FOOTER))
  }

  @Test
  fun CombinedReportsAndNextRequestHaveOneFinalFooter() {
    val concern = JSONObject().put("message", "Review this concern.")
      .put("procedures", JSONObject().put("cards", JSONArray().put(card("SOP-06"))))
    val replenishment = JSONObject().put("message", "Review this request.")
      .put("procedures", JSONObject().put("cards", JSONArray().put(card("SOP-11"))))
    concern.put("next_request", replenishment)
    val reply = HarnessReplyFormatter.format(JSONObject().put("results", JSONArray().put(concern)))

    assertTrue(reply.contains("Bundled guidance SOP-06"))
    assertTrue(reply.contains("Bundled guidance SOP-11"))
    assertEquals(1, reply.split(HarnessReplyFormatter.PROCEDURE_FOOTER).size - 1)
    assertTrue(reply.endsWith(HarnessReplyFormatter.PROCEDURE_FOOTER))
  }

  @Test
  fun StockAndUnmatchedProcedureResponsesDoNotGainSafetyFooter() {
    val outcome = JSONObject().put("message", "I couldn't find that product in the catalog.")
      .put("procedures", JSONObject().put("cards", JSONArray()))
    val reply = HarnessReplyFormatter.format(outcome)

    assertEquals("I couldn't find that product in the catalog.", reply)
    assertFalse(reply.contains(HarnessReplyFormatter.PROCEDURE_FOOTER))
  }
}
