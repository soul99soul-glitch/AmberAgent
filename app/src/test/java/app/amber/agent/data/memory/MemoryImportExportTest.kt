package app.amber.core.memory

import java.io.File
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
import app.amber.core.memory.export.MemoryImportExportManager
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryImportExportTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun exportWritesIndexAndTopicDocsAndCleansStaleOnes() = runBlocking {
        val memoryDao = ExportFakeMemoryDao(
            listOf(
                exportEntity(1, "用户偏好中文回复。", MemoryKind.USER),
                exportEntity(2, "用户偏好分点列出。", MemoryKind.FEEDBACK),
                exportEntity(
                    9,
                    "用户偏好中文简洁分点回复。",
                    MemoryKind.TOPIC,
                    topicTitle = "回复风格",
                    memberIdsJson = "[1, 2]",
                ),
            )
        )
        val repository = MemoryRepository(memoryDao, ExportFakeCandidateDao(), ExportFakeEventDao())
        val manager = MemoryImportExportManager(repository)
        val out = temp.newFolder("export")

        manager.exportTo(out)
        val root = File(out, "AmberAgentMemory")

        val index = File(root, "index.md")
        assertTrue(index.exists())
        val indexText = index.readText()
        assertTrue(indexText.contains("topics: 1"))
        assertTrue(indexText.contains("[回复风格](topics/9-回复风格.md)"))

        val topicFiles = File(root, "topics").listFiles().orEmpty()
        assertEquals(1, topicFiles.size)
        val topicText = topicFiles.single().readText()
        assertTrue(topicText.contains("member_ids: [1, 2]"))
        assertTrue(topicText.contains("# 回复风格"))
        assertTrue(topicText.contains("- #1 [long_term/user] 用户偏好中文回复。"))

        // Topic record itself still exports as a canonical .mem.md record.
        assertTrue(File(root, "memories/topic").listFiles().orEmpty().any { it.name.endsWith(".mem.md") })

        // A stale topic file from an older export is removed.
        val stale = File(root, "topics/99-stale.md").apply { writeText("stale") }
        manager.exportTo(out)
        assertFalse(stale.exists())
        assertEquals(1, File(root, "topics").listFiles().orEmpty().size)
    }

    @Test
    fun exportWithoutTopicsWritesIndexWithEmptySection() = runBlocking {
        val repository = MemoryRepository(
            ExportFakeMemoryDao(listOf(exportEntity(1, "单条记忆。", MemoryKind.NOTE))),
            ExportFakeCandidateDao(),
            ExportFakeEventDao(),
        )
        val manager = MemoryImportExportManager(repository)
        val out = temp.newFolder("export-empty")

        manager.exportTo(out)
        val root = File(out, "AmberAgentMemory")

        val indexText = File(root, "index.md").readText()
        assertTrue(indexText.contains("topics: 0"))
        assertTrue(indexText.contains("none yet"))
    }

    @Test
    fun importRemapsTopicMembersToFreshIds() = runBlocking {
        val repository = MemoryRepository(
            ExportFakeMemoryDao(emptyList()),
            ExportFakeCandidateDao(),
            ExportFakeEventDao(),
        )
        val manager = MemoryImportExportManager(repository)
        val dir = temp.newFolder("import-src")
        val root = File(dir, "AmberAgentMemory")
        File(root, "memories/user").mkdirs()
        File(root, "memories/topic").mkdirs()
        File(root, "manifest.json").writeText("{}")
        File(root, "memories/user/m1.mem.md").writeText(
            """
            ---
            id: "41"
            kind: "user"
            scope: "long_term"
            confidence: 0.9
            created_at: "2024-01-01T00:00:00Z"
            updated_at: "2024-01-01T00:00:00Z"
            source_message_ids: []
            supersedes_ids: []
            pinned: false
            archived: false
            ---
            用户偏好中文回复。
            """.trimIndent()
        )
        File(root, "memories/topic/t1.mem.md").writeText(
            """
            ---
            id: "90"
            kind: "topic"
            scope: "long_term"
            topic_title: "回复风格"
            member_ids: [41]
            confidence: 0.8
            created_at: "2024-01-01T00:00:00Z"
            updated_at: "2024-01-01T00:00:00Z"
            source_message_ids: []
            supersedes_ids: []
            pinned: false
            archived: false
            ---
            主题摘要。
            """.trimIndent()
        )

        manager.importFrom(dir)

        val records = repository.getAllRecords()
        val member = records.single { it.kind == MemoryKind.USER }
        val topic = records.single { it.kind == MemoryKind.TOPIC }
        // Fresh ids (1, 2) — the topic must reference the remapped member id.
        assertEquals(listOf(member.id), topic.memberIds)
        assertEquals("回复风格", topic.topicTitle)
    }
}

