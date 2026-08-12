import Foundation
import Observation
@preconcurrency import Shared

struct IOSCouncilSeatDescriptor: Equatable, Identifiable {
    let id: String
    let name: String
    let role: String
    let modelLabel: String
}

enum IOSCouncilReasoningPreset: String, Codable, CaseIterable, Identifiable {
    case off
    case auto
    case low
    case medium
    case high
    case xhigh
    case max

    var id: String { rawValue }

    var title: String {
        switch self {
        case .off: "关闭"
        case .auto: "Auto"
        case .low: "Low"
        case .medium: "Medium"
        case .high: "High"
        case .xhigh: "X High"
        case .max: "Max"
        }
    }

    var reasoningLevel: ReasoningLevel {
        switch self {
        case .off: .off
        case .auto: .auto_
        case .low: .low
        case .medium: .medium
        case .high: .high
        case .xhigh: .xhigh
        case .max: .max
        }
    }
}

struct IOSCouncilRoomSeatConfig: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var rolePrompt: String
    var modelId: String
    var reasoning: IOSCouncilReasoningPreset
    var prompt: String
    var isDefault: Bool

    init(
        id: String = UUID().uuidString,
        name: String,
        rolePrompt: String,
        modelId: String,
        reasoning: IOSCouncilReasoningPreset = .off,
        prompt: String = "",
        isDefault: Bool = true
    ) {
        self.id = id
        self.name = name
        self.rolePrompt = rolePrompt
        self.modelId = modelId
        self.reasoning = reasoning
        self.prompt = prompt
        self.isDefault = isDefault
    }

    func normalized(currentModelId: String) -> IOSCouncilRoomSeatConfig {
        var copy = self
        copy.name = copy.name.trimmedOr("未命名席位")
        copy.rolePrompt = copy.rolePrompt.trimmedOr(copy.name)
        copy.modelId = copy.modelId.trimmedOr(currentModelId)
        copy.prompt = copy.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        return copy
    }
}

/// 预制席位角色,供「添加席位」面板选择(Android lens 预设 + 几个常用扩展)。
/// modelId 留空 → 运行时由 resolveSeatModel 落到主持人的工作模型。
struct IOSCouncilSeatPreset: Identifiable, Equatable {
    let id: String
    let name: String
    let rolePrompt: String
    let reasoning: IOSCouncilReasoningPreset

    static let all: [IOSCouncilSeatPreset] = [
        .init(id: "engineering", name: "工程", rolePrompt: "从工程可行性、实现复杂度、系统边界和维护成本审视议题。", reasoning: .medium),
        .init(id: "product", name: "产品", rolePrompt: "从用户价值、需求优先级、产品体验和上线取舍审视议题。", reasoning: .medium),
        .init(id: "marketing", name: "营销", rolePrompt: "从市场定位、增长渠道、传播路径和获客成本审视议题。", reasoning: .medium),
        .init(id: "pr", name: "公关", rolePrompt: "从品牌形象、舆论风险、对外口径和危机应对审视议题。", reasoning: .medium),
        .init(id: "design", name: "设计", rolePrompt: "从用户体验、交互流程、可用性和情感化设计审视议题。", reasoning: .medium),
        .init(id: "risk", name: "风险", rolePrompt: "从隐私、安全、成本、失败模式和误用风险审视议题。", reasoning: .high),
        .init(id: "opponent", name: "反方", rolePrompt: "主动提出反例、盲区和反对意见，压测方案是否成立。", reasoning: .high),
        .init(id: "data", name: "数据", rolePrompt: "从数据指标、度量口径、实验设计和因果推断审视议题。", reasoning: .medium),
        .init(id: "legal", name: "法务", rolePrompt: "从合规、法律风险、知识产权和监管要求审视议题。", reasoning: .high),
        .init(id: "finance", name: "财务", rolePrompt: "从成本结构、投入产出、现金流和商业模式审视议题。", reasoning: .medium),
    ]

    func asSeat() -> IOSCouncilRoomSeatConfig {
        IOSCouncilRoomSeatConfig(
            id: id, name: name, rolePrompt: rolePrompt,
            modelId: "", reasoning: reasoning, prompt: "", isDefault: true
        )
    }
}

struct IOSCouncilHostConfig: Codable, Equatable {
    var modelId: String
    var reasoning: IOSCouncilReasoningPreset
    var prompt: String

    func normalized(currentModelId: String) -> IOSCouncilHostConfig {
        IOSCouncilHostConfig(
            modelId: modelId.trimmedOr(currentModelId),
            reasoning: reasoning,
            prompt: prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }
}

struct IOSCouncilRoomLimits: Codable, Equatable {
    var maxSeats: Int
    var defaultRounds: Int
    var seatTimeoutSeconds: Int
    var outputBudgetCharacters: Int

    func normalized() -> IOSCouncilRoomLimits {
        IOSCouncilRoomLimits(
            maxSeats: min(max(maxSeats, 2), 8),
            // 总轮次限制 1-5 轮（所有成员发言一遍 = 一轮），避免无限辩论。
            // 下限放宽到 1：freeChat/debate 都允许单轮快速议会（parity 修复）。
            defaultRounds: min(max(defaultRounds, 1), 5),
            seatTimeoutSeconds: min(max(seatTimeoutSeconds, 15), 180),
            outputBudgetCharacters: min(max(outputBudgetCharacters, 2_000), 40_000)
        )
    }
}

struct IOSCouncilRoomSettings: Codable, Equatable {
    var host: IOSCouncilHostConfig
    var seats: [IOSCouncilRoomSeatConfig]
    var limits: IOSCouncilRoomLimits
    var legacySeatsImported: Bool

    static func defaults(currentModelId: String = "gpt-4o") -> IOSCouncilRoomSettings {
        IOSCouncilRoomSettings(
            host: IOSCouncilHostConfig(
                modelId: currentModelId,
                reasoning: .off,
                prompt: "你是 AmberAgent 模型议会主持人。先调研和完善议题，再组织席位顺序发言，最后给出清晰结论。"
            ),
            seats: [
                IOSCouncilRoomSeatConfig(
                    id: "engineering",
                    name: "工程",
                    rolePrompt: "从工程可行性、实现复杂度、系统边界和维护成本审视议题。",
                    modelId: currentModelId,
                    reasoning: .medium,
                    prompt: "",
                    isDefault: true
                ),
                IOSCouncilRoomSeatConfig(
                    id: "product",
                    name: "产品",
                    rolePrompt: "从用户价值、需求优先级、产品体验和上线取舍审视议题。",
                    modelId: currentModelId,
                    reasoning: .medium,
                    prompt: "",
                    isDefault: true
                ),
                IOSCouncilRoomSeatConfig(
                    id: "risk",
                    name: "风险",
                    rolePrompt: "从隐私、安全、成本、失败模式和误用风险审视议题。",
                    modelId: currentModelId,
                    reasoning: .high,
                    prompt: "",
                    isDefault: true
                ),
                IOSCouncilRoomSeatConfig(
                    id: "opponent",
                    name: "反方",
                    rolePrompt: "主动提出反例、盲区和反对意见，压测方案是否成立。",
                    modelId: currentModelId,
                    reasoning: .high,
                    prompt: "",
                    isDefault: false
                )
            ],
            limits: IOSCouncilRoomLimits(
                maxSeats: 4,
                defaultRounds: 2,
                seatTimeoutSeconds: 60,
                outputBudgetCharacters: 12_000
            ),
            legacySeatsImported: false
        )
    }

    func normalized(currentModelId: String) -> IOSCouncilRoomSettings {
        let normalizedLimits = limits.normalized()
        var seen = Set<String>()
        let normalizedSeats = seats.compactMap { seat -> IOSCouncilRoomSeatConfig? in
            let normalized = seat.normalized(currentModelId: currentModelId)
            guard !normalized.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            guard seen.insert(normalized.id).inserted else { return nil }
            return normalized
        }
        return IOSCouncilRoomSettings(
            host: host.normalized(currentModelId: currentModelId),
            seats: normalizedSeats,
            limits: normalizedLimits,
            legacySeatsImported: legacySeatsImported
        )
    }

    func defaultSeats(currentModelId: String) -> [IOSCouncilRoomSeatConfig] {
        let normalized = normalized(currentModelId: currentModelId)
        let defaults = normalized.seats.filter(\.isDefault)
        let selected = defaults.isEmpty ? normalized.seats : defaults
        return Array(selected.prefix(normalized.limits.maxSeats))
    }
}

@MainActor
@Observable
final class IOSCouncilRoomSettingsStore {
    static let shared = IOSCouncilRoomSettingsStore()

    var settings: IOSCouncilRoomSettings {
        didSet { persist() }
    }

    /// 主持人是否可按议题自由动态生成席位。开:自由组建;关:只能用下方已添加的席位。
    /// 单独存一个 UserDefaults 键(不塞进 Codable settings,避免给老数据加字段导致解码失败重置)。
    var dynamicSeatGeneration: Bool {
        didSet { userDefaults.set(dynamicSeatGeneration, forKey: Self.dynamicSeatKey) }
    }

    private static let dynamicSeatKey = "app.amber.ios.councilDynamicSeatGeneration.v1"

    /// 席位发言前是否联网查证一轮。独立 UserDefaults 键,默认关(用户显式开启才联网,
    /// 避免无谓的网络/成本);老数据无此键时按关起步。
    var seatWebSearch: Bool {
        didSet { userDefaults.set(seatWebSearch, forKey: Self.seatWebSearchKey) }
    }

    private static let seatWebSearchKey = "app.amber.ios.councilSeatWebSearch.v1"

    @ObservationIgnored private let userDefaults: UserDefaults
    @ObservationIgnored private let storageKey: String
    @ObservationIgnored private let encoder = JSONEncoder()
    @ObservationIgnored private let decoder = JSONDecoder()

    init(
        userDefaults: UserDefaults = .standard,
        storageKey: String = "app.amber.ios.councilRoomSettings.v1",
        currentModelId: String = "gpt-4o"
    ) {
        self.userDefaults = userDefaults
        self.storageKey = storageKey
        if let data = userDefaults.data(forKey: storageKey),
           let decoded = try? decoder.decode(IOSCouncilRoomSettings.self, from: data) {
            self.settings = decoded.normalized(currentModelId: currentModelId)
        } else {
            self.settings = IOSCouncilRoomSettings.defaults(currentModelId: currentModelId)
        }
        // 默认开启动态生成(老用户/首次进入都按"自由组建"起步)。
        self.dynamicSeatGeneration = (userDefaults.object(forKey: Self.dynamicSeatKey) as? Bool) ?? true
        // 席位联网查证默认关:联网更慢更耗,需用户在设置里显式开启。
        self.seatWebSearch = (userDefaults.object(forKey: Self.seatWebSearchKey) as? Bool) ?? false
    }

    func bootstrapLegacySeatsIfNeeded(_ legacySeats: [[String: String]], currentModelId: String) {
        guard !settings.legacySeatsImported else { return }
        var merged = settings.normalized(currentModelId: currentModelId)
        let existing = Set(merged.seats.map { $0.name.lowercased() })
        for seat in legacySeats {
            guard let name = seat["name"]?.trimmedNilIfBlank else { continue }
            guard !existing.contains(name.lowercased()) else { continue }
            merged.seats.append(IOSCouncilRoomSeatConfig(
                name: name,
                rolePrompt: seat["role"]?.trimmedNilIfBlank ?? name,
                modelId: seat["modelId"]?.trimmedNilIfBlank ?? currentModelId,
                reasoning: .off,
                prompt: "",
                isDefault: true
            ))
        }
        merged.legacySeatsImported = true
        settings = merged
    }

    func updateHost(modelId: String? = nil, reasoning: IOSCouncilReasoningPreset? = nil, prompt: String? = nil) {
        var copy = settings
        if let modelId { copy.host.modelId = modelId }
        if let reasoning { copy.host.reasoning = reasoning }
        if let prompt { copy.host.prompt = prompt }
        settings = copy
    }

    func updateLimits(maxSeats: Int? = nil, defaultRounds: Int? = nil, seatTimeoutSeconds: Int? = nil, outputBudgetCharacters: Int? = nil) {
        var copy = settings
        if let maxSeats { copy.limits.maxSeats = maxSeats }
        if let defaultRounds { copy.limits.defaultRounds = defaultRounds }
        if let seatTimeoutSeconds { copy.limits.seatTimeoutSeconds = seatTimeoutSeconds }
        if let outputBudgetCharacters { copy.limits.outputBudgetCharacters = outputBudgetCharacters }
        settings = copy.normalized(currentModelId: copy.host.modelId)
    }

