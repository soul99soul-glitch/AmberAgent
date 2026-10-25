package app.amber.feature.runtime

import android.util.Log
import app.amber.agent.data.db.dao.RunTerminalDAO
import app.amber.agent.data.db.entity.ConversationActivityBaselineEntity
import app.amber.agent.data.db.entity.ConversationActivitySeenEntity
import app.amber.agent.data.db.entity.RunTerminalEntity
import app.amber.core.ai.GenerationTerminal
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "RunTerminalStore"

/** Schema version of the run terminal table — surfaced on the debug page. */
const val RUN_TERMINAL_SCHEMA_VERSION = 1

/**
 * Typed Run Terminal (P1-03).
 *
 * WAITING_USER / WAITING_EXTERNAL / RESUMABLE / OUTCOME_UNKNOWN are pauses —
 * not completions and not failures. Only [RunTerminalState.isTerminal] states
 * end a run. STEP_LIMIT / OUTPUT_LIMIT / GUARD_STOPPED are terminal and must
 * never be mapped to COMPLETED.
 */
enum class RunTerminalState {
    RUNNING,
    WAITING_USER,
    WAITING_EXTERNAL,
    RESUMABLE,
    COMPLETED,
    CANCELLED,
    FAILED,
    STEP_LIMIT,
    OUTPUT_LIMIT,
    GUARD_STOPPED,
    OUTCOME_UNKNOWN,
    INTERRUPTED,
    ;

    val isTerminal: Boolean get() = this in TERMINAL_STATES

    companion object {
        val TERMINAL_STATES: Set<RunTerminalState> = setOf(
            COMPLETED,
            CANCELLED,
            FAILED,
            STEP_LIMIT,
            OUTPUT_LIMIT,
            GUARD_STOPPED,
            INTERRUPTED,
        )
    }
}

/** Why a run is paused. Persisted next to the terminal state. */
enum class PauseReason {
    /** Waiting for the user to approve/deny a tool call (approval card). */
    TOOL_APPROVAL,

    /** Waiting for the user to answer an ask_user question. */
    ASK_USER,

    /** Waiting for the user to confirm retry/abandon of an unknown outcome. */
    OUTCOME_UNKNOWN,

    /** The user stopped the run. */
    USER_STOP,

    /** The process died while the run was active. */
    PROCESS_RESTART,

    /** The tool loop exhausted its step budget. */
    STEP_LIMIT_EXHAUSTED,

    /** The reply was cut off by the provider output limit. */
    OUTPUT_LIMIT_REACHED,

    /** The duplicate-tool-call guard stopped the loop. */
    DUPLICATE_TOOL_CALL,
}

data class RunTerminal(
    val runId: String,
    val conversationId: String,
    val assistantId: String?,
    val state: RunTerminalState,
    val pauseReason: PauseReason?,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val finishedAtMs: Long?,
) {
    val activityVersionMs: Long get() = finishedAtMs ?: updatedAtMs

    companion object {
        fun from(entity: RunTerminalEntity): RunTerminal = RunTerminal(
            runId = entity.runId,
            conversationId = entity.conversationId,
            assistantId = entity.assistantId,
            state = runCatching { RunTerminalState.valueOf(entity.state) }
                .getOrDefault(RunTerminalState.INTERRUPTED),
            pauseReason = entity.pauseReason?.let { reason ->
                runCatching { PauseReason.valueOf(reason) }.getOrNull()
            },
            startedAtMs = entity.startedAtMs,
            updatedAtMs = entity.updatedAtMs,
            finishedAtMs = entity.finishedAtMs,
        )
    }
}

/** Persisted activity-center watermark for one run. */
data class ConversationActivitySeen(
    val runId: String,
    val conversationId: String,
    val versionMs: Long,
) {
    companion object {
        fun from(entity: ConversationActivitySeenEntity) = ConversationActivitySeen(
            runId = entity.runId,
            conversationId = entity.conversationId,
            versionMs = entity.versionMs,
        )
    }
}

/**
 * Persisted typed terminal state per generation run. One row per runId; a
 * terminal state is write-once (a finished run is never re-opened, and
 * STEP_LIMIT can never be overwritten by COMPLETED).
 */
interface RunTerminalStore {
    suspend fun begin(runId: String, conversationId: String, assistantId: String?)

    suspend fun pause(runId: String, state: RunTerminalState, reason: PauseReason?)

    suspend fun finish(runId: String, state: RunTerminalState, reason: PauseReason? = null)

    /**
     * Stop a persisted approval pause after its generation Job has ended and
     * the in-memory ownership entry has been released.
     */
    suspend fun cancelWaitingUser(runId: String, conversationId: String): Boolean

    suspend fun get(runId: String): RunTerminal?

    /** The latest run still open (paused or running) for a conversation. */
    suspend fun activeForConversation(conversationId: String): RunTerminal?

    suspend fun unfinished(): List<RunTerminal>

    /** Live runs plus attention terminals for the cross-conversation activity center. */
    fun observeActivity(): Flow<List<RunTerminal>>

    fun observeActivitySeen(): Flow<List<ConversationActivitySeen>>

    /**
     * Persist the first-upgrade cutoff. Later calls return the original timestamp
     * so historical COMPLETED/FAILED rows never become unread as a flood.
     */
    suspend fun ensureActivityBaseline(nowMs: Long): Long

    /**
     * CAS watermark for the version the user actually opened. Returns true when
     * a row was inserted or moved forward.
     *
     * The durable owner must exist: [runId] belongs to [conversationId] and
     * [versionMs] is at most that run's `finishedAtMs ?: updatedAtMs`. Missing
     * runs, mismatched conversations, and future versions are rejected and
     * mint no watermark. Registry-only RUNNING (no `begin` row yet) cannot
     * bypass this check.
     */
    suspend fun markActivitySeen(runId: String, conversationId: String, versionMs: Long): Boolean
}

