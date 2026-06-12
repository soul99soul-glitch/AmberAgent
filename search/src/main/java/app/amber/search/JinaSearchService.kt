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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

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

            val body = buildJsonObject {
                put("q", query)
            }

            val searchUrl = serviceOptions.searchUrl.ifBlank { DEFAULT_SEARCH_URL }

            val response = httpClient.post(searchUrl) {
                setBody(body.toString())
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                if (serviceOptions.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${serviceOptions.apiKey}")
                }
            }
            if (response.status.isSuccess()) {
                val responseData = response.bodyAsText().let {
                    json.decodeFromString<JinaSearchResponse>(it)
                }

                return@withContext Result.success(
                    SearchResult(
                        items = responseData.data.take(commonOptions.resultSize).map {
                            SearchResultItem(
                                title = it.title,
                                url = it.url,
                                text = it.description
                            )
                        }
                    )
                )
            } else {
                error("response failed #${response.status.value}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.JinaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("urls is required")

            val body = buildJsonObject {
                put("url", url)
            }

            val scrapeUrl = serviceOptions.scrapeUrl.ifBlank { DEFAULT_SCRAPE_URL }

            val response = httpClient.post(scrapeUrl) {
                setBody(body.toString())
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                header("X-Return-Format", "markdown")
                if (serviceOptions.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${serviceOptions.apiKey}")
                }
            }
            if (!response.status.isSuccess()) {
                error("response failed for url $url #${response.status.value}")
            }
            val responseData = response.bodyAsText().let {
                json.decodeFromString<JinaScrapeResponse>(it)
            }

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
        }
    }

    @Serializable
    data class JinaSearchResponse(
        val code: Int,
        val status: Int,
        val data: List<JinaSearchResultItem>
    )

    @Serializable
    data class JinaSearchResultItem(
        val title: String,
        val url: String,
        val description: String,
        val content: String = "",
        val usage: JinaUsage? = null
    )

    @Serializable
    data class JinaUsage(
        val tokens: Int
    )

    @Serializable
    data class JinaScrapeResponse(
        val code: Int,
        val status: Int,
        val data: JinaScrapeData
    )

    @Serializable
    data class JinaScrapeData(
        val title: String,
        val description: String = "",
        val url: String,
        val content: String,
        val publishedTime: String? = null,
        val usage: JinaUsage? = null
    )
}
