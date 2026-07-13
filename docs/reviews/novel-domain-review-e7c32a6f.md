## Summary

Phase 0–1 create/rename/delete/model-policy/polish-pref paths largely close the durable loop through `NovelReducer` → `NovelFileProjectRepository` (stage/fsync/atomic move + revision CAS). Several call-chain and authority problems remain: UI **Send is a pure no-op** (never reaches `start()`), `SwitchBranch` returns a durable-looking **`BranchRenamed` outcome without mutating or persisting**, list/scan **drops corrupt primaries instead of exposing `isDegraded` recovery**, delete is **not two-file atomic**, and claimed **operation-id idempotent replay cannot commit** because the coordinator always forces a `+1` revision write. Generation/collection/fork remain intentionally incomplete; the risk is where incomplete work still looks successful or breaks recovery authority.

## Closed-loop map

| Action | Call chain | Durable? | Status |
|---|---|---|---|
| **Create blank / quick start** | `NovelProjectsPage` → `NovelProjectsViewModel.create*` → `NovelIntent.CreateProject` → `NovelReducer.createProject` → `repository.createProject` → `_projectList` refresh | Yes (primary JSON + index best-effort) | **closed** (quick-start only stores seed; no materials yet — by design) |
| **Rename (list)** | Dialog → `VM.rename` → `RenameProject` → reducer → `commitProject(expectedRevision)` → list refresh | Yes | **closed** |
| **Rename (workspace)** | `VM.renameProject` → same + `refresh()` snapshot | Yes | **closed** |
| **Delete** | Dialog → `VM.delete` → `DeleteProject` → `deleteProject` (primary + previous) → list refresh | Partial (two separate deletes; crash can resurrect from `.previous.json`) | **partial** |
| **Open workspace** | Nav `NovelWorkspace(projectId)` → `snapshot(Project)` → `loadProject` | Read path yes; **drops `NovelProjectLoadAccess`** | **partial** (degraded authority invisible to UI) |
| **Switch branch** | Chip → `VM.selectBranch` → `SwitchBranch` in-memory map + **fake `BranchRenamed`** | No durable branch rename; process-memory only; VM also keeps local id | **broken** (wrong outcome; failure ignored in UI) |
| **Set model policy** | Materials “More” → restore Global only → `SetModelPolicy` → reducer → commit → refresh | Yes when used | **partial** (closed for Global restore; no Fixed picker UI) |
| **Set polish preference** | Intent + reducer exist; **no UI** | Would be durable if called | **partial** / unwired |
| **Materials browse** | Tab projections from loaded `document` (manuscript via `workingChapterSelections`; candidates not mixed into formal body) | Read-only | **closed** (read); edit **stub** (`reviseMaterial` reducer not on `NovelIntent`) |
| **Send / generation** | Composer **Send** `onClick` only reassigns `draft`; never `start()` | No | **broken** (silent no-op). Domain `start()` itself correctly fails with `generation_unavailable` if called |
| **Interrupt / route exit** | `onCleared` → `interrupt` | No-op body | **stub** (honest for Phase 2; not fake success) |
| **RetryTerminal** | Intent throws hard error | N/A | **stub** (explicit fail — OK) |

## Issues

### Issue 1 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelWorkspacePage.kt:221-225`
- Description: The enabled **发送** button does not call `NovelCreation.start`, `perform`, or any domain API. User intent to write/discuss is a silent UI no-op. Domain `start()` already emits an honest `Failed(generation_unavailable)` path (`DefaultNovelCreation.kt:91-102`), but the UI never reaches it, so the product presents a working composer with no feedback.
- Suggestion: Wire Send to `novelCreation.start(...)` (or a VM method that does) and surface `NovelRunEvent.Failed` in `errorMessage` until Phase 2 generation is real. Do not leave an enabled primary action without a domain call.
- Status: open

