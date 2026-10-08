package app.amber.feature.novelworkspace

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Author-facing output only. Recovering it never adopts a chapter or replays tools. */
@Serializable
data class NovelWorkspaceTurnOutput(
    val runId: String,
    val projectPath: String,
    val branchId: String,
    val branchSlug: String,
    val userText: String,
    val content: String,
    val completed: Boolean = false,
    @Serializable(with = NovelWorkspaceInstantSerializer::class)
    val createdAt: Instant,
)

/** Host-local snapshots, retained until their session message has been durably saved. */
object NovelWorkspaceTurnOutputs {
    private const val DIRECTORY_NAME = "turn-outputs"
    private val json = Json { ignoreUnknownKeys = true }
    private val activeRuns = mutableMapOf<String, Long>()

    private fun directory(projectDirectory: File) =
        File(File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME), DIRECTORY_NAME)

    private fun file(projectDirectory: File, runId: String) =
        File(directory(projectDirectory), "${sha256Hex(runId)}.json")

    private fun key(projectDirectory: File, runId: String) =
        "${projectDirectory.absolutePath}\u0000$runId"

    fun begin(projectDirectory: File, runId: String, expectedEpoch: Long? = null) =
        NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
            activeRuns[key(projectDirectory, runId)] = NovelWorkspaceRestoreBoundary.currentEpoch()
        }

    fun end(projectDirectory: File, runId: String, expectedEpoch: Long? = null) =
        synchronized(NovelWorkspaceGhostwriteJobs) {
            val runKey = key(projectDirectory, runId)
            if (expectedEpoch == null || activeRuns[runKey] == expectedEpoch) activeRuns.remove(runKey)
            Unit
        }

    fun save(projectDirectory: File, output: NovelWorkspaceTurnOutput, expectedEpoch: Long? = null) =
        NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
            if (output.content.isBlank()) return@write
            val dir = directory(projectDirectory)
            if (!dir.exists() && !dir.mkdirs()) throw NovelWorkspaceIoError("Cannot create turn output directory: $dir")
            val temp = File.createTempFile("novel-turn-", ".tmp", dir)
            try {
                temp.writeText(json.encodeToString(NovelWorkspaceTurnOutput.serializer(), output), Charsets.UTF_8)
                RandomAccessFile(temp, "rw").use { it.fd.sync() }
                NovelWorkspaceLedger.atomicMove(temp, file(projectDirectory, output.runId))
            } finally {
                temp.delete()
            }
        }

    fun loadPending(projectDirectory: File, branchId: String): List<NovelWorkspaceTurnOutput> =
        synchronized(NovelWorkspaceGhostwriteJobs) { loadPendingLocked(projectDirectory, branchId) }

    private fun loadPendingLocked(projectDirectory: File, branchId: String): List<NovelWorkspaceTurnOutput> =
        directory(projectDirectory).listFiles().orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull { candidate ->
                // Leave unreadable snapshots in place; recovery must not erase author output.
                runCatching {
                    json.decodeFromString(NovelWorkspaceTurnOutput.serializer(), candidate.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            .filter { it.branchId == branchId && it.content.isNotBlank() }
            .sortedBy { it.createdAt }

    fun acknowledge(projectDirectory: File, runId: String, expectedEpoch: Long? = null) =
        NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
            val snapshot = file(projectDirectory, runId)
            if (snapshot.exists() && !snapshot.delete()) throw NovelWorkspaceIoError("Cannot remove saved turn output: $snapshot")
        }

    /** Called before loading branch history. Active turns stay live; cold snapshots become bubbles. */
    fun recoverToSessions(projectDirectory: File, branchId: String, expectedEpoch: Long? = null) =
        NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
            val pending = loadPendingLocked(projectDirectory, branchId).filter {
                activeRuns[key(projectDirectory, it.runId)]?.let(NovelWorkspaceRestoreBoundary::isCurrent) != true
            }
            if (pending.isEmpty()) return@write
            val sessions = NovelWorkspaceSessions.load(projectDirectory)
            val messages = sessions.sessions[branchId].orEmpty()
            val existingIds = messages.map { it.id }.toSet()
            val recovered = pending.filter { it.runId !in existingIds }.map {
                NovelWorkspaceSessionMessage(
                    id = it.runId,
                    role = "assistant",
                    kind = if (it.completed) "discussion" else "interrupted",
                    content = it.content,
                    createdAt = it.createdAt,
                )
            }
            if (recovered.isNotEmpty()) {
                NovelWorkspaceSessions.save(
                    sessions.copy(sessions = sessions.sessions + (branchId to (messages + recovered).sortedBy { it.createdAt })),
                    projectDirectory,
                )
            }
            // A crash after saving history and before deletion is harmless: stable run ids deduplicate.
            pending.forEach { acknowledge(projectDirectory, it.runId, expectedEpoch) }
        }
}
