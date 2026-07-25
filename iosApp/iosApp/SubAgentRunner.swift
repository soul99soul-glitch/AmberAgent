import Foundation
import Observation
@preconcurrency import Shared

private func subAgentJSON(_ object: [String: Any]) -> String {
    guard JSONSerialization.isValidJSONObject(object),
          let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
          let text = String(data: data, encoding: .utf8) else {
        return String(describing: object)
    }
    return text
}

struct IOSSubAgentRoleDescriptor: Identifiable, Equatable {
    let id: String
    let name: String
    let summary: String
    let systemPrompt: String
    let routing: String
    let toolAllowlist: [String]
    let maxTurns: Int
    let timeoutSeconds: Int
    let outputBudgetChars: Int
}

enum IOSSubAgentRoleCatalog {
    static let builtIns: [IOSSubAgentRoleDescriptor] = [
        .init(
            id: "explorer",
            name: "Explorer",
            summary: "跨多源快速并行侦察，速度优先。",
            systemPrompt: "快速侦察范围、列出证据和未知数，优先用只读来源。",
            routing: "范围广、不确定、需要先摸清有哪些信息时调用。",
            toolAllowlist: [
                "tools_list", "search_web", "scrape_web", "file_read_selected", "permissions_status",
                "workspace_file_read", "workspace_file_list", "workspace_file_search", "workspace_artifact_read",
                "wm_stations", "wm_state", "wm_extract", "wm_get", "wm_find", "wm_wait", "wm_back", "wm_forward"
            ],
            maxTurns: 4,
            timeoutSeconds: 300,
            outputBudgetChars: 12_000
        ),
        .init(
            id: "historian",
            name: "Historian",
            summary: "历史会话搜索、主题挖掘、跨分片综合。",
            systemPrompt: "聚合历史上下文，明确哪些结论来自过往记录。",
            routing: "需要回忆过去对话、决策或跨会话主题时调用。",
            toolAllowlist: ["tools_list", "permissions_status"],
            maxTurns: 4,
            timeoutSeconds: 300,
            outputBudgetChars: 12_000
        ),
        .init(
            id: "oracle",
            name: "Oracle",
            summary: "架构取舍、风险复议、提交前评审。",
            systemPrompt: "进行高判断力复议，重点给出风险、反例和取舍。",
            routing: "长期影响大的决定、高风险重构、提交前二次复议时调用。",
            toolAllowlist: ["tools_list", "file_read_selected", "permissions_status", "workspace_file_read", "workspace_file_search", "workspace_artifact_read"],
            maxTurns: 4,
            timeoutSeconds: 300,
            outputBudgetChars: 12_000
        ),
        .init(
            id: "designer",
            name: "Designer",
            summary: "视觉产出规格、版式、配色和信息密度审查。",
            systemPrompt: "从视觉质量、信息层级和移动端可用性评审输出。",
            routing: "生成或评审视觉产物，并且在意版式和信息密度时调用。",
            toolAllowlist: ["tools_list", "file_read_selected", "workspace_file_read", "workspace_artifact_read"],
            maxTurns: 3,
            timeoutSeconds: 240,
            outputBudgetChars: 8_000
        ),
        .init(
            id: "writer",
            name: "Writer",
            summary: "中文写作、文案润色、故事与风格改写。",
            systemPrompt: "以中文表达质量为第一目标，保留事实边界。",
            routing: "中文写作、润色、邮件、文案或故事表达时调用。",
            toolAllowlist: ["tools_list", "file_read_selected", "workspace_file_read", "workspace_artifact_read"],
            maxTurns: 3,
            timeoutSeconds: 240,
            outputBudgetChars: 8_000
        ),
        .init(
            id: "fixer",
            name: "Fixer",
            summary: "边界清晰的机械执行：翻译、格式转换、抽取。",
            systemPrompt: "完成边界清晰的机械任务，不扩大范围。",
            routing: "翻译、格式化、抽取、命名等明确任务时调用。",
            toolAllowlist: ["tools_list"],
            maxTurns: 2,
            timeoutSeconds: 180,
            outputBudgetChars: 6_000
        )
    ]

