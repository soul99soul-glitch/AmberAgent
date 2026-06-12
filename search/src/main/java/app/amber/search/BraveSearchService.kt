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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

private const val TAG = "BraveSearchService"

object BraveSearchService : SearchService<SearchServiceOptions.BraveOptions> {
    override val name: String = "Brave"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://api.search.brave.com/")
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
        serviceOptions: SearchServiceOptions.BraveOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val requestUrl = "https://api.search.brave.com/res/v1/web/search" +
                    "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                    "&count=${commonOptions.resultSize}"

            val response = httpClient.get(requestUrl) {
                header("Accept", "application/json")
                header("X-Subscription-Token", serviceOptions.apiKey)
            }
            if (response.status.isSuccess()) {
                val responseBody = response.bodyAsText()
                val searchResponse = json.decodeFromString<BraveSearchResponse>(responseBody)

                val items = searchResponse.web?.results?.map { result ->
                    // Prefer `original` (true source URL like n.sinaimg.cn /
                    // reuters.com). `src` is the Brave-proxied thumbnail
                    // (imgs.search.brave.com/<sig>/...) which is hot-link
                    // protected — Coil's GET without a brave.com Referer header
                    // gets a 403 and the image renders as blank space inline.
                    // Source-resized thumbnails are still fine on mobile because
                    // most news CDNs ship sub-1MB JPEGs.
                    val imgUrl = (result.thumbnail?.original ?: result.thumbnail?.src)
                        ?.takeIf { it.startsWith("http") }
                    android.util.Log.i("BraveSearchService", "result: ${result.title}, thumbnail: ${result.thumbnail?.src}, original: ${result.thumbnail?.original}, imgUrl: $imgUrl")
                    SearchResultItem(
                        title = result.title,
                        url = result.url,
                        text = result.description ?: "",
                        images = listOfNotNull(imgUrl),
                    )
                } ?: emptyList()
                android.util.Log.i("BraveSearchService", "total items: ${items.size}, with images: ${items.count { it.images.isNotEmpty() }}")

                return@withContext Result.success(
                    SearchResult(
                        answer = null,
                        items = items
                    )
                )
            } else {
                error("Brave search failed with code ${response.status.value}: ${response.status.description}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BraveOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Brave"))
    }

    @Serializable
    data class BraveSearchResponse(
        val type: String? = null,
        val web: WebResults? = null,
    )

    @Serializable
    data class WebResults(
        val type: String? = null,
        val results: List<WebResult>? = null,
    )

    @Serializable
    data class WebResult(
        val type: String,
        val title: String,
        val url: String,
        val description: String? = null,
        val thumbnail: Thumbnail? = null,
    )

    @Serializable
    data class Thumbnail(
        val src: String? = null,
        val height: Int? = null,
        val width: Int? = null,
        val original: String? = null,
    )
}
