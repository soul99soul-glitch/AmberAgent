# Amber iOS Dynamic Island Task Beacon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Amber’s inferred-progress Dynamic Island with a truthful, privacy-safe task beacon that communicates identity, one critical fact, freshness, and one next action across Minimal, Compact, Expanded, and Lock Screen presentations.

**Architecture:** Make the shared ActivityKit payload semantic and finite: task kind, phase, stage, optional real metric, optional safe action, and identifiers for deep linking. The widget derives every string, symbol, color, progress treatment, and layout from that contract; the app controller owns lifecycle, freshness, recovery, and routing. Indeterminate Agent work never exposes a percentage, progress ring, raw prompt, tool arguments, model name, URL, or file path.

**Tech Stack:** Swift 6.1, SwiftUI, WidgetKit, ActivityKit, Xcode previews, XcodeGen, XCTest, iOS 26.

---

## Status and authority

- Date: 2026-07-15
- Status: implementation complete; device-only and Xcode worker-blocked acceptance remains recorded in `docs/PROJECT_STATE.md`
- Supersedes: `docs/ios-port/IOS_DYNAMIC_ISLAND_FINAL_DESIGN_DEBUG_PLAN_2026-06-14.md`
- The superseded plan remains historical evidence, but its three-step track, inferred progress, glass treatment inside the system island, and “debugging only” freeze are no longer product requirements.
- This plan does not touch the current uncommitted chat scrolling, Council, novel creation, or vendored Markdown work.
- Git execution rule: the commit steps below are conditional. Run them only when the user explicitly authorizes commits for plan execution; otherwise skip them and leave the implementation unstaged.

## Product contract

The Dynamic Island is a task beacon, not a mini task dashboard.

The information hierarchy is fixed:

1. Task identity.
2. One highest-priority fact.
3. One safe next action when action is required.

Priority for the single fact:

```text
waiting for user
> stale / disconnected
> real measurable progress
> real completed count
> elapsed time
> current semantic stage
```

The four surface contracts are:

| Surface | Required content | Forbidden content |
| --- | --- | --- |
| Minimal | One task/state glyph, or a real progress ring | Text, fake ring, spinner, model identity |
| Compact leading | Stable task identity | Current tool log or changing sentence |
| Compact trailing | One priority fact | Multiple facts, buttons, two-line text |
| Expanded | Safe task title, current stage, one metric/freshness fact, at most one link | Three-step track, multiple buttons, raw prompt, nested cards |
| Lock Screen | Expanded context plus last update and result/confirmation link | Sensitive title, URL, file name, command, full response |

## Non-goals

- No direct approval of commands, MCP calls, messages, purchases, or other consequential actions from the Live Activity.
- No server push-to-start or ActivityKit APNs service in this plan. Amber currently has no server-owned Agent run lifecycle; that requires a separate backend protocol plan.
- No multi-activity task hub. The current product contract remains one foreground Agent run at a time.
- No redesign of `ChatActivityIslandView`; it is an in-app logical island with a separate state owner.
- No chat timeline, streaming, Markdown, Council, novel creation, provider, or KMP behavior changes.
- No new dependency.
- No custom Liquid Glass card inside the system-black Dynamic Island.

## File map

| File | Responsibility after the refactor |
| --- | --- |
| `iosApp/iosApp/AgentActivityModels.swift` | Shared, Codable semantic payload; copy keys; real metric semantics; deep-link value types; lifecycle policy |
| `iosApp/iosApp/AgentActivity.strings` | Base English copy used by the app and widget extension |
| `iosApp/iosApp/zh-Hans.lproj/AgentActivity.strings` | Simplified Chinese Activity copy |
| `iosApp/ActivityWidget/AmberAgentActivityWidget.swift` | Four ActivityKit presentations and preview matrix only |
| `iosApp/iosApp/AgentLiveActivityController.swift` | Single-activity lifecycle, stale date, relevance, recovery, update throttling, terminal policy |
| `iosApp/iosApp/ChatGenerationCoordinator.swift` | Pass run and conversation identity into ActivityKit; publish semantic factories only |
| `iosApp/iosApp/ChatViewModel.swift` | Bridge conversation identity to the controller |
| `iosApp/iosApp/AppShell.swift` | Validate Activity deep links, select the owning conversation, navigate to Chat |
| `iosApp/iosApp/Info.plist` | Register the narrow `amber://activity/<run-id>` URL scheme |
| `iosApp/project.yml` | Include the shared Activity localization resources in stable and Experimental widget targets |
| `iosApp/iosAppTests/AgentActivityPresentationTests.swift` | Semantic, privacy, metric, lifecycle policy, and factory contracts |
| `iosApp/iosAppTests/AgentActivityDeepLinkTests.swift` | URL construction and fail-closed parsing contracts |

## Acceptance matrix

| State | Minimal | Compact trailing | Expanded bottom | Lock Screen |
| --- | --- | --- | --- | --- |
| Indeterminate running | Task glyph | Elapsed time or short stage | Last update | Safe title + stage + last update |
| Measurable running | Real progress ring | Integer percent | Real progress + count | Real progress + count + last update |
| Waiting for user | Warning glyph | `待确认` | One `打开确认` link | Reason + one `打开确认` link |
| Reconnecting | Connection-warning glyph | `正在重连` | Last update | Reconnect state + last update |
| Stale | Stale glyph | `状态已过期` | Last update | Explicit stale warning + open-task link |
| Completed | Check glyph | `已完成` | `查看结果` | Safe summary + result link |
| Failed | Failure glyph | `遇到问题` | `打开任务` | Safe failure + last update + task link |
| Cancelled | Stop glyph | `已停止` | No action | Safe stopped summary |

### Task 1: Replace inferred progress with a semantic display contract

**Files:**
- Modify: `iosApp/iosAppTests/AgentActivityPresentationTests.swift:1-108`
- Modify: `iosApp/iosApp/AgentActivityModels.swift:1-491`

- [ ] **Step 1: Replace the existing presentation tests with failing semantic-contract tests**

The new tests must prove three things before UI work starts: indeterminate tasks have no progress, measurable tasks retain exact progress, and raw tool/user data never enters the payload.

```swift
import XCTest
@testable import iosApp

final class AgentActivityPresentationTests: XCTestCase {
    func testIndeterminateAgentWorkHasNoProgress() {
        let presentation = AgentActivityPresentation.generatingResponse(modelName: "private-model-name")

        XCTAssertEqual(presentation.kind, .response)
        XCTAssertEqual(presentation.phase, .running)
        XCTAssertEqual(presentation.stage, .generating)
        XCTAssertEqual(presentation.metric, .none)
        XCTAssertNil(presentation.progressFraction)
        XCTAssertFalse(presentation.showsProgressRing)
        XCTAssertFalse(String(describing: presentation).contains("private-model-name"))
    }

    func testMeasurableWorkUsesOnlyRealNumeratorAndDenominator() throws {
        let presentation = AgentActivityPresentation.measurablePreview(
            kind: .document,
            completed: 12,
            total: 30,
            unit: .item
        )

        XCTAssertEqual(presentation.metric, .progress(completed: 12, total: 30, unit: .item))
        XCTAssertEqual(try XCTUnwrap(presentation.progressFraction), 0.4, accuracy: 0.000_001)
        XCTAssertTrue(presentation.showsProgressRing)
        XCTAssertEqual(presentation.percentValue, 40)
    }

    func testInvalidProgressDegradesToNoMetric() {
        XCTAssertEqual(
            AgentActivityMetric.validatedProgress(completed: -1, total: 0, unit: .item),
            .none
        )
        XCTAssertEqual(
            AgentActivityMetric.validatedProgress(completed: 31, total: 30, unit: .item),
            .none
        )
    }

    func testToolFactoryMapsRawNamesToFinitePublicSemantics() {
        XCTAssertEqual(AgentActivityPresentation.runningTool(toolName: "search_web").kind, .research)
        XCTAssertEqual(AgentActivityPresentation.runningTool(toolName: "scrape_web").stage, .readingWeb)
        XCTAssertEqual(AgentActivityPresentation.runningTool(toolName: "generate_image").kind, .imageGeneration)

        let privateTool = AgentActivityPresentation.runningTool(
            toolName: "curl https://internal.example.com?token=secret"
        )
        XCTAssertEqual(privateTool.kind, .workflow)
        XCTAssertEqual(privateTool.stage, .runningTool)
        XCTAssertFalse(String(describing: privateTool).contains("internal.example.com"))
        XCTAssertFalse(String(describing: privateTool).contains("secret"))
    }

    func testStateFactoriesSelectOnlySafeActions() {
        XCTAssertEqual(AgentActivityPresentation.waitingForUser().action, .openConfirmation)
        XCTAssertEqual(AgentActivityPresentation.completed().action, .viewResult)
        XCTAssertEqual(AgentActivityPresentation.failed().action, .openTask)
        XCTAssertNil(AgentActivityPresentation.cancelled().action)
    }

    func testStaleDisplayOverridesRunningFactWithoutChangingPayload() {
        let presentation = AgentActivityPresentation.defaultRunning

        XCTAssertEqual(presentation.displayPhase(isStale: true), .stale)
        XCTAssertEqual(presentation.displayPhase(isStale: false), .running)
        XCTAssertEqual(presentation.phase, .running)
    }

    func testLifecyclePolicyMakesOnlyActiveWorkStale() {
        let now = Date(timeIntervalSince1970: 1_000)

        XCTAssertEqual(
            AgentActivityLifecyclePolicy.staleDate(for: .running, now: now),
            now.addingTimeInterval(180)
        )
        XCTAssertEqual(
            AgentActivityLifecyclePolicy.staleDate(for: .reconnecting, now: now),
            now.addingTimeInterval(60)
        )
        XCTAssertNil(AgentActivityLifecyclePolicy.staleDate(for: .waitingForUser, now: now))
        XCTAssertGreaterThan(
            AgentActivityLifecyclePolicy.relevanceScore(for: .waitingForUser),
            AgentActivityLifecyclePolicy.relevanceScore(for: .running)
        )
    }

    func testLegacyContentStateDecodesWithoutReassertingFreshness() throws {
        let legacyJSON = """
        {
          "presentation": {
            "statusText": "Amber 正在搜索资料",
            "toolTitle": "网页搜索",
            "phase": "running",
            "steps": [
              { "id": "search", "title": "搜索资料", "state": "current" }
            ]
          }
        }
        """

        let state = try JSONDecoder().decode(
            AgentActivityAttributes.ContentState.self,
            from: try XCTUnwrap(legacyJSON.data(using: .utf8))
        )

        XCTAssertEqual(state.presentation.kind, .research)
        XCTAssertEqual(state.presentation.phase, .stale)
        XCTAssertEqual(state.presentation.stage, .stale)
        XCTAssertEqual(state.presentation.metric, .none)
        XCTAssertEqual(state.updatedAt, .distantPast)
    }

    func testLegacyAttributesDecodeWithoutConversationIdentity() throws {
        let legacyJSON = """
        { "runId": "legacy-run", "startedAt": 0 }
        """

        let attributes = try JSONDecoder().decode(
            AgentActivityAttributes.self,
            from: try XCTUnwrap(legacyJSON.data(using: .utf8))
        )

        XCTAssertEqual(attributes.runId, "legacy-run")
        XCTAssertNil(attributes.conversationId)
    }

    func testNewPayloadDoesNotEncodeLegacyTextFields() throws {
        let data = try JSONEncoder().encode(AgentActivityPresentation.defaultRunning)
        let json = try XCTUnwrap(String(data: data, encoding: .utf8))

        XCTAssertFalse(json.contains("statusText"))
        XCTAssertFalse(json.contains("toolTitle"))
        XCTAssertFalse(json.contains("steps"))
    }
}
```

