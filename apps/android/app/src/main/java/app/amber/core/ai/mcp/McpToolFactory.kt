package app.amber.core.ai.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart

/**
 * P2-02: builds the model-facing MCP tool entries from server-scoped refs.
 *
 * - One namespaced entry per (server, tool) ref with the stable expanded name
 *   `mcp__server__tool`; the original server/tool names are preserved in the
 *   schema ([InputSchema.Obj.originalServerName]/[originalToolName]).
 * - Legacy gateway entries for old conversations that call `mcp__<tool>`:
 *   a unique match routes through the alias; multiple matches produce a
 *   structured error so the model re-selects explicitly (it never executes).
 * - A gateway whose legacy name collides with a namespaced name is dropped —
 *   the namespaced tool wins (pathological, deterministic).
 */
fun createMcpTools(
    refs: List<McpToolRef>,
    call: suspend (McpToolRef, JsonObject) -> List<UIMessagePart>,
): List<Tool> {
    val batch = McpToolNamespace.encodeBatch(refs)
    val namespacedNames = batch.values.toSet()
    val tools = mutableListOf<Tool>()

    refs.forEach { ref ->
        val expandedName = batch.getValue(ref)
        tools += Tool(
            name = expandedName,
            description = ref.description ?: "",
            parameters = { ref.inputSchema?.withMcpOrigin(ref.serverName, ref.toolName) },
            needsApproval = ref.needsApproval,
            // MCP server tools are externally supplied and may mutate remote
            // state; plain global auto-approval must never bypass the gate.
            allowsAutoApproval = false,
            execute = { input -> call(ref, input.jsonObject) },
        )
    }

    refs.groupBy { it.toolName }.forEach { (toolName, group) ->
        val legacyName = McpToolNamespace.PREFIX + toolName
        // Namespaced names are unique and win over an alias with the same text.
        if (legacyName in namespacedNames) return@forEach
        tools += when {
            group.size == 1 -> {
                val ref = group.first()
                Tool(
                    name = legacyName,
                    description = "Deprecated alias for the MCP tool ${ref.toolName} on " +
                        "server ${ref.serverName}; prefer ${batch.getValue(ref)}.",
                    parameters = { ref.inputSchema?.withMcpOrigin(ref.serverName, ref.toolName) },
                    needsApproval = ref.needsApproval,
                    allowsAutoApproval = false,
                    execute = { input -> call(ref, input.jsonObject) },
                )
            }

            else -> Tool(
                name = legacyName,
                description = "Multiple MCP servers expose the tool name '$toolName'. " +
                    "Call mcp_list to inspect servers, then mcp_call_tool with an explicit server.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject { },
                        required = emptyList(),
                    )
                },
                needsApproval = false,
                execute = { legacyAmbiguityError(toolName, group) },
            )
        }
    }
    return tools
}

/** Attach the original MCP identity to the schema sent to the model. */
fun InputSchema.withMcpOrigin(serverName: String, toolName: String): InputSchema = when (this) {
    is InputSchema.Obj -> copy(
        originalServerName = serverName,
        originalToolName = toolName,
    )
}

/** Structured error telling the model the legacy name is ambiguous. */
internal fun legacyAmbiguityError(
    toolName: String,
    matches: List<McpToolRef>,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("status", "mcp_tool_name_ambiguous")
            put(
                "message",
                "Multiple MCP servers expose the tool name '$toolName'. " +
                    "Use mcp_list to inspect servers and mcp_call_tool with an explicit server.",
            )
            put(
                "matches",
                buildJsonArray {
                    matches.forEach { ref ->
                        add(JsonPrimitive("${ref.serverName}/${ref.toolName}"))
                    }
                },
            )
            put("recoverable", JsonPrimitive(true))
        }.toString()
    )
)
