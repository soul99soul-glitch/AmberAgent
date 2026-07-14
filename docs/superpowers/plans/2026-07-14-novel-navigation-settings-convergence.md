# Novel Navigation And Settings Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every novel-creation entry open the same project list, give that list import/create/settings actions, and make list/workspace settings open one shared settings page with global or project-scoped model choices.

**Architecture:** Keep `Route.novelCreation` as the single feature entry. Change `novelCreationSettings` into a route carrying an optional project ID: `nil` edits global defaults, while a project ID edits project overrides and exposes project management. Remove the project-only settings sheet and push the shared settings page through the existing app router.

**Tech Stack:** Swift, SwiftUI, Observation, XCTest, XcodeGen

---

### Task 1: Lock the corrected navigation contract

**Files:**
- Modify: `iosApp/iosAppTests/IOSNovelCreationWiringTests.swift`
- Modify: `iosApp/iosAppTests/IOSSettingsWiringTests.swift`

- [x] Assert the Settings advanced-feature row routes to `.novelCreation`, not directly to settings.
- [x] Assert the project list exposes import and settings in the top bar and a bottom floating create action.
- [x] Assert the project-list gear opens global settings and the workspace gear pushes project-scoped settings.
- [x] Assert the old `NovelProjectSettingsSheet` and `.projectSettings` sheet route are gone.

### Task 2: Make the project list the only feature landing page

**Files:**
- Modify: `iosApp/iosApp/AppShell.swift`
- Modify: `iosApp/iosApp/PlaceholderViews.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectListView.swift`

- [x] Route both Session and Settings advanced-feature entry to `.novelCreation`.
- [x] Keep import and settings as top-right utility actions.
- [x] Move create to a labeled bottom-trailing native glass action when projects exist; retain clear create/import actions in the empty state.
- [x] Pass a settings callback from `AppShell` so list navigation remains explicit and testable.

### Task 3: Converge global and project settings into one page

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelCreationSettingsView.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectWorkspaceView.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionSheets.swift`

- [x] Let `NovelCreationSettingsView` accept an optional project and load it when needed.
- [x] In global mode, edit the two persisted novel defaults with a current-chat fallback.
- [x] In project mode, edit the two project overrides with a novel-default fallback and include project name, branch management, and manuscript export.
- [x] Push the shared page from the workspace gear through `RouterPath`.
- [x] Delete the old project settings sheet plus its duplicate rename/branch/export routing from the workspace.

### Task 4: Verify and record

**Files:**
- Modify: `docs/PROJECT_STATE.md`

- [x] Regenerate the Xcode project if membership changes.
- [x] Run `IOSNovelCreationWiringTests`, the novel settings wiring test, model-policy tests, and relevant view-model tests.
- [x] Run `git diff --check` and the Stable Debug generic iOS build.
- [x] Update current project state and report the remaining true-device interaction gap.
