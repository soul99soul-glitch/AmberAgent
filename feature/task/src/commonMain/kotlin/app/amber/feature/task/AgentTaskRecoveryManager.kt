package app.amber.feature.task

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

class AgentTaskRecoveryManager(
    private val outputExists: (AgentTaskSnapshot) -> Boolean = { snapshot ->
        snapshot.outputPath?.let { TaskFile(it).exists() }
            ?: snapshot.outputRef?.path?.let { TaskFile(it).exists() }
            ?: false
    },
) {
    fun recoverOnStartup(snapshot: AgentTaskSnapshot, nowMs: Long = Clock.System.now().toEpochMilliseconds()): AgentTaskSnapshot {
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
        // Retry callbacks are executable process state and never survive a
        // restart. A producer may opt back in only by re-registering a live
        // adapter with AgentTaskStore.
        val recoveredSnapshot = if (snapshot.retryPolicy.retryable) {
            snapshot.copy(
                retryPolicy = snapshot.retryPolicy.copy(
                    retryable = false,
                    maxRetries = 0,
                    reason = null,
                ),
            )
        } else {
            snapshot
        }
        val cronEnabled = runCatching {
            recoveredSnapshot.spec
                ?.get("enabled")
                ?.jsonPrimitive
                ?.booleanOrNull
        }.getOrNull()
        return when {
            recoveredSnapshot.type == "cron" &&
                (cronEnabled == false || recoveredSnapshot.status == AgentTaskStatus.CANCELLED) -> recoveredSnapshot.copy(
                status = AgentTaskStatus.CANCELLED,
                queueState = AgentTaskQueueState.TERMINAL,
                recoveryState = AgentTaskRecoveryState.CLEANUP_ONLY,
                cancelCapability = false,
                outputRef = outputRef,
                lastHeartbeatMs = nowMs,
            )

            recoveredSnapshot.type == "cron" -> recoveredSnapshot.copy(
                status = AgentTaskStatus.QUEUED,
                queueState = AgentTaskQueueState.SCHEDULED,
                recoveryState = AgentTaskRecoveryState.SCHEDULED,
                cancelCapability = false,
                outputRef = outputRef,
                lastHeartbeatMs = nowMs,
            )

            recoveredSnapshot.status.running -> recoveredSnapshot.copy(
                status = AgentTaskStatus.INTERRUPTED,
                queueState = AgentTaskQueueState.TERMINAL,
                recoveryState = if (outputExists) AgentTaskRecoveryState.OUTPUT_ONLY else AgentTaskRecoveryState.INTERRUPTED,
                updatedAtMs = nowMs,
                error = "Task was interrupted because AmberAgent restarted.",
                lastErrorCode = "interrupted_by_restart",
                cancelCapability = false,
                outputRef = outputRef,
            )

            recoveredSnapshot.status == AgentTaskStatus.COMPLETED && outputExists -> recoveredSnapshot.copy(
                recoveryState = AgentTaskRecoveryState.OUTPUT_ONLY,
                cancelCapability = false,
                outputRef = outputRef,
                lastHeartbeatMs = nowMs,
            )

            else -> recoveredSnapshot.copy(
                recoveryState = AgentTaskRecoveryState.CLEANUP_ONLY,
                cancelCapability = false,
                outputRef = outputRef,
            )
        }
    }
}
