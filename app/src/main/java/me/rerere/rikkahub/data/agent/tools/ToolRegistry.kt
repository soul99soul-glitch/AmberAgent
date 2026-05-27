package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.modelcouncil.DEFAULT_MODEL_COUNCIL_MAX_ROUNDS
import me.rerere.rikkahub.data.agent.modelcouncil.DEFAULT_MODEL_COUNCIL_MAX_SEATS
import me.rerere.rikkahub.data.agent.modelcouncil.EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS
import me.rerere.rikkahub.data.agent.subagent.EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS
import me.rerere.rikkahub.utils.JsonInstant
import java.util.Locale

data class ToolMetadata(
    val name: String,
    val category: String,
    val mutates: Boolean,
    val sensitiveRead: Boolean,
    val needsApproval: Boolean,
    val autoApprovable: Boolean,
    val outputBudgetChars: Int,
    val risk: ToolRisk,
    val mandatoryApproval: Boolean = false,
)

data class ToolInvocationPolicy(
    val name: String,
    val category: String,
    val mutates: Boolean,
    val needsApproval: Boolean,
    val autoApprovable: Boolean,
    val concurrencySafe: Boolean,
    val outputBudgetChars: Int,
    val risk: ToolRisk,
    val parallelGroup: String? = null,
    val requiresForegroundAppPackage: String? = null,
    val speculativeEligible: Boolean = false,
    val speculativeBlockReason: String? = null,
    val hardBlocked: Boolean = false,
    val reason: String? = null,
    val mandatoryApproval: Boolean = false,
)

class ToolRegistry private constructor(
    private val entries: List<Entry>,
) {
    val metadata: List<ToolMetadata> = entries.map { it.metadata }

    fun tools(): List<Tool> = entries.map { entry ->
        entry.tool.copy(
            needsApproval = entry.metadata.needsApproval,
            allowsAutoApproval = entry.metadata.autoApprovable,
            parameters = { entry.tool.parameters().withDisplayTitleHint() },
            execute = { input ->
                entry.tool.execute(input).enforceOutputBudget(entry.metadata.outputBudgetChars)
            }
        )
    }

    fun metadataFor(name: String): ToolMetadata? =
        metadata.firstOrNull { it.name == name }

    fun evaluateInvocation(toolName: String, input: JsonElement? = null): ToolInvocationPolicy? =
        entries.firstOrNull { it.tool.name == toolName }?.tool?.invocationPolicy(input)

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
            val effectiveNeedsApproval = mandatoryApproval || needsApproval || mutates || risk == ToolRisk.High
            val effectiveAutoApproval = !mandatoryApproval && allowsAutoApproval && risk != ToolRisk.High
            return ToolMetadata(
                name = name,
                category = category(),
                mutates = mutates,
                sensitiveRead = sensitiveRead(),
                needsApproval = effectiveNeedsApproval,
                autoApprovable = effectiveAutoApproval,
                outputBudgetChars = outputBudgetChars(),
                risk = risk,
                mandatoryApproval = mandatoryApproval,
            )
        }
    }

    private data class Entry(
        val tool: Tool,
        val metadata: ToolMetadata,
    )
}

private fun InputSchema?.withDisplayTitleHint(): InputSchema? = when (this) {
    is InputSchema.Obj -> copy(
        properties = JsonObject(
            properties.toMutableMap().apply {
                put(
                    "display_title",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional short user-facing action title in Chinese, 4-14 chars, describing this specific step (e.g. 写入第一卷, 合并最终文件)."
                        )
                    }
                )
            }
        )
    )

    null -> null
}

enum class ToolRisk {
    Normal,
    Sensitive,
    High,
}

fun Tool.invocationPolicy(inputText: String): ToolInvocationPolicy {
    val input = runCatching {
        JsonInstant.parseToJsonElement(inputText.ifBlank { "{}" })
    }.getOrNull()
    return invocationPolicy(input)
}

