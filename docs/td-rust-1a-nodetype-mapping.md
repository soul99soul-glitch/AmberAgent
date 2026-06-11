# TD.Rust.1a — NodeType Gap Analysis & Mapping Table

**Date**: 2026-06-12
**Status**: Stage-2 GATE output. Zero unresolved rows.
**Spec**: `docs/td-rust-1a-renderer-switch-design.md` §3
**Plan**: `docs/td-rust-1a-renderer-switch-plan.md` Task 6
**Consumes**: `app/src/main/java/app/amber/agent/ui/components/richtext/nativebridge/PackedAstExtras.kt` (Task 5),
`app/src/main/java/app/amber/agent/ui/components/richtext/nativebridge/MarkdownParserNative.kt` (`enum NodeType`, lines 276-312)
**Subject**: `app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt` (2916 LOC)

This document tells the Stage-3 implementer EXACTLY what each JetBrains-type usage in
`Markdown.kt` becomes under the `MdNode` interface, and the Stage-3/Stage-4 implementers
which `MdNodeType` variants exist and how the two trees populate the typed accessors.

> **Reading key.** Every row's target is one of:
> - **`MdNodeType.X`** — a variant the renderer matches on `node.type`.
> - **typed accessor** — the token is *not* matched directly; today's child-token dig is
>   replaced by a typed property (`headingLevel`, `codeLang`, `linkHref`, ...) that each
>   tree implements internally (`JvmMdNode` digs the JetBrains child token; `NativeMdNode`
>   reads packed extras).
> - **text slicing** — replaced by `node.textIn(source)` (start/endOffset substring).
> - **dead-by-construction** — the token has no role in the new model; justified per row.

---

## 1. Method

Enumeration greps (run from repo root against `Markdown.kt`):

```bash
# Plan Step 1 — the canonical 88-hit enumeration:
grep -n "MarkdownElementTypes\.\|GFMElementTypes\.\|MarkdownTokenTypes\." \
  app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt
#   → 88 hits

# Augmented to catch GFMTokenTypes.* (CELL/CHECK_BOX/GFM_AUTOLINK/TILDE), which the
# plan's grep omits but which the renderer matches on:
grep -n "MarkdownElementTypes\.\|GFMElementTypes\.\|MarkdownTokenTypes\.\|GFMTokenTypes\." \
  app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt

# Extraction-pattern & extension-function audit (digging child tokens, not type refs):
grep -n "getTextInNode\|fun ASTNode\|containsHtmlBlocks\|nextSibling\|\
findChildOfTypeRecursive\|traverseChildren\|fun .*trim\|LeafASTNode" \
  app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt
```

JetBrains imports in scope (line 107-115): `IElementType`, `MarkdownElementTypes`,
`MarkdownTokenTypes`, `ASTNode`, `LeafASTNode`, `GFMElementTypes`, `GFMFlavourDescriptor`,
`GFMTokenTypes`, `MarkdownParser`. After Stage 3, only the parse-funnel + `JvmMdNode`
adapter retain these; the renderer body sees `MdNode`/`MdNodeType` only.

Rust wire side verified by reading `native/markdown-parser/src/tree_builder.rs`
(`make_block_or_inline_type` lines 161-189, `attach_tag_extras` lines 191-222) and
`PackedAstExtras.kt`.

**Counts**: 45 distinct fully-qualified JetBrains identifiers referenced
(`ATX_1..ATX_6` collapse to one `Heading` row; `BLOCK_QUOTE` appears as both an element and a
token form, kept distinct) — these fold into **29 mapping rows** across §2.1-§2.4 plus 39
sub-rows for trim/dig tokens; 15 extraction patterns (§3); 9 hard token cases classified (§4);
0 unresolved rows. Verified distinct-identifier list:
`grep -oE "(MarkdownElementTypes|GFMElementTypes|MarkdownTokenTypes|GFMTokenTypes)\.[A-Z_0-9]+" Markdown.kt | sort -u` → 45 lines.

---

## 2. Full mapping table — every distinct JetBrains type referenced in `Markdown.kt`

Line numbers are the exact reference sites for that type. "Match" = used in a `when`/`==`
type discriminator (becomes an `MdNodeType` match); "extract" = used to dig a child token
(becomes a typed accessor or slicing). `Native side` = how `NativeMdNode` (packed AST)
produces the same result.

### 2.1 Block-level element types

