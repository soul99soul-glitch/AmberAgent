package me.rerere.rikkahub.data.agent.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

data class ToolMetadata(
    val name: String,
    val category: String,
    val mutates: Boolean,
    val needsApproval: Boolean,
    val autoApprovable: Boolean,
    val outputBudgetChars: Int,
)

class ToolRegistry private constructor(
    private val entries: List<Entry>,
) {
    val metadata: List<ToolMetadata> = entries.map { it.metadata }

    fun tools(): List<Tool> = entries.map { entry ->
        entry.tool.copy(
            execute = { input ->
                entry.tool.execute(input).enforceOutputBudget(entry.metadata.outputBudgetChars)
            }
        )
    }

    fun metadataFor(name: String): ToolMetadata? =
        metadata.firstOrNull { it.name == name }

    companion object {
        fun from(tools: List<Tool>): ToolRegistry {
            val duplicates = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) {
                "Duplicate tool names registered: ${duplicates.sorted().joinToString(", ")}"
            }
            return ToolRegistry(
                tools.map { tool ->
                    Entry(
                        tool = tool,
                        metadata = ToolMetadata(
                            name = tool.name,
                            category = tool.category(),
                            mutates = tool.mutatesState(),
                            needsApproval = tool.needsApproval,
                            autoApprovable = tool.allowsAutoApproval,
                            outputBudgetChars = tool.outputBudgetChars(),
                        )
                    )
                }
            )
        }
    }

    private data class Entry(
        val tool: Tool,
        val metadata: ToolMetadata,
    )
}

internal fun Tool.category(): String = when {
    name.startsWith("file_") || name.startsWith("archive_") ||
        name in setOf("download_file", "pdf_read", "pdf_render_page", "office_read", "image_info", "image_convert", "ocr_image") -> "workspace"
    name.startsWith("icloud_") -> "cloud"
    name.startsWith("terminal_") -> "terminal"
    name in setOf("search_web", "scrape_web", "http_request") -> "web"
    name.startsWith("webview_") -> "webview"
    name.startsWith("screen_") || name == "vlm_task" -> "screen"
    name.startsWith("sms_") || name.startsWith("contacts_") || name.startsWith("calendar_") ||
        name.startsWith("call_") || name.startsWith("apps_") || name.startsWith("app_") ||
        name in setOf("device_phone_state", "media_search", "location_current", "audio_record_once", "notification_list", "usage_stats_list", "battery_status", "network_status", "wifi_status", "device_info", "settings_open", "intent_open", "share_text", "share_file", "notification_post") -> "system"
    name.startsWith("memory_") -> "memory"
    name.startsWith("skill") || name == "use_skill" -> "skill"
    name.startsWith("mcp_") || name.startsWith("mcp__") -> "mcp"
    else -> "utility"
}

private fun Tool.mutatesState(): Boolean {
    if (needsApproval) return true
    return name.contains("_write") ||
        name.contains("_edit") ||
        name.contains("_move") ||
        name.contains("_delete") ||
        name.contains("_install") ||
        name.contains("_stop") ||
        name.startsWith("memory_") && name != "memory_list" ||
        name.startsWith("skill_enable") ||
        name.startsWith("skill_disable")
}

private fun Tool.outputBudgetChars(): Int = when (name) {
    "file_read" -> FILE_READ_HARD_MAX_CHARS + 2_048
    else -> DEFAULT_TOOL_OUTPUT_BUDGET_CHARS
}

private fun List<UIMessagePart>.enforceOutputBudget(maxChars: Int): List<UIMessagePart> {
    var remaining = maxChars
    var truncated = false
    val bounded = mutableListOf<UIMessagePart>()
    for (part in this) {
        if (part !is UIMessagePart.Text) {
            bounded += part
            continue
        }
        if (remaining <= 0) {
            truncated = true
            break
        }
        if (part.text.length <= remaining) {
            bounded += part
            remaining -= part.text.length
        } else {
            bounded += part.copy(
                text = part.text.take(remaining) +
                    "\n... [tool output truncated to $maxChars chars]"
            )
            truncated = true
            break
        }
    }
    return if (truncated) bounded else this
}

private const val DEFAULT_TOOL_OUTPUT_BUDGET_CHARS = 80_000