### Issue 2 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:69-81`
- Description: `NovelIntent.SwitchBranch` does not rename a branch and does not append any applied operation, but returns `NovelOutcome.BranchRenamed(projectID, branchID, revision = doc.project.revision)`. That outcome type is a **durable ledger outcome** used for real renames elsewhere (`NovelOutcome.kt:100-104`). Callers or future logging/UI that pattern-match on `BranchRenamed` will treat a soft UI selection as a successful rename at the current revision — a protocol lie and a recovery/authority hazard if outcomes are ever persisted or shown as history.
- Suggestion: Introduce a non-durable result (e.g. `Unit`, a dedicated `BranchSelected`, or fold selection into snapshot only). Never reuse `BranchRenamed` for process-memory selection. Document that active branch is not V1 durable state (comment at L41 is correct; the return type contradicts it).
- Status: open

### Issue 3 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelWorkspaceViewModel.kt:102-108`
- Description: `selectBranch` uses `runCatching { perform(SwitchBranch) }` and **always** applies `selectedBranchId = branchId` afterward, even when domain throws `BranchNotFound`. UI can show a selected branch that is not valid for the document / domain map.
- Suggestion: Only update local selection on success; on failure set `errorMessage` and keep previous branch.
- Status: open

### Issue 4 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/persistence/NovelProjectRepository.kt:283-303`
- Description: `scanProjectSummaries` only reads primary `*.json` and on any failure returns `null` (drops the project). `NovelProjectSummary.isDegraded` is never set `true`. Meanwhile `loadProject` can recover via `.previous.json` (`L73-86`). Result: a project with corrupt primary + good previous **disappears from the list** even though opening by known id could work — closed-loop break for list → open → recover, and the `isDegraded` field is dead for discovery.
- Suggestion: On primary failure, try previous; if previous validates, emit summary with `isDegraded = true` and `loadError`. Optionally still list unreadable ids for support.
- Status: open

### Issue 5 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:55-58`
- Description: `snapshot(NovelQuery.Project)` returns only `loaded.document` and discards `NovelLoadedProject.access` / `primaryFailure`. Workspace UI therefore cannot distinguish ReadWrite vs DegradedPrevious; mutations (`commitProject`) correctly throw `DegradedReadOnly` (`NovelProjectRepository.kt:123-125`) but the user never sees degraded/read-only authority on load. Recovery path exists in repo (`restorePrevious`) but is not exposed on `NovelCreation`.
- Suggestion: Extend `NovelSnapshot.Project` (or parallel field) with access mode + primaryFailure; surface banner in workspace; expose restore intent when degraded.
- Status: open

### Issue 6 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/persistence/NovelProjectRepository.kt:162-175`
- Description: `deleteProject` deletes primary then previous as two independent `File.delete()` calls with no single atomic swap. Crash/kill after primary delete leaves `.previous.json`; subsequent `loadProject` / list scan can **resurrect** the project from previous — delete is not durable/idempotent under failure.
- Suggestion: Stage a tombstone or delete previous first after verifying expectedRevision, or move both into a trash dir via atomic directory rename; document crash semantics and add a test for mid-delete recovery.
- Status: open

### Issue 7 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:135-149`
- Description: Reducer idempotent replay is implemented (`NovelReducer.replayIfPresent` returns prior outcome and **unchanged** document — `NovelReducer.kt:446-457`, tested in `NovelReducerRepositoryTest.idempotentRename_replaysOutcome`). The coordinator always follows with `repository.commitProject(reduced.document, expectedRevision = loaded.revision)`, and commit requires `document.revision == expectedRevision + 1` (`NovelProjectRepository.kt:132-134`). On true replay, revision does not advance → **commit throws**, so end-to-end idempotent apply is broken despite domain claims. Today UI always generates fresh `operationID`s (masks the bug) but any client/retry that reuses op ids will fail after first success.
- Suggestion: After reduce, if `reduced.document.project.revision == loaded.revision` (replay/no-op), skip commit and return outcome. Only commit when revision advanced.
- Status: open

