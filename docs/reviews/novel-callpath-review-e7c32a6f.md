## Summary

Drawer → `Screen.NovelProjects` → list CRUD → `Screen.NovelWorkspace(projectId)` → workspace load/tabs/materials is largely wired: `:feature:novel` is included, `novelModule` is loaded once, and a single `DefaultNovelCreation`/`NovelFileProjectRepository` graph roots under `filesDir/amberagent/novel-creation`. Production call-path breaks are at the UX closed-loop layer: empty-list loading can stick forever after refresh, create does not open the new project, composer Send is a silent no-op (never calls `start()`), route-exit `interrupt` is a deliberate empty body, and branch soft-select is written to the coordinator but never read back on workspace open. Generation/SAF/ProcessLifecycle bridge remain incomplete as documented; stubs should not be mistaken for finished product paths.

## Call-path map

### 1. Drawer entry — **closed**

| Step | Evidence |
|---|---|
| Chat drawer (phone + tablet) | `ChatPage.kt:346` / `:391` both use single `ChatDrawerContent` |
| V3 nav row | `ChatDrawer.kt:550-556` — label `小说创作`, `navController.navigate(Screen.NovelProjects)` |
| Route type | `RouteActivity.kt:982` `data object NovelProjects` |
| Entry composable | `RouteActivity.kt:692-693` `entry<Screen.NovelProjects> { NovelProjectsPage() }` |

No second legacy drawer content path exists; both big-screen permanent drawer and modal drawer share the same `ChatDrawerContent` / `V3DrawerHeader`. Unlike “新聊天” (`ChatDrawer.kt:529-531`), novel navigate does not close the drawer — same pattern as 今日看板 / 小应用.

### 2. Projects list load / create / rename / delete — **partial**

| Step | Evidence | Status |
|---|---|---|
| DI VM | `NovelModule.kt:44-46` `viewModel { NovelProjectsViewModel(get()) }` | closed |
| Page collect | `NovelProjectsPage.kt:53-56` `koinViewModel()` + `collectAsStateWithLifecycle` | closed |
| Load | `NovelProjectsViewModel.kt:31-39` collect `projectList` + `refresh()` → `novelCreation.refreshProjects()` → `DefaultNovelCreation.kt:44-46` → `repository.listProjects()` → `NovelFileProjectRepository.kt:56-64` scan `projects/*.json` under `defaultRoot(filesDir)` (`:334-335`) | partial (empty-list loading stuck; see Issue 1) |
| Create blank / quick start | UI `NovelProjectsPage.kt:126-137` → VM `createBlank`/`createQuickStart` (`NovelProjectsViewModel.kt:55-62,88-108`) → `NovelIntent.CreateProject` → `DefaultNovelCreation.createProject` (`:116-132`) → `NovelReducer.createProject` + `repository.createProject` | partial: list refreshes via `perform` end `listProjects()` (`DefaultNovelCreation.kt:87-88`); **no navigate** to workspace; **outcome discarded** |
| Rename | `NovelProjectsPage.kt:140-149` → `rename` → `NovelIntent.RenameProject` → reducer + `commitProject` | closed for list |
| Delete | `NovelProjectsPage.kt:152-166` → `delete` → `NovelIntent.DeleteProject` → `repository.deleteProject` | closed for list |
| Error surface | `state.errorMessage` rendered `NovelProjectsPage.kt:117-123` | partial (overlaid; `busy` never shown) |

### 3. Navigate to workspace — **closed** (open-from-list only)

| Step | Evidence |
|---|---|
| Row click | `NovelProjectsPage.kt:107-109` `navController.navigate(Screen.NovelWorkspace(project.id.rawValue))` |
| Route | `RouteActivity.kt:985` `data class NovelWorkspace(val projectId: String)` |
| Entry | `RouteActivity.kt:696-697` `NovelWorkspacePage(projectId = key.projectId)` |
| VM params | `NovelWorkspacePage.kt:51` `koinViewModel(parameters = { parametersOf(projectId) })` |
| DI | `NovelModule.kt:48-53` `viewModel { parameters -> NovelWorkspaceViewModel(parameters.get(), get()) }` |