- [ ] **Step 2: Run the tests and confirm the old model fails the new contract**

Run:

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/AgentActivityPresentationTests test
```

Expected: compilation fails because `AgentActivityKind`, `AgentActivityMetric`, `progressFraction`, `displayPhase(isStale:)`, and `AgentActivityLifecyclePolicy` do not exist.

- [ ] **Step 3: Replace the raw string/step model with finite semantic types**

Use these stored fields and cases. Do not retain `steps`, `AgentActivityStep`, `AgentActivityStepState`, or the inferred `progress` property.

```swift
import ActivityKit
import Foundation

struct AgentActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var presentation: AgentActivityPresentation
        var updatedAt: Date
    }

    let runId: String
    let conversationId: String?
    let startedAt: Date
}

struct AgentActivityPresentation: Codable, Hashable {
    var kind: AgentActivityKind
    var phase: AgentActivityPhase
    var stage: AgentActivityStage
    var metric: AgentActivityMetric
    var action: AgentActivityAction?

    init(
        kind: AgentActivityKind,
        phase: AgentActivityPhase,
        stage: AgentActivityStage,
        metric: AgentActivityMetric = .none,
        action: AgentActivityAction? = .openTask
    ) {
        self.kind = kind
        self.phase = phase
        self.stage = stage
        self.metric = metric.validated
        self.action = action
    }
}

enum AgentActivityKind: String, Codable, Hashable {
    case research
    case response
    case imageGeneration
    case document
    case web
    case memory
    case command
    case workflow
}

enum AgentActivityPhase: String, Codable, Hashable {
    case running
    case reconnecting
    case waitingForUser
    case stale
    case completed
    case failed
    case cancelled
}

enum AgentActivityStage: String, Codable, Hashable {
    case preparing
    case searching
    case readingSources
    case readingWeb
    case generating
    case generatingImage
    case organizing
    case readingDocument
    case updatingMemory
    case runningTool
    case waitingForConfirmation
    case reconnecting
    case stale
    case completed
    case failed
    case cancelled
}

enum AgentActivityMetricUnit: String, Codable, Hashable {
    case source
    case file
    case image
    case item
}

enum AgentActivityMetric: Codable, Hashable {
    case none
    case count(completed: Int, unit: AgentActivityMetricUnit)
    case progress(completed: Int, total: Int, unit: AgentActivityMetricUnit)

    static func validatedProgress(
        completed: Int,
        total: Int,
        unit: AgentActivityMetricUnit
    ) -> AgentActivityMetric {
        guard total > 0, completed >= 0, completed <= total else { return .none }
        return .progress(completed: completed, total: total, unit: unit)
    }

    var validated: AgentActivityMetric {
        switch self {
        case .none:
            .none
        case let .count(completed, unit):
            completed >= 0 ? .count(completed: completed, unit: unit) : .none
        case let .progress(completed, total, unit):
            Self.validatedProgress(completed: completed, total: total, unit: unit)
        }
    }
}

enum AgentActivityAction: String, Codable, Hashable {
    case openTask
    case openConfirmation
    case viewResult
}
```

Add these derived contracts in the same file:

```swift
extension AgentActivityPresentation {
    var progressFraction: Double? {
        guard case let .progress(completed, total, _) = metric, total > 0 else { return nil }
        return Double(completed) / Double(total)
    }

    var percentValue: Int? {
        progressFraction.map { Int(($0 * 100).rounded()) }
    }

    var showsProgressRing: Bool {
        phase == .running && progressFraction != nil
    }

    func displayPhase(isStale: Bool) -> AgentActivityPhase {
        if isStale, phase == .running || phase == .reconnecting { return .stale }
        return phase
    }
}

enum AgentActivityLifecyclePolicy {
    static func staleDate(for phase: AgentActivityPhase, now: Date) -> Date? {
        switch phase {
        case .running:
            now.addingTimeInterval(180)
        case .reconnecting:
            now.addingTimeInterval(60)
        case .waitingForUser, .stale, .completed, .failed, .cancelled:
            nil
        }
    }

    static func relevanceScore(for phase: AgentActivityPhase) -> Double {
        switch phase {
        case .waitingForUser: 100
        case .failed, .stale: 90
        case .reconnecting: 80
        case .running: 60
        case .completed: 20
        case .cancelled: 0
        }
    }

    static func lockScreenDismissalDelay(for phase: AgentActivityPhase) -> TimeInterval {
        switch phase {
        case .failed, .stale: 60
        case .completed: 20
        case .cancelled: 6
        case .running, .reconnecting, .waitingForUser: 0
        }
    }
}
```

- [ ] **Step 4: Decode in-flight legacy Activities without restoring legacy UI semantics**

App updates can leave a system-owned Live Activity carrying the old `statusText/toolTitle/steps` payload. Keep decoding compatibility for that one boundary, but always encode only the new finite schema. A legacy active state has no trustworthy `updatedAt`, so convert it to `.stale` instead of presenting it as fresh work.

Add custom decoding to `AgentActivityPresentation`:

```swift
private extension AgentActivityPresentation {
    enum CodingKeys: String, CodingKey {
        case kind, phase, stage, metric, action
    }

    enum LegacyCodingKeys: String, CodingKey {
        case toolTitle, phase
    }
}

extension AgentActivityPresentation {
    init(from decoder: Decoder) throws {
        let current = try decoder.container(keyedBy: CodingKeys.self)
        if current.contains(.kind) {
            kind = try current.decode(AgentActivityKind.self, forKey: .kind)
            phase = try current.decode(AgentActivityPhase.self, forKey: .phase)
            stage = try current.decode(AgentActivityStage.self, forKey: .stage)
            metric = try current.decodeIfPresent(AgentActivityMetric.self, forKey: .metric)?.validated ?? .none
            action = try current.decodeIfPresent(AgentActivityAction.self, forKey: .action)
            return
        }

        let legacy = try decoder.container(keyedBy: LegacyCodingKeys.self)
        let toolTitle = try legacy.decodeIfPresent(String.self, forKey: .toolTitle) ?? ""
        let legacyPhase = try legacy.decodeIfPresent(AgentActivityPhase.self, forKey: .phase) ?? .stale

        kind = Self.kind(forPublicToolTitle: toolTitle)
        phase = legacyPhase
        stage = Self.legacyStage(for: legacyPhase, toolTitle: toolTitle)
        metric = .none
        action = Self.safeAction(for: legacyPhase)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(kind, forKey: .kind)
        try container.encode(phase, forKey: .phase)
        try container.encode(stage, forKey: .stage)
        try container.encode(metric.validated, forKey: .metric)
        try container.encodeIfPresent(action, forKey: .action)
    }

