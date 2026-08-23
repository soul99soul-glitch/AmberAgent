package app.amber.search

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import app.amber.ai.core.InputSchema
import app.amber.ai.util.KeyRoulette
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

interface SearchService<T : SearchServiceOptions> {
    val name: String

    val parameters: InputSchema?

    val scrapingParameters: InputSchema?

    @Composable
    fun Description()

    suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<SearchResult>

    suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<ScrapedResult>

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : SearchServiceOptions> getService(options: T): SearchService<T> {
            return when (options) {
                is SearchServiceOptions.TavilyOptions -> TavilySearchService
                is SearchServiceOptions.ExaOptions -> ExaSearchService
                is SearchServiceOptions.ZhipuOptions -> ZhipuSearchService
                is SearchServiceOptions.BingLocalOptions -> BingSearchService
                is SearchServiceOptions.SearXNGOptions -> SearXNGService
                is SearchServiceOptions.LinkUpOptions -> LinkUpService
                is SearchServiceOptions.BraveOptions -> BraveSearchService
                is SearchServiceOptions.SerperOptions -> SerperSearchService
                is SearchServiceOptions.SerpApiOptions -> SerpApiSearchService
                is SearchServiceOptions.MetasoOptions -> MetasoSearchService
                is SearchServiceOptions.OllamaOptions -> OllamaSearchService
                is SearchServiceOptions.PerplexityOptions -> PerplexitySearchService
                is SearchServiceOptions.FirecrawlOptions -> FirecrawlSearchService
                is SearchServiceOptions.JinaOptions -> JinaSearchService
                is SearchServiceOptions.BochaOptions -> BochaSearchService
                is SearchServiceOptions.AmberAgentSearchOptions -> AmberAgentSearchService
                is SearchServiceOptions.GrokOptions -> GrokSearchService
            } as SearchService<T>
        }

        @Volatile
        internal var httpClient: HttpClient = HttpClient(OkHttp) {
            engine {
                config {
                    retryOnConnectionFailure(true)
                    followRedirects(true)
                    followSslRedirects(true)
                    readTimeout(30, TimeUnit.SECONDS)
                }
            }
        }

        @Volatile
        internal var keyRoulette: KeyRoulette = KeyRoulette.default()

        fun init(client: HttpClient, context: Context? = null) {
            httpClient = client
            keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
        }

        internal val json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
        val publishedAt: String? = null,
        /** Image URLs associated with this result. Max 5 per item. */
        val images: List<String> = emptyList(),
    )
}

@Serializable
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
)

@Serializable
data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

@Serializable
data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
)
