import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P2-a：记忆 polluted 三态的 iOS 契约测试。
///
/// 覆盖：search_web/mcp__* 成功输出进会话 → 该会话 POLLUTED 且持久化（新 store
/// 实例读回）；workspace_file_read/memory_tool 不标；失败工具不标；重复置位幂等；
/// 后台 run 标记到 run 锚定会话而非前台会话；复位 POLLUTED→ENABLED 与列表投影。
@MainActor
final class IOSMemoryPollutionTests: XCTestCase {

    // MARK: - Helpers

    private func makeTempDirectory(_ name: String) -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("\(name)-\(UUID().uuidString)")
    }

    private func makeStore(directory: URL) async -> IOSConversationStore {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let store = IOSConversationStore(baseDirectory: directory)
        await store.bootstrap()
        return store
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.pollution.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }

    private func assistantToolMessage(toolCall: UIMessagePart.Tool) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [toolCall],
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(
                year: 2026, month: 8, day: 8, hour: 0, minute: 0, second: 0, nanosecond: 0
            ),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func toolPart(
        toolCallId: String,
        toolName: String,
        input: String = "{}"
    ) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: toolCallId,
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func successOutput(for toolName: String) -> String {
        #"{"ok":true,"tool":"\#(toolName)","status":"success"}"#
    }

    private func failureOutput(for toolName: String) -> String {
        ChatToolOutputFormatter.toolFailureJSON(
            toolName: toolName,
            reason: "测试失败",
            status: "failed"
        )
    }

    /// 生产同形接线：marker → store.markConversationMemoryPolluted（fire-and-forget）。
    private func productionStyleMarker(store: IOSConversationStore) -> ((KotlinUuid, String) -> Void) {
        { id, _ in
            Task { @MainActor in
                _ = await store.markConversationMemoryPolluted(id)
            }
        }
    }

    private func makeRuntime(
        sharedSettings: IOSSharedSettingsStore,
        marker: ((KotlinUuid, String) -> Void)? = nil,
        mcpManager: IOSMcpManager? = nil
    ) -> ChatToolRuntime {
        ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            localToolExecutor: nil,
            searchTransport: PollutionSearchTransport(),
            mcpManager: mcpManager ?? IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared),
            memoryPollutionMarker: marker
        )
    }

    /// 单 server（alpha/search）的 MCP 目录，client 由测试注入（抛错或成功）。
    private func makeMcpManager(client: any IOSMcpClienting) -> IOSMcpManager {
        let manager = IOSMcpManager(
            serverProvider: {
                [
                    .streamableHTTP(
                        name: "alpha",
                        url: "https://example.com/alpha",
                        tools: [IOSMcpTool(name: "search", description: "Alpha search")]
                    ),
                ]
            },
            clientFactory: { _ in client }
        )
        manager.refreshFromCurrentSettings()
        return manager
    }

    private func makePending(
        toolCall: UIMessagePart.Tool,
        conversationId: KotlinUuid?,
        baseMessages: [UIMessage],
        sharedSettings: IOSSharedSettingsStore
    ) -> ChatPendingToolApproval {
        let model = Model(
            modelId: "pollution-test",
            displayName: "Pollution Test",
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
            tools: [ToolKt.createSearchWebToolDeclaration()],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        return ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: IOSCouncilRoomRunner.makeProviderSetting(
                baseUrl: "https://example.com/v1",
                apiKey: "test-key"
            ),
            params: params,
            runId: "run-pollution-test",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: conversationId,
            baseMessages: baseMessages
        )
    }

    /// 轮询等待异步置位落盘（marker 走 Task fire-and-forget，与生产一致）。
    private func waitUntilPolluted(
        store: IOSConversationStore,
        id: KotlinUuid,
        timeout: TimeInterval = 5
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let summaries = await store.pollutedConversationSummaries()
            if summaries.contains(where: { $0.id == id }) {
                return true
            }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return false
    }

    private func memoryMode(of conversation: Conversation?) -> ConversationMemoryMode? {
        conversation?.memoryMode
    }

    // MARK: - 置位 + 持久化

    func testSearchWebCompletionMarksRunConversationAndPersists() async throws {
        let directory = makeTempDirectory("SearchMarks")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = await makeStore(directory: directory)
        let foregroundId = try XCTUnwrap(store.currentConversation?.id)

        let runConversationId = KotlinUuid.companion.random()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: runConversationId,
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: false
        ))

        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setEnableWebSearch(true)
        let runtime = makeRuntime(sharedSettings: sharedSettings, marker: productionStyleMarker(store: store))

        let toolCall = toolPart(toolCallId: "search-1", toolName: "search_web", input: #"{"query":"amber","max_results":1}"#)
        let pending = makePending(
            toolCall: toolCall,
            conversationId: runConversationId,
            baseMessages: [assistantToolMessage(toolCall: toolCall)],
            sharedSettings: sharedSettings
        )
        let resolved = await runtime.finishSearchApproval(pending: pending, allow: true)
        let filledPart = resolved.flatMap { $0.parts }.compactMap { $0 as? UIMessagePart.Tool }
            .first { $0.toolCallId == toolCall.toolCallId }
        XCTAssertNotNil(filledPart)
        XCTAssertFalse(filledPart?.output.isEmpty ?? true, "search_web 输出必须进入会话")

        let polluted = await waitUntilPolluted(store: store, id: runConversationId)
        XCTAssertTrue(polluted)

        // 新 store 实例读回：POLLUTED 已持久化。
        let freshStore = await makeStore(directory: directory)
        let reloaded = try await freshStore.loadConversationForOrchestration(runConversationId)
        XCTAssertEqual(memoryMode(of: reloaded), .polluted)

        // 前台会话（store 的 currentConversation）不受后台 run 影响。
        let freshPolluted = await freshStore.pollutedConversationSummaries()
        XCTAssertFalse(freshPolluted.contains { $0.id == foregroundId })
    }

    func testMcpPrefixToolSuccessMarksPolluted() async throws {
        let directory = makeTempDirectory("McpPrefixMarks")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = await makeStore(directory: directory)
        let runConversationId = KotlinUuid.companion.random()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: runConversationId,
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: false
        ))

        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let runtime = makeRuntime(sharedSettings: sharedSettings, marker: productionStyleMarker(store: store))

        let toolCall = toolPart(toolCallId: "mcp-1", toolName: "mcp__alpha__search", input: #"{"query":"x"}"#)
        let baseMessages = [assistantToolMessage(toolCall: toolCall)]
        _ = runtime.messagesByFinishingToolCall(
            toolCall,
            outputText: successOutput(for: toolCall.toolName),
            in: baseMessages,
            conversationId: runConversationId
        )
        let polluted = await waitUntilPolluted(store: store, id: runConversationId)
        XCTAssertTrue(polluted)

        let freshStore = await makeStore(directory: directory)
        let reloaded = try await freshStore.loadConversationForOrchestration(runConversationId)
        XCTAssertEqual(memoryMode(of: reloaded), .polluted)
    }

    // MARK: - 不置位

    func testNonPollutingToolsDoNotMark() async throws {
        let directory = makeTempDirectory("NonPolluting")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = await makeStore(directory: directory)
        let runConversationId = KotlinUuid.companion.random()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: runConversationId,
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: false
        ))

        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let runtime = makeRuntime(sharedSettings: sharedSettings, marker: productionStyleMarker(store: store))

        for toolName in ["workspace_file_read", "memory_tool", "tool_search"] {
            let toolCall = toolPart(toolCallId: "x-\(toolName)", toolName: toolName)
            _ = runtime.messagesByFinishingToolCall(
                toolCall,
                outputText: successOutput(for: toolName),
                in: [assistantToolMessage(toolCall: toolCall)],
                conversationId: runConversationId
            )
        }
        try? await Task.sleep(nanoseconds: 400_000_000)
        let summaries = await store.pollutedConversationSummaries()
        XCTAssertFalse(summaries.contains { $0.id == runConversationId })
    }

    func testFailedToolOutputDoesNotMark() async throws {
        let directory = makeTempDirectory("FailedNoMark")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = await makeStore(directory: directory)
        let runConversationId = KotlinUuid.companion.random()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: runConversationId,
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: false
        ))

        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let runtime = makeRuntime(sharedSettings: sharedSettings, marker: productionStyleMarker(store: store))

        // 搜索失败（结构化失败输出）与 MCP 失败都不置位。
        for toolName in ["search_web", "mcp_call"] {
            let toolCall = toolPart(toolCallId: "fail-\(toolName)", toolName: toolName)
            _ = runtime.messagesByFinishingToolCall(
                toolCall,
                outputText: failureOutput(for: toolName),
                in: [assistantToolMessage(toolCall: toolCall)],
                conversationId: runConversationId
            )
        }
        try? await Task.sleep(nanoseconds: 400_000_000)
        let summaries = await store.pollutedConversationSummaries()
        XCTAssertFalse(summaries.contains { $0.id == runConversationId })
    }

    // MARK: - MCP 失败输出结构化（P2-a 复核修复）

    /// mcp_call 直调失败必须产出结构化失败 JSON（failureReason 可识别、不置位污染）。
    func testMcpCallFailureIsStructuredAndDoesNotMarkPolluted() async throws {
        try await assertMcpFailureIsStructuredAndDoesNotMarkPolluted(toolPart(
            toolCallId: "mcp-fail-1",
            toolName: "mcp_call",
            input: #"{"server":"alpha","tool":"search","arguments":{"query":"x"}}"#
        ))
    }

    /// mcp__* 展开调用失败同样必须结构化（与 mcp_call 同一 catch 契约）。
    func testExpandedMcpFailureIsStructuredAndDoesNotMarkPolluted() async throws {
        try await assertMcpFailureIsStructuredAndDoesNotMarkPolluted(toolPart(
            toolCallId: "mcp-fail-2",
            toolName: "mcp__alpha__search",
            input: #"{"query":"x"}"#
        ))
    }

    private func assertMcpFailureIsStructuredAndDoesNotMarkPolluted(
        _ toolCall: UIMessagePart.Tool
    ) async throws {
        let directory = makeTempDirectory("McpFailStructured-\(toolCall.toolCallId)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = await makeStore(directory: directory)
        let runConversationId = KotlinUuid.companion.random()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: runConversationId,
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: false
        ))

        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let failingClient = StubMcpClient(
            tools: [IOSMcpTool(name: "search", description: "Alpha search")],
            result: .failure(NSError(
                domain: "test",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "expected failure"]
            ))
        )
        let runtime = makeRuntime(
            sharedSettings: sharedSettings,
            marker: productionStyleMarker(store: store),
            mcpManager: makeMcpManager(client: failingClient)
        )

        let pending = makePending(
            toolCall: toolCall,
            conversationId: runConversationId,
            baseMessages: [assistantToolMessage(toolCall: toolCall)],
            sharedSettings: sharedSettings
        )
        let output = try await executeAdvancedTool(runtime: runtime, pending: pending, toolCall: toolCall)

        XCTAssertNotNil(
            ChatToolOutputFormatter.failureReason(from: [UIMessagePart.Text(text: output, metadata: nil)]),
            "\(toolCall.toolName) 失败输出必须可被 failureReason 识别: \(output)"
        )
        XCTAssertTrue(output.contains("MCP 调用失败"), "失败原因文本必须保留在输出中: \(output)")
        XCTAssertTrue(output.contains("\"ok\":false"), output)
        XCTAssertTrue(output.contains("\"status\":\"failed\""), output)

        try? await Task.sleep(nanoseconds: 400_000_000)
        let summaries = await store.pollutedConversationSummaries()
        XCTAssertFalse(summaries.contains { $0.id == runConversationId }, "\(toolCall.toolName) 失败不得置位 POLLUTED")
    }

    /// MCP 调用成功（真实执行路径）仍必须置位 POLLUTED。
    func testMcpCallSuccessThroughRealPathMarksPolluted() async throws {
        let directory = makeTempDirectory("McpCallSuccessRealPath")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = await makeStore(directory: directory)
        let runConversationId = KotlinUuid.companion.random()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: runConversationId,
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: false
        ))

        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let successClient = StubMcpClient(
            tools: [IOSMcpTool(name: "search", description: "Alpha search")],
            result: .success(#"{"ok":true,"text":"recorded"}"#)
        )
        let runtime = makeRuntime(
            sharedSettings: sharedSettings,
            marker: productionStyleMarker(store: store),
            mcpManager: makeMcpManager(client: successClient)
        )

        let toolCall = toolPart(
            toolCallId: "mcp-ok-1",
            toolName: "mcp_call",
            input: #"{"server":"alpha","tool":"search","arguments":{"query":"x"}}"#
        )
        let pending = makePending(
            toolCall: toolCall,
            conversationId: runConversationId,
            baseMessages: [assistantToolMessage(toolCall: toolCall)],
            sharedSettings: sharedSettings
        )
        _ = try await executeAdvancedTool(runtime: runtime, pending: pending, toolCall: toolCall)

        let polluted = await waitUntilPolluted(store: store, id: runConversationId)
        XCTAssertTrue(polluted, "MCP 调用成功必须置位 POLLUTED")
    }

    /// 高级工具（mcp_call/mcp__*）以自动批准执行并返回填入的输出文本。
    private func executeAdvancedTool(
        runtime: ChatToolRuntime,
        pending: ChatPendingToolApproval,
        toolCall: UIMessagePart.Tool
    ) async throws -> String {
        let result = await withHighRiskAutoApprove(true) {
            await runtime.execute(
                ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
                context: pending
            )
        }
        guard case .completed(let messages) = result else {
            XCTFail("MCP 调用必须原位完成，got \(result)")
            return ""
        }
        return messages.flatMap { $0.parts.compactMap { ($0 as? UIMessagePart.Tool)?.output.compactMap { ($0 as? UIMessagePart.Text)?.text } } }
            .flatMap { $0 }
            .joined()
    }

    // MARK: - 幂等

    func testRepeatedMarkingIsIdempotent() async throws {
        let directory = makeTempDirectory("Idempotent")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = await makeStore(directory: directory)
        let conversationId = try XCTUnwrap(store.currentConversation?.id)

        let firstMark = await store.markConversationMemoryPolluted(conversationId)
        let secondMark = await store.markConversationMemoryPolluted(conversationId)
        XCTAssertTrue(firstMark)
        XCTAssertTrue(secondMark)
        let afterDoubleMark = try await store.loadConversationForOrchestration(conversationId)
        XCTAssertEqual(memoryMode(of: afterDoubleMark), .polluted)
    }

    // MARK: - 后台 run 锚定

    func testBackgroundRunMarksRunConversationNotForeground() async throws {
        let directory = makeTempDirectory("BackgroundAnchor")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = await makeStore(directory: directory)
        let foregroundId = try XCTUnwrap(store.currentConversation?.id)
        let runConversationId = KotlinUuid.companion.random()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: runConversationId,
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: false
        ))

        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setEnableWebSearch(true)
        let runtime = makeRuntime(sharedSettings: sharedSettings, marker: productionStyleMarker(store: store))

        let model = Model(
            modelId: "pollution-bg",
            displayName: "Pollution BG",
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
            tools: [ToolKt.createSearchWebToolDeclaration()],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let executors = runtime.backgroundToolExecutors(
            providerSetting: IOSCouncilRoomRunner.makeProviderSetting(
                baseUrl: "https://example.com/v1",
                apiKey: "test-key"
            ),
            params: params,
            runId: "run-bg-pollution",
            conversationId: runConversationId
        )
        let executor = try XCTUnwrap(executors["search_web"], "search_web 必须在后台注册")
        let outcome = await withGlobalAutoApprove(true) {
            await UncheckedPollutionExecutorBox(executor).execute(
                name: "search_web",
                arguments: #"{"query":"amber","max_results":1}"#,
                isUserInitiated: false
            )
        }
        guard case .filled = outcome else {
            return XCTFail("后台 search_web 必须成功执行，got \(outcome)")
        }

        let polluted = await waitUntilPolluted(store: store, id: runConversationId)
        XCTAssertTrue(polluted)
        let summaries = await store.pollutedConversationSummaries()
        XCTAssertFalse(summaries.contains { $0.id == foregroundId })
    }

    // MARK: - 复位 + 列表投影

    func testResetRestoresEnabledAndListProjectionListsOnlyPolluted() async throws {
        let directory = makeTempDirectory("Reset")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = await makeStore(directory: directory)
        let cleanId = try XCTUnwrap(store.currentConversation?.id)
        let pollutedId = KotlinUuid.companion.random()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: pollutedId,
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: false
        ))

        let marked = await store.markConversationMemoryPolluted(pollutedId)
        XCTAssertTrue(marked)

        // 列表投影：只列 POLLUTED。
        let projected = await store.pollutedConversationSummaries()
        XCTAssertTrue(projected.contains { $0.id == pollutedId })
        XCTAssertFalse(projected.contains { $0.id == cleanId })

        // 复位 POLLUTED→ENABLED 并持久化。
        let reset = await store.resetConversationMemoryPollution(pollutedId)
        XCTAssertTrue(reset)
        let afterReset = await store.pollutedConversationSummaries()
        XCTAssertFalse(afterReset.contains { $0.id == pollutedId })

        let freshStore = await makeStore(directory: directory)
        let afterResetReload = try await freshStore.loadConversationForOrchestration(pollutedId)
        XCTAssertEqual(memoryMode(of: afterResetReload), .enabled)
    }
}