    func addOrUpdateSeat(_ seat: IOSCouncilRoomSeatConfig, currentModelId: String) {
        var copy = settings.normalized(currentModelId: currentModelId)
        let normalized = seat.normalized(currentModelId: currentModelId)
        if let index = copy.seats.firstIndex(where: { $0.id == normalized.id }) {
            copy.seats[index] = normalized
        } else {
            copy.seats.append(normalized)
        }
        settings = copy
    }

    func removeSeat(id: String) {
        var copy = settings
        copy.seats.removeAll { $0.id == id }
        settings = copy
    }

    func toggleDefaultSeat(id: String) {
        var copy = settings
        guard let index = copy.seats.firstIndex(where: { $0.id == id }) else { return }
        copy.seats[index].isDefault.toggle()
        settings = copy
    }

    func removeAllCustomSeats() {
        var copy = settings
        copy.seats.removeAll()
        settings = copy
    }

    private func persist() {
        if let data = try? encoder.encode(settings) {
            userDefaults.set(data, forKey: storageKey)
        }
    }
}

enum IOSCouncilRoomRunMode: String, Codable, Equatable {
    case freeChat = "free_chat"
    case debate
}

enum IOSCouncilResearchConsent: String, Codable, Equatable {
    case allowed
    case denied
    case unavailable
}

struct IOSCouncilRoomSpeaker: Equatable, Identifiable {
    let id: String
    let name: String
    let rolePrompt: String
    let modelId: String
    let providerId: String?
    let reasoning: IOSCouncilReasoningPreset
    let prompt: String
    let isHost: Bool

    init(
        id: String,
        name: String,
        rolePrompt: String,
        modelId: String,
        providerId: String? = nil,
        reasoning: IOSCouncilReasoningPreset,
        prompt: String,
        isHost: Bool
    ) {
        self.id = id
        self.name = name
        self.rolePrompt = rolePrompt
        self.modelId = modelId
        self.providerId = providerId?.trimmedNilIfBlank
        self.reasoning = reasoning
        self.prompt = prompt
        self.isHost = isHost
    }

    var shortLens: String {
        rolePrompt.components(separatedBy: "，").first?.trimmedNilIfBlank ?? rolePrompt
    }
}

struct IOSCouncilModelRouteDescriptor: Equatable {
    let providerId: String
    let modelId: String
}

enum IOSCouncilRoomMessageKind: Equatable {
    case host
    case seat
    case system
    case divider
}

enum IOSCouncilRoomMessageStatus: Equatable {
    case speaking
    case completed
    case failed
}

struct IOSCouncilRoomMessageEvent: Equatable, Identifiable {
    let id: UUID
    let kind: IOSCouncilRoomMessageKind
    let speakerId: String?
    let author: String
    let body: String
    let subtitle: String?
    let status: IOSCouncilRoomMessageStatus
}

enum IOSCouncilRoomEvent: Equatable {
    case taskStarted(String)
    case state(String)
    case roster([IOSCouncilRoomSpeaker], activeSpeakerId: String?, failedSpeakerIds: Set<String>)
    case append(IOSCouncilRoomMessageEvent)
    case updateMessage(id: UUID, body: String, status: IOSCouncilRoomMessageStatus)
}

struct IOSCouncilRoomContinuation {
    let taskId: String
    let originalObjective: String
    let finalTopic: String
    let priorTranscript: String
    let speakers: [IOSCouncilRoomSpeaker]
    let nextRound: Int
}

struct IOSCouncilRoomRunRequest {
    var taskId: String? = nil
    let objective: String
    let mode: IOSCouncilRoomRunMode
    let settings: IOSCouncilRoomSettings
    let currentModelId: String
    var currentModel: Model? = nil
    var baseParams: TextGenerationParams? = nil
    let providerSetting: ProviderSetting
    var providerSettings: [ProviderSetting] = []
    let searchSettings: Settings?
    let researchConsent: IOSCouncilResearchConsent
    /// 开:主持人按议题自由动态生成席位;关:只用 settings.seats 里已添加的席位。
    var dynamicSeatGeneration: Bool = false
    /// 开:每位席位发言前先联网查证一轮,把材料注入其发言 prompt(治 grok 等模型
    /// 「想搜却无工具」而幻觉出 web_search 文本);关:席位纯推理。仍需 researchConsent
    /// 为 allowed(全局联网开关)才真正联网,且追问(continuation)不查以控成本。
    var seatWebSearch: Bool = false
    /// 非 nil 时沿用同一议会任务、席位与既有转录，只追加一轮追问讨论。
    var continuation: IOSCouncilRoomContinuation? = nil
    /// 用户上传文件/图片解析后的文本材料（仅首轮议题完善使用；追问不重复注入）。
    var sourceMaterials: String? = nil
    /// 联网调研用的议题文案；缺省时回退 `objective`。可含材料摘要以提升检索相关性。
    var researchObjective: String? = nil
}

struct IOSCouncilRoomRunSummary: Equatable {
    let taskId: String
    let status: IOSAdvancedTaskStatus
    let finalTopic: String
    let finalAnswer: String
    let failureReason: String?
    let seatNames: [String]
    let failedSeats: [String]
    let transcript: String
}

struct IOSCouncilScrapedPage: Equatable {
    let url: String
    let content: String
}

struct IOSCouncilResearchBundle: Equatable {
    var searches: [IOSSearchExecution]
    var scrapedPages: [IOSCouncilScrapedPage]
    var failures: [String]

    static func == (lhs: IOSCouncilResearchBundle, rhs: IOSCouncilResearchBundle) -> Bool {
        lhs.searches == rhs.searches
            && lhs.scrapedPages.map { [$0.url, $0.content] } == rhs.scrapedPages.map { [$0.url, $0.content] }
            && lhs.failures == rhs.failures
    }

    var isEmpty: Bool {
        searches.isEmpty && scrapedPages.isEmpty && failures.isEmpty
    }

    var summaryText: String {
        var lines: [String] = []
        for execution in searches {
            lines.append("Search: \(execution.request.query)")
            for result in execution.results.prefix(5) {
                lines.append("- \(result.title): \(result.url)\n  \(result.snippet)")
            }
        }
        for page in scrapedPages {
            lines.append("Scrape: \(page.url)\n\(page.content)")
        }
        if !failures.isEmpty {
            lines.append("Research failures:\n" + failures.joined(separator: "\n"))
        }
        return lines.joined(separator: "\n\n")
    }
}

@MainActor
protocol IOSCouncilResearching {
    func research(
        objective: String,
        settings: Settings?,
        maxSearches: Int,
        maxScrapes: Int
    ) async -> IOSCouncilResearchBundle
}

@MainActor
struct IOSCouncilSearchResearcher: IOSCouncilResearching {
    var transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()

    func research(
        objective: String,
        settings: Settings?,
        maxSearches: Int = 4,
        maxScrapes: Int = 4
    ) async -> IOSCouncilResearchBundle {
        let trimmed = objective.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return IOSCouncilResearchBundle(searches: [], scrapedPages: [], failures: ["empty objective"])
        }

        let queries = Array([
            trimmed,
            "\(trimmed) 最新 信息",
            "\(trimmed) 分析 观点",
            "\(trimmed) 背景 影响"
        ].prefix(max(0, maxSearches)))

        var executions: [IOSSearchExecution] = []
        var scraped: [IOSCouncilScrapedPage] = []
        var failures: [String] = []
        var scrapeCandidates: [String] = []

        for query in queries {
            do {
                let input = Self.json(["query": query, "max_results": 5])
                let execution = try await IOSSearchExecutor.searchResults(
                    toolInput: input,
                    maxResults: 5,
                    settings: settings,
                    transport: transport
                )
                executions.append(execution)
                scrapeCandidates.append(contentsOf: execution.results.map(\.url))
            } catch {
                failures.append("search_web \(query): \(error.localizedDescription)")
            }
        }

        var seen = Set<String>()
        for url in scrapeCandidates where scraped.count < maxScrapes {
            guard seen.insert(url).inserted else { continue }
            do {
                let input = Self.json(["url": url, "max_chars": 4_000])
                let content = try await IOSSearchExecutor.execute(
                    toolName: "scrape_web",
                    toolInput: input,
                    maxResults: 1,
                    settings: settings,
                    transport: transport
                )
                scraped.append(IOSCouncilScrapedPage(url: url, content: content))
            } catch {
                failures.append("scrape_web \(url): \(error.localizedDescription)")
            }
        }

        return IOSCouncilResearchBundle(searches: executions, scrapedPages: scraped, failures: failures)
    }

    private static func json(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return String(describing: object)
        }
        return text
    }
}

@MainActor
protocol IOSCouncilTextStreaming: AnyObject {
    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String

    func cancel()
}

/// A presentation-only gate for council text streams. Provider chunks continue to
/// accumulate losslessly; the expensive full snapshot is deferred until the one
/// scheduled UI flush (or an exact terminal/cancel flush).
@MainActor
final class IOSCouncilTextPresentationSession {
    private let flushDelayNanoseconds: UInt64
    private let snapshotProvider: @MainActor () -> String
    private let onUpdate: @MainActor (String) -> Void
    private var flushTask: Task<Void, Never>?
    private var lastPublishedText: String?
    private(set) var isClosed = false
    private(set) var finalText = ""

    init(
        flushDelayNanoseconds: UInt64 = 48_000_000,
        snapshotProvider: @escaping @MainActor () -> String,
        onUpdate: @escaping @MainActor (String) -> Void
    ) {
        self.flushDelayNanoseconds = flushDelayNanoseconds
        self.snapshotProvider = snapshotProvider
        self.onUpdate = onUpdate
    }

    func scheduleFlush() {
        guard !isClosed, flushTask == nil else { return }
        flushTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await Task.sleep(nanoseconds: self.flushDelayNanoseconds)
            } catch {
                return
            }
            guard !Task.isCancelled, !self.isClosed else { return }
            self.flushTask = nil
            self.publish(self.snapshotProvider())
        }
    }

    /// Cancels any delayed publication and synchronously exposes the exact latest
    /// authoritative text before the caller publishes completed/failed/cancelled state.
    @discardableResult
    func flushAndClose() -> String {
        guard !isClosed else { return finalText }
        isClosed = true
        flushTask?.cancel()
        flushTask = nil
        let text = snapshotProvider()
        finalText = text
        publish(text)
        return text
    }

    private func publish(_ text: String) {
        guard lastPublishedText != text else { return }
        lastPublishedText = text
        onUpdate(text)
    }
}

enum IOSCouncilGeneratedTextSnapshot {
    static func text(from messages: [UIMessage]) -> String {
        guard let message = messages.last,
              message.role == MessageRole.assistant else {
            return ""
        }
        return message.toText()
    }
}

@MainActor
private final class IOSCouncilActiveTextStream {
    let accumulator: MessageStreamAccumulator
    let eventSink: ChatStreamEventSink
    let presentation: IOSCouncilTextPresentationSession

    init(
        accumulator: MessageStreamAccumulator,
        eventSink: ChatStreamEventSink,
        onUpdate: @escaping @MainActor (String) -> Void
    ) {
        self.accumulator = accumulator
        self.eventSink = eventSink
        self.presentation = IOSCouncilTextPresentationSession(
            snapshotProvider: {
                IOSCouncilGeneratedTextSnapshot.text(from: accumulator.snapshot())
            },
            onUpdate: onUpdate
        )
    }

    func accept(_ chunk: MessageChunk) {
        guard !presentation.isClosed else { return }
        accumulator.append(chunk: chunk)
        presentation.scheduleFlush()
    }

    @discardableResult
    func flushAndClose(drainingQueuedChunks: Bool) -> String {
        if drainingQueuedChunks, !presentation.isClosed {
            // Close the sink first so the drained prefix has an exact acceptance
            // boundary: callbacks racing cancellation are either already queued or
            // rejected, never admitted between drain and snapshot.
            eventSink.finish()
            for chunk in eventSink.takePendingChunks() {
                accumulator.append(chunk: chunk)
            }
        }
        let text = presentation.flushAndClose()
        eventSink.finish()
        return text
    }
}

