package app.amber.feature.ui.components.richtext.tree

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * JetBrains-ASTNode-backed implementation of [MdNode] (TD.Rust.1a Task 8 / Stage 3).
 *
 * This adapter wraps a single `org.intellij.markdown` [ASTNode] and exposes it through the
 * parser-agnostic [MdNode] interface so the renderer can be re-typed to consume [MdNode]
 * instead of `ASTNode` directly (the 2900-line re-type is the NEXT task). It must preserve
 * TODAY'S renderer behavior EXACTLY — every typed accessor below replicates the extraction
 * already living in `Markdown.kt`, with the source line cited.
 *
 * Source of truth: `docs/td-rust-1a-nodetype-mapping.md` (gate-reviewed against the real code).
 * The [IElementType] → [MdNodeType] mapping in [toMdNodeType] follows §2 row by row; anything
 * not listed there falls to [MdNodeType.Unknown] (the renderer's `else` arm handles those
 * exactly as today's `else` does — mapping doc §6 "Unknown is load-bearing").
 *
 * ### Children filtering decision (mapping doc §3, §4)
 * [children] returns ALL JetBrains children UN-prefiltered — call-site filters
 * (ATX_CONTENT skip, EMPH/TILDE marker `trim()`, LIST_ITEM walks, table HEADER/ROW/CELL digs)
 * stay in the renderer and move in the NEXT task. Specifically:
 *  - Heading children keep the `ATX_CONTENT` wrapper unchanged (mapping doc §4 H1 / KU-4:
 *    the JVM tree exposes the wrapper; the render loop still matches it as a Paragraph-like
 *    container — `ATX_CONTENT` maps to [MdNodeType.Paragraph] per §6, row 25/H1).
 *  - Emphasis/Strong/Strikethrough keep their literal marker child tokens (`*`/`**`/`~~`);
 *    the JVM-only `trim()` helper still strips them at the call site (mapping doc §4 H5).
 *  - Link/Image keep `LINK_TEXT`/`LINK_DESTINATION` children so the next task can reconstruct
 *    the visible label + destination (mapping doc §4 H6 / KU-1 — the riskiest area).
 *
 * Not thread-confined; holds no mutable state beyond a lazily-computed children list.
 */
internal class JvmMdNode(
    private val ast: ASTNode,
    private val source: String,
    override val parent: JvmMdNode?,
) : MdNode {

    override val type: MdNodeType = ast.type.toMdNodeType()

    override val startOffset: Int get() = ast.startOffset
    override val endOffset: Int get() = ast.endOffset

    override val children: List<MdNode> by lazy {
        // KEEP ALL JetBrains children (mapping doc §3/§4: no pre-filtering of token noise here).
        ast.children.map { JvmMdNode(it, source, this) }
    }

    /**
     * Next sibling under the same parent, mirroring `ASTNode.nextSibling()`
     * (relocated from Markdown.kt:2869-2879). JetBrains compares by reference equality; we
     * compare by wrapped-ASTNode identity to preserve that exact semantics across re-wraps.
     */
    override fun nextSibling(): MdNode? {
        val siblings = parent?.children ?: return null
        for (i in siblings.indices) {
            if ((siblings[i] as JvmMdNode).ast === ast) {
                if (i + 1 < siblings.size) return siblings[i + 1]
            }
        }
        return null
    }

    /**
     * Pre-order DFS find by type, mirroring `ASTNode.findChildOfTypeRecursive(vararg)`
     * (relocated from Markdown.kt:2881-2888): returns `this` when its own type matches,
     * otherwise the first matching descendant in child order.
     */
    override fun findChildOfTypeRecursive(vararg types: MdNodeType): MdNode? {
        if (this.type in types) return this
        for (child in children) {
            val result = child.findChildOfTypeRecursive(*types)
            if (result != null) return result
        }
        return null
    }

    // ── Typed accessors — verbatim relocation of today's extraction logic ──────────────────

    /**
     * Heading level 1..6 from the ATX element type.
     * Relocated from Markdown.kt:1454-1460 (the `HeaderStyle.H1..H6` switch over ATX_1..ATX_6).
     */
    override val headingLevel: Int?
        get() = when (ast.type) {
            MarkdownElementTypes.ATX_1 -> 1
            MarkdownElementTypes.ATX_2 -> 2
            MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4
            MarkdownElementTypes.ATX_5 -> 5
            MarkdownElementTypes.ATX_6 -> 6
            else -> null
        }

    /**
     * Fenced-code language from the `FENCE_LANG` child token; null for indented `CODE_BLOCK`
     * (which has no FENCE_LANG). Relocated from Markdown.kt:1774
     * (`findChildOfTypeRecursive(FENCE_LANG)?.getTextInNode(content) ?: "plaintext"`).
     *
     * NOTE: the renderer applies the `?: "plaintext"` default at the call site (1774); the
     * accessor returns the RAW lang (null when absent) so the next task keeps that default in
     * the render path and `codeLang == null` can distinguish indented vs fenced (mapping doc
     * §2 #8 / E3). For an empty fence info-string (` ``` ` with no lang) JetBrains emits no
     * FENCE_LANG token, so this returns null — same as indented.
     */
    override val codeLang: String?
        get() = ast.findChildOfTypeRecursive(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(source)

    /**
     * Link destination URL from the `LINK_DESTINATION` child token.
     * Relocated from Markdown.kt:1604 / 2566
     * (`findChildOfTypeRecursive(LINK_DESTINATION)?.getTextInNode(content)`).
     * Null when the node carries no destination token.
     */
    override val linkHref: String?
        get() = ast.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(source)

    /**
     * Link title — today's renderer NEVER reads link titles (no `findChildOfTypeRecursive(
     * LINK_TITLE)` site exists in Markdown.kt). Per mapping doc §6 "linkTitle note", the JVM
     * path returns null; the accessor exists in the interface for forward compatibility only.
     */
    override val linkTitle: String? get() = null

    /**
     * Image source URL from the `LINK_DESTINATION` child token (images reuse the link
     * destination token). Relocated from Markdown.kt:1689
     * (`findChildOfTypeRecursive(LINK_DESTINATION)?.getTextInNode(content)`).
     */
    override val imageSrc: String?
        get() = ast.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(source)

    /**
     * Task-checkbox checked state.
     * Relocated VERBATIM from Markdown.kt:1534 (`getTextInNode(content).trim() == "[x]"`).
     * This is a CASE-SENSITIVE compare today: `[X]` (uppercase) is NOT checked. We preserve
     * the renderer's exact string compare (mapping doc §4 H2 / E6) — null for non-checkbox
     * nodes so the carrier contract holds (`taskChecked: TaskListMarker only`).
     */
    override val taskChecked: Boolean?
        get() = if (ast.type == GFMTokenTypes.CHECK_BOX) {
            ast.getTextInNode(source).trim() == "[x]"
        } else {
            null
        }

    /**
     * Ordered-list start number, parsed from the first item's `LIST_NUMBER` literal.
     *
     * The JVM RENDER path does not use this accessor today — it keeps slicing the per-item
     * `LIST_NUMBER` literal at Markdown.kt:1930 to preserve the author's exact numbering
     * (mapping doc R-E2 / KU-7). This accessor exists for the native side and parity tests;
     * the JVM impl derives `start` from the first list item's leading digits so test 14 pins
     * `start == 7L`. Null for non-ordered-list nodes (carrier: ListOrdered only).
     */
    override val listStart: Long?
        get() {
            if (ast.type != MarkdownElementTypes.ORDERED_LIST) return null
            val firstItem = ast.children.firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM } ?: return null
            val numberToken = firstItem.findChildOfTypeRecursive(MarkdownTokenTypes.LIST_NUMBER) ?: return null
            // LIST_NUMBER literal is e.g. "7. " — take the leading digit run.
            return numberToken.getTextInNode(source).trimStart().takeWhile { it.isDigit() }.toLongOrNull()
        }

    /**
     * Table column alignments — UNUSED by today's renderer; returns null on both trees
     * (mapping doc §5, gate decision option (c): the delimiter row is never inspected and the
     * data model has no alignment field). Kept in the interface for forward compatibility.
     */
    override val tableAlignments: List<TableAlign>? get() = null
}

/**
 * `IElementType → MdNodeType` mapping — exhaustive per mapping doc §2 (the single source of
 * truth). Each arm cites the §2 row / §4 hard-case it satisfies. Unknown tokens (EOL, marker
 * tokens, the BLOCK_QUOTE marker token, etc.) fall to [MdNodeType.Unknown] (§6: load-bearing).
 */
private fun IElementType.toMdNodeType(): MdNodeType = when (this) {
    // ── §2.1 Block-level element types ────────────────────────────────
    MarkdownElementTypes.MARKDOWN_FILE -> MdNodeType.Root                 // §2 #1
    MarkdownElementTypes.PARAGRAPH -> MdNodeType.Paragraph                // §2 #2
    MarkdownTokenTypes.ATX_CONTENT -> MdNodeType.Paragraph                // §4 H1: ATX_CONTENT → Paragraph (heading body wrapper)
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6 -> MdNodeType.Heading                      // §2 #3 (level via headingLevel)
    MarkdownElementTypes.UNORDERED_LIST -> MdNodeType.ListUnordered       // §2 #4
    MarkdownElementTypes.ORDERED_LIST -> MdNodeType.ListOrdered           // §2 #5 (start via listStart)
    MarkdownElementTypes.LIST_ITEM -> MdNodeType.ListItem                 // §2 #6
    MarkdownElementTypes.BLOCK_QUOTE -> MdNodeType.Blockquote             // §2 #7 (element form)
    MarkdownElementTypes.CODE_BLOCK -> MdNodeType.CodeBlock               // §2 #8 (indented → null codeLang)
    MarkdownElementTypes.CODE_FENCE -> MdNodeType.CodeBlock               // §2 #9 (fenced, lang via codeLang)
    MarkdownElementTypes.HTML_BLOCK -> MdNodeType.HtmlBlock               // §2 #10
    MarkdownElementTypes.IMAGE -> MdNodeType.Image                        // §2 #11 (src via imageSrc)
    GFMElementTypes.TABLE -> MdNodeType.Table                            // §2 #12
    GFMElementTypes.BLOCK_MATH -> MdNodeType.MathBlock                   // §2 #13 / §4 H8
    MarkdownTokenTypes.HORIZONTAL_RULE -> MdNodeType.HorizontalRule       // §2 #14 (token form)

    // ── §2.2 Inline-level element types ───────────────────────────────
    MarkdownElementTypes.EMPH -> MdNodeType.Emphasis                      // §2 #15
    MarkdownElementTypes.STRONG -> MdNodeType.Strong                      // §2 #16
    GFMElementTypes.STRIKETHROUGH -> MdNodeType.Strikethrough            // §2 #17
    MarkdownElementTypes.INLINE_LINK -> MdNodeType.Link                   // §2 #18 / §4 H6 (href via linkHref)
    MarkdownElementTypes.AUTOLINK -> MdNodeType.Link                      // §2 #19 / §4 H7 (<url>)
    GFMTokenTypes.GFM_AUTOLINK -> MdNodeType.Link                        // §2 #23 / §4 H7 (bare url)
    MarkdownElementTypes.CODE_SPAN -> MdNodeType.InlineCode               // §2 #20
    GFMElementTypes.INLINE_MATH -> MdNodeType.MathInline                 // §2 #21 / §4 H8
    GFMTokenTypes.CHECK_BOX -> MdNodeType.TaskListMarker                 // §2 #22 (checked via taskChecked)

    // ── §2.3 Token-level types matched directly ───────────────────────
    MarkdownTokenTypes.TEXT -> MdNodeType.Text                           // §2 #24 / §4 H3 (raw prose leaf)

    // ── §2.3 Structural table parts (walked structurally, §3 E7) ──────
    GFMElementTypes.HEADER -> MdNodeType.TableHead                       // §2 #27 (table header row)
    GFMElementTypes.ROW -> MdNodeType.TableRow                           // §2 #28 (table body row)
    GFMTokenTypes.CELL -> MdNodeType.TableCell                           // §2 #29 (table cell token)

    // ── Sentinel ──────────────────────────────────────────────────────
    // Every unmapped token (EOL, ATX `#` markers, EMPH/TILDE/LT/GT markers, the BLOCK_QUOTE
    // marker token §4 H9, LINK_TEXT/LINK_DESTINATION/FENCE_LANG/LIST_NUMBER dig tokens, …)
    // maps to Unknown and is handled by the renderer's `else` arm exactly as today (§6).
    else -> MdNodeType.Unknown
}

/** Source-substring helper for the JetBrains side, mirroring Markdown.kt:2848-2850. */
private fun ASTNode.getTextInNode(source: String): String = source.substring(startOffset, endOffset)

/**
 * Pre-order DFS find on the raw JetBrains tree (mirrors Markdown.kt:2881-2888) — used by the
 * typed accessors above to dig child tokens (FENCE_LANG, LINK_DESTINATION) the same way the
 * renderer does today. Returns `this` when its own type matches.
 */
private fun ASTNode.findChildOfTypeRecursive(vararg types: IElementType): ASTNode? {
    if (this.type in types) return this
    for (child in children) {
        val result = child.findChildOfTypeRecursive(*types)
        if (result != null) return result
    }
    return null
}
