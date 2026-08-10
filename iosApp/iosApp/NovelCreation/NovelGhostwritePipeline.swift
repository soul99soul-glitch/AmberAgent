import Foundation

enum NovelGhostwriteCandidateOwnership {
    static func belongs(_ candidate: NovelCandidateRecord, to plan: NovelChapterPlanRecord) -> Bool {
        candidate.ghostwritePlanID == plan.id
            && candidate.chapterPlanDigest == plan.contentDigest
    }
}

enum NovelGhostwriteBatch {
    static let minChapterCount = 1
    static let maxChapterCount = 10

    /// 代笔写稿的输入预算请求值：对齐结构化执行器内部上限，
    /// 由 `effectiveInputBudget` 再按模型窗口与输出留位收敛。
    /// 此前硬编码 16_000，总纲等常驻资料一多就必撞注入预算墙。
    static let writeInputBudgetTokens =
        NovelStructuredModelExecutor.maximumInternalInputBudgetTokens

    static func clamp(_ value: Int) -> Int {
        min(max(value, minChapterCount), maxChapterCount)
    }
}

/// 代笔基建重试（纯逻辑，可单测）：只对执行器标记为可重试的失败做有界重试。
/// 与章计划拟定的 3 次重试对齐——验收/连续性审计此前零重试，
/// 一次传输抖动就把整批代笔打停。取消必须立即透传，绝不重试。
enum NovelGhostwriteInfraRetry {
    static let maxAttempts = 3

    static func run<T: Sendable>(
        maxAttempts: Int = maxAttempts,
        onRetry: @Sendable (Int) async -> Void = { _ in },
        operation: @Sendable () async throws -> T
    ) async throws -> T {
        var attempt = 0
        while true {
            attempt += 1
            do {
                return try await operation()
            } catch let failure as NovelStructuredModelExecutionFailure
                where failure.failure.isRetryable
                    && failure.failure.code != "cancelled"
                    && attempt < maxAttempts {
                await onRetry(attempt)
                try await Task.sleep(for: .milliseconds(400 * attempt))
            }
        }
    }
}

enum NovelGhostwritePhase: String, Codable, Equatable, Sendable {
    case writing
    case accepting
    case collecting
    case syncing
    case planning
    /// 人工润修生成中（解套路径）。
    case revising
    case paused
    case waitingUser
    case failed
}

enum NovelGhostwritePauseReason: String, Codable, Equatable, Sendable {
    case userPaused
    case acceptanceFailed
    case obviousRepetition
    case blockingContinuity
    case continuityAuditIncomplete
    case collectFailed
    case syncFailed
    case incompleteCandidate
    case planMismatch
    case planProposalFailed
    case chapterCompleted
    case batchCompleted
    case cancelled
    /// 自动改写预算用尽，等待润修或改合同。
    case healBudgetExhausted
    /// 新批首章已自动拟定计划，等用户确认后连写。
    case planProposedForNewBatch
    /// 基建失败（传输/取消外的模型执行故障等）：不是质量判定，候选不背锅。
    case infrastructureFailed

    var displayMessage: String {
        switch self {
        case .userPaused: "已暂停代笔。"
        case .acceptanceFailed:
            "没按本章计划写过关。继续将重写本章，不会再验同一篇旧稿。"
        case .obviousRepetition:
            "检测到明显复读。继续将重写本章，避免重复近期节拍。"
        case .blockingContinuity:
            "前后情节有严重问题，已暂停自动收录。建议按审稿意见润修或改合同。"
        case .continuityAuditIncomplete:
            "连续性检查未完整完成，已暂停自动收录。"
        case .collectFailed: "自动收录失败，已暂停代笔。"
        case .syncFailed: "剧情同步还没完成，代笔已暂停，不会开始下一章。"
        case .incompleteCandidate: "本章正文不完整。继续将重新生成整章。"
        case .planMismatch: "这篇稿和当前计划对不上。继续将按当前计划重写。"
        case .planProposalFailed: "自动拟定下一章计划失败，已暂停代笔。"
        case .chapterCompleted: "本章已收录并同步。请先定好下一章计划再继续。"
        case .batchCompleted: "本批代笔已完成。"
        case .planProposedForNewBatch: "已自动拟定下一章计划，确认后开始写。"
        case .cancelled: "代笔已取消。"
        case .healBudgetExhausted:
            "自动改写已达上限仍未过关。建议按审稿意见润修，或整章重写 / 改本章计划。"
        case .infrastructureFailed:
            "模型调用失败（非质量判定）。继续将从当前阶段重试，已产候选不丢弃。"
        }
    }

