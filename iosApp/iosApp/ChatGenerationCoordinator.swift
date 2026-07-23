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

final class ChatStreamEvent: @unchecked Sendable {
    enum Payload {
        case chunk(MessageChunk)
        case complete
        case error(KotlinThrowable)
    }

    let payload: Payload

    private init(_ payload: Payload) {
        self.payload = payload
    }

    static func chunk(_ chunk: MessageChunk) -> ChatStreamEvent {
        ChatStreamEvent(.chunk(chunk))
    }

    static func complete() -> ChatStreamEvent {
        ChatStreamEvent(.complete)
    }

    static func error(_ error: KotlinThrowable) -> ChatStreamEvent {
        ChatStreamEvent(.error(error))
    }
}

final class ChatStreamEventSink: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: AsyncStream<ChatStreamEvent>.Continuation?
    private var pendingEvents: [ChatStreamEvent] = []
    private var pendingEventHead = 0
    private var isFinished = false

    func bind(_ continuation: AsyncStream<ChatStreamEvent>.Continuation) {
        lock.lock()
        guard !isFinished else {
            lock.unlock()
            continuation.finish()
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    func yield(_ event: ChatStreamEvent) {
        lock.lock()
        guard !isFinished, let continuation else {
            lock.unlock()
            return
        }
        pendingEvents.append(event)
        // `yield` 也放在锁内，确保不同 provider 回调线程看到同一 FIFO 次序。
        continuation.yield(event)
        lock.unlock()
    }

    func claim(_ event: ChatStreamEvent) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard pendingEventHead < pendingEvents.count,
              pendingEvents[pendingEventHead] === event else {
            return false
        }
        pendingEventHead += 1
        compactClaimedPrefixIfNeeded()
        return true
    }

    func takePendingChunks() -> [MessageChunk] {
        lock.lock()
        defer { lock.unlock() }
        var chunks: [MessageChunk] = []
        var retained: [ChatStreamEvent] = []
        if pendingEventHead < pendingEvents.count {
            retained.reserveCapacity(pendingEvents.count - pendingEventHead)
            for event in pendingEvents[pendingEventHead...] {
                if case .chunk(let chunk) = event.payload {
                    chunks.append(chunk)
                } else {
                    retained.append(event)
                }
            }
        }
        pendingEvents = retained
        pendingEventHead = 0
        return chunks
    }

    func finish() {
        lock.lock()
        let continuation = continuation
        self.continuation = nil
        isFinished = true
        lock.unlock()
        continuation?.finish()
    }

    /// Atomically chooses background ownership against a racing provider terminal callback.
    /// Accepted chunks remain drainable; a queued complete/error keeps foreground ownership.
    @MainActor
    func transitionToBackgroundIfNoTerminal(_ startBackground: () -> Bool) -> Bool {
        lock.lock()
        guard !isFinished,
              !pendingEvents[pendingEventHead...].contains(where: { event in
                switch event.payload {
                case .complete, .error:
                    return true
                case .chunk:
                    return false
                }
              }) else {
            lock.unlock()
            return false
        }
        let didStart = startBackground()
        let continuation = didStart ? continuation : nil
        if didStart {
            self.continuation = nil
            isFinished = true
        }
        lock.unlock()
        continuation?.finish()
        return didStart
    }

    private func compactClaimedPrefixIfNeeded() {
        guard pendingEventHead >= 64,
              pendingEventHead * 2 >= pendingEvents.count else { return }
        pendingEvents.removeFirst(pendingEventHead)
        pendingEventHead = 0
    }

#if DEBUG
    var pendingEventCountForTesting: Int {
        lock.lock()
        defer { lock.unlock() }
        return pendingEvents.count - pendingEventHead
    }
#endif
}

@MainActor
private final class ChatStreamAccumulatorSession {
    let accumulator: MessageStreamAccumulator
    let eventSink: ChatStreamEventSink
    var detectedToolCallIds = Set<String>()

    init(accumulator: MessageStreamAccumulator, eventSink: ChatStreamEventSink) {
        self.accumulator = accumulator
        self.eventSink = eventSink
    }
}

struct ChatStreamPresentationStep {
    let snapshot: [UIMessage]
    let isCaughtUp: Bool
}

enum ChatStreamPresentationPacer {
    /// Keeps one UI publication below a typical phone-width prose line while
    /// retaining the existing 48ms publication clock.
    static let maximumTextAdvance = 12

