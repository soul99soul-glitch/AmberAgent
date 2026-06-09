package app.amber.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.amber.search.SearchResult.SearchResultItem
import app.amber.search.SearchService.Companion.httpClient
import app.amber.search.SearchService.Companion.json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

object HackerNewsSearchService {
    const val name: String = "Hacker News"

    suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://hn.algolia.com/api/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("tags", "story")
                .addQueryParameter("hitsPerPage", commonOptions.resultSize.coerceIn(1, 20).toString())
                .build()
            val response = httpClient.newCall(Request.Builder().url(url).build()).await()
            if (!response.isSuccessful) {
                error("Hacker News request failed #${response.code}")
            }
            val payload = response.body.string().let { json.decodeFromString<HackerNewsResponse>(it) }
            SearchResult(
                items = payload.hits.mapNotNull { hit ->
                    val title = hit.title ?: hit.storyTitle ?: return@mapNotNull null
                    val url = hit.url ?: "https://news.ycombinator.com/item?id=${hit.objectID}"
                    SearchResultItem(
                        title = title,
                        url = url,
                        text = "HN points=${hit.points ?: 0}, comments=${hit.numComments ?: 0}",
                        publishedAt = hit.createdAt,
                    )
                }
            )
        }
    }

    @Serializable
    private data class HackerNewsResponse(
        val hits: List<HackerNewsHit> = emptyList(),
    )

    @Serializable
    private data class HackerNewsHit(
        @SerialName("objectID")
        val objectID: String,
        val title: String? = null,
        @SerialName("story_title")
        val storyTitle: String? = null,
        val url: String? = null,
        val points: Int? = null,
        @SerialName("num_comments")
        val numComments: Int? = null,
        @SerialName("created_at")
        val createdAt: String? = null,
    )
}
