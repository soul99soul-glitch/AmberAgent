package me.rerere.rikkahub.data.agent

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

enum class AgentLiveStatusKind {
    IDLE,
    PLANNING,
    RUNNING_TOOL,
    WAITING_PERMISSION,
    WAITING_USER,
    WRITING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class AgentLiveStatus(
    val kind: AgentLiveStatusKind,
    val title: String,
    val content: String,
    val subText: String,
    val chipText: String,
)

class AgentLiveStatusNotifier(
    private val context: Context,
) {
    private val lastUpdates = ConcurrentHashMap<Uuid, LastUpdate>()

    fun notifyRunning(
        conversationId: Uuid,
        senderName: String,
        messages: List<UIMessage>,
        activity: SandboxActivityUiState?,
        hideSensitive: Boolean,
        launchIntent: PendingIntent?,
    ) {
        val status = buildStatus(senderName, messages, activity, hideSensitive)
        if (status.kind == AgentLiveStatusKind.IDLE) return

        val now = System.currentTimeMillis()
        val signature = "${status.kind}:${status.content}:${status.subText}:${status.chipText}"
        val last = lastUpdates[conversationId]
        if (last != null && last.signature == signature && now - last.atMillis < MIN_UPDATE_INTERVAL_MS) {
            return
        }
        lastUpdates[conversationId] = LastUpdate(now, signature)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(conversationId),
        ) {
            title = status.title
            content = status.content
            subText = status.subText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            contentIntent = launchIntent
            requestPromotedOngoing = true
            shortCriticalText = status.chipText
        }
    }

    fun notifyFailure(
        conversationId: Uuid,
        senderName: String,
        error: Throwable,
        launchIntent: PendingIntent?,
    ) {
        lastUpdates.remove(conversationId)
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(conversationId),
        ) {
            title = senderName
            content = context.getString(R.string.notification_live_status_failed)
            subText = error::class.java.simpleName
            autoCancel = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = launchIntent
            shortCriticalText = context.getString(R.string.notification_live_status_chip_failed)
            useDefaults = true
        }
    }

    fun cancel(conversationId: Uuid) {
        lastUpdates.remove(conversationId)
        context.cancelNotification(notificationId(conversationId))
    }

    private fun buildStatus(
        senderName: String,
        messages: List<UIMessage>,
        activity: SandboxActivityUiState?,
        hideSensitive: Boolean,
    ): AgentLiveStatus {
        activity?.let { active ->
            val chipText = when (active.status) {
                ToolActivityStatus.RUNNING -> context.getString(R.string.notification_live_status_chip_tool)
                ToolActivityStatus.WAITING_FOR_PERMISSION -> context.getString(R.string.notification_live_status_chip_waiting)
                ToolActivityStatus.SUCCEEDED -> context.getString(R.string.notification_live_status_chip_done)
                ToolActivityStatus.FAILED -> context.getString(R.string.notification_live_status_chip_failed)
                ToolActivityStatus.CANCELLED -> context.getString(R.string.notification_live_status_chip_failed)
            }
            val kind = when (active.status) {
                ToolActivityStatus.RUNNING -> AgentLiveStatusKind.RUNNING_TOOL
                ToolActivityStatus.WAITING_FOR_PERMISSION -> AgentLiveStatusKind.WAITING_PERMISSION
                ToolActivityStatus.SUCCEEDED -> AgentLiveStatusKind.RUNNING_TOOL
                ToolActivityStatus.FAILED -> AgentLiveStatusKind.FAILED
                ToolActivityStatus.CANCELLED -> AgentLiveStatusKind.CANCELLED
            }
            return AgentLiveStatus(
                kind = kind,
                title = senderName,
                content = toolContent(active, hideSensitive),
                subText = activeStepText(active),
                chipText = chipText,
            )
        }

        val parts = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.parts.orEmpty()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        if (lastTool != null && !lastTool.isExecuted) {
            val waiting = lastTool.approvalState is ToolApprovalState.Pending
            return AgentLiveStatus(
                kind = if (waiting) AgentLiveStatusKind.WAITING_PERMISSION else AgentLiveStatusKind.RUNNING_TOOL,
                title = senderName,
                content = if (waiting) {
                    context.getString(R.string.notification_live_status_waiting_permission)
                } else {
                    context.getString(
                        R.string.notification_live_status_running_tool,
                        lastTool.toolName.removePrefix("mcp__").safeToolTitle()
                    )
                },
                subText = context.getString(R.string.app_name),
                chipText = if (waiting) {
                    context.getString(R.string.notification_live_status_chip_waiting)
                } else {
                    context.getString(R.string.notification_live_status_chip_tool)
                },
            )
        }

        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        if (lastReasoning != null && lastReasoning.finishedAt == null) {
            return AgentLiveStatus(
                kind = AgentLiveStatusKind.PLANNING,
                title = senderName,
                content = context.getString(R.string.notification_live_status_planning),
                subText = context.getString(R.string.app_name),
                chipText = context.getString(R.string.notification_live_status_chip_planning),
            )
        }

        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()
        return AgentLiveStatus(
            kind = if (lastText == null) AgentLiveStatusKind.PLANNING else AgentLiveStatusKind.WRITING,
            title = senderName,
            content = if (lastText == null) {
                context.getString(R.string.notification_live_status_planning)
            } else {
                context.getString(R.string.notification_live_status_writing)
            },
            subText = context.getString(R.string.app_name),
            chipText = if (lastText == null) {
                context.getString(R.string.notification_live_status_chip_planning)
            } else {
                context.getString(R.string.notification_live_status_chip_writing)
            },
        )
    }

    private fun toolContent(activity: SandboxActivityUiState, hideSensitive: Boolean): String {
        if (activity.toolName == "webview_open") {
            val domain = extractDomain(activity.inputPreview)
            if (domain != null) {
                return context.getString(R.string.notification_live_status_webview, domain)
            }
        }
        if (activity.toolName.startsWith("terminal_") || activity.toolName == "terminal_execute") {
            return context.getString(R.string.notification_live_status_terminal)
        }
        if (!hideSensitive && activity.title.isNotBlank()) {
            return context.getString(R.string.notification_live_status_running_tool, activity.title)
        }
        return context.getString(
            R.string.notification_live_status_running_tool,
            activity.title.ifBlank { activity.toolName.safeToolTitle() },
        )
    }

    private fun activeStepText(activity: SandboxActivityUiState): String {
        val step = if (activity.stepIndex != null && activity.stepTotal != null) {
            "${activity.stepIndex}/${activity.stepTotal}"
        } else {
            null
        }
        return listOfNotNull(
            activity.runtime.takeIf { it.isNotBlank() },
            activity.workspace.takeIf { it.isNotBlank() },
            step,
        ).joinToString(" · ").ifBlank { context.getString(R.string.app_name) }
    }

    private fun extractDomain(inputPreview: String): String? {
        val url = URL_REGEX.find(inputPreview)?.value ?: return null
        return url.removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .takeIf { it.isNotBlank() }
    }

    private fun String.safeToolTitle(): String =
        replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .take(MAX_TOOL_TITLE_CHARS)

    private fun notificationId(conversationId: Uuid): Int =
        conversationId.hashCode() + LIVE_NOTIFICATION_OFFSET

    private data class LastUpdate(
        val atMillis: Long,
        val signature: String,
    )

    companion object {
        private const val LIVE_NOTIFICATION_OFFSET = 10_000
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L
        private const val MAX_TOOL_TITLE_CHARS = 36
        private val URL_REGEX = Regex("https?://[^\\s\"'}]+")
    }
}
