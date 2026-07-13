# Novel final domain review (post-P0)

Scope: closed-loop + breakage only in `DefaultNovelCreation` (`collectCandidate`, terminal CAS, generation complete/fail) and `NovelCollectionReducer` (stale candidate / superseded). Read-only; no inventions beyond cited code.

## Summary

The three targeted P0 fixes are present and correct in source:

1. **`collectCandidate` does not hold `writeMutex` across model await** — preflight under lock, provider await outside, re-load + collect + commit under lock.
2. **`complete` (and fail/interrupt) skip `commitProject` when revision is unchanged** — avoids CAS throw on no-op reducer returns.
3. **`NovelCollectionReducer.collect` supersedes same-branch sibling `Available` candidates and rejects stale `baseHeadRevision` / wrong branch.**

Durable collect and generation happy paths close: reserve → stream (unlocked) → complete/fail commit under mutex; collect → chapter/checkpoint/head+1 → candidate `Collected` + siblings `Superseded`. Residual breakage is concentrated in **terminal event CAS**: durable finish is largely serialized, but **which terminal `NovelRunEvent` is emitted** is still not a single exclusive winner under the write lock.

## Closed-loop verdict

| Path | Durable loop | Verdict |
|---|---|---|
| Collect (no state delta) | `perform` → `collectCandidate` → mutex reload → `NovelCollectionReducer.collect` → `commitProject(+1)` | **Closed** (stale/supersede enforced in reducer) |
| Collect + `runStateDelta` | mutex preflight → **await outside lock** → mutex collect/commit | **Closed** for text collect; delta best-effort (failures swallowed → collect without facts) |
| Generation start | `writeMutex` → `begin` + commit → provider outside lock | **Closed** |
| Generation complete | mutex → `complete` → commit iff rev advanced → set `live.terminal` → emit `Completed` | **Partial** — durable skip-on-no-op fixed; event may still lie (see Issues 1–2) |
| Generation fail | mutex → `fail` → commit iff rev advanced → set terminal → **always** emit `Failed` | **Partial** — durable OK; event not gated on winning CAS (Issue 3) |
| Interrupt | tombstone + cancel + async `finalizeInterrupt` under mutex | **Partial** — durable interrupt OK when still `Running`; dual/mismatched terminal events remain (Issues 1, 3–4) |

**Overall:** prior P0 lock-hold / commit-on-no-op / collect isolation issues are **fixed**. Remaining risk is **protocol/event closed-loop** (wrong or duplicate terminal events; rare strand of `Running` if no-op complete marks process-terminal), not manuscript double-apply under normal single-winner durable CAS.

## Verify checklist

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | `collectCandidate` does **not** hold `writeMutex` during model await | **PASS** | `DefaultNovelCreation.kt:117-120` routes collect outside the outer `perform` lock; `:522-536` preflight under lock; `:538-573` `modelRunning.start(...).collect` with no mutex; `:575-594` re-lock for collect+commit |
| 2 | `complete` skips commit when revision unchanged | **PASS** | `:236-241` `if (next.project.revision != loaded.document.project.revision) commit…`; same pattern fail `:265-267`, interrupt `:422-424`. Reducer no-ops return same doc (`NovelGenerationReducer.complete` `:183-186`, `:190-191`) |
| 3 | collect supersedes sibling `Available` + checks `baseHeadRevision` | **PASS** | `NovelCollectionReducer.kt:185-190` branch + `baseHeadRevision == branch.headRevision`; `:191-200` chosen → `Collected`, other same-branch `Available` → `Superseded` |

## Remaining P0/P1

- **P0 (must-fix before trusting cancel vs complete UX):** none on the three verified durable mechanics. No remaining lock-across-await, commit-on-no-op-throw, or collect sibling-stale authority hole in the reducer.
- **P1 (terminal event / race closed-loop):** Issues 1–5 below. Prefer a single `tryTerminal` under `writeMutex` that returns the event to emit (or null), and only emit that one event after the lock.

## Issues

### Issue 1 — Severity: bug
- File: `feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:232-246`
- Description: After the complete critical section, the coordinator emits `NovelRunEvent.Completed` whenever `live?.terminal == true`, **not** when *this* call performed a durable complete. If `finalizeInterrupt` already set `terminal = true` and committed `Interrupted`, complete’s `withLock` early-returns on `live?.terminal == true` (`:233`) and then still hits `:244-246` and emits **`Completed` after cancel won**. Callers (e.g. workspace refresh-on-Completed) treat a cancelled run as success.
- Suggestion: Track a local `won: Boolean` (or terminal reason) set only on the successful complete branch inside the lock; emit `Completed` only if `won`. Mirror for fail.

