# Novel final call-path review (post-P0)

Scope: re-check the six P0 closed-loop fixes against current sources only. Read-only; evidence from the live tree under `app/` + `feature/novel/`. Cap 8 issues.

## Summary

All six audited P0 paths are **closed** in production code:

| Check | Status |
|---|---|
| Failed clears `streamingText` + `refresh()` | **pass** |
| Proposal jump uses typed kind categories | **pass** |
| SAF oversize reports error | **pass** |
| `RestorePrevious` wired from degraded banner | **pass** |
| Drawer → `NovelCreation` → files/provider continuous | **pass** |
| `LifecycleBridge` started from `AmberAgentApp` | **pass** |

No remaining **P0** on these six paths. Residual work is **P1/partial**: import replace stub, null-stream silent, non-send mutators not RW-gated, markdown export unsurfaced, dual `Interrupted` emission, domain intents without UI.

## Call-path map

| Path | Status | Evidence chain |
|---|---|---|
| **Failed run → clear stream + refresh** | **closed** | `NovelRunEvent.Failed` → `generating=false`, `busy=false`, `errorMessage=event.message`, `streamingText=""`, then `refresh()` (`NovelWorkspaceViewModel.kt:168-176`). Domain still persists fail: `DefaultNovelCreation.kt:247-275` → `NovelGenerationReducer.fail` + `commitProject` + emit Failed. Symmetric with `Completed`/`Interrupted` (`:154-166`). |
| **Proposal jump typed categories** | **closed** | Create-tab “查看并确认设定建议” maps `QuickStart.suggestedKind`: `World`→`World`, `Character`→`Characters`, `MasterOutline`→`Plot`, else `More` (`NovelWorkspacePage.kt:255-269`). `selectMaterialsCategory` also forces Materials tab (`NovelWorkspaceViewModel.kt:95-97`). Origins stamped with typed kinds at complete: `NovelGenerationReducer.quickStartProposals` (`:408-422`) — World/Character/MasterOutline/WritingRequirements. |
| **SAF oversize → error** | **closed** | Bounded read `MAX_ENVELOPE_BYTES+1` (`NovelProjectsPage.kt:84-96`); oversize → `ByteArray(0)` (`:92-93`); empty → `viewModel.reportError("Import package is too large or unreadable")` (`:101`, `NovelProjectsViewModel.kt:100-102`). Constant `140 * 1024 * 1024` (`NovelSwiftWireContract.kt:20`); codec re-guards (`NovelPackageCodec.kt:16-17`). |
| **Degraded banner → RestorePrevious** | **closed** | Banner when `access == DegradedPrevious` (`NovelWorkspacePage.kt:94-104`) → `viewModel::restorePrevious` → `NovelIntent.RestorePrevious` (`NovelWorkspaceViewModel.kt:290-298`) → `DefaultNovelCreation.restorePrevious` (`:127,480-486`) → `NovelFileProjectRepository.restorePrevious` copies previous→primary, returns `ReadWrite` (`:178-189`). `perform` refreshes `projectList` (`:141-143`). Workspace `refresh()` reloads access. |
| **Drawer → NovelCreation → files + provider** | **closed** | Drawer “小说创作” → `Screen.NovelProjects` (`ChatDrawer.kt:550-556`) → route entry (`RouteActivity.kt:692-693,982`) → `NovelProjectsPage` / VM → `NovelCreation` singleton (`NovelModule.kt:45-51,60-69`). Files root: `NovelFileProjectRepository.defaultRoot(filesDir)` → `filesDir/amberagent/novel-creation` (`NovelModule.kt:31-35`, `NovelProjectRepository.kt:358-359`). Generation: workspace `send()` → `novelCreation.start` → `AndroidNovelModelAdapter` → `providerManager` + `streamText` (`NovelWorkspaceViewModel.kt:114-145`, `DefaultNovelCreation.kt:148-169,209`, `AndroidNovelModelAdapter.kt:75-118`). |
| **LifecycleBridge from AmberAgentApp** | **closed** | `novelModule` in Koin modules (`AmberAgentApp.kt:77`); after `startKoin`, `get<NovelLifecycleBridge>().start()` (`:79`). Bridge idempotent `ProcessLifecycleOwner` observer (`NovelLifecycleBridge.kt:19-23`); `onStop` interrupts all `projectList` ids with `Background` (`:25-35`). DI note: “started from AmberAgentApp” (`NovelModule.kt:24`). |
| **Projects list / create / open workspace** | **closed** | List via `projectList` + `refreshProjects` (`NovelProjectsViewModel.kt:38-55`); create blank/QS → `ProjectCreated` → navigate workspace (`:150-173`, `NovelProjectsPage.kt:117-121`); row open (`:184-186`, `RouteActivity.kt:696-697,985`). |
| **Stop / route-exit / background interrupt** | **closed** | User stop (`NovelWorkspacePage.kt:291-292` → VM `:182-186`); route exit `onCleared` → `RouteExit` (`:321-325`); background via bridge (above). |
| **Collect / resolve / fork / undo** | **closed** (gating partial) | Buttons → intents → reducers via `perform` (VM collect/resolve/fork/undo). Only `send` pre-checks `ReadWrite` (see issues). |
| **Package export SAF** | **closed** | Row export → `snapshot(ProjectPackage)` → `CreateDocument` write (`NovelProjectsPage.kt:106-114,189-196`). |
| **Package import SAF** | **partial** | Oversize reported; null stream silent; replace always null (see issues). |
| **Export markdown** | **stub** | VM + query implemented; no novel-page UI call site. |
| **Set model Fixed / polish / manual edit / polish run** | **stub** | Domain present; no production UI for Fixed pick / polish edit / manual chapter / Polish kind. |

