package app.amber.feature.board.hotlist.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.feature.board.hotlist.HotListItem
import app.amber.feature.board.hotlist.HotListProvider
import app.amber.feature.board.hotlist.HotListResult
import me.rerere.rikkahub.data.db.entity.HotListSourceEntity
import okhttp3.OkHttpClient
import okhttp3.Request

object CustomHotListSourceTypes {
    const val RSS = "rss"
    const val JSON = "json"
}

@Serializable
data class CustomHotListFieldMapping(
    val itemsPath: String = "",
    val titlePath: String = "title",
    val urlPath: String = "url",
    val heatPath: String = "",
    val categoryPath: String = "",
    val imagePath: String = "",
)

class CustomHotListProvider(
    private val source: HotListSourceEntity,
    private val client: OkHttpClient,
    private val json: Json,
) : HotListProvider {
    override val id: String = source.id
    override val displayName: String = source.displayName
    override val isBuiltIn: Boolean = false

    override suspend fun fetch(limit: Int): HotListResult =
        when (source.sourceType) {
            CustomHotListSourceTypes.RSS -> fetchRss(limit)
            CustomHotListSourceTypes.JSON -> fetchJson(limit)
            else -> error("Unsupported custom hot list source type: ${source.sourceType}")
        }

    private suspend fun fetchRss(limit: Int): HotListResult = withFetchTimeout {
        source.url.requireHttpUrl()
        val body = client.getText(source.url, RSS_HEADERS)
        val items = body.rssBlocks()
            .mapIndexedNotNull { index, block ->
                val title = block.xmlTag("title")?.cleanMarkup() ?: return@mapIndexedNotNull null
                HotListItem(
                    rank = index + 1,
                    title = title,
                    url = block.xmlTag("link")?.cleanMarkup()?.takeIf { it.isHttpUrl() }
                        ?: block.preferredAtomLink(),
                    category = "rss",
                    images = block.extractImageUrls(),
                )
            }
            .take(limit)
        if (items.isEmpty()) error("RSS returned no items")
        HotListResult(items = items, fetchedAt = System.currentTimeMillis())
    }

    private suspend fun fetchJson(limit: Int): HotListResult = withFetchTimeout {
        source.url.requireHttpUrl()
        val mapping = runCatching {
            json.decodeFromString<CustomHotListFieldMapping>(source.fieldMappingJson)
        }.getOrDefault(CustomHotListFieldMapping())
        val root = json.parseToJsonElement(client.getText(source.url, JSON_HEADERS))
        val array = root.findItemsArray(mapping.itemsPath) ?: error("JSON items array not found")
        val items = array.take(limit).mapIndexedNotNull { index, element ->
            val title = element.pathString(mapping.titlePath)
                ?: element.pathString("title")
                ?: element.pathString("name")
                ?: element.pathString("word")
                ?: return@mapIndexedNotNull null
            HotListItem(
                rank = index + 1,
                title = title,
                url = element.pathString(mapping.urlPath)?.takeIf { it.isHttpUrl() },
                heat = element.pathString(mapping.heatPath),
                category = element.pathString(mapping.categoryPath),
                images = element.pathString(mapping.imagePath)
                    ?.takeIf { it.isHttpUrl() }
                    ?.let(::listOf)
                    .orEmpty(),
            )
        }
        if (items.isEmpty()) error("JSON returned no readable items")
        HotListResult(items = items, fetchedAt = System.currentTimeMillis())
    }
}

private suspend fun <T> withFetchTimeout(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        withTimeout(10_000L) { block() }
    }

private fun JsonElement.findItemsArray(path: String): JsonArray? {
    path.takeIf { it.isNotBlank() }?.let { explicit ->
        pathElement(explicit)?.jsonArrayOrNull()?.let { return it }
    }
    jsonArrayOrNull()?.let { return it }
    val candidates = listOf(
        "data.list",
        "data.items",
        "data",
        "items",
        "list",
        "result.list",
        "result.items",
    )
    return candidates.firstNotNullOfOrNull { candidate -> pathElement(candidate)?.jsonArrayOrNull() }
}

