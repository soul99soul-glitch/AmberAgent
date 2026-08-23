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

    @Test
    fun pinnedItemsAreAlwaysFreshEvenWhenExpired() {
        // Pinned articles must stay fresh past their TTL so the user can always reopen them.
        assertTrue(DeepReadCachePolicy.isFresh(expiresAt = 99L, now = 100L, pinned = true))
        assertFalse(DeepReadCachePolicy.isFresh(expiresAt = 99L, now = 100L, pinned = false))
        assertTrue(DeepReadCachePolicy.isFresh(expiresAt = Long.MAX_VALUE, now = Long.MAX_VALUE, pinned = true))
    }
}
