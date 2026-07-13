import Foundation
import Observation

struct NovelSessionBinding: Equatable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

private struct NovelSessionRunDraft: Equatable, Sendable {
    let kind: NovelRunKind
    let mode: NovelSessionMode
    let granularity: NovelGenerationGranularity?
    let userText: String
    let sourceChapterVersionID: NovelChapterVersionID?
    let injectionOverrides: NovelInjectionOverrides
    let inputBudgetTokens: Int
}

@MainActor
@Observable
final class NovelSessionViewModel {
    var mode: NovelSessionMode = .writeProse
    var granularity: NovelGenerationGranularity = .wholeChapter
    private(set) var binding: NovelSessionBinding?
    private(set) var transientTail: NovelSessionTransientTail?
    private(set) var sessionStartingRunID: NovelRunID?
    private(set) var isPerformingAction = false
    private(set) var operationErrorMessage: String?
    private(set) var refreshErrorMessage: String?
    private(set) var lastFailure: NovelFailure?

    @ObservationIgnored private let workspace: NovelCreationViewModel
    @ObservationIgnored private var consumerTask: Task<Void, Never>?
    @ObservationIgnored private var consumerID: UUID?
    @ObservationIgnored private var attachAttemptID: UUID?
    @ObservationIgnored private var attachingRunID: NovelRunID?
    @ObservationIgnored private var attachingBindingToken: UUID?
    @ObservationIgnored private var bindingToken = UUID()
    @ObservationIgnored private var currentRunDraft: NovelSessionRunDraft?
    @ObservationIgnored private var lastRetryDraft: NovelSessionRunDraft?
    @ObservationIgnored private var lastRetryRunID: NovelRunID?
    @ObservationIgnored private var transientRunRecord: NovelActiveRunRecord?
    @ObservationIgnored private var terminalAwaitingRefresh = false
    @ObservationIgnored private var cancelledStartRunIDs: Set<NovelRunID> = []
    @ObservationIgnored private var sessionActionOwnerID: UUID?

    init(workspace: NovelCreationViewModel) {
        self.workspace = workspace
        granularity = workspace.projectSnapshot?.project.lastGenerationGranularity ?? .wholeChapter
    }

    var durableMessages: [NovelSessionMessageRecord] {
        guard snapshotMatchesBinding else { return [] }
        return workspace.branchSnapshot?.session.messages ?? []
    }

    var currentChapterVersions: [NovelChapterVersionRecord] {
        guard snapshotMatchesBinding,
              let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else { return [] }
        let versionsByID = Dictionary(uniqueKeysWithValues: project.chapterVersions.map { ($0.id, $0) })
        return branch.chapterSelections.compactMap { versionsByID[$0.versionID] }
    }

    var availableProseCandidates: [NovelCandidateRecord] {
        candidates(kind: .prose).filter { $0.status == .available }
    }

    var availablePolishCandidates: [NovelCandidateRecord] {
        candidates(kind: .polish).filter { $0.status == .available }
    }

    var branchPendingOperations: [NovelPendingOperationRecord] {
        guard let branchID = binding?.branchID else { return [] }
        return workspace.projectSnapshot?.pendingOperations.filter { $0.branchID == branchID } ?? []
    }

    var branchPolishTransactions: [NovelPendingPolishTransactionRecord] {
        guard let branchID = binding?.branchID else { return [] }
        return workspace.projectSnapshot?.polishTransactions.filter { $0.branchID == branchID } ?? []
    }

    var access: NovelProjectLoadAccess? {
        guard snapshotMatchesBinding else { return nil }
        return workspace.projectSnapshot?.access
    }

    var needsSync: Bool {
        workspace.branchSnapshot?.branch.syncStatus == .needsSync
    }

    var activeRunID: NovelRunID? {
        if terminalAwaitingRefresh { return nil }
        if let tail = transientTail {
            switch tail.phase {
            case .waitingForFirstToken, .streaming:
                return tail.runID
            case .persistenceBlocked, .terminalAwaitingRefresh, .interrupted, .failed:
                return nil
            }
        }
        return activeRun?.id ?? boundQuickStartStartingRun?.id
    }

    var isStarting: Bool {
        sessionStartingRunID != nil || boundQuickStartStartingRun != nil
    }

    var isRunning: Bool {
        activeRunID != nil
    }

    var isStreaming: Bool {
        guard !terminalAwaitingRefresh else { return false }
        return switch transientTail?.phase {
        case .waitingForFirstToken, .streaming: true
        case .persistenceBlocked, .terminalAwaitingRefresh, .interrupted, .failed, nil: false
        }
    }

    var isBusy: Bool {
        isStarting || isPerformingAction || workspace.isPerforming
    }

    var canSend: Bool {
        canStart(kind: mode == .discussPlan ? .discussion : .prose)
    }

    var canStop: Bool {
        activeRunID != nil && access == .readWrite && !isPerformingAction
    }

