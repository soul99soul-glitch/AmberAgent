import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Generation-parameter + system-prompt parity tests (Android GenerationHandler
/// parity). Verifies makeTextGenerationParams reads real Assistant/Model
/// values instead of hardcoding temperature=0.7/topP=nil/maxTokens=nil, and
/// that the assistant system prompt is injected into the upload context.
@MainActor
final class ChatViewModelGenerationParamsTests: XCTestCase {

    private func isolatedDefaults() -> UserDefaults {
        let suite = "ChatViewModelGenerationParamsTests-\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }

    /// The default seeded Amber Assistant carries a non-empty systemPrompt.
    /// The upload context must include it as a leading system message so the
    /// model receives the assistant's persona/instructions (Android parity).
    func testAssistantSystemPromptIsInjectedIntoUploadContext() {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            autoGenerateResponses: false
        )
        viewModel.inputText = "hello"
        viewModel.sendMessage()

        let uploadMessages = viewModel.preparedUploadMessagesForTesting(viewModel.messages)
        let assistantSystemPrompt = sharedSettings.snapshot.getCurrentAssistant().systemPrompt
            .trimmingCharacters(in: .whitespacesAndNewlines)

        // The first upload message must be the assistant system prompt (it is
        // injected before memory/MCP/mini-app/user).
        XCTAssertFalse(assistantSystemPrompt.isEmpty, "default assistant should have a system prompt")
        let first = uploadMessages.first
        XCTAssertEqual(first?.role, MessageRole.system)
        XCTAssertTrue((first?.toText() ?? "").contains(String(assistantSystemPrompt.prefix(40))))
    }

    /// System prompt must be upload-only — never persisted into the visible
    /// chat history (it would pollute the message list).
    func testSystemPromptInjectionDoesNotPersistToChatHistory() {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            autoGenerateResponses: false
        )
        viewModel.inputText = "hello"
        viewModel.sendMessage()

        // Persisted messages must contain only the user message — no system
        // message from the injection.
        XCTAssertFalse(viewModel.messages.contains { $0.role == MessageRole.system })
        XCTAssertEqual(viewModel.messages.count, 1)
        XCTAssertEqual(viewModel.messages.first?.role, MessageRole.user)
    }

    /// makeTextGenerationParams must succeed and produce a non-empty tool list
    /// (the tool declarations are independent of the params fix, but this guards
    /// against the real-params refactor throwing on snapshot access).
    func testMakeTextGenerationParamsProducesValidParams() {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            autoGenerateResponses: false
        )
        let params = viewModel.textGenerationParamsForTesting()
        // The default assistant has temperature == null, so the resolved
        // temperature must be nil (NOT the old hardcoded 0.7).
        XCTAssertNil(params.temperature, "temperature should come from Assistant (null by default), not hardcoded 0.7")
        XCTAssertNil(params.topP)
        XCTAssertNotNil(params.model)
        XCTAssertFalse(params.tools.isEmpty, "tool declarations must be populated")
    }

    /// The tool-declaration names must still include the expected core tools
    /// after the params refactor (regression guard).
    func testToolDeclarationsStillIncludeCoreTools() {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            autoGenerateResponses: false
        )
        let names = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(names.contains("subagent_dispatch"))
        XCTAssertTrue(names.contains("model_council_run"))
    }
}
