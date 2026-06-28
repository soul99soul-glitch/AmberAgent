import Foundation
import Observation
import SwiftUI
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
    /// 当前模型的真实上下文窗口(token)。模型未声明时为 nil。
    let contextWindowTokens: Int?
    /// 当前上下文实际占用(token):取最近一轮的 promptTokens + completionTokens。
    /// 每轮的 promptTokens 已含到该轮为止的全部历史,所以这就是"现在窗口里有多少",
    /// 用它驱动用量环 —— 不能用 totalTokens(那是逐轮累加和,会严重重复计数)。
    let currentContextTokens: Int
}

enum ChatContextCompactStatus: Equatable {
    case idle
    case planning
    case compacting
    case completed
    case failed
}

struct ChatContextCompactState: Equatable {
    var status: ChatContextCompactStatus
    var summary: String
    var updatedAt: Date

    static let idle = ChatContextCompactState(status: .idle, summary: "", updatedAt: Date.distantPast)

    var isActive: Bool {
        status == .planning || status == .compacting
    }

    var isVisible: Bool {
        isActive || status == .completed || status == .failed
    }
}

extension ChatContextSnapshot {
    /// 未声明上下文窗口的模型的默认兜底(token)。当下主流模型基本都 ≥100万,
    /// 几乎不存在比 20万更小的,故用 20万作保守默认 —— 比旧的 8K 合理得多。
    static let defaultContextWindowTokens: Double = 200_000