@MainActor
final class IOSCouncilTextStreamer: IOSCouncilTextStreaming {
    private let openAIProvider: OpenAIKmpProvider
    private let claudeProvider: ClaudeKmpProvider
    private var job: Kotlinx_coroutines_coreJob?
    private var grokWebStreamTask: Task<Void, Never>?
    private var activeStream: IOSCouncilActiveTextStream?

    init(
        provider: OpenAIKmpProvider = OpenAIKmpProvider(),
        claudeProvider: ClaudeKmpProvider = ClaudeKmpProvider()
    ) {
        self.openAIProvider = provider
        self.claudeProvider = claudeProvider
    }

    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        let effectiveProvider = try await IOSCodexProviderResolver.resolved(providerSetting)
        let effectiveParams = IOSCodexProviderResolver.augmentParamsForCodex(
            params,
            provider: effectiveProvider
        )
        let accumulator = MessageStreamAccumulator(initialMessages: messages, model: effectiveParams.model)
        let eventSink = ChatStreamEventSink()
        let eventStream = AsyncStream<ChatStreamEvent>(bufferingPolicy: .unbounded) { continuation in
            eventSink.bind(continuation)
        }
        let stream = IOSCouncilActiveTextStream(
            accumulator: accumulator,
            eventSink: eventSink,
            onUpdate: onUpdate
        )
        activeStream = stream

        job = dispatchCouncilStream(
            providerSetting: effectiveProvider,
            messages: messages,
            params: effectiveParams,
            onChunk: { chunk in
                eventSink.yield(.chunk(chunk))
            },
            onComplete: {
                eventSink.yield(.complete())
                eventSink.finish()
            },
            onError: { error in
                eventSink.yield(.error(error))
                eventSink.finish()
            }
        )

        for await event in eventStream {
            guard eventSink.claim(event) else { continue }
            switch event.payload {
            case .chunk(let chunk):
                stream.accept(chunk)
            case .complete:
                let text = stream.flushAndClose(drainingQueuedChunks: false)
                clearActiveStreamIfNeeded(stream)
                return text
            case .error(let error):
                _ = stream.flushAndClose(drainingQueuedChunks: false)
                clearActiveStreamIfNeeded(stream)
                throw IOSCouncilRoomRunnerError.generationFailed(
                    error.message ?? String(describing: error)
                )
            }
        }

        let text = stream.flushAndClose(drainingQueuedChunks: true)
        if Task.isCancelled, activeStream === stream {
            job?.cancel(cause: nil)
        }
        clearActiveStreamIfNeeded(stream)
        return text
    }

    /// Dispatch a council seat stream to the OpenAI or Claude executor based on
    /// the resolved provider's sealed type. Both share streamTextCancellable.
    private func dispatchCouncilStream(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onChunk: @escaping (MessageChunk) -> Void,
        onComplete: @escaping () -> Void,
        onError: @escaping (KotlinThrowable) -> Void
    ) -> Kotlinx_coroutines_coreJob? {
        if let openAI = providerSetting as? ProviderSetting.OpenAI {
            if IOSGrokWebProviderResolver.isGrokWebConfiguration(openAI) {
                grokWebStreamTask?.cancel()
                let providerId = IOSGrokWebProviderResolver.providerKey(openAI)
                grokWebStreamTask = Task {
                    do {
                        try await IOSGrokWebClient(providerId: providerId).streamText(
                            messages: messages,
                            params: params,
                            onChunk: onChunk
                        )
                        guard !Task.isCancelled else { return }
                        onComplete()
                    } catch is CancellationError {
                        return
                    } catch {
                        guard !Task.isCancelled else { return }
                        onError(KotlinThrowable(message: (error as NSError).localizedDescription))
                    }
                }
                return nil
            }
            return openAIProvider.streamTextCancellable(
                providerSetting: openAI, messages: messages, params: params,
                onChunk: onChunk, onComplete: onComplete, onError: onError
            )
        }
        if let claude = providerSetting as? ProviderSetting.Claude {
            return claudeProvider.streamTextCancellable(
                providerSetting: claude, messages: messages, params: params,
                onChunk: onChunk, onComplete: onComplete, onError: onError
            )
        }
        onError(KotlinThrowable(message: "当前服务商类型暂不支持 council"))
        return nil
    }

    func cancel() {
        if let activeStream {
            _ = activeStream.flushAndClose(drainingQueuedChunks: true)
            self.activeStream = nil
        }
        job?.cancel(cause: nil)
        job = nil
        grokWebStreamTask?.cancel()
        grokWebStreamTask = nil
    }

    private func clearActiveStreamIfNeeded(_ stream: IOSCouncilActiveTextStream) {
        guard activeStream === stream else { return }
        activeStream = nil
        job = nil
        grokWebStreamTask = nil
    }

    deinit {
        job?.cancel(cause: nil)
        grokWebStreamTask?.cancel()
    }
}

@MainActor
private final class IOSCouncilStreamTail {
    var body = ""
}

enum IOSCouncilRoomRunnerError: LocalizedError, Equatable {
    case missingAPIKey
    case emptyObjective
    case emptyOutput(String)
    case generationFailed(String)
    case timedOut(String)
    case cancelled

    var errorDescription: String? {
        switch self {
        case .missingAPIKey: "当前服务商缺少 API Key。"
        case .emptyObjective: "议题为空。"
        case .emptyOutput(let stage): "\(stage)没有返回内容。"
        case .generationFailed(let message): "模型生成失败：\(message)"
        case .timedOut(let speaker): "\(speaker) 连续无输出，已超时。"
        case .cancelled: "模型议会已取消。"
        }
    }
}

private final class IOSCouncilStreamHeartbeat: @unchecked Sendable {
    private let lock = NSLock()
    private var lastOutputNanoseconds = DispatchTime.now().uptimeNanoseconds

    func recordOutput() {
        lock.lock()
        lastOutputNanoseconds = DispatchTime.now().uptimeNanoseconds
        lock.unlock()
    }

    func hasBeenSilent(for timeoutNanoseconds: UInt64) -> Bool {
        lock.lock()
        let lastOutputNanoseconds = self.lastOutputNanoseconds
        lock.unlock()
        return DispatchTime.now().uptimeNanoseconds &- lastOutputNanoseconds >= timeoutNanoseconds
    }
}

private struct IOSCouncilModelRoute {
    let providerSetting: ProviderSetting
    let model: Model

    var descriptor: IOSCouncilModelRouteDescriptor {
        IOSCouncilModelRouteDescriptor(
            providerId: providerSetting.id.description(),
            modelId: model.modelId
        )
    }
}

@MainActor
final class IOSCouncilRoomRunner {
    private let streamer: any IOSCouncilTextStreaming
    private let researcher: any IOSCouncilResearching
    private let taskStore: IOSAdvancedTaskStore
    private let permissionStore: IOSPermissionStore?
    private let timeoutUnitNanoseconds: UInt64
    private var runGeneration: UInt64 = 0
    private var activeRunGeneration: UInt64?
    private var activeTaskId: String?

    init(
        streamer: any IOSCouncilTextStreaming = IOSCouncilTextStreamer(),
        researcher: any IOSCouncilResearching = IOSCouncilSearchResearcher(),
        taskStore: IOSAdvancedTaskStore = .shared,
        permissionStore: IOSPermissionStore? = nil,
        timeoutUnitNanoseconds: UInt64 = 1_000_000_000
    ) {
        self.streamer = streamer
        self.researcher = researcher
        self.taskStore = taskStore
        self.permissionStore = permissionStore
        self.timeoutUnitNanoseconds = timeoutUnitNanoseconds
    }

    func cancel() {
        runGeneration &+= 1
        activeRunGeneration = nil
        activeTaskId = nil
        streamer.cancel()
    }

    /// Reads the persisted status for the run currently shown by the room.
    /// The ViewModel still owns the lifecycle; this read only closes the narrow
    /// handoff window after `run()` has returned and before its caller releases
    /// the background lease.
    func taskStatus(taskId: String?) -> IOSAdvancedTaskStatus? {
        guard let taskId else { return nil }
        return taskStore.tasks.first(where: { $0.id == taskId })?.status
    }

    /// Returns the same durable record owned by this runner's injected store.
    /// Home and runtime projections must not reach around an injected runner to
    /// read the process-global store, or tests/custom owners can observe another task.
    func taskRecord(taskId: String?) -> IOSAdvancedTaskRecord? {
        guard let taskId else { return nil }
        return taskStore.tasks.first(where: { $0.id == taskId })
    }

    /// 由 ViewModel 在取消/系统执行权到期时写入任务终态；runner 本身仍只负责
    /// 取消 provider，避免把页面生命周期和 provider 流程混成第二套状态机。
    func markActiveTaskTerminal(
        taskId: String? = nil,
        status: IOSAdvancedTaskStatus,
        summary: String,
        retryable: Bool
    ) {
        guard let activeTaskId,
              (taskId == nil || taskId == activeTaskId) else { return }
        _ = taskStore.updateTask(
            id: activeTaskId,
            status: status,
            resultSummary: summary,
            error: "",
            retryable: retryable,
            cancelCapability: false,
            metadata: status == .interrupted
                ? ["interruption_reason": "background_execution_expired"]
                : nil
        )
    }

