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

enum class AgentRunStatus(
    val wireName: String,
    val isTerminal: Boolean,
    val isRecoverable: Boolean,
) {
    RUNNING("running", isTerminal = false, isRecoverable = true),
    AWAITING_PERMISSION("awaiting_permission", isTerminal = false, isRecoverable = true),
    RECOVERY_PENDING("recovery_pending", isTerminal = false, isRecoverable = true),
    COMPLETED("completed", isTerminal = true, isRecoverable = false),
    FAILED("failed", isTerminal = true, isRecoverable = false),
    INTERRUPTED("interrupted", isTerminal = true, isRecoverable = false),
    CANCELLED("cancelled", isTerminal = true, isRecoverable = false),
    ;

    fun canTransitionTo(next: AgentRunStatus): Boolean = when (this) {
        RUNNING -> next in setOf(
            AWAITING_PERMISSION,
            RECOVERY_PENDING,
            COMPLETED,
            FAILED,
            INTERRUPTED,
            CANCELLED,
        )
        AWAITING_PERMISSION -> next in setOf(
            RUNNING,
            RECOVERY_PENDING,
            FAILED,
            INTERRUPTED,
            CANCELLED,
        )
        RECOVERY_PENDING -> next in setOf(
            RUNNING,
            FAILED,
            INTERRUPTED,
            CANCELLED,
        )
        COMPLETED,
        FAILED,
        INTERRUPTED,
        CANCELLED,
        -> false
    }

    companion object {
        /**
         * Decodes persisted wire values. Historical domain-specific terminal
         * values are failed runs; unknown values also fail closed instead of
         * being presented as an interrupted/recoverable run.
         */
        fun fromWireName(value: String): AgentRunStatus = when (value.lowercase()) {
            RUNNING.wireName -> RUNNING
            AWAITING_PERMISSION.wireName -> AWAITING_PERMISSION
            RECOVERY_PENDING.wireName -> RECOVERY_PENDING
            COMPLETED.wireName -> COMPLETED
            FAILED.wireName, "truncated", "guard_stopped" -> FAILED
            INTERRUPTED.wireName -> INTERRUPTED
            CANCELLED.wireName -> CANCELLED
            else -> FAILED
        }
    }
}
