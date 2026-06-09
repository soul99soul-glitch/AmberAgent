# TD.Rust.1a Markdown Renderer Switch — Feasibility Analysis

**Date**: 2026-05-28
**Author**: refactor agent (Phase D cascade final-mile)
**Status**: Decision document; no code changes proposed in this pass.

## Background

The user-facing markdown rendering pipeline in `:app/.../richtext/Markdown.kt`
(2009 LOC) currently consumes `org.intellij.markdown.ast.ASTNode` — a JetBrains
tree node — as its primary AST representation. PR #10 landed a parallel
shadow path: native Rust parser (`markdown-parser` crate) emits a binary
`PackedAstReader` blob that's read in parallel via
`maybeShadowCompareNativeAst()` and compared structurally for parity.

The goal task TD.Rust.1a calls for switching the renderer's primary AST
consumer from `ASTNode` to `PackedAstReader`. This would let the renderer
skip the JetBrains parse entirely when the native path is enabled — the
biggest available CPU win in the Markdown pipeline.

## Impact radius

`Markdown.kt` is 2009 LOC with **41 references** to `ASTNode` or `astTree`.
Concretely:

- **`MarkdownParseResult.astTree: ASTNode`** (line 256) — the central data
  carrier. Every renderer entry-point reads `data.astTree.children`.
- **Private helpers with `ASTNode` parameters** (lines 460, 491, 836, 871,
  1212, 1245, 1276, 1320, 1339, 1494, 1551, 1597, 1983, 1987, 2004, 2016,
  2025, 2034): 18+ functions that destructure ASTNodes via JetBrains API
  (`children`, `type`, `startOffset`, `endOffset`, sibling traversal).
- **Top-level extension functions on ASTNode** (`containsHtmlBlocks`,
  `getTextInNode`, `nextSibling`, `findChildOfTypeRecursive`,
  `traverseChildren`, `trim`).
- **Public surface**: `internal fun extractMarkdownTableData(node: ASTNode,
  content: String): MarkdownTableData?` (line 1551) — consumed by table
  components outside Markdown.kt.

The shadow path already wraps `PackedAstReader.root()` + `.children`
under a try-catch (line 506-532), but the **render path itself cannot
consume PackedAstReader today** — it's typed against ASTNode.

## Shadow path's current capability coverage

`PackedAstNode` exposes:
- `typeCode: Int`, `type: NodeType` (custom enum, not `IElementType`)
- `startOffset: Int`, `endOffset: Int`
- `nextSibling()`, `findChildOfTypeRecursive(vararg types: NodeType)`
- `children` (children iteration)

**Gap analysis vs. JetBrains ASTNode API used by the renderer**:

| ASTNode API | PackedAstReader equivalent | Status |
|---|---|---|
| `.type: IElementType` | `.type: NodeType` | **DIFFERENT enum**. NodeType is the native-side enum; IElementType is JetBrains' sealed-class hierarchy with MarkdownElementTypes / GFMElementTypes / MarkdownTokenTypes. Type-switching code uses pattern-match on JetBrains types that have no NodeType counterpart for every case. |
| `.children: List<ASTNode>` | `.children` | Equivalent shape. ✓ |
| `.startOffset`, `.endOffset` | Same names | ✓ |
| `.nextSibling()`, `.findChildOfTypeRecursive(IElementType)` | Same names, NodeType arg | Surface match, but parameter type mismatch. ✓ structurally. |
| `getTextInNode(text, type: IElementType)` | Requires NodeType arg | API contract differs — need an overload or generic mapping. |
| `IElementType` pattern matching: `when (node.type) { is MarkdownElementTypes.PARAGRAPH -> … }` | NodeType has parallel enum constants | Coverage probably ≥95%; needs case-by-case audit. |

**Verdict**: PackedAstReader has the *structural* surface but a
**type-enum disjoint**. The shadow path validates *parity* (structure
counts), not *coverage* (which JetBrains element types map 1:1 to
NodeType variants).

## Switch options

### Option A — JVM adapter (PackedAstReader → ASTNode wrapper)

Wrap the binary blob in a class that implements `ASTNode` (JetBrains
interface). The renderer's existing code path is unchanged; the adapter
translates each `.children` / `.type` / `.startOffset` access to a
PackedAst lookup.

**Pros**: Zero renderer changes. Full ASTNode pattern-match compatibility.
**Cons**: **Defeats the perf win entirely**. The adapter must materialize
ASTNode instances on every access; each .children call decodes the blob
and allocates JVM objects equivalent to the JetBrains parser's output.
Net cost vs. status quo: ≥ same (adapter overhead added on top of native
parse). User-perceptible delta: zero or negative.

### Option B — Renderer rewrite (consume PackedAstReader directly)

Re-type every internal helper from `ASTNode` to `PackedAstNode`. Audit
each `is MarkdownElementTypes.X` pattern match and map to the equivalent
`NodeType.X`. Replace `getTextInNode(text, type)` with the NodeType
variant. Update `extractMarkdownTableData` and other public-surface
helpers.

**Pros**: Full native perf win (no JetBrains parse on enabled path).
**Cons**: 2009-LOC diff. The type-system change cascades through every
private helper and the public `MarkdownTableData` extraction site. No
single-commit slice is safe — the renderer's pattern matches on element
types are scattered.

