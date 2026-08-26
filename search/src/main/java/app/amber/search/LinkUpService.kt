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
import app.amber.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "LinkUpService"

object LinkUpService : SearchService<SearchServiceOptions.LinkUpOptions> {
    override val name: String = "LinkUp"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://www.linkup.so/")
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
        serviceOptions: SearchServiceOptions.LinkUpOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            require(serviceOptions.apiKey.isNotBlank()) { "LinkUp API key is required" }
            val body = buildJsonObject {
                put("q", JsonPrimitive(query))
                put("depth", JsonPrimitive(serviceOptions.depth))
                put("outputType", JsonPrimitive("sourcedAnswer"))
                put("includeImages", false)
                put("maxResults", commonOptions.resultSize)
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.linkup.so/v1/search")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            Log.i(TAG, "search: $query")

            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    error("response failed #${response.code}: ${response.body?.string().orEmpty()}")
                }

                return@withContext Result.success(
                    mapLinkUpSearchResponse(response.body?.string().orEmpty(), commonOptions.resultSize)
                )
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.LinkUpOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            require(serviceOptions.apiKey.isNotBlank()) { "LinkUp API key is required" }
            val body = buildJsonObject {
                put("url", JsonPrimitive(url))
                put("includeRawHtml", JsonPrimitive(false))
                put("renderJs", JsonPrimitive(false))
                put("extractImages", JsonPrimitive(false))
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.linkup.so/v1/fetch")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    error("response failed #${response.code}: ${response.body?.string().orEmpty()}")
                }

                val responseBody = json.decodeFromString<LinkUpFetchResponse>(response.body?.string().orEmpty())
                return@withContext Result.success(
                    ScrapedResult(
                        urls = listOf(
                            ScrapedResultUrl(
                                url = url,
                                content = responseBody.markdown
                            )
                        )
                    )
                )
            }
        }
    }

    internal fun mapLinkUpSearchResponse(body: String, resultSize: Int): SearchResult {
        val response = json.decodeFromString<LinkUpSearchResponse>(body)
        val sources = (response.sources + response.results).distinctBy { it.url }
        return SearchResult(
            answer = response.answer,
            items = sources.take(resultSize).map {
                SearchResultItem(
                    title = it.name?.takeIf(String::isNotBlank) ?: it.url,
                    url = it.url,
                    text = it.snippet ?: it.content.orEmpty()
                )
            }
        )
    }

    @Serializable
    data class LinkUpSearchResponse(
        val answer: String? = null,
        val sources: List<Source> = emptyList(),
        val results: List<Source> = emptyList(),
    )

    @Serializable
    data class Source(
        val name: String? = null,
        val url: String,
        val snippet: String? = null,
        val content: String? = null,
    )

    @Serializable
    data class LinkUpFetchResponse(
        val markdown: String
    )
}
