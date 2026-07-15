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
        guard terminalOwner != .expiration,
              terminalOwner != .cancellation,
              !terminalFinalized else {
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

    private init() {}

    var restorableRunIds: Set<String> {
        Set(activeJobs.values.map(\.runId))
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
            return true
        } catch {
            activeJobs.removeValue(forKey: requestId)
            finish(runId: handoff.runId, requestId: requestId)
            NSLog("[AmberChatBG] BGContinuedProcessingTask submit failed: \(error)")
            return false
        }
    }

    func hasActiveJob(conversationId: KotlinUuid) -> Bool {
        !jobs(conversationId: conversationId).isEmpty
    }

    func cancelJobs(conversationId: KotlinUuid) {
        let matchingJobs = jobs(conversationId: conversationId)
        for (requestId, job) in matchingJobs {
            if let runState = activeRunStates[requestId] {
                guard runState.cancelAndReserveTerminal(),
                      runState.finalizeTerminal(as: .cancellation),
                      runState.claimSystemTaskCompletion() else {
                    continue
                }
            }
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: requestId)
            activeBackgroundTasks[requestId]?.setTaskCompleted(success: false)
            finish(runId: job.runId, requestId: requestId)
            Task { @MainActor in
                await self.recordRun(
                    job.runId,
                    startedAt: job.startedAt,
                    status: "interrupted",
                    inputDigest: job.inputDigest,
                    conversationId: job.conversationId
                )
                _ = job.liveActivityController.adoptExistingActivity(
                    runId: job.runId,
                    conversationId: job.conversationId.toHexDashString()
                )
                await job.liveActivityController.end(
                    runId: job.runId,
                    presentation: .cancelled()
                )
            }
        }
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
        let runState = IOSChatBackgroundRunState()
        activeRunStates[backgroundTask.identifier] = runState
        activeBackgroundTasks[backgroundTask.identifier] = backgroundTask
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
            let claim = runState.expireAndReserveTerminal()
            if claim == .rejected {
                guard runState.terminalWasFinalized(by: .completion) else { return }
                Task { @MainActor in
                    guard runState.claimSystemTaskCompletion() else { return }
                    self.finish(requestId: backgroundTask.identifier, removePayload: false)
                    backgroundTask.setTaskCompleted(success: false)
                }
                return
            }
            guard runState.finalizeTerminal(as: .expiration) else { return }
            Task { @MainActor in
                guard runState.claimSystemTaskCompletion() else { return }
                self.finish(requestId: backgroundTask.identifier, removePayload: false)
                backgroundTask.setTaskCompleted(success: false)
                switch claim {
                case .persistFailure:
                    await self.persistExpirationFailure(
                        job: job,
                        requestId: backgroundTask.identifier,
                        rawMessage: "后台生成被系统中断，可回到 App 后重试。",
                        partialAssistantText: assistantTextSnapshot.text
                    )
                case .terminateInFlightSave:
                    // 会话写入已经开始，无法原子取消；由保存结果决定最终呈现，避免双终态。
                    break
                case .rejected:
                    break
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

        await job.liveActivityController.update(
            runId: job.runId,
            presentation: job.mode == .singleToolOnly
                ? .runningTool(toolName: "generate_image")
                : .generatingResponse(modelName: requestParams.model.modelId),
            force: true
        )

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
            configuration: .init(maxSteps: 6, honorApprovalPause: false)
        )
        let operationTask = Task { () -> IOSAgentToolEngineResult in
            switch job.mode {
            case .continueModel:
                return await engine.run(
                    providerSetting: requestProvider,
                    messages: job.uploadMessages,
                    params: requestParams,
                    onAssistantTurnStarted: {
                        assistantTextSnapshot.replace(with: "")
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
        runState.clearOperationTask()
        guard runState.reserveTerminal() else { return }

        progress.completedUnitCount = 3
        backgroundTask.updateTitle("Amber 后台生成", subtitle: "正在保存结果")

        let generatedSuffix = job.mode == .continueModel
            ? Array(result.messages.dropFirst(job.uploadMessages.count))
            : []
        if job.mode == .continueModel, let rawFailure = providerFailureMessage(in: generatedSuffix) {
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
        if job.mode == .continueModel,
           let miniAppNotice = job.saveMiniAppIfPresent?(finalMessages, job.conversationId) {
            finalMessages.append(miniAppNotice)
        }
        let singleToolFailureReason = job.mode == .singleToolOnly
            ? ChatToolOutputFormatter.imageFailureReason(in: finalMessages)
            : nil

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
                    singleToolFailureReason: singleToolFailureReason
                )
            }
            return
        }
        guard didSave else {
            backgroundTask.updateTitle("Amber 后台生成", subtitle: "保存结果失败")
            await completeAsFailureWithoutClearingPayload(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState
            )
            return
        }
        let succeeded = singleToolFailureReason == nil
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: succeeded ? "completed" : "failed",
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
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
        let finalMessages = Self.failedMessages(
            displayMessages: job.displayMessages,
            preservedGeneratedSuffix: preservedGeneratedSuffix,
            partialAssistantText: partialAssistantText,
            rawMessage: rawMessage,
            modelId: job.params.model.modelId
        )
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
                    status: "failed",
                    inputDigest: job.inputDigest,
                    conversationId: job.conversationId
                )
                await job.liveActivityController.end(runId: job.runId, presentation: .failed())
            }
            return
        }
        guard didSave else {
            backgroundTask.updateTitle("Amber 后台生成", subtitle: "无法保存失败状态")
            await completeAsFailureWithoutClearingPayload(
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
        await job.liveActivityController.end(runId: job.runId, presentation: .failed())
        if runState.claimSystemTaskCompletion() {
            finish(runId: job.runId, requestId: backgroundTask.identifier)
            backgroundTask.setTaskCompleted(success: false)
        } else {
            removePayload(requestId: backgroundTask.identifier)
        }
    }

    private func persistExpirationFailure(
        job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        rawMessage: String,
        partialAssistantText: String?
    ) async {
        let finalMessages = Self.failedMessages(
            displayMessages: job.displayMessages,
            partialAssistantText: partialAssistantText,
            rawMessage: rawMessage,
            modelId: job.params.model.modelId
        )
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
            status: "failed",
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        await job.liveActivityController.end(runId: job.runId, presentation: .failed())
    }

    private func resolveExpiredInFlightSave(
        job: IOSChatBackgroundRuntimeJob,
        requestId: String,
        didSave: Bool,
        singleToolFailureReason: String?
    ) async {
        let succeeded = didSave && singleToolFailureReason == nil
        if didSave {
            removePayload(requestId: requestId)
        }
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: succeeded ? "completed" : "failed",
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        await job.liveActivityController.end(
            runId: job.runId,
            presentation: succeeded ? .completed() : .failed()
        )
    }

    private func completeAsFailureWithoutClearingPayload(
        job: IOSChatBackgroundRuntimeJob,
        backgroundTask: BGContinuedProcessingTask,
        runState: IOSChatBackgroundRunState
    ) async {
        // 保存失败先收口 run / Activity，再释放系统任务；payload 只保留失败现场，
        // 释放后不再具有冷启动 hydrate / Live Activity restore 资格。
        await recordRun(
            job.runId,
            startedAt: job.startedAt,
            status: "failed",
            inputDigest: job.inputDigest,
            conversationId: job.conversationId
        )
        await job.liveActivityController.end(runId: job.runId, presentation: .failed())
        if runState.claimSystemTaskCompletion() {
            finish(requestId: backgroundTask.identifier, removePayload: false)
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
            return IOSChatBackgroundHandoff(
                runId: payload.runId,
                startedAt: payload.startedAt,
                inputDigest: payload.inputDigest,
                conversationId: payload.conversationId,
                providerId: payload.providerId,
                providerSetting: providerSetting,
                params: payload.params,
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
        let fileName = requestId
            .map { character -> Character in
                character.isLetter || character.isNumber || character == "." || character == "-" ? character : "-"
            }
        return directory.appendingPathComponent(String(fileName)).appendingPathExtension("json")
    }

    private func recordRun(
        _ runId: String,
        startedAt: Int64,
        status: String,
        inputDigest: String,
        conversationId: KotlinUuid
    ) async {
        let dao = db.agentRuntimeDao()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let finishedAtValue: KotlinLong? = status == "running" ? nil : KotlinLong(value: now)
        let interruptedReason: String? = status == "interrupted" ? "user_cancelled" : nil
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
            interruptedReason: interruptedReason
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

    private func providerFailureMessage(in generatedSuffix: [UIMessage]) -> String? {
        let marker = "[engine] provider error:"
        guard let text = generatedSuffix.last?.toText(),
              text.hasPrefix(marker) else { return nil }
        return String(text.dropFirst(marker.count)).trimmingCharacters(in: .whitespacesAndNewlines)
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
        if let requestId {
            activeJobs.removeValue(forKey: requestId)
            activeRunStates.removeValue(forKey: requestId)
            activeBackgroundTasks.removeValue(forKey: requestId)
            map.removeValue(forKey: requestId)
            if shouldRemovePayload {
                removePayload(requestId: requestId)
            }
        } else if let runId {
            let matching = map.filter { $0.value == runId }.map(\.key)
            for requestId in matching {
                activeJobs.removeValue(forKey: requestId)
                activeRunStates.removeValue(forKey: requestId)
                activeBackgroundTasks.removeValue(forKey: requestId)
                map.removeValue(forKey: requestId)
                if shouldRemovePayload {
                    removePayload(requestId: requestId)
                }
            }
        }
        UserDefaults.standard.set(map, forKey: taskMapKey)
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