    var canRetryLastTerminal: Bool {
        guard lastRetryDraft != nil,
              let runID = lastRetryRunID,
              !isRunning,
              !isBusy,
              access == .readWrite,
              let run = workspace.projectSnapshot?.activeRuns.first(where: { $0.id == runID }) else {
            return false
        }
        return run.kind != .quickStart &&
            canStart(kind: run.kind) &&
            isEligibleForExactRetry(run)
    }

    var canRetryPendingTerminal: Bool {
        guard case .persistenceBlocked = transientTail?.phase else { return false }
        return access == .readWrite && !workspace.requiresReload && !isPerformingAction
    }

    var errorMessage: String? {
        operationErrorMessage ?? refreshErrorMessage
    }

    var hasRefreshError: Bool {
        refreshErrorMessage != nil
    }

    func bindToCurrentSelection() async {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              workspace.selectedProjectID == project.project.id,
              workspace.selectedBranchID == branch.branch.id else {
            resetBinding()
            return
        }
        let next = NovelSessionBinding(
            projectID: project.project.id,
            branchID: branch.branch.id
        )
        let didChange = binding != next
        if didChange {
            detachConsumer()
            bindingToken = UUID()
            binding = next
            transientTail = nil
            transientRunRecord = nil
            terminalAwaitingRefresh = false
            currentRunDraft = nil
            lastRetryDraft = nil
            lastRetryRunID = nil
            operationErrorMessage = nil
            refreshErrorMessage = nil
            lastFailure = nil
            granularity = project.project.lastGenerationGranularity
        }
        hydrateTerminalState()
        let currentActiveRun = activeRun
        if let run = currentActiveRun,
           consumerTask == nil || transientTail?.runID != run.id {
            await attach(to: run)
        } else if let startingRun = boundQuickStartStartingRun {
            if transientTail?.runID != startingRun.id {
                installTail(
                    run: startingRun,
                    content: "",
                    phase: .waitingForFirstToken
                )
            }
        } else if currentActiveRun == nil,
                  !terminalAwaitingRefresh,
                  isActiveTailPhase(transientTail?.phase) {
            transientTail = nil
            transientRunRecord = nil
        }
    }

    @discardableResult
    func refresh() async -> Bool {
        guard let binding else {
            await bindToCurrentSelection()
            return self.binding != nil
        }
        return await refreshDurable(binding: binding, token: bindingToken)
    }

    func detachConsumer() {
        consumerID = nil
        consumerTask?.cancel()
        consumerTask = nil
        attachAttemptID = nil
        attachingRunID = nil
        attachingBindingToken = nil
    }

    func clearError() {
        operationErrorMessage = nil
        refreshErrorMessage = nil
        lastFailure = nil
    }

    @discardableResult
    func send(
        text: String,
        injectionOverrides: NovelInjectionOverrides = .none,
        inputBudgetTokens: Int = 16_000
    ) async -> Bool {
        let kind: NovelRunKind = mode == .discussPlan ? .discussion : .prose
        let draft = NovelSessionRunDraft(
            kind: kind,
            mode: mode,
            granularity: kind == .prose ? granularity : nil,
            userText: text,
            sourceChapterVersionID: nil,
            injectionOverrides: injectionOverrides,
            inputBudgetTokens: inputBudgetTokens
        )
        return await start(draft)
    }

    func stop(reason: NovelRunInterruptionReason = .user) async {
        _ = await interruptBoundRun(reason: reason)
    }

    @discardableResult
    func interruptForRouteExit() async -> Bool {
        if binding == nil {
            await bindToCurrentSelection()
        }
        guard !isPerformingAction,
              !workspace.isPerforming || isStarting else { return false }
        if activeRunID != nil {
            guard await interruptBoundRun(reason: .routeExit) else { return false }
        }
        detachConsumer()
        return true
    }

    func interruptForBackground(deadline: Date = Date().addingTimeInterval(5)) async {
        if binding == nil {
            await bindToCurrentSelection()
        }
        guard let projectID = binding?.projectID else { return }
        let ownsSessionAction = !isPerformingAction
        let backgroundOwnerID = UUID()
        let ownsWorkspaceBusy = workspace.acquireSessionOperation(ownerID: backgroundOwnerID)
        if ownsSessionAction { isPerformingAction = true }
        defer {
            if ownsWorkspaceBusy {
                workspace.releaseSessionOperation(ownerID: backgroundOwnerID)
            }
            if ownsSessionAction { isPerformingAction = false }
        }
        let startingRunID = isStarting ? activeRunID : nil
        if let startingRunID, sessionStartingRunID == startingRunID {
            cancelledStartRunIDs.insert(startingRunID)
        }
        await workspace.interruptSessionForBackground(
            projectID: projectID,
            runID: activeRunID,
            deadline: deadline
        )
        let refreshed = await refreshDurable(binding: binding, token: bindingToken)
        if refreshed,
           let startingRunID,
           workspace.projectSnapshot?.activeRuns.contains(where: {
               $0.id == startingRunID && $0.status == .running
           }) != true {
            releaseSessionStartOwnership(runID: startingRunID)
            if transientTail?.runID == startingRunID {
                clearTransientTail()
            }
            operationErrorMessage = nil
        }
    }

