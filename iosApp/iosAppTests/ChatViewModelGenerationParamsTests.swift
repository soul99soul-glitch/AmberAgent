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

    func testAuxiliaryGenerationParamsPreserveSelectedModelOverrides() {
        let json = Kotlinx_serialization_jsonJson.companion
        let model = Model(
            modelId: "aux-model",
            displayName: "Aux Model",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [CustomHeader(name: "X-Aux", value: "enabled")],
            customBodies: [
                CustomBody(
                    key: "service_tier",
                    value: json.parseToJsonElement(string: "\"priority\"")
                )
            ],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )

        let params = ChatViewModel.auxiliaryTextGenerationParamsForTesting(
            model: model,
            assistantHeaders: [CustomHeader(name: "X-Assistant", value: "assistant")],
            assistantBodies: [
                CustomBody(
                    key: "assistant_flag",
                    value: json.parseToJsonElement(string: "true")
                )
            ]
        )

        XCTAssertEqual(params.customHeaders.map(\.name), ["X-Assistant", "X-Aux"])
        XCTAssertEqual(params.customHeaders.map(\.value), ["assistant", "enabled"])
        XCTAssertEqual(params.customBody.map(\.key), ["assistant_flag", "service_tier"])
        XCTAssertEqual(params.customBody.map { $0.value.description }, ["true", "\"priority\""])
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
        XCTAssertTrue(names.contains("ask_user"))
        // Workspace read AND write tools are always declared; the approval gate
        // and the injected workspace policy prompt (not keyword detection) stop
        // unsanctioned writes.
        XCTAssertTrue(names.contains("workspace_file_list"))
        XCTAssertTrue(names.contains("workspace_file_search"))
        XCTAssertTrue(names.contains("workspace_file_write"))
        XCTAssertTrue(names.contains("workspace_file_edit"))
        XCTAssertTrue(names.contains("workspace_file_move"))
        XCTAssertTrue(names.contains("workspace_artifact_delete"))
        // iSH embedded execution (when compiled in) and external handoff are
        // both always declared; the model picks per user intent and the
        // approval gate guards execution.
        XCTAssertTrue(names.contains("ish_handoff"))
        XCTAssertEqual(
            names.intersection(IOSEmbeddedIshToolCatalog.supportedToolNames),
            IOSEmbeddedIshToolCatalog.supportedToolNames
        )
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

    func testEmbeddedIshExecutionAndHandoffBothDeclaredForPlainIshRequest() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        viewModel.inputText = "用内置 iSH 执行 uname -a，并返回 stdout stderr exit code"
        viewModel.sendMessage()

        let names = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(names.contains("ish_handoff"))
        XCTAssertEqual(
            names.intersection(IOSEmbeddedIshToolCatalog.supportedToolNames),
            IOSEmbeddedIshToolCatalog.supportedToolNames
        )
    }

    func testExternalIshHandoffCanBeRequestedExplicitly() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        viewModel.inputText = "交接到外部 iSH App，复制脚本到剪贴板，我手动粘贴执行"
        viewModel.sendMessage()

        let names = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(names.contains("ish_handoff"))
        XCTAssertEqual(
            names.intersection(IOSEmbeddedIshToolCatalog.supportedToolNames),
            IOSEmbeddedIshToolCatalog.supportedToolNames
        )
    }

    /// Workspace write tools are always declared regardless of the latest user
    /// message wording — including a message with no write keyword at all. The
    /// approval gate and the injected workspace policy prompt (not keyword
    /// detection) are what stop unsanctioned writes.
    func testWorkspaceWriteToolsAlwaysDeclaredEvenWithoutWriteRequest() {
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
        XCTAssertEqual(
            markdownDemoNames.intersection(IOSWorkspaceToolCatalog.supportedToolNames),
            IOSWorkspaceToolCatalog.supportedToolNames
        )

        let explicitWriteViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        explicitWriteViewModel.inputText = "把上面的 Markdown 保存到 Workspace 文件 /workspace/notes/demo.md"
        explicitWriteViewModel.sendMessage()

        let explicitWriteNames = Set(explicitWriteViewModel.currentToolDeclarationNames())
        XCTAssertEqual(
            explicitWriteNames.intersection(IOSWorkspaceToolCatalog.supportedToolNames),
            IOSWorkspaceToolCatalog.supportedToolNames
        )
    }

    func testDisabledAdvancedCapabilityIsNotDeclaredToModel() throws {
        let permissionStore = IOSPermissionStore(userDefaults: isolatedDefaults())
        let subAgent = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.agent.subagent_dispatch" }
        )
        let council = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.agent.model_council_run" }
        )
        let ish = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.external.ish_handoff" }
        )
        let embeddedIsh = try XCTUnwrap(
            IOSCapabilityRegistry.capabilities.first { $0.id == "ios.embedded.ish_runtime" }
        )
        permissionStore.setPolicy(.disabled, for: subAgent)
        permissionStore.setPolicy(.disabled, for: council)
        permissionStore.setPolicy(.disabled, for: ish)
        permissionStore.setPolicy(.disabled, for: embeddedIsh)
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(permissionStore: permissionStore),
            autoGenerateResponses: false
        )
        let names = Set(viewModel.currentToolDeclarationNames())

        XCTAssertFalse(names.contains("subagent_dispatch"))
        XCTAssertFalse(names.contains("model_council_run"))
        XCTAssertFalse(names.contains("ish_handoff"))
        XCTAssertFalse(names.contains("ios_ish_execute"))
    }

    /// G7: 前台工具循环上限参数化——默认 12，clamp 4-24，UserDefaults 持久化。
    func testChatMaxToolResumeCountDefaultsToTwelveAndClamps() {
        let defaults = isolatedDefaults()
        let store = SettingsStore(userDefaults: defaults)

        XCTAssertEqual(store.chatMaxToolResumeCount, 12)

        store.chatMaxToolResumeCount = 3
        XCTAssertEqual(store.chatMaxToolResumeCount, 4)

        store.chatMaxToolResumeCount = 100
        XCTAssertEqual(store.chatMaxToolResumeCount, 24)

        store.chatMaxToolResumeCount = 9
        XCTAssertEqual(SettingsStore(userDefaults: defaults).chatMaxToolResumeCount, 9)
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
        let ishNames = IOSIshToolCatalog.supportedToolNames
        let embeddedIshNames = IOSEmbeddedIshToolCatalog.supportedToolNames
        let webMountNames = IOSWebMountToolCatalog.supportedToolNames

        XCTAssertEqual(paramsNames.intersection(workspaceNames), workspaceNames)
        XCTAssertEqual(paramsNames.intersection(ishNames), ishNames)
        XCTAssertEqual(paramsNames.intersection(embeddedIshNames), embeddedIshNames)
        XCTAssertEqual(paramsNames.intersection(webMountNames), webMountNames)
        XCTAssertEqual(executableNames.intersection(workspaceNames), workspaceNames)
        XCTAssertEqual(executableNames.intersection(ishNames), ishNames)
        XCTAssertEqual(executableNames.intersection(embeddedIshNames), embeddedIshNames)
        XCTAssertEqual(executableNames.intersection(webMountNames), webMountNames)

        viewModel.inputText = "创建 Workspace 文件 /workspace/check.md"
        viewModel.sendMessage()
        let explicitWriteNames = Set(viewModel.currentToolDeclarationNames())
        XCTAssertEqual(explicitWriteNames.intersection(workspaceNames), workspaceNames)

        let skillViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        skillViewModel.inputText = "生成一份技能说明"
        skillViewModel.sendMessage()
        let skillWriteNames = Set(skillViewModel.currentToolDeclarationNames())
        XCTAssertEqual(skillWriteNames.intersection(workspaceNames), workspaceNames)
    }
}
