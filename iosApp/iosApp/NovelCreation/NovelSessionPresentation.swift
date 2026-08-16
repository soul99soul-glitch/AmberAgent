import Foundation

enum NovelSessionComposerPolicy {
    static func canSubmit(canSend: Bool, text: String) -> Bool {
        canSend && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func askUserBlocker(
        access: NovelProjectLoadAccess?,
        requiresReload: Bool,
        isBusy: Bool
    ) -> NovelSessionActionBlocker? {
        guard access == .readWrite else { return .projectReadOnly }
        if requiresReload { return .reloadRequired }
        if isBusy { return .transactionInProgress }
        return nil
    }

    static func polishTransactionBlocker(
        access: NovelProjectLoadAccess?,
        requiresReload: Bool,
        isRunning: Bool,
        isBusy: Bool
    ) -> NovelSessionActionBlocker? {
        guard access == .readWrite else { return .projectReadOnly }
        if requiresReload { return .reloadRequired }
        if isRunning { return .generationRunning }
        if isBusy { return .transactionInProgress }
        return nil
    }

    static func showsGenerationStatus(
        isRunning: Bool,
        isTerminalPresenting: Bool = false,
        activeRunKind: NovelRunKind?,
        hasGhostwriteProgress: Bool = false
    ) -> Bool {
        // Ghostwrite owns its own dock chrome; do not stack co-create status above it.
        if hasGhostwriteProgress { return false }
        guard activeRunKind == .prose ||
                activeRunKind == .regenerate ||
                activeRunKind == .polish else { return false }
        // Keep the strip slot through terminal presentation drain so safeArea
        // height does not collapse mid-finish (scroll jump).
        return isRunning || isTerminalPresenting
    }

    static func runtimeActionBlocker(
        requiresReload: Bool,
        hasRefreshError: Bool,
        isBusy: Bool
    ) -> NovelSessionActionBlocker? {
        if requiresReload || hasRefreshError { return .reloadRequired }
        if isBusy { return .transactionInProgress }
        return nil
    }
}

enum NovelSessionTransientTailPhase: Equatable, Sendable {
    case waitingForFirstToken
    case streaming
    case terminalAwaitingRefresh
    case interrupted
    case failed(NovelFailure)
    case persistenceBlocked(NovelFailure)
}

struct NovelSessionTransientTail: Equatable, Sendable {
    let branchID: NovelBranchID
    let sessionID: NovelSessionID
    let runID: NovelRunID
    let userMessageID: NovelMessageID
    let startingUserContent: String?
    let messageID: NovelMessageID
    let candidateID: NovelCandidateID?
    let mode: NovelSessionMode
    let granularity: NovelGenerationGranularity?
    let kind: NovelSessionMessageKind
    let content: String
    /// Presentation-only thinking stream. Never manuscript / candidate content.
    let reasoningContent: String
    let isReasoningLive: Bool
    /// Monotonic per-tail revision owned by the Session ViewModel.
    /// The row digest uses this instead of hashing an ever-growing whole chapter.
    let renderRevision: UInt64
    let startedAt: Date
    let phase: NovelSessionTransientTailPhase
    /// 滚动跟随的滞后允许度（与 Chat 同源）：1=流式期；终态排空期间随剩余积压
    /// 连续衰减到 0，driver 据此连续收紧跟随时间常数（τ_eff = τ × allowance），
    /// 排空最后一拍前视口已贴回底部——完成瞬间无需一次性清掉跟随滞后。
    let lagAllowance: Double

    init(
        run: NovelActiveRunRecord,
        content: String,
        renderRevision: UInt64,
        startingUserContent: String? = nil,
        phase: NovelSessionTransientTailPhase,
        reasoningContent: String = "",
        isReasoningLive: Bool = false,
        lagAllowance: Double = 1
    ) {
        branchID = run.branchID
        sessionID = run.sessionID
        runID = run.id
        userMessageID = run.userMessageID
        self.startingUserContent = startingUserContent
        messageID = run.messageID
        candidateID = run.candidateID
        mode = run.mode
        granularity = run.granularity
        kind = switch run.kind {
        case .quickStart, .characterProposal, .discussion: .discussion
        case .prose, .regenerate: .proseCandidate
        case .polish: .polishCandidate
        }
        self.content = content
        self.reasoningContent = reasoningContent
        self.isReasoningLive = isReasoningLive
        self.renderRevision = renderRevision
        startedAt = run.startedAt
        self.phase = phase
        self.lagAllowance = lagAllowance
    }

    func updating(
        content: String,
        renderRevision: UInt64,
        phase: NovelSessionTransientTailPhase,
        reasoningContent: String? = nil,
        isReasoningLive: Bool? = nil,
        lagAllowance: Double? = nil
    ) -> NovelSessionTransientTail {
        NovelSessionTransientTail(
            branchID: branchID,
            sessionID: sessionID,
            runID: runID,
            userMessageID: userMessageID,
            startingUserContent: startingUserContent,
            messageID: messageID,
            candidateID: candidateID,
            mode: mode,
            granularity: granularity,
            kind: kind,
            content: content,
            reasoningContent: reasoningContent ?? self.reasoningContent,
            isReasoningLive: isReasoningLive ?? self.isReasoningLive,
            renderRevision: renderRevision,
            startedAt: startedAt,
            phase: phase,
            lagAllowance: lagAllowance ?? self.lagAllowance
        )
    }

    func updating(
        content: String,
        phase: NovelSessionTransientTailPhase,
        lagAllowance: Double? = nil
    ) -> NovelSessionTransientTail {
        updating(
            content: content,
            renderRevision: renderRevision &+ 1,
            phase: phase,
            lagAllowance: lagAllowance
        )
    }

    func updatingReasoning(
        content: String,
        isLive: Bool
    ) -> NovelSessionTransientTail {
        updating(
            content: self.content,
            renderRevision: renderRevision &+ 1,
            phase: phase,
            reasoningContent: content,
            isReasoningLive: isLive
        )
    }

    private init(
        branchID: NovelBranchID,
        sessionID: NovelSessionID,
        runID: NovelRunID,
        userMessageID: NovelMessageID,
        startingUserContent: String?,
        messageID: NovelMessageID,
        candidateID: NovelCandidateID?,
        mode: NovelSessionMode,
        granularity: NovelGenerationGranularity?,
        kind: NovelSessionMessageKind,
        content: String,
        reasoningContent: String,
        isReasoningLive: Bool,
        renderRevision: UInt64,
        startedAt: Date,
        phase: NovelSessionTransientTailPhase,
        lagAllowance: Double
    ) {
        self.branchID = branchID
        self.sessionID = sessionID
        self.runID = runID
        self.userMessageID = userMessageID
        self.startingUserContent = startingUserContent
        self.messageID = messageID
        self.candidateID = candidateID
        self.mode = mode
        self.granularity = granularity
        self.kind = kind
        self.content = content
        self.reasoningContent = reasoningContent
        self.isReasoningLive = isReasoningLive
        self.renderRevision = renderRevision
        self.startedAt = startedAt
        self.phase = phase
        self.lagAllowance = lagAllowance
    }
}

enum NovelSettingProposalRoute: Hashable, Sendable {
    case characters
    case world
    case story
    case more

    init(kind: NovelMaterialKind?) {
        self = switch kind {
        case .character: .characters
        case .world: .world
        case .relationship, .masterOutline: .story
        case .writingRequirements, .decisionLog, .custom, nil: .more
        }
    }
}

enum NovelSessionRowAction: Hashable, Sendable {
    case collectProse(NovelCandidateID)
    case adoptPolish(NovelCandidateID)
    case retryGeneration(NovelRunID)
    case retryTerminalPersistence(NovelRunID)
    case retryPending(NovelPendingOperationID)
    case retryPolish(NovelPendingOperationID)
    case abandonPolish(NovelPendingOperationID)
    case convertPolishToManualRewrite(
        candidateID: NovelCandidateID,
        sourceChapterVersionID: NovelChapterVersionID
    )
    case cloneCollectedProse(NovelCandidateID)
    case forkFromCheckpoint(NovelCheckpointID)
    case viewSettingProposals(NovelSettingProposalRoute)
    case undoCommittedChange(checkpointID: NovelCheckpointID, kind: NovelCandidateKind)
}

enum NovelSessionActionBlocker: String, Hashable, Sendable {
    case projectReadOnly
    case reloadRequired
    case branchInactive
    case branchNeedsSync
    case chapterPlanRequired
    case ghostwriteRequirementsMissing
    case generationRunning
    case pendingOperation
    case transactionInProgress
    case transactionBlocked
    case staleCandidate
    case sourceChapterChanged
    case failureNotRetryable
}

struct NovelSessionRowActionAvailability: Equatable, Hashable, Sendable {
    let action: NovelSessionRowAction
    let blocker: NovelSessionActionBlocker?

