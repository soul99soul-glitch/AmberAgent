package app.amber.core.agent.runtime

import kotlinx.coroutines.flow.Flow

interface AgentEventStore {
    suspend fun appendRun(run: AgentRunRecord)

    /**
     * Append an event record. Idempotent on (runId, seq): re-appending an
     * event with an already-persisted (runId, seq) pair is a silent no-op,
     * so retries after partial failures never duplicate or corrupt the log.
     */
    suspend fun appendEvent(event: AgentEventRecord)
    suspend fun appendSpan(span: TraceSpanRecord)
    fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot>
    suspend fun listEvents(runId: AgentRunId): List<AgentEventRecord>
    suspend fun deleteEventsByType(runId: AgentRunId, type: String)
    suspend fun listUnfinishedRuns(): List<AgentRunRecord>
    suspend fun markInterrupted(runId: AgentRunId, reason: String)
}

data class AgentRunRecord(
    val runId: String,
    val parentRunId: String?,
    val agentDescriptorId: String,
    val agentVersion: String,
    val conversationId: String?,
    val messageNodeId: String?,
    val producesMessageId: String?,
    val assistantId: String?,
    val status: String,
    val inputDigest: String,
    val inputSnapshotRef: String?,
    val inputSchemaVersion: Int,
    val startedAt: Long,
    val finishedAt: Long?,
    val interruptedReason: String?,
)

data class AgentEventRecord(
    val eventId: String,
    val runId: String,
    val parentRunId: String?,
    val seq: Long,
    val type: String,
    val payloadType: String,
    val payload: String,
    val payloadSchemaVersion: Int,
    val agentDescriptorId: String,
    val agentVersion: String,
    val isFinal: Boolean,
    val ts: Long,
)

data class TraceSpanRecord(
    val spanId: String,
    val runId: String,
    val parentSpanId: String?,
    val name: String,
    val kind: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val attributesJson: String,
)
