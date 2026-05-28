package me.rerere.rikkahub.service.orchestrator

import me.rerere.ai.ui.UIMessage
import app.amber.core.model.Conversation
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/**
 * Orchestrates branching (forking) a conversation at a specific message —
 * creates a copy of the conversation truncated at that message so the user
 * can explore an alternative path without losing the original.
 *
 * Unlike Send / Regenerate, branching does NOT invoke generation; it's a pure
 * conversation-copy operation. Owns the "branch is identified by message id"
 * input adaptation (callers pass the full UIMessage they already have on hand).
 *
 * Introduced in M1.2.
 */
class BranchMessageOrchestrator(
    private val chatService: ChatService,
) {
    suspend fun fork(
        conversationId: Uuid,
        message: UIMessage,
    ): Conversation {
        return chatService.forkConversationAtMessage(conversationId, message.id)
    }
}
