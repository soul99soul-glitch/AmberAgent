package me.rerere.rikkahub.data.memory

import me.rerere.rikkahub.data.memory.extraction.MemoryCandidateFilter
import me.rerere.rikkahub.data.memory.model.MemoryCandidate
import me.rerere.rikkahub.data.memory.model.MemoryCandidateStatus
import me.rerere.rikkahub.data.memory.model.MemoryKind
import me.rerere.rikkahub.data.memory.model.MemoryRecord
import me.rerere.rikkahub.data.memory.model.MemoryScope
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
}
