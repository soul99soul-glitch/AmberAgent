# Novel Sync Fast Path Implementation Plan

> **Status:** Phase 0–4 landed 2026-08-16 per locked decisions. Current facts live in `docs/PROJECT_STATE.md`. Do not restart this plan. Do not commit unless the user explicitly requests it. Do not invent a pending-sync-point store or a new state machine.

**Goal:** New chapters finish plot-state at collect time; paragraph edits sync only the changed range; clerical errors never fail a chunk; remaining model repairs name the bad items. Fast path is the default, suffix rebuild is the exception.

**Architecture:** Keep `needsSync → manualSync pending → retryable → synchronized`. Reuse `stateDelta` (already used by ghostwrite auto-collect) for user collect and for paragraph-scoped edits. Host-side sanitization absorbs bookkeeping. Evidence still must anchor in the manuscript. Do not persist a second “sync point” artifact.

**Tech Stack:** Swift, existing `NovelFactTransactionReducer` / `NovelStructuredModelExecutor` / `NovelCreationViewModel`, XCTest.

---

## Decisions (locked)

| Topic | Choice |
| --- | --- |
| New chapter | Collect-time `stateDelta`, then `.synchronized`. No sidecar queue. |
| Co-create collect | Same inline delta as ghostwrite auto-collect, when branch is idle and already synchronized. |
| Paragraph revision (`写入正文`) | Last working chapter: existing `manualSync` pending, one `stateDelta` on the **full new chapter**, base = checkpoint before that chapter. Not append-to-current-snapshot (events do not store evidence; cannot retract by `oldText`). Middle-chapter edit keeps suffix rebuild. |
| Whole-chapter rewrite / delete-chapter suffix / editor save | Keep existing `manualSync` rebuild. `preferStateDelta` is call-site only (`applyChapterRevision`). |
| Clerical errors | Already in `sanitizedUnresolvedEntityNames`. Do not rewrite `validateEntities`. |
| All evidence discarded | First attempt throws `state_facts_evidence_unmatched` and lists the unmatched sentences. After 2 same-attempt repairs, commit empty facts and keep base summary. |
| Model repair | At most 2 same-attempt repairs. `FAILURE` includes `UNMATCHED EVIDENCE`. Same loop on collection delta. |
| Outer auto-retry | Only when `allowsOutputRepair == false` (timeout / transport / no-output). Not validation. |
| Evidence gate | Keep literal + 8-char/40% anchor. No fuzzy “close enough”. |
| Writing prompt | Unchanged. Do not ask the prose model for JSON. |
| Collect after success | Do not `scheduleAutomaticStateSync` when the branch is already `.synchronized`. |

## Non-goals

- New durable “待同步点” schema or pending kind.
- Mixing structured extract into `novel.discussion` / prose prompts.
- Loosening evidence to raise paper success rate.
- Middle-chapter full rewrite using scoped delta (still rebuild).
- Unlocking discussion `canStart` during `isPerforming` (separate product call).
- Android.

## What already exists

- Ghostwrite `systemAutoCollect` + idle synchronized branch → `executeInlineStateDeltaCollect` (`NovelFactTransactionLifecycle.swift`). Failure falls back to without-sync + later rebuild.
- Same-chunk rebuild repair (`executeStateRebuildAllowingRepair`): previous JSON + one-line error, max 2. Timeout does not repair.
- `runAutomaticStateSync` outer heal ×3 for any recoverable fail (too broad after this plan).
- `sanitizedCollectionDelta` / `sanitizedManualRebuild` already drop unmatched facts, then `requireEvidenceNotAllDiscarded` fail-closes if all dropped.
- `validateEntities` fail-closes on missing unresolved / leftover known names.
- Chapter revision card already has `oldText` / `newText` / paragraph range.

## File map

| File | Role |
| --- | --- |
| `iosApp/iosApp/NovelCreation/NovelFactOutputValidation.swift` | Clerical heal; stop fail-all when facts are empty after filter. |
| `iosApp/iosApp/NovelCreation/NovelManualSyncProgress.swift` | Targeted `repairManuscript` (list bad items). |
| `iosApp/iosApp/NovelCreation/NovelFactTransactionLifecycle.swift` | Shared repair helper for delta + rebuild; user-collect inline delta; scoped edit delta. |
| `iosApp/iosApp/NovelCreation/NovelFactTransactions.swift` | Finalize scoped edit delta; keep rebuild for unscopeable edits. |
| `iosApp/iosApp/NovelCreation/NovelCreationViewModel.swift` | After `saveManualRewrite` from revision, schedule scoped sync; outer heal only infra. |
| `iosApp/iosApp/NovelCreation/NovelSessionViewModel.swift` | `applyChapterRevision` passes paragraph range into save/sync. |
| Tests listed per phase | Contract first. |

