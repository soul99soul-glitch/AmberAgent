package app.amber.core.memory.store

import app.amber.agent.data.db.dao.MemoryCandidateDAO
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.dao.MemoryEventDAO
import app.amber.agent.data.db.entity.MemoryCandidateEntity
import app.amber.agent.data.db.entity.MemoryEntity
import app.amber.agent.data.db.entity.MemoryEventEntity
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryScope
import app.amber.feature.runtime.ContentDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P2-06 memory write CAS and pollution audit (parity plan §P2-06).
 *
 * Acceptance covered:
 *  - memory records carry a monotonic revision (start at 1, bump per edit);
 *  - edit/delete are compare-and-set: a changed revision rejects the stale
 *    approval (MemoryStaleException) instead of overwriting;
 *  - successful CAS writes expose old/new digests for the audit trail and
 *    record provenance (source_run_id / source_trigger);
 *  - auto-extraction writes are marked with a distinct trigger.
 */
class MemoryCasTest {

    /** In-memory MemoryDAO simulating the SQL CAS guards exactly. */
    private class FakeMemoryDAO : MemoryDAO {
        val rows = mutableMapOf<Int, MemoryEntity>()
        private var nextId = 1

        fun seed(content: String, revision: Long = 1): MemoryEntity {
            val entity = MemoryEntity(
                id = nextId++,
                assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
                content = content,
                scope = MemoryScope.CORE.wireName,
                revision = revision,
            )
            rows[entity.id] = entity
            return entity
        }

        override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> = emptyFlow()

        override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
            rows.values.filter { it.assistantId == assistantId }

        override suspend fun getActiveMemories(now: Long?): List<MemoryEntity> = rows.values.toList()

        override suspend fun getActiveMemoriesByScopes(scopes: List<String>, now: Long?): List<MemoryEntity> =
            rows.values.filter { it.scope in scopes }

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
            rows[id] = current.copy(
                content = content,
                revision = current.revision + 1,
                updatedAt = updatedAt,
            )
            return 1
        }

        override suspend fun updateRecordCas(
            id: Int,
            assistantId: String,
            content: String,
            scope: String,
            kind: String,
            sourceConversationId: String?,
            sourceMessageIdsJson: String,
            supersedesIdsJson: String,
            expiresAt: Long?,
            confidence: Float,
            pinned: Boolean,
            archived: Boolean,
            createdAt: Long,
            updatedAt: Long,
            lastUsedAt: Long?,
            sourceRunId: String?,
            sourceTrigger: String?,
            expectedRevision: Long,
        ): Int {
            val current = rows[id] ?: return 0
            if (current.revision != expectedRevision) return 0
            rows[id] = current.copy(
                assistantId = assistantId,
                content = content,
                scope = scope,
                kind = kind,
                sourceConversationId = sourceConversationId,
                sourceMessageIdsJson = sourceMessageIdsJson,
                supersedesIdsJson = supersedesIdsJson,
                expiresAt = expiresAt,
                confidence = confidence,
                pinned = pinned,
                archived = archived,
                createdAt = createdAt,
                updatedAt = updatedAt,
                lastUsedAt = lastUsedAt,
                revision = current.revision + 1,
                sourceRunId = sourceRunId,
                sourceTrigger = sourceTrigger,
            )
            return 1
        }

        override suspend fun updateContentCas(
            id: Int,
            content: String,
            expectedRevision: Long,
            updatedAt: Long,
            sourceRunId: String?,
            sourceTrigger: String?,
        ): Int {
            val current = rows[id] ?: return 0
            if (current.revision != expectedRevision) return 0
            rows[id] = current.copy(
                content = content,
                revision = expectedRevision + 1,
                updatedAt = updatedAt,
                sourceRunId = sourceRunId,
                sourceTrigger = sourceTrigger,
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

        override suspend fun touchMemories(ids: List<Int>, usedAt: Long) {
            ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(lastUsedAt = usedAt) } }
        }

