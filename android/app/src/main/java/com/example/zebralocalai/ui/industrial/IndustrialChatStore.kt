package com.example.zebralocalai.ui.industrial

data class ConfirmationReference(val requestKey: String, val revision: Int)

data class IndustrialMessage(val role: String, val text: String, val tools: List<String> = emptyList(),
  val confirmation: ConfirmationReference? = null, val processingMillis: Long? = null)

data class IndustrialChat(
  val id: String,
  val title: String,
  val createdAt: Long,
  val messages: List<IndustrialMessage> = emptyList(),
)

object IndustrialChatRetention {
  const val MAX_CHATS = 5
  const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

  fun retain(chats: List<IndustrialChat>, now: Long): List<IndustrialChat> =
    chats.filter { it.createdAt > now - RETENTION_MILLIS }
      .sortedByDescending(IndustrialChat::createdAt)
      .take(MAX_CHATS)
}
