package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.HighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilManager
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRolePresets
import me.rerere.rikkahub.data.agent.subagent.SubAgentDefinitions
import me.rerere.rikkahub.data.agent.subagent.SubAgentManager
import me.rerere.rikkahub.data.agent.subagent.SubAgentRunStatus
import me.rerere.rikkahub.data.agent.subagent.readSubAgentDisplayTextFromTranscript
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.WorkspaceIconButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceLeadingIcon
import me.rerere.rikkahub.ui.components.ui.WorkspaceStatusPill
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject
import java.io.File

private object ToolNames {
    const val MEMORY = "memory_tool"
    const val SEARCH_WEB = "search_web"
    const val SCRAPE_WEB = "scrape_web"
    const val GET_TIME_INFO = "get_time_info"
    const val CLIPBOARD = "clipboard_tool"
    const val TTS = "text_to_speech"
    const val ASK_USER = "ask_user"
    const val USE_SKILL = "use_skill"
}

private enum class AgentToolStatus {
    RUNNING,
    WAITING_FOR_PERMISSION,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

private enum class AgentToolKind {
    FILE,
    TERMINAL,
    MCP,
    SCREEN,
    WEB,
    MEMORY,
    GENERIC,
}

private object MemoryActions {
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

    ToolNames.SEARCH_WEB -> HugeIcons.Search01
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
        "file_move"
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
        else -> HugeIcons.Tools
    }
}

private fun getToolKind(toolName: String) = when {
    toolName in setOf(
        "file_list",
        "file_read",
        "file_write",
        "file_edit",
        "file_search",
        "file_move"
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

private fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

private fun String.compactToolPreview(maxLength: Int = 34): String {
    val compact = trim().replace(Regex("\\s+"), " ")
    return if (compact.length > maxLength) compact.take(maxLength - 1) + "…" else compact
}

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
private fun AgentToolCallCapsule(
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
) {
    val workspace = workspaceColors()
    val tone = toolStatusTone(status)
    val shape = RoundedCornerShape(10.dp)
    val kindDescription = toolKindLabel(kind, toolName)
    Surface(
        modifier = modifier
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
        color = workspace.paper,
        contentColor = workspace.ink,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkspaceLeadingIcon(
                    icon = icon,
                    size = 28.dp,
                    iconSize = 15.dp,
                    tone = tone,
                )

                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.shimmer(isLoading = loading && status == AgentToolStatus.RUNNING),
                    )
                    Text(
                        text = subtitle ?: "$kindDescription · ${toolName.compactToolPreview(28)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (approvalActions != null) {
                    approvalActions()
                } else {
                    ToolStatusPill(status = status)
                }
            }
            // Note: dropped the LinearProgressIndicator below the row. Title-level shimmer
            // (line 409) plus the running status pill on the right already convey "in flight";
            // the indeterminate bar's segmented sweep (Material3 1.3+ default) read as stuttery
            // against this tight capsule layout and added visual noise without info gain.
        }
    }
}

