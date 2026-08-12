import Foundation

// MARK: - IOSPromotionPolicyEngine (Phase 2 Wave C; §13.4 / §9.6 / 不变量 7/16/17)
//
// Host-side, deterministic authorization engine. The proposer/evaluator models
// NEVER approve their own candidates (invariant 16): every autonomous
// promotion decision is made here from the immutable evaluation report plus
// persisted state (budget / cooldown / circuit breaker) and user settings
// (autonomy level + global kill switch).
//
// Tier classification (§13.4 table, mapped onto the REAL effect classes):
// - T0: Skill/Playbook 纯文本 delta（权限包络为空）；或全部 step 为 `pure` 的
//   Recipe（`networkRead` step 升入 T1）。
// - T1: 仅含 `networkRead` step，或含本地可逆副作用 step（effect class
//   `idempotent`）且权限包络不扩大的 Recipe。
// - T2: 外部副作用、破坏性操作、权限包络扩大（envelope == `sideEffect`）、
//   新 MCP server 绑定（mcpBinding）、harness patch（只进 Lab）。
// - 永不自动: 无 sealed holdout（draftOnly）、任一 deterministic/protected
//   门禁失败、预算耗尽、冷却期、该制品处于熔断期、kill switch 打开、自治
//   级别不是 T0+T1 自动。
//
// Hard gates for ANY autonomous promotion (§13.4):
//   1. report 存在且 `matches(candidateHash:)`（§9.4/§13.1：候选与报告任一
//      字节变化即失效）；
//   2. report.recommendation == .promote；
//   3. report.protectedRegressions == 0（不变量 13）；
//   4. 分类层 ∈ {T0, T1}；
//   5. 预算/冷却/熔断/kill switch/自治级别全部放行。
//
// Budget/cooldown/circuit breaker (§13.4):
// - 每日自动晋升上限（`IOSPromotionPolicyConfiguration.dailyAutoPromotionLimit`）;
// - 同一制品自动晋升后的冷却期（`cooldownAfterPromotionSeconds`）;
// - 同一制品连续两次 rollback → 自动关闭该制品自治并通知
//   （`maxConsecutiveRollbacksBeforeCircuitBreak`; 熔断状态持久化在
//   `IOSPromotionPolicyStateStore`，kill switch 全局开关消费设置项）。
//
// Automatic-authorization receipts carry `approvedBy = policy.engine:<version>`
// (invariant 7/16); 授权后立即 apply（CAS 不变）→ registry.refresh →
// 通知卡（不变量 17: 自动发布的制品必须有 receipt、用户可见通知和一键回退）。

// MARK: - Settings keys (autonomy level + kill switch)

enum IOSEvolutionPreferenceKeys {
    /// 自治级别（`IOSEvolutionAutonomyLevel` rawValue）。默认 T0+T1 自动。
    static let autonomyLevel = "app.amber.ios.evolution.autonomyLevel"
    /// 全局 kill switch：true = 一切演化发布都走人工批准（默认 false）。
    static let killSwitch = "app.amber.ios.evolution.killSwitch"
}

/// 自治级别三档（§13.4）：
/// - `.allManual` 全部人工：T0/T1 也走人工批准；
/// - `.t0T1Auto` T0+T1 自动（默认）：policy engine 硬门禁全过即自动发布 + 通知卡；
/// - `.notifyOnly` 仅通知不自动：T0/T1 也走人工卡，但附带完整自动评估结论。
enum IOSEvolutionAutonomyLevel: String, CaseIterable, Codable, Equatable, Sendable {
    case allManual
    case t0T1Auto
    case notifyOnly

    var title: String {
        switch self {
        case .allManual: "全部人工"
        case .t0T1Auto: "T0+T1 自动"
        case .notifyOnly: "仅通知不自动"
        }
    }

    var subtitle: String {
        switch self {
        case .allManual: "所有候选（含低风险）都先给你审阅"
        case .t0T1Auto: "低风险候选自动发布，并带通知与一键回退（默认）"
        case .notifyOnly: "只做评测与通知，T0/T1 也需人工批准"
        }
    }
}

