# AmberAgent iOS Phase 2 Capability Closure

## Goal
Push AmberAgent iOS Phase 2 from entry points to real usable loops for Memory, Search, Image Generation, and Mini App, as close as practical to Android parity while keeping iOS to a single Amber Assistant.

## Success Criteria
- Each capability reaches configuration/entry -> use -> result -> history or management -> error recovery.
- Memory supports search/filter, source view, edit/delete, recall explanation, and write approval evidence.
- Search supports provider selection, search_web, scrape_web, normalized results, citations, and graceful failure.
- Image Generation supports entry, parameters, history/gallery, save/share or export path, chat tool results, and missing configuration states.
- Mini App supports management list, runner, install/update/delete, permissions, bridge storage/network/search/fetch, HTML validation, and runtime errors.
- P0/P1 findings from subagents or isolated review passes are fixed or explicitly recorded.
- `git diff --check`, relevant iOS tests, and best-effort xcodebuild/Gradle checks are run or precisely blocked.

## Current Context
- Branch: `codex/ios-port-wip`, ahead of origin by 16 at start.
- Start checks run: `git status --short --branch`, `git log --oneline --decorate -12`.
- Existing untracked workflow directory `.workflow/amberagent-ios-deep-read-closure` appears unrelated and must not be overwritten.

## Constraints
- Follow AGENTS.md and protect existing worktree changes.
- Do not modify Android business logic, Gradle/Xcode generated files, certs, release config, `google-services.json`, private config, real accounts, API keys, paid services, or production data.
- Do not implement multi-assistant iOS, hide these features behind experimental/global toggles, add fake API-key/readiness settings, or use static copy as a substitute for working features.
- Stay within allowed iOS Phase 2 Swift files, related tests, `IOSSharedSettingsStore` write-back, minimal chat/settings/app shell wiring, small new support files, and a small roadmap update.
- Avoid DeepRead, WebMount advanced tools, remote SSH, SubAgent, and model council mainlines except read-only comparison or minimal non-breaking result entry wiring.

## Risks
- Some loops may require real external provider keys, paid APIs, account state, iOS entitlements, simulator availability, or user privacy/product policy decisions.
- Large parity work can create regressions in chat runtime or settings persistence; verify narrow tests first and then build.

## Approval Required
- Pause before destructive deletes, real external account/API usage, production data, paid services, privacy policy choices, Apple entitlement changes, or allowing Mini Apps broad automatic networking/persistent sensitive storage.

## Work Packets
- A Android Parity Auditor: read-only Android Memory/Search/ImageGen/MiniApp implementation and produce gap matrix.
- B iOS Architecture Auditor: read-only iOS stores/runtime/tests for data model, persistence, concurrency, and regression risks.
- C Product UX Auditor: read-only iOS UI/settings/chat copy to find fake settings, experimental placement, unavailable entries, and loop breaks.
- D Verification Auditor: after implementation, review test coverage/build risk; early pass may identify expected test targets.

## Integration Policy
- Main agent owns all implementation and final decisions.
- Subagent output is evidence, not authority; conflicts are resolved by inspecting source.
- No subagent may edit files unless explicitly reassigned with a disjoint write scope.

## Verification
- Per-slice focused tests where available.
- End checks: `git diff --check`; relevant `iosAppTests`; best effort `xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build`.
- Run shared/KMP Gradle checks only if shared/KMP files are modified.

## Reusable Artifacts
- Keep the Phase 2 Capability Gate Matrix and final audit notes in this workflow directory and mirror the final landed scope into `docs/ios-port/IOS_ANDROID_PARITY_ROADMAP_2026-06-19.md`.
