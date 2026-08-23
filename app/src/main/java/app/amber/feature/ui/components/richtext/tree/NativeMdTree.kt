package app.amber.feature.ui.components.richtext.tree

import app.amber.agent.ui.components.richtext.nativebridge.NodeType
import app.amber.agent.ui.components.richtext.nativebridge.PackedAstNode
import app.amber.agent.ui.components.richtext.nativebridge.PackedAstReader
import app.amber.agent.ui.components.richtext.nativebridge.codeFenceKindExtra
import app.amber.agent.ui.components.richtext.nativebridge.codeLangExtra
import app.amber.agent.ui.components.richtext.nativebridge.headingLevelExtra
import app.amber.agent.ui.components.richtext.nativebridge.linkHrefExtra
import app.amber.agent.ui.components.richtext.nativebridge.linkTitleExtra
import app.amber.agent.ui.components.richtext.nativebridge.listStartExtra
import app.amber.agent.ui.components.richtext.nativebridge.tableAlignmentsExtra
import app.amber.agent.ui.components.richtext.nativebridge.taskCheckedExtra

/**
 * Packed-AST-backed implementation of [MdNode] (TD.Rust.1a Task 12 / Stage 4).
 *
 * The parity-critical twin of [JvmMdNode]. Where [JvmMdNode] wraps an `org.intellij.markdown`
 * `ASTNode` and digs JetBrains child tokens, this adapter wraps a single [PackedAstNode] decoded
 * from the Rust `markdown-parser` crate's binary blob and reads packed extras. Both feed the SAME
 * renderer through the [MdNode] interface; every accessor below must produce a render-equivalent
 * value to its JVM twin, or the Stage-4 tree switch regresses.
 *
 * Source of truth: `docs/td-rust-1a-nodetype-mapping.md` (gate-reviewed) and the Rust wire
 * builder `native/markdown-parser/src/tree_builder.rs`. The [NodeType] → [MdNodeType] mapping in
 * [toMdNodeType] is near-1:1 (mapping doc §2); [NodeType.Unknown] → [MdNodeType.Unknown].
 *
 * ### Offsets are CHAR offsets at the interface; byte basis is internal (KU-2)
 * pulldown-cmark reports `range.start/range.end` as UTF-8 BYTE offsets into the source; the blob
 * stores them verbatim, so [PackedAstNode.startOffset]/[endOffset] are byte offsets. The [MdNode]
 * interface contract, however, is Kotlin UTF-16 CHAR offsets (`textIn` does `String.substring`,
 * and [JvmMdNode] already returns char offsets). To make the contract uniform across both
 * implementations, [NativeMdNode] translates byte→char INSIDE the tree: [startOffset]/[endOffset]
 * return TRANSLATED char offsets, so `source.substring(startOffset, endOffset)` is render-faithful
 * for any source — CJK, emoji, em-dash, Greek, arrows — not only pure ASCII.
 *
 * The translation table ([ByteToCharTranslator]) is built ONCE per tree (via [nativeMdTreeOrNull])
 * and shared by every node. For pure-ASCII sources byte index == char index, so the translator is
 * `null` (identity, zero overhead). Out-of-range byte offsets clamp to the source length and never
 * throw.
 *
 * ### Structural differences from JetBrains handled here (mapping doc §4)
 * The native tree is cleaner: no marker tokens (`*`/`**`/`~~`/`<`/`>`), no `ATX_CONTENT` wrapper,
 * no `LINK_TEXT`/`LINK_DESTINATION`/`FENCE_LANG`/`CODE_FENCE_*` dig tokens, no `BLOCK_QUOTE` marker
 * leaf. Consequently most Stage-3 accessors are TRIVIAL on this side (see each accessor's KDoc).
 *
 * Not thread-confined; holds no mutable state beyond a lazily-computed children list.
 */