### Issue 8 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelProjectsViewModel.kt:42-52`
- Description: `refresh()` sets `loading = true` and only clears loading via `projectList` collection (`L35-37`). `MutableStateFlow` does not re-emit equal list values. After a successful `refreshProjects()` that returns the same summaries, collectors do not run → **`loading` can stick true** (common when revisiting the list with singleton `DefaultNovelCreation` already holding the list).
- Suggestion: Clear `loading = false` in `refresh` success path (and onFailure), independent of StateFlow equality; or use a SharedFlow/version counter for list ticks.
- Status: open

### Issue 9 -- Severity: suggestion
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:41-42`
- Description: Process-memory `selectedBranch` is written on create/switch but **never consulted** by `snapshot` or the workspace VM. Workspace keeps its own `selectedBranchId` and defaults to `mainBranchID` on load (`NovelWorkspaceViewModel.kt:71-73`). Two selection authorities will diverge once generation uses domain `selectedBranchId()` (`DefaultNovelCreation.kt:110-114`) while UI uses local state — wrong branch runs later.
- Suggestion: Single source of truth: either selection only in VM and pass `branchId` on every run intent, or expose selected branch from `NovelCreation.snapshot` and drive UI from that.
- Status: open

### Issue 10 -- Severity: suggestion
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/persistence/NovelProjectRepository.kt:191-207`
- Description: Public `loadProject` rethrows `UnsupportedSchema` before falling back to previous (`L77`). `loadProjectUnlocked` (used by `commitProject`/`delete` paths) does **not**, so higher-schema primary may surface as `DegradedPrevious` + read-only rather than schema rejection — inconsistent authority/error taxonomy for the same bytes.
- Suggestion: Align unlocked load with public load: rethrow `UnsupportedSchema` (and other non-recoverable errors) before previous fallback.
- Status: open

### Issue 11 -- Severity: suggestion
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/domain/NovelDocumentValidator.kt:50-80`
- Description: `validateTransition` enforces project metadata, single revision step, and prefix-append for appliedOperations/checkpoints/events/chapterVersions, but **not** materialRevisions, candidates, sessions, or “candidates must not appear in workingChapterSelections / formal manuscript.” Phase 1 create/rename is fine; once collection/generation land, incomplete transition rules are exactly where pending vs formal authority can corrupt.
- Suggestion: Before Phase 3/4 reducers ship, extend transition checks for append-only material revisions, session message monotonicity, and candidate isolation vs chapter selections.
- Status: open

### Issue 12 -- Severity: suggestion
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/NovelCreation.kt:46-82`
- Description: Public `NovelIntent` surface omits `ReviseMaterial` even though `NovelReducer.reviseMaterial` is implemented and tested. Materials UI is read-only projection only. Not a fake success today, but the deep-module boundary encourages UI to invent ad-hoc writes or call the reducer/repository directly, bypassing mutex/CAS.
- Suggestion: Either expose `ReviseMaterial` (and delete) through `perform` with the same commit pattern as rename, or document explicitly that material mutation is not part of the public Phase 1 API.
- Status: open

### Issue 13 -- Severity: nit
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:69-71`
- Description: Comment acknowledges soft selection is not a rename (“return a no-op rename-like outcome is wrong”) then still returns `BranchRenamed`. Comment and code disagree — increases risk of copy-paste into ledger writes later.
- Suggestion: Fix code (Issue 2) and delete the contradictory comment.
- Status: open

## Residual non-issues (intentionally incomplete — not filed above)

- `start()` / `interrupt()` / `RetryTerminal` Phase 2 stubs that **fail or no-op honestly** when reached.
- Quick-start without material bootstrap; polish preference without UI; Fixed model policy without picker.
- SAF import/export, fork/undo/polish, collection pending — out of scope for this checkpoint per project state.
- Repository stage/fsync/move + revision CAS for successful rename/policy paths look sound and are covered by unit tests for happy path.
