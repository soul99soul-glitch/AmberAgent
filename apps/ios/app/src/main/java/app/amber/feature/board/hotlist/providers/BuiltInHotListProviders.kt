package app.amber.feature.board.hotlist.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.feature.board.hotlist.HotListItem
import app.amber.feature.board.hotlist.HotListProvider
import app.amber.feature.board.hotlist.HotListProviderIds
import app.amber.feature.board.hotlist.HotListResult
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BuiltInHotListProviders(
    private val client: OkHttpClient,
    private val json: Json,
) {
    fun all(): List<HotListProvider> = listOf(
        WeiboProvider(client, json),
        ZhihuProvider(client, json),
        BilibiliProvider(client, json),
        HackerNewsProvider(client, json),
        ArxivAiProvider(client),
        InfoqAiProvider(client),
        Kr36Provider(client),
        HuggingFacePapersProvider(client),
        GithubTrendingAiProvider(client),
    )
}

private class BilibiliProvider(
    private val client: OkHttpClient,
    private val json: Json,
) : HotListProvider {
    override val id = HotListProviderIds.BILIBILI
    override val displayName = "B站热门"
    override val isBuiltIn = true

    override suspend fun fetch(limit: Int): HotListResult = withJsonTimeout {
        val root = client.getJson(
            url = "https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all",
            json = json,
            headers = BILIBILI_HEADERS,
        )
        val list = root.obj("data")?.arr("list").orEmpty()
        HotListResult(
            items = list.take(limit).mapIndexedNotNull { index, element ->
                val obj = element.jsonObject
                val title = obj.string("title") ?: return@mapIndexedNotNull null
                HotListItem(
                    rank = index + 1,
                    title = title,
                    heat = obj.obj("stat")?.numberString("view"),
                    url = obj.string("short_link_v2") ?: obj.string("short_link") ?: obj.string("arcurl"),
                    category = obj.string("tname"),
                    images = listOfNotNull(obj.string("pic")),
                )
            },
            fetchedAt = System.currentTimeMillis(),
        )
    }
}

private class HackerNewsProvider(
    private val client: OkHttpClient,
    private val json: Json,
) : HotListProvider {
    override val id = HotListProviderIds.HACKER_NEWS
    override val displayName = "Hacker News"
    override val isBuiltIn = true

    override suspend fun fetch(limit: Int): HotListResult = withJsonTimeout {
        val ids = client.getJson("https://hacker-news.firebaseio.com/v0/topstories.json", json)
            .jsonArray
            .mapNotNull { it.jsonPrimitive.intOrNull }
            .take(limit.coerceAtMost(MAX_HN_ITEMS))
        val items = coroutineScope {
            ids.mapIndexed { index, id ->
                async {
                    val item = runCatching {
                        client.getJson("https://hacker-news.firebaseio.com/v0/item/$id.json", json).jsonObject
                    }.getOrNull() ?: return@async null
                    val title = item.string("title") ?: return@async null
                    HotListItem(
                        rank = index + 1,
                        title = title,
                        heat = item.numberString("score"),
                        url = item.string("url") ?: "https://news.ycombinator.com/item?id=$id",
                        category = item.string("type"),
                    )
                }
            }.awaitAll().filterNotNull().sortedBy { it.rank }
        }
        HotListResult(items = items, fetchedAt = System.currentTimeMillis())
    }
}

private class WeiboProvider(
    private val client: OkHttpClient,
    private val json: Json,
) : HotListProvider {
    override val id = HotListProviderIds.WEIBO
    override val displayName = "微博热搜"
    override val isBuiltIn = true

    override suspend fun fetch(limit: Int): HotListResult = withJsonTimeout {
        val root = client.getJson(
            url = "https://weibo.com/ajax/statuses/hot_band",
            json = json,
            headers = WEIBO_HEADERS,
        )
        val list = root.obj("data")?.arr("band_list").orEmpty()
        HotListResult(
            items = list
                .mapNotNull { it.jsonObject.takeUnless { obj -> obj.int("is_ad") == 1 } }
                .take(limit)
                .mapIndexedNotNull { index, obj ->
                    val title = obj.string("word") ?: obj.string("note") ?: return@mapIndexedNotNull null
                    val url = "https://s.weibo.com/weibo?q=${java.net.URLEncoder.encode(title, "UTF-8")}"
                    HotListItem(
                        rank = obj.int("realpos")?.takeIf { it > 0 } ?: index + 1,
                        title = title,
                        heat = obj.numberString("num") ?: obj.numberString("raw_hot"),
                        url = url,
                        category = obj.string("category") ?: obj.string("label_name"),
                        images = listOfNotNull(obj.string("pic_id")?.takeIf { it.startsWith("http") }),
                    )
                },
            fetchedAt = System.currentTimeMillis(),
        )
    }
}

