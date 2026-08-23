package app.amber.feature.ui.components.richtext

import app.amber.core.utils.stripReasoningMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPlainTextTest {

    // ------------------------------------------------------------------
    // Marker repair (display-only)
    // ------------------------------------------------------------------

    @Test
    fun open_code_fence_does_not_gain_stray_backtick() {
        val streamingFence = "思考中\n```kotlin\nval x = 1"
        // 围栏未闭合：围栏行的 3 个反引号不计入行内配对，不得追加杂散 `
        assertEquals(streamingFence, repairUnpairedInlineMarkers(streamingFence))
    }

    @Test
    fun unpaired_inline_marker_gets_closer() {
        assertEquals("a**b**", repairUnpairedInlineMarkers("a**b"))
        assertEquals("a`b`", repairUnpairedInlineMarkers("a`b"))
        assertEquals("a~~b~~", repairUnpairedInlineMarkers("a~~b"))
    }

    @Test
    fun closed_markers_are_left_alone() {
        assertEquals("a**b**", repairUnpairedInlineMarkers("a**b**"))
    }

    // ------------------------------------------------------------------
    // Reasoning display stripping
    // ------------------------------------------------------------------

    @Test
    fun strip_removes_document_markers_but_keeps_text() {
        val raw = "## 标题\n\n**重点**和*斜体*，还有 `code`。\n\n- 列表项\n\n[链接](https://x.com)"
        val stripped = raw.stripReasoningMarkdown()
        assertEquals("标题\n\n重点和斜体，还有 code。\n\n列表项\n\n链接", stripped)
    }

    @Test
    fun strip_keeps_code_fence_content() {
        val raw = "先写代码：\n```kotlin\nval x = 1\n```\n结束"
        assertEquals("先写代码：\n\nval x = 1\n\n结束", raw.stripReasoningMarkdown())
    }

    @Test
    fun strip_preserves_nested_list_indentation() {
        val raw = "- 顶层\n  - 嵌套"
        assertEquals("顶层\n  嵌套", raw.stripReasoningMarkdown())
    }

    @Test
    fun strip_keeps_hashtag_without_space() {
        // CommonMark 要求 # 后有空格才是标题；`#标签` 保留 #
        assertEquals("#标签", "#标签".stripReasoningMarkdown())
    }

    @Test
    fun strip_removes_setext_underline() {
        assertEquals("标题", "标题\n===".stripReasoningMarkdown())
    }

    @Test
    fun strip_collapses_extra_blank_lines() {
        assertEquals("a\n\nb", "a\n\n\n\nb".stripReasoningMarkdown())
    }

    // ------------------------------------------------------------------
    // Segmentation edges not pinned in StreamingDisplayBufferTest
    // ------------------------------------------------------------------

    @Test
    fun unit_count_is_pinned_for_mixed_content() {
        // 你 | 好 | " world，" | 再 | 一 | 次 | " ok"
        // （全角 ，跟在拉丁词后并入该拉丁 run）
        val content = "你好 world，再一次 ok"
        val units = buildList {
            var i = 0
            while (i < content.length) {
                i = content.nextStreamingReleaseUnitEnd(i)
                add(i)
            }
        }
        assertEquals(listOf(1, 2, 9, 10, 11, 12, 15), units)
    }

    @Test
    fun ideographic_space_separates_cjk_units() {
        // U+3000 是空白：好、 solitary空白附着、词
        val content = "好\u3000世界"
        val first = content.nextStreamingReleaseUnitEnd(0)
        assertEquals(1, first)
        // "␣世界"：空白 run + 世（CJK 单字）
        assertEquals(3, content.nextStreamingReleaseUnitEnd(first))
    }

    @Test
    fun newline_then_word_is_one_unit() {
        val content = "a\n\n标题"
        assertEquals(1, content.nextStreamingReleaseUnitEnd(0))
        // "\n\n标" 整体一个 unit（空白附着 + CJK 单字）
        assertEquals(4, content.nextStreamingReleaseUnitEnd(1))
    }

    @Test
    fun hangul_syllables_are_single_units() {
        val content = "한국어"
        assertEquals(1, content.nextStreamingReleaseUnitEnd(0))
        assertEquals(2, content.nextStreamingReleaseUnitEnd(1))
    }

    @Test
    fun from_at_length_returns_length() {
        val content = "abc"
        assertEquals(3, content.nextStreamingReleaseUnitEnd(3))
        assertEquals(0, countStreamingReleaseUnits(content, 3, 3))
    }

    @Test
    fun zwj_emoji_cluster_stays_one_unit() {
        val family = String(Character.toChars(0x1F468)) + "‍" + String(Character.toChars(0x1F469))
        val content = "字$family"
        // CJK 字单独成 unit；ZWJ 家族 emoji 在拉丁 run 中作为不可断 cluster
        assertEquals(1, content.nextStreamingReleaseUnitEnd(0))
        val second = content.nextStreamingReleaseUnitEnd(1)
        assertTrue("ZWJ cluster consumed whole", second > 3)
        assertEquals(content.length, second)
    }
}
