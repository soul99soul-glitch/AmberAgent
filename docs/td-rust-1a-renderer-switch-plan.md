# TD.Rust.1a Renderer Switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Switch the Markdown renderer's primary AST consumer from JetBrains `ASTNode` to the Rust packed AST, behind a unified tree interface, with the JVM path retained as fallback.

**Architecture:** A new `MdNode` interface + `MdNodeType` unified enum decouple the 2916-line renderer from any concrete parser. `JvmMdTree` wraps JetBrains `ASTNode` (today's behavior, relocated); `NativeMdTree` wraps `PackedAstNode` (Rust blob). Selection happens only in `parsePreprocessedMarkdownUncached()`. A golden-blob + semantics-snapshot test rig guards every stage.

**Tech Stack:** Kotlin / Jetpack Compose, Robolectric 4.14.1 + Compose UI Test (already in `:app`), Rust (`markdown-parser` crate, pulldown-cmark), packed binary wire format `PMDA` v1.

**Spec:** `docs/td-rust-1a-renderer-switch-design.md` (approved 2026-06-12). One naming deviation from the spec: the interface is `MdNode`, NOT `MarkdownNode`, because `Markdown.kt:1410` already has a `private fun MarkdownNode(...)` composable — the spec name would collide.

**Working rules for every task:**
- Branch: work directly on `main` (project convention), ≥1 commit per task, message prefix `refactor(td-rust-1a): ...` or `test(td-rust-1a): ...`.
- Never touch: `markdownAst` flag default (stays `false` until rollout), unrelated working-tree changes (`.DS_Store`, `Highlighter.kt`, gradle files etc. — DO NOT `git add -A`; always add explicit paths).
- Compile gate per task: `./gradlew :app:compileDebugKotlin` must pass before commit.
- Test command base: `./gradlew :app:testDebugUnitTest --tests "<filter>"`.

## File structure (locked)

```
native/markdown-parser/src/bin/dump_corpus.rs          NEW  golden-blob generator
native/markdown-parser/src/lib.rs                      MOD  +2 lines pub use
native/markdown-parser/Cargo.toml                      MOD  nothing needed ([[bin]] auto-discovered in src/bin/)
native/markdown-parser/regen-corpus.sh                 NEW  blob regen script
app/src/test/resources/markdown-corpus/*.md            NEW  32 samples
app/src/test/resources/markdown-corpus/*.pmda          NEW  golden blobs (generated)
app/src/test/resources/markdown-corpus-snapshots/*.txt NEW  semantics goldens (generated)
app/src/test/.../richtext/tree/SemanticsDump.kt        NEW  test helper (test sourceset)
app/src/test/.../richtext/tree/MarkdownRendererSnapshotTest.kt  NEW  Stage-1/3 rig
app/src/test/.../richtext/tree/MarkdownTreeParityTest.kt        NEW  Stage-4 rig
app/src/main/.../richtext/nativebridge/PackedAstExtras.kt       NEW  typed extras decoders
app/src/test/.../richtext/nativebridge/PackedAstExtrasTest.kt   NEW
docs/td-rust-1a-nodetype-mapping.md                    NEW  Stage-2 gap analysis output
app/src/main/.../richtext/tree/MarkdownTree.kt         NEW  MdNode + MdNodeType
app/src/main/.../richtext/tree/JvmMdTree.kt            NEW
app/src/main/.../richtext/tree/NativeMdTree.kt         NEW  (Stage 4)
app/src/test/.../richtext/tree/JvmMdTreeTest.kt        NEW
app/src/test/.../richtext/tree/NativeMdTreeTest.kt     NEW  (Stage 4)
app/src/main/.../richtext/Markdown.kt                  MOD  re-type (Stage 3) + funnel (Stage 4)
app/src/main/.../richtext/nativebridge/MarkdownParserNative.kt  MOD  +validate() (Stage 4)
```

(`app/src/main/...` = `app/src/main/java/app/amber/feature/ui/components/richtext`; test mirrors under `app/src/test/java/...`.)

---

## STAGE 1 — Test rig + corpus (no renderer changes)

### Task 1: dump-corpus Rust bin

**Files:**
- Modify: `native/markdown-parser/src/lib.rs` (top, near line 15-16)
- Create: `native/markdown-parser/src/bin/dump_corpus.rs`

- [ ] **Step 1: Re-export pure entry points.** In `lib.rs`, the modules are private (`mod packed_ast; mod tree_builder;`). Below them add:

```rust
// Pure (non-JNI) entry points for the dump-corpus bin and host-side tools.
pub use packed_ast::pack;
pub use tree_builder::build_tree;
```

If `cargo check` complains `Tree` is not reachable, also add `pub use tree_builder::Tree;`.

- [ ] **Step 2: Write the bin.** Create `native/markdown-parser/src/bin/dump_corpus.rs`:

```rust
//! Golden-blob generator for the renderer parity rig.
//! Usage: cargo run -p markdown-parser --bin dump_corpus -- <dir>
//! For every <name>.md in <dir>, writes <name>.pmda (packed AST) beside it.

use std::{env, fs, process};

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() != 2 {
        eprintln!("usage: dump_corpus <corpus-dir>");
        process::exit(2);
    }
    let mut paths: Vec<_> = fs::read_dir(&args[1])
        .expect("read corpus dir")
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "md"))
        .collect();
    paths.sort();
    if paths.is_empty() {
        eprintln!("no .md samples found in {}", args[1]);
        process::exit(1);
    }
    for path in &paths {
        let text = fs::read_to_string(path).expect("read sample");
        let blob = markdown_parser::pack(&markdown_parser::build_tree(&text));
        fs::write(path.with_extension("pmda"), &blob).expect("write blob");
    }
    println!("wrote {} blobs", paths.len());
}
```

- [ ] **Step 3: Verify it builds and runs.**

```bash
cd native && cargo check -p markdown-parser && \
mkdir -p /tmp/corpus-smoke && printf '# h\n\ntext **b**\n' > /tmp/corpus-smoke/smoke.md && \
cargo run -q -p markdown-parser --bin dump_corpus -- /tmp/corpus-smoke && \
xxd -l 8 /tmp/corpus-smoke/smoke.pmda
```

Expected: `wrote 1 blobs`; first 4 bytes of the hexdump are `504d 4441` (`PMDA`).

- [ ] **Step 4: Run crate tests, then commit.**

```bash
cd native && cargo test -p markdown-parser
git add native/markdown-parser/src/lib.rs native/markdown-parser/src/bin/dump_corpus.rs
git commit -m "test(td-rust-1a): dump_corpus golden-blob generator bin"
```

### Task 2: Corpus — 32 samples

**Files:**
- Create: `app/src/test/resources/markdown-corpus/<NN>-<topic>.md` × 32

- [ ] **Step 1: Author the samples.** Realistic chat-style markdown, 15-80 lines each, numbered for stable ordering. Required list (coverage matrix from spec §6):

```
01-plain-paragraphs.md      02-headings-all-levels.md   03-emphasis-nesting.md
04-links-inline.md          05-links-with-titles.md     06-images.md
07-fenced-code-kotlin.md    08-fenced-code-no-lang.md   09-indented-code.md
10-inline-code.md           11-gfm-table-simple.md      12-gfm-table-aligned.md
13-nested-lists.md          14-ordered-list-start.md    15-task-lists.md
16-blockquotes-nested.md    17-thematic-breaks.md       18-katex-inline.md
19-katex-block.md           20-html-block.md            21-inline-html.md
22-footnotes.md             23-strikethrough.md         24-cjk-mixed.md
25-long-message.md          26-streaming-truncated.md   27-hard-soft-breaks.md
28-link-edge-cases.md       29-mixed-everything.md      30-empty-and-whitespace.md
31-deep-nesting.md          32-special-chars-escapes.md
```

Mandatory contents for the tricky ones — `14-ordered-list-start.md` must start a list at `7.`; `26-streaming-truncated.md` must end mid-fence:

````markdown
Here is a code example:

```kotlin
fun partial(
````

`24-cjk-mixed.md` must mix CJK + emphasis + table cells with Chinese text. `12-gfm-table-aligned.md` must use all three alignments (`:---`, `:---:`, `---:`). `18/19-katex` use `$...$` / `$$...$$` (post-preprocess form). `30-empty-and-whitespace.md` contains only blank lines and spaces.

- [ ] **Step 2: Generate blobs and commit.**

```bash
cd native && cargo run -q -p markdown-parser --bin dump_corpus -- \
  ../app/src/test/resources/markdown-corpus
ls ../app/src/test/resources/markdown-corpus/*.pmda | wc -l   # expect 32
git add app/src/test/resources/markdown-corpus/
git commit -m "test(td-rust-1a): 32-sample markdown corpus + golden blobs"
```

### Task 3: regen script + staleness guard

**Files:**
- Create: `native/markdown-parser/regen-corpus.sh`

- [ ] **Step 1: Write the script.**

```bash
#!/usr/bin/env bash
# Regenerate golden .pmda blobs after ANY markdown-parser crate change.
set -euo pipefail
cd "$(dirname "$0")/../.."
( cd native && cargo run -q -p markdown-parser --bin dump_corpus -- \
    ../app/src/test/resources/markdown-corpus )
echo "Done. Re-run :app tests; commit changed .pmda files together with the crate change."
```

`chmod +x native/markdown-parser/regen-corpus.sh`

- [ ] **Step 2: Commit.**

```bash
git add native/markdown-parser/regen-corpus.sh
git commit -m "test(td-rust-1a): corpus blob regen script"
```

(The version-staleness guard test is part of Task 4's harness: it asserts every blob's header version byte == `PackedAstReader` supported version, failing with "run regen-corpus.sh".)

### Task 4: Semantics snapshot harness (Stage-1 form)

**Files:**
- Create: `app/src/test/java/app/amber/feature/ui/components/richtext/tree/SemanticsDump.kt`
- Create: `app/src/test/java/app/amber/feature/ui/components/richtext/tree/MarkdownRendererSnapshotTest.kt`
- Create (generated): `app/src/test/resources/markdown-corpus-snapshots/*.txt`

Before writing, check how existing Robolectric+Compose tests in `app/src/test` are configured (`grep -rl createComposeRule app/src/test`) and copy their runner/@Config setup. Render through the public entry `MarkdownBlock(...)` (`Markdown.kt:1012`) — read its signature first and fill required params with production-typical values (the chat path's defaults).

- [ ] **Step 1: Write `SemanticsDump.kt`** — a deterministic, normalized dump:

```kotlin
package app.amber.feature.ui.components.richtext.tree

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * Deterministic text dump of a semantics tree for golden comparison.
 * Captures structure + text + roles + link annotations; deliberately
 * ignores positions/sizes (layout jitter) and unmerged internals.
 */
fun SemanticsNode.dumpNormalized(indent: String = ""): String = buildString {
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }
    val desc = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")
    val role = config.getOrNull(SemanticsProperties.Role)?.toString()
    val parts = listOfNotNull(
        role?.let { "role=$it" },
        text?.let { "text=${it.normalizeWs()}" },
        desc?.let { "desc=${it.normalizeWs()}" },
    )
    if (parts.isNotEmpty()) appendLine("$indent${parts.joinToString(" ")}")
    children.forEach { append(it.dumpNormalized("$indent  ")) }
}

