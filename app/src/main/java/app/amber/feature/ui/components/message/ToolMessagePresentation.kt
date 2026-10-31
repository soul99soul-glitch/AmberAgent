package app.amber.feature.ui.components.message

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.common.http.jsonObjectOrNull
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.Clipboard
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.DatabaseZap
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.ScanSearch
import com.composables.icons.lucide.WandSparkles
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Feather
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Wrench
import app.amber.agent.R
import app.amber.core.ai.mcp.McpToolNamespace
import app.amber.core.utils.jsonPrimitiveOrNull

internal object ToolNames {
    const val MEMORY = "memory_tool"
    const val SEARCH_WEB = "search_web"
    const val SCRAPE_WEB = "scrape_web"
    const val GET_TIME_INFO = "get_time_info"
    const val CLIPBOARD = "clipboard_tool"
    const val ASK_USER = "ask_user"
    const val USE_SKILL = "use_skill"
}

internal enum class AgentToolStatus {
    RUNNING,
    WAITING_FOR_PERMISSION,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    INTERRUPTED,
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

internal fun getToolIcon(toolName: String, action: String?) = when (toolName) {
    ToolNames.MEMORY -> when (action) {
        MemoryActions.CREATE, MemoryActions.EDIT -> Lucide.Feather
        MemoryActions.DELETE -> Lucide.Eraser
        else -> Lucide.Feather
    }

    ToolNames.SEARCH_WEB -> Lucide.ScanSearch
    ToolNames.SCRAPE_WEB -> Lucide.ScanSearch
    "webview_search_open" -> Lucide.ScanSearch
    "webview_open" -> Lucide.ScanSearch
    "webview_wait_for_load" -> Lucide.ScanSearch
    "webview_read" -> Lucide.ScanSearch
    in setOf("icloud_status", "icloud_list", "icloud_read", "icloud_write", "icloud_search") -> Lucide.DatabaseZap
    ToolNames.GET_TIME_INFO -> Lucide.Clock
    ToolNames.CLIPBOARD -> Lucide.Clipboard
    ToolNames.ASK_USER -> Lucide.MessageCircleQuestion
    ToolNames.USE_SKILL -> Lucide.WandSparkles
    in setOf(
        "file_list",
        "file_read",
        "file_write",
        "file_edit",
        "file_search",
        "file_move",
        "share_file"
    ) -> Lucide.FileText

    in setOf(
        "terminal_execute",
        "terminal_install_packages",
        "terminal_workspace_flush",
        "terminal_job_start",
        "terminal_job_read",
        "terminal_job_wait",
        "terminal_job_stop",
        "terminal_session_start",
        "terminal_session_exec",
        "terminal_session_read",
        "terminal_session_stop"
    ) -> Lucide.CodeXml

    in setOf(
        "screen_click",
        "screen_long_click",
        "screen_swipe",
        "screen_input_text",
        "screen_back",
        "screen_home",
        "screen_open_app",
        "screen_open_url",
        "screen_read_ui",
        "screen_find_text",
        "screen_tap_text",
        "screen_wait_for_text",
        "screen_scroll_until",
        "screen_screenshot",
        "screen_run_goal",
        "vlm_task"
    ) -> Lucide.Maximize

    else -> when {
        toolName.startsWith("screen_") -> Lucide.Maximize
        toolName.startsWith("mcp__") -> Lucide.Package
        // Phase 2 WebMount: primitives + adapter tools use the web/search
        // icon so they're visually grouped with webview_* tools.
        toolName.startsWith("wm_") ||
            toolName.startsWith("hn_") ||
            toolName.startsWith("reddit_") ||
            toolName.startsWith("juejin_") ||
            toolName.startsWith("github_") ||
            toolName.startsWith("bilibili_") ||
            toolName.startsWith("zhihu_") ||
            toolName.startsWith("feishu_docs_") -> Lucide.ScanSearch
        else -> Lucide.Wrench
    }
}

internal fun getToolKind(toolName: String) = when {
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
        "terminal_install_packages",
        "terminal_workspace_flush",
        "terminal_job_start",
        "terminal_job_read",
        "terminal_job_wait",
        "terminal_job_stop",
        "terminal_session_start",
        "terminal_session_exec",
        "terminal_session_read",
        "terminal_session_stop"
    ) -> AgentToolKind.TERMINAL

