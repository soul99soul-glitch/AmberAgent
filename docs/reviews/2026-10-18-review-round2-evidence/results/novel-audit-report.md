# Packet C — active Harmony Novel lifecycle and storage

Read-only audit of dirty working tree at HEAD `e6d6c26b6bf151ee3c58cbaa0b4e2acf61deac55`. No production or checked-in test edits. Loaded `.agents/skills/harmony-arkts/SKILL.md`. Reviewed actual final code including earlier R05/R16–R20 repairs. Independent DeepRead product excluded. Probes below live only in this results directory.

## Confirmed findings

### N1 · P2 · Ghostwrite pause/cancel can still start a model after it reports stopped

- Owner: `harmony/deepread/src/main/ets/novel/ghostwrite_runner.ts:367` awaits `validate`; lines 368–383 immediately register/start without checking the durable stage and original claim again. Async `resolveModelTarget` at line 361 has the same boundary.
- Caller: `creation.ts:474–480` / `512–520` durably pause/cancel, then `runner.cancel()`. During validation there is no `activeModelRuns` entry to cancel. `resumeGhostwrite` at line 484 also waits for this driver, so the newly started un-cancelled run can delay explicit resume.
- Reproduction uses real `createNovelCreation` + file repository, gates the runner's validation after successful start preflight, pauses/cancels, then releases validation. Both variants produce one new request after durable stop, and zero cancel calls. Durable stage remains paused/cancelled, so do not describe this as manuscript overwrite; repository claim checks still protect storage.
- Command: `harmony/deepread/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json .workflow/comprehensive-review-round2-20261018/results/novel-ghostwrite-validate-stop-probe.ts`.
- Output: `{"action":"pause","beforeReleaseStage":"paused","durableStage":"paused","requestsAfterStop":1,"cancelled":[]}` and corresponding `cancelled` stage for cancel.
- Secondary review: ordinary generation `collectWithEvents` already checks `live.interrupted` before start; state rebuild and continuity repair likewise check cancellation. Repaired polish does fresh `safePoint` after validation. This is an isolated missing ghostwrite boundary, not a requirement for another state machine.
- Minimal repair: carry the original claim into ghostwrite `callModel`; after async selection/validation, load/check stage and owner before start. Reuse `safePoint` / terminal handling. Ensure writer, reviewer, planner routes all stop gracefully. Tests: extend `novel_creation_ghostwrite.test.ts` or `novel_audit_regressions.test.ts` with gated validate pause and cancel; assert zero requests and prompt resume settlement. Run affected ghostwrite runner/creation suites.

### N2 · P2 · A visible page stops watching a system-waiting polish job

- Owners: `harmony/entry/src/main/ets/pages/NovelPolishPage.ets:68,143` and `NovelWorkspacePage.ets:760,770` reuse execution-stage predicates for polling; both exclude `waiting_system`.
- Actual external producer: `NovelPolishWorkAbility.ets:44–46` calls background driver independently; `creation.ts:2147–2149` intentionally re-schedules `waiting_system` on open instead of running it in foreground. A page loaded while waiting therefore has no timer to observe later system-triggered writing/progress/completion. Leaving/reentering or explicit resume refreshes it, but normal background progress remains stale.
- Reproduction executes extracted actual `running`/`schedulePoll` and `polishStageRuns`/`schedulePolishPoll` methods via TypeScript transpilation, without modifying production. Command: `node .workflow/comprehensive-review-round2-20261018/results/novel-waiting-poll-probe.cjs`.
- Output: `{"page":"NovelPolishPage","timersForWaitingSystem":0,"timersForWriting":1}` and `{"page":"NovelWorkspacePage","timersForWaitingSystem":0}`.
- Secondary review: durable waiting itself is by design and existing `novel_polish_creation.test.ts` explicitly preserves it. The defect is observation eligibility; neither page registers a cross-context job event listener. Do not change scheduler/state-machine semantics.
- Minimal repair: let visible page polling include `waiting_system` while preserving its non-running meaning for controls/busy state. Extend `harmony/entry/src/test/novel_polish_page_ui.test.cjs` (it already executes extracted page methods) and a workspace page behavior test to execute waiting → writing/completed with a controlled job source; do not merely assert source strings. Run existing page-focused CJS suites plus `novel_polish_creation.test.ts` waiting-system cases. UI change requires ArkTS lint/build/sign/install and screenshot under project rules.

### N3 · P2 · Public workspace roundtrip loses differing per-branch reasoning choices

- Independent storage sub-audit confirmed using real creation setter, public export/parse/install roundtrip: main=false / side=true becomes false / false.
- Owner: `harmony/deepread/src/main/ets/novel/workspace_interop.ts:176–178` assigns project.md reasoning flag to every branch; line 446 emits only main branch flag.
- Existing contract: `docs/reviews/2026-10-03-standalone-completion/p4-state-implementation.md:17` says branch-isolated; `harmony/deepread/src/test/novel_p5_exchange.test.ts:59–61,81` checks per-branch author fields including this flag, but its fixture line 43 initializes both true.
- Probe command: `harmony/deepread/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json .workflow/comprehensive-review-round2-20261018/results/novel-storage-reasoning.ts`. Output `before=[{id:main,reasoning:false},{id:side,reasoning:true}]`, `after=[{id:main,reasoning:false},{id:side,reasoning:false}]`; generated side ID is random per run. Independent storage child performed the first reproduction and parent audit independently reran it.
- Scope: public ZIP exchange only; native backup preserves flag. Public exchange already claims/checks author-field retention, so this is not a request to include private runtime state.
- Minimal repair: serialize branch-specific value in branch.md and read it with legacy project.md fallback; reject malformed present values consistently. Add a differing-value multi-branch public roundtrip test in `novel_p5_exchange.test.ts`, plus legacy fallback case. Run workspace interop/exchange and native backup suites.

## Rechecked exclusions

- R05 directory swap crash recovery and R16 project.md retention are already repaired; not relisted.
- R17/R19/R20 live polish owner overlap, immediate cross-context resume, and system-stop during validation already have current code and focused regressions; not relisted.
- Polish validation of all three distinct role models is an explicit existing contract (`novel_polish_creation.test.ts:225–254`), even though current runner calls writer/reviewer only. Classified by design, not silently relaxed.
- Native backup copy clears ordinary-run runtime owner/cursor deliberately; existing behavior/tests support it.
- Branch forked ordinary-run records are blocked by active branch identity; no cross-branch resume was demonstrated.
- Paused/failed durable jobs blocking branch mutations follows frozen-job contract; user must cancel/resolve them before structural branch changes.
- Restore/install checks durable jobs idle under repository project lock; frontend in-memory checks alone were not treated as integrity proof or absence.
- No dead-code deletions, retries/fallback framework, or speculative recovery layers proposed.

## Evidence limits

Probes execute actual domain functions or extracted current page methods under Node; no real provider, device WorkScheduler window, process kill, or UI screenshot was exercised by this audit packet. Each finding has a concrete minimal regression target for implementation and final platform validation.
