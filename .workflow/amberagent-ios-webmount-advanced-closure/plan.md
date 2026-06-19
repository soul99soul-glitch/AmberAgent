# AmberAgent iOS WebMount advanced closure

## Goal
Close the AmberAgent iOS WebMount advanced web tooling loop so WebMount is a formal advanced capability: site registry, allowlist/settings, WKWebView runtime/session state, read-only page extraction, basic navigation, visual/capture surface, content handoff into chat/deep-read fallback, and permission/error UX.

## Success Criteria
- User can manage sites, enable/disable them, open allowed pages, inspect session state, read/extract page content, navigate back/forward, clear session, and move web content into chat or the existing deep-read entry path when available.
- High-risk actions are denied, unsupported, or routed through explicit approval; arbitrary JS eval is not enabled by default.
- Cookies/tokens/Authorization headers/full sensitive URLs are never emitted.
- OAuth, signed fetch, real account login, and site-specific adapters remain explicitly unsupported unless already available without real account state.
- Tests or static verification cover the implemented mockable paths; build/test blockers are recorded with exact commands and errors.

## Current Context
- Branch: codex/ios-port-wip, ahead of origin by 16 at start.
- Initial status: only .workflow/ was untracked before this WebMount workflow directory was created.
- Recent history already includes "Add WebMount clear session approval flow"; implementation must integrate with existing code instead of replacing it blindly.

## Constraints
- Do not modify Android business logic.
- Do not touch MiniApp, Workspace/File, DeepRead, SubAgent, model council, or remote execution mainlines except minimal read-only content handoff wiring explicitly allowed by the goal.
- Do not change release config, certificates, real accounts/cookies, production data, or delete untracked files.
- Do not hide WebMount behind an experimental/global kill switch.
- Keep SwiftUI changes local, compositional, and visually consistent with current iOS design.

## Risks
- WebKit availability and simulator/build environment may block live runtime validation.
- URL/cookie/session output can leak sensitive data if not summarized and redacted centrally.
- Tool declarations and local executor behavior can drift unless tests validate shared names/permission behavior.
- Concurrent work on MiniApp/Workspace/DeepRead may touch adjacent files; keep edits minimal and inspect before patching.

## Approval Required
- Modifying the Xcode project file.
- Deleting untracked files.
- Running real-login/OAuth flows or touching real account/session data.
- Enabling arbitrary JS eval by default or bypassing WebKit security.
- Destructive git operations.

## Work Packets
- A Android WebMount Parity Auditor: read-only comparison of Android WebMount classes and iOS gaps.
- B iOS WebKit/Security Auditor: read-only review of iOS WebMount runtime, redaction, permissions, and WebKit risk.
- C Product UX Auditor: read-only review of iOS WebMount UX, settings, empty/error states, and result presentation.
- D Verification Auditor: read-only test/build plan and likely failure points.
- Main implementation: capability matrix, registry/settings/allowlist, runtime/session/cookie, read-only bridge/tools, permission/result UI, tests, verification.

## Integration Policy
- Treat auditor output as evidence, not authority.
- Resolve conflicts by inspecting the source files directly.
- Keep implementation scoped to allowed iOS files and new IOSWebMount*.swift helpers.
- Prefer mockable pure Swift services for tests; avoid Xcode project edits unless unavoidable and approved.

## Verification
- Required start checks already run: git status --short --branch; git log --oneline --decorate -12.
- After edits: git diff --check.
- Best effort: xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build.
- Best effort: targeted iosAppTests covering registry, enable/disable, allowlist, WKWebView session state abstractions, extract/get, back/forward, cookie summary/clear, tool permission denial, chat/deep-read fallback.

## Reusable Artifacts
- Capability gate matrix under this workflow results directory.
- Final report with implemented/unsupported/blocked items and exact verification evidence.
