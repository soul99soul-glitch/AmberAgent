package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class DeepReadPlaybookRepository(
    private val context: Context,
) {
    private val playbookDir = File(context.filesDir, "deep_read_playbook")
    private val playbookFile = File(playbookDir, "playbook.md")
    private val snapshotsDir = File(playbookDir, "snapshots")
    private val mutex = Mutex()

    suspend fun read(): DeepReadPlaybookSnapshot = withContext(Dispatchers.IO) { mutex.withLock {
        ensureInitialized()
        snapshotOf(playbookFile.readText())
    } }

    suspend fun update(
        baseRevision: String,
        changeSummary: String,
        updatedMarkdown: String,
    ): Result<DeepReadPlaybookSnapshot> = withContext(Dispatchers.IO) { mutex.withLock {
        ensureInitialized()
        val current = snapshotOf(playbookFile.readText())
        if (baseRevision != current.revision) {
            return@withLock Result.failure(
                IllegalStateException("Playbook revision conflict: current=${current.revision}, base=$baseRevision")
            )
        }
        val normalized = updatedMarkdown.trim().takeIf { it.length >= MIN_MARKDOWN_CHARS }
            ?: return@withLock Result.failure(IllegalArgumentException("Playbook markdown is too short"))
        if (normalized.encodeToByteArray().size > MAX_MARKDOWN_BYTES) {
            return@withLock Result.failure(IllegalArgumentException("Playbook markdown is too large"))
        }
        saveSnapshot(current, changeSummary)
        playbookFile.writeText(normalized)
        Result.success(snapshotOf(normalized))
    } }

    suspend fun restoreDefault(): DeepReadPlaybookSnapshot = withContext(Dispatchers.IO) { mutex.withLock {
        ensureInitialized()
        saveSnapshot(snapshotOf(playbookFile.readText()), "restore_default")
        val default = defaultMarkdown()
        playbookFile.writeText(default)
        snapshotOf(default)
    } }

    suspend fun restorePrevious(): Result<DeepReadPlaybookSnapshot> = withContext(Dispatchers.IO) { mutex.withLock {
        ensureInitialized()
        val previous = snapshotsDir
            .listFiles { file -> file.extension == "md" }
            .orEmpty()
            .maxByOrNull { it.lastModified() }
            ?: return@withLock Result.failure(IllegalStateException("No previous playbook snapshot"))
        saveSnapshot(snapshotOf(playbookFile.readText()), "restore_previous")
        val previousText = previous.readText()
        val markdown = previousText.substringAfter("\n\n", previousText)
        playbookFile.writeText(markdown)
        Result.success(snapshotOf(markdown))
    } }

    private fun ensureInitialized() {
        playbookDir.mkdirs()
        snapshotsDir.mkdirs()
        if (!playbookFile.exists()) {
            playbookFile.writeText(defaultMarkdown())
        }
    }

    private fun defaultMarkdown(): String =
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }.trim()

    private fun saveSnapshot(snapshot: DeepReadPlaybookSnapshot, changeSummary: String) {
        snapshotsDir.mkdirs()
        val normalizedSummary = changeSummary
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(160)
        val safeSummary = normalizedSummary.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "snapshot" }
        val file = File(snapshotsDir, "${System.currentTimeMillis()}_${snapshot.revision}_$safeSummary.md")
        file.writeText(
            buildString {
                appendLine("revision: ${snapshot.revision}")
                appendLine("change_summary: $normalizedSummary")
                appendLine()
                append(snapshot.markdown)
            }
        )
    }

    private fun snapshotOf(markdown: String): DeepReadPlaybookSnapshot =
        DeepReadPlaybookSnapshot(
            revision = markdown.sha256().take(16),
            markdown = markdown,
            updatedAt = playbookFile.takeIf { it.exists() }?.lastModified() ?: System.currentTimeMillis(),
        )

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ASSET_PATH = "deepread/deep_read_playbook.md"
        private const val MAX_MARKDOWN_BYTES = 40_000
        private const val MIN_MARKDOWN_CHARS = 400
    }
}