// MARK: - Tier

/// §13.4 risk tiers.
enum IOSPromotionTier: String, Codable, Equatable, Sendable {
    case t0
    case t1
    case t2
    case neverAuto
}

// MARK: - Configuration (budget / cooldown / breaker constants)

struct IOSPromotionPolicyConfiguration: Equatable, Sendable {
    /// 每日自动晋升上限（§13.4 预算）。3 次/天：既有 T0/T1 候选极少，3 次足够
    /// 覆盖一整天的真实修复，同时把“自动换版”限制在用户可审计的频率内。
    var dailyAutoPromotionLimit: Int = 3
    /// 同一制品自动晋升后的冷却期（§13.4）。12 小时：一次发布后留半天观察窗口，
    /// 若效果变差用户回退，熔断计数随之生效。
    var cooldownAfterPromotionSeconds: TimeInterval = 12 * 60 * 60
    /// 同一制品回退后的冷却期。24 小时：回退是强烈负信号，冷却比晋升更长。
    var cooldownAfterRollbackSeconds: TimeInterval = 24 * 60 * 60
    /// 同一制品连续 rollback 达到该次数 → 熔断（自动关闭该制品自治并通知，§13.4）。
    var maxConsecutiveRollbacksBeforeCircuitBreak: Int = 2

    static let standard = IOSPromotionPolicyConfiguration()
}

// MARK: - Decision input / output

/// Everything the deterministic policy decision needs. `permissionEnvelopeRaw`
/// is the candidate manifest's permission summary (`[IOSToolEffectClass.rawValue]`,
/// conservative union of all steps, invariant 10); empty for text-only
/// Skill/Playbook deltas.
struct IOSPromotionPolicyInput: Equatable, Sendable {
    let artifactKind: IOSArtifactKind
    let artifactName: String
    let permissionEnvelopeRaw: [String]
    let candidateHash: String
    let report: IOSEvaluationReport?
    /// §15 Phase 2 stop condition: 无 failure replay / protected / sealed
    /// holdout 的候选只能开人工草稿，永远不自动。
    let draftOnly: Bool
}

struct IOSPromotionPolicyDecision: Equatable, Sendable {
    let tier: IOSPromotionTier
    /// 为什么被分到这一级（展示用）。
    let classificationReasons: [String]
    /// 硬门禁失败列表；空 = 全部门禁通过。
    let gateFailures: [String]
    /// 仅当全部门禁通过且 tier ∈ {T0, T1} 时可为 true。
    let canAutoApprove: Bool
}

// MARK: - Engine

enum IOSPromotionPolicyEngine {
    /// 策略版本。receipt 的 `approvedBy` 必须同时记录引擎标识与策略版本
    /// （不变量 7/16），策略行为变化时递增版本。
    static let policyVersion = "amber.ios.promotion.policy.v1"
    static var approvedBy: String { "policy.engine:\(policyVersion)" }

    /// §13.4 classification. Deterministic: artifact kind + conservative
    /// permission envelope only — never the model's own claim.
    static func classify(
        artifactKind: IOSArtifactKind,
        permissionEnvelopeRaw: [String]
    ) -> (tier: IOSPromotionTier, reasons: [String]) {
        switch artifactKind {
        case .mcpBinding:
            return (.t2, ["新 MCP server 绑定——始终人工批准（§13.4）"])
        case .harnessPatch:
            return (.t2, ["Harness patch 只能走隔离 Lab，不进入生产自动通道（§5 Phase 5）"])
        case .skill, .playbook:
            if permissionEnvelopeRaw.isEmpty {
                return (.t0, ["Skill/Playbook 纯文本 delta（无权限包络）"])
            }
            return (.t2, ["Skill/Playbook 携带权限包络，超出纯文本范围（T0 仅限纯文本 delta）"])
        case .recipe:
            let classes = permissionEnvelopeRaw.compactMap(IOSToolEffectClass.init(rawValue:))
            guard let envelope = IOSToolEffectClass.conservativeUpperBound(of: classes) else {
                return (.neverAuto, ["Recipe 没有可解析的权限包络"])
            }
            switch envelope {
            case .pure:
                return (.t0, ["全部 step 为 pure（只读无副作用）"])
            case .networkRead:
                return (.t1, ["仅含 networkRead 出网读取 step（纯 local 无写，但出网升一档）"])
            case .idempotent:
                return (.t1, ["含本地可逆副作用 step（幂等，如按稳定 id 的记忆编辑），权限包络不扩大"])
            case .sideEffect:
                return (.t2, ["外部副作用 / 破坏性操作 / 权限包络扩大（envelope 为 sideEffect）"])
            }
        }
    }

