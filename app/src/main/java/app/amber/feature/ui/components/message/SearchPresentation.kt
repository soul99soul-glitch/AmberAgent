package app.amber.feature.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.ui.components.richtext.SearchImageBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Locale

internal data class SearchImageRef(
    val url: String,
    val caption: String? = null,
    val sourceId: String? = null,
)

internal data class SourceRef(
    val host: String,
    val name: String,
    val url: String,
    val id: String? = null,
)

internal class SearchSourcesRegistry(
    private val byHost: Map<String, SourceRef>,
) {
    val isNotEmpty: Boolean get() = byHost.isNotEmpty()

    fun lookup(urlOrHost: String): SourceRef? {
        val host = normalizeSearchSourceHost(urlOrHost) ?: return null
        return byHost[host]
    }
}

internal data class SearchPresentation(
    val images: List<SearchImageRef>,
    val sources: SearchSourcesRegistry,
)

internal val EmptySearchPresentation = SearchPresentation(
    images = emptyList(),
    sources = SearchSourcesRegistry(emptyMap()),
)

internal val LocalSearchSources = compositionLocalOf<SearchSourcesRegistry?> { null }

internal fun List<UIMessagePart>.searchWebOutputsSignature(): String {
    return filterIsInstance<UIMessagePart.Tool>()
        .filter { it.toolName == "search_web" && it.isExecuted }
        .joinToString(separator = "\u001E") { tool ->
            buildString {
                append(tool.toolCallId)
                append('\u001F')
                append(tool.output.joinToString(separator = "\u001F") { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.text
                        else -> part.toString()
                    }
                })
            }
        }
}

@Composable
internal fun rememberSearchPresentation(parts: List<UIMessagePart>): SearchPresentation {
    val signature = parts.searchWebOutputsSignature()
    return remember(signature) {
        deriveSearchPresentation(parts)
    }
}

internal fun deriveSearchPresentation(parts: List<UIMessagePart>): SearchPresentation {
    val sourceRefs = linkedMapOf<String, SourceRef>()
    val imageRefs = mutableListOf<SearchImageRef>()
    val seenImages = linkedSetOf<String>()

    parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.toolName == "search_web" && it.isExecuted }
        .forEach { tool ->
            val items = runCatching {
                MessageRenderCache.toolOutputJson(tool.output).jsonObject["items"]?.jsonArray
            }.getOrNull() ?: return@forEach

            items.forEach { itemElement ->
                val item = itemElement as? JsonObject ?: return@forEach
                val url = item.stringValue("url") ?: return@forEach
                val host = normalizeSearchSourceHost(url)
                    ?: normalizeSearchSourceHost(item.stringValue("domain").orEmpty())
                    ?: return@forEach
                val title = item.stringValue("title").orEmpty()
                val source = SourceRef(
                    host = host,
                    name = searchSourceDisplayName(host = host, title = title),
                    url = url,
                    id = item.stringValue("id"),
                )
                sourceRefs.putIfAbsent(host, source)

                val images = item["images"] as? JsonArray ?: return@forEach
                images.forEach { imageElement ->
                    if (imageRefs.size >= 5) return@forEach
                    val imageUrl = imageElement.jsonPrimitive.content.takeIf(::isRenderableSearchImageUrl)
                        ?: return@forEach
                    if (seenImages.add(imageUrl)) {
                        imageRefs += SearchImageRef(
                            url = imageUrl,
                            caption = searchImageCaption(title = title, sourceName = source.name),
                            sourceId = source.id,
                        )
                    }
                }
            }
        }

    if (sourceRefs.isEmpty() && imageRefs.isEmpty()) return EmptySearchPresentation
    return SearchPresentation(
        images = imageRefs,
        sources = SearchSourcesRegistry(sourceRefs),
    )
}

@Composable
internal fun SearchImageGallery(
    images: List<SearchImageRef>,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    SearchImageBlock(
        urls = images.map { image ->
            if (image.caption.isNullOrBlank()) {
                image.url
            } else {
                "${image.url}|${image.caption.replace('|', ' ')}"
            }
        },
        modifier = modifier,
    )
}

internal fun normalizeSearchSourceHost(urlOrHost: String): String? {
    val raw = urlOrHost.trim()
    if (raw.isBlank()) return null
    val host = runCatching {
        val candidate = when {
            raw.startsWith("//") -> "https:$raw"
            raw.contains("://") -> raw
            else -> "https://$raw"
        }
        URI(candidate).host
    }.getOrNull()
        ?: raw.substringBefore('/').substringBefore('?').substringBefore('#')
    return host
        .lowercase(Locale.ROOT)
        .removePrefix("www.")
        .substringBefore(':')
        .takeIf { it.contains('.') || it.isNotBlank() }
}

private fun JsonObject.stringValue(name: String): String? {
    return this[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
}

private fun isRenderableSearchImageUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("//")
}

private fun searchImageCaption(title: String, sourceName: String): String? {
    val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: return sourceName
    return "$cleanTitle · $sourceName"
}

private fun searchSourceDisplayName(host: String, title: String): String {
    SEARCH_SOURCE_BRANDS[host]?.let { return it }
    val domainLabel = hostToReadableLabel(host)
    return domainLabel.takeIf { it.isNotBlank() }
        ?: title.substringBefore('|').substringBefore('-').trim().takeIf { it.isNotBlank() }
        ?: host
}

private fun hostToReadableLabel(host: String): String {
    val withoutWww = host.removePrefix("www.")
    SEARCH_SOURCE_BRANDS[withoutWww]?.let { return it }
    val suffix = SEARCH_SOURCE_SUFFIXES.firstOrNull { withoutWww.endsWith(".$it") }
    val base = if (suffix != null) {
        withoutWww.removeSuffix(".$suffix")
    } else {
        withoutWww.substringBeforeLast('.', withoutWww)
    }
    return base.substringAfterLast('.').ifBlank { withoutWww }
}

private val SEARCH_SOURCE_BRANDS = mapOf(
    "weibo.com" to "微博",
    "sina.com.cn" to "新浪",
    "news.sina.com.cn" to "新浪",
    "zhihu.com" to "知乎",
    "nytimes.com" to "纽约时报",
    "bbc.com" to "bbc",
    "bbc.co.uk" to "bbc",
    "theguardian.com" to "Guardian",
    "reuters.com" to "Reuters",
    "apnews.com" to "AP",
    "wikipedia.org" to "Wikipedia",
    "youtube.com" to "YouTube",
    "x.com" to "X",
    "twitter.com" to "X",
)

private val SEARCH_SOURCE_SUFFIXES = listOf(
    "com.cn",
    "co.uk",
    "com",
    "org",
    "net",
    "edu",
    "gov",
    "io",
    "ai",
    "co",
    "cn",
    "uk",
    "de",
    "jp",
)
