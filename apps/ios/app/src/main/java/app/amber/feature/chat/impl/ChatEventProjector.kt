package app.amber.feature.chat.impl

import android.util.Log
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunEvent
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunStatus
import app.amber.feature.chat.api.ChatEventPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ConversationAccess
import kotlin.time.Clock
import kotlin.time.Instant
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
            conversation.copy(updateAt = Clock.System.now())
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
    ) {
        val record = AgentRunEvent(
            eventId = Uuid.random().toString(),
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
            isFinal = event !is ChatEventPayload.AssistantTextDelta,
            ts = System.currentTimeMillis(),
        )
        if (record.isFinal && !eventStore.appendRunEvent(runId, record)) {
            error("Cannot append event ${record.eventId}: run ${runId.value} does not exist")
        }
    }

    suspend fun replayUnfinished() {
        val unfinished = eventStore.listRecoverableRuns(listOf("chat_turn"))
        for (run in unfinished) {
            Log.i(TAG, "Marking unfinished run ${run.runId} as interrupted")
            eventStore.transitionRun(
                runId = AgentRunId(run.runId),
                expectedStatus = run.status,
                status = AgentRunStatus.INTERRUPTED,
                inputSnapshotRef = run.inputSnapshotRef,
                detail = "process_restart",
                at = System.currentTimeMillis(),
            )
        }
    }

}

data class ProjectionState(
    val runId: AgentRunId,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
)
