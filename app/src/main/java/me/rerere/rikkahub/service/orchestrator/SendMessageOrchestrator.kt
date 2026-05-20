package me.rerere.rikkahub.service.orchestrator

import com.google.firebase.analytics.FirebaseAnalytics
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.PendingUserMessageMode
import kotlin.uuid.Uuid

/**
 * Orchestrates sending a user message to a conversation.
 *
 * Owns the pre-flight policy: empty input → no-op, otherwise log analytics and
 * hand off to ChatService for the actual session/queue/job work.
 *
 * Introduced in M1.2. ChatService.sendMessage continues to own session + queue
 * + generation loop state (M1.3 will split those internals out).
 */
class SendMessageOrchestrator(
    private val chatService: ChatService,
    private val analytics: FirebaseAnalytics,
) {
    fun send(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        queueMode: PendingUserMessageMode = PendingUserMessageMode.FOLLOWUP,
    ): Boolean {
        if (content.isEmptyInputMessage()) return false
        analytics.logEvent("ai_send_message", null)
        return chatService.sendMessage(conversationId, content, answer, queueMode)
    }
}