private fun String.normalizeWs(): String = replace(Regex("[ \\t]+"), " ").trim()
```

- [ ] **Step 2: Write the snapshot test.**

```kotlin
package app.amber.feature.ui.components.richtext.tree

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Stage-1 golden pin of today's renderer output for the 32-sample corpus.
 * Regenerate goldens: ./gradlew :app:testDebugUnitTest -PupdateMarkdownSnapshots=true \
 *   --tests "app.amber.feature.ui.components.richtext.tree.MarkdownRendererSnapshotTest"
 * Stage 3 MUST keep these byte-identical (zero-change proof of the re-type).
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownRendererSnapshotTest {

    @get:Rule val compose = createComposeRule()

    @Test fun allCorpusSamplesMatchGoldenSnapshots() { /* loop over corpus, see below */ }

    @Test fun goldenBlobsMatchSupportedWireVersion() { /* header byte 4 == 1 per blob */ }
}
```

Implementation requirements (write real code, this is the contract):
- Corpus loading: `File("src/test/resources/markdown-corpus")` (module working dir) or classloader resources — match whichever existing app tests use for resources.
- Per sample: `compose.setContent { /* theme wrapper used by chat */ MarkdownBlock(content = sampleText, ...) }`, `compose.waitForIdle()`, dump via `compose.onRoot().fetchSemanticsNode().dumpNormalized()`.
- Compare to `markdown-corpus-snapshots/<name>.txt`; if `-PupdateMarkdownSnapshots=true` (read via `System.getProperty`/gradle `-P` passthrough — wire `systemProperty` in app/build.gradle.kts testOptions if not already passed through), write instead of assert. Collect ALL mismatches and fail once with the list (don't stop at first sample).
- Compose rule + multiple setContent: one `setContent` per test instance — iterate via `org.junit.runners.Parameterized` with the sample name as parameter (one test instance per sample). This also gives readable per-sample failures.
- Wire-version guard test: read each `.pmda`, assert `blob[4].toInt() == 1`, failure message: `"golden blob stale — run native/markdown-parser/regen-corpus.sh"`.

- [ ] **Step 3: Generate goldens, run, commit.**

```bash
./gradlew :app:testDebugUnitTest -PupdateMarkdownSnapshots=true --tests "app.amber.feature.ui.components.richtext.tree.*"
./gradlew :app:testDebugUnitTest --tests "app.amber.feature.ui.components.richtext.tree.*"   # must PASS
git add app/src/test/java/app/amber/feature/ui/components/richtext/tree/ \
        app/src/test/resources/markdown-corpus-snapshots/ app/build.gradle.kts
