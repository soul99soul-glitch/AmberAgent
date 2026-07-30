import Foundation
import Observation

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
    case failed(message: String)
    case persistenceBlocked(runID: NovelRunID, message: String)
    case refreshFailed(message: String)
}

enum NovelBranchSelectionResult: Equatable, Sendable {
    case selected
    case requiresStoppingActiveRun
    case failed
}

private struct NovelQuickStartOwner: Hashable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

private struct NovelAutomaticStateSyncTarget: Equatable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

private struct NovelAutomaticStateSyncFailure: Equatable, Sendable {
    let target: NovelAutomaticStateSyncTarget
    let message: String
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

    var displayedCompletionFraction: Double? {
        guard completedCharacters > 0 else { return nil }
        return completionFraction
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
    private(set) var continuityAuditFailure: String?
    private(set) var isAuditingContinuity = false
    @ObservationIgnored private var continuityAuditTask: Task<Void, Never>?
    var isLoading = false
    private(set) var isPerforming = false
    private(set) var stateSyncActivity: NovelStateSyncActivity?
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
    @ObservationIgnored private var operationOwnerID: UUID?
    @ObservationIgnored private var stateSyncActivityOwnerID: UUID?
    @ObservationIgnored private var stateSyncActivityTask: Task<Void, Never>?
    @ObservationIgnored private var automaticStateSyncTask: Task<Void, Never>?
    @ObservationIgnored private var automaticStateSyncTarget: NovelAutomaticStateSyncTarget?
    @ObservationIgnored private var queuedAutomaticStateSyncTarget: NovelAutomaticStateSyncTarget?
    private var automaticStateSyncPresentationTarget: NovelAutomaticStateSyncTarget?
    private var automaticStateSyncFailure: NovelAutomaticStateSyncFailure?

    init(creation: any NovelCreation) {
        self.creation = creation
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
        isPerforming || automaticStateSyncPresentationTarget != nil
    }

    func canCancelAutomaticStateSync(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) -> Bool {
        automaticStateSyncPresentationTarget == NovelAutomaticStateSyncTarget(
            projectID: projectID,
            branchID: branchID
        )
    }

    func cancelAutomaticStateSync(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) {
        guard canCancelAutomaticStateSync(projectID: projectID, branchID: branchID) else {
            return
        }
        automaticStateSyncTask?.cancel()
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
        scheduleAutomaticStateSync(projectID: projectID, branchID: branchID)
    }

    func waitForBackgroundGeneration() async {
        while !Task.isCancelled, await hasActiveBackgroundGeneration() {
            try? await Task.sleep(for: .milliseconds(250))
        }
    }

    func interruptSessionForBackground(deadline: Date) async {
        automaticStateSyncTask?.cancel()
        queuedAutomaticStateSyncTarget = nil
        let summaries = (try? await projectSummaries()) ?? []
        var projectIDs = Set(summaries.map(\.id))
        if let selectedProjectID { projectIDs.insert(selectedProjectID) }
        if let quickStartStartingProjectID { projectIDs.insert(quickStartStartingProjectID) }
        for projectID in projectIDs.sorted(by: { $0.description < $1.description }) {
            await interruptSessionForBackground(
                projectID: projectID,
                runID: nil,
                deadline: deadline
            )
        }
    }

    private func hasActiveBackgroundGeneration() async -> Bool {
        if isPerforming || automaticStateSyncTask != nil || quickStartStartingProjectID != nil {
            return true
        }
        guard let summaries = try? await projectSummaries() else { return false }
        for summary in summaries where summary.loadError == nil {
            if let snapshot = try? await project(id: summary.id),
               snapshot.activeRuns.contains(where: { $0.status == .running }) {
                return true
            }
        }
        return false
    }

    func loadProjects(selecting preferredProjectID: NovelProjectID? = nil) async {
        isLoading = true
        defer { isLoading = false }
        do {
            projects = try await projectSummaries()
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
        } catch {
            report(error)
        }
    }

    @discardableResult
    func selectProject(_ projectID: NovelProjectID) async -> Bool {
        if selectedProjectID != projectID, isProjectSelectionBlocked {
            report(NovelError.projectBusy(selectedProjectID ?? projectID))
            return false
        }
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
            let branchID = project.branches.first(where: {
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
    ///   - exactUserText: 重试专用。`retryGeneration` 从失败 run 的**持久化** user 消息
    ///     取回原文并原样重发,以满足 `isEligibleForExactRetry` 的「精确重试」契约。
    ///     若不传,重试会退回默认文案、把用户填过的调整方向静默丢掉(真机实测过的缺陷)。
    @discardableResult
    func startQuickStartSuggestions(
        guidance: String? = nil,
        exactUserText: String? = nil
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
        let userText: String
        if let exactUserText, !exactUserText.isEmpty {
            userText = exactUserText
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
            baseCheckpointID: branch.branch.headCheckpointID,
            baseHeadRevision: branch.branch.headRevision,
            status: .running,
            partialContent: "",
            receiptID: request.generationReceiptID,
            startedAt: Date(),
            terminalAt: nil,
            interruptionReason: nil,
            terminalFailure: nil
        )
        quickStartStartingProjectID = projectID
        quickStartStartingRun = placeholder
        cancelledQuickStartRunIDs.remove(request.id)
        quickStartStatuses[owner] = .starting(run: placeholder)
        quickStartTasks[owner]?.cancel()
        quickStartTaskRunIDs[owner] = request.id
        quickStartTasks[owner] = Task { @MainActor [weak self] in
            await self?.runQuickStart(request, owner: owner)
        }
        return request.id
    }

    private func runQuickStart(_ request: NovelRunRequest, owner: NovelQuickStartOwner) async {
        guard !Task.isCancelled,
              quickStartTaskRunIDs[owner] == request.id,
              !cancelledQuickStartRunIDs.contains(request.id) else { return }
        let run: NovelRun
        quickStartCreationStartRunIDs.insert(request.id)
        do {
            run = try await creation.start(request)
            quickStartCreationStartRunIDs.remove(request.id)
        } catch {
            quickStartCreationStartRunIDs.remove(request.id)
            guard quickStartTaskRunIDs[owner] == request.id else { return }
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
              !cancelledQuickStartRunIDs.contains(request.id) else { return }
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
            case .delta, .replaced:
                continue
            case .completed:
                do {
                    try await refreshCurrentSelection(projectID: owner.projectID)
                    guard quickStartTaskRunIDs[owner] == run.id else { return }
                    quickStartStatuses[owner] = nil
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

    func renameProject(_ name: String) async {
        guard let snapshot = projectSnapshot else { return }
        _ = await perform(.renameProject(NovelRenameProjectCommand(
            context: mutationContext(projectRevision: snapshot.project.revision),
            projectID: snapshot.project.id,
            name: name
        )))
    }

    @discardableResult
    func stopActiveRunsForProjectOperation(projectID: NovelProjectID) async -> Bool {
        let ownerID = UUID()
        guard acquireOperation(ownerID: ownerID) else { return false }
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
        await loadProjects()
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

    func deleteMaterial(_ materialID: NovelMaterialID) async {
        guard let snapshot = projectSnapshot else { return }
        _ = await perform(.deleteMaterial(NovelDeleteMaterialCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: snapshot.project.id,
            materialID: materialID
        )))
    }

    func setModelPolicy(
        _ policy: NovelProjectModelPolicy,
        for purpose: NovelModelRole = .creation
    ) async {
        guard let snapshot = projectSnapshot else { return }
        _ = await perform(.setModelPolicy(NovelSetModelPolicyCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: snapshot.project.id,
            purpose: purpose,
            policy: policy
        )))
    }

    func setPolishPreference(_ preference: String) async {
        guard let snapshot = projectSnapshot else { return }
        _ = await perform(.setPolishPreference(NovelSetPolishPreferenceCommand(
            context: mutationContext(configRevision: snapshot.project.configRevision),
            projectID: snapshot.project.id,
            preference: preference
        )))
    }

    func resolveProposal(
        _ proposalID: NovelProposalID,
        resolution: NovelSettingProposalResolution
    ) async {
        guard let project = projectSnapshot, let branch = branchSnapshot else { return }
        _ = await perform(.resolveSettingProposal(NovelResolveSettingProposalCommand(
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

    func setBranchMaterialOverride(
        materialID: NovelMaterialID,
        change: NovelBranchMaterialOverrideChange
    ) async {
        guard let project = projectSnapshot, let branch = branchSnapshot else { return }
        _ = await perform(.setBranchMaterialOverride(NovelSetBranchMaterialOverrideCommand(
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
    func setChapterDiscarded(_ isDiscarded: Bool, chapterID: NovelChapterID) async {
        guard let project = projectSnapshot,
              let branch = branchSnapshot,
              let chapter = project.chapters.first(where: { $0.id == chapterID }),
              (chapter.discardedAt != nil) != isDiscarded else {
            return
        }
        let context = mutationContext(
            projectRevision: project.project.revision,
            branchHeadRevision: branch.branch.headRevision
        )
        if isDiscarded {
            _ = await perform(.discardChapter(NovelDiscardChapterCommand(
                context: context,
                projectID: project.project.id,
                branchID: branch.branch.id,
                chapterID: chapterID
            )))
        } else {
            _ = await perform(.restoreChapter(NovelRestoreChapterCommand(
                context: context,
                projectID: project.project.id,
                branchID: branch.branch.id,
                chapterID: chapterID
            )))
        }
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
        content: String
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
        if saved {
            scheduleAutomaticStateSync(
                projectID: project.project.id,
                branchID: branch.branch.id
            )
        }
        return saved
    }

    func startSessionRun(_ request: NovelRunRequest) async throws -> NovelRun {
        try await creation.start(request)
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
            $0.projectID == projectID
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
        guard branchPending.isEmpty ||
                (branchPending.count == 1 &&
                    branchPending[0].kind == .manualSync &&
                    branchPending[0].status == .pending) else {
            return
        }
        scheduleAutomaticStateSync(
            projectID: project.project.id,
            branchID: branch.branch.id
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
        guard target != automaticStateSyncTarget,
              target != queuedAutomaticStateSyncTarget else { return }
        guard automaticStateSyncTask == nil else {
            queuedAutomaticStateSyncTarget = target
            return
        }
        startAutomaticStateSync(target)
    }

    func retryPending(_ pendingID: NovelPendingOperationID) async {
        guard let project = projectSnapshot else { return }
        _ = await perform(.retryPending(NovelRetryPendingCommand(
            context: mutationContext(projectRevision: project.project.revision),
            projectID: project.project.id,
            pendingID: pendingID
        )))
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
        let ownerID = UUID()
        guard acquireOperation(ownerID: ownerID) else {
            // 抢不到锁不能静默返回:用户看到的就是「点了没反应」。
            continuityAuditFailure = "有别的操作正在进行，请稍后再试。"
            return nil
        }
        defer { releaseOperation(ownerID: ownerID) }
        do {
            let plan = try await creation.planContinuityAudit(
                projectID: projectID,
                branchID: branchID
            )
            continuityAuditFailure = nil
            errorMessage = nil
            return plan
        } catch {
            continuityAuditFailure = errorDescription(error)
            return nil
        }
    }

    /// 界面用这个入口发起扫描:任务句柄留在 ViewModel 里,「停止扫描」才有东西可取消。
    func startContinuityAudit() {
        guard continuityAuditTask == nil else { return }
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
        let ownerID = UUID()
        guard acquireOperation(ownerID: ownerID) else {
            continuityAuditFailure = "有别的操作正在进行，请稍后再试。"
            return
        }
        isAuditingContinuity = true
        defer {
            isAuditingContinuity = false
            releaseOperation(ownerID: ownerID)
        }
        do {
            let audit = try await creation.auditContinuity(
                projectID: projectID,
                branchID: branchID
            )
            continuityAuditReport = audit
            continuityAuditFailure = nil
            errorMessage = nil
        } catch is CancellationError {
            // 用户自己按的停止,不是故障,不必弹错误。
            continuityAuditFailure = nil
        } catch {
            continuityAuditFailure = errorDescription(error)
        }
    }

    func clearContinuityAudit() {
        continuityAuditReport = nil
        continuityAuditFailure = nil
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
        choice: NovelProjectImportChoice
    ) async -> NovelProjectImportResult? {
        guard let preview = await previewImport(data) else { return nil }
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
        reportsError: Bool = true
    ) async -> Bool {
        if let projectID, selectedProjectID != projectID, isProjectSelectionBlocked {
            report(NovelError.projectBusy(selectedProjectID ?? action.projectID))
            return false
        }
        let ownerID = UUID()
        guard reloadNoticeProjectID != action.projectID,
              acquireOperation(ownerID: ownerID) else { return false }
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
            if reportsError { report(operationError) }
            return false
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
        let startedAt = Date()
        stateSyncActivityOwnerID = ownerID
        stateSyncActivity = .preparing(
            projectID: projectID,
            branchID: branchID,
            pendingID: pendingID,
            startedAt: startedAt
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
                    self.stateSyncActivity = activity
                }
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    private func stopStateSyncActivity(ownerID: UUID) {
        guard stateSyncActivityOwnerID == ownerID else { return }
        stateSyncActivityTask?.cancel()
        stateSyncActivityTask = nil
        stateSyncActivityOwnerID = nil
        stateSyncActivity = nil
    }

    private func startAutomaticStateSync(_ target: NovelAutomaticStateSyncTarget) {
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
            await self.runAutomaticStateSync(target)
            self.automaticStateSyncTask = nil
            self.automaticStateSyncTarget = nil
            self.automaticStateSyncPresentationTarget = nil
            if let queued = self.queuedAutomaticStateSyncTarget {
                self.queuedAutomaticStateSyncTarget = nil
                self.startAutomaticStateSync(queued)
            }
        }
    }

    private func runAutomaticStateSync(_ target: NovelAutomaticStateSyncTarget) async {
        // Let the saving caller finish its immediate refresh before the background
        // transaction begins reading the same branch.
        try? await Task.sleep(for: .milliseconds(250))
        while !Task.isCancelled {
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
                guard !Task.isCancelled else { return }
                let message = "自动剧情同步准备失败：\(errorDescription(error))"
                automaticStateSyncFailure = NovelAutomaticStateSyncFailure(
                    target: target,
                    message: message
                )
                errorMessage = message
                return
            }
            guard branchSnapshot.branch.syncStatus == .needsSync else { return }
            let branchPending = projectSnapshot.pendingOperations.filter {
                $0.branchID == target.branchID
            }
            guard branchPending.isEmpty ||
                    (branchPending.count == 1 &&
                        branchPending[0].kind == .manualSync &&
                        branchPending[0].status == .pending) else {
                return
            }
            if isPerforming || branchSnapshot.branch.activeRunID != nil {
                try? await Task.sleep(for: .milliseconds(250))
                continue
            }

            if let pending = branchPending.first {
                _ = await perform(.retryPending(NovelRetryPendingCommand(
                    context: mutationContext(
                        projectRevision: projectSnapshot.project.revision
                    ),
                    projectID: target.projectID,
                    pendingID: pending.id
                )), reportsError: false)
            } else {
                _ = await perform(.syncManualEdits(NovelSyncManualEditsCommand(
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
            return
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