    func run(
        request: IOSCouncilRoomRunRequest,
        onEvent: @escaping @MainActor (IOSCouncilRoomEvent) -> Void = { _ in }
    ) async -> IOSCouncilRoomRunSummary {
        runGeneration &+= 1
        let currentRunGeneration = runGeneration
        if activeRunGeneration != nil {
            streamer.cancel()
        }
        activeRunGeneration = currentRunGeneration
        defer {
            if activeRunGeneration == currentRunGeneration {
                activeRunGeneration = nil
                activeTaskId = nil
            }
        }
        let objective = request.objective.trimmingCharacters(in: .whitespacesAndNewlines)
        let settings = request.settings.normalized(currentModelId: request.currentModelId)
        let limits = settings.limits.normalized()
        let availableRoutes = modelRoutes(for: request)
        func resolveRoute(providerId: String?, modelId: String) -> IOSCouncilModelRoute {
            resolvedRoute(
                providerId: providerId,
                modelId: modelId,
                request: request,
                routes: availableRoutes
            )
        }
        let task: IOSAdvancedTaskRecord
        if let continuation = request.continuation,
           let existing = taskStore.tasks.first(where: { $0.id == continuation.taskId }) {
            task = taskStore.updateTask(
                id: existing.id,
                status: .running,
                resultSummary: "",
                error: "",
                retryable: false,
                cancelCapability: true,
                metadata: [
                    "continuation_round": String(continuation.nextRound),
                    "continuation_base_completed": "true"
                ]
            ) ?? existing
        } else {
            let continuation = request.continuation
            let metadata = continuation.map {
                [
                    "room_mode": request.mode.rawValue,
                    "host_model": settings.host.modelId,
                    "research_consent": request.researchConsent.rawValue,
                    "continuation_round": String($0.nextRound),
                    "continuation_base_completed": "true"
                ]
            } ?? [
                "room_mode": request.mode.rawValue,
                "host_model": settings.host.modelId,
                "research_consent": request.researchConsent.rawValue
            ]
            task = taskStore.startTask(
                id: request.taskId ?? continuation?.taskId,
                kind: .modelCouncil,
                title: "\(request.mode.title) · \((continuation?.originalObjective ?? objective).prefix(34))",
                objective: continuation?.originalObjective ?? objective,
                toolScope: continuation == nil && request.researchConsent == .allowed
                    ? ["search_web", "scrape_web"]
                    : [],
                budgetSummary: "mode \(request.mode.rawValue) · max seats \(limits.maxSeats) · rounds \(limits.defaultRounds) · budget \(limits.outputBudgetCharacters) chars",
                sourceToolName: "council_room",
                metadata: metadata
            )
        }
        activeTaskId = task.id
        onEvent(.taskStarted(task.id))

        guard !objective.isEmpty else {
            let message = IOSCouncilRoomRunnerError.emptyObjective.localizedDescription
            _ = taskStore.updateTask(
                id: task.id,
                status: .failed,
                resultSummary: message,
                error: message,
                retryable: true,
                cancelCapability: false
            )
            return IOSCouncilRoomRunSummary(
                taskId: task.id,
                status: .failed,
                finalTopic: "",
                finalAnswer: "",
                failureReason: message,
                seatNames: [],
                failedSeats: [],
                transcript: ""
            )
        }

        let currentRoute = resolveRoute(
            providerId: request.providerSetting.id.description(),
            modelId: request.currentModelId
        )
        if let issue = ChatProviderConfiguration.issue(
            for: currentRoute.model,
            provider: currentRoute.providerSetting
        ) {
            let message = issue.message
            onEvent(.append(systemMessage(body: message, subtitle: "配置阻塞", status: .failed)))
            _ = taskStore.updateTask(
                id: task.id,
                status: .failed,
                resultSummary: message,
                error: message,
                retryable: true,
                cancelCapability: false
            )
            return IOSCouncilRoomRunSummary(
                taskId: task.id,
                status: .failed,
                finalTopic: "",
                finalAnswer: "",
                failureReason: message,
                seatNames: [],
                failedSeats: [],
                transcript: message
            )
        }

        recordResearchConsent(request.researchConsent, objective: objective, runId: task.id)

        let continuedHost = request.continuation?.speakers.first(where: \.isHost)
        let hostRoute = resolveRoute(
            providerId: continuedHost?.providerId,
            modelId: continuedHost?.modelId ?? settings.host.modelId
        )
        let host = continuedHost.map { speaker in
            IOSCouncilRoomSpeaker(
                id: speaker.id,
                name: speaker.name,
                rolePrompt: speaker.rolePrompt,
                modelId: hostRoute.model.modelId,
                providerId: hostRoute.providerSetting.id.description(),
                reasoning: speaker.reasoning,
                prompt: speaker.prompt,
                isHost: true
            )
        } ?? IOSCouncilRoomSpeaker(
            id: "host",
            name: "主持人",
            rolePrompt: settings.host.prompt,
            modelId: hostRoute.model.modelId,
            providerId: hostRoute.providerSetting.id.description(),
            reasoning: settings.host.reasoning,
            prompt: settings.host.prompt,
            isHost: true
        )
        // 静态默认席位仅作为「主持人动态规划失败」时的兜底。
        var activeSeats = if let continuation = request.continuation {
            continuation.speakers.filter { !$0.isHost }.map { seat in
                let route = resolveRoute(providerId: seat.providerId, modelId: seat.modelId)
                return IOSCouncilRoomSpeaker(
                    id: seat.id,
                    name: seat.name,
                    rolePrompt: seat.rolePrompt,
                    modelId: route.model.modelId,
                    providerId: route.providerSetting.id.description(),
                    reasoning: seat.reasoning,
                    prompt: seat.prompt,
                    isHost: false
                )
            }
        } else {
            settings.defaultSeats(currentModelId: request.currentModelId).map { seat in
                let route = resolveRoute(providerId: nil, modelId: seat.modelId)
                return IOSCouncilRoomSpeaker(
                    id: seat.id,
                    name: seat.name,
                    rolePrompt: seat.rolePrompt,
                    modelId: route.model.modelId,
                    providerId: route.providerSetting.id.description(),
                    reasoning: seat.reasoning,
                    prompt: seat.prompt,
                    isHost: false
                )
            }
        }
        // 兜底注入内置默认席位,只在以下两种情况:动态生成开(这只是占位,稍后会被主持人
        // 动态规划替换);或用户一个席位都没配(否则会变成只有主持人的空议会)。动态关 + 用户
        // 已添加 ≥1 个席位时,尊重用户配置,不再塞入无关的默认人设(honor「只用已添加的席位」)。
        if activeSeats.count < 2,
           request.continuation == nil,
           request.dynamicSeatGeneration || activeSeats.isEmpty {
            activeSeats = Array(IOSCouncilRoomSettings.defaults(currentModelId: request.currentModelId)
                .defaultSeats(currentModelId: request.currentModelId)
                .prefix(limits.maxSeats)
                .map {
                    let route = resolveRoute(providerId: nil, modelId: $0.modelId)
                    return IOSCouncilRoomSpeaker(
                        id: $0.id,
                        name: $0.name,
                        rolePrompt: $0.rolePrompt,
                        modelId: route.model.modelId,
                        providerId: route.providerSetting.id.description(),
                        reasoning: $0.reasoning,
                        prompt: $0.prompt,
                        isHost: false
                    )
                })
        }

        var transcript = request.continuation?.priorTranscript.trimmedNilIfBlank.map { [$0] } ?? []
        var failedSeatIds = Set<String>()
        onEvent(.roster([host] + activeSeats, activeSpeakerId: nil, failedSpeakerIds: failedSeatIds))
        if request.continuation == nil {
            onEvent(.state("主持调研中"))
            onEvent(.append(dividerMessage("主持调研")))
        } else {
            onEvent(.state("追问讨论中"))
        }

        let research: IOSCouncilResearchBundle
        if request.continuation != nil {
            research = IOSCouncilResearchBundle(searches: [], scrapedPages: [], failures: [])
        } else if request.researchConsent == .allowed {
            let researchQuery = request.researchObjective?.trimmedNilIfBlank ?? objective
            research = await researcher.research(
                objective: researchQuery,
                settings: request.searchSettings,
                maxSearches: 4,
                maxScrapes: 4
            )
        } else {
            research = IOSCouncilResearchBundle(
                searches: [],
                scrapedPages: [],
                failures: request.researchConsent == .denied ? ["用户选择本轮不联网调研。"] : ["搜索未启用。"]
            )
        }
        if request.continuation == nil {
            taskStore.appendLog(id: task.id, chunk: "research:\n\(research.summaryText)\n\n")
        } else {
            transcript.append("[你 · 追问] \(objective)")
            taskStore.appendLog(id: task.id, chunk: "[你 · 追问] \(objective)\n\n")
        }

        do {
            let finalTopic: String
            if let continuation = request.continuation {
                finalTopic = continuation.finalTopic.trimmedOr(continuation.originalObjective)
            } else {
                let topicMessageId = UUID()
                onEvent(.append(IOSCouncilRoomMessageEvent(
                    id: topicMessageId,
                    kind: .host,
                    speakerId: host.id,
                    author: host.name,
                    body: "调研和完善议题中...",
                    subtitle: "主持 · \(host.modelId)",
                    status: .speaking
                )))
                onEvent(.roster([host] + activeSeats, activeSpeakerId: host.id, failedSpeakerIds: failedSeatIds))
                let defaultSeatsForTopic = activeSeats
                let generatedTopic = try await streamWithTimeout(
                    runGeneration: currentRunGeneration,
                    seconds: limits.seatTimeoutSeconds,
                    timeoutLabel: "主持人议题整理"
                ) { recordOutput in
                    try await self.stream(
                        runGeneration: currentRunGeneration,
                        speaker: host,
                        systemPrompt: self.hostSystemPrompt(settings: settings),
                        userPrompt: self.finalTopicPrompt(
                            objective: objective,
                            research: research,
                            sourceMaterials: request.sourceMaterials,
                            limits: limits,
                            defaultSeats: defaultSeatsForTopic
                        ),
                        request: request,
                        temperature: 0.35,
                        onUpdate: { text in
                            if !text.isEmpty { recordOutput() }
                            onEvent(.updateMessage(id: topicMessageId, body: text.isEmpty ? "调研和完善议题中..." : text, status: .speaking))
                        }
                    )
                }
                guard let generatedTopic = generatedTopic.trimmedNilIfBlank else {
                    onEvent(.updateMessage(
                        id: topicMessageId,
                        body: "主持人未返回议题。",
                        status: .failed
                    ))
                    throw IOSCouncilRoomRunnerError.emptyOutput("最终议题")
                }
                try checkCancelled(runGeneration: currentRunGeneration)
                onEvent(.updateMessage(id: topicMessageId, body: generatedTopic, status: .completed))
                transcript.append("[\(host.name)] \(generatedTopic)")
                taskStore.appendLog(id: task.id, chunk: "[\(host.name)] \(generatedTopic)\n\n")
                finalTopic = generatedTopic
            }

            // 开关「动态席位生成」开 → 主持人按议题 + 调研单独输出一份严格 JSON 席位清单
            //（Android planned_seats 思路），贴合本议题;关 → 直接用用户已添加的席位,不自由发挥。
            if request.continuation != nil {
                onEvent(.append(dividerMessage("沿用本议会席位：\(activeSeats.map(\.name).joined(separator: "、"))")))
            } else if request.dynamicSeatGeneration {
                onEvent(.state("组建议员席位中"))
                // 组席调用失败（超时/抛错）时重试一次；调用成功但解析不出席位不重试，
                // 走下面的显式回退——避免把"调用挂了"和"模型没给 JSON"混为一谈。
                var seatPlanRawOrNil: String? = try? await streamWithTimeout(
                    runGeneration: currentRunGeneration,
                    seconds: limits.seatTimeoutSeconds,
                    timeoutLabel: "主持人组席"
                ) { recordOutput in
                    try await self.stream(
                        runGeneration: currentRunGeneration,
                        speaker: host,
                        systemPrompt: self.seatPlanSystemPrompt(),
                        userPrompt: self.seatPlanPrompt(
                            objective: objective,
                            finalTopic: finalTopic,
                            research: research,
                            limits: limits,
                            sourceMaterials: request.sourceMaterials
                        ),
                        request: request,
                        temperature: 0.3,
                        onUpdate: { text in
                            if !text.isEmpty { recordOutput() }
                        }
                    )
                }
                if seatPlanRawOrNil == nil {
                    try checkCancelled(runGeneration: currentRunGeneration)
                    seatPlanRawOrNil = try? await streamWithTimeout(
                        runGeneration: currentRunGeneration,
                        seconds: limits.seatTimeoutSeconds,
                        timeoutLabel: "主持人组席重试"
                    ) { recordOutput in
                        try await self.stream(
                            runGeneration: currentRunGeneration,
                            speaker: host,
                            systemPrompt: self.seatPlanSystemPrompt(),
                            userPrompt: self.seatPlanPrompt(
                                objective: objective,
                                finalTopic: finalTopic,
                                research: research,
                                limits: limits,
                                sourceMaterials: request.sourceMaterials
                            ),
                            request: request,
                            temperature: 0.3,
                            onUpdate: { text in
                                if !text.isEmpty { recordOutput() }
                            }
                        )
                    }
                }
                let seatPlanRaw = seatPlanRawOrNil ?? ""
                try checkCancelled(runGeneration: currentRunGeneration)
                let plannedSeats = Self.plannedSeatsFromJSON(
                    seatPlanRaw,
                    maxSeats: limits.maxSeats,
                    routes: Self.diverseSeatRoutes(
                        routes: availableRoutes.map(\.descriptor),
                        hostRoute: IOSCouncilModelRouteDescriptor(
                            providerId: host.providerId ?? request.providerSetting.id.description(),
                            modelId: host.modelId
                        )
                    )
                )
                let usedDynamicSeats = plannedSeats.count >= 2
                if usedDynamicSeats {
                    activeSeats = plannedSeats
                } else {
                    // 显式回退：动态组席没产出有效席位时，明说沿用了默认席位，而不是像
                    // 组席成功那样打印"已组建…工程、产品、风险"，让用户误以为动态生效。
                    onEvent(.append(dividerMessage(
                        "动态组席未返回有效席位，已沿用默认席位：\(activeSeats.map(\.name).joined(separator: "、"))"
                    )))
                    taskStore.appendLog(
                        id: task.id,
                        chunk: "dynamic seat plan fell back to defaults; raw=\(seatPlanRaw.prefix(200))\n\n"
                    )
                }
                let validation = try await validateDynamicSeatModels(
                    activeSeats,
                    hostRoute: IOSCouncilModelRouteDescriptor(
                        providerId: host.providerId ?? request.providerSetting.id.description(),
                        modelId: host.modelId
                    ),
                    request: request,
                    runGeneration: currentRunGeneration,
                    timeoutSeconds: min(15, limits.seatTimeoutSeconds)
                )
                activeSeats = validation.seats
                if !validation.failures.isEmpty {
                    let summary = validation.failures
                        .map { "\($0.modelId)：\($0.reason)" }
                        .joined(separator: "；")
                    onEvent(.append(dividerMessage("模型联通检查已替换不可用模型：\(validation.failures.map(\.modelId).joined(separator: "、"))")))
                    taskStore.appendLog(id: task.id, chunk: "dynamic model probe fallback: \(summary)\n\n")
                }
                if usedDynamicSeats {
                    onEvent(.append(dividerMessage("已组建 \(activeSeats.count) 位议员：\(activeSeats.map(\.name).joined(separator: "、"))")))
                }
            } else {
                onEvent(.append(dividerMessage("本轮议员（已添加席位）：\(activeSeats.map(\.name).joined(separator: "、"))")))
            }
            onEvent(.roster([host] + activeSeats, activeSpeakerId: nil, failedSpeakerIds: failedSeatIds))
            if request.continuation == nil {
                onEvent(.append(dividerMessage(request.mode == .debate ? "辩论开始" : "自由群聊开始")))
            }

            // 两种模式的新议会都使用默认轮数；追问只追加 continuation 指定的一轮。
            let finalRound = request.continuation?.nextRound ?? limits.defaultRounds
            let roundNumbers = request.continuation.map { [max(2, $0.nextRound)] }
                ?? Array(1...finalRound)
            for round in roundNumbers {
                try checkCancelled(runGeneration: currentRunGeneration)
                onEvent(.append(dividerMessage("第 \(round) 轮")))
                for seat in activeSeats where !failedSeatIds.contains(seat.id) {
                    try checkCancelled(runGeneration: currentRunGeneration)
                    onEvent(.state("\(seat.name) 发言中"))
                    onEvent(.roster([host] + activeSeats, activeSpeakerId: seat.id, failedSpeakerIds: failedSeatIds))
                    let messageId = UUID()
                    onEvent(.append(IOSCouncilRoomMessageEvent(
                        id: messageId,
                        kind: .seat,
                        speakerId: seat.id,
                        author: seat.name,
                        body: "思考中...",
                        subtitle: "\(seat.modelId) · \(seat.reasoning.title)",
                        status: .speaking
                    )))
                    let latestMessage = IOSCouncilStreamTail()
                    do {
                        let transcriptSnapshot = transcript
                        // 席位发言前可选联网查证：开关开 + 本轮允许联网(全局联网开) + 非追问
                        // 时，复用主持同一条调研链路为该席查一轮，把材料注入其发言 prompt。
                        // 该调用在 runner.run 内 → discussionTask 内 → 自动落在后台保活租约内。
                        let seatResearch: IOSCouncilResearchBundle
                        if request.seatWebSearch,
                           request.researchConsent == .allowed,
                           request.continuation == nil {
                            try checkCancelled(runGeneration: currentRunGeneration)
                            seatResearch = await researcher.research(
                                objective: "\(finalTopic)（\(seat.name)视角：\(seat.rolePrompt)）",
                                settings: request.searchSettings,
                                maxSearches: 2,
                                maxScrapes: 2
                            )
                        } else {
                            seatResearch = IOSCouncilResearchBundle(
                                searches: [], scrapedPages: [], failures: []
                            )
                        }
                        let output = try await streamWithTimeout(
                            runGeneration: currentRunGeneration,
                            seconds: limits.seatTimeoutSeconds,
                            timeoutLabel: seat.name
                        ) { recordOutput in
                            try await self.stream(
                                runGeneration: currentRunGeneration,
                                speaker: seat,
                                systemPrompt: self.seatSystemPrompt(seat: seat),
                                userPrompt: self.seatPrompt(
                                    objective: request.continuation?.originalObjective ?? objective,
                                    finalTopic: finalTopic,
                                    followUp: request.continuation == nil ? nil : objective,
                                    mode: request.mode,
                                    round: round,
                                    rounds: finalRound,
                                    transcript: transcriptSnapshot,
                                    budget: limits.outputBudgetCharacters,
                                    seatResearch: seatResearch
                                ),
                                request: request,
                                temperature: request.mode == .debate ? 0.55 : 0.75,
                                onUpdate: { text in
                                    if !text.isEmpty { recordOutput() }
                                    let shown = text.isEmpty
                                        ? "思考中..."
                                        : Self.sanitizeSeatOutput(text)
                                    // 判断是否更新 latestMessage.body 必须基于原始 text：占位符
                                    // "思考中..."(text 为空时)不算席位真实产出,否则失败兜底会把
                                    // 占位符当成已生成内容而漏掉"席位失败"提示。shown 仅负责显示。
                                    if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                        latestMessage.body = shown
                                    }
                                    onEvent(.updateMessage(id: messageId, body: shown, status: .speaking))
                                }
                            )
                        }
                        guard let output = Self.sanitizeSeatOutput(output).trimmedNilIfBlank else {
                            throw IOSCouncilRoomRunnerError.emptyOutput("\(seat.name)席位")
                        }
                        onEvent(.updateMessage(id: messageId, body: output, status: .completed))
                        transcript.append("[\(seat.name)] \(output)")
                        taskStore.appendLog(id: task.id, chunk: "[\(seat.name)] \(output)\n\n")
                    } catch {
                        if activeRunGeneration != currentRunGeneration
                            || error is CancellationError
                            || (error as? IOSCouncilRoomRunnerError) == .cancelled {
                            throw error
                        }
                        let reason = error.localizedDescription
                        failedSeatIds.insert(seat.id)
                        let failedBody = latestMessage.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ? "席位失败：\(reason)"
                            : latestMessage.body
                        onEvent(.updateMessage(id: messageId, body: failedBody, status: .failed))
                        onEvent(.roster([host] + activeSeats, activeSpeakerId: nil, failedSpeakerIds: failedSeatIds))
                        taskStore.appendLog(id: task.id, chunk: "[\(seat.name)] failed: \(reason)\n\n")
                    }
                }

                // 主持人轮末点评：非最后一轮时，主持人对本轮发言做点评
                // （指出矛盾 / 下轮重点），让下一轮有递进方向。
                if round < finalRound {
                    try checkCancelled(runGeneration: currentRunGeneration)
                    onEvent(.state("主持人轮末点评"))
                    onEvent(.append(dividerMessage("主持人轮末点评")))
                    let commentaryId = UUID()
                    onEvent(.append(IOSCouncilRoomMessageEvent(
                        id: commentaryId,
                        kind: .host,
                        speakerId: host.id,
                        author: host.name,
                        body: "点评中...",
                        subtitle: "第 \(round) 轮点评 · \(host.modelId)",
                        status: .speaking
                    )))
                    onEvent(.roster([host] + activeSeats, activeSpeakerId: host.id, failedSpeakerIds: failedSeatIds))
                    let latestCommentary = IOSCouncilStreamTail()
                    let roundTranscript = transcript.suffix(8).joined(separator: "\n\n")
                    do {
                        let commentary = try await streamWithTimeout(
                            runGeneration: currentRunGeneration,
                            seconds: limits.seatTimeoutSeconds,
                            timeoutLabel: "主持人点评"
                        ) { recordOutput in
                            try await self.stream(
                                runGeneration: currentRunGeneration,
                                speaker: host,
                                systemPrompt: self.hostSystemPrompt(settings: settings),
                                userPrompt: Self.roundEndCommentaryPrompt(
                                    objective: request.continuation?.originalObjective ?? objective,
                                    round: round,
                                    rounds: finalRound,
                                    roundTranscript: roundTranscript
                                ),
                                request: request,
                                temperature: 0.45,
                                onUpdate: { text in
                                    if !text.isEmpty { recordOutput() }
                                    let body = text.isEmpty ? "点评中..." : text
                                    if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                        latestCommentary.body = text
                                    }
                                    onEvent(.updateMessage(id: commentaryId, body: body, status: .speaking))
                                }
                            )
                        }
                        guard let commentary = commentary.trimmedNilIfBlank else {
                            throw IOSCouncilRoomRunnerError.emptyOutput("主持人点评")
                        }
                        onEvent(.updateMessage(id: commentaryId, body: commentary, status: .completed))
                        transcript.append("[\(host.name) · 第\(round)轮点评] \(commentary)")
                        taskStore.appendLog(id: task.id, chunk: "[\(host.name) · 第\(round)轮点评] \(commentary)\n\n")
                    } catch {
                        if activeRunGeneration != currentRunGeneration
                            || error is CancellationError
                            || (error as? IOSCouncilRoomRunnerError) == .cancelled {
                            throw error
                        }
                        let failedBody = latestCommentary.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ? "点评失败：\(error.localizedDescription)"
                            : latestCommentary.body
                        onEvent(.updateMessage(id: commentaryId, body: failedBody, status: .failed))
                    }
                    onEvent(.roster([host] + activeSeats, activeSpeakerId: nil, failedSpeakerIds: failedSeatIds))
                }
                onEvent(.roster([host] + activeSeats, activeSpeakerId: nil, failedSpeakerIds: failedSeatIds))
                // 最后一轮不点评，直接进综合（阶段 6）。
            }

