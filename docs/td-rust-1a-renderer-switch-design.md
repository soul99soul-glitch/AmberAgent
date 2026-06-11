# TD.Rust.1a Markdown Renderer Switch — Design (Phase 3-B)

**Date**: 2026-06-12
**Status**: Approved design; supersedes the "defer" recommendation in
`td-rust-1a-feasibility.md` (preconditions re-evaluated with user, see §1).
**Prereq doc**: `docs/td-rust-1a-feasibility.md` (Option C selected)
**Plan doc**: `docs/RUST_NATIVE_SPIKE_PLAN.md` §10-B

## 1. Decisions locked with user (2026-06-12)

| Decision | Choice | Note |
|---|---|---|
| Timing | Full work now, prep through switch | Streaming-reveal work on Markdown.kt is confirmed finished by the user; the "quiet ≥2 weeks" gate was multi-contributor insurance and is waived for solo work |
| Test rig | Structural semantics comparison, zero new deps | Robolectric 4.14.1 + Compose UI Test already in `:app` testImplementation |
| JVM fallback | **Kept.** JetBrains markdown dependency stays | D-6 (delete dep, ~1 MiB APK) deferred to a separate decision after one stable release cycle |
| Approach | Option C — unified interface, dual implementations, 4 stages | Option B (direct re-type + fallback encoder) rejected: fallback would become novel code, weakening the insurance property |

## 2. Current facts (measured 2026-06-12)

- `Markdown.kt` is **2916 LOC** (feasibility doc said 2009 — streaming work grew it),
  **36** `ASTNode` references, **88** `MarkdownElementTypes./GFMElementTypes./MarkdownTokenTypes.`
  pattern-match sites.
- Parse funnel is a single function: `parsePreprocessedMarkdownUncached()` —
  all entry points (cold, cached, `StreamingMarkdownParseCache`) flow through it.
  `maybeShadowCompareNativeAst()` hangs off it today.
- `MarkdownParseResult(preprocessed, astTree: ASTNode, hasHtmlBlocks)` is the
  central carrier (line ~273).
- Kotlin `PackedAstNode` (in `MarkdownParserNative.kt`) already exposes:
  `type/typeCode`, `startOffset/endOffset`, `children`, `parent`,
  `nextSibling()`, `findChildOfTypeRecursive()`, raw `extras: ByteArray`,
  and an iterative `skipNode` walker.
- Rust `tree_builder.rs` already encodes extras: heading level, code-fence
  lang, link dest_url+title, image dest_url+title, task-list checked byte,
  table alignment bytes. **No Rust changes expected** (Stage 2 gap analysis
  may surface small additions, e.g. ordered-list start number — verify).
- Host-JVM unit tests **cannot load the Android .so** (existing
  `MarkdownPreprocessParityTest` is a documentation-pin for this reason) —
  drives the golden-blob rig design (§6).

> **Naming errata (2026-06-12, plan phase)**: the interface ships as `MdNode`
> (not `MarkdownNode` as written below) — `Markdown.kt:1410` already has a
> `private fun MarkdownNode(...)` composable and the names would collide.

## 3. Architecture

New subpackage `app/src/main/java/app/amber/feature/ui/components/richtext/tree/`:

- **`MarkdownTree.kt`** — `interface MarkdownNode` + `enum class MdNodeType`
  (unified node-type enum, final variant list produced by Stage 2 gap
  analysis; unmappable JetBrains token-level types are re-expressed as
  interface semantics, not enum variants).
- **`JvmMarkdownTree.kt`** — wraps JetBrains `ASTNode` + source text.
  Maps `IElementType → MdNodeType`. Typed attribute accessors implemented by
  digging child tokens — i.e. the renderer's existing extraction logic
  relocated here, byte-for-byte behavior preserved.
