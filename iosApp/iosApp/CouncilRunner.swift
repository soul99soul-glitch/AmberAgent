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
    let reasoning: IOSCouncilReasoningPreset
    let prompt: String
    let isHost: Bool

    var shortLens: String {
        rolePrompt.components(separatedBy: "，").first?.trimmedNilIfBlank ?? rolePrompt
    }
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
    /// 主持人/任意阶段向用户提问，暂停议会等用户回答。
    case askUser(question: String)
}

struct IOSCouncilRoomRunRequest {
    let objective: String
    let mode: IOSCouncilRoomRunMode
    let settings: IOSCouncilRoomSettings
    let currentModelId: String
    var currentModel: Model? = nil
    var baseParams: TextGenerationParams? = nil
    let providerSetting: ProviderSetting
    let searchSettings: Settings?
    let researchConsent: IOSCouncilResearchConsent
    /// 开:主持人按议题自由动态生成席位;关:只用 settings.seats 里已添加的席位。
    var dynamicSeatGeneration: Bool = false
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

@MainActor
final class IOSCouncilRoomRunner {
    private let streamer: any IOSCouncilTextStreaming
    private let researcher: any IOSCouncilResearching
    private let taskStore: IOSAdvancedTaskStore
    private let permissionStore: IOSPermissionStore?
    private let timeoutUnitNanoseconds: UInt64
    private var runGeneration: UInt64 = 0
    private var activeRunGeneration: UInt64?

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
        streamer.cancel()
    }

