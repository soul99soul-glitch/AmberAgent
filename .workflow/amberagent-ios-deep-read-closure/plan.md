# AmberAgent iOS Deep Read Closure

## Goal
Make Deep Read a real, locally verifiable iOS feature in AmberAgent:
entry points, task creation, loading/failed/succeeded states, history,
detail/results, persistence, source normalization, minimal templates,
search/conversation/file/web source paths, retry, and tests.

## Success Criteria
- iOS has a visible Deep Read entry and does not hide it behind experiments or a master switch.
- iOS keeps a single Amber Assistant and does not reintroduce Android-style multi-assistant behavior.
- Manual text, search result, and conversation sources are locally verifiable.
- File and WebMount sources either work through real read-only paths or degrade honestly.
- Tasks persist in Documents with history and result detail restoration.
- Template/layout choice exists with validation, without fake API-key/settings placeholders.
- Failure, retry, no network/API key, unreadable source, and generation failure have clear recovery paths.
- `git diff --check` runs; Xcode build/tests run when the local environment allows it.

## Current Context
- Branch: `codex/ios-port-wip...origin/codex/ios-port-wip [ahead 16]`.
- Initial working tree was clean.
- Initial log head: `dd0f86c6f Consolidate iOS capability parity work`.
- User-required first commands completed: `git status --short --branch`, `git log --oneline --decorate -12`.
- Required code comparison: iOS Board/persistence/search/chat/file/web views versus Android Deep Read UI, agent, repository, tools, templates, and board signal pipeline.

## Constraints
- Follow AGENTS.md: clarify real blockers, keep scope small, avoid over-engineering, do not touch code that is not understood.
- Do not modify Android business logic.
- Do not edit Gradle/Xcode generated project artifacts, certs, release config, private config, or `google-services.json`.
- Do not touch real accounts, API keys, paid services, or production data.
- Do not restore Android multi-assistant behavior on iOS.
- Do not split statistics back into an independent page.
- Do not make broad visual-design changes.
- Allowed iOS changes are limited to the user-listed Deep Read adjacent files plus new small `IOSDeepRead*.swift` files and tests.

## Risks
- Swift files are large; edits must stay narrow and avoid turning existing types into god objects.
- Xcode/test environment may require simulator/Xcode components unavailable in this sandbox.
- Real search, model generation, WebMount sessions, or file security scopes may require credentials or platform permissions.
- Android implementation includes hidden assistants/playbooks that should inform behavior but not be ported directly.

## Approval Required
- Destructive cleanup, mass rename, force push, external service/account/API-key access, Apple account/entitlement changes, or touching private config.
- None required for local read-only audits, local Swift code edits within scope, local tests, or local build attempts.

## Work Packets
- P0 gap matrix: read required iOS and Android files and define real gaps before edits.
- P0 model/persistence: task/source/template/result models, Documents storage, history read/write, state transitions.
- P0 UI loop: Board entry, create screen, history/detail/results, retry, copy/save/chat actions, honest empty/error states.
- P0 source adapters: manual text, search results, conversation messages; file/web honest degradation or read-only adapter.
- P1 tests: persistence, history, normalization, template validation, failure/retry, search/conversation/file/web sources.
- P1 docs: small roadmap update with actual landed scope and known unsupported paths.
- P2 polish: minimal copy cleanup and visual alignment only where needed.

## Integration Policy
- Main agent owns all code edits unless a worker is explicitly assigned a disjoint write scope.
- Current subagents are read-only explorers for Android and iOS audits.
- Explorer output is evidence, not authority; integrate only after checking relevant files locally.
- Preserve user/unrelated changes and never revert files outside this task.

## Verification
- After each meaningful lane, run the narrowest static/test command that can validate it.
- Required final checks: `git diff --check`; try `xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build`; try relevant `iosAppTests`.
- Record exact command and error for any environment blocker.

## Reusable Artifacts
- Keep this workflow only as a task audit trail.
- Do not store secrets, bulky logs, transcripts, account data, or private source content.