    var isEnabled: Bool { blocker == nil }
}

struct NovelSessionCandidatePresentation: Equatable, Sendable {
    let id: NovelCandidateID
    let kind: NovelCandidateKind
    let status: NovelCandidateStatus
    let sourceChapterVersionID: NovelChapterVersionID?
    let pendingStatus: NovelPendingOperationStatus?
    let polishTransactionStatus: NovelPolishTransactionStatus?
}

struct NovelSessionCommittedChangeSummary: Equatable, Sendable {
    let checkpointID: NovelCheckpointID
    let stateSnapshotID: NovelStateSnapshotID
    let stateSummary: String
    let eventSummaries: [String]
    /// Whether the branch's plot state is currently synchronized, as of the last projection.
    ///
    /// This is `branch.syncStatus` — a **branch-level**, persisted fact — not a per-row
    /// history entry. Every committed row on the branch shares this same value: if the
    /// user later commits more chapters and the branch drops back to `.needsSync`, a row
    /// that previously showed "synchronized" will flip back to showing nothing (or the
    /// existing "needs sync" notice) the next time it's projected, even though this exact
    /// row's own content hasn't changed. That is semantically correct (the branch really
    /// did go stale again), but it is not a durable "this paragraph was synced at time T"
    /// stamp.
    ///
    /// A true per-paragraph sync stamp is not derivable: a manual sync's input is *all*
    /// backlog chapters since the last sync (aggregated, not scoped to one message — see
    /// `NovelFactTransactions.manualSync`), the resulting checkpoint's
    /// `sourceCandidateID` is `nil` (it cannot be traced back to any one candidate/message),
    /// and the pending manual-sync operation record is deleted on success. There is nothing
    /// left to hang a per-segment "synced" fact off of after the fact.
    let branchSyncStatus: NovelBranchSyncStatus
}

struct NovelAskUserPresentation: Equatable, Sendable {
    let prompt: NovelAskUserPrompt
    let response: NovelAskUserResponse?

    var isAnswered: Bool { response != nil }
}

struct NovelDiscussionArchivePresentation: Equatable, Sendable {
    let id: NovelMessageID
    let title: String
    let summary: String
    let messageCount: Int
    let revealedRowCount: Int
    let isExpanded: Bool
}

struct NovelSessionRowDigest: Equatable, Hashable, Sendable {
    /// Inputs that can change the row's measured height.
    let layout: String
    /// Inputs that only reroute interaction while keeping the same content row.
    let presentation: String
}

struct NovelSessionRowModel: Identifiable, Equatable, Sendable {
    let id: NovelMessageID
    let sequence: Int64
    let role: NovelSessionRole
    let mode: NovelSessionMode
    let granularity: NovelGenerationGranularity?
    let kind: NovelSessionMessageKind
    let content: String
    /// Presentation-only thinking text for the active/transient assistant row.
    let reasoningContent: String
    let isReasoningLive: Bool
    let createdAt: Date
    let runID: NovelRunID?
    let runStatus: NovelRunStatus?
    let candidate: NovelSessionCandidatePresentation?
    let committedChange: NovelSessionCommittedChangeSummary?
    let askUser: NovelAskUserPresentation?
    let archive: NovelDiscussionArchivePresentation?
    let transientPhase: NovelSessionTransientTailPhase?
    let actions: [NovelSessionRowActionAvailability]
    let digest: NovelSessionRowDigest
    /// 活动尾行的跟随滞后允许度（流式 1 → 终态排空连续衰减到 0），视图层透传
    /// 给 streamContentGrew(lagAllowance:)。非活动行恒为 1。
    let lagAllowance: Double

    var isTransient: Bool { transientPhase != nil }

    var isStreaming: Bool {
        switch transientPhase {
        case .waitingForFirstToken, .streaming: true
        case .terminalAwaitingRefresh, .interrupted, .failed, .persistenceBlocked, nil: false
        }
    }

    init(
        id: NovelMessageID,
        sequence: Int64,
        role: NovelSessionRole,
        mode: NovelSessionMode,
        granularity: NovelGenerationGranularity?,
        kind: NovelSessionMessageKind,
        content: String,
        reasoningContent: String = "",
        isReasoningLive: Bool = false,
        createdAt: Date,
        runID: NovelRunID?,
        runStatus: NovelRunStatus?,
        candidate: NovelSessionCandidatePresentation?,
        committedChange: NovelSessionCommittedChangeSummary?,
        askUser: NovelAskUserPresentation?,
        archive: NovelDiscussionArchivePresentation?,
        transientPhase: NovelSessionTransientTailPhase?,
        actions: [NovelSessionRowActionAvailability],
        digest: NovelSessionRowDigest,
        lagAllowance: Double = 1
    ) {
        self.id = id
        self.sequence = sequence
        self.role = role
        self.mode = mode
        self.granularity = granularity
        self.kind = kind
        self.content = content
        self.reasoningContent = reasoningContent
        self.isReasoningLive = isReasoningLive
        self.createdAt = createdAt
        self.runID = runID
        self.runStatus = runStatus
        self.candidate = candidate
        self.committedChange = committedChange
        self.askUser = askUser
        self.archive = archive
        self.transientPhase = transientPhase
        self.actions = actions
        self.digest = digest
        self.lagAllowance = lagAllowance
    }
}

struct NovelSessionListModel: Equatable, Sendable {
    let sessionID: NovelSessionID
    let rows: [NovelSessionRowModel]
    let activeTailID: NovelMessageID?

    var historicalRows: [NovelSessionRowModel] {
        guard let activeRunID else { return rows }
        return rows.filter { $0.runID != activeRunID }
    }

    var activeRunRows: [NovelSessionRowModel] {
        guard let activeRunID else { return [] }
        return rows.filter { $0.runID == activeRunID }
    }

    private var activeRunID: NovelRunID? {
        if let activeTailID {
            return rows.first(where: { $0.id == activeTailID })?.runID
        }
        // Keep the just-finished run in the active stack after the tail
        // retires. Moving those rows into the history ForEach remounts the
        // finished markdown bubble; merging both stacks into one VStack
        // instead remeasures every historical chapter on each stream tick.
        return rows.last(where: { $0.runID != nil })?.runID
    }
}

/// Staged session open so large projects never pay transcript + long layout +
/// identity chrome in a single main-thread turn (0x8BADF00D on 赵大来了-scale).
enum NovelSessionLoadStage: Int, Comparable, Sendable {
    /// Binding / snapshot not ready — avoid projecting heavy lists.
    case idle = 0
    /// Bound: tiny history window only (row count capped; body not truncated).
    case coreTranscript = 1
    /// After first layout: expand steady history window.
    case steadyTranscript = 2
    /// After core is on screen: identity cards and other secondary chrome.
    case secondaryChrome = 3

    static func < (lhs: Self, rhs: Self) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}

enum NovelSessionHistoryWindowPolicy {
    /// First paint after open — latest exchange only (user + assistant).
    static let coldOpenLimit = 2
    /// After the first layout commits, grow to this many historical rows.
    static let initialLimit = 4
    /// "更早的创作记录" page size.
    static let pageSize = 12

    static func startIndex(totalCount: Int, limit: Int) -> Int {
        max(0, totalCount - max(0, limit))
    }

    static func expandedLimit(currentLimit: Int, totalCount: Int) -> Int {
        min(totalCount, max(0, currentLimit) + pageSize)
    }

    /// Cold open → steady window without shrinking a user-expanded history.
    static func limitAfterColdOpenSettles(currentLimit: Int) -> Int {
        max(currentLimit, initialLimit)
    }

    /// 追加新行时窗口必须吸收增量,否则 `startIndex` 前移会把**已经渲染过的**
    /// 顶部行踢出窗口。那些行可能有几万 pt 高,一旦被踢,contentSize 当场收缩,
    /// 而收缩发生在视口上方——没有任何锚点补偿(底锚已在 0eea3d38b 撤除),
    /// 结果就是整列表大幅位移、视口里冒出本条回复更早的内容。
    ///
    /// 这里不区分「是否贴底」:贴底与否只影响用户看不看得见,不影响
    /// 「已渲染的行不得中途消失」这条不变量。
    static func limitAfterRowsAppended(
        currentLimit: Int,
        previousRowCount: Int,
        currentRowCount: Int
    ) -> Int {
        let appended = max(0, currentRowCount - previousRowCount)
        guard appended > 0 else { return currentLimit }
        return min(currentRowCount, currentLimit + appended)
    }

    static func limitAfterArchiveExpansion(
        currentLimit: Int,
        revealedRowCount: Int
    ) -> Int {
        currentLimit + revealedRowCount
    }

    /// 活动 run 的行离开 eager 尾部时，窗口上限必须吸收真正转入历史的行数，
    /// 否则完成瞬间窗口会裁掉完成前已可见的旧行，表现为内容位移。
    /// 行数按快照差推导：整体消失的行（启动失败恢复）不计入，避免无谓扩窗。
    static func limitAfterActiveRunReturnsToHistory(
        currentLimit: Int,
        activeRunRowCount: Int,
        previousRowCount: Int,
        currentRowCount: Int
    ) -> Int {
        let vanished = max(0, previousRowCount - currentRowCount)
        let returned = max(0, activeRunRowCount - vanished)
        return currentLimit + returned
    }
}

struct NovelSessionProjectionInput: Equatable, Sendable {
    let branch: NovelBranchRecord
    let session: NovelSessionRecord
    let candidates: [NovelCandidateRecord]
    let runs: [NovelActiveRunRecord]
    let pendingOperations: [NovelPendingOperationRecord]
    let polishTransactions: [NovelPendingPolishTransactionRecord]
    let discardedChapterVersionIDs: Set<NovelChapterVersionID>
    let checkpoints: [NovelBranchCheckpointRecord]
    let stateSnapshots: [NovelStateSnapshotRecord]
    let events: [NovelStoryEventRecord]
    let settingProposals: [NovelSettingProposalRecord]
    let collaborationMode: NovelCollaborationMode
    let hasConfirmedChapterPlan: Bool
    let access: NovelProjectLoadAccess
    let expandedArchiveIDs: Set<NovelMessageID>
    let transientTail: NovelSessionTransientTail?

