# Novel pre-APK domain review (logic closed-loop + data authority)

Scope: read-only review of durable authority and closed loops in:

- `feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt`
- `feature/novel/.../domain/*Reducer*.kt`
- `feature/novel/.../persistence/*`
- `feature/novel/.../runtime/*` (only where coordinator binds model await / recovery)

Focus checks: generation lock/terminal/recovery; collect supersede/`baseHeadRevision`; manual `needsSync` → checkpoint; polish adopt vs rewrite + drift fail-closed; package export busy / import.

No code changes. Cap 12 issues. Line numbers from current tree.

## Summary

Generation and collect durable cores are largely closed: provider awaits sit **outside** `writeMutex`; reserve/complete/fail/interrupt commit under the mutex; complete/fail emit only when **this** call advanced revision (`didComplete` / `didFail`); collect reloads under lock and the reducer enforces branch match, `baseHeadRevision == head`, `Collected` + sibling `Superseded`. Manual edit → `NeedsSync` → manual sync checkpoint → `Synchronized` is a coherent ledger loop. Package/markdown export is busy-gated under the snapshot mutex.

**Do not claim APK-ready closed loops** for polish drift fail-closed, polish-vs-live-run authority, import-replace safety, or recovery sidecar lifecycle. Those still break data authority or the stated fail-closed contract. Generation durable terminalization is mostly single-winner; **event** terminalization for interrupt is still multi-emitter.

## Closed-loop map

| Path | Coordinator | Reducer / codec / store | Durable authority | Verdict |
|---|---|---|---|---|
| **Gen start** | `start` registers `LiveRun` then launches; `runGeneration` → mutex `reserveRun` → `begin` + `commitProject(+1)` | `NovelGenerationReducer.begin` sets `activeRunID`, `baseHeadRevision`, receipts | Project rev CAS; one active run per branch | **Closed** |
| **Gen stream** | `modelRunning.start` **outside** mutex; deltas → `live.partial` + optional recovery flush | N/A (memory + sidecar) | Partials not manuscript authority | **Closed** for lock hygiene |
| **Gen complete** | mutex: tombstone/`terminal` recheck → `complete` → commit **iff** rev advanced → `didComplete` → emit `Completed` | `complete`: no-op if not `Running` / branch pointer mismatch; candidate gets run’s `baseHeadRevision` | Single durable winner vs interrupt under mutex | **Closed** durable; event gated on `didComplete` |
| **Gen fail** | same CAS pattern with `didFail` + tombstone under lock | `fail` → terminal run + error message | Same | **Closed** durable |
| **Gen interrupt** | tombstone + cancel + async `finalizeInterrupt` under mutex | `interrupt` if still `Running` | Durable interrupt single-winner under mutex | **Partial** — events can double-emit |
| **Recovery sidecar** | `maybeFlushRecovery` on delta; `delete` only on successful complete | `NovelRecoveryStore.write/delete/list`; **no apply/rehydrate** path on load | Orphan on fail/interrupt; never restored into session | **Open** |
| **Collect** | preflight mutex → optional state-delta await **outside** → reload + collect + commit under mutex | `baseHeadRevision`/`branchID`; chosen `Collected`; other same-branch `Available` → `Superseded`; head+1 checkpoint | Stale candidate cannot land | **Closed** (delta best-effort) |
| **Manual edit** | `saveManualEdit` under perform mutex | working selection only; `NeedsSync`; `activeRunID` busy | No head advance without sync | **Closed** |
| **Manual sync** | same await-outside pattern as collect | requires `NeedsSync`; `ManualSync` checkpoint; head+1; `Synchronized` | Working selections become head | **Closed** (delta best-effort) |
| **Polish safe adopt** | optional drift await outside lock → mutex adopt | same `factCompatibilityID`; Polish checkpoint; head+1; `Synchronized` | Intended for compatible polish | **Partial** — drift fail-open on blank; no run-busy gate |
| **Polish rewrite** | `asRewrite` or `!compatible` | `ManualEdit` version; `NeedsSync`; **no** head checkpoint | Forces later manual sync | **Partial** — auto path on failed drift |
| **Export MD / package** | `snapshot` under mutex + `assertNotBusy` | MD codec also busy-checks | Reject while `Running`/pending | **Closed** |
| **Import** | `decode` then optional delete+create | Running→Interrupted + clear `activeRunID` | Normalize stuck runs | **Partial** — replace path unsafe |

## P0 issues (must fix before APK claim)

### 1. bug — Polish drift is fail-**open** on blank model output
- File: `feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:772-822`
- Description: Safe adopt initializes `compatible = true`. Drift is applied only when `text.isNotBlank()`; empty/whitespace model output **leaves `compatible == true`**. Only the `catch` sets `compatible = false` (“fail closed”). A no-output or stripped-empty drift check therefore takes `NovelPolishReducer.adoptSafe`, reusing `factCompatibilityID` and advancing a Polish checkpoint without a successful drift verdict.
- Authority break: manuscript facts marked compatible without evidence.
- Fix direction: default `compatible = false` when `!asRewrite`; require successful decode; blank/invalid → rewrite reject or explicit fail, never silent safe adopt.

