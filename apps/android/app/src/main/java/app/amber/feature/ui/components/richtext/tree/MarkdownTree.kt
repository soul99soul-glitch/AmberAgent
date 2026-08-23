package app.amber.feature.ui.components.richtext.tree

/** Parser-agnostic markdown tree consumed by the renderer (spec: td-rust-1a §3). */
internal interface MdNode {
    val type: MdNodeType
    val startOffset: Int
    val endOffset: Int
    val children: List<MdNode>
    val parent: MdNode?
    fun nextSibling(): MdNode?

    /**
     * Pre-order DFS find by node type.
     *
     * **Contract (both implementations MUST follow — divergence = silent render differences):**
     * checks the receiver itself first; if its own type is in [types], returns `this`.
     * Otherwise searches descendants in pre-order DFS (check each child, recurse into it,
     * then next sibling). This matches JetBrains `ASTNode.findChildOfTypeRecursive` semantics
     * (Markdown.kt:2881-2888) and the call sites in Markdown.kt rely on self-inclusion
     * (e.g. line 942 / 2072: `child.findChildOfTypeRecursive(IMAGE, BLOCK_MATH)` where
     * child may itself be an IMAGE or BLOCK_MATH).
     */
    fun findChildOfTypeRecursive(vararg types: MdNodeType): MdNode?
    // Typed attributes — null when the node kind doesn't carry the attribute.
    // Carriers: headingLevel: Heading; codeLang: CodeBlock; linkHref/linkTitle: Link & Image;
    // imageSrc: Image; taskChecked: TaskListMarker; listStart: ListOrdered; tableAlignments: Table (currently always null — see TableAlign KDoc).
    val headingLevel: Int?
    val codeLang: String?
    val linkHref: String?
    val linkTitle: String?
    val imageSrc: String?
    val taskChecked: Boolean?
    val listStart: Long?
    val tableAlignments: List<TableAlign>?

    /**
     * Fenced-code discriminator (mapping doc §4 H4). JetBrains emits CODE_BLOCK (indented) and
     * CODE_FENCE (fenced) as two distinct types; the unified [MdNodeType.CodeBlock] collapses
     * them, so the renderer needs this flag to pick the simple-Text (indented) vs
     * HighlightCodeBlock (fenced) path. `codeLang == null` cannot discriminate because an
     * empty-info-string fence ALSO has null lang. False for non-code / indented nodes.
     */
    val isFencedCode: Boolean

    /**
     * Closing-fence end offset for the streaming `completeCodeBlock` position check
     * (mapping doc §4 H4 / KU-5: `CODE_FENCE_END.endOffset`). Null when the fence is unclosed
     * (streaming-truncated tail) or the node is not a fence.
     */
    val codeFenceEndOffset: Int?

    /**
     * Offset range of the fenced-code body (mapping doc §4 H4: the "back up to the last EOL"
     * computation the renderer applied in-arm — start = the EOL before the first
     * CODE_FENCE_CONTENT, end = the last CODE_FENCE_CONTENT's end). The renderer slices the body
     * with this range AND feeds the same start/end to `streamingCodeFenceLiveSuffixFor`, so the
     * raw offsets (not a pre-sliced string) are what it needs. Null when the node is not a
     * fenced block or carries no fence content (malformed fence → renderer renders nothing,
     * preserving the original three early-returns).
     */
    val codeFenceContentRange: IntRange?

    /**
     * Marker-free children for inline containers (mapping doc §4 H5 / H7). JetBrains includes
     * the literal markers as child tokens that all map to [MdNodeType.Unknown], so the renderer
     * cannot strip them by `MdNodeType` (every marker AND the inner content can be Unknown).
     * This accessor applies the original type-based trim using the REAL JetBrains marker types:
     *  - Emphasis  → strip 1 leading + 1 trailing `*`/`_` marker
     *  - Strong    → strip 2 leading + 2 trailing markers
     *  - Strikethrough → strip 2 leading + 2 trailing `~` markers
     *  - Link (AUTOLINK `<url>` form) → strip the `<` and `>` delimiter tokens
     *  - everything else → [children] unchanged
     * `NativeMdNode` returns [children] (the native tree carries no marker children).
     */
    val contentChildren: List<MdNode>

    /**
     * True only for the JetBrains AUTOLINK element (`<url>` angle-bracket form) — distinct from
     * an inline link `[text](url)` and a bare GFM autolink (a leaf). All three collapse to
     * [MdNodeType.Link]; the renderer needs this to pick the AUTOLINK render arm (italic
     * link over [contentChildren], no destination dig). False for non-Link nodes.
     * (mapping doc §4 H7.)
     */
    val isAutolink: Boolean

    /**
     * The link/image LINK_TEXT label text, RAW (brackets included), relocated from the
     * `findChildOfTypeRecursive(LINK_TEXT)?.getTextInNode()` digs (Markdown.kt image alt 1687,
     * inline-link label 2567). Call sites apply their own trimming:
     *  - image alt uses it as-is (`[alt]`),
     *  - inline-link label trims `[`/`]`.
     * Null when the node has no LINK_TEXT child. (mapping doc §3 E4/E5, §4 H6.)
     */
    val linkLabel: String?

