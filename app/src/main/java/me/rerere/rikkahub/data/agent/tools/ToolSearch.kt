package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.Locale

const val TOOL_SEARCH_TOOL_NAME = "tool_search"
const val TOOL_SEARCH_AUTO_THRESHOLD = 40
const val TOOL_SEARCH_DEFAULT_LIMIT = 5

private val toolSearchJson = Json { ignoreUnknownKeys = true }

fun createToolSearchTool(registry: ToolRegistry) = Tool(
    name = TOOL_SEARCH_TOOL_NAME,
    description = "Search AmberAgent's full tool catalog by intent/category and expose the best matching tool schemas for the next step.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", stringProp("Required. What capability you need, e.g. \"read PDF\", \"call Feishu MCP\", \"webview click\", or \"list calendar\"."))
                put("category", stringProp("Optional category filter, e.g. workspace, terminal, web, webview, webmount, screen, system, memory, context, subagent, model_council, mcp, office, skill."))
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum tools to expose. Defaults to 5; capped at 20.")
                })
            },
            required = listOf("query")
        )
    },
    needsApproval = false,
    allowsAutoApproval = true,
    systemPrompt = { _, _ ->
        val index = ToolSearchIndex(registry)
        val categories = index.categoryCounts().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${it.key}:${it.value}" }
        val residentCount = registry.tools().count { tool ->
            val metadata = registry.metadataFor(tool.name)
            ToolExposureState.isResidentTool(tool.name, metadata?.category)
        }
        """
        Tool discovery:
        - This run has ${registry.metadata.size} generated tools across categories: $categories.
        - If the needed tool is not currently visible, call `$TOOL_SEARCH_TOOL_NAME` with a concrete query. It exposes the best matching schemas for the next generation step.
        - `tools_list` is a debug/catalog view; prefer `$TOOL_SEARCH_TOOL_NAME` for ordinary tool discovery when the catalog is large.
        - Resident tools currently stay visible without search: $residentCount core tools plus discovered tools.
        """.trimIndent()
    },
    execute = { input ->
        val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val category = input.jsonObject["category"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: TOOL_SEARCH_DEFAULT_LIMIT
        val index = ToolSearchIndex(registry)
        listOf(UIMessagePart.Text(index.searchPayload(query, category, limit).toString()))
    },
)

class ToolSearchIndex(
    private val registry: ToolRegistry,
) {
    private val toolsByName = registry.tools().associateBy { it.name }

    fun categoryCounts(): Map<String, Int> =
        registry.metadata.groupingBy { it.category }.eachCount()

    fun searchPayload(
        query: String,
        category: String?,
        limit: Int,
    ): JsonObject {
        val normalizedCategory = category?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null }
        val boundedLimit = limit.coerceIn(1, 20)
        val matches = search(query, normalizedCategory, boundedLimit)
        val expandedTools = matches.map { it.metadata.name }
        val fullSchemaChars = registry.tools().sumOf { it.schemaFootprintChars() }
        val residentSchemaChars = registry.tools()
            .filter { tool -> ToolExposureState.isResidentTool(tool.name, registry.metadataFor(tool.name)?.category) }
            .sumOf { it.schemaFootprintChars() }
        val expandedSchemaChars = matches.sumOf { it.tool.schemaFootprintChars() }
        return buildJsonObject {
            put("status", "ok")
            put("query", query)
            normalizedCategory?.let { put("category", it) }
            put("limit", boundedLimit)
            put("total_tools", registry.metadata.size)
            put("resident_tools", registry.tools().count { tool ->
                ToolExposureState.isResidentTool(tool.name, registry.metadataFor(tool.name)?.category)
            })
            put("matches_count", matches.size)
            put("expanded_tools", buildJsonArray { expandedTools.forEach(::add) })
            put("trace", buildJsonObject {
                put("mode", if (registry.metadata.size > TOOL_SEARCH_AUTO_THRESHOLD) "lazy" else "bypass")
                put("query", query)
                put("hit_tools", buildJsonArray { expandedTools.forEach(::add) })
                put("expanded_tools", buildJsonArray { expandedTools.forEach(::add) })
                put("estimated_full_schema_chars", fullSchemaChars)
                put("estimated_resident_schema_chars", residentSchemaChars)
                put("estimated_expanded_schema_chars", expandedSchemaChars)
                put("estimated_schema_savings_chars", (fullSchemaChars - residentSchemaChars - expandedSchemaChars).coerceAtLeast(0))
            })
            put("tools", buildJsonArray { matches.forEach { add(it.toJson()) } })
            if (matches.isEmpty()) {
                put("category_candidates", buildJsonArray {
                    categoryCounts().entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .take(16)
                        .forEach { (name, count) ->
                            add(buildJsonObject {
                                put("category", name)
                                put("count", count)
                            })
                        }
                })
                put("debug_hint", "No matching tool was expanded. Try a more concrete query or call tools_list(category=..., include_schema=false) for a catalog view.")
            } else {
                put("next_step", "The tools in expanded_tools are available with full schemas on the next model step. Execute them normally; permissions still apply.")
            }
        }
    }

    private fun search(
        query: String,
        category: String?,
        limit: Int,
    ): List<ScoredTool> {
        val tokens = query.lowercase(Locale.ROOT)
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return registry.metadata
            .asSequence()
            .filter { it.name != TOOL_SEARCH_TOOL_NAME }
            .filter { category == null || it.category.lowercase(Locale.ROOT) == category }
            .mapNotNull { metadata ->
                val tool = toolsByName[metadata.name] ?: return@mapNotNull null
                val score = scoreTool(metadata, tool, tokens, category)
                if (score <= 0) null else ScoredTool(metadata, tool, score)
            }
            .sortedWith(compareByDescending<ScoredTool> { it.score }.thenBy { it.metadata.name })
            .take(limit)
            .toList()
    }

    private fun scoreTool(
        metadata: ToolMetadata,
        tool: Tool,
        tokens: List<String>,
        category: String?,
    ): Int {
        var score = 0
        if (category != null && metadata.category.lowercase(Locale.ROOT) == category) score += 25
        if (tokens.isEmpty()) return if (category != null) score + 1 else 0
        val name = metadata.name.lowercase(Locale.ROOT)
        val description = tool.description.lowercase(Locale.ROOT)
        val categoryText = metadata.category.lowercase(Locale.ROOT)
        tokens.forEach { token ->
            score += when {
                name == token -> 120
                name.startsWith(token) -> 80
                name.contains(token) -> 55
                categoryText == token -> 35
                categoryText.contains(token) -> 24
                description.contains(token) -> 16
                else -> 0
            }
        }
        if (tokens.any { token -> name.split('_').contains(token) }) score += 30
        if (score > 0 && !metadata.mutates && metadata.risk == ToolRisk.Normal) score += 2
        return score
    }

    private fun ScoredTool.toJson(): JsonObject = buildJsonObject {
        put("name", metadata.name)
        put("category", metadata.category)
        put("description", tool.description.take(360))
        put("score", score)
        put("mutates", metadata.mutates)
        put("sensitive_read", metadata.sensitiveRead)
        put("needs_approval", metadata.needsApproval)
        put("allows_auto_approval", metadata.autoApprovable)
        put("risk", metadata.risk.name.lowercase(Locale.ROOT))
        put("output_budget_chars", metadata.outputBudgetChars)
        put("schema", tool.parameters()?.toString().orEmpty())
    }

    private data class ScoredTool(
        val metadata: ToolMetadata,
        val tool: Tool,
        val score: Int,
    )
}

