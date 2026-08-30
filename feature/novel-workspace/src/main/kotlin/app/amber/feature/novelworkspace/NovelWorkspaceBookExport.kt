package app.amber.feature.novelworkspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.CRC32

/**
 * "Export as book" renderers (txt / md / epub) over a workspace project directory.
 *
 * v1 exports one branch's manuscript; [branchSlug] null 保持旧行为导出主线
 * (manifest.mainBranch)，工作区页/项目页传活跃分支。Chapter order is the file-name
 * ordinal, chapter titles come from front matter with the file-name fallback. The epub
 * is byte-reproducible: fixed entry order, fixed timestamps, fixed dc:identifier. Its
 * container is written by [DeterministicZipWriter] — not java.util.zip.ZipOutputStream —
 * so the mimetype entry stays free of injected extra fields (OCF 3.0.1 §3.3).
 */
object NovelWorkspaceBookExport {

    enum class Format(val extension: String, val mimeType: String) {
        TXT("txt", "text/plain"),
        MARKDOWN("md", "text/markdown"),
        EPUB("epub", "application/epub+zip"),
    }

    data class Chapter(val ordinal: Int, val title: String, val body: String) {
        /** Legacy accessor keeps the historical Chinese export wording. */
        val heading: String get() = heading(Locale.CHINESE)

        fun heading(locale: Locale): String = if (locale.language.equals("zh", ignoreCase = true)) {
            if (title.isBlank()) "第${ordinal}章" else "第${ordinal}章 $title"
        } else {
            if (title.isBlank()) "Chapter $ordinal" else "Chapter $ordinal: $title"
        }
    }

    data class Book(val title: String, val chapters: List<Chapter>)

    fun exportBytes(
        projectDirectory: File,
        format: Format,
        branchSlug: String? = null,
        locale: Locale = Locale.CHINESE,
    ): ByteArray {
        val book = read(projectDirectory, branchSlug)
        return when (format) {
            Format.TXT -> txt(book, locale)
            Format.MARKDOWN -> markdown(book, locale)
            Format.EPUB -> epub(book, locale)
        }
    }

    /** One branch's book; chapters ascend by the ordinal encoded in their file names. */
    fun read(projectDirectory: File, branchSlug: String? = null): Book {
        val store = NovelWorkspaceStore(projectDirectory)
        val manifest = NovelWorkspaceManifest.parse(store.read(NovelWorkspacePaths.MANIFEST) ?: "")
        val branch = branchSlug?.takeIf { it.isNotBlank() } ?: manifest.mainBranch
        val prefix = NovelWorkspacePaths.branchPrefix(branch) + "/chapters"
        val chapters = store.list(prefix)
            .mapNotNull { path ->
                val ordinal = NovelWorkspacePaths.chapterOrdinalFromPath(path) ?: return@mapNotNull null
                val parsed = NovelWorkspaceMarkdown.parseFile(store.read(path) ?: return@mapNotNull null)
                Chapter(
                    ordinal = ordinal,
                    title = parsed.fields["title"]?.takeIf { it.isNotBlank() }
                        ?: NovelWorkspacePaths.fileNameTitle(path),
                    body = parsed.body,
                )
            }
            .sortedBy { it.ordinal }
        return Book(NovelWorkspaceProjectTitle.read(store), chapters)
    }

    fun txt(book: Book, locale: Locale = Locale.CHINESE): ByteArray = buildString {
        append(book.title)
        book.chapters.forEachIndexed { index, chapter ->
            // Title hugs the first heading; blank lines only separate chapters.
            append(if (index == 0) "\n" else "\n\n")
            append(chapter.heading(locale))
            append("\n\n")
            append(chapter.body)
        }
        append("\n")
    }.toByteArray(Charsets.UTF_8)

    fun markdown(book: Book, locale: Locale = Locale.CHINESE): ByteArray = buildString {
        append("# ${book.title}")
        for (chapter in book.chapters) {
            append("\n\n## ${chapter.heading(locale)}\n\n")
            append(chapter.body)
        }
        append("\n")
    }.toByteArray(Charsets.UTF_8)

