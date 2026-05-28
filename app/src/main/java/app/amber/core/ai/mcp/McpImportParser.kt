package app.amber.core.ai.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun parseMcpServersFromJson(json: String): List<McpServerConfig> {
    val root = Json.parseToJsonElement(json).jsonObject
    val mcpServers = root["mcpServers"]?.jsonObject ?: return emptyList()
    return mcpServers.entries.mapNotNull { (name, element) ->
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "streamable_http"
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val headers = obj["headers"]?.jsonObject?.entries?.map { (k, v) ->
            k to (v.jsonPrimitive.contentOrNull ?: "")
        } ?: emptyList()
        val commonOptions = McpCommonOptions(name = name, headers = headers)
        when (type) {
            "sse" -> McpServerConfig.SseTransportServer(commonOptions = commonOptions, url = url)
            else -> McpServerConfig.StreamableHTTPServer(commonOptions = commonOptions, url = url)
        }
    }
}
