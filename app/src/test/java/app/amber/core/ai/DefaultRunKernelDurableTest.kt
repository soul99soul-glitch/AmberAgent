package app.amber.core.ai

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Settings
import app.amber.core.settings.SpeculativeToolExecutionSetting
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.runtime.ToolEffectStatus
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Durable-path loop-policy tests for [DefaultRunKernel]: the real Room ledger
 * (via [DurableRuntimeTestBase]) behind a scripted [GenerationRoundEngine],
 * pinning the kernel-side durable semantics that the non-durable
 * [DefaultRunKernelTest] cannot see:
 *
 *  - speculative execution never starts on the durable path;
 *  - the duplicate guard is rebuilt from the ledger, so a re-emitted
 *    signature across run() restarts of the same runId counts as a repeat;
 *  - a resumed PREPARED emission (approval round) still executes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DefaultRunKernelDurableTest : DurableRuntimeTestBase() {

    private val json = Json { ignoreUnknownKeys = true }

    /** Scripted engine that also feeds the round's model output to the speculative runner, like the streaming engine does mid-stream. */
    private class ScriptedRoundEngine(
        private val script: List<(List<UIMessage>) -> UIMessage>,
    ) : GenerationRoundEngine {
        val requests = mutableListOf<GenerationRoundRequest>()

        override suspend fun generateRound(
            request: GenerationRoundRequest,
            onUpdateMessages: suspend (GenerationUpdate) -> Unit,
        ): GenerationRoundOutcome {
            requests += request
            val step = script.getOrNull(requests.size - 1)
                ?: { _: List<UIMessage> -> textAssistant("fallback answer") }
            val produced = step(request.messages)
            request.speculativeRunner?.observe(
                produced.getTools(),
                request.tools.associateBy { it.name },
            )
            onUpdateMessages(GenerationUpdate.full(request.messages + produced))
            return GenerationRoundOutcome(outputLimitReached = false)
        }
    }

    private fun durableFlags(): CapabilityFlags {
        val flags = CapabilityFlags(
            PreferenceDataStoreFactory.create {
                File(context.cacheDir, "kernel-durable-flags-${System.nanoTime()}.preferences_pb")
            }
        )
        return flags
    }

    private fun durableKernel(
        engine: GenerationRoundEngine,
        flags: CapabilityFlags,
    ): DefaultRunKernel = DefaultRunKernel(
        context = context,
        toolDispatcher = AgentToolDispatcher(json, PermissionDecisionResolver()),
        roundEngine = engine,
        toolEffectLedger = ledger,
        capabilityFlags = flags,
        capabilityPermissionStore = null,
    )

    /** Non-durable kernel: no runId/onTerminal/ledger wiring at all. */
    private fun plainKernel(engine: GenerationRoundEngine): DefaultRunKernel = DefaultRunKernel(
        context = context,
        toolDispatcher = AgentToolDispatcher(json, PermissionDecisionResolver()),
        roundEngine = engine,
    )

    private fun session(
        messages: List<UIMessage>,
        tools: List<Tool> = emptyList(),
        runId: String? = null,
        settings: Settings = Settings(),
    ): GenerationRunSession = GenerationRunSession(
        settings = settings,
        model = Model(),
        messages = messages,
        tools = tools,
        runId = runId,
        onTerminal = if (runId != null) {
            { }
        } else {
            null
        },
    )

    private fun speculativeSettings() = Settings(
        agentRuntime = AgentRuntimeSetting(
            speculativeToolExecution = SpeculativeToolExecutionSetting(enabled = true),
        ),
    )

    private fun readOnlyTool(executions: AtomicInteger) = Tool(
        name = "file_read",
        description = "read-only lookup",
        execute = {
            executions.incrementAndGet()
            listOf(UIMessagePart.Text("tool-result"))
        },
    )

    @Test
    fun `durable path never creates a speculative runner even when enabled`() = runBlocking {
        val executions = AtomicInteger(0)
        val readOnly = readOnlyTool(executions)
        val engine = ScriptedRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "file_read") },
                { textAssistant("完成") },
            ),
        )
        val flags = durableFlags()

        durableKernel(engine, flags).run(
            session(
                messages = listOf(UIMessage.user("查一下")),
                tools = listOf(readOnly),
                runId = "run_speculative_off",
                settings = speculativeSettings(),
            ),
        ).toList()

        // The pin: every round sees NO speculative runner, so the streamed
        // tool call was never pre-executed — it ran once, in the normal batch.
        assertTrue(engine.requests.isNotEmpty())
        assertTrue(engine.requests.all { it.speculativeRunner == null })
        assertEquals(1, executions.get())
        assertTrue(engine.requests[1].messages.last().getTools().single().isExecuted)
    }

    @Test
    fun `non-durable path still wires the speculative runner when enabled`() = runBlocking {
        val executions = AtomicInteger(0)
        val readOnly = readOnlyTool(executions)
        val engine = ScriptedRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "file_read") },
                { textAssistant("完成") },
            ),
        )

        plainKernel(engine).run(
            session(
                messages = listOf(UIMessage.user("查一下")),
                tools = listOf(readOnly),
                settings = speculativeSettings(),
            ),
        ).toList()

        // Control for the pin above: without the durable path the runner
        // exists and the streamed call was handed to it for pre-execution.
        assertTrue(engine.requests.isNotEmpty())
        assertTrue(engine.requests.all { it.speculativeRunner != null })
        assertTrue(engine.requests[0].speculativeRunner!!.snapshot().isNotEmpty())
    }

    @Test
    fun `guard rebuild counts a finished effect so a re-emitted signature skips across runs`() = runBlocking {
        val executions = AtomicInteger(0)
        val readOnly = readOnlyTool(executions)
        val runId = "run_guard_rebuild"
        val flags = durableFlags()

        // Run 1: call_1 executes successfully → FINISHED in the ledger.
        val engine1 = ScriptedRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "file_read", input = """{"q":"a"}""") },
                { textAssistant("完成") },
            ),
        )
        durableKernel(engine1, flags).run(
            session(listOf(UIMessage.user("查一下")), listOf(readOnly), runId),
        ).toList()
        assertEquals(1, executions.get())
        assertEquals(ToolEffectStatus.FINISHED, ledger.getByToolCallId("call_1")!!.status)

        // Run 2 — a fresh run() of the SAME runId (approval resume / process
        // restart shape): the model re-emits the same signature under a new
        // callId. The guard rebuilt from the ledger must count it as the
        // SECOND occurrence (skipped), not the first (executed).
        val engine2 = ScriptedRoundEngine(
            listOf(
                { toolCallAssistant("call_2", "file_read", input = """{"q":"a"}""") },
                { textAssistant("完成") },
            ),
        )
        durableKernel(engine2, flags).run(
            session(listOf(UIMessage.user("再查一次")), listOf(readOnly), runId),
        ).toList()

        assertEquals("the rebuilt table remembered run 1's execution", 1, executions.get())
        val roundTwoTool = engine2.requests[1].messages.last().getTools().single()
        assertEquals("call_2", roundTwoTool.toolCallId)
        val skippedOutput = roundTwoTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(skippedOutput.contains("\"status\":\"skipped\""))
        assertTrue(skippedOutput.contains("\"reason\":\"duplicate_tool_call\""))
        assertTrue(skippedOutput.contains("\"occurrence\":2"))
    }

    @Test
    fun `a finished callId re-emitted under the same id is not re-executed on the durable path`() = runBlocking {
        val executions = AtomicInteger(0)
        val readOnly = readOnlyTool(executions)
        val runId = "run_guard_same_call_id"
        val flags = durableFlags()

        val engine1 = ScriptedRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "file_read") },
                { textAssistant("完成") },
            ),
        )
        durableKernel(engine1, flags).run(
            session(listOf(UIMessage.user("查一下")), listOf(readOnly), runId),
        ).toList()
        assertEquals(1, executions.get())

        val engine2 = ScriptedRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "file_read") },
                { textAssistant("完成") },
            ),
        )
        durableKernel(engine2, flags).run(
            session(listOf(UIMessage.user("又查一次")), listOf(readOnly), runId),
        ).toList()

        assertEquals("the FINISHED callId is signature-handled, not re-executed", 1, executions.get())
        val roundTwoTool = engine2.requests[1].messages.last().getTools().single()
        assertTrue(roundTwoTool.isExecuted)
        val skippedOutput = roundTwoTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(skippedOutput.contains("\"reason\":\"duplicate_tool_call\""))
        // The write-ahead prepare runs BEFORE the guard classifies the call:
        // it must reuse the FINISHED effect instead of minting a PREPARED
        // orphan that no retention would ever collect.
        assertEquals(1, ledger.listByRun(runId).size)
        assertEquals(1, ledger.listByToolCallId("call_1").size)
    }

    @Test
    fun `an approval-parked PREPARED emission still executes on durable resume`() = runBlocking {
        val executions = AtomicInteger(0)
        val guarded = Tool(
            name = "write_thing",
            description = "side-effecting write",
            needsApproval = true,
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("written"))
            },
        )
        val runId = "run_guard_approval_resume"
        val flags = durableFlags()
        val terminals = mutableListOf<GenerationTerminal>()

        // Run 1: the call parks at the approval gate; its effect is PREPARED
        // (never FINISHED), so the rebuild must not count it.
        val engine1 = ScriptedRoundEngine(listOf({ toolCallAssistant("call_1", "write_thing") }))
        val run1Chunks = durableKernel(engine1, flags).run(
            GenerationRunSession(
                settings = Settings(),
                model = Model(),
                messages = listOf(UIMessage.user("写一下")),
                tools = listOf(guarded),
                runId = runId,
                onTerminal = { terminals += it },
            ),
        ).toList()

        assertEquals(listOf(GenerationTerminal.WaitingUser), terminals)
        assertEquals(0, executions.get())
        assertEquals(ToolEffectStatus.PREPARED, ledger.getByToolCallId("call_1")!!.status)

        // Run 2 (same runId): the user approved → the resumed emission must
        // execute exactly once — PREPARED/RECONCILED resume stays exempt.
        val parkedMessages = (run1Chunks.last() as GenerationChunk.Messages).messages
        val engine2 = ScriptedRoundEngine(listOf({ textAssistant("写完了") }))
        val run2Chunks = durableKernel(engine2, flags).run(
            session(approveCall(parkedMessages, "call_1"), listOf(guarded), runId),
        ).toList()

        assertEquals(1, executions.get())
        assertEquals(ToolEffectStatus.FINISHED, ledger.getByToolCallId("call_1")!!.status)
        val finalAnswer = (run2Chunks.last() as GenerationChunk.Messages)
            .messages.last().parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("写完了", finalAnswer.text)
    }

    // ---- helpers ----

    private fun toolCallAssistant(
        callId: String,
        toolName: String,
        input: String = "{}",
    ): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool(toolCallId = callId, toolName = toolName, input = input)),
    )

    // textAssistant lives at file level: the nested ScriptedRoundEngine's
    // lambdas need it without an outer-class receiver.

    private fun approveCall(messages: List<UIMessage>, toolCallId: String): List<UIMessage> =
        messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                        part.copy(approvalState = ToolApprovalState.Approved)
                    } else {
                        part
                    }
                },
            )
        }
}

private fun textAssistant(text: String): UIMessage = UIMessage(
    role = MessageRole.ASSISTANT,
    parts = listOf(UIMessagePart.Text(text)),
)
