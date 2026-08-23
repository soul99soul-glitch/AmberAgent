package app.amber.feature.ui.components.richtext

import app.amber.feature.ui.components.richtext.tree.JvmMdNode
import app.amber.feature.ui.components.richtext.tree.MdNode
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownMotionTest {
    @Test
    fun `motion scope claims each structural key once`() {
        val scope = StreamingMarkdownMotionScope()
        val paragraphKey = StreamingMarkdownMotionKey("PARAGRAPH", 42)
        val quoteKeyAtSameOffset = StreamingMarkdownMotionKey("BLOCK_QUOTE", 42)

        assertTrue(scope.claim(paragraphKey))
        assertFalse(scope.claim(paragraphKey))
        assertTrue(scope.hasSeen(paragraphKey))
        assertTrue(scope.claim(quoteKeyAtSameOffset))
    }

    @Test
    fun `motion scope can be reset for replaced stream content`() {
        val scope = StreamingMarkdownMotionScope()
        val paragraphKey = StreamingMarkdownMotionKey("PARAGRAPH", 42)

        assertTrue(scope.claim(paragraphKey))
        scope.clear()
        assertTrue(scope.claim(paragraphKey))
    }

    @Test
    fun `motion key includes type and source offset base`() {
        val quote = parse("> hello").find(MdNodeType.Blockquote)
            ?: error("quote missing")
        val key = streamingMarkdownMotionKey(
            type = quote.type,
            sourceOffsetBase = 120,
            nodeStartOffset = quote.startOffset,
        )

        assertEquals(quote.type.toString(), key.type)
        assertEquals(120 + quote.startOffset, key.absoluteStartOffset)
    }

    @Test
    fun `quote live suffix targets its last renderable child`() {
        val quote = parse("> alpha\n> beta").find(MdNodeType.Blockquote)
            ?: error("quote missing")
        val paragraph = quote.find(MdNodeType.Paragraph)
            ?: error("paragraph missing")

        quote.children.forEach { child ->
            val suffix = streamingLiveSuffixForLastRenderableChild(
                children = quote.children,
                child = child,
                liveSuffix = " live",
                liveSuffixSourceOffset = 17,
            )
            if (child === paragraph) {
                assertEquals(" live", suffix.text)
                assertEquals(17, suffix.sourceOffset)
            } else {
                assertEquals("", suffix.text)
            }
        }
    }

    @Test
    fun `list live suffix targets the final list item`() {
        val list = parse("- one\n- two").find(MdNodeType.ListUnordered)
            ?: error("list missing")
        val items = list.children.filter { it.type == MdNodeType.ListItem }

        assertEquals(2, items.size)
        items.forEachIndexed { index, item ->
            val suffix = streamingLiveSuffixForLastRenderableChild(
                children = list.children,
                child = item,
                liveSuffix = " tail",
                liveSuffixSourceOffset = 23,
            )
            assertEquals(if (index == 1) " tail" else "", suffix.text)
        }
    }

    @Test
    fun `table cell keys keep old cells settled and new cells animatable`() {
        val tableKey = StreamingMarkdownMotionKey(MdNodeType.Table.toString(), 8)
        val scope = StreamingMarkdownMotionScope()
        val oldCell = streamingTableCellMotionKey(tableKey, rowIndex = 0, columnIndex = 0, header = false)
        val sameOldCell = streamingTableCellMotionKey(tableKey, rowIndex = 0, columnIndex = 0, header = false)
        val newRowCell = streamingTableCellMotionKey(tableKey, rowIndex = 1, columnIndex = 0, header = false)
        val headerCell = streamingTableCellMotionKey(tableKey, rowIndex = 0, columnIndex = 0, header = true)

        assertTrue(scope.claim(oldCell))
        assertFalse(scope.claim(sameOldCell))
        assertTrue(scope.claim(newRowCell))
        assertNotEquals(oldCell.type, headerCell.type)
        assertTrue(scope.claim(headerCell))
    }

    private fun parse(content: String): MdNode {
        val source = content
        return JvmMdNode(
            MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source),
            source,
            null,
        )
    }

    private fun MdNode.find(type: MdNodeType): MdNode? {
        if (this.type == type) return this
        return children.firstNotNullOfOrNull { it.find(type) }
    }
}