    /// 上下文用量比例 [0,1],驱动用量环与上下文面板的填充。分子是"当前占用"
    /// (currentContextTokens,非累加和);分母优先用模型真实 contextWindow,模型未声明
    /// 窗口时回退到 defaultContextWindowTokens(20万)。
    var contextFillFraction: CGFloat {
        let ceiling = contextWindowTokens.flatMap { $0 > 0 ? Double($0) : nil } ?? Self.defaultContextWindowTokens
        return min(CGFloat(Double(currentContextTokens) / ceiling), 1.0)
    }
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
    /// 助手回复完成后用 suggestionModelId 生成的对话建议(输入框上方胶囊)。
    /// 用户发送或切换会话时清空。
    var chatSuggestions: [String] = []
    var isAttachingSelectedFile: Bool = false
    var pendingSelectedFilePreview: SelectedDocumentReadResult?
    var pendingImages: [PendingChatImage] = []
    var isRecognizingImages = false
    var selectedFileContextError: String?
    var reasoningLevel: ReasoningLevel = .off
    var messageRevision: Int = 0
    var pendingMemoryApproval: MemoryToolApprovalRequest?
    var pendingSearchApproval: SearchToolApprovalRequest?
    var pendingWebMountApproval: WebMountToolApprovalRequest?
    var pendingWorkspaceApproval: WorkspaceToolApprovalRequest?
    var pendingIshHandoffApproval: IshHandoffToolApprovalRequest?
    var pendingMcpApproval: McpToolApprovalRequest?
    var configurationError: String?
    var contextCompactState: ChatContextCompactState = .idle

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
        // 最近一轮的占用(promptTokens 已含全部历史);遍历中后者覆盖前者,留下最后一条。
        var latestContextTokens = 0
        for message in messages {
            guard let usage = message.usage else { continue }
            prompt += Int(usage.promptTokens)
            completion += Int(usage.completionTokens)
            cached += Int(usage.cachedTokens)
            latestContextTokens = Int(usage.promptTokens) + Int(usage.completionTokens)
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
            tokensPerSecond: generationDuration > 0 ? Double(timedCompletionTokens) / generationDuration : nil,
            contextWindowTokens: currentModel?.contextWindowTokens.map { Int(truncating: $0) },
            currentContextTokens: latestContextTokens
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
        sharedSettings.currentAssistantReasoningLevels().contains { $0 != .off }
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
                    guard let self else { return }
                    let wasLoading = self.isLoading
                    self.isLoading = isLoading
                    // 生成由「运行中」转为「空闲」即视为本轮完成 → 触发标题/建议辅助生成。
                    if wasLoading && !isLoading {
                        self.onGenerationCompleted()
                    }
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
                setPendingIshHandoffApproval: { [weak self] request in
                    self?.pendingIshHandoffApproval = request
                },
                setPendingMcpApproval: { [weak self] request in
                    self?.pendingMcpApproval = request
                },
                setContextCompactState: { [weak self] state in
                    withAnimation(.easeOut(duration: 0.22)) {
                        self?.contextCompactState = state
                    }
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
        contextCompactState = .idle
        messageRevision &+= 1
        chatSuggestions = []
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
        let hasImages = !pendingImages.isEmpty
        guard (!text.isEmpty || hasImages),
              !generationCoordinator.isRunning,
              !isAttachingSelectedFile,
              !isRecognizingImages,
              pendingMemoryApproval == nil,
              pendingSearchApproval == nil,
              pendingWebMountApproval == nil,
              pendingWorkspaceApproval == nil,
              pendingIshHandoffApproval == nil,
              pendingMcpApproval == nil else { return }

        if autoGenerateResponses, let configurationIssue {
            configurationError = configurationIssue.message
            return
        }
        // 发图前先按模型视觉能力判断（对齐安卓 ImageAttachmentValidator）：
        // ready→直接发；fallback→先用视觉模型识别成文字再发；blocked→拦下提示，绝不静默丢弃。
        if hasImages {
            switch imageAttachmentState {
            case .blocked(let reason):
                selectedFileContextError = reason
                return
            case .fallback:
                configurationError = nil
                startVisionFallbackAndSend(text: text, images: pendingImages)
                return
            case .ready, .none:
                break
            }
        }
        configurationError = nil
        sendUserMessage(text: text, images: pendingImages)
    }

    func modifyGeneratedImage(sourceImageURL: String, prompt: String, aspectRatio: String) {
        let trimmedPrompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedSource = sourceImageURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedPrompt.isEmpty,
              !trimmedSource.isEmpty,
              !generationCoordinator.isRunning else { return }

        configurationError = nil
        let input = Self.imageEditToolInput(
            prompt: trimmedPrompt,
            sourceImageURL: trimmedSource,
            aspectRatio: aspectRatio
        )
        generationCoordinator.runImageTool(input: input, conversationId: currentConversationId)
    }

    /// Appends the user message (keeping image parts for in-bubble display) and persists it.
    /// Returns the input digest + conversation id so the caller can start generation.
    @discardableResult
    private func appendUserMessage(text: String, images: [PendingChatImage]) -> (digest: String, conversationId: KotlinUuid?) {
        let prompt = Self.promptText(userText: text, selectedFilePreview: pendingSelectedFilePreview)
        let digest = chatInputDigest(for: prompt.isEmpty ? "[image]" : prompt)
        let userMsg = makeUserMessage(prompt: prompt, images: images)
        pendingAssistantRegeneration = nil
        // iMessage 式上屏:仅把这条用户消息的插入放进动画事务,驱动消息行的入场 transition。
        // 批量加载/切换会话走 `messages = store.currentMessages`(不在事务内),不会逐条动画。
        withAnimation(.spring(response: 0.34, dampingFraction: 0.8)) {
            messages.append(userMsg)
        }
        messageRevision &+= 1
        inputText = ""
        chatSuggestions = []
        pendingSelectedFilePreview = nil
        clearPendingImages()
        selectedFileContextError = nil
        let runConversationId = currentConversationId
        // 用户消息立即落盘：即使随后生成崩溃/被杀进程，用户输入也不会丢。
        persistMessages(conversationId: runConversationId)
        return (digest, runConversationId)
    }

    /// Sends the user message and kicks off generation. For non-vision models the image
    /// parts are swapped for the cached recognition text inside
    /// `messagesByInjectingRuntimeContext` before the request leaves for the provider.
    private func sendUserMessage(text: String, images: [PendingChatImage]) {
        let (digest, conversationId) = appendUserMessage(text: text, images: images)
        guard autoGenerateResponses else { return }
        generateResponse(inputDigest: digest, conversationId: conversationId)
    }

    private static func imageEditToolInput(
        prompt: String,
        sourceImageURL: String,
        aspectRatio: String
    ) -> String {
        let payload: [String: Any] = [
            "prompt": prompt,
            "aspect_ratio": aspectRatio,
            "count": 1,
            "mode": "edit",
            "source_image_url": sourceImageURL,
        ]
        guard let data = try? JSONSerialization.data(
            withJSONObject: payload,
            options: [.sortedKeys, .withoutEscapingSlashes]
        ),
        let text = String(data: data, encoding: .utf8) else {
            return prompt
        }
        return text
    }

    /// OCR fallback: the user message (with its image) is shown in the chat immediately;
    /// a recognition indicator runs on the user side while the vision model reads each
    /// image, then the main model responds. On failure the message stays and an error shows.
    private func startVisionFallbackAndSend(text: String, images: [PendingChatImage]) {
        let (digest, conversationId) = appendUserMessage(text: text, images: images)
        guard autoGenerateResponses else { return }
        isRecognizingImages = true
        Task {
            let result = await performVisionRecognition(images: images)
            await MainActor.run {
                isRecognizingImages = false
                switch result {
                case .failure(let error):
                    selectedFileContextError = error.message
                case .success(let texts):
                    for (key, value) in texts { visionRecognitionTexts[key] = value }
                    generateResponse(inputDigest: digest, conversationId: conversationId)
                }
            }
        }
    }

    struct VisionRecognitionError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// Calls the configured vision model once per image and returns the wrapped
    /// recognition text keyed by the image's `data:` URL (Android OcrTransformer parity).
    private func performVisionRecognition(
        images: [PendingChatImage]
    ) async -> Result<[String: String], VisionRecognitionError> {
        let snapshot = sharedSettings.snapshot
        guard let visionModel = snapshot.findModelById(uuid: snapshot.ocrModelId) else {
            return .failure(VisionRecognitionError(message: "请先在「默认模型 → 辅助任务」配置视觉识别模型"))
        }
        guard let providerSetting = ChatProviderConfiguration.provider(
            for: visionModel,
            providers: snapshot.providers
        ) else {
            return .failure(VisionRecognitionError(message: "视觉识别模型的服务商不可用"))
        }
        let prompt = OcrPromptKt.resolveVisionRecognitionPrompt(prompt: snapshot.ocrPrompt)
        let params = TextGenerationParams(
            model: visionModel,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.off,
            customHeaders: [],
            customBody: []
        )
        let adapter = OpenAIKmpProviderAdapter()
        var results: [String: String] = [:]
        for image in images {
            if let cached = visionRecognitionTexts[image.dataUrl] {
                results[image.dataUrl] = cached
                continue
            }
            let requestMessages = [
                UIMessage.companion.system(prompt: prompt),
                makeImageOnlyUserMessage(dataUrl: image.dataUrl)
            ]
            do {
                let chunk = try await adapter.generateText(
                    providerSetting: providerSetting,
                    messages: requestMessages,
                    params: params
                )
                let text = chunk.choices.first?.message?.toText()
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                guard !text.isEmpty else {
                    return .failure(VisionRecognitionError(message: "视觉识别模型没有返回可用内容"))
                }
                results[image.dataUrl] = """
                <image_context>
                \(text)
                </image_context>
                * 以上 image_context 是对用户上传图片的视觉识别结果，不是用户的提问。
                """
            } catch {
                return .failure(VisionRecognitionError(message: "视觉识别失败：\((error as NSError).localizedDescription)"))
            }
        }
        return .success(results)
    }

    // MARK: - Auxiliary generation (title + chat suggestions)

    /// 一轮生成完成后触发:总是尝试生成对话建议;首轮(仅 1 条用户消息)再生成标题。
    /// 工具审批暂停期间(有 pending approval)不触发;最后一条非助手消息也不触发。
    private func onGenerationCompleted() {
        guard pendingMemoryApproval == nil, pendingSearchApproval == nil,
              pendingWebMountApproval == nil, pendingWorkspaceApproval == nil,
              pendingIshHandoffApproval == nil,
              pendingMcpApproval == nil else { return }
        guard let last = messages.last, last.role == MessageRole.assistant,
              !last.toText().trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        generateChatSuggestions()
        let userMessageCount = messages.filter { $0.role == MessageRole.user }.count
        if userMessageCount == 1 { generateConversationTitle() }
    }

    /// 辅助模型:优先用指定的辅助模型,未设置时回退当前聊天模型(对齐 Android resolveTaskChatModel)。
    private func resolveAuxModel(_ auxId: KotlinUuid) -> Model? {
        sharedSettings.snapshot.findModelById(uuid: auxId) ?? sharedSettings.snapshot.getCurrentChatModel()
    }

    /// 最近 [maxMessages] 条消息拼成带角色前缀的文本,填入提示词的 {content}。
    private func auxConversationText(maxMessages: Int) -> String {
        messages.suffix(maxMessages).compactMap { message -> String? in
            let text = message.toText().trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            let role: String
            switch message.role {
            case MessageRole.user: role = "User"
            case MessageRole.assistant: role = "Assistant"
            default: role = "System"
            }
            return "\(role): \(text)"
        }.joined(separator: "\n\n")
    }

    private func auxLocaleName() -> String {
        Locale.current.localizedString(forIdentifier: Locale.current.identifier) ?? Locale.current.identifier
    }

    /// 用辅助模型跑一次单轮文本生成(OpenAIKmpProviderAdapter 内部按服务商类型分发 OpenAI/Claude)。
    private func runAuxModel(model: Model, prompt: String) async -> String? {
        guard let provider = ChatProviderConfiguration.provider(
            for: model,
            providers: sharedSettings.snapshot.providers
        ) else { return nil }
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.off,
            customHeaders: [],
            customBody: []
        )
        do {
            let chunk = try await OpenAIKmpProviderAdapter().generateText(
                providerSetting: provider,
                messages: [UIMessage.companion.user(prompt: prompt)],
                params: params
            )
            return chunk.choices.first?.message?.toText().trimmingCharacters(in: .whitespacesAndNewlines)
        } catch {
            return nil
        }
    }