private fun exportEntity(
    id: Int,
    content: String,
    kind: MemoryKind,
    topicTitle: String? = null,
    memberIdsJson: String = "[]",
) = MemoryEntity(
    id = id,
    assistantId = MemoryRepository.LONG_TERM_MEMORY_ID,
    content = content,
    scope = MemoryScope.LONG_TERM.wireName,
    kind = kind.wireName,
    confidence = 0.9f,
    createdAt = 1_000L + id,
    updatedAt = 1_000L + id,
    topicTitle = topicTitle,
    memberIdsJson = memberIdsJson,
)

private class ExportFakeMemoryDao(
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
        flowOf(memories.groupBy { it.assistantId }.map { (a, rows) -> MemoryCount(a, rows.size) })

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

    override suspend fun updateContentBlind(id: Int, content: String, updatedAt: Long): Int {
        val index = memories.indexOfFirst { it.id == id }
        if (index < 0) return 0
        memories[index] = memories[index].copy(
            content = content,
            revision = memories[index].revision + 1,
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
        topicTitle: String?,
        memberIdsJson: String,
        expectedRevision: Long,
    ): Int {
        val index = memories.indexOfFirst { it.id == id }
        if (index < 0) return 0
        val current = memories[index]
        if (current.revision != expectedRevision) return 0
        memories[index] = current.copy(
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
            topicTitle = topicTitle,
            memberIdsJson = memberIdsJson,
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
        val index = memories.indexOfFirst { it.id == id }
        if (index < 0) return 0
        val current = memories[index]
        if (current.revision != expectedRevision) return 0
        memories[index] = current.copy(content = content, revision = expectedRevision + 1, updatedAt = updatedAt)
        return 1
    }

    override suspend fun deleteCas(id: Int, expectedRevision: Long): Int {
        val current = memories.firstOrNull { it.id == id } ?: return 0
        if (current.revision != expectedRevision) return 0
        memories.removeAll { it.id == id }
        return 1
    }

    override suspend fun revisionOf(id: Int): Long? = memories.firstOrNull { it.id == id }?.revision

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

private class ExportFakeCandidateDao : MemoryCandidateDAO {
    private val candidates = mutableListOf<MemoryCandidateEntity>()

    override fun getCandidatesFlow(): Flow<List<MemoryCandidateEntity>> = flowOf(candidates.toList())

    override fun getCandidatesByStatusFlow(status: String): Flow<List<MemoryCandidateEntity>> =
        flowOf(candidates.filter { it.status == status })

    override fun countCandidatesByStatusFlow(status: String): Flow<Int> =
        flowOf(candidates.count { it.status == status })

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

private class ExportFakeEventDao : MemoryEventDAO {
    private val events = mutableListOf<MemoryEventEntity>()

    override fun getRecentEventsFlow(limit: Int): Flow<List<MemoryEventEntity>> =
        flowOf(events.take(limit))

    override suspend fun getRecentEvents(limit: Int): List<MemoryEventEntity> = events.take(limit)

    override suspend fun getEventsOfConversation(conversationId: String, limit: Int): List<MemoryEventEntity> =
        events.filter { it.conversationId == conversationId }.take(limit)

    override suspend fun countEventsSince(eventType: String, createdAfter: Long): Int =
        events.count { it.eventType == eventType && it.createdAt >= createdAfter }

    override suspend fun insert(event: MemoryEventEntity) {
        events += event
    }
}
