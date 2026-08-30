package app.amber.feature.novelworkspace

import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceBookExportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Two front-matter chapters (one with <>& in title/body) plus a bare chapter and a stray side branch. */
    private fun writeBook(directory: File) {
        val store = NovelWorkspaceStore(directory)
        store.write(
            NovelWorkspacePaths.MANIFEST,
            NovelWorkspaceManifestRenderer.render(
                exportedAt = Instant.parse("2026-08-19T00:00:00Z"),
                sourceProjectID = "p-1",
                sourceProjectRevision = 1,
                sourceSchemaVersion = 1,
                mainBranch = "主线",
            ),
        )
        store.write(
            NovelWorkspacePaths.PROJECT_FILE,
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "p-1", "kind" to "project", "title" to "赵大来了"),
                body = "",
            ),
        )
        store.write(
            "branches/主线/chapters/001-山呼.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "c-1", "kind" to "chapter", "title" to "山呼", "ordinal" to "1"),
                body = "陈桥驿的风先到。\n夜色像一张弓。",
            ),
        )
        store.write(
            "branches/主线/chapters/002-黄袍.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "c-2", "kind" to "chapter", "title" to "黄袍 & <试探>", "ordinal" to "2"),
                body = "a < b & c > d",
            ),
        )
        store.write("branches/主线/chapters/010-终章.md", "没有 front matter 的终章。")
        store.write(
            "branches/支线/chapters/001-外传.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "c-x", "kind" to "chapter", "title" to "外传"),
                body = "不该被导出的支线。",
            ),
        )
    }

    @Test
    fun `txt export renders title chapters and strips front matter`() {
        val dir = tempFolder.newFolder("txt-book")
        writeBook(dir)
        val text = String(
            NovelWorkspaceBookExport.exportBytes(dir, NovelWorkspaceBookExport.Format.TXT),
            Charsets.UTF_8,
        )
        assertEquals(
            "赵大来了\n" +
                "第1章 山呼\n\n陈桥驿的风先到。\n夜色像一张弓。\n\n" +
                "第2章 黄袍 & <试探>\n\na < b & c > d\n\n" +
                "第10章 终章\n\n没有 front matter 的终章。\n",
            text,
        )
    }

    @Test
    fun `markdown export renders headings and strips front matter`() {
        val dir = tempFolder.newFolder("md-book")
        writeBook(dir)
        val text = String(
            NovelWorkspaceBookExport.exportBytes(dir, NovelWorkspaceBookExport.Format.MARKDOWN),
            Charsets.UTF_8,
        )
        assertEquals(
            "# 赵大来了\n\n" +
                "## 第1章 山呼\n\n陈桥驿的风先到。\n夜色像一张弓。\n\n" +
                "## 第2章 黄袍 & <试探>\n\na < b & c > d\n\n" +
                "## 第10章 终章\n\n没有 front matter 的终章。\n",
            text,
        )
    }

    @Test
    fun `epub export is spec-shaped byte-stable and escaped`() {
        val dir = tempFolder.newFolder("epub-book")
        writeBook(dir)
        val bytes = NovelWorkspaceBookExport.exportBytes(dir, NovelWorkspaceBookExport.Format.EPUB)

        val entries = mutableListOf<Pair<ZipEntry, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries.add(entry to String(zip.readBytes(), Charsets.UTF_8))
                entry = zip.nextEntry
            }
        }

        // mimetype first, STORED, exact payload.
        val first = entries.first()
        assertEquals("mimetype", first.first.name)
        assertEquals(ZipEntry.STORED, first.first.method)
        assertEquals("application/epub+zip", first.second)
        // The side branch never leaks in; entry order is fixed.
        assertEquals(
            listOf(
                "mimetype",
                "META-INF/container.xml",
                "OEBPS/content.opf",
                "OEBPS/nav.xhtml",
                "OEBPS/chapter-001.xhtml",
                "OEBPS/chapter-002.xhtml",
                "OEBPS/chapter-003.xhtml",
            ),
            entries.map { it.first.name },
        )
        // One fixed DOS timestamp (1980-01-01 00:00) on every entry ⇒ byte-identical exports.
        assertEquals(1, entries.map { it.first.time }.toSet().size)

        val textOf: (String) -> String = { name -> entries.first { it.first.name == name }.second }
        val opf = textOf("OEBPS/content.opf")
        assertTrue(opf.contains("<dc:title>赵大来了</dc:title>"))
        assertTrue(opf.contains("<dc:language>zh</dc:language>"))
        val spine = opf.substringAfter("<spine>").substringBefore("</spine>")
        assertEquals(
            listOf("nav", "ch001", "ch002", "ch003"),
            Regex("idref=\"([^\"]+)\"").findAll(spine).map { it.groupValues[1] }.toList(),
        )

        val nav = textOf("OEBPS/nav.xhtml")
        assertTrue(nav.contains("epub:type=\"toc\""))
        assertTrue(nav.contains("第2章 黄袍 &amp; &lt;试探&gt;"))
        val chapter2 = textOf("OEBPS/chapter-002.xhtml")
        assertTrue(chapter2.contains("<h2>第2章 黄袍 &amp; &lt;试探&gt;</h2>"))
        assertTrue(chapter2.contains("<p>a &lt; b &amp; c &gt; d</p>"))

        // Fixed order + fixed timestamps + fixed identifier ⇒ identical bytes.
        assertArrayEquals(bytes, NovelWorkspaceBookExport.exportBytes(dir, NovelWorkspaceBookExport.Format.EPUB))
    }

    @Test
    fun `epub mimetype local header is stored with no extra field and fixed dos time`() {
        val dir = tempFolder.newFolder("epub-raw-header")
        writeBook(dir)
        val bytes = NovelWorkspaceBookExport.exportBytes(dir, NovelWorkspaceBookExport.Format.EPUB)

        fun u16(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        // "PK\x03\x04" — local file header signature of the very first entry.
        assertEquals(0x50, bytes[0].toInt() and 0xFF)
        assertEquals(0x4B, bytes[1].toInt() and 0xFF)
        assertEquals(0x03, bytes[2].toInt() and 0xFF)
        assertEquals(0x04, bytes[3].toInt() and 0xFF)
        assertEquals(0, u16(6)) // general purpose bit flags: none set
        assertEquals(0, u16(8)) // method: STORED
        assertEquals(0, u16(10)) // DOS time 00:00
        assertEquals(0x21, u16(12)) // DOS date 1980-01-01
        // JDK's ZipOutputStream would put a 0x5455 XTIMESTAMP extra here, which violates
        // OCF 3.0.1 §3.3 (epubcheck error) — the extra field must stay empty.
        val nameLength = u16(26)
        val extraLength = u16(28)
        assertEquals("mimetype".length, nameLength)
        assertEquals(0, extraLength)
        assertEquals("mimetype", String(bytes, 30, nameLength, Charsets.UTF_8))
        val mime = "application/epub+zip"
        assertEquals(mime, String(bytes, 30 + nameLength, mime.length, Charsets.UTF_8))
    }

    @Test
    fun `epub metadata and headings use the requested locale`() {
        val dir = tempFolder.newFolder("epub-english")
        writeBook(dir)
        val bytes = NovelWorkspaceBookExport.exportBytes(
            dir,
            NovelWorkspaceBookExport.Format.EPUB,
            locale = Locale.ENGLISH,
        )
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = String(zip.readBytes(), Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }

        assertTrue(entries.getValue("OEBPS/content.opf").contains("<dc:language>en</dc:language>"))
        assertTrue(entries.getValue("OEBPS/nav.xhtml").contains("Chapter 1: 山呼"))
    }

    @Test
    fun `suggest file name slugs the title per format`() {
        assertEquals(
            "赵大来了.txt",
            NovelWorkspaceBookExport.suggestFileName("赵大来了", NovelWorkspaceBookExport.Format.TXT),
        )
        assertEquals(
            "书名.md",
            NovelWorkspaceBookExport.suggestFileName(" 书名 ", NovelWorkspaceBookExport.Format.MARKDOWN),
        )
        assertEquals(
            "a-b-c.epub",
            NovelWorkspaceBookExport.suggestFileName("a/b:c", NovelWorkspaceBookExport.Format.EPUB),
        )
    }
}