private fun JsonElement.pathString(path: String): String? =
    path.takeIf { it.isNotBlank() }
        ?.let { pathElement(it) }
        ?.jsonPrimitiveOrNull()
        ?.contentOrNull
        ?.cleanMarkup()
        ?.takeIf { it.isNotBlank() }

private fun JsonElement.pathElement(path: String): JsonElement? =
    path.split('.')
        .filter { it.isNotBlank() }
        .fold(this as JsonElement?) { current, segment ->
            current ?: return null
            val arrayIndex = segment.toIntOrNull()
            when {
                arrayIndex != null -> current.jsonArrayOrNull()?.getOrNull(arrayIndex)
                else -> current.jsonObjectOrNull()?.get(segment)
            }
        }

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
private fun JsonElement.jsonArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()
private fun JsonElement.jsonPrimitiveOrNull() = runCatching { jsonPrimitive }.getOrNull()

private fun OkHttpClient.getText(url: String, headers: Map<String, String>): String {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", headers["User-Agent"] ?: DESKTOP_USER_AGENT)
        .header("Accept", headers["Accept"] ?: "*/*")
        .also { builder -> headers.forEach { (name, value) -> builder.header(name, value) } }
        .build()
    newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code}: ${response.message}")
        val length = response.body.contentLength()
        if (length > MAX_RESPONSE_BYTES) error("response too large")
        val peeked = response.peekBody(MAX_RESPONSE_BYTES + 1L)
        if (peeked.contentLength() > MAX_RESPONSE_BYTES) error("response too large")
        return peeked.string()
    }
}

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

private fun String.xmlTag(tag: String): String? =
    Regex("<${Regex.escape(tag)}\\b[^>]*>(.*?)</${Regex.escape(tag)}>", RegexOption.DOT_MATCHES_ALL)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()

private fun String.preferredAtomLink(): String? {
    val links = Regex("<link\\b([^>]*)>", RegexOption.IGNORE_CASE)
        .findAll(this)
        .mapNotNull { match ->
            val attrs = match.groupValues.getOrNull(1).orEmpty()
            val href = attrs.attributeValue("href")?.cleanMarkup()?.takeIf { it.isHttpUrl() } ?: return@mapNotNull null
            AtomLink(
                href = href,
                rel = attrs.attributeValue("rel")?.lowercase(),
                type = attrs.attributeValue("type")?.lowercase(),
            )
        }
        .toList()
    return links.firstOrNull { it.rel == "alternate" && it.prefersHtml() }?.href
        ?: links.firstOrNull { it.rel == null && it.prefersHtml() }?.href
        ?: links.firstOrNull { it.rel == "alternate" }?.href
        ?: links.firstOrNull()?.href
}

private data class AtomLink(
    val href: String,
    val rel: String?,
    val type: String?,
) {
    fun prefersHtml(): Boolean = type == null || "html" in type
}

private fun String.attributeValue(attribute: String): String? =
    Regex("""\b${Regex.escape(attribute)}\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)

private fun String.extractImageUrls(): List<String> {
    val patterns = listOf(
        Regex("""<media:(?:content|thumbnail)\b[^>]*\burl=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<enclosure\b(?=[^>]*\btype=["']image/[^"']+["'])[^>]*\burl=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<enclosure\b[^>]*\burl=["']([^"']+)["'][^>]*(?:image/)""", RegexOption.IGNORE_CASE),
        Regex("""<img\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
    )
    return patterns
        .asSequence()
        .flatMap { pattern -> pattern.findAll(this).map { it.groupValues[1] } }
        .map { it.cleanMarkup() }
        .filter { it.isHttpUrl() }
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

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"

private val RSS_HEADERS = mapOf(
    "User-Agent" to DESKTOP_USER_AGENT,
    "Accept" to "application/rss+xml,application/atom+xml,application/xml,text/xml,text/html;q=0.8,*/*;q=0.6",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)

private val JSON_HEADERS = mapOf(
    "User-Agent" to DESKTOP_USER_AGENT,
    "Accept" to "application/json,text/plain,*/*",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)

private fun String.isHttpUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

private fun String.requireHttpUrl() {
    if (!isHttpUrl()) error("Only http/https URLs are supported")
}

private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
