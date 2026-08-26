package app.amber.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.File
import java.util.zip.ZipFile

/** Reads slide text in the order declared by `presentation.xml`. */
object PptxParser {
    private const val DEFAULT_MAX_CHARS = 512_000
    private const val MAX_XML_BYTES = 8L * 1024 * 1024
    private const val MAX_SLIDES = 1_000

    fun parse(file: File, maxChars: Int = DEFAULT_MAX_CHARS): String = try {
        ZipFile(file).use { zip ->
            val paths = slideOrder(zip)
            if (paths.isEmpty()) return "No slides found in PPTX file"

            val output = StringBuilder()
            var readableSlides = 0
            var truncated = false
            for (path in paths.take(MAX_SLIDES)) {
                val entry = zip.getEntry(path) ?: continue
                val content = runCatching {
                    zip.getInputStream(entry).use { input ->
                        parseSlide(BoundedInputStream(input, MAX_XML_BYTES))
                    }
                }.getOrNull() ?: continue
                // Notes are optional metadata. A corrupt notes relationship or
                // XML must not discard the already-readable slide body.
                val notes = runCatching {
                    notesPath(zip, path)?.let { notePath ->
                        zip.getEntry(notePath)?.let { noteEntry ->
                            zip.getInputStream(noteEntry).use { input ->
                                parseNotes(BoundedInputStream(input, MAX_XML_BYTES))
                            }
                        }
                    }.orEmpty()
                }.getOrNull().orEmpty()
                readableSlides++
                appendSlide(output, readableSlides, content, notes)
                if (output.length > maxChars) {
                    truncated = true
                    break
                }
            }
            if (readableSlides == 0) "No readable slides in PPTX file"
            else if (truncated) limit(output.toString(), maxChars)
            else output.toString().trim()
        }
    } catch (e: Exception) {
        "Error parsing PPTX file: ${e.message}"
    }