### Issue 2 — Severity: bug
- File: `DefaultNovelCreation.kt:232-242`; `NovelGenerationReducer.kt:183-191`
- Description: On complete, `live?.terminal = true` runs even when `NovelGenerationReducer.complete` **no-ops** (run missing / not `Running` / `activeRunID` mismatch → same revision → no commit). Combined with Issue 1’s emit rule, the process can report **`Completed` without appending a candidate or finishing the run in the document**. If the run is still `Running` in storage (asymmetric branch pointer), `assertNotBusy` stays true forever while UI saw completion.
- Suggestion: Set `terminal` and emit only when `next.project.revision != loaded…` **or** document already shows this run non-`Running` with the intended status; otherwise leave terminal unset / emit nothing / route to interrupt/fail recovery.

### Issue 3 — Severity: bug
- File: `DefaultNovelCreation.kt:247-275`
- Description: Fail path checks `interruptTombstones` only **before** acquiring `writeMutex` (`:248-250`). Under the lock it checks `live.terminal` but **not** the tombstone. After the lock it **unconditionally** emits `Failed` (`:270-275`), even when the lock body no-op’d because interrupt already terminalized. Race: exception → tombstone false at catch entry → interrupt wins mutex and durable-interrupts → fail no-ops → still emits **`Failed` after `Interrupted`**.
- Suggestion: Re-check tombstone under the lock; emit `Failed` only if this call committed fail (or uniquely owns terminal). Prefer one shared terminal helper with fail/interrupt/complete.

### Issue 4 — Severity: bug
- File: `DefaultNovelCreation.kt:172-189`, `:227-230`, `:248-250`, `:411-427`
- Description: Multiple independent `Interrupted` emitters for one cancel:
  1. `finalizeInterrupt` always `tryEmit(Interrupted)` after durable interrupt (`:426`).
  2. Post-stream tombstone path calls `finalizeInterrupt` **then** emits `Interrupted` again (`:227-229`).
  3. Catch tombstone path emits `Interrupted` and returns **without** coordinating with the async `finalizeInterrupt` from `interrupt()` (`:248-250` vs `:187-189`).
  Durable apply is mutex-safe; the event loop is not single-CAS — collectors may double-refresh or observe contradictory order with Issue 1/3.
- Suggestion: Only the mutex owner that transitions the run emits the single terminal event; other paths observe `live.terminal` and stay silent.

### Issue 5 — Severity: bug
- File: `DefaultNovelCreation.kt:154-168`
- Description: `appScope.launch { runGeneration… }` runs before `liveRuns[runId] = LiveRun(...)`. Early `interrupt(runId)` can miss the map entry (no tombstone/cancel registration on that live). `runGeneration` may see `live == null` (`:206`), so `partial` is not stored, `live?.terminal = true` is a no-op, and complete’s post-lock `if (live?.terminal == true)` skips `Completed` even after a successful durable complete — **durable success without terminal event** (inverse of Issue 1). Outer `catch` on the launch (`:157-163`) can emit `Failed` with **no** durable `fail` if something escapes after `begin` committed.
- Suggestion: Insert `LiveRun` (job placeholder) into `liveRuns` before `launch`; on escape after reserve, best-effort `fail` under mutex.

### Issue 6 — Severity: suggestion
- File: `DefaultNovelCreation.kt:538-572`, `:575-593`
- Description: When `runStateDelta=true`, provider/decode failures are swallowed (`catch { stateDelta = null }`) and collect still returns `CandidateCollected`. Text collect closed-loop is honest; the **delta half** of the intent can silently drop with no outcome flag. Empty model text also skips decode without error.
- Suggestion: Surface `stateDeltaApplied` / warning on outcome, or fail closed when the user opted into delta and extraction fails.

### Issue 7 — Severity: suggestion
- File: `feature/novel/src/test/.../NovelGenerationLifecycleTest.kt` (collect happy path only)
- Description: Lifecycle tests cover prose → candidate `Available` and single collect → `Collected` + chapter. There is **no** test that a second same-branch `Available` becomes `Superseded`, or that collect with mismatched `baseHeadRevision` throws. Regressions of the P0 collect isolation would not fail CI.
- Suggestion: Add reducer/lifecycle tests for supersede siblings and stale head rejection.

### Issue 8 — Severity: nit
- File: `DefaultNovelCreation.kt:232-234` vs `:252-253`
- Description: Complete re-checks `interruptTombstones` under `writeMutex`; fail does not. Asymmetric terminal guards make Issue 3 easier to reintroduce when editing one path.
- Suggestion: Share one `finalizeRun(kind)` used by complete/fail/interrupt.

---

## Residual OK (out of issue budget / not regressions of the three verifies)

- Generation provider await remains outside `writeMutex` (reserve/complete/fail only under lock) — still sound.
- Collect re-loads document under lock before reduce; uses current revision/head for expected CAS — concurrent collects serialize; second sibling correctly fails `not available` / stale after first advances head.
- `NovelGenerationReducer.complete` blank content throws → catch can durable-fail (no silent empty candidate) — acceptable.
- Recovery sidecars / polish transaction ledger still unwired — intentional residual, not introduced by these P0s.

*Cap 8 issues. Evidence limited to current `feature/novel` sources cited above.*