    init(
        branch: NovelBranchRecord,
        session: NovelSessionRecord,
        candidates: [NovelCandidateRecord],
        runs: [NovelActiveRunRecord],
        pendingOperations: [NovelPendingOperationRecord],
        polishTransactions: [NovelPendingPolishTransactionRecord],
        discardedChapterVersionIDs: Set<NovelChapterVersionID> = [],
        checkpoints: [NovelBranchCheckpointRecord] = [],
        stateSnapshots: [NovelStateSnapshotRecord] = [],
        events: [NovelStoryEventRecord] = [],
        settingProposals: [NovelSettingProposalRecord] = [],
        collaborationMode: NovelCollaborationMode = .cocreation,
        hasConfirmedChapterPlan: Bool = false,
        access: NovelProjectLoadAccess,
        expandedArchiveIDs: Set<NovelMessageID> = [],
        transientTail: NovelSessionTransientTail?
    ) {
        self.branch = branch
        self.session = session
        self.candidates = candidates
        self.runs = runs
        self.pendingOperations = pendingOperations
        self.polishTransactions = polishTransactions
        self.discardedChapterVersionIDs = discardedChapterVersionIDs
        self.checkpoints = checkpoints
        self.stateSnapshots = stateSnapshots
        self.events = events
        self.settingProposals = settingProposals
        self.collaborationMode = collaborationMode
        self.hasConfirmedChapterPlan = hasConfirmedChapterPlan
        self.access = access
        self.expandedArchiveIDs = expandedArchiveIDs
        self.transientTail = transientTail
    }

    init(
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot,
        expandedArchiveIDs: Set<NovelMessageID> = [],
        transientTail: NovelSessionTransientTail?
    ) {
        let discardedChapterIDs = Set(project.chapters.lazy.filter {
            $0.discardedAt != nil
        }.map(\.id))
        self.init(
            branch: branch.branch,
            session: branch.session,
            candidates: project.candidates,
            runs: project.activeRuns,
            pendingOperations: project.pendingOperations,
            polishTransactions: project.polishTransactions,
            discardedChapterVersionIDs: Set(project.chapterVersions.lazy.filter {
                discardedChapterIDs.contains($0.chapterID)
            }.map(\.id)),
            checkpoints: project.checkpoints,
            stateSnapshots: project.stateSnapshots,
            events: project.events,
            settingProposals: branch.activeSettingProposals,
            collaborationMode: project.project.collaborationMode,
            hasConfirmedChapterPlan: project.confirmedChapterPlan(for: branch.branch.id) != nil,
            access: project.access,
            expandedArchiveIDs: expandedArchiveIDs,
            transientTail: transientTail
        )
    }
}

enum NovelSessionPresentation {
    static func project(_ input: NovelSessionProjectionInput) -> NovelSessionListModel {
        let index = NovelSessionProjectionIndex(input: input)
        let messages = input.session.messages.sorted { lhs, rhs in
            if lhs.sequence != rhs.sequence { return lhs.sequence < rhs.sequence }
            return lhs.id.description < rhs.id.description
        }
        let durableIDs = Set(messages.map(\.id))
        let visibleTail = input.transientTail.flatMap { tail in
            tail.branchID == input.branch.id && tail.sessionID == input.session.id ? tail : nil
        }
        let missingInterruptedRuns = input.runs
            .filter { run in
                run.branchID == input.branch.id &&
                    run.sessionID == input.session.id &&
                    run.status == .interrupted &&
                    !durableIDs.contains(run.messageID) &&
                    input.transientTail?.runID != run.id
            }
            .sorted(by: runOrder)
        let interruptedRunsByUserMessage = Dictionary(
            grouping: missingInterruptedRuns,
            by: \.userMessageID
        )
        var projectedInterruptedRunIDs: Set<NovelRunID> = []
        var rows: [NovelSessionRowModel] = []
        rows.reserveCapacity(messages.count + missingInterruptedRuns.count + 2)
        for message in messages where message.id != visibleTail?.messageID {
            rows.append(durableRow(message: message, input: input, index: index))
            for run in interruptedRunsByUserMessage[message.id] ?? [] {
                rows.append(interruptedWithoutOutputRow(
                    run: run,
                    sequence: message.sequence,
                    input: input,
                    index: index
                ))
                projectedInterruptedRunIDs.insert(run.id)
            }
        }
        for run in missingInterruptedRuns where !projectedInterruptedRunIDs.contains(run.id) {
            rows.append(interruptedWithoutOutputRow(
                run: run,
                sequence: Int64(messages.count),
                input: input,
                index: index
            ))
        }
        rows = foldingArchivedDiscussionRows(rows, input: input)
        var activeTailID: NovelMessageID?

        if let tail = visibleTail {
            if let startingUserContent = tail.startingUserContent,
               !durableIDs.contains(tail.userMessageID) {
                rows.append(startingUserRow(
                    tail: tail,
                    content: startingUserContent,
                    sequence: Int64(rows.count)
                ))
            }
            rows.append(transientRow(
                tail: tail,
                sequence: Int64(rows.count),
                input: input,
                index: index
            ))
            activeTailID = tail.messageID
        }

        return NovelSessionListModel(
            sessionID: input.session.id,
            rows: rows,
            activeTailID: activeTailID
        )
    }

