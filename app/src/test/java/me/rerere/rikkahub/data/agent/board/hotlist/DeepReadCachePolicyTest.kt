package app.amber.feature.board.hotlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadCachePolicyTest {
    @Test
    fun freshOnlyWhenExpiresAtIsInTheFuture() {
        assertTrue(DeepReadCachePolicy.isFresh(expiresAt = 101L, now = 100L))
        assertFalse(DeepReadCachePolicy.isFresh(expiresAt = 100L, now = 100L))
        assertFalse(DeepReadCachePolicy.isFresh(expiresAt = 99L, now = 100L))
    }
}