fun Tool.invocationPolicy(input: JsonElement?): ToolInvocationPolicy {
    val baseMutates = mutatesState()
    val baseRisk = risk()
    var mutates = baseMutates
    var risk = baseRisk
    var mandatoryApprovalEffective = mandatoryApproval
    var needsApproval = mandatoryApprovalEffective || needsApproval || baseMutates || baseRisk == ToolRisk.High
    var autoApprovable = !mandatoryApprovalEffective && allowsAutoApproval && baseRisk != ToolRisk.High
    var concurrencySafe = concurrencySafe()

    when (name) {
        "http_request" -> {
            val method = input.stringValue("method")?.uppercase(Locale.ROOT) ?: "GET"
            val safe = method in setOf("GET", "HEAD")
            mutates = !safe
            risk = if (safe) ToolRisk.Normal else ToolRisk.High
            needsApproval = !safe
            autoApprovable = safe || (allowsAutoApproval && risk != ToolRisk.High)
            concurrencySafe = safe
        }

        "memory_tool" -> {
            val op = input.stringValue("action")
                ?: input.stringValue("operation")
                ?: input.stringValue("op")
                ?: input.stringValue("type")
                ?: "read"
            val readOnly = op.lowercase(Locale.ROOT) in setOf("read", "get", "list", "search", "status", "query")
            mutates = !readOnly
            risk = if (readOnly) ToolRisk.Normal else ToolRisk.High
            needsApproval = !readOnly
            autoApprovable = readOnly || (allowsAutoApproval && risk != ToolRisk.High)
            concurrencySafe = readOnly
        }

        "cron_task_list", "agent_task_list", "agent_task_read", "agent_runtime_status", "tool_policy_explain", "tool_search", "tools_list" -> {
            mutates = false
            risk = ToolRisk.Normal
            needsApproval = false
            autoApprovable = true
            concurrencySafe = true
        }

        "agent_prompt_config" -> {
            val action = input.stringValue("action") ?: "get"
            val readOnly = action == "get"
            mutates = !readOnly
            risk = if (readOnly) ToolRisk.Normal else ToolRisk.Sensitive
            needsApproval = !readOnly
            autoApprovable = readOnly || allowsAutoApproval
            concurrencySafe = readOnly
        }

        "mcp_call_tool" -> {
            mutates = true
            risk = ToolRisk.Sensitive
            needsApproval = true
            autoApprovable = allowsAutoApproval
            concurrencySafe = false
        }

        "wm_eval" -> {
            // Arbitrary JS in the user's logged-in WebView — treat as high-risk
            // mutation; always needs explicit approval.
            mutates = true
            risk = ToolRisk.High
            needsApproval = true
            autoApprovable = false
            concurrencySafe = false
        }

        "wm_click", "wm_tap", "wm_type", "wm_keys", "wm_select" -> {
            // DOM mutation on a logged-in page — Sensitive by default; the
            // adapter system can pre-approve known-origin tools later.
            mutates = true
            risk = ToolRisk.Sensitive
            needsApproval = true
            autoApprovable = allowsAutoApproval
            concurrencySafe = false
        }

        "wm_tab_close" -> {
            // Destroys a session the agent itself opened. Mutates state but
            // not user data — keep at Normal risk + auto-approvable to avoid
            // approval-fatigue training. The user can still observe the
            // close in activity logs.
            mutates = true
            risk = ToolRisk.Normal
            needsApproval = false
            autoApprovable = allowsAutoApproval
            concurrencySafe = false
        }

        "wm_site_add" -> {
            // Plan v2: agent-driven site add. Reversible (user can delete in
            // the settings page or via wm_site_remove). Doesn't grant the
            // agent new capabilities — agent already had wm_open for any URL.
            // Auto-approve so bulk-add scenarios ("add these 10 sites I'm
            // pasting") don't require 10 confirmations.
            mutates = true
            risk = ToolRisk.Normal
            needsApproval = false
            autoApprovable = true
            concurrencySafe = false
        }

        "wm_site_remove" -> {
            // Destructive: clears cookies + OAuth credentials + tokens for
            // the site. Always require explicit per-call approval.
            mutates = true
            risk = ToolRisk.Sensitive
            needsApproval = true
            autoApprovable = allowsAutoApproval
            concurrencySafe = false
        }

        "wm_profile_synthesize" -> {
            // Writes a Site Profile to the user-imported namespace. Reversible
            // (profile deleted with the site or by re-synthesizing). The profile
            // only enriches wm_open/wm_state output with hints — it cannot grant
            // signing or arbitrary-JS powers (synthesize doesn't accept scripts,
            // so the resulting profile carries no call_page_fn permissions).
            // Auto-approve so the agent can iterate on hints across a chat turn.
            mutates = true
            risk = ToolRisk.Normal
            needsApproval = false
            autoApprovable = true
            concurrencySafe = false
        }

        "wm_signed_fetch" -> {
            // Phase 2 M2.2: profile-driven signed fetch. The risk is
            // method-dependent — GET/HEAD reads are safe (user's own
            // cookies, same as a browser tab fetching the URL), but
            // write methods need explicit human approval since they
            // can perform follow/like/post actions on the user's behalf.
            val method = input.stringValue("method")?.uppercase(Locale.ROOT) ?: "GET"
            val safe = method in setOf("GET", "HEAD")
            mutates = !safe
            risk = if (safe) ToolRisk.Normal else ToolRisk.High
            needsApproval = !safe
            autoApprovable = safe || (allowsAutoApproval && risk != ToolRisk.High)
            concurrencySafe = safe
        }

        "model_council_start" -> {
            if (input.containsExternalCliCouncilSeat() || input.allowsExternalCliCouncil()) {
                mutates = true
                risk = ToolRisk.Sensitive
                needsApproval = true
                autoApprovable = false
                concurrencySafe = false
                mandatoryApprovalEffective = true
            }
        }
    }

    val category = category()
    val foregroundRequirement = foregroundPackageRequirement()
    // For wm_* tools we want concurrent execution across different sessions
    // but strict serialization within a session, regardless of mutates/risk.
    // Derive the parallel group from the session_id in input.
    val baseParallelGroup = if (concurrencySafe && !mutates && risk == ToolRisk.Normal) category else null
    val parallelGroup = if (name.startsWith("wm_")) {
        val sessionId = input.stringValue("session_id")
        if (sessionId != null) "webmount:$sessionId" else "webmount:unbound"
    } else {
        baseParallelGroup
    }
    val speculativeBlockReason = speculativeBlockReason(
        category = category,
        mutates = mutates,
        needsApproval = needsApproval,
        concurrencySafe = concurrencySafe,
        risk = risk,
        parallelGroup = parallelGroup,
        requiresForegroundAppPackage = foregroundRequirement,
    )
    return ToolInvocationPolicy(
        name = name,
        category = category,
        mutates = mutates,
        needsApproval = needsApproval,
        autoApprovable = autoApprovable,
        concurrencySafe = concurrencySafe,
        outputBudgetChars = outputBudgetChars(),
        risk = risk,
        parallelGroup = parallelGroup,
        requiresForegroundAppPackage = foregroundRequirement,
        speculativeEligible = speculativeBlockReason == null,
        speculativeBlockReason = speculativeBlockReason,
        mandatoryApproval = mandatoryApprovalEffective,
    )
}

