package app.amber.core.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSearchLimitsTest {

    private fun result(id: String, preview: String, stale: Boolean = false) = ContextSearchResult(
        source = "compact_summary",
        id = id,
        preview = preview,
        stale = stale,
    )

    @Test
    fun stalenessOnlyMarksCompactsWhoseMessagesAreAllGone() {
        assertFalse(ContextSearchStaleness.isStale(listOf("a"), existingMessageIds = null))
        assertFalse(ContextSearchStaleness.isStale(emptyList(), emptySet()))
        assertTrue(ContextSearchStaleness.isStale(listOf("a", "b"), setOf("c")))
        assertFalse(ContextSearchStaleness.isStale(listOf("a", "b"), setOf("b", "c")))
    }

    @Test
    fun budgetKeepsRankingOrderAndReportsTruncation() {
        val results = listOf(result("1", "a".repeat(100)), result("2", "b".repeat(100)), result("3", "c".repeat(100)))

        val applied = ContextSearchBudget.apply(results, maxChars = 200)

        assertEquals(listOf("1", "2"), applied.results.map { it.id })
        assertEquals(200, applied.usedChars)
        assertTrue(applied.truncated)
        assertFalse(ContextSearchBudget.apply(results, maxChars = 1_000).truncated)
    }

    @Test
    fun firstOversizedResultIsDroppedInsteadOfOverrunningTheBudget() {
        val applied = ContextSearchBudget.apply(listOf(result("huge", "x".repeat(1_000))), maxChars = 300)

        assertTrue(applied.results.isEmpty())
        assertEquals(0, applied.usedChars)
        assertTrue(applied.truncated)
    }

    @Test
    fun deduplicateAndRoleFilterRunBeforeLimitSoHasMoreAndTruncatedStayAccurate() {
        val raw = listOf(
            ContextSearchResult(source = "message", id = "m1", preview = "hit 1", role = "user"),
            ContextSearchResult(source = "message", id = "m1", preview = "hit 1 dup", role = "user"),
            ContextSearchResult(source = "message", id = "m2", preview = "hit 2", role = "assistant"),
            ContextSearchResult(source = "message", id = "m3", preview = "hit 3", role = "user"),
            ContextSearchResult(source = "message", id = "m4", preview = "hit 4", role = "user"),
        )
        // Without deduplication & role filter before limit=2, "m1" dup and "m2" assistant would
        // consume the limit and hide "m3", or report wrong hasMore.
        val pageTwo = ContextSearchBudget.deduplicateAndFilter(raw, role = "user", limit = 2)
        assertEquals(listOf("m1", "m3"), pageTwo.results.map { it.id })
        assertEquals(3, pageTwo.totalMatches)
        assertTrue(pageTwo.hasMore)
        assertTrue(pageTwo.truncated)

        val budgetedTwo = ContextSearchBudget.apply(
            pageTwo.results,
            maxChars = 1_000,
            limitTruncated = pageTwo.hasMore,
        )
        assertTrue(budgetedTwo.truncated)
        assertTrue(budgetedTwo.hasMore)

        val pageThree = ContextSearchBudget.deduplicateAndFilter(raw, role = "user", limit = 3)
        assertEquals(listOf("m1", "m3", "m4"), pageThree.results.map { it.id })
        assertFalse(pageThree.hasMore)
    }
}
