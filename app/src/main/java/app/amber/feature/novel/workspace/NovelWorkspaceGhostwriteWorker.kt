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
import app.amber.core.ai.Generator
import app.amber.core.settings.getCurrentAssistant
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Lean WorkManager worker for workspace ghostwrite batches. The durable cursor is the
 * ledger (commits), so a process restart simply reloads the job and recomputes progress
 * from the manuscript — no epoch/lease machinery.
 */
class NovelWorkspaceGhostwriteWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    companion object {
        const val KEY_PROJECT_ID = "novelWorkspaceProjectId"
        const val KEY_JOB_ID = "novelWorkspaceJobId"
        private const val TAG = "NovelWsGhostwriteWorker"
    }

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val repository: NovelWorkspaceProjectRepository = get()
        val directory = repository.projectDirectory(projectId)
        val job = NovelWorkspaceGhostwriteJobs.load(directory, jobId) ?: return Result.success()
        if (job.isTerminal) return Result.success()

        // Inject the DI-built coordinator (it owns the runtime) instead of building our own.
        val coordinator = get<NovelWorkspaceGhostwriteCoordinator>()
        val settingsAggregator = get<SettingsAggregator>()
        // Cold process starts can surface the dummy placeholder settings before DataStore
        // emits; wait for the real settings so the model isn't misread as "not configured".
        val settings = settingsAggregator.settingsFlow.first { !it.init }
        val model = resolveWritingModel(settings, directory)
        if (model == null) {
            finishJob(directory, job, NovelWorkspaceGhostwriteJob.STATUS_FAILED, "未配置聊天模型")
            return Result.failure()
        }
        val assistant = settings.getCurrentAssistant()
        // The panel's injection toggles apply to batch turns too (review finding:
        // this link was missing, making the toggles no-ops for ghostwrite).
        val injection = NovelWorkspaceProjectSettingsStore.load(directory).injection
        val branchId = NovelWorkspaceLedger.load(directory).heads.keys.firstOrNull()
            ?: run {
                finishJob(directory, job, NovelWorkspaceGhostwriteJob.STATUS_FAILED, "工作区没有分支")
                return Result.failure()
            }

        try {
            setForeground(foregroundInfo(job, 0, job.targetChapterCount))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to enter foreground for ghostwrite", error)
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
                assistant = assistant,
                isPaused = { NovelWorkspaceGhostwriteJobs.load(directory, jobId)?.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING },
                injection = injection,
            ) { written ->
                runCatching { setForeground(foregroundInfo(job, written, job.targetChapterCount)) }
            }
        } finally {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        }

        return when (result) {
            is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed -> {
                finishJob(directory, job, NovelWorkspaceGhostwriteJob.STATUS_COMPLETED, null)
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
                    finishJob(directory, job, NovelWorkspaceGhostwriteJob.STATUS_FAILED, result.error)
                    Result.failure()
                }
            }
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

    private fun finishJob(directory: java.io.File, job: NovelWorkspaceGhostwriteJob, status: String, reason: String?) {
        NovelWorkspaceGhostwriteJobs.save(
            job.copy(status = status, reason = reason, updatedAt = java.time.Instant.now()),
            directory,
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
