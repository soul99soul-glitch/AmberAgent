package app.amber.core.agent.store

import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunStatus
import app.amber.core.agent.runtime.TraceSpanRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomAgentEventStore(
    private val dao: AgentRuntimeDao,
) : AgentEventStore {

    override suspend fun appendRun(run: AgentRunRecord) {
        dao.insertRun(run.toEntity())
    }

    override suspend fun appendEvent(event: AgentEventRecord) {
        dao.insertEvent(event.toEntity())
    }

    override suspend fun appendSpan(span: TraceSpanRecord) {
        dao.insertSpan(span.toEntity())
    }

    override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> =
        dao.observeRun(runId.value).map { entity ->
            entity?.toSnapshot() ?: AgentRunSnapshot(
                runId = runId,
                parentRunId = null,
                descriptorId = app.amber.core.agent.runtime.AgentDescriptorId("unknown"),
                status = AgentRunStatus.INTERRUPTED,
                startedAt = 0,
                finishedAt = null,
            )
        }

    override suspend fun listUnfinishedRuns(): List<AgentRunRecord> =
        dao.listUnfinished().map { it.toRecord() }

    override suspend fun markInterrupted(runId: AgentRunId, reason: String) {
        dao.markInterrupted(runId.value, reason, System.currentTimeMillis())
    }
}

private fun AgentRunRecord.toEntity() = AgentRunEntity(
    runId = runId,
    parentRunId = parentRunId,
    agentDescriptorId = agentDescriptorId,
    agentVersion = agentVersion,
    conversationId = conversationId,
    messageNodeId = messageNodeId,
    producesMessageId = producesMessageId,
    assistantId = assistantId,
    status = status,
    inputDigest = inputDigest,
    inputSnapshotRef = inputSnapshotRef,
    inputSchemaVersion = inputSchemaVersion,
    startedAt = startedAt,
    finishedAt = finishedAt,
    interruptedReason = interruptedReason,
)

private fun AgentRunEntity.toRecord() = AgentRunRecord(
    runId = runId,
    parentRunId = parentRunId,
    agentDescriptorId = agentDescriptorId,
    agentVersion = agentVersion,
    conversationId = conversationId,
    messageNodeId = messageNodeId,
    producesMessageId = producesMessageId,
    assistantId = assistantId,
    status = status,
    inputDigest = inputDigest,
    inputSnapshotRef = inputSnapshotRef,
    inputSchemaVersion = inputSchemaVersion,
    startedAt = startedAt,
    finishedAt = finishedAt,
    interruptedReason = interruptedReason,
)

private fun AgentRunEntity.toSnapshot() = AgentRunSnapshot(
    runId = AgentRunId(runId),
    parentRunId = parentRunId?.let { AgentRunId(it) },
    descriptorId = app.amber.core.agent.runtime.AgentDescriptorId(agentDescriptorId),
    status = try {
        AgentRunStatus.valueOf(status.uppercase())
    } catch (_: IllegalArgumentException) {
        AgentRunStatus.INTERRUPTED
    },
    startedAt = startedAt,
    finishedAt = finishedAt,
)

private fun AgentEventRecord.toEntity() = AgentEventEntity(
    eventId = eventId,
    runId = runId,
    parentRunId = parentRunId,
    seq = seq,
    type = type,
    payloadType = payloadType,
    payload = payload,
    payloadSchemaVersion = payloadSchemaVersion,
    agentDescriptorId = agentDescriptorId,
    agentVersion = agentVersion,
    isFinal = isFinal,
    ts = ts,
)

private fun TraceSpanRecord.toEntity() = TraceSpanEntity(
    spanId = spanId,
    runId = runId,
    parentSpanId = parentSpanId,
    name = name,
    kind = kind,
    status = status,
    startedAt = startedAt,
    endedAt = endedAt,
    attributesJson = attributesJson,
)