@Composable
private fun toolDisplayTitle(
    toolName: String,
    arguments: JsonElement,
    memoryAction: String?,
): String = when (toolName) {
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
    "file_write" -> "写入文件 ${arguments.getStringContent("path")?.compactToolPreview(22).orEmpty()}"
    "file_edit" -> "编辑文件 ${arguments.getStringContent("path")?.compactToolPreview(22).orEmpty()}"
    "file_search" -> "搜索文件 ${arguments.getStringContent("query")?.compactToolPreview(22).orEmpty()}"
    "file_move" -> "移动文件 ${arguments.getStringContent("from")?.compactToolPreview(16).orEmpty()}"
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
    val arguments = remember(tool.input) { tool.inputAsJson() }
    val memoryAction = arguments.getStringContent("action")
    val content = remember(tool.isExecuted, tool.output) {
        if (tool.isExecuted) {
            runCatching {
                JsonInstant.parseToJsonElement(
                    tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                )
            }.getOrElse { JsonObject(emptyMap()) }
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
                        // Show search result thumbnails inline on the collapsed card
                        val searchImages = content?.jsonObject?.get("available_images")
                            ?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?.take(3)
                            .orEmpty()
                        if (searchImages.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                searchImages.forEach { imgUrl ->
                                    ZoomableAsyncImage(
                                        model = imgUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .height(48.dp)
                                            .widthIn(max = 72.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                }
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

@Composable
private fun ToolCallPreviewSheet(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
    output: List<UIMessagePart>,
    onDismissRequest: () -> Unit = {}
) {
    val memoryRepo: MemoryRepository = koinInject()
    val scope = rememberCoroutineScope()

    val memoryAction = arguments.getStringContent("action")
    val isMemoryOperation = toolName == ToolNames.MEMORY &&
        memoryAction in listOf(MemoryActions.CREATE, MemoryActions.EDIT)
    val memoryId = (content as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismissRequest,
        content = {
            when {
                content == null -> GenericToolPreview(
                    toolName = toolName,
                    arguments = arguments,
                    output = emptyList(),
                    isMemoryOperation = false,
                    memoryId = null,
                    memoryRepo = memoryRepo,
                    scope = scope,
                    onDismissRequest = onDismissRequest
                )

                toolName == ToolNames.SEARCH_WEB -> SearchWebPreview(
                    arguments = arguments,
                    content = content,
                )

                toolName == ToolNames.SCRAPE_WEB -> ScrapeWebPreview(content = content)
                else -> GenericToolPreview(
                    toolName = toolName,
                    arguments = arguments,
                    output = output,
                    isMemoryOperation = isMemoryOperation,
                    memoryId = memoryId,
                    memoryRepo = memoryRepo,
                    scope = scope,
                    onDismissRequest = onDismissRequest
                )
            }
        },
    )
}

@Composable
private fun SearchWebPreview(
    arguments: JsonElement,
    content: JsonElement,
) {
    val context = LocalContext.current
    val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
    val answer = content.getStringContent("answer")
    val query = arguments.getStringContent("query") ?: ""

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(stringResource(R.string.chat_message_tool_search_prefix, query))
        }

        if (answer != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    MarkdownBlock(
                        content = answer,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Top-level image thumbnails (from available_images)
        val topImages = content.jsonObject["available_images"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.take(5)
            .orEmpty()
        if (topImages.isNotEmpty()) {
            item {
                Text(
                    text = "相关图片 (${topImages.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    topImages.forEach { imgUrl ->
                        ZoomableAsyncImage(
                            model = imgUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .height(64.dp)
                                .widthIn(max = 100.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }

        if (items.isNotEmpty()) {
            items(items) { item ->
                val url = item.getStringContent("url") ?: return@items
                val title = item.getStringContent("title") ?: return@items
                val text = item.getStringContent("text") ?: return@items
                val itemImages = runCatching {
                    item.jsonObject["images"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                }.getOrNull().orEmpty()

                Card(
                    onClick = { context.openUrl(url) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Favicon(
                                url = url,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = title, maxLines = 1)
                                Text(
                                    text = text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = url,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        // Per-item images
                        if (itemImages.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                itemImages.take(2).forEach { imgUrl ->
                                    ZoomableAsyncImage(
                                        model = imgUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .height(56.dp)
                                            .widthIn(max = 80.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item {
                HighlightText(
                    code = JsonInstantPretty.encodeToString(content),
                    language = "json",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ScrapeWebPreview(content: JsonElement) {
    val urls = content.jsonObject["urls"]?.jsonArray ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(
                    R.string.chat_message_tool_scrape_prefix,
                    urls.joinToString(", ") { it.getStringContent("url") ?: "" }
                )
            )
        }

        items(urls) { url ->
            val urlObject = url.jsonObject
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = urlObject["url"]?.jsonPrimitive?.content ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                )
                Card {
                    MarkdownBlock(
                        content = urlObject["content"]?.jsonPrimitive?.content ?: "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GenericToolPreview(
    toolName: String,
    arguments: JsonElement,
    output: List<UIMessagePart>,
    isMemoryOperation: Boolean,
    memoryId: Int?,
    memoryRepo: MemoryRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismissRequest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            if (isMemoryOperation && memoryId != null) {
                IconButton(
                    onClick = {
                        scope.launch {
                            memoryRepo.deleteMemory(memoryId)
                            onDismissRequest()
                        }
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = "Delete memory"
                    )
                }
            }
        }
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_label, toolName))
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                forceAutoWrap = true,
            )
        }
        if (output.isNotEmpty()) {
            FormItem(
                label = {
                    Text(stringResource(R.string.chat_message_tool_call_result))
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    output.fastForEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> HighlightCodeBlock(
                                code = runCatching {
                                    JsonInstantPretty.encodeToString(
                                        JsonInstant.parseToJsonElement(part.text)
                                    )
                                }.getOrElse { part.text },
                                language = "json",
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                                forceAutoWrap = true,
                            )

                            is UIMessagePart.Image -> ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = remember(tool.input) { tool.inputAsJson() }
    val answeredAnswers = remember(tool.approvalState) {
        val state = tool.approvalState
        if (state is ToolApprovalState.Answered) {
            runCatching {
                JsonInstant.parseToJsonElement(state.answer)
                    .jsonObject["answers"]?.jsonObject
            }.getOrNull()
        } else {
            null
        }
    }

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }
    val workspace = workspaceColors()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                questions.forEachIndexed { index, q ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                            thickness = 0.5.dp,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onToolAnswer != null) {
                            when (q.selectionType) {
                                "single" -> {
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                AskOptionChip(
                                                    selected = answers[q.id] == option,
                                                    label = option,
                                                    onClick = { answers[q.id] = option },
                                                )
                                            }
                                        }
                                    }
                                }
                                "multi" -> {
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                val selectedSet = multiAnswers[q.id] ?: emptySet()
                                                AskOptionChip(
                                                    selected = selectedSet.contains(option),
                                                    label = option,
                                                    onClick = {
                                                        val current = selectedSet.toMutableSet()
                                                        if (current.contains(option)) current.remove(option)
                                                        else current.add(option)
                                                        multiAnswers[q.id] = current
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                AskOptionChip(
                                                    selected = answers[q.id] == option,
                                                    label = option,
                                                    onClick = { answers[q.id] = option },
                                                )
                                            }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = answers[q.id] ?: "",
                                        onValueChange = { answers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        placeholder = {
                                            Text(
                                                text = "在此输入回答…",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                            )
                                        },
                                        singleLine = false,
                                        minLines = 2,
                                        maxLines = 6,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                                            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                        ),
                                    )
                                }
                            }
                        } else if (isAnswered) {
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerJson = answeredAnswers?.get(q.id)
                            val answerText = when {
                                answerJson is JsonArray -> answerJson.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
                                    .joinToString(" · ")
                                answerJson != null -> answerJson.jsonPrimitiveOrNull?.contentOrNull
                                    ?: answeredState.answer
                                else -> answeredState.answer
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                border = BorderStroke(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    ) {
                                        Text(
                                            text = "A",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    Text(
                                        text = answerText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                if (isPending && onToolAnswer != null) {
                    val anyAnswered = answers.values.any { it.isNotBlank() } ||
                        multiAnswers.values.any { it.isNotEmpty() }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (anyAnswered) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        answers.clear()
                                        multiAnswers.clear()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Refresh01,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                )
                                Text(
                                    text = "清空",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Button(
                            onClick = {
                                val answerPayload = buildJsonObject {
                                    put("answers", buildJsonObject {
                                        questions.forEach { q ->
                                            when (q.selectionType) {
                                                "multi" -> put(
                                                    q.id,
                                                    buildJsonArray {
                                                        multiAnswers[q.id].orEmpty().forEach { add(JsonPrimitive(it)) }
                                                    }
                                                )
                                                else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                            }
                                        }
                                    })
                                }
                                onToolAnswer(tool.toolCallId, answerPayload.toString())
                            },
                            enabled = questions.all { q ->
                                when (q.selectionType) {
                                    "multi" -> !multiAnswers[q.id].isNullOrEmpty()
                                    else -> !answers[q.id].isNullOrBlank()
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Tick01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.chat_message_tool_submit),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun AskOptionChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )
        },
        tonalElevation = 0.dp,
        shadowElevation = if (selected) 1.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (selected) {
                Icon(
                    imageVector = HugeIcons.Tick01,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
            )
        }
    }
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * Render a coalesced subagent task: one card replaces the multiple subagent_* tool calls
 * (start / wait / read / cancel) that share a run_id.
 *
 * Status is derived from the parsed `status` field of the latest wait/read output (most
 * authoritative view of the subagent's actual state); falls back to RUNNING if no result
 * has arrived yet. While running, cycles through the role's [phaseLabels] every few seconds
 * to give a sense of progression. Phase 5 wires the click to an expandable run sheet.
 */
@Composable
fun SubAgentTaskStepView(
    step: ThinkingStep.SubAgentTaskStep,
    loading: Boolean,
) {
    var showSheet by remember(step.runId) { mutableStateOf(false) }
    val anchor = step.anchor
    val arguments = remember(anchor.input) { anchor.inputAsJson() }
    val subagentId = arguments.getStringContent("subagent_id") ?: "subagent"
    val def = remember(subagentId) { SubAgentDefinitions.find(subagentId) }
    val customName = arguments.jsonObjectOrNull
        ?.payloadObject("custom_subagent")
        ?.getStringContent("name")
        ?.takeIf { it.isNotBlank() }
    val displayName = extractLatestSubAgentName(step.tools)
        ?: customName
        ?: def?.name
        ?: subagentId

    // coalesceSubAgentSteps rebuilds step.tools each render even when contents are identical,
    // so remember(step.tools) wouldn't actually memoize — drop the wrap; the parse is cheap.
    val parsedStatus = parseLatestSubAgentStatus(step.tools)
    val isRunning = parsedStatus == SubAgentRunStatus.RUNNING

    val phaseLabels = def?.phaseLabels.orEmpty()
    // Reset cycle when role's phaseLabels list changes (mid-run edits to a custom role would
    // otherwise leave phaseIndex pointing past the new list's end).
    var phaseIndex by remember(step.runId, phaseLabels) { mutableIntStateOf(0) }
    LaunchedEffect(step.runId, isRunning, phaseLabels.size) {
        if (!isRunning || phaseLabels.size <= 1) return@LaunchedEffect
        while (true) {
            delay(PHASE_LABEL_INTERVAL_MS)
            phaseIndex = (phaseIndex + 1) % phaseLabels.size
        }
    }
    val currentPhase = when {
        phaseLabels.isEmpty() -> ""
        isRunning -> phaseLabels[phaseIndex.coerceIn(phaseLabels.indices)]
        else -> phaseLabels.last()
    }

    val statusVerb = when (parsedStatus) {
        SubAgentRunStatus.RUNNING -> "正在工作"
        SubAgentRunStatus.COMPLETED -> "已完成"
        SubAgentRunStatus.FAILED -> "失败"
        SubAgentRunStatus.CANCELLED -> "已取消"
        SubAgentRunStatus.TIMED_OUT -> "超时"
        SubAgentRunStatus.APPROVAL_REQUIRED -> "等待审批"
        SubAgentRunStatus.INTERRUPTED -> "已中断"
    }
    val title = if (isRunning && currentPhase.isNotBlank()) {
        "@$displayName $statusVerb · $currentPhase"
    } else {
        "@$displayName $statusVerb"
    }

    AgentToolCallCapsule(
        title = title,
        toolName = "subagent_task",
        icon = HugeIcons.MagicWand01,
        kind = AgentToolKind.GENERIC,
        status = parsedStatus.toAgentToolStatus(),
        loading = loading && isRunning,
        onClick = { showSheet = true },
        approvalActions = null,
    )

    if (showSheet) {
        // Sheet derives its own status — sheet may outlive the outer card's recomposition
        // (e.g. card scrolled offscreen in a LazyColumn while sheet is still open), so we
        // can't rely on parent-passed verb staying fresh.
        SubAgentRunSheet(
            step = step,
            displayName = displayName,
            onDismiss = { showSheet = false },
        )
    }
}

private const val PHASE_LABEL_INTERVAL_MS = 5_000L

/**
 * Walk the subagent_* tools in reverse order and pick the most recent parsable `status` from
 * the result payload. Returns RUNNING if no result has been observed yet (initial state).
 */
private fun parseLatestSubAgentStatus(tools: List<UIMessagePart.Tool>): SubAgentRunStatus {
    for (tool in tools.asReversed()) {
        val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
            ?: continue
        // Use `as?` casts (same defensive pattern as parseLatestCouncilStatus) — runCatching
        // caught the throws before, but the cast version is clearer about intent and fails open.
        val statusStr = runCatching {
            (JsonInstant.parseToJsonElement(outputText) as? JsonObject)?.get("status")
                ?.let { it as? JsonPrimitive }?.contentOrNull
        }.getOrNull() ?: continue
        SubAgentRunStatus.entries.firstOrNull { it.name.equals(statusStr, ignoreCase = true) }
            ?.let { return it }
    }
    return SubAgentRunStatus.RUNNING
}

private fun extractLatestSubAgentName(tools: List<UIMessagePart.Tool>): String? {
    for (tool in tools.asReversed()) {
        val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
            ?: continue
        val parsed = runCatching {
            JsonInstant.parseToJsonElement(outputText) as? JsonObject
        }.getOrNull() ?: continue
        val name = parsed.getStringContent("subagent_name")
        if (!name.isNullOrBlank()) return name
    }
    return null
}

private fun SubAgentRunStatus.toAgentToolStatus(): AgentToolStatus = when (this) {
    SubAgentRunStatus.RUNNING -> AgentToolStatus.RUNNING
    SubAgentRunStatus.APPROVAL_REQUIRED -> AgentToolStatus.WAITING_FOR_PERMISSION
    SubAgentRunStatus.COMPLETED -> AgentToolStatus.SUCCEEDED
    SubAgentRunStatus.FAILED,
    SubAgentRunStatus.TIMED_OUT,
    SubAgentRunStatus.INTERRUPTED -> AgentToolStatus.FAILED
    SubAgentRunStatus.CANCELLED -> AgentToolStatus.CANCELLED
}

/**
 * Bottom sheet showing the subagent's full task spec and live streaming output. Subscribes to
 * [SubAgentManager.liveTextFlow] so the body updates token-by-token as the run progresses.
 *
 * Fallback: if the live flow is empty (e.g. app was killed and the conversation reopened later,
 * so the manager no longer holds a flow for this run), pull the final summary out of the latest
 * subagent_wait/read tool result. Best-effort — if neither has anything we just show "等待输出".
 */
@Composable
private fun SubAgentRunSheet(
    step: ThinkingStep.SubAgentTaskStep,
    displayName: String,
    onDismiss: () -> Unit,
) {
    val manager: SubAgentManager = koinInject()
    val context = LocalContext.current
    val workspace = workspaceColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val anchor = step.anchor
    val arguments = remember(anchor.input) { anchor.inputAsJson() }
    val taskObjective = remember(arguments) {
        runCatching {
            arguments.jsonObject["task"]?.jsonObject?.get("objective")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    // Re-derive status here (instead of receiving from parent) so the sheet stays correct even
    // when the outer card is offscreen and not recomposing.
    val parsedStatus = parseLatestSubAgentStatus(step.tools)
    val isRunning = parsedStatus == SubAgentRunStatus.RUNNING
    val statusVerb = when (parsedStatus) {
        SubAgentRunStatus.RUNNING -> "正在工作"
        SubAgentRunStatus.COMPLETED -> "已完成"
        SubAgentRunStatus.FAILED -> "失败"
        SubAgentRunStatus.CANCELLED -> "已取消"
        SubAgentRunStatus.TIMED_OUT -> "超时"
        SubAgentRunStatus.APPROVAL_REQUIRED -> "等待审批"
        SubAgentRunStatus.INTERRUPTED -> "已中断"
    }

    // Live flow may be null when the manager has no record of this run (process restart, eviction).
    // In that case create an empty fallback flow so collectAsState works.
    val liveFlow = remember(step.runId) { manager.liveTextFlow(step.runId) ?: MutableStateFlow("") }
    val liveText by liveFlow.collectAsState()
    val snapshotRun = manager.snapshot(step.runId)
    val snapshotText = snapshotRun?.displayText.orEmpty()
    var transcriptText by remember(step.runId) { mutableStateOf("") }
    val finalText = extractFinalSubAgentText(step.tools)
    LaunchedEffect(step.runId, parsedStatus, liveText, snapshotText) {
        val mayBeStaleRunningAfterRestart = isRunning && snapshotRun == null
        if ((!isRunning || mayBeStaleRunningAfterRestart) &&
            liveText.isBlank() &&
            snapshotText.isBlank() &&
            transcriptText.isBlank()
        ) {
            transcriptText = extractSubAgentDisplayTextFromTranscript(
                tools = step.tools,
                runRoot = File(context.filesDir, "amberagent/subagents/runs"),
            )
        }
    }
    val displayText = liveText.ifBlank { snapshotText.ifBlank { transcriptText.ifBlank { finalText } } }

    val scrollState = rememberScrollState()
    var followBottom by remember(step.runId) { mutableStateOf(true) }
    LaunchedEffect(scrollState, isRunning) {
        snapshotFlow { scrollState.value to scrollState.maxValue }
            .collect { (value, maxValue) ->
                if (isRunning && value >= (maxValue - 8).coerceAtLeast(0)) {
                    followBottom = true
                }
            }
    }
    // Auto-follow only while the run is active. Once it finishes the user can scroll up freely
    // without being yanked back to the bottom.
    //
    // Scroll spec mirrors ChatList.scrollToTimelineBottom (tween 80ms + LinearEasing).
    // Default spring on ScrollState.animateScrollTo holds isScrollInProgress true for
    // ~1s after the destination is reached, which gates off the next chunk's scroll
    // — same shape of bug as 1.8.9's main-chat scroll regression.
    LaunchedEffect(displayText, isRunning, followBottom) {
        if (isRunning && followBottom) {
            scrollState.animateScrollTo(
                value = scrollState.maxValue,
                animationSpec = tween(durationMillis = 80, easing = LinearEasing),
            )
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .pointerInput(isRunning) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (isRunning) followBottom = false
                    }
                }
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = HugeIcons.MagicWand01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = workspace.muted,
                )
                Text(
                    text = "@$displayName",
                    style = MaterialTheme.typography.titleMedium,
                    color = workspace.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = statusVerb,
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.muted,
                )
            }

            taskObjective?.takeIf { it.isNotBlank() }?.let { objective ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = workspace.row,
                    border = BorderStroke(1.dp, workspace.hairline),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "任务",
                            style = MaterialTheme.typography.labelSmall,
                            color = workspace.faint,
                        )
                        Text(
                            text = objective,
                            style = MaterialTheme.typography.bodySmall,
                            color = workspace.ink,
                        )
                    }
                }
            }

            HorizontalDivider(color = workspace.hairline)

            // Live / final text body — render as Markdown so headings, bold, lists, code,
            // hashtags-as-text etc. all show properly. Falls back to plain "waiting" text when
            // nothing has streamed in yet.
            if (displayText.isBlank()) {
                Text(
                    text = "等待输出...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspace.faint,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                SelectionContainer {
                    MarkdownBlock(
                        content = displayText,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium.copy(color = workspace.ink),
                    )
                }
            }
        }
    }
}

/**
 * Render a coalesced Model Council run: one card replaces the multiple model_council_* tool calls
 * sharing one run_id. Tap → opens [ModelCouncilRunSheet] showing per-seat live streaming output
 * in a tab layout.
 */
@Composable
fun CouncilTaskStepView(
    step: ThinkingStep.CouncilTaskStep,
    loading: Boolean,
) {
    val manager: ModelCouncilManager = koinInject()
    var showSheet by remember(step.runId) { mutableStateOf(false) }
    val parsedStatus = parseLatestCouncilStatus(step.tools)
    val isRunning = parsedStatus == ModelCouncilCardStatus.RUNNING
    val phaseLabels = listOf("听证", "辩论", "权衡", "裁决")
    var phaseIndex by remember(step.runId) { mutableIntStateOf(0) }
    LaunchedEffect(step.runId, isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (true) {
            delay(PHASE_LABEL_INTERVAL_MS)
            phaseIndex = (phaseIndex + 1) % phaseLabels.size
        }
    }
    val currentPhase = if (isRunning) phaseLabels[phaseIndex.coerceIn(phaseLabels.indices)]
        else phaseLabels.last()
    val statusVerb = when (parsedStatus) {
        ModelCouncilCardStatus.RUNNING -> "正在审议"
        ModelCouncilCardStatus.COMPLETED -> "已完成"
        ModelCouncilCardStatus.PARTIAL_FAILED -> "部分失败"
        ModelCouncilCardStatus.FAILED -> "失败"
        ModelCouncilCardStatus.CANCELLED -> "已取消"
        ModelCouncilCardStatus.TIMED_OUT -> "超时"
        ModelCouncilCardStatus.INTERRUPTED -> "已中断"
    }
    val title = if (isRunning) "@Council $statusVerb · $currentPhase" else "@Council $statusVerb"
    val seatSubtitle = remember(step.runId, step.tools) {
        val names = manager.snapshot(step.runId)?.seats
            ?.map { it.name.ifBlank { ModelCouncilRolePresets.byName(it.role)?.name ?: it.role } }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: extractCouncilSeatEntries(step.tools).map { it.label }
        names.takeIf { it.isNotEmpty() }?.joinToString(" / ", prefix = "席位 · ")
    }
    AgentToolCallCapsule(
        title = title,
        toolName = "model_council",
        icon = HugeIcons.MagicWand01,
        kind = AgentToolKind.GENERIC,
        status = parsedStatus.toAgentToolStatus(),
        loading = loading && isRunning,
        onClick = { showSheet = true },
        approvalActions = null,
        subtitle = seatSubtitle,
    )

    if (showSheet) {
        ModelCouncilRunSheet(
            step = step,
            onDismiss = { showSheet = false },
        )
    }
}

private enum class ModelCouncilCardStatus {
    RUNNING, COMPLETED, PARTIAL_FAILED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED
}

/** Walk model_council_* tools in reverse, find the most recent parsable `status`. */
private fun parseLatestCouncilStatus(tools: List<UIMessagePart.Tool>): ModelCouncilCardStatus {
    for (tool in tools.asReversed()) {
        val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
            ?: continue
        val statusStr = runCatching {
            (JsonInstant.parseToJsonElement(outputText) as? JsonObject)?.get("status")
                ?.let { it as? JsonPrimitive }?.contentOrNull
        }.getOrNull() ?: continue
        ModelCouncilCardStatus.entries.firstOrNull { it.name.equals(statusStr, ignoreCase = true) }
            ?.let { return it }
    }
    return ModelCouncilCardStatus.RUNNING
}

private fun ModelCouncilCardStatus.toAgentToolStatus(): AgentToolStatus = when (this) {
    ModelCouncilCardStatus.RUNNING -> AgentToolStatus.RUNNING
    ModelCouncilCardStatus.COMPLETED,
    ModelCouncilCardStatus.PARTIAL_FAILED -> AgentToolStatus.SUCCEEDED
    ModelCouncilCardStatus.FAILED,
    ModelCouncilCardStatus.TIMED_OUT,
    ModelCouncilCardStatus.INTERRUPTED -> AgentToolStatus.FAILED
    ModelCouncilCardStatus.CANCELLED -> AgentToolStatus.CANCELLED
}

/**
 * Bottom sheet showing the council run's task + per-seat live streaming output, organized in
 * tabs. Synthesizer pane is the default tab; each seat (in run order) gets its own tab. Falls
 * back to extracting final text from tool result when the live flow is gone (process restart).
 */
@Composable
private fun ModelCouncilRunSheet(
    step: ThinkingStep.CouncilTaskStep,
    onDismiss: () -> Unit,
) {
    val manager: ModelCouncilManager = koinInject()
    val workspace = workspaceColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val anchor = step.anchor
    val arguments = remember(anchor.input) { anchor.inputAsJson() }
    val taskObjective = remember(arguments) {
        runCatching {
            (arguments.jsonObject["task"] as? JsonObject)?.get("objective")?.let { it as? JsonPrimitive }?.contentOrNull
                ?: arguments.jsonObject["objective"]?.let { it as? JsonPrimitive }?.contentOrNull
        }.getOrNull()
    }

    val parsedStatus = parseLatestCouncilStatus(step.tools)
    val isRunning = parsedStatus == ModelCouncilCardStatus.RUNNING
    val statusVerb = when (parsedStatus) {
        ModelCouncilCardStatus.RUNNING -> "正在审议"
        ModelCouncilCardStatus.COMPLETED -> "已完成"
        ModelCouncilCardStatus.PARTIAL_FAILED -> "部分失败"
        ModelCouncilCardStatus.FAILED -> "失败"
        ModelCouncilCardStatus.CANCELLED -> "已取消"
        ModelCouncilCardStatus.TIMED_OUT -> "超时"
        ModelCouncilCardStatus.INTERRUPTED -> "已中断"
    }

    // Resolve seat list from the snapshot (preserves run order). Synthesizer is appended last.
    val snapshot = remember(step.runId) { manager.snapshot(step.runId) }
    val seatTabs = remember(step.runId, snapshot?.seats, step.tools) {
        val seatEntries = snapshot?.seats?.map { seat ->
            CouncilTabEntry(
                key = seat.seatId,
                label = seat.name.ifBlank { ModelCouncilRolePresets.byName(seat.role)?.name ?: seat.role },
            )
        }?.takeIf { it.isNotEmpty() } ?: extractCouncilSeatEntries(step.tools)
        // Synthesizer pane is conceptually the "verdict"; show it first so the user sees the
        // bottom-line answer immediately when the run finishes.
        listOf(CouncilTabEntry(ModelCouncilManager.SYNTHESIZER_SEAT_KEY, "综合裁决")) + seatEntries
    }

    // While the run is still going, default to the FIRST seat tab (index 1) instead of the
    // synthesizer (index 0) — the synthesizer has nothing to show until debate is over.
    // After completion, jump to synthesizer (index 0) for the verdict.
    val initialTab = if (isRunning && seatTabs.size > 1) 1 else 0
    var selectedTab by remember(step.runId, isRunning) { mutableIntStateOf(initialTab) }
    val safeSelected = selectedTab.coerceIn(0, (seatTabs.size - 1).coerceAtLeast(0))

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = HugeIcons.MagicWand01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = workspace.muted,
                )
                Text(
                    text = "@Council",
                    style = MaterialTheme.typography.titleMedium,
                    color = workspace.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = statusVerb,
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.muted,
                )
            }

            taskObjective?.takeIf { it.isNotBlank() }?.let { objective ->
                CouncilObjectiveCard(objective = objective)
            }

            if (seatTabs.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = safeSelected,
                    edgePadding = 0.dp,
                    containerColor = workspace.paper,
                    contentColor = workspace.ink,
                ) {
                    seatTabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = index == safeSelected,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
            }

            HorizontalDivider(color = workspace.hairline)

            // Active tab body — subscribe to the seat's live flow + auto-scroll while running.
            val activeSeatKey = seatTabs.getOrNull(safeSelected)?.key
            val seatFlow = remember(step.runId, activeSeatKey) {
                activeSeatKey?.let { manager.liveTextFlow(step.runId, it) } ?: MutableStateFlow("")
            }
            val liveText by seatFlow.collectAsState()
            val finalText = remember(step.tools, activeSeatKey) {
                if (activeSeatKey == ModelCouncilManager.SYNTHESIZER_SEAT_KEY) {
                    extractFinalCouncilSynthesisText(step.tools)
                } else if (activeSeatKey != null) {
                    extractFinalCouncilSeatText(step.tools, activeSeatKey)
                } else ""
            }
            val displayTextSource = if (isRunning) {
                liveText.ifBlank { finalText }
            } else {
                finalText.ifBlank { liveText }
            }
            val displayText = displayTextSource.cleanCouncilLineBreaks()
            val activeModelLabel = remember(step.tools, activeSeatKey) {
                activeSeatKey?.let { extractCouncilModelLabel(step.tools, it) }.orEmpty()
            }
            val scrollState = rememberScrollState()
            var followBottom by remember(step.runId, activeSeatKey) { mutableStateOf(true) }
            LaunchedEffect(scrollState, isRunning, activeSeatKey) {
                snapshotFlow { scrollState.value to scrollState.maxValue }
                    .collect { (value, maxValue) ->
                        if (isRunning && value >= (maxValue - 8).coerceAtLeast(0)) {
                            followBottom = true
                        }
                    }
            }
            // Scroll spec aligned with ChatList.scrollToTimelineBottom — tween 80ms,
            // LinearEasing. Default spring's long settle holds isScrollInProgress and
            // would gate off subsequent chunks (same regression shape 1.8.10 fixed
            // on the main chat path).
            LaunchedEffect(displayText, isRunning, activeSeatKey, followBottom) {
                if (isRunning && followBottom) {
                    scrollState.animateScrollTo(
                        value = scrollState.maxValue,
                        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
                    )
                }
            }
            if (activeModelLabel.isNotBlank()) {
                Text(
                    text = activeModelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(isRunning, activeSeatKey) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            if (isRunning) followBottom = false
                        }
                    }
                    .verticalScroll(scrollState),
            ) {
                if (displayText.isBlank()) {
                    Text(
                        text = if (isRunning) "等待此席位输出..." else "（无输出）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = workspace.faint,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    SelectionContainer(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CouncilRoundContent(
                            content = displayText,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private data class CouncilTabEntry(val key: String, val label: String)

private data class CouncilRoundSection(
    val label: String?,
    val content: String,
)

@Composable
private fun CouncilRoundContent(
    content: String,
    modifier: Modifier = Modifier,
) {
    val sections = remember(content) { content.toCouncilRoundSections() }
    val workspace = workspaceColors()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        sections.forEach { section ->
            section.label?.let { CouncilRoundDivider(label = it) }
            if (section.content.isNotBlank()) {
                MarkdownBlock(
                    content = section.content,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium.copy(color = workspace.ink),
                )
            }
        }
    }
}

@Composable
private fun CouncilRoundDivider(label: String) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = workspace.blue.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, workspace.blue.copy(alpha = 0.24f)),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = workspace.blue,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = workspace.blue.copy(alpha = 0.20f),
        )
    }
}

@Composable
private fun CouncilObjectiveCard(objective: String) {
    val workspace = workspaceColors()
    val shouldCollapse = remember(objective) { objective.shouldCollapseCouncilObjective() }
    var expanded by remember(objective) { mutableStateOf(!shouldCollapse) }
    val objectiveScrollState = rememberScrollState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = shouldCollapse) { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        color = workspace.row,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "议题",
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.faint,
                    modifier = Modifier.weight(1f),
                )
                if (shouldCollapse) {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.blue,
                    )
                }
            }
            Text(
                text = objective,
                style = MaterialTheme.typography.bodySmall,
                color = workspace.ink,
                maxLines = if (expanded) Int.MAX_VALUE else COUNCIL_OBJECTIVE_COLLAPSED_LINES,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (expanded && shouldCollapse) {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(objectiveScrollState)
                } else {
                    Modifier.fillMaxWidth()
                },
            )
        }
    }
}

private fun String.shouldCollapseCouncilObjective(): Boolean {
    val lineCount = lineSequence().count()
    return length > COUNCIL_OBJECTIVE_COLLAPSE_CHARS || lineCount > COUNCIL_OBJECTIVE_COLLAPSE_LINES
}

private fun String.toCouncilRoundSections(): List<CouncilRoundSection> {
    if (!contains(COUNCIL_ROUND_MARKER_TEXT)) {
        return listOf(CouncilRoundSection(label = null, content = this))
    }
    val sections = mutableListOf<CouncilRoundSection>()
    var label: String? = null
    val body = StringBuilder()

    fun flush() {
        val content = body.toString().trim('\n')
        if (label != null || content.isNotBlank()) {
            sections += CouncilRoundSection(label = label, content = content)
        }
        body.clear()
    }

    lineSequence().forEach { line ->
        val match = COUNCIL_ROUND_MARKER.matchEntire(line.trim())
        if (match != null) {
            flush()
            label = match.groupValues[1].replace(Regex("""\s+"""), " ")
        } else {
            body.appendLine(line)
        }
    }
    flush()

    return sections.ifEmpty { listOf(CouncilRoundSection(label = null, content = this)) }
}

private const val COUNCIL_OBJECTIVE_COLLAPSED_LINES = 3
private const val COUNCIL_OBJECTIVE_COLLAPSE_LINES = 5
private const val COUNCIL_OBJECTIVE_COLLAPSE_CHARS = 180
private const val COUNCIL_ROUND_MARKER_TEXT = "--- 第"
private val COUNCIL_ROUND_MARKER = Regex("""---\s*(第\s*\d+\s*轮)\s*---""")

/** Pull the synthesizer's final text from the latest read/wait result (`result.finalRecommendation`). */
private fun extractFinalCouncilSynthesisText(tools: List<UIMessagePart.Tool>): String {
    for (tool in tools.asReversed()) {
        val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
            ?: continue
        val parsed = runCatching {
            JsonInstant.parseToJsonElement(outputText) as? JsonObject
        }.getOrNull() ?: continue
        val result = parsed.payloadObject("result") ?: continue
        val recommendation = ((result["final_recommendation"] as? JsonPrimitive)?.contentOrNull)
            ?.takeIf { it.isNotBlank() }
            ?: ((result["error"] as? JsonPrimitive)?.contentOrNull)
        if (!recommendation.isNullOrBlank()) return recommendation
    }
    return ""
}

/** Pull the provider/model label for a council seat or synthesizer from the latest payload. */
private fun extractCouncilModelLabel(tools: List<UIMessagePart.Tool>, seatId: String): String {
    for (tool in tools.asReversed()) {
        val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
            ?: continue
        val parsed = runCatching {
            JsonInstant.parseToJsonElement(outputText) as? JsonObject
        }.getOrNull() ?: continue
        val labels = parsed["seat_model_labels"] as? JsonObject
        val direct = (labels?.get(seatId) as? JsonPrimitive)?.contentOrNull
        if (!direct.isNullOrBlank()) return direct

        val parsedTurns = parsed.payloadArray("turns") ?: continue
        parsedTurns.forEach { turnElement ->
            val turn = turnElement as? JsonObject ?: return@forEach
            val tSeatId = (turn["seat_id"] as? JsonPrimitive)?.contentOrNull
            if (tSeatId != seatId) return@forEach
            val providerName = (turn["provider_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val modelName = (turn["model_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val label = listOf(providerName, modelName)
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            if (label.isNotBlank()) return label
        }
    }
    return ""
}

private fun extractCouncilSeatEntries(tools: List<UIMessagePart.Tool>): List<CouncilTabEntry> {
    for (tool in tools.asReversed()) {
        val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
            ?: continue
        val parsed = runCatching {
            JsonInstant.parseToJsonElement(outputText) as? JsonObject
        }.getOrNull() ?: continue
        val parsedTurns = parsed.payloadArray("turns") ?: continue
        val entries = linkedMapOf<String, String>()
        parsedTurns.forEach { turnElement ->
            val turn = turnElement as? JsonObject ?: return@forEach
            val seatId = (turn["seat_id"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val name = (turn["seat_name"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            entries.putIfAbsent(seatId, name)
        }
        if (entries.isNotEmpty()) {
            return entries.map { (seatId, name) -> CouncilTabEntry(key = seatId, label = name) }
        }
    }
    return emptyList()
}

/**
 * Pull a specific seat's content across ALL rounds from the latest tool result, joined the same
 * way the live flow renders them (with `--- 第 N 轮 ---` separators between rounds 2+). Walking
 * tools in reverse means we pick the most recent (richest) turns array; within it, all matching
 * turns are kept in original order.
 */
private fun extractFinalCouncilSeatText(tools: List<UIMessagePart.Tool>, seatId: String): String {
    for (tool in tools.asReversed()) {
        val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
            ?: continue
        val parsed = runCatching {
            JsonInstant.parseToJsonElement(outputText) as? JsonObject
        }.getOrNull() ?: continue
        val parsedTurns = parsed.payloadArray("turns") ?: continue
        val matching = parsedTurns.mapNotNull { turnElement ->
            val turn = turnElement as? JsonObject ?: return@mapNotNull null
            val tSeatId = (turn["seat_id"] as? JsonPrimitive)?.contentOrNull
            if (tSeatId != seatId) return@mapNotNull null
            val round = (turn["round"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
            val warnings = (turn["warnings"] as? JsonArray)?.mapNotNull { warning ->
                (warning as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }.orEmpty()
            val content = (turn["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (turn["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { "失败：$it" }
                ?: return@mapNotNull null
            val text = buildString {
                warnings.forEach { warning -> appendLine("提示：$warning") }
                if (warnings.isNotEmpty()) appendLine()
                append(content)
            }
            round to text
        }.sortedBy { it.first }
        if (matching.isNotEmpty()) {
            return matching.joinToString("\n\n") { (round, content) ->
                "--- 第 $round 轮 ---\n\n$content"
            }
        }
    }
    return ""
}

private fun JsonObject.payloadObject(name: String): JsonObject? {
    val value = this[name] ?: return null
    return (value as? JsonObject)
        ?: (value as? JsonPrimitive)?.contentOrNull?.let { raw ->
            runCatching { JsonInstant.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        }
}

private fun JsonObject.payloadArray(name: String): JsonArray? {
    val value = this[name] ?: return null
    return (value as? JsonArray)
        ?: (value as? JsonPrimitive)?.contentOrNull?.let { raw ->
            runCatching { JsonInstant.parseToJsonElement(raw).jsonArray }.getOrNull()
        }
}

/**
 * Pull the canonical final-text field from the latest tool result.
 *
 * Note: [me.rerere.rikkahub.data.agent.subagent.SubAgentManager.runToPayload] encodes the
 * `SubAgentResult` as a **JSON-encoded string field** (calls `json.encodeToString(result)` and
 * stores the resulting string), not a nested object. So `payload["result"]` is a
 * `JsonPrimitive` carrying serialized JSON text — must be re-parsed before reading `summary`.
 * Every cast uses `as?` to avoid `Element X is not a JsonObject` crashes on partial / shaped-
 * differently outputs.
 */
private fun extractFinalSubAgentText(tools: List<UIMessagePart.Tool>): String {
    for (tool in tools.asReversed()) {
        val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
            ?: continue
        val parsed = runCatching {
            JsonInstant.parseToJsonElement(outputText) as? JsonObject
        }.getOrNull() ?: continue
        val resultStr = (parsed["result"] as? JsonPrimitive)?.contentOrNull
        val nestedSummary = resultStr?.let { rs ->
            runCatching {
                ((JsonInstant.parseToJsonElement(rs) as? JsonObject)?.get("summary")
                    as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
        }
        val summary = nestedSummary
            ?: (parsed["summary"] as? JsonPrimitive)?.contentOrNull
        if (!summary.isNullOrBlank()) return summary
    }
    return ""
}

private suspend fun extractSubAgentDisplayTextFromTranscript(
    tools: List<UIMessagePart.Tool>,
    runRoot: File,
): String =
    withContext(Dispatchers.IO) {
        val transcriptPath = tools.asReversed().firstNotNullOfOrNull { tool ->
            val outputText = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
                ?: return@firstNotNullOfOrNull null
            runCatching {
                val parsed = JsonInstant.parseToJsonElement(outputText) as? JsonObject
                    ?: return@runCatching null
                val explicitPath = (parsed["transcript_path"] as? JsonPrimitive)?.contentOrNull
                explicitPath ?: (parsed["run_id"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runId -> File(runRoot, "$runId.jsonl").absolutePath }
            }.getOrNull()
        } ?: return@withContext ""

        readSubAgentDisplayTextFromTranscript(transcriptPath, runRoot)
    }

/**
 * Fix legacy council text where [UIMessage.toText] joined streaming deltas with "\n" separators,
 * producing single-character lines like "根据\n常见\n控\n糖\n...". Collapses spurious line breaks
 * while preserving intentional paragraph breaks (double newlines) and Markdown structure.
 */
private fun String.cleanCouncilLineBreaks(): String {
    if (!contains('\n')) return this
    val lines = lines()
    val shortLineRatio = lines.count { it.trim().length in 1..3 }.toFloat() / lines.size.coerceAtLeast(1)
    if (shortLineRatio < 0.35f) return this
    return replace(Regex("""(?<!\n)\n(?!\n)""")) { match ->
        val before = getOrNull(match.range.first - 1)
        val after = getOrNull(match.range.last + 1)
        if (before in listOf(':', '-', '。', '！', '？', '.', '!', '?') || after == '#' || after == '-' || after == '*') "\n"
        else ""
    }
}
