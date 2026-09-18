package app.amber.core.jev

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长工具结果语义投影：切分/打包/保留信号 + active 隐藏 + shadow/off 回退。 */
class JevToolOutputProjectorTest {

    private class FakeTransport(var responder: suspend (JevHttpRequest) -> JevTransportResponse) : JevTransport {
        var calls = 0

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            return responder(request)
        }
    }

    private fun runtime(
        transport: FakeTransport,
        mode: JevMode,
    ): JevRuntime {
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.CONTEXT_SELECTION to mode),
                dataScopes = setOf(JevDataScope.TOOL_OUTPUT, JevDataScope.TASK_TEXT),
            ),
        )
        return JevRuntime(
            coordinator = JevDecisionCoordinator(
                client = JevClient(transport = transport),
                apiKeyProvider = { "key" },
                clock = { 1_000_000L },
            ),
            settingsProvider = { settings },
        )
    }

    private fun paragraph(prefix: String, filler: String = "filler content for length purposes. ") =
        prefix + filler.repeat(120)

    private fun longToolOutput(): UIMessagePart.Tool {
        val relevant = paragraph("The login page shows a large hero banner and a centered sign-in form. ")
        val irrelevant1 = paragraph("Weather station data for the pacific region. ")
        val mustKeep = paragraph("error: request failed with status 403 permission denied. ")
        val irrelevant2 = paragraph("Historical stock prices of unrelated companies. ")
        return UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "screen_read_ui",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(listOf(relevant, irrelevant1, mustKeep, irrelevant2).joinToString("\n\n")),
            ),
        )
    }

    private fun message(tool: UIMessagePart.Tool) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(tool),
    )

    private fun taskMessage() = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text("summarize the login page layout")),
    )

    private fun successAnswer(vararg probabilities: Pair<String, Double>) = JevTransportResponse.Http(
        200,
        buildString {
            append("""{"model":"jev-test","answers":{""")
            probabilities.joinTo(this) { (id, p) -> """"$id":{"type":"noul","noul":$p}""" }
            append("}}")
        }.toByteArray(),
        null,
    )

    @Test
    fun activeHidesIrrelevantKeepsRelevantAndMustKeep() = runTest {
        val transport = FakeTransport {
            successAnswer("0" to 0.9, "1" to 0.05, "2" to 0.05, "3" to 0.05)
        }
        val projector = JevToolOutputProjector(runtime(transport, JevMode.ACTIVE))
        val result = projector.projectMessages(listOf(taskMessage(), message(longToolOutput())), runKey = "run-1")
        val tool = result.last().parts.first() as UIMessagePart.Tool
        val text = (tool.output.single() as UIMessagePart.Text).text
        assertTrue("relevant block kept", text.contains("sign-in form"))
        assertTrue("must-keep block kept", text.contains("permission denied"))
        assertTrue("omission marker present", text.contains("omitted by context filter"))
        assertFalse("irrelevant block hidden", text.contains("pacific region"))
        assertEquals(1, transport.calls)
    }

    @Test
    fun shadowLeavesMessagesUnchanged() = runTest {
        val transport = FakeTransport { successAnswer("0" to 0.9, "1" to 0.05, "2" to 0.05, "3" to 0.05) }
        val projector = JevToolOutputProjector(runtime(transport, JevMode.SHADOW))
        val original = listOf(taskMessage(), message(longToolOutput()))
        val result = projector.projectMessages(original, runKey = "run-1")
        assertEquals(original, result)
        assertEquals(1, transport.calls)
    }

    @Test
    fun offModeNeverCallsNetwork() = runTest {
        val transport = FakeTransport { successAnswer("0" to 0.9) }
        val projector = JevToolOutputProjector(runtime(transport, JevMode.OFF))
        val original = listOf(taskMessage(), message(longToolOutput()))
        assertEquals(original, projector.projectMessages(original, runKey = "run-1"))
        assertEquals(0, transport.calls)
    }

    @Test
    fun shortOutputUntouched() = runTest {
        val transport = FakeTransport { successAnswer("0" to 0.9) }
        val projector = JevToolOutputProjector(runtime(transport, JevMode.ACTIVE))
        val shortTool = UIMessagePart.Tool(
            toolCallId = "c2",
            toolName = "file_read",
            input = "{}",
            output = listOf(UIMessagePart.Text("short content")),
        )
        val original = listOf(taskMessage(), message(shortTool))
        assertEquals(original, projector.projectMessages(original, runKey = null))
        assertEquals(0, transport.calls)
    }

    @Test
    fun errorSignalIsKeptPerBlockWhileIrrelevantHidden() = runTest {
        val transport = FakeTransport { successAnswer("0" to 0.05, "1" to 0.05) }
        val projector = JevToolOutputProjector(runtime(transport, JevMode.ACTIVE))
        val failed = UIMessagePart.Tool(
            toolCallId = "c3",
            toolName = "terminal_execute",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    "error: crashed with permission denied\n\n" + "plain filler. ".repeat(650),
                ),
            ),
        )
        val result = projector.projectMessages(listOf(taskMessage(), message(failed)), runKey = null)
        val tool = result.last().parts.first() as UIMessagePart.Tool
        val text = (tool.output.single() as UIMessagePart.Text).text
        assertTrue("error block kept by must-keep", text.contains("permission denied"))
        assertTrue(text.contains("omitted by context filter"))
        assertEquals(1, transport.calls)
    }

    @Test
    fun toolLevelFailureSignalSkipsProjectionEntirely() = runTest {
        val transport = FakeTransport { successAnswer("0" to 0.05) }
        val projector = JevToolOutputProjector(runtime(transport, JevMode.ACTIVE))
        // JSON 状态级失败信号（与 PreparedContextEditor 同款模式）：整体不筛
        val failed = UIMessagePart.Tool(
            toolCallId = "c9",
            toolName = "wm_eval",
            input = "{}",
            output = listOf(UIMessagePart.Text("\"status\":\"failed\" diagnostics " + "x".repeat(9_000))),
        )
        val original = listOf(taskMessage(), message(failed))
        assertEquals(original, projector.projectMessages(original, runKey = null))
        assertEquals(0, transport.calls)
    }

    @Test
    fun oversizeBundleIsKeptWithoutJudging() = runTest {
        val transport = FakeTransport { successAnswer("0" to 0.05, "1" to 0.05) }
        val projector = JevToolOutputProjector(runtime(transport, JevMode.ACTIVE))
        // 2 包 → 判题前缀 8k；块0 30k 超前缀 2 倍（大半不可见）→ 整包保留；块1 判为无关被隐藏
        val bigBlock = "x".repeat(30_000)
        val smallBlock = "small filler paragraph"
        val tool = UIMessagePart.Tool(
            toolCallId = "c10",
            toolName = "file_read",
            input = "{}",
            output = listOf(UIMessagePart.Text(listOf(bigBlock, smallBlock).joinToString("\n\n"))),
        )
        val result = projector.projectMessages(listOf(taskMessage(), message(tool)), runKey = null)
        val text = ((result.last().parts.first() as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text
        assertTrue("oversize bundle kept", text.contains("x".repeat(30_000).take(100)))
        assertTrue(text.contains("omitted by context filter"))
        assertFalse(text.contains("small filler paragraph"))
    }

    // ---- 纯函数 ----

    @Test
    fun splitBlocksKeepsFencedCodeIntact() {
        val text = "intro paragraph\n\n```json\n{\"a\": 1,\n\"b\": 2}\n```\n\ntrailing paragraph"
        val blocks = JevToolOutputProjector.splitBlocks(text)
        assertEquals(3, blocks.size)
        assertTrue(blocks[1].contains("```json") && blocks[1].contains("\"b\": 2"))
    }

    @Test
    fun bundleBlocksFlagsMustKeep() {
        val blocks = List(6) { index -> "block $index ${"x".repeat(700)}" + if (index == 3) " error happened" else "" }
        val bundles = JevToolOutputProjector.bundleBlocks(blocks)
        assertTrue(bundles.size in 2..6)
        assertTrue(bundles.any { it.mustKeep })
    }

    @Test
    fun mustKeepMarkers() {
        assertTrue(JevToolOutputProjector.isMustKeepBlock("next_page_token: abc123"))
        assertTrue(JevToolOutputProjector.isMustKeepBlock("Approval: required before continuing"))
        assertFalse(JevToolOutputProjector.isMustKeepBlock("lorem ipsum dolor sit amet"))
    }
}