    /// The deterministic authorization decision (§13.4 / 不变量 16).
    static func decide(
        input: IOSPromotionPolicyInput,
        state: IOSPromotionPolicyStateStore,
        autonomyLevel: IOSEvolutionAutonomyLevel,
        killSwitchEnabled: Bool,
        now: Date = Date(),
        configuration: IOSPromotionPolicyConfiguration = .standard
    ) -> IOSPromotionPolicyDecision {
        let classification = classify(
            artifactKind: input.artifactKind,
            permissionEnvelopeRaw: input.permissionEnvelopeRaw
        )
        let failures = hardGateFailures(
            input: input,
            state: state,
            autonomyLevel: autonomyLevel,
            killSwitchEnabled: killSwitchEnabled,
            now: now,
            configuration: configuration
        )
        if classification.tier == .t2 {
            return IOSPromotionPolicyDecision(
                tier: .t2,
                classificationReasons: classification.reasons,
                gateFailures: failures + ["tier_t2_requires_manual_approval: T2 始终人工批准"],
                canAutoApprove: false
            )
        }
        if classification.tier == .neverAuto {
            return IOSPromotionPolicyDecision(
                tier: .neverAuto,
                classificationReasons: classification.reasons,
                gateFailures: failures + ["tier_never_auto: 该分类永不自动授权"],
                canAutoApprove: false
            )
        }
        return IOSPromotionPolicyDecision(
            tier: classification.tier,
            classificationReasons: classification.reasons,
            gateFailures: failures,
            canAutoApprove: failures.isEmpty
        )
    }

    /// Settings readers (§13.4 kill switch / autonomy level). The settings
    /// view writes the same keys (`IOSEvolutionPreferenceKeys`); default is
    /// T0+T1 auto, kill switch off.
    static func currentAutonomyLevel(defaults: UserDefaults = .standard) -> IOSEvolutionAutonomyLevel {
        defaults.string(forKey: IOSEvolutionPreferenceKeys.autonomyLevel)
            .flatMap(IOSEvolutionAutonomyLevel.init(rawValue:)) ?? .t0T1Auto
    }

