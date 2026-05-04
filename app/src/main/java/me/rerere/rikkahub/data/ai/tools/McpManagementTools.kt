package me.rerere.rikkahub.data.ai.tools

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
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.parseMcpServersFromJson
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager

fun createMcpManagementTools(
    settingsStore: SettingsStore,
    mcpManager: McpManager,
    skillManager: SkillManager,
): List<Tool> = listOf(
    Tool(
        name = "mcp_list",
        description = "List configured MCP servers, enabled state, connection status, and known tool counts.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val settings = settingsStore.settingsFlow.value
            val statusMap = mcpManager.syncingStatus.value
            val payload = buildJsonObject {
                put("servers", buildJsonArray {
                    settings.mcpServers.forEach { server ->
                        add(server.toJson(statusMap[server.id]))
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
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

private fun findMcpServer(settingsStore: SettingsStore, input: kotlinx.serialization.json.JsonElement): McpServerConfig {
    val serverId = input.jsonObject["server_id"]?.jsonPrimitive?.contentOrNull
    val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
    return settingsStore.settingsFlow.value.mcpServers.firstOrNull { server ->
        server.id.toString() == serverId || (!name.isNullOrBlank() && server.commonOptions.name == name)
    } ?: error("MCP server not found")
}

private fun McpServerConfig.toJson(status: McpStatus?) = buildJsonObject {
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
