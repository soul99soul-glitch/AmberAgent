package app.amber.core.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import app.amber.agent.data.db.dao.MemoryCandidateDAO
import app.amber.agent.data.db.dao.MemoryCount
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.dao.MemoryEventDAO
import app.amber.agent.data.db.entity.MemoryCandidateEntity
import app.amber.agent.data.db.entity.MemoryEntity
import app.amber.agent.data.db.entity.MemoryEventEntity
import app.amber.core.memory.dream.MemoryDreamApplier
import app.amber.core.memory.dream.MemoryDreamPlan
import app.amber.core.memory.dream.MemorySupersedeSuggestion
import app.amber.core.memory.model.MemoryEventType
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.telemetry.MemoryEventLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDreamApplierTest {
    @Test
    fun supersedeCreatesNewRecordArchivesOldAndLogsEvents() = runBlocking {
        val memoryDao = FakeMemoryDao(
            listOf(
                entity(
                    id = 1,
                    assistantId = MemoryRepository.LONG_TERM_MEMORY_ID,
                    content = "用户偏好中文简洁回复。",
                    scope = MemoryScope.LONG_TERM,
                    kind = MemoryKind.USER,
                    sourceConversationId = "conversation-1",
                    sourceMessageIdsJson = """["message-1"]""",
                )
            )
        )
        val eventDao = FakeMemoryEventDao()
        val repository = MemoryRepository(memoryDao, FakeMemoryCandidateDao(), eventDao)
        val applier = MemoryDreamApplier(repository, MemoryEventLogger(repository))

        val applied = applier.apply(
            MemoryDreamPlan(
                supersedeSuggestions = listOf(
                    MemorySupersedeSuggestion(
                        oldMemoryIds = listOf(1),
                        newContent = "用户现在偏好英文详细解释。",
                        scope = MemoryScope.LONG_TERM,
                        kind = MemoryKind.USER,
                        confidence = 0.86f,
                        reason = "Newer preference conflicts with older preference.",
                    )
                )
            )
        )

        val records = repository.getAllRecords().associateBy { it.id }
        val newRecord = records.values.single { it.id != 1 }
        assertEquals(1, applied.supersedeSuggestions.size)
        assertTrue(records.getValue(1).archived)
        assertEquals("用户现在偏好英文详细解释。", newRecord.content)
        assertEquals(MemoryRepository.LONG_TERM_MEMORY_ID, newRecord.assistantId)
        assertEquals(listOf(1), newRecord.supersedesIds)
        assertEquals("conversation-1", newRecord.sourceConversationId)
        assertEquals(listOf("message-1"), newRecord.sourceMessageIds)
        assertTrue(eventDao.events.any { it.eventType == MemoryEventType.MEMORY_CREATED.wireName })
        assertTrue(eventDao.events.any { it.eventType == MemoryEventType.MEMORY_ARCHIVED.wireName })
        assertTrue(eventDao.events.any { it.eventType == MemoryEventType.DREAM_APPLIED.wireName })
    }

    @Test
    fun supersedeRejectsCorePinnedSensitiveNoteLowConfidenceAndShortContent() = runBlocking {
        val memoryDao = FakeMemoryDao(
            listOf(
                entity(1, "__global__", "核心记忆", MemoryScope.CORE, MemoryKind.USER),
                entity(2, "__long_term__", "用户偏好中文简洁回复。", MemoryScope.LONG_TERM, MemoryKind.USER, pinned = true),
                entity(3, "__long_term__", "用户的密码是 123456。", MemoryScope.LONG_TERM, MemoryKind.USER),
                entity(4, "__long_term__", "用户偏好中文简洁回复。", MemoryScope.LONG_TERM, MemoryKind.USER),
            )
        )
        val eventDao = FakeMemoryEventDao()
        val repository = MemoryRepository(memoryDao, FakeMemoryCandidateDao(), eventDao)
        val applier = MemoryDreamApplier(repository, MemoryEventLogger(repository))

        val applied = applier.apply(
            MemoryDreamPlan(
                supersedeSuggestions = listOf(
                    supersede(oldId = 1),
                    supersede(oldId = 2),
                    supersede(oldId = 3),
                    supersede(oldId = 4, kind = MemoryKind.NOTE),
                    supersede(oldId = 4, confidence = 0.69f),
                    supersede(oldId = 4, newContent = "太短"),
                    supersede(oldId = 4, newContent = "用户的密码是 123456。"),
                )
            )
        )

        assertTrue(applied.supersedeSuggestions.isEmpty())
        assertEquals(4, repository.getAllRecords().size)
        assertFalse(repository.getAllRecords().any { it.archived })
        assertTrue(eventDao.events.isEmpty())
    }

    private fun supersede(
        oldId: Int,
        newContent: String = "用户现在偏好英文详细解释。",
        kind: MemoryKind = MemoryKind.USER,
        confidence: Float = 0.86f,
    ) = MemorySupersedeSuggestion(
        oldMemoryIds = listOf(oldId),
        newContent = newContent,
        scope = MemoryScope.LONG_TERM,
        kind = kind,
        confidence = confidence,
    )

    private fun entity(
        id: Int,
        assistantId: String,
        content: String,
        scope: MemoryScope,
        kind: MemoryKind,
        sourceConversationId: String? = null,
        sourceMessageIdsJson: String = "[]",
        pinned: Boolean = false,
    ) = MemoryEntity(
        id = id,
        assistantId = assistantId,
        content = content,
        scope = scope.wireName,
        kind = kind.wireName,
        sourceConversationId = sourceConversationId,
        sourceMessageIdsJson = sourceMessageIdsJson,
        confidence = 0.9f,
        pinned = pinned,
        archived = false,
        createdAt = 1_000L + id,
        updatedAt = 1_000L + id,
    )
}

