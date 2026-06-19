import Foundation
import Observation
@preconcurrency import Shared

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
            toolAllowlist: ["tools_list", "search_web", "scrape_web", "file_read_selected", "permissions_status"],
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
            toolAllowlist: ["tools_list", "file_read_selected", "permissions_status"],
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
            toolAllowlist: ["tools_list", "file_read_selected"],
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
            toolAllowlist: ["tools_list", "file_read_selected"],
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
        providerSetting: ProviderSetting.OpenAI,
        modelId: String,
        parentToolExecutors: [String: any IOSToolExecutor]
    ) async -> String {
        let role = IOSSubAgentRoleCatalog.resolve(roleId: roleId)
        let scopedTools = requestedToolScope.isEmpty
            ? role.toolAllowlist
            : requestedToolScope.filter { role.toolAllowlist.contains($0) }
        let tools = scopedTools.isEmpty ? role.toolAllowlist : scopedTools

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
        var executors: [String: any IOSToolExecutor] = [
            SUBAGENT_REPORT_TOOL_NAME_swift: reportCapture
        ]
        for name in tools {
            if let parent = parentToolExecutors[name] {
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

        let params = TextGenerationParams(
            model: Model(
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
            ),
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: KotlinInt(value: Int32(role.outputBudgetChars / 4)),
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )

        let engine = IOSAgentToolEngine(
            provider: OpenAIKmpProviderAdapter(),
            executors: executors,
            configuration: .init(maxSteps: role.maxTurns + 1, honorApprovalPause: false)
        )

        let result = await engine.run(
            providerSetting: providerSetting,
            messages: messages,
            params: params
        )

        let displayText = result.messages
            .filter { $0.role == MessageRole.assistant }
            .flatMap { $0.parts }
            .compactMap { $0 as? UIMessagePart.Text }
            .map { $0.text }
            .joined(separator: "\n")

        let summary: String
        if let report = reportCapture.captured {
            summary = report
        } else {
            // No structured report captured — fall back to the visible transcript
            // (Android resultOrFallback behavior).
            summary = displayText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "SubAgent completed without a report or visible output."
                : String(displayText.prefix(role.outputBudgetChars))
        }

        let mappedStatus: IOSAdvancedTaskStatus = result.hitStepLimit ? .timedOut : .completed
        lastTask = taskStore.updateTask(
            id: task.id,
            status: mappedStatus,
            resultSummary: String(summary.prefix(1_000)),
            logTail: "role=\(role.id)\ntools=\(tools.joined(separator: ", "))\nsteps=\(result.stepsExecuted)\nreport_captured=\(reportCapture.captured != nil)",
            retryable: mappedStatus != .completed,
            cancelCapability: false,
            metadata: [
                "steps_executed": "\(result.stepsExecuted)",
                "report_captured": "\(reportCapture.captured != nil)",
                "hit_step_limit": "\(result.hitStepLimit)"
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
            "steps_executed": result.stepsExecuted,
            "report_captured": reportCapture.captured != nil,
            "summary": String(summary.prefix(2_000))
        ])
    }

    /// The KMP `subagent_report` tool name as visible in Swift.
    private var SUBAGENT_REPORT_TOOL_NAME_swift: String { "subagent_report" }
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

    private static func json(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return String(describing: object)
        }
        return text
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
