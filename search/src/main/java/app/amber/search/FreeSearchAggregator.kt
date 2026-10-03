package app.amber.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.search.SearchResult.SearchResultItem
import app.amber.search.SearchService.Companion.httpClient
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 无需 API Key 的免费搜索引擎。解析方式参考 deedy5/ddgs 与 searxng 的公开引擎实现，
 * 与 iOS 端 IOSFreeSearchAggregator 保持一致；百度、搜狗、Mojeek、Startpage、Yandex、
 * DuckDuckGo Lite、无 Key 的 Jina Search 在 2026-09 实测中返回验证码/403/401，未接入。
 */
enum class FreeSearchEngine(val displayName: String) {
    WIKIPEDIA("Wikipedia"),
    BING("Bing"),
    BRAVE("Brave"),
    DUCKDUCKGO("DuckDuckGo"),
    QUARK("夸克"),
    SO360("360 搜索"),
    HACKER_NEWS("Hacker News"),
}

/** 验证码 / 反爬页 / 限流：进入冷却，冷却期内不再请求该引擎。 */
class FreeSearchBlockedException : Exception("遇到验证页，已暂停 10 分钟")

data class FreeSearchResponse(val code: Int, val body: String)

fun interface FreeSearchFetcher {
    suspend fun fetch(request: Request): FreeSearchResponse
}

/** 多引擎并发查询、单引擎失败跳过、按引擎轮流合并去重。 */
object FreeSearchAggregator {
    const val NAME = "免费聚合搜索"
    private const val BLOCKED_COOLDOWN_MS = 10 * 60 * 1000L
    private const val MAX_RESULTS = 20

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val API_USER_AGENT = "AmberAgent-Android/1.0 (free search aggregator)"

    private val cooldownUntil = ConcurrentHashMap<FreeSearchEngine, Long>()
    private val json = Json { ignoreUnknownKeys = true }

    private val defaultFetcher = FreeSearchFetcher { request ->
        val client = httpClient.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        client.newCall(request).await().use { response ->
            FreeSearchResponse(response.code, response.body.string())
        }
    }

    fun resetCooldowns() = cooldownUntil.clear()

