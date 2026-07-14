# Novel Navigation Performance 10× Implementation Plan

> **For agentic workers:** Execute each task as one measured, reversible loop. Preserve existing behavior unless a test below explicitly changes the contract.

**Goal:** Remove the visible hitch when entering a novel project and make long-session presentation projection at least 10× faster on a representative benchmark.

**Architecture:** Keep navigation native. Preload the selected project and branch before pushing, then publish the selection as one coherent state update so the destination does not rebuild its title and content during the transition. Replace repeated per-message scans in session presentation with one request-scoped index, and compute the resulting list only once per SwiftUI body evaluation.

**Tech Stack:** Swift, SwiftUI, XCTest, XcodeGen

---

### Task 1: Establish the baseline and behavior contract

**Files:**
- Modify: `iosApp/iosAppTests/NovelSessionReplayTests.swift`
- Modify: `iosApp/iosAppTests/NovelCreationViewModelTests.swift`
- Modify: `iosApp/iosAppTests/IOSNovelCreationWiringTests.swift`

- [x] Add a deterministic large-session projection benchmark and record its current timing.
- [x] Add a suspended-load test proving project selection never exposes a half-loaded project/branch pair.
- [x] Add a wiring contract proving project rows await selection before invoking navigation.
- [x] Run the focused tests before implementation and retain the failing/baseline evidence.

### Task 2: Make long-session projection linear after sorting

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionPresentation.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionView.swift`

- [x] Build one projection-local index for candidates, transactions, runs, checkpoints, states, events, proposals, and blockers.
- [x] Preserve first-match and ordering semantics while replacing repeated whole-array scans.
- [x] Thread the index through row/candidate/action projection without introducing persistent cache invalidation state.
- [x] Compute the projected list once per SwiftUI body evaluation and pass it down explicitly.
- [x] Re-run the same benchmark and require a measured improvement of at least 10×.

### Task 3: Publish project selection atomically before native navigation

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelCreationViewModel.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectListView.swift`

- [x] Load the project and its active branch into locals, then commit all selection fields together.
- [x] Make a project row await that coherent selection before calling the existing navigation closure.
- [x] Keep stale-request protection and failure behavior intact; do not add delay, animation, or fallback state.

### Task 4: Verify the complete path

**Files:**
- Modify: `docs/PROJECT_STATE.md`

- [x] Run focused projection, view-model, and wiring tests.
- [x] Run the affected novel regression suite and `git diff --check`.
- [x] Regenerate the Xcode project only if file membership changed, then build Stable Debug for iPhone.
- [x] Install and launch on the connected iPhone; record benchmark ratio and any remaining manual-only animation risk.

### Task 5: Close the device-only push/pop asymmetry

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectListView.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectWorkspaceView.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionView.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionViewModel.swift`

- [x] Remove the list-owned pending-open `@State` and `.task(id:)` pair so the source page is not invalidated again while the push begins.
- [x] Initialize the session binding from the already-complete project selection, before the destination's first frame, instead of replacing the empty transcript after appearance.
- [x] Collapse the two appearance binding tasks into one and stop dispatching redundant initial follow-state mutations during the transition.
- [x] Give the shared settings toolbar button the same explicit toolbar ID and component identity on both pages.
- [x] Run the complete related navigation, session, projection, and Chat scroll gates, then rebuild, install, and launch on the connected iPhone.
