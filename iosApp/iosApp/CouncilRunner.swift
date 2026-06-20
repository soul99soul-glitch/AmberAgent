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
            defaultRounds: min(max(defaultRounds, 1), 6),
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
}

struct IOSCouncilRoomRunRequest {
    let objective: String
    let mode: IOSCouncilRoomRunMode
    let settings: IOSCouncilRoomSettings
    let currentModelId: String
    let providerSetting: ProviderSetting.OpenAI
    let searchSettings: Settings?
    let researchConsent: IOSCouncilResearchConsent
}

struct IOSCouncilRoomRunSummary: Equatable {
    let taskId: String
    let status: IOSAdvancedTaskStatus
    let finalTopic: String
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
        maxSearches: Int = 2,
        maxScrapes: Int = 2
    ) async -> IOSCouncilResearchBundle {
        let trimmed = objective.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return IOSCouncilResearchBundle(searches: [], scrapedPages: [], failures: ["empty objective"])
        }

        let queries = Array([
            trimmed,
            "\(trimmed) 最新 信息"
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
        providerSetting: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String

    func cancel()
}

@MainActor
final class IOSCouncilTextStreamer: IOSCouncilTextStreaming {
    private let provider: OpenAIKmpProvider
    private var job: Kotlinx_coroutines_coreJob?

    init(provider: OpenAIKmpProvider = OpenAIKmpProvider()) {
        self.provider = provider
    }

    func streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        let accumulator = MessageStreamAccumulator(initialMessages: messages, model: params.model)
        return await withCheckedContinuation { continuation in
            var didResume = false
            func resumeOnce(_ value: String) {
                guard !didResume else { return }
                didResume = true
                continuation.resume(returning: value)
            }

            job = provider.streamTextCancellable(
                providerSetting: providerSetting,
                messages: messages,
                params: params,
                onChunk: { chunk in
                    accumulator.append(chunk: chunk)
                    let text = accumulator.snapshot().last?.toText() ?? ""
                    Task { @MainActor in onUpdate(text) }
                },
                onComplete: {
                    let text = accumulator.snapshot().last?.toText() ?? ""
                    Task { @MainActor in resumeOnce(text) }
                },
                onError: { error in
                    Task { @MainActor in
                        resumeOnce("Error: \(error.message ?? String(describing: error))")
                    }
                }
            )
        }
    }

    func cancel() {
        job?.cancel(cause: nil)
        job = nil
    }

    deinit {
        job?.cancel(cause: nil)
    }
}

enum IOSCouncilRoomRunnerError: LocalizedError, Equatable {
    case missingAPIKey
    case emptyObjective
    case hostFailed(String)
    case timedOut(String)
    case cancelled

    var errorDescription: String? {
        switch self {
        case .missingAPIKey: "当前服务商缺少 API Key。"
        case .emptyObjective: "议题为空。"
        case .hostFailed(let message): "主持人生成失败：\(message)"
        case .timedOut(let speaker): "\(speaker) 发言超时。"
        case .cancelled: "模型议会已取消。"
        }
    }
}

@MainActor
final class IOSCouncilRoomRunner {
    private let streamer: any IOSCouncilTextStreaming
    private let researcher: any IOSCouncilResearching
    private let taskStore: IOSAdvancedTaskStore
    private let permissionStore: IOSPermissionStore?
    private var isCancelled = false

    init(
        streamer: any IOSCouncilTextStreaming = IOSCouncilTextStreamer(),
        researcher: any IOSCouncilResearching = IOSCouncilSearchResearcher(),
        taskStore: IOSAdvancedTaskStore = .shared,
        permissionStore: IOSPermissionStore? = nil
    ) {
        self.streamer = streamer
        self.researcher = researcher
        self.taskStore = taskStore
        self.permissionStore = permissionStore
    }

    func cancel() {
        isCancelled = true
        streamer.cancel()
    }

    func run(
        request: IOSCouncilRoomRunRequest,
        onEvent: @escaping @MainActor (IOSCouncilRoomEvent) -> Void = { _ in }
    ) async -> IOSCouncilRoomRunSummary {
        isCancelled = false
        let objective = request.objective.trimmingCharacters(in: .whitespacesAndNewlines)
        let settings = request.settings.normalized(currentModelId: request.currentModelId)
        let limits = settings.limits.normalized()
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
            _ = taskStore.updateTask(
                id: task.id,
                status: .failed,
                resultSummary: IOSCouncilRoomRunnerError.emptyObjective.localizedDescription,
                retryable: true,
                cancelCapability: false
            )
            return IOSCouncilRoomRunSummary(taskId: task.id, status: .failed, finalTopic: "", failedSeats: [], transcript: "")
        }

