import Foundation
@preconcurrency import Shared

@Observable
final class ChatViewModel {

    // MARK: - State

    var messages: [UIMessage] = []
    var inputText: String = ""
    var isLoading: Bool = false

    // MARK: - Private

    private let settingsStore: SettingsStore
    private let provider = OpenAIKmpProvider()
    private var streamingTask: Task<Void, Never>?

    // MARK: - Init

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    // MARK: - Actions

    func sendMessage() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let userMsg = UIMessage.companion.user(prompt: text)
        messages.append(userMsg)
        inputText = ""
        generateResponse()
    }

    func cancelGeneration() {
        streamingTask?.cancel()
        streamingTask = nil
        isLoading = false
    }

    // MARK: - Private

    private func generateResponse() {
        streamingTask?.cancel()
        isLoading = true

        let providerSetting = makeProviderSetting()
        let params = makeTextGenerationParams()

        streamingTask = Task { @MainActor in
            // Create a placeholder assistant message
            let placeholderId = KotlinUuid.companion.random()
            let localDt = nowLocalDateTime()
            let placeholder = UIMessage(
                id: placeholderId,
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: "", metadata: nil)],
                annotations: [],
                createdAt: localDt,
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
            self.messages.append(placeholder)

            self.provider.generateText(
                providerSetting: providerSetting,
                messages: Array(self.messages.dropLast()),
                params: params
            ) { [weak self] chunk, error in
                Task { @MainActor in
                    guard let self else { return }
                    if let chunk {
                        let assistantText = chunk.choices.first?.delta?.toText()
                            ?? chunk.choices.first?.message?.toText()
                            ?? ""
                        let updatedPlaceholder = UIMessage(
                            id: placeholderId,
                            role: MessageRole.assistant,
                            parts: [UIMessagePart.Text(text: assistantText, metadata: nil)],
                            annotations: [],
                            createdAt: localDt,
                            finishedAt: self.nowLocalDateTime(),
                            modelId: nil,
                            usage: chunk.usage,
                            translation: nil
                        )
                        if let lastIndex = self.messages.indices.last {
                            self.messages[lastIndex] = updatedPlaceholder
                        }
                    } else if let error {
                        let errorPlaceholder = UIMessage(
                            id: placeholderId,
                            role: MessageRole.assistant,
                            parts: [UIMessagePart.Text(
                                text: "Error: \(error.localizedDescription)",
                                metadata: nil
                            )],
                            annotations: [],
                            createdAt: localDt,
                            finishedAt: self.nowLocalDateTime(),
                            modelId: nil,
                            usage: nil,
                            translation: nil
                        )
                        if let lastIndex = self.messages.indices.last {
                            self.messages[lastIndex] = errorPlaceholder
                        }
                    }
                    self.isLoading = false
                }
            }
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