internal fun Tool.category(): String = when {
    name.startsWith("external_file_") -> "external_file"
    name.startsWith("file_") || name.startsWith("archive_") ||
        name in setOf("download_file", "pdf_read", "pdf_render_page", "office_read", "image_info", "image_convert", "ocr_image") -> "workspace"
    name.startsWith("icloud_") -> "cloud"
    name.startsWith("officepro_") -> "office"
    name.startsWith("terminal_") -> "terminal"
    name in setOf("search_web", "scrape_web", "search_sources_status", "search_strategy_explain", "http_request") -> "web"
    name.startsWith("webview_") -> "webview"
    name.startsWith("wm_") -> "webmount"
    name.startsWith("hn_") -> "webmount_hackernews"
    name.startsWith("reddit_") -> "webmount_reddit"
    name.startsWith("juejin_") -> "webmount_juejin"
    name.startsWith("feishu_docs_") -> "webmount_feishu_docs"
    name.startsWith("github_") -> "webmount_github"
    name.startsWith("bilibili_") -> "webmount_bilibili"
    name.startsWith("zhihu_") -> "webmount_zhihu"
    name.startsWith("screen_") || name == "vlm_task" -> "screen"
    name.startsWith("sms_") || name.startsWith("contacts_") || name.startsWith("calendar_") ||
        name.startsWith("call_") || name.startsWith("apps_") || name.startsWith("app_") ||
        name in setOf("device_phone_state", "media_search", "location_current", "audio_record_once", "notification_list", "usage_stats_list", "battery_status", "network_status", "wifi_status", "device_info", "settings_open", "intent_open", "share_text", "share_file", "notification_post") -> "system"
    name.startsWith("memory_") -> "memory"
    name == "agent_prompt_config" -> "prompt_config"
    name.startsWith("conversation_") || name.startsWith("session_") -> "context"
    name.startsWith("deep_read_") -> "deep_read"
    name.startsWith("cron_task_") -> "cron"
    name.startsWith("agent_task_") || name == "agent_runtime_status" -> "task"
    name in setOf("tool_policy_explain", "tool_search", "tools_list") -> "utility"
    name.startsWith("subagent_") -> "subagent"
    name.startsWith("model_council_") -> "model_council"
    name.startsWith("skill") || name == "use_skill" -> "skill"
    name.startsWith("mcp_") || name.startsWith("mcp__") -> "mcp"
    else -> "utility"
}