git commit -m "test(td-rust-1a): semantics snapshot rig pinning current renderer output"
```

If KaTeX/image samples produce nondeterministic dumps (async loads), normalize those nodes in `dumpNormalized` (e.g. map image nodes to `image=<desc>`) — determinism beats fidelity here; document any normalization in the test header.

---

## STAGE 2 — Gap analysis + extras decoders

### Task 5: Typed extras decoders

**Files:**
- Create: `app/src/main/java/app/amber/feature/ui/components/richtext/nativebridge/PackedAstExtras.kt` — note: nativebridge for the MAIN bridge is `app/src/main/java/app/amber/agent/ui/components/richtext/nativebridge/` (where `MarkdownParserNative.kt` lives). Put `PackedAstExtras.kt` THERE, same package, so it can stay `internal`-friendly with `PackedAstNode`.
- Create: `app/src/test/java/app/amber/agent/ui/components/richtext/nativebridge/PackedAstExtrasTest.kt`

Wire format facts (verified against `native/markdown-parser/src/tree_builder.rs`):
- string = LEB128 varint length + UTF-8 bytes (`encode_string`)
- `Heading`: 1 byte level (1-6)
- `CodeBlock`: string lang ("" for indented)
- `Link`/`Image`: string dest_url + string title
- `TaskListMarker`: 1 byte (1=checked)
- `ListOrdered`: u64 little-endian start number (8 bytes)
- `Table`: 1 byte per column — **verify exact byte values in `tree_builder.rs` Table arm before coding; record them in the KDoc**

- [ ] **Step 1: Write failing tests** with handcrafted byte arrays (varint cases: 0, 127, 128, multi-byte; UTF-8 with CJK; u64 LE start=7; checked/unchecked) AND one spot-check decoding `extras` from real golden blobs (load `14-ordered-list-start.pmda`, walk to the ordered-list node, assert `listStart == 7L`; same idea for heading level, code lang, link href from other samples).

- [ ] **Step 2: Run, verify failure** (`--tests "*.PackedAstExtrasTest"` → compile error/FAIL).

- [ ] **Step 3: Implement** as extension accessors on `PackedAstNode`:

```kotlin
package app.amber.agent.ui.components.richtext.nativebridge

