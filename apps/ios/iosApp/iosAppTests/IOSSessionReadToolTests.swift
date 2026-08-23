import XCTest
@preconcurrency import Shared
@testable import iosApp

/// 会话工具不触发网络（本地只读）；测试 transport 只记录请求不返回内容。
private final class SessionReadCountingSearchTransport: IOSSearchHTTPTransport {
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

/// IOSToolExecutor 非 Sendable；executor 字典值跨 async 边界必须装箱
/// （照 IOSToolSearchExposureTests 同款模式）。
private final class SessionReadUncheckedToolExecutorBox: @unchecked Sendable {
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

/// session_search / session_read —— 跨会话读取工具（真机测试发现的真实缺口：
/// agent 无法读取其它会话内容）。与 Android 当前会话作用域的
/// conversation_search/conversation_expand 语义错开：本工具搜索并读取本机
/// 全部会话（标题 + 消息文本），只读 pure、无审批、前后台同注册。
@MainActor
final class IOSSessionReadToolTests: XCTestCase {

    private func isolatedDefaults() -> UserDefaults {
        let suite = "IOSSessionReadToolTests-\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }

    /// 重配置（>40 声明工具）用：与 IOSToolSearchExposureTests 同款，保证
    /// tool_search 惰性池生效（workspace/iSH 声明挂在 executor 存在时）。
    private func localToolExecutor() -> IOSLocalToolExecutor {
        IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore(),
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            )
        )
    }

    private func makeTempDirectory(_ name: String) -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(name)-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func makeStore(directory: URL) -> IOSConversationStore {
        IOSConversationStore(baseDirectory: directory)
    }

