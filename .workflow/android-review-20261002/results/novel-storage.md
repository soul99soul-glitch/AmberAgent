# Novel storage/import/ledger review

Scope: current HEAD + WIP in `feature/novel-workspace` and `feature/novel`, with production callers traced inside this Android repository. No code changes or Gradle runs. Evidence is static production data flow; suggested red tests below have not yet been executed. Standards and Spec are recorded separately; functional failures are not style findings.

## Confirmed findings

### NS1 — P1 — Installation publishes manifest before the installation is complete

- Location: `feature/novel-workspace/src/main/kotlin/app/amber/feature/novelworkspace/NovelWorkspaceInstaller.kt:34-50`, `:78-86`.
- Trigger: write failure or process death after manifest has been written but before remaining book files, ledger, or checkout. `createBlank` puts manifest first. Legacy conversion returns sorted paths, so manifest is written before project/settings files. Even a deterministic invalid late input path can reproduce the failure without timing.
- Production: project import → repository.install → Installer; local migration uses the source project ID and `NovelWorkspaceMigrationService.migrateAtEpoch` treats `workspaceRepository.exists(id)` as AlreadyMigrated. `exists` only checks manifest existence. A half-installed book becomes visible as a completed project; the migration cannot retry and is skipped permanently.
- Second check: Installer's WIP recovery explicitly assumes an incomplete directory has no manifest, but it writes manifest in input order. The comments and behavior contradict one another. Per-file atomic writes do not make the installation atomic. Sessions failure rolls back only after Installer has successfully returned, so does not cover this.
- Minimal repair: prevalidate all paths before writes; publish manifest last after required tree and ledger/checkout are complete, or stage the entire installation under a temporary directory and atomically publish it. Preserve the refusal to overwrite a completed existing book. Do not add retry machinery.
- Red test: install valid manifest/project/branch plus a later invalid path, assert the failed target is not `exists`, then retry that same project ID successfully. Add an install interrupted before ledger test if using a publish boundary seam.
- Standards: explicit repository durability boundary violated. Spec: Phase 4 reliable backup/migration and product goal of safe persistence.

### NS2 — P2 — Project rename detaches global head from every branch head

- Location: `feature/novel-workspace/src/main/kotlin/app/amber/feature/novelworkspace/NovelWorkspaceProjectRepository.kt:171-180`.
- Trigger: rename a normal workspace project with `heads[mainId] = oldHead`. Rename appends a commit and sets only `ledger.head = renameCommit`; every `heads[...]` remains oldHead.
- Production: `NovelProjectsViewModel.renameProject` invokes this implementation. `NovelWorkspaceRuntime.commitTree` chooses parent from `heads[branchId]`, `canUndo` checks that head, and per-branch history/status use the branch ancestry. Subsequent chapter commits bypass the rename commit. `commitTree` also only advances global head when the branch head previously equaled it, which is now false for every branch; global head remains stuck at rename while branch heads advance.
- Second check: a project-level title edit may reasonably be shared across branches. That does not justify creating a detached global head, which contradicts its mirror invariant and the subsequent advancement predicate. The filesystem title survives, so this is head/history/CAS consistency rather than immediate loss of title.
- Minimal repair: attach the rename commit to the branch whose head global head was mirroring and advance that head as well. Explicitly define fallback for a legacy ledger with no heads; do not reset every branch head to one commit.
- Red test: blank project → rename → assert head equals the corresponding branch head; perform a real Runtime author edit and assert both advance, ancestry includes rename, and Undo leaves valid pointers.
- Standards: `UI gate → domain owner/CAS → durable persistence` invariant. Spec: correctness; rename semantics have no more specific task spec.

### NS3 — P1 — Main branch slug can point to the wrong branch after migration/import