    /// A paced stream changes only the transient row's content/revision. Reuse the
    /// already-projected durable rows instead of sorting/indexing the whole session
    /// again at ~21 Hz.
    static func updatingTransientTail(
        in model: NovelSessionListModel,
        with tail: NovelSessionTransientTail
    ) -> NovelSessionListModel? {
        guard model.activeTailID == tail.messageID,
              let rowIndex = model.rows.firstIndex(where: { $0.id == tail.messageID }) else {
            return nil
        }
        let current = model.rows[rowIndex]
        guard current.runID == tail.runID,
              current.transientPhase == tail.phase else { return nil }

        var rows = model.rows
        rows[rowIndex] = NovelSessionRowModel(
            id: current.id,
            sequence: current.sequence,
            role: current.role,
            mode: current.mode,
            granularity: current.granularity,
            kind: current.kind,
            content: presentedContent(for: tail),
            reasoningContent: tail.reasoningContent,
            isReasoningLive: tail.isReasoningLive,
            createdAt: current.createdAt,
            runID: current.runID,
            runStatus: current.runStatus,
            candidate: current.candidate,
            committedChange: current.committedChange,
            askUser: current.askUser,
            archive: current.archive,
            transientPhase: current.transientPhase,
            actions: current.actions,
            digest: digest(
                messageID: tail.messageID,
                transientRevision: tail.renderRevision,
                transientPhase: tail.phase,
                runStatus: current.runStatus,
                granularity: current.granularity,
                candidate: current.candidate,
                committedChange: current.committedChange,
                askUser: current.askUser,
                actions: current.actions
            ),
            lagAllowance: tail.lagAllowance
        )
        return NovelSessionListModel(
            sessionID: model.sessionID,
            rows: rows,
            activeTailID: model.activeTailID
        )
    }
}

private extension NovelSessionPresentation {
    static func startingUserRow(
        tail: NovelSessionTransientTail,
        content: String,
        sequence: Int64
    ) -> NovelSessionRowModel {
        NovelSessionRowModel(
            id: tail.userMessageID,
            sequence: sequence,
            role: .user,
            mode: tail.mode,
            granularity: tail.granularity,
            kind: .userInput,
            content: content,
            createdAt: tail.startedAt,
            runID: tail.runID,
            runStatus: .running,
            candidate: nil,
            committedChange: nil,
            askUser: nil,
            archive: nil,
            transientPhase: nil,
            actions: [],
            digest: digest(
                messageID: tail.userMessageID,
                transientRevision: nil,
                transientPhase: nil,
                runStatus: .running,
                granularity: tail.granularity,
                candidate: nil,
                committedChange: nil,
                askUser: nil,
                actions: []
            )
        )
    }
}

private extension NovelSessionPresentation {
    static func foldingArchivedDiscussionRows(
        _ rows: [NovelSessionRowModel],
        input: NovelSessionProjectionInput
    ) -> [NovelSessionRowModel] {
        guard case .some(.through(let archiveCursor)) = input.session.archiveCursor,
              let archives = input.session.discussionArchives,
              !archives.isEmpty else { return rows }

        let checkpointByID = Dictionary(
            input.checkpoints.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var activeCheckpointIDs: Set<NovelCheckpointID> = []
        var checkpointID: NovelCheckpointID? = input.branch.headCheckpointID
        while let currentID = checkpointID,
              activeCheckpointIDs.insert(currentID).inserted,
              let checkpoint = checkpointByID[currentID] {
            checkpointID = checkpoint.parentCheckpointID
        }
        let activeArchives = archives.filter {
            $0.throughSequence <= archiveCursor && activeCheckpointIDs.contains($0.checkpointID)
        }.sorted { lhs, rhs in
            if lhs.throughSequence != rhs.throughSequence {
                return lhs.throughSequence < rhs.throughSequence
            }
            return lhs.id.description < rhs.id.description
        }
        guard !activeArchives.isEmpty else { return rows }

        var result: [NovelSessionRowModel] = []
        result.reserveCapacity(rows.count + activeArchives.count)
        var rowIndex = 0
        var lowerSequence: Int64 = 0
        for archive in activeArchives {
            while rowIndex < rows.count, rows[rowIndex].sequence < lowerSequence {
                result.append(rows[rowIndex])
                rowIndex += 1
            }
            let rangeStart = rowIndex
            while rowIndex < rows.count, rows[rowIndex].sequence <= archive.throughSequence {
                rowIndex += 1
            }
            guard rangeStart < rowIndex else {
                lowerSequence = archive.throughSequence + 1
                continue
            }
            let isExpanded = input.expandedArchiveIDs.contains(archive.id)
            let range = rows[rangeStart..<rowIndex]
            let collapsedRows = range.filter(remainsVisibleWhileArchiveCollapsed)
            let revealedRowCount = range.count - collapsedRows.count
            result.append(archiveRow(
                archive,
                input: input,
                revealedRowCount: revealedRowCount,
                isExpanded: isExpanded
            ))
            if isExpanded {
                result.append(contentsOf: range)
            } else {
                // Keep uncollected prose visible so collect remains reachable without expanding.
                result.append(contentsOf: collapsedRows)
            }
            lowerSequence = archive.throughSequence + 1
        }
        result.append(contentsOf: rows[rowIndex...])
        return result
    }

    static func remainsVisibleWhileArchiveCollapsed(_ row: NovelSessionRowModel) -> Bool {
        guard let candidate = row.candidate, candidate.kind == .prose else { return false }
        return candidate.status == .available || candidate.status == .interrupted
    }

    static func archiveRow(
        _ archive: NovelDiscussionArchiveRecord,
        input: NovelSessionProjectionInput,
        revealedRowCount: Int,
        isExpanded: Bool
    ) -> NovelSessionRowModel {
        let chapterTitle: String
        if let chapterID = archive.chapterID,
           let index = input.branch.workingChapterSelections.firstIndex(where: {
               $0.chapterID == chapterID
           }) {
            chapterTitle = "第 \(index + 1) 章讨论已归档"
        } else {
            chapterTitle = "讨论已归档"
        }
        let presentation = NovelDiscussionArchivePresentation(
            id: archive.id,
            title: "\(chapterTitle)（\(archive.messageCount) 条）",
            summary: archive.summary,
            messageCount: archive.messageCount,
            revealedRowCount: revealedRowCount,
            isExpanded: isExpanded
        )
        return NovelSessionRowModel(
            id: archive.id,
            sequence: archive.throughSequence,
            role: .system,
            mode: .discussPlan,
            granularity: nil,
            kind: .discussion,
            content: archive.summary,
            createdAt: archive.createdAt,
            runID: nil,
            runStatus: nil,
            candidate: nil,
            committedChange: nil,
            askUser: nil,
            archive: presentation,
            transientPhase: nil,
            actions: [],
            digest: NovelSessionRowDigest(
                layout: "discussion-archive:\(archive.id.description)",
                presentation: "\(isExpanded ? "expanded" : "collapsed"):\(revealedRowCount)"
            )
        )
    }
}

private struct NovelSessionCollectedCheckpointKey: Hashable {
    let checkpointID: NovelCheckpointID
    let candidateID: NovelCandidateID
}

private struct NovelSessionProjectionIndex {
    let candidatesBySourceMessageID: [NovelMessageID: [NovelCandidateRecord]]
    let pendingCandidateIDs: Set<NovelCandidateID>
    let polishCandidateIDs: Set<NovelCandidateID>
    let pendingByCandidateID: [NovelCandidateID: NovelPendingOperationRecord]
    let polishByCandidateID: [NovelCandidateID: NovelPendingPolishTransactionRecord]
    let runByID: [NovelRunID: NovelActiveRunRecord]
    let unresolvedQuickStartProposalByRunID: [NovelRunID: NovelSettingProposalRecord]
    let collectedCheckpointByKey: [NovelSessionCollectedCheckpointKey: NovelBranchCheckpointRecord]
    let checkpointByID: [NovelCheckpointID: NovelBranchCheckpointRecord]
    let stateByID: [NovelStateSnapshotID: NovelStateSnapshotRecord]
    let eventByID: [NovelEventID: NovelStoryEventRecord]
    let askUserResponseByPromptMessageID: [NovelMessageID: NovelAskUserResponse]
    let workingChapterVersionIDs: Set<NovelChapterVersionID>
    let runningRunIDs: Set<NovelRunID>
    let branchPendingOperationIDs: Set<NovelPendingOperationID>
    let branchManualSyncPendingOperationIDs: Set<NovelPendingOperationID>
    let branchProseBlockingPendingOperationIDs: Set<NovelPendingOperationID>
    let branchBlockingPolishTransactionIDs: Set<NovelPendingOperationID>
    let discardedChapterVersionIDs: Set<NovelChapterVersionID>

    init(input: NovelSessionProjectionInput) {
        var candidatesBySourceMessageID: [NovelMessageID: [NovelCandidateRecord]] = [:]
        for candidate in input.candidates
        where candidate.branchID == input.branch.id && candidate.sessionID == input.session.id {
            candidatesBySourceMessageID[candidate.sourceMessageID, default: []].append(candidate)
        }
        self.candidatesBySourceMessageID = candidatesBySourceMessageID

        var pendingCandidateIDs: Set<NovelCandidateID> = []
        var pendingByCandidateID: [NovelCandidateID: NovelPendingOperationRecord] = [:]
        var branchPendingOperationIDs: Set<NovelPendingOperationID> = []
        var branchManualSyncPendingOperationIDs: Set<NovelPendingOperationID> = []
        var branchProseBlockingPendingOperationIDs: Set<NovelPendingOperationID> = []
        for pending in input.pendingOperations {
            if let candidateID = pending.candidateID {
                pendingCandidateIDs.insert(candidateID)
                if pending.branchID == input.branch.id, pendingByCandidateID[candidateID] == nil {
                    pendingByCandidateID[candidateID] = pending
                }
            }
            if pending.branchID == input.branch.id {
                branchPendingOperationIDs.insert(pending.id)
                if pending.kind == .manualSync {
                    branchManualSyncPendingOperationIDs.insert(pending.id)
                }
                if pending.blocksProseGeneration {
                    branchProseBlockingPendingOperationIDs.insert(pending.id)
                }
            }
        }
        self.pendingCandidateIDs = pendingCandidateIDs
        self.pendingByCandidateID = pendingByCandidateID
        self.branchPendingOperationIDs = branchPendingOperationIDs
        self.branchManualSyncPendingOperationIDs = branchManualSyncPendingOperationIDs
        self.branchProseBlockingPendingOperationIDs = branchProseBlockingPendingOperationIDs

        var polishCandidateIDs: Set<NovelCandidateID> = []
        var polishByCandidateID: [NovelCandidateID: NovelPendingPolishTransactionRecord] = [:]
        var branchBlockingPolishTransactionIDs: Set<NovelPendingOperationID> = []
        for transaction in input.polishTransactions {
            polishCandidateIDs.insert(transaction.candidateID)
            if transaction.branchID == input.branch.id, polishByCandidateID[transaction.candidateID] == nil {
                polishByCandidateID[transaction.candidateID] = transaction
            }
            if transaction.branchID == input.branch.id,
               transaction.status == .pending || transaction.status == .retryable || transaction.status == .blocked {
                branchBlockingPolishTransactionIDs.insert(transaction.id)
            }
        }
        self.polishCandidateIDs = polishCandidateIDs
        self.polishByCandidateID = polishByCandidateID
        self.branchBlockingPolishTransactionIDs = branchBlockingPolishTransactionIDs
        self.discardedChapterVersionIDs = input.discardedChapterVersionIDs

        var runByID: [NovelRunID: NovelActiveRunRecord] = [:]
        var runningRunIDs: Set<NovelRunID> = []
        for run in input.runs {
            if runByID[run.id] == nil { runByID[run.id] = run }
            if run.branchID == input.branch.id, run.status == .running {
                runningRunIDs.insert(run.id)
            }
        }
        self.runByID = runByID
        self.runningRunIDs = runningRunIDs

        var unresolvedQuickStartProposalByRunID: [NovelRunID: NovelSettingProposalRecord] = [:]
        for proposal in input.settingProposals where
            !proposal.isResolved && proposal.supersededByRunID == nil {
            guard case .some(.quickStart(let runID, _)) = proposal.origin else { continue }
            if unresolvedQuickStartProposalByRunID[runID] == nil {
                unresolvedQuickStartProposalByRunID[runID] = proposal
            }
        }
        self.unresolvedQuickStartProposalByRunID = unresolvedQuickStartProposalByRunID

        var collectedCheckpointByKey: [
            NovelSessionCollectedCheckpointKey: NovelBranchCheckpointRecord
        ] = [:]
        var checkpointByID: [NovelCheckpointID: NovelBranchCheckpointRecord] = [:]
        for checkpoint in input.checkpoints {
            if checkpointByID[checkpoint.id] == nil { checkpointByID[checkpoint.id] = checkpoint }
            if let candidateID = checkpoint.sourceCandidateID {
                let key = NovelSessionCollectedCheckpointKey(
                    checkpointID: checkpoint.id,
                    candidateID: candidateID
                )
                if collectedCheckpointByKey[key] == nil { collectedCheckpointByKey[key] = checkpoint }
            }
        }
        self.collectedCheckpointByKey = collectedCheckpointByKey
        self.checkpointByID = checkpointByID

        var stateByID: [NovelStateSnapshotID: NovelStateSnapshotRecord] = [:]
        for state in input.stateSnapshots where stateByID[state.id] == nil {
            stateByID[state.id] = state
        }
        self.stateByID = stateByID

        var eventByID: [NovelEventID: NovelStoryEventRecord] = [:]
        for event in input.events where eventByID[event.id] == nil {
            eventByID[event.id] = event
        }
        self.eventByID = eventByID
        var askResponses: [NovelMessageID: NovelAskUserResponse] = [:]
        for message in input.session.messages {
            guard case .some(.askUserAnswer(let response)) = message.interaction else { continue }
            if askResponses[response.promptMessageID] == nil {
                askResponses[response.promptMessageID] = response
            }
        }
        askUserResponseByPromptMessageID = askResponses
        self.workingChapterVersionIDs = Set(input.branch.workingChapterSelections.map(\.versionID))
    }