    /// 合同已消费、但本批仍可续跑时，继续不要求已确认合同。
    var resumesWithoutConfirmedPlan: Bool {
        switch self {
        case .syncFailed, .planProposalFailed, .infrastructureFailed, .planProposedForNewBatch: true
        default: false
        }
    }

    /// 质量失败：继续/自愈时必须产新候选，禁止用同一稿再验。
    var requiresRewriteOnContinue: Bool {
        switch self {
        case .acceptanceFailed, .obviousRepetition, .blockingContinuity,
             .incompleteCandidate, .planMismatch, .healBudgetExhausted:
            return true
        case .userPaused, .continuityAuditIncomplete, .collectFailed, .syncFailed,
             .planProposalFailed, .planProposedForNewBatch,
             .chapterCompleted, .batchCompleted, .cancelled,
             .infrastructureFailed:
            return false
        }
    }

    /// 章内可自动改写（同合同 Tier1）。严重连续性默认不停在自动档空转。
    var allowsAutomaticQualityHeal: Bool {
        switch self {
        case .acceptanceFailed, .obviousRepetition:
            return true
        default:
            return false
        }
    }

    static func failedReason(from error: Error) -> NovelGhostwritePauseReason {
        if let novel = error as? NovelError {
            switch novel {
            case .invalidInput(let message) where message.contains("不完整"):
                return .incompleteCandidate
            case .invalidInput(let message)
                where message.contains("合同") || message.contains("计划"):
                return .planMismatch
            default:
                break
            }
        }
        // 其余抛错全是基建面（传输/解码/执行故障），不是质量判定：
        // 归入 infra，继续时从当前阶段重试，不强制重写已产候选。
        return .infrastructureFailed
    }
}

/// 有界失败回执：只注入短列表，不把失败全文灌进长期上下文。
struct NovelGhostwriteFailureReceipt: Codable, Equatable, Sendable {
    var reason: NovelGhostwritePauseReason
    var summary: String
    var missingMustHappen: [String]
    var repetitionBeats: [String]
    var continuityNotes: [String]
    var attemptIndex: Int
    var sourceCandidateID: NovelCandidateID?
    var planDigest: String?

    var fingerprint: String {
        let parts = [
            String(describing: reason),
            normalize(summary),
            missingMustHappen.map(normalize).joined(separator: "|"),
            repetitionBeats.map(normalize).joined(separator: "|"),
            continuityNotes.map(normalize).joined(separator: "|"),
        ]
        return parts.joined(separator: "§")
    }

    /// 写入模型的改写说明（有界）。
    func healInstructionBlock(characterLimit: Int = 1_200) -> String {
        var lines: [String] = []
        let summary = self.summary.trimmingCharacters(in: .whitespacesAndNewlines)
        if !summary.isEmpty {
            lines.append("审稿意见：\(clip(summary, 400))")
        }
        if !missingMustHappen.isEmpty {
            lines.append(
                "必须补写的节拍：\n"
                    + missingMustHappen.prefix(6).map { "- \(clip($0, 120))" }.joined(separator: "\n")
            )
        }
        if !repetitionBeats.isEmpty {
            lines.append(
                "禁止再写的近期复读节拍：\n"
                    + repetitionBeats.prefix(4).map { "- \(clip($0, 120))" }.joined(separator: "\n")
            )
        }
        if !continuityNotes.isEmpty {
            lines.append(
                "连续性注意：\n"
                    + continuityNotes.prefix(4).map { "- \(clip($0, 120))" }.joined(separator: "\n")
            )
        }
        lines.append("请重写完整一章：落实上述要求，开篇换新，不要复述已写过的同一动作节拍。")
        return clip(lines.joined(separator: "\n\n"), characterLimit)
    }

    /// 人工润修 sheet 预填 brief。
    func recommendedRevisionBrief() -> String {
        healInstructionBlock(characterLimit: 2_000)
    }

    static func make(
        reason: NovelGhostwritePauseReason,
        summary: String,
        missingMustHappen: [String] = [],
        repetitionBeats: [String] = [],
        continuityNotes: [String] = [],
        attemptIndex: Int,
        sourceCandidateID: NovelCandidateID?,
        planDigest: String?
    ) -> NovelGhostwriteFailureReceipt {
        NovelGhostwriteFailureReceipt(
            reason: reason,
            summary: clip(summary, 400),
            missingMustHappen: missingMustHappen
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .prefix(6)
                .map { clip($0, 120) },
            repetitionBeats: repetitionBeats
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .prefix(4)
                .map { clip($0, 120) },
            continuityNotes: continuityNotes
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .prefix(4)
                .map { clip($0, 120) },
            attemptIndex: max(0, attemptIndex),
            sourceCandidateID: sourceCandidateID,
            planDigest: planDigest
        )
    }