private fun Tool.mutatesState(): Boolean {
    if (name == "memory_tool") return true
    if (name == "deep_read_open") return true
    return name.contains("_write") ||
        name.contains("_edit") ||
        name.contains("_move") ||
        name.contains("_delete") ||
        name.contains("_install") ||
        name.contains("_stop") ||
        name == "pdf_render_page" ||
        name == "mcp_call_tool" ||
        name == "officepro_make_report" ||
        name == "officepro_project_update" ||
        name == "agent_prompt_config" ||
        name == "model_council_make_report" ||
        name in setOf("cron_task_create", "cron_task_update", "cron_task_delete") ||
        name in setOf("agent_task_cancel", "agent_task_retry", "agent_task_cleanup") ||
        name.startsWith("memory_") && name != "memory_list" ||
        name == "conversation_compact" ||
        name == "deep_read_finish" ||
        name in setOf("subagent_start", "subagent_cancel") ||
        name in setOf("wm_click", "wm_tap", "wm_type", "wm_keys", "wm_select") ||
        name.startsWith("skill_enable") ||
        name.startsWith("skill_disable")
}

private fun Tool.risk(): ToolRisk = when {
    name == "http_request" -> ToolRisk.High
    name == "memory_tool" -> ToolRisk.High
    name == "mcp_call_tool" -> ToolRisk.Sensitive
    name == "wm_eval" -> ToolRisk.High
    name in setOf("wm_click", "wm_tap", "wm_type", "wm_keys", "wm_select") -> ToolRisk.Sensitive
    name in setOf("session_read", "session_expand") -> ToolRisk.Sensitive
    name == "pdf_render_page" -> ToolRisk.High
    name in setOf("agent_task_cancel", "agent_task_retry", "agent_task_cleanup") -> ToolRisk.Sensitive
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

private fun Tool.concurrencySafe(): Boolean = when {
    name in setOf("terminal_install_packages", "terminal_job_stop", "terminal_execute", "terminal_job_start") -> false
    name.startsWith("cron_task_") && name != "cron_task_list" -> false
    name.startsWith("agent_task_") || name in setOf("agent_runtime_status", "tool_policy_explain", "tool_search", "tools_list") -> true
    name.startsWith("subagent_") || name.startsWith("model_council_") -> false
    else -> !mutatesState()
}

private fun Tool.sensitiveRead(): Boolean =
    name in setOf("session_read", "session_expand") ||
        name.startsWith("screen_") ||
        name in setOf(
            "officepro_read_screen",
            "officepro_capture_context",
            "officepro_context_digest",
        )

private fun Tool.foregroundPackageRequirement(): String? = when (name) {
    "officepro_read_screen",
    "officepro_capture_context",
    "officepro_context_digest",
    "officepro_daily_radar",
    "officepro_project_briefing",
    "officepro_document_warroom",
    "officepro_open_items_radar",
    "officepro_meeting_closure" -> "configured_officepro_target"
    else -> null
}

private fun Tool.speculativeBlockReason(
    category: String,
    mutates: Boolean,
    needsApproval: Boolean,
    concurrencySafe: Boolean,
    risk: ToolRisk,
    parallelGroup: String?,
    requiresForegroundAppPackage: String?,
): String? = when {
    risk != ToolRisk.Normal -> "risk_not_normal"
    mutates -> "mutates_state"
    needsApproval -> "needs_approval"
    !concurrencySafe -> "not_concurrency_safe"
    parallelGroup == null -> "no_parallel_group"
    requiresForegroundAppPackage != null -> "requires_foreground_app"
    category in setOf("terminal", "screen", "office", "external_file", "subagent", "model_council") -> "category_blocked"
    category == "cron" && name != "cron_task_list" -> "cron_mutation_blocked"
    category == "memory" && mutates -> "memory_write_blocked"
    else -> null
}

private fun Tool.outputBudgetChars(): Int = when (name) {
    "file_read" -> FILE_READ_HARD_MAX_CHARS + 2_048
    // Screenshots inline their base64 image in the Text payload (Image parts
    // are silently dropped by every provider's tool-result serializer). A
    // 412×915 PNG ≈ 300 KB base64; JPEG q=85 ≈ 80 KB. Full-page screenshots
    // are best taken as JPEG.
    "wm_screenshot" -> 1_200_000
    "wm_observe" -> 180_000
    "wm_fetch_replay" -> 220_000
    // Phase 2 M2.2: signed_fetch bodies can be ~1MB (cap in the shim).
    // Add headroom for the JSON envelope.
    "wm_signed_fetch" -> 1_100_000
    // WebMount adapters whose Tool ctor advertises a >80k max_chars cap. Without
    // these overrides, the registry truncates them at 80k right after the tool
    // produces the larger payload — pure waste. Cap each at "claimed max +
    // small overhead for envelope JSON".
    "feishu_docs_read" -> 220_000
    "feishu_docs_blocks" -> 180_000
    "feishu_docs_snapshot" -> 180_000
    "feishu_docs_markdown_pack" -> 220_000
    "github_file_read" -> 220_000
    "zhihu_answer_read" -> 90_000
    "zhihu_question_read" -> 100_000
    "model_council_status",
    "model_council_start",
    "model_council_wait",
    "model_council_cancel",
    "model_council_read" -> MODEL_COUNCIL_TOOL_OUTPUT_BUDGET_CHARS

    "subagent_start",
    "subagent_wait",
    "subagent_cancel",
    "subagent_read" -> SUB_AGENT_TOOL_OUTPUT_BUDGET_CHARS

    else -> DEFAULT_TOOL_OUTPUT_BUDGET_CHARS
}

private fun JsonElement?.stringValue(name: String): String? =
    runCatching { this?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull }.getOrNull()

private fun JsonElement?.booleanValue(name: String): Boolean =
    runCatching { this?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) }
        .getOrNull() == true

