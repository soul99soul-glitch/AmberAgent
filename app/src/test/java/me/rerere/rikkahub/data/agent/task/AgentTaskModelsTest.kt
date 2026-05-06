package me.rerere.rikkahub.data.agent.task

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTaskModelsTest {
    @Test
    fun statusMapsToQueueState() {
        assertEquals(AgentTaskQueueState.QUEUED, AgentTaskStatus.QUEUED.toQueueState("terminal"))
        assertEquals(AgentTaskQueueState.SCHEDULED, AgentTaskStatus.QUEUED.toQueueState("cron"))
        assertEquals(AgentTaskQueueState.ACTIVE, AgentTaskStatus.RUNNING.toQueueState("subagent"))
        assertEquals(AgentTaskQueueState.TERMINAL, AgentTaskStatus.COMPLETED.toQueueState("terminal"))
        assertEquals(AgentTaskQueueState.TERMINAL, AgentTaskStatus.INTERRUPTED.toQueueState("model_council"))
    }

    @Test
    fun statusMapsToRecoveryState() {
        assertEquals(
            AgentTaskRecoveryState.SCHEDULED,
            AgentTaskStatus.QUEUED.toRecoveryState("cron", AgentTaskRetryPolicy()),
        )
        assertEquals(
            AgentTaskRecoveryState.ACTIVE,
            AgentTaskStatus.RUNNING.toRecoveryState("terminal", AgentTaskRetryPolicy()),
        )
        assertEquals(
            AgentTaskRecoveryState.RETRYABLE,
            AgentTaskStatus.INTERRUPTED.toRecoveryState("subagent", AgentTaskRetryPolicy(retryable = true)),
        )
        assertEquals(
            AgentTaskRecoveryState.OUTPUT_ONLY,
            AgentTaskStatus.COMPLETED.toRecoveryState("officepro", AgentTaskRetryPolicy()),
        )
    }
}
