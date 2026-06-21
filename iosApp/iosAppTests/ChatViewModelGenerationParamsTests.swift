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

    private func localToolExecutor(
        permissionStore: IOSPermissionStore? = nil
    ) -> IOSLocalToolExecutor {
        IOSLocalToolExecutor(
            permissionStore: permissionStore ?? IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore(),
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            )
        )
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
            localToolExecutor: localToolExecutor(),
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
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        let names = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(names.contains("subagent_dispatch"))
        XCTAssertTrue(names.contains("model_council_run"))
        XCTAssertTrue(names.contains("workspace_file_list"))
        XCTAssertTrue(names.contains("workspace_file_search"))
        XCTAssertFalse(names.contains("workspace_file_write"))
        XCTAssertFalse(names.contains("workspace_file_edit"))
        XCTAssertFalse(names.contains("workspace_file_move"))
        XCTAssertFalse(names.contains("workspace_artifact_delete"))
        XCTAssertTrue(names.contains("wm_tab_list"))
        XCTAssertTrue(names.contains("wm_tab_new"))
        XCTAssertTrue(names.contains("wm_tab_close"))
        XCTAssertTrue(names.contains("wm_observe"))
        XCTAssertTrue(names.contains("wm_visual_snapshot"))
        XCTAssertTrue(names.contains("wm_screenshot"))
        XCTAssertTrue(names.contains("wm_site_add"))
        XCTAssertTrue(names.contains("wm_site_remove"))
        XCTAssertTrue(names.contains("wm_click"))
        XCTAssertTrue(names.contains("wm_tap"))
        XCTAssertTrue(names.contains("wm_type"))
        XCTAssertTrue(names.contains("wm_keys"))
        XCTAssertTrue(names.contains("wm_scroll"))
        XCTAssertTrue(names.contains("wm_select"))
        XCTAssertTrue(names.contains("wm_find"))
        XCTAssertTrue(names.contains("wm_wait"))
    }

    func testWorkspaceWriteToolsRequireExplicitFileWriteRequest() {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )

        viewModel.inputText = "试试 Markdown，写几个格式示例"
        viewModel.sendMessage()

        let markdownDemoNames = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(markdownDemoNames.contains("workspace_file_list"))
        XCTAssertFalse(markdownDemoNames.contains("workspace_file_write"))
        XCTAssertFalse(markdownDemoNames.contains("workspace_file_edit"))

        let explicitWriteViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        explicitWriteViewModel.inputText = "把上面的 Markdown 保存到 Workspace 文件 /workspace/notes/demo.md"
        explicitWriteViewModel.sendMessage()

        let explicitWriteNames = Set(explicitWriteViewModel.currentToolDeclarationNames())
        XCTAssertTrue(explicitWriteNames.contains("workspace_file_write"))
        XCTAssertTrue(explicitWriteNames.contains("workspace_file_edit"))
        XCTAssertTrue(explicitWriteNames.contains("workspace_file_move"))
        XCTAssertTrue(explicitWriteNames.contains("workspace_artifact_delete"))
    }

    func testDisabledAdvancedCapabilityIsNotDeclaredToModel() throws {
        let permissionStore = IOSPermissionStore(userDefaults: isolatedDefaults())
        let subAgent = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.agent.subagent_dispatch" }
        )
        let council = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.agent.model_council_run" }
        )
        permissionStore.setPolicy(.disabled, for: subAgent)
        permissionStore.setPolicy(.disabled, for: council)
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(permissionStore: permissionStore),
            autoGenerateResponses: false
        )
        let names = Set(viewModel.currentToolDeclarationNames())

        XCTAssertFalse(names.contains("subagent_dispatch"))
        XCTAssertFalse(names.contains("model_council_run"))
    }

    func testLocalToolDeclarationsMatchCatalogAndCapabilityRegistry() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        let paramsNames = Set(viewModel.currentToolDeclarationNames())
        let executableNames = IOSCapabilityRegistry.executableToolNames
        let workspaceNames = IOSWorkspaceToolCatalog.supportedToolNames
        let webMountNames = IOSWebMountToolCatalog.supportedToolNames

        XCTAssertEqual(paramsNames.intersection(workspaceNames), IOSWorkspaceToolCatalog.readToolNames)
        XCTAssertEqual(paramsNames.intersection(webMountNames), webMountNames)
        XCTAssertEqual(executableNames.intersection(workspaceNames), workspaceNames)
        XCTAssertEqual(executableNames.intersection(webMountNames), webMountNames)

        viewModel.inputText = "创建 Workspace 文件 /workspace/check.md"
        viewModel.sendMessage()
        let explicitWriteNames = Set(viewModel.currentToolDeclarationNames())
        XCTAssertEqual(explicitWriteNames.intersection(workspaceNames), workspaceNames)
    }
}