    private static func clip(_ text: String, _ limit: Int) -> String {
        guard text.count > limit else { return text }
        return String(text.prefix(limit)) + "…"
    }

    private func clip(_ text: String, _ limit: Int) -> String {
        Self.clip(text, limit)
    }

    private func normalize(_ text: String) -> String {
        text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .lowercased()
    }
}

enum NovelGhostwriteHeal {
    static let defaultMaxQualityAttempts = 3
    static let maxQualityAttemptsClamp = 1...5
    /// 基建（同步）自动重试次数。
    static let defaultMaxInfraRetries = 3
    /// 连续相同失败指纹达到该次数则熔断（含本次）。
    static let stuckFingerprintThreshold = 2

    static func clampMaxAttempts(_ value: Int) -> Int {
        min(max(value, maxQualityAttemptsClamp.lowerBound), maxQualityAttemptsClamp.upperBound)
    }

    /// 再失败一次后是否仍允许自动改写（不含本次已发生的失败计数）。
    static func shouldAutoRewrite(
        afterFailureCount failureCount: Int,
        maxAttempts: Int,
        reason: NovelGhostwritePauseReason,
        recentFingerprints: [String] = []
    ) -> Bool {
        guard reason.allowsAutomaticQualityHeal else { return false }
        if isStuckOnSameFingerprint(recentFingerprints) { return false }
        let max = clampMaxAttempts(maxAttempts)
        // failureCount 为累计质量失败次数；小于 max 时可再写一篇。
        return failureCount < max
    }

    /// 尾部连续相同 fingerprint 达到阈值 → 空转熔断。
    static func isStuckOnSameFingerprint(_ fingerprints: [String]) -> Bool {
        let tail = Array(fingerprints.suffix(stuckFingerprintThreshold))
        guard tail.count >= stuckFingerprintThreshold else { return false }
        let first = tail[0]
        guard !first.isEmpty else { return false }
        return tail.allSatisfy { $0 == first }
    }

    /// 纯复读且预算/指纹用尽时，可薄升级：把撞车 beat 写入 mustNot。
    static func shouldAttemptMustNotAmend(
        reason: NovelGhostwritePauseReason,
        receipt: NovelGhostwriteFailureReceipt,
        alreadyAmendedThisChapter: Bool
    ) -> Bool {
        guard !alreadyAmendedThisChapter else { return false }
        guard reason == .obviousRepetition || !receipt.repetitionBeats.isEmpty else { return false }
        return !receipt.repetitionBeats.isEmpty
    }

    /// 仅缺 1 条 must、无禁止项/复读、且本章未薄升级时，可放宽该条措辞后再写一轮。
    static func shouldAttemptMustAlign(
        reason: NovelGhostwritePauseReason,
        receipt: NovelGhostwriteFailureReceipt,
        forbiddenViolations: [String],
        alreadyAmendedThisChapter: Bool
    ) -> Bool {
        guard !alreadyAmendedThisChapter else { return false }
        guard reason == .acceptanceFailed else { return false }
        guard receipt.missingMustHappen.count == 1 else { return false }
        let missing = receipt.missingMustHappen[0]
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !missing.isEmpty else { return false }
        // 有禁止项违反或明显复读时不改 must（避免放水）。
        let hasForbidden = forbiddenViolations.contains {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        guard !hasForbidden else { return false }
        guard receipt.repetitionBeats.isEmpty else { return false }
        return true
    }

    /// 把合同中对应的那一条 must 改成「保留意图 + 允许等价表达」。
    /// 找不到匹配项且 must 多于 1 条时返回 nil（宁可不改）。
    static func rephraseSingleMust(
        planMustHappen: [String],
        missingItem: String,
        acceptanceSummary: String
    ) -> (index: Int, original: String, rewritten: String)? {
        let missing = missingItem.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !missing.isEmpty else { return nil }

        var matchIndex: Int?
        for (index, item) in planMustHappen.enumerated() {
            let text = item.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { continue }
            if text == missing
                || text.contains(missing)
                || missing.contains(text) {
                matchIndex = index
                break
            }
        }
        if matchIndex == nil, planMustHappen.count == 1 {
            matchIndex = 0
        }
        guard let index = matchIndex else { return nil }

        let original = planMustHappen[index]
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !original.isEmpty else { return nil }
        // 已放过宽则不再叠字，防止 digest 空转。
        if original.contains("允许等价") || original.contains("可辨认写出") {
            return nil
        }

        let hint = acceptanceSummary
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let hintClip: String = {
            guard !hint.isEmpty else { return "" }
            if hint.count <= 100 { return hint }
            return String(hint.prefix(100)) + "…"
        }()

        var rewritten =
            "\(original)（须在正文可辨认写出；允许等价情绪/动作措辞，不必与合同字面完全一致）"
        if !hintClip.isEmpty {
            rewritten += " 审稿摘要：\(hintClip)"
        }
        if rewritten.count > 220 {
            rewritten = String(rewritten.prefix(220)) + "…"
        }
        guard rewritten != original else { return nil }
        return (index, original, rewritten)
    }

    static func writeUserText(receipt: NovelGhostwriteFailureReceipt?) -> String {
        guard let receipt else {
            return "请按本章计划写完整一章正文。"
        }
        return """
        请按本章计划重写完整一章正文。上一稿未通过验收，必须按下列意见修改；不要再用同一开篇。

        \(receipt.healInstructionBlock())
        """
    }
}

/// 批内可审计的合同薄升级记录。
struct NovelGhostwriteContractAmendment: Codable, Equatable, Sendable {
    enum Kind: String, Codable, Equatable, Sendable {
        case appendMustNot
        /// 单条 must 措辞放宽对齐（保留意图，允许等价表达）。
        case alignSingleMust
    }