    static func resolve(roleId: String?) -> IOSSubAgentRoleDescriptor {
        let normalized = roleId?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        return builtIns.first { $0.id == normalized } ?? builtIns[0]
    }
}

enum IOSSubAgentToolPolicy {
    static let readOnlyParentToolNames: Set<String> = [
        "search_web", "scrape_web",
        "file_read_selected", "permissions_status",
        "workspace_file_read", "workspace_file_list", "workspace_file_search", "workspace_artifact_read",
        "wm_stations", "wm_state", "wm_extract", "wm_get", "wm_find", "wm_wait", "wm_back", "wm_forward"
    ]

    static let deniedToolNames: Set<String> = [
        "workspace_file_write", "workspace_file_edit", "workspace_file_move", "workspace_artifact_delete",
        "wm_open", "wm_clear_session", "wm_click", "wm_tap", "wm_type", "wm_keys", "wm_scroll", "wm_select",
        "mcp_call", "memory_tool", "generate_image", "subagent_dispatch", "model_council_run"
    ]

    static func isDenied(_ name: String) -> Bool {
        deniedToolNames.contains(name)
    }
}

/// iOS entry point for calling SubAgentManager.start/read/wait/cancel.
/// Uses IosSubAgentFactory (KMP iosMain). Real provider if API key configured;
/// otherwise falls back to an honest stub runner for call-chain validation.
@MainActor
@Observable
final class SubAgentRunner {
    @ObservationIgnored private var manager: SubAgentManager?
    @ObservationIgnored private var currentRunId: String?
    @ObservationIgnored private let taskStore: IOSAdvancedTaskStore
    @ObservationIgnored private var currentTaskId: String?

    var lastRunResult: String = "(未运行)"
    var isRunning: Bool = false
    var lastTask: IOSAdvancedTaskRecord?

    init(taskStore: IOSAdvancedTaskStore = .shared) {
        self.taskStore = taskStore
    }

    var recentTasks: [IOSAdvancedTaskRecord] {
        taskStore.recent(kind: .subAgent, limit: 5)
    }

    private func ensureManager() -> SubAgentManager? {
        if let manager { return manager }
        guard let docsDir = try? FileManager.default.url(
            for: .documentDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        ).path else { return nil }

        let store = SettingsStore()
        let baseUrl = store.baseUrl
        let apiKey = store.currentApiKey
        let modelId = store.modelId

        let m: SubAgentManager
        if !apiKey.isEmpty {
            m = IosSubAgentFactory.shared.createWithRealProvider(
                documentsDir: docsDir,
                baseUrl: baseUrl,
                apiKey: apiKey,
                modelId: modelId
            )
        } else {
            m = IosSubAgentFactory.shared.create(documentsDir: docsDir)
        }
        manager = m
        return m
    }

    func runTestCycle() {
        Task {
            lastRunResult = await run(
                objective: "验证 iOS SubAgentManager start/read/wait/cancel 调用链",
                roleId: "explorer"
            )
        }
    }

    // MARK: - Engine-based multi-turn execution (Android GenerationSubAgentRunner parity)
    //
    // The KMP RealOpenAISubAgentRunner does a single non-streaming call with no
    // tools and never passes parentTools (it's a stub). This Swift path uses the
    // reusable IOSAgentToolEngine to run a real multi-turn loop: the model can
    // call the allowed parent tools + subagent_report, the engine executes each
    // and re-calls the model, and the structured report is captured. Mirrors
    // Android's GenerationSubAgentRunner (maxTurns loop + subagent_report
    // capture + resultOrFallback). Real-model behavior is validated via manual
    // smoke; the loop mechanics are unit-tested with a scripted provider.

