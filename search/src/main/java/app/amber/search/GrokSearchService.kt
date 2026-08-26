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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.search.SearchResult.SearchResultItem
import app.amber.search.SearchService.Companion.httpClient
import app.amber.search.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "GrokSearchService"

object GrokSearchService : SearchService<SearchServiceOptions.GrokOptions> {
    override val name: String = "Grok"

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                uriHandler.openUri("https://console.x.ai/")
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
                    put("description", "The question to ask, can be a natural language question")
                })
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (serviceOptions.apiKey.isBlank()) {
                error("Grok API key is required")
            }

            val query = params["query"]?.jsonPrimitive?.content
                ?: error("query is required")

            val body = buildJsonObject {
                put("model", JsonPrimitive(serviceOptions.model))
                put("input", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("system"))
                        put("content", JsonPrimitive(serviceOptions.systemPrompt))
                    })
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(query))
                    })
                })
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("web_search"))
                    })
                    add(buildJsonObject {
                        put("type", JsonPrimitive("x_search"))
                    })
                })
                put("store", JsonPrimitive(false))
            }

            Log.i(TAG, "search: $query")

            val request = Request.Builder()
                .url(serviceOptions.customUrl)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    error("response failed #${response.code}: ${response.body?.string().orEmpty()}")
                }

                return@withContext Result.success(
                    mapGrokSearchResponse(response.body?.string().orEmpty(), commonOptions.resultSize)
                )
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Grok"))
    }

    internal fun mapGrokSearchResponse(body: String, resultSize: Int): SearchResult {
        val response = json.decodeFromString<GrokResponse>(body)
        val textContent = response.output
            .firstOrNull { it.type == "message" && it.role == "assistant" }
            ?.content
            ?.firstOrNull { it.type == "output_text" }

        val titlesByUrl = textContent?.annotations.orEmpty()
            .asSequence()
            .filter { it.type == "url_citation" && !it.url.isNullOrBlank() }
            .associate { it.url!! to it.title?.takeIf(String::isNotBlank) }
        val citationUrls = (titlesByUrl.keys + response.citations)
            .distinct()
            .take(resultSize)
        val items = citationUrls.map { url ->
            SearchResultItem(
                title = titlesByUrl[url] ?: url,
                url = url,
                text = ""
            )
        }

        return SearchResult(
            answer = textContent?.text,
            items = items
        )
    }

    @Serializable
    private data class GrokResponse(
        val output: List<GrokOutputItem> = emptyList(),
        val citations: List<String> = emptyList(),
    )

    @Serializable
    private data class GrokOutputItem(
        val type: String,
        val role: String? = null,
        val status: String? = null,
        val content: List<GrokContent>? = null,
    )

    @Serializable
    private data class GrokContent(
        val type: String,
        val text: String? = null,
        val annotations: List<GrokAnnotation>? = null
    )

    @Serializable
    private data class GrokAnnotation(
        val type: String,
        val url: String? = null,
        val title: String? = null,
        @SerialName("start_index") val startIndex: Int? = null,
        @SerialName("end_index") val endIndex: Int? = null
    )
}
