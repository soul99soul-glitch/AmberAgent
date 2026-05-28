package app.amber.core.settings.migration

import app.amber.core.agent.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun migrateMcpServersJson(json: String): String {
    val element = JsonInstant.parseToJsonElement(json).jsonArray.map { element ->
        val jsonObj = element.jsonObject.toMutableMap()
        val type = jsonObj["type"]?.jsonPrimitive?.content ?: ""
        when (type) {
            "me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer" -> {
                jsonObj["type"] = JsonPrimitive("sse")
            }

            "me.rerere.rikkahub.data.mcp.McpServerConfig.StreamableHTTPServer" -> {
                jsonObj["type"] = JsonPrimitive("streamable_http")
            }
        }
        JsonObject(jsonObj)
    }
    return JsonInstant.encodeToString(element)
}
