package app.amber.core.agent.runtime

import kotlinx.coroutines.flow.StateFlow

interface AgentRunner {
    fun <I : AgentInput> launch(
        descriptorId: AgentDescriptorId,
        input: I,
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
    val status: AgentRunStatus,
    val startedAt: Long,
    val finishedAt: Long?,
)

enum class AgentRunStatus {
    RUNNING,
    AWAITING_PERMISSION,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    CANCELLED,
}
