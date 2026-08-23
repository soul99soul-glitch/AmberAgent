package app.amber.feature.runtime

import app.amber.core.ai.GenerationTerminal
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
    fun terminalStateIsWriteOnce() = runBlocking {
        runTerminalStore.begin("run_1", "conv_1", null)
        runTerminalStore.finish("run_1", RunTerminalState.FAILED)
        // After a terminal, later transitions are refused.
        runTerminalStore.finish("run_1", RunTerminalState.COMPLETED)
        assertEquals(RunTerminalState.FAILED, runTerminalStore.get("run_1")!!.state)
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
