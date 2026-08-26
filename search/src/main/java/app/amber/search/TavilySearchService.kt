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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.search.SearchResult.SearchResultItem
import app.amber.search.SearchService.Companion.httpClient
import app.amber.search.SearchService.Companion.json
import app.amber.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "TavilySearchService"

object TavilySearchService : SearchService<SearchServiceOptions.TavilyOptions> {
    override val name: String = "Tavily"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://app.tavily.com/home")
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
                put("topic", buildJsonObject {
                    put("type", "string")
                    put("description", "search topic (one of `general`, `news`, `finance`)")
                    put("enum", buildJsonArray {
                        add("general")
                        add("news")
                        add("finance")
                    })
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
        serviceOptions: SearchServiceOptions.TavilyOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            require(serviceOptions.apiKey.isNotBlank()) { "Tavily API key is required" }
            val topic = params["topic"]?.jsonPrimitive?.contentOrNull ?: "general"

            // Validate topic
            if (topic !in listOf("general", "news", "finance")) {
                error("topic must be one of `general`, `news`, `finance`")
            }

            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize)
                val searchDepth = serviceOptions.depth.ifEmpty { "advanced" }
                put("search_depth", searchDepth)
                put("topic", topic)
                put("include_answer", if (searchDepth == "advanced") "advanced" else "basic")
                put("include_images", true)
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    error("response failed #${response.code}: ${response.body?.string().orEmpty()}")
                }

                return@withContext Result.success(
                    mapTavilySearchResponse(response.body?.string().orEmpty(), commonOptions.resultSize)
                )
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            require(serviceOptions.apiKey.isNotBlank()) { "Tavily API key is required" }
            val body = buildJsonObject {
                put("urls", buildJsonArray {
                    add(url)
                })
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())
            val request = Request.Builder()
                .url("https://api.tavily.com/extract")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()
            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    error("response failed #${response.code}: ${response.body?.string().orEmpty()}")
                }
                val responseBody = json.decodeFromString<ScrapeResponse>(response.body?.string().orEmpty())
                if (responseBody.results.isEmpty() && responseBody.failedResults.isNotEmpty()) {
                    error("Tavily extract failed: ${responseBody.failedResults.joinToString { it.url }}")
                }
                return@withContext Result.success(
                    ScrapedResult(
                        urls = responseBody.results.map {
                            ScrapedResultUrl(
                                url = it.url,
                                content = it.rawContent,
                            )
                        }
                    )
                )
            }
        }
    }

    internal fun mapTavilySearchResponse(body: String, resultSize: Int): SearchResult {
        val response = json.decodeFromString<SearchResponse>(body)
        val tavilyImages = response.images.mapNotNull { it.imageUrl() }.distinct().take(5)
        return SearchResult(
            answer = response.answer,
            items = response.results.take(resultSize).mapIndexed { index, item ->
                SearchResultItem(
                    title = item.title,
                    url = item.url,
                    text = item.content,
                    images = if (index == 0) tavilyImages else emptyList(),
                )
            }
        )
    }

    private fun JsonElement.imageUrl(): String? {
        return when (this) {
            is kotlinx.serialization.json.JsonPrimitive -> contentOrNull
            is JsonObject -> this["url"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    @Serializable
    data class SearchResponse(
        val query: String,
        val followUpQuestions: String? = null,
        val answer: String? = null,
        val images: List<JsonElement> = emptyList(),
        val results: List<TavilySearchService.SearchResultItem>,
    )

    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val content: String,
        val score: Double,
        val rawContent: String? = null
    )

    @Serializable
    data class ScrapeResponse(
        val results: List<ScrapedResultItem>,
        @SerialName("failed_results")
        val failedResults: List<FailedResultItem> = emptyList(),
    )

    @Serializable
    data class ScrapedResultItem(
        val url: String,
        @SerialName("raw_content")
        val rawContent: String,
    )

    @Serializable
    data class FailedResultItem(
        val url: String,
        val error: String? = null,
    )
}
