package app.amber.core.jev

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.context.PreparedContextEditor
import app.amber.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 压缩保留：窗口内后台判定、移出窗口按固定结果跳过清空；未判定即固定为清空。 */
class JevToolResultRetentionTest {

    private class FakeTransport(var probability: Double = 0.9) : JevTransport {
        var calls = 0
        var completed = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            gate?.await()
            completed++
            val ids = Regex("\"id\":\"(r\\d+)\"").findAll(request.body).map { it.groupValues[1] }.toList()
            val answers = ids.joinToString(",") { """"$it":{"type":"noul","noul":$probability}""" }
            return JevTransportResponse.Http(200, """{"model":"jev-test","answers":{$answers}}""".toByteArray(), null)
        }
    }

    private fun retention(mode: JevMode, transport: FakeTransport, scope: CoroutineScope): JevToolResultRetention {
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.TOOL_RESULT_RETENTION to mode),
                dataScopes = setOf(JevDataScope.TOOL_OUTPUT, JevDataScope.TASK_TEXT),
            ),
        )
        return JevToolResultRetention(
            JevRuntime(
                coordinator = JevDecisionCoordinator(
                    client = JevClient(transport = transport),
                    apiKeyProvider = { "key" },
                    clock = { 1_000_000L },
                ),
                settingsProvider = { settings },
                backgroundScope = scope,
            ),
        )
    }

    private val longOutput = "src/Main.kt line ".repeat(200)

    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistantText(text: String) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private val readCall = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call-read",
                toolName = "file_read",
                input = """{"path":"src/Main.kt"}""",
                output = listOf(UIMessagePart.Text(longOutput)),
            ),
        ),
    )

    /** 结果在保留窗口内（keepRecent=4）且其后已有 assistant 回复。 */
    private val inWindow = listOf(user("refactor Main.kt"), readCall, assistantText("I read it."), user("go on"))

    /** 再追加 4 条后，读取结果移出保留窗口。 */
    private val outOfWindow = inWindow + listOf(
        assistantText("step 1"), user("next"), assistantText("step 2"), user("next"),
    )

    private fun TestScope.cleared(retainedIds: Set<String>): Boolean {
        val edited = PreparedContextEditor.edit(outOfWindow, keepRecentMessages = 4, retainedToolCallIds = retainedIds)
        val tool = edited.messages[1].parts.single() as UIMessagePart.Tool
        return (tool.output.single() as UIMessagePart.Text).text.contains("cleared_tool_result")
    }

    @Test
    fun activeKeepDecisionSkipsClearingAfterLeavingWindow() = runTest {
        val transport = FakeTransport(probability = 0.9)
        val retention = retention(JevMode.ACTIVE, transport, backgroundScope)

        assertEquals(emptySet<String>(), retention.retainedToolCallIds(inWindow, "c1", 4, "run-1"))
        runCurrent()
        assertEquals(1, transport.calls)

        val retained = retention.retainedToolCallIds(outOfWindow, "c1", 4, "run-1")
        assertEquals(setOf("call-read"), retained)
        assertTrue("retained result keeps its text", !cleared(retained))
        assertTrue("without retention the same result is cleared", cleared(emptySet()))
    }

    @Test
    fun lowProbabilityIsClearedAsBefore() = runTest {
        val transport = FakeTransport(probability = 0.2)
        val retention = retention(JevMode.ACTIVE, transport, backgroundScope)
        retention.retainedToolCallIds(inWindow, "c1", 4, "run-1")
        runCurrent()
        assertEquals(emptySet<String>(), retention.retainedToolCallIds(outOfWindow, "c1", 4, "run-1"))
    }

    /** 移出窗口时仍未判定：固定为清空，迟到的"保留"不再翻转（保护 prompt 前缀）。 */
    @Test
    fun undecidedWhenLeavingWindowStaysClearedEvenIfAnswerArrivesLater() = runTest {
        val transport = FakeTransport(probability = 0.9).apply { gate = CompletableDeferred() }
        val retention = retention(JevMode.ACTIVE, transport, backgroundScope)
        retention.retainedToolCallIds(inWindow, "c1", 4, "run-1")
        runCurrent()
        assertEquals(emptySet<String>(), retention.retainedToolCallIds(outOfWindow, "c1", 4, "run-1"))

        transport.gate!!.complete(Unit)
        runCurrent()
        assertEquals("late answer did arrive", 1, transport.completed)
        assertEquals(emptySet<String>(), retention.retainedToolCallIds(outOfWindow, "c1", 4, "run-1"))
    }

    @Test
    fun shadowObservesButNeverRetains() = runTest {
        val transport = FakeTransport(probability = 0.9)
        val retention = retention(JevMode.SHADOW, transport, backgroundScope)
        retention.retainedToolCallIds(inWindow, "c1", 4, "run-1")
        runCurrent()
        assertEquals(1, transport.calls)
        assertEquals(emptySet<String>(), retention.retainedToolCallIds(outOfWindow, "c1", 4, "run-1"))
    }

    @Test
    fun offNeverCallsNetwork() = runTest {
        val transport = FakeTransport()
        val retention = retention(JevMode.OFF, transport, backgroundScope)
        retention.retainedToolCallIds(inWindow, "c1", 4, "run-1")
        runCurrent()
        assertEquals(0, transport.calls)
    }

    /** 模型还没对结果作出反应（其后没有 assistant 消息）时不判断。 */
    @Test
    fun resultWithoutLaterAssistantReplyIsNotJudged() = runTest {
        val transport = FakeTransport()
        val retention = retention(JevMode.ACTIVE, transport, backgroundScope)
        retention.retainedToolCallIds(listOf(user("refactor Main.kt"), readCall), "c1", 4, "run-1")
        runCurrent()
        assertEquals(0, transport.calls)
    }
}
