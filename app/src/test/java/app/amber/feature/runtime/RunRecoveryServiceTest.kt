package app.amber.feature.runtime

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.DEFAULT_ASSISTANT_ID
import app.amber.core.model.MessageNode
import app.amber.feature.tools.ToolEffectClass
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * P1-02 crash recovery by ledger state: interrupted non-idempotent tools are
 * never re-executed without confirmation, readOnly/idempotent tools stay
 * retryable, finished results are replayed, WAITING_USER survives restart.
 */
class RunRecoveryServiceTest : DurableRuntimeTestBase() {

    private fun recoveryService() = RunRecoveryService(
        ledger = ledger,
        runTerminalStore = runTerminalStore,
        conversationRepo = conversationRepository(),
        json = Json,
    )

    private suspend fun effect(
        runId: String,
        toolCallId: String = "call_1",
        effectClass: ToolEffectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE,
    ) = ledger.prepare(
        runId = runId,
        turnId = 0,
        toolCallId = toolCallId,
        toolName = "post_message",
        input = """{"text":"hello"}""",
        effectClass = effectClass,
    )

    private fun conversationWithTool(conversationId: Uuid, tool: UIMessagePart.Tool): Conversation =
        Conversation(
            id = conversationId,
            assistantId = DEFAULT_ASSISTANT_ID,
            messageNodes = listOf(
                MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("run a tool")))),
                MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))),
            ),
        )

    @Test
    fun nonIdempotentStartedEffectEscalatesToOutcomeUnknown() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val effect = effect("run_1")
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))

        recoveryService().recover()

        // Run died mid-run → the non-idempotent effect must NOT be silently
        // retried: the run escalates to OUTCOME_UNKNOWN and waits for the user.
        val run = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.OUTCOME_UNKNOWN, run.state)
        assertNull(run.finishedAtMs)
        assertEquals("run_1", runTerminalStore.unfinished().single().runId)
        assertEquals(ToolEffectStatus.OUTCOME_UNKNOWN, ledger.get(effect.effectId)!!.status)
        assertEquals("interrupted_mid_execution", ledger.get(effect.effectId)!!.errorCategory)
    }

    @Test
    fun readOnlyAndIdempotentStartedEffectsStayRetryable() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val readOnly = effect("run_1", toolCallId = "call_read", effectClass = ToolEffectClass.READ_ONLY)
        ledger.markStarted(readOnly.effectId, approvalDigest("run_1", "call_read", readOnly.argsDigest))
        val idempotent = effect("run_1", toolCallId = "call_write", effectClass = ToolEffectClass.IDEMPOTENT_WRITE)
        ledger.markStarted(idempotent.effectId, approvalDigest("run_1", "call_write", idempotent.argsDigest))

        recoveryService().recover()

        // Safe to retry: readOnly retries plainly, idempotentWrite retries
        // with its idempotency key — neither needs a user decision.
        assertEquals(ToolEffectStatus.STARTED, ledger.get(readOnly.effectId)!!.status)
        assertEquals(ToolEffectStatus.STARTED, ledger.get(idempotent.effectId)!!.status)
        assertEquals(RunTerminalState.INTERRUPTED, runTerminalStore.get("run_1")!!.state)
    }

    @Test
    fun preparedEffectIsLeftForApprovalReentry() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val effect = effect("run_1") // PREPARED — approval was still pending

        recoveryService().recover()

        assertEquals(ToolEffectStatus.PREPARED, ledger.get(effect.effectId)!!.status)
        assertEquals(RunTerminalState.INTERRUPTED, runTerminalStore.get("run_1")!!.state)
    }

    @Test
    fun finishedEffectResultIsReplayedIntoConversationWithoutReExecution() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val effect = effect("run_1")
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))
        ledger.finish(effect.effectId, listOf(UIMessagePart.Text("""{"status":"ok","data":42}""")))

        val repo = conversationRepository()
        val tool = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "post_message",
            input = """{"text":"hello"}""",
            approvalState = ToolApprovalState.Approved,
        )
        repo.insertConversation(conversationWithTool(conversationId, tool))

        recoveryService().recover()

        // The tool result was never written to the conversation (crash after
        // FINISHED, before message persist) — replay from the ledger.
        val replayed = repo.getConversationById(conversationId)!!
        val part = replayed.messageNodes
            .flatMap { it.messages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .first { it.toolCallId == "call_1" }
        assertTrue(part.isExecuted)
        val text = (part.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("\"status\":\"ok\""))
        assertEquals(ToolEffectStatus.FINISHED, ledger.get(effect.effectId)!!.status)
        // The replayed result is durable in the conversation — the ledger
        // replay payload is dropped (M1 retention).
        assertNull(ledger.get(effect.effectId)!!.resultPayload)
    }

    @Test
    fun waitingUserWithStartedEffectStaysPausedAndKeepsRunIdForReconcile() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        runTerminalStore.pause("run_1", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
        val effect = effect("run_1")
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))

        recoveryService().recover()

        // The stray STARTED non-idempotent effect escalates to OUTCOME_UNKNOWN,
        // but the run is PAUSED (never finish()ed), so the SAME runId is still
        // active and can be resumed after the user reconciles — no minting.
        assertEquals(ToolEffectStatus.OUTCOME_UNKNOWN, ledger.get(effect.effectId)!!.status)
        val run = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.OUTCOME_UNKNOWN, run.state)
        assertNull(run.finishedAtMs)
        val active = runTerminalStore.activeForConversation(conversationId.toString())!!
        assertEquals("run_1", active.runId)
        // User confirms retry → resume continues the same runId.
        runTerminalStore.begin(active.runId, conversationId.toString(), null)
        assertEquals(RunTerminalState.RUNNING, runTerminalStore.get("run_1")!!.state)
    }

    @Test
    fun recoverPrunesTerminalEffectsOlderThanRetentionWindow() = runBlocking {
        val conversationId = Uuid.random()
        val retentionMs = 7L * 24 * 60 * 60 * 1000
        // ±1h margins keep the ages deterministic against real-clock drift
        // between row creation and the cleanup cutoff.
        val nowMs = System.currentTimeMillis()
        runTerminalStore.begin("run_old", conversationId.toString(), null)
        val oldFinished = effect("run_old", toolCallId = "call_old")
        ledger.markStarted(oldFinished.effectId, approvalDigest("run_old", "call_old", oldFinished.argsDigest))
        ledger.finish(oldFinished.effectId, listOf(UIMessagePart.Text("""{"status":"ok"}""")))
        database.toolEffectDao().upsert(
            database.toolEffectDao().getByEffectId(oldFinished.effectId)!!
                .copy(updatedAtMs = nowMs - retentionMs - 3_600_000)
        )

        runTerminalStore.begin("run_recent", conversationId.toString(), null)
        val recentFinished = effect("run_recent", toolCallId = "call_recent")
        ledger.markStarted(recentFinished.effectId, approvalDigest("run_recent", "call_recent", recentFinished.argsDigest))
        ledger.finish(recentFinished.effectId, listOf(UIMessagePart.Text("""{"status":"ok"}""")))
        database.toolEffectDao().upsert(
            database.toolEffectDao().getByEffectId(recentFinished.effectId)!!
                .copy(updatedAtMs = nowMs - retentionMs + 3_600_000)
        )

        // Non-terminal effects (still actionable) are never pruned by age.
        val oldUnknown = effect("run_old", toolCallId = "call_unknown")
        ledger.markStarted(oldUnknown.effectId, approvalDigest("run_old", "call_unknown", oldUnknown.argsDigest))
        ledger.markOutcomeUnknown(oldUnknown.effectId, "interrupted_mid_execution")
        database.toolEffectDao().upsert(
            database.toolEffectDao().getByEffectId(oldUnknown.effectId)!!
                .copy(updatedAtMs = nowMs - retentionMs - 3_600_000)
        )

        recoveryService().recover()

        // Terminal rows past the 7-day window are pruned at cold start; recent
        // terminal rows and old non-terminal rows survive.
        assertNull(ledger.get(oldFinished.effectId))
        assertEquals(ToolEffectStatus.FINISHED, ledger.get(recentFinished.effectId)!!.status)
        assertEquals(ToolEffectStatus.OUTCOME_UNKNOWN, ledger.get(oldUnknown.effectId)!!.status)
    }

    @Test
    fun waitingUserRunSurvivesRecoveryForApprovalResume() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        runTerminalStore.pause("run_1", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)

        recoveryService().recover()

        // WAITING_USER is a pause, not a crash: the approval entry is rebuilt
        // and approving continues the SAME runId.
        val run = runTerminalStore.get("run_1")!!
        assertEquals(RunTerminalState.WAITING_USER, run.state)
        assertNotNull(runTerminalStore.activeForConversation(conversationId.toString()))
        assertEquals("run_1", runTerminalStore.activeForConversation(conversationId.toString())!!.runId)
    }

    @Test
    fun outcomeUnknownPromptListsEscalatedEffects() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        val effect = effect("run_1")
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))

        recoveryService().recover()

        val unknown = ledger.listOutcomeUnknown()
        assertEquals(1, unknown.size)
        assertEquals(effect.effectId, unknown.single().effectId)
    }
}
