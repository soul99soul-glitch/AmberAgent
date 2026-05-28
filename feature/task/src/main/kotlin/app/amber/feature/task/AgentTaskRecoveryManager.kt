package app.amber.feature.task

import java.io.File

class AgentTaskRecoveryManager(
    private val outputExists: (AgentTaskSnapshot) -> Boolean = { snapshot ->
        snapshot.outputPath?.let { File(it).exists() } ?: snapshot.outputRef?.path?.let { File(it).exists() } ?: false
    },
) {
    fun recoverOnStartup(snapshot: AgentTaskSnapshot, nowMs: Long = System.currentTimeMillis()): AgentTaskSnapshot {
        val outputExists = outputExists(snapshot)
        val outputRef = snapshot.outputRef?.copy(exists = outputExists)
            ?: snapshot.outputPath?.let {
                AgentTaskOutputRef(
                    type = if (snapshot.type == "terminal") "terminal_log" else "file",
                    path = it,
                    tailOffset = snapshot.outputOffset,
                    exists = outputExists,
                )
            }
        return when {
            snapshot.type == "cron" -> snapshot.copy(
                status = AgentTaskStatus.QUEUED,
                queueState = AgentTaskQueueState.SCHEDULED,
                recoveryState = AgentTaskRecoveryState.SCHEDULED,
                cancelCapability = false,
                outputRef = outputRef,
                lastHeartbeatMs = nowMs,
            )

            snapshot.status.running -> snapshot.copy(
                status = AgentTaskStatus.INTERRUPTED,
                queueState = AgentTaskQueueState.TERMINAL,
                recoveryState = if (outputExists) AgentTaskRecoveryState.OUTPUT_ONLY else AgentTaskRecoveryState.INTERRUPTED,
                updatedAtMs = nowMs,
                error = "Task was interrupted because AmberAgent restarted.",
                lastErrorCode = "interrupted_by_restart",
                cancelCapability = false,
                outputRef = outputRef,
            )

            snapshot.status == AgentTaskStatus.COMPLETED && outputExists -> snapshot.copy(
                recoveryState = AgentTaskRecoveryState.OUTPUT_ONLY,
                cancelCapability = false,
                outputRef = outputRef,
                lastHeartbeatMs = nowMs,
            )

            snapshot.status in setOf(AgentTaskStatus.FAILED, AgentTaskStatus.INTERRUPTED, AgentTaskStatus.TIMED_OUT) &&
                snapshot.retryPolicy.retryable -> snapshot.copy(
                    recoveryState = AgentTaskRecoveryState.RETRYABLE,
                    cancelCapability = false,
                    outputRef = outputRef,
                )

            else -> snapshot.copy(
                recoveryState = AgentTaskRecoveryState.CLEANUP_ONLY,
                cancelCapability = false,
                outputRef = outputRef,
            )
        }
    }
}

interface TaskRecoveryAdapter {
    val type: String
    fun canRetry(snapshot: AgentTaskSnapshot): Boolean
}
