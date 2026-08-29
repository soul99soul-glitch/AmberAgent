package app.amber.core.agent.store

import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.runtime.RunStatusTransitions
import app.amber.core.agent.runtime.RunTransitionResult
import app.amber.core.agent.runtime.TraceSpanRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

class RoomAgentEventStore(
    private val dao: AgentRuntimeDao,
    private val now: () -> Long = System::currentTimeMillis,
) : AgentEventStore {

    override suspend fun appendRun(run: AgentRunRecord) {
        // insertRun is IGNORE-on-conflict: create-only by design.
        dao.insertRun(run.toEntity())
    }

    override suspend fun appendEvent(event: AgentEventRecord) {
        dao.insertEvent(event.toEntity())
    }

    override suspend fun appendEventAllocatingSeq(event: AgentEventRecord): AgentEventRecord {
        val seq = dao.appendEventAllocatingSeq(event.toEntity())
        return event.copy(seq = seq)
    }

    override suspend fun transitionRun(
        runId: AgentRunId,
        expected: Set<RunStatus>,
        to: RunStatus,
        reason: String?,
    ): RunTransitionResult {
        val current = dao.getRun(runId.value) ?: return RunTransitionResult.UnknownRun(to)
        // Fail-closed: a persisted state we cannot parse is never overwritten.
        val from = RunStatus.parse(current.status)
            ?: return RunTransitionResult.Rejected(current = null, to = to, illegal = false)
        if (!RunStatusTransitions.canTransition(from, to)) {
            return RunTransitionResult.Rejected(current = from, to = to, illegal = true)
        }
        if (expected.isNotEmpty() && from !in expected) {
            return RunTransitionResult.Rejected(current = from, to = to, illegal = false)
        }
        if (from == to) return RunTransitionResult.Applied(from, to) // idempotent no-op
        val updated = dao.transitionStatus(
            runId = runId.value,
            expectedStatus = current.status,
            to = to.wireName,
            reason = reason,
            setFinished = to.isTerminal,
            now = now(),
        )
        if (updated == 1) return RunTransitionResult.Applied(from, to)
        // Lost the race: report the winning state instead of pretending.
        val winner = dao.getRun(runId.value) ?: return RunTransitionResult.UnknownRun(to)
        return RunTransitionResult.Rejected(
            current = RunStatus.parse(winner.status),
            to = to,
            illegal = false,
        )
    }

    override suspend fun appendSpan(span: TraceSpanRecord) {
        dao.insertSpan(span.toEntity())
    }

    override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> =
        dao.observeRun(runId.value).mapNotNull { it?.toSnapshot() }

    override suspend fun listEvents(runId: AgentRunId): List<AgentEventRecord> =
        dao.listEvents(runId.value).map { it.toRecord() }

    override suspend fun deleteEventsByType(runId: AgentRunId, type: String) {
        dao.deleteEventsByType(runId.value, type)
    }

    override suspend fun deleteEventsOfTypeOlderThan(type: String, cutoffMs: Long): Int =
        dao.deleteEventsOfTypeOlderThan(type, cutoffMs)

    override suspend fun listUnfinishedRuns(): List<AgentRunRecord> =
        dao.listUnfinished().map { it.toRecord() }

    override suspend fun markInterrupted(runId: AgentRunId, reason: String) {
        dao.markInterrupted(runId.value, reason, now())
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
    status = status.wireName,
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
    status = RunStatus.parse(status) ?: RunStatus.INTERRUPTED,
    inputDigest = inputDigest,
    inputSnapshotRef = inputSnapshotRef,
    inputSchemaVersion = inputSchemaVersion,
    startedAt = startedAt,
    finishedAt = finishedAt,
    interruptedReason = interruptedReason,
)

private fun AgentRunEntity.toSnapshot(): AgentRunSnapshot? {
    // Fail-closed: an unparseable persisted state yields no snapshot rather
    // than a fabricated one.
    val parsed = RunStatus.parse(status) ?: return null
    return AgentRunSnapshot(
        runId = AgentRunId(runId),
        parentRunId = parentRunId?.let { AgentRunId(it) },
        descriptorId = AgentDescriptorId(agentDescriptorId),
        status = parsed,
        startedAt = startedAt,
        finishedAt = finishedAt,
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
