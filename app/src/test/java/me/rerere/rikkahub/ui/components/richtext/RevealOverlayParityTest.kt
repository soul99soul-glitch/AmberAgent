package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
 * These tests don't go through MarkdownParser — they hand-craft the
 * static AnnotatedString with [REVEAL_LEAF_TAG] annotations so the
 * unit under test is the overlay layer in isolation. The end-to-end
 * "static build emits the right annotations" path is verified by
 * device dogfood, not here.
 */
class RevealOverlayParityTest {

    private val baseColor = Color(0xFF000000)
    private val revealDurationNanos = 100_000_000L  // 100ms

    private fun controllerFor(content: String): CharRevealController {
        val c = CharRevealController(revealDurationNanos = revealDurationNanos)
        c.onContentChanged(content)
        return c
    }

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
        assertSame(static, applyRevealOverlay(static, c, Color.Unspecified))
    }

    @Test
    fun overlay_returns_same_when_no_annotations() {
        val static = buildAnnotatedString { append("hello world") }
        val c = controllerFor("hello world")
        // No fade-eligible leaves marked — overlay has nothing to do.
        assertSame(static, applyRevealOverlay(static, c, baseColor))
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
        val result = applyRevealOverlay(static, c, baseColor)
        // After drain, no alpha SpanStyles are added — observable proof
        // is the result has the same text and no extra spans beyond
        // whatever the static already had.
        assertEquals(static.text, result.text)
        // static had zero SpanStyles; result should too.
        assertEquals(0, result.spanStyles.size)
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
        val result = applyRevealOverlay(static, c, baseColor)
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
        val result = applyRevealOverlay(static, c, baseColor)
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
        val result = applyRevealOverlay(static, c, baseColor)
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
    fun overlay_skips_leaf_with_unparseable_baseOffset() {
        val static = buildAnnotatedString {
            // Intentionally garbage value — overlay must defend.
            pushStringAnnotation(REVEAL_LEAF_TAG, "not-an-int")
            append("hello")
            pop()
        }
        val c = controllerFor("hello")
        c.onFrame(System.nanoTime())
        val result = applyRevealOverlay(static, c, baseColor)
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
        val result = applyRevealOverlay(static, c, baseColor)
        assertEquals(static.text, result.text)
        // The emoji's alpha span must cover [0..2) — the whole surrogate
        // pair. If overlay walked by char (not codepoint), the second
        // surrogate at position 1 would get its own span starting at 1,
        // and the first span would be [0..1). We assert no span starts
        // at position 1 — the codepoint walk skipped to 2.
        val alphaSpans = result.spanStyles.filter { span ->
            span.item.color != Color.Unspecified && span.item.color.alpha < 1f
        }
        assertTrue(
            "No alpha span starts at the emoji position 0",
            alphaSpans.any { it.start == 0 }
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
        assertSame(static, applyRevealOverlay(static, c, baseColor))
    }
}
