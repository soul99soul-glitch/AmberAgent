package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ghostwrite batch job record. Progress is NOT stored — it is derived from the ledger
 * (chapters committed since the job started), so a crashed worker never reports stale
 * progress and resume is recomputed from the actual manuscript, not a counter.
 */
@Serializable
data class NovelWorkspaceGhostwriteJob(
    val id: String,
    /** Changes on every resume/retry so an older Worker cannot regain write ownership. */
    val executionId: String = "",
    val branchSlug: String,
    val targetChapterCount: Int,
    /** Manuscript ordinal just before the job's first chapter was committed. */
    val startOrdinal: Int,
    val status: String = STATUS_RUNNING,
    val reason: String? = null,
    @Serializable(with = NovelWorkspaceInstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = NovelWorkspaceInstantSerializer::class)
    val updatedAt: Instant,
) {
    val executionKey: String get() = executionId.ifBlank { id }

    val isTerminal: Boolean get() =
        status == STATUS_COMPLETED || status == STATUS_FAILED || status == STATUS_CANCELLED

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}

object NovelWorkspaceGhostwriteJobs {
    private const val DIR = "jobs"

    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(projectDirectory: File): File =
        File(File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME), DIR)

    @Synchronized
    fun save(job: NovelWorkspaceGhostwriteJob, projectDirectory: File) {
        val directory = dir(projectDirectory)
        if (!directory.exists() && !directory.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create jobs directory: $directory")
        }
        val destination = File(directory, "${job.id}.json")
        val temp = File.createTempFile("novel-job-", ".tmp", directory)
        try {
            temp.writeText(
                json.encodeToString(NovelWorkspaceGhostwriteJob.serializer(), job),
                Charsets.UTF_8,
            )
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            NovelWorkspaceLedger.atomicMove(temp, destination)
        } finally {
            temp.delete()
        }
    }

    fun load(projectDirectory: File, jobId: String): NovelWorkspaceGhostwriteJob? {
        val f = File(dir(projectDirectory), "$jobId.json")
        if (!f.exists()) return null
        return try {
            json.decodeFromString(NovelWorkspaceGhostwriteJob.serializer(), f.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            null
        }
    }

    fun listActive(projectDirectory: File): List<NovelWorkspaceGhostwriteJob> =
        decodeAll(projectDirectory).filter { !it.isTerminal }

    @Synchronized
    fun activeFor(projectDirectory: File, branchSlug: String): NovelWorkspaceGhostwriteJob? =
        listActive(projectDirectory).firstOrNull { it.branchSlug == branchSlug }

    /** Atomically claim the branch for a new batch inside this app process. */
    @Synchronized
    fun saveIfNoActive(job: NovelWorkspaceGhostwriteJob, projectDirectory: File): Boolean {
        if (activeFor(projectDirectory, job.branchSlug) != null) return false
        save(job, projectDirectory)
        return true
    }

    /** Reactivate the same failed batch so its durable cursor and target stay intact. */
    @Synchronized
    fun restartFailed(
        projectDirectory: File,
        jobId: String,
        expectedExecutionId: String? = null,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_FAILED) return null
        if (expectedExecutionId != null && current.executionKey != expectedExecutionId) return null
        if (activeFor(projectDirectory, current.branchSlug) != null) return null
        return current.copy(
            executionId = java.util.UUID.randomUUID().toString().uppercase(),
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            reason = null,
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /** Resume only the paused durable state and invalidate the previous Worker execution. */
    @Synchronized
    fun restartPaused(
        projectDirectory: File,
        jobId: String,
        expectedExecutionId: String? = null,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_PAUSED) return null
        if (expectedExecutionId != null && current.executionKey != expectedExecutionId) return null
        return current.copy(
            executionId = java.util.UUID.randomUUID().toString().uppercase(),
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            reason = null,
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /** Persist a state change only when the latest durable state still matches. */
    @Synchronized
    fun transition(
        projectDirectory: File,
        jobId: String,
        expectedStatuses: Set<String>,
        newStatus: String,
        reason: String? = null,
        expectedExecutionId: String? = null,
    ): NovelWorkspaceGhostwriteJob? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status !in expectedStatuses) return null
        if (expectedExecutionId != null && current.executionKey != expectedExecutionId) return null
        return current.copy(
            status = newStatus,
            reason = reason,
            updatedAt = Instant.now(),
        ).also { save(it, projectDirectory) }
    }

    /** Serialize the final owner check with pause/cancel transitions. */
    @Synchronized
    fun <T> withRunningOwner(
        projectDirectory: File,
        jobId: String,
        executionId: String,
        block: () -> T,
    ): T? {
        val current = load(projectDirectory, jobId) ?: return null
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
            current.executionKey != executionId
        ) return null
        return block()
    }

    /**
     * Latest failed job, if any — surfaced by the UI so a dead batch's reason is
     * not silent (the worker posts no failure notification of its own).
     */
    fun latestFailed(
        projectDirectory: File,
        branchSlug: String? = null,
    ): NovelWorkspaceGhostwriteJob? =
        decodeAll(projectDirectory)
            .filter {
                it.status == NovelWorkspaceGhostwriteJob.STATUS_FAILED &&
                    (branchSlug == null || it.branchSlug == branchSlug)
            }
            .maxByOrNull { it.updatedAt }

    private fun decodeAll(projectDirectory: File): List<NovelWorkspaceGhostwriteJob> {
        val directory = dir(projectDirectory)
        if (!directory.exists()) return emptyList()
        return directory.listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull { f ->
                runCatching {
                    json.decodeFromString(NovelWorkspaceGhostwriteJob.serializer(), f.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            .sortedBy { it.createdAt }
    }

    fun delete(projectDirectory: File, jobId: String) {
        File(dir(projectDirectory), "$jobId.json").delete()
    }

    /** Progress = durable branch-head chapters committed since the job started. */
    fun progress(job: NovelWorkspaceGhostwriteJob, store: NovelWorkspaceStore): Int {
        val ledger = NovelWorkspaceLedger.load(store.rootDirectory)
        val ordinals = NovelWorkspaceLedger.committedChapterOrdinals(store, ledger, job.branchSlug)
        val currentMax = ordinals.maxOrNull() ?: job.startOrdinal
        return (currentMax - job.startOrdinal).coerceIn(0, job.targetChapterCount)
    }
}
