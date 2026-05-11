package me.rerere.rikkahub.data.agent.webmount.adapters.bilibili

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
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCookieBundle

/**
 * Bilibili API client. Phase 1 limits itself to unsigned endpoints
 * (popular feed, video info, search, user history) so we don't need to
 * implement WBI (`w_rid`) signing — that's a complex SHA-256 mixin-key
 * dance that's much easier to keep in-page via `wm_eval` than to port to
 * Kotlin. M1.6 adapter ships the unsigned subset and documents the gap.
 *
 * Cookies come from WebMountCookieProvider after the user logs into
 * www.bilibili.com via the in-app WebView; `SESSDATA` is the session
 * cookie that personal endpoints (history) require.
 */
class BilibiliClient(
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun popular(cookies: WebMountCookieBundle, pageSize: Int): List<BilibiliVideoSummary> {
        val response = http.get("$API_BASE/x/web-interface/popular") {
            applyHeaders(cookies)
            parameter("ps", pageSize.coerceIn(1, 50))
            parameter("pn", 1)
        }
        val data = parseEnvelope(response, "popular").data as? JsonObject ?: return emptyList()
        return (data["list"] as? JsonArray).orEmpty().mapNotNull { entry ->
            (entry as? JsonObject)?.let { parseVideoSummary(it) }
        }
    }

    suspend fun videoInfo(cookies: WebMountCookieBundle, bvid: String): BilibiliVideoDetail? {
        require(bvid.isNotBlank()) { "bvid is required" }
        val response = http.get("$API_BASE/x/web-interface/view") {
            applyHeaders(cookies)
            parameter("bvid", bvid)
        }
        val data = parseEnvelope(response, "video info").data as? JsonObject ?: return null
        val owner = data["owner"] as? JsonObject
        val stat = data["stat"] as? JsonObject
        return BilibiliVideoDetail(
            bvid = data.s("bvid") ?: bvid,
            aid = data.l("aid") ?: 0L,
            title = data.s("title") ?: "",
            desc = data.s("desc"),
            pic = data.s("pic"),
            ownerName = owner?.s("name"),
            ownerMid = owner?.l("mid"),
            durationSec = data.i("duration") ?: 0,
            view = stat?.i("view") ?: 0,
            danmaku = stat?.i("danmaku") ?: 0,
            reply = stat?.i("reply") ?: 0,
            like = stat?.i("like") ?: 0,
            coin = stat?.i("coin") ?: 0,
            favorite = stat?.i("favorite") ?: 0,
            share = stat?.i("share") ?: 0,
            pubdateMs = (data.l("pubdate") ?: 0L) * 1000L,
        )
    }

    suspend fun search(cookies: WebMountCookieBundle, query: String, page: Int, limit: Int): List<BilibiliVideoSummary> {
        require(query.isNotBlank()) { "query is required" }
        val response = http.get("$API_BASE/x/web-interface/search/type") {
            applyHeaders(cookies)
            parameter("search_type", "video")
            parameter("keyword", query)
            parameter("page", page.coerceAtLeast(1))
            parameter("page_size", limit.coerceIn(1, 50))
        }
        val data = parseEnvelope(response, "search").data as? JsonObject ?: return emptyList()
        return (data["result"] as? JsonArray).orEmpty().mapNotNull { entry ->
            (entry as? JsonObject)?.let { parseSearchHit(it) }
        }
    }

    suspend fun history(cookies: WebMountCookieBundle, pageSize: Int): List<BilibiliHistoryItem> {
        val response = http.get("$API_BASE/x/v2/history") {
            applyHeaders(cookies)
            parameter("pn", 1)
            parameter("ps", pageSize.coerceIn(1, 50))
        }
        val arr = parseEnvelope(response, "history").data as? JsonArray ?: return emptyList()
        return arr.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            BilibiliHistoryItem(
                bvid = obj.s("bvid"),
                aid = obj.l("aid"),
                title = obj.s("title") ?: "",
                authorName = obj.s("author_name"),
                cover = obj.s("cover"),
                viewAtMs = (obj.l("view_at") ?: 0L) * 1000L,
                progressSec = obj.i("progress"),
            )
        }
    }

    suspend fun probe(cookies: WebMountCookieBundle): Boolean = runCatching {
        popular(cookies, pageSize = 1).isNotEmpty()
    }.getOrElse { false }

    // ----------------------------------------------------------------------

    private fun HttpRequestBuilder.applyHeaders(cookies: WebMountCookieBundle) {
        headers {
            append(HttpHeaders.UserAgent, USER_AGENT)
            append(HttpHeaders.Accept, "application/json")
            append("Referer", "https://www.bilibili.com/")
            append("Origin", "https://www.bilibili.com")
            if (!cookies.isEmpty) append(HttpHeaders.Cookie, cookies.header)
        }
    }

    private suspend fun parseEnvelope(response: HttpResponse, label: String): Envelope {
        val text = response.bodyAsText()
        require(response.status.isSuccess()) {
            "$label HTTP ${response.status.value}: ${text.take(500)}"
        }
        val outer = (json.parseToJsonElement(text) as? JsonObject)
            ?: error("$label returned non-object body")
        val code = outer["code"]?.jsonPrimitive?.intOrNull
        if (code != null && code != 0) {
            val msg = outer["message"]?.jsonPrimitive?.contentOrNull
                ?: outer["msg"]?.jsonPrimitive?.contentOrNull
                ?: "(no message)"
            error("$label failed (code=$code msg=$msg)")
        }
        return Envelope(code = code, data = outer["data"])
    }

    private fun parseVideoSummary(obj: JsonObject): BilibiliVideoSummary {
        val owner = obj["owner"] as? JsonObject
        val stat = obj["stat"] as? JsonObject
        return BilibiliVideoSummary(
            bvid = obj.s("bvid") ?: "",
            aid = obj.l("aid") ?: 0L,
            title = obj.s("title") ?: "",
            ownerName = owner?.s("name"),
            ownerMid = owner?.l("mid"),
            pic = obj.s("pic"),
            durationSec = obj.i("duration") ?: 0,
            viewCount = stat?.i("view") ?: 0,
            danmakuCount = stat?.i("danmaku") ?: 0,
            pubdateMs = (obj.l("pubdate") ?: 0L) * 1000L,
        )
    }

    private fun parseSearchHit(obj: JsonObject): BilibiliVideoSummary {
        return BilibiliVideoSummary(
            bvid = obj.s("bvid") ?: "",
            aid = obj.l("aid") ?: 0L,
            title = obj.s("title")?.replace(Regex("""<[^>]+>"""), "") ?: "",
            ownerName = obj.s("author"),
            ownerMid = obj.l("mid"),
            pic = obj.s("pic"),
            durationSec = (obj.s("duration") ?: "0").toIntOrNull() ?: 0,
            viewCount = obj.i("play") ?: 0,
            danmakuCount = obj.i("video_review") ?: 0,
            pubdateMs = (obj.l("pubdate") ?: 0L) * 1000L,
        )
    }

    private fun JsonObject.s(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.i(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.l(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private data class Envelope(val code: Int?, val data: JsonElement?)

    companion object {
        private const val API_BASE = "https://api.bilibili.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
    }
}

data class BilibiliVideoSummary(
    val bvid: String,
    val aid: Long,
    val title: String,
    val ownerName: String?,
    val ownerMid: Long?,
    val pic: String?,
    val durationSec: Int,
    val viewCount: Int,
    val danmakuCount: Int,
    val pubdateMs: Long,
)

data class BilibiliVideoDetail(
    val bvid: String,
    val aid: Long,
    val title: String,
    val desc: String?,
    val pic: String?,
    val ownerName: String?,
    val ownerMid: Long?,
    val durationSec: Int,
    val view: Int,
    val danmaku: Int,
    val reply: Int,
    val like: Int,
    val coin: Int,
    val favorite: Int,
    val share: Int,
    val pubdateMs: Long,
)

data class BilibiliHistoryItem(
    val bvid: String?,
    val aid: Long?,
    val title: String,
    val authorName: String?,
    val cover: String?,
    val viewAtMs: Long,
    val progressSec: Int?,
)
