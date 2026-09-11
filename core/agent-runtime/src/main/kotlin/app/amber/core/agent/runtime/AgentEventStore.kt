package app.amber.core.agent.runtime

import kotlinx.coroutines.flow.Flow

interface AgentEventStore {
    /**
     * Create the run row. Create-only: an existing row for the same runId is
     * left untouched — status changes must go through [transitionRun] so a
     * stale writer can never overwrite a newer or terminal state.
     *
     * Returns true when the row was created, false when a row for the same
     * runId already existed (conflict — nothing was written). Store failures
     * surface as exceptions, so callers can gate execution on a durable
     * create (e.g. [appendRun] winning the right to run a launch).
     */
    suspend fun appendRun(run: AgentRunRecord): Boolean

    /**
     * Append an event record. Idempotent on (runId, seq): re-appending an
     * event with an already-persisted (runId, seq) pair is a silent no-op,
     * so retries after partial failures never duplicate or corrupt the log.
     */
    suspend fun appendEvent(event: AgentEventRecord)

    /**
     * Append an event whose sequence number is allocated by the store
     * (monotonic per run, database-assigned) inside one transaction, and
     * return the stored record including its assigned seq.
     *
     * Writers that keep an in-memory seq counter must use this variant when
     * they can outlive a process restart — a recycled counter would collide
     * with already-persisted seqs and silently drop events under the
     * idempotent-append rule.
     */
    suspend fun appendEventAllocatingSeq(event: AgentEventRecord): AgentEventRecord

    /**
     * Compare-and-set status transition under the Run Protocol
     * ([RunStatusTransitions]). The write lands only when the persisted
     * state is still in [expected] (empty set = any state) and the
     * transition is legal; otherwise the store reports
     * [RunTransitionResult.Rejected] and changes nothing. Terminal states
     * are write-once.
     */
    suspend fun transitionRun(
        runId: AgentRunId,
        expected: Set<RunStatus>,
        to: RunStatus,
        reason: String? = null,
    ): RunTransitionResult

    suspend fun appendSpan(span: TraceSpanRecord)
    fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot>
    suspend fun listEvents(runId: AgentRunId): List<AgentEventRecord>
    suspend fun deleteEventsByType(runId: AgentRunId, type: String)
    suspend fun listUnfinishedRuns(): List<AgentRunRecord>

    /**
     * Retention sweep hook: delete events of the given [type] whose `ts` is
     * older than [cutoffMs] (epoch millis). Returns the number of rows
     * removed. Default no-op so stores that do not age events (in-memory
     * fakes) stay valid; the Room store implements it so bulky per-wire-call
     * payloads (RequestSnapshot) age out on the same 7-day cold-start sweep
     * as terminal ledger effects instead of accumulating forever.
     */
    suspend fun deleteEventsOfTypeOlderThan(type: String, cutoffMs: Long): Int = 0

    /**
     * Recovery-only transition to [RunStatus.INTERRUPTED]; a live run must
     * finish through [transitionRun]. Refuses to touch a run that already
     * reached a terminal state.
     */
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
    val status: RunStatus,
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