### 2. bug — Polish adopt mutates head with no `activeRun` / project-busy gate
- File: `DefaultNovelCreation.kt:825-848` (no `assertNotBusy`); `domain/NovelPolishReducer.kt:150-189` (`validatePolishCandidate` never checks `branch.activeRunID`)
- Contrast: `NovelManualEditReducer.saveEdit:36`, `NovelCollectionReducer.collect:74`, `NovelManualSyncReducer.sync:52`, `NovelGenerationReducer.begin:94` all busy-check `activeRunID`.
- Description: While a generation is `Running` on the same branch, polish adopt/rewrite can still commit under `writeMutex`, advance `headRevision` (safe adopt) or set `NeedsSync` (rewrite). The in-flight run’s candidate is created with `baseHeadRevision` from begin (`NovelGenerationReducer.kt:130,242`). After polish advances head, collect rejects that candidate as stale (`NovelCollectionReducer.kt:188-190`). Durable “successful” generation becomes uncollectable; run and manuscript heads diverge under a live `activeRunID`.
- Fix direction: `assertNotBusy` in `adoptPolish` and/or `activeRunID != null → ProjectBusy` in `validatePolishCandidate` (both adopt and rewrite).

### 3. bug — Import replace can delete a busy project and is not id-safe
- File: `DefaultNovelCreation.kt:939-950`
- Description:
  1. Replace path calls `repository.deleteProject` **directly**, bypassing `deleteProject` intent’s Running check (`:526-530`). A live generation’s project can be deleted mid-run; complete/fail then `loadProject` fails; authority and recovery files are torn down under an active `LiveRun`.
  2. Existence rejection runs only when `replaceProjectId == null` (`:947-948`). If `replaceProjectId != document.project.id`, the replace target is deleted first; `createProject(packageId)` may then throw `ProjectAlreadyExists` or create a **different** id — user loses the replaced project without a successful install of the intended package.
- Fix direction: `assertNotBusy` / Running check before delete; require `replaceProjectId == document.project.id` (or transactional replace); delete only after staged create validation.

## P1 issues

### 4. bug — Interrupt terminal **events** are not single-winner
- File: `DefaultNovelCreation.kt:209-215`, `:255-258`, `:458-482`
- Description: Durable interrupt is mutex-CAS’d (`live.terminal` + run status). Events are not: `interrupt()` always launches `finalizeInterrupt` (emits `Interrupted` when it wins), and the post-stream tombstone path calls `finalizeInterrupt` **then always** `tryEmit(Interrupted)` again (`:255-258`). Collectors can observe two terminals for one cancel. Complete/fail paths correctly gate on `didComplete`/`didFail` (`:260-312`); interrupt does not share that discipline.
- Fix direction: only the mutex owner that transitions `Running → Interrupted` emits; other paths no-op on `live.terminal`.

### 5. bug — Recovery sidecar write path is half-closed
- File: `DefaultNovelCreation.kt:273` (delete only on complete), `:898-921` (flush); `persistence/NovelRecoveryStore.kt:18-71`; DI wires store in `app/.../NovelModule.kt:56`
- Description: Sidecars flush during stream and delete on successful complete only. Fail and interrupt leave files on disk. Nothing in `NovelCreation` / repository load **reads or rehydrates** `listForProject` into session/partial UI. Crash recovery is therefore not a closed loop—only a best-effort write side with leaks on non-success terminals.
- Fix direction: delete sidecar on all terminal paths; define apply-on-open (or explicitly do not ship recovery as a feature).

### 6. bug — Failed drift auto-commits rewrite without user `asRewrite`
- File: `DefaultNovelCreation.kt:828-836`; `NovelPolishReducer.kt:72-137`
- Description: When `asRewrite == false` but `compatible == false` (including fail-closed catch), coordinator still commits `saveAsRewrite`: new `ManualEdit` version, **new** `factCompatibilityID`, `NeedsSync`, outcome `ManualEditSaved` (not `PolishCandidateAdopted`). User intent was safe adopt; ledger silently rewrites lineage and forces sync.
- Fix direction: if `!asRewrite && !compatible`, return a typed failure / require explicit rewrite intent; do not auto-mutate working selections.

### 7. suggestion — Opt-in state delta fails open on collect and manual sync
- File: `DefaultNovelCreation.kt:625-627`, `:741-743`; reducers accept `stateDelta == null` (`NovelCollectionReducer.kt:132-163`, `NovelManualSyncReducer.kt:54-84`)
- Description: `runStateDelta=true` still commits collection/sync when provider/decode throws or text is blank (`stateDelta = null`). Outcome remains full success (`CandidateCollected` / `ManualSyncCommitted`) while events/summary may still describe pre-edit facts. Text/manuscript loop is honest; the fact half of the intent is not.
- Fix direction: surface `stateDeltaApplied` or fail closed when the user requested delta.

