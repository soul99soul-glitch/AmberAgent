package app.amber.feature.ui.components.richtext.tree

/** Parser-agnostic markdown tree consumed by the renderer (spec: td-rust-1a §3). */
internal interface MdNode {
    val type: MdNodeType
    val startOffset: Int
    val endOffset: Int
    val children: List<MdNode>
    val parent: MdNode?
    fun nextSibling(): MdNode?
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