- **`NativeMarkdownTree.kt`** — wraps `PackedAstNode`. Maps
  `NodeType → MdNodeType`. Typed attribute accessors delegate to extras
  decoders that live next to `PackedAstNode` in the nativebridge package
  (closest to the wire format, unit-testable without the interface; string
  encoding mirrors Rust `encode_string`: length-prefixed UTF-8 — confirm
  exact prefix format against `tree_builder.rs` in Stage 2).

`MarkdownParseResult.astTree: ASTNode` → `tree: MarkdownNode`. The renderer
(all of Markdown.kt) types against the interface only.

### Interface surface (minimum required by renderer audit)

```kotlin
interface MarkdownNode {
    val type: MdNodeType
    val startOffset: Int
    val endOffset: Int
    val children: List<MarkdownNode>
    val parent: MarkdownNode?
    fun nextSibling(): MarkdownNode?
    fun findChildOfTypeRecursive(vararg types: MdNodeType): MarkdownNode?
    // Typed attributes — REQUIRED, not convenience: the two trees differ
    // structurally below the inline level (JetBrains stores href/level as
    // child tokens; pulldown-cmark stores them in extras and emits no such
    // token children).
    val headingLevel: Int?
    val codeLang: String?
    val linkHref: String?
    val linkTitle: String?
    val imageSrc: String?
    val taskChecked: Boolean?
    val listStart: Int?
    val tableAlignments: List<TableAlignment>?
}
```

(Exact member list to be finalized by the Stage 2 audit of all 88 match
sites; additions allowed, removals require re-approval.)

## 4. Data flow & selection point

Single change site — `parsePreprocessedMarkdownUncached()`:

```
flag markdownAst ON
  → MarkdownParserNative.parse(preprocessed)        — null → JVM fallback
  → PackedAstReader(blob).isValid                   — false → JVM fallback
  → PackedAstReader.validate()  (new, see §5)       — false → JVM fallback
  → NativeMarkdownTree
flag OFF or any failure
  → JetBrains parse → JvmMarkdownTree   (today's exact path)
```

- `StreamingMarkdownParseCache` needs no changes (funnels through the same
  function).
- `maybeShadowCompareNativeAst()` retires in Stage 4 — its parity job is
  taken over by the test rig; remove the `markdownAst`-as-shadow-flag
  semantics and repurpose the flag as the real selector.
- `hasHtmlBlocks`: native path reads the blob header flag bit; JVM path
  keeps the recursive `containsHtmlBlocks()` walk.

## 5. Error handling

Three fallback layers, all routing telemetry through the existing
`MarkdownNativeSwitch` Crashlytics pipeline (`NativePathFailure`):

1. `.so` load failure → `parse()` returns null → JVM.
2. Parse failure / empty blob / wrong magic-version → JVM.
3. **New: eager blob bounds validation.** `PackedAstNode` decodes lazily, so
   a corrupt blob would otherwise throw out-of-bounds **during composition**
   (UI crash). Add `PackedAstReader.validate()`: one `skipNode` pass over the
   whole blob at parse time (pure varint scanning, no allocation) so no
   render-time access can run past the buffer. Validation failure → JVM +
   Crashlytics report.

Renderer itself contains **no** native/JVM branching — if a tree was handed
to it, the tree is safe to walk.

## 6. Test rig (Stage 1) — golden-blob structural parity

Host JVM cannot load the Android `.so`, so the rig never crosses JNI:

- **Corpus**: 30+ real markdown samples under
  `app/src/test/resources/markdown-corpus/` — GFM tables, nested lists,
  fenced code with languages, KaTeX inline/block, links/images, task lists,
  footnotes, HTML blocks, CJK, streaming-truncated tails, long messages,
  blockquotes, thematic breaks, emphasis nesting.
- **Golden blobs**: new Rust bin `cargo run -p markdown-parser --bin
  dump-corpus` parses each sample and writes `<sample>.pmda` next to it.
  Checked into test resources. Regeneration script
  `native/markdown-parser/regen-corpus.sh` documented in the test class
  header. A staleness guard test asserts blob header version ==
  `PackedAstReader.SUPPORTED_VERSION`.