    /// Executes a SubAgent via the engine. Caller supplies the engine's tool
    /// executors (parent tools) so this stays decoupled from ChatViewModel.
    /// Returns the captured report JSON (or an honest fallback).
    func runViaEngine(
        objective: String,
        roleId: String = "explorer",
        requestedToolScope: [String] = [],
        providerSetting: ProviderSetting,
        modelId: String,
        baseParams: TextGenerationParams? = nil,
        parentToolExecutors: [String: any IOSToolExecutor],
        toolCallId: String = "",
        timeoutSeconds: TimeInterval? = nil,
        provider: any IOSAgentTextProvider = OpenAIKmpProviderAdapter()
    ) async -> String {
        let role = IOSSubAgentRoleCatalog.resolve(roleId: roleId)
        let requested = requestedToolScope
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let deniedRequested = requested.filter(IOSSubAgentToolPolicy.isDenied)
        if !deniedRequested.isEmpty {
            return Self.json([
                "ok": false,
                "denied": true,
                "reason": "SubAgent read-only scope does not execute write, destructive, nested-agent, or sensitive WebMount tools.",
                "requested_tools": deniedRequested
            ])
        }
        let scopedTools = requested.isEmpty
            ? role.toolAllowlist
            : requested.filter { role.toolAllowlist.contains($0) }
        let tools = Self.uniqueTools((["tools_list"] + scopedTools).filter { tool in
            tool == "tools_list" || IOSSubAgentToolPolicy.readOnlyParentToolNames.contains(tool)
        })

        let task = taskStore.startTask(
            kind: .subAgent,
            title: "\(role.name) · \(objective.prefix(36))",
            objective: objective,
            roleId: role.id,
            toolScope: tools,
            budgetSummary: "turns \(role.maxTurns) · timeout \(role.timeoutSeconds)s · output \(role.outputBudgetChars) chars (engine)",
            sourceToolName: "subagent_dispatch",
            metadata: [
                "provider_mode": "engine_real_provider",
                "role_name": role.name,
                "engine": "true"
            ]
        )
        currentTaskId = task.id
        lastTask = task
        isRunning = true
        defer { isRunning = false }

        // Build the report-capture executor + merge with parent tools. Only the
        // allowed tools get executors; others fall through to the engine's
        // "unregistered tool" honest-failure path.
        let reportCapture = SubAgentReportCaptureExecutor()
        let toolsList = SubAgentToolsListExecutor(tools: tools)
        var executors: [String: any IOSToolExecutor] = [
            SUBAGENT_REPORT_TOOL_NAME_swift: reportCapture,
            "tools_list": toolsList
        ]
        for name in tools {
            if name != "tools_list", let parent = parentToolExecutors[name] {
                executors[name] = parent
            }
        }

        // Build the prompt: role system prompt + objective + tool scope.
        let systemPrompt = """
        \(role.systemPrompt)

        You are subagent \(role.name). Work toward the objective using only the allowed tools.
        When done, call `subagent_report` with a concise summary and findings.
        Do not ask the user follow-up questions.
        """
        let userPrompt = """
        Objective:
        \(objective)

        Allowed tools: \(tools.joined(separator: ", "))
        """
        let messages = [
            UIMessage.companion.system(prompt: systemPrompt),
            UIMessage.companion.user(prompt: userPrompt)
        ]

        let fallbackMaxTokens = KotlinInt(value: Int32(role.outputBudgetChars / 4))
        let fallbackModel = Model(
                modelId: modelId,
                displayName: modelId,
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
            model: baseParams?.model ?? fallbackModel,
            temperature: baseParams?.temperature,
            topP: baseParams?.topP,
            maxTokens: baseParams?.maxTokens ?? fallbackMaxTokens,
            tools: Self.buildSubAgentToolDeclarations(names: [SUBAGENT_REPORT_TOOL_NAME_swift] + tools),
            reasoningLevel: baseParams?.reasoningLevel ?? .off,
            customHeaders: baseParams?.customHeaders ?? [],
            customBody: baseParams?.customBody ?? []
        )

        let engine = IOSAgentToolEngine(
            provider: provider,
            executors: executors,
            configuration: .init(maxSteps: role.maxTurns + 1, honorApprovalPause: false)
        )

        // Live stream: register a model keyed by the dispatch tool call so the
        // chat detail sheet can show the subagent generating token-by-token, then
        // feed the engine's accumulating assistant text into it.
        let liveModel = await MainActor.run { SubAgentLiveModel() }
        await MainActor.run { SubAgentLiveRegistry.shared.register(toolCallId: toolCallId, liveModel) }
        let execution = await runEngine(
            engine,
            providerSetting: providerSetting,
            messages: messages,
            params: params,
            timeoutSeconds: timeoutSeconds ?? TimeInterval(role.timeoutSeconds),
            liveModel: liveModel
        )
        await MainActor.run { liveModel.finish() }

        let result: IOSAgentToolEngineResult?
        let mappedStatus: IOSAdvancedTaskStatus
        let terminalError: String?
        switch execution {
        case .result(let value):
            result = value
            if value.wasCancelled {
                mappedStatus = .cancelled
                terminalError = nil
            } else if let providerFailure = value.providerFailureMessage {
                mappedStatus = .failed
                terminalError = providerFailure
            } else if value.hitStepLimit {
                mappedStatus = .failed
                terminalError = "SubAgent reached its maximum turn budget."
            } else {
                mappedStatus = .completed
                terminalError = nil
            }
        case .timedOut:
            result = nil
            mappedStatus = .timedOut
            terminalError = "SubAgent timed out after \(timeoutSeconds ?? TimeInterval(role.timeoutSeconds)) seconds."
        case .cancelled:
            result = nil
            mappedStatus = .cancelled
            terminalError = nil
        }

        let displayText = (result?.messages ?? [])
            .filter { $0.role == MessageRole.assistant }
            .flatMap { $0.parts }
            .compactMap { $0 as? UIMessagePart.Text }
            .map { $0.text }
            .joined(separator: "\n")

        let summary: String
        if let terminalError {
            summary = terminalError
        } else if mappedStatus == .cancelled {
            summary = "SubAgent was cancelled."
        } else if let report = reportCapture.captured {
            summary = report
        } else {
            // No structured report captured — fall back to the visible transcript
            // (Android resultOrFallback behavior).
            summary = displayText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "SubAgent completed without a report or visible output."
                : String(displayText.prefix(role.outputBudgetChars))
        }

        lastTask = taskStore.updateTask(
            id: task.id,
            status: mappedStatus,
            resultSummary: String(summary.prefix(1_000)),
            logTail: "role=\(role.id)\ntools=\(tools.joined(separator: ", "))\nsteps=\(result?.stepsExecuted ?? 0)\nreport_captured=\(reportCapture.captured != nil)",
            error: terminalError ?? "",
            retryable: mappedStatus != .completed,
            cancelCapability: false,
            metadata: [
                "steps_executed": "\(result?.stepsExecuted ?? 0)",
                "report_captured": "\(reportCapture.captured != nil)",
                "hit_step_limit": "\(result?.hitStepLimit ?? false)",
                "was_cancelled": "\(result?.wasCancelled ?? (mappedStatus == .cancelled))"
            ]
        )
        lastRunResult = summary
        return Self.json([
            "ok": mappedStatus == .completed,
            "task_id": task.id,
            "kind": IOSAdvancedTaskKind.subAgent.rawValue,
            "role_id": role.id,
            "role_name": role.name,
            "status": mappedStatus.rawValue,
            "tool_scope": tools,
            "engine": true,
            "steps_executed": result?.stepsExecuted ?? 0,
            "report_captured": reportCapture.captured != nil,
            "summary": String(summary.prefix(2_000))
        ])
    }