    static func killSwitchEnabled(defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: IOSEvolutionPreferenceKeys.killSwitch) == nil
            ? false
            : defaults.bool(forKey: IOSEvolutionPreferenceKeys.killSwitch)
    }

    // MARK: Hard gates

    private static func hardGateFailures(
        input: IOSPromotionPolicyInput,
        state: IOSPromotionPolicyStateStore,
        autonomyLevel: IOSEvolutionAutonomyLevel,
        killSwitchEnabled: Bool,
        now: Date,
        configuration: IOSPromotionPolicyConfiguration
    ) -> [String] {
        var failures: [String] = []

        guard let report = input.report else {
            failures.append("no_evaluation_report: 没有评测报告——永不自动（§13.4）")
            // 后续门禁都依赖 report，直接返回。
            return failures
        }
        if !report.matches(candidateHash: input.candidateHash) {
            failures.append("report_candidate_hash_mismatch: 评测报告与候选哈希不匹配（候选或报告已变化，§13.1）")
        }
        if report.recommendation != .promote {
            failures.append("recommendation_not_promote: 评测建议不是 promote（\(report.recommendation.rawValue)）")
        }
        if report.protectedRegressions != 0 {
            failures.append("protected_regressions: \(report.protectedRegressions) 条受保护成功样例回归（不变量 13）")
        }
        if input.draftOnly {
            failures.append("draft_only: 无完整评测套件（failure replay / protected / sealed holdout 缺一），只开人工草稿（§15 停止条件）")
        }
        if killSwitchEnabled {
            failures.append("kill_switch: 全局自治开关已关闭")
        }
        switch autonomyLevel {
        case .allManual:
            failures.append("autonomy_level_all_manual: 自治级别为「全部人工」")
        case .notifyOnly:
            failures.append("autonomy_level_notify_only: 自治级别为「仅通知不自动」（T0/T1 也走人工批准）")
        case .t0T1Auto:
            break
        }

        let snapshot = state.snapshot()
        if let artifactState = snapshot.artifacts[input.artifactName], artifactState.autonomyDisabled {
            failures.append("circuit_broken: 该制品已因连续回退被熔断，自治已关闭（§13.4）")
        }
        let remaining = state.cooldownRemaining(artifactId: input.artifactName, now: now)
        if remaining > 0 {
            failures.append("cooldown: 该制品处于冷却期（剩余 \(Int(remaining / 60)) 分钟，§13.4）")
        }
        if !state.withinDailyAutoPromotionBudget(
            now: now,
            limit: configuration.dailyAutoPromotionLimit
        ) {
            failures.append(
                "daily_budget_exhausted: 今日自动晋升已达上限 \(configuration.dailyAutoPromotionLimit)（§18.3 预算）"
            )
        }
        return failures
    }
}

// MARK: - Phase 4 扩面数据闸门（§15 Phase 4 进入条件；§19 可解释率）

/// 可放宽的自治范围项（§15 Phase 4 建议默认策略：按真实数据评估是否放宽
/// 自治范围）。本 wave 只提供只读闸门判定，不做任何自动放宽动作——
/// 数据闸门，不是自动扩权。
enum IOSWideningTarget: String, Codable, Equatable, Sendable, CaseIterable {
    /// 提高每日自动晋升上限（§13.4 预算）。
    case dailyAutoPromotionLimit
    /// 缩短同一制品自动晋升后的冷却期。
    case promotionCooldown
    /// 缩短回退冷却期。
    case rollbackCooldown
}

/// 扩面闸门判定结果：allow/refuse + 依据（真实观测数据摘要）与缺失数据说明。
struct IOSWideningGateEvaluation: Equatable, Sendable {
    let target: IOSWideningTarget
    let allow: Bool
    /// 判定依据：真实数据（累计 promotion 数、30 天 rollback 率、用户拒绝率）。
    let basis: String
    /// allow == false 时说明缺什么数据/哪项指标越限；allow == true 时为空。
    let missingData: [String]
}

extension IOSPromotionPolicyEngine {
    /// 扩面最小真实 promotion 样本数。Phase 4 进入条件要求「已积累真实
    /// promotion/rollback 数据，能够量化 false-positive、回归率和用户拒绝
    /// 率」；少于该样本量任何率都不可信，拒绝扩面并说明缺什么数据。
    static let wideningMinimumPromotions = 5
    /// 30 天 rollback 率上限。超过该上限说明自动通道正在制造需要回退的
    /// 发布，应先修通道而不是扩权。
    static let wideningMaximumRollbackRate = 0.20
    /// 用户拒绝率上限。超过一半候选被用户拒绝说明候选质量/相关性不足，
    /// 扩大自治只会制造更多噪音。
    static let wideningMaximumUserRejectionRate = 0.50