`projectId` is route-key string (uppercase UUID rawValue), not `SavedStateHandle`; Navigation3 `rememberViewModelStoreNavEntryDecorator()` (`RouteActivity.kt:430`) scopes the VM to the entry. Create-then-navigate is **missing** (Issue 2).

### 4. Workspace load / tabs / materials — **partial**

| Step | Evidence | Status |
|---|---|---|
| Load | `NovelWorkspaceViewModel.refresh` `:64-88` → `snapshot(NovelQuery.Project)` → `repository.loadProject` | closed for happy path |
| Tabs | Create / 资料 `NovelWorkspacePage.kt:95-113` | closed UI shell |
| Branch chips | `:128-147` → `selectBranch` → `NovelIntent.SwitchBranch` | partial (soft-select; dual state; see Issues 5–6) |
| Mode chips | 讨论 / 续写 / 整章 `:156-171` | partial; 整章 never selected (Issue 8) |
| Materials 5 categories | `:254-418` read-only projections from document | closed as read-only shell |
| Degraded access | List UI can show “只读恢复” (`NovelProjectsPage.kt:194-196`) but `scanProjectSummaries` hardcodes `isDegraded = false` (`NovelProjectRepository.kt:297`) | broken badge path (Issue 9) |
| Workspace access mode | `snapshot` returns only `document`, drops `NovelLoadedProject.access` (`DefaultNovelCreation.kt:55-58`) | degraded not surfaced in workspace |

### 5. Composer send — **stub / broken closed-loop**

| Step | Evidence |
|---|---|
| Send button | `NovelWorkspacePage.kt:221-228` `onClick = { draft = draft // keep }`, enabled when draft non-blank |
| Generation API | `NovelCreation.start` / `DefaultNovelCreation.start` (`:91-103`) returns `NovelRunEvent.Failed(code = "generation_unavailable")` |
| UI call | **No** call site to `novelCreation.start(...)` under app novel pages |

Looks like a finished composer chrome; user gets zero feedback. Documented incomplete (`docs/NOVEL_ANDROID_PROJECT_STATE.md:43-47,63`).

### 6. Route dispose interrupt — **partial (call present, body lie)**

| Step | Evidence |
|---|---|
| VM cleared on nav pop | ViewModelStore entry decorator + `onCleared` |
| Interrupt registration | `NovelWorkspaceViewModel.kt:153-161` `novelCreation.interrupt(RouteExit)` |
| Implementation | `DefaultNovelCreation.interrupt` `:105-108` empty body comment “No-op until generation lifecycle exists” |
| App-level lifecycle bridge | `NovelModule.kt:22-23` says bridge started from `AmberAgentApp` “once generation exists”; `AmberAgentApp` loads `novelModule` only (`:76`) — **no** ProcessLifecycleOwner novel interrupt |

Not a fake “started” bridge; honest no-op. Do not treat as production cancel path.

### 7. DI singleton graph — **closed**

| Binding | Evidence |
|---|---|
| Module load | `AmberAgentApp.kt:33,76` `novelModule` in `startKoin` modules list |
| Scope | `NovelModule.kt:26-28` `single(named("novelAppScope"))` IO SupervisorJob |
| Repo | `single<NovelProjectPersisting>` → `NovelFileProjectRepository(defaultRoot(context.filesDir))` `:30-35` → `filesDir/amberagent/novel-creation` |
| Coordinator | `single { DefaultNovelCreation(...) } binds arrayOf(NovelCreation::class)` `:37-42` — one instance for both VMs |
| Gradle | `settings.gradle.kts:97` `include(":feature:novel")`; `app/build.gradle.kts:762` `implementation(project(":feature:novel"))` |

No multi-binding of `NovelCreation`. `appScope` is injected but unused in `DefaultNovelCreation` (harmless until Phase 2).

---

## Issues

### Issue 1 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelProjectsViewModel.kt:42-52` (and `:31-39`, `:75-81` empty UI)
- Description: `refresh()` sets `loading = true` and only clears loading on `projectList` collection or failure. `MutableStateFlow` does not re-emit when `listProjects()` returns a list equal to the current value. First launch with zero projects: collect applies initial `emptyList` with `loading=false`, then `refresh()` sets `loading=true`, then `refreshProjects()` writes the same empty list → no emission → UI stays on `state.loading && state.projects.isEmpty()` (“加载中…”) forever (`NovelProjectsPage.kt:75-81`).
- Suggestion: Always clear `loading` in `refresh()` success path (`onSuccess` / `finally`), independent of StateFlow equality; optional `update { }` with distinct list only for content.
- Status: open

