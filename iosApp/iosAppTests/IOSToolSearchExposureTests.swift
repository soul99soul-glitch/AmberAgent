import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P0-a: iOS chat wiring of the shared KMP tool_search / ToolExposureState
/// mechanism. Heavy configs (>40 declared tools) enter lazy mode: the first
/// round exposes only the iOS resident set + tool_search; a tool_search call
/// executes locally (no network) and its `expanded_tools` become callable on
/// the NEXT round. Light configs (≤40) keep the pre-change full declaration.
@MainActor
final class IOSToolSearchExposureTests: XCTestCase {

    private func isolatedDefaults() -> UserDefaults {
        let suite = "IOSToolSearchExposureTests-\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }

    private func localToolExecutor() -> IOSLocalToolExecutor {
        IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore(),
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            )
        )
    }

    /// Deferred tool names per the KMP IOS_RESIDENT_TOOL_NAMES contract.
    private let deferredToolNames: Set<String> = [
        "wm_stations", "wm_tab_list", "wm_tab_new", "wm_tab_close", "wm_open",
        "wm_state", "wm_observe", "wm_extract", "wm_get", "wm_visual_snapshot",
        "wm_screenshot", "wm_back", "wm_forward", "wm_clear_session", "wm_site_add",
        "wm_site_remove", "wm_click", "wm_tap", "wm_type", "wm_keys", "wm_scroll",
        "wm_select", "wm_find", "wm_wait",
        "ish_handoff", "ios_ish_execute",
        "mcp_test", "mcp_import_from_skill",
        "skill_validate", "skill_import", "skill_enable", "skill_disable",
        "subagent_report",
    ]

    private func fullIosDeclarations() -> [Tool] {
        let names =
            IOSWorkspaceToolCatalog.supportedToolNames
            .union(IOSIshToolCatalog.supportedToolNames)
            .union(IOSEmbeddedIshToolCatalog.supportedToolNames)
            .union(IOSWebMountToolCatalog.supportedToolNames)
            .union(IOSSkillToolCatalog.toolNames)
            .union(IOSMcpManagementToolCatalog.toolNames)
            .union([
                "search_web", "scrape_web", "memory_tool", "generate_image",
                "mcp_call", "subagent_dispatch", "model_council_run", "ask_user",
            ])
        return ToolKt.iosToolDeclarations(names: Array(names).sorted())
    }

    // MARK: - Heavy config (default >40 declared tools → lazy mode)

    func testHeavyConfigFirstRoundExposesOnlyResidentToolsPlusSearch() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        let names = Set(viewModel.currentToolDeclarationNames())
        let bridge = viewModel.toolExposureBridgeForTesting()

        XCTAssertNotNil(bridge, "makeTextGenerationParams must assemble the run bridge")
        XCTAssertEqual(bridge?.lazyModeEnabled(), true, "default chat declares >40 tools → lazy mode")

        // Resident (declared) tools must be visible on the first round.
        let residentDeclared: Set<String> = [
            "tool_search",
            "search_web", "scrape_web", "memory_tool", "ask_user",
            "workspace_file_read", "workspace_file_write", "workspace_file_edit",
            "workspace_file_list", "workspace_file_search", "workspace_file_move",
            "workspace_artifact_read", "workspace_artifact_delete",
            "mcp_call", "mcp_list", "mcp_describe_tool",
            "skills_list", "use_skill",
        ]
        XCTAssertTrue(residentDeclared.isSubset(of: names), "missing resident tools: \(residentDeclared.subtracting(names))")

        // Deferred tools must NOT be declared on the first round.
        let deferredPresent = deferredToolNames.intersection(names)
        XCTAssertTrue(deferredPresent.isEmpty, "deferred tools must be hidden until tool_search exposes them: \(deferredPresent)")
    }

    func testToolSearchHitBecomesCallableOnNextRound() throws {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        let firstRoundNames = Set(viewModel.currentToolDeclarationNames())
        XCTAssertFalse(firstRoundNames.contains("wm_type"))
        let bridge = try XCTUnwrap(viewModel.toolExposureBridgeForTesting())

        // Model calls tool_search (pure local execution inside the bridge).
        let payload = bridge.executeToolSearch(argumentsJson: #"{"query":"wm_type","limit":1}"#)
        XCTAssertTrue(payload.contains("expanded_tools"))
        XCTAssertTrue(payload.contains("wm_type"))

        // The coordinator re-derives every round's params from the same run
        // bridge, so the hit is declared on the very next round.
        let nextRoundParams = viewModel.textGenerationParamsForTesting().replacingTools(bridge.visibleTools())
        XCTAssertTrue(nextRoundParams.tools.map(\.name).contains("wm_type"))
        XCTAssertFalse(nextRoundParams.tools.map(\.name).contains("wm_screenshot"))
    }

    // MARK: - S1: P1-d 三工具进生产目录（bridge 全目录 → tool_search 命中 → 可路由）

    func testOrchestrationToolsInFullCatalogAreSearchHitAndRoutable() throws {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        _ = viewModel.currentToolDeclarationNames()
        let bridge = try XCTUnwrap(viewModel.toolExposureBridgeForTesting())

        // 桥全目录含六工具（生产组装点：makeTextGenerationParams 的声明列表）。
        let fullNames = Set(bridge.fullToolDeclarations().map(\.name))
        for name in ["spawn_agent", "list_agents", "interrupt_agent",
                     "send_message", "followup_task", "wait_agent"] {
            XCTAssertTrue(fullNames.contains(name), "线程编排工具必须进生产目录（bridge 全目录），缺: \(name)")
        }
        // 非常驻：首轮不可见（deferred 池，命中后才进 params.tools）。
        let firstRound = Set(bridge.visibleTools().map(\.name))
        XCTAssertFalse(firstRound.contains("send_message"), "send_message 首轮不可见（deferred）")
        XCTAssertFalse(firstRound.contains("wait_agent"), "wait_agent 首轮不可见（deferred）")

        // tool_search 中文 query「子代理」命中六工具（KMP ToolSearch 词条）。
        let payload = bridge.executeToolSearch(argumentsJson: #"{"query":"子代理","limit":10}"#)
        for name in ["spawn_agent", "list_agents", "interrupt_agent",
                     "send_message", "followup_task", "wait_agent"] {
            XCTAssertTrue(payload.contains(name), "中文 query 必须命中 \(name)")
        }

        // 次轮 visibleTools() 含命中（同桥实例、同 run 生命周期）。
        let nextRound = Set(bridge.visibleTools().map(\.name))
        XCTAssertTrue(nextRound.contains("send_message"))
        XCTAssertTrue(nextRound.contains("followup_task"))
        XCTAssertTrue(nextRound.contains("wait_agent"))

        // ChatToolRuntime 分类可路由：命中后的 send_message 调用被 nextPendingToolCall
        // 识别为 advanced（编排执行路径），不会落「未知名硬失败」。
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
        let toolCall = UIMessagePart.Tool(
            toolCallId: "sm-1",
            toolName: "send_message",
            input: #"{"target":"child","message":"hi"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let seed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: seed.id,
            role: seed.role,
            parts: [toolCall],
            annotations: [],
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )
        let pending = runtime.nextPendingToolCall(
            in: [UIMessage.companion.user(prompt: "hi"), assistant],
            availableToolNames: nextRound
        )
        guard let pending else {
            return XCTFail("命中后的 send_message 必须被 ChatToolRuntime 分类为可路由工具调用")
        }
        guard case .advanced = pending.kind else {
            return XCTFail("send_message 必须路由为 advanced 编排路径，实际: \(pending.kind)")
        }
    }

    // MARK: - M5: tools_list 声明 + 本地执行

    func testToolsListIsDeclaredResidentAndExecutesLocally() throws {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        let names = Set(viewModel.currentToolDeclarationNames())
        // discovery 引导教模型用 tools_list——生产目录必须声明它（常驻，首轮可见）。
        XCTAssertTrue(names.contains("tools_list"), "tools_list 必须进生产目录（resident）")

        let bridge = try XCTUnwrap(viewModel.toolExposureBridgeForTesting())
        let payload = bridge.executeToolsList()
        XCTAssertTrue(payload.contains("\"status\":\"ok\""), "tools_list 本地执行必须返回 ok，实际: \(payload)")
        XCTAssertTrue(payload.contains("wm_type"), "目录必须含全量（含 deferred 的 wm_*），缺 wm_type")
        XCTAssertTrue(payload.contains("send_message"), "目录必须含线程编排工具")
        XCTAssertTrue(payload.contains("tool_search"), "目录必须含 tool_search")
    }

    // MARK: - Fix A: discovery guidance injection into the runtime context

    func testLazyRunInjectsDiscoveryGuidanceIntoUploadContext() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        // Assemble the run bridge first (heavy config → lazy mode).
        let names = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(names.contains("tool_search"))
        XCTAssertEqual(viewModel.toolExposureBridgeForTesting()?.lazyModeEnabled(), true)

        let uploadMessages = viewModel.preparedUploadMessagesForTesting([
            UIMessage.companion.user(prompt: "hello")
        ])
        let systemText = uploadMessages
            .filter { $0.role == MessageRole.system }
            .map { $0.toText() }
            .joined(separator: "\n")
        XCTAssertTrue(
            systemText.contains("not callable until"),
            "lazy run must inject the tool discovery guidance into the upload context"
        )
        XCTAssertTrue(systemText.contains("tool_search"))
    }

    func testNonLazyRunDoesNotInjectDiscoveryGuidance() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            autoGenerateResponses: false
        )
        _ = viewModel.currentToolDeclarationNames()
        XCTAssertEqual(viewModel.toolExposureBridgeForTesting()?.lazyModeEnabled(), false)

        let uploadMessages = viewModel.preparedUploadMessagesForTesting([
            UIMessage.companion.user(prompt: "hello")
        ])
        let systemText = uploadMessages
            .filter { $0.role == MessageRole.system }
            .map { $0.toText() }
            .joined(separator: "\n")
        XCTAssertFalse(
            systemText.contains("not callable until"),
            "non-lazy runs must not inject discovery guidance"
        )
    }

    // MARK: - Fix B: unexposed-but-known tool calls soft-fail with guidance

    func testUnexposedKnownToolCallIsSoftFailedWithDiscoveryGuidance() throws {
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
        let fullCatalog = fullIosDeclarations()
        let bridge = IosToolExposureBridge(tools: fullCatalog)
        XCTAssertTrue(bridge.lazyModeEnabled())
        let fullCatalogNames = Set(fullCatalog.map(\.name))
        let visibleNames = Set(bridge.visibleTools().map(\.name))
        XCTAssertFalse(visibleNames.contains("wm_type"))

        // The model called a tool that EXISTS in the full catalog but was never
        // exposed this round (wm_type is deferred behind tool_search).
        let toolCall = UIMessagePart.Tool(
            toolCallId: "unexposed-1",
            toolName: "wm_type",
            input: #"{"url":"https://example.com"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let seed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: seed.id,
            role: seed.role,
            parts: [toolCall],
            annotations: [],
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )
        let messages = [UIMessage.companion.user(prompt: "hi"), assistant]

        let guided = try XCTUnwrap(
            runtime.messagesByGuidingUnexposedToolCalls(
                in: messages,
                fullCatalogNames: fullCatalogNames,
                visibleToolNames: visibleNames
            ),
            "a known-but-unexposed tool call must be guided, not hard-failed"
        )
        XCTAssertFalse(
            runtime.hasUnresolvedToolCall(in: guided.messages),
            "after soft-fail the part is filled, so the run continues instead of failing"
        )
        let outputText = guided.messages
            .flatMap { $0.parts.compactMap { ($0 as? UIMessagePart.Tool)?.output.compactMap { ($0 as? UIMessagePart.Text)?.text } } }
            .flatMap { $0 }
            .joined()
        XCTAssertTrue(outputText.contains("tool_search"), "guidance must point the model at tool_search")
        XCTAssertTrue(outputText.contains("未暴露"), "guidance must explain the tool was not exposed this round")
        XCTAssertTrue(outputText.contains("\"status\":\"failed\""), "the soft-failed part must carry status=failed")
    }

    func testUnknownToolNameKeepsHardFailSemantics() throws {
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
        let fullCatalog = fullIosDeclarations()
        let bridge = IosToolExposureBridge(tools: fullCatalog)
        let fullCatalogNames = Set(fullCatalog.map(\.name))
        let visibleNames = Set(bridge.visibleTools().map(\.name))

        // A name that is in NEITHER the full catalog NOR the visible set stays
        // on the existing hard-fail path (unresolved tool call).
        let toolCall = UIMessagePart.Tool(
            toolCallId: "unknown-1",
            toolName: "definitely_not_a_real_tool",
            input: "{}",
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let seed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: seed.id,
            role: seed.role,
            parts: [toolCall],
            annotations: [],
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )
        let messages = [UIMessage.companion.user(prompt: "hi"), assistant]

        let guided = runtime.messagesByGuidingUnexposedToolCalls(
            in: messages,
            fullCatalogNames: fullCatalogNames,
            visibleToolNames: visibleNames
        )
        XCTAssertNil(guided, "unknown tool names must NOT be soft-failed")
        XCTAssertTrue(runtime.hasUnresolvedToolCall(in: messages), "the unknown call stays unresolved → existing hard fail")
    }

    func testVisibleToolCallIsNotTouchedByGuidancePath() throws {
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
        let fullCatalog = fullIosDeclarations()
        let bridge = IosToolExposureBridge(tools: fullCatalog)
        let fullCatalogNames = Set(fullCatalog.map(\.name))
        let visibleNames = Set(bridge.visibleTools().map(\.name))
        XCTAssertTrue(visibleNames.contains("search_web"))

        let toolCall = UIMessagePart.Tool(
            toolCallId: "visible-1",
            toolName: "search_web",
            input: #"{"query":"x"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let seed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: seed.id,
            role: seed.role,
            parts: [toolCall],
            annotations: [],
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )
        let messages = [UIMessage.companion.user(prompt: "hi"), assistant]

        let guided = runtime.messagesByGuidingUnexposedToolCalls(
            in: messages,
            fullCatalogNames: fullCatalogNames,
            visibleToolNames: visibleNames
        )
        XCTAssertNil(guided, "a visible tool call is handled by the normal execution path, not the guidance path")
    }

    func testToolSearchExecutesLocallyWithoutNetwork() async throws {
        let transport = CountingSearchTransport()
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: transport,
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
        let bridge = IosToolExposureBridge(tools: fullIosDeclarations())
        let provider = IOSCouncilRoomRunner.makeProviderSetting(
            baseUrl: "https://example.com/v1",
            apiKey: "test-key"
        )
        let model = Model(
            modelId: "tool-search-test",
            displayName: "Tool Search Test",
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
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: bridge.visibleTools(),
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let toolCall = UIMessagePart.Tool(
            toolCallId: "tool-search-1",
            toolName: "tool_search",
            input: #"{"query":"wm_type","limit":1}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let assistantSeed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: assistantSeed.id,
            role: assistantSeed.role,
            parts: [toolCall],
            annotations: assistantSeed.annotations,
            createdAt: assistantSeed.createdAt,
            finishedAt: assistantSeed.finishedAt,
            modelId: assistantSeed.modelId,
            usage: assistantSeed.usage,
            translation: assistantSeed.translation
        )
        let context = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: provider,
            params: params,
            runId: "run-1",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [assistant]
        )

        let result = await runtime.execute(
            ChatPendingToolCall(kind: .toolSearch, toolCall: toolCall),
            context: context,
            toolExposureBridge: bridge
        )
        guard case .completed(let messages) = result else {
            return XCTFail("tool_search must complete locally, got \(result)")
        }
        let outputText = messages.flatMap { $0.parts.compactMap { ($0 as? UIMessagePart.Tool)?.output.compactMap { ($0 as? UIMessagePart.Text)?.text } } }
            .flatMap { $0 }
            .joined()
        XCTAssertTrue(outputText.contains("expanded_tools"))
        XCTAssertTrue(outputText.contains("wm_type"))
        XCTAssertEqual(transport.requests.count, 0, "tool_search must not produce any network request")
        // The bridge exposed the hit for the next round.
        XCTAssertTrue(bridge.visibleTools().map(\.name).contains("wm_type"))
    }

    func testBackgroundExecutorsRegisterToolSearchWithTheJobBridge() async throws {
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
        let bridge = IosToolExposureBridge(tools: fullIosDeclarations())
        let provider = IOSCouncilRoomRunner.makeProviderSetting(
            baseUrl: "https://example.com/v1",
            apiKey: "test-key"
        )
        let model = Model(
            modelId: "bg-tool-search-test",
            displayName: "BG Tool Search Test",
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
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: bridge.visibleTools(),
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let executors = runtime.backgroundToolExecutors(
            providerSetting: provider,
            params: params,
            runId: "run-bg-1",
            toolExposureBridge: bridge
        )
        let executor = UncheckedToolExecutorBox(try XCTUnwrap(executors["tool_search"]))
        let outcome = await executor.execute(name: "tool_search", arguments: #"{"query":"wm_type","limit":1}"#, isUserInitiated: false)
        guard case .filled(let output) = outcome else {
            return XCTFail("background tool_search must fill locally, got \(outcome)")
        }
        XCTAssertTrue(output.contains("expanded_tools"))
        XCTAssertTrue(output.contains("wm_type"))
    }

    func testBackgroundBridgeSeedsExposureFromHandoffVisibleTools() {
        // Fix follow-up: the rebuilt background bridge must start from the
        // handoff's visible set, otherwise the per-round replacingTools
        // refresh drops foreground-exposed tools from round 2 onward.
        let all = fullIosDeclarations()
        let handoffVisible = all.filter { $0.name == "wm_type" }
        let bridge = IOSChatBackgroundGenerationCoordinator.makeBackgroundToolExposureBridge(
            fullToolNames: all.map(\.name),
            handoffVisibleTools: handoffVisible
        )
        XCTAssertTrue(bridge.lazyModeEnabled(), "full catalog must keep lazy mode on")
        let visibleNames = Set(bridge.visibleTools().map(\.name))
        XCTAssertTrue(visibleNames.contains("wm_type"), "foreground-exposed tool must stay visible in background")
        XCTAssertFalse(visibleNames.contains("wm_click"), "unexposed deferred tools must stay hidden")
    }

    // MARK: - Light config (≤40 declared tools → bypass mode)

    func testLightConfigKeepsFullDeclarationList() {
        // Without a local tool executor the declared set is small enough to
        // stay below the 40-tool threshold: every declared tool plus the
        // bridge-appended tool_search is visible, exactly like pre-P0-a.
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            autoGenerateResponses: false
        )
        let names = Set(viewModel.currentToolDeclarationNames())
        let bridge = viewModel.toolExposureBridgeForTesting()

        XCTAssertEqual(bridge?.lazyModeEnabled(), false, "light config must bypass lazy mode")
        let staticDeclarations: Set<String> = [
            "search_web", "scrape_web", "memory_tool", "ask_user",
            "mcp_call", "mcp_list", "mcp_test", "mcp_describe_tool", "mcp_import_from_skill",
            "skills_list", "use_skill", "skill_validate", "skill_import", "skill_enable", "skill_disable",
            "subagent_dispatch", "model_council_run",
            // P1-c/P1-d: 线程编排六工具追加进 iOS 声明面（非常驻；轻配置 bypass 模式
            // 下与其余声明一起全量可见——阈值内行为契约随声明面扩展而更新）。
            "spawn_agent", "list_agents", "interrupt_agent",
            "send_message", "followup_task", "wait_agent",
            // 跨会话读取工具（非常驻；轻配置 bypass 模式全量可见——阈值内行为
            // 契约随声明面扩展而更新，与 P1-c/P1-d 同一模式）。
            "session_search", "session_read",
            // M5: discovery 引导引用的 tools_list（常驻目录工具）。
            "tools_list",
        ]
        XCTAssertEqual(
            names,
            staticDeclarations.union(["tool_search"]),
            "below threshold every declared tool plus tool_search must be visible"
        )
    }

    // MARK: - Kotlin→Swift interop smoke

    func testBridgeKotlinSwiftExportShape() throws {
        let bridge = IosToolExposureBridge(tools: fullIosDeclarations())

        XCTAssertTrue(bridge.lazyModeEnabled())
        let visible = bridge.visibleTools()
        XCTAssertFalse(visible.isEmpty)
        XCTAssertTrue(visible.map(\.name).contains("tool_search"))
        let full = bridge.fullToolDeclarations()
        XCTAssertGreaterThan(full.count, visible.count)
        let summary = bridge.savingsSummary()
        XCTAssertTrue(summary.contains("estimated_full_schema_chars"))
        XCTAssertTrue(summary.contains("estimated_visible_schema_chars"))
        bridge.exposeToolNames(names: ["wm_type"])
        XCTAssertTrue(bridge.visibleTools().map(\.name).contains("wm_type"))
    }
}

/// Minimal IOSSearchHTTPTransport double that counts requests and never
/// touches the network (tool_search must not drive any search request).
private final class CountingSearchTransport: IOSSearchHTTPTransport {
    private(set) var requests: [URLRequest] = []

    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        requests.append(request)
        let http = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/html; charset=utf-8"]
        )!
        return (http, Data())
    }
}

/// IOSToolExecutor is not Sendable; the executor dict value cannot cross the
/// async boundary without a box (same pattern as IOSToolRuntimeTests).
private final class UncheckedToolExecutorBox: @unchecked Sendable {
    private let base: any IOSToolExecutor

    init(_ base: any IOSToolExecutor) {
        self.base = base
    }

    func execute(
        name: String,
        arguments: String,
        isUserInitiated: Bool
    ) async -> IOSAgentToolOutcome {
        await base.execute(
            name: name,
            arguments: arguments,
            isUserInitiated: isUserInitiated
        )
    }
}