    let kind: Kind
    let detail: String
    let chapterIndex: Int
    let beforeDigest: String?
    let afterDigest: String?
}

/// 跨进程可恢复的本批代笔进度（按 project+branch 落盘 sidecar）。
struct NovelGhostwriteBatchProgressRecord: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1

    var schemaVersion: Int
    var projectID: NovelProjectID
    var branchID: NovelBranchID
    var phase: NovelGhostwritePhase
    var pauseReason: NovelGhostwritePauseReason?
    var detailMessage: String?
    var candidateID: NovelCandidateID?
    var chapterPlanDigest: String?
    var autoCollectedCandidateIDs: [NovelCandidateID]
    var startedAt: Date
    var updatedAt: Date
    var targetChapterCount: Int
    var completedChapterCount: Int
    var currentChapterIndex: Int
    var lastCompletedPlanSummary: String?
    var pendingSyncChapterCredit: Bool
    var qualityAttemptIndex: Int
    var maxQualityAttempts: Int
    var lastFailureReceipt: NovelGhostwriteFailureReceipt?
    var supersededCandidateIDs: [NovelCandidateID]
    var recentFailureFingerprints: [String]
    var revisionBriefOverride: String?
    var didThinContractAmendThisChapter: Bool
    var contractAmendments: [NovelGhostwriteContractAmendment]

    /// 冷启动：把进行中相位收成可继续的暂停/失败态。
    func normalizedForColdStart() -> NovelGhostwriteBatchProgressRecord {
        var next = self
        let recoveryNote = "应用重启后已恢复本批代笔进度，可继续。"
        switch phase {
        case .writing, .accepting, .collecting, .planning, .revising:
            next.phase = .paused
            if next.pauseReason == nil || next.pauseReason == .cancelled {
                next.pauseReason = .userPaused
            }
            next.detailMessage = Self.mergeDetail(next.detailMessage, recoveryNote)
        case .syncing:
            if next.pendingSyncChapterCredit {
                next.phase = .failed
                next.pauseReason = .syncFailed
                next.detailMessage = Self.mergeDetail(
                    next.detailMessage,
                    "本章已收录，重启后请继续完成剧情同步。"
                )
            } else {
                next.phase = .paused
                next.pauseReason = next.pauseReason ?? .userPaused
                next.detailMessage = Self.mergeDetail(next.detailMessage, recoveryNote)
            }
        case .paused, .waitingUser, .failed:
            if next.pauseReason == nil {
                next.pauseReason = .userPaused
            }
            // 已是终态/暂停：仍提示已恢复，避免用户以为进度丢了。
            if next.shouldContinueSameBatchAfterRestore {
                next.detailMessage = Self.mergeDetail(next.detailMessage, recoveryNote)
            }
        }
        next.revisionBriefOverride = revisionBriefOverride
        return next
    }

    private var shouldContinueSameBatchAfterRestore: Bool {
        if completedChapterCount >= NovelGhostwriteBatch.clamp(targetChapterCount) { return false }
        if pendingSyncChapterCredit { return true }
        switch pauseReason {
        case .batchCompleted, .chapterCompleted, .cancelled, nil:
            return false
        default:
            return true
        }
    }

    private static func mergeDetail(_ existing: String?, _ note: String) -> String {
        let base = existing?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if base.isEmpty { return note }
        if base.contains(note) { return base }
        return base + "\n" + note
    }

