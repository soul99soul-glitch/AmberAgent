# Orchestration: AmberAgent iOS WebMount advanced closure

## Execution Rules

- Keep the original objective intact.
- Ask for approval before risky, expensive, external, or destructive actions.
- Keep immediate blocking work local.
- Delegate only bounded, disjoint, materially useful packets.
- Integrate packet results before final verification.

## Branching Rules
- If a real account, OAuth, real cookie, certificate, or production data is required, stop that path, mark it unsupported/blocked, and continue with mock/static coverage.
- If a check fails twice with the same external blocker, switch to logs/static tests/mocks and record the exact failing command.
- If Xcode project edits are required to compile newly added files, pause for approval before editing the project file.
- If concurrent unrelated changes appear, preserve them and adapt.

## Packet Prompts
- Packet A: Audit Android WebMountManager, UserSiteRegistry, WebViewPool, SessionHandle, JsBridge, ProfileBridge, and WebMount*Tools against iOS WebMount capability needs. Read-only. Output gaps, tool names, permission/security expectations, and test suggestions.
- Packet B: Audit iOS WebMountView, IOSLocalToolExecutor, permissions, message rendering, and WebKit usage for security, redaction, allowlist, JS, cookie/session, and unsupported flows. Read-only.
- Packet C: Audit product UX for formal advanced WebMount: site management, enable/disable, open/state/read/navigation/session clear, empty/error states, content handoff to chat/deep-read, and result readability. Read-only.
- Packet D: Audit verification approach: existing iosAppTests patterns, how to test new services without simulator/real WebKit where possible, likely xcodebuild blockers, and commands to run. Read-only.

## Completion Audit
- Capability gate matrix created and used.
- Implementation covers the ordered lanes or records exact unsupported reasons.
- Tests/static checks added for mockable paths.
- git diff --check run.
- xcodebuild/tests run or exact blocker captured.
