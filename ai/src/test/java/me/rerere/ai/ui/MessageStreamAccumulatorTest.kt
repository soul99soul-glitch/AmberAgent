package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
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

    private fun chunk(part: UIMessagePart): MessageChunk = MessageChunk(
        id = "chunk",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(part)
                ),
                message = null,
                finishReason = null,
            )
        )
    )
}
