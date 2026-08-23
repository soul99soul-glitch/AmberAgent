package app.amber.feature.novel.workspace

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.time.Instant
import java.util.UUID

/**
 * Drives workspace ghostwrite batches: create job + enqueue WorkManager, pause/resume by
 * writing the job status (the worker re-reads it each chapter), cancel removes the work.
 * Progress is derived from the ledger — there is no separate counter to drift.
 */
class NovelWorkspaceGhostwriteController(
    private val context: Context,
    private val coordinator: NovelWorkspaceGhostwriteCoordinator,
) {

    fun startBatch(
        projectDirectory: java.io.File,
        projectId: String,
        branchSlug: String,
        targetChapterCount: Int,
    ): NovelWorkspaceGhostwriteJob {
        require(targetChapterCount > 0) { "targetChapterCount must be positive" }
        val job = coordinator.newJob(projectDirectory, branchSlug, targetChapterCount)
        enqueue(projectId, job)
        return job
    }

    private fun enqueue(projectId: String, job: NovelWorkspaceGhostwriteJob) {
        val request = OneTimeWorkRequestBuilder<NovelWorkspaceGhostwriteWorker>()
            .setInputData(
                workDataOf(
                    NovelWorkspaceGhostwriteWorker.KEY_PROJECT_ID to projectId,
                    NovelWorkspaceGhostwriteWorker.KEY_JOB_ID to job.id,
                ),
            )
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(WORK_TAG)
            .addTag("$WORK_TAG:${job.id}")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_TAG:${job.id}",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun pause(projectDirectory: java.io.File, jobId: String) {
        NovelWorkspaceGhostwriteJobs.load(projectDirectory, jobId)?.let { job ->
            NovelWorkspaceGhostwriteJobs.save(
                job.copy(status = NovelWorkspaceGhostwriteJob.STATUS_PAUSED, updatedAt = Instant.now()),
                projectDirectory,
            )
        }
    }

    /** Resume a paused job: flip it back to running and re-enqueue the worker. */
    fun resume(projectDirectory: java.io.File, projectId: String, jobId: String) {
        val job = NovelWorkspaceGhostwriteJobs.load(projectDirectory, jobId) ?: return
        if (!job.isTerminal) {
            NovelWorkspaceGhostwriteJobs.save(
                job.copy(status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING, updatedAt = Instant.now()),
                projectDirectory,
            )
            enqueue(projectId, job)
        }
    }

    fun cancel(projectDirectory: java.io.File, jobId: String) {
        NovelWorkspaceGhostwriteJobs.load(projectDirectory, jobId)?.let { job ->
            NovelWorkspaceGhostwriteJobs.save(
                job.copy(status = NovelWorkspaceGhostwriteJob.STATUS_CANCELLED, updatedAt = Instant.now()),
                projectDirectory,
            )
        }
        WorkManager.getInstance(context).cancelAllWorkByTag("$WORK_TAG:$jobId")
    }

    companion object {
        private const val WORK_TAG = "novel_workspace_ghostwrite"
    }
}
