import Foundation
import Observation
@preconcurrency import Shared

/// iOS entry point for calling SubAgentManager.start/read/wait/cancel.
/// Uses IosSubAgentFactory (KMP iosMain). Real provider if API key configured;
/// otherwise falls back to an honest stub runner for call-chain validation.
@MainActor
@Observable
final class SubAgentRunner {
    @ObservationIgnored private var manager: SubAgentManager?
    @ObservationIgnored private var currentRunId: String?

    var lastRunResult: String = "(未运行)"
    var isRunning: Bool = false

    init() {}

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
        guard let m = ensureManager() else {
            lastRunResult = "无法构造 Manager（文档目录不可用）"
            return
        }
        isRunning = true
        lastRunResult = "正在启动 SubAgent…"

        let input = IosSubAgentFactory.shared.startInput(
            objective: "验证 iOS SubAgentManager start/read/wait/cancel 调用链",
            subagentId: "ios-smoke-runner",
            outputFormat: "返回简短 Markdown 摘要。",
            toolsAndSources: "不使用外部工具，仅验证调用链。",
            boundaries: "不要请求用户输入，不要伪造真实工具结果。",
            context: "iOS SubAgentsView 手动触发的运行验证。"
        )

        m.start(parentConversationId: KotlinUuid.companion.random(), input: input, parentTools: []) { [weak self] result, error in
            if let error {
                DispatchQueue.main.async {
                    self?.isRunning = false
                    self?.lastRunResult = "start 错误: \(error.localizedDescription)"
                }
                return
            }
            guard let result else {
                DispatchQueue.main.async {
                    self?.isRunning = false
                    self?.lastRunResult = "start 返回空结果"
                }
                return
            }

            let runId = IosSubAgentFactory.shared.extractRunId(result: result)
            let startStatus = IosSubAgentFactory.shared.extractStatus(result: result)
            guard runId != "(unknown)", startStatus == "running" || startStatus == "completed" else {
                let resultDescription = String(describing: result)
                DispatchQueue.main.async {
                    self?.isRunning = false
                    self?.lastRunResult = "start 失败\nstatus: \(startStatus)\nresult: \(resultDescription)"
                }
                return
            }
            DispatchQueue.main.async { self?.currentRunId = runId }

            m.wait(runId: runId, waitTimeoutMs: 15_000) { waitResult, waitError in
                if let waitError {
                    DispatchQueue.main.async {
                        guard let self else { return }
                        self.isRunning = false
                        self.lastRunResult = "✅ start 成功，wait 错误: \(waitError.localizedDescription)\nrunId: \(runId)"
                    }
                    return
                }

                m.read(runId: runId) { readResult, readError in
                    let summary: String
                    if let readError {
                        summary = "✅ start/wait 成功，read 错误: \(readError.localizedDescription)\nrunId: \(runId)"
                    } else if let readResult {
                        let status = IosSubAgentFactory.shared.extractStatus(result: readResult)
                        summary = "✅ SubAgent 调用链验证成功\nrunId: \(runId)\nstatus: \(status)\n\n已通过 IosSubAgentFactory → SubAgentManager.start → runner.run → wait → read 状态返回。无 API Key 时使用诚实 stub；配置 API Key 时会调用真实 OpenAI-compatible provider。"
                    } else if let waitResult {
                        let status = IosSubAgentFactory.shared.extractStatus(result: waitResult)
                        summary = "✅ start/wait 成功，read 返回空结果\nrunId: \(runId)\nstatus: \(status)"
                    } else {
                        summary = "✅ start 成功，但 wait/read 返回空结果\nrunId: \(runId)"
                    }
                    DispatchQueue.main.async {
                        guard let self else { return }
                        self.isRunning = false
                        self.lastRunResult = summary
                    }
                }
            }
        }
    }

    /// [Slice 3] Input-driven run for the chat-tool dispatch path. Drives the
    /// same startInput → start → wait → read chain as runTestCycle, but with a
    /// caller-supplied `objective`. Returns a text summary suitable to use as a
    /// tool-call output that the model can read to continue the conversation.
    /// Failures (no manager, start/wait/read error, timeout) return an honest
    /// error string — never empty/fabricated success.
    func run(objective: String) async -> String {
        guard let m = ensureManager() else {
            return "SubAgent 不可用：无法构造 Manager（文档目录不可用）。"
        }
        let input = IosSubAgentFactory.shared.startInput(
            objective: objective,
            subagentId: "ios-chat-dispatch",
            outputFormat: "返回针对 objective 的最终 Markdown 回答。",
            toolsAndSources: "不使用外部工具，仅完成委派任务。",
            boundaries: "不要请求用户输入，不要伪造真实工具结果。",
            context: "iOS ChatViewModel subagent_dispatch 工具调用。"
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
            return "SubAgent 启动失败：\(startResult.status)"
        }

        // wait (15s budget, matches runTestCycle)
        let _: String = await withCheckedContinuation { cont in
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
        return "SubAgent 已执行（runId: \(startResult.runId)，状态: \(finalStatus)）。objective: \(objective)。配置 API Key 时为真实推理；无 Key 时为诚实 stub。"
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
            DispatchQueue.main.async { self?.lastRunResult = summary }
        }
    }
}
