## Summary

Phase 2–4 domain/runtime now has real closed loops for **start → begin commit → stream → complete/fail commit**, **collect → checkpoint**, **fork/undo/resolve**, and **busy-gated package/markdown export**. The generation happy path (mutex only around reserve/complete/fail; provider await outside the lock) is sound. The highest-risk breaks are: **`CollectCandidate` holds `writeMutex` across the entire state-delta provider await** (freezes all novel writes/interrupts), **collection does not isolate/supersede sibling `Available` candidates or enforce `baseHeadRevision`/`branchID`**, **run terminalization is not CAS’d under the write lock (double terminal events / complete-after-interrupt races)**, and **`undoHead` rewinds manuscript head without repairing candidate/session authority**. Export-while-busy is correctly gated. Polish request kind is silently demoted to Prose. Recovery sidecars and polish transaction ledger remain honestly unwired.

## Closed-loop map (action → durable?)

| Action | Call chain | Durable? | Notes |
|---|---|---|---|
| **Start prose/discussion/quickstart** | `start` → `reserveRun` (`writeMutex`) → `NovelInjectionPlanner` + `NovelGenerationReducer.begin` → `commitProject(+1)` → `modelRunning.start` (lock released) | Yes (run `Running`, receipts, user message) | Kind routing: Polish request never selected (see Issue 5) |
| **Stream deltas** | `AndroidNovelModelAdapter` / scripted flow → `NovelRunEvent.Delta`; `live.partial` volatile | Process-memory only | No recovery sidecar write |
| **Complete** | `NovelGenerationReducer.complete` → `commitProject` → `Completed` event | Yes when run still `Running` | No-op if already terminal; caller still tries commit (Issue 2) |
| **Fail** | `fail` → commit only if revision advanced → `Failed` event | Yes when still `Running` | Better than complete path |
| **Interrupt** | tombstone + `modelRunning.cancel` + `job.cancel` + async `finalizeInterrupt` → `interrupt` reducer → commit | Yes if still `Running` under mutex | Terminal flag + events not single-CAS (Issue 2) |
| **Collect (no state delta)** | `perform` → `assertNotBusy` → `NovelCollectionReducer.collect` → commit | Yes (chapter version, checkpoint, head+1, candidate `Collected`) | Sibling candidates stay `Available` (Issue 3) |
| **Collect + state delta** | same, but `modelRunning.start` **inside** `perform`’s `writeMutex` | Partial / hazardous | Lock across provider (Issue 1); delta failure swallows to `null` (honest collect-without-facts) |
| **Resolve proposal** | `NovelBranchReducer.resolveProposal` → commit | Yes (materials on accept; always configRevision+1) | No project-busy gate (OK if no manuscript race) |
| **Fork** | `assertNotBusy` → `fork` → commit | Yes (new branch/session; inherit `Available` as `InheritedReadOnly`) | No checkpoint lineage check (Issue 7) |
| **Undo head** | `assertNotBusy` → `undoHead` → commit | Yes (head pointer / selections) | Does not repair candidates/session (Issue 4) |
| **Manual edit** | reducer busy if `activeRunID` → commit | Yes | Coordinator has no `assertNotBusy`; reducer covers same-branch run |
| **Export MD / package** | `snapshot` → `assertNotBusy` + codec busy check (MD) → encode | Read of durable snapshot | **Closed** for busy rejection |
| **Import package** | decode (Running→Interrupted, clear `activeRunID`) → optional delete + `createProject` | Yes | Replace is delete-then-create (non-atomic residual) |
| **RetryTerminal** | throws hard | N/A | Honest stub |
| **Polish adopt/abandon / recovery sidecar** | model types exist; no coordinator path | No | Residual OK |

## Issues (bug|suggestion|nit with file:line)

### Issue 1 — Severity: bug
- File: `feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:117`, `:496-555` (provider await `:521-528`)
- Description: `perform` wraps every intent in `writeMutex.withLock`. `collectCandidate` with `runStateDelta=true` calls `modelRunning.start(req).collect { … }` **while still holding that mutex**. During state extraction, **all** other novel mutations block: other `perform`s, `snapshot` (including export), `reserveRun` / complete / fail, and `finalizeInterrupt`. Interrupt cannot durable-finalize a concurrent generation on any project while collect waits on the provider. This is lock-holding across provider await — the generation path correctly avoids it; collect reintroduces it.
- Evidence: `perform` L117 `writeMutex.withLock { … collectCandidate … }`; collect L521-528 nested `modelRunning.start(req).collect` with no mutex release; comment L537 “re-load after optional network” admits network under lock.
- Suggestion: Split collect into (1) preflight under lock, (2) provider await **outside** lock, (3) re-load + CAS collect under lock (same pattern as `runGeneration`). Or use a separate long-op queue that does not hold `writeMutex`.

