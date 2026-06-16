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
    func runTestCycle() {
        guard let m = ensureManager() else {
            lastRunResult = "无法构造 Manager（文档目录不可用）"
            return
        }
        isRunning = true
        lastRunResult = "正在启动…"

        let input = IosCouncilFactory.shared.startInput(objective: "验证 iOS ModelCouncilManager 调用链")

        m.start(input: input) { [weak self] result, error in
            let summary: String
            if let error = error {
                summary = "start 错误: \(error.localizedDescription)"
            } else if let result = result {
                let runId = IosCouncilFactory.shared.extractRunId(result: result)
                let status = IosCouncilFactory.shared.extractStatus(result: result)
                summary = "✅ start 调用链验证成功\nrunId: \(runId)\nstatus: \(status)\n\n注意：这是 stub runner，不产生真实议会推理。ModelCouncilManager 已在 iOS 端成功构造并执行了 start()。"
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
