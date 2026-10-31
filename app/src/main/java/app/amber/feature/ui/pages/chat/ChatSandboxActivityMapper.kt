package app.amber.feature.ui.pages.chat

import android.content.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.R
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import app.amber.core.ai.mcp.McpToolNamespace
import app.amber.core.model.Conversation
import app.amber.feature.ui.components.message.MessageRenderCache
import app.amber.core.utils.jsonPrimitiveOrNull

private const val MAX_SANDBOX_TIMELINE_ITEMS = 24
private const val MAX_SANDBOX_OUTPUT_TAIL_CHARS = 1_600
private const val MAX_SANDBOX_JSON_PARSE_CHARS = 3_200_000

internal data class SandboxToolsSnapshot(
    val conversationId: String,
    val tools: List<UIMessagePart.Tool>,
)

internal data class SandboxActivityInput(
    val tools: SandboxToolsSnapshot,
    val loading: Boolean,
    val processingStatus: String?,
    val localeTag: String,
)

internal fun mergeSandboxTimeline(
    messageActivities: List<SandboxActivityUiState>,
    liveActivity: SandboxActivityUiState?,
): List<SandboxActivityUiState> {
    val merged = if (liveActivity == null) {
        messageActivities
    } else {
        val replaced = messageActivities.map { activity ->
            if (activity.toolCallId == liveActivity.toolCallId) liveActivity else activity
        }
        if (replaced.any { it.toolCallId == liveActivity.toolCallId }) {
            replaced
        } else {
            replaced + liveActivity
        }
    }
    return merged.mapIndexed { index, activity ->
        activity.copy(stepIndex = index + 1, stepTotal = merged.size)
    }
}

internal fun SandboxToolsSnapshot.deriveSandboxActivities(
    loading: Boolean,
    processingStatus: String?,
    context: Context,
): List<SandboxActivityUiState> {
    val sandboxTools = tools.takeLast(MAX_SANDBOX_TIMELINE_ITEMS)
    if (sandboxTools.isEmpty()) {
        return processingStatus?.takeIf { loading && it.isNotBlank() }?.let {
            listOf(
                SandboxActivityUiState(
                    toolCallId = "processing-status",
                    toolName = "agent_processing",
                    title = it,
                    status = ToolActivityStatus.RUNNING,
                    conversationId = conversationId,
                    runtime = "agent-run",
                    canCancel = true,
                    stepIndex = 1,
                    stepTotal = 1,
                )
            )
        } ?: emptyList()
    }

    return sandboxTools.mapIndexed { index, tool ->
        val outputJson = tool.outputJson()
        val input = MessageRenderCache.toolInputJson(tool.input)
        val status = tool.activityStatus(loading, outputJson)
        SandboxActivityUiState(
            toolCallId = tool.toolCallId,
            toolName = tool.toolName,
            title = tool.sandboxTitle(context, input),
            status = status,
            conversationId = conversationId,
            inputPreview = tool.inputPreview(input),
            outputTail = tool.outputTail(outputJson),
            runtime = outputJson.getStringContent("runtime") ?: tool.defaultRuntime(input),
            workspace = outputJson.getStringContent("workspace") ?: tool.defaultWorkspace(input),
            stepIndex = index + 1,
            stepTotal = sandboxTools.size,
            canCancel = loading && status in setOf(
                ToolActivityStatus.RUNNING,
                ToolActivityStatus.WAITING_FOR_PERMISSION
            ),
        )
    }
}

internal fun Conversation.idleSandboxActivity(context: Context): SandboxActivityUiState =
    SandboxActivityUiState(
        toolCallId = "agent-idle-$id",
        toolName = "agent_idle",
        title = context.getString(R.string.chat_page_agent_operation_preview),
        status = ToolActivityStatus.RUNNING,
        conversationId = id.toString(),
        inputPreview = context.getString(R.string.chat_page_waiting_for_tool_call),
        runtime = "standby",
        stepIndex = null,
        stepTotal = null,
    )

internal fun Conversation.sandboxActivityTools(): List<UIMessagePart.Tool> =
    currentRunMessages().flatMap { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isSandboxActivityTool() }
    }

private fun Conversation.currentRunMessages() = currentMessages.let { messages ->
    messages.drop((messages.indexOfLast { it.role == MessageRole.USER } + 1).coerceAtLeast(0))
}

