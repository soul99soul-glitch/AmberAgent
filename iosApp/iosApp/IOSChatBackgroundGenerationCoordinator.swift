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
    let conversationStore: IOSConversationStore
    let toolRuntime: ChatToolRuntime
    let liveActivityController: AgentLiveActivityController
    let saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> UIMessage?)?
}

private struct IOSChatBackgroundDependencies {
    let conversationStore: IOSConversationStore
    let toolRuntime: ChatToolRuntime
    let sharedSettings: IOSSharedSettingsStore
    let liveActivityController: AgentLiveActivityController
    let saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> UIMessage?)?
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
    /// 已重投、但系统还没把任务启动起来的那些 requestId。
    /// 恢复次数记的是「实际被系统到期打断了几次」，不是「回前台扫了几遍」——
    /// 没有这道闸，用户切出去瞄一眼再回来两次就能把配额烧光。
    private var pendingResumeRequestIds: Set<String> = []
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
        saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> UIMessage?)? = nil
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
        saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> UIMessage?)? = nil
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
            let hadUnresolvedTool = job.toolRuntime.hasUnresolvedToolCall(in: job.displayMessages)
            let didPersistTerminal: Bool
            if hadUnresolvedTool {
                let cancelledMessages = job.toolRuntime.messagesByFailingPendingToolCalls(
                    in: job.displayMessages,
                    failureReason: "User cancelled.",
                    denied: true
                )
                didPersistTerminal = await job.conversationStore.saveBackgroundToolCompletion(
                    baseMessages: job.displayMessages,
                    completedMessages: cancelledMessages,
                    to: job.conversationId
                )
            } else {
                didPersistTerminal = true
            }
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

        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: requestId, using: nil) { task in
            guard let task = task as? BGContinuedProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
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
        saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> UIMessage?)?
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
            conversationStore: conversationStore,
            toolRuntime: toolRuntime,
            liveActivityController: liveActivityController,
            saveMiniAppIfPresent: saveMiniAppIfPresent
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
        activeBackgroundTasks[backgroundTask.identifier] = backgroundTask
        // 重投的任务已经真正跑起来了，闸放开：之后再到期就该重新计一次配额。
        pendingResumeRequestIds.remove(backgroundTask.identifier)
        let resumeAttempt = suspensionStore?.load(requestId: backgroundTask.identifier)?.resumeCount ?? 0
        IOSBackgroundLifecycleLog.record(
            "bgTaskStarted(run=\(job.runId.prefix(8)),resumeAttempt=\(resumeAttempt))",
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
            guard let self else { return }
            Task { @MainActor in
                let claim = runState.expireAndReserveTerminal()
                if claim == .rejected {
                    guard runState.terminalWasFinalized(by: .completion),
                          runState.claimSystemTaskCompletion() else { return }
                    self.finish(requestId: backgroundTask.identifier)
                    backgroundTask.setTaskCompleted(success: false)
                    return
                }
                guard runState.finalizeTerminal(as: .expiration) else { return }
                guard runState.claimSystemTaskCompletion() else { return }
                IOSBackgroundLifecycleLog.record(
                    "bgTaskExpired(claim=\(claim))",
                    detail: self.lifecycleSnapshotDetail
                )
                switch claim {
                case .persistFailure:
                    // 系统到期 ≠ 生成失败：这一轮还没开始写会话，保留 payload 与
                    // taskMap 转入可恢复的挂起态，回到前台自动重投一次把它跑完。
                    self.releaseRuntimeState(requestId: backgroundTask.identifier)
                    backgroundTask.setTaskCompleted(success: false)
                    await self.suspendForResume(
                        job: job,
                        requestId: backgroundTask.identifier,
                        partialAssistantText: assistantTextSnapshot.text
                    )
                case .terminateInFlightSave:
                    // 会话写入已经开始，无法原子取消；由保存结果决定最终呈现，避免双终态。
                    backgroundTask.setTaskCompleted(success: false)
                case .rejected:
                    self.finish(requestId: backgroundTask.identifier)
                    backgroundTask.setTaskCompleted(success: false)
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
                return await engine.run(
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
                    }
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
        presentationEvents.continuation.finish()
        await presentationConsumer.value
        runState.clearOperationTask()
        guard runState.reserveTerminal() else { return }

        progress.completedUnitCount = 3
        backgroundTask.updateTitle("Amber 后台生成", subtitle: "正在保存结果")

        let generatedSuffix = job.mode == .continueModel
            ? Array(result.messages.dropFirst(job.uploadMessages.count))
            : []
        if job.mode == .continueModel, result.hitOutputLimit {
            await completeTruncatedAfterTerminalReservation(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState,
                generatedSuffix: generatedSuffix
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

        var finalMessages = job.mode == .singleToolOnly
            ? result.messages
            : job.displayMessages + generatedSuffix
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
        if job.mode == .continueModel,
           let miniAppNotice = job.saveMiniAppIfPresent?(finalMessages, job.conversationId) {
            finalMessages.append(miniAppNotice)
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
        guard runState.finalizeTerminal() else {
            if runState.terminalWasFinalized(by: .expiration) {
                await resolveExpiredInFlightSave(
                    job: job,
                    requestId: backgroundTask.identifier,
                    didSave: didSave,
                    singleToolFailureReason: singleToolFailureReason,
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
        let succeeded = singleToolFailureReason == nil && guardStoppedNotice == nil
        let runStatus = guardStoppedNotice != nil ? "guard_stopped" : (succeeded ? "completed" : "failed")
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
        runState: IOSChatBackgroundRunState,
        generatedSuffix: [UIMessage]
    ) async {
        var finalMessages = job.displayMessages + generatedSuffix
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
            if runState.terminalWasFinalized(by: .expiration) {
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
        var finalMessages = Self.failedMessages(
            displayMessages: job.displayMessages,
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
            if runState.terminalWasFinalized(by: .expiration) {
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

    /// 只释放本进程的运行时句柄，保留 payload / taskMap / activeJobs，
    /// 让挂起的这一轮仍然算「本 App 拥有的可恢复任务」。
    private func releaseRuntimeState(requestId: String) {
        activeRunStates.removeValue(forKey: requestId)
        activeBackgroundTasks.removeValue(forKey: requestId)
    }

    /// 系统到期后转入挂起态：留住 payload 与已流出的正文，把呈现改成「重连中」
    /// 而不是失败，等回到前台再重投一次。
    private func suspendForResume(
        job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        partialAssistantText: String
    ) async {
        let existing = suspensionStore?.load(requestId: requestId)
        suspensionStore?.save(
            IOSChatBackgroundSuspensionRecord(
                requestId: requestId,
                runId: job.runId,
                partialAssistantText: partialAssistantText,
                suspendedAt: Int64(Date().timeIntervalSince1970 * 1000),
                resumeCount: existing?.resumeCount ?? 0
            )
        )
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: "interrupted",
            inputDigest: job.inputDigest,
            conversationId: job.conversationId,
            interruptedReason: "background_expired"
        )
        let presentation = AgentActivityPresentation.reconnecting(
            kind: job.mode == .singleToolOnly ? .imageGeneration : .response
        )
        WatchTaskCoordinator.shared.publish(
            runId: job.runId,
            conversationId: job.conversationId.toHexDashString(),
            presentation: presentation
        )
        _ = job.liveActivityController.adoptExistingActivity(
            runId: job.runId,
            conversationId: job.conversationId.toHexDashString()
        )
        await job.liveActivityController.update(
            runId: job.runId,
            presentation: presentation,
            force: true
        )
        IOSBackgroundLifecycleLog.record(
            "suspendedForResume(run=\(job.runId.prefix(8)),partialChars=\(partialAssistantText.count))",
            detail: lifecycleSnapshotDetail
        )
    }

    /// 回到前台时调用：把被系统中断的后台生成重新投递一次。
    /// 幂等——已经在飞的、payload 丢失的、超出恢复次数的分别跳过或就地了结。
    func resumeSuspendedRunsIfNeeded() {
        guard let store = suspensionStore else { return }
        let records = store.allRecords()
        guard !records.isEmpty else { return }
        IOSBackgroundLifecycleLog.record(
            "resumeSweep(pending=\(records.count))",
            detail: lifecycleSnapshotDetail
        )
        for record in records {
            resumeSuspendedRun(record, store: store)
        }
    }

    private func resumeSuspendedRun(
        _ record: IOSChatBackgroundSuspensionRecord,
        store: IOSChatBackgroundSuspensionStore
    ) {
        // 已经有在飞的系统任务：这条记录属于上一轮，交给它自己走完终态。
        guard activeBackgroundTasks[record.requestId] == nil else { return }
        // 上一次重投还排在系统队列里没启动，别重复投、更别再扣一次配额。
        guard !pendingResumeRequestIds.contains(record.requestId) else { return }
        guard let job = job(for: record.requestId) else {
            // payload 已不可用，恢复无从谈起，清掉记录避免留下孤儿文件。
            store.remove(requestId: record.requestId)
            finish(requestId: record.requestId)
            return
        }
        guard record.canResume, register(requestId: record.requestId) else {
            Task { @MainActor in await self.abandonSuspendedRun(record, job: job) }
            return
        }

        let attempted = record.markingResumeAttempt()
        store.save(attempted)

        let request = BGContinuedProcessingTaskRequest(
            identifier: record.requestId,
            title: "Amber 后台生成",
            subtitle: "继续未完成的生成"
        )
        request.strategy = .fail
        do {
            try BGTaskScheduler.shared.submit(request)
            pendingResumeRequestIds.insert(record.requestId)
            IOSBackgroundLifecycleLog.record(
                "resumeSubmitted(run=\(record.runId.prefix(8))"
                    + ",attempt=\(attempted.resumeCount)/\(IOSChatBackgroundSuspensionRecord.maxResumeAttempts))",
                detail: lifecycleSnapshotDetail
            )
        } catch {
            NSLog("[AmberChatBG] Resume submit failed for \(record.requestId): \(error)")
            Task { @MainActor in await self.abandonSuspendedRun(attempted, job: job) }
        }
    }

    /// 不再自动恢复：把已经流出来的正文连同一条可重试提示落盘，交回给用户。
    private func abandonSuspendedRun(
        _ record: IOSChatBackgroundSuspensionRecord,
        job: IOSChatBackgroundRuntimeJob
    ) async {
        pendingResumeRequestIds.remove(record.requestId)
        suspensionStore?.remove(requestId: record.requestId)
        await persistExpirationFailure(
            job: job,
            requestId: record.requestId,
            rawMessage: "后台生成被系统中断，可回到 App 后重试。",
            partialAssistantText: record.partialAssistantText
        )
        finish(requestId: record.requestId)
        IOSBackgroundLifecycleLog.record(
            "resumeAbandoned(run=\(record.runId.prefix(8)),attempts=\(record.resumeCount))",
            detail: lifecycleSnapshotDetail
        )
    }

    private func persistExpirationFailure(
        job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        rawMessage: String,
        partialAssistantText: String?
    ) async {
        var finalMessages = Self.failedMessages(
            displayMessages: job.displayMessages,
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
        summary: String?
    ) async {
        let succeeded = didSave && singleToolFailureReason == nil
        if didSave {
            removePayload(requestId: requestId)
        }
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: didSave ? (succeeded ? "completed" : "failed") : "recovery_pending",
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
                presentation: .failed()
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
            mode: handoff.mode.rawValue
        )
        let directory = try jobsDirectory()
        let url = payloadURL(for: requestId, in: directory)
        try Data(json.utf8).write(to: url, options: [.atomic])
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
                mode: IOSChatBackgroundHandoffMode(rawValue: payload.mode) ?? .continueModel
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
            pendingResumeRequestIds.remove(requestId)
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
