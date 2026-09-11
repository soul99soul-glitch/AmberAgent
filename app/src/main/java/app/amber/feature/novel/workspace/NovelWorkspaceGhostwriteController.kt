package app.amber.feature.novel.workspace

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.amber.agent.R
import app.amber.core.utils.appLocale
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Drives workspace ghostwrite batches: create job + enqueue WorkManager, pause/resume by
 * writing the job status (the worker re-reads it each chapter), cancel removes the work.
 * Progress is derived from the ledger — there is no separate counter to drift.
 */
class NovelWorkspaceGhostwriteController(
    private val context: Context,
    private val coordinator: NovelWorkspaceGhostwriteCoordinator,
) {
    /** Serialize job-file mutations with WorkManager identity lookup/enqueue. */
    private val mutationMutex = Mutex()

    suspend fun startBatch(
        projectDirectory: java.io.File,
        projectId: String,
        branchSlug: String,
        targetChapterCount: Int,
    ): NovelWorkspaceGhostwriteJob = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            require(targetChapterCount in 1..NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS) {
                localized(
                    chinese = "代笔章数必须在 1 到 ${NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS} 之间",
                    english = "The ghostwrite target must be between 1 and ${NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS} chapters.",
                )
            }
            val store = NovelWorkspaceStore(projectDirectory)
            val ledger = NovelWorkspaceLedger.load(projectDirectory)
            // Write mode commits chapters and plot together. A stale plot here is a real
            // authoring gap; the dangling polish-pointer repair belongs to polish mode.
            check(!NovelWorkspaceLedger.isPlotStale(store, ledger, branchSlug)) {
                context.getString(
                    R.string.novel_ghostwrite_error_stale_plot,
                    context.getString(R.string.novel_ghostwrite_task_write),
                )
            }
            check(NovelWorkspaceUnresolvedStore.entryFor(projectDirectory, branchSlug) == null) {
                context.getString(
                    R.string.novel_ghostwrite_error_unresolved_edits,
                    context.getString(R.string.novel_ghostwrite_task_write),
                )
            }
            val job = coordinator.newJob(
                projectDirectory,
                branchSlug,
                targetChapterCount,
                locale = context.appLocale(),
            )
            try {
                enqueue(projectId, job, ExistingWorkPolicy.REPLACE)
            } catch (error: Exception) {
                NovelWorkspaceGhostwriteJobs.transition(
                    projectDirectory = projectDirectory,
                    jobId = job.id,
                    expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                    newStatus = NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                    reason = error.message ?: localized(
                        chinese = "代笔任务入队失败",
                        english = "Could not enqueue the ghostwrite batch.",
                    ),
                    expectedExecutionId = job.executionKey,
                )
                throw error
            }
            job
        }
    }

    /** Start a polishing batch over the inclusive ordinal range [fromOrdinal, toOrdinal]. */
    suspend fun startPolishBatch(
        projectDirectory: java.io.File,
        projectId: String,
        branchSlug: String,
        fromOrdinal: Int,
        toOrdinal: Int,
    ): NovelWorkspaceGhostwriteJob = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val job = coordinator.preparePolishBatch(
                projectDirectory,
                branchSlug,
                fromOrdinal,
                toOrdinal,
                locale = context.appLocale(),
            )
            try {
                enqueue(projectId, job, ExistingWorkPolicy.REPLACE)
            } catch (error: Exception) {
                NovelWorkspaceGhostwriteJobs.transition(
                    projectDirectory = projectDirectory,
                    jobId = job.id,
                    expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                    newStatus = NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                    reason = error.message ?: localized(
                        chinese = "润色任务入队失败",
                        english = "Could not enqueue the polishing batch.",
                    ),
                    expectedExecutionId = job.executionKey,
                )
                throw error
            }
            job
        }
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
        // Include the mode so an old queued write cannot cancel a polish enqueue. Branch
        // exclusivity is still enforced by saveIfNoActive/newPolishJob at the durable layer.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_TAG:$projectId:${job.branchSlug}:${job.mode.value}",
            policy,
            request,
        ).result.get()
    }

    private fun localized(chinese: String, english: String): String =
        if (context.appLocale().language.equals("zh", ignoreCase = true)) chinese else english

    suspend fun pause(projectDirectory: java.io.File, jobId: String, executionId: String) =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val paused = NovelWorkspaceGhostwriteJobs.transition(
                    projectDirectory = projectDirectory,
                    jobId = jobId,
                    expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                    newStatus = NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
                    expectedExecutionId = executionId,
                )
                if (paused != null) {
                    WorkManager.getInstance(context)
                        .cancelAllWorkByTag(executionTag(jobId, executionId))
                        .result.get()
                }
            }
        }

    /** Resume a paused job and issue a fresh execution identity to its Worker. */
    suspend fun resume(
        projectDirectory: java.io.File,
        projectId: String,
        jobId: String,
        executionId: String,
        expectedBranchSlug: String? = null,
    ): NovelWorkspaceGhostwriteJob? = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val job = NovelWorkspaceGhostwriteJobs.restartPaused(
                projectDirectory,
                jobId,
                expectedExecutionId = executionId,
                expectedBranchSlug = expectedBranchSlug,
            ) ?: return@withLock null
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
            job
        }
    }

    /** Continue the same failed batch; its original cursor distinguishes its own stale plot. */
    suspend fun retryFailed(
        projectDirectory: java.io.File,
        projectId: String,
        jobId: String,
        executionId: String,
        expectedBranchSlug: String? = null,
    ): NovelWorkspaceGhostwriteJob = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val job = checkNotNull(
                NovelWorkspaceGhostwriteJobs.restartFailed(
                    projectDirectory,
                    jobId,
                    expectedExecutionId = executionId,
                    expectedBranchSlug = expectedBranchSlug,
                ),
            ) {
                localized(
                    chinese = "该代笔批次已不可继续",
                    english = "This ghostwrite batch can no longer continue.",
                )
            }
            try {
                enqueue(projectId, job, ExistingWorkPolicy.REPLACE)
            } catch (error: Exception) {
                NovelWorkspaceGhostwriteJobs.transition(
                    projectDirectory = projectDirectory,
                    jobId = jobId,
                    expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                    newStatus = NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                    reason = error.message ?: localized(
                        chinese = "代笔任务入队失败",
                        english = "Could not enqueue the ghostwrite batch.",
                    ),
                    expectedExecutionId = job.executionKey,
                )
                throw error
            }
            job
        }
    }

    suspend fun cancel(projectDirectory: java.io.File, jobId: String, executionId: String) =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
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
                    WorkManager.getInstance(context).cancelAllWorkByTag(jobTag(jobId)).result.get()
                }
            }
        }

    /** Serialize the WorkManager lookup with enqueue so a new batch is never mistaken for an orphan. */
    suspend fun reconcile(projectDirectory: java.io.File) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val workManager = WorkManager.getInstance(context)
            for (job in NovelWorkspaceGhostwriteJobs.listActive(projectDirectory)) {
                if (job.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING) continue
                val work = workManager.getWorkInfosByTag(executionTag(job.id, job.executionKey)).get()
                NovelWorkspaceGhostwriteJobs.recoverUnscheduled(
                    projectDirectory,
                    job,
                    hasUnfinishedWork = work.any { !it.state.isFinished },
                )
            }
        }
    }

    companion object {
        private const val WORK_TAG = "novel_workspace_ghostwrite"

        private fun jobTag(jobId: String): String = "$WORK_TAG:$jobId"

        private fun executionTag(jobId: String, executionId: String): String =
            "$WORK_TAG:$jobId:$executionId"
    }
}
