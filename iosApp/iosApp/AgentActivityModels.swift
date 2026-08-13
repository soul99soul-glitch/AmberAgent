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
    /// 会话标题，展开态主标题用。锁屏/灵动岛是系统共享表面，只放用户自己
    /// 创建的标题，不放模型名或提示词。
    let conversationTitle: String?
}

struct AgentActivityPresentation: Codable, Hashable, Sendable {
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

enum AgentActivityKind: String, Codable, Hashable, Sendable {
    case research
    case response
    case imageGeneration
    case document
    case web
    case memory
    case command
    case workflow
}

enum AgentActivityPhase: String, Codable, Hashable, Sendable {
    case running
    case reconnecting
    case waitingForUser
    case stale
    case completed
    case failed
    case cancelled
}

public enum AgentActivityStage: String, Codable, Hashable, Sendable {
    case preparing
    case thinking
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

enum AgentActivityMetricUnit: String, Codable, Hashable, Sendable {
    case source
    case file
    case image
    case item
}

enum AgentActivityMetric: Codable, Hashable, Sendable {
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

enum AgentActivityAction: String, Codable, Hashable, Sendable {
    case openTask
    case openConfirmation
    case viewResult
}

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

    static var scheme: String {
        scheme(forBundleIdentifier: Bundle.main.bundleIdentifier)
    }

    static func scheme(forBundleIdentifier bundleIdentifier: String?) -> String {
        let bundleIdentifier = bundleIdentifier ?? ""
        return bundleIdentifier.contains(".experimental-gpl")
            ? "amber-experimental"
            : "amber"
    }

    static func makeURL(
        runId: String,
        conversationId: String,
        focus: Focus
    ) -> URL? {
        guard isValid(runId, maxLength: 128),
              isValid(conversationId, maxLength: 64) else { return nil }

        var components = URLComponents()
        components.scheme = scheme
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
              components.scheme == scheme,
              components.host == "activity" else { return nil }

        let runId = components.path.trimmingCharacters(
            in: CharacterSet(charactersIn: "/")
        )
        let queryItems = components.queryItems ?? []
        guard components.path == "/\(runId)",
              queryItems.count == 2,
              queryItems.filter({ $0.name == "conversation" }).count == 1,
              queryItems.filter({ $0.name == "focus" }).count == 1,
              let conversationId = queryItems.first(where: { $0.name == "conversation" })?.value,
              let focusValue = queryItems.first(where: { $0.name == "focus" })?.value,
              isValid(runId, maxLength: 128),
              isValid(conversationId, maxLength: 64),
              let focus = Focus(rawValue: focusValue) else { return nil }

        return Target(
            runId: runId,
            conversationId: conversationId,
            focus: focus
        )
    }

    private static func isValid(_ value: String, maxLength: Int) -> Bool {
        guard !value.isEmpty, value.count <= maxLength else { return false }
        let allowed = CharacterSet(
            charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        )
        return value.unicodeScalars.allSatisfy { allowed.contains($0) }
    }
}

extension AgentActivityPresentation {
    static let defaultRunning = AgentActivityPresentation(
        kind: .research,
        phase: .running,
        stage: .readingSources
    )

    static func generatingResponse(modelName _: String) -> AgentActivityPresentation {
        response(stage: .generating)
    }

    static func response(stage: AgentActivityStage) -> AgentActivityPresentation {
        AgentActivityPresentation(
            kind: .response,
            phase: .running,
            stage: stage
        )
    }

    static func runningTool(toolName: String) -> AgentActivityPresentation {
        if toolName.hasPrefix("wm_") {
            return AgentActivityPresentation(
                kind: .web,
                phase: .running,
                stage: .readingWeb
            )
        }

        switch toolName {
        case "search_web":
            return AgentActivityPresentation(
                kind: .research,
                phase: .running,
                stage: .searching
            )
        case "scrape_web":
            return AgentActivityPresentation(
                kind: .web,
                phase: .running,
                stage: .readingWeb
            )
        case "generate_image":
            return AgentActivityPresentation(
                kind: .imageGeneration,
                phase: .running,
                stage: .generatingImage
            )
        case "memory_tool":
            return AgentActivityPresentation(
                kind: .memory,
                phase: .running,
                stage: .updatingMemory
            )
        default:
            if toolName.hasPrefix("workspace_") ||
                toolName.contains("file") ||
                toolName.contains("workspace") {
                return AgentActivityPresentation(
                    kind: .document,
                    phase: .running,
                    stage: .readingDocument
                )
            } else {
                return AgentActivityPresentation(
                    kind: .workflow,
                    phase: .running,
                    stage: .runningTool
                )
            }
        }
    }

