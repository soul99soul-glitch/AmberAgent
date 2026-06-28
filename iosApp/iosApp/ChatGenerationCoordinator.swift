import CryptoKit
import Foundation
@preconcurrency import Shared

func chatInputDigest(for text: String) -> String {
    let hash = SHA256.hash(data: Data(text.utf8))
    return hash.map { String(format: "%02x", $0) }.joined()
}

func chatNowLocalDateTime() -> Kotlinx_datetimeLocalDateTime {
    let now = Date()
    let cal = Calendar.current
    return Kotlinx_datetimeLocalDateTime(
        year: Int32(cal.component(.year, from: now)),
        month: Int32(cal.component(.month, from: now)),
        day: Int32(cal.component(.day, from: now)),
        hour: Int32(cal.component(.hour, from: now)),
        minute: Int32(cal.component(.minute, from: now)),
        second: Int32(cal.component(.second, from: now)),
        nanosecond: Int32(cal.component(.nanosecond, from: now))
    )
}

private final class StreamJobBox {
    var job: Kotlinx_coroutines_coreJob?

    deinit {
        job?.cancel(cause: nil)
    }
}

struct ChatPendingToolApproval {
    let toolCall: UIMessagePart.Tool
    let providerSetting: ProviderSetting
    let params: TextGenerationParams
    let runId: String
    let startedAt: Int64
    let inputDigest: String
    let conversationId: KotlinUuid?
    let baseMessages: [UIMessage]
}

struct ChatGenerationDependencies {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    let localToolExecutor: IOSLocalToolExecutor?
    let searchTransport: any IOSSearchHTTPTransport
    let liveActivityController: AgentLiveActivityController
    let autoGenerateResponses: Bool
    let mcpManager: IOSMcpManager
}

struct ChatGenerationBindings {
    let getMessages: () -> [UIMessage]
    let setMessages: ([UIMessage]) -> Void
    let bumpMessageRevision: () -> Void
    let setIsLoading: (Bool) -> Void
    let setPendingMemoryApproval: (MemoryToolApprovalRequest?) -> Void
    let setPendingSearchApproval: (SearchToolApprovalRequest?) -> Void
    let setPendingWebMountApproval: (WebMountToolApprovalRequest?) -> Void
    let setPendingWorkspaceApproval: (WorkspaceToolApprovalRequest?) -> Void
    let setPendingIshHandoffApproval: (IshHandoffToolApprovalRequest?) -> Void
    let setPendingMcpApproval: (McpToolApprovalRequest?) -> Void
    let setContextCompactState: (ChatContextCompactState) -> Void
    let persistMessages: (KotlinUuid?) -> Void
    let recordRun: (String, Int64, String, String) async -> Void
    let startLiveActivity: (String, AgentActivityPresentation) -> Void
    let saveMiniAppIfPresent: ([UIMessage], KotlinUuid?) -> UIMessage?
    let messagesByInjectingRuntimeContext: ([UIMessage]) -> [UIMessage]
    let userFacingGenerationError: (String, String?) -> String
}

@MainActor
final class ChatGenerationCoordinator {
    private let dependencies: ChatGenerationDependencies
    private let bindings: ChatGenerationBindings
    private lazy var provider = OpenAIKmpProvider()
    private lazy var claudeProvider = ClaudeKmpProvider()
    private let streamJobBox = StreamJobBox()
    private lazy var toolRuntime = ChatToolRuntime(
        settingsStore: dependencies.settingsStore,
        sharedSettings: dependencies.sharedSettings,
        localToolExecutor: dependencies.localToolExecutor,
        searchTransport: dependencies.searchTransport,
        mcpManager: dependencies.mcpManager
    )

    private var streamJob: Kotlinx_coroutines_coreJob? {
        get { streamJobBox.job }
        set { streamJobBox.job = newValue }
    }