### Issue 2 — Severity: bug
- File: `DefaultNovelCreation.kt:216-229`, `:230-257`, `:393-409`; `NovelGenerationReducer.kt:183-186`, `:262-265`, `:325-328`
- Description: Durable run finish is “if status==Running then mutate” (serialized only when callers hold `writeMutex`), but **live terminalization and events are not CAS under that lock**:
  1. **Complete always commits** after `complete()` even when the reducer no-ops (`runIndex < 0` / not `Running` → same document). `commitProject` requires `revision == expected+1` → throws; catch may emit `Failed`/`Interrupted` after a prior durable terminal.
  2. **No tombstone re-check under the complete lock** (check only at L216 before acquire). Interrupt can mark tombstone and finalize as `Interrupted`, then complete still enters the lock; no-op complete + throw + catch double-emits `Interrupted`, or (if finalize loses) durable `Completed` after user cancel.
  3. `live.terminal` is a plain `@Volatile Boolean`, set **after** commit (L228, L251) or inside finalize (L407). `finalizeInterrupt` early-outs on `terminal` but **complete/fail do not set `terminal` before emit under the same critical section as commit**.
  4. Cancelled job with tombstone (L231-233) emits `Interrupted` **without** calling `finalizeInterrupt` (relies on async L176-178); ordering can yield event-without-durable or double events.
- Suggestion: Under `writeMutex`, single function `tryTerminal(runId)` that: re-reads doc, applies complete|interrupt|fail only if `Running`, commits iff revision advanced, sets `live.terminal=true`, emits exactly one terminal event. Complete path must skip commit on no-op (mirror fail L247-249).

### Issue 3 — Severity: bug
- File: `domain/NovelCollectionReducer.kt:75-81`, `:185-189`
- Description: Collect marks only the chosen candidate `Collected`. Enum already has `Superseded` (`NovelEnums.kt:174-175`) but siblings with `status==Available` on the same branch remain collectible. There is **no** check that `candidate.branchID == command.branchId`, and **no** check that `candidate.baseHeadRevision == branch.headRevision`. After one collect advances head, a second stale candidate can still append to the new head, producing chapter/checkpoint history whose `sourceCandidateID` / `baseHeadRevision` no longer isolate uncollected alternatives. Fork correctly freezes source `Available` as `InheritedReadOnly` (`NovelBranchReducer.kt:62-71`); collect does not apply equivalent isolation on the live branch.
- Suggestion: On successful collect: require matching branch + `baseHeadRevision == headRevision` (or explicit allowlist); set other same-branch `Available` candidates to `Superseded` (or `InheritedReadOnly`).

### Issue 4 — Severity: bug
- File: `domain/NovelBranchReducer.kt:118-178` (esp. `:144-153`); call site `DefaultNovelCreation.kt:586-598`
- Description: `undoHead` moves `headCheckpointID` / `workingChapterSelections` / state snapshot to the parent checkpoint and bumps head/working revisions, but:
  - Candidates with `status==Collected` and `collectedCheckpointID == undone head` stay `Collected` (not restored to `Available`, not marked orphaned).
  - Session messages are not trimmed to `parent.sessionCursor`.
  - Checkpoints remain append-only (good) but authority after undo is **corrupt relative to collect**: UI can show a collected candidate whose manuscript contribution is no longer on head, and re-collect is blocked (`status != Available`).
- Suggestion: On undo of a `Collection` checkpoint, re-open or supersede linked candidates deterministically; optionally restore session projection to cursor (or document that session is not rewound and enforce it in UI). Add transition tests for collect→undo→recollect.

### Issue 5 — Severity: bug
- File: `DefaultNovelCreation.kt:275-284`, `:290-304`
- Description: `NovelRunKindRequest.Polish` exists on the public request (`NovelCreation.kt:135`) and reducer/prompt paths support `NovelRunKind.Polish`, but `reserveRun` kind selection never assigns Polish:
  ```
  QuickStart | (auto) → QuickStart
  DiscussPlan → Discussion
  else → Prose
  ```
  A client that starts a polish run **silently executes Prose** (candidate kind Prose, prose prompts). Fake-success / wrong-loop relative to requested kind.
- Suggestion: Map `request.kind == Polish` explicitly (and fail honestly if polish transaction prerequisites are not implemented yet).

### Issue 6 — Severity: bug
- File: `runtime/AndroidNovelModelAdapter.kt:75-137`
- Description: Cancel registration race and error taxonomy break the interrupt closed loop at the adapter boundary:
  1. `launch { … }` runs before `mutex.withLock { jobs[runId] = job }` (L76-131). Early `cancel(runId)` can miss the job entirely; generation continues until natural completion.
  2. `catch (error: Exception)` (L119-126) treats **cancellation** as `NovelModelEvent.Failed(provider_error)`, so cooperative cancel may surface as fail/retryable rather than clean stream end; coordinator then depends solely on tombstones to map to interrupt.