    func hasRunningRun(excluding runID: NovelRunID?) -> Bool {
        runningRunIDs.count > (runID.map(runningRunIDs.contains) == true ? 1 : 0)
    }

    func pendingOperationBlocker(
        excluding pendingID: NovelPendingOperationID?
    ) -> NovelSessionActionBlocker? {
        let excludesPending = pendingID.map(branchPendingOperationIDs.contains) == true
        let remainingCount = branchPendingOperationIDs.count - (excludesPending ? 1 : 0)
        guard remainingCount > 0 else { return nil }

        let excludesManualSync = pendingID.map(branchManualSyncPendingOperationIDs.contains) == true
        let remainingManualSyncCount = branchManualSyncPendingOperationIDs.count -
            (excludesManualSync ? 1 : 0)
        return remainingCount == remainingManualSyncCount ? .branchNeedsSync : .pendingOperation
    }

    func hasBlockingPolishTransaction(excluding transactionID: NovelPendingOperationID?) -> Bool {
        branchBlockingPolishTransactionIDs.count > (
            transactionID.map(branchBlockingPolishTransactionIDs.contains) == true ? 1 : 0
        )
    }
}

private extension NovelSessionPresentation {
    static func runOrder(_ lhs: NovelActiveRunRecord, _ rhs: NovelActiveRunRecord) -> Bool {
        if lhs.startedAt != rhs.startedAt { return lhs.startedAt < rhs.startedAt }
        return lhs.id.description < rhs.id.description
    }

