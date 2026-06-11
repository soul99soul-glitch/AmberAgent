package app.amber.agent.ui.components.richtext.nativebridge

/**
 * Typed decoders for [PackedAstNode.extras]. Wire format verified against
 * native/markdown-parser/src/tree_builder.rs (attach_tag_extras function).
 *
 * ## Verified wire format per Rust source
 *
 * - **Heading**: extras[0] = heading level byte (1–6)
 * - **CodeBlock**: LEB128 varint lang-length + UTF-8 lang bytes;
 *   empty string "" for indented code blocks (no language)
 * - **Link / Image**: LEB128 varint dest_url-len + dest_url UTF-8 bytes
 *   followed by LEB128 varint title-len + title UTF-8 bytes
 * - **TaskListMarker**: extras[0] = 1 (checked) or 0 (unchecked)
 * - **ListOrdered**: 8 bytes, u64 little-endian, the ordered list's start number
 * - **Table**: EMPTY — `attach_tag_extras` has no `Tag::Table` arm; column
 *   alignment information is NOT encoded in the extras. `tableAlignmentsExtra()`
 *   returns null for all real corpus blobs.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Public typed decoders
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns the heading level (1–6), or `null` if this node is not a [NodeType.Heading],
 * its extras are empty (malformed blob), or the level byte is outside the valid 1–6
 * range (malformed levels outside 1..6 → null).
 */
internal fun PackedAstNode.headingLevelExtra(): Int? =
    if (type == NodeType.Heading && extras.isNotEmpty())
        (extras[0].toInt() and 0xFF).takeIf { it in 1..6 }
    else null

/**
 * Returns the fenced-code language string, or `null` if:
 * - this node is not a [NodeType.CodeBlock], or
 * - the language is empty (indented code block has no language), or
 * - extras are truncated / malformed.
 */
internal fun PackedAstNode.codeLangExtra(): String? {
    if (type != NodeType.CodeBlock) return null
    return extras.readString(0)?.first?.ifEmpty { null }
}

/**
 * Returns the link/image destination URL, or `null` if:
 * - this node is not a [NodeType.Link] or [NodeType.Image], or
 * - extras are truncated / malformed.
 */
internal fun PackedAstNode.linkHrefExtra(): String? {
    if (type != NodeType.Link && type != NodeType.Image) return null
    return extras.readString(0)?.first
}

/**
 * Returns the link/image title, or `null` if:
 * - this node is not a [NodeType.Link] or [NodeType.Image],
 * - the title is empty, or
 * - extras are truncated / malformed.
 */
internal fun PackedAstNode.linkTitleExtra(): String? {
    if (type != NodeType.Link && type != NodeType.Image) return null
    val (_, next) = extras.readString(0) ?: return null
    return extras.readString(next)?.first?.ifEmpty { null }
}

/**
 * Returns `true` if the task-list checkbox is checked, `false` if unchecked,
 * or `null` if this node is not a [NodeType.TaskListMarker] or extras are empty.
 * Any byte value other than 1 — including unexpected values not defined by the
 * wire format — is treated as unchecked (returns `false`).
 */
internal fun PackedAstNode.taskCheckedExtra(): Boolean? {
    if (type != NodeType.TaskListMarker || extras.isEmpty()) return null
    return (extras[0].toInt() and 0xFF) == 1
}

/**
 * Returns the ordered-list start number, or `null` if:
 * - this node is not a [NodeType.ListOrdered], or
 * - extras are shorter than 8 bytes (malformed blob).
 *
 * Encoded as a u64 little-endian 8-byte value per tree_builder.rs.
 */
internal fun PackedAstNode.listStartExtra(): Long? {
    if (type != NodeType.ListOrdered || extras.size < 8) return null
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (extras[i].toLong() and 0xFF)
    return v
}

/**
 * Returns column alignment info for table nodes.
 *
 * **VERIFIED**: `attach_tag_extras` in `tree_builder.rs` has no `Tag::Table`
 * arm — alignment data is NOT written to the wire format. All real corpus
 * blobs have `extras_len=0` for Table nodes. This function returns `null`
 * when extras is empty (which it always is for real blobs), and returns `null`
 * for non-Table nodes.
 *
 * If a future Rust version ever starts encoding alignments, add the byte
 * mapping to [TableAlign] and implement the decoding here.
 */
internal fun PackedAstNode.tableAlignmentsExtra(): List<TableAlign>? {
    if (type != NodeType.Table || extras.isEmpty()) return null
    // Future: decode one byte per column when Rust starts writing alignment extras.
    // Dead path today — extras is always empty for Table nodes (no Tag::Table arm in
    // attach_tag_extras); implemented here for when Rust adds the Table arm.
    return extras.map { b -> TableAlign.fromByte(b.toInt() and 0xFF) }
}

/**
 * Column alignment for GFM tables.
 *
 * NOTE: as of the verified Rust source (tree_builder.rs), no alignment bytes
 * are written — this enum exists for future compatibility only.
 */
internal enum class TableAlign {
    NONE,
    LEFT,
    CENTER,
    RIGHT;

    companion object {
        fun fromByte(b: Int): TableAlign = when (b) {
            1 -> LEFT
            2 -> CENTER
            3 -> RIGHT
            else -> NONE
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Reads a LEB128-varint-prefixed UTF-8 string starting at [at].
 *
 * @return a pair of (decoded string, offset just past the string end), or
 *         `null` if the byte array is truncated / the varint is malformed.
 *
 * **Design note — never throws, always returns null on malformed input.**
 * Extras decoding is best-effort on the render path: a corrupt or
 * unrecognised extras blob must degrade gracefully, not crash. This
 * deliberately DIFFERS from [PackedAstNode.Companion.readVarint], which
 * throws on malformed node-tree data (node-tree decode is authoritative
 * and a corrupt tree is unrecoverable). Future authors must NOT
 * "harmonize" these two error strategies.
 */
private fun ByteArray.readString(at: Int): Pair<String, Int>? {
    var value = 0L
    var shift = 0
    var cursor = at
    while (true) {
        if (cursor >= size || shift > 63) return null
        val b = this[cursor].toInt() and 0xFF
        value = value or ((b and 0x7F).toLong() shl shift)
        cursor++
        if (b and 0x80 == 0) break
        shift += 7
    }
    if (value > Int.MAX_VALUE) return null
    val end = cursor + value.toInt()
    if (end > size) return null
    return String(this, cursor, value.toInt(), Charsets.UTF_8) to end
}
