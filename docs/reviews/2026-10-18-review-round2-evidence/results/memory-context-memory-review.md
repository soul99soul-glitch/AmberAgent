# E / Memory: current production review

Read-only review, current shared dirty tree. No product/test changes. The ArkTS skill and the previous comprehensive review were read first. Do not re-count repaired R13/R14/R15. Findings below are new relative to that report.

## Confirmed candidate M1: extraction daily budget can be exceeded by concurrent conversations (P2)

- Owner: `harmony/chat/src/main/ets/chat/memory_extractor.ts:318-320` (read quota), `:355-356` (record extraction_started after separate model resolution).
- Trigger: two detached conversations complete while one extraction slot remains. The per-conversation `extractionInFlight` Set explicitly allows cross-conversation concurrency. Both count existing events before either writes its started event.
- Production path: `ConversationRunAuxiliary.ets:103-139` invokes runMemoryExtraction after each finalized conversation. `ConversationRunService` owns separate sessions and run records by conversation ID; no process-global memory quota lock exists. `MemoryStore.countEventsSince` reads the event file; addEvent serializes writes but does not combine count and reservation.
- Observable impact: maxDailyRuns=1 allows 2 worker model calls and persists 2 extraction_started events. Default quota 8 can likewise become 9 when two conversations finish with seven events already recorded. This is a cost-control limit, not a ranking heuristic.
- Actual-function probe: `memory-context-memory-probe.cjs`, second case invokes two real `runMemoryExtraction` calls through Promise.all, returns empty candidates, and asserts modelCalls=2 and started=2 for limit=1. No artificial delay is needed.
- Minimal repair: make the persisted quota check plus extraction_started reservation one serialized operation. Reuse the existing MemoryStore queue, preserving parallel provider calls after admission; do not serialize full model work or add retries/timers. The domain deps can accept an atomic admission callback while retaining existing test/host behavior only if needed by actual callers.
- Meaningful regression: two different conversations contend for the last slot; exactly one calls generateText, the other logs limit reached; successful reservations persist across a new repository instance. Also keep no_model/no_provider and contamination paths from consuming quota.

## Lower-priority candidate M2: real recall path omits lastUsedAt update (P3; root may defer)

- Owner: `harmony/entry/src/main/ets/platform_impl/ConversationRunDependencies.ets:188-234`, especially the final injectedIds at :227.
- Trigger: any chat turn that injects a memory through memorySystemBlocks. `recallMemorySelections` only reads/ranks. The only domain function that calls touchMemories is `buildMemoryRecallPrompt`, which the production Entry chain no longer invokes.
- Actual-function probe: transpiles the complete actual Entry source, exposes the private memorySystemBlocks function for observation, supplies a real domain record and current conversation. It returns a prompt containing record #1 and records recalledMemoryIds=[1], but touchCalls=0 and lastUsedAt stays null.
- Intended behavior evidence: `docs/reviews/2026-10-03-harmony-memory/implementation.md` says `召回只更新使用时间` and distinguishes that from reinforcement. Ranking uses lastUsedAt at `memory_recall.ts:134-136`, awarding up to 8 points. No UI currently displays this field, and the idle-archive consumer belongs to unreachable old Dream, so do not claim active data loss or incorrect automatic archiving.
- Minimal repair if included: call touchMemories only for actual injectedIds after prompt budget/Jev selection; do not touch candidates rejected by reranking or budget and do not reinforce on recall. A test should capture actual Entry injection rather than call legacy buildMemoryRecallPrompt.

## Rejected / by design / no production path

- Memory import/export stale files in a reused target directory: real pure-function weakness, but no production Entry caller of getMemoryImportExportDeps/exportMemoriesTo/importMemoriesFrom exists. Excluded from product findings.
- Old Dream maintenance/automatic promotion/archive: exports exist, but no active production trigger. No findings based on its behavior.
- UTC extraction day boundary and 120-second per-conversation debounce follow the documented Android contract; do not call them defects.
- Broad contamination guard after search/MCP, explicit source grounding floor, short evidence threshold and last-24 target window are explicit current policies. No additional defect asserted from preference alone.
- Current read path prunes invalid topic sources before recall (R13 fixed); all persistent allocators use durable lastId in same atomic file (R14 fixed); extraction commit guard compares the actual preceding assistant/source fingerprint (R15 fixed).
- Profile invalidation on source content/scope/kind/expiry/archive and restored-pair exclusions were traced; no confirmed additional issue.

## Reproduction

From repository root:

`harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json .workflow/comprehensive-review-round2-20261018/results/memory-context-memory-probe.cjs`

Saved output: `memory-context-memory-probe.out`.

Controlled SDK/dependency ports only. This proves the source-level sequencing and the Entry invocation, not real provider billing, SDK compilation or physical device behavior.
