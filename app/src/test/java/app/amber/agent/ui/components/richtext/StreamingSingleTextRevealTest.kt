package app.amber.feature.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streaming single-Text reveal window: direction (newest most transparent,
 * drying as text recedes), dry-out guarantee, and prefix continuity. The
 * inverted gradient shipped 2026-08-16 ("光扫过" + moving invisible band) is
 * the regression these tests pin down.
 */
class StreamingSingleTextRevealTest {

    // ------------------------------------------------------------------
    // revealWindowChars
    // ------------------------------------------------------------------

    @Test
    fun window_scales_with_release_rate_while_streaming() {
        // 100 chars/s * 0.15s = 15, quantized to steps of 4.
        assertEquals(12, revealWindowChars(ratePerSecond = 100f, idleMs = 0L))
    }

    @Test
    fun window_clamps_to_max_at_high_rate() {
        // Max 48: covers the raw unparsed tail (rate * 120ms parse tick = 43
        // chars at the 360 chars/s pace cap) with margin, and no more — a
        // bigger window reads as a whole ghost paragraph instead of a
        // word-by-word fade (2026-08-16 video forensics).
        assertEquals(48, revealWindowChars(ratePerSecond = 100_000f, idleMs = 0L))
    }

    @Test
    fun window_keeps_minimum_at_zero_rate() {
        assertEquals(4, revealWindowChars(ratePerSecond = 0f, idleMs = 0L))
    }

    @Test
    fun window_is_full_during_dry_grace_period() {
        // 200 cps * 0.15s = 30 exactly -> quantized 28; at the grace boundary
        // nothing has dried.
        assertEquals(28, revealWindowChars(ratePerSecond = 200f, idleMs = 350L))
    }

    @Test
    fun window_dries_linearly_after_grace() {
        // Half-way through the 550ms dry: 30 * 0.5 = 15 -> quantized 12.
        assertEquals(12, revealWindowChars(ratePerSecond = 200f, idleMs = 625L))
    }

    @Test
    fun window_always_reaches_zero_after_dry_completes() {
        assertEquals(0, revealWindowChars(ratePerSecond = 200f, idleMs = 900L))
        assertEquals(0, revealWindowChars(ratePerSecond = 100_000f, idleMs = 900L))
        assertEquals(0, revealWindowChars(ratePerSecond = 200f, idleMs = 60_000L))
    }

    // ------------------------------------------------------------------
    // applyStreamingWindowReveal
    // ------------------------------------------------------------------

    private fun spanAlphaAt(annotated: AnnotatedString, index: Int): Float? =
        annotated.spanStyles
            .firstOrNull { it.start <= index && index < it.end }
            ?.item
            ?.color
            ?.alpha

    @Test
    fun reveal_is_noop_when_window_is_zero() {
        val annotated = AnnotatedString("一二三四五六七八九十")
        assertSame(annotated, applyStreamingWindowReveal(annotated, 0, Color.Black))
    }

    @Test
    fun reveal_is_noop_for_empty_text() {
        val annotated = AnnotatedString("")
        assertSame(annotated, applyStreamingWindowReveal(annotated, 8, Color.Black))
    }

    @Test
    fun reveal_leaves_text_beyond_the_window_untouched() {
        val annotated = AnnotatedString("一二三四五六七八九十")
        val revealed = applyStreamingWindowReveal(annotated, 4, Color.Black)

        assertEquals(annotated.text, revealed.text)
        // 10 chars, window 4: the char exactly one window back (index 6) sits
        // at progress=1 and is intentionally NOT styled — no snap against the
        // unstyled prefix; everything older is untouched.
        for (index in 0..6) {
            assertTrue(
                "index $index must not carry a reveal span",
                revealed.spanStyles.none { it.start <= index && index < it.end },
            )
        }
    }

    @Test
    fun reveal_fades_newest_chars_most_and_dries_toward_the_tail() {
        val annotated = AnnotatedString("一二三四五六七八九十")
        val revealed = applyStreamingWindowReveal(annotated, 4, Color.Black)

        val headAlpha = spanAlphaAt(revealed, 9)
        val midAlpha = spanAlphaAt(revealed, 8)
        val farAlpha = spanAlphaAt(revealed, 7)

        assertTrue("writing head must start near-transparent", headAlpha != null && headAlpha < 0.25f)
        assertTrue(midAlpha != null && farAlpha != null)
        assertTrue(headAlpha!! < midAlpha!!)
        assertTrue(midAlpha < farAlpha!!)
    }
}
