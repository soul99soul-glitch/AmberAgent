package me.rerere.rikkahub.data.agent.office.radar

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.FEISHU_DOC_CHANGE_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

class FeishuChangeNotifier(
    private val context: Context,
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun notifyChange(docTitle: String, changeCount: Int, summary: String, changeId: String) {
        notify(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.amberagent_live_status_icon)
                .setContentTitle("飞书文档有重要变更")
                .setContentText("「$docTitle」+${changeCount}字 — ${summary.take(80)}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(500)))
                .setContentIntent(buildLaunchPendingIntent(changeId))
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build(),
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

    private fun buildLaunchPendingIntent(changeId: String): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_FEISHU_CHANGE, changeId)
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = FEISHU_DOC_CHANGE_CHANNEL_ID
        const val EXTRA_OPEN_FEISHU_CHANGE = "openFeishuChange"
        private const val NOTIFICATION_ID = 8202
    }
}
