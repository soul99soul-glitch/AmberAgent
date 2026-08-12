import Foundation

// MARK: - IOSEvolutionMetrics (Phase 4 Wave 1; §19 观测指标 / §18.2 隐私)
//
// 记录为主的可解释率观测 store，不充当 dashboard。覆盖 §19 需要的原始事件
// 计数，全部以「计数 / 时间戳 / 枚举键」形式持久化：
//
//   - evidence → no-op / candidate 各结局（evidenceNoOp/diagnosisNoOp/
//     buildNoOp/draftDowngrade/candidateStaticFail/candidateEvalFail/
//     userReject/userApprove/autoPublish/publishFailed/workflowFailed）；
//   - protected regression 拦截次数（protectedRegressionBlocked）与
//     policy engine 门禁拒绝分布（gateDenialsByReason，带原因键）；
//   - promotion 后回退：promotions/rollbacks 时间戳序列，7/30 天窗口
//     rollback 率按时间戳现算（`IOSEvolutionMetricsSnapshot.rollbackRate`）；
//     rollback 区分用户主动 / 熔断建议（§13.4 熔断语义）；
//   - recipe step 失败分布（按 ToolId）与权限拒绝分布（按 ToolId）——
//     埋点已接进 ChatToolRuntime：recipe 路由 step 失败的两个 catch 与审批
//     拒绝漏斗（recordApprovalDeniedInLedger），经注入的 metrics 实例记录
//     （默认 `.shared`，测试注入隔离实例）；
//   - stale CAS 发生数（recipe_import apply / 发布 apply 的 fail-closed 路径）；
//   - catalog revision 与执行 lease 不一致哨兵（catalogLeaseInconsistency，
//     正常路径应恒为 0，见 IOSDynamicToolRegistry.refresh 的断言点）。
//
// §18.2 隐私：本 store 只记录计数/时间戳/枚举键/制品名与原因键，绝不记录
// 消息正文、工具输出或任何用户内容（隐私回归测试断言序列化文档不含消息
// 正文标记）。
//
// 存储：`<base>/evolution/metrics.json`，与 receipt store / policy state
// store 同一 base-directory 约定（§18.1）。全部读写经静态 NSLock 串行化，
// 坏文件降级为空态（fail-safe：损坏的 metrics 不阻塞任何演化动作）。

// MARK: - Event keys (§19)

extension IOSEvolutionMetrics {
    enum EventKey: String, Codable, CaseIterable, Sendable {
        // evidence → outcome 路由（§19 前两项）
        case evidenceNoOp      // 无可归因失败证据 → no-op（I-3）
        case diagnosisNoOp     // 诊断 no-op
        case buildNoOp         // 候选生成 no-op
        case draftDowngrade    // 候选降级为人工草稿（§15 停止条件）
        case candidateStaticFail // 候选未通过静态校验
        case candidateEvalFail // 评测失败（哈希失配 / 预算耗尽 / 报告建议不 promote）
        case candidateNeverAutoRefused // 分类为「永不自动」被拒
        case userReject        // 用户拒绝候选
        case userApprove       // 用户批准发布
        case autoPublish       // T0/T1 自动发布
        case workflowFailed    // 流程失败（诊断/构建失败等发布前终止）
        case publishFailed     // 发布失败（零写入）
        // 安全闸门（§19）
        case protectedRegressionBlocked // 受保护成功样例回归 → 拦截晋升
        case staleCASSkipped   // base/candidate CAS 失配 → fail-closed 零写入
        case circuitBreakerTripped // 同一制品连续回退触发熔断（§13.4）
        case rollbackUserInitiated     // 用户主动回退
        case rollbackBreakerSuggested  // 熔断状态下的回退（系统已建议回退）
        // 哨兵（§19：应恒 0）
        case catalogLeaseInconsistency // catalog revision ↔ 执行 lease 不一致
    }

    enum PromotionSource: String, Codable, Equatable, Sendable {
        case auto
        case manual
    }

    enum RollbackSource: String, Codable, Equatable, Sendable {
        case userInitiated
        case circuitBreakerSuggested
    }

    struct PromotionRecord: Codable, Equatable, Sendable {
        let artifactId: String
        let source: PromotionSource
        let atEpochMs: Int64
    }

