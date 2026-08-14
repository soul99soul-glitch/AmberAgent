@preconcurrency import BackgroundTasks
import Foundation
import OSLog
@preconcurrency import Shared

private let backgroundRunLedgerLogger = Logger(subsystem: "app.amber.ios", category: "chat-bg-ledger")
private let backgroundToolExposureLogger = Logger(subsystem: "app.amber.ios", category: "chat-bg-tools")
private let backgroundMailboxLogger = Logger(subsystem: "app.amber.ios", category: "chat-bg-mailbox")

private struct IOSChatBackgroundRuntimeJob {
    let runId: String
    let startedAt: Int64
    let inputDigest: String
    let conversationId: KotlinUuid
    let providerSetting: ProviderSetting
    let params: TextGenerationParams
    let uploadMessages: [UIMessage]
    let displayMessages: [UIMessage]
    let mode: IOSChatBackgroundHandoffMode
    let responseId: String?
    let responseSequenceNumber: Int64?
    let generativeUiRequirement: IOSGenerativeUiRequirement
    let generativeUiFallbackAttempted: Bool
    let conversationStore: IOSConversationStore
    let toolRuntime: ChatToolRuntime
    let liveActivityController: AgentLiveActivityController
    let saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> ChatMiniAppOutputApplication?)?
    let messagesSnapshot: IOSChatBackgroundMessagesSnapshot
    /// P0-a: background-owned tool exposure bridge (rebuilt from the handoff's
    /// visible declarations — the exposed set at handoff — since the persisted
    /// payload cannot carry Kotlin Tool lists). Powers the background
    /// `tool_search` executor.
    let toolExposureBridge: IosToolExposureBridge
}

/// KMP message objects are not declared `Sendable`, but this retry snapshot is
/// immutable after construction and the engine copies its array before mutation.
private struct IOSChatBackgroundRetryMessages: @unchecked Sendable {
    let values: [UIMessage]
}

private struct IOSChatBackgroundDependencies {
    let conversationStore: IOSConversationStore
    let toolRuntime: ChatToolRuntime
    let sharedSettings: IOSSharedSettingsStore
    let liveActivityController: AgentLiveActivityController
    let saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> ChatMiniAppOutputApplication?)?
}

struct IOSChatBackgroundHandoff {
    let runId: String
    let startedAt: Int64
    let inputDigest: String
    let conversationId: KotlinUuid
    let providerId: String
    let providerSetting: ProviderSetting
    let params: TextGenerationParams
    let uploadMessages: [UIMessage]
    let displayMessages: [UIMessage]
    var mode: IOSChatBackgroundHandoffMode
    var responseId: String? = nil
    var responseSequenceNumber: Int64? = nil
    let generativeUiRequirement: IOSGenerativeUiRequirement
    let generativeUiFallbackAttempted: Bool
    /// P0-a Fix C: the FULL catalog tool names of the run (params.tools only
    /// carries the visible subset, which would silently disable lazy mode in
    /// the background bridge). Empty for legacy payloads → fall back to
    /// params.tools.
    let fullToolNames: [String]
}

enum IOSChatBackgroundHandoffMode: String {
    case continueModel = "continue_model"
    case singleToolOnly = "single_tool_only"
    case resumeResponse = "resume_response"
}

struct IOSChatBackgroundProvider: IOSAgentTextProvider, IOSAgentStreamingProvider {
    private let openAIProvider = OpenAIKmpProvider()
    private let claudeProvider = ClaudeKmpProvider()

    func generateText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        if let openAI = providerSetting as? ProviderSetting.OpenAI {
            return try await openAIProvider.generateText(providerSetting: openAI, messages: messages, params: params)
        }
        if let claude = providerSetting as? ProviderSetting.Claude {
            return try await claudeProvider.generateText(providerSetting: claude, messages: messages, params: params)
        }
        throw NSError(
            domain: "AmberAgent.ChatBackgroundGeneration",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "当前服务商类型暂不支持后台生成"]
        )
    }

    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onChunk: @escaping @Sendable (MessageChunk) -> Void,
        onComplete: @escaping @Sendable () -> Void,
        onError: @escaping @Sendable (KotlinThrowable) -> Void
    ) -> Kotlinx_coroutines_coreJob? {
        if let openAI = providerSetting as? ProviderSetting.OpenAI {
            return openAIProvider.streamTextCancellable(
                providerSetting: openAI,
                messages: messages,
                params: params,
                onChunk: onChunk,
                onComplete: onComplete,
                onError: onError
            )
        }
        if let claude = providerSetting as? ProviderSetting.Claude {
            return claudeProvider.streamTextCancellable(
                providerSetting: claude,
                messages: messages,
                params: params,
                onChunk: onChunk,
                onComplete: onComplete,
                onError: onError
            )
        }
        onError(KotlinThrowable(message: "当前服务商类型暂不支持后台生成"))
        return nil
    }
}

final class IOSChatBackgroundRunState: @unchecked Sendable {
    enum TerminalOwner: Equatable {
        case completion
        case expiration
        case cancellation
    }

    enum ExpirationClaim: Equatable {
        case persistFailure
        case terminateInFlightSave
        case rejected
    }

    private let lock = NSLock()
    private var terminalOwner: TerminalOwner?
    private var terminalFinalized = false
    private var systemTaskCompletionClaimed = false
    private var operationTask: Task<IOSAgentToolEngineResult, Never>?

    var isExpired: Bool {
        lock.lock()
        defer { lock.unlock() }
        return terminalOwner == .expiration || terminalOwner == .cancellation
    }

    var allowsRunningPresentation: Bool {
        lock.lock()
        defer { lock.unlock() }
        return terminalOwner == nil && !terminalFinalized
    }

    func expireAndReserveTerminal() -> ExpirationClaim {
        let task: Task<IOSAgentToolEngineResult, Never>?
        lock.lock()
        guard terminalOwner != .expiration,
              terminalOwner != .cancellation,
              !terminalFinalized else {
            lock.unlock()
            return .rejected
        }
        let claim: ExpirationClaim = terminalOwner == nil
            ? .persistFailure
            : .terminateInFlightSave
        terminalOwner = .expiration
        task = operationTask
        operationTask = nil
        lock.unlock()
        task?.cancel()
        return claim
    }

    func cancelAndReserveTerminal() -> Bool {
        let task: Task<IOSAgentToolEngineResult, Never>?
        lock.lock()
        guard terminalOwner == nil, !terminalFinalized else {
            lock.unlock()
            return false
        }
        terminalOwner = .cancellation
        task = operationTask
        operationTask = nil
        lock.unlock()
        task?.cancel()
        return true
    }

    func installOperationTask(_ task: Task<IOSAgentToolEngineResult, Never>) {
        lock.lock()
        let shouldCancel = terminalOwner == .expiration || terminalOwner == .cancellation
        if !shouldCancel {
            operationTask = task
        }
        lock.unlock()
        if shouldCancel {
            task.cancel()
        }
    }

    func clearOperationTask() {
        lock.lock()
        operationTask = nil
        lock.unlock()
    }

    func reserveTerminal() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard terminalOwner == nil, !terminalFinalized else { return false }
        terminalOwner = .completion
        operationTask = nil
        return true
    }

    func finalizeTerminal(as owner: TerminalOwner = .completion) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard terminalOwner == owner, !terminalFinalized else { return false }
        terminalFinalized = true
        return true
    }

    func terminalWasFinalized(by owner: TerminalOwner) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return terminalOwner == owner && terminalFinalized
    }

    func terminalIsOwned(by owner: TerminalOwner) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return terminalOwner == owner
    }

    func claimSystemTaskCompletion() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !systemTaskCompletionClaimed else { return false }
        systemTaskCompletionClaimed = true
        return true
    }
}

private final class IOSChatBackgroundAssistantTextSnapshot: @unchecked Sendable {
    private let lock = NSLock()
    private var latestText = ""

    var text: String {
        lock.lock()
        defer { lock.unlock() }
        return latestText
    }

    func replace(with text: String) {
        lock.lock()
        latestText = text
        lock.unlock()
    }
}

private final class IOSChatBackgroundMessagesSnapshot: @unchecked Sendable {
    private let lock = NSLock()
    private var latestMessages: [UIMessage]

    init(_ messages: [UIMessage]) {
        latestMessages = messages
    }

    var messages: [UIMessage] {
        lock.lock()
        defer { lock.unlock() }
        return latestMessages
    }

    func replace(with messages: [UIMessage]) {
        lock.lock()
        latestMessages = messages
        lock.unlock()
    }
}

private final class IOSChatDurableResponseAccumulator: @unchecked Sendable {
    private let lock = NSLock()
    private let accumulator: MessageStreamAccumulator

    init(displayMessages: [UIMessage], model: Model) {
        accumulator = MessageStreamAccumulator(initialMessages: displayMessages, model: model)
    }

    func append(_ chunk: MessageChunk) -> [UIMessage] {
        lock.lock()
        defer { lock.unlock() }
        accumulator.append(chunk: chunk)
        return accumulator.snapshot()
    }

    var messages: [UIMessage] {
        lock.lock()
        defer { lock.unlock() }
        return accumulator.snapshot()
    }
}

private final class IOSChatDurableResumeCompletion: @unchecked Sendable {
    enum Outcome {
        case completed
        case disconnected(String)
        case failed(String)
    }

    private let lock = NSLock()
    private var continuation: CheckedContinuation<Outcome, Never>?
    private var pendingOutcome: Outcome?
    private var isResolved = false

    func install(_ continuation: CheckedContinuation<Outcome, Never>) {
        lock.lock()
        if let pendingOutcome {
            self.pendingOutcome = nil
            lock.unlock()
            continuation.resume(returning: pendingOutcome)
        } else {
            self.continuation = continuation
            lock.unlock()
        }
    }

    func resolve(_ outcome: Outcome) {
        lock.lock()
        guard !isResolved else {
            lock.unlock()
            return
        }
        isResolved = true
        if let continuation {
            self.continuation = nil
            lock.unlock()
            continuation.resume(returning: outcome)
        } else {
            pendingOutcome = outcome
            lock.unlock()
        }
    }
}

struct IOSChatBackgroundJobTerminalEvent: Equatable, Sendable {
    let conversationId: String
}

extension Notification.Name {
    static let amberChatBackgroundJobDidTerminate = Notification.Name(
        "app.amber.ios.chat.backgroundJobDidTerminate"
    )
}

@MainActor
extension IOSChatBackgroundGenerationCoordinator: IOSThreadOrchestrationToolService.BackgroundScheduling {}

@MainActor
final class IOSChatBackgroundGenerationCoordinator {
    static let shared = IOSChatBackgroundGenerationCoordinator()

    /// P1-c: 后台 job 终态钩子（子线程完成/失败/截断/取消时向父线程投递
    /// FINAL_ANSWER 由 ChatViewModel 接线到编排服务）。nil 时零开销。
    var onRunTerminal: (@MainActor (KotlinUuid, String, [UIMessage]) async -> Void)?