---

### Phase 0 — Host clerical heal (no extra model call)

**Why first:** Raises success of every existing delta/rebuild without UX or schema change.

**Files:**
- Modify: `NovelFactOutputValidation.swift` (`validateEntities`, `sanitizedUnresolvedEntityNames`, `requireEvidenceNotAllDiscarded`, `sanitizedCollectionDelta`, `sanitizedManualRebuild`)
- Test: `iosApp/iosAppTests/NovelManualEditSyncTests.swift` (and any existing entity/evidence tests in `NovelFactTransactionLifecycleTests.swift`)

**Do:**
- When a referenced person is neither known nor listed unresolved: append them to unresolved (same sanitizer used by both delta and rebuild). Do not throw `Unknown entity`.
- When unresolved contains a known/clarified person: drop them. Do not throw.
- Newly unresolved with no referencing fact: drop the name, do not throw.
- `requireEvidenceNotAllDiscarded`: if all facts filtered out, keep `baseState.summary` / outline and empty fact arrays (valid “nothing new”). Still throw only if we cannot build a legal snapshot for a hard domain reason (missing material revision).

**Do not:** Change evidence matching thresholds. Do not auto-invent events.

**Tests (red first):**
- Referenced person missing from unresolved → sanitizer adds them, `validateManualChunkOutput` / collection sanitize succeeds.
- Known character listed unresolved → dropped, succeeds.
- Rebuild/delta with only unmatched evidence → commits with unchanged summary, no events.
- Unmatched evidence plus one anchored event → keeps the anchored event only.

**Gate:** `:iosAppTests/NovelManualEditSyncTests` plus the new cases green.

---

### Phase 1 — Targeted repair prompt

**Why:** Repair already exists; it only says “evidence didn’t match”. Agent cannot see which strings failed.

**Files:**
- Modify: `NovelManualSyncProgress.swift` (`repairManuscript`)
- Modify: `NovelFactTransactionLifecycle.swift` (`executeStateRebuildAllowingRepair`, new shared helper for delta)
- Modify: `NovelFactOutputValidation.swift` (return or throw a structured list of discarded evidence strings — keep throw type `NovelStructuredModelExecutionFailure` or a small value type consumed only by repair)
- Test: `NovelManualEditSyncTests`, `NovelFactTransactionLifecycleTests`

**Do:**
- When filtering evidence, remember the discarded `evidence` strings (and unknown names if any remain after Phase 0).
- Repair user text lists: `UNMATCHED EVIDENCE`, `MISSING UNRESOLVED` (should be rare after Phase 0), `FAILURE`, plus the current chunk and previous JSON.
- Apply the same repair loop to `executeCollectionTransaction` (stateDelta), not only rebuild.
- Still max 2 repairs. Still no repair on timeout / cancel / stream fail.

**Do not:** New prompt catalog version unless the extra section cannot live in user text. Prefer user-text only so receipts stay on current `novel.manual-sync.v4` / `stateDelta` versions.

**Tests:**
- Repair request contains the exact unmatched evidence sentence from the first draft.
- Collection delta: bad JSON then valid delta → one collect, synchronized, 2 model requests, no `needsSync`.
- Timeout after rejected draft still does not start a third call.

**Gate:** existing rebuild repair tests stay green; new listing assertions green.

---

### Phase 2 — Co-create collect uses inline delta

**Why:** Biggest speed win. User collect today is `commitCollectionWithoutStateSync` → `needsSync` → later multi-chunk rebuild.

**Files:**
- Modify: `NovelFactTransactionLifecycle.swift` (`prefersInlineStateDeltaCollect`, `executeCollectCandidate`, fallback)
- Test: `NovelFactTransactionLifecycleTests.swift` (`testCollectionCommitsImmediatelyWithoutStartingFactModel` will change contract — update with reason)
- Test: `NovelSessionViewModelTests` / collect presentation if `needsSync` after collect is asserted

**Do:**
- `prefersInlineStateDeltaCollect`: true when branch is `.synchronized`, idle, no pending, **and** collect source is `systemAutoCollect` **or** user collect (共创点收录). Same guards otherwise.
- On delta success: branch stays `.synchronized`, no auto-rebuild.
- On delta failure: run Phase 1 repairs (≤2). If still failing: keep today’s without-sync fallback + `needsSync` + existing rebuild (last resort, not first).
- Do not start a second discussion turn. Collect UX may wait on the one delta call (progress already exists via fact activity if wired; reuse, don’t invent chrome).

