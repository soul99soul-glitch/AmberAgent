import Foundation
import Observation
@preconcurrency import Shared

// MARK: - ChatViewModel

struct ChatContextSnapshot {
    let messageCount: Int
    let modelId: String
    let supportsReasoning: Bool
    let pendingSelectedFileName: String?
    let pendingSelectedFileBytesText: String?
    // [Slice 5] Real token aggregation from messages.usage (TokenUsage on
    // each UIMessage; aggregated by reduce over messages). 0 when no usage
    // recorded (e.g. no API key / no completed runs yet) — honest, not faked.
    let promptTokens: Int
    let completionTokens: Int
    let totalTokens: Int
    let cachedTokens: Int
    let tokensPerSecond: Double?
}

private struct PendingAssistantRegeneration {
    let conversationId: KotlinUuid
    let targetMessageIndex: Int
    let generatedMessageIndex: Int
}

@MainActor
@Observable
final class ChatViewModel {

    // MARK: - State

    var messages: [UIMessage] = []
    var inputText: String = ""
    var isLoading: Bool = false
    var isAttachingSelectedFile: Bool = false
    var pendingSelectedFilePreview: SelectedDocumentReadResult?
    var selectedFileContextError: String?
    var reasoningLevel: ReasoningLevel = .off
    var messageRevision: Int = 0
    var pendingMemoryApproval: MemoryToolApprovalRequest?
    var pendingSearchApproval: SearchToolApprovalRequest?
    var pendingWebMountApproval: WebMountToolApprovalRequest?
    var pendingWorkspaceApproval: WorkspaceToolApprovalRequest?
    var pendingMcpApproval: McpToolApprovalRequest?
    var configurationError: String?

    /// 持久化存储（由 AppShell 注入）。nil 时退化为纯内存模式（向后兼容旧调用方）。
    weak var conversationStore: IOSConversationStore?

    /// 当前会话 id，与 conversationStore.currentConversation.id 同步。
    /// 留作快速访问；切换会话后由 reloadFromStore() 刷新。
    var currentConversationId: KotlinUuid? {
        conversationStore?.currentConversation?.id
    }

    var contextSnapshot: ChatContextSnapshot {
        // [Slice 5] Aggregate TokenUsage across all messages (each UIMessage
        // carries usage: TokenUsage? set by the provider on completion).
        var prompt = 0
        var completion = 0
        var cached = 0
        var timedCompletionTokens = 0
        var generationDuration = 0.0
        for message in messages {
            guard let usage = message.usage else { continue }
            prompt += Int(usage.promptTokens)
            completion += Int(usage.completionTokens)
            cached += Int(usage.cachedTokens)
            if let duration = Self.durationSeconds(from: message.createdAt, to: message.finishedAt),
               duration > 0 {
                timedCompletionTokens += Int(usage.completionTokens)
                generationDuration += duration
            }
        }
        return ChatContextSnapshot(
            messageCount: messages.count,
            modelId: currentModelId,
            supportsReasoning: currentModelSupportsReasoning,
            pendingSelectedFileName: pendingSelectedFilePreview?.fileName,
            pendingSelectedFileBytesText: pendingSelectedFilePreview?.byteSummary,
            promptTokens: prompt,
            completionTokens: completion,
            totalTokens: prompt + completion,
            cachedTokens: cached,
            tokensPerSecond: generationDuration > 0 ? Double(timedCompletionTokens) / generationDuration : nil
        )
    }

    private static func durationSeconds(
        from start: Kotlinx_datetimeLocalDateTime,
        to end: Kotlinx_datetimeLocalDateTime?
    ) -> TimeInterval? {
        guard let end,
              let startDate = date(from: start),
              let endDate = date(from: end) else {
            return nil
        }
        return endDate.timeIntervalSince(startDate)
    }

    private static func date(from localDateTime: Kotlinx_datetimeLocalDateTime) -> Date? {
        var components = DateComponents()
        components.calendar = Calendar.current
        components.timeZone = TimeZone.current
        components.year = Int(localDateTime.year)
        components.month = Int(localDateTime.month.ordinal) + 1
        components.day = Int(localDateTime.day)
        components.hour = Int(localDateTime.hour)
        components.minute = Int(localDateTime.minute)
        components.second = Int(localDateTime.second)
        components.nanosecond = Int(localDateTime.nanosecond)
        return components.date
    }

    var currentModelSupportsReasoning: Bool {
        currentModelAbilities.contains(.reasoning)
    }

    var isGenerationActive: Bool {
        generationCoordinator.isRunning
    }