class RoomRunTerminalStore(
    private val dao: RunTerminalDAO,
    private val now: () -> Long = System::currentTimeMillis,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) : RunTerminalStore {

    override suspend fun begin(runId: String, conversationId: String, assistantId: String?) {
        withDurableWrite {
            val nowMs = now()
            // Create path: INSERT only when the runId is new.
            val inserted = dao.insertIgnore(
                RunTerminalEntity(
                    runId = runId,
                    conversationId = conversationId,
                    assistantId = assistantId,
                    state = RunTerminalState.RUNNING.name,
                    pauseReason = null,
                    startedAtMs = nowMs,
                    updatedAtMs = nowMs,
                    finishedAtMs = null,
                )
            )
            if (inserted != -1L) return@withDurableWrite
            // Resume path: flip an existing live row back to RUNNING in one
            // conditional UPDATE. 0 rows = terminal row — write-once wins.
            val resumed = dao.resumeIfLive(runId, conversationId, assistantId, nowMs)
            if (resumed == 0) {
                runCatching { Log.w(TAG, "begin: refusing to re-open terminal run $runId") }
            }
        }
    }

    override suspend fun pause(runId: String, state: RunTerminalState, reason: PauseReason?) {
        withDurableWrite {
            // Conditional UPDATE: 0 rows = missing or already-terminal row — never resurrect.
            val updated = dao.pauseIfLive(
                runId = runId,
                state = state.name,
                reason = reason?.name,
                nowMs = now(),
            )
            if (updated == 0) {
                runCatching { Log.w(TAG, "pause: no live run row for $runId (requested $state), skipped") }
            }
        }
    }

    override suspend fun finish(runId: String, state: RunTerminalState, reason: PauseReason?) {
        withDurableWrite {
            if (!state.isTerminal) {
                runCatching { Log.w(TAG, "finish: refusing non-terminal state $state for $runId") }
                return@withDurableWrite
            }
            // Conditional UPDATE: write-once and the STEP_LIMIT / OUTPUT_LIMIT /
            // GUARD_STOPPED→COMPLETED refusal are enforced by the WHERE clause,
            // not by a read-check-write race.
            val updated = dao.finishIfLive(
                runId = runId,
                state = state.name,
                reason = reason?.name,
                nowMs = now(),
            )
            if (updated == 0) {
                runCatching { Log.w(TAG, "finish: run $runId not finishable to $state (already terminal or a limit/guard terminal), skipped") }
            }
        }
    }

    override suspend fun cancelWaitingUser(runId: String, conversationId: String): Boolean =
        withDurableWrite {
            dao.cancelWaitingUser(runId, conversationId, now()) > 0
        }

    override suspend fun get(runId: String): RunTerminal? =
        dao.getByRunId(runId)?.let(RunTerminal::from)

    override suspend fun activeForConversation(conversationId: String): RunTerminal? =
        dao.activeByConversation(conversationId)?.let(RunTerminal::from)

    override suspend fun unfinished(): List<RunTerminal> =
        dao.listUnfinished().map(RunTerminal::from)

    override fun observeActivity(): Flow<List<RunTerminal>> =
        dao.observeActivityCandidates().map { rows -> rows.map(RunTerminal::from) }

    override fun observeActivitySeen(): Flow<List<ConversationActivitySeen>> =
        dao.observeActivitySeen().map { rows -> rows.map(ConversationActivitySeen::from) }

    override suspend fun ensureActivityBaseline(nowMs: Long): Long = withDurableWrite {
        dao.insertActivityBaselineIfAbsent(
            ConversationActivityBaselineEntity(id = 1, establishedAtMs = nowMs),
        )
        dao.getActivityBaselineMs() ?: nowMs
    }

    override suspend fun markActivitySeen(
        runId: String,
        conversationId: String,
        versionMs: Long,
    ): Boolean = withDurableWrite {
        dao.markActivitySeenOwned(runId, conversationId, versionMs)
    }

    private suspend fun <T> withDurableWrite(block: suspend () -> T): T {
        val gate = restoreWriteGate
        return if (gate == null) block() else gate.withCurrentWriterOrCancel(block)
    }
}

/**
 * Pure decision: which terminal state to persist when a generation flow ends.
 * COMPLETED is only chosen when the flow ended cleanly without reporting a
 * pause — the caller must persist the conversation first, then call this.
 * STEP_LIMIT / OUTPUT_LIMIT / GUARD_STOPPED are terminal and never map to
 * COMPLETED.
 */
fun terminalForFlowEnd(
    flowCause: Throwable?,
    reportedPause: GenerationTerminal?,
): Pair<RunTerminalState, PauseReason?> = when {
    flowCause is CancellationException -> RunTerminalState.CANCELLED to PauseReason.USER_STOP
    flowCause != null -> RunTerminalState.FAILED to null
    reportedPause is GenerationTerminal.WaitingUser -> RunTerminalState.WAITING_USER to PauseReason.TOOL_APPROVAL
    reportedPause is GenerationTerminal.StepLimit -> RunTerminalState.STEP_LIMIT to PauseReason.STEP_LIMIT_EXHAUSTED
    reportedPause is GenerationTerminal.OutputLimit -> RunTerminalState.OUTPUT_LIMIT to PauseReason.OUTPUT_LIMIT_REACHED
    reportedPause is GenerationTerminal.GuardStopped -> RunTerminalState.GUARD_STOPPED to PauseReason.DUPLICATE_TOOL_CALL
    else -> RunTerminalState.COMPLETED to null
}
