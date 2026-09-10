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

    data class Snapshot(
        val jobs: List<NovelWorkspaceGhostwriteJob>,
        /** Retained on disk; these records cannot acquire execution ownership. */
        val unreadableFiles: List<String>,
    )

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
        if (jobId.isBlank() || jobId.any { it == '/' || it == '\\' } || jobId == "..") return null
        val f = File(dir(projectDirectory), "$jobId.json")
        if (!f.exists()) return null
        return readValidJob(f)
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

    fun snapshot(projectDirectory: File): Snapshot {
        val directory = dir(projectDirectory)
        if (!directory.exists()) return Snapshot(emptyList(), emptyList())
        val files = directory.listFiles()
            ?: throw NovelWorkspaceIoError("Cannot read jobs directory: $directory")
        val jobs = mutableListOf<NovelWorkspaceGhostwriteJob>()
        val unreadable = mutableListOf<String>()
        for (file in files.filter { it.extension == "json" }) {
            val job = readValidJob(file)
            if (job == null) {
                unreadable += file.name
            } else {
                jobs += job
            }
        }
        return Snapshot(jobs.sortedBy { it.createdAt }, unreadable.sorted())
    }

    private fun decodeAll(projectDirectory: File): List<NovelWorkspaceGhostwriteJob> =
        snapshot(projectDirectory).jobs

    private fun readValidJob(file: File): NovelWorkspaceGhostwriteJob? = runCatching {
        json.decodeFromString(NovelWorkspaceGhostwriteJob.serializer(), file.readText(Charsets.UTF_8))
    }.getOrNull()?.takeIf {
        it.id == file.nameWithoutExtension && it.targetChapterCount > 0 && it.startOrdinal >= 0 &&
            it.status in validStatuses && it.branchSlug.isNotBlank() &&
            it.branchSlug != ".." && it.branchSlug.none { c -> c == '/' || c == '\\' }
    }

    /** Called after the controller has checked this execution against WorkManager. */
    @Synchronized
    fun recoverUnscheduled(
        projectDirectory: File,
        observedJob: NovelWorkspaceGhostwriteJob,
        hasUnfinishedWork: Boolean,
    ): NovelWorkspaceGhostwriteJob? {
        if (hasUnfinishedWork || observedJob.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING) return null
        val current = load(projectDirectory, observedJob.id) ?: return null
        if (current.executionKey != observedJob.executionKey || current.status != observedJob.status) return null
        val complete = progress(current, NovelWorkspaceStore(projectDirectory)) >= current.targetChapterCount
        return transition(
            projectDirectory, current.id, setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
            if (complete) NovelWorkspaceGhostwriteJob.STATUS_COMPLETED else NovelWorkspaceGhostwriteJob.STATUS_FAILED,
            reason = if (complete) null else "后台执行已中断。已收录章节会保留，可继续剩余章节；未收录内容请先检查工作区。",
            expectedExecutionId = current.executionKey,
        )
    }

    private val validStatuses = setOf(
        NovelWorkspaceGhostwriteJob.STATUS_RUNNING, NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
        NovelWorkspaceGhostwriteJob.STATUS_COMPLETED, NovelWorkspaceGhostwriteJob.STATUS_FAILED,
        NovelWorkspaceGhostwriteJob.STATUS_CANCELLED,
    )

    @Synchronized
    fun dismissFailed(projectDirectory: File, jobId: String, executionId: String): Boolean {
        val current = load(projectDirectory, jobId) ?: return false
        if (current.status != NovelWorkspaceGhostwriteJob.STATUS_FAILED || current.executionKey != executionId) return false
        if (!File(dir(projectDirectory), "$jobId.json").delete()) {
            throw NovelWorkspaceIoError("无法移除失败记录，请重试")
        }
        return true
    }

    /** Progress = durable branch-head chapters committed since the job started. */
    fun progress(job: NovelWorkspaceGhostwriteJob, store: NovelWorkspaceStore): Int {
        val ledger = NovelWorkspaceLedger.load(store.rootDirectory)
        val ordinals = NovelWorkspaceLedger.committedChapterOrdinals(store, ledger, job.branchSlug)
        val currentMax = ordinals.maxOrNull() ?: job.startOrdinal
        return (currentMax - job.startOrdinal).coerceIn(0, job.targetChapterCount)
    }
}
