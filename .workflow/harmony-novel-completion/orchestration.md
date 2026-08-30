# Orchestration: Harmony Novel Completion

## Execution Rules

- Keep the original objective intact.
- Ask for approval before risky, expensive, external, or destructive actions.
- Keep immediate blocking work local.
- Delegate only bounded, disjoint, materially useful packets.
- Integrate packet results before final verification.
- Never advance a Phase before implementation, two-domain regression, ArkTS lint/build/install,
  logic review, UI review, evidence-backed fixes, and re-verification are complete.
- Preserve user dirty files and stage exact paths only.

## Branching Rules

- If a review finding has no production caller, reproduction, test, log, or screenshot evidence,
  record and reject it instead of adding speculative fallback.
- If API 12 compatibility is uncertain, pause that code slice only, query official docs, then continue.
- If target-device streaming is buffered/unavailable, report the transport state honestly and fix the
  shared Chat transport seam; never add a Novel-only parser or typewriter simulation.
- If project revision changes during a workflow, fail closed at the commit seam; never overwrite.

## Packet Prompts

After every Phase spawn two read-only packets:

1. `phase_<n>_logic_review`: verify invariants, production entrypoint, caller/callee chain,
   ownership, cancellation, failure, resume, persistence, and exactly-once behavior.
2. `phase_<n>_ui_review`: inspect actual ArkUI plus target-device screenshots for state refresh,
   alignment, margins, spacing, size, touch targets, dark theme, scrolling and terminal handoff.

Workers must cite file/line and distinguish confirmed defects from optional improvements.

## Completion Audit

For each Phase write `results/C<n>-integration.md` with accepted/rejected findings, fixes,
tests/lint/build/install/screenshots and commit SHA. C7 final report must map every original user gap
to a production call chain and runtime proof.
