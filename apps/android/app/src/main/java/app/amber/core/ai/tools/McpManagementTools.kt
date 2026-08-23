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
import app.amber.core.ai.mcp.McpImportApplyResult
import app.amber.core.ai.mcp.McpImportPreparation
import app.amber.core.ai.mcp.McpImportPreview
import app.amber.core.ai.mcp.McpImportTransaction
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.ai.mcp.McpStatus
import app.amber.core.ai.mcp.RealMcpConnectPreflight
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.files.SkillManager
import app.amber.feature.runtime.CasLedger
import java.net.URI
import java.util.UUID

fun createMcpManagementTools(
    settingsStore: SettingsAggregator,
    mcpManager: McpManager,
    skillManager: SkillManager,
    approvalLedger: CasLedger? = null,
): List<Tool> {
    val importTransaction = approvalLedger?.let { ledger ->
        McpImportTransaction(
            preflight = RealMcpConnectPreflight(),
            approvalLedger = ledger,
            existingServerNames = {
                settingsStore.settingsFlow.value.mcpServers
                    .map { it.commonOptions.name }
                    .toSet()
            },
            publish = { configs ->
                settingsStore.update { settings ->
                    settings.copy(mcpServers = settings.mcpServers + configs)
                }
            },
        )
    }

    val mcpPreviewImportTool = Tool(
        name = "mcp_preview_import_from_skill",
        description = "Preview an installed Skill mcp.json with redacted server origins, header names, risk, and a candidate digest. This is read-only; pass the digest to mcp_import_from_skill after approval.",
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
        needsApproval = false,
        allowsAutoApproval = true,
        execute = { input ->
            val transaction = importTransaction ?: error("MCP import approval ledger is unavailable")
            val skillName = input.jsonObject["skill_name"]?.jsonPrimitive?.contentOrNull
                ?: error("skill_name is required")
            val mcpFile = skillManager.getSkillDir(skillName)?.resolve("mcp.json")
                ?: error("Skill not found: $skillName")
            require(mcpFile.exists()) { "Skill '$skillName' does not contain mcp.json" }
            val preparation = transaction.prepare(mcpFile.readText())
            val ready = when (preparation) {
                is McpImportPreparation.Ready -> preparation
                is McpImportPreparation.Rejected -> error(preparation.errors.joinToString("; "))
            }
            listOf(UIMessagePart.Text(ready.preview.toRedactedJson(skillName).toString()))
        }
    )

    val mcpImportTool = Tool(
        name = "mcp_import_from_skill",
        description = "Import standard mcp.json from an installed Skill after the user approves the redacted preview and candidate_digest.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("skill_name", buildJsonObject {
                        put("type", "string")
                        put("description", "Installed skill name.")
                    })
                    put("candidate_digest", buildJsonObject {
                        put("type", "string")
                        put("description", "The digest returned by mcp_preview_import_from_skill and approved for this exact import candidate.")
                    })
                },
                required = listOf("skill_name", "candidate_digest")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { input ->
            val transaction = importTransaction ?: error("MCP import approval ledger is unavailable")
            val skillName = input.jsonObject["skill_name"]?.jsonPrimitive?.contentOrNull
                ?: error("skill_name is required")
            val candidateDigest = input.mcpCandidateDigest()
            val mcpFile = skillManager.getSkillDir(skillName)?.resolve("mcp.json")
                ?: error("Skill not found: $skillName")
            require(mcpFile.exists()) { "Skill '$skillName' does not contain mcp.json" }
            val rawJson = mcpFile.readText()
            val preparation = transaction.prepare(rawJson)
            val ready = when (preparation) {
                is McpImportPreparation.Ready -> preparation
                is McpImportPreparation.Rejected -> error(preparation.errors.joinToString("; "))
            }
            require(ready.preview.digest == candidateDigest) {
                "candidate_digest is stale; call mcp_preview_import_from_skill again"
            }
            // The dispatcher has approved the outer call carrying this exact
            // candidate_digest; only now bind it to the transaction ledger.
            val sessionId = UUID.randomUUID().toString()
            transaction.approve(ready.preview, sessionId, source = "tool_approval")
            when (val result = transaction.apply(rawJson, sessionId)) {
                is McpImportApplyResult.Applied -> listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("skill_name", skillName)
                            put("imported_count", result.serverCount)
                            put("tool_count", result.toolCount)
                        }.toString()
                    )
                )
                is McpImportApplyResult.Stale -> error(result.reason)
                is McpImportApplyResult.Rejected -> error(result.errors.joinToString("; "))
            }
        }
    )

    return listOf(
        mcpPreviewImportTool,
        mcpImportTool,
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
        allowsAutoApproval = false,
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
        allowsAutoApproval = false,
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
    )
}

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

internal fun kotlinx.serialization.json.JsonElement.mcpCandidateDigest(): String =
    jsonObject["candidate_digest"]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException(
            "candidate_digest is required; call mcp_preview_import_from_skill first"
        )

private fun findMcpServer(settingsStore: SettingsAggregator, input: kotlinx.serialization.json.JsonElement): McpServerConfig {
    val serverId = input.jsonObject["server_id"]?.jsonPrimitive?.contentOrNull
    val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
    return settingsStore.settingsFlow.value.mcpServers.firstOrNull { server ->
        server.id.toString() == serverId || (!name.isNullOrBlank() && server.commonOptions.name == name)
    } ?: error("MCP server not found")
}

private fun McpImportPreview.toRedactedJson(skillName: String): JsonObject = buildJsonObject {
    put("skill_name", skillName)
    put("digest", digest)
    put("candidate_digest", digest)
    put("risk", risk)
    put("server_count", serverCount)
    put("header_name_count", headerNameCount)
    put("servers", buildJsonArray {
        servers.forEach { server ->
            add(buildJsonObject {
                put("server_name", server.serverName)
                put("transport", server.transport.name.lowercase())
                put("origin", server.origin)
                put("risk", server.risk)
                put("header_names", buildJsonArray {
                    server.headerNames.forEach { add(it) }
                })
                server.note?.let { put("note", it) }
            })
        }
    })
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
    put("url", safeMcpOrigin(when (this@toJson) {
        is McpServerConfig.SseTransportServer -> url
        is McpServerConfig.StreamableHTTPServer -> url
    }))
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

private fun safeMcpOrigin(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return ""
    val scheme = uri.scheme ?: return ""
    val host = uri.host ?: return ""
    val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
    return "$scheme://$host$port"
}

private fun McpStatus.toStatusString(): String = when (this) {
    McpStatus.Idle -> "idle"
    McpStatus.Connecting -> "connecting"
    McpStatus.Connected -> "connected"
    is McpStatus.Reconnecting -> "reconnecting:$attempt/$maxAttempts"
    is McpStatus.Error -> "error:$message"
    McpStatus.Authorizing -> "authorizing"
    McpStatus.NeedsAuthorization -> "needs_authorization"
}