    private func generateConversationTitle() {
        let snapshot = sharedSettings.snapshot
        guard let model = resolveAuxModel(snapshot.titleModelId),
              let conversationId = currentConversationId else { return }
        let content = auxConversationText(maxMessages: 4)
        guard !content.isEmpty else { return }
        let prompt = snapshot.titlePrompt
            .replacingOccurrences(of: "{locale}", with: auxLocaleName())
            .replacingOccurrences(of: "{content}", with: content)
        Task { [weak self] in
            guard let self else { return }
            guard let raw = await self.runAuxModel(model: model, prompt: prompt) else { return }
            let title = Self.sanitizeTitle(raw)
            guard !title.isEmpty else { return }
            await self.conversationStore?.renameConversation(id: conversationId, title: title)
        }
    }

    private func generateChatSuggestions() {
        let snapshot = sharedSettings.snapshot
        guard let model = resolveAuxModel(snapshot.suggestionModelId) else { return }
        let content = auxConversationText(maxMessages: 8)
        guard !content.isEmpty else { return }
        let prompt = snapshot.suggestionPrompt
            .replacingOccurrences(of: "{locale}", with: auxLocaleName())
            .replacingOccurrences(of: "{content}", with: content)
        let conversationId = currentConversationId
        Task { [weak self] in
            guard let self else { return }
            guard let raw = await self.runAuxModel(model: model, prompt: prompt) else { return }
            let suggestions = raw
                .split(separator: "\n")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .prefix(5)
            await MainActor.run {
                // 仅当仍是同一会话且未在生成中时才应用,避免覆盖到新一轮。
                guard self.currentConversationId == conversationId, !self.isGenerationActive else { return }
                withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                    self.chatSuggestions = Array(suggestions)
                }
            }
        }
    }

    /// 清洗标题:取首行、去引号/首尾标点、限长。
    private static func sanitizeTitle(_ raw: String) -> String {
        var title = raw
            .split(separator: "\n").first.map(String.init)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        title = title.trimmingCharacters(in: CharacterSet(charactersIn: "\"'“”『』「」《》 "))
        if title.count > 24 { title = String(title.prefix(24)) }
        return title
    }

    private func makeImageOnlyUserMessage(dataUrl: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [UIMessagePart.Image(url: dataUrl, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    /// Builds the user message: a plain text message when there are no images, or a
    /// multi-part message (text + `UIMessagePart.Image`) the KMP providers turn into
    /// real image blocks. Image URLs are self-contained `data:` URLs.
    private func makeUserMessage(prompt: String, images: [PendingChatImage]) -> UIMessage {
        guard !images.isEmpty else {
            return UIMessage.companion.user(prompt: prompt)
        }
        var parts: [UIMessagePart] = []
        if !prompt.isEmpty {
            parts.append(UIMessagePart.Text(text: prompt, metadata: nil))
        }
        for image in images {
            parts.append(UIMessagePart.Image(url: image.dataUrl, metadata: nil))
        }
        return UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: parts,
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
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
        case .ishExecuteResult, .ishHandoffResult:
            selectedFileContextError = "iSH tool output cannot be attached to a chat message."
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

    // MARK: - Image attachments

    static let maxImagesPerMessage = 4

    struct PendingChatImage: Identifiable, Equatable {
        let id = UUID()
        /// `data:<mime>;base64,...` — self-contained so it survives persistence and regeneration.
        let dataUrl: String
        /// Small JPEG bytes used only to render the composer thumbnail.
        let previewData: Data
    }

    enum ImageAttachmentState: Equatable {
        case none
        /// The current chat model accepts image input — send the image directly.
        case ready
        /// The current model is text-only but a vision model is configured — the image
        /// is recognized into text by the vision model first, then sent to the current model.
        case fallback
        case blocked(String)
    }

    /// Cached `<image_context>` recognition text per image `data:` URL, so the vision
    /// model is called once per image (covers regeneration within the session too).
    @ObservationIgnored private var visionRecognitionTexts: [String: String] = [:]

    func addPendingImage(dataUrl: String, previewData: Data) {
        guard pendingImages.count < Self.maxImagesPerMessage else {
            selectedFileContextError = "一次最多发送 \(Self.maxImagesPerMessage) 张图片"
            return
        }
        pendingImages.append(PendingChatImage(dataUrl: dataUrl, previewData: previewData))
        selectedFileContextError = nil
    }

    func removePendingImage(_ id: PendingChatImage.ID) {
        pendingImages.removeAll { $0.id == id }
    }

    func clearPendingImages() {
        pendingImages.removeAll()
    }

    /// Mirrors Android `ImageAttachmentValidator`: the current model having image input
    /// means the image is ready to send; otherwise a configured vision model is required,
    /// and failing that the send is blocked with a prompt (never silently dropped).
    var imageAttachmentState: ImageAttachmentState {
        guard !pendingImages.isEmpty else { return .none }
        if pendingImages.count > Self.maxImagesPerMessage {
            return .blocked("一次最多发送 \(Self.maxImagesPerMessage) 张图片")
        }
        let snapshot = sharedSettings.snapshot
        guard let model = snapshot.getCurrentChatModel() else {
            return .blocked("请先选择模型")
        }
        if Self.modelSupportsImageInput(model) { return .ready }
        // The user explicitly configured a vision model for this purpose — trust it
        // (capability metadata isn't reliably known for every model id), only requiring
        // that it resolves and has a usable provider.
        if let vision = snapshot.findModelById(uuid: snapshot.ocrModelId),
           ChatProviderConfiguration.provider(for: vision, providers: snapshot.providers) != nil {
            return .fallback
        }
        return .blocked("当前模型不支持图片，请先在「默认模型 → 辅助任务」配置视觉识别模型")
    }

    private static func modelSupportsImageInput(_ model: Model) -> Bool {
        let modalities = model.inputModalities.isEmpty
            ? (ModelRegistry.shared.MODEL_INPUT_MODALITIES.getData(modelId: model.modelId) as? [Modality] ?? [])
            : model.inputModalities
        return modalities.contains { $0.name == "IMAGE" }
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

    func approvePendingIshHandoffTool() {
        Task { @MainActor in
            await generationCoordinator.approvePendingIshHandoffTool()
        }
    }

    func denyPendingIshHandoffTool() {
        Task { @MainActor in
            await generationCoordinator.denyPendingIshHandoffTool()
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

    @discardableResult
    func handoffGenerationToBackgroundIfNeeded() -> Bool {
        generationCoordinator.handoffCurrentGenerationToBackground(conversationStore: conversationStore)
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
        let withContext = ChatRuntimeContextBuilder(
            sharedSettings: sharedSettings,
            mcpTools: mcpManager.tools,
            miniAppRepository: miniAppRepository,
            miniAppRuntimeEnabled: isMiniAppRuntimeEnabled
        ).injectingRuntimeContext(into: messages)
        return replacingImagesForNonVisionModel(withContext)
    }

    /// The stored/displayed messages keep their image parts (so the user sees the photo in
    /// the bubble), but a text-only chat model cannot receive image blocks. When the current
    /// model has no image input, swap each image part for its cached vision-recognition text
    /// before the request leaves for the provider.
    private func replacingImagesForNonVisionModel(_ messages: [UIMessage]) -> [UIMessage] {
        guard let model = sharedSettings.snapshot.getCurrentChatModel(),
              !Self.modelSupportsImageInput(model) else {
            return messages
        }
        guard messages.contains(where: { $0.parts.contains { $0 is UIMessagePart.Image } }) else {
            return messages
        }
        return messages.map { message in
            guard message.parts.contains(where: { $0 is UIMessagePart.Image }) else { return message }
            let newParts: [UIMessagePart] = message.parts.map { part in
                guard let image = part as? UIMessagePart.Image else { return part }
                let recognized = visionRecognitionTexts[image.url] ?? "[图片未能识别]"
                return UIMessagePart.Text(text: recognized, metadata: nil)
            }
            return UIMessage(
                id: message.id,
                role: message.role,
                parts: newParts,
                annotations: message.annotations,
                createdAt: message.createdAt,
                finishedAt: message.finishedAt,
                modelId: message.modelId,
                usage: message.usage,
                translation: message.translation
            )
        }
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
        // 图片生成现在挂载在「指定的生图模型」上(辅助任务 → 生图模型 / imageGenerationModelId),
        // 不再依赖独立的图片生成配置页。模型能解析到且其 provider 有有效 apiKey/baseURL 才挂工具。
        let imageGenerationConfigured: Bool = {
            let snap = sharedSettings.snapshot
            guard let model = snap.findModelById(uuid: snap.imageGenerationModelId),
                  let provider = ChatProviderConfiguration.provider(for: model, providers: snap.providers) else {
                return false
            }
            // Codex image generation uses the OAuth bearer (no apiKey); gate on
            // sign-in instead. Other providers require a real apiKey + baseURL.
            if IOSCodexProviderResolver.isCodexProvider(provider) {
                return IOSCodexProviderResolver.isSignedIn(provider)
            }
            let key = ChatProviderConfiguration.apiKey(of: provider).trimmingCharacters(in: .whitespacesAndNewlines)
            let base = ChatProviderConfiguration.baseURL(of: provider).trimmingCharacters(in: .whitespacesAndNewlines)
            return !key.isEmpty && !base.isEmpty
        }()
        var builtInTools: [BuiltInTools] = []
        if searchEnabled { builtInTools.append(BuiltInTools.Search.shared) }

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
        if localToolExecutor != nil {
            toolDeclarations.append(contentsOf: ToolKt.iosToolDeclarations(
                names: ishToolNamesForCurrentTurn()
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

    private func ishToolNamesForCurrentTurn() -> [String] {
        let embeddedNames = enabledModelToolNames(IOSEmbeddedIshToolCatalog.supportedToolNames)
        let handoffNames = enabledModelToolNames(IOSIshToolCatalog.supportedToolNames)
        guard !embeddedNames.isEmpty else { return handoffNames }
        return latestUserMessageRequestsExternalIshHandoff() ? handoffNames : embeddedNames
    }

    private func latestUserMessageRequestsExternalIshHandoff() -> Bool {
        guard let text = latestUserMessageTextLowercased() else { return false }
        return [
            "外部 ish", "ish app", "ish 应用", "打开 ish", "交给 ish",
            "交接", "复制到剪贴板", "剪贴板", "粘贴", "手动执行",
            "handoff", "external ish", "clipboard", "paste manually"
        ].contains { text.contains($0) }
    }

    private func latestUserMessageRequestsWorkspaceWrite() -> Bool {
        guard let text = latestUserMessageTextLowercased() else { return false }

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

    private func latestUserMessageTextLowercased() -> String? {
        messages.reversed()
            .first(where: { $0.role == MessageRole.user })?
            .toText()
            .lowercased()
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

    private static func truncatedError(_ value: String, maxLength: Int = 700) -> String {
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

    private func enabledModelToolNames(_ names: Set<String>) -> [String] {
        guard let localToolExecutor else { return Array(names).sorted() }
        let snapshot = localToolExecutor.permissionsStatus()
        return names.filter { toolName in
            guard let capability = snapshot.capabilities.first(where: { $0.modelToolNames.contains(toolName) }) else {
                return false
            }
            return capability.policy != IOSAgentPermissionPolicy.disabled.title
        }
        .sorted()
    }

}
