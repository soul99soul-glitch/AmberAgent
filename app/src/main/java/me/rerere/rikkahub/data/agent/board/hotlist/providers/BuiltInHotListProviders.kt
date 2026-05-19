package me.rerere.rikkahub.data.agent.board.hotlist.providers

import kotlinx.coroutines.Dispatchers
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
import me.rerere.rikkahub.data.agent.board.hotlist.HotListItem
import me.rerere.rikkahub.data.agent.board.hotlist.HotListProvider
import me.rerere.rikkahub.data.agent.board.hotlist.HotListProviderIds
import me.rerere.rikkahub.data.agent.board.hotlist.HotListResult
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
        Kr36Provider(client),
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
        val root = client.getJson("https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all", json)
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
            .take(limit)
        val items = ids.mapIndexedNotNull { index, id ->
            val item = runCatching {
                client.getJson("https://hacker-news.firebaseio.com/v0/item/$id.json", json).jsonObject
            }.getOrNull() ?: return@mapIndexedNotNull null
            val title = item.string("title") ?: return@mapIndexedNotNull null
            HotListItem(
                rank = index + 1,
                title = title,
                heat = item.numberString("score"),
                url = item.string("url") ?: "https://news.ycombinator.com/item?id=$id",
                category = item.string("type"),
            )
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
        val root = client.getJson("https://weibo.com/ajax/side/hotSearch", json)
        val list = root.obj("data")?.arr("realtime").orEmpty()
        HotListResult(
            items = list.take(limit).mapIndexedNotNull { index, element ->
                val obj = element.jsonObject
                val title = obj.string("word") ?: obj.string("note") ?: return@mapIndexedNotNull null
                val url = "https://s.weibo.com/weibo?q=${java.net.URLEncoder.encode(title, "UTF-8")}"
                HotListItem(
                    rank = index + 1,
                    title = title,
                    heat = obj.numberString("num"),
                    url = url,
                    category = obj.string("label_name"),
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
        val root = client.getJson("https://www.zhihu.com/api/v3/feed/topstory/hot-lists/total?limit=$limit", json)
        val list = root.arr("data").orEmpty()
        HotListResult(
            items = list.take(limit).mapIndexedNotNull { index, element ->
                val obj = element.jsonObject
                val target = obj.obj("target")
                val title = target?.string("title") ?: obj.string("title") ?: return@mapIndexedNotNull null
                HotListItem(
                    rank = index + 1,
                    title = title,
                    heat = obj.string("detail_text"),
                    url = target?.string("url") ?: target?.string("link") ?: obj.string("url"),
                    category = target?.string("type"),
                )
            },
            fetchedAt = System.currentTimeMillis(),
        )
    }
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
                )
            }
            .toList()
        HotListResult(items = items, fetchedAt = System.currentTimeMillis())
    }
}

private suspend fun <T> withJsonTimeout(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        withTimeout(10_000L) { block() }
    }

private suspend fun OkHttpClient.getJson(url: String, json: Json): JsonElement =
    json.parseToJsonElement(getText(url))

private suspend fun OkHttpClient.getText(url: String): String {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json,text/html,application/xml;q=0.9,*/*;q=0.8")
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
private fun JsonObject.numberString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun String.xmlTag(tag: String): String? =
    Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) AmberAgent/2.0"