    toolName.startsWith("screen_") || toolName == "vlm_task" -> AgentToolKind.SCREEN

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
    val successFalse = jsonObject?.get("success")?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() == false
    if (jsonObject != null) {
        return !error.isNullOrBlank() ||
            (exitCode != null && exitCode != 0) ||
            failed ||
            successFalse ||
            status in setOf("failed", "error", "denied", "timed_out", "interrupted", "policy_denied", "disabled", "busy", "stale_snapshot")
    }
    return output.filterIsInstance<UIMessagePart.Text>().any { part ->
        part.text.contains("\"error\"", ignoreCase = true) ||
            part.text.contains("Exception", ignoreCase = true)
    }
}

internal fun toolStatusFromMessagePart(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    content: JsonElement?,
): AgentToolStatus {
    val jsonObject = content?.jsonObjectOrNull
    val reportedStatus = jsonObject
        ?.get("status")
        ?.jsonPrimitiveOrNull
        ?.contentOrNull
        ?.lowercase()
    val isScreenTool = tool.toolName.startsWith("screen_") || tool.toolName == "vlm_task"
    val screenGoalUnverified = tool.toolName == "screen_run_goal" &&
        jsonObject?.get("goal_verified")?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() == false
    val screenVerifyTextFailed = isScreenTool &&
        jsonObject?.get("verify_text_found")?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() == false
    val screenActionUnverified = tool.toolName in setOf("screen_tap_text", "screen_click", "screen_input_text") &&
        jsonObject?.get("verified")?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() == false
    val screenFoundFalse = tool.toolName in setOf("screen_wait_for_text", "screen_scroll_until") &&
        (jsonObject?.get("matched")?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() == false ||
            jsonObject?.get("found")?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() == false)
    // screen_click/swipe/long_click with `expectation` report acceptance under `verification`.
    val screenExpectationUnmet = isScreenTool &&
        jsonObject?.get("verification")
            ?.jsonObject
            ?.get("met")
            ?.jsonPrimitiveOrNull
            ?.contentOrNull
            ?.toBooleanStrictOrNull() == false
    val screenUnverified = screenGoalUnverified || screenVerifyTextFailed || screenActionUnverified ||
        screenFoundFalse || screenExpectationUnmet
    return when {
        tool.approvalState is ToolApprovalState.Pending -> AgentToolStatus.WAITING_FOR_PERMISSION
        tool.approvalState is ToolApprovalState.Denied -> AgentToolStatus.CANCELLED
        !tool.isExecuted && loading -> AgentToolStatus.RUNNING
        !tool.isExecuted -> AgentToolStatus.RUNNING
        reportedStatus in setOf("queued", "running") -> AgentToolStatus.RUNNING
        reportedStatus == "cancelled" -> AgentToolStatus.CANCELLED
        reportedStatus == "timed_out" -> AgentToolStatus.TIMED_OUT
        reportedStatus in setOf(
            "interrupted",
            "needs_user_action",
            "handback",
            "steps_exhausted",
            "budget_exhausted",
            "shadow_trace",
            // Allow-list refusal and pre-flight rejection dispatch nothing.
            "no_scope",
            "rejected",
        ) -> AgentToolStatus.INTERRUPTED
        // A WebMount or Screen action may have been dispatched even when its receipt or
        // postcondition is unavailable. Keep that outcome distinct from both
        // a verified success and a confirmed failure.
        reportedStatus in setOf("unknown", "outcome_unknown") -> AgentToolStatus.UNKNOWN
        toolHasFailure(content = content, output = tool.output) -> AgentToolStatus.FAILED
        screenUnverified -> AgentToolStatus.INTERRUPTED
        else -> AgentToolStatus.SUCCEEDED
    }
}