### Issue 2 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelProjectsViewModel.kt:88-108`
- Description: Create succeeds (`NovelOutcome.ProjectCreated` with `projectID` from reducer) but the VM discards the outcome and never navigates. Page closes the dialog only (`NovelProjectsPage.kt:129-136`). Product closed-loop “create → open workspace” is broken; user must manually find and tap the new row (which may also be hidden behind Issue 1 if list refresh equality quirks intervene after non-empty).
- Suggestion: On success, map `ProjectCreated.projectID.rawValue` to `navController.navigate(Screen.NovelWorkspace(...))` (callback from VM or one-shot UI event).
- Status: open

### Issue 3 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelWorkspacePage.kt:221-228`
- Description: Composer Send is a no-op (`draft = draft`). It never calls `NovelCreation.start`, never surfaces `generation_unavailable`, and never clears draft. Enabled button with production-looking chrome is a dead UI path. Related API exists and intentionally fails (`DefaultNovelCreation.kt:91-103`) but is unreachable from UI.
- Suggestion: Wire Send → VM → `start(NovelRunRequest(...))` and collect `Failed` into `errorMessage` / toaster even before real generation; or disable Send with explicit “生成未接入” until Phase 2.
- Status: open

### Issue 4 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:105-108`
- Description: Route exit path calls `interrupt` from `NovelWorkspaceViewModel.onCleared` (`:153-161`) with `NovelInterruptReason.RouteExit`, but the coordinator body is empty. Comments on VM claim “route exit → interrupt” as the cancel design; for current checkpoint this is a no-op (docs agree). Risk is treating this as a finished lifecycle guarantee when generation is later enabled without filling interrupt + ProcessLifecycle bridge (`NovelModule.kt:22-23` deferred, not started in `AmberAgentApp`).
- Suggestion: Keep explicit TODO/throw or structured log until Phase 2; when generation lands, implement cancel tombstone + app `ProcessLifecycleOwner` bridge before advertising closed route-exit cancel.
- Status: open

### Issue 5 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelWorkspaceViewModel.kt:102-108`
- Description: `selectBranch` always copies `selectedBranchId = branchId` after `runCatching { perform(SwitchBranch) }` without checking success. If `BranchNotFound` or load fails, UI still selects the branch and shows its session/materials. Errors are swallowed (no `errorMessage`).
- Suggestion: Only update local selection in `onSuccess`; surface `onFailure` into `errorMessage`.
- Status: open

### Issue 6 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:41-42,69-81,110-114` + `NovelWorkspaceViewModel.kt:71-78`
- Description: Coordinator keeps process-wide `selectedBranch` and exposes `selectedBranchId(...)`, but workspace load never reads it — it uses only local `_state.selectedBranchId` or `mainBranchID`. Re-opening a project after back navigation resets to main branch while coordinator map still holds the prior soft-select. Dual sources of truth; half-wired soft-select (also returns durable-looking `NovelOutcome.BranchRenamed` without renaming — `DefaultNovelCreation.kt:77-81`, noted in `docs/NOVEL_ANDROID_PROJECT_STATE.md:64`).
- Suggestion: On workspace `refresh`, seed selection from `DefaultNovelCreation.selectedBranchId` (promote to interface) or drop coordinator map and keep selection only in VM/SavedState.
- Status: open

### Issue 7 -- Severity: bug
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/persistence/NovelProjectRepository.kt:283-302`
- Description: `listProjects` / `scanProjectSummaries` only scans primary `*.json` files and always sets `isDegraded = false`. Projects that only load via `.previous.json` (degraded path in `loadProject` `:73-87`) never appear as degraded in the list; corrupted primary with good previous may be **omitted** entirely if primary decode fails (`mapNotNull` catch → null), while open-by-id could still recover via previous. List badge “只读恢复” (`NovelProjectsPage.kt:194-196`) is effectively dead.
- Suggestion: Mirror `loadProject` summary strategy: try primary, else previous with `isDegraded = true`; include load errors in `loadError`.
- Status: open

### Issue 8 -- Severity: suggestion
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelWorkspacePage.kt:166-171`
- Description: “整章” chip is hard-coded `selected = false` and only sets `WriteProse` with no granularity field on UI state (`NovelWorkspaceUiState` has `composerMode` only; `NovelRunRequest.granularity` exists in `NovelCreation.kt:85-100` but unused). Users cannot tell whole-chapter mode is active; chip looks broken.
- Suggestion: Track `granularity` in UI state or hide “整章” until Phase 2 wiring.
- Status: open