    func makeProgress() -> NovelGhostwriteProgress {
        let record = normalizedForColdStart()
        return NovelGhostwriteProgress(
            binding: NovelSessionBinding(projectID: record.projectID, branchID: record.branchID),
            phase: record.phase,
            pauseReason: record.pauseReason,
            detailMessage: record.detailMessage,
            candidateID: record.candidateID,
            chapterPlanDigest: record.chapterPlanDigest,
            autoCollectedCandidateIDs: Set(record.autoCollectedCandidateIDs),
            startedAt: record.startedAt,
            targetChapterCount: record.targetChapterCount,
            completedChapterCount: record.completedChapterCount,
            currentChapterIndex: record.currentChapterIndex,
            lastCompletedPlanSummary: record.lastCompletedPlanSummary,
            pendingSyncChapterCredit: record.pendingSyncChapterCredit,
            qualityAttemptIndex: record.qualityAttemptIndex,
            maxQualityAttempts: record.maxQualityAttempts,
            lastFailureReceipt: record.lastFailureReceipt,
            supersededCandidateIDs: Set(record.supersededCandidateIDs),
            recentFailureFingerprints: record.recentFailureFingerprints,
            revisionBriefOverride: record.revisionBriefOverride,
            didThinContractAmendThisChapter: record.didThinContractAmendThisChapter,
            contractAmendments: record.contractAmendments,
            infraRetryCount: 0
        )
    }

    static func from(
        progress: NovelGhostwriteProgress,
        updatedAt: Date = Date()
    ) -> NovelGhostwriteBatchProgressRecord {
        NovelGhostwriteBatchProgressRecord(
            schemaVersion: currentSchemaVersion,
            projectID: progress.binding.projectID,
            branchID: progress.binding.branchID,
            phase: progress.phase,
            pauseReason: progress.pauseReason,
            detailMessage: progress.detailMessage,
            candidateID: progress.candidateID,
            chapterPlanDigest: progress.chapterPlanDigest,
            autoCollectedCandidateIDs: Array(progress.autoCollectedCandidateIDs),
            startedAt: progress.startedAt,
            updatedAt: updatedAt,
            targetChapterCount: progress.targetChapterCount,
            completedChapterCount: progress.completedChapterCount,
            currentChapterIndex: progress.currentChapterIndex,
            lastCompletedPlanSummary: progress.lastCompletedPlanSummary,
            pendingSyncChapterCredit: progress.pendingSyncChapterCredit,
            qualityAttemptIndex: progress.qualityAttemptIndex,
            maxQualityAttempts: progress.maxQualityAttempts,
            lastFailureReceipt: progress.lastFailureReceipt,
            supersededCandidateIDs: Array(progress.supersededCandidateIDs),
            recentFailureFingerprints: progress.recentFailureFingerprints,
            revisionBriefOverride: progress.revisionBriefOverride,
            didThinContractAmendThisChapter: progress.didThinContractAmendThisChapter,
            contractAmendments: progress.contractAmendments
        )
    }

    /// 完批/取消且无需再续跑时不应保留 sidecar。
    var shouldPersist: Bool {
        if pendingSyncChapterCredit { return true }
        switch pauseReason {
        case .batchCompleted, .chapterCompleted, .cancelled:
            return false
        case nil:
            // 进行中（尚无 pause）也应落盘，便于中途杀进程恢复。
            switch phase {
            case .paused, .waitingUser, .failed:
                return false
            default:
                return completedChapterCount < NovelGhostwriteBatch.clamp(targetChapterCount)
            }
        default:
            return completedChapterCount < NovelGhostwriteBatch.clamp(targetChapterCount)
                || pendingSyncChapterCredit
        }
    }
}