    /// §15 Phase 4 只读扩面闸门：基于真实观测数据（§19）判定是否允许放宽
    /// 某项自治范围。不做任何放宽动作，只输出 allow/refuse + 依据。
    /// 样本不足（无 promotion / promotion 少于下限 / 窗口无数据 / 无用户
    /// 决策）→ refuse 并说明缺什么数据（进入条件：没有真实数据不得凭感觉
    /// 扩大）。
    static func wideningGate(
        target: IOSWideningTarget,
        metrics: IOSEvolutionMetricsSnapshot,
        now: Date = Date()
    ) -> IOSWideningGateEvaluation {
        var missing: [String] = []

        let total = metrics.promotionCount
        if total == 0 {
            missing.append("没有任何真实 promotion（自动/人工晋升均无）——Phase 4 进入条件要求先积累真实数据")
        } else if total < wideningMinimumPromotions {
            missing.append(
                "累计 promotion \(total) 次 < \(wideningMinimumPromotions) 次下限——样本不足以量化回归率与用户拒绝率"
            )
        }

        let rollbackRate = metrics.rollbackRate(days: 30, now: now)
        if let rollbackRate {
            if rollbackRate > wideningMaximumRollbackRate {
                missing.append(
                    "近 30 天 rollback 率 \(Self.percent(rollbackRate)) > 上限 \(Self.percent(wideningMaximumRollbackRate))——自动通道正在制造回退，先修通道"
                )
            }
        } else {
            missing.append("近 30 天窗口内没有 promotion——无法计算 30 天 rollback 率（窗口无数据不等于 0）")
        }

        let rejectionRate = metrics.userRejectionRate
        if let rejectionRate {
            if rejectionRate > wideningMaximumUserRejectionRate {
                missing.append(
                    "用户拒绝率 \(Self.percent(rejectionRate)) > 上限 \(Self.percent(wideningMaximumUserRejectionRate))——候选质量/相关性不足"
                )
            }
        } else {
            missing.append("没有用户可见决策（拒绝/批准/自动发布）——无法量化用户拒绝率")
        }

        return IOSWideningGateEvaluation(
            target: target,
            allow: missing.isEmpty,
            basis: Self.wideningBasis(
                metrics: metrics,
                rollbackRate: rollbackRate,
                rejectionRate: rejectionRate
            ),
            missingData: missing
        )
    }

    private static func wideningBasis(
        metrics: IOSEvolutionMetricsSnapshot,
        rollbackRate: Double?,
        rejectionRate: Double?
    ) -> String {
        let rollbackText = rollbackRate.map { "近 30 天 rollback 率 \(Self.percent($0))" }
            ?? "近 30 天无 promotion，rollback 率不可算"
        let rejectionText = rejectionRate.map { "用户拒绝率 \(Self.percent($0))" }
            ?? "无用户可见决策，拒绝率不可算"
        return "真实观测：累计 promotion \(metrics.promotionCount)（自动 \(metrics.autoPromotionCount) / 人工 \(metrics.manualPromotionCount)）；\(rollbackText)；\(rejectionText)。"
    }

    private static func percent(_ rate: Double) -> String {
        String(format: "%.0f%%", rate * 100)
    }
}

// MARK: - Persisted policy state (budget / cooldown / circuit breaker, §13.4)

/// Durable state the policy engine reads and writes. Lives under the recipe
/// store's base directory (`evolution/policy-state.json`), so receipts and
/// breaker state travel together. All mutations are lock-protected and
/// atomic-written; a corrupted/missing file degrades to an empty state
/// (fail-safe: no stale breaker can survive a corrupt file, and no
/// promotion is blocked by an unreadable file beyond the default budget).
final class IOSPromotionPolicyStateStore: @unchecked Sendable {
    struct ArtifactState: Codable, Equatable {
        var cooldownUntilEpochMs: Int64 = 0
        var consecutiveRollbacks: Int = 0
        var autonomyDisabled: Bool = false
    }

    struct Snapshot: Codable, Equatable {
        var schemaVersion: Int = 1
        /// UTC calendar date (`yyyy-MM-dd`) the daily count belongs to.
        var dailyDate: String = ""
        var dailyAutoPromotionCount: Int = 0
        var artifacts: [String: ArtifactState] = [:]
    }

    /// Production store over the documents directory (same base-directory
    /// convention as the recipe store / receipt store).
    static let shared: IOSPromotionPolicyStateStore = {
        let baseDirectory = (try? FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? FileManager.default.temporaryDirectory
        return IOSPromotionPolicyStateStore(baseDirectory: baseDirectory)
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
            .appendingPathComponent("policy-state.json")
    }

    func snapshot() -> Snapshot {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        guard let data = try? Data(contentsOf: fileURL),
              let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data) else {
            return Snapshot()
        }
        return snapshot
    }

