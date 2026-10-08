package app.amber.feature.runtime

import app.amber.agent.feature.runtime.AgentNotificationActionReceiver
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.HttpException
import app.amber.agent.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.core.ai.mcp.McpToolNamespace
import app.amber.core.utils.NotificationActionConfig
import app.amber.core.utils.NotificationConfig
import app.amber.core.utils.XiaomiSuperIslandConfig
import app.amber.core.utils.cancelNotification
import app.amber.core.utils.sendNotification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
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
}

/**
 * 一张实时卡片的全部文案。[publicTitle]/[publicContent] 是锁屏公开版本；
 * [chipText] 为 null 时状态栏胶囊显示已运行计时器。
 */
data class AgentLiveStatus(
    val kind: AgentLiveStatusKind,
    val title: String,
    val content: String,
    val publicTitle: String,
    val publicContent: String,
    val chipText: String?,
    val stepsDone: Int = 0,
    val replyChoices: List<String> = emptyList(),
)

/** 失败原因类别：给用户看的是原因，不是异常类名。 */
enum class LiveFailureReason { NETWORK, QUOTA, SERVICE, OTHER }

/**
 * Android 16 实时更新 + HyperOS 超级岛。
 *
 * 版式约定（与 iOS 灵动岛同一套状态和措辞）：
 * - 颜色只有三种语义：待确认/待回复 = 琥珀金，完成 = 绿，失败 = 红；运行中不传强调色，跟随系统。
 * - 进度条是 ProgressStyle 三段：规划 / 执行 / 成稿。做完的阶段填满，当前阶段停在段首，
 *   不再编造百分比。运行期没有可信的步骤总数，所以按阶段分段，已完成步数写进正文。
 * - 锁屏走 setPublicVersion，命令原文和问题原文只在解锁后出现。
 * - 完成/失败卡片 setTimeoutAfter 到时自动收起，胶囊和超级岛一起消失。
 */
