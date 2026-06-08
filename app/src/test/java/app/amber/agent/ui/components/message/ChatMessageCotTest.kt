package app.amber.feature.ui.components.message

import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.reasoningContentPresentMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatMessageCotTest {
    @Test
    fun `single visible text part is not copied while grouping`() {
        val text = UIMessagePart.Text("Streaming tail")

        val blocks = listOf(text).groupMessageParts()

        assertEquals(1, blocks.size)
        val block = blocks.single() as MessagePartBlock.ContentBlock
        assertSame(text, block.part)
    }

    @Test
    fun `adjacent visible text parts merge exactly once`() {
        val blocks = listOf(
            UIMessagePart.Text("Amber"),
            UIMessagePart.Text("Agent"),
        ).groupMessageParts()

        assertEquals(1, blocks.size)
        val block = blocks.single() as MessagePartBlock.ContentBlock
        assertEquals("AmberAgent", (block.part as UIMessagePart.Text).text)
    }

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
