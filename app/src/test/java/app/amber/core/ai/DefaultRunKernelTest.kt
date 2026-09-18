package app.amber.core.ai

import android.content.Context
import android.content.res.Configuration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.ResponsesResumeRequest
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.ToolApprovalState
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Settings
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.runtime.ToolEffectStatus
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
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loop-policy unit tests for [DefaultRunKernel] with a scripted
 * [GenerationRoundEngine] — the seam that makes the tool loop testable
 * without any provider streaming. The focused durable mixed-batch regression
 * reuses the real Room ledger; broader runtime wiring stays in canaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DefaultRunKernelTest : DurableRuntimeTestBase() {

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

    @Test
    fun `high risk auto approval executes consecutive SSH and file calls without a user pause`() = runTest {
        val executions = mutableListOf<String>()
        val tools = listOf("terminal_execute", "file_write").map { name ->
            Tool(
                name = name,
                description = "approval-required test tool",
                needsApproval = true,
                allowsAutoApproval = false,
                execute = {
                    executions += name
                    listOf(UIMessagePart.Text("done"))
                },
            )
        }
        val engine = FakeRoundEngine(listOf(
            { toolCallAssistant("ssh_1", "terminal_execute", """{"runtime":"remote_ssh","command":"pwd"}""") },
            { toolCallAssistant("write_1", "file_write") },
            { textAssistant("已完成") },
        ))
        val terminals = mutableListOf<GenerationTerminal>()
        val chunks = kernel(engine).run(
            session(
                listOf(UIMessage.user("执行任务")), tools, terminals = terminals,
                autoApproveHighRiskTools = true,
            ),
        ).toList()

        assertEquals(listOf("terminal_execute", "file_write"), executions)
        assertEquals(3, engine.requests.size)
        assertTrue(terminals.isEmpty())
        assertTrue(chunks.filterIsInstance<GenerationChunk.Messages>().none { chunk ->
            chunk.messages.any { message -> message.getTools().any { it.isPending } }
        })
    }

    private fun kernel(engine: GenerationRoundEngine): DefaultRunKernel = DefaultRunKernel(
        context = testContext(),
        toolDispatcher = AgentToolDispatcher(json, PermissionDecisionResolver()),
        roundEngine = engine,
    )

    private fun durableKernel(
        engine: GenerationRoundEngine,
        flags: CapabilityFlags,
    ): DefaultRunKernel = DefaultRunKernel(
        context = testContext(),
        toolDispatcher = AgentToolDispatcher(json, PermissionDecisionResolver()),
        roundEngine = engine,
        toolEffectLedger = ledger,
        capabilityFlags = flags,
    )

    private fun durableFlags(): CapabilityFlags = CapabilityFlags(
        PreferenceDataStoreFactory.create {
            File(context.cacheDir, "kernel-mixed-batch-flags-${System.nanoTime()}.preferences_pb")
        },
    )

    /** Keep legacy copy assertions deterministic while production follows the app locale. */
    private fun testContext(): Context {
        val application = RuntimeEnvironment.getApplication()
        return application.createConfigurationContext(
            Configuration(application.resources.configuration).apply {
                setLocale(Locale.SIMPLIFIED_CHINESE)
            },
        )
    }

    private fun session(
        messages: List<UIMessage>,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 8,
        terminals: MutableList<GenerationTerminal> = mutableListOf(),
        steer: List<UIMessage> = emptyList(),
        executionPolicy: app.amber.feature.runtime.ExecutionPolicy =
            app.amber.feature.runtime.ExecutionPolicy.permissive(),
        settings: Settings = Settings(),
        responsesResume: ResponsesResumeRequest? = null,
        autoApproveHighRiskTools: Boolean = false,
        runId: String? = null,
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
            responsesResume = responsesResume,
            autoApproveHighRiskTools = autoApproveHighRiskTools,
            runId = runId,
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
    fun `stored response cursor is attached only to the first model round`() = runTest {
        val resume = ResponsesResumeRequest(
            runId = "run_resume",
            store = object : ResponseResumeStore {
                override suspend fun save(runId: String, responseId: String, sequence: Long, providerId: String) = Unit
                override suspend fun load(runId: String): ResponseCursor? = null
                override suspend fun clear(runId: String) = Unit
            },
            resumeFrom = ResponseCursor(
                responseId = "response_1",
                sequence = 7,
                providerId = "provider_1",
            ),
        )
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
                messages = listOf(UIMessage.user("继续")),
                tools = listOf(readOnly),
                responsesResume = resume,
            ),
        ).toList()

        assertEquals(resume.resumeFrom, engine.requests[0].responsesResume?.resumeFrom)
        assertEquals(resume.runId, engine.requests[1].responsesResume?.runId)
        assertNull(engine.requests[1].responsesResume?.resumeFrom)
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
    fun `high risk auto approval rechecks the whole persisted mixed batch`() = runTest {
        val sshExecutions = AtomicInteger(0)
        val readExecutions = AtomicInteger(0)
        val executionOrder = mutableListOf<String>()
        val ssh = Tool(
            name = "terminal_execute",
            description = "SSH command",
            needsApproval = true,
            allowsAutoApproval = false,
            execute = {
                sshExecutions.incrementAndGet()
                executionOrder += "terminal_execute"
                listOf(UIMessagePart.Text("ssh-ok"))
            },
        )
        val read = Tool(
            name = "read_thing",
            description = "read-only sibling",
            execute = {
                readExecutions.incrementAndGet()
                executionOrder += "read_thing"
                listOf(UIMessagePart.Text("read-ok"))
            },
        )
        val engine = FakeRoundEngine(listOf({ textAssistant("继续完成") }))
        val terminals = mutableListOf<GenerationTerminal>()
        val chunks = kernel(engine).run(
            session(
                messages = listOf(
                    UIMessage.user("检查 SSH"),
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "read-deferred",
                                toolName = "read_thing",
                                input = "{}",
                            ),
                            UIMessagePart.Tool(
                                toolCallId = "ssh-pending",
                                toolName = "terminal_execute",
                                input = """{"runtime":"remote_ssh","command":"pwd"}""",
                                approvalState = ToolApprovalState.Pending,
                            ),
                        ),
                    ),
                ),
                tools = listOf(read, ssh),
                terminals = terminals,
                autoApproveHighRiskTools = true,
            ),
        ).toList()

        assertEquals(1, sshExecutions.get())
        assertEquals(1, readExecutions.get())
        assertEquals(listOf("read_thing", "terminal_execute"), executionOrder)
        assertEquals("the resumed batch goes through one fresh model round", 1, engine.requests.size)
        assertTrue(terminals.isEmpty())
        val resumedTools = engine.requests.single().messages.last().getTools()
        assertTrue(resumedTools.all { it.isExecuted })
        assertTrue(
            "the kernel must not forge a user Approved state",
            resumedTools.single { it.toolCallId == "ssh-pending" }.approvalState != ToolApprovalState.Approved,
        )
        assertEquals("继续完成", (lastMessages(chunks).last().parts.last() as UIMessagePart.Text).text)
    }

    @Test
    fun `high risk auto approval waits for a human answer before releasing a pending batch`() = runTest {
        val executions = AtomicInteger(0)
        val askUser = Tool(
            name = "ask_user",
            description = "human answer",
            needsApproval = true,
            allowsAutoApproval = false,
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("must not execute"))
            },
        )
        val engine = FakeRoundEngine(emptyList())
        val terminals = mutableListOf<GenerationTerminal>()
        val chunks = kernel(engine).run(
            session(
                messages = listOf(
                    UIMessage.user("询问用户"),
                    toolCallAssistant(
                        callId = "ask-pending",
                        toolName = "ask_user",
                        input = """{"question":"继续吗？"}""",
                        approvalState = ToolApprovalState.Pending,
                    ).let { message ->
                        message.copy(parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "ssh-pending",
                                toolName = "terminal_execute",
                                input = """{"runtime":"remote_ssh","command":"pwd"}""",
                                approvalState = ToolApprovalState.Pending,
                            ),
                        ) + message.parts)
                    },
                ),
                tools = listOf(askUser, askUser.copy(name = "terminal_execute")),
                terminals = terminals,
                autoApproveHighRiskTools = true,
            ),
        ).toList()

        assertEquals(0, executions.get())
        assertEquals(0, engine.requests.size)
        assertEquals(listOf(GenerationTerminal.WaitingUser), terminals)
        val pausedTools = lastMessages(chunks).last().getTools().associateBy { it.toolCallId }
        assertEquals(
            "the high-risk release remains Auto so it is rechecked after the human answer",
            ToolApprovalState.Auto,
            pausedTools.getValue("ssh-pending").approvalState,
        )
        assertEquals(ToolApprovalState.Pending, pausedTools.getValue("ask-pending").approvalState)
    }

    @Test
    fun `truncated reply with a tool call executes nothing and settles as OutputLimit`() = runTest {
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
        // channel and the run settles as OUTPUT_LIMIT — a truncation is not a
        // completion the caller could map to COMPLETED.
        assertEquals(0, executions.get())
        assertEquals(1, engine.requests.size)
        assertEquals(listOf(GenerationTerminal.OutputLimit), terminals)
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

        assertEquals(listOf(GenerationTerminal.OutputLimit), terminals)
        val parts = lastMessages(chunks).last().parts
        assertEquals("写到一半的回答", (parts.first() as UIMessagePart.Text).text)
        assertEquals("模型回复达到输出上限，请重试。", (parts.last() as UIMessagePart.Text).text)
    }

    @Test
    fun `fresh wait calls keep observing until the job completes`() = runTest {
        val observations = mutableListOf<String>()
        val wait = Tool(
            name = "terminal_job_wait",
            description = "observe a running job",
            execute = {
                val status = if (observations.size < 2) "running" else "completed"
                observations += status
                listOf(UIMessagePart.Text("""{"status":"$status"}"""))
            },
        )
        val engine = FakeRoundEngine(listOf(
            { toolCallAssistant("wait_1", wait.name, """{"job_id":"job_1","timeout_ms":60000}""") },
            { toolCallAssistant("wait_2", wait.name, """{"job_id":"job_1","timeout_ms":60000}""") },
            { toolCallAssistant("wait_3", wait.name, """{"job_id":"job_1","timeout_ms":60000}""") },
            { textAssistant("任务已完成") },
        ))
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(session(
            listOf(UIMessage.user("等待任务完成")), listOf(wait), terminals = terminals,
        )).toList()

        assertEquals(listOf("running", "running", "completed"), observations)
        assertTrue(terminals.isEmpty())
        assertEquals("任务已完成", (lastMessages(chunks).last().parts.last() as UIMessagePart.Text).text)
    }

    @Test
    fun `reading a file after editing it observes the new contents`() = runTest {
        var contents = "before"
        val reads = mutableListOf<String>()
        val read = Tool(name = "file_read", description = "read file", execute = {
            reads += contents
            listOf(UIMessagePart.Text(contents))
        })
        val edit = Tool(name = "file_edit", description = "edit file", execute = {
            contents = "after"
            listOf(UIMessagePart.Text("edited"))
        })
        val engine = FakeRoundEngine(listOf(
            { toolCallAssistant("read_1", read.name, """{"path":"/workspace/a.txt"}""") },
            { toolCallAssistant("edit_1", edit.name) },
            { toolCallAssistant("read_2", read.name, """{"path":"/workspace/a.txt"}""") },
            { textAssistant("验证完成") },
        ))

        kernel(engine).run(session(
            listOf(UIMessage.user("修改文件并检查")), listOf(read, edit), autoApproveHighRiskTools = true,
        )).toList()

        assertEquals(listOf("before", "after"), reads)
        val verification = engine.requests.last().messages.last().getTools().single()
        assertEquals("after", verification.output.filterIsInstance<UIMessagePart.Text>().single().text)
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
        // Round 3: stopped (occurrence 3) with the guard note; the stop is a
        // real terminal (GUARD_STOPPED), not a silent completion. The tool
        // body only ever ran once.
        assertEquals("only the first emission ever executed", 1, executions.get())
        assertEquals(3, engine.requests.size)
        assertEquals(
            "the guard stop reports its terminal, never a silent COMPLETED",
            listOf(GenerationTerminal.GuardStopped("duplicate_tool_call")),
            terminals,
        )

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
    fun `cross-step identical screen automation calls all execute without tripping the guard`() = runTest {
        val executions = AtomicInteger(0)
        val swipe = Tool(
            name = "screen_swipe",
            description = "swipe the screen",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("swiped"))
            },
        )
        // 长任务常态：连续多步以相同参数滑动，每次调用后屏幕状态已变化。
        val engine = FakeRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "screen_swipe") },
                { toolCallAssistant("call_2", "screen_swipe") },
                { toolCallAssistant("call_3", "screen_swipe") },
                { toolCallAssistant("call_4", "screen_swipe") },
                { textAssistant("完成") },
            ),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("一直滑到底")),
                tools = listOf(swipe),
                terminals = terminals,
            ),
        ).toList()

        assertEquals("every cross-step emission executed", 4, executions.get())
        assertEquals("no guard stop, the loop ran into the final answer round", 5, engine.requests.size)
        assertTrue("natural completion, no terminal signal", terminals.isEmpty())
    }

    @Test
    fun `same-batch twins of a screen automation tool still dedupe`() = runTest {
        val executions = AtomicInteger(0)
        val click = Tool(
            name = "screen_click",
            description = "click the screen",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("clicked"))
            },
        )
        val engine = FakeRoundEngine(
            listOf(
                { _: List<UIMessage> ->
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(toolCallId = "call_1", toolName = "screen_click", input = "{}"),
                            UIMessagePart.Tool(toolCallId = "call_2", toolName = "screen_click", input = "{}"),
                        ),
                    )
                },
                { textAssistant("完成") },
            ),
        )
        val terminals = mutableListOf<GenerationTerminal>()

        kernel(engine).run(
            session(
                messages = listOf(UIMessage.user("点两下")),
                tools = listOf(click),
                terminals = terminals,
            ),
        ).toList()

        assertEquals("only the first in-batch emission executed", 1, executions.get())
        assertTrue("a skipped twin is not a stop and not a park", terminals.isEmpty())
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
    fun `a third same-signature call inside one batch executes nothing and stops the run`() = runTest {
        val executions = AtomicInteger(0)
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("tool-result"))
            },
        )
        // ONE assistant message carrying three same-signature calls: the
        // first executes, the second is skipped as the in-batch twin, and the
        // third must STOP the run (occurrence 3) — not skip forever.
        val engine = FakeRoundEngine(
            listOf(
                { _: List<UIMessage> ->
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(toolCallId = "call_1", toolName = "read_thing", input = "{}"),
                            UIMessagePart.Tool(toolCallId = "call_2", toolName = "read_thing", input = "{}"),
                            UIMessagePart.Tool(toolCallId = "call_3", toolName = "read_thing", input = "{}"),
                        ),
                    )
                },
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

        assertEquals("only the first in-batch emission ever executed", 1, executions.get())
        assertEquals("the run stopped before any further model round", 1, engine.requests.size)
        assertEquals(
            listOf(GenerationTerminal.GuardStopped("duplicate_tool_call")),
            terminals,
        )

        val lastTools = lastMessages(chunks).last().getTools()
        assertEquals(3, lastTools.size)
        val executed = lastTools.single { it.toolCallId == "call_1" }
        assertEquals("tool-result", executed.output.filterIsInstance<UIMessagePart.Text>().single().text)
        // The in-batch twin never reached executeBatch; its structured skip
        // output is merged with the batch results before the stop lands.
        val skipped = lastTools.single { it.toolCallId == "call_2" }
        val skippedOutput = skipped.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(skippedOutput.contains("\"status\":\"skipped\""))
        assertTrue(skippedOutput.contains("\"occurrence\":2"))
        assertTrue(!skipped.output.any { p -> p is UIMessagePart.Text && p.text == "tool-result" })
        val stopped = lastTools.single { it.toolCallId == "call_3" }
        val stoppedOutput = stopped.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(stoppedOutput.contains("\"status\":\"skipped\""))
        assertTrue(stoppedOutput.contains("\"occurrence\":3"))
        val note = lastMessages(chunks).last().parts.filterIsInstance<UIMessagePart.Text>().last()
        assertEquals("检测到重复的工具调用，已停止本轮以避免死循环。", note.text)
    }

    @Test
    fun `an in-batch twin after an already counted execution is the third occurrence and stops`() = runTest {
        val executions = AtomicInteger(0)
        val readOnly = Tool(
            name = "read_thing",
            description = "read-only lookup",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("tool-result"))
            },
        )
        // Round 1: call_1 executes (run-level count 1). Round 2: ONE message
        // with two more same-signature calls — occurrence 2 (skip) then
        // occurrence 3 (stop), so a repeated batch cannot dodge the guard by
        // pairing a fresh twin with its skip.
        val engine = FakeRoundEngine(
            listOf(
                { toolCallAssistant("call_1", "read_thing") },
                { _: List<UIMessage> ->
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(toolCallId = "call_2", toolName = "read_thing", input = "{}"),
                            UIMessagePart.Tool(toolCallId = "call_3", toolName = "read_thing", input = "{}"),
                        ),
                    )
                },
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

        assertEquals(1, executions.get())
        assertEquals(2, engine.requests.size)
        assertEquals(
            listOf(GenerationTerminal.GuardStopped("duplicate_tool_call")),
            terminals,
        )
        val lastTools = lastMessages(chunks).last().getTools()
        val secondCall = lastTools.single { it.toolCallId == "call_2" }
        val skippedOutput = secondCall.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(
            "the occurrence-2 twin carries the skip reminder, never a result",
            skippedOutput.contains("\"occurrence\":2") && !secondCall.output.any { p -> p is UIMessagePart.Text && p.text == "tool-result" },
        )
        val thirdCall = lastTools.single { it.toolCallId == "call_3" }
        val stoppedOutput = thirdCall.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(stoppedOutput.contains("\"occurrence\":3"))
    }

    @Test
    fun `same toolCallId re-emitted in the same run is a duplicate and is not re-executed`() = runTest {
        val executions = AtomicInteger(0)
        val readOnly = Tool(
            name = "file_read",
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
                { toolCallAssistant("call_1", "file_read") },
                { toolCallAssistant("call_1", "file_read") },
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
    fun `approval resume dispatches deferred auto siblings from the same batch`() = runTest {
        val readExecutions = AtomicInteger(0)
        val writeExecutions = AtomicInteger(0)
        val read = Tool(
            name = "read_thing",
            description = "read-only sibling",
            execute = {
                readExecutions.incrementAndGet()
                listOf(UIMessagePart.Text("read"))
            },
        )
        val write = Tool(
            name = "write_thing",
            description = "approval sibling",
            needsApproval = true,
            execute = {
                writeExecutions.incrementAndGet()
                listOf(UIMessagePart.Text("written"))
            },
        )
        val parkEngine = FakeRoundEngine(
            listOf({
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool("read_1", "read_thing", "{}"),
                        UIMessagePart.Tool("write_1", "write_thing", "{}"),
                    ),
                )
            }),
        )
        val firstRun = kernel(parkEngine).run(
            session(
                messages = listOf(UIMessage.user("读取后写入")),
                tools = listOf(read, write),
            ),
        ).toList()
        val parkedTools = lastMessages(firstRun).last().getTools().associateBy { it.toolCallId }
        assertEquals(ToolApprovalState.Auto, parkedTools.getValue("read_1").approvalState)
        assertEquals(ToolApprovalState.Pending, parkedTools.getValue("write_1").approvalState)

        val approvedMessages = lastMessages(firstRun).map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == "write_1") {
                        part.copy(approvalState = ToolApprovalState.Approved)
                    } else {
                        part
                    }
                },
            )
        }
        val resumeEngine = FakeRoundEngine(listOf({ textAssistant("完成") }))
        kernel(resumeEngine).run(
            session(messages = approvedMessages, tools = listOf(read, write)),
        ).toList()

        assertEquals(1, readExecutions.get())
        assertEquals(1, writeExecutions.get())
        val resumedTools = resumeEngine.requests.single().messages.last().getTools()
        assertTrue(resumedTools.all { it.isExecuted })
    }

    @Test
    fun `durable mixed approval resume finalizes every prepared effect`() = runTest {
        val readExecutions = AtomicInteger(0)
        val writeExecutions = AtomicInteger(0)
        val read = Tool(
            name = "file_read",
            description = "read-only sibling",
            execute = {
                readExecutions.incrementAndGet()
                listOf(UIMessagePart.Text("read"))
            },
        )
        val write = Tool(
            name = "file_write",
            description = "approval sibling",
            needsApproval = true,
            execute = {
                writeExecutions.incrementAndGet()
                listOf(UIMessagePart.Text("written"))
            },
        )
        val runId = "run_durable_mixed_approval"
        val flags = durableFlags()
        val parkEngine = FakeRoundEngine(
            listOf({
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool("read_1", "file_read", "{}"),
                        UIMessagePart.Tool("write_1", "file_write", "{}"),
                    ),
                )
            }),
        )
        val firstRun = durableKernel(parkEngine, flags).run(
            session(
                messages = listOf(UIMessage.user("读取后写入")),
                tools = listOf(read, write),
                runId = runId,
            ),
        ).toList()

        assertEquals(ToolEffectStatus.PREPARED, ledger.getByToolCallId("read_1")!!.status)
        assertEquals(ToolEffectStatus.PREPARED, ledger.getByToolCallId("write_1")!!.status)
        val approvedMessages = lastMessages(firstRun).map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == "write_1") {
                        part.copy(approvalState = ToolApprovalState.Approved)
                    } else {
                        part
                    }
                },
            )
        }
        val resumeEngine = FakeRoundEngine(listOf({ textAssistant("完成") }))
        durableKernel(resumeEngine, flags).run(
            session(
                messages = approvedMessages,
                tools = listOf(read, write),
                runId = runId,
            ),
        ).toList()

        assertEquals(1, readExecutions.get())
        assertEquals(1, writeExecutions.get())
        assertEquals(ToolEffectStatus.FINISHED, ledger.getByToolCallId("read_1")!!.status)
        assertEquals(ToolEffectStatus.FINISHED, ledger.getByToolCallId("write_1")!!.status)
    }

    @Test
    fun `deferred auto tool that becomes approval gated keeps the mixed batch waiting`() = runTest {
        val approvedExecutions = AtomicInteger(0)
        val deferredExecutions = AtomicInteger(0)
        val approved = Tool(
            name = "write_thing",
            description = "already approved sibling",
            needsApproval = true,
            execute = {
                approvedExecutions.incrementAndGet()
                listOf(UIMessagePart.Text("written"))
            },
        )
        val deferred = Tool(
            name = "http_request",
            description = "now policy-gated sibling",
            execute = {
                deferredExecutions.incrementAndGet()
                listOf(UIMessagePart.Text("must not run"))
            },
        )
        val engine = FakeRoundEngine(emptyList())
        val terminals = mutableListOf<GenerationTerminal>()

        val chunks = kernel(engine).run(
            session(
                messages = listOf(
                    UIMessage.user("继续"),
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "write_approved",
                                toolName = "write_thing",
                                input = "{}",
                                approvalState = ToolApprovalState.Approved,
                            ),
                            UIMessagePart.Tool(
                                toolCallId = "post_deferred",
                                toolName = "http_request",
                                input = """{"method":"POST","url":"https://example.com"}""",
                            ),
                        ),
                    ),
                ),
                tools = listOf(approved, deferred),
                terminals = terminals,
            ),
        ).toList()

        assertEquals(0, approvedExecutions.get())
        assertEquals(0, deferredExecutions.get())
        assertEquals(0, engine.requests.size)
        assertEquals(listOf(GenerationTerminal.WaitingUser), terminals)
        val tools = lastMessages(chunks).last().getTools().associateBy { it.toolCallId }
        assertEquals(ToolApprovalState.Approved, tools.getValue("write_approved").approvalState)
        assertEquals(ToolApprovalState.Pending, tools.getValue("post_deferred").approvalState)
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