internal class NativeMdNode internal constructor(
    private val packed: PackedAstNode,
    private val source: String,
    override val parent: NativeMdNode?,
    /**
     * Shared byte→char translator for this whole tree, built once by [nativeMdTreeOrNull]. `null`
     * means the source is pure ASCII (identity translation). Every node carries the same reference.
     */
    private val translator: ByteToCharTranslator?,
) : MdNode {

    override val type: MdNodeType = packed.type.toMdNodeType()

    /** UTF-16 char offset (translated from the packed UTF-8 byte offset). */
    override val startOffset: Int get() = translateOffset(packed.startOffset)

    /** UTF-16 char offset (translated from the packed UTF-8 byte offset). */
    override val endOffset: Int get() = translateOffset(packed.endOffset)

    private fun translateOffset(byteOffset: Int): Int =
        translator?.toCharOffset(byteOffset) ?: byteOffset.coerceIn(0, source.length)

    override val children: List<MdNode> by lazy {
        packed.children.map { NativeMdNode(it, source, this, translator) }
    }

    /**
     * Next sibling under the same parent. Delegates to [PackedAstNode.nextSibling] (O(1) via the
     * cached index) and re-wraps the result against this node's parent so the returned [MdNode]
     * carries the right parent identity. Mirrors [JvmMdNode.nextSibling] / the interface contract
     * (mapping doc F3).
     */
    override fun nextSibling(): MdNode? {
        val parentChildren = parent?.children ?: return null
        // Find this node's index by packed-node identity, then return the next wrapped sibling.
        for (i in parentChildren.indices) {
            if ((parentChildren[i] as? NativeMdNode)?.packed === packed) {
                return if (i + 1 < parentChildren.size) parentChildren[i + 1] else null
            }
        }
        return null
    }

    /**
     * Pre-order DFS find by type honoring the SELF-FIRST contract (interface KDoc / mapping doc
     * F4): returns `this` when its own type is in [types], else the first matching descendant in
     * child order. Implemented directly over the wrapped tree (rather than delegating to
     * [PackedAstNode.findChildOfTypeRecursive]) so the returned node is a [NativeMdNode] with the
     * correct parent identity. The reverse [MdNodeType] → [NodeType] mapping is many-to-one only
     * for table parts, but here we match on the already-wrapped [MdNode.type] so no reverse table
     * is needed.
     */
    override fun findChildOfTypeRecursive(vararg types: MdNodeType): MdNode? {
        if (this.type in types) return this
        for (child in children) {
            val result = child.findChildOfTypeRecursive(*types)
            if (result != null) return result
        }
        return null
    }

    // ── Typed accessors — delegate to the verified PackedAstExtras decoders ────────────────────

    /** Heading level 1..6 from extras byte (mapping doc E1). Null for non-heading. */
    override val headingLevel: Int? get() = packed.headingLevelExtra()

    /**
     * Fenced-code language from extras (mapping doc E3). `null` for indented code AND for an
     * empty-info-string fence (Rust encodes `""` for both → decoder maps empty → null), matching
     * the JVM side where neither emits a FENCE_LANG token.
     */
    override val codeLang: String? get() = packed.codeLangExtra()

    /** Link/Image destination URL from extras (mapping doc E5/E4). Null for non-link/image. */
    override val linkHref: String? get() = packed.linkHrefExtra()

    /**
     * Link/Image title from extras. The renderer NEVER reads link titles today (mapping doc §6
     * linkTitle note), so this has no render effect; decoded here for interface completeness. Null
     * when the title is empty or the node is not a link/image — consistent with the JVM side's
     * always-null behavior for the live render path.
     */
    override val linkTitle: String? get() = packed.linkTitleExtra()

    /** Image source URL — images reuse the link-destination extras slot (mapping doc E4). */
    override val imageSrc: String? get() = packed.linkHrefExtra()

    /**
     * Task-checkbox checked state from the extras byte (mapping doc E6 / H2).
     *
     * **PARITY DIVERGENCE (headline).** pulldown-cmark decides checked-ness itself and is
     * CASE-INSENSITIVE: `[X]` (uppercase) is `checked = true`. The JVM path does a case-SENSITIVE
     * `getTextInNode().trim() == "[x]"`, so `[X]` is `false` there (see [JvmMdNode.taskChecked]).
     * On the `[X]` corpus sample (15-task-lists.md "Case Sensitivity") the two trees therefore
     * disagree: native = checked, JVM = unchecked. pulldown is GFM-correct (matches the sample's
     * stated intent). This is a real Stage-4 controller decision, pinned by both tree tests with
     * opposite expectations for the same input.
     */
    override val taskChecked: Boolean? get() = packed.taskCheckedExtra()

    /** Ordered-list start number from the u64-LE extras (mapping doc E2). Null for non-ordered. */
    override val listStart: Long? get() = packed.listStartExtra()

    /**
     * Table column alignments — null on both trees (mapping doc §5). The Rust wire builder writes
     * NO Table extras, so [tableAlignmentsExtra] returns null for every real blob; the renderer
     * never inspects alignment.
     */
    override val tableAlignments: List<TableAlign>? get() = packed.tableAlignmentsExtra()

    /**
     * Fenced vs indented discriminator (mapping doc §4 H4). The collapsed [MdNodeType.CodeBlock]
     * needs this to pick the renderer's HighlightCodeBlock (fenced) vs plain-Text (indented) arm
     * (Markdown.kt:1776-1820).
     *
     * **Primary path — kind byte from extras (T12.5).** pulldown-cmark's `CodeBlockKind` is now
     * encoded as a trailing byte in the CodeBlock extras (after the lang string): `1u8` = Fenced,
     * `0u8` = Indented. [codeFenceKindExtra] decodes this byte and returns the authoritative
     * ground-truth from the parser. This eliminates the false-positive class where an indented
     * block whose first content line is a literal ``` ``` ``` fence marker would be misclassified.
     *
     * **Null-fallback — heuristic for old blobs.** When [codeFenceKindExtra] returns `null` (blob
     * generated before the layout-extension), the legacy heuristic applies:
     *  - a non-empty [codeLang] is unambiguously fenced (only fences carry an info string), AND
     *  - a fenced block's node span starts at the opening fence, so its [textIn] (after leading
     *    whitespace) starts with ``` ``` ``` or `~~~`; an indented block's span begins at the
     *    de-indented first code char (verified on 07/08 = fenced, 09 = indented).
     * The heuristic also classifies the unclosed streaming fence (sample 26) as fenced. False for
     * non-CodeBlock nodes.
     */
    override val isFencedCode: Boolean
        get() {
            if (packed.type != NodeType.CodeBlock) return false
            // Primary path: authoritative kind byte from the wire extras (T12.5).
            val kindFromExtras = packed.codeFenceKindExtra()
            if (kindFromExtras != null) return kindFromExtras
            // Null-fallback: old blob without kind byte — use legacy heuristic.
            if (codeLang != null) return true
            val text = textIn(source).trimStart()
            return text.startsWith("```") || text.startsWith("~~~")
        }

    /**
     * Closing-fence end offset for the streaming `completeCodeBlock` position check
     * (mapping doc §4 H4 / KU-5). The renderer compares `sourceOffsetBase + this <=
     * syntheticSuffixStart` (Markdown.kt:1801) to decide whether the fence is closed.
     *
     * **Native derivation.** pulldown's fenced CodeBlock node span runs through the closing fence
     * when one exists, so the closed-fence end offset is the node's [endOffset]. When the fence is
     * never closed (streaming-truncated tail, sample 26), the node text carries no closing fence —
     * return null so the renderer treats the block as still-open, mirroring the JVM side's null
     * `CODE_FENCE_END`. Null for non-fenced nodes.
     */
    override val codeFenceEndOffset: Int?
        get() {
            if (!isFencedCode) return null
            // Closed iff the trimmed node text ends with a fence marker (the closing fence is part
            // of the node span). A truncated tail (no closing fence) → null.
            val text = textIn(source).trimEnd()
            return if (text.endsWith("```") || text.endsWith("~~~")) endOffset else null
        }

    /**
     * Offset range of the fenced-code body (mapping doc §4 H4). The renderer slices the body with
     * `content.substring(range.first, range.last + 1)` and feeds the same start/end to
     * `streamingCodeFenceLiveSuffixFor` (Markdown.kt:1793-1809), so the RAW offsets are what it
     * needs — not a pre-sliced string.
     *
     * **Native derivation (golden-blob evidence).** pulldown emits the code body as child `Text`
     * leaf node(s) under the CodeBlock; the body spans from the first child's [startOffset] to the
     * last child's [endOffset] (verified: 07 fenced = single Text [213..783] excluding the fence
     * lines; 09 indented = several Text leaves whose union excludes nothing but the body). This is
     * the same body string the JVM path produces from its "back up to the last EOL" computation —
     * both feed `.trimIndent()` and match (design H4 / tests 07/08). The range is
     * `firstChild.startOffset until lastChild.endOffset` (i.e. `IntRange(firstStart, lastEnd-1)` so
     * the renderer's `+1` recovers `lastEnd`).
     *
     * Null when the node is not fenced or carries no child content (malformed/empty fence →
     * renderer renders nothing, preserving the JVM arm's early-returns).
     */
    override val codeFenceContentRange: IntRange?
        get() {
            if (!isFencedCode) return null
            // Use the WRAPPED children so the range is in translated CHAR offsets — the renderer
            // slices the char-indexed source with this range, so raw packed BYTE offsets would be
            // wrong on non-ASCII code bodies (CJK comments etc.).
            val kids = children
            val first = kids.firstOrNull() ?: return null
            val last = kids.last()
            if (last.endOffset <= first.startOffset) return null
            return first.startOffset until last.endOffset
        }

    /**
     * Marker-free children for inline containers (mapping doc §4 H5/H7). The native tree carries NO
     * marker child tokens (pulldown's Emphasis/Strong/Strikethrough/autolink nodes contain only
     * content), so this is simply [children] — the JVM-only `trim()` is a no-op here.
     */
    override val contentChildren: List<MdNode> get() = children

    /**
     * True for the angle-bracket autolink form (`<url>` / `<email>`) — the native analogue of the
     * JetBrains AUTOLINK element (mapping doc §4 H7). pulldown normalizes `<url>` to a [Link] whose
     * span starts with `<` and ends with `>` (verified on 04: `<https://example.com>`,
     * `<user@example.com>`), distinct from an inline link `[t](<url>)` (span starts with `[`) and a
     * bare GFM autolink (a childless leaf). The renderer uses this to pick the italic-autolink arm
     * (Markdown.kt:1605, 2610) instead of the inline-link/pill arm. False for non-Link nodes.
     */
    override val isAutolink: Boolean
        get() {
            if (packed.type != NodeType.Link) return false
            val text = textIn(source)
            return text.startsWith("<") && text.endsWith(">")
        }

    /**
     * The link/image visible label (mapping doc §3 E4/E5, §4 H6). JetBrains digs the raw
     * `LINK_TEXT` token (brackets included); pulldown has no such child, so the native label is the
     * concatenated [textIn] of the Link/Image node's children (verified: 04 `[link text](…)` →
     * child Text "link text"; 06 image → child Text "Android robot logo").
     *
     * **Note on the bracket difference.** The JVM `linkLabel` includes the surrounding `[ ]`; the
     * native concatenation does not. The inline-link call site trims `[`/`]` anyway
     * (Markdown.kt:2616), so that path is parity-equivalent. The image-alt call site uses the label
     * as-is (Markdown.kt:1712), so native alt text lacks the brackets the JVM alt carries — but alt
     * text is non-visible (accessibility metadata), so this does not affect rendered output.
     *
     * Null when the node has no children (e.g. an empty-text link `[](url)`), matching the
     * "no LINK_TEXT child → null" JVM contract.
     */
    override val linkLabel: String?
        get() {
            if (packed.type != NodeType.Link && packed.type != NodeType.Image) return null
            val kids = children
            if (kids.isEmpty()) return null
            return kids.joinToString("") { it.textIn(source) }
        }

    /**
     * The inner label text for the block-level inline-link arm (mapping doc §4 H6). JetBrains digs
     * `LINK_TEXT → first GFM_AUTOLINK/TEXT` — the first text token inside the label, with no
     * brackets and, for nested inline content like `[*x*]`, the innermost text ("x", not "*x*").
     *
     * **Native derivation.** The native equivalent is the first [MdNodeType.Text] leaf in pre-order
     * DFS under this Link (verified: 04 `[link text]` → "link text"; `[*Italic link text*]` → Link
     * › Emphasis › Text "Italic link text" — the innermost text, matching JVM). Null when the Link
     * has no Text descendant (e.g. a link wrapping only an image or inline code).
     */
    override val linkInnerText: String?
        get() {
            if (packed.type != NodeType.Link) return null
            val textNode = firstTextDescendant(this) ?: return null
            return textNode.textIn(source)
        }

    /**
     * Always false on the native side (interface KDoc / mapping doc §4 H9 ERRATA). pulldown emits
     * NO blockquote-marker leaf token — the `>` prefix never appears as a node — so there is
     * nothing to skip. The dedicated skip arm exists only to suppress the JetBrains BLOCK_QUOTE
     * marker token, which the native tree never produces.
     */
    override val isBlockquoteMarker: Boolean get() = false

    private companion object {
        /** First [MdNodeType.Text] leaf in pre-order DFS under [start] (self included). */
        fun firstTextDescendant(start: MdNode): MdNode? {
            if (start.type == MdNodeType.Text) return start
            for (child in start.children) {
                val found = firstTextDescendant(child)
                if (found != null) return found
            }
            return null
        }
    }
}

