# Novel pre-APK production call-path review

Scope: read-only production path audit for Amber Android novel creation  
Trace: Drawer → `RouteActivity` Novel\* → VMs → `NovelCreation` → provider / files / SAF  
Plus: `novelModule` DI + `NovelLifecycleBridge` start; Workspace send/stop/collect/polish/adopt/sync/export markdown; Projects create/open/import/export package  

Evidence from live tree under `app/` + `feature/novel/` only. **No code fixes.** Cap 12 issues.

---

## Summary

The novel production spine is **mostly closed**: drawer entry, Navigation3 routes, Koin singleton graph, `DefaultNovelCreation` + file repo under `filesDir`, lifecycle background interrupt from `AmberAgentApp`, and the core workspace/project loops (generate, collect, polish, adopt, sync, package I/O, markdown export) all reach real domain + provider/file code.

Remaining risk before APK is concentrated in **orphan runs**, **silent UI failures**, and a few **dead/stub intents** (import replace, materials revise, recovery rehydrate). Not a missing-DI or stub-`start()` problem.

| Area | Verdict |
|---|---|
| Entry + DI + lifecycle | **closed** |
| Projects create/open/import/export package | **closed** (import replace **stub**) |
| Workspace send/stop/collect | **closed** |
| Polish / adopt / sync / manual edit | **closed** domain+UI (adopt drift demotion **partial**) |
| Export markdown | **closed** (workspace SAF) |
| Dual owners of document | **OK** (single `NovelCreation` + single file repo) |
| Silent / broken UX | **several P0/P1** |

---

## Call-path map (closed|partial|broken|stub)

