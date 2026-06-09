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