/**
 * Build a [NativeMdNode] root over [reader] for the given [source], with the byte→char translator
 * computed EXACTLY ONCE and shared by every node in the tree.
 *
 * Returns `null` when the reader has no decodable root (`reader.root() == null`) — the caller falls
 * back to the JVM path. Construction always goes through this factory so the translation table is
 * never built per-node.
 *
 * Precondition: [source] is the same string the blob was generated from (the preprocessed markdown
 * on the production funnel). [reader] should already be validated (`isValid && validate()`) by the
 * caller; this factory does not re-validate.
 */
internal fun nativeMdTreeOrNull(reader: PackedAstReader, source: String): NativeMdNode? {
    val root = reader.root() ?: return null
    return NativeMdNode(root, source, parent = null, translator = ByteToCharTranslator.of(source))
}

/**
 * Translates UTF-8 byte offsets (the packed-AST basis) into Kotlin UTF-16 char offsets (the
 * [MdNode] interface basis). Built once per tree and shared by all nodes.
 *
 * For a pure-ASCII source byte index == char index, so [of] returns `null` (callers treat `null`
 * as identity — no table, no per-offset lookup). For non-ASCII sources [of] precomputes a dense
 * `utf8-byte-index → utf16-char-index` map by iterating the string by CODE POINTS: each code point
 * contributes 1–4 UTF-8 bytes and 1–2 UTF-16 units (a code point above U+FFFF is a surrogate pair,
 * i.e. 4 UTF-8 bytes ↔ 2 UTF-16 chars). The map has `utf8ByteLength + 1` entries so the inclusive
 * end-of-string byte offset is representable. Out-of-range byte offsets clamp to the source length.
 */