| # | JetBrains type | usage sites (lines) | role | Target `MdNodeType` / replacement | Native side | Risk |
|---|---|---|---|---|---|---|
| 1 | `MarkdownElementTypes.MARKDOWN_FILE` | 1421 | match (root) | **`MdNodeType.Root`** | `NodeType.Root` | none — 1:1 |
| 2 | `MarkdownElementTypes.PARAGRAPH` | 502, 940, 1441, 2037(implicit via else), 2483(token form, see #19) | match | **`MdNodeType.Paragraph`** | `NodeType.Paragraph` | none — 1:1 |
| 3 | `MarkdownElementTypes.ATX_1`..`ATX_6` | 507-512, 1453, 1455-1460, 1463-1469 | match (6 variants) | **`MdNodeType.Heading`** + `headingLevel: Int?` (1-6) | `NodeType.Heading`, `headingLevelExtra()` | **MEDIUM** — JetBrains has 6 distinct types; pulldown-cmark has ONE `Heading` + level byte. The renderer's `when` over ATX_1..6 (lines 1453-1469: HeaderStyle + padding) MUST become `when (node.headingLevel)`. See §3 row E1. |
| 4 | `MarkdownElementTypes.UNORDERED_LIST` | 503, 1508, 2037 | match | **`MdNodeType.ListUnordered`** | `NodeType.ListUnordered` | none — 1:1 |
| 5 | `MarkdownElementTypes.ORDERED_LIST` | 504, 1520, 2037 | match | **`MdNodeType.ListOrdered`** + `listStart: Long?` | `NodeType.ListOrdered`, `listStartExtra()` | LOW — list-start surfaces via accessor, see §3 E2 |
| 6 | `MarkdownElementTypes.LIST_ITEM` | 505, 1888, 1928 | match | **`MdNodeType.ListItem`** | `NodeType.ListItem` | none — 1:1 |
| 7 | `MarkdownElementTypes.BLOCK_QUOTE` | 506, 1558 | match | **`MdNodeType.Blockquote`** | `NodeType.Blockquote` | none. (Distinct from token-form `MarkdownTokenTypes.BLOCK_QUOTE`, row #19.) |
| 8 | `MarkdownElementTypes.CODE_BLOCK` | 515, 1750 | match (indented code) | **`MdNodeType.CodeBlock`** | `NodeType.CodeBlock` (indented → empty lang) | **MEDIUM** — JetBrains emits `CODE_BLOCK` (indented) vs `CODE_FENCE` (fenced) as TWO types; pulldown-cmark emits ONE `CodeBlock` for both, distinguished only by `codeLang` (null for indented). The two renderer arms (1750 simple Text vs 1761 HighlightCodeBlock) MUST merge or branch on `codeLang == null`. See §3 E3. |
| 9 | `MarkdownElementTypes.CODE_FENCE` | 514, 1761 | match (fenced code) | **`MdNodeType.CodeBlock`** + `codeLang: String?` | `NodeType.CodeBlock`, `codeLangExtra()` | see #8 + §3 E3, §4 H4 |
| 10 | `MarkdownElementTypes.HTML_BLOCK` | 516, 753, 1807 | match | **`MdNodeType.HtmlBlock`** | `NodeType.HtmlBlock` | none. Used by `containsHtmlBlocks()` (753) → §3 row F1 |
| 11 | `MarkdownElementTypes.IMAGE` | 513, 942, 1686, 2072 | match + `findChildOfTypeRecursive` arg | **`MdNodeType.Image`** + `imageSrc: String?` | `NodeType.Image`, `linkHrefExtra()` | LOW — alt/src via accessor, §3 E4 |
| 12 | `GFMElementTypes.TABLE` | 520, 1665, 2433 | match | **`MdNodeType.Table`** | `NodeType.Table` | LOW (alignment dead — §5) |
| 13 | `GFMElementTypes.BLOCK_MATH` | 521, 942, 1724, 2072 | match + `findChildOfTypeRecursive` arg | **`MdNodeType.MathBlock`** | `NodeType.MathBlock` | **MEDIUM** — see §4 H8 (how `$$` becomes BLOCK_MATH at all) |
| 14 | `MarkdownTokenTypes.HORIZONTAL_RULE` | 519, 1675 | match | **`MdNodeType.HorizontalRule`** | `NodeType.HorizontalRule` | none — 1:1 (token in JB, block in Rust; both map to one variant) |

### 2.2 Inline-level element types

| # | JetBrains type | usage sites (lines) | role | Target `MdNodeType` / replacement | Native side | Risk |
|---|---|---|---|---|---|---|
| 15 | `MarkdownElementTypes.EMPH` | 1616, 2501 | match | **`MdNodeType.Emphasis`** | `NodeType.Emphasis` | LOW — child-trim differs, §4 H5 |
| 16 | `MarkdownElementTypes.STRONG` | 1637, 2522 | match | **`MdNodeType.Strong`** | `NodeType.Strong` | LOW — child-trim differs, §4 H5 |
| 17 | `GFMElementTypes.STRIKETHROUGH` | 1659, 2543 | match | **`MdNodeType.Strikethrough`** | `NodeType.Strikethrough` | LOW — child-trim differs, §4 H5 |
| 18 | `MarkdownElementTypes.INLINE_LINK` | 1599, 2564 | match | **`MdNodeType.Link`** + `linkHref`/`linkTitle` | `NodeType.Link`, `linkHrefExtra()`/`linkTitleExtra()` | **HIGH** — JetBrains nests `LINK_TEXT`/`LINK_DESTINATION` child tokens; pulldown-cmark has NO such children (extras only). The renderer's `findChildOfTypeRecursive(LINK_DESTINATION/LINK_TEXT)` (1600-1604, 2566-2567) MUST become accessors + `node.children` for the visible label. See §3 E5, §4 H6. |
| 19 | `MarkdownElementTypes.AUTOLINK` | 2606 | match | **`MdNodeType.Link`** (see risk) | `NodeType.Link` (pulldown-cmark normalizes `<url>` to a Link) | **MEDIUM** — JetBrains `AUTOLINK` (angle-bracket `<https://…>`) is distinct from `INLINE_LINK` and from GFM bare-url `GFM_AUTOLINK`. In the new model all three converge on `MdNodeType.Link`; the `<`/`>` trimming (2607) is replaced by `linkHref` + slicing. See §4 H7. |
| 20 | `MarkdownElementTypes.CODE_SPAN` | 1743, 2617 | match | **`MdNodeType.InlineCode`** | `NodeType.InlineCode` | LOW — backtick trim differs, §4 H4 |
| 21 | `GFMElementTypes.INLINE_MATH` | 1708, 2108, 2630 | match + `findChildOfTypeRecursive` arg | **`MdNodeType.MathInline`** | `NodeType.MathInline` | MEDIUM — §4 H8 |
| 22 | `GFMTokenTypes.CHECK_BOX` | 522, 1533 | match | **`MdNodeType.TaskListMarker`** + `taskChecked: Boolean?` | `NodeType.TaskListMarker`, `taskCheckedExtra()` | **MEDIUM** — JetBrains emits the `[x]`/`[ ]` literal as a `CHECK_BOX` *token* inside the list item; the renderer text-slices it (`getTextInNode(...).trim() == "[x]"`, 1534). pulldown-cmark emits a dedicated `TaskListMarker` leaf with a checked byte. See §3 E6, §4 H2. |
| 23 | `GFMTokenTypes.GFM_AUTOLINK` | 1601, 2485 | match (2485) + dig (1601) | **`MdNodeType.Link`** | `NodeType.Link` | MEDIUM — bare-url autolink; converges on Link, §4 H7 |

### 2.3 Token-level types matched directly (not block/inline elements)

| # | JetBrains type | usage sites (lines) | role | Target `MdNodeType` / replacement | Native side | Risk |
|---|---|---|---|---|---|---|
| 24 | `MarkdownTokenTypes.TEXT` | 518, 1601(dig), 1799(match) | match (raw text leaf) | **`MdNodeType.Text`** | `NodeType.Text` | LOW — both trees emit a Text leaf for prose runs; §4 H3 |
| 25 | `MarkdownTokenTypes.ATX_CONTENT` | 517, 1483 | match (heading body) | **dead-as-match → typed via `headingLevel` + children** | (no equivalent — heading children ARE the inline content) | **MEDIUM** — see §4 H1 |
| 26 | `MarkdownTokenTypes.BLOCK_QUOTE` | 2483 | match in inline-append (no-op `{}`) | **dead-by-construction** | n/a | LOW — §4 H9 |
| 27 | `GFMElementTypes.HEADER` | 2440 | dig (table header row) | **structural: first `TableHead` child** | `NodeType.TableHead` child | LOW — §3 E7 |
| 28 | `GFMElementTypes.ROW` | 2441 | dig (table body rows) | **structural: `TableRow` children** | `NodeType.TableRow` children | LOW — §3 E7 |
| 29 | `GFMTokenTypes.CELL` | 2442, 2446, 2451 | dig (table cells) | **structural: `TableCell` children** | `NodeType.TableCell` children | LOW — §3 E7. JetBrains cells are TOKENS (text-slice); pulldown-cmark cells are NODES whose children are inline content. See §3 E7 risk. |

### 2.4 Token-level types used ONLY for child-token digging / trimming (never matched as node type)

These never appear as a `when` arm; they parameterize `findChildOfTypeRecursive(...)`,
`getTextInNode(...,type)`, or `List<ASTNode>.trim(type, n)`. Each becomes a typed accessor
or text-slice; see §3 (extraction) and §4 (hard cases). They do **not** produce
`MdNodeType` variants.

| # | JetBrains token | usage sites (lines) | replacement | hard-case |
|---|---|---|---|---|
| 30 | `MarkdownTokenTypes.CODE_FENCE_CONTENT` | 1764, 1770 | `codeLang` + text slicing of node body | §4 H4 |
| 31 | `MarkdownTokenTypes.EOL` | 1767 | dead — body sliced by offsets, no EOL hunt | §4 H4 |
| 32 | `MarkdownTokenTypes.FENCE_LANG` | 1774 | **`codeLang` accessor** | §3 E3 |
| 33 | `MarkdownTokenTypes.CODE_FENCE_END` | 1777 | typed `codeFenceClosed: Boolean` OR offset check (see §4 H4) | §4 H4 |
| 34 | `MarkdownTokenTypes.LIST_NUMBER` | 1930 | **`listStart` + per-item index** (slice retained for JVM) | §3 E2 |
| 35 | `MarkdownElementTypes.LINK_TEXT` | 1600, 1687, 2567 | visible-label: `node.children` text / `imageSrc` alt | §3 E4, E5 |
| 36 | `MarkdownElementTypes.LINK_DESTINATION` | 1604, 1689, 2566 | **`linkHref`/`imageSrc` accessor** | §3 E4, E5 |
| 37 | `MarkdownTokenTypes.EMPH` | 2503, 2524 | dead — child markers absent in native; trim is JVM-only | §4 H5 |
| 38 | `GFMTokenTypes.TILDE` | 2545 | dead — strikethrough markers absent in native | §4 H5 |
| 39 | `MarkdownTokenTypes.LT` / `.GT` | 2607 | dead — autolink `<`/`>` absent; `linkHref` carries url | §4 H7 |

---

## 3. Extraction-pattern mapping (child-token digging → typed accessor / slicing)

Each row is a concrete extraction *pattern* in today's code. Stage 3 replaces the JVM dig
with the interface call; `JvmMdNode` keeps the dig internally, `NativeMdNode` reads extras.

| ID | Today's pattern (Markdown.kt) | Lines | New interface call | `JvmMdNode` impl | `NativeMdNode` impl |
|---|---|---|---|---|---|
| **E1** | `when (node.type) { ATX_1 -> H1 … }` + per-level padding | 1453-1469 | `node.headingLevel` → `when (level)` | level = ATX_n type → n | `headingLevelExtra()` |
| **E2** | `findChildOfTypeRecursive(LIST_NUMBER)?.getTextInNode()` for ordered bullet text | 1930 | `node.listStart` (parent list) + running index; JVM may keep slicing the literal marker for exact text | dig `LIST_NUMBER` token text (preserve current "7. " literal) | `listStartExtra()` on the list; item index from child order. **See risk R-E2.** |
| **E3** | `findChildOfTypeRecursive(FENCE_LANG)?.getTextInNode() ?: "plaintext"` | 1774 | `node.codeLang ?: "plaintext"` | dig `FENCE_LANG` token | `codeLangExtra()` |
| **E4** | image: `findChildOfTypeRecursive(LINK_TEXT)?.getTextInNode()` (alt), `LINK_DESTINATION` (src) | 1687-1689 | `node.imageSrc` (src) + alt text from `node` label | dig the two child tokens | `linkHrefExtra()` (src); alt from inline children |
| **E5** | inline-link: `findChildOfTypeRecursive(LINK_DESTINATION)` (href), `LINK_TEXT` (label) | 1600-1604, 2566-2567 | `node.linkHref`/`node.linkTitle` (href/title) + label from `node.children` | dig `LINK_DESTINATION`/`LINK_TEXT` | `linkHrefExtra()`/`linkTitleExtra()`; label = link's child inline nodes |
| **E6** | checkbox: `getTextInNode().trim() == "[x]"` | 1534 | `node.taskChecked == true` | slice `CHECK_BOX` token, compare `[x]` | `taskCheckedExtra()` |
| **E7** | table: `children.find{HEADER}`, `.filter{ROW}`, `.count/filter{CELL}`, cell `getTextInNode().trim()` | 2440-2452 | iterate `children` by `MdNodeType.TableHead`/`TableRow`/`TableCell`; cell text via `textIn(source)` | HEADER/ROW = element children; CELL = token children, slice text | TableHead/TableRow children; TableCell text = `textIn(source)` over cell node. **See risk R-E7.** |
| **F1** | `containsHtmlBlocks()` recursive walk on `type == HTML_BLOCK` | 752-755, 806 | JVM path keeps the walk; native path reads the **blob header HTML-block flag bit** (spec §4) | recursive `type == MdNodeType.HtmlBlock` | `PackedAstReader.hasHtmlBlocks` (header bit) — NOT a tree walk |
| **F2** | `getTextInNode(text)` everywhere (12+ sites) | 2848-2850; called at 1534,1661,1709,1725,1744,1751,1800,1808,2429,2447,2452,2486,2495,2618,2633,2653,2609,2611 | **`node.textIn(source)`** (spec helper) | `source.substring(start,end)` | identical — same start/endOffset contract |
| **F3** | `nextSibling()` for paragraph bottom-padding + streaming stabilization | 941, 2129; def 2869-2879 | **`MdNode.nextSibling()`** (interface method) | parent.children index+1 | `PackedAstNode.nextSibling()` (already exists) |
| **F4** | `findChildOfTypeRecursive(vararg types)` | 942, 1600-1601, 1604, 1687, 1689, 1774, 1777, 2072, 2108, 2566-2567; def 2881-2888 | **`MdNode.findChildOfTypeRecursive(vararg MdNodeType)`** (interface method) | recursive type match | `PackedAstNode.findChildOfTypeRecursive()` (already exists) |
| **F5** | `List<ASTNode>.trim(type, n)` — strips n leading/trailing marker tokens | 2503, 2524, 2545, 2607; def 2899-2916 | **JVM-only helper**; native children carry no markers → identity | keep on `List<MdNode>` (JVM) | no-op / identity (no marker children) — §4 H5 |
| **F6** | `getTextInNode(text, type: IElementType)` (offset-span of matching child tokens) | def 2852-2867 | **no live caller — DEAD CODE** (see §7 KU-3) | — | — |
| **F7** | `ASTNode.traverseChildren(action)` | def 2890-2897 | **no live caller — DEAD CODE** (see §7 KU-3) | — | — |
| **F8** | `node is LeafASTNode` — "is this a raw leaf?" in inline append | 2494 | **`node.children.isEmpty()`** (leaf ⇔ no children) | `ast is LeafASTNode` (or `children.isEmpty()`) | `children.isEmpty()` | 

> **Risk R-E2 (ordered-list start).** JetBrains stores the *literal* bullet ("7. ", "8. ")
> as a per-item `LIST_NUMBER` token, so the JVM path renders the author's exact numbering.
> pulldown-cmark stores only the list-level `start` (=7) + item order; per-item numbers are
> `start + index`. For Stage-3 zero-change, `JvmMdNode` MUST keep slicing the `LIST_NUMBER`
> literal (preserve "7. " text exactly). `NativeMdNode` (Stage 4) reconstructs
> `listStart + index`. Parity test 14 (`14-ordered-list-start.md`) pins this — the design's
> `listStart` accessor exists for the native side; the JVM render path does **not** need to
> switch to it (the literal slice already produces identical output).

> **Risk R-E7 (table cells: token vs node).** In JetBrains, `CELL` is a leaf *token*; the
> renderer text-slices `cell.getTextInNode().trim()` and re-parses the result through
> `TableCellContent` → nested `MarkdownBlock` for cells containing markdown (line 2397). In
> pulldown-cmark, `TableCell` is a *node* whose children are already inline-parsed. The
> `textIn(source)` slice over a `TableCell` node yields the same raw cell text (start/end
> offsets cover the cell content), so the existing "re-parse cell text" path keeps working
> identically on both trees. **No structural cell-walking is needed** — keep the slice-then-
> reparse approach; do NOT try to render `TableCell` children directly (that would diverge
> from the JVM path's nested-`MarkdownBlock` behavior). Parity tests 11/12 pin this.

---

## 4. Hard cases — written strategies (no row is "TBD")

These are the token-level types with no clean pulldown-cmark counterpart. Each gets one
resolved strategy.

### H1 — `MarkdownTokenTypes.ATX_CONTENT` (heading body wrapper)
**Sites**: 517 (streaming target list), 1483 (heading render loop).
**Problem**: JetBrains wraps a heading's inline content in an `ATX_CONTENT` token child;
the renderer loops `node.children` and only renders the child whose `type == ATX_CONTENT`
(1483), skipping the `#` marker tokens. pulldown-cmark's `Heading` node has the inline
content directly as children (no marker tokens, no wrapper).
**Strategy**: **Dead-as-a-direct-match in the shared renderer.** Replace the
`if (child.type == ATX_CONTENT)` filter with "render the heading's inline children". For
`JvmMdNode`, `ATX_CONTENT` maps to `MdNodeType.Paragraph` (it *is* a paragraph-like inline
container) OR the JVM `children` accessor exposes the `ATX_CONTENT`'s own children directly
so both trees present the same flat inline list. **Decision for Stage 3**: keep behavior
zero-change by having `JvmMdNode.children` for a Heading return the `ATX_CONTENT` wrapper
(unchanged), and the render loop matches `MdNodeType.Paragraph` (the ATX_CONTENT maps to
Paragraph). The streaming-target entry (517) becomes `MdNodeType.Heading` (the heading
block itself is the renderable target; the ATX_CONTENT entry is redundant once headings
are a single type). Native side: `Heading` children are inline nodes; no wrapper, rendered
the same way. **Risk: MEDIUM** — verify parity test 02 (`02-headings-all-levels.md`) that
the wrapper-vs-flat children produce identical semantics dumps.

### H2 — `GFMTokenTypes.CHECK_BOX` (`[x]`/`[ ]` literal token)
**Sites**: 522, 1533-1534.
**Problem**: JetBrains emits the literal `[x]` as a `CHECK_BOX` token; the renderer
text-slices and string-compares `== "[x]"`.
**Strategy**: **Replaced by typed accessor `taskChecked: Boolean?`.** Map `CHECK_BOX` →
`MdNodeType.TaskListMarker`; the render arm becomes `node.taskChecked == true`. `JvmMdNode`
implements `taskChecked` by slicing the token and comparing `[x]` (relocate line 1534 logic
verbatim). `NativeMdNode` delegates to `taskCheckedExtra()` (checked byte). **Resolved.**

### H3 — `MarkdownTokenTypes.TEXT` (raw prose leaf)
**Sites**: 518, 1799-1800.
**Problem/Strategy**: **Direct map → `MdNodeType.Text`, body via text slicing.** Both
parsers emit a plain-text leaf for prose runs (JetBrains `TEXT` token / pulldown-cmark
`Text` leaf). The render arm (1799) text-slices `node.getTextInNode()` → `node.textIn()`.
Note pulldown-cmark merges adjacent text differently than JetBrains, but the rendered
AnnotatedString is whitespace-normalized in the parity rig, so this is benign. **Resolved.**

### H4 — Code-fence body extraction (`CODE_FENCE_CONTENT`, `EOL`, `FENCE_LANG`, `CODE_FENCE_END`)
**Sites**: 1761-1797 (fence render), 1764/1767/1770 (body offset hunt), 1774 (lang), 1777
(end-fence presence).
**Problem**: Today the renderer hand-reconstructs the fenced body: finds the first
`CODE_FENCE_CONTENT`, walks back to the last preceding `EOL` for the true start offset
(comment at 1762: "首行 indent 没有包含在内"), finds the last `CODE_FENCE_CONTENT` for the end
offset, then slices `content.substring(start, end)`. It separately reads `FENCE_LANG` for
the language and checks `CODE_FENCE_END` existence (1777-1778) to decide `completeCodeBlock`
(for the streaming "code still open" state). pulldown-cmark emits a `CodeBlock` node whose
`startOffset..endOffset` already cover the content, with lang in extras, and **no** content/
EOL/end tokens.
**Strategy** (three sub-parts):
- `FENCE_LANG` → **`codeLang` accessor** (E3). Resolved.
- `CODE_FENCE_CONTENT` + `EOL` body hunt → **text slicing of the code node body**, but the
  JVM path's exact "back up to last EOL" offset math is JVM-specific. **Decision**: keep the
  body-offset computation inside `JvmMdNode` as a typed `codeBody(source): String` helper (or
  retain the current in-arm logic gated on the JVM tree), and have `NativeMdNode` produce the
  body by slicing its own node offsets (`textIn`) which already exclude the fence lines. The
  parity rig (test 07/08/26) proves the two bodies match after `trimIndent()`.
- `CODE_FENCE_END` presence → **typed `codeFenceClosed: Boolean`** (or, native side, the node
  having a closing fence is implicit in pulldown-cmark always emitting a complete CodeBlock;
  the streaming "is it closed" signal already comes from the offset check
  `sourceOffsetBase + endFence.endOffset <= syntheticSuffixStart`, line 1778, which is a
  *streaming-position* check, not a structural one). **Decision**: expose
  `codeFenceEndOffset: Int?` so the streaming position check works on both trees; `JvmMdNode`
  returns `CODE_FENCE_END.endOffset`, `NativeMdNode` returns the node `endOffset` (closed
  fence) or null when the blob represents a truncated tail. **Risk: MEDIUM** — `26-streaming-
  truncated.md` is the dedicated pin for the "fence never closes" path. Verify the streaming
  suffix still renders identically.

### H5 — Emphasis/Strong/Strikethrough child-marker trimming (`EMPH`, `TILDE`, `trim()`)
**Sites**: 2503 (`trim(EMPH,1)`), 2524 (`trim(EMPH,2)`), 2545 (`trim(TILDE,2)`); helper
2899-2916.
**Problem**: JetBrains includes the literal markers (`*`, `**`, `~~`) as child `EMPH`/
`TILDE` tokens; the renderer strips n leading/trailing marker tokens before recursing into
the real content. pulldown-cmark's `Emphasis`/`Strong`/`Strikethrough` nodes contain ONLY
the content children — no marker tokens.
**Strategy**: **`trim()` stays a JVM-only `List<MdNode>` helper; native is identity.** The
shared render path calls a small helper that, for the JVM tree, strips markers (relocated
verbatim), and for the native tree is a no-op (no marker children exist). Cleanest: make
`MdNode.contentChildren` return marker-free children — `JvmMdNode` applies the trim,
`NativeMdNode` returns `children` unchanged. The render arms (1616/1637/1659 and
2501/2522/2543) iterate `contentChildren`. **Resolved; LOW risk** (tests 03/23 pin it).

### H6 — `INLINE_LINK` structure: child tokens vs extras
**Sites**: 1599-1604, 2564-2603.
**Problem**: covered in §2 #18 / §3 E5. JetBrains digs `LINK_DESTINATION`/`LINK_TEXT`
children; pulldown-cmark has none (href/title in extras, label is the inline children).
**Strategy**: **`linkHref`/`linkTitle` accessors for the URL/title; visible label from the
link's inline children.** The citation/search-source pill logic (2570-2592) keys off
`linkText` and `linkDest` — both now come from accessor (`linkHref`) + child-text. For JVM,
the label is `LINK_TEXT`'s text (relocate 2567 `.trim{ '[' or ']' }`); for native, the label
is the concatenated text of the Link node's children. **Risk: HIGH** — this is the most
structurally divergent case. The `citation,`-prefix and 6-char-id special handling
(2570-2584) and the `appendSearchSourcePill` path must produce identical pills on both trees.
Tests 04/05/28 (`links-inline`, `links-with-titles`, `link-edge-cases`) are the pins;
Stage-4 parity is the real proof. Flagged as the #1 thing for the Stage-3 reviewer to scrutinize.

### H7 — Autolinks: `AUTOLINK` (`<url>`), `GFM_AUTOLINK` (bare url), `LT`/`GT`
**Sites**: 2485-2491 (GFM_AUTOLINK), 2606-2614 (AUTOLINK + `trim(LT,1).trim(GT,1)`).
**Problem**: JetBrains has two distinct autolink types plus the `<`/`>` delimiter tokens;
pulldown-cmark normalizes both to `Link` nodes (href in extras, no delimiter tokens).
**Strategy**: **Both converge on `MdNodeType.Link`; `linkHref` carries the URL; `LT`/`GT`
trimming is dead (no delimiter children on native).** For the JVM tree, keep the existing
two arms behaviorally (they map to `MdNodeType.Link`, and the renderer can still distinguish
"render as italic autolink" by checking whether `linkHref == label`, i.e. the URL is shown
verbatim). **Decision**: since both JB autolink arms render the URL as an italic clickable
link, and pulldown-cmark Link with `linkHref == text` produces the same, fold them into the
Link arm with a `isAutolink = (linkHref == labelText)` check if the italic styling must be
preserved. **Risk: MEDIUM** — verify test 28 (`link-edge-cases`) includes `<https://…>` and
bare-url forms and that italic styling survives.

### H8 — Math: `$`/`$$` → `INLINE_MATH`/`BLOCK_MATH` (how the JetBrains parser even sees math)
**Sites**: 1708-1741 (block/inline render), 2630-2662 (inline append), 2108/2072
(`findChildOfTypeRecursive` math presence).
**Problem to document**: The renderer matches `GFMElementTypes.INLINE_MATH` /
`GFMElementTypes.BLOCK_MATH`. These ARE real types in the project's GFM flavour — the
preprocessor (`preProcess`) converts `\( \)` / `\[ \]` to `$…$` / `$$…$$`, and the GFM
flavour descriptor in use recognizes `$`/`$$` as math spans/blocks, emitting `INLINE_MATH`/
`BLOCK_MATH` nodes whose `getTextInNode()` is the formula. pulldown-cmark emits `MathInline`/
`MathBlock` leaves (see `tree_builder.rs` lines 129-133: pulldown-cmark `Event::InlineMath`/
`DisplayMath`). **Caveat to verify (KU-2)**: the composables (`MathInline`/`MathBlock` in
`MathBlock.kt:18-53`) pass through the text unchanged; they do not strip delimiters. Whether
JetBrains and pulldown-cmark offsets *both* include the `$`/`$$` delimiters is the open
verification item tracked as KU-2.
**Strategy**: **Direct map — `INLINE_MATH → MdNodeType.MathInline`,
`BLOCK_MATH → MdNodeType.MathBlock`; formula via text slicing.** Both trees expose the
formula as the node's `textIn(source)`. Tests 18/19 (`katex-inline`, `katex-block`)
pin this. **Risk: MEDIUM.**

### H9 — `MarkdownTokenTypes.BLOCK_QUOTE` (token form) in inline append
**Site**: 2483 (`node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}` — explicit no-op).
**Problem**: This is the `>` marker *token* (distinct from the `MarkdownElementTypes.
BLOCK_QUOTE` *element*, row #7). In the inline-append walker it's matched only to be
explicitly skipped (empty body). pulldown-cmark has no such marker token.
**Strategy**: **Dead-by-construction.** The native tree never produces a blockquote-marker
leaf, so the skip is unnecessary there. Keep the no-op arm for the JVM tree (harmless) or
drop it once the native path is sole — but for Stage 3 (JVM only) **keep it**, mapped to a
`MdNodeType.Unknown` (the marker token has no semantic variant) which the inline walker's
`else`/no-op handles. **Resolved.**

> **ERRATA (Stage-3, 2026-06-12)**: H9's earlier claim that the BLOCK_QUOTE marker can map to Unknown and be handled by the inline walker's else/no-op is incomplete. The marker is a LEAF token (no children) and therefore would hit the generic leaf arm in `appendMarkdownNodeContent`, rendering a literal `>` character into the output. The implemented fix is a dedicated `MdNode.isBlockquoteMarker` typed accessor with an explicit skip arm that must precede the generic leaf arm (Markdown.kt ~2516). Native side (Task 12) must implement `isBlockquoteMarker` accordingly per its KDoc in `MarkdownTree.kt`.

---

## 5. Table alignment resolution — DECISION

**Decision: Option (c) — table column alignment is genuinely UNUSED by the current renderer.
No Rust change, no decoder work, no wire-format bump. The `tableAlignments` accessor returns
`null` on both trees.**

### Proof (read top-to-bottom, it's airtight)

1. **Rust does not encode it.** `attach_tag_extras` (`tree_builder.rs` 191-222) has arms for
   Heading/CodeBlock/List/Link/Image and a `_ => {}` catch-all. There is **no `Tag::Table`
   arm**, so the `Vec<Alignment>` carried by `Tag::Table(_)` (matched only for the type code
   at line 170, payload discarded) is dropped. Table nodes ship with `extras_len = 0`. This
   matches the KDoc already written in `PackedAstExtras.kt` (lines 16-18, 93-111).

2. **The JVM extractor does not read it.** `extractMarkdownTableData` (Markdown.kt 2439-2459)
   produces a `MarkdownTableData(columnCount, headers, rows)` — three fields, **no alignment
   field**. The delimiter row (`:---:`, `---:`) is never inspected; it's simply one of the
   table's children that the extractor ignores (it filters for `HEADER`/`ROW`/`CELL` only).

3. **The data model has no alignment field.** `data class MarkdownTableData` (2410-2414) =
   `columnCount`, `headers: List<String>`, `rows: List<List<String>>`. Full stop.

4. **The renderer applies one uniform alignment to every cell.** `TableNode` (2306-2393)
   feeds `DataTable` and never sets a per-column alignment. `DataTable` (DataTable.kt 40-53)
   has a single `cellAlignment: Alignment = Alignment.CenterStart` applied to ALL cells;
   `TableNode` doesn't override it. Therefore `:---`, `:---:`, `---:` all render identically
   (CenterStart) **today**.

Conclusion: alignment is **dead-by-construction in the current renderer**. Encoding it on the
Rust side, or deriving it JVM-side from the delimiter row, would add behavior the renderer
discards — and crucially would make the native path *diverge* from the pinned JVM golden
(parity rig would flag a difference the user never sees). The zero-change invariant of
Stages 3-4 requires we do NOT add alignment.

### Why this does NOT need a wire-format version bump
We are changing nothing on the wire. The blob format `PMDA v1` already produces empty Table
extras; that is the format. No corpus regen is triggered by this decision.

### Concrete Stage-3/Stage-4 action items
- **Stage 3 (`JvmMdNode`)**: `tableAlignments` returns `null` (don't parse the delimiter row).
- **Stage 4 (`NativeMdNode`)**: `tableAlignments` delegates to `tableAlignmentsExtra()`, which
  returns `null` for real blobs (empty extras). Already implemented in Task 5.
- **Interface**: keep `tableAlignments: List<TableAlign>?` in `MdNode` (spec §3) for forward
  compatibility, but it is a **null-on-both-paths** accessor today. The `TableAlign` enum
  (NONE/LEFT/CENTER/RIGHT, bytes 0/1/2/3) already lives in `PackedAstExtras.kt`; per the plan
  it relocates to `MarkdownTree.kt` in Task 7 with nativebridge importing it (single
  definition).
- **If alignment is ever wanted** (a real feature, out of TD.Rust.1a scope): it requires (a) a
  `Tag::Table` arm in `attach_tag_extras` writing one byte/column + corpus regen, (b) the
  `MarkdownTableData` model + `DataTable` per-column alignment, AND (c) a new golden because
  output *changes*. That is a deliberate feature with its own approval — **not** part of this
  refactor.

---

## 6. Final `MdNodeType` variant list (paste-ready for Stage-3 `MarkdownTree.kt`)

Derived as: **all Rust wire `NodeType` block/inline variants that the renderer can encounter**
∪ **JetBrains-only kinds that survive classification** (none survive as *new* variants — every
JetBrains type folds into a wire variant or a typed accessor) ∪ **`Unknown`** sentinel.

The wire enum has 30 entries (incl. Root/Unknown). The renderer never needs `TableHead`/
`TableRow`/`TableCell` as `when` arms (they're walked structurally in §3 E7) but they MUST
exist as variants so the structural walk can match `child.type == MdNodeType.TableRow`.
`InlineHtml`, `FootnoteRef`, `FootnoteDef`, `SoftBreak`, `HardBreak` are emitted by the
native tree and fall through the renderer's `else` (recurse/append) today — they need
variants so `NativeMdNode` can map `NodeType.X → MdNodeType.X` exhaustively (plan Task 12
requires an exhaustive `when`).

**30 variants** (matches the design's "~30-36" expectation):

```kotlin
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
```

### Notes for the implementer
- **No new variant survives from JetBrains-only kinds.** Every JetBrains token that lacks a
  wire counterpart (`ATX_CONTENT`, `CODE_FENCE_CONTENT`, `EOL`, `FENCE_LANG`,
  `CODE_FENCE_END`, `LIST_NUMBER`, `LINK_TEXT`, `LINK_DESTINATION`, `EMPH`/`TILDE`/`LT`/`GT`
  markers, the `BLOCK_QUOTE` token) is either a typed accessor (§3) or maps to `Unknown` and
  is skipped — none becomes its own `MdNodeType`.
- **`Unknown` is load-bearing.** `JvmMdNode`'s `IElementType → MdNodeType` `when` falls to
  `Unknown` for every token not in the table; the renderer's `else` arm (recurse children /
  append text) handles them exactly as today's `else` does.
- **`TableHead`/`TableRow`/`TableCell`** exist as variants for the structural table walk
  (§3 E7) even though no top-level `when` arm matches them.
- **Native-only variants** (`InlineHtml`, `FootnoteRef`, `FootnoteDef`, `SoftBreak`,
  `HardBreak`) have no JetBrains match site — the JVM tree never produces them (JetBrains
  represents these as raw `TEXT`/`HTML` runs), but they're required for `NativeMdNode`'s
  exhaustive mapping. They render via the renderer's `else`/append path identically.

### Interface accessor ↔ extras decoder ↔ JVM dig cross-reference
| `MdNode` accessor | Native (`PackedAstExtras.kt`) | JVM dig (Markdown.kt source) |
|---|---|---|
| `headingLevel: Int?` | `headingLevelExtra()` | ATX_n type → n (1453-1460) |
| `codeLang: String?` | `codeLangExtra()` | `FENCE_LANG` token (1774) |
| `linkHref: String?` | `linkHrefExtra()` | `LINK_DESTINATION` token (1604/2566) |
| `linkTitle: String?` | `linkTitleExtra()` | (JB: no title token in current code — returns null) |
| `imageSrc: String?` | `linkHrefExtra()` (Image) | `LINK_DESTINATION` token (1689) |
| `taskChecked: Boolean?` | `taskCheckedExtra()` | `CHECK_BOX` text `== "[x]"` (1534) |
| `listStart: Long?` | `listStartExtra()` | `LIST_NUMBER` literal (1930) — but JVM render keeps slicing literal, see R-E2 |
| `tableAlignments: List<TableAlign>?` | `tableAlignmentsExtra()` → null | null (delimiter row unread) — §5 |

> **`linkTitle` note**: today's renderer does NOT read link titles at all (no
> `findChildOfTypeRecursive(LINK_TITLE)` site exists). The accessor is in the spec for
> completeness/future use; both trees may return null for the title without changing current
> output. `linkTitleExtra()` decodes it on the native side (it's in extras) but the renderer
> ignores it — harmless. (Sample `05-links-with-titles.md` exercises the parse, not a
> render difference.)

---

## 7. Coverage risks / known unknowns for Stage 3-4 implementers

| ID | Risk / unknown | Where it bites | Mitigation / who resolves |
|---|---|---|---|
| **KU-1** | `INLINE_LINK` label reconstruction (§4 H6) is the single most structurally divergent mapping. Citation pills (6-char id), search-source pills, and `citation,`-prefix handling all key off `linkText`/`linkDest`. | Stage 3 (JVM accessor) + Stage 4 (native parity) | Tests 04/05/28; **Stage-3 reviewer must scrutinize** the pill paths. Native label = concat of Link children text. |
| **KU-2** | Math node offset boundaries — does pulldown-cmark include the `$`/`$$` delimiters in `MathInline`/`MathBlock` offsets the way JetBrains does? (§4 H8) | Stage 4 native formula slice | Verify with tests 18/19 golden blobs; normalize delimiter stripping in `NativeMdNode.textIn` or in `MathInline`/`MathBlock` if they differ. |
| **KU-3** | Two ASTNode extensions are **dead code**: `getTextInNode(text, type)` (2852-2867) and `traverseChildren` (2890-2897) — no live callers found. | Stage 3 cleanup | Do NOT port them to `MdNode`. Leave the JetBrains versions until Stage 3 deletes them when the compiler proves zero refs (plan Task 9 rule 5). Flag for removal, don't remove preemptively. |
| **KU-4** | `ATX_CONTENT` wrapper-vs-flat children (§4 H1) — the heading render loop filters `child.type == ATX_CONTENT`; native headings have flat inline children. Risk of a one-level structural mismatch. | Stage 3 (heading arm rewrite) | Test 02. Decide in Task 8 whether `JvmMdNode` flattens the wrapper or maps `ATX_CONTENT → Paragraph`; record in `JvmMdNode` KDoc. |
| **KU-5** | Code-fence `completeCodeBlock` streaming flag (§4 H4) depends on `CODE_FENCE_END` offset vs `syntheticSuffixStart` — a *streaming-position* check, not purely structural. | Stage 3 + Stage 4 | Test 26 (`streaming-truncated`). Expose `codeFenceEndOffset: Int?`; native returns node endOffset or null on truncated tail. |
| **KU-6** | `StreamingMarkdownParseCache` (871-957) reads `astTree.children`, `child.type == PARAGRAPH`, `child.nextSibling()`, `child.findChildOfTypeRecursive(IMAGE/BLOCK_MATH)`, `startOffset`/`endOffset`. ALL are on the `MdNode` interface — **no token-level dependency.** | Stage 3 (carrier rename `astTree → tree`) | Confirmed safe: every access is an interface member. The block-key hashing (`"${child.type}:…"`, 938) uses `type.toString()` — `MdNodeType` enum names are stable; cache keys change format once (acceptable, cache is in-memory/per-session). |
| **KU-7** | Ordered-list literal numbering (R-E2) — JVM renders author's exact "7." via `LIST_NUMBER` slice; native reconstructs `start+index`. | Stage 4 parity | Test 14. JVM path keeps the literal slice (zero-change); native uses `listStart`. If a malformed list has non-sequential numbers, JVM and native diverge — document as accepted (native is GFM-correct). |
| **KU-8** | `MarkdownTableData` cell re-parse (R-E7) — cells route through nested `MarkdownBlock` when they contain markdown (2397). Both trees slice raw cell text, so this keeps working; do NOT render `TableCell` children directly on native. | Stage 4 | Tests 11/12/24 (CJK table cells). |
| **KU-9** | `LeafASTNode` check (F8, line 2494) → `children.isEmpty()`. A native non-leaf with zero children (edge case) would change branch. | Stage 3 | Both `Emphasis`-with-no-content etc. are degenerate; covered by test 30 (`empty-and-whitespace`). |

**GATE status: PASS.** 45 distinct JetBrains identifiers (29 mapping rows + token sub-rows)
mapped, 15 extraction patterns mapped, 9 hard token cases each given a written non-TBD
strategy, table alignment resolved (option c — genuinely unused), 30-variant `MdNodeType` enum
derived and paste-ready. Zero unresolved rows.
