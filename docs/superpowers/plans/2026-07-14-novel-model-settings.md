# Novel Model Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real novel settings entry and route creative writing and story-state synchronization through independently configurable models.

**Architecture:** Keep the existing project creative-model policy as the project override, add a backward-compatible optional synchronization-model policy, and resolve both through two persisted global defaults. The workspace gear edits the current project's overrides; Settings > Advanced Features edits global defaults. Generation/polish consume the creative role, while state delta/rebuild consume the synchronization role.

**Tech Stack:** Swift, SwiftUI, Observation, UserDefaults, XCTest, XcodeGen

---

### Task 1: Lock the two-model contract

**Files:**
- Modify: `iosApp/iosAppTests/NovelProjectConfigurationTests.swift`
- Modify: `iosApp/iosAppTests/NovelGenerationLifecycleTests.swift`
- Modify: `iosApp/iosAppTests/NovelFactTransactionLifecycleTests.swift`
- Modify: `iosApp/iosAppTests/IOSNovelCreationWiringTests.swift`
- Modify: `iosApp/iosAppTests/IOSSettingsWiringTests.swift`

- [x] Add a reducer test proving the synchronization policy persists independently and old project JSON decodes with no synchronization field.
- [x] Add lifecycle assertions proving generation resolves the creative policy and state rebuild resolves the synchronization policy.
- [x] Add wiring assertions for the workspace gear, project settings sheet, and Settings > Advanced Features route.
- [x] Run the focused tests and confirm they fail before production changes.

### Task 2: Persist defaults and project overrides

**Files:**
- Create: `iosApp/iosApp/NovelCreation/NovelCreationModelPreferences.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelDomainModels.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelActions.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectConfiguration.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelReducer.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelCreationViewModel.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelDocumentValidator.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectPackage.swift`

- [x] Add `NovelModelRole` with creative and state-sync roles.
- [x] Persist global role defaults in UserDefaults using stable provider/model IDs and `.global` as the follow-current-chat fallback.
- [x] Add an optional state-sync policy to V1 project JSON so documents written before this change still decode.
- [x] Extend the existing model-policy mutation with a purpose and validate both fixed policies.
- [x] Run reducer and document validation tests.

### Task 3: Route the two roles through runtime requests

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelCreation.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelCreationComposition.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelGenerationLifecycle.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelFactTransactionLifecycle.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelManualSyncProgress.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelPolishLifecycle.swift`

- [x] Inject a sendable role-default resolver into `DefaultNovelCreation`.
- [x] Resolve discussion/prose/quick-start/polish with the creative role.
- [x] Resolve state delta/manual rebuild with the state-sync role and keep durable progress guards tied to the effective policy.
- [x] Run generation and fact lifecycle tests.

### Task 4: Add both settings surfaces

**Files:**
- Create: `iosApp/iosApp/NovelCreation/NovelCreationSettingsView.swift`
- Modify: `iosApp/iosApp/AppShell.swift`
- Modify: `iosApp/iosApp/PlaceholderViews.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectWorkspaceView.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionSheets.swift`

- [x] Add a right-side gear to the novel workspace.
- [x] Add a project settings sheet with creative model, story-sync model, project name, branch management, and manuscript export.
- [x] Keep the title sheet limited to writing preferences and context injection.
- [x] Add Settings > Advanced Features > Novel Creation with two global model defaults and concise role guidance.
- [x] Reuse the existing model picker and native navigation/sheet behavior.

### Task 5: Verify and record state

**Files:**
- Modify: `docs/PROJECT_STATE.md`

- [x] Regenerate the Xcode project so new Swift files enter app and test targets.
- [x] Run focused settings, reducer, generation, fact, view-model, and UI wiring tests.
- [x] Run `git diff --check` and a Stable Debug arm64 build.
- [x] Inspect only this task's diff and record remaining true-device interaction gaps.
