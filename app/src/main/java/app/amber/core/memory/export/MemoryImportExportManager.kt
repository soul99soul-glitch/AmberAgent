package app.amber.core.memory.export

import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.utils.JsonInstant
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

class MemoryImportExportManager(
    private val memoryRepository: MemoryRepository,
    private val codec: MemoryFrontmatterCodec = MemoryFrontmatterCodec(),
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    suspend fun exportTo(directory: File): MemoryExportResult {
        val root = resolveRoot(directory)
        val records = memoryRepository.getAllRecords()
        val events = memoryRepository.getRecentEvents(limit = 500)
        val exportedMemoryPaths = mutableSetOf<String>()
        MemoryKind.entries.forEach { kind ->
            File(root, "memories/${kind.wireName}").mkdirs()
            File(root, "archive/${kind.wireName}").mkdirs()
        }
        records.forEach { record ->
            val baseDir = if (record.archived) {
                "archive/${record.kind.wireName}"
            } else {
                "memories/${record.kind.wireName}"
            }
            val file = File(root, "$baseDir/${fileName(record)}")
            file.parentFile?.mkdirs()
            file.writeText(codec.encode(record))
            exportedMemoryPaths += file.relativeTo(root).invariantSeparatorsPath
        }
        File(root, "manifest.json").writeText(
            JsonInstant.encodeToString(
                mapOf(
                    "version" to "1",
                    "exported_at" to Instant.now().toString(),
                    "count" to records.size.toString(),
                    "active_count" to records.count { !it.archived }.toString(),
                    "archived_count" to records.count { it.archived }.toString(),
                    "event_count" to events.size.toString(),
                )
            )
        )
        File(root, "events").mkdirs()
        File(root, "events/memory_events.ndjson").writeText(
            events.joinToString("\n") { JsonInstant.encodeToString(it) }
        )
        sequenceOf(File(root, "memories"), File(root, "archive"))
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.name.endsWith(".mem.md") }
            .filter { it.relativeTo(root).invariantSeparatorsPath !in exportedMemoryPaths }
            .forEach { file -> check(file.delete()) { "Failed to remove stale memory export: ${file.name}" } }
        return MemoryExportResult(
            root = root,
            memoryCount = records.size,
            archivedCount = records.count { it.archived },
            eventCount = events.size,
        )
    }

    suspend fun importFrom(root: File): MemoryImportResult {
        return withContext(captureWriteContext()) { importInternal(root) }
    }

    private suspend fun importInternal(root: File): MemoryImportResult {
        val resolvedRoot = resolveExistingRoot(root)
        val existingRecords = memoryRepository.getAllRecords()
        var imported = 0
        sequenceOf(File(resolvedRoot, "memories"), File(resolvedRoot, "archive"))
            .filter { it.exists() }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.name.endsWith(".mem.md") }
            .forEach { file ->
                val decoded = codec.decode(file.readText())
                val existing = existingRecords.firstOrNull { record ->
                    record.scope == decoded.scope &&
                        record.kind == decoded.kind &&
                        normalize(record.content) == normalize(decoded.content)
                }
                memoryRepository.upsertRecord(
                    decoded.copy(
                        id = existing?.id ?: 0,
                        revision = existing?.revision ?: decoded.revision,
                    )
                )
                imported++
            }
        return MemoryImportResult(root = resolvedRoot, importedCount = imported)
    }

    private suspend fun captureWriteContext(): CoroutineContext {
        coroutineContext[SyncRestoreWriteEpoch]?.let { return it }
        val gate = restoreWriteGate ?: return EmptyCoroutineContext
        gate.withWriter { Unit }
        return SyncRestoreWriteEpoch(gate.currentEpoch())
    }

    private fun fileName(record: MemoryRecord): String {
        val date = Instant.ofEpochMilli(record.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .takeIf { it != LocalDate.MIN }
            ?: LocalDate.now()
        val slug = record.content.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "memory" }
        return "${date}_${record.id}_$slug.mem.md"
    }

    private fun resolveRoot(directory: File): File =
        if (directory.name == "AmberAgentMemory") directory else File(directory, "AmberAgentMemory")

    private fun resolveExistingRoot(directory: File): File {
        if (File(directory, "manifest.json").exists()) return directory
        val nested = File(directory, "AmberAgentMemory")
        return if (nested.exists()) nested else directory
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }.take(200)
}

data class MemoryExportResult(
    val root: File,
    val memoryCount: Int,
    val archivedCount: Int,
    val eventCount: Int,
)

data class MemoryImportResult(
    val root: File,
    val importedCount: Int,
)
