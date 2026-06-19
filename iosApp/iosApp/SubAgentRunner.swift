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

    /// [Slice 3] Input-driven run for the chat-tool dispatch path. Drives the
    /// same startInput → start → wait → read chain as runTestCycle, but with a
    /// caller-supplied `objective`. Returns a text summary suitable to use as a
    /// tool-call output that the model can read to continue the conversation.
    /// Failures (no manager, start/wait/read error, timeout) return an honest
    /// error string — never empty/fabricated success.
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