            try checkCancelled(runGeneration: currentRunGeneration)
            onEvent(.state("主持总结中"))
            onEvent(.append(dividerMessage("主持总结")))
            let summaryId = UUID()
            onEvent(.append(IOSCouncilRoomMessageEvent(
                id: summaryId,
                kind: .host,
                speakerId: host.id,
                author: host.name,
                body: "总结中...",
                subtitle: "总结 · \(host.modelId)",
                status: .speaking
            )))
            onEvent(.roster([host] + activeSeats, activeSpeakerId: host.id, failedSpeakerIds: failedSeatIds))
            let transcriptForSynthesis = transcript
            let failedSeatsForSynthesis = activeSeats
                .filter { failedSeatIds.contains($0.id) }
                .map(\.name)
            let summary = try await streamWithTimeout(
                runGeneration: currentRunGeneration,
                seconds: limits.seatTimeoutSeconds,
                timeoutLabel: "主持人最终综合"
            ) { recordOutput in
                try await self.stream(
                    runGeneration: currentRunGeneration,
                    speaker: host,
                    systemPrompt: self.hostSystemPrompt(settings: settings),
                    userPrompt: self.synthesisPrompt(
                        objective: request.continuation?.originalObjective ?? objective,
                        finalTopic: finalTopic,
                        followUp: request.continuation == nil ? nil : objective,
                        transcript: transcriptForSynthesis,
                        failedSeats: failedSeatsForSynthesis
                    ),
                    request: request,
                    temperature: 0.35,
                    onUpdate: { text in
                        if !text.isEmpty { recordOutput() }
                        onEvent(.updateMessage(id: summaryId, body: text.isEmpty ? "总结中..." : text, status: .speaking))
                    }
                )
            }
            guard let summary = summary.trimmedNilIfBlank else {
                onEvent(.updateMessage(
                    id: summaryId,
                    body: "主持人未返回总结。",
                    status: .failed
                ))
                throw IOSCouncilRoomRunnerError.emptyOutput("最终综合")
            }
            onEvent(.updateMessage(id: summaryId, body: summary, status: .completed))
            transcript.append("[\(host.name)] \(summary)")
            taskStore.appendLog(id: task.id, chunk: "[\(host.name)] \(summary)\n\n")
            onEvent(.roster([host] + activeSeats, activeSpeakerId: nil, failedSpeakerIds: failedSeatIds))
            onEvent(.state("就绪"))

