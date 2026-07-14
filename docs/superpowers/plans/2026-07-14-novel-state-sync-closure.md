# Novel State Sync Closure Implementation Plan

> **For agentic workers:** Implement inline in this task. Do not commit unless the user explicitly requests it.

**Goal:** Make novel state synchronization close cleanly from local manuscript save through provider execution, durable recovery, foreground resume, success cleanup, and actionable failure UI.

**Architecture:** Keep the existing `needsSync -> manualSync pending -> retryPending -> synchronized` state machine and file format. Narrow manual rebuild input to authoritative manuscript/state, teach the existing single-flight scheduler to resume its one durable manual-sync pending, and include fact mutations in the existing background interruption path.

**Tech Stack:** Swift, Swift Concurrency, Observation, SwiftUI, XCTest

---

### Task 1: Lock the state-sync contract with regression tests

**Files:**
- Modify: `iosApp/iosAppTests/NovelFactTransactionLifecycleTests.swift`
- Modify: `iosApp/iosAppTests/NovelSessionViewModelTests.swift`
- Modify: `iosApp/iosAppTests/IOSNovelCreationWiringTests.swift`

- [x] Extend `testManualRebuildPlannerUsesRebuildBaseState` with a unique recent-discussion sentinel and assert both the model request and injection receipt contain no session-message section.
- [x] Add a persisted retryable-manual-sync fixture next to `documentWithChapter()` using the existing `saveManualEdit`, `prepareManualSync`, and `markRetryable` reducers.
- [x] Add an appearance recovery test that calls only `scheduleAutomaticStateSyncIfNeeded()` and asserts one request, empty `pendingOperations`, `.synchronized`, and matching repository/ViewModel snapshots.
- [x] Add a failure test that asserts one request only, `.retryable`, `.needsSync`, and the exact provider `lastError` after the automatic attempt.
- [x] Add a background interruption test proving an in-flight manual-sync mutation is cancelled into durable recoverable state.
- [x] Add wiring assertions that foreground activation schedules state sync and the banner consumes `pending.lastError`.

Run:

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/NovelFactTransactionLifecycleTests \
  -only-testing:iosAppTests/NovelSessionViewModelTests \
  -only-testing:iosAppTests/IOSNovelCreationWiringTests test
```

Expected before implementation: the new no-session-input and persisted-pending recovery assertions fail.

### Task 2: Remove non-authoritative chat from manual state rebuild

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelFactTransactionLifecycle.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelManualSyncProgress.swift`

- [x] Pass `.empty` as the session cursor limit for every manual-rebuild planner invocation, including the chunk-fit validator.
- [x] Keep the durable pending cursor unchanged because final checkpoints still use it.
- [x] Do not change the 60-second timeout, output schema, prompt version, chunking, or provider selection.

Expected behavior: the current sample request drops its twelve recent chat messages while retaining formal manuscript and projected state.

### Task 3: Close durable recovery and lifecycle termination

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelCreationViewModel.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelCreation.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelGenerationLifecycle.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectWorkspaceView.swift`

- [x] Let the existing scheduler proceed when the branch has exactly one `manualSync` pending, whether `.pending` or `.retryable`; dispatch the existing `.retryPending` action instead of creating a second pending.
- [x] Keep other pending kinds blocked so legacy/project mutations are not silently broadened.
- [x] Include `.syncManualEdits` and `.retryPending` in the existing background mutation cancellation filter so cancellation reaches the provider and existing `markPendingRetryable` path.
- [x] On project-workspace foreground activation, call the same scheduler after the routed project is loaded. Do not introduce a timer or retry loop.

Expected behavior: relaunch/orphaned pending, retryable failure, and background cancellation all re-enter the same single-flight state machine; success removes the pending and publishes `.synchronized`.

### Task 4: Show the persisted failure reason without new chrome

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionView.swift`

- [x] Use trimmed `pending.lastError` as the banner message when present.
- [x] Keep the existing banner, button, spacing, colors, and manual retry action unchanged.
- [x] Fall back to the existing generic copy only for old records without an error.

### Task 5: Verify and hand off

- [x] Run the three focused test classes above.
- [x] Run all affected novel state tests plus `git diff --check`.
- [x] Build the Stable Debug arm64 app with automatic signing.
- [x] Before installation, report the changed variables and expected invariants; then install and launch the exact built app on the connected iPhone.
- [x] Update `docs/PROJECT_STATE.md` with verified facts and any remaining real-provider gap.
