package me.rerere.rikkahub.data.agent

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.NotificationActionConfig
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
            smallIcon = R.drawable.amberagent_live_status_icon
            largeIcon = R.drawable.amberagent_live_status_icon
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            contentIntent = launchIntent
            actions = if (status.canStopGeneration()) {
                listOf(stopAction(conversationId))
            } else {
                emptyList()
            }
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
            title = context.getString(R.string.notification_live_status_failed)
            content = senderName.ifBlank { context.getString(R.string.app_name) }
            subText = error::class.java.simpleName
            smallIcon = R.drawable.amberagent_live_status_icon
            largeIcon = R.drawable.amberagent_live_status_icon
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
                ToolActivityStatus.CANCELLED -> context.getString(R.string.notification_live_status_chip_cancelled)
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
                title = activeTitle(active),
                content = toolContent(active, hideSensitive),
                subText = activeMetaText(senderName, active),
                chipText = chipText,
            )
        }

        val parts = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.parts.orEmpty()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        if (lastTool != null && !lastTool.isExecuted) {
            val waiting = lastTool.approvalState is ToolApprovalState.Pending
            return AgentLiveStatus(
                kind = if (waiting) AgentLiveStatusKind.WAITING_PERMISSION else AgentLiveStatusKind.RUNNING_TOOL,
                title = if (waiting) {
                    context.getString(R.string.notification_live_status_waiting_permission)
                } else {
                    context.getString(R.string.notification_live_status_tool_title)
                },
                content = if (waiting) {
                    context.getString(R.string.notification_live_status_waiting_permission)
                } else {
                    context.getString(
                        R.string.notification_live_status_running_tool,
                        lastTool.toolName.removePrefix("mcp__").safeToolTitle()
                    )
                },
                subText = senderName.ifBlank { context.getString(R.string.app_name) },
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
                title = context.getString(R.string.notification_live_status_planning),
                content = senderName.ifBlank { context.getString(R.string.app_name) },
                subText = context.getString(R.string.app_name),
                chipText = context.getString(R.string.notification_live_status_chip_planning),
            )
        }

        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()
        return AgentLiveStatus(
            kind = if (lastText == null) AgentLiveStatusKind.PLANNING else AgentLiveStatusKind.WRITING,
            title = if (lastText == null) {
                context.getString(R.string.notification_live_status_planning)
            } else {
                context.getString(R.string.notification_live_status_writing)
            },
            content = senderName.ifBlank { context.getString(R.string.app_name) },
            subText = context.getString(R.string.app_name),
            chipText = if (lastText == null) {
                context.getString(R.string.notification_live_status_chip_planning)
            } else {
                context.getString(R.string.notification_live_status_chip_writing)
            },
        )
    }

    private fun activeTitle(activity: SandboxActivityUiState): String =
        when (activity.status) {
            ToolActivityStatus.WAITING_FOR_PERMISSION -> {
                context.getString(R.string.notification_live_status_waiting_permission)
            }

            ToolActivityStatus.FAILED -> context.getString(R.string.notification_live_status_failed)
            ToolActivityStatus.CANCELLED -> context.getString(R.string.notification_live_status_cancelled)
            else -> if (activity.isTerminalTool()) {
                context.getString(R.string.notification_live_status_terminal_title)
            } else {
                context.getString(R.string.notification_live_status_tool_title)
            }
        }

    private fun toolContent(activity: SandboxActivityUiState, hideSensitive: Boolean): String {
        if (activity.toolName == "webview_open") {
            val domain = extractDomain(activity.inputPreview)
            if (domain != null) {
                return context.getString(R.string.notification_live_status_webview, domain)
            }
        }
        if (activity.isTerminalTool()) {
            return terminalContent(activity.inputPreview, hideSensitive)
        }
        if (!hideSensitive && activity.title.isNotBlank()) {
            return context.getString(R.string.notification_live_status_running_tool, activity.title)
        }
        return context.getString(
            R.string.notification_live_status_running_tool,
            activity.title.ifBlank { activity.toolName.safeToolTitle() },
        )
    }

    private fun terminalContent(command: String, hideSensitive: Boolean): String {
        if (hideSensitive) {
            return context.getString(R.string.notification_live_status_terminal)
        }
        val normalized = command.replace(WHITESPACE_REGEX, " ").trim()
        val installTargets = installTargets(normalized)
        if (installTargets != null) {
            return if (installTargets.isEmpty()) {
                context.getString(R.string.notification_live_status_terminal_install_generic)
            } else {
                context.getString(
                    R.string.notification_live_status_terminal_install,
                    installTargets.joinToString("、")
                )
            }
        }
        return if (normalized.isBlank()) {
            context.getString(R.string.notification_live_status_terminal)
        } else {
            context.getString(R.string.notification_live_status_terminal_command, normalized.compact())
        }
    }

    private fun installTargets(command: String): List<String>? {
        val match = INSTALL_COMMAND_REGEX.find(command) ?: return null
        val tokens = match.groupValues[1]
            .substringBefore("&&")
            .substringBefore(";")
            .substringBefore("|")
            .split(WHITESPACE_REGEX)
            .map { it.trim('"', '\'', ',', ' ') }
            .filter { token ->
                token.isNotBlank() &&
                    !token.startsWith("-") &&
                    !token.contains("=") &&
                    !token.startsWith("/") &&
                    !token.endsWith(".apk")
            }
        return tokens.take(MAX_INSTALL_TARGETS).let { visible ->
            if (tokens.size > visible.size) visible + "..." else visible
        }
    }

    private fun activeMetaText(senderName: String, activity: SandboxActivityUiState): String {
        val step = if (activity.stepIndex != null && activity.stepTotal != null) {
            "${activity.stepIndex}/${activity.stepTotal}"
        } else {
            null
        }
        return listOfNotNull(
            senderName.takeIf { it.isNotBlank() },
            activity.runtime.runtimeLabel(),
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

    private fun SandboxActivityUiState.isTerminalTool(): Boolean =
        toolName.startsWith("terminal_") || toolName == "terminal_execute"

    private fun String.runtimeLabel(): String? =
        when {
            contains("alpine", ignoreCase = true) -> "Alpine"
            contains("android-shell", ignoreCase = true) -> "Android Shell"
            isBlank() -> null
            else -> safeToolTitle()
        }

    private fun String.compact(maxChars: Int = MAX_COMMAND_CHARS): String =
        if (length <= maxChars) this else take(maxChars - 3).trimEnd() + "..."

    private fun notificationId(conversationId: Uuid): Int =
        conversationId.hashCode() + LIVE_NOTIFICATION_OFFSET

    private fun stopAction(conversationId: Uuid): NotificationActionConfig {
        val intent = Intent(context, AgentNotificationActionReceiver::class.java).apply {
            action = AgentNotificationActionReceiver.ACTION_STOP_GENERATION
            putExtra(AgentNotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId.toString())
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            conversationId.hashCode() + STOP_ACTION_REQUEST_OFFSET,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationActionConfig(
            title = context.getString(R.string.notification_live_status_action_stop),
            intent = pendingIntent,
        )
    }

    private fun AgentLiveStatus.canStopGeneration(): Boolean =
        kind in setOf(
            AgentLiveStatusKind.PLANNING,
            AgentLiveStatusKind.RUNNING_TOOL,
            AgentLiveStatusKind.WAITING_PERMISSION,
            AgentLiveStatusKind.WAITING_USER,
            AgentLiveStatusKind.WRITING,
        )

    private data class LastUpdate(
        val atMillis: Long,
        val signature: String,
    )

    companion object {
        private const val LIVE_NOTIFICATION_OFFSET = 10_000
        private const val STOP_ACTION_REQUEST_OFFSET = 20_000
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L
        private const val MAX_TOOL_TITLE_CHARS = 36
        private const val MAX_COMMAND_CHARS = 44
        private const val MAX_INSTALL_TARGETS = 3
        private val URL_REGEX = Regex("https?://[^\\s\"'}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val INSTALL_COMMAND_REGEX = Regex(
            "\\b(?:apk\\s+add|pip3?\\s+install|uv\\s+pip\\s+install|npm\\s+install|pnpm\\s+add|yarn\\s+add)\\b\\s+([^;&|]+)"
        )
    }
}
