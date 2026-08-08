package app.amber.document

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentParseLimitsTest {

    @Test
    fun oversizedInputIsRejectedBeforeParserAllocation() {
        val file = File.createTempFile("document-limit", ".pdf")
        try {
            RandomAccessFile(file, "rw").use { it.setLength(DocumentParseLimits.MAX_INPUT_BYTES + 1L) }
            assertTrue(
                runCatching { DocumentParseLimits.requireInputFile(file) }
                    .exceptionOrNull() is IllegalArgumentException,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun docxOutputNeverExceedsWorkspaceReadContract() {
        val file = File.createTempFile("document-output-limit", ".docx")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                val text = "x".repeat(DocumentParseLimits.MAX_OUTPUT_CHARS + 10_000)
                zip.write(
                    """<w:document xmlns:w="urn:test"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"""
                        .toByteArray(),
                )
                zip.closeEntry()
            }

            val parsed = DocxParser.parse(file)
            assertEquals(DocumentParseLimits.MAX_OUTPUT_CHARS, parsed.length)
        } finally {
            file.delete()
        }
    }

    @Test
    fun textReadUsesSameOutputCeiling() {
        val file = File.createTempFile("document-text-limit", ".txt")
        try {
            file.writeText("y".repeat(DocumentParseLimits.MAX_OUTPUT_CHARS + 1))
            assertEquals(DocumentParseLimits.MAX_OUTPUT_CHARS, DocumentParseLimits.readText(file).length)
        } finally {
            file.delete()
        }
    }

    @Test
    fun pptxHonorsCallerOutputLimitDuringExtraction() {
        val file = File.createTempFile("document-pptx-limit", ".pptx")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
                zip.write(
                    """<p:sld xmlns:p="urn:p" xmlns:a="urn:a"><p:sp><a:p><a:r><a:t>${"p".repeat(1_000)}</a:t></a:r></a:p></p:sp></p:sld>"""
                        .toByteArray(),
                )
                zip.closeEntry()
            }

            assertTrue(PptxParser.parse(file, maxChars = 32).length <= 32)
        } finally {
            file.delete()
        }
    }

    @Test
    fun epubHonorsCallerOutputLimitDuringExtraction() {
        val file = File.createTempFile("document-epub-limit", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.writeEntry(
                    "META-INF/container.xml",
                    """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
                )
                zip.writeEntry(
                    "content.opf",
                    """<package><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
                )
                zip.writeEntry("chapter.xhtml", "<html><body><p>${"e".repeat(1_000)}</p></body></html>")
            }

            assertTrue(EpubParser.parse(file, maxChars = 32).length <= 32)
        } finally {
            file.delete()
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }
}
