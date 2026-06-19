# Orchestration: AmberAgent iOS Phase 2 Capability Closure

## Execution Rules

- Keep the original objective intact.
- Ask for approval before risky, expensive, external, or destructive actions.
- Keep immediate blocking work local.
- Delegate only bounded, disjoint, materially useful packets.
- Integrate packet results before final verification.

## Branching Rules
- If real API keys, paid services, production accounts/data, iOS entitlements, or product privacy decisions are required, record the blocker and continue with mocks/static/local validation.
- If the same external/environment failure appears twice, switch evidence source: logs, narrower tests, mocks, static checks, or subagent review.
- If a requested capability would require multi-assistant iOS, implement against the single Amber Assistant model or record the mismatch.

## Packet Prompts
- A Android Parity Auditor: Read only Android/KMP files under `app/`, `search/`, `ai/`, and tests related to Memory, Search, ImageGen, MiniApp. Output Android capability facts and gaps versus the iOS Phase 2 target. Do not edit files.
- B iOS Architecture Auditor: Read only `iosApp/iosApp` and `iosApp/iosAppTests` files related to Memory, Search, ImageGen, MiniApp, ChatViewModel, MessageBubbleView, SettingsView, AppShell, and IOSSharedSettingsStore. Output data model, persistence, concurrency, and regression risks. Do not edit files.
- C Product UX Auditor: Read only iOS UI/settings/chat files and roadmap. Flag fake settings, experimental hiding, unavailable entries, missing error/empty states, and broken loops. Do not edit files.
- D Verification Auditor: Read tests and likely touched files, then after implementation review coverage/build risks against the requested verification list. Do not edit files.

## Completion Audit
- Capability Gate Matrix exists and was used to prioritize implementation.
- Four capability loops meet the requested minimum or have precise blockers.
- P0/P1 audit findings are addressed or recorded.
- Final checks are run or blocked with exact command/error.