    @discardableResult
    func retryLastTerminal() async -> Bool {
        guard let runID = lastRetryRunID else { return false }
        return await retryGeneration(runID: runID)
    }

    @discardableResult
    func retryGeneration(runID: NovelRunID) async -> Bool {
        if terminalAwaitingRefresh, !(await refresh()) { return false }
        guard let run = workspace.projectSnapshot?.activeRuns.first(where: {
            $0.id == runID && $0.branchID == binding?.branchID &&
                ($0.status == .failed || $0.status == .interrupted)
        }), isEligibleForExactRetry(run) else { return false }
        if run.kind == .quickStart {
            guard let retryRunID = await workspace.startQuickStartSuggestions(),
                  retryRunID != runID else { return false }
            operationErrorMessage = nil
            refreshErrorMessage = nil
            lastFailure = nil
            lastRetryDraft = nil
            lastRetryRunID = nil
            await bindToCurrentSelection()
            return activeRunID == retryRunID
        }
        guard let draft = draft(for: run) else { return false }
        return await start(draft)
    }

    func retryPendingTerminal() async {
        guard let runID = transientTail?.runID,
              canRetryPendingTerminal,
              beginAction() else { return }
        defer { endAction() }
        do {
            try await workspace.retrySessionTerminal(runID: runID)
            operationErrorMessage = nil
            if transientTail?.runID == runID {
                updateTail(phase: .terminalAwaitingRefresh)
                terminalAwaitingRefresh = true
            }
        } catch {
            operationErrorMessage = describe(error)
        }
        _ = await refreshDurable(binding: binding, token: bindingToken)
    }

    func candidate(id: NovelCandidateID) -> NovelCandidateRecord? {
        guard let branchID = binding?.branchID else { return nil }
        return workspace.projectSnapshot?.candidates.first {
            $0.id == id && $0.branchID == branchID
        }
    }

    func collectionGranularity(for candidateID: NovelCandidateID) -> NovelGenerationGranularity {
        guard let project = workspace.projectSnapshot else { return granularity }
        if let direct = project.activeRuns.first(where: { $0.candidateID == candidateID })?.granularity {
            return direct
        }
        guard let candidate = project.candidates.first(where: { $0.id == candidateID }) else {
            return project.project.lastGenerationGranularity
        }
        let sourceRunID = project.sessions
            .first(where: { $0.id == candidate.sessionID })?
            .messages
            .first(where: { $0.id == candidate.sourceMessageID })?
            .runID
        if let sourceRunID,
           let sourceGranularity = project.activeRuns.first(where: {
               $0.id == sourceRunID
           })?.granularity {
            return sourceGranularity
        }
        if let sourceCandidateID = candidate.clonedFromCandidateID,
           let sourceGranularity = project.activeRuns.first(where: {
               $0.candidateID == sourceCandidateID
           })?.granularity {
            return sourceGranularity
        }
        return project.project.lastGenerationGranularity
    }

    func paragraphs(candidateID: NovelCandidateID) -> [NovelParagraphRecord] {
        guard let candidate = candidate(id: candidateID) else { return [] }
        return NovelParagraphParser.paragraphs(in: candidate.content)
    }

    @discardableResult
    func collectCandidate(
        _ candidateID: NovelCandidateID,
        selection: NovelParagraphSelection,
        target: NovelCollectionTarget
    ) async -> Bool {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let candidate = candidate(id: candidateID),
              candidate.kind == .prose,
              candidate.status == .available,
              branch.branch.syncStatus == .synchronized,
              branchPendingOperations.isEmpty,
              snapshotMatchesBinding else { return false }
        do {
            _ = try NovelParagraphParser.selectedText(for: selection, in: candidate.content)
        } catch {
            operationErrorMessage = describe(error)
            return false
        }
        let action = NovelAction.collectCandidate(NovelCollectCandidateCommand(
            context: mutationContext(project: project, branch: branch),
            projectID: project.project.id,
            branchID: branch.branch.id,
            pendingID: NovelPendingOperationID(),
            candidateID: candidate.id,
            selection: selection,
            target: target,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        ))
        return await perform(action) != nil
    }