    private enum EngineExecution {
        case result(IOSAgentToolEngineResult)
        case timedOut
        case cancelled
    }

    private func runEngine(
        _ engine: IOSAgentToolEngine,
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        timeoutSeconds: TimeInterval,
        liveModel: SubAgentLiveModel
    ) async -> EngineExecution {
        let timeoutState = SubAgentTimeoutState()
        let input = SubAgentEngineInput(
            engine: engine,
            providerSetting: providerSetting,
            messages: messages,
            params: params
        )
        let runTask = Task { @MainActor in
            await input.engine.run(
                providerSetting: input.providerSetting,
                messages: input.messages,
                params: input.params,
                onAssistantText: { text in
                    Task { @MainActor in liveModel.ingest(text) }
                }
            )
        }
        let timeoutTask = Task { @MainActor in
            do {
                let nanoseconds = UInt64(max(0.001, timeoutSeconds) * 1_000_000_000)
                try await Task.sleep(nanoseconds: nanoseconds)
            } catch {
                return
            }
            timeoutState.didTimeout = true
            runTask.cancel()
        }
        return await withTaskCancellationHandler {
            let result = await runTask.value
            timeoutTask.cancel()
            if timeoutState.didTimeout {
                return .timedOut
            }
            return result.wasCancelled ? .cancelled : .result(result)
        } onCancel: {
            runTask.cancel()
            timeoutTask.cancel()
        }
    }