    suspend fun search(
        query: String,
        resultSize: Int,
        fetcher: FreeSearchFetcher = defaultFetcher,
        now: () -> Long = System::currentTimeMillis,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = query.trim()
            require(trimmed.isNotEmpty()) { "query is required" }
            val engines = engines(trimmed, now())
            val outcomes = coroutineScope {
                engines.map { engine ->
                    async { engine to runCatching { search(engine, trimmed, fetcher) } }
                }.awaitAll()
            }

            val perEngine = LinkedHashMap<FreeSearchEngine, List<SearchResultItem>>()
            val failures = mutableListOf<String>()
            outcomes.forEach { (engine, outcome) ->
                outcome.onSuccess {
                    cooldownUntil.remove(engine)
                    perEngine[engine] = it
                }.onFailure { error ->
                    if (error is FreeSearchBlockedException) {
                        cooldownUntil[engine] = now() + BLOCKED_COOLDOWN_MS
                    }
                    failures += "${engine.displayName}：${error.message ?: error::class.simpleName}"
                }
            }

            val merged = roundRobinMerge(perEngine, engines, resultSize.coerceIn(1, MAX_RESULTS))
            check(merged.isNotEmpty()) {
                "免费搜索源均未返回结果（${failures.ifEmpty { listOf("所有免费源都没有返回结果") }.joinToString("；")}）"
            }
            SearchResult(items = merged)
        }
    }

    internal fun engines(query: String, now: Long): List<FreeSearchEngine> {
        return FreeSearchEngine.entries.filter { engine ->
            val until = cooldownUntil[engine]
            if (until != null && until > now) return@filter false
            // HN 只有英文技术讨论，中文查询只会带来噪声。
            engine != FreeSearchEngine.HACKER_NEWS || !containsCJK(query)
        }
    }

    /** 按引擎顺序轮流取结果：保证最终列表里各引擎都有代表，而不是被第一个引擎占满。 */
    internal fun roundRobinMerge(
        perEngine: Map<FreeSearchEngine, List<SearchResultItem>>,
        order: List<FreeSearchEngine>,
        limit: Int,
    ): List<SearchResultItem> {
        val lists = order.mapNotNull { perEngine[it] }
        val depth = lists.maxOfOrNull { it.size } ?: 0
        val seen = HashSet<String>()
        val output = mutableListOf<SearchResultItem>()
        for (index in 0 until depth) {
            for (list in lists) {
                val item = list.getOrNull(index) ?: continue
                val key = dedupeKey(item.url)
                if (key.isEmpty() || !seen.add(key)) continue
                output += item
                if (output.size >= limit) return output
            }
        }
        return output
    }

    internal fun dedupeKey(url: String): String {
        return url.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").removePrefix("m.")
            .trimEnd('/')
    }

    // region Engines

    private suspend fun search(
        engine: FreeSearchEngine,
        query: String,
        fetcher: FreeSearchFetcher,
    ): List<SearchResultItem> = when (engine) {
        FreeSearchEngine.BING -> {
            val html = send(htmlGet("https://www.bing.com/search", "q" to query), fetcher)
            BingSearchService.parseDocument(Jsoup.parse(html), MAX_RESULTS).orBlocked(html)
        }
        FreeSearchEngine.DUCKDUCKGO -> {
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .post(FormBody.Builder().add("q", query).add("b", "").build())
                .browserHeaders()
                .header("Referer", "https://html.duckduckgo.com/")
                .build()
            val html = send(request, fetcher)
            parseDuckDuckGoHtml(html).orBlocked(html)
        }
        FreeSearchEngine.BRAVE -> {
            val html = send(htmlGet("https://search.brave.com/search", "q" to query, "source" to "web"), fetcher)
            parseBraveHtml(html).orBlocked(html)
        }
        FreeSearchEngine.SO360 -> {
            val html = send(htmlGet("https://www.so.com/s", "q" to query), fetcher)
            parse360Html(html).orBlocked(html)
        }
        FreeSearchEngine.QUARK -> {
            val html = send(htmlGet("https://quark.sm.cn/s", "q" to query, "layout" to "html", "page" to "1"), fetcher)
            // 夸克短时间约 9 次请求后返回阿里 X5SEC 验证页（见 searxng quark.py）。
            if (QUARK_CAPTCHA.containsMatchIn(html)) throw FreeSearchBlockedException()
            parseQuarkHtml(html)
        }
        FreeSearchEngine.WIKIPEDIA -> {
            val lang = if (containsCJK(query)) "zh" else "en"
            val url = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("generator", "search")
                .addQueryParameter("gsrsearch", query)
                .addQueryParameter("gsrlimit", "2")
                .addQueryParameter("prop", "extracts|info")
                .addQueryParameter("exintro", "1")
                .addQueryParameter("explaintext", "1")
                .addQueryParameter("exchars", "600")
                .addQueryParameter("inprop", "url")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .build()
            parseWikipediaJson(send(apiRequest(url.toString()), fetcher))
        }
        FreeSearchEngine.HACKER_NEWS -> {
            val url = "https://hn.algolia.com/api/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("tags", "story")
                .addQueryParameter("hitsPerPage", "5")
                .build()
            parseHackerNewsJson(send(apiRequest(url.toString()), fetcher))
        }
    }

    private fun htmlGet(base: String, vararg query: Pair<String, String>): Request {
        val url = base.toHttpUrl().newBuilder()
            .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        return Request.Builder().url(url).browserHeaders().build()
    }

    // 桌面 UA：移动 UA 会让 360/Bing 跳转到结构不同的移动页。
    private fun Request.Builder.browserHeaders(): Request.Builder = this
        .header("User-Agent", DESKTOP_USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

    // Wikimedia 的 UA 政策要求可识别的客户端标识。
    private fun apiRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", API_USER_AGENT)
        .header("Accept", "application/json")
        .build()

    private suspend fun send(request: Request, fetcher: FreeSearchFetcher): String {
        val response = fetcher.fetch(request)
        if (response.code == 202 || response.code == 403 || response.code == 429) {
            throw FreeSearchBlockedException()
        }
        check(response.code in 200..299) { "HTTP ${response.code}" }
        return response.body
    }

    private fun List<SearchResultItem>.orBlocked(html: String): List<SearchResultItem> {
        if (isEmpty() && looksBlocked(html)) throw FreeSearchBlockedException()
        return this
    }

    private fun looksBlocked(html: String): Boolean {
        val lower = html.lowercase()
        return listOf("captcha", "anomaly", "unusual traffic", "are you a robot", "antispider", "verifying your browser")
            .any { lower.contains(it) }
    }

    internal fun containsCJK(text: String): Boolean =
        text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }

    // endregion

    // region Parsers

    private val QUARK_CAPTCHA = Regex(""""action"\s*:\s*"captcha"""")
    private val QUARK_SC = setOf("ss_doc", "ss_text", "ss_pic", "ss_kv", "baike")

    internal fun parseDuckDuckGoHtml(html: String): List<SearchResultItem> {
        return Jsoup.parse(html).select(".result").mapNotNull { result ->
            if (result.hasClass("result--ad")) return@mapNotNull null
            val anchor = result.selectFirst("a.result__a") ?: return@mapNotNull null
            val url = decodeDuckDuckGoUrl(anchor.attr("href"))
            if (url.contains("duckduckgo.com/y.js")) return@mapNotNull null
            item(anchor.text(), url, result.select(".result__snippet").text())
        }
    }

    internal fun parseBraveHtml(html: String): List<SearchResultItem> {
        return Jsoup.parse(html).select("[data-type=web]").mapNotNull { block ->
            val anchor = block.select("a[href^=http]").firstOrNull { a ->
                runCatching { !a.attr("href").toHttpUrl().host.endsWith("brave.com") }.getOrDefault(false)
            } ?: return@mapNotNull null
            val titleElement = block.selectFirst(".title")
            val title = titleElement?.attr("title")?.ifBlank { null } ?: titleElement?.text().orEmpty()
            item(title, anchor.attr("href"), block.select(".content, .snippet-description").firstOrNull()?.text().orEmpty())
        }
    }

    internal fun parse360Html(html: String): List<SearchResultItem> {
        return Jsoup.parse(html).select("li.res-list").mapNotNull { block ->
            val anchor = block.selectFirst("h3.res-title a") ?: return@mapNotNull null
            val url = anchor.attr("data-mdurl").ifBlank { anchor.attr("href") }
            // 360 自家 AI 聚合页、跳转页不是原始来源。
            val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return@mapNotNull null
            if (host.endsWith("so.com")) return@mapNotNull null
            val snippet = block.select("p.res-desc, span.res-list-summary").firstOrNull()?.text().orEmpty()
            item(anchor.text(), url, snippet)
        }
    }

    internal fun parseQuarkHtml(html: String): List<SearchResultItem> {
        return Jsoup.parse(html).select("script[type=application/json][id^=s-data-][data-used-by=hydrate]")
            .mapNotNull { script ->
                val root = runCatching { json.parseToJsonElement(script.data()).jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                val sc = root.obj("extraData")?.str("sc")
                if (sc !in QUARK_SC) return@mapNotNull null
                val data = root.obj("data")?.obj("initialData") ?: return@mapNotNull null
                val title = data.obj("titleProps")?.str("content") ?: data.str("title").orEmpty()
                val url = data.obj("sourceProps")?.str("dest_url")
                    ?: data.str("normal_url") ?: data.str("url").orEmpty()
                val summary = data.obj("summaryProps")?.str("content")
                    ?: data.str("show_body") ?: data.str("desc").orEmpty()
                item(Jsoup.parse(title).text(), url, Jsoup.parse(summary).text())
            }
    }

    internal fun parseWikipediaJson(body: String): List<SearchResultItem> {
        val pages = runCatching {
            json.parseToJsonElement(body).jsonObject.obj("query")?.get("pages")?.jsonArray
        }.getOrNull() ?: return emptyList()
        return pages.map { it.jsonObject }
            .sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
            .mapNotNull { page ->
                val extract = page.str("extract").orEmpty().trim()
                // 消歧义页没有实质内容。
                if (extract.isEmpty() || "may refer to" in extract || "可以指" in extract) return@mapNotNull null
                item(page.str("title").orEmpty(), page.str("fullurl").orEmpty(), extract)
            }
    }

    internal fun parseHackerNewsJson(body: String): List<SearchResultItem> {
        val hits = runCatching {
            json.parseToJsonElement(body).jsonObject["hits"] as? JsonArray
        }.getOrNull() ?: return emptyList()
        return hits.map { it.jsonObject }.mapNotNull { hit ->
            val objectId = hit.str("objectID").orEmpty()
            val discussion = "https://news.ycombinator.com/item?id=$objectId"
            val points = hit["points"]?.jsonPrimitive?.intOrNull ?: 0
            val comments = hit["num_comments"]?.jsonPrimitive?.intOrNull ?: 0
            SearchResultItem(
                title = hit.str("title")?.trim().orEmpty(),
                url = hit.str("url")?.ifBlank { null } ?: discussion,
                text = "Hacker News 讨论：$points 分，$comments 条评论。$discussion",
                publishedAt = hit.str("created_at"),
            ).takeIf { it.title.isNotEmpty() }
        }
    }

    private fun decodeDuckDuckGoUrl(rawHref: String): String {
        val href = rawHref.trim().let { if (it.startsWith("//")) "https:$it" else it }
        return runCatching { href.toHttpUrl().queryParameter("uddg") }.getOrNull()?.ifBlank { null } ?: href
    }

    private fun item(title: String, url: String, snippet: String): SearchResultItem? {
        val cleanTitle = title.trim()
        val cleanUrl = url.trim()
        if (cleanTitle.isEmpty() || !cleanUrl.startsWith("http")) return null
        return SearchResultItem(title = cleanTitle, url = cleanUrl, text = snippet.trim())
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    // endregion
}