- Location: `feature/novel/src/main/kotlin/app/amber/feature/novel/workspace/NovelLegacyWorkspaceMigrator.kt:32-39`, `:94-105`.
- Trigger: two active branches have names yielding the same slug, and the real main branch appears later in `document.branches`. Example: side branch named `Main` first, main branch named `main` second. `mainSlug` is calculated using an empty set, yielding `main`; later branchSlugs allocates `main` to the side branch and `main-2` to the main branch.
- Production: both `.ambernovel` import and automatic legacy migration call this converter. Manifest points at the side branch; Installer parses/registers it as mainBranchId; activeSlug uses it as default. Initial view/export/default writing uses the wrong manuscript despite preserving both trees.
- Second check: `NovelDocumentValidator.validateSessionsAndBranches` checks branch IDs and names being nonblank, but does not require names or normalized slugs to be unique. The import-only validator also does not impose uniqueness. Case-fold slug collisions are therefore legal source data. This is not an invalid-document-only issue.
- Minimal repair: allocate branchSlugs exactly once before rendering manifest, then use `branchSlugs[project.mainBranchID]` for mainBranch.
- Red test: adapt the existing two-branch fixture, put the non-main colliding name first, assert parsed.mainBranchID and installed.mainBranchId match the source mainBranchID and selected chapter body.
- Standards: deterministic identity/path mapping. Spec: `.ambernovel` import preserves active branches and selected manuscript; phase2-exchange.md.

### NS4 — P1 — Dot-leading source titles become hidden and silently disappear from the visible/exported tree

- Location: `feature/novel/src/main/kotlin/app/amber/feature/novel/workspace/NovelLegacyWorkspaceMigrator.kt:73-74`, `:97-105`, analogous discarded/inbox/override slug sites. Shared source: `NovelWorkspaceSlug.kt:14-39`; acceptance mismatch: `NovelWorkspacePaths.kt:69-76`; exclusion: `NovelWorkspaceStore.kt:120-124`.
- Trigger: valid source material title `.规则` yields `setting/world/.规则.md`; valid branch name `.番外` yields `branches/.番外/...`. Slug preserves dot. Paths.validate only rejects a hidden *first* segment, so both paths pass and get written. collect skips dot-leading children at every level.
- Production: migration and `.ambernovel` import report success, but the material or entire branch is missing from catalog, fileTree, checkout and ZIP export. Source content exists on disk, which limits the damage, but a subsequent normal exchange loses it entirely. A `..` branch name instead produces a path that fails after other writes and interacts with NS1.
- Second check: ZIP reader already rejects hidden segments and createBranch explicitly rejects dot-leading names. Host `.amber` files are intentionally hidden, but author-supplied source names are book content and must not become hidden storage. This is not a request to export `.amber`.
- Minimal repair: local migration path allocation must choose a visible fallback leaf for dot-leading slugs while retaining the original display title. `reservedPath` is an appropriate shared allocation boundary if carefully checked against consumers; preserve existing non-dot golden names. Paths.validate can reject hidden segments to enforce the same tree boundary as collectors, but **only after adapting path producers**; otherwise it converts silent omission into whole-import rejection for legitimate source names. Do not change arbitrary existing valid path names.
- Red tests: import material `.规则`, branch `.番外`, discarded `.废稿` and an inbox/override title; assert visible body retention and export presence. Reject direct writes into `setting/.secret` while allowing normal private stores through their own API. Golden existing ordinary names must remain unchanged.
- Standards: common path/canonical tree boundary mismatch. Spec: phase2-exchange promises preservation of supported settings, discarded chapters and active branches.

### NS5 — P1 — ZIP import has unbounded decompression allocations

- Location: `feature/novel-workspace/src/main/kotlin/app/amber/feature/novelworkspace/NovelWorkspaceExchange.kt:48-61`.
- Trigger: a small valid ZIP contains a highly compressible huge `.md` entry, or many entries whose decompressed sum exceeds memory. `zip.readBytes()` allocates the entire entry, then UTF-8 decode and string conversion allocate additional buffers, while previous strings are retained in `files`. Compressed input length does not bound the inflated tree.
- Production: NovelProjectsViewModel → NovelWorkspaceProjectImport ZIP branch calls this reader; unlike JSON package decoding it has no `MAX_PROJECT_BYTES` budget at all. With the known device 512 MB heap, a few megabytes of compressed repeated text can force hundreds of MB to GB of allocations. OOM is an Error and bypasses the caller's `catch (Exception)`.
- Second check: `.ambernovel` already has a 100 MB project payload ceiling, and the ZIP reader has no outer decompressed guard. Duplicate-entry and UTF-8 validation do not bound memory. This is a concrete file-import crash primitive, not justification for generic retries or fallback parsers.
- Minimal repair: count bytes while streaming entries and reject before allocation exceeds an aggregate decompressed 100 MB budget aligned with the existing project contract. Enforce actual bytes read rather than trusting ZipEntry.size. Avoid parallel decompression or extra buffering abstractions.
- Red tests: compressed >100 MB entry and aggregate of individually small entries both reject predictably; unknown size/data-descriptor ZIP also enforces cap. Normal ZIP roundtrip remains intact.
- Standards: correctness/security, no style violation. Spec: safe import and existing project package resource limit; ZIP-specific numeric limit is an implementation choice.

