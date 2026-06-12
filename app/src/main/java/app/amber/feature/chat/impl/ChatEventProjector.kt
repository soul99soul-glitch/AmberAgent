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
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ConversationAccess
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

        val existingNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }

        val updatedConversation = if (existingNodeIndex >= 0) {
            conversation.copy(updateAt = Instant.now())
        } else {
            Log.w(TAG, "Finalized assistant message $messageId before any message body was projected")
            return
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
                is ChatEventPayload.StreamCheckpoint ->
                    json.encodeToString(ChatEventPayload.StreamCheckpoint.serializer(), event)
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

    /**
     * Crash recovery: every run still `running` / `awaiting_permission` at
     * startup was killed mid-flight. Mark it interrupted, then project its
     * last stream checkpoint back onto the persisted conversation so the
     * UI reopens coherent (tail message annotated interrupted, pending
     * approvals preserved, half-streamed tool args made non-executable).
     */
    suspend fun replayUnfinished() {
        val unfinished = eventStore.listUnfinishedRuns()
        for (run in unfinished) {
            Log.i(TAG, "Marking unfinished run ${run.runId} as interrupted")
            val runId = AgentRunId(run.runId)
            eventStore.markInterrupted(
                runId,
                reason = "process_restart",
            )
            runCatching { projectInterruptedRun(runId) }
                .onFailure { Log.w(TAG, "Failed to project interrupted run ${run.runId}", it) }
        }
    }

    private suspend fun projectInterruptedRun(runId: AgentRunId) {
        val checkpoint = latestStreamCheckpoint(eventStore.listEvents(runId), json) ?: return
        val conversationId = runCatching { Uuid.parse(checkpoint.conversationId) }.getOrNull()
            ?: return
        val conversation = conversationRepo.getConversationById(conversationId) ?: return
        val projected = InterruptedRunProjection.project(conversation, checkpoint)
        if (projected !== conversation) {
            conversationRepo.updateConversation(projected)
            Log.i(
                TAG,
                "Projected interrupted run ${runId.value} into conversation $conversationId " +
                    "(tail=${checkpoint.messageId})",
            )
        }
        // Recovery consumed the checkpoints; drop them so agent_event stays lean.
        eventStore.deleteEventsByType(runId, ChatEventPayload.StreamCheckpoint.TYPE)
    }

    companion object {
        fun latestStreamCheckpoint(
            events: List<AgentEventRecord>,
            json: Json,
        ): ChatEventPayload.StreamCheckpoint? =
            events
                .filter { it.type == ChatEventPayload.StreamCheckpoint.TYPE }
                .maxByOrNull { it.seq }
                ?.let { record ->
                    runCatching {
                        json.decodeFromString(
                            ChatEventPayload.StreamCheckpoint.serializer(),
                            record.payload,
                        )
                    }.getOrNull()
                }
    }
}

data class ProjectionState(
    val runId: AgentRunId,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
)
