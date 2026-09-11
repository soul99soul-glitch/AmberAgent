package app.amber.core.agent.runtime

import kotlinx.coroutines.flow.StateFlow

interface AgentRunner {
    /**
     * Launch a run for [descriptorId]. [requestedRunId] lets the caller
     * resume a paused run under the same id (e.g. approval resume reuses the
     * persisted runId); the store is create-only, so reusing an id never
     * resets its persisted state.
     */
    fun <I : AgentInput> launch(
        descriptorId: AgentDescriptorId,
        input: I,
        requestedRunId: AgentRunId? = null,
    ): Result<AgentRunHandle>

    fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot>
    fun cancel(runId: AgentRunId)
    suspend fun listUnfinishedRuns(): List<AgentRunSnapshot>
}

data class AgentRunHandle(
    val runId: AgentRunId,
    val descriptorId: AgentDescriptorId,
)

data class AgentRunSnapshot(
    val runId: AgentRunId,
    val parentRunId: AgentRunId?,
    val descriptorId: AgentDescriptorId,
    val status: RunStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    /**
     * The handler's artifact once the run completes; null for
     * failed/cancelled/paused runs. In-process only (a reference, not a
     * persisted value) — durable artifact delivery rides the event stream.
     * Awaiting pattern: `observe(runId).first { it.status.isTerminal }`.
     */
    val artifact: AgentArtifact? = null,
    /**
     * The failure/cancellation cause, in-process only (never persisted).
     * Lets launchers keep their own error classification (retry policies,
     * user-facing mapping) when awaiting a terminal snapshot instead of
     * calling the handler directly.
     */
    val error: Throwable? = null,
)
