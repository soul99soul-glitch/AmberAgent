package app.amber.feature.reminder

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Reminder recurrence uses elapsed epoch arithmetic, not local-time arithmetic. */
@Serializable
enum class ReminderRecurrence {
    NONE,
    DAILY,
    WEEKLY,
}

/** Durable, app-owned reminder record. A non-null [corruptReason] is visible recovery state. */
@Serializable
data class ReminderSnapshot(
    val id: String,
    val title: String,
    val message: String,
    val triggerAtEpochMs: Long,
    val recurrence: ReminderRecurrence = ReminderRecurrence.NONE,
    val nextFireAtEpochMs: Long? = triggerAtEpochMs,
    val enabled: Boolean = true,
    val firedCount: Long = 0L,
    /** Stable fire key already committed, used to make receiver delivery idempotent. */
    val lastFireKey: String? = null,
    /** Corrupt files stay listed and inspectable instead of being silently discarded. */
    val corruptReason: String? = null,
) {
    val isCorrupt: Boolean get() = !corruptReason.isNullOrBlank()

    fun fireKey(): String? = nextFireAtEpochMs?.let { "$id:$it" }
}

/**
 * The smallest public API needed by reminder-management tools and future UI owners.
 * Each record is a separate file so one damaged record cannot hide healthy records.
 */
class ReminderStore(
    filesDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    constructor(context: Context, json: Json = Json { ignoreUnknownKeys = true }) : this(context.filesDir, json)

    private val reminderDir = File(filesDir, "amberagent/reminders").also { it.mkdirs() }
    private val mutex = Mutex()
    private val reminders = ConcurrentHashMap<String, ReminderSnapshot>()

    init {
        loadSnapshots()
    }

    fun list(): List<ReminderSnapshot> = reminders.values.sortedWith(
        compareByDescending<ReminderSnapshot> { it.enabled && !it.isCorrupt }
            .thenBy { it.nextFireAtEpochMs ?: Long.MAX_VALUE }
            .thenBy { it.id },
    )

    fun read(id: String): ReminderSnapshot? = reminders[id]

    suspend fun upsert(snapshot: ReminderSnapshot): ReminderSnapshot = mutex.withLock {
        persistValidated(snapshot)
        snapshot
    }

    /** Atomic read-transform-write API for reminder management and future UI owners. */
    suspend fun update(id: String, transform: (ReminderSnapshot) -> ReminderSnapshot): ReminderSnapshot? = mutex.withLock {
        val current = reminders[id] ?: return@withLock null
        val next = transform(current)
        require(next.id == id) { "Reminder update cannot change id" }
        persistValidated(next)
        next
    }

    suspend fun remove(id: String): Boolean = mutex.withLock {
        val file = snapshotFile(id)
        if (file.exists() && !file.delete() && file.exists()) {
            throw IOException("Failed to delete reminder snapshot: ${file.absolutePath}")
        }
        reminders.remove(id) != null
    }

    /** Atomic compare-and-set for the receiver. Returns null for missing/stale/duplicate fires. */
    suspend fun commitFire(id: String, fireKey: String): ReminderFireCommit? = mutex.withLock {
        val current = reminders[id] ?: return@withLock null
        if (current.isCorrupt || !current.enabled || current.fireKey() != fireKey || current.lastFireKey == fireKey) {
            return@withLock null
        }
        val now = current.nextFireAtEpochMs ?: return@withLock null
        val next = nextFire(now, current.recurrence)
        val updated = current.copy(
            nextFireAtEpochMs = next,
            enabled = next != null,
            firedCount = current.firedCount + 1,
            lastFireKey = fireKey,
        )
        persist(updated)
        reminders[id] = updated
        ReminderFireCommit(current, updated)
    }

    /** Epoch semantics are deliberately fixed-duration: daily = 24h, weekly = 7*24h. */
    fun nextFire(firedAtEpochMs: Long, recurrence: ReminderRecurrence): Long? =
        nextFireAt(firedAtEpochMs, recurrence)

    private fun validate(snapshot: ReminderSnapshot) {
        require(snapshot.id.isNotBlank() && snapshot.id.none { it == '/' || it == '\\' }) {
            "Reminder id must be a non-blank path-safe value"
        }
        require(!snapshot.isCorrupt) { "Corrupt reminder records are read-only until replaced" }
        require(snapshot.title.isNotBlank()) { "Reminder title cannot be blank" }
        require(snapshot.message.isNotBlank()) { "Reminder message cannot be blank" }
        require(snapshot.triggerAtEpochMs >= 0L) { "Reminder trigger time cannot be negative" }
    }

    private fun persistValidated(snapshot: ReminderSnapshot) {
        validate(snapshot)
        persist(snapshot)
        reminders[snapshot.id] = snapshot
    }

    private fun loadSnapshots() {
        reminderDir.listFiles { file -> file.extension == "json" }.orEmpty().forEach { file ->
            val snapshot = runCatching {
                json.decodeFromString(ReminderSnapshot.serializer(), file.readText())
            }.getOrElse { error ->
                // Keep the bytes on disk and expose a visible disabled recovery record.
                ReminderSnapshot(
                    id = file.nameWithoutExtension,
                    title = "损坏的提醒",
                    message = "记录无法解析，原文件已保留：${error.message.orEmpty().take(240)}",
                    triggerAtEpochMs = 0L,
                    nextFireAtEpochMs = null,
                    enabled = false,
                    corruptReason = error.message ?: "JSON decode failed",
                )
            }
            reminders[snapshot.id] = snapshot
        }
    }

    private fun snapshotFile(id: String): File {
        require(id.isNotBlank() && id.none { it == '/' || it == '\\' }) {
            "Reminder id must be a non-blank path-safe value"
        }
        return File(reminderDir, "$id.json")
    }

    private fun persist(snapshot: ReminderSnapshot) {
        val destination = snapshotFile(snapshot.id)
        val parent = destination.parentFile ?: throw IOException("Cannot resolve reminder parent")
        if (!parent.isDirectory) throw IOException("Reminder parent is not a directory: ${parent.path}")
        val encoded = json.encodeToString(ReminderSnapshot.serializer(), snapshot)
        val temporary = File.createTempFile("reminder-snapshot-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encoded.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_FIRE_KEY = "reminder_fire_key"
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val WEEK_MS = 7L * DAY_MS

        fun nextFireAt(firedAtEpochMs: Long, recurrence: ReminderRecurrence): Long? = when (recurrence) {
            ReminderRecurrence.NONE -> null
            ReminderRecurrence.DAILY -> firedAtEpochMs + DAY_MS
            ReminderRecurrence.WEEKLY -> firedAtEpochMs + WEEK_MS
        }
    }
}

data class ReminderFireCommit(
    val fired: ReminderSnapshot,
    val updated: ReminderSnapshot,
)