/** Typed decoders for [PackedAstNode.extras]. Wire format per tree_builder.rs. */

internal fun PackedAstNode.headingLevelExtra(): Int? =
    if (type == NodeType.Heading && extras.isNotEmpty()) extras[0].toInt() else null

internal fun PackedAstNode.codeLangExtra(): String? =
    if (type == NodeType.CodeBlock) extras.readString(0)?.first?.ifEmpty { null } else null

internal fun PackedAstNode.linkHrefExtra(): String? =
    if (type == NodeType.Link || type == NodeType.Image) extras.readString(0)?.first else null

internal fun PackedAstNode.linkTitleExtra(): String? {
    if (type != NodeType.Link && type != NodeType.Image) return null
    val (_, next) = extras.readString(0) ?: return null
    return extras.readString(next)?.first?.ifEmpty { null }
}

internal fun PackedAstNode.taskCheckedExtra(): Boolean? =
    if (type == NodeType.TaskListMarker && extras.isNotEmpty()) extras[0].toInt() == 1 else null

internal fun PackedAstNode.listStartExtra(): Long? {
    if (type != NodeType.ListOrdered || extras.size < 8) return null
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (extras[i].toLong() and 0xFF)
    return v
}

/** @return decoded string + offset just past it, or null on truncation. */
private fun ByteArray.readString(at: Int): Pair<String, Int>? {
    var value = 0L; var shift = 0; var cursor = at
    while (true) {
        if (cursor >= size || shift > 63) return null
        val b = this[cursor].toInt() and 0xFF
        value = value or ((b and 0x7F).toLong() shl shift)
        cursor++
        if (b and 0x80 == 0) break
        shift += 7
    }
    val end = cursor + value.toInt()
    if (end > size || value > Int.MAX_VALUE) return null
    return String(this, cursor, value.toInt(), Charsets.UTF_8) to end
}
```

Add `tableAlignmentsExtra(): List<TableAlign>?` after verifying the byte values (Step 1's verification); define `internal enum class TableAlign { NONE, LEFT, CENTER, RIGHT }` in the same file mapped to the verified bytes.

- [ ] **Step 4: Run tests → PASS. Step 5: Commit** (`refactor(td-rust-1a): typed extras decoders for PackedAstNode`).

### Task 6: NodeType gap analysis → mapping doc

**Files:**
- Create: `docs/td-rust-1a-nodetype-mapping.md`

- [ ] **Step 1: Enumerate all match sites.**

```bash
grep -n "MarkdownElementTypes\.\|GFMElementTypes\.\|MarkdownTokenTypes\." \
  app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt
