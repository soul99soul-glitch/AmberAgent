import Foundation
import CryptoKit
import Observation
@preconcurrency import Shared

// MARK: - ChatViewModel

private final class StreamJobBox {
    var job: Kotlinx_coroutines_coreJob?

    deinit {
        job?.cancel(cause: nil)
    }
}

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
        for message in messages {
            guard let usage = message.usage else { continue }
            prompt += Int(usage.promptTokens)
            completion += Int(usage.completionTokens)
            cached += Int(usage.cachedTokens)
        }
        return ChatContextSnapshot(
            messageCount: messages.count,
            modelId: currentModelId,
            supportsReasoning: currentModelSupportsReasoning,
            pendingSelectedFileName: pendingSelectedFilePreview?.fileName,
            pendingSelectedFileBytesText: pendingSelectedFilePreview.map { "\($0.bytesRead) bytes" },
            promptTokens: prompt,
            completionTokens: completion,
            totalTokens: prompt + completion,
            cachedTokens: cached
        )
    }

    var currentModelSupportsReasoning: Bool {
        currentModelAbilities.contains(.reasoning)
    }

    // MARK: - Private

    private let settingsStore: SettingsStore
    private let sharedSettings: IOSSharedSettingsStore
    private let localToolExecutor: IOSLocalToolExecutor?
    private let autoGenerateResponses: Bool
    private let liveActivityController: AgentLiveActivityController
    @ObservationIgnored private lazy var provider = OpenAIKmpProvider()
    @ObservationIgnored private lazy var db: AgentRuntimeDatabase = IosDatabaseFactory.shared.createDatabase()
    @ObservationIgnored private let streamJobBox = StreamJobBox()
    // [Slice 3] Dispatch targets for chat-injected tools. Lazily built so a
    // chat without these tools pays no construction cost.
    @ObservationIgnored private lazy var subAgentRunner = SubAgentRunner()
    @ObservationIgnored private lazy var councilRunner = CouncilRunner()
    @ObservationIgnored private lazy var mcpManager: IOSMcpManager = {
        // Build from the shared config store (same UserDefaults key as
        // McpServersView) so callTool reaches the same configured servers.
        IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared)
    }()
    private var attachRequestId: UUID?

    private var streamJob: Kotlinx_coroutines_coreJob? {
        get { streamJobBox.job }
        set { streamJobBox.job = newValue }
    }

    private var currentModelId: String {
        let trimmed = settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "gpt-4o" : trimmed
    }

    private var currentModelAbilities: [ModelAbility] {
        ModelRegistry.shared.MODEL_ABILITIES.getData(modelId: currentModelId) as? [ModelAbility] ?? []
    }

    private var liveActivityPreferenceEnabled: Bool {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: IOSExecutionPreferenceKeys.liveActivity) != nil else { return true }
        return defaults.bool(forKey: IOSExecutionPreferenceKeys.liveActivity)
    }

    // Run tracking — stored so cancelGeneration() can record an interrupted run.
    private var currentRunId: String?
    private var currentStartedAt: Int64?
    private var currentInputDigest: String?
    private var currentConversationIdForRun: KotlinUuid?
    private var currentToolResumeCount: Int = 0
    private let maxToolResumeCount = 1

    // MARK: - Init

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore = IOSSharedSettingsStore(),
        localToolExecutor: IOSLocalToolExecutor? = nil,
        autoGenerateResponses: Bool = true,
        liveActivityController: AgentLiveActivityController? = nil
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.localToolExecutor = localToolExecutor
        self.autoGenerateResponses = autoGenerateResponses
        self.liveActivityController = liveActivityController ?? .shared
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
        Task { @MainActor in
            if let targetConversationId {
                await store.save(messages: snapshot, to: targetConversationId)
            } else {
                await store.saveCurrent(messages: snapshot)
            }
        }
    }

    func sendMessage() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isAttachingSelectedFile else { return }

        let prompt = Self.promptText(userText: text, selectedFilePreview: pendingSelectedFilePreview)
        let digest = Self.inputDigest(for: prompt)
        let userMsg = UIMessage.companion.user(prompt: prompt)
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
        }
    }

    func clearPendingSelectedFilePreview() {
        pendingSelectedFilePreview = nil
        selectedFileContextError = nil
    }

    func cancelGeneration() {
        // Capture run info before clearing state so we can record the interruption.
        let runId = currentRunId
        let startedAt = currentStartedAt
        let digest = currentInputDigest
        let conversationId = currentConversationIdForRun

        streamJob?.cancel(cause: nil)
        streamJob = nil
        currentRunId = nil
        currentStartedAt = nil
        currentInputDigest = nil
        currentConversationIdForRun = nil
        currentToolResumeCount = 0
        isLoading = false

        guard let runId, let startedAt, let digest else { return }
        Task { @MainActor in
            await self.liveActivityController.end(
                runId: runId,
                presentation: .cancelled()
            )
            await self.recordRun(
                runId: runId,
                startedAt: startedAt,
                status: "interrupted",
                inputDigest: digest
            )
            // 持久化已生成的部分消息——用户取消后，半截回复仍应留存在历史里。
            self.persistMessages(conversationId: conversationId)
        }
    }

    // MARK: - Private

    private func generateResponse(inputDigest: String, conversationId: KotlinUuid?) {
        if streamJob != nil {
            cancelGeneration()
        }
        isLoading = true

        let providerSetting = makeProviderSetting()
        let params = makeTextGenerationParams()
        let runId = UUID().uuidString
        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)

        currentRunId = runId
        currentStartedAt = startedAt
        currentInputDigest = inputDigest
        currentConversationIdForRun = conversationId
        currentToolResumeCount = 0
        startLiveActivity(
            runId: runId,
            presentation: .generatingResponse(modelName: params.model.modelId)
        )

        startStreaming(
            providerSetting: providerSetting,
            params: params,
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            uploadMessages: messages
        )
    }

    private func startStreaming(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        uploadMessages: [UIMessage]
    ) {
        let accumulator = MessageStreamAccumulator(
            initialMessages: uploadMessages,
            model: params.model
        )
        var detectedToolCallIds = Set<String>()

        streamJob = provider.streamTextCancellable(
            providerSetting: providerSetting,
            messages: uploadMessages,
            params: params,
            onChunk: { chunk in
                // Called sequentially from Dispatchers.Default.
                accumulator.append(chunk: chunk)
                let toolCalls = Self.toolCalls(in: chunk)
                    .filter { toolCall in
                        let key = Self.toolCallKey(toolCall)
                        guard !detectedToolCallIds.contains(key) else { return false }
                        detectedToolCallIds.insert(key)
                        return true
                    }
                let snapshot = accumulator.snapshot()
                Task { @MainActor [weak self] in
                    guard let self, self.currentRunId == runId else { return }
                    if !toolCalls.isEmpty {
                        self.handleDetectedToolCalls(toolCalls, runId: runId)
                    }
                    self.messages = snapshot
                    self.messageRevision &+= 1
                }
            },
            onComplete: { [weak self] in
                let snapshot = accumulator.snapshot()
                Task { @MainActor [weak self] in
                    guard let self, self.currentRunId == runId else { return }
                    self.messages = snapshot
                    self.messageRevision &+= 1

                    if sharedSettings.snapshot.enableWebSearch,
                       let searchToolCall = self.pendingSearchToolCall(in: snapshot),
                       self.currentToolResumeCount < self.maxToolResumeCount {
                        self.currentToolResumeCount += 1
                        self.streamJob = nil
                        Task { @MainActor in
                            await self.executeSearchToolCall(
                                searchToolCall,
                                providerSetting: providerSetting,
                                params: params,
                                runId: runId,
                                startedAt: startedAt,
                                inputDigest: inputDigest,
                                conversationId: conversationId,
                                baseMessages: snapshot
                            )
                        }
                        return
                    }

                    // [Slice 3] MCP / sub-agent / council dispatch: if the model
                    // invoked one of these tools, run its executor and resume the
                    // stream with the result (same resume pattern as search_web).
                    if let slice3ToolCall = self.pendingSlice3ToolCall(in: snapshot),
                       self.currentToolResumeCount < self.maxToolResumeCount {
                        self.currentToolResumeCount += 1
                        self.streamJob = nil
                        Task { @MainActor in
                            await self.executeSlice3ToolCall(
                                slice3ToolCall,
                                providerSetting: providerSetting,
                                params: params,
                                runId: runId,
                                startedAt: startedAt,
                                inputDigest: inputDigest,
                                conversationId: conversationId,
                                baseMessages: snapshot
                            )
                        }
                        return
                    }

                    await self.recordRun(
                        runId: runId,
                        startedAt: startedAt,
                        status: "completed",
                        inputDigest: inputDigest
                    )
                    await self.liveActivityController.end(
                        runId: runId,
                        presentation: .completed()
                    )
                    self.persistMessages(conversationId: conversationId)
                    self.finishStreaming()
                }
            },
            onError: { [weak self] error in
                Task { @MainActor [weak self] in
                    guard let self, self.currentRunId == runId else { return }
                    let errMsg = UIMessage(
                        id: KotlinUuid.companion.random(),
                        role: MessageRole.assistant,
                        parts: [UIMessagePart.Text(text: "Error: \(error.message ?? String(describing: error))", metadata: nil)],
                        annotations: [],
                        createdAt: self.nowLocalDateTime(),
                        finishedAt: self.nowLocalDateTime(),
                        modelId: nil,
                        usage: nil,
                        translation: nil
                    )
                    self.messages.append(errMsg)
                    self.messageRevision &+= 1
                    await self.recordRun(
                        runId: runId,
                        startedAt: startedAt,
                        status: "failed",
                        inputDigest: inputDigest
                    )
                    await self.liveActivityController.end(
                        runId: runId,
                        presentation: .failed()
                    )
                    self.persistMessages(conversationId: conversationId)
                    self.finishStreaming()
                }
            }
        )
    }

    private func finishStreaming() {
        currentRunId = nil
        currentStartedAt = nil
        currentInputDigest = nil
        currentConversationIdForRun = nil
        streamJob = nil
        isLoading = false
    }

    private static func toolCalls(in chunk: MessageChunk) -> [UIMessagePart.Tool] {
        chunk.choices.flatMap { choice in
            (choice.delta ?? choice.message)?.parts.compactMap { $0 as? UIMessagePart.Tool } ?? []
        }
    }

    private static func toolCallKey(_ toolCall: UIMessagePart.Tool) -> String {
        let id = toolCall.toolCallId.trimmingCharacters(in: .whitespacesAndNewlines)
        if !id.isEmpty { return id }
        return "\(toolCall.toolName):\(toolCall.input)"
    }

    private func pendingSearchToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { $0.toolName == "search_web" && $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    /// [Slice 3] Detects a pending MCP / sub-agent / council tool call (one
    /// whose output is still empty) so onComplete can dispatch it. Mirrors
    /// pendingSearchToolCall but matches the Slice-3 tool names.
    private func pendingSlice3ToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        let slice3Names: Set<String> = ["mcp_call", "subagent_dispatch", "model_council_run"]
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { slice3Names.contains($0.toolName) && $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    private func executeSearchToolCall(
        _ toolCall: UIMessagePart.Tool,
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        baseMessages: [UIMessage]
    ) async {
        let resultText: String
        do {
            resultText = try await IOSSearchExecutor.execute(toolInput: toolCall.input)
        } catch {
            resultText = "Search failed: \((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)"
        }

        guard currentRunId == runId else { return }
        let resumedMessages = messagesByFinishingToolCall(toolCall, outputText: resultText, in: baseMessages)
        messages = resumedMessages
        messageRevision &+= 1

        startStreaming(
            providerSetting: providerSetting,
            params: params,
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            uploadMessages: resumedMessages
        )
    }

    /// [Slice 3] Executes an MCP / sub-agent / council tool call and resumes the
    /// stream with the result. Mirrors executeSearchToolCall but dispatches to
    /// IOSMcpManager.callTool / SubAgentRunner.run / CouncilRunner.run based on
    /// the tool name. The tool `input` is a JSON string; we extract the fields
    /// the model provided.
    private func executeSlice3ToolCall(
        _ toolCall: UIMessagePart.Tool,
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
        conversationId: KotlinUuid?,
        baseMessages: [UIMessage]
    ) async {
        let resultText = await dispatchSlice3ToolCall(toolCall)

        guard currentRunId == runId else { return }
        let resumedMessages = messagesByFinishingToolCall(toolCall, outputText: resultText, in: baseMessages)
        messages = resumedMessages
        messageRevision &+= 1

        startStreaming(
            providerSetting: providerSetting,
            params: params,
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId,
            uploadMessages: resumedMessages
        )
    }

    /// [Slice 3] Routes a Slice-3 tool call to its executor and returns the
    /// result text (or an honest error string — never fabricated success).
    private func dispatchSlice3ToolCall(_ toolCall: UIMessagePart.Tool) async -> String {
        switch toolCall.toolName {
        case "subagent_dispatch":
            let objective = jsonObject(toolCall.input)?["objective"] as? String ?? toolCall.input
            return await subAgentRunner.run(objective: objective)
        case "model_council_run":
            let objective = jsonObject(toolCall.input)?["objective"] as? String ?? toolCall.input
            return await councilRunner.run(objective: objective)
        case "mcp_call":
            guard let args = jsonObject(toolCall.input),
                  let server = args["server"] as? String,
                  let tool = args["tool"] as? String else {
                return "mcp_call 参数无效：需要 server 与 tool。"
            }
            let arguments = (args["arguments"] as? [String: Any]) ?? [:]
            do {
                // IOSMcpManager.callTool connects to the server (JSON-RPC) and
                // invokes the named tool, returning text content.
                return try await mcpManager.callTool(serverName: server, toolName: tool, arguments: arguments)
            } catch {
                return "MCP 调用失败（server: \(server)，tool: \(tool)）：\(error.localizedDescription)"
            }
        default:
            return "未知工具：\(toolCall.toolName)"
        }
    }

    /// Best-effort JSON object parse of a tool-call input string. Returns nil
    /// if the input isn't a valid JSON object (callers fall back to raw text).
    private func jsonObject(_ string: String) -> [String: Any]? {
        guard let data = string.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private func messagesByFinishingToolCall(
        _ targetToolCall: UIMessagePart.Tool,
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        let outputPart = UIMessagePart.Text(text: outputText, metadata: nil)
        var didFinishToolCall = false

        return messages.map { message in
            guard message.role == MessageRole.assistant else { return message }
            var didChangeMessage = false
            let parts = message.parts.map { part -> UIMessagePart in
                guard !didFinishToolCall,
                      let toolPart = part as? UIMessagePart.Tool,
                      Self.toolCallKey(toolPart) == Self.toolCallKey(targetToolCall) else {
                    return part
                }

                didFinishToolCall = true
                didChangeMessage = true
                return UIMessagePart.Tool(
                    toolCallId: toolPart.toolCallId,
                    toolName: toolPart.toolName,
                    input: toolPart.input,
                    output: [outputPart],
                    approvalState: toolPart.approvalState,
                    metadata: toolPart.metadata
                )
            }

            guard didChangeMessage else { return message }
            return UIMessage(
                id: message.id,
                role: message.role,
                parts: parts,
                annotations: message.annotations,
                createdAt: message.createdAt,
                finishedAt: message.finishedAt ?? nowLocalDateTime(),
                modelId: message.modelId,
                usage: message.usage,
                translation: message.translation
            )
        }
    }

    private func handleDetectedToolCalls(_ toolCalls: [UIMessagePart.Tool], runId: String) {
        for toolCall in toolCalls where toolCall.toolName == "search_web" {
            print("[ChatViewModel] Detected search tool call runId=\(runId) toolCallId=\(toolCall.toolCallId) input=\(toolCall.input)")
        }
    }

    private func startLiveActivity(runId: String, presentation: AgentActivityPresentation) {
        guard liveActivityPreferenceEnabled else { return }
        liveActivityController.start(runId: runId, presentation: presentation)
    }

    /// Stable SHA-256 hex digest of the input text.
    /// Unlike the previous raw-text approach, this does not leak conversation content
    /// into the run record's `inputDigest` field.
    private static func inputDigest(for text: String) -> String {
        let hash = SHA256.hash(data: Data(text.utf8))
        return hash.map { String(format: "%02x", $0) }.joined()
    }

    static func promptText(
        userText: String,
        selectedFilePreview: SelectedDocumentReadResult?
    ) -> String {
        guard let selectedFilePreview else { return userText }
        return """
        \(userText)

        [Selected file preview: \(selectedFilePreview.fileName), \(selectedFilePreview.bytesRead) bytes]
        \(selectedFilePreview.preview)
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
        let finishedAtValue: KotlinLong? = KotlinLong(value: now)
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

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "OpenAI",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: settingsStore.apiKey,
            baseUrl: settingsStore.baseUrl,
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    private func makeTextGenerationParams() -> TextGenerationParams {
        let modelId = currentModelId
        let modelAbilities = currentModelAbilities
        let searchEnabled = sharedSettings.snapshot.enableWebSearch
        let model = Model(
            modelId: modelId,
            displayName: modelId,
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: modelAbilities,
            tools: searchEnabled ? Set([BuiltInTools.Search.shared]) : Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        // [Slice 3] Tool declarations: search_web (existing) + MCP dispatch +
        // sub-agent dispatch + model-council run. The model decides which to
        // call; the iOS onComplete dispatch routes each to its executor.
        var toolDeclarations: [Tool] = []
        if searchEnabled {
            toolDeclarations.append(ToolKt.createSearchWebToolDeclaration())
        }
        toolDeclarations.append(ToolKt.createMcpCallToolDeclaration())
        toolDeclarations.append(ToolKt.createSubAgentDispatchToolDeclaration())
        toolDeclarations.append(ToolKt.createModelCouncilRunToolDeclaration())
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: toolDeclarations,
            reasoningLevel: modelAbilities.contains(.reasoning) ? reasoningLevel : .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func nowLocalDateTime() -> Kotlinx_datetimeLocalDateTime {
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
}
