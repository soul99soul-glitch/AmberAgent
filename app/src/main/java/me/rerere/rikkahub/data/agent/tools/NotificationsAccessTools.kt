package me.rerere.rikkahub.data.agent.tools

import android.app.Notification
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.system.AmberNotificationListenerService

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
