package app.amber.feature.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishBatchFadeTest {
    @Test
    fun newBatchFadesTogetherWithoutFadingPreviouslyDisplayedText() {
        val fade = PublishBatchFade()
        fade.observe(4, tailActive = false, nowNanos = 0L)
        fade.observe(8, tailActive = true, nowNanos = 1_000_000_000L)
        val batch = fade.batches.single()
        assertEquals(4, batch.start)
        assertEquals(8, batch.end)
        assertEquals(1f, fade.remainingOpacity(batch, 1_000_000_000L), 0f)
        assertEquals(0.5f, fade.remainingOpacity(batch, 1_175_000_000L), 0.001f)
    }

    @Test
    fun lastBatchFinishesFadingAfterStreamingEnds() {
        val fade = PublishBatchFade()
        fade.observe(4, tailActive = true, nowNanos = 1_000_000_000L)
        val batch = fade.batches.single()
        fade.observe(4, tailActive = false, nowNanos = 1_100_000_000L)
        assertTrue(fade.hasActive(1_175_000_000L))
        assertEquals(0f, fade.remainingOpacity(batch, 1_350_000_000L), 0f)
        assertFalse(fade.hasActive(1_350_000_000L))
    }

    @Test
    fun parserRemovingSyntaxDoesNotLeaveRangesOutsideTheRenderedText() {
        val fade = PublishBatchFade()
        fade.observe(8, tailActive = true, nowNanos = 1_000_000_000L)
        fade.observe(4, tailActive = true, nowNanos = 1_100_000_000L)
        assertTrue(fade.batches.isEmpty())
        assertFalse(fade.hasActive(1_100_000_000L))
    }
}
