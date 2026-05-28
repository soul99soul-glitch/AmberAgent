package app.amber.feature.chat.impl

import android.util.Log
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunStatus
import app.amber.feature.chat.api.ChatEventPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ConversationAccess
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "ChatEventProjector"

class ChatEventProjector(
    private val eventStore: AgentEventStore,
    private val conversationRepo: ConversationRepository,
    private val conversationAccess: ConversationAccess,
    private val json: Json,
) {
    fun observeProjection(runId: AgentRunId): Flow<ProjectionState> {
        return eventStore.observeRun(runId).map { snapshot ->
            ProjectionState(
                runId = runId,
                status = snapshot.status.name.lowercase(),
                startedAt = snapshot.startedAt,
                finishedAt = snapshot.finishedAt,
            )
        }
    }

    suspend fun projectFinalized(
        conversationId: Uuid,
        event: ChatEventPayload.AssistantMessageFinalized,
    ) {
        val messageId = Uuid.parse(event.messageId)
        val conversation = conversationAccess.getConversationFlow(conversationId).value

        val assistantMessage = UIMessage(
            id = messageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("")),
        )

        val existingNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }

        val updatedConversation = if (existingNodeIndex >= 0) {
            val node = conversation.messageNodes[existingNodeIndex]
            val updatedMessages = node.messages.map { msg ->
                if (msg.id == messageId) assistantMessage else msg
            }
            conversation.copy(
                messageNodes = conversation.messageNodes.toMutableList().apply {
                    set(existingNodeIndex, node.copy(messages = updatedMessages))
                },
                updateAt = Instant.now(),
            )
        } else {
            val isRegenerate = event.regenerateOf != null
            if (isRegenerate) {
                val targetNodeIndex = conversation.messageNodes.indexOfLast { node ->
                    node.messages.any { it.role == MessageRole.ASSISTANT }
                }
                if (targetNodeIndex >= 0) {
                    val node = conversation.messageNodes[targetNodeIndex]
                    conversation.copy(
                        messageNodes = conversation.messageNodes.toMutableList().apply {
                            set(targetNodeIndex, node.copy(
                                messages = node.messages + assistantMessage,
                            ))
                        },
                        updateAt = Instant.now(),
                    )
                } else {
                    appendNewNode(conversation, assistantMessage)
                }
            } else {
                appendNewNode(conversation, assistantMessage)
            }
        }

        conversationAccess.updateConversation(conversationId, updatedConversation, checkDeletedFiles = false)
        conversationRepo.updateConversationMetadata(
            conversationId = conversationId,
            updateAt = updatedConversation.updateAt,
        )
        Log.i(TAG, "Projected assistant message $messageId into conversation $conversationId")
    }

    suspend fun commitEvent(
        runId: AgentRunId,
        event: ChatEventPayload,
        seq: Long,
    ) {
        val record = AgentEventRecord(
            eventId = "${runId.value}_$seq",
            runId = runId.value,
            parentRunId = null,
            seq = seq,
            type = event::class.simpleName ?: "unknown",
            payloadType = event::class.qualifiedName ?: "unknown",
            payload = when (event) {
                is ChatEventPayload.AssistantMessageFinalized ->
                    json.encodeToString(ChatEventPayload.AssistantMessageFinalized.serializer(), event)
                is ChatEventPayload.ToolInvoked ->
                    json.encodeToString(ChatEventPayload.ToolInvoked.serializer(), event)
                is ChatEventPayload.UserMessageAccepted ->
                    json.encodeToString(ChatEventPayload.UserMessageAccepted.serializer(), event)
                is ChatEventPayload.AssistantTextDelta -> ""
            },
            payloadSchemaVersion = 1,
            agentDescriptorId = "chat_turn",
            agentVersion = "1.0.0",
            isFinal = event !is ChatEventPayload.AssistantTextDelta,
            ts = System.currentTimeMillis(),
        )
        if (record.isFinal) {
            eventStore.appendEvent(record)
        }
    }

    suspend fun replayUnfinished() {
        val unfinished = eventStore.listUnfinishedRuns()
        for (run in unfinished) {
            Log.i(TAG, "Marking unfinished run ${run.runId} as interrupted")
            eventStore.markInterrupted(
                AgentRunId(run.runId),
                reason = "process_restart",
            )
        }
    }

    private fun appendNewNode(conversation: Conversation, message: UIMessage): Conversation {
        return conversation.copy(
            messageNodes = conversation.messageNodes + MessageNode(
                messages = listOf(message),
            ),
            updateAt = Instant.now(),
        )
    }
}

data class ProjectionState(
    val runId: AgentRunId,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
)
