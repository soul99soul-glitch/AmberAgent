package app.amber.core.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformerInvariantTest {
    @Test
    fun invariantRejectsRemovedSystemMessage() {
        val before = listOf(
            UIMessage.system("system"),
            UIMessage.user("hello"),
        )
        val after = listOf(UIMessage.user("hello"))

        val error = runCatching {
            validateTransformerInvariants(before, after, "BadTransformer")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("removed the system message"))
    }

    @Test
    fun invariantRejectsToolMutation() {
        val before = listOf(toolMessage(toolName = "file_read"))
        val after = listOf(toolMessage(toolName = "file_write"))

        val error = runCatching {
            validateTransformerInvariants(before, after, "BadTransformer")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("tool call/result"))
    }

    private fun toolMessage(toolName: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = toolName,
                input = "{}",
            )
        )
    )
}
