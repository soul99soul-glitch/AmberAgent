package app.amber.feature.chat.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCheckpointCoalescerTest {

    private fun coalescer() = StreamCheckpointCoalescer(minIntervalMs = 1_000, charThreshold = 512)

    @Test
    fun `parts hash is lazy and unchanged due check does not reset the window`() {
        val c = coalescer()
        var toolStateHash = "tool-pending"
        var hashCalls = 0

        fun offer(nowMs: Long): Boolean {
            return c.offer(nowMs, "m1", 512) {
                hashCalls += 1
                toolStateHash
            }
        }

        assertTrue(offer(0))
        assertFalse(offer(999))
        assertEquals(1, hashCalls)
        assertFalse(offer(1_000))
        assertEquals(2, hashCalls)
        toolStateHash = "tool-completed"
        assertTrue(offer(1_001))
        assertEquals(3, hashCalls)
    }

    @Test
    fun `first offer arms the window without emitting`() {
        val c = coalescer()
        var hashCalls = 0
        assertFalse(c.offer(nowMs = 0, messageId = "m1", charCount = 10) {
            hashCalls += 1
            "h1"
        })
        assertEquals(0, hashCalls)
    }

    @Test
    fun `emits after one second when content changed`() {
        val c = coalescer()
        c.offer(0, "m1", 10) { "h1" }
        assertFalse(c.offer(500, "m1", 20) { "h2" })
        assertTrue(c.offer(1_000, "m1", 30) { "h3" })
    }

    @Test
    fun `emits before one second once 512 new chars accumulated`() {
        val c = coalescer()
        c.offer(0, "m1", 100) { "h1" }
        assertTrue(c.offer(200, "m1", 512) { "h2" })
    }

    @Test
    fun `never emits when hash unchanged even after interval`() {
        val c = coalescer()
        c.offer(0, "m1", 600) { "h1" }
        assertTrue(c.offer(1_000, "m1", 600) { "h2" })
        assertFalse(c.offer(5_000, "m1", 600) { "h2" })
    }

    @Test
    fun `after emit next emit requires another interval or char delta`() {
        val c = coalescer()
        c.offer(0, "m1", 0) { "h1" }
        assertTrue(c.offer(1_000, "m1", 100) { "h2" })
        assertFalse(c.offer(1_500, "m1", 200) { "h3" })
        // +512 since the last emit (100 -> 612) fires despite < 1s elapsed
        assertTrue(c.offer(1_600, "m1", 612) { "h4" })
        assertFalse(c.offer(1_700, "m1", 700) { "h5" })
        assertTrue(c.offer(2_600, "m1", 700) { "h6" })
    }

    @Test
    fun `new tail message resets the char baseline`() {
        val c = coalescer()
        c.offer(0, "m1", 5_000) { "h1" }
        assertTrue(c.offer(1_000, "m1", 5_100) { "h2" })
        // switching tails: charCount restarts small, 512 measured from zero
        assertFalse(c.offer(1_100, "m2", 400) { "h3" })
        assertTrue(c.offer(1_200, "m2", 520) { "h4" })
    }

    @Test
    fun `message switch does not bypass the time pace cap below char threshold`() {
        val c = coalescer()
        c.offer(0, "m1", 0) { "h1" }
        assertTrue(c.offer(1_000, "m1", 100) { "h2" })
        assertFalse(c.offer(1_010, "m2", 50) { "h3" })
    }
}
