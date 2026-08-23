package app.amber.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

private data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String
)

object EpubParser {
    fun parse(file: File, maxChars: Int = DocumentParseLimits.MAX_OUTPUT_CHARS): String {
        DocumentParseLimits.requireOfficeArchive(file)
        val outputLimit = maxChars.coerceIn(1, DocumentParseLimits.MAX_OUTPUT_CHARS)
        return try {
            ZipFile(file).use { zip ->
                val opfPath = findOpfPath(zip)
                    ?: return DocumentParseLimits.limitOutput("Unable to find OPF file in EPUB", outputLimit)
                val opfDir = opfPath.substringBeforeLast('/', "")

                val opfEntry = zip.getEntry(opfPath)
                    ?: return DocumentParseLimits.limitOutput("Unable to read OPF file in EPUB", outputLimit)
                val (manifest, spine) = zip.getInputStream(opfEntry).use {
                    parseOpf(DocumentParseLimits.boundedEntry(it))
                }

                val result = StringBuilder()
                for (itemId in spine) {
                    if (result.length >= outputLimit) break
                    val item = manifest[itemId] ?: continue
                    if (!item.mediaType.contains("html")) continue

                    val itemPath = if (opfDir.isEmpty()) item.href else "$opfDir/${item.href}"
                    val entry = zip.getEntry(itemPath) ?: continue
                    val content = zip.getInputStream(entry).use {
                        parseXhtml(
                            DocumentParseLimits.boundedEntry(it),
                            maxChars = outputLimit - result.length,
                        )
                    }
                    if (content.isNotBlank()) {
                        val remaining = outputLimit - result.length
                        if (remaining <= 0) break
                        result.append(content.take(remaining))
                        if (result.length < outputLimit) {
                            DocumentParseLimits.appendLimited(result, "\n\n", outputLimit)
                        }
                    }
                }

                DocumentParseLimits.limitOutput(
                    result.toString().trim().ifEmpty { "No readable content found in EPUB file" },
                    maxChars,
                )
            }
        } catch (e: Exception) {
            DocumentParseLimits.limitOutput("Error parsing EPUB file: ${e.message}", outputLimit)
        }
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
        return zip.getInputStream(containerEntry).use { rawStream ->
            val stream = DocumentParseLimits.boundedEntry(rawStream)
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(stream, "UTF-8")

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return@use parser.getAttributeValue(null, "full-path")
                }
                parser.next()
            }
            null
        }
    }

    private fun parseOpf(inputStream: InputStream): Pair<Map<String, ManifestItem>, List<String>> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        if (id.isNotEmpty()) {
                            require(manifest.size < DocumentParseLimits.MAX_CONTAINER_ENTRIES) {
                                "EPUB manifest contains too many items"
                            }
                            manifest[id] = ManifestItem(id, href, mediaType)
                        }
                    }

                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref") ?: ""
                        if (idref.isNotEmpty()) {
                            require(spine.size < DocumentParseLimits.MAX_CONTAINER_ENTRIES) {
                                "EPUB spine contains too many items"
                            }
                            spine.add(idref)
                        }
                    }
                }
            }
            parser.next()
        }

        return manifest to spine
    }

    private fun parseXhtml(inputStream: InputStream, maxChars: Int): String {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(inputStream, "UTF-8")

            val result = StringBuilder()
            val tagStack = ArrayDeque<String>()
            var inBody = false
            var listCounter = 0

            while (parser.eventType != XmlPullParser.END_DOCUMENT && result.length < maxChars) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase()
                        tagStack.addLast(tag)
                        require(tagStack.size <= DocumentParseLimits.MAX_CONTAINER_ENTRIES) {
                            "EPUB XHTML nesting is too deep"
                        }

                        when (tag) {
                            "body" -> inBody = true
                            "ol" -> listCounter = 0
                            "li" -> {
                                val parentTag = tagStack.dropLast(1).lastOrNull()
                                if (parentTag == "ol") {
                                    listCounter++
                                    DocumentParseLimits.appendLimited(result, "$listCounter. ", maxChars)
                                } else {
                                    DocumentParseLimits.appendLimited(result, "- ", maxChars)
                                }
                            }

                            "br" -> DocumentParseLimits.appendLimited(result, "\n", maxChars)
                            "img" -> {
                                if (inBody) {
                                    val alt = parser.getAttributeValue(null, "alt")
                                    if (!alt.isNullOrBlank()) {
                                        DocumentParseLimits.appendLimited(result, "[image: $alt]", maxChars)
                                    }
                                }
                            }

                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                if (inBody) {
                                    val level = tag[1].digitToInt()
                                    DocumentParseLimits.appendLimited(result, "${"#".repeat(level)} ", maxChars)
                                }
                            }

                            "strong", "b" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "**", maxChars)
                            }

                            "em", "i" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "*", maxChars)
                            }

                            "hr" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "\n---\n", maxChars)
                            }

                            "blockquote" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "> ", maxChars)
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (inBody) {
                            val text = parser.text
                                ?.replace('\n', ' ')
                                ?.replace('\r', ' ')
                                ?.replace("\\s+".toRegex(), " ")
                            if (!text.isNullOrBlank()) {
                                DocumentParseLimits.appendLimited(result, text, maxChars)
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase()
                        if (tagStack.isNotEmpty()) tagStack.removeLast()

                        when (tag) {
                            "body" -> inBody = false
                            "p", "div" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "\n\n", maxChars)
                            }

                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "\n\n", maxChars)
                            }

                            "li" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "\n", maxChars)
                            }

                            "ul", "ol" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "\n", maxChars)
                            }

                            "br" -> {}
                            "strong", "b" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "**", maxChars)
                            }

                            "em", "i" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "*", maxChars)
                            }

                            "blockquote" -> {
                                if (inBody) DocumentParseLimits.appendLimited(result, "\n", maxChars)
                            }
                        }
                    }
                }
                try {
                    parser.next()
                } catch (_: Exception) {
                    break
                }
            }

            result.toString()
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        } catch (e: Exception) {
            ""
        }
    }
}
