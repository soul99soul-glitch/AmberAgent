package me.rerere.rikkahub.data.memory.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryCandidateDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryEventDAO
import me.rerere.rikkahub.data.db.entity.MemoryCandidateEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryEventEntity
import me.rerere.rikkahub.data.memory.model.MemoryCandidate
import me.rerere.rikkahub.data.memory.model.MemoryCandidateStatus
import me.rerere.rikkahub.data.memory.model.MemoryEvent
import me.rerere.rikkahub.data.memory.model.MemoryEventType
import me.rerere.rikkahub.data.memory.model.MemoryKind
import me.rerere.rikkahub.data.memory.model.MemoryRecord
import me.rerere.rikkahub.data.memory.model.MemoryScope
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstant

open class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val candidateDAO: MemoryCandidateDAO,
    private val eventDAO: MemoryEventDAO,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val SHORT_TERM_MEMORY_ID = "__short_term__"
        const val LONG_TERM_MEMORY_ID = "__long_term__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId).map { entities ->
            entities.map { it.toAssistantMemory() }
        }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toAssistantMemory() }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID).map { entities ->
            entities.map { it.toAssistantMemory() }
        }

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID).map { it.toAssistantMemory() }

    fun getShortTermMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(SHORT_TERM_MEMORY_ID).map { entities ->
            entities.map { it.toAssistantMemory() }
        }

    suspend fun getShortTermMemories(): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(SHORT_TERM_MEMORY_ID).map { it.toAssistantMemory() }

    fun getLongTermMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(LONG_TERM_MEMORY_ID).map { entities ->
            entities.map { it.toAssistantMemory() }
        }

    suspend fun getLongTermMemories(): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(LONG_TERM_MEMORY_ID).map { it.toAssistantMemory() }

    suspend fun getActiveRecords(scopes: Set<MemoryScope>, now: Long = System.currentTimeMillis()): List<MemoryRecord> {
        if (scopes.isEmpty()) return emptyList()
        return memoryDAO.getActiveMemoriesByScopes(scopes.map { it.wireName }, now).map { it.toRecord() }
    }

    suspend fun getAllActiveRecords(now: Long = System.currentTimeMillis()): List<MemoryRecord> =
        memoryDAO.getActiveMemories(now).map { it.toRecord() }

    suspend fun getAllRecords(): List<MemoryRecord> =
        memoryDAO.getAllMemories().map { it.toRecord() }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val updated = old.copy(content = content, updatedAt = System.currentTimeMillis())
        memoryDAO.updateMemory(updated)
        return updated.toAssistantMemory()
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val scope = scopeForBucket(assistantId)
        val kind = if (scope == MemoryScope.SHORT_TERM) MemoryKind.PROJECT else MemoryKind.NOTE
        return addMemory(
            scope = scope,
            kind = kind,
            content = content,
            assistantId = assistantId,
        ).toAssistantMemory()
    }

    suspend fun addMemory(
        scope: MemoryScope,
        kind: MemoryKind,
        content: String,
        assistantId: String = bucketForScope(scope),
        sourceConversationId: String? = null,
        sourceMessageIds: List<String> = emptyList(),
        expiresAt: Long? = null,
        confidence: Float = 1f,
        pinned: Boolean = false,
    ): MemoryRecord {
        val now = System.currentTimeMillis()
        val id = memoryDAO.insertMemory(
            MemoryEntity(
                assistantId = assistantId,
                content = content,
                scope = scope.wireName,
                kind = kind.wireName,
                sourceConversationId = sourceConversationId,
                sourceMessageIdsJson = JsonInstant.encodeToString(sourceMessageIds),
                expiresAt = expiresAt,
                confidence = confidence.coerceIn(0f, 1f),
                pinned = pinned,
                archived = false,
                createdAt = now,
                updatedAt = now,
            )
        ).toInt()
        return memoryDAO.getMemoryById(id)?.toRecord() ?: error("Created memory #$id not found")
    }

    suspend fun upsertRecord(record: MemoryRecord): MemoryRecord {
        val entity = record.toEntity()
        if (record.id == 0) {
            val id = memoryDAO.insertMemory(entity).toInt()
            return memoryDAO.getMemoryById(id)?.toRecord() ?: record.copy(id = id)
        }
        memoryDAO.updateMemory(entity.copy(updatedAt = System.currentTimeMillis()))
        return memoryDAO.getMemoryById(record.id)?.toRecord() ?: record
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }

    suspend fun touchMemories(ids: List<Int>, usedAt: Long = System.currentTimeMillis()) {
        if (ids.isNotEmpty()) {
            memoryDAO.touchMemories(ids, usedAt)
        }
    }

    fun getPendingCandidatesFlow(): Flow<List<MemoryCandidate>> =
        candidateDAO.getCandidatesByStatusFlow(MemoryCandidateStatus.PENDING.wireName).map { list ->
            list.map { it.toCandidate() }
        }

    suspend fun getPendingCandidates(): List<MemoryCandidate> =
        candidateDAO.getCandidatesByStatus(MemoryCandidateStatus.PENDING.wireName).map { it.toCandidate() }

    suspend fun getAllCandidates(): List<MemoryCandidate> =
        candidateDAO.getAllCandidates().map { it.toCandidate() }

    suspend fun addCandidate(candidate: MemoryCandidate) {
        candidateDAO.insert(candidate.toEntity())
    }

    suspend fun addCandidates(candidates: List<MemoryCandidate>) {
        candidateDAO.insertAll(candidates.map { it.toEntity() })
    }

    suspend fun updateCandidate(candidate: MemoryCandidate) {
        candidateDAO.update(candidate.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    suspend fun acceptCandidate(id: String): MemoryRecord {
        val candidate = candidateDAO.getCandidateById(id)?.toCandidate()
            ?: error("Memory candidate #$id not found")
        val record = addMemory(
            scope = candidate.scope,
            kind = candidate.kind,
            content = candidate.content,
            sourceConversationId = candidate.sourceConversationId,
            sourceMessageIds = candidate.sourceMessageIds,
            expiresAt = candidate.expiresAt,
            confidence = candidate.confidence,
        )
        updateCandidate(candidate.copy(status = MemoryCandidateStatus.ACCEPTED))
        return record
    }

    fun getRecentEventsFlow(limit: Int = 100): Flow<List<MemoryEvent>> =
        eventDAO.getRecentEventsFlow(limit).map { list -> list.map { it.toEvent() } }

    suspend fun getRecentEvents(limit: Int = 100): List<MemoryEvent> =
        eventDAO.getRecentEvents(limit).map { it.toEvent() }

    suspend fun countEventsSince(type: MemoryEventType, createdAfter: Long): Int =
        eventDAO.countEventsSince(type.wireName, createdAfter)

    suspend fun addEvent(event: MemoryEvent) {
        eventDAO.insert(event.toEntity())
    }

    private fun MemoryEntity.toAssistantMemory() = AssistantMemory(
        id = id,
        content = content,
        scope = MemoryScope.fromWireName(scope),
        kind = MemoryKind.fromWireName(kind),
        expiresAt = expiresAt,
        confidence = confidence,
        pinned = pinned,
        archived = archived,
    )

    private fun MemoryRecord.toAssistantMemory() = AssistantMemory(
        id = id,
        content = content,
        scope = scope,
        kind = kind,
        expiresAt = expiresAt,
        confidence = confidence,
        pinned = pinned,
        archived = archived,
    )

    private fun MemoryEntity.toRecord() = MemoryRecord(
        id = id,
        content = content,
        scope = MemoryScope.fromWireName(scope),
        kind = MemoryKind.fromWireName(kind),
        assistantId = assistantId,
        sourceConversationId = sourceConversationId,
        sourceMessageIds = decodeStringList(sourceMessageIdsJson),
        expiresAt = expiresAt,
        confidence = confidence,
        pinned = pinned,
        archived = archived,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastUsedAt = lastUsedAt,
    )

    private fun MemoryRecord.toEntity() = MemoryEntity(
        id = id,
        assistantId = assistantId.ifBlank { bucketForScope(scope) },
        content = content,
        scope = scope.wireName,
        kind = kind.wireName,
        sourceConversationId = sourceConversationId,
        sourceMessageIdsJson = JsonInstant.encodeToString(sourceMessageIds),
        expiresAt = expiresAt,
        confidence = confidence.coerceIn(0f, 1f),
        pinned = pinned,
        archived = archived,
        createdAt = createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        lastUsedAt = lastUsedAt,
    )

    private fun MemoryCandidateEntity.toCandidate() = MemoryCandidate(
        id = id,
        content = content,
        scope = MemoryScope.fromWireName(scope),
        kind = MemoryKind.fromWireName(kind),
        sourceConversationId = sourceConversationId,
        sourceMessageIds = decodeStringList(sourceMessageIdsJson),
        expiresAt = expiresAt,
        confidence = confidence,
        reason = reason,
        sensitive = sensitive,
        status = MemoryCandidateStatus.fromWireName(status),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun MemoryCandidate.toEntity() = MemoryCandidateEntity(
        id = id,
        content = content,
        scope = scope.wireName,
        kind = kind.wireName,
        sourceConversationId = sourceConversationId,
        sourceMessageIdsJson = JsonInstant.encodeToString(sourceMessageIds),
        expiresAt = expiresAt,
        confidence = confidence.coerceIn(0f, 1f),
        reason = reason,
        sensitive = sensitive,
        status = status.wireName,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun MemoryEventEntity.toEvent() = MemoryEvent(
        id = id,
        type = MemoryEventType.entries.firstOrNull { it.wireName == eventType } ?: MemoryEventType.EXTRACTION_SKIPPED,
        conversationId = conversationId,
        memoryId = memoryId,
        candidateId = candidateId,
        modelId = modelId,
        message = message,
        durationMs = durationMs,
        messageCount = messageCount,
        createdAt = createdAt,
    )

    private fun MemoryEvent.toEntity() = MemoryEventEntity(
        id = id,
        eventType = type.wireName,
        conversationId = conversationId,
        memoryId = memoryId,
        candidateId = candidateId,
        modelId = modelId,
        message = message.take(2_000),
        durationMs = durationMs,
        messageCount = messageCount,
        createdAt = createdAt,
    )

    private fun decodeStringList(raw: String): List<String> =
        runCatching { JsonInstant.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    private fun scopeForBucket(assistantId: String): MemoryScope = when (assistantId) {
        GLOBAL_MEMORY_ID -> MemoryScope.CORE
        SHORT_TERM_MEMORY_ID -> MemoryScope.SHORT_TERM
        LONG_TERM_MEMORY_ID -> MemoryScope.LONG_TERM
        else -> MemoryScope.LONG_TERM
    }
}

fun bucketForScope(scope: MemoryScope): String = when (scope) {
    MemoryScope.CORE -> MemoryRepository.GLOBAL_MEMORY_ID
    MemoryScope.SHORT_TERM -> MemoryRepository.SHORT_TERM_MEMORY_ID
    MemoryScope.LONG_TERM -> MemoryRepository.LONG_TERM_MEMORY_ID
}
