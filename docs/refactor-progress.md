# Refactor Progress — Agent Kernel + Surfaces

## Phase 0 — 2026-05-27

- Tasks completed: T0.1, T0.2, T0.3, T0.4, T0.5, T0.6
- Gate review: P1=0, P2=0, P3=0, P4=1 (fixed), P5=2
- Unresolved: none
- Commits: 56ee5b0a..7fd2637b (6 commits)
- Notes: detekt custom rule deferred (project has no detekt); using shell script + CI workflow instead. ADR-0001 amended with sync redactor sentinel. CI workflow refactored to delegate to check-legacy-package.sh.

## Phase A — 2026-05-28

- Tasks completed: TA.1, TA.2, TA.4, TA.5
- Tasks deferred: TA.3a, TA.3b (UniFFI tokenizer — requires Rust toolchain, independent pipeline work)
- Gate review: P1=0, P2=2 (fixed), P3=3 (fixed), P4=4, P5=2
- Unresolved: none
- Commits: d79164d0..06edc6a2 (6 commits)
- Notes: :core:agent-runtime (pure Kotlin, zero Android deps, 12 interface files). :core:agent-store-room (Room entities for agent_run/agent_event/trace_span/permission_intent, separate DB). LegacyRunScope adapter created. GenerationHandler scope param deferred to Phase C per review. ADR-0002 with 14 design decisions.

## TC.0 (Phase C pre-split) — 2026-05-28

- Tasks completed: TC.0.1 (TranslationHandler), TC.0.2 (AiAuxiliaryGenerator)
- Gate review: P1=0, P2=1 (fixed), P3=1 (fixed), P4=1, P5=1
- Unresolved: none
- Commits: 06903658..12e25273 (3 commits)
- Notes: ChatService 2623→2474 (−149 lines). ConversationAccess interface extracted for clean delegation. checkDeletedFiles param preserved in interface. getConversationFlowOrNull added for null-safe session access. compressConversation left in ChatService (depends on ensureFullConversationLoaded).

## Phase B — 2026-05-28

- Tasks completed: TB.2 (:feature:deepread:api), TB.3.5 (DeepReadAgentAdapter), TB.5 (ADR-0003)
- Tasks deferred: TB.1 (Rust reader-extractor, requires cargo-ndk), TB.3 (full pipeline), TB.4 (Surface ViewModel)
- Gate review: P1=0, P2=0, P3=2 (fixed), P4=2, P5=2
- Unresolved: none
- Commits: 9ae5cedb..289977de (5 commits)
- Notes: :feature:deepread:api module (pure Kotlin). DeepReadAgentAdapter wraps existing RunManager into Agent interface. ADR-0003 documents UniFFI strategy.

## Phase A (TA.3b) — 2026-05-28

- Tasks completed: TA.3b (tokenizer Rust crate)
- Gate review: combined with Phase B
- Commits: 9534d469 (1 commit)
- Notes: tiktoken-rs for o200k/cl100k, char-based approximation for Claude/Gemini. 5 cargo tests pass. cargo ndk arm64-v8a release verified.

## Phase C (TC.1 + TC.2 + TC.3) — 2026-05-28

- Tasks completed: TC.1 (:feature:chat:api), TC.2 (ChatTurnAgent adapter), TC.3 (InProcessAgentRunner + InMemoryAgentRegistry)
- Commits: 5c390cc5, f9daed4a, 7901f169 (3 commits)
- Notes: ChatTurnAgent wraps GenerationHandler via ChatSessionResolver interface. InProcessAgentRunner enables runner.launch() dispatch. TC.4-TC.6 (ChatPage UI, projector, property tests) are downstream integration that requires connected device testing.

## Phase D (QW1) — 2026-05-28

- Tasks completed: QW1 (:core:agent-utils with shared ToolJson extensions)
- Commits: 2b39dec4 (1 commit)
- Notes: Public versions of string/requiredString/boolean/int/long ready for webmount decoupling. Physical module extraction (Sprint 2-4) requires mechanical import path updates across hundreds of files — structurally ready but volume work.

## Phase E (TE.3 + TE.4) — 2026-05-28

- Tasks completed: TE.3 (architecture documentation), TE.4 (CLAUDE.md update)
- Commits: efed227e, b42875ef (2 commits)
- Notes: docs/architecture.md with module map + ADR index. CLAUDE.md updated to reflect Agent Kernel architecture. TE.1 (allowlist minimization) and TE.2 (package renaming) depend on Phase D module extraction completion.

## Session 2 — 2026-05-28

### TA.3a — Tokenizer Gradle pipeline
- Registered tokenizer in cargo-ndk build pipeline (registerCargoBuild)
- TokenCounterNative JNI bridge + TokenCounter suspend API with JVM fallback
- libtokenizer.so verified in arm64-v8a output
- Commit: 8381ce12