    /// The KMP `subagent_report` tool name as visible in Swift.
    private var SUBAGENT_REPORT_TOOL_NAME_swift: String { "subagent_report" }

    /// Build tool declarations for a subagent the SAME way the main chat does:
    /// `search_web` / `scrape_web` need their dedicated factories (the generic
    /// `iosToolDeclarations` emits an incomplete schema the provider rejects with a
    /// 500). Everything else (workspace / wm_ / file_read / report / tools_list) goes
    /// through the generic declarations.
    private static func buildSubAgentToolDeclarations(names: [String]) -> [Tool] {
        var declarations: [Tool] = []
        var genericNames: [String] = []
        for name in names {
            switch name {
            case "search_web": declarations.append(ToolKt.createSearchWebToolDeclaration())
            case "scrape_web": declarations.append(ToolKt.createScrapeWebToolDeclaration())
            default: genericNames.append(name)
            }
        }
        if !genericNames.isEmpty {
            declarations.append(contentsOf: ToolKt.iosToolDeclarations(names: genericNames))
        }
        return declarations
    }

    private static func uniqueTools(_ names: [String]) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for name in names where seen.insert(name).inserted {
            result.append(name)
        }
        return result
    }

    func run(objective: String, roleId: String = "explorer", requestedToolScope: [String] = []) async -> String {
        guard let m = ensureManager() else {
            return "SubAgent 不可用：无法构造 Manager（文档目录不可用）。"
        }
        let role = IOSSubAgentRoleCatalog.resolve(roleId: roleId)
        let scopedTools = requestedToolScope.isEmpty
            ? role.toolAllowlist
            : requestedToolScope.filter { role.toolAllowlist.contains($0) }
        let tools = scopedTools.isEmpty ? role.toolAllowlist : scopedTools
        let providerMode = SettingsStore().currentApiKey.isEmpty ? "stub_fallback" : "real_provider"
        let task = taskStore.startTask(
            kind: .subAgent,
            title: "\(role.name) · \(objective.prefix(36))",
            objective: objective,
            roleId: role.id,
            toolScope: tools,
            budgetSummary: "turns \(role.maxTurns) · timeout \(role.timeoutSeconds)s · output \(role.outputBudgetChars) chars",
            sourceToolName: "subagent_dispatch",
            metadata: [
                "provider_mode": providerMode,
                "role_name": role.name
            ]
        )
        currentTaskId = task.id
        lastTask = task
        isRunning = true
        defer { isRunning = false }

        let input = IosSubAgentFactory.shared.startInput(
            objective: objective,
            subagentId: role.id,
            outputFormat: "返回针对 objective 的最终 Markdown 回答。",
            toolsAndSources: tools.joined(separator: ", "),
            boundaries: "只使用列出的工具范围。不要请求用户输入，不要伪造真实工具结果。\(role.systemPrompt)",
            context: "iOS SubAgent task. Role: \(role.name). Routing: \(role.routing). Provider mode: \(providerMode)."
        )

        // start
        let startResult: (runId: String, status: String) = await withCheckedContinuation { cont in
            m.start(parentConversationId: KotlinUuid.companion.random(), input: input, parentTools: []) { result, error in
                if let error {
                    cont.resume(returning: ("(unknown)", "error: \(error.localizedDescription)"))
                } else if let result {
                    cont.resume(returning: (
                        IosSubAgentFactory.shared.extractRunId(result: result),
                        IosSubAgentFactory.shared.extractStatus(result: result)
                    ))
                } else {
                    cont.resume(returning: ("(unknown)", "empty"))
                }
            }
        }
        guard startResult.runId != "(unknown)" else {
            let message = "SubAgent 启动失败：\(startResult.status)"
            lastTask = taskStore.updateTask(
                id: task.id,
                status: .failed,
                resultSummary: message,
                error: startResult.status,
                retryable: true,
                cancelCapability: false
            )
            return message
        }
        currentRunId = startResult.runId
        lastTask = taskStore.updateTask(
            id: task.id,
            metadata: ["kmp_run_id": startResult.runId, "start_status": startResult.status]
        )

        // wait (15s budget, matches runTestCycle)
        let waitStatus: String = await withCheckedContinuation { cont in
            m.wait(runId: startResult.runId, waitTimeoutMs: 15_000) { waitResult, waitError in
                if let waitError {
                    cont.resume(returning: "wait 错误: \(waitError.localizedDescription)")
                } else if let waitResult {
                    cont.resume(returning: IosSubAgentFactory.shared.extractStatus(result: waitResult))
                } else {
                    cont.resume(returning: "wait 空结果")
                }
            }
        }

        // read final status (the honest result we expose as tool output)
        let finalStatus: String = await withCheckedContinuation { cont in
            m.read(runId: startResult.runId) { readResult, readError in
                if let readError {
                    cont.resume(returning: "read 错误: \(readError.localizedDescription)")
                } else if let readResult {
                    cont.resume(returning: IosSubAgentFactory.shared.extractStatus(result: readResult))
                } else {
                    cont.resume(returning: "read 空结果")
                }
            }
        }
        let mappedStatus = mapStatus(finalStatus)
        let summary = "SubAgent \(role.name) 已执行。runId: \(startResult.runId)，start: \(startResult.status)，wait: \(waitStatus)，final: \(finalStatus)。"
        lastTask = taskStore.updateTask(
            id: task.id,
            status: mappedStatus,
            resultSummary: summary,
            logTail: "role=\(role.id)\ntools=\(tools.joined(separator: ", "))\nobjective=\(objective)",
            error: mappedStatus == .failed ? finalStatus : "",
            retryable: mappedStatus != .completed,
            cancelCapability: false,
            metadata: ["final_status": finalStatus]
        )
        lastRunResult = summary
        return Self.json([
            "ok": mappedStatus == .completed,
            "task_id": task.id,
            "kind": IOSAdvancedTaskKind.subAgent.rawValue,
            "role_id": role.id,
            "role_name": role.name,
            "run_id": startResult.runId,
            "status": mappedStatus.rawValue,
            "tool_scope": tools,
            "summary": summary
        ])
    }

    func cancelCurrentRun() {
        guard let m = ensureManager(), let runId = currentRunId else {
            lastRunResult = "没有可取消的 SubAgent runId"
            return
        }
        m.cancel(runId: runId) { [weak self] result, error in
            let summary: String
            if let error {
                summary = "cancel 错误: \(error.localizedDescription)"
            } else if let result {
                let status = IosSubAgentFactory.shared.extractStatus(result: result)
                summary = "cancel 已返回\nrunId: \(runId)\nstatus: \(status)"
            } else {
                summary = "cancel 返回空结果"
            }
            DispatchQueue.main.async {
                self?.lastRunResult = summary
                if let taskId = self?.currentTaskId {
                    self?.lastTask = self?.taskStore.updateTask(
                        id: taskId,
                        status: .cancelled,
                        resultSummary: summary,
                        retryable: true,
                        cancelCapability: false
                    )
                }
            }
        }
    }

    private func mapStatus(_ status: String) -> IOSAdvancedTaskStatus {
        let normalized = status.lowercased()
        if normalized.contains("completed") { return .completed }
        if normalized.contains("cancel") { return .cancelled }
        if normalized.contains("timed") || normalized.contains("timeout") { return .timedOut }
        if normalized.contains("interrupt") { return .interrupted }
        if normalized.contains("error") || normalized.contains("fail") { return .failed }
        if normalized.contains("running") { return .running }
        return .completed
    }

    static func json(_ object: [String: Any]) -> String {
        subAgentJSON(object)
    }
}

