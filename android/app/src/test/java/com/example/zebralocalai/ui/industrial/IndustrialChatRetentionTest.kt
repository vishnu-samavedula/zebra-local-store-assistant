package com.example.zebralocalai.ui.industrial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndustrialChatRetentionTest {
  @Test fun removesChatsAtTheSevenDayBoundary() {
    val now = 10L * IndustrialChatRetention.RETENTION_MILLIS
    val cutoff = now - IndustrialChatRetention.RETENTION_MILLIS
    val chats = listOf(IndustrialChat("expired", "Old", cutoff), IndustrialChat("older", "Older", cutoff - 1), IndustrialChat("fresh", "Fresh", cutoff + 1))
    assertEquals(listOf("fresh"), IndustrialChatRetention.retain(chats, now).map { it.id })
  }

  @Test fun retainsOnlyTheFiveNewestChatsRegardlessOfInputOrder() {
    val chats = (0..7).map { IndustrialChat(it.toString(), "Chat $it", 100L + it) }
    assertEquals(listOf("7", "6", "5", "4", "3"), IndustrialChatRetention.retain(chats.shuffled(), 200L).map { it.id })
  }

  @Test fun allExpiredChatsLeaveAnEmptyHistory() {
    assertTrue(IndustrialChatRetention.retain(listOf(IndustrialChat("old", "Old", 1)), IndustrialChatRetention.RETENTION_MILLIS + 1).isEmpty())
  }
}
