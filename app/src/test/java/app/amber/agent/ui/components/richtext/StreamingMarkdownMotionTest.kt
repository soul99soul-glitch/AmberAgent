package app.amber.feature.ui.components.richtext

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
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
        val quote = parse("> hello").find(MarkdownElementTypes.BLOCK_QUOTE)
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
        val quote = parse("> alpha\n> beta").find(MarkdownElementTypes.BLOCK_QUOTE)
            ?: error("quote missing")
        val paragraph = quote.find(MarkdownElementTypes.PARAGRAPH)
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
        val list = parse("- one\n- two").find(MarkdownElementTypes.UNORDERED_LIST)
            ?: error("list missing")
        val items = list.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }

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
        val tableKey = StreamingMarkdownMotionKey(GFMElementTypes.TABLE.toString(), 8)
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

    private fun parse(content: String): ASTNode {
        return MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    }

    private fun ASTNode.find(type: IElementType): ASTNode? {
        if (this.type == type) return this
        return children.firstNotNullOfOrNull { it.find(type) }
    }
}