    var configurationIssue: ChatConfigurationIssue? {
        if let currentModel {
            return ChatProviderConfiguration.issue(
                for: currentModel,
                provider: ChatProviderConfiguration.provider(
                    for: currentModel,
                    providers: sharedSettings.snapshot.providers
                )
            )
        }

        if !ChatProviderConfiguration.configuredChatModels(in: sharedSettings.snapshot.providers).isEmpty {
            return .missingModel
        }

        // Legacy scalar fallback for callers that have not selected a KMP
        // provider-backed chat model yet.
        return ChatProviderConfiguration.issue(
            baseUrl: settingsStore.baseUrl,
            apiKey: settingsStore.apiKey,
            modelId: settingsStore.modelId
        )
    }

    // MARK: - Private

    private let settingsStore: SettingsStore
    private let sharedSettings: IOSSharedSettingsStore
    private let localToolExecutor: IOSLocalToolExecutor?
    private let searchTransport: any IOSSearchHTTPTransport
    private let miniAppRepository: IOSMiniAppRepository
    private let autoGenerateResponses: Bool
    private let liveActivityController: AgentLiveActivityController
    @ObservationIgnored private lazy var db: AgentRuntimeDatabase = IosDatabaseFactory.shared.createDatabase()
    @ObservationIgnored private lazy var mcpManager: IOSMcpManager = {
        // Build from the shared config store (same UserDefaults key as
        // McpServersView) so callTool reaches the same configured servers.
        IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared)
    }()
    @ObservationIgnored private lazy var generationCoordinator = makeGenerationCoordinator()
    private var attachRequestId: UUID?

    private var currentModel: Model? {
        sharedSettings.snapshot.getCurrentChatModel()
    }

    private var currentModelId: String {
        (currentModel?.modelId ?? settingsStore.modelId).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var currentModelAbilities: [ModelAbility] {
        if let abilities = currentModel?.abilities, !abilities.isEmpty {
            return abilities
        }
        return ModelRegistry.shared.MODEL_ABILITIES.getData(modelId: currentModelId) as? [ModelAbility] ?? []
    }

    private var liveActivityPreferenceEnabled: Bool {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: IOSExecutionPreferenceKeys.liveActivity) != nil else { return true }
        return defaults.bool(forKey: IOSExecutionPreferenceKeys.liveActivity)
    }

    private var pendingAssistantRegeneration: PendingAssistantRegeneration?

    // MARK: - Init

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore = IOSSharedSettingsStore(),
        localToolExecutor: IOSLocalToolExecutor? = nil,
        searchTransport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport(),
        miniAppRepository: IOSMiniAppRepository? = nil,
        autoGenerateResponses: Bool = true,
        liveActivityController: AgentLiveActivityController? = nil
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.localToolExecutor = localToolExecutor
        self.searchTransport = searchTransport
        self.miniAppRepository = miniAppRepository ?? IOSMiniAppRepository.shared
        self.autoGenerateResponses = autoGenerateResponses
        self.liveActivityController = liveActivityController ?? .shared
    }

    private func makeGenerationCoordinator() -> ChatGenerationCoordinator {
        ChatGenerationCoordinator(
            dependencies: ChatGenerationDependencies(
                settingsStore: settingsStore,
                sharedSettings: sharedSettings,
                localToolExecutor: localToolExecutor,
                searchTransport: searchTransport,
                liveActivityController: liveActivityController,
                autoGenerateResponses: autoGenerateResponses,
                mcpManager: mcpManager
            ),
            bindings: ChatGenerationBindings(
                getMessages: { [weak self] in
                    self?.messages ?? []
                },
                setMessages: { [weak self] messages in
                    self?.messages = messages
                },
                bumpMessageRevision: { [weak self] in
                    self?.messageRevision &+= 1
                },
                setIsLoading: { [weak self] isLoading in
                    self?.isLoading = isLoading
                },
                setPendingMemoryApproval: { [weak self] request in
                    self?.pendingMemoryApproval = request
                },
                setPendingSearchApproval: { [weak self] request in
                    self?.pendingSearchApproval = request
                },
                setPendingWebMountApproval: { [weak self] request in
                    self?.pendingWebMountApproval = request
                },
                setPendingWorkspaceApproval: { [weak self] request in
                    self?.pendingWorkspaceApproval = request
                },
                setPendingMcpApproval: { [weak self] request in
                    self?.pendingMcpApproval = request
                },
                persistMessages: { [weak self] conversationId in
                    self?.persistMessages(conversationId: conversationId)
                },
                recordRun: { [weak self] runId, startedAt, status, inputDigest in
                    await self?.recordRun(
                        runId: runId,
                        startedAt: startedAt,
                        status: status,
                        inputDigest: inputDigest
                    )
                },
                startLiveActivity: { [weak self] runId, presentation in
                    self?.startLiveActivity(runId: runId, presentation: presentation)
                },
                saveMiniAppIfPresent: { [weak self] messages, conversationId in
                    self?.saveMiniAppIfPresent(in: messages, conversationId: conversationId)
                },
                messagesByInjectingRuntimeContext: { [weak self] messages in
                    self?.messagesByInjectingRuntimeContext(messages) ?? messages
                },
                userFacingGenerationError: { rawMessage, modelId in
                    ChatViewModel.userFacingGenerationError(rawMessage, modelId: modelId)
                }
            )
        )
    }

    // MARK: - Actions

    /// 从 store 的 currentConversation 灌入 messages（切换会话 / App 启动时调用）。
    /// 必须在主线程；调用方负责确保 store 已 bootstrap。
    func reloadFromStore() {
        guard let store = conversationStore else { return }
        messages = store.currentMessages
        messageRevision &+= 1
    }

    /// 把当前 messages 落盘（节流：只在流式结束/取消/切换时调，不在每个 chunk 调）。
    private func persistMessages(conversationId: KotlinUuid? = nil) {
        guard let store = conversationStore else { return }
        let snapshot = messages
        let targetConversationId = conversationId ?? store.currentConversation?.id
        let pendingRegeneration = pendingAssistantRegeneration
        Task { @MainActor in
            await persistMessagesSnapshot(
                snapshot,
                targetConversationId: targetConversationId,
                pendingRegeneration: pendingRegeneration,
                store: store
            )
        }
    }

    private func persistMessagesSnapshot(
        _ snapshot: [UIMessage],
        targetConversationId: KotlinUuid?,
        pendingRegeneration: PendingAssistantRegeneration?,
        store: IOSConversationStore
    ) async {
        if let pendingRegeneration,
           let targetConversationId,
           String(describing: targetConversationId) == String(describing: pendingRegeneration.conversationId) {
            if pendingRegeneration.generatedMessageIndex >= 0,
               pendingRegeneration.generatedMessageIndex < snapshot.count {
                let regenerated = snapshot[pendingRegeneration.generatedMessageIndex]
                if regenerated.role == MessageRole.assistant {
                    let saved = await store.appendVariantAndTruncateAfter(
                        messageIndex: pendingRegeneration.targetMessageIndex,
                        message: regenerated,
                        conversationId: pendingRegeneration.conversationId
                    )
                    if saved {
                        self.pendingAssistantRegeneration = nil
                        self.messages = store.currentMessages
                        self.messageRevision &+= 1
                        return
                    }
                }
            }
            self.pendingAssistantRegeneration = nil
            self.messages = store.currentMessages
            self.messageRevision &+= 1
            return
        }
        if let targetConversationId {
            await store.save(messages: snapshot, to: targetConversationId)
        } else {
            await store.saveCurrent(messages: snapshot)
        }
    }

    func sendMessage() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty,
              !generationCoordinator.isRunning,
              !isAttachingSelectedFile,
              pendingMemoryApproval == nil,
              pendingSearchApproval == nil,
              pendingWebMountApproval == nil,
              pendingWorkspaceApproval == nil,
              pendingMcpApproval == nil else { return }

        if autoGenerateResponses, let configurationIssue {
            configurationError = configurationIssue.message
            return
        }
        configurationError = nil

        let prompt = Self.promptText(userText: text, selectedFilePreview: pendingSelectedFilePreview)
        let digest = chatInputDigest(for: prompt)
        let userMsg = UIMessage.companion.user(prompt: prompt)
        pendingAssistantRegeneration = nil
        messages.append(userMsg)
        messageRevision &+= 1
        inputText = ""
        pendingSelectedFilePreview = nil
        selectedFileContextError = nil
        let runConversationId = currentConversationId
        // 用户消息立即落盘：即使随后生成崩溃/被杀进程，用户输入也不会丢。
        persistMessages(conversationId: runConversationId)
        guard autoGenerateResponses else { return }
        generateResponse(inputDigest: digest, conversationId: runConversationId)
    }

    func attachSelectedFilePreviewToNextMessage() async {
        guard !isAttachingSelectedFile else { return }
        guard let localToolExecutor else {
            selectedFileContextError = "Local iOS tool executor is unavailable."
            return
        }

        let requestId = UUID()
        attachRequestId = requestId
        isAttachingSelectedFile = true
        let activityRunId = "tool-\(requestId.uuidString)"
        startLiveActivity(runId: activityRunId, presentation: .readingSelectedFile)
        defer {
            if attachRequestId == requestId {
                isAttachingSelectedFile = false
                attachRequestId = nil
            }
        }

        let request = localToolExecutor.requestForCurrentSelectedFile(isUserInitiated: true)
        let output = await localToolExecutor.execute(request)
        guard attachRequestId == requestId else { return }
        switch output {
        case .selectedFilePreview(let result):
            pendingSelectedFilePreview = result
            selectedFileContextError = nil
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadCompleted,
                dismissalDelay: 4
            )
        case .needsUserAction(let reason):
            selectedFileContextError = reason
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadWaitingForUser,
                dismissalDelay: 6
            )
        case .denied(let reason):
            selectedFileContextError = reason
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        case .failed(let message):
            selectedFileContextError = message
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        case .permissionsStatus:
            selectedFileContextError = "permissions_status cannot be attached to a chat message."
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        case .webMountResult:
            selectedFileContextError = "WebMount tool output cannot be attached to a chat message."
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        case .workspaceResult:
            selectedFileContextError = "Workspace tool output cannot be attached to a chat message."
            await liveActivityController.end(
                runId: activityRunId,
                presentation: .selectedFileReadFailed,
                dismissalDelay: 6
            )
        }
    }

    func clearPendingSelectedFilePreview() {
        pendingSelectedFilePreview = nil
        selectedFileContextError = nil
    }

    func approvePendingMemoryTool() {
        generationCoordinator.approvePendingMemoryTool()
    }

    func denyPendingMemoryTool() {
        generationCoordinator.denyPendingMemoryTool()
    }

    func approvePendingSearchTool() {
        Task { @MainActor in
            await generationCoordinator.approvePendingSearchTool()
        }
    }

    func denyPendingSearchTool() {
        Task { @MainActor in
            await generationCoordinator.denyPendingSearchTool()
        }
    }

    func approvePendingWebMountTool() {
        Task { @MainActor in
            await generationCoordinator.approvePendingWebMountTool()
        }
    }

    func denyPendingWebMountTool() {
        Task { @MainActor in
            await generationCoordinator.denyPendingWebMountTool()
        }
    }

    func approvePendingWorkspaceTool() {
        Task { @MainActor in
            await generationCoordinator.approvePendingWorkspaceTool()
        }
    }

    func denyPendingWorkspaceTool() {
        Task { @MainActor in
            await generationCoordinator.denyPendingWorkspaceTool()
        }
    }

    func approvePendingMcpTool() {
        Task { @MainActor in
            await generationCoordinator.approvePendingMcpTool()
        }
    }

    func denyPendingMcpTool() {
        Task { @MainActor in
            await generationCoordinator.denyPendingMcpTool()
        }
    }

    func cancelGeneration() {
        generationCoordinator.cancel()
    }

    // MARK: - Message branching actions (Android ChatService parity)
    //
    // These operate on the Conversation tree via IOSConversationStore. After
    // mutating the tree they re-sync `self.messages` (the flat currentMessages
    // projection) so the chat list reflects the new branch, then re-run
    // generation for regenerate. Mirrors Android ChatService.regenerateAtMessage
    // / editMessage.

    /// Regenerate the assistant reply that follows a given message index.
    /// - If the target is a USER message: drop everything after it and re-run
    ///   generation (Android's USER-regenerate path).
    /// - If the target is an ASSISTANT message: drop it + everything after, then
    ///   re-run from the preceding user turn (produces a fresh variant; the old
    ///   reply is replaced in this minimal implementation — full variant
    ///   retention is surfaced via selectVariant for nodes that already have
    ///   multiple siblings).
    func regenerate(atMessageIndex index: Int) {
        guard !isGenerationActive else { return }
        guard let store = conversationStore,
              let conversation = store.currentConversation,
              index >= 0, index < conversation.messageNodes.count else { return }

        let targetNode = conversation.messageNodes[index]
        if targetNode.role == MessageRole.user {
            Task { @MainActor in
                pendingAssistantRegeneration = nil
                await store.truncateAfter(messageIndex: index)
                // Re-sync the flat projection from the mutated tree.
                if let updated = store.currentConversation {
                    self.messages = updated.currentMessages
                    self.messageRevision &+= 1
                }
                let digest = chatInputDigest(for: regenerateDigestSeed())
                generateResponse(inputDigest: digest, conversationId: currentConversationId)
            }
        } else {
            // Assistant: regenerate from the user turn immediately before it.
            // Keep the original assistant node in storage. The new assistant
            // turn is appended as a variant after streaming completes.
            // messageNodes is a Kotlin List — convert to Swift Array so we can
            // use Swift's lastIndex(where:) on the prefix.
            let nodes = Array(conversation.messageNodes.prefix(index))
            guard let precedingUser = nodes.lastIndex(where: { $0.role == MessageRole.user }) else {
                return
            }
            Task { @MainActor in
                let uploadMessages = Array(conversation.currentMessages.prefix(precedingUser + 1))
                self.pendingAssistantRegeneration = PendingAssistantRegeneration(
                    conversationId: conversation.id,
                    targetMessageIndex: index,
                    generatedMessageIndex: uploadMessages.count
                )
                self.messages = uploadMessages
                self.messageRevision &+= 1
                let digest = chatInputDigest(for: regenerateDigestSeed())
                generateResponse(inputDigest: digest, conversationId: conversation.id)
            }
        }
    }

    /// Edit a user message in place and re-run generation from it. The edited
    /// text replaces the selected variant; the conversation is truncated after
    /// the edited user turn (Android's editMessage = append-variant + re-run).
    func editMessage(atMessageIndex index: Int, newText: String) {
        guard !isGenerationActive else { return }
        let trimmed = newText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard let store = conversationStore,
              let conversation = store.currentConversation,
              index >= 0, index < conversation.messageNodes.count else { return }

        let node = conversation.messageNodes[index]
        guard node.role == MessageRole.user else { return }

        let edited = UIMessage.companion.user(prompt: trimmed)
        Task { @MainActor in
            pendingAssistantRegeneration = nil
            await store.appendVariant(messageIndex: index, message: edited)
            // Truncate anything after the edited user turn (drop stale reply).
            await store.truncateAfter(messageIndex: index)
            if let updated = store.currentConversation {
                self.messages = updated.currentMessages
                self.messageRevision &+= 1
                self.persistMessages(conversationId: currentConversationId)
            }
            let digest = chatInputDigest(for: trimmed)
            generateResponse(inputDigest: digest, conversationId: currentConversationId)
        }
    }

    /// Delete a single message (and its node). No generation.
    func deleteMessage(atMessageIndex index: Int) {
        guard !isGenerationActive, let store = conversationStore else { return }
        Task { @MainActor in
            await store.deleteMessage(messageIndex: index)
            if let updated = store.currentConversation {
                self.messages = updated.currentMessages
                self.messageRevision &+= 1
            }
            self.persistMessages(conversationId: currentConversationId)
        }
    }

    /// Switch the visible variant of a node (no generation).
    func selectVariant(messageIndex: Int, variantIndex: Int) {
        guard !isGenerationActive, let store = conversationStore else { return }
        Task { @MainActor in
            await store.selectVariant(messageIndex: messageIndex, variantIndex: variantIndex)
            if let updated = store.currentConversation {
                self.messages = updated.currentMessages
                self.messageRevision &+= 1
            }
        }
    }

    /// Variant info for the UI (to render `< n/m >` when a node has siblings).
    func variantInfo(atMessageIndex index: Int) -> IOSConversationStore.VariantInfo? {
        conversationStore?.variantInfo(forMessageIndex: index)
    }

    private func regenerateDigestSeed() -> String {
        messages.last(where: { $0.role == MessageRole.user })?.toText() ?? ""
    }

    // MARK: - Private

    private func generateResponse(inputDigest: String, conversationId: KotlinUuid?) {
        if generationCoordinator.isRunning {
            cancelGeneration()
        }
        isLoading = true

        let providerSetting = makeProviderSetting()
        let params = makeTextGenerationParams()

        guard let resolvedProvider = providerSetting else {
            isLoading = false
            configurationError = configurationIssue?.message ?? ChatConfigurationIssue.missingProvider.message
            return
        }

        generationCoordinator.start(
            providerSetting: resolvedProvider,
            params: params,
            inputDigest: inputDigest,
            conversationId: conversationId,
            uploadMessages: messages
        )
    }

    private func messagesByInjectingRuntimeContext(_ messages: [UIMessage]) -> [UIMessage] {
        ChatRuntimeContextBuilder(
            sharedSettings: sharedSettings,
            mcpTools: mcpManager.tools,
            miniAppRepository: miniAppRepository,
            miniAppRuntimeEnabled: isMiniAppRuntimeEnabled
        ).injectingRuntimeContext(into: messages)
    }