- **Parity test** (`MarkdownTreeParityTest`, Robolectric + Compose UI Test):
  for each sample, render the real Markdown composable twice — once with
  `JvmMarkdownTree` (JVM parse), once with `NativeMarkdownTree` (golden
  blob) — dump semantics trees (text content, structure, link annotations)
  and assert equality after trivial-whitespace normalization.
- **Rig lifecycle across stages**: Stage 1 renders each sample through
  today's renderer and commits the semantics dumps as golden snapshots
  (no interface exists yet). Stage 3 asserts the re-typed renderer still
  matches those Stage-1 snapshots exactly (zero-change proof). Stage 4
  switches the test to dual-render parity (JvmMarkdownTree vs
  NativeMarkdownTree from golden blobs).

JNI boundary itself is NOT covered by the rig — it is already exercised by
Phase 1/2 benchmarks and on-device dogfood; the rig's scope is renderer
parity only.

## 7. Stages (each independently landable & revertable)

| Stage | Content | Touches Markdown.kt? | Est. |
|---|---|---|---|
| 1 | Corpus + golden blobs + parity rig pinning today's JVM output | No | ~1 d |
| 2 | Gap analysis: 88 match sites → `MdNodeType` mapping table (committed as `docs/td-rust-1a-nodetype-mapping.md`); typed extras decoders on `PackedAstNode` + unit tests; verify `encode_string` format & ordered-list start | No | ~1 d |
| 3 | Introduce interface + `JvmMarkdownTree`; mechanically re-type Markdown.kt against the interface. **Behavior zero-change** — rig must stay green with identical pinned output | Yes (mechanical) | ~1.5-2 d |
| 4 | `NativeMarkdownTree` + selection logic in parse funnel + `validate()` + shadow-compare retirement; rig gains native side | Yes (funnel only) | ~1 d |

Total ≈ 4-5 days. Commit discipline: ≥1 commit per stage, merge-commit style
so any stage reverts in one step.

## 8. Rollout & scope boundary

- Stage 4 lands with `markdownAst` default **false** (unchanged).
- Flip to **true** only after: rig green on all corpus samples + several
  days of user dogfood on a real device with `sampleRate` temporarily > 0.
- Global RC `native_path_kill_switch` continues to gate the path.
- **Out of scope**: D-6 (delete JetBrains dep), D-7 (per-component kill
  switch), D-3 (CharReveal tuning), any Rust crate changes beyond what
  Stage 2 gap analysis strictly requires.

## 9. Risks

- **88 match sites > the 41 the feasibility doc estimated** — Stage 2 may
  surface token-level matches with no clean interface expression; those get
  case-by-case design notes in the mapping table before Stage 3 starts.
- **Semantics comparison is not pixel comparison** — purely visual styling
  divergence (e.g. font weight from a mis-mapped emphasis type) shows up as
  structure/annotation diffs in most cases, but not all. Accepted by user;
  Roborazzi can be added later if dogfood surfaces a visual-only bug.
- **Golden blobs can go stale vs the Rust crate** — mitigated by the
  staleness guard test + regen script; true drift also caught by on-device
  dogfood before the flag flips.
- **[B-CJK] CJK-flanked `**strong**` fails on the native path (parser-level,
  upstream)** — pulldown-cmark's CommonMark left/right-flanking rules treat CJK
  as non-punctuation, so a `**…**` run whose BOTH flanks are CJK is
  simultaneously left- and right-flanking and never opens/closes. Source
  `常被称为**"离线优先"**策略` renders the literal asterisks on the native path;
  the JetBrains JVM tree bolds it. This is in the renderer's INPUT (the parse),
  not its shape-dispatch, so it is NOT renderer-fixable — it needs an upstream
  pulldown-cmark fix or a CJK-aware delimiter pass. It is the PRIMARY flag-flip
  blocker for CJK-heavy usage and is tracked as parity class B-CJK (sample 24,
  `MarkdownTreeParityTest`).
