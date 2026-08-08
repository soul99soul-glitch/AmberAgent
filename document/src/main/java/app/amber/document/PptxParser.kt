package app.amber.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private data class SlideContent(
    val slideNumber: Int,
    val content: String,
    val notes: String = ""
)

object PptxParser {
    fun parse(file: File, maxChars: Int = DocumentParseLimits.MAX_OUTPUT_CHARS): String {
        DocumentParseLimits.requireOfficeArchive(file)
        val outputLimit = maxChars.coerceIn(1, DocumentParseLimits.MAX_OUTPUT_CHARS)
        return try {
            ZipFile(file).use { zipFile ->
                val slides = mutableListOf<SlideContent>()

                // Find all slide XML files and sort them by number
                val slideEntries = zipFile.entries().toList()
                    .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                    .sortedBy { entry ->
                        entry.name.substringAfter("slide").substringBefore(".xml").toIntOrNull() ?: 0
                    }

                if (slideEntries.isEmpty()) {
                    return DocumentParseLimits.limitOutput("No slides found in PPTX file", outputLimit)
                }

                // Parse each slide
                var extractedChars = 0
                for ((index, entry) in slideEntries.withIndex()) {
                    if (extractedChars >= outputLimit) break
                    val slideNumber = index + 1
                    val slideContent = zipFile.getInputStream(entry).use { rawStream ->
                        val stream = DocumentParseLimits.boundedEntry(rawStream)
                        parseSlideXml(stream, outputLimit - extractedChars)
                    }

                    // Try to get notes for this slide
                    val notesEntry = zipFile.getEntry("ppt/notesSlides/notesSlide${slideNumber}.xml")
                    val notes = if (notesEntry != null) {
                        zipFile.getInputStream(notesEntry).use { rawStream ->
                            val stream = DocumentParseLimits.boundedEntry(rawStream)
                            parseNotesXml(stream, (outputLimit - extractedChars - slideContent.length).coerceAtLeast(1))
                        }
                    } else ""

                    slides.add(SlideContent(slideNumber, slideContent, notes))
                    extractedChars += slideContent.length + notes.length
                    if (extractedChars >= outputLimit) break
                }

                // Format output
                DocumentParseLimits.limitOutput(formatOutput(slides), maxChars)
            }
        } catch (e: Exception) {
            DocumentParseLimits.limitOutput("Error parsing PPTX file: ${e.message}", outputLimit)
        }
    }

    private fun formatOutput(slides: List<SlideContent>): String {
        val result = StringBuilder()

        slides.forEach { slide ->
            result.append("## Slide ${slide.slideNumber}\n\n")
            result.append(slide.content)

            if (slide.notes.isNotBlank()) {
                result.append("\n### Speaker Notes\n\n")
                result.append(slide.notes)
            }

            result.append("\n")
        }

        return result.toString().trim()
    }

