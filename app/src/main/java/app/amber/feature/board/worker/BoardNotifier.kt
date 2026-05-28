package app.amber.feature.board.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.BOARD_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

/**
 * Surfaces board-update outcomes via system notifications. Low-priority by default -
 * the board is a pull surface, not a push surface - so a successful refresh shows a
 * quiet informational notification that users can disable per-channel.
 *
 * Failures use the same channel but carry a hint text so users can see why updates
 * stopped landing without opening logcat.
 */
class BoardNotifier(
    private val context: Context,
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun notifySuccess(itemCount: Int, summary: String) {
        val text = if (summary.isNotBlank()) summary else "已整理 $itemCount 条内容"
        notify(
            NotificationCompat.Builder(context, BOARD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.amberagent_live_status_icon)
                .setContentTitle("今日看板已更新")
                .setContentText(text.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(500)))
                .setContentIntent(buildLaunchPendingIntent())
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build(),
        )
    }

    fun notifyFailure(reason: String) {
        notify(
            NotificationCompat.Builder(context, BOARD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.amberagent_live_status_icon)
                .setContentTitle("今日看板更新失败")
                .setContentText(reason.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason.take(500)))
                .setContentIntent(buildLaunchPendingIntent())
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build(),
        )
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
            putExtra(EXTRA_OPEN_TODAY_BOARD, true)
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val EXTRA_OPEN_TODAY_BOARD = "openTodayBoard"
        private const val NOTIFICATION_ID = 8301
    }
}
