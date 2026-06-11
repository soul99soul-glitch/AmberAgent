package app.amber.feature.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCharRevealTest {

    private val base = Color.Black
    private val fadeNanos = 170L * 1_000_000L

    private fun clock() = StreamingCharRevealClock()

    private fun StreamingCharRevealClock.stampDefaults(
        text: String,
        sourceOffset: Int,
        nowNanos: Long,
        maxPending: Int = 220,
    ) = stamp(
        suffixText = text,
        suffixSourceOffset = sourceOffset,
        nowNanos = nowNanos,
        fadeNanos = fadeNanos,
        staggerNanos = 9L * 1_000_000L,
        maxCascadeNanos = 140L * 1_000_000L,
        maxPending = maxPending,
    )

    // ---- clock ----

    @Test
    fun `character animates on its own clock from first sight`() {
        val c = clock()
        c.stampDefaults("ab", sourceOffset = 100, nowNanos = 0L)

        assertEquals(0f, c.progressAt(100, nowNanos = 0L, fadeNanos = fadeNanos))
        assertEquals(0.5f, c.progressAt(100, nowNanos = fadeNanos / 2, fadeNanos = fadeNanos), 0.001f)
        assertEquals(1f, c.progressAt(100, nowNanos = fadeNanos * 2, fadeNanos = fadeNanos))
    }

    @Test
    fun `same frame batch cascades left to right`() {
        val c = clock()
        c.stampDefaults("abc", sourceOffset = 0, nowNanos = 0L)
        val now = 60L * 1_000_000L

        val first = c.progressAt(0, now, fadeNanos)
        val second = c.progressAt(1, now, fadeNanos)
        val third = c.progressAt(2, now, fadeNanos)
        assertTrue(first > second)
        assertTrue(second > third)
    }

    @Test
    fun `late characters start their own fade not the batch's`() {
        val c = clock()
        c.stampDefaults("ab", sourceOffset = 0, nowNanos = 0L)
        // 150ms later two more characters surface — they must start at ~0,
        // not 150ms into a shared curve.
        val later = 150L * 1_000_000L
        c.stampDefaults("abcd", sourceOffset = 0, nowNanos = later)

        assertTrue(c.progressAt(0, later, fadeNanos) > 0.8f)
        assertEquals(0f, c.progressAt(2, later, fadeNanos))
    }

    @Test
    fun `carried over characters keep their timeline across parse ticks`() {
        val c = clock()
        c.stampDefaults("abcd", sourceOffset = 0, nowNanos = 0L)
        val mid = 80L * 1_000_000L
        val before = c.progressAt(2, mid, fadeNanos)
        // Parse tick absorbs "ab"; suffix becomes "cd" at sourceOffset 2.
        c.stampDefaults("cd", sourceOffset = 2, nowNanos = mid)
        val after = c.progressAt(2, mid, fadeNanos)

        assertEquals(before, after, 0.0001f)
    }

    @Test
    fun `backward source offset clears stale stamps`() {
        val c = clock()
        c.stampDefaults("abcd", sourceOffset = 100, nowNanos = 0L)
        // Content replaced (regenerate): offsets restart lower.
        c.stampDefaults("xy", sourceOffset = 10, nowNanos = 0L)

        assertEquals(1f, c.progressAt(102, nowNanos = 0L, fadeNanos = fadeNanos))
        assertEquals(0f, c.progressAt(10, nowNanos = 0L, fadeNanos = fadeNanos))
    }

    @Test
    fun `burst overflow finishes oldest characters instantly`() {
        val c = clock()
        c.stampDefaults("abcdef", sourceOffset = 0, nowNanos = 0L, maxPending = 2)

        // 4 overflow characters stamped as already finished.
        assertEquals(1f, c.progressAt(0, 0L, fadeNanos))
        assertEquals(1f, c.progressAt(3, 0L, fadeNanos))
        // The newest two cascade normally.
        assertTrue(c.progressAt(4, 0L, fadeNanos) < 1f)
        assertTrue(c.progressAt(5, 0L, fadeNanos) < 1f)
    }

    @Test
    fun `unseen offsets render settled`() {
        assertEquals(1f, clock().progressAt(42, 0L, fadeNanos))
    }

    // ---- span builder ----

    @Test
    fun `returns input unchanged when baseColor unspecified`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyStreamingCharReveal(
            combined, staticLength = 7, suffixSourceOffset = 0,
            clock = clock(), nowNanos = 0L, baseColor = Color.Unspecified,
        )
        assertSame(combined, out)
    }

    @Test
    fun `returns input unchanged when nothing animates`() {
        val combined = buildAnnotatedString { append("settled") }
        val out = applyStreamingCharReveal(
            combined, staticLength = 7, suffixSourceOffset = 0,
            clock = clock(), nowNanos = 0L, baseColor = base,
        )
        assertSame(combined, out)
    }

    @Test
    fun `suffix characters fade and lift on appearance`() {
        // static = "settled" [0,7), suffix = "tail" [7,11)
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyStreamingCharReveal(
            combined, staticLength = 7, suffixSourceOffset = 7,
            clock = clock(), nowNanos = 0L, baseColor = base,
        )
        val spans = out.spanStyles
        assertEquals(4, spans.size)
        spans.forEachIndexed { index, span ->
            assertEquals(7 + index, span.start)
            assertEquals(8 + index, span.end)
        }
        val first = spans.first().item
        // Fresh characters start invisible and below the baseline.
        assertEquals(0f, first.color.alpha, 0.001f)
        assertNotNull(first.baselineShift)
        assertTrue(first.baselineShift!!.multiplier < 0f)
    }

    @Test
    fun `finished characters get no span`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val c = clock()
        applyStreamingCharReveal(
            combined, staticLength = 7, suffixSourceOffset = 7,
            clock = c, nowNanos = 0L, baseColor = base,
        )
        val done = 10L * fadeNanos
        val out = applyStreamingCharReveal(
            combined, staticLength = 7, suffixSourceOffset = 7,
            clock = c, nowNanos = done, baseColor = base,
        )
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `does not split surrogate pairs`() {
        val combined = buildAnnotatedString { append("settled🙂a") }
        val out = applyStreamingCharReveal(
            combined, staticLength = 7, suffixSourceOffset = 7,
            clock = clock(), nowNanos = 0L, baseColor = base,
        )
        val spans = out.spanStyles
        assertEquals(2, spans.size)
        assertEquals(7, spans[0].start)
        assertEquals(9, spans[0].end)
        assertEquals(9, spans[1].start)
        assertEquals(10, spans[1].end)
    }

    @Test
    fun `settled catchup ramps darkest at the newest end`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyStreamingCharReveal(
            combined, staticLength = 11, suffixSourceOffset = 11,
            clock = clock(), nowNanos = 0L, baseColor = base,
            settledCatchupStart = 7, settledCatchupAlpha = 0f,
        )
        val spans = out.spanStyles
        assertTrue(spans.isNotEmpty())
        // Oldest absorbed character sits at alpha 1 (no span); the newest is
        // at the catch-up floor; alpha decreases monotonically toward the end.
        assertTrue(spans.all { it.start >= 7 && it.end <= 11 })
        val alphas = spans.map { it.item.color.alpha }
        assertEquals(0.72f, alphas.last(), 0.005f)
        assertTrue(alphas.zipWithNext().all { (a, b) -> a > b })
        // No baseline shift on settled text — absorbed glyphs must not move.
        assertTrue(spans.all { it.item.baselineShift == null })
    }

    @Test
    fun `settled catchup disappears when finished`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyStreamingCharReveal(
            combined, staticLength = 11, suffixSourceOffset = 11,
            clock = clock(), nowNanos = 0L, baseColor = base,
            settledCatchupStart = 7, settledCatchupAlpha = 1f,
        )
        assertSame(combined, out)
    }

    @Test
    fun `plain live suffix fades on the same mechanism`() {
        val out = applyStreamingCharRevealPlain(
            text = "\n\nbody",
            suffixSourceOffset = 50,
            clock = clock(),
            nowNanos = 0L,
            baseColor = base,
        )
        assertTrue(out.spanStyles.isNotEmpty())
        assertEquals(0, out.spanStyles.first().start)
        assertEquals(out.length, out.spanStyles.last().end)
        val first = out.spanStyles.first().item
        assertEquals(0f, first.color.alpha, 0.001f)
        assertNotNull(first.baselineShift)
    }
}
