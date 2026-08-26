package app.amber.search
import app.amber.search.R

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.search.SearchResult.SearchResultItem
import app.amber.search.SearchService.Companion.httpClient
import app.amber.search.SearchService.Companion.json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

object JinaSearchService : SearchService<SearchServiceOptions.JinaOptions> {
    private const val DEFAULT_SEARCH_URL = "https://s.jina.ai/"
    private const val DEFAULT_SCRAPE_URL = "https://r.jina.ai/"

    override val name: String = "Jina"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://jina.ai/")
            }
        ) {
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
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema?
        get() = InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.JinaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val searchUrl = serviceOptions.searchUrl.ifBlank { DEFAULT_SEARCH_URL }
            val url = searchUrl.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .apply {
                    if (serviceOptions.apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                    }
                }
                .build()

            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    error("response failed #${response.code}: ${response.body?.string().orEmpty()}")
                }

                return@withContext Result.success(
                    mapJinaSearchResponse(response.body?.string().orEmpty(), commonOptions.resultSize)
                )
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.JinaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val targetUrl = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val scrapeUrl = serviceOptions.scrapeUrl.ifBlank { DEFAULT_SCRAPE_URL }
            val requestUrl = if (scrapeUrl.endsWith('/')) {
                "$scrapeUrl$targetUrl"
            } else {
                "$scrapeUrl/$targetUrl"
            }

            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("Accept", "application/json")
                .apply {
                    if (serviceOptions.apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                    }
                }
                .build()

            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    error("response failed for url $targetUrl #${response.code}: ${response.body?.string().orEmpty()}")
                }
                val responseData = json.decodeFromString<JinaScrapeResponse>(response.body?.string().orEmpty())

                return@withContext Result.success(
                    ScrapedResult(
                        urls = listOf(
                            ScrapedResultUrl(
                                url = responseData.data.url,
                                content = responseData.data.content,
                                metadata = ScrapedResultMetadata(
                                    title = responseData.data.title,
                                    description = responseData.data.description
                                )
                            )
                        )
                    )
                )
            }
        }
    }

    internal fun mapJinaSearchResponse(body: String, resultSize: Int): SearchResult {
        val response = json.decodeFromString<JinaSearchResponse>(body)
        return SearchResult(
            items = response.data.take(resultSize).map {
                SearchResultItem(
                    title = it.title,
                    url = it.url,
                    text = it.description.ifBlank { it.content },
                )
            }
        )
    }

    @Serializable
    data class JinaSearchResponse(
        val code: Int = 200,
        val status: Int = 20000,
        val data: List<JinaSearchResultItem> = emptyList()
    )

    @Serializable
    data class JinaSearchResultItem(
        val title: String,
        val url: String,
        val description: String = "",
        val content: String = "",
        val usage: JinaUsage? = null
    )

    @Serializable
    data class JinaUsage(
        val tokens: Int
    )

    @Serializable
    data class JinaScrapeResponse(
        val code: Int = 200,
        val status: Int = 20000,
        val data: JinaScrapeData
    )

    @Serializable
    data class JinaScrapeData(
        val title: String = "",
        val description: String = "",
        val url: String,
        val content: String = "",
        val publishedTime: String? = null,
        val usage: JinaUsage? = null
    )
}
