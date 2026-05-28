package app.amber.feature.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskRecoveryManagerTest {
    private val manager = AgentTaskRecoveryManager { snapshot ->
        snapshot.outputRef?.exists == true || snapshot.outputPath == "exists"
    }

    @Test
    fun runningTerminalBecomesInterruptedAfterRestart() {
        val recovered = manager.recoverOnStartup(
            snapshot(
                type = "terminal",
                status = AgentTaskStatus.RUNNING,
                outputRef = AgentTaskOutputRef(type = "terminal_log", path = "/tmp/job.log", exists = true),
            ),
            nowMs = 2_000L,
        )

        assertEquals(AgentTaskStatus.INTERRUPTED, recovered.status)
        assertEquals(AgentTaskQueueState.TERMINAL, recovered.queueState)
        assertEquals(AgentTaskRecoveryState.OUTPUT_ONLY, recovered.recoveryState)
        assertEquals("interrupted_by_restart", recovered.lastErrorCode)
        assertTrue(recovered.error!!.contains("interrupted"))
    }

    @Test
    fun cronPlanStaysScheduledAfterRestart() {
        val recovered = manager.recoverOnStartup(
            snapshot(type = "cron", status = AgentTaskStatus.RUNNING),
            nowMs = 2_000L,
        )

        assertEquals(AgentTaskStatus.QUEUED, recovered.status)
        assertEquals(AgentTaskQueueState.SCHEDULED, recovered.queueState)
        assertEquals(AgentTaskRecoveryState.SCHEDULED, recovered.recoveryState)
        assertEquals(2_000L, recovered.lastHeartbeatMs)
    }

    @Test
    fun failedRetryableTaskBecomesRetryable() {
        val recovered = manager.recoverOnStartup(
            snapshot(
                type = "subagent",
                status = AgentTaskStatus.FAILED,
                retryPolicy = AgentTaskRetryPolicy(retryable = true, maxRetries = 1, reason = "safe to rerun"),
            ),
            nowMs = 2_000L,
        )

        assertEquals(AgentTaskRecoveryState.RETRYABLE, recovered.recoveryState)
        assertTrue(recovered.retryPolicy.retryable)
    }

    @Test
    fun completedTaskWithOutputBecomesOutputOnly() {
        val recovered = manager.recoverOnStartup(
            snapshot(
                type = "officepro",
                status = AgentTaskStatus.COMPLETED,
                outputRef = AgentTaskOutputRef(type = "report", path = "/tmp/report.md", exists = true),
            ),
            nowMs = 2_000L,
        )

        assertEquals(AgentTaskStatus.COMPLETED, recovered.status)
        assertEquals(AgentTaskRecoveryState.OUTPUT_ONLY, recovered.recoveryState)
        assertEquals(2_000L, recovered.lastHeartbeatMs)
    }

    private fun snapshot(
        type: String,
        status: AgentTaskStatus,
        retryPolicy: AgentTaskRetryPolicy = AgentTaskRetryPolicy(),
        outputRef: AgentTaskOutputRef? = null,
    ) = AgentTaskSnapshot(
        taskId = "task-$type",
        type = type,
        title = "$type task",
        status = status,
        retryPolicy = retryPolicy,
        outputRef = outputRef,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )
}
