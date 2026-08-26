package app.amber.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

private data class DocxParagraphProperties(
    val list: DocxListProperties?,
    val headingLevel: Int,
)

private data class DocxListProperties(
    val numId: String,
    val level: Int,
    val numbered: Boolean,
)

/** Reads the small, public OOXML subset needed for inline document context. */
object DocxParser {
    private const val DEFAULT_MAX_CHARS = 512_000
    private const val MAX_XML_BYTES = 32L * 1024 * 1024

    fun parse(file: File, maxChars: Int = DEFAULT_MAX_CHARS): String = try {
        ZipFile(file).use { zip ->
            val document = zip.getEntry("word/document.xml")
                ?: return "Unable to find document content in DOCX file"
            val numbering = zip.getEntry("word/numbering.xml")?.let { entry ->
                zip.getInputStream(entry).use { input ->
                    readNumbering(BoundedInputStream(input, MAX_XML_BYTES))
                }
            }.orEmpty()
            zip.getInputStream(document).use { input ->
                parseDocumentXml(BoundedInputStream(input, MAX_XML_BYTES), maxChars, numbering)
            }
        }
    } catch (e: Exception) {
        "Error parsing DOCX file: ${e.message}"
    }

    private fun parseDocumentXml(
        input: InputStream,
        maxChars: Int,
        numbering: Map<String, Map<Int, String>>,
    ): String = try {
        val parser = newParser(input)
        val output = StringBuilder()
        val counters = mutableMapOf<String, Int>()
        var bodyDepth = -1

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "body" -> bodyDepth = parser.depth
                    "p" -> if (bodyDepth >= 0 && parser.depth == bodyDepth + 1) {
                        appendParagraph(parser, output, numbering, counters)
                    }
                    "tbl" -> if (bodyDepth >= 0 && parser.depth == bodyDepth + 1) {
                        appendTable(parser, output)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.localName() == "body") bodyDepth = -1
            }
            parser.next()
        }

        limit(output.toString(), maxChars, "DOCX")
    } catch (e: Exception) {
        "Error parsing document XML: ${e.message}"
    }

    private fun appendParagraph(
        parser: XmlPullParser,
        output: StringBuilder,
        numbering: Map<String, Map<Int, String>>,
        counters: MutableMap<String, Int>,
    ) {
        val paragraphDepth = parser.depth
        val text = StringBuilder()
        var properties = DocxParagraphProperties(null, 0)

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "pPr" -> properties = readParagraphProperties(parser, numbering)
                    "r" -> readRun(parser, text)
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "p" && parser.depth == paragraphDepth
                ) break
            }
        }

        val content = text.toString().trim()
        if (content.isBlank()) return

        val list = properties.list
        if (list != null) {
            val indent = "  ".repeat(list.level.coerceAtLeast(0))
            val marker = if (list.numbered) {
                val key = "${list.numId}:${list.level}"
                val next = (counters[key] ?: 0) + 1
                counters[key] = next
                "$next. "
            } else {
                "- "
            }
            output.append(indent).append(marker).append(content).append('\n')
        } else if (properties.headingLevel > 0) {
            output.append("#".repeat(properties.headingLevel)).append(' ')
                .append(content).append("\n\n")
        } else {
            output.append(content).append("\n\n")
        }
    }

    private fun readParagraphProperties(
        parser: XmlPullParser,
        numbering: Map<String, Map<Int, String>>,
    ): DocxParagraphProperties {
        val depth = parser.depth
        var style = ""
        var numId = ""
        var level = 0

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "pStyle" -> style = parser.attribute("val").orEmpty()
                    "ilvl" -> level = parser.attribute("val")?.toIntOrNull() ?: 0
                    "numId" -> numId = parser.attribute("val").orEmpty()
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "pPr" && parser.depth == depth
                ) break
            }
        }

        val styleLower = style.lowercase()
        val headingLevel = if (styleLower.startsWith("heading")) {
            styleLower.lastOrNull()?.digitToIntOrNull() ?: 1
        } else {
            0
        }
        val list = when {
            numId.isNotBlank() -> DocxListProperties(
                numId = numId,
                level = level,
                numbered = numbering[numId]?.get(level) != "bullet",
            )
            styleLower.contains("listbullet") -> DocxListProperties("style", level, false)
            styleLower.contains("listnumber") -> DocxListProperties("style", level, true)
            else -> null
        }
        return DocxParagraphProperties(list, headingLevel)
    }

    private fun readRun(parser: XmlPullParser, output: StringBuilder) {
        val depth = parser.depth
        var bold = false
        var italic = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "rPr" -> {
                        val formatting = readRunProperties(parser)
                        bold = formatting.first
                        italic = formatting.second
                    }
                    "t", "instrText" -> {
                        val value = readElementText(parser)
                        output.append(formatRun(value, bold, italic))
                    }
                    "tab" -> output.append('\t')
                    "br", "cr" -> output.append('\n')
                    "noBreakHyphen" -> output.append('-')
                }
                XmlPullParser.END_TAG -> if (parser.localName() == "r" && parser.depth == depth) break
            }
        }
    }

    private fun readRunProperties(parser: XmlPullParser): Pair<Boolean, Boolean> {
        val depth = parser.depth
        var bold = false
        var italic = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "b" -> bold = parser.attribute("val") !in setOf("0", "false", "off")
                    "i" -> italic = parser.attribute("val") !in setOf("0", "false", "off")
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "rPr" && parser.depth == depth
                ) break
            }
        }
        return bold to italic
    }

    private fun appendTable(parser: XmlPullParser, output: StringBuilder) {
        val tableDepth = parser.depth
        val rows = mutableListOf<List<String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.localName() == "tr") {
                    readTableRow(parser)?.let(rows::add)
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "tbl" && parser.depth == tableDepth
                ) break
            }
        }
        if (rows.isEmpty()) return
        val columns = rows.maxOf { it.size }
        rows.forEachIndexed { index, row ->
            output.append("| ")
            repeat(columns) { column ->
                output.append(row.getOrElse(column) { "" }).append(" | ")
            }
            output.append('\n')
            if (index == 0) {
                output.append("| ")
                repeat(columns) { output.append("--- | ") }
                output.append('\n')
            }
        }
        output.append('\n')
    }

    private fun readTableRow(parser: XmlPullParser): List<String>? {
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
        return cells.takeIf { it.isNotEmpty() }
    }

    private fun readTableCell(parser: XmlPullParser): String {
        val cellDepth = parser.depth
        val paragraphs = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.localName() == "p") {
                    paragraphs += readCellParagraph(parser)
                }
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "tc" && parser.depth == cellDepth
                ) break
            }
        }
        return paragraphs.filter { it.isNotBlank() }.joinToString(" ") { it.trim() }
    }

    private fun readCellParagraph(parser: XmlPullParser): String {
        val paragraphDepth = parser.depth
        val text = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.localName() == "r") readRun(parser, text)
                XmlPullParser.END_TAG -> if (
                    parser.localName() == "p" && parser.depth == paragraphDepth
                ) break
            }
        }
        return text.toString().trim()
    }

    private fun readNumbering(input: InputStream): Map<String, Map<Int, String>> {
        val parser = newParser(input)
        val abstractFormats = mutableMapOf<String, MutableMap<Int, String>>()
        val numToAbstract = mutableMapOf<String, String>()
        var abstractId: String? = null
        var level = 0
        var numId: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.localName()) {
                    "abstractNum" -> abstractId = parser.attribute("abstractNumId")
                    "lvl" -> level = parser.attribute("ilvl")?.toIntOrNull() ?: 0
                    "numFmt" -> {
                        val id = abstractId
                        if (id != null) {
                            abstractFormats.getOrPut(id) { mutableMapOf() }[level] =
                                parser.attribute("val").orEmpty()
                        }
                    }
                    "num" -> numId = parser.attribute("numId")
                    "abstractNumId" -> {
                        val id = numId
                        val abstractNum = parser.attribute("val")
                        if (id != null && abstractNum != null) numToAbstract[id] = abstractNum
                    }
                }
            }
            parser.next()
        }
        return numToAbstract.mapValues { (_, abstractNum) -> abstractFormats[abstractNum].orEmpty() }
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

    private fun formatRun(text: String, bold: Boolean, italic: Boolean): String = when {
        bold && italic -> "***$text***"
        bold -> "**$text**"
        italic -> "*$text*"
        else -> text
    }

    private fun limit(text: String, maxChars: Int, type: String): String {
        val trimmed = text.trim()
        val limit = maxChars.coerceAtLeast(0)
        return if (trimmed.length <= limit) trimmed
        else trimmed.take(limit) + "\n[TRUNCATED: $type text exceeds $maxChars characters]"
    }
}