| Path | Status | Evidence chain |
|---|---|---|
| **Drawer → NovelProjects** | **closed** | `ChatDrawer.kt:550-556` → `Screen.NovelProjects` → `RouteActivity.kt:692-693,982` → `NovelProjectsPage` + `koinViewModel()` |
| **Open project → Workspace** | **closed** | Row / create emit → `Screen.NovelWorkspace(projectId)` (`NovelProjectsPage.kt:117-121,184-186`) → `RouteActivity.kt:696-697,985` → `NovelWorkspacePage` + `parametersOf(projectId)` (`NovelModule.kt:71-76`) |
| **DI graph / single owner** | **closed** | `AmberAgentApp.kt:76` loads `novelModule`; singles: `novelAppScope`, `NovelProjectPersisting`=`NovelFileProjectRepository`, `NovelModelRunning`=`AndroidNovelModelAdapter`, `DefaultNovelCreation` binds `NovelCreation`, `NovelLifecycleBridge`, both VMs (`NovelModule.kt:27-76`). One coordinator + one files root `filesDir/…/novel-creation`. |
| **LifecycleBridge start** | **closed** | After Koin: `get<NovelLifecycleBridge>().start()` (`AmberAgentApp.kt:79`); idempotent `ProcessLifecycleOwner` observer (`NovelLifecycleBridge.kt:19-23`); `onStop` interrupts all `projectList` ids with `Background` (`:25-35`) |
| **Projects list / refresh** | **closed** | `projectList` collect + `refreshProjects` → repo `listProjects` (`NovelProjectsViewModel.kt:38-55`, `DefaultNovelCreation.kt:100-102`) |
| **Create blank / quick start** | **closed** | Dialog → `CreateProject` → `ProjectCreated` → navigate workspace (`NovelProjectsViewModel.kt:155-178`, `NovelProjectsPage.kt:212-220`) |
| **Rename / delete project** | **closed** | UI dialogs → `RenameProject` / `DeleteProject` (`NovelProjectsPage.kt:223-249`, VM `:74-98`) |
| **Import package (SAF)** | **partial** | `OpenDocument` bounded read (`NovelProjectsPage.kt:77-104`) → `ImportPackage` decode/create (`DefaultNovelCreation.kt:939-963`). Oversize reported; null stream silent; **replace always null** (see P1) |
| **Export package (SAF)** | **partial** | Row export → `snapshot(ProjectPackage)` → `CreateDocument` write (`NovelProjectsPage.kt:106-114,189-196`, VM `:131-141`). Write `openOutputStream` failure silent |
| **Workspace load + degraded restore** | **closed** | `snapshot(Project)` + access/primaryFailure (`NovelWorkspaceViewModel.kt:68-91`); banner → `RestorePrevious` (`NovelWorkspacePage.kt:103-113`, VM `:290-298`, `DefaultNovelCreation.kt:535-540`) |
| **Send → provider stream** | **closed** | Send → `novelCreation.start` → `reserveRun` + `AndroidNovelModelAdapter.streamText` (`NovelWorkspaceViewModel.kt:114-179`, `DefaultNovelCreation.kt:170-196,219-254`, `AndroidNovelModelAdapter.kt:75-118`) |
| **Stop (user)** | **closed** | “停止” → `interrupt(User)` (`NovelWorkspacePage.kt:309-310`, VM `:182-186`) → cancel model + `finalizeInterrupt` (`DefaultNovelCreation.kt:198-216,458-482`) |
| **Route-exit interrupt** | **closed** | `onCleared` → `interrupt(RouteExit)` (`NovelWorkspaceViewModel.kt:472-476`) |
| **Collect candidate** | **closed** | “收录正文” → full-paragraph default + target suggest → `CollectCandidate` (+ optional state-delta outside lock) (`NovelWorkspacePage.kt:230-233`, VM `:188-229`, `DefaultNovelCreation.kt:577-649`) |
| **Resolve proposal** | **closed** | 确认/忽略 → `ResolveProposal` (`NovelWorkspacePage.kt:267-272`, VM `:232-242`) |
| **Polish chapter** | **partial** | Materials “整章润色” → `start(kind=Polish)` (`NovelWorkspacePage.kt:387-389`, VM `:327-372`). **Cancels UI job without `interrupt`** (P0) |
| **Adopt polish** | **partial** | 采用润色 / 保存为剧情改写 → `AdoptPolishCandidate` (`NovelWorkspacePage.kt:236-241`, VM `:375-386`). Safe path silently demotes to rewrite on drift fail (`DefaultNovelCreation.kt:828-836`) |
| **Sync manual edits** | **closed** | NeedsSync banner “同步状态” → `SyncManualEdits` (`NovelWorkspacePage.kt:368-373`, VM `:315-324`, `DefaultNovelCreation.kt:696-759`) |
| **Manual edit save** | **closed** | “编辑本章” / “保存修改” → `SaveManualEdit` (`NovelWorkspacePage.kt:392-414`, VM `:301-312`) |
| **Export markdown (workspace)** | **partial** | Materials More → `BranchMarkdown` → SAF `CreateDocument` (`NovelWorkspacePage.kt:570-592`, VM `:440-449`). Write failures silent; errors not shown on Materials tab |
| **Fork / undo / set main / rename branch** | **partial** | Buttons wired (`NovelWorkspacePage.kt:243-245,612-617,600-609`, VM fork/undo/setMain/rename). Rename is `name + "+"` only; fork name hard-coded `"分支"` |
| **Set model / polish preference** | **partial** | Global + raw UUID Fixed fields + polish pref save (`NovelWorkspacePage.kt:522-568`, VM `:279-287,389-397`). No model picker |
| **Revise material** | **stub** | `ReviseMaterial` intent + VM method (`NovelCreation.kt:134-140`, `NovelWorkspaceViewModel.kt:422-437`); **no UI** call site |
| **Import replace** | **stub** | Domain `replaceProjectId` delete+import (`DefaultNovelCreation.kt:941-944`); production UI always `null` (`NovelProjectsViewModel.kt:104-113`) |
| **RetryTerminal** | **stub** | Explicitly throws “not needed” (`DefaultNovelCreation.kt:155-156`) |
| **Recovery sidecar** | **partial** | Flushed during stream + deleted on complete (`DefaultNovelCreation.kt:241-248,273,898-921`); `listForProject` never consumed by production reopen path |

---

## P0 issues

### 1. bug — `polishChapter` cancels UI collector without interrupting the live run (orphan `Running`)

`polishChapter` / second polish does `generateJob?.cancel()` then `novelCreation.start(...)` without `interrupt()`:

```327:343:app/src/main/java/app/amber/feature/ui/pages/novel/NovelWorkspaceViewModel.kt
    fun polishChapter(versionId: app.amber.feature.novel.model.NovelChapterVersionId) {
        ...
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            ...
            val run = novelCreation.start(
```

