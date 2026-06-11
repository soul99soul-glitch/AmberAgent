package app.amber.agent.ui.components.richtext.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD tests for [PackedAstExtras] typed decoders.
 *
 * Tests are grouped into:
 * 1. Handcrafted varint / encoding edge-cases (pure byte arrays)
 * 2. Node-type guard tests (wrong type → null)
 * 3. Golden blob spot-checks against real Rust-generated corpus files
 *
 * Wire format verified against native/markdown-parser/src/tree_builder.rs:
 * - Heading:        extras[0] = level byte (1–6)
 * - CodeBlock:      LEB128 varint lang-len + UTF-8 lang bytes
 * - Link/Image:     LEB128 varint dest_len + dest bytes + LEB128 varint title_len + title bytes
 * - TaskListMarker: extras[0] = 1 (checked) or 0 (unchecked)
 * - ListOrdered:    8 bytes, u64 little-endian start number
 * - Table:          NO extras written (attach_tag_extras has no Tag::Table arm).
 *                   tableAlignmentsExtra() always returns null / empty for real blobs.
 */
class PackedAstExtrasTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers: build minimal PackedAstNode from raw extras bytes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a minimal packed-AST blob containing a single leaf node (no
     * children) with the given [typeCode] and [extras], then return its
     * PackedAstNode so the decoders can be exercised.
     *
     * Wire layout per SPIKE_PLAN §4.2:
     *   header (8 bytes): 'PMDA' + version(1) + flags(0) + reserved(0,0)
     *   root node:
     *     tag(0=Root) + varint(start=0) + varint(delta=0) + varint(0 extras)
     *     + varint(1 child)
     *   child node:
     *     tag(typeCode) + varint(0) + varint(0) + varint(extrasLen) + extras
     *     + varint(0 children)
     */
    private fun nodeWith(typeCode: Int, extras: ByteArray): PackedAstNode {
        val blob = mutableListOf<Byte>()
        // header
        blob.addAll(listOf('P'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte()))
        blob.add(1)  // version
        blob.add(0)  // flags
        blob.add(0); blob.add(0)  // reserved

        // root node: tag=0, start=0, delta=0, extrasLen=0, childCount=1
        blob.add(0)
        writeVarint(0, blob); writeVarint(0, blob); writeVarint(0, blob)
        writeVarint(1, blob)

        // child node
        blob.add(typeCode.toByte())
        writeVarint(0, blob); writeVarint(0, blob)
        writeVarint(extras.size.toLong(), blob)
        extras.forEach { blob.add(it) }
        writeVarint(0, blob)

        val array = blob.toByteArray()
        return PackedAstReader(array).root()!!.children[0]
    }

    private fun writeVarint(value: Long, out: MutableList<Byte>) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toByte()
            v = v ushr 7
            if (v == 0L) { out.add(b); return }
            out.add((b.toInt() or 0x80).toByte())
        }
    }

    private fun bytes(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

    /** LEB128 encode a string (varint length + UTF-8 bytes). */
    private fun encodeString(s: String): ByteArray {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        val out = mutableListOf<Byte>()
        writeVarint(utf8.size.toLong(), out)
        utf8.forEach { out.add(it) }
        return out.toByteArray()
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val result = ByteArray(total)
        var pos = 0
        for (a in arrays) { a.copyInto(result, pos); pos += a.size }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. headingLevelExtra
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun headingLevelExtra_h1_returns_1() {
        val node = nodeWith(NodeType.Heading.code, bytes(1))
        assertEquals(1, node.headingLevelExtra())
    }

    @Test
    fun headingLevelExtra_h6_returns_6() {
        val node = nodeWith(NodeType.Heading.code, bytes(6))
        assertEquals(6, node.headingLevelExtra())
    }

    @Test
    fun headingLevelExtra_wrong_type_returns_null() {
        val node = nodeWith(NodeType.Paragraph.code, bytes(1))
        assertNull(node.headingLevelExtra())
    }

    @Test
    fun headingLevelExtra_empty_extras_returns_null() {
        val node = nodeWith(NodeType.Heading.code, ByteArray(0))
        assertNull(node.headingLevelExtra())
    }

    @Test
    fun headingLevelExtra_byte_0_out_of_range_returns_null() {
        // 0 is not a valid heading level (1–6 only)
        val node = nodeWith(NodeType.Heading.code, bytes(0))
        assertNull(node.headingLevelExtra())
    }

    @Test
    fun headingLevelExtra_byte_7_out_of_range_returns_null() {
        // 7 exceeds the maximum valid heading level
        val node = nodeWith(NodeType.Heading.code, bytes(7))
        assertNull(node.headingLevelExtra())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. codeLangExtra — varint encoding edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun codeLangExtra_empty_lang_returns_null() {
        // varint 0 → empty string → codeLangExtra returns null (empty → null)
        val node = nodeWith(NodeType.CodeBlock.code, encodeString(""))
        assertNull(node.codeLangExtra())
    }

    @Test
    fun codeLangExtra_kotlin_returns_kotlin() {
        val node = nodeWith(NodeType.CodeBlock.code, encodeString("kotlin"))
        assertEquals("kotlin", node.codeLangExtra())
    }

    @Test
    fun codeLangExtra_lang_len_127_boundary() {
        // varint for 127 fits in 1 byte (0x7F)
        val lang = "a".repeat(127)
        val node = nodeWith(NodeType.CodeBlock.code, encodeString(lang))
        assertEquals(lang, node.codeLangExtra())
    }

    @Test
    fun codeLangExtra_lang_len_128_two_byte_varint() {
        // varint for 128 = 0x80 0x01 (two bytes)
        val lang = "b".repeat(128)
        val node = nodeWith(NodeType.CodeBlock.code, encodeString(lang))
        assertEquals(lang, node.codeLangExtra())
    }

    @Test
    fun codeLangExtra_cjk_utf8() {
        // CJK character 你好 = 3 bytes each → 6 bytes total, varint 6 = 0x06
        val cjk = "你好"
        val node = nodeWith(NodeType.CodeBlock.code, encodeString(cjk))
        assertEquals(cjk, node.codeLangExtra())
    }

    @Test
    fun codeLangExtra_wrong_type_returns_null() {
        val node = nodeWith(NodeType.Paragraph.code, encodeString("kotlin"))
        assertNull(node.codeLangExtra())
    }

    // ── Fuzz anchors: never-throw contract (M3) ──────────────────────────────

    @Test
    fun codeLangExtra_oversized_varint_returns_null_not_throw() {
        // 0xFF,0xFF,0xFF,0xFF,0x7F encodes a varint far beyond the array length.
        // readString must return null, not throw any exception.
        val node = nodeWith(NodeType.CodeBlock.code, bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x7F))
        assertNull(node.codeLangExtra())
    }

    @Test
    fun linkHrefExtra_oversized_varint_returns_null_not_throw() {
        // Same oversized varint through linkHrefExtra path.
        val node = nodeWith(NodeType.Link.code, bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x7F))
        assertNull(node.linkHrefExtra())
    }

    @Test
    fun linkTitleExtra_oversized_second_varint_returns_null_not_throw() {
        // Valid first string ("x"), then an oversized varint for the second string
        // (title). readString for the second field must return null, not throw.
        val validFirst = encodeString("x")
        val oversized = bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x7F)
        val node = nodeWith(NodeType.Link.code, concat(validFirst, oversized))
        assertNull(node.linkTitleExtra())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. linkHrefExtra / linkTitleExtra
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun linkHrefExtra_returns_url() {
        val extras = concat(encodeString("https://example.com"), encodeString(""))
        val node = nodeWith(NodeType.Link.code, extras)
        assertEquals("https://example.com", node.linkHrefExtra())
    }

    @Test
    fun linkHrefExtra_image_type_also_works() {
        val extras = concat(encodeString("https://img.example.com/a.png"), encodeString("alt text"))
        val node = nodeWith(NodeType.Image.code, extras)
        assertEquals("https://img.example.com/a.png", node.linkHrefExtra())
    }

    @Test
    fun linkTitleExtra_empty_title_returns_null() {
        val extras = concat(encodeString("https://example.com"), encodeString(""))
        val node = nodeWith(NodeType.Link.code, extras)
        assertNull(node.linkTitleExtra())
    }

    @Test
    fun linkTitleExtra_non_empty_title_returned() {
        val extras = concat(encodeString("https://example.com"), encodeString("My Title"))
        val node = nodeWith(NodeType.Link.code, extras)
        assertEquals("My Title", node.linkTitleExtra())
    }

    @Test
    fun linkHrefExtra_wrong_type_returns_null() {
        val extras = concat(encodeString("https://example.com"), encodeString(""))
        val node = nodeWith(NodeType.Paragraph.code, extras)
        assertNull(node.linkHrefExtra())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. taskCheckedExtra
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun taskCheckedExtra_1_means_checked() {
        val node = nodeWith(NodeType.TaskListMarker.code, bytes(1))
        assertTrue(node.taskCheckedExtra()!!)
    }

    @Test
    fun taskCheckedExtra_0_means_unchecked() {
        val node = nodeWith(NodeType.TaskListMarker.code, bytes(0))
        assertFalse(node.taskCheckedExtra()!!)
    }

    @Test
    fun taskCheckedExtra_wrong_type_returns_null() {
        val node = nodeWith(NodeType.Paragraph.code, bytes(1))
        assertNull(node.taskCheckedExtra())
    }

    @Test
    fun taskCheckedExtra_empty_extras_returns_null() {
        val node = nodeWith(NodeType.TaskListMarker.code, ByteArray(0))
        assertNull(node.taskCheckedExtra())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. listStartExtra — u64 LE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun listStartExtra_start_1() {
        val extras = ByteArray(8); extras[0] = 1
        val node = nodeWith(NodeType.ListOrdered.code, extras)
        assertEquals(1L, node.listStartExtra())
    }

    @Test
    fun listStartExtra_start_7() {
        val extras = ByteArray(8); extras[0] = 7
        val node = nodeWith(NodeType.ListOrdered.code, extras)
        assertEquals(7L, node.listStartExtra())
    }

    @Test
    fun listStartExtra_start_42_le() {
        // 42 = 0x2A in LE → bytes[0]=0x2A, rest=0
        val extras = ByteArray(8); extras[0] = 0x2A
        val node = nodeWith(NodeType.ListOrdered.code, extras)
        assertEquals(42L, node.listStartExtra())
    }

    @Test
    fun listStartExtra_large_value_multi_byte() {
        // 0x0102030405060708L in LE
        val v = 0x0102030405060708L
        val extras = ByteArray(8)
        for (i in 0 until 8) extras[i] = ((v ushr (i * 8)) and 0xFF).toByte()
        val node = nodeWith(NodeType.ListOrdered.code, extras)
        assertEquals(v, node.listStartExtra())
    }

    @Test
    fun listStartExtra_wrong_type_returns_null() {
        val node = nodeWith(NodeType.Paragraph.code, ByteArray(8))
        assertNull(node.listStartExtra())
    }

    @Test
    fun listStartExtra_too_short_returns_null() {
        val node = nodeWith(NodeType.ListOrdered.code, ByteArray(7))
        assertNull(node.listStartExtra())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. tableAlignmentsExtra
    //
    // VERIFIED: attach_tag_extras in tree_builder.rs has NO Tag::Table arm.
    // Table nodes always have empty extras in the wire format. The function
    // returns null for Table nodes with empty extras, and null for non-Table.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tableAlignmentsExtra_empty_extras_returns_null() {
        val node = nodeWith(NodeType.Table.code, ByteArray(0))
        assertNull(node.tableAlignmentsExtra())
    }

    @Test
    fun tableAlignmentsExtra_wrong_type_returns_null() {
        val node = nodeWith(NodeType.Paragraph.code, ByteArray(0))
        assertNull(node.tableAlignmentsExtra())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Golden blob spot-checks (Rust-generated corpus files)
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadBlob(name: String): ByteArray {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("markdown-corpus/$name")
            ?: error("corpus file not found: markdown-corpus/$name")
        return stream.use { it.readBytes() }
    }

    @Test
    fun golden_headingLevel_02_headings_all_levels() {
        val blob = loadBlob("02-headings-all-levels.pmda")
        val root = PackedAstReader(blob).root()!!
        // First child should be H1
        val h1 = root.children.first { it.type == NodeType.Heading }
        assertEquals(1, h1.headingLevelExtra())
    }

    @Test
    fun golden_codeLang_07_fenced_code_kotlin() {
        val blob = loadBlob("07-fenced-code-kotlin.pmda")
        val root = PackedAstReader(blob).root()!!
        val codeBlock = root.children.first { it.type == NodeType.CodeBlock }
        assertEquals("kotlin", codeBlock.codeLangExtra())
    }

    @Test
    fun golden_linkHref_04_links_inline() {
        val blob = loadBlob("04-links-inline.pmda")
        val root = PackedAstReader(blob).root()!!
        // First Link node in the tree
        val link = root.findChildOfTypeRecursive(NodeType.Link)!!
        assertEquals("https://example.com", link.linkHrefExtra())
    }

    @Test
    fun golden_listStart_14_ordered_list_start() {
        val blob = loadBlob("14-ordered-list-start.pmda")
        val root = PackedAstReader(blob).root()!!
        val orderedList = root.children.first { it.type == NodeType.ListOrdered }
        assertEquals(7L, orderedList.listStartExtra())
    }

    @Test
    fun golden_taskMarkers_15_task_lists() {
        val blob = loadBlob("15-task-lists.pmda")
        val root = PackedAstReader(blob).root()!!
        // Find at least one checked and one unchecked marker
        val markers = mutableListOf<Boolean?>()
        fun collect(node: PackedAstNode) {
            if (node.type == NodeType.TaskListMarker) markers.add(node.taskCheckedExtra())
            node.children.forEach { collect(it) }
        }
        collect(root)
        assertTrue("expected at least one checked marker", markers.any { it == true })
        assertTrue("expected at least one unchecked marker", markers.any { it == false })
    }

    @Test
    fun golden_table_12_gfm_table_aligned_has_no_extras() {
        // VERIFIED: Rust tree_builder.rs writes NO extras for Table nodes.
        // tableAlignmentsExtra() must return null for real corpus blobs.
        val blob = loadBlob("12-gfm-table-aligned.pmda")
        val root = PackedAstReader(blob).root()!!
        val table = root.children.first { it.type == NodeType.Table }
        assertNull(table.tableAlignmentsExtra())
        // Also verify directly that extras is empty
        assertEquals(0, table.extras.size)
    }
}
