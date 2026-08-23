package app.amber.feature.jscell

import kotlinx.serialization.Serializable

/**
 * P4-03 persistent JS cell (parity plan §10 P4-03) — cell state machine.
 *
 *  - WAITING: cell created / idle between runs.
 *  - RUNNING: a JS evaluation is in flight on the cell's worker thread.
 *  - COMPLETED: the evaluation returned a value (terminal).
 *  - FAILED: the evaluation threw or hit a hard limit (terminal).
 *  - TERMINATED: stopped by the user, or marked dead after a process restart
 *    (terminal — never silently resurrected).
 *
 * Only metadata + store content + terminal state are persisted; an in-flight
 * JS stack is never saved (first-version scope: no cross-restart resume).
 */
enum class JsCellStatus {
    WAITING,
    RUNNING,
    COMPLETED,
    FAILED,
    TERMINATED,
}

/** Persisted record for one cell (Settings DataStore, JSON list). */
@Serializable
data class JsCellRecord(
    val cellId: String,
    /** Owner generation run (round) that created the cell; null on non-durable paths. */
    val ownerRunId: String?,
    val status: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** `js_cell_store` payload — small serializable state, never a JS stack. */
    val storeJson: String = "{}",
    /** Last captured console output (persisted on terminal transitions). */
    val lastOutput: String = "",
    /** Final result JSON when COMPLETED. */
    val terminalResult: String? = null,
    /** Failure / termination reason when FAILED / TERMINATED. */
    val error: String? = null,
) {
    val statusEnum: JsCellStatus
        get() = runCatching { JsCellStatus.valueOf(status) }.getOrDefault(JsCellStatus.TERMINATED)
}

/**
 * Hard resource limits for one cell. All limits are applied regardless of
 * caller: memory + JS stack depth are enforced by the QuickJS engine, output
 * and run time by the runtime state machine, store size at the store API.
 *
 * [quickReturnMs] is the run-threshold: an evaluation that does not finish
 * within it is reported to the model as `running` + cellId (the long-task
 * path), and its output is retrieved via `js_cell_wait`.
 */
data class JsCellLimits(
    /** QuickJS context memory limit (bytes). */
    val memoryBytes: Int = 8 * 1024 * 1024,
    /** QuickJS JS stack size limit (bytes) — caps recursion/nesting depth. */
    val maxStackSize: Int = 1024 * 1024,
    /** Hard cap on accumulated console output characters per run. */
    val outputChars: Int = 16_000,
    /** js_cell_run returns "running" after this many ms of execution. */
    val quickReturnMs: Long = 200,
    /** Total run time hard limit per cell; exceeded => TERMINATED(time_limit). */
    val maxRunTimeMs: Long = 60_000,
    /** js_cell_store payload size cap (characters). */
    val storeBytes: Int = 32 * 1024,
)

/** Why a cell was terminated. */
object JsCellTerminationReasons {
    const val USER = "user"
    const val TIME_LIMIT = "time_limit"
    const val PROCESS_RESTART = "process_restart"
}
