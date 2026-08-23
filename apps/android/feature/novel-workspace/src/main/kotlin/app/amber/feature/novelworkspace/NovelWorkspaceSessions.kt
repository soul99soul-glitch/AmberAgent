package app.amber.feature.novelworkspace

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Locked decision A: chat bubbles live in the ledger, never in the book.
 * This file is Android-host-local; it is not part of the cross-platform wire contract.
 */
@Serializable
data class NovelWorkspaceSessionMessage(
    val id: String,
    val role: String,
    val kind: String,
    val content: String,
    @Serializable(with = NovelWorkspaceInstantSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class NovelWorkspaceSessionsFile(
    val version: Int = 1,
    /** branchID → append-only message list. */
    val sessions: Map<String, List<NovelWorkspaceSessionMessage>> = emptyMap(),
)

object NovelWorkspaceSessions {
    const val FILE_NAME = "sessions.json"

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    fun load(projectDirectory: File): NovelWorkspaceSessionsFile {
        val file = File(File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME), FILE_NAME)
        if (!file.exists()) return NovelWorkspaceSessionsFile()
        return try {
            json.decodeFromString(NovelWorkspaceSessionsFile.serializer(), file.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            // Same quarantine rule as the ledger: never let a later save silently
            // erase an unreadable-but-present history file.
            runCatching {
                file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt-${System.currentTimeMillis()}"))
            }
            NovelWorkspaceSessionsFile()
        }
    }

    fun save(sessions: NovelWorkspaceSessionsFile, projectDirectory: File) {
        val ledgerDir = File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME)
        if (!ledgerDir.exists() && !ledgerDir.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create ledger directory: $ledgerDir")
        }
        val destination = File(ledgerDir, FILE_NAME)
        val temp = File.createTempFile("novel-sessions-", ".tmp", ledgerDir)
        try {
            temp.writeText(json.encodeToString(NovelWorkspaceSessionsFile.serializer(), sessions), Charsets.UTF_8)
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            NovelWorkspaceLedger.atomicMove(temp, destination)
        } finally {
            temp.delete()
        }
    }
}
