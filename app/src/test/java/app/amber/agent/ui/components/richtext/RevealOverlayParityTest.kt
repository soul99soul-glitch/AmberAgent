package app.amber.feature.ui.components.richtext

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.fastForEach
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [applyRevealOverlay]'s observable contract — what the
 * per-frame reveal layer adds on top of the static AnnotatedString
 * built by [appendMarkdownNodeContent]. Two correctness goals:
 *
 *  1. **Fast-path parity**: when there's nothing to fade (no
 *     annotations, no usable baseColor, or every leaf is already
 *     past the controller's stable head), the overlay returns the
 *     input AnnotatedString unchanged. Critical for finalized
 *     messages and the post-reveal idle window — see
 *     [CharRevealController.onFrame]'s idle short-circuit at the
 *     top of CharReveal.kt.
 *
 *  2. **Overlay correctness**: when a leaf is mid-fade, the
 *     overlay adds per-codepoint SpanStyles with the alpha-modulated
 *     baseColor on the *unrevealed* tail of that leaf, leaving the
 *     stable prefix untouched. The static's pre-existing SpanStyles
 *     (bold / italic / etc.) survive intact via [buildAnnotatedString]'s
 *     append-AnnotatedString copy.
 *
 * The first group of tests hand-crafts the static AnnotatedString
 * with [REVEAL_LEAF_TAG] annotations so the overlay's logic is the
 * unit under test in isolation. The "static marking" tests at the
 * bottom go end-to-end through [appendMarkdownNodeContent] with a
 * MarkdownParser-built [LeafASTNode] to lock the static side's
 * decision (trim'd headings, BREAK_LINE_REGEX-collapsed text, and
 * Color.Unspecified baseColor must NOT emit the annotation; ordinary
 * paragraph leaves MUST).
 */
class RevealOverlayParityTest {

    private val baseColor = Color(0xFF000000)
    private val revealDurationNanos = 100_000_000L  // 100ms

    private fun controllerFor(content: String): CharRevealController {
        val c = CharRevealController(revealDurationNanos = revealDurationNanos)
        c.onContentChanged(content)
        return c
    }

    private fun rangesOf(static: AnnotatedString): List<AnnotatedString.Range<String>> =
        static.getStringAnnotations(REVEAL_LEAF_TAG, 0, static.length)

    // ──────────────────────────────────────────────────────────────
    // Fast-path: no alpha layering should happen
    // ──────────────────────────────────────────────────────────────

    @Test
    fun overlay_returns_same_when_baseColor_unspecified() {
        val static = buildAnnotatedString {
            pushStringAnnotation(REVEAL_LEAF_TAG, "0")
            append("hello world")
            pop()
        }
        val c = controllerFor("hello world")
        // baseColor Unspecified means there's no color to modulate — the
        // pre-refactor leaf-text branch also fell straight through to
        // `append(text)` in that case (Markdown.kt LeafASTNode arm).
        assertSame(static, applyRevealOverlay(static, rangesOf(static), c, Color.Unspecified))
    }

    @Test
    fun overlay_returns_same_when_no_annotations() {
        val static = buildAnnotatedString { append("hello world") }
        val c = controllerFor("hello world")
        // No fade-eligible leaves marked — overlay has nothing to do.
        assertSame(static, applyRevealOverlay(static, rangesOf(static), c, baseColor))
    }

    @Test
    fun overlay_returns_same_when_all_leaves_revealed() {
        val content = "hello world"
        val static = buildAnnotatedString {
            pushStringAnnotation(REVEAL_LEAF_TAG, "0")
            append(content)
            pop()
        }
        val c = controllerFor(content)
        // Advance well past revealDurationNanos so onFrame drains the
        // queue (stableOffsetExclusive == contentLength). The overlay
        // should detect this and skip per-codepoint work.
        c.onFrame(System.nanoTime() + 10 * revealDurationNanos)
        val result = applyRevealOverlay(static, rangesOf(static), c, baseColor)
        // After drain, no alpha SpanStyles are added — observable proof
        // is the result has the same text and no extra spans beyond
        // whatever the static already had.
        assertEquals(static.text, result.text)
        // static had zero SpanStyles; result should too.
        assertEquals(0, result.spanStyles.size)
    }