/// 后台 search_web 的 DDG Lite HTML stub（与 IOSSearchExecutorTests fixture 同构）。
private final class PollutionSearchTransport: IOSSearchHTTPTransport {
    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        let html = """
        <html><body>
        <a rel="nofollow" class='result-link' href="/l/?kh=-1&amp;uddg=https%3A%2F%2Fexample.com%2Fone">First &amp; Result</a>
        <td class='result-snippet'>First snippet.</td>
        </body></html>
        """
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "https://lite.duckduckgo.com")!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/html; charset=utf-8"]
        )!
        return (response, Data(html.utf8))
    }
}

/// 后台 executor 不保证 Sendable 跨隔离域安全，测试里用与既有测试相同的 box 包装。
private final class UncheckedPollutionExecutorBox: @unchecked Sendable {
    private let executor: any IOSToolExecutor
    init(_ executor: any IOSToolExecutor) {
        self.executor = executor
    }

    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        await executor.execute(name: name, arguments: arguments, isUserInitiated: isUserInitiated)
    }
}

/// IOSMcpClienting double：callTool 按注入的 result 返回成功文本或抛错。
@MainActor
private final class StubMcpClient: IOSMcpClienting {
    private let tools: [IOSMcpTool]
    private let result: Result<String, Error>

    init(tools: [IOSMcpTool], result: Result<String, Error>) {
        self.tools = tools
        self.result = result
    }

