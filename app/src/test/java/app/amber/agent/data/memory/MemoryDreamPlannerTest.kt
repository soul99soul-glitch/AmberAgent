package app.amber.core.memory

import kotlinx.serialization.json.Json
import app.amber.core.memory.dream.MemoryDreamPlanner
import app.amber.core.memory.dream.MemoryDreamPlan
import app.amber.core.memory.dream.MemorySupersedeSuggestion
import app.amber.core.memory.dream.mergeWith
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.model.MemoryWorkerDreamGate
import app.amber.core.memory.model.MemoryWorkerSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    createdAt = now - 1_000,
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

    @Test
    fun dreamGateUsesSplitTogglesAfterLegacyMigration() {
        val disabled = MemoryWorkerSetting(dreamMaintenanceEnabled = false, dreamModelEnabled = false)
        val maintenanceOnly = MemoryWorkerSetting(dreamMaintenanceEnabled = true, dreamModelEnabled = false)
        val modelOnly = MemoryWorkerSetting(dreamMaintenanceEnabled = false, dreamModelEnabled = true)
        val legacyOnly = MemoryWorkerSetting(
            dreamMaintenanceEnabled = false,
            dreamModelEnabled = false,
            dreamEnabled = true,
        )
        val migratedLegacy = legacyOnly.copy(dreamModelEnabled = true, dreamEnabled = false)

        assertFalse(MemoryWorkerDreamGate.isAnyDreamEnabled(disabled))
        assertTrue(MemoryWorkerDreamGate.isAnyDreamEnabled(maintenanceOnly))
        assertTrue(MemoryWorkerDreamGate.isMaintenanceEnabled(maintenanceOnly))
        assertFalse(MemoryWorkerDreamGate.isModelDreamEnabled(maintenanceOnly))
        assertTrue(MemoryWorkerDreamGate.isAnyDreamEnabled(modelOnly))
        assertTrue(MemoryWorkerDreamGate.isModelDreamEnabled(modelOnly))
        assertFalse(MemoryWorkerDreamGate.isAnyDreamEnabled(legacyOnly))
        assertTrue(MemoryWorkerDreamGate.isAnyDreamEnabled(migratedLegacy))
        assertTrue(MemoryWorkerDreamGate.isModelDreamEnabled(migratedLegacy))
    }

    @Test
    fun modelPlanParsesSupersedeSuggestions() {
        val plan = MemoryDreamPlanner.parseModelPlanJson(
            raw = """
                {
                  "merge": [],
                  "promote": [],
                  "archive": [],
                  "delete_suggestions": [],
                  "supersede": [
                    {
                      "old_memory_ids": [1, 99],
                      "new_content": "用户现在偏好英文详细解释。",
                      "scope": "long_term",
                      "kind": "user",
                      "confidence": 0.86,
                      "reason": "Newer preference conflicts with older one."
                    }
                  ],
                  "notes": ["review required"]
                }
            """.trimIndent(),
            records = listOf(
                MemoryRecord(
                    id = 1,
                    content = "用户偏好中文简洁回复。",
                    scope = MemoryScope.LONG_TERM,
                    kind = MemoryKind.USER,
                    assistantId = "__long_term__",
                )
            ),
            candidates = emptyList(),
            json = Json,
        )

        assertTrue(plan.hasChanges)
        assertEquals(1, plan.supersedeSuggestions.size)
        val suggestion = plan.supersedeSuggestions.single()
        assertEquals(listOf(1), suggestion.oldMemoryIds)
        assertEquals("用户现在偏好英文详细解释。", suggestion.newContent)
        assertEquals(MemoryScope.LONG_TERM, suggestion.scope)
        assertEquals(MemoryKind.USER, suggestion.kind)
        assertEquals(0.86f, suggestion.confidence)
    }

    @Test
    fun oldPendingPlanJsonCanDecodeWithoutSupersedeField() {
        val legacyJson = """{"mergeSuggestions":[],"promoteMemoryIds":[1],"archiveMemoryIds":[],"ignoreCandidateIds":[],"notes":[]}"""
        val plan = Json.decodeFromString(MemoryDreamPlan.serializer(), legacyJson)

        assertEquals(listOf(1), plan.promoteMemoryIds)
        assertTrue(plan.supersedeSuggestions.isEmpty())
        assertTrue(plan.hasChanges)
    }

    @Test
    fun maintenanceIgnoresExpiredAndAgedOutCandidates() {
        val now = 1_800_000_000_000L
        val plan = MemoryDreamPlanner.planLocally(
            records = emptyList(),
            candidates = listOf(
                MemoryCandidate(
                    id = "expired",
                    content = "这条候选已经过期不再有效",
                    scope = MemoryScope.SHORT_TERM,
                    kind = MemoryKind.PROJECT,
                    expiresAt = now - 1,
                    confidence = 0.9f,
                    createdAt = now - 1_000,
                ),
                MemoryCandidate(
                    id = "aged",
                    content = "这条候选长期无人审批未处理",
                    scope = MemoryScope.LONG_TERM,
                    kind = MemoryKind.NOTE,
                    expiresAt = null,
                    confidence = 0.9f,
                    createdAt = now - MemoryDreamPlanner.PENDING_CANDIDATE_TTL_MS - 1,
                ),
                MemoryCandidate(
                    id = "boundary",
                    content = "这条候选恰好在过期边界上",
                    scope = MemoryScope.SHORT_TERM,
                    kind = MemoryKind.PROJECT,
                    expiresAt = now,
                    confidence = 0.9f,
                    createdAt = now - 1_000,
                ),
                MemoryCandidate(
                    id = "fresh",
                    content = "这条候选刚产生值得保留下来",
                    scope = MemoryScope.LONG_TERM,
                    kind = MemoryKind.NOTE,
                    expiresAt = now + 1_000,
                    confidence = 0.9f,
                    createdAt = now - 1_000,
                ),
            ),
            now = now,
        )

        assertEquals(listOf("expired", "aged", "boundary"), plan.ignoreCandidateIds)
    }

    @Test
    fun maintenanceOverflowDropsLowestConfidencePendingCandidates() {
        val now = 1_800_000_000_000L
        val candidates = (1..MemoryDreamPlanner.MAX_PENDING_CANDIDATES + 3).map { index ->
            MemoryCandidate(
                id = "candidate-$index",
                content = "第 $index 条等待审批的候选记忆",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.NOTE,
                confidence = 0.9f - index * 0.001f,
                createdAt = now - 1_000,
            )
        }
        val plan = MemoryDreamPlanner.planLocally(
            records = emptyList(),
            candidates = candidates,
            now = now,
        )

        // 103 pending, cap 100 -> the three lowest-confidence ids are dropped.
        assertEquals(
            listOf("candidate-103", "candidate-102", "candidate-101"),
            plan.ignoreCandidateIds,
        )
    }

    @Test
    fun maintenanceOverflowAccountsForAlreadyFlaggedCandidates() {
        val now = 1_800_000_000_000L
        val noisy = MemoryCandidate(
            id = "noisy",
            content = "x",
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.NOTE,
            confidence = 0.1f,
            createdAt = now - 1_000,
        )
        val rest = (1..MemoryDreamPlanner.MAX_PENDING_CANDIDATES).map { index ->
            MemoryCandidate(
                id = "ok-$index",
                content = "第 $index 条正常的候选记忆",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.NOTE,
                confidence = 0.9f - index * 0.001f,
                createdAt = now - 1_000,
            )
        }
        val plan = MemoryDreamPlanner.planLocally(
            records = emptyList(),
            candidates = rest + noisy,
            now = now,
        )

        // 101 pending, cap 100, one already flagged as noisy -> nothing extra dropped.
        assertEquals(listOf("noisy"), plan.ignoreCandidateIds)
    }

    @Test
    fun modelPlanParsesTopicsAndFiltersMembersToManagedRecords() {
        val plan = MemoryDreamPlanner.parseModelPlanJson(
            raw = """
                {
                  "topics": [
                    {
                      "title": "回复风格偏好",
                      "member_memory_ids": [1, 2, 3, 4, 5, 99],
                      "content": "用户偏好中文简洁回复，并要求分点列出。",
                      "reason": "same preference cluster"
                    },
                    {
                      "title": "单成员主题",
                      "member_memory_ids": [1],
                      "content": "只有一条成员不应成题。"
                    }
                  ],
                  "notes": []
                }
            """.trimIndent(),
            records = listOf(
                MemoryRecord(id = 1, content = "a", scope = MemoryScope.LONG_TERM, kind = MemoryKind.USER, assistantId = "__long_term__"),
                MemoryRecord(id = 2, content = "b", scope = MemoryScope.LONG_TERM, kind = MemoryKind.USER, assistantId = "__long_term__"),
                MemoryRecord(id = 3, content = "c", scope = MemoryScope.CORE, kind = MemoryKind.USER, assistantId = "__global__"),
                MemoryRecord(id = 4, content = "d", scope = MemoryScope.LONG_TERM, kind = MemoryKind.TOPIC, assistantId = "__long_term__"),
                MemoryRecord(id = 5, content = "e", scope = MemoryScope.LONG_TERM, kind = MemoryKind.USER, assistantId = "__long_term__", archived = true),
            ),
            candidates = emptyList(),
            json = Json,
        )

        assertEquals(1, plan.topicSuggestions.size)
        val topic = plan.topicSuggestions.single()
        assertEquals("回复风格偏好", topic.title)
        // Core (#3), topic (#4), archived (#5), and unknown (#99) ids are filtered out.
        assertEquals(listOf(1, 2), topic.memberMemoryIds)
        assertTrue(plan.hasChanges)
    }

    @Test
    fun hasChangesAndMergeIncludeSupersedeSuggestions() {
        val supersede = MemorySupersedeSuggestion(
            oldMemoryIds = listOf(1),
            newContent = "用户现在偏好英文详细解释。",
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.USER,
            confidence = 0.86f,
        )
        val local = MemoryDreamPlan(promoteMemoryIds = listOf(2))
        val model = MemoryDreamPlan(supersedeSuggestions = listOf(supersede))

        val merged = local.mergeWith(model)

        assertTrue(model.hasChanges)
        assertEquals(listOf(2), merged.promoteMemoryIds)
        assertEquals(listOf(supersede), merged.supersedeSuggestions)
    }
}
