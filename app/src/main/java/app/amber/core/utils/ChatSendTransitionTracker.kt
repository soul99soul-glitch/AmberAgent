package app.amber.core.utils

import android.os.SystemClock

object ChatSendTransitionTracker {
    /**
     * The send-entrance animation must play at most once per send tap, and
     * only when the sent bubble lands promptly — a STEER message queued behind
     * a running generation surfaces seconds later, where a spring entrance
     * would read as a glitch rather than "my text flew up from the input".
     */
    private const val SEND_ENTRANCE_WINDOW_MS = 1_500L

    @Volatile
    private var activeConversationId: String? = null

    @Volatile
    private var activePreSendLatestMessageId: String? = null

    @Volatile
    private var activeSentUserMessageId: String? = null

    @Volatile
    private var entranceArmedAtMs: Long = 0L

    @Volatile
    private var entranceConsumed: Boolean = true

    fun start(conversationId: String, preSendLatestMessageId: String?) {
        activeConversationId = conversationId
        activePreSendLatestMessageId = preSendLatestMessageId
        activeSentUserMessageId = null
        entranceArmedAtMs = SystemClock.elapsedRealtime()
        entranceConsumed = false
    }

    /**
     * One-shot claim of the send-entrance animation for the bubble that just
     * landed. First caller within the window wins; recompositions and
     * scroll-backs can never replay it.
     */
    fun consumeSendEntrance(conversationId: String): Boolean {
        if (activeConversationId != conversationId) return false
        if (entranceConsumed) return false
        entranceConsumed = true
        return SystemClock.elapsedRealtime() - entranceArmedAtMs <= SEND_ENTRANCE_WINDOW_MS
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