private fun UIMessagePart.Tool.isSandboxActivityTool(): Boolean =
    toolName.startsWith("mcp__") ||
        toolName in setOf(
            "search_web",
            "scrape_web",
            "webview_search_open",
            "webview_open",
            "webview_wait_for_load",
            "webview_read",
            "icloud_status",
            "icloud_list",
            "icloud_read",
            "icloud_write",
            "icloud_search",
            "file_list",
            "file_read",
            "file_write",
            "file_edit",
            "file_search",
            "file_move",
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
            "terminal_session_stop",
            "screen_click",
            "screen_long_click",
            "screen_swipe",
            "screen_input_text",
            "screen_back",
            "screen_home",
            "screen_open_app",
            "screen_read_ui",
            "screen_screenshot",
            "vlm_task",
        )

private fun UIMessagePart.Tool.activityStatus(
    loading: Boolean,
    outputJson: JsonObject,
): ToolActivityStatus {
    val reportedStatus = outputJson.getStringContent("status")?.lowercase()
    return when {
        approvalState is ToolApprovalState.Pending -> ToolActivityStatus.WAITING_FOR_PERMISSION
        approvalState is ToolApprovalState.Denied -> ToolActivityStatus.CANCELLED
        !isExecuted && loading -> ToolActivityStatus.RUNNING
        !isExecuted -> ToolActivityStatus.RUNNING
        reportedStatus in setOf("queued", "running") -> ToolActivityStatus.RUNNING
        reportedStatus == "cancelled" -> ToolActivityStatus.CANCELLED
        reportedStatus == "timed_out" -> ToolActivityStatus.TIMED_OUT
        reportedStatus == "interrupted" -> ToolActivityStatus.INTERRUPTED
        outputJson.indicatesFailure() -> ToolActivityStatus.FAILED
        else -> ToolActivityStatus.SUCCEEDED
    }
}