```

(~88 hits.) For EACH distinct JetBrains type used, produce a table row: JetBrains type | usage sites (line numbers) | `MdNodeType` variant OR interface-semantic replacement | risk note.

- [ ] **Step 2: Classify the hard cases explicitly.** Token-level types with no pulldown-cmark counterpart (`MarkdownTokenTypes.EOL`, `WHITE_SPACE`, `TEXT`, `CODE_FENCE_CONTENT`, list bullets/numbers, `ATX_HEADER` markers, link destination/text tokens) must each get a written strategy: replaced by typed accessor (`headingLevel`, `linkHref`, `codeLang`...), replaced by `startOffset/endOffset` text slicing, or dead-by-construction in the new model (justify). NO row may say "TBD".

- [ ] **Step 3: Derive the final `MdNodeType` variant list** (union of NodeType wire variants + JetBrains-only block/inline kinds that survive classification). Expect ~30-36 variants. Include the verified table-alignment byte mapping from Task 5.

- [ ] **Step 4: Commit** (`docs(td-rust-1a): NodeType mapping table + gap analysis`). **GATE: a human-readable doc with zero unresolved rows is the entry condition for Stage 3.**

---

## STAGE 3 — Interface + mechanical re-type (behavior zero-change)

### Task 7: `MarkdownTree.kt` — `MdNode` + `MdNodeType`

**Files:**
- Create: `app/src/main/java/app/amber/feature/ui/components/richtext/tree/MarkdownTree.kt`

- [ ] **Step 1: Write the interface** exactly per spec §3 surface, renamed `MdNode`, with `MdNodeType` variants from the Task-6 doc:

```kotlin
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
    val headingLevel: Int?
    val codeLang: String?
    val linkHref: String?
    val linkTitle: String?
    val imageSrc: String?
    val taskChecked: Boolean?
    val listStart: Long?
    val tableAlignments: List<TableAlign>?
}

