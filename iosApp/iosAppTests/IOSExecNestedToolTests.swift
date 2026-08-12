import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P3-b: exec 嵌套 tools 桥的集成契约——真实 JavaScriptCore 求值 + 真实
/// ChatToolRuntime 分发 + ChatGenerationCoordinator 的嵌套 runner（同一
/// 执行路径：分类、账本 Started/Finished、审批暂停/恢复）。
///
/// 覆盖：
/// - 嵌套 workspace 工具经完整链路执行，账本各记一条 started/finished；
/// - 需审批的嵌套工具触发审批暂停（卡片经 bindings 可见），批准后 JS 继续；
/// - 白名单沿当轮可见工具集传递（exec 自身与编排工具不可见）。
@MainActor
final class IOSExecNestedToolTests: XCTestCase {

    // MARK: - Fixtures

    private func isolatedDefaults() -> UserDefaults {
        UserDefaults(suiteName: "IOSExecNestedToolTests-\(UUID().uuidString)")!
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "exec-nested-test",
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
            modelId: "exec-nested-test-model",
            displayName: "exec-nested-test-model",
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

    private func makeAssistantMessage(parts: [UIMessagePart]) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: parts,
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 19, hour: 0, minute: 0, second: 0, nanosecond: 0),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func execToolCall(id: String = "tc-exec", input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: id,
            toolName: "exec",
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func makeWorkspaceStore() throws -> (store: IOSWorkspaceStore, directory: URL) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSExecNestedToolTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return (IOSWorkspaceStore(baseDirectory: directory), directory)
    }

