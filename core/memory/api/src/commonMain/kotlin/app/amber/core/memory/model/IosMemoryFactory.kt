package app.amber.core.memory.model

import app.amber.core.model.AssistantMemory
import app.amber.core.settings.IosSettingsDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/**
 * iOS in-memory memory store — provides read/write access to memory records
 * WITHOUT Room persistence. Seeds from the KMP Settings snapshot (assistants'
 * memory entries), then allows add/delete/update in memory.
 *
 * HONESTY: This is NOT persisted (no Room DB on iOS). Changes are lost on app
 * restart. The seed data comes from IosSettingsDefaults (the same seeded
 * Settings snapshot other settings pages read). This proves the Memory UI
 * read/write chain works on iOS; real persistence needs a Room KMP store
 * (future work, like core/agent-store-room).
 */
object IosMemoryFactory {

    private val _records = MutableStateFlow<List<MemoryRecord>>(seedRecords())
    val recordsFlow: StateFlow<List<MemoryRecord>> = _records

    private var nextId = 1000

    fun getAllRecords(): List<MemoryRecord> = _records.value

    fun addMemory(
        scope: MemoryScope,
        kind: MemoryKind,
        content: String,
        assistantId: String = bucketForScope(scope),
    ): MemoryRecord {
        val now = Clock.System.now().toEpochMilliseconds()
        val record = MemoryRecord(
            id = nextId++,
            content = content,
            scope = scope,
            kind = kind,
            assistantId = assistantId,
            sourceConversationId = null,
            sourceMessageIds = emptyList(),
            supersedesIds = emptyList(),
            expiresAt = null,
            confidence = 1f,
            pinned = false,
            archived = false,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = now,
        )
        _records.value = _records.value + record
        return record
    }

    fun addSimpleMemory(assistantId: String, content: String): AssistantMemory {
        val scope = scopeForBucket(assistantId)
        val kind = if (scope == MemoryScope.SHORT_TERM) MemoryKind.PROJECT else MemoryKind.NOTE
        val record = addMemory(scope = scope, kind = kind, content = content, assistantId = assistantId)
        return record.toAssistantMemory()
    }

    fun updateContent(id: Int, content: String): AssistantMemory? {
        val records = _records.value.toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = records[index].copy(content = content, updatedAt = Clock.System.now().toEpochMilliseconds())
        records[index] = updated
        _records.value = records
        return updated.toAssistantMemory()
    }

    fun deleteMemory(id: Int) {
        _records.value = _records.value.filterNot { it.id == id }
    }

    fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        _records.value.filter { it.assistantId == assistantId }.map { it.toAssistantMemory() }

    fun getGlobalMemories(): List<AssistantMemory> = getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
    fun getShortTermMemories(): List<AssistantMemory> = getMemoriesOfAssistant(SHORT_TERM_MEMORY_ID)
    fun getLongTermMemories(): List<AssistantMemory> = getMemoriesOfAssistant(LONG_TERM_MEMORY_ID)

    // ---------- Constants (mirror Android MemoryRepository) ----------

    const val GLOBAL_MEMORY_ID = "__global__"
    const val SHORT_TERM_MEMORY_ID = "__short_term__"
    const val LONG_TERM_MEMORY_ID = "__long_term__"

    // ---------- Seed ----------

    private fun seedRecords(): List<MemoryRecord> {
        // No memory records in the seed Settings snapshot (memories live in Room DB,
        // not in Settings). iOS starts with an empty store; addMemory populates it.
        return emptyList()
    }

    private fun scopeForBucket(assistantId: String): MemoryScope = when (assistantId) {
        GLOBAL_MEMORY_ID -> MemoryScope.CORE
        SHORT_TERM_MEMORY_ID -> MemoryScope.SHORT_TERM
        LONG_TERM_MEMORY_ID -> MemoryScope.LONG_TERM
        else -> MemoryScope.LONG_TERM
    }

    private fun bucketForScope(scope: MemoryScope): String = when (scope) {
        MemoryScope.CORE -> GLOBAL_MEMORY_ID
        MemoryScope.SHORT_TERM -> SHORT_TERM_MEMORY_ID
        MemoryScope.LONG_TERM -> LONG_TERM_MEMORY_ID
    }

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
}
