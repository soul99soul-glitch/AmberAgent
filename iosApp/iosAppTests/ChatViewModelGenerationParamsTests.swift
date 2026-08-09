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
    /// after the params refactor (regression guard). P0-a: the default config
    /// declares >40 tools, so lazy mode hides the deferred set (wm_*, iSH,
    /// skill management) behind tool_search on the first round; the resident
    /// iOS core tools stay declared.
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
        // P0-a: the discovery tool itself is always resident.
        XCTAssertTrue(names.contains("tool_search"))
        // Workspace read AND write tools are always declared; the approval gate
        // and the injected workspace policy prompt (not keyword detection) stop
        // unsanctioned writes.
        XCTAssertTrue(names.contains("workspace_file_list"))
        XCTAssertTrue(names.contains("workspace_file_search"))
        XCTAssertTrue(names.contains("workspace_file_write"))
        XCTAssertTrue(names.contains("workspace_file_edit"))
        XCTAssertTrue(names.contains("workspace_file_move"))
        XCTAssertTrue(names.contains("workspace_artifact_delete"))
        // P0-a: iSH execution/handoff and the WebMount catalog are deferred in
        // the default (>40 tools) config — the model reaches them by calling
        // tool_search with a concrete query, not on the first round.
        XCTAssertFalse(names.contains("ish_handoff"))
        XCTAssertTrue(names.isDisjoint(with: IOSEmbeddedIshToolCatalog.supportedToolNames))
        XCTAssertTrue(names.isDisjoint(with: IOSWebMountToolCatalog.supportedToolNames))
    }

    /// P0-a: iSH tools are no longer first-round declarations in the default
    /// heavy config — they are discovered via tool_search (which returns them
    /// as expanded_tools for the next step).
    func testIshToolsDeferredBehindToolSearchForPlainIshRequest() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        viewModel.inputText = "用内置 iSH 执行 uname -a，并返回 stdout stderr exit code"
        viewModel.sendMessage()

        let names = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(names.contains("tool_search"))
        XCTAssertFalse(names.contains("ish_handoff"))
        XCTAssertTrue(names.isDisjoint(with: IOSEmbeddedIshToolCatalog.supportedToolNames))

        let bridge = viewModel.toolExposureBridgeForTesting()
        let payload = bridge?.executeToolSearch(argumentsJson: #"{"query":"ish_handoff","limit":1}"#) ?? ""
        XCTAssertTrue(payload.contains("ish_handoff"))
    }

    func testExternalIshHandoffDiscoverableViaToolSearch() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        viewModel.inputText = "交接到外部 iSH App，复制脚本到剪贴板，我手动粘贴执行"
        viewModel.sendMessage()

        let names = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(names.contains("tool_search"))
        XCTAssertFalse(names.contains("ish_handoff"))

        let bridge = viewModel.toolExposureBridgeForTesting()
        let payload = bridge?.executeToolSearch(argumentsJson: #"{"query":"ish_handoff","limit":1}"#) ?? ""
        XCTAssertTrue(payload.contains("ish_handoff"))
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

    // MARK: - P3-a: exec 工具开关（默认关，开时进 deferred 池）

    func testExecSwitchDefaultsToOffAndPersists() {
        let defaults = isolatedDefaults()
        let store = SettingsStore(userDefaults: defaults)
        XCTAssertFalse(store.execJavaScriptEnabled, "exec 开关默认关")

        store.execJavaScriptEnabled = true
        XCTAssertTrue(SettingsStore(userDefaults: defaults).execJavaScriptEnabled, "开关必须持久化")
    }

    func testExecZeroTraceInFullCatalogWhenSwitchOff() {
        let defaults = isolatedDefaults()
        let settingsStore = SettingsStore(userDefaults: defaults)
        settingsStore.execJavaScriptEnabled = false
        let viewModel = ChatViewModel(
            settingsStore: settingsStore,
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        let names = Set(viewModel.currentToolDeclarationNames())
        XCTAssertFalse(names.contains("exec"), "开关关时首轮声明不得含 exec")

        // 零痕迹也覆盖桥输入全目录——tool_search 也搜不到 exec。
        let bridge = viewModel.toolExposureBridgeForTesting()
        let fullNames = bridge?.fullToolDeclarations().map(\.name) ?? []
        XCTAssertFalse(fullNames.contains("exec"), "开关关时全目录也不得含 exec")
    }

    func testExecEntersDeferredPoolWhenSwitchOn() {
        let defaults = isolatedDefaults()
        let settingsStore = SettingsStore(userDefaults: defaults)
        settingsStore.execJavaScriptEnabled = true
        let viewModel = ChatViewModel(
            settingsStore: settingsStore,
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        let names = Set(viewModel.currentToolDeclarationNames())
        XCTAssertFalse(names.contains("exec"), "exec 非常驻：首轮声明不含（deferred 池）")

        let bridge = viewModel.toolExposureBridgeForTesting()
        let fullNames = bridge?.fullToolDeclarations().map(\.name) ?? []
        XCTAssertTrue(fullNames.contains("exec"), "开关开时 exec 必须进桥输入全目录")

        // tool_search 精确命中后，exec 进入下轮声明。
        let payload = bridge?.executeToolSearch(argumentsJson: #"{"query":"exec","limit":1}"#) ?? ""
        XCTAssertTrue(payload.contains("exec"), "tool_search 必须能命中 exec")
        let visible = bridge?.visibleTools().map(\.name) ?? []
        XCTAssertTrue(visible.contains("exec"), "命中后 exec 必须出现在下轮可见声明")
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

        // Workspace tools are resident and always declared; iSH + WebMount are
        // deferred behind tool_search in the default (>40 tools) config.
        XCTAssertEqual(paramsNames.intersection(workspaceNames), workspaceNames)
        XCTAssertTrue(paramsNames.isDisjoint(with: ishNames))
        XCTAssertTrue(paramsNames.isDisjoint(with: embeddedIshNames))
        XCTAssertTrue(paramsNames.isDisjoint(with: webMountNames))
        XCTAssertEqual(executableNames.intersection(workspaceNames), workspaceNames)
        XCTAssertEqual(executableNames.intersection(ishNames), ishNames)
        XCTAssertEqual(executableNames.intersection(embeddedIshNames), embeddedIshNames)
        XCTAssertEqual(executableNames.intersection(webMountNames), webMountNames)

        // tool_search is the discovery path for the deferred catalogs.
        XCTAssertTrue(paramsNames.contains("tool_search"))
        let bridge = viewModel.toolExposureBridgeForTesting()
        let payload = bridge?.executeToolSearch(argumentsJson: #"{"query":"wm_tab_list","limit":1}"#) ?? ""
        XCTAssertTrue(payload.contains("wm_tab_list"))
        XCTAssertTrue(bridge?.visibleTools().map(\.name).contains("wm_tab_list") ?? false)

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