internal class ByteToCharTranslator private constructor(
    /** `byteToChar[b]` = UTF-16 char index for UTF-8 byte index `b`. Size = utf8ByteLength + 1. */
    private val byteToChar: IntArray,
) {
    /**
     * Char offset for [byteOffset], clamping out-of-range inputs (never throws).
     * Byte offsets past the end of the source clamp to the final sentinel entry, which equals
     * the source's char length — equivalent to `source.length` as a char offset.
     */
    fun toCharOffset(byteOffset: Int): Int {
        if (byteOffset <= 0) return 0
        if (byteOffset >= byteToChar.size) return byteToChar[byteToChar.size - 1]
        return byteToChar[byteOffset]
    }

    companion object {
        /**
         * Returns a translator for [source], or `null` when [source] is pure ASCII (identity).
         * `null` is the fast path: no allocation, callers skip the lookup entirely.
         */
        fun of(source: String): ByteToCharTranslator? {
            if (source.all { it.code < 128 }) return null

            // Total UTF-8 byte length, computed without allocating the byte array.
            var utf8Len = 0
            var i = 0
            while (i < source.length) {
                val cp = source.codePointAt(i)
                utf8Len += utf8ByteCount(cp)
                i += Character.charCount(cp)
            }

            val map = IntArray(utf8Len + 1)
            var bytePos = 0
            var charPos = 0
            i = 0
            while (i < source.length) {
                val cp = source.codePointAt(i)
                val nBytes = utf8ByteCount(cp)
                val nChars = Character.charCount(cp) // 1 (BMP) or 2 (surrogate pair)
                // Every byte that belongs to this code point maps to the code point's START char.
                for (b in 0 until nBytes) {
                    map[bytePos + b] = charPos
                }
                bytePos += nBytes
                charPos += nChars
                i += nChars
            }
            // Sentinel: the byte position just past the last code point maps to the char length.
            map[bytePos] = charPos
            return ByteToCharTranslator(map)
        }

        /** UTF-8 byte width of a Unicode code point. */
        private fun utf8ByteCount(cp: Int): Int = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
    }
}

