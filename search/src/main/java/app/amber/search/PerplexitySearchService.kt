package app.amber.search
import app.amber.search.R

import android.util.Log
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
import kotlinx.serialization.json.JsonPrimitive
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

private const val PERPLEXITY_ENDPOINT = "https://api.perplexity.ai/search"
private const val TAG = "PerplexitySearchService"

object PerplexitySearchService : SearchService<SearchServiceOptions.PerplexityOptions> {
    override val name: String = "Perplexity"

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                uriHandler.openUri("https://www.perplexity.ai/settings/api")
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

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.PerplexityOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (serviceOptions.apiKey.isBlank()) {
                error("Perplexity API key is required")
            }

            val query = params["query"]?.jsonPrimitive?.content
                ?: error("query is required")

            val body = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("max_results", JsonPrimitive(commonOptions.resultSize))
                // NOTE: return_images is a Sonar /chat/completions feature, not
                // supported by the /search endpoint. Kept commented for reference.
                // put("return_images", JsonPrimitive(true))
                serviceOptions.maxTokens?.let {
                    if (it > 0) {
                        put("max_tokens", JsonPrimitive(it))
                    }
                }
                serviceOptions.maxTokensPerPage?.let {
                    if (it > 0) {
                        put("max_tokens_per_page", JsonPrimitive(it))
                    }
                }
            }

            Log.i(TAG, "search request started")

            val response = httpClient.post(PERPLEXITY_ENDPOINT) {
                setBody(body.toString())
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${serviceOptions.apiKey}")
            }
            if (response.status.isSuccess()) {
                val responseBodyText = response.bodyAsText()
                val perplexityResponse = json.decodeFromString<PerplexityResponse>(responseBodyText)

                // Collect all image URLs from the response-level images array.
                // Perplexity returns images at the response level, not per-result,
                // so we distribute them across the top results (round-robin, max 5 total).
                Log.i(TAG, "response images count: ${perplexityResponse.images.size}, results: ${perplexityResponse.results.size}")
                val allImages = perplexityResponse.images
                    .mapNotNull { it.imageUrl }
                    .distinct()
                    .take(5)
                Log.i(TAG, "usable response images: ${allImages.size}")

                val rawItems = perplexityResponse.results
                    .filter { !it.title.isNullOrBlank() && !it.url.isNullOrBlank() }
                    .take(commonOptions.resultSize)

                // Attach all response-level images to the first valid result.
                // The aggregator handles cross-result distribution and dedup.
                var imagesAttached = false
                val items = rawItems.map {
                    val imgs = if (!imagesAttached && allImages.isNotEmpty()) {
                        imagesAttached = true
                        allImages
                    } else emptyList()
                    SearchResultItem(
                        title = it.title!!,
                        url = it.url!!,
                        text = it.snippet ?: it.text ?: "",
                        images = imgs,
                    )
                }

                return@withContext Result.success(
                    SearchResult(
                        answer = perplexityResponse.answer,
                        items = items
                    )
                )
            } else {
                response.bodyAsText()
                error("response failed #${response.status.value}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.PerplexityOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Perplexity"))
    }

    @Serializable
    private data class PerplexityResponse(
        val answer: String? = null,
        val results: List<ResultItem> = emptyList(),
        val images: List<ImageItem> = emptyList(),
    ) {
        @Serializable
        data class ResultItem(
            val title: String? = null,
            val url: String? = null,
            val snippet: String? = null,
            @SerialName("text") val text: String? = null,
        )

        @Serializable
        data class ImageItem(
            @SerialName("image_url") val imageUrl: String? = null,
            @SerialName("origin_url") val originUrl: String? = null,
            val title: String? = null,
            val width: Int? = null,
            val height: Int? = null,
        )
    }
}
