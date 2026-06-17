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

    var contextSnapshot: ChatContextSnapshot {
        ChatContextSnapshot(
            messageCount: messages.count,
            modelId: currentModelId,
            supportsReasoning: currentModelSupportsReasoning,
            pendingSelectedFileName: pendingSelectedFilePreview?.fileName,
            pendingSelectedFileBytesText: pendingSelectedFilePreview.map { "\($0.bytesRead) bytes" }
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
        guard autoGenerateResponses else { return }
        generateResponse(inputDigest: digest)
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

        streamJob?.cancel(cause: nil)
        streamJob = nil
        currentRunId = nil
        currentStartedAt = nil
        currentInputDigest = nil
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
        }
    }

    // MARK: - Private

    private func generateResponse(inputDigest: String) {
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
            uploadMessages: messages
        )
    }

    private func startStreaming(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
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
                    self.finishStreaming()
                }
            }
        )
    }

    private func finishStreaming() {
        currentRunId = nil
        currentStartedAt = nil
        currentInputDigest = nil
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

    private func executeSearchToolCall(
        _ toolCall: UIMessagePart.Tool,
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        runId: String,
        startedAt: Int64,
        inputDigest: String,
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
            uploadMessages: resumedMessages
        )
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
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: searchEnabled ? [ToolKt.createSearchWebToolDeclaration()] : [],
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
