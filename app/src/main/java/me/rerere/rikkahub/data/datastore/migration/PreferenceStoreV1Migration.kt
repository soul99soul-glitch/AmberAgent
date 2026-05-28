package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.core.settings.PreferencesKeys
import me.rerere.rikkahub.utils.JsonInstant

class PreferenceStoreV1Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[PreferencesKeys.VERSION]
        return version == null || version < 1
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        // 清理老的没有设置@SerialName的字段
        prefs[PreferencesKeys.MCP_SERVERS] = migrateMcpServersJson(prefs[PreferencesKeys.MCP_SERVERS] ?: "[]")

        // 更新版本
        prefs[PreferencesKeys.VERSION] = 1

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

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
