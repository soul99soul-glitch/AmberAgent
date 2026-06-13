import Foundation
import CryptoKit
@preconcurrency import Shared

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
    private let db: AgentRuntimeDatabase = IosDatabaseFactory.shared.createDatabase()
    private var streamJob: Kotlinx_coroutines_coreJob?

    // Run tracking — stored so cancelGeneration() can record an interrupted run.
    private var currentRunId: String?
    private var currentStartedAt: Int64?
    private var currentInputDigest: String?

    // MARK: - Init

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    // MARK: - Actions

    func sendMessage() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let digest = Self.inputDigest(for: text)
        let userMsg = UIMessage.companion.user(prompt: text)
        messages.append(userMsg)
        inputText = ""
        generateResponse(inputDigest: digest)
    }

    func cancelGeneration() {
        // Capture run info before clearing state so we can record the interruption.
        let runId = currentRunId
        let startedAt = currentStartedAt
        let digest = currentInputDigest

        streamJob?.cancel()
        streamJob = nil
        currentRunId = nil
        currentStartedAt = nil
        currentInputDigest = nil
        isLoading = false

        guard let runId, let startedAt, let digest else { return }
        Task { @MainActor in
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
        streamJob?.cancel()
        isLoading = true

        let providerSetting = makeProviderSetting()
        let params = makeTextGenerationParams()
        let runId = UUID().uuidString
        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)

        currentRunId = runId
        currentStartedAt = startedAt
        currentInputDigest = inputDigest

        let accumulator = MessageStreamAccumulator(
            initialMessages: messages,
            model: params.model
        )

        streamJob = provider.streamTextCancellable(
            providerSetting: providerSetting,
            messages: messages,
            params: params,
            onChunk: { chunk in
                // Called sequentially from Dispatchers.Default.
                accumulator.append(chunk: chunk)
                let snapshot = accumulator.snapshot()
                Task { @MainActor [weak self] in
                    self?.messages = snapshot
                }
            },
            onComplete: { [weak self] in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    await self.recordRun(
                        runId: runId,
                        startedAt: startedAt,
                        status: "completed",
                        inputDigest: inputDigest
                    )
                    self.finishStreaming()
                }
            },
            onError: { [weak self] error in
                Task { @MainActor [weak self] in
                    guard let self else { return }
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
                        inputDigest: inputDigest
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

    /// Stable SHA-256 hex digest of the input text.
    /// Unlike the previous raw-text approach, this does not leak conversation content
    /// into the run record's `inputDigest` field.
    private static func inputDigest(for text: String) -> String {
        let hash = SHA256.hash(data: Data(text.utf8))
        return hash.map { String(format: "%02x", $0) }.joined()
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
