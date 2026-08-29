package app.amber.core.ai

import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.ToolApprovalState
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.PermissionDecisionResolver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loop-policy unit tests for [DefaultRunKernel] with a scripted
 * [GenerationRoundEngine] — the seam that makes the tool loop testable
 * without any provider streaming. The durable (ledger) path stays covered by
 * RuntimeChainCanaryTest with the real Room ledger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DefaultRunKernelTest {

    /**
     * Engine that appends one scripted assistant message per round.
     * [truncatedRounds] marks rounds whose provider finish_reason meant an
     * output-limit truncation — every other round reports a normal stop.
     */
    private class FakeRoundEngine(
        private val script: List<(List<UIMessage>) -> UIMessage>,
        private val truncatedRounds: Set<Int> = emptySet(),
    ) : GenerationRoundEngine {
        val requests = mutableListOf<GenerationRoundRequest>()

        override suspend fun generateRound(
            request: GenerationRoundRequest,
            onUpdateMessages: suspend (GenerationUpdate) -> Unit,
        ): GenerationRoundOutcome {
            requests += request
            val round = requests.size - 1
            val step = script.getOrNull(round)
                ?: { _: List<UIMessage> ->
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("fallback answer")),
                    )
                }
            onUpdateMessages(GenerationUpdate.full(request.messages + step(request.messages)))
            return GenerationRoundOutcome(outputLimitReached = round in truncatedRounds)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun kernel(engine: GenerationRoundEngine): DefaultRunKernel = DefaultRunKernel(
        context = RuntimeEnvironment.getApplication(),
        toolDispatcher = AgentToolDispatcher(json, PermissionDecisionResolver()),
        roundEngine = engine,
    )

    private fun session(
        messages: List<UIMessage>,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 8,
        terminals: MutableList<GenerationTerminal> = mutableListOf(),
        steer: List<UIMessage> = emptyList(),
        executionPolicy: app.amber.feature.runtime.ExecutionPolicy =
            app.amber.feature.runtime.ExecutionPolicy.permissive(),
        settings: Settings = Settings(),
    ): GenerationRunSession {
        var pendingSteer = steer
        return GenerationRunSession(
            settings = settings,
            model = Model(),
            messages = messages,
            tools = tools,
            maxSteps = maxSteps,
            consumeSteerMessages = {
                val out = pendingSteer
                pendingSteer = emptyList()
                out
            },
            onTerminal = { terminals += it },
            executionPolicy = executionPolicy,
        )
    }

    private fun lastMessages(chunks: List<GenerationChunk>): List<UIMessage> =
        (chunks.last() as GenerationChunk.Messages).messages

    private fun textAssistant(text: String): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun toolCallAssistant(
        callId: String,
        toolName: String,
        input: String = "{}",
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
    ): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = callId,
                toolName = toolName,
                input = input,
                approvalState = approvalState,
            ),
        ),
    )

    @Test
    fun `plain answer completes the loop with no terminal signal`() = runTest {
        val engine = FakeRoundEngine(listOf({ textAssistant("你好，世界") }))
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("打个招呼")),
                terminals = terminals,
            ),
        ).toList()

        assertEquals(1, engine.requests.size)
        val last = lastMessages(chunks).last()
        assertEquals(MessageRole.ASSISTANT, last.role)
        assertNotNull("turn finalization stamps finishedAt", last.finishedAt)
        assertTrue("COMPLETED is caller-decided; the loop reports nothing", terminals.isEmpty())
    }

    @Test
    fun `approval-required tool parks the loop at WaitingUser`() = runTest {
        val guarded = Tool(
            name = "write_thing",
            description = "side-effecting write",
            needsApproval = true,
            execute = { error("must not execute while approval is pending") },
        )
        val engine = FakeRoundEngine(listOf({ toolCallAssistant("call_1", "write_thing") }))
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("写一下")),
                tools = listOf(guarded),
                terminals = terminals,
            ),
        ).toList()

        assertEquals(listOf(GenerationTerminal.WaitingUser), terminals)
        val tool = lastMessages(chunks).last().getTools().single()
        assertEquals(
            "the approval snapshot marks the call Pending",
            ToolApprovalState.Pending,
            tool.approvalState,
        )
    }

    @Test
    fun `auto-allowed tool executes inline and the loop reaches a final answer`() = runTest {
        val executions = AtomicInteger(0)
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("tool-result"))
            },
        )
        val engine = FakeRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "read_thing") },
                { textAssistant("读取完成") },
            ),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("查一下")),
                tools = listOf(readOnly),
                terminals = terminals,
            ),
        ).toList()

        assertEquals(1, executions.get())
        assertEquals("two rounds: tool call then final answer", 2, engine.requests.size)
        val roundTwoTool = engine.requests[1].messages.last().getTools().single()
        assertTrue("round two sees the executed tool output", roundTwoTool.isExecuted)
        assertEquals(
            "读取完成",
            (lastMessages(chunks).last().parts.last() as UIMessagePart.Text).text,
        )
        assertTrue(terminals.isEmpty())
    }

    @Test
    fun `exhausting the step budget reports StepLimit, never silence`() = runTest {
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = { listOf(UIMessagePart.Text("tool-result")) },
        )
        // Every round produces another tool call: the loop never breaks early.
        val engine = FakeRoundEngine(
            List(4) { { _: List<UIMessage> -> toolCallAssistant("call_$it", "read_thing") } },
        )
        val terminals = mutableListOf<GenerationTerminal>()

        kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("一直查")),
                tools = listOf(readOnly),
                maxSteps = 2,
                terminals = terminals,
            ),
        ).toList()

        assertEquals(2, engine.requests.size)
        assertEquals(listOf(GenerationTerminal.StepLimit), terminals)
    }

    @Test
    fun `steer messages join the conversation between steps`() = runTest {
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = { listOf(UIMessagePart.Text("tool-result")) },
        )
        val engine = FakeRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "read_thing") },
                { textAssistant("完成") },
            ),
        )

        kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("开始")),
                tools = listOf(readOnly),
                steer = listOf(UIMessage.user("补充一句")),
            ),
        ).toList()

        assertEquals(2, engine.requests.size)
        val roundTwo = engine.requests[1].messages
        assertTrue(
            "round two request contains the steered user message",
            roundTwo.any { msg ->
                msg.role == MessageRole.USER &&
                    msg.parts.filterIsInstance<UIMessagePart.Text>().any { it.text == "补充一句" }
            },
        )
    }

    @Test
    fun `a persisted pending tool stops the loop before any model round`() = runTest {
        val engine = FakeRoundEngine(emptyList())
        val terminals = mutableListOf<GenerationTerminal>()

        kernel(engine).run(
            session(
                messages = listOf(
                    UIMessage.user("恢复"),
                    toolCallAssistant("call_1", "write_thing", approvalState = ToolApprovalState.Pending),
                ),
                terminals = terminals,
            ),
        ).toList()

        assertEquals("no model round while a nested approval is persisted", 0, engine.requests.size)
        assertEquals(listOf(GenerationTerminal.WaitingUser), terminals)
    }

    @Test
    fun `truncated reply with a tool call executes nothing and settles without a terminal`() = runTest {
        val executions = AtomicInteger(0)
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("tool-result"))
            },
        )
        val engine = FakeRoundEngine(
            listOf({ toolCallAssistant("call_1", "read_thing") }),
            truncatedRounds = setOf(0),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("查一下")),
                tools = listOf(readOnly),
                terminals = terminals,
            ),
        ).toList()

        // The half-emitted tool call must never execute and never park the
        // loop at the approval gate; the guard note rides the plain-text
        // channel and the caller settles the run (no terminal reported).
        assertEquals(0, executions.get())
        assertEquals(1, engine.requests.size)
        assertTrue(terminals.isEmpty())
        val last = lastMessages(chunks).last()
        val tool = last.getTools().single()
        assertTrue("the truncated call stays unexecuted", !tool.isExecuted)
        val note = last.parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("模型回复达到输出上限，请重试。", note.text)
    }

    @Test
    fun `truncated plain-text reply still gets the output-limit note`() = runTest {
        val engine = FakeRoundEngine(
            listOf({ textAssistant("写到一半的回答") }),
            truncatedRounds = setOf(0),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("继续写")),
                terminals = terminals,
            ),
        ).toList()

        assertTrue(terminals.isEmpty())
        val parts = lastMessages(chunks).last().parts
        assertEquals("写到一半的回答", (parts.first() as UIMessagePart.Text).text)
        assertEquals("模型回复达到输出上限，请重试。", (parts.last() as UIMessagePart.Text).text)
    }

    @Test
    fun `identical tool call is skipped once with a reminder then stops the run on the third`() = runTest {
        val executions = AtomicInteger(0)
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("tool-result"))
            },
        )
        val engine = FakeRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "read_thing") },
                { toolCallAssistant("call_2", "read_thing") },
                { toolCallAssistant("call_3", "read_thing") },
            ),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("一直查")),
                tools = listOf(readOnly),
                terminals = terminals,
            ),
        ).toList()

        // Round 1: executes. Round 2: skipped (occurrence 2), loop continues.
        // Round 3: stopped (occurrence 3) with the guard note, no terminal —
        // the tool body only ever ran once.
        assertEquals("only the first emission ever executed", 1, executions.get())
        assertEquals(3, engine.requests.size)
        assertTrue("stop is not a park and not a step limit", terminals.isEmpty())

        val roundThreeTool = engine.requests[2].messages.last().getTools().single()
        assertEquals("call_2", roundThreeTool.toolCallId)
        assertTrue("the skipped call carries the structured reminder", roundThreeTool.isExecuted)
        val skippedOutput = roundThreeTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(skippedOutput.contains("\"status\":\"skipped\""))
        assertTrue(skippedOutput.contains("\"reason\":\"duplicate_tool_call\""))
        assertTrue(skippedOutput.contains("\"occurrence\":2"))

        val last = lastMessages(chunks).last()
        val stoppedTool = last.getTools().last { it.toolCallId == "call_3" }
        val stoppedOutput = stoppedTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(stoppedOutput.contains("\"occurrence\":3"))
        val note = last.parts.filterIsInstance<UIMessagePart.Text>().last()
        assertEquals("检测到重复的工具调用，已停止本轮以避免死循环。", note.text)
    }

    @Test
    fun `two same-signature calls in one message execute once and the twin is skipped in-batch`() = runTest {
        val executions = AtomicInteger(0)
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("tool-result"))
            },
        )
        // Round 1 emits ONE assistant message carrying two same-signature
        // calls. The cross-step table cannot know the twin (it only updates
        // after executeBatch), so the batch-local seen set must skip the
        // second call before the batch is dispatched.
        val engine = FakeRoundEngine(
            listOf(
                { _: List<UIMessage> ->
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(toolCallId = "call_1", toolName = "read_thing", input = "{}"),
                            UIMessagePart.Tool(toolCallId = "call_2", toolName = "read_thing", input = "{}"),
                        ),
                    )
                },
                { textAssistant("完成") },
            ),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("查一下")),
                tools = listOf(readOnly),
                terminals = terminals,
            ),
        ).toList()

        assertEquals("only the first in-batch emission ever executed", 1, executions.get())
        assertEquals("the loop continued into the next round", 2, engine.requests.size)
        assertTrue("a skipped twin is not a stop and not a park", terminals.isEmpty())

        val roundTwoTools = engine.requests[1].messages.last().getTools()
        assertEquals(2, roundTwoTools.size)
        val executedCall = roundTwoTools.single { it.toolCallId == "call_1" }
        assertTrue(executedCall.isExecuted)
        assertEquals("tool-result", executedCall.output.filterIsInstance<UIMessagePart.Text>().single().text)
        val skippedCall = roundTwoTools.single { it.toolCallId == "call_2" }
        assertTrue("the twin carries the structured skip output", skippedCall.isExecuted)
        val skippedOutput = skippedCall.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(skippedOutput.contains("\"status\":\"skipped\""))
        assertTrue(skippedOutput.contains("\"reason\":\"duplicate_tool_call\""))
        assertTrue(skippedOutput.contains("\"occurrence\":2"))

        assertEquals("完成", (lastMessages(chunks).last().parts.last() as UIMessagePart.Text).text)
    }

    @Test
    fun `same toolCallId re-emitted in the same run is a duplicate and is not re-executed`() = runTest {
        val executions = AtomicInteger(0)
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("tool-result"))
            },
        )
        // The model re-sends the SAME toolCallId after it already executed
        // successfully — a genuine repeat emission, so the second pass must
        // be signature-handled (skipped), not unconditionally re-executed.
        val engine = FakeRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "read_thing") },
                { toolCallAssistant("call_1", "read_thing") },
                { textAssistant("完成") },
            ),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("查一下")),
                tools = listOf(readOnly),
                terminals = terminals,
            ),
        ).toList()

        // Round 1 executes. Round 2: the counted callId routes through the
        // signature table (count 1) and gets the structured skip output; the
        // loop continues into round 3 where the model answers.
        assertEquals("the body only ever ran once", 1, executions.get())
        assertEquals(3, engine.requests.size)
        assertTrue(terminals.isEmpty())
        val roundTwoTool = engine.requests[2].messages.last().getTools().single()
        assertEquals("call_1", roundTwoTool.toolCallId)
        assertTrue(roundTwoTool.isExecuted)
        val skippedOutput = roundTwoTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(skippedOutput.contains("\"status\":\"skipped\""))
        assertTrue(skippedOutput.contains("\"reason\":\"duplicate_tool_call\""))
        assertTrue(skippedOutput.contains("\"occurrence\":2"))
        assertEquals("完成", (lastMessages(chunks).last().parts.last() as UIMessagePart.Text).text)
    }

    @Test
    fun `an approval-parked call resumes and executes in the follow-up run`() = runTest {
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
        // Run 1: the call parks at the approval gate (Pending) — the flow
        // ends there; resume is always a NEW run() invocation with fresh
        // guard tables, so the approved call must execute exactly once.
        val parkEngine = FakeRoundEngine(listOf({ toolCallAssistant("call_1", "write_thing") }))
        val firstRun = kernel(parkEngine).run(
            session(
                messages = listOf(UIMessage.user("写一下")),
                tools = listOf(guarded),
            ),
        ).toList()
        val pendingTool = lastMessages(firstRun).last().getTools().single()
        assertEquals(ToolApprovalState.Pending, pendingTool.approvalState)
        assertEquals(0, executions.get())

        // Run 2: the approval card flipped the call to Approved (as the UI
        // does); the resumed run executes it without tripping the guard.
        val approvedMessages = lastMessages(firstRun).map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == "call_1") {
                        part.copy(approvalState = ToolApprovalState.Approved)
                    } else {
                        part
                    }
                },
            )
        }
        val resumeEngine = FakeRoundEngine(listOf({ textAssistant("写完了") }))
        val chunks = kernel(resumeEngine).run(
            session(
                messages = approvedMessages,
                tools = listOf(guarded),
            ),
        ).toList()

        assertEquals("the resumed emission executed once", 1, executions.get())
        assertEquals(1, resumeEngine.requests.size)
        assertEquals("写完了", (lastMessages(chunks).last().parts.last() as UIMessagePart.Text).text)
    }

    @Test
    fun `a failed call may be retried with identical args without tripping the guard`() = runTest {
        val executions = AtomicInteger(0)
        val flaky = Tool(
            name = "flaky_thing",
            description = "fails on the first attempt",
            execute = {
                if (executions.incrementAndGet() == 1) error("boom")
                listOf(UIMessagePart.Text("ok"))
            },
        )
        val engine = FakeRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "flaky_thing") },
                { toolCallAssistant("call_2", "flaky_thing") },
                { textAssistant("重试成功") },
            ),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("试一下")),
                tools = listOf(flaky),
                terminals = terminals,
                settings = Settings(
                    agentRuntime = AgentRuntimeSetting(
                        generationRetry = GenerationRetrySetting(enabled = false),
                    ),
                ),
            ),
        ).toList()

        // The first execution failed (structured failure output), so it never
        // counts; the identical-args retry must run the body again.
        assertEquals(2, executions.get())
        assertEquals(3, engine.requests.size)
        assertTrue(terminals.isEmpty())
        assertEquals("重试成功", (lastMessages(chunks).last().parts.last() as UIMessagePart.Text).text)
    }

    @Test
    fun `same tool with different args is never blocked`() = runTest {
        val executions = AtomicInteger(0)
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("tool-result"))
            },
        )
        val engine = FakeRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "read_thing", input = """{"q":"a"}""") },
                { toolCallAssistant("call_2", "read_thing", input = """{"q":"b"}""") },
                { textAssistant("完成") },
            ),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("查两个")),
                tools = listOf(readOnly),
                terminals = terminals,
            ),
        ).toList()

        assertEquals("different digests are different signatures", 2, executions.get())
        assertTrue(terminals.isEmpty())
    }

    @Test
    fun `a fully denied batch continues to the next round instead of WaitingUser`() = runTest {
        val executions = AtomicInteger(0)
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("tool-result"))
            },
        )
        // Resume with the only pending call already denied by the user: the
        // batch executes to a structured denial (no execution), which must
        // keep the loop going — never park it at WaitingUser.
        val engine = FakeRoundEngine(
            listOf({ textAssistant("已按你的拒绝继续") }),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(
                    UIMessage.user("查一下"),
                    toolCallAssistant("call_0", "read_thing", approvalState = ToolApprovalState.Denied()),
                ),
                tools = listOf(readOnly),
                terminals = terminals,
            ),
        ).toList()

        assertEquals("the denied body never ran", 0, executions.get())
        assertEquals("the loop continued into a model round", 1, engine.requests.size)
        assertTrue("a fully denied batch must never report WaitingUser", terminals.isEmpty())
        val deniedTool = engine.requests[0].messages
            .flatMap { it.getTools() }
            .single { it.toolCallId == "call_0" }
        val deniedOutput = deniedTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(deniedOutput.contains("\"status\":\"denied\""))
    }

    @Test
    fun `narrowed session policy denies a shell tool at the boundary without executing it`() = runTest {
        val executions = AtomicInteger(0)
        val shell = Tool(
            name = "terminal_execute",
            description = "shell side effect",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("""{"status":"ok"}"""))
            },
        )
        val engine = FakeRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "terminal_execute") },
                { textAssistant("已按沙箱策略拒绝") },
            ),
        )

        val chunks = kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("跑一下命令")),
                tools = listOf(shell),
                executionPolicy = app.amber.feature.runtime.ExecutionPolicy(allowShell = false),
            ),
        ).toList()

        // The run completes normally; the tool body never ran and the model
        // received the structured denial instead.
        assertEquals(0, executions.get())
        assertEquals(2, engine.requests.size)
        val roundTwoTool = engine.requests[1].messages.last().getTools().single()
        assertTrue(roundTwoTool.isExecuted)
        val toolOutput = roundTwoTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(toolOutput.contains("\"status\":\"policy_denied\""))
        assertEquals("已按沙箱策略拒绝", (lastMessages(chunks).last().parts.last() as UIMessagePart.Text).text)
    }
}
