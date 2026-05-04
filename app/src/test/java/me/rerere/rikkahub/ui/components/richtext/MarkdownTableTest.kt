package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MarkdownTableTest {
    @Test
    fun `gfm table keeps empty first header cell`() {
        val content = """
            | | Name |
            | --- | --- |
            | 1 | Amber |
        """.trimIndent()
        val table = findTable(
            MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        )

        val tableData = extractMarkdownTableData(table ?: error("table missing"), content)
        assertNotNull(tableData)
        assertEquals(2, tableData?.columnCount)
        assertEquals(listOf("", "Name"), tableData?.headers)
        assertEquals(listOf(listOf("1", "Amber")), tableData?.rows)
    }

    private fun findTable(node: ASTNode): ASTNode? {
        if (node.type == GFMElementTypes.TABLE) return node
        return node.children.firstNotNullOfOrNull(::findTable)
    }
}
