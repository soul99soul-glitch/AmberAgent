# Novel Phase 5–7 production call-path review

Scope: drawer → routes → VMs → `NovelCreation` → provider/files; send/stop/collect/propose/fork/undo/export/import/background. Read-only; evidence from current sources only (not the earlier e7c32a6f review, which is stale relative to this tree).

## Summary

Phase 5–7 has a **real closed production spine**: drawer entry, Koin singleton graph, `DefaultNovelCreation` + file repo under `filesDir`, workspace send → `start()` → `AndroidNovelModelAdapter` → provider stream, stop/route-exit/background interrupt, collect/propose/fork/undo, package import/export with a bounded SAF read. Lifecycle bridge **is** started from `AmberAgentApp` (not lazy).

Remaining breaks are **UX closed-loop gaps**, not missing DI or stub `start()`: failed runs leave streaming/UI desynced; one “设定建议” button routes to a tab that never lists proposals; degraded projects cannot be restored from UI (`restorePrevious` lives only on the repository); import oversize/open failure is silent; import-replace is a no-op stub; markdown export and several domain intents have no surface. Cap 12 issues below.

## Call-path map (closed|partial|broken|stub)

| Path | Status | Evidence chain |
|---|---|---|
| **Drawer → projects** | **closed** | `ChatDrawer.kt:550-556` → `Screen.NovelProjects` → `RouteActivity.kt:692-693,982` → `NovelProjectsPage` + `koinViewModel()` |
| **Projects list load** | **closed** | `NovelProjectsViewModel.init` collects `projectList` + `refresh()` → `NovelCreation.refreshProjects` → `NovelFileProjectRepository.listProjects` / `scanProjectSummaries` (`NovelProjectRepository.kt:56-64,286-328`); degraded flag set when primary fails and previous loads (`:293-305`) |
| **Create blank / quick start → open workspace** | **closed** | UI dialog → `createBlank`/`createQuickStart` → `NovelIntent.CreateProject` → outcome `ProjectCreated` → `_openProjectId` → `LaunchedEffect` navigates `Screen.NovelWorkspace` (`NovelProjectsViewModel.kt:146-169`, `NovelProjectsPage.kt:111-115`) |
| **Open row → workspace** | **closed** | Row click `NovelProjectsPage.kt:178-179` → `RouteActivity.kt:696-697,985` → `NovelWorkspacePage(projectId)` → `koinViewModel(parametersOf(projectId))` (`NovelModule.kt:64-69`) |
| **DI graph / single owner** | **closed** | `AmberAgentApp.kt:76` loads `novelModule`; singles: `novelAppScope`, `NovelProjectPersisting`, `NovelModelRunning`=`AndroidNovelModelAdapter`, `DefaultNovelCreation` binds `NovelCreation`, `NovelLifecycleBridge`, both VMs (`NovelModule.kt:26-69`). One coordinator + one file root. |
| **Lifecycle bridge start** | **closed** | `AmberAgentApp.kt:79` `get<NovelLifecycleBridge>().start()` → `ProcessLifecycleOwner` observer (`NovelLifecycleBridge.kt:19-23`) |
| **Background interrupt** | **closed** (with list caveat) | `onStop` → for each `projectList` id → `interrupt(Background)` (`NovelLifecycleBridge.kt:25-35`) → cancel model job + `finalizeInterrupt` (`DefaultNovelCreation.kt:161-179,393-409`) |
| **Route-exit interrupt** | **closed** | `NovelWorkspaceViewModel.onCleared` → `interrupt(RouteExit)` (`:307-311`) |
| **Workspace load / tabs / materials** | **closed** (read projections) | `snapshot(NovelQuery.Project)` with `access`/`primaryFailure` (`DefaultNovelCreation.kt:95-98`, VM `:72-84`); Create/Materials tabs + 5 material categories (`NovelWorkspacePage.kt:102-120,293-478`) |
| **Send → generation → provider** | **closed** | UI Send → `send()` builds `NovelRunRequest` → `novelCreation.start` on `appScope` → `reserveRun` commit → `modelRunning.start` → `AndroidNovelModelAdapter` `streamText` (`NovelWorkspaceViewModel.kt:114-176`, `DefaultNovelCreation.kt:136-159,182-229`, `AndroidNovelModelAdapter.kt:75-133`) |
| **Stop (user)** | **closed** | UI “停止” → `stop()` → `interrupt(User)` (`NovelWorkspacePage.kt:274-275`, VM `:179-183`); events `Interrupted` clear generating + `refresh()` (`:160-166`) |
| **Collect candidate** | **closed** | “收录正文” → `CollectCandidate` + paragraph default selection + target suggest → `NovelCollectionReducer` + optional state-delta model call (`NovelWorkspacePage.kt:218-219`, VM `:185-226`, `DefaultNovelCreation.kt:496-554`) |
| **Resolve proposal** | **closed** | Create-tab 确认/忽略 → `ResolveProposal` → `NovelBranchReducer.resolveProposal` (`NovelWorkspacePage.kt:245-250`, VM `:229-239`, `DefaultNovelCreation.kt:557-568`) |
| **Fork** | **partial** | Materials + candidate-row buttons → `ForkBranch` at head checkpoint; outcome selects new branch (`VM:242-261`, `DefaultNovelCreation.kt:571-583`). Name hard-coded `"分支"`; no rename UX. |
| **Undo head** | **closed** | Materials “撤销最近收录” → `UndoHead` (`NovelWorkspacePage.kt:472-473`, VM `:264-273`, `DefaultNovelCreation.kt:586-598`) |
| **Export package (SAF)** | **closed** | Row export → `snapshot(ProjectPackage)` → `CreateDocument` write (`NovelProjectsPage.kt:100-108,183-190`, VM `:122-131`, `DefaultNovelCreation.kt:105-112`) |
| **Import package (SAF)** | **partial** | `OpenDocument` bounded read to `MAX_ENVELOPE_BYTES` (`NovelProjectsPage.kt:77-98`, `NovelSwiftWireContract.MAX_ENVELOPE_BYTES=140MiB`); `ImportPackage` → decode/create (`DefaultNovelCreation.kt:616-640`). Oversize/null stream silent; replace path stub (see issues). |
| **Export markdown** | **stub** | VM `exportMarkdown` + `NovelQuery.BranchMarkdown` exist (`NovelProjectsViewModel.kt:134-144`, `DefaultNovelCreation.kt:99-103`); **no** UI call site under novel pages. |
| **Degraded restore** | **broken** | Banner + list “只读恢复”; `commitProject` rejects non-RW (`NovelProjectRepository.kt:123-125`); `restorePrevious` only on repository (`:178-189`) — **not** on `NovelCreation` / any intent / UI. |
| **Set model policy** | **partial** | Materials “恢复跟随全局” → `SetModelPolicy(Global)` only (`NovelWorkspacePage.kt:453-455`, VM `:276-284`). Fixed policy display-only; no picker. |
| **Set polish / manual edit / polish run** | **stub** | Intents `SetPolishPreference`, `SaveManualEdit`, `NovelRunKindRequest.Polish` exist in domain/API (`NovelCreation.kt:72,101-107,135`); **zero** app UI/VM call sites. |
| **Import replace** | **stub** | `importPackage(replace=…)` always passes `replaceProjectId = null` (`NovelProjectsViewModel.kt:104-109`). Conflict → `ProjectAlreadyExists` throw only. |