## Remaining P0/P1

### P0

**None** on the six checked paths. Prior phase5–7 P0s (failed stream sticky, proposal jump to More-only, silent oversize, no restore intent/UI, lifecycle not started) are fixed in current sources.

### P1

1. **Import replace is a hard stub** — UI always `replaceProjectId = null` (`NovelProjectsViewModel.kt:108-113`); domain replace branch exists (`DefaultNovelCreation.kt:658-671`) but unreachable.
2. **SAF null stream still silent** — `bytes == null` no-ops (`NovelProjectsPage.kt:100`); open failure ≠ oversize path.
3. **Non-send mutators ignore `access`** — only `send` gates RW (`NovelWorkspaceViewModel.kt:117-119`); collect/resolve/fork/undo/setModelPolicy fail later via `DegradedReadOnly` / repo.
4. **Markdown export unsurfaced** — `exportMarkdown` + `BranchMarkdown` exist (`NovelProjectsViewModel.kt:138-148`, `DefaultNovelCreation.kt:99-103`); zero UI.
5. **Dual `Interrupted` emission** — `finalizeInterrupt` emits (`DefaultNovelCreation.kt:426`) and cancelled `runGeneration` may also emit (`:227-230,247-250`).
6. **Domain intents without surface** — `SetPolishPreference`, `SaveManualEdit`, `NovelRunKindRequest.Polish`, `SetModelPolicy(Fixed)` have no editor/picker/run entry from workspace UI.

## Issues

Cap 8. Severity: bug | partial | stub | suggestion | nit.

### 1. partial — SAF import: null `openInputStream` remains silent

`NovelProjectsPage.kt:99-102`:

```kotlin
when {
    bytes == null -> { /* cancelled or unreadable — leave existing error path */ }
    bytes.isEmpty() -> viewModel.reportError("Import package is too large or unreadable")
    else -> viewModel.importPackage(bytes)
}
```

Oversize is reported. Cancelled picker and unreadable URI both yield `null` with no `reportError`/toast. User cannot distinguish “did nothing” from “failed open”.

### 2. stub — Import replace always null from production UI

`NovelProjectsViewModel.kt:108-113`: both branches of `if (replace)` assign `null`. No conflict chooser. Re-import of same id hits `ProjectAlreadyExists` only (`DefaultNovelCreation.kt:664+`). Domain `replaceProjectId` delete+import path is dead from UI.

