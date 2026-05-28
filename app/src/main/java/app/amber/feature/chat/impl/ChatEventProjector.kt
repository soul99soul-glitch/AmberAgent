package app.amber.feature.chat.impl

import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.feature.chat.api.ChatEventPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

class ChatEventProjector(
    private val eventStore: AgentEventStore,
    private val conversationRepo: ConversationRepository,
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
        runId: AgentRunId,
        event: ChatEventPayload.AssistantMessageFinalized,
    ) {
        val conversationId = Uuid.parse(event.messageNodeId.value)
        val conversation = conversationRepo.getConversationById(conversationId) ?: return

        conversationRepo.updateConversationMetadata(
            conversationId = conversationId,
            updateAt = java.time.Instant.now(),
        )
    }

    suspend fun replayUnfinished() {
        val unfinished = eventStore.listUnfinishedRuns()
        for (run in unfinished) {
            eventStore.markInterrupted(
                AgentRunId(run.runId),
                reason = "process_restart",
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