    func run(
        request: IOSCouncilRoomRunRequest,
        onEvent: @escaping @MainActor (IOSCouncilRoomEvent) -> Void = { _ in },
        onAskUser: ((String) async -> String?)? = nil
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
            }
        }
        let objective = request.objective.trimmingCharacters(in: .whitespacesAndNewlines)
        let settings = request.settings.normalized(currentModelId: request.currentModelId)
        let limits = settings.limits.normalized()

        // 当前 provider 实际支持的 chat 模型。席位模型不在其中（或为空/历史默认值如
        // "gpt-4o"）时回退到主持人正在用的当前模型，避免把不支持的 model 发给只认
        // 自家模型的 provider 导致 HTTP 400「Not supported model」。
        let supportedModelIds = Set(request.providerSetting.models
            .filter { $0.type == ModelType.chat }
            .map { $0.modelId.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty })
        func resolveSeatModel(_ modelId: String) -> String {
            let trimmed = modelId.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty || supportedModelIds.isEmpty || !supportedModelIds.contains(trimmed) {
                return request.currentModelId
            }
            return trimmed
        }
        let task = taskStore.startTask(
            kind: .modelCouncil,
            title: "\(request.mode.title) · \(objective.prefix(34))",
            objective: objective,
            toolScope: request.researchConsent == .allowed ? ["search_web", "scrape_web"] : [],
            budgetSummary: "mode \(request.mode.rawValue) · max seats \(limits.maxSeats) · rounds \(limits.defaultRounds) · budget \(limits.outputBudgetCharacters) chars",
            sourceToolName: "council_room",
            metadata: [
                "room_mode": request.mode.rawValue,
                "host_model": settings.host.modelId,
                "research_consent": request.researchConsent.rawValue
            ]
        )
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

        let validationModel = resolvedModel(modelId: request.currentModelId, request: request)
        if let issue = ChatProviderConfiguration.issue(
            for: validationModel,
            provider: request.providerSetting
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

        let host = IOSCouncilRoomSpeaker(
            id: "host",
            name: "主持人",
            rolePrompt: settings.host.prompt,
            modelId: resolveSeatModel(settings.host.modelId),
            reasoning: settings.host.reasoning,
            prompt: settings.host.prompt,
            isHost: true
        )
        // 静态默认席位仅作为「主持人动态规划失败」时的兜底；模型一律走 resolveSeatModel。
        var activeSeats = settings.defaultSeats(currentModelId: request.currentModelId).map { seat in
            IOSCouncilRoomSpeaker(
                id: seat.id,
                name: seat.name,
                rolePrompt: seat.rolePrompt,
                modelId: resolveSeatModel(seat.modelId),
                reasoning: seat.reasoning,
                prompt: seat.prompt,
                isHost: false
            )
        }
        // 兜底注入内置默认席位,只在以下两种情况:动态生成开(这只是占位,稍后会被主持人
        // 动态规划替换);或用户一个席位都没配(否则会变成只有主持人的空议会)。动态关 + 用户
        // 已添加 ≥1 个席位时,尊重用户配置,不再塞入无关的默认人设(honor「只用已添加的席位」)。
        if activeSeats.count < 2, request.dynamicSeatGeneration || activeSeats.isEmpty {
            activeSeats = Array(IOSCouncilRoomSettings.defaults(currentModelId: request.currentModelId)
                .defaultSeats(currentModelId: request.currentModelId)
                .prefix(limits.maxSeats)
                .map {
                    IOSCouncilRoomSpeaker(
                        id: $0.id,
                        name: $0.name,
                        rolePrompt: $0.rolePrompt,
                        modelId: resolveSeatModel($0.modelId),
                        reasoning: $0.reasoning,
                        prompt: $0.prompt,
                        isHost: false
                    )
                })
        }

        var transcript: [String] = []
        var failedSeatIds = Set<String>()
        onEvent(.roster([host] + activeSeats, activeSpeakerId: nil, failedSpeakerIds: failedSeatIds))
        onEvent(.state("主持调研中"))
        onEvent(.append(dividerMessage("主持调研")))

        let research: IOSCouncilResearchBundle
        if request.researchConsent == .allowed {
            research = await researcher.research(
                objective: objective,
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
        taskStore.appendLog(id: task.id, chunk: "research:\n\(research.summaryText)\n\n")

        do {
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
            let finalTopic = try await streamWithTimeout(
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
            guard let finalTopic = finalTopic.trimmedNilIfBlank else {
                onEvent(.updateMessage(
                    id: topicMessageId,
                    body: "主持人未返回议题。",
                    status: .failed
                ))
                throw IOSCouncilRoomRunnerError.emptyOutput("最终议题")
            }
            try checkCancelled(runGeneration: currentRunGeneration)
            onEvent(.updateMessage(id: topicMessageId, body: finalTopic, status: .completed))
            transcript.append("[\(host.name)] \(finalTopic)")
            taskStore.appendLog(id: task.id, chunk: "[\(host.name)] \(finalTopic)\n\n")

            // 开关「动态席位生成」开 → 主持人按议题 + 调研单独输出一份严格 JSON 席位清单
            //（Android planned_seats 思路），贴合本议题;关 → 直接用用户已添加的席位,不自由发挥。
            if request.dynamicSeatGeneration {
                onEvent(.state("组建议员席位中"))
                let seatPlanRaw = (try? await streamWithTimeout(
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
                            limits: limits
                        ),
                        request: request,
                        temperature: 0.3,
                        onUpdate: { text in
                            if !text.isEmpty { recordOutput() }
                        }
                    )
                }) ?? ""
                try checkCancelled(runGeneration: currentRunGeneration)
                let dynamicSeats = Self.plannedSeatsFromJSON(
                    seatPlanRaw,
                    maxSeats: limits.maxSeats,
                    modelId: host.modelId
                )
                if dynamicSeats.count >= 2 { activeSeats = dynamicSeats }
                onEvent(.append(dividerMessage("已组建 \(activeSeats.count) 位议员：\(activeSeats.map(\.name).joined(separator: "、"))")))
            } else {
                onEvent(.append(dividerMessage("本轮议员（已添加席位）：\(activeSeats.map(\.name).joined(separator: "、"))")))
            }
            onEvent(.roster([host] + activeSeats, activeSpeakerId: nil, failedSpeakerIds: failedSeatIds))
            onEvent(.append(dividerMessage(request.mode == .debate ? "辩论开始" : "自由群聊开始")))

            // freeChat 用单轮（host 议题 + 各席位 + host 总结 = 一轮）；debate 用
            // defaultRounds（normalized 限制 1-5 轮）。parity 修复：避免 freeChat 重复
            // 跑席位导致消息翻倍。
            let rounds = request.mode == .freeChat ? 1 : limits.defaultRounds
            for round in 1...rounds {
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
                                    objective: objective,
                                    finalTopic: finalTopic,
                                    mode: request.mode,
                                    round: round,
                                    rounds: rounds,
                                    transcript: transcriptSnapshot,
                                    budget: limits.outputBudgetCharacters
                                ),
                                request: request,
                                temperature: request.mode == .debate ? 0.55 : 0.75,
                                onUpdate: { text in
                                    if !text.isEmpty { recordOutput() }
                                    let body = text.isEmpty ? "思考中..." : text
                                    if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                        latestMessage.body = text
                                    }
                                    onEvent(.updateMessage(id: messageId, body: body, status: .speaking))
                                }
                            )
                        }
                        guard let output = output.trimmedNilIfBlank else {
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
                if round < rounds {
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
                                    objective: objective,
                                    round: round,
                                    rounds: rounds,
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
                        objective: objective,
                        finalTopic: finalTopic,
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
            onEvent(.state(isCancel ? "已取消" : (isTimeout ? "已超时" : "失败")))
            onEvent(.append(systemMessage(
                body: message,
                subtitle: isCancel ? "已取消" : (isTimeout ? "已超时" : "运行失败"),
                status: .failed
            )))
            _ = taskStore.updateTask(
                id: task.id,
                status: status,
                resultSummary: message,
                logTail: clippedTranscript(transcript, budget: 24_000),
                error: isCancel ? "" : message,
                retryable: true,
                cancelCapability: false
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
    /// dynamic council speakers, all running on the host's working model. Tolerates
    /// ```json fences / surrounding prose (reuses the deep-read balanced-brace
    /// extractor). Returns [] when fewer than 2 usable seats parse, so the caller
    /// keeps the resolved default seats as a fallback.
    static func plannedSeatsFromJSON(
        _ text: String,
        maxSeats: Int,
        modelId: String
    ) -> [IOSCouncilRoomSpeaker] {
        guard let json = IOSDeepReadDraftGenerator.extractJSONObject(text),
              let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let seats = obj["seats"] as? [[String: Any]] else {
            return []
        }
        var result: [IOSCouncilRoomSpeaker] = []
        var seenNames = Set<String>()
        for entry in seats {
            guard let rawName = entry["name"] as? String else { continue }
            let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, seenNames.insert(name.lowercased()).inserted else { continue }
            let lensRaw = (entry["lens"] as? String) ?? (entry["role"] as? String) ?? (entry["prompt"] as? String) ?? ""
            let lens = lensRaw.trimmingCharacters(in: .whitespacesAndNewlines)
            result.append(IOSCouncilRoomSpeaker(
                id: "planned-\(Self.digest(name).prefix(8))",
                name: name,
                rolePrompt: lens.isEmpty ? "围绕议题提供独立、专业的视角。" : lens,
                modelId: modelId,
                reasoning: .medium,
                prompt: "",
                isHost: false
            ))
            if result.count >= maxSeats { break }
        }
        return result.count >= 2 ? result : []
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
        let params = makeTextGenerationParams(
            modelId: speaker.modelId,
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
            providerSetting: request.providerSetting,
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
        modelId: String,
        reasoning: IOSCouncilReasoningPreset,
        temperature: Float,
        outputBudgetCharacters: Int,
        request: IOSCouncilRoomRunRequest
    ) -> TextGenerationParams {
        let model = resolvedModel(modelId: modelId, request: request)
        let abilities = model.abilities
        let matchingBaseParams = request.baseParams.flatMap { params in
            params.model.modelId == modelId ? params : nil
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
        limits: IOSCouncilRoomLimits,
        defaultSeats: [IOSCouncilRoomSpeaker]
    ) -> String {
        """
        用户议题：
        \(objective)

        联网调研要点：
        \(research.summaryText.trimmedOr("本轮没有联网调研材料。"))

        请基于以上调研完善议题、补充关键背景和最新信息，形成一个清晰、有讨论价值的「最终议题」。
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
        limits: IOSCouncilRoomLimits
    ) -> String {
        """
        最终议题：
        \(finalTopic.trimmedOr(objective))

        联网调研要点：
        \(research.summaryText.trimmedOr("（无联网调研材料，请基于议题本身判断）"))

        请针对这个【具体议题】动态设计 2 到 \(limits.maxSeats) 位最有价值的议员席位：
        - 紧扣本议题的真实关键维度，不要套用「工程/产品/风险」之类的通用模板，除非它们确实最贴切。
        - 每位议员视角独特、互补，合起来能覆盖议题的核心分歧与决策要点。
        - 议员简称 2-6 个字；职责用一句话写清它从什么角度、审视什么。

        只输出严格 JSON（不要代码围栏、不要解释）：
        {"seats":[{"name":"议员简称","lens":"该议员的具体职责与审视角度"}]}
        """
    }

    private func seatSystemPrompt(seat: IOSCouncilRoomSpeaker) -> String {
        """
        你是模型议会席位：\(seat.name)。
        你的职责：\(seat.rolePrompt)
        \(seat.prompt)

        在主持人组织下发言。不要自称主持人。输出要具体、短而有判断。
        """
    }

    private func seatPrompt(
        objective: String,
        finalTopic: String,
        mode: IOSCouncilRoomRunMode,
        round: Int,
        rounds: Int,
        transcript: [String],
        budget: Int
    ) -> String {
        """
        原始议题：
        \(objective)

        主持人完善后的最终议题：
        \(finalTopic)

        模式：\(mode.title)
        轮次：\(round)/\(rounds)

        已有讨论：
        \(clippedTranscript(transcript, budget: max(2_000, budget / 2)))

        请从你的席位职责给出本轮发言。自由群聊要补充新角度；辩论模式要回应前文的核心判断、指出盲区或确认成立条件。
        """
    }

    private func synthesisPrompt(
        objective: String,
        finalTopic: String,
        transcript: [String],
        failedSeats: [String]
    ) -> String {
        """
        原始议题：
        \(objective)

        最终议题：
        \(finalTopic)

        议会讨论：
        \(clippedTranscript(transcript, budget: 12_000))

        失败或缺席席位：
        \(failedSeats.isEmpty ? "无" : failedSeats.joined(separator: ", "))

        请作为主持人给出结论：共识、分歧、风险、推荐决策和下一步。若有席位失败，明确说明结论的不确定性。
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

    /// 主持人向用户提问。发出 `.askUser` 事件，暂停议会，等用户回答。
    /// 返回用户的回答；若用户跳过或取消则返回 nil（议会继续，视为"无补充"）。
    @MainActor
    private func askUser(
        _ question: String,
        onEvent: @escaping @MainActor (IOSCouncilRoomEvent) -> Void,
        onAskUser: ((String) async -> String?)?
    ) async -> String? {
        guard let onAskUser else { return nil }
        onEvent(.askUser(question: question))
        return await onAskUser(question)
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
    @ObservationIgnored private var manager: ModelCouncilManager?
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

    private func ensureManager() -> ModelCouncilManager? {
        if let m = manager { return m }
        guard let docsDir = try? FileManager.default.url(
            for: .documentDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        ).path else { return nil }

        // Try real provider first (needs valid API key from SettingsStore).
        // If no key configured, fall back to stub for chain validation.
        let store = SettingsStore()
        let baseUrl = store.baseUrl
        let apiKey = store.currentApiKey
        let modelId = store.modelId

        let m: ModelCouncilManager
        if !apiKey.isEmpty {
            m = IosCouncilFactory.shared.createWithRealProvider(
                documentsDir: docsDir,
                baseUrl: baseUrl,
                apiKey: apiKey,
                modelId: modelId
            )
        } else {
            m = IosCouncilFactory.shared.create(documentsDir: docsDir)
        }
        manager = m
        return m
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

    func runTestCycle() {
        guard let m = ensureManager() else {
            lastRunResult = "无法构造 Manager（文档目录不可用）"
            return
        }
        isRunning = true
        lastRunResult = "正在启动…"

        let input = IosCouncilFactory.shared.startInput(objective: "试运行模型议会")

        m.start(input: input) { [weak self] result, error in
            let summary: String
            if let error = error {
                summary = "start 错误: \(error.localizedDescription)"
            } else if let result = result {
                let runId = IosCouncilFactory.shared.extractRunId(result: result)
                let status = IosCouncilFactory.shared.extractStatus(result: result)
                summary = "模型议会已完成试运行\nrunId: \(runId)\nstatus: \(status)\n\n配置 API Key 后会使用真实模型生成。"
            } else {
                summary = "start 返回空结果"
            }
            DispatchQueue.main.async {
                guard let self else { return }
                self.isRunning = false
                self.lastRunResult = summary
            }
        }
    }

    private func mapStatus(_ status: String) -> IOSAdvancedTaskStatus {
        let normalized = status.lowercased()
        if normalized.contains("completed") || normalized.contains("partial_failed") { return .completed }
        if normalized.contains("cancel") { return .cancelled }
        if normalized.contains("timed") || normalized.contains("timeout") { return .timedOut }
        if normalized.contains("interrupt") { return .interrupted }
        if normalized.contains("error") || normalized.contains("fail") { return .failed }
        if normalized.contains("running") { return .running }
        return normalized == "(unknown)" ? .failed : .completed
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