    @Test
    fun overlay_skips_at_stable_offset_equal_to_leaf_length() {
        // Boundary: stableRel == rangeLen → early-out. Locks the
        // `stableRel >= rangeLen` clause against a future off-by-one
        // (`>` instead of `>=`). With a single-leaf static the
        // "queue drained" and "this leaf's stableHead reached its
        // end" cases collapse to the same code path; the test
        // exercises the early-return but doesn't isolate it from
        // drain. Sufficient for the off-by-one regression risk.
        val content = "hi"
        val static = buildAnnotatedString {
            pushStringAnnotation(REVEAL_LEAF_TAG, "0")
            append(content)
            pop()
        }
        val c = controllerFor(content)
        c.onFrame(System.nanoTime() + 10 * revealDurationNanos)
        // stableOffsetExclusive should equal content length (= range end).
        val result = applyRevealOverlay(static, rangesOf(static), c, baseColor)
        // Skip path: zero alpha spans added.
        assertEquals(0, result.spanStyles.size)
        assertEquals(static.text, result.text)
    }

    // ──────────────────────────────────────────────────────────────
    // Overlay correctness: chars in the fade window get alpha spans
    // ──────────────────────────────────────────────────────────────

    @Test
    fun overlay_adds_alpha_spans_for_unrevealed_chars() {
        val content = "hello world"
        val static = buildAnnotatedString {
            pushStringAnnotation(REVEAL_LEAF_TAG, "0")
            append(content)
            pop()
        }
        val c = controllerFor(content)
        // Call onFrame with a timestamp barely after contentChanged so
        // every entry's age is well below revealDurationNanos. Result:
        // queue full, stableOffsetExclusive == 0, every char unrevealed.
        c.onFrame(System.nanoTime())
        val result = applyRevealOverlay(static, rangesOf(static), c, baseColor)
        // At least one codepoint must have alpha < 1 styled on top.
        assertTrue(
            "Expected alpha SpanStyles for unrevealed chars; found none",
            result.spanStyles.any { span ->
                span.item.color != Color.Unspecified && span.item.color.alpha < 1f
            }
        )
        // Text content is preserved by buildAnnotatedString { append(static); ... }
        assertEquals(static.text, result.text)
    }