            let finalTranscript = clippedTranscript(transcript, budget: 24_000)
            let status: IOSAdvancedTaskStatus = .completed
            let failedNames = activeSeats.filter { failedSeatIds.contains($0.id) }.map(\.name)
            _ = taskStore.updateTask(
                id: task.id,
                status: status,
                resultSummary: failedNames.isEmpty ? "模型议会已完成讨论并生成结论。" : "模型议会已完成，失败席位：\(failedNames.joined(separator: ", "))。",
                logTail: finalTranscript,
                error: failedNames.isEmpty ? "" : "failed seats: \(failedNames.joined(separator: ", "))",
                retryable: !failedNames.isEmpty,
                cancelCapability: false,
                metadata: [
                    "final_topic_digest": Self.digest(finalTopic),
                    "seat_names": activeSeats.map(\.name).joined(separator: ", ")
                ]
            )
            return IOSCouncilRoomRunSummary(
                taskId: task.id,
                status: status,
                finalTopic: finalTopic,
                finalAnswer: summary,
                failureReason: nil,
                seatNames: activeSeats.map(\.name),
                failedSeats: failedNames,
                transcript: finalTranscript
            )
        } catch {
            // System execution expiry is recorded by the owner before cancelling
            // the provider. Preserve that explicit interrupted terminal instead
            // of letting the generic cancellation catch overwrite it as cancelled.
            if let terminalRecord = taskStore.tasks.first(where: { $0.id == task.id }),
               terminalRecord.status == .interrupted || terminalRecord.status == .cancelled {
                return IOSCouncilRoomRunSummary(
                    taskId: task.id,
                    status: terminalRecord.status,
                    finalTopic: "",
                    finalAnswer: "",
                    failureReason: terminalRecord.resultSummary,
                    seatNames: activeSeats.map(\.name),
                    failedSeats: activeSeats.filter { failedSeatIds.contains($0.id) }.map(\.name),
                    transcript: clippedTranscript(transcript, budget: 24_000)
                )
            }
            let isCancel = activeRunGeneration != currentRunGeneration
                || error is CancellationError
                || (error as? IOSCouncilRoomRunnerError) == .cancelled
            let isTimeout: Bool
            if case .timedOut? = error as? IOSCouncilRoomRunnerError {
                isTimeout = true
            } else {
                isTimeout = false
            }
            let status: IOSAdvancedTaskStatus = isCancel ? .cancelled : (isTimeout ? .timedOut : .failed)
            let message = isCancel ? "模型议会已取消。" : error.localizedDescription
            let isFailedContinuation = request.continuation != nil
            onEvent(.state(isCancel ? "已取消" : (isTimeout ? "已超时" : "失败")))
            onEvent(.append(systemMessage(
                body: message,
                subtitle: isCancel ? "已取消" : (isTimeout ? "已超时" : "运行失败"),
                status: .failed
            )))
            _ = taskStore.updateTask(
                id: task.id,
                status: isFailedContinuation ? .completed : status,
                resultSummary: isFailedContinuation
                    ? "既有议会结论已保留，本轮追问未完成。"
                    : message,
                logTail: clippedTranscript(transcript, budget: 24_000),
                error: isFailedContinuation || isCancel ? "" : message,
                retryable: !isFailedContinuation,
                cancelCapability: false,
                metadata: request.continuation.map {
                    [
                        "continuation_round": String($0.nextRound),
                        "continuation_status": status.rawValue
                    ]
                }
            )
            return IOSCouncilRoomRunSummary(
                taskId: task.id,
                status: status,
                finalTopic: "",
                finalAnswer: "",
                failureReason: message,
                seatNames: activeSeats.map(\.name),
                failedSeats: activeSeats.filter { failedSeatIds.contains($0.id) }.map(\.name),
                transcript: clippedTranscript(transcript, budget: 24_000)
            )
        }
    }

    static func makeProviderSetting(baseUrl: String, apiKey: String) -> ProviderSetting {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "当前 OpenAI-compatible 配置",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: apiKey,
            baseUrl: baseUrl,
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    /// Council supports the same OpenAI/Claude provider objects used by Chat.
    /// Preserve the object, especially its stable id used by OAuth/Web credentials.
    static func resolveProviderSetting(selected: ProviderSetting?) -> ProviderSetting? {
        guard let selected else { return nil }
        guard selected is ProviderSetting.OpenAI || selected is ProviderSetting.Claude else { return nil }
        return selected
    }

    /// Parses the host's strict-JSON seat plan `{"seats":[{"name","lens"}]}` into
    /// dynamic council speakers. Assigns the supplied supported models in order,
    /// without repeating until the pool is exhausted. Tolerates
    /// ```json fences / surrounding prose (reuses the deep-read balanced-brace
    /// extractor). Returns [] when fewer than 2 usable seats parse, so the caller
    /// keeps the resolved default seats as a fallback.
    static func plannedSeatsFromJSON(
        _ text: String,
        maxSeats: Int,
        routes: [IOSCouncilModelRouteDescriptor]
    ) -> [IOSCouncilRoomSpeaker] {
        let usableRoutes = routes.compactMap { route -> IOSCouncilModelRouteDescriptor? in
            guard let providerId = route.providerId.trimmedNilIfBlank,
                  let modelId = route.modelId.trimmedNilIfBlank else { return nil }
            return IOSCouncilModelRouteDescriptor(providerId: providerId, modelId: modelId)
        }
        guard !usableRoutes.isEmpty else { return [] }
        // 扫描所有顶层 JSON 对象，取第一个含 seats 数组的：主持人在真正 JSON 前用了带
        // 花括号的列举（如 {历史, 政治}）时，extractJSONObject 会先抓到干扰对象而静默回退，
        // 这里跳过它继续找含 seats 的对象。
        let seats: [[String: Any]]? = {
            for objectString in Self.topLevelJSONObjects(text) {
                guard let data = objectString.data(using: .utf8),
                      let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let seats = obj["seats"] as? [[String: Any]] else { continue }
                return seats
            }
            return nil
        }()
        guard let seats else { return [] }
        var result: [IOSCouncilRoomSpeaker] = []
        var seenNames = Set<String>()
        for entry in seats {
            guard let rawName = entry["name"] as? String else { continue }
            let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, seenNames.insert(name.lowercased()).inserted else { continue }
            let lensRaw = (entry["lens"] as? String) ?? (entry["role"] as? String) ?? (entry["prompt"] as? String) ?? ""
            let lens = lensRaw.trimmingCharacters(in: .whitespacesAndNewlines)
            let route = usableRoutes[result.count % usableRoutes.count]
            result.append(IOSCouncilRoomSpeaker(
                id: "planned-\(Self.digest(name).prefix(8))",
                name: name,
                rolePrompt: lens.isEmpty ? "围绕议题提供独立、专业的视角。" : lens,
                modelId: route.modelId,
                providerId: route.providerId,
                reasoning: .medium,
                prompt: "",
                isHost: false
            ))
            if result.count >= maxSeats { break }
        }
        return result.count >= 2 ? result : []
    }

    /// 文本中所有顶层配平 `{...}` 对象的子串（忽略字符串内的花括号与转义），按出现顺序。
    /// 用于在主持人输出里跳过不含 seats 的干扰对象，定位真正的席位 JSON。
    private static func topLevelJSONObjects(_ text: String) -> [String] {
        let chars = Array(text)
        var result: [String] = []
        var i = 0
        while i < chars.count {
            guard chars[i] == "{" else { i += 1; continue }
            let start = i
            var depth = 0
            var inString = false
            var escaped = false
            var j = i
            var closed = false
            while j < chars.count {
                let c = chars[j]
                if inString {
                    if escaped { escaped = false }
                    else if c == "\\" { escaped = true }
                    else if c == "\"" { inString = false }
                } else if c == "\"" {
                    inString = true
                } else if c == "{" {
                    depth += 1
                } else if c == "}" {
                    depth -= 1
                    if depth == 0 {
                        result.append(String(chars[start...j]))
                        i = j + 1
                        closed = true
                        break
                    }
                }
                j += 1
            }
            if !closed { break }
        }
        return result
    }

    /// Dynamic seats prefer one model from each non-host provider before reusing
    /// the host provider. The exact host route remains the last fallback.
    static func diverseSeatRoutes(
        routes: [IOSCouncilModelRouteDescriptor],
        hostRoute: IOSCouncilModelRouteDescriptor
    ) -> [IOSCouncilModelRouteDescriptor] {
        var seenRoutes = Set<String>()
        let normalized = routes.compactMap { route -> IOSCouncilModelRouteDescriptor? in
            guard let providerId = route.providerId.trimmedNilIfBlank,
                  let modelId = route.modelId.trimmedNilIfBlank else { return nil }
            let normalized = IOSCouncilModelRouteDescriptor(providerId: providerId, modelId: modelId)
            guard seenRoutes.insert(routeKey(normalized)).inserted else { return nil }
            return normalized
        }
        let hostKey = routeKey(hostRoute)
        let alternatives = normalized.filter { routeKey($0) != hostKey }

        func providerDiverse(_ candidates: [IOSCouncilModelRouteDescriptor]) -> [IOSCouncilModelRouteDescriptor] {
            var seenProviders = Set<String>()
            var firstPerProvider: [IOSCouncilModelRouteDescriptor] = []
            var remaining: [IOSCouncilModelRouteDescriptor] = []
            for route in candidates {
                if seenProviders.insert(route.providerId).inserted {
                    firstPerProvider.append(route)
                } else {
                    remaining.append(route)
                }
            }
            return firstPerProvider + remaining
        }

        var result = providerDiverse(alternatives.filter { $0.providerId != hostRoute.providerId })
        result += providerDiverse(alternatives.filter { $0.providerId == hostRoute.providerId })
        if !hostRoute.providerId.isEmpty,
           !hostRoute.modelId.isEmpty,
           !result.contains(where: { routeKey($0) == hostKey }) {
            result.append(hostRoute)
        }
        return result
    }

    private func validateDynamicSeatModels(
        _ seats: [IOSCouncilRoomSpeaker],
        hostRoute: IOSCouncilModelRouteDescriptor,
        request: IOSCouncilRoomRunRequest,
        runGeneration: UInt64,
        timeoutSeconds: Int
    ) async throws -> (seats: [IOSCouncilRoomSpeaker], failures: [(modelId: String, reason: String)]) {
        var seenRoutes = Set<String>()
        let candidateRoutes = seats.compactMap { seat -> IOSCouncilModelRoute? in
            let route = resolvedRoute(providerId: seat.providerId, modelId: seat.modelId, request: request)
            let key = Self.routeKey(route.descriptor)
            guard key != Self.routeKey(hostRoute), seenRoutes.insert(key).inserted else { return nil }
            return route
        }
        var reachableRoutes = Set([Self.routeKey(hostRoute)])
        var failures: [(modelId: String, reason: String)] = []
        for route in candidateRoutes {
            try checkCancelled(runGeneration: runGeneration)
            if let issue = ChatProviderConfiguration.issue(for: route.model, provider: route.providerSetting) {
                failures.append((route.model.modelId, issue.message))
                continue
            }
            do {
                try await probeDynamicSeatModel(
                    route,
                    request: request,
                    runGeneration: runGeneration,
                    timeoutSeconds: timeoutSeconds
                )
                reachableRoutes.insert(Self.routeKey(route.descriptor))
            } catch {
                try checkCancelled(runGeneration: runGeneration)
                failures.append((route.model.modelId, error.localizedDescription))
            }
        }
        guard !failures.isEmpty else { return (seats, []) }

        let resolvedHostRoute = resolvedRoute(
            providerId: hostRoute.providerId,
            modelId: hostRoute.modelId,
            request: request
        )
        let reachableAlternatives = candidateRoutes.filter {
            reachableRoutes.contains(Self.routeKey($0.descriptor))
        }
        let fallbackRoutes = [resolvedHostRoute] + reachableAlternatives
        var fallbackIndex = 0
        let repairedSeats = seats.map { seat in
            let route = resolvedRoute(providerId: seat.providerId, modelId: seat.modelId, request: request)
            guard !reachableRoutes.contains(Self.routeKey(route.descriptor)) else { return seat }
            let fallbackRoute = fallbackRoutes[fallbackIndex % fallbackRoutes.count]
            fallbackIndex += 1
            return IOSCouncilRoomSpeaker(
                id: seat.id,
                name: seat.name,
                rolePrompt: seat.rolePrompt,
                modelId: fallbackRoute.model.modelId,
                providerId: fallbackRoute.providerSetting.id.description(),
                reasoning: seat.reasoning,
                prompt: seat.prompt,
                isHost: false
            )
        }
        return (repairedSeats, failures)
    }

    private func probeDynamicSeatModel(
        _ route: IOSCouncilModelRoute,
        request: IOSCouncilRoomRunRequest,
        runGeneration: UInt64,
        timeoutSeconds: Int
    ) async throws {
        let output = try await streamWithTimeout(
            runGeneration: runGeneration,
            seconds: timeoutSeconds,
            timeoutLabel: "\(route.model.modelId) 联通测试"
        ) { recordOutput in
            try await self.performDynamicSeatProbe(
                route,
                request: request,
                recordOutput: recordOutput
            )
        }
        guard output.trimmedNilIfBlank != nil else {
            throw IOSCouncilRoomRunnerError.emptyOutput("\(route.model.modelId) 联通测试")
        }
    }

    private func performDynamicSeatProbe(
        _ route: IOSCouncilModelRoute,
        request: IOSCouncilRoomRunRequest,
        recordOutput: @escaping @Sendable () -> Void
    ) async throws -> String {
        let matchingBaseParams = request.baseParams.flatMap { params in
            params.model.modelId == route.model.modelId
                && route.providerSetting.id.description() == request.providerSetting.id.description()
                ? params : nil
        }
        let params = TextGenerationParams(
            model: route.model,
            temperature: KotlinFloat(value: 0),
            topP: nil,
            maxTokens: KotlinInt(value: 16),
            tools: [],
            reasoningLevel: .off,
            customHeaders: matchingBaseParams?.customHeaders ?? route.model.customHeaders,
            customBody: matchingBaseParams?.customBody ?? route.model.customBodies
        )
        return try await streamer.streamText(
            providerSetting: route.providerSetting,
            messages: [UIMessage.companion.user(prompt: "只回复 OK")],
            params: params,
            onUpdate: { text in
                if !text.isEmpty { recordOutput() }
            }
        )
    }

    private func stream(
        runGeneration: UInt64,
        speaker: IOSCouncilRoomSpeaker,
        systemPrompt: String,
        userPrompt: String,
        request: IOSCouncilRoomRunRequest,
        temperature: Float,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        try checkCancelled(runGeneration: runGeneration)
        let route = resolvedRoute(
            providerId: speaker.providerId,
            modelId: speaker.modelId,
            request: request
        )
        let params = makeTextGenerationParams(
            route: route,
            reasoning: speaker.reasoning,
            temperature: temperature,
            outputBudgetCharacters: request.settings.limits.outputBudgetCharacters,
            request: request
        )
        let messages = [
            UIMessage.companion.system(prompt: systemPrompt),
            UIMessage.companion.user(prompt: userPrompt)
        ]
        let text = try await streamer.streamText(
            providerSetting: route.providerSetting,
            messages: messages,
            params: params,
            onUpdate: onUpdate
        )
        try checkCancelled(runGeneration: runGeneration)
        return text
    }

    private func streamWithTimeout(
        runGeneration: UInt64,
        seconds: Int,
        timeoutLabel: String,
        operation: @escaping @Sendable (@escaping @Sendable () -> Void) async throws -> String
    ) async throws -> String {
        let timeoutNanoseconds = UInt64(max(1, seconds)) * timeoutUnitNanoseconds
        let pollIntervalNanoseconds = max(1, min(timeoutNanoseconds / 4, 250_000_000))
        let heartbeat = IOSCouncilStreamHeartbeat()
        return try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask {
                try await operation {
                    heartbeat.recordOutput()
                }
            }
            group.addTask {
                while true {
                    try await Task.sleep(nanoseconds: pollIntervalNanoseconds)
                    if heartbeat.hasBeenSilent(for: timeoutNanoseconds) {
                        throw IOSCouncilRoomRunnerError.timedOut(timeoutLabel)
                    }
                }
            }
            do {
                guard let result = try await group.next() else {
                    throw IOSCouncilRoomRunnerError.timedOut(timeoutLabel)
                }
                group.cancelAll()
                return result
            } catch {
                group.cancelAll()
                if activeRunGeneration == runGeneration {
                    streamer.cancel()
                }
                throw error
            }
        }
    }

    private func makeTextGenerationParams(
        route: IOSCouncilModelRoute,
        reasoning: IOSCouncilReasoningPreset,
        temperature: Float,
        outputBudgetCharacters: Int,
        request: IOSCouncilRoomRunRequest
    ) -> TextGenerationParams {
        let model = route.model
        let abilities = route.model.abilities
        let matchingBaseParams = request.baseParams.flatMap { params in
            params.model.modelId == model.modelId
                && route.providerSetting.id.description() == request.providerSetting.id.description()
                ? params : nil
        }
        let maxTokens = Int32(min(max(outputBudgetCharacters / 4, 512), 8_192))
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: temperature),
            topP: matchingBaseParams?.topP,
            maxTokens: KotlinInt(value: maxTokens),
            tools: [],
            reasoningLevel: abilities.contains(.reasoning) ? reasoning.reasoningLevel : .off,
            customHeaders: matchingBaseParams?.customHeaders ?? model.customHeaders,
            customBody: matchingBaseParams?.customBody ?? model.customBodies
        )
    }

    private func modelRoutes(for request: IOSCouncilRoomRunRequest) -> [IOSCouncilModelRoute] {
        var providers = request.providerSettings
        let currentProviderId = request.providerSetting.id.description()
        if !providers.contains(where: { $0.id.description() == currentProviderId }) {
            providers.insert(request.providerSetting, at: 0)
        }
        if providers.isEmpty {
            providers = [request.providerSetting]
        }

        var routes: [IOSCouncilModelRoute] = []
        var seen = Set<String>()
        if let currentModel = request.currentModel,
           ChatProviderConfiguration.issue(for: currentModel, provider: request.providerSetting) == nil {
            let route = IOSCouncilModelRoute(providerSetting: request.providerSetting, model: currentModel)
            seen.insert(Self.routeKey(route.descriptor))
            routes.append(route)
        }
        for configured in ChatProviderConfiguration.configuredChatModels(in: providers) {
            for model in configured.models {
                guard let provider = ChatProviderConfiguration.provider(for: model, providers: providers),
                      provider.enabled,
                      ChatProviderConfiguration.issue(for: model, provider: provider) == nil else { continue }
                let route = IOSCouncilModelRoute(providerSetting: provider, model: model)
                guard seen.insert(Self.routeKey(route.descriptor)).inserted else { continue }
                routes.append(route)
            }
        }
        return routes
    }

    private func resolvedRoute(
        providerId: String?,
        modelId: String,
        request: IOSCouncilRoomRunRequest,
        routes providedRoutes: [IOSCouncilModelRoute]? = nil
    ) -> IOSCouncilModelRoute {
        let routes = providedRoutes ?? modelRoutes(for: request)
        let currentProviderId = request.providerSetting.id.description()
        let normalizedModelId = modelId.trimmedNilIfBlank ?? request.currentModelId

        if let providerId = providerId?.trimmedNilIfBlank {
            if let exact = routes.first(where: {
                $0.providerSetting.id.description() == providerId && $0.model.modelId == normalizedModelId
            }) {
                return exact
            }
        } else {
            if let currentProviderMatch = routes.first(where: {
                $0.providerSetting.id.description() == currentProviderId && $0.model.modelId == normalizedModelId
            }) {
                return currentProviderMatch
            }
            if let matchingModel = routes.first(where: { $0.model.modelId == normalizedModelId }) {
                return matchingModel
            }
        }

        if let currentRoute = routes.first(where: {
            $0.providerSetting.id.description() == currentProviderId
                && $0.model.modelId == request.currentModelId
        }) {
            return currentRoute
        }
        return IOSCouncilModelRoute(
            providerSetting: request.providerSetting,
            model: resolvedModel(modelId: request.currentModelId, request: request)
        )
    }

    private static func routeKey(_ route: IOSCouncilModelRouteDescriptor) -> String {
        "\(route.providerId)\n\(route.modelId)"
    }

    private func resolvedModel(
        modelId: String,
        request: IOSCouncilRoomRunRequest
    ) -> Model {
        if let currentModel = request.currentModel,
           currentModel.modelId == modelId {
            return currentModel
        }
        if let configured = request.providerSetting.models.first(where: {
            $0.type == ModelType.chat && $0.modelId == modelId
        }) {
            return configured
        }
        let abilities = ModelRegistry.shared.MODEL_ABILITIES.getData(modelId: modelId) as? [ModelAbility] ?? []
        return Model(
            modelId: modelId,
            displayName: modelId,
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: abilities,
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
    }

    private func hostSystemPrompt(settings: IOSCouncilRoomSettings) -> String {
        """
        \(settings.host.prompt)

        你在 iOS 模型议会 Room 中发言。保持中文、具体、可执行。不要声称调用了跨服务商模型；本轮所有席位都运行在当前 OpenAI-compatible 服务商内。
        """
    }

    private func finalTopicPrompt(
        objective: String,
        research: IOSCouncilResearchBundle,
        sourceMaterials: String? = nil,
        limits: IOSCouncilRoomLimits,
        defaultSeats: [IOSCouncilRoomSpeaker]
    ) -> String {
        let materialsBlock = sourceMaterials?.trimmingCharacters(in: .whitespacesAndNewlines)
        let materialsSection: String
        if let materialsBlock, !materialsBlock.isEmpty {
            materialsSection = """

            用户上传材料（文件解析 / 图片视觉识别结果）：
            \(materialsBlock)
            """
        } else {
            materialsSection = ""
        }
        return """
        用户议题：
        \(objective)
        \(materialsSection)
        联网调研要点：
        \(research.summaryText.trimmedOr("本轮没有联网调研材料。"))

        请基于以上议题、上传材料（如有）与联网调研完善议题，补充关键背景和最新信息，形成一个清晰、有讨论价值的「最终议题」。
        若用户上传了材料，最终议题必须紧扣材料中的事实与争议点，不要忽略附件内容。
        直接输出完善后的议题正文即可，不要列席位、不要输出 JSON。
        """
    }

    private func seatPlanSystemPrompt() -> String {
        """
        你是 iOS 模型议会的主持人，负责根据具体议题和联网调研，动态组建本轮最有价值的议员席位。
        只输出严格 JSON，不要代码围栏、不要任何解释文字。
        """
    }

    private func seatPlanPrompt(
        objective: String,
        finalTopic: String,
        research: IOSCouncilResearchBundle,
        limits: IOSCouncilRoomLimits,
        sourceMaterials: String? = nil
    ) -> String {
        let materialsHint: String
        if let materials = sourceMaterials?.trimmingCharacters(in: .whitespacesAndNewlines),
           !materials.isEmpty {
            materialsHint = "用户已上传材料（文件/图片），席位应覆盖材料中的事实核验、影响面与决策维度。"
        } else {
            materialsHint = ""
        }
        return """
        最终议题：
        \(finalTopic.trimmedOr(objective))

        联网调研要点：
        \(research.summaryText.trimmedOr("（无联网调研材料，请基于议题本身判断）"))
        \(materialsHint.isEmpty ? "" : "\n\(materialsHint)\n")
        请针对这个【具体议题】动态设计 2 到 \(limits.maxSeats) 位最有价值的议员席位：
        - 紧扣本议题的真实关键维度，不要套用「工程/产品/风险」之类的通用模板，除非它们确实最贴切。
        - 每位议员视角独特、互补，合起来能覆盖议题的核心分歧与决策要点。
        - 议员简称 2-6 个字；职责用一句话写清它从什么角度、审视什么。

        只输出严格 JSON（不要代码围栏、不要解释）：
        {"seats":[{"name":"议员简称","lens":"该议员的具体职责与审视角度"}]}
        """
    }

    /// 清洗席位输出里模型幻觉出的伪联网搜索 / 工具调用文本。典型如 grok 在无可用工具时
    /// 把搜索冲动写成 `web_search / query … / num_results …`，并常被其包进误标的 ```html
    /// 围栏。终态与流式累积文本各调用一次；纯函数，便于单测。
    static func sanitizeSeatOutput(_ text: String) -> String {
        // 逐行状态机：围栏内整块缓冲，闭合时若含伪搜索则整块丢弃（含围栏标记），否则原样
        // 保留——正常代码块因此不受影响；围栏外逐行删裸的 web_search/query/num_results 行。
        // 刻意不用正则做条件删除，避开 NSRegularExpression 闭包重载与 NSRange 转换的坑。
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        var out: [String] = []
        var fenceBuffer: [String] = []
        var inFence = false
        // 围栏外裸行删除的上下文态：query / num_results 只有在「前序出现过裸 web_search 行」
        // 的连续伪搜索块内才删，避免误删英文正常发言里行首恰为 query 的合法句子。
        // 遇到非空普通行即复位（说明已脱离伪搜索块）；空行不复位。
        var sawBareWebSearch = false

        func isPseudoSearch(_ s: String) -> Bool {
            let lower = s.lowercased()
            return lower.contains("web_search") || lower.contains("num_results")
        }
        func flushFence() {
            if !isPseudoSearch(fenceBuffer.joined(separator: "\n")) {
                out.append(contentsOf: fenceBuffer)
            }
            fenceBuffer.removeAll()
        }

        for line in lines {
            let marker = line.trimmingCharacters(in: .whitespaces)
            if marker.hasPrefix("```") {
                fenceBuffer.append(line)
                if inFence {
                    flushFence()
                    inFence = false
                } else {
                    inFence = true
                }
                continue
            }
            if inFence {
                fenceBuffer.append(line)
                continue
            }
            let t = line.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let isWebSearchLine = t == "web_search" || t.hasPrefix("web_search ")
            let isQueryLine = t == "query" || t.hasPrefix("query ")
            let isNumResultsLine = t.hasPrefix("num_results")
            if isWebSearchLine {
                // web_search 是伪搜索块的锚点：删它并打开上下文，使紧随的 query/num_results
                // 行也被识别为同一伪搜索块而删除。
                sawBareWebSearch = true
                continue
            }
            if (isQueryLine || isNumResultsLine) && sawBareWebSearch {
                // 仅当处于伪搜索块上下文内才删 query/num_results 行；否则视为正常发言保留。
                continue
            }
            // 保留该行；非空普通行说明已脱离伪搜索块，复位上下文。
            if !t.isEmpty { sawBareWebSearch = false }
            out.append(line)
        }
        // 未闭合围栏（流式中围栏还没写完）：含伪搜索则暂不显示，否则保留。
        if inFence, !isPseudoSearch(fenceBuffer.joined(separator: "\n")) {
            out.append(contentsOf: fenceBuffer)
        }

        var result = out.joined(separator: "\n")
        while result.contains("\n\n\n") {
            result = result.replacingOccurrences(of: "\n\n\n", with: "\n\n")
        }
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func seatSystemPrompt(seat: IOSCouncilRoomSpeaker) -> String {
        """
        你是模型议会席位：\(seat.name)。
        你的职责：\(seat.rolePrompt)
        \(seat.prompt)

        在主持人组织下发言。不要自称主持人。输出要具体、短而有判断。

        重要：你没有联网或调用工具的能力，本轮也不会为你执行任何搜索。
        因此绝对不要输出 web_search、function call、tool use 之类的工具调用文本，
        不要写 "query … / num_results …" 这类搜索参数，也不要用代码围栏（如 ```html）
        去包裹任何"结构化"内容或搜索计划。请直接以自然语言给出你的判断与论据；
        若某点你不确定，用一句话说明不确定即可，不要假装去查。
        """
    }

    private func seatPrompt(
        objective: String,
        finalTopic: String,
        followUp: String?,
        mode: IOSCouncilRoomRunMode,
        round: Int,
        rounds: Int,
        transcript: [String],
        budget: Int,
        seatResearch: IOSCouncilResearchBundle = IOSCouncilResearchBundle(
            searches: [], scrapedPages: [], failures: []
        )
    ) -> String {
        let followUpSection = followUp.map {
            """

            用户本轮追问或补充：
            \($0)
            """
        } ?? ""
        // 席位发言前的联网查证材料（开关开启且本轮允许联网时由 runner 注入）；空则不渲染，
        // 保持与未启用时逐字一致的 prompt。标题用「本席联网查证」与主持的「联网调研要点」区分。
        let seatResearchSection = seatResearch.isEmpty
            ? ""
            : "\n\n本席联网查证要点（发言前为你查到的最新材料，可据此佐证，但勿照抄、勿编造其中没有的事实）：\n\(seatResearch.summaryText)"
        let instruction = followUp == nil
            ? "请从你的席位职责给出本轮发言。自由群聊要补充新角度；辩论模式要回应前文的核心判断、指出盲区或确认成立条件。"
            : "请从你的席位职责直接回应本轮追问，并结合已有讨论给出新增判断。自由群聊要补充新角度；辩论模式要回应前文的核心判断、指出盲区或确认成立条件。"
        return """
        原始议题：
        \(objective)

        主持人完善后的最终议题：
        \(finalTopic)
        \(followUpSection)\(seatResearchSection)

        模式：\(mode.title)
        轮次：\(round)/\(rounds)

        已有讨论：
        \(clippedTranscript(transcript, budget: max(2_000, budget / 2)))

        \(instruction)
        """
    }

    private func synthesisPrompt(
        objective: String,
        finalTopic: String,
        followUp: String?,
        transcript: [String],
        failedSeats: [String]
    ) -> String {
        let followUpSection = followUp.map {
            """

            用户本轮追问或补充：
            \($0)
            """
        } ?? ""
        let instruction = followUp == nil
            ? "请作为主持人给出结论：共识、分歧、风险、推荐决策和下一步。若有席位失败，明确说明结论的不确定性。"
            : "请作为主持人优先回答本轮追问，再给出更新后的共识、分歧、风险、推荐决策和下一步。若有席位失败，明确说明结论的不确定性。"
        return """
        原始议题：
        \(objective)

        最终议题：
        \(finalTopic)
        \(followUpSection)

        议会讨论：
        \(clippedTranscript(transcript, budget: 12_000))

        失败或缺席席位：
        \(failedSeats.isEmpty ? "无" : failedSeats.joined(separator: ", "))

        \(instruction)
        """
    }

    /// 主持人轮末点评 prompt：总结本轮发言，指出矛盾点和下一轮应聚焦的方向。
    static func roundEndCommentaryPrompt(
        objective: String,
        round: Int,
        rounds: Int,
        roundTranscript: String
    ) -> String {
        """
        原始议题：\(objective)

        当前轮次：第 \(round) 轮 / 共 \(rounds) 轮

        本轮发言：
        \(roundTranscript)

        请作为主持人点评本轮：1) 各席位观点的矛盾或共识；2) 下一轮应聚焦的核心问题或盲区。保持简短（150-250 字），不要重复各席位的原文。
        """
    }

    private func recordResearchConsent(_ consent: IOSCouncilResearchConsent, objective: String, runId: String) {
        guard consent == .allowed || consent == .denied else { return }
        let action: IOSToolApprovalAction = consent == .allowed ? .allowed : .denied
        let reason = consent == .allowed
            ? "Council Room research is enabled in the current settings."
            : "User denied Council Room host research for this run."
        for tool in ["search_web", "scrape_web"] {
            _ = permissionStore?.recordApproval(
                capabilityId: "ios.network.search_tools",
                toolName: tool,
                action: action,
                reason: reason,
                runId: runId,
                scopeDigest: "council_room",
                payloadDigest: Self.digest(objective)
            )
        }
    }

    private func checkCancelled(runGeneration: UInt64) throws {
        if activeRunGeneration != runGeneration || Task.isCancelled {
            if activeRunGeneration == runGeneration {
                streamer.cancel()
            }
            throw IOSCouncilRoomRunnerError.cancelled
        }
    }

    private func dividerMessage(_ body: String) -> IOSCouncilRoomMessageEvent {
        IOSCouncilRoomMessageEvent(
            id: UUID(),
            kind: .divider,
            speakerId: nil,
            author: "议会",
            body: body,
            subtitle: nil,
            status: .completed
        )
    }

    private func systemMessage(body: String, subtitle: String, status: IOSCouncilRoomMessageStatus) -> IOSCouncilRoomMessageEvent {
        IOSCouncilRoomMessageEvent(
            id: UUID(),
            kind: .system,
            speakerId: nil,
            author: "议会",
            body: body,
            subtitle: subtitle,
            status: status
        )
    }

    private func clippedTranscript(_ transcript: [String], budget: Int) -> String {
        let text = transcript.joined(separator: "\n\n")
        guard text.count > budget else { return text }
        return String(text.suffix(budget))
    }

    private static func digest(_ value: String) -> String {
        var hash: UInt64 = 14_695_981_039_346_656_037
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1_099_511_628_211
        }
        return String(hash, radix: 16)
    }
}

