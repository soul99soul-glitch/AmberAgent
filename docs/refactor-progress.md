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