    private var bundleIdentifier: String { Bundle.main.bundleIdentifier ?? "app.amber.ios" }
    private var permittedIdentifier: String { "\(bundleIdentifier).chat.*" }
    private var requestPrefix: String { "\(bundleIdentifier).chat." }
    private var taskMapKey: String { "\(bundleIdentifier).chat.backgroundTaskMap" }
    private var registeredRequestIds: Set<String> = []
    private var dependencies: IOSChatBackgroundDependencies?
    private var activeJobs: [String: IOSChatBackgroundRuntimeJob] = [:]
    private var activeRunStates: [String: IOSChatBackgroundRunState] = [:]
    private var activeBackgroundTasks: [String: BGContinuedProcessingTask] = [:]
    private var activeDetachedResponseTasks: [String: Task<Void, Never>] = [:]
    private var activeDetachedResponseJobs: [String: Kotlinx_coroutines_coreJob] = [:]
    private var activeDetachedResponseCompletions: [String: IOSChatDurableResumeCompletion] = [:]
    private lazy var db: AgentRuntimeDatabase = IosDatabaseFactory.shared.createDatabase()
    private lazy var runStore = IOSDurableRunStore(dao: db.agentRuntimeDao())
    // W1 durable ledger (I-1): background-continued tool execution accounts
    // itself against the SAME runId the foreground run already started under
    // (see the `IOSAgentToolEngine(... ledger:ledgerRunId:)` call below).
    private lazy var toolLedger: IOSAgentRunLedgering = IOSAgentRunLedger(dao: db.agentRuntimeDao())

    private init() {}

    /// 生命周期快照里属于本协调器的那一段：只读内存态，不碰磁盘。
    var lifecycleSnapshotDetail: String {
        "bgTasks=\(activeBackgroundTasks.count)"
            + " jobs=\(activeJobs.count)"
            + " runStates=\(activeRunStates.count)"
    }

    var restorableRunIds: Set<String> {
        Set(activeJobs.values.map(\.runId))
    }

    var reconnectingWatchProjection: WatchTaskReconnectProjection? {
        guard let job = activeJobs.values.first else { return nil }
        return WatchTaskReconnectProjection(
            runId: job.runId,
            conversationId: job.conversationId.toHexDashString()
        )
    }

