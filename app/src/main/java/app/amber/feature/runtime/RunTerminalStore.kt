package app.amber.feature.runtime

import android.util.Log
import app.amber.agent.data.db.dao.RunTerminalDAO
import app.amber.agent.data.db.entity.RunTerminalEntity
import app.amber.core.ai.GenerationTerminal
import kotlinx.coroutines.CancellationException

private const val TAG = "RunTerminalStore"

/** Schema version of the run terminal table — surfaced on the debug page. */
const val RUN_TERMINAL_SCHEMA_VERSION = 1

/**
 * Typed Run Terminal (P1-03).
 *
 * WAITING_USER / WAITING_EXTERNAL / RESUMABLE / OUTCOME_UNKNOWN are pauses —
 * not completions and not failures. Only [RunTerminalState.isTerminal] states
 * end a run. STEP_LIMIT is terminal and must never be mapped to COMPLETED.
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
}

class RoomRunTerminalStore(
    private val dao: RunTerminalDAO,
    private val now: () -> Long = System::currentTimeMillis,
) : RunTerminalStore {

    override suspend fun begin(runId: String, conversationId: String, assistantId: String?) {
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
        if (inserted != -1L) return
        // Resume path: flip an existing live row back to RUNNING in one
        // conditional UPDATE. 0 rows = terminal row — write-once wins.
        val resumed = dao.resumeIfLive(runId, conversationId, assistantId, nowMs)
        if (resumed == 0) {
            runCatching { Log.w(TAG, "begin: refusing to re-open terminal run $runId") }
        }
    }

    override suspend fun pause(runId: String, state: RunTerminalState, reason: PauseReason?) {
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

    override suspend fun finish(runId: String, state: RunTerminalState, reason: PauseReason?) {
        if (!state.isTerminal) {
            runCatching { Log.w(TAG, "finish: refusing non-terminal state $state for $runId") }
            return
        }
        // Conditional UPDATE: write-once and the STEP_LIMIT→COMPLETED refusal
        // are enforced by the WHERE clause, not by a read-check-write race.
        val updated = dao.finishIfLive(
            runId = runId,
            state = state.name,
            reason = reason?.name,
            nowMs = now(),
        )
        if (updated == 0) {
            runCatching { Log.w(TAG, "finish: run $runId not finishable to $state (already terminal or STEP_LIMIT), skipped") }
        }
    }

    override suspend fun cancelWaitingUser(runId: String, conversationId: String): Boolean =
        dao.cancelWaitingUser(runId, conversationId, now()) > 0

    override suspend fun get(runId: String): RunTerminal? =
        dao.getByRunId(runId)?.let(RunTerminal::from)

    override suspend fun activeForConversation(conversationId: String): RunTerminal? =
        dao.activeByConversation(conversationId)?.let(RunTerminal::from)

    override suspend fun unfinished(): List<RunTerminal> =
        dao.listUnfinished().map(RunTerminal::from)
}

/**
 * Pure decision: which terminal state to persist when a generation flow ends.
 * COMPLETED is only chosen when the flow ended cleanly without reporting a
 * pause — the caller must persist the conversation first, then call this.
 */
fun terminalForFlowEnd(
    flowCause: Throwable?,
    reportedPause: GenerationTerminal?,
): Pair<RunTerminalState, PauseReason?> = when {
    flowCause is CancellationException -> RunTerminalState.CANCELLED to PauseReason.USER_STOP
    flowCause != null -> RunTerminalState.FAILED to null
    reportedPause is GenerationTerminal.WaitingUser -> RunTerminalState.WAITING_USER to PauseReason.TOOL_APPROVAL
    reportedPause is GenerationTerminal.StepLimit -> RunTerminalState.STEP_LIMIT to PauseReason.STEP_LIMIT_EXHAUSTED
    else -> RunTerminalState.COMPLETED to null
}
