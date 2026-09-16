package app.amber.core.memory.export

import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.store.MemoryStaleException
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
        // Derived reading surface: index.md + topics/<slug>.md rendered from
        // TOPIC records. The per-record .mem.md files stay canonical — these
        // are regenerated on every export and never imported.
        val recordsById = records.associateBy { it.id }
        val topics = records
            .filter { it.kind == MemoryKind.TOPIC && !it.archived }
            .sortedBy { it.id }
        File(root, "topics").mkdirs()
        val exportedTopicPaths = topics.map { topic ->
            val file = File(root, "topics/${topicFileName(topic)}")
            file.writeText(buildTopicMarkdown(topic, recordsById))
            file.relativeTo(root).invariantSeparatorsPath
        }
        File(root, "index.md").writeText(buildIndexMarkdown(records, topics, recordsById))
        File(root, "topics").listFiles().orEmpty()
            .filter {
                it.isFile && it.name.endsWith(".md") &&
                    it.relativeTo(root).invariantSeparatorsPath !in exportedTopicPaths
            }
            .forEach { file -> check(file.delete()) { "Failed to remove stale topic export: ${file.name}" } }
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
        // Live view of the table: every successful upsert replaces its entry so
        // a second file matching the same row CAS-succeeds instead of crashing
        // the import on the pre-import snapshot revision.
        val currentRecords = memoryRepository.getAllRecords().toMutableList()
        var imported = 0
        var skipped = 0
        val decoded = sequenceOf(File(resolvedRoot, "memories"), File(resolvedRoot, "archive"))
            .filter { it.exists() }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.name.endsWith(".mem.md") }
            .mapNotNull { file ->
                runCatching { codec.decode(file.readText()) }
                    .getOrNull()
                    ?: run { skipped++; null }
            }
            .toList()
        // Non-topic records first so a file-id -> new-id map exists when
        // topics import; member ids from another database would dangle.
        val remappedIds = mutableMapOf<Int, Int>()
        decoded.filter { it.kind != MemoryKind.TOPIC }.forEach { record ->
            val existing = currentRecords
                .filter { candidate ->
                    candidate.scope == record.scope &&
                        candidate.kind == record.kind &&
                        normalize(candidate.content) == normalize(record.content)
                }
                .minByOrNull { it.id }
            val written = try {
                memoryRepository.upsertRecord(
                    record.copy(
                        id = existing?.id ?: 0,
                        revision = existing?.revision ?: record.revision,
                    )
                )
            } catch (stale: MemoryStaleException) {
                // A concurrent writer owns the row now — skip, don't abort.
                skipped++
                return@forEach
            }
            currentRecords.replaceAll { if (it.id == written.id) written else it }
            if (existing == null) currentRecords += written
            remappedIds[record.id] = written.id
            imported++
        }
        decoded.filter { it.kind == MemoryKind.TOPIC }.forEach { record ->
            val titleKey = record.topicTitle?.lowercase()
            val existing = currentRecords
                .filter { candidate ->
                    candidate.kind == MemoryKind.TOPIC &&
                        candidate.archived == record.archived &&
                        titleKey != null &&
                        candidate.topicTitle?.lowercase() == titleKey
                }
                .minByOrNull { it.id }
            val written = try {
                memoryRepository.upsertRecord(
                    record.copy(
                        id = existing?.id ?: 0,
                        revision = existing?.revision ?: record.revision,
                        memberIds = record.memberIds.mapNotNull(remappedIds::get),
                    )
                )
            } catch (stale: MemoryStaleException) {
                skipped++
                return@forEach
            }
            currentRecords.replaceAll { if (it.id == written.id) written else it }
            if (existing == null) currentRecords += written
            imported++
        }
        return MemoryImportResult(
            root = resolvedRoot,
            importedCount = imported,
            skippedCount = skipped,
        )
    }

    private suspend fun captureWriteContext(): CoroutineContext {
        coroutineContext[SyncRestoreWriteEpoch]?.let { return it }
        val gate = restoreWriteGate ?: return EmptyCoroutineContext
        gate.withWriter { Unit }
        return SyncRestoreWriteEpoch(gate.currentEpoch())
    }

    private fun topicFileName(topic: MemoryRecord): String {
        val slug = (topic.topicTitle ?: topic.content).lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(48)
            .ifBlank { "topic" }
        // The id prefix guarantees uniqueness — the slug follows title edits
        // and the old file is removed by the stale-export cleanup.
        return "${topic.id}-$slug.md"
    }

    private fun markdownSafe(text: String): String =
        text.replace(Regex("[\\[\\]()]"), " ").replace("\n", " ").trim()

    private fun buildTopicMarkdown(
        topic: MemoryRecord,
        recordsById: Map<Int, MemoryRecord>,
    ): String = buildString {
        appendLine("---")
        appendLine("id: ${topic.id}")
        appendLine("topic_title: \"${topic.topicTitle.orEmpty().replace("\"", "\\\"")}\"")
        appendLine("member_ids: [${topic.memberIds.joinToString(", ")}]")
        appendLine("updated_at: \"${Instant.ofEpochMilli(topic.updatedAt)}\"")
        appendLine("---")
        appendLine()
        appendLine("# ${markdownSafe(topic.topicTitle ?: "Topic ${topic.id}")}")
        appendLine()
        appendLine(topic.content.trim())
        appendLine()
        appendLine("## Members")
        topic.memberIds
            .mapNotNull { recordsById[it] }
            .filter { !it.archived }
            .forEach { member ->
                appendLine(
                    "- #${member.id} [${member.scope.wireName}/${member.kind.wireName}] " +
                        member.content.trim().replace("\n", " ")
                )
            }
    }

    private fun buildIndexMarkdown(
        records: List<MemoryRecord>,
        topics: List<MemoryRecord>,
        recordsById: Map<Int, MemoryRecord>,
    ): String = buildString {
        appendLine("# Memory Index")
        appendLine()
        appendLine("generated_at: \"${Instant.now()}\"")
        appendLine(
            "active: ${records.count { !it.archived }} · " +
                "archived: ${records.count { it.archived }} · " +
                "topics: ${topics.size}"
        )
        appendLine()
        appendLine("## Topics")
        if (topics.isEmpty()) {
            appendLine("- none yet — topics are synthesized by Daydream review.")
        } else {
            topics.forEach { topic ->
                val liveMembers = topic.memberIds.count { recordsById[it]?.archived == false }
                appendLine(
                    "- [${markdownSafe(topic.topicTitle ?: "Topic ${topic.id}")}]" +
                        "(topics/${topicFileName(topic)}) — $liveMembers members"
                )
            }
        }
        appendLine()
        appendLine("## Records")
        MemoryKind.entries
            .filter { it != MemoryKind.TOPIC }
            .forEach { kind ->
                val active = records.count { !it.archived && it.kind == kind }
                val archived = records.count { it.archived && it.kind == kind }
                if (active + archived > 0) {
                    val dir = if (active > 0) "memories" else "archive"
                    appendLine("- ${kind.wireName}: $active active, $archived archived → $dir/${kind.wireName}/")
                }
            }
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
        text.lowercase().filter { it.isLetterOrDigit() }
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
    val skippedCount: Int = 0,
)
