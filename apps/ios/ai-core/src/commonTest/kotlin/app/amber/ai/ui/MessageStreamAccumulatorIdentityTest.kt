package app.amber.ai.ui

import app.amber.ai.core.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageStreamAccumulatorIdentityTest {
    @Test
    fun finalAssistantMessageKeepsStreamingMessageId() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("hello")),
            model = null,
        )

        accumulator.append(assistantDelta("hello"))
        val streamingId = accumulator.snapshot().last().id

        accumulator.append(assistantFinalMessage("hello world"))

        val final = accumulator.snapshot().last()
        assertEquals(MessageRole.ASSISTANT, final.role)
        assertEquals("hello world", final.toText())
        assertEquals(streamingId, final.id)
    }

    private fun assistantDelta(text: String): MessageChunk = MessageChunk(
        id = "chunk",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage.assistant(text),
                message = null,
                finishReason = null,
            )
        ),
    )

    private fun assistantFinalMessage(text: String): MessageChunk = MessageChunk(
        id = "final",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = null,
                message = UIMessage.assistant(text),
                finishReason = "stop",
            )
        ),
    )
}