## Issues (cap 12)

### 1. bug — Failed generation leaves streaming text and skips document refresh

`NovelWorkspaceViewModel.kt:168-172` handles `NovelRunEvent.Failed` by clearing only `generating`/`busy` and setting `errorMessage`. Unlike `Completed`/`Interrupted`, it does **not** clear `streamingText` or call `refresh()`.

Production path still reserves the run and persists fail via `NovelGenerationReducer.fail` + `commitProject` (`DefaultNovelCreation.kt:235-249`). UI can show stale stream chrome and miss the user/assistant session rows until a manual re-open.

### 2. bug — “查看并确认设定建议” navigates to a tab that does not list proposals

`NovelWorkspacePage.kt:251-253` calls `selectMaterialsCategory(More)`. Materials **More** renders writing requirements, model policy, polish text, branch management (`:436-475`) — **not** `settingProposals`. Actual accept/reject already sits on the Create tab (`:245-250`). Button is a broken/misleading navigation control.

### 3. bug — Degraded projects have no restore closed loop

Workspace shows degraded banner (`NovelWorkspacePage.kt:94-100`); list shows “只读恢复” when `isDegraded` (`NovelProjectsPage.kt:272-274`, set in `scanProjectSummaries` `:293-305`). Writes are blocked at `commitProject` (`NovelProjectRepository.kt:123-125`) and send is gated (`NovelWorkspaceViewModel.kt:117-119`).

`NovelFileProjectRepository.restorePrevious` (`:178-189`) is never exposed through `NovelCreation` / `NovelIntent` / UI. User can open a permanently read-only project with mutative buttons that fail at the repository.

### 4. bug — SAF import oversize / open failure is silent

`NovelProjectsPage.kt:82-98`: read capped at `MAX_ENVELOPE_BYTES + 1`; if oversize or `openInputStream` is null, `bytes` is null and the page **returns without** setting `errorMessage` or toast. Only successful non-null bytes call `importPackage`. User gets no feedback on reject/fail open.

Import bound itself is correct (`NovelSwiftWireContract` + codec decode guard) — the UX loop is broken, not the size constant.

### 5. bug — Import “replace” path is a hard stub (outcome/replace always null)

`NovelProjectsViewModel.kt:104-109`:

```kotlin
val replaceId = if (replace) {
    // decode later via perform; for replace pass existing matching id only when user chose
    null
} else null
```

