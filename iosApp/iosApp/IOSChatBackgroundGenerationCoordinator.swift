@preconcurrency import BackgroundTasks
import Foundation
@preconcurrency import Shared

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
    let generativeUiRequirement: IOSGenerativeUiRequirement
    let generativeUiFallbackAttempted: Bool
    let conversationStore: IOSConversationStore
    let toolRuntime: ChatToolRuntime
    let liveActivityController: AgentLiveActivityController
    let saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> ChatMiniAppOutputApplication?)?
    let messagesSnapshot: IOSChatBackgroundMessagesSnapshot
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
    let mode: IOSChatBackgroundHandoffMode
    let generativeUiRequirement: IOSGenerativeUiRequirement
    let generativeUiFallbackAttempted: Bool
}

enum IOSChatBackgroundHandoffMode: String {
    case continueModel = "continue_model"
    case singleToolOnly = "single_tool_only"
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

struct IOSChatBackgroundJobTerminalEvent: Equatable, Sendable {
    let conversationId: String
}

extension Notification.Name {
    static let amberChatBackgroundJobDidTerminate = Notification.Name(
        "app.amber.ios.chat.backgroundJobDidTerminate"
    )
}

@MainActor
final class IOSChatBackgroundGenerationCoordinator {
    static let shared = IOSChatBackgroundGenerationCoordinator()

    private var bundleIdentifier: String { Bundle.main.bundleIdentifier ?? "app.amber.ios" }
    private var permittedIdentifier: String { "\(bundleIdentifier).chat.*" }
    private var requestPrefix: String { "\(bundleIdentifier).chat." }
    private var taskMapKey: String { "\(bundleIdentifier).chat.backgroundTaskMap" }
    private var registeredRequestIds: Set<String> = []
    private var dependencies: IOSChatBackgroundDependencies?
    private var activeJobs: [String: IOSChatBackgroundRuntimeJob] = [:]
    private var activeRunStates: [String: IOSChatBackgroundRunState] = [:]
    private var activeBackgroundTasks: [String: BGContinuedProcessingTask] = [:]
    private lazy var db: AgentRuntimeDatabase = IosDatabaseFactory.shared.createDatabase()
    // W1 durable ledger (I-1): background-continued tool execution accounts
    // itself against the SAME runId the foreground run already started under
    // (see the `IOSAgentToolEngine(... ledger:ledgerRunId:)` call below).
    private lazy var toolLedger: IOSAgentRunLedgering = IOSAgentRunLedger(dao: db.agentRuntimeDao())

    private init() {}

    private var suspensionStore: IOSChatBackgroundSuspensionStore? {
        guard let directory = try? jobsDirectory() else { return nil }
        return IOSChatBackgroundSuspensionStore(directory: directory)
    }

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
        request.strategy = .fail

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

    func hasActiveJob(conversationId: KotlinUuid) -> Bool {
        !jobs(conversationId: conversationId).isEmpty
    }