        override suspend fun deleteMemory(id: Int) {
            rows.remove(id)
        }

        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            rows.entries.removeAll { it.value.assistantId == assistantId }
        }
    }

    private class EmptyCandidateDAO : MemoryCandidateDAO {
        override fun getCandidatesFlow(): Flow<List<MemoryCandidateEntity>> = emptyFlow()
        override fun getCandidatesByStatusFlow(status: String): Flow<List<MemoryCandidateEntity>> = emptyFlow()
        override suspend fun getCandidatesByStatus(status: String): List<MemoryCandidateEntity> = emptyList()
        override suspend fun getAllCandidates(): List<MemoryCandidateEntity> = emptyList()
        override suspend fun getCandidateById(id: String): MemoryCandidateEntity? = null
        override suspend fun insert(candidate: MemoryCandidateEntity) = Unit
        override suspend fun insertAll(candidates: List<MemoryCandidateEntity>) = Unit
        override suspend fun update(candidate: MemoryCandidateEntity) = Unit
    }

    private class EmptyEventDAO : MemoryEventDAO {
        override fun getRecentEventsFlow(limit: Int): Flow<List<MemoryEventEntity>> = emptyFlow()
        override suspend fun getRecentEvents(limit: Int): List<MemoryEventEntity> = emptyList()
        override suspend fun getEventsOfConversation(conversationId: String, limit: Int): List<MemoryEventEntity> = emptyList()
        override suspend fun countEventsSince(eventType: String, createdAfter: Long): Int = 0
        override suspend fun insert(event: MemoryEventEntity) = Unit
    }

    private fun repository(dao: FakeMemoryDAO) = MemoryRepository(dao, EmptyCandidateDAO(), EmptyEventDAO())

    @Test
    fun `memory records start at revision 1 and bump on each CAS edit`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val created = repo.addMemory(
            scope = MemoryScope.CORE,
            kind = MemoryKind.NOTE,
            content = "v1 content",
            sourceRunId = "run-1",
            sourceTrigger = MemoryRepository.TRIGGER_TOOL,
        )
        assertEquals(1, created.revision)
        assertEquals("run-1", created.sourceRunId)
        assertEquals("tool", created.sourceTrigger)

        val updated = repo.updateContentCas(
            id = created.id,
            content = "v2 content",
            expectedRevision = 1,
            sourceRunId = "run-2",
            sourceTrigger = MemoryRepository.TRIGGER_TOOL,
        )
        assertEquals(2, updated.memory.revision)
        assertEquals("v2 content", updated.memory.content)

        // Audit digests: old digest hashes the previous content, new the current.
        assertEquals(ContentDigest.sha256("v1 content"), updated.oldDigest)
        assertEquals(ContentDigest.sha256("v2 content"), updated.newDigest)
    }

    @Test
    fun `stale revision rejects the edit instead of overwriting`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val created = repo.addMemory(MemoryScope.CORE, MemoryKind.NOTE, "original")

        // Another writer bumps the revision between approval and apply.
        repo.updateContentCas(created.id, "someone else's edit", expectedRevision = 1)

        try {
            repo.updateContentCas(created.id, "stale edit", expectedRevision = 1)
            fail("expected MemoryStaleException")
        } catch (e: MemoryStaleException) {
            assertEquals(created.id, e.memoryId)
            assertEquals(1, e.expectedRevision)
            assertEquals(2, e.actualRevision)
        }
        // The newer content was NOT overwritten.
        assertEquals("someone else's edit", repo.getGlobalMemories().single().content)
    }

    @Test
    fun `delete is compare-and-set too`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val created = repo.addMemory(MemoryScope.CORE, MemoryKind.NOTE, "to delete")
        repo.updateContentCas(created.id, "changed", expectedRevision = 1)

        try {
            repo.deleteMemoryCas(created.id, expectedRevision = 1)
            fail("expected MemoryStaleException")
        } catch (e: MemoryStaleException) {
            assertEquals(2, e.actualRevision)
        }
        assertEquals(1, repo.getGlobalMemories().size)

        val deleted = repo.deleteMemoryCas(created.id, expectedRevision = 2)
        assertEquals(created.id, deleted.memoryId)
        assertEquals(ContentDigest.sha256("changed"), deleted.oldDigest)
        assertNull(dao.rows[created.id])
    }

    @Test
    fun `auto extraction writes are marked with the extraction trigger`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val created = repo.addMemory(
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.NOTE,
            content = "extracted fact",
            sourceTrigger = MemoryRepository.TRIGGER_AUTO_EXTRACTION,
        )
        assertEquals(MemoryRepository.TRIGGER_AUTO_EXTRACTION, created.sourceTrigger)
        assertTrue(created.sourceRunId == null)
    }

    @Test
    fun `manual and full-record writes bump revision before an old CAS can apply`() = runBlocking {
        val dao = FakeMemoryDAO()
        val repo = repository(dao)
        val created = repo.addMemory(MemoryScope.LONG_TERM, MemoryKind.NOTE, "v1")

        val manual = repo.updateContent(created.id, "manual v2")
        assertEquals(2, manual.revision)

        val dreamSnapshot = repo.getAllRecords().single()
        val dream = repo.upsertRecord(dreamSnapshot.copy(content = "dream v3"))
        assertEquals(3, dream.revision)

        try {
            repo.updateContentCas(created.id, "stale approval", expectedRevision = 1)
            fail("expected MemoryStaleException")
        } catch (error: MemoryStaleException) {
            assertEquals(3, error.actualRevision)
        }
        assertEquals("dream v3", repo.getLongTermMemories().single().content)
    }
}
