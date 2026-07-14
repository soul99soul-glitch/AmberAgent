# Novel Toolbar Transition And Settings Root Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give both novel settings buttons the same root page, restore the standard settings push, and let iOS natively morph the project-list toolbar into the project toolbar.

**Architecture:** Replace the project/global settings route mode with one plain destination. Both gears open one global novel settings root using the default `NavigationStack` push; project-specific model overrides, naming, branches, and export move under a secondary project-management destination. Keep the list and workspace gear as equivalent native toolbar items, and separate the list-only import action with `ToolbarSpacer(.fixed)` so the system owns the Liquid Glass grouping and navigation morph.

**Tech Stack:** Swift, SwiftUI NavigationStack, ToolbarSpacer, XCTest, XcodeGen

---

### Task 1: Lock the native toolbar and identical-root contract

**Files:**
- Modify: `iosApp/iosAppTests/IOSNovelCreationWiringTests.swift`
- Modify: `iosApp/iosAppTests/IOSSettingsWiringTests.swift`

- [x] Assert list and workspace gears route to the same plain settings case.
- [x] Assert settings uses the default native push with no custom zoom/source namespace.
- [x] Assert the list import and settings actions are separate toolbar items divided by a fixed system toolbar spacer.
- [x] Assert `NovelCreationSettingsView` no longer has global/project scope switching and always exposes the same default-model and project-management rows.
- [x] Assert project overrides, rename, branch management, and export remain reachable in a secondary project settings detail.

### Task 2: Let the system own toolbar morphing and settings navigation

**Files:**
- Modify: `iosApp/iosApp/AppShell.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectListView.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelProjectWorkspaceView.swift`

- [x] Change `Route.novelCreationSettings` to one destination with no project scope or animation payload.
- [x] Remove the custom matched source and zoom transition from both gears and the settings destination.
- [x] Keep the project-list and workspace gear as equivalent enabled native toolbar items; place the list-only import action in its own Liquid Glass group.

### Task 3: Make one settings root and move project operations down one level

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelCreationSettingsView.swift`
- Create: `iosApp/iosApp/NovelCreation/NovelProjectSettingsDetailView.swift`

- [x] Keep only global creative and state-sync defaults on the shared root.
- [x] Add one project-management row that opens a project list.
- [x] Put project model overrides, rename, branches, and manuscript export in the selected project's detail page.
- [x] Reuse the existing model picker and project/branch editor views without adding another settings sheet.

### Task 4: Verify, build, and install

**Files:**
- Modify: `docs/PROJECT_STATE.md`

- [x] Regenerate the Xcode project for the new Swift file.
- [x] Run novel/settings wiring, project configuration, view-model, and package tests.
- [x] Run `git diff --check` and Stable Debug generic iOS build.
- [x] Install and launch the final package on the connected iPhone, then record the remaining manual transition check.
