package app.amber.agent.ui.components.richtext.nativebridge

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge to the `markdown-parser` Rust crate.
 *
 * Returns a packed binary AST blob (see SPIKE_PLAN §4.2 for wire format).
 * Decode via [PackedAstReader].
 *
 * Phase 1 (PR #9) shipped this bridge without production wiring — benchmarks
 * called it directly. Phase 2 Step 4 wires the HTML emit path through the
 * sibling [MarkdownNativeSwitch] (called from `MarkdownNew.kt`'s
 * `generateMarkdownHtml`). Phase 2 Step 5 will wire the packed-AST path
 * through the same Switch when the JetBrains-tree-builder caller in
 * `Markdown.kt` is ready to consume the binary format.
 */
internal object MarkdownParserNative {

    private const val TAG = "MarkdownParserNative"
    private const val LIB_NAME = "markdown_parser"

    private val loaded = AtomicBoolean(false)
    @Volatile private var loadError: Throwable? = null

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded.get()
        }

    /** See [app.amber.document.nativebridge.OfficeParserNative.lastLoadError]. */
    internal fun lastLoadError(): Throwable? = loadError

    private fun ensureLoaded() {
        if (loaded.get() || loadError != null) return
        synchronized(this) {
            if (loaded.get() || loadError != null) return
            try {
                System.loadLibrary(LIB_NAME)
                loaded.set(true)
                Log.i(TAG, "loaded native library: $LIB_NAME")
            } catch (t: Throwable) {
                loadError = t
                Log.w(TAG, "failed to load native library $LIB_NAME — will fall back to JVM", t)
            }
        }
    }

    /**
     * @return packed AST byte array, or `null` if native is unavailable / errored.
     *         Aligns with the other nativebridge adapters' `T?` convention so
     *         callers can do `parse(text) ?: return jvmFallback(text)`
     *         (cross-component review V2 P2 fix).
     */
    fun parse(text: String): ByteArray? {
        ensureLoaded()
        if (!loaded.get()) return null
        val blob = parseMarkdownNative(text)
        return if (blob.isEmpty()) null else blob
    }

    /**
     * Single-pass markdown → HTML conversion (Component #8). Replaces the
     * MarkdownNew.kt JetBrains HtmlGenerator path. Returns `null` if native
     * is unavailable / errored — caller MUST fall back to the JVM HtmlGenerator.
     */
    fun renderHtml(text: String): String? {
        ensureLoaded()
        if (!loaded.get()) return null
        return parseMarkdownToHtmlNative(text)
    }

    @JvmStatic
    private external fun parseMarkdownNative(text: String): ByteArray

    @JvmStatic
    private external fun parseMarkdownToHtmlNative(text: String): String?
}

/**
 * Decoder for the packed AST format. Lazy: decoding a child only happens when
 * the consumer touches it. The wire format is described in SPIKE_PLAN §4.2.
 *
 * Wire layout recap:
 * ```
 * header: 'PMDA' + u8 version + u8 flags + u16 reserved   (8 bytes)
 * body:   depth-first nodes; each = u8 tag + varint start + varint endDelta
 *         + varint extrasLen + extras + varint childCount + children
 * ```
 */
class PackedAstReader(private val blob: ByteArray) {

    val isValid: Boolean
    val version: Int
    val hasHtmlBlocks: Boolean

    init {
        if (blob.size < 8 || blob[0] != 'P'.code.toByte() || blob[1] != 'M'.code.toByte()
            || blob[2] != 'D'.code.toByte() || blob[3] != 'A'.code.toByte()
        ) {
            isValid = false
            version = -1
            hasHtmlBlocks = false
        } else {
            val rawVersion = blob[4].toInt() and 0xFF
            version = rawVersion
            // Reject wire formats we don't know how to read — Phase 1 ships
            // schema v1. Forward-compat is opt-in (bump SUPPORTED_VERSION when
            // a backwards-compatible v2 reader lands).
            isValid = rawVersion == SUPPORTED_VERSION
            hasHtmlBlocks = (blob[5].toInt() and 0x01) != 0
        }
    }

    /** Root node, or null if [isValid] is false / blob truncated. */
    fun root(): PackedAstNode? {
        if (!isValid || blob.size < 9) return null
        return PackedAstNode(blob, offset = 8, parent = null, indexInParent = -1)
    }

    companion object {
        private const val SUPPORTED_VERSION = 1
    }
}

/**
 * Lazy view onto a single node in the packed blob. Constructs child nodes
 * on-demand by re-scanning the blob from this node's offset — that's O(N)
 * worst case per traversal but each value is computed only once via the
 * `by lazy` properties.
 */
