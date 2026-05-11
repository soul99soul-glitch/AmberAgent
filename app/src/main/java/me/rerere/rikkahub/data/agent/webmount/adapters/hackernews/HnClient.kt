package me.rerere.rikkahub.data.agent.webmount.adapters.hackernews

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * HackerNews HTTP client. Two surfaces:
 *  - Firebase API (https://hacker-news.firebaseio.com/v0/) — anonymous, no rate limit,
 *    returns JSON for item / user / top / new / best / ask / show stories.
 *  - Algolia search (https://hn.algolia.com/api/v1/) — anonymous, returns JSON
 *    with `hits[]`, used for full-text search.
 *
 * No auth, no cookies, no headers besides JSON Accept.
 */
class HnClient(private val http: HttpClient, private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }) {

    suspend fun storyIds(feed: HnFeed, limit: Int): List<Long> {
        val raw = getJson("$FIREBASE_BASE/${feed.endpoint}.json").jsonArray
        return raw.take(limit.coerceAtLeast(1)).mapNotNull { it.jsonPrimitive.longOrNull }
    }

    suspend fun item(id: Long): HnItem? {
        val element = getJson("$FIREBASE_BASE/item/$id.json")
        if (element !is JsonObject) return null
        return parseItem(element)
    }

    suspend fun user(handle: String): JsonObject? {
        val element = getJson("$FIREBASE_BASE/user/$handle.json")
        return element as? JsonObject
    }

    suspend fun search(query: String, tags: String?, hitsPerPage: Int): HnSearchResult {
        require(query.isNotBlank()) { "query is required" }
        val response = http.get("$ALGOLIA_BASE/search") {
            parameter("query", query)
            parameter("hitsPerPage", hitsPerPage.coerceIn(1, 50))
            if (!tags.isNullOrBlank()) parameter("tags", tags)
        }
        val text = response.bodyAsTextOrFail("Algolia search")
        val parsed = json.parseToJsonElement(text).jsonObject
        val hits = (parsed["hits"] as? JsonArray)?.mapNotNull { hit ->
            val obj = hit as? JsonObject ?: return@mapNotNull null
            HnSearchHit(
                objectId = obj.s("objectID") ?: return@mapNotNull null,
                title = obj.s("title") ?: obj.s("story_title"),
                author = obj.s("author"),
                url = obj.s("url") ?: obj.s("story_url"),
                points = obj.i("points") ?: obj.i("story_points"),
                numComments = obj.i("num_comments"),
                createdAtMs = (obj.l("created_at_i") ?: 0L) * 1000L,
            )
        }.orEmpty()
        return HnSearchResult(
            query = query,
            hits = hits,
            nbHits = parsed.i("nbHits") ?: hits.size,
            page = parsed.i("page") ?: 0,
        )
    }

    private suspend fun getJson(url: String): JsonElement {
        val response = http.get(url)
        val text = response.bodyAsTextOrFail("GET $url")
        return json.parseToJsonElement(text)
    }

    private suspend fun HttpResponse.bodyAsTextOrFail(label: String): String {
        val body = bodyAsText()
        require(status.isSuccess()) { "$label failed: ${status.value} ${body.take(500)}" }
        return body
    }

    private fun parseItem(o: JsonObject): HnItem = HnItem(
        id = o.l("id") ?: 0L,
        type = o.s("type") ?: "unknown",
        title = o.s("title"),
        author = o.s("by"),
        url = o.s("url"),
        text = o.s("text"),
        score = o.i("score"),
        descendants = o.i("descendants"),
        kids = (o["kids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty(),
        createdAtMs = (o.l("time") ?: 0L) * 1000L,
        parent = o.l("parent"),
        deleted = o["deleted"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
        dead = o["dead"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
    )

    private fun JsonObject.s(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.i(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.l(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

    companion object {
        private const val FIREBASE_BASE = "https://hacker-news.firebaseio.com/v0"
        private const val ALGOLIA_BASE = "https://hn.algolia.com/api/v1"
    }
}

enum class HnFeed(val endpoint: String) {
    TOP("topstories"),
    NEW("newstories"),
    BEST("beststories"),
    ASK("askstories"),
    SHOW("showstories"),
    JOB("jobstories"),
    ;

    companion object {
        fun fromWire(s: String?): HnFeed = entries.firstOrNull { it.endpoint.equals(s, true) || it.name.equals(s, true) } ?: TOP
    }
}

data class HnItem(
    val id: Long,
    val type: String,
    val title: String?,
    val author: String?,
    val url: String?,
    val text: String?,
    val score: Int?,
    val descendants: Int?,
    val kids: List<Long>,
    val createdAtMs: Long,
    val parent: Long?,
    val deleted: Boolean,
    val dead: Boolean,
)

data class HnSearchHit(
    val objectId: String,
    val title: String?,
    val author: String?,
    val url: String?,
    val points: Int?,
    val numComments: Int?,
    val createdAtMs: Long,
)

data class HnSearchResult(
    val query: String,
    val hits: List<HnSearchHit>,
    val nbHits: Int,
    val page: Int,
)
