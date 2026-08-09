package app.amber.feature.tools

import app.amber.ai.core.Tool
import kotlinx.serialization.json.Json
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

private val bridgeJson = Json { ignoreUnknownKeys = true }

/**
 * P0-a: ObjC/Swift-facing facade over [ToolExposureState] for the iOS chat run.
 *
 * Kotlin/Native export constraints (reserved words, default arguments, sealed
 * classes) mean this surface only uses String / Boolean / List<Tool> /
 * List<String>. The registry is built internally from the passed declarations;
 * [tool_search] is appended by the bridge itself so iOS never declares it
 * twice. One bridge instance is owned per chat run (see
 * ChatGenerationCoordinator) — the same instance is reused across all tool
 * rounds of that run so a `tool_search` hit becomes callable on the NEXT round.
 */
class IosToolExposureBridge private constructor(
    private val allTools: List<Tool>,
    private val registry: ToolRegistry,
    private val exposureState: ToolExposureState,
) {
    constructor(tools: List<Tool>) : this(tools, ToolRegistry.from(tools))

    constructor(tools: List<Tool>, registry: ToolRegistry) : this(
        withSearchTool(tools, registry),
        registry,
        ToolExposureState.from(
            withSearchTool(tools, registry),
            residentPolicy = ::iosResidentToolPolicy,
        ),
    )

    /** Appends the discovery tool unless the caller already declared it (a
     *  bridge rebuilt from a previous bridge's `visibleTools()` already has it —
     *  appending again would make ToolRegistry.from throw on duplicates). */
    private companion object {
        fun withSearchTool(tools: List<Tool>, registry: ToolRegistry): List<Tool> =
            if (tools.any { it.name == TOOL_SEARCH_TOOL_NAME }) tools
            else tools + createToolSearchTool(registry)
    }

    /** Full declaration list including the appended tool_search tool. */
    fun fullToolDeclarations(): List<Tool> = allTools

    fun lazyModeEnabled(): Boolean = exposureState.enabled

    /**
     * Fix A: system-level discovery guidance for the iOS run. In lazy mode this
     * is the internal tool_search tool's systemPrompt text — it tells the model
     * that hidden tools are "not callable until" `tool_search` exposes them.
     * Non-lazy runs return an empty string (no discovery contract needed).
     */
    fun discoveryGuidance(): String =
        if (exposureState.enabled) toolSearchDiscoveryGuidance(registry) else ""

    fun visibleTools(): List<Tool> = exposureState.toolsForStep()

    fun exposeToolNames(names: List<String>) {
        exposureState.exposeToolNames(names)
    }

    /**
     * Executes a `tool_search` call locally: parses query/category/limit, runs
     * the shared search index, feeds the `expanded_tools` names back into the
     * exposure state (so hits are visible on the next model step), and returns
     * the payload JSON. Never throws — malformed arguments yield an error
     * payload instead.
     */
    fun executeToolSearch(argumentsJson: String): String {
        return runCatching {
            val input = bridgeJson.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
            val query = input["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val category = input["category"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: TOOL_SEARCH_DEFAULT_LIMIT
            val payload = ToolSearchIndex(registry).searchPayload(query, category, limit)
            val expanded = payload["expanded_tools"]?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            exposureState.exposeToolNames(expanded)
            payload.toString()
        }.getOrElse { toolSearchErrorPayload(it.message) }
    }

    /** JSON summary of lazy mode + schema savings (same footprint algorithm as the search payload). */
    fun savingsSummary(): String = buildJsonObject {
        put("lazy", exposureState.enabled)
        put("total_tools", allTools.size)
        put("visible_tools", exposureState.toolsForStep().size)
        put("estimated_full_schema_chars", allTools.sumOf { it.schemaFootprintChars() })
        put("estimated_visible_schema_chars", exposureState.toolsForStep().sumOf { it.schemaFootprintChars() })
    }.toString()

    /**
     * M5: executes the `tools_list` catalog call locally — the model-facing
     * guidance (`toolSearchDiscoveryGuidance`) tells the model to use
     * `tools_list` to identify exact tool names before `tool_search`, so iOS
     * must both DECLARE it (resident) and EXECUTE it. Returns the full catalog
     * as a `{status, total, tools:[{name, description}]}` JSON list (no
     * schemas, catalog/debug only). Never throws — malformed state yields an
     * error payload instead, mirroring `executeToolSearch`.
     */
    fun executeToolsList(): String {
        return runCatching {
            buildJsonObject {
                put("status", "ok")
                put("total", allTools.size)
                put("tools", buildJsonArray {
                    allTools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                        })
                    }
                })
            }.toString()
        }.getOrElse { toolSearchErrorPayload(it.message) }
    }

    private fun toolSearchErrorPayload(reason: String?): String = buildJsonObject {
        put("status", "error")
        put("error", "tool_search failed: ${reason ?: "invalid arguments"}")
    }.toString()
}

/**
 * Pinned iOS resident tool policy. Exact-name allowlist (no prefix rules) —
 * mirrors the real iOS declaration names from `iosToolDeclaration` in
 * ai-core/Tool.kt. Everything NOT in this set (wm_*, ish_handoff,
 * ios_ish_execute, mcp_test, mcp_import_from_skill, skill_validate,
 * skill_import, skill_enable, skill_disable, subagent_report) is deferred
 * until `tool_search` exposes it.
 */
internal val IOS_RESIDENT_TOOL_NAMES: Set<String> = setOf(
    TOOL_SEARCH_TOOL_NAME,
    "tools_list",
    "ask_user",
    "permissions_status",
    "memory_tool",
    "search_web",
    "scrape_web",
    "generate_image",
    "workspace_file_read",
    "workspace_file_write",
    "workspace_file_edit",
    "workspace_file_list",
    "workspace_file_search",
    "workspace_file_move",
    "workspace_artifact_read",
    "workspace_artifact_delete",
    "mcp_list",
    "mcp_call",
    "mcp_describe_tool",
    "skills_list",
    "use_skill",
    "subagent_dispatch",
    "model_council_run",
    "file_read_selected",
)

internal fun iosResidentToolPolicy(name: String, category: String?): Boolean =
    name in IOS_RESIDENT_TOOL_NAMES
