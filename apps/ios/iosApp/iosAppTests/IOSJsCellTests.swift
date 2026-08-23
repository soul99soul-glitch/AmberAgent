import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P3-c: exec cell 生命周期契约 —— yield/wait/terminate 三路径、并发上限、
/// store/load 会话持久化、冷启动 interrupted sweep，以及引擎级「exec 超时
/// yield → 模型 wait → 结果回灌」集成闭环（wait 消耗普通工具续跑步数）。
@MainActor
final class IOSJsCellTests: XCTestCase {

    // MARK: - Fixtures

    private func isolatedDefaults() -> UserDefaults {
        UserDefaults(suiteName: "IOSJsCellTests-\(UUID().uuidString)")!
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "js-cell-test",
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

    private func makeParams(tools: [Tool] = []) -> TextGenerationParams {
        let model = Model(
            modelId: "js-cell-test-model",
            displayName: "js-cell-test-model",
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
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: tools,
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func makeMessage(role: MessageRole, parts: [UIMessagePart]) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: role,
            parts: parts,
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 19, hour: 0, minute: 0, second: 0, nanosecond: 0),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func toolCallPart(toolCallId: String, toolName: String, input: String) -> UIMessagePart.Tool {
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

    private func toolOutputText(_ messages: [UIMessage]) -> String {
        messages.flatMap { $0.parts.compactMap { ($0 as? UIMessagePart.Tool)?.output.compactMap { ($0 as? UIMessagePart.Text)?.text } } }
            .flatMap { $0 }
            .joined()
    }

    private func runtime(execEnabled: Bool, registry: IOSJsCellRegistry) -> ChatToolRuntime {
        let defaults = isolatedDefaults()
        let settingsStore = SettingsStore(userDefaults: defaults)
        settingsStore.execJavaScriptEnabled = execEnabled
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        return ChatToolRuntime(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            localToolExecutor: nil,
            searchTransport: IOSURLSessionSearchHTTPTransport(),
            mcpManager: IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared),
            jsCellRegistry: registry
        )
    }

    private func tempDirectory() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSJsCellTests-\(UUID().uuidString)", isDirectory: true)
    }

    private func executeTool(
        _ runtime: ChatToolRuntime,
        toolCall: UIMessagePart.Tool,
        conversationId: KotlinUuid?,
        runId: String = "run-js-cell"
    ) async -> String {
        let assistant = makeMessage(role: .assistant, parts: [toolCall])
        let context = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: runId,
            startedAt: 1,
            inputDigest: "digest",
            conversationId: conversationId,
            baseMessages: [assistant]
        )
        let result = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: context
        )
        guard case .completed(let messages) = result else {
            return "EXECUTE-NOT-COMPLETED"
        }
        return toolOutputText(messages)
    }

    private func cellID(fromExecOutput output: String) -> String {
        guard let data = output.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let cellID = object["cell_id"] as? String else {
            XCTFail("exec yield output must carry cell_id: \(output)")
            return ""
        }
        return cellID
    }

    // MARK: - Yield → wait 闭环

    func testExecTimeoutYieldsRunningCellAndWaitRetrievesOutput() async {
        let registry = IOSJsCellRegistry(directory: tempDirectory())
        let runtime = runtime(execEnabled: true, registry: registry)
        let conversationId = KotlinUuid.companion.random()

        // exec 超时（1000ms 是最小 clamp，脚本 ~1800ms）→ 不丢句柄：返回含
        // cell ID 的 running 文本，注册表里有 Running cell。
        let execOutput = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-exec",
                toolName: "exec",
                input: #"{"code":"let end = Date.now() + 1800; while (Date.now() < end) {}; 6 * 7","timeout_ms":1000}"#
            ),
            conversationId: conversationId
        )
        XCTAssertTrue(execOutput.contains("Script running with cell ID"), "yield 必须返回 codex 文案: \(execOutput)")
        let cellID = cellID(fromExecOutput: execOutput)
        XCTAssertFalse(cellID.isEmpty)

        let sessionKey = conversationId.description()
        let cells = await registry.cells(sessionKey: sessionKey)
        XCTAssertEqual(cells.count, 1)
        XCTAssertEqual(cells.first?.status, .running, "yield 后注册表必须持有 Running cell")

        // 求值继续跑：完成后 wait 拿到 output。
        let waitOutput = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-wait",
                toolName: "wait",
                input: #"{"cell_id":"\#(cellID)","timeout_ms":8000}"#
            ),
            conversationId: conversationId
        )
        XCTAssertTrue(waitOutput.contains("\"status\":\"completed\""), "wait 必须返回终态: \(waitOutput)")
        // wait 的 output 字段携带 exec 同款 payload（外层 JSON 内是转义的结果文本）。
        XCTAssertTrue(waitOutput.contains(#"\"result\":\"42\""#), "wait 必须回灌求值结果: \(waitOutput)")

        // 读一次后清除：再次 wait 是诚实错误而非重复输出。
        let secondWait = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-wait-2",
                toolName: "wait",
                input: #"{"cell_id":"\#(cellID)","timeout_ms":1000}"#
            ),
            conversationId: conversationId
        )
        XCTAssertTrue(secondWait.contains("\"ok\":false"), "已读取的 cell 再次 wait 必须是结构化错误: \(secondWait)")
        XCTAssertTrue(secondWait.contains("不存在"), "错误必须说明不存在: \(secondWait)")
        let after = await registry.cells(sessionKey: sessionKey)
        XCTAssertTrue(after.isEmpty, "读一次后 cell 应从注册表清除")
    }

    // MARK: - wait 三路径

    func testWaitTerminateMarksCellTerminated() async {
        let registry = IOSJsCellRegistry(directory: tempDirectory())
        let runtime = runtime(execEnabled: true, registry: registry)
        let conversationId = KotlinUuid.companion.random()

        let execOutput = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-exec",
                toolName: "exec",
                input: #"{"code":"let end = Date.now() + 2500; while (Date.now() < end) {}; 1","timeout_ms":1000}"#
            ),
            conversationId: conversationId
        )
        let cellID = cellID(fromExecOutput: execOutput)

        let terminateOutput = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-terminate",
                toolName: "wait",
                input: #"{"cell_id":"\#(cellID)","terminate":true}"#
            ),
            conversationId: conversationId
        )
        XCTAssertTrue(terminateOutput.contains("\"status\":\"terminated\""), "terminate 必须返回终止终态: \(terminateOutput)")
        let sessionKey = conversationId.description()
        let after = await registry.cells(sessionKey: sessionKey)
        XCTAssertTrue(after.isEmpty, "terminate 读取后 cell 应清除")
    }

    func testWaitUnknownCellReturnsStructuredError() async {
        let registry = IOSJsCellRegistry(directory: tempDirectory())
        let runtime = runtime(execEnabled: true, registry: registry)
        let output = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-wait",
                toolName: "wait",
                input: #"{"cell_id":"no-such-cell","timeout_ms":1000}"#
            ),
            conversationId: KotlinUuid.companion.random()
        )
        XCTAssertTrue(output.contains("\"ok\":false"), "不存在的 cell 必须是结构化错误: \(output)")
        XCTAssertTrue(output.contains("不存在"), "错误必须诚实说明 cell 不存在: \(output)")
    }

    // MARK: - M6: wait 取消感知 + terminate 唤醒其他 waiter

    /// 红测试对应缺陷：wait 的 continuation 只靠 1-60s 超时释放，run 取消后不
    /// 响应（阻塞到超时）。修复后取消尽快返回结构化 cancelled，cell 保持 Running。
    func testWaitReturnsCancelledPromptlyOnRunCancellation() async {
        let registry = IOSJsCellRegistry(directory: tempDirectory())
        let sessionKey = "sess-cancel"
        let started = await registry.startCell(sessionKey: sessionKey, cellId: "cell-cancel-1")
        XCTAssertEqual(started, .started)

        let waitTask = Task { @MainActor in
            await registry.wait(cellId: "cell-cancel-1", sessionKey: sessionKey, timeoutMs: 3000, terminate: false)
        }
        // 等 waiter 注册（actor 串行序；300ms 余量充足）。
        try? await Task.sleep(nanoseconds: 300_000_000)
        waitTask.cancel()

        let outcome = await waitTask.value
        guard case .cancelled = outcome else {
            return XCTFail("wait 必须及时返回 cancelled（不等 3s 超时），实际: \(outcome)")
        }
        // 取消不改 cell 状态：仍 Running、可再 wait。
        let cells = await registry.cells(sessionKey: sessionKey)
        XCTAssertEqual(cells.first?.status, .running, "取消后 cell 必须保持 Running")
        // 二次 wait（正常路径）仍可读：cell 未被取消消费。
        let second = await registry.wait(cellId: "cell-cancel-1", sessionKey: sessionKey, timeoutMs: 1000, terminate: false)
        guard case .stillRunning = second else {
            return XCTFail("取消后的 cell 再 wait 应为 stillRunning（未消费），实际: \(second)")
        }
    }

    /// 红测试对应缺陷：terminate=true 只处理调用者自身，同 cell 的其他 waiter
    /// 继续阻塞到各自超时。修复后 terminate 唤醒所有 waiter（返回 terminated 终态）。
    func testTerminateWakesOtherWaitersWithTerminalRecord() async {
        let registry = IOSJsCellRegistry(directory: tempDirectory())
        let sessionKey = "sess-term"
        let started = await registry.startCell(sessionKey: sessionKey, cellId: "cell-term-1")
        XCTAssertEqual(started, .started)

        let waiterA = Task { @MainActor in
            await registry.wait(cellId: "cell-term-1", sessionKey: sessionKey, timeoutMs: 3000, terminate: false)
        }
        let waiterB = Task { @MainActor in
            await registry.wait(cellId: "cell-term-1", sessionKey: sessionKey, timeoutMs: 3000, terminate: false)
        }
        // 等两个 waiter 都注册。
        try? await Task.sleep(nanoseconds: 300_000_000)

        let terminateOutcome = await registry.wait(
            cellId: "cell-term-1", sessionKey: sessionKey, timeoutMs: 1000, terminate: true
        )
        guard case .terminal(let record) = terminateOutcome, record.status == .terminated else {
            return XCTFail("terminate 必须返回 terminated 终态，实际: \(terminateOutcome)")
        }

        let outcomeA = await waiterA.value
        let outcomeB = await waiterB.value
        guard case .terminal(let recordA) = outcomeA, recordA.status == .terminated else {
            return XCTFail("terminate 必须唤醒 waiter A（terminated），实际: \(outcomeA)")
        }
        guard case .terminal(let recordB) = outcomeB, recordB.status == .terminated else {
            return XCTFail("terminate 必须唤醒 waiter B（terminated），实际: \(outcomeB)")
        }
        let remaining = await registry.cells(sessionKey: sessionKey)
        XCTAssertTrue(remaining.isEmpty, "terminate 读取后 cell 应清除")
    }

    func testWaitTimeoutClamp() {
        XCTAssertEqual(ChatToolRuntime.clampWaitTimeoutMs(500), 1000)
        XCTAssertEqual(ChatToolRuntime.clampWaitTimeoutMs(120000), 60000)
        XCTAssertEqual(ChatToolRuntime.clampWaitTimeoutMs(10000), 10000)
        XCTAssertEqual(ChatToolRuntime.clampWaitTimeoutMs(8000), 8000)
    }

    // MARK: - 并发上限

    func testFifthConcurrentCellIsRejected() async {
        let registry = IOSJsCellRegistry(directory: tempDirectory())
        let runtime = runtime(execEnabled: true, registry: registry)
        let conversationId = KotlinUuid.companion.random()
        let sessionKey = conversationId.description()

        // 4 个 ~5.5s 脚本、各自 1000ms 超时（exec 最小 clamp）→ 顺序发起也全部
        // yield 为 Running（第 5 个发起时 4 个脚本都还活着）。
        for index in 1...4 {
            let output = await executeTool(
                runtime,
                toolCall: toolCallPart(
                    toolCallId: "tc-exec-\(index)",
                    toolName: "exec",
                    input: #"{"code":"let end = Date.now() + 5500; while (Date.now() < end) {}; \#(index)","timeout_ms":1000}"#
                ),
                conversationId: conversationId
            )
            XCTAssertTrue(output.contains("Script running with cell ID"), "第 \(index) 个 cell 应 yield: \(output)")
        }
        let running = await registry.cells(sessionKey: sessionKey).filter { $0.status == .running }
        XCTAssertEqual(running.count, 4, "前 4 个 cell 应全部 Running")

        // 第 5 个并发 cell 被拒：结构化错误（脚本仍在跑，上限计数有效）。
        let fifth = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-exec-5",
                toolName: "exec",
                input: #"{"code":"2 + 2","timeout_ms":1000}"#
            ),
            conversationId: conversationId
        )
        XCTAssertTrue(fifth.contains("\"ok\":false"), "超限必须是结构化错误: \(fifth)")
        XCTAssertTrue(fifth.contains("已达上限"), "错误必须说明并发上限: \(fifth)")
        XCTAssertTrue(fifth.contains("4"), "错误必须说明上限值: \(fifth)")
    }

    // MARK: - store/load（会话 KV）

    func testStoreLoadSharedAcrossCellsInSameSession() async {
        let registry = IOSJsCellRegistry(directory: tempDirectory())
        let runtime = runtime(execEnabled: true, registry: registry)
        let conversationId = KotlinUuid.companion.random()

        // cell 1：写入并读回。
        let first = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-exec-1",
                toolName: "exec",
                input: #"{"code":"store('k', {a: 1, b: 'x'}); load('k')"}"#
            ),
            conversationId: conversationId
        )
        XCTAssertTrue(first.contains(#""result":"{\"a\":1,\"b\":\"x\"}""#), "cell 1 应写后读回对象: \(first)")

        // cell 2：同会话读同一 key（跨 cell 共享，且与 cell 1 完全独立求值）。
        let second = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-exec-2",
                toolName: "exec",
                input: #"{"code":"const v = load('k'); v.b + '|' + (v.a + 1)"}"#
            ),
            conversationId: conversationId
        )
        XCTAssertTrue(second.contains(#""result":"\"x|2\"""#), "cell 2 必须读到 cell 1 写入的值: \(second)")

        // 未存储的 key → undefined（不抛错）。求值结果是被 JSON 序列化的字符串
        // "undefined"（payload 里带转义引号）。
        let missing = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-exec-3",
                toolName: "exec",
                input: #"{"code":"typeof load('nope')"}"#
            ),
            conversationId: conversationId
        )
        XCTAssertTrue(missing.contains(#"\"undefined\""#), "缺失 key 的 load 必须是 undefined: \(missing)")
    }

    func testStoreOverLimitThrowsJsError() async {
        let registry = IOSJsCellRegistry(directory: tempDirectory())
        let runtime = runtime(execEnabled: true, registry: registry)
        let output = await executeTool(
            runtime,
            toolCall: toolCallPart(
                toolCallId: "tc-exec-big",
                toolName: "exec",
                input: #"{"code":"store('big', 'x'.repeat(70000)); 'ok'"}"#
            ),
            conversationId: KotlinUuid.companion.random()
        )
        // 超限 store 必须抛 JS 错误（脚本异常收口为 error payload），不得静默截断。
        XCTAssertTrue(output.contains("error"), "超限 store 必须报错: \(output)")
        XCTAssertTrue(output.contains("64 KB"), "错误必须说明单 key 上限: \(output)")
    }

    func testStorePersistsAcrossRegistryInstances() async {
        let directory = tempDirectory()
        let registryA = IOSJsCellRegistry(directory: directory)
        let stored = await registryA.storeValue(sessionKey: "sess-persist", key: "k", valueJSON: #"{"a":1}"#)
        XCTAssertEqual(stored, .stored)
        // 新持久化实例（同目录 = 模拟进程重启后冷启动）读回同一 sidecar。
        let registryB = IOSJsCellRegistry(directory: directory)
        let loaded = await registryB.loadValue(sessionKey: "sess-persist", key: "k")
        XCTAssertEqual(loaded, #"{"a":1}"#)
    }

    func testStoreTotalLimitIsEnforcedPerSession() async {
        let directory = tempDirectory()
        let registry = IOSJsCellRegistry(directory: directory)
        // 单 key 上限 64KB、总会话 1MB：16 个 64KB 值恰好压线（1048576 字节），
        // 第 17 个必须被总会话上限拒绝。
        let chunk = String(repeating: "y", count: 64 * 1024)
        for index in 1...16 {
            let stored = await registry.storeValue(sessionKey: "sess-total", key: "k\(index)", valueJSON: chunk)
            guard stored == .stored else {
                return XCTFail("第 \(index) 个 64KB 值应在单 key/总量限制内: \(stored)")
            }
        }
        let outcome = await registry.storeValue(sessionKey: "sess-total", key: "k17", valueJSON: chunk)
        guard case .overLimit(let reason) = outcome else {
            return XCTFail("超过 1MB 总会话上限必须被拒: \(outcome)")
        }
        XCTAssertTrue(reason.contains("1 MB"), "错误必须说明总会话上限: \(reason)")
    }

    // MARK: - P3-d 安全审查：16 未读终态 cell 驱逐

    func testTerminalCellRetentionEvictsOldestAfterSixteen() async {
        let registry = IOSJsCellRegistry(directory: tempDirectory())
        // 模型放弃的 yield cell 会以终态留在注册表（read-once 语义）。驱逐兜底在
        // startCell 触发：每会话最多保留 16 个未读终态 cell，超限时最旧的先被
        // 逐出（running cell 永不被逐），防止被放弃的 cell 无限累积。
        for index in 1...17 {
            let started = await registry.startCell(sessionKey: "sess-retention", cellId: "cell-\(index)")
            guard started == .started else {
                return XCTFail("cell \(index) must start: \(started)")
            }
            await registry.finishCell(
                sessionKey: "sess-retention",
                cellId: "cell-\(index)",
                result: .success(result: "\(index)", logs: []),
                maxOutputChars: 10000
            )
        }
        // 17 个终态 + 0 running：第 18 个 cell 落地时（驱逐点）最旧的 cell-1 被逐出，
        // 注册表回到 16 终态上限。
        let started = await registry.startCell(sessionKey: "sess-retention", cellId: "cell-18")
        XCTAssertEqual(started, .started)
        let after = await registry.cells(sessionKey: "sess-retention")
        XCTAssertEqual(after.filter { $0.status == .running }.count, 1, "new cell must be running")
        XCTAssertEqual(
            after.filter { $0.status != .running }.count, 16,
            "terminal retention bound (16) must be enforced at the next startCell, got \(after.count)"
        )
        XCTAssertFalse(after.contains { $0.cellId == "cell-1" },
                       "oldest terminal cell must be evicted first")
        XCTAssertTrue(after.contains { $0.cellId == "cell-17" },
                      "newest terminal cell must be kept")
        XCTAssertTrue(after.contains { $0.cellId == "cell-18" })
    }

    // MARK: - 冷启动 sweep

    func testColdStartMarksRunningCellInterrupted() async {
        let directory = tempDirectory()
        let registryA = IOSJsCellRegistry(directory: directory)
        let started = await registryA.startCell(sessionKey: "sess-cold", cellId: "cell-cold-1")
        guard started == .started else {
            return XCTFail("startCell must start, got \(started)")
        }
        // 进程死亡模拟：新注册表实例读同一个 sidecar → Running 一律标 interrupted，
        // 不假 completion。
        let registryB = IOSJsCellRegistry(directory: directory)
        let outcome = await registryB.wait(cellId: "cell-cold-1", sessionKey: "sess-cold", timeoutMs: 1000, terminate: false)
        guard case .terminal(let record) = outcome else {
            return XCTFail("冷启动后 wait 必须返回终态，got \(outcome)")
        }
        XCTAssertEqual(record.status, .interrupted, "冷启动 Running cell 必须标 interrupted 而非 completed")
        XCTAssertNil(record.output, "interrupted 不得携带伪造输出")
        let remaining = await registryB.cells(sessionKey: "sess-cold")
        XCTAssertTrue(remaining.isEmpty, "interrupted cell 读取后应清除")
    }

    // MARK: - 引擎集成：exec yield → 模型 wait → 结果回灌

    func testExecYieldThenModelWaitFeedsResultBackThroughEngine() async throws {
        let directory = tempDirectory()
        let registry = IOSJsCellRegistry(directory: directory)
        let runtime = runtime(execEnabled: true, registry: registry)
        let conversationId = KotlinUuid.companion.random()
        let declarations = try XCTUnwrap(ToolKt.iosToolDeclarations(names: ["exec", "wait"]))
        let params = makeParams(tools: declarations)
        let executors = runtime.backgroundToolExecutors(
            providerSetting: makeProviderSetting(),
            params: params,
            runId: "run-js-cell-bg",
            conversationId: conversationId
        )
        XCTAssertNotNil(executors["exec"], "后台 run 必须注册 exec executor")
        XCTAssertNotNil(executors["wait"], "后台 run 必须注册 wait executor")

        let provider = ExecWaitScriptedProvider(
            execInput: #"{"code":"let end = Date.now() + 1800; while (Date.now() < end) {}; 6 * 7","timeout_ms":1000}"#
        )
        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: executors,
            configuration: .init(maxSteps: 3)
        )
        let result = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [makeMessage(role: .user, parts: [UIMessagePart.Text(text: "run it", metadata: nil)])],
            params: params
        )

        // wait 是普通工具调用：占一次模型轮次（exec 轮 + wait 轮 + 终答轮 = 3 步），
        // 受引擎步数预算兜底，无独立预算机制。
        XCTAssertEqual(provider.callCount, 3, "引擎必须走 exec → wait → 终答三轮")
        XCTAssertEqual(result.stepsExecuted, 3, "wait 必须消耗一次工具续跑步数")
        XCTAssertFalse(result.hitStepLimit)

        let allText = toolOutputText(result.messages)
        XCTAssertTrue(allText.contains("\"status\":\"completed\""), "模型 wait 的结果必须回灌: \(allText)")
        XCTAssertTrue(allText.contains(#"\"result\":\"42\""#), "回灌结果必须携带求值输出: \(allText)")

        let leftover = await registry.cells(sessionKey: conversationId.description())
        XCTAssertTrue(leftover.isEmpty, "闭环后注册表不应残留 cell")
    }
}

/// P3-c: 脚本化 provider —— 第一轮发 exec（长脚本 + 短超时 → yield），第二轮从
/// 上一轮 exec 的工具输出里解析 cell_id 并发 wait，之后返回终答文本。
private final class ExecWaitScriptedProvider: IOSAgentTextProvider, @unchecked Sendable {
    private let execInput: String
    private var phase = 0
    private(set) var callCount = 0

    init(execInput: String) {
        self.execInput = execInput
    }

    func generateText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        callCount += 1
        switch phase {
        case 0:
            phase = 1
            return chunk(with: assistantToolCall(toolCallId: "tc-exec", toolName: "exec", input: execInput))
        case 1:
            phase = 2
            let cellID = Self.extractCellID(from: messages)
            return chunk(with: assistantToolCall(
                toolCallId: "tc-wait",
                toolName: "wait",
                input: #"{"cell_id":"\#(cellID)","timeout_ms":8000}"#
            ))
        default:
            return chunk(with: UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: "done", metadata: nil)],
                annotations: [],
                createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 19, hour: 0, minute: 0, second: 0, nanosecond: 0),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            ))
        }
    }

    private static func extractCellID(from messages: [UIMessage]) -> String {
        for message in messages {
            for part in message.parts {
                guard let tool = part as? UIMessagePart.Tool, tool.toolName == "exec" else { continue }
                let outputText = tool.output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()
                if let data = outputText.data(using: .utf8),
                   let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let cellID = object["cell_id"] as? String {
                    return cellID
                }
            }
        }
        return ""
    }

    private func assistantToolCall(toolCallId: String, toolName: String, input: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Tool(
                    toolCallId: toolCallId,
                    toolName: toolName,
                    input: input,
                    output: [],
                    approvalState: ToolApprovalState.Auto.shared,
                    streamIndex: nil,
                    metadata: nil
                )
            ],
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 19, hour: 0, minute: 0, second: 0, nanosecond: 0),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func chunk(with message: UIMessage) -> MessageChunk {
        MessageChunk(
            id: "chunk-\(UUID().uuidString)",
            model: "test-model",
            choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: "stop")],
            usage: nil
        )
    }
}