private class ZhihuProvider(
    private val client: OkHttpClient,
    private val json: Json,
) : HotListProvider {
    override val id = HotListProviderIds.ZHIHU
    override val displayName = "知乎热榜"
    override val isBuiltIn = true

    override suspend fun fetch(limit: Int): HotListResult = withJsonTimeout {
        val root = client.getJson(
            url = "https://api.zhihu.com/topstory/hot-list",
            json = json,
            headers = ZHIHU_HEADERS,
        )
        val list = root.arr("data").orEmpty()
        HotListResult(
            items = list.take(limit).mapIndexedNotNull { index, element ->
                val obj = element.jsonObject
                val target = obj.obj("target")
                val title = target?.string("title") ?: obj.string("title") ?: return@mapIndexedNotNull null
                val questionId = target?.numberString("id")
                HotListItem(
                    rank = index + 1,
                    title = title,
                    heat = obj.string("detail_text"),
                    url = questionId?.let { "https://www.zhihu.com/question/$it" }
                        ?: target?.string("url")
                        ?: target?.string("link")
                        ?: obj.string("url"),
                    category = target?.string("type"),
                    images = runCatching {
                        target?.arr("thumbnail_info")
                            ?.firstOrNull()
                            ?.jsonObject
                            ?.string("url")
                            ?.let(::listOf)
                            .orEmpty()
                    }.getOrDefault(emptyList()),
                )
            },
            fetchedAt = System.currentTimeMillis(),
        )
    }
}

private class ArxivAiProvider(
    private val client: OkHttpClient,
) : HotListProvider {
    override val id = HotListProviderIds.ARXIV_AI
    override val displayName = "arXiv AI"
    override val isBuiltIn = true

    override suspend fun fetch(limit: Int): HotListResult =
        fetchRssFeeds(
            client = client,
            urls = ARXIV_AI_RSS_URLS,
            limit = limit,
            category = "paper",
        )
}

private class InfoqAiProvider(
    private val client: OkHttpClient,
) : HotListProvider {
    override val id = HotListProviderIds.INFOQ_AI
    override val displayName = "InfoQ AI"
    override val isBuiltIn = true

    override suspend fun fetch(limit: Int): HotListResult =
        fetchRssFeeds(
            client = client,
            urls = INFOQ_AI_RSS_URLS,
            limit = limit,
            category = "article",
        )
}

private class Kr36Provider(
    private val client: OkHttpClient,
) : HotListProvider {
    override val id = HotListProviderIds.KR36
    override val displayName = "36Kr"
    override val isBuiltIn = true

    override suspend fun fetch(limit: Int): HotListResult = withJsonTimeout {
        val body = client.getText("https://36kr.com/feed")
        val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(body)
            .take(limit)
            .mapIndexedNotNull { index, match ->
                val block = match.groupValues[1]
                val title = block.xmlTag("title")
                    ?.removePrefix("<![CDATA[")
                    ?.removeSuffix("]]>")
                    ?.trim()
                    ?: return@mapIndexedNotNull null
                HotListItem(
                    rank = index + 1,
                    title = title,
                    url = block.xmlTag("link"),
                    category = "rss",
                    images = block.extractImageUrls(),
                )
            }
            .toList()
        HotListResult(items = items, fetchedAt = System.currentTimeMillis())
    }
}