`start()` registers a live run on `appScope` independent of the VM job (`DefaultNovelCreation.kt:170-195`). Cancelling only the collector leaves `branch.activeRunID` set until the orphan finishes; the next `reserveRun` hits `ProjectBusy` (`NovelGenerationReducer.kt:94-96`). Materials “整章润色” is **not** gated on `state.generating` (`NovelWorkspacePage.kt:387-389`), so polish-during-send (switch tab) is reachable.

**Effect:** sticky busy project, failed polish/send with error that Materials may not show (issue 2).

### 2. bug — Materials tab never renders `errorMessage` (silent failures)

Workspace errors are written to `state.errorMessage` for polish/sync/save/export/model/branch/adopt failures, but UI only paints the banner on the **Create** tab footer:

```318:323:app/src/main/java/app/amber/feature/ui/pages/novel/NovelWorkspacePage.kt
        state.errorMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
```

`NovelMaterialsTab` (`:329-622`) has **zero** `errorMessage` reads. Grep confirms only Create-path display (`NovelWorkspacePage.kt:98,318`). User actions on 正文/更多 can fail with no visible feedback.

### 3. bug — Terminal event may never reach UI if complete/fail skips emit after collector already cancelled

`run.events` is a never-completing `MutableSharedFlow` (`DefaultNovelCreation.kt:172-176`). Completion only emits when revision advances:

```260:278:feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt
            var didComplete = false
            writeMutex.withLock {
                ...
                if (next.project.revision != before) {
                    repository.commitProject(...)
                    live?.terminal = true
                    didComplete = true
                }
            }
            if (didComplete) {
                events.tryEmit(NovelRunEvent.Completed(...))
            }
```

Combined with P0-1 (collector cancelled while run still live): document may commit Completed/Failed with **no active collector**; UI stays on last `generating` snapshot until leave/refresh. Symmetric gap when `didComplete`/`didFail` is false (no terminal event at all) after interrupt race returns unchanged revision (`NovelGenerationReducer.complete` early `document to null` at `:184-192`).

---

## P1 issues

### 4. stub — Import replace is unreachable from production UI

UI always imports with default `replaceProjectId = null` (`NovelProjectsViewModel.kt:104-113`). Conflict surfaces only as “项目已存在…删除后重导” (`:116-121`). Domain replace delete+import (`DefaultNovelCreation.kt:941-944`) is dead from the button path. No conflict chooser / re-import overwrite control.

### 5. partial — SAF import: null `openInputStream` still silent

```99:103:app/src/main/java/app/amber/feature/ui/pages/novel/NovelProjectsPage.kt
            when {
                bytes == null -> { /* cancelled or unreadable — leave existing error path */ }
                bytes.isEmpty() -> viewModel.reportError("Import package is too large or unreadable")
                else -> viewModel.importPackage(bytes)
```

Oversize → empty → reported. Unreadable URI / null stream → no-op. Cancelled picker and hard failure look identical.

### 6. partial — SAF export writes ignore null `OutputStream` / IO errors

Package export (`NovelProjectsPage.kt:112-114`) and markdown export (`NovelWorkspacePage.kt:581-585`) both:

```kotlin
context.contentResolver.openOutputStream(uri)?.use { ... }
```

Null stream or write exception → silent success from the user’s POV (picker closed, no error).

### 7. partial — “采用润色” silently becomes rewrite + `NeedsSync` on drift failure

```828:836:feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt
            val reduced = if (intent.asRewrite || !compatible) {
                NovelPolishReducer.saveAsRewrite(...)
            } else {
                NovelPolishReducer.adoptSafe(...)
            }
```

Drift check fails closed (`compatible = false` on exception, `:821-822`). UI button “采用润色” passes `asRewrite = false` (`NovelWorkspacePage.kt:236-237`) but may apply ManualEdit + `NeedsSync` (`NovelPolishReducer.kt:72-112`) with **no** distinct toast/outcome messaging. User may think safe adopt succeeded.

### 8. partial — Non-send mutators ignore `access == ReadWrite`

