package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.DEEP_READ_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.sendNotification

class DeepReadNotifier(
    private val context: Context,
) {
    fun buildRunningNotification(
        topicId: String,
        title: String,
        sourceUrl: String?,
    ): Notification =
        NotificationCompat.Builder(context, DEEP_READ_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle("深度阅读正在生成")
            .setContentText(title.take(120))
            .setContentIntent(buildOpenPendingIntent(topicId, title, sourceUrl, fromHistory = false))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun notifyCompleted(
        topicId: String,
        title: String,
        sourceUrl: String?,
        complete: Boolean,
    ) {
        context.sendNotification(
            channelId = DEEP_READ_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(topicId),
        ) {
            this.title = if (complete) "深度阅读已生成" else "深度阅读已部分生成"
            content = title.take(120)
            smallIcon = R.drawable.amberagent_live_status_icon
            autoCancel = true
            useDefaults = complete
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_STATUS
            priority = NotificationCompat.PRIORITY_DEFAULT
            contentIntent = buildOpenPendingIntent(topicId, title, sourceUrl, fromHistory = true)
        }
    }

    fun notifyFailed(
        topicId: String,
        title: String,
        sourceUrl: String?,
        reason: String,
    ) {
        context.sendNotification(
            channelId = DEEP_READ_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(topicId),
        ) {
            this.title = "深度阅读生成失败"
            content = reason.ifBlank { title }.take(160)
            smallIcon = R.drawable.amberagent_live_status_icon
            autoCancel = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_ERROR
            priority = NotificationCompat.PRIORITY_DEFAULT
            contentIntent = buildOpenPendingIntent(topicId, title, sourceUrl, fromHistory = true)
        }
    }

    private fun buildOpenPendingIntent(
        topicId: String,
        title: String,
        sourceUrl: String?,
        fromHistory: Boolean,
    ): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_DEEP_READ, true)
            putExtra(EXTRA_TOPIC_ID, topicId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_SOURCE_URL, sourceUrl.orEmpty())
            putExtra(EXTRA_FROM_HISTORY, fromHistory)
        }
        return PendingIntent.getActivity(
            context,
            notificationId(topicId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val EXTRA_OPEN_DEEP_READ = "openDeepRead"
        const val EXTRA_TOPIC_ID = "deepReadTopicId"
        const val EXTRA_TITLE = "deepReadTitle"
        const val EXTRA_SOURCE_URL = "deepReadSourceUrl"
        const val EXTRA_FROM_HISTORY = "deepReadFromHistory"

        fun notificationId(topicId: String): Int = 9100 + (topicId.hashCode() and 0x0fffffff)
    }
}