private class HuggingFacePapersProvider(
    private val client: OkHttpClient,
) : HotListProvider {
    override val id = HotListProviderIds.HUGGINGFACE_PAPERS
    override val displayName = "HF Papers"
    override val isBuiltIn = true

    override suspend fun fetch(limit: Int): HotListResult = withJsonTimeout {
        val body = client.getText("https://huggingface.co/papers", DESKTOP_HTML_HEADERS)
        val seen = linkedSetOf<String>()
        val items = Regex("""href=["']/papers/([^"']+)["']""")
            .findAll(body)
            .mapNotNull { match ->
                val slug = match.groupValues[1]
                if (!seen.add(slug)) return@mapNotNull null
                val block = body.windowAround(match.range.first)
                val title = block.firstTagText("h3")
                    ?: block.firstTagText("h2")
                    ?: slug.replace('-', ' ')
                HotListItem(
                    rank = seen.size,
                    title = title.cleanMarkup(),
                    url = "https://huggingface.co/papers/$slug",
                    category = "paper",
                    images = block.extractImageUrls(),
                )
            }
            .take(limit)
            .toList()
        if (items.isEmpty()) error("Hugging Face returned no papers")
        HotListResult(items = items, fetchedAt = System.currentTimeMillis())
    }
}

private class GithubTrendingAiProvider(
    private val client: OkHttpClient,
) : HotListProvider {
    override val id = HotListProviderIds.GITHUB_TRENDING_AI
    override val displayName = "GitHub AI"
    override val isBuiltIn = true

    override suspend fun fetch(limit: Int): HotListResult = withJsonTimeout {
        val bodies = coroutineScope {
            GITHUB_AI_URLS.map { url ->
                async {
                    runCatching { client.getText(url, DESKTOP_HTML_HEADERS) }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        val seen = linkedSetOf<String>()
        val items = bodies.flatMap { body ->
            Regex("""href=["']/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)["']""")
                .findAll(body)
                .mapNotNull { match ->
                    val repo = match.groupValues[1]
                    if (!seen.add(repo)) return@mapNotNull null
                    val block = body.windowAround(match.range.first)
                    HotListItem(
                        rank = seen.size,
                        title = repo.replace("/", " / "),
                        heat = block.firstStarText(),
                        url = "https://github.com/$repo",
                        category = "github",
                        images = block.extractImageUrls(),
                    )
                }
                .toList()
        }.take(limit)
        if (items.isEmpty()) error("GitHub returned no AI repositories")
        HotListResult(items = items, fetchedAt = System.currentTimeMillis())
    }
}

private suspend fun fetchRssFeeds(
    client: OkHttpClient,
    urls: List<String>,
    limit: Int,
    category: String,
): HotListResult = withJsonTimeout {
    val bodies = coroutineScope {
        urls.map { url ->
            async {
                runCatching { client.getText(url, RSS_HEADERS) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }
    val seen = linkedSetOf<String>()
    val items = bodies.flatMap { body ->
        body.rssBlocks().mapNotNull { block ->
            val title = block.xmlTag("title")?.cleanMarkup() ?: return@mapNotNull null
            val link = block.xmlTag("link")?.cleanMarkup()?.takeIf { it.startsWith("http") }
            val key = link ?: title
            if (!seen.add(key)) return@mapNotNull null
            HotListItem(
                rank = seen.size,
                title = title,
                url = link,
                category = category,
                images = block.extractImageUrls(),
            )
        }
    }.take(limit)
    if (items.isEmpty()) error("RSS returned no items")
    HotListResult(items = items, fetchedAt = System.currentTimeMillis())
}

private suspend fun <T> withJsonTimeout(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        withTimeout(10_000L) { block() }
    }

private suspend fun OkHttpClient.getJson(
    url: String,
    json: Json,
    headers: Map<String, String> = emptyMap(),
): JsonElement = json.parseToJsonElement(getText(url, headers))

private suspend fun OkHttpClient.getText(
    url: String,
    headers: Map<String, String> = emptyMap(),
): String {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", headers["User-Agent"] ?: USER_AGENT)
        .header("Accept", "application/json,text/html,application/xml;q=0.9,*/*;q=0.8")
        .also { builder -> headers.forEach { (name, value) -> builder.header(name, value) } }
        .build()
    newCall(request).await().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code}: ${response.message}")
        return response.body.string()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isActive) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) {
                    response.close()
                    return
                }
                cont.resume(response)
            }
        }
    )
}