private class FakeMemoryDao(
    initial: List<MemoryEntity> = emptyList(),
) : MemoryDAO {
    private val memories = initial.toMutableList()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> =
        flowOf(memories.filter { it.assistantId == assistantId })

    override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
        memories.filter { it.assistantId == assistantId }

    override suspend fun getActiveMemories(now: Long?): List<MemoryEntity> =
        memories.filter { !it.archived && (now == null || it.expiresAt == null || it.expiresAt > now) }

    override suspend fun getActiveMemoriesByScopes(scopes: List<String>, now: Long?): List<MemoryEntity> =
        getActiveMemories(now).filter { it.scope in scopes }

    override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = flowOf(memories.toList())

    override fun getMemoryCountsFlow(): Flow<List<MemoryCount>> =
        flowOf(memories.groupBy { it.assistantId }.map { (assistantId, rows) -> MemoryCount(assistantId, rows.size) })

    override suspend fun getAllMemories(): List<MemoryEntity> = memories.toList()

    override suspend fun getMemoryById(id: Int): MemoryEntity? = memories.firstOrNull { it.id == id }

    override suspend fun insertMemory(memory: MemoryEntity): Long {
        val id = memory.id.takeIf { it != 0 } ?: nextId++
        memories.removeAll { it.id == id }
        memories += memory.copy(id = id)
        return id.toLong()
    }

    override suspend fun updateMemory(memory: MemoryEntity) {
        memories.replaceAll { if (it.id == memory.id) memory else it }
    }

    override suspend fun touchMemories(ids: List<Int>, usedAt: Long) {
        memories.replaceAll { if (it.id in ids) it.copy(lastUsedAt = usedAt) else it }
    }

    override suspend fun deleteMemory(id: Int) {
        memories.removeAll { it.id == id }
    }

    override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memories.removeAll { it.assistantId == assistantId }
    }
}

private class FakeMemoryCandidateDao : MemoryCandidateDAO {
    private val candidates = mutableListOf<MemoryCandidateEntity>()

    override fun getCandidatesFlow(): Flow<List<MemoryCandidateEntity>> = flowOf(candidates.toList())

    override fun getCandidatesByStatusFlow(status: String): Flow<List<MemoryCandidateEntity>> =
        flowOf(candidates.filter { it.status == status })

    override suspend fun getCandidatesByStatus(status: String): List<MemoryCandidateEntity> =
        candidates.filter { it.status == status }

    override suspend fun getAllCandidates(): List<MemoryCandidateEntity> = candidates.toList()

    override suspend fun getCandidateById(id: String): MemoryCandidateEntity? = candidates.firstOrNull { it.id == id }

    override suspend fun insert(candidate: MemoryCandidateEntity) {
        candidates.removeAll { it.id == candidate.id }
        candidates += candidate
    }

    override suspend fun insertAll(candidates: List<MemoryCandidateEntity>) {
        candidates.forEach { insert(it) }
    }

    override suspend fun update(candidate: MemoryCandidateEntity) {
        candidates.replaceAll { if (it.id == candidate.id) candidate else it }
    }
}

private class FakeMemoryEventDao : MemoryEventDAO {
    val events = mutableListOf<MemoryEventEntity>()

    override fun getRecentEventsFlow(limit: Int): Flow<List<MemoryEventEntity>> =
        flowOf(events.sortedByDescending { it.createdAt }.take(limit))

    override suspend fun getRecentEvents(limit: Int): List<MemoryEventEntity> =
        events.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun getEventsOfConversation(conversationId: String, limit: Int): List<MemoryEventEntity> =
        events.filter { it.conversationId == conversationId }.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun countEventsSince(eventType: String, createdAfter: Long): Int =
        events.count { it.eventType == eventType && it.createdAt >= createdAfter }

    override suspend fun insert(event: MemoryEventEntity) {
        events.removeAll { it.id == event.id }
        events += event
    }
}
