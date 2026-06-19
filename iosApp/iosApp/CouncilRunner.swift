import Foundation
import Observation
@preconcurrency import Shared

/// iOS entry point for calling ModelCouncilManager.start() — proves the Council
/// execution chain is wired end-to-end on iOS.
///
/// Uses IosCouncilFactory (KMP iosMain) which constructs ModelCouncilManager with
/// stub adapters (no real model inference). The stub model runner returns
/// placeholder text — this verifies the start call chain works, NOT that real
/// council reasoning happens.
@MainActor
@Observable
final class CouncilRunner {
    @ObservationIgnored private var manager: ModelCouncilManager?

    var lastRunResult: String = "(未运行)"
    var isRunning: Bool = false

    init() {}

    private func ensureManager() -> ModelCouncilManager? {
        if let m = manager { return m }
        guard let docsDir = try? FileManager.default.url(
            for: .documentDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        ).path else { return nil }

        // Try real provider first (needs valid API key from SettingsStore).
        // If no key configured, fall back to stub for chain validation.
        let store = SettingsStore()
        let baseUrl = store.baseUrl
        let apiKey = store.currentApiKey
        let modelId = store.modelId

        let m: ModelCouncilManager
        if !apiKey.isEmpty {
            m = IosCouncilFactory.shared.createWithRealProvider(
                documentsDir: docsDir,
                baseUrl: baseUrl,
                apiKey: apiKey,
                modelId: modelId
            )
        } else {
            m = IosCouncilFactory.shared.create(documentsDir: docsDir)
        }
        manager = m
        return m
    }

    /// Run a minimal council start cycle to prove the call chain works.
    /// [Slice 3] Input-driven run for the chat-tool dispatch path. Drives
    /// startInput → m.start with a caller-supplied `objective`. Returns a text
    /// summary usable as a tool-call output. Failures return an honest error
    /// string — never empty/fabricated success.
    func run(objective: String) async -> String {
        guard let m = ensureManager() else {
            return "模型议会暂不可用：无法准备运行环境。"
        }
        let input = IosCouncilFactory.shared.startInput(objective: objective)

        let outcome: (runId: String, status: String) = await withCheckedContinuation { cont in
            m.start(input: input) { result, error in
                if let error = error {
                    cont.resume(returning: ("(unknown)", "error: \(error.localizedDescription)"))
                } else if let result = result {
                    cont.resume(returning: (
                        IosCouncilFactory.shared.extractRunId(result: result),
                        IosCouncilFactory.shared.extractStatus(result: result)
                    ))
                } else {
                    cont.resume(returning: ("(unknown)", "empty"))
                }
            }
        }
        return "模型议会已执行（runId: \(outcome.runId)，状态: \(outcome.status)）。任务：\(objective)。配置 API Key 后会使用真实模型生成。"
    }

    func runTestCycle() {
        guard let m = ensureManager() else {
            lastRunResult = "无法构造 Manager（文档目录不可用）"
            return
        }
        isRunning = true
        lastRunResult = "正在启动…"

        let input = IosCouncilFactory.shared.startInput(objective: "试运行模型议会")

        m.start(input: input) { [weak self] result, error in
            let summary: String
            if let error = error {
                summary = "start 错误: \(error.localizedDescription)"
            } else if let result = result {
                let runId = IosCouncilFactory.shared.extractRunId(result: result)
                let status = IosCouncilFactory.shared.extractStatus(result: result)
                summary = "模型议会已完成试运行\nrunId: \(runId)\nstatus: \(status)\n\n配置 API Key 后会使用真实模型生成。"
            } else {
                summary = "start 返回空结果"
            }
            DispatchQueue.main.async {
                guard let self else { return }
                self.isRunning = false
                self.lastRunResult = summary
            }
        }
    }
}
