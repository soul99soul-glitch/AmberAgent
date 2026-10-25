package app.amber.feature.ui.pages.chat

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.amber.feature.runtime.ConversationActivityCenter
import kotlin.uuid.Uuid

/**
 * Run [consume] only while the host is in the foreground. Leaving
 * [Lifecycle.State.RESUMED] cancels the collector so backgrounded events stay
 * unread; returning starts a new first-frame consume of what is now visible.
 */
internal suspend fun consumeConversationActivityWhileResumed(
    lifecycle: Lifecycle,
    conversationId: String,
    consume: suspend (String) -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        consume(conversationId)
    }
}

internal suspend fun ConversationActivityCenter.consumeWhileResumed(
    lifecycle: Lifecycle,
    conversationId: String,
) {
    consumeConversationActivityWhileResumed(lifecycle, conversationId) { id ->
        consumeWhileOpen(id)
    }
}

/**
 * Click-to-open is a real navigation only when [noticeConversationId] parses
 * as a Uuid other than the chat already on screen. Parse failure yields null
 * so the caller must not [ConversationActivityCenter.markSeen].
 */
internal fun activityNoticeNavigationTarget(
    noticeConversationId: String,
    currentConversationId: Uuid,
): Uuid? {
    val target = runCatching { Uuid.parse(noticeConversationId) }.getOrNull() ?: return null
    if (target == currentConversationId) return null
    return target
}