    func configure(
        conversationStore: IOSConversationStore? = nil,
        toolRuntime: ChatToolRuntime? = nil,
        sharedSettings: IOSSharedSettingsStore? = nil,
        liveActivityController: AgentLiveActivityController = .shared,
        saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> ChatMiniAppOutputApplication?)? = nil
    ) {
        if let conversationStore, let toolRuntime, let sharedSettings {
            dependencies = IOSChatBackgroundDependencies(
                conversationStore: conversationStore,
                toolRuntime: toolRuntime,
                sharedSettings: sharedSettings,
                liveActivityController: liveActivityController,
                saveMiniAppIfPresent: saveMiniAppIfPresent
            )
        }
        for requestId in taskMap().keys {
            if !register(requestId: requestId) {
                finish(requestId: requestId)
            } else if activeJobs[requestId] == nil {
                _ = job(for: requestId)
            }
        }
    }

    func start(
        handoff: IOSChatBackgroundHandoff,
        conversationStore: IOSConversationStore,
        toolRuntime: ChatToolRuntime,
        liveActivityController: AgentLiveActivityController,
        saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> ChatMiniAppOutputApplication?)? = nil
    ) -> Bool {
        if handoff.mode == .resumeResponse {
            guard checkpointDurableResponse(handoff) else { return false }
            activeJobs[requestIdentifier(for: handoff.runId)] = runtimeJob(
                handoff: handoff,
                conversationStore: conversationStore,
                toolRuntime: toolRuntime,
                liveActivityController: liveActivityController,
                saveMiniAppIfPresent: saveMiniAppIfPresent
            )
            return true
        }
        configure()

        let requestId = requestIdentifier(for: handoff.runId)
        guard register(requestId: requestId) else { return false }

        do {
            try persist(handoff: handoff, requestId: requestId)
        } catch {
            NSLog("[AmberChatBG] Failed to persist background payload: \(error)")
            return false
        }

        activeJobs[requestId] = runtimeJob(
            handoff: handoff,
            conversationStore: conversationStore,
            toolRuntime: toolRuntime,
            liveActivityController: liveActivityController,
            saveMiniAppIfPresent: saveMiniAppIfPresent
        )
        remember(runId: handoff.runId, requestId: requestId)

        let request = BGContinuedProcessingTaskRequest(
            identifier: requestId,
            title: "Amber 后台生成",
            subtitle: handoff.params.model.displayName
        )
        // Match KeepAlive: queue when the system is busy instead of failing the
        // handoff immediately (which left only the ~30s UIKit short window).
        request.strategy = .queue

        do {
            try BGTaskScheduler.shared.submit(request)
            IOSBackgroundLifecycleLog.record(
                "bgTaskSubmitted(run=\(handoff.runId.prefix(8)))",
                detail: lifecycleSnapshotDetail
            )
            return true
        } catch {
            // 提交失败 = 这一轮没交出去，所有权仍在前台（调用方看到 false 就不会
            // 清 currentRunId）。所以只回滚后台侧刚登记的东西，租约绝不能还——
            // 还了前台这一轮就失去保活，等于白白退化。
            activeJobs.removeValue(forKey: requestId)
            var map = taskMap()
            map.removeValue(forKey: requestId)
            UserDefaults.standard.set(map, forKey: taskMapKey)
            removePayload(requestId: requestId)
            NSLog("[AmberChatBG] BGContinuedProcessingTask submit failed: \(error)")
            return false
        }
    }

    /// Persist the first server-owned Responses cursor without submitting a
    /// second model run. The existing payload/task map becomes the single
    /// cold-start owner; later sequence updates stay in memory because resume
    /// replays this response from its beginning.
    func checkpointDurableResponse(_ handoff: IOSChatBackgroundHandoff) -> Bool {
        guard handoff.mode == .resumeResponse,
              handoff.responseId != nil else {
            return false
        }
        let requestId = requestIdentifier(for: handoff.runId)
        guard register(requestId: requestId) else { return false }
        do {
            try persist(handoff: handoff, requestId: requestId)
        } catch {
            NSLog("[AmberChatBG] Failed to persist durable response checkpoint: \(error)")
            return false
        }
        remember(runId: handoff.runId, requestId: requestId)
        return true
    }

    func discardDurableResponse(runId: String) {
        let requestIds = taskMap().filter { $0.value == runId }.map(\.key)
        for requestId in requestIds {
            activeDetachedResponseTasks[requestId]?.cancel()
            activeDetachedResponseJobs.removeValue(forKey: requestId)?.cancel(cause: nil)
            activeDetachedResponseCompletions.removeValue(forKey: requestId)?
                .resolve(.disconnected("discarded"))
        }
        finish(runId: runId)
    }

    /// Reattach server-owned Responses runs left by suspension or process
    /// termination. Non-durable payloads remain owned by the existing stale
    /// sweep and are closed as retryable failures.
    func resumeDetachedResponsesIfNeeded() {
        for (requestId, _) in taskMap() {
            guard activeBackgroundTasks[requestId] == nil,
                  activeDetachedResponseTasks[requestId] == nil,
                  let job = job(for: requestId),
                  job.mode == .resumeResponse,
                  job.responseId != nil else {
                continue
            }
            activeDetachedResponseTasks[requestId] = Task { @MainActor [weak self] in
                guard let self else { return }
                await self.resumeDetachedResponse(job: job, requestId: requestId)
                self.activeDetachedResponseTasks.removeValue(forKey: requestId)
                self.activeDetachedResponseJobs.removeValue(forKey: requestId)
                self.activeDetachedResponseCompletions.removeValue(forKey: requestId)
            }
        }
    }

    func hasActiveJob(conversationId: KotlinUuid) -> Bool {
        !jobs(conversationId: conversationId).isEmpty
    }

    /// P1-e: 后台活跃 job 总数（并发限额的活注册表计数源；与 restorableRunIds
    /// 同集合，activeJobs 内每个 job 均有持久化 payload）。
    var activeJobCount: Int {
        activeJobs.count
    }

    func activeRunId(conversationId: KotlinUuid) -> String? {
        jobs(conversationId: conversationId).values.max { lhs, rhs in
            if lhs.startedAt == rhs.startedAt {
                return lhs.runId < rhs.runId
            }
            return lhs.startedAt < rhs.startedAt
        }?.runId
    }

    /// P1-c: 按 hex-dash conversation id 查活跃后台 run（编排服务 interrupt 用，
    /// 避免在 Swift 侧重建 KotlinUuid）。
    func activeRunId(conversationHex: String) -> String? {
        activeJobs.values
            .filter { String(describing: $0.conversationId) == conversationHex }
            .max { lhs, rhs in
                if lhs.startedAt == rhs.startedAt {
                    return lhs.runId < rhs.runId
                }
                return lhs.startedAt < rhs.startedAt
            }?
            .runId
    }

    @discardableResult
    func cancelActiveJob(conversationId: KotlinUuid) -> Bool {
        guard let runId = activeRunId(conversationId: conversationId) else { return false }
        return cancelJob(runId: runId)
    }

    func cancelJobs(conversationId: KotlinUuid) {
        let matchingJobs = jobs(conversationId: conversationId)
        for (requestId, job) in matchingJobs {
            _ = cancelJob(requestId: requestId, job: job)
        }
    }

    @discardableResult
    func cancelJob(runId: String) -> Bool {
        guard let match = activeJobs.first(where: { $0.value.runId == runId }) else {
            return false
        }
        return cancelJob(requestId: match.key, job: match.value)
    }

    @discardableResult
    private func cancelJob(requestId: String, job: IOSChatBackgroundRuntimeJob) -> Bool {
        let runState = activeRunStates[requestId] ?? IOSChatBackgroundRunState()
        activeRunStates[requestId] = runState
        guard runState.cancelAndReserveTerminal(),
              runState.finalizeTerminal(as: .cancellation),
              runState.claimSystemTaskCompletion() else {
            return false
        }
        cancelDetachedResponseTransport(requestId: requestId, job: job)
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: requestId)
        Task { @MainActor in
            let latestMessages = Self.reconciledMessages(
                resultMessages: job.messagesSnapshot.messages,
                uploadMessageCount: job.uploadMessages.count,
                displayMessages: job.displayMessages
            )
            let cancelledMessages: [UIMessage]
            if job.toolRuntime.hasUnresolvedToolCall(in: latestMessages) {
                cancelledMessages = job.toolRuntime.messagesByFailingPendingToolCalls(
                    in: latestMessages,
                    failureReason: "User cancelled.",
                    denied: true
                )
            } else {
                cancelledMessages = latestMessages
            }
            let didPersistTerminal: Bool
            switch job.mode {
            case .continueModel, .resumeResponse:
                didPersistTerminal = await job.conversationStore.saveBackgroundCompletion(
                    baseMessages: job.displayMessages,
                    completedMessages: cancelledMessages,
                    to: job.conversationId
                )
            case .singleToolOnly:
                didPersistTerminal = await job.conversationStore.saveBackgroundToolCompletion(
                    baseMessages: job.displayMessages,
                    completedMessages: cancelledMessages,
                    to: job.conversationId
                )
            }
            await self.recordRun(
                job.runId,
                startedAt: job.startedAt,
                status: didPersistTerminal ? .cancelled : .recoveryPending,
                inputDigest: job.inputDigest,
                conversationId: job.conversationId
            )
            if didPersistTerminal {
                self.notifyRunTerminal(job: job, runId: job.runId, finalMessages: cancelledMessages)
            }
            _ = job.liveActivityController.adoptExistingActivity(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString()
            )
            WatchTaskCoordinator.shared.publish(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                presentation: didPersistTerminal ? .cancelled() : .failed(),
                summary: didPersistTerminal ? nil : "已停止生成，但工具取消状态保存失败。"
            )
            await job.liveActivityController.end(
                runId: job.runId,
                presentation: didPersistTerminal ? .cancelled() : .failed()
            )
            let backgroundTask = self.activeBackgroundTasks[requestId]
            if didPersistTerminal {
                self.finish(runId: job.runId, requestId: requestId)
            } else {
                self.releaseRuntimeOwnership(requestId: requestId)
            }
            backgroundTask?.setTaskCompleted(success: false)
        }
        return true
    }

    private func register(requestId: String) -> Bool {
        guard requestId.hasPrefix(requestPrefix) else {
            NSLog("[AmberChatBG] Refusing to register unexpected BGContinuedProcessingTask id \(requestId); expected prefix \(requestPrefix)")
            return false
        }
        guard !registeredRequestIds.contains(requestId) else { return true }

        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: requestId, using: nil) { @MainActor [weak self] task in
            guard let task = task as? BGContinuedProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            // queue=nil 走主队列；在把工作排进 async handler 前先登记，冷启动
            // sweep 才不会把刚被系统唤起的 request 当成 stale。
            self?.activeBackgroundTasks[task.identifier] = task
            Task { @MainActor in
                await IOSChatBackgroundGenerationCoordinator.shared.handle(task)
            }
        }
        if registered {
            registeredRequestIds.insert(requestId)
        } else {
            NSLog("[AmberChatBG] BGContinuedProcessingTask registration failed for \(requestId); permitted pattern \(permittedIdentifier)")
        }
        return registered
    }

    /// Builds the background run's exposure bridge. When the handoff carries
    /// the full catalog the bridge is rebuilt over it (lazy mode stays on and
    /// tool_search can search everything); otherwise it falls back to the
    /// handoff's visible tool subset. Either way the bridge's initial exposure
    /// is seeded with the tools that were visible at handoff time, so the
    /// per-round `replacingTools(visibleTools())` refresh does not drop tools
    /// the foreground had already exposed.
    static func makeBackgroundToolExposureBridge(
        fullToolNames: [String],
        handoffVisibleTools: [Tool],
        additionalDeclarations: [Tool] = []
    ) -> IosToolExposureBridge {
        let bridge: IosToolExposureBridge
        if !fullToolNames.isEmpty {
            var rebuilt = ToolKt.iosToolDeclarations(names: fullToolNames)
            let rebuiltNames = Set(rebuilt.map(\.name))
            // P0-b: dynamic `mcp__*` declarations cannot be rebuilt from a
            // name (the payload has no server/tool directory), so exclude them
            // from the static mismatch check and append the regenerated ones.
            let staticNames = fullToolNames.filter { !ToolKt.isExpandedMcpToolName(name: $0) }
            if rebuiltNames != Set(staticNames) {
                backgroundToolExposureLogger.error(
                    "background bridge catalog mismatch: \(rebuilt.count)/\(staticNames.count) declarations rebuilt — a tool name is missing from KMP iosToolDeclaration"
                )
            }
            for tool in additionalDeclarations where !rebuiltNames.contains(tool.name) {
                rebuilt.append(tool)
            }
            bridge = IosToolExposureBridge(tools: rebuilt)
        } else {
            bridge = IosToolExposureBridge(tools: handoffVisibleTools)
        }
        bridge.exposeToolNames(names: handoffVisibleTools.map(\.name))
        return bridge
    }

    private func runtimeJob(
        handoff: IOSChatBackgroundHandoff,
        conversationStore: IOSConversationStore,
        toolRuntime: ChatToolRuntime,
        liveActivityController: AgentLiveActivityController,
        saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> ChatMiniAppOutputApplication?)?
    ) -> IOSChatBackgroundRuntimeJob {
        // P0-a Fix C: rebuild the exposure bridge over the FULL catalog when
        // the handoff carried it (foreground bridges are built from the full
        // static declarations, so lazy mode stays on and tool_search searches
        // the whole catalog — hits become callable on the next round via
        // IOSAgentToolEngine's per-round params refresh). Legacy payloads
        // without fullToolNames fall back to the previous behavior (visible
        // subset from handoff.params.tools, which may disable lazy mode).
        let backgroundBridge = Self.makeBackgroundToolExposureBridge(
            fullToolNames: handoff.fullToolNames,
            handoffVisibleTools: handoff.params.tools,
            // P0-b: regenerate the dynamic MCP surface from the runtime's own
            // directory so background tool_search can expose (and the engine
            // can execute) `mcp__*` tools like the foreground run could.
            additionalDeclarations: toolRuntime.mcpExpandedDeclarations()
        )
        return IOSChatBackgroundRuntimeJob(
            runId: handoff.runId,
            startedAt: handoff.startedAt,
            inputDigest: handoff.inputDigest,
            conversationId: handoff.conversationId,
            providerSetting: handoff.providerSetting,
            params: handoff.params,
            uploadMessages: handoff.uploadMessages,
            displayMessages: handoff.displayMessages,
            mode: handoff.mode,
            responseId: handoff.responseId,
            responseSequenceNumber: handoff.responseSequenceNumber,
            generativeUiRequirement: handoff.generativeUiRequirement,
            generativeUiFallbackAttempted: handoff.generativeUiFallbackAttempted,
            conversationStore: conversationStore,
            toolRuntime: toolRuntime,
            liveActivityController: liveActivityController,
            saveMiniAppIfPresent: saveMiniAppIfPresent,
            messagesSnapshot: IOSChatBackgroundMessagesSnapshot(handoff.uploadMessages),
            toolExposureBridge: backgroundBridge
        )
    }

    private func publishRunningPresentation(
        _ presentation: AgentActivityPresentation,
        for job: IOSChatBackgroundRuntimeJob,
        runState: IOSChatBackgroundRunState
    ) async {
        guard runState.allowsRunningPresentation else { return }
        WatchTaskCoordinator.shared.publish(
            runId: job.runId,
            conversationId: job.conversationId.toHexDashString(),
            presentation: presentation
        )
        await job.liveActivityController.update(
            runId: job.runId,
            presentation: presentation,
            force: true
        )
    }

    private func resumeDetachedResponse(
        job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        reconnectAttempt: Int = 0
    ) async {
        guard let openAI = job.providerSetting as? ProviderSetting.OpenAI,
              let responseId = job.responseId else {
            return
        }
        do {
            guard let snapshot = try await runStore.snapshot(runId: job.runId) else { return }
            if snapshot.status == .awaitingPermission {
                return
            }
            if snapshot.status == .recoveryPending {
                guard try await runStore.transition(
                    runId: job.runId,
                    expected: .recoveryPending,
                    to: .running
                ) else { return }
            } else if snapshot.status != .running {
                finish(runId: job.runId, requestId: requestId)
                return
            }
        } catch {
            return
        }
        let runState = activeRunStates[requestId] ?? IOSChatBackgroundRunState()
        activeRunStates[requestId] = runState
        let accumulator = IOSChatDurableResponseAccumulator(
            displayMessages: job.displayMessages,
            model: job.params.model
        )
        let completion = IOSChatDurableResumeCompletion()
        activeDetachedResponseCompletions[requestId] = completion
        let transport = OpenAIResponsesBackgroundTransport()
        let outcome = await withCheckedContinuation { continuation in
            completion.install(continuation)
            do {
                let streamJob = try transport.resumeBackground(
                    providerSetting: openAI,
                    responseId: responseId,
                    startingAfter: 0,
                    customHeaders: job.params.customHeaders,
                    onChunk: { chunk in
                        job.messagesSnapshot.replace(with: accumulator.append(chunk))
                    },
                    onCheckpoint: { _, _ in },
                    onComplete: {
                        completion.resolve(.completed)
                    },
                    onDisconnected: { error in
                        completion.resolve(.disconnected(error.message ?? String(describing: error)))
                    },
                    onFailure: { error in
                        completion.resolve(.failed(error.message ?? String(describing: error)))
                    }
                )
                activeDetachedResponseJobs[requestId] = streamJob
            } catch {
                completion.resolve(.failed((error as NSError).localizedDescription))
            }
        }

        activeDetachedResponseJobs.removeValue(forKey: requestId)
        activeDetachedResponseCompletions.removeValue(forKey: requestId)
        guard !Task.isCancelled else { return }
        switch outcome {
        case .completed:
            let messages = accumulator.messages
            job.messagesSnapshot.replace(with: messages)
            if job.toolRuntime.hasUnresolvedToolCall(in: messages) {
                let generatedSuffix = Array(messages.dropFirst(job.displayMessages.count))
                var nextHandoff = IOSChatBackgroundHandoff(
                    runId: job.runId,
                    startedAt: job.startedAt,
                    inputDigest: job.inputDigest,
                    conversationId: job.conversationId,
                    providerId: job.providerSetting.id.toHexDashString(),
                    providerSetting: job.providerSetting,
                    params: job.params,
                    uploadMessages: job.uploadMessages + generatedSuffix,
                    displayMessages: messages,
                    mode: .continueModel,
                    generativeUiRequirement: job.generativeUiRequirement,
                    generativeUiFallbackAttempted: job.generativeUiFallbackAttempted,
                    fullToolNames: job.toolExposureBridge.fullToolDeclarations().map(\.name)
                )
                nextHandoff.responseId = nil
                nextHandoff.responseSequenceNumber = nil
                if !start(
                    handoff: nextHandoff,
                    conversationStore: job.conversationStore,
                    toolRuntime: job.toolRuntime,
                    liveActivityController: job.liveActivityController,
                    saveMiniAppIfPresent: job.saveMiniAppIfPresent
                ) {
                    _ = checkpointDurableResponse(durableResponseHandoff(for: job))
                    activeJobs[requestId] = job
                    guard runState.reserveTerminal() else { return }
                    let didSave = await persistExpirationFailure(
                        job: job,
                        requestId: requestId,
                        rawMessage: "回复已恢复，但工具阶段未能继续，请重试。",
                        partialAssistantText: nil
                    )
                    guard runState.finalizeTerminal() else { return }
                    if didSave {
                        finish(runId: job.runId, requestId: requestId)
                    } else {
                        activeRunStates[requestId] = IOSChatBackgroundRunState()
                    }
                }
                return
            }
            guard runState.reserveTerminal() else { return }
            await completeDetachedResponse(
                job: job,
                requestId: requestId,
                messages: messages,
                runState: runState
            )
        case .failed(let message):
            guard runState.reserveTerminal() else { return }
            let didSave = await persistExpirationFailure(
                job: job,
                requestId: requestId,
                rawMessage: message,
                partialAssistantText: nil
            )
            guard runState.finalizeTerminal() else { return }
            if didSave {
                finish(runId: job.runId, requestId: requestId)
            } else {
                activeRunStates[requestId] = IOSChatBackgroundRunState()
            }
        case .disconnected(let message):
            if reconnectAttempt == 0 {
                try? await Task.sleep(for: .milliseconds(400))
                guard !Task.isCancelled else { return }
                await resumeDetachedResponse(
                    job: job,
                    requestId: requestId,
                    reconnectAttempt: 1
                )
                return
            }
            guard runState.reserveTerminal() else { return }
            let didSave = await persistExpirationFailure(
                job: job,
                requestId: requestId,
                rawMessage: message.isEmpty ? "后台回复连接中断，请重试。" : message,
                partialAssistantText: nil
            )
            guard runState.finalizeTerminal() else { return }
            if didSave {
                cancelRemoteResponse(for: job)
                finish(runId: job.runId, requestId: requestId)
            } else {
                // Keep a visible/cancellable owner. A future foreground entry
                // may retry the same response after storage becomes writable.
                activeRunStates[requestId] = IOSChatBackgroundRunState()
            }
        }
    }

    private func durableResponseHandoff(
        for job: IOSChatBackgroundRuntimeJob
    ) -> IOSChatBackgroundHandoff {
        IOSChatBackgroundHandoff(
            runId: job.runId,
            startedAt: job.startedAt,
            inputDigest: job.inputDigest,
            conversationId: job.conversationId,
            providerId: job.providerSetting.id.toHexDashString(),
            providerSetting: job.providerSetting,
            params: job.params,
            uploadMessages: job.uploadMessages,
            displayMessages: job.displayMessages,
            mode: .resumeResponse,
            responseId: job.responseId,
            responseSequenceNumber: job.responseSequenceNumber,
            generativeUiRequirement: job.generativeUiRequirement,
            generativeUiFallbackAttempted: job.generativeUiFallbackAttempted,
            fullToolNames: job.toolExposureBridge.fullToolDeclarations().map(\.name)
        )
    }

    private func cancelRemoteResponse(for job: IOSChatBackgroundRuntimeJob) {
        guard let openAI = job.providerSetting as? ProviderSetting.OpenAI,
              let responseId = job.responseId else { return }
        _ = try? OpenAIResponsesBackgroundTransport().cancelBackground(
            providerSetting: openAI,
            responseId: responseId,
            customHeaders: job.params.customHeaders,
            onComplete: {},
            onError: { _ in }
        )
    }

    private func cancelDetachedResponseTransport(
        requestId: String,
        job: IOSChatBackgroundRuntimeJob
    ) {
        activeDetachedResponseTasks[requestId]?.cancel()
        activeDetachedResponseJobs.removeValue(forKey: requestId)?.cancel(cause: nil)
        activeDetachedResponseCompletions.removeValue(forKey: requestId)?
            .resolve(.disconnected("cancelled"))
        cancelRemoteResponse(for: job)
    }

    private func completeDetachedResponse(
        job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        messages: [UIMessage],
        runState: IOSChatBackgroundRunState
    ) async {
        let miniAppApplication = job.saveMiniAppIfPresent?(messages, job.conversationId)
        let didSave = await job.conversationStore.saveBackgroundCompletion(
            baseMessages: job.displayMessages,
            completedMessages: messages,
            to: job.conversationId
        )
        if didSave {
            _ = miniAppApplication?.commit()
        } else {
            _ = miniAppApplication?.rollback()
        }
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: didSave ? .completed : .recoveryPending,
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        guard runState.finalizeTerminal() else { return }
        if didSave {
            notifyRunTerminal(job: job, runId: job.runId, finalMessages: messages)
            WatchTaskCoordinator.shared.publishCompleted(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                summary: Self.backgroundSummary(from: messages)
            )
            await job.liveActivityController.end(runId: job.runId, presentation: .completed())
            finish(runId: job.runId, requestId: requestId)
        } else {
            WatchTaskCoordinator.shared.publish(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                presentation: .failed(),
                summary: "回复已完成，但保存失败。"
            )
            await job.liveActivityController.end(runId: job.runId, presentation: .failed())
            activeRunStates[requestId] = IOSChatBackgroundRunState()
        }
    }

    private func handle(_ backgroundTask: BGContinuedProcessingTask) async {
        let mappedRunId = taskMap()[backgroundTask.identifier]
        // 先标记“系统已经把这一轮交回来了”，再做 payload 解码。冷启动扫尾
        // 可能和 handler 同时被调度；只要 handler 已经到达这里，就不能把它误判
        // 成 App Switcher 强杀留下的 stale request。
        activeBackgroundTasks[backgroundTask.identifier] = backgroundTask
        guard let job = job(for: backgroundTask.identifier) else {
            backgroundTask.updateTitle("Amber 后台生成", subtitle: "无法恢复任务")
            if let mappedRunId {
                let controller = dependencies?.liveActivityController ?? .shared
                _ = controller.adoptExistingActivity(runId: mappedRunId)
                await controller.end(runId: mappedRunId, presentation: .failed())
                await markRunInterrupted(
                    runId: mappedRunId,
                    reason: "background_payload_unavailable"
                )
            }
            finish(requestId: backgroundTask.identifier)
            backgroundTask.setTaskCompleted(success: false)
            return
        }
        let runState = activeRunStates[backgroundTask.identifier] ?? IOSChatBackgroundRunState()
        activeRunStates[backgroundTask.identifier] = runState
        IOSBackgroundLifecycleLog.record(
            "bgTaskStarted(run=\(job.runId.prefix(8)))",
            detail: lifecycleSnapshotDetail
        )
        let assistantTextSnapshot = IOSChatBackgroundAssistantTextSnapshot()
        let progress = backgroundTask.progress
        progress.totalUnitCount = 4
        progress.completedUnitCount = 0
        backgroundTask.updateTitle("Amber 后台生成", subtitle: "准备上下文")
        _ = job.liveActivityController.adoptExistingActivity(
            runId: job.runId,
            conversationId: job.conversationId.toHexDashString()
        )
        backgroundTask.expirationHandler = { [weak self] in
            guard let self else {
                backgroundTask.setTaskCompleted(success: false)
                return
            }

            // `expirationHandler` is the last synchronous callback before iOS may
            // terminate the process. Reserve the terminal owner and claim the
            // system task here, before hopping to MainActor for persistence/UI
            // cleanup. Waiting for the hop can leave BGTask uncompleted at the
            // deadline, especially while the app is suspended.
            let claim = runState.expireAndReserveTerminal()
            if claim == .rejected {
                guard runState.terminalWasFinalized(by: .completion),
                      runState.claimSystemTaskCompletion() else { return }
                backgroundTask.setTaskCompleted(success: false)
                Task { @MainActor in
                    self.finish(requestId: backgroundTask.identifier)
                }
                return
            }
            guard runState.claimSystemTaskCompletion() else { return }
            backgroundTask.setTaskCompleted(success: false)

            Task { @MainActor in
                guard runState.finalizeTerminal(as: .expiration) else { return }
                IOSBackgroundLifecycleLog.record(
                    "bgTaskExpired(claim=\(claim))",
                    detail: self.lifecycleSnapshotDetail
                )
                switch claim {
                case .persistFailure:
                    let didSave = await self.persistExpirationFailure(
                        job: job,
                        requestId: backgroundTask.identifier,
                        rawMessage: "后台生成已停止，可以重试。",
                        partialAssistantText: assistantTextSnapshot.text
                    )
                    if didSave {
                        self.finish(runId: job.runId, requestId: backgroundTask.identifier)
                    } else {
                        self.releaseRuntimeOwnership(requestId: backgroundTask.identifier)
                    }
                case .terminateInFlightSave:
                    // 会话写入已经开始，无法原子取消；由保存结果决定最终呈现，避免双终态。
                    break
                case .rejected:
                    self.finish(requestId: backgroundTask.identifier)
                }
            }
        }

        let requestProvider: ProviderSetting
        let requestParams: TextGenerationParams
        do {
            requestProvider = try await IOSCodexProviderResolver.resolved(job.providerSetting)
            requestParams = IOSCodexProviderResolver.augmentParamsForCodex(job.params, provider: requestProvider)
        } catch {
            await fail(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState,
                rawMessage: (error as NSError).localizedDescription
            )
            return
        }

        let runningPresentation = job.mode == .singleToolOnly
            ? AgentActivityPresentation.runningTool(toolName: "generate_image")
            : AgentActivityPresentation.response(
                stage: AgentActivityResponseStagePolicy.initialStage
            )
        await publishRunningPresentation(runningPresentation, for: job, runState: runState)

        progress.completedUnitCount = 1
        backgroundTask.updateTitle(
            "Amber 后台生成",
            subtitle: job.mode == .singleToolOnly ? "正在执行工具" : "正在生成回复"
        )

        let engine = IOSAgentToolEngine(
            provider: IOSChatBackgroundProvider(),
            executors: job.toolRuntime.backgroundToolExecutors(
                providerSetting: requestProvider,
                params: requestParams,
                runId: job.runId,
                toolExposureBridge: job.toolExposureBridge,
                // P1-c: run 锚定会话——后台 job 的 spawn/list/interrupt 以
                // 本 job 的 conversationId 为父，不读 VM 当前会话。
                conversationId: job.conversationId,
                // Pad-image enrich must use the job-frozen display snapshot.
                messages: job.displayMessages
            ),
            // M2: 传了 toolExposureBridge 的路径，每轮 replacingTools 后按当轮
            // effectiveParams 重建 executor 表——tool_search 命中工具下一轮
            // 「声明且可执行」；闭包捕获的是当轮 params/bridge（与首次注册
            // 同一入口）。未传桥的 SubAgent/Novel 路径（本文件外构造点）保持
            // 静态表不变。
            // G7: 后台续跑步数上限保持 6（引擎默认 8）。理由：后台续跑是前台预算
            // 之外的第二道防线，跑在无人盯屏的电池/流量预算上；前台上限已参数化
            // （默认 12），交互式长链由前台设置自控，后台保持较短预算以约束静默耗电。
            // 若后续发现后台续跑频繁在 6 步被掐断，再同步到引擎默认 8。
            configuration: .init(maxSteps: 6, honorApprovalPause: false),
            ledger: toolLedger,
            ledgerRunId: job.runId,
            executorRebuilder: { params in
                job.toolRuntime.backgroundToolExecutors(
                    providerSetting: requestProvider,
                    params: params,
                    runId: job.runId,
                    toolExposureBridge: job.toolExposureBridge,
                    conversationId: job.conversationId,
                    messages: job.displayMessages
                )
            }
        )
        // P1-d: 后台引擎 mailbox drain——每轮批量执行后把本会话信封渲染折入下一轮
        // upload 并持久化进会话（后台无 UI 上屏，折入 = 持久化 + 入 working）。
        // 前后台双 drain 由 MailboxDao.drainPending 的事务加固兜底（loser 返回空）。
        let mailboxStore = IOSMailboxStore(mailboxDao: self.db.mailboxDao())
        let mailboxDrain: @Sendable () async -> IOSMailboxDrainResult = { [weak self] in
            guard let self else { return IOSMailboxDrainResult(values: []) }
            // 渲染 + 会话持久化全部在 MainActor 助手内完成（KMP UIMessage 非
            // Sendable，不跨隔离边界传递实体）；返回盒携带渲染出的 user 消息。
            return await self.drainMailboxForBackgroundJob(
                store: job.conversationStore,
                mailboxStore: mailboxStore,
                conversationId: job.conversationId
            )
        }
        let presentationEvents = AsyncStream<AgentActivityPresentation>.makeStream()
        let presentationConsumer = Task { @MainActor in
            for await presentation in presentationEvents.stream {
                await self.publishRunningPresentation(
                    presentation,
                    for: job,
                    runState: runState
                )
            }
        }
        // P2-c: 后台流 citation 隐藏标记剥离。每个 engine.run 一个 tracker
        // （finish 幂等收口该 run 的未闭合标签；generative UI retry 是第二次
        // 独立生成，不共享 tracker——跨 run 复用会把上一 run 的 citations 串进
        // 下一 run）。引擎终结处已把剩余可见文本并入终态消息；run 结束后这里在
        // MainActor 上把收集到的引用 id 落 markUsed——引用是模型显式信号 →
        // force（不受 P2-b 召回同集去抖影响）。
        let initialCitationTracker = IOSMemoryCitationTracker()
        let retryCitationTracker = IOSMemoryCitationTracker()
        let operationTask = Task { () -> IOSAgentToolEngineResult in
            switch job.mode {
            case .continueModel, .resumeResponse:
                let initialResult = await engine.run(
                    providerSetting: requestProvider,
                    messages: job.uploadMessages,
                    params: requestParams,
                    citationTracker: initialCitationTracker,
                    toolExposureBridge: job.toolExposureBridge,
                    mailboxDrain: mailboxDrain,
                    onAssistantTurnStarted: {
                        assistantTextSnapshot.replace(with: "")
                        presentationEvents.continuation.yield(
                            AgentActivityPresentation.response(
                                stage: AgentActivityResponseStagePolicy.initialStage
                            )
                        )
                    },
                    onToolExecutionStarted: { toolName in
                        presentationEvents.continuation.yield(
                            AgentActivityPresentation.runningTool(toolName: toolName)
                        )
                    },
                    onAssistantStage: { stage in
                        presentationEvents.continuation.yield(
                            AgentActivityPresentation.response(stage: stage)
                        )
                    },
                    onAssistantText: { text in
                        assistantTextSnapshot.replace(with: text)
                    },
                    onMessagesUpdated: { messages in
                        job.messagesSnapshot.replace(with: messages)
                    }
                )
                guard !job.generativeUiFallbackAttempted,
                      !initialResult.wasCancelled,
                      !Task.isCancelled,
                      initialResult.providerFailureMessage == nil || initialResult.hitOutputLimit,
                      initialResult.pendingApproval == nil,
                      !initialResult.hitStepLimit,
                      !initialResult.guardStopped,
                      !job.toolRuntime.hasUnresolvedToolCall(in: initialResult.messages),
                      let widgetIssue = IOSGenerativeUiRequestPolicy.widgetIssue(
                        in: initialResult.messages,
                        afterDisplayMessageCount: job.uploadMessages.count,
                        requirement: job.generativeUiRequirement
                      ) else {
                    return initialResult
                }
                let retryBase = IOSGenerativeUiRequestPolicy.retryBaseMessages(initialResult.messages)
                let retryDisplayMessages = Self.reconciledMessages(
                    resultMessages: retryBase,
                    uploadMessageCount: job.uploadMessages.count,
                    displayMessages: job.displayMessages
                )
                let retryMessages = IOSGenerativeUiRequestPolicy.retryMessages(
                    retryBase,
                    requirement: job.generativeUiRequirement,
                    issue: widgetIssue
                )
                let retryUpload = IOSChatBackgroundRetryMessages(
                    values: ChatRuntimeContextBuilder.coalescingSystemMessages(retryMessages)
                )
                let retryUploadMessageCount = retryUpload.values.count
                let retryParams = IOSGenerativeUiRequestPolicy.retryParams(requestParams)
                guard self.persistGenerativeUiRetryCheckpoint(
                    for: job,
                    requestId: backgroundTask.identifier,
                    uploadMessages: retryUpload.values,
                    displayMessages: retryDisplayMessages,
                    params: retryParams
                ) else {
                    return IOSAgentToolEngineResult(
                        messages: initialResult.messages,
                        stepsExecuted: initialResult.stepsExecuted,
                        pendingApproval: initialResult.pendingApproval,
                        hitStepLimit: initialResult.hitStepLimit,
                        providerFailureMessage: "Unable to persist the required visual retry checkpoint.",
                        hitOutputLimit: initialResult.hitOutputLimit,
                        wasCancelled: initialResult.wasCancelled,
                        guardStopped: initialResult.guardStopped
                    )
                }
                let retryResult = await engine.run(
                    providerSetting: requestProvider,
                    messages: retryUpload.values,
                    params: retryParams,
                    citationTracker: retryCitationTracker,
                    toolExposureBridge: job.toolExposureBridge,
                    mailboxDrain: mailboxDrain,
                    onAssistantTurnStarted: {
                        assistantTextSnapshot.replace(with: "")
                        presentationEvents.continuation.yield(
                            AgentActivityPresentation.response(
                                stage: AgentActivityResponseStagePolicy.initialStage
                            )
                        )
                    },
                    onToolExecutionStarted: { toolName in
                        presentationEvents.continuation.yield(
                            AgentActivityPresentation.runningTool(toolName: toolName)
                        )
                    },
                    onAssistantStage: { stage in
                        presentationEvents.continuation.yield(
                            AgentActivityPresentation.response(stage: stage)
                        )
                    },
                    onAssistantText: { text in
                        assistantTextSnapshot.replace(with: text)
                    },
                    onMessagesUpdated: { messages in
                        job.messagesSnapshot.replace(
                            with: retryBase + Array(messages.dropFirst(retryUploadMessageCount))
                        )
                    }
                )
                return IOSAgentToolEngineResult(
                    messages: retryBase + Array(retryResult.messages.dropFirst(retryUploadMessageCount)),
                    stepsExecuted: retryResult.stepsExecuted,
                    pendingApproval: retryResult.pendingApproval,
                    hitStepLimit: retryResult.hitStepLimit,
                    providerFailureMessage: retryResult.providerFailureMessage,
                    hitOutputLimit: retryResult.hitOutputLimit,
                    wasCancelled: retryResult.wasCancelled,
                    guardStopped: retryResult.guardStopped
                )
            case .singleToolOnly:
                return IOSAgentToolEngineResult(
                    messages: await engine.executePreExistingToolsOnly(messages: job.uploadMessages),
                    stepsExecuted: 0,
                    pendingApproval: nil,
                    hitStepLimit: false
                )
            }
        }
        runState.installOperationTask(operationTask)
        let result = await operationTask.value
        job.messagesSnapshot.replace(with: result.messages)
        // P2-c: 引擎终结处 finish() 已把引用 id 收齐（幂等）；模型显式引用 →
        // markUsed(force: true)，与前台 citation flush 同语义。空集合 no-op。
        IOSMemoryPersistence.shared.markUsed(ids: initialCitationTracker.citationIds, force: true)
        IOSMemoryPersistence.shared.markUsed(ids: retryCitationTracker.citationIds, force: true)
        presentationEvents.continuation.finish()
        await presentationConsumer.value
        runState.clearOperationTask()
        guard runState.reserveTerminal() else { return }

        progress.completedUnitCount = 3
        backgroundTask.updateTitle("Amber 后台生成", subtitle: "正在保存结果")

        var reconciledMessages = job.mode == .continueModel
            ? Self.reconciledMessages(
                resultMessages: result.messages,
                uploadMessageCount: job.uploadMessages.count,
                displayMessages: job.displayMessages
            )
            : result.messages
        if job.mode == .continueModel,
           IOSGenerativeUiRequestPolicy.widgetIssue(
               in: reconciledMessages,
               afterDisplayMessageCount: job.displayMessages.count,
               requirement: job.generativeUiRequirement
           ) != nil {
            reconciledMessages = IOSGenerativeUiRequestPolicy.terminalRepairFailureMessages(
                reconciledMessages
            )
        }
        let generatedSuffix = job.mode == .continueModel
            ? Array(reconciledMessages.dropFirst(job.displayMessages.count))
            : []
        if job.mode == .continueModel, result.hitOutputLimit {
            await completeTruncatedAfterTerminalReservation(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState,
                reconciledMessages: reconciledMessages
            )
            return
        }
        if job.mode == .continueModel, let rawFailure = result.providerFailureMessage {
            await failAfterTerminalReservation(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState,
                terminalOwner: .completion,
                rawMessage: rawFailure,
                preservedGeneratedSuffix: Array(generatedSuffix.dropLast()),
                partialAssistantText: assistantTextSnapshot.text
            )
            return
        }

        var finalMessages = reconciledMessages
        if job.mode == .continueModel,
           job.toolRuntime.hasUnresolvedToolCall(in: finalMessages) {
            finalMessages = job.toolRuntime.messagesByFailingPendingToolCalls(
                in: finalMessages,
                failureReason: "The tool was unavailable during background continuation."
            )
        }
        if job.mode == .continueModel, result.hitStepLimit {
            finalMessages.append(Self.assistantMessage("后台生成已达到工具循环上限，已保存当前结果。"))
        }
        // I-5: same visibility contract as the hitStepLimit notice above — the
        // engine already wrote a per-tool stop explanation into that tool's
        // output; this appends a chat-level notice so the user does not read
        // the transcript as an ordinary, uninterrupted completion.
        //
        // F6 fix: this notice alone used to be the ONLY trace of the stop —
        // the terminal status below still recorded "completed" and published
        // `.completed()`/`publishCompleted`, so Watch/LiveActivity/`agent_run`
        // all read this as an ordinary successful finish. That is I-5 failing
        // silently specifically on the background path (the foreground
        // sibling, `terminatePendingToolCalls`, records shared status `failed`
        // and publishes `.failed()`). Capture the notice text here so the
        // terminal-status block below can align background with foreground.
        var guardStoppedNotice: String?
        if job.mode == .continueModel, result.guardStopped {
            let notice = "模型连续以相同参数重复调用工具，已停止本轮以避免空耗，已保存当前结果。"
            finalMessages.append(Self.assistantMessage(notice))
            guardStoppedNotice = notice
        }
        let emptyMiniAppResponse = job.mode == .continueModel &&
            ChatRuntimeContextBuilder.miniAppTurnContext(in: job.displayMessages) != nil &&
            ChatGenerationCoordinator.isEmptyAssistantResponse(finalMessages)
        if emptyMiniAppResponse {
            finalMessages.append(ChatGenerationCoordinator.emptyMiniAppResponseNotice())
        }
        let miniAppApplication = job.mode == .continueModel
            ? job.saveMiniAppIfPresent?(finalMessages, job.conversationId)
            : nil
        if let miniAppApplication {
            finalMessages = miniAppApplication.messages
        }
        let miniAppFailed = emptyMiniAppResponse || miniAppApplication?.outcome == .failed
        let singleToolFailureReason = job.mode == .singleToolOnly
            ? ChatToolOutputFormatter.imageFailureReason(in: finalMessages)
            : nil
        let watchSummary = Self.backgroundSummary(from: finalMessages)

        let didSave: Bool
        switch job.mode {
        case .continueModel, .resumeResponse:
            didSave = await job.conversationStore.saveBackgroundCompletion(
                baseMessages: job.displayMessages,
                completedMessages: finalMessages,
                to: job.conversationId
            )
        case .singleToolOnly:
            didSave = await job.conversationStore.saveBackgroundToolCompletion(
                baseMessages: job.displayMessages,
                completedMessages: finalMessages,
                to: job.conversationId
            )
        }
        if !didSave,
           let miniAppApplication,
           !miniAppApplication.rollback() {
            NSLog("[AmberChatBG] MiniApp rollback skipped because its persisted state changed")
        }
        if didSave,
           let miniAppApplication,
           !miniAppApplication.commit() {
            NSLog("[AmberChatBG] MiniApp transaction commit remains pending for cold-start reconciliation")
        }
        if didSave,
           let workspaceFailure = miniAppApplication?.syncWorkspaceAfterConversationPersistence() {
            finalMessages = workspaceFailure.messages
            _ = await job.conversationStore.replaceBackgroundMessage(
                workspaceFailure.replacementMessage,
                in: job.conversationId
            )
        }
        guard runState.finalizeTerminal() else {
            if runState.terminalIsOwned(by: .expiration) {
                await resolveExpiredInFlightSave(
                    job: job,
                    requestId: backgroundTask.identifier,
                    didSave: didSave,
                    singleToolFailureReason: singleToolFailureReason,
                    guardStoppedNotice: guardStoppedNotice,
                    miniAppFailed: miniAppFailed,
                    summary: watchSummary,
                    completedMessages: finalMessages
                )
            }
            return
        }
        guard didSave else {
            backgroundTask.updateTitle("Amber 后台生成", subtitle: "保存结果失败")
            await completeAsFailureAfterSaveFailure(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState
            )
            return
        }
        let runStatus = Self.backgroundTerminalStatus(
            didSave: didSave,
            singleToolFailureReason: singleToolFailureReason,
            guardStopped: guardStoppedNotice != nil,
            miniAppFailed: miniAppFailed
        )
        let succeeded = runStatus == .completed
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: runStatus,
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        notifyRunTerminal(job: job, runId: job.runId, finalMessages: finalMessages)
        if succeeded {
            WatchTaskCoordinator.shared.publishCompleted(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                summary: watchSummary
            )
            // 与前台 generationSucceeded → list preview 对齐：用本 job 的消息快照，不读可能已切换的 ChatViewModel。
            if let settings = dependencies?.sharedSettings {
                ConversationListPreviewGenerator.schedule(
                    conversationId: job.conversationId,
                    messages: finalMessages,
                    store: job.conversationStore,
                    settings: settings
                )
            }
        } else {
            WatchTaskCoordinator.shared.publish(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                presentation: .failed(),
                summary: guardStoppedNotice.flatMap { WatchTaskText.clipped($0, maxLength: 200) }
                    ?? (miniAppFailed ? watchSummary : nil)
            )
        }
        await job.liveActivityController.end(
            runId: job.runId,
            presentation: succeeded ? .completed() : .failed()
        )
        progress.completedUnitCount = progress.totalUnitCount
        if runState.claimSystemTaskCompletion() {
            finish(runId: job.runId, requestId: backgroundTask.identifier)
            backgroundTask.setTaskCompleted(success: succeeded)
        } else {
            removePayload(requestId: backgroundTask.identifier)
        }
    }

    private func completeTruncatedAfterTerminalReservation(
        job: IOSChatBackgroundRuntimeJob,
        backgroundTask: BGContinuedProcessingTask,
        runState: IOSChatBackgroundRunState,
        reconciledMessages: [UIMessage]
    ) async {
        var finalMessages = reconciledMessages
        if job.toolRuntime.hasUnresolvedToolCall(in: finalMessages) {
            finalMessages = job.toolRuntime.messagesByFailingPendingToolCalls(
                in: finalMessages,
                failureReason: "The model output ended before the tool call completed."
            )
        }
        finalMessages.append(ChatGenerationCoordinator.outputLimitNotice())
        let didSave = await job.conversationStore.saveBackgroundCompletion(
            baseMessages: job.displayMessages,
            completedMessages: finalMessages,
            to: job.conversationId
        )
        let didFinalize = runState.finalizeTerminal()
        guard didFinalize else {
            if runState.terminalIsOwned(by: .expiration) {
                await publishTruncatedTerminal(
                    job: job,
                    requestId: backgroundTask.identifier,
                    didSave: didSave,
                    summary: Self.backgroundSummary(from: finalMessages)
                )
            }
            return
        }
        guard didSave else {
            backgroundTask.updateTitle("Amber 后台生成", subtitle: "保存截断回复失败")
            await completeAsFailureAfterSaveFailure(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState
            )
            return
        }

        notifyRunTerminal(job: job, runId: job.runId, finalMessages: finalMessages)

        await publishTruncatedTerminal(
            job: job,
            requestId: backgroundTask.identifier,
            didSave: true,
            summary: Self.backgroundSummary(from: finalMessages)
        )
        backgroundTask.updateTitle("Amber 后台生成", subtitle: "回复达到输出上限")
        backgroundTask.progress.completedUnitCount = backgroundTask.progress.totalUnitCount
        if runState.claimSystemTaskCompletion() {
            backgroundTask.setTaskCompleted(success: true)
        }
    }

    private func publishTruncatedTerminal(
        job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        didSave: Bool,
        summary: String?
    ) async {
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: didSave ? .failed : .recoveryPending,
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        WatchTaskCoordinator.shared.publish(
            runId: job.runId,
            conversationId: job.conversationId.toHexDashString(),
            presentation: .failed(),
            summary: summary
        )
        await job.liveActivityController.end(runId: job.runId, presentation: .failed())
        finish(runId: job.runId, requestId: requestId)
    }

    private func fail(
        job: IOSChatBackgroundRuntimeJob,
        backgroundTask: BGContinuedProcessingTask,
        runState: IOSChatBackgroundRunState,
        rawMessage: String,
        preservedGeneratedSuffix: [UIMessage] = [],
        partialAssistantText: String? = nil
    ) async {
        guard runState.reserveTerminal() else { return }
        await failAfterTerminalReservation(
            job: job,
            backgroundTask: backgroundTask,
            runState: runState,
            terminalOwner: .completion,
            rawMessage: rawMessage,
            preservedGeneratedSuffix: preservedGeneratedSuffix,
            partialAssistantText: partialAssistantText
        )
    }

    private func failAfterTerminalReservation(
        job: IOSChatBackgroundRuntimeJob,
        backgroundTask: BGContinuedProcessingTask,
        runState: IOSChatBackgroundRunState,
        terminalOwner: IOSChatBackgroundRunState.TerminalOwner,
        rawMessage: String,
        preservedGeneratedSuffix: [UIMessage] = [],
        partialAssistantText: String? = nil
    ) async {
        let reconciledBase = Self.reconciledDisplayPrefix(
            resultMessages: job.messagesSnapshot.messages,
            uploadMessageCount: job.uploadMessages.count,
            displayMessages: job.displayMessages
        )
        var finalMessages = Self.failedMessages(
            displayMessages: reconciledBase,
            preservedGeneratedSuffix: preservedGeneratedSuffix,
            partialAssistantText: partialAssistantText,
            rawMessage: rawMessage,
            modelId: job.params.model.modelId
        )
        if job.toolRuntime.hasUnresolvedToolCall(in: finalMessages) {
            finalMessages = job.toolRuntime.messagesByFailingPendingToolCalls(
                in: finalMessages,
                failureReason: "Generation failed before the tool call completed."
            )
        }
        let didSave = await job.conversationStore.saveBackgroundCompletion(
            baseMessages: job.displayMessages,
            completedMessages: finalMessages,
            to: job.conversationId
        )
        let didFinalize = terminalOwner == .completion
            ? runState.finalizeTerminal()
            : runState.finalizeTerminal(as: terminalOwner)
        guard didFinalize else {
            if runState.terminalIsOwned(by: .expiration) {
                if didSave {
                    removePayload(requestId: backgroundTask.identifier)
                }
                await recordRun(
                    job.runId,
                    startedAt: job.startedAt,
                    status: didSave ? .failed : .recoveryPending,
                    inputDigest: job.inputDigest,
                    conversationId: job.conversationId
                )
                WatchTaskCoordinator.shared.publish(
                    runId: job.runId,
                    conversationId: job.conversationId.toHexDashString(),
                    presentation: .failed()
                )
                await job.liveActivityController.end(runId: job.runId, presentation: .failed())
                finish(runId: job.runId, requestId: backgroundTask.identifier)
            }
            return
        }
        guard didSave else {
            backgroundTask.updateTitle("Amber 后台生成", subtitle: "无法保存失败状态")
            await completeAsFailureAfterSaveFailure(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState
            )
            return
        }
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: .failed,
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        notifyRunTerminal(job: job, runId: job.runId, finalMessages: finalMessages)
        WatchTaskCoordinator.shared.publish(
            runId: job.runId,
            conversationId: job.conversationId.toHexDashString(),
            presentation: .failed()
        )
        await job.liveActivityController.end(runId: job.runId, presentation: .failed())
        if runState.claimSystemTaskCompletion() {
            finish(runId: job.runId, requestId: backgroundTask.identifier)
            backgroundTask.setTaskCompleted(success: false)
        } else {
            removePayload(requestId: backgroundTask.identifier)
        }
    }

    /// 冷启动时收口 App Switcher 强杀留下的后台 request。
    ///
    /// `BGContinuedProcessingTask` 被用户上划关闭时，系统不会调用应用的
    /// expiration handler；因此 payload 和 task map 会停在“running”。这个入口
    /// 只清理没有正在执行 handler 的持久化 request，不重新提交模型请求。调用方
    /// 应在普通 UI 冷启动完成后调用；实际已被系统唤起的 handler 会先登记到
    /// `activeBackgroundTasks`，从而保留给 `handle` 继续处理。重复调用是幂等的。
    func finalizeStalePersistedJobsIfNeeded() {
        let persisted = taskMap()
        guard !persisted.isEmpty else { return }

        IOSBackgroundLifecycleLog.record(
            "staleBackgroundSweep(pending=\(persisted.count))",
            detail: lifecycleSnapshotDetail
        )
        for (requestId, mappedRunId) in persisted {
            guard activeBackgroundTasks[requestId] == nil else {
                continue
            }
            if activeDetachedResponseTasks[requestId] != nil {
                continue
            }
            if let durableJob = job(for: requestId),
               durableJob.mode == .resumeResponse,
               durableJob.responseId != nil {
                continue
            }

            // `.queue`/遗留 request 不会因应用被杀而自行消失；先撤掉它，防止
            // 扫尾后系统又唤起一张已经被标记停止的后台卡。
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: requestId)

            guard let job = job(for: requestId) else {
                // payload 已不可恢复时，至少把 agent_run 从 running 收口；不留
                // 一个下次启动仍会被当作后台 owner 的 map 条目。
                finish(requestId: requestId)
                let controller = dependencies?.liveActivityController ?? .shared
                _ = controller.adoptExistingActivity(runId: mappedRunId)
                Task { @MainActor in
                    await controller.end(runId: mappedRunId, presentation: .failed())
                    await self.markRunInterrupted(
                        runId: mappedRunId,
                        reason: "app_terminated"
                    )
                }
                continue
            }

            // 先摘掉 task map、内存 job 和 payload，让重复扫尾及重新挂载 UI
            // 都立即看见“已停止”；下面只用内存快照写一条可重试终态。
            finish(runId: job.runId, requestId: requestId)
            Task { @MainActor in
                // 没有可重放的 cursor；把 handoff 快照收口为一次明确的可重试
                // 失败。request owner 已在上面摘除，这里不创建新的后台提交。
                await self.persistExpirationFailure(
                    job: job,
                    requestId: requestId,
                    rawMessage: "后台生成已停止，可以重试。",
                    partialAssistantText: nil
                )
            }
        }
    }

    private func persistExpirationFailure(
        job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        rawMessage: String,
        partialAssistantText: String?
    ) async -> Bool {
        let reconciledBase = Self.reconciledDisplayPrefix(
            resultMessages: job.messagesSnapshot.messages,
            uploadMessageCount: job.uploadMessages.count,
            displayMessages: job.displayMessages
        )
        var finalMessages = Self.failedMessages(
            displayMessages: reconciledBase,
            partialAssistantText: partialAssistantText,
            rawMessage: rawMessage,
            modelId: job.params.model.modelId
        )
        if job.toolRuntime.hasUnresolvedToolCall(in: finalMessages) {
            finalMessages = job.toolRuntime.messagesByFailingPendingToolCalls(
                in: finalMessages,
                failureReason: "Generation failed before the tool call completed."
            )
        }
        let didSave = await job.conversationStore.saveBackgroundCompletion(
            baseMessages: job.displayMessages,
            completedMessages: finalMessages,
            to: job.conversationId
        )
        if didSave {
            removePayload(requestId: requestId)
        }
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: didSave ? .failed : .recoveryPending,
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        notifyRunTerminal(job: job, runId: job.runId, finalMessages: finalMessages)
        WatchTaskCoordinator.shared.publish(
            runId: job.runId,
            conversationId: job.conversationId.toHexDashString(),
            presentation: .failed()
        )
        await job.liveActivityController.end(runId: job.runId, presentation: .failed())
        return didSave
    }

    private func resolveExpiredInFlightSave(
        job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        didSave: Bool,
        singleToolFailureReason: String?,
        guardStoppedNotice: String?,
        miniAppFailed: Bool,
        summary: String?,
        completedMessages: [UIMessage]
    ) async {
        let runStatus = Self.backgroundTerminalStatus(
            didSave: didSave,
            singleToolFailureReason: singleToolFailureReason,
            guardStopped: guardStoppedNotice != nil,
            miniAppFailed: miniAppFailed
        )
        let succeeded = runStatus.wireName == AgentRunStatus.completed.wireName
        if didSave {
            removePayload(requestId: requestId)
        }
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: runStatus,
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        notifyRunTerminal(job: job, runId: job.runId, finalMessages: completedMessages)
        if succeeded {
            WatchTaskCoordinator.shared.publishCompleted(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                summary: summary
            )
            if let settings = dependencies?.sharedSettings {
                ConversationListPreviewGenerator.schedule(
                    conversationId: job.conversationId,
                    messages: completedMessages,
                    store: job.conversationStore,
                    settings: settings
                )
            }
        } else {
            WatchTaskCoordinator.shared.publish(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                presentation: .failed(),
                summary: guardStoppedNotice.flatMap { WatchTaskText.clipped($0, maxLength: 200) }
                    ?? (miniAppFailed ? summary : nil)
            )
        }
        await job.liveActivityController.end(
            runId: job.runId,
            presentation: succeeded ? .completed() : .failed()
        )
        finish(runId: job.runId, requestId: requestId)
    }

    private func completeAsFailureAfterSaveFailure(
        job: IOSChatBackgroundRuntimeJob,
        backgroundTask: BGContinuedProcessingTask,
        runState: IOSChatBackgroundRunState
    ) async {
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: .recoveryPending,
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        WatchTaskCoordinator.shared.publish(
            runId: job.runId,
            conversationId: job.conversationId.toHexDashString(),
            presentation: .failed()
        )
        await job.liveActivityController.end(runId: job.runId, presentation: .failed())
        if runState.claimSystemTaskCompletion() {
            releaseRuntimeOwnership(requestId: backgroundTask.identifier)
            backgroundTask.setTaskCompleted(success: false)
        }
    }

    /// Release the in-process/system-task owner while retaining the task map
    /// and payload for the existing cold-start reconciliation pass.
    private func releaseRuntimeOwnership(requestId: String) {
        activeJobs.removeValue(forKey: requestId)
        activeRunStates.removeValue(forKey: requestId)
        activeBackgroundTasks.removeValue(forKey: requestId)
    }

    private func job(for requestId: String) -> IOSChatBackgroundRuntimeJob? {
        if let job = activeJobs[requestId] { return job }
        guard let dependencies,
              let handoff = loadHandoff(requestId: requestId) else {
            return nil
        }
        let job = runtimeJob(
            handoff: handoff,
            conversationStore: dependencies.conversationStore,
            toolRuntime: dependencies.toolRuntime,
            liveActivityController: dependencies.liveActivityController,
            saveMiniAppIfPresent: dependencies.saveMiniAppIfPresent
        )
        activeJobs[requestId] = job
        return job
    }

    private func jobs(conversationId: KotlinUuid) -> [String: IOSChatBackgroundRuntimeJob] {
        return activeJobs.filter { _, job in
            String(describing: job.conversationId) == String(describing: conversationId)
        }
    }

    private func persist(handoff: IOSChatBackgroundHandoff, requestId: String) throws {
        let json = IosChatBackgroundPayloadJsonBridge.shared.encode(
            runId: handoff.runId,
            startedAt: handoff.startedAt,
            inputDigest: handoff.inputDigest,
            conversationId: handoff.conversationId,
            providerSetting: handoff.providerSetting,
            params: handoff.params,
            uploadMessages: handoff.uploadMessages,
            displayMessages: handoff.displayMessages,
            mode: handoff.mode.rawValue,
            responseId: handoff.responseId,
            responseSequenceNumber: handoff.responseSequenceNumber.map { KotlinLong(value: $0) },
            generativeUiRequired: handoff.generativeUiRequirement.required,
            generativeUiExpectSlides: handoff.generativeUiRequirement.expectSlides,
            generativeUiExpectFullHtmlDeck: handoff.generativeUiRequirement.expectFullHtmlDeck,
            generativeUiFallbackAttempted: handoff.generativeUiFallbackAttempted,
            fullToolNames: handoff.fullToolNames
        )
        let directory = try jobsDirectory()
        let url = payloadURL(for: requestId, in: directory)
        try Data(json.utf8).write(to: url, options: [.atomic])
    }

    private func persistGenerativeUiRetryCheckpoint(
        for job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        uploadMessages: [UIMessage],
        displayMessages: [UIMessage],
        params: TextGenerationParams
    ) -> Bool {
        let handoff = IOSChatBackgroundHandoff(
            runId: job.runId,
            startedAt: job.startedAt,
            inputDigest: job.inputDigest,
            conversationId: job.conversationId,
            providerId: job.providerSetting.id.toHexDashString(),
            providerSetting: job.providerSetting,
            params: params,
            uploadMessages: uploadMessages,
            displayMessages: displayMessages,
            mode: job.mode,
            generativeUiRequirement: job.generativeUiRequirement,
            generativeUiFallbackAttempted: true,
            fullToolNames: job.toolExposureBridge.fullToolDeclarations().map(\.name)
        )
        do {
            try persist(handoff: handoff, requestId: requestId)
            return true
        } catch {
            NSLog("[AmberChatBG] Failed to persist generative UI retry checkpoint: \(error)")
            return false
        }
    }

    private func loadHandoff(requestId: String) -> IOSChatBackgroundHandoff? {
        do {
            let url = payloadURL(for: requestId, in: try jobsDirectory())
            let data = try Data(contentsOf: url)
            guard let json = String(data: data, encoding: .utf8) else { return nil }
            let payload = try IosChatBackgroundPayloadJsonBridge.shared.decode(json: json)
            guard let providerSetting = providerSetting(for: payload.providerId) else {
                NSLog("[AmberChatBG] Missing background provider \(payload.providerId) for \(requestId)")
                return nil
            }
            guard let dependencies,
                  let params = Self.rehydratedParams(
                    persistedParams: payload.params,
                    providerSetting: providerSetting,
                    assistantHeaders: dependencies.sharedSettings.snapshot.getCurrentAssistant().customHeaders,
                    assistantBodies: dependencies.sharedSettings.snapshot.getCurrentAssistant().customBodies
                  ) else {
                NSLog("[AmberChatBG] Missing current background model for \(requestId)")
                return nil
            }
            return IOSChatBackgroundHandoff(
                runId: payload.runId,
                startedAt: payload.startedAt,
                inputDigest: payload.inputDigest,
                conversationId: payload.conversationId,
                providerId: payload.providerId,
                providerSetting: providerSetting,
                params: params,
                uploadMessages: payload.uploadMessages,
                displayMessages: payload.displayMessages,
                mode: IOSChatBackgroundHandoffMode(rawValue: payload.mode) ?? .continueModel,
                responseId: payload.responseId,
                responseSequenceNumber: payload.responseSequenceNumber?.int64Value,
                generativeUiRequirement: IOSGenerativeUiRequirement(
                    required: payload.generativeUiRequired,
                    expectSlides: payload.generativeUiExpectSlides,
                    expectFullHtmlDeck: payload.generativeUiExpectFullHtmlDeck
                ),
                generativeUiFallbackAttempted: payload.generativeUiFallbackAttempted,
                fullToolNames: payload.fullToolNames
            )
        } catch {
            NSLog("[AmberChatBG] Failed to load background payload \(requestId): \(error)")
            return nil
        }
    }

    private func removePayload(requestId: String) {
        do {
            let url = payloadURL(for: requestId, in: try jobsDirectory())
            try? FileManager.default.removeItem(at: url)
        } catch {
            NSLog("[AmberChatBG] Failed to resolve payload URL for cleanup: \(error)")
        }
    }

    private func jobsDirectory() throws -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let directory = base.appendingPathComponent("ChatBackgroundJobs", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func payloadURL(for requestId: String, in directory: URL) -> URL {
        directory
            .appendingPathComponent(IOSChatBackgroundJobFileNaming.sanitized(requestId))
            .appendingPathExtension("json")
    }

    private func recordRun(
        _ runId: String,
        startedAt: Int64,
        status: AgentRunStatus,
        inputDigest: String,
        conversationId: KotlinUuid,
        interruptedReason: String? = nil
    ) async {
        // 中断原因由调用方给出；历史调用点只有「用户取消」一种，保留为默认值。
        let resolvedInterruptedReason: String? = status == .interrupted
            ? (interruptedReason ?? "user_cancelled")
            : nil

        do {
            if status == .running {
                _ = try await runStore.startChatRun(
                    runId: runId,
                    startedAt: startedAt,
                    inputDigest: inputDigest,
                    conversationId: conversationId.toHexDashString()
                )
            } else {
                _ = try await runStore.transitionFromAnyActive(
                    runId: runId,
                    to: status,
                    detail: resolvedInterruptedReason
                )
            }
        } catch {
            // agent_run 是强杀恢复（applyToolCallLedgerRecovery）依赖的账本，
            // 写失败必须走用户可见错误通道，不能只 print 静默吞掉。
            let detail = "未能写入运行账本：\(error)"
            if let store = dependencies?.conversationStore {
                store.publishUserVisibleError(
                    IOSUserVisibleError(title: "运行状态记录失败", message: detail, severity: .error)
                )
            } else {
                backgroundRunLedgerLogger.error("\(detail)")
            }
        }
    }

    private func markRunInterrupted(runId: String, reason: String) async {
        _ = try? await runStore.transitionFromAnyActive(
            runId: runId,
            to: .interrupted,
            detail: reason
        )
    }

    /// P1-c: 后台 job 终态回传（FINAL_ANSWER 投递由接线方——编排服务——处理；
    /// 每个终态路径调用一次；enqueue 按 runId 幂等去重）。
    private func notifyRunTerminal(
        job: IOSChatBackgroundRuntimeJob,
        runId: String,
        finalMessages: [UIMessage]
    ) {
        guard let onRunTerminal else { return }
        let conversationId = job.conversationId
        Task { @MainActor [onRunTerminal, conversationId, runId, finalMessages] in
            await onRunTerminal(conversationId, runId, finalMessages)
        }
    }

    /// P1-d: 后台 job 的 mailbox drain（引擎每轮批量执行后调用）。Room drain
    /// （事务化 exactly-once）+ 渲染 + 会话持久化全部在 MainActor 内完成；
    /// 返回渲染出的 user 消息（@unchecked Sendable 盒，KMP UIMessage 非
    /// Sendable 不外传）。无信封时返回空盒（引擎零追加）。
    private func drainMailboxForBackgroundJob(
        store: IOSConversationStore,
        mailboxStore: IOSMailboxStore,
        conversationId: KotlinUuid
    ) async -> IOSMailboxDrainResult {
        let snapshots = await mailboxStore.drainPending(forConversationId: conversationId)
        guard !snapshots.isEmpty else { return IOSMailboxDrainResult(values: []) }
        let drained = snapshots.map { envelope in
            UIMessage.companion.user(prompt: MailboxEnvelopeKt.renderMailboxEnvelopeToUserText(
                authorThreadId: envelope.authorThreadId,
                type: envelope.type,
                payload: envelope.payload
            ))
        }
        // 持久化：会话既有消息 + 渲染信封（复用既有写路径与 operationMutex，
        // 不在 Swift 侧另建写通道；drain 事务保证信封不会重复落盘）。
        let current: [UIMessage]
        if store.currentConversation?.id == conversationId {
            current = store.currentConversation?.currentMessages ?? []
        } else {
            current = (try? await store.loadConversationForOrchestration(conversationId))?.currentMessages ?? []
        }
        let baseline = store.writeBaseline(for: conversationId)
        let persisted = await store.save(messages: current + drained, to: conversationId, ifUnchangedSince: baseline)
        if !persisted {
            // 信封已标 delivered，持久化失败时由终态 persist（working 含 drained）兜底；
            // 只记录日志便于诊断，不回滚 drain（会丢投递记录）。
            backgroundMailboxLogger.error(
                "mailbox drain persist failed for \(conversationId) — terminal persist is the fallback"
            )
        }
        return IOSMailboxDrainResult(values: drained)
    }

    private static func assistantMessage(_ text: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private static func backgroundSummary(from messages: [UIMessage]) -> String? {
        guard let lastAssistant = messages.last(where: { $0.role == MessageRole.assistant }) else {
            return nil
        }
        let text = lastAssistant.parts
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return WatchTaskText.clipped(text, maxLength: 280)
    }

    private static func backgroundTerminalStatus(
        didSave: Bool,
        singleToolFailureReason: String?,
        guardStopped: Bool,
        miniAppFailed: Bool = false
    ) -> AgentRunStatus {
        guard didSave else { return .recoveryPending }
        if guardStopped || miniAppFailed { return .failed }
        return singleToolFailureReason == nil ? .completed : .failed
    }

    private static func rehydratedParams(
        persistedParams: TextGenerationParams,
        providerSetting: ProviderSetting,
        assistantHeaders: [CustomHeader],
        assistantBodies: [CustomBody]
    ) -> TextGenerationParams? {
        let persistedModel = persistedParams.model
        let configuredModel = providerSetting.models.first { candidate in
            candidate.id == persistedModel.id
                || (candidate.type == persistedModel.type && candidate.modelId == persistedModel.modelId)
        }
        guard let configuredModel else { return nil }

        let storedHeaders = IOSProviderRequestHeaderStore.headers(
            for: providerSetting.id.description()
        )
        let runtimeHeaders = storedHeaders + assistantHeaders + configuredModel.customHeaders
        let runtimeBodies = assistantBodies + configuredModel.customBodies
        let runtimeModel = Model(
            modelId: configuredModel.modelId,
            displayName: configuredModel.displayName,
            id: configuredModel.id,
            type: configuredModel.type,
            customHeaders: configuredModel.customHeaders,
            customBodies: configuredModel.customBodies,
            inputModalities: configuredModel.inputModalities,
            outputModalities: configuredModel.outputModalities,
            abilities: configuredModel.abilities,
            tools: configuredModel.tools,
            contextWindowTokens: configuredModel.contextWindowTokens,
            providerOverwrite: configuredModel.providerOverwrite
        )
        return TextGenerationParams(
            model: runtimeModel,
            temperature: persistedParams.temperature,
            topP: persistedParams.topP,
            maxTokens: persistedParams.maxTokens,
            tools: persistedParams.tools,
            reasoningLevel: persistedParams.reasoningLevel,
            customHeaders: runtimeHeaders,
            customBody: runtimeBodies
        )
    }

    private static func failedMessages(
        displayMessages: [UIMessage],
        preservedGeneratedSuffix: [UIMessage] = [],
        partialAssistantText: String?,
        rawMessage: String,
        modelId: String
    ) -> [UIMessage] {
        var finalMessages = displayMessages + preservedGeneratedSuffix
        let partial = partialAssistantText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let errorText = ChatViewModel.userFacingGenerationError(rawMessage, modelId: modelId)
        if partial.isEmpty {
            finalMessages.append(assistantMessage(errorText))
        } else {
            finalMessages.append(assistantMessage("\(partial)\n\n\(errorText)"))
        }
        return finalMessages
    }

    private static func reconciledDisplayPrefix(
        resultMessages: [UIMessage],
        uploadMessageCount: Int,
        displayMessages: [UIMessage]
    ) -> [UIMessage] {
        var completedTools: [String: UIMessagePart.Tool] = [:]
        for message in resultMessages.prefix(uploadMessageCount)
            where message.role == MessageRole.assistant {
            for case let tool as UIMessagePart.Tool in message.parts where !tool.output.isEmpty {
                completedTools[chatToolCallKey(tool)] = tool
            }
        }
        guard !completedTools.isEmpty else { return displayMessages }

        return displayMessages.map { message in
            guard message.role == MessageRole.assistant else { return message }
            var didChange = false
            let parts = message.parts.map { part -> UIMessagePart in
                guard let tool = part as? UIMessagePart.Tool,
                      tool.output.isEmpty,
                      let completed = completedTools[chatToolCallKey(tool)] else {
                    return part
                }
                didChange = true
                return completed
            }
            guard didChange else { return message }
            return UIMessage(
                id: message.id,
                role: message.role,
                parts: parts,
                annotations: message.annotations,
                createdAt: message.createdAt,
                finishedAt: message.finishedAt,
                modelId: message.modelId,
                usage: message.usage,
                translation: message.translation
            )
        }
    }

    private static func reconciledMessages(
        resultMessages: [UIMessage],
        uploadMessageCount: Int,
        displayMessages: [UIMessage]
    ) -> [UIMessage] {
        reconciledDisplayPrefix(
            resultMessages: resultMessages,
            uploadMessageCount: uploadMessageCount,
            displayMessages: displayMessages
        ) + Array(resultMessages.dropFirst(uploadMessageCount))
    }

#if DEBUG
    static func rehydratedParamsForTesting(
        persistedParams: TextGenerationParams,
        providerSetting: ProviderSetting,
        assistantHeaders: [CustomHeader],
        assistantBodies: [CustomBody]
    ) -> TextGenerationParams? {
        rehydratedParams(
            persistedParams: persistedParams,
            providerSetting: providerSetting,
            assistantHeaders: assistantHeaders,
            assistantBodies: assistantBodies
        )
    }

    static func backgroundSummaryForTesting(messages: [UIMessage]) -> String? {
        backgroundSummary(from: messages)
    }

    static func backgroundTerminalStatusForTesting(
        didSave: Bool,
        singleToolFailureReason: String?,
        guardStopped: Bool,
        miniAppFailed: Bool = false
    ) -> String {
        backgroundTerminalStatus(
            didSave: didSave,
            singleToolFailureReason: singleToolFailureReason,
            guardStopped: guardStopped,
            miniAppFailed: miniAppFailed
        ).wireName
    }

    static func failedMessagesForTesting(
        displayMessages: [UIMessage],
        preservedGeneratedSuffix: [UIMessage] = [],
        partialAssistantText: String?,
        rawMessage: String,
        modelId: String
    ) -> [UIMessage] {
        failedMessages(
            displayMessages: displayMessages,
            preservedGeneratedSuffix: preservedGeneratedSuffix,
            partialAssistantText: partialAssistantText,
            rawMessage: rawMessage,
            modelId: modelId
        )
    }

    static func reconciledMessagesForTesting(
        resultMessages: [UIMessage],
        uploadMessageCount: Int,
        displayMessages: [UIMessage]
    ) -> [UIMessage] {
        reconciledMessages(
            resultMessages: resultMessages,
            uploadMessageCount: uploadMessageCount,
            displayMessages: displayMessages
        )
    }
#endif

    private func requestIdentifier(for runId: String) -> String {
        requestPrefix + String(runId.map { character -> Character in
            character.isLetter || character.isNumber ? character : "-"
        })
    }

    private func remember(runId: String, requestId: String) {
        var map = taskMap()
        map[requestId] = runId
        UserDefaults.standard.set(map, forKey: taskMapKey)
    }

    private func finish(
        runId: String? = nil,
        requestId: String? = nil,
        removePayload shouldRemovePayload: Bool = true
    ) {
        var map = taskMap()
        var terminatedJobs: [IOSChatBackgroundRuntimeJob] = []
        if let requestId {
            if let job = activeJobs.removeValue(forKey: requestId) {
                terminatedJobs.append(job)
            }
            activeRunStates.removeValue(forKey: requestId)
            activeBackgroundTasks.removeValue(forKey: requestId)
            map.removeValue(forKey: requestId)
            if shouldRemovePayload {
                removePayload(requestId: requestId)
            }
        } else if let runId {
            let matching = map.filter { $0.value == runId }.map(\.key)
            for requestId in matching {
                if let job = activeJobs.removeValue(forKey: requestId) {
                    terminatedJobs.append(job)
                }
                activeRunStates.removeValue(forKey: requestId)
                activeBackgroundTasks.removeValue(forKey: requestId)
                map.removeValue(forKey: requestId)
                if shouldRemovePayload {
                    removePayload(requestId: requestId)
                }
            }
        }
        UserDefaults.standard.set(map, forKey: taskMapKey)
        for job in terminatedJobs {
            publishTerminalEvent(for: job)
        }
    }

    private func publishTerminalEvent(for job: IOSChatBackgroundRuntimeJob) {
        NotificationCenter.default.post(
            name: .amberChatBackgroundJobDidTerminate,
            object: IOSChatBackgroundJobTerminalEvent(
                conversationId: String(describing: job.conversationId)
            )
        )
    }

    private func taskMap() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: taskMapKey) as? [String: String] ?? [:]
    }

    private func providerSetting(for providerId: String) -> ProviderSetting? {
        dependencies?.sharedSettings.snapshot.providers.first {
            $0.id.toHexDashString().caseInsensitiveCompare(providerId) == .orderedSame
        }
    }
}
