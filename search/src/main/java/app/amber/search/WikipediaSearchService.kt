package app.amber.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.amber.search.SearchResult.SearchResultItem
import app.amber.search.SearchService.Companion.httpClient
import app.amber.search.SearchService.Companion.json
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

object WikipediaSearchService {
    const val name: String = "Wikipedia"

    suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val apiBaseUrl = if (query.any { it.code in 0x4E00..0x9FFF }) {
                "https://zh.wikipedia.org/w/api.php"
            } else {
                "https://en.wikipedia.org/w/api.php"
            }
            val response = httpClient.get(apiBaseUrl) {
                parameter("action", "query")
                parameter("list", "search")
                parameter("srsearch", query)
                parameter("srlimit", commonOptions.resultSize.coerceIn(1, 10).toString())
                parameter("format", "json")
                parameter("utf8", "1")
                header("User-Agent", "AmberAgent/1.0 search (Android)")
            }
            if (!response.status.isSuccess()) {
                error("Wikipedia request failed #${response.status.value}")
            }
            val payload = response.bodyAsText().let { json.decodeFromString<WikipediaResponse>(it) }
            val wikiHost = if (query.any { it.code in 0x4E00..0x9FFF }) "zh.wikipedia.org" else "en.wikipedia.org"
            val base = "https://$wikiHost/wiki/"
            SearchResult(
                items = payload.query.search.map {
                    SearchResultItem(
                        title = it.title,
                        url = base + java.net.URLEncoder.encode(it.title.replace(' ', '_'), "UTF-8"),
                        text = it.snippet.replace(Regex("<[^>]+>"), ""),
                        publishedAt = it.timestamp,
                    )
                }
            )
        }
    }

    @Serializable
    private data class WikipediaResponse(
        val query: WikipediaQuery = WikipediaQuery(),
    )

    @Serializable
    private data class WikipediaQuery(
        val search: List<WikipediaItem> = emptyList(),
    )

    @Serializable
    private data class WikipediaItem(
        val title: String,
        val snippet: String = "",
        val timestamp: String? = null,
    )
}