    static func waitingForUser(kind: AgentActivityKind = .command) -> AgentActivityPresentation {
        AgentActivityPresentation(
            kind: kind,
            phase: .waitingForUser,
            stage: .waitingForConfirmation,
            action: .openConfirmation
        )
    }

    static var readingSelectedFile: AgentActivityPresentation {
        AgentActivityPresentation(
            kind: .document,
            phase: .running,
            stage: .readingDocument
        )
    }

    static var selectedFileReadCompleted: AgentActivityPresentation {
        completed(toolTitle: "文档读取")
    }

    static var selectedFileReadFailed: AgentActivityPresentation {
        failed(toolTitle: "文档读取")
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
            metric: .validatedProgress(
                completed: completed,
                total: total,
                unit: unit
            )
        )
    }

    static func reconnecting(
        kind: AgentActivityKind = .workflow
    ) -> AgentActivityPresentation {
        AgentActivityPresentation(
            kind: kind,
            phase: .reconnecting,
            stage: .reconnecting
        )
    }

    func preservingKind(from previous: AgentActivityPresentation?) -> AgentActivityPresentation {
        guard phase == .completed || phase == .failed || phase == .cancelled,
              let previous else { return self }
        var presentation = self
        presentation.kind = previous.kind
        return presentation
    }

    var progressFraction: Double? {
        guard case let .progress(completed, total, _) = metric, total > 0 else {
            return nil
        }
        return Double(completed) / Double(total)
    }

    var percentValue: Int? {
        progressFraction.map { Int(($0 * 100).rounded()) }
    }

    var showsProgressRing: Bool {
        phase == .running && progressFraction != nil
    }

    func displayPhase(isStale: Bool) -> AgentActivityPhase {
        if isStale, phase == .running || phase == .reconnecting {
            return .stale
        }
        return phase
    }

    func displayStage(isStale: Bool) -> AgentActivityStage {
        displayPhase(isStale: isStale) == .stale ? .stale : stage
    }

    private static func kind(forPublicToolTitle title: String) -> AgentActivityKind {
        switch title {
        case "网页搜索":
            .research
        case "网页读取", "WebMount":
            .web
        case "图片生成":
            .imageGeneration
        case "记忆更新":
            .memory
        case "文档读取", "Workspace":
            .document
        case "终端命令":
            .command
        case "生成回复":
            .response
        default:
            .workflow
        }
    }
}

enum AgentActivityResponseStagePolicy {
    static let initialStage = AgentActivityStage.preparing

    static func updatedStage(
        hasReasoningDelta: Bool,
        hasTextDelta: Bool
    ) -> AgentActivityStage? {
        if hasReasoningDelta { return .thinking }
        if hasTextDelta { return .generating }
        return nil
    }

    static func nextPublishedStage(
        current: AgentActivityStage?,
        candidate: AgentActivityStage
    ) -> AgentActivityStage? {
        guard current != candidate else { return nil }
        if current == .generating, candidate == .thinking { return nil }
        return candidate
    }
}

enum AgentActivityElapsedTimePolicy {
    static func frozenEndDate(
        for phase: AgentActivityPhase,
        updatedAt: Date
    ) -> Date? {
        switch phase {
        case .completed, .failed, .cancelled:
            updatedAt
        case .running, .reconnecting, .waitingForUser, .stale:
            nil
        }
    }
}

enum AgentActivityOrbAnimationTiming {
    private static let engineCycle = 2 * Double.pi
    private static let widgetAnimationLimit: TimeInterval = 2

    static func duration(speed: Double) -> TimeInterval {
        min(widgetAnimationLimit, engineCycle / speed)
    }
}

enum AgentActivityCopy {
    static func text(_ key: String) -> String {
        NSLocalizedString(
            key,
            tableName: "AgentActivity",
            bundle: .main,
            value: key,
            comment: ""
        )
    }
}

extension AgentActivityKind {
    var title: String {
        AgentActivityCopy.text("agent.activity.kind.\(rawValue)")
    }

    var symbolName: String {
        switch self {
        case .research:
            "magnifyingglass"
        case .response:
            "text.bubble"
        case .imageGeneration:
            "photo.on.rectangle"
        case .document:
            "doc.text"
        case .web:
            "globe"
        case .memory:
            "brain.head.profile"
        case .command:
            "terminal"
        case .workflow:
            "sparkles"
        }
    }
}

extension AgentActivityStage {
    var title: String {
        AgentActivityCopy.text("agent.activity.stage.\(rawValue)")
    }

