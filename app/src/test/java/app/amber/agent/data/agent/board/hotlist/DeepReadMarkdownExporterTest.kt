package app.amber.feature.board.hotlist.deepread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadMarkdownExporterTest {

    @Test
    fun toMarkdownRendersPersistedSectionsInOrder() {
        val output = DeepReadOutput(
            summary = "事件概览正文。",
            keyEntities = listOf("实体甲", "实体乙"),
            timeline = listOf(
                TimelineEvent(date = "2026-05-01", event = "首个公开节点。"),
                TimelineEvent(date = "2026-05-03", event = "后续进展。", isHighlight = true),
            ),
            corePoints = listOf(
                CorePoint(point = "论点一", supporting = "支撑材料一"),
                CorePoint(point = "论点二"),
            ),
            analysis = DeepAnalysis(
                coreDispute = "核心分歧正文。",
                perspectives = listOf(
                    Perspective(holder = "观察者甲", viewpoint = "视角一"),
                    Perspective(viewpoint = "无署名视角"),
                ),
                implications = "影响正文。",
                quotes = listOf(DeepQuote(text = "引文内容", attribution = "发言人")),
            ),
            extendedReading = listOf(
                ReadingLink(title = "延伸阅读一", url = "https://example.com/a", source = "媒体甲"),
            ),
            references = listOf(
                ReadingLink(title = "参考一", url = "https://example.com/b"),
                ReadingLink(title = "重复链接", url = "https://example.com/a"),
            ),
        )

        val markdown = DeepReadMarkdownExporter.toMarkdown("深度阅读标题", output)

        assertTrue(markdown.startsWith("# 深度阅读标题"))
        assertTrue(markdown.contains("事件概览正文。"))
        assertTrue(markdown.contains("**Key entities**: 实体甲, 实体乙"))
        assertTrue(markdown.contains("**2026-05-01** — 首个公开节点。"))
        assertTrue(markdown.contains("- 论点一"))
        assertTrue(markdown.contains("核心分歧正文。"))
        assertTrue(markdown.contains("**观察者甲**: 视角一"))
        assertTrue(markdown.contains("> 引文内容 — 发言人"))
        assertTrue(markdown.contains("[延伸阅读一](https://example.com/a) — 媒体甲"))
        assertTrue(markdown.contains("[参考一](https://example.com/b)"))
        // Duplicate URL appears once.
        assertEquals(1, Regex("example\\.com/a").findAll(markdown).count())
        // Section order: summary < timeline < key points < analysis < links.
        assertTrue(markdown.indexOf("## Timeline") < markdown.indexOf("## Key points"))
        assertTrue(markdown.indexOf("## Key points") < markdown.indexOf("## Analysis"))
        assertTrue(markdown.indexOf("## Analysis") < markdown.indexOf("## Further reading"))
    }

    @Test
    fun toMarkdownSkipsEmptySectionsAndBlankEntries() {
        val output = DeepReadOutput(
            summary = "只有摘要。",
            timeline = listOf(TimelineEvent(date = "", event = "")),
            corePoints = listOf(CorePoint(point = "  ")),
        )

        val markdown = DeepReadMarkdownExporter.toMarkdown("标题", output)

        assertTrue(markdown.contains("只有摘要。"))
        assertFalse(markdown.contains("## Timeline"))
        assertFalse(markdown.contains("## Key points"))
        assertFalse(markdown.contains("## Analysis"))
        assertFalse(markdown.contains("## Further reading"))
        assertTrue(markdown.endsWith("\n"))
    }

    @Test
    fun toMarkdownExportsPartialOutputWhenRunIncomplete() {
        // A mid-run output: overview READY, other sections empty — export carries
        // only what was persisted, no fabricated sections.
        val output = DeepReadOutput(summary = "半截输出摘要。")

        val markdown = DeepReadMarkdownExporter.toMarkdown("进行中主题", output)

        assertEquals("# 进行中主题\n\n半截输出摘要。\n", markdown)
    }
}
