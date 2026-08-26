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
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore

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
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        check(!NovelWorkspaceLedger.isPlotStale(ledger, branchSlug)) {
            "剧情落后于正文。请切到“讨论”，发送“根据最新正文同步 plot/current.md”，并批准剧情修改后再代笔"
        }
        check(NovelWorkspaceUnresolvedStore.entryFor(projectDirectory, branchSlug) == null) {
            "存在未解决的中间章修改，请先处理（确认无碍/重写后章）再代笔"
        }
        val job = coordinator.newJob(projectDirectory, branchSlug, targetChapterCount)
        try {
            enqueue(projectId, job, ExistingWorkPolicy.REPLACE)
        } catch (error: Exception) {
            NovelWorkspaceGhostwriteJobs.transition(
                projectDirectory = projectDirectory,
                jobId = job.id,
                expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                newStatus = NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                reason = error.message ?: "代笔任务入队失败",
                expectedExecutionId = job.executionKey,
            )
            throw error
        }
        return job
    }

    private fun enqueue(
        projectId: String,
        job: NovelWorkspaceGhostwriteJob,
        policy: ExistingWorkPolicy,
    ) {
        val request = OneTimeWorkRequestBuilder<NovelWorkspaceGhostwriteWorker>()
            .setInputData(
                workDataOf(
                    NovelWorkspaceGhostwriteWorker.KEY_PROJECT_ID to projectId,
                    NovelWorkspaceGhostwriteWorker.KEY_JOB_ID to job.id,
                    NovelWorkspaceGhostwriteWorker.KEY_EXECUTION_ID to job.executionKey,
                ),
            )
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(WORK_TAG)
            .addTag(jobTag(job.id))
            .addTag(executionTag(job.id, job.executionKey))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_TAG:$projectId:${job.branchSlug}",
            policy,
            request,
        )
    }

    fun pause(projectDirectory: java.io.File, jobId: String, executionId: String) {
        val paused = NovelWorkspaceGhostwriteJobs.transition(
            projectDirectory = projectDirectory,
            jobId = jobId,
            expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
            newStatus = NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
            expectedExecutionId = executionId,
        )
        if (paused != null) {
            WorkManager.getInstance(context).cancelAllWorkByTag(executionTag(jobId, executionId))
        }
    }

    /** Resume a paused job: flip it back to running and re-enqueue the worker. */
    fun resume(
        projectDirectory: java.io.File,
        projectId: String,
        jobId: String,
        executionId: String,
    ): NovelWorkspaceGhostwriteJob? {
        val job = NovelWorkspaceGhostwriteJobs.restartPaused(
            projectDirectory,
            jobId,
            expectedExecutionId = executionId,
        ) ?: return null
        try {
            enqueue(projectId, job, ExistingWorkPolicy.REPLACE)
        } catch (error: Exception) {
            NovelWorkspaceGhostwriteJobs.transition(
                projectDirectory = projectDirectory,
                jobId = jobId,
                expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                newStatus = NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
                expectedExecutionId = job.executionKey,
            )
            throw error
        }
        return job
    }

    /** Continue the same failed batch; its original cursor distinguishes its own stale plot. */
    fun retryFailed(
        projectDirectory: java.io.File,
        projectId: String,
        jobId: String,
        executionId: String,
    ): NovelWorkspaceGhostwriteJob {
        val job = checkNotNull(
            NovelWorkspaceGhostwriteJobs.restartFailed(
                projectDirectory,
                jobId,
                expectedExecutionId = executionId,
            ),
        ) {
            "该代笔批次已不可继续"
        }
        try {
            enqueue(projectId, job, ExistingWorkPolicy.REPLACE)
        } catch (error: Exception) {
            NovelWorkspaceGhostwriteJobs.transition(
                projectDirectory = projectDirectory,
                jobId = jobId,
                expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                newStatus = NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                reason = error.message ?: "代笔任务入队失败",
                expectedExecutionId = job.executionKey,
            )
            throw error
        }
        return job
    }

    fun cancel(projectDirectory: java.io.File, jobId: String, executionId: String) {
        val cancelled = NovelWorkspaceGhostwriteJobs.transition(
            projectDirectory = projectDirectory,
            jobId = jobId,
            expectedStatuses = setOf(
                NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
                NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
            ),
            newStatus = NovelWorkspaceGhostwriteJob.STATUS_CANCELLED,
            expectedExecutionId = executionId,
        )
        if (cancelled != null) {
            WorkManager.getInstance(context).cancelAllWorkByTag(jobTag(jobId))
        }
    }

    companion object {
        private const val WORK_TAG = "novel_workspace_ghostwrite"

        private fun jobTag(jobId: String): String = "$WORK_TAG:$jobId"

        private fun executionTag(jobId: String, executionId: String): String =
            "$WORK_TAG:$jobId:$executionId"
    }
}
