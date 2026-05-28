package app.amber.search
import app.amber.search.R

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.search.SearchResult.SearchResultItem
import app.amber.search.SearchService.Companion.httpClient
import app.amber.search.SearchService.Companion.json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object SerperSearchService : SearchService<SearchServiceOptions.SerperOptions> {
    override val name: String = "Serper"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(onClick = { urlHandler.openUri("https://serper.dev/api-key") }) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override val parameters: InputSchema?
        get() = InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("topic", buildJsonObject {
                    put("type", "string")
                    put("description", "search topic, use news for recent events")
                })
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SerperOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(serviceOptions.apiKey.isNotBlank()) { "Serper API key is required" }
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val topic = params["topic"]?.jsonPrimitive?.contentOrNull
            val endpoint = if (topic == "news") "news" else "search"
            val body = buildJsonObject {
                put("q", query)
                put("num", commonOptions.resultSize.coerceIn(1, 20))
            }
            val request = Request.Builder()
                .url("https://google.serper.dev/$endpoint")
                .post(body.toString().toRequestBody())
                .addHeader("X-API-KEY", serviceOptions.apiKey)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) {
                error("Serper request failed #${response.code}")
            }
            val payload = response.body.string().let { json.decodeFromString<SerperResponse>(it) }
            val items = (payload.news ?: payload.organic ?: emptyList()).map {
                SearchResultItem(
                    title = it.title,
                    url = it.link,
                    text = it.snippet.orEmpty(),
                    publishedAt = it.date,
                )
            }
            SearchResult(items = items.take(commonOptions.resultSize))
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SerperOptions
    ): Result<ScrapedResult> = Result.failure(Exception("Scraping is not supported for Serper"))

    @Serializable
    private data class SerperResponse(
        val organic: List<SerperItem>? = null,
        val news: List<SerperItem>? = null,
    )

    @Serializable
    private data class SerperItem(
        val title: String,
        val link: String,
        val snippet: String? = null,
        val date: String? = null,
    )
}
