import Foundation

enum NovelGhostwritePhase: Equatable, Sendable {
    case writing
    case accepting
    case collecting
    case syncing
    case paused
    case waitingUser
    case failed
}

enum NovelGhostwritePauseReason: Equatable, Sendable {
    case userPaused
    case acceptanceFailed
    case obviousRepetition
    case blockingContinuity
    case collectFailed
    case syncFailed
    case incompleteCandidate
    case planMismatch
    case chapterCompleted
    case cancelled

    var displayMessage: String {
        switch self {
        case .userPaused: "已暂停代笔。"
        case .acceptanceFailed: "本章合同验收未通过，已保留候选。"
        case .obviousRepetition: "检测到明显复读，已暂停自动收录。"
        case .blockingContinuity: "连续性检查发现严重问题，已暂停自动收录。"
        case .collectFailed: "自动收录失败，已暂停代笔。"
        case .syncFailed: "剧情同步未完成，代笔已暂停，不会开始下一章。"
        case .incompleteCandidate: "本章正文不完整，已暂停代笔。"
        case .planMismatch: "候选与当前合同不一致，已暂停代笔。"
        case .chapterCompleted: "本章已收录并同步。请确认下一章合同后再继续。"
        case .cancelled: "代笔已取消。"
        }
    }

    static func failedReason(from error: Error) -> NovelGhostwritePauseReason {
        if let novel = error as? NovelError {
            switch novel {
            case .invalidInput(let message) where message.contains("不完整"):
                return .incompleteCandidate
            case .invalidInput(let message) where message.contains("合同"):
                return .planMismatch
            default:
                break
            }
        }
        return .acceptanceFailed
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

    var statusLabel: String {
        switch phase {
        case .writing: "代笔中 · 写整章"
        case .accepting: "代笔中 · 验收合同"
        case .collecting: "代笔中 · 自动收录"
        case .syncing: "代笔中 · 剧情同步"
        case .paused: "代笔已暂停"
        case .waitingUser: "代笔等待继续"
        case .failed: "代笔失败"
        }
    }

    /// 面板只读看板：短步骤码；暂停原因留给 `detailMessage`，避免重复长句。
    var boardStepSummary: String {
        switch phase {
        case .writing:
            return "写整章中"
        case .accepting:
            return "写✓ · 验收中"
        case .collecting:
            return "写✓验✓ · 收录中"
        case .syncing:
            return "写✓验✓收✓ · 同步中"
        case .paused, .waitingUser, .failed:
            if pauseReason == .chapterCompleted {
                return "写✓验✓收✓同✓"
            }
            return "已中断"
        }
    }
}