    private static func kind(forPublicToolTitle title: String) -> AgentActivityKind {
        switch title {
        case "网页搜索": .research
        case "网页读取", "WebMount": .web
        case "图片生成": .imageGeneration
        case "记忆更新": .memory
        case "文档读取", "Workspace": .document
        case "终端命令": .command
        case "生成回复": .response
        default: .workflow
        }
    }

    private static func legacyStage(
        for phase: AgentActivityPhase,
        toolTitle: String
    ) -> AgentActivityStage {
        switch phase {
        case .running:
            switch kind(forPublicToolTitle: toolTitle) {
            case .research: .searching
            case .response: .generating
            case .imageGeneration: .generatingImage
            case .document: .readingDocument
            case .web: .readingWeb
            case .memory: .updatingMemory
            case .command, .workflow: .runningTool
            }
        case .reconnecting: .reconnecting
        case .waitingForUser: .waitingForConfirmation
        case .stale: .stale
        case .completed: .completed
        case .failed: .failed
        case .cancelled: .cancelled
        }
    }

    private static func safeAction(for phase: AgentActivityPhase) -> AgentActivityAction? {
        switch phase {
        case .waitingForUser: .openConfirmation
        case .completed: .viewResult
        case .cancelled: nil
        case .running, .reconnecting, .stale, .failed: .openTask
        }
    }
}
```

The encoder writes only new semantic fields; do not add legacy keys to it.

Add compatibility decoding to `ContentState`:

```swift
extension AgentActivityAttributes.ContentState {
    private enum CodingKeys: String, CodingKey {
        case presentation, updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        var decodedPresentation = try container.decode(
            AgentActivityPresentation.self,
            forKey: .presentation
        )
        let decodedUpdatedAt = try container.decodeIfPresent(Date.self, forKey: .updatedAt)

        if decodedUpdatedAt == nil,
           decodedPresentation.phase == .running || decodedPresentation.phase == .reconnecting {
            decodedPresentation.phase = .stale
            decodedPresentation.stage = .stale
            decodedPresentation.action = .openTask
        }

        presentation = decodedPresentation
        updatedAt = decodedUpdatedAt ?? .distantPast
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(presentation, forKey: .presentation)
        try container.encode(updatedAt, forKey: .updatedAt)
    }
}
```

The optional `conversationId` attribute remains source-compatible with old Activities because synthesized decoding uses `decodeIfPresent`; do not make it non-optional in this migration.

- [ ] **Step 5: Restore the existing producer API using semantic factories**

Keep the factory names used by `ChatGenerationCoordinator` and `ChatViewModel`, but map raw arguments to finite enums and never store those raw arguments.

```swift
extension AgentActivityPresentation {
    static let defaultRunning = AgentActivityPresentation(
        kind: .research,
        phase: .running,
        stage: .readingSources
    )

    static func generatingResponse(modelName _: String) -> AgentActivityPresentation {
        AgentActivityPresentation(kind: .response, phase: .running, stage: .generating)
    }

    static func runningTool(toolName: String) -> AgentActivityPresentation {
        switch toolName {
        case "search_web":
            AgentActivityPresentation(kind: .research, phase: .running, stage: .searching)
        case "scrape_web":
            AgentActivityPresentation(kind: .web, phase: .running, stage: .readingWeb)
        case "generate_image":
            AgentActivityPresentation(kind: .imageGeneration, phase: .running, stage: .generatingImage)
        case "memory_tool":
            AgentActivityPresentation(kind: .memory, phase: .running, stage: .updatingMemory)
        default:
            if toolName.hasPrefix("workspace_") || toolName.contains("file") || toolName.contains("workspace") {
                AgentActivityPresentation(kind: .document, phase: .running, stage: .readingDocument)
            } else {
                AgentActivityPresentation(kind: .workflow, phase: .running, stage: .runningTool)
            }
        }
    }

    static func waitingForUser(toolTitle: String = "终端命令") -> AgentActivityPresentation {
        AgentActivityPresentation(
            kind: toolTitle == "文档读取" ? .document : .command,
            phase: .waitingForUser,
            stage: .waitingForConfirmation,
            action: .openConfirmation
        )
    }

    static var readingSelectedFile: AgentActivityPresentation {
        AgentActivityPresentation(kind: .document, phase: .running, stage: .readingDocument)
    }

    static var selectedFileReadCompleted: AgentActivityPresentation {
        completed(toolTitle: "文档读取")
    }

    static var selectedFileReadFailed: AgentActivityPresentation {
        failed(toolTitle: "文档读取")
    }

    static var selectedFileReadWaitingForUser: AgentActivityPresentation {
        waitingForUser(toolTitle: "文档读取")
    }

    static func completed(toolTitle: String = "生成回复") -> AgentActivityPresentation {
        AgentActivityPresentation(
            kind: kind(forPublicToolTitle: toolTitle),
            phase: .completed,
            stage: .completed,
            action: .viewResult
        )
    }

    static func failed(toolTitle: String = "生成回复") -> AgentActivityPresentation {
        AgentActivityPresentation(
            kind: kind(forPublicToolTitle: toolTitle),
            phase: .failed,
            stage: .failed,
            action: .openTask
        )
    }

    static func cancelled(toolTitle: String = "生成回复") -> AgentActivityPresentation {
        AgentActivityPresentation(
            kind: kind(forPublicToolTitle: toolTitle),
            phase: .cancelled,
            stage: .cancelled,
            action: nil
        )
    }

    static func measurablePreview(
        kind: AgentActivityKind,
        completed: Int,
        total: Int,
        unit: AgentActivityMetricUnit
    ) -> AgentActivityPresentation {
        AgentActivityPresentation(
            kind: kind,
            phase: .running,
            stage: .organizing,
            metric: .validatedProgress(completed: completed, total: total, unit: unit)
        )
    }

    static func reconnecting(kind: AgentActivityKind = .workflow) -> AgentActivityPresentation {
        AgentActivityPresentation(kind: kind, phase: .reconnecting, stage: .reconnecting)
    }

}
```

- [ ] **Step 6: Run the semantic tests**

Run the Task 1 test command again.

Expected: `AgentActivityPresentationTests` passes with zero failures.

- [ ] **Step 7: Commit the semantic contract**

```bash
git add iosApp/iosApp/AgentActivityModels.swift iosApp/iosAppTests/AgentActivityPresentationTests.swift
git commit -m "Make Live Activity state truthful by construction" \
  -m "Replace raw step logs and inferred percentages with finite task, phase, stage, metric, and action semantics." \
  -m "Constraint: Indeterminate Agent work cannot claim measurable progress." \
  -m "Rejected: Preserve the three-step track and reinterpret its weights | The weights remain fabricated." \
  -m "Confidence: high" \
  -m "Scope-risk: moderate" \
  -m "Tested: AgentActivityPresentationTests"
```

### Task 2: Add fail-closed task deep links and conversation ownership

**Files:**
- Modify: `iosApp/iosApp/AgentActivityModels.swift`
- Create: `iosApp/iosAppTests/AgentActivityDeepLinkTests.swift`
- Modify: `iosApp/iosApp/ChatGenerationCoordinator.swift:321,427,1520`
- Modify: `iosApp/iosApp/ChatViewModel.swift:400-402,1009,1690-1693`
- Modify: `iosApp/iosApp/Info.plist`
- Modify: `iosApp/iosApp/AppShell.swift:106-146`
- Modify test closures that construct `ChatGenerationBindings`, including `iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift:1432`

- [ ] **Step 1: Add failing URL construction and parser tests**

```swift
import XCTest
@testable import iosApp

final class AgentActivityDeepLinkTests: XCTestCase {
    func testRoundTripKeepsOnlyIdentifiersAndFocus() throws {
        let url = try XCTUnwrap(AgentActivityDeepLink.makeURL(
            runId: "run-123",
            conversationId: "01234567-89ab-cdef-0123-456789abcdef",
            focus: .confirmation
        ))

        XCTAssertEqual(
            AgentActivityDeepLink.parse(url),
            AgentActivityDeepLink.Target(
                runId: "run-123",
                conversationId: "01234567-89ab-cdef-0123-456789abcdef",
                focus: .confirmation
            )
        )
        XCTAssertFalse(url.absoluteString.contains("prompt"))
        XCTAssertFalse(url.absoluteString.contains("command"))
    }

    func testParserRejectsWrongSchemeHostFocusAndOversizedIdentifiers() {
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "https://activity/run")!))
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "amber://settings/run")!))
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "amber://activity/run?conversation=abc&focus=approve")!))
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "amber://activity/run?focus=task")!))
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "amber://activity/run?conversation=abc&focus=task&approve=true")!))
        XCTAssertNil(AgentActivityDeepLink.parse(URL(string: "amber://activity/run?conversation=abc&focus=task&focus=result")!))
        XCTAssertNil(AgentActivityDeepLink.makeURL(
            runId: String(repeating: "r", count: 129),
            conversationId: "01234567-89ab-cdef-0123-456789abcdef",
            focus: .task
        ))
    }
}
```

- [ ] **Step 2: Run the deep-link tests and confirm they fail**

Run:

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/AgentActivityDeepLinkTests test
```

