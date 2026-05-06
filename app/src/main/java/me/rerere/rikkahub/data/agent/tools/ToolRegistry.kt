package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

data class ToolMetadata(
    val name: String,
    val category: String,
    val mutates: Boolean,
    val needsApproval: Boolean,
    val autoApprovable: Boolean,
    val outputBudgetChars: Int,
    val risk: ToolRisk,
)

class ToolRegistry private constructor(
    private val entries: List<Entry>,
) {
    val metadata: List<ToolMetadata> = entries.map { it.metadata }

    fun tools(): List<Tool> = entries.map { entry ->
        entry.tool.copy(
            needsApproval = entry.metadata.needsApproval,
            allowsAutoApproval = entry.metadata.autoApprovable,
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
                        metadata = tool.toMetadata()
                    )
                }
            )
        }

        private fun Tool.toMetadata(): ToolMetadata {
            val mutates = mutatesState()
            val risk = risk()
            val effectiveNeedsApproval = needsApproval || mutates || risk == ToolRisk.High
            val effectiveAutoApproval = allowsAutoApproval && risk != ToolRisk.High
            return ToolMetadata(
                name = name,
                category = category(),
                mutates = mutates,
                needsApproval = effectiveNeedsApproval,
                autoApprovable = effectiveAutoApproval,
                outputBudgetChars = outputBudgetChars(),
                risk = risk,
            )
        }
    }

    private data class Entry(
        val tool: Tool,
        val metadata: ToolMetadata,
    )
}

enum class ToolRisk {
    Normal,
    Sensitive,
    High,
}

internal fun Tool.category(): String = when {
    name.startsWith("external_file_") -> "external_file"
    name.startsWith("file_") || name.startsWith("archive_") ||
        name in setOf("download_file", "pdf_read", "pdf_render_page", "office_read", "image_info", "image_convert", "ocr_image") -> "workspace"
    name.startsWith("icloud_") -> "cloud"
    name.startsWith("officepro_") -> "office"
    name.startsWith("terminal_") -> "terminal"
    name in setOf("search_web", "scrape_web", "http_request") -> "web"
    name.startsWith("webview_") -> "webview"
    name.startsWith("screen_") || name == "vlm_task" -> "screen"
    name.startsWith("sms_") || name.startsWith("contacts_") || name.startsWith("calendar_") ||
        name.startsWith("call_") || name.startsWith("apps_") || name.startsWith("app_") ||
        name in setOf("device_phone_state", "media_search", "location_current", "audio_record_once", "notification_list", "usage_stats_list", "battery_status", "network_status", "wifi_status", "device_info", "settings_open", "intent_open", "share_text", "share_file", "notification_post") -> "system"
    name.startsWith("memory_") -> "memory"
    name.startsWith("conversation_") -> "context"
    name.startsWith("cron_task_") -> "cron"
    name.startsWith("subagent_") -> "subagent"
    name.startsWith("model_council_") -> "model_council"
    name.startsWith("skill") || name == "use_skill" -> "skill"
    name.startsWith("mcp_") || name.startsWith("mcp__") -> "mcp"
    else -> "utility"
}

private fun Tool.mutatesState(): Boolean {
    if (name == "memory_tool") return true
    return name.contains("_write") ||
        name.contains("_edit") ||
        name.contains("_move") ||
        name.contains("_delete") ||
        name.contains("_install") ||
        name.contains("_stop") ||
        name == "pdf_render_page" ||
        name == "officepro_make_report" ||
        name == "officepro_project_update" ||
        name == "model_council_make_report" ||
        name in setOf("cron_task_create", "cron_task_update", "cron_task_delete") ||
        name.startsWith("memory_") && name != "memory_list" ||
        name == "conversation_compact" ||
        name in setOf("subagent_start", "subagent_cancel") ||
        name.startsWith("skill_enable") ||
        name.startsWith("skill_disable")
}

private fun Tool.risk(): ToolRisk = when {
    name == "http_request" -> ToolRisk.High
    name == "memory_tool" -> ToolRisk.High
    name == "pdf_render_page" -> ToolRisk.High
    name == "officepro_read_screen" ||
        name == "officepro_capture_context" ||
        name == "officepro_context_digest" ||
        name == "officepro_daily_radar" ||
        name == "officepro_project_briefing" ||
        name == "officepro_document_warroom" ||
        name == "officepro_open_items_radar" ||
        name == "officepro_meeting_closure" ||
        name == "officepro_create_task_draft" ||
        name == "officepro_create_base_record_draft" ||
        name == "officepro_reply_draft" ||
        name == "officepro_project_context" -> ToolRisk.High
    name.startsWith("external_file_") && (name.contains("_write") || name.contains("_delete")) -> ToolRisk.High
    name.startsWith("sms_") || name.startsWith("call_") || name.startsWith("contacts_write") -> ToolRisk.High
    name.startsWith("screen_") || name == "vlm_task" -> ToolRisk.Sensitive
    name.startsWith("terminal_") -> ToolRisk.Sensitive
    else -> ToolRisk.Normal
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
            bounded += part.copy(text = truncatedEnvelope(part.text, maxChars))
            truncated = true
            break
        }
    }
    return if (truncated) bounded else this
}

private fun truncatedEnvelope(text: String, maxChars: Int): String {
    val tailChars = (maxChars - 512).coerceAtLeast(1_024)
    return buildJsonObject {
        put("status", "truncated")
        put("truncated", true)
        put("total_chars", text.length)
        put("max_chars", maxChars)
        put("content_tail", text.takeLast(tailChars))
        put("note", "Tool output exceeded the registry output budget. The original tool result remains in the local transcript.")
    }.toString()
}

private const val DEFAULT_TOOL_OUTPUT_BUDGET_CHARS = 80_000
