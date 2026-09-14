package app.amber.feature.ui.components.ai

import app.amber.feature.subagent.SubAgentDefinition
import app.amber.feature.subagent.SubAgentResult
import app.amber.feature.subagent.SubAgentRun
import app.amber.feature.subagent.ThreadGraphManager
import app.amber.feature.subagent.SubAgentRunStatus
import app.amber.feature.subagent.SubAgentTaskSpec
import app.amber.ai.ui.UIMessagePart
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
            mapOf(summaryWrite.taskId to completedLive.copy(updatedAtMs = 880L)),
        ).tasks.single()
        assertEquals(135L, stable.finishedAtMs)
    }

    @Test
    fun currentProcessTerminalSurvivesAConflatedActiveEmission() {
        val tracker = SubAgentDockTracker()
        val terminal = snapshot(
            status = AgentTaskStatus.COMPLETED,
            createdAtMs = 300L,
            updatedAtMs = 340L,
        )
        val key = SubAgentDockRunKey(terminal.taskId, terminal.createdAtMs)

        // The global StateFlow can publish only this terminal value when a local failure finishes
        // between register and the Main collector's first reduction. The state holder admits a
        // key absent from its startup baseline as current-process work.
        val visible = tracker.reduce(
            snapshots = listOf(terminal),
            liveRuns = emptyMap(),
            processRunKeys = setOf(key),
        ).tasks.single()

        assertEquals(SubAgentDockStatus.COMPLETED, visible.status)
        assertEquals(340L, visible.finishedAtMs)
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
    fun dismissAllHidesActiveRunsWithoutCancellingAndNewGenerationReappears() {
        val tracker = SubAgentDockTracker()
        val firstRunning = snapshot(status = AgentTaskStatus.RUNNING, createdAtMs = 100L, updatedAtMs = 100L)

        assertEquals(
            SubAgentDockStatus.RUNNING,
            tracker.reduce(listOf(firstRunning), emptyMap()).tasks.single().status,
        )
        tracker.dismissAll()

        assertTrue(tracker.reduce(listOf(firstRunning), emptyMap()).tasks.isEmpty())
        // This is presentation-only state: the task store still describes the run as active.
        assertEquals(AgentTaskStatus.RUNNING, firstRunning.status)

        val followupRunning = firstRunning.copy(createdAtMs = 300L, updatedAtMs = 300L)
        assertEquals(
            SubAgentDockStatus.RUNNING,
            tracker.reduce(listOf(followupRunning), emptyMap()).tasks.single().status,
        )
    }

    @Test
    fun terminalAutoHideUsesFirstFinishDeadlineAndKeepsAnOpenDetailsRow() {
        val tracker = SubAgentDockTracker()
        val running = snapshot(status = AgentTaskStatus.RUNNING, createdAtMs = 100L, updatedAtMs = 100L)
        tracker.reduce(listOf(running), emptyMap())
        val terminal = tracker.reduce(
            listOf(running.copy(status = AgentTaskStatus.COMPLETED, updatedAtMs = 200L)),
            emptyMap(),
        ).tasks.single()

        assertFalse(tracker.dismissExpired(nowMs = 999L, autoHideAfterMs = 0L))
        assertFalse(tracker.dismissExpired(nowMs = 229L, autoHideAfterMs = 30L))
        assertFalse(
            tracker.dismissExpired(
                nowMs = 230L,
                autoHideAfterMs = 30L,
                protectedKey = terminal.key,
            )
        )
        assertTrue(tracker.dismissExpired(nowMs = 230L, autoHideAfterMs = 30L))
        assertTrue(tracker.reduce(listOf(running.copy(status = AgentTaskStatus.COMPLETED, updatedAtMs = 999L)), emptyMap()).tasks.isEmpty())
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

    @Test
    fun persistedApprovalProjectsOnlyOntoTheMatchingRecoveredGeneration() {
        val tracker = SubAgentDockTracker()
        val recovered = snapshot(status = AgentTaskStatus.INTERRUPTED, createdAtMs = 100L, updatedAtMs = 140L)
        val key = SubAgentDockRunKey(recovered.taskId, recovered.createdAtMs)
        val persistedApproval = mapOf(key to SubAgentRunStatus.APPROVAL_REQUIRED)

        assertEquals(
            SubAgentDockStatus.APPROVAL_REQUIRED,
            tracker.reduce(
                snapshots = listOf(recovered),
                liveRuns = emptyMap(),
                persistedStatuses = persistedApproval,
                processRunKeys = setOf(key),
            ).tasks.single().status,
        )

        val replacement = recovered.copy(createdAtMs = 200L, updatedAtMs = 240L)
        assertEquals(
            SubAgentDockStatus.INTERRUPTED,
            tracker.reduce(
                snapshots = listOf(replacement),
                liveRuns = emptyMap(),
                persistedStatuses = persistedApproval,
                processRunKeys = setOf(replacement.toDockKeyForTest()),
            ).tasks.single().status,
        )

        val details = buildDockDetails(
            snapshot = recovered.copy(summary = "approval objective"),
            liveRun = null,
            liveText = "",
            liveParts = emptyList(),
            persistedState = ThreadGraphManager.ThreadGraphState(
                status = SubAgentRunStatus.APPROVAL_REQUIRED,
                startedAtMs = 100L,
                updatedAtMs = 140L,
            ),
            followupSeed = null,
            transcript = TranscriptDockDetails(),
        )
        assertEquals("approval objective", details.objective)

        val cancelledDetails = buildDockDetails(
            snapshot = recovered.copy(status = AgentTaskStatus.CANCELLED, summary = "cancelled objective"),
            liveRun = null,
            liveText = "",
            liveParts = emptyList(),
            persistedState = ThreadGraphManager.ThreadGraphState(
                status = SubAgentRunStatus.APPROVAL_REQUIRED,
                startedAtMs = 100L,
                updatedAtMs = 140L,
            ),
            followupSeed = null,
            transcript = TranscriptDockDetails(),
        )
        assertEquals(null, cancelledDetails.objective)
    }

    @Test
    fun runningSnapshotSummaryIsExposedOnlyAsObjective() {
        val running = snapshot(
            status = AgentTaskStatus.RUNNING,
            createdAtMs = 500L,
            updatedAtMs = 500L,
        ).copy(summary = "original objective")

        val details = buildDockDetails(
            snapshot = running,
            liveRun = null,
            liveText = "",
            liveParts = emptyList(),
            persistedState = null,
            followupSeed = null,
            transcript = TranscriptDockDetails(),
        )

        assertEquals("original objective", details.objective)
        assertEquals(null, details.summary)
    }

    @Test
    fun livePartsProduceOnlyRealToolReasoningAndAssistantExcerpts() {
        val stages = extractLiveStages(
            parts = listOf(
                UIMessagePart.Reasoning("Inspecting the relevant files", finishedAt = null),
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "file_read",
                    input = "logs.txt",
                    output = listOf(UIMessagePart.Text("found the failure")),
                ),
                UIMessagePart.Text("The failure is in the retry path."),
            ),
            isRunActive = true,
        )

        assertEquals(3, stages.size)
        assertEquals(SubAgentDockStageKind.REASONING, stages[0].kind)
        assertEquals(SubAgentDockStageKind.TOOL, stages[1].kind)
        assertEquals("file_read", stages[1].title)
        assertEquals(SubAgentDockStageKind.TEXT, stages[2].kind)
        assertTrue(stages[0].isRunning)
    }

    @Test
    fun consecutiveToolsDoNotEvictEarlierReadableProgress() {
        val details = buildDockDetails(
            snapshot = snapshot(status = AgentTaskStatus.RUNNING, createdAtMs = 100L, updatedAtMs = 200L),
            liveRun = null, liveText = "", persistedState = null, followupSeed = null,
            transcript = TranscriptDockDetails(),
            liveParts = listOf(UIMessagePart.Text("已确认重复请求来自重试逻辑。")) + List(6) { index ->
                UIMessagePart.Tool(toolCallId = "tool-$index", toolName = "file_read", input = "{}", output = emptyList())
            },
        )
        assertEquals(listOf("已确认重复请求来自重试逻辑。"), details.readableOverview().stages.map { it.text })
    }

    @Test
    fun oldGenerationKeyIsRejectedAndFollowupSeedStaysPreviousOutput() {
        val current = snapshot(
            status = AgentTaskStatus.RUNNING,
            createdAtMs = 700L,
            updatedAtMs = 700L,
        )
        assertFalse(current.matchesDockRunKey(SubAgentDockRunKey(current.taskId, 699L)))

        val details = buildDockDetails(
            snapshot = current,
            liveRun = liveRun(
                taskId = current.taskId,
                status = SubAgentRunStatus.RUNNING,
                updatedAtMs = 700L,
            ).copy(displayText = "previous generation answer"),
            liveText = "",
            liveParts = emptyList(),
            persistedState = null,
            followupSeed = "previous generation answer",
            transcript = TranscriptDockDetails(),
        )

        assertEquals("previous generation answer", details.previousOutput)
        assertEquals("", details.output)
    }

    @Test
    fun terminalResultKeepsStructuredRiskAndNextStepSemantics() {
        val terminal = snapshot(
            status = AgentTaskStatus.COMPLETED,
            createdAtMs = 900L,
            updatedAtMs = 950L,
        )
        val details = buildDockDetails(
            snapshot = terminal,
            liveRun = liveRun(
                taskId = terminal.taskId,
                status = SubAgentRunStatus.COMPLETED,
                updatedAtMs = 950L,
            ).copy(
                result = SubAgentResult(
                    status = SubAgentRunStatus.COMPLETED,
                    summary = "done",
                    findings = listOf("finding"),
                    evidence = listOf("evidence"),
                    risks = listOf("risk"),
                    recommendedNextSteps = listOf("next"),
                ),
            ),
            liveText = "",
            liveParts = emptyList(),
            persistedState = null,
            followupSeed = null,
            transcript = TranscriptDockDetails(),
        )

        assertTrue(details.output.contains("risk"))
        assertTrue(details.stages.any { it.kind == SubAgentDockStageKind.FINDING })
        assertTrue(details.stages.any { it.kind == SubAgentDockStageKind.EVIDENCE })
        assertTrue(details.stages.any { it.kind == SubAgentDockStageKind.RISK })
        assertTrue(details.stages.any { it.kind == SubAgentDockStageKind.NEXT_STEP })
    }

    @Test
    fun structuredFollowupOutputKeepsPreviousAnswerSeparate() {
        val terminal = snapshot(status = AgentTaskStatus.COMPLETED, createdAtMs = 900L, updatedAtMs = 950L)
        val details = buildDockDetails(
            snapshot = terminal,
            liveRun = liveRun(
                taskId = terminal.taskId,
                status = SubAgentRunStatus.COMPLETED,
                updatedAtMs = 950L,
            ).copy(
                displayText = "",
                result = SubAgentResult(
                    status = SubAgentRunStatus.COMPLETED,
                    summary = "new structured result",
                ),
            ),
            liveText = "",
            liveParts = emptyList(),
            persistedState = null,
            followupSeed = "previous answer",
            transcript = TranscriptDockDetails(previousOutput = "previous answer"),
        )

        assertTrue(details.output.contains("new structured result"))
        assertEquals("previous answer", details.previousOutput)
    }

    @Test
    fun aRecoveredFollowupDoesNotShowThePreviousPersistedAnswerAsItsOwn() {
        val terminal = snapshot(status = AgentTaskStatus.INTERRUPTED, createdAtMs = 700L, updatedAtMs = 900L)
        val persisted = ThreadGraphManager.ThreadGraphState(
            status = SubAgentRunStatus.INTERRUPTED,
            startedAtMs = 100L,
            updatedAtMs = 900L,
            finalAnswer = "previous answer",
            resultFinishedAtMs = 600L,
        )
        fun details(state: ThreadGraphManager.ThreadGraphState) = buildDockDetails(
            snapshot = terminal, liveRun = null, liveText = "", liveParts = emptyList(),
            persistedState = state, followupSeed = null, transcript = TranscriptDockDetails(),
        )
        assertEquals("", details(persisted).output)
        assertEquals("current answer", details(persisted.copy(
            finalAnswer = "current answer", resultFinishedAtMs = 800L,
        )).output)
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

    private fun AgentTaskSnapshot.toDockKeyForTest() = SubAgentDockRunKey(taskId, createdAtMs)

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