private fun UIMessagePart.Tool.sandboxTitle(
    context: Context,
    input: JsonElement,
): String {
    return when (toolName) {
        "search_web" -> context.getString(
            R.string.chat_page_tool_search_web,
            input.getFirstStringContent("query", "q", "keyword", "keywords")
                .orEmpty()
                .compactSandboxText(20),
        )
        "scrape_web" -> context.getString(
            R.string.chat_page_tool_open_web,
            input.getFirstStringContent("url", "link", "uri")
                .orEmpty()
                .compactSandboxText(24),
        )
        "webview_search_open" -> context.getString(
            R.string.chat_page_tool_open_search_page,
            input.getStringContent("query").orEmpty().compactSandboxText(20),
        )
        "webview_open" -> context.getString(
            R.string.chat_page_tool_open_web,
            input.getStringContent("url").orEmpty().compactSandboxText(24),
        )
        "webview_wait_for_load" -> context.getString(R.string.chat_page_tool_wait_web_load)
        "webview_read" -> context.getString(R.string.chat_page_tool_read_web)
        "icloud_status" -> context.getString(R.string.chat_page_tool_check_icloud_mount)
        "icloud_list" -> context.getString(
            R.string.chat_page_tool_list_icloud,
            input.getStringContent("path").orEmpty().compactSandboxText(18),
        )
        "icloud_read" -> context.getString(
            R.string.chat_page_tool_read_icloud,
            input.getStringContent("path").orEmpty().compactSandboxText(20),
        )
        "icloud_write" -> context.getString(
            R.string.chat_page_tool_write_icloud,
            input.getStringContent("path").orEmpty().compactSandboxText(20),
        )
        "icloud_search" -> context.getString(
            R.string.chat_page_tool_search_icloud,
            input.getStringContent("query").orEmpty().compactSandboxText(20),
        )
        "file_list" -> context.getString(
            R.string.chat_page_tool_list_workspace,
            input.getStringContent("path").orEmpty().compactSandboxText(18),
        )
        "file_read" -> context.getString(
            R.string.chat_page_tool_read_file,
            input.getStringContent("path").orEmpty().compactSandboxText(20),
        )
        "file_write" -> context.getString(
            R.string.chat_page_tool_write_file,
            input.getStringContent("path").orEmpty().compactSandboxText(20),
        )
        "file_edit" -> context.getString(
            R.string.chat_page_tool_edit_file,
            input.getStringContent("path").orEmpty().compactSandboxText(20),
        )
        "file_search" -> context.getString(
            R.string.chat_page_tool_search_file,
            input.getStringContent("query").orEmpty().compactSandboxText(20),
        )
        "file_move" -> context.getString(
            R.string.chat_page_tool_move_file,
            input.getStringContent("from").orEmpty().compactSandboxText(16),
        )
        "terminal_execute" -> context.getString(R.string.chat_page_tool_execute_alpine_command)
        "terminal_install_packages" -> context.getString(R.string.chat_page_tool_install_terminal_packages)
        "terminal_workspace_flush" -> context.getString(R.string.chat_page_tool_sync_terminal_workspace)
        "terminal_job_start" -> context.getString(R.string.chat_page_tool_start_terminal_job)
        "terminal_job_read" -> context.getString(R.string.chat_page_tool_read_terminal_job)
        "terminal_job_wait" -> context.getString(R.string.chat_page_tool_wait_terminal_job)
        "terminal_job_stop" -> context.getString(R.string.chat_page_tool_stop_terminal_job)
        "terminal_session_start" -> context.getString(R.string.chat_page_tool_start_terminal_session)
        "terminal_session_exec" -> context.getString(R.string.chat_page_tool_execute_terminal_session)
        "terminal_session_read" -> context.getString(R.string.chat_page_tool_read_terminal_output)
        "terminal_session_stop" -> context.getString(R.string.chat_page_tool_stop_terminal_session)
        "screen_click" -> context.getString(R.string.chat_page_tool_screen_click)
        "screen_long_click" -> context.getString(R.string.chat_page_tool_screen_long_click)
        "screen_swipe" -> context.getString(R.string.chat_page_tool_screen_swipe)
        "screen_input_text" -> context.getString(R.string.chat_page_tool_screen_input_text)
        "screen_back" -> context.getString(R.string.chat_page_tool_screen_back)
        "screen_home" -> context.getString(R.string.chat_page_tool_screen_home)
        "screen_open_app" -> context.getString(
            R.string.chat_page_tool_open_app,
            input.getStringContent("package").orEmpty().compactSandboxText(18),
        )
        "screen_read_ui" -> context.getString(R.string.chat_page_tool_read_current_ui)
        "screen_screenshot" -> context.getString(R.string.chat_page_tool_screenshot)
        "vlm_task" -> context.getString(R.string.chat_page_tool_vlm_task)
        else -> if (toolName.startsWith("mcp__")) {
            context.getString(
                R.string.chat_page_tool_call_mcp,
                McpToolNamespace.displayName(toolName).compactSandboxText(24),
            )
        } else {
            toolName
        }
    }
}

private fun UIMessagePart.Tool.inputPreview(input: JsonElement): String {
    return when (toolName) {
        "search_web" -> input.getFirstStringContent("query", "q", "keyword", "keywords")
        "scrape_web" -> input.getFirstStringContent("url", "link", "uri")
        "webview_search_open" -> input.getStringContent("query")
        "webview_open" -> input.getStringContent("url")
        "webview_wait_for_load" -> input.getStringContent("target_url")
        "webview_read" -> input.getStringContent("url")
        "icloud_list", "icloud_read", "icloud_write" -> input.getStringContent("path")
        "icloud_search" -> input.getStringContent("query")
        "terminal_execute", "terminal_job_start", "terminal_session_exec" -> input.getStringContent("command")
        "terminal_install_packages" -> input.getStringContent("packages")
        "terminal_job_read", "terminal_job_wait", "terminal_job_stop" -> input.getStringContent("job_id")
        "file_list", "file_read", "file_write", "file_edit" -> input.getStringContent("path")
        "file_search" -> input.getStringContent("query")
        "file_move" -> input.getStringContent("from")
        "screen_open_app" -> input.getStringContent("package")
        "screen_input_text" -> input.getStringContent("text")
        "vlm_task" -> input.getStringContent("goal")
        else -> null
    }?.compactSandboxText(180) ?: input.toString().compactSandboxText(180)
}

