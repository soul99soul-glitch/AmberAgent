import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P0-b: flattened `mcp__{server}__{tool}` declarations + prefix routing.
/// Contract: discovered MCP tools become independent declarations that are
/// deferred behind tool_search (first round never contains `mcp__*`); a call
/// routes by prefix to the correct server/tool; unexposed-but-known names
/// soft-fail with tool_search guidance; stale names get an honest error;
/// approval semantics match mcp_call; background executors register the same
/// surface; MCP off keeps the P0-a baseline byte-for-byte.
@MainActor
final class IOSMcpExpandedToolTests: XCTestCase {

    // MARK: - Fixtures

    private func isolatedDefaults() -> UserDefaults {
        UserDefaults(suiteName: "IOSMcpExpandedToolTests-\(UUID().uuidString)")!
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

    private func twoServerManager() -> IOSMcpManager {
        IOSMcpManager(serverProvider: {
            [
                .streamableHTTP(
                    name: "alpha",
                    url: "https://example.com/alpha",
                    tools: [
                        IOSMcpTool(
                            name: "search",
                            description: "Alpha search",
                            inputSchema: #"{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"#
                        ),
                        IOSMcpTool(name: "count", description: "Alpha count"),
                    ]
                ),
                .streamableHTTP(
                    name: "beta",
                    url: "https://example.com/beta",
                    tools: [
                        IOSMcpTool(name: "list", description: "Beta list"),
                        IOSMcpTool(name: "ping", description: nil),
                    ]
                ),
            ]
        })
    }

    private func expandedToolCall(name: String, input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "expanded-\(name)-\(UUID().uuidString)",
            toolName: name,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func makeContext(toolCall: UIMessagePart.Tool) -> ChatPendingToolApproval {
        let provider = IOSCouncilRoomRunner.makeProviderSetting(
            baseUrl: "https://example.com/v1",
            apiKey: "test-key"
        )
        let model = Model(
            modelId: "mcp-expanded-test",
            displayName: "MCP Expanded Test",
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
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let seed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: seed.id,
            role: seed.role,
            parts: [toolCall],
            annotations: seed.annotations,
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )
        return ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: provider,
            params: params,
            runId: "run-expanded",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [assistant]
        )
    }

    private func toolOutputText(_ messages: [UIMessage]) -> String {
        messages.flatMap { $0.parts.compactMap { ($0 as? UIMessagePart.Tool)?.output.compactMap { ($0 as? UIMessagePart.Text)?.text } } }
            .flatMap { $0 }
            .joined()
    }

    private func withHighRiskAutoApprove(_ enabled: Bool, _ body: () async throws -> Void) async throws {
        let key = "app.amber.ios.highRiskAutoApprove"
        let previous = UserDefaults.standard.object(forKey: key)
        UserDefaults.standard.set(enabled, forKey: key)
        defer {
            if let previous {
                UserDefaults.standard.set(previous, forKey: key)
            } else {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }
        try await body()
    }

    // MARK: - Declarations: deferred by default, searchable, mcp_call stays resident

    func testExpandedMcpDeclarationsAreDeferredAndSearchable() throws {
        let manager = twoServerManager()
        manager.refreshFromCurrentSettings()
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false,
            mcpManager: manager
        )
        let firstRound = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(firstRound.contains("tool_search"))
        XCTAssertTrue(firstRound.contains("mcp_call"), "the mcp_call passthrough stays resident")
        XCTAssertFalse(
            firstRound.contains(where: { $0.hasPrefix("mcp__") }),
            "expanded MCP tools must be deferred behind tool_search on the first round"
        )

        let bridge = try XCTUnwrap(viewModel.toolExposureBridgeForTesting())
        let fullNames = Set(bridge.fullToolDeclarations().map(\.name))
        for name in ["mcp__alpha__search", "mcp__alpha__count", "mcp__beta__list", "mcp__beta__ping"] {
            XCTAssertTrue(fullNames.contains(name), "\(name) must be in the run's full catalog")
        }

        // Exact-name tool_search exposes the hit for the next model step.
        let payload = bridge.executeToolSearch(argumentsJson: #"{"query":"mcp__alpha__search","limit":1}"#)
        XCTAssertTrue(payload.contains("mcp__alpha__search"), payload)
        XCTAssertTrue(
            bridge.visibleTools().map(\.name).contains("mcp__alpha__search"),
            "the tool_search hit must be callable on the next step"
        )
    }

    func testDisabledServerToolsAreNotDeclared() throws {
        let manager = IOSMcpManager(serverProvider: {
            [
                .streamableHTTP(
                    name: "off",
                    url: "https://example.com/off",
                    enabled: false,
                    tools: [IOSMcpTool(name: "hidden", description: "Hidden tool")]
                ),
                .streamableHTTP(
                    name: "on",
                    url: "https://example.com/on",
                    enabled: true,
                    tools: [IOSMcpTool(name: "visible_tool", description: "Visible tool")]
                ),
            ]
        })
        manager.refreshFromCurrentSettings()
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false,
            mcpManager: manager
        )
        // Assemble the run bridge (makeTextGenerationParams) first.
        _ = viewModel.currentToolDeclarationNames()
        let fullNames = try XCTUnwrap(viewModel.toolExposureBridgeForTesting())
            .fullToolDeclarations().map(\.name)
        XCTAssertTrue(fullNames.contains("mcp__on__visible_tool"))
        XCTAssertFalse(fullNames.contains("mcp__off__hidden"), "disabled servers must not contribute declarations")
    }

    // MARK: - Execution routing: prefix → directory lookup → mcpManager.callTool

    func testExpandedToolCallRoutesToTheCorrectServerAndTool() async throws {
        let alphaClient = RecordingMcpClient(tools: [IOSMcpTool(name: "search", description: "Alpha search")])
        let betaClient = RecordingMcpClient(tools: [IOSMcpTool(name: "list", description: "Beta list")])
        let manager = IOSMcpManager(
            serverProvider: {
                [
                    .streamableHTTP(name: "alpha", url: "https://example.com/alpha", tools: [IOSMcpTool(name: "search", description: "Alpha search")]),
                    .streamableHTTP(name: "beta", url: "https://example.com/beta", tools: [IOSMcpTool(name: "list", description: "Beta list")]),
                ]
            },
            clientFactory: { config in config.name == "alpha" ? alphaClient : betaClient }
        )
        manager.refreshFromCurrentSettings()
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: manager
        )
        let context = makeContext(toolCall: expandedToolCall(name: "mcp__alpha__search", input: #"{"query":"hello"}"#))

        try await withHighRiskAutoApprove(true) {
            let result = await runtime.execute(
                ChatPendingToolCall(kind: .advanced, toolCall: context.toolCall),
                context: context
            )
            guard case .completed = result else {
                return XCTFail("expanded MCP call must complete, got \(result)")
            }
            XCTAssertEqual(alphaClient.calls.count, 1, "the call must route to the alpha server")
            XCTAssertEqual(alphaClient.calls.first?.name, "search")
            XCTAssertEqual(alphaClient.calls.first?.arguments["query"] as? String, "hello")
            XCTAssertTrue(betaClient.calls.isEmpty, "beta must not receive the alpha call")
        }
    }

    // MARK: - Soft-fail / hard-fail boundaries (P0-a Fix B)

    func testUnexposedExpandedMcpToolCallIsSoftFailedWithGuidance() throws {
        let manager = twoServerManager()
        manager.refreshFromCurrentSettings()
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: manager
        )
        let fullCatalog = fullIosDeclarations() + expandedMcpToolDeclarations(mcpManager: manager)
        let bridge = IosToolExposureBridge(tools: fullCatalog)
        XCTAssertTrue(bridge.lazyModeEnabled())
        let fullCatalogNames = Set(fullCatalog.map(\.name))
        let visibleNames = Set(bridge.visibleTools().map(\.name))
        XCTAssertFalse(visibleNames.contains("mcp__alpha__search"))

        let toolCall = expandedToolCall(name: "mcp__alpha__search", input: #"{"query":"x"}"#)
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
            "a known-but-unexposed expanded MCP tool must be guided, not hard-failed"
        )
        XCTAssertFalse(runtime.hasUnresolvedToolCall(in: guided.messages))
        let outputText = toolOutputText(guided.messages)
        XCTAssertTrue(outputText.contains("tool_search"))
        XCTAssertTrue(outputText.contains("未暴露"))
        XCTAssertTrue(outputText.contains("\"status\":\"failed\""))
    }

    func testStaleExpandedToolCallReturnsHonestFailureWithoutCrash() async throws {
        // The exposed name's server/tool is no longer in the directory.
        let manager = IOSMcpManager(serverProvider: { [] })
        manager.refreshFromCurrentSettings()
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: manager
        )
        let context = makeContext(toolCall: expandedToolCall(name: "mcp__alpha__search", input: #"{"query":"x"}"#))

        try await withHighRiskAutoApprove(true) {
            let result = await runtime.execute(
                ChatPendingToolCall(kind: .advanced, toolCall: context.toolCall),
                context: context
            )
            guard case .completed(let messages) = result else {
                return XCTFail("a stale expanded MCP call must resolve in place, got \(result)")
            }
            let outputText = toolOutputText(messages)
            XCTAssertTrue(outputText.contains("\"status\":\"failed\""), outputText)
            XCTAssertTrue(outputText.contains("不可用"), "the failure must state the tool is unavailable: \(outputText)")
            XCTAssertFalse(runtime.hasUnresolvedToolCall(in: messages), "the call must be filled, not left hanging")
        }
    }

    // MARK: - Approval: same gate and resume path as mcp_call

    func testExpandedToolCallWaitsForMcpApprovalWithResolvedTargetAndResumes() async throws {
        let alphaClient = RecordingMcpClient(tools: [IOSMcpTool(name: "search", description: "Alpha search")])
        let manager = IOSMcpManager(
            serverProvider: {
                [.streamableHTTP(name: "alpha", url: "https://example.com/alpha", tools: [IOSMcpTool(name: "search", description: "Alpha search")])]
            },
            clientFactory: { _ in alphaClient }
        )
        manager.refreshFromCurrentSettings()
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: manager
        )
        let context = makeContext(toolCall: expandedToolCall(name: "mcp__alpha__search", input: #"{"query":"hi"}"#))

        try await withHighRiskAutoApprove(false) {
            let result = await runtime.execute(
                ChatPendingToolCall(kind: .advanced, toolCall: context.toolCall),
                context: context
            )
            guard case .waitingForApproval(.mcp(let request)) = result else {
                return XCTFail("expanded MCP call must hit the mcp_call approval gate, got \(result)")
            }
            XCTAssertEqual(request.serverName, "alpha")
            XCTAssertEqual(request.toolName, "search")
            XCTAssertTrue(alphaClient.calls.isEmpty, "nothing may execute before approval")

            // Approval resumes through the exact mcp_call path (finishMcpApproval).
            let resumed = await runtime.finishMcpApproval(pending: context, allow: true)
            XCTAssertEqual(alphaClient.calls.count, 1)
            XCTAssertEqual(alphaClient.calls.first?.name, "search")
            XCTAssertFalse(runtime.hasUnresolvedToolCall(in: resumed))
        }
    }

    // MARK: - Background: same executor surface as mcp_call

    func testBackgroundBridgeRebuildKeepsExpandedMcpDeclarationsInCatalog() {
        // Handoff payloads carry tool NAMES only; dynamic `mcp__*` tools must
        // be regenerated from the runtime directory so the background catalog
        // (and its tool_search) stays in parity with the foreground run.
        let manager = twoServerManager()
        manager.refreshFromCurrentSettings()
        let expanded = expandedMcpToolDeclarations(mcpManager: manager)
        let fullNames = fullIosDeclarations().map(\.name) + expanded.map(\.name)
        let handoffVisible = expanded.filter { $0.name == "mcp__alpha__search" }

        let bridge = IOSChatBackgroundGenerationCoordinator.makeBackgroundToolExposureBridge(
            fullToolNames: fullNames,
            handoffVisibleTools: handoffVisible,
            additionalDeclarations: expanded
        )
        XCTAssertTrue(bridge.lazyModeEnabled())
        XCTAssertTrue(
            Set(bridge.fullToolDeclarations().map(\.name)).contains("mcp__alpha__search"),
            "the rebuilt background catalog must keep the expanded MCP surface"
        )
        XCTAssertTrue(
            Set(bridge.visibleTools().map(\.name)).contains("mcp__alpha__search"),
            "a foreground-exposed expanded tool must stay callable in background"
        )
        // And a background tool_search can still surface the deferred ones.
        let payload = bridge.executeToolSearch(argumentsJson: #"{"query":"mcp__beta__list","limit":1}"#)
        XCTAssertTrue(payload.contains("mcp__beta__list"), payload)
    }

    func testBackgroundExecutorsRegisterAndExecuteExpandedMcpTools() async throws {
        let alphaClient = RecordingMcpClient(tools: [IOSMcpTool(name: "search", description: "Alpha search")])
        let manager = IOSMcpManager(
            serverProvider: {
                [.streamableHTTP(name: "alpha", url: "https://example.com/alpha", tools: [IOSMcpTool(name: "search", description: "Alpha search")])]
            },
            clientFactory: { _ in alphaClient }
        )
        manager.refreshFromCurrentSettings()
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: manager
        )
        let provider = IOSCouncilRoomRunner.makeProviderSetting(baseUrl: "https://example.com/v1", apiKey: "test-key")
        let exposed = expandedMcpToolDeclarations(mcpManager: manager)
        let model = Model(
            modelId: "mcp-expanded-bg",
            displayName: "MCP Expanded BG",
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
            tools: exposed,
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let executors = runtime.backgroundToolExecutors(
            providerSetting: provider,
            params: params,
            runId: "run-bg-expanded"
        )
        let executor = try XCTUnwrap(executors["mcp__alpha__search"], "exposed expanded tools must be registered")

        try await withHighRiskAutoApprove(true) {
            let outcome = await UncheckedToolExecutorBox(executor).execute(
                name: "mcp__alpha__search",
                arguments: #"{"query":"bg"}"#,
                isUserInitiated: false
            )
            guard case .filled = outcome else {
                return XCTFail("background expanded MCP call must fill, got \(outcome)")
            }
            XCTAssertEqual(alphaClient.calls.first?.name, "search")
            XCTAssertEqual(alphaClient.calls.first?.arguments["query"] as? String, "bg")
        }
    }

    func testBackgroundExpandedToolDeniedWithoutHighRiskAutoApprove() async throws {
        let manager = twoServerManager()
        manager.refreshFromCurrentSettings()
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: CountingSearchTransport(),
            mcpManager: manager
        )
        let provider = IOSCouncilRoomRunner.makeProviderSetting(baseUrl: "https://example.com/v1", apiKey: "test-key")
        let exposed = expandedMcpToolDeclarations(mcpManager: manager)
        let model = Model(
            modelId: "mcp-expanded-bg-deny",
            displayName: "MCP Expanded BG Deny",
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
            tools: exposed,
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let executors = runtime.backgroundToolExecutors(
            providerSetting: provider,
            params: params,
            runId: "run-bg-expanded-deny"
        )
        let executor = try XCTUnwrap(executors["mcp__alpha__search"])

        try await withHighRiskAutoApprove(false) {
            let outcome = await UncheckedToolExecutorBox(executor).execute(
                name: "mcp__alpha__search",
                arguments: #"{"query":"bg"}"#,
                isUserInitiated: false
            )
            guard case .denied = outcome else {
                return XCTFail("background expanded MCP must be denied without the high-risk switch, got \(outcome)")
            }
        }
    }

    // MARK: - MCP off / no discovered tools: P0-a baseline unchanged

    func testMcpOffKeepsP0aBaselineDeclarations() throws {
        let manager = IOSMcpManager(serverProvider: { [] })
        manager.refreshFromCurrentSettings()
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false,
            mcpManager: manager
        )
        // Assemble the run bridge (makeTextGenerationParams) first.
        _ = viewModel.currentToolDeclarationNames()
        let bridge = try XCTUnwrap(viewModel.toolExposureBridgeForTesting())
        XCTAssertTrue(bridge.lazyModeEnabled())
        XCTAssertFalse(
            bridge.fullToolDeclarations().contains(where: { $0.name.hasPrefix("mcp__") }),
            "no discovered tools must keep the P0-a declaration set"
        )
        let visibleNames = bridge.visibleTools().map(\.name)
        XCTAssertTrue(visibleNames.contains("mcp_call"))
        XCTAssertTrue(visibleNames.contains("mcp_list"))
        XCTAssertTrue(visibleNames.contains("tool_search"))
    }

    // MARK: - Full catalog fixture (mirrors IOSToolSearchExposureTests)

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
}

/// IOSMcpClienting double that records callTool invocations and returns the
/// same tool list it was configured with (so syncAll's merge keeps tools).
@MainActor
private final class RecordingMcpClient: IOSMcpClienting {
    struct Call {
        let name: String
        let arguments: [String: Any]
    }

    private(set) var calls: [Call] = []
    private let tools: [IOSMcpTool]

    init(tools: [IOSMcpTool]) {
        self.tools = tools
    }

    func connect(config: IOSMcpServerConfig) async throws -> Bool { true }
    func listTools() async throws -> [IOSMcpTool] { tools }
    func callTool(name: String, arguments: [String: Any]) async throws -> String {
        calls.append(Call(name: name, arguments: arguments))
        return #"{"ok":true,"text":"recorded"}"#
    }
    func disconnect() {}
}

/// Minimal IOSSearchHTTPTransport double that never touches the network.
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

/// IOSToolExecutor is not Sendable; box it for the async boundary.
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