Expected: compilation fails because `AgentActivityDeepLink` does not exist.

- [ ] **Step 3: Implement the narrow URL contract beside the shared Activity model**

```swift
enum AgentActivityDeepLink {
    enum Focus: String, Codable, Hashable {
        case task
        case confirmation
        case result
    }

    struct Target: Equatable {
        let runId: String
        let conversationId: String
        let focus: Focus
    }

    static func makeURL(
        runId: String,
        conversationId: String,
        focus: Focus
    ) -> URL? {
        guard isValid(runId, maxLength: 128),
              isValid(conversationId, maxLength: 64) else { return nil }

        var components = URLComponents()
        components.scheme = "amber"
        components.host = "activity"
        components.path = "/\(runId)"
        components.queryItems = [
            URLQueryItem(name: "conversation", value: conversationId),
            URLQueryItem(name: "focus", value: focus.rawValue)
        ]
        return components.url
    }

    static func parse(_ url: URL) -> Target? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme == "amber",
              components.host == "activity" else { return nil }

        let runId = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let queryItems = components.queryItems ?? []
        guard queryItems.count == 2,
              queryItems.filter({ $0.name == "conversation" }).count == 1,
              queryItems.filter({ $0.name == "focus" }).count == 1 else { return nil }

        let conversationId = queryItems.first { $0.name == "conversation" }?.value
        let focusRaw = queryItems.first { $0.name == "focus" }?.value

        guard isValid(runId, maxLength: 128),
              let conversationId,
              isValid(conversationId, maxLength: 64),
              let focusRaw,
              let focus = Focus(rawValue: focusRaw) else { return nil }
        return Target(runId: runId, conversationId: conversationId, focus: focus)
    }

    private static func isValid(_ value: String, maxLength: Int) -> Bool {
        guard !value.isEmpty, value.count <= maxLength else { return false }
        let allowed = CharacterSet(
            charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        )
        return value.unicodeScalars.allSatisfy { allowed.contains($0) }
    }
}

extension AgentActivityAction {
    var deepLinkFocus: AgentActivityDeepLink.Focus {
        switch self {
        case .openTask: .task
        case .openConfirmation: .confirmation
        case .viewResult: .result
        }
    }
}

extension AgentActivityAttributes {
    func destinationURL(for action: AgentActivityAction?) -> URL? {
        guard let conversationId else { return nil }
        AgentActivityDeepLink.makeURL(
            runId: runId,
            conversationId: conversationId,
            focus: action?.deepLinkFocus ?? .task
        )
    }
}
```

- [ ] **Step 4: Carry conversation identity through the start boundary**

Change `ChatGenerationBindings.startLiveActivity` to:

```swift
let startLiveActivity: (String, KotlinUuid?, AgentActivityPresentation) -> Void
```

Every call must pass the run-owned conversation, not the currently selected conversation at callback time:

```swift
bindings.startLiveActivity(
    runId,
    conversationId,
    .generatingResponse(modelName: params.model.modelId)
)
```

The user-initiated image path uses the same run-owned `conversationId`:

```swift
bindings.startLiveActivity(
    runId,
    conversationId,
    .runningTool(toolName: "generate_image")
)
```

For resumed approval:

```swift
bindings.startLiveActivity(
    pending.runId,
    pending.conversationId,
    .generatingResponse(modelName: pending.params.model.modelId)
)
```

Update the `ChatViewModel` bridge to:

```swift
startLiveActivity: { [weak self] runId, conversationId, presentation in
    self?.startLiveActivity(
        runId: runId,
        conversationId: conversationId,
        presentation: presentation
    )
}
```

```swift
private func startLiveActivity(
    runId: String,
    conversationId: KotlinUuid?,
    presentation: AgentActivityPresentation
) {
    guard liveActivityPreferenceEnabled else { return }
    liveActivityController.start(
        runId: runId,
        conversationId: conversationId?.toHexDashString(),
        presentation: presentation
    )
}
```

Selected-file activity starts with `currentConversationId`. Test closures that ignore Live Activity arguments become:

```swift
startLiveActivity: { _, _, _ in },
```

- [ ] **Step 5: Register and handle only the Activity URL scheme**

Add this exact entry to `iosApp/iosApp/Info.plist`:

```xml
<key>CFBundleURLTypes</key>
<array>
  <dict>
    <key>CFBundleTypeRole</key>
    <string>Viewer</string>
    <key>CFBundleURLName</key>
    <string>app.amber.ios.activity</string>
    <key>CFBundleURLSchemes</key>
    <array>
      <string>amber</string>
    </array>
  </dict>
</array>
```

Add cold-launch-safe pending state to `AppShell`:

```swift
@State private var pendingAgentActivityTarget: AgentActivityDeepLink.Target?
@State private var didBootstrapConversations = false
```

Attach the handler after the existing `.task` modifier:

```swift
.onOpenURL { url in
    enqueueAgentActivityURL(url)
}
```

Extend the existing bootstrap task so a URL received during cold launch is routed only after summaries are available:

```swift
await conversationStore.bootstrap()
didBootstrapConversations = true
sharedSettings.repairCurrentChatModelIfNeeded(settingsStore)
await openPendingAgentActivityIfReady()
```

Implement fail-closed routing inside `AppShell`:

```swift
private func enqueueAgentActivityURL(_ url: URL) {
    guard let target = AgentActivityDeepLink.parse(url) else { return }
    pendingAgentActivityTarget = target
    Task { await openPendingAgentActivityIfReady() }
}

private func openPendingAgentActivityIfReady() async {
    guard didBootstrapConversations,
          let target = pendingAgentActivityTarget else { return }
    pendingAgentActivityTarget = nil

    guard let summary = conversationStore.summaries.first(where: {
        $0.id.toHexDashString().caseInsensitiveCompare(target.conversationId) == .orderedSame
    }) else { return }

    guard chatViewModel.prepareForConversationChange(to: summary.id) else { return }
    if conversationStore.currentConversation?.id != summary.id {
        await conversationStore.selectConversation(id: summary.id)
    }

    rootRouter.path = [.chat]
}
```

The URL never executes or approves anything. It only selects a known local conversation and navigates to the existing Chat UI, where the authoritative pending-action card remains responsible for approval. A legacy Activity without `conversationId`, an unknown conversation, an extra query item, or a duplicate query item produces no URL action; it must never fall back to the currently selected chat.

- [ ] **Step 6: Run focused model, deep-link, and affected wiring tests**

Run:

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/AgentActivityPresentationTests \
  -only-testing:iosAppTests/AgentActivityDeepLinkTests \
  -only-testing:iosAppTests/ChatViewModelSelectedFileContextTests test
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit deep-link ownership**

```bash
git add iosApp/iosApp/AgentActivityModels.swift iosApp/iosAppTests/AgentActivityDeepLinkTests.swift iosApp/iosApp/ChatGenerationCoordinator.swift iosApp/iosApp/ChatViewModel.swift iosApp/iosApp/Info.plist iosApp/iosApp/AppShell.swift iosApp/iosAppTests
git commit -m "Return Live Activity taps to the owning task" \
  -m "Carry conversation identity across the run boundary and route validated Activity URLs to the existing authoritative Chat confirmation flow." \
  -m "Constraint: Live Activity links may navigate but must never approve an action." \
  -m "Rejected: Route every tap to the current chat | It can silently open the wrong conversation." \
  -m "Confidence: high" \
  -m "Scope-risk: moderate" \
  -m "Tested: Activity model, deep-link, and selected-file wiring tests"
```

### Task 3: Rebuild the four ActivityKit presentations around one fact and one action

**Files:**
- Modify: `iosApp/ActivityWidget/AmberAgentActivityWidget.swift:1-283`
- Modify: `iosApp/iosApp/AgentActivityModels.swift`

- [ ] **Step 1: Add display-copy and symbol derivation without storing user text**

Add finite key mapping. Localization resources arrive in Task 5; until then missing keys intentionally render as keys in development, making omissions obvious.