private fun UIMessagePart.Tool.defaultRuntime(input: JsonElement): String = when {
    toolName in setOf("terminal_execute", "terminal_job_start") &&
        (input.getStringContent("profile_id") != null || input.getStringContent("ssh_profile_id") != null) -> "remote_ssh"
    toolName in setOf("terminal_execute", "terminal_job_start") &&
        input.getStringContent("runtime") != null -> input.getStringContent("runtime").orEmpty()
    toolName == "search_web" -> "web-search"
    toolName == "scrape_web" -> "webview"
    toolName == "webview_search_open" -> "webview"
    toolName == "webview_open" -> "webview"
    toolName == "webview_wait_for_load" -> "webview"
    toolName == "webview_read" -> "webview"
    toolName.startsWith("icloud_") -> "icloud-web-mount"
    toolName == "terminal_execute" ||
        toolName == "terminal_install_packages" ||
        toolName.startsWith("terminal_job_") -> "terminal"
    toolName == "terminal_workspace_flush" -> "saf-workspace"
    toolName.startsWith("terminal_session_") -> "alpine-proot-session"
    toolName.startsWith("file_") -> "saf-workspace"
    toolName.startsWith("screen_") || toolName == "vlm_task" -> "accessibility-service"
    toolName.startsWith("mcp__") -> "mcp"
    else -> ""
}

private fun UIMessagePart.Tool.defaultWorkspace(input: JsonElement): String = when {
    defaultRuntime(input) == "remote_ssh" ->
        (input.getStringContent("profile_id") ?: input.getStringContent("ssh_profile_id")).orEmpty()
    toolName.startsWith("terminal_job_") -> ""
    toolName.startsWith("terminal_") || toolName.startsWith("file_") -> "/workspace"
    toolName.startsWith("icloud_") -> "/icloud"
    else -> ""
}

private fun JsonObject.indicatesFailure(): Boolean {
    val exitCode = this["exit_code"]?.jsonPrimitiveOrNull?.intOrNull
    val error = getStringContent("error")
    val status = this["status"]?.jsonPrimitiveOrNull?.contentOrNull?.lowercase()
    val failed = this["failed"]?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() == true
    return !error.isNullOrBlank() ||
        (exitCode != null && exitCode != 0) ||
        failed ||
        status in setOf("failed", "error", "denied", "timed_out", "interrupted", "policy_denied")
}

private fun UIMessagePart.Tool.outputTail(outputJson: JsonObject): String {
    val output = outputJson.getStringContent("output")
        ?: outputJson.getStringContent("output_tail")
        ?: outputJson.getStringContent("error")
        ?: outputText(MAX_SANDBOX_OUTPUT_TAIL_CHARS)
    return output.trim().takeLast(MAX_SANDBOX_OUTPUT_TAIL_CHARS)
}

private fun UIMessagePart.Tool.outputJson(): JsonObject {
    var textChars = 0L
    var textParts = 0
    output.forEach { part ->
        if (part is UIMessagePart.Text) {
            if (textParts > 0) textChars++ // `textOutputForJson` joins text parts with '\n'.
            textChars += part.text.length.toLong()
            textParts++
        }
    }
    if (textChars == 0L || textChars > MAX_SANDBOX_JSON_PARSE_CHARS.toLong()) {
        return JsonObject(emptyMap())
    }
    return runCatching {
        MessageRenderCache.toolOutputJson(output) as? JsonObject
    }.getOrNull() ?: JsonObject(emptyMap())
}

private fun UIMessagePart.Tool.outputText(maxChars: Int = Int.MAX_VALUE): String {
    val textParts = output.filterIsInstance<UIMessagePart.Text>()
    if (maxChars == Int.MAX_VALUE) {
        return textParts.joinToString("\n") { it.text }
    }
    var remaining = maxChars
    val chunks = ArrayDeque<String>()
    for (part in textParts.asReversed()) {
        if (remaining <= 0) break
        val text = part.text
        val chunk = if (text.length > remaining) text.takeLast(remaining) else text
        chunks.addFirst(chunk)
        remaining -= chunk.length
    }
    return chunks.joinToString("\n")
}

private fun JsonElement?.getStringContent(key: String): String? =
    (this as? JsonObject)?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

private fun JsonElement?.getFirstStringContent(vararg keys: String): String? {
    val json = this as? JsonObject ?: return null
    return keys.firstNotNullOfOrNull { key ->
        json[key]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

internal fun SandboxActivityUiState.isActiveOperation(): Boolean =
    status == ToolActivityStatus.RUNNING || status == ToolActivityStatus.WAITING_FOR_PERMISSION

private fun String.compactSandboxText(maxLength: Int): String {
    val compact = trim().replace(Regex("\\s+"), " ")
    return if (compact.length > maxLength) compact.take(maxLength - 1) + "…" else compact
}
