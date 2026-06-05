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
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import java.net.URI
import java.util.Locale

// Covers preprocessed link destinations that contain a rewritten bare URL fragment: `](url)`.
private val RewrittenLinkDestinationRegex = Regex("""\]\((https?://[^)]+)\)""")

internal data class SearchImageRef(
    val url: String,
    val caption: String? = null,
    val sourceId: String? = null,
    /** Display/debug only; matching uses imagesByHost and does not read this field. */
    val host: String? = null,
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

internal class SearchImageUrlRegistry(
    private val urls: Set<String>,
) {
    fun contains(url: String): Boolean {
        val normalized = normalizeSearchImageUrl(url) ?: return false
        return normalized in urls
    }
}

internal data class SearchPresentation(
    val images: List<SearchImageRef>,
    val sources: SearchSourcesRegistry,
    val imageUrls: SearchImageUrlRegistry,
    val imagesById: Map<String, List<SearchImageRef>> = emptyMap(),
    val imagesByCanonicalUrl: Map<String, List<SearchImageRef>> = emptyMap(),
    val imagesByHost: Map<String, List<SearchImageRef>> = emptyMap(),
)

internal val EmptySearchPresentation = SearchPresentation(
    images = emptyList(),
    sources = SearchSourcesRegistry(emptyMap()),
    imageUrls = SearchImageUrlRegistry(emptySet()),
)

internal val LocalSearchSources = compositionLocalOf<SearchSourcesRegistry?> { null }
internal val LocalSearchImageUrls = compositionLocalOf<SearchImageUrlRegistry?> { null }

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
    val imagesById = linkedMapOf<String, MutableList<SearchImageRef>>()
    val imagesByCanonicalUrl = linkedMapOf<String, MutableList<SearchImageRef>>()
    val imagesByHost = linkedMapOf<String, MutableList<SearchImageRef>>()

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
                val canonicalUrl = canonicalizeSearchUrl(url)
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
                    val imageUrl = normalizeSearchImageUrl(imageElement.jsonPrimitive.content)
                        ?: return@forEach
                    if (seenImages.add(imageUrl)) {
                        imageRefs += SearchImageRef(
                            url = imageUrl,
                            caption = searchImageCaption(title = title, sourceName = source.name),
                            sourceId = source.id,
                            host = host,
                        ).also { image ->
                            source.id?.let { id -> imagesById.addImage(id, image) }
                            canonicalUrl?.let { canonical -> imagesByCanonicalUrl.addImage(canonical, image) }
                            imagesByHost.addImage(host, image)
                        }
                    }
                }
            }
        }

    if (sourceRefs.isEmpty() && imageRefs.isEmpty()) return EmptySearchPresentation
    return SearchPresentation(
        images = imageRefs,
        sources = SearchSourcesRegistry(sourceRefs),
        imageUrls = SearchImageUrlRegistry(seenImages),
        imagesById = imagesById.freezeImageIndex(),
        imagesByCanonicalUrl = imagesByCanonicalUrl.freezeImageIndex(),
        imagesByHost = imagesByHost.freezeImageIndex(),
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

internal fun normalizeSearchImageUrl(url: String): String? {
    val clean = url.trim()
    return when {
        clean.startsWith("http://") || clean.startsWith("https://") -> clean
        clean.startsWith("//") -> "https:$clean"
        else -> null
    }
}

internal fun canonicalizeSearchUrl(url: String): String? {
    val clean = url.trim()
    if (clean.isBlank()) return null
    val candidate = when {
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("http://") || clean.startsWith("https://") -> clean
        else -> return null
    }
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val host = uri.host
        ?.lowercase(Locale.ROOT)
        ?.removePrefix("www.")
        ?: return null
    val rawPath = uri.rawPath.orEmpty().trimEnd('/')
    val path = rawPath.ifBlank { "/" }
    val query = uri.rawQuery
        ?.split('&')
        ?.filterNot { it.substringBefore('=').lowercase(Locale.ROOT).startsWith("utm_") }
        ?.joinToString("&")
        .orEmpty()
    return buildString {
        append(host)
        append(path)
        if (query.isNotBlank()) {
            append('?')
            append(query)
        }
    }
}

internal sealed interface SearchBlockRef {
    data class Citation(val id: String) : SearchBlockRef
    data class Link(val url: String) : SearchBlockRef
}

internal fun extractSearchBlockReferences(blockNode: ASTNode, content: String): List<SearchBlockRef> {
    val refs = mutableListOf<SearchBlockRef>()
    blockNode.collectSearchBlockReferences(content, refs)
    return refs
}

internal class SearchBlockImageAnchorResolver(
    private val presentation: SearchPresentation,
    private val perBlockCap: Int = 2,
) {
    private val used = linkedSetOf<String>()

    fun resolveBlock(blockNode: ASTNode, content: String): List<SearchImageRef> {
        val images = mutableListOf<SearchImageRef>()
        extractSearchBlockReferences(blockNode, content).forEach { ref ->
            if (images.size >= perBlockCap) return@forEach
            val candidates = when (ref) {
                is SearchBlockRef.Citation -> presentation.imagesById[ref.id].orEmpty()
                is SearchBlockRef.Link -> {
                    val byUrl = canonicalizeSearchUrl(ref.url)
                        ?.let { presentation.imagesByCanonicalUrl[it] }
                        .orEmpty()
                    byUrl.ifEmpty {
                        normalizeSearchSourceHost(ref.url)
                            ?.let { presentation.imagesByHost[it] }
                            .orEmpty()
                    }
                }
            }
            candidates.firstOrNull { it.url !in used }?.let { image ->
                used += image.url
                images += image
            }
        }
        return images
    }

    fun orphans(): List<SearchImageRef> {
        return presentation.images.filter { it.url !in used }
    }
}

private fun ASTNode.collectSearchBlockReferences(content: String, refs: MutableList<SearchBlockRef>) {
    when (type) {
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.IMAGE -> return
        MarkdownElementTypes.INLINE_LINK -> {
            val linkText = findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)
                ?.getTextInNode(content)
                ?.toString()
                ?.trim { it == '[' || it == ']' }
                .orEmpty()
            val linkDest = findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)
                ?.getTextInNode(content)
                ?.toString()
                ?.cleanSearchLinkDestination()
                .orEmpty()
            if (linkDest.isNotBlank()) {
                if (linkText.startsWith("citation,") && linkDest.length == 6) {
                    refs += SearchBlockRef.Citation(linkDest)
                } else {
                    refs += SearchBlockRef.Link(linkDest)
                }
            }
            return
        }
    }
    children.forEach { child -> child.collectSearchBlockReferences(content, refs) }
}

private fun String.cleanSearchLinkDestination(): String {
    val clean = trim().replace("&amp;", "&")
    return RewrittenLinkDestinationRegex.find(clean)
        ?.groupValues
        ?.getOrNull(1)
        ?: clean
}

private fun ASTNode.findChildOfTypeRecursive(vararg types: IElementType): ASTNode? {
    if (type in types) return this
    children.forEach { child ->
        val result = child.findChildOfTypeRecursive(*types)
        if (result != null) return result
    }
    return null
}

private fun MutableMap<String, MutableList<SearchImageRef>>.addImage(
    key: String,
    image: SearchImageRef,
) {
    getOrPut(key) { mutableListOf() }.add(image)
}

private fun Map<String, List<SearchImageRef>>.distinctImageUrls(): Map<String, List<SearchImageRef>> {
    return mapValues { (_, images) -> images.distinctBy { it.url } }
}

private fun Map<String, MutableList<SearchImageRef>>.freezeImageIndex(): Map<String, List<SearchImageRef>> {
    return mapValues { (_, images) -> images.toList() }.distinctImageUrls()
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