@Composable
internal fun toolStatusLabel(status: AgentToolStatus): String = when (status) {
    AgentToolStatus.RUNNING -> stringResource(R.string.chat_message_tool_status_running)
    AgentToolStatus.WAITING_FOR_PERMISSION -> stringResource(R.string.chat_message_tool_status_waiting_permission)
    AgentToolStatus.UNKNOWN -> stringResource(R.string.chat_page_tool_result_unknown)
    AgentToolStatus.SUCCEEDED -> stringResource(R.string.chat_message_tool_status_succeeded)
    AgentToolStatus.FAILED -> stringResource(R.string.chat_message_tool_status_failed)
    AgentToolStatus.TIMED_OUT -> stringResource(R.string.chat_message_tool_status_timed_out)
    AgentToolStatus.INTERRUPTED -> stringResource(R.string.chat_message_tool_status_interrupted)
    AgentToolStatus.CANCELLED -> stringResource(R.string.chat_message_tool_status_cancelled)
}

@Composable
internal fun toolKindLabel(kind: AgentToolKind, toolName: String): String = when (kind) {
    AgentToolKind.FILE -> stringResource(R.string.chat_message_tool_kind_file)
    AgentToolKind.TERMINAL -> stringResource(R.string.chat_message_tool_kind_terminal)
    AgentToolKind.MCP -> stringResource(R.string.chat_message_tool_kind_mcp)
    AgentToolKind.SCREEN -> stringResource(R.string.chat_message_tool_kind_screen)
    AgentToolKind.WEB -> stringResource(R.string.chat_message_tool_kind_web)
    AgentToolKind.MEMORY -> stringResource(R.string.chat_message_tool_kind_memory)
    // Generic fallback used to return toolName itself, which produced the
    // ugly "$toolName · $toolName" duplicate subtitle for any tool not in
    // one of the categorized buckets. Plain "工具" reads cleaner and the
    // tool name still shows on the right of the dot.
    AgentToolKind.GENERIC -> stringResource(R.string.chat_message_tool_kind_generic)
}

@Composable
private fun toolActivityTitleWithDetail(
    resourceId: Int,
    detail: String?,
): String {
    val base = stringResource(resourceId)
    val safeDetail = detail?.trim()?.takeIf { it.isNotEmpty() } ?: return base
    return stringResource(R.string.tool_activity_with_detail, base, safeDetail)
}

