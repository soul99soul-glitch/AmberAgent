package app.amber.feature.runtime

import me.rerere.rikkahub.data.agent.AgentNotificationActionReceiver

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import app.amber.core.utils.NotificationActionConfig
import app.amber.core.utils.XiaomiSuperIslandConfig
import app.amber.core.utils.cancelNotification
import app.amber.core.utils.sendNotification
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
        val xiaomiSuperIsland = status.toXiaomiSuperIsland()

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(conversationId),
        ) {
            title = xiaomiSuperIsland.title
            content = xiaomiSuperIsland.content
            subText = status.subText
            smallIcon = R.drawable.amberagent_live_status_icon
            largeIcon = R.drawable.amberagent_launcher_foreground
            color = xiaomiSuperIsland.accentColor.toNotificationColor()
            priority = NotificationCompat.PRIORITY_DEFAULT
            silent = true
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            progressMax = 100
            progress = xiaomiSuperIsland.progressPercent ?: 0
            contentIntent = launchIntent
            actions = if (status.canStopGeneration()) {
                listOf(stopAction(conversationId))
            } else {
                emptyList()
            }
            requestPromotedOngoing = true
            shortCriticalText = xiaomiSuperIsland.chipText
            this.xiaomiSuperIsland = xiaomiSuperIsland
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
            autoCancel = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = launchIntent
            shortCriticalText = context.getString(R.string.notification_live_status_chip_failed)
            xiaomiSuperIsland = XiaomiSuperIslandConfig(
                title = context.getString(R.string.notification_live_status_failed),
                content = error::class.java.simpleName,
                chipText = context.getString(R.string.notification_live_status_chip_failed),
                iconRes = R.drawable.amberagent_live_status_icon,
                progressPercent = 100,
                progressText = context.getString(R.string.notification_live_status_chip_failed),
                accentColor = XIAOMI_ISLAND_ERROR_COLOR,
                trackColor = XIAOMI_ISLAND_ERROR_TRACK_COLOR,
                enableFloat = true,
                timeoutSeconds = FAILURE_ISLAND_TIMEOUT_SECONDS,
            )
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
            contains("android_shell", ignoreCase = true) -> "Android Shell"
            contains("termux", ignoreCase = true) -> "Termux"
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

    private fun AgentLiveStatus.toXiaomiSuperIsland(): XiaomiSuperIslandConfig =
        kind.xiaomiIslandVisual(content).let { visual ->
            XiaomiSuperIslandConfig(
                title = visual.title,
                content = visual.content,
                chipText = visual.summary,
                ticker = visual.summary,
                iconRes = R.drawable.amberagent_live_status_icon,
                progressPercent = kind.xiaomiIslandProgress(),
                progressText = visual.summary,
                accentColor = visual.accentColor,
                trackColor = visual.trackColor,
                enableFloat = true,
                timeoutSeconds = when (kind) {
                    AgentLiveStatusKind.WAITING_PERMISSION,
                    AgentLiveStatusKind.WAITING_USER -> WAITING_ISLAND_TIMEOUT_SECONDS
                    AgentLiveStatusKind.RUNNING_TOOL -> RUNNING_TOOL_ISLAND_TIMEOUT_SECONDS
                    else -> DEFAULT_ISLAND_TIMEOUT_SECONDS
                },
            )
        }

    private fun AgentLiveStatusKind.xiaomiIslandVisual(content: String): XiaomiIslandVisual {
        val objectText = content
            .removePrefix("正在处理：")
            .removePrefix("正在处理:")
            .trim()
            .ifBlank { "准备下一步" }
            .compact(24)
        return when (this) {
            AgentLiveStatusKind.PLANNING -> XiaomiIslandVisual(
                title = "整理思路",
                content = "拆解任务与上下文",
                summary = "构思",
                accentColor = XIAOMI_ISLAND_THINKING_COLOR,
                trackColor = XIAOMI_ISLAND_THINKING_TRACK_COLOR,
            )

            AgentLiveStatusKind.RUNNING_TOOL -> XiaomiIslandVisual(
                title = if (objectText.contains("搜索")) "正在检索" else "正在执行",
                content = objectText,
                summary = if (objectText.contains("搜索")) "检索" else "执行",
                accentColor = XIAOMI_ISLAND_ACCENT_COLOR,
                trackColor = XIAOMI_ISLAND_TRACK_COLOR,
            )

            AgentLiveStatusKind.WAITING_PERMISSION,
            AgentLiveStatusKind.WAITING_USER -> XiaomiIslandVisual(
                title = "等待确认",
                content = "授权后继续执行",
                summary = "待准",
                accentColor = XIAOMI_ISLAND_WAITING_COLOR,
                trackColor = XIAOMI_ISLAND_WAITING_TRACK_COLOR,
            )

            AgentLiveStatusKind.WRITING -> XiaomiIslandVisual(
                title = "撰写回复",
                content = "整理结果与表达",
                summary = "成稿",
                accentColor = XIAOMI_ISLAND_WRITING_COLOR,
                trackColor = XIAOMI_ISLAND_WRITING_TRACK_COLOR,
            )

            AgentLiveStatusKind.COMPLETED -> XiaomiIslandVisual(
                title = "任务完成",
                content = "结果已准备好",
                summary = "完成",
                accentColor = XIAOMI_ISLAND_WRITING_COLOR,
                trackColor = XIAOMI_ISLAND_WRITING_TRACK_COLOR,
            )

            AgentLiveStatusKind.FAILED -> XiaomiIslandVisual(
                title = "执行遇阻",
                content = objectText,
                summary = "异常",
                accentColor = XIAOMI_ISLAND_ERROR_COLOR,
                trackColor = XIAOMI_ISLAND_ERROR_TRACK_COLOR,
            )

            AgentLiveStatusKind.CANCELLED -> XiaomiIslandVisual(
                title = "已停止",
                content = "本次执行已取消",
                summary = "已停",
                accentColor = XIAOMI_ISLAND_ERROR_COLOR,
                trackColor = XIAOMI_ISLAND_ERROR_TRACK_COLOR,
            )

            AgentLiveStatusKind.IDLE -> XiaomiIslandVisual(
                title = "Amber",
                content = "等待任务",
                summary = "待命",
                accentColor = XIAOMI_ISLAND_ACCENT_COLOR,
                trackColor = XIAOMI_ISLAND_TRACK_COLOR,
            )
        }
    }

    private data class XiaomiIslandVisual(
        val title: String,
        val content: String,
        val summary: String,
        val accentColor: String,
        val trackColor: String,
    )

    private fun String.toNotificationColor(): Int =
        runCatching { Color.parseColor(this) }.getOrDefault(Color.rgb(255, 122, 26))

    private fun AgentLiveStatusKind.xiaomiIslandProgress(): Int =
        when (this) {
            AgentLiveStatusKind.IDLE -> 0
            AgentLiveStatusKind.PLANNING -> 18
            AgentLiveStatusKind.RUNNING_TOOL -> 54
            AgentLiveStatusKind.WAITING_PERMISSION,
            AgentLiveStatusKind.WAITING_USER -> 72
            AgentLiveStatusKind.WRITING -> 86
            AgentLiveStatusKind.COMPLETED -> 100
            AgentLiveStatusKind.FAILED,
            AgentLiveStatusKind.CANCELLED -> 100
        }

    private data class LastUpdate(
        val atMillis: Long,
        val signature: String,
    )

    companion object {
        private const val LIVE_NOTIFICATION_OFFSET = 10_000
        private const val STOP_ACTION_REQUEST_OFFSET = 20_000
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L
        private const val MAX_TOOL_TITLE_CHARS = 32
        private const val MAX_COMMAND_CHARS = 36
        private const val MAX_INSTALL_TARGETS = 3
        private const val DEFAULT_ISLAND_TIMEOUT_SECONDS = 20 * 60
        private const val RUNNING_TOOL_ISLAND_TIMEOUT_SECONDS = 60 * 60
        private const val WAITING_ISLAND_TIMEOUT_SECONDS = 2 * 60 * 60
        private const val FAILURE_ISLAND_TIMEOUT_SECONDS = 5 * 60
        private const val XIAOMI_ISLAND_ACCENT_COLOR = "#FF7A1A"
        private const val XIAOMI_ISLAND_TRACK_COLOR = "#33FF7A1A"
        private const val XIAOMI_ISLAND_THINKING_COLOR = "#7A5AF8"
        private const val XIAOMI_ISLAND_THINKING_TRACK_COLOR = "#337A5AF8"
        private const val XIAOMI_ISLAND_WAITING_COLOR = "#245BDB"
        private const val XIAOMI_ISLAND_WAITING_TRACK_COLOR = "#33245BDB"
        private const val XIAOMI_ISLAND_WRITING_COLOR = "#0F9D58"
        private const val XIAOMI_ISLAND_WRITING_TRACK_COLOR = "#330F9D58"
        private const val XIAOMI_ISLAND_ERROR_COLOR = "#D93025"
        private const val XIAOMI_ISLAND_ERROR_TRACK_COLOR = "#33D93025"
        private val URL_REGEX = Regex("https?://[^\\s\"'}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val INSTALL_COMMAND_REGEX = Regex(
            "\\b(?:apk\\s+add|pip3?\\s+install|uv\\s+pip\\s+install|npm\\s+install|pnpm\\s+add|yarn\\s+add)\\b\\s+([^;&|]+)"
        )
    }
}
