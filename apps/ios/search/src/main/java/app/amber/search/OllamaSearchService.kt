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

private const val TAG = "OllamaSearchService"

object OllamaSearchService : SearchService<SearchServiceOptions.OllamaOptions> {
    override val name: String = "Ollama"

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri("https://ollama.com/settings/keys") }) {
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
        serviceOptions: SearchServiceOptions.OllamaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize.coerceIn(5..10))
            }

            val response = httpClient.post("https://ollama.com/api/web_search") {
                setBody(body.toString())
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${serviceOptions.apiKey}")
            }
            if (response.status.isSuccess()) {
                val responseBody = response.bodyAsText()
                val searchResponse = json.decodeFromString<OllamaSearchResponse>(responseBody)

                return@withContext Result.success(
                    SearchResult(
                        items = searchResponse.results.map {
                            SearchResultItem(
                                title = it.title,
                                url = it.url,
                                text = it.content
                            )
                        }
                    )
                )
            } else {
                error("Ollama search failed with code ${response.status.value}: ${response.status.description}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.OllamaOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Ollama"))
    }

    @Serializable
    private data class OllamaSearchResponse(
        val results: List<OllamaSearchResult>
    )

    @Serializable
    private data class OllamaSearchResult(
        val title: String,
        val url: String,
        val content: String
    )
}
