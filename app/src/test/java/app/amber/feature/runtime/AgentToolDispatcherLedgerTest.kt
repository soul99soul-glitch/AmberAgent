package app.amber.feature.runtime

import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.core.ai.GenerationRetrySetting
import app.amber.feature.tools.effectClass
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-02 dispatcher write-ahead: PREPARED → STARTED → FINISHED / FAILED,
 * OUTCOME_UNKNOWN blocking, structured denial, idempotency support.
 */
class AgentToolDispatcherLedgerTest : DurableRuntimeTestBase() {

    private val dispatcher = AgentToolDispatcher(
        json = Json { ignoreUnknownKeys = true },
        permissionDecisionResolver = PermissionDecisionResolver(),
    )

    private fun context(runId: String = "run_1") = ToolLedgerContext(
        runId = runId,
        turnId = 0,
        ledger = ledger,
        messagePersistenceCursor = "msg_1",
    )

    private fun toolCall(
        toolCallId: String = "call_1",
        toolName: String = "post_message",
        input: String = """{"text":"hello"}""",
        approvalState: ToolApprovalState = ToolApprovalState.Approved,
    ) = UIMessagePart.Tool(
        toolCallId = toolCallId,
        toolName = toolName,
        input = input,
        approvalState = approvalState,
    )

    private fun toolDef(name: String, execute: suspend (JsonElement) -> List<UIMessagePart>): Tool =
        Tool(name = name, description = "", execute = execute)

