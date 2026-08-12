package app.amber.core.agent.store

import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunEvent
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunStatus
import app.amber.core.agent.runtime.TraceSpanRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

class RoomAgentEventStore(
    private val dao: AgentRuntimeDao,
) : AgentEventStore {

    @Throws(Exception::class)
    override suspend fun startRun(run: AgentRunRecord): Boolean {
        if (run.status != AgentRunStatus.RUNNING || run.finishedAt != null) return false
        return dao.insertRunIfAbsent(run.toEntity()) != -1L
    }

    @Throws(Exception::class)
    override suspend fun getRun(runId: AgentRunId): AgentRunRecord? =
        dao.getRun(runId.value)?.toRecord()

    @Throws(Exception::class)
    override suspend fun transitionRun(
        runId: AgentRunId,
        expectedStatus: AgentRunStatus,
        status: AgentRunStatus,
        inputSnapshotRef: String?,
        detail: String?,
        at: Long,
    ): Boolean {
        if (!expectedStatus.canTransitionTo(status)) return false
        return dao.transitionRun(
            runId = runId.value,
            expectedStatus = expectedStatus.wireName,
            status = status.wireName,
            inputSnapshotRef = inputSnapshotRef,
            detail = detail,
            finishedAt = at.takeIf { status.isTerminal },
        ) == 1
    }

    @Throws(Exception::class)
    override suspend fun appendRunEvent(runId: AgentRunId, event: AgentRunEvent): Boolean =
        dao.insertRunEvent(
            runId = runId.value,
            eventId = event.eventId,
            type = event.type,
            payloadType = event.payloadType,
            payload = event.payload,
            payloadSchemaVersion = event.payloadSchemaVersion,
            isFinal = event.isFinal,
            ts = event.ts,
        ) != -1L

    @Throws(Exception::class)
    override suspend fun listRunEvents(runId: AgentRunId): List<AgentEventRecord> =
        dao.listEventsForRun(runId.value).map { it.toRecord() }

    @Throws(Exception::class)
    override suspend fun appendSpan(span: TraceSpanRecord) {
        dao.insertSpan(span.toEntity())
    }

    override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> =
        dao.observeRun(runId.value).mapNotNull { it?.toSnapshot() }

    @Throws(Exception::class)
    override suspend fun listRecoverableRuns(descriptorIds: List<String>): List<AgentRunRecord> =
        dao.listRecoverable(descriptorIds).map { it.toRecord() }
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
    status = status.wireName,
    inputDigest = inputDigest,
    inputSnapshotRef = inputSnapshotRef,
    inputSchemaVersion = inputSchemaVersion,
    startedAt = startedAt,
    finishedAt = finishedAt,
    interruptedReason = interruptedReason,
)

private fun AgentRunEntity.toRecord(): AgentRunRecord {
    val typedStatus = AgentRunStatus.fromWireName(status)
    return AgentRunRecord(
        runId = runId,
        parentRunId = parentRunId,
        agentDescriptorId = agentDescriptorId,
        agentVersion = agentVersion,
        conversationId = conversationId,
        messageNodeId = messageNodeId,
        producesMessageId = producesMessageId,
        assistantId = assistantId,
        status = typedStatus,
        inputDigest = inputDigest,
        inputSnapshotRef = inputSnapshotRef,
        inputSchemaVersion = inputSchemaVersion,
        startedAt = startedAt,
        finishedAt = finishedAt.takeIf { typedStatus.isTerminal },
        interruptedReason = interruptedReason,
    )
}

private fun AgentRunEntity.toSnapshot(): AgentRunSnapshot {
    val typedStatus = AgentRunStatus.fromWireName(status)
    return AgentRunSnapshot(
        runId = AgentRunId(runId),
        parentRunId = parentRunId?.let { AgentRunId(it) },
        descriptorId = AgentDescriptorId(agentDescriptorId),
        status = typedStatus,
        startedAt = startedAt,
        finishedAt = finishedAt.takeIf { typedStatus.isTerminal },
    )
}

private fun AgentEventEntity.toRecord() = AgentEventRecord(
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