@MainActor
private final class SubAgentTimeoutState {
    var didTimeout = false
}

/// KMP message/config objects are immutable for one engine invocation but are
/// not imported as Swift `Sendable`. The child task owns this box for the run;
/// no field is read again by the parent while the engine is executing.
private final class SubAgentEngineInput: @unchecked Sendable {
    let engine: IOSAgentToolEngine
    let providerSetting: ProviderSetting
    let messages: [UIMessage]
    let params: TextGenerationParams

    init(
        engine: IOSAgentToolEngine,
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) {
        self.engine = engine
        self.providerSetting = providerSetting
        self.messages = messages
        self.params = params
    }
}

/// Engine executor for the `subagent_report` tool. Captures the model's
/// structured report payload (summary/findings/evidence/risks/...) so the
/// SubAgent runner can surface it as the tool result, mirroring Android's
/// KMP `SubAgentReportCapture`. The captured value is the raw arguments JSON.
final class SubAgentReportCaptureExecutor: IOSToolExecutor {
    /// The last `subagent_report` arguments the model emitted, or nil if it
    /// never called the tool (the runner falls back to visible text).
    private(set) var captured: String?

    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        let trimmed = arguments.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return .failed("subagent_report requires a non-empty summary or findings.")
        }
        captured = trimmed
        return .filled("{\"status\":\"ok\",\"message\":\"Structured subagent report recorded.\"}")
    }
}

