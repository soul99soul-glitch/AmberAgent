package app.amber.core.memory

import app.amber.core.memory.extraction.MemoryCandidateFilter
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryCandidateStatus
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCandidateFilterTest {
    private val filter = MemoryCandidateFilter()

    @Test
    fun filtersDuplicatesSensitiveAndWeakCandidates() {
        val existing = listOf(
            MemoryRecord(
                id = 1,
                content = "用户喜欢中文简洁回复",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.FEEDBACK,
                assistantId = "__long_term__",
            )
        )
        val result = filter.filter(
            candidates = listOf(
                MemoryCandidate(
                    content = "用户喜欢中文简洁回复",
                    scope = MemoryScope.LONG_TERM,
                    kind = MemoryKind.FEEDBACK,
                    confidence = 0.9f,
                ),
                MemoryCandidate(
                    content = "密码是 123456",
                    scope = MemoryScope.LONG_TERM,
                    kind = MemoryKind.USER,
                    confidence = 0.9f,
                ),
                MemoryCandidate(
                    content = "短",
                    scope = MemoryScope.SHORT_TERM,
                    kind = MemoryKind.NOTE,
                    confidence = 0.9f,
                ),
                MemoryCandidate(
                    content = "当前项目是 AmberAgent 记忆系统升级",
                    scope = MemoryScope.SHORT_TERM,
                    kind = MemoryKind.NOTE,
                    confidence = 0.8f,
                ),
            ),
            existing = existing,
        )

        assertEquals(1, result.accepted.size)
        assertEquals(MemoryKind.PROJECT, result.accepted.single().kind)
        assertEquals(3, result.rejected.size)
        assertTrue(result.rejected.all { it.status == MemoryCandidateStatus.FILTERED })
    }

    @Test
    fun intentCandidatesBypassDuplicateAndWeakGatesButNotSensitivity() {
        val existing = listOf(
            MemoryRecord(
                id = 1,
                content = "用户喜欢中文简洁回复",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.FEEDBACK,
                assistantId = "__long_term__",
            )
        )
        val confirm = MemoryCandidate(
            id = "confirm-1",
            // Confirm restates the target verbatim — normalized content is a
            // duplicate by definition; rejecting it would starve the intent.
            content = "用户喜欢中文简洁回复",
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.FEEDBACK,
            confidence = 0.9f,
        )
        val shortInvalidate = MemoryCandidate(
            id = "invalidate-1",
            content = "已过期",
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.NOTE,
            confidence = 0.2f,
        )
        val sensitiveIntent = MemoryCandidate(
            id = "sensitive-1",
            content = "密码是 123456",
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.USER,
            confidence = 0.9f,
        )

        val result = filter.filter(
            candidates = listOf(confirm, shortInvalidate, sensitiveIntent),
            existing = existing,
            intentCandidateIds = setOf("confirm-1", "invalidate-1", "sensitive-1"),
        )

        assertEquals(setOf("confirm-1", "invalidate-1"), result.accepted.map { it.id }.toSet())
        assertEquals(listOf("sensitive-1"), result.rejected.map { it.id })
    }
}
