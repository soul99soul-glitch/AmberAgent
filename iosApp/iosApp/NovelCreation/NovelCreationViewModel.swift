import Foundation
import Observation
import UIKit

func novelRunBackgroundLeaseID(for runID: NovelRunID) -> String {
    "novel-run-\(runID)"
}

func novelStateSyncBackgroundLeaseID(for ownerID: UUID) -> String {
    "novel-state-sync-\(ownerID.uuidString)"
}

func novelContinuityBackgroundLeaseID(for ownerID: UUID) -> String {
    "novel-continuity-\(ownerID.uuidString)"
}

func novelGhostwriteBackgroundLeaseID(
    projectID: NovelProjectID,
    branchID: NovelBranchID
) -> String {
    "novel-ghostwrite-\(projectID)-\(branchID)"
}

func isProtectedNovelBackgroundLeaseID(_ leaseID: String) -> Bool {
    leaseID.hasPrefix("novel-run-") ||
        leaseID.hasPrefix("novel-state-sync-") ||
        leaseID.hasPrefix("novel-continuity-") ||
        leaseID.hasPrefix("novel-ghostwrite-")
}

enum NovelProjectImportChoice: Equatable, Sendable {
    case reject
    case replace
    case keepBoth
}

enum NovelProjectImportResult: Equatable, Sendable {
    case selected(NovelProjectID)
    case committedNeedsReload(NovelProjectID)
}

enum NovelQuickStartStatus: Equatable, Sendable {
    case idle
    case starting(run: NovelActiveRunRecord)
    case generating(runID: NovelRunID)
    case awaitingUser(promptMessageID: NovelMessageID)
    case failed(message: String)
    case persistenceBlocked(runID: NovelRunID, message: String)
    case refreshFailed(message: String)
}

enum NovelBranchSelectionResult: Equatable, Sendable {
    case selected
    case requiresStoppingActiveRun
    case failed
}

struct NovelComposerDraft: Equatable, Sendable {
    let text: String
    let injectionOverrides: NovelInjectionOverrides
    let inputBudgetTokens: Int

    static let empty = NovelComposerDraft(
        text: "",
        injectionOverrides: .none,
        inputBudgetTokens: 16_000
    )
}

struct NovelComposerDraftOwner: Hashable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

private struct NovelQuickStartOwner: Hashable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

private struct NovelAutomaticStateSyncTarget: Equatable, Hashable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

private struct NovelAutomaticStateSyncFailure: Equatable, Sendable {
    let target: NovelAutomaticStateSyncTarget
    let message: String
}

private struct NovelContinuityAuditFailure: Equatable, Sendable {
    let target: NovelAutomaticStateSyncTarget
    let message: String
}

private enum NovelBackgroundGenerationProbe {
    case active
    case inactive
    case unknown
}

struct NovelStateSyncActivity: Equatable, Sendable {
    enum Phase: Equatable, Sendable {
        case preparing
        case analyzing
    }

    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let pendingID: NovelPendingOperationID
    let phase: Phase
    let startedAt: Date
    let requestStartedAt: Date?
    let completedCharacters: Int
    let totalCharacters: Int?
    let completedChunks: Int

    var completionFraction: Double? {
        guard let totalCharacters, totalCharacters > 0 else { return nil }
        return min(1, max(0, Double(completedCharacters) / Double(totalCharacters)))
    }

    /// Percent bar only after at least one durable chunk lands — first long model
    /// call would otherwise sit at 0% and look stuck.
    var displayedCompletionFraction: Double? {
        guard completedCharacters > 0 else { return nil }
        return completionFraction
    }

    var displayedPercent: Int? {
        displayedCompletionFraction.map { Int(($0 * 100).rounded(.down)) }
    }

    var statusTitle: String {
        switch phase {
        case .preparing: "正在准备剧情状态"
        case .analyzing: "正在同步剧情状态"
        }
    }

    /// Preferred-cap estimate so banners do not imply "segment 1 forever" on long books.
    var estimatedTotalSegments: Int? {
        guard let totalCharacters, totalCharacters > 0 else { return nil }
        return NovelManualSyncChunker.estimatedSegmentCount(
            manuscriptCharacterCount: totalCharacters
        )
    }

    var segmentedRebuildHint: String? {
        guard let estimatedTotalSegments, estimatedTotalSegments > 1 else { return nil }
        return "分段读取正文并更新剧情状态，大项目会较久。"
    }

    /// Secondary copy for banners: chunk, word count, elapsed — always concrete
    /// even before the first durable percent is available.
    /// `streamedCharacters` 是当前段已流式到达的字数（>0 时替换「完成后才更新」的静态提示）。
    func progressDetail(elapsedSeconds: Int, streamedCharacters: Int = 0) -> String {
        let elapsed = max(0, elapsedSeconds)
        let streamedDetail = streamedCharacters > 0 ? " · 本段已生成 \(streamedCharacters) 字" : nil
        switch phase {
        case .preparing:
            return "已等待 \(elapsed) 秒 · 正在准备请求"
        case .analyzing:
            let currentSegment = completedChunks + 1
            if let percent = displayedPercent {
                let chunkDetail = completedChunks > 0
                    ? " · 已完成 \(completedChunks) 段"
                    : ""
                if let estimated = estimatedTotalSegments, estimated > 1 {
                    return "正文已处理 \(percent)%\(chunkDetail) / 约 \(estimated) 段 · 已等待 \(elapsed) 秒"
                        + (streamedDetail ?? "")
                }
                return "正文已处理 \(percent)%\(chunkDetail) · 已等待 \(elapsed) 秒"
                    + (streamedDetail ?? "")
            }
            if let totalCharacters, totalCharacters > 0 {
                if let estimated = estimatedTotalSegments, estimated > 1 {
                    let streamed = streamedDetail ?? " · 本段完成后才会更新进度"
                    if completedChunks > 0 {
                        return "正文共 \(totalCharacters) 字 · 约 \(estimated) 段 · 已完成 \(completedChunks) 段 · 正在处理第 \(currentSegment) 段 · 已等待 \(elapsed) 秒" + streamed
                    }
                    return "正文共 \(totalCharacters) 字 · 约 \(estimated) 段 · 正在处理第 \(currentSegment) 段 · 已等待 \(elapsed) 秒" + streamed
                }
                // Single preferred segment: avoid "第 1 段" which sounds stuck forever.
                return "正文共 \(totalCharacters) 字 · 全文分析中 · 已等待 \(elapsed) 秒"
                    + (streamedDetail ?? " · 模型返回后才会更新进度")
            }
            return "正在分析第 \(currentSegment) 段 · 已等待 \(elapsed) 秒" + (streamedDetail ?? "")
        }
    }

    static func preparing(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        pendingID: NovelPendingOperationID,
        startedAt: Date
    ) -> NovelStateSyncActivity {
        NovelStateSyncActivity(
            projectID: projectID,
            branchID: branchID,
            pendingID: pendingID,
            phase: .preparing,
            startedAt: startedAt,
            requestStartedAt: nil,
            completedCharacters: 0,
            totalCharacters: nil,
            completedChunks: 0
        )
    }

    static func project(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        pending: NovelPendingOperationRecord,
        snapshot: NovelProjectSnapshot,
        attemptOperationID: NovelOperationID,
        startedAt: Date
    ) -> NovelStateSyncActivity? {
        guard pending.kind == .manualSync,
              let chapters = try? NovelFactTransactionReducer.decodeManualPayload(
                  pending.selectedText
              ) else { return nil }
        let manuscript = NovelFactTransactionReducer.manualRebuildManuscript(chapters)
        let progress = pending.manualSyncProgress
        let requestStartedAt = snapshot.generationReceipts
            .filter {
                $0.factTransaction?.pendingID == pending.id &&
                    $0.factTransaction?.attemptOperationID == attemptOperationID
            }
            .max(by: { $0.createdAt < $1.createdAt })?
            .createdAt
        return NovelStateSyncActivity(
            projectID: projectID,
            branchID: branchID,
            pendingID: pending.id,
            phase: .analyzing,
            startedAt: startedAt,
            requestStartedAt: requestStartedAt,
            completedCharacters: min(progress?.consumedCharacterCount ?? 0, manuscript.count),
            totalCharacters: manuscript.count,
            completedChunks: progress?.completedChunks.count ?? 0
        )
    }
}



@MainActor
@Observable
final class NovelCreationViewModel {
    var projects: [NovelProjectSummary] = []
    var selectedProjectID: NovelProjectID?
    var selectedBranchID: NovelBranchID?
    var projectSnapshot: NovelProjectSnapshot?
    var branchSnapshot: NovelBranchSnapshot?
    var injectionPreview: NovelInjectionPreviewSnapshot?
    /// 剧情矛盾检查的结果。**只活在内存里**:它是一份诊断报告,不是故事状态的一部分,
    /// 退出项目或换分支就丢弃,需要重扫(见 `NovelContinuityAuditReport` 的说明)。
    ///
    /// 归属由**读取侧**判定,不靠「每个切换点手动清一次」——新建/导入项目、fork、
    /// 删分支走的是 `reloadSelection` 而不是 `selectProject`/`selectBranch`,
    /// 靠手动清必然漏,漏了就会把上一个项目的报告显示在当前项目的剧情页上。
    var continuityAudit: NovelContinuityAuditReport? {
        guard let report = continuityAuditReport,
              report.projectID == selectedProjectID,
              report.branchID == selectedBranchID else { return nil }
        return report
    }

    private(set) var continuityAuditReport: NovelContinuityAuditReport?
    private var continuityAuditPlanStorage: NovelContinuityAuditPlan?
    private var continuityAuditFailureStorage: NovelContinuityAuditFailure?
    @ObservationIgnored private var continuityAuditExpirationOwnerID: UUID?
    private(set) var isPlanningContinuity = false
    private(set) var isAuditingContinuity = false
    @ObservationIgnored private var continuityAuditTask: Task<Void, Never>?
    var isLoading = false
    /// loadProjects 并发防护：首页 onAppear 与项目列表 .task 可并发触发同一加载，
    /// 较早响应不得回写覆盖较晚结果（latest-wins）。
    @ObservationIgnored private var projectsLoadRevision = 0
    private(set) var isPerforming = false
    private(set) var stateSyncActivity: NovelStateSyncActivity?
    private(set) var projectListLoadError: String?
    var errorMessage: String?
    var reloadNoticeMessage: String?
    private(set) var reloadNoticeProjectID: NovelProjectID?
    private(set) var reloadNoticeBranchID: NovelBranchID?
    private var quickStartStatuses: [NovelQuickStartOwner: NovelQuickStartStatus] = [:]
    private(set) var quickStartStartingProjectID: NovelProjectID?
    private(set) var quickStartStartingRun: NovelActiveRunRecord?

    @ObservationIgnored private let creation: any NovelCreation
    @ObservationIgnored private var selectionToken = UUID()
    @ObservationIgnored private var selectionIntentToken = UUID()
    @ObservationIgnored private var quickStartTasks: [NovelQuickStartOwner: Task<Void, Never>] = [:]
    @ObservationIgnored private var quickStartTaskRunIDs: [NovelQuickStartOwner: NovelRunID] = [:]
    @ObservationIgnored private var quickStartCreationStartRunIDs: Set<NovelRunID> = []
    @ObservationIgnored private var cancelledQuickStartRunIDs: Set<NovelRunID> = []
    @ObservationIgnored private var composerDrafts: [NovelComposerDraftOwner: NovelComposerDraft] = [:]
    @ObservationIgnored private var lastSelectedBranchIDs: [NovelProjectID: NovelBranchID] = [:]
    @ObservationIgnored private var operationOwnerID: UUID?
    @ObservationIgnored private var stateSyncActivityOwnerID: UUID?
    @ObservationIgnored private var stateSyncActivityTask: Task<Void, Never>?
    @ObservationIgnored private var stateSyncReportedWorkByOwnerID: [UUID: Int64] = [:]
    /// Keep the visible progress strip across outer heal `perform()`s.
    @ObservationIgnored private var automaticStateSyncKeepAlive = false
    @ObservationIgnored private var manualStateSyncTask: Task<Void, Never>?
    @ObservationIgnored private var manualStateSyncPendingID: NovelPendingOperationID?
    private var manualStateSyncTarget: NovelAutomaticStateSyncTarget?
    @ObservationIgnored private var automaticStateSyncTask: Task<Void, Never>?
    @ObservationIgnored private var automaticStateSyncTarget: NovelAutomaticStateSyncTarget?
    @ObservationIgnored private var queuedAutomaticStateSyncTarget: NovelAutomaticStateSyncTarget?
    private var automaticStateSyncPresentationTarget: NovelAutomaticStateSyncTarget?
    private var automaticStateSyncFailure: NovelAutomaticStateSyncFailure?
    /// Last error from a state-sync `perform` (reportsError: false path). Used when
    /// pending.lastError was not written yet so retry banners do not collapse to a
    /// useless generic "没有完成，请重试".
    @ObservationIgnored private var lastStateSyncOperationError: String?
    @ObservationIgnored private var lastStateSyncOperationCause: Error?
    /// Targets the user explicitly stopped. Prevents cancel from looking like a no-op when
    /// `needsSync` remains true and would immediately reschedule the same work.
    private var userSuppressedStateSyncTargets: Set<NovelAutomaticStateSyncTarget> = []
    /// Targets whose Stop was pressed but mutation/task teardown has not finished yet.
    /// Keeps a visible “正在停止” state so selection block is not unexplained.
    private var stateSyncStoppingTargets: Set<NovelAutomaticStateSyncTarget> = []
    /// 领域 mutation 广播的订阅任务；weak self 退出，不占额外生命周期管理。
    @ObservationIgnored private var mutationEventsTask: Task<Void, Never>?
    /// 本 VM 发起的 mutation 的 operationID 集合，用于抑制广播回声；成功广播到达即移除。
    @ObservationIgnored private var ownMutationOperationIDs: Set<NovelOperationID> = []
    /// Coalesce external mutation refreshes (discussion tools often fire many
    /// commits in one agent turn). Serial full-project reloads freeze large novels.
    @ObservationIgnored private var pendingExternalMutationProjectIDs: Set<NovelProjectID> = []
    @ObservationIgnored private var externalMutationRefreshTask: Task<Void, Never>?

    init(creation: any NovelCreation) {
        self.creation = creation
        // 非 UI 写入源（讨论工具 executor 等）落盘后广播的 mutation 事件：复用
        // refreshCurrentSelection 一处刷新项目列表与选中快照，否则顶栏标题/面板/
        // 设定页/列表行要等 run 终态甚至重进才更新。自身 UI 写入的回流是幂等重复
        // 刷新（latest-wins），不额外抑制；VM 释放后下一条事件让任务自行退出。
        mutationEventsTask = Task { @MainActor [weak self] in
            guard let stream = await self?.creation.mutationEvents() else { return }
            for await event in stream {
                guard let self else { return }
                // 自身发起的写入已在自己的流程里刷新；回声刷新会与这些流程的
                // 中间状态竞争（分支选择/快照序列），必须按 operationID 抑制。
                if self.ownMutationOperationIDs.remove(event.operationID) != nil { continue }
                self.scheduleExternalMutationRefresh(projectID: event.projectID)
            }
        }
    }

    /// Batch tool writes into one UI refresh after a short quiet window.
    private func scheduleExternalMutationRefresh(projectID: NovelProjectID) {
        pendingExternalMutationProjectIDs.insert(projectID)
        guard externalMutationRefreshTask == nil else { return }
        externalMutationRefreshTask = Task { @MainActor [weak self] in
            // 150ms covers typical multi-tool bursts without making title/panel lag feel stuck.
            try? await Task.sleep(nanoseconds: 150_000_000)
            guard let self else { return }
            let projectIDs = self.pendingExternalMutationProjectIDs
            self.pendingExternalMutationProjectIDs = []
            self.externalMutationRefreshTask = nil
            for id in projectIDs {
                _ = try? await self.refreshCurrentSelection(projectID: id)
            }
        }
    }

