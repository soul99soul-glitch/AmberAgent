package app.amber.feature.webmount.adapters.juejin

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import app.amber.feature.webmount.core.WebMountCookieBundle

/**
 * 掘金 (Juejin) API client. Uses the `api.juejin.cn` JSON endpoints with
 * the user's logged-in cookies (read from Android CookieManager via the
 * WebMount cookie provider on the adapter side).
 *
 * Public listing endpoints (推荐沸点 / 推荐文章) work without auth but rate-limit
 * by IP. Personal endpoints (我的收藏 / 我的文章) need the `sid_tt` /
 * `sessionid` cookies set after a Web login on juejin.cn.
 */
class JuejinClient(
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun recommendArticles(cookies: WebMountCookieBundle, limit: Int, cursor: String?): JuejinListing {
        val body = buildJsonObject {
            put("id_type", 2)
            put("client_type", 2608)
            put("sort_type", 200)
            put("cursor", cursor ?: "0")
            put("limit", limit.coerceIn(1, 50))
        }
        val parsed = postJson("$API_BASE/recommend_api/v1/article/recommend_all_feed", cookies, body)
        return parseListing(parsed, ItemKind.ARTICLE)
    }

    suspend fun shortMsgFeed(cookies: WebMountCookieBundle, limit: Int, cursor: String?): JuejinListing {
        val body = buildJsonObject {
            put("client_type", 2608)
            put("sort_type", 200)
            put("cursor", cursor ?: "0")
            put("limit", limit.coerceIn(1, 50))
        }
        val parsed = postJson("$API_BASE/recommend_api/v1/short_msg/recommend", cookies, body)
        return parseListing(parsed, ItemKind.PIN)
    }

    suspend fun searchArticles(cookies: WebMountCookieBundle, query: String, limit: Int): JuejinListing {
        require(query.isNotBlank()) { "query is required" }
        val response = http.post("$SEARCH_BASE/search_api/v1/search") {
            applyDefaultHeaders(cookies)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("aid", 2608)
                    put("uuid", "0")
                    put("spider", 0)
                    put("search_type", 0)
                    put("query", query)
                    put("id_type", 0)
                    put("cursor", "0")
                    put("limit", limit.coerceIn(1, 50))
                    put("search_attribute", buildJsonObject {})
                }.toString()
            )
        }
        return parseListing(parseJson(response, "juejin search"), ItemKind.ARTICLE)
    }

    suspend fun articleDetail(cookies: WebMountCookieBundle, articleId: String): JuejinArticle? {
        require(articleId.isNotBlank()) { "article_id required" }
        val body = buildJsonObject {
            put("article_id", articleId)
            put("client_type", 2608)
        }
        val parsed = postJson("$API_BASE/content_api/v1/article/detail", cookies, body) as? JsonObject ?: return null
        val data = parsed["data"] as? JsonObject ?: return null
        return parseArticleDetail(data)
    }

    suspend fun myPosts(cookies: WebMountCookieBundle, userId: String?, limit: Int, cursor: String?): JuejinListing {
        // Requires the user's own `userId` (the numeric one from their profile URL).
        require(!userId.isNullOrBlank()) { "user_id required for my_posts" }
        val body = buildJsonObject {
            put("user_id", userId)
            put("sort_type", 2)
            put("cursor", cursor ?: "0")
            put("limit", limit.coerceIn(1, 50))
        }
        val parsed = postJson("$API_BASE/content_api/v1/article/query_list", cookies, body)
        return parseListing(parsed, ItemKind.ARTICLE)
    }

    // ----------------------------------------------------------- internals

    private suspend fun postJson(
        url: String,
        cookies: WebMountCookieBundle,
        body: JsonObject,
    ): JsonElement {
        val response = http.post(url) {
            applyDefaultHeaders(cookies)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return parseJson(response, url)
    }

    private suspend fun parseJson(response: HttpResponse, label: String): JsonElement {
        val text = response.bodyAsText()
        require(response.status.isSuccess()) {
            "$label failed: HTTP ${response.status.value} ${text.take(500)}"
        }
        val parsed = json.parseToJsonElement(text)
        // 掘金 wraps all responses as {err_no, err_msg, data, cursor, has_more}.
        // Non-zero err_no is a logical failure even though HTTP is 200.
        if (parsed is JsonObject) {
            val errNo = parsed["err_no"]?.jsonPrimitive?.intOrNull
            if (errNo != null && errNo != 0) {
                val errMsg = parsed["err_msg"]?.jsonPrimitive?.contentOrNull ?: "(no message)"
                error("掘金 API err_no=$errNo: $errMsg")
            }
        }
        return parsed
    }

    private fun HttpRequestBuilder.applyDefaultHeaders(cookies: WebMountCookieBundle) {
        headers {
            append(HttpHeaders.UserAgent, USER_AGENT)
            append(HttpHeaders.Accept, "application/json, text/plain, */*")
            append("Origin", "https://juejin.cn")
            append("Referer", "https://juejin.cn/")
            if (!cookies.isEmpty) append(HttpHeaders.Cookie, cookies.header)
        }
    }

    private fun parseListing(element: JsonElement, kind: ItemKind): JuejinListing {
        val outer = element as? JsonObject ?: return JuejinListing(emptyList(), null, false)
        val items = (outer["data"] as? JsonArray)
            .orEmpty()
            .mapNotNull { entry -> (entry as? JsonObject)?.let { parseItem(it, kind) } }
        val cursor = outer["cursor"]?.jsonPrimitive?.contentOrNull
        val hasMore = outer["has_more"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        return JuejinListing(items, cursor, hasMore)
    }

    private fun parseItem(entry: JsonObject, kind: ItemKind): JuejinItem? = when (kind) {
        ItemKind.ARTICLE -> {
            val articleInfo = entry["article_info"] as? JsonObject
            val author = entry["author_user_info"] as? JsonObject
            if (articleInfo == null) null
            else JuejinItem.Article(
                articleId = articleInfo.s("article_id").orEmpty(),
                title = articleInfo.s("title").orEmpty(),
                briefContent = articleInfo.s("brief_content"),
                authorName = author?.s("user_name"),
                viewCount = articleInfo.i("view_count"),
                diggCount = articleInfo.i("digg_count"),
                commentCount = articleInfo.i("comment_count"),
                createdAtMs = (articleInfo.l("ctime") ?: 0L) * 1000L,
                articleUrl = articleInfo.s("article_id")?.let { "https://juejin.cn/post/$it" },
            )
        }
        ItemKind.PIN -> {
            val msgInfo = entry["msg_info"] as? JsonObject
            val author = entry["author_user_info"] as? JsonObject
            if (msgInfo == null) null
            else JuejinItem.Pin(
                msgId = msgInfo.s("msg_id").orEmpty(),
                content = msgInfo.s("content"),
                authorName = author?.s("user_name"),
                diggCount = msgInfo.i("digg_count"),
                commentCount = msgInfo.i("comment_count"),
                createdAtMs = (msgInfo.l("ctime") ?: 0L) * 1000L,
            )
        }
    }

    private fun parseArticleDetail(data: JsonObject): JuejinArticle {
        val articleInfo = data["article_info"] as? JsonObject ?: error("missing article_info in juejin response")
        val author = data["author_user_info"] as? JsonObject
        return JuejinArticle(
            articleId = articleInfo.s("article_id").orEmpty(),
            title = articleInfo.s("title").orEmpty(),
            content = articleInfo.s("mark_content") ?: articleInfo.s("content"),
            authorName = author?.s("user_name"),
            viewCount = articleInfo.i("view_count"),
            diggCount = articleInfo.i("digg_count"),
            commentCount = articleInfo.i("comment_count"),
            createdAtMs = (articleInfo.l("ctime") ?: 0L) * 1000L,
            articleUrl = "https://juejin.cn/post/${articleInfo.s("article_id").orEmpty()}",
        )
    }

    private fun JsonObject.s(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.i(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.l(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private enum class ItemKind { ARTICLE, PIN }

    companion object {
        private const val API_BASE = "https://api.juejin.cn"
        private const val SEARCH_BASE = "https://api.juejin.cn"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
    }
}

sealed class JuejinItem {
    abstract val createdAtMs: Long

    data class Article(
        val articleId: String,
        val title: String,
        val briefContent: String?,
        val authorName: String?,
        val viewCount: Int?,
        val diggCount: Int?,
        val commentCount: Int?,
        override val createdAtMs: Long,
        val articleUrl: String?,
    ) : JuejinItem()

    data class Pin(
        val msgId: String,
        val content: String?,
        val authorName: String?,
        val diggCount: Int?,
        val commentCount: Int?,
        override val createdAtMs: Long,
    ) : JuejinItem()
}

data class JuejinListing(
    val items: List<JuejinItem>,
    val cursor: String?,
    val hasMore: Boolean,
)

data class JuejinArticle(
    val articleId: String,
    val title: String,
    val content: String?,
    val authorName: String?,
    val viewCount: Int?,
    val diggCount: Int?,
    val commentCount: Int?,
    val createdAtMs: Long,
    val articleUrl: String?,
)