/**
 * `NodeType → MdNodeType` mapping — exhaustive (plan Task 12 requires a total `when`). Near-1:1
 * per mapping doc §2; [NodeType.Unknown] → [MdNodeType.Unknown]. Every wire variant has a 1:1
 * [MdNodeType] counterpart (the enums were co-derived in §6), so the reverse mapping needed by the
 * structural table walk is also 1:1 — no many-to-one table-part collapsing occurs on the native
 * side (the JetBrains-only collapses — ATX_CONTENT→Paragraph, etc. — have no native analogue).
 */
private fun NodeType.toMdNodeType(): MdNodeType = when (this) {
    // ── Block ──────────────────────────────────────────────
    NodeType.Root -> MdNodeType.Root
    NodeType.Paragraph -> MdNodeType.Paragraph
    NodeType.Heading -> MdNodeType.Heading
    NodeType.Blockquote -> MdNodeType.Blockquote
    NodeType.CodeBlock -> MdNodeType.CodeBlock
    NodeType.ListOrdered -> MdNodeType.ListOrdered
    NodeType.ListUnordered -> MdNodeType.ListUnordered
    NodeType.ListItem -> MdNodeType.ListItem
    NodeType.Table -> MdNodeType.Table
    NodeType.TableHead -> MdNodeType.TableHead
    NodeType.TableRow -> MdNodeType.TableRow
    NodeType.TableCell -> MdNodeType.TableCell
    NodeType.HorizontalRule -> MdNodeType.HorizontalRule
    NodeType.HtmlBlock -> MdNodeType.HtmlBlock

    // ── Inline ─────────────────────────────────────────────
    NodeType.Emphasis -> MdNodeType.Emphasis
    NodeType.Strong -> MdNodeType.Strong
    NodeType.Strikethrough -> MdNodeType.Strikethrough
    NodeType.Link -> MdNodeType.Link
    NodeType.Image -> MdNodeType.Image
    NodeType.InlineCode -> MdNodeType.InlineCode
    NodeType.InlineHtml -> MdNodeType.InlineHtml
    NodeType.MathInline -> MdNodeType.MathInline
    NodeType.MathBlock -> MdNodeType.MathBlock
    NodeType.FootnoteRef -> MdNodeType.FootnoteRef
    NodeType.FootnoteDef -> MdNodeType.FootnoteDef
    NodeType.TaskListMarker -> MdNodeType.TaskListMarker

    // ── Leaf text ──────────────────────────────────────────
    NodeType.Text -> MdNodeType.Text
    NodeType.SoftBreak -> MdNodeType.SoftBreak
    NodeType.HardBreak -> MdNodeType.HardBreak

    // ── Sentinel ───────────────────────────────────────────
    NodeType.Unknown -> MdNodeType.Unknown
}
