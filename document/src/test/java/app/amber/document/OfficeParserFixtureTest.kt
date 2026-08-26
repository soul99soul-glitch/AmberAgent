package app.amber.document

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfficeParserFixtureTest {
    @Test
    fun docxReadsParagraphListAndTable() {
        val file = zipFixture(
            "word/numbering.xml" to """
                <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl></w:abstractNum>
                  <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
                </w:numbering>
            """.trimIndent(),
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                  <w:p><w:r><w:t>Paragraph</w:t></w:r></w:p>
                  <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Item</w:t></w:r></w:p>
                  <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Head</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                </w:body></w:document>
            """.trimIndent(),
        )

        val text = DocxParser.parse(file)

        assertTrue(text.contains("Paragraph"))
        assertTrue(text.contains("- Item"))
        assertTrue(text.contains("| Head | Value |"))
    }

    @Test
    fun pptxUsesPresentationSlideOrderAndReadsTable() {
        val file = zipFixture(
            "ppt/presentation.xml" to """
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <p:sldIdLst><p:sldId r:id="rId1"/><p:sldId r:id="rId2"/></p:sldIdLst>
                </p:presentation>
            """.trimIndent(),
            "ppt/_rels/presentation.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="slides/slide2.xml" Type="slide"/>
                  <Relationship Id="rId2" Target="slides/slide1.xml" Type="slide"/>
                </Relationships>
            """.trimIndent(),
            "ppt/slides/slide1.xml" to """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>First</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>
            """.trimIndent(),
            "ppt/slides/slide2.xml" to """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Second</a:t></a:r><a:br/><a:r><a:t>Line</a:t></a:r></a:p></p:txBody></p:sp><p:graphicFrame><a:graphic><a:graphicData><a:tbl><a:tr><a:tc><a:txBody><a:p><a:r><a:t>Cell</a:t></a:r><a:br/><a:r><a:t>After</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame></p:spTree></p:cSld></p:sld>
            """.trimIndent(),
            "ppt/slides/_rels/slide2.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdNotes" Target="../notesSlides/notesSlide2.xml" Type="notesSlide"/></Relationships>
            """.trimIndent(),
            "ppt/notesSlides/notesSlide2.xml" to """
                <p:notes xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:sp>
            """.trimIndent(),
        )

        val text = PptxParser.parse(file)

        assertTrue(text.indexOf("Second") < text.indexOf("First"))
        assertTrue(text, text.contains("| Cell\nAfter |"))
        assertTrue(text.contains("Second\nLine"))
        assertTrue(text.contains("Cell\nAfter"))
        assertTrue(text.contains("Second"))
        assertTrue(!text.contains("Speaker Notes"))
        assertTrue(PptxParser.parse(file, maxChars = 30).contains("[TRUNCATED: PPTX text exceeds 30 characters]"))
    }

    @Test
    fun pptxReportsWhenDeclaredSlidesAreMissing() {
        val file = zipFixture(
            "ppt/presentation.xml" to """
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId r:id="rId1"/></p:sldIdLst></p:presentation>
            """.trimIndent(),
            "ppt/_rels/presentation.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="slides/missing.xml" Type="slide"/></Relationships>
            """.trimIndent(),
        )

        assertEquals("No readable slides in PPTX file", PptxParser.parse(file))
    }

    @Test
    fun epubUsesSpineOrder() {
        val file = zipFixture(
            "META-INF/container.xml" to """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>
            """.trimIndent(),
            "OPS/package.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf"><manifest>
                  <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>
            """.trimIndent(),
            "OPS/a.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Alpha</p></body></html>",
            "OPS/b.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Beta</p></body></html>",
        )

        val text = EpubParser.parse(file)

        assertTrue(text.indexOf("Beta") < text.indexOf("Alpha"))
        val truncated = EpubParser.parse(file, maxChars = 5)
        assertTrue(truncated, truncated.contains("[TRUNCATED: EPUB text exceeds 5 characters]"))
    }

    private fun zipFixture(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("office-parser-fixture-", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }
}
