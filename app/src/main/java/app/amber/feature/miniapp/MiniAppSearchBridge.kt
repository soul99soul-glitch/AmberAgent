package app.amber.feature.miniapp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.SearchAggregator
import app.amber.core.settings.prefs.SettingsAggregator

class MiniAppSearchBridge(
    private val settingsStore: SettingsAggregator,
) {
    suspend fun search(params: JsonObject): JsonObject {
        val query = params["query"]?.jsonPrimitive?.contentOrNull?.take(160)
            ?: throw MiniAppValidationException("Missing query")
        val limit = params["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 8) ?: 6
        val result = SearchAggregator.search(
            settings = settingsStore.settingsFlow.value,
            params = buildJsonObject {
                put("query", query)
                put("topic", params["topic"]?.jsonPrimitive?.contentOrNull ?: "news")
                put("max_results", limit)
            },
        )
        val items = result["items"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject {
            put("items", buildJsonArray {
                items.take(limit).forEach { element ->
                    val item = element.jsonObject
                    add(
                        buildJsonObject {
                            put("title", item.string("title").take(120))
                            put("url", item.string("url").take(500))
                            put("snippet", item.string("text").take(300))
                            put("source", item.string("source_service").take(60))
                            item["published_at"]?.let { put("publishedAt", it) }
                        }
                    )
                }
            })
        }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
