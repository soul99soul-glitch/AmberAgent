package app.amber.feature.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRevealSuffixTest {

    private val base = Color.Black

    @Test
    fun `returns input unchanged when suffix fully revealed`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixProgress = 1f, baseColor = base)
        assertSame(combined, out)
    }

    @Test
    fun `returns input unchanged when baseColor unspecified`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixProgress = 0.5f, baseColor = Color.Unspecified)
        assertSame(combined, out)
    }

    @Test
    fun `returns input unchanged when staticLength at or past end`() {
        val combined = buildAnnotatedString { append("settled") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixProgress = 0.3f, baseColor = base)
        assertSame(combined, out)
    }

    @Test
    fun `reveals only the suffix range with local stagger and lift`() {
        // static = "settled" [0,7), suffix = "tail" [7,11)
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixProgress = 0.4f, baseColor = base)
        val spans = out.spanStyles
        assertEquals(4, spans.size)
        spans.forEachIndexed { index, span ->
            assertEquals(7 + index, span.start)
            assertEquals(8 + index, span.end)
        }
        assertTrue(spans.all { it.start >= 7 })

        val first = spans.first().item
        val last = spans.last().item
        assertNotNull(first.baselineShift)
        assertNotNull(last.baselineShift)
        assertEquals(0.50752f, first.color.alpha, 0.003f)
        assertTrue(first.color.alpha > last.color.alpha)
        assertTrue(first.baselineShift!!.multiplier > last.baselineShift!!.multiplier)
        assertTrue(last.baselineShift!!.multiplier < 0f)
    }

    @Test
    fun `does not split surrogate pairs while revealing suffix`() {
        val combined = buildAnnotatedString { append("settled🙂a") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixProgress = 0.4f, baseColor = base)
        val spans = out.spanStyles
        assertEquals(2, spans.size)
        assertEquals(7, spans[0].start)
        assertEquals(9, spans[0].end)
        assertEquals(9, spans[1].start)
        assertEquals(10, spans[1].end)
    }

    @Test
    fun `falls back to one suffix span for very long suffixes`() {
        val suffix = "x".repeat(160)
        val combined = buildAnnotatedString {
            append("settled")
            append(suffix)
        }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixProgress = 0.4f, baseColor = base)
        val spans = out.spanStyles
        assertEquals(1, spans.size)
        assertEquals(7, spans.single().start)
        assertEquals(7 + suffix.length, spans.single().end)
        assertEquals(0.50752f, spans.single().item.color.alpha, 0.003f)
    }

    @Test
    fun `starts suffix faint and below baseline`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixProgress = 0f, baseColor = base)
        val first = out.spanStyles.first().item
        assertEquals(0.24f, first.color.alpha, 0.003f)
        assertNotNull(first.baselineShift)
        assertTrue(first.baselineShift!!.multiplier < 0f)
    }
}