private fun JsonElement?.containsExternalCliCouncilSeat(): Boolean {
    val root = runCatching { this?.jsonObject }.getOrNull() ?: return false
    val task = runCatching { root["task"]?.jsonObject }.getOrNull() ?: root
    return task["planned_seats"].containsExternalCliSeat() || task["seats"].containsExternalCliSeat()
}

private fun JsonElement?.allowsExternalCliCouncil(): Boolean {
    val root = runCatching { this?.jsonObject }.getOrNull() ?: return false
    val task = runCatching { root["task"]?.jsonObject }.getOrNull()
    return this.booleanValue("allow_external_cli") || task.booleanValue("allow_external_cli")
}

private fun JsonElement?.containsExternalCliSeat(): Boolean {
    val seats = runCatching { this?.jsonArray }.getOrNull() ?: return false
    return seats.any { item ->
        val seat = runCatching { item.jsonObject }.getOrNull() ?: return@any false
        val runnerType = seat["runner_type"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT).orEmpty()
        val externalTool = seat["external_tool"]?.jsonPrimitive?.contentOrNull.orEmpty()
        runnerType in setOf("external_cli", "cli", "gemini_cli", "antigravity_cli", "codex_cli", "claude_code", "kimi_cli") ||
            externalTool.isNotBlank()
    }
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
private const val MODEL_COUNCIL_TOOL_OUTPUT_BUDGET_CHARS =
    EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS *
        DEFAULT_MODEL_COUNCIL_MAX_SEATS *
        DEFAULT_MODEL_COUNCIL_MAX_ROUNDS +
        512_000
private const val SUB_AGENT_TOOL_OUTPUT_BUDGET_CHARS =
    EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS + 64_000
