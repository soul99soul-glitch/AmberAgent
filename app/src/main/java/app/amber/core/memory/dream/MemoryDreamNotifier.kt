package app.amber.core.memory.dream

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.MEMORY_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

class MemoryDreamNotifier(
    private val context: Context,
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun notifyRunning() {
        notify(
            NotificationCompat.Builder(context, MEMORY_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.amberagent_live_status_icon)
                .setContentTitle("AmberAgent 正在做梦")
                .setContentText("正在自动整理短期和长期记忆")
                .setContentIntent(buildLaunchPendingIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        )
    }

    fun notifyApplied(plan: MemoryDreamPlan) {
        notify(
            NotificationCompat.Builder(context, MEMORY_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.amberagent_live_status_icon)
                .setContentTitle("做梦完成：已整理记忆")
                .setContentText(plan.summaryText())
                .setStyle(NotificationCompat.BigTextStyle().bigText(plan.summaryText()))
                .setContentIntent(buildLaunchPendingIntent())
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        )
    }

    fun notifyFailed(message: String) {
        notify(
            NotificationCompat.Builder(context, MEMORY_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.amberagent_live_status_icon)
                .setContentTitle("Daydream 整理失败")
                .setContentText(message.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(500)))
                .setContentIntent(buildLaunchPendingIntent())
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()
        )
    }

    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun notify(notification: android.app.Notification) {
        if (!canPostNotifications()) return
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildLaunchPendingIntent(): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_AGENT_MEMORY, true)
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val EXTRA_OPEN_AGENT_MEMORY = "openAgentMemory"
        private const val NOTIFICATION_ID = 8201
    }
}

private fun MemoryDreamPlan.summaryText(): String =
    "合并 ${mergeSuggestions.size} · 提升 ${promoteMemoryIds.size} · " +
        "归档 ${archiveMemoryIds.size} · 忽略候选 ${ignoreCandidateIds.size}"