    private fun parseSlideXml(inputStream: InputStream, maxChars: Int): String {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            val result = StringBuilder()

            while (parser.eventType != XmlPullParser.END_DOCUMENT && result.length < maxChars) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "sp" -> processShape(parser, result, maxChars)  // Text box/shape
                            "graphicFrame" -> processGraphicFrame(parser, result, maxChars)  // Table
                        }
                    }
                }
                parser.next()
            }

            result.toString()
        } catch (e: Exception) {
            "Error parsing slide XML: ${e.message}\n"
        }
    }

    private fun processShape(parser: XmlPullParser, result: StringBuilder, maxChars: Int) {
        val shapeStartDepth = parser.depth
        val textContent = StringBuilder()
        var hasBullet = false
        var bulletLevel = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT && result.length + textContent.length < maxChars) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "p" -> {
                            // Start of paragraph - check for bullet/numbering
                            val paragraphInfo = processParagraph(parser, textContent, maxChars - result.length)
                            hasBullet = paragraphInfo.first
                            bulletLevel = paragraphInfo.second
                            isNumbered = paragraphInfo.third
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "sp" && parser.depth == shapeStartDepth) {
                        break
                    }
                }
            }
        }

        val text = textContent.toString().trim()
        if (text.isNotBlank()) {
            DocumentParseLimits.appendLimited(result, text, maxChars)
            DocumentParseLimits.appendLimited(result, "\n\n", maxChars)
        }
    }

    private fun processParagraph(
        parser: XmlPullParser,
        result: StringBuilder,
        maxChars: Int,
    ): Triple<Boolean, Int, Boolean> {
        val paragraphStartDepth = parser.depth
        val paragraphText = StringBuilder()
        var hasBullet = false
        var bulletLevel = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT && paragraphText.length < maxChars) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "pPr" -> {
                            // Paragraph properties - check for bullets
                            val bulletInfo = extractBulletInfo(parser)
                            hasBullet = bulletInfo.first
                            bulletLevel = bulletInfo.second
                            isNumbered = bulletInfo.third
                        }

                        "r" -> {
                            // Text run
                            extractTextRun(parser, paragraphText, maxChars)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && parser.depth == paragraphStartDepth) {
                        break
                    }
                }
            }
        }

        val text = paragraphText.toString().trim()
        if (text.isNotBlank()) {
            if (hasBullet) {
                val indent = "  ".repeat(bulletLevel)
                val marker = if (isNumbered) "1. " else "- "
                DocumentParseLimits.appendLimited(result, "$indent$marker$text\n", maxChars)
            } else {
                DocumentParseLimits.appendLimited(result, "$text\n", maxChars)
            }
        }

        return Triple(hasBullet, bulletLevel, isNumbered)
    }

    private fun extractBulletInfo(parser: XmlPullParser): Triple<Boolean, Int, Boolean> {
        val pPrStartDepth = parser.depth
        var hasBullet = false
        var level = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "buChar" -> {
                            hasBullet = true
                            isNumbered = false
                        }

                        "buAutoNum" -> {
                            hasBullet = true
                            isNumbered = true
                        }

                        "lvl" -> {
                            parser.getAttributeValue(null, "val")?.let {
                                level = it.toIntOrNull() ?: 0
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "pPr" && parser.depth == pPrStartDepth) {
                        break
                    }
                }
            }
        }

        return Triple(hasBullet, level, isNumbered)
    }

    private fun extractTextRun(parser: XmlPullParser, result: StringBuilder, maxChars: Int) {
        val runStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT && result.length < maxChars) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            DocumentParseLimits.appendLimited(result, parser.text ?: "", maxChars)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "r" && parser.depth == runStartDepth) {
                        break
                    }
                }
            }
        }
    }

    private fun processGraphicFrame(parser: XmlPullParser, result: StringBuilder, maxChars: Int) {
        val frameStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT && result.length < maxChars) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tbl") {
                        processTable(parser, result, maxChars)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "graphicFrame" && parser.depth == frameStartDepth) {
                        break
                    }
                }
            }
        }
    }

    private fun processTable(parser: XmlPullParser, result: StringBuilder, maxChars: Int) {
        val tableStartDepth = parser.depth
        val rows = mutableListOf<List<String>>()

        var extractedChars = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT && extractedChars < maxChars - result.length) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tr") {
                        val cells = extractTableRow(parser, maxChars - result.length - extractedChars)
                        if (cells.isNotEmpty()) {
                            rows.add(cells)
                            extractedChars += cells.sumOf { it.length }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tbl" && parser.depth == tableStartDepth) {
                        break
                    }
                }
            }
        }

        // Convert to markdown table
        if (rows.isNotEmpty()) {
            val maxCols = rows.maxOfOrNull { it.size } ?: 0

            for ((index, row) in rows.withIndex()) {
                DocumentParseLimits.appendLimited(result, "| ", maxChars)
                for (colIndex in 0 until maxCols) {
                    val cellContent = if (colIndex < row.size) row[colIndex] else ""
                    DocumentParseLimits.appendLimited(result, "$cellContent | ", maxChars)
                }
                DocumentParseLimits.appendLimited(result, "\n", maxChars)

                // Add separator after first row (header)
                if (index == 0) {
                    DocumentParseLimits.appendLimited(result, "| ", maxChars)
                    repeat(maxCols) {
                        DocumentParseLimits.appendLimited(result, "--- | ", maxChars)
                    }
                    DocumentParseLimits.appendLimited(result, "\n", maxChars)
                }
            }
            DocumentParseLimits.appendLimited(result, "\n", maxChars)
        }
    }

    private fun extractTableRow(parser: XmlPullParser, maxChars: Int): List<String> {
        val rowStartDepth = parser.depth
        val cells = mutableListOf<String>()

        var extractedChars = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT && extractedChars < maxChars) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tc") {
                        val cellText = extractTableCell(parser, maxChars - extractedChars)
                        cells.add(cellText)
                        extractedChars += cellText.length
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tr" && parser.depth == rowStartDepth) {
                        break
                    }
                }
            }
        }

        return cells
    }

    private fun extractTableCell(parser: XmlPullParser, maxChars: Int): String {
        val cellStartDepth = parser.depth
        val result = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT && result.length < maxChars) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            if (result.isNotEmpty()) {
                                result.append(" ")
                            }
                            DocumentParseLimits.appendLimited(result, parser.text ?: "", maxChars)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tc" && parser.depth == cellStartDepth) {
                        break
                    }
                }
            }
        }

        return result.toString().trim()
    }

    private fun parseNotesXml(inputStream: InputStream, maxChars: Int): String {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            val result = StringBuilder()
            while (parser.eventType != XmlPullParser.END_DOCUMENT && result.length < maxChars) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "sp" -> extractNotesShape(parser, result, maxChars)
                        }
                    }
                }
                parser.next()
            }

            result.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractNotesShape(parser: XmlPullParser, result: StringBuilder, maxChars: Int) {
        val shapeStartDepth = parser.depth
        val shapeText = StringBuilder()
        var isBodyShape = false

        while (parser.next() != XmlPullParser.END_DOCUMENT && shapeText.length < maxChars - result.length) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "ph" -> isBodyShape = isBodyShape || parser.getAttributeValue(null, "type") == "body"
                        "t" -> {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                DocumentParseLimits.appendLimited(
                                    shapeText,
                                    parser.text ?: "",
                                    maxChars - result.length,
                                )
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "sp" && parser.depth == shapeStartDepth) {
                        break
                    }
                    if (parser.name == "p") {
                        DocumentParseLimits.appendLimited(shapeText, "\n", maxChars - result.length)
                    }
                }
            }
        }
        if (isBodyShape) {
            DocumentParseLimits.appendLimited(result, shapeText, maxChars)
        }
    }
}
