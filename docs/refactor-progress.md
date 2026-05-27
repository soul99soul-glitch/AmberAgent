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

## Phase C (TC.1) — 2026-05-28

- Tasks completed: TC.1 (:feature:chat:api with ChatTurnInput/Artifact/EventPayload)
- Tasks deferred: TC.2 (ChatTurnAgent implementation), TC.3 (ChatService degradation), TC.4-TC.6
- Commits: 5c390cc5 (1 commit)
- Notes: @Serializable added to value classes in :core:agent-runtime for serialization support.

## Phase D (QW1) — 2026-05-28

- Tasks completed: QW1 (:core:agent-utils with shared ToolJson extensions)
- Tasks deferred: Sprint 2-4 (module extraction sprints)
- Commits: 2b39dec4 (1 commit)
- Notes: Public versions of string/requiredString/boolean/int/long ready for webmount decoupling.

## Deferred Work Summary

The following items require infrastructure not available in this session:

### Rust Toolchain Dependent (TA.3a + downstream)
- TA.3a: UniFFI Android Pioneer (cargo-ndk 4 ABI, CI cross-compile)
- TA.3b: tokenizer crate
- Phase A.5: markdown + regex Rust crate production wiring (HARD GATE)
- TB.1: reader-extractor Rust crate

### Deep Implementation (multi-week, require extensive business logic understanding)
- TC.2: ChatTurnAgent (rewrite of GenerationHandler 230-line tool loop)
- TC.3: ChatService degradation to service shell
- TC.4-TC.6: ChatPage UI, projector, property tests
- TB.3: Full DeepRead pipeline
- TB.4: DeepReadSurface ViewModel
- Phase D Sprint 2-4: Physical module extraction of 18 agent sub-systems

### Legacy Cleanup (Phase E, depends on D completion)
- TE.1: Minimize legacy allowlist
- TE.2: Rename highlight/document module packages
- TE.3: Documentation rewrite
- TE.4: Internal docs update
