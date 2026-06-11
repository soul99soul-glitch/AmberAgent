package app.amber.feature.chat.impl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCheckpointCoalescerTest {

    private fun coalescer() = StreamCheckpointCoalescer(minIntervalMs = 1_000, charThreshold = 512)

    @Test
    fun `first offer arms the window without emitting`() {
        val c = coalescer()
        assertFalse(c.offer(nowMs = 0, messageId = "m1", partsHash = "h1", charCount = 10))
    }

    @Test
    fun `emits after one second when content changed`() {
        val c = coalescer()
        c.offer(0, "m1", "h1", 10)
        assertFalse(c.offer(500, "m1", "h2", 20))
        assertTrue(c.offer(1_000, "m1", "h3", 30))
    }

    @Test
    fun `emits before one second once 512 new chars accumulated`() {
        val c = coalescer()
        c.offer(0, "m1", "h1", 100)
        assertTrue(c.offer(200, "m1", "h2", 512))
    }

    @Test
    fun `never emits when hash unchanged even after interval`() {
        val c = coalescer()
        c.offer(0, "m1", "h1", 600)
        assertTrue(c.offer(1_000, "m1", "h2", 600))
        assertFalse(c.offer(5_000, "m1", "h2", 600))
    }

    @Test
    fun `after emit next emit requires another interval or char delta`() {
        val c = coalescer()
        c.offer(0, "m1", "h1", 0)
        assertTrue(c.offer(1_000, "m1", "h2", 100))
        assertFalse(c.offer(1_500, "m1", "h3", 200))
        // +512 since the last emit (100 -> 612) fires despite < 1s elapsed
        assertTrue(c.offer(1_600, "m1", "h4", 612))
        assertFalse(c.offer(1_700, "m1", "h5", 700))
        assertTrue(c.offer(2_600, "m1", "h6", 700))
    }

    @Test
    fun `new tail message resets the char baseline`() {
        val c = coalescer()
        c.offer(0, "m1", "h1", 5_000)
        assertTrue(c.offer(1_000, "m1", "h2", 5_100))
        // switching tails: charCount restarts small, 512 measured from zero
        assertFalse(c.offer(1_100, "m2", "h3", 400))
        assertTrue(c.offer(1_200, "m2", "h4", 520))
    }

    @Test
    fun `message switch does not bypass the time pace cap below char threshold`() {
        val c = coalescer()
        c.offer(0, "m1", "h1", 0)
        assertTrue(c.offer(1_000, "m1", "h2", 100))
        assertFalse(c.offer(1_010, "m2", "h3", 50))
    }
}