final class SubAgentToolsListExecutor: IOSToolExecutor {
    private let tools: [String]

    init(tools: [String]) {
        self.tools = tools
    }

    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        let items = tools.map { tool in
            [
                "name": tool,
                "description": Self.description(for: tool)
            ]
        }
        return .filled(subAgentJSON([
            "ok": true,
            "tools": items
        ]))
    }

    private static func description(for tool: String) -> String {
        switch tool {
        case "tools_list": "List tools available in this sub-agent run."
        case "search_web": "Search the public web through configured iOS search providers."
        case "scrape_web": "Extract readable text from a public http/https URL."
        case "file_read_selected": "Read the user's currently selected foreground file preview."
        case "permissions_status": "Read iOS capability and permission status."
        case "workspace_file_read": "Read an imported Workspace file."
        case "workspace_file_list": "List imported Workspace files."
        case "workspace_file_search": "Search imported Workspace file previews."
        case "workspace_artifact_read": "Read a saved Workspace artifact."
        case "wm_stations": "List configured WebMount stations."
        case "wm_state": "Read current WebMount page state."
        case "wm_extract": "Extract readable or interactive WebMount page content."
        case "wm_get": "Read one safe WebMount element value/text/attribute."
        case "wm_find": "Find text or selector matches on the current WebMount page."
        case "wm_wait": "Wait briefly for WebMount page activity."
        case "wm_back": "Navigate WebMount backward."
        case "wm_forward": "Navigate WebMount forward."
        default: "Available iOS sub-agent tool."
        }
    }
}