### 8. suggestion — Head-advancing sync does not supersede stale `Available` candidates
- File: `NovelManualSyncReducer.kt:105-112` (head+1, no candidate pass); contrast `NovelCollectionReducer.kt:191-200`
- Description: After manual sync, `headRevision` advances but prior prose/polish candidates remain `Available` with old `baseHeadRevision`. Collect correctly rejects them later, but UI/authority scans still show “available” unusable candidates. Same class of hygiene gap on polish head moves for non-adopted siblings.
- Fix direction: on head+1 paths, mark same-branch `Available` with `baseHeadRevision != newHead` as `Superseded` (or equivalent).

### 9. suggestion — Polish validation ignores candidate `baseHeadRevision`
- File: `NovelPolishReducer.kt:150-189`
- Description: Validates working selection still points at `sourceChapterVersionID`, but never `candidate.baseHeadRevision == branch.headRevision` (collect does at `:188-190`). After intervening head moves that leave the same chapter version selected, a polish candidate from an older head can still be adopted/rewritten.
- Fix direction: enforce the same base-head CAS as collect.

### 10. nit — Post-stream interrupt hardcodes `User` reason
- File: `DefaultNovelCreation.kt:255-257`
- Description: Tombstone path always finalizes with `NovelRunInterruptionReason.User`, even when `interrupt()` recorded Background/RouteExit. Race with async finalize can disagree on stored `interruptionReason`.
- Fix direction: store reason on `LiveRun` / tombstone map entry.

### 11. nit — `SyncSeed.headRev` / `projRev` captured then unused
- File: `DefaultNovelCreation.kt:697-710`, `:762-768`
- Description: Pre-await revisions are snapshotted but post-await path reloads and CAS’s on current only. Harmless; dead fields invite false confidence that preimage CAS is enforced across the await.
- Fix direction: drop unused fields or use them only for diagnostics.

### 12. nit — Outer `start` launch catch can emit `Failed` without durable fail
- File: `DefaultNovelCreation.kt:180-189` vs `runGeneration` structure (`reserveRun` at `:228-230` outside the inner try at `:234`)
- Description: Escape before/around the inner try that is not converted into `NovelGenerationReducer.fail` yields a process event with no matching terminal run record. Rare after current structure (reserve either commits under mutex or throws before `Started`), but still a dual-channel protocol gap if `reserveRun` grows.
- Fix direction: wrap entire post-registration body in one terminal helper.

## Residual OK

- **Lock outside await (generation):** reserve/complete/fail under `writeMutex`; `modelRunning.start(...).collect` outside (`DefaultNovelCreation.kt:228-254`, `:261-303`). Same pattern for collect/sync/polish model awaits.
- **Complete/fail single-winner durable + emit gate:** `didComplete` / `didFail` only when commit runs after revision advance (`:260-312`); re-check `live.terminal` and interrupt tombstones under the lock. Fixes the earlier “emit Completed after interrupt won” class of bug.
- **LiveRun registration before launch:** map insert at `:177-179` before `appScope.launch` — early `interrupt(runId)` can find the run.
- **Collect supersede + `baseHeadRevision`:** `NovelCollectionReducer.kt:185-200` — wrong branch, stale head, sibling `Available` → `Superseded`, chosen → `Collected` + collection checkpoint.
- **Manual needsSync → checkpoint:** edit/rewrite set `NeedsSync` without head checkpoint; `NovelManualSyncReducer.sync` requires `NeedsSync`, writes `ManualSync` checkpoint, head+1, `Synchronized` (`:49-112`).
- **Generation blocks formal work on NeedsSync:** `NovelGenerationReducer.begin:79-84` (Prose/Polish blocked; Discussion/QuickStart allowed).
- **Package/markdown export busy:** `snapshot` `assertNotBusy` for `BranchMarkdown` / `ProjectPackage` (`DefaultNovelCreation.kt:115-128`, `:485-489`); MD codec double-checks (`NovelPackageCodec.kt:46-49`).
- **Import run normalization:** `NovelPackageCodec.decode:21-34` maps `Running` → `Interrupted` and clears matching `activeRunID` so imported projects are not permanently busy.
- **Repository CAS:** `commitProject` requires `expectedRevision` and exact `+1` (`NovelProjectRepository.kt:116-134`); stage + fsync + atomic move.
- **Collect/sync reload under lock after await:** concurrent mutations lose via stale rev / busy / candidate status — no silent double-collect of the same candidate.

---

*Read-only. Cap 12. Evidence limited to cited sources; no speculative UI call-path issues.*