    func cloneCollectedProse(_ candidateID: NovelCandidateID) async -> NovelCandidateID? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let source = candidate(id: candidateID),
              source.kind == .prose,
              source.status == .collected,
              snapshotMatchesBinding else { return nil }
        let clonedID = NovelCandidateID()
        let outcome = await perform(.cloneCandidate(NovelCloneCandidateCommand(
            context: mutationContext(project: project, branch: branch),
            projectID: project.project.id,
            branchID: branch.branch.id,
            sourceCandidateID: candidateID,
            candidateID: clonedID
        )))
        guard case .candidateCloned(_, _, candidateID, let actualID, _) = outcome,
              candidateID == source.id,
              actualID == clonedID else { return nil }
        return clonedID
    }

    @discardableResult
    func forkFromCheckpoint(
        _ checkpointID: NovelCheckpointID,
        name: String
    ) async -> NovelBranchID? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              project.checkpoints.contains(where: {
                  $0.id == checkpointID && $0.kind != .initial
              }), snapshotMatchesBinding,
              !isRunning else {
            operationErrorMessage = "当前状态不能从这个存档点创建分支。"
            return nil
        }
        let branchID = await workspace.forkBranch(
            from: branch.branch.id,
            checkpointID: checkpointID,
            name: name
        )
        operationErrorMessage = branchID == nil ? workspace.presentedMessage : nil
        await bindToCurrentSelection()
        return branchID
    }

    func retryPending(_ pendingID: NovelPendingOperationID) async {
        guard let project = workspace.projectSnapshot,
              project.pendingOperations.contains(where: { $0.id == pendingID }),
              snapshotMatchesBinding else { return }
        _ = await perform(.retryPending(NovelRetryPendingCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: project.project.revision,
                expectedConfigRevision: project.project.configRevision,
                expectedBranchHeadRevision: workspace.branchSnapshot?.branch.headRevision
            ),
            projectID: project.project.id,
            pendingID: pendingID
        )))
    }

    @discardableResult
    func startWholeChapterPolish(chapterID: NovelChapterID? = nil) async -> Bool {
        let source: NovelChapterVersionRecord?
        if let chapterID {
            source = currentChapterVersions.first { $0.chapterID == chapterID }
        } else {
            source = currentChapterVersions.last
        }
        guard let source else {
            operationErrorMessage = "当前分支还没有可润色的正式章节。"
            return false
        }
        let draft = NovelSessionRunDraft(
            kind: .polish,
            mode: .writeProse,
            granularity: nil,
            userText: "请在不改变任何剧情事实的前提下润色《\(source.title)》。",
            sourceChapterVersionID: source.id,
            injectionOverrides: .none,
            inputBudgetTokens: 16_000
        )
        return await start(draft)
    }

    func adoptPolishCandidate(_ candidateID: NovelCandidateID) async {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let candidate = candidate(id: candidateID),
              candidate.kind == .polish,
              candidate.status == .available,
              snapshotMatchesBinding else { return }
        let command = NovelAdoptPolishCandidateCommand(
            context: mutationContext(project: project, branch: branch),
            projectID: project.project.id,
            branchID: branch.branch.id,
            transactionID: NovelPendingOperationID(),
            candidateID: candidate.id,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            expectedWorkingRevision: branch.branch.workingRevision
        )
        await applyPolishAdoption(command)
    }

    func retryPolishTransaction(_ transactionID: NovelPendingOperationID) async {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let transaction = project.polishTransactions.first(where: {
                  $0.id == transactionID && $0.branchID == branch.branch.id &&
                      ($0.status == .pending || $0.status == .retryable)
              }), snapshotMatchesBinding else { return }
        let command = NovelAdoptPolishCandidateCommand(
            context: NovelMutationContext(
                operationID: transaction.operationID,
                expectedProjectRevision: project.project.revision,
                expectedConfigRevision: project.project.configRevision,
                expectedBranchHeadRevision: branch.branch.headRevision
            ),
            projectID: project.project.id,
            branchID: branch.branch.id,
            transactionID: transaction.id,
            candidateID: transaction.candidateID,
            proposedChapterVersionID: transaction.proposedChapterVersionID,
            checkpointID: transaction.checkpointID,
            expectedWorkingRevision: branch.branch.workingRevision
        )
        await applyPolishAdoption(command)
    }

    func abandonPolishTransaction(_ transactionID: NovelPendingOperationID) async {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              project.polishTransactions.contains(where: {
                  $0.id == transactionID && $0.branchID == branch.branch.id
              }), snapshotMatchesBinding else { return }
        _ = await perform(.abandonPolishTransaction(
            NovelAbandonPolishTransactionCommand(
                context: mutationContext(project: project, branch: branch),
                projectID: project.project.id,
                branchID: branch.branch.id,
                transactionID: transactionID
            )
        ))
    }

    @discardableResult
    func convertPolishCandidateToManualRewrite(_ candidateID: NovelCandidateID) async -> Bool {
        guard !workspace.requiresReload,
              let candidate = candidate(id: candidateID),
              candidate.kind == .polish,
              let sourceID = candidate.sourceChapterVersionID,
              let source = workspace.projectSnapshot?.chapterVersions.first(where: { $0.id == sourceID }),
              currentChapterVersions.contains(where: { $0.id == source.id }) else {
            return false
        }
        isPerformingAction = true
        workspace.clearError()
        let saved = await workspace.saveManualRewrite(
            chapterID: source.chapterID,
            title: source.title,
            content: candidate.content
        )
        isPerformingAction = false
        operationErrorMessage = workspace.errorMessage
        _ = await refreshDurable(binding: binding, token: bindingToken)
        return saved
    }

    func syncManualEdits() async {
        guard needsSync, !isPerformingAction, !workspace.requiresReload else { return }
        isPerformingAction = true
        workspace.clearError()
        await workspace.syncManualEdits()
        isPerformingAction = false
        operationErrorMessage = workspace.errorMessage
        _ = await refreshDurable(binding: binding, token: bindingToken)
    }
}