    func connect(config: IOSMcpServerConfig) async throws -> Bool { true }
    func listTools() async throws -> [IOSMcpTool] { tools }
    func callTool(name: String, arguments: [String: Any]) async throws -> String {
        switch result {
        case .success(let text): return text
        case .failure(let error): throw error
        }
    }
    func disconnect() {}
}

/// 后台 search gate 需要全局自动批准；与既有测试的 withHighRiskAutoApprove 同形，
/// 设置后还原，避免污染全局 UserDefaults。
@MainActor
private func withGlobalAutoApprove<T>(_ enabled: Bool, _ body: () async throws -> T) async rethrows -> T {
    let key = "app.amber.ios.globalAutoApprove"
    let previous = UserDefaults.standard.object(forKey: key)
    UserDefaults.standard.set(enabled, forKey: key)
    defer {
        if let previous {
            UserDefaults.standard.set(previous, forKey: key)
        } else {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
    return try await body()
}

/// 高级工具（mcp_call/mcp__*）需要 high-risk 自动批准；与 IOSMcpExpandedToolTests
/// 的同名 helper 同形，设置后还原。
@MainActor
private func withHighRiskAutoApprove<T>(_ enabled: Bool, _ body: () async throws -> T) async rethrows -> T {
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
    return try await body()
}
