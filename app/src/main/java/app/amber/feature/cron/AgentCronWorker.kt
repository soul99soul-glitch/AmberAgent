package app.amber.feature.cron

import android.app.PendingIntent
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.service.ChatService
import app.amber.core.utils.appLocale
import app.amber.core.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

class AgentCronWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val manualRun = inputData.getBoolean(KEY_MANUAL_RUN, false)
        val manager = get<AgentCronManager>()
        val task = manager.prepareTriggeredRun(taskId, manual = manualRun) ?: return Result.success()
        setForeground(createForegroundInfo(task))
        manager.markRunStarted(taskId)
        try {
            return runCatching {
                val conversationId = Uuid.parse(task.conversationId)
                val prompt = buildString {
                    appendLine("This is an AmberAgent mobile Cron task trigger.")
                    appendLine("Task name: ${task.title}")
                    appendLine("Cron: ${task.cronExpression} (${task.timezoneId})")
                    if (manualRun) appendLine("Trigger mode: Run immediately by user request")
                    appendLine("Respond in app locale ${applicationContext.appLocale().toLanguageTag().ifBlank { "en" }} and preserve stable protocol/schema field names.")
                    appendLine()
                    append(task.prompt)
                }
                val chatService = get<ChatService>()
                coroutineScope {
                    val completion = async {
                        withTimeout(CRON_GENERATION_TIMEOUT_MS) {
                            chatService.generationDoneFlow.first { it == conversationId }
                        }
                    }
                    chatService.sendMessage(
                        conversationId = conversationId,
                        content = listOf(UIMessagePart.Text(prompt)),
                        answer = true,
                    )
                    completion.await()
                }
                manager.markRunCompleted(taskId)
                sendCronNotification(task, success = true)
                Result.success()
            }.getOrElse { error ->
                manager.markRunFailed(taskId, error.message ?: error::class.simpleName.orEmpty())
                sendCronNotification(task, success = false, error = error)
                Result.failure()
            }
        } finally {
            // Enqueueing the next occurrence under the same unique work name while
            // this run is still active would REPLACE-cancel it, so schedule on the
            // way out. Skip when stopped: an external REPLACE (e.g. manual trigger)
            // must not be clobbered by this run's own reschedule.
            if (!isStopped) {
                withContext(NonCancellable) {
                    runCatching { manager.scheduleNextRun(taskId) }
                }
            }
        }
    }

    private fun sendCronNotification(
        task: AgentCronTask,
        success: Boolean,
        error: Throwable? = null,
    ) {
        val title = if (success) {
            applicationContext.getString(R.string.cron_notification_completed_title, task.title)
        } else {
            applicationContext.getString(R.string.cron_notification_failed_title, task.title)
        }
        val content = if (success) {
            applicationContext.getString(R.string.cron_notification_completed_content)
        } else {
            when (error) {
                is TimeoutCancellationException ->
                    applicationContext.getString(R.string.cron_notification_timeout_content)
                else -> (error?.message ?: error?.javaClass?.simpleName
                    ?: applicationContext.getString(R.string.cron_notification_unknown_error)).take(160)
            }
        }
        applicationContext.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = task.id.hashCode(),
        ) {
            this.title = title
            this.content = content
            smallIcon = R.drawable.amberagent_live_status_icon
            autoCancel = true
            useDefaults = success
            useBigTextStyle = true
            category = if (success) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR
            priority = if (success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH
            contentIntent = buildConversationPendingIntent(task.conversationId)
        }
    }

    private fun createForegroundInfo(task: AgentCronTask): ForegroundInfo {
        val notification = buildRunningNotification(task)
        val id = task.id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun buildRunningNotification(task: AgentCronTask): Notification =
        NotificationCompat.Builder(applicationContext, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(applicationContext.getString(R.string.cron_notification_running_title, task.title))
            .setContentText(applicationContext.getString(R.string.cron_notification_running_content))
            .setContentIntent(buildConversationPendingIntent(task.conversationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun buildConversationPendingIntent(conversationId: String): PendingIntent {
        val intent = Intent(applicationContext, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId)
        }
        return PendingIntent.getActivity(
            applicationContext,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_MANUAL_RUN = "manual_run"
        private const val CRON_GENERATION_TIMEOUT_MS = 9 * 60 * 1000L
    }
}