**Do not:** Persist intermediate “points”. Do not change prose generation.

**Tests:**
- User collect + valid delta script → synchronized, 1 stateDelta request, no manualSync pending.
- User collect + 2 failed deltas then valid → synchronized, 3 requests, no rebuild.
- User collect + exhausted delta → without-sync, `needsSync`, later rebuild still works.
- Ghostwrite auto-collect path unchanged (existing happy-path test).

**Gate:** collect + ghostwrite collect tests green. Manual verify: 共创收录后无「待同步」横幅。

---

### Phase 3 — Paragraph revision is a scoped delta

**Why:** `写入正文` currently `saveManualEdit` + full suffix `manualSync`. Input is huge; that is the hang after approval.

**Files:**
- Modify: `NovelSessionViewModel.swift` (`applyChapterRevision`)
- Modify: `NovelCreationViewModel.swift` (`saveManualRewrite` or a sibling that accepts a scoped range)
- Modify: `NovelFactTransactions.swift` / `NovelFactTransactionLifecycle.swift` (scoped delta after edit)
- Test: `NovelSessionViewModelTests`, `NovelFactTransactionLifecycleTests`, `IOSNovelProjectToolExecutorTests` (card still writes via `saveManualEdit`)

**Do:**
- After a successful paragraph replace (`NovelParagraphParser.replacingParagraphs`), persist the working version as today (`saveManualEdit` → `needsSync`).
- Immediately run **one** `stateDelta` whose manuscript/evidence source is **only `newText`** (the written paragraphs), base = current snapshot. On success, commit like collection finalize and clear `needsSync` without creating a suffix rebuild pending.
- If scoped delta fails after ≤2 repairs: fall back to existing `scheduleAutomaticStateSync` rebuild (same last-resort as Phase 2).
- Whole-chapter rewrite, delete-from-manuscript, and editor “save entire chapter” stay on rebuild. Scope is explicit: only `applyChapterRevision`.

**Do not:** Heuristic “if the edit is small”. Call-site is the scope.

**Tests:**
- Revision apply + valid scoped delta → synchronized, 1 stateDelta request, 0 rebuild chunks, working version is the new text.
- Revision apply + failed scoped delta → `needsSync` + one rebuild pending (existing machine).
- Ghostwrite in progress still blocks revision (existing guard).
- Approve still does not `start()` a discussion.

**Gate:** session + lifecycle tests green. True-device: 写入正文后应一次短同步或直接已同步，而不是长篇 rebuild。

---

### Phase 4 — Outer heal only for infra

**Files:**
- Modify: `NovelCreationViewModel.swift` (`runAutomaticStateSync`)
- Test: `NovelSessionViewModelTests` (`testAutomaticManualSyncHealsTransientFailureWithoutBanner`, `testAutomaticManualSyncStopsAfterBoundedHealAttempts`)

**Do:**
- After a failed `perform`, inspect `pending.lastError` / `lastStateSyncOperationError`. If it is validation / structured-output / evidence (already repaired inside the attempt), **do not** outer-retry. Publish banner once.
- Outer ≤3 remains for timeout, snapshot load, transport, cancelled-then-retryable timeout copy.

**Tests:**
- Validation fail after inner repairs: exactly 1 `perform` / model-request group, banner, `retryable`.
- Timeout: still auto-retries up to 3, then banner.

**Gate:** those two tests updated with reason; no silent threshold relaxation elsewhere.

---

### Phase 5 — Verify and hand off

- Run:
  - `NovelFactTransactionLifecycleTests`
  - `NovelManualEditSyncTests`
  - `NovelSessionViewModelTests` (sync / collect / revision cases)
  - `IOSNovelProjectToolExecutorTests`
  - `IOSNovelCreationWiringTests` (retry gate)
- Update `docs/PROJECT_STATE.md`: which phase is done, what was verified, residual (middle-chapter rewrite still rebuild; Grok Web tools still unattached).
- Device: wireless install only when the user asks. Distinguish code vs device evidence.

---

## Suggested order and stop points

Ship after Phase 2 if we only care about **new chapters**. Phase 3 is the 写入正文 win. Phase 0–1 are prerequisites and should not be skipped.

Stop and ask before: changing evidence thresholds, adding a new pending kind, or applying scoped delta to whole-chapter rewrite.

## Residual

- Middle-chapter full rewrite / deleted-chapter suffix: still rebuild. Correct, not lazy.
- Generation receipt SHA still describes the first request of an attempt (pre-existing; don’t mix into this plan).
- Collect will wait on one model call. That is the intended trade for “收录完就能继续写”.