#if DEBUG
    func preparedUploadMessagesForTesting(_ messages: [UIMessage]) -> [UIMessage] {
        messagesByInjectingRuntimeContext(messages)
    }
#endif

    private func saveMiniAppIfPresent(in messages: [UIMessage], conversationId: KotlinUuid?) -> UIMessage? {
        guard isMiniAppRuntimeEnabled else { return nil }
        guard let lastUser = messages.last(where: { $0.role == MessageRole.user }),
              let userText = ChatRuntimeContextBuilder.messageText(lastUser),
              IOSMiniAppOutputParser.isExplicitMiniAppRequest(userText),
              let assistant = messages.last(where: { $0.role == MessageRole.assistant }) else {
            return nil
        }
        let assistantText = ChatRuntimeContextBuilder.messageText(assistant) ?? ""
        guard let output = IOSMiniAppOutputParser().parseOrNull(assistantText) else { return nil }

        do {
            let sourceConversationId = conversationId.map { String(describing: $0) }
            let sourceMessageId = String(describing: assistant.id)
            let record: IOSMiniAppRecord
            if let targetAppId = ChatRuntimeContextBuilder.revisionAppId(in: userText) {
                guard let updated = try miniAppRepository.saveRevision(
                    appId: targetAppId,
                    output: output,
                    expectedBaseVersion: ChatRuntimeContextBuilder.revisionVersion(in: userText),
                    sourceMessageId: sourceMessageId,
                    changeNote: "Generated from chat"
                ) else {
                    throw IOSMiniAppStoreError.notFound(targetAppId)
                }
                record = updated
            } else {
                record = try miniAppRepository.saveGenerated(
                    output,
                    sourceConversationId: sourceConversationId,
                    sourceMessageId: sourceMessageId
                )
            }
            _ = try? IOSWorkspaceStore.shared.saveArtifact(
                title: record.title,
                content: record.htmlContent,
                type: .miniApp,
                sourceKind: "miniapp",
                sourceId: record.id
            )
            let notice = """
            已保存 MiniApp「\(record.title)」v\(record.version)。
            appId: \(record.id)
            可在小应用列表中打开并管理版本、grant 和运行状态。
            """
            return UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: notice, metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: chatNowLocalDateTime(),
                modelId: nil,
                usage: nil,
                translation: nil
            )
        } catch {
            let notice = "MiniApp 输出解析成功，但保存失败：\((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)"
            return UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: notice, metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: chatNowLocalDateTime(),
                modelId: nil,
                usage: nil,
                translation: nil
            )
        }
    }

