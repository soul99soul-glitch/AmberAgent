import Foundation
import Observation
@preconcurrency import Shared

struct IOSCouncilSeatDescriptor: Equatable, Identifiable {
    let id: String
    let name: String
    let role: String
    let modelLabel: String
}

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
    @ObservationIgnored private let taskStore: IOSAdvancedTaskStore

    var lastRunResult: String = "(未运行)"
    var isRunning: Bool = false
    var lastTask: IOSAdvancedTaskRecord?

    init(taskStore: IOSAdvancedTaskStore = .shared) {
        self.taskStore = taskStore
    }

    var recentTasks: [IOSAdvancedTaskRecord] {
        taskStore.recent(kind: .modelCouncil, limit: 5)
    }

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
    func run(
        objective: String,
        seats: [IOSCouncilSeatDescriptor] = [],
        mode: String = "compare",
        outputBudgetChars: Int = 12_000
    ) async -> String {
        guard let m = ensureManager() else {
            return "模型议会暂不可用：无法准备运行环境。"
        }
        let providerMode = SettingsStore().currentApiKey.isEmpty ? "stub_fallback" : "real_provider"
        let effectiveSeats = seats.isEmpty ? Self.defaultSeatDescriptors() : seats
        let task = taskStore.startTask(
            kind: .modelCouncil,
            title: "Council · \(objective.prefix(34))",
            objective: objective,
            toolScope: [],
            budgetSummary: "mode \(mode) · seats \(effectiveSeats.count) · output \(outputBudgetChars) chars",
            sourceToolName: "model_council_run",
            metadata: [
                "provider_mode": providerMode,
                "seat_names": effectiveSeats.map(\.name).joined(separator: ", ")
            ]
        )
        lastTask = task
        isRunning = true
        defer { isRunning = false }

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
        let mappedStatus = mapStatus(outcome.status)
        let conclusion = mappedStatus == .failed
            ? "模型议会启动失败或返回错误。"
            : "模型议会完成运行链路，席位：\(effectiveSeats.map(\.name).joined(separator: ", "))。"
        let summary = "\(conclusion) runId: \(outcome.runId)，status: \(outcome.status)，mode: \(mode)，provider: \(providerMode)。"
        lastTask = taskStore.updateTask(
            id: task.id,
            status: mappedStatus,
            resultSummary: summary,
            logTail: "objective=\(objective)\nseats=\(effectiveSeats.map { "\($0.name): \($0.role) (\($0.modelLabel))" }.joined(separator: "\n"))",
            error: mappedStatus == .failed ? outcome.status : "",
            retryable: mappedStatus != .completed,
            cancelCapability: false,
            metadata: [
                "kmp_run_id": outcome.runId,
                "final_status": outcome.status
            ]
        )
        lastRunResult = summary
        return Self.json([
            "ok": mappedStatus == .completed,
            "task_id": task.id,
            "kind": IOSAdvancedTaskKind.modelCouncil.rawValue,
            "run_id": outcome.runId,
            "status": mappedStatus.rawValue,
            "mode": mode,
            "seat_count": effectiveSeats.count,
            "budget_chars": outputBudgetChars,
            "summary": summary
        ])
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

    private func mapStatus(_ status: String) -> IOSAdvancedTaskStatus {
        let normalized = status.lowercased()
        if normalized.contains("completed") || normalized.contains("partial_failed") { return .completed }
        if normalized.contains("cancel") { return .cancelled }
        if normalized.contains("timed") || normalized.contains("timeout") { return .timedOut }
        if normalized.contains("interrupt") { return .interrupted }
        if normalized.contains("error") || normalized.contains("fail") { return .failed }
        if normalized.contains("running") { return .running }
        return normalized == "(unknown)" ? .failed : .completed
    }

    private static func defaultSeatDescriptors() -> [IOSCouncilSeatDescriptor] {
        [
            IOSCouncilSeatDescriptor(id: "host", name: "Host", role: "主持、串联、综合", modelLabel: "当前模型"),
            IOSCouncilSeatDescriptor(id: "risk", name: "Risk", role: "风险与失败模式", modelLabel: "当前模型"),
            IOSCouncilSeatDescriptor(id: "opponent", name: "Opponent", role: "反方质询", modelLabel: "当前模型")
        ]
    }

    #if DEBUG
    /// Test accessor for the default seat roster.
    static func defaultSeatDescriptorsForTesting() -> [IOSCouncilSeatDescriptor] {
        defaultSeatDescriptors()
    }
    #endif

    private static func json(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return String(describing: object)
        }
        return text
    }
}