Both branches are `null`. UI never surfaces a replace conflict chooser either. Re-import of same project id only hits `ProjectAlreadyExists` (`DefaultNovelCreation.kt:623-626`). `replaceProjectId` handling in perform (`:618-621`) is dead from production UI.

### 6. stub — Markdown export not reachable from production UI

`exportMarkdown` + `NovelQuery.BranchMarkdown` + `NovelPackageCodec.exportMarkdown` are implemented (`NovelProjectsViewModel.kt:134-144`, `DefaultNovelCreation.kt:99-103`, `NovelPackageCodec.kt:45-61`). Grep under `app/.../pages/novel` shows **no** call site. Package export is the only export button (`NovelProjectsPage.kt:276-278`).

### 7. suggestion — Mutative workspace actions ignore `access` (only send checks RW)

`send()` refuses when `access != ReadWrite` (`NovelWorkspaceViewModel.kt:117-119`). `collectCandidate` / `resolveProposal` / `forkFromHead` / `undoHead` / `setModelPolicy` do not. They fail later with `DegradedReadOnly` (or busy) via repository/perform. Buttons stay enabled on degraded banner projects → avoidable error path and inconsistent gating.

### 8. suggestion — Dual `Interrupted` emission on cancel

`interrupt` cancels the job **and** launches `finalizeInterrupt`, which `tryEmit(Interrupted)` (`DefaultNovelCreation.kt:172-178,393-409`). The cancelled `runGeneration` catch also emits `Interrupted` when the run id is in `interruptTombstones` (`:230-233`). Collectors can refresh twice; not a dual document owner (single mutex + `terminal` flag), but noisy closed-loop.

### 9. suggestion / partial — Domain intents without surface: polish preference edit, manual chapter edit, polish run, fixed-model select

| Intent / kind | Domain | UI |
|---|---|---|
| `SetPolishPreference` | `NovelCreation.kt:72`, `DefaultNovelCreation.kt:479-494` | Polish shown read-only (`NovelWorkspacePage.kt:457-459`); no editor |
| `SaveManualEdit` | `NovelCreation.kt:101-107`, `DefaultNovelCreation.kt:601-613` | Manuscript is display-only (`:331-342`) |
| `NovelRunKindRequest.Polish` | `NovelCreation.kt:135` | Composer only Discuss / Continuation / WholeChapter; VM kind map has no Polish (`VM:123-130`) |
| `SetModelPolicy(Fixed)` | policy display `:448-452` | Only “恢复跟随全局” (`:453-455`) |

Not broken wiring for existing buttons; incomplete product loop relative to the feature API.

### 10. nit — Dead `exportTarget` state on projects page

`NovelProjectsPage.kt:74` declares `var exportTarget by remember { … }` and never reads/writes it. Export uses `pendingExport` + row `onExport` instead (`:75,183-190`). Dead code only.

### 11. suggestion — Candidate-row Fork always named `"分支"` and duplicates materials fork

`NovelWorkspacePage.kt:221-222` and `:469-470` both call `forkFromHead("分支")`. No name dialog; repeated forks collide on display name. Candidate-row Fork sits next to “收录正文” without explaining checkpoint semantics (materials copy is clearer).

### 12. partial — Collect / resolve / undo discard typed outcomes (rely on throw + refresh)

`collectCandidate` / `resolveProposal` / `undoHead` ignore `NovelOutcome` and only `refresh()` on success (`NovelWorkspaceViewModel.kt:211-225,232-238,268-272`). Errors depend on exceptions from `perform`. Acceptable if reducers always throw on failure, but success-specific UX (e.g. empty undo) cannot be distinguished. Fork is the only mutator that branches on outcome (`:255-257`).

## Residual OK

- **No missing novel DI**: `novelModule` registered; VMs, repository, adapter, bridge, `NovelCreation` all bound once (`NovelModule.kt`, `AmberAgentApp.kt:76-79`).
- **No dual document owners**: single `DefaultNovelCreation` + `writeMutex`; generation jobs on `novelAppScope` are intentional for process-background continuity until `NovelLifecycleBridge.onStop`.
- **Lifecycle bridge is started** (not page-lazy): `AmberAgentApp.kt:79` + `NovelLifecycleBridge.start`.
- **Send is real**, not a chrome stub: `start` → reserve → `AndroidNovelModelAdapter` → provider `streamText` with empty tools/memory by design.
- **SAF import read is bounded** at envelope max + 1 (`NovelProjectsPage.kt:84-93`); not unbounded SAF ingest.
- **Create → navigate** and **package export** loops work end-to-end.
- **isDegraded on list** is computed from primary-fail + previous success (`NovelProjectRepository.kt:293-305`), not hardcoded false.
- **Stop / route-exit / background** all call the same `interrupt` implementation (reasons mapped to `NovelRunInterruptionReason`).

---

*Reviewer: production call-path audit only. No code changes.*
