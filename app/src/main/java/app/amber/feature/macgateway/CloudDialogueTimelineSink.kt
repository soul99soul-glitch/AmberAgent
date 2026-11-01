package app.amber.feature.macgateway

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.toMessageNode
import app.amber.core.service.ConversationStore
import kotlin.uuid.Uuid

/**
 * Receiving sink for conversation timeline entries produced by Cloud Dialogue v1.
 *
 * Only `user_message` / `followup`, `assistant_reply`, and `task_result` enter the conversation
 * timeline; `process_event` never enters the timeline or creates per-tool cards.
 */
interface CloudDialogueTimelineSink {
    suspend fun appendUserMessage(
        conversationId: String,
        text: String,
        eventId: String? = null,
    )

    suspend fun appendAssistantReply(
        conversationId: String,
        text: String,
        eventId: String,
        topicId: String,
    )

    suspend fun appendTaskResult(
        conversationId: String,
        workItemId: String,
        taskTitle: String?,
        outcome: String?,
        text: String,
        eventId: String,
        topicId: String,
    )

    object NoOp : CloudDialogueTimelineSink {
        override suspend fun appendUserMessage(conversationId: String, text: String, eventId: String?) = Unit
        override suspend fun appendAssistantReply(conversationId: String, text: String, eventId: String, topicId: String) = Unit
        override suspend fun appendTaskResult(
            conversationId: String,
            workItemId: String,
            taskTitle: String?,
            outcome: String?,
            text: String,
            eventId: String,
            topicId: String,
        ) = Unit
    }
}

class ConversationStoreCloudDialogueSink(
    private val conversationStore: ConversationStore,
) : CloudDialogueTimelineSink {
    override suspend fun appendUserMessage(
        conversationId: String,
        text: String,
        eventId: String?,
    ) {
        val uuid = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return
        val node = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(text)),
        ).toMessageNode()
        conversationStore.appendCloudDialogueNode(
            conversationId = uuid,
            node = node,
            titleHint = text,
        )
    }

    override suspend fun appendAssistantReply(
        conversationId: String,
        text: String,
        eventId: String,
        topicId: String,
    ) {
        val uuid = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return
        val node = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(text)),
        ).toMessageNode()
        conversationStore.appendCloudDialogueNode(
            conversationId = uuid,
            node = node,
        )
    }

    override suspend fun appendTaskResult(
        conversationId: String,
        workItemId: String,
        taskTitle: String?,
        outcome: String?,
        text: String,
        eventId: String,
        topicId: String,
    ) {
        val uuid = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return
        val formatted = formatTaskResultMarkdown(
            workItemId = workItemId,
            taskTitle = taskTitle,
            outcome = outcome,
            text = text,
        )
        val node = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(formatted)),
        ).toMessageNode()
        conversationStore.appendCloudDialogueNode(
            conversationId = uuid,
            node = node,
        )
    }

    companion object {
        fun formatTaskResultMarkdown(
            workItemId: String,
            taskTitle: String?,
            outcome: String?,
            text: String,
        ): String {
            val label = taskTitle?.takeIf { it.isNotBlank() && it != workItemId } ?: workItemId
            val normalizedOutcome = outcome?.takeIf { it.isNotBlank() } ?: "completed"
            val body = text.trim()
            return if (body.isNotEmpty()) {
                "**[$label · $normalizedOutcome]**\n\n$body"
            } else {
                "**[$label · $normalizedOutcome]**"
            }
        }
    }
}