- Suggestion: Register job in map before any suspend work (or use `coroutineContext.job` atomically); rethrow/swallow `CancellationException` without `Failed`; let coordinator own terminal reason.

### Issue 7 — Severity: suggestion
- File: `domain/NovelBranchReducer.kt:47-50`, `:73-88`
- Description: Fork loads any `checkpointId` present in the document, not necessarily on the source branch’s parent chain from head. A caller can fork “from” another branch’s checkpoint while labeling `sourceBranchId` as branch A, inheriting A’s session messages via A’s session + that checkpoint’s cursor while using B’s chapter selections/state. Not blocked by validator. Corruption risk if UI ever exposes multi-branch checkpoint pickers incorrectly.
- Suggestion: Assert checkpoint is reachable from `source.headCheckpointID` via `parentCheckpointID` links (or `createdOnBranchID` / lineage field if added).

### Issue 8 — Severity: suggestion
- File: `DefaultNovelCreation.kt:136-158`
- Description: `appScope.launch { runGeneration… }` starts before `liveRuns[runId] = LiveRun(...)`. Concurrent `interrupt` by `runId` or project filter can miss the live entry; early deltas may see `live == null` (partial not stored for finalize). Outer `catch` (L146-152) emits `Failed` without durable `fail` if something escapes `runGeneration` after `begin` committed (defense-in-depth gap).
- Suggestion: Insert `LiveRun` (job placeholder) before launch; on any escape after reserve, best-effort `fail` under mutex.

### Issue 9 — Severity: suggestion
- File: `DefaultNovelCreation.kt:496-535`
- Description: State-delta extraction failures are swallowed (`catch (_: Exception) { stateDelta = null }`) and collect still returns `CandidateCollected` success. Honest for “text collect without facts,” but **fake success for the delta half** of the intent when `runStateDelta=true`—callers cannot tell facts were dropped. Empty model text also silently skips decode (L530-532).
- Suggestion: Surface `stateDeltaApplied: Boolean` / warning on outcome, or fail closed when user opted into delta and extraction fails.

### Issue 10 — Severity: suggestion
- File: `serialization/NovelPackageCodec.kt:21-35`; `DefaultNovelCreation.kt:616-640`
- Description: Import normalizes Running→Interrupted and clears `activeRunID` only when the referenced run was Running in the pre-map document (works for that case). `importPackage` replace path `deleteProject` then `createProject` is not one atomic install—crash leaves project missing. Acceptable residual if documented; still a closed-loop hole for “replace import.”
- Suggestion: Stage new primary then swap; or create-under-temp-id then rename.

### Issue 11 — Severity: nit
- File: `domain/NovelBranchReducer.kt:195-198`
- Description: Config-revision mismatch throws `StaleProjectRevision` rather than `StaleConfigRevision` (generation begin uses the latter at `NovelGenerationReducer.kt:88-89`). Clients distinguishing config vs project stale will mis-handle proposal resolve.
- Suggestion: Match generation’s error type when only configRevision differs.

### Issue 12 — Severity: nit
- File: `NovelGenerationReducer.kt:189-191` vs `:266-267` / `:329-330`
- Description: `complete` requires `branch.activeRunID == run.id` or no-ops; `interrupt`/`fail` only require branch index ≥ 0 and still clear `activeRunID` via `finishRun` even if branch pointer was already null/mismatched. Asymmetric terminalization can leave confusing no-op complete after a partial branch unlink.
- Suggestion: Unify branch/run pointer checks in `finishRun` preconditions.

## Residual OK

- **Recovery/lifecycle directories** exist on disk layout (`NovelProjectRepository` L29-30, L52-53) but generation never writes recovery sidecars / lifecycle journals — crash mid-stream loses partials except in-memory `live.partial`. Honest gap until a recovery phase.
- **Polish transaction** records/outcomes exist in the model; no `perform`/`start` path adopts/abandons polish candidates — OK if UI does not claim polish.
- **`RetryTerminal`** hard-throws (`DefaultNovelCreation.kt:130`) — explicit, not fake success.
- **Smart material injection** without embeddings skips non-Always materials (`NovelInjectionPlanner.kt:154-160`) — deterministic residual; decisions still receipted.
- **Export while busy**: closed — `assertNotBusy` (L412-416) + `exportMarkdown` busy check (codec L46-49); package snapshot also asserts before encode.
- **Idempotent operation replay** for generation ops: always fresh `operationID`s; not claimed for run start.
- **State-delta optional best-effort** when `runStateDelta=false` — intentional.

*Review scope: `feature/novel` Phase 2–4 domain/runtime only. Read-only; no code changes. Cap 12 issues.*
