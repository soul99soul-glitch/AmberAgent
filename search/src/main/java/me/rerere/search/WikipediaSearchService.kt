package me.rerere.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

object WikipediaSearchService {
    const val name: String = "Wikipedia"

    suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val host = if (query.any { it.code in 0x4E00..0x9FFF }) {
                "https://zh.wikipedia.org/w/api.php"
            } else {
                "https://en.wikipedia.org/w/api.php"
            }
            val url = host.toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("list", "search")
                .addQueryParameter("srsearch", query)
                .addQueryParameter("srlimit", commonOptions.resultSize.coerceIn(1, 10).toString())
                .addQueryParameter("format", "json")
                .addQueryParameter("utf8", "1")
                .build()
            val response = httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "AmberAgent/1.0 search (Android)")
                    .build()
            ).await()
            if (!response.isSuccessful) {
                error("Wikipedia request failed #${response.code}")
            }
            val payload = response.body.string().let { json.decodeFromString<WikipediaResponse>(it) }
            val base = "${url.scheme}://${url.host}/wiki/"
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