class ToolExposureState private constructor(
    private val allTools: List<Tool>,
    private val lazyMode: Boolean,
    initialExposedNames: Set<String>,
) {
    private val toolsByName = allTools.associateBy { it.name }
    private val exposedNames = initialExposedNames.toMutableSet()

    val enabled: Boolean
        get() = lazyMode

    fun toolsForStep(): List<Tool> =
        if (!lazyMode) allTools else allTools.filter { it.name in exposedNames }

    fun observeExecutedTools(executedTools: List<UIMessagePart.Tool>) {
        if (!lazyMode) return
        executedTools
            .filter { it.toolName == TOOL_SEARCH_TOOL_NAME }
            .flatMap { it.expandedToolNames() }
            .filter { it in toolsByName }
            .forEach { exposedNames += it }
    }

    companion object {
        fun from(tools: List<Tool>): ToolExposureState {
            val toolCount = tools.count { it.name !in DISCOVERY_UTILITY_TOOLS }
            val hasSearch = tools.any { it.name == TOOL_SEARCH_TOOL_NAME }
            if (toolCount <= TOOL_SEARCH_AUTO_THRESHOLD || !hasSearch) {
                return ToolExposureState(tools, lazyMode = false, initialExposedNames = tools.map { it.name }.toSet())
            }
            val registry = runCatching { ToolRegistry.from(tools) }.getOrNull()
            val initial = tools.filter { tool ->
                isResidentTool(tool.name, registry?.metadataFor(tool.name)?.category)
            }.map { it.name }.toSet()
            return ToolExposureState(tools, lazyMode = true, initialExposedNames = initial)
        }

        fun isResidentTool(name: String, category: String?): Boolean = when {
            name in DISCOVERY_UTILITY_TOOLS -> true
            name in RESIDENT_EXACT_TOOLS -> true
            name.startsWith("subagent_") -> true
            name.startsWith("agent_task_") -> name in setOf("agent_task_list", "agent_task_read")
            category == "context" -> name in RESIDENT_CONTEXT_TOOLS
            else -> false
        }

        private val DISCOVERY_UTILITY_TOOLS = setOf(
            TOOL_SEARCH_TOOL_NAME,
            "tools_list",
            "tool_policy_explain",
        )

        private val RESIDENT_EXACT_TOOLS = setOf(
            "ask_user",
            "permissions_status",
            "agent_runtime_status",
            "file_list",
            "file_read",
            "file_write",
            "file_edit",
            "file_search",
            "file_move",
            "terminal_execute",
            "terminal_job_start",
            "terminal_job_read",
            "terminal_job_wait",
            "terminal_job_stop",
            "terminal_session_start",
            "terminal_session_exec",
            "terminal_session_read",
            "terminal_session_stop",
            "mcp_list",
            "mcp_call_tool",
        )

        private val RESIDENT_CONTEXT_TOOLS = setOf(
            "conversation_context_status",
            "conversation_search",
            "conversation_expand",
        )
    }
}

private fun UIMessagePart.Tool.expandedToolNames(): List<String> =
    output.filterIsInstance<UIMessagePart.Text>().flatMap { part ->
        runCatching {
            val payload = toolSearchJson.parseToJsonElement(part.text) as? JsonObject
                ?: return@runCatching emptyList()
            val names = payload["expanded_tools"] as? JsonArray ?: return@runCatching emptyList()
            names.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrDefault(emptyList())
    }

private fun Tool.schemaFootprintChars(): Int =
    name.length + description.length + (parameters()?.toString()?.length ?: 0)

private fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}