enum NovelGhostwriteContinuityGate {
    /// 仅 `blocking`（界面「严重」）触发暂停；`major`/`minor` 不挡自动收录。
    static func blockingIssueSummaries(in report: NovelContinuityAuditReport) -> [String] {
        report.issues
            .filter { $0.severity == .blocking }
            .map(\.summary)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    /// 有块失败则审计未结论；不得当作「无严重问题」放行。
    static func pauseDetail(for report: NovelContinuityAuditReport) -> String? {
        if report.failedChunkCount > 0 {
            return "连续性检查未完整完成，已暂停自动收录。"
        }
        let blocking = blockingIssueSummaries(in: report)
        return blocking.isEmpty ? nil : blocking.joined(separator: "；")
    }

    static func pauseReason(for report: NovelContinuityAuditReport) -> NovelGhostwritePauseReason? {
        if report.failedChunkCount > 0 { return .continuityAuditIncomplete }
        return blockingIssueSummaries(in: report).isEmpty ? nil : .blockingContinuity
    }
}

struct NovelGhostwriteProgress: Equatable, Sendable {
    let binding: NovelSessionBinding
    var phase: NovelGhostwritePhase
    var pauseReason: NovelGhostwritePauseReason?
    var detailMessage: String?
    var candidateID: NovelCandidateID?
    var chapterPlanDigest: String?
    var autoCollectedCandidateIDs: Set<NovelCandidateID>
    let startedAt: Date
    /// 本批目标章数，开跑时固定，范围 1...10。
    var targetChapterCount: Int
    /// 本批已收录并同步成功的章数。
    var completedChapterCount: Int
    /// 1-based 当前推进中的章序号（不超过 target）。
    var currentChapterIndex: Int
    /// 上一章合同摘要，供自动拟下一章计划注入。
    var lastCompletedPlanSummary: String?
    /// 已收录并清合同、等待同步成功后才计入 completed 的待记账标记。
    /// 防止 syncFailed 续跑时少计章、越过本批上限再写一章。
    var pendingSyncChapterCredit: Bool
    /// 本章已累计的质量失败次数（验收/复读等）。
    var qualityAttemptIndex: Int
    /// 本章最多允许的质量失败次数（默认 3：失败未满 3 可自动改写）。
    var maxQualityAttempts: Int
    /// 最近一次质量失败回执（注入改写 / 润修预填）。
    var lastFailureReceipt: NovelGhostwriteFailureReceipt?
    /// 本章已作废、禁止再验的候选。
    var supersededCandidateIDs: Set<NovelCandidateID>
    /// 最近失败指纹环，用于检测空转。
    var recentFailureFingerprints: [String]
    /// 人工润修 brief：仅下一次写稿消费，写完清空。
    var revisionBriefOverride: String?
    /// 本章是否已做过一次 mustNot 薄升级（每章最多一次）。
    var didThinContractAmendThisChapter: Bool
    /// 本批合同薄升级账本（可审计，不进正史）。
    var contractAmendments: [NovelGhostwriteContractAmendment]
    /// 同步基建已自动重试次数（每次进入 await 同步前可清零或按次累加）。
    var infraRetryCount: Int

    init(
        binding: NovelSessionBinding,
        phase: NovelGhostwritePhase,
        pauseReason: NovelGhostwritePauseReason? = nil,
        detailMessage: String? = nil,
        candidateID: NovelCandidateID? = nil,
        chapterPlanDigest: String? = nil,
        autoCollectedCandidateIDs: Set<NovelCandidateID> = [],
        startedAt: Date,
        targetChapterCount: Int = 1,
        completedChapterCount: Int = 0,
        currentChapterIndex: Int = 1,
        lastCompletedPlanSummary: String? = nil,
        pendingSyncChapterCredit: Bool = false,
        qualityAttemptIndex: Int = 0,
        maxQualityAttempts: Int = NovelGhostwriteHeal.defaultMaxQualityAttempts,
        lastFailureReceipt: NovelGhostwriteFailureReceipt? = nil,
        supersededCandidateIDs: Set<NovelCandidateID> = [],
        recentFailureFingerprints: [String] = [],
        revisionBriefOverride: String? = nil,
        didThinContractAmendThisChapter: Bool = false,
        contractAmendments: [NovelGhostwriteContractAmendment] = [],
        infraRetryCount: Int = 0
    ) {
        self.binding = binding
        self.phase = phase
        self.pauseReason = pauseReason
        self.detailMessage = detailMessage
        self.candidateID = candidateID
        self.chapterPlanDigest = chapterPlanDigest
        self.autoCollectedCandidateIDs = autoCollectedCandidateIDs
        self.startedAt = startedAt
        self.targetChapterCount = NovelGhostwriteBatch.clamp(targetChapterCount)
        self.completedChapterCount = max(0, completedChapterCount)
        self.currentChapterIndex = max(1, currentChapterIndex)
        self.lastCompletedPlanSummary = lastCompletedPlanSummary
        self.pendingSyncChapterCredit = pendingSyncChapterCredit
        self.qualityAttemptIndex = max(0, qualityAttemptIndex)
        self.maxQualityAttempts = NovelGhostwriteHeal.clampMaxAttempts(maxQualityAttempts)
        self.lastFailureReceipt = lastFailureReceipt
        self.supersededCandidateIDs = supersededCandidateIDs
        self.recentFailureFingerprints = Array(recentFailureFingerprints.suffix(3))
        self.revisionBriefOverride = revisionBriefOverride
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .flatMap { $0.isEmpty ? nil : $0 }
        self.didThinContractAmendThisChapter = didThinContractAmendThisChapter
        self.contractAmendments = contractAmendments
        self.infraRetryCount = max(0, infraRetryCount)
    }