    /// 跨进程：写入本批代笔进度 sidecar；不该保留时删除。
    func persistGhostwriteBatchProgress(_ progress: NovelGhostwriteProgress) {
        let record = NovelGhostwriteBatchProgressRecord.from(progress: progress)
        Task { @MainActor in
            do {
                if record.shouldPersist {
                    try await creation.saveGhostwriteBatchProgress(record)
                } else {
                    try await creation.removeGhostwriteBatchProgress(
                        projectID: record.projectID,
                        branchID: record.branchID
                    )
                }
            } catch {
                // 进度落盘失败不挡代笔主路径；下次 mutate 会再试。
            }
        }
    }

    func loadGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async -> NovelGhostwriteProgress? {
        do {
            guard let record = try await creation.loadGhostwriteBatchProgress(
                projectID: projectID,
                branchID: branchID
            ) else { return nil }
            return record.makeProgress()
        } catch {
            return nil
        }
    }

    func clearGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) {
        Task { @MainActor in
            try? await creation.removeGhostwriteBatchProgress(
                projectID: projectID,
                branchID: branchID
            )
        }
    }

    func composerDraft(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> NovelComposerDraft {
        composerDrafts[NovelComposerDraftOwner(projectID: projectID, branchID: branchID)] ?? .empty
    }

    func saveComposerDraft(
        _ draft: NovelComposerDraft,
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) {
        let owner = NovelComposerDraftOwner(projectID: projectID, branchID: branchID)
        if draft == .empty {
            composerDrafts[owner] = nil
        } else {
            composerDrafts[owner] = draft
        }
    }

    func acquireSessionOperation(ownerID: UUID) -> Bool {
        acquireOperation(ownerID: ownerID)
    }

    func releaseSessionOperation(ownerID: UUID) {
        releaseOperation(ownerID: ownerID)
    }

    private func acquireOperation(ownerID: UUID) -> Bool {
        guard !isPerforming else { return false }
        operationOwnerID = ownerID
        isPerforming = true
        return true
    }

    private func releaseOperation(ownerID: UUID) {
        guard operationOwnerID == ownerID else { return }
        operationOwnerID = nil
        isPerforming = false
    }

    var canMutate: Bool {
        guard !isPerforming, quickStartStartingRun == nil, let projectSnapshot else { return false }
        return projectSnapshot.access == .readWrite &&
            !requiresReload &&
            !projectSnapshot.activeRuns.contains(where: { $0.status == .running })
    }

    var isProjectSelectionBlocked: Bool {
        isPerforming ||
            manualStateSyncTarget != nil ||
            automaticStateSyncPresentationTarget != nil ||
            !stateSyncStoppingTargets.isEmpty
    }

    var isStateSyncOperationRunning: Bool {
        manualStateSyncTarget != nil ||
            automaticStateSyncPresentationTarget != nil ||
            !stateSyncStoppingTargets.isEmpty
    }

    func canCancelAutomaticStateSync(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> Bool {
        let target = NovelAutomaticStateSyncTarget(
            projectID: projectID,
            branchID: branchID
        )
        // Stopping targets are no longer cancellable — Stop already took effect.
        return (manualStateSyncTarget == target || automaticStateSyncPresentationTarget == target)
            && !stateSyncStoppingTargets.contains(target)
    }

    func isStateSyncStopping(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> Bool {
        stateSyncStoppingTargets.contains(
            NovelAutomaticStateSyncTarget(projectID: projectID, branchID: branchID)
        )
    }

    /// User-visible phase copy for sync banners (running / preparing / stopping).
    func stateSyncStatusTitle(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> String? {
        let target = NovelAutomaticStateSyncTarget(projectID: projectID, branchID: branchID)
        if stateSyncStoppingTargets.contains(target) {
            return "正在停止剧情同步"
        }
        guard manualStateSyncTarget == target ||
                automaticStateSyncPresentationTarget == target else { return nil }
        if let activity = stateSyncActivity,
           activity.projectID == projectID,
           activity.branchID == branchID {
            return activity.statusTitle
        }
        return "正在按正文对齐剧情指针"
    }

    func cancelAutomaticStateSync(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        suppressReschedule: Bool = true
    ) {
        guard canCancelAutomaticStateSync(projectID: projectID, branchID: branchID) else {
            return
        }
        let target = NovelAutomaticStateSyncTarget(projectID: projectID, branchID: branchID)
        // Only an explicit user Stop suppresses automatic reschedule. System lease
        // expiration cancels the current mutation but keeps the durable retry path live.
        if suppressReschedule {
            userSuppressedStateSyncTargets.insert(target)
        }
        // Keep a short “正在停止” presentation until task/mutation teardown finishes so
        // selection block is not unexplained after the running banner disappears.
        stateSyncStoppingTargets.insert(target)
        if let failure = automaticStateSyncFailure, failure.target == target {
            if errorMessage == failure.message {
                errorMessage = nil
            }
            automaticStateSyncFailure = nil
        }

        // Clear running presentation immediately so Stop is not a silent no-op.
        if automaticStateSyncPresentationTarget == target || automaticStateSyncTarget == target {
            automaticStateSyncTask?.cancel()
            automaticStateSyncPresentationTarget = nil
            automaticStateSyncTarget = nil
        }
        if manualStateSyncTarget == target {
            // Clear the task slot immediately. The task body previously gated cleanup on
            // `manualStateSyncPendingID == pendingID`, so cancel left an orphan task that
            // permanently blocked `startManualStateSyncRetry`.
            manualStateSyncTask?.cancel()
            manualStateSyncTask = nil
            manualStateSyncTarget = nil
            manualStateSyncPendingID = nil
        }
        if queuedAutomaticStateSyncTarget == target {
            queuedAutomaticStateSyncTarget = nil
        }
        if stateSyncActivity?.projectID == projectID,
           stateSyncActivity?.branchID == branchID,
           let activityOwnerID = stateSyncActivityOwnerID {
            stopStateSyncActivity(ownerID: activityOwnerID)
        }

        // Outer Task.cancel alone does not stop `perform` / model streaming. Cancel the
        // durable fact-sync mutation tasks so structured model requests receive cancellation.
        Task { @MainActor [weak self] in
            await self?.creation.cancelInFlightBackgroundMutations(projectID: projectID)
            // If no outer task remains to clear stopping (edge race), drop it once
            // mutation cancel returns and we are not mid-perform for another reason.
            guard let self else { return }
            if self.automaticStateSyncTask == nil,
               self.manualStateSyncTask == nil,
               !self.isPerforming {
                self.stateSyncStoppingTargets.remove(target)
            }
        }
    }

    var continuityAuditPlan: NovelContinuityAuditPlan? {
        guard let plan = continuityAuditPlanStorage,
              plan.projectID == selectedProjectID,
              plan.branchID == selectedBranchID else { return nil }
        return plan
    }

    var continuityAuditFailure: String? {
        guard let failure = continuityAuditFailureStorage,
              failure.target.projectID == selectedProjectID,
              failure.target.branchID == selectedBranchID else { return nil }
        return failure.message
    }

    var isContinuityOperationRunning: Bool {
        isPlanningContinuity || isAuditingContinuity
    }

    var continuityOperationTitle: String {
        isPlanningContinuity ? "正在准备剧情矛盾检查" : "正在通读全书正文"
    }

    var presentedMessage: String? {
        errorMessage ?? reloadNoticeMessage
    }

    var requiresReload: Bool {
        reloadNoticeProjectID != nil && reloadNoticeProjectID == selectedProjectID
    }

    var hasReloadRequirement: Bool {
        reloadNoticeProjectID != nil
    }

    var activeMaterials: [NovelMaterialRecord] {
        projectSnapshot?.materials.filter { !$0.isDeleted } ?? []
    }

    func effectiveAliases(for material: NovelMaterialRecord) -> [String] {
        guard let projectSnapshot else { return material.aliases }
        return NovelPresentation.effectiveAliases(
            for: material,
            project: projectSnapshot,
            branch: branchSnapshot
        )
    }

    var activeBranches: [NovelBranchRecord] {
        projectSnapshot?.branches.filter { $0.lifecycle == .active } ?? []
    }

    var quickStartStatus: NovelQuickStartStatus {
        guard let selectedProjectID, let selectedBranchID else { return .idle }
        let owner = NovelQuickStartOwner(
            projectID: selectedProjectID,
            branchID: selectedBranchID
        )
        if let status = quickStartStatuses[owner] {
            return status
        }
        guard let projectSnapshot,
              projectSnapshot.project.creationMode == .quickStart else {
            return .idle
        }
        if let running = projectSnapshot.activeRuns.first(where: {
            $0.branchID == selectedBranchID &&
                $0.kind == .quickStart &&
                $0.status == .running
        }) {
            return .generating(runID: running.id)
        }
        let answeredPromptIDs = Set(projectSnapshot.sessions.flatMap { session in
            session.messages.compactMap { message -> NovelMessageID? in
                guard case .some(.askUserAnswer(let response)) = message.interaction else {
                    return nil
                }
                return response.promptMessageID
            }
        })
        if let promptMessage = projectSnapshot.sessions
            .first(where: { $0.branchID == selectedBranchID })?
            .messages
            .last(where: { message in
                guard message.role == .assistant,
                      case .some(.askUser) = message.interaction,
                      !answeredPromptIDs.contains(message.id),
                      let runID = message.runID else { return false }
                return projectSnapshot.activeRuns.first(where: { $0.id == runID })?.kind == .quickStart
            }) {
            return .awaitingUser(promptMessageID: promptMessage.id)
        }
        // 注意：这里用当前分支未过滤 isResolved 的 settingProposals 判空，
        // 与卡片列表用的 activeSettingProposals 不一致——用户全部接受/拒绝后
        // 仍会走到 idle 而非 failed。这是已知行为，不在本次改动范围内；
        // 「重新生成设定建议」入口走显式按钮，不依赖本状态机。
        if !projectSnapshot.settingProposals.contains(where: {
            $0.branchID == selectedBranchID
        }) {
            return .failed(message: "尚未生成创作建议，可以重新生成。")
        }
        return .idle
    }

    func automaticStateSyncFailureMessage(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> String? {
        let target = NovelAutomaticStateSyncTarget(projectID: projectID, branchID: branchID)
        guard automaticStateSyncFailure?.target == target,
              selectedProjectID == projectID,
              selectedBranchID == branchID,
              branchSnapshot?.branch.syncStatus == .needsSync else { return nil }
        return automaticStateSyncFailure?.message
    }

    func retryAutomaticStateSync(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) {
        let target = NovelAutomaticStateSyncTarget(projectID: projectID, branchID: branchID)
        guard automaticStateSyncFailure?.target == target else { return }
        retryStateSync(projectID: projectID, branchID: branchID)
    }

    func retryStateSync(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) {
        let target = NovelAutomaticStateSyncTarget(projectID: projectID, branchID: branchID)
        guard selectedProjectID == projectID,
              selectedBranchID == branchID else { return }

        // Already running this branch — progress banner should be visible.
        if manualStateSyncTarget == target ||
            automaticStateSyncPresentationTarget == target {
            return
        }
        if stateSyncStoppingTargets.contains(target) {
            publishAutomaticStateSyncFailure(
                target: target,
                message: "正在停止上次同步，请稍后再点重试。"
            )
            return
        }
        guard branchSnapshot?.branch.syncStatus == .needsSync else {
            // Nothing to do; clear stale failure strip.
            if automaticStateSyncFailure?.target == target {
                automaticStateSyncFailure = nil
            }
            return
        }
        if requiresReload {
            publishAutomaticStateSyncFailure(
                target: target,
                message: "请先重新载入项目，再重试剧情同步。"
            )
            return
        }
        if projectSnapshot?.access != .readWrite {
            publishAutomaticStateSyncFailure(
                target: target,
                message: "项目当前只读，无法同步剧情。"
            )
            return
        }

        // Explicit retry must always lift Stop suppress.
        userSuppressedStateSyncTargets.remove(target)
        lastStateSyncOperationError = nil
        lastStateSyncOperationCause = nil
        if let message = errorMessage,
           message.contains("剧情同步") || message.contains("剧情状态") {
            errorMessage = nil
        }

        // Force-start: do not go through scheduleAutomaticStateSync, which can
        // silently no-op when target/task bookkeeping is stale — that cleared the
        // failure banner first and looked like a dead button.
        if automaticStateSyncTask == nil {
            // Drop zombie bookkeeping so start is not skipped.
            if automaticStateSyncTarget == target {
                automaticStateSyncTarget = nil
            }
            if queuedAutomaticStateSyncTarget == target {
                queuedAutomaticStateSyncTarget = nil
            }
            startWorkspacePlotRelink(target)
            return
        }

        // Another sync task is live (other branch / stuck). Queue and keep feedback visible.
        queuedAutomaticStateSyncTarget = target
        publishAutomaticStateSyncFailure(
            target: target,
            message: "已排队，等待当前同步结束后自动开始"
        )
    }

    func canRetryStateSync(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> Bool {
        guard selectedProjectID == projectID,
              selectedBranchID == branchID,
              branchSnapshot?.branch.syncStatus == .needsSync,
              !requiresReload,
              projectSnapshot?.access == .readWrite else { return false }
        let target = NovelAutomaticStateSyncTarget(projectID: projectID, branchID: branchID)
        // Only block retry when THIS branch is already syncing/stopping.
        // Do not use global isProjectSelectionBlocked — isPerforming from an
        // unrelated action would disable the only recovery button.
        if manualStateSyncTarget == target ||
            automaticStateSyncPresentationTarget == target ||
            stateSyncStoppingTargets.contains(target) {
            return false
        }
        return true
    }

    func stateSyncRecoveryMessage(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> String? {
        let target = NovelAutomaticStateSyncTarget(projectID: projectID, branchID: branchID)
        guard selectedProjectID == projectID,
              selectedBranchID == branchID,
              branchSnapshot?.branch.syncStatus == .needsSync,
              manualStateSyncTarget != target,
              automaticStateSyncPresentationTarget != target,
              !stateSyncStoppingTargets.contains(target) else { return nil }
        if let failure = automaticStateSyncFailure, failure.target == target {
            return failure.message
        }
        // Leftover cancelled JSON extract is recovered by pointer relink.
        // Do not keep its lastError (or a generic "尚未同步") as a dead banner
        // that makes 重试同步 look like the only path while relink is already
        // running without progress chrome.
        return nil
    }

    func waitForBackgroundGeneration() async {
        var unknownProbeDelayMilliseconds = 1_000
        while !Task.isCancelled {
            switch await backgroundGenerationProbe() {
            case .inactive:
                return
            case .active:
                unknownProbeDelayMilliseconds = 1_000
                try? await Task.sleep(for: .milliseconds(250))
            case .unknown:
                try? await Task.sleep(for: .milliseconds(unknownProbeDelayMilliseconds))
                unknownProbeDelayMilliseconds = min(
                    unknownProbeDelayMilliseconds * 2,
                    4_000
                )
            }
        }
    }

    func resumeDetachedBackgroundGeneration() async {
        await creation.resumeDetachedGenerationRuns()
        await loadProjects(restoresSelection: false)
    }

    func interruptSessionForBackground(deadline: Date) async {
        guard !Task.isCancelled else { return }
        // 不在这里 cancel 自动/手动剧情同步：它们有独立 keepAlive 与 expire 回调。
        // 后台总中断若一并掐掉 sync，会和代笔 awaitGhostwriteStateSync 竞态成假 syncFailed
        // （正文已收录，批进度却停在待记账）。生成 run 仍由下方 interrupt 收口。
        continuityAuditTask?.cancel()
        queuedAutomaticStateSyncTarget = nil
        var projectIDs = Set(projects.map(\.id))
        if let selectedProjectID { projectIDs.insert(selectedProjectID) }
        if let quickStartStartingProjectID { projectIDs.insert(quickStartStartingProjectID) }
        for projectID in projectIDs.sorted(by: { $0.description < $1.description }) {
            guard !Task.isCancelled else { return }
            await interruptSessionForBackground(
                projectID: projectID,
                runID: nil,
                deadline: deadline
            )
        }
        guard !Task.isCancelled, Date() < deadline else { return }
        guard let summaries = try? await projectSummaries() else { return }
        guard !Task.isCancelled, Date() < deadline else { return }
        let freshProjectIDs = Set(summaries.map(\.id)).subtracting(projectIDs)
        for projectID in freshProjectIDs.sorted(by: { $0.description < $1.description }) {
            guard !Task.isCancelled, Date() < deadline else { return }
            await interruptSessionForBackground(
                projectID: projectID,
                runID: nil,
                deadline: deadline
            )
        }
    }

    private func backgroundGenerationProbe() async -> NovelBackgroundGenerationProbe {
        if isPerforming ||
            manualStateSyncTask != nil ||
            automaticStateSyncTask != nil ||
            continuityAuditTask != nil ||
            quickStartStartingProjectID != nil {
            return .active
        }
        let summaries: [NovelProjectSummary]
        do {
            summaries = try await projectSummaries()
        } catch {
            return Task.isCancelled ? .inactive : .unknown
        }
        for summary in summaries {
            guard !Task.isCancelled else { return .inactive }
            guard summary.loadError == nil else { return .unknown }
            do {
                let snapshot = try await project(id: summary.id)
                if snapshot.activeRuns.contains(where: { $0.status == .running }) {
                    return .active
                }
            } catch {
                return Task.isCancelled ? .inactive : .unknown
            }
        }
        return .inactive
    }

    func loadProjects(
        selecting preferredProjectID: NovelProjectID? = nil,
        restoresSelection: Bool = true
    ) async {
        projectsLoadRevision &+= 1
        let revision = projectsLoadRevision
        isLoading = true
        defer {
            if revision == projectsLoadRevision {
                isLoading = false
            }
        }
        do {
            let loadedProjects = try await projectSummaries()
            try Task.checkCancellation()
            // 仅接受最新一次加载的结果；较早响应静默丢弃，防止旧快照回写。
            guard revision == projectsLoadRevision else { return }
            projects = loadedProjects
            projectListLoadError = nil
            guard restoresSelection else {
                errorMessage = nil
                return
            }
            let nextID = preferredProjectID.flatMap { preferred in
                projects.contains(where: { $0.id == preferred }) ? preferred : nil
            } ?? selectedProjectID.flatMap { selected in
                projects.contains(where: { $0.id == selected }) ? selected : nil
            }
            if let nextID {
                await selectProject(nextID)
            } else {
                if selectedProjectID != nil {
                    clearSelection()
                }
                errorMessage = nil
            }
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled else { return }
            // 过期加载的失败同样不得覆盖最新状态。
            guard revision == projectsLoadRevision else { return }
            projectListLoadError = errorDescription(error)
            report(error)
        }
    }

    @discardableResult
    func selectProject(_ projectID: NovelProjectID) async -> Bool {
        if selectedProjectID != projectID, isProjectSelectionBlocked {
            report(NovelError.projectBusy(selectedProjectID ?? projectID))
            return false
        }
        let preferredBranchID = selectedProjectID == projectID
            ? selectedBranchID
            : lastSelectedBranchIDs[projectID]
        let intentToken = UUID()
        selectionIntentToken = intentToken
        let token = UUID()
        selectionToken = token
        isLoading = true
        defer {
            if selectionToken == token { isLoading = false }
        }
        do {
            let project = try await project(id: projectID)
            let branchID = preferredBranchID.flatMap { preferred in
                project.branches.first(where: {
                    $0.id == preferred && $0.lifecycle == .active
                })?.id
            } ?? project.branches.first(where: {
                $0.id == project.project.mainBranchID && $0.lifecycle == .active
            })?.id ?? project.branches.first(where: { $0.lifecycle == .active })?.id
            let loadedBranch: NovelBranchSnapshot?
            if let branchID {
                loadedBranch = try await branch(projectID: projectID, branchID: branchID)
            } else {
                loadedBranch = nil
            }
            try Task.checkCancellation()
            guard selectionIntentToken == intentToken,
                  selectionToken == token else { return false }
            selectedProjectID = projectID
            projectSnapshot = project
            selectedBranchID = branchID
            branchSnapshot = loadedBranch
            if let branchID { lastSelectedBranchIDs[projectID] = branchID }
            injectionPreview = nil
            errorMessage = nil
            if reloadNoticeProjectID == projectID {
                clearReloadRequirement()
            }
            return true
        } catch is CancellationError {
            return false
        } catch {
            guard selectionIntentToken == intentToken,
                  selectionToken == token else { return false }
            report(error)
            return false
        }
    }

    @discardableResult
    func selectBranch(
        _ branchID: NovelBranchID,
        stoppingActiveRun: Bool = true
    ) async -> NovelBranchSelectionResult {
        guard let projectID = selectedProjectID else { return .failed }
        guard branchID != selectedBranchID else { return .selected }
        guard !isProjectSelectionBlocked else {
            report(NovelError.projectBusy(projectID))
            return .failed
        }
        let operationOwnerID = UUID()
        guard acquireOperation(ownerID: operationOwnerID) else { return .failed }
        let intentToken = UUID()
        selectionIntentToken = intentToken
        selectionToken = UUID()
        isLoading = true
        defer {
            if self.operationOwnerID == operationOwnerID,
               selectionIntentToken == intentToken {
                isLoading = false
            }
            releaseOperation(ownerID: operationOwnerID)
        }
        var interruptedSourceBranchID: NovelBranchID?
        do {
            guard let sourceBranchID = selectedBranchID else { return .failed }
            let authoritativeSource = try await branch(
                projectID: projectID,
                branchID: sourceBranchID
            )
            let authoritativeProject = try await project(id: projectID)
            guard selectionIntentToken == intentToken,
                  self.operationOwnerID == operationOwnerID,
                  selectedProjectID == projectID,
                  selectedBranchID == sourceBranchID else { return .failed }
            selectionToken = UUID()
            projectSnapshot = authoritativeProject
            branchSnapshot = rebasedBranchSnapshot(
                authoritativeSource,
                onto: authoritativeProject
            )
            injectionPreview = nil

            let validatedTarget = try await branch(
                projectID: projectID,
                branchID: branchID
            )
            guard selectionIntentToken == intentToken,
                  self.operationOwnerID == operationOwnerID,
                  selectedProjectID == projectID,
                  selectedBranchID == sourceBranchID else { return .failed }

            let activeRun = authoritativeProject.activeRuns.first(where: {
                $0.branchID == sourceBranchID && $0.status == .running
            })
            if activeRun != nil, !stoppingActiveRun {
                errorMessage = nil
                return .requiresStoppingActiveRun
            }

            let refreshedProject: NovelProjectSnapshot
            let snapshot: NovelBranchSnapshot
            if let activeRun {
                let source = authoritativeProject.branches.first(where: {
                    $0.id == sourceBranchID
                }) ?? authoritativeSource.branch
                try await interruptSessionRun(NovelCancelRunCommand(
                    context: NovelMutationContext(
                        operationID: NovelOperationID(),
                        expectedProjectRevision: authoritativeProject.project.revision,
                        expectedConfigRevision: authoritativeProject.project.configRevision,
                        expectedBranchHeadRevision: source.headRevision
                    ),
                    projectID: projectID,
                    runID: activeRun.id,
                    reason: .routeExit
                ))
                interruptedSourceBranchID = sourceBranchID
                let project = try await project(id: projectID)
                refreshedProject = project
                snapshot = rebasedBranchSnapshot(validatedTarget, onto: project)
            } else {
                refreshedProject = authoritativeProject
                snapshot = rebasedBranchSnapshot(validatedTarget, onto: authoritativeProject)
            }
            guard selectionIntentToken == intentToken,
                  self.operationOwnerID == operationOwnerID,
                  selectedProjectID == projectID,
                  selectedBranchID == sourceBranchID else { return .failed }
            selectionToken = UUID()
            projectSnapshot = refreshedProject
            selectedBranchID = branchID
            branchSnapshot = snapshot
            lastSelectedBranchIDs[projectID] = branchID
            injectionPreview = nil
            errorMessage = nil
            return .selected
        } catch {
            if let interruptedSourceBranchID,
               let refreshedProject = try? await project(id: projectID),
               let refreshedBranch = try? await branch(
                   projectID: projectID,
                   branchID: interruptedSourceBranchID
               ),
               selectionIntentToken == intentToken,
               self.operationOwnerID == operationOwnerID,
               selectedProjectID == projectID {
                selectionToken = UUID()
                projectSnapshot = refreshedProject
                selectedBranchID = interruptedSourceBranchID
                branchSnapshot = refreshedBranch
                lastSelectedBranchIDs[projectID] = interruptedSourceBranchID
                injectionPreview = nil
            }
            guard selectionIntentToken == intentToken,
                  self.operationOwnerID == operationOwnerID,
                  selectedProjectID == projectID else { return .failed }
            report(error)
            return .failed
        }
    }

    @discardableResult
    func createProject(
        name: String,
        branchName: String = "主线",
        mode: NovelProjectCreationMode,
        genre: String = "",
        coreIdea: String = ""
    ) async -> NovelProjectID? {
        let projectID = NovelProjectID()
        let command = NovelCreateProjectCommand(
            context: mutationContext(),
            projectID: projectID,
            branchID: NovelBranchID(),
            sessionID: NovelSessionID(),
            initialStateSnapshotID: NovelStateSnapshotID(),
            initialCheckpointID: NovelCheckpointID(),
            name: name,
            branchName: branchName,
            creationMode: mode,
            quickStartSeed: mode == .quickStart
                ? NovelQuickStartSeed(genre: genre, coreIdea: coreIdea)
                : nil
        )
        guard await perform(.createProject(command), selecting: projectID) else { return nil }
        if mode == .quickStart, projectSnapshot?.project.id == projectID {
            await startQuickStartSuggestions()
        }
        return projectID
    }

    /// - Parameters:
    ///   - guidance: 用户在「重新生成设定建议」里填的调整方向,会被并入请求正文。
    ///   - coreIdeaOverride: 用户载入最初的核心想法后编辑出的本轮版本。它只覆盖本轮
    ///     prompt 的核心想法,不会改写项目里作为创建元数据保存的 `quickStartSeed`。
    ///   - exactUserText: 重试专用。`retryGeneration` 从失败 run 的**持久化** user 消息
    ///     取回原文并原样重发,以满足 `isEligibleForExactRetry` 的「精确重试」契约。
    ///     若不传,重试会退回默认文案、把用户填过的调整方向静默丢掉(真机实测过的缺陷)。
    @discardableResult
    func startQuickStartSuggestions(
        guidance: String? = nil,
        coreIdeaOverride: String? = nil,
        exactUserText: String? = nil,
        askUserResponse: NovelAskUserResponse? = nil
    ) async -> NovelRunID? {
        guard !isPerforming,
              !requiresReload,
              let project = projectSnapshot,
              let branch = branchSnapshot,
              project.project.creationMode == .quickStart,
              branch.branch.activeRunID == nil else { return nil }
        let projectID = project.project.id
        let owner = NovelQuickStartOwner(
            projectID: projectID,
            branchID: branch.branch.id
        )
        let trimmedGuidance = guidance?.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedCoreIdeaOverride = coreIdeaOverride?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let originalCoreIdea = project.project.quickStartSeed?.coreIdea
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let userText: String
        if let exactUserText, !exactUserText.isEmpty {
            userText = exactUserText
        } else if let trimmedCoreIdeaOverride,
                  !trimmedCoreIdeaOverride.isEmpty,
                  trimmedCoreIdeaOverride != originalCoreIdea {
            userText = """
            请生成一组可确认的世界观、人物、总剧情大纲和写作要求建议。

            本轮核心想法（仅本次有效）：
            \(trimmedCoreIdeaOverride)

            若本轮核心想法与 QUICK START SEED 中的 Core idea 不一致，以本轮内容为准；
            题材仍沿用 QUICK START SEED。
            """
        } else if let trimmedGuidance, !trimmedGuidance.isEmpty {
            userText = "请生成一组可确认的世界观、人物、总剧情大纲和写作要求建议。\n\n用户对上一版建议不满意，要求按以下方向调整重新生成：\n\(trimmedGuidance)"
        } else {
            userText = "请生成一组可确认的世界观、人物、总剧情大纲和写作要求建议。"
        }
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: projectID,
            branchID: branch.branch.id,
            kind: .quickStart,
            mode: .discussPlan,
            granularity: nil,
            userText: userText,
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: nil,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            askUserResponse: askUserResponse,
            inputBudgetTokens: 16_000,
            expectedProjectRevision: project.project.revision,
            expectedConfigRevision: project.project.configRevision,
            expectedBranchHeadRevision: branch.branch.headRevision
        )
        guard acquireOperation(ownerID: request.id.rawValue) else { return nil }
        errorMessage = nil
        let placeholder = NovelActiveRunRecord(
            id: request.id,
            operationID: request.operationID,
            requestPayloadSHA256: (try? request.canonicalPayloadSHA256()) ?? "",
            branchID: request.branchID,
            sessionID: branch.session.id,
            kind: .quickStart,
            mode: .discussPlan,
            granularity: nil,
            userMessageID: request.userMessageID,
            messageID: request.assistantMessageID,
            candidateID: nil,
            sourceChapterVersionID: nil,
            contextualCharacterMention: nil,
            baseCheckpointID: branch.branch.headCheckpointID,
            baseHeadRevision: branch.branch.headRevision,
            status: .running,
            partialContent: "",
            receiptID: request.generationReceiptID,
            startedAt: Date(),
            terminalAt: nil,
            interruptionReason: nil,
            terminalFailure: nil,
            chapterPlanDigest: nil
        )
        quickStartStartingProjectID = projectID
        quickStartStartingRun = placeholder
        cancelledQuickStartRunIDs.remove(request.id)
        quickStartStatuses[owner] = .starting(run: placeholder)
        if let previousRunID = quickStartTaskRunIDs[owner] {
            endBackgroundGeneration(for: previousRunID)
        }
        quickStartTasks[owner]?.cancel()
        quickStartTaskRunIDs[owner] = request.id
        beginBackgroundGeneration(for: request)
        quickStartTasks[owner] = Task { @MainActor [weak self] in
            await self?.runQuickStart(request, owner: owner)
        }
        return request.id
    }

    private func runQuickStart(_ request: NovelRunRequest, owner: NovelQuickStartOwner) async {
        guard !Task.isCancelled,
              quickStartTaskRunIDs[owner] == request.id,
              !cancelledQuickStartRunIDs.contains(request.id) else {
            endBackgroundGeneration(for: request.id)
            return
        }
        let run: NovelRun
        quickStartCreationStartRunIDs.insert(request.id)
        do {
            run = try await creation.start(request)
            quickStartCreationStartRunIDs.remove(request.id)
        } catch {
            quickStartCreationStartRunIDs.remove(request.id)
            guard quickStartTaskRunIDs[owner] == request.id else { return }
            endBackgroundGeneration(for: request.id)
            if cancelledQuickStartRunIDs.contains(request.id) {
                quickStartStatuses[owner] = .failed(
                    message: "建议生成已中断，可以重新生成。"
                )
                errorMessage = nil
            } else {
                let message = errorDescription(error)
                quickStartStatuses[owner] = .failed(message: message)
                report(error)
            }
            finishQuickStartTask(
                owner: owner,
                runID: request.id,
                releasesStartingBusy: true
            )
            return
        }
        guard quickStartTaskRunIDs[owner] == request.id,
              !cancelledQuickStartRunIDs.contains(request.id) else {
            endBackgroundGeneration(for: request.id)
            return
        }
        quickStartStatuses[owner] = .generating(runID: run.id)
        do {
            try await refreshCurrentSelection(projectID: owner.projectID)
            errorMessage = nil
            reconcileQuickStartStartingOwner(owner: owner, runID: request.id)
        } catch {
            guard quickStartTaskRunIDs[owner] == request.id else { return }
            reportQuickStartRefreshFailure(error, owner: owner)
        }
        guard quickStartTaskRunIDs[owner] == request.id else { return }
        releaseOperation(ownerID: request.id.rawValue)
        await consumeQuickStart(run, owner: owner)
    }

    private func finishQuickStartTask(
        owner: NovelQuickStartOwner,
        runID: NovelRunID,
        releasesStartingBusy: Bool = false
    ) {
        guard quickStartTaskRunIDs[owner] == runID else { return }
        endBackgroundGeneration(for: runID)
        quickStartTasks[owner] = nil
        quickStartTaskRunIDs[owner] = nil
        if quickStartStartingRun?.id == runID {
            quickStartStartingRun = nil
            quickStartStartingProjectID = nil
        }
        cancelledQuickStartRunIDs.remove(runID)
        if releasesStartingBusy {
            releaseOperation(ownerID: runID.rawValue)
        }
    }

    private func reportQuickStartRefreshFailure(_ error: Error, owner: NovelQuickStartOwner) {
        let message = errorDescription(error)
        quickStartStatuses[owner] = .refreshFailed(message: message)
        report(error)
    }

    func retryQuickStartPersistence(runID: NovelRunID) async {
        let ownerID = UUID()
        guard let projectID = selectedProjectID,
              let branchID = selectedBranchID,
              acquireOperation(ownerID: ownerID) else { return }
        let owner = NovelQuickStartOwner(projectID: projectID, branchID: branchID)
        defer { releaseOperation(ownerID: ownerID) }
        do {
            try await creation.retryPendingTerminal(runID: runID)
            try await refreshCurrentSelection(projectID: projectID)
            quickStartStatuses[owner] = nil
            errorMessage = nil
        } catch {
            let message = errorDescription(error)
            quickStartStatuses[owner] = .refreshFailed(message: message)
            report(error)
        }
    }

    func reloadQuickStartProject() async {
        let ownerID = UUID()
        guard let projectID = selectedProjectID,
              let branchID = selectedBranchID,
              acquireOperation(ownerID: ownerID) else { return }
        let owner = NovelQuickStartOwner(projectID: projectID, branchID: branchID)
        defer { releaseOperation(ownerID: ownerID) }
        do {
            try await refreshCurrentSelection(projectID: projectID)
            if let runID = quickStartStartingRun?.id {
                reconcileQuickStartStartingOwner(owner: owner, runID: runID)
            }
            quickStartStatuses[owner] = nil
            errorMessage = nil
        } catch {
            let message = errorDescription(error)
            quickStartStatuses[owner] = .refreshFailed(message: message)
            report(error)
        }
    }

    private func consumeQuickStart(_ run: NovelRun, owner: NovelQuickStartOwner) async {
        for await event in run.events {
            guard quickStartTaskRunIDs[owner] == run.id else { return }
            switch event {
            case .started:
                do {
                    try await refreshCurrentSelection(projectID: owner.projectID)
                    reconcileQuickStartStartingOwner(owner: owner, runID: run.id)
                } catch {
                    guard quickStartTaskRunIDs[owner] == run.id else { return }
                    reportQuickStartRefreshFailure(error, owner: owner)
                }
            case .delta, .replaced, .reasoningDelta:
                continue
            case .completed(let snapshot):
                do {
                    try await refreshCurrentSelection(projectID: owner.projectID)
                    guard quickStartTaskRunIDs[owner] == run.id else { return }
                    if case .some(.askUser) = snapshot.message.interaction {
                        quickStartStatuses[owner] = .awaitingUser(
                            promptMessageID: snapshot.message.id
                        )
                    } else {
                        quickStartStatuses[owner] = nil
                    }
                    errorMessage = nil
                } catch {
                    guard quickStartTaskRunIDs[owner] == run.id else { return }
                    reportQuickStartRefreshFailure(error, owner: owner)
                }
                finishQuickStartTask(owner: owner, runID: run.id)
                return
            case .interrupted:
                quickStartStatuses[owner] = .failed(message: "建议生成已中断，可以重新生成。")
                do {
                    try await refreshCurrentSelection(projectID: owner.projectID)
                    guard quickStartTaskRunIDs[owner] == run.id else { return }
                } catch {
                    guard quickStartTaskRunIDs[owner] == run.id else { return }
                    reportQuickStartRefreshFailure(error, owner: owner)
                }
                finishQuickStartTask(owner: owner, runID: run.id)
                return
            case .failed(let failure):
                quickStartStatuses[owner] = .failed(
                    message: NovelPresentation.failureMessage(failure)
                )
                do {
                    try await refreshCurrentSelection(projectID: owner.projectID)
                    guard quickStartTaskRunIDs[owner] == run.id else { return }
                } catch {
                    guard quickStartTaskRunIDs[owner] == run.id else { return }
                    reportQuickStartRefreshFailure(error, owner: owner)
                }
                finishQuickStartTask(owner: owner, runID: run.id)
                return
            case .persistenceBlocked(let failure):
                quickStartStatuses[owner] = .persistenceBlocked(
                    runID: run.id,
                    message: NovelPresentation.failureMessage(failure)
                )
            }
        }
    }

    private func reconcileQuickStartStartingOwner(
        owner: NovelQuickStartOwner,
        runID: NovelRunID
    ) {
        guard quickStartStartingProjectID == owner.projectID,
              quickStartStartingRun?.id == runID,
              projectSnapshot?.activeRuns.contains(where: {
                  $0.id == runID && $0.branchID == owner.branchID
              }) == true else {
            return
        }
        quickStartStartingProjectID = nil
        quickStartStartingRun = nil
    }

    func refreshCurrentSelection(projectID: NovelProjectID? = nil) async throws {
        let targetProjectID = projectID ?? selectedProjectID
        guard let targetProjectID else { return }
        projects = try await projectSummaries()
        guard selectedProjectID == targetProjectID else { return }
        try await reloadSelection(
            projectID: targetProjectID,
            branchID: selectedBranchID
        )
        if reloadNoticeProjectID == targetProjectID {
            clearReloadRequirement()
        }
    }

    @discardableResult
    func renameProject(_ name: String) async -> Bool {
        guard let snapshot = projectSnapshot else { return false }
        return await perform(.renameProject(NovelRenameProjectCommand(
            context: mutationContext(projectRevision: snapshot.project.revision),
            projectID: snapshot.project.id,
            name: name
        )))
    }

    @discardableResult
    func stopActiveRunsForProjectOperation(projectID: NovelProjectID) async -> Bool {
        let ownerID = UUID()
        guard acquireOperation(ownerID: ownerID) else {
            report(NovelError.projectBusy(projectID))
            return false
        }
        errorMessage = nil
        defer { releaseOperation(ownerID: ownerID) }

        do {
            let snapshot = try await project(id: projectID)
            var running = snapshot.activeRuns.filter { $0.status == .running }
            if quickStartStartingProjectID == projectID,
               let startingRun = quickStartStartingRun,
               !running.contains(where: { $0.id == startingRun.id }) {
                running.append(startingRun)
            }
            for run in running {
                try await interruptSessionRun(NovelCancelRunCommand(
                    context: NovelMutationContext(
                        operationID: NovelOperationID(),
                        expectedProjectRevision: nil,
                        expectedConfigRevision: nil,
                        expectedBranchHeadRevision: nil
                    ),
                    projectID: projectID,
                    runID: run.id,
                    reason: .user
                ))
            }

            let refreshed: NovelProjectSnapshot
            if selectedProjectID == projectID {
                try await refreshCurrentSelection(projectID: projectID)
                guard let projectSnapshot, projectSnapshot.project.id == projectID else {
                    throw NovelError.projectNotFound(projectID)
                }
                refreshed = projectSnapshot
            } else {
                refreshed = try await project(id: projectID)
            }
            guard !refreshed.activeRuns.contains(where: { $0.status == .running }),
                  !refreshed.branches.contains(where: { $0.activeRunID != nil }) else {
                throw NovelError.projectBusy(projectID)
            }
            errorMessage = nil
            return true
        } catch {
            report(error)
            return false
        }
    }

    func deleteProject() async {
        guard let snapshot = projectSnapshot else { return }
        await deleteProject(
            id: snapshot.project.id,
            expectedRevision: snapshot.project.revision
        )
    }

    func deleteProject(_ project: NovelProjectSummary) async {
        if project.loadError == nil {
            guard projectSnapshot?.project.id == project.id else { return }
            await deleteProject()
            return
        }
        await deleteProject(id: project.id, expectedRevision: project.revision)
    }

    private func deleteProject(id projectID: NovelProjectID, expectedRevision: Int64) async {
        let succeeded = await perform(.deleteProject(NovelDeleteProjectCommand(
            context: mutationContext(projectRevision: expectedRevision),
            projectID: projectID
        )), reload: false)
        guard succeeded else { return }
        if selectedProjectID == projectID { clearSelection() }
        projects.removeAll { $0.id == projectID }
        await loadProjects(restoresSelection: false)
    }

    func restorePreviousProject() async {
        guard let snapshot = projectSnapshot,
              snapshot.access != .readWrite else { return }
        _ = await perform(.restorePreviousProject(NovelRestorePreviousProjectCommand(
            context: mutationContext(projectRevision: snapshot.project.revision),
            projectID: snapshot.project.id
        )))
    }

    func saveMaterial(
        materialID: NovelMaterialID?,
        kind: NovelMaterialKind,
        title: String,
        content: String,
        tags: [String],
        injectionMode: NovelInjectionMode,
        aliases: [String] = []
    ) async {
        guard let snapshot = projectSnapshot else { return }
        _ = await perform(.reviseMaterial(NovelReviseMaterialCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: snapshot.project.id,
            materialID: materialID ?? NovelMaterialID(),
            revisionID: NovelMaterialRevisionID(),
            kind: kind,
            title: title,
            content: content,
            tags: tags,
            injectionMode: injectionMode,
            aliases: aliases
        )))
    }

    @discardableResult
    func clarifyCharacterIdentity(
        mention: String,
        clarification: String
    ) async -> Bool {
        guard let project = projectSnapshot,
              let branch = branchSnapshot else { return false }
        return await perform(.clarifyCharacterIdentity(
            NovelClarifyCharacterIdentityCommand(
                context: mutationContext(
                    projectRevision: project.project.revision,
                    branchHeadRevision: branch.branch.headRevision
                ),
                projectID: project.project.id,
                branchID: branch.branch.id,
                checkpointID: NovelCheckpointID(),
                stateSnapshotID: NovelStateSnapshotID(),
                mention: mention,
                clarification: clarification
            )
        ))
    }

    func deleteMaterial(_ materialID: NovelMaterialID) async {
        guard let snapshot = projectSnapshot else { return }
        _ = await perform(.deleteMaterial(NovelDeleteMaterialCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: snapshot.project.id,
            materialID: materialID
        )))
    }

    @discardableResult
    func setModelPolicy(
        _ policy: NovelProjectModelPolicy,
        for purpose: NovelModelRole = .creation
    ) async -> Bool {
        guard let snapshot = projectSnapshot else { return false }
        return await perform(.setModelPolicy(NovelSetModelPolicyCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: snapshot.project.id,
            purpose: purpose,
            policy: policy
        )))
    }

    @discardableResult
    func setPolishPreference(_ preference: String) async -> Bool {
        guard let snapshot = projectSnapshot else { return false }
        return await perform(.setPolishPreference(NovelSetPolishPreferenceCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: snapshot.project.id,
            preference: preference
        )))
    }

    func ghostwriteReadinessIssues(
        requireChapterPlan: Bool = false
    ) -> [NovelGhostwriteReadinessIssue] {
        guard let project = projectSnapshot,
              let branchID = selectedBranchID else { return [.branchNeedsSync] }
        return NovelGhostwriteReadiness.issues(
            materials: project.materials,
            materialRevisions: project.materialRevisions,
            branches: project.branches,
            pendingOperations: project.pendingOperations,
            polishTransactions: project.polishTransactions,
            activeRuns: project.activeRuns,
            chapterPlans: project.chapterPlans,
            mainBranchID: project.project.mainBranchID,
            branchID: branchID,
            requireChapterPlan: requireChapterPlan
        )
    }

    @discardableResult
    func setCollaborationMode(_ mode: NovelCollaborationMode) async -> Bool {
        guard let project = projectSnapshot,
              let branchID = selectedBranchID else { return false }
        return await perform(.setCollaborationMode(NovelSetCollaborationModeCommand(
            context: mutationContext(configRevision: project.project.configRevision),
            projectID: project.project.id,
            branchID: branchID,
            mode: mode
        )))
    }

    func setPauseGhostwriteOnBlockingContinuity(_ enabled: Bool) async -> Bool {
        guard let project = projectSnapshot else { return false }
        return await perform(.setPauseGhostwriteOnBlockingContinuity(
            NovelSetPauseGhostwriteOnBlockingContinuityCommand(
                context: mutationContext(configRevision: project.project.configRevision),
                projectID: project.project.id,
                enabled: enabled
            )
        ))
    }

    @discardableResult
    func upsertChapterPlan(
        planID: NovelChapterPlanID? = nil,
        status: NovelChapterPlanStatus,
        outlinePlacement: String,
        goalAndConflict: String,
        mustHappen: [String],
        mustNotHappen: [String],
        endingHook: String,
        visibleFacts: [String]
    ) async -> Bool {
        guard let project = projectSnapshot,
              let branchID = selectedBranchID else { return false }
        let resolvedPlanID = planID
            ?? project.chapterPlan(for: branchID)?.id
            ?? NovelChapterPlanID()
        return await perform(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: mutationContext(configRevision: project.project.configRevision),
            projectID: project.project.id,
            branchID: branchID,
            planID: resolvedPlanID,
            status: status,
            outlinePlacement: outlinePlacement,
            goalAndConflict: goalAndConflict,
            mustHappen: mustHappen,
            mustNotHappen: mustNotHappen,
            endingHook: endingHook,
            visibleFacts: visibleFacts
        )))
    }

    @discardableResult
    func clearChapterPlan(branchID: NovelBranchID? = nil) async -> Bool {
        guard let project = projectSnapshot,
              let branchID = branchID ?? selectedBranchID else { return false }
        return await perform(.clearChapterPlan(NovelClearChapterPlanCommand(
            context: mutationContext(configRevision: project.project.configRevision),
            projectID: project.project.id,
            branchID: branchID
        )))
    }

    @discardableResult
    func upsertUpcomingArc(beats: [String], branchID: NovelBranchID? = nil) async -> Bool {
        guard let project = projectSnapshot,
              let branchID = branchID ?? selectedBranchID else { return false }
        return await perform(.upsertUpcomingArc(NovelUpsertUpcomingArcCommand(
            context: mutationContext(configRevision: project.project.configRevision),
            projectID: project.project.id,
            branchID: branchID,
            beats: beats
        )))
    }

    @discardableResult
    func clearUpcomingArc(branchID: NovelBranchID? = nil) async -> Bool {
        guard let project = projectSnapshot,
              let branchID = branchID ?? selectedBranchID else { return false }
        return await perform(.clearUpcomingArc(NovelClearUpcomingArcCommand(
            context: mutationContext(configRevision: project.project.configRevision),
            projectID: project.project.id,
            branchID: branchID
        )))
    }

    @discardableResult
    func resolveProposal(
        _ proposalID: NovelProposalID,
        resolution: NovelSettingProposalResolution
    ) async -> Bool {
        guard let project = projectSnapshot, let branch = branchSnapshot else { return false }
        return await perform(.resolveSettingProposal(NovelResolveSettingProposalCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                configRevision: project.project.configRevision,
                branchHeadRevision: branch.branch.headRevision
            ),
            projectID: project.project.id,
            proposalID: proposalID,
            resolution: resolution
        )))
    }

    @discardableResult
    func rejectActiveSettingProposals() async -> Bool {
        let ids = branchSnapshot?.activeSettingProposals.map(\.id) ?? []
        guard !ids.isEmpty else { return true }
        for id in ids {
            let ok = await resolveProposal(id, resolution: .reject)
            if !ok { return false }
        }
        return true
    }

    func setMainBranch(_ branchID: NovelBranchID) async {
        guard let project = projectSnapshot else { return }
        _ = await perform(.setMainBranch(NovelSetMainBranchCommand(
            context: mutationContext(projectRevision: project.project.revision),
            projectID: project.project.id,
            branchID: branchID
        )))
    }

    func renameBranch(_ branchID: NovelBranchID, name: String) async {
        guard let project = projectSnapshot,
              let branch = project.branches.first(where: { $0.id == branchID }) else { return }
        _ = await perform(.renameBranch(NovelRenameBranchCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                branchHeadRevision: branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branchID,
            name: name
        )))
    }

    @discardableResult
    func forkBranch(
        from sourceBranchID: NovelBranchID,
        checkpointID: NovelCheckpointID,
        name: String
    ) async -> NovelBranchID? {
        guard let project = projectSnapshot,
              let source = project.branches.first(where: { $0.id == sourceBranchID }) else { return nil }
        let branchID = NovelBranchID()
        guard await perform(.forkBranch(NovelForkBranchCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                configRevision: project.project.configRevision,
                branchHeadRevision: source.headRevision
            ),
            projectID: project.project.id,
            sourceBranchID: sourceBranchID,
            checkpointID: checkpointID,
            branchID: branchID,
            sessionID: NovelSessionID(),
            name: name
        )), selectingBranch: branchID),
              selectedBranchID == branchID,
              branchSnapshot?.branch.id == branchID else { return nil }
        return branchID
    }

    func deleteBranch(_ branchID: NovelBranchID) async {
        guard let project = projectSnapshot,
              let branch = project.branches.first(where: { $0.id == branchID }) else { return }
        _ = await perform(.deleteBranch(NovelDeleteBranchCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                branchHeadRevision: branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branchID
        )), selectingBranch: project.project.mainBranchID)
    }

    func undoBranchHead() async {
        guard let project = projectSnapshot, let branch = branchSnapshot else { return }
        _ = await perform(.undoBranchHead(NovelUndoBranchHeadCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                branchHeadRevision: branch.branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branch.branch.id,
            expectedWorkingRevision: branch.branch.workingRevision
        )))
    }

    func revertRecentChapters(_ proposal: NovelManuscriptRevertProposal) async -> Bool {
        guard let project = projectSnapshot,
              let branch = branchSnapshot else {
            errorMessage = "项目尚未就绪，请重新载入后再试。"
            return false
        }
        switch NovelBranchSemantics.recentChapterRevertPlan(
            chapterCount: proposal.chapterCount,
            branch: branch.branch,
            chapters: project.chapters,
            chapterVersions: project.chapterVersions,
            checkpoints: project.checkpoints
        ) {
        case .failure(let failure):
            errorMessage = failure.localizedDescription
            return false
        case .success(let plan):
            guard branch.branch.headRevision == proposal.expectedHeadRevision,
                  branch.branch.workingRevision == proposal.expectedWorkingRevision,
                  plan.targetCheckpointID == proposal.targetCheckpointID,
                  plan.chapters.map(\.chapterID) == proposal.chapterIDs else {
                errorMessage = "当前分支已经变化，请重新发起回退。"
                return false
            }
            for step in 1...plan.undoStepCount {
                guard let currentProject = projectSnapshot,
                      let currentBranch = branchSnapshot else {
                    if step > 1 {
                        await reconcileGhostwriteProgressAfterRevert()
                    }
                    errorMessage = "回退进行到第 \(step) 步时项目不可用。"
                    return false
                }
                let undone = await perform(.undoBranchHead(NovelUndoBranchHeadCommand(
                    context: mutationContext(
                        projectRevision: currentProject.project.revision,
                        branchHeadRevision: currentBranch.branch.headRevision
                    ),
                    projectID: currentProject.project.id,
                    branchID: currentBranch.branch.id,
                    expectedWorkingRevision: currentBranch.branch.workingRevision
                )))
                if !undone {
                    if step > 1 {
                        await reconcileGhostwriteProgressAfterRevert()
                        if let existing = errorMessage, !existing.isEmpty {
                            errorMessage = "已回退 \(step - 1)/\(plan.undoStepCount) 步后失败：\(existing)"
                        }
                    }
                    return false
                }
            }
            await reconcileGhostwriteProgressAfterRevert()
            return true
        }
    }

    private func reconcileGhostwriteProgressAfterRevert() async {
        guard let project = projectSnapshot,
              let branch = branchSnapshot,
              let record = try? await creation.loadGhostwriteBatchProgress(
                projectID: project.project.id,
                branchID: branch.branch.id
              ) else {
            return
        }
        let reconciled = record.reconciledAfterManuscriptRevert(
            chapterVersions: project.chapterVersions,
            workingChapterIDs: Set(
                NovelBranchSemantics.workingManuscriptChapters(
                    branch: branch.branch,
                    chapters: project.chapters,
                    chapterVersions: project.chapterVersions
                ).map(\.chapterID)
            ),
            now: Date()
        )
        guard reconciled != record else { return }
        try? await creation.saveGhostwriteBatchProgress(reconciled)
    }

    @discardableResult
    func setBranchMaterialOverride(
        materialID: NovelMaterialID,
        change: NovelBranchMaterialOverrideChange
    ) async -> Bool {
        guard canMutate,
              let project = projectSnapshot,
              let branch = branchSnapshot else { return false }
        return await perform(.setBranchMaterialOverride(NovelSetBranchMaterialOverrideCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                configRevision: project.project.configRevision,
                branchHeadRevision: branch.branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branch.branch.id,
            materialID: materialID,
            change: change
        )))
    }

    /// 标记废弃 / 恢复整章。不删除任何记录,废弃后该章不再进入生成上下文。
    @discardableResult
    func setChapterDiscarded(_ isDiscarded: Bool, chapterID: NovelChapterID) async -> Bool {
        guard let project = projectSnapshot,
              let branch = branchSnapshot,
              let chapter = project.chapters.first(where: { $0.id == chapterID }) else {
            errorMessage = "章节已经不存在，请重新载入项目。"
            return false
        }
        if (chapter.discardedAt != nil) == isDiscarded { return true }
        let context = mutationContext(
            projectRevision: project.project.revision,
            branchHeadRevision: branch.branch.headRevision
        )
        if isDiscarded {
            return await perform(.discardChapter(NovelDiscardChapterCommand(
                context: context,
                projectID: project.project.id,
                branchID: branch.branch.id,
                chapterID: chapterID
            )))
        }
        return await perform(.restoreChapter(NovelRestoreChapterCommand(
            context: context,
            projectID: project.project.id,
            branchID: branch.branch.id,
            chapterID: chapterID
        )))
    }

    /// 从当前分支正文目录删除一章（工作稿不再包含；历史检查点仍保留引用）。
    func deleteChapterFromManuscript(chapterID: NovelChapterID) async -> Bool {
        guard let project = projectSnapshot,
              let branch = branchSnapshot else {
            errorMessage = "项目尚未就绪，请重新载入后再试。"
            return false
        }
        guard project.chapters.contains(where: { $0.id == chapterID }) else {
            errorMessage = "章节已经不存在，请重新载入项目。"
            return false
        }
        guard branch.chapterSelections.contains(where: { $0.chapterID == chapterID }) else {
            errorMessage = "这一章已经不在当前正文目录里。"
            return false
        }
        let deleted = await perform(.deleteChapterFromManuscript(NovelDeleteChapterFromManuscriptCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                branchHeadRevision: branch.branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branch.branch.id,
            chapterID: chapterID,
            expectedWorkingRevision: branch.branch.workingRevision
        )))
        if deleted {
            do {
                try await creation.applyWorkspacePlotRelink(
                    projectID: project.project.id,
                    branchID: branch.branch.id
                )
                errorMessage = nil
            } catch {
                report(error)
            }
        }
        return deleted
    }

    func restoreChapterVersion(_ targetVersionID: NovelChapterVersionID) async {
        guard let project = projectSnapshot,
              let branch = branchSnapshot,
              project.chapterVersions.contains(where: { $0.id == targetVersionID }) else {
            return
        }
        _ = await perform(.restoreChapterVersion(NovelRestoreChapterVersionCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                branchHeadRevision: branch.branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branch.branch.id,
            targetChapterVersionID: targetVersionID,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            expectedWorkingRevision: branch.branch.workingRevision
        )))
    }

    @discardableResult
    func saveManualRewrite(from version: NovelChapterVersionRecord) async -> Bool {
        guard let branch = branchSnapshot,
              version.chapterID == branch.chapterSelections.first(where: {
                  $0.chapterID == version.chapterID
              })?.chapterID else {
            return false
        }
        return await saveManualRewrite(
            chapterID: version.chapterID,
            title: version.title,
            content: version.content
        )
    }

    @discardableResult
    func saveManualRewrite(
        chapterID: NovelChapterID,
        title: String,
        content: String,
        schedulesAutomaticSync: Bool = true
    ) async -> Bool {
        guard let project = projectSnapshot,
              let branch = branchSnapshot,
              branch.chapterSelections.contains(where: { $0.chapterID == chapterID }) else {
            return false
        }
        let saved = await perform(.saveManualEdit(NovelSaveManualEditCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                configRevision: project.project.configRevision,
                branchHeadRevision: branch.branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branch.branch.id,
            chapterID: chapterID,
            versionID: NovelChapterVersionID(),
            title: title,
            content: content,
            factCompatibilityID: UUID(),
            expectedWorkingRevision: branch.branch.workingRevision
        )))
        if saved, schedulesAutomaticSync {
            _ = await applyWorkspaceFastForwardPlot(
                chapterID: chapterID,
                title: title,
                content: content
            )
        }
        return saved
    }

    @discardableResult
    func syncWorkingManuscript(preferStateDelta: Bool) async -> Bool {
        guard let project = projectSnapshot,
              let branch = branchSnapshot,
              branch.branch.syncStatus == .needsSync else {
            return true
        }
        let target = NovelAutomaticStateSyncTarget(
            projectID: project.project.id,
            branchID: branch.branch.id
        )
        automaticStateSyncPresentationTarget = target
        defer {
            if automaticStateSyncPresentationTarget == target {
                automaticStateSyncPresentationTarget = nil
            }
            stateSyncStoppingTargets.remove(target)
        }
        return await perform(.syncManualEdits(NovelSyncManualEditsCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                configRevision: project.project.configRevision,
                branchHeadRevision: branch.branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branch.branch.id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: branch.branch.workingRevision,
            preferStateDelta: preferStateDelta
        )))
    }

    func startSessionRun(
        _ request: NovelRunRequest,
        acquiresBackgroundLease: Bool = true
    ) async throws -> NovelRun {
        if acquiresBackgroundLease {
            beginBackgroundGeneration(for: request)
        }
        do {
            return try await creation.start(request)
        } catch {
            if acquiresBackgroundLease {
                endBackgroundGeneration(for: request.id)
            }
            throw error
        }
    }

    /// 在已经取得 session 单写者之后、第一次 await 之前取得后台执行权。
    /// 代笔内层正文沿用整批 lease，避免每章再挂一条会独立过期的短 lease。
    func beginBackgroundGeneration(for request: NovelRunRequest) {
        let leaseID = novelRunBackgroundLeaseID(for: request.id)
        let ghostwriteLeaseID = novelGhostwriteBackgroundLeaseID(
            projectID: request.projectID,
            branchID: request.branchID
        )
        if request.ghostwritePlanID != nil,
           BackgroundGenerationKeepAlive.shared.holdsLease(ghostwriteLeaseID) {
            BackgroundGenerationKeepAlive.shared.advanceProgress(
                ghostwriteLeaseID,
                by: 1,
                subtitle: "正在生成正文"
            )
            return
        }

        // 普通正文生成由当前用户动作直接申请 continued-processing；等首段输出
        // 再提交会错过退后台后的申请窗口。
        BackgroundGenerationKeepAlive.shared.begin(
            leaseID,
            title: "Amber 小说创作中",
            subtitle: "准备生成",
            onExpire: { [weak self] in
                Task { @MainActor [weak self] in
                    await self?.handleNovelGenerationKeepAliveLoss(
                        projectID: request.projectID,
                        runID: request.id,
                        rearm: { self?.beginBackgroundGeneration(for: request) }
                    )
                }
            },
            onSystemTaskExpiration: { [weak self] in
                Task { @MainActor [weak self] in
                    await self?.handleNovelGenerationKeepAliveLoss(
                        projectID: request.projectID,
                        runID: request.id,
                        rearm: { self?.beginBackgroundGeneration(for: request) }
                    )
                }
            }
        )
        BackgroundGenerationKeepAlive.shared.updateProgress(
            leaseID,
            completed: 0,
            total: 4,
            subtitle: "准备生成"
        )
    }

    /// KeepAlive 短窗/系统进度卡丢失时：
    /// - 非后台（前台 / inactive 控制中心等）：不砍流，重挂租约。
    /// - 真正进后台：可持久化中断（有 cursor 则 detach，否则 interrupt）。
    private func handleNovelGenerationKeepAliveLoss(
        projectID: NovelProjectID,
        runID: NovelRunID,
        rearm: () -> Void
    ) async {
        // inactive（下拉通知中心、App Switcher 半屏）不能当后台砍流。
        if UIApplication.shared.applicationState != .background {
            rearm()
            return
        }
        await interruptSessionForBackground(
            projectID: projectID,
            runID: runID,
            deadline: Date()
        )
    }

    func endBackgroundGeneration(for runID: NovelRunID) {
        BackgroundGenerationKeepAlive.shared.end(novelRunBackgroundLeaseID(for: runID))
    }

    func performSessionAction(_ action: NovelAction) async throws -> NovelOutcome {
        // Session-driven actions (e.g. explicit "retry") reach here without going
        // through the private `perform(_:)` wiring, so this call is the only place
        // left to publish `stateSyncActivity` for them. Reuse the exact same
        // start/stop helpers and key them off the operation owner that the caller
        // (NovelSessionViewModel.beginAction) already acquired, so ownership stays
        // single-writer and consistent with the automatic-sync path.
        let stateSyncContext = stateSyncContext(for: action)
        let activityOwnerID = stateSyncContext != nil ? operationOwnerID : nil
        if let stateSyncContext, let activityOwnerID {
            startStateSyncActivity(
                ownerID: activityOwnerID,
                projectID: action.projectID,
                branchID: stateSyncContext.branchID,
                pendingID: stateSyncContext.pendingID,
                attemptOperationID: stateSyncContext.attemptOperationID
            )
        }
        defer {
            if stateSyncContext != nil, let activityOwnerID {
                stopStateSyncActivity(ownerID: activityOwnerID)
            }
        }
        ownMutationOperationIDs.insert(action.context.operationID)
        return try await creation.perform(action)
    }

    func distillDiscussionArchive(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        chapterID: NovelChapterID?
    ) async throws -> NovelDiscussionArchiveDraft {
        try await creation.distillDiscussionArchive(
            projectID: projectID,
            branchID: branchID,
            chapterID: chapterID
        )
    }

    func acceptChapterPlan(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID
    ) async throws -> NovelChapterPlanAcceptanceV1 {
        try await creation.acceptChapterPlan(
            projectID: projectID,
            branchID: branchID,
            candidateID: candidateID
        )
    }

    func proposeAndConfirmNextChapterPlan(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        nextChapterOrdinal: Int,
        previousPlanSummary: String?
    ) async throws -> NovelChapterPlanRecord {
        let plan = try await creation.proposeAndConfirmNextChapterPlan(
            projectID: projectID,
            branchID: branchID,
            nextChapterOrdinal: nextChapterOrdinal,
            previousPlanSummary: previousPlanSummary
        )
        // 自动确认合同后刷新快照，供代笔 pipeline 立刻读到新 digest。
        try await refreshCurrentSelection(projectID: projectID)
        return plan
    }

    /// 根据前文生成草稿本章计划（不自动确认）；成功后尽量刷新快照。
    /// 刷新失败不吞掉已生成的 plan（调用方可用返回值回填）。
    func proposeNextChapterPlanDraft(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        nextChapterOrdinal: Int,
        previousPlanSummary: String?
    ) async throws -> NovelChapterPlanRecord {
        let plan = try await creation.proposeNextChapterPlanDraft(
            projectID: projectID,
            branchID: branchID,
            nextChapterOrdinal: nextChapterOrdinal,
            previousPlanSummary: previousPlanSummary
        )
        // 刷新失败不抛：盘上已有 draft，调用方用返回的 plan 回填字段。
        try? await refreshCurrentSelection(projectID: projectID)
        return plan
    }

    func auditContinuityIncludingCandidate(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID,
        maxPriorManuscriptChapters: Int? = nil
    ) async throws -> NovelContinuityAuditReport {
        try await creation.auditContinuityIncludingCandidate(
            projectID: projectID,
            branchID: branchID,
            candidateID: candidateID,
            maxPriorManuscriptChapters: maxPriorManuscriptChapters
        )
    }

    func interruptSessionRun(_ command: NovelCancelRunCommand) async throws {
        let quickStartOwner = quickStartOwner(
            projectID: command.projectID,
            runID: command.runID
        )
        if let quickStartOwner {
            cancelledQuickStartRunIDs.insert(command.runID)
            if !quickStartCreationStartRunIDs.contains(command.runID) {
                quickStartTasks[quickStartOwner]?.cancel()
            }
        }
        do {
            try await creation.interruptRun(command)
        } catch {
            if let quickStartOwner,
               let novelError = error as? NovelError,
               novelError == .runNotFound(command.runID) {
                clearQuickStartTask(
                    owner: quickStartOwner,
                    runID: command.runID,
                    status: .failed(message: "建议生成已中断，可以重新生成。")
                )
                return
            }
            if quickStartOwner != nil {
                cancelledQuickStartRunIDs.remove(command.runID)
            }
            throw error
        }
        if let quickStartOwner {
            await reconcileQuickStartAfterInterrupt(
                owner: quickStartOwner,
                runID: command.runID
            )
        }
    }

    func retrySessionTerminal(runID: NovelRunID) async throws {
        try await creation.retryPendingTerminal(runID: runID)
    }

    func interruptSessionForBackground(
        projectID: NovelProjectID,
        runID: NovelRunID?,
        deadline: Date
    ) async {
        let quickStartOwner = quickStartTaskRunIDs.keys.first(where: {
            $0.projectID == projectID &&
                (runID == nil || quickStartTaskRunIDs[$0] == runID)
        })
        let quickStartRunID = quickStartOwner.flatMap { quickStartTaskRunIDs[$0] }
        let effectiveRunID = runID ?? quickStartRunID
        if let quickStartOwner, let quickStartRunID {
            cancelledQuickStartRunIDs.insert(quickStartRunID)
            if !quickStartCreationStartRunIDs.contains(quickStartRunID) {
                quickStartTasks[quickStartOwner]?.cancel()
            }
        }
        await creation.interruptForBackground(
            projectID: projectID,
            deadline: deadline,
            runID: effectiveRunID
        )
        if let quickStartOwner, let quickStartRunID {
            await reconcileQuickStartAfterInterrupt(
                owner: quickStartOwner,
                runID: quickStartRunID
            )
        }
        // Foreground recovery can race ahead of the expiration callback and
        // scan before this method has detached the response. Re-scan only when
        // the app is actually active after the interruption finishes.
        if UIApplication.shared.applicationState == .active {
            await creation.resumeDetachedGenerationRuns()
        }
    }

    private func reconcileQuickStartAfterInterrupt(
        owner: NovelQuickStartOwner,
        runID: NovelRunID
    ) async {
        do {
            try await refreshCurrentSelection(projectID: owner.projectID)
        } catch {
            let message = errorDescription(error)
            clearQuickStartTask(
                owner: owner,
                runID: runID,
                status: .refreshFailed(message: message)
            )
            return
        }
        guard quickStartTaskRunIDs[owner] == runID else { return }
        let run = projectSnapshot?.activeRuns.first { $0.id == runID }
        let status: NovelQuickStartStatus?
        switch run?.status {
        case .completed:
            status = nil
        case .failed:
            status = .failed(
                message: run?.terminalFailure.map(NovelPresentation.failureMessage)
                    ?? "建议生成失败，可以重新生成。"
            )
        case .interrupted, nil:
            status = .failed(message: "建议生成已中断，可以重新生成。")
        case .running:
            guard quickStartTaskRunIDs[owner] == runID else { return }
            quickStartStatuses[owner] = .refreshFailed(
                message: "生成状态尚未收口，请重新载入后再继续。"
            )
            releaseOperation(ownerID: runID.rawValue)
            return
        }
        clearQuickStartTask(owner: owner, runID: runID, status: status)
    }

    private func clearQuickStartTask(
        owner: NovelQuickStartOwner,
        runID: NovelRunID,
        status: NovelQuickStartStatus?
    ) {
        guard quickStartTaskRunIDs[owner] == runID else { return }
        endBackgroundGeneration(for: runID)
        quickStartTasks[owner]?.cancel()
        quickStartTasks[owner] = nil
        quickStartTaskRunIDs[owner] = nil
        quickStartCreationStartRunIDs.remove(runID)
        if quickStartStartingProjectID == owner.projectID,
           quickStartStartingRun?.id == runID {
            quickStartStartingProjectID = nil
            quickStartStartingRun = nil
        }
        cancelledQuickStartRunIDs.remove(runID)
        quickStartStatuses[owner] = status
        errorMessage = nil
        releaseOperation(ownerID: runID.rawValue)
    }

    private func quickStartOwner(
        projectID: NovelProjectID,
        runID: NovelRunID
    ) -> NovelQuickStartOwner? {
        quickStartTaskRunIDs.first(where: {
            $0.key.projectID == projectID && $0.value == runID
        })?.key
    }

    func syncManualEdits() async {
        guard let project = projectSnapshot,
              let branch = branchSnapshot,
              branch.branch.syncStatus == .needsSync else { return }
        _ = await perform(.syncManualEdits(NovelSyncManualEditsCommand(
            context: mutationContext(
                projectRevision: project.project.revision,
                configRevision: project.project.configRevision,
                branchHeadRevision: branch.branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branch.branch.id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: branch.branch.workingRevision
        )))
    }

    func scheduleAutomaticStateSyncIfNeeded() {
        guard let project = projectSnapshot,
              let branch = branchSnapshot,
              branch.branch.syncStatus == .needsSync else { return }
        let branchPending = project.pendingOperations.filter {
            $0.branchID == branch.branch.id
        }
        let leftoverManualSync = branchPending.count == 1 &&
            branchPending[0].kind == .manualSync &&
            (branchPending[0].status == .pending ||
                branchPending[0].status == .retryable)
        guard branchPending.isEmpty || leftoverManualSync else { return }
        startWorkspacePlotRelink(
            NovelAutomaticStateSyncTarget(
                projectID: project.project.id,
                branchID: branch.branch.id
            )
        )
    }

    func scheduleAutomaticStateSync(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) {
        let target = NovelAutomaticStateSyncTarget(
            projectID: projectID,
            branchID: branchID
        )
        let ghostwriteLeaseID = novelGhostwriteBackgroundLeaseID(
            projectID: projectID,
            branchID: branchID
        )
        guard UIApplication.shared.applicationState != .background ||
                BackgroundGenerationKeepAlive.shared.holdsLease(ghostwriteLeaseID) else {
            return
        }
        guard !userSuppressedStateSyncTargets.contains(target) else { return }
        guard target != automaticStateSyncTarget,
              target != queuedAutomaticStateSyncTarget else { return }
        guard automaticStateSyncTask == nil else {
            queuedAutomaticStateSyncTarget = target
            return
        }
        startAutomaticStateSync(target)
    }

    func retryPending(_ pendingID: NovelPendingOperationID) async {
        if projectSnapshot?.pendingOperations.contains(where: {
            $0.id == pendingID && $0.kind == .manualSync
        }) == true {
            if let task = startManualStateSyncRetry(pendingID) {
                await task.value
            }
            return
        }
        guard let project = projectSnapshot else { return }
        _ = await perform(.retryPending(NovelRetryPendingCommand(
            context: mutationContext(projectRevision: project.project.revision),
            projectID: project.project.id,
            pendingID: pendingID
        )))
    }

    @discardableResult
    func startManualStateSyncRetry(
        _ pendingID: NovelPendingOperationID
    ) -> Task<Void, Never>? {
        if manualStateSyncPendingID == pendingID {
            return manualStateSyncTask
        }
        guard manualStateSyncTask == nil,
              automaticStateSyncTask == nil,
              let project = projectSnapshot,
              let pending = project.pendingOperations.first(where: {
                  $0.id == pendingID &&
                      $0.kind == .manualSync &&
                      $0.status == .retryable
              }),
              reloadNoticeProjectID != project.project.id else { return nil }
        let target = NovelAutomaticStateSyncTarget(
            projectID: project.project.id,
            branchID: pending.branchID
        )
        let ownerID = UUID()
        guard acquireOperation(ownerID: ownerID) else { return nil }

        // Explicit retry after a user Stop should lift suppress for this target.
        userSuppressedStateSyncTargets.remove(target)
        if automaticStateSyncFailure?.target == target {
            automaticStateSyncFailure = nil
            errorMessage = nil
        }
        manualStateSyncPendingID = pendingID
        manualStateSyncTarget = target
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.manualStateSyncPendingID == pendingID {
                    self.manualStateSyncTask = nil
                    self.manualStateSyncPendingID = nil
                    self.manualStateSyncTarget = nil
                }
                self.stateSyncStoppingTargets.remove(target)
            }
            if Task.isCancelled {
                self.releaseOperation(ownerID: ownerID)
            } else {
                _ = await self.perform(
                    .retryPending(NovelRetryPendingCommand(
                        context: self.mutationContext(
                            projectRevision: project.project.revision
                        ),
                        projectID: project.project.id,
                        pendingID: pendingID
                    )),
                    reportsError: false,
                    reservedOwnerID: ownerID
                )
            }
        }
        manualStateSyncTask = task
        return task
    }

    @discardableResult
    func previewInjection(
        _ request: NovelInjectionPreviewRequest
    ) async -> NovelInjectionPreviewSnapshot? {
        let ownerID = UUID()
        guard acquireOperation(ownerID: ownerID) else { return nil }
        defer { releaseOperation(ownerID: ownerID) }
        injectionPreview = nil
        do {
            guard case .injectionPreview(let preview) = try await creation.snapshot(
                .injectionPreview(request)
            ) else {
                throw NovelError.invalidInput("注入预览返回了意外的快照，请重试。")
            }
            guard preview.projectID == selectedProjectID,
                  preview.branchID == selectedBranchID else { return nil }
            injectionPreview = preview
            errorMessage = nil
            return preview
        } catch {
            report(error)
            return nil
        }
    }

    /// 发起前的预估:扫几章、切几块。块数就是这次要发多少个模型请求,给用户一个
    /// 「这一趟要花多少」的交代。不消耗模型调用。
    func planContinuityAudit() async -> NovelContinuityAuditPlan? {
        guard let projectID = selectedProjectID, let branchID = selectedBranchID else {
            return nil
        }
        let target = NovelAutomaticStateSyncTarget(projectID: projectID, branchID: branchID)
        guard !Task.isCancelled else { return nil }
        let ownerID = UUID()
        guard acquireOperation(ownerID: ownerID) else {
            // 抢不到锁不能静默返回:用户看到的就是「点了没反应」。
            continuityAuditFailureStorage = NovelContinuityAuditFailure(
                target: target,
                message: "有别的操作正在进行，请稍后再试。"
            )
            return nil
        }
        defer { releaseOperation(ownerID: ownerID) }
        do {
            try Task.checkCancellation()
            let plan = try await creation.planContinuityAudit(
                projectID: projectID,
                branchID: branchID
            )
            try Task.checkCancellation()
            continuityAuditPlanStorage = plan
            if continuityAuditFailureStorage?.target == target {
                continuityAuditFailureStorage = nil
            }
            errorMessage = nil
            return plan
        } catch is CancellationError {
            if continuityAuditFailureStorage?.target == target {
                continuityAuditFailureStorage = nil
            }
            return nil
        } catch {
            continuityAuditFailureStorage = NovelContinuityAuditFailure(
                target: target,
                message: errorDescription(error)
            )
            return nil
        }
    }

    func startContinuityAuditPlanning() {
        guard continuityAuditTask == nil else { return }
        continuityAuditPlanStorage = nil
        isPlanningContinuity = true
        continuityAuditTask = Task { @MainActor [weak self] in
            _ = await self?.planContinuityAudit()
            self?.isPlanningContinuity = false
            self?.continuityAuditTask = nil
        }
    }

    /// 界面用这个入口发起扫描:任务句柄留在 ViewModel 里,「停止扫描」才有东西可取消。
    func startContinuityAudit() {
        guard continuityAuditTask == nil else { return }
        continuityAuditPlanStorage = nil
        continuityAuditTask = Task { @MainActor [weak self] in
            await self?.auditContinuity()
            self?.continuityAuditTask = nil
        }
    }

    func cancelContinuityAudit() {
        continuityAuditTask?.cancel()
    }

    func auditContinuity() async {
        guard let projectID = selectedProjectID, let branchID = selectedBranchID else { return }
        let target = NovelAutomaticStateSyncTarget(projectID: projectID, branchID: branchID)
        let ownerID = UUID()
        guard acquireOperation(ownerID: ownerID) else {
            continuityAuditFailureStorage = NovelContinuityAuditFailure(
                target: target,
                message: "有别的操作正在进行，请稍后再试。"
            )
            return
        }
        isAuditingContinuity = true
        beginContinuityBackgroundLease(
            ownerID: ownerID,
            target: target
        )
        defer {
            isAuditingContinuity = false
            if continuityAuditExpirationOwnerID == ownerID {
                continuityAuditExpirationOwnerID = nil
            }
            BackgroundGenerationKeepAlive.shared.end(
                novelContinuityBackgroundLeaseID(for: ownerID)
            )
            releaseOperation(ownerID: ownerID)
        }
        do {
            let audit = try await creation.auditContinuity(
                projectID: projectID,
                branchID: branchID
            )
            try Task.checkCancellation()
            continuityAuditReport = audit
            if continuityAuditFailureStorage?.target == target {
                continuityAuditFailureStorage = nil
            }
            errorMessage = nil
        } catch is CancellationError {
            if continuityAuditExpirationOwnerID == ownerID {
                continuityAuditFailureStorage = NovelContinuityAuditFailure(
                    target: target,
                    message: "后台执行时间已结束，请重新检查。"
                )
            } else if continuityAuditFailureStorage?.target == target {
                // 用户自己按的停止,不是故障,不必弹错误。
                continuityAuditFailureStorage = nil
            }
        } catch {
            if continuityAuditExpirationOwnerID == ownerID {
                continuityAuditFailureStorage = NovelContinuityAuditFailure(
                    target: target,
                    message: "后台执行时间已结束，请重新检查。"
                )
            } else if Task.isCancelled {
                if continuityAuditFailureStorage?.target == target {
                    continuityAuditFailureStorage = nil
                }
            } else {
                continuityAuditFailureStorage = NovelContinuityAuditFailure(
                    target: target,
                    message: errorDescription(error)
                )
            }
        }
    }

    func clearContinuityAudit() {
        continuityAuditReport = nil
        continuityAuditPlanStorage = nil
        continuityAuditFailureStorage = nil
    }

    func clearContinuityAuditPlan() {
        continuityAuditPlanStorage = nil
    }

    func previewImport(_ data: Data) async -> NovelProjectImportPreview? {
        let ownerID = UUID()
        guard acquireOperation(ownerID: ownerID) else { return nil }
        defer { releaseOperation(ownerID: ownerID) }
        do {
            guard case .projectImportPreview(let preview) = try await creation.snapshot(
                .projectImportPreview(data)
            ) else {
                throw NovelError.invalidInput("The import preview returned an unexpected snapshot.")
            }
            errorMessage = nil
            return preview
        } catch {
            report(error)
            return nil
        }
    }

    @discardableResult
    func importProject(
        _ data: Data,
        choice: NovelProjectImportChoice,
        preview acceptedPreview: NovelProjectImportPreview? = nil
    ) async -> NovelProjectImportResult? {
        let preview: NovelProjectImportPreview
        if let acceptedPreview {
            preview = acceptedPreview
        } else {
            guard let preparedPreview = await previewImport(data) else { return nil }
            preview = preparedPreview
        }
        let destinationID: NovelProjectID
        let policy: NovelProjectImportPolicy
        let expectedRevision: Int64?
        switch choice {
        case .reject:
            destinationID = preview.sourceProjectID
            policy = .reject
            expectedRevision = nil
        case .replace:
            guard let existing = preview.existingProject else {
                report(NovelError.invalidInput("There is no local project to replace."))
                return nil
            }
            destinationID = preview.sourceProjectID
            policy = .replace(expectedRevision: existing.revision)
            expectedRevision = existing.revision
        case .keepBoth:
            destinationID = NovelProjectID()
            policy = .keepBoth(destinationProjectID: destinationID)
            expectedRevision = nil
        }
        guard await perform(.importProject(NovelImportProjectCommand(
            context: mutationContext(projectRevision: expectedRevision),
            projectID: destinationID,
            packageData: data,
            policy: policy
        )), selecting: destinationID) else { return nil }
        if selectedProjectID == destinationID,
           projectSnapshot?.project.id == destinationID {
            return .selected(destinationID)
        }
        if reloadNoticeProjectID == destinationID {
            return .committedNeedsReload(destinationID)
        }
        return nil
    }

    func exportProjectPackage() async -> NovelProjectPackageArtifact? {
        guard let projectID = selectedProjectID else { return nil }
        do {
            guard case .package(let artifact) = try await creation.snapshot(
                .projectPackage(projectID)
            ) else {
                throw NovelError.invalidInput("The project export returned an unexpected snapshot.")
            }
            errorMessage = nil
            return artifact
        } catch {
            report(error)
            return nil
        }
    }

    func exportBranchMarkdown() async -> NovelMarkdownExportArtifact? {
        guard let projectID = selectedProjectID, let branchID = selectedBranchID else { return nil }
        do {
            guard case .markdown(let artifact) = try await creation.snapshot(
                .branchMarkdown(projectID: projectID, branchID: branchID)
            ) else {
                throw NovelError.invalidInput("The Markdown export returned an unexpected snapshot.")
            }
            errorMessage = nil
            return artifact
        } catch {
            report(error)
            return nil
        }
    }

    func applyWorkspacePlot(path: String, body: String) async -> String? {
        guard let projectID = selectedProjectID, let branchID = selectedBranchID else {
            return "当前没有打开的小说项目。"
        }
        do {
            try await creation.applyWorkspacePlot(
                projectID: projectID,
                branchID: branchID,
                path: path,
                body: body
            )
            errorMessage = nil
            return nil
        } catch {
            report(error)
            return errorMessage ?? error.localizedDescription
        }
    }

    var hasStalePlot: Bool {
        branchSnapshot?.currentState.hasStaleChapterPlots == true
    }

    func acceptStalePlot() async {
        guard let projectID = selectedProjectID, let branchID = selectedBranchID else { return }
        do {
            try await creation.applyWorkspacePlotAcceptStale(
                projectID: projectID,
                branchID: branchID
            )
            errorMessage = nil
            projectSnapshot = try await project(id: projectID)
            branchSnapshot = try await branch(projectID: projectID, branchID: branchID)
        } catch {
            report(error)
        }
    }

    func applyWorkspaceFastForwardPlot(
        chapterID: NovelChapterID,
        title: String,
        content: String
    ) async -> String? {
        guard let projectID = selectedProjectID, let branchID = selectedBranchID else {
            return "当前没有打开的小说项目。"
        }
        do {
            try await creation.applyWorkspaceFastForwardPlot(
                projectID: projectID,
                branchID: branchID,
                chapterID: chapterID,
                chapterTitle: title,
                chapterContent: content
            )
            errorMessage = nil
            return nil
        } catch {
            report(error)
            return errorMessage ?? error.localizedDescription
        }
    }

    func exportWorkspace() async -> NovelWorkspaceExportArtifact? {
        guard let projectID = selectedProjectID else { return nil }
        do {
            guard case .workspace(let artifact) = try await creation.snapshot(
                .workspace(projectID)
            ) else {
                throw NovelError.invalidInput("The workspace export returned an unexpected snapshot.")
            }
            errorMessage = nil
            return artifact
        } catch {
            report(error)
            return nil
        }
    }

    func clearError() {
        errorMessage = nil
        reloadNoticeMessage = nil
    }

    func retryCommittedMutationReload() async {
        let ownerID = UUID()
        guard let projectID = reloadNoticeProjectID,
              acquireOperation(ownerID: ownerID) else { return }
        defer { releaseOperation(ownerID: ownerID) }
        do {
            projects = try await projectSummaries()
            if selectedProjectID == projectID {
                try await reloadSelection(
                    projectID: projectID,
                    branchID: reloadNoticeBranchID
                )
            } else {
                let project = try await self.project(id: projectID)
                let preferredBranchID = reloadNoticeBranchID.flatMap { preferred in
                    project.branches.contains(where: {
                        $0.id == preferred && $0.lifecycle == .active
                    }) ? preferred : nil
                }
                let branchID = preferredBranchID ?? project.branches.first(where: {
                    $0.id == project.project.mainBranchID && $0.lifecycle == .active
                })?.id ?? project.branches.first(where: { $0.lifecycle == .active })?.id
                if let branchID {
                    _ = try await branch(projectID: projectID, branchID: branchID)
                }
            }
            errorMessage = nil
            if reloadNoticeProjectID == projectID {
                clearReloadRequirement()
            }
        } catch {
            errorMessage = nil
            reloadNoticeMessage = "操作已经完成，但项目重新载入失败：\(errorDescription(error))"
        }
    }

    func presentError(_ error: Error) {
        report(error)
    }

    private func perform(
        _ action: NovelAction,
        selecting projectID: NovelProjectID? = nil,
        selectingBranch branchID: NovelBranchID? = nil,
        reload: Bool = true,
        reportsError: Bool = true,
        reservedOwnerID: UUID? = nil
    ) async -> Bool {
        if let projectID, selectedProjectID != projectID, isProjectSelectionBlocked {
            report(NovelError.projectBusy(selectedProjectID ?? action.projectID))
            return false
        }
        let ownerID = reservedOwnerID ?? UUID()
        guard reloadNoticeProjectID != action.projectID else {
            if reportsError {
                errorMessage = errorDescription(NovelError.storageIndeterminate(action.projectID))
            }
            return false
        }
        if let reservedOwnerID {
            guard operationOwnerID == reservedOwnerID, isPerforming else {
                if reportsError { report(NovelError.projectBusy(action.projectID)) }
                return false
            }
        } else {
            guard acquireOperation(ownerID: ownerID) else {
                if reportsError { report(NovelError.projectBusy(action.projectID)) }
                return false
            }
        }
        let stateSyncContext = stateSyncContext(for: action)
        if let stateSyncContext {
            startStateSyncActivity(
                ownerID: ownerID,
                projectID: action.projectID,
                branchID: stateSyncContext.branchID,
                pendingID: stateSyncContext.pendingID,
                attemptOperationID: stateSyncContext.attemptOperationID
            )
        }
        let startingSelectionToken = selectionToken
        defer {
            if stateSyncContext != nil { stopStateSyncActivity(ownerID: ownerID) }
            releaseOperation(ownerID: ownerID)
        }

        do {
            ownMutationOperationIDs.insert(action.context.operationID)
            _ = try await creation.perform(action)
        } catch {
            let operationError = error
            if reload {
                if let refreshedProjects = try? await projectSummaries() {
                    projects = refreshedProjects
                }
                let targetProjectID = projectID ?? selectedProjectID ?? action.projectID
                if (projectID != nil || selectionToken == startingSelectionToken),
                   projects.contains(where: { $0.id == targetProjectID }) {
                    try? await reloadSelection(
                        projectID: targetProjectID,
                        branchID: branchID ?? selectedBranchID
                    )
                }
            } else if !reload {
                await loadProjects()
            }
            if stateSyncContext != nil {
                // Keep the real invalidInput / model detail — errorDescription alone
                // used to collapse everything to a useless short line.
                lastStateSyncOperationCause = operationError
                lastStateSyncOperationError = NovelPresentation.stateSyncFailureMessage(
                    for: operationError
                )
            }
            if reportsError { report(operationError) }
            return false
        }

        if stateSyncContext != nil {
            lastStateSyncOperationCause = nil
            lastStateSyncOperationError = nil
        }
        if reportsError { errorMessage = nil }
        guard reload else { return true }

        let targetProjectID = projectID ?? selectedProjectID ?? action.projectID
        let targetBranchID = branchID ?? selectedBranchID
        do {
            projects = try await projectSummaries()
            if projectID != nil || selectionToken == startingSelectionToken {
                try await reloadSelection(
                    projectID: targetProjectID,
                    branchID: targetBranchID
                )
            }
            return true
        } catch {
            errorMessage = nil
            reloadNoticeMessage = "操作已经完成，但项目重新载入失败：\(errorDescription(error))"
            reloadNoticeProjectID = targetProjectID
            reloadNoticeBranchID = targetBranchID
            return true
        }
    }

    private func clearReloadRequirement() {
        reloadNoticeMessage = nil
        reloadNoticeProjectID = nil
        reloadNoticeBranchID = nil
    }

    private func stateSyncContext(
        for action: NovelAction
    ) -> (
        branchID: NovelBranchID,
        pendingID: NovelPendingOperationID,
        attemptOperationID: NovelOperationID
    )? {
        switch action {
        case .syncManualEdits(let command):
            return (command.branchID, command.pendingID, command.context.operationID)
        case .retryPending(let command):
            guard let pending = projectSnapshot?.pendingOperations.first(where: {
                $0.id == command.pendingID && $0.kind == .manualSync
            }) else { return nil }
            return (pending.branchID, command.pendingID, command.context.operationID)
        default:
            return nil
        }
    }

    private func startStateSyncActivity(
        ownerID: UUID,
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        pendingID: NovelPendingOperationID,
        attemptOperationID: NovelOperationID
    ) {
        let sameTarget = stateSyncActivity?.projectID == projectID &&
            stateSyncActivity?.branchID == branchID
        let startedAt = sameTarget ? (stateSyncActivity?.startedAt ?? Date()) : Date()
        stateSyncActivityOwnerID = ownerID
        if !(sameTarget && stateSyncActivity?.phase == .analyzing) {
            stateSyncActivity = .preparing(
                projectID: projectID,
                branchID: branchID,
                pendingID: pendingID,
                startedAt: startedAt
            )
        }
        beginStateSyncBackgroundLease(
            ownerID: ownerID,
            projectID: projectID,
            branchID: branchID
        )
        stateSyncActivityTask?.cancel()
        stateSyncActivityTask = Task { @MainActor [weak self] in
            guard let self else { return }
            while !Task.isCancelled, self.stateSyncActivityOwnerID == ownerID {
                if let snapshot = try? await self.project(id: projectID),
                   let pending = snapshot.pendingOperations.first(where: { $0.id == pendingID }),
                   let activity = NovelStateSyncActivity.project(
                       projectID: projectID,
                       branchID: branchID,
                       pending: pending,
                       snapshot: snapshot,
                       attemptOperationID: attemptOperationID,
                       startedAt: startedAt
                   ), self.stateSyncActivityOwnerID == ownerID {
                    let streamedCharacters = NovelStateSyncStreamProgress.shared.count(
                        pendingID: pendingID
                    )
                    self.stateSyncActivity = activity
                    self.updateStateSyncBackgroundProgress(
                        ownerID: ownerID,
                        activity: activity,
                        streamedCharacters: streamedCharacters
                    )
                }
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    private func stopStateSyncActivity(ownerID: UUID) {
        guard stateSyncActivityOwnerID == ownerID else { return }
        BackgroundGenerationKeepAlive.shared.end(
            novelStateSyncBackgroundLeaseID(for: ownerID)
        )
        stateSyncReportedWorkByOwnerID.removeValue(forKey: ownerID)
        stateSyncActivityTask?.cancel()
        stateSyncActivityTask = nil
        stateSyncActivityOwnerID = nil
        let keepVisible = automaticStateSyncKeepAlive &&
            stateSyncActivity.map { activity in
                automaticStateSyncPresentationTarget == NovelAutomaticStateSyncTarget(
                    projectID: activity.projectID,
                    branchID: activity.branchID
                )
            } == true
        if !keepVisible {
            stateSyncActivity = nil
        }
    }

    private func beginStateSyncBackgroundLease(
        ownerID: UUID,
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) {
        stateSyncReportedWorkByOwnerID[ownerID] = 0
        let ghostwriteLeaseID = novelGhostwriteBackgroundLeaseID(
            projectID: projectID,
            branchID: branchID
        )
        if BackgroundGenerationKeepAlive.shared.holdsLease(ghostwriteLeaseID) {
            BackgroundGenerationKeepAlive.shared.advanceProgress(
                ghostwriteLeaseID,
                by: 1,
                subtitle: "代笔中 · 剧情同步"
            )
            return
        }

        let leaseID = novelStateSyncBackgroundLeaseID(for: ownerID)
        let expire: () -> Void = { [weak self] in
            Task { @MainActor [weak self] in
                await self?.expireStateSyncBackgroundLease(
                    ownerID: ownerID,
                    projectID: projectID
                )
            }
        }
        BackgroundGenerationKeepAlive.shared.begin(
            leaseID,
            title: "Amber 小说创作中",
            subtitle: "剧情同步",
            onExpire: expire,
            onSystemTaskExpiration: expire
        )
        BackgroundGenerationKeepAlive.shared.updateProgress(
            leaseID,
            completed: 0,
            total: -1,
            subtitle: "剧情同步"
        )
    }

    private func updateStateSyncBackgroundProgress(
        ownerID: UUID,
        activity: NovelStateSyncActivity,
        streamedCharacters: Int
    ) {
        let reportedWork = Int64(
            max(0, activity.completedCharacters) + max(0, streamedCharacters)
        )
        let previousWork = stateSyncReportedWorkByOwnerID[ownerID] ?? 0
        let currentWork = max(previousWork, reportedWork)
        stateSyncReportedWorkByOwnerID[ownerID] = currentWork

        let leaseID = novelStateSyncBackgroundLeaseID(for: ownerID)
        if BackgroundGenerationKeepAlive.shared.holdsLease(leaseID) {
            BackgroundGenerationKeepAlive.shared.updateProgress(
                leaseID,
                completed: currentWork,
                subtitle: activity.statusTitle
            )
        }

        let ghostwriteLeaseID = novelGhostwriteBackgroundLeaseID(
            projectID: activity.projectID,
            branchID: activity.branchID
        )
        if BackgroundGenerationKeepAlive.shared.holdsLease(ghostwriteLeaseID) {
            BackgroundGenerationKeepAlive.shared.advanceProgress(
                ghostwriteLeaseID,
                by: currentWork - previousWork,
                subtitle: "代笔中 · 剧情同步"
            )
        }
    }

    private func expireStateSyncBackgroundLease(
        ownerID: UUID,
        projectID: NovelProjectID
    ) async {
        guard stateSyncActivityOwnerID == ownerID else { return }
        stateSyncReportedWorkByOwnerID.removeValue(forKey: ownerID)
        queuedAutomaticStateSyncTarget = nil
        manualStateSyncTask?.cancel()
        automaticStateSyncTask?.cancel()
        await creation.cancelInFlightBackgroundMutations(projectID: projectID)
    }

    private func beginContinuityBackgroundLease(
        ownerID: UUID,
        target: NovelAutomaticStateSyncTarget
    ) {
        let leaseID = novelContinuityBackgroundLeaseID(for: ownerID)
        let expire: () -> Void = { [weak self] in
            Task { @MainActor [weak self] in
                guard let self,
                      self.operationOwnerID == ownerID,
                      self.isAuditingContinuity else { return }
                self.continuityAuditExpirationOwnerID = ownerID
                self.continuityAuditFailureStorage = NovelContinuityAuditFailure(
                    target: target,
                    message: "后台执行时间已结束，请重新检查。"
                )
                self.continuityAuditTask?.cancel()
            }
        }
        BackgroundGenerationKeepAlive.shared.begin(
            leaseID,
            title: "Amber 小说创作中",
            subtitle: "剧情矛盾检查",
            onExpire: expire,
            onSystemTaskExpiration: expire
        )
    }

    private func startWorkspacePlotRelink(_ target: NovelAutomaticStateSyncTarget) {
        guard automaticStateSyncTask == nil,
              target != automaticStateSyncTarget else { return }
        automaticStateSyncTarget = target
        automaticStateSyncPresentationTarget = target
        if automaticStateSyncFailure?.target == target {
            automaticStateSyncFailure = nil
        }
        automaticStateSyncTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.automaticStateSyncTarget == target {
                    self.automaticStateSyncTarget = nil
                }
                if self.automaticStateSyncPresentationTarget == target {
                    self.automaticStateSyncPresentationTarget = nil
                }
                self.automaticStateSyncTask = nil
            }
            guard !Task.isCancelled else { return }
            do {
                try await self.creation.applyWorkspacePlotRelink(
                    projectID: target.projectID,
                    branchID: target.branchID
                )
                if self.automaticStateSyncFailure?.target == target {
                    self.automaticStateSyncFailure = nil
                }
                if self.selectedProjectID == target.projectID {
                    self.projectSnapshot = try await self.project(id: target.projectID)
                    if self.selectedBranchID == target.branchID {
                        self.branchSnapshot = try await self.branch(
                            projectID: target.projectID,
                            branchID: target.branchID
                        )
                    }
                }
            } catch is CancellationError {
                return
            } catch {
                self.publishAutomaticStateSyncFailure(
                    target: target,
                    message: NovelPresentation.stateSyncFailureMessage(
                        "剧情指针未能按章回填。\(error.localizedDescription)"
                    )
                )
            }
        }
    }

    private func startAutomaticStateSync(_ target: NovelAutomaticStateSyncTarget) {
        guard !userSuppressedStateSyncTargets.contains(target) else { return }
        if let failure = automaticStateSyncFailure, failure.target == target {
            if errorMessage == failure.message {
                errorMessage = nil
            }
            automaticStateSyncFailure = nil
        }
        automaticStateSyncTarget = target
        automaticStateSyncPresentationTarget = target
        automaticStateSyncTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let wasCancelled: Bool
            if Task.isCancelled || self.userSuppressedStateSyncTargets.contains(target) {
                wasCancelled = true
            } else {
                await self.runAutomaticStateSync(target)
                wasCancelled = Task.isCancelled ||
                    self.userSuppressedStateSyncTargets.contains(target)
            }
            if self.automaticStateSyncTarget == target {
                self.automaticStateSyncTarget = nil
            }
            if self.automaticStateSyncPresentationTarget == target {
                self.automaticStateSyncPresentationTarget = nil
            }
            if self.stateSyncActivityOwnerID == nil {
                self.stateSyncActivity = nil
            }
            self.automaticStateSyncTask = nil
            self.stateSyncStoppingTargets.remove(target)
            if wasCancelled {
                // Drop only the cancelled target from the queue; still start a
                // different queued branch so stop on A does not strand B.
                if self.queuedAutomaticStateSyncTarget == target {
                    self.queuedAutomaticStateSyncTarget = nil
                }
                if let queued = self.queuedAutomaticStateSyncTarget {
                    self.queuedAutomaticStateSyncTarget = nil
                    self.startAutomaticStateSync(queued)
                }
                return
            }
            if let queued = self.queuedAutomaticStateSyncTarget {
                self.queuedAutomaticStateSyncTarget = nil
                self.startAutomaticStateSync(queued)
            }
        }
    }

    private func runAutomaticStateSync(_ target: NovelAutomaticStateSyncTarget) async {
        automaticStateSyncKeepAlive = true
        defer { automaticStateSyncKeepAlive = false }
        // Let the saving caller finish its immediate refresh before the background
        // transaction begins reading the same branch.
        try? await Task.sleep(for: .milliseconds(250))
        var healAttempts = 0
        while !Task.isCancelled, !userSuppressedStateSyncTargets.contains(target) {
            let projectSnapshot: NovelProjectSnapshot
            let branchSnapshot: NovelBranchSnapshot
            do {
                projectSnapshot = try await project(id: target.projectID)
                branchSnapshot = try await branch(
                    projectID: target.projectID,
                    branchID: target.branchID
                )
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled,
                      !userSuppressedStateSyncTargets.contains(target) else { return }
                healAttempts += 1
                if healAttempts < NovelGhostwriteHeal.defaultMaxInfraRetries {
                    try? await Task.sleep(for: .milliseconds(200))
                    continue
                }
                // Banner only — do not set errorMessage (that triggers the global
                // "无法完成操作" alert and makes Retry look like an instant failure popup).
                publishAutomaticStateSyncFailure(
                    target: target,
                    message: NovelPresentation.stateSyncFailureMessage(for: error)
                )
                return
            }
            if selectedProjectID == target.projectID,
               selectedBranchID == target.branchID {
                self.projectSnapshot = projectSnapshot
                self.branchSnapshot = branchSnapshot
            }
            guard branchSnapshot.branch.syncStatus == .needsSync else {
                userSuppressedStateSyncTargets.remove(target)
                return
            }
            let branchPending = projectSnapshot.pendingOperations.filter {
                $0.branchID == target.branchID
            }
            let canDriveManualSync = branchPending.isEmpty ||
                (branchPending.count == 1 &&
                    branchPending[0].kind == .manualSync &&
                    (branchPending[0].status == .pending ||
                        branchPending[0].status == .retryable))
            guard canDriveManualSync else {
                // Previously returned silently — retry looked broken with no new banner.
                let message: String
                if branchPending.contains(where: { $0.kind != .manualSync }) {
                    message = "当前还有未完成的正文操作，请先处理后再重试剧情同步。"
                } else if branchPending.count > 1 {
                    message = "当前有多个未完成的同步任务，请重新打开项目后再试。"
                } else {
                    message = "剧情同步任务状态异常，请重新打开项目后再试。"
                }
                publishAutomaticStateSyncFailure(target: target, message: message)
                return
            }
            if isPerforming || branchSnapshot.branch.activeRunID != nil {
                try? await Task.sleep(for: .milliseconds(250))
                continue
            }
            guard !Task.isCancelled,
                  !userSuppressedStateSyncTargets.contains(target) else { return }

            let ghostwriteLeaseID = novelGhostwriteBackgroundLeaseID(
                projectID: target.projectID,
                branchID: target.branchID
            )
            guard UIApplication.shared.applicationState != .background ||
                    BackgroundGenerationKeepAlive.shared.holdsLease(ghostwriteLeaseID) else {
                return
            }

            lastStateSyncOperationError = nil
            lastStateSyncOperationCause = nil
            let succeeded: Bool
            if let pending = branchPending.first {
                succeeded = await perform(.retryPending(NovelRetryPendingCommand(
                    context: mutationContext(
                        projectRevision: projectSnapshot.project.revision
                    ),
                    projectID: target.projectID,
                    pendingID: pending.id
                )), reportsError: false)
            } else {
                succeeded = await perform(.syncManualEdits(NovelSyncManualEditsCommand(
                    context: mutationContext(
                        projectRevision: projectSnapshot.project.revision,
                        configRevision: projectSnapshot.project.configRevision,
                        branchHeadRevision: branchSnapshot.branch.headRevision
                    ),
                    projectID: target.projectID,
                    branchID: target.branchID,
                    pendingID: NovelPendingOperationID(),
                    checkpointID: NovelCheckpointID(),
                    stateSnapshotID: NovelStateSnapshotID(),
                    expectedWorkingRevision: branchSnapshot.branch.workingRevision
                )), reportsError: false)
            }
            // Success after a concurrent Stop still means the branch is synced —
            // lift suppress so the next edit can auto-schedule again.
            if succeeded {
                userSuppressedStateSyncTargets.remove(target)
                lastStateSyncOperationCause = nil
                lastStateSyncOperationError = nil
                return
            }
            if Task.isCancelled || userSuppressedStateSyncTargets.contains(target) {
                return
            }
            if let failure = lastStateSyncOperationCause as? NovelStructuredModelExecutionFailure,
               failure.allowsOutputRepair {
                let pending = self.projectSnapshot?.pendingOperations.first(where: {
                    $0.branchID == target.branchID && $0.kind == .manualSync
                })
                let completedChunks = pending?.manualSyncProgress?.completedChunks.count ?? 0
                let raw = pending?.lastError
                    ?? lastStateSyncOperationError
                    ?? failure.failure.message
                publishAutomaticStateSyncFailure(
                    target: target,
                    message: NovelPresentation.stateSyncFailureMessage(
                        raw,
                        completedChunkCount: completedChunks
                    )
                )
                return
            }
            healAttempts += 1
            if healAttempts < NovelGhostwriteHeal.defaultMaxInfraRetries {
                // Same pending, next attempt sees lastError in the current chunk.
                try? await Task.sleep(for: .milliseconds(200))
                continue
            }
            let pending = self.projectSnapshot?.pendingOperations.first(where: {
                $0.branchID == target.branchID && $0.kind == .manualSync
            })
            let completedChunks = pending?.manualSyncProgress?.completedChunks.count ?? 0
            let raw = pending?.lastError
                ?? lastStateSyncOperationError
                ?? "剧情同步没有完成，请点重试。大项目可能较久，请保持前台。"
            // If durable chunks exist, say so — retry continues from next segment,
            // it does not re-feed already completed manuscript.
            let message = NovelPresentation.stateSyncFailureMessage(
                raw,
                completedChunkCount: completedChunks
            )
            publishAutomaticStateSyncFailure(target: target, message: message)
            return
        }
    }

    /// Recoverable auto-sync failures stay on the status banner. Writing
    /// `errorMessage` would also fire `NovelCreationErrorAlertModifier`
    /// ("无法完成操作") on the workspace — the "retry instantly pops a dialog"
    /// bug users hit on the 正文 tab.
    private func publishAutomaticStateSyncFailure(
        target: NovelAutomaticStateSyncTarget,
        message: String
    ) {
        automaticStateSyncFailure = NovelAutomaticStateSyncFailure(
            target: target,
            message: message
        )
        // If a previous unrelated error left a modal up with the same text, clear
        // it so only the banner remains as the recovery surface.
        if errorMessage == message {
            errorMessage = nil
        }
    }

    private func reloadSelection(
        projectID: NovelProjectID,
        branchID preferredBranchID: NovelBranchID?
    ) async throws {
        let token = UUID()
        selectionToken = token
        let project = try await self.project(id: projectID)
        let branchID = preferredBranchID.flatMap { preferred in
            project.branches.contains(where: { $0.id == preferred && $0.lifecycle == .active })
                ? preferred
                : nil
        } ?? project.branches.first(where: {
            $0.id == project.project.mainBranchID && $0.lifecycle == .active
        })?.id ?? project.branches.first(where: { $0.lifecycle == .active })?.id
        let loadedBranch: NovelBranchSnapshot?
        if let branchID {
            loadedBranch = try await branch(projectID: projectID, branchID: branchID)
        } else {
            loadedBranch = nil
        }
        guard selectionToken == token else { return }
        selectedProjectID = projectID
        projectSnapshot = project
        selectedBranchID = branchID
        branchSnapshot = loadedBranch
        if let branchID { lastSelectedBranchIDs[projectID] = branchID }
        injectionPreview = nil
    }

    private func projectSummaries() async throws -> [NovelProjectSummary] {
        guard case .projects(let summaries) = try await creation.snapshot(.projects) else {
            throw NovelError.invalidInput("The project list returned an unexpected snapshot.")
        }
        return summaries
    }

    private func project(id: NovelProjectID) async throws -> NovelProjectSnapshot {
        guard case .project(let snapshot) = try await creation.snapshot(.project(id)) else {
            throw NovelError.invalidInput("The project returned an unexpected snapshot.")
        }
        return snapshot
    }

    private func branch(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelBranchSnapshot {
        guard case .branch(let snapshot) = try await creation.snapshot(
            .branch(projectID: projectID, branchID: branchID)
        ) else {
            throw NovelError.invalidInput("The branch returned an unexpected snapshot.")
        }
        return snapshot
    }

    private func rebasedBranchSnapshot(
        _ snapshot: NovelBranchSnapshot,
        onto project: NovelProjectSnapshot
    ) -> NovelBranchSnapshot {
        NovelBranchSnapshot(
            projectID: project.project.id,
            projectRevision: project.project.revision,
            configRevision: project.project.configRevision,
            branch: project.branches.first(where: { $0.id == snapshot.branch.id })
                ?? snapshot.branch,
            session: snapshot.session,
            headCheckpoint: snapshot.headCheckpoint,
            currentState: snapshot.currentState,
            chapterSelections: snapshot.chapterSelections,
            activeSettingProposals: snapshot.activeSettingProposals,
            access: project.access
        )
    }

    private func clearSelection() {
        selectionToken = UUID()
        selectedProjectID = nil
        selectedBranchID = nil
        projectSnapshot = nil
        branchSnapshot = nil
        injectionPreview = nil
    }

    private func mutationContext(
        projectRevision: Int64? = nil,
        configRevision: Int64? = nil,
        branchHeadRevision: Int64? = nil
    ) -> NovelMutationContext {
        NovelMutationContext(
            operationID: NovelOperationID(),
            expectedProjectRevision: projectRevision,
            expectedConfigRevision: configRevision,
            expectedBranchHeadRevision: branchHeadRevision
        )
    }

    private func report(_ error: Error) {
        reloadNoticeMessage = nil
        errorMessage = errorDescription(error)
    }

    private func errorDescription(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }
}
