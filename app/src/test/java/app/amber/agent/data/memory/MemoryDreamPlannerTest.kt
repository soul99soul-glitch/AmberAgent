package app.amber.core.memory

import app.amber.core.memory.dream.MemoryDreamPlanner
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDreamPlannerTest {
    @Test
    fun localPlanBuildsReviewableDiffWithoutDeletingFormalMemories() {
        val now = 1_800_000_000_000L
        val plan = MemoryDreamPlanner.planLocally(
            records = listOf(
                MemoryRecord(
                    id = 1,
                    content = "用户喜欢中文简洁回复",
                    scope = MemoryScope.LONG_TERM,
                    kind = MemoryKind.FEEDBACK,
                    assistantId = "__long_term__",
                    confidence = 0.9f,
                    updatedAt = now,
                ),
                MemoryRecord(
                    id = 2,
                    content = "用户喜欢中文简洁回复",
                    scope = MemoryScope.SHORT_TERM,
                    kind = MemoryKind.NOTE,
                    assistantId = "__short_term__",
                    confidence = 0.6f,
                    updatedAt = now - 1,
                ),
                MemoryRecord(
                    id = 3,
                    content = "AmberAgent 记忆系统升级正在推进",
                    scope = MemoryScope.SHORT_TERM,
                    kind = MemoryKind.PROJECT,
                    assistantId = "__short_term__",
                    confidence = 0.88f,
                    expiresAt = null,
                    lastUsedAt = now,
                    updatedAt = now,
                ),
                MemoryRecord(
                    id = 4,
                    content = "已经结束的短期事项",
                    scope = MemoryScope.SHORT_TERM,
                    kind = MemoryKind.PROJECT,
                    assistantId = "__short_term__",
                    confidence = 0.7f,
                    expiresAt = now - 1,
                    updatedAt = now,
                ),
            ),
            candidates = listOf(
                MemoryCandidate(
                    id = "candidate-1",
                    content = "短",
                    scope = MemoryScope.LONG_TERM,
                    kind = MemoryKind.NOTE,
                )
            ),
            now = now,
        )

        assertEquals(1, plan.mergeSuggestions.size)
        assertEquals(1, plan.mergeSuggestions.single().targetMemoryId)
        assertEquals(listOf(2), plan.mergeSuggestions.single().duplicateMemoryIds)
        assertEquals(listOf(3), plan.promoteMemoryIds)
        assertEquals(listOf(4), plan.archiveMemoryIds)
        assertEquals(listOf("candidate-1"), plan.ignoreCandidateIds)
        assertTrue(plan.hasChanges)
    }
}
