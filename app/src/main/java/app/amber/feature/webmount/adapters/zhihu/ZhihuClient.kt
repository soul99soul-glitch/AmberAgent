package app.amber.feature.webmount.adapters.zhihu

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
import app.amber.feature.webmount.core.WebMountCookieBundle

/**
 * 知乎 (Zhihu) API client. Cookie-based after the user signs into
 * www.zhihu.com in the in-app WebView.
 *
 * Phase 1 caveats: 知乎's modern endpoints require `x-zse-93` / `x-zse-96`
 * signature headers (a SHA-256 + cookie-derived signing dance that's
 * easier to reproduce in-page via wm_eval than in Kotlin). We probe an
 * unsigned-friendly endpoint and surface 风控 / 403 errors as DEGRADED
 * so the panel reports "logged in but rate-limited" instead of a hard
 * ERROR. M1.7+ may add per-call WBI/zse signing through a helper.
 */
class ZhihuClient(
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun feed(cookies: WebMountCookieBundle, limit: Int): List<ZhihuFeedItem> {
        require(!cookies.isEmpty) { "Zhihu feed requires login cookies" }
        val response = http.get("$WEB_BASE/api/v4/feed/topstory") {
            applyHeaders(cookies)
            parameter("limit", limit.coerceIn(1, 50))
            parameter("page_number", 1)
            parameter("session_token", cookies.value("z_c0") ?: "")
        }
        val data = parseEnvelope(response, "feed") as? JsonObject ?: return emptyList()
        return (data["data"] as? JsonArray).orEmpty().mapNotNull { entry ->
            (entry as? JsonObject)?.let { parseFeedItem(it) }
        }
    }

    suspend fun question(cookies: WebMountCookieBundle, questionId: String): ZhihuQuestion? {
        require(questionId.isNotBlank()) { "question_id is required" }
        val response = http.get("$WEB_BASE/api/v4/questions/$questionId") {
            applyHeaders(cookies)
            parameter("include", "title,detail,answer_count,follower_count,visit_count,updated_time,created")
        }
        val data = parseEnvelope(response, "question") as? JsonObject ?: return null
        return ZhihuQuestion(
            id = data.s("id") ?: questionId,
            title = data.s("title") ?: "",
            detail = data.s("detail"),
            answerCount = data.i("answer_count"),
            followerCount = data.i("follower_count"),
            visitCount = data.i("visit_count"),
            createdMs = (data.l("created") ?: 0L) * 1000L,
            updatedMs = (data.l("updated_time") ?: 0L) * 1000L,
        )
    }

    suspend fun answersFor(cookies: WebMountCookieBundle, questionId: String, limit: Int, offset: Int): List<ZhihuAnswer> {
        val response = http.get("$WEB_BASE/api/v4/questions/$questionId/answers") {
            applyHeaders(cookies)
            parameter("include", "data[*].content,voteup_count,comment_count,author.name,created_time,updated_time")
            parameter("limit", limit.coerceIn(1, 20))
            parameter("offset", offset.coerceAtLeast(0))
            parameter("sort_by", "default")
        }
        val data = parseEnvelope(response, "answers") as? JsonObject ?: return emptyList()
        return (data["data"] as? JsonArray).orEmpty().mapNotNull { entry ->
            (entry as? JsonObject)?.let { parseAnswer(it) }
        }
    }

    suspend fun answer(cookies: WebMountCookieBundle, answerId: String): ZhihuAnswer? {
        val response = http.get("$WEB_BASE/api/v4/answers/$answerId") {
            applyHeaders(cookies)
            parameter("include", "content,voteup_count,comment_count,author.name,created_time,updated_time,question")
        }
        val data = parseEnvelope(response, "answer") as? JsonObject ?: return null
        return parseAnswer(data)
    }

    suspend fun search(cookies: WebMountCookieBundle, query: String, limit: Int): List<ZhihuSearchHit> {
        val response = http.get("$WEB_BASE/api/v4/search_v3") {
            applyHeaders(cookies)
            parameter("t", "general")
            parameter("q", query)
            parameter("correction", 1)
            parameter("offset", 0)
            parameter("limit", limit.coerceIn(1, 30))
            parameter("filter_fields", "")
        }
        val data = parseEnvelope(response, "search") as? JsonObject ?: return emptyList()
        return (data["data"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val highlight = (obj["highlight"] as? JsonObject)
            val content = (obj["object"] as? JsonObject) ?: return@mapNotNull null
            ZhihuSearchHit(
                type = content.s("type") ?: obj.s("type") ?: "",
                id = content.s("id") ?: "",
                title = (highlight?.s("title") ?: content.s("title"))
                    ?.replace(Regex("""<[^>]+>"""), "") ?: "",
                excerpt = (highlight?.s("description") ?: content.s("excerpt"))
                    ?.replace(Regex("""<[^>]+>"""), ""),
                url = content.s("url"),
            )
        }
    }

    suspend fun probe(cookies: WebMountCookieBundle): Boolean = runCatching {
        // The /api/v4/feed/topstory endpoint requires login but tolerates
        // missing signature headers more often than the new /web-search ones.
        // If feed returns >0 items, we treat the station as healthy.
        if (cookies.isEmpty) return false
        feed(cookies, limit = 1).isNotEmpty()
    }.getOrElse { false }

    // ----------------------------------------------------------------------

    private fun HttpRequestBuilder.applyHeaders(cookies: WebMountCookieBundle) {
        headers {
            append(HttpHeaders.UserAgent, USER_AGENT)
            append(HttpHeaders.Accept, "application/json, text/plain, */*")
            append("Referer", "https://www.zhihu.com/")
            append("x-requested-with", "fetch")
            if (!cookies.isEmpty) append(HttpHeaders.Cookie, cookies.header)
        }
    }

    private suspend fun parseEnvelope(response: HttpResponse, label: String): JsonElement? {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val errMsg = runCatching {
                (json.parseToJsonElement(text) as? JsonObject)
                    ?.let { it["error"] as? JsonObject }
                    ?.s("message")
            }.getOrNull()
            error("$label HTTP ${response.status.value}: ${errMsg ?: text.take(300)}")
        }
        val parsed = json.parseToJsonElement(text)
        if (parsed is JsonObject) {
            val err = parsed["error"] as? JsonObject
            if (err != null) {
                val code = err["code"]?.jsonPrimitive?.intOrNull
                val msg = err.s("message") ?: "(no message)"
                error("$label zhihu error code=$code msg=$msg")
            }
        }
        return parsed
    }

    private fun parseFeedItem(entry: JsonObject): ZhihuFeedItem? {
        val target = (entry["target"] as? JsonObject) ?: return null
        val type = target.s("type") ?: entry.s("type") ?: "unknown"
        return ZhihuFeedItem(
            type = type,
            id = target.s("id") ?: "",
            title = target.s("title")
                ?: (target["question"] as? JsonObject)?.s("title")
                ?: "",
            authorName = (target["author"] as? JsonObject)?.s("name"),
            excerpt = target.s("excerpt"),
            voteupCount = target.i("voteup_count"),
            commentCount = target.i("comment_count"),
            url = target.s("url"),
        )
    }

    private fun parseAnswer(obj: JsonObject): ZhihuAnswer {
        val question = obj["question"] as? JsonObject
        val author = obj["author"] as? JsonObject
        return ZhihuAnswer(
            id = obj.s("id") ?: "",
            questionId = question?.s("id"),
            questionTitle = question?.s("title"),
            authorName = author?.s("name"),
            content = obj.s("content"),
            voteupCount = obj.i("voteup_count"),
            commentCount = obj.i("comment_count"),
            createdMs = (obj.l("created_time") ?: 0L) * 1000L,
            updatedMs = (obj.l("updated_time") ?: 0L) * 1000L,
            url = obj.s("url"),
        )
    }

    private fun JsonObject.s(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.i(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.l(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    companion object {
        private const val WEB_BASE = "https://www.zhihu.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
    }
}

data class ZhihuFeedItem(
    val type: String,
    val id: String,
    val title: String,
    val authorName: String?,
    val excerpt: String?,
    val voteupCount: Int?,
    val commentCount: Int?,
    val url: String?,
)

data class ZhihuQuestion(
    val id: String,
    val title: String,
    val detail: String?,
    val answerCount: Int?,
    val followerCount: Int?,
    val visitCount: Int?,
    val createdMs: Long,
    val updatedMs: Long,
)

data class ZhihuAnswer(
    val id: String,
    val questionId: String?,
    val questionTitle: String?,
    val authorName: String?,
    val content: String?,
    val voteupCount: Int?,
    val commentCount: Int?,
    val createdMs: Long,
    val updatedMs: Long,
    val url: String?,
)

data class ZhihuSearchHit(
    val type: String,
    val id: String,
    val title: String,
    val excerpt: String?,
    val url: String?,
)
