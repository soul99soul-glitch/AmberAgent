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
        require(targetChapterCount in 1..NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS) {
            localized(
                chinese = "代笔章数必须在 1 到 ${NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS} 之间",
                english = "The ghostwrite target must be between 1 and ${NovelWorkspaceGhostwriteCoordinator.MAX_GHOSTWRITE_CHAPTERS} chapters.",
            )
        }
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        // Write 模式没有「悬挂配对」窗口可自愈：代笔的一轮把 chapters/ 与 plot/ 的写入
        // 在同一笔 commit 落地（中途崩溃则整轮回滚、什么都不剩），不存在「润色已提交、
        // 指针未提交」那种只缺一条配对 commit 的形态。这里的 stale 一定是真实剧情缺口，
        // 维持拒绝并引导到讨论页同步。
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
        return job
    }

    /**
     * Start a batch polish over the inclusive ordinal range [fromOrdinal, toOrdinal].
     * Gates live in [NovelWorkspaceGhostwriteCoordinator.preparePolishBatch] (freshness
     * gate + dangling-pointer self-heal + unresolved gate + range validation); this
     * wrapper only owns the WorkManager enqueue and its failure transition.
     */
    fun startPolishBatch(
        projectDirectory: java.io.File,
        projectId: String,
        branchSlug: String,
        fromOrdinal: Int,
        toOrdinal: Int,
    ): NovelWorkspaceGhostwriteJob {
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
        // The unique-work name carries the mode: a polish batch and a ghostwrite batch
        // on the same branch must not cancel each other's WorkManager work — branch
        // exclusivity is enforced at the job level (saveIfNoActive), not here.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_TAG:$projectId:${job.branchSlug}:${job.mode.value}",
            policy,
            request,
        )
    }

    private fun localized(chinese: String, english: String): String =
        if (context.appLocale().language.equals("zh", ignoreCase = true)) chinese else english

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

    /**
     * Resume a paused job: flip it back to running and re-enqueue the worker.
     * [expectedBranchSlug] 非空时要求批次仍属于该分支（作者切走后被拒，先切回原分支）。
     */
    fun resume(
        projectDirectory: java.io.File,
        projectId: String,
        jobId: String,
        executionId: String,
        expectedBranchSlug: String? = null,
    ): NovelWorkspaceGhostwriteJob? {
        val job = NovelWorkspaceGhostwriteJobs.restartPaused(
            projectDirectory,
            jobId,
            expectedExecutionId = executionId,
            expectedBranchSlug = expectedBranchSlug,
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
        expectedBranchSlug: String? = null,
    ): NovelWorkspaceGhostwriteJob {
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