    struct RollbackRecord: Codable, Equatable, Sendable {
        let artifactId: String
        let source: RollbackSource
        let atEpochMs: Int64
    }
}

/// retention 裁剪的最小抽象（见 `IOSEvolutionMetrics.trimmedRecords`）。
private protocol IOSEvolutionTimestampedRecord {
    var atEpochMs: Int64 { get }
}

extension IOSEvolutionMetrics.PromotionRecord: IOSEvolutionTimestampedRecord {}
extension IOSEvolutionMetrics.RollbackRecord: IOSEvolutionTimestampedRecord {}

// MARK: - Snapshot (read-only query API, 供后续 UI / widening gate 使用)

/// 只读观测快照。UI / `IOSPromotionPolicyEngine.wideningGate` 只消费本类型，
/// 不做原地修改；时间窗口指标（7/30 天 rollback 率）按时间戳现算。
struct IOSEvolutionMetricsSnapshot: Codable, Equatable, Sendable {
    var schemaVersion: Int = 1
    /// EventKey.rawValue → 次数。
    var counters: [String: Int] = [:]
    /// policy engine 门禁拒绝原因键（如 "kill_switch"、"cooldown"）→ 次数。
    var gateDenialsByReason: [String: Int] = [:]
    /// §19 recipe step 失败分布：ToolId → 次数（API 已就绪，埋点在 runner 层）。
    var recipeStepFailuresByTool: [String: Int] = [:]
    /// §19 权限拒绝分布：ToolId → 次数（API 已就绪，埋点在 approval 层）。
    var permissionDenialsByTool: [String: Int] = [:]
    /// 每次自动/人工晋升的时间戳序列（§19：供 rollback 率与熔断分析）。
    var promotions: [IOSEvolutionMetrics.PromotionRecord] = []
    /// 每次回退的时间戳序列（区分用户主动/熔断建议）。
    var rollbacks: [IOSEvolutionMetrics.RollbackRecord] = []

    // MARK: 查询 API

    func count(_ event: IOSEvolutionMetrics.EventKey) -> Int {
        switch event {
        // rollback 事件以时间戳序列为唯一事实源（区分来源），这里派生计数，
        // 避免计数与序列两处漂移。
        case .rollbackUserInitiated:
            return rollbacks.filter { $0.source == .userInitiated }.count
        case .rollbackBreakerSuggested:
            return rollbacks.filter { $0.source == .circuitBreakerSuggested }.count
        default:
            return counters[event.rawValue] ?? 0
        }
    }

    func gateDenialCount(reasonKey: String) -> Int {
        gateDenialsByReason[reasonKey] ?? 0
    }

    var promotionCount: Int { promotions.count }
    var autoPromotionCount: Int {
        promotions.filter { $0.source == .auto }.count
    }
    var manualPromotionCount: Int {
        promotions.filter { $0.source == .manual }.count
    }
    var rollbackCount: Int { rollbacks.count }

    /// §19 promotion 后回退率：窗口（7/30 天）内 rollbacks / promotions。
    /// 窗口内无 promotion → nil（「没有观察」不能当作 0——0 会掩盖无数据）。
    func rollbackRate(days: Int, now: Date = Date()) -> Double? {
        let windowStartEpochMs = Int64(now.timeIntervalSince1970 * 1000)
            - Int64(days) * 24 * 60 * 60 * 1000
        let windowPromotions = promotions.filter { $0.atEpochMs >= windowStartEpochMs }
        guard !windowPromotions.isEmpty else { return nil }
        let windowRollbacks = rollbacks.filter { $0.atEpochMs >= windowStartEpochMs }
        return Double(windowRollbacks.count) / Double(windowPromotions.count)
    }

    /// 用户拒绝率 = userReject / (userReject + userApprove + autoPublish)。
    /// 无用户可见决策 → nil。
    var userRejectionRate: Double? {
        let decisions = count(.userReject) + count(.userApprove) + count(.autoPublish)
        guard decisions > 0 else { return nil }
        return Double(count(.userReject)) / Double(decisions)
    }

    var isEmpty: Bool {
        counters.isEmpty && gateDenialsByReason.isEmpty
            && recipeStepFailuresByTool.isEmpty && permissionDenialsByTool.isEmpty
            && promotions.isEmpty && rollbacks.isEmpty
    }
}

