package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.reasoningContentPresentMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageCotTest {
    @Test
    fun `blank reasoning markers do not split adjacent visible text`() {
        val blocks = listOf(
            UIMessagePart.Text("Amber"),
            UIMessagePart.Reasoning(
                reasoning = "",
                metadata = reasoningContentPresentMetadata(),
            ),
            UIMessagePart.Text("Agent"),
        ).groupMessageParts()

        assertEquals(1, blocks.size)
        val block = blocks.single() as MessagePartBlock.ContentBlock
        assertEquals("AmberAgent", (block.part as UIMessagePart.Text).text)
    }
}