## Needs main-agent verification before acceptance

### NS6 — Potential P1 — Import/migration resets an explicitly needsSync branch to fresh

- Evidence: migrator writes `syncStatus` to branch.md at line 114. Installer gives all files one INITIAL commit at lines 54-82. Ledger.lastBranchChanges (`NovelWorkspaceLedger.kt:218-225`) finds chapter and plot changes at the same ancestry position and therefore `isPlotStale == false`. Repository-wide `syncStatus` searches show no workspace consumer of the copied field. A source branch `needsSync` with an existing older plot snapshot becomes fresh after import.
- Production: both ZIP and .ambernovel can contain branch.md `syncStatus: needsSync`; source legacy validator allows NeedsSync. Native runtime's hard gate checks derived freshness rather than this field.
- Why not automatically accepted: exchange documentation explicitly excludes source runtime state, and the exact meaning of legacy needsSync may include an intentionally non-migrated legacy reducer transaction. Main agent should distinguish a real manuscript-versus-plot lag from a private runtime compatibility signal. **Do not fix by blindly reading permanent `needsSync` on every turn**, which would lock the project forever after a legitimate plot synchronization.
- If accepted: preserve the initial freshness requirement as a local one-time baseline that real plot synchronization can clear, or derive suitable synthetic initial chapter/plot ancestry. Test actual source stale manuscript against runtime write/ghostwrite gate and later unlock after sync. No need to restore checkpoints or active runs.

### NS7 — Potential P2 — Multiline YAML scalars render but do not parse back

- Evidence: `NovelWorkspaceMarkdown.yamlScalar:224-233` deliberately emits raw newline inside quotes (existing golden test at MarkdownTest:119-120 confirms by-design encoding). `parseFile:35-58` parses every newline independently, so `title = "第一行\n第二行"` becomes a scalar with only `"第一行`; subsequent lines can become accidental new fields. `withFields` replaces only the first scalar line, leaving continuation lines.
- Production scope requiring confirmation: converter renders arbitrary project/material/branch titles and project.polishPreference. `polishPreference` has no current Android prompt consumer, so a broken old preference alone must not be elevated to a current user-facing bug. Imported multiline material/branch title affects visible catalog and lookup, but confirm the product permits such titles before accepting.
- Minimal fix if accepted: preserve existing raw-newline wire rendering and teach the parser/field updater to consume quoted scalar continuations; do not change golden serialization to escaped newline solely to simplify parsing.

## Explicitly rejected / by design

- Symlink traversal in Store.resolve: normalized rather than canonical paths would follow a symlink, but current public ZIP importer writes regular files and cannot introduce a symlink. App-private trees have no demonstrated producer of attacker-controlled symlinks. No finding without a concrete reachability chain.
- Branch creation does not copy unresolved.json: public unresolved guidance explicitly includes fork as a resolution path. Do not flag solely from absence of a copied private gate. Plot freshness during fork may warrant a specific product test, but is not conflated with this private unresolved record.
- Full source activeRuns/checkpoints/history/unknown JSON roundtrip loss: excluded by phase2-exchange specification. Stream decoder intentionally ignores runtime histories. No issue.
- ZIP attachments omitted: explicit v1 `.md/.yaml` exchange boundary. No issue.
- Imported drafts/inbox not adopted as local runtime candidates: explicitly preserved as public file tree only. No issue.
- Chapter history does not reconstruct absent old blobs: explicitly limited to available retained text; no invented versions.
- Old files below `.amber/checkout` are not agent tree: deliberately hidden host mirror.
- Ledger/sessions parse failures quarantine the original rather than propagate: existing deliberate policy, with source retained. Potential failed quarantine rename is an IO-error-only concern, not elevated without a concrete failure scenario.

## Suggested repair grouping

1. Import durability and resource boundary: NS1 + NS5; red tests then installer/import suite.
2. Identity/persistence integrity: NS2 + NS3 + NS4; branch/repository/migrator tests, then real Runtime head/undo test.
3. Only if confirmed by main agent: NS6/NS7. Reuse production gates and wire rules; avoid inventing source runtime restoration.

Each phase needs an independent subagent review of behavior, tests, and regression scope. Main agent owns Gradle validation.
