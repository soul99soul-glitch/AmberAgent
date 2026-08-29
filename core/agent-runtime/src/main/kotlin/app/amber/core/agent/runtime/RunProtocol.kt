package app.amber.core.agent.runtime

/**
 * Run Protocol (P0): the single typed run lifecycle shared by every agent
 * runtime surface (chat, subagent, DeepRead, Novel). Replaces the ad-hoc
 * string status writes with one state machine, one legal-transition table
 * and compare-and-set persistence, so a stale callback can never overwrite
 * a newer or terminal state.
 *
 * Vocabulary mirrors the app-level typed terminal store
 * (`RunTerminalState`): WAITING_USER / WAITING_EXTERNAL / RESUMABLE /
 * OUTCOME_UNKNOWN are pauses — not completions and not failures. Only
 * [RunStatus.isTerminal] states end a run. STEP_LIMIT is terminal and must
 * never be mapped to COMPLETED.
 */
enum class RunStatus {
    /** The run record exists but no step has executed yet. */
    CREATED,

    /** A step is actively executing (model request or tool batch). */
    RUNNING,

    /** Paused for the user: tool approval or an ask_user answer. */
    WAITING_USER,

    /** Paused on an external system (e.g. unconfirmed server-side cancel). */
    WAITING_EXTERNAL,

    /** Suspended with enough durable state to resume (e.g. after restart). */
    RESUMABLE,

    /** A non-idempotent tool finished with an unverifiable outcome; the run
     * waits for the user to confirm retry or abandon. */
    OUTCOME_UNKNOWN,

    COMPLETED,
    FAILED,
    CANCELLED,

    /** The process died (or the run was otherwise non-gracefully stopped)
     * while the run was live; set by recovery, never by the live loop. */
    INTERRUPTED,

    /** The tool loop exhausted its step budget without a final answer. */
    STEP_LIMIT,
    ;

    /** Pauses keep the run resumable; they never appear in [TERMINAL_STATES]. */
    val isPause: Boolean get() = this in PAUSE_STATES

    val isTerminal: Boolean get() = this in TERMINAL_STATES

    /** Lowercase persistence/wire form stored in the agent_runtime tables. */
    val wireName: String get() = name.lowercase()

    companion object {
        val PAUSE_STATES: Set<RunStatus> = setOf(
            WAITING_USER,
            WAITING_EXTERNAL,
            RESUMABLE,
            OUTCOME_UNKNOWN,
        )

        val TERMINAL_STATES: Set<RunStatus> = setOf(
            COMPLETED,
            FAILED,
            CANCELLED,
            INTERRUPTED,
            STEP_LIMIT,
        )

        /** Non-terminal states: the legal CAS guard for "any live run". */
        val LIVE_STATES: Set<RunStatus> = setOf(CREATED, RUNNING) + PAUSE_STATES

        /**
         * Fail-closed parse: returns null for unknown wire names instead of
         * fabricating a state. Accepts the pre-protocol kernel alias
         * `awaiting_permission` so rows written before the protocol landed
         * still resolve.
         */
        fun parse(raw: String): RunStatus? = when (raw.lowercase()) {
            "awaiting_permission" -> WAITING_USER
            else -> entries.firstOrNull { it.wireName == raw.lowercase() }
        }
    }
}

class IllegalRunTransitionException(from: RunStatus, to: RunStatus) :
    IllegalStateException("Illegal run transition: $from -> $to")

/**
 * The legal-transition table. Terminal states are write-once: they have no
 * outgoing transitions, which also makes `STEP_LIMIT -> COMPLETED`
 * impossible by construction.
 *
 * A transition from a state onto itself is always legal and treated as an
 * idempotent no-op, so crash-recovery writers can safely re-assert.
 */
object RunStatusTransitions {

    private val LEGAL_TARGETS: Map<RunStatus, Set<RunStatus>> = mapOf(
        RunStatus.CREATED to setOf(
            RunStatus.RUNNING,
            RunStatus.CANCELLED,
            RunStatus.FAILED,
            RunStatus.INTERRUPTED,
        ),
        RunStatus.RUNNING to setOf(
            RunStatus.WAITING_USER,
            RunStatus.WAITING_EXTERNAL,
            RunStatus.RESUMABLE,
            RunStatus.OUTCOME_UNKNOWN,
            RunStatus.COMPLETED,
            RunStatus.FAILED,
            RunStatus.CANCELLED,
            RunStatus.INTERRUPTED,
            RunStatus.STEP_LIMIT,
        ),
        // Approval answered / ask_user answered -> the loop re-enters RUNNING.
        // OUTCOME_UNKNOWN: cold-start recovery may discover, while the run is
        // parked for approval, that a started effect's outcome is undecidable
        // — the pause escalates to the actionable outcome-unknown pause.
        RunStatus.WAITING_USER to setOf(
            RunStatus.RUNNING,
            RunStatus.OUTCOME_UNKNOWN,
            RunStatus.CANCELLED,
            RunStatus.FAILED,
            RunStatus.INTERRUPTED,
        ),
        // Server-side resume succeeded, or the outcome proved undecidable.
        RunStatus.WAITING_EXTERNAL to setOf(
            RunStatus.RUNNING,
            RunStatus.OUTCOME_UNKNOWN,
            RunStatus.CANCELLED,
            RunStatus.FAILED,
            RunStatus.INTERRUPTED,
        ),
        RunStatus.RESUMABLE to setOf(
            RunStatus.RUNNING,
            // Same recovery escalation as WAITING_USER above.
            RunStatus.OUTCOME_UNKNOWN,
            RunStatus.CANCELLED,
            RunStatus.FAILED,
            RunStatus.INTERRUPTED,
        ),
        // User confirmed retry (-> RUNNING) or abandon (-> FAILED).
        RunStatus.OUTCOME_UNKNOWN to setOf(
            RunStatus.RUNNING,
            RunStatus.FAILED,
            RunStatus.CANCELLED,
            RunStatus.INTERRUPTED,
        ),
    )

    fun canTransition(from: RunStatus, to: RunStatus): Boolean =
        from == to || LEGAL_TARGETS[from]?.contains(to) == true

    fun requireLegal(from: RunStatus, to: RunStatus) {
        if (!canTransition(from, to)) throw IllegalRunTransitionException(from, to)
    }
}

/** Outcome of a compare-and-set run transition. Never silently succeeds. */
sealed interface RunTransitionResult {

    /** The transition landed (or the run was already in [to] — a no-op). */
    data class Applied(val from: RunStatus, val to: RunStatus) : RunTransitionResult

    /**
     * Refused: either the transition is not in the legal table
     * ([illegal] = true), or the run's current state no longer matches the
     * caller's expectation — a newer transition won the race.
     */
    data class Rejected(
        val current: RunStatus?,
        val to: RunStatus,
        val illegal: Boolean,
    ) : RunTransitionResult

    /** No run row exists at all. */
    data class UnknownRun(val to: RunStatus) : RunTransitionResult
}

val RunTransitionResult.applied: Boolean get() = this is RunTransitionResult.Applied
