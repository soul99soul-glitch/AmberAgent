package app.amber.feature.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BatchRevealSuffixTest {

    private val base = Color.Black

    @Test
    fun `returns input unchanged when suffix fully revealed`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixAlpha = 1f, baseColor = base)
        assertSame(combined, out)
    }

    @Test
    fun `returns input unchanged when baseColor unspecified`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixAlpha = 0.5f, baseColor = Color.Unspecified)
        assertSame(combined, out)
    }

    @Test
    fun `returns input unchanged when staticLength at or past end`() {
        val combined = buildAnnotatedString { append("settled") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixAlpha = 0.3f, baseColor = base)
        assertSame(combined, out)
    }

    @Test
    fun `fades only the suffix range leaving settled prefix opaque`() {
        // static = "settled" [0,7), suffix = "tail" [7,11)
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixAlpha = 0.4f, baseColor = base)
        val spans = out.spanStyles
        assertEquals(1, spans.size)
        val span = spans.first()
        assertEquals(7, span.start)
        assertEquals(11, span.end)
        assertEquals(base.copy(alpha = 0.4f), span.item.color)
    }
}
