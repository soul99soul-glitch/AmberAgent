package app.amber.core.memory

import app.amber.core.memory.extraction.MemoryExtractor
import app.amber.core.memory.model.MemoryScope
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryExtractionTimeAnchorTest {
    @Test
    fun shortTermFutureDateAndMonthCanDeriveExpiresAt() {
        val now = day("2026-06-01")

        assertEquals(
            day("2026-07-16"),
            MemoryExtractor.resolveCandidateExpiresAt(
                content = "计划 2026-07-15 去东京出差。",
                scope = MemoryScope.SHORT_TERM,
                expiresInDays = null,
                now = now,
            )
        )
        assertEquals(
            day("2026-08-01"),
            MemoryExtractor.resolveCandidateExpiresAt(
                content = "计划 2026年7月 去新加坡旅行。",
                scope = MemoryScope.SHORT_TERM,
                expiresInDays = null,
                now = now,
            )
        )
    }

    @Test
    fun durableScopesNeverReceiveFallbackExpiresAt() {
        val now = day("2026-06-01")

        assertNull(
            MemoryExtractor.resolveCandidateExpiresAt(
                content = "用户计划 2026年7月 去新加坡旅行。",
                scope = MemoryScope.LONG_TERM,
                expiresInDays = null,
                now = now,
            )
        )
        assertNull(
            MemoryExtractor.resolveCandidateExpiresAt(
                content = "用户 2026-07 会避免提 Stan。",
                scope = MemoryScope.CORE,
                expiresInDays = null,
                now = now,
            )
        )
    }

    @Test
    fun historicalAndUnclearRelativeDatesDoNotDeriveExpiresAt() {
        val now = day("2026-06-01")

        assertNull(
            MemoryExtractor.resolveCandidateExpiresAt(
                content = "用户 2025年3月去了北京。",
                scope = MemoryScope.SHORT_TERM,
                expiresInDays = null,
                now = now,
            )
        )
        assertNull(
            MemoryExtractor.resolveCandidateExpiresAt(
                content = "用户下个月可能要旅行。",
                scope = MemoryScope.SHORT_TERM,
                expiresInDays = null,
                now = now,
            )
        )
    }

    private companion object {
        fun day(date: String): Long =
            LocalDate.parse(date)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
    }
}