```swift
enum AgentActivityCopy {
    static func text(_ key: String) -> String {
        NSLocalizedString(key, tableName: "AgentActivity", bundle: .main, value: key, comment: "")
    }
}

extension AgentActivityKind {
    var title: String {
        AgentActivityCopy.text("agent.activity.kind.\(rawValue)")
    }

    var symbolName: String {
        switch self {
        case .research: "magnifyingglass"
        case .response: "text.bubble"
        case .imageGeneration: "photo.on.rectangle"
        case .document: "doc.text"
        case .web: "globe"
        case .memory: "brain.head.profile"
        case .command: "terminal"
        case .workflow: "sparkles"
        }
    }
}

extension AgentActivityStage {
    var title: String {
        AgentActivityCopy.text("agent.activity.stage.\(rawValue)")
    }
}

extension AgentActivityAction {
    var title: String {
        AgentActivityCopy.text("agent.activity.action.\(rawValue)")
    }
}

extension AgentActivityPresentation {
    func priorityFact(isStale: Bool) -> String? {
        switch displayPhase(isStale: isStale) {
        case .waitingForUser:
            AgentActivityCopy.text("agent.activity.fact.waiting")
        case .stale:
            AgentActivityCopy.text("agent.activity.fact.stale")
        case .reconnecting:
            AgentActivityCopy.text("agent.activity.fact.reconnecting")
        case .completed:
            AgentActivityCopy.text("agent.activity.fact.completed")
        case .failed:
            AgentActivityCopy.text("agent.activity.fact.failed")
        case .cancelled:
            AgentActivityCopy.text("agent.activity.fact.cancelled")
        case .running:
            metric.shortText
        }
    }

    func displaySymbolName(isStale: Bool) -> String {
        switch displayPhase(isStale: isStale) {
        case .running: kind.symbolName
        case .reconnecting: "wifi.exclamationmark"
        case .waitingForUser: "exclamationmark.circle.fill"
        case .stale: "clock.badge.exclamationmark"
        case .completed: "checkmark.circle.fill"
        case .failed: "xmark.circle.fill"
        case .cancelled: "stop.circle.fill"
        }
    }
}

extension AgentActivityMetric {
    var shortText: String? {
        switch validated {
        case .none:
            nil
        case let .count(completed, unit):
            String(format: AgentActivityCopy.text("agent.activity.metric.\(unit.rawValue).count"), completed)
        case let .progress(completed, total, _):
            "\(Int((Double(completed) / Double(total) * 100).rounded()))%"
        }
    }

    var detailText: String? {
        switch validated {
        case .none:
            nil
        case let .count(completed, unit):
            String(format: AgentActivityCopy.text("agent.activity.metric.\(unit.rawValue).count"), completed)
        case let .progress(completed, total, unit):
            String(
                format: AgentActivityCopy.text("agent.activity.metric.\(unit.rawValue).progress"),
                completed,
                total
            )
        }
    }
}
```

- [ ] **Step 2: Replace the expanded region with stable semantic slots**

The expanded layout must keep the same geometry when state changes. Leading is identity, trailing is one fact or elapsed time, center is title plus stage, bottom is real metric/freshness plus at most one link.

```swift
DynamicIsland {
    DynamicIslandExpandedRegion(.leading) {
        AgentActivityGlyph(
            presentation: context.state.presentation,
            isStale: context.isStale,
            size: 34
        )
    }
    DynamicIslandExpandedRegion(.trailing) {
        AgentActivityPriorityFact(
            presentation: context.state.presentation,
            startedAt: context.attributes.startedAt,
            isStale: context.isStale
        )
    }
    DynamicIslandExpandedRegion(.center) {
        VStack(alignment: .leading, spacing: 2) {
            Text(context.state.presentation.kind.title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(context.state.presentation.stage.title)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
    DynamicIslandExpandedRegion(.bottom) {
        AgentActivityExpandedFooter(
            attributes: context.attributes,
            state: context.state,
            isStale: context.isStale
        )
    }
} compactLeading: {
    AgentActivityGlyph(
        presentation: context.state.presentation,
        isStale: context.isStale,
        size: 20
    )
} compactTrailing: {
    AgentActivityPriorityFact(
        presentation: context.state.presentation,
        startedAt: context.attributes.startedAt,
        isStale: context.isStale
    )
} minimal: {
    AgentActivityGlyph(
        presentation: context.state.presentation,
        isStale: context.isStale,
        size: 17
    )
}
.widgetURL(context.attributes.destinationURL(for: context.state.presentation.action))
.keylineTint(.amberAccent)
```

- [ ] **Step 3: Implement fact, footer, and glyph components**

```swift
private struct AgentActivityPriorityFact: View {
    let presentation: AgentActivityPresentation
    let startedAt: Date
    let isStale: Bool

    var body: some View {
        Group {
            if let fact = presentation.priorityFact(isStale: isStale) {
                Text(fact)
            } else {
                Text(startedAt, style: .timer)
                    .monospacedDigit()
            }
        }
        .font(.caption2.weight(.semibold))
        .lineLimit(1)
        .minimumScaleFactor(0.72)
        .foregroundStyle(.white.opacity(0.9))
    }
}

private struct AgentActivityExpandedFooter: View {
    let attributes: AgentActivityAttributes
    let state: AgentActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let progress = state.presentation.progressFraction {
                ProgressView(value: progress)
                    .progressViewStyle(.linear)
                    .tint(state.presentation.displayPhase(isStale: isStale).widgetColor)
            }

            HStack(spacing: 10) {
                if let detail = state.presentation.metric.detailText {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else {
                    Text(state.updatedAt, style: .relative)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                if let action = state.presentation.action,
                   let url = attributes.destinationURL(for: action) {
                    Link(destination: url) {
                        Label(action.title, systemImage: "arrow.up.right")
                            .font(.caption.weight(.semibold))
                            .frame(minHeight: 44)
                    }
                    .accessibilityLabel(action.title)
                }
            }
        }
        .padding(.bottom, 4)
    }
}

private struct AgentActivityGlyph: View {
    let presentation: AgentActivityPresentation
    let isStale: Bool
    let size: CGFloat

    var body: some View {
        ZStack {
            if let progress = presentation.progressFraction,
               presentation.displayPhase(isStale: isStale) == .running {
                Circle()
                    .stroke(.white.opacity(0.16), lineWidth: max(1, size * 0.05))
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(
                        Color.amberAccent,
                        style: StrokeStyle(lineWidth: max(1.2, size * 0.07), lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
            }

            Image(systemName: presentation.displaySymbolName(isStale: isStale))
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(presentation.displayPhase(isStale: isStale).widgetColor)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
```

Update the widget-only semantic color mapping so every new phase is exhaustive:

```swift
private extension AgentActivityPhase {
    var widgetColor: Color {
        switch self {
        case .running:
            .amberAccent
        case .reconnecting, .waitingForUser:
            .orange
        case .stale:
            .yellow
        case .completed:
            .green
        case .failed, .cancelled:
            .red
        }
    }
}
```

There is no ring at all for indeterminate work. A static decorative circle is also forbidden because users read it as progress.

- [ ] **Step 4: Rebuild the Lock Screen presentation as a richer summary, not a scaled island**

```swift
private struct LockScreenAgentActivityView: View {
    let attributes: AgentActivityAttributes
    let state: AgentActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                AgentActivityGlyph(
                    presentation: state.presentation,
                    isStale: isStale,
                    size: 34
                )

                VStack(alignment: .leading, spacing: 2) {
                    Text(state.presentation.kind.title)
                        .font(.headline)
                    Text(isStale
                         ? AgentActivityCopy.text("agent.activity.fact.stale")
                         : state.presentation.stage.title)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                AgentActivityPriorityFact(
                    presentation: state.presentation,
                    startedAt: attributes.startedAt,
                    isStale: isStale
                )
            }

            if let progress = state.presentation.progressFraction {
                ProgressView(value: progress)
                    .progressViewStyle(.linear)
                    .tint(state.presentation.displayPhase(isStale: isStale).widgetColor)
            }

            HStack(spacing: 10) {
                Group {
                    if let detail = state.presentation.metric.detailText {
                        Text(detail)
                    } else {
                        Text(state.updatedAt, style: .relative)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

                Spacer(minLength: 8)

                if let action = state.presentation.action,
                   let url = attributes.destinationURL(for: action) {
                    Link(destination: url) {
                        Text(action.title)
                            .font(.caption.weight(.semibold))
                            .frame(minHeight: 44)
                    }
                    .accessibilityLabel(action.title)
                }
            }
        }
        .padding()
        .accessibilityElement(children: .combine)
    }
}
```

Pass `context.attributes`, `context.state`, and `context.isStale` from the `ActivityConfiguration` Lock Screen closure. Use `.activityBackgroundTint(.black)`; do not add glass materials, blur cards, gradients, shadows, or detached capsules.

- [ ] **Step 5: Remove every obsolete inferred-progress and step-track symbol**

Run:

```bash
rg -n "AgentActivityStep|StepPill|ExpandedStepTrack|ExpandedStatusLine|ElapsedActivityTime|currentStepTitle|presentation\.progress|showsProgressRing: true" \
  iosApp/iosApp/AgentActivityModels.swift \
  iosApp/ActivityWidget/AmberAgentActivityWidget.swift \
  iosApp/iosAppTests/AgentActivityPresentationTests.swift
```

Expected: no matches. `progressFraction` and the semantic computed `showsProgressRing` test are allowed; the exact obsolete property `presentation.progress` and old boolean call-site argument must be absent.

- [ ] **Step 6: Build both widget variants**

Run:

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -target AmberAgentActivityWidget \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -target AmberAgentActivityWidgetExperimentalGPL \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Expected: both targets build successfully with no extension-unsafe API error.

