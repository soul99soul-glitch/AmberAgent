package me.rerere.rikkahub.service.orchestrator

import com.google.firebase.analytics.FirebaseAnalytics
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/**
 * Orchestrates regenerating an assistant message at a specific point in the
 * conversation timeline.
 *
 * Owns analytics policy + delegates the actual regeneration (which involves
 * truncating timeline, re-planning context, and triggering generation) to
 * ChatService.
 *
 * Introduced in M1.2. M1.3 will split ChatService's regeneration internals
 * (ContextPlanner / StreamingPipeline / ToolApprovalCoordinator) into their
 * own components — this orchestrator will then wire to those directly.
 */
class RegenerateMessageOrchestrator(
    private val chatService: ChatService,
    private val analytics: FirebaseAnalytics,
) {
    fun regenerate(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(conversationId, message, regenerateAssistantMsg)
    }
}
