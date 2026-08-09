import Foundation
@preconcurrency import Shared

func chatToolCallKey(_ toolCall: UIMessagePart.Tool) -> String {
    let id = toolCall.toolCallId.trimmingCharacters(in: .whitespacesAndNewlines)
    if !id.isEmpty { return id }
    return "\(toolCall.toolName):\(toolCall.input)"
}

/// P3-b: runs one nested tool call from inside an `exec` evaluation through
/// the same top-level execution path (same `ChatToolRuntime` dispatch with
/// its approval pause/resume, same ledger Started/Finished pair). Implemented
/// by `ChatGenerationCoordinator` (it owns the ledger and the approval UI);
/// `ChatToolRuntime` only threads it into the sandbox's synchronous
/// `tools` bridge.
typealias IosExecNestedToolRunner = @MainActor (String, String) async -> String

private final class IOSClosureToolExecutor: IOSToolExecutor {
    private let handler: @MainActor (String, String, Bool) async -> IOSAgentToolOutcome

    init(_ handler: @escaping @MainActor (String, String, Bool) async -> IOSAgentToolOutcome) {
        self.handler = handler
    }

    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        await handler(name, arguments, isUserInitiated)
    }
}

private extension IOSLocalToolExecutionOutput {
    var isSuccessfulToolResult: Bool {
        switch self {
        case .selectedFilePreview, .permissionsStatus, .ishExecuteResult, .ishHandoffResult, .workspaceResult, .webMountResult:
            true
        case .needsUserAction, .denied, .failed:
            false
        }
    }
}

enum ChatPendingToolKind {
    case toolSearch
    case search
    case workspace
    case ish
    case webMount
    case memory
    case image
    case advanced
    case askUser
    /// 跨会话读取工具（session_search/session_read）：本地只读，无审批，照
    /// tool_search/tools_list 模式独立成类（进 advanced 会因分类侧副作用违约）。
    case sessionRead
}

/// P2-a: 记忆污染置位的工具名判定（harness 拥有，不经模型）。只含明确外部上下文
/// 来源：web 搜索 / 网页读取 / MCP 直调与 `mcp__*` 展开。`wm_*` 读内网也读外网，
/// 误标会扩大停抽范围，待有 URL 分类后再纳入（AGENT_ORCHESTRATION_ADOPTION_PLAN
/// P2.5）。与 Android 侧常量集合保持一致，避免双轨漂移。
enum ConversationMemoryPollutionPolicy {
    static let pollutingToolNames: Set<String> = ["search_web", "scrape_web", "mcp_call"]

    static func isPollutingToolName(_ name: String) -> Bool {
        pollutingToolNames.contains(name) || name.hasPrefix("mcp__")
    }
}

struct ChatPendingToolCall {
    let kind: ChatPendingToolKind
    let toolCall: UIMessagePart.Tool
}

enum ChatToolApprovalPrompt {
    case memory(MemoryToolApprovalRequest)
    case search(SearchToolApprovalRequest)
    case webMount(WebMountToolApprovalRequest)
    case workspace(WorkspaceToolApprovalRequest)
    case ish(IshHandoffToolApprovalRequest)
    case mcp(McpToolApprovalRequest)
    case council(CouncilToolApprovalRequest)
    case askUser(ChatAskUserRequest)

    var toolTitle: String {
        switch self {
        case .memory:
            "记忆写入"
        case .search(let request):
            request.toolName == "scrape_web" ? "网页读取" : "网络搜索"
        case .webMount:
            "WebMount"
        case .workspace:
            "Workspace"
        case .ish(let request):
            request.title
        case .mcp:
            "MCP 工具"
        case .council(let request):
            request.title
        case .askUser:
            "需要你的回答"
        }
    }

    var activityKind: AgentActivityKind {
        switch self {
        case .memory:
            .memory
        case .search(let request):
            request.toolName == "scrape_web" ? .web : .research
        case .webMount:
            .web
        case .workspace:
            .document
        case .ish:
            .command
        case .mcp, .council, .askUser:
            .workflow
        }
    }
}

enum ChatToolRuntimeResult {
    case completed([UIMessage])
    case waitingForApproval(ChatToolApprovalPrompt)
}

private enum ChatCodexImageConfig {
    case signedIn(providerId: String)
    case notSignedIn
    case notSelected
}