    var batchProgressLabel: String {
        "第 \(min(currentChapterIndex, targetChapterCount))/\(targetChapterCount) 章"
    }

    var statusLabel: String {
        let batch = targetChapterCount > 1 ? " · \(batchProgressLabel)" : ""
        switch phase {
        case .writing:
            if qualityAttemptIndex > 0 {
                return "代笔中\(batch) · 改写 \(qualityAttemptIndex)/\(maxQualityAttempts)"
            }
            return "代笔中\(batch) · 写整章"
        case .accepting: return "代笔中\(batch) · 核对计划"
        case .collecting: return "代笔中\(batch) · 自动收录"
        case .syncing: return "代笔中\(batch) · 剧情同步"
        case .planning: return "代笔中\(batch) · 拟定计划"
        case .revising: return "代笔中\(batch) · 按意见润修"
        case .paused:
            if pauseReason == .cancelled { return "代笔已取消\(batch)" }
            return "代笔已暂停\(batch)"
        case .waitingUser:
            switch pauseReason {
            case .batchCompleted:
                let done = targetChapterCount > 1
                    ? " · \(completedChapterCount)/\(targetChapterCount) 章"
                    : ""
                return "本批已完成\(done)"
            case .chapterCompleted:
                return "本章已完成"
            case .planProposedForNewBatch:
                return "已拟定计划 · 待确认"
            case .healBudgetExhausted:
                return "代笔待润修\(batch)"
            default:
                return "代笔等待继续\(batch)"
            }
        case .failed: return "代笔失败\(batch)"
        }
    }

    /// 面板只读看板：短步骤码；暂停原因留给 `detailMessage`，避免重复长句。
    var boardStepSummary: String {
        // 「已收 k/N」与 statusLabel 的「第 i/N 章」区分，避免两行两套 x/5 误解。
        let batchSuffix = targetChapterCount > 1
            ? " · 已收\(completedChapterCount)/\(targetChapterCount)"
            : ""
        switch phase {
        case .writing:
            if qualityAttemptIndex > 0 {
                return "改写 \(qualityAttemptIndex)/\(maxQualityAttempts)" + batchSuffix
            }
            return "写整章中" + batchSuffix
        case .accepting:
            return "写✓ · 验收中" + batchSuffix
        case .collecting:
            return "写✓验✓ · 收录中" + batchSuffix
        case .syncing:
            return "写✓验✓收✓ · 同步中" + batchSuffix
        case .planning:
            return "同✓ · 拟定下一章" + batchSuffix
        case .revising:
            return "润修中" + batchSuffix
        case .paused, .waitingUser, .failed:
            if pauseReason == .chapterCompleted || pauseReason == .batchCompleted {
                return "写✓验✓收✓同✓" + batchSuffix
            }
            if pauseReason == .planProposedForNewBatch {
                return "同✓ · 计划已拟定" + batchSuffix
            }
            if pauseReason == .healBudgetExhausted {
                return "待润修" + batchSuffix
            }
            if let reason = pauseReason, reason.requiresRewriteOnContinue {
                return "已中断·将重写" + batchSuffix
            }
            return "已中断" + batchSuffix
        }
    }

    var isBatchComplete: Bool {
        completedChapterCount >= targetChapterCount
    }

    /// 面板「继续」与 `start` 续跑共用：本批未完成，且不是完批/取消后的新开。
    /// 有 `pendingSyncChapterCredit` 时必须先续跑记账，避免少计章。
    var shouldContinueSameBatch: Bool {
        if isBatchComplete { return false }
        if pendingSyncChapterCredit { return true }
        switch pauseReason {
        case .batchCompleted, .chapterCompleted, .cancelled, nil:
            return false
        case .userPaused, .acceptanceFailed, .obviousRepetition, .blockingContinuity,
             .continuityAuditIncomplete, .collectFailed, .syncFailed, .incompleteCandidate,
             .planMismatch, .planProposalFailed, .planProposedForNewBatch,
             .healBudgetExhausted, .infrastructureFailed:
            return true
        }
    }

    var canResumeWithoutConfirmedPlan: Bool {
        guard shouldContinueSameBatch else { return false }
        if pendingSyncChapterCredit { return true }
        return pauseReason?.resumesWithoutConfirmedPlan == true
    }

    /// 质量失败后续跑时是否必须丢弃当前候选。
    var mustRewriteCandidateOnResume: Bool {
        pauseReason?.requiresRewriteOnContinue == true
    }