private fun JsonElement.obj(key: String): JsonObject? = jsonObject[key]?.jsonObject
private fun JsonElement.arr(key: String): JsonArray? = jsonObject[key]?.jsonArray
private fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject
private fun JsonObject.arr(key: String): JsonArray? = this[key]?.jsonArray
private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.numberString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun String.xmlTag(tag: String): String? =
    Regex("<${Regex.escape(tag)}\\b[^>]*>(.*?)</${Regex.escape(tag)}>", RegexOption.DOT_MATCHES_ALL)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()

private fun String.rssBlocks(): List<String> {
    val rssItems = Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        .findAll(this)
        .map { it.groupValues[1] }
        .toList()
    if (rssItems.isNotEmpty()) return rssItems
    return Regex("<entry\\b[^>]*>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
        .findAll(this)
        .map { it.groupValues[1] }
        .toList()
}

private fun String.firstTagText(tag: String): String? =
    Regex("<$tag\\b[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.cleanMarkup()

private fun String.windowAround(index: Int, radius: Int = 1_400): String {
    val start = (index - radius).coerceAtLeast(0)
    val end = (index + radius).coerceAtMost(length)
    return substring(start, end)
}

private fun String.firstStarText(): String? =
    Regex("""([0-9][0-9,.kK]*)\s*(?:stars?|星)""")
        .find(cleanMarkup())
        ?.groupValues
        ?.getOrNull(1)

private fun String.extractImageUrls(): List<String> {
    val patterns = listOf(
        Regex("""<media:(?:content|thumbnail)\b[^>]*\burl=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<enclosure\b[^>]*\burl=["']([^"']+)["'][^>]*(?:image/)""", RegexOption.IGNORE_CASE),
        Regex("""<img\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
    )
    return patterns
        .asSequence()
        .flatMap { pattern -> pattern.findAll(this).map { it.groupValues[1] } }
        .map { it.cleanMarkup() }
        .filter { it.startsWith("http") }
        .distinct()
        .take(4)
        .toList()
}

private fun String.cleanMarkup(): String =
    removePrefix("<![CDATA[")
        .removeSuffix("]]>")
        .replace(Regex("<[^>]+>"), " ")
        .htmlUnescape()
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.htmlUnescape(): String =
    replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) AmberAgent/2.0"

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"

private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

private const val MAX_HN_ITEMS = 30

private val ARXIV_AI_RSS_URLS = listOf(
    "https://rss.arxiv.org/rss/cs.AI",
    "https://rss.arxiv.org/rss/cs.CL",
    "https://rss.arxiv.org/rss/cs.LG",
    "https://rss.arxiv.org/rss/cs.RO",
)

private val INFOQ_AI_RSS_URLS = listOf(
    "https://www.infoq.com/artificial_intelligence/rss/",
    "https://feed.infoq.com/artificial_intelligence/",
)

private val GITHUB_AI_URLS = listOf(
    "https://github.com/topics/artificial-intelligence?o=desc&s=updated",
    "https://github.com/topics/large-language-models?o=desc&s=updated",
    "https://github.com/topics/robotics?o=desc&s=updated",
)

private val RSS_HEADERS = mapOf(
    "User-Agent" to DESKTOP_USER_AGENT,
    "Accept" to "application/rss+xml,application/atom+xml,application/xml,text/xml,text/html;q=0.8,*/*;q=0.6",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)

private val DESKTOP_HTML_HEADERS = mapOf(
    "User-Agent" to DESKTOP_USER_AGENT,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)

private val BILIBILI_HEADERS = mapOf(
    "User-Agent" to DESKTOP_USER_AGENT,
    "Referer" to "https://www.bilibili.com/v/popular/rank/all",
    "Origin" to "https://www.bilibili.com",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)

private val WEIBO_HEADERS = mapOf(
    "User-Agent" to DESKTOP_USER_AGENT,
    "Referer" to "https://s.weibo.com/top/summary",
    "Accept" to "application/json,text/plain,*/*",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)

private val ZHIHU_HEADERS = mapOf(
    "User-Agent" to MOBILE_USER_AGENT,
    "Referer" to "https://www.zhihu.com/hot",
    "Accept" to "application/json,*/*",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)
