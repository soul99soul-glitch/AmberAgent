package app.amber.feature.novel.background

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.utils.NotificationUtil
import app.amber.feature.novel.domain.NovelGhostwriteJobError
import app.amber.feature.novel.domain.NovelGhostwriteJobReducer
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobPhase
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelGhostwriteJobV1
import app.amber.feature.novel.persistence.NovelGhostwriteJobLoadAccess
import app.amber.feature.novel.persistence.NovelGhostwriteJobStore
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * The only foreground owner for a durable multi-chapter ghostwrite batch.
 *
 * The legacy AgentGenerationForegroundService remains responsible for chat and
 * single-run generation only; this worker never starts it.
 */
class NovelGhostwriteWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
            ?.let { raw -> runCatching { NovelGhostwriteJobId.parse(raw) }.getOrNull() }
            ?: return failure(FAILURE_INVALID_JOB_ID)
        val store = get<NovelGhostwriteJobStore>()
        val loaded = runCatching { store.loadJob(jobId) }.getOrElse { error ->
            Log.e(TAG, "Unable to load ghostwrite job", error)
            return failure(FAILURE_JOB_UNAVAILABLE)
        }
        if (loaded.access != NovelGhostwriteJobLoadAccess.ReadWrite) {
            return failure(FAILURE_JOB_READ_ONLY)
        }
        if (loaded.job.isTerminal) {
            return terminalResult(loaded.job.status)
        }

        val workId = id.toString()
        val scheduledExecutionEpoch = inputData.getLong(
            KEY_SCHEDULED_EXECUTION_EPOCH,
            MISSING_EXECUTION_EPOCH,
        )
        if (scheduledExecutionEpoch == MISSING_EXECUTION_EPOCH) {
            return failure(FAILURE_INVALID_EXECUTION_EPOCH)
        }
        if (!isEligibleWork(loaded.job, workId, scheduledExecutionEpoch)) {
            return success(SUCCESS_SUPERSEDED_WORK)
        }
        if (!canRemainForeground()) {
            return failClosedResult(
                store,
                jobId,
                workId,
                scheduledExecutionEpoch,
                FAILURE_NOTIFICATION_UNAVAILABLE,
            )
        }

        try {
            setForeground(createForegroundInfo(loaded.job))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to enter foreground for ghostwrite batch", error)
            return failClosedResult(
                store,
                jobId,
                workId,
                scheduledExecutionEpoch,
                FAILURE_FOREGROUND_START,
            )
        }

        return try {
            val batchResult = runWithNotificationUpdates(
                runner = get(),
                store = store,
                jobId = jobId,
                workId = workId,
                initialLedgerRevision = loaded.job.ledgerRevision,
            )
            val finalLoaded = store.loadJob(jobId)
            if (finalLoaded.access != NovelGhostwriteJobLoadAccess.ReadWrite) {
                return failure(FAILURE_JOB_READ_ONLY)
            }
            when (
                reconcileBatchResult(
                    result = batchResult,
                    job = finalLoaded.job,
                    workId = workId,
                    scheduledExecutionEpoch = scheduledExecutionEpoch,
                )
            ) {
                BatchResultDisposition.Success -> Result.success()
                BatchResultDisposition.Retry -> Result.retry()
                BatchResultDisposition.PauseCurrent -> failClosedResult(
                    store,
                    jobId,
                    workId,
                    scheduledExecutionEpoch,
                    FAILURE_EXECUTOR_STATE_MISMATCH,
                )

                BatchResultDisposition.Failure -> failure(FAILURE_EXECUTOR)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ForegroundUnavailable) {
            Log.e(TAG, "Foreground notification became unavailable", error)
            failClosedResult(store, jobId, workId, scheduledExecutionEpoch, error.reasonCode)
        } catch (error: Throwable) {
            Log.e(TAG, "Ghostwrite batch worker failed", error)
            failClosedResult(store, jobId, workId, scheduledExecutionEpoch, FAILURE_WORKER)
        }
    }

    private suspend fun runWithNotificationUpdates(
        runner: NovelGhostwriteBatchRunning,
        store: NovelGhostwriteJobStore,
        jobId: NovelGhostwriteJobId,
        workId: String,
        initialLedgerRevision: Long,
    ): BatchRunResult = supervisorScope {
        val execution = async { runner.run(jobId, workId) }
        val monitor = async {
            var displayedRevision = initialLedgerRevision
            while (true) {
                delay(NOTIFICATION_REFRESH_MS)
                if (!canRemainForeground()) {
                    throw ForegroundUnavailable(FAILURE_NOTIFICATION_UNAVAILABLE)
                }
                val loaded = store.loadJob(jobId)
                if (loaded.access != NovelGhostwriteJobLoadAccess.ReadWrite) {
                    throw ForegroundUnavailable(FAILURE_JOB_READ_ONLY)
                }
                if (loaded.job.ledgerRevision != displayedRevision) {
                    try {
                        setForeground(createForegroundInfo(loaded.job))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        throw ForegroundUnavailable(FAILURE_FOREGROUND_UPDATE, error)
                    }
                    displayedRevision = loaded.job.ledgerRevision
                }
            }
        }
        try {
            select {
                execution.onAwait { it }
                monitor.onAwait {
                    throw ForegroundUnavailable(FAILURE_FOREGROUND_UPDATE)
                }
            }
        } finally {
            execution.cancelAndJoin()
            monitor.cancelAndJoin()
        }
    }

    private fun canRemainForeground(): Boolean = runCatching {
        NotificationUtil.canShowNotification(
            applicationContext,
            CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
        )
    }.getOrElse { error ->
        Log.e(TAG, "Unable to verify foreground notification visibility", error)
        false
    }

    private suspend fun failClosedResult(
        store: NovelGhostwriteJobStore,
        jobId: NovelGhostwriteJobId,
        workId: String,
        scheduledExecutionEpoch: Long,
        reasonCode: String,
    ): Result {
        val failClosedStateConfirmed = ensureFailClosedState(
            store,
            jobId,
            workId,
            scheduledExecutionEpoch,
            reasonCode,
        )
        // A durable pause is a handled control outcome. Returning success keeps a
        // resume request appended during worker unwind from inheriting a failure.
        return when (failClosedResolution(failClosedStateConfirmed)) {
            WorkResolution.Success -> success(reasonCode)
            WorkResolution.Failure -> failure(reasonCode)
        }
    }

    private suspend fun ensureFailClosedState(
        store: NovelGhostwriteJobStore,
        jobId: NovelGhostwriteJobId,
        workId: String,
        scheduledExecutionEpoch: Long,
        reasonCode: String,
    ): Boolean {
        repeat(PAUSE_CAS_ATTEMPTS) {
            val loaded = runCatching { store.loadJob(jobId) }.getOrElse { return false }
            if (loaded.access != NovelGhostwriteJobLoadAccess.ReadWrite) return false
            val job = loaded.job
            if (job.isTerminal || job.status == NovelGhostwriteJobStatus.Paused) return true
            if (!isEligibleWork(job, workId, scheduledExecutionEpoch)) return true
            val now = Instant.now()
            try {
                val paused = NovelGhostwriteJobReducer.pause(
                    job = job,
                    expectedLedgerRevision = job.ledgerRevision,
                    expectedExecutionEpoch = job.executionEpoch,
                    reasonCode = reasonCode,
                    now = now,
                )
                store.commitJob(
                    job = paused,
                    expectedLedgerRevision = job.ledgerRevision,
                    expectedExecutionEpoch = job.executionEpoch,
                )
                return true
            } catch (_: NovelGhostwriteJobError.StaleLedgerRevision) {
                // Reload and retry the bounded CAS.
            } catch (_: NovelGhostwriteJobError.StaleExecutionEpoch) {
                // Reload and retry the bounded CAS.
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to persist fail-closed ghostwrite pause", error)
                return false
            }
        }
        return false
    }

    private fun createForegroundInfo(job: NovelGhostwriteJobV1): ForegroundInfo {
        val notificationId = notificationId(job.id)
        val notification = buildNotification(job, notificationId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(
        job: NovelGhostwriteJobV1,
        notificationId: Int,
    ): Notification {
        val snapshot = notificationSnapshot(job)
        return NotificationCompat.Builder(
            applicationContext,
            CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(snapshot.title)
            .setContentText(snapshot.content)
            .setContentIntent(buildLaunchPendingIntent(job, notificationId))
            .setProgress(snapshot.target, snapshot.completed, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildLaunchPendingIntent(
        job: NovelGhostwriteJobV1,
        requestCode: Int,
    ): PendingIntent {
        val route = notificationRouteIdentity(job)
        val intent = Intent(applicationContext, RouteActivity::class.java).apply {
            action = if (route == null) ACTION_OPEN_APP else ACTION_OPEN_NOVEL_RUN
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // RouteActivity intentionally requires an exact project+run pair. Before
            // a run is prepared we open Amber's default screen instead of inventing one.
            route?.let {
                putExtra(RouteActivity.EXTRA_OPEN_NOVEL_PROJECT_ID, route.projectId)
                putExtra(RouteActivity.EXTRA_OPEN_NOVEL_RUN_ID, route.runId)
            }
        }
        return PendingIntent.getActivity(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun terminalResult(status: NovelGhostwriteJobStatus): Result = when (status) {
        NovelGhostwriteJobStatus.Completed,
        NovelGhostwriteJobStatus.Failed,
        NovelGhostwriteJobStatus.Cancelled,
        -> Result.success()

        NovelGhostwriteJobStatus.Pending,
        NovelGhostwriteJobStatus.Running,
        NovelGhostwriteJobStatus.Paused,
        -> failure(FAILURE_WORKER)
    }

    private fun failure(reasonCode: String): Result =
        Result.failure(workDataOf(KEY_RESULT_REASON to reasonCode))

    private fun success(reasonCode: String): Result =
        Result.success(workDataOf(KEY_RESULT_REASON to reasonCode))

    private class ForegroundUnavailable(
        val reasonCode: String,
        cause: Throwable? = null,
    ) : Exception(reasonCode, cause)

    companion object {
        const val KEY_JOB_ID = "novel_ghostwrite_job_id"
        const val KEY_SCHEDULED_EXECUTION_EPOCH = "novel_ghostwrite_scheduled_execution_epoch"
        const val KEY_RESULT_REASON = "novel_ghostwrite_result_reason"

        internal const val FAILURE_INVALID_JOB_ID = "invalid_job_id"
        internal const val FAILURE_INVALID_EXECUTION_EPOCH = "invalid_execution_epoch"
        internal const val FAILURE_JOB_UNAVAILABLE = "job_unavailable"
        internal const val FAILURE_JOB_READ_ONLY = "job_read_only"
        internal const val FAILURE_NOTIFICATION_UNAVAILABLE = "foreground_notification_unavailable"
        internal const val FAILURE_FOREGROUND_START = "foreground_start_failed"
        internal const val FAILURE_FOREGROUND_UPDATE = "foreground_update_failed"
        internal const val FAILURE_EXECUTOR = "batch_executor_failed"
        internal const val FAILURE_EXECUTOR_STATE_MISMATCH = "batch_executor_state_mismatch"
        internal const val FAILURE_WORKER = "worker_shell_failed"
        internal const val SUCCESS_SUPERSEDED_WORK = "superseded_work"

        private const val TAG = "NovelGhostwriteWorker"
        private const val ACTION_OPEN_APP = "app.amber.agent.action.OPEN_GHOSTWRITE_BATCH"
        private const val ACTION_OPEN_NOVEL_RUN = "app.amber.agent.action.OPEN_GHOSTWRITE_RUN"
        private const val NOTIFICATION_REFRESH_MS = 1_500L
        private const val PAUSE_CAS_ATTEMPTS = 3
        private const val MISSING_EXECUTION_EPOCH = -1L

        /**
         * A work request may claim only the epoch captured when it was enqueued.
         * A WorkManager retry may re-enter the immediately following epoch only
         * when that exact WorkRequest still owns the lease. Older requests never
         * wait for or steal an expired lease from a newer owner.
         */
        internal fun isEligibleWork(
            job: NovelGhostwriteJobV1,
            workId: String,
            scheduledExecutionEpoch: Long,
        ): Boolean {
            if (scheduledExecutionEpoch < 0 || workId.isBlank()) return false
            if (job.executionEpoch == scheduledExecutionEpoch) {
                return job.leaseOwnerWorkID == null
            }
            return scheduledExecutionEpoch < Long.MAX_VALUE &&
                job.executionEpoch == scheduledExecutionEpoch + 1 &&
                job.leaseOwnerWorkID == workId
        }

        internal fun notificationId(jobId: NovelGhostwriteJobId): Int =
            (jobId.rawValue.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

        internal fun notificationRouteIdentity(job: NovelGhostwriteJobV1): NotificationRouteIdentity? =
            job.currentCursor.runID?.let { runId ->
                NotificationRouteIdentity(
                    projectId = job.projectID.toString(),
                    runId = runId.toString(),
                )
            }

        internal fun notificationSnapshot(job: NovelGhostwriteJobV1): NotificationSnapshot =
            NotificationSnapshot(
                title = "小说代笔 第 ${job.currentCursor.chapterIndex}/${job.targetChapterCount} 章",
                content = phaseProgressLabel(job),
                completed = job.completedChapterCount.coerceIn(0, job.targetChapterCount),
                target = job.targetChapterCount,
            )

        internal fun reconcileBatchResult(
            result: BatchRunResult,
            job: NovelGhostwriteJobV1,
            workId: String,
            scheduledExecutionEpoch: Long,
        ): BatchResultDisposition {
            if (job.status == NovelGhostwriteJobStatus.Paused || job.isTerminal) {
                return BatchResultDisposition.Success
            }
            if (job.status != NovelGhostwriteJobStatus.Running) {
                return BatchResultDisposition.Failure
            }
            if (scheduledExecutionEpoch < 0 || scheduledExecutionEpoch == Long.MAX_VALUE) {
                return BatchResultDisposition.Failure
            }
            val ownedExecutionEpoch = scheduledExecutionEpoch + 1
            if (job.executionEpoch < ownedExecutionEpoch || job.leaseOwnerWorkID == null) {
                return BatchResultDisposition.Failure
            }
            if (job.executionEpoch > ownedExecutionEpoch || job.leaseOwnerWorkID != workId) {
                return BatchResultDisposition.Success
            }
            return if (result == BatchRunResult.Retry) {
                BatchResultDisposition.Retry
            } else {
                BatchResultDisposition.PauseCurrent
            }
        }

        internal fun failClosedResolution(failClosedStateConfirmed: Boolean): WorkResolution =
            if (failClosedStateConfirmed) WorkResolution.Success else WorkResolution.Failure

        private fun phaseLabel(phase: NovelGhostwriteJobPhase): String = when (phase) {
            NovelGhostwriteJobPhase.AwaitingPlan -> "等待章节计划"
            NovelGhostwriteJobPhase.Planning -> "生成章节计划"
            NovelGhostwriteJobPhase.PlanPrepared -> "提交章节计划"
            NovelGhostwriteJobPhase.GenerationPrepared -> "准备生成"
            NovelGhostwriteJobPhase.Generating -> "正在生成正文"
            NovelGhostwriteJobPhase.CandidateReady -> "正文待校验"
            NovelGhostwriteJobPhase.Validating -> "正在自检"
            NovelGhostwriteJobPhase.CorrectionReady -> "准备纠正"
            NovelGhostwriteJobPhase.CollectPrepared -> "准备收集"
            NovelGhostwriteJobPhase.CollectedNeedsSync -> "已收集，等待同步"
            NovelGhostwriteJobPhase.Syncing -> "同步小说状态"
            NovelGhostwriteJobPhase.ClearPlanPrepared -> "清除已完成计划"
            NovelGhostwriteJobPhase.ChapterCommitPrepared -> "提交章节检查点"
            NovelGhostwriteJobPhase.ChapterCommitted -> "章节已完成"
        }

        private fun phaseProgressLabel(job: NovelGhostwriteJobV1): String =
            "${phaseLabel(job.phase)} · 已完成 ${job.completedChapterCount}/${job.targetChapterCount}"
    }
}

internal data class NotificationSnapshot(
    val title: String,
    val content: String,
    val completed: Int,
    val target: Int,
)

internal data class NotificationRouteIdentity(
    val projectId: String,
    val runId: String,
)

internal enum class WorkResolution {
    Success,
    Failure,
}

internal enum class BatchResultDisposition {
    Success,
    Retry,
    PauseCurrent,
    Failure,
}
