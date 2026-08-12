package app.amber.core.agent.runtime

import kotlinx.coroutines.flow.Flow

interface AgentEventStore {
    @Throws(Exception::class)
    suspend fun startRun(run: AgentRunRecord): Boolean
    @Throws(Exception::class)
    suspend fun getRun(runId: AgentRunId): AgentRunRecord?
    @Throws(Exception::class)
    suspend fun transitionRun(
        runId: AgentRunId,
        expectedStatus: AgentRunStatus,
        status: AgentRunStatus,
        inputSnapshotRef: String?,
        detail: String?,
        at: Long,
    ): Boolean
    @Throws(Exception::class)
    suspend fun appendRunEvent(runId: AgentRunId, event: AgentRunEvent): Boolean
    @Throws(Exception::class)
    suspend fun listRunEvents(runId: AgentRunId): List<AgentEventRecord>
    @Throws(Exception::class)
    suspend fun appendSpan(span: TraceSpanRecord)
    fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot>
    @Throws(Exception::class)
    suspend fun listRecoverableRuns(descriptorIds: List<String>): List<AgentRunRecord>
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
    val status: AgentRunStatus,
    val inputDigest: String,
    val inputSnapshotRef: String?,
    val inputSchemaVersion: Int,
    val startedAt: Long,
    val finishedAt: Long?,
    val interruptedReason: String?,
)

/**
 * Caller-owned event payload. Run identity and sequence are deliberately absent:
 * the durable store derives them from the persisted run in the same SQL write.
 */
data class AgentRunEvent(
    val eventId: String,
    val type: String,
    val payloadType: String,
    val payload: String,
    val payloadSchemaVersion: Int,
    val isFinal: Boolean,
    val ts: Long,
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
