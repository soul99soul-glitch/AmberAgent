import Foundation
@preconcurrency import Shared

// MARK: - Flow Collector Helper

private class ChunkCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    let onChunk: (MessageChunk) -> Void
    init(onChunk: @escaping (MessageChunk) -> Void) { self.onChunk = onChunk }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let chunk = value as? MessageChunk { onChunk(chunk) }
        completionHandler(nil)
    }
}

// MARK: - ChatViewModel

@MainActor
@Observable
final class ChatViewModel {

    // MARK: - State

    var messages: [UIMessage] = []
    var inputText: String = ""
    var isLoading: Bool = false

    // MARK: - Private

    private let settingsStore: SettingsStore
    private let provider = OpenAIKmpProvider()
    private let db: AgentRuntimeDatabase = AgentRuntimeDatabaseConstructor.shared.initialize()
    private var streamingTask: Task<Void, Never>?

    // MARK: - Init

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    // MARK: - Actions

    func sendMessage() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let inputSnapshot = text
        let userMsg = UIMessage.companion.user(prompt: text)
        messages.append(userMsg)
        inputText = ""
        generateResponse(inputSnapshot: inputSnapshot)
    }

    func cancelGeneration() {
        streamingTask?.cancel()
        streamingTask = nil
        isLoading = false
    }

    // MARK: - Private

    private func generateResponse(inputSnapshot: String) {
        streamingTask?.cancel()
        isLoading = true

        let providerSetting = makeProviderSetting()
        let params = makeTextGenerationParams()
        let runId = UUID().uuidString
        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)

        streamingTask = Task { @MainActor in
            let accumulator = MessageStreamAccumulator(
                initialMessages: self.messages,
                model: params.model
            )

            do {
                // Step 1: Obtain the Flow from streamText
                let flow = try await withCheckedThrowingContinuation {
                    (cont: CheckedContinuation<any Kotlinx_coroutines_coreFlow, Error>) in
                    self.provider.streamText(
                        providerSetting: providerSetting,
                        messages: self.messages,
                        params: params
                    ) { flow, error in
                        if let flow { cont.resume(returning: flow) }
                        else if let error { cont.resume(throwing: error) }
                    }
                }

                // Step 2: Collect the flow — each emission is an incremental MessageChunk
                try await withCheckedThrowingContinuation {
                    (cont: CheckedContinuation<Void, Error>) in
                    let collector = ChunkCollector { [weak self] chunk in
                        guard let self else { return }
                        accumulator.append(chunk: chunk)
                        let snapshot = accumulator.snapshot()
                        Task { @MainActor [weak self] in
                            guard let self else { return }
                            self.messages = snapshot
                        }
                    }
                    flow.collect(collector: collector) { error in
                        if let error { cont.resume(throwing: error) }
                        else { cont.resume() }
                    }
                }

                // Step 3: Record successful run to Room
                await self.recordRun(
                    runId: runId,
                    startedAt: startedAt,
                    status: "completed",
                    inputDigest: inputSnapshot
                )
            } catch is CancellationError {
                // Cancelled — record as interrupted
                await self.recordRun(
                    runId: runId,
                    startedAt: startedAt,
                    status: "interrupted",
                    inputDigest: inputSnapshot
                )
            } catch {
                // Error — append error message
                let errMsg = UIMessage(
                    id: KotlinUuid.companion.random(),
                    role: MessageRole.assistant,
                    parts: [UIMessagePart.Text(text: "Error: \(error.localizedDescription)", metadata: nil)],
                    annotations: [],
                    createdAt: self.nowLocalDateTime(),
                    finishedAt: self.nowLocalDateTime(),
                    modelId: nil,
                    usage: nil,
                    translation: nil
                )
                self.messages.append(errMsg)

                await self.recordRun(
                    runId: runId,
                    startedAt: startedAt,
                    status: "failed",
                    inputDigest: inputSnapshot
                )
            }

            isLoading = false
        }
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
        let model = Model(
            modelId: settingsStore.modelId,
            displayName: settingsStore.modelId,
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
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.off,
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
