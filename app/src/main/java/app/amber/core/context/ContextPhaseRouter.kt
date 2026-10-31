package app.amber.core.context

/**
 * Phase routing is a recommendation, never an automatic switch: compaction keeps the current
 * session working set, while switching to a new session requires explicit acceptance evidence.
 * Pending tool calls or an approval dialog always keep the current session.
 */
data class ContextPhaseRouting(
    val action: String,
    val reason: String,
)

object ContextPhaseRouter {
    const val ACTION_CONTINUE = "continue"
    const val ACTION_SWITCH = "switch"

    fun decide(
        pendingTools: Boolean,
        awaitingApproval: Boolean,
        acceptanceSatisfied: Boolean,
    ): ContextPhaseRouting = when {
        pendingTools -> ContextPhaseRouting(ACTION_CONTINUE, "pending_tool_calls")
        awaitingApproval -> ContextPhaseRouting(ACTION_CONTINUE, "awaiting_approval")
        !acceptanceSatisfied -> ContextPhaseRouting(ACTION_CONTINUE, "no_acceptance_evidence")
        else -> ContextPhaseRouting(ACTION_SWITCH, "phase_accepted")
    }
}
