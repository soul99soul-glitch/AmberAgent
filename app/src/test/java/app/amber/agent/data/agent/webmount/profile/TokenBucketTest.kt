package app.amber.feature.webmount.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketTest {

    @Test
    fun capacityLimitsConsecutiveAcquires() {
        val now = 1_000L
        val bucket = TokenBucket(capacity = 3, initialNowMs = now)
        assertTrue(bucket.tryAcquire(now))
        assertTrue(bucket.tryAcquire(now))
        assertTrue(bucket.tryAcquire(now))
        // 4th immediate acquire denied — bucket empty until refill window.
        assertFalse(bucket.tryAcquire(now))
    }

    @Test
    fun refillsOverTime() {
        val start = 10_000L
        val bucket = TokenBucket(capacity = 4, initialNowMs = start)
        // Drain.
        repeat(4) { assertTrue(bucket.tryAcquire(start)) }
        assertFalse(bucket.tryAcquire(start))
        // After 1000ms / 4 = 250ms per slot refill, advance ~500ms → 2 slots back.
        val refilledAt = start + 500L
        assertTrue(bucket.tryAcquire(refilledAt))
        assertTrue(bucket.tryAcquire(refilledAt))
        assertFalse(bucket.tryAcquire(refilledAt))
    }
}