    /// Records one autonomous promotion: increments the daily count (resetting
    /// when the calendar date changed), starts this artifact's promotion
    /// cooldown and resets its consecutive-rollback counter (a successful
    /// promotion re-proves the artifact, §13.4).
    func recordAutoPromotion(
        artifactId: String,
        now: Date = Date(),
        configuration: IOSPromotionPolicyConfiguration = .standard
    ) {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        var snapshot = read()
        let today = Self.dayString(now)
        if snapshot.dailyDate != today {
            snapshot.dailyDate = today
            snapshot.dailyAutoPromotionCount = 0
        }
        snapshot.dailyAutoPromotionCount += 1
        var artifact = snapshot.artifacts[artifactId] ?? ArtifactState()
        artifact.cooldownUntilEpochMs = Self.millis(
            now.addingTimeInterval(configuration.cooldownAfterPromotionSeconds)
        )
        artifact.consecutiveRollbacks = 0
        snapshot.artifacts[artifactId] = artifact
        write(snapshot)
    }

    /// Records one rollback of an artifact. Returns `true` exactly when this
    /// rollback TRIPS the breaker (consecutive rollbacks reached the
    /// threshold) — the caller must surface the user-visible notification
    /// (不变量 17 / §13.4: 连续两次 rollback → 自动关闭该制品自治并通知).
    @discardableResult
    func recordRollback(
        artifactId: String,
        now: Date = Date(),
        configuration: IOSPromotionPolicyConfiguration = .standard
    ) -> Bool {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        var snapshot = read()
        var artifact = snapshot.artifacts[artifactId] ?? ArtifactState()
        artifact.consecutiveRollbacks += 1
        artifact.cooldownUntilEpochMs = Self.millis(
            now.addingTimeInterval(configuration.cooldownAfterRollbackSeconds)
        )
        let tripped = artifact.consecutiveRollbacks >= configuration.maxConsecutiveRollbacksBeforeCircuitBreak
        if tripped {
            artifact.autonomyDisabled = true
        }
        snapshot.artifacts[artifactId] = artifact
        write(snapshot)
        return tripped
    }

    /// Seconds until the artifact's cooldown expires (0 = not in cooldown).
    func cooldownRemaining(artifactId: String, now: Date = Date()) -> TimeInterval {
        let snapshot = self.snapshot()
        guard let state = snapshot.artifacts[artifactId] else { return 0 }
        let until = TimeInterval(state.cooldownUntilEpochMs) / 1000
        return max(0, until - now.timeIntervalSince1970)
    }

    func isAutonomyDisabled(artifactId: String) -> Bool {
        snapshot().artifacts[artifactId]?.autonomyDisabled ?? false
    }

    /// True when today's autonomous promotions are still under the limit.
    func withinDailyAutoPromotionBudget(now: Date = Date(), limit: Int) -> Bool {
        let snapshot = self.snapshot()
        guard snapshot.dailyDate == Self.dayString(now) else { return true }
        return snapshot.dailyAutoPromotionCount < limit
    }

    // MARK: Private

    private func read() -> Snapshot {
        guard let data = try? Data(contentsOf: fileURL),
              let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data) else {
            return Snapshot()
        }
        return snapshot
    }

    private func write(_ snapshot: Snapshot) {
        do {
            try fileManager.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try encoder.encode(snapshot).write(to: fileURL, options: .atomic)
        } catch {
            // Best-effort: a state write failure must not fail the promotion
            // itself — the breaker only degrades to "not disabled" until the
            // next successful write (documented MVP boundary).
            print("[AmberChat] promotion policy state write failed: \(error)")
        }
    }

    private static func dayString(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .current
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        return String(
            format: "%04d-%02d-%02d",
            components.year ?? 0, components.month ?? 0, components.day ?? 0
        )
    }

    private static func millis(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }
}

// MARK: - Publisher (authorize → apply → registry refresh → receipt, §13.1)