#if DEBUG
    func finishedToolCallMessagesForTesting(
        _ targetToolCall: UIMessagePart.Tool,
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        generationCoordinator.finishedToolCallMessagesForTesting(
            targetToolCall,
            outputText: outputText,
            in: messages
        )
    }

    func memoryToolOutputForTesting(input: String) -> String {
        generationCoordinator.memoryToolOutputForTesting(input: input)
    }

    func memoryApprovalRequestForTesting(input: String) -> MemoryToolApprovalRequest? {
        generationCoordinator.memoryApprovalRequestForTesting(input: input)
    }

    func memoryToolApprovalOutputForTesting(input: String, allow: Bool) -> String {
        generationCoordinator.memoryToolApprovalOutputForTesting(input: input, allow: allow)
    }

    func webMountToolOutputForTesting(
        toolName: String,
        input: String,
        isUserInitiated: Bool = false
    ) async -> String {
        await generationCoordinator.webMountToolOutputForTesting(
            toolName: toolName,
            input: input,
            isUserInitiated: isUserInitiated
        )
    }

    func webMountApprovalRequestForTesting(
        toolName: String,
        input: String
    ) async -> WebMountToolApprovalRequest? {
        await generationCoordinator.webMountApprovalRequestForTesting(toolName: toolName, input: input)
    }

    func webMountToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        await generationCoordinator.webMountToolApprovalOutputForTesting(
            toolName: toolName,
            input: input,
            allow: allow
        )
    }

    func searchApprovalRequestForTesting(
        toolName: String,
        input: String
    ) -> SearchToolApprovalRequest? {
        generationCoordinator.searchApprovalRequestForTesting(toolName: toolName, input: input)
    }

    func searchToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        await generationCoordinator.searchToolApprovalOutputForTesting(
            toolName: toolName,
            input: input,
            allow: allow
        )
    }