Only `send()` pre-checks degraded access (`NovelWorkspaceViewModel.kt:117-119`). collect / resolve / fork / undo / polish / sync / save / setModel still invoke `perform`/`start` until `DegradedReadOnly` at repo/commit (`NovelProjectRepository.kt:123-125`, `DefaultNovelCreation.kt:322-323`). Restore banner exists (`NovelWorkspacePage.kt:103-113`) but other buttons stay live.

### 9. stub — `reviseMaterial` has VM API, no surface

Characters / World / Plot materials are **read-only** projections (`NovelWorkspacePage.kt:420-510`). `NovelWorkspaceViewModel.reviseMaterial` (`:422-437`) and `NovelIntent.ReviseMaterial` never called from Compose.

### 10. partial — Recovery sidecar is write-only (no reopen rehydrate)

`maybeFlushRecovery` + `delete` on complete (`DefaultNovelCreation.kt:898-921,273`). `NovelRecoveryStore.listForProject` (`NovelRecoveryStore.kt:56-68`) has **no** production caller (grep: store write/delete only). Crash mid-stream leaves sidecar files that nothing loads into the session on next open.

### 11. partial — Dual terminal emissions / SharedFlow hang after success

- `interrupt` → `finalizeInterrupt` emits `Interrupted` (`DefaultNovelCreation.kt:480-481`); cancelled `runGeneration` may also emit (`:255-257`).
- After `Completed`/`Failed`/`Interrupted`, VM `collect` never ends (SharedFlow), so `generateJob` stays alive until next cancel — couples with P0-1 when polish cancels that job.

### 12. nit / partial — Dead UI state + crude branch rename / fork name

- `exportTarget` remembered but never read (`NovelProjectsPage.kt:74`); export uses `pendingExport` only.
- `NovelProjectsViewModel.exportMarkdown` (`:143-153`) has **no** page call site (workspace owns markdown export).
- Fork always named `"分支"` (`NovelWorkspacePage.kt:243-244,612-613`); rename is `branch.name + "+"` (`:607-609`) — no dialog.

---

## Residual OK

- **Drawer → routes → VMs → NovelCreation → files/provider** continuous; not page-lazy DI.
- **Single document owner**: one `DefaultNovelCreation` + one `NovelFileProjectRepository` + `writeMutex` (`NovelModule.kt:32-58`, `DefaultNovelCreation.kt:77`). Lifecycle / stop / route-exit are multi-*entry* interrupt sources, not dual state owners.
- **Provider path real**: settings + `ProviderManager` + `streamText`; tools/memory intentionally empty (`AndroidNovelModelAdapter.kt:27-29,95-97`).
- **Failed / Completed / Interrupted** all clear `streamingText` and `refresh()` on Create-path collector (`NovelWorkspaceViewModel.kt:154-176,352-368`) — prior sticky-stream P0 is fixed for the active collector.
- **Degraded restore closed loop**: banner → intent → `restorePrevious` file copy → RW (`NovelWorkspacePage.kt:103-113` … `NovelProjectRepository` restore path).
- **Package import size bound** + oversize error (`NovelSwiftWireContract.kt:20`, `NovelProjectsPage.kt:84-101`).
- **Collect / sync / polish / adopt / manual edit / markdown export** domain reducers are wired from production buttons (not API-only), modulo issues above.
- **Background interrupt** started from `AmberAgentApp`, not first novel page open (`AmberAgentApp.kt:79`).

---

## Issue budget

| # | Sev | Title |
|---|---|---|
| 1 | P0 bug | Polish cancels collector without interrupt → orphan run |
| 2 | P0 bug | Materials tab silent `errorMessage` |
| 3 | P0 bug | Terminal emit / collector cancel desync |
| 4 | P1 stub | Import replace unreachable |
| 5 | P1 partial | SAF import null stream silent |
| 6 | P1 partial | SAF export write silent |
| 7 | P1 partial | Safe adopt silently → rewrite |
| 8 | P1 partial | Mutators not RW-gated |
| 9 | P1 stub | reviseMaterial no UI |
| 10 | P1 partial | Recovery never rehydrated |
| 11 | P1 partial | Dual terminal / SharedFlow hang |
| 12 | P1 nit | Dead exportTarget + crude fork/rename |

**Cap 12 reached.** Pre-APK recommendation: fix P0-1/2 before shipping polish+materials as production-ready; P1s are residual UX / secondary closed-loop gaps, not missing kernel wiring.
