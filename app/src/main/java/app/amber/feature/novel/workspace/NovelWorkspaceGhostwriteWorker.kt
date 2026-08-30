package app.amber.feature.novel.workspace

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.amber.agent.NOVEL_GHOSTWRITE_FAILURE_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.utils.appLocale
import app.amber.core.utils.sendNotification
import app.amber.ai.provider.Model
import app.amber.core.settings.findModelById
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode
import app.amber.feature.novelworkspace.NovelWorkspaceProjectSettingsStore
import app.amber.feature.novelworkspace.NovelWorkspaceProjectTitle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Lean WorkManager worker for workspace ghostwrite batches. The durable cursor is the
 * ledger (commits), so a process restart simply reloads the job and recomputes progress
 * from the manuscript. A small execution token invalidates an older Worker after resume.
 */
class NovelWorkspaceGhostwriteWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    companion object {
        const val KEY_PROJECT_ID = "novelWorkspaceProjectId"
        const val KEY_JOB_ID = "novelWorkspaceJobId"
        const val KEY_EXECUTION_ID = "novelWorkspaceExecutionId"
        // Distinct from the FGS progress id (raw job hash) so the system teardown of the
        // foreground notification on worker stop cannot cancel the failure notice too.
        private const val FAILURE_NOTIFICATION_ID_OFFSET = 30_000
        private const val TAG = "NovelWsGhostwriteWorker"
    }

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val executionId = inputData.getString(KEY_EXECUTION_ID) ?: jobId
        val repository: NovelWorkspaceProjectRepository = get()
        val directory = repository.projectDirectory(projectId)
        return try {
            runJob(directory, jobId, executionId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Ghostwrite worker failed before a normal result", error)
            runCatching {
                finishJob(
                    directory,
                    jobId,
                    executionId,
                    NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                    error.message
                        ?: applicationContext.getString(R.string.novel_ghostwrite_error_unexpected_termination),
                )
            }
            Result.failure()
        }
    }

    private suspend fun runJob(directory: java.io.File, jobId: String, executionId: String): Result {
        val job = NovelWorkspaceGhostwriteJobs.load(directory, jobId) ?: return Result.success()
        if (job.executionKey != executionId) return Result.success()
        if (job.isTerminal) return Result.success()
        if (job.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING) return Result.success()

        // Inject the DI-built coordinator (it owns the runtime) instead of building our own.
        val coordinator = get<NovelWorkspaceGhostwriteCoordinator>()
        val settingsAggregator = get<SettingsAggregator>()
        // Cold process starts can surface the dummy placeholder settings before DataStore
        // emits; wait for the real settings so the model isn't misread as "not configured".
        val settings = settingsAggregator.settingsFlow.first { !it.init }
        val model = resolveWritingModel(settings, directory)
        if (model == null) {
            finishJob(
                directory,
                job.id,
                executionId,
                NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                applicationContext.getString(R.string.novel_ghostwrite_error_model_missing),
            )
            return Result.failure()
        }
        val reviewModel = resolveReviewModel(settings, directory, model)
        // The panel's injection toggles apply to batch turns too (review finding:
        // this link was missing, making the toggles no-ops for ghostwrite).
        val injection = NovelWorkspaceProjectSettingsStore.load(directory).injection
        val store = NovelWorkspaceStore(directory)
        var ledger = NovelWorkspaceLedger.load(directory)
        val branchId = NovelWorkspaceLedger.branchId(store, ledger, job.branchSlug)
            ?: run {
                finishJob(
                    directory,
                    job.id,
                    executionId,
                    NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                    applicationContext.getString(R.string.novel_ghostwrite_error_branch_missing),
                )
                return Result.failure()
            }
        if (job.isVersionBound) {
            try {
                coordinator.reconcileReviewedChapter(directory, job.id, executionId)
                ledger = NovelWorkspaceLedger.load(directory)
            } catch (error: Exception) {
                finishJob(
                    directory,
                    job.id,
                    executionId,
                    NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                    error.message ?: applicationContext.getString(R.string.error_title_operation),
                )
                return Result.failure()
            }
        }
        val chapterPrefix = app.amber.feature.novelworkspace.NovelWorkspacePaths
            .branchPrefix(job.branchSlug) + "/chapters/"
        val dirtyChapters = NovelWorkspaceLedger.status(
            head = ledger.headOf(branchId),
            working = store.fileTree(),
            plotStale = false,
            unresolved = false,
        ).dirtyPaths.filter { it.startsWith(chapterPrefix) }
        if (dirtyChapters.isNotEmpty()) {
            finishJob(
                directory,
                job.id,
                executionId,
                NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                applicationContext.getString(
                    R.string.novel_ghostwrite_error_dirty_chapters,
                    taskLabel(job),
                    dirtyChapters.first(),
                ),
            )
            return Result.failure()
        }
        // A resumed batch may have made the plot stale with its own already-committed
        // chapters. The initial start was gated, so only block before this job has progress.
        if (NovelWorkspaceGhostwriteJobs.progress(job, store) == 0 &&
            NovelWorkspaceLedger.isPlotStale(store, ledger, job.branchSlug)
        ) {
            finishJob(
                directory,
                job.id,
                executionId,
                NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                applicationContext.getString(
                    R.string.novel_ghostwrite_error_stale_plot,
                    taskLabel(job),
                ),
            )
            return Result.failure()
        }
        if (NovelWorkspaceUnresolvedStore.entryFor(directory, job.branchSlug) != null) {
            finishJob(
                directory,
                job.id,
                executionId,
                NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                applicationContext.getString(
                    R.string.novel_ghostwrite_error_unresolved_edits,
                    taskLabel(job),
                ),
            )
            return Result.failure()
        }

        try {
            setForeground(foregroundInfo(job, 0, job.targetChapterCount))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Unable to enter foreground for ghostwrite", error)
            finishJob(
                directory,
                job.id,
                executionId,
                NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                applicationContext.getString(
                    R.string.novel_ghostwrite_error_foreground_start,
                    taskLabel(job),
                    error.message
                        ?: applicationContext.getString(R.string.novel_ghostwrite_error_system_rejected),
                ),
            )
            return Result.failure()
        }

        // Screen-off generation: a partial wake lock keeps the CPU receiving the
        // streamed tokens while the device sleeps; bounded by every model turn and
        // retry the selected batch mode can actually execute.
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = powerManager?.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "amber:novel-ghostwrite",
        )?.apply {
            setReferenceCounted(false)
            acquire(NovelWorkspaceGhostwriteCoordinator.maximumBatchRuntimeMs(job))
        }
        val result = try {
            coordinator.runBatch(
                job = job,
                projectDirectory = directory,
                branchId = branchId,
                settings = settings,
                model = model,
                reviewModel = reviewModel,
                isPaused = {
                    NovelWorkspaceGhostwriteJobs.load(directory, jobId)?.let {
                        it.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
                            it.executionKey != executionId
                    } != false
                },
                injection = injection,
                locale = applicationContext.appLocale(),
                fallbackErrorMessage = applicationContext.getString(R.string.error_title_operation),
            ) { written ->
                runCatching { setForeground(foregroundInfo(job, written, job.targetChapterCount)) }
            }
        } finally {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        }

        return when (result) {
            is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed -> {
                finishJob(directory, job.id, executionId, NovelWorkspaceGhostwriteJob.STATUS_COMPLETED, null)
                Result.success()
            }
            is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed -> {
                val recoveredCommit = if (job.isVersionBound) {
                    runCatching {
                        coordinator.reconcileReviewedChapter(directory, job.id, executionId)
                    }.getOrDefault(false)
                } else {
                    false
                }
                if (recoveredCommit) {
                    val recoveredJob = NovelWorkspaceGhostwriteJobs.load(directory, job.id)
                        ?: return Result.failure()
                    val recoveredProgress = NovelWorkspaceGhostwriteJobs.progress(
                        recoveredJob,
                        NovelWorkspaceStore(directory),
                    )
                    if (recoveredProgress >= recoveredJob.targetChapterCount) {
                        finishJob(
                            directory,
                            job.id,
                            executionId,
                            NovelWorkspaceGhostwriteJob.STATUS_COMPLETED,
                            null,
                        )
                        return Result.success()
                    }
                    return Result.retry()
                }
                val latest = NovelWorkspaceGhostwriteJobs.load(directory, jobId)
                if (latest?.status == NovelWorkspaceGhostwriteJob.STATUS_PAUSED ||
                    latest?.status == NovelWorkspaceGhostwriteJob.STATUS_CANCELLED
                ) {
                    // User paused/cancelled — keep that status, stop quietly.
                    Result.success()
                } else {
                    finishJob(directory, job.id, executionId, NovelWorkspaceGhostwriteJob.STATUS_FAILED, result.error)
                    Result.failure()
                }
            }
            is NovelWorkspaceGhostwriteCoordinator.BatchResult.Stopped -> Result.success()
        }
    }

    /** Per-project writing model wins over the global chat model; invalid ids fall through. */
    @OptIn(ExperimentalUuidApi::class)
    private fun resolveWritingModel(settings: Settings, directory: java.io.File): Model? {
        val override = NovelWorkspaceProjectSettingsStore.load(directory).writingModelId
        if (override != null) {
            runCatching { Uuid.parse(override) }.getOrNull()?.let { uuid ->
                settings.findModelById(uuid)?.let { return it }
            }
        }
        return settings.getCurrentChatModel()
    }

    /** Per-project review model; invalid/missing override follows the resolved writer. */
    @OptIn(ExperimentalUuidApi::class)
    private fun resolveReviewModel(
        settings: Settings,
        directory: java.io.File,
        writingModel: Model,
    ): Model {
        val override = NovelWorkspaceProjectSettingsStore.load(directory).reviewModelId
        if (override != null) {
            runCatching { Uuid.parse(override) }.getOrNull()?.let { uuid ->
                settings.findModelById(uuid)?.let { return it }
            }
        }
        return writingModel
    }

    private fun finishJob(
        directory: java.io.File,
        jobId: String,
        executionId: String,
        status: String,
        reason: String?,
    ) {
        val transitioned = NovelWorkspaceGhostwriteJobs.transition(
            projectDirectory = directory,
            jobId = jobId,
            expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
            newStatus = status,
            reason = reason,
            expectedExecutionId = executionId,
        )
        // Observer-only hook on the terminal failed transition. The CAS above
        // (expectedStatuses = RUNNING) lets exactly one call win per execution, so
        // paused/cancelled/completed endings never notify and a lost race never
        // double-notifies the same job.
        if (status == NovelWorkspaceGhostwriteJob.STATUS_FAILED && transitioned != null) {
            runCatching { notifyJobFailed(directory, transitioned) }
                .onFailure { Log.w(TAG, "Ghostwrite failure notification failed", it) }
        }
    }

    /** One-shot failure notification; silently no-op when notifications are disabled. */
    private fun notifyJobFailed(directory: java.io.File, job: NovelWorkspaceGhostwriteJob) {
        val context = applicationContext
        val store = NovelWorkspaceStore(directory)
        val bookTitle = runCatching { NovelWorkspaceProjectTitle.read(store) }.getOrNull()
        // The chapter the batch was attempting: Write mode leaves no commit on failure,
        // so manuscript head + 1 is the ordinal whose turn just failed; Polish mode
        // works a fixed range (startOrdinal + ledger-derived progress), except the
        // pointer-commit window where the 润色 commit already landed — the notification
        // must name the polished chapter itself, one less. Derivation lives in the
        // pure notification object so both windows stay unit-testable.
        val chapterOrdinal = runCatching {
            NovelGhostwriteFailureNotification.failedChapterOrdinal(
                polishMode = job.mode == NovelWorkspaceGhostwriteMode.Polish,
                startOrdinal = job.startOrdinal,
                ledgerProgress = NovelWorkspaceGhostwriteJobs.progress(job, store),
                newestCommittedOrdinal = NovelWorkspaceLedger.committedChapterOrdinals(
                    store,
                    NovelWorkspaceLedger.load(directory),
                    job.branchSlug,
                ).maxOrNull(),
                reason = job.reason,
            )
        }.getOrNull()
        val taskLabel = taskLabel(job)
        val copy = NovelGhostwriteFailureNotification.content(
            bookTitle = bookTitle,
            chapterOrdinal = chapterOrdinal,
            reason = notificationReason(job.reason),
            taskLabel = taskLabel,
            templates = NovelGhostwriteFailureNotification.Templates(
                fallbackTitle = context.getString(
                    R.string.novel_ghostwrite_failure_fallback_title,
                    taskLabel,
                ),
                unknownReason = context.getString(R.string.novel_ghostwrite_failure_unknown_reason),
                chapterFailure = { ordinal, reason ->
                    context.getString(R.string.novel_ghostwrite_failure_chapter, ordinal, reason)
                },
                taskFailure = { label, reason ->
                    context.getString(R.string.novel_ghostwrite_failure_task, label, reason)
                },
            ),
        )
        val launch = Intent(context, RouteActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            job.id.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // sendNotification's canShowNotification gate skips silently when
        // POST_NOTIFICATIONS is denied or notifications are disabled — the worker
        // must never prompt for the permission.
        context.sendNotification(
            channelId = NOVEL_GHOSTWRITE_FAILURE_NOTIFICATION_CHANNEL_ID,
            notificationId = job.id.hashCode() + FAILURE_NOTIFICATION_ID_OFFSET,
        ) {
            title = copy.title
            content = copy.text
            smallIcon = R.drawable.amberagent_live_status_icon
            autoCancel = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = pendingIntent
        }
    }

    /** Hide the internal polish-pointer reason id from non-Chinese notifications. */
    private fun notificationReason(reason: String?): String? {
        if (reason == null || applicationContext.appLocale().language.equals("zh", ignoreCase = true)) {
            return reason
        }
        val prefix = NovelWorkspaceGhostwriteCoordinator.REASON_POLISH_POINTER_COMMIT_FAILED
        if (!reason.startsWith(prefix)) return reason
        val detail = reason.removePrefix(prefix).removePrefix("：").trim()
        return if (detail.isEmpty()) {
            applicationContext.getString(R.string.error_title_operation)
        } else {
            applicationContext.getString(R.string.error_title_operation) + ": " + detail
        }
    }

    /** UI/task wording for the batch kind: 代笔 (write) vs 润色 (polish). */
    private fun taskLabel(job: NovelWorkspaceGhostwriteJob): String =
        applicationContext.getString(
            if (job.mode == NovelWorkspaceGhostwriteMode.Polish) {
                R.string.novel_ghostwrite_task_polish
            } else {
                R.string.novel_ghostwrite_task_write
            }
        )

    private fun progressLabel(job: NovelWorkspaceGhostwriteJob): String =
        applicationContext.getString(
            if (job.mode == NovelWorkspaceGhostwriteMode.Polish) {
                R.string.novel_ghostwrite_progress_polished
            } else {
                R.string.novel_ghostwrite_progress_written
            }
        )

    private fun foregroundInfo(job: NovelWorkspaceGhostwriteJob, written: Int, target: Int): ForegroundInfo {
        val notificationId = job.id.hashCode()
        val notification = buildNotification(job, written, target, notificationId)
        // targetSDK 37 prohibits FGS with type "none" — the 2-arg ForegroundInfo
        // crashed the whole process on every WorkManager resume (device: crash loop).
        // The manifest declares specialUse for SystemForegroundService; match it here.
        return if (android.os.Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(
        job: NovelWorkspaceGhostwriteJob,
        written: Int,
        target: Int,
        notificationId: Int,
    ): Notification {
        val launch = Intent(applicationContext, RouteActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = taskLabel(job)
        val progressText = progressLabel(job)
        return NotificationCompat.Builder(applicationContext, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(applicationContext.getString(R.string.novel_ghostwrite_notification_running_title, label))
            .setContentText(
                applicationContext.getString(
                    R.string.novel_ghostwrite_notification_running_content,
                    progressText,
                    written,
                    target,
                )
            )
            .setContentIntent(pendingIntent)
            .setProgress(target, written, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