    private var currentRunId: String?
    private var currentStartedAt: Int64?
    private var currentInputDigest: String?
    private var currentConversationIdForRun: KotlinUuid?
    private var currentToolResumeCount = 0
    private let maxToolResumeCount = 4
    private var pendingStreamSnapshot: [UIMessage]?
    private var streamSnapshotFlushTask: Task<Void, Never>?
    private let streamSnapshotFlushDelayNanos: UInt64 = 33_000_000
    private var pendingMemoryToolApproval: ChatPendingToolApproval?
    private var pendingSearchToolApproval: ChatPendingToolApproval?
    private var pendingWebMountToolApproval: ChatPendingToolApproval?
    private var pendingWorkspaceToolApproval: ChatPendingToolApproval?
    private var pendingIshHandoffToolApproval: ChatPendingToolApproval?
    private var pendingMcpToolApproval: ChatPendingToolApproval?
    private var backgroundHandoff: IOSChatBackgroundHandoff?
    private weak var pendingBackgroundConversationStore: IOSConversationStore?

    var isRunning: Bool {
        currentRunId != nil
    }

    init(
        dependencies: ChatGenerationDependencies,
        bindings: ChatGenerationBindings
    ) {
        self.dependencies = dependencies
        self.bindings = bindings
    }

    func start(
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        inputDigest: String,
        conversationId: KotlinUuid?,
        uploadMessages: [UIMessage]
    ) {
        if isRunning {
            cancel()
        }
        bindings.setIsLoading(true)
        bindings.setContextCompactState(.idle)

        let runId = UUID().uuidString
        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)
        currentRunId = runId
        currentStartedAt = startedAt
        currentInputDigest = inputDigest
        currentConversationIdForRun = conversationId
        currentToolResumeCount = 0
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        bindings.startLiveActivity(
            runId,
            .generatingResponse(modelName: params.model.modelId)
        )

