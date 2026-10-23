package app.amber.feature.ui.components.ai

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import app.amber.agent.R
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import app.amber.feature.webview.WebViewOperationState
import java.io.File
import java.net.URLEncoder

internal fun String.normalizedWebPreviewUrl(): String {
    val raw = trim()
    if (raw.isBlank()) return raw
    return runCatching {
        raw.toUri()
            .buildUpon()
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }.getOrDefault(raw.trimEnd('/'))
}

internal fun WebViewOperationState.matchesPreview(toolCallId: String, normalizedUrl: String): Boolean {
    if (loadId.isBlank()) return false
    if (toolCallId.isNotBlank() && this.toolCallId == toolCallId) return true
    if (normalizedUrl.isBlank()) return false
    return sequenceOf(requestedUrl, committedUrl, this.url, displayUrl)
        .filter { it.isNotBlank() }
        .map { it.normalizedWebPreviewUrl() }
        .any { it == normalizedUrl }
}

internal fun WebViewOperationState.bestThumbnailFile(isCurrentPreview: Boolean, cached: File? = null): File? =
    thumbnailPath.takeIf { isCurrentPreview }?.asValidThumbnailFile()
        ?: cached
        ?: lastGoodThumbnailPath.asValidThumbnailFile()

internal fun String.asValidThumbnailFile(): File? =
    takeIf { it.isNotBlank() }
        ?.let { path -> File(path) }
        ?.takeIf { file -> file.exists() && file.length() > 0L }

internal fun sandboxStatusLabel(context: Context, status: ToolActivityStatus): String = when (status) {
    ToolActivityStatus.RUNNING -> context.getString(R.string.chat_sandbox_status_running)
    ToolActivityStatus.WAITING_FOR_PERMISSION -> context.getString(R.string.chat_sandbox_status_waiting_permission)
    ToolActivityStatus.SUCCEEDED -> context.getString(R.string.chat_sandbox_status_succeeded)
    ToolActivityStatus.FAILED -> context.getString(R.string.chat_sandbox_status_failed)
    ToolActivityStatus.TIMED_OUT -> context.getString(R.string.chat_sandbox_status_timed_out)
    ToolActivityStatus.INTERRUPTED -> context.getString(R.string.chat_sandbox_status_interrupted)
    ToolActivityStatus.CANCELLED -> context.getString(R.string.chat_sandbox_status_cancelled)
}

internal fun sandboxStatusContainerColor(status: ToolActivityStatus): Color = when (status) {
    ToolActivityStatus.RUNNING -> Color(0xFF34C96E)
    ToolActivityStatus.WAITING_FOR_PERMISSION -> Color(0xFFFFC44D)
    ToolActivityStatus.SUCCEEDED -> Color(0xFF34C96E)
    ToolActivityStatus.FAILED -> Color(0xFFE45A5A)
    ToolActivityStatus.TIMED_OUT -> Color(0xFFE45A5A)
    ToolActivityStatus.INTERRUPTED -> Color(0xFFFFC44D)
    ToolActivityStatus.CANCELLED -> Color(0xFFB8C0CC)
}

internal fun sandboxStatusOnContainerColor(status: ToolActivityStatus): Color = when (status) {
    ToolActivityStatus.WAITING_FOR_PERMISSION,
    ToolActivityStatus.INTERRUPTED,
    ToolActivityStatus.CANCELLED -> Color(0xFF1B1C20)
    else -> Color.White
}

internal fun SandboxActivityUiState.operationPreviewKind(): String = when {
    toolName == "agent_idle" -> "agent"
    toolName == "search_web" -> "web search"
    toolName == "scrape_web" || toolName == "webview_search_open" || toolName == "webview_open" || toolName == "webview_wait_for_load" || toolName == "webview_read" -> "webview"
    toolName.startsWith("icloud_") -> "icloud"
    toolName.startsWith("screen_") || toolName == "vlm_task" -> "screen"
    toolName.startsWith("file_") -> "workspace"
    toolName.startsWith("terminal_") -> "runtime"
    toolName.startsWith("mcp__") -> "mcp"
    else -> toolName
}