    /// 生产拼装的最小镜像：run 状态 + exposure bridge + 嵌套 runner
    /// （与 `ChatGenerationCoordinator.executeToolCall` 用同一
    /// `makeNestedExecToolRunner` 组装），再执行一次 exec 求值。
    private func runExecThroughCoordinator(
        coordinator: ChatGenerationCoordinator,
        toolCall: UIMessagePart.Tool,
        bridge: IosToolExposureBridge,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String
    ) async -> ChatToolRuntimeResult {
        let pending = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: providerSetting,
            params: params,
            runId: runId,
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [makeAssistantMessage(parts: [toolCall])]
        )
        return await coordinator.executeExecWithNestedToolsForTesting(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: pending,
            toolExposureBridge: bridge
        )
    }

    private func toolOutputText(_ messages: [UIMessage]) -> String {
        messages.flatMap { $0.parts.compactMap { ($0 as? UIMessagePart.Tool)?.output.compactMap { ($0 as? UIMessagePart.Text)?.text } } }
            .flatMap { $0 }
            .joined()
    }

    /// 从 exec 工具 payload（`{"result": <jsonified last expression>, ...}`）里
    /// 解析出 last-expression 的 JSON 文本（payload 内层引号已转义，逐字断言会
    /// 被双重转义干扰，先解析再断言）。
    private func execResultText(from outputText: String) -> String? {
        guard let data = outputText.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let result = object["result"] as? String else {
            return nil
        }
        return result
    }

    private func makeCoordinatorAndRuntime(
        executor: IOSLocalToolExecutor?,
        ledger: IOSAgentRunLedgering,
        state: IOSExecNestedBindingState
    ) -> (ChatGenerationCoordinator, ChatToolRuntime, IOSSharedSettingsStore) {
        let defaults = isolatedDefaults()
        let settingsStore = SettingsStore(userDefaults: defaults)
        settingsStore.execJavaScriptEnabled = true
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        let runtime = ChatToolRuntime(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            localToolExecutor: executor,
            searchTransport: IOSExecNestedNoopSearchTransport(),
            mcpManager: IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared)
        )
        let coordinator = ChatGenerationCoordinator(
            dependencies: ChatGenerationDependencies(
                settingsStore: settingsStore,
                sharedSettings: sharedSettings,
                localToolExecutor: executor,
                searchTransport: IOSExecNestedNoopSearchTransport(),
                liveActivityController: .shared,
                autoGenerateResponses: false,
                mcpManager: IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared),
                orchestrationToolService: nil,
                memoryPollutionMarker: nil
            ),
            bindings: state.bindings(),
            toolLedger: ledger
        )
        return (coordinator, runtime, sharedSettings)
    }

    // MARK: - Ledger inheritance

    func testNestedWorkspaceToolRecordsLedgerStartedAndFinished() async throws {
        let ledger = IOSExecNestedRecordingLedger()
        let state = IOSExecNestedBindingState()
        let (workspaceStore, _) = try makeWorkspaceStore()
        let permissionStore = IOSPermissionStore(userDefaults: isolatedDefaults())
        // workspace_file_write 是高风险能力（availablePolicies 只含
        // autoApproveHighRisk），设 autoApprove 会被规范化丢弃 → 写仍然触发审批。
        // 本测试聚焦账本，用高风险自动批准跳过审批卡。
        let capability = try XCTUnwrap(IOSCapabilityRegistry.capability(forToolName: "workspace_file_write"))
        permissionStore.setPolicy(.autoApproveHighRisk, for: capability)
        let executor = IOSLocalToolExecutor(
            permissionStore: permissionStore,
            documentStore: DocumentAccessStore(),
            workspaceStore: workspaceStore
        )
        let (coordinator, runtime, _) = makeCoordinatorAndRuntime(
            executor: executor,
            ledger: ledger,
            state: state
        )
        let declarations = ToolKt.iosToolDeclarations(names: ["exec", "workspace_file_write"])
        let bridge = IosToolExposureBridge(tools: declarations)
        let params = makeParams(tools: declarations)
        let providerSetting = makeProviderSetting()

        let toolCall = execToolCall(input: #"{"code":"const r = tools.workspace_file_write({path: '/workspace/notes/a.md', content: 'hi'}); ({ok: r.ok, path: r.path})"}"#)
        let result = await runExecThroughCoordinator(
            coordinator: coordinator,
            toolCall: toolCall,
            bridge: bridge,
            providerSetting: providerSetting,
            params: params,
            runId: "run-exec-nested-ledger"
        )
        guard case .completed(let messages) = result else {
            return XCTFail("exec must complete through the nested runner, got \(result)")
        }
        let resultText = try XCTUnwrap(
            execResultText(from: toolOutputText(messages)),
            "exec payload must carry the last-expression JSON"
        )
        XCTAssertTrue(resultText.contains(#""ok":true"#),
                      "nested workspace write result must flow back to JS and out: \(resultText)")

        // 账本：嵌套调用记 started/finished 各一（同一执行路径的 Started/Finished 对）。
        let nestedStarted = await ledger.started
        let nestedFinished = await ledger.finished
        XCTAssertEqual(nestedStarted.count, 1, "exactly one Started for the nested call")
        XCTAssertEqual(nestedFinished.count, 1, "exactly one Finished for the nested call")
        let started = try XCTUnwrap(nestedStarted.first)
        XCTAssertEqual(started.toolName, "workspace_file_write")
        XCTAssertTrue(started.toolCallId.hasPrefix("exec-nested-"), "nested provenance is the exec-nested- toolCallId prefix")
        XCTAssertEqual(started.effectClass, .sideEffect, "workspace writes classify as sideEffect (same map as top-level)")
        let finished = try XCTUnwrap(nestedFinished.first)
        XCTAssertEqual(finished.toolCallId, started.toolCallId)
        XCTAssertEqual(finished.outcome, "completed")
    }

    // MARK: - Approval pause / resume (JS thread stays blocked)

    func testNestedWorkspaceWritePausesForApprovalAndResumesAfterApproval() async throws {
        let ledger = IOSExecNestedRecordingLedger()
        let state = IOSExecNestedBindingState()
        let (workspaceStore, directory) = try makeWorkspaceStore()
        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore(),
            workspaceStore: workspaceStore
        )
        let (coordinator, runtime, _) = makeCoordinatorAndRuntime(
            executor: executor,
            ledger: ledger,
            state: state
        )
        let declarations = ToolKt.iosToolDeclarations(names: ["exec", "workspace_file_write"])
        let bridge = IosToolExposureBridge(tools: declarations)
        let params = makeParams(tools: declarations)
        let providerSetting = makeProviderSetting()

        let toolCall = execToolCall(input: #"{"code":"const r = tools.workspace_file_write({path: '/workspace/notes/summary.md', content: 'hello workspace'}); ({ok: r.ok})"}"#)
        let pending = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: providerSetting,
            params: params,
            runId: "run-exec-nested-approval",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [makeAssistantMessage(parts: [toolCall])]
        )
        let execTask = Task { @MainActor in
            await coordinator.executeExecWithNestedToolsForTesting(
                ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
                context: pending,
                toolExposureBridge: bridge
            )
        }

        // 审批暂停：卡片必须经 bindings 出现，JS 线程保持阻塞（exec 未完成）。
        let deadline = Date().addingTimeInterval(10)
        while state.pendingWorkspaceApproval == nil && Date() < deadline {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        let request = try XCTUnwrap(state.pendingWorkspaceApproval, "nested workspace write must surface an approval card")
        XCTAssertEqual(request.toolName, "workspace_file_write")

        // 批准后同一 finish 路径执行写入，结果回到阻塞的 JS，exec 完成。
        await coordinator.approvePendingWorkspaceTool()
        let result = await execTask.value
        guard case .completed(let messages) = result else {
            return XCTFail("exec must complete after approval, got \(result)")
        }
        let resultText = try XCTUnwrap(
            execResultText(from: toolOutputText(messages)),
            "exec payload must carry the last-expression JSON"
        )
        XCTAssertTrue(resultText.contains(#""ok":true"#), "approved nested write must complete in JS: \(resultText)")
        // 真实落盘到隔离 workspace（baseDirectory/AmberWorkspace/files/...）。
        XCTAssertTrue(
            FileManager.default.fileExists(
                atPath: directory.appendingPathComponent("AmberWorkspace/files/notes/summary.md").path
            ),
            "approved nested write must reach the workspace store"
        )

        // 账本：首次尝试 Started→Finished(paused_for_approval)，批准后再一对
        // Started→Finished(completed)（与顶层审批语义一致）。
        let started = await ledger.started
        let finished = await ledger.finished
        XCTAssertEqual(started.count, 2, "first attempt + post-approval attempt")
        XCTAssertEqual(finished.count, 2)
        let paused = try XCTUnwrap(finished.first { $0.outcome == "paused_for_approval" })
        XCTAssertEqual(paused.toolCallId, started.first?.toolCallId)
        let completed = try XCTUnwrap(finished.last)
        XCTAssertEqual(completed.outcome, "completed")
        XCTAssertEqual(completed.toolCallId, started.last?.toolCallId)
    }

    func testNestedApprovalDeniedReturnsStructuredDenialToJS() async throws {
        let ledger = IOSExecNestedRecordingLedger()
        let state = IOSExecNestedBindingState()
        let (workspaceStore, _) = try makeWorkspaceStore()
        let executor = IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: DocumentAccessStore(),
            workspaceStore: workspaceStore
        )
        let (coordinator, runtime, _) = makeCoordinatorAndRuntime(
            executor: executor,
            ledger: ledger,
            state: state
        )
        let declarations = ToolKt.iosToolDeclarations(names: ["exec", "workspace_file_write"])
        let bridge = IosToolExposureBridge(tools: declarations)
        let params = makeParams(tools: declarations)
        let providerSetting = makeProviderSetting()

        let toolCall = execToolCall(input: #"{"code":"const r = tools.workspace_file_write({path: '/workspace/notes/nope.md', content: 'x'}); ({ok: r.ok})"}"#)
        let pending = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: providerSetting,
            params: params,
            runId: "run-exec-nested-deny",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [makeAssistantMessage(parts: [toolCall])]
        )
        let execTask = Task { @MainActor in
            await coordinator.executeExecWithNestedToolsForTesting(
                ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
                context: pending,
                toolExposureBridge: bridge
            )
        }
        let deadline = Date().addingTimeInterval(10)
        while state.pendingWorkspaceApproval == nil && Date() < deadline {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertNotNil(state.pendingWorkspaceApproval, "approval card must appear")

        await coordinator.denyPendingWorkspaceTool()
        let result = await execTask.value
        guard case .completed(let messages) = result else {
            return XCTFail("exec must complete after denial, got \(result)")
        }
        let resultText = try XCTUnwrap(
            execResultText(from: toolOutputText(messages)),
            "exec payload must carry the last-expression JSON"
        )
        // 拒绝文本回到 JS：workspace 拒绝 JSON 的 ok=false → r.ok === false。
        XCTAssertTrue(resultText.contains(#""ok":false"#), "denial must flow back to JS: \(resultText)")
    }

    // MARK: - Whitelist end-to-end

    func testExcludedToolsAreNotVisibleInExecScript() async throws {
        let ledger = IOSExecNestedRecordingLedger()
        let state = IOSExecNestedBindingState()
        let (coordinator, runtime, _) = makeCoordinatorAndRuntime(
            executor: nil,
            ledger: ledger,
            state: state
        )
        let declarations = ToolKt.iosToolDeclarations(names: ["exec", "workspace_file_read"])
        let bridge = IosToolExposureBridge(tools: declarations)
        let params = makeParams(tools: declarations)
        let providerSetting = makeProviderSetting()

        let toolCall = execToolCall(input: #"{"code":"typeof tools.exec + '|' + typeof tools.spawn_agent + '|' + typeof tools.workspace_file_read"}"#)
        let result = await runExecThroughCoordinator(
            coordinator: coordinator,
            toolCall: toolCall,
            bridge: bridge,
            providerSetting: providerSetting,
            params: params,
            runId: "run-exec-nested-whitelist"
        )
        guard case .completed(let messages) = result else {
            return XCTFail("expected completed, got \(result)")
        }
        let resultText = try XCTUnwrap(
            execResultText(from: toolOutputText(messages)),
            "exec payload must carry the last-expression JSON"
        )
        XCTAssertEqual(resultText, #""undefined|undefined|function""#,
                       "whitelist must exclude exec + orchestration and keep visible tools: \(resultText)")
    }

    // MARK: - P3-d: ALL_TOOLS discovery metadata (integration)

    func testAllToolsThroughCoordinatorCarriesRealDescriptionsAndExcludesVisibleExcludedTools() async throws {
        let ledger = IOSExecNestedRecordingLedger()
        let state = IOSExecNestedBindingState()
        let (coordinator, runtime, _) = makeCoordinatorAndRuntime(
            executor: nil,
            ledger: ledger,
            state: state
        )
        // 小声明集 → 桥非 lazy，visibleTools = 全部声明 + tool_search。exec /
        // spawn_agent / ask_user 都可见于当轮工具面，但白名单排除集必须把它们
        // 挡在 ALL_TOOLS 之外——集成级排除证明（不是 defer 池的偶然缺席）。
        let declarations = ToolKt.iosToolDeclarations(
            names: ["exec", "workspace_file_read", "spawn_agent", "ask_user"]
        )
        let bridge = IosToolExposureBridge(tools: declarations)
        let params = makeParams(tools: declarations)
        let providerSetting = makeProviderSetting()

        let toolCall = execToolCall(input: #"""
        {"code":"const names = ALL_TOOLS.map(t => t.name); const read = ALL_TOOLS.filter(t => t.name === 'workspace_file_read')[0]; ({names: names, hasRead: !!read, descriptionPrefix: read ? read.description.slice(0, 4) : '', noExec: names.indexOf('exec') === -1, noSpawn: names.indexOf('spawn_agent') === -1, noAsk: names.indexOf('ask_user') === -1, noSearch: names.indexOf('tool_search') === -1, frozen: Object.isFrozen(ALL_TOOLS)})"}
        """#)
        let result = await runExecThroughCoordinator(
            coordinator: coordinator,
            toolCall: toolCall,
            bridge: bridge,
            providerSetting: providerSetting,
            params: params,
            runId: "run-exec-all-tools"
        )
        guard case .completed(let messages) = result else {
            return XCTFail("exec must complete through the nested runner, got \(result)")
        }
        let resultText = try XCTUnwrap(
            execResultText(from: toolOutputText(messages)),
            "exec payload must carry the last-expression JSON"
        )
        XCTAssertTrue(resultText.contains(#""names":["workspace_file_read"]"#),
                      "ALL_TOOLS must be exactly the whitelist: \(resultText)")
        XCTAssertTrue(resultText.contains(#""hasRead":true"#))
        XCTAssertTrue(resultText.contains(#""descriptionPrefix":"Read""#),
                      "ALL_TOOLS must carry the real KMP declaration description: \(resultText)")
        XCTAssertTrue(resultText.contains(#""noExec":true"#), "exec itself must be excluded: \(resultText)")
        XCTAssertTrue(resultText.contains(#""noSpawn":true"#), "orchestration must be excluded: \(resultText)")
        XCTAssertTrue(resultText.contains(#""noAsk":true"#), "ask_user must be excluded: \(resultText)")
        XCTAssertTrue(resultText.contains(#""noSearch":true"#), "tool_search must be excluded: \(resultText)")
        XCTAssertTrue(resultText.contains(#""frozen":true"#), "ALL_TOOLS must be frozen: \(resultText)")
    }

    func testAllToolsFilterByDescriptionFindsAndCallsToolThroughRealChain() async throws {
        let ledger = IOSExecNestedRecordingLedger()
        let state = IOSExecNestedBindingState()
        let (workspaceStore, _) = try makeWorkspaceStore()
        // 读工具在 fresh permission store 下默认 askEveryTime → 嵌套调用会挂起等
        // 审批卡。读能力是 sensitive/可复用 gate（availablePolicies 只含
        // autoApprove），设 autoApprove 跳过审批，聚焦 ALL_TOOLS 过滤发现 →
        // 真实执行链调用。
        let permissionStore = IOSPermissionStore(userDefaults: isolatedDefaults())
        let readCapability = try XCTUnwrap(
            IOSCapabilityRegistry.capability(forToolName: "workspace_file_read")
        )
        permissionStore.setPolicy(.autoApprove, for: readCapability)
        XCTAssertEqual(permissionStore.policy(for: readCapability), .autoApprove)
        let executor = IOSLocalToolExecutor(
            permissionStore: permissionStore,
            documentStore: DocumentAccessStore(),
            workspaceStore: workspaceStore
        )
        let (coordinator, runtime, _) = makeCoordinatorAndRuntime(
            executor: executor,
            ledger: ledger,
            state: state
        )
        let declarations = ToolKt.iosToolDeclarations(names: ["exec", "workspace_file_read"])
        let bridge = IosToolExposureBridge(tools: declarations)
        let params = makeParams(tools: declarations)
        let providerSetting = makeProviderSetting()

        // 按 description 过滤发现工具名，再经 tools[found.name] 走真实嵌套链
        // （同一执行路径：分类 → 执行 → 账本 Started/Finished）。
        let toolCall = execToolCall(input: #"""
        {"code":"const found = ALL_TOOLS.filter(t => t.description.indexOf('Read text preview') !== -1)[0]; const r = tools[found.name]({path: '/workspace/notes/missing.md'}); ({name: found.name, isObject: typeof r === 'object', hasOk: typeof r === 'object' && 'ok' in r})"}
        """#)
        let result = await runExecThroughCoordinator(
            coordinator: coordinator,
            toolCall: toolCall,
            bridge: bridge,
            providerSetting: providerSetting,
            params: params,
            runId: "run-exec-all-tools-call"
        )
        guard case .completed(let messages) = result else {
            return XCTFail("exec must complete through the nested runner, got \(result)")
        }
        let resultText = try XCTUnwrap(
            execResultText(from: toolOutputText(messages)),
            "exec payload must carry the last-expression JSON"
        )
        XCTAssertTrue(resultText.contains(#""name":"workspace_file_read""#),
                      "discovery by description must resolve the whitelisted name: \(resultText)")
        XCTAssertTrue(resultText.contains(#""isObject":true"#))
        XCTAssertTrue(resultText.contains(#""hasOk":true"#),
                      "the found tool must have executed through the real chain: \(resultText)")
    }

    // MARK: - P3-d 安全审查：exec/wait 的 effectClass 分类钉死

    func testExecAndWaitEffectClassClassificationIsPinned() {
        // exec 重放会重复运行任意 JS → sideEffect；wait 会推进/终止 cell 状态
        // （terminate 真实变更），保守按 fail-safe sideEffect 分类（崩溃后不
        // 自动重试 wait，避免读到推进后的另一终态）。两条路径分类一致。
        XCTAssertEqual(IOSToolEffectClassMapping.forToolName("exec", input: "{}"), .sideEffect)
        XCTAssertEqual(IOSToolEffectClassMapping.forToolName("wait", input: "{}"), .sideEffect)
        XCTAssertEqual(IOSToolEffectClassMapping.forChatKind(.advanced, input: "{}"), .sideEffect)
    }
}

// MARK: - Fixtures

/// I-1 ledger spy：记录 Started/Finished 调用，验证嵌套调用走同一账本路径。
private actor IOSExecNestedRecordingLedger: IOSAgentRunLedgering {
    private(set) var started: [(runId: String, toolCallId: String, toolName: String, effectClass: IOSToolEffectClass)] = []
    private(set) var finished: [(runId: String, toolCallId: String, outcome: String)] = []

    func recordToolCallStarted(
        runId: String,
        toolCallId: String,
        toolName: String,
        argsDigest: String,
        effectClass: IOSToolEffectClass
    ) async -> Bool {
        started.append((runId, toolCallId, toolName, effectClass))
        return true
    }

    func recordToolCallFinished(
        runId: String,
        toolCallId: String,
        outcome: String
    ) async {
        await recordToolCallFinished(
            runId: runId, toolCallId: toolCallId, outcome: outcome,
            artifactId: nil, artifactVersion: nil, outcomeKind: nil, errorCode: nil, sourceRef: nil
        )
    }

    func recordToolCallFinished(
        runId: String,
        toolCallId: String,
        outcome: String,
        artifactId: String? = nil,
        artifactVersion: String? = nil,
        outcomeKind: String? = nil,
        errorCode: String? = nil,
        sourceRef: String? = nil
    ) async {
        finished.append((runId, toolCallId, outcome))
    }

    func recordApprovalDenied(
        runId: String,
        toolCallId: String,
        toolName: String,
        reason: String,
        capabilityId: String?
    ) async {
    }
}

/// bindings 状态捕获（同 IOSRunSnapshotTests 的 harness 模式）。
private final class IOSExecNestedBindingState {
    var messages: [UIMessage] = []
    var pendingMemoryApproval: MemoryToolApprovalRequest?
    var pendingSearchApproval: SearchToolApprovalRequest?
    var pendingWebMountApproval: WebMountToolApprovalRequest?
    var pendingWorkspaceApproval: WorkspaceToolApprovalRequest?
    var pendingIshHandoffApproval: IshHandoffToolApprovalRequest?
    var pendingMcpApproval: McpToolApprovalRequest?
    var pendingCouncilApproval: CouncilToolApprovalRequest?
    var pendingAskUser: ChatAskUserRequest?
    var revisions: [ChatMessageUpdateReason] = []
    var isLoading = false

    func bindings() -> ChatGenerationBindings {
        ChatGenerationBindings(
            getMessages: { self.messages },
            setMessages: { self.messages = $0 },
            bumpMessageRevision: { self.revisions.append($0) },
            shouldPaceStreamPresentation: { true },
            setIsLoading: { self.isLoading = $0 },
            setPendingMemoryApproval: { self.pendingMemoryApproval = $0 },
            setPendingSearchApproval: { self.pendingSearchApproval = $0 },
            setPendingWebMountApproval: { self.pendingWebMountApproval = $0 },
            setPendingWorkspaceApproval: { self.pendingWorkspaceApproval = $0 },
            setPendingIshHandoffApproval: { self.pendingIshHandoffApproval = $0 },
            setPendingMcpApproval: { self.pendingMcpApproval = $0 },
            setPendingCouncilApproval: { self.pendingCouncilApproval = $0 },
            setPendingAskUser: { self.pendingAskUser = $0 },
            setContextCompactState: { _ in },
            persistMessages: { _ in true },
            capturePersistMessagesBaseline: { _ in nil },
            persistMessagesSnapshot: { _, _, _ in true },
            recordRun: { _, _, _, _, _ in true },
            startLiveActivity: { _, _, _ in },
            saveMiniAppIfPresent: { _, _ in nil },
            messagesByInjectingRuntimeContext: { $0 },
            userFacingGenerationError: { rawMessage, _ in rawMessage }
        )
    }
}

/// 无网络搜索传输（同 IOSRunSnapshotTests 的 NoopSearchTransport 模式）。
private struct IOSExecNestedNoopSearchTransport: IOSSearchHTTPTransport {
    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: [:]
        )!
        return (response, Data())
    }
}