- [ ] **Step 7: Commit the four-surface UI**

```bash
git add iosApp/ActivityWidget/AmberAgentActivityWidget.swift iosApp/iosApp/AgentActivityModels.swift
git commit -m "Make the Dynamic Island a glanceable task beacon" \
  -m "Recompose each system surface around stable identity, one fact, freshness, and one safe link." \
  -m "Constraint: The system-black island is not a Liquid Glass container." \
  -m "Rejected: Scale the existing progress dashboard down | It repeats untrustworthy information across every surface." \
  -m "Confidence: high" \
  -m "Scope-risk: moderate" \
  -m "Tested: Stable and Experimental widget target builds"
```

### Task 4: Make freshness, recovery, and terminal lifecycle explicit

**Files:**
- Modify: `iosApp/iosApp/AgentLiveActivityController.swift:1-158`
- Modify: `iosApp/iosApp/AppShell.swift:128-145`
- Modify: `iosApp/iosAppTests/AgentActivityPresentationTests.swift`

- [ ] **Step 1: Lock terminal dismissal policy with a focused test**

Append:

```swift
func testTerminalDismissalKeepsFailuresLongerThanCompletions() {
    XCTAssertEqual(AgentActivityLifecyclePolicy.lockScreenDismissalDelay(for: .completed), 20)
    XCTAssertEqual(AgentActivityLifecyclePolicy.lockScreenDismissalDelay(for: .failed), 60)
    XCTAssertEqual(AgentActivityLifecyclePolicy.lockScreenDismissalDelay(for: .cancelled), 6)
}
```

Run the focused presentation tests. Expected: pass before controller integration, proving the controller will consume an already-locked policy.

- [ ] **Step 2: Centralize ActivityContent construction**

Add this controller helper and use it for request, update, and end:

```swift
private static func content(
    presentation: AgentActivityPresentation,
    now: Date
) -> ActivityContent<AgentActivityAttributes.ContentState> {
    ActivityContent(
        state: .init(presentation: presentation, updatedAt: now),
        staleDate: AgentActivityLifecyclePolicy.staleDate(for: presentation.phase, now: now),
        relevanceScore: AgentActivityLifecyclePolicy.relevanceScore(for: presentation.phase)
    )
}
```

`update` becomes:

```swift
let now = Date()
if !force,
   let lastUpdateAt,
   now.timeIntervalSince(lastUpdateAt) < minimumInterval,
   presentation.phase == lastPresentation?.phase,
   presentation.stage == lastPresentation?.stage,
   presentation.metric == lastPresentation?.metric {
    return
}

lastPresentation = presentation
lastUpdateAt = now
await activity.update(Self.content(presentation: presentation, now: now))
```

This throttle may suppress an identical update, but it must never suppress a phase, stage, or metric change.

- [ ] **Step 3: Start with run-owned identifiers and recover the current system Activity**

Change start/request signatures to accept `conversationId: String?`, and construct attributes exactly once:

```swift
func start(
    runId: String,
    conversationId: String?,
    presentation: AgentActivityPresentation
) {
    guard activitiesEnabled else { return }
    reconcileExistingActivities(
        for: runId,
        conversationId: conversationId
    )

    if activity?.attributes.runId == runId {
        currentRunId = runId
        Task {
            await update(runId: runId, presentation: presentation, force: true)
        }
        return
    }

    if let oldActivity = activity {
        activity = nil
        currentRunId = nil
        Task {
            await Self.end(
                activity: oldActivity,
                presentation: .cancelled(toolTitle: "工具执行"),
                dismissalDelay: 1
            )
        }
    }

    requestActivity(
        runId: runId,
        conversationId: conversationId,
        presentation: presentation
    )
}
```

Add the matching reconcile helper. Matching on `runId` is authoritative; `conversationId` is immutable routing metadata and is checked to avoid adopting an Activity with inconsistent attributes.

```swift
private func reconcileExistingActivities(
    for runId: String,
    conversationId: String?
) {
    let existing = Activity<AgentActivityAttributes>.activities
    activity = existing.first {
        $0.attributes.runId == runId && $0.attributes.conversationId == conversationId
    }
    currentRunId = activity?.attributes.runId
    lastPresentation = activity?.content.state.presentation
    lastUpdateAt = activity?.content.state.updatedAt

    for staleActivity in existing where staleActivity.id != activity?.id {
        Task {
            await Self.end(
                activity: staleActivity,
                presentation: .cancelled(toolTitle: "工具执行"),
                dismissalDelay: 1
            )
        }
    }
}
```

```swift
private func requestActivity(
    runId: String,
    conversationId: String?,
    presentation: AgentActivityPresentation
) {
    let now = Date()
    let attributes = AgentActivityAttributes(
        runId: runId,
        conversationId: conversationId,
        startedAt: now
    )
    do {
        activity = try Activity.request(
            attributes: attributes,
            content: Self.content(presentation: presentation, now: now),
            pushType: nil
        )
        currentRunId = runId
        lastPresentation = presentation
        lastUpdateAt = now
    } catch {
        print("[LiveActivity] Failed to start Agent activity: \(error)")
        activity = nil
        currentRunId = nil
        lastPresentation = nil
        lastUpdateAt = nil
    }
}
```

Add startup restoration:

```swift
func restoreExistingActivity() {
    let existing = Activity<AgentActivityAttributes>.activities
    guard let restored = existing.max(by: {
        AgentActivityLifecyclePolicy.relevanceScore(for: $0.content.state.presentation.phase) <
            AgentActivityLifecyclePolicy.relevanceScore(for: $1.content.state.presentation.phase)
    }) else {
        activity = nil
        currentRunId = nil
        lastPresentation = nil
        lastUpdateAt = nil
        return
    }

    activity = restored
    currentRunId = restored.attributes.runId
    lastPresentation = restored.content.state.presentation
    lastUpdateAt = restored.content.state.updatedAt

    for duplicate in existing where duplicate.id != restored.id {
        Task {
            await Self.end(
                activity: duplicate,
                presentation: .cancelled(toolTitle: "工具执行"),
                dismissalDelay: 1
            )
        }
    }
}
```

Call it in the existing `AppShell.task` before conversation bootstrap, preserving the cold-launch deep-link drain added in Task 2:

```swift
AgentLiveActivityController.shared.restoreExistingActivity()
await conversationStore.bootstrap()
didBootstrapConversations = true
sharedSettings.repairCurrentChatModelIfNeeded(settingsStore)
await openPendingAgentActivityIfReady()
```

- [ ] **Step 4: Use terminal state for Lock Screen retention without promising Dynamic Island retention**

Change `end` to accept an optional override and otherwise use the policy:

```swift
func end(
    runId: String,
    presentation: AgentActivityPresentation,
    dismissalDelay: TimeInterval? = nil
) async {
    guard let activity, currentRunId == runId, activity.attributes.runId == runId else { return }
    lastPresentation = presentation
    lastUpdateAt = Date()
    self.activity = nil
    currentRunId = nil

    await Self.end(
        activity: activity,
        presentation: presentation,
        dismissalDelay: dismissalDelay ?? AgentActivityLifecyclePolicy.lockScreenDismissalDelay(for: presentation.phase)
    )
}
```

The static end helper must call `Self.content(presentation:now:)` and `.after(Date().addingTimeInterval(dismissalDelay))`. Record in code comments that ending a Live Activity removes it from Dynamic Island immediately; the dismissal policy controls the ended Lock Screen presentation. Do not delay `activity.end` with an in-process timer.

- [ ] **Step 5: Run lifecycle and generation regression tests**

Run:

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/AgentActivityPresentationTests \
  -only-testing:iosAppTests/ChatViewModelSelectedFileContextTests \
  -only-testing:iosAppTests/ChatStreamReplayTests test
```

Expected: all selected tests pass. This gate verifies complete/error/cancel/background paths still reach their existing terminal calls.

- [ ] **Step 6: Commit lifecycle truthfulness**

```bash
git add iosApp/iosApp/AgentLiveActivityController.swift iosApp/iosApp/AppShell.swift iosApp/iosAppTests/AgentActivityPresentationTests.swift
git commit -m "Expose Live Activity freshness and recovery honestly" \
  -m "Derive stale dates, relevance, terminal retention, and startup recovery from one lifecycle policy." \
  -m "Constraint: Live Activity does not grant background execution time." \
  -m "Rejected: Keep the process alive with audio or location | It is unrelated to the task and creates review and energy risk." \
  -m "Confidence: high" \
  -m "Scope-risk: moderate" \
  -m "Tested: Activity policy, selected-file, and chat stream replay tests"
