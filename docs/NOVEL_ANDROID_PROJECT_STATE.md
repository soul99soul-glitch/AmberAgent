# Novel Creation Android — Project State

> Updated: 2026-07-31 (deferred queue closed)

## Status

Android novel creation is **feature-complete for the planned iOS parity track** (MVP → M1 → P0′ → P1 → P2 MVP) **plus deferred deepenings**:

| Milestone | Plan | Status |
|-----------|------|--------|
| M1 P0-A/B/C | collect delta / sheet / import-export | ✅ |
| P0′ | materials CRUD · QS multi-char · dual model | ✅ |
| P1-A/B | regenerate+replace · version history · bubble undo | ✅ |
| P1-C | injection panel · discussion archive MVP | ✅ |
| P2 | batch polish · continuity audit · discard · keep-both · stream quiet | ✅ MVP |
| Deferred | LLM distill · find-replace · multi-chunk sync · undo archive · stream polish | ✅ |

### Plan docs

- [`docs/plans/2026-07-30-novel-android-ios-parity-plan.md`](plans/2026-07-30-novel-android-ios-parity-plan.md)
- [`docs/plans/2026-07-31-novel-android-ios-gap-audit.md`](plans/2026-07-31-novel-android-ios-gap-audit.md)
- [`docs/plans/2026-07-31-novel-p0-prime-three-pack.md`](plans/2026-07-31-novel-p0-prime-three-pack.md)
- [`docs/plans/2026-07-31-novel-p1-rewrite-history.md`](plans/2026-07-31-novel-p1-rewrite-history.md)
- [`docs/plans/2026-07-31-novel-p1c-archive-injection.md`](plans/2026-07-31-novel-p1c-archive-injection.md)
- [`docs/plans/2026-07-31-novel-p2-remaining.md`](plans/2026-07-31-novel-p2-remaining.md)

---

## Closed user paths

### Core
- Project CRUD, QuickStart (multi-character), degraded restore
- Discussion / prose / polish / **regenerate** generation
- Collect sheet (target / paragraphs / state-delta) + interrupted collect
- Manual edit → needsSync → **multi-chunk** sync (chapter-sized passes, merged delta)
- Reader, Living materials (CRUD + decisions), settings (dual model, branches)

### P1/P2
- **ReplaceChapter** collect after rewrite
- Version history restore (same fact lineage)
- Bubble **撤销收录** (head only; also rewinds archive cursor / restores candidate)
- **本次上下文** injection receipt panel
- **归档讨论** — LLM auto-distill prefill → user confirm → DecisionLog + archiveCursor
- **批量润色** serial polish→adopt (drift skip)
- **一致性审计** one-shot structured report
- **废弃本章** (`discardedAt` + remove from working selections)
- Import conflict: **替换 / 保留两份 (keep-both project id remap)**
- Stream complete: quiet window before clearing streaming text (chat / polish / regenerate)

### Deferred closures (this pass)
- **LLM distill discussion archive** (`distillDiscussionArchive` + `novel.discussion-archive.v1`)
- **Chapter editor find-replace** (查找 / 替换 / 全部)
- **Multi-chunk manual sync** (per-chapter model passes; single final commit; not durable iOS ledger)
- **Undo archive** (head DiscussionArchive → recompute archiveCursor from lineage)
- **Restore polish/collect candidate** on undo head (`sourceCandidateID` → Available)
- **Streaming history quiet** unified 120ms clear

---

## Still thinner than full iOS (explicit non-goals)

| Item | Notes |
|------|--------|
| Full polish transaction ledger machine | Simplified adopt path |
| Durable multi-chunk manual sync resume ledger | In-memory multi-pass only; no pendingOperations progress resume |
| Full identity remapper keep-both | Project id + injection receipts only (iOS same simplification) |
| Presentation pacer perfection | Quiet window + basic pin; not full iOS presentation machine |
| Global NovelCreationModelPreferences | Null stateSync falls back to writing model |

---

## Tests

- `:feature:novel:testDebugUnitTest` — domain / codec / lifecycle / structured decode / distill / undo archive
- `:app:compileDebugKotlin` — UI (archive distill sheet, find-replace editor)

### External verification still needed (device)

- SAF import/export keep-both  
- Real-provider continuity audit + discussion distill quality  
- Long multi-chapter manual sync feel  
- Long-stream reading feel on device  

---

## Review notes

Phase reviews via subagents on P0′ / P1 / P1-C; P2 + deferred deepenings landed with unit/compile verification.