    static func step(current: [UIMessage], target: [UIMessage]) -> ChatStreamPresentationStep {
        guard let targetAssistant = target.last,
              targetAssistant.role == MessageRole.assistant else {
            return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
        }

        let currentAssistant: UIMessage?
        let currentPrefix: ArraySlice<UIMessage>
        if current.count == target.count,
           let last = current.last,
           last.role == MessageRole.assistant,
           last.id == targetAssistant.id {
            currentAssistant = last
            currentPrefix = current.dropLast()
        } else if current.count + 1 == target.count {
            currentAssistant = nil
            currentPrefix = current[...]
        } else {
            return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
        }

        let targetPrefix = target.dropLast()
        guard currentPrefix.count == targetPrefix.count,
              zip(currentPrefix, targetPrefix).allSatisfy({ $0.id == $1.id }) else {
            return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
        }

        let currentParts = currentAssistant?.parts ?? []
        guard currentParts.count <= targetAssistant.parts.count else {
            return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
        }

        var remainingBudget = maximumTextAdvance
        var caughtUp = true
        var pacedParts: [UIMessagePart] = []
        pacedParts.reserveCapacity(targetAssistant.parts.count)

        for (index, targetPart) in targetAssistant.parts.enumerated() {
            guard let targetText = targetPart as? UIMessagePart.Text else {
                if index < currentParts.count,
                   currentParts[index] is UIMessagePart.Text {
                    return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
                }
                pacedParts.append(targetPart)
                continue
            }

            let currentText: String
            if index < currentParts.count {
                guard let text = currentParts[index] as? UIMessagePart.Text else {
                    return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
                }
                currentText = text.text
            } else {
                currentText = ""
            }
            guard targetText.text.hasPrefix(currentText) else {
                return ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
            }

            let suffix = targetText.text.dropFirst(currentText.count)
            let advanceCount = min(remainingBudget, suffix.count)
            let pacedText = currentText + suffix.prefix(advanceCount)
            remainingBudget -= advanceCount
            if advanceCount < suffix.count {
                caughtUp = false
            }
            pacedParts.append(UIMessagePart.Text(text: String(pacedText), metadata: targetText.metadata))
        }

        let pacedAssistant = UIMessage(
            id: targetAssistant.id,
            role: targetAssistant.role,
            parts: pacedParts,
            annotations: targetAssistant.annotations,
            createdAt: targetAssistant.createdAt,
            finishedAt: caughtUp ? targetAssistant.finishedAt : currentAssistant?.finishedAt,
            modelId: targetAssistant.modelId,
            usage: caughtUp ? targetAssistant.usage : currentAssistant?.usage,
            translation: targetAssistant.translation
        )
        return ChatStreamPresentationStep(
            snapshot: Array(targetPrefix) + [pacedAssistant],
            isCaughtUp: caughtUp
        )
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
    let bumpMessageRevision: (ChatMessageUpdateReason) -> Void
    let shouldPaceStreamPresentation: () -> Bool
    let setIsLoading: (Bool) -> Void
    let setPendingMemoryApproval: (MemoryToolApprovalRequest?) -> Void
    let setPendingSearchApproval: (SearchToolApprovalRequest?) -> Void
    let setPendingWebMountApproval: (WebMountToolApprovalRequest?) -> Void
    let setPendingWorkspaceApproval: (WorkspaceToolApprovalRequest?) -> Void
    let setPendingIshHandoffApproval: (IshHandoffToolApprovalRequest?) -> Void
    let setPendingMcpApproval: (McpToolApprovalRequest?) -> Void
    let setPendingCouncilApproval: (CouncilToolApprovalRequest?) -> Void
    let setPendingAskUser: (ChatAskUserRequest?) -> Void
    let setContextCompactState: (ChatContextCompactState) -> Void
    let persistMessages: (KotlinUuid?) -> Void
    let capturePersistMessagesBaseline: (KotlinUuid?) -> IOSConversationWriteBaseline?
    let persistMessagesSnapshot: ([UIMessage], KotlinUuid?, IOSConversationWriteBaseline?) -> Void
    let recordRun: (String, Int64, String, String, String?) async -> Void
    let startLiveActivity: (String, KotlinUuid?, AgentActivityPresentation) -> Void
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
    private var grokWebStreamTask: Task<Void, Never>?
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
    private var pendingStreamSnapshotProvider: (() -> [UIMessage])?
    private var streamSnapshotFlushTask: Task<Void, Never>?
    private var streamEventTask: Task<Void, Never>?
    private var streamEventSink: ChatStreamEventSink?
    private var activeStreamSession: ChatStreamAccumulatorSession?
    /// 流式 UI 快照的发布间隔。每次发布都会让整个消息列表子树跑一轮 SwiftUI
    /// 事务(采样实测:子树全部短路后,事务图遍历本身仍是长内容流式的主线程
    /// 地板)。因此 48ms(≈20Hz)把事务频率从 60Hz 降到 20Hz；它不是屏幕或
    /// 动画帧率。普通小 delta 原样发布，超过一行量级的 provider burst 则在
    /// 同一时钟上分帧追上。cancel/error/handoff 始终从 accumulator 取权威快照；
    /// complete 只延后可见终态，直到分帧追上，数据完整性不受影响。
    private let streamSnapshotFlushDelayNanos: UInt64 = 48_000_000
    private var pendingMemoryToolApproval: ChatPendingToolApproval?
    private var pendingSearchToolApproval: ChatPendingToolApproval?
    private var pendingWebMountToolApproval: ChatPendingToolApproval?
    private var pendingWorkspaceToolApproval: ChatPendingToolApproval?
    private var pendingIshHandoffToolApproval: ChatPendingToolApproval?
    private var pendingMcpToolApproval: ChatPendingToolApproval?
    private var pendingCouncilToolApproval: ChatPendingToolApproval?
    private var pendingAskUserToolApproval: ChatPendingToolApproval?
    private var backgroundHandoff: IOSChatBackgroundHandoff?
    private weak var pendingBackgroundConversationStore: IOSConversationStore?
    private var foregroundToolExecutionTask: Task<ChatToolRuntimeResult, Never>?
    private var foregroundToolExecutionToken: UUID?
    private var foregroundImageToolExecutionTask: Task<[UIMessage], Never>?
    private var foregroundImageToolExecutionToken: UUID?

    var isRunning: Bool {
        currentRunId != nil
    }

    var activeConversationId: KotlinUuid? {
        currentConversationIdForRun
    }

    func hasPendingApproval(runId: String) -> Bool {
        currentRunId == runId && hasPendingToolApproval
    }

    private var hasPendingToolApproval: Bool {
        pendingMemoryToolApproval != nil ||
            pendingSearchToolApproval != nil ||
            pendingWebMountToolApproval != nil ||
            pendingWorkspaceToolApproval != nil ||
            pendingIshHandoffToolApproval != nil ||
            pendingMcpToolApproval != nil ||
            pendingCouncilToolApproval != nil ||
            pendingAskUserToolApproval != nil
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
            conversationId,
            .generatingResponse(modelName: params.model.modelId)
        )

        Task { @MainActor [weak self] in
            guard let self else { return }
            // Persist a "running" run record up-front so an interrupted mid-stream
            // run is detectable (status=running) by IOSRunRecovery's startup sweep,
            // which marks it interrupted. The terminal recordRun (completion / cancel
            // / error) REPLACEs this row with the final status + finishedAt
            // (insertRun is OnConflictStrategy.REPLACE).
            await self.bindings.recordRun(runId, startedAt, "running", inputDigest, conversationId?.toHexDashString())
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
            await self.prepareAndStartStreaming(
                providerSetting: effectiveProvider,
                params: params,
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                uploadMessages: uploadMessages,
                diagnosticOriginalProvider: providerSetting
            )
        }
    }

