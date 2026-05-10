package me.rerere.rikkahub.data.agent.subagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

object SubAgentValidator {
    private val genericNameParts = listOf(
        "general", "helper", "all-purpose", "allpurpose", "fullstack", "universal", "万能", "通用", "全能", "全栈"
    )

    val defaultDynamicReadOnlyTools = setOf(
        "tools_list", "file_list", "file_read", "file_search",
        "conversation_search", "conversation_expand",
        "session_search",
        "officepro_status", "officepro_dashboard",
        "search_web", "scrape_web",
        "apps_list", "apps_installed_list", "permissions_status", "skills_list", "mcp_list",
    )

    fun parseTask(input: JsonObject): SubAgentTaskSpec {
        val task = input["task"]?.jsonObject ?: error("task object is required")
        val spec = SubAgentTaskSpec(
            objective = task.string("objective"),
            outputFormat = task.string("output_format"),
            toolsAndSources = task.string("tools_and_sources"),
            boundaries = task.string("boundaries"),
            context = task.stringOrBlank("context"),
            sessionGrantId = task.stringOrBlank("session_grant_id"),
            sourceSessionIds = task["source_session_ids"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty(),
            historyQuery = task.stringOrBlank("history_query"),
            shardIndex = task["shard_index"]?.jsonPrimitive?.intOrNull ?: 0,
            shardCount = task["shard_count"]?.jsonPrimitive?.intOrNull ?: 1,
        )
        validateTask(spec)
        return spec
    }

    fun validateTask(task: SubAgentTaskSpec) {
        require(task.objective.isNotBlank()) { "task.objective is required" }
        require(task.outputFormat.isNotBlank()) { "task.output_format is required" }
        require(task.toolsAndSources.isNotBlank()) { "task.tools_and_sources is required" }
        require(task.boundaries.isNotBlank()) { "task.boundaries is required" }
    }

    fun resolveDefinition(
        input: JsonObject,
        setting: SubAgentRuntimeSetting,
        availableToolNames: Set<String>,
    ): SubAgentValidationResult {
        input["subagent_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { id ->
            // Match against built-ins, then user-saved custom definitions, applying user overrides for built-ins.
            // Use builtIn.id (canonical) for override lookup — `find()` allows name-fallback,
            // but overrides map is keyed by canonical id only.
            val builtIn = SubAgentDefinitions.find(id)
            if (builtIn != null) {
                return SubAgentValidationResult(builtIn.applyOverride(setting.overrides[builtIn.id]).cappedBy(setting))
            }
            val custom = setting.customDefinitions.firstOrNull {
                it.id == id || it.name.equals(id, ignoreCase = true)
            } ?: error("Unknown subagent_id: $id")
            return SubAgentValidationResult(custom.cappedBy(setting))
        }

        val custom = input["custom_subagent"]?.jsonObject ?: error("subagent_id or custom_subagent is required")
        require(setting.allowDynamicSubAgents) { "Dynamic subagents are disabled in settings" }

        val rawName = custom.string("name")
        val id = normalizeId(rawName)
        val description = custom.string("description")
        val systemPrompt = custom.string("system_prompt")
        validateNarrowDynamicRole(id, description, systemPrompt)

        val explicitTools = custom["tool_allowlist"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
        val requestedTools = explicitTools ?: defaultDynamicReadOnlyTools
            .filter { it in availableToolNames }
            .toSet()
        require(requestedTools.isNotEmpty()) {
            "Dynamic subagent has no available read-only tools"
        }
        validateToolAllowlist(requestedTools, availableToolNames)

        val modelIdRaw = custom["model_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val parsedModelId = modelIdRaw?.let { raw ->
            runCatching { Uuid.parse(raw) }.getOrElse {
                error("custom_subagent.model_id is not a valid UUID: $raw")
            }
        }
        val temperatureRaw = custom["temperature"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val parsedTemperature = temperatureRaw?.let { raw ->
            raw.toFloatOrNull() ?: error("custom_subagent.temperature is not a number: $raw")
        }
        val reasoningRaw = custom["reasoning_level"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val parsedReasoning = reasoningRaw?.let { value ->
            ReasoningLevel.entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.toString() == value }
                ?: error("custom_subagent.reasoning_level must be one of ${ReasoningLevel.entries.joinToString { it.name.lowercase() }}; got: $value")
        }

        val definition = SubAgentDefinition(
            id = id,
            name = rawName,
            description = description,
            systemPrompt = systemPrompt,
            toolAllowlist = requestedTools,
            maxTurns = custom["max_turns"]?.jsonPrimitive?.intOrNull ?: setting.maxTurns,
            timeoutMs = custom["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: setting.timeoutMs,
            outputBudgetChars = custom["output_budget_chars"]?.jsonPrimitive?.intOrNull ?: setting.outputBudgetChars,
            dynamic = true,
            modelId = parsedModelId,
            temperature = parsedTemperature,
            reasoningLevel = parsedReasoning,
            routingHint = custom["routing_hint"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
        )
        validateBudgets(definition, setting)
        return SubAgentValidationResult(definition)
    }

    fun validateToolAllowlist(toolAllowlist: Set<String>, availableToolNames: Set<String>) {
        require(toolAllowlist.none { it.startsWith("subagent_") }) {
            "Subagents cannot call subagent_* tools"
        }
        val missing = toolAllowlist.filterNot { it in availableToolNames }
        require(missing.isEmpty()) {
            "Tool allowlist contains unavailable tools: ${missing.sorted().joinToString(", ")}"
        }
    }

    fun validateNarrowDynamicRole(id: String, description: String, systemPrompt: String) {
        require(id.isNotBlank()) { "custom_subagent.name must produce a non-empty id" }
        require(genericNameParts.none { id.contains(it, ignoreCase = true) }) {
            "Dynamic subagent name is too broad: $id"
        }
        require(description.length >= 24 && description.hasInvocationCue()) {
            "custom_subagent.description must explain when this subagent should be invoked"
        }
        require(systemPrompt.length >= 80) { "custom_subagent.system_prompt is too short" }
        require(systemPrompt.hasBoundaryCue()) {
            "custom_subagent.system_prompt must include explicit boundaries"
        }
        require(systemPrompt.hasReportCue()) {
            "custom_subagent.system_prompt must include report/output instructions"
        }
    }

    fun validateBudgets(definition: SubAgentDefinition, setting: SubAgentRuntimeSetting) {
        require(definition.maxTurns in 1..setting.maxTurns.coerceAtLeast(1)) {
            "custom_subagent.max_turns exceeds setting limit ${setting.maxTurns}"
        }
        require(definition.timeoutMs in 1_000L..setting.timeoutMs.coerceAtLeast(1_000L)) {
            "custom_subagent.timeout_ms exceeds setting limit ${setting.timeoutMs}"
        }
        require(definition.outputBudgetChars in 1_000..setting.outputBudgetChars.coerceAtLeast(1_000)) {
            "custom_subagent.output_budget_chars exceeds setting limit ${setting.outputBudgetChars}"
        }
    }

    fun normalizeId(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9\\-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')

    private fun SubAgentDefinition.cappedBy(setting: SubAgentRuntimeSetting) = copy(
        maxTurns = maxTurns.coerceAtMost(setting.maxTurns.coerceAtLeast(1)),
        timeoutMs = timeoutMs.coerceAtMost(setting.timeoutMs.coerceAtLeast(1_000L)),
        outputBudgetChars = outputBudgetChars.coerceAtMost(setting.outputBudgetChars.coerceAtLeast(1_000)),
    )

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().also {
            require(it.isNotBlank()) { "$name is required" }
        }

    private fun JsonObject.stringOrBlank(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun String.hasInvocationCue(): Boolean {
        val lower = lowercase()
        return listOf("when", "invoke", "use for", "用于", "适合", "何时", "调用").any { lower.contains(it) }
    }

    private fun String.hasBoundaryCue(): Boolean {
        val lower = lowercase()
        return listOf("boundary", "boundaries", "do not", "never", "边界", "不要", "禁止").any { lower.contains(it) }
    }

    private fun String.hasReportCue(): Boolean {
        val lower = lowercase()
        return listOf("report", "output", "return", "summary", "输出", "返回", "报告", "摘要").any { lower.contains(it) }
    }
}
