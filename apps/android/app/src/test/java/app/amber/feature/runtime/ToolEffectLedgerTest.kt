package app.amber.feature.runtime

import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.ToolEffectClass
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-02 ledger write-ahead protocol + status transitions.
 */
class ToolEffectLedgerTest : DurableRuntimeTestBase() {

    private suspend fun prepare(
        toolCallId: String = "call_1",
        runId: String = "run_1",
        turnId: Int = 0,
        effectClass: ToolEffectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE,
    ) = ledger.prepare(
        runId = runId,
        turnId = turnId,
        toolCallId = toolCallId,
        toolName = "post_message",
        input = """{"text":"hello"}""",
        effectClass = effectClass,
        messagePersistenceCursor = "msg_1",
    )

    @Test
    fun writeAheadProtocolAdvancesPreparedStartedFinished() = runBlocking {
        val effect = prepare()
        assertEquals(ToolEffectStatus.PREPARED, effect.status)
        assertNotNull(effect.effectId)
        assertNotEquals(effect.argsDigest, "hello")
        assertEquals("msg_1", effect.messagePersistenceCursor)

        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))
        val started = ledger.get(effect.effectId)!!
        assertEquals(ToolEffectStatus.STARTED, started.status)
        assertNotNull(started.approvalDigest)

        ledger.finish(effect.effectId, listOf(UIMessagePart.Text("""{"status":"ok"}""")))
        val finished = ledger.get(effect.effectId)!!
        assertEquals(ToolEffectStatus.FINISHED, finished.status)
        assertNotNull(finished.finishedAtMs)
        // Result payload is stored so a missed conversation write can replay.
        assertNotNull(finished.resultPayload)
        assertTrue(finished.resultSummary!!.contains("ok"))
    }

    @Test
    fun failureRecordsErrorCategory() = runBlocking {
        val effect = prepare()
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))
        ledger.fail(effect.effectId, "HttpException")
        val failed = ledger.get(effect.effectId)!!
        assertEquals(ToolEffectStatus.FAILED, failed.status)
        assertEquals("HttpException", failed.errorCategory)
        assertNotNull(failed.finishedAtMs)
    }

    @Test
    fun startedWithoutFinishCanBeMarkedOutcomeUnknownAndReconciled() = runBlocking {
        val effect = prepare()
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))

        // crash: no finish — recovery escalates non-idempotent effects
        ledger.markOutcomeUnknown(effect.effectId, "interrupted_mid_execution")
        assertEquals(ToolEffectStatus.OUTCOME_UNKNOWN, ledger.get(effect.effectId)!!.status)

        // user confirms retry
        ledger.reconcile(effect.effectId, retry = true)
        val reconciled = ledger.get(effect.effectId)!!
        assertEquals(ToolEffectStatus.RECONCILED, reconciled.status)
        assertNull(reconciled.errorCategory)
    }

    @Test
    fun abandonRecordsStructuredRejectionAndBlocksReuse() = runBlocking {
        val effect = prepare()
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))
        ledger.markOutcomeUnknown(effect.effectId, "interrupted_mid_execution")

        val abandoned = listOf(UIMessagePart.Text("""{"status":"abandoned","error":"user chose to abandon"}"""))
        ledger.reconcile(effect.effectId, retry = false, abandonOutput = abandoned)
        val reconciled = ledger.get(effect.effectId)!!
        assertEquals(ToolEffectStatus.RECONCILED, reconciled.status)
        assertEquals("abandoned", reconciled.errorCategory)
        assertNotNull(reconciled.resultPayload)

        // A prepare for the same tool call must NOT reuse the abandoned effect.
        val reprepare = prepare(toolCallId = "call_1")
        assertNotEquals(reconciled.effectId, reprepare.effectId)
        assertEquals(ToolEffectStatus.PREPARED, reprepare.status)
    }

    @Test
    fun markResultPersistedClearsPayloadAfterConversationWrite() = runBlocking {
        val effect = prepare()
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))
        ledger.finish(effect.effectId, listOf(UIMessagePart.Text("""{"status":"ok"}""")))
        assertNotNull(ledger.get(effect.effectId)!!.resultPayload)

        // The result has been written into the conversation — the replay
        // payload is dropped, but FINISHED state / summary / timestamps stay.
        ledger.markResultPersisted(effect.effectId)

        val persisted = ledger.get(effect.effectId)!!
        assertEquals(ToolEffectStatus.FINISHED, persisted.status)
        assertNull(persisted.resultPayload)
        assertNotNull(persisted.resultSummary)
        assertNotNull(persisted.finishedAtMs)
    }

    @Test
    fun markResultPersistedKeepsUnfinishedEffectsUntouched() = runBlocking {
        val effect = prepare()
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))

        ledger.markResultPersisted(effect.effectId)

        // Not FINISHED/FAILED: nothing was written back, nothing is cleared.
        assertEquals(ToolEffectStatus.STARTED, ledger.get(effect.effectId)!!.status)
    }

    @Test
    fun prepareReusesEffectWithinSameRun() = runBlocking {
        val first = prepare()
        // Approval round re-enters prepare with the same (runId, toolCallId).
        val second = prepare()
        assertEquals(first.effectId, second.effectId)
        assertEquals(1, ledger.listByRun("run_1").size)
    }

    @Test
    fun prepareRebindsEffectToNewRunAfterCrashResume() = runBlocking {
        runTerminalStore.begin("run_old", "conv_1", null)
        val first = prepare(runId = "run_old")
        // Process death + resume with a fresh runId in the same conversation:
        // the effect is rebound, not duplicated.
        runTerminalStore.begin("run_new", "conv_1", null)
        val second = prepare(runId = "run_new")
        assertEquals(first.effectId, second.effectId)
        assertEquals("run_new", second.runId)
        assertEquals(1, ledger.listByRun("run_new").size)
    }

    @Test
    fun outcomeUnknownEffectsAreListed() = runBlocking {
        val effect = prepare()
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))
        ledger.markOutcomeUnknown(effect.effectId, "interrupted_mid_execution")
        val unknown = ledger.listOutcomeUnknown()
        assertEquals(1, unknown.size)
        assertEquals(effect.effectId, unknown.single().effectId)
    }
}