    var compactTitle: String {
        switch self {
        case .preparing, .thinking, .searching, .readingSources, .readingWeb,
             .generating, .generatingImage, .organizing, .readingDocument,
             .updatingMemory, .runningTool:
            AgentActivityCopy.text("agent.activity.compact.\(rawValue)")
        case .waitingForConfirmation:
            AgentActivityCopy.text("agent.activity.fact.waiting")
        case .reconnecting:
            AgentActivityCopy.text("agent.activity.fact.reconnecting")
        case .stale:
            AgentActivityCopy.text("agent.activity.fact.stale")
        case .completed:
            AgentActivityCopy.text("agent.activity.fact.completed")
        case .failed:
            AgentActivityCopy.text("agent.activity.fact.failed")
        case .cancelled:
            AgentActivityCopy.text("agent.activity.fact.cancelled")
        }
    }
}

extension AgentActivityAction {
    var title: String {
        AgentActivityCopy.text("agent.activity.action.\(rawValue)")
    }

    /// 整张 Live Activity 已通过 widgetURL 打开对话，普通运行态不再重复显示 CTA。
    var showsLockScreenLabel: Bool {
        switch self {
        case .openTask:
            false
        case .openConfirmation, .viewResult:
            true
        }
    }

    var deepLinkFocus: AgentActivityDeepLink.Focus {
        switch self {
        case .openTask:
            .task
        case .openConfirmation:
            .confirmation
        case .viewResult:
            .result
        }
    }
}

extension AgentActivityMetric {
    var shortText: String? {
        switch validated {
        case .none:
            nil
        case let .count(completed, unit):
            String(
                format: AgentActivityCopy.text(
                    "agent.activity.metric.\(unit.rawValue).count"
                ),
                completed
            )
        case let .progress(completed, total, _):
            "\(Int((Double(completed) / Double(total) * 100).rounded()))%"
        }
    }

    var detailText: String? {
        switch validated {
        case .none:
            nil
        case let .count(completed, unit):
            String(
                format: AgentActivityCopy.text(
                    "agent.activity.metric.\(unit.rawValue).count"
                ),
                completed
            )
        case let .progress(completed, total, unit):
            String(
                format: AgentActivityCopy.text(
                    "agent.activity.metric.\(unit.rawValue).progress"
                ),
                completed,
                total
            )
        }
    }
}

extension AgentActivityPresentation {
    func priorityFact(isStale: Bool) -> String? {
        switch displayPhase(isStale: isStale) {
        case .running:
            metric.shortText
        case .reconnecting:
            AgentActivityCopy.text("agent.activity.fact.reconnecting")
        case .waitingForUser:
            AgentActivityCopy.text("agent.activity.fact.waiting")
        case .stale:
            AgentActivityCopy.text("agent.activity.fact.stale")
        case .completed:
            AgentActivityCopy.text("agent.activity.fact.completed")
        case .failed:
            AgentActivityCopy.text("agent.activity.fact.failed")
        case .cancelled:
            AgentActivityCopy.text("agent.activity.fact.cancelled")
        }
    }

    func displaySymbolName(isStale: Bool) -> String {
        switch displayPhase(isStale: isStale) {
        case .running:
            kind.symbolName
        case .reconnecting:
            "wifi.exclamationmark"
        case .waitingForUser:
            "exclamationmark.circle.fill"
        case .stale:
            "clock.badge.exclamationmark"
        case .completed:
            "checkmark.circle.fill"
        case .failed:
            "xmark.circle.fill"
        case .cancelled:
            "stop.circle.fill"
        }
    }

    func accessibilitySummary(isStale: Bool) -> String {
        [kind.title, priorityFact(isStale: isStale) ?? displayStage(isStale: isStale).title]
            .joined(separator: ", ")
    }
}

extension AgentActivityAttributes {
    func destinationURL(for action: AgentActivityAction?) -> URL? {
        guard let conversationId else { return nil }
        return AgentActivityDeepLink.makeURL(
            runId: runId,
            conversationId: conversationId,
            focus: action?.deepLinkFocus ?? .task
        )
    }
}

enum AgentActivityLifecyclePolicy {
    static func shouldRestore(
        runId: String,
        ownedRunIds: Set<String>,
        activityState: ActivityState
    ) -> Bool {
        guard ownedRunIds.contains(runId) else { return false }
        return activityState == .active || activityState == .stale
    }

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
        case .waitingForUser:
            100
        case .reconnecting:
            80
        case .running:
            60
        // Terminal failures should not outrank ongoing work or pin a loud surface.
        case .failed, .stale:
            30
        case .completed:
            20
        case .cancelled:
            0
        }
    }

    static func lockScreenDismissalDelay(for phase: AgentActivityPhase) -> TimeInterval {
        switch phase {
        // Keep a brief terminal glance, then clear — long hangs feel like a stuck banner.
        case .failed, .stale:
            8
        case .completed:
            12
        case .cancelled:
            4
        case .running, .reconnecting, .waitingForUser:
            0
        }
    }
}
