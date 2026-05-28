package app.amber.core.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.core.utils.JsonInstant
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.mcp.McpManager
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.ai.mcp.McpStatus
import app.amber.core.ai.mcp.parseMcpServersFromJson
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.files.SkillManager

fun createMcpManagementTools(
    settingsStore: SettingsAggregator,
    mcpManager: McpManager,
    skillManager: SkillManager,
): List<Tool> = listOf(
    Tool(
        name = "mcp_list",
        description = "List configured MCP servers, enabled state, connection status, and known tool counts. Pass include_tools=true to see callable MCP tool names.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("include_tools", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include enabled/disabled tool names for each server. Defaults to true.")
                    })
                    put("include_schema", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include MCP input schemas. Defaults to false.")
                    })
                }
            )
        },
        execute = { input ->
            val includeTools = input.jsonObject["include_tools"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            val includeSchema = input.jsonObject["include_schema"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val settings = settingsStore.settingsFlow.value
            val statusMap = mcpManager.syncingStatus.value
            val payload = buildJsonObject {
                put("servers", buildJsonArray {
                    settings.mcpServers.forEach { server ->
                        add(server.toJson(statusMap[server.id], includeTools, includeSchema))
                    }
                })
                put("call_tool", "Use mcp_call_tool with server_id or name, tool_name, and arguments to call one of these tools directly.")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "mcp_call_tool",
        description = "Call one tool exposed by a configured MCP server. Use mcp_list include_tools=true first to discover server_id/name, tool_name, and input schema.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("server_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional MCP server id. Recommended when multiple servers expose the same tool name.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional MCP server name.")
                    })
                    put("tool_name", buildJsonObject {
                        put("type", "string")
                        put("description", "MCP tool name to call, for example search_doc.")
                    })
                    put("arguments", buildJsonObject {
                        put("type", "object")
                        put("description", "JSON object arguments for the MCP tool. A JSON string is also accepted for compatibility.")
                    })
                },
                required = listOf("tool_name"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = true,
        execute = { input ->
            val serverId = input.jsonObject["server_id"]?.jsonPrimitive?.contentOrNull
            val serverName = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            val toolName = input.jsonObject["tool_name"]?.jsonPrimitive?.contentOrNull
                ?: error("tool_name is required")
            mcpManager.callConfiguredTool(
                serverId = serverId,
                serverName = serverName,
                toolName = toolName,
                args = input.mcpArgumentsObject(),
            )
        }
    ),
    Tool(
        name = "mcp_test",
        description = "Test one configured MCP server by id or name and refresh its tool list.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("server_id", buildJsonObject {
                        put("type", "string")
                        put("description", "MCP server id.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "MCP server name.")
                    })
                }
            )
        },
        needsApproval = true,
        execute = { input ->
            val server = findMcpServer(settingsStore, input)
            mcpManager.addClient(server)
            val status = mcpManager.syncingStatus.value[server.id] ?: McpStatus.Idle
            val payload = buildJsonObject {
                put("server", server.toJson(status))
                put("status", status.toStatusString())
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "mcp_import_from_skill",
        description = "Import standard mcp.json from an installed Skill into the global AmberAgent MCP settings.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("skill_name", buildJsonObject {
                        put("type", "string")
                        put("description", "Installed skill name.")
                    })
                },
                required = listOf("skill_name")
            )
        },
        needsApproval = true,
        execute = { input ->
            val skillName = input.jsonObject["skill_name"]?.jsonPrimitive?.contentOrNull ?: error("skill_name is required")
            val mcpFile = skillManager.getSkillDir(skillName)?.resolve("mcp.json") ?: error("Skill not found: $skillName")
            require(mcpFile.exists()) { "Skill '$skillName' does not contain mcp.json" }
            val configs = parseMcpServersFromJson(mcpFile.readText())
            require(configs.isNotEmpty()) { "mcp.json does not contain valid MCP servers" }
            var importedCount = 0
            settingsStore.update { settings ->
                val existingKeys = settings.mcpServers.map { it.importKey() }.toSet()
                val newConfigs = configs.filter { it.importKey() !in existingKeys }
                importedCount = newConfigs.size
                settings.copy(mcpServers = settings.mcpServers + newConfigs)
            }
            val payload = buildJsonObject {
                put("success", true)
                put("skill_name", skillName)
                put("imported_count", importedCount)
                put("already_exists_count", configs.size - importedCount)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
)

internal fun kotlinx.serialization.json.JsonElement.mcpArgumentsObject(): JsonObject {
    val raw = jsonObject["arguments"] ?: jsonObject["args"] ?: return buildJsonObject { }
    return when (raw) {
        is JsonObject -> raw
        is JsonPrimitive -> {
            val text = raw.contentOrNull.orEmpty().trim()
            if (text.isBlank()) {
                buildJsonObject { }
            } else {
                JsonInstant.parseToJsonElement(text).jsonObject
            }
        }

        else -> error("arguments must be a JSON object")
    }
}

private fun findMcpServer(settingsStore: SettingsAggregator, input: kotlinx.serialization.json.JsonElement): McpServerConfig {
    val serverId = input.jsonObject["server_id"]?.jsonPrimitive?.contentOrNull
    val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
    return settingsStore.settingsFlow.value.mcpServers.firstOrNull { server ->
        server.id.toString() == serverId || (!name.isNullOrBlank() && server.commonOptions.name == name)
    } ?: error("MCP server not found")
}

private fun McpServerConfig.toJson(
    status: McpStatus?,
    includeTools: Boolean = false,
    includeSchema: Boolean = false,
) = buildJsonObject {
    put("id", id.toString())
    put("name", commonOptions.name)
    put("enabled", commonOptions.enable)
    put("status", (status ?: McpStatus.Idle).toStatusString())
    put("tool_count", commonOptions.tools.size)
    put("enabled_tool_count", commonOptions.tools.count { it.enable })
    put("type", when (this@toJson) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    })
    put("url", when (this@toJson) {
        is McpServerConfig.SseTransportServer -> url
        is McpServerConfig.StreamableHTTPServer -> url
    })
    if (includeTools) {
        put("tools", buildJsonArray {
            commonOptions.tools.forEach { tool ->
                add(
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description.orEmpty().take(240))
                        put("enabled", tool.enable)
                        put("needs_approval", tool.needsApproval)
                        if (includeSchema) {
                            put("schema", tool.inputSchema?.toString().orEmpty())
                        }
                    }
                )
            }
        })
    }
}

private fun McpStatus.toStatusString(): String = when (this) {
    McpStatus.Idle -> "idle"
    McpStatus.Connecting -> "connecting"
    McpStatus.Connected -> "connected"
    is McpStatus.Reconnecting -> "reconnecting:$attempt/$maxAttempts"
    is McpStatus.Error -> "error:$message"
}

private fun McpServerConfig.importKey(): String = when (this) {
    is McpServerConfig.SseTransportServer -> "sse|${commonOptions.name}|$url"
    is McpServerConfig.StreamableHTTPServer -> "streamable_http|${commonOptions.name}|$url"
}
