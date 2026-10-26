package app.amber.feature.chat.impl

import android.util.Log
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.feature.chat.api.ChatEventPayload
import kotlinx.serialization.json.Json
import app.amber.core.repository.ConversationRepository
import kotlin.uuid.Uuid

private const val TAG = "ChatEventProjector"

class ChatEventProjector(
    private val eventStore: AgentEventStore,
    private val conversationRepo: ConversationRepository,
    private val json: Json,
) {
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