struct IOSPromotionPublishReceipt: Equatable {
    let storeReceipt: IOSRecipeApplyReceipt
    let promotionReceipt: IOSPromotionReceipt
    let catalogRevision: Int64?
}

enum IOSPromotionPublishError: Error, Equatable {
    case recipeNotApplicable(String)
    /// Slice B（B1）：report 携带的 originRunId 与调用方冻结的不一致 →
    /// 用户批准/回退的对象已不是同一 run 链路，fail closed 零写入。
    case reportOriginRunMismatch(expected: String?, actual: String?)
    /// report 与候选哈希不匹配（report.matches 失败，§9.4/§13.1）→
    /// 候选或报告任一变化，fail closed 零写入。
    case reportCandidateHashMismatch(expected: String, actual: String)
}

/// Applies an already-evaluated candidate with the SAME CAS semantics as a
/// manual approval (§13.1: base/candidate 任一变化 → fail closed, zero writes),
/// then refreshes the registry so the next model round acquires the new
/// revision (§13.2 / §16.1), and records the versioned receipt. `approvedBy`
/// is the authorizer identity: policy engine + policy version for autonomous
/// promotions (invariant 7/16), or "user" for manual approvals.
/// `expectedOriginRunId`（Slice B/B1）是调用方冻结的失败 run 身份；非 nil
/// 时要求 report 携带同一 originRunId，否则 fail closed 零写入。
enum IOSPromotionPublisher {
    static func publishRecipe(
        content: Data,
        manifest: IOSEvolutionCandidateManifest,
        report: IOSEvaluationReport?,
        approvedBy: String,
        expectedOriginRunId: String? = nil,
        recipeStore: IOSRecipeFileStore,
        receiptStore: IOSPromotionReceiptStore,
        refreshRegistry: @MainActor () async -> IOSDynamicToolCatalogSnapshot?
    ) async throws -> IOSPromotionPublishReceipt {
        guard manifest.artifactKind == .recipe else {
            throw IOSPromotionPublishError.recipeNotApplicable(
                "只有 Recipe 候选可走发布通道（当前 \(manifest.artifactKind.rawValue)）。"
            )
        }
        // Slice B（B1/B2 identity 门禁，§13.1 最后一层防线）：policy engine
        // 之外再验一遍 report 与候选/run 链路的绑定。任一不匹配 → 抛 typed
        // 错误、零写入（applyRecipe 之前的任何失败都不会触碰磁盘）。
        if let expectedOriginRunId {
            guard report?.originRunId == expectedOriginRunId else {
                throw IOSPromotionPublishError.reportOriginRunMismatch(
                    expected: expectedOriginRunId,
                    actual: report?.originRunId
                )
            }
        }
        if let report, !report.matches(candidateHash: manifest.candidateHash) {
            throw IOSPromotionPublishError.reportCandidateHashMismatch(
                expected: manifest.candidateHash,
                actual: report.candidateHash
            )
        }
        // CAS: applyRecipe re-verifies both sides against the manifest the
        // policy engine decided on — a stale candidate/base fails closed.
        let storeReceipt = try recipeStore.applyRecipe(
            name: manifest.artifactName,
            recipeJSON: content,
            expectedBaseHash: manifest.baseHash,
            expectedCandidateHash: manifest.candidateHash,
            approvedBy: approvedBy,
            evaluationReportHash: report?.reportHash
        )
        // Round-boundary publish (§13.2): the new revision is what the next
        // model round acquires; the receipt carries it for attribution.
        let snapshot = await refreshRegistry()
        let promotionReceipt = IOSPromotionReceipt(
            artifactId: manifest.artifactName,
            fromHash: manifest.baseHash,
            toHash: manifest.candidateHash,
            evaluationReportHash: report?.reportHash,
            catalogRevision: snapshot?.revision,
            approvedBy: approvedBy,
            promotedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        receiptStore.record(promotionReceipt)
        return IOSPromotionPublishReceipt(
            storeReceipt: storeReceipt,
            promotionReceipt: promotionReceipt,
            catalogRevision: snapshot?.revision
        )
    }
}
