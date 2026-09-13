package app.amber.core.service

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.RoomRunTerminalStore
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.terminalForFlowEnd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatGenerationFinalizationTest : DurableRuntimeTestBase() {
    @Test
    fun `auto resume accepts only its live waiting user run`() = runBlocking {
        val conversationId = Uuid.random()
        val expected = AgentRunId("run_waiting")
        runTerminalStore.begin(expected.value, conversationId.toString(), null)
        runTerminalStore.pause(expected.value, RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)

        assertTrue(
            isExpectedWaitingUserRun(
                runTerminalStore.activeForConversation(conversationId.toString()),
                conversationId,
                expected,
            ),
        )
        assertFalse(
            isExpectedWaitingUserRun(
                runTerminalStore.activeForConversation(conversationId.toString()),
                conversationId,
                AgentRunId("different_run"),
            ),
        )

        assertTrue(runTerminalStore.cancelWaitingUser(expected.value, conversationId.toString()))
        assertFalse(
            isExpectedWaitingUserRun(
                runTerminalStore.get(expected.value),
                conversationId,
                expected,
            ),
        )
    }

    @Test
    fun `generation sanitizer preserves a pending approval checkpoint`() {
        val pending = UIMessagePart.Tool(
            toolCallId = "call_waiting",
            toolName = "terminal_execute",
            input = "{\"command\":\"pwd\"}",
            approvalState = ToolApprovalState.Pending,
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(pending),
                        ),
                    ),
                ),
            ),
        )

        val sanitized = sanitizeInvalidMessagesForGeneration(conversation)

        val preserved = sanitized.messageNodes.single().currentMessage.getTools().single()
        assertTrue(preserved.isPending)
        assertFalse(preserved.isExecuted)
    }

    @Test
    fun cancelledGenerationStillPersistsItsTerminal() = runBlocking {
        runTerminalStore.begin("run_cancelled", "conversation", null)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            runCatching {
                flow<Unit> {
                    started.complete(Unit)
                    awaitCancellation()
                }.onCompletion { cause ->
                    finalizeChatGeneration {
                        val (state, reason) = terminalForFlowEnd(cause, null)
                        runTerminalStore.finish("run_cancelled", state, reason)
                    }
                }.collect()
            }
        }
        started.await()
        job.cancelAndJoin()

        val persisted = runTerminalStore.get("run_cancelled")!!
        assertEquals(RunTerminalState.CANCELLED, persisted.state)
        assertNotNull(persisted.finishedAtMs)
    }

    @Test
    fun cancellationCleanupStillRejectsPreRestoreWrites() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val store = RoomRunTerminalStore(database.runTerminalDao(), restoreWriteGate = gate)
        store.begin("run_restored", "conversation", null)
        val started = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        val job = launch(SyncRestoreWriteEpoch(gate.currentEpoch())) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                failure = runCatching {
                    finalizeChatGeneration {
                        store.finish("run_restored", RunTerminalState.CANCELLED, PauseReason.USER_STOP)
                    }
                }.exceptionOrNull()
            }
        }
        started.await()
        gate.withRestore { }
        job.cancelAndJoin()

        assertTrue(failure is SyncRestoreWriteRejectedException)
        assertEquals(RunTerminalState.RUNNING, store.get("run_restored")!!.state)
    }
}