internal fun SandboxActivityUiState.operationPreviewText(context: Context): String {
    if (toolName == "agent_idle") {
        return "• ${inputPreview.ifBlank { context.getString(R.string.chat_sandbox_waiting_for_tool_call) }}\n" +
            context.getString(R.string.chat_sandbox_persistent_preview_enabled)
    }

    val previewUrl = operationPreviewUrl()
    if (previewUrl != null) {
        return buildString {
            append(previewUrl.webHostPreview().compactForSandbox(30))
            append('\n')
            append(inputPreview.ifBlank { title }.compactForSandbox(42))
            append('\n')
            append(sandboxStatusLabel(context, status))
        }
    }

    val command = inputPreview.ifBlank { title }
    val tail = outputTail.trim()
    return buildString {
        append("• ")
        append(command.compactForSandbox(28))
        append('\n')
        if (tail.isNotBlank()) {
            append(tail.lines().takeLast(3).joinToString("\n").compactForSandbox(96))
        } else {
            append(runtime.ifBlank { sandboxStatusLabel(context, status) }.compactForSandbox(36))
        }
    }
}

internal fun SandboxActivityUiState.operationPreviewUrl(): String? {
    if (toolName != "search_web" && toolName != "scrape_web" && toolName != "webview_search_open" && toolName != "webview_open" && toolName != "webview_wait_for_load" && toolName != "webview_read") {
        return null
    }

    val directInputUrl = inputPreview.firstHttpUrl()
    if (directInputUrl != null) return directInputUrl

    val outputUrl = outputTail.firstHttpUrl()
    if (outputUrl != null) return outputUrl

    if ((toolName == "search_web" || toolName == "webview_search_open") && inputPreview.isNotBlank()) {
        return "https://www.google.com/search?q=${URLEncoder.encode(inputPreview, "UTF-8")}"
    }

    return null
}

internal fun SandboxActivityUiState.stepProgressText(context: Context): String {
    if (toolName == "agent_idle") return context.getString(R.string.chat_sandbox_idle)
    val current = stepIndex
    val total = stepTotal
    return if (current != null && total != null) {
        "$current/$total"
    } else {
        sandboxStatusLabel(context, status)
    }
}

internal fun SandboxActivityUiState.terminalTranscript(context: Context): String = buildString {
    append("$ ")
    append(inputPreview.ifBlank { title })
    append('\n')
    if (runtime.isNotBlank()) {
        append(context.getString(R.string.chat_sandbox_calling_embedded, runtime))
        append('\n')
    }
    if (workspace.isNotBlank()) {
        append("workspace: ")
        append(workspace)
        append('\n')
    }
    append("status: ")
    append(sandboxStatusLabel(context, status))
    append('\n')
    if (outputTail.isNotBlank()) {
        append('\n')
        append(outputTail)
    } else if (status == ToolActivityStatus.RUNNING || status == ToolActivityStatus.WAITING_FOR_PERMISSION) {
        append('\n')
        append(context.getString(R.string.chat_sandbox_waiting_tool_output))
    }
}

private val HTTP_URL_REGEX = Regex("https?://[^\\s\"'<>),]+")

internal fun String.firstHttpUrl(): String? =
    HTTP_URL_REGEX.find(this)?.value?.trimEnd('.', ',', ';', ')')

internal fun String.webHostPreview(): String =
    runCatching { Uri.parse(this).host?.removePrefix("www.") }.getOrNull() ?: this

internal fun String.compactForSandbox(maxLength: Int): String {
    val compact = trim().replace(Regex("\\s+"), " ")
    return if (compact.length > maxLength) compact.take(maxLength - 1) + "…" else compact
}