internal fun MdNode.textIn(source: String): String =
    source.substring(startOffset.coerceIn(0, source.length), endOffset.coerceIn(0, source.length))

internal enum class MdNodeType { /* variants from docs/td-rust-1a-nodetype-mapping.md */ }
```

(`TableAlign` moves here from Task 5 if Task 5 parked it in nativebridge — single definition, nativebridge imports it.)

- [ ] **Step 2: Compile + commit** (`refactor(td-rust-1a): MdNode interface + unified MdNodeType`).

### Task 8: `JvmMdTree` + unit tests

**Files:**
- Create: `app/src/main/java/app/amber/feature/ui/components/richtext/tree/JvmMdTree.kt`
- Create: `app/src/test/java/app/amber/feature/ui/components/richtext/tree/JvmMdTreeTest.kt`

- [ ] **Step 1: Failing tests first.** Parse 5 corpus samples with the JetBrains parser (same call as `Markdown.kt:787` — `parser.buildMarkdownTreeFromString`), wrap in `JvmMdTree`, assert: heading levels (sample 02), link href+title (05), code lang (07), ordered start=7 (14), task checked states (15), table alignments (12), children/sibling traversal order matches JetBrains pre-order.

- [ ] **Step 2: Implement.** `class JvmMdNode(private val ast: ASTNode, private val source: String, override val parent: JvmMdNode?) : MdNode`. Mapping `IElementType → MdNodeType` per the Task-6 doc (exhaustive `when`, unknown → `MdNodeType.Unknown`). Typed accessors implement TODAY'S extraction logic — find each extraction in `Markdown.kt` (link destination digging, ATX level from type, fence lang from `FENCE_LANG` token, etc.) and replicate it here verbatim; cite the source line in a comment (`// relocated from Markdown.kt:NNNN`). Lazy `children` filtering the same token noise the renderer currently skips ONLY if the renderer skips it globally — otherwise keep all children and let Stage 3 move call-site filters into accessor calls (decide per the mapping doc, record decisions in KDoc).

- [ ] **Step 3: Tests PASS. Step 4: Commit** (`refactor(td-rust-1a): JvmMdTree adapter over JetBrains ASTNode`).

### Task 9: Re-type Markdown.kt against `MdNode` (THE big one)

**Files:**
- Modify: `app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt`
- Modify: callers of `extractMarkdownTableData` + `app/src/test/java/app/amber/feature/ui/components/richtext/MarkdownTableTest.kt` if signatures shift

