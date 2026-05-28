package app.amber.feature.runtime

object AgentLoopBudgetPrompt {
    fun build(stepIndex: Int, maxSteps: Int): String {
        val stage = stage(stepIndex, maxSteps) ?: return ""
        val remaining = remainingSteps(stepIndex, maxSteps)
        return when (stage) {
            AgentLoopBudgetStage.WARN ->
                "Agent loop budget: $remaining steps remain. Avoid starting new branches; reuse gathered evidence and call only tools that clearly unblock the final answer."

            AgentLoopBudgetStage.TIGHT ->
                "Agent loop budget: $remaining steps remain. Start converging now; call only essential tools and prepare a final answer from the evidence already gathered."

            AgentLoopBudgetStage.FINAL ->
                "Agent loop budget: $remaining steps remain. Do not start more tool work; provide the final answer now and explicitly note any unfinished items."
        }
    }

    fun shouldHideTools(stepIndex: Int, maxSteps: Int, hasResumableTools: Boolean): Boolean =
        !hasResumableTools && stage(stepIndex, maxSteps) == AgentLoopBudgetStage.FINAL

    internal fun stage(stepIndex: Int, maxSteps: Int): AgentLoopBudgetStage? {
        val remaining = remainingSteps(stepIndex, maxSteps)
        if (maxSteps <= SMALL_LOOP_MAX_STEPS) {
            return when {
                remaining <= 0 -> AgentLoopBudgetStage.FINAL
                remaining <= 1 -> AgentLoopBudgetStage.FINAL
                remaining <= 2 && stepIndex > 0 -> AgentLoopBudgetStage.TIGHT
                remaining <= 3 && stepIndex > 0 -> AgentLoopBudgetStage.WARN
                else -> null
            }
        }
        return when {
            remaining <= 0 -> AgentLoopBudgetStage.FINAL
            remaining <= 2 -> AgentLoopBudgetStage.FINAL
            remaining <= 6 -> AgentLoopBudgetStage.TIGHT
            remaining <= 12 -> AgentLoopBudgetStage.WARN
            else -> null
        }
    }

    private fun remainingSteps(stepIndex: Int, maxSteps: Int): Int =
        (maxSteps - stepIndex).coerceAtLeast(0)

    private const val SMALL_LOOP_MAX_STEPS = 4
}

internal enum class AgentLoopBudgetStage {
    WARN,
    TIGHT,
    FINAL,
}
