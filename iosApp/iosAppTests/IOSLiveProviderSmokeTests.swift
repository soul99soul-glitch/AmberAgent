import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSLiveProviderSmokeTests: XCTestCase {
    func testOpenAICompatibleOrdinaryChatWithMemoryContextWhenKeyIsProvided() async throws {
        let environment = ProcessInfo.processInfo.environment
        let apiKey = environment["AMBERAGENT_IOS_LIVE_OPENAI_API_KEY"]?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !apiKey.isEmpty else {
            throw XCTSkip("Set AMBERAGENT_IOS_LIVE_OPENAI_API_KEY to run the real iOS ordinary-chat provider smoke.")
        }

        let baseURL = environment["AMBERAGENT_IOS_LIVE_OPENAI_BASE_URL"]?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfBlank ?? "https://api.openai.com/v1"
        let modelID = environment["AMBERAGENT_IOS_LIVE_OPENAI_MODEL"]?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfBlank ?? "gpt-4o-mini"

        let originalRecords = IosMemoryFactory.shared.getAllRecords()
        IosMemoryFactory.shared.replaceAll(records: [])
        defer {
            IosMemoryFactory.shared.replaceAll(records: originalRecords)
        }

        let memorySentinel = "live smoke memory sentinel"
        IosMemoryFactory.shared.addMemory(
            scope: MemoryScope.core,
            kind: MemoryKind.note,
            content: memorySentinel,
            assistantId: IosMemoryFactory.shared.GLOBAL_MEMORY_ID
        )

        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            autoGenerateResponses: false
        )
        let userMessage = UIMessage.companion.user(
            prompt: "Reply with exactly: AMBER-IOS-LIVE-OK"
        )
        let uploadMessages = viewModel.preparedUploadMessagesForTesting([userMessage])
        XCTAssertEqual(uploadMessages.first?.role, MessageRole.system)
        XCTAssertTrue(textContent(of: uploadMessages.first).contains(memorySentinel))

        let provider = OpenAIKmpProvider()
        let providerSetting = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "iOS Live Smoke",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: apiKey,
            baseUrl: baseURL,
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        let model = Model(
            modelId: modelID,
            displayName: modelID,
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
        let params = TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0),
            topP: nil,
            maxTokens: KotlinInt(value: 32),
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )

        let chunk = try await provider.generateText(
            providerSetting: providerSetting,
            messages: uploadMessages,
            params: params
        )
        let response = textContent(of: chunk)

        XCTAssertFalse(response.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        XCTAssertTrue(
            response.contains("AMBER-IOS-LIVE-OK"),
            "Expected the real provider response to include the requested smoke token; got: \(response)"
        )
    }

    private func textContent(of message: UIMessage?) -> String {
        guard let message else { return "" }
        return message.parts
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "\n")
    }

    private func textContent(of chunk: MessageChunk) -> String {
        chunk.choices
            .flatMap { choice -> [UIMessagePart] in
                let messageParts = choice.message?.parts ?? []
                let deltaParts = choice.delta?.parts ?? []
                return messageParts + deltaParts
            }
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "")
    }
}

private extension String {
    var nilIfBlank: String? {
        isEmpty ? nil : self
    }
}