Transformation rules (apply mechanically, in this order):
1. `MarkdownParseResult.astTree: ASTNode` → `tree: MdNode` (build `JvmMdNode(astTree, preprocessed, null)` at the `parsePreprocessedMarkdownUncached` exit — the ONLY place a JetBrains type remains visible).
2. Every helper signature `(node: ASTNode, ...)` → `(node: MdNode, ...)`; every `it.type == MarkdownElementTypes.X` → `it.type == MdNodeType.X'` per the mapping doc.
3. Every child-token extraction (href/level/lang/...) → the corresponding typed accessor.
4. `getTextInNode(...)` → `node.textIn(source)`.
5. Top-level ASTNode extension functions (`containsHtmlBlocks`, `nextSibling`, `findChildOfTypeRecursive`, `trim`...) → MdNode equivalents or interface methods; delete the ASTNode versions ONLY when zero references remain (compiler proves it).
6. `import org.intellij.markdown.*` lines in Markdown.kt must reduce to exactly the ones `parsePreprocessedMarkdownUncached` + `maybeShadowCompareNativeAst` still need.

- [ ] **Step 1: Confirm rig green BEFORE starting** (full `tree.*` filter run).
- [ ] **Step 2: Apply rules 1-6.** Single logical change; commit only when compiling.
- [ ] **Step 3: Run the ENTIRE app unit suite, not just the rig:** `./gradlew :app:testDebugUnitTest`. The snapshot test must be **byte-identical to Stage-1 goldens — DO NOT regenerate goldens in this task.** Any diff = behavior change = bug in JvmMdTree or the mapping; fix there, never by updating the snapshot.
- [ ] **Step 4: Commit** (`refactor(td-rust-1a): renderer consumes MdNode interface (JVM impl only, zero behavior change)`).

### Task 10: Stage-3 review checkpoint

- [ ] Dispatch a code-reviewer subagent over the Stage-3 diff with the explicit question list: any `is ASTNode` leakage? any snapshot regenerated? any logic change smuggled into the mechanical re-type? `markdownAst` flag semantics untouched? Fix findings before Stage 4.

---

## STAGE 4 — Native tree + funnel switch

### Task 11: `PackedAstReader.validate()`

**Files:**
- Modify: `app/src/main/java/app/amber/agent/ui/components/richtext/nativebridge/MarkdownParserNative.kt`
- Create/extend: test beside `PackedAstExtrasTest.kt`

- [ ] **Step 1: Failing tests:** valid golden blob → true; blob truncated at byte n-3 → false; flipped childCount varint (corrupt a copy) → false; empty/short blob → false. Must not throw.
- [ ] **Step 2: Implement** — one bounds-checked `skipNode`-style walk over the whole blob inside `try/catch` returning `Boolean`; share the walking code with `skipNode` (refactor `skipNode` to take a bounds-checking flag or wrap calls in the catch — keep it allocation-free).
- [ ] **Step 3: PASS, commit** (`refactor(td-rust-1a): eager packed-blob bounds validation`).

### Task 12: `NativeMdTree` + unit tests

**Files:**
- Create: `app/src/main/java/app/amber/feature/ui/components/richtext/tree/NativeMdTree.kt`
- Create: `app/src/test/java/app/amber/feature/ui/components/richtext/tree/NativeMdTreeTest.kt`

- [ ] **Step 1: Failing tests** mirroring `JvmMdTreeTest` case-for-case but built from golden blobs (`PackedAstReader(blob).root()!!` → `NativeMdNode`). Same assertions, same samples — this IS the tree-level parity proof.
- [ ] **Step 2: Implement.** `class NativeMdNode(private val packed: PackedAstNode, override val parent: NativeMdNode?) : MdNode` — `NodeType → MdNodeType` exhaustive `when` (per mapping doc; `NodeType.Unknown → MdNodeType.Unknown`), typed accessors delegate to Task-5 extras decoders, lazy children wrapping.
- [ ] **Step 3: PASS, commit** (`refactor(td-rust-1a): NativeMdTree over packed AST`).

### Task 13: Funnel switch + shadow-compare retirement