```

### Task 5: Localize, preview, and accessibility-test every state and surface

**Files:**
- Create: `iosApp/iosApp/AgentActivity.strings`
- Create: `iosApp/iosApp/zh-Hans.lproj/AgentActivity.strings`
- Modify: `iosApp/project.yml:182-210`
- Modify: `iosApp/ActivityWidget/AmberAgentActivityWidget.swift`
- Modify: `iosApp/iosApp/AgentActivityModels.swift`

- [ ] **Step 1: Add the complete base English localization table**

```text
"agent.activity.kind.research" = "Deep research";
"agent.activity.kind.response" = "Generate response";
"agent.activity.kind.imageGeneration" = "Image generation";
"agent.activity.kind.document" = "Document task";
"agent.activity.kind.web" = "Web task";
"agent.activity.kind.memory" = "Memory update";
"agent.activity.kind.command" = "Command task";
"agent.activity.kind.workflow" = "Agent task";
"agent.activity.stage.preparing" = "Preparing";
"agent.activity.stage.searching" = "Searching";
"agent.activity.stage.readingSources" = "Reading sources";
"agent.activity.stage.readingWeb" = "Reading the web";
"agent.activity.stage.generating" = "Generating";
"agent.activity.stage.generatingImage" = "Generating images";
"agent.activity.stage.organizing" = "Organizing results";
"agent.activity.stage.readingDocument" = "Reading document";
"agent.activity.stage.updatingMemory" = "Updating memory";
"agent.activity.stage.runningTool" = "Working";
"agent.activity.stage.waitingForConfirmation" = "Waiting for confirmation";
"agent.activity.stage.reconnecting" = "Reconnecting";
"agent.activity.stage.stale" = "Status expired";
"agent.activity.stage.completed" = "Completed";
"agent.activity.stage.failed" = "Needs attention";
"agent.activity.stage.cancelled" = "Stopped";
"agent.activity.action.openTask" = "Open task";
"agent.activity.action.openConfirmation" = "Open confirmation";
"agent.activity.action.viewResult" = "View result";
"agent.activity.fact.waiting" = "Confirm";
"agent.activity.fact.stale" = "Status expired";
"agent.activity.fact.reconnecting" = "Reconnecting";
"agent.activity.fact.completed" = "Done";
"agent.activity.fact.failed" = "Issue";
"agent.activity.fact.cancelled" = "Stopped";
"agent.activity.metric.source.count" = "%ld sources";
"agent.activity.metric.source.progress" = "%ld / %ld sources";
"agent.activity.metric.file.count" = "%ld files";
"agent.activity.metric.file.progress" = "%ld / %ld files";
"agent.activity.metric.image.count" = "%ld images";
"agent.activity.metric.image.progress" = "%ld / %ld images";
"agent.activity.metric.item.count" = "%ld items";
"agent.activity.metric.item.progress" = "%ld / %ld items";
```

- [ ] **Step 2: Add the complete Simplified Chinese table**

```text
"agent.activity.kind.research" = "深度研究";
"agent.activity.kind.response" = "生成回复";
"agent.activity.kind.imageGeneration" = "图片生成";
"agent.activity.kind.document" = "文档任务";
"agent.activity.kind.web" = "网页任务";
"agent.activity.kind.memory" = "记忆更新";
"agent.activity.kind.command" = "命令任务";
"agent.activity.kind.workflow" = "Agent 任务";
"agent.activity.stage.preparing" = "正在准备";
"agent.activity.stage.searching" = "正在检索";
"agent.activity.stage.readingSources" = "正在阅读来源";
"agent.activity.stage.readingWeb" = "正在阅读网页";
"agent.activity.stage.generating" = "正在生成";
"agent.activity.stage.generatingImage" = "正在生成图片";
"agent.activity.stage.organizing" = "正在整理结果";
"agent.activity.stage.readingDocument" = "正在读取文档";
"agent.activity.stage.updatingMemory" = "正在更新记忆";
"agent.activity.stage.runningTool" = "正在处理";
"agent.activity.stage.waitingForConfirmation" = "等待你的确认";
"agent.activity.stage.reconnecting" = "正在重新连接";
"agent.activity.stage.stale" = "状态已过期";
"agent.activity.stage.completed" = "已完成";
"agent.activity.stage.failed" = "需要处理";
"agent.activity.stage.cancelled" = "已停止";
"agent.activity.action.openTask" = "打开任务";
"agent.activity.action.openConfirmation" = "打开确认";
"agent.activity.action.viewResult" = "查看结果";
"agent.activity.fact.waiting" = "待确认";
"agent.activity.fact.stale" = "状态已过期";
"agent.activity.fact.reconnecting" = "正在重连";
"agent.activity.fact.completed" = "已完成";
"agent.activity.fact.failed" = "遇到问题";
"agent.activity.fact.cancelled" = "已停止";
"agent.activity.metric.source.count" = "已查看 %ld 个来源";
"agent.activity.metric.source.progress" = "%ld / %ld 个来源";
"agent.activity.metric.file.count" = "已处理 %ld 个文件";
"agent.activity.metric.file.progress" = "%ld / %ld 个文件";
"agent.activity.metric.image.count" = "已生成 %ld 张图片";
"agent.activity.metric.image.progress" = "%ld / %ld 张图片";
"agent.activity.metric.item.count" = "已处理 %ld 项";
"agent.activity.metric.item.progress" = "%ld / %ld 项";
```

- [ ] **Step 3: Add the localization files to both widget targets and regenerate the project**

Add this source directly after `iosApp/AgentActivityModels.swift` in both widget target source lists:

```yaml
      - path: iosApp/AgentActivity.strings
        buildPhase: resources
      - path: iosApp/zh-Hans.lproj/AgentActivity.strings
        buildPhase: resources
```

Run from `iosApp/`:

```bash
xcodegen generate
```

Expected: `iosApp/AmberAgent.xcodeproj` regenerates successfully; neither existing app source membership nor the Experimental widget dependency changes unexpectedly.

- [ ] **Step 4: Add a surface-by-state `#Preview` matrix**

Define stable fixtures at the bottom of `AmberAgentActivityWidget.swift`:

```swift
private let previewAttributes = AgentActivityAttributes(
    runId: "preview-run",
    conversationId: "01234567-89ab-cdef-0123-456789abcdef",
    startedAt: Date.now.addingTimeInterval(-125)
)
```

Create four Activity previews with all eight states written explicitly in each `contentStates` builder. The Compact form is listed first, followed by the other three complete declarations.

```swift
#Preview("Dynamic Island Compact", as: .dynamicIsland(.compact), using: previewAttributes) {
    AmberAgentActivityWidget()
} contentStates: {
    AgentActivityAttributes.ContentState(presentation: .defaultRunning, updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: .measurablePreview(kind: .document, completed: 12, total: 30, unit: .item),
        updatedAt: .now
    )
    AgentActivityAttributes.ContentState(presentation: .waitingForUser(), updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: .reconnecting(),
        updatedAt: .now.addingTimeInterval(-45)
    )
    AgentActivityAttributes.ContentState(
        presentation: AgentActivityPresentation(
            kind: .workflow,
            phase: .stale,
            stage: .stale,
            action: .openTask
        ),
        updatedAt: .now.addingTimeInterval(-300)
    )
    AgentActivityAttributes.ContentState(presentation: .completed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .failed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .cancelled(), updatedAt: .now)
}
```

Use these exact preview declarations:

```swift
#Preview("Lock Screen", as: .content, using: previewAttributes) {
    AmberAgentActivityWidget()
} contentStates: {
    AgentActivityAttributes.ContentState(presentation: .defaultRunning, updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: .measurablePreview(kind: .document, completed: 12, total: 30, unit: .item),
        updatedAt: .now
    )
    AgentActivityAttributes.ContentState(presentation: .waitingForUser(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .reconnecting(), updatedAt: .now.addingTimeInterval(-45))
    AgentActivityAttributes.ContentState(
        presentation: AgentActivityPresentation(
            kind: .workflow,
            phase: .stale,
            stage: .stale,
            action: .openTask
        ),
        updatedAt: .now.addingTimeInterval(-300)
    )
    AgentActivityAttributes.ContentState(presentation: .completed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .failed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .cancelled(), updatedAt: .now)
}

#Preview("Dynamic Island Expanded", as: .dynamicIsland(.expanded), using: previewAttributes) {
    AmberAgentActivityWidget()
} contentStates: {
    AgentActivityAttributes.ContentState(presentation: .defaultRunning, updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: .measurablePreview(kind: .document, completed: 12, total: 30, unit: .item),
        updatedAt: .now
    )
    AgentActivityAttributes.ContentState(presentation: .waitingForUser(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .reconnecting(), updatedAt: .now.addingTimeInterval(-45))
    AgentActivityAttributes.ContentState(
        presentation: AgentActivityPresentation(
            kind: .workflow,
            phase: .stale,
            stage: .stale,
            action: .openTask
        ),
        updatedAt: .now.addingTimeInterval(-300)
    )
    AgentActivityAttributes.ContentState(presentation: .completed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .failed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .cancelled(), updatedAt: .now)
}

#Preview("Dynamic Island Minimal", as: .dynamicIsland(.minimal), using: previewAttributes) {
    AmberAgentActivityWidget()
} contentStates: {
    AgentActivityAttributes.ContentState(presentation: .defaultRunning, updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: .measurablePreview(kind: .document, completed: 12, total: 30, unit: .item),
        updatedAt: .now
    )
    AgentActivityAttributes.ContentState(presentation: .waitingForUser(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .reconnecting(), updatedAt: .now.addingTimeInterval(-45))
    AgentActivityAttributes.ContentState(
        presentation: AgentActivityPresentation(
            kind: .workflow,
            phase: .stale,
            stage: .stale,
            action: .openTask
        ),
        updatedAt: .now.addingTimeInterval(-300)
    )
    AgentActivityAttributes.ContentState(presentation: .completed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .failed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .cancelled(), updatedAt: .now)
}
```

