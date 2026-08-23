package app.amber.core.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedContextEditorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun clearsOldRetriableToolResultsWithoutMutatingOriginalMessage() {
        val old = message(
            tool(
                name = "file_read",
                output = "x".repeat(30_000),
            )
        )
        val recent = message(UIMessagePart.Text("recent user-visible context"))

        val result = PreparedContextEditor.edit(
            messages = listOf(old, recent),
            keepRecentMessages = 1,
        )

        val editedTool = result.messages.first().parts.single() as UIMessagePart.Tool
        val payload = json.parseToJsonElement((editedTool.output.single() as UIMessagePart.Text).text).jsonObject

        assertEquals("cleared_tool_result", payload["status"]!!.jsonPrimitive.content)
        assertEquals("file_read", payload["tool_name"]!!.jsonPrimitive.content)
        assertEquals(30_000, ((old.parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text.length)
        assertEquals(listOf("trim", "clear"), result.trace.steps.map { it.stage })
        assertTrue(result.trace.steps.sumOf { it.savedTokens } > 0)
    }

    @Test
    fun keepsRecentToolResults() {
        val old = message(UIMessagePart.Text("old"))
        val recent = message(tool(name = "file_read", output = "x".repeat(30_000)))

        val result = PreparedContextEditor.edit(
            messages = listOf(old, recent),
            keepRecentMessages = 1,
        )

        val editedTool = result.messages.last().parts.single() as UIMessagePart.Tool
        assertEquals(30_000, (editedTool.output.single() as UIMessagePart.Text).text.length)
    }

    @Test
    fun keepsFailedAndPendingToolResults() {
        val failed = message(tool(name = "file_read", output = """{"status":"failed","error":"boom"}"""))
        val pending = message(
            UIMessagePart.Tool(
                toolCallId = "call-pending",
                toolName = "file_read",
                input = "{}",
                output = emptyList(),
                approvalState = ToolApprovalState.Pending,
            )
        )

        val result = PreparedContextEditor.edit(
            messages = listOf(failed, pending, message(UIMessagePart.Text("recent"))),
            keepRecentMessages = 1,
        )

        val failedTool = result.messages[0].parts.single() as UIMessagePart.Tool
        val pendingTool = result.messages[1].parts.single() as UIMessagePart.Tool
        assertEquals("""{"status":"failed","error":"boom"}""", (failedTool.output.single() as UIMessagePart.Text).text)
        assertTrue(pendingTool.output.isEmpty())
    }

    @Test
    fun trimsButDoesNotClearSensitiveSessionReads() {
        val old = message(tool(name = "session_read", output = "x".repeat(30_000)))

        val result = PreparedContextEditor.edit(
            messages = listOf(old, message(UIMessagePart.Text("recent"))),
            keepRecentMessages = 1,
        )

        val editedTool = result.messages.first().parts.single() as UIMessagePart.Tool
        val payload = json.parseToJsonElement((editedTool.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("trimmed_tool_result", payload["status"]!!.jsonPrimitive.content)
        assertEquals("session_read", payload["tool_name"]!!.jsonPrimitive.content)
    }

    private fun message(part: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(part),
    )

    private fun tool(
        name: String,
        output: String,
    ) = UIMessagePart.Tool(
        toolCallId = "call-$name",
        toolName = name,
        input = """{"path":"notes/example.md"}""",
        output = listOf(UIMessagePart.Text(output)),
    )
}
