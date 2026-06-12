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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

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
            val body = buildJsonObject {
                put("q", JsonPrimitive(query))
                put("depth", JsonPrimitive(serviceOptions.depth))
                put("outputType", JsonPrimitive("sourcedAnswer"))
                put("includeImages", JsonPrimitive("false"))
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val response = httpClient.post("https://api.linkup.so/v1/search") {
                setBody(body.toString())
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
            }

            Log.i(TAG, "search: $query")

            if (response.status.isSuccess()) {
                val responseBody = response.bodyAsText().let {
                    json.decodeFromString<LinkUpSearchResponse>(it)
                }

                return@withContext Result.success(
                    SearchResult(
                        answer = responseBody.answer,
                        items = responseBody.sources.take(commonOptions.resultSize).map {
                            SearchResultItem(
                                title = it.name,
                                url = it.url,
                                text = it.snippet
                            )
                        }
                    )
                )
            } else {
                error("response failed #${response.status.value}: ${response.bodyAsText()}")
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
            val body = buildJsonObject {
                put("url", JsonPrimitive(url))
                put("includeRawHtml", JsonPrimitive(false))
                put("renderJs", JsonPrimitive(false))
                put("extractImages", JsonPrimitive(false))
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val response = httpClient.post("https://api.linkup.so/v1/fetch") {
                setBody(body.toString())
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
            }
            if (response.status.isSuccess()) {
                val responseBody = response.bodyAsText().let {
                    json.decodeFromString<LinkUpFetchResponse>(it)
                }

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
            } else {
                error("response failed #${response.status.value}: ${response.bodyAsText()}")
            }
        }
    }

    @Serializable
    data class LinkUpSearchResponse(
        val answer: String,
        val sources: List<Source>
    )

    @Serializable
    data class Source(
        val name: String,
        val url: String,
        val snippet: String
    )

    @Serializable
    data class LinkUpFetchResponse(
        val markdown: String
    )
}
