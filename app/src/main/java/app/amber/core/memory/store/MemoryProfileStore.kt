package app.amber.core.memory.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The dream-synthesized user profile. It is a derived view, not a memory
 * record: produced by dream review (reviewable like every other model op),
 * injected into recall while fresh, and rebuilt by the next dream when the
 * underlying records drift.
 */
@Serializable
data class MemoryProfile(
    val content: String,
    val generatedAt: Long,
    /** Managed records the synthesis drew on; used for staleness checks. */
    val sourceMemoryIds: List<Int> = emptyList(),
) {
    /**
     * Fresh when generated recently and at least half of its source records
     * are still active — a heavily churned library invalidates it.
     */
    fun isFresh(now: Long, activeMemoryIds: Set<Int>): Boolean {
        if (content.isBlank()) return false
        if (now - generatedAt > PROFILE_TTL_MS) return false
        if (sourceMemoryIds.isEmpty()) return true
        val retained = sourceMemoryIds.count { it in activeMemoryIds }
        return retained * 2 >= sourceMemoryIds.size
    }

    companion object {
        const val PROFILE_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

/** JSON file store; a single blob needs no Room table. */
class MemoryProfileStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val mutex = Mutex()

    suspend fun get(): MemoryProfile? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            runCatching {
                json.decodeFromString(MemoryProfile.serializer(), file.readText())
            }.getOrNull()
        }
    }

    suspend fun put(profile: MemoryProfile) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(MemoryProfile.serializer(), profile))
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (file.exists()) file.delete()
        }
    }
}