- [ ] **Step 5: Finish accessibility semantics**

Apply these rules:

- Decorative glyphs use `.accessibilityHidden(true)`.
- Compact and Minimal presentations expose one combined label: task kind plus priority fact or elapsed time.
- Expanded and Lock Screen expose task kind, stage, metric/freshness, then action in reading order.
- Every link has a localized label and a minimum `44 × 44 pt` hit target.
- Color is never the only distinction between waiting, stale, completed, failed, and cancelled.
- `Reduce Motion` does not change comprehension because there is no continuous custom animation.

Add this shared summary derivation and apply it to the compact/minimal containers:

```swift
extension AgentActivityPresentation {
    func accessibilitySummary(isStale: Bool) -> String {
        [kind.title, priorityFact(isStale: isStale) ?? stage.title]
            .joined(separator: ", ")
    }
}
```

```swift
.accessibilityElement(children: .ignore)
.accessibilityLabel(
    context.state.presentation.accessibilitySummary(isStale: context.isStale)
)
```

- [ ] **Step 6: Build and inspect the preview matrix**

Run both widget build commands from Task 3, then inspect all 32 combinations:

```text
4 surfaces × 8 states = 32 preview combinations
```

For each combination confirm:

- No line wrap in Compact or Minimal.
- No raw identifier is visible.
- No progress ring or bar for indeterminate work.
- Stale, waiting, and failed remain understandable without color.
- Expanded contains zero or one link.
- English and Simplified Chinese do not overlap the TrueDepth region.
- Accessibility size does not overlap the action; truncation is one line and graceful.

- [ ] **Step 7: Commit localization, previews, and accessibility**

```bash
git add iosApp/iosApp/AgentActivity.strings iosApp/iosApp/zh-Hans.lproj/AgentActivity.strings iosApp/project.yml iosApp/ActivityWidget/AmberAgentActivityWidget.swift iosApp/iosApp/AgentActivityModels.swift
git commit -m "Make every Live Activity state legible before shipping" \
  -m "Add localized copy, a complete surface-state preview matrix, and explicit accessibility semantics." \
  -m "Constraint: Widget extension resources are separate from the app bundle." \
  -m "Rejected: Validate only one Chinese expanded screenshot | It misses compact, stale, English, and accessibility failures." \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Tested: 32 preview combinations and both widget target builds"
```

### Task 6: Run simulator, state-flow, and physical-device acceptance

**Files:**
- Modify: `docs/PROJECT_STATE.md`
- Inspect only: all files changed in Tasks 1-5

- [ ] **Step 1: Run the focused regression gate**

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/AgentActivityPresentationTests \
  -only-testing:iosAppTests/AgentActivityDeepLinkTests \
  -only-testing:iosAppTests/ChatViewModelSelectedFileContextTests \
  -only-testing:iosAppTests/ChatStreamReplayTests test
```

Expected: zero failures. Record the `.xcresult` path.

- [ ] **Step 2: Build the stable app and Experimental app**

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' build
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosAppExperimentalGPL \
  -destination 'generic/platform=iOS Simulator' build
```

Expected: both schemes build successfully and embed their matching widget extension.

- [ ] **Step 3: Verify the complete production state chain on Simulator**

Exercise these paths through the real Chat UI and controller rather than a standalone mock:

1. Start a normal generation: indeterminate glyph, stage, elapsed time, no percent.
2. Enter a tool: task kind and stage change without changing layout slots.
3. Enter tool approval: compact reads `待确认`; expanded and Lock Screen show one `打开确认` link.
4. Tap the link from a different conversation: Amber selects the run-owned conversation and opens its existing confirmation card.
5. Deny or approve in the app: the Live Activity returns to running or reaches the real terminal path.
6. Complete: Dynamic Island ends immediately; ended Lock Screen summary remains for the policy delay.
7. Fail: Lock Screen remains longer than completion and offers `打开任务`.
8. Cancel: no success checkmark and no result action.
9. Let an active fixture pass `staleDate`: UI says the status expired instead of implying continued execution.
10. Terminate and relaunch the app while ActivityKit still reports an activity: controller restores one activity and ends duplicates.

Expected: every complete/error/cancel/background/relaunch path converges on one Activity owned by the original run and conversation.

- [ ] **Step 4: Verify privacy and URL abuse cases**

Attempt to feed these values through existing tool factories and external URL opening:

```text
https://internal.example.com?token=secret
/private/var/mobile/Containers/Data/secrets.txt
Authorization: Bearer secret
rm -rf user-content
amber://activity/run?focus=approve
amber://settings/run?focus=task
```

Expected:

- None of the raw strings appears on any Live Activity surface.
- Unsupported URL focus/host combinations are ignored.
- No URL can approve or execute an action.
- The valid link only navigates to an existing local conversation.

- [ ] **Step 5: Verify on a physical Dynamic Island device**

Use the connected iPhone Air or another Dynamic Island iPhone. Record device model, iOS build, and app build.

Verify:

- Compact legibility against light, dark, and busy wallpapers.
- Long-press expansion and collapse feel native and do not resemble a detached notification card.
- Minimal remains recognizable when another Live Activity is active.
- Always-On Display does not depend on custom animation.
- VoiceOver reads identity, fact, freshness, and action once, in order.
- Reduce Motion preserves all meaning.
- Extra-large accessibility text does not overlap or expose clipped sensitive content.
- Background handoff does not freeze a false running state indefinitely; stale state appears when updates stop.
- Tapping Lock Screen, Compact, Expanded, and the explicit link lands in the run-owned conversation.

Simulator screenshots are not sufficient evidence for these checks.

- [ ] **Step 6: Inspect the final diff and repository hygiene**

```bash
git diff --check
git diff --stat
git status --short --branch
rg -n "AgentActivityStep|StepPill|ExpandedStepTrack|0\.58|0\.82|0\.92" iosApp
```

Expected:

- `git diff --check` has no errors.
- The obsolete symbols and weights have no matches.
- Existing unrelated dirty files remain unchanged by this implementation.
- No new dependency, background audio mode, location keep-alive, or server push code appears.

- [ ] **Step 7: Update the current project state with evidence**

Add one concise `2026-07-15` slice to `docs/PROJECT_STATE.md` containing:

- The new task-beacon contract and the deleted fake-progress behavior.
- The exact focused test result and build result.
- Simulator evidence separated from physical-device evidence.
- Any unverified Always-On, VoiceOver, or cross-process deep-link behavior.
- A note that server-owned push-to-start remains a separate future project requiring a backend ActivityKit token protocol.

- [ ] **Step 8: Commit the verified closure**

Only perform this commit when all automated gates pass. If physical-device checks remain unavailable, record them under `Not-tested` rather than claiming completion.

```bash
git add docs/PROJECT_STATE.md
git commit -m "Record the verified Dynamic Island task-beacon contract" \
  -m "Capture automated, simulator, and device evidence separately so later changes do not restore fake progress or dashboard density." \
  -m "Constraint: Push-to-start requires a server-owned ActivityKit token lifecycle and is outside this local UI refactor." \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Tested: Focused XCTest gate, stable and Experimental builds, simulator state matrix" \
  -m "Not-tested: Physical-device Dynamic Island, Always-On Display, VoiceOver, and Reduce Motion acceptance"
```

If all physical-device checks were completed, omit the final `Not-tested` trailer and include the device model and iOS build in the `Tested` trailer instead.

## Final release gate

The refactor is complete only when all statements are true:

- No inferred percentage or decorative progress ring remains.
- The content payload contains only finite public semantics and identifiers.
- Minimal, Compact, Expanded, and Lock Screen are independently composed.
- Compact communicates no more than identity plus one fact.
- Expanded and Lock Screen expose at most one safe link.
- Waiting, stale, completed, failed, and cancelled do not look like active progress.
- Activity taps return to the run-owned conversation.
- `staleDate`, relevance, recovery, update throttling, and terminal dismissal come from one tested policy.
- The Activity never depends on background audio, location, or a high-frequency timer.
- Both widget targets and both app schemes build.
- The focused test gate passes.
- Device-only behavior is either verified on hardware or explicitly recorded as unverified.

## Deferred follow-up boundary

If Amber later runs Agent tasks on a server after the app is suspended or terminated, create a separate plan for:

- iOS 17.2+ push-to-start token registration.
- Per-Activity update token rotation and storage.
- APNs ActivityKit start/update/end payload ownership.
- Server authentication and run-to-device authorization.
- Token revocation, reinstall, logout, multiple-device, and notification-budget behavior.
- Public ContentState allowlisting on the server.

Do not add those responsibilities to `AgentLiveActivityController` until the server is the authoritative owner of a real remote run.
