package app.amber.core.memory.store

import app.amber.agent.data.db.dao.MemoryCandidateDAO
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.dao.MemoryEventDAO
import app.amber.agent.data.db.entity.MemoryCandidateEntity
import app.amber.agent.data.db.entity.MemoryEntity
import app.amber.agent.data.db.entity.MemoryEventEntity
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Lifecycle semantics of the optimized memory pass:
 *  - reinforcement counting (touch -> useCount +1);
 *  - supersede keeps the old record archived and linked, never overwrites;
 *  - restore revives an archived record and clears a stale expiresAt;
 *  - all three bind to a CAS revision — a stale snapshot fails loudly.
 */
class MemoryLifecycleTest {

    private class FakeMemoryDAO : MemoryDAO {
        val rows = mutableMapOf<Int, MemoryEntity>()
        private var nextId = 1

        fun seed(
            content: String,
            scope: MemoryScope = MemoryScope.LONG_TERM,
            kind: MemoryKind = MemoryKind.NOTE,
            revision: Long = 1,
            archived: Boolean = false,
            expiresAt: Long? = null,
            lastUsedAt: Long? = null,
            useCount: Int = 0,
            pinned: Boolean = false,
        ): MemoryEntity {
            val entity = MemoryEntity(
                id = nextId++,
                assistantId = when (scope) {
                    MemoryScope.CORE -> MemoryRepository.GLOBAL_MEMORY_ID
                    MemoryScope.SHORT_TERM -> MemoryRepository.SHORT_TERM_MEMORY_ID
                    MemoryScope.LONG_TERM -> MemoryRepository.LONG_TERM_MEMORY_ID
                },
                content = content,
                scope = scope.wireName,
                kind = kind.wireName,
                revision = revision,
                archived = archived,
                expiresAt = expiresAt,
                lastUsedAt = lastUsedAt,
                useCount = useCount,
                pinned = pinned,
            )
            rows[entity.id] = entity
            return entity
        }

