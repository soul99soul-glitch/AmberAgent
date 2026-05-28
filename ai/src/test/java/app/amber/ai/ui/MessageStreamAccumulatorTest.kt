package app.amber.ai.ui

import app.amber.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStreamAccumulatorTest {
    @Test(timeout = 4_000)
    fun `20k tiny text and reasoning chunks merge without repeated full string copies`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        repeat(20_000) {
            accumulator.append(chunk(UIMessagePart.Text("x")))
        }
        repeat(20_000) {
            accumulator.append(chunk(UIMessagePart.Reasoning("r", finishedAt = null)))
        }

        val assistant = accumulator.snapshot().last()
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals(20_000, assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text.length)
        assertEquals(20_000, assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning.length)
    }

    @Test
    fun `explicit empty reasoning marker does not split streamed text`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        accumulator.append(chunk(UIMessagePart.Text("你好")))
        accumulator.append(
            chunk(
                UIMessagePart.Reasoning(
                    reasoning = "",
                    metadata = reasoningContentPresentMetadata()
                )
            )
        )
        accumulator.append(chunk(UIMessagePart.Text("啊")))

        val assistant = accumulator.snapshot().last()
        assertEquals("你好啊", assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        val reasoning = assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("", reasoning.reasoning)
        assertTrue(reasoning.hasExplicitReasoningContentField())
    }

    @Test
    fun `reasoning finishes when streamed text starts`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        accumulator.append(chunk(UIMessagePart.Reasoning("thinking", finishedAt = null)))
        accumulator.append(chunk(UIMessagePart.Text("answer")))

        val assistant = accumulator.snapshot().last()
        val reasoning = assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertNotNull(reasoning.finishedAt)
        assertEquals("answer", assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `empty reasoning marker in text chunk does not keep reasoning timer open`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        accumulator.append(chunk(UIMessagePart.Reasoning("thinking", finishedAt = null)))
        accumulator.append(
            chunk(
                UIMessagePart.Reasoning(
                    reasoning = "",
                    finishedAt = null,
                    metadata = reasoningContentPresentMetadata()
                ),
                UIMessagePart.Text("answer"),
            )
        )

        val assistant = accumulator.snapshot().last()
        val reasoning = assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertNotNull(reasoning.finishedAt)
        assertEquals("answer", assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `final full message replaces streamed deltas instead of appending again`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        accumulator.append(chunk(UIMessagePart.Text("{\"summary\":\"半")))
        accumulator.append(chunk(UIMessagePart.Text("截\"")))
        accumulator.append(finalMessage("""{"summary":"完整 JSON"}"""))

        val assistant = accumulator.snapshot().last()
        assertEquals("""{"summary":"完整 JSON"}""", assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    private fun chunk(vararg parts: UIMessagePart): MessageChunk = MessageChunk(
        id = "chunk",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = parts.toList()
                ),
                message = null,
                finishReason = null,
            )
        )
    )

    private fun finalMessage(text: String): MessageChunk = MessageChunk(
        id = "final",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = null,
                message = UIMessage.assistant(text),
                finishReason = null,
            )
        )
    )
}
