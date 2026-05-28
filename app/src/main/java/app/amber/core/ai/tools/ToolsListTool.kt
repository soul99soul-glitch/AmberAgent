package app.amber.core.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.system.AgentPermissionBroker
import app.amber.feature.tools.ToolExposureState
import app.amber.feature.tools.ToolRegistry

/**
 * Factory for the `tools_list` agent tool — lets the model enumerate currently
 * enabled tools (with category / approval policy / runtime invocation policy /
 * per-tool capability + permission state).
 *
 * Depends on [ToolRegistry] (the registry built for this run — passed per-call
 * because each run produces a fresh registry) and [AgentPermissionBroker]
 * (capability ↔ tool mapping + current permission status).
 *
 * Extracted from `LocalTools.createToolsListTool` in M1.4 continuation.
 */
fun createToolsListTool(
    registry: ToolRegistry,
    permissionBroker: AgentPermissionBroker,
): Tool = Tool(
    name = "tools_list",
    description = "Debug/catalog view of AmberAgent's full tool catalog. In lazy mode, hidden tools listed here are not callable until exposed by tool_search.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional category filter: workspace, external_file, cloud, office, terminal, web, webview, screen, system, memory, context, cron, task, subagent, model_council, skill, mcp, utility.")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional name or description filter")
                })
                put("include_disabled", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Stage 1 lists enabled tools only; when true, the response explains this limitation.")
                })
                put("include_schema", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Include tool input schema. Defaults to false.")
                })
            }
        )
    },
    execute = { input ->
        val categoryFilter = input.jsonObject["category"]?.jsonPrimitive?.contentOrNull
        val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val includeSchema = input.jsonObject["include_schema"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val includeDisabled = input.jsonObject["include_disabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
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
            put("enabled_count", tools.size)
            put("include_disabled_supported", false)
            put("catalog_mode", "debug")
            put("callability_note", "This is a full catalog/debug view. In lazy tool mode, hidden tools listed here are not callable until tool_search exposes their schemas.")
            put("next_action", "To call a non-resident tool from this list, call tool_search with query set to the exact tool name, then call it on the next model step.")
            if (includeDisabled) {
                put("note", "Disabled tool enumeration is not available in stage1 because tools are generated from the current agent configuration.")
            }
            put(
                "tools",
                buildJsonArray {
                    tools.forEach { metadata ->
                        val tool = toolDefinitions[metadata.name]
                        val capabilities = permissionBroker.capabilities.filter { capability ->
                            metadata.name in capability.toolNames
                        }
                        add(
                            buildJsonObject {
                                put("name", metadata.name)
                                put("category", metadata.category)
                                put("description", tool?.description.orEmpty().take(240))
                                put("enabled", true)
                                put("resident", ToolExposureState.isResidentTool(metadata.name, metadata.category))
                                put("mutates", metadata.mutates)
                                put("sensitive_read", metadata.sensitiveRead)
                                put("needs_approval", metadata.needsApproval)
                                put("allows_auto_approval", metadata.autoApprovable)
                                put("output_budget_chars", metadata.outputBudgetChars)
                                put("dynamic_policy_supported", true)
                                val invocationPolicy = registry.evaluateInvocation(metadata.name)
                                put("concurrency_safe", invocationPolicy?.concurrencySafe ?: true)
                                invocationPolicy?.parallelGroup?.let { put("parallel_group", it) }
                                invocationPolicy?.requiresForegroundAppPackage?.let {
                                    put("requires_foreground_app_package", it)
                                }
                                put("speculative_eligible", invocationPolicy?.speculativeEligible ?: false)
                                invocationPolicy?.speculativeBlockReason?.let {
                                    put("speculative_block_reason", it)
                                }
                                put("risk", capabilities.maxByOrNull { it.risk.ordinal }?.risk?.name ?: metadata.risk.name)
                                put("required_permissions", buildJsonArray {
                                    capabilities.forEach { capability ->
                                        add(
                                            buildJsonObject {
                                                put("capability_id", capability.id)
                                                put("title", capability.title)
                                                put("status", permissionBroker.getStatus(capability).name.lowercase())
                                            }
                                        )
                                    }
                                })
                                if (includeSchema && tool != null) {
                                    put("schema", tool.parameters()?.toString().orEmpty())
                                }
                            }
                        )
                    }
                }
            )
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