**Estimated effort**: 2-3 days of careful work + on-device QA sweep
across 30 representative markdown samples (KaTeX, GFM tables, nested
lists, fenced code, HTML blocks, mixed inline). Without QA the
regression surface is too large.

### Option C — Dual-track via interface

Define a `MarkdownTree` interface with the methods the renderer needs:
- `val children: List<MarkdownNode>`
- `val type: MarkdownNodeType` (NEW unified enum that maps to both
  IElementType and NodeType variants)
- `val startOffset: Int`, `val endOffset: Int`
- helper extensions `containsHtmlBlocks`, `findChildOfTypeRecursive`,
  `nextSibling`, `getTextInNode`

Two implementations: `JvmMarkdownTree` (wraps ASTNode) and
`NativeMarkdownTree` (wraps PackedAstReader). The renderer types against
the interface.

**Pros**: Per-call dispatch overhead is negligible (interface vs. class
dispatch is JIT-inlined). Enables A/B testing per-platform. Path
selection happens at parse time, not in the renderer.

**Cons**: Still a 2009-LOC diff (the interface adoption). PLUS the
`MarkdownNodeType` unified enum definition has to be the union of both
type-enums' interesting cases (~30 variants). Slightly bigger than
Option B because of the abstraction layer.

**Estimated effort**: 3-4 days. The MarkdownNodeType unification is the
new work; the renderer adoption is similar to Option B.

## Minimal viable slice

A genuinely shippable single-commit slice would be one of:

1. **Type-rename micro-slice**: Add a `typealias ASTNode = JvmASTNode`
   in a new file. Update `Markdown.kt` imports to use the local alias.
   This is purely cosmetic but lays the typealias-foundation that later
   commits can replace with the interface from Option C. Risk: zero (no
   behaviour change). Value: prepares the migration's first step without
   committing to a path.

2. **Public surface freeze**: Convert
   `internal fun extractMarkdownTableData(node: ASTNode, ...)` to
   `internal fun extractMarkdownTableData(tree: MarkdownTableSource, ...)`
   where MarkdownTableSource is a stable data class (already-extracted
   row/cell structure). Decouples the cross-file table-extraction call
   from the AST type. Risk: low (one helper signature change). Value:
   one of the two cross-file ASTNode dependencies eliminated.

Either alone is small enough to land + Gate Review. Neither delivers
user-visible perf on its own.

## Risks

- **GFM extension coverage**: `GFMElementTypes` (strikethrough, tables,
  task list items) — the native NodeType enum coverage of these is
  not validated by the shadow path. If a GFM element type has no
  NodeType equivalent the renderer hits a fallback branch that may
  render differently.
- **HTML block handling**: the renderer special-cases
  `astTree.children.any { it.type == MarkdownElementTypes.HTML_BLOCK }`
  and routes those messages through a separate Compose component. Need
  to verify native parser emits the same boundary.
- **Streaming**: the StreamingMarkdownParseCache (lines 516-575)
  destructures `astTree.children` on every chunk. The streaming caller
  would need a native-equivalent re-parse path, which requires
  PackedAstReader to be cheaply seekable mid-stream (the current shadow
  path always parses from scratch).
- **Test coverage**: there are NO existing renderer parity tests at
  the visual level. The shadow path's structural compare is necessary
  but not sufficient — it doesn't catch "renders the same DOM but
  with subtly different inline formatting".

## Recommendation

**Defer the full switch.** Ship Option (1) typealias-foundation as a
prep commit if downstream code requires it, but do not pursue Option B
or C without:

1. A device-test rig that compares rendered Compose output for 30+
   markdown samples (KaTeX, GFM features, edge cases).
2. The NodeType enum gap-analysis above made concrete (file all
   pattern-match sites in Markdown.kt, map each to NodeType, flag the
   ones without coverage).
3. A staged rollout plan (e.g., new flag `markdownAstPrimary` defaulting
   to false; flip to true on debug builds first).

The cost of getting this wrong is regression on the highest-frequency
user surface (chat message rendering). The cost of NOT doing it is
keeping the current shadow-only mode, which is already validating
parity and providing observability. The status quo is safe; the move
is risky and reversible only in proportion to the test rig built first.

## Concrete next actions (if/when prioritized)

1. Build the device-test rig (separate sprint, ~2 days).
2. Run gap-analysis: `grep -n "MarkdownElementTypes\|GFMElementTypes\|MarkdownTokenTypes" Markdown.kt` → list every pattern-match site → cross-reference NodeType enum constants → produce a coverage table.
3. Land Option (1) typealias as a prep commit.
4. Pursue Option C (interface) in a dedicated 3-4 day sprint with the test rig as continuous validation.

**Cumulative cost**: ~5-7 days of focused work on Markdown.kt only.
**User-perceptible benefit**: native Rust parser becomes the only
parse path when enabled; estimated 2-4x parse speed-up on long
markdown messages (most LLM streaming).

**Decision-maker question to revisit when this comes up next**: "Has
anyone built the device-test rig yet?" If no → defer further. If
yes → estimate cascades into a real sprint with a dedicated commit
review.