private extension IOSCouncilRoomRunMode {
    var title: String {
        switch self {
        case .freeChat: "自由群聊"
        case .debate: "辩论"
        }
    }
}

private extension String {
    func trimmedOr(_ fallback: String) -> String {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }

    var trimmedNilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// Chat-tool entry point for the iOS Council Room. Formal runs use
/// `IOSCouncilRoomRunner` so iOS can stream the host and seats as a real room
/// without pretending to support cross-provider model mixing.
@MainActor
@Observable
final class CouncilRunner {
    @ObservationIgnored private let taskStore: IOSAdvancedTaskStore
    @ObservationIgnored private let roomSettingsStore: IOSCouncilRoomSettingsStore
    @ObservationIgnored private let roomStreamer: any IOSCouncilTextStreaming

    var lastRunResult: String = "(未运行)"
    var isRunning: Bool = false
    var lastTask: IOSAdvancedTaskRecord?

    init(
        taskStore: IOSAdvancedTaskStore = .shared,
        roomSettingsStore: IOSCouncilRoomSettingsStore = .shared,
        roomStreamer: any IOSCouncilTextStreaming = IOSCouncilTextStreamer()
    ) {
        self.taskStore = taskStore
        self.roomSettingsStore = roomSettingsStore
        self.roomStreamer = roomStreamer
    }