    private fun approvalStore(): CapabilityPermissionStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
            )
        ) {
            File.createTempFile("approval-history", ".preferences_pb")
        }
        return CapabilityPermissionStore(dataStore)
    }

    @Test
    fun successfulExecutionWritesPreparedStartedFinished() = runBlocking {
        var executions = 0
        val result = dispatcher.execute(
            tool = toolCall(),
            toolDef = toolDef("post_message") {
                executions++
                listOf(UIMessagePart.Text("""{"status":"ok"}"""))
            },
            autoApproveTools = false,
            ledgerContext = context(),
        )!!

        assertEquals(1, executions)
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("""{"status":"ok"}"""))

        val effect = ledger.getByToolCallId("call_1")!!
        assertEquals(ToolEffectStatus.FINISHED, effect.status)
        assertEquals("post_message", effect.toolName)
        assertNotNull(effect.argsDigest)
        assertNotNull(effect.approvalDigest)
        assertNotNull(effect.resultPayload)
    }

    @Test
    fun failureIsRecordedWithErrorCategory() = runBlocking {
        val result = dispatcher.execute(
            tool = toolCall(),
            toolDef = toolDef("post_message") { error("boom") },
            autoApproveTools = false,
            ledgerContext = context(),
        )!!

        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("\"status\":\"failed\""))
        val effect = ledger.getByToolCallId("call_1")!!
        assertEquals(ToolEffectStatus.FAILED, effect.status)
        assertNotNull(effect.errorCategory)
    }

    @Test
    fun cancellationLeavesEffectStarted() = runBlocking {
        val result = runCatching {
            dispatcher.execute(
                tool = toolCall(),
                toolDef = toolDef("post_message") { throw CancellationException("stopped") },
                autoApproveTools = false,
                ledgerContext = context(),
            )
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        val effect = ledger.getByToolCallId("call_1")!!
        assertEquals(ToolEffectStatus.STARTED, effect.status)
        assertNull(effect.finishedAtMs)
    }

    @Test
    fun userDenialRecordsApprovalDeniedAndStructuredRejection() = runBlocking {
        // The generation coordinator prepares the effect before the approval card.
        val effect = ledger.prepare(
            runId = "run_1",
            turnId = 0,
            toolCallId = "call_1",
            toolName = "post_message",
            input = """{"text":"hello"}""",
            effectClass = app.amber.feature.tools.ToolEffectClass.NON_IDEMPOTENT_WRITE,
        )
        val result = dispatcher.execute(
            tool = toolCall(approvalState = ToolApprovalState.Denied("用户拒绝")),
            toolDef = toolDef("post_message") { listOf(UIMessagePart.Text("must not run")) },
            autoApproveTools = false,
            ledgerContext = context(),
        )!!

        // 结构化拒绝结果交给模型
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("\"status\":\"denied\""))
        assertTrue(text.contains("用户拒绝"))
        val recorded = ledger.get(effect.effectId)!!
        assertEquals(ToolEffectStatus.FAILED, recorded.status)
        assertEquals("approval_denied", recorded.errorCategory)
    }

    @Test
    fun outcomeUnknownEffectIsNotReExecuted() = runBlocking {
        // Crash mid-execution: STARTED without FINISHED, recovered to
        // OUTCOME_UNKNOWN (simulating what RunRecoveryService does).
        val effect = ledger.prepare(
            runId = "run_1",
            turnId = 0,
            toolCallId = "call_1",
            toolName = "post_message",
            input = """{"text":"hello"}""",
            effectClass = app.amber.feature.tools.ToolEffectClass.NON_IDEMPOTENT_WRITE,
        )
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))
        ledger.markOutcomeUnknown(effect.effectId, "interrupted_mid_execution")

        var executions = 0
        val result = dispatcher.execute(
            tool = toolCall(),
            toolDef = toolDef("post_message") {
                executions++
                listOf(UIMessagePart.Text("""{"status":"ok"}"""))
            },
            autoApproveTools = false,
            ledgerContext = context(),
        )!!

        // The non-idempotent tool must NOT run again without user confirmation.
        assertEquals(0, executions)
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("\"status\":\"outcome_unknown\""))
        assertEquals(ToolEffectStatus.OUTCOME_UNKNOWN, ledger.get(effect.effectId)!!.status)
    }

    @Test
    fun reconciledEffectExecutesAfterUserConfirmsRetry() = runBlocking {
        val effect = ledger.prepare(
            runId = "run_1",
            turnId = 0,
            toolCallId = "call_1",
            toolName = "post_message",
            input = """{"text":"hello"}""",
            effectClass = app.amber.feature.tools.ToolEffectClass.NON_IDEMPOTENT_WRITE,
        )
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))
        ledger.markOutcomeUnknown(effect.effectId, "interrupted_mid_execution")
        ledger.reconcile(effect.effectId, retry = true)

        var executions = 0
        val result = dispatcher.execute(
            tool = toolCall(),
            toolDef = toolDef("post_message") {
                executions++
                listOf(UIMessagePart.Text("""{"status":"ok"}"""))
            },
            autoApproveTools = false,
            ledgerContext = context(),
        )!!
        assertEquals(1, executions)
        assertEquals(ToolEffectStatus.FINISHED, ledger.get(effect.effectId)!!.status)
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("""{"status":"ok"}"""))
    }

    @Test
    fun abandonedEffectIsBlockedEvenIfResubmitted() = runBlocking {
        val effect = ledger.prepare(
            runId = "run_1",
            turnId = 0,
            toolCallId = "call_1",
            toolName = "post_message",
            input = """{"text":"hello"}""",
            effectClass = app.amber.feature.tools.ToolEffectClass.NON_IDEMPOTENT_WRITE,
        )
        ledger.markStarted(effect.effectId, approvalDigest("run_1", "call_1", effect.argsDigest))
        ledger.markOutcomeUnknown(effect.effectId, "interrupted_mid_execution")
        ledger.reconcile(effect.effectId, retry = false, abandonOutput = listOf(UIMessagePart.Text("{}")))

        var executions = 0
        val result = dispatcher.execute(
            tool = toolCall(),
            toolDef = toolDef("post_message") {
                executions++
                listOf(UIMessagePart.Text("""{"status":"ok"}"""))
            },
            autoApproveTools = false,
            ledgerContext = context(),
        )!!
        assertEquals(0, executions)
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("\"status\":\"abandoned\""))
    }

    @Test
    fun approvedCallWithChangedArgsIsBlockedOnTheDurablePath() = runBlocking {
        val store = approvalStore()
        store.recordApproval(
            ApprovalHistoryEntry.approved(
                capability = null,
                toolName = "post_message",
                runId = "run_1",
                toolCallId = "call_1",
                effectId = null,
                argsDigest = argsDigest("""{"text":"hello"}"""),
                source = "user",
            )
        )

        var executions = 0
        val result = dispatcher.execute(
            tool = toolCall(input = """{"text":"changed"}"""),
            toolDef = toolDef("post_message") {
                executions++
                listOf(UIMessagePart.Text("must not run"))
            },
            autoApproveTools = false,
            ledgerContext = context(),
            approvalHistory = store,
        )!!

        // The effect is prepared (write-ahead) but the stale approval must
        // block execution with the structured result.
        assertEquals(0, executions)
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue("expected approval_stale but was: $text", text.contains("\"status\":\"approval_stale\""))
        assertTrue(text.contains("\"effect_id\""))
        val effect = ledger.getByToolCallId("call_1")!!
        assertEquals(ToolEffectStatus.PREPARED, effect.status)
    }

    @Test
    fun readOnlyToolIsClassifiedReadOnlyAndIdempotentToolGetsIdempotencyKey() = runBlocking {
        val readOnly = Tool(name = "file_read", description = "", execute = { emptyList() }).effectClass()
        assertEquals(app.amber.feature.tools.ToolEffectClass.READ_ONLY, readOnly)

        val idempotent = Tool(name = "file_write", description = "", execute = { emptyList() }).effectClass()
        assertEquals(app.amber.feature.tools.ToolEffectClass.IDEMPOTENT_WRITE, idempotent)

        val unknown = Tool(name = "post_message", description = "", execute = { emptyList() }).effectClass()
        assertEquals(app.amber.feature.tools.ToolEffectClass.NON_IDEMPOTENT_WRITE, unknown)

        // EffectId is the idempotency key for idempotent writes.
        val result = dispatcher.execute(
            tool = toolCall(toolName = "file_write", input = """{"path":"a.txt","content":"x"}"""),
            toolDef = toolDef("file_write") { listOf(UIMessagePart.Text("""{"status":"ok"}""")) },
            autoApproveTools = false,
            ledgerContext = context(),
        )!!
        assertNotNull(result)
        val effect = ledger.getByToolCallId("call_1")!!
        assertEquals(app.amber.feature.tools.ToolEffectClass.IDEMPOTENT_WRITE, effect.effectClass)
    }
}
