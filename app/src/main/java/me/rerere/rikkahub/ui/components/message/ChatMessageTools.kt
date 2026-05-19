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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
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
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.agent.subagent.SubAgentDefinitions
import me.rerere.rikkahub.data.agent.subagent.SubAgentManager
import me.rerere.rikkahub.data.agent.subagent.SubAgentRunStatus
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.components.ui.WorkspaceIconButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceLeadingIcon
import me.rerere.rikkahub.ui.components.ui.WorkspaceStatusPill
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject
import java.io.File

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

internal fun JsonElement?.getStringContent(key: String): String? =
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

internal const val PHASE_LABEL_INTERVAL_MS = 5_000L

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