    /// 登记一次质量失败；若仍可自动改写则准备 rewrite 状态并返回 true。
    /// - Returns: `(willRewrite, blockedByFingerprint)`
    @discardableResult
    mutating func registerQualityFailureForHeal(
        reason: NovelGhostwritePauseReason,
        receipt: NovelGhostwriteFailureReceipt,
        failedCandidateID: NovelCandidateID?
    ) -> (willRewrite: Bool, blockedByFingerprint: Bool) {
        qualityAttemptIndex += 1
        lastFailureReceipt = receipt
        if let failedCandidateID {
            supersededCandidateIDs.insert(failedCandidateID)
            if candidateID == failedCandidateID {
                candidateID = nil
            }
        }
        var prints = recentFailureFingerprints
        prints.append(receipt.fingerprint)
        recentFailureFingerprints = Array(prints.suffix(3))

        let stuck = NovelGhostwriteHeal.isStuckOnSameFingerprint(recentFailureFingerprints)
        let canHeal = NovelGhostwriteHeal.shouldAutoRewrite(
            afterFailureCount: qualityAttemptIndex,
            maxAttempts: maxQualityAttempts,
            reason: reason,
            recentFingerprints: recentFailureFingerprints
        )
        if canHeal {
            phase = .writing
            pauseReason = nil
            detailMessage = "验收未过，自动改写 \(qualityAttemptIndex)/\(maxQualityAttempts)…"
            return (true, false)
        }
        return (false, stuck)
    }

    /// 本章成功收录后清 heal 状态。
    mutating func resetChapterHealState() {
        qualityAttemptIndex = 0
        lastFailureReceipt = nil
        supersededCandidateIDs = []
        recentFailureFingerprints = []
        revisionBriefOverride = nil
        didThinContractAmendThisChapter = false
        infraRetryCount = 0
    }

    /// Tier2 薄升级成功后：重置质量 attempt，再给一轮自动写。
    mutating func prepareAfterThinContractAmend(
        amendment: NovelGhostwriteContractAmendment,
        newPlanDigest: String?
    ) {
        contractAmendments.append(amendment)
        didThinContractAmendThisChapter = true
        // 薄升级后只再给一轮自动写：下次质量失败即停（不再整段 Tier1 预算）。
        qualityAttemptIndex = max(0, maxQualityAttempts - 1)
        recentFailureFingerprints = []
        chapterPlanDigest = newPlanDigest
        phase = .writing
        pauseReason = nil
        detailMessage = amendment.kind == .alignSingleMust
            ? "已放宽 1 条必发生措辞，再写一轮…"
            : "已把复读节拍写入禁止项，再写一轮…"
        // revisionBriefOverride 由调用方设置；此处不强制清空。
    }

    /// 是否适合展示「按审稿意见润修」入口。
    var shouldOfferRevisionSheet: Bool {
        switch pauseReason {
        case .healBudgetExhausted, .acceptanceFailed, .obviousRepetition,
             .blockingContinuity, .continuityAuditIncomplete:
            return true
        default:
            return false
        }
    }

    /// - Parameter sourceDraft: 仅人工润修时附上一稿正文（有界），便于改而非空写。
    static func writeUserText(
        receipt: NovelGhostwriteFailureReceipt?,
        revisionBrief: String?,
        sourceDraft: String? = nil
    ) -> String {
        if let brief = revisionBrief?.trimmingCharacters(in: .whitespacesAndNewlines),
           !brief.isEmpty {
            let clippedBrief = brief.count > 2_400 ? String(brief.prefix(2_400)) + "…" : brief
            var parts = [
                "请在上一稿基础上按润修要求改写完整一章（保留可用段落，针对意见修改；开篇勿复读近期节拍）。",
                "【润修要求】\n\(clippedBrief)",
            ]
            if let draft = sourceDraft?.trimmingCharacters(in: .whitespacesAndNewlines),
               !draft.isEmpty {
                let clippedDraft = draft.count > 12_000
                    ? String(draft.prefix(12_000)) + "…"
                    : draft
                parts.append("【上一稿正文】\n\(clippedDraft)")
            }
            return parts.joined(separator: "\n\n")
        }
        return NovelGhostwriteHeal.writeUserText(receipt: receipt)
    }

    /// 同步成功后把待记账章计入 completed；返回是否已达本批目标。
    mutating func applyPendingSyncChapterCredit() -> Bool {
        guard pendingSyncChapterCredit else { return isBatchComplete }
        pendingSyncChapterCredit = false
        completedChapterCount += 1
        currentChapterIndex = min(completedChapterCount + 1, targetChapterCount)
        chapterPlanDigest = nil
        pauseReason = nil
        detailMessage = nil
        resetChapterHealState()
        return isBatchComplete
    }
}
