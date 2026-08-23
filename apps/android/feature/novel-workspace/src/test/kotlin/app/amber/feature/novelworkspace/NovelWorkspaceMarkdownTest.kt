package app.amber.feature.novelworkspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelWorkspaceMarkdownTest {

    @Test
    fun `render preserves host field order and appends aliases last`() {
        // Insertion order (iOS exporter behavior), not sorted order.
        val rendered = NovelWorkspaceMarkdown.render(
            fields = listOf(
                "title" to "赵匡胤",
                "id" to "3f2a0c1a",
                "kind" to "material",
            ),
            aliases = listOf("赵大", "官家"),
            body = "正文内容",
        )
        val expected = """
            ---
            title: 赵匡胤
            id: 3f2a0c1a
            kind: material
            aliases:
              - 赵大
              - 官家
            ---

            正文内容
        """.trimIndent() + "\n"
        assertEquals(expected, rendered)
    }

    @Test
    fun `render without body ends after the fence`() {
        val rendered = NovelWorkspaceMarkdown.render(fields = listOf("kind" to "branch"), body = "")
        assertEquals("---\nkind: branch\n---\n", rendered)
    }

    @Test
    fun `parse file round-trips rendered fields lists and body`() {
        val rendered = NovelWorkspaceMarkdown.render(
            fields = listOf(
                "id" to "abc",
                "kind" to "material",
                "title" to "赵匡胤",
                "injection" to "always",
            ),
            aliases = listOf("赵大"),
            body = "第一段\n第二段",
        )
        val parsed = NovelWorkspaceMarkdown.parseFile(rendered)
        assertEquals("abc", parsed.fields["id"])
        assertEquals("material", parsed.fields["kind"])
        assertEquals("赵匡胤", parsed.fields["title"])
        assertEquals("always", parsed.fields["injection"])
        assertEquals(listOf("赵大"), parsed.lists["aliases"])
        assertEquals("第一段\n第二段", parsed.body)
    }

    @Test
    fun `parse file without fence treats everything as body`() {
        val parsed = NovelWorkspaceMarkdown.parseFile("没有 front matter 的正文")
        assertEquals("没有 front matter 的正文", parsed.body)
        assertEquals(emptyMap<String, String>(), parsed.fields)
    }

    @Test
    fun `parse file with unclosed fence treats everything as body`() {
        val parsed = NovelWorkspaceMarkdown.parseFile("---\nid: abc\n正文没有围栏结束")
        assertEquals(mapOf<String, String>(), parsed.fields)
        assertEquals("---\nid: abc\n正文没有围栏结束", parsed.body)
    }

    @Test
    fun `yaml scalar quoting matches ios rules`() {
        assertEquals("plain", NovelWorkspaceMarkdown.yamlScalar("plain"))
        assertEquals("\"\"", NovelWorkspaceMarkdown.yamlScalar(""))
        assertEquals("\"has: colon\"", NovelWorkspaceMarkdown.yamlScalar("has: colon"))
        assertEquals("\" leading\"", NovelWorkspaceMarkdown.yamlScalar(" leading"))
        assertEquals("\"trail \"", NovelWorkspaceMarkdown.yamlScalar("trail "))
        assertEquals("\"quote \\\" inside\"", NovelWorkspaceMarkdown.yamlScalar("quote \" inside"))
        // iOS keeps the raw newline inside the quotes (no \n escaping).
        assertEquals("\"line\nbreak\"", NovelWorkspaceMarkdown.yamlScalar("line\nbreak"))
        assertEquals("123", NovelWorkspaceMarkdown.yamlScalar("123"))
    }

    @Test
    fun `unquote reverses quoting`() {
        assertEquals("赵匡胤", NovelWorkspaceMarkdown.unquote("赵匡胤"))
        assertEquals("a\"b", NovelWorkspaceMarkdown.unquote("\"a\\\"b\""))
        assertEquals("a\\b", NovelWorkspaceMarkdown.unquote("\"a\\\\b\""))
        assertEquals("\"", NovelWorkspaceMarkdown.unquote("\""))
    }

    @Test
    fun `parse mapping flattens one nested level`() {
        val mapping = NovelWorkspaceMarkdown.parseMapping(
            """
            format: amber.novel.workspace
            formatVersion: 1
            exportedAt: 2026-08-16T12:00:00Z
            source:
              projectID: "abc-123"
              projectRevision: 1112
            mainBranch: 主线
            """.trimIndent(),
        )
        assertEquals("amber.novel.workspace", mapping["format"])
        assertEquals("1", mapping["formatVersion"])
        assertEquals("abc-123", mapping["source.projectID"])
        assertEquals("1112", mapping["source.projectRevision"])
        assertEquals("主线", mapping["mainBranch"])
    }

    @Test
    fun `split highlights separates summary from recent bullets`() {
        val body = "概要内容\n\n## 近期已写\n\n- 第一条\n- 第二条\n# 不是高亮"
        val (summary, highlights) = NovelWorkspaceMarkdown.splitHighlights(body)
        assertEquals("概要内容", summary)
        assertEquals(listOf("第一条", "第二条"), highlights)
    }

    @Test
    fun `split highlights without marker returns null list`() {
        val (summary, highlights) = NovelWorkspaceMarkdown.splitHighlights("只有概要")
        assertEquals("只有概要", summary)
        assertNull(highlights)
    }

    @Test
    fun `sections splits on h2 headings`() {
        val sections = NovelWorkspaceMarkdown.sections(
            "## 位置\n\n汴京\n\n## 必须发生\n- 陈桥兵变\n- 黄袍加身",
        )
        assertEquals("汴京", sections["位置"])
        assertEquals("- 陈桥兵变\n- 黄袍加身", sections["必须发生"])
    }

    @Test
    fun `bullets strips dash prefixes and blanks`() {
        assertEquals(listOf("甲", "乙"), NovelWorkspaceMarkdown.bullets("- 甲\n\n- 乙\n"))
        assertEquals(listOf("裸行"), NovelWorkspaceMarkdown.bullets("裸行"))
    }
}
