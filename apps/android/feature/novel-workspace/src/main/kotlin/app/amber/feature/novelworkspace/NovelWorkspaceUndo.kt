package app.amber.feature.novelworkspace

import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Minimal one-level undo ("撤销最近一笔"). The ledger keeps only file hashes, so undo
 * stores the previous content of the files a commit changed. Host-local, single level —
 * this is the safety net, not a history browser.
 */
@Serializable
data class NovelWorkspaceUndoRecord(
    val commitId: String,
    val parentCommitId: String?,
    /** path → previous content (null = the file did not exist before that commit). */
    val files: Map<String, String?>,
)

object NovelWorkspaceUndo {
    private const val FILE_NAME = "undo.json"

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(projectDirectory: File): File =
        File(File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME), FILE_NAME)

    fun load(projectDirectory: File): NovelWorkspaceUndoRecord? {
        val f = file(projectDirectory)
        if (!f.exists()) return null
        return try {
            json.decodeFromString(NovelWorkspaceUndoRecord.serializer(), f.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            null
        }
    }

    fun save(record: NovelWorkspaceUndoRecord, projectDirectory: File) {
        val ledgerDir = File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME)
        if (!ledgerDir.exists() && !ledgerDir.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create ledger directory: $ledgerDir")
        }
        val destination = file(projectDirectory)
        val temp = File.createTempFile("novel-undo-", ".tmp", ledgerDir)
        try {
            temp.writeText(json.encodeToString(NovelWorkspaceUndoRecord.serializer(), record), Charsets.UTF_8)
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            NovelWorkspaceLedger.atomicMove(temp, destination)
        } finally {
            temp.delete()
        }
    }

    fun clear(projectDirectory: File) {
        file(projectDirectory).delete()
    }
}