#endif

    private func startLiveActivity(runId: String, presentation: AgentActivityPresentation) {
        guard liveActivityPreferenceEnabled else { return }
        liveActivityController.start(runId: runId, presentation: presentation)
    }

    static func promptText(
        userText: String,
        selectedFilePreview: SelectedDocumentReadResult?
    ) -> String {
        guard let selectedFilePreview else { return userText }
        let status = selectedFilePreview.statusSummary
        return """
        \(userText)

        [文件上下文]
        来源文件：\(selectedFilePreview.fileName)
        类型：\(selectedFilePreview.fileType)
        大小：\(selectedFilePreview.totalBytes) bytes
        已读取：\(selectedFilePreview.bytesRead) bytes
        文本字符：\(selectedFilePreview.characterCount)
        状态：\(status)
        内容：
        \(selectedFilePreview.preview)
        [/文件上下文]
        """
    }

    private func recordRun(
        runId: String,
        startedAt: Int64,
        status: String,
        inputDigest: String
    ) async {
        let dao = db.agentRuntimeDao()

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // A "running" record is the up-front, not-yet-finished row written at
        // start, so an interrupted mid-stream run is detectable by the recovery
        // sweep; it carries no finishedAt. Terminal statuses finish the run and,
        // because insertRun is OnConflict.REPLACE, overwrite the running row.
        let finishedAtValue: KotlinLong? = status == "running" ? nil : KotlinLong(value: now)
        let interruptedReason: String? = status == "interrupted" ? "user_cancelled" : nil

        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: nil,
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
            print("[Room] Failed to insert agent_run: \(error)")
        }
    }

    /// Resolve the provider for the current chat model, Android-style:
    ///   current chat model  ->  model.findProvider(settings.providers)
    /// The resolved ProviderSetting carries its own apiKey/baseUrl (the key now
    /// lives inside the provider, not in an iOS per-provider Keychain slot).
    /// Returns nil when the current model can't be resolved to a provider
    /// (e.g. fresh install with no configured provider/model) — the caller
    /// surfaces an honest configuration issue rather than faking an OpenAI one.
    private func makeProviderSetting() -> ProviderSetting? {
        guard let currentModel else { return nil }
        guard let provider = ChatProviderConfiguration.provider(
            for: currentModel,
            providers: sharedSettings.snapshot.providers
        ), ChatProviderConfiguration.supportsChatStreaming(provider) else { return nil }
        return provider
    }

    private func makeTextGenerationParams() -> TextGenerationParams {
        let modelId = currentModelId
        let modelAbilities = currentModelAbilities
        let searchEnabled = sharedSettings.snapshot.enableWebSearch
        let imageGenerationConfigured = IOSImageGenerationSettingsStore.shared.configurationIssue(settingsStore: settingsStore) == nil
        var builtInTools: [BuiltInTools] = []
        if searchEnabled { builtInTools.append(BuiltInTools.Search.shared) }
        if imageGenerationConfigured { builtInTools.append(BuiltInTools.ImageGeneration.shared) }

        // Real Android parity: read generation params from the current Assistant
        // + Model instead of hardcoding temperature=0.7/topP=nil/maxTokens=nil.
        // Mirrors GenerationHandler.kt:453-468 + resolveSessionDefaults.
        let snapshot = sharedSettings.snapshot
        let assistant = snapshot.getCurrentAssistant()
        let currentModel = snapshot.getCurrentChatModel()
        let contextWindow = currentModel?.contextWindowTokens
        // resolveSessionDefaults handles: reasoningLevel AUTO→model default,
        // contextMessageSize 0→group default, maxTokens→group default fallback.
        // It is a Settings extension fun; the fallback model avoids a nil model.
        // Model requires its full initializer (Kotlin default args don't bridge).
        let resolvedModel = currentModel ?? Model(
            modelId: modelId,
            displayName: modelId,
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        let resolved = snapshot.resolveSessionDefaults(assistant: assistant, model: resolvedModel)

        // Merge custom headers/bodies: Assistant's + Model's (Android merges both).
        let mergedHeaders: [CustomHeader] = assistant.customHeaders + (currentModel?.customHeaders ?? [])
        let mergedBodies: [CustomBody] = assistant.customBodies + (currentModel?.customBodies ?? [])

        let model = Model(
            modelId: modelId,
            displayName: currentModel?.displayName ?? modelId,
            id: currentModel?.id ?? KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: mergedHeaders,
            customBodies: mergedBodies,
            inputModalities: [],
            outputModalities: [],
            abilities: modelAbilities,
            tools: Set(builtInTools),
            contextWindowTokens: contextWindow,
            providerOverwrite: currentModel?.providerOverwrite
        )
        // Tool declarations: iOS search/scrape, memory, WebMount, MCP,
        // sub-agent dispatch, and model-council run. The model decides which to
        // call; the iOS onComplete dispatch routes each to its executor.
        var toolDeclarations: [Tool] = []
        if searchEnabled {
            toolDeclarations.append(ToolKt.createSearchWebToolDeclaration())
            toolDeclarations.append(ToolKt.createScrapeWebToolDeclaration())
        }
        if IOSMemoryToolExecutor.isEnabled(runtime: sharedSettings.agentRuntime) {
            toolDeclarations.append(ToolKt.createMemoryToolDeclaration())
        }
        if imageGenerationConfigured {
            toolDeclarations.append(ToolKt.createImageGenToolDeclaration())
        }
        if localToolExecutor != nil {
            toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(
                names: workspaceToolNamesForCurrentTurn()
            ))
        }
        if localToolExecutor != nil, isWebMountRuntimeEnabled {
            toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(
                names: Array(IOSWebMountToolCatalog.supportedToolNames).sorted()
            ))
        }
        if sharedSettings.isCapabilityGateEnabled(.mcp) {
            toolDeclarations.append(ToolKt.createMcpCallToolDeclaration())
        }
        if isSlice3ToolEnabled("subagent_dispatch") {
            toolDeclarations.append(ToolKt.createSubAgentDispatchToolDeclaration())
        }
        if isSlice3ToolEnabled("model_council_run") {
            toolDeclarations.append(ToolKt.createModelCouncilRunToolDeclaration())
        }
        // Real params: temperature/topP from Assistant, maxTokens from
        // resolveSessionDefaults (Assistant → group default), reasoningLevel
        // resolved, custom headers/bodies merged. Mirrors GenerationHandler.
        let resolvedReasoningLevel = modelAbilities.contains(.reasoning) ? resolved.reasoningLevel : ReasoningLevel.off
        return TextGenerationParams(
            model: model,
            temperature: assistant.temperature.map { KotlinFloat(value: Float(truncating: $0)) },
            topP: assistant.topP.map { KotlinFloat(value: Float(truncating: $0)) },
            maxTokens: resolved.maxTokens.map { KotlinInt(value: Int32(truncating: $0)) },
            tools: toolDeclarations,
            reasoningLevel: resolvedReasoningLevel,
            customHeaders: mergedHeaders,
            customBody: mergedBodies
        )
    }

    func currentToolDeclarationNames() -> [String] {
        makeTextGenerationParams().tools.map(\.name)
    }

    private func workspaceToolNamesForCurrentTurn() -> [String] {
        let names = latestUserMessageRequestsWorkspaceWrite()
            ? IOSWorkspaceToolCatalog.supportedToolNames
            : IOSWorkspaceToolCatalog.readToolNames
        return Array(names).sorted()
    }

    private func latestUserMessageRequestsWorkspaceWrite() -> Bool {
        guard let text = messages.reversed()
            .first(where: { $0.role == MessageRole.user })?
            .toText()
            .lowercased()
        else { return false }

        let hasWriteAction = [
            "保存", "存到", "存入", "写入", "新建", "创建", "生成文件", "导出",
            "修改", "编辑", "删除", "移动", "重命名", "落盘",
            "save", "write", "create", "export", "edit", "delete", "move", "rename"
        ].contains { text.contains($0) }
        guard hasWriteAction else { return false }

        return [
            "workspace", "工作区", "/workspace", "文件", "文档",
            ".md", ".txt", ".json", ".html", ".csv"
        ].contains { text.contains($0) }
    }

    #if DEBUG
    /// Test accessor for the resolved generation params (reads real
    /// Assistant/Model values + resolveSessionDefaults).
    func textGenerationParamsForTesting() -> TextGenerationParams {
        makeTextGenerationParams()
    }

    func persistPendingAssistantRegenerationForTesting(
        conversationId: KotlinUuid,
        targetMessageIndex: Int,
        generatedMessageIndex: Int,
        snapshot: [UIMessage]
    ) async {
        guard let store = conversationStore else { return }
        let pending = PendingAssistantRegeneration(
            conversationId: conversationId,
            targetMessageIndex: targetMessageIndex,
            generatedMessageIndex: generatedMessageIndex
        )
        pendingAssistantRegeneration = pending
        messages = snapshot
        await persistMessagesSnapshot(
            snapshot,
            targetConversationId: conversationId,
            pendingRegeneration: pending,
            store: store
        )
    }
    #endif

    static func userFacingGenerationError(_ rawMessage: String, modelId: String? = nil) -> String {
        let raw = rawMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowercased = raw.lowercased()
        let prefix: String
        if lowercased.contains("invalid api key") ||
            lowercased.contains("incorrect api key") ||
            lowercased.contains("unauthorized") ||
            lowercased.contains("401") {
            prefix = "API Key 无效或没有权限。请回到「服务商」确认 Key 是否填写正确，并确认它有访问当前服务商的权限。"
        } else if lowercased.contains("model_not_found") ||
                    lowercased.contains("model not found") ||
                    lowercased.contains("does not exist") ||
                    lowercased.contains("not found") ||
                    lowercased.contains("404") {
            let trimmedModelId = modelId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let suffix = trimmedModelId.isEmpty ? "" : "当前 Model ID：\(trimmedModelId)。"
            prefix = "模型不可用、模型不存在，或当前 Base URL 不支持这个聊天路径。请在「默认模型」选择当前服务商支持的模型。\(suffix)"
        } else if lowercased.contains("network") ||
                    lowercased.contains("internet") ||
                    lowercased.contains("offline") ||
                    lowercased.contains("timed out") ||
                    lowercased.contains("timeout") ||
                    lowercased.contains("cannot connect") ||
                    lowercased.contains("could not connect") ||
                    lowercased.contains("dns") ||
                    lowercased.contains("connection refused") ||
                    lowercased.contains("nsurlerror") {
            prefix = "网络连接失败。请检查网络、Base URL 和服务商状态后重试。"
        } else {
            prefix = "请求失败。请检查服务商配置后重试。"
        }

        guard !raw.isEmpty else { return prefix }
        return "\(prefix)\n\n原始错误：\(truncatedError(raw))"
    }

    private static func truncatedError(_ value: String, maxLength: Int = 260) -> String {
        guard value.count > maxLength else { return value }
        return String(value.prefix(maxLength)) + "..."
    }

    private var isMiniAppRuntimeEnabled: Bool {
        true
    }

    private var isWebMountRuntimeEnabled: Bool {
        true
    }

    private func isSlice3ToolEnabled(_ toolName: String) -> Bool {
        switch toolName {
        case "mcp_call":
            sharedSettings.isCapabilityGateEnabled(.mcp)
        case "subagent_dispatch":
            isCapabilityPolicyEnabled("ios.agent.subagent_dispatch")
        case "model_council_run":
            isCapabilityPolicyEnabled("ios.agent.model_council_run")
        default:
            false
        }
    }

    private func isCapabilityPolicyEnabled(_ capabilityId: String) -> Bool {
        guard let localToolExecutor else { return true }
        let snapshot = localToolExecutor.permissionsStatus()
        return snapshot.capabilities.first { $0.id == capabilityId }?.policy != IOSAgentPermissionPolicy.disabled.title
    }

}
