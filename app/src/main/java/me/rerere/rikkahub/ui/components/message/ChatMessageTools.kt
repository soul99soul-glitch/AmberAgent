package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject

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
    "webview_open" -> HugeIcons.GlobalSearch
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

    else -> if (toolName.startsWith("mcp__")) HugeIcons.Package else HugeIcons.Tools
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

    toolName == ToolNames.SEARCH_WEB || toolName == ToolNames.SCRAPE_WEB || toolName == "webview_open" || toolName == "webview_read" -> AgentToolKind.WEB
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
    return !error.isNullOrBlank() ||
        (exitCode != null && exitCode != 0) ||
        output.filterIsInstance<UIMessagePart.Text>().any { part ->
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

private data class AgentToolColors(
    val container: Color,
    val content: Color,
    val statusContainer: Color,
    val statusContent: Color,
)

@Composable
private fun agentToolColors(status: AgentToolStatus): AgentToolColors = when (status) {
    AgentToolStatus.RUNNING -> AgentToolColors(
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        statusContainer = MaterialTheme.colorScheme.primary,
        statusContent = MaterialTheme.colorScheme.onPrimary,
    )

    AgentToolStatus.WAITING_FOR_PERMISSION -> AgentToolColors(
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        statusContainer = MaterialTheme.colorScheme.tertiary,
        statusContent = MaterialTheme.colorScheme.onTertiary,
    )

    AgentToolStatus.SUCCEEDED -> AgentToolColors(
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        statusContainer = MaterialTheme.colorScheme.surface,
        statusContent = MaterialTheme.colorScheme.secondary,
    )

    AgentToolStatus.FAILED -> AgentToolColors(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        statusContainer = MaterialTheme.colorScheme.error,
        statusContent = MaterialTheme.colorScheme.onError,
    )

    AgentToolStatus.CANCELLED -> AgentToolColors(
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        statusContainer = MaterialTheme.colorScheme.surface,
        statusContent = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun toolStatusLabel(status: AgentToolStatus): String = when (status) {
    AgentToolStatus.RUNNING -> "执行中"
    AgentToolStatus.WAITING_FOR_PERMISSION -> "待授权"
    AgentToolStatus.SUCCEEDED -> "成功"
    AgentToolStatus.FAILED -> "失败"
    AgentToolStatus.CANCELLED -> "已取消"
}

@Composable
private fun toolKindLabel(kind: AgentToolKind, toolName: String): String = when (kind) {
    AgentToolKind.FILE -> "文件"
    AgentToolKind.TERMINAL -> "终端"
    AgentToolKind.MCP -> "MCP"
    AgentToolKind.SCREEN -> "屏幕"
    AgentToolKind.WEB -> "网页"
    AgentToolKind.MEMORY -> "记忆"
    AgentToolKind.GENERIC -> toolName
}

@Composable
private fun ToolStatusPill(
    status: AgentToolStatus,
    modifier: Modifier = Modifier,
) {
    val colors = agentToolColors(status)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.statusContainer,
        contentColor = colors.statusContent,
    ) {
        Text(
            text = toolStatusLabel(status),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
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
) {
    val colors = agentToolColors(status)
    val shape = RoundedCornerShape(24.dp)
    val kindDescription = toolKindLabel(kind, toolName)
    Surface(
        modifier = modifier
            .wrapContentWidth(align = Alignment.Start)
            .widthIn(max = 420.dp)
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
        color = colors.container,
        contentColor = colors.content,
        border = BorderStroke(1.dp, colors.content.copy(alpha = 0.08f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.size(26.dp),
                shape = CircleShape,
                color = colors.content.copy(alpha = 0.10f),
                contentColor = colors.content,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (loading && status == AgentToolStatus.RUNNING) {
                        DotLoading(size = 8.dp)
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = kindDescription,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .shimmer(isLoading = loading && status == AgentToolStatus.RUNNING),
            )

            if (approvalActions != null) {
                approvalActions()
            } else {
                ToolStatusPill(status = status)
            }
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
    "webview_open" -> "打开网页 ${arguments.getStringContent("url")?.compactToolPreview(28).orEmpty()}"
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
    val arguments = tool.inputAsJson()
    val memoryAction = arguments.getStringContent("action")
    val content = if (tool.isExecuted) {
        runCatching {
            JsonInstant.parseToJsonElement(
                tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            )
        }.getOrElse { JsonObject(emptyMap()) }
    } else {
        null
    }
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    val title = toolDisplayTitle(tool.toolName, arguments, memoryAction)
    val status = toolStatusFromMessagePart(tool = tool, loading = loading, content = content)

    val hasExtraContent = isDenied || images.isNotEmpty()

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
                        FilledTonalIconButton(
                            onClick = { showDenyDialog = true },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.chat_message_tool_deny),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { onToolApproval(tool.toolCallId, true, "") },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Tick01,
                                contentDescription = stringResource(R.string.chat_message_tool_approve),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            } else {
                null
            },
        )

        if (hasExtraContent) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                    if (tool.toolName == ToolNames.SCRAPE_WEB) {
                        val url = arguments.getStringContent("url") ?: ""
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalIconButton(
                                onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text)) } },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Refresh01,
                                    contentDescription = "Replay",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
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
                            color = MaterialTheme.colorScheme.error,
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

        if (items.isNotEmpty()) {
            items(items) { item ->
                val url = item.getStringContent("url") ?: return@items
                val title = item.getStringContent("title") ?: return@items
                val text = item.getStringContent("text") ?: return@items

                Card(
                    onClick = { context.openUrl(url) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(
                            url = url,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
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
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
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
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
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
    val arguments = tool.inputAsJson()

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
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onToolAnswer != null) {
                            when (q.selectionType) {
                                "single" -> {
                                    // Single select: chips only, no text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                "multi" -> {
                                    // Multi select: chips only, multiple can be selected
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                val selectedSet = multiAnswers[q.id] ?: emptySet()
                                                FilterChip(
                                                    selected = selectedSet.contains(option),
                                                    onClick = {
                                                        val current = selectedSet.toMutableSet()
                                                        if (current.contains(option)) current.remove(option)
                                                        else current.add(option)
                                                        multiAnswers[q.id] = current
                                                    },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    // Text (default): optional option chips + free text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    // Free text input
                                    OutlinedTextField(
                                        value = answers[q.id] ?: "",
                                        onValueChange = { answers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = false,
                                        minLines = 1,
                                        maxLines = 3,
                                    )
                                }
                            }
                        } else if (isAnswered) {
                            // Show the user's answer
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerJson = runCatching {
                                JsonInstant.parseToJsonElement(answeredState.answer)
                            }.getOrNull()
                            val answerText = answerJson?.jsonObject?.get("answers")
                                ?.jsonObject?.get(q.id)?.jsonPrimitive?.contentOrNull
                                ?: answeredState.answer
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Submit button
                if (isPending && onToolAnswer != null) {
                    FilledTonalButton(
                        onClick = {
                            val answerPayload = buildJsonObject {
                                put("answers", buildJsonObject {
                                    questions.forEach { q ->
                                        when (q.selectionType) {
                                            "multi" -> put(q.id, JsonPrimitive(multiAnswers[q.id]?.joinToString(", ") ?: ""))
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
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_message_tool_submit),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
    )
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
