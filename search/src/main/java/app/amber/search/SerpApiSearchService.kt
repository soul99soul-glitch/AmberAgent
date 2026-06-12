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
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

object SerpApiSearchService : SearchService<SearchServiceOptions.SerpApiOptions> {
    override val name: String = "SerpAPI"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(onClick = { urlHandler.openUri("https://serpapi.com/manage-api-key") }) {
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
        serviceOptions: SearchServiceOptions.SerpApiOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(serviceOptions.apiKey.isNotBlank()) { "SerpAPI key is required" }
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val topic = params["topic"]?.jsonPrimitive?.contentOrNull
            val response = httpClient.get("https://serpapi.com/search.json") {
                parameter("engine", "google")
                parameter("q", query)
                parameter("api_key", serviceOptions.apiKey)
                parameter("num", commonOptions.resultSize.coerceIn(1, 20).toString())
                if (topic == "news") {
                    parameter("tbm", "nws")
                }
            }
            if (!response.status.isSuccess()) {
                error("SerpAPI request failed #${response.status.value}")
            }
            val payload = response.bodyAsText().let { json.decodeFromString<SerpApiResponse>(it) }
            val items = (payload.newsResults ?: payload.organicResults ?: emptyList()).map {
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
        serviceOptions: SearchServiceOptions.SerpApiOptions
    ): Result<ScrapedResult> = Result.failure(Exception("Scraping is not supported for SerpAPI"))

    @Serializable
    private data class SerpApiResponse(
        @SerialName("organic_results")
        val organicResults: List<SerpApiItem>? = null,
        @SerialName("news_results")
        val newsResults: List<SerpApiItem>? = null,
    )

    @Serializable
    private data class SerpApiItem(
        val title: String,
        val link: String,
        val snippet: String? = null,
        val date: String? = null,
    )
}
