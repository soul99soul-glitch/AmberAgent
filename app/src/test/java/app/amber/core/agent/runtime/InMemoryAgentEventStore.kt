package app.amber.core.agent.runtime

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Shared in-memory [AgentEventStore] for JVM tests that wire a real
 * [app.amber.core.agent.runtime.impl.InProcessAgentRunner] without Room.
 * Mirrors the protocol semantics of the Room store: create-only run rows,
 * idempotent (runId, seq) event append, CAS transitions with legality
 * checks and write-once terminal states. Safe for concurrent event appends
 * (parallel tool executions): the event list and the seq allocation share
 * one lock, and run rows live in a concurrent map.
 */
class InMemoryAgentEventStore : AgentEventStore {

    /** Single lock for the event list and its seq read-modify-write, so
     *  concurrent parallel-tool appends can neither corrupt the list nor
     *  allocate duplicate seqs. */
    private val eventLock = Any()
    private val eventList = mutableListOf<AgentEventRecord>()

    /** Snapshot copy under the lock — assertions never alias the live list. */
    val events: List<AgentEventRecord> get() = synchronized(eventLock) { eventList.toList() }

    val runs = ConcurrentHashMap<String, AgentRunRecord>()
    val interruptions = mutableListOf<Pair<AgentRunId, String>>()

    override suspend fun appendRun(run: AgentRunRecord): Boolean =
        runs.putIfAbsent(run.runId, run) == null

    override suspend fun appendEvent(event: AgentEventRecord) {
        synchronized(eventLock) {
            if (eventList.none { it.runId == event.runId && it.seq == event.seq }) {
                eventList += event
            }
        }
    }

    override suspend fun appendEventAllocatingSeq(event: AgentEventRecord): AgentEventRecord =
        synchronized(eventLock) {
            val next = (eventList.filter { it.runId == event.runId }.maxOfOrNull { it.seq } ?: 0L) + 1
            val stored = event.copy(seq = next)
            eventList += stored
            stored
        }

    override suspend fun transitionRun(
        runId: AgentRunId,
        expected: Set<RunStatus>,
        to: RunStatus,
        reason: String?,
    ): RunTransitionResult {
        val current = runs[runId.value] ?: return RunTransitionResult.UnknownRun(to)
        val from = current.status
        if (!RunStatusTransitions.canTransition(from, to)) {
            return RunTransitionResult.Rejected(from, to, illegal = true)
        }
        if (expected.isNotEmpty() && from !in expected) {
            return RunTransitionResult.Rejected(from, to, illegal = false)
        }
        runs[runId.value] = current.copy(
            status = to,
            finishedAt = if (to.isTerminal) System.currentTimeMillis() else current.finishedAt,
            interruptedReason = reason ?: current.interruptedReason,
        )
        return RunTransitionResult.Applied(from, to)
    }

    override suspend fun appendSpan(span: TraceSpanRecord) {}

    override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> = emptyFlow()

    override suspend fun listEvents(runId: AgentRunId): List<AgentEventRecord> =
        synchronized(eventLock) { eventList.filter { it.runId == runId.value } }.sortedBy { it.seq }

    override suspend fun deleteEventsByType(runId: AgentRunId, type: String) {
        synchronized(eventLock) {
            eventList.removeAll { it.runId == runId.value && it.type == type }
        }
    }

    override suspend fun listUnfinishedRuns(): List<AgentRunRecord> =
        runs.values.filter { !it.status.isTerminal }

    override suspend fun markInterrupted(runId: AgentRunId, reason: String) {
        interruptions += runId to reason
    }
}
