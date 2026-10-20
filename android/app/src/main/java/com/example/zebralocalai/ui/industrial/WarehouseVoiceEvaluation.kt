package com.example.zebralocalai.ui.industrial

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Separate held-out voice session. Intended wording is never asserted to be a transcript. */
class WarehouseVoiceEvaluation(context: Context) {
  private val directory = File(context.filesDir, "warehouse-evaluation/voice-${System.currentTimeMillis()}")
  companion object {
    val prompts = listOf(
      "Check the stock of Cloudweight Hoodie in Ink, size medium.",
      "Where can I find Cloudweight Hoodie in Ink, size medium?",
      "How much stock is there for Cedar, fifty milliliters?",
      "What is in bin three in section A one?",
      "Request five Cloudweight Hoodies in Ink, size medium.",
      "We have twenty Cloudweight Hoodies in Ink, medium. Please request five more.",
      "Request ten Court Low in White, size nine.",
      "I need more Cloudweight Hoodies.",
      "The handheld screen flashes violet beside the folded divider.",
      "One Cloudweight Hoodie in Ink, medium has a torn hem beside the packing station.",
      "How many tasks are assigned to me?",
      "Handle that.",
    )
  }

  fun save(wav: File, index: Int): File {
    require(index in prompts.indices)
    require(wav.isFile && wav.length() > 44)
    directory.mkdirs()
    val id = UUID.randomUUID().toString()
    val destination = File(directory, "$id.wav")
    wav.copyTo(destination)
    val hash = MessageDigest.getInstance("SHA-256").digest(destination.readBytes())
      .joinToString("") { "%02x".format(it) }
    val record = JSONObject().put("id", id).put("split", "heldout_worker_voice")
      .put("voice_session", directory.name).put("prompt_index", index)
      .put("intended_text", prompts[index]).put("actual_transcript", JSONObject.NULL)
      .put("transcript_review_status", "pending_human_review").put("training_allowed", false)
      .put("audio_file", destination.name).put("audio_sha256", hash)
      .put("sample_rate", 16000).put("channels", 1).put("sample_bits", 16)
    File(directory, "$id.json").writeText(record.toString(2))
    wav.delete()
    return destination
  }
}
