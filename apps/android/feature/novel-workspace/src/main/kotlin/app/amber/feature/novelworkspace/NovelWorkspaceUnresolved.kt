package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cross-platform standard D-D (unresolved gate) state.
 *
 * A middle-chapter edit invalidates everything after it; the affected range is recorded
 * here and must be resolved (confirm / fork / rewrite) before further chapters, collects,
 * or ghostwriting proceed. Host-local, like the ledger — it never crosses platforms.
 */
@Serializable
data class NovelWorkspaceUnresolvedEntry(
    val fromOrdinal: Int,
    val sinceCommitId: String,
    @Serializable(with = NovelWorkspaceInstantSerializer::class)
    val setAt: Instant,
)

@Serializable
data class NovelWorkspaceUnresolvedFile(
    val branches: Map<String, NovelWorkspaceUnresolvedEntry> = emptyMap(),
)

object NovelWorkspaceUnresolvedStore {
    const val FILE_NAME = "unresolved.json"

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(projectDirectory: File): File =
        File(File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME), FILE_NAME)

    fun load(projectDirectory: File): NovelWorkspaceUnresolvedFile {
        val f = file(projectDirectory)
        if (!f.exists()) return NovelWorkspaceUnresolvedFile()
        return try {
            json.decodeFromString(NovelWorkspaceUnresolvedFile.serializer(), f.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            NovelWorkspaceUnresolvedFile()
        }
    }

    fun save(unresolved: NovelWorkspaceUnresolvedFile, projectDirectory: File) {
        val ledgerDir = File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME)
        if (!ledgerDir.exists() && !ledgerDir.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create ledger directory: $ledgerDir")
        }
        val destination = file(projectDirectory)
        val temp = File.createTempFile("novel-unresolved-", ".tmp", ledgerDir)
        try {
            temp.writeText(
                json.encodeToString(NovelWorkspaceUnresolvedFile.serializer(), unresolved),
                Charsets.UTF_8,
            )
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            NovelWorkspaceLedger.atomicMove(temp, destination)
        } finally {
            temp.delete()
        }
    }

    fun set(
        projectDirectory: File,
        branchSlug: String,
        fromOrdinal: Int,
        sinceCommitId: String,
        at: Instant = Instant.now(),
    ) {
        val current = load(projectDirectory)
        save(
            current.copy(
                branches = current.branches + (branchSlug to NovelWorkspaceUnresolvedEntry(
                    fromOrdinal = fromOrdinal,
                    sinceCommitId = sinceCommitId,
                    setAt = at,
                )),
            ),
            projectDirectory,
        )
    }

    fun clear(projectDirectory: File, branchSlug: String) {
        val current = load(projectDirectory)
        if (branchSlug !in current.branches) return
        save(current.copy(branches = current.branches - branchSlug), projectDirectory)
    }

    fun entryFor(projectDirectory: File, branchSlug: String): NovelWorkspaceUnresolvedEntry? =
        load(projectDirectory).branches[branchSlug]
}