    private func makeRuntime(store: IOSConversationStore) -> ChatToolRuntime {
        ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: SessionReadCountingSearchTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] }),
            conversationStoreProvider: { store }
        )
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "session-read-test",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "sk-test",
            baseUrl: "https://example.test",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    private func makeParams() -> TextGenerationParams {
        let model = Model(
            modelId: "test-model",
            displayName: "test-model",
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
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func makeToolCall(name: String, input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "session-\(UUID().uuidString)",
            toolName: name,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func makeAssistantMessage(parts: [UIMessagePart]) -> UIMessage {
        let seed = UIMessage.companion.assistant(prompt: "")
        return UIMessage(
            id: seed.id,
            role: seed.role,
            parts: parts,
            annotations: [],
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )
    }

    /// 生产拼装的最小镜像：run 状态 + 工具调用，执行 session 工具并取回输出文本。
    private func executeSessionTool(
        runtime: ChatToolRuntime,
        toolCall: UIMessagePart.Tool,
        conversationId: KotlinUuid? = nil
    ) async -> String {
        let pending = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "session-read-run",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: conversationId,
            baseMessages: [makeAssistantMessage(parts: [toolCall])]
        )
        let result = await runtime.execute(
            ChatPendingToolCall(kind: .sessionRead, toolCall: toolCall),
            context: pending
        )
        guard case .completed(let messages) = result else {
            XCTFail("session 工具必须走 completed 路径，实际: \(result)")
            return ""
        }
        let outputs = messages.flatMap { message in
            message.parts.compactMap { ($0 as? UIMessagePart.Tool)?.output }
        }
        return outputs.flatMap { $0 }.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()
    }

    private func parseJSON(_ text: String) -> [String: Any] {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            XCTFail("输出必须是可解析 JSON，实际: \(text.prefix(200))")
            return [:]
        }
        return object
    }

    /// 种子化：一个当前会话（红酒）+ 一个显式 id 会话（电影）。
    private func seedTwoConversations(
        in store: IOSConversationStore
    ) async throws -> (wineId: KotlinUuid, movieId: KotlinUuid) {
        await store.newConversation()
        let wineId = try XCTUnwrap(store.currentConversation?.id)
        _ = await store.save(messages: [
            UIMessage.companion.user(prompt: "红酒品鉴笔记"),
            UIMessage.companion.assistant(prompt: "赤霞珠 2019 有黑樱桃风味，值得买。"),
        ], to: wineId)

        let movieId = KotlinUuid.companion.random()
        _ = await store.saveForkedConversation(Conversation.companion.ofId(
            id: movieId,
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: false
        ))
        _ = await store.save(messages: [
            UIMessage.companion.user(prompt: "周末电影清单"),
            UIMessage.companion.assistant(prompt: "《沙丘》和《银翼杀手》值得一看。"),
        ], to: movieId)
        return (wineId, movieId)
    }

    // MARK: - session_search

    func testSessionSearchReturnsMatchingConversationWithSnippetAndCount() async throws {
        let base = makeTempDirectory("SessionSearch")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        let (wineId, _) = try await seedTwoConversations(in: store)
        let runtime = makeRuntime(store: store)

        let output = await executeSessionTool(
            runtime: runtime,
            toolCall: makeToolCall(name: "session_search", input: #"{"query":"红酒"}"#)
        )
        let payload = parseJSON(output)
        XCTAssertEqual(payload["status"] as? String, "ok")
        XCTAssertEqual(payload["query"] as? String, "红酒")
        let results = payload["results"] as? [[String: Any]] ?? []
        XCTAssertEqual(results.count, 1, "标题 + 消息多条命中必须聚合为每会话一条")
        let hit = try XCTUnwrap(results.first)
        XCTAssertEqual(hit["conversation_id"] as? String, wineId.toHexDashString())
        XCTAssertTrue((hit["title"] as? String)?.contains("红酒") == true
            || (hit["snippet"] as? String)?.contains("红酒") == true,
            "snippet 必须含关键词上下文，实际: \(hit["snippet"] ?? "")")
        XCTAssertEqual(hit["message_count"] as? Int, 2, "message_count 必须是会话真实消息数")
        XCTAssertNotNil(hit["updated_at"])
    }

    func testSessionSearchLimitCapsResults() async throws {
        let base = makeTempDirectory("SessionSearchLimit")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let wineId = try XCTUnwrap(store.currentConversation?.id)
        // 标题 + 三条消息都含「笔记」：若 limit 不生效会返回 4 条命中。
        _ = await store.save(messages: [
            UIMessage.companion.user(prompt: "品酒笔记"),
            UIMessage.companion.assistant(prompt: "笔记一：丹宁柔和。"),
            UIMessage.companion.assistant(prompt: "笔记二：酸度明亮。"),
            UIMessage.companion.assistant(prompt: "笔记三：回味悠长。"),
        ], to: wineId)
        let runtime = makeRuntime(store: store)

        let output = await executeSessionTool(
            runtime: runtime,
            toolCall: makeToolCall(name: "session_search", input: #"{"query":"笔记","limit":1}"#)
        )
        let payload = parseJSON(output)
        let results = payload["results"] as? [[String: Any]] ?? []
        XCTAssertEqual(results.count, 1, "limit=1 必须生效（多条命中只回 1 条）")
        XCTAssertEqual(results.first?["conversation_id"] as? String, wineId.toHexDashString())
    }

    func testSessionSearchEmptyResultsReturnEmptyArrayAndHint() async throws {
        let base = makeTempDirectory("SessionSearchEmpty")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        _ = try await seedTwoConversations(in: store)
        let runtime = makeRuntime(store: store)

        let output = await executeSessionTool(
            runtime: runtime,
            toolCall: makeToolCall(name: "session_search", input: #"{"query":"量子力学不存在的词"}"#)
        )
        let payload = parseJSON(output)
        XCTAssertEqual(payload["status"] as? String, "ok")
        let results = payload["results"] as? [[String: Any]] ?? []
        XCTAssertTrue(results.isEmpty, "无命中必须给空数组而不是错误")
        XCTAssertNotNil(payload["hint"], "空结果必须带换关键词提示")
    }

    // MARK: - session_read

    func testSessionReadReturnsLatestMessagesWithToolProjection() async throws {
        let base = makeTempDirectory("SessionReadProjection")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        _ = try await seedTwoConversations(in: store)
        // 目标会话独立种子：一次写全量消息列表（与生产 save 契约一致——
        // 节点按索引/消息 id 归并，二次部分保存会累积分支而不是替换）。
        let movieId = KotlinUuid.companion.random()
        _ = await store.saveForkedConversation(Conversation.companion.ofId(
            id: movieId,
            assistantId: AssistantKt.DEFAULT_ASSISTANT_ID,
            messages: [],
            newConversation: false
        ))
        _ = await store.save(messages: [
            UIMessage.companion.user(prompt: "周末电影清单"),
            UIMessage.companion.assistant(prompt: "《沙丘》和《银翼杀手》值得一看。"),
            UIMessage.companion.user(prompt: "电影清单"),
            UIMessage.companion.assistant(prompt: "《沙丘》"),
            makeAssistantMessage(parts: [
                UIMessagePart.Tool(
                    toolCallId: "t-web",
                    toolName: "search_web",
                    input: #"{"query":"沙丘 影评"}"#,
                    output: [UIMessagePart.Text(text: #"{"ok":true}"#, metadata: nil)],
                    approvalState: ToolApprovalState.Auto.shared,
                    streamIndex: nil,
                    metadata: nil
                ),
            ]),
        ], to: movieId)
        let runtime = makeRuntime(store: store)

        let output = await executeSessionTool(
            runtime: runtime,
            toolCall: makeToolCall(
                name: "session_read",
                input: #"{"conversation_id":"\#(movieId.toHexDashString())","max_messages":3}"#
            )
        )
        let payload = parseJSON(output)
        XCTAssertEqual(payload["status"] as? String, "ok")
        XCTAssertEqual(payload["conversation_id"] as? String, movieId.toHexDashString())
        XCTAssertEqual(payload["message_count"] as? Int, 5, "message_count 必须是会话真实消息数")
        let messages = payload["messages"] as? [[String: Any]] ?? []
        XCTAssertEqual(messages.count, 3, "max_messages=3 必须只回最新 3 条")

        let first = try XCTUnwrap(messages.first)
        XCTAssertEqual(first["role"] as? String, "user")
        XCTAssertEqual(first["text"] as? String, "电影清单")
        let second = messages[1]
        XCTAssertEqual(second["role"] as? String, "assistant")
        XCTAssertEqual(second["text"] as? String, "《沙丘》")
        let third = messages[2]
        XCTAssertEqual(third["text"] as? String, "[tool: search_web completed]",
                       "Tool part 必须投影为 [tool: 名称 状态] 一行")
    }

    func testSessionReadTruncatesPerMessageText() async throws {
        let base = makeTempDirectory("SessionReadPerMessageTruncation")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let targetId = try XCTUnwrap(store.currentConversation?.id)
        let longText = String(repeating: "很长的消息内容", count: 300) // 1800 字
        _ = await store.save(messages: [
            UIMessage.companion.user(prompt: "开头：\(longText)"),
        ], to: targetId)
        let runtime = makeRuntime(store: store)

        let output = await executeSessionTool(
            runtime: runtime,
            toolCall: makeToolCall(
                name: "session_read",
                input: #"{"conversation_id":"\#(targetId.toHexDashString())","max_messages":1}"#
            )
        )
        let payload = parseJSON(output)
        let messages = payload["messages"] as? [[String: Any]] ?? []
        XCTAssertEqual(messages.count, 1)
        let text = messages.first?["text"] as? String ?? ""
        XCTAssertEqual(text.count, 2000, "单条消息文本必须截断到 2000 字符，实际: \(text.count)")
        XCTAssertTrue(text.hasPrefix("开头："), "截断必须保留开头")
    }

    func testSessionReadTruncatesTotalOutput() async throws {
        let base = makeTempDirectory("SessionReadTotalTruncation")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let targetId = try XCTUnwrap(store.currentConversation?.id)
        let longText = String(repeating: "很长的消息内容", count: 300) // 1800 字
        var seeded: [UIMessage] = []
        for index in 0..<8 {
            seeded.append(index.isMultiple(of: 2)
                ? UIMessage.companion.user(prompt: "第 \(index) 轮：\(longText)")
                : UIMessage.companion.assistant(prompt: "回复 \(index)：\(longText)"))
        }
        _ = await store.save(messages: seeded, to: targetId)
        let runtime = makeRuntime(store: store)

        let output = await executeSessionTool(
            runtime: runtime,
            toolCall: makeToolCall(
                name: "session_read",
                input: #"{"conversation_id":"\#(targetId.toHexDashString())","max_messages":8}"#
            )
        )
        let payload = parseJSON(output)
        let messages = payload["messages"] as? [[String: Any]] ?? []
        // 8 × 2000 = 16000 > 12000 总预算 → 最多容纳 6 条。
        XCTAssertLessThanOrEqual(messages.count, 6, "总预算 12000 字符下 8 条 2000 字消息必须截断")
        let total = messages.reduce(0) { $0 + (($1["text"] as? String)?.count ?? 0) }
        XCTAssertLessThanOrEqual(total, 12000, "总输出必须截断到 12000 字符，实际: \(total)")
        for message in messages {
            let text = message["text"] as? String ?? ""
            XCTAssertLessThanOrEqual(text.count, 2000, "单条消息文本必须截断到 2000 字符")
            XCTAssertTrue(text.hasPrefix("第 ") || text.hasPrefix("回复 "), "截断必须保留开头，实际前缀: \(text.prefix(20))")
        }
    }

    func testSessionReadInvalidConversationIdReturnsStructuredErrorWithoutCrash() async throws {
        let base = makeTempDirectory("SessionReadInvalid")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        _ = try await seedTwoConversations(in: store)
        let runtime = makeRuntime(store: store)

        // 模型垃圾输入：非法 UUID 字符串。K/N 的 KotlinUuid.parse 对非法串在
        // K/N 导出下终止进程（NSException），必须被 Foundation 预校验拦截。
        let output = await executeSessionTool(
            runtime: runtime,
            toolCall: makeToolCall(name: "session_read", input: #"{"conversation_id":"not-a-uuid"}"#)
        )
        let payload = parseJSON(output)
        XCTAssertEqual(payload["ok"] as? Bool, false, "非法 conversation_id 必须结构化失败")
        XCTAssertEqual(payload["tool"] as? String, "session_read")

        // 连 JSON 都不是的输入走 parseInputStrict 门（同样不得崩溃）。
        let garbage = await executeSessionTool(
            runtime: runtime,
            toolCall: makeToolCall(name: "session_read", input: "garbage-not-json")
        )
        let garbagePayload = parseJSON(garbage)
        XCTAssertEqual(garbagePayload["ok"] as? Bool, false, "非 JSON 输入必须结构化失败")
    }

    func testSessionReadUnknownConversationReturnsNotFound() async throws {
        let base = makeTempDirectory("SessionReadNotFound")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        _ = try await seedTwoConversations(in: store)
        let runtime = makeRuntime(store: store)

        let missingId = KotlinUuid.companion.random()
        let output = await executeSessionTool(
            runtime: runtime,
            toolCall: makeToolCall(
                name: "session_read",
                input: #"{"conversation_id":"\#(missingId.toHexDashString())"}"#
            )
        )
        let payload = parseJSON(output)
        XCTAssertEqual(payload["status"] as? String, "error")
        XCTAssertEqual(payload["reason"] as? String, "conversation not found")
    }

    func testSessionReadCurrentConversationPrefersInMemoryCopy() async throws {
        let base = makeTempDirectory("SessionReadCurrent")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        let (wineId, _) = try await seedTwoConversations(in: store)
        let runtime = makeRuntime(store: store)

        // 目标 = 当前会话（内存 currentConversation），照常读取最新消息。
        let output = await executeSessionTool(
            runtime: runtime,
            toolCall: makeToolCall(
                name: "session_read",
                input: #"{"conversation_id":"\#(wineId.toHexDashString())","max_messages":1}"#
            ),
            conversationId: wineId
        )
        let payload = parseJSON(output)
        XCTAssertEqual(payload["status"] as? String, "ok")
        let messages = payload["messages"] as? [[String: Any]] ?? []
        XCTAssertEqual(messages.count, 1)
        XCTAssertEqual(messages.first?["text"] as? String, "赤霞珠 2019 有黑樱桃风味，值得买。")
    }

    // MARK: - 声明池 + tool_search 命中 + 分类路由（S1 端到端契约）

    func testSessionToolsDeferredInFullCatalogAndChineseSearchHitIsRoutable() throws {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: localToolExecutor(),
            autoGenerateResponses: false
        )
        _ = viewModel.currentToolDeclarationNames()
        let bridge = try XCTUnwrap(viewModel.toolExposureBridgeForTesting())

        // 生产组装点（makeTextGenerationParams）必须把两工具放进桥全目录。
        let fullNames = Set(bridge.fullToolDeclarations().map(\.name))
        XCTAssertTrue(fullNames.contains("session_search"), "session_search 必须进生产目录（bridge 全目录）")
        XCTAssertTrue(fullNames.contains("session_read"), "session_read 必须进生产目录（bridge 全目录）")

        // 非常驻：首轮不可见（deferred 池，tool_search 命中后才进 params.tools）。
        let firstRound = Set(bridge.visibleTools().map(\.name))
        XCTAssertFalse(firstRound.contains("session_search"), "session_search 首轮不可见（deferred）")
        XCTAssertFalse(firstRound.contains("session_read"), "session_read 首轮不可见（deferred）")

        // tool_search 中文 query「另一个会话」命中两工具（KMP ToolSearch 词条）。
        let payload = bridge.executeToolSearch(argumentsJson: #"{"query":"另一个会话","limit":10}"#)
        XCTAssertTrue(payload.contains("session_search"), "中文 query 必须命中 session_search")
        XCTAssertTrue(payload.contains("session_read"), "中文 query 必须命中 session_read")

        // 次轮 visibleTools() 含命中（同桥实例、同 run 生命周期）。
        let nextRound = Set(bridge.visibleTools().map(\.name))
        XCTAssertTrue(nextRound.contains("session_search"))
        XCTAssertTrue(nextRound.contains("session_read"))

        // ChatToolRuntime 分类可路由：命中后的 session_read 调用被
        // nextPendingToolCall 识别（不会落「未知名硬失败」）。
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: nil,
            searchTransport: SessionReadCountingSearchTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
        let toolCall = makeToolCall(name: "session_read", input: #"{"conversation_id":"00000000-0000-0000-0000-000000000000"}"#)
        let pending = runtime.nextPendingToolCall(
            in: [UIMessage.companion.user(prompt: "查一下别的会话"), makeAssistantMessage(parts: [toolCall])],
            availableToolNames: nextRound
        )
        guard let pending else {
            return XCTFail("命中后的 session_read 必须被 ChatToolRuntime 分类为可路由工具调用")
        }
        guard case .sessionRead = pending.kind else {
            return XCTFail("session_read 必须路由为 sessionRead 路径，实际: \(pending.kind)")
        }
    }

    // MARK: - 后台 executor 注册

    func testBackgroundExecutorsRegisterSessionTools() async throws {
        let base = makeTempDirectory("SessionReadBackground")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        let (wineId, _) = try await seedTwoConversations(in: store)
        let runtime = makeRuntime(store: store)

        let params = makeParams().replacingTools([
            ToolKt.createSessionSearchToolDeclaration(),
            ToolKt.createSessionReadToolDeclaration(),
        ])
        let executors = runtime.backgroundToolExecutors(
            providerSetting: makeProviderSetting(),
            params: params,
            runId: "bg-session-run",
            conversationId: wineId
        )
        XCTAssertNotNil(executors["session_search"], "后台必须注册 session_search")
        XCTAssertNotNil(executors["session_read"], "后台必须注册 session_read")

        let outcome = await SessionReadUncheckedToolExecutorBox(
            try XCTUnwrap(executors["session_search"])
        ).execute(
            name: "session_search",
            arguments: #"{"query":"红酒","limit":8}"#,
            isUserInitiated: false
        )
        guard case .filled(let output) = outcome else {
            return XCTFail("后台 session_search 必须返回 filled，实际: \(String(describing: outcome))")
        }
        let payload = parseJSON(output)
        XCTAssertEqual(payload["status"] as? String, "ok")
        let results = payload["results"] as? [[String: Any]] ?? []
        XCTAssertEqual(results.first?["conversation_id"] as? String, wineId.toHexDashString())
    }
}
