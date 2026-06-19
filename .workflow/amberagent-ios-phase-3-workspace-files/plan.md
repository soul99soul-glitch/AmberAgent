# AmberAgent iOS Phase 3 Workspace/File Capability Closure

Goal:
Make iOS Workspace, file context, Artifacts, file import/preview/parsing, and permission approval a real local capability loop rather than placeholder UI or scattered entries.

Success Criteria:
- Build a Phase 3 Workspace/File Capability Gate Matrix from iOS and Android source facts before implementation.
- iOS exposes one Amber Assistant only; no multi-assistant system.
- Workspace has a real home surface for imported files, previews, selected chat context, artifact management, missing-file recovery, and honest unsupported states.
- Text, Markdown, and PDF basic extraction have testable paths; Office/OCR/iCloud limitations degrade honestly.
- Artifacts can be saved, read, and deleted, and can store outputs from chat/deep-read/webmount/miniapp/image generation surfaces without changing those main flows.
- File reads and writes through local tools require explicit permission approval.
- Swift XCTest covers safety bookmarks, stale files, metadata, parsing, chat context, preview/removal, artifact CRUD, approvals, and failure states where practical.
- Required checks run or precise environment blockers are recorded.

Constraints:
- Follow AGENTS.md and protect existing worktree changes.
- Do not edit Android business logic, Gradle/Xcode generated project files, signing/release/private config, or real account/API data.
- Do not auto-scan user directories or read private files.
- Do not fake unavailable iOS system capabilities.
- Keep current visual direction; no large visual redesign.

Workflow Artifact Path:
`.workflow/amberagent-ios-phase-3-workspace-files`

Work Packets:
- A Android Workspace Parity Auditor: read-only Android workspace/file/artifact parity map.
- B iOS File Security Auditor: read-only security bookmark, Files permission, stale file, privacy review.
- C Product UX Auditor: read-only loop review: import -> preview -> context -> result -> artifact/manage.
- D Verification Auditor: read-only test/build/failure-state review.

Integration Policy:
Main agent owns all writes. Subagent output is evidence, not authority. P0/P1 findings are implemented or explicitly recorded with rationale.

Verification:
- `git diff --check`
- `xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build`
- Relevant `iosAppTests`
- KMP Gradle checks only if shared/KMP code changes.
