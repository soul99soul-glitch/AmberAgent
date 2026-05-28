package me.rerere.search
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
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

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
            val url = "https://serpapi.com/search.json".toHttpUrl().newBuilder()
                .addQueryParameter("engine", "google")
                .addQueryParameter("q", query)
                .addQueryParameter("api_key", serviceOptions.apiKey)
                .addQueryParameter("num", commonOptions.resultSize.coerceIn(1, 20).toString())
                .apply {
                    if (topic == "news") {
                        addQueryParameter("tbm", "nws")
                    }
                }
                .build()
            val response = httpClient.newCall(Request.Builder().url(url).build()).await()
            if (!response.isSuccessful) {
                error("SerpAPI request failed #${response.code}")
            }
            val payload = response.body.string().let { json.decodeFromString<SerpApiResponse>(it) }
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
