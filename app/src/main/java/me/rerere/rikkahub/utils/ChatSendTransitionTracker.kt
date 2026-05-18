package me.rerere.rikkahub.utils

import android.os.SystemClock

object ChatSendTransitionTracker {
    private const val WindowMs = 2_000L

    @Volatile
    private var activeConversationId: String? = null

    @Volatile
    private var activePreSendLatestMessageId: String? = null

    @Volatile
    private var activeSentUserMessageId: String? = null

    @Volatile
    private var startedAtMs = 0L

    fun start(conversationId: String, preSendLatestMessageId: String?) {
        activeConversationId = conversationId
        activePreSendLatestMessageId = preSendLatestMessageId
        activeSentUserMessageId = null
        startedAtMs = SystemClock.elapsedRealtime()
    }

    fun markSentUserMessage(conversationId: String, messageId: String) {
        if (!isActive(conversationId)) return
        activeSentUserMessageId = messageId
    }

    fun isPreSendLatestMessage(conversationId: String, messageId: String?): Boolean =
        isActive(conversationId) &&
            messageId != null &&
            activePreSendLatestMessageId == messageId

    fun isSentUserMessage(conversationId: String, messageId: String?): Boolean =
        isActive(conversationId) &&
            messageId != null &&
            activeSentUserMessageId == messageId

    private fun isActive(conversationId: String): Boolean {
        val startedAt = startedAtMs
        if (startedAt == 0L || activeConversationId != conversationId) return false
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        return elapsed in 0..WindowMs
    }
}
