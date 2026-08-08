package app.amber.document

import com.artifex.mupdf.fitz.Document
import java.io.File

object PdfParser {
    fun parserPdf(file: File, maxChars: Int = DocumentParseLimits.MAX_OUTPUT_CHARS): String {
        DocumentParseLimits.requireInputFile(file)
        val outputLimit = maxChars.coerceIn(1, DocumentParseLimits.MAX_OUTPUT_CHARS)
        val document = Document.openDocument(file.absolutePath)
        try {
            val pages = document.countPages()
            require(pages <= DocumentParseLimits.MAX_CONTAINER_ENTRIES) { "PDF contains too many pages" }
            val result = StringBuilder(minOf(outputLimit, 16_384))
            for (i in 0 until pages) {
                if (result.length >= outputLimit) break
                val page = document.loadPage(i)
                try {
                    val structuredText = page.toStructuredText()
                    try {
                        val pageText = "---Page ${i + 1}:\n${structuredText.asText()}\n"
                        result.append(pageText.take(outputLimit - result.length))
                    } finally {
                        structuredText.destroy()
                    }
                } finally {
                    page.destroy()
                }
            }
            return result.toString()
        } finally {
            document.destroy()
        }
    }
}
