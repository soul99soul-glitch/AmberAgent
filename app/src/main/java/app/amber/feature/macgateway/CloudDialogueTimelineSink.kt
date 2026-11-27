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

    suspend fun appendLocalUserMessage(conversationId: String, text: String, eventId: String?, requestId: String) =
        appendUserMessage(conversationId, text, eventId)

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
        sourceRefs: List<String> = emptyList(),
        sourcePaths: List<String> = emptyList(),
        outcomeUnknown: Boolean = false,
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
            sourceRefs: List<String>,
            sourcePaths: List<String>,
            outcomeUnknown: Boolean,
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
        appendUserNode(uuid, text, Uuid.random())
    }

    override suspend fun appendLocalUserMessage(conversationId: String, text: String, eventId: String?, requestId: String) {
        val messageId = runCatching { Uuid.parse(requestId) }.getOrElse { Uuid.random() }
        check(appendUserNode(Uuid.parse(conversationId), text, messageId)) {
            "The conversation changed before the message could be saved."
        }
    }

    private suspend fun appendUserNode(conversationId: Uuid, text: String, messageId: Uuid): Boolean {
        val node = UIMessage(
            id = messageId,
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(text)),
        ).toMessageNode()
        return conversationStore.appendCloudDialogueNode(
            conversationId = conversationId,
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
        sourceRefs: List<String>,
        sourcePaths: List<String>,
        outcomeUnknown: Boolean,
    ) {
        val uuid = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return
        val formatted = formatTaskResultMarkdown(
            workItemId = workItemId,
            taskTitle = taskTitle,
            outcome = outcome,
            text = text,
            sourceRefs = sourceRefs,
            sourcePaths = sourcePaths,
            outcomeUnknown = outcomeUnknown,
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
            sourceRefs: List<String> = emptyList(),
            sourcePaths: List<String> = emptyList(),
            outcomeUnknown: Boolean = false,
        ): String {
            val label = taskTitle?.takeIf { it.isNotBlank() && it != workItemId } ?: workItemId
            val normalizedOutcome = when {
                outcomeUnknown -> "outcome_unknown"
                !outcome.isNullOrBlank() -> outcome
                else -> "completed"
            }
            val body = text.trim()
            val sources = (sourceRefs + sourcePaths)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            return buildString {
                append("**[").append(label).append(" · ").append(normalizedOutcome).append("]**")
                if (outcomeUnknown) {
                    append("\n\n> 执行结果未知（outcomeUnknown），可在下方选择重查、重试或放弃。")
                }
                if (body.isNotEmpty()) {
                    append("\n\n").append(body)
                }
                if (sources.isNotEmpty()) {
                    append("\n\n来源：")
                    sources.forEach { source ->
                        append("\n- `").append(source).append("`")
                    }
                }
            }
        }
    }
}
