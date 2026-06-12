package app.amber.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

object PdfParser {
    fun parserPdf(file: File, maxChars: Int = Int.MAX_VALUE): String {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        try {
            val pages = document.countPages()
            val result = StringBuilder()
            for (i in 0 until pages) {
                val page = document.loadPage(i)
                try {
                    val text = page.toStructuredText()
                    try {
                        result.append("---")
                        result.append("Page ${i + 1}:\n")
                        result.append(text.asText())
                        result.appendLine()
                        if (result.length > maxChars) {
                            result.setLength(maxChars)
                            result.append("\n[TRUNCATED: PDF text exceeds $maxChars characters]")
                            break
                        }
                    } finally {
                        text.destroy()
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
