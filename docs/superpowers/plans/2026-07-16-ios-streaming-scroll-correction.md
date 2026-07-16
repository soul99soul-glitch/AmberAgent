# iOS Streaming Scroll Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the visible streaming and bottom-follow regressions in standard Chat, Model Council, and Novel Creation while keeping the existing native SwiftUI scroll architecture and live Markdown behavior.

**Architecture:** Keep standard `ChatSwiftUIMessageList` as the production reference path, and stop routing users into the unfinished Native Timeline driver. Preserve each feature's current business owner; fix only demonstrated presentation ownership breaks: one measured-growth writer in Novel, one process-lifetime Council view model, and append-only Markdown updates without whole-paragraph remount fade or repeated full accessibility scans.

**Tech Stack:** Swift 6, SwiftUI `ScrollView` / `ScrollPosition`, Observation, UIKit TextKit 1, vendored `SwiftStreamingMarkdown`, XCTest, Xcode 26 iOS Simulator.

---

## Task 1: Converge standard Chat onto the proven production list

**Files:**
- Modify: `iosApp/iosApp/ChatView.swift`
- Modify: `iosApp/iosApp/DisplayFontSettingsView.swift`
- Modify: `iosApp/iosAppTests/IOSSettingsWiringTests.swift`
- Modify: `iosApp/iosAppTests/ChatMessageProjectionTests.swift`

- [x] Add/adjust routing assertions proving the production Chat screen cannot select `NativeChatTimelineView` from display settings.
- [x] Remove the visible “原生滚动容器（实验性）” toggle and its runtime routing inputs; retain the experimental implementation and focused tests as dormant code.
- [x] Keep `ChatSwiftUIMessageList` as the default when its existing feature flag is enabled and the collection list as the existing fallback.
- [x] Run `IOSSettingsWiringTests` and `ChatMessageProjectionTests`.

## Task 2: Preserve incremental Markdown animation and append performance

**Files:**
- Modify: `iosApp/vendor/SwiftStreamingMarkdown/Sources/MarkdownText/UI/ParagraphView.swift`
- Modify: `iosApp/vendor/SwiftStreamingMarkdown/Sources/MarkdownText/UI/UIKit/ParagraphUIView.swift`
- Modify: `iosApp/vendor/SwiftStreamingMarkdown/Tests/MarkdownTextTests/ParagraphViewTests.swift`
- Modify: `iosApp/iosApp/ChatCollectionMessageList.swift`
- Modify: `iosApp/iosApp/ChatMessageProjection.swift` or the smallest existing shared projection helper file
- Modify: the focused iOS projection/replay test that owns frozen Markdown snapshots

- [x] Add a pure policy test proving an already-mounted attachment-free TextKit 1 paragraph suppresses whole-paragraph remount fade while leaving its first mount and incremental appended-range fades enabled.
- [x] In the attachment-free append path, update the plain accessibility label directly instead of rescanning every attributed character for citations that cannot exist there.
- [x] Extract the existing “exactly one non-empty text part” snapshot rule and use it for Native Timeline freeze snapshots, avoiding `toText()` blank-line reconstruction.
- [x] Run the focused paragraph and Chat projection/replay tests.

## Task 3: Give Novel one measured-growth follow writer

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionPresentation.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionView.swift`
- Modify: `iosApp/iosAppTests/NovelSessionReplayTests.swift`
- Modify: `iosApp/iosAppTests/IOSNovelCreationWiringTests.swift`

- [x] Split “content snapshot changed” from “measured content height grew”: stream deltas update state only; positive measured growth owns the semantic bottom write.
- [x] Read the existing `IOSDisplayPreferenceKeys.followGeneration` setting and suppress only automatic live/terminal follow commands when it is disabled; initial anchoring and explicit bottom requests remain available.
- [x] Keep the existing history-browsing and near-bottom resume contract; do not add raw offsets, a new coordinator, or geometry compensation.
- [x] Run `NovelSessionReplayTests` and `IOSNovelCreationWiringTests`.

## Task 4: Keep one Council runtime owner across navigation

**Files:**
- Modify: `iosApp/iosApp/AppShell.swift`
- Modify: `iosApp/iosApp/CouncilChatRuntimeView.swift`
- Modify: `iosApp/iosAppTests/IOSCouncilRunnerMechanicsTests.swift`

- [x] Add a wiring contract proving production navigation has one Council destination and one `CouncilChatViewModel` instance owned by `AppShell`.
- [x] Construct the Council view model once from the existing stores, inject it into `.council`, and remove the duplicate dormant `.councilChat` route; do not introduce a singleton or cancel background discussion on disappear.
- [x] Preserve the current detach behavior for pending questions and background archive completion.
- [x] Run `IOSCouncilRunnerMechanicsTests`.

## Task 5: Regression, review, and state update

**Files:**
- Modify: `docs/PROJECT_STATE.md`
- Update checkboxes in: `docs/superpowers/plans/2026-07-16-ios-streaming-scroll-correction.md`

- [x] Run the mandatory `ChatStreamReplayTests`, plus `ChatSwiftUIStreamReplayTests`, Novel, Council, settings, projection, and paragraph focused suites.
- [x] Run a Stable Debug arm64 simulator build and `git diff --check`.
- [x] Have read-only subagents review state ownership, terminal paths, and production call-chain reachability; fix only concrete P0-P2 findings.
- [x] Record exact simulator evidence and leave 120Hz feel, keyboard/safe-area behavior, and real-provider long output as device-only checks unless actually verified.

## Verification result

- Focused Novel, Markdown, projection, and wiring suites: 116 passed, 0 failed (`/tmp/amber-stream-scroll-audit/Logs/Test/Test-iosApp-2026.07.16_14-37-28-+0800.xcresult`).
- Council ownership and routing suite: 26 passed, 0 failed (`/tmp/amber-stream-scroll-audit/Logs/Test/Test-iosApp-2026.07.16_14-39-28-+0800.xcresult`).
- Final standard Chat gate (`ChatStreamReplayTests`, `ChatSwiftUIStreamReplayTests`, `ChatViewportPolicyTests`): 73 passed, 1 expected skip, 0 failed (`/tmp/amber-stream-scroll-audit/Logs/Test/Test-iosApp-2026.07.16_14-52-00-+0800.xcresult`).
- Stable Debug generic iOS Simulator arm64 build and `git diff --check` passed. Three final read-only reviews reported no P0-P2 findings.
- The growing-table frame-gap canary produced one overloaded-simulator outlier while the host load average was about 24; the unchanged isolated canary and the unchanged full gate both passed after load returned to normal. No threshold or assertion was relaxed.
- Real-device 120 Hz feel, keyboard/safe-area transitions, and long real-provider output were not verified in this slice.