        guard !request.providerSetting.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            let message = IOSCouncilRoomRunnerError.missingAPIKey.localizedDescription
            onEvent(.append(systemMessage(body: message, subtitle: "配置阻塞", status: .failed)))
            _ = taskStore.updateTask(
                id: task.id,
                status: .failed,
                resultSummary: message,
                error: message,
                retryable: true,
                cancelCapability: false
            )
            return IOSCouncilRoomRunSummary(taskId: task.id, status: .failed, finalTopic: "", failedSeats: [], transcript: message)
        }

        recordResearchConsent(request.researchConsent, objective: objective, runId: task.id)

        let host = IOSCouncilRoomSpeaker(
            id: "host",
            name: "主持人",
            rolePrompt: settings.host.prompt,
            modelId: settings.host.modelId,
            reasoning: settings.host.reasoning,
            prompt: settings.host.prompt,
            isHost: true
        )
        var activeSeats = settings.defaultSeats(currentModelId: request.currentModelId).map { seat in
            IOSCouncilRoomSpeaker(
                id: seat.id,
                name: seat.name,
                rolePrompt: seat.rolePrompt,
                modelId: seat.modelId,
                reasoning: seat.reasoning,
                prompt: seat.prompt,
                isHost: false
            )
        }
        if activeSeats.count < 2 {
            activeSeats = Array(IOSCouncilRoomSettings.defaults(currentModelId: request.currentModelId)
                .defaultSeats(currentModelId: request.currentModelId)
                .prefix(limits.maxSeats)
                .map {
                    IOSCouncilRoomSpeaker(
                        id: $0.id,
                        name: $0.name,
                        rolePrompt: $0.rolePrompt,
                        modelId: $0.modelId,
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
                maxSearches: 2,
                maxScrapes: 2
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
            let finalTopic = try await stream(
                speaker: host,
                systemPrompt: hostSystemPrompt(settings: settings),
                userPrompt: finalTopicPrompt(objective: objective, research: research, limits: limits, defaultSeats: activeSeats),
                request: request,
                temperature: 0.35,
                onUpdate: { text in
                    onEvent(.updateMessage(id: topicMessageId, body: text.isEmpty ? "调研和完善议题中..." : text, status: .speaking))
                }
            )
            try checkCancelled()
            onEvent(.updateMessage(id: topicMessageId, body: finalTopic.trimmedOr("主持人未返回议题。"), status: .completed))
            transcript.append("[\(host.name)] \(finalTopic)")
            taskStore.appendLog(id: task.id, chunk: "[\(host.name)] \(finalTopic)\n\n")

            let plannedSeats = Self.plannedSeats(
                from: finalTopic,
                fallback: activeSeats,
                maxSeats: limits.maxSeats,
                currentModelId: request.currentModelId
            )
            activeSeats = plannedSeats
            onEvent(.roster([host] + activeSeats, activeSpeakerId: nil, failedSpeakerIds: failedSeatIds))
            onEvent(.append(dividerMessage(request.mode == .debate ? "辩论开始" : "自由群聊开始")))

            let rounds = request.mode == .debate ? limits.defaultRounds : 1
            for round in 1...rounds {
                try checkCancelled()
                if request.mode == .debate {
                    onEvent(.append(dividerMessage("第 \(round) 轮")))
                }
                for seat in activeSeats where !failedSeatIds.contains(seat.id) {
                    try checkCancelled()
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
                    do {
                        let transcriptSnapshot = transcript
                        let output = try await streamWithTimeout(
                            seconds: limits.seatTimeoutSeconds,
                            timeoutLabel: seat.name
                        ) {
                            try await self.stream(
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
                                    onEvent(.updateMessage(id: messageId, body: text.isEmpty ? "思考中..." : text, status: .speaking))
                                }
                            )
                        }
                        onEvent(.updateMessage(id: messageId, body: output.trimmedOr("没有输出。"), status: .completed))
                        transcript.append("[\(seat.name)] \(output)")
                        taskStore.appendLog(id: task.id, chunk: "[\(seat.name)] \(output)\n\n")
                    } catch {
                        let reason = error.localizedDescription
                        failedSeatIds.insert(seat.id)
                        onEvent(.updateMessage(id: messageId, body: "席位失败：\(reason)", status: .failed))
                        onEvent(.roster([host] + activeSeats, activeSpeakerId: nil, failedSpeakerIds: failedSeatIds))
                        taskStore.appendLog(id: task.id, chunk: "[\(seat.name)] failed: \(reason)\n\n")
                    }
                }
            }

            try checkCancelled()
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
            let summary = try await stream(
                speaker: host,
                systemPrompt: hostSystemPrompt(settings: settings),
                userPrompt: synthesisPrompt(
                    objective: objective,
                    finalTopic: finalTopic,
                    transcript: transcript,
                    failedSeats: activeSeats.filter { failedSeatIds.contains($0.id) }.map(\.name)
                ),
                request: request,
                temperature: 0.35,
                onUpdate: { text in
                    onEvent(.updateMessage(id: summaryId, body: text.isEmpty ? "总结中..." : text, status: .speaking))
                }
            )
            onEvent(.updateMessage(id: summaryId, body: summary.trimmedOr("主持人未返回总结。"), status: .completed))
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
                failedSeats: failedNames,
                transcript: finalTranscript
            )
        } catch {
            let isCancel = isCancelled || error is CancellationError || (error as? IOSCouncilRoomRunnerError) == .cancelled
            let status: IOSAdvancedTaskStatus = isCancel ? .cancelled : .failed
            let message = isCancel ? "模型议会已取消。" : error.localizedDescription
            onEvent(.state(isCancel ? "已取消" : "失败"))
            onEvent(.append(systemMessage(body: message, subtitle: isCancel ? "已取消" : "运行失败", status: .failed)))
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
                failedSeats: [],
                transcript: clippedTranscript(transcript, budget: 24_000)
            )
        }
    }

    static func makeProviderSetting(baseUrl: String, apiKey: String) -> ProviderSetting.OpenAI {
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

    static func plannedSeats(
        from text: String,
        fallback: [IOSCouncilRoomSpeaker],
        maxSeats: Int,
        currentModelId: String
    ) -> [IOSCouncilRoomSpeaker] {
        let parsed = text
            .components(separatedBy: .newlines)
            .compactMap { raw -> IOSCouncilRoomSpeaker? in
                let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                guard line.lowercased().hasPrefix("席位:") || line.lowercased().hasPrefix("seat:") else { return nil }
                let body = line.drop { $0 != ":" }.dropFirst().trimmingCharacters(in: .whitespacesAndNewlines)
                let parts = body.components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                guard let name = parts.first?.trimmedNilIfBlank else { return nil }
                let role = parts.dropFirst().first?.trimmedNilIfBlank ?? "围绕议题提供独立视角。"
                let matched = fallback.first { $0.name.caseInsensitiveCompare(name) == .orderedSame }
                return IOSCouncilRoomSpeaker(
                    id: matched?.id ?? "planned-\(Self.digest(name).prefix(8))",
                    name: name,
                    rolePrompt: matched?.rolePrompt ?? role,
                    modelId: matched?.modelId ?? currentModelId,
                    reasoning: matched?.reasoning ?? .medium,
                    prompt: matched?.prompt ?? "",
                    isHost: false
                )
            }
        let capped = Array(parsed.prefix(maxSeats))
        guard capped.count >= 2 else { return Array(fallback.prefix(maxSeats)) }
        return capped
    }

    private func stream(
        speaker: IOSCouncilRoomSpeaker,
        systemPrompt: String,
        userPrompt: String,
        request: IOSCouncilRoomRunRequest,
        temperature: Float,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        try checkCancelled()
        let params = makeTextGenerationParams(
            modelId: speaker.modelId,
            reasoning: speaker.reasoning,
            temperature: temperature,
            outputBudgetCharacters: request.settings.limits.outputBudgetCharacters
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
        try checkCancelled()
        if text.lowercased().hasPrefix("error:") {
            throw IOSCouncilRoomRunnerError.hostFailed(text)
        }
        return text
    }

    private func streamWithTimeout(
        seconds: Int,
        timeoutLabel: String,
        operation: @escaping @Sendable () async throws -> String
    ) async throws -> String {
        try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask {
                try await operation()
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(max(1, seconds)) * 1_000_000_000)
                throw IOSCouncilRoomRunnerError.timedOut(timeoutLabel)
            }
            do {
                guard let result = try await group.next() else {
                    throw IOSCouncilRoomRunnerError.timedOut(timeoutLabel)
                }
                group.cancelAll()
                return result
            } catch {
                group.cancelAll()
                streamer.cancel()
                throw error
            }
        }
    }

    private func makeTextGenerationParams(
        modelId: String,
        reasoning: IOSCouncilReasoningPreset,
        temperature: Float,
        outputBudgetCharacters: Int
    ) -> TextGenerationParams {
        let abilities = ModelRegistry.shared.MODEL_ABILITIES.getData(modelId: modelId) as? [ModelAbility] ?? []
        let model = Model(
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
        let maxTokens = Int32(min(max(outputBudgetCharacters / 4, 512), 8_192))
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: temperature),
            topP: nil,
            maxTokens: KotlinInt(value: maxTokens),
            tools: [],
            reasoningLevel: abilities.contains(.reasoning) ? reasoning.reasoningLevel : .off,
            customHeaders: [],
            customBody: []
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
        User topic:
        \(objective)

        Bounded web research:
        \(research.summaryText.trimmedOr("本轮没有联网调研材料。"))

        Default available seats:
        \(defaultSeats.map { "- \($0.name): \($0.rolePrompt)" }.joined(separator: "\n"))

        请先完善议题并补充最新信息，形成“最终议题”。然后在结尾用严格格式列出你要拉起的 2 到 \(limits.maxSeats) 个席位：
        席位: 名称 | 角色提示

        只列本轮需要加入的席位，不要超过上限。
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

    private func recordResearchConsent(_ consent: IOSCouncilResearchConsent, objective: String, runId: String) {
        guard consent == .allowed || consent == .denied else { return }
        let action: IOSToolApprovalAction = consent == .allowed ? .allowed : .denied
        let reason = consent == .allowed
            ? "User approved bounded Council Room host research for this run."
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

    private func checkCancelled() throws {
        if isCancelled || Task.isCancelled {
            streamer.cancel()
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
    @ObservationIgnored private var manager: ModelCouncilManager?
    @ObservationIgnored private let taskStore: IOSAdvancedTaskStore

    var lastRunResult: String = "(未运行)"
    var isRunning: Bool = false
    var lastTask: IOSAdvancedTaskRecord?

    init(taskStore: IOSAdvancedTaskStore = .shared) {
        self.taskStore = taskStore
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
        mode: String = "compare",
        outputBudgetChars: Int = 12_000
    ) async -> String {
        let settingsStore = SettingsStore()
        let currentModelId = settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "gpt-4o"
            : settingsStore.modelId
        let providerMode = settingsStore.currentApiKey.isEmpty ? "missing_key" : "current_openai_compatible"
        let effectiveSeats = seats.isEmpty ? Self.defaultSeatDescriptors() : seats
        var roomSettings = IOSCouncilRoomSettings.defaults(currentModelId: currentModelId)
        roomSettings.seats = effectiveSeats
            .filter { $0.id != "host" }
            .map {
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
        if roomSettings.seats.count < 2 {
            roomSettings.seats = IOSCouncilRoomSettings.defaults(currentModelId: currentModelId).seats
        }
        roomSettings.limits = IOSCouncilRoomLimits(
            maxSeats: max(2, min(effectiveSeats.count, 8)),
            defaultRounds: mode.lowercased().contains("debate") ? 2 : 1,
            seatTimeoutSeconds: 60,
            outputBudgetCharacters: outputBudgetChars
        )

        isRunning = true
        defer { isRunning = false }

        let request = IOSCouncilRoomRunRequest(
            objective: objective,
            mode: mode.lowercased().contains("debate") ? .debate : .freeChat,
            settings: roomSettings,
            currentModelId: currentModelId,
            providerSetting: IOSCouncilRoomRunner.makeProviderSetting(
                baseUrl: settingsStore.baseUrl,
                apiKey: settingsStore.currentApiKey
            ),
            searchSettings: nil,
            researchConsent: .unavailable
        )
        let roomRunner = IOSCouncilRoomRunner(taskStore: taskStore)
        let outcome = await roomRunner.run(request: request)
        lastTask = taskStore.tasks.first { $0.id == outcome.taskId }
        let summary = outcome.status == .completed
            ? "模型议会已完成，席位：\(effectiveSeats.map(\.name).joined(separator: ", "))。"
            : "模型议会未完成：\(outcome.status.title)。"
        lastRunResult = "\(summary) taskId: \(outcome.taskId)，mode: \(mode)，provider: \(providerMode)。"
        return Self.json([
            "ok": outcome.status == .completed,
            "task_id": outcome.taskId,
            "kind": IOSAdvancedTaskKind.modelCouncil.rawValue,
            "run_id": outcome.taskId,
            "status": outcome.status.rawValue,
            "mode": mode,
            "seat_count": effectiveSeats.count,
            "budget_chars": outputBudgetChars,
            "summary": lastRunResult
        ])
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