        Task { @MainActor [weak self] in
            guard let self else { return }
            // Persist a "running" run record up-front so an interrupted mid-stream
            // run is detectable (status=running) by IOSRunRecovery's startup sweep,
            // which marks it interrupted. The terminal recordRun (completion / cancel
            // / error) REPLACEs this row with the final status + finishedAt
            // (insertRun is OnConflictStrategy.REPLACE).
            await self.bindings.recordRun(runId, startedAt, "running", inputDigest)
            if self.dependencies.sharedSettings.isCapabilityGateEnabled(.mcp) {
                await self.dependencies.mcpManager.syncAll()
            }
            guard self.currentRunId == runId else { return }
            // Codex OAuth providers carry no apiKey: resolve a valid OAuth access
            // token (refreshing if needed) and swap in a request-ready provider
            // (bearer + Responses API + codex backend). Non-codex providers pass
            // through unchanged. A resolution failure (not signed in / refresh
            // failed) surfaces as a normal generation error.
            let effectiveProvider: ProviderSetting
            do {
                effectiveProvider = try await IOSCodexProviderResolver.resolved(providerSetting)
            } catch {
                await self.presentStreamError(
                    rawMessage: (error as NSError).localizedDescription,
                    modelId: params.model.modelId,
                    runId: runId,
                    startedAt: startedAt,
                    inputDigest: inputDigest,
                    conversationId: conversationId
                )
                return
            }
            guard self.currentRunId == runId else { return }
            if let openAI = providerSetting as? ProviderSetting.OpenAI,
               openAI.authMode != OpenAIAuthMode.codexOauth,
               IOSCodexProviderResolver.isCodexProvider(providerSetting) {
                _ = self.dependencies.sharedSettings.setOpenAIAuthMode(
                    providerId: openAI.id.description(),
                    authMode: OpenAIAuthMode.codexOauth
                )
                self.dependencies.sharedSettings.syncLegacySettingsStoreForCurrentChat(self.dependencies.settingsStore)
            }
            // Codex needs `OpenAI-Beta`/originator/account-id headers on the
            // /responses request (else the backend 404s) — inject them for codex.
            let effectiveParams = IOSCodexProviderResolver.augmentParamsForCodex(params, provider: providerSetting)
            await self.prepareAndStartStreaming(
                providerSetting: effectiveProvider,
                params: effectiveParams,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                uploadMessages: uploadMessages
            )
        }
    }

    func runImageTool(input: String, conversationId: KotlinUuid?) {
        if isRunning {
            cancel()
        }

        let runId = UUID().uuidString
        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)
        let inputDigest = chatInputDigest(for: input)
        currentRunId = runId
        currentStartedAt = startedAt
        currentInputDigest = inputDigest
        currentConversationIdForRun = conversationId
        currentToolResumeCount = 0
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        bindings.setIsLoading(true)
        bindings.startLiveActivity(runId, .runningTool(toolName: "generate_image"))

        let toolCall = toolRuntime.userInitiatedImageToolCall(input: input)
        var snapshot = bindings.getMessages()
        snapshot.append(UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [toolCall],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        ))
        bindings.setMessages(snapshot)
        bindings.bumpMessageRevision()
        bindings.persistMessages(conversationId)

        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.bindings.recordRun(runId, startedAt, "running", inputDigest)
            let resumed = await self.toolRuntime.messagesByExecutingImageToolCall(
                toolCall,
                in: snapshot
            )
            guard self.currentRunId == runId else { return }
            self.bindings.setMessages(resumed)
            self.bindings.bumpMessageRevision()
            await self.bindings.recordRun(runId, startedAt, "completed", inputDigest)
            await self.dependencies.liveActivityController.end(
                runId: runId,
                presentation: .completed(toolTitle: "图片生成")
            )
            self.bindings.persistMessages(conversationId)
            self.finishStreaming()
        }
    }

    func cancel() {
        let runId = currentRunId
        let startedAt = currentStartedAt
        let digest = currentInputDigest
        let conversationId = currentConversationIdForRun

        streamJob?.cancel(cause: nil)
        streamJob = nil
        cancelPendingStreamSnapshotPublish()
        currentRunId = nil
        currentStartedAt = nil
        currentInputDigest = nil
        currentConversationIdForRun = nil
        currentToolResumeCount = 0
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        clearPendingApprovals()
        bindings.setIsLoading(false)
        bindings.setContextCompactState(.idle)

        guard let runId, let startedAt, let digest else { return }
        Task { @MainActor [dependencies, bindings] in
            await dependencies.liveActivityController.end(
                runId: runId,
                presentation: .cancelled()
            )
            await bindings.recordRun(runId, startedAt, "interrupted", digest)
            bindings.persistMessages(conversationId)
        }
    }

    @discardableResult
    func handoffCurrentGenerationToBackground(conversationStore: IOSConversationStore?) -> Bool {
        guard let handoff = backgroundHandoff,
              let conversationStore else {
            if isRunning {
                pendingBackgroundConversationStore = conversationStore
            }
            return false
        }
        let didStart = IOSChatBackgroundGenerationCoordinator.shared.start(
            handoff: handoff,
            conversationStore: conversationStore,
            toolRuntime: toolRuntime,
            liveActivityController: dependencies.liveActivityController,
            saveMiniAppIfPresent: { [bindings] messages, conversationId in
                bindings.saveMiniAppIfPresent(messages, conversationId)
            }
        )
        guard didStart else { return false }

        streamJob?.cancel(cause: nil)
        streamJob = nil
        cancelPendingStreamSnapshotPublish()
        bindings.setMessages(handoff.displayMessages)
        bindings.bumpMessageRevision()
        bindings.persistMessages(handoff.conversationId)
        currentRunId = nil
        currentStartedAt = nil
        currentInputDigest = nil
        currentConversationIdForRun = nil
        currentToolResumeCount = 0
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        clearPendingApprovals()
        bindings.setContextCompactState(.idle)
        bindings.setIsLoading(false)
        return true
    }

    func approvePendingMemoryTool() {
        finishPendingMemoryToolApproval(writePolicy: .allow)
    }

    func denyPendingMemoryTool() {
        finishPendingMemoryToolApproval(writePolicy: .deniedByUser("User denied memory write."))
    }

    func approvePendingSearchTool() async {
        await finishPendingSearchToolApproval(allow: true)
    }

    func denyPendingSearchTool() async {
        await finishPendingSearchToolApproval(allow: false)
    }

    func approvePendingWebMountTool() async {
        await finishPendingWebMountToolApproval(allow: true)
    }

    func denyPendingWebMountTool() async {
        await finishPendingWebMountToolApproval(allow: false)
    }

    func approvePendingWorkspaceTool() async {
        await finishPendingWorkspaceToolApproval(allow: true)
    }

    func denyPendingWorkspaceTool() async {
        await finishPendingWorkspaceToolApproval(allow: false)
    }

    func approvePendingIshHandoffTool() async {
        await finishPendingIshHandoffToolApproval(allow: true)
    }

    func denyPendingIshHandoffTool() async {
        await finishPendingIshHandoffToolApproval(allow: false)
    }

    func approvePendingMcpTool() async {
        await finishPendingMcpToolApproval(allow: true)
    }

    func denyPendingMcpTool() async {
        await finishPendingMcpToolApproval(allow: false)
    }

    private func prepareAndStartStreaming(
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        uploadMessages: [UIMessage]
    ) async {
        guard currentRunId == runId else { return }
        let effectiveProvider: ProviderSetting
        do {
            effectiveProvider = try await IOSCodexProviderResolver.resolved(providerSetting)
        } catch {
            await presentStreamError(
                rawMessage: (error as NSError).localizedDescription,
                modelId: params.model.modelId,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }
        let effectiveParams = IOSCodexProviderResolver.augmentParamsForCodex(params, provider: effectiveProvider)

        let runtimeBaseline = bindings.messagesByInjectingRuntimeContext(uploadMessages)
        let runtimeOverheadTokens = max(
            IOSContextCompactionCoordinator.estimatedTokensForRequest(runtimeBaseline) -
                IOSContextCompactionCoordinator.estimatedTokensForRequest(uploadMessages),
            0
        )

        let preparedUploadMessages: [UIMessage]
        do {
            preparedUploadMessages = try await IOSContextCompactionCoordinator.shared.prepareMessagesForRequest(
                uploadMessages: uploadMessages,
                conversationId: conversationId,
                settings: dependencies.sharedSettings.snapshot,
                params: effectiveParams,
                fallbackProvider: effectiveProvider,
                promptOverheadTokens: runtimeOverheadTokens,
                onEvent: { [weak self] event in
                    guard let self, self.currentRunId == runId else { return }
                    self.applyCompactEvent(event)
                }
            )
        } catch {
            if currentRunId == runId {
                applyCompactEvent(.failed(message: (error as NSError).localizedDescription))
            }
            await presentStreamError(
                rawMessage: "上下文压缩失败：\((error as NSError).localizedDescription)",
                modelId: effectiveParams.model.modelId,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }
        guard currentRunId == runId else { return }
        let runtimePreparedMessages = bindings.messagesByInjectingRuntimeContext(preparedUploadMessages)
        let finalUploadMessages: [UIMessage]
        do {
            finalUploadMessages = try IOSContextCompactionCoordinator.shared.finalizedMessagesForRequest(
                runtimePreparedMessages,
                settings: dependencies.sharedSettings.snapshot,
                params: effectiveParams
            )
        } catch {
            if currentRunId == runId {
                applyCompactEvent(.failed(message: (error as NSError).localizedDescription))
            }
            await presentStreamError(
                rawMessage: "上下文压缩失败：\((error as NSError).localizedDescription)",
                modelId: effectiveParams.model.modelId,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }
        startStreaming(
            providerSetting: effectiveProvider,
            params: effectiveParams,
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            uploadMessages: finalUploadMessages,
            backgroundProviderSetting: providerSetting
        )
    }

    private func applyCompactEvent(_ event: IOSContextCompactionEvent) {
        switch event {
        case .planning:
            bindings.setContextCompactState(ChatContextCompactState(
                status: .planning,
                summary: "",
                updatedAt: Date()
            ))
        case .compacting:
            bindings.setContextCompactState(ChatContextCompactState(
                status: .compacting,
                summary: "",
                updatedAt: Date()
            ))
        case .completed(let summary):
            bindings.setContextCompactState(ChatContextCompactState(
                status: .completed,
                summary: summary,
                updatedAt: Date()
            ))
        case .failed(let message):
            bindings.setContextCompactState(ChatContextCompactState(
                status: .failed,
                summary: message,
                updatedAt: Date()
            ))
        case .idle:
            bindings.setContextCompactState(.idle)
        }
    }

    private func startStreaming(
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        uploadMessages: [UIMessage],
        backgroundProviderSetting: ProviderSetting? = nil
    ) {
        let displayMessages = bindings.getMessages()
        if let conversationId {
            backgroundHandoff = IOSChatBackgroundHandoff(
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                providerSetting: backgroundProviderSetting ?? providerSetting,
                params: params,
                uploadMessages: uploadMessages,
                displayMessages: displayMessages
            )
        } else {
            backgroundHandoff = nil
        }
        if let pendingStore = pendingBackgroundConversationStore {
            pendingBackgroundConversationStore = nil
            if handoffCurrentGenerationToBackground(conversationStore: pendingStore) {
                return
            }
        }
        let accumulator = MessageStreamAccumulator(
            initialMessages: displayMessages,
            model: params.model
        )
        var detectedToolCallIds = Set<String>()

        streamJob = dispatchStream(
            providerSetting: providerSetting,
            messages: uploadMessages,
            params: params,
            onChunk: { chunk in
                Task { @MainActor [weak self] in
                    guard let self, self.currentRunId == runId else { return }
                    accumulator.append(chunk: chunk)
                    let toolCalls = Self.toolCalls(in: chunk)
                        .filter { toolCall in
                            let key = chatToolCallKey(toolCall)
                            guard !detectedToolCallIds.contains(key) else { return false }
                            detectedToolCallIds.insert(key)
                            return true
                        }
                    let snapshot = accumulator.snapshot()
                    if !toolCalls.isEmpty {
                        self.handleDetectedToolCalls(toolCalls, runId: runId)
                    }
                    self.scheduleStreamSnapshotPublish(snapshot, runId: runId)
                }
            },
            onComplete: {
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    guard self.currentRunId == runId else { return }
                    self.cancelPendingStreamSnapshotPublish()
                    let snapshot = accumulator.snapshot()
                    await self.handleCompletedStream(
                        snapshot: snapshot,
                        providerSetting: providerSetting,
                        params: params,
                        runId: runId,
                        startedAt: startedAt,
                        inputDigest: inputDigest,
                        conversationId: conversationId
                    )
                }
            },
            onError: { error in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    guard self.currentRunId == runId else { return }
                    self.cancelPendingStreamSnapshotPublish()
                    let snapshot = accumulator.snapshot()
                    self.bindings.setMessages(snapshot)
                    self.bindings.bumpMessageRevision()
                    await self.presentStreamError(
                        rawMessage: error.message ?? String(describing: error),
                        modelId: params.model.modelId,
                        runId: runId,
                        startedAt: startedAt,
                        inputDigest: inputDigest,
                        conversationId: conversationId
                    )
                }
            }
        )
    }

    private func scheduleStreamSnapshotPublish(_ snapshot: [UIMessage], runId: String) {
        pendingStreamSnapshot = snapshot
        guard streamSnapshotFlushTask == nil else { return }
        streamSnapshotFlushTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: self?.streamSnapshotFlushDelayNanos ?? 16_000_000)
            } catch {
                return
            }
            self?.flushPendingStreamSnapshot(runId: runId)
        }
    }

    private func flushPendingStreamSnapshot(runId: String) {
        streamSnapshotFlushTask = nil
        guard currentRunId == runId else {
            pendingStreamSnapshot = nil
            return
        }
        guard let snapshot = pendingStreamSnapshot else { return }
        pendingStreamSnapshot = nil
        bindings.setMessages(snapshot)
        bindings.bumpMessageRevision()
    }

    private func cancelPendingStreamSnapshotPublish() {
        streamSnapshotFlushTask?.cancel()
        streamSnapshotFlushTask = nil
        pendingStreamSnapshot = nil
    }

    /// Surfaces a generation failure as an assistant error bubble and finalizes
    /// the run (records "failed", ends the live activity, persists). Shared by the
    /// stream's onError and the codex token-resolution failure path.
    @MainActor
    private func presentStreamError(
        rawMessage: String,
        modelId: String,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?
    ) async {
        guard currentRunId == runId else { return }
        let userFacingMessage = bindings.userFacingGenerationError(rawMessage, modelId)
        let errMsg = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: userFacingMessage, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        var updated = bindings.getMessages()
        updated.append(errMsg)
        bindings.setMessages(updated)
        bindings.bumpMessageRevision()
        await bindings.recordRun(runId, startedAt, "failed", inputDigest)
        await dependencies.liveActivityController.end(runId: runId, presentation: .failed())
        bindings.persistMessages(conversationId)
        finishStreaming()
    }

    private func handleCompletedStream(
        snapshot: [UIMessage],
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?
    ) async {
        guard currentRunId == runId else { return }
        bindings.setMessages(snapshot)
        bindings.bumpMessageRevision()

        if let pendingToolCall = toolRuntime.nextPendingToolCall(in: snapshot),
           currentToolResumeCount < maxToolResumeCount {
            currentToolResumeCount += 1
            streamJob = nil
            await executeToolCall(
                pendingToolCall,
                providerSetting: providerSetting,
                params: params,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                baseMessages: snapshot
            )
            return
        }

        var finalSnapshot = snapshot
        if let miniAppNotice = bindings.saveMiniAppIfPresent(snapshot, conversationId) {
            finalSnapshot.append(miniAppNotice)
            bindings.setMessages(finalSnapshot)
            bindings.bumpMessageRevision()
        }

        await bindings.recordRun(runId, startedAt, "completed", inputDigest)
        await dependencies.liveActivityController.end(
            runId: runId,
            presentation: .completed()
        )
        bindings.persistMessages(conversationId)
        finishStreaming()
    }

    private func executeToolCall(
        _ pendingToolCall: ChatPendingToolCall,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        baseMessages: [UIMessage]
    ) async {
        let pending = ChatPendingToolApproval(
            toolCall: pendingToolCall.toolCall,
            providerSetting: providerSetting,
            params: params,
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            baseMessages: baseMessages
        )
        await dependencies.liveActivityController.update(
            runId: runId,
            presentation: .runningTool(toolName: pendingToolCall.toolCall.toolName),
            force: true
        )
        let result = await toolRuntime.execute(pendingToolCall, context: pending)
        guard currentRunId == runId else { return }

        switch result {
        case .completed(let resumedMessages):
            bindings.setMessages(resumedMessages)
            bindings.bumpMessageRevision()
            await dependencies.liveActivityController.update(
                runId: runId,
                presentation: .generatingResponse(modelName: params.model.modelId),
                force: true
            )
            await prepareAndStartStreaming(
                providerSetting: providerSetting,
                params: params,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                uploadMessages: resumedMessages
            )
        case .waitingForApproval(let prompt):
            pauseForApproval(prompt, pending: pending)
        }
    }

    private func pauseForApproval(
        _ prompt: ChatToolApprovalPrompt,
        pending: ChatPendingToolApproval
    ) {
        guard currentRunId == pending.runId else { return }
        switch prompt {
        case .memory(let request):
            pendingMemoryToolApproval = pending
            bindings.setPendingMemoryApproval(request)
        case .search(let request):
            pendingSearchToolApproval = pending
            bindings.setPendingSearchApproval(request)
        case .webMount(let request):
            pendingWebMountToolApproval = pending
            bindings.setPendingWebMountApproval(request)
        case .workspace(let request):
            pendingWorkspaceToolApproval = pending
            bindings.setPendingWorkspaceApproval(request)
        case .ish(let request):
            pendingIshHandoffToolApproval = pending
            bindings.setPendingIshHandoffApproval(request)
        case .mcp(let request):
            pendingMcpToolApproval = pending
            bindings.setPendingMcpApproval(request)
        }
        bindings.setMessages(pending.baseMessages)
        bindings.bumpMessageRevision()
        bindings.setIsLoading(false)
        Task { @MainActor [dependencies] in
            await dependencies.liveActivityController.update(
                runId: pending.runId,
                presentation: .waitingForUser(toolTitle: prompt.toolTitle),
                force: true
            )
        }
    }

    private func finishPendingMemoryToolApproval(writePolicy: IOSMemoryToolWritePolicy) {
        guard let pending = pendingMemoryToolApproval else { return }
        clearPendingMemoryApproval()
        guard currentRunId == pending.runId else { return }
        let resumedMessages = toolRuntime.finishMemoryApproval(
            pending: pending,
            writePolicy: writePolicy
        )
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision()
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingSearchToolApproval(allow: Bool) async {
        guard let pending = pendingSearchToolApproval else { return }
        clearPendingSearchApproval()
        guard currentRunId == pending.runId else { return }
        bindings.setIsLoading(true)
        let resumedMessages = await toolRuntime.finishSearchApproval(
            pending: pending,
            allow: allow
        )
        guard currentRunId == pending.runId else { return }
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision()
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingWebMountToolApproval(allow: Bool) async {
        guard let pending = pendingWebMountToolApproval else { return }
        clearPendingWebMountApproval()
        guard currentRunId == pending.runId else { return }
        bindings.setIsLoading(true)
        let resumedMessages = await toolRuntime.finishWebMountApproval(
            pending: pending,
            allow: allow
        )
        guard currentRunId == pending.runId else { return }
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision()
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingWorkspaceToolApproval(allow: Bool) async {
        guard let pending = pendingWorkspaceToolApproval else { return }
        clearPendingWorkspaceApproval()
        guard currentRunId == pending.runId else { return }
        bindings.setIsLoading(true)
        let resumedMessages = await toolRuntime.finishWorkspaceApproval(
            pending: pending,
            allow: allow
        )
        guard currentRunId == pending.runId else { return }
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision()
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingIshHandoffToolApproval(allow: Bool) async {
        guard let pending = pendingIshHandoffToolApproval else { return }
        clearPendingIshHandoffApproval()
        guard currentRunId == pending.runId else { return }
        bindings.setIsLoading(true)
        let resumedMessages = await toolRuntime.finishIshHandoffApproval(
            pending: pending,
            allow: allow
        )
        guard currentRunId == pending.runId else { return }
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision()
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingMcpToolApproval(allow: Bool) async {
        guard let pending = pendingMcpToolApproval else { return }
        clearPendingMcpApproval()
        guard currentRunId == pending.runId else { return }
        bindings.setIsLoading(true)
        let resumedMessages = await toolRuntime.finishMcpApproval(
            pending: pending,
            allow: allow
        )
        guard currentRunId == pending.runId else { return }
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision()
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func resumeAfterApproval(
        pending: ChatPendingToolApproval,
        resumedMessages: [UIMessage]
    ) {
        guard currentRunId == pending.runId else { return }
        guard dependencies.autoGenerateResponses else {
            bindings.persistMessages(pending.conversationId)
            finishStreaming()
            return
        }

        bindings.setIsLoading(true)
        bindings.startLiveActivity(
            pending.runId,
            .generatingResponse(modelName: pending.params.model.modelId)
        )
        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.prepareAndStartStreaming(
                providerSetting: pending.providerSetting,
                params: pending.params,
                runId: pending.runId,
                startedAt: pending.startedAt,
                inputDigest: pending.inputDigest,
                conversationId: pending.conversationId,
                uploadMessages: resumedMessages
            )
        }
    }

    private func dispatchStream(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onChunk: @escaping (MessageChunk) -> Void,
        onComplete: @escaping () -> Void,
        onError: @escaping (KotlinThrowable) -> Void
    ) -> Kotlinx_coroutines_coreJob? {
        if let openAI = providerSetting as? ProviderSetting.OpenAI {
            return provider.streamTextCancellable(
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
        onError(KotlinThrowable(message: "当前服务商类型暂不支持聊天"))
        return nil
    }

    private func finishStreaming() {
        cancelPendingStreamSnapshotPublish()
        currentRunId = nil
        currentStartedAt = nil
        currentInputDigest = nil
        currentConversationIdForRun = nil
        streamJob = nil
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        bindings.setIsLoading(false)
        clearPendingApprovals()
    }

    private func clearPendingApprovals() {
        clearPendingMemoryApproval()
        clearPendingSearchApproval()
        clearPendingWebMountApproval()
        clearPendingWorkspaceApproval()
        clearPendingIshHandoffApproval()
        clearPendingMcpApproval()
    }

    private func clearPendingMemoryApproval() {
        pendingMemoryToolApproval = nil
        bindings.setPendingMemoryApproval(nil)
    }

    private func clearPendingSearchApproval() {
        pendingSearchToolApproval = nil
        bindings.setPendingSearchApproval(nil)
    }

    private func clearPendingWebMountApproval() {
        pendingWebMountToolApproval = nil
        bindings.setPendingWebMountApproval(nil)
    }

    private func clearPendingWorkspaceApproval() {
        pendingWorkspaceToolApproval = nil
        bindings.setPendingWorkspaceApproval(nil)
    }

    private func clearPendingIshHandoffApproval() {
        pendingIshHandoffToolApproval = nil
        bindings.setPendingIshHandoffApproval(nil)
    }

    private func clearPendingMcpApproval() {
        pendingMcpToolApproval = nil
        bindings.setPendingMcpApproval(nil)
    }

    private static func toolCalls(in chunk: MessageChunk) -> [UIMessagePart.Tool] {
        chunk.choices.flatMap { choice in
            (choice.delta ?? choice.message)?.parts.compactMap { $0 as? UIMessagePart.Tool } ?? []
        }
    }

    private func handleDetectedToolCalls(_ toolCalls: [UIMessagePart.Tool], runId: String) {
        for toolCall in toolCalls where IOSSearchExecutor.supportedToolNames.contains(toolCall.toolName) {
            print("[ChatViewModel] Detected search tool call runId=\(runId) tool=\(toolCall.toolName) toolCallId=\(toolCall.toolCallId) inputDigest=\(chatInputDigest(for: toolCall.input))")
        }
        for toolCall in toolCalls where IOSWebMountToolCatalog.supportedToolNames.contains(toolCall.toolName) {
            print("[ChatViewModel] Detected WebMount tool call runId=\(runId) toolCallId=\(toolCall.toolCallId) tool=\(toolCall.toolName)")
        }
        for toolCall in toolCalls where IOSWorkspaceToolCatalog.supportedToolNames.contains(toolCall.toolName) {
            print("[ChatViewModel] Detected Workspace tool call runId=\(runId) toolCallId=\(toolCall.toolCallId) tool=\(toolCall.toolName)")
        }
        for toolCall in toolCalls where IOSIshToolCatalog.supportedToolNames.contains(toolCall.toolName) {
            print("[ChatViewModel] Detected iSH handoff tool call runId=\(runId) toolCallId=\(toolCall.toolCallId)")
        }
        for toolCall in toolCalls where IOSEmbeddedIshToolCatalog.supportedToolNames.contains(toolCall.toolName) {
            print("[ChatViewModel] Detected embedded iSH tool call runId=\(runId) toolCallId=\(toolCall.toolCallId)")
        }
        for toolCall in toolCalls where toolCall.toolName == "memory_tool" {
            print("[ChatViewModel] Detected memory_tool call runId=\(runId) toolCallId=\(toolCall.toolCallId)")
        }
    }

#if DEBUG
    func installPendingSearchApprovalForTesting(
        pending: ChatPendingToolApproval,
        request: SearchToolApprovalRequest
    ) {
        streamJob = nil
        currentRunId = pending.runId
        currentStartedAt = pending.startedAt
        currentInputDigest = pending.inputDigest
        currentConversationIdForRun = pending.conversationId
        pauseForApproval(.search(request), pending: pending)
    }

    func finishedToolCallMessagesForTesting(
        _ targetToolCall: UIMessagePart.Tool,
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        toolRuntime.finishedToolCallMessagesForTesting(
            targetToolCall,
            outputText: outputText,
            in: messages
        )
    }

    func memoryToolOutputForTesting(input: String) -> String {
        toolRuntime.memoryToolOutputForTesting(input: input)
    }

    func memoryApprovalRequestForTesting(input: String) -> MemoryToolApprovalRequest? {
        toolRuntime.memoryApprovalRequestForTesting(input: input)
    }

    func memoryToolApprovalOutputForTesting(input: String, allow: Bool) -> String {
        toolRuntime.memoryToolApprovalOutputForTesting(input: input, allow: allow)
    }

    func webMountToolOutputForTesting(
        toolName: String,
        input: String,
        isUserInitiated: Bool = false
    ) async -> String {
        await toolRuntime.webMountToolOutputForTesting(
            toolName: toolName,
            input: input,
            isUserInitiated: isUserInitiated
        )
    }

    func webMountApprovalRequestForTesting(
        toolName: String,
        input: String
    ) async -> WebMountToolApprovalRequest? {
        await toolRuntime.webMountApprovalRequestForTesting(
            toolName: toolName,
            input: input
        )
    }

    func webMountToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        await toolRuntime.webMountToolApprovalOutputForTesting(
            toolName: toolName,
            input: input,
            allow: allow
        )
    }

    func searchApprovalRequestForTesting(
        toolName: String,
        input: String
    ) -> SearchToolApprovalRequest? {
        toolRuntime.searchApprovalRequestForTesting(toolName: toolName, input: input)
    }

    func searchToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        await toolRuntime.searchToolApprovalOutputForTesting(
            toolName: toolName,
            input: input,
            allow: allow
        )
    }
#endif
}