        override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> = emptyFlow()
        override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
            rows.values.filter { it.assistantId == assistantId }
        override suspend fun getActiveMemories(now: Long?): List<MemoryEntity> =
            rows.values.filter { !it.archived && (it.expiresAt == null || now == null || it.expiresAt > now) }
        override suspend fun getActiveMemoriesByScopes(scopes: List<String>, now: Long?): List<MemoryEntity> =
            rows.values.filter { it.scope in scopes && !it.archived && (it.expiresAt == null || now == null || it.expiresAt > now) }
        override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = emptyFlow()
        override fun getMemoryCountsFlow(): Flow<List<app.amber.agent.data.db.dao.MemoryCount>> = emptyFlow()
        override suspend fun getAllMemories(): List<MemoryEntity> = rows.values.toList()
        override suspend fun getMemoryById(id: Int): MemoryEntity? = rows[id]
        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val id = if (memory.id == 0) nextId++ else memory.id
            rows[id] = memory.copy(id = id)
            return id.toLong()
        }
        override suspend fun updateMemory(memory: MemoryEntity) {
            rows[memory.id] = memory
        }
        override suspend fun updateContentBlind(id: Int, content: String, updatedAt: Long): Int {
            val current = rows[id] ?: return 0
            rows[id] = current.copy(content = content, revision = current.revision + 1, updatedAt = updatedAt)
            return 1
        }
        override suspend fun updateRecordCas(
            id: Int, assistantId: String, content: String, scope: String, kind: String,
            sourceConversationId: String?, sourceMessageIdsJson: String, supersedesIdsJson: String,
            expiresAt: Long?, confidence: Float, pinned: Boolean, archived: Boolean,
            createdAt: Long, updatedAt: Long, lastUsedAt: Long?, sourceRunId: String?,
            sourceTrigger: String?, topicTitle: String?, memberIdsJson: String,
            useCount: Int, expectedRevision: Long,
        ): Int {
            val current = rows[id] ?: return 0
            if (current.revision != expectedRevision) return 0
            rows[id] = current.copy(
                assistantId = assistantId, content = content, scope = scope, kind = kind,
                sourceConversationId = sourceConversationId,
                sourceMessageIdsJson = sourceMessageIdsJson,
                supersedesIdsJson = supersedesIdsJson,
                expiresAt = expiresAt, confidence = confidence, pinned = pinned,
                archived = archived, createdAt = createdAt, updatedAt = updatedAt,
                lastUsedAt = lastUsedAt, revision = current.revision + 1,
                sourceRunId = sourceRunId, sourceTrigger = sourceTrigger,
                topicTitle = topicTitle, memberIdsJson = memberIdsJson,
                useCount = useCount,
            )
            return 1
        }
        override suspend fun updateContentCas(
            id: Int, content: String, expectedRevision: Long,
            updatedAt: Long, sourceRunId: String?, sourceTrigger: String?,
        ): Int {
            val current = rows[id] ?: return 0
            if (current.revision != expectedRevision) return 0
            rows[id] = current.copy(
                content = content, revision = expectedRevision + 1, updatedAt = updatedAt,
                sourceRunId = sourceRunId, sourceTrigger = sourceTrigger,
            )
            return 1
        }
        override suspend fun deleteCas(id: Int, expectedRevision: Long): Int {
            val current = rows[id] ?: return 0
            if (current.revision != expectedRevision) return 0
            rows.remove(id)
            return 1
        }
        override suspend fun revisionOf(id: Int): Long? = rows[id]?.revision
        override fun getArchivedMemoriesFlow(): Flow<List<MemoryEntity>> = emptyFlow()
        override suspend fun touchMemories(ids: List<Int>, usedAt: Long) {
            ids.forEach { id ->
                rows[id]?.let { rows[id] = it.copy(lastUsedAt = usedAt, useCount = it.useCount + 1) }
            }
        }
        override suspend fun deleteMemory(id: Int) {
            rows.remove(id)
        }
        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            rows.entries.removeAll { it.value.assistantId == assistantId }
        }
    }

    private open class FakeCandidateDAO : MemoryCandidateDAO {
        val rows = linkedMapOf<String, MemoryCandidateEntity>()

        fun seed(
            id: String,
            reason: String,
            content: String = "复述确认的内容",
            scope: MemoryScope = MemoryScope.LONG_TERM,
            kind: MemoryKind = MemoryKind.USER,
        ): MemoryCandidateEntity {
            val entity = MemoryCandidateEntity(
                id = id,
                content = content,
                scope = scope.wireName,
                kind = kind.wireName,
                sourceConversationId = "conv-1",
                sourceMessageIdsJson = "[]",
                expiresAt = null,
                confidence = 0.8f,
                reason = reason,
                sensitive = false,
                status = "pending",
                createdAt = 1L,
                updatedAt = 1L,
            )
            rows[id] = entity
            return entity
        }

        override fun getCandidatesFlow(): Flow<List<MemoryCandidateEntity>> = emptyFlow()
        override fun getCandidatesByStatusFlow(status: String): Flow<List<MemoryCandidateEntity>> = emptyFlow()
        override fun countCandidatesByStatusFlow(status: String): Flow<Int> = emptyFlow()
        override suspend fun getCandidatesByStatus(status: String): List<MemoryCandidateEntity> =
            rows.values.filter { it.status == status }
        override suspend fun getAllCandidates(): List<MemoryCandidateEntity> = rows.values.toList()
        override suspend fun getCandidateById(id: String): MemoryCandidateEntity? = rows[id]
        override suspend fun insert(candidate: MemoryCandidateEntity) {
            rows[candidate.id] = candidate
        }
        override suspend fun insertAll(candidates: List<MemoryCandidateEntity>) {
            candidates.forEach { insert(it) }
        }
        override suspend fun update(candidate: MemoryCandidateEntity) {
            rows[candidate.id] = candidate
        }
    }

    private class EmptyCandidateDAO : FakeCandidateDAO()

    private open class FakeEventDAO : MemoryEventDAO {
        val events = mutableListOf<MemoryEventEntity>()
        override fun getRecentEventsFlow(limit: Int): Flow<List<MemoryEventEntity>> = emptyFlow()
        override suspend fun getRecentEvents(limit: Int): List<MemoryEventEntity> = events
        override suspend fun getEventsOfConversation(conversationId: String, limit: Int): List<MemoryEventEntity> = emptyList()
        override suspend fun countEventsSince(eventType: String, createdAfter: Long): Int = 0
        override suspend fun insert(event: MemoryEventEntity) {
            events += event
        }
    }

    private class EmptyEventDAO : FakeEventDAO()

    private fun repository(
        dao: FakeMemoryDAO,
        candidateDAO: MemoryCandidateDAO = EmptyCandidateDAO(),
        eventDAO: MemoryEventDAO = EmptyEventDAO(),
    ) = MemoryRepository(dao, candidateDAO, eventDAO)

    @Test
    fun `touchMemories reinforces use count`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val seeded = dao.seed("reinforced by recall")

        repo.touchMemories(listOf(seeded.id))
        repo.touchMemories(listOf(seeded.id))

        val row = dao.rows.getValue(seeded.id)
        assertEquals(2, row.useCount)
        assertTrue(row.lastUsedAt != null)
    }

    @Test
    fun `unrelated updates preserve the use count`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val seeded = dao.seed("count must survive edits", useCount = 7)

        repo.updateContentCas(seeded.id, "edited", expectedRevision = 1)

        assertEquals(7, dao.rows.getValue(seeded.id).useCount)
    }

    @Test
    fun `supersede archives the old record and links the new one`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val seeded = dao.seed(
            "用户偏好中文简洁回复",
            kind = MemoryKind.USER,
            useCount = 3,
            lastUsedAt = 123L,
        )

        val created = repo.supersedeMemory(
            targetId = seeded.id,
            newContent = "用户现在偏好英文详细回复",
            expectedRevision = seeded.revision,
            confidence = 0.9f,
            sourceTrigger = MemoryRepository.TRIGGER_AUTO_EXTRACTION,
        )

        val old = dao.rows.getValue(seeded.id)
        assertTrue(old.archived)
        // The replacement is a fresh record chained back to the old id —
        // history stays recoverable instead of overwritten.
        assertNotEquals(seeded.id, created.id)
        assertEquals(listOf(seeded.id), created.supersedesIds)
        assertEquals("用户现在偏好英文详细回复", created.content)
        assertEquals(1, created.revision)
        assertEquals(old.useCount, created.useCount)
        assertEquals(old.lastUsedAt, created.lastUsedAt)
        // Only one record remains active.
        assertEquals(1, repo.getAllRecords().count { !it.archived })
    }

    @Test
    fun `supersede on a stale snapshot changes nothing`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val seeded = dao.seed("original")
        repo.updateContentCas(seeded.id, "newer", expectedRevision = 1)

        try {
            repo.supersedeMemory(seeded.id, "stale replacement", expectedRevision = 1)
            fail("expected MemoryStaleException")
        } catch (error: MemoryStaleException) {
            assertEquals(2, error.actualRevision)
        }

        // No orphan replacement, no archived original: the stale write was
        // rejected before touching any row.
        val row = dao.rows.getValue(seeded.id)
        assertFalse(row.archived)
        assertEquals("newer", row.content)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `restore revives an archived record and clears a stale expiry`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val now = System.currentTimeMillis()
        val seeded = dao.seed(
            "expired while archived",
            archived = true,
            expiresAt = now - 1_000,
        )

        val restored = repo.restoreMemory(seeded.id, expectedRevision = seeded.revision)

        val row = dao.rows.getValue(seeded.id)
        assertFalse(row.archived)
        assertNull(row.expiresAt)
        assertEquals(seeded.revision + 1, restored.revision)
    }

    @Test
    fun `restore keeps a still-valid expiry`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val now = System.currentTimeMillis()
        val future = now + 3_600_000
        val seeded = dao.seed("archived but still fresh", archived = true, expiresAt = future)

        repo.restoreMemory(seeded.id, expectedRevision = seeded.revision)

        assertEquals(future, dao.rows.getValue(seeded.id).expiresAt)
    }

    @Test
    fun `restore binds to the seen revision`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val seeded = dao.seed("archived", archived = true, revision = 4)

        try {
            repo.restoreMemory(seeded.id, expectedRevision = 3)
            fail("expected MemoryStaleException")
        } catch (error: MemoryStaleException) {
            assertEquals(4, error.actualRevision)
        }
        assertTrue(dao.rows.getValue(seeded.id).archived)
    }

    @Test
    fun `archived records leave recall entirely`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val now = System.currentTimeMillis()
        dao.seed("active record", archived = false)
        dao.seed("archived record", archived = true)
        dao.seed("expired record", archived = false, expiresAt = now - 1)

        val active = repo.getAllActiveRecords(now)

        assertEquals(listOf("active record"), active.map { it.content })
    }

    private fun assertNotEquals(unexpected: Int, actual: Int) {
        assertTrue("expected a new id, got $actual", unexpected != actual)
    }

    @Test
    fun `supersede drops an expiry that already passed`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val now = System.currentTimeMillis()
        val seeded = dao.seed("过期的旧事实", expiresAt = now - 1_000)

        val created = repo.supersedeMemory(
            targetId = seeded.id,
            newContent = "更新后的事实",
            expectedRevision = seeded.revision,
        )

        // Inheriting a past expiry would birth a record already dead.
        assertNull(created.expiresAt)
    }

    @Test
    fun `supersede keeps a still-valid expiry`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val future = System.currentTimeMillis() + 3_600_000
        val seeded = dao.seed("带时限的旧事实", expiresAt = future)

        val created = repo.supersedeMemory(
            targetId = seeded.id,
            newContent = "更新后的时限事实",
            expectedRevision = seeded.revision,
        )

        assertEquals(future, created.expiresAt)
    }

    @Test
    fun `accepting a confirm candidate reinforces the target and logs`() = runBlocking {
        val dao = FakeMemoryDAO()
        val candidateDAO = FakeCandidateDAO()
        val eventDAO = FakeEventDAO()
        val repo = repository(dao, candidateDAO, eventDAO)
        val target = dao.seed("用户偏好中文简洁回复", kind = MemoryKind.USER)
        candidateDAO.seed(id = "cand-1", reason = "confirms memory #${target.id}: 用户重申")

        val record = repo.acceptCandidate("cand-1")

        val row = dao.rows.getValue(target.id)
        assertEquals(target.id, record.id)
        assertEquals(1, row.useCount)
        assertFalse(row.archived)
        assertEquals("accepted", candidateDAO.rows.getValue("cand-1").status)
        assertTrue(eventDAO.events.any { it.eventType == "candidate_accepted" })
    }

    @Test
    fun `accepting a confirm on an archived target re-adds a fresh record`() = runBlocking {
        val dao = FakeMemoryDAO()
        val candidateDAO = FakeCandidateDAO()
        val repo = repository(dao, candidateDAO)
        val target = dao.seed("旧的偏好", kind = MemoryKind.USER, archived = true)
        candidateDAO.seed(
            id = "cand-2",
            reason = "confirms memory #${target.id}: 用户重申",
            content = "用户仍然偏好中文简洁回复",
        )

        val record = repo.acceptCandidate("cand-2")

        // An archived target cannot be reinforced in place — the fact returns
        // as a brand-new live record instead of an invisible +1 on a dead row.
        assertNotEquals(target.id, record.id)
        assertEquals("用户仍然偏好中文简洁回复", record.content)
        assertTrue(dao.rows.getValue(target.id).archived)
        assertFalse(record.archived)
    }

    @Test
    fun `accepting an update candidate supersedes the target`() = runBlocking {
        val dao = FakeMemoryDAO()
        val candidateDAO = FakeCandidateDAO()
        val repo = repository(dao, candidateDAO)
        val target = dao.seed("旧版本事实", kind = MemoryKind.USER)
        candidateDAO.seed(
            id = "cand-3",
            reason = "updates memory #${target.id}: 用户更正",
            content = "新版本事实",
        )

        val record = repo.acceptCandidate("cand-3")

        assertNotEquals(target.id, record.id)
        assertEquals("新版本事实", record.content)
        assertEquals(listOf(target.id), record.supersedesIds)
        assertTrue(dao.rows.getValue(target.id).archived)
        assertEquals("accepted", candidateDAO.rows.getValue("cand-3").status)
    }
}
