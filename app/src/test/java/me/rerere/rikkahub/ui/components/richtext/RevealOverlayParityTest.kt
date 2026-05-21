package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
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
        // Boundary: stableRel == rangeLen → early-out path. Distinct from
        // "fully drained" because here the queue might still have entries
        // for OTHER leaves, but THIS leaf's stableHead just reached its
        // end. Lock the early-return so a future off-by-one (`>` vs `>=`)
        // can't sneak back in.
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
        // The codepoint walk must advance by 2 chars per emoji, not 1.
        val content = "😀 hi"  // "😀 hi" — 5 Java chars, 4 codepoints
        val static = buildAnnotatedString {
            pushStringAnnotation(REVEAL_LEAF_TAG, "0")
            append(content)
            pop()
        }
        val c = controllerFor(content)
        c.onFrame(System.nanoTime())
        val result = applyRevealOverlay(static, rangesOf(static), c, baseColor)
        assertEquals(static.text, result.text)
        // The emoji's alpha span must cover the WHOLE surrogate pair
        // [0..2). If overlay walked by char (not codepoint), the second
        // surrogate at position 1 would get its own span starting at 1,
        // and the first span would only span [0..1).
        val alphaSpans = result.spanStyles.filter { span ->
            span.item.color != Color.Unspecified && span.item.color.alpha < 1f
        }
        assertTrue(
            "Emoji span at position 0 must span the full surrogate pair " +
                "(start=0, end=2); found spans: ${alphaSpans.map { it.start to it.end }}",
            alphaSpans.any { it.start == 0 && it.end == 2 }
        )
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
}
