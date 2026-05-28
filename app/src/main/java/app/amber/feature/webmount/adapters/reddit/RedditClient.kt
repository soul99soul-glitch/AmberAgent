package app.amber.feature.webmount.adapters.reddit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reddit anonymous public JSON client. Reads `<reddit>.json` endpoints.
 *
 *  - Listing: `https://www.reddit.com/r/<subreddit>/<sort>.json`
 *  - Permalink: `https://www.reddit.com<permalink>.json` → [post, comments]
 *  - Search: `https://www.reddit.com/search.json?q=...&restrict_sr=...`
 *
 * Reddit rate-limits public JSON heavily and **requires** a descriptive
 * User-Agent — the default Java UA gets 429s within seconds. We send a
 * stable AmberAgent UA on every request.
 */
class RedditClient(
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun listing(
        subreddit: String?,
        sort: RedditSort,
        limit: Int,
        after: String?,
    ): RedditListing {
        val path = if (subreddit.isNullOrBlank()) {
            "/${sort.endpoint}.json"
        } else {
            "/r/${subreddit.trim('/').trim()}/${sort.endpoint}.json"
        }
        val response = http.get("$BASE$path") {
            redditDefaults()
            parameter("limit", limit.coerceIn(1, 100))
            parameter("raw_json", 1)
            if (!after.isNullOrBlank()) parameter("after", after)
        }
        val parsed = parseJson(response, "Reddit listing")
        return parseListing(parsed)
    }

    suspend fun postWithComments(permalink: String, commentLimit: Int = 30): RedditPostDetail {
        val cleaned = permalink.trim('/').ifBlank { error("permalink is required") }
        val response = http.get("$BASE/$cleaned.json") {
            redditDefaults()
            parameter("limit", commentLimit.coerceIn(1, 200))
            parameter("raw_json", 1)
        }
        val parsed = parseJson(response, "Reddit comments")
        require(parsed is JsonArray && parsed.size >= 2) {
            "Unexpected Reddit comments shape (got ${parsed.javaClass.simpleName})"
        }
        val postListing = parseListing(parsed[0])
        val commentsListing = parseListing(parsed[1])
        val post = postListing.children.firstOrNull() ?: error("Reddit post missing")
        return RedditPostDetail(post = post, comments = commentsListing.children)
    }

    suspend fun search(query: String, subreddit: String?, limit: Int): RedditListing {
        require(query.isNotBlank()) { "query is required" }
        val path = if (subreddit.isNullOrBlank()) "/search.json" else "/r/${subreddit.trim('/').trim()}/search.json"
        val response = http.get("$BASE$path") {
            redditDefaults()
            parameter("q", query)
            parameter("limit", limit.coerceIn(1, 100))
            parameter("raw_json", 1)
            if (!subreddit.isNullOrBlank()) parameter("restrict_sr", "true")
        }
        return parseListing(parseJson(response, "Reddit search"))
    }

    suspend fun about(subreddit: String): JsonObject? {
        val response = http.get("$BASE/r/${subreddit.trim('/').trim()}/about.json") {
            redditDefaults()
            parameter("raw_json", 1)
        }
        val parsed = parseJson(response, "Reddit about") as? JsonObject ?: return null
        return parsed["data"] as? JsonObject
    }

    private suspend fun parseJson(response: HttpResponse, label: String): kotlinx.serialization.json.JsonElement {
        val text = response.bodyAsText()
        require(response.status.isSuccess()) {
            "$label failed: HTTP ${response.status.value} ${text.take(500)}"
        }
        return json.parseToJsonElement(text)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.redditDefaults() {
        header("User-Agent", USER_AGENT)
        header("Accept", "application/json")
    }

    private fun parseListing(element: kotlinx.serialization.json.JsonElement): RedditListing {
        val outer = element as? JsonObject ?: return RedditListing(emptyList(), null, null)
        val data = outer["data"] as? JsonObject ?: return RedditListing(emptyList(), null, null)
        val children = (data["children"] as? JsonArray).orEmpty().mapNotNull { child ->
            val obj = child as? JsonObject ?: return@mapNotNull null
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
            val payload = obj["data"] as? JsonObject ?: return@mapNotNull null
            parseChild(kind, payload)
        }
        return RedditListing(
            children = children,
            after = data["after"]?.jsonPrimitive?.contentOrNull,
            before = data["before"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseChild(kind: String?, data: JsonObject): RedditChild = when (kind) {
        "t1" -> RedditChild.Comment(
            id = data.s("id").orEmpty(),
            author = data.s("author"),
            body = data.s("body"),
            score = data.i("score"),
            createdAtMs = (data.l("created_utc") ?: 0L) * 1000L,
            permalink = data.s("permalink"),
            parentId = data.s("parent_id"),
        )
        else -> RedditChild.Post(
            id = data.s("id").orEmpty(),
            subreddit = data.s("subreddit"),
            author = data.s("author"),
            title = data.s("title"),
            url = data.s("url"),
            permalink = data.s("permalink"),
            selftext = data.s("selftext"),
            score = data.i("score"),
            numComments = data.i("num_comments"),
            createdAtMs = (data.l("created_utc") ?: 0L) * 1000L,
            isSelf = data["is_self"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
            over18 = data["over_18"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
        )
    }

    private fun JsonObject.s(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.i(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.l(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    companion object {
        private const val BASE = "https://www.reddit.com"
        // Reddit explicitly forbids the default Java UA and 429s within seconds.
        // Including AmberAgent contact in the UA per Reddit's API guidelines.
        private const val USER_AGENT = "AmberAgent/1.0 (+https://github.com/soul99soul-glitch/AmberAgent)"
    }
}

enum class RedditSort(val endpoint: String) {
    HOT("hot"),
    NEW("new"),
    TOP("top"),
    RISING("rising"),
    CONTROVERSIAL("controversial"),
    ;

    companion object {
        fun fromWire(s: String?): RedditSort =
            entries.firstOrNull { it.endpoint.equals(s, true) || it.name.equals(s, true) } ?: HOT
    }
}

data class RedditListing(
    val children: List<RedditChild>,
    val after: String?,
    val before: String?,
)

sealed class RedditChild {
    abstract val id: String

    data class Post(
        override val id: String,
        val subreddit: String?,
        val author: String?,
        val title: String?,
        val url: String?,
        val permalink: String?,
        val selftext: String?,
        val score: Int?,
        val numComments: Int?,
        val createdAtMs: Long,
        val isSelf: Boolean,
        val over18: Boolean,
    ) : RedditChild()

    data class Comment(
        override val id: String,
        val author: String?,
        val body: String?,
        val score: Int?,
        val createdAtMs: Long,
        val permalink: String?,
        val parentId: String?,
    ) : RedditChild()
}

data class RedditPostDetail(
    val post: RedditChild,
    val comments: List<RedditChild>,
)
