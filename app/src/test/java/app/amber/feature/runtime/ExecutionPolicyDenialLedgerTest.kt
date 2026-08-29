package app.amber.feature.runtime

import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentEventPayload
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.agent.runtime.ToolLifecycleEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 6 — separate sandbox from approval: a policy denial on the durable path
 * mirrors the s05 user-denial shape exactly — ledger row FAILED with
 * errorCategory `policy_denied`, a Finished(DENIED) lifecycle event and NO
 * Started event — while the model still receives the structured
 * `status=policy_denied` output.
 */
class ExecutionPolicyDenialLedgerTest : DurableRuntimeTestBase() {

    private val json = Json { ignoreUnknownKeys = true }

    private val dispatcher = AgentToolDispatcher(
        json = json,
        permissionDecisionResolver = PermissionDecisionResolver(),
    )

    private class RecordingEventWriter : AgentEventWriter {
        val committed = mutableListOf<AgentEventPayload.Final>()
        override fun emit(transient: AgentEventPayload.Transient) {}
        override suspend fun commit(final: AgentEventPayload.Final) {
            committed += final
        }
        override suspend fun flush() {}
        override suspend fun commitError(throwable: Throwable, recoverable: Boolean) {}
    }

    private fun RecordingEventWriter.lifecycleEvents(): List<ToolLifecycleEvent> =
        committed.filterIsInstance<ToolLifecycleEvent>()

    @Test
    fun policyDeniedExecutionMirrorsTheUserDenialShape() = runBlocking {
        // The kernel prepares the effect before dispatch, exactly like the
        // approval path.
        val effect = ledger.prepare(
            runId = "run_1",
            turnId = 0,
            toolCallId = "call_1",
            toolName = "terminal_execute",
            input = """{"command":"ls"}""",
            effectClass = app.amber.feature.tools.ToolEffectClass.NON_IDEMPOTENT_WRITE,
        )
        val events = RecordingEventWriter()
        var executions = 0
        val result = dispatcher.execute(
            tool = UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "terminal_execute",
                input = """{"command":"ls"}""",
                approvalState = ToolApprovalState.Approved,
            ),
            toolDef = Tool(
                name = "terminal_execute",
                description = "",
                execute = {
                    executions++
                    listOf(UIMessagePart.Text("""{"status":"ok"}"""))
                },
            ),
            autoApproveTools = false,
            ledgerContext = ToolLedgerContext(
                runId = "run_1",
                turnId = 0,
                ledger = ledger,
                messagePersistenceCursor = "msg_1",
                events = events,
            ),
            executionPolicy = ExecutionPolicy(allowShell = false),
        )!!

        // The tool body never ran.
        assertEquals(0, executions)
        // The model sees the structured policy denial.
        val payload = json.parseToJsonElement(
            (result.output.single() as UIMessagePart.Text).text,
        ).jsonObject
        assertEquals("policy_denied", payload["status"]!!.jsonPrimitive.content)
        assertTrue(payload["message"]!!.jsonPrimitive.content.contains("terminal_execute"))
        // The ledger row failed with the policy_denied category.
        val recorded = ledger.get(effect.effectId)!!
        assertEquals(ToolEffectStatus.FAILED, recorded.status)
        assertEquals(POLICY_DENIED_EFFECT_CATEGORY, recorded.errorCategory)
        // Same event shape as the s05 user denial: Finished(DENIED), no Started.
        val emitted = events.lifecycleEvents()
        assertEquals(listOf("Finished"), emitted.map { it::class.simpleName })
        val finished = emitted.single() as ToolLifecycleEvent.Finished
        assertEquals(effect.effectId, finished.effectId)
        assertEquals(ToolLifecycleEvent.Finished.Status.DENIED, finished.status)
        assertEquals(POLICY_DENIED_EFFECT_CATEGORY, finished.errorCategory)
    }
}
