package app.amber.feature.ui.components.ai

import app.amber.feature.subagent.SubAgentDefinition
import app.amber.feature.subagent.SubAgentRun
import app.amber.feature.subagent.SubAgentRunStatus
import app.amber.feature.subagent.SubAgentTaskSpec
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentDockStateTest {
    private val sourceConversation = Uuid.parse("11111111-1111-1111-1111-111111111111")

    @Test
    fun activeTaskFromAnotherConversationIsRetainedThroughItsTerminalSnapshot() {
        val tracker = SubAgentDockTracker()
        val running = snapshot(status = AgentTaskStatus.RUNNING, createdAtMs = 100L, updatedAtMs = 100L)

        val active = tracker.reduce(listOf(running), emptyMap()).tasks.single()
        assertEquals(sourceConversation, active.sourceConversationId)
        assertEquals(SubAgentDockStatus.RUNNING, active.status)

        val completed = running.copy(status = AgentTaskStatus.COMPLETED, updatedAtMs = 140L)
        val completedLive = liveRun(
            taskId = running.taskId,
            status = SubAgentRunStatus.COMPLETED,
            updatedAtMs = 135L,
        )
        val terminal = tracker.reduce(
            listOf(completed),
            mapOf(completed.taskId to completedLive),
        ).tasks.single()
        assertEquals(SubAgentDockStatus.COMPLETED, terminal.status)
        assertEquals(135L, terminal.finishedAtMs)
        assertEquals(35L, terminal.elapsedAt(nowMs = 9_999L))

        // A later summary write may advance the task snapshot timestamp, but it must not make
        // the already-finished elapsed time grow.
        val summaryWrite = completed.copy(updatedAtMs = 880L)
        val stable = tracker.reduce(
            listOf(summaryWrite),
            mapOf(summaryWrite.taskId to completedLive),
        ).tasks.single()
        assertEquals(135L, stable.finishedAtMs)
    }

    @Test
    fun dismissDoesNotMutateTaskAndReusedTaskIdWithNewGenerationReappears() {
        val tracker = SubAgentDockTracker()
        val firstRunning = snapshot(status = AgentTaskStatus.RUNNING, createdAtMs = 100L, updatedAtMs = 100L)
        val firstCompleted = firstRunning.copy(status = AgentTaskStatus.COMPLETED, updatedAtMs = 160L)

        val terminal = tracker.reduce(listOf(firstRunning), emptyMap()).tasks.single()
        tracker.reduce(listOf(firstCompleted), emptyMap())
        tracker.dismiss(terminal.key)

        assertTrue(tracker.reduce(listOf(firstCompleted), emptyMap()).tasks.isEmpty())
        // The reducer owns only UI visibility; the snapshot passed by the task store remains intact.
        assertEquals(AgentTaskStatus.COMPLETED, firstCompleted.status)
        assertEquals(160L, firstCompleted.updatedAtMs)

        val followupRunning = firstCompleted.copy(
            status = AgentTaskStatus.RUNNING,
            createdAtMs = 300L,
            updatedAtMs = 300L,
        )
        val reappeared = tracker.reduce(listOf(followupRunning), emptyMap()).tasks.single()
        assertEquals(SubAgentDockRunKey(followupRunning.taskId, 300L), reappeared.key)
        assertEquals(SubAgentDockStatus.RUNNING, reappeared.status)
    }

    @Test
    fun recoveryDoesNotInventRunningAndMatchingApprovalOverridesOnlyAnActiveSnapshot() {
        val tracker = SubAgentDockTracker()
        val recovered = snapshot(status = AgentTaskStatus.INTERRUPTED, createdAtMs = 100L, updatedAtMs = 140L)

        // AgentTaskStore startup recovery already made this terminal. The dock never turns it
        // back into RUNNING merely because the in-memory manager has no record after a restart.
        assertTrue(tracker.reduce(listOf(recovered), emptyMap()).tasks.isEmpty())

        val active = recovered.copy(
            status = AgentTaskStatus.RUNNING,
            createdAtMs = 200L,
            updatedAtMs = 200L,
        )
        // A newly registered task remains visible even during the short window before the
        // manager exposes its in-memory flow.
        assertEquals(
            SubAgentDockStatus.RUNNING,
            tracker.reduce(listOf(active), emptyMap()).tasks.single().status,
        )

        val priorGeneration = liveRun(
            taskId = active.taskId,
            status = SubAgentRunStatus.FAILED,
            updatedAtMs = 199L,
        )
        assertEquals(
            "a previous same-thread generation cannot override the newer task snapshot",
            SubAgentDockStatus.RUNNING,
            tracker.reduce(listOf(active), mapOf(active.taskId to priorGeneration)).tasks.single().status,
        )

        val approvalRun = liveRun(
            taskId = active.taskId,
            status = SubAgentRunStatus.APPROVAL_REQUIRED,
            updatedAtMs = 220L,
        )
        val approval = tracker.reduce(
            listOf(active),
            mapOf(active.taskId to approvalRun),
        ).tasks.single()
        assertEquals(SubAgentDockStatus.APPROVAL_REQUIRED, approval.status)
        tracker.dismiss(approval.key)
        assertFalse(
            "approval wait is an active lifecycle state and cannot be hidden",
            tracker.reduce(listOf(active), mapOf(active.taskId to approvalRun)).tasks.isEmpty(),
        )

        val terminal = active.copy(status = AgentTaskStatus.INTERRUPTED, updatedAtMs = 260L)
        val staleRunning = approvalRun.copy(status = SubAgentRunStatus.RUNNING, updatedAtMs = 270L)
        val interrupted = tracker.reduce(
            listOf(terminal),
            mapOf(terminal.taskId to staleRunning),
        ).tasks.single()
        assertEquals(SubAgentDockStatus.INTERRUPTED, interrupted.status)
        assertEquals(260L, interrupted.finishedAtMs)
    }

    private fun snapshot(
        taskId: String = "thread-1",
        status: AgentTaskStatus,
        createdAtMs: Long,
        updatedAtMs: Long,
    ) = AgentTaskSnapshot(
        taskId = taskId,
        type = "subagent",
        title = "Researcher",
        sourceConversationId = sourceConversation.toString(),
        status = status,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

    private fun liveRun(
        taskId: String,
        status: SubAgentRunStatus,
        updatedAtMs: Long,
    ) = SubAgentRun(
        runId = taskId,
        parentConversationId = sourceConversation,
        definition = SubAgentDefinition(
            id = "researcher",
            name = "Researcher",
            description = "",
            systemPrompt = "",
            toolAllowlist = emptySet(),
        ),
        task = SubAgentTaskSpec(
            objective = "",
            outputFormat = "",
            toolsAndSources = "",
            boundaries = "",
        ),
        status = status,
        transcriptPath = "/tmp/unused-subagent-dock-test.jsonl",
        startedAtMs = 100L,
        updatedAtMs = updatedAtMs,
    )
}
