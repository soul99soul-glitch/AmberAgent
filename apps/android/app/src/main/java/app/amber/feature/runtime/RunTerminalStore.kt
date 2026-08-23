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
        val existing = dao.getByRunId(runId)
        val nowMs = now()
        dao.upsert(
            existing?.copy(
                conversationId = conversationId,
                assistantId = assistantId,
                state = RunTerminalState.RUNNING.name,
                pauseReason = null,
                updatedAtMs = nowMs,
                finishedAtMs = null,
            ) ?: RunTerminalEntity(
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
    }

    override suspend fun pause(runId: String, state: RunTerminalState, reason: PauseReason?) {
        val entity = dao.getByRunId(runId) ?: return
        if (entity.finishedAtMs != null) return // terminal is write-once
        dao.upsert(
            entity.copy(
                state = state.name,
                pauseReason = reason?.name,
                updatedAtMs = now(),
            )
        )
    }

    override suspend fun finish(runId: String, state: RunTerminalState, reason: PauseReason?) {
        val entity = dao.getByRunId(runId) ?: return
        if (entity.finishedAtMs != null) return // terminal is write-once
        if (!state.isTerminal) {
            runCatching { Log.w(TAG, "finish: refusing non-terminal state $state for $runId") }
            return
        }
        // STEP_LIMIT must never be mapped to COMPLETED (plan §P1-03).
        if (entity.state == RunTerminalState.STEP_LIMIT.name && state == RunTerminalState.COMPLETED) {
            runCatching { Log.w(TAG, "finish: refusing to map STEP_LIMIT to COMPLETED for $runId") }
            return
        }
        val nowMs = now()
        dao.upsert(
            entity.copy(
                state = state.name,
                pauseReason = reason?.name,
                updatedAtMs = nowMs,
                finishedAtMs = nowMs,
            )
        )
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
