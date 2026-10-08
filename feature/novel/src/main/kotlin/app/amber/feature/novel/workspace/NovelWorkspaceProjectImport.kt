package app.amber.feature.novel.workspace

import app.amber.feature.novel.serialization.NovelPackageCodec
import app.amber.feature.novelworkspace.NovelWorkspaceExchange
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

/** Accept workspace ZIPs and Swift-compatible .ambernovel packages as fresh projects. */
object NovelWorkspaceProjectImport {
    fun importProject(
        bytes: ByteArray,
        repository: NovelWorkspaceProjectRepository,
        now: Instant = Instant.now(),
        restoreEpoch: Long = NovelWorkspaceRestoreBoundary.currentEpoch(),
    ): NovelWorkspaceInstaller.Result {
        // Inspect content rather than the filename/MIME supplied by the file provider.
        val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
        val document = if (isZip) null else NovelPackageCodec.decodeForWorkspaceImport(bytes)
        val files = if (document == null) {
            NovelWorkspaceExchange.readZipFiles(ByteArrayInputStream(bytes))
        } else {
            NovelLegacyWorkspaceMigrator.workspaceFiles(document, exportedAt = now)
        }
        val sessions = document?.let(NovelLegacyWorkspaceMigrator::sessionsFile)
        return NovelWorkspaceRestoreBoundary.write(restoreEpoch) {
            val projectId = UUID.randomUUID().toString().uppercase()
            val installed = repository.install(projectId, files, now = now)
            if (sessions != null) {
                try {
                    NovelWorkspaceSessions.save(sessions, installed.projectDirectory)
                } catch (error: Exception) {
                    // Match local migration: a failed discussion write rolls back this new book.
                    repository.delete(projectId)
                    throw error
                }
            }
            installed
        }
    }
}