### Phase A.5 — HARD GATE template
- ADR-0004 documenting the 5-item gate checklist
- All 5 items confirmed already implemented (NativePathBootstrap, NativePathPrefs, Crashlytics, sampling)
- Commit: 3e07ff4b

### TC.5 — ChatEventProjector
- AgentEvent.Final → chat.db bridge via ConversationRepository
- Unfinished run replay on process restart
- Commit: 8c7d1509

### TC.6 — Projector property tests
- 4 kotest properties: determinism, idempotency, truncation safety, regenerate candidate preservation
- All 4 pass
- Commit: 849afaf6

### Session 2 continued — 2026-05-28

### TA.3a — Tokenizer Gradle pipeline + JNI bridge
- Commit: 8381ce12

### ADR-0004 — HARD GATE native crate template
- Commit: 3e07ff4b

### TC.3 — AgentRunner param added to ChatService
- Commit: 84e6babc

### TB.1 — reader-extractor Rust crate + Gradle pipeline + JNI bridge
- readability crate for HTML extraction, section splitting
- arm64-v8a build verified, 2 cargo tests pass
- Commits: 6a313f6e, 6e45c13d

### TB.3 — reader-extractor wired into DeepRead
- Native dispatch in DeepReadSourcePrefetcher.extractReadableText()
- Falls back to JVM regex stripping when native unavailable
- Commit: db919b5b

### Full APK build verified
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL

### Phase D — Package migration (Sprint 2-4)
- Sprint 2: Moved history(2), webview(1), live(3) — Commits: 9d21058d, e8c9fde3, 03308e90
- Sprint 3: Moved cron(4), icloud(6), office(5+radar) — Commits: f96ec95f, f56b616c
- Sprint 2+: Moved system(2), task(4), prompts(1), miniapp(12) — Commit: 25f0cb19
- Sprint 4: Moved terminal(4), modelcouncil(6), workspace(2), subagent(9) — Commit: f60f96ba
- Sprint 4: Moved board(54), tools(33), webmount(70) — Commit: 234f04dc

**Result: 227 files in app.amber.* (within :app) + 26 in new modules = 253 total migrated**
**Remaining in legacy: 563 files (UI pages, data models, DI, services — not agent subsystems)**
**All 13 agent subsystems fully relocated to app.amber.feature.***

### Full APK verified
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL

## Session 3 — 2026-05-28 (Kernel path fully wired)

### ChatSessionResolverImpl
- Reads SettingsAggregator + ConversationRepo + MemoryRepo to build ChatSession
- Commit: 86591396

### Koin DI — full Kernel registration
- AgentRuntimeDatabase + DAO in DataSourceModule
- InMemoryAgentRegistry (ChatTurnAgent + DeepReadAgentAdapter registered) in AgentRuntimeModule
- InProcessAgentRunner + RoomAgentEventStore
- ChatService receives agentRunner via constructor
- Commit: 4cfb1fef

### useKernelPath flag exposed
- ChatService.useKernelPath now public, flip to activate kernel path
- Commit: 5532b507

### InProcessAgentRunner persists runs to DB
- appendRun(running/completed/failed) + markInterrupted on cancel
- Job tracked per run for real cancellation
- Commit: 2471bdf2

### Real ChatEventProjector
- projectFinalized writes MessageNode to chat.db (not just metadata)
- commitEvent persists Final AgentEventRecord to agent_event table
- ProjectingEventWriter bridges AgentEventWriter → Projector
- ProjectingRunScope used for ChatTurnInput runs (via runScopeFactory)
- Commit: 310fa5e8

### Physical Gradle module splits
- :feature:history + :feature:webview (zero me.rerere deps)
- :feature:task + :feature:workspace (visibility fixes for cross-module access)
- :feature:icloud (JsonInstant moved to :core:agent-utils as shared dep)
- Commits: 1ee7c018, 79b5402d, fb3bc9bb

### Plan revision #8 (Compose optimizations, no new tasks)
- ChatPage 16x collectAsState → sub-Composable split: attach to TC.4 (+2-3d)
- Markdown streaming throttled parse: attach to TD.Rust.1a (+1-2d)
- Conversation full-load guard: any time in Phase D (+0.5d)
- DI lazy module: zero cost, automatic from module split

## Remaining Work

### Phase C behavior switch (partial)
- TC.4: ChatPage UI observe(runId) migration (with修订 #8 sub-Composable split)

### Phase D — physical module splits still in :app

### Phase D remaining
- runtime/ sub-package (5 files: AgentToolDispatcher, PermissionDecisionResolver, etc.) — too many cross-references to move atomically
- Root agent files (5: notifier, receiver, models, activity store, ToolFailure)

### Phase E legacy cleanup
- TE.1: 563 files remain in me.rerere.* (non-agent: UI, data models, DI, services)
- TE.2: highlight/document package renaming (depends on JNI crate migration)