// MARK: - Store

/// 计数/事件 store。全部变异经静态锁串行化并原子写盘；坏文件降级为空态。
/// 每个 record 一次事件 = 一次读改写 + 原子写（事件频率极低，不需要批量缓冲）。
final class IOSEvolutionMetrics: @unchecked Sendable {
    /// 生产单例：与 receipt store / policy state store 同一 documents
    /// base-directory 约定。
    static let shared: IOSEvolutionMetrics = {
        let baseDirectory = (try? FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? FileManager.default.temporaryDirectory
        return IOSEvolutionMetrics(baseDirectory: baseDirectory)
    }()

    private static let mutationLock = NSLock()

    private let baseDirectory: URL
    private let fileManager: FileManager

    init(baseDirectory: URL, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.baseDirectory = baseDirectory
    }

    private var fileURL: URL {
        baseDirectory
            .appendingPathComponent("evolution", isDirectory: true)
            .appendingPathComponent("metrics.json")
    }

    // MARK: Read

    func snapshot() -> IOSEvolutionMetricsSnapshot {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        return read()
    }

    // MARK: Record（每个埋点一行调用）

    func record(_ event: EventKey) {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        var snapshot = read()
        snapshot.counters[event.rawValue, default: 0] += 1
        write(snapshot)
    }

    /// policy engine 门禁拒绝，带原因键（§13.4 各硬门禁的稳定键）。
    func recordGateDenial(reasonKey: String) {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        var snapshot = read()
        snapshot.gateDenialsByReason[reasonKey, default: 0] += 1
        write(snapshot)
    }

    /// §19 recipe step 失败分布（按 ToolId 计数）。runner 层埋点接线后调用。
    func recordRecipeStepFailure(toolId: String) {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        var snapshot = read()
        snapshot.recipeStepFailuresByTool[toolId, default: 0] += 1
        write(snapshot)
    }

    /// §19 权限拒绝分布（按 ToolId 计数）。approval 层埋点接线后调用。
    func recordPermissionDenied(toolId: String) {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        var snapshot = read()
        snapshot.permissionDenialsByTool[toolId, default: 0] += 1
        write(snapshot)
    }

    /// 记录一次晋升（自动/人工）的时间戳序列。
    func recordPromotion(
        artifactId: String,
        source: PromotionSource,
        atEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        var snapshot = read()
        snapshot.promotions.append(PromotionRecord(artifactId: artifactId, source: source, atEpochMs: atEpochMs))
        snapshot.promotions = Self.trimmedRecords(snapshot.promotions)
        write(snapshot)
    }

    /// 记录一次回退，区分用户主动 / 熔断建议（§13.4）。
    func recordRollback(
        artifactId: String,
        source: RollbackSource,
        atEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        var snapshot = read()
        snapshot.rollbacks.append(RollbackRecord(artifactId: artifactId, source: source, atEpochMs: atEpochMs))
        snapshot.rollbacks = Self.trimmedRecords(snapshot.rollbacks)
        write(snapshot)
    }

    // MARK: Private

    /// 时间戳序列供任意天数窗口的 rollback 率查询（不止 30 天），不能按时间
    /// retention 裁剪；只加条数硬上限防止 metrics.json 无界增长（保留最新）。
    private static let recordHardCap = 200

    private static func trimmedRecords<Record: IOSEvolutionTimestampedRecord>(
        _ records: [Record]
    ) -> [Record] {
        records.count > recordHardCap ? Array(records.suffix(recordHardCap)) : records
    }

    private func read() -> IOSEvolutionMetricsSnapshot {
        guard let data = try? Data(contentsOf: fileURL),
              let snapshot = try? JSONDecoder().decode(IOSEvolutionMetricsSnapshot.self, from: data) else {
            return IOSEvolutionMetricsSnapshot()
        }
        return snapshot
    }

    private func write(_ snapshot: IOSEvolutionMetricsSnapshot) {
        do {
            try fileManager.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try encoder.encode(snapshot).write(to: fileURL, options: .atomic)
        } catch {
            // Best-effort：观测记录写失败不得影响演化动作本身（§18.3 预算精神：
            // 观测是辅助，不是 gate）。
            print("[AmberChat] evolution metrics write failed: \(error)")
        }
    }
}
