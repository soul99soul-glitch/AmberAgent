package app.amber.agent.ui.components.richtext.nativebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD tests for [PackedAstReader.validate] — eager packed-blob bounds validation.
 *
 * The contract: validate() returns true iff the entire blob is self-consistent and
 * no later lazy PackedAstNode read can run past the buffer. It must NEVER throw —
 * only return false for any form of corruption.
 *
 * Stage 4 parse funnel calls: reader.isValid && reader.validate() before handing the
 * tree to the renderer, so an IndexOutOfBoundsException during composition is impossible.
 */
class PackedAstValidateTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun writeVarint(value: Long, out: MutableList<Byte>) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toByte()
            v = v ushr 7
            if (v == 0L) { out.add(b); return }
            out.add((b.toInt() or 0x80).toByte())
        }
    }

    /** Build a minimal valid blob: header + Root node (no extras, N children). */
    private fun buildMinimalBlob(childCount: Int = 0): ByteArray {
        val blob = mutableListOf<Byte>()
        // Header: 'PMDA' + version=1 + flags=0 + reserved=0,0
        blob.addAll(listOf('P'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte()))
        blob.add(1); blob.add(0); blob.add(0); blob.add(0)

        // Root node: tag=0, start=0, delta=0, extrasLen=0, childCount
        blob.add(0)
        writeVarint(0, blob); writeVarint(0, blob); writeVarint(0, blob)
        writeVarint(childCount.toLong(), blob)

        // Add the specified number of leaf children
        repeat(childCount) {
            blob.add(NodeType.Paragraph.code.toByte())
            writeVarint(0, blob); writeVarint(0, blob); writeVarint(0, blob)
            writeVarint(0, blob)
        }

        return blob.toByteArray()
    }

    private fun loadBlob(name: String): ByteArray {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("markdown-corpus/$name")
            ?: error("corpus file not found: markdown-corpus/$name")
        return stream.use { it.readBytes() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Valid blobs → true
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun validate_valid_golden_02_headings_returns_true() {
        val blob = loadBlob("02-headings-all-levels.pmda")
        val reader = PackedAstReader(blob)
        assertTrue("valid golden blob must validate", reader.validate())
    }

    @Test
    fun validate_minimal_root_only_blob_returns_true() {
        val blob = buildMinimalBlob(childCount = 0)
        val reader = PackedAstReader(blob)
        assertTrue("minimal valid blob must validate", reader.validate())
    }

    @Test
    fun validate_minimal_blob_with_children_returns_true() {
        val blob = buildMinimalBlob(childCount = 3)
        val reader = PackedAstReader(blob)
        assertTrue("blob with leaf children must validate", reader.validate())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Invalid header → false (not throw)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun validate_empty_array_returns_false() {
        val reader = PackedAstReader(ByteArray(0))
        assertFalse("empty array must not validate", reader.validate())
    }

    @Test
    fun validate_header_only_8_bytes_returns_false() {
        // A header-only blob has no root node body at all — blob is only 8 bytes.
        val blob = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(),
            'A'.code.toByte(), 1, 0, 0, 0)
        val reader = PackedAstReader(blob)
        assertFalse("8-byte header-only blob has no root node body → false", reader.validate())
    }

    @Test
    fun validate_wrong_magic_returns_false() {
        val blob = ByteArray(20) { 0x42 }  // all 'B', wrong magic
        val reader = PackedAstReader(blob)
        // isValid is false, so validate() must also return false
        assertFalse("wrong magic → isValid false → validate false", reader.validate())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Truncated blob → false (not throw)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun validate_blob_truncated_by_3_bytes_returns_false_not_throw() {
        val original = loadBlob("02-headings-all-levels.pmda")
        val truncated = original.copyOf(original.size - 3)
        val reader = PackedAstReader(truncated)
        // isValid is still true (header intact), but body is cut short
        assertFalse("blob truncated by 3 bytes must return false, not throw", reader.validate())
    }

    @Test
    fun validate_blob_truncated_to_9_bytes_returns_false_not_throw() {
        // Header (8 bytes) + just the root tag byte — rest of the root node is missing.
        val original = loadBlob("02-headings-all-levels.pmda")
        val truncated = original.copyOf(9)
        val reader = PackedAstReader(truncated)
        assertFalse("blob truncated to 9 bytes must return false, not throw", reader.validate())
    }

    @Test
    fun validate_truncated_minimal_blob_returns_false_not_throw() {
        val original = buildMinimalBlob(childCount = 2)
        // Lop off the last 2 bytes (inside the second child node)
        val truncated = original.copyOf(original.size - 2)
        val reader = PackedAstReader(truncated)
        assertFalse("truncated minimal blob must return false, not throw", reader.validate())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Corrupted childCount varint → false (not throw)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun validate_huge_childCount_varint_returns_false_not_throw() {
        // Build a valid minimal blob, then corrupt the childCount field of the root node
        // by overwriting the single-byte childCount (at offset just after start/delta/extras
        // varints) with a 5-byte continuation varint encoding a huge number.
        //
        // The minimal blob layout:
        //   [0..7]  header
        //   [8]     root tag byte (0x00)
        //   [9]     start varint (0x00)
        //   [10]    endDelta varint (0x00)
        //   [11]    extrasLen varint (0x00)
        //   [12]    childCount varint (0x00 for 0 children) ← corrupt here
        //
        // We want to insert a varint that claims a huge child count without enough following
        // bytes. We'll build the blob manually and insert the oversized varint.
        val blobList = mutableListOf<Byte>()
        // header
        blobList.addAll(listOf('P'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte()))
        blobList.add(1); blobList.add(0); blobList.add(0); blobList.add(0)
        // root tag + start + delta + extrasLen
        blobList.add(0)
        writeVarint(0, blobList); writeVarint(0, blobList); writeVarint(0, blobList)
        // Corrupt childCount: encode 2^28 = 268435456, which is a multi-byte varint
        // claiming far more children than there are bytes remaining
        writeVarint(268435456L, blobList)
        // Blob ends here — no children follow despite childCount claiming millions

        val blob = blobList.toByteArray()
        val reader = PackedAstReader(blob)
        assertFalse("huge childCount varint with no body → false, not throw", reader.validate())
    }

    @Test
    fun validate_oversized_varint_5byte_returns_false_not_throw() {
        // Build a blob where the root node has a childCount encoded as
        // 0xFF 0xFF 0xFF 0xFF 0x7F — a valid large varint but way more children
        // than bytes available.
        val blobList = mutableListOf<Byte>()
        // header
        blobList.addAll(listOf('P'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte()))
        blobList.add(1); blobList.add(0); blobList.add(0); blobList.add(0)
        // root tag + start + delta + extrasLen
        blobList.add(0)
        writeVarint(0, blobList); writeVarint(0, blobList); writeVarint(0, blobList)
        // oversized childCount: 0xFF 0xFF 0xFF 0xFF 0x7F
        blobList.add(0xFF.toByte())
        blobList.add(0xFF.toByte())
        blobList.add(0xFF.toByte())
        blobList.add(0xFF.toByte())
        blobList.add(0x7F.toByte())
        // No actual children — skipNode will run past the end

        val blob = blobList.toByteArray()
        val reader = PackedAstReader(blob)
        assertFalse("5-byte oversized childCount varint → false, not throw", reader.validate())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. All 32 golden blobs → true
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun validate_all_32_golden_blobs_return_true() {
        val names = (1..32).map { n -> "%02d".format(n) }.flatMap { prefix ->
            javaClass.classLoader!!
                .getResourceAsStream("markdown-corpus")
                ?.use { null }  // can't list classpath dirs this way — list statically instead
                ?: listOf<String>()
        }
        // Static list since classpath directory listing is unreliable in JVM unit tests.
        val goldenBlobs = listOf(
            "01-plain-paragraphs.pmda",
            "02-headings-all-levels.pmda",
            "03-emphasis-nesting.pmda",
            "04-links-inline.pmda",
            "05-links-with-titles.pmda",
            "06-images.pmda",
            "07-fenced-code-kotlin.pmda",
            "08-fenced-code-no-lang.pmda",
            "09-indented-code.pmda",
            "10-inline-code.pmda",
            "11-gfm-table-simple.pmda",
            "12-gfm-table-aligned.pmda",
            "13-nested-lists.pmda",
            "14-ordered-list-start.pmda",
            "15-task-lists.pmda",
            "16-blockquotes-nested.pmda",
            "17-thematic-breaks.pmda",
            "18-katex-inline.pmda",
            "19-katex-block.pmda",
            "20-html-block.pmda",
            "21-inline-html.pmda",
            "22-footnotes.pmda",
            "23-strikethrough.pmda",
            "24-cjk-mixed.pmda",
            "25-long-message.pmda",
            "26-streaming-truncated.pmda",
            "27-hard-soft-breaks.pmda",
            "28-link-edge-cases.pmda",
            "29-mixed-everything.pmda",
            "30-empty-and-whitespace.pmda",
            "31-deep-nesting.pmda",
            "32-special-chars-escapes.pmda",
        )

        val failures = mutableListOf<String>()
        for (name in goldenBlobs) {
            val blob = loadBlob(name)
            val reader = PackedAstReader(blob)
            if (!reader.validate()) {
                failures.add(name)
            }
        }
        assertTrue(
            "The following golden blobs failed validate(): $failures",
            failures.isEmpty()
        )
    }
}
