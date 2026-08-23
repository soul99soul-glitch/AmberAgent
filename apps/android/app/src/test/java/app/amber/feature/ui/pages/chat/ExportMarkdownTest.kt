package app.amber.feature.ui.pages.chat

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 对话导出 Markdown 的脱敏契约：工具参数经 maskSensitiveJson 处理，
 * api_key / token 等敏感字段在导出文件中不出现明文，只出现掩码值；
 * 普通字段原样保留。
 */
class ExportMarkdownTest {

    private fun markdownFor(toolInput: String): String {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            title = "Security Export Test",
            messageNodes = emptyList(),
        )
        val toolPart = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "provider_config_apply",
            input = toolInput,
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(toolPart))
        return buildMarkdownExport(conversation, listOf(message))
    }

    @Test
    fun `tool input api_key and token are masked in exported markdown`() {
        val markdown = markdownFor(
            """{"provider_id":"p-1","api_key":"sk-plain-secret-9876","token":"tok-secret-1234"}"""
        )
        assertFalse("plain api_key must not leak into the export", markdown.contains("sk-plain-secret-9876"))
        assertFalse("plain token must not leak into the export", markdown.contains("tok-secret-1234"))
        assertTrue("masked api_key suffix must be present", markdown.contains("••••9876"))
        assertTrue("masked token suffix must be present", markdown.contains("••••1234"))
        assertTrue("non-sensitive fields must survive", markdown.contains("\"provider_id\": \"p-1\""))
    }

    @Test
    fun `exported markdown keeps plain text and tool framing`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            title = "Framing Test",
            messageNodes = emptyList(),
        )
        val textPart = UIMessagePart.Text("hello world")
        val toolPart = UIMessagePart.Tool(
            toolCallId = "call_2",
            toolName = "file_read",
            input = """{"path":"/tmp/a.txt"}""",
        )
        val message = UIMessage(role = MessageRole.USER, parts = listOf(textPart, toolPart))
        val markdown = buildMarkdownExport(conversation, listOf(message))

        assertTrue(markdown.contains("# Framing Test"))
        assertTrue(markdown.contains("**User**"))
        assertTrue(markdown.contains("hello world"))
        assertTrue(markdown.contains("**Tool**: `file_read`"))
        assertTrue(markdown.contains("- Call ID: `call_2`"))
        assertTrue(markdown.contains("\"path\": \"/tmp/a.txt\""))
    }
}
