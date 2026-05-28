package app.amber.feature.subagent

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.TOOL_SEARCH_TOOL_NAME
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.createToolSearchTool
import app.amber.core.agent.utils.JsonInstant

private const val TOOLS_LIST_TOOL_NAME = "tools_list"
private const val TOOL_POLICY_EXPLAIN_TOOL_NAME = "tool_policy_explain"

private val SUBAGENT_DISCOVERY_TOOL_NAMES = setOf(
    TOOL_SEARCH_TOOL_NAME,
    TOOLS_LIST_TOOL_NAME,
    TOOL_POLICY_EXPLAIN_TOOL_NAME,
)

fun scopedSubAgentTools(allowedTools: List<Tool>): List<Tool> {
    if (allowedTools.isEmpty()) return emptyList()
    val requestedTools = allowedTools.map { it.name }.toSet()
    val executableTools = allowedTools.filterNot { it.name in SUBAGENT_DISCOVERY_TOOL_NAMES }
    val scopedRegistry = ToolRegistry.from(executableTools)
    return executableTools + buildList {
        add(createToolSearchTool(scopedRegistry))
        if (TOOLS_LIST_TOOL_NAME in requestedTools) add(createScopedToolsListTool(scopedRegistry))
        if (TOOL_POLICY_EXPLAIN_TOOL_NAME in requestedTools) add(createScopedToolPolicyExplainTool(scopedRegistry))
    }
}

private fun createScopedToolsListTool(registry: ToolRegistry) = Tool(
    name = TOOLS_LIST_TOOL_NAME,
    description = "List tools available inside this subagent scope. This catalog is scoped and cannot reveal parent-only tools.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional scoped tool category filter.")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional scoped tool name or description filter.")
                })
                put("include_schema", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Include scoped tool input schema. Defaults to false.")
                })
            }
        )
    },
    execute = { input ->
        val categoryFilter = input.jsonObject["category"]?.jsonPrimitive?.contentOrNull
        val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val includeSchema = input.jsonObject["include_schema"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        val toolDefinitions = registry.tools().associateBy { it.name }
        val tools = registry.metadata
            .filter { metadata -> categoryFilter == null || metadata.category == categoryFilter }
            .filter { metadata ->
                val tool = toolDefinitions[metadata.name]
                query.isBlank() ||
                    metadata.name.contains(query, ignoreCase = true) ||
                    tool?.description.orEmpty().contains(query, ignoreCase = true)
            }
        val payload = buildJsonObject {
            put("scoped", true)
            put("enabled_count", tools.size)
            put("tools", buildJsonArray {
                tools.forEach { metadata ->
                    val tool = toolDefinitions[metadata.name]
                    add(
                        buildJsonObject {
                            put("name", metadata.name)
                            put("category", metadata.category)
                            put("description", tool?.description.orEmpty().take(240))
                            put("enabled", true)
                            put("mutates", metadata.mutates)
                            put("sensitive_read", metadata.sensitiveRead)
                            put("needs_approval", metadata.needsApproval)
                            put("allows_auto_approval", metadata.autoApprovable)
                            put("output_budget_chars", metadata.outputBudgetChars)
                            put("risk", metadata.risk.name)
                            val invocationPolicy = registry.evaluateInvocation(metadata.name)
                            put("concurrency_safe", invocationPolicy?.concurrencySafe ?: true)
                            put("speculative_eligible", invocationPolicy?.speculativeEligible ?: false)
                            invocationPolicy?.speculativeBlockReason?.let { put("speculative_block_reason", it) }
                            if (includeSchema && tool != null) {
                                put("schema", tool.parameters()?.toString().orEmpty())
                            }
                        }
                    )
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun createScopedToolPolicyExplainTool(registry: ToolRegistry) = Tool(
    name = TOOL_POLICY_EXPLAIN_TOOL_NAME,
    description = "Explain how this subagent scope would evaluate one allowed tool invocation without executing it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tool_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Tool name to evaluate within this subagent scope.")
                })
                put("input", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional JSON string input for dynamic policy evaluation.")
                })
            },
            required = listOf("tool_name")
        )
    },
    execute = { input ->
        val toolName = input.jsonObject["tool_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rawInput = input.jsonObject["input"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val toolInput = runCatching {
            JsonInstant.parseToJsonElement(rawInput.ifBlank { "{}" })
        }.getOrNull()
        val policy = registry.evaluateInvocation(toolName, toolInput)
        val payload = buildJsonObject {
            put("scoped", true)
            put("status", if (policy == null) "not_found" else "ok")
            put("tool_name", toolName)
            policy?.let {
                put("category", it.category)
                put("risk", it.risk.name.lowercase())
                put("mutates", it.mutates)
                put("needs_approval", it.needsApproval)
                put("allows_auto_approval", it.autoApprovable)
                put("concurrency_safe", it.concurrencySafe)
                put("speculative_eligible", it.speculativeEligible)
                it.speculativeBlockReason?.let { reason -> put("speculative_block_reason", reason) }
                put("output_budget_chars", it.outputBudgetChars)
                put("hard_blocked", it.hardBlocked)
                it.reason?.let { reason -> put("reason", reason) }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
