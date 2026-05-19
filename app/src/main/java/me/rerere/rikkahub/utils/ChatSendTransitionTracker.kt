package me.rerere.rikkahub.utils

object ChatSendTransitionTracker {
    @Volatile
    private var activeConversationId: String? = null

    @Volatile
    private var activePreSendLatestMessageId: String? = null

    @Volatile
    private var activeSentUserMessageId: String? = null

    fun start(conversationId: String, preSendLatestMessageId: String?) {
        activeConversationId = conversationId
        activePreSendLatestMessageId = preSendLatestMessageId
        activeSentUserMessageId = null
    }

    fun markSentUserMessage(conversationId: String, messageId: String) {
        if (activeConversationId != conversationId) return
        activeSentUserMessageId = messageId
    }

    fun isPreSendLatestMessage(conversationId: String, messageId: String?): Boolean =
        activeConversationId == conversationId &&
            messageId != null &&
            activePreSendLatestMessageId == messageId

    fun isSentUserMessage(conversationId: String, messageId: String?): Boolean =
        activeConversationId == conversationId &&
            messageId != null &&
            activeSentUserMessageId == messageId

    fun sentUserMessageId(conversationId: String): String? =
        activeSentUserMessageId.takeIf { activeConversationId == conversationId }

    fun clear(conversationId: String) {
        if (activeConversationId != conversationId) return
        activeConversationId = null
        activePreSendLatestMessageId = null
        activeSentUserMessageId = null
    }
}
