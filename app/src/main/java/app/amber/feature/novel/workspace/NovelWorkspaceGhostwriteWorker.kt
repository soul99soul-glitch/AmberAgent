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
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.settings.getCurrentChatModel
import app.amber.ai.provider.Model
import app.amber.core.settings.findModelById
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceProjectSettingsStore
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
                    error.message ?: "代笔后台任务异常终止",
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
            finishJob(directory, job.id, executionId, NovelWorkspaceGhostwriteJob.STATUS_FAILED, "未配置聊天模型")
            return Result.failure()
        }
        // The panel's injection toggles apply to batch turns too (review finding:
        // this link was missing, making the toggles no-ops for ghostwrite).
        val injection = NovelWorkspaceProjectSettingsStore.load(directory).injection
        val store = NovelWorkspaceStore(directory)
        val ledger = NovelWorkspaceLedger.load(directory)
        val branchId = NovelWorkspaceLedger.branchId(store, ledger, job.branchSlug)
            ?: run {
                finishJob(directory, job.id, executionId, NovelWorkspaceGhostwriteJob.STATUS_FAILED, "工作区没有当前分支")
                return Result.failure()
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
                "检测到未提交的正文文件，已停止代笔以避免覆盖：${dirtyChapters.first()}",
            )
            return Result.failure()
        }
        // A resumed batch may have made the plot stale with its own already-committed
        // chapters. The initial start was gated, so only block before this job has progress.
        if (NovelWorkspaceGhostwriteJobs.progress(job, store) == 0 &&
            NovelWorkspaceLedger.isPlotStale(ledger, job.branchSlug)
        ) {
            finishJob(
                directory,
                job.id,
                executionId,
                NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                "剧情落后于正文。请切到“讨论”，发送“根据最新正文同步 plot/current.md”，并批准剧情修改后再代笔",
            )
            return Result.failure()
        }
        if (NovelWorkspaceUnresolvedStore.entryFor(directory, job.branchSlug) != null) {
            finishJob(
                directory,
                job.id,
                executionId,
                NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                "存在未解决的中间章修改，请先处理再代笔",
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
                "无法启动前台代笔：${error.message ?: "系统拒绝后台执行"}",
            )
            return Result.failure()
        }

        // Screen-off generation: a partial wake lock keeps the CPU receiving the
        // streamed tokens while the device sleeps; bounded so a wedged batch can
        // never hold it forever (each chapter ≤ turn timeout + retry backoff).
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = powerManager?.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "amber:novel-ghostwrite",
        )?.apply {
            setReferenceCounted(false)
            val boundMs = (job.targetChapterCount + 1L) * (10 * 60_000L)
            acquire(boundMs)
        }
        val result = try {
            coordinator.runBatch(
                job = job,
                projectDirectory = directory,
                branchId = branchId,
                settings = settings,
                model = model,
                isPaused = {
                    NovelWorkspaceGhostwriteJobs.load(directory, jobId)?.let {
                        it.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
                            it.executionKey != executionId
                    } != false
                },
                injection = injection,
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

    private fun finishJob(
        directory: java.io.File,
        jobId: String,
        executionId: String,
        status: String,
        reason: String?,
    ) {
        NovelWorkspaceGhostwriteJobs.transition(
            projectDirectory = directory,
            jobId = jobId,
            expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
            newStatus = status,
            reason = reason,
            expectedExecutionId = executionId,
        )
    }

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
        return NotificationCompat.Builder(applicationContext, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle("代笔进行中")
            .setContentText("已写 $written / $target 章")
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