    static func interruptedWithoutOutputRow(
        run: NovelActiveRunRecord,
        sequence: Int64,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> NovelSessionRowModel {
        durableRow(
            message: NovelSessionMessageRecord(
                id: run.messageID,
                sequence: sequence,
                role: .assistant,
                mode: run.mode,
                kind: .interruptedDraft,
                content: "",
                createdAt: run.terminalAt ?? run.startedAt,
                runID: run.id,
                candidateID: nil
            ),
            input: input,
            index: index
        )
    }

    static func durableRow(
        message: NovelSessionMessageRecord,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> NovelSessionRowModel {
        let candidate = presentedCandidate(for: message, index: index)
        let presentedCandidate: NovelSessionCandidatePresentation?
        let changeSummary: NovelSessionCommittedChangeSummary?
        if let candidate {
            presentedCandidate = candidatePresentation(for: candidate, index: index)
            changeSummary = committedChange(
                for: candidate,
                index: index,
                branchSyncStatus: input.branch.syncStatus
            )
        } else {
            presentedCandidate = nil
            changeSummary = nil
        }
        let actions = actions(
            for: message,
            candidate: candidate,
            input: input,
            index: index
        )
        let run = message.runID.flatMap { runID in
            index.runByID[runID]
        }
        let runStatus = run?.status
        let askUser: NovelAskUserPresentation?
        if case .some(.askUser(let prompt)) = message.interaction {
            askUser = NovelAskUserPresentation(
                prompt: prompt,
                response: index.askUserResponseByPromptMessageID[message.id]
            )
        } else {
            askUser = nil
        }
        let presentedContent: String
        if message.kind == .error, let failure = run?.terminalFailure {
            presentedContent = NovelPresentation.failureMessage(failure)
        } else if run?.kind == .quickStart, message.kind == .interruptedDraft {
            presentedContent = runStatus == .failed
                ? run?.terminalFailure.map(NovelPresentation.failureMessage) ?? ""
                : ""
        } else {
            presentedContent = message.content
        }
        let digest = digest(
            messageID: message.id,
            transientRevision: nil,
            transientPhase: nil,
            runStatus: runStatus,
            granularity: run?.granularity,
            candidate: presentedCandidate,
            committedChange: changeSummary,
            askUser: askUser,
            actions: actions
        )
        return NovelSessionRowModel(
            id: message.id,
            sequence: message.sequence,
            role: message.role,
            mode: message.mode,
            granularity: run?.granularity,
            kind: message.kind,
            content: presentedContent,
            createdAt: message.createdAt,
            runID: message.runID,
            runStatus: runStatus,
            candidate: presentedCandidate,
            committedChange: changeSummary,
            askUser: askUser,
            archive: nil,
            transientPhase: nil,
            actions: actions,
            digest: digest
        )
    }

    static func transientRow(
        tail: NovelSessionTransientTail,
        sequence: Int64,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> NovelSessionRowModel {
        let actions: [NovelSessionRowActionAvailability]
        switch tail.phase {
        case .persistenceBlocked:
            actions = [availability(
                .retryTerminalPersistence(tail.runID),
                blocker: baseMutationBlocker(
                    input: input,
                    index: index,
                    includePending: false,
                    excludingRunID: tail.runID
                )
            )]
        case .interrupted:
            var interruptedActions: [NovelSessionRowActionAvailability] = []
            if tail.kind == .proseCandidate,
               !tail.content.isEmpty,
               let candidateID = tail.candidateID {
                interruptedActions.append(availability(
                    .collectProse(candidateID),
                    blocker: transientCollectionBlocker(tail: tail, input: input, index: index)
                ))
            }
            interruptedActions.append(availability(
                .retryGeneration(tail.runID),
                blocker: transientRetryBlocker(tail: tail, input: input, index: index)
            ))
            actions = interruptedActions
        case .failed(let failure):
            actions = [availability(
                .retryGeneration(tail.runID),
                blocker: failure.isRetryable
                    ? transientRetryBlocker(tail: tail, input: input, index: index)
                    : .failureNotRetryable
            )]
        case .waitingForFirstToken, .streaming, .terminalAwaitingRefresh:
            actions = []
        }
        let presentedKind: NovelSessionMessageKind = switch tail.phase {
        case .interrupted, .failed:
            tail.content.isEmpty ? .error : .interruptedDraft
        case .waitingForFirstToken, .streaming, .terminalAwaitingRefresh, .persistenceBlocked:
            tail.kind
        }
        return NovelSessionRowModel(
            id: tail.messageID,
            sequence: sequence,
            role: .assistant,
            mode: tail.mode,
            granularity: tail.granularity,
            kind: presentedKind,
            content: presentedContent(for: tail),
            reasoningContent: tail.reasoningContent,
            isReasoningLive: tail.isReasoningLive,
            createdAt: tail.startedAt,
            runID: tail.runID,
            runStatus: transientRunStatus(for: tail.phase),
            candidate: nil,
            committedChange: nil,
            askUser: nil,
            archive: nil,
            transientPhase: tail.phase,
            actions: actions,
            digest: digest(
                messageID: tail.messageID,
                transientRevision: tail.renderRevision,
                transientPhase: tail.phase,
                runStatus: transientRunStatus(for: tail.phase),
                granularity: tail.granularity,
                candidate: nil,
                committedChange: nil,
                askUser: nil,
                actions: actions
            )
        )
    }

    static func presentedContent(for tail: NovelSessionTransientTail) -> String {
        switch tail.phase {
        case .failed(let failure):
            // Prefer humanized failure copy when there is no draft yet, or when
            // content accidentally carries a raw NSError/URL dump.
            if tail.content.isEmpty
                || NovelPresentation.looksLikeTechnicalFailureDump(tail.content) {
                return NovelPresentation.failureMessage(failure)
            }
            return tail.content
        case .persistenceBlocked(let failure):
            if tail.content.isEmpty
                || NovelPresentation.looksLikeTechnicalFailureDump(tail.content) {
                return NovelPresentation.failureMessage(failure)
            }
            return tail.content
        default:
            return tail.content
        }
    }

    static func presentedCandidate(
        for message: NovelSessionMessageRecord,
        index: NovelSessionProjectionIndex
    ) -> NovelCandidateRecord? {
        guard let matches = index.candidatesBySourceMessageID[message.id] else { return nil }

        if let pending = matches.first(where: { index.pendingCandidateIDs.contains($0.id) }) {
            return pending
        }
        if let transaction = matches.first(where: { index.polishCandidateIDs.contains($0.id) }) {
            return transaction
        }
        if let available = matches
            .filter({ $0.status == .available })
            .max(by: candidateOrder) {
            return available
        }
        if let messageCandidateID = message.candidateID,
           let direct = matches.first(where: { $0.id == messageCandidateID }) {
            return direct
        }
        return matches.max(by: candidateOrder)
    }

    static func candidateOrder(
        _ lhs: NovelCandidateRecord,
        _ rhs: NovelCandidateRecord
    ) -> Bool {
        if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
        return lhs.id.description < rhs.id.description
    }

    static func candidatePresentation(
        for candidate: NovelCandidateRecord,
        index: NovelSessionProjectionIndex
    ) -> NovelSessionCandidatePresentation {
        NovelSessionCandidatePresentation(
            id: candidate.id,
            kind: candidate.kind,
            status: candidate.status,
            sourceChapterVersionID: candidate.sourceChapterVersionID,
            pendingStatus: index.pendingByCandidateID[candidate.id]?.status,
            polishTransactionStatus: index.polishByCandidateID[candidate.id]?.status
        )
    }

    static func committedChange(
        for candidate: NovelCandidateRecord,
        index: NovelSessionProjectionIndex,
        branchSyncStatus: NovelBranchSyncStatus
    ) -> NovelSessionCommittedChangeSummary? {
        guard let checkpointID = candidate.collectedCheckpointID,
              let checkpoint = index.collectedCheckpointByKey[NovelSessionCollectedCheckpointKey(
                  checkpointID: checkpointID,
                  candidateID: candidate.id
              )],
              let state = index.stateByID[checkpoint.stateSnapshotID] else {
            return nil
        }
        let parentEventIDs: Set<NovelEventID>
        if let parentID = checkpoint.parentCheckpointID,
           let parent = index.checkpointByID[parentID],
           let parentState = index.stateByID[parent.stateSnapshotID] {
            parentEventIDs = Set(parentState.eventIDs)
        } else {
            parentEventIDs = []
        }
        let eventSummaries = state.eventIDs.compactMap { id -> String? in
            guard !parentEventIDs.contains(id) else { return nil }
            return index.eventByID[id]?.summary
        }
        return NovelSessionCommittedChangeSummary(
            checkpointID: checkpoint.id,
            stateSnapshotID: state.id,
            stateSummary: state.summary,
            eventSummaries: eventSummaries,
            branchSyncStatus: branchSyncStatus
        )
    }

    static func actions(
        for message: NovelSessionMessageRecord,
        candidate: NovelCandidateRecord?,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> [NovelSessionRowActionAvailability] {
        guard message.role == .assistant else { return [] }
        if let candidate {
            var result = candidateActions(candidate, input: input, index: index)
            if candidate.status == .interrupted,
               let runID = message.runID,
               let run = index.runByID[runID] {
                result.append(availability(
                    .retryGeneration(runID),
                    blocker: retryBlocker(for: run, input: input, index: index)
                ))
            }
            return result
        }
        if let runID = message.runID,
           index.runByID[runID]?.kind == .quickStart,
           let proposal = index.unresolvedQuickStartProposalByRunID[runID] {
            return [availability(
                .viewSettingProposals(NovelSettingProposalRoute(kind: proposal.suggestedMaterialKind)),
                blocker: nil
            )]
        }
        guard message.kind == .interruptedDraft || message.kind == .error,
              let runID = message.runID,
              let run = index.runByID[runID] else {
            return []
        }
        if run.status == .failed, run.terminalFailure?.isRetryable != true {
            return [availability(.retryGeneration(runID), blocker: .failureNotRetryable)]
        }
        return [availability(
            .retryGeneration(runID),
            blocker: retryBlocker(for: run, input: input, index: index)
        )]
    }

    static func candidateActions(
        _ candidate: NovelCandidateRecord,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> [NovelSessionRowActionAvailability] {
        if let pending = index.pendingByCandidateID[candidate.id] {
            return [availability(
                .retryPending(pending.id),
                blocker: baseMutationBlocker(
                    input: input,
                    index: index,
                    includePending: true,
                    excludingPendingID: pending.id
                )
            )]
        }

        if let transaction = index.polishByCandidateID[candidate.id] {
            switch transaction.status {
            case .pending:
                let baseBlocker = baseMutationBlocker(
                    input: input,
                    index: index,
                    includePending: true,
                    excludingPolishTransactionID: transaction.id
                )
                let retryBlocker = baseBlocker ??
                    polishTransactionSourceBlocker(transaction, index: index) ??
                    staleBlocker(candidate, input: input)
                return [
                    availability(.retryPolish(transaction.id), blocker: retryBlocker),
                    availability(.abandonPolish(transaction.id), blocker: baseBlocker)
                ]
            case .retryable:
                let baseBlocker = baseMutationBlocker(
                    input: input,
                    index: index,
                    includePending: true,
                    excludingPolishTransactionID: transaction.id
                )
                let retryBlocker = baseBlocker ??
                    polishTransactionSourceBlocker(transaction, index: index) ??
                    staleBlocker(candidate, input: input)
                return [
                    availability(.retryPolish(transaction.id), blocker: retryBlocker),
                    availability(.abandonPolish(transaction.id), blocker: baseBlocker)
                ]
            case .blocked:
                return [availability(
                    .abandonPolish(transaction.id),
                    blocker: baseMutationBlocker(
                        input: input,
                        index: index,
                        includePending: true,
                        excludingPolishTransactionID: transaction.id
                    )
                )]
            case .incompatible:
                guard let sourceID = candidate.sourceChapterVersionID else { return [] }
                return [availability(
                    .convertPolishToManualRewrite(
                        candidateID: candidate.id,
                        sourceChapterVersionID: sourceID
                    ),
                    blocker: manualRewriteBlocker(
                        sourceChapterVersionID: sourceID,
                        input: input,
                        index: index
                    )
                )]
            case .completed, .abandoned:
                break
            }
        }

        switch candidate.status {
        case .available:
            let blocker = candidateMutationBlocker(candidate, input: input, index: index)
            switch candidate.kind {
            case .prose:
                return [availability(.collectProse(candidate.id), blocker: blocker)]
            case .polish:
                return [availability(.adoptPolish(candidate.id), blocker: blocker)]
            }
        case .collected, .adopted:
            var result: [NovelSessionRowActionAvailability] = []
            if undoRemovesCandidateCheckpoint(candidate, input: input, index: index) {
                result.append(availability(
                    .undoCommittedChange(
                        checkpointID: input.branch.headCheckpointID,
                        kind: candidate.kind
                    ),
                    blocker: undoBlocker(input: input, index: index)
                ))
            }
            if let checkpointID = candidate.collectedCheckpointID {
                result.append(availability(
                    .forkFromCheckpoint(checkpointID),
                    blocker: forkBlocker(input: input, index: index)
                ))
            }
            if candidate.kind == .prose,
               candidate.status == .collected,
               NovelCandidateSemantics.cloneBaseMatches(
                   candidate,
                   currentCheckpointID: input.branch.headCheckpointID,
                   checkpoints: input.checkpoints,
                   sourceMessage: input.session.messages.first {
                       $0.id == candidate.sourceMessageID
                   }
               ) {
                result.append(availability(
                    .cloneCollectedProse(candidate.id),
                    blocker: candidateMutationBlocker(
                        candidate,
                        input: input,
                        index: index,
                        requiresCurrentBase: false
                    )
                ))
            }
            return result
        case .interrupted:
            guard candidate.kind == .prose else { return [] }
            return [availability(
                .collectProse(candidate.id),
                blocker: candidateMutationBlocker(candidate, input: input, index: index)
            )]
        case .superseded, .inheritedReadOnly:
            return []
        }
    }

    static func undoRemovesCandidateCheckpoint(
        _ candidate: NovelCandidateRecord,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> Bool {
        guard let candidateCheckpointID = candidate.collectedCheckpointID,
              let head = index.checkpointByID[input.branch.headCheckpointID],
              let target = NovelBranchSemantics.undoTarget(
                  for: head,
                  branch: input.branch,
                  checkpoints: input.checkpoints
              ) else {
            return false
        }
        if candidateCheckpointID == head.id { return true }
        guard head.parentCheckpointID == candidateCheckpointID,
              let candidateCheckpoint = index.checkpointByID[candidateCheckpointID] else {
            return false
        }
        return target.id == candidateCheckpoint.parentCheckpointID
    }

    static func retryBlocker(
        for run: NovelActiveRunRecord,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex,
        excludingRunID: NovelRunID? = nil
    ) -> NovelSessionActionBlocker? {
        if let blocker = baseMutationBlocker(
            input: input,
            index: index,
            includePending: false,
            excludingRunID: excludingRunID
        ) {
            return blocker
        }
        if run.kind == .polish || run.kind == .regenerate || run.kind == .prose {
            if input.branch.syncStatus == .needsSync {
                return .branchNeedsSync
            }
            if run.kind == .prose,
               run.granularity == .wholeChapter,
               input.collaborationMode == .ghostwrite,
               !input.hasConfirmedChapterPlan {
                return .chapterPlanRequired
            }
            if run.kind == .polish || run.kind == .regenerate {
                if let blocker = index.pendingOperationBlocker(excluding: nil) {
                    return blocker
                }
                if index.hasBlockingPolishTransaction(excluding: nil) { return .pendingOperation }
            } else if !index.branchProseBlockingPendingOperationIDs.isEmpty {
                return .pendingOperation
            }
        }
        if run.kind == .prose || run.kind == .polish || run.kind == .regenerate {
            if input.branch.headCheckpointID != run.baseCheckpointID ||
                input.branch.headRevision != run.baseHeadRevision {
                return .staleCandidate
            }
            if run.kind == .polish || run.kind == .regenerate {
                guard let sourceVersionID = run.sourceChapterVersionID,
                      index.workingChapterVersionIDs.contains(sourceVersionID),
                      !input.discardedChapterVersionIDs.contains(sourceVersionID) else {
                    return .sourceChapterChanged
                }
            }
        }
        return nil
    }

    static func transientRetryBlocker(
        tail: NovelSessionTransientTail,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> NovelSessionActionBlocker? {
        if let run = index.runByID[tail.runID] {
            return retryBlocker(
                for: run,
                input: input,
                index: index,
                excludingRunID: tail.runID
            )
        }
        if let blocker = baseMutationBlocker(
            input: input,
            index: index,
            includePending: false,
            excludingRunID: tail.runID
        ) {
            return blocker
        }
        if tail.kind == .proseCandidate || tail.kind == .polishCandidate {
            if input.branch.syncStatus == .needsSync {
                return .branchNeedsSync
            }
            if tail.kind == .proseCandidate,
               tail.granularity == .wholeChapter,
               input.collaborationMode == .ghostwrite,
               !input.hasConfirmedChapterPlan {
                return .chapterPlanRequired
            }
            if !index.branchProseBlockingPendingOperationIDs.isEmpty {
                return .pendingOperation
            }
        }
        return nil
    }

    static func transientCollectionBlocker(
        tail: NovelSessionTransientTail,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> NovelSessionActionBlocker? {
        if let blocker = baseMutationBlocker(
            input: input,
            index: index,
            includePending: true,
            excludingRunID: tail.runID
        ) {
            return blocker
        }
        guard input.branch.syncStatus == .synchronized else { return .branchNeedsSync }
        guard let run = index.runByID[tail.runID],
              run.baseCheckpointID == input.branch.headCheckpointID,
              run.baseHeadRevision == input.branch.headRevision else {
            return .staleCandidate
        }
        return nil
    }

    static func candidateMutationBlocker(
        _ candidate: NovelCandidateRecord,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex,
        requiresCurrentBase: Bool = true
    ) -> NovelSessionActionBlocker? {
        if let blocker = baseMutationBlocker(input: input, index: index, includePending: true) {
            return blocker
        }
        if input.branch.syncStatus == .needsSync {
            return .branchNeedsSync
        }
        if requiresCurrentBase {
            let baseMatches: Bool
            if candidate.kind == .prose {
                let sourceMessage = input.session.messages.first {
                    $0.id == candidate.sourceMessageID
                }
                baseMatches = NovelCandidateSemantics.collectionBaseMatches(
                    candidate,
                    targetCheckpointID: input.branch.headCheckpointID,
                    targetHeadRevision: input.branch.headRevision,
                    checkpoints: input.checkpoints,
                    sourceMessage: sourceMessage
                )
            } else {
                baseMatches = candidate.baseCheckpointID == input.branch.headCheckpointID &&
                    candidate.baseHeadRevision == input.branch.headRevision
            }
            if !baseMatches { return .staleCandidate }
        }
        if requiresCurrentBase,
           let sourceID = candidate.sourceChapterVersionID,
           index.discardedChapterVersionIDs.contains(sourceID) {
            return .sourceChapterChanged
        }
        if candidate.kind == .polish,
           let sourceID = candidate.sourceChapterVersionID,
           !index.workingChapterVersionIDs.contains(sourceID) {
            return .sourceChapterChanged
        }
        return nil
    }

    static func baseMutationBlocker(
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex,
        includePending: Bool,
        excludingPendingID: NovelPendingOperationID? = nil,
        excludingPolishTransactionID: NovelPendingOperationID? = nil,
        excludingRunID: NovelRunID? = nil
    ) -> NovelSessionActionBlocker? {
        if input.access != .readWrite { return .projectReadOnly }
        if input.branch.lifecycle != .active { return .branchInactive }
        if let tail = input.transientTail,
           tail.branchID == input.branch.id,
           tail.sessionID == input.session.id,
           tail.phase == .waitingForFirstToken || tail.phase == .streaming {
            return .generationRunning
        }
        if (input.branch.activeRunID != nil && input.branch.activeRunID != excludingRunID) ||
            index.hasRunningRun(excluding: excludingRunID) {
            return .generationRunning
        }
        if includePending {
            if let blocker = index.pendingOperationBlocker(excluding: excludingPendingID) {
                return blocker
            }
            if index.hasBlockingPolishTransaction(excluding: excludingPolishTransactionID) {
                return .pendingOperation
            }
        }
        return nil
    }

    static func manualRewriteBlocker(
        sourceChapterVersionID: NovelChapterVersionID,
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> NovelSessionActionBlocker? {
        if let blocker = baseMutationBlocker(input: input, index: index, includePending: true) {
            return blocker
        }
        guard index.workingChapterVersionIDs.contains(sourceChapterVersionID) else {
            return .sourceChapterChanged
        }
        return nil
    }

    static func staleBlocker(
        _ candidate: NovelCandidateRecord,
        input: NovelSessionProjectionInput
    ) -> NovelSessionActionBlocker? {
        candidate.baseCheckpointID == input.branch.headCheckpointID &&
            candidate.baseHeadRevision == input.branch.headRevision
            ? nil
            : .staleCandidate
    }

    static func polishTransactionSourceBlocker(
        _ transaction: NovelPendingPolishTransactionRecord,
        index: NovelSessionProjectionIndex
    ) -> NovelSessionActionBlocker? {
        guard index.workingChapterVersionIDs.contains(transaction.sourceChapterVersionID),
              !index.discardedChapterVersionIDs.contains(transaction.sourceChapterVersionID) else {
            return .sourceChapterChanged
        }
        return nil
    }

    static func forkBlocker(
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> NovelSessionActionBlocker? {
        baseMutationBlocker(input: input, index: index, includePending: false)
    }

    static func undoBlocker(
        input: NovelSessionProjectionInput,
        index: NovelSessionProjectionIndex
    ) -> NovelSessionActionBlocker? {
        if let blocker = baseMutationBlocker(input: input, index: index, includePending: true) {
            return blocker
        }
        guard input.branch.syncStatus == .needsSync else { return nil }
        guard let head = input.checkpoints.first(where: {
            $0.id == input.branch.headCheckpointID
        }), NovelBranchSemantics.canUndoHead(
            head,
            branch: input.branch,
            checkpoints: input.checkpoints
        ) else {
            return .branchNeedsSync
        }
        return nil
    }

    static func availability(
        _ action: NovelSessionRowAction,
        blocker: NovelSessionActionBlocker?
    ) -> NovelSessionRowActionAvailability {
        NovelSessionRowActionAvailability(action: action, blocker: blocker)
    }

    static func digest(
        messageID: NovelMessageID,
        transientRevision: UInt64?,
        transientPhase: NovelSessionTransientTailPhase?,
        runStatus: NovelRunStatus?,
        granularity: NovelGenerationGranularity?,
        candidate: NovelSessionCandidatePresentation?,
        committedChange: NovelSessionCommittedChangeSummary?,
        askUser: NovelAskUserPresentation?,
        actions: [NovelSessionRowActionAvailability]
    ) -> NovelSessionRowDigest {
        let contentToken: String
        if let transientRevision {
            contentToken = "tail:\(messageID):\(transientRevision):\(phaseToken(transientPhase))"
        } else {
            // Session messages are immutable after append; identity is therefore a complete
            // durable-content revision and avoids re-hashing historical whole chapters.
            contentToken = "durable:\(messageID)"
        }
        let candidateToken = candidate.map {
            "\($0.id):\($0.status.rawValue):\($0.pendingStatus?.rawValue ?? "-"):" +
                "\($0.polishTransactionStatus?.rawValue ?? "-")"
        } ?? "none"
        let committedToken = committedChange.map {
            "\($0.checkpointID):\($0.stateSnapshotID):\($0.eventSummaries.count):" +
                "\($0.branchSyncStatus.rawValue)"
        } ?? "none"
        let askUserToken = askUser.map { presentation in
            "ask:\(presentation.prompt.question):" +
                (presentation.isAnswered ? "answered" : "pending")
        } ?? "none"
        let actionToken = actions.map(actionToken).joined(separator: "|")
        return NovelSessionRowDigest(
            layout: "\(contentToken);\(runStatus?.rawValue ?? "-");" +
                "\(granularity?.rawValue ?? "-");\(candidateToken);" +
                "\(committedToken);\(askUserToken);\(actionToken)",
            presentation: actionToken
        )
    }

    static func transientRunStatus(
        for phase: NovelSessionTransientTailPhase
    ) -> NovelRunStatus {
        switch phase {
        case .interrupted:
            .interrupted
        case .failed:
            .failed
        case .waitingForFirstToken, .streaming, .terminalAwaitingRefresh, .persistenceBlocked:
            .running
        }
    }

    static func phaseToken(_ phase: NovelSessionTransientTailPhase?) -> String {
        switch phase {
        case .waitingForFirstToken: "waiting"
        case .streaming: "streaming"
        case .terminalAwaitingRefresh: "terminal-awaiting-refresh"
        case .interrupted: "interrupted"
        case .failed(let failure):
            "failed:\(failure.code):\(failure.isRetryable)"
        case .persistenceBlocked(let failure):
            "blocked:\(failure.code):\(failure.isRetryable)"
        case nil: "none"
        }
    }

    static func actionToken(_ availability: NovelSessionRowActionAvailability) -> String {
        "\(availability.action):\(availability.blocker?.rawValue ?? "enabled")"
    }
}

enum NovelSessionBottomFollowMode: Equatable, Sendable {
    case awaitingInitialRows
    case followingBottom
    case browsingHistory
    case settlingTerminal(token: UInt64)
}

struct NovelSessionBottomFollowState: Equatable, Sendable {
    var mode: NovelSessionBottomFollowMode = .awaitingInitialRows
    var showsBottomButton = false
    var nextSettleToken: UInt64 = 0
}

enum NovelSessionBottomFollowEvent: Equatable, Sendable {
    case reset
    case initialRowsPresented(hasRows: Bool)
    case streamStarted
    case streamDelta
    case measuredStreamGrowth(isAtBottom: Bool)
    case measuredTerminalGrowth(isAtBottom: Bool)
    case staticContentGrowth(isAtBottom: Bool)
    case userDragBegan(isAtBottom: Bool)
    case userDragEnded(isAtBottom: Bool)
    case viewportChanged(isAtBottom: Bool)
    case explicitBottomRequested
    case terminalReached
    case terminalLayoutChanged
    case terminalQuietElapsed(token: UInt64)
}

enum NovelSessionBottomFollowCommand: Equatable, Sendable {
    case anchorBottom
    case followBottom(animated: Bool)
    /// 生成终态：driver 激活时发 generationTerminated（逐帧钉底 + 静默交还），
    /// 与 Chat 同构；driver 未激活时退回 followBottom + quietSettle。
    case terminateGeneration
    case setBottomButton(Bool)
    case scheduleTerminalQuietSettle(token: UInt64, delay: TimeInterval)
}

struct NovelSessionBottomFollowTransition: Equatable, Sendable {
    let state: NovelSessionBottomFollowState
    let commands: [NovelSessionBottomFollowCommand]
}

enum NovelSessionBottomFollowPolicy {
    static let terminalQuietDelay: TimeInterval = 0.4

    static func reduce(
        state: NovelSessionBottomFollowState,
        event: NovelSessionBottomFollowEvent,
        followEnabled: Bool = true
    ) -> NovelSessionBottomFollowTransition {
        var next = state
        var commands: [NovelSessionBottomFollowCommand] = []

        switch event {
        case .reset:
            next = NovelSessionBottomFollowState()

        case .initialRowsPresented(let hasRows):
            guard next.mode == .awaitingInitialRows else { break }
            next.mode = .followingBottom
            if hasRows { commands.append(.anchorBottom) }

        case .streamStarted:
            next.mode = .followingBottom
            setBottomButton(false, state: &next, commands: &commands)
            commands.append(.anchorBottom)

        case .streamDelta:
            break

        case .measuredStreamGrowth(let isAtBottom):
            guard followEnabled else {
                handleDisabledMeasuredGrowth(
                    isAtBottom: isAtBottom,
                    state: &next,
                    commands: &commands
                )
                break
            }
            switch next.mode {
            case .followingBottom:
                // 2026-07-26 撤锚更正:`.sizeChanges` 底锚已被真机录屏(15fps 逐帧对齐,
                // 连续三次 −391/−398/−385px 结构性跳变)推翻——它在生产的
                // ParagraphUIView 异步增量 TextKit 布局路径下并不能同步吸收增长
                // (合成的 Color.frame(height:) 单次布局 pass 测不到这条路径)。
                // measured-geometry 回调
                // 重新成为流式跟随底部的唯一写者;命令必须是 animated:false——
                // 执行侧 NovelSessionView.scrollToBottomWithoutAnimation() 用
                // Transaction(animation: nil) 显式禁用动画,不经过
                // startExplicitBottomAnimation() 的 0.2s easeOut。这正是
                // PROJECT_STATE 2026-07-21 记录的「先欠账、再用 0.08s 动画追回」
                // 53pt 回归不能复发的关键:那次回归的病根不是「measured growth 驱动
                // 跟随」本身,而是「给语义为瞬时到位的自动跟随包了动画」。
                commands.append(.followBottom(animated: false))
            case .settlingTerminal:
                next.mode = .followingBottom
                commands.append(.followBottom(animated: false))
            case .awaitingInitialRows, .browsingHistory:
                break
            }

        case .measuredTerminalGrowth(let isAtBottom):
            guard followEnabled else {
                handleDisabledMeasuredGrowth(
                    isAtBottom: isAtBottom,
                    state: &next,
                    commands: &commands
                )
                break
            }
            scheduleTerminalSettle(state: &next, commands: &commands)

        case .staticContentGrowth(let isAtBottom):
            // 静态排版增长不拥有滚动：不发滚动命令、不改模式，只让底部按钮
            // 反映真实的到底状态，用户始终保有主动回底的入口。
            guard next.mode != .awaitingInitialRows else { break }
            setBottomButton(!isAtBottom, state: &next, commands: &commands)

        case .userDragBegan(let isAtBottom):
            next.mode = .browsingHistory
            setBottomButton(!isAtBottom, state: &next, commands: &commands)

        case .userDragEnded(let isAtBottom):
            if isAtBottom, followEnabled {
                next.mode = .followingBottom
                setBottomButton(false, state: &next, commands: &commands)
                commands.append(.followBottom(animated: false))
            } else {
                next.mode = .browsingHistory
                setBottomButton(!isAtBottom, state: &next, commands: &commands)
            }

        case .viewportChanged(let isAtBottom):
            switch next.mode {
            case .followingBottom, .settlingTerminal:
                if !isAtBottom {
                    if followEnabled {
                        commands.append(.followBottom(animated: false))
                    } else {
                        next.mode = .browsingHistory
                        setBottomButton(true, state: &next, commands: &commands)
                    }
                } else {
                    // staticContentGrowth 可能在跟随态亮过按钮；几何回底后必须
                    // 收掉，保证按钮始终反映真实到底状态（无按钮时是无操作）。
                    setBottomButton(false, state: &next, commands: &commands)
                }
            case .browsingHistory:
                // Passive geometry cannot resume follow. Only the user's drag ending at
                // bottom, or the explicit bottom command, owns that transition.
                setBottomButton(!isAtBottom, state: &next, commands: &commands)
            case .awaitingInitialRows:
                break
            }

        case .explicitBottomRequested:
            next.mode = .followingBottom
            setBottomButton(false, state: &next, commands: &commands)
            commands.append(.followBottom(animated: true))

        case .terminalReached, .terminalLayoutChanged:
            guard followEnabled else { break }
            scheduleTerminalSettle(state: &next, commands: &commands)

        case .terminalQuietElapsed(let token):
            guard next.mode == .settlingTerminal(token: token) else { break }
            next.mode = .followingBottom
            if followEnabled {
                commands.append(.followBottom(animated: false))
            }
        }

        return NovelSessionBottomFollowTransition(state: next, commands: commands)
    }
}

private extension NovelSessionBottomFollowPolicy {
    static func handleDisabledMeasuredGrowth(
        isAtBottom: Bool,
        state: inout NovelSessionBottomFollowState,
        commands: inout [NovelSessionBottomFollowCommand]
    ) {
        switch state.mode {
        case .followingBottom, .settlingTerminal:
            if !isAtBottom {
                state.mode = .browsingHistory
                setBottomButton(true, state: &state, commands: &commands)
            }
        case .browsingHistory:
            setBottomButton(!isAtBottom, state: &state, commands: &commands)
        case .awaitingInitialRows:
            break
        }
    }

    static func scheduleTerminalSettle(
        state: inout NovelSessionBottomFollowState,
        commands: inout [NovelSessionBottomFollowCommand]
    ) {
        switch state.mode {
        case .followingBottom, .settlingTerminal:
            state.nextSettleToken &+= 1
            let token = state.nextSettleToken
            state.mode = .settlingTerminal(token: token)
            // driver 激活时由它逐帧钉底（generationTerminated），视图层不再
            // 需要 quietSettle 定时器——driver 的 idleStopInterval(0.3s) 静默
            // 交还 + viewportChanged idle 近底重锚兜底晚到布局。
            commands.append(.terminateGeneration)
            commands.append(.scheduleTerminalQuietSettle(
                token: token,
                delay: terminalQuietDelay
            ))
        case .awaitingInitialRows, .browsingHistory:
            break
        }
    }

    static func setBottomButton(
        _ visible: Bool,
        state: inout NovelSessionBottomFollowState,
        commands: inout [NovelSessionBottomFollowCommand]
    ) {
        guard state.showsBottomButton != visible else { return }
        state.showsBottomButton = visible
        commands.append(.setBottomButton(visible))
    }
}
