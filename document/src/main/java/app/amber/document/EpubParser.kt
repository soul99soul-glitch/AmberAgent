package app.amber.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

private data class EpubManifestItem(
    val href: String,
    val mediaType: String,
)

/** Reads EPUB content in the order declared by the package spine. */
object EpubParser {
    private const val DEFAULT_MAX_CHARS = 512_000
    private const val MAX_XML_BYTES = 8L * 1024 * 1024
    private const val MAX_SPINE_ENTRIES = 2_000

    fun parse(file: File, maxChars: Int = DEFAULT_MAX_CHARS): String = try {
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip) ?: return "Unable to find OPF file in EPUB"
            val opfEntry = zip.getEntry(opfPath) ?: return "Unable to read OPF file in EPUB"
            val (manifest, spine) = zip.getInputStream(opfEntry).use { input ->
                parsePackage(BoundedInputStream(input, MAX_XML_BYTES))
            }
            val output = StringBuilder()
            var truncated = false
            for (id in spine.take(MAX_SPINE_ENTRIES)) {
                val item = manifest[id] ?: continue
                if (!item.mediaType.contains("html", ignoreCase = true)) continue
                val path = resolveZipPath(opfPath, item.href)
                val entry = zip.getEntry(path) ?: continue
                val content = zip.getInputStream(entry).use { input ->
                    parseXhtml(BoundedInputStream(input, MAX_XML_BYTES))
                }
                if (content.isNotBlank()) {
                    output.append(content).append("\n\n")
                    if (output.length > maxChars) {
                        truncated = true
                        break
                    }
                }
            }
            (if (truncated) limit(output.toString(), maxChars) else output.toString().trim())
                .ifBlank { "No readable content found in EPUB file" }
        }
    } catch (e: Exception) {
        "Error parsing EPUB file: ${e.message}"
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val entry = zip.getEntry("META-INF/container.xml") ?: return null
        return zip.getInputStream(entry).use { input ->
            val parser = newParser(BoundedInputStream(input, MAX_XML_BYTES))
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.localName() == "rootfile") {
                    return@use parser.attribute("full-path")
                }
                parser.next()
            }
            null
        }
    }

    private fun parsePackage(input: InputStream): Pair<Map<String, EpubManifestItem>, List<String>> {
        val parser = newParser(input)
        val manifest = mutableMapOf<String, EpubManifestItem>()
        val spine = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.localName()) {
                    "item" -> {
                        val id = parser.attribute("id")
                        val href = parser.attribute("href")
                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                            manifest[id] = EpubManifestItem(href, parser.attribute("media-type").orEmpty())
                        }
                    }
                    "itemref" -> parser.attribute("idref")?.let(spine::add)
                }
            }
            parser.next()
        }
        return manifest to spine
    }

    private fun parseXhtml(input: InputStream): String = try {
        val parser = newParser(input, namespaceAware = false)
        val result = StringBuilder()
        val lists = ArrayDeque<Pair<String, Int>>()
        var inBody = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.localName().lowercase()
                    when (tag) {
                        "body" -> inBody = true
                        "ol", "ul" -> if (inBody) lists.addLast(tag to 0)
                        "li" -> if (inBody) {
                            val list = lists.lastOrNull()
                            if (list?.first == "ol") {
                                val next = list.second + 1
                                lists.removeLast()
                                lists.addLast(list.first to next)
                                result.append(next).append(". ")
                            } else {
                                result.append("- ")
                            }
                        }
                        "br" -> if (inBody) result.append('\n')
                        "img" -> if (inBody) {
                            parser.attribute("alt")?.takeIf { it.isNotBlank() }
                                ?.let { result.append("[image: ").append(it).append(']') }
                        }
                        "h1", "h2", "h3", "h4", "h5", "h6" -> if (inBody) {
                            result.append("#".repeat(tag[1].digitToInt())).append(' ')
                        }
                        "strong", "b" -> if (inBody) result.append("**")
                        "em", "i" -> if (inBody) result.append('*')
                        "hr" -> if (inBody) result.append("\n---\n")
                        "blockquote" -> if (inBody) result.append("> ")
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inBody) {
                    val text = parser.text.orEmpty().replace(Regex("\\s+"), " ")
                    if (text.isNotBlank()) result.append(text)
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.localName().lowercase()
                    when (tag) {
                        "body" -> inBody = false
                        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            if (inBody) result.append("\n\n")
                        }
                        "li" -> if (inBody) result.append('\n')
                        "ol", "ul" -> if (inBody && lists.isNotEmpty()) {
                            lists.removeLast()
                            result.append('\n')
                        }
                        "strong", "b" -> if (inBody) result.append("**")
                        "em", "i" -> if (inBody) result.append('*')
                        "blockquote" -> if (inBody) result.append('\n')
                    }
                }
            }
            parser.next()
        }

        result.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    } catch (_: Exception) {
        ""
    }

    private fun resolveZipPath(sourcePath: String, target: String): String {
        val raw = target.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URI(raw).path }.getOrNull() ?: raw
        val combined = if (decoded.startsWith('/')) {
            decoded.drop(1)
        } else {
            val base = sourcePath.substringBeforeLast('/', "")
            if (base.isBlank()) decoded else "$base/$decoded"
        }
        val parts = ArrayDeque<String>()
        combined.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun newParser(input: InputStream, namespaceAware: Boolean = true): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = namespaceAware
        }
        return factory.newPullParser().also {
            it.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            it.setInput(input, "UTF-8")
        }
    }

    private fun XmlPullParser.localName(): String = name.substringAfterLast(':')

    private fun XmlPullParser.attribute(name: String): String? = (0 until attributeCount)
        .asSequence()
        .firstOrNull { getAttributeName(it).substringAfterLast(':') == name }
        ?.let { getAttributeValue(it) }

    private fun limit(text: String, maxChars: Int): String {
        val trimmed = text.trim()
        val limit = maxChars.coerceAtLeast(0)
        return trimmed.take(limit) + "\n[TRUNCATED: EPUB text exceeds $maxChars characters]"
    }
}