**Files:**
- Modify: `Markdown.kt` — `parsePreprocessedMarkdownUncached()` (~line 757) and `maybeShadowCompareNativeAst` (~line 822, delete)

- [ ] **Step 1: Implement selection** per spec §4:

```kotlin
private fun parsePreprocessedMarkdownUncached(preprocessed: String): MarkdownParseResult {
    if (markdownAstFlagEnabled()) {   // same NativePathPrefs read the shadow path used
        val blob = MarkdownParserNative.parse(preprocessed)
        if (blob != null) {
            val reader = PackedAstReader(blob)
            if (reader.isValid && reader.validate()) {
                reader.root()?.let { root ->
                    return MarkdownParseResult(
                        preprocessed,
                        NativeMdNode(root, parent = null),
                        reader.hasHtmlBlocks,
                    )
                }
            }
            reportNativePathFailure(stage = "markdownAst", reason = "invalid blob")  // existing MarkdownNativeSwitch pipeline
        }
    }
    val astTree = parser.buildMarkdownTreeFromString(preprocessed)  // unchanged JVM path
    return MarkdownParseResult(preprocessed, JvmMdNode(astTree, preprocessed, null), astTree.containsHtmlBlocks())
}
```

Adapt names to what Stage 3 left in place; route the failure report through the SAME helper `MarkdownNativeSwitch` uses (read it first; reuse, don't duplicate). Delete `maybeShadowCompareNativeAst` and its call; keep the `markdownAst` flag read (it now gates the real path; default stays `false`).

- [ ] **Step 2: Full suite green. Step 3: Commit** (`refactor(td-rust-1a): markdownAst flag now selects NativeMdTree; shadow compare retired`).

### Task 14: Parity rig final form + full verification

**Files:**
- Create: `app/src/test/java/app/amber/feature/ui/components/richtext/tree/MarkdownTreeParityTest.kt`

- [ ] **Step 1: Dual-render parity test** (Parameterized over the 32 samples): render sample via `JvmMdTree` and via `NativeMdTree`-from-golden-blob through `MarkdownBlock`, dump both with `dumpNormalized()`, assert equal. Known-acceptable divergences (if any survive) must be normalized explicitly with a comment citing the mapping-doc row — no blanket fuzzy matching.
- [ ] **Step 2: Run everything:**

```bash
./gradlew :app:testDebugUnitTest && (cd native && cargo test --workspace)
./gradlew :app:compileDebugKotlin :app:assembleDebug
```

- [ ] **Step 3: Commit** (`test(td-rust-1a): dual-tree render parity rig — Phase 3-B complete`).
- [ ] **Step 4: Update `docs/RUST_NATIVE_SPIKE_PLAN.md`** §11 Phase-3 table: mark B as "✅ renderer consumes packed AST behind MdNode interface; flag default still false pending dogfood". Commit (`docs(td-rust-1a): record Phase 3-B completion`).

### Task 15: Final review checkpoint

- [ ] Dispatch code-reviewer subagent over the full Stage-4 diff: lazy-decode safety (can any render-time access escape `validate()`?), flag semantics, fallback completeness (3 layers per spec §5), test honesty (no weakened assertions). Fix findings, commit.

---

## Self-review notes (writing-plans checklist applied)

- Spec coverage: §3 interface→T7/8/12; §4 funnel→T13; §5 errors→T11/13; §6 rig→T1-4,14; §7 stages→task order; §8 rollout boundary→working rules (flag untouched); §9 risks→T6 gates, T9 no-regenerate rule, T3 staleness guard.
- Naming locked: `MdNode`/`MdNodeType`/`JvmMdNode`/`NativeMdNode`/`TableAlign`; `tree:` carrier field; `validate()`; `dumpNormalized()`. Tasks must not rename.
- Known unknowns delegated WITH verification steps: table-align byte values (T5 Step 1), `MarkdownBlock` exact params (T4 preamble), Robolectric config pattern (T4 preamble), Crashlytics helper name (T13 Step 1).