### Issue 9 -- Severity: suggestion
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelWorkspaceViewModel.kt:51-55` + `NovelModule.kt:48-52`
- Description: `projectId` is constructor-injected via `parametersOf` only — not `SavedStateHandle`. Survives config change only while the Navigation3 entry ViewModelStore retains the VM (`RouteActivity.kt:430`). Process death restore depends entirely on serializable `Screen.NovelWorkspace(projectId)` back stack; if that restores, a new VM reparses id (`NovelProjectId.parse`) — OK. Risk is lower than classic Fragment args, but inconsistent with comment “Project-scoped workspace owner” and harder to test wrong-id reuse if Koin ever reuses a VM key without the route key.
- Suggestion: Prefer `SavedStateHandle["projectId"]` (or route-args API) as single source so process death + parameters stay aligned.
- Status: open

### Issue 10 -- Severity: suggestion
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelProjectsViewModel.kt:18-22,64-72` + `NovelProjectsPage.kt`
- Description: `busy` is set during create/rename/delete but never read by the page. Double-tap FAB/create confirm can enqueue concurrent creates (serialized by coordinator mutex, but double success possible). No disable on confirm buttons while busy.
- Suggestion: Collect `busy` to disable dialog confirm / FAB; or ignore duplicate in-flight intents.
- Status: open

### Issue 11 -- Severity: suggestion
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelProjectsPage.kt:117-123`
- Description: `errorMessage` is drawn as a `Text` sibling after the `when` content inside Scaffold, not as a snackbar/banner tied to lifecycle. Easy to miss under a full list; no dismiss control (unlike chat errors pattern).
- Suggestion: Use `LocalToaster` or a dismissible banner; clear error on next successful action already partially done.
- Status: open

### Issue 12 -- Severity: nit
- File: `/Users/arquiel/Downloads/AI/amberagent/app/src/main/java/app/amber/feature/ui/pages/novel/NovelProjectsViewModel.kt:31-38`
- Description: `projectList.stateIn(...).collect` double-shares an already hot `StateFlow`; unnecessary and obscures the loading equality issue.
- Suggestion: `novelCreation.projectList.collect { ... }` directly.
- Status: open

### Issue 13 -- Severity: nit
- File: `/Users/arquiel/Downloads/AI/amberagent/feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:33-36,20`
- Description: `appScope` constructor param is unused (no launched generation/recovery jobs). Fine for Phase 1; confirm Phase 2 uses the named `novelAppScope` single and does not spawn a second process-wide scope.
- Suggestion: Use this scope for generation jobs when implemented; avoid a second AppScope for novel.
- Status: open

### Issue 14 -- Severity: suggestion
- File: `/Users/arquiel/Downloads/AI/amberagent/docs/NOVEL_ANDROID_PROJECT_STATE.md:33-37` vs UI Send path
- Description: Project state doc correctly lists generation/SAF/lifecycle as not done, but the workspace shell presents Send / mode chips / materials as interactive without inline “Phase 2” blockers except a code comment. Risk of QA marking composer “works” when it only no-ops.
- Suggestion: Surface in-UI unavailable state matching `generation_unavailable` so shell is not mistaken for complete product.
- Status: open

---

## Verdict

**Call-path continuity for navigation + DI + persistence is mostly closed.** Breakages that matter for production UX are: empty list infinite loading, create without open, dead Send, swallow-on-branch-switch, list degraded badge never true, and interrupt/lifecycle still no-op (expected incomplete, but must not be treated as done). No Koin multi-instance of `NovelCreation`; drawer entry is single-implementation dual-host (modal + permanent). Generation/SAF remain stubs by design — Send must not look finished.