private extension NovelSessionViewModel {
    var snapshotMatchesBinding: Bool {
        guard let binding,
              workspace.selectedProjectID == binding.projectID,
              workspace.selectedBranchID == binding.branchID,
              workspace.projectSnapshot?.project.id == binding.projectID,
              workspace.branchSnapshot?.branch.id == binding.branchID else { return false }
        return true
    }

    var activeRun: NovelActiveRunRecord? {
        guard let branchID = binding?.branchID else { return nil }
        return workspace.projectSnapshot?.activeRuns.first {
            $0.branchID == branchID && $0.status == .running
        }
    }

    var boundQuickStartStartingRun: NovelActiveRunRecord? {
        guard let binding,
              workspace.quickStartStartingProjectID == binding.projectID,
              let run = workspace.quickStartStartingRun,
              run.branchID == binding.branchID else { return nil }
        return run
    }

    func isEligibleForExactRetry(_ run: NovelActiveRunRecord) -> Bool {
        guard run.branchID == binding?.branchID,
              run.status == .failed || run.status == .interrupted,
              run.status != .failed || run.terminalFailure?.isRetryable == true,
              let branch = workspace.branchSnapshot?.branch else { return false }
        if run.kind == .prose || run.kind == .polish {
            guard run.baseCheckpointID == branch.headCheckpointID,
                  run.baseHeadRevision == branch.headRevision else { return false }
        }
        if run.kind == .polish {
            guard let sourceID = run.sourceChapterVersionID,
                  branch.workingChapterSelections.contains(where: { $0.versionID == sourceID }) else {
                return false
            }
        }
        return true
    }

    func candidates(kind: NovelCandidateKind) -> [NovelCandidateRecord] {
        guard let branchID = binding?.branchID else { return [] }
        return workspace.projectSnapshot?.candidates.filter {
            $0.branchID == branchID && $0.kind == kind
        } ?? []
    }

    func canStart(kind: NovelRunKind) -> Bool {
        guard snapshotMatchesBinding,
              access == .readWrite,
              !workspace.requiresReload,
              refreshErrorMessage == nil,
              !terminalAwaitingRefresh,
              !isBusy,
              !isRunning,
              let branch = workspace.branchSnapshot?.branch,
              branch.lifecycle == .active,
              branch.activeRunID == nil,
              activeRun == nil else { return false }
        switch kind {
        case .discussion:
            return true
        case .prose, .polish:
            return branch.syncStatus == .synchronized && branchPendingOperations.isEmpty
        case .quickStart:
            return false
        }
    }

    @discardableResult
    func start(_ draft: NovelSessionRunDraft) async -> Bool {
        guard !draft.userText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              draft.inputBudgetTokens > 0,
              canStart(kind: draft.kind),
              let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot else { return false }
        let previousTail = transientTail
        let previousRunRecord = transientRunRecord
        let previousTerminalAwaitingRefresh = terminalAwaitingRefresh

        let candidateID: NovelCandidateID? = switch draft.kind {
        case .prose, .polish: NovelCandidateID()
        case .quickStart, .discussion: nil
        }
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: project.project.id,
            branchID: branch.branch.id,
            kind: draft.kind,
            mode: draft.mode,
            granularity: draft.granularity,
            userText: draft.userText,
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: candidateID,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: draft.sourceChapterVersionID,
            injectionOverrides: draft.injectionOverrides,
            inputBudgetTokens: draft.inputBudgetTokens,
            expectedProjectRevision: project.project.revision,
            expectedConfigRevision: project.project.configRevision,
            expectedBranchHeadRevision: branch.branch.headRevision
        )
        guard workspace.acquireSessionOperation(ownerID: request.id.rawValue) else {
            return false
        }
        let placeholderRun = NovelActiveRunRecord(
            id: request.id,
            operationID: request.operationID,
            requestPayloadSHA256: (try? request.canonicalPayloadSHA256()) ?? "",
            branchID: request.branchID,
            sessionID: branch.session.id,
            kind: request.kind,
            mode: request.mode,
            granularity: request.granularity,
            userMessageID: request.userMessageID,
            messageID: request.assistantMessageID,
            candidateID: request.candidateID,
            sourceChapterVersionID: request.sourceChapterVersionID,
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
        installTail(run: placeholderRun, content: "", phase: .waitingForFirstToken)
        sessionStartingRunID = request.id
        let requestID = request.id
        defer {
            releaseSessionStartOwnership(runID: requestID)
            cancelledStartRunIDs.remove(requestID)
        }
        do {
            let run = try await workspace.startSessionRun(request)
            guard !cancelledStartRunIDs.contains(request.id) else { return false }
            currentRunDraft = draft
            operationErrorMessage = nil
            refreshErrorMessage = nil
            lastFailure = nil
            lastRetryDraft = nil
            lastRetryRunID = nil
            consume(run, draft: draft, token: bindingToken)
            return true
        } catch {
            transientTail = previousTail
            transientRunRecord = previousRunRecord
            terminalAwaitingRefresh = previousTerminalAwaitingRefresh
            if cancelledStartRunIDs.contains(request.id) {
                operationErrorMessage = nil
            } else {
                operationErrorMessage = describe(error)
            }
            return false
        }
    }

