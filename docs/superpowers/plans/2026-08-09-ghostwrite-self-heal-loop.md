# Ghostwrite Self-Heal Loop Implementation Plan

> **For agentic workers:** Implement task-by-task against `docs/superpowers/specs/2026-08-09-ghostwrite-self-heal-loop-design.md`. Steps use checkbox syntax.

**Goal:** Multi-chapter ghostwrite self-heals quality failures with bounded rewrites, never re-accepts a dead draft, and offers polish-shaped human recovery when automatic heal fails.

**Architecture:** Extend `NovelGhostwriteProgress` + pure helpers in `NovelGhostwritePipeline.swift` for heal state/receipts; keep the batch loop in `NovelSessionViewModel` but replace fail-closed `return false` with a chapter heal loop. Human recovery uses a revision brief sheet that produces a new prose candidate and re-enters acceptance (not canon polish adopt).

**Tech Stack:** Swift / SwiftUI iOS novel domain; XCTest in `NovelCollaborationModeTests`.

**Spec:** `docs/superpowers/specs/2026-08-09-ghostwrite-self-heal-loop-design.md`

---

## File map

| File | Responsibility |
| --- | --- |
| `iosApp/iosApp/NovelCreation/NovelGhostwritePipeline.swift` | Phases, pause reasons, progress, heal helpers, receipt, rewrite policy |
| `iosApp/iosApp/NovelCreation/NovelSessionViewModel.swift` | Batch loop, obtain candidate, auto heal, revision start |
| `iosApp/iosApp/NovelCreation/NovelSessionSheets.swift` | Board CTAs + Recovery revision sheet |
| `iosApp/iosApp/NovelCreation/NovelPromptCatalog.swift` | HEAL RECEIPT / revision prompt snippets |
| `iosApp/iosApp/NovelCreation/NovelChapterPlanProposalLifecycle.swift` | (later) Tier2 amend helpers if needed |
| `iosApp/iosAppTests/NovelCollaborationModeTests.swift` | Pure progress + rewrite policy tests |
| `docs/NOVEL_COCREATION_GHOSTWRITE_PLAN.md` | Phase 5 pointer |
| `docs/PROJECT_STATE.md` | Current next cut |

---

### Task 1: P0 — Rewrite policy + no same-draft re-accept

**Files:**
- Modify: `NovelGhostwritePipeline.swift`
- Modify: `NovelSessionViewModel.swift` (`startGhostwriteChapter` retain candidate, `obtainGhostwriteCandidate`)
- Test: `NovelCollaborationModeTests.swift`

- [ ] Add `NovelGhostwritePauseReason.requiresRewriteOnContinue` (true for acceptanceFailed, obviousRepetition, blockingContinuity, incompleteCandidate; false for sync/collect infra reuse cases).
- [ ] `startGhostwriteChapter` retainedCandidate: nil when `requiresRewriteOnContinue`.
- [ ] `obtainGhostwriteCandidate` mustRewrite uses same predicate.
- [ ] Tests: acceptanceFailed requires rewrite; syncFailed does not; resume progress clears candidate when rewrite required.
- [ ] Run: `NovelCollaborationModeTests` filtered ghostwrite batch tests.

### Task 2: P1 — Failure receipt + in-chapter auto rewrite loop

**Files:**
- Modify: `NovelGhostwritePipeline.swift` (receipt, heal fields on progress)
- Modify: `NovelSessionViewModel.swift` (`runOneGhostwriteChapter` heal loop)
- Modify: `NovelPromptCatalog.swift` or write userText builder for heal
- Test: pure receipt fingerprint + attempt budget tests

- [ ] `NovelGhostwriteFailureReceipt` + progress fields: qualityAttemptIndex, maxQualityAttempts, lastFailureReceipt.
- [ ] On quality fail: if attempts remaining, supersede candidate, rewrite with receipt in userText, re-gate; else pause for human.
- [ ] Board labels show attempt n/max.
- [ ] Tests for budget exhaustion → pause; success path resets attempt index on chapter complete.

### Task 3: P2 — Infra retry (sync) + thin amend (optional thin slice)

- [ ] Sync Tier0: retry awaitGhostwriteStateSync up to 3 before pause.
- [ ] Tier2 only if P1 stable: single must rephrase helper + amendment log (can defer if large).

### Task 4: P3 — Recovery revision sheet

- [ ] `recommendRevisionBrief(receipt:plan:)`
- [ ] Sheet with editable brief; start revision run → new prose candidate → re-accept.
- [ ] Board primary CTA when Tier3.

### Task 5: Docs

- [ ] Point Phase 5 in ghostwrite plan + PROJECT_STATE.

---

**Default execution:** inline in this session, P0 → P1 first; P2/P3 if time; no commit unless user asks.