@MainActor
final class ChatToolRuntime {
    private let settingsStore: SettingsStore
    private let sharedSettings: IOSSharedSettingsStore
    private let localToolExecutor: IOSLocalToolExecutor?
    private let searchTransport: any IOSSearchHTTPTransport
    private let mcpManager: IOSMcpManager
    private let skillFileStore: IOSSkillFileStore
    private let mcpConfigStore: IOSMcpConfigStore
    /// P1-c: 线程编排工具执行体（spawn_agent/list_agents/interrupt_agent）。
    /// 可选：未注入时三工具返回结构化「不可用」错误而不是静默缺失。
    private let orchestrationToolService: IOSThreadOrchestrationToolService?
    /// 跨会话读取工具（session_search/session_read）的会话存储源。可选：
    /// 未注入时两工具返回结构化「不可用」错误（照 orchestrationToolService 先例；
    /// 测试注入隔离 store）。
    private let conversationStoreProvider: (() -> IOSConversationStore?)?
    /// P2-a: harness 拥有的记忆污染置位回调（conversationId, toolName）。由接线方
    /// 注入持久化（storage 写 + baseline 守卫）；nil = 不置位（零行为变化）。置位
    /// 判定（工具名 + 成功输出）在本 runtime 收口处完成，不经过模型。
    private let memoryPollutionMarker: ((KotlinUuid, String) -> Void)?
    private lazy var subAgentRunner = SubAgentRunner()
    private lazy var councilRunner = CouncilRunner()
    /// P3-a: JavaScriptCore 沙箱引擎（exec 纯求值）。每次求值独立 context +
    /// 独立串行队列；超时 abandon 语义见 IOSJsSandboxEngine。
    private lazy var jsSandboxEngine = IOSJsSandboxEngine()
    /// P3-c: 会话级 cell 注册表（exec cell + store/load KV 的唯一 owner，
    /// 跨 run 共享）。默认生产单例；测试注入隔离实例。
    private let jsCellRegistry: IOSJsCellRegistry
    private lazy var skillMcpToolService = IOSSkillMcpToolService(
        skillStore: skillFileStore,
        sharedSettings: sharedSettings,
        workspaceStore: .shared,
        mcpConfigStore: mcpConfigStore,
        mcpManager: mcpManager
    )

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        localToolExecutor: IOSLocalToolExecutor?,
        searchTransport: any IOSSearchHTTPTransport,
        mcpManager: IOSMcpManager,
        skillFileStore: IOSSkillFileStore = IOSSkillFileStore(),
        mcpConfigStore: IOSMcpConfigStore = .shared,
        orchestrationToolService: IOSThreadOrchestrationToolService? = nil,
        memoryPollutionMarker: ((KotlinUuid, String) -> Void)? = nil,
        jsCellRegistry: IOSJsCellRegistry? = nil,
        conversationStoreProvider: (() -> IOSConversationStore?)? = nil
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.localToolExecutor = localToolExecutor
        self.searchTransport = searchTransport
        self.mcpManager = mcpManager
        self.skillFileStore = skillFileStore
        self.mcpConfigStore = mcpConfigStore
        self.orchestrationToolService = orchestrationToolService
        self.memoryPollutionMarker = memoryPollutionMarker
        self.jsCellRegistry = jsCellRegistry ?? .shared
        self.conversationStoreProvider = conversationStoreProvider
    }

    /// Tool set for the novel discussion agent. Ask User is always available;
    /// search continues to respect the global Web Search consent switch.
    func novelDiscussionToolExecutors() -> [String: any IOSToolExecutor] {
        var executors: [String: any IOSToolExecutor] = [
            "ask_user": IOSClosureToolExecutor { _, _, _ in
                .needsApproval("等待用户回答")
            }
        ]
        guard sharedSettings.snapshot.enableWebSearch else { return executors }
        for name in IOSSearchExecutor.supportedToolNames {
            executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                let call = self.toolCall(name: toolName, input: arguments)
                return .filled(await self.dispatchSearchToolCall(call))
            }
        }
        return executors
    }

    func backgroundToolExecutors(
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        toolExposureBridge: IosToolExposureBridge? = nil,
        conversationId: KotlinUuid? = nil
    ) -> [String: any IOSToolExecutor] {
        var executors: [String: any IOSToolExecutor] = [:]
        let availableToolNames = Set(params.tools.map(\.name))

        // P0-a: tool_search is a local discovery call, safe in background. The
        // background job owns its own bridge instance (rebuilt from the handoff
        // declarations with exposure reset — the foreground's expanded hits are
        // not transferred). IOSAgentToolEngine re-derives params from the same
        // bridge after every batch (Fix C), so hits expanded inside a
        // background round become callable on the next background round.
        if availableToolNames.contains("tool_search") {
            executors["tool_search"] = IOSClosureToolExecutor { _, arguments, _ in
                guard let bridge = toolExposureBridge else {
                    return .failed("tool_search is unavailable in this run.")
                }
                return .filled(bridge.executeToolSearch(argumentsJson: arguments))
            }
        }
        // M5: tools_list 与 tool_search 同属本地目录调用（discovery 引导引用它）——
        // 后台安全，注册为桥的本地执行（返回全目录 {name, description} 清单）。
        if availableToolNames.contains("tools_list") {
            executors["tools_list"] = IOSClosureToolExecutor { _, _, _ in
                guard let bridge = toolExposureBridge else {
                    return .failed("tools_list is unavailable in this run.")
                }
                return .filled(bridge.executeToolsList())
            }
        }

        for name in IOSSearchExecutor.supportedToolNames where availableToolNames.contains(name) {
            executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                guard self.shouldExecuteSearchInBackground(toolName: toolName, arguments: arguments) else {
                    return .denied("后台生成期间需要回到 App 确认网络搜索或网页读取。")
                }
                let result = await self.dispatchSearchToolCall(self.toolCall(name: toolName, input: arguments))
                // P2-a: 后台 run 标记到 run 锚定会话（conversationId 由 job 传入），
                // 不得标到前台当前会话。
                self.markMemoryPollutionIfNeeded(
                    toolName: toolName,
                    outputText: result,
                    conversationId: conversationId
                )
                return .filled(result)
            }
        }

        if localToolExecutor != nil {
            for name in IOSWorkspaceToolCatalog.supportedToolNames where availableToolNames.contains(name) {
                executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                    guard let self else { return .failed("Chat runtime is unavailable.") }
                    let toolCall = self.toolCall(name: toolName, input: arguments)
                    let output = await self.workspaceToolExecutionOutput(toolCall, isUserInitiated: false)
                    if case .needsUserAction(let reason) = output {
                        return .denied("后台生成期间需要回到 App 确认 Workspace 操作：\(reason)")
                    }
                    return .filled(ChatToolOutputFormatter.workspaceResultText(for: toolCall, output: output))
                }
            }

            for name in IOSIshToolCatalog.supportedToolNames.union(IOSEmbeddedIshToolCatalog.supportedToolNames)
            where availableToolNames.contains(name) {
                executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                    guard let self else { return .failed("Chat runtime is unavailable.") }
                    let toolCall = self.toolCall(name: toolName, input: arguments)
                    let output = await self.ishToolExecutionOutput(toolCall, isUserInitiated: false)
                    if case .needsUserAction(let reason) = output {
                        return .denied("后台生成期间需要回到 App 确认 iSH 操作：\(reason)")
                    }
                    return .filled(ChatToolOutputFormatter.ishHandoffResultText(for: toolCall, output: output))
                }
            }
        }

        if isWebMountRuntimeEnabled {
            for name in IOSWebMountToolCatalog.supportedToolNames.union(IOSWebMountToolCatalog.unsupportedToolNames)
            where availableToolNames.contains(name) {
                executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                    guard let self else { return .failed("Chat runtime is unavailable.") }
                    let toolCall = self.toolCall(name: toolName, input: arguments)
                    let output = await self.webMountToolExecutionOutput(toolCall, isUserInitiated: false)
                    if case .needsUserAction(let reason) = output {
                        return .denied("后台生成期间需要回到 App 确认 WebMount 操作：\(reason)")
                    }
                    return .filled(ChatToolOutputFormatter.webMountResultText(for: toolCall, output: output))
                }
            }
        }

        if availableToolNames.contains("memory_tool") {
            executors["memory_tool"] = IOSClosureToolExecutor { [weak self] _, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                let policy = self.memoryToolWritePolicy(input: arguments, isUserInitiated: false)
                if case .needsUserAction(let reason) = policy {
                    return .denied("后台生成期间需要回到 App 确认记忆写入：\(reason)")
                }
                return .filled(self.dispatchMemoryToolCall(self.toolCall(name: "memory_tool", input: arguments), writePolicy: policy))
            }
        }

        if availableToolNames.contains("generate_image") {
            executors["generate_image"] = IOSClosureToolExecutor { [weak self] _, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                return .filledParts(await self.dispatchImageToolCall(self.toolCall(name: "generate_image", input: arguments)))
            }
        }

        // Advanced tools in background. The foreground approval UI cannot surface
        // during a BGContinuedProcessingTask, so:
        //  - subagent_dispatch: disabled stays blocked; askEveryTime (including a
        //    normalized legacy allowOncePerRun value) must return to foreground
        //    for approval. Only autoApprove may execute silently in background.
        //  - mcp_call: high-risk (external/remote), mirrors the foreground gate —
        //    only runs when the high-risk auto-approve switch is on, otherwise
        //    denied so the user returns to the app to confirm.
        //  - model_council_run: DENIED in background. A council run is a long,
        //    multi-seat/multi-round streaming sequence (many sequential HTTP calls,
        //    potentially tens of minutes) that occupies a single executor step. The
        //    BGTask expirationHandler cannot interrupt an in-flight streamText, so a
        //    background council would overrun the BGTask window and be force-killed,
        //    leaving an incomplete run. It also drives the council-room @Observable
        //    UI, which has no subscriber in background. Revert to foreground.
        if availableToolNames.contains("subagent_dispatch") {
            executors["subagent_dispatch"] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                let toolCall = self.toolCall(name: toolName, input: arguments)
                guard self.isAdvancedToolEnabled(toolName) else {
                    return .failed("\(toolName) 未开启。请先在设置中启用对应能力。")
                }
                guard !self.requiresSubAgentApproval() else {
                    self.recordToolApproval(
                        capabilityId: "ios.agent.subagent_dispatch",
                        toolCall: toolCall,
                        action: .denied,
                        reason: "Background subagent dispatch requires foreground approval.",
                        runId: runId
                    )
                    return .denied("后台生成期间需要回到 App 确认子代理调度。")
                }
                let result = await self.dispatchAdvancedToolCall(
                    toolCall,
                    providerSetting: providerSetting,
                    params: params,
                    runId: runId,
                    conversationId: conversationId
                )
                self.recordAdvancedToolApprovalIfNeeded(toolCall: toolCall, runId: runId)
                return .filled(result)
            }
        }

        if availableToolNames.contains("model_council_run") {
            executors["model_council_run"] = IOSClosureToolExecutor { _, _, _ in
                .denied("模型委员会运行时间较长且依赖前台房间界面，请回到 App 内执行。")
            }
        }

        // ask_user is a foreground HITL node. Background cannot present the card or
        // Watch decision, so deny with an explicit return-to-app reason instead of
        // leaving the tool unregistered (engine would otherwise error-fill and continue).
        if availableToolNames.contains("ask_user") {
            executors["ask_user"] = IOSClosureToolExecutor { _, _, _ in
                .denied("后台生成期间需要回到 App 回答问题。")
            }
        }

        if availableToolNames.contains("mcp_call") {
            executors["mcp_call"] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                // High-risk gate mirrors the foreground path (executeAdvancedToolCall):
                // MCP may touch external services, so only auto-run when the high-risk
                // auto-approve switch is on. Otherwise deny so the user returns to confirm.
                guard IOSLocalToolExecutor.isHighRiskAutoApproveEnabled else {
                    return .denied("后台生成期间需要回到 App 确认 MCP 工具。")
                }
                guard self.isAdvancedToolEnabled(toolName) else {
                    return .failed("\(toolName) 未开启。请先在设置中启用对应能力。")
                }
                let result = await self.dispatchAdvancedToolCall(
                    self.toolCall(name: toolName, input: arguments),
                    providerSetting: providerSetting,
                    params: params,
                    runId: runId,
                    conversationId: conversationId
                )
                // P2-a: 后台 run 标记到 run 锚定会话。
                self.markMemoryPollutionIfNeeded(
                    toolName: toolName,
                    outputText: result,
                    conversationId: conversationId
                )
                return .filled(result)
            }
        }

        // P0-b: flattened `mcp__*` tools mirror the mcp_call background gate
        // (high-risk auto-approve required; same dispatch path). Only names
        // visible in the current round's params are registered.
        for tool in params.tools where ToolKt.isExpandedMcpToolName(name: tool.name) {
            let expandedName = tool.name
            executors[expandedName] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                guard IOSLocalToolExecutor.isHighRiskAutoApproveEnabled else {
                    return .denied("后台生成期间需要回到 App 确认 MCP 工具。")
                }
                guard self.isAdvancedToolEnabled(toolName) else {
                    return .failed("\(toolName) 未开启。请先在设置中启用对应能力。")
                }
                let result = await self.dispatchAdvancedToolCall(
                    self.toolCall(name: toolName, input: arguments),
                    providerSetting: providerSetting,
                    params: params,
                    runId: runId,
                    conversationId: conversationId
                )
                // P2-a: 后台 run 标记到 run 锚定会话。
                self.markMemoryPollutionIfNeeded(
                    toolName: toolName,
                    outputText: result,
                    conversationId: conversationId
                )
                return .filled(result)
            }
        }

        let skillMcpNames = IOSSkillToolCatalog.toolNames.union(IOSMcpManagementToolCatalog.toolNames)
        for name in skillMcpNames where availableToolNames.contains(name) {
            executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                let mutating = IOSSkillToolCatalog.mutatingToolNames.contains(toolName)
                    || IOSMcpManagementToolCatalog.mutatingToolNames.contains(toolName)
                let highRisk = IOSMcpManagementToolCatalog.highRiskToolNames.contains(toolName)
                let autoApproved = highRisk
                    ? IOSLocalToolExecutor.isHighRiskAutoApproveEnabled
                    : IOSLocalToolExecutor.isGlobalAutoApproveEnabled
                        || IOSLocalToolExecutor.isHighRiskAutoApproveEnabled
                if mutating, !autoApproved {
                    return .denied("后台生成期间需要回到 App 确认 \(toolName)。")
                }
                if IOSMcpManagementToolCatalog.toolNames.contains(toolName),
                   !self.isAdvancedToolEnabled(toolName) {
                    return .failed("\(toolName) 未开启。请先在设置中启用 MCP。")
                }
                let result = await self.dispatchAdvancedToolCall(
                    self.toolCall(name: toolName, input: arguments),
                    providerSetting: providerSetting,
                    params: params,
                    runId: runId,
                    conversationId: conversationId
                )
                return .filled(result)
            }
        }

        // P1-c/P1-d: 线程编排工具后台注册——子线程在后台引擎里同样可 spawn 孙线程 /
        // list / interrupt / 收发消息 / wait（深度与并发上限由服务内检查兜底）。
        // 与 mcp 先例同：只注册当前轮 params 可见的名字。conversationId = 本 job
        // 的 run 锚定会话（由后台协调器传入），生成中切会话不串到当前会话。
        for name in ["spawn_agent", "list_agents", "interrupt_agent", "send_message", "followup_task", "wait_agent"]
            where availableToolNames.contains(name) {
            executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                guard let service = self.orchestrationToolService else {
                    return .failed("线程编排工具当前不可用。")
                }
                let result = await service.execute(
                    toolName: toolName,
                    arguments: arguments,
                    providerSetting: providerSetting,
                    params: params,
                    runId: runId,
                    conversationId: conversationId,
                    // M3: 传 run 的桥——子 run 的 fullToolNames 取全目录而非当轮
                    // 可见子集（闭包捕获的是本 job 的桥实例，全 run 不变）。
                    toolExposureBridge: toolExposureBridge
                )
                return .filled(result)
            }
        }

        // 跨会话读取（session_search/session_read）：本地只读、无审批，前后台
        // 同注册（照 tool_search 先例）。只注册当前轮 params 可见的名字。
        for name in ["session_search", "session_read"] where availableToolNames.contains(name) {
            executors[name] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                return .filled(await self.dispatchSessionReadToolCall(
                    self.toolCall(name: toolName, input: arguments)
                ))
            }
        }

        // P3-a: exec 求值——后台 run 也能跑（每次求值独立队列/context，与
        // 前台生命周期无关）。只注册当前轮 params 可见的名字；开关关时声明侧
        // 零痕迹（params 不会含 exec），此处双重门控避免陈旧轮次执行。
        // P3-b: 后台 run 没有协调器级嵌套 runner（账本与审批卡都归前台
        // 协调器），所以 tools 对象按当轮可见集注入白名单、但每个嵌套调用都
        // 报 "tool not available in exec"——诚实拒绝而不是静默缺失。
        // P3-c: 后台 run 同样按会话注册 cell（conversationId 由 job 传入），
        // 前台 yield 的 cell 后台可以 wait，反之亦然——注册表跨 run/前后台共享。
        if availableToolNames.contains("exec") {
            executors["exec"] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                guard self.settingsStore.execJavaScriptEnabled else {
                    return .failed("exec 未开启。请先在设置中启用 JavaScript 执行工具。")
                }
                let whitelist = ChatToolRuntime.execNestedToolWhitelist(
                    visibleToolNames: availableToolNames
                )
                let nestedTools = IOSJsSandboxTools(
                    availableToolNames: whitelist.sorted(),
                    hostCall: { _, _ in nil },
                    // P3-d: 后台同样注入 ALL_TOOLS 发现元数据——描述来自同一轮
                    // params.tools 声明（与白名单同源）。嵌套调用仍诚实拒绝
                    // （hostCall nil → "tool not available in exec"），但脚本可以
                    // 用 ALL_TOOLS 发现工具名，避免在后续轮次猜名字。
                    toolDescriptions: Self.execToolDescriptions(
                        from: params.tools,
                        whitelist: whitelist
                    )
                )
                return .filled(await self.dispatchExecToolCall(
                    self.toolCall(name: toolName, input: arguments),
                    nestedTools: nestedTools,
                    conversationId: conversationId
                ))
            }
        }
        // P3-c: wait 与 exec 同开关同池——后台 run 可 wait 本会话的 cell。
        if availableToolNames.contains("wait") {
            executors["wait"] = IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else { return .failed("Chat runtime is unavailable.") }
                guard self.settingsStore.execJavaScriptEnabled else {
                    return .failed("wait 未开启。请先在设置中启用 JavaScript 执行工具。")
                }
                return .filled(await self.dispatchWaitToolCall(
                    self.toolCall(name: toolName, input: arguments),
                    conversationId: conversationId
                ))
            }
        }

        return executors
    }

    func nextPendingToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> ChatPendingToolCall? {
        // tool_search runs first: it changes which tools the NEXT round declares,
        // so its result should reach the model before any other pending call.
        if let toolCall = pendingToolSearchToolCall(in: messages, availableToolNames: availableToolNames) {
            return ChatPendingToolCall(kind: .toolSearch, toolCall: toolCall)
        }
        if let toolCall = pendingSearchToolCall(in: messages, availableToolNames: availableToolNames) {
            return ChatPendingToolCall(kind: .search, toolCall: toolCall)
        }
        if let toolCall = pendingWorkspaceToolCall(in: messages, availableToolNames: availableToolNames) {
            return ChatPendingToolCall(kind: .workspace, toolCall: toolCall)
        }
        if let toolCall = pendingIshToolCall(in: messages, availableToolNames: availableToolNames) {
            return ChatPendingToolCall(kind: .ish, toolCall: toolCall)
        }
        if let toolCall = pendingWebMountToolCall(in: messages, availableToolNames: availableToolNames) {
            return ChatPendingToolCall(kind: .webMount, toolCall: toolCall)
        }
        if let toolCall = pendingMemoryToolCall(in: messages, availableToolNames: availableToolNames) {
            return ChatPendingToolCall(kind: .memory, toolCall: toolCall)
        }
        if let toolCall = pendingImageToolCall(in: messages, availableToolNames: availableToolNames) {
            return ChatPendingToolCall(kind: .image, toolCall: toolCall)
        }
        if let toolCall = pendingAskUserToolCall(in: messages, availableToolNames: availableToolNames) {
            return ChatPendingToolCall(kind: .askUser, toolCall: toolCall)
        }
        if let toolCall = pendingSessionReadToolCall(in: messages, availableToolNames: availableToolNames) {
            return ChatPendingToolCall(kind: .sessionRead, toolCall: toolCall)
        }
        if let toolCall = pendingAdvancedToolCall(in: messages, availableToolNames: availableToolNames) {
            return ChatPendingToolCall(kind: .advanced, toolCall: toolCall)
        }
        return nil
    }

    func hasUnresolvedToolCall(in messages: [UIMessage]) -> Bool {
        unresolvedToolCall(in: messages) != nil
    }

    func execute(
        _ pendingToolCall: ChatPendingToolCall,
        context: ChatPendingToolApproval,
        toolExposureBridge: IosToolExposureBridge? = nil,
        nestedToolRunner: IosExecNestedToolRunner? = nil
    ) async -> ChatToolRuntimeResult {
        // I-2 fail-closed: gate every kind on the same check before it reaches its
        // own dispatch* function, all of which read `context.toolCall.input`
        // directly. A gateway that double-writes a call, truncates one mid
        // argument, or a model that emits bare non-JSON text must not run with
        // silently wrong (not absent) arguments — see `parseInputStrict()`. This
        // does not execute the tool; it resolves it in place with a structured
        // error and lets the existing resume path hand that back to the model.
        if let invalid = context.toolCall.parseInputStrict() as? ToolInputParse.Invalid {
            let resolvedMessages = messagesByFinishingToolCall(
                context.toolCall,
                outputText: ChatToolOutputFormatter.toolArgumentsInvalidJSON(
                    toolName: context.toolCall.toolName,
                    message: invalid.message,
                    rawPrefix: invalid.rawPrefix
                ),
                in: context.baseMessages
            )
            return .completed(resolvedMessages)
        }
        switch pendingToolCall.kind {
        case .toolSearch:
            return executeToolSearchToolCall(context, toolExposureBridge: toolExposureBridge)
        case .search:
            return await executeSearchToolCall(context)
        case .workspace:
            return await executeWorkspaceToolCall(context)
        case .ish:
            return await executeIshToolCall(context)
        case .webMount:
            return await executeWebMountToolCall(context)
        case .memory:
            return executeMemoryToolCall(context)
        case .image:
            return await executeImageToolCall(context)
        case .askUser:
            return executeAskUserToolCall(context)
        case .sessionRead:
            return await executeSessionReadToolCall(context)
        case .advanced:
            return await executeAdvancedToolCall(
                context,
                nestedTools: Self.execNestedToolsBridge(
                    toolExposureBridge: toolExposureBridge,
                    nestedToolRunner: nestedToolRunner
                ),
                toolExposureBridge: toolExposureBridge
            )
        }
    }

    /// P3-b: names that are NEVER injectable as nested `tools` functions —
    /// `exec` itself (self-call guard), the thread-orchestration tools
    /// (aligned with codex "collaboration tools cannot be called from exec"),
    /// the discovery tool, and `ask_user` (its HITL card cannot be re-entered
    /// from inside an evaluation). Single source for the engine's function
    /// injection AND the coordinator's nested-runner classification.
    static let execNestedToolExclusions: Set<String> = [
        "exec", "spawn_agent", "list_agents", "interrupt_agent",
        "send_message", "followup_task", "wait_agent", "tool_search", "ask_user",
    ]

    /// P3-b: whitelist for one evaluation's `tools` object = the current
    /// round's visible tool set minus the exec exclusions. Same source for
    /// the engine's function injection and the coordinator's nested-runner
    /// classification, so the two can never disagree.
    static func execNestedToolWhitelist(visibleToolNames: Set<String>) -> Set<String> {
        visibleToolNames.subtracting(execNestedToolExclusions)
    }

    /// P3-d: ALL_TOOLS discovery descriptions for the whitelisted names, taken
    /// from the same round's `[Tool]` declarations (the same objects that made
    /// the whitelist visible). Names outside the whitelist are ignored; a
    /// declared-but-descriptionless tool falls back to an empty string and the
    /// engine still installs its name-only ALL_TOOLS entry.
    static func execToolDescriptions(from tools: [Tool], whitelist: Set<String>) -> [String: String] {
        var descriptions: [String: String] = [:]
        for tool in tools where whitelist.contains(tool.name) {
            descriptions[tool.name] = tool.description_
        }
        return descriptions
    }

    /// P3-b: builds the sandbox bridge from the run's exposure bridge and the
    /// coordinator-provided nested runner. Nil when there is no runner or no
    /// bridge (the evaluation then runs without a `tools` object, exactly like
    /// P3-a).
    private static func execNestedToolsBridge(
        toolExposureBridge: IosToolExposureBridge?,
        nestedToolRunner: IosExecNestedToolRunner?
    ) -> IOSJsSandboxTools? {
        guard let toolExposureBridge, let nestedToolRunner else { return nil }
        let visible = toolExposureBridge.visibleTools()
        let whitelist = execNestedToolWhitelist(
            visibleToolNames: Set(visible.map(\.name))
        )
        guard !whitelist.isEmpty else { return nil }
        return IOSJsSandboxTools(
            availableToolNames: whitelist.sorted(),
            hostCall: { name, arguments in
                await nestedToolRunner(name, arguments)
            },
            // P3-d: ALL_TOOLS 与白名单同一来源（同轮可见工具集），描述直接来自
            // 可见声明的 KMP description。
            toolDescriptions: execToolDescriptions(from: visible, whitelist: whitelist)
        )
    }

    func userInitiatedImageToolCall(input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "image-\(UUID().uuidString)-\(chatInputDigest(for: input))",
            toolName: "generate_image",
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    func messagesByExecutingImageToolCall(
        _ toolCall: UIMessagePart.Tool,
        in messages: [UIMessage]
    ) async -> [UIMessage] {
        let resultParts = await dispatchImageToolCall(toolCall)
        return messagesByFinishingToolCall(
            toolCall,
            outputParts: resultParts,
            in: messages
        )
    }

    func finishMemoryApproval(
        pending: ChatPendingToolApproval,
        writePolicy: IOSMemoryToolWritePolicy,
        expectedUpdatedAt: Int64? = nil
    ) -> [UIMessage] {
        let allowed: Bool
        if case .allow = writePolicy {
            allowed = true
        } else {
            allowed = false
        }
        recordToolApproval(
            capabilityId: "ios.agent.memory_write",
            toolCall: pending.toolCall,
            action: allowed ? .allowed : .denied,
            reason: allowed ? "User approved memory write." : "User denied memory write.",
            runId: pending.runId
        )

        let resultText = IOSMemoryToolExecutor.execute(
            input: pending.toolCall.input,
            runtime: sharedSettings.agentRuntime,
            writePolicy: writePolicy,
            expectedUpdatedAt: expectedUpdatedAt
        )
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func finishSearchApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        recordToolApproval(
            capabilityId: "ios.network.search_tools",
            toolCall: pending.toolCall,
            action: allow ? .allowed : .denied,
            reason: allow ? "User approved network search." : "User denied network search.",
            runId: pending.runId
        )
        let resultText = allow
            ? await dispatchSearchToolCall(pending.toolCall)
            : ChatToolOutputFormatter.toolFailureJSON(
                toolName: pending.toolCall.toolName,
                reason: "User denied network search.",
                denied: true
            )
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages,
            conversationId: allow ? pending.conversationId : nil
        )
    }

    func finishWebMountApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        recordToolApproval(
            capabilityId: "ios.webmount.browser",
            toolCall: pending.toolCall,
            action: allow ? .allowed : .denied,
            reason: allow ? "User approved WebMount foreground action." : "User denied WebMount foreground action.",
            runId: pending.runId
        )

        let resultText: String
        if allow {
            let output = await webMountToolExecutionOutput(pending.toolCall, isUserInitiated: true)
            resultText = ChatToolOutputFormatter.webMountResultText(for: pending.toolCall, output: output)
        } else {
            resultText = IOSWebMountController.json([
                "ok": false,
                "tool": pending.toolCall.toolName,
                "denied": true,
                "policy": "user_denied",
                "reason": "User denied WebMount foreground action."
            ])
        }
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func finishWorkspaceApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        let resultText: String
        if allow {
            let output = await workspaceToolExecutionOutput(pending.toolCall, isUserInitiated: true)
            resultText = ChatToolOutputFormatter.workspaceResultText(for: pending.toolCall, output: output)
        } else {
            resultText = IOSWorkspaceStore.json([
                "ok": false,
                "tool": pending.toolCall.toolName,
                "denied": true,
                "policy": "user_denied",
                "reason": "User denied Workspace tool access."
            ])
        }
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func finishIshHandoffApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        let isEmbeddedIshExecution = IOSEmbeddedIshToolCatalog.supportedToolNames.contains(pending.toolCall.toolName)
        recordToolApproval(
            capabilityId: isEmbeddedIshExecution ? "ios.embedded.ish_runtime" : "ios.external.ish_handoff",
            toolCall: pending.toolCall,
            action: allow ? .allowed : .denied,
            reason: allow ? "User approved iSH tool." : "User denied iSH tool.",
            runId: pending.runId
        )

        let resultText: String
        if allow {
            let output = await ishToolExecutionOutput(pending.toolCall, isUserInitiated: true)
            resultText = ChatToolOutputFormatter.ishHandoffResultText(for: pending.toolCall, output: output)
        } else {
            resultText = IOSWorkspaceStore.json([
                "ok": false,
                "tool": pending.toolCall.toolName,
                "denied": true,
                "policy": "user_denied",
                "reason": "User denied iSH tool.",
                "stdout_available": isEmbeddedIshExecution,
                "stderr_available": isEmbeddedIshExecution,
                "exit_code_available": false
            ])
        }
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func finishMcpApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        let audit: (capabilityId: String, actionName: String)
        if pending.toolCall.toolName == "mcp_call"
            || ToolKt.isExpandedMcpToolName(name: pending.toolCall.toolName) {
            audit = ("ios.mcp.tool_call", "MCP tool call")
        } else if IOSMcpManagementToolCatalog.toolNames.contains(pending.toolCall.toolName) {
            audit = ("ios.mcp.management", "MCP management operation")
        } else {
            audit = ("ios.skills.management", "local Skill operation")
        }
        recordToolApproval(
            capabilityId: audit.capabilityId,
            toolCall: pending.toolCall,
            action: allow ? .allowed : .denied,
            reason: allow ? "User approved \(audit.actionName)." : "User denied \(audit.actionName).",
            runId: pending.runId
        )

        let resultText: String
        if allow {
            resultText = await dispatchAdvancedToolCall(
                pending.toolCall,
                providerSetting: pending.providerSetting,
                params: pending.params,
                runId: pending.runId,
                conversationId: pending.conversationId
            )
        } else {
            resultText = "用户拒绝执行 \(pending.toolCall.toolName)。"
        }
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages,
            // 拒绝分支输出是纯文本（无 ok:false 可判定），显式只在 allow 时置位。
            conversationId: allow ? pending.conversationId : nil
        )
    }

    func finishCouncilApproval(
        pending: ChatPendingToolApproval,
        allow: Bool
    ) async -> [UIMessage] {
        let isSubAgent = pending.toolCall.toolName == "subagent_dispatch"
        let capabilityId = isSubAgent
            ? "ios.agent.subagent_dispatch"
            : "ios.agent.model_council_run"
        recordToolApproval(
            capabilityId: capabilityId,
            toolCall: pending.toolCall,
            action: allow ? .allowed : .denied,
            reason: allow
                ? "User approved \(pending.toolCall.toolName)."
                : "User denied \(pending.toolCall.toolName).",
            runId: pending.runId
        )
        let resultText: String
        if allow {
            resultText = await dispatchAdvancedToolCall(
                pending.toolCall,
                providerSetting: pending.providerSetting,
                params: pending.params,
                runId: pending.runId,
                conversationId: pending.conversationId
            )
        } else {
            resultText = IOSWorkspaceStore.json([
                "ok": false,
                "tool": pending.toolCall.toolName,
                "status": "denied",
                "denied": true,
                "policy": "user_denied",
                "reason": isSubAgent ? "用户拒绝调度子代理。" : "用户拒绝启动模型议会。"
            ])
        }
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        )
    }

    func finishAskUserAnswer(
        pending: ChatPendingToolApproval,
        answer: String
    ) -> [UIMessage] {
        let trimmed = answer.trimmingCharacters(in: .whitespacesAndNewlines)
        let payload: [String: Any]
        if trimmed.isEmpty {
            payload = [
                "denied": true,
                "reason": "User skipped ask_user."
            ]
        } else {
            payload = ["answer": trimmed]
        }
        let outputText: String
        if let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            outputText = text
        } else if trimmed.isEmpty {
            outputText = #"{"denied":true,"reason":"User skipped ask_user."}"#
        } else {
            outputText = #"{"answer":"\#(trimmed)"}"#
        }
        return messagesByFinishingToolCall(
            pending.toolCall,
            outputText: outputText,
            in: pending.baseMessages
        )
    }

    func messagesByFinishingToolCall(
        _ targetToolCall: UIMessagePart.Tool,
        outputText: String,
        in messages: [UIMessage],
        conversationId: KotlinUuid? = nil
    ) -> [UIMessage] {
        // P2-a: 工具输出唯一收口——成功的外部上下文输出进会话时由 harness 置
        // POLLUTED（只升不降；失败输出不置位；置位失败只记日志，不阻塞工具结果）。
        // conversationId 只由 run 锚定会话传入（前台 pending / 后台 job），
        // 恢复/失败路径（nil）与 UI 当前会话天然隔离。
        markMemoryPollutionIfNeeded(
            toolName: targetToolCall.toolName,
            outputText: outputText,
            conversationId: conversationId
        )
        let outputPart = UIMessagePart.Text(text: outputText, metadata: nil)
        return messagesByFinishingToolCall(targetToolCall, outputParts: [outputPart], in: messages)
    }

    /// P2-a: 判定并触发记忆污染置位。只标「外部上下文」工具（web 搜索/网页读取/
    /// MCP 直调与 mcp__* 展开）且输出为成功（failureReason 与 toolFailureJSON 的
    /// ok:false/status 契约一致）；wm_* 待 URL 分类后纳入（P2.5）。幂等只升不降由
    /// 存储层保证，这里只负责「成功输出进会话」这一 harness 时机。
    private func markMemoryPollutionIfNeeded(
        toolName: String,
        outputText: String,
        conversationId: KotlinUuid?
    ) {
        guard let conversationId,
              let marker = memoryPollutionMarker,
              ConversationMemoryPollutionPolicy.isPollutingToolName(toolName),
              ChatToolOutputFormatter.failureReason(from: [UIMessagePart.Text(text: outputText, metadata: nil)]) == nil
        else { return }
        marker(conversationId, toolName)
    }

    func messagesByFailingPendingToolCalls(
        in messages: [UIMessage],
        outputText: String
    ) -> [UIMessage] {
        var resolvedMessages = messages
        var resolvedKeys = Set<String>()

        while let toolCall = unresolvedToolCall(in: resolvedMessages) {
            let key = chatToolCallKey(toolCall)
            guard resolvedKeys.insert(key).inserted else { break }
            resolvedMessages = messagesByFinishingToolCall(
                toolCall,
                outputText: outputText,
                in: resolvedMessages
            )
        }

        return resolvedMessages
    }

    func messagesByFailingPendingToolCalls(
        in messages: [UIMessage],
        failureReason: String,
        denied: Bool = false
    ) -> [UIMessage] {
        var resolvedMessages = messages
        var resolvedKeys = Set<String>()

        while let toolCall = unresolvedToolCall(in: resolvedMessages) {
            let key = chatToolCallKey(toolCall)
            guard resolvedKeys.insert(key).inserted else { break }
            resolvedMessages = messagesByFinishingToolCall(
                toolCall,
                outputText: ChatToolOutputFormatter.toolFailureJSON(
                    toolName: toolCall.toolName,
                    reason: failureReason,
                    denied: denied
                ),
                in: resolvedMessages
            )
        }

        return resolvedMessages
    }

    /// P0-a Fix B: soft-fail a tool call whose name EXISTS in the run's full
    /// catalog but is NOT in the current round's visible set — the model
    /// called a real tool that was never exposed (deferred behind tool_search).
    /// Fills that part in place with a structured failure output telling the
    /// model to call `tool_search` first, so the run CONTINUES instead of
    /// hard-failing. Returns nil when nothing needs guidance: a visible tool is
    /// handled by the normal execution path, and a truly unknown name stays on
    /// the existing unresolved-tool hard-fail path (never mask a real bug).
    func messagesByGuidingUnexposedToolCalls(
        in messages: [UIMessage],
        fullCatalogNames: Set<String>,
        visibleToolNames: Set<String>
    ) -> (messages: [UIMessage], toolCallId: String)? {
        guard let toolCall = unresolvedToolCall(in: messages) else { return nil }
        guard fullCatalogNames.contains(toolCall.toolName),
              !visibleToolNames.contains(toolCall.toolName) else { return nil }
        let outputText = ChatToolOutputFormatter.toolFailureJSON(
            toolName: toolCall.toolName,
            reason: "该工具本轮未暴露，请先调用 tool_search 获取，下一步再执行",
            status: "failed"
        )
        return (
            messagesByFinishingToolCall(toolCall, outputText: outputText, in: messages),
            toolCall.toolCallId
        )
    }

    private func unresolvedToolCall(in messages: [UIMessage]) -> UIMessagePart.Tool? {
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { $0.output.isEmpty }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingToolSearchToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> UIMessagePart.Tool? {
        // M5: tools_list 与 tool_search 同属本地目录调用（发现引导同一路径）。
        let localDiscoveryNames: Set<String> = ["tool_search", "tools_list"]
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: {
                    localDiscoveryNames.contains($0.toolName)
                        && availableToolNames.contains($0.toolName)
                        && $0.output.isEmpty
                }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingSearchToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> UIMessagePart.Tool? {
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: {
                    IOSSearchExecutor.supportedToolNames.contains($0.toolName)
                        && availableToolNames.contains($0.toolName)
                        && $0.output.isEmpty
                }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingWorkspaceToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> UIMessagePart.Tool? {
        guard localToolExecutor != nil else { return nil }
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: {
                    IOSWorkspaceToolCatalog.supportedToolNames.contains($0.toolName)
                        && availableToolNames.contains($0.toolName)
                        && $0.output.isEmpty
                }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingIshToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> UIMessagePart.Tool? {
        guard localToolExecutor != nil else { return nil }
        let ishNames = IOSIshToolCatalog.supportedToolNames
            .union(IOSEmbeddedIshToolCatalog.supportedToolNames)
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: {
                    ishNames.contains($0.toolName)
                        && availableToolNames.contains($0.toolName)
                        && $0.output.isEmpty
                }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingWebMountToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> UIMessagePart.Tool? {
        guard isWebMountRuntimeEnabled else { return nil }
        let webMountNames = IOSWebMountToolCatalog.supportedToolNames
            .union(IOSWebMountToolCatalog.unsupportedToolNames)
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: {
                    webMountNames.contains($0.toolName)
                        && availableToolNames.contains($0.toolName)
                        && $0.output.isEmpty
                }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingMemoryToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> UIMessagePart.Tool? {
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: {
                    $0.toolName == "memory_tool"
                        && availableToolNames.contains($0.toolName)
                        && $0.output.isEmpty
                }) {
                return toolCall
            }
        }
        return nil
    }

    private func pendingImageToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> UIMessagePart.Tool? {
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: {
                    $0.toolName == "generate_image"
                        && availableToolNames.contains($0.toolName)
                        && $0.output.isEmpty
                }) {
                return toolCall
            }
        }
        return nil
    }

    /// Resolve the designated image-generation model (Settings.imageGenerationModelId) to
    /// its modelId + provider apiKey/baseURL. Returns nil when no usable image model is set,
    /// which gates the generate_image tool off (image generation disabled).
    private func resolvedImageGenerationConfig() -> (modelId: String, apiKey: String, baseURL: String)? {
        let snap = sharedSettings.snapshot
        guard let model = snap.findModelById(uuid: snap.imageGenerationModelId),
              let provider = ChatProviderConfiguration.provider(for: model, providers: snap.providers) else {
            return nil
        }
        let apiKey = ChatProviderConfiguration.apiKey(of: provider).trimmingCharacters(in: .whitespacesAndNewlines)
        let baseURL = ChatProviderConfiguration.baseURL(of: provider).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !apiKey.isEmpty, !baseURL.isEmpty else { return nil }
        return (model.modelId, apiKey, baseURL)
    }

    /// Resolve a codex (ChatGPT OAuth) image target. The actual request uses
    /// Android's fixed Codex image routing model, so only the provider id is
    /// needed here for OAuth token lookup.
    private func codexImageConfig() -> ChatCodexImageConfig {
        let snap = sharedSettings.snapshot
        guard let model = snap.findModelById(uuid: snap.imageGenerationModelId),
              let provider = ChatProviderConfiguration.provider(for: model, providers: snap.providers) as? ProviderSetting.OpenAI,
              provider.authMode == OpenAIAuthMode.codexOauth else {
            return .notSelected
        }
        let providerId = provider.id.description()
        guard IOSCodexAuthStore.load(providerId: providerId) != nil else { return .notSignedIn }
        return .signedIn(providerId: providerId)
    }

    private func pendingAskUserToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> UIMessagePart.Tool? {
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: {
                    $0.toolName == "ask_user"
                        && availableToolNames.contains($0.toolName)
                        && $0.output.isEmpty
                }) {
                return toolCall
            }
        }
        return nil
    }

    private func executeAskUserToolCall(_ pending: ChatPendingToolApproval) -> ChatToolRuntimeResult {
        if let request = ChatToolApprovalRequestBuilder.askUser(for: pending.toolCall) {
            return .waitingForApproval(.askUser(request))
        }
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: #"{"error":"ask_user requires a non-empty question."}"#,
            in: pending.baseMessages
        ))
    }

    /// 跨会话读取工具（session_search/session_read）的待执行检测。只识别
    /// 可见且 output 为空的调用（照 pendingAskUserToolCall 模式）。
    private func pendingSessionReadToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> UIMessagePart.Tool? {
        let sessionToolNames: Set<String> = ["session_search", "session_read"]
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: {
                    sessionToolNames.contains($0.toolName)
                        && availableToolNames.contains($0.toolName)
                        && $0.output.isEmpty
                }) {
                return toolCall
            }
        }
        return nil
    }

    /// session_search / session_read 执行入口：本地只读，无审批、无网络。
    private func executeSessionReadToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        let resultText = await dispatchSessionReadToolCall(pending.toolCall)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    // MARK: - 跨会话读取（session_search / session_read）执行体

    // 契约常量与 KMP 声明默认值/上限同源（session_search limit [1,20] 默认 8；
    // session_read max_messages [1,50] 默认 20）。
    static let sessionSearchDefaultLimit = 8
    static let sessionSearchMaxLimit = 20
    static let sessionReadDefaultMaxMessages = 20
    static let sessionReadMaxMessages = 50
    /// 单条消息投影文本截断上限（字符）。
    static let sessionReadMessageTextLimit = 2_000
    /// 总输出截断上限（消息投影合计，JSON 编码前）。
    static let sessionReadTotalOutputLimit = 12_000

    /// 执行体：无 store 注入时结构化「不可用」；会话不存在给
    /// `{status:error, reason:"conversation not found"}`；非法 conversation_id
    /// 走 Foundation 预校验（M4 先例：K/N `Uuid.parse` 对非法串终止进程，
    /// 必须先用 `UUID(uuidString:)` 拦截）。
    private func dispatchSessionReadToolCall(_ toolCall: UIMessagePart.Tool) async -> String {
        guard let store = conversationStoreProvider?() else {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "会话读取工具当前不可用。",
                status: "failed"
            )
        }
        switch toolCall.toolName {
        case "session_search":
            return await Self.executeSessionSearch(toolCall: toolCall, store: store)
        case "session_read":
            return await Self.executeSessionRead(toolCall: toolCall, store: store)
        default:
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "未知的会话工具。",
                status: "failed"
            )
        }
    }

    private static func executeSessionSearch(
        toolCall: UIMessagePart.Tool,
        store: IOSConversationStore
    ) async -> String {
        guard let args = ChatToolCallParsing.jsonObject(toolCall.input),
              let query = args["query"] as? String,
              !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "session_search 参数无效：需要非空 query。",
                status: "failed"
            )
        }
        let requestedLimit = (args["limit"] as? Int) ?? sessionSearchDefaultLimit
        let limit = min(max(requestedLimit, 1), sessionSearchMaxLimit)
        let hits = await store.sessionSearchHits(query: query, limit: limit)
        var payload: [String: Any] = [
            "ok": true,
            "tool": toolCall.toolName,
            "status": "ok",
            "query": query,
            "results": hits.map { hit -> [String: Any] in
                [
                    "conversation_id": hit.conversationId.toHexDashString(),
                    "title": hit.title,
                    "snippet": hit.snippet,
                    "updated_at": hit.updatedAt,
                    "message_count": hit.messageCount,
                ]
            },
        ]
        if hits.isEmpty {
            payload["hint"] = "没有找到匹配的会话，试试换一个关键词。"
        }
        return IOSWorkspaceStore.json(payload)
    }

    private static func executeSessionRead(
        toolCall: UIMessagePart.Tool,
        store: IOSConversationStore
    ) async -> String {
        guard let args = ChatToolCallParsing.jsonObject(toolCall.input),
              let rawId = args["conversation_id"] as? String else {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "session_read 参数无效：需要 conversation_id。",
                status: "failed"
            )
        }
        // M4 先例：K/N 的 KotlinUuid.parse 对非法串在导出下终止进程（NSException）——
        // 先用 Foundation UUID(uuidString:) 正则级预校验拦截（并归一化小写）。
        let normalizedId = rawId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard UUID(uuidString: normalizedId) != nil else {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "conversation_id 不是合法的会话 id：\(rawId)。请用 session_search 获取 conversation_id。",
                status: "failed"
            )
        }
        let conversationId = KotlinUuid.companion.parse(uuidString: normalizedId)
        let requestedMax = (args["max_messages"] as? Int) ?? sessionReadDefaultMaxMessages
        let maxMessages = min(max(requestedMax, 1), sessionReadMaxMessages)

        // 当前会话优先走内存（P1-c loadConversationForOrchestration 同契约）。
        let conversation: Conversation?
        if store.currentConversation?.id == conversationId {
            conversation = store.currentConversation
        } else {
            conversation = try? await store.loadConversationForOrchestration(conversationId)
        }
        guard let conversation else {
            return IOSWorkspaceStore.json([
                "status": "error",
                "reason": "conversation not found",
            ])
        }
        let messages = Self.searchableMessages(of: conversation)
        return IOSWorkspaceStore.json([
            "ok": true,
            "tool": toolCall.toolName,
            "status": "ok",
            "conversation_id": conversation.id.toHexDashString(),
            "title": conversation.title,
            "message_count": messages.count,
            "messages": Self.projectLatestMessages(messages, maxMessages: maxMessages),
        ])
    }

    /// 与 IOSConversationStore.searchableMessages 同源：分支节点优先，兜底 currentMessages。
    private static func searchableMessages(of conversation: Conversation) -> [UIMessage] {
        let nodeMessages = conversation.messageNodes.flatMap { node in
            node.messages
        }
        if !nodeMessages.isEmpty { return nodeMessages }
        return conversation.currentMessages
    }

    /// 最新 N 条消息的投影；总预算 [sessionReadTotalOutputLimit] 耗尽即停（截断
    /// 发生在 JSON 编码前，逐条前缀截断不截半条以上内容）。
    private static func projectLatestMessages(
        _ messages: [UIMessage],
        maxMessages: Int
    ) -> [[String: Any]] {
        let latest = messages.suffix(max(maxMessages, 1))
        var remainingBudget = sessionReadTotalOutputLimit
        var rows: [[String: Any]] = []
        for message in latest {
            let projected = String(projectMessage(message).prefix(remainingBudget))
            guard !projected.isEmpty else { break }
            rows.append([
                "role": roleName(message.role),
                "text": projected,
            ])
            remainingBudget -= projected.count
        }
        return rows
    }

    /// 单条消息投影：Text parts 拼接、Tool parts 摘要为 `[tool: 名称 状态]` 一行、
    /// 每条文本截断 [sessionReadMessageTextLimit] 字符。
    private static func projectMessage(_ message: UIMessage) -> String {
        let text = message.parts.map { part -> String in
            if let textPart = part as? UIMessagePart.Text {
                return textPart.text
            }
            if let tool = part as? UIMessagePart.Tool {
                return toolPartSummary(tool)
            }
            return ""
        }.joined(separator: "\n")
        return String(text.prefix(sessionReadMessageTextLimit))
    }

    private static func toolPartSummary(_ tool: UIMessagePart.Tool) -> String {
        let status: String
        if tool.isExecuted {
            status = "completed"
        } else if tool.isPending {
            status = "pending"
        } else {
            status = "waiting"
        }
        return "[tool: \(tool.toolName) \(status)]"
    }

    private static func roleName(_ role: MessageRole) -> String {
        if role == MessageRole.user { return "user" }
        if role == MessageRole.assistant { return "assistant" }
        if role == MessageRole.system { return "system" }
        if role == MessageRole.tool { return "tool" }
        return String(describing: role).lowercased()
    }

    private func pendingAdvancedToolCall(
        in messages: [UIMessage],
        availableToolNames: Set<String>
    ) -> UIMessagePart.Tool? {
        var advancedNames: Set<String> = Set([
            "mcp_call", "subagent_dispatch", "model_council_run",
            // P1-c/P1-d: 线程编排工具（非常驻，tool_search 命中后与 mcp__* 同样可执行）。
            "spawn_agent", "list_agents", "interrupt_agent",
            "send_message", "followup_task", "wait_agent",
        ])
        .union(IOSSkillToolCatalog.toolNames)
        .union(IOSMcpManagementToolCatalog.toolNames)
        // P3-a: exec 仅开关开时存在执行路径；关时零痕迹——模型调用 exec 走
        // 未知名硬失败语义（与声明侧 gate 同源：settingsStore.execJavaScriptEnabled）。
        // P3-c: wait 与 exec 同开关（cell 生命周期续取，无独立设置项）。
        if settingsStore.execJavaScriptEnabled {
            advancedNames.insert("exec")
            advancedNames.insert("wait")
        }
        for message in messages.reversed() where message.role == MessageRole.assistant {
            if let toolCall = message.parts.compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: {
                    (advancedNames.contains($0.toolName)
                        || ToolKt.isExpandedMcpToolName(name: $0.toolName))
                        && availableToolNames.contains($0.toolName)
                        && $0.output.isEmpty
                }) {
                return toolCall
            }
        }
        return nil
    }

    /// P0-a: `tool_search`/`tools_list` are pure local discovery calls — no
    /// approval, no network. `tool_search` runs through the KMP bridge
    /// (parses query/category/limit, searches the full declaration catalog,
    /// feeds `expanded_tools` back into the run exposure state so hits become
    /// callable on the NEXT round); `tools_list` returns the full catalog
    /// {name, description} list from the same bridge (M5).
    private func executeToolSearchToolCall(
        _ pending: ChatPendingToolApproval,
        toolExposureBridge: IosToolExposureBridge?
    ) -> ChatToolRuntimeResult {
        guard let bridge = toolExposureBridge else {
            return .completed(messagesByFinishingToolCall(
                pending.toolCall,
                outputText: ChatToolOutputFormatter.toolFailureJSON(
                    toolName: pending.toolCall.toolName,
                    reason: "tool_search 当前不可用。"
                ),
                in: pending.baseMessages
            ))
        }
        let resultText: String
        if pending.toolCall.toolName == "tools_list" {
            resultText = bridge.executeToolsList()
        } else {
            resultText = bridge.executeToolSearch(argumentsJson: pending.toolCall.input)
        }
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func executeSearchToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        // Honor the global / high-risk auto-approve switches (Permissions page). When on,
        // skip the per-call approval card and dispatch directly.
        let autoApprove = IOSLocalToolExecutor.isGlobalAutoApproveEnabled
            || IOSLocalToolExecutor.isHighRiskAutoApproveEnabled
        if !autoApprove,
           let request = ChatToolApprovalRequestBuilder.search(
               for: pending.toolCall,
               reason: "网络搜索和网页读取会访问外部站点，需要你确认。",
               settings: sharedSettings.snapshot
           ) {
            return .waitingForApproval(.search(request))
        }

        let resultText = await dispatchSearchToolCall(pending.toolCall)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages,
            conversationId: pending.conversationId
        ))
    }

    private func executeWorkspaceToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        let output = await workspaceToolExecutionOutput(pending.toolCall, isUserInitiated: false)
        if case .needsUserAction(let reason) = output,
           let request = ChatToolApprovalRequestBuilder.workspace(
               for: pending.toolCall,
               reason: reason,
               localToolExecutor: localToolExecutor
           ) {
            return .waitingForApproval(.workspace(request))
        }

        let resultText = ChatToolOutputFormatter.workspaceResultText(for: pending.toolCall, output: output)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func executeIshToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        let output = await ishToolExecutionOutput(pending.toolCall, isUserInitiated: false)
        if case .needsUserAction(let reason) = output,
           let request = ChatToolApprovalRequestBuilder.ishHandoff(for: pending.toolCall, reason: reason) {
            return .waitingForApproval(.ish(request))
        }

        let resultText = ChatToolOutputFormatter.ishHandoffResultText(for: pending.toolCall, output: output)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func executeWebMountToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        let output = await webMountToolExecutionOutput(pending.toolCall, isUserInitiated: false)
        if case .needsUserAction(let reason) = output,
           let request = ChatToolApprovalRequestBuilder.webMount(
               for: pending.toolCall,
               reason: reason,
               localToolExecutor: localToolExecutor
           ) {
            return .waitingForApproval(.webMount(request))
        }

        let resultText = ChatToolOutputFormatter.webMountResultText(for: pending.toolCall, output: output)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func executeMemoryToolCall(_ pending: ChatPendingToolApproval) -> ChatToolRuntimeResult {
        let writePolicy = memoryToolWritePolicy(input: pending.toolCall.input, isUserInitiated: false)
        if case .needsUserAction(let reason) = writePolicy,
           let request = ChatToolApprovalRequestBuilder.memory(for: pending.toolCall, reason: reason) {
            return .waitingForApproval(.memory(request))
        }

        let resultText = dispatchMemoryToolCall(pending.toolCall, writePolicy: writePolicy)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages
        ))
    }

    private func executeImageToolCall(_ pending: ChatPendingToolApproval) async -> ChatToolRuntimeResult {
        let resultParts = await dispatchImageToolCall(pending.toolCall)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputParts: resultParts,
            in: pending.baseMessages
        ))
    }

    private func executeAdvancedToolCall(
        _ pending: ChatPendingToolApproval,
        nestedTools: IOSJsSandboxTools? = nil,
        toolExposureBridge: IosToolExposureBridge? = nil
    ) async -> ChatToolRuntimeResult {
        // MCP calls and MCP management can access remote services or import
        // connection configuration. Ordinary global auto-approve must not cross
        // this high-risk boundary.
        let toolName = pending.toolCall.toolName
        let highRiskMcp = toolName == "mcp_call"
            || ToolKt.isExpandedMcpToolName(name: toolName)
            || IOSMcpManagementToolCatalog.highRiskToolNames.contains(toolName)
        if highRiskMcp, !IOSLocalToolExecutor.isHighRiskAutoApproveEnabled {
            let request: McpToolApprovalRequest?
            if toolName == "mcp_call" {
                request = ChatToolApprovalRequestBuilder.mcp(
                    for: pending.toolCall,
                    reason: "MCP 工具可能访问外部服务或执行远端操作，需要你确认。"
                )
            } else if ToolKt.isExpandedMcpToolName(name: toolName),
                      let target = resolvedMcpTarget(forExpandedName: toolName) {
                // P0-b: flattened calls carry the tool's own arguments; the
                // approval card resolves server/tool from the directory —
                // same gate and resume path as mcp_call.
                request = ChatToolApprovalRequestBuilder.expandedMcp(
                    for: pending.toolCall,
                    server: target.server,
                    tool: target.tool,
                    reason: "MCP 工具可能访问外部服务或执行远端操作，需要你确认。"
                )
            } else {
                request = ChatToolApprovalRequestBuilder.extensionMutation(
                    for: pending.toolCall,
                    reason: "MCP 管理操作可能访问外部服务或写入连接配置，需要你确认。"
                )
            }
            if let request {
                return .waitingForApproval(.mcp(request))
            }
        }

        let mutatingSkill = IOSSkillToolCatalog.mutatingToolNames.contains(pending.toolCall.toolName)
        if mutatingSkill,
           !IOSLocalToolExecutor.isGlobalAutoApproveEnabled,
           !IOSLocalToolExecutor.isHighRiskAutoApproveEnabled,
           let request = ChatToolApprovalRequestBuilder.extensionMutation(
               for: pending.toolCall,
               reason: "将写入本机 Skill 或 MCP 配置，需要你确认。"
           ) {
            return .waitingForApproval(.mcp(request))
        }

        if pending.toolCall.toolName == "model_council_run",
           requiresCouncilApproval,
           let request = ChatToolApprovalRequestBuilder.council(
               for: pending.toolCall,
               reason: "模型议会会发起多次模型请求，需要你确认。"
           ) {
            return .waitingForApproval(.council(request))
        }

        if pending.toolCall.toolName == "subagent_dispatch",
           requiresSubAgentApproval(),
           let request = ChatToolApprovalRequestBuilder.subAgent(
               for: pending.toolCall,
               reason: "子代理会发起独立模型请求并使用获准的只读工具，需要你确认。"
           ) {
            return .waitingForApproval(.council(request))
        }

        let resultText = await dispatchAdvancedToolCall(
            pending.toolCall,
            providerSetting: pending.providerSetting,
            params: pending.params,
            runId: pending.runId,
            conversationId: pending.conversationId,
            nestedTools: nestedTools,
            toolExposureBridge: toolExposureBridge
        )
        recordAdvancedToolApprovalIfNeeded(toolCall: pending.toolCall, runId: pending.runId)
        return .completed(messagesByFinishingToolCall(
            pending.toolCall,
            outputText: resultText,
            in: pending.baseMessages,
            conversationId: pending.conversationId
        ))
    }

    private func dispatchSearchToolCall(_ toolCall: UIMessagePart.Tool) async -> String {
        guard sharedSettings.snapshot.enableWebSearch else {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "Web search is disabled in settings."
            )
        }
        do {
            if toolCall.toolName == "search_web" {
                return try await executeSearchWebWithFallback(toolCall)
            }
            return try await IOSSearchExecutor.execute(
                toolName: toolCall.toolName,
                toolInput: toolCall.input,
                settings: sharedSettings.snapshot,
                transport: searchTransport
            )
        } catch is CancellationError {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "User cancelled.",
                cancelled: true
            )
        } catch {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            )
        }
    }

    private func shouldExecuteSearchInBackground(toolName: String, arguments: String) -> Bool {
        let autoApprove = IOSLocalToolExecutor.isGlobalAutoApproveEnabled
            || IOSLocalToolExecutor.isHighRiskAutoApproveEnabled
        guard !autoApprove else { return true }
        let toolCall = toolCall(name: toolName, input: arguments)
        return ChatToolApprovalRequestBuilder.search(
            for: toolCall,
            reason: "网络搜索和网页读取会访问外部站点，需要你确认。",
            settings: sharedSettings.snapshot
        ) == nil
    }

    private func executeSearchWebWithFallback(_ toolCall: UIMessagePart.Tool) async throws -> String {
        let settings = sharedSettings.snapshot
        let maxResults = Int(settings.searchCommonOptions.resultSize)
        let request = try IOSSearchExecutor.searchRequest(
            from: toolCall.input,
            defaultMaxResults: maxResults
        )
        let initialSelection = IOSSearchExecutor.searchProviderSelection(settings: settings)
        do {
            let execution = try await IOSSearchExecutor.searchResults(
                toolInput: toolCall.input,
                maxResults: maxResults,
                settings: settings,
                transport: searchTransport
            )
            return IOSSearchExecutor.format(
                query: execution.request.query,
                results: execution.results,
                selection: execution.selection
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            try Task.checkCancellation()
            guard let fallbackSelection = chatSearchFallbackSelection(
                after: initialSelection,
                settings: settings,
                initialError: error
            ) else {
                throw error
            }
            let results: [IOSSearchResult]
            switch fallbackSelection.route {
            case .duckDuckGoLite:
                results = try await IOSSearchExecutor.searchDuckDuckGoLite(
                    query: request.query,
                    maxResults: request.maxResults,
                    transport: searchTransport
                )
            case .bingHTML:
                results = try await IOSSearchExecutor.searchBingHTML(
                    query: request.query,
                    maxResults: request.maxResults,
                    transport: searchTransport
                )
            default:
                throw error
            }
            return IOSSearchExecutor.format(
                query: request.query,
                results: results,
                selection: fallbackSelection
            )
        }
    }

    private func chatSearchFallbackSelection(
        after selection: IOSSearchProviderSelection,
        settings: Settings,
        initialError: Error
    ) -> IOSSearchProviderSelection? {
        let reason = "原搜索服务 \(selection.providerName) 失败：\(searchErrorSummary(initialError))"
        if selection.route != .duckDuckGoLite, settings.searchBuiltinDuckDuckGoEnabled {
            return IOSSearchProviderSelection(
                route: .duckDuckGoLite,
                providerName: "DuckDuckGo Lite",
                providerType: "duckduckgo_builtin",
                serviceId: nil,
                fallbackReason: reason
            )
        }
        if selection.route != .bingHTML, settings.searchBuiltinBingEnabled {
            return IOSSearchProviderSelection(
                route: .bingHTML,
                providerName: "Bing HTML",
                providerType: "bing_builtin",
                serviceId: nil,
                fallbackReason: reason
            )
        }
        return nil
    }

    private func searchErrorSummary(_ error: Error) -> String {
        let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        guard message.count > 120 else { return message }
        return String(message.prefix(120)) + "..."
    }

    private func workspaceToolExecutionOutput(
        _ toolCall: UIMessagePart.Tool,
        isUserInitiated: Bool
    ) async -> IOSLocalToolExecutionOutput {
        guard let localToolExecutor else {
            return .failed("Local iOS tool executor is unavailable.")
        }
        return await localToolExecutor.execute(
            IOSLocalToolExecutionRequest(
                toolName: toolCall.toolName,
                operation: toolCall.input,
                scopeDigest: "workspace",
                payloadDigest: chatInputDigest(for: toolCall.input),
                isUserInitiated: isUserInitiated
            )
        )
    }

    private func ishToolExecutionOutput(
        _ toolCall: UIMessagePart.Tool,
        isUserInitiated: Bool
    ) async -> IOSLocalToolExecutionOutput {
        guard let localToolExecutor else {
            return .failed("Local iOS tool executor is unavailable.")
        }
        return await localToolExecutor.execute(
            IOSLocalToolExecutionRequest(
                toolName: toolCall.toolName,
                operation: toolCall.input,
                scopeDigest: "ish",
                payloadDigest: chatInputDigest(for: toolCall.input),
                isUserInitiated: isUserInitiated
            )
        )
    }

    private func dispatchWebMountToolCall(_ toolCall: UIMessagePart.Tool) async -> String {
        let output = await webMountToolExecutionOutput(toolCall, isUserInitiated: false)
        return ChatToolOutputFormatter.webMountResultText(for: toolCall, output: output)
    }

    private func webMountToolExecutionOutput(
        _ toolCall: UIMessagePart.Tool,
        isUserInitiated: Bool
    ) async -> IOSLocalToolExecutionOutput {
        guard let localToolExecutor else {
            return .failed("Local iOS tool executor is unavailable.")
        }
        return await localToolExecutor.execute(
            IOSLocalToolExecutionRequest(
                toolName: toolCall.toolName,
                operation: toolCall.input,
                scopeDigest: "webmount",
                payloadDigest: chatInputDigest(for: toolCall.input),
                isUserInitiated: isUserInitiated
            )
        )
    }

    private func dispatchMemoryToolCall(
        _ toolCall: UIMessagePart.Tool,
        writePolicy: IOSMemoryToolWritePolicy
    ) -> String {
        IOSMemoryToolExecutor.execute(
            input: toolCall.input,
            runtime: sharedSettings.agentRuntime,
            writePolicy: writePolicy
        )
    }

    private func memoryToolWritePolicy(input: String, isUserInitiated: Bool) -> IOSMemoryToolWritePolicy {
        localToolExecutor?.memoryToolWritePolicy(
            input: input,
            isUserInitiated: isUserInitiated
        ) ?? (IOSMemoryToolExecutor.requiresWriteApproval(input: input)
            ? .needsUserAction("Memory writes require foreground approval.")
            : .allow)
    }

    private func dispatchImageToolCall(_ toolCall: UIMessagePart.Tool) async -> [UIMessagePart] {
        do {
            switch codexImageConfig() {
            case .signedIn(let codex):
                let request = try IOSImageGenerationRepository.shared.toolRequest(
                    from: toolCall.input,
                    modelId: IOSCodexOAuthConstants.imageModelId
                )
                let record = try await IOSImageGenerationRepository.shared.generateViaCodex(
                    request: request,
                    providerId: codex
                )
                var parts: [UIMessagePart] = record.files.map { file in
                    UIMessagePart.Image(
                        url: IOSImageGenerationRepository.chatImageURLString(filePath: file.path),
                        metadata: nil
                    )
                }
                parts.append(UIMessagePart.Text(text: IOSImageGenerationRepository.shared.toolResultJSON(record: record), metadata: nil))
                return parts
            case .notSignedIn:
                return [UIMessagePart.Text(
                    text: ChatToolOutputFormatter.toolFailureJSON(
                        toolName: "generate_image",
                        reason: "请先在服务商设置里登录 Codex 后再生成或修改图片。"
                    ),
                    metadata: nil
                )]
            case .notSelected:
                break
            }
            guard let config = resolvedImageGenerationConfig() else {
                return [UIMessagePart.Text(
                    text: ChatToolOutputFormatter.toolFailureJSON(
                        toolName: "generate_image",
                        reason: "请先在「默认模型 → 辅助任务」里设置生图模型。"
                    ),
                    metadata: nil
                )]
            }
            let request = try IOSImageGenerationRepository.shared.toolRequest(from: toolCall.input, modelId: config.modelId)
            if request.sourceImageURL != nil {
                return [UIMessagePart.Text(
                    text: ChatToolOutputFormatter.toolFailureJSON(
                        toolName: "generate_image",
                        reason: "当前图片修改只支持 Codex 生图。"
                    ),
                    metadata: nil
                )]
            }
            let record = try await IOSImageGenerationRepository.shared.generate(
                request: request,
                apiKey: config.apiKey,
                baseURL: config.baseURL
            )
            var parts: [UIMessagePart] = record.files.map { file in
                UIMessagePart.Image(
                    url: IOSImageGenerationRepository.chatImageURLString(filePath: file.path),
                    metadata: nil
                )
            }
            parts.append(UIMessagePart.Text(text: IOSImageGenerationRepository.shared.toolResultJSON(record: record), metadata: nil))
            return parts
        } catch {
            return [
                UIMessagePart.Text(
                    text: ChatToolOutputFormatter.toolFailureJSON(
                        toolName: "generate_image",
                        reason: error.localizedDescription
                    ),
                    metadata: nil
                )
            ]
        }
    }

    private func dispatchAdvancedToolCall(
        _ toolCall: UIMessagePart.Tool,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        conversationId: KotlinUuid? = nil,
        nestedTools: IOSJsSandboxTools? = nil,
        toolExposureBridge: IosToolExposureBridge? = nil
    ) async -> String {
        guard isAdvancedToolEnabled(toolCall.toolName) else {
            return IOSWorkspaceStore.json([
                "ok": false,
                "tool": toolCall.toolName,
                "status": "denied",
                "denied": true,
                "policy": "disabled",
                "reason": "\(toolCall.toolName) 未开启。请先在设置中启用对应能力。"
            ])
        }
        switch toolCall.toolName {
        case "subagent_dispatch":
            let args = ChatToolCallParsing.jsonObject(toolCall.input)
            let objective = args?["objective"] as? String ?? toolCall.input
            let roleId = args?["role_id"] as? String ?? args?["subagent_id"] as? String ?? "explorer"
            let scope = ChatToolCallParsing.stringArray(args?["tool_scope"])
                ?? ChatToolCallParsing.stringArray(args?["tools"])
                ?? []
            return await subAgentRunner.runViaEngine(
                objective: objective,
                roleId: roleId,
                requestedToolScope: scope,
                customRoleName: args?["custom_role_name"] as? String,
                customRoleLens: args?["custom_role_lens"] as? String,
                customRolePrompt: args?["custom_role_prompt"] as? String,
                savedRolePromptOverride: sharedSettings.snapshot.agentRuntime.subAgent.overrides[roleId]?.systemPrompt,
                maxTurnsOverride: args?["max_turns"] as? Int,
                outputBudgetCharsOverride: args?["output_budget_chars"] as? Int,
                providerSetting: providerSetting,
                modelId: params.model.modelId,
                baseParams: params,
                parentToolExecutors: subAgentParentToolExecutors(runId: runId),
                toolCallId: toolCall.toolCallId
            )
        case "model_council_run":
            let args = ChatToolCallParsing.jsonObject(toolCall.input)
            let objective = args?["objective"] as? String ?? toolCall.input
            let maxSeats = args?["max_seats"] as? Int
            return await councilRunner.run(
                objective: objective,
                maxSeats: maxSeats,
                providerSetting: providerSetting,
                currentModel: params.model,
                baseParams: params
            )
        case "mcp_call":
            guard let args = ChatToolCallParsing.jsonObject(toolCall.input),
                  let server = args["server"] as? String,
                  let tool = args["tool"] as? String else {
                return "mcp_call 参数无效：需要 server 与 tool。"
            }
            let arguments = (args["arguments"] as? [String: Any]) ?? [:]
            do {
                return try await mcpManager.callTool(serverName: server, toolName: tool, arguments: arguments)
            } catch {
                // P2-a：失败输出必须是结构化 JSON（与 search 路径同契约），否则
                // failureReason 识别不到 → 误把失败调用标成 POLLUTED。
                return ChatToolOutputFormatter.toolFailureJSON(
                    toolName: toolCall.toolName,
                    reason: "MCP 调用失败（server: \(server)，tool: \(tool)）：\(error.localizedDescription)",
                    status: "failed"
                )
            }
        case let name where ToolKt.isExpandedMcpToolName(name: name):
            // P0-b: prefix routing — the flattened name is resolved against the
            // CURRENT discovery directory (sanitization is not reversible).
            // A name whose server/tool vanished mid-run gets an honest
            // status=failed payload, never a crash.
            guard let target = resolvedMcpTarget(forExpandedName: name) else {
                return ChatToolOutputFormatter.toolFailureJSON(
                    toolName: name,
                    reason: "MCP 工具已不可用：当前已启用的 server 中不存在该工具。请调用 mcp_list 检查可用的 MCP 工具。",
                    status: "failed"
                )
            }
            guard let arguments = ChatToolCallParsing.jsonObject(toolCall.input) else {
                return ChatToolOutputFormatter.toolFailureJSON(
                    toolName: name,
                    reason: "MCP 工具参数无效：需要 JSON 对象。",
                    status: "failed"
                )
            }
            do {
                return try await mcpManager.callTool(serverName: target.server, toolName: target.tool, arguments: arguments)
            } catch {
                // P2-a：与 mcp_call 同一契约——结构化失败输出，failureReason 可识别，
                // 不把失败调用误标成 POLLUTED。
                return ChatToolOutputFormatter.toolFailureJSON(
                    toolName: name,
                    reason: "MCP 调用失败（server: \(target.server)，tool: \(target.tool)）：\(error.localizedDescription)",
                    status: "failed"
                )
            }
        case let name where IOSSkillToolCatalog.toolNames.contains(name)
            || IOSMcpManagementToolCatalog.toolNames.contains(name):
            return await skillMcpToolService.execute(toolName: name, arguments: toolCall.input)
        case "spawn_agent", "list_agents", "interrupt_agent", "send_message", "followup_task", "wait_agent":
            // P1-c/P1-d: 线程编排工具走独立服务（会话 fork / edge / mailbox / 子 run
            // 启动与取消全部收口在服务内；未注入时诚实报错而非静默缺失）。
            // conversationId = run 锚定会话（前台由 pending/conversationId 透传，
            // 后台由 job 透传）——生成中切会话不会建错边或误拒。
            guard let orchestrationToolService else {
                return ChatToolOutputFormatter.toolFailureJSON(
                    toolName: toolCall.toolName,
                    reason: "线程编排工具当前不可用。",
                    status: "failed"
                )
            }
            return await orchestrationToolService.execute(
                toolName: toolCall.toolName,
                arguments: toolCall.input,
                providerSetting: providerSetting,
                params: params,
                runId: runId,
                conversationId: conversationId,
                // M3: 子 run 的 fullToolNames 取 run 桥全目录（spawn/followup
                // 不被当轮可见子集截断）；nil 时服务回退 params.tools。
                toolExposureBridge: toolExposureBridge
            )
        case "exec":
            // P3-b: exec 求值可带嵌套 tools 桥（白名单 + 宿主 runner 由
            // executeAdvancedToolCall 传入；nil = 纯求值，同 P3-a）。
            // P3-c: conversationId = 会话作用域键（cell 注册表 + store/load）。
            return await dispatchExecToolCall(
                toolCall,
                nestedTools: nestedTools,
                conversationId: conversationId
            )
        case "wait":
            // P3-c: 续取本会话的 exec cell（yield/wait/terminate 三路径）。
            return await dispatchWaitToolCall(toolCall, conversationId: conversationId)
        default:
            return "未知工具：\(toolCall.toolName)"
        }
    }

    /// P3-c: wait timeout clamp (declaration contract: [1000, 60000], default 10000).
    static func clampWaitTimeoutMs(_ value: Int) -> Int {
        min(max(value, 1000), 60000)
    }

    /// P3-d: max_output_chars hard cap (declaration contract: [1, 100000],
    /// default 10000). The returned payload lands in the tool output and the
    /// ledger, so a model-supplied unbounded limit must not bypass truncation.
    static let maxOutputCharsHardCap = 100000

    static func clampMaxOutputChars(_ value: Int) -> Int {
        min(max(value, 1), maxOutputCharsHardCap)
    }

    /// P3-c: one `exec` call with the cell lifecycle.
    ///
    /// Every evaluation registers a Running cell first (per-session
    /// concurrency cap of 4, enforced before any JS runs). On timeout the
    /// handle is NOT dropped: exec returns the codex wording
    /// "Script running with cell ID {cell_id}" and the evaluation keeps
    /// running on its own queue (abandon semantics); the completion listener
    /// delivers the eventual result to the registry so a later `wait` can
    /// retrieve it. Inline terminal (success/failure within the timeout) cells
    /// are consumed here — the model never saw a cell_id, so nothing can wait
    /// on them (read-once, zero residue).
    private func dispatchExecToolCall(
        _ toolCall: UIMessagePart.Tool,
        nestedTools: IOSJsSandboxTools? = nil,
        conversationId: KotlinUuid? = nil
    ) async -> String {
        guard let args = ChatToolCallParsing.jsonObject(toolCall.input),
              let code = args["code"] as? String, !code.isEmpty else {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "exec 参数无效：需要非空 code（JavaScript 源码）。"
            )
        }
        let timeoutMs = IOSJsSandboxEngine.clampTimeoutMs(
            (args["timeout_ms"] as? Int) ?? IOSJsSandboxEngine.defaultTimeoutMs
        )
        let maxOutputChars = Self.clampMaxOutputChars(
            (args["max_output_chars"] as? Int)
                ?? IOSJsSandboxEngine.defaultMaxOutputChars
        )
        let sessionKey = conversationId?.description() ?? "global"
        let cellId = UUID().uuidString

        let started = await jsCellRegistry.startCell(sessionKey: sessionKey, cellId: cellId)
        guard started == .started else {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "exec 并发 cell 已达上限（每会话 \(IOSJsCellRegistry.maxRunningCellsPerSession) 个）。请先 wait 或 terminate 已有 cell。",
                status: "failed"
            )
        }

        // P3-c: session-scoped store/load bridge — shared by every cell of the
        // conversation, persisted by the registry (single writer).
        let storeBridge = IOSJsSandboxStore(
            load: { [registry = jsCellRegistry] key in
                await registry.loadValue(sessionKey: sessionKey, key: key)
            },
            store: { [registry = jsCellRegistry] key, value in
                switch await registry.storeValue(sessionKey: sessionKey, key: key, valueJSON: value) {
                case .stored:
                    return nil
                case .overLimit(let reason):
                    return reason
                }
            }
        )
        let result = await jsSandboxEngine.evaluate(
            code: code,
            timeoutMs: timeoutMs,
            maxOutputChars: maxOutputChars,
            tools: nestedTools,
            store: storeBridge,
            completion: { [registry = jsCellRegistry] final in
                Task { @Sendable in
                    await registry.finishCell(
                        sessionKey: sessionKey,
                        cellId: cellId,
                        result: final,
                        maxOutputChars: maxOutputChars
                    )
                }
            }
        )
        switch result {
        case .success, .failure:
            // Inline terminal: exec's own payload carries the result/error;
            // consume the cell (nothing can reference it without a cell_id).
            await jsCellRegistry.removeCell(sessionKey: sessionKey, cellId: cellId)
            return IOSJsSandboxEngine.toolPayload(result, maxOutputChars: maxOutputChars)
        case .timedOut:
            // P3-c yield: the cell keeps running; the model continues it with
            // wait(cell_id). The evaluation's completion listener will deliver
            // the eventual result to the registry.
            return IOSWorkspaceStore.json([
                "status": "running",
                "cell_id": cellId,
                "output": "Script running with cell ID \(cellId)",
            ])
        }
    }

    /// P3-c: one `wait` call — the three paths:
    /// - cell missing → structured error (never silent).
    /// - `terminate: true` → mark Terminated (abandon semantics: JavaScriptCore
    ///   cannot force-kill the runaway script; it keeps burning CPU until it
    ///   ends by itself, and its result is discarded) and return the terminal.
    /// - otherwise block until the cell completes or the wait timeout elapses
    ///   (clamped [1000, 60000], default 10000); a still-running cell returns
    ///   its current running status so the model can wait again.
    /// Every wait is an ordinary tool call — it consumes one tool-resume
    /// budget slot via the existing maxToolResumeCount/maxSteps machinery, no
    /// separate budget mechanism.
    private func dispatchWaitToolCall(
        _ toolCall: UIMessagePart.Tool,
        conversationId: KotlinUuid?
    ) async -> String {
        guard let args = ChatToolCallParsing.jsonObject(toolCall.input),
              let cellId = args["cell_id"] as? String, !cellId.isEmpty else {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "wait 参数无效：需要 cell_id（exec 超时 yield 返回的 cell ID）。"
            )
        }
        let timeoutMs = Self.clampWaitTimeoutMs(
            (args["timeout_ms"] as? Int) ?? IOSJsSandboxEngine.defaultTimeoutMs
        )
        let terminate = (args["terminate"] as? Bool) ?? false
        let sessionKey = conversationId?.description() ?? "global"
        let outcome = await jsCellRegistry.wait(
            cellId: cellId,
            sessionKey: sessionKey,
            timeoutMs: timeoutMs,
            terminate: terminate
        )
        switch outcome {
        case .notFound:
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolCall.toolName,
                reason: "cell \(cellId) 不存在或已读取。exec 超时会返回 cell ID；同一 cell 的输出只可读取一次。",
                status: "failed"
            )
        case .stillRunning:
            return IOSWorkspaceStore.json([
                "status": "running",
                "cell_id": cellId,
                "output": "Script running with cell ID \(cellId)",
            ])
        case .cancelled:
            // M6: run 取消期间 wait 立即收口（不等超时）——结构化终态，不冒充
            // running/超时；cell 保持 Running 可再 wait。
            return IOSWorkspaceStore.json([
                "status": "cancelled",
                "cell_id": cellId,
            ])
        case .terminal(let record):
            var object: [String: Any] = [
                "status": record.status.rawValue,
                "output": record.output ?? NSNull(),
                "logs": record.logs,
            ]
            if let error = record.error {
                object["error"] = error
            }
            return IOSWorkspaceStore.json(object)
        }
    }

    private func subAgentParentToolExecutors(runId: String) -> [String: any IOSToolExecutor] {
        Dictionary(uniqueKeysWithValues: IOSSubAgentToolPolicy.readOnlyParentToolNames.map { name in
            (name, IOSClosureToolExecutor { [weak self] toolName, arguments, _ in
                guard let self else {
                    return .failed("Chat runtime is unavailable.")
                }
                return await self.executeSubAgentParentTool(name: toolName, arguments: arguments, runId: runId)
            } as any IOSToolExecutor)
        })
    }

    private func executeSubAgentParentTool(
        name: String,
        arguments: String,
        runId: String
    ) async -> IOSAgentToolOutcome {
        guard IOSSubAgentToolPolicy.readOnlyParentToolNames.contains(name) else {
            return .denied("SubAgent read-only scope does not allow \(name).")
        }

        if IOSSearchExecutor.supportedToolNames.contains(name) {
            let output = await dispatchSearchToolCall(toolCall(name: name, input: arguments))
            recordSubAgentParentToolApproval(toolName: name, arguments: arguments, action: .allowed, runId: runId)
            return .filled(output)
        }

        guard let localToolExecutor else {
            return .failed("Local iOS tool executor is unavailable.")
        }

        let request: IOSLocalToolExecutionRequest
        if name == "file_read_selected" {
            request = localToolExecutor.requestForCurrentSelectedFile(isUserInitiated: true)
        } else {
            request = IOSLocalToolExecutionRequest(
                toolName: name,
                operation: arguments,
                scopeDigest: "subagent",
                payloadDigest: chatInputDigest(for: arguments),
                isUserInitiated: false
            )
        }
        let output = await localToolExecutor.execute(request)
        recordSubAgentParentToolApproval(
            toolName: name,
            arguments: arguments,
            action: output.isSuccessfulToolResult ? .allowed : .denied,
            runId: runId
        )
        return ChatToolOutputFormatter.subAgentOutcome(for: name, output: output)
    }

    private func recordSubAgentParentToolApproval(
        toolName: String,
        arguments: String,
        action: IOSToolApprovalAction,
        runId: String
    ) {
        guard let localToolExecutor,
              let capability = IOSCapabilityRegistry.capability(forToolName: toolName) else { return }
        localToolExecutor.recordApproval(
            capabilityId: capability.id,
            toolName: toolName,
            action: action,
            reason: "SubAgent read-only parent tool \(action == .allowed ? "executed" : "denied").",
            runId: runId,
            scopeDigest: "subagent",
            payloadDigest: chatInputDigest(for: arguments)
        )
    }

    private func recordAdvancedToolApprovalIfNeeded(
        toolCall: UIMessagePart.Tool,
        runId: String
    ) {
        guard toolCall.toolName == "subagent_dispatch" else { return }
        let capabilityId = "ios.agent.subagent_dispatch"
        let enabled = isAdvancedToolEnabled(toolCall.toolName)
        recordToolApproval(
            capabilityId: capabilityId,
            toolCall: toolCall,
            action: enabled ? .allowed : .denied,
            reason: "\(toolCall.toolName) model tool call \(enabled ? "executed" : "denied").",
            runId: runId
        )
    }

    private func toolCall(name: String, input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "subagent-\(name)-\(chatInputDigest(for: input))",
            toolName: name,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func recordToolApproval(
        capabilityId: String,
        toolCall: UIMessagePart.Tool,
        action: IOSToolApprovalAction,
        reason: String,
        runId: String
    ) {
        localToolExecutor?.recordApproval(
            capabilityId: capabilityId,
            toolName: toolCall.toolName,
            action: action,
            reason: reason,
            runId: runId,
            scopeDigest: chatToolCallKey(toolCall),
            payloadDigest: chatInputDigest(for: toolCall.input)
        )
    }

    private func messagesByFinishingToolCall(
        _ targetToolCall: UIMessagePart.Tool,
        outputParts: [UIMessagePart],
        in messages: [UIMessage]
    ) -> [UIMessage] {
        var didFinishToolCall = false

        return messages.map { message in
            guard message.role == MessageRole.assistant else { return message }
            var didChangeMessage = false
            let parts = message.parts.map { part -> UIMessagePart in
                guard !didFinishToolCall,
                      let toolPart = part as? UIMessagePart.Tool,
                      chatToolCallKey(toolPart) == chatToolCallKey(targetToolCall) else {
                    return part
                }

                didFinishToolCall = true
                didChangeMessage = true
                return UIMessagePart.Tool(
                    toolCallId: toolPart.toolCallId,
                    toolName: toolPart.toolName,
                    input: toolPart.input,
                    // 工具输出统一收口：总文本超上限就地截断（JSON 形态保形），
                    // 防止 Exa 全文等巨量输出被持久化进会话。
                    output: ChatToolOutputFormatter.cappedToolOutputParts(outputParts),
                    approvalState: toolPart.approvalState,
                    streamIndex: toolPart.streamIndex,
                    metadata: nil
                )
            }

            guard didChangeMessage else { return message }
            return UIMessage(
                id: message.id,
                role: message.role,
                parts: parts,
                annotations: message.annotations,
                createdAt: message.createdAt,
                finishedAt: message.finishedAt ?? chatNowLocalDateTime(),
                modelId: message.modelId,
                usage: message.usage,
                translation: message.translation
            )
        }
    }

    private var isWebMountRuntimeEnabled: Bool {
        true
    }

    /// P0-b: resolve a flattened `mcp__{server}__{tool}` name back to the
    /// discovery directory (enabled servers only). Sanitization is not
    /// reversible, so this is the authoritative lookup for execution routing.
    private func resolvedMcpTarget(forExpandedName name: String) -> (server: String, tool: String)? {
        let enabledServerNames = Set(mcpManager.servers.filter(\.enabled).map(\.name))
        for discovered in mcpManager.tools where enabledServerNames.contains(discovered.serverName) {
            if ToolKt.expandedMcpToolName(server: discovered.serverName, tool: discovered.tool.name) == name {
                return (discovered.serverName, discovered.tool.name)
            }
        }
        return nil
    }

    /// P0-b: flattened MCP declarations over the CURRENT directory. The
    /// background job regenerates them so its exposure bridge catalog stays in
    /// parity with the foreground run — handoff payloads carry tool NAMES only,
    /// and dynamic `mcp__*` tools cannot be rebuilt from a name.
    func mcpExpandedDeclarations() -> [Tool] {
        expandedMcpToolDeclarations(mcpManager: mcpManager)
    }

    private func isAdvancedToolEnabled(_ toolName: String) -> Bool {
        switch toolName {
        case "mcp_call", "mcp_list", "mcp_test", "mcp_describe_tool", "mcp_import_from_skill":
            true
        case let name where ToolKt.isExpandedMcpToolName(name: name):
            // P0-b: flattened MCP tools follow mcp_call's always-on gate.
            true
        case "skills_list", "use_skill", "skill_validate", "skill_import", "skill_enable", "skill_disable":
            true
        case "subagent_dispatch":
            isCapabilityPolicyEnabled("ios.agent.subagent_dispatch")
        case "model_council_run":
            isCapabilityPolicyEnabled("ios.agent.model_council_run")
        case "spawn_agent", "list_agents", "interrupt_agent", "send_message", "followup_task", "wait_agent":
            // P1-c/P1-d: 编排工具非常驻、不加新设置项——与 mcp 一样恒可用
            // （暴露与否由 tool_search 的 deferred 池决定）。
            true
        case "exec":
            // P3-a: 与声明侧同源 gate。开关关时（理论上调用到不了这里，因为
            // pendingAdvancedToolCall 不收 exec）诚实拒绝而非静默执行。
            settingsStore.execJavaScriptEnabled
        case "wait":
            // P3-c: 与 exec 同开关（cell 续取工具没有独立设置项）。
            settingsStore.execJavaScriptEnabled
        default:
            false
        }
    }

    private func isCapabilityPolicyEnabled(_ capabilityId: String) -> Bool {
        guard let localToolExecutor else { return true }
        let snapshot = localToolExecutor.permissionsStatus()
        return snapshot.capabilities.first { $0.id == capabilityId }?.policy != IOSAgentPermissionPolicy.disabled.title
    }

    private var requiresCouncilApproval: Bool {
        guard let policy = localToolExecutor?.permissionPolicy(
            capabilityId: "ios.agent.model_council_run"
        ) else {
            return false
        }
        return policy == .askEveryTime || policy == .allowOncePerRun
    }

    func requiresSubAgentApproval() -> Bool {
        guard let policy = localToolExecutor?.permissionPolicy(
            capabilityId: "ios.agent.subagent_dispatch"
        ) else {
            return false
        }
        return policy == .askEveryTime || policy == .allowOncePerRun
    }

#if DEBUG
    func finishedToolCallMessagesForTesting(
        _ targetToolCall: UIMessagePart.Tool,
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        messagesByFinishingToolCall(targetToolCall, outputText: outputText, in: messages)
    }

    func memoryToolOutputForTesting(input: String) -> String {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-memory-tool",
            toolName: "memory_tool",
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        return dispatchMemoryToolCall(
            toolCall,
            writePolicy: memoryToolWritePolicy(input: input, isUserInitiated: false)
        )
    }

    func memoryApprovalRequestForTesting(input: String) -> MemoryToolApprovalRequest? {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-memory-tool",
            toolName: "memory_tool",
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        guard case .needsUserAction(let reason) = memoryToolWritePolicy(input: input, isUserInitiated: false) else {
            return nil
        }
        return ChatToolApprovalRequestBuilder.memory(for: toolCall, reason: reason)
    }

    func memoryToolApprovalOutputForTesting(
        input: String,
        allow: Bool,
        expectedUpdatedAt: Int64? = nil
    ) -> String {
        IOSMemoryToolExecutor.execute(
            input: input,
            runtime: sharedSettings.agentRuntime,
            writePolicy: allow ? .allow : .deniedByUser("User denied memory write."),
            expectedUpdatedAt: expectedUpdatedAt
        )
    }

    func webMountToolOutputForTesting(
        toolName: String,
        input: String,
        isUserInitiated: Bool = false
    ) async -> String {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-webmount-tool",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        if !isUserInitiated {
            return await dispatchWebMountToolCall(toolCall)
        }
        let output = await webMountToolExecutionOutput(toolCall, isUserInitiated: isUserInitiated)
        return ChatToolOutputFormatter.webMountResultText(for: toolCall, output: output)
    }

    func webMountApprovalRequestForTesting(
        toolName: String,
        input: String
    ) async -> WebMountToolApprovalRequest? {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-webmount-tool",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let output = await webMountToolExecutionOutput(toolCall, isUserInitiated: false)
        guard case .needsUserAction(let reason) = output else { return nil }
        return ChatToolApprovalRequestBuilder.webMount(
            for: toolCall,
            reason: reason,
            localToolExecutor: localToolExecutor
        )
    }

    func webMountToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-webmount-tool",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        guard allow else {
            return IOSWebMountController.json([
                "ok": false,
                "tool": toolName,
                "denied": true,
                "policy": "user_denied",
                "reason": "User denied WebMount foreground action."
            ])
        }
        let output = await webMountToolExecutionOutput(toolCall, isUserInitiated: true)
        return ChatToolOutputFormatter.webMountResultText(for: toolCall, output: output)
    }

    func searchApprovalRequestForTesting(
        toolName: String,
        input: String
    ) -> SearchToolApprovalRequest? {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-search-tool",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        return ChatToolApprovalRequestBuilder.search(
            for: toolCall,
            reason: "Test approval",
            settings: sharedSettings.snapshot
        )
    }

    func searchToolApprovalOutputForTesting(
        toolName: String,
        input: String,
        allow: Bool
    ) async -> String {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "test-search-tool",
            toolName: toolName,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        guard allow else {
            return ChatToolOutputFormatter.toolFailureJSON(
                toolName: toolName,
                reason: "User denied network search.",
                denied: true
            )
        }
        return await dispatchSearchToolCall(toolCall)
    }
#endif
}