class PackedAstNode internal constructor(
    private val blob: ByteArray,
    private val offset: Int,
    val parent: PackedAstNode?,
    /**
     * 0-based position within [parent].children, or -1 for the root. Cached
     * at construction so [nextSibling] is O(1) instead of O(parent-children-count).
     */
    private val indexInParent: Int,
) {

    /** Raw tag byte; map to [NodeType] for typed access. */
    val typeCode: Int by lazy { (blob[offset].toInt() and 0xFF) }

    val type: NodeType by lazy { NodeType.fromCode(typeCode) }

    private val header: NodeHeader by lazy { readHeader() }

    /** Byte offset into the original markdown text. */
    val startOffset: Int by lazy { header.start }

    /** Byte offset into the original markdown text. */
    val endOffset: Int by lazy { header.start + header.endDelta }

    val extras: ByteArray by lazy { header.extras }

    val children: List<PackedAstNode> by lazy {
        val list = ArrayList<PackedAstNode>(header.childCount)
        var cursor = header.bodyStart
        for (i in 0 until header.childCount) {
            list.add(PackedAstNode(blob, cursor, this, indexInParent = i))
            cursor = skipNode(blob, cursor)
        }
        list
    }

    /** O(1) sibling lookup — required for deep-tree traversal patterns. */
    fun nextSibling(): PackedAstNode? {
        val p = parent ?: return null
        val nextIdx = indexInParent + 1
        if (nextIdx < 0 || nextIdx >= p.children.size) return null
        return p.children[nextIdx]
    }

    /**
     * Iterative **DFS pre-order** find by type.
     *
     * Checks the receiver itself first (JetBrains `ASTNode.findChildOfTypeRecursive` semantics —
     * `if (this.type in types) return this`), then searches descendants in pre-order DFS
     * (check each child, recurse into it, then next sibling). The depth budget applies to
     * descendants only; the self-check is unconditional.
     *
     * BFS would have been a behaviour change vs JetBrains traversal order (review Round 3 P2
     * fix). Depth budget caps both stack growth and worst-case work.
     */
    fun findChildOfTypeRecursive(vararg types: NodeType): PackedAstNode? {
        if (this.type in types) return this
        val maxDepth = MAX_TRAVERSAL_DEPTH
        // Stack of (node, depth-from-this). Push children in reverse so the
        // first child ends up on top → popped next.
        val stack: ArrayDeque<Pair<PackedAstNode, Int>> = ArrayDeque()
        for (child in children.asReversed()) stack.addLast(child to 1)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (node.type in types) return node
            if (depth >= maxDepth) continue
            for (grandchild in node.children.asReversed()) {
                stack.addLast(grandchild to depth + 1)
            }
        }
        return null
    }

    private fun readHeader(): NodeHeader {
        var cursor = offset + 1
        val (start, c1) = readVarint(blob, cursor); cursor = c1
        val (delta, c2) = readVarint(blob, cursor); cursor = c2
        val (extrasLen, c3) = readVarint(blob, cursor); cursor = c3
        val extrasStart = cursor
        cursor += extrasLen.toInt()
        val (childCount, c4) = readVarint(blob, cursor); cursor = c4
        val extras = ByteArray(extrasLen.toInt()) { i -> blob[extrasStart + i] }
        return NodeHeader(
            start = start.toInt(),
            endDelta = delta.toInt(),
            extras = extras,
            childCount = childCount.toInt(),
            bodyStart = cursor,
        )
    }

    private data class NodeHeader(
        val start: Int,
        val endDelta: Int,
        val extras: ByteArray,
        val childCount: Int,
        val bodyStart: Int,
    )

    companion object {
        const val MAX_TRAVERSAL_DEPTH: Int = 200

        /**
         * Walk a node and its descendants and return the byte offset just past
         * the final child. Iterative impl so deeply-nested markdown can't
         * blow the JVM stack (review P2 fix).
         */
        internal fun skipNode(blob: ByteArray, start: Int): Int {
            var cursor = start
            // Stack of "children left to skip" at each open frame. We start
            // with a synthetic frame of 1 so the outer loop processes exactly
            // one node + all its descendants.
            val remaining: ArrayDeque<Long> = ArrayDeque()
            remaining.addLast(1L)
            while (remaining.isNotEmpty()) {
                val top = remaining.removeLast() - 1L
                if (top > 0) remaining.addLast(top)

                cursor += 1                                      // tag byte
                val (_, c1) = readVarint(blob, cursor); cursor = c1   // start
                val (_, c2) = readVarint(blob, cursor); cursor = c2   // end_delta
                val (extrasLen, c3) = readVarint(blob, cursor); cursor = c3
                cursor += extrasLen.toInt()
                val (childCount, c4) = readVarint(blob, cursor); cursor = c4
                if (childCount > 0L) remaining.addLast(childCount)
            }
            return cursor
        }

        private fun readVarint(blob: ByteArray, start: Int): Pair<Long, Int> {
            var value = 0L
            var shift = 0
            var cursor = start
            while (true) {
                val byte = blob[cursor].toInt() and 0xFF
                value = value or ((byte and 0x7F).toLong() shl shift)
                cursor++
                if (byte and 0x80 == 0) return value to cursor
                shift += 7
                if (shift > 63) error("varint too long at offset $start")
            }
        }
    }
}

/** Symbolic names for the u8 tag codes in the wire format. */
enum class NodeType(val code: Int) {
    Root(0),
    Paragraph(1),
    Heading(2),
    Blockquote(3),
    CodeBlock(4),
    ListOrdered(5),
    ListUnordered(6),
    ListItem(7),
    Table(8),
    TableHead(9),
    TableRow(10),
    TableCell(11),
    HorizontalRule(12),
    HtmlBlock(13),
    // code 14 reserved (see type_mapping.rs note) — Rust never emits it.
    Emphasis(30),
    Strong(31),
    Strikethrough(32),
    Link(33),
    Image(34),
    InlineCode(35),
    InlineHtml(36),
    MathInline(37),
    MathBlock(38),
    FootnoteRef(39),
    FootnoteDef(40),
    TaskListMarker(41),
    Text(100),
    SoftBreak(101),
    HardBreak(102),
    Unknown(-1);

    companion object {
        fun fromCode(code: Int): NodeType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}