    func consume(_ run: NovelRun, draft: NovelSessionRunDraft, token: UUID) {
        detachConsumer()
        let nextConsumerID = UUID()
        consumerID = nextConsumerID
        consumerTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for await event in run.events {
                guard !Task.isCancelled, self.bindingToken == token else { return }
                await self.consume(event, runID: run.id, draft: draft, token: token)
            }
            guard self.consumerID == nextConsumerID else { return }
            self.consumerID = nil
            self.consumerTask = nil
        }
    }

    func consume(
        _ event: NovelRunEvent,
        runID: NovelRunID,
        draft: NovelSessionRunDraft,
        token: UUID
    ) async {
        guard transientTail?.runID == runID else { return }
        switch event {
        case .started:
            _ = await refreshDurable(binding: binding, token: token)
            adoptDurableRunRecord(runID: runID)
        case .delta(let text):
            if draft.kind == .quickStart {
                updateTail(content: "", phase: .streaming)
            } else {
                updateTail(content: (transientTail?.content ?? "") + text, phase: .streaming)
            }
        case .replaced(let text):
            updateTail(content: draft.kind == .quickStart ? "" : text, phase: .streaming)
        case .completed(let snapshot):
            updateTail(content: snapshot.message.content, phase: .terminalAwaitingRefresh)
            terminalAwaitingRefresh = true
            lastRetryDraft = nil
            lastRetryRunID = nil
            currentRunDraft = nil
            let refreshed = await refreshDurable(binding: binding, token: token)
            if refreshed { clearTransientTail() }
        case .interrupted(let snapshot):
            updateTail(
                content: draft.kind == .quickStart
                    ? ""
                    : snapshot?.message.content ?? transientTail?.content ?? "",
                phase: .interrupted
            )
            terminalAwaitingRefresh = true
            lastRetryDraft = draft
            lastRetryRunID = runID
            currentRunDraft = nil
            let refreshed = await refreshDurable(binding: binding, token: token)
            if refreshed { clearTransientTail() }
        case .failed(let failure):
            updateTail(phase: .failed(failure))
            terminalAwaitingRefresh = true
            lastFailure = failure
            operationErrorMessage = NovelPresentation.failureMessage(failure)
            lastRetryDraft = failure.isRetryable ? draft : nil
            lastRetryRunID = failure.isRetryable ? runID : nil
            currentRunDraft = nil
            let refreshed = await refreshDurable(binding: binding, token: token)
            if refreshed { clearTransientTail() }
        case .persistenceBlocked(let failure):
            updateTail(phase: .persistenceBlocked(failure))
            lastFailure = failure
            operationErrorMessage = NovelPresentation.failureMessage(failure)
        }
    }

    func attach(to run: NovelActiveRunRecord) async {
        let expectedToken = bindingToken
        if attachingRunID == run.id,
           attachingBindingToken == expectedToken {
            return
        }
        let attemptID = UUID()
        attachAttemptID = attemptID
        attachingRunID = run.id
        attachingBindingToken = expectedToken
        defer {
            if attachAttemptID == attemptID {
                attachAttemptID = nil
                attachingRunID = nil
                attachingBindingToken = nil
            }
        }
        guard let draft = draft(for: run), let request = request(for: run, draft: draft) else {
            refreshErrorMessage = "无法恢复正在生成的消息订阅。"
            return
        }
        let expectedBinding = binding
        let initialContent = run.kind == .quickStart ? "" : run.partialContent
        installTail(
            run: run,
            content: initialContent,
            phase: run.partialContent.isEmpty ? .waitingForFirstToken : .streaming
        )
        do {
            let observed = try await workspace.startSessionRun(request)
            guard attachAttemptID == attemptID,
                  binding == expectedBinding,
                  bindingToken == expectedToken,
                  transientTail?.runID == run.id else { return }
            currentRunDraft = draft
            consume(observed, draft: draft, token: expectedToken)
        } catch {
            guard attachAttemptID == attemptID,
                  binding == expectedBinding,
                  bindingToken == expectedToken,
                  transientTail?.runID == run.id else { return }
            transientTail = nil
            transientRunRecord = nil
            refreshErrorMessage = describe(error)
        }
    }

    func draft(for run: NovelActiveRunRecord) -> NovelSessionRunDraft? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let user = branch.session.messages.first(where: {
                  $0.runID == run.id && $0.id == run.userMessageID && $0.role == .user
              }), let injection = project.injectionReceipts.first(where: {
                  $0.runID == run.id
              }) else { return nil }
        return NovelSessionRunDraft(
            kind: run.kind,
            mode: run.mode,
            granularity: run.granularity,
            userText: user.content,
            sourceChapterVersionID: run.sourceChapterVersionID,
            injectionOverrides: NovelInjectionOverrides(
                forceIncludeMaterialIDs: injection.forceIncludeMaterialIDs,
                forceExcludeMaterialIDs: injection.forceExcludeMaterialIDs
            ),
            inputBudgetTokens: injection.requestedInputBudgetTokens
        )
    }

    func request(
        for run: NovelActiveRunRecord,
        draft: NovelSessionRunDraft
    ) -> NovelRunRequest? {
        guard let project = workspace.projectSnapshot,
              let branch = workspace.branchSnapshot,
              let injection = project.injectionReceipts.first(where: { $0.runID == run.id }) else {
            return nil
        }
        return NovelRunRequest(
            id: run.id,
            operationID: run.operationID,
            projectID: project.project.id,
            branchID: branch.branch.id,
            kind: run.kind,
            mode: run.mode,
            granularity: run.granularity,
            userText: draft.userText,
            userMessageID: run.userMessageID,
            assistantMessageID: run.messageID,
            candidateID: run.candidateID,
            generationReceiptID: run.receiptID,
            injectionReceiptID: injection.id,
            sourceChapterVersionID: run.sourceChapterVersionID,
            injectionOverrides: draft.injectionOverrides,
            inputBudgetTokens: draft.inputBudgetTokens,
            expectedProjectRevision: project.project.revision,
            expectedConfigRevision: project.project.configRevision,
            expectedBranchHeadRevision: branch.branch.headRevision
        )
    }

    @discardableResult
    func refreshDurable(binding expected: NovelSessionBinding?, token: UUID) async -> Bool {
        guard let expected,
              binding == expected,
              bindingToken == token,
              workspace.selectedProjectID == expected.projectID,
              workspace.selectedBranchID == expected.branchID else { return false }
        do {
            try await workspace.refreshCurrentSelection(projectID: expected.projectID)
            guard binding == expected,
                  bindingToken == token,
                  snapshotMatchesBinding else { return false }
            refreshErrorMessage = nil
            hydrateTerminalState()
            if terminalAwaitingRefresh,
               let tail = transientTail,
               workspace.projectSnapshot?.activeRuns.first(where: { $0.id == tail.runID })?.status != .running {
                clearTransientTail()
            } else if terminalAwaitingRefresh,
                      transientTail == nil,
                      workspace.branchSnapshot?.branch.activeRunID == nil {
                terminalAwaitingRefresh = false
            }
            return true
        } catch {
            guard bindingToken == token else { return false }
            refreshErrorMessage = describe(error)
            return false
        }
    }

    func hydrateTerminalState() {
        guard let branchID = binding?.branchID,
              let runs = workspace.projectSnapshot?.activeRuns.filter({ $0.branchID == branchID }) else {
            return
        }
        guard let latest = runs.max(by: { $0.startedAt < $1.startedAt }) else {
            lastRetryDraft = nil
            lastRetryRunID = nil
            lastFailure = nil
            return
        }
        lastFailure = latest.terminalFailure
        switch latest.status {
        case .failed:
            lastRetryDraft = latest.terminalFailure?.isRetryable == true ? draft(for: latest) : nil
            lastRetryRunID = lastRetryDraft == nil ? nil : latest.id
        case .interrupted:
            lastRetryDraft = draft(for: latest)
            lastRetryRunID = lastRetryDraft == nil ? nil : latest.id
        case .running, .completed:
            lastRetryDraft = nil
            lastRetryRunID = nil
        }
    }

    func applyPolishAdoption(_ command: NovelAdoptPolishCandidateCommand) async {
        _ = await perform(.adoptPolishCandidate(command))
    }

    @discardableResult
    func perform(_ action: NovelAction) async -> NovelOutcome? {
        guard beginAction() else { return nil }
        defer { endAction() }
        let outcome: NovelOutcome?
        do {
            outcome = try await workspace.performSessionAction(action)
            operationErrorMessage = nil
        } catch {
            outcome = nil
            operationErrorMessage = describe(error)
        }
        _ = await refreshDurable(binding: binding, token: bindingToken)
        return outcome
    }

    func beginAction() -> Bool {
        guard !isPerformingAction,
              !workspace.requiresReload else { return false }
        let ownerID = UUID()
        guard workspace.acquireSessionOperation(ownerID: ownerID) else { return false }
        sessionActionOwnerID = ownerID
        isPerformingAction = true
        return true
    }

    func endAction() {
        if let ownerID = sessionActionOwnerID {
            workspace.releaseSessionOperation(ownerID: ownerID)
            sessionActionOwnerID = nil
        }
        isPerformingAction = false
    }

    func mutationContext(
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot
    ) -> NovelMutationContext {
        NovelMutationContext(
            operationID: NovelOperationID(),
            expectedProjectRevision: project.project.revision,
            expectedConfigRevision: project.project.configRevision,
            expectedBranchHeadRevision: branch.branch.headRevision
        )
    }

    func installTail(
        run: NovelActiveRunRecord,
        content: String,
        renderRevision: UInt64 = 0,
        phase: NovelSessionTransientTailPhase
    ) {
        transientRunRecord = run
        transientTail = NovelSessionTransientTail(
            run: run,
            content: content,
            renderRevision: renderRevision,
            phase: phase
        )
        terminalAwaitingRefresh = false
    }

    func updateTail(
        content: String? = nil,
        phase: NovelSessionTransientTailPhase? = nil
    ) {
        guard let current = transientTail else { return }
        transientTail = current.updating(
            content: content ?? current.content,
            phase: phase ?? current.phase
        )
    }

    func adoptDurableRunRecord(runID: NovelRunID) {
        guard let durable = workspace.projectSnapshot?.activeRuns.first(where: { $0.id == runID }),
              let current = transientTail else { return }
        transientRunRecord = durable
        transientTail = NovelSessionTransientTail(
            run: durable,
            content: current.content,
            renderRevision: current.renderRevision,
            phase: current.phase
        )
    }

    func clearTransientTail() {
        transientTail = nil
        transientRunRecord = nil
        terminalAwaitingRefresh = false
    }

    func isActiveTailPhase(_ phase: NovelSessionTransientTailPhase?) -> Bool {
        return switch phase {
        case .waitingForFirstToken, .streaming: true
        case .persistenceBlocked, .terminalAwaitingRefresh, .interrupted, .failed, nil: false
        }
    }

    func interruptBoundRun(reason: NovelRunInterruptionReason) async -> Bool {
        guard let bound = binding,
              let runID = activeRunID else { return true }
        guard !isPerformingAction,
              !workspace.isPerforming || isStarting else { return false }
        let isCancellingSessionStart = sessionStartingRunID == runID
        let isCancellingQuickStart = boundQuickStartStartingRun?.id == runID
        let actionOwnerID: UUID?
        if isCancellingSessionStart || isCancellingQuickStart {
            actionOwnerID = nil
        } else {
            let ownerID = UUID()
            guard workspace.acquireSessionOperation(ownerID: ownerID) else { return false }
            actionOwnerID = ownerID
        }
        if isCancellingSessionStart {
            cancelledStartRunIDs.insert(runID)
        }
        isPerformingAction = true
        defer {
            if let actionOwnerID {
                workspace.releaseSessionOperation(ownerID: actionOwnerID)
            }
            isPerformingAction = false
        }
        let command = NovelCancelRunCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: nil,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: bound.projectID,
            runID: runID,
            reason: reason
        )
        do {
            try await workspace.interruptSessionRun(command)
            operationErrorMessage = nil
            if isCancellingSessionStart {
                releaseSessionStartOwnership(runID: runID)
            }
        } catch {
            operationErrorMessage = describe(error)
            if isCancellingSessionStart {
                cancelledStartRunIDs.remove(runID)
                _ = await refreshDurable(binding: bound, token: bindingToken)
                await bindToCurrentSelection()
            }
            return false
        }
        if transientTail?.runID == runID {
            clearTransientTail()
        }
        currentRunDraft = nil
        guard snapshotMatchesBinding else { return true }
        let refreshed = await refreshDurable(binding: bound, token: bindingToken)
        return refreshed &&
            workspace.branchSnapshot?.branch.activeRunID == nil &&
            workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) != true
    }

    func releaseSessionStartOwnership(runID: NovelRunID) {
        if sessionStartingRunID == runID {
            sessionStartingRunID = nil
        }
        workspace.releaseSessionOperation(ownerID: runID.rawValue)
    }

    func resetBinding() {
        detachConsumer()
        bindingToken = UUID()
        binding = nil
        clearTransientTail()
        currentRunDraft = nil
        lastRetryDraft = nil
        lastRetryRunID = nil
    }

    func describe(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }
}