    /**
     * The inner label token text for the block-level link arm (Markdown.kt 1600-1601:
     * `findChildOfTypeRecursive(LINK_TEXT)?.findChildOfTypeRecursive(GFM_AUTOLINK, TEXT)`), i.e.
     * the first autolink/text token INSIDE LINK_TEXT (no surrounding brackets, and for nested
     * inline content like `[*x*]` it is the innermost text "x", not "*x*"). Null when absent.
     * (mapping doc §4 H6.)
     */
    val linkInnerText: String?

    /**
     * True for the JetBrains BLOCK_QUOTE *marker token* (the `>` prefix), relocated from the
     * `type == MarkdownTokenTypes.BLOCK_QUOTE -> {}` no-op in Markdown.kt:2483 (mapping doc §4 H9).
     * The marker token collapses to [MdNodeType.Unknown] and cannot be distinguished from other
     * `>` tokens (a closing autolink delimiter, an escaped `\>`) by type or text, so the inline
     * walker uses this accessor to skip ONLY the genuine blockquote marker. False for everything
     * else; `NativeMdNode` returns false (the native tree emits no such marker token).
     */
    val isBlockquoteMarker: Boolean
}

/**
 * Extract the text span for this node. Never throws — malformed/reversed offsets degrade to the empty string
 * (render path must not crash on a corrupt tree).
 */
internal fun MdNode.textIn(source: String): String {
    val start = startOffset.coerceIn(0, source.length)
    val end = endOffset.coerceIn(start, source.length)
    return source.substring(start, end)
}

internal enum class MdNodeType {
    // ── Block ──────────────────────────────────────────────
    Root,            // JB MARKDOWN_FILE          | Rust Root
    Paragraph,       // JB PARAGRAPH (+ ATX_CONTENT via JvmMdNode) | Rust Paragraph
    Heading,         // JB ATX_1..ATX_6 (level via headingLevel)   | Rust Heading
    Blockquote,      // JB BLOCK_QUOTE (element)  | Rust Blockquote
    CodeBlock,       // JB CODE_BLOCK + CODE_FENCE (lang via codeLang) | Rust CodeBlock
    ListOrdered,     // JB ORDERED_LIST (start via listStart) | Rust ListOrdered
    ListUnordered,   // JB UNORDERED_LIST         | Rust ListUnordered
    ListItem,        // JB LIST_ITEM              | Rust ListItem
    Table,           // JB GFM TABLE              | Rust Table
    TableHead,       // JB GFM HEADER (structural)| Rust TableHead
    TableRow,        // JB GFM ROW (structural)   | Rust TableRow
    TableCell,       // JB GFM CELL (structural)  | Rust TableCell
    HorizontalRule,  // JB HORIZONTAL_RULE (token)| Rust HorizontalRule
    HtmlBlock,       // JB HTML_BLOCK             | Rust HtmlBlock

    // ── Inline ─────────────────────────────────────────────
    Emphasis,        // JB EMPH                   | Rust Emphasis
    Strong,          // JB STRONG                 | Rust Strong
    Strikethrough,   // JB GFM STRIKETHROUGH      | Rust Strikethrough
    Link,            // JB INLINE_LINK + AUTOLINK + GFM_AUTOLINK (href/title via accessors) | Rust Link
    Image,           // JB IMAGE (src via imageSrc) | Rust Image
    InlineCode,      // JB CODE_SPAN              | Rust InlineCode
    InlineHtml,      // (no JB match site; native only) | Rust InlineHtml
    MathInline,      // JB GFM INLINE_MATH        | Rust MathInline
    MathBlock,       // JB GFM BLOCK_MATH         | Rust MathBlock
    FootnoteRef,     // (no JB match site; native only) | Rust FootnoteRef
    FootnoteDef,     // (no JB match site; native only) | Rust FootnoteDef
    TaskListMarker,  // JB GFM CHECK_BOX (checked via taskChecked) | Rust TaskListMarker

    // ── Leaf text ──────────────────────────────────────────
    Text,            // JB TEXT (+ other JB leaf tokens) | Rust Text
    SoftBreak,       // (no JB match site; native only) | Rust SoftBreak
    HardBreak,       // (no JB match site; native only) | Rust HardBreak

    // ── Sentinel ───────────────────────────────────────────
    Unknown,         // any unmapped JB token (EOL, markers, BLOCK_QUOTE-token, …) | Rust Unknown
}

/**
 * Column alignment for GFM tables.
 *
 * NOTE: as of the verified Rust source (tree_builder.rs), no alignment bytes
 * are written — this enum exists for future compatibility only.
 *
 * Relocated here from [app.amber.agent.ui.components.richtext.nativebridge.PackedAstExtras]
 * as the single definition (spec: td-rust-1a §3, single-definition rule).
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
