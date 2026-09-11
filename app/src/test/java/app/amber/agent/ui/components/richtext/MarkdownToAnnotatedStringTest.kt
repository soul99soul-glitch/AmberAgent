package app.amber.feature.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import app.amber.feature.ui.theme.JetbrainsMono
import app.amber.highlight.HighlightToken
import app.amber.highlight.HighlightTextColorPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownToAnnotatedStringTest {

    private val testStyle = MarkdownSingleTextStyle(
        paragraphSpaceAfter = 16.sp,
        headingSpacing = listOf(16.sp, 14.sp, 12.sp, 10.sp, 8.sp, 6.sp),
        linkColor = Color(0xFF0A66C2),
        bulletColor = Color(0xFF0A66C2),
        inlineCodeBackground = Color(0x33000000),
        codeBackground = Color(0x1F000000),
        codePalette = HighlightTextColorPalette.Default,
        codeFontSize = 12.sp,
        quoteBackground = Color(0x1F636363),
        ruleColor = Color(0xFF9AA0A6),
    )

    private fun render(source: String, enableLatexRendering: Boolean = true): AnnotatedString {
        val result = parseRawMarkdownForParityTest(source)
        return mdNodeToAnnotatedString(
            source = result.preprocessed,
            root = result.tree,
            style = testStyle,
            imageFallbackLabel = "Image",
            enableLatexRendering = enableLatexRendering,
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun AnnotatedString.rangeOf(fragment: String): IntRange {
        val start = text.indexOf(fragment)
        assertTrue("fragment '$fragment' not found in '${text}'", start >= 0)
        return start until (start + fragment.length)
    }

    /** Spans whose range is fully contained in [range] (exact per-node spans). */
    private fun AnnotatedString.exactSpanStyles(range: IntRange): List<SpanStyle> =
        spanStyles.filter { it.start >= range.first && it.end <= range.last + 1 }.map { it.item }

    /** Spans that enclose [range] (block-level wraps like quote color). */
    private fun AnnotatedString.enclosingSpanStyles(range: IntRange): List<SpanStyle> =
        spanStyles.filter { it.start <= range.first && it.end >= range.last + 1 }.map { it.item }

    // ------------------------------------------------------------------
    // Paragraphs
    // ------------------------------------------------------------------

    @Test
    fun `paragraphs join with gap lines between non-last paragraphs`() {
        val md = render("alpha\n\nbravo\n\ncharlie")

        // Single-\n separators; each non-last paragraph gets a one-space gap
        // line at paragraphSpaceAfter height (block tree padding(bottom=fontSize)).
        assertEquals("alpha\n \nbravo\n \ncharlie", md.text)
        assertEquals(
            "two gap lines at 0.875 x paragraphSpaceAfter fontSize",
            2,
            md.spanStyles.count { it.item.fontSize == 14.sp },
        )
        assertTrue("gap span covers the gap newline", md.spanStyles.any {
            it.item.fontSize == 14.sp && md.text.substring(it.start, it.end) == " \n"
        })
        assertTrue("last paragraph must not trail a gap line", md.text.endsWith("charlie"))
    }

    @Test
    fun `soft line break inside a paragraph is preserved`() {
        val md = render("alpha\nbravo")

        assertEquals("alpha\nbravo", md.text)
        assertEquals(
            "single paragraph — no gap lines",
            0,
            md.spanStyles.count { it.item.fontSize == 14.sp },
        )
    }

    // ------------------------------------------------------------------
    // Headings
    // ------------------------------------------------------------------

    @Test
    fun `headings get H1 and H3 font sizes plus heading spacing gaps`() {
        val md = render("# Title\n\n### Sub")

        assertEquals(" \nTitle\n \n \nSub\n ", md.text)
        val titleSpans = md.exactSpanStyles(md.rangeOf("Title"))
        assertTrue(titleSpans.any { it.fontSize == 24.sp && it.fontWeight == FontWeight.Bold })
        val subSpans = md.exactSpanStyles(md.rangeOf("Sub"))
        assertTrue(subSpans.any { it.fontSize == 20.sp && it.fontWeight == FontWeight.Bold })
        assertTrue(
            "H1 spacing gap mirrors headingPadding 16dp (0.875x fontSize)",
            md.spanStyles.any { it.item.fontSize == 14.sp },
        )
        assertTrue(
            "H3 spacing gap mirrors headingPadding 12dp (0.875x fontSize)",
            md.spanStyles.any { it.item.fontSize == 10.5.sp },
        )
    }

    @Test
    fun `marker-only heading renders nothing`() {
        val md = render("##\n\nafter")

        assertEquals("after", md.text)
    }

    // ------------------------------------------------------------------
    // Inline spans
    // ------------------------------------------------------------------

    @Test
    fun `inline bold italic strikethrough and inline code spans`() {
        val md = render("**bold** and *italic* and ~~strike~~ and `code`")

        assertEquals("bold and italic and strike and code", md.text)
        assertTrue(md.exactSpanStyles(md.rangeOf("bold")).any { it.fontWeight == FontWeight.SemiBold })
        assertTrue(md.exactSpanStyles(md.rangeOf("italic")).any { it.fontStyle == FontStyle.Italic })
        assertTrue(
            md.exactSpanStyles(md.rangeOf("strike")).any { it.textDecoration == TextDecoration.LineThrough }
        )
        assertTrue(
            md.exactSpanStyles(md.rangeOf("code")).any {
                it.fontFamily == FontFamily.Monospace && it.background == testStyle.inlineCodeBackground
            }
        )
    }

    @Test
    fun `backslash escapes resolve in text leaves`() {
        val md = render("\\*x\\*")

        assertEquals("*x*", md.text)
    }

    // ------------------------------------------------------------------
    // Links
    // ------------------------------------------------------------------

    @Test
    fun `inline link renders label with clickable annotation and opens url`() {
        var opened: String? = null
        val result = parseRawMarkdownForParityTest("[text](https://example.com)")
        val md = mdNodeToAnnotatedString(
            source = result.preprocessed,
            root = result.tree,
            style = testStyle,
            imageFallbackLabel = "Image",
            onClickUrl = { opened = it },
        )

        assertEquals("text", md.text)
        val linkRange = md.getLinkAnnotations(0, md.length).firstOrNull()
        assertNotNull("link annotation present", linkRange)
        val clickable = linkRange?.item as? LinkAnnotation.Clickable
        assertNotNull("link is Clickable", clickable)
        val link = clickable!!
        assertEquals("https://example.com", link.tag)
        link.linkInteractionListener?.onClick(link)
        assertEquals("https://example.com", opened)
        assertTrue(
            md.exactSpanStyles(md.rangeOf("text")).any {
                it.textDecoration == TextDecoration.Underline && it.color == testStyle.linkColor
            }
        )
    }

    @Test
    fun `bare url autolink renders italic clickable`() {
        val md = render("<https://example.com>")

        assertTrue(md.text.contains("https://example.com"))
        assertTrue(
            md.enclosingSpanStyles(md.rangeOf("https://example.com")).any { it.fontStyle == FontStyle.Italic }
        )
        assertTrue(md.getLinkAnnotations(0, md.length).isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------

    @Test
    fun `unordered list prefixes bullets`() {
        val md = render("- a\n- b")

        assertEquals("• a\n• b", md.text)
    }

    @Test
    fun `nested list gets deeper bullet glyph`() {
        // No textIndent: ParagraphStyle spans are banned from this layer
        // (phantom lines) — nesting is conveyed by the bullet glyph cycle.
        val md = render("- a\n  - b")

        assertEquals("• a\n◦ b", md.text)
    }

    @Test
    fun `ordered list keeps author numbering`() {
        val md = render("7. a\n8. b")

        assertEquals("7. a\n8. b", md.text)
    }

    @Test
    fun `task list items get checkbox prefixes`() {
        val md = render("- [x] done\n- [ ] todo")

        assertEquals("• ☑ done\n• ☐ todo", md.text)
    }

    // ------------------------------------------------------------------
    // Code blocks
    // ------------------------------------------------------------------

    @Test
    fun `fenced code block renders body monospace with background`() {
        val md = render("```kotlin\nval x = 1\n```")

        assertEquals("val x = 1", md.text)
        assertTrue(
            md.exactSpanStyles(md.rangeOf("val x = 1")).any {
                it.fontFamily == JetbrainsMono &&
                    it.fontSize == testStyle.codeFontSize &&
                    it.background == testStyle.codeBackground
            }
        )
    }

    @Test
    fun `closed fenced code block renders cached highlight token colors`() {
        val source = "```kotlin\nval x = 1\n```"
        val body = "val x = 1"
        val result = parseRawMarkdownForParityTest(source)
        val tokens = listOf(
            HighlightToken.Token.StringContent(content = "val ", type = "keyword", length = 4),
            HighlightToken.Plain(content = "x = 1"),
        )
        val md = mdNodeToAnnotatedString(
            source = result.preprocessed,
            root = result.tree,
            style = testStyle,
            imageFallbackLabel = "Image",
            codeHighlights = mapOf(streamingCodeBlockKey("kotlin", body) to tokens),
        )

        assertEquals(body, md.text)
        assertTrue(
            "keyword token gets the palette keyword color",
            md.exactSpanStyles(md.rangeOf("val ")).any {
                it.color == testStyle.codePalette!!.keyword
            }
        )
        // Token text must concatenate to the same body — no char loss.
        assertEquals(body.length, md.length)
    }

    @Test
    fun `unclosed fence ignores the token cache`() {
        // Streaming tail: fence opened, never closed — the collector skips it
        // and the renderer must not look it up either (its body keeps growing,
        // a stale hit would color a truncated body).
        val source = "```kotlin\nval x = 1"
        val body = "val x = 1"
        val result = parseRawMarkdownForParityTest(source)
        val tokens = listOf(
            HighlightToken.Token.StringContent(content = body, type = "keyword", length = body.length),
        )
        val md = mdNodeToAnnotatedString(
            source = result.preprocessed,
            root = result.tree,
            style = testStyle,
            imageFallbackLabel = "Image",
            codeHighlights = mapOf(streamingCodeBlockKey("kotlin", body) to tokens),
        )

        assertTrue(md.text.contains(body))
        assertTrue(
            md.exactSpanStyles(md.rangeOf(body)).none { it.color == testStyle.codePalette!!.keyword }
        )
        // And the collector never emits it.
        assertTrue(
            collectStreamingClosedCodeBlocks(result.tree, result.preprocessed)
                .none { it.body == body }
        )
    }

    @Test
    fun `collector emits closed fenced blocks with stable keys`() {
        val source = "```kotlin\nval a = 1\n```\n\ntext\n\n```python\nx = 2\n```"
        val result = parseRawMarkdownForParityTest(source)

        val blocks = collectStreamingClosedCodeBlocks(result.tree, result.preprocessed)
        assertEquals(listOf("kotlin" to "val a = 1", "python" to "x = 2"), blocks.map { it.lang to it.body })
        assertEquals(streamingCodeBlockKey("kotlin", "val a = 1"), blocks.first().key)
    }

    @Test
    fun `indented code block passes raw span through`() {
        val md = render("    code line")

        assertTrue(md.text.contains("code line"))
        // Settled indented arm renders plain body text — no mono font here.
        assertTrue(
            md.enclosingSpanStyles(md.rangeOf("code line")).none { it.fontFamily == JetbrainsMono }
        )
    }

    // ------------------------------------------------------------------
    // Table
    // ------------------------------------------------------------------

    @Test
    fun `table uses settled columns and keeps ragged cells stable`() {
        val md = render("| a | b |\n| --- | --- |\n| 1 | 2 |")

        assertEquals("a | b\n1 | 2", md.text)
        assertTrue(
            md.exactSpanStyles(md.rangeOf("a | b")).any {
                it.fontWeight == FontWeight.Bold && it.fontFamily == FontFamily.Monospace
            }
        )
        assertTrue(
            md.exactSpanStyles(md.rangeOf("1 | 2")).any {
                it.fontFamily == FontFamily.Monospace && it.fontWeight != FontWeight.Bold
            }
        )
    }

    @Test
    fun `ragged table row gets the settled renderer empty cell`() {
        val md = render("| a | b |\n| --- | --- |\n| 1 |")

        assertEquals("a | b\n1 | ", md.text)
    }

    // ------------------------------------------------------------------
    // Blockquote / rule / image / passthrough
    // ------------------------------------------------------------------

    @Test
    fun `blockquote wraps content with italic and settled background`() {
        val md = render("> quote")

        assertTrue(md.text.contains("quote"))
        assertTrue(
            "settled style: italic + faint fill, text color untouched",
            md.enclosingSpanStyles(md.rangeOf("quote")).any {
                it.fontStyle == FontStyle.Italic && it.background == testStyle.quoteBackground
            }
        )
    }

    @Test
    fun `horizontal rule renders as dash glyph`() {
        val md = render("---")

        assertEquals("———", md.text)
        assertTrue(md.exactSpanStyles(md.rangeOf("———")).any { it.color == testStyle.ruleColor })
    }

    @Test
    fun `image renders as image placeholder label`() {
        val md = render("![alt](https://example.com/img.png)")

        assertEquals("[Image: alt]", md.text)
    }

    @Test
    fun `image without alt uses localized placeholder label`() {
        val md = render("![](https://example.com/img.png)")

        assertEquals("[Image]", md.text)
    }

    @Test
    fun `html block passes through raw`() {
        val md = render("<div>x</div>\n\nafter")

        assertTrue("html raw passthrough", md.text.contains("<div>x</div>"))
        assertTrue(md.text.contains("after"))
    }

    @Test
    fun `math fallback reuses settled delimiter normalization`() {
        val md = render("energy \$mc^2\$")

        assertEquals("energy mc^2", md.text)
        assertTrue(
            md.exactSpanStyles(md.rangeOf("mc^2")).any {
                it.fontFamily == FontFamily.Monospace
            }
        )
    }

    @Test
    fun `disabled latex rendering keeps the original inline formula`() {
        val md = render("energy \$mc^2\$", enableLatexRendering = false)

        assertEquals("energy \$mc^2\$", md.text)
    }

    @Test
    fun `unknown tokens inside paragraph pass through raw`() {
        val md = render("a &lt;b&gt; c")

        assertEquals("a &lt;b&gt; c", md.text)
    }

    // ------------------------------------------------------------------
    // Character fidelity across mixed content
    // ------------------------------------------------------------------

    @Test
    fun `mapped text corresponds to source after removing synthetic prefixes`() {
        val md = render(
            "- alpha\n" +
                "\n" +
                "1. beta\n" +
                "\n" +
                "> gamma\n" +
                "\n" +
                "| c1 | c2 |\n" +
                "| --- | --- |\n" +
                "| v1 | v2 |"
        )

        assertEquals("• alpha\n1. beta\ngamma\nc1 | c2\nv1 | v2", md.text)
    }

    // ------------------------------------------------------------------
    // The phantom-line ban (2026-08-16 "段间大空白")
    // ------------------------------------------------------------------

    @Test
    fun `output never carries paragraph styles`() {
        // ANY ParagraphStyle span boundary makes Compose emit a phantom empty
        // line — identical or different spans, adjacent or not
        // (GapLineLayoutMeasureTest). Pin the ban across every block kind.
        val md = render(
            "# H\n\npara\n\n- a\n  - b\n\n> q\n\n" +
                "```kotlin\nx = 1\n```\n\n" +
                "| a |\n| --- |\n| 1 |\n\n---\n\nmath \$x\$"
        )

        assertTrue(
            "ParagraphStyle spans are banned from the single-text layer",
            md.paragraphStyles.isEmpty(),
        )
    }
}