    func activeRunId(conversationId: KotlinUuid) -> String? {
        jobs(conversationId: conversationId).values.max { lhs, rhs in
            if lhs.startedAt == rhs.startedAt {
                return lhs.runId < rhs.runId
            }
            return lhs.startedAt < rhs.startedAt
        }?.runId
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
            let didPersistTerminal = await job.conversationStore.saveBackgroundToolCompletion(
                baseMessages: job.displayMessages,
                completedMessages: cancelledMessages,
                to: job.conversationId
            )
            await self.recordRun(
                job.runId,
                startedAt: job.startedAt,
                status: didPersistTerminal ? "interrupted" : "failed",
                inputDigest: job.inputDigest,
                conversationId: job.conversationId
            )
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
            self.finish(runId: job.runId, requestId: requestId)
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

    private func runtimeJob(
        handoff: IOSChatBackgroundHandoff,
        conversationStore: IOSConversationStore,
        toolRuntime: ChatToolRuntime,
        liveActivityController: AgentLiveActivityController,
        saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> ChatMiniAppOutputApplication?)?
    ) -> IOSChatBackgroundRuntimeJob {
        IOSChatBackgroundRuntimeJob(
            runId: handoff.runId,
            startedAt: handoff.startedAt,
            inputDigest: handoff.inputDigest,
            conversationId: handoff.conversationId,
            providerSetting: handoff.providerSetting,
            params: handoff.params,
            uploadMessages: handoff.uploadMessages,
            displayMessages: handoff.displayMessages,
            mode: handoff.mode,
            generativeUiRequirement: handoff.generativeUiRequirement,
            generativeUiFallbackAttempted: handoff.generativeUiFallbackAttempted,
            conversationStore: conversationStore,
            toolRuntime: toolRuntime,
            liveActivityController: liveActivityController,
            saveMiniAppIfPresent: saveMiniAppIfPresent,
            messagesSnapshot: IOSChatBackgroundMessagesSnapshot(handoff.uploadMessages)
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
        if let suspended = suspensionStore?.load(requestId: backgroundTask.identifier) {
            suspensionStore?.remove(requestId: backgroundTask.identifier)
            await persistExpirationFailure(
                job: job,
                requestId: backgroundTask.identifier,
                rawMessage: "后台生成已停止，可以重试。",
                partialAssistantText: suspended.partialAssistantText
            )
            finish(runId: job.runId, requestId: backgroundTask.identifier)
            backgroundTask.setTaskCompleted(success: false)
            return
        }
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
                    await self.persistExpirationFailure(
                        job: job,
                        requestId: backgroundTask.identifier,
                        rawMessage: "后台生成已停止，可以重试。",
                        partialAssistantText: assistantTextSnapshot.text
                    )
                    self.finish(runId: job.runId, requestId: backgroundTask.identifier)
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
                runId: job.runId
            ),
            configuration: .init(maxSteps: 6, honorApprovalPause: false),
            ledger: toolLedger,
            ledgerRunId: job.runId
        )
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
        let operationTask = Task { () -> IOSAgentToolEngineResult in
            switch job.mode {
            case .continueModel:
                let initialResult = await engine.run(
                    providerSetting: requestProvider,
                    messages: job.uploadMessages,
                    params: requestParams,
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
                var retryBase = initialResult.messages
                if retryBase.last?.role == MessageRole.assistant {
                    retryBase.removeLast()
                }
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
                    params: retryParams
                ) else {
                    return initialResult
                }
                let retryResult = await engine.run(
                    providerSetting: requestProvider,
                    messages: retryUpload.values,
                    params: retryParams,
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
                            with: job.uploadMessages + Array(messages.dropFirst(retryUploadMessageCount))
                        )
                    }
                )
                return IOSAgentToolEngineResult(
                    messages: job.uploadMessages + Array(retryResult.messages.dropFirst(retryUploadMessageCount)),
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
        presentationEvents.continuation.finish()
        await presentationConsumer.value
        runState.clearOperationTask()
        guard runState.reserveTerminal() else { return }

        progress.completedUnitCount = 3
        backgroundTask.updateTitle("Amber 后台生成", subtitle: "正在保存结果")

        let reconciledMessages = job.mode == .continueModel
            ? Self.reconciledMessages(
                resultMessages: result.messages,
                uploadMessageCount: job.uploadMessages.count,
                displayMessages: job.displayMessages
            )
            : result.messages
        let generatedSuffix = job.mode == .continueModel
            ? Array(reconciledMessages.dropFirst(job.displayMessages.count))
            : []
        if job.mode == .continueModel, result.hitOutputLimit {
            await completeTruncatedAfterTerminalReservation(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState
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
        // sibling, `terminatePendingToolCalls`, already records "guard_stopped"
        // and publishes `.failed()`). Capture the notice text here so the
        // terminal-status block below can align background with foreground.
        var guardStoppedNotice: String?
        if job.mode == .continueModel, result.guardStopped {
            let notice = "模型连续以相同参数重复调用工具，已停止本轮以避免空耗，已保存当前结果。"
            finalMessages.append(Self.assistantMessage(notice))
            guardStoppedNotice = notice
        }
        let miniAppApplication = job.mode == .continueModel
            ? job.saveMiniAppIfPresent?(finalMessages, job.conversationId)
            : nil
        if let miniAppApplication {
            finalMessages = miniAppApplication.messages
        }
        let singleToolFailureReason = job.mode == .singleToolOnly
            ? ChatToolOutputFormatter.imageFailureReason(in: finalMessages)
            : nil
        let watchSummary = Self.backgroundSummary(from: finalMessages)

        let didSave: Bool
        switch job.mode {
        case .continueModel:
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
           let workspaceFailureMessages = miniAppApplication?.syncWorkspaceAfterConversationPersistence() {
            finalMessages = workspaceFailureMessages
            let baseline = job.conversationStore.writeBaseline(for: job.conversationId)
            _ = await job.conversationStore.save(
                messages: finalMessages,
                to: job.conversationId,
                ifUnchangedSince: baseline
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
                    summary: watchSummary
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
            guardStopped: guardStoppedNotice != nil
        )
        let succeeded = runStatus == "completed"
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: runStatus,
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        if succeeded {
            WatchTaskCoordinator.shared.publishCompleted(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                summary: watchSummary
            )
        } else {
            WatchTaskCoordinator.shared.publish(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                presentation: .failed(),
                summary: guardStoppedNotice.flatMap { WatchTaskText.clipped($0, maxLength: 200) }
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
        runState: IOSChatBackgroundRunState
    ) async {
        var finalMessages = Self.reconciledMessages(
            resultMessages: job.messagesSnapshot.messages,
            uploadMessageCount: job.uploadMessages.count,
            displayMessages: job.displayMessages
        )
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
            status: didSave ? "truncated" : "recovery_pending",
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
                    status: didSave ? "failed" : "recovery_pending",
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
            status: "failed",
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
            finish(runId: job.runId, requestId: backgroundTask.identifier)
            backgroundTask.setTaskCompleted(success: false)
        } else {
            removePayload(requestId: backgroundTask.identifier)
        }
    }

    /// 旧版本可能已经留下了自动恢复记录。当前系统 API 无法区分用户 Stop 与
    /// 系统到期，因此这些记录只落为可见的可重试终态，不再重新提交后台请求。
    func finalizeSuspendedRunsIfNeeded() {
        guard let store = suspensionStore else { return }
        let records = store.allRecords()
        guard !records.isEmpty else { return }
        IOSBackgroundLifecycleLog.record(
            "suspensionFinalizeSweep(pending=\(records.count))",
            detail: lifecycleSnapshotDetail
        )
        for record in records {
            store.remove(requestId: record.requestId)
            guard let job = job(for: record.requestId) else {
                finish(requestId: record.requestId)
                continue
            }
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: record.requestId)
            finish(runId: job.runId, requestId: record.requestId)
            Task { @MainActor in
                await self.persistExpirationFailure(
                    job: job,
                    requestId: record.requestId,
                    rawMessage: "后台生成已停止，可以重试。",
                    partialAssistantText: record.partialAssistantText
                )
                IOSBackgroundLifecycleLog.record(
                    "suspensionFinalized(run=\(record.runId.prefix(8)))",
                    detail: self.lifecycleSnapshotDetail
                )
            }
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
    ) async {
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
            status: didSave ? "failed" : "recovery_pending",
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        WatchTaskCoordinator.shared.publish(
            runId: job.runId,
            conversationId: job.conversationId.toHexDashString(),
            presentation: .failed()
        )
        await job.liveActivityController.end(runId: job.runId, presentation: .failed())
    }

    private func resolveExpiredInFlightSave(
        job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        didSave: Bool,
        singleToolFailureReason: String?,
        guardStoppedNotice: String?,
        summary: String?
    ) async {
        let runStatus = Self.backgroundTerminalStatus(
            didSave: didSave,
            singleToolFailureReason: singleToolFailureReason,
            guardStopped: guardStoppedNotice != nil
        )
        let succeeded = runStatus == "completed"
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
        if succeeded {
            WatchTaskCoordinator.shared.publishCompleted(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                summary: summary
            )
        } else {
            WatchTaskCoordinator.shared.publish(
                runId: job.runId,
                conversationId: job.conversationId.toHexDashString(),
                presentation: .failed(),
                summary: guardStoppedNotice.flatMap { WatchTaskText.clipped($0, maxLength: 200) }
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
        // 当前没有持久化 payload 的 retry owner；终态失败必须同步删除，避免孤儿文件。
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: "recovery_pending",
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
            finish(requestId: backgroundTask.identifier)
            backgroundTask.setTaskCompleted(success: false)
        }
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
            generativeUiRequired: handoff.generativeUiRequirement.required,
            generativeUiExpectSlides: handoff.generativeUiRequirement.expectSlides,
            generativeUiExpectFullHtmlDeck: handoff.generativeUiRequirement.expectFullHtmlDeck,
            generativeUiFallbackAttempted: handoff.generativeUiFallbackAttempted
        )
        let directory = try jobsDirectory()
        let url = payloadURL(for: requestId, in: directory)
        try Data(json.utf8).write(to: url, options: [.atomic])
    }

    private func persistGenerativeUiRetryCheckpoint(
        for job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        uploadMessages: [UIMessage],
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
            displayMessages: job.displayMessages,
            mode: job.mode,
            generativeUiRequirement: job.generativeUiRequirement,
            generativeUiFallbackAttempted: true
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
                generativeUiRequirement: IOSGenerativeUiRequirement(
                    required: payload.generativeUiRequired,
                    expectSlides: payload.generativeUiExpectSlides,
                    expectFullHtmlDeck: payload.generativeUiExpectFullHtmlDeck
                ),
                generativeUiFallbackAttempted: payload.generativeUiFallbackAttempted
            )
        } catch {
            NSLog("[AmberChatBG] Failed to load background payload \(requestId): \(error)")
            return nil
        }
    }

    private func removePayload(requestId: String) {
        // payload 与挂起记录是一对：payload 走了，挂起态就没有恢复目标，必须一起清。
        suspensionStore?.remove(requestId: requestId)
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
        status: String,
        inputDigest: String,
        conversationId: KotlinUuid,
        interruptedReason: String? = nil
    ) async {
        let dao = db.agentRuntimeDao()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let finishedAtValue: KotlinLong? = status == "running" ? nil : KotlinLong(value: now)
        // 中断原因由调用方给出；历史调用点只有「用户取消」一种，保留为默认值。
        let resolvedInterruptedReason: String? = status == "interrupted"
            ? (interruptedReason ?? "user_cancelled")
            : nil
        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: conversationId.toHexDashString(),
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: status,
            inputDigest: inputDigest,
            inputSnapshotRef: nil,
            inputSchemaVersion: 1,
            startedAt: startedAt,
            finishedAt: finishedAtValue,
            interruptedReason: resolvedInterruptedReason
        )

        do {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                dao.insertRun(run: run) { error in
                    if let error { cont.resume(throwing: error) }
                    else { cont.resume() }
                }
            }
        } catch {
            print("[AmberChatBG] Failed to insert agent_run: \(error)")
        }
    }

    private func markRunInterrupted(runId: String, reason: String) async {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            db.agentRuntimeDao().markInterrupted(
                runId: runId,
                reason: reason,
                now: now
            ) { _ in
                continuation.resume()
            }
        }
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
        guardStopped: Bool
    ) -> String {
        guard didSave else { return "recovery_pending" }
        if guardStopped { return "guard_stopped" }
        return singleToolFailureReason == nil ? "completed" : "failed"
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

        let runtimeHeaders = assistantHeaders + configuredModel.customHeaders
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
        guardStopped: Bool
    ) -> String {
        backgroundTerminalStatus(
            didSave: didSave,
            singleToolFailureReason: singleToolFailureReason,
            guardStopped: guardStopped
        )
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
            // 后台这一轮真正终结了，执行权才还回去。前台交接时不能还——
            // 那会让刚接手的后台任务立刻失去进程。
            if let ownedRunId = map.removeValue(forKey: requestId) {
                BackgroundGenerationKeepAlive.shared.end(ownedRunId)
            }
            if shouldRemovePayload {
                removePayload(requestId: requestId)
            }
        } else if let runId {
            BackgroundGenerationKeepAlive.shared.end(runId)
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
