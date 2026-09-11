package app.amber.feature.runtime

import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.db.entity.ToolEffectEntity
import app.amber.feature.tools.ToolEffectClass
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.assertFailsWith
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
    fun prepareDoesNotMintSecondRowWhenSameCallIdIsFinished() = runBlocking {
        val first = prepare()
        ledger.markStarted(first.effectId, approvalDigest("run_1", "call_1", first.argsDigest))
        ledger.finish(first.effectId, listOf(UIMessagePart.Text("""{"status":"ok"}""")))

        // The model re-emits the same callId with the same args in the same
        // run: the FINISHED effect is returned as-is, no second PREPARED row.
        val reprepared = prepare()
        assertEquals(first.effectId, reprepared.effectId)
        assertEquals(ToolEffectStatus.FINISHED, reprepared.status)
        assertEquals("no orphan row next to the FINISHED effect", 1, ledger.listByRun("run_1").size)
        assertEquals(1, ledger.listByToolCallId("call_1").size)
    }

    @Test
    fun prepareWithDifferentArgsForSameRunCallIdFailsClosed() = runBlocking {
        val first = prepare()
        ledger.markStarted(first.effectId, approvalDigest("run_1", "call_1", first.argsDigest))
        ledger.finish(first.effectId, listOf(UIMessagePart.Text("""{"status":"ok"}""")))

        // The model re-emits the SAME callId with DIFFERENT args — a protocol
        // violation. Fail-closed: no reuse of the old binding and no sibling
        // row (two effects for one call would make the approval binding and
        // the duplicate guard ambiguous).
        val exception = assertFailsWith<ToolEffectProtocolMismatchException> {
            ledger.prepare(
                runId = "run_1",
                turnId = 0,
                toolCallId = "call_1",
                toolName = "post_message",
                input = """{"text":"changed"}""",
                effectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE,
            )
        }
        assertEquals("call_1", exception.toolCallId)
        assertEquals(first.argsDigest, exception.boundArgsDigest)
        assertEquals("the ledger is unchanged — one row, still FINISHED", 1, ledger.listByRun("run_1").size)
        assertEquals(ToolEffectStatus.FINISHED, ledger.getByToolCallId("call_1")!!.status)
    }

    @Test
    fun prepareWithDifferentToolNameForSameRunCallIdFailsClosed() = runBlocking {
        val first = prepare()

        val exception = assertFailsWith<ToolEffectProtocolMismatchException> {
            ledger.prepare(
                runId = "run_1",
                turnId = 0,
                toolCallId = "call_1",
                toolName = "other_tool",
                input = """{"text":"hello"}""",
                effectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE,
            )
        }
        assertEquals("post_message", exception.boundToolName)
        assertEquals("other_tool", exception.requestedToolName)
        assertEquals("no row was minted for the mismatched call", 1, ledger.listByRun("run_1").size)
        assertEquals(ToolEffectStatus.PREPARED, ledger.get(first.effectId)!!.status)
    }

    @Test
    fun prepareRebindRejectsMismatchedReusableRowAcrossRuns() = runBlocking {
        runTerminalStore.begin("run_old", "conv_1", null)
        val first = prepare(runId = "run_old")

        // Crash + resume with a new runId; the model re-emits the callId with
        // different args. The reusable old row must never be rebound to new
        // contents (the old approval binding would silently authorize them).
        runTerminalStore.begin("run_new", "conv_1", null)
        assertFailsWith<ToolEffectProtocolMismatchException> {
            ledger.prepare(
                runId = "run_new",
                turnId = 0,
                toolCallId = "call_1",
                toolName = "post_message",
                input = """{"text":"changed"}""",
                effectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE,
            )
        }
        assertEquals("run_old", ledger.getByToolCallId("call_1")!!.runId)

        // Same callId with the SAME args still rebinds (crash-resume reuse).
        val rebound = ledger.prepare(
            runId = "run_new",
            turnId = 0,
            toolCallId = "call_1",
            toolName = "post_message",
            input = """{"text":"hello"}""",
            effectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE,
        )
        assertEquals(first.effectId, rebound.effectId)
        assertEquals("run_new", rebound.runId)
        assertEquals(1, ledger.listByRun("run_new").size)
    }

    @Test
    fun markStartedLeavesFinishedRowUntouched() = runBlocking {
        val effect = prepare()
        ledger.finish(effect.effectId, listOf(UIMessagePart.Text("""{"status":"ok"}""")))

        // A FINISHED row can surface as a prepare reuse product; a stray
        // markStarted must never downgrade it back to STARTED.
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))

        assertEquals(ToolEffectStatus.FINISHED, ledger.get(effect.effectId)!!.status)
    }

    @Test
    fun payloadSweepClearsFinishedRowEvenWhenYoungerRowsShareTheCallId() = runBlocking {
        val effect = prepare()
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))
        ledger.finish(effect.effectId, listOf(UIMessagePart.Text("""{"status":"ok"}""")))
        // Historical shape (pre-fix builds minted these): a younger PREPARED
        // orphan sharing the callId, so getByToolCallId (newest wins) does
        // not surface the FINISHED row. The DAO sorts by created_at_ms, so
        // the orphan must carry a later timestamp than the live rows.
        val now = System.currentTimeMillis() + 60_000
        database.toolEffectDao().upsert(
            ToolEffectEntity(
                effectId = "orphan_1",
                runId = "run_1",
                turnId = 0,
                toolCallId = "call_1",
                toolName = "post_message",
                argsDigest = effect.argsDigest,
                approvalDigest = null,
                effectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE.name,
                status = ToolEffectStatus.PREPARED.name,
                startedAtMs = now,
                finishedAtMs = null,
                resultSummary = null,
                resultPayload = null,
                errorCategory = null,
                messagePersistenceCursor = "msg_1",
                createdAtMs = now,
                updatedAtMs = now,
            )
        )
        assertEquals(ToolEffectStatus.PREPARED, ledger.getByToolCallId("call_1")!!.status)

        // clearPersistedToolPayloads shape: sweep ALL rows for the callId.
        ledger.listByToolCallId("call_1").forEach { row ->
            ledger.markResultPersisted(row.effectId)
        }

        val swept = ledger.listByToolCallId("call_1").associateBy(ToolEffect::effectId)
        assertNull("the FINISHED row's plaintext payload is dropped", swept[effect.effectId]!!.resultPayload)
        assertEquals(ToolEffectStatus.FINISHED, swept[effect.effectId]!!.status)
        assertEquals(ToolEffectStatus.PREPARED, swept["orphan_1"]!!.status)
    }

    @Test
    fun prepareMintsFreshRowForFailedRetry() = runBlocking {
        val first = prepare()
        ledger.markStarted(first.effectId, approvalDigest("run_1", "call_1", first.argsDigest))
        ledger.fail(first.effectId, "HttpException")

        // A FAILED execution keeps its retry semantics: the next prepare
        // mints a FRESH PREPARED row (the FAILED row stays for the audit).
        val retry = prepare()
        assertNotEquals(first.effectId, retry.effectId)
        assertEquals(ToolEffectStatus.PREPARED, retry.status)
        assertEquals(2, ledger.listByRun("run_1").size)
        // Current-attempt semantics: the newest row is the one callers see.
        assertEquals(retry.effectId, ledger.getByToolCallId("call_1")!!.effectId)
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

    @Test
    fun argsDigestIsIndependentOfTopLevelObjectKeyOrder() {
        // Negative control: different args must still digest differently
        // (guards against a canonicalization that erases content).
        assertNotEquals(argsDigest("""{"a":1}"""), argsDigest("""{"a":2}"""))
        assertEquals(
            argsDigest("""{"a":1,"b":2}"""),
            argsDigest("""{"b":2,"a":1}"""),
        )
    }

    @Test
    fun argsDigestCanonicalizesNestedObjectsAndArrayElements() {
        assertEquals(
            argsDigest("""{"outer":{"a":1,"b":2},"z":[3,{"y":4,"x":5}]}"""),
            argsDigest("""{"z":[3,{"x":5,"y":4}],"outer":{"b":2,"a":1}}"""),
        )
    }

    @Test
    fun argsDigestFiltersDisplayTitleAtEveryNestingLevel() {
        assertEquals(
            argsDigest("""{"display_title":"顶层","a":1}"""),
            argsDigest("""{"a":1}"""),
        )
        assertEquals(
            argsDigest("""{"a":1,"inner":{"display_title":"嵌套","b":2}}"""),
            argsDigest("""{"a":1,"inner":{"b":2}}"""),
        )
    }
}