    func runImageTool(
        input: String,
        conversationId: KotlinUuid?,
        providerSetting: ProviderSetting?,
        params: TextGenerationParams?
    ) {
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
        bindings.startLiveActivity(
            runId,
            conversationId,
            .runningTool(toolName: "generate_image")
        )

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
        bindings.bumpMessageRevision(.toolCallStarted)
        bindings.persistMessages(conversationId)
        if let conversationId, let providerSetting, let params {
            backgroundHandoff = IOSChatBackgroundHandoff(
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                providerId: providerSetting.id.toHexDashString(),
                providerSetting: providerSetting,
                params: params,
                uploadMessages: snapshot,
                displayMessages: snapshot,
                mode: .singleToolOnly
            )
        }

        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.bindings.recordRun(runId, startedAt, "running", inputDigest, conversationId?.toHexDashString())
            guard self.currentRunId == runId else { return }
            let executionToken = UUID()
            let executionTask = Task { @MainActor [toolRuntime] in
                await toolRuntime.messagesByExecutingImageToolCall(
                    toolCall,
                    in: snapshot
                )
            }
            self.foregroundImageToolExecutionToken = executionToken
            self.foregroundImageToolExecutionTask = executionTask
            let resumed = await executionTask.value
            self.clearForegroundImageToolExecution(matching: executionToken)
            guard self.currentRunId == runId else { return }
            self.bindings.setMessages(resumed)
            self.bindings.bumpMessageRevision(.toolResultAppended)
            let failureReason = ChatToolOutputFormatter.imageFailureReason(in: resumed, matching: toolCall)
            let conversationHex = conversationId?.toHexDashString()
            await self.bindings.recordRun(
                runId,
                startedAt,
                failureReason == nil ? "completed" : "failed",
                inputDigest,
                conversationHex
            )
            if failureReason == nil {
                WatchTaskCoordinator.shared.publishCompleted(
                    runId: runId,
                    conversationId: conversationHex,
                    summary: Self.watchSummary(from: resumed),
                    kind: .imageGeneration
                )
            } else {
                WatchTaskCoordinator.shared.publish(
                    runId: runId,
                    conversationId: conversationHex,
                    presentation: .failed(),
                    summary: WatchTaskText.clipped(failureReason, maxLength: 200)
                )
            }
            await self.dependencies.liveActivityController.end(
                runId: runId,
                presentation: failureReason == nil ? .completed(toolTitle: "图片生成") : .failed()
            )
            self.bindings.persistMessages(conversationId)
            self.finishStreaming(
                terminalEvent: failureReason == nil ? .generationCompleted : .generationFailed
            )
        }
    }

    func cancel() {
        let runId = currentRunId
        let startedAt = currentStartedAt
        let digest = currentInputDigest
        let conversationId = currentConversationIdForRun

        streamJob?.cancel(cause: nil)
        streamJob = nil
        grokWebStreamTask?.cancel()
        grokWebStreamTask = nil
        streamEventSink?.finish()
        drainPendingStreamChunksIntoAccumulator()
        let pendingStreamSnapshotAtCancellation = latestPendingStreamSnapshot()
        var messagesAtCancellation = pendingStreamSnapshotAtCancellation ?? bindings.getMessages()
        // Cancel must close empty tool outputs (including ask_user). Otherwise a later
        // complete/resume path can re-pick the same unresolved tool as a fresh pending node.
        if toolRuntime.hasUnresolvedToolCall(in: messagesAtCancellation) {
            messagesAtCancellation = toolRuntime.messagesByFailingPendingToolCalls(
                in: messagesAtCancellation,
                outputText: #"{"denied":true,"reason":"User cancelled."}"#
            )
        }
        let writeBaselineAtCancellation = bindings.capturePersistMessagesBaseline(conversationId)
        bindings.setMessages(messagesAtCancellation)
        cancelStreamEventConsumer()
        cancelForegroundToolExecutions()
        cancelPendingStreamSnapshotPublish()
        // 取消也是 run 的终结:与 finishStreaming/后台交接保持一致,关闭录制器,
        // 否则取消路径会泄漏文件句柄与 per-run 字典条目。
        if let runId {
            ChatStreamRecorder.shared.record(runId: runId, snapshot: messagesAtCancellation)
            ChatStreamRecorder.shared.finish(runId: runId)
            // Publish cancelled immediately so Watch does not briefly fall to idle.
            WatchTaskCoordinator.shared.publish(
                runId: runId,
                conversationId: conversationId?.toHexDashString(),
                presentation: .cancelled(),
                summary: nil
            )
        }
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
        if runId != nil {
            bindings.bumpMessageRevision(.generationCancelled)
        }

        guard let runId, let startedAt, let digest else { return }
        Task { @MainActor [dependencies, bindings] in
            await dependencies.liveActivityController.end(
                runId: runId,
                presentation: .cancelled()
            )
            await bindings.recordRun(runId, startedAt, "interrupted", digest, conversationId?.toHexDashString())
            bindings.persistMessagesSnapshot(messagesAtCancellation, conversationId, writeBaselineAtCancellation)
        }
    }

    /// (Re)snapshots the background handoff payload. Called at every point a run
    /// can be interrupted by a background transition:
    ///  - `startStreaming` (model streaming), so a lock screen mid-stream hands
    ///    off the current round's input/output snapshot.
    ///  - `executeToolCall` (tool HTTP in flight), so a lock screen DURING tool
    ///    execution hands off a snapshot that still carries the model's just-made
    ///    tool call. The background engine then pre-executes that pending tool
    ///    (see IOSAgentToolEngine.executePreExistingPendingTools) instead of
    ///    re-prompting the model to re-issue it — e.g. avoiding a wasted
    ///    duplicate image generation.
    private func refreshBackgroundHandoff(
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        providerSetting: ProviderSetting,
        backgroundProviderSetting: ProviderSetting?,
        params: TextGenerationParams,
        uploadMessages: [UIMessage],
        displayMessages: [UIMessage]
    ) {
        if let conversationId {
            backgroundHandoff = IOSChatBackgroundHandoff(
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId,
                providerId: (backgroundProviderSetting ?? providerSetting).id.toHexDashString(),
                providerSetting: backgroundProviderSetting ?? providerSetting,
                params: params,
                uploadMessages: uploadMessages,
                displayMessages: displayMessages,
                mode: .continueModel
            )
        } else {
            backgroundHandoff = nil
        }
    }

    private func backgroundToolHandoffUploadMessages(from baseMessages: [UIMessage]) -> [UIMessage] {
        let runtimeMessages = bindings.messagesByInjectingRuntimeContext(baseMessages)
        return ChatRuntimeContextBuilder.coalescingSystemMessages(runtimeMessages)
    }

    @discardableResult
    func handoffCurrentGenerationToBackground(conversationStore: IOSConversationStore?) -> Bool {
        guard !hasPendingToolApproval else { return false }
        // Once a foreground tool request is already in flight, keep that request
        // as the owner. BG handoff is not guaranteed to start immediately; if we
        // cancel/clear the foreground run here, the in-flight result can be
        // discarded and the user returns to an empty pending tool bubble.
        guard foregroundToolExecutionTask == nil,
              foregroundImageToolExecutionTask == nil else {
            return false
        }
        guard let handoff = backgroundHandoff,
              let conversationStore else {
            if isRunning {
                pendingBackgroundConversationStore = conversationStore
            }
            return false
        }
        guard !IOSGrokWebProviderResolver.isGrokWebProvider(handoff.providerSetting) else {
            return false
        }
        let startBackground = { [self] in
            IOSChatBackgroundGenerationCoordinator.shared.start(
                handoff: handoff,
                conversationStore: conversationStore,
                toolRuntime: self.toolRuntime,
                liveActivityController: self.dependencies.liveActivityController,
                saveMiniAppIfPresent: { [bindings = self.bindings] messages, conversationId in
                    bindings.saveMiniAppIfPresent(messages, conversationId)
                }
            )
        }
        let didStart = streamEventSink?.transitionToBackgroundIfNoTerminal(startBackground)
            ?? startBackground()
        guard didStart else { return false }

        streamJob?.cancel(cause: nil)
        streamJob = nil
        grokWebStreamTask?.cancel()
        grokWebStreamTask = nil
        drainPendingStreamChunksIntoAccumulator()
        cancelForegroundToolExecutions()
        if let pendingStreamSnapshot = latestPendingStreamSnapshot() {
            // Background handoff bypasses the normal scheduleStreamSnapshotPublish
            // path, so without this the recorder would miss the last in-flight
            // snapshot before ownership moves to the background coordinator.
            if let runId = currentRunId {
                ChatStreamRecorder.shared.record(runId: runId, snapshot: pendingStreamSnapshot)
            }
            bindings.setMessages(pendingStreamSnapshot)
            bindings.bumpMessageRevision(.streamDelta)
        }
        cancelStreamEventConsumer()
        cancelPendingStreamSnapshotPublish()
        if let runId = currentRunId {
            ChatStreamRecorder.shared.finish(runId: runId)
        }
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
        bindings.bumpMessageRevision(.generationHandedOffToBackground)
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

    func approvePendingCouncilTool() async {
        await finishPendingCouncilToolApproval(allow: true)
    }

    func denyPendingCouncilTool() async {
        await finishPendingCouncilToolApproval(allow: false)
    }

    private func prepareAndStartStreaming(
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        uploadMessages: [UIMessage],
        diagnosticOriginalProvider: ProviderSetting? = nil
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
        IOSCodexProviderResolver.writeRequestDiagnostic(
            originalProvider: diagnosticOriginalProvider ?? providerSetting,
            resolvedProvider: effectiveProvider,
            params: effectiveParams
        )

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
                    guard let self,
                          ChatContextCompactEventRouter.shouldApply(
                            event: event,
                            eventRunId: runId,
                            currentRunId: self.currentRunId
                          ) else { return }
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
        let promptedUploadMessages = messagesByInjectingImageGenerationPromptIfNeeded(
            preparedUploadMessages,
            params: effectiveParams
        )
        let runtimePreparedMessages = bindings.messagesByInjectingRuntimeContext(promptedUploadMessages)
        let finalizedUploadMessages: [UIMessage]
        do {
            finalizedUploadMessages = try IOSContextCompactionCoordinator.shared.finalizedMessagesForRequest(
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
        let finalUploadMessages = ChatRuntimeContextBuilder.coalescingSystemMessages(finalizedUploadMessages)
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

    private func messagesByInjectingImageGenerationPromptIfNeeded(
        _ messages: [UIMessage],
        params: TextGenerationParams
    ) -> [UIMessage] {
        guard params.tools.contains(where: { $0.name == "generate_image" }) else {
            return messages
        }
        let prompt = """
        Image-generation routing guidance for AmberAgent iOS:
        - When the user asks for a photographic, painted, illustrated, poster, wallpaper, concept-art, or character-art image, call `generate_image` exactly once instead of answering with SVG/HTML.
        - Preserve the user's subject, style, aspect-ratio cues, and language. Prefer a detailed prompt with subject, composition, lighting, mood, and visual style.
        - If the request references named fiction/IP, avoid brittle prompt wording like "fan art of <character> from <franchise>". Use an original inspired depiction that keeps the user's requested vibe and recognizable high-level visual cues without asking for an exact copyrighted character, logo, actor, or celebrity likeness.
        - If `generate_image` fails, report the failure honestly and ask whether to retry or adjust the prompt. Do not substitute an SVG/code sketch as if image generation succeeded unless the user explicitly asks for a fallback sketch.
        """
        let systemMessage = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.system,
            parts: [UIMessagePart.Text(text: prompt, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        return [systemMessage] + messages
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
        refreshBackgroundHandoff(
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            providerSetting: providerSetting,
            backgroundProviderSetting: backgroundProviderSetting,
            params: params,
            uploadMessages: uploadMessages,
            displayMessages: displayMessages
        )
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
        let eventSink = ChatStreamEventSink()
        cancelStreamEventConsumer()
        let streamSession = ChatStreamAccumulatorSession(
            accumulator: accumulator,
            eventSink: eventSink
        )
        streamEventSink = eventSink
        activeStreamSession = streamSession
        // 从 session 建立起就保留快照入口；即使 MainActor 尚未消费首个 chunk，
        // cancel / background handoff 也能在 drain 后拿到累加器的最新状态。
        pendingStreamSnapshotProvider = { accumulator.snapshot() }
        let eventStream = AsyncStream<ChatStreamEvent>(bufferingPolicy: .unbounded) { continuation in
            eventSink.bind(continuation)
        }
        streamEventTask = Task { @MainActor [weak self] in
            for await event in eventStream {
                guard eventSink.claim(event) else { continue }
                guard let self, self.currentRunId == runId else { return }
                switch event.payload {
                case .chunk(let chunk):
                    accumulator.append(chunk: chunk)
                    let toolCalls = Self.toolCalls(in: chunk)
                        .filter { toolCall in
                            let key = chatToolCallKey(toolCall)
                            guard !streamSession.detectedToolCallIds.contains(key) else { return false }
                            streamSession.detectedToolCallIds.insert(key)
                            return true
                        }
                    if !toolCalls.isEmpty {
                        self.handleDetectedToolCalls(toolCalls, runId: runId)
                    }
                    self.scheduleStreamSnapshotPublish(
                        snapshotProvider: { accumulator.snapshot() },
                        runId: runId
                    )
                case .complete:
                    self.cancelPendingStreamSnapshotPublish()
                    let snapshot = accumulator.snapshot()
                    // Terminal hands authority to bindings/tool execution. Keeping this
                    // accumulator reachable would let a later cancel restore a stale pre-tool snapshot.
                    self.activeStreamSession = nil
                    guard await self.drainStreamPresentation(to: snapshot, runId: runId) else {
                        return
                    }
                    await self.handleCompletedStream(
                        snapshot: snapshot,
                        providerSetting: providerSetting,
                        params: params,
                        runId: runId,
                        startedAt: startedAt,
                        inputDigest: inputDigest,
                        conversationId: conversationId
                    )
                    return
                case .error(let error):
                    self.cancelPendingStreamSnapshotPublish()
                    let snapshot = accumulator.snapshot()
                    // The terminal snapshot is published below; it is no longer a cancel fallback.
                    self.activeStreamSession = nil
                    ChatStreamRecorder.shared.record(runId: runId, snapshot: snapshot)
                    // 与 .complete 对称:先按 pacer 逐拍追平积压文本,再发布终态与错误气泡。
                    // drain 失败(run 被取消/接管)时终态所有权已在别处,直接退出。
                    guard await self.drainStreamPresentation(to: snapshot, runId: runId) else {
                        return
                    }
                    self.bindings.setMessages(snapshot)
                    self.bindings.bumpMessageRevision(.assistantStreamClosed)
                    await self.presentStreamError(
                        rawMessage: error.message ?? String(describing: error),
                        modelId: params.model.modelId,
                        runId: runId,
                        startedAt: startedAt,
                        inputDigest: inputDigest,
                        conversationId: conversationId
                    )
                    return
                }
            }
        }

        streamJob = dispatchStream(
            providerSetting: providerSetting,
            messages: uploadMessages,
            params: params,
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
    }

    private func scheduleStreamSnapshotPublish(
        snapshotProvider: @escaping () -> [UIMessage],
        runId: String
    ) {
        pendingStreamSnapshotProvider = snapshotProvider
        guard streamSnapshotFlushTask == nil else { return }
        streamSnapshotFlushTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: self?.streamSnapshotFlushDelayNanos ?? 48_000_000)
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
            pendingStreamSnapshotProvider = nil
            return
        }
        ChatPerfTrace.measure("StreamFlush") {
            guard let targetSnapshot = latestPendingStreamSnapshot() else { return }
            pendingStreamSnapshot = nil
            pendingStreamSnapshotProvider = nil
            let caughtUp = publishPacedStreamSnapshot(targetSnapshot, runId: runId)
            if !caughtUp {
                pendingStreamSnapshot = targetSnapshot
                scheduleStreamSnapshotPublish(
                    snapshotProvider: { targetSnapshot },
                    runId: runId
                )
            }
        }
    }

    @discardableResult
    private func publishPacedStreamSnapshot(_ target: [UIMessage], runId: String) -> Bool {
        let step = bindings.shouldPaceStreamPresentation()
            ? ChatStreamPresentationPacer.step(current: bindings.getMessages(), target: target)
            : ChatStreamPresentationStep(snapshot: target, isCaughtUp: true)
        ChatStreamRecorder.shared.record(runId: runId, snapshot: step.snapshot)
        bindings.setMessages(step.snapshot)
        bindings.bumpMessageRevision(.streamDelta)
        return step.isCaughtUp
    }

    private func drainStreamPresentation(to target: [UIMessage], runId: String) async -> Bool {
        // Keep the authoritative terminal snapshot reachable while presentation
        // catches up. A cancellation during this short window must persist the
        // full provider result rather than the currently visible prefix.
        pendingStreamSnapshot = target
        while currentRunId == runId, !Task.isCancelled {
            if publishPacedStreamSnapshot(target, runId: runId) {
                pendingStreamSnapshot = nil
                return true
            }
            do {
                try await Task.sleep(nanoseconds: streamSnapshotFlushDelayNanos)
            } catch {
                return false
            }
        }
        return false
    }

    private func latestPendingStreamSnapshot() -> [UIMessage]? {
        if let provider = pendingStreamSnapshotProvider {
            let snapshot = provider()
            pendingStreamSnapshot = snapshot
            return snapshot
        }
        if let pendingStreamSnapshot {
            return pendingStreamSnapshot
        }
        return activeStreamSession?.accumulator.snapshot()
    }

    private func drainPendingStreamChunksIntoAccumulator() {
        guard let activeStreamSession else { return }
        for chunk in activeStreamSession.eventSink.takePendingChunks() {
            activeStreamSession.accumulator.append(chunk: chunk)
        }
    }

    private func cancelPendingStreamSnapshotPublish() {
        streamSnapshotFlushTask?.cancel()
        streamSnapshotFlushTask = nil
        pendingStreamSnapshot = nil
        pendingStreamSnapshotProvider = nil
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
        let conversationHex = conversationId?.toHexDashString()
        await bindings.recordRun(runId, startedAt, "failed", inputDigest, conversationHex)
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: conversationHex,
            presentation: .failed(),
            summary: WatchTaskText.clipped(userFacingMessage, maxLength: 200)
        )
        await dependencies.liveActivityController.end(runId: runId, presentation: .failed())
        bindings.persistMessages(conversationId)
        finishStreaming(terminalEvent: .generationFailed)
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
        bindings.bumpMessageRevision(.assistantStreamClosed)

        if let pendingToolCall = toolRuntime.nextPendingToolCall(in: snapshot) {
            guard currentToolResumeCount < maxToolResumeCount else {
                await failPendingToolCalls(
                    snapshot: snapshot,
                    failureText: "工具调用未执行：已达到本轮工具循环上限（\(maxToolResumeCount) 次）。请继续对话或拆分任务后重试。",
                    runId: runId,
                    startedAt: startedAt,
                    inputDigest: inputDigest,
                    conversationId: conversationId
                )
                return
            }

            currentToolResumeCount += 1
            bindings.bumpMessageRevision(.toolCallStarted)
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

        if toolRuntime.hasUnresolvedToolCall(in: snapshot) {
            await failPendingToolCalls(
                snapshot: snapshot,
                failureText: "工具调用未执行：该工具当前未启用或不可执行。请在设置中启用对应能力后重试。",
                runId: runId,
                startedAt: startedAt,
                inputDigest: inputDigest,
                conversationId: conversationId
            )
            return
        }

        var finalSnapshot = snapshot
        if let miniAppNotice = bindings.saveMiniAppIfPresent(snapshot, conversationId) {
            finalSnapshot.append(miniAppNotice)
            bindings.setMessages(finalSnapshot)
            bindings.bumpMessageRevision(.toolResultAppended)
        }

        let conversationHex = conversationId?.toHexDashString()
        let summary = Self.watchSummary(from: finalSnapshot)
        await bindings.recordRun(runId, startedAt, "completed", inputDigest, conversationHex)
        WatchTaskCoordinator.shared.publishCompleted(
            runId: runId,
            conversationId: conversationHex,
            summary: summary
        )
        await dependencies.liveActivityController.end(
            runId: runId,
            presentation: .completed()
        )
        bindings.persistMessages(conversationId)
        finishStreaming(terminalEvent: .generationCompleted)
    }

    private func failPendingToolCalls(
        snapshot: [UIMessage],
        failureText: String,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?
    ) async {
        let writeBaseline = bindings.capturePersistMessagesBaseline(conversationId)
        let finalSnapshot = toolRuntime.messagesByFailingPendingToolCalls(
            in: snapshot,
            outputText: failureText
        )
        bindings.setMessages(finalSnapshot)
        bindings.bumpMessageRevision(.toolResultAppended)
        let conversationHex = conversationId?.toHexDashString()
        await bindings.recordRun(runId, startedAt, "failed", inputDigest, conversationHex)
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: conversationHex,
            presentation: .failed(),
            summary: WatchTaskText.clipped(failureText, maxLength: 200)
        )
        await dependencies.liveActivityController.end(runId: runId, presentation: .failed())
        bindings.persistMessagesSnapshot(finalSnapshot, conversationId, writeBaseline)
        finishStreaming(terminalEvent: .generationFailed)
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
        let toolPresentation = AgentActivityPresentation.runningTool(
            toolName: pendingToolCall.toolCall.toolName
        )
        WatchTaskCoordinator.shared.publish(
            runId: runId,
            conversationId: conversationId?.toHexDashString(),
            presentation: toolPresentation
        )
        await dependencies.liveActivityController.update(
            runId: runId,
            presentation: toolPresentation,
            force: true
        )
        // Refresh the handoff snapshot right before the (potentially long) tool
        // HTTP call, so a background transition during tool execution hands off
        // a snapshot carrying this pending tool call. The background engine then
        // pre-executes it instead of re-prompting the model to re-issue it.
        refreshBackgroundHandoff(
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            providerSetting: providerSetting,
            backgroundProviderSetting: nil,
            params: params,
            uploadMessages: backgroundToolHandoffUploadMessages(from: baseMessages),
            displayMessages: bindings.getMessages()
        )
        let executionToken = UUID()
        let executionTask = Task { @MainActor [toolRuntime] in
            await toolRuntime.execute(pendingToolCall, context: pending)
        }
        foregroundToolExecutionToken = executionToken
        foregroundToolExecutionTask = executionTask
        let result = await executionTask.value
        clearForegroundToolExecution(matching: executionToken)
        guard currentRunId == runId else { return }

        switch result {
        case .completed(let resumedMessages):
            bindings.setMessages(resumedMessages)
            bindings.bumpMessageRevision(.toolResultAppended)
            let generating = AgentActivityPresentation.generatingResponse(
                modelName: params.model.modelId
            )
            WatchTaskCoordinator.shared.publish(
                runId: runId,
                conversationId: conversationId?.toHexDashString(),
                presentation: generating
            )
            await dependencies.liveActivityController.update(
                runId: runId,
                presentation: generating,
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
        case .council(let request):
            pendingCouncilToolApproval = pending
            bindings.setPendingCouncilApproval(request)
        case .askUser(let request):
            pendingAskUserToolApproval = pending
            bindings.setPendingAskUser(request)
            WatchTaskCoordinator.shared.publishAskUser(
                runId: pending.runId,
                conversationId: pending.conversationId?.toHexDashString(),
                request: WatchAskUserRequest(
                    id: request.id,
                    question: request.question,
                    options: request.options
                )
            )
        }
        bindings.setMessages(pending.baseMessages)
        bindings.bumpMessageRevision(.awaitingToolApproval)
        bindings.setIsLoading(false)
        if case .askUser = prompt {
            // Watch already received the ask-user decision above.
        } else {
            let conversationHex = pending.conversationId?.toHexDashString()
            WatchTaskCoordinator.shared.publishWaitingApproval(
                runId: pending.runId,
                conversationId: conversationHex,
                prompt: prompt
            )
        }
        Task { @MainActor [dependencies] in
            await dependencies.liveActivityController.update(
                runId: pending.runId,
                presentation: .waitingForUser(kind: prompt.activityKind),
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
        bindings.bumpMessageRevision(.toolResultAppended)
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
        bindings.bumpMessageRevision(.toolResultAppended)
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
        bindings.bumpMessageRevision(.toolResultAppended)
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
        bindings.bumpMessageRevision(.toolResultAppended)
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
        bindings.bumpMessageRevision(.toolResultAppended)
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
        bindings.bumpMessageRevision(.toolResultAppended)
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    private func finishPendingCouncilToolApproval(allow: Bool) async {
        guard let pending = pendingCouncilToolApproval else { return }
        clearPendingCouncilApproval()
        guard currentRunId == pending.runId else { return }
        bindings.setIsLoading(true)
        let executionToken = UUID()
        let executionTask: Task<ChatToolRuntimeResult, Never> = Task { @MainActor [toolRuntime] in
            .completed(await toolRuntime.finishCouncilApproval(
                pending: pending,
                allow: allow
            ))
        }
        foregroundToolExecutionToken = executionToken
        foregroundToolExecutionTask = executionTask
        let result = await executionTask.value
        clearForegroundToolExecution(matching: executionToken)
        guard currentRunId == pending.runId else { return }
        guard case .completed(let resumedMessages) = result else { return }
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision(.toolResultAppended)
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
    }

    @discardableResult
    func answerPendingAskUser(_ answer: String) -> Bool {
        finishPendingAskUserAnswer(answer: answer)
    }

    @discardableResult
    private func finishPendingAskUserAnswer(answer: String) -> Bool {
        guard let pending = pendingAskUserToolApproval else { return false }
        // Validate ownership before clearing UI/pending. Otherwise a racing cancel or
        // run replacement can drop the card without writing tool output.
        guard currentRunId == pending.runId else { return false }
        clearPendingAskUser()
        let resumedMessages = toolRuntime.finishAskUserAnswer(
            pending: pending,
            answer: answer
        )
        bindings.setMessages(resumedMessages)
        bindings.bumpMessageRevision(.toolResultAppended)
        resumeAfterApproval(pending: pending, resumedMessages: resumedMessages)
        return true
    }

    private func resumeAfterApproval(
        pending: ChatPendingToolApproval,
        resumedMessages: [UIMessage]
    ) {
        guard currentRunId == pending.runId else { return }
        guard dependencies.autoGenerateResponses else {
            let conversationHex = pending.conversationId?.toHexDashString()
            WatchTaskCoordinator.shared.publishCompleted(
                runId: pending.runId,
                conversationId: conversationHex,
                summary: nil
            )
            Task { @MainActor [dependencies] in
                await dependencies.liveActivityController.end(
                    runId: pending.runId,
                    presentation: .completed()
                )
            }
            bindings.persistMessages(pending.conversationId)
            finishStreaming(terminalEvent: .generationCompleted)
            return
        }

        bindings.setIsLoading(true)
        bindings.startLiveActivity(
            pending.runId,
            pending.conversationId,
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

    private func finishStreaming(terminalEvent: ChatMessageUpdateReason? = nil) {
        cancelPendingStreamSnapshotPublish()
        cancelStreamEventConsumer()
        if let runId = currentRunId {
            ChatStreamRecorder.shared.finish(runId: runId)
        }
        grokWebStreamTask?.cancel()
        grokWebStreamTask = nil
        currentRunId = nil
        currentStartedAt = nil
        currentInputDigest = nil
        currentConversationIdForRun = nil
        streamJob = nil
        clearForegroundToolExecutions()
        backgroundHandoff = nil
        pendingBackgroundConversationStore = nil
        bindings.setIsLoading(false)
        clearPendingApprovals()
        if let terminalEvent {
            bindings.bumpMessageRevision(terminalEvent)
        }
    }

    private func cancelStreamEventConsumer() {
        streamEventSink?.finish()
        streamEventSink = nil
        streamEventTask?.cancel()
        streamEventTask = nil
        activeStreamSession = nil
    }

    private func cancelForegroundToolExecutions() {
        foregroundToolExecutionTask?.cancel()
        foregroundToolExecutionTask = nil
        foregroundToolExecutionToken = nil
        foregroundImageToolExecutionTask?.cancel()
        foregroundImageToolExecutionTask = nil
        foregroundImageToolExecutionToken = nil
    }

    private func clearForegroundToolExecutions() {
        foregroundToolExecutionTask = nil
        foregroundToolExecutionToken = nil
        foregroundImageToolExecutionTask = nil
        foregroundImageToolExecutionToken = nil
    }

    private func clearForegroundToolExecution(matching token: UUID) {
        guard foregroundToolExecutionToken == token else { return }
        foregroundToolExecutionTask = nil
        foregroundToolExecutionToken = nil
    }

    private func clearForegroundImageToolExecution(matching token: UUID) {
        guard foregroundImageToolExecutionToken == token else { return }
        foregroundImageToolExecutionTask = nil
        foregroundImageToolExecutionToken = nil
    }

    private func clearPendingApprovals() {
        clearPendingMemoryApproval()
        clearPendingSearchApproval()
        clearPendingWebMountApproval()
        clearPendingWorkspaceApproval()
        clearPendingIshHandoffApproval()
        clearPendingMcpApproval()
        clearPendingCouncilApproval()
        clearPendingAskUser()
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

    private func clearPendingCouncilApproval() {
        pendingCouncilToolApproval = nil
        bindings.setPendingCouncilApproval(nil)
    }

    private func clearPendingAskUser() {
        pendingAskUserToolApproval = nil
        bindings.setPendingAskUser(nil)
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

    func backgroundToolHandoffUploadMessagesForTesting(_ baseMessages: [UIMessage]) -> [UIMessage] {
        backgroundToolHandoffUploadMessages(from: baseMessages)
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

    func failingPendingToolCallMessagesForTesting(
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        toolRuntime.messagesByFailingPendingToolCalls(
            in: messages,
            outputText: outputText
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


extension ChatGenerationCoordinator {
    fileprivate static func watchSummary(from messages: [UIMessage]) -> String? {
        guard let lastAssistant = messages.last(where: { $0.role == MessageRole.assistant }) else {
            return nil
        }
        let text = lastAssistant.parts
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return WatchTaskText.clipped(text, maxLength: 280)
    }
}