    private fun slideOrder(zip: ZipFile): List<String> {
        val presentation = zip.getEntry("ppt/presentation.xml")
        val relationships = zip.getEntry("ppt/_rels/presentation.xml.rels")
        if (presentation != null && relationships != null) {
            val ids = zip.getInputStream(presentation).use { input ->
                readSlideRelationshipIds(BoundedInputStream(input, MAX_XML_BYTES))
            }
            val targets = zip.getInputStream(relationships).use { input ->
                readRelationships(BoundedInputStream(input, MAX_XML_BYTES))
            }
            val ordered = ids.mapNotNull { id ->
                targets[id]?.let { resolveZipPath("ppt/presentation.xml", it) }
            }.filter { it.startsWith("ppt/slides/") && it.endsWith(".xml") }
            if (ordered.isNotEmpty()) return ordered
        }
        return zip.entries().asSequence()
            .map { it.name }
            .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfter("slide").substringBefore(".xml").toIntOrNull() ?: 0 }
            .toList()
    }

    private fun readSlideRelationshipIds(input: InputStream): List<String> {
        val parser = newParser(input)
        val ids = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.localName() == "sldId") {
                parser.attribute("id")?.let(ids::add)
            }
            parser.next()
        }
        return ids
    }

    private fun notesPath(zip: ZipFile, slidePath: String): String? {
        val base = slidePath.substringBeforeLast('/')
        val name = slidePath.substringAfterLast('/')
        val relationshipsPath = "$base/_rels/$name.rels"
        val entry = zip.getEntry(relationshipsPath) ?: return null
        val target = zip.getInputStream(entry).use { input ->
            readRelationships(BoundedInputStream(input, MAX_XML_BYTES))
        }.values.firstOrNull { it.contains("notesSlide", ignoreCase = true) }
        return target?.let { resolveZipPath(slidePath, it) }
    }

    private fun readRelationships(input: InputStream): Map<String, String> {
        val parser = newParser(input)
        val result = mutableMapOf<String, String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.localName() == "Relationship") {
                val id = parser.attribute("Id")
                val target = parser.attribute("Target")
                if (id != null && target != null) result[id] = target
            }
            parser.next()
        }
        return result
    }

    private fun resolveZipPath(sourcePath: String, target: String): String {
        val cleanTarget = target.substringBefore('#').substringBefore('?')
        val combined = if (cleanTarget.startsWith('/')) {
            cleanTarget.drop(1)
        } else {
            val base = sourcePath.substringBeforeLast('/', "")
            if (base.isBlank()) cleanTarget else "$base/$cleanTarget"
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

    private fun parseSlide(input: InputStream): String {
        val parser = newParser(input)
        val result = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.localName()) {
                    "sp" -> appendShape(parser, result)
                    "graphicFrame" -> appendGraphicFrame(parser, result)
                }
            }
            parser.next()
        }
        return result.toString().trim()
    }

    private fun appendShape(parser: XmlPullParser, result: StringBuilder) {
        val shapeDepth = parser.depth
        val start = result.length
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.localName() == "p") {
                    readParagraph(parser)?.let { result.append(it).append('\n') }
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "sp" && parser.depth == shapeDepth
                ) break
            }
        }
        if (result.length > start) {
            if (result.last() != '\n') result.append('\n')
            result.append('\n')
        }
    }

    private fun readParagraph(parser: XmlPullParser): String? {
        val paragraphDepth = parser.depth
        val text = StringBuilder()
        var level = 0
        var bullet = false
        var numbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "br" -> text.append('\n')
                    "pPr" -> {
                        level = parser.attribute("lvl")?.toIntOrNull() ?: 0
                        val bulletInfo = readBulletProperties(parser)
                        bullet = bulletInfo.first
                        numbered = bulletInfo.second
                    }
                    "r" -> readTextRun(parser, text)
                    "fld" -> readField(parser, text)
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "p" && parser.depth == paragraphDepth
                ) break
            }
        }

        val content = text.toString().trim()
        if (content.isBlank()) return null
        if (!bullet) return content
        val marker = if (numbered) "1. " else "- "
        return "${"  ".repeat(level.coerceAtLeast(0))}$marker$content"
    }

    private fun readBulletProperties(parser: XmlPullParser): Pair<Boolean, Boolean> {
        val depth = parser.depth
        var bullet = false
        var numbered = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "buChar" -> {
                        bullet = true
                        numbered = false
                    }
                    "buAutoNum" -> {
                        bullet = true
                        numbered = true
                    }
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "pPr" && parser.depth == depth
                ) break
            }
        }
        return bullet to numbered
    }

    private fun readTextRun(parser: XmlPullParser, output: StringBuilder) {
        val depth = parser.depth
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "t" -> output.append(readElementText(parser))
                    "br" -> output.append('\n')
                }
                XmlPullParser.END_TAG -> if (parser.localName() == "r" && parser.depth == depth) break
            }
        }
    }

    private fun readField(parser: XmlPullParser, output: StringBuilder) {
        val depth = parser.depth
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.localName() == "t") {
                    output.append(readElementText(parser))
                }
                XmlPullParser.END_TAG -> if (parser.localName() == "fld" && parser.depth == depth) break
            }
        }
    }

    private fun appendGraphicFrame(parser: XmlPullParser, result: StringBuilder) {
        val frameDepth = parser.depth
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.localName() == "tbl") {
                    appendTable(parser, result)
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "graphicFrame" && parser.depth == frameDepth
                ) break
            }
        }
    }

    private fun appendTable(parser: XmlPullParser, result: StringBuilder) {
        val tableDepth = parser.depth
        val rows = mutableListOf<List<String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.localName() == "tr") {
                    rows += readTableRow(parser)
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "tbl" && parser.depth == tableDepth
                ) break
            }
        }
        if (rows.isEmpty()) return
        val columns = rows.maxOf { it.size }
        rows.forEachIndexed { index, row ->
            result.append("| ")
            repeat(columns) { column ->
                result.append(row.getOrElse(column) { "" }).append(" | ")
            }
            result.append('\n')
            if (index == 0) {
                result.append("| ")
                repeat(columns) { result.append("--- | ") }
                result.append('\n')
            }
        }
        result.append('\n')
    }

    private fun readTableRow(parser: XmlPullParser): List<String> {
        val rowDepth = parser.depth
        val cells = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.localName() == "tc") {
                    cells += readTableCell(parser)
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "tr" && parser.depth == rowDepth
                ) break
            }
        }
        return cells
    }

    private fun readTableCell(parser: XmlPullParser): String {
        val cellDepth = parser.depth
        val text = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "t" -> {
                        if (text.isNotEmpty() && text.last() != '\n') text.append(' ')
                        text.append(readElementText(parser))
                    }
                    "br" -> text.append('\n')
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "tc" && parser.depth == cellDepth
                ) break
            }
        }
        return text.toString().trim()
    }

    private fun parseNotes(input: InputStream): String {
        val parser = newParser(input)
        val result = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.localName() == "sp") {
                val text = readNotesShape(parser)
                if (text != null) {
                    if (result.isNotEmpty()) result.append('\n')
                    result.append(text)
                }
            }
            parser.next()
        }
        return result.toString().trim()
    }

    private fun readNotesShape(parser: XmlPullParser): String? {
        val shapeDepth = parser.depth
        var body = false
        val text = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "ph" -> body = parser.attribute("type") == "body"
                    "t" -> text.append(readElementText(parser))
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "sp" && parser.depth == shapeDepth
                ) break
            }
        }
        return text.toString().trim().takeIf { body && it.isNotBlank() }
    }

    private fun appendSlide(
        output: StringBuilder,
        number: Int,
        content: String,
        notes: String,
    ) {
        output.append("## Slide ").append(number).append("\n\n")
        if (content.isNotBlank()) output.append(content)
        if (notes.isNotBlank()) {
            output.append("\n### Speaker Notes\n\n").append(notes)
        }
        output.append("\n")
    }

    private fun newParser(input: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
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

    private fun readElementText(parser: XmlPullParser): String {
        val depth = parser.depth
        val text = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(parser.text.orEmpty())
                XmlPullParser.END_TAG -> if (parser.depth == depth) break
            }
        }
        return text.toString()
    }

    private fun limit(text: String, maxChars: Int): String {
        val trimmed = text.trim()
        val limit = maxChars.coerceAtLeast(0)
        return trimmed.take(limit) + "\n[TRUNCATED: PPTX text exceeds $maxChars characters]"
    }
}
