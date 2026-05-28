package app.amber.feature.tools

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.Tool
import app.amber.agent.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.feature.system.AmberNotificationListenerService

internal fun createNotificationListTool(deps: SystemAccessDeps): Tool = Tool(
    name = "notification_list",
    description = "List active notification summaries after Notification Access is enabled.",
    parameters = {
        obj(
            "limit" to integerProp("Maximum notifications. Defaults to 30."),
        )
    },
    execute = { input ->
        deps.trackSystemTool("notification_list", "读取通知", "notification_access", input) {
            textJson {
                put("notifications", queryNotifications(input.limit(default = 30, max = 80)))
            }
        }
    }
)

internal fun createNotificationPostTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "notification_post",
    description = "Post an AmberAgent notification for a task reminder or status summary.",
    parameters = {
        obj(
            "title" to accessStringProp("Notification title."),
            "text" to accessStringProp("Notification text."),
            required = listOf("title", "text")
        )
    },
    execute = { input ->
        deps.trackSystemTool("notification_post", "发布通知", "apps", input.safePreview()) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val notification = NotificationCompat.Builder(context, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(input.requiredString("title").take(80))
                .setContentText(input.requiredString("text").take(160))
                .setStyle(NotificationCompat.BigTextStyle().bigText(input.requiredString("text").take(800)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            notificationManager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
            textJson { put("success", true) }
        }
    }
)

private fun queryNotifications(limit: Int) = buildJsonArray {
    AmberNotificationListenerService.getActiveNotificationsSnapshot()
        .take(limit)
        .forEach { sbn ->
            val extras = sbn.notification.extras
            add(buildJsonObject {
                put("package_name", sbn.packageName)
                put("posted_at_epoch_ms", sbn.postTime)
                put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
                put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().take(240))
            })
        }
}