    fun epub(book: Book, locale: Locale = Locale.CHINESE): ByteArray {
        val zip = DeterministicZipWriter()
        // mimetype must be the very first entry, uncompressed, with no extra fields (OCF 3.0.1 §3.3).
        zip.write("mimetype", EPUB_MIME.toByteArray(Charsets.UTF_8))
        zip.write("META-INF/container.xml", CONTAINER_XML.toByteArray(Charsets.UTF_8))
        zip.write("OEBPS/content.opf", contentOpf(book, locale).toByteArray(Charsets.UTF_8))
        zip.write("OEBPS/nav.xhtml", navXhtml(book, locale).toByteArray(Charsets.UTF_8))
        book.chapters.forEachIndexed { index, chapter ->
            zip.write("OEBPS/${chapterHref(index)}", chapterXhtml(chapter, locale).toByteArray(Charsets.UTF_8))
        }
        return zip.finish()
    }

    /** Download suggestion: slugified book title + format extension. */
    fun suggestFileName(title: String, format: Format): String {
        val slug = NovelWorkspaceSlug.slug(title).ifEmpty { "book" }
        return "$slug.${format.extension}"
    }

    private const val EPUB_MIME = "application/epub+zip"

    // ── Deterministic zip constants (little-endian wire values) ──
    private const val ZIP_SIG_LOCAL = 0x04034b50L
    private const val ZIP_SIG_CENTRAL = 0x02014b50L
    private const val ZIP_SIG_EOCD = 0x06054b50L
    /** 2.0 — the classic "version needed to extract" for STORED entries. */
    private const val ZIP_VERSION_NEEDED = 20
    private const val ZIP_METHOD_STORED = 0
    /** DOS clock floor 1980-01-01 00:00:00 — one fixed timestamp for every entry. */
    private const val ZIP_DOS_TIME = 0
    private const val ZIP_DOS_DATE = 0x21

    /**
     * Minimal hand-written zip writer (STORED-only) for the epub container.
     *
     * 为什么不用 java.util.zip.ZipOutputStream：JDK 17 对每个条目（包括 mimetype）自动注入
     * 9 字节 0x5455 extended-timestamp extra field，违反 OCF 3.0.1 §3.3（mimetype 条目不得
     * 携带 extra field，epubcheck 直接报错），而 JDK 不提供关闭该行为的入口，覆写 java.* 内部
     * 亦不可行，故自写。取舍：epub 体量小且为纯文本，全部条目 STORED，直接拼本地文件头 +
     * 数据 + 中央目录 + EOCD，代码最短且行为完全可控。通用位标志恒为 0（无 data descriptor、
     * 不设 UTF-8 位——条目名全为 ASCII），extra/comment 恒为空，DOS 时间固定 1980-01-01 00:00；
     * 相同输入得到字节级相同的输出。
     */
    private class DeterministicZipWriter {
        private val locals = ByteArrayOutputStream()
        private val central = ByteArrayOutputStream()
        private var entryCount = 0

        /** Appends one STORED entry: local file header + payload, plus its central record. */
        fun write(name: String, bytes: ByteArray) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            require(nameBytes.size <= 0xFFFF) { "zip entry name too long: $name" }
            require(bytes.size <= 0xFFFFFFFFL) { "zip entry too large: $name" }
            val crc = CRC32().apply { update(bytes) }.value
            val offset = locals.size()
            locals.u32(ZIP_SIG_LOCAL)
            locals.u16(ZIP_VERSION_NEEDED)
            locals.u16(0) // general purpose bit flags: none set
            locals.u16(ZIP_METHOD_STORED)
            locals.u16(ZIP_DOS_TIME)
            locals.u16(ZIP_DOS_DATE)
            locals.u32(crc)
            locals.u32(bytes.size.toLong())
            locals.u32(bytes.size.toLong())
            locals.u16(nameBytes.size)
            locals.u16(0) // extra length: always empty — this is where XTIMESTAMP would live
            locals.write(nameBytes)
            locals.write(bytes)
            central.u32(ZIP_SIG_CENTRAL)
            central.u16(ZIP_VERSION_NEEDED) // version made by (host 0 / MS-DOS)
            central.u16(ZIP_VERSION_NEEDED)
            central.u16(0) // flags
            central.u16(ZIP_METHOD_STORED)
            central.u16(ZIP_DOS_TIME)
            central.u16(ZIP_DOS_DATE)
            central.u32(crc)
            central.u32(bytes.size.toLong())
            central.u32(bytes.size.toLong())
            central.u16(nameBytes.size)
            central.u16(0) // extra length
            central.u16(0) // comment length
            central.u16(0) // disk number start
            central.u16(0) // internal attributes
            central.u32(0) // external attributes
            central.u32(offset.toLong())
            central.write(nameBytes)
            entryCount++
        }

