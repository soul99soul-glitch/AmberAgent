package app.amber.core.memory.model

import app.amber.core.model.AssistantMemory
import app.amber.core.settings.IosSettingsDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/**
 * iOS memory store — provides read/write access to memory records.
 *
 * [Slice 6] Persistence: a Swift wrapper (IOSMemoryPersistence) observes
 * recordsFlow and writes the records to Documents/memories/memories.json on
 * every change; on app startup it loads that file and calls [replaceAll] to
 * seed the store. The KMP side stays platform-agnostic (pure in-memory
 * StateFlow); file IO lives in Swift where Foundation interop is native.
 *
 * HONESTY: Persistence is real (JSON file via Swift NSFileManager, atomic
 * write). The seed data comes from IosSettingsDefaults when no persisted file
 * exists. This is the iOS-local persistence layer for memories — same role as
 * the Android Room MemoryRepository, but file-based.
 */
object IosMemoryFactory {

    private val _records = MutableStateFlow<List<MemoryRecord>>(seedRecords())
    val recordsFlow: StateFlow<List<MemoryRecord>> = _records

    private var nextId = (_records.value.maxOfOrNull { it.id } ?: 999) + 1

    fun getAllRecords(): List<MemoryRecord> = _records.value

    fun addMemory(
        scope: MemoryScope,
        kind: MemoryKind,
        content: String,
        assistantId: String = bucketForScope(scope),
    ): MemoryRecord = addDetailedMemory(
        scope = scope,
        kind = kind,
        content = content,
        assistantId = assistantId,
        sourceConversationId = null,
        sourceMessageIds = emptyList(),
        supersedesIds = emptyList(),
        expiresAt = null,
        confidence = 1f,
        pinned = false,
        archived = false,
    )

    fun addDetailedMemory(
        scope: MemoryScope,
        kind: MemoryKind,
        content: String,
        assistantId: String = bucketForScope(scope),
        sourceConversationId: String? = null,
        sourceMessageIds: List<String> = emptyList(),
        supersedesIds: List<Int> = emptyList(),
        expiresAt: Long? = null,
        confidence: Float = 1f,
        pinned: Boolean = false,
        archived: Boolean = false,
    ): MemoryRecord {
        val now = Clock.System.now().toEpochMilliseconds()
        val record = MemoryRecord(
            id = nextId++,
            content = content,
            scope = scope,
            kind = kind,
            assistantId = assistantId,
            sourceConversationId = sourceConversationId,
            sourceMessageIds = sourceMessageIds,
            supersedesIds = supersedesIds,
            expiresAt = expiresAt,
            confidence = confidence,
            pinned = pinned,
            archived = archived,
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

    fun updateRecord(record: MemoryRecord): MemoryRecord? {
        val records = _records.value.toMutableList()
        val index = records.indexOfFirst { it.id == record.id }
        if (index < 0) return null
        records[index] = record
        _records.value = records
        nextId = (records.maxOfOrNull { it.id } ?: 999) + 1
        return record
    }

    fun deleteMemory(id: Int) {
        _records.value = _records.value.filterNot { it.id == id }
    }

    fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        _records.value.filter { it.assistantId == assistantId }.map { it.toAssistantMemory() }

    fun getGlobalMemories(): List<AssistantMemory> = getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
    fun getShortTermMemories(): List<AssistantMemory> = getMemoriesOfAssistant(SHORT_TERM_MEMORY_ID)
    fun getLongTermMemories(): List<AssistantMemory> = getMemoriesOfAssistant(LONG_TERM_MEMORY_ID)

    // ---------- Persistence hooks (Slice 6, driven by Swift wrapper) ----------

    /**
     * Replace all records with [records]. Called once at app startup by the
     * Swift IOSMemoryPersistence wrapper after it loads
     * Documents/memories/memories.json. Resets nextId above the highest id.
     */
    fun replaceAll(records: List<MemoryRecord>) {
        _records.value = records
        nextId = (records.maxOfOrNull { it.id } ?: 999) + 1
    }

    /**
     * Snapshot for the Swift wrapper to serialize. Returns the current records
     * so Swift can JSON-encode + write the file (Swift's Codable/JSONEncoder is
     * cleaner than K/N Foundation interop for this).
     */
    fun snapshotRecords(): List<MemoryRecord> = _records.value

    // ---------- Constants (mirror Android MemoryRepository) ----------

    const val GLOBAL_MEMORY_ID = "__global__"
    const val SHORT_TERM_MEMORY_ID = "__short_term__"
    const val LONG_TERM_MEMORY_ID = "__long_term__"

    // ---------- Seed ----------

    private fun seedRecords(): List<MemoryRecord> {
        // No memory records in the seed Settings snapshot (memories live in Room DB,
        // not in Settings). iOS starts with an empty store; addMemory populates it
        // and the Swift wrapper persists it to disk for next launch.
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