class AgentLiveStatusNotifier(
    private val context: Context,
    private val approvalTokens: NotificationApprovalTokenRegistry,
) {
    private val sessions = ConcurrentHashMap<Uuid, Session>()

    fun notifyRunning(
        conversationId: Uuid,
        senderName: String,
        messages: List<UIMessage>,
        activity: SandboxActivityUiState?,
        hideSensitive: Boolean,
        launchIntent: () -> PendingIntent?,
        runId: String? = null,
    ) {
        val status = buildStatus(messages, activity, hideSensitive)
        if (status.kind == AgentLiveStatusKind.IDLE) return

        val now = System.currentTimeMillis()
        val session = sessions.getOrPut(conversationId) { Session(startedAt = now) }
        val signature = "${status.kind}:${status.title}:${status.content}:${status.stepsDone}"
        if (session.signature == signature && now - session.updatedAt < MIN_UPDATE_INTERVAL_MS) return
        session.signature = signature
        session.updatedAt = now
        session.phase = status.kind.phaseIndex()
        val waiting = status.kind.isWaiting()
        session.waitingSince = if (waiting) session.waitingSince ?: now else null

        val tone = status.kind.tone()
        val progress = LiveProgress(phase = session.phase, tone = tone)
        val sender = senderName.ifBlank { context.getString(R.string.app_name) }
        val whenMillis = session.waitingSince ?: session.startedAt

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(conversationId),
        ) {
            liveCard(
                status = status,
                subText = sender,
                tone = tone,
                progress = progress,
                launchIntent = launchIntent(),
            )
            this.whenMillis = whenMillis
            usesChronometer = true
            actions = buildActions(conversationId, runId, status, messages)
            xiaomiSuperIsland = islandConfig(status, tone, progress)
            publicVersion = publicCard(status, sender, tone, progress).apply {
                this.whenMillis = whenMillis
                usesChronometer = true
            }
        }
    }

    /**
     * 任务完成：把同一张实时卡片切成绿色“完成”，一段时间后自动收起。
     * [alert] 为 true 时走完成渠道（响铃/横幅），用于应用在后台时替代旧的完成通知。
     */
    fun notifyCompleted(
        conversationId: Uuid,
        senderName: String,
        messages: List<UIMessage>,
        hideSensitive: Boolean,
        alert: Boolean,
        launchIntent: PendingIntent?,
    ) {
        val session = sessions.remove(conversationId)
        approvalTokens.revokeForConversation(conversationId.toString())
        val parts = lastAssistantParts(messages)
        val stepsDone = parts.filterIsInstance<UIMessagePart.Tool>().count { it.isExecuted }
        val summary = parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString(" ") { it.text }
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .compact(MAX_SUMMARY_CHARS)
        val status = AgentLiveStatus(
            kind = AgentLiveStatusKind.COMPLETED,
            title = if (stepsDone > 0) {
                context.getString(R.string.live_update_completed_title_steps, stepsDone)
            } else {
                context.getString(R.string.live_update_completed_title)
            },
            content = summary.takeIf { it.isNotBlank() && !hideSensitive }
                ?: context.getString(R.string.live_update_completed_content),
            publicTitle = context.getString(R.string.live_update_completed_title),
            publicContent = context.getString(R.string.live_update_completed_public),
            chipText = context.getString(R.string.live_update_chip_done),
            stepsDone = stepsDone,
        )
        val progress = LiveProgress(phase = PHASE_COUNT, tone = LiveTone.SUCCESS)
        val elapsed = session?.let { context.getString(R.string.live_update_elapsed, formatElapsed(it.startedAt)) }
        val sender = senderName.ifBlank { context.getString(R.string.app_name) }
        context.sendNotification(
            channelId = if (alert) CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID else CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(conversationId),
        ) {
            liveCard(
                status = status,
                subText = listOfNotNull(sender, elapsed).joinToString(" · "),
                tone = LiveTone.SUCCESS,
                progress = progress,
                launchIntent = launchIntent,
            )
            silent = !alert
            useDefaults = alert
            // 同一 ID 更新默认只提醒一次；完成要在后台真正响起来。
            onlyAlertOnce = !alert
            autoCancel = true
            timeoutAfterMillis = COMPLETED_TIMEOUT_MS
            xiaomiSuperIsland = islandConfig(status, LiveTone.SUCCESS, progress, COMPLETED_TIMEOUT_MS)
            publicVersion = publicCard(status, sender, LiveTone.SUCCESS, progress)
        }
    }

    fun notifyFailure(
        conversationId: Uuid,
        senderName: String,
        error: Throwable,
        launchIntent: PendingIntent?,
        runId: String? = null,
    ) {
        val session = sessions.remove(conversationId)
        // P8-10: a failed run must not be approvable from a stale notification.
        approvalTokens.revokeForConversation(conversationId.toString())
        val status = AgentLiveStatus(
            kind = AgentLiveStatusKind.FAILED,
            title = context.getString(liveFailureReason(error).titleRes()),
            content = context.getString(R.string.live_update_failed_content),
            publicTitle = context.getString(liveFailureReason(error).titleRes()),
            publicContent = context.getString(R.string.live_update_failed_content),
            chipText = context.getString(R.string.live_update_chip_failed),
        )
        // 当前阶段标红，停在中断的位置。
        val progress = LiveProgress(phase = session?.phase ?: 0, tone = LiveTone.FAILURE)
        val stoppedAfter = session?.let {
            context.getString(R.string.live_update_failed_after, formatElapsed(it.startedAt))
        }
        val sender = senderName.ifBlank { context.getString(R.string.app_name) }
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(conversationId),
        ) {
            liveCard(
                status = status,
                subText = listOfNotNull(sender, stoppedAfter).joinToString(" · "),
                tone = LiveTone.FAILURE,
                progress = progress,
                launchIntent = launchIntent,
            )
            silent = false
            onlyAlertOnce = false
            useDefaults = true
            autoCancel = true
            timeoutAfterMillis = FAILURE_TIMEOUT_MS
            actions = listOfNotNull(
                launchIntent?.let {
                    NotificationActionConfig(title = context.getString(R.string.live_update_action_details), intent = it)
                },
                retryAction(conversationId, runId),
            )
            xiaomiSuperIsland = islandConfig(status, LiveTone.FAILURE, progress, FAILURE_TIMEOUT_MS)
            publicVersion = publicCard(status, sender, LiveTone.FAILURE, progress)
        }
    }

    fun cancel(conversationId: Uuid) {
        sessions.remove(conversationId)
        // P8-10: dismissing the notification invalidates its approval tokens.
        approvalTokens.revokeForConversation(conversationId.toString())
        context.cancelNotification(notificationId(conversationId))
    }

    // ---- 卡片 ----

    private fun NotificationConfig.liveCard(
        status: AgentLiveStatus,
        subText: String,
        tone: LiveTone,
        progress: LiveProgress,
        launchIntent: PendingIntent?,
    ) {
        title = status.title
        content = status.content
        this.subText = subText
        smallIcon = R.drawable.amberagent_live_status_icon
        largeIcon = R.mipmap.ic_launcher
        color = tone.color()
        priority = NotificationCompat.PRIORITY_DEFAULT
        silent = true
        ongoing = true
        onlyAlertOnce = true
        category = NotificationCompat.CATEGORY_PROGRESS
        contentIntent = launchIntent
        style = progressStyle(progress)
        requestPromotedOngoing = true
        shortCriticalText = status.chipText
    }

    private fun publicCard(
        status: AgentLiveStatus,
        sender: String,
        tone: LiveTone,
        progress: LiveProgress,
    ): NotificationConfig = NotificationConfig().apply {
        title = status.publicTitle
        content = status.publicContent
        subText = sender
        smallIcon = R.drawable.amberagent_live_status_icon
        color = tone.color()
        silent = true
        ongoing = true
        category = NotificationCompat.CATEGORY_PROGRESS
        visibility = NotificationCompat.VISIBILITY_PUBLIC
        style = progressStyle(progress)
        requestPromotedOngoing = true
        shortCriticalText = status.chipText
    }

    private fun progressStyle(progress: LiveProgress): NotificationCompat.ProgressStyle {
        val accent = progress.tone.color()
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.amberagent_live_tracker))
        repeat(PHASE_COUNT) { index ->
            val segment = NotificationCompat.ProgressStyle.Segment(PHASE_LENGTH)
            // 运行中不传强调色，跟随系统；其余状态只给当前段（或全部段）上色。
            if (accent != null && (progress.tone == LiveTone.SUCCESS || index == progress.phase)) {
                segment.setColor(accent)
            }
            style.addProgressSegment(segment)
        }
        return style.setProgress(progress.filledLength())
    }

    private fun islandConfig(
        status: AgentLiveStatus,
        tone: LiveTone,
        progress: LiveProgress,
        timeoutMillis: Long? = null,
    ): XiaomiSuperIslandConfig {
        val accent = tone.hex() ?: ISLAND_NEUTRAL_COLOR
        val summary = status.chipText ?: context.getString(R.string.live_update_island_running)
        return XiaomiSuperIslandConfig(
            title = status.publicTitle,
            content = status.publicContent,
            chipText = summary,
            ticker = summary,
            iconRes = R.drawable.amberagent_live_status_icon,
            progressPercent = progress.filledLength() * 100 / (PHASE_COUNT * PHASE_LENGTH),
            progressText = summary,
            accentColor = accent,
            trackColor = "#33" + accent.removePrefix("#"),
            enableFloat = status.kind.isWaiting() || tone != LiveTone.NEUTRAL,
            timeoutSeconds = timeoutMillis?.let { (it / 1000).toInt() } ?: when {
                status.kind.isWaiting() -> WAITING_ISLAND_TIMEOUT_SECONDS
                else -> RUNNING_ISLAND_TIMEOUT_SECONDS
            },
        )
    }

    // ---- 状态归纳 ----

    private fun buildStatus(
        messages: List<UIMessage>,
        activity: SandboxActivityUiState?,
        hideSensitive: Boolean,
    ): AgentLiveStatus {
        val parts = lastAssistantParts(messages)
        val stepsDone = parts.filterIsInstance<UIMessagePart.Tool>().count { it.isExecuted }
        val progressText = if (stepsDone > 0) {
            context.getString(R.string.live_update_steps_done, stepsDone)
        } else {
            context.getString(R.string.live_update_first_step)
        }

        pendingToolForActions(messages)?.let { pending ->
            return if (pending.toolName == ASK_USER_TOOL) {
                askUserStatus(pending, hideSensitive, stepsDone)
            } else {
                approvalStatus(pending, hideSensitive, stepsDone)
            }
        }

        // 已成功结束的工具不再代表“当前在做什么”，交给下面按消息判断（多半在写回复）。
        activity?.takeIf {
            it.status != ToolActivityStatus.WAITING_FOR_PERMISSION && it.status != ToolActivityStatus.SUCCEEDED
        }?.let { active ->
            val stepFailed = active.status in STEP_FAILED_STATUSES
            return runningStatus(
                kind = AgentLiveStatusKind.RUNNING_TOOL,
                title = toolTitle(active, hideSensitive),
                publicTitle = toolTitle(active, hideSensitive = true),
                content = if (stepFailed) context.getString(R.string.live_update_step_failed) else progressText,
                stepsDone = stepsDone,
            )
        }

        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        if (lastTool != null && !lastTool.isExecuted) {
            return runningStatus(
                kind = AgentLiveStatusKind.RUNNING_TOOL,
                title = context.getString(
                    R.string.notification_live_status_running_tool,
                    McpToolNamespace.displayName(lastTool.toolName).safeToolTitle(),
                ),
                content = progressText,
                stepsDone = stepsDone,
            )
        }

        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()
        return if (lastText == null || (lastReasoning != null && lastReasoning.finishedAt == null)) {
            runningStatus(
                kind = AgentLiveStatusKind.PLANNING,
                title = context.getString(R.string.live_update_planning_title),
                content = if (stepsDone > 0) progressText else context.getString(R.string.live_update_planning_content),
                stepsDone = stepsDone,
            )
        } else {
            runningStatus(
                kind = AgentLiveStatusKind.WRITING,
                title = context.getString(R.string.live_update_writing_title),
                content = progressText,
                stepsDone = stepsDone,
            )
        }
    }

    private fun runningStatus(
        kind: AgentLiveStatusKind,
        title: String,
        content: String,
        stepsDone: Int,
        publicTitle: String = title,
    ) = AgentLiveStatus(
        kind = kind,
        title = title,
        content = content,
        publicTitle = publicTitle,
        publicContent = context.getString(R.string.live_update_phase_public, kind.phaseIndex() + 1, PHASE_COUNT),
        chipText = null,
        stepsDone = stepsDone,
    )

    private fun approvalStatus(tool: UIMessagePart.Tool, hideSensitive: Boolean, stepsDone: Int): AgentLiveStatus {
        val isTerminal = tool.toolName.isTerminalTool()
        val action = if (isTerminal) {
            context.getString(R.string.live_update_action_terminal)
        } else {
            context.getString(
                R.string.live_update_action_tool,
                McpToolNamespace.displayName(tool.toolName).safeToolTitle(),
            )
        }
        val command = tool.inputField("command")?.replace(WHITESPACE_REGEX, " ")?.trim()
        val content = when {
            hideSensitive && isTerminal -> context.getString(R.string.live_update_command_hidden)
            isTerminal && !command.isNullOrBlank() -> command.compact(MAX_COMMAND_CHARS)
            else -> context.getString(R.string.live_update_confirm_content)
        }
        return AgentLiveStatus(
            kind = AgentLiveStatusKind.WAITING_PERMISSION,
            title = context.getString(R.string.live_update_confirm_title, action),
            content = content,
            publicTitle = context.getString(R.string.live_update_confirm_public_title),
            publicContent = context.getString(R.string.live_update_confirm_public_content, action),
            chipText = context.getString(R.string.live_update_chip_waiting),
            stepsDone = stepsDone,
        )
    }

    private fun askUserStatus(tool: UIMessagePart.Tool, hideSensitive: Boolean, stepsDone: Int): AgentLiveStatus {
        val questions = runCatching {
            Json.parseToJsonElement(tool.input).jsonObject["questions"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
        }.getOrDefault(emptyList())
        val first = questions.firstOrNull()
        val question = first?.stringField("question")?.replace(WHITESPACE_REGEX, " ")?.trim()
        // 只有一个问题时，把它的候选项做成快捷回复；多问题要进应用里逐个答。
        val choices = if (questions.size == 1) {
            runCatching { first?.get("options")?.jsonArray?.map { it.jsonPrimitive.content } }
                .getOrNull().orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(MAX_REPLY_CHOICES)
        } else {
            emptyList()
        }
        return AgentLiveStatus(
            kind = AgentLiveStatusKind.WAITING_USER,
            title = question?.takeIf { it.isNotBlank() && !hideSensitive }?.compact(MAX_QUESTION_CHARS)
                ?: context.getString(R.string.live_update_reply_hidden_title),
            content = context.getString(R.string.live_update_reply_content),
            publicTitle = context.getString(R.string.live_update_reply_public_title),
            publicContent = context.getString(R.string.live_update_reply_public_content),
            chipText = context.getString(R.string.live_update_chip_reply),
            stepsDone = stepsDone,
            replyChoices = if (hideSensitive) emptyList() else choices,
        )
    }

    private fun toolTitle(activity: SandboxActivityUiState, hideSensitive: Boolean): String {
        if (activity.toolName == "webview_open") {
            extractDomain(activity.inputPreview)?.let {
                return context.getString(R.string.notification_live_status_webview, it)
            }
        }
        if (activity.toolName.isTerminalTool()) {
            return terminalTitle(activity.inputPreview, hideSensitive)
        }
        return context.getString(
            R.string.notification_live_status_running_tool,
            activity.title.ifBlank { activity.toolName.safeToolTitle() }.compact(MAX_TOOL_TITLE_CHARS),
        )
    }

    private fun terminalTitle(command: String, hideSensitive: Boolean): String {
        if (hideSensitive) return context.getString(R.string.notification_live_status_terminal_title)
        val normalized = command.replace(WHITESPACE_REGEX, " ").trim()
        val installTargets = installTargets(normalized)
        if (installTargets != null) {
            return if (installTargets.isEmpty()) {
                context.getString(R.string.notification_live_status_terminal_install_generic)
            } else {
                context.getString(R.string.notification_live_status_terminal_install, installTargets.joinToString("、"))
            }
        }
        return if (normalized.isBlank()) {
            context.getString(R.string.notification_live_status_terminal_title)
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

    private fun extractDomain(inputPreview: String): String? {
        val url = URL_REGEX.find(inputPreview)?.value ?: return null
        return url.removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .takeIf { it.isNotBlank() }
    }

    private fun lastAssistantParts(messages: List<UIMessage>): List<UIMessagePart> =
        messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.parts.orEmpty()

    private fun UIMessagePart.Tool.inputField(name: String): String? =
        runCatching { Json.parseToJsonElement(input).jsonObject.stringField(name) }.getOrNull()

    private fun JsonObject.stringField(name: String): String? =
        runCatching { get(name)?.jsonPrimitive?.content }.getOrNull()

    private fun String.safeToolTitle(): String =
        replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .take(MAX_TOOL_TITLE_CHARS)

    private fun String.isTerminalTool(): Boolean = startsWith("terminal_")

    private fun String.compact(maxChars: Int = MAX_COMMAND_CHARS): String =
        if (length <= maxChars) this else take(maxChars - 1).trimEnd() + "…"

    private fun formatElapsed(startedAt: Long): String {
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%d:%02d".format(minutes, secs)
        }
    }

    private fun notificationId(conversationId: Uuid): Int =
        conversationId.hashCode() + LIVE_NOTIFICATION_OFFSET

    // ---- 按钮 ----

    /**
     * P8-11 L1 — notification actions. 运行中只有“停止”；待确认只留“拒绝 / 允许一次”
     * （停止先收起来，免得和拒绝点混）；ask_user 给 RemoteInput 回复 + 快捷答案。
     * Approve/deny/reply are protected by a one-time token bound to
     * runId + toolCallId + args digest (P8-10) — notification ID alone is
     * never enough to act.
     */
    private fun buildActions(
        conversationId: Uuid,
        runId: String?,
        status: AgentLiveStatus,
        messages: List<UIMessage>,
    ): List<NotificationActionConfig> {
        val pendingTool = pendingToolForActions(messages)
        return when {
            pendingTool == null -> if (status.kind.isRunning()) listOf(stopAction(conversationId, runId)) else emptyList()
            pendingTool.toolName == ASK_USER_TOOL -> listOf(replyAction(conversationId, runId, pendingTool, status.replyChoices))
            else -> approvalActions(conversationId, runId, pendingTool)
        }
    }

    private fun pendingToolForActions(messages: List<UIMessage>): UIMessagePart.Tool? =
        lastAssistantParts(messages).filterIsInstance<UIMessagePart.Tool>()
            .lastOrNull { it.approvalState is ToolApprovalState.Pending }

    private fun approvalActions(
        conversationId: Uuid,
        runId: String?,
        tool: UIMessagePart.Tool,
    ): List<NotificationActionConfig> {
        val token = approvalTokens.issue(
            runId = runId,
            conversationId = conversationId.toString(),
            toolCallId = tool.toolCallId,
            argsDigest = argsDigest(tool.input),
        )
        return listOf(
            decisionAction(
                action = AgentNotificationActionReceiver.ACTION_DENY_TOOL,
                requestOffset = DENY_ACTION_REQUEST_OFFSET,
                title = context.getString(R.string.notification_live_status_action_deny),
                conversationId = conversationId,
                runId = runId,
                toolCallId = tool.toolCallId,
                token = token,
            ),
            decisionAction(
                action = AgentNotificationActionReceiver.ACTION_APPROVE_TOOL,
                requestOffset = APPROVE_ACTION_REQUEST_OFFSET,
                title = context.getString(R.string.live_update_action_allow_once),
                conversationId = conversationId,
                runId = runId,
                toolCallId = tool.toolCallId,
                token = token,
            ),
        )
    }

    private fun decisionAction(
        action: String,
        requestOffset: Int,
        title: String,
        conversationId: Uuid,
        runId: String?,
        toolCallId: String,
        token: String,
    ): NotificationActionConfig {
        val intent = Intent(context, AgentNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(AgentNotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId.toString())
            putExtra(AgentNotificationActionReceiver.EXTRA_TOOL_CALL_ID, toolCallId)
            putExtra(AgentNotificationActionReceiver.EXTRA_APPROVAL_TOKEN, token)
            // P1-05: the runId lets the receiver validate ownership before acting.
            if (runId != null) {
                putExtra(AgentNotificationActionReceiver.EXTRA_RUN_ID, runId)
            }
        }
        return NotificationActionConfig(title = title, intent = broadcast(intent, conversationId, requestOffset))
    }

    private fun replyAction(
        conversationId: Uuid,
        runId: String?,
        tool: UIMessagePart.Tool,
        choices: List<String>,
    ): NotificationActionConfig {
        // P8-10: the reply is bound to the ask_user call the user saw — one-time
        // token, so an old notification cannot answer a resolved question.
        val token = approvalTokens.issue(
            runId = runId,
            conversationId = conversationId.toString(),
            toolCallId = tool.toolCallId,
            argsDigest = argsDigest(tool.input),
        )
        val intent = Intent(context, AgentNotificationActionReceiver::class.java).apply {
            action = AgentNotificationActionReceiver.ACTION_REPLY_ASK_USER
            putExtra(AgentNotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId.toString())
            putExtra(AgentNotificationActionReceiver.EXTRA_TOOL_CALL_ID, tool.toolCallId)
            putExtra(AgentNotificationActionReceiver.EXTRA_APPROVAL_TOKEN, token)
            if (runId != null) {
                putExtra(AgentNotificationActionReceiver.EXTRA_RUN_ID, runId)
            }
        }
        val remoteInput = RemoteInput.Builder(AgentNotificationActionReceiver.EXTRA_REPLY_TEXT)
            .setLabel(context.getString(R.string.notification_live_status_reply_hint))
            .apply { if (choices.isNotEmpty()) setChoices(choices.toTypedArray()) }
            .build()
        return NotificationActionConfig(
            title = context.getString(R.string.notification_live_status_action_reply),
            intent = broadcast(intent, conversationId, REPLY_ACTION_REQUEST_OFFSET, mutable = true),
            remoteInput = remoteInput,
            // 快捷答案只用 ask_user 自带的候选项，不让系统再生成猜测性回复。
            allowGeneratedReplies = false,
        )
    }

    private fun stopAction(conversationId: Uuid, runId: String?): NotificationActionConfig {
        val intent = Intent(context, AgentNotificationActionReceiver::class.java).apply {
            action = AgentNotificationActionReceiver.ACTION_STOP_GENERATION
            putExtra(AgentNotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId.toString())
            // P1-05: the stop action carries the runId so the receiver can
            // cancel exactly the run this notification belongs to.
            if (runId != null) {
                putExtra(AgentNotificationActionReceiver.EXTRA_RUN_ID, runId)
            }
        }
        return NotificationActionConfig(
            title = context.getString(R.string.notification_live_status_action_stop),
            intent = broadcast(intent, conversationId, STOP_ACTION_REQUEST_OFFSET),
        )
    }

    private fun retryAction(conversationId: Uuid, runId: String?): NotificationActionConfig {
        val intent = Intent(context, AgentNotificationActionReceiver::class.java).apply {
            action = AgentNotificationActionReceiver.ACTION_RETRY_GENERATION
            putExtra(AgentNotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId.toString())
            if (runId != null) {
                putExtra(AgentNotificationActionReceiver.EXTRA_RUN_ID, runId)
            }
        }
        return NotificationActionConfig(
            title = context.getString(R.string.live_update_action_retry),
            intent = broadcast(intent, conversationId, RETRY_ACTION_REQUEST_OFFSET),
        )
    }

    private fun broadcast(
        intent: Intent,
        conversationId: Uuid,
        requestOffset: Int,
        mutable: Boolean = false,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            conversationId.hashCode() + requestOffset,
            intent,
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or
                PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // ---- 语义 ----

    private enum class LiveTone { NEUTRAL, ATTENTION, SUCCESS, FAILURE }

    private data class LiveProgress(val phase: Int, val tone: LiveTone) {
        /** 已完成阶段填满；完成态全部填满。当前阶段停在段首，不编造段内百分比。 */
        fun filledLength(): Int = phase.coerceIn(0, PHASE_COUNT) * PHASE_LENGTH
    }

    private class Session(val startedAt: Long) {
        var updatedAt: Long = 0
        var signature: String? = null
        var phase: Int = 0
        var waitingSince: Long? = null
    }

    private fun AgentLiveStatusKind.tone(): LiveTone = when (this) {
        AgentLiveStatusKind.WAITING_PERMISSION, AgentLiveStatusKind.WAITING_USER -> LiveTone.ATTENTION
        AgentLiveStatusKind.COMPLETED -> LiveTone.SUCCESS
        AgentLiveStatusKind.FAILED -> LiveTone.FAILURE
        else -> LiveTone.NEUTRAL
    }

    private fun LiveTone.hex(): String? = when (this) {
        LiveTone.NEUTRAL -> null
        LiveTone.ATTENTION -> ACCENT_COLOR
        LiveTone.SUCCESS -> SUCCESS_COLOR
        LiveTone.FAILURE -> FAILURE_COLOR
    }

    private fun LiveTone.color(): Int? = hex()?.let(Color::parseColor)

    private fun AgentLiveStatusKind.isWaiting(): Boolean =
        this == AgentLiveStatusKind.WAITING_PERMISSION || this == AgentLiveStatusKind.WAITING_USER

    private fun AgentLiveStatusKind.isRunning(): Boolean =
        this == AgentLiveStatusKind.PLANNING ||
            this == AgentLiveStatusKind.RUNNING_TOOL ||
            this == AgentLiveStatusKind.WRITING

    /** 三段：0 规划 / 1 执行 / 2 成稿；等待确认发生在执行段。 */
    private fun AgentLiveStatusKind.phaseIndex(): Int = when (this) {
        AgentLiveStatusKind.IDLE, AgentLiveStatusKind.PLANNING -> 0
        AgentLiveStatusKind.RUNNING_TOOL,
        AgentLiveStatusKind.WAITING_PERMISSION,
        AgentLiveStatusKind.WAITING_USER -> 1
        AgentLiveStatusKind.WRITING, AgentLiveStatusKind.FAILED -> 2
        AgentLiveStatusKind.COMPLETED -> PHASE_COUNT
    }

    private fun LiveFailureReason.titleRes(): Int = when (this) {
        LiveFailureReason.NETWORK -> R.string.live_update_failed_network
        LiveFailureReason.QUOTA -> R.string.live_update_failed_quota
        LiveFailureReason.SERVICE -> R.string.live_update_failed_service
        LiveFailureReason.OTHER -> R.string.live_update_failed_other
    }

    companion object {
        private const val LIVE_NOTIFICATION_OFFSET = 10_000
        private const val STOP_ACTION_REQUEST_OFFSET = 20_000
        private const val APPROVE_ACTION_REQUEST_OFFSET = 21_000
        private const val DENY_ACTION_REQUEST_OFFSET = 22_000
        private const val REPLY_ACTION_REQUEST_OFFSET = 23_000
        private const val RETRY_ACTION_REQUEST_OFFSET = 24_000
        private const val ASK_USER_TOOL = "ask_user"
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L
        private const val MAX_TOOL_TITLE_CHARS = 32
        private const val MAX_COMMAND_CHARS = 48
        private const val MAX_QUESTION_CHARS = 60
        private const val MAX_SUMMARY_CHARS = 80
        private const val MAX_INSTALL_TARGETS = 3
        private const val MAX_REPLY_CHOICES = 3
        private const val PHASE_COUNT = 3
        private const val PHASE_LENGTH = 100
        private const val COMPLETED_TIMEOUT_MS = 2 * 60 * 1000L
        private const val FAILURE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val RUNNING_ISLAND_TIMEOUT_SECONDS = 60 * 60
        private const val WAITING_ISLAND_TIMEOUT_SECONDS = 2 * 60 * 60
        private const val ACCENT_COLOR = "#C99C54"
        private const val SUCCESS_COLOR = "#2A9D67"
        private const val FAILURE_COLOR = "#D24C49"
        private const val ISLAND_NEUTRAL_COLOR = "#8E8E93"
        private val STEP_FAILED_STATUSES = setOf(
            ToolActivityStatus.FAILED,
            ToolActivityStatus.TIMED_OUT,
            ToolActivityStatus.INTERRUPTED,
            ToolActivityStatus.CANCELLED,
        )
        private val URL_REGEX = Regex("https?://[^\\s\"'}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val INSTALL_COMMAND_REGEX = Regex(
            "\\b(?:apk\\s+add|pip3?\\s+install|uv\\s+pip\\s+install|npm\\s+install|pnpm\\s+add|yarn\\s+add)\\b\\s+([^;&|]+)"
        )
    }
}

private val QUOTA_REGEX = Regex(
    "\\b429\\b|\\b402\\b|quota|rate.?limit|insufficient|billing|credit|余额|额度|限流",
    RegexOption.IGNORE_CASE,
)

/** 按异常链归类失败原因；只看类型和消息关键词，不把异常名给用户看。 */
internal fun liveFailureReason(error: Throwable): LiveFailureReason {
    val chain = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
    if (chain.any { QUOTA_REGEX.containsMatchIn(it.message.orEmpty()) }) return LiveFailureReason.QUOTA
    if (chain.any { it is IOException }) return LiveFailureReason.NETWORK
    if (chain.any { it is HttpException }) return LiveFailureReason.SERVICE
    return LiveFailureReason.OTHER
}