### 3. suggestion — Mutative workspace actions not RW-gated (only send)

`send()` refuses non-`ReadWrite` (`NovelWorkspaceViewModel.kt:117-119`). `collectCandidate` / `resolveProposal` / `forkFromHead` / `undoHead` / `setModelPolicy` stay enabled under degraded banner until repository/`perform` throws `DegradedReadOnly` (`NovelProjectRepository.kt:123-125`, `DefaultNovelCreation.kt:285-286`). After restore P0, residual UX inconsistency while still degraded.

### 4. suggestion — “查看并确认设定建议” jumps category but does not confirm

Typed category mapping is correct (`NovelWorkspacePage.kt:255-269`). Accept/reject remain separate Create-tab buttons (`:249-254`). Materials categories render committed materials / outline, not pending `settingProposals`. Label “并确认” overpromises; user must return to Create for 确认/忽略. Not a wrong-category bug (P0 fixed); residual copy/flow gap.

### 5. stub — Markdown export not reachable

`exportMarkdown` + `NovelQuery.BranchMarkdown` + `NovelPackageCodec.exportMarkdown` implemented. Grep of novel pages: only package export button (`NovelProjectsPage.kt:282-284`). No markdown SAF/share path.

### 6. suggestion — Dual `Interrupted` on cancel

`interrupt` cancels job + launches `finalizeInterrupt` which `tryEmit(Interrupted)` (`DefaultNovelCreation.kt:172-189,411-426`). Cancelled `runGeneration` also emits `Interrupted` when id is in `interruptTombstones` (`:227-230`). VM `refresh()` can run twice; single mutex + `terminal` flag prevent dual commit, not dual UI event.

### 7. stub — Feature API without production surface (polish / manual edit / Fixed model / Polish run)

| Surface missing | Domain present |
|---|---|
| Polish preference editor | `NovelIntent.SetPolishPreference` (`NovelCreation.kt` / `DefaultNovelCreation.kt:505+`); Materials shows read-only text (`NovelWorkspacePage.kt:474-477`) |
| Manual chapter edit | `SaveManualEdit`; Manuscript display-only (`:348-358`) |
| Polish generation | `NovelRunKind.Polish` / reducer paths; composer kinds only Discuss/Continuation/WholeChapter (`VM:123-130`) |
| Fixed model picker | Display Fixed (`:465-468`); only “恢复跟随全局” mutates (`:470-472`) |

### 8. nit — Dead `exportTarget` + hard-coded fork name `"分支"`

- `NovelProjectsPage.kt:74`: `exportTarget` remembered, never read; export uses `pendingExport`.
- Fork buttons pass literal `"分支"` (`NovelWorkspacePage.kt:225,486`); no name dialog; repeated forks collide on display name.

## Residual OK (evidence)

- **Failed path parity** with Completed/Interrupted for stream clear + refresh (`NovelWorkspaceViewModel.kt:154-176`).
- **QuickStart proposal kinds** are four typed materials (`NovelGenerationReducer.kt:408-411`); UI maps three explicit + WritingRequirements→More (where writing requirements render, `:453-462`).
- **Restore is full stack**: UI → intent → creation → file copy primary ← previous → RW access (`NovelWorkspacePage.kt:101-102` … `NovelProjectRepository.kt:178-189`).
- **Single coordinator / single file root**: one `DefaultNovelCreation` + one `NovelFileProjectRepository` (`NovelModule.kt:31-51`); `writeMutex` around mutations.
- **Lifecycle not page-lazy**: explicit `AmberAgentApp` start (`:79`), not first-open of novel pages.
- **Provider path real**: `AndroidNovelModelAdapter` uses settings + `ProviderManager`; tools/memory intentionally empty (`AndroidNovelModelAdapter.kt:27-29,95-97`).
- **List degraded flag** from primary-fail + previous success (`NovelProjectRepository.kt:293-305`); list row “只读恢复” (`NovelProjectsPage.kt:278-280`).