        /** Appends the central directory then the EOCD record and returns the archive. */
        fun finish(): ByteArray {
            val centralBytes = central.toByteArray()
            val centralOffset = locals.size()
            locals.write(centralBytes)
            locals.u32(ZIP_SIG_EOCD)
            locals.u16(0) // this disk number
            locals.u16(0) // disk where the central directory starts
            locals.u16(entryCount)
            locals.u16(entryCount)
            locals.u32(centralBytes.size.toLong())
            locals.u32(centralOffset.toLong())
            locals.u16(0) // archive comment length
            return locals.toByteArray()
        }

        private fun ByteArrayOutputStream.u16(value: Int) {
            write(value and 0xFF)
            write((value ushr 8) and 0xFF)
        }

        private fun ByteArrayOutputStream.u32(value: Long) {
            write((value and 0xFF).toInt())
            write(((value ushr 8) and 0xFF).toInt())
            write(((value ushr 16) and 0xFF).toInt())
            write(((value ushr 24) and 0xFF).toInt())
        }
    }

    private val CONTAINER_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent() + "\n"

    /** Positions, not ordinals: duplicate ordinals in the tree must not collide in the opf. */
    private fun chapterHref(index: Int): String =
        String.format(java.util.Locale.ROOT, "chapter-%03d.xhtml", index + 1)

    private fun chapterId(index: Int): String =
        String.format(java.util.Locale.ROOT, "ch%03d", index + 1)

    private fun contentOpf(book: Book, locale: Locale): String {
        // Deterministic identifier: a UUID v3-shaped digest of the title, never random.
        val uuid = java.util.UUID.nameUUIDFromBytes("amber-novel:${book.title}".toByteArray(Charsets.UTF_8))
        val lines = mutableListOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\">",
            "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">",
            "    <dc:identifier id=\"book-id\">urn:uuid:$uuid</dc:identifier>",
            "    <dc:title>${escape(book.title)}</dc:title>",
            "    <dc:language>${escape(locale.toLanguageTag())}</dc:language>",
            "    <meta property=\"dcterms:modified\">1970-01-01T00:00:00Z</meta>",
            "  </metadata>",
            "  <manifest>",
            "    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>",
        )
        book.chapters.forEachIndexed { index, _ ->
            lines.add(
                "    <item id=\"${chapterId(index)}\" href=\"${chapterHref(index)}\"" +
                    " media-type=\"application/xhtml+xml\"/>",
            )
        }
        lines.add("  </manifest>")
        lines.add("  <spine>")
        lines.add("    <itemref idref=\"nav\"/>")
        book.chapters.forEachIndexed { index, _ ->
            lines.add("    <itemref idref=\"${chapterId(index)}\"/>")
        }
        lines.add("  </spine>")
        lines.add("</package>")
        return lines.joinToString("\n") + "\n"
    }

    private fun navXhtml(book: Book, locale: Locale): String {
        val lines = mutableListOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">",
            "  <head><title>${escape(book.title)}</title></head>",
            "  <body>",
            "    <nav epub:type=\"toc\">",
            "      <ol>",
        )
        book.chapters.forEachIndexed { index, chapter ->
            lines.add("        <li><a href=\"${chapterHref(index)}\">${escape(chapter.heading(locale))}</a></li>")
        }
        lines.add("      </ol>")
        lines.add("    </nav>")
        lines.add("  </body>")
        lines.add("</html>")
        return lines.joinToString("\n") + "\n"
    }

    private fun chapterXhtml(chapter: Chapter, locale: Locale): String {
        val lines = mutableListOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<html xmlns=\"http://www.w3.org/1999/xhtml\">",
            "  <head><title>${escape(chapter.heading(locale))}</title></head>",
            "  <body>",
            "    <h2>${escape(chapter.heading(locale))}</h2>",
        )
        chapter.body.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { lines.add("    <p>${escape(it)}</p>") }
        lines.add("  </body>")
        lines.add("</html>")
        return lines.joinToString("\n") + "\n"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
