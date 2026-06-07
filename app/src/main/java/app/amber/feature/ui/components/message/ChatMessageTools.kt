package app.amber.feature.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Wrench01
import app.amber.agent.R
import app.amber.core.event.AppEvent
import app.amber.core.event.AppEventBus
import app.amber.feature.ui.components.richtext.ZoomableAsyncImage
import app.amber.feature.ui.components.ui.ChainOfThoughtScope
import app.amber.feature.ui.components.ui.FaviconRow
import app.amber.feature.ui.components.ui.WorkspaceIconButton
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.WorkspaceStatusPill
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.modifier.shimmer
import app.amber.core.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object ToolNames {
    const val MEMORY = "memory_tool"
    const val SEARCH_WEB = "search_web"
    const val SCRAPE_WEB = "scrape_web"
    const val GET_TIME_INFO = "get_time_info"
    const val CLIPBOARD = "clipboard_tool"
    const val TTS = "text_to_speech"
    const val ASK_USER = "ask_user"
    const val USE_SKILL = "use_skill"
}

internal enum class AgentToolStatus {
    RUNNING,
    WAITING_FOR_PERMISSION,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

internal enum class AgentToolKind {
    FILE,
    TERMINAL,
    MCP,
    SCREEN,
    WEB,
    MEMORY,
    GENERIC,
}

internal object MemoryActions {
    const val CREATE = "create"
    const val EDIT = "edit"
    const val DELETE = "delete"
}

private object ClipboardActions {
    const val READ = "read"
    const val WRITE = "write"
}

private fun getToolIcon(toolName: String, action: String?) = when (toolName) {
    ToolNames.MEMORY -> when (action) {
        MemoryActions.CREATE, MemoryActions.EDIT -> HugeIcons.QuillWrite01
        MemoryActions.DELETE -> HugeIcons.Eraser
        else -> HugeIcons.QuillWrite01
    }

    ToolNames.SEARCH_WEB -> HugeIcons.GlobalSearch
    ToolNames.SCRAPE_WEB -> HugeIcons.GlobalSearch
    "webview_search_open" -> HugeIcons.GlobalSearch
    "webview_open" -> HugeIcons.GlobalSearch
    "webview_wait_for_load" -> HugeIcons.GlobalSearch
    "webview_read" -> HugeIcons.GlobalSearch
    in setOf("icloud_status", "icloud_list", "icloud_read", "icloud_write", "icloud_search") -> HugeIcons.Database02
    ToolNames.GET_TIME_INFO -> HugeIcons.Time02
    ToolNames.CLIPBOARD -> HugeIcons.Clipboard
    ToolNames.TTS -> HugeIcons.VolumeHigh
    ToolNames.ASK_USER -> HugeIcons.BubbleChatQuestion
    ToolNames.USE_SKILL -> HugeIcons.MagicWand01
    in setOf(
        "file_list",
        "file_read",
        "file_write",
        "file_edit",
        "file_search",
        "file_move",
        "share_file"
    ) -> HugeIcons.File02

    in setOf(
        "terminal_execute",
        "terminal_session_start",
        "terminal_session_exec",
        "terminal_session_read",
        "terminal_session_stop"
    ) -> HugeIcons.Code

    in setOf(
        "screen_click",
        "screen_long_click",
        "screen_swipe",
        "screen_input_text",
        "screen_back",
        "screen_home",
        "screen_open_app",
        "screen_read_ui",
        "screen_screenshot",
        "vlm_task"
    ) -> HugeIcons.FullScreen

    else -> when {
        toolName.startsWith("mcp__") -> HugeIcons.Package
        // Phase 2 WebMount: primitives + adapter tools use the web/search
        // icon so they're visually grouped with webview_* tools.
        toolName.startsWith("wm_") ||
            toolName.startsWith("hn_") ||
            toolName.startsWith("reddit_") ||
            toolName.startsWith("juejin_") ||
            toolName.startsWith("github_") ||
            toolName.startsWith("bilibili_") ||
            toolName.startsWith("zhihu_") ||
            toolName.startsWith("feishu_docs_") -> HugeIcons.GlobalSearch
        else -> HugeIcons.Wrench01
    }
}

private fun getToolKind(toolName: String) = when {
    toolName in setOf(
        "file_list",
        "file_read",
        "file_write",
        "file_edit",
        "file_search",
        "file_move",
        "share_file"
    ) -> AgentToolKind.FILE

    toolName in setOf(
        "terminal_execute",
        "terminal_session_start",
        "terminal_session_exec",
        "terminal_session_read",
        "terminal_session_stop"
    ) -> AgentToolKind.TERMINAL

    toolName in setOf(
        "screen_click",
        "screen_long_click",
        "screen_swipe",
        "screen_input_text",
        "screen_back",
        "screen_home",
        "screen_open_app",
        "screen_read_ui",
        "screen_screenshot",
        "vlm_task"
    ) -> AgentToolKind.SCREEN

    toolName == ToolNames.SEARCH_WEB ||
        toolName == ToolNames.SCRAPE_WEB ||
        toolName == "webview_search_open" ||
        toolName == "webview_open" ||
        toolName == "webview_wait_for_load" ||
        toolName == "webview_read" -> AgentToolKind.WEB
    // Phase 2 WebMount: primitives + adapter tools all live under "网页".
    toolName.startsWith("wm_") ||
        toolName.startsWith("hn_") ||
        toolName.startsWith("reddit_") ||
        toolName.startsWith("juejin_") ||
        toolName.startsWith("github_") ||
        toolName.startsWith("bilibili_") ||
        toolName.startsWith("zhihu_") ||
        toolName.startsWith("feishu_docs_") -> AgentToolKind.WEB
    toolName.startsWith("icloud_") -> AgentToolKind.FILE
    toolName == ToolNames.MEMORY -> AgentToolKind.MEMORY
    toolName.startsWith("mcp__") -> AgentToolKind.MCP
    else -> AgentToolKind.GENERIC
}

internal fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

private fun String.compactToolPreview(maxLength: Int = 34): String {
    val compact = trim().replace(Regex("\\s+"), " ")
    return if (compact.length > maxLength) compact.take(maxLength - 1) + "…" else compact
}

private fun JsonElement.toolDisplayTitleHint(): String? =
    getStringContent("display_title")
        ?.compactToolPreview(28)
        ?.takeIf { it.isNotBlank() }

private fun JsonElement.pathDisplayName(key: String = "path"): String =
    getStringContent(key)
        ?.substringAfterLast('/')
        ?.ifBlank { null }
        ?.compactToolPreview(18)
        .orEmpty()

private fun toolHasFailure(content: JsonElement?, output: List<UIMessagePart>): Boolean {
    val jsonObject = content?.jsonObjectOrNull
    val error = jsonObject?.getStringContent("error")
    val exitCode = jsonObject?.get("exit_code")?.jsonPrimitiveOrNull?.intOrNull
    val status = jsonObject?.get("status")?.jsonPrimitiveOrNull?.contentOrNull?.lowercase()
    val failed = jsonObject?.get("failed")?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() == true
    if (jsonObject != null) {
        return !error.isNullOrBlank() ||
            (exitCode != null && exitCode != 0) ||
            failed ||
            status in setOf("failed", "error", "denied")
    }
    return output.filterIsInstance<UIMessagePart.Text>().any { part ->
        part.text.contains("\"error\"", ignoreCase = true) ||
            part.text.contains("Exception", ignoreCase = true)
    }
}

private fun toolStatusFromMessagePart(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    content: JsonElement?,
): AgentToolStatus = when {
    tool.approvalState is ToolApprovalState.Pending -> AgentToolStatus.WAITING_FOR_PERMISSION
    tool.approvalState is ToolApprovalState.Denied -> AgentToolStatus.CANCELLED
    !tool.isExecuted && loading -> AgentToolStatus.RUNNING
    !tool.isExecuted -> AgentToolStatus.RUNNING
    toolHasFailure(content = content, output = tool.output) -> AgentToolStatus.FAILED
    else -> AgentToolStatus.SUCCEEDED
}

@Composable
private fun toolStatusLabel(status: AgentToolStatus): String = when (status) {
    AgentToolStatus.RUNNING -> "执行中"
    AgentToolStatus.WAITING_FOR_PERMISSION -> "待授权"
    AgentToolStatus.SUCCEEDED -> "成功"
    AgentToolStatus.FAILED -> "失败"
    AgentToolStatus.CANCELLED -> "已取消"
}

private fun toolStatusTone(status: AgentToolStatus): WorkspaceTone = when (status) {
    AgentToolStatus.RUNNING -> WorkspaceTone.Accent
    AgentToolStatus.WAITING_FOR_PERMISSION -> WorkspaceTone.Warning
    AgentToolStatus.SUCCEEDED -> WorkspaceTone.Success
    AgentToolStatus.FAILED -> WorkspaceTone.Danger
    AgentToolStatus.CANCELLED -> WorkspaceTone.Neutral
}

@Composable
private fun toolKindLabel(kind: AgentToolKind, toolName: String): String = when (kind) {
    AgentToolKind.FILE -> "文件"
    AgentToolKind.TERMINAL -> "终端"
    AgentToolKind.MCP -> "MCP"
    AgentToolKind.SCREEN -> "屏幕"
    AgentToolKind.WEB -> "网页"
    AgentToolKind.MEMORY -> "记忆"
    // Generic fallback used to return toolName itself, which produced the
    // ugly "$toolName · $toolName" duplicate subtitle for any tool not in
    // one of the categorized buckets. Plain "工具" reads cleaner and the
    // tool name still shows on the right of the dot.
    AgentToolKind.GENERIC -> "工具"
}

@Composable
private fun ToolStatusPill(
    status: AgentToolStatus,
    modifier: Modifier = Modifier,
) {
    WorkspaceStatusPill(
        text = toolStatusLabel(status),
        modifier = modifier,
        tone = toolStatusTone(status),
    )
}

@Composable
internal fun AgentToolCallCapsule(
    title: String,
    toolName: String,
    icon: ImageVector,
    kind: AgentToolKind,
    status: AgentToolStatus,
    loading: Boolean,
    onClick: (() -> Unit)?,
    approvalActions: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val workspace = workspaceColors()
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // V3 ResultPill spec (convo-tool-result.jsx:80):
    //   padding 3/10/3/3, fillMaxWidth, 999 圆角, toolPillBg + 1dp toolPillEdge
    //   左侧 16dp accent 圆 + 10dp 白勾 (替代旧 15dp tool 图标)
    //   inline "tool · query" 11.5sp letter 0.2  toolLabelInk W500 / inkSoft W400
    //   高度 ~22dp (3+16+3)
    val shape = androidx.compose.foundation.shape.CircleShape
    Surface(
        modifier = modifier
            // V3: 改 wrapContentWidth, 胶囊自适应内容长度而非顶满整行
            .wrapContentWidth(align = Alignment.Start)
            .widthIn(max = 460.dp)
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            )
            .animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = shape,
        color = theme.toolPillBg,
        contentColor = workspace.ink,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, theme.toolPillEdge),
    ) {
        val reserveStatusSlot = approvalActions == null
        Box {
            Row(
                modifier = Modifier.padding(start = 7.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = if (reserveStatusSlot) {
                        Modifier.padding(end = 24.dp)
                    } else {
                        Modifier.padding(end = 7.dp)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (leadingContent != null) {
                        leadingContent()
                    } else {
                        V3ToolTypeIcon(
                            icon = icon,
                            kind = kind,
                        )
                    }

                    val displayText = if (subtitle.isNullOrBlank()) title else "$title $subtitle"
                    val amberType = app.amber.feature.ui.theme.LocalAmberType.current
                    Text(
                        text = displayText,
                        // §6.2 ToolCall row: tool name + args are machine-facts → mono (.meta),
                        // colored with accent (toolLabelInk).
                        style = amberType.meta,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = theme.toolLabelInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = if (approvalActions == null) 380.dp else 180.dp)
                            .shimmer(isLoading = loading && status == AgentToolStatus.RUNNING),
                    )

                    if (approvalActions != null) {
                        approvalActions()
                    }
                }
            }
            if (reserveStatusSlot) {
                V3ToolStatusBadge(
                    status = status,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun V3ToolTypeIcon(
    icon: ImageVector,
    kind: AgentToolKind,
) {
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val tint = when (kind) {
        AgentToolKind.FILE -> theme.toolIconInk
        AgentToolKind.TERMINAL -> theme.toolIconInk
        AgentToolKind.MCP -> theme.toolIconInk
        AgentToolKind.SCREEN -> theme.toolIconInk
        AgentToolKind.WEB -> theme.toolIconInk
        AgentToolKind.MEMORY -> theme.toolIconInk
        AgentToolKind.GENERIC -> theme.toolIconInk
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.requiredSize(16.dp),
    )
}

@Composable
private fun V3ToolStatusBadge(
    status: AgentToolStatus,
    modifier: Modifier = Modifier,
) {
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // §6.2: a completed tool call uses signal-green (liveness/"just-finished"),
    // independent of the accent-derived toolDoneBg.
    val signal = app.amber.feature.ui.theme.LocalAmberTokens.current.signal
    val statusIconInk = if (theme.isDark) theme.bg else Color.White
    val (bg, ink) = when (status) {
        AgentToolStatus.SUCCEEDED -> signal to theme.toolDoneBadgeInk
        AgentToolStatus.RUNNING -> Color.Transparent to theme.toolDoneBg
        AgentToolStatus.WAITING_FOR_PERMISSION -> theme.contextMid to statusIconInk
        AgentToolStatus.FAILED -> theme.contextHigh to statusIconInk
        AgentToolStatus.CANCELLED -> Color.Transparent to theme.inkSoft
    }
    if (status == AgentToolStatus.RUNNING) {
        // §6.2 ToolCall row: running → signal-green breathing live dot.
        Box(modifier.requiredSize(16.dp), contentAlignment = Alignment.Center) {
            app.amber.feature.ui.components.ds.LiveDot()
        }
        return
    }
    Surface(
        modifier = modifier.requiredSize(16.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = bg,
        border = if (status == AgentToolStatus.CANCELLED) BorderStroke(1.dp, ink) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (status == AgentToolStatus.SUCCEEDED) {
                Icon(
                    imageVector = me.rerere.hugeicons.HugeIcons.Tick01,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(10.dp),
                )
            } else if (status == AgentToolStatus.FAILED) {
                Icon(
                    imageVector = me.rerere.hugeicons.HugeIcons.Cancel01,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(10.dp),
                )
            } else if (status == AgentToolStatus.CANCELLED) {
                Icon(
                    imageVector = me.rerere.hugeicons.HugeIcons.Cancel01,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(10.dp),
                )
            } else if (status == AgentToolStatus.WAITING_FOR_PERMISSION) {
                Icon(
                    imageVector = me.rerere.hugeicons.HugeIcons.Time02,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

@Composable
private fun RunningToolSpinner(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "toolRunningSpinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
        ),
        label = "toolRunningSpinnerRotation",
    )
    Canvas(
        modifier = modifier.graphicsLayer { rotationZ = rotation }
    ) {
        val dotCount = 8
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.36f
        val dotRadius = size.minDimension * 0.07f
        for (index in 0 until dotCount) {
            val angle = (2.0 * PI * index / dotCount).toFloat()
            drawCircle(
                color = color.copy(alpha = 0.22f + 0.68f * (index + 1) / dotCount),
                radius = dotRadius,
                center = androidx.compose.ui.geometry.Offset(
                    x = center.x + cos(angle) * radius,
                    y = center.y + sin(angle) * radius,
                ),
            )
        }
    }
}

@Composable
private fun toolDisplayTitle(
    toolName: String,
    arguments: JsonElement,
    memoryAction: String?,
): String {
    arguments.toolDisplayTitleHint()?.let { return it }

    return when (toolName) {
    ToolNames.MEMORY -> when (memoryAction) {
        MemoryActions.CREATE -> stringResource(R.string.chat_message_tool_create_memory)
        MemoryActions.EDIT -> stringResource(R.string.chat_message_tool_edit_memory)
        MemoryActions.DELETE -> stringResource(R.string.chat_message_tool_delete_memory)
        else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
    }

    ToolNames.SEARCH_WEB -> stringResource(
        R.string.chat_message_tool_search_web,
        arguments.getStringContent("query") ?: ""
    )

    ToolNames.SCRAPE_WEB -> stringResource(R.string.chat_message_tool_scrape_web)
    "webview_search_open" -> "打开搜索页 ${arguments.getStringContent("query")?.compactToolPreview(28).orEmpty()}"
    "webview_open" -> "打开网页 ${arguments.getStringContent("url")?.compactToolPreview(28).orEmpty()}"
    "webview_wait_for_load" -> "等待网页加载"
    "webview_read" -> "读取当前网页"
    "icloud_status" -> "检查 iCloud 挂载"
    "icloud_list" -> "列出 iCloud ${arguments.getStringContent("path")?.compactToolPreview(18).orEmpty()}"
    "icloud_read" -> "读取 iCloud ${arguments.getStringContent("path")?.compactToolPreview(22).orEmpty()}"
    "icloud_write" -> "写入 iCloud ${arguments.getStringContent("path")?.compactToolPreview(22).orEmpty()}"
    "icloud_search" -> "搜索 iCloud ${arguments.getStringContent("query")?.compactToolPreview(22).orEmpty()}"
    ToolNames.GET_TIME_INFO -> stringResource(R.string.chat_message_tool_get_time)
    ToolNames.CLIPBOARD -> when (memoryAction) {
        ClipboardActions.READ -> stringResource(R.string.chat_message_tool_clipboard_read)
        ClipboardActions.WRITE -> stringResource(R.string.chat_message_tool_clipboard_write)
        else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
    }

    ToolNames.TTS -> {
        val preview = arguments.getStringContent("text")?.compactToolPreview(24) ?: ""
        "Speaking: $preview"
    }

    ToolNames.USE_SKILL -> {
        val skillName = arguments.getStringContent("name") ?: ""
        val path = arguments.getStringContent("path")
        if (path != null) "Skill: $skillName / $path" else "Skill: $skillName"
    }

    "file_list" -> "列出 workspace ${arguments.getStringContent("path")?.compactToolPreview(18).orEmpty()}"
    "file_read" -> "读取文件 ${arguments.getStringContent("path")?.compactToolPreview(22).orEmpty()}"
    "file_write" -> "写入 ${arguments.pathDisplayName().ifBlank { "文件" }}"
    "file_edit" -> "编辑 ${arguments.pathDisplayName().ifBlank { "文件" }}"
    "file_search" -> "搜索文件 ${arguments.getStringContent("query")?.compactToolPreview(22).orEmpty()}"
    "file_move" -> "移动文件 ${arguments.getStringContent("from")?.compactToolPreview(16).orEmpty()}"
    "share_file" -> "分享 ${arguments.pathDisplayName().ifBlank { "文件" }}"
    "share_text" -> "分享文本"
    "tool_search" -> "查找可用工具"
    "tools_list" -> "查看工具目录"
    "tool_policy_explain" -> "检查工具权限"
    "terminal_execute" -> "执行 Alpine 命令 ${arguments.getStringContent("command")?.compactToolPreview(22).orEmpty()}"
    "terminal_session_start" -> "启动终端会话"
    "terminal_session_exec" -> "终端会话执行 ${arguments.getStringContent("command")?.compactToolPreview(20).orEmpty()}"
    "terminal_session_read" -> "读取终端输出"
    "terminal_session_stop" -> "停止终端会话"
    "screen_click" -> "点击屏幕"
    "screen_long_click" -> "长按屏幕"
    "screen_swipe" -> "滑动屏幕"
    "screen_input_text" -> "输入文字"
    "screen_back" -> "返回"
    "screen_home" -> "回到桌面"
    "screen_open_app" -> "打开应用 ${arguments.getStringContent("package")?.compactToolPreview(18).orEmpty()}"
    "screen_read_ui" -> "读取当前 UI"
    "screen_screenshot" -> "获取屏幕截图"
    "vlm_task" -> "执行 VLM 手机任务"
    else -> if (toolName.startsWith("mcp__")) {
        "MCP ${toolName.removePrefix("mcp__").compactToolPreview(26)}"
    } else {
        stringResource(R.string.chat_message_tool_call_generic, toolName)
    }
    }
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
) {
    val isAskUser = tool.toolName == ToolNames.ASK_USER

    if (isAskUser) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }
    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    val eventBus: AppEventBus = koinInject()
    val scope = rememberCoroutineScope()
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val arguments = remember(tool.input) { MessageRenderCache.toolInputJson(tool.input) }
    val memoryAction = arguments.getStringContent("action")
    val content = remember(tool.isExecuted, tool.output) {
        if (tool.isExecuted) {
            MessageRenderCache.toolOutputJson(tool.output)
        } else {
            null
        }
    }
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    val title = toolDisplayTitle(tool.toolName, arguments, memoryAction)
    val status = toolStatusFromMessagePart(tool = tool, loading = loading, content = content)
    val workspace = workspaceColors()

    val workspaceFilePath = remember(tool.toolName, status, content, arguments) {
        if (status != AgentToolStatus.SUCCEEDED) return@remember null
        if (tool.toolName !in setOf("file_write", "file_edit", "file_read")) return@remember null
        val candidate = content.getStringContent("path")
            ?: arguments.getStringContent("path")
        candidate?.takeIf { it.isNotBlank() && !it.startsWith("/") }
    }
    // generate_image renders its result as a dedicated full-width carousel
    // below the capsule, NOT as 64dp thumbnails inside the small extra-content
    // surface. Skip the regular extra-content path so we don't double-render
    // (capsule + tiny thumb + big carousel).
    val isGenerateImage = tool.toolName == "generate_image"
    val hasExtraContent = isDenied || (images.isNotEmpty() && !isGenerateImage)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        AgentToolCallCapsule(
            title = title,
            toolName = tool.toolName,
            icon = getToolIcon(tool.toolName, memoryAction),
            kind = getToolKind(tool.toolName),
            status = status,
            loading = loading,
            onClick = { showResult = true },
            approvalActions = if (isPending && onToolApproval != null) {
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ToolStatusPill(status = status)
                        WorkspaceIconButton(
                            onClick = { showDenyDialog = true },
                            modifier = Modifier.size(24.dp),
                            size = 24.dp,
                            iconSize = 12.dp,
                            tone = WorkspaceTone.Danger,
                            icon = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                        )
                        WorkspaceIconButton(
                            onClick = { onToolApproval(tool.toolCallId, true, "") },
                            modifier = Modifier.size(24.dp),
                            size = 24.dp,
                            iconSize = 12.dp,
                            tone = WorkspaceTone.Success,
                            icon = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                        )
                    }
                }
            } else {
                null
            },
        )

        // generate_image tool: pre-allocate the result area while the tool
        // is running so we can show an animated dot-grid placeholder card
        // (same shape as the requested aspect ratio), then crossfade to the
        // real carousel once images arrive. Skip entirely on FAILED / DENIED
        // — the capsule itself surfaces those states.
        if (isGenerateImage && status != AgentToolStatus.FAILED && !isDenied) {
            val aspectRatioFloat = remember(arguments) {
                parseAspectRatioFloat(
                    arguments.jsonObject["aspect_ratio"]?.jsonPrimitive?.contentOrNull
                )
            }
            val requestedCount = remember(arguments) {
                arguments.jsonObject["count"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 4) ?: 1
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 4.dp),
            ) {
                AnimatedContent(
                    targetState = images.isNotEmpty(),
                    transitionSpec = {
                        fadeIn(animationSpec = tween(durationMillis = 420)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 260))
                    },
                    label = "imageGenSwap",
                ) { hasImages ->
                    if (hasImages) {
                        GeneratedImageCarousel(images = images)
                    } else {
                        // Match the post-load carousel width so the
                        // crossfade doesn't visibly resize:
                        //   - count == 1 → carousel renders single full-width
                        //     card, so placeholder also full-width.
                        //   - count >  1 → carousel uses 0.85f per card in a
                        //     horizontal scroller, so placeholder matches.
                        val widthFraction = if (requestedCount > 1) 0.85f else 1f
                        GeneratedImagePlaceholder(
                            aspectRatio = aspectRatioFloat,
                            modifier = Modifier.fillMaxWidth(widthFraction),
                        )
                    }
                }
            }
        }

        if (workspaceFilePath != null && onOpenWorkspaceFile != null) {
            Surface(
                modifier = Modifier
                    .padding(start = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenWorkspaceFile(workspaceFilePath) },
                shape = RoundedCornerShape(8.dp),
                color = workspace.row,
                contentColor = workspace.ink,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = workspace.muted,
                    )
                    Text(
                        text = workspaceFilePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = "点击预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.faint,
                    )
                }
            }
        }

        if (hasExtraContent) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
                shape = RoundedCornerShape(8.dp),
                color = workspace.row,
                contentColor = workspace.muted,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (tool.toolName == ToolNames.MEMORY &&
                        memoryAction in listOf(MemoryActions.CREATE, MemoryActions.EDIT)
                    ) {
                        content.getStringContent("content")?.let { memoryContent ->
                            Text(
                                text = memoryContent,
                                style = MaterialTheme.typography.labelSmall,
                                color = workspace.muted,
                                modifier = Modifier.shimmer(isLoading = loading),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (tool.toolName == ToolNames.SEARCH_WEB) {
                        content.getStringContent("answer")?.let { answer ->
                            Text(
                                text = answer,
                                style = MaterialTheme.typography.labelSmall,
                                color = workspace.muted,
                                modifier = Modifier.shimmer(isLoading = loading),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val items = content?.jsonObject?.get("items")?.jsonArray ?: emptyList()
                        if (items.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FaviconRow(
                                    urls = items.mapNotNull { it.getStringContent("url") },
                                    size = 18.dp,
                                )
                                Text(
                                    text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = workspace.faint,
                                )
                            }
                        }
                    }
                    if (tool.toolName == ToolNames.SCRAPE_WEB) {
                        val url = arguments.getStringContent("url") ?: ""
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            color = workspace.faint,
                        )
                    }
                    if (tool.toolName == ToolNames.TTS) {
                        val text = arguments.getStringContent("text") ?: ""
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelSmall,
                                color = workspace.muted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            WorkspaceIconButton(
                                onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text)) } },
                                modifier = Modifier.size(28.dp),
                                size = 28.dp,
                                iconSize = 14.dp,
                                icon = HugeIcons.Refresh01,
                                contentDescription = "Replay",
                            )
                        }
                    }
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = workspace.red,
                        )
                    }
                }
            }
        }
    }

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            }
        )
    }

    if (showResult) {
        ToolCallPreviewSheet(
            toolName = tool.toolName,
            arguments = arguments,
            content = content,
            output = tool.output,
            onDismissRequest = { showResult = false }
        )
    }
}