@Composable
internal fun toolDisplayTitle(
    toolName: String,
    arguments: JsonElement,
    memoryAction: String?,
): String {
    arguments.toolDisplayTitleHint()?.let { return it }
    val fileLabel = stringResource(R.string.chat_message_tool_kind_file)

    return when (toolName) {
    // P2-06: the approval capsule shows the target memory id, scope and
    // revision the write is bound to (来源/目标 scope/版本), reusing the
    // existing approval card with the minimal title change.
    ToolNames.MEMORY -> when (memoryAction) {
        MemoryActions.CREATE -> stringResource(R.string.chat_message_tool_create_memory)
        MemoryActions.EDIT -> {
            val id = arguments.getStringContent("id")
            val scope = arguments.getStringContent("scope")
            val revision = arguments.getStringContent("revision")
            if (id != null && revision != null) {
                stringResource(
                    R.string.chat_message_tool_edit_memory_detail,
                    id,
                    scope.orEmpty(),
                    revision,
                )
            } else {
                stringResource(R.string.chat_message_tool_edit_memory)
            }
        }
        MemoryActions.DELETE -> {
            val id = arguments.getStringContent("id")
            val revision = arguments.getStringContent("revision")
            if (id != null && revision != null) {
                stringResource(R.string.chat_message_tool_delete_memory_detail, id, revision)
            } else {
                stringResource(R.string.chat_message_tool_delete_memory)
            }
        }
        else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
    }

    ToolNames.SEARCH_WEB -> stringResource(
        R.string.chat_message_tool_search_web,
        arguments.getStringContent("query") ?: ""
    )

    ToolNames.SCRAPE_WEB -> stringResource(R.string.chat_message_tool_scrape_web)
    "webview_search_open" -> stringResource(
        R.string.chat_message_tool_webview_search_open,
        arguments.getStringContent("query")?.compactToolPreview(28).orEmpty(),
    )
    "webview_open" -> stringResource(
        R.string.chat_message_tool_webview_open,
        arguments.getStringContent("url")?.compactToolPreview(28).orEmpty(),
    )
    "webview_wait_for_load" -> stringResource(R.string.chat_message_tool_webview_wait_for_load)
    "webview_read" -> stringResource(R.string.chat_message_tool_webview_read)
    "icloud_status" -> stringResource(R.string.chat_message_tool_icloud_status)
    "icloud_list" -> stringResource(
        R.string.chat_message_tool_icloud_list,
        arguments.getStringContent("path")?.compactToolPreview(18).orEmpty(),
    )
    "icloud_read" -> stringResource(
        R.string.chat_message_tool_icloud_read,
        arguments.getStringContent("path")?.compactToolPreview(22).orEmpty(),
    )
    "icloud_write" -> stringResource(
        R.string.chat_message_tool_icloud_write,
        arguments.getStringContent("path")?.compactToolPreview(22).orEmpty(),
    )
    "icloud_search" -> stringResource(
        R.string.chat_message_tool_icloud_search,
        arguments.getStringContent("query")?.compactToolPreview(22).orEmpty(),
    )
    ToolNames.GET_TIME_INFO -> stringResource(R.string.chat_message_tool_get_time)
    ToolNames.CLIPBOARD -> when (memoryAction) {
        ClipboardActions.READ -> stringResource(R.string.chat_message_tool_clipboard_read)
        ClipboardActions.WRITE -> stringResource(R.string.chat_message_tool_clipboard_write)
        else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
    }

    ToolNames.USE_SKILL -> {
        val skillName = arguments.getStringContent("name") ?: ""
        val path = arguments.getStringContent("path")
        if (path != null) {
            stringResource(R.string.chat_message_tool_use_skill_with_path, skillName, path)
        } else {
            stringResource(R.string.chat_message_tool_use_skill, skillName)
        }
    }

    "file_list" -> stringResource(
        R.string.chat_message_tool_file_list,
        arguments.getStringContent("path")?.compactToolPreview(18).orEmpty(),
    )
    "file_read" -> stringResource(
        R.string.chat_message_tool_file_read,
        arguments.getStringContent("path")?.compactToolPreview(22).orEmpty(),
    )
    "file_write" -> stringResource(
        R.string.chat_message_tool_file_write,
        arguments.pathDisplayName().ifBlank { fileLabel },
    )
    "file_edit" -> stringResource(
        R.string.chat_message_tool_file_edit,
        arguments.pathDisplayName().ifBlank { fileLabel },
    )
    "file_search" -> stringResource(
        R.string.chat_message_tool_file_search,
        arguments.getStringContent("query")?.compactToolPreview(22).orEmpty(),
    )
    "file_move" -> stringResource(
        R.string.chat_message_tool_file_move,
        arguments.getStringContent("from")?.compactToolPreview(16).orEmpty(),
    )
    "share_file" -> stringResource(
        R.string.chat_message_tool_share_file,
        arguments.pathDisplayName().ifBlank { fileLabel },
    )
    "share_text" -> stringResource(R.string.chat_message_tool_share_text)
    "tool_search" -> stringResource(R.string.chat_message_tool_search_available)
    "tools_list" -> stringResource(R.string.chat_message_tool_list_catalog)
    "tool_policy_explain" -> stringResource(R.string.chat_message_tool_check_permissions)
    "terminal_execute" -> stringResource(
        R.string.chat_message_tool_terminal_execute,
        arguments.getStringContent("command")?.compactToolPreview(22).orEmpty(),
    )
    "terminal_install_packages" -> stringResource(R.string.chat_message_tool_terminal_install_packages)
    "terminal_workspace_flush" -> stringResource(R.string.chat_message_tool_terminal_workspace_flush)
    "terminal_job_start" -> stringResource(
        R.string.chat_message_tool_terminal_job_start,
        arguments.getStringContent("command")?.compactToolPreview(20).orEmpty(),
    )
    "terminal_job_read" -> stringResource(R.string.chat_message_tool_terminal_job_read)
    "terminal_job_wait" -> stringResource(R.string.chat_message_tool_terminal_job_wait)
    "terminal_job_stop" -> stringResource(R.string.chat_message_tool_terminal_job_stop)
    "terminal_session_start" -> stringResource(R.string.chat_message_tool_terminal_session_start)
    "terminal_session_exec" -> stringResource(
        R.string.chat_message_tool_terminal_session_exec,
        arguments.getStringContent("command")?.compactToolPreview(20).orEmpty(),
    )
    "terminal_session_read" -> stringResource(R.string.chat_message_tool_terminal_session_read)
    "terminal_session_stop" -> stringResource(R.string.chat_message_tool_terminal_session_stop)
    "screen_click" -> stringResource(R.string.chat_message_tool_screen_click)
    "screen_long_click" -> stringResource(R.string.chat_message_tool_screen_long_click)
    "screen_swipe" -> stringResource(R.string.chat_message_tool_screen_swipe)
    "screen_input_text" -> stringResource(R.string.chat_message_tool_screen_input_text)
    "screen_back" -> stringResource(R.string.chat_message_tool_screen_back)
    "screen_home" -> stringResource(R.string.chat_message_tool_screen_home)
    "screen_open_app" -> stringResource(
        R.string.chat_message_tool_screen_open_app,
        (arguments.getStringContent("package_name") ?: arguments.getStringContent("package"))?.compactToolPreview(18).orEmpty(),
    )
    "screen_open_url" -> toolActivityTitleWithDetail(
        R.string.tool_activity_screen_open_url,
        arguments.getStringContent("url")?.compactToolPreview(22),
    )
    "screen_read_ui" -> stringResource(R.string.chat_message_tool_screen_read_ui)
    "screen_find_text" -> toolActivityTitleWithDetail(
        R.string.tool_activity_screen_find_text,
        arguments.getStringContent("text")?.compactToolPreview(18),
    )
    "screen_tap_text" -> toolActivityTitleWithDetail(
        R.string.tool_activity_screen_tap_text,
        arguments.getStringContent("text")?.compactToolPreview(18),
    )
    "screen_wait_for_text" -> toolActivityTitleWithDetail(
        R.string.tool_activity_screen_wait_for_text,
        arguments.getStringContent("text")?.compactToolPreview(18),
    )
    "screen_scroll_until" -> toolActivityTitleWithDetail(
        R.string.tool_activity_screen_scroll_until,
        arguments.getStringContent("text")?.compactToolPreview(18),
    )
    "screen_run_goal" -> toolActivityTitleWithDetail(
        R.string.tool_activity_screen_run_goal,
        (arguments.getStringContent("goal")?.let(app.amber.core.automation.SensitiveScreenTextClassifier::redactIfSensitive)
            ?: arguments.getStringContent("package_name"))?.compactToolPreview(22),
    )
    "screen_screenshot" -> stringResource(R.string.chat_message_tool_screen_screenshot)
    "vlm_task" -> stringResource(R.string.chat_message_tool_vlm_task)
    else -> if (toolName.startsWith("mcp__")) {
        stringResource(
            R.string.chat_message_tool_mcp,
            McpToolNamespace.displayName(toolName).compactToolPreview(26),
        )
    } else {
        stringResource(R.string.chat_message_tool_call_generic, toolName)
    }
    }
}