    var recentTasks: [IOSAdvancedTaskRecord] {
        taskStore.recent(kind: .modelCouncil, limit: 5)
    }

    /// Run an input-driven Council Room cycle for the chat-tool dispatch path.
    /// Failures return an honest JSON status — never empty/fabricated success.
    func run(
        objective: String,
        seats: [IOSCouncilSeatDescriptor] = [],
        maxSeats: Int? = nil,
        outputBudgetChars: Int? = nil,
        providerSetting: ProviderSetting,
        currentModel: Model,
        baseParams: TextGenerationParams
    ) async -> String {
        let currentModelId = currentModel.modelId
        var roomSettings = roomSettingsStore.settings.normalized(currentModelId: currentModelId)
        let seatLimit = max(2, min(maxSeats ?? roomSettings.limits.maxSeats, 8))
        if !seats.isEmpty {
            roomSettings.seats = Array(seats.filter { $0.id != "host" }.prefix(seatLimit)).map {
                IOSCouncilRoomSeatConfig(
                    id: $0.id,
                    name: $0.name,
                    rolePrompt: $0.role,
                    modelId: $0.modelLabel == "当前模型" ? currentModelId : $0.modelLabel,
                    reasoning: .off,
                    prompt: "",
                    isDefault: true
                )
            }
        }
        roomSettings.limits.maxSeats = seatLimit
        if let outputBudgetChars {
            roomSettings.limits.outputBudgetCharacters = outputBudgetChars
        }
        roomSettings = roomSettings.normalized(currentModelId: currentModelId)
        isRunning = true
        defer { isRunning = false }

        let request = IOSCouncilRoomRunRequest(
            objective: objective,
            mode: .freeChat,
            settings: roomSettings,
            currentModelId: currentModelId,
            currentModel: currentModel,
            baseParams: baseParams,
            providerSetting: providerSetting,
            providerSettings: [providerSetting],
            searchSettings: nil,
            researchConsent: .unavailable,
            dynamicSeatGeneration: seats.isEmpty ? roomSettingsStore.dynamicSeatGeneration : false
        )
        let roomRunner = IOSCouncilRoomRunner(streamer: roomStreamer, taskStore: taskStore)
        let outcome = await roomRunner.run(request: request)
        lastTask = taskStore.tasks.first { $0.id == outcome.taskId }
        let runSummary = outcome.status == .completed
            ? "模型议会已完成，席位：\(outcome.seatNames.joined(separator: ", "))。"
            : "模型议会未完成：\(outcome.status.title)。"
        lastRunResult = "\(runSummary) taskId: \(outcome.taskId)，provider: \(providerSetting.name)。"
        var result: [String: Any] = [
            "ok": outcome.status == .completed,
            "task_id": outcome.taskId,
            "kind": IOSAdvancedTaskKind.modelCouncil.rawValue,
            "run_id": outcome.taskId,
            "status": outcome.status.rawValue,
            "mode": IOSCouncilRoomRunMode.freeChat.rawValue,
            "seat_count": outcome.seatNames.count,
            "seats": outcome.seatNames,
            "failed_seats": outcome.failedSeats,
            "budget_chars": roomSettings.limits.outputBudgetCharacters,
            "summary": lastRunResult
        ]
        if outcome.status == .completed {
            result["final_answer"] = outcome.finalAnswer
        } else {
            result["reason"] = outcome.failureReason ?? outcome.status.title
        }
        return Self.json(result)
    }

    private static func defaultSeatDescriptors() -> [IOSCouncilSeatDescriptor] {
        [
            IOSCouncilSeatDescriptor(id: "host", name: "Host", role: "主持、串联、综合", modelLabel: "当前模型"),
            IOSCouncilSeatDescriptor(id: "risk", name: "Risk", role: "风险与失败模式", modelLabel: "当前模型"),
            IOSCouncilSeatDescriptor(id: "opponent", name: "Opponent", role: "反方质询", modelLabel: "当前模型")
        ]
    }

    #if DEBUG
    /// Test accessor for the default seat roster.
    static func defaultSeatDescriptorsForTesting() -> [IOSCouncilSeatDescriptor] {
        defaultSeatDescriptors()
    }
    #endif

    private static func json(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return String(describing: object)
        }
        return text
    }
}
