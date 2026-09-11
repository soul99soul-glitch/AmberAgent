package app.amber.feature.runtime

import app.amber.core.ai.GenerationTerminal
import app.amber.agent.data.db.entity.RunTerminalEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-03 typed run terminal state machine.
 */
class RunTerminalStoreTest : DurableRuntimeTestBase() {

    @Test
    fun beginPauseResumeFinishLifecycle() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", "assistant_1")
        var run = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.RUNNING, run.state)
        assertFalse(run.state.isTerminal)

        runTerminalStore.pause("run_1", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
        run = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.WAITING_USER, run.state)
        assertEquals(PauseReason.TOOL_APPROVAL, run.pauseReason)
        // WAITING_USER is a pause — neither completed nor failed.
        assertFalse(run.state.isTerminal)
        assertNull(run.finishedAtMs)

        runTerminalStore.finish("run_1", RunTerminalState.COMPLETED)
        run = runTerminalStore.get("run_1")!!
        assertTrue(run.state.isTerminal)
        assertEquals(RunTerminalState.COMPLETED, run.state)
        assertNotNull(run.finishedAtMs)
    }

    @Test
    fun stepLimitIsTerminalAndNeverMapsToCompleted() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", null)
        runTerminalStore.finish("run_1", RunTerminalState.STEP_LIMIT, PauseReason.STEP_LIMIT_EXHAUSTED)
        var run = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.STEP_LIMIT, run.state)
        assertTrue(run.state.isTerminal)

        // A late COMPLETED must never overwrite STEP_LIMIT.
        runTerminalStore.finish("run_1", RunTerminalState.COMPLETED)
        run = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.STEP_LIMIT, run.state)
    }

    @Test
    fun outputLimitAndGuardStoppedAreTerminalAndNeverMapToCompleted() = runBlocking {
        runTerminalStore.begin("run_output", "conv_1", null)
        runTerminalStore.finish("run_output", RunTerminalState.OUTPUT_LIMIT, PauseReason.OUTPUT_LIMIT_REACHED)
        val outputRun = runTerminalStore.get("run_output")!!
        assertEquals(RunTerminalState.OUTPUT_LIMIT, outputRun.state)
        assertTrue(outputRun.state.isTerminal)
        assertEquals(PauseReason.OUTPUT_LIMIT_REACHED, outputRun.pauseReason)
        // A late COMPLETED must never overwrite OUTPUT_LIMIT.
        runTerminalStore.finish("run_output", RunTerminalState.COMPLETED)
        assertEquals(RunTerminalState.OUTPUT_LIMIT, runTerminalStore.get("run_output")!!.state)

        runTerminalStore.begin("run_guard", "conv_2", null)
        runTerminalStore.finish("run_guard", RunTerminalState.GUARD_STOPPED, PauseReason.DUPLICATE_TOOL_CALL)
        val guardRun = runTerminalStore.get("run_guard")!!
        assertEquals(RunTerminalState.GUARD_STOPPED, guardRun.state)
        assertTrue(guardRun.state.isTerminal)
        assertEquals(PauseReason.DUPLICATE_TOOL_CALL, guardRun.pauseReason)
        runTerminalStore.finish("run_guard", RunTerminalState.COMPLETED)
        assertEquals(RunTerminalState.GUARD_STOPPED, runTerminalStore.get("run_guard")!!.state)

        // Terminal runs are invisible to recovery's unfinished scan.
        assertEquals(emptyList<String>(), runTerminalStore.unfinished().map { it.runId })
    }

    @Test
    fun daoFinishIfLiveRefusesCompletedOverAnomalousOpenLimitRows() = runBlocking {
        // Defensive-depth pin at the DAO level. The store API always stamps
        // finished_at_ms on a terminal finish, so for store-written rows the
        // write-once guard alone already blocks any overwrite; the state
        // clause in finishIfLive only bites on an anomalous row — a limit
        // terminal whose finished_at_ms is still NULL. Inject exactly that
        // shape and require the COMPLETED refusal to come from the state
        // clause as well (mirrors the STEP_LIMIT store contract for
        // OUTPUT_LIMIT / GUARD_STOPPED).
        val dao = database.runTerminalDao()
        fun anomaly(runId: String, state: RunTerminalState, reason: PauseReason) = RunTerminalEntity(
            runId = runId,
            conversationId = "conv_1",
            assistantId = null,
            state = state.name,
            pauseReason = reason.name,
            startedAtMs = 1_000L,
            updatedAtMs = 1_000L,
            finishedAtMs = null,
        )
        dao.insertIgnore(anomaly("run_anom_output", RunTerminalState.OUTPUT_LIMIT, PauseReason.OUTPUT_LIMIT_REACHED))
        dao.insertIgnore(anomaly("run_anom_guard", RunTerminalState.GUARD_STOPPED, PauseReason.DUPLICATE_TOOL_CALL))

        assertEquals(0, dao.finishIfLive("run_anom_output", RunTerminalState.COMPLETED.name, null, 2_000L))
        assertEquals(0, dao.finishIfLive("run_anom_guard", RunTerminalState.COMPLETED.name, null, 2_000L))

        val untouched = dao.getByRunId("run_anom_output")!!
        assertEquals(RunTerminalState.OUTPUT_LIMIT, runCatching { RunTerminalState.valueOf(untouched.state) }.getOrDefault(RunTerminalState.INTERRUPTED))
        assertNull(untouched.finishedAtMs)
        assertEquals(1_000L, untouched.updatedAtMs)

        // Positive control: the state clause must not block a plain live row.
        dao.insertIgnore(anomaly("run_live", RunTerminalState.RUNNING, PauseReason.USER_STOP).copy(pauseReason = null))
        assertEquals(1, dao.finishIfLive("run_live", RunTerminalState.COMPLETED.name, null, 2_000L))
        assertEquals(
            RunTerminalState.COMPLETED,
            runCatching { RunTerminalState.valueOf(dao.getByRunId("run_live")!!.state) }.getOrDefault(RunTerminalState.INTERRUPTED),
        )
    }

    @Test
    fun terminalForFlowEndMapsNewKernelTerminals() {
        // A truncation settles OUTPUT_LIMIT, never COMPLETED.
        assertEquals(
            RunTerminalState.OUTPUT_LIMIT to PauseReason.OUTPUT_LIMIT_REACHED,
            terminalForFlowEnd(flowCause = null, reportedPause = GenerationTerminal.OutputLimit),
        )
        // A guard stop settles GUARD_STOPPED with its reason.
        assertEquals(
            RunTerminalState.GUARD_STOPPED to PauseReason.DUPLICATE_TOOL_CALL,
            terminalForFlowEnd(
                flowCause = null,
                reportedPause = GenerationTerminal.GuardStopped("duplicate_tool_call"),
            ),
        )
    }

    @Test
    fun terminalStateIsWriteOnce() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", null)
        runTerminalStore.finish("run_1", RunTerminalState.FAILED)
        // After a terminal, later transitions are refused.
        runTerminalStore.finish("run_1", RunTerminalState.COMPLETED)
        assertEquals(RunTerminalState.FAILED, runTerminalStore.get("run_1")!!.state)
    }

    @Test
    fun beginOnTerminalRowDoesNotReopenIt() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", null)
        runTerminalStore.finish("run_1", RunTerminalState.FAILED)
        val finished = runTerminalStore.get("run_1")!!

        // A later begin for the same runId must leave the terminal row byte-still.
        val later = RoomRunTerminalStore(
            dao = database.runTerminalDao(),
            now = { finished.updatedAtMs + 1_000 },
        )
        later.begin("run_1", "conv_1", "assistant_2")

        val after = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.FAILED, after.state)
        assertEquals(finished.finishedAtMs, after.finishedAtMs)
        assertEquals(finished.updatedAtMs, after.updatedAtMs)
        assertNull(after.assistantId)
    }

    @Test
    fun pauseAfterFinishDoesNotResurrectRow() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", null)
        runTerminalStore.finish("run_1", RunTerminalState.COMPLETED)
        val finished = runTerminalStore.get("run_1")!!

        runTerminalStore.pause("run_1", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)

        val after = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.COMPLETED, after.state)
        assertEquals(finished.finishedAtMs, after.finishedAtMs)
        assertEquals(finished.updatedAtMs, after.updatedAtMs)
        assertNull(after.pauseReason)
    }

    @Test
    fun beginResumesLivePausedRowAndClearsPauseMetadata() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", "assistant_1")
        runTerminalStore.pause("run_1", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
        val paused = runTerminalStore.get("run_1")!!

        runTerminalStore.begin("run_1", "conv_1", "assistant_1")

        val resumed = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.RUNNING, resumed.state)
        assertNull(resumed.pauseReason)
        assertNull(resumed.finishedAtMs)
        assertEquals(paused.startedAtMs, resumed.startedAtMs)
    }

    @Test
    fun outcomeUnknownIsAnUnfinishedPause() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", null)
        runTerminalStore.pause("run_1", RunTerminalState.OUTCOME_UNKNOWN, PauseReason.OUTCOME_UNKNOWN)

        val run = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.OUTCOME_UNKNOWN, run.state)
        assertNull(run.finishedAtMs)
        assertEquals(listOf("run_1"), runTerminalStore.unfinished().map { it.runId })
    }

    @Test
    fun persistedWaitingUserCanBeCancelledAfterOwnershipRelease() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", null)
        runTerminalStore.pause("run_1", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)

        assertTrue(runTerminalStore.cancelWaitingUser("run_1", "conv_1"))
        val cancelled = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.CANCELLED, cancelled.state)
        assertEquals(PauseReason.USER_STOP, cancelled.pauseReason)
        assertNotNull(cancelled.finishedAtMs)
        assertFalse(runTerminalStore.cancelWaitingUser("run_1", "conv_1"))
    }

    @Test
    fun persistedWaitingUserCancellationIsConversationScoped() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", null)
        runTerminalStore.pause("run_1", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)

        assertFalse(runTerminalStore.cancelWaitingUser("run_1", "conv_other"))
        assertEquals(RunTerminalState.WAITING_USER, runTerminalStore.get("run_1")!!.state)
    }

    @Test
    fun waitingUserRunSurvivesRestartAndResumesSameRunId() = runBlocking {
        // Approval pause, then process death.
        runTerminalStore.begin("run_1", "conv_1", "assistant_1")
        runTerminalStore.pause("run_1", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)

        // "Restart": a fresh store instance over the same persisted DB.
        val restarted = RoomRunTerminalStore(dao = database.runTerminalDao())
        val active = restarted.activeForConversation("conv_1")!!
        assertEquals("run_1", active.runId)
        assertEquals(RunTerminalState.WAITING_USER, active.state)

        // Approval continues the SAME run.
        restarted.begin(active.runId, "conv_1", "assistant_1")
        assertEquals(RunTerminalState.RUNNING, restarted.get("run_1")!!.state)
        restarted.finish("run_1", RunTerminalState.COMPLETED)
        assertEquals(RunTerminalState.COMPLETED, restarted.get("run_1")!!.state)
    }

    @Test
    fun activeForConversationReturnsNullAfterTerminal() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", null)
        runTerminalStore.finish("run_1", RunTerminalState.COMPLETED)
        assertNull(runTerminalStore.activeForConversation("conv_1"))
    }

    @Test
    fun terminalForFlowEndDecisions() {
        // STEP_LIMIT must never be reported as COMPLETED.
        assertEquals(
            RunTerminalState.STEP_LIMIT to PauseReason.STEP_LIMIT_EXHAUSTED,
            terminalForFlowEnd(flowCause = null, reportedPause = GenerationTerminal.StepLimit),
        )
        // WAITING_USER is a pause, not a completion.
        assertEquals(
            RunTerminalState.WAITING_USER to PauseReason.TOOL_APPROVAL,
            terminalForFlowEnd(flowCause = null, reportedPause = GenerationTerminal.WaitingUser),
        )
        // Clean end without a pause → COMPLETED (persisted by the caller).
        assertEquals(
            RunTerminalState.COMPLETED to null,
            terminalForFlowEnd(flowCause = null, reportedPause = null),
        )
        assertEquals(
            RunTerminalState.CANCELLED to PauseReason.USER_STOP,
            terminalForFlowEnd(flowCause = CancellationException("stop"), reportedPause = null),
        )
        assertEquals(
            RunTerminalState.FAILED to null,
            terminalForFlowEnd(flowCause = IllegalStateException("boom"), reportedPause = GenerationTerminal.StepLimit),
        )
    }

    @Test
    fun unfinishedListsOnlyNonTerminalRuns() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", null)
        runTerminalStore.begin("run_2", "conv_2", null)
        runTerminalStore.pause("run_2", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
        runTerminalStore.begin("run_3", "conv_3", null)
        runTerminalStore.finish("run_3", RunTerminalState.COMPLETED)

        val unfinished = runTerminalStore.unfinished().map { it.runId }.toSet()
        assertEquals(setOf("run_1", "run_2"), unfinished)
    }
}