    @Test
    fun overlay_preserves_existing_static_spans() {
        val content = "bold text"
        val static = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                pushStringAnnotation(REVEAL_LEAF_TAG, "0")
                append(content)
                pop()
            }
        }
        val c = controllerFor(content)
        // Drain so no alpha overlay is added — we only want to verify
        // that the append-AnnotatedString copy preserves the bold span.
        c.onFrame(System.nanoTime() + 10 * revealDurationNanos)
        val result = applyRevealOverlay(static, rangesOf(static), c, baseColor)
        assertEquals(static.text, result.text)
        // Bold span should still be present after the no-op overlay.
        assertTrue(
            "Bold SpanStyle was lost across overlay copy",
            result.spanStyles.any { it.item.fontWeight == FontWeight.Bold }
        )
    }

    @Test
    fun overlay_processes_multiple_leaves_independently() {
        // Two separate fade-eligible leaves at different baseOffsets.
        // First leaf: source offset 0, static positions [0..5)
        // Second leaf: source offset 6, static positions [5..11)
        val static = buildAnnotatedString {
            pushStringAnnotation(REVEAL_LEAF_TAG, "0")
            append("hello")
            pop()
            pushStringAnnotation(REVEAL_LEAF_TAG, "6")
            append(" world")
            pop()
        }
        val c = controllerFor("hello world")
        c.onFrame(System.nanoTime())  // all unrevealed
        val result = applyRevealOverlay(static, rangesOf(static), c, baseColor)
        assertEquals(static.text, result.text)
        // Both leaves should have contributed alpha spans (proves overlay
        // processed each range, not just the first).
        val alphaSpans = result.spanStyles.filter { span ->
            span.item.color != Color.Unspecified && span.item.color.alpha < 1f
        }
        assertTrue(
            "Expected alpha spans from both leaves; found ${alphaSpans.size} total",
            alphaSpans.size >= 2
        )
        // At least one span should be in each leaf's static range.
        assertTrue(
            "No alpha span in first leaf range [0..5)",
            alphaSpans.any { it.start in 0..4 }
        )
        assertTrue(
            "No alpha span in second leaf range [5..11)",
            alphaSpans.any { it.start in 5..10 }
        )
    }

    @Test
    fun overlay_aligns_with_non_zero_range_start() {
        // The annotated range doesn't begin at static position 0 — there's
        // prefix text first. Lock that the codepoint walk starts at
        // `range.start + stableRel`, not from 0.
        val content = "prefix body"
        val static = buildAnnotatedString {
            append("prefix ")  // unmarked filler
            pushStringAnnotation(REVEAL_LEAF_TAG, "7")  // body starts at source 7
            append("body")
            pop()
        }
        val c = controllerFor(content)
        c.onFrame(System.nanoTime())  // unrevealed
        val result = applyRevealOverlay(static, rangesOf(static), c, baseColor)
        val alphaSpans = result.spanStyles.filter { span ->
            span.item.color != Color.Unspecified && span.item.color.alpha < 1f
        }
        // All alpha spans must land in [7..11) — the annotated range —
        // not anywhere in the unmarked prefix [0..7).
        assertTrue(
            "Expected alpha spans only inside annotated range [7..11); " +
                "found at offsets ${alphaSpans.map { it.start }}",
            alphaSpans.all { it.start in 7..10 }
        )
        // And there should be at least one (proves the body was actually
        // processed, didn't just early-out).
        assertTrue(alphaSpans.isNotEmpty())
    }

    @Test
    fun overlay_skips_leaf_with_unparseable_baseOffset() {
        val static = buildAnnotatedString {
            // Intentionally garbage value — overlay must defend.
            pushStringAnnotation(REVEAL_LEAF_TAG, "not-an-int")
            append("hello")
            pop()
        }
        val c = controllerFor("hello")
        c.onFrame(System.nanoTime())
        val result = applyRevealOverlay(static, rangesOf(static), c, baseColor)
        // Defensive skip: no alpha overlay added for the bad-value range.
        assertEquals(static.text, result.text)
        assertEquals(0, result.spanStyles.size)
    }

    @Test
    fun overlay_handles_surrogate_pair_emoji() {
        // U+1F600 (😀) is a surrogate pair: 2 Java chars, 1 codepoint.
        // The codepoint walk must advance by 2 chars per emoji, not 1 —
        // otherwise it would land on the dangling low-surrogate at
        // position 1 and emit a span starting there.
        //
        // Note: post-batching, adjacent codepoints in the same reveal
        // entry / chunk share alpha and collapse to a single span, so
        // we can't isolate the emoji's exact end boundary from spans
        // alone — that would require interleaving different alphas
        // across the surrogate boundary, which requires multi-chunk
        // setup that doesn't compose well with the controller's
        // System.nanoTime-based stamping. The observable invariant we
        // CAN check is "no span starts at position 1" — a buggy
        // char-walk would produce one there (high-surrogate alpha at
        // 0, low-surrogate alpha at 1) regardless of batching, because
        // surrogate-half codepoints are distinct (Character.codePointAt
        // at offset 1 returns U+DE00 — the low surrogate as a standalone
        // codepoint).
        val content = "😀 hi"  // 5 Java chars, 4 codepoints
        val static = buildAnnotatedString {
            pushStringAnnotation(REVEAL_LEAF_TAG, "0")
            append(content)
            pop()
        }
        val c = controllerFor(content)
        c.onFrame(System.nanoTime())
        val result = applyRevealOverlay(static, rangesOf(static), c, baseColor)
        assertEquals(static.text, result.text)
        val alphaSpans = result.spanStyles.filter { span ->
            span.item.color != Color.Unspecified && span.item.color.alpha < 1f
        }
        // Some span must cover the emoji at position 0 (proves the walk
        // didn't bail out before reaching it).
        assertTrue(
            "Expected at least one alpha span starting at position 0 covering " +
                "the emoji; found spans: ${alphaSpans.map { it.start to it.end }}",
            alphaSpans.any { it.start == 0 && it.end >= 2 }
        )
        // Critical: no span starts at position 1. A buggy char-walk
        // (i++) would process the low surrogate at position 1 as its
        // own codepoint and emit a span there.
        assertTrue(
            "Unexpected alpha span starts at position 1 — overlay walked by " +
                "Java char instead of codepoint, splitting the surrogate pair",
            alphaSpans.none { it.start == 1 }
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Annotation marking shape — pins how the static side maps to overlay
    // ──────────────────────────────────────────────────────────────

    @Test
    fun overlay_reads_annotations_via_REVEAL_LEAF_TAG() {
        // Wrong tag — overlay must ignore it.
        val static = buildAnnotatedString {
            pushStringAnnotation("some.other.tag", "0")
            append("hello")
            pop()
        }
        val c = controllerFor("hello")
        c.onFrame(System.nanoTime())
        assertSame(static, applyRevealOverlay(static, rangesOf(static), c, baseColor))
    }

    // ──────────────────────────────────────────────────────────────
    // Static-side: appendMarkdownNodeContent must NOT mark trim'd
    // leaves or Color.Unspecified leaves. Locks the silent behavioral
    // change documented in the commit (trim/BREAK_LINE_REGEX leaves
    // now show no fade, vs. the old code's misaligned-alpha fade).
    // ──────────────────────────────────────────────────────────────

    private fun firstLeaf(content: String): LeafASTNode {
        val root: ASTNode =
            MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        fun walk(node: ASTNode): LeafASTNode? {
            if (node is LeafASTNode) return node
            return node.children.firstNotNullOfOrNull { walk(it) }
        }
        return walk(root) ?: error("no LeafASTNode found in parse of: $content")
    }

    private fun buildStaticFor(
        leaf: LeafASTNode,
        content: String,
        trim: Boolean,
        baseColor: Color,
    ): AnnotatedString = buildAnnotatedString {
        appendMarkdownNodeContent(
            node = leaf,
            content = content,
            trim = trim,
            inlineContents = mutableMapOf(),
            colorScheme = lightColorScheme(),
            density = Density(1f, 1f),
            style = TextStyle(),
            baseColor = baseColor,
        )
    }

    @Test
    fun static_marks_ordinary_leaf_with_REVEAL_LEAF_TAG() {
        val content = "hello"
        val leaf = firstLeaf(content)
        val static = buildStaticFor(leaf, content, trim = false, baseColor = baseColor)
        val annotations = static.getStringAnnotations(REVEAL_LEAF_TAG, 0, static.length)
        assertEquals(
            "Ordinary leaf with usable baseColor must be marked as fade-eligible",
            1,
            annotations.size,
        )
        assertEquals(
            "Annotation value must be the leaf's source startOffset",
            leaf.startOffset.toString(),
            annotations[0].item,
        )
    }

    @Test
    fun static_does_not_mark_trim_leaf() {
        // trim=true is set only by the ATX_HEADER branch in Markdown.kt.
        // We invoke the function directly with trim=true to pin its
        // contribution to canFade in the LeafASTNode arm.
        val content = "hello"
        val leaf = firstLeaf(content)
        val static = buildStaticFor(leaf, content, trim = true, baseColor = baseColor)
        val annotations = static.getStringAnnotations(REVEAL_LEAF_TAG, 0, static.length)
        assertEquals(
            "trim=true leaves must not be marked — their source offset cannot " +
                "align with the static positions after .trim() modifies the string",
            0,
            annotations.size,
        )
    }

    @Test
    fun shouldMarkLeaf_false_when_text_differs_from_rawText() {
        // BREAK_LINE_REGEX rewrites `<br>` → `\n` inside the LeafASTNode
        // arm. When that fires, `text != rawText` and the offset map
        // breaks. The end-to-end MarkdownParser path can't reach this
        // case reliably (GFM may not preserve `<br>` inside a single
        // TEXT leaf), so pin it via the extracted helper.
        assertFalse(
            "Leaf with text != rawText (BREAK_LINE_REGEX collision) must not be " +
                "marked — alpha offsets would misalign with content positions",
            shouldMarkLeafAsFadeEligible(
                rawText = "a<br>b",
                text = "a\nb",
                baseColor = baseColor,
                trim = false,
            ),
        )
    }

    @Test
    fun shouldMarkLeaf_true_when_all_gates_pass() {
        // Symmetric positive case for the helper: identical rawText/text,
        // trim=false, valid baseColor → marking allowed.
        assertTrue(
            shouldMarkLeafAsFadeEligible(
                rawText = "hello",
                text = "hello",
                baseColor = baseColor,
                trim = false,
            ),
        )
    }

    @Test
    fun static_does_not_mark_leaf_when_baseColor_unspecified() {
        val content = "hello"
        val leaf = firstLeaf(content)
        val static = buildStaticFor(leaf, content, trim = false, baseColor = Color.Unspecified)
        val annotations = static.getStringAnnotations(REVEAL_LEAF_TAG, 0, static.length)
        assertEquals(
            "Color.Unspecified means there's no color to alpha-modulate; " +
                "marking would just waste an annotation slot",
            0,
            annotations.size,
        )
    }

    @Test
    fun static_marks_both_leaves_inside_strong_wrapping() {
        // Locks the STRONG-recurse-threads-baseColor-correctly invariant:
        // if a future refactor drops baseColor from the recursive call
        // (compiles clean, parses fine, just silently disables fade for
        // bold/italic text mid-stream), this test goes red. End-to-end
        // through MarkdownParser to exercise the actual recursive arm.
        val content = "**bold** plain"
        val root: ASTNode =
            MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        // Find the PARAGRAPH and build the static from its children
        // (mirrors what Paragraph composable does).
        fun findParagraph(n: ASTNode): ASTNode? {
            if (n.type.name == "PARAGRAPH") return n
            return n.children.firstNotNullOfOrNull { findParagraph(it) }
        }
        val paragraph = findParagraph(root) ?: error("paragraph not found")
        val static = buildAnnotatedString {
            paragraph.children.fastForEach { child ->
                appendMarkdownNodeContent(
                    node = child,
                    content = content,
                    trim = false,
                    inlineContents = mutableMapOf(),
                    colorScheme = lightColorScheme(),
                    density = Density(1f, 1f),
                    style = TextStyle(),
                    baseColor = baseColor,
                )
            }
        }
        val annotations = static.getStringAnnotations(REVEAL_LEAF_TAG, 0, static.length)
        // Both the leaf inside the STRONG wrap AND the plain leaf after
        // it must be marked. If the STRONG recursion drops baseColor or
        // somehow skips the leaf arm, the bold leaf's annotation goes
        // missing.
        assertTrue(
            "Expected at least 2 REVEAL_LEAF_TAG annotations (one for the " +
                "leaf inside STRONG, one for the plain text after); got " +
                "${annotations.size} at offsets ${annotations.map { it.item }}",
            annotations.size >= 2,
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Batching: same-alpha codepoints collapse to one SpanStyle
    // ──────────────────────────────────────────────────────────────

    @Test
    fun overlay_batches_adjacent_same_alpha_codepoints() {
        // CharRevealController slices its queue by word/CJK/whitespace
        // entry. Inside one word entry, every codepoint returns the SAME
        // alpha from alphaAt() (per CharReveal.kt's alphaAt formula —
        // age and duration are entry-level fields). applyRevealOverlay
        // batches by alpha-equal run to avoid N×addStyle when one would do.
        // Lock the savings: a single contiguous word's worth of unrevealed
        // codepoints should produce ONE alpha SpanStyle, not N.
        val content = "hello"  // one word, slices into one entry of length 5
        val static = buildAnnotatedString {
            pushStringAnnotation(REVEAL_LEAF_TAG, "0")
            append(content)
            pop()
        }
        val c = controllerFor(content)
        c.onFrame(System.nanoTime())  // entry queued, age ~0 → alpha 0+
        val result = applyRevealOverlay(static, rangesOf(static), c, baseColor)
        val alphaSpans = result.spanStyles.filter { span ->
            span.item.color != Color.Unspecified && span.item.color.alpha < 1f
        }
        assertEquals(
            "Five codepoints in one reveal entry must collapse to a single " +
                "alpha SpanStyle; found ${alphaSpans.size} spans at " +
                "${alphaSpans.map { it.start to it.end }}",
            1,
            alphaSpans.size,
        )
        // And the single span must cover the whole word [0..5).
        val span = alphaSpans.single()
        assertEquals(0, span.start)
        assertEquals(5, span.end)
    }

    @Test
    fun hard_degrade_keeps_reveal_entries_in_flight() {
        val content = List(90) { "字" }.joinToString("")
        val c = controllerFor(content)
        val frame = System.nanoTime() + 1_000_000L

        c.onFrame(frame)

        assertTrue("degrade must not clear the whole queue", c.queueDepth() > 0)
        assertTrue(
            "degrade must not promote the whole content in one frame",
            c.stableOffsetExclusive() < content.length,
        )
        assertTrue(
            "first glyph should still be mid-fade after degrade",
            c.alphaAt(0) < 1f,
        )
    }

    @Test
    fun hard_degrade_alpha_is_monotonic() {
        val content = List(90) { "字" }.joinToString("")
        val c = controllerFor(content)
        val frame = System.nanoTime() + 1_000_000L

        c.onFrame(frame)
        val firstAlpha = c.alphaAt(0)
        c.onFrame(frame + 8_000_000L)
        val secondAlpha = c.alphaAt(0)

        assertTrue(
            "compressed reveal must continue forward, not regress alpha",
            secondAlpha >= firstAlpha,
        )
        assertTrue(
            "compressed reveal should still avoid a one-frame jump to black",
            firstAlpha < 1f,
        )
    }
}
