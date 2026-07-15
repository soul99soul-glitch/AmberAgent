# iOS Dynamic Island Ownership Closure Implementation Plan

> **For Codex:** Execute this plan in small verified slices. Preserve unrelated worktree changes and do not commit unless the user asks.

**Goal:** Close the run-ownership, recovery, cancellation, deep-link, and selected-file gaps found by the Dynamic Island review without expanding the product surface.

**Architecture:** Keep `AgentLiveActivityController` as the single ActivityKit owner. Restore only runs with a durable background-job owner, make background handlers explicitly adopt their run before update/end, and make terminal paths reserve ownership exactly once. Deep links remain navigation-only and fail closed when either the conversation or run ownership is invalid.

**Tech Stack:** Swift 6.1, SwiftUI, ActivityKit, BackgroundTasks, XCTest, XcodeGen.

---

### Task 1: Make Activity restoration owner-driven

**Files:**
- Modify: `iosApp/iosApp/AgentLiveActivityController.swift`
- Modify: `iosApp/iosApp/IOSRunRecovery.swift`
- Modify: `iosApp/iosApp/AppShell.swift`
- Modify: `iosApp/iosApp/AmberAgentApp.swift`
- Test: `iosApp/iosAppTests/AgentActivityPresentationTests.swift`

**Contract:** Only active/stale ActivityKit instances whose `runId` is present in the durable background task map may survive cold-launch reconciliation. Foreground or waiting-for-confirmation runs abandoned by process death end as cancelled/interrupted; duplicate cleanup cannot race with later adoption.

**Implementation:** Add exact-run adoption, an owner-filtered restore policy, and an `endingActivityIDs` guard. Sequence recovery and Activity restoration once in `AppShell`, excluding durable background runs from interrupted-run recovery.

### Task 2: Close background adoption and cancellation

**Files:**
- Modify: `iosApp/iosApp/IOSChatBackgroundGenerationCoordinator.swift`
- Test: `iosApp/iosAppTests/IOSParityRedLightTests.swift`

**Contract:** A cold-launched background handler adopts its Activity before the first update. User cancellation reserves the terminal path, cancels the operation task, completes the system task as failed, records `interrupted`, and ends the Activity as cancelled. Missing payload/provider recovery also ends the mapped Activity instead of only deleting bookkeeping.

**Implementation:** Persist active run-state/system-task references only while the handler runs. Reuse the existing terminal reservation primitive; do not add retry loops or a second execution owner.

### Task 3: Fail closed on invalid deep-link ownership

**Files:**
- Modify: `iosApp/iosApp/IOSConversationStore.swift`
- Modify: `iosApp/iosApp/AppShell.swift`
- Modify: `iosApp/iosApp/AgentActivityModels.swift`
- Modify: `iosApp/iosApp/Info.plist`
- Modify: `iosApp/project.yml`
- Test: `iosApp/iosAppTests/AgentActivityDeepLinkTests.swift`
- Test: `iosApp/iosAppTests/IOSConversationStoreTests.swift`

**Contract:** A failed conversation load never opens the previous conversation. The target run must belong to the target conversation. Stable and Experimental builds use distinct URL schemes.

**Implementation:** Return success from conversation selection, validate run-to-conversation ownership through the existing agent runtime DAO, and derive the scheme from the target bundle identifier with per-target Info.plist substitution. Keep `focus` navigation-only; it never approves or executes work.

### Task 4: Keep selected-file work attached to its initiating conversation

**Files:**
- Modify: `iosApp/iosApp/ChatViewModel.swift`
- Modify: `iosApp/iosApp/AgentActivityModels.swift`
- Test: `iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`

**Contract:** Switching/newing a conversation cancels an in-flight selected-file read and invalidates its result before the switch. A file-picker-required result is represented as a failed/open-task state, not as a nonexistent confirmation action.

**Implementation:** Reuse the existing request token as the cancellation/ownership boundary; do not introduce a second attachment store.

### Task 5: Preserve semantic kind and verify the complete chain

**Files:**
- Modify: `iosApp/iosApp/AgentActivityModels.swift`
- Modify: `iosApp/iosApp/AgentLiveActivityController.swift`
- Modify: `iosApp/iosApp/ChatGenerationCoordinator.swift`
- Test: `iosApp/iosAppTests/AgentActivityPresentationTests.swift`

**Contract:** Approval states map from the typed prompt/tool family, and terminal updates preserve the last presentation kind for the same run.

**Verification:**

```bash
cd iosApp
xcodegen generate
xcodebuild -quiet -project AmberAgent.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -parallel-testing-enabled NO \
  -only-testing:iosAppTests/AgentActivityPresentationTests \
  -only-testing:iosAppTests/AgentActivityDeepLinkTests \
  -only-testing:iosAppTests/IOSConversationStoreTests \
  -only-testing:iosAppTests/ChatViewModelSelectedFileContextTests \
  -only-testing:iosAppTests/IOSParityRedLightTests test
xcodebuild -quiet -project AmberAgent.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' -arch arm64 CODE_SIGNING_ALLOWED=NO build
xcodebuild -quiet -project AmberAgent.xcodeproj -scheme iosAppExperimental -destination 'generic/platform=iOS Simulator' -arch arm64 CODE_SIGNING_ALLOWED=NO build
git diff --check
```

Finally, ask the existing lifecycle, deep-link/widget, and generation-chain reviewers to inspect the resulting diff for ownership gaps and broken call chains.
