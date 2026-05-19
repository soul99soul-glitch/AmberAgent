package me.rerere.rikkahub.data.agent.board.hotlist

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotListSafeFetcherTest {
    @Test
    fun successfulFetchSavesAndReturnsFreshSnapshot() = runTest {
        var saved: HotListResult? = null
        val provider = fakeProvider { HotListResult(listOf(HotListItem(1, "title")), fetchedAt = 10L) }

        val snapshot = HotListSafeFetcher().fetch(
            provider = provider,
            cachedSnapshot = { null },
            saveResult = { saved = it },
        )

        assertFalse(snapshot.stale)
        assertEquals(1, snapshot.items.size)
        assertEquals(10L, saved?.fetchedAt)
    }

    @Test
    fun failureFallsBackToStaleCache() = runTest {
        val cached = HotListProviderSnapshot("fake", "Fake", listOf(HotListItem(1, "cached")), 1L)
        val provider = fakeProvider { error("network unavailable") }

        val snapshot = HotListSafeFetcher().fetch(
            provider = provider,
            cachedSnapshot = { cached },
            saveResult = {},
        )

        assertTrue(snapshot.stale)
        assertEquals("cached", snapshot.items.single().title)
        assertTrue(snapshot.error.orEmpty().contains("network unavailable"))
    }

    @Test
    fun timeoutWithoutCacheReturnsEmptyStaleSnapshot() = runTest {
        val provider = fakeProvider {
            delay(100)
            HotListResult(listOf(HotListItem(1, "late")), fetchedAt = 10L)
        }

        val snapshot = HotListSafeFetcher(timeoutMs = 1L).fetch(
            provider = provider,
            cachedSnapshot = { null },
            saveResult = {},
        )

        assertTrue(snapshot.stale)
        assertTrue(snapshot.items.isEmpty())
    }

    @Test
    fun httpFailureWithoutCacheReturnsEmptyStaleSnapshot() = runTest {
        val provider = fakeProvider { error("HTTP 500") }

        val snapshot = HotListSafeFetcher().fetch(
            provider = provider,
            cachedSnapshot = { null },
            saveResult = {},
        )

        assertTrue(snapshot.stale)
        assertTrue(snapshot.items.isEmpty())
        assertTrue(snapshot.error.orEmpty().contains("HTTP 500"))
    }

    private fun fakeProvider(block: suspend () -> HotListResult): HotListProvider =
        object : HotListProvider {
            override val id = "fake"
            override val displayName = "Fake"
            override val isBuiltIn = true
            override suspend fun fetch(limit: Int): HotListResult = block()
        }
}
