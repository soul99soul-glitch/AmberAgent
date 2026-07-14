import Foundation

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
    let messageID: NovelMessageID
    let candidateID: NovelCandidateID?
    let mode: NovelSessionMode
    let kind: NovelSessionMessageKind
    let content: String
    /// Monotonic per-tail revision owned by the Session ViewModel.
    /// The row digest uses this instead of hashing an ever-growing whole chapter.
    let renderRevision: UInt64
    let startedAt: Date
    let phase: NovelSessionTransientTailPhase

    init(
        run: NovelActiveRunRecord,
        content: String,
        renderRevision: UInt64,
        phase: NovelSessionTransientTailPhase
    ) {
        branchID = run.branchID
        sessionID = run.sessionID
        runID = run.id
        messageID = run.messageID
        candidateID = run.candidateID
        mode = run.mode
        kind = switch run.kind {
        case .quickStart, .discussion: .discussion
        case .prose: .proseCandidate
        case .polish: .polishCandidate
        }
        self.content = content
        self.renderRevision = renderRevision
        startedAt = run.startedAt
        self.phase = phase
    }

    func updating(
        content: String,
        renderRevision: UInt64,
        phase: NovelSessionTransientTailPhase
    ) -> NovelSessionTransientTail {
        NovelSessionTransientTail(
            branchID: branchID,
            sessionID: sessionID,
            runID: runID,
            messageID: messageID,
            candidateID: candidateID,
            mode: mode,
            kind: kind,
            content: content,
            renderRevision: renderRevision,
            startedAt: startedAt,
            phase: phase
        )
    }

    func updating(
        content: String,
        phase: NovelSessionTransientTailPhase
    ) -> NovelSessionTransientTail {
        updating(
            content: content,
            renderRevision: renderRevision &+ 1,
            phase: phase
        )
    }

    private init(
        branchID: NovelBranchID,
        sessionID: NovelSessionID,
        runID: NovelRunID,
        messageID: NovelMessageID,
        candidateID: NovelCandidateID?,
        mode: NovelSessionMode,
        kind: NovelSessionMessageKind,
        content: String,
        renderRevision: UInt64,
        startedAt: Date,
        phase: NovelSessionTransientTailPhase
    ) {
        self.branchID = branchID
        self.sessionID = sessionID
        self.runID = runID
        self.messageID = messageID
        self.candidateID = candidateID
        self.mode = mode
        self.kind = kind
        self.content = content
        self.renderRevision = renderRevision
        self.startedAt = startedAt
        self.phase = phase
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
        case .masterOutline: .story
        case .writingRequirements, .custom, nil: .more
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
    case branchInactive
    case branchNeedsSync
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
    let kind: NovelSessionMessageKind
    let content: String
    let createdAt: Date
    let runID: NovelRunID?
    let runStatus: NovelRunStatus?
    let candidate: NovelSessionCandidatePresentation?
    let committedChange: NovelSessionCommittedChangeSummary?
    let transientPhase: NovelSessionTransientTailPhase?
    let actions: [NovelSessionRowActionAvailability]
    let digest: NovelSessionRowDigest

    var isTransient: Bool { transientPhase != nil }

    var isStreaming: Bool {
        switch transientPhase {
        case .waitingForFirstToken, .streaming: true
        case .terminalAwaitingRefresh, .interrupted, .failed, .persistenceBlocked, nil: false
        }
    }
}

struct NovelSessionListModel: Equatable, Sendable {
    let sessionID: NovelSessionID
    let rows: [NovelSessionRowModel]
    let activeTailID: NovelMessageID?
}

struct NovelSessionProjectionInput: Equatable, Sendable {
    let branch: NovelBranchRecord
    let session: NovelSessionRecord
    let candidates: [NovelCandidateRecord]
    let runs: [NovelActiveRunRecord]
    let pendingOperations: [NovelPendingOperationRecord]
    let polishTransactions: [NovelPendingPolishTransactionRecord]
    let checkpoints: [NovelBranchCheckpointRecord]
    let stateSnapshots: [NovelStateSnapshotRecord]
    let events: [NovelStoryEventRecord]
    let settingProposals: [NovelSettingProposalRecord]
    let access: NovelProjectLoadAccess
    let transientTail: NovelSessionTransientTail?

    init(
        branch: NovelBranchRecord,
        session: NovelSessionRecord,
        candidates: [NovelCandidateRecord],
        runs: [NovelActiveRunRecord],
        pendingOperations: [NovelPendingOperationRecord],
        polishTransactions: [NovelPendingPolishTransactionRecord],
        checkpoints: [NovelBranchCheckpointRecord] = [],
        stateSnapshots: [NovelStateSnapshotRecord] = [],
        events: [NovelStoryEventRecord] = [],
        settingProposals: [NovelSettingProposalRecord] = [],
        access: NovelProjectLoadAccess,
        transientTail: NovelSessionTransientTail?
    ) {
        self.branch = branch
        self.session = session
        self.candidates = candidates
        self.runs = runs
        self.pendingOperations = pendingOperations
        self.polishTransactions = polishTransactions
        self.checkpoints = checkpoints
        self.stateSnapshots = stateSnapshots
        self.events = events
        self.settingProposals = settingProposals
        self.access = access
        self.transientTail = transientTail
    }

    init(
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot,
        transientTail: NovelSessionTransientTail?
    ) {
        self.init(
            branch: branch.branch,
            session: branch.session,
            candidates: project.candidates,
            runs: project.activeRuns,
            pendingOperations: project.pendingOperations,
            polishTransactions: project.polishTransactions,
            checkpoints: project.checkpoints,
            stateSnapshots: project.stateSnapshots,
            events: project.events,
            settingProposals: branch.activeSettingProposals,
            access: project.access,
            transientTail: transientTail
        )
    }
}

enum NovelSessionPresentation {
    static func project(_ input: NovelSessionProjectionInput) -> NovelSessionListModel {
        let messages = input.session.messages.sorted { lhs, rhs in
            if lhs.sequence != rhs.sequence { return lhs.sequence < rhs.sequence }
            return lhs.id.description < rhs.id.description
        }
        let durableIDs = Set(messages.map(\.id))
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
        rows.reserveCapacity(messages.count + missingInterruptedRuns.count + 1)
        for message in messages {
            rows.append(durableRow(message: message, input: input))
            for run in interruptedRunsByUserMessage[message.id] ?? [] {
                rows.append(interruptedWithoutOutputRow(
                    run: run,
                    sequence: message.sequence,
                    input: input
                ))
                projectedInterruptedRunIDs.insert(run.id)
            }
        }
        for run in missingInterruptedRuns where !projectedInterruptedRunIDs.contains(run.id) {
            rows.append(interruptedWithoutOutputRow(
                run: run,
                sequence: Int64(messages.count),
                input: input
            ))
        }
        var activeTailID: NovelMessageID?

        if let tail = input.transientTail,
           tail.branchID == input.branch.id,
           tail.sessionID == input.session.id,
           !durableIDs.contains(tail.messageID) {
            rows.append(transientRow(
                tail: tail,
                sequence: Int64(messages.count),
                input: input
            ))
            activeTailID = tail.messageID
        }

        return NovelSessionListModel(
            sessionID: input.session.id,
            rows: rows,
            activeTailID: activeTailID
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
        input: NovelSessionProjectionInput
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
            input: input
        )
    }

    static func durableRow(
        message: NovelSessionMessageRecord,
        input: NovelSessionProjectionInput
    ) -> NovelSessionRowModel {
        let candidate = presentedCandidate(for: message, input: input)
        let presentedCandidate: NovelSessionCandidatePresentation?
        let changeSummary: NovelSessionCommittedChangeSummary?
        if let candidate {
            presentedCandidate = candidatePresentation(for: candidate, input: input)
            changeSummary = committedChange(for: candidate, input: input)
        } else {
            presentedCandidate = nil
            changeSummary = nil
        }
        let actions = actions(
            for: message,
            candidate: candidate,
            input: input
        )
        let run = message.runID.flatMap { runID in
            input.runs.first(where: { $0.id == runID })
        }
        let runStatus = run?.status
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
            candidate: presentedCandidate,
            committedChange: changeSummary,
            actions: actions
        )
        return NovelSessionRowModel(
            id: message.id,
            sequence: message.sequence,
            role: message.role,
            mode: message.mode,
            kind: message.kind,
            content: presentedContent,
            createdAt: message.createdAt,
            runID: message.runID,
            runStatus: runStatus,
            candidate: presentedCandidate,
            committedChange: changeSummary,
            transientPhase: nil,
            actions: actions,
            digest: digest
        )
    }

    static func transientRow(
        tail: NovelSessionTransientTail,
        sequence: Int64,
        input: NovelSessionProjectionInput
    ) -> NovelSessionRowModel {
        let actions: [NovelSessionRowActionAvailability]
        switch tail.phase {
        case .persistenceBlocked:
            actions = [availability(
                .retryTerminalPersistence(tail.runID),
                blocker: baseMutationBlocker(
                    input: input,
                    includePending: false,
                    excludingRunID: tail.runID
                )
            )]
        case .interrupted:
            actions = [availability(
                .retryGeneration(tail.runID),
                blocker: transientRetryBlocker(tail: tail, input: input)
            )]
        case .failed(let failure):
            actions = [availability(
                .retryGeneration(tail.runID),
                blocker: failure.isRetryable
                    ? transientRetryBlocker(tail: tail, input: input)
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
        let presentedContent: String
        switch tail.phase {
        case .failed(let failure) where tail.content.isEmpty:
            presentedContent = NovelPresentation.failureMessage(failure)
        case .persistenceBlocked(let failure) where tail.content.isEmpty:
            presentedContent = NovelPresentation.failureMessage(failure)
        default:
            presentedContent = tail.content
        }
        return NovelSessionRowModel(
            id: tail.messageID,
            sequence: sequence,
            role: .assistant,
            mode: tail.mode,
            kind: presentedKind,
            content: presentedContent,
            createdAt: tail.startedAt,
            runID: tail.runID,
            runStatus: transientRunStatus(for: tail.phase),
            candidate: nil,
            committedChange: nil,
            transientPhase: tail.phase,
            actions: actions,
            digest: digest(
                messageID: tail.messageID,
                transientRevision: tail.renderRevision,
                transientPhase: tail.phase,
                runStatus: transientRunStatus(for: tail.phase),
                candidate: nil,
                committedChange: nil,
                actions: actions
            )
        )
    }

    static func presentedCandidate(
        for message: NovelSessionMessageRecord,
        input: NovelSessionProjectionInput
    ) -> NovelCandidateRecord? {
        let matches = input.candidates.filter {
            $0.branchID == input.branch.id &&
                $0.sessionID == input.session.id &&
                $0.sourceMessageID == message.id
        }
        guard !matches.isEmpty else { return nil }

        let pendingCandidateIDs = Set(input.pendingOperations.compactMap(\.candidateID))
        if let pending = matches.first(where: { pendingCandidateIDs.contains($0.id) }) {
            return pending
        }
        let transactionCandidateIDs = Set(input.polishTransactions.map(\.candidateID))
        if let transaction = matches.first(where: { transactionCandidateIDs.contains($0.id) }) {
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
        input: NovelSessionProjectionInput
    ) -> NovelSessionCandidatePresentation {
        NovelSessionCandidatePresentation(
            id: candidate.id,
            kind: candidate.kind,
            status: candidate.status,
            sourceChapterVersionID: candidate.sourceChapterVersionID,
            pendingStatus: input.pendingOperations.first(where: {
                $0.candidateID == candidate.id && $0.branchID == input.branch.id
            })?.status,
            polishTransactionStatus: input.polishTransactions.first(where: {
                $0.candidateID == candidate.id && $0.branchID == input.branch.id
            })?.status
        )
    }

    static func committedChange(
        for candidate: NovelCandidateRecord,
        input: NovelSessionProjectionInput
    ) -> NovelSessionCommittedChangeSummary? {
        guard let checkpointID = candidate.collectedCheckpointID,
              let checkpoint = input.checkpoints.first(where: {
                  $0.id == checkpointID && $0.sourceCandidateID == candidate.id
              }),
              let state = input.stateSnapshots.first(where: {
                  $0.id == checkpoint.stateSnapshotID
              }) else {
            return nil
        }
        let parentEventIDs: Set<NovelEventID>
        if let parentID = checkpoint.parentCheckpointID,
           let parent = input.checkpoints.first(where: { $0.id == parentID }),
           let parentState = input.stateSnapshots.first(where: {
               $0.id == parent.stateSnapshotID
           }) {
            parentEventIDs = Set(parentState.eventIDs)
        } else {
            parentEventIDs = []
        }
        let eventByID = Dictionary(
            input.events.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let eventSummaries = state.eventIDs.compactMap { id -> String? in
            guard !parentEventIDs.contains(id) else { return nil }
            return eventByID[id]?.summary
        }
        return NovelSessionCommittedChangeSummary(
            checkpointID: checkpoint.id,
            stateSnapshotID: state.id,
            stateSummary: state.summary,
            eventSummaries: eventSummaries
        )
    }

    static func actions(
        for message: NovelSessionMessageRecord,
        candidate: NovelCandidateRecord?,
        input: NovelSessionProjectionInput
    ) -> [NovelSessionRowActionAvailability] {
        guard message.role == .assistant else { return [] }
        if let candidate {
            return candidateActions(candidate, input: input)
        }
        if let runID = message.runID,
           input.runs.first(where: { $0.id == runID })?.kind == .quickStart,
           let proposal = input.settingProposals.first(where: { proposal in
               guard !proposal.isResolved else { return false }
               guard case .some(.quickStart(let originRunID, _)) = proposal.origin else {
                   return false
               }
               return originRunID == runID
           }) {
            return [availability(
                .viewSettingProposals(NovelSettingProposalRoute(kind: proposal.suggestedMaterialKind)),
                blocker: nil
            )]
        }
        guard message.kind == .interruptedDraft || message.kind == .error,
              let runID = message.runID,
              let run = input.runs.first(where: { $0.id == runID }) else {
            return []
        }
        if run.status == .failed, run.terminalFailure?.isRetryable != true {
            return [availability(.retryGeneration(runID), blocker: .failureNotRetryable)]
        }
        return [availability(
            .retryGeneration(runID),
            blocker: retryBlocker(for: run, input: input)
        )]
    }

    static func candidateActions(
        _ candidate: NovelCandidateRecord,
        input: NovelSessionProjectionInput
    ) -> [NovelSessionRowActionAvailability] {
        if let pending = input.pendingOperations.first(where: {
            $0.branchID == input.branch.id && $0.candidateID == candidate.id
        }) {
            return [availability(
                .retryPending(pending.id),
                blocker: baseMutationBlocker(
                    input: input,
                    includePending: true,
                    excludingPendingID: pending.id
                )
            )]
        }

        if let transaction = input.polishTransactions.first(where: {
            $0.branchID == input.branch.id && $0.candidateID == candidate.id
        }) {
            switch transaction.status {
            case .pending:
                let blocker = baseMutationBlocker(
                    input: input,
                    includePending: true,
                    excludingPolishTransactionID: transaction.id
                ) ?? staleBlocker(candidate, input: input)
                return [
                    availability(.retryPolish(transaction.id), blocker: blocker),
                    availability(.abandonPolish(transaction.id), blocker: blocker)
                ]
            case .retryable:
                let blocker = baseMutationBlocker(
                    input: input,
                    includePending: true,
                    excludingPolishTransactionID: transaction.id
                ) ??
                    staleBlocker(candidate, input: input)
                return [
                    availability(.retryPolish(transaction.id), blocker: blocker),
                    availability(.abandonPolish(transaction.id), blocker: blocker)
                ]
            case .blocked:
                return [availability(
                    .abandonPolish(transaction.id),
                    blocker: baseMutationBlocker(
                        input: input,
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
                        input: input
                    )
                )]
            case .completed, .abandoned:
                break
            }
        }

        switch candidate.status {
        case .available:
            let blocker = candidateMutationBlocker(candidate, input: input)
            switch candidate.kind {
            case .prose:
                return [availability(.collectProse(candidate.id), blocker: blocker)]
            case .polish:
                return [availability(.adoptPolish(candidate.id), blocker: blocker)]
            }
        case .collected, .adopted:
            var result: [NovelSessionRowActionAvailability] = []
            if candidate.collectedCheckpointID == input.branch.headCheckpointID {
                result.append(availability(
                    .undoCommittedChange(
                        checkpointID: input.branch.headCheckpointID,
                        kind: candidate.kind
                    ),
                    blocker: undoBlocker(input: input)
                ))
            }
            if let checkpointID = candidate.collectedCheckpointID {
                result.append(availability(
                    .forkFromCheckpoint(checkpointID),
                    blocker: forkBlocker(input: input)
                ))
            }
            if candidate.kind == .prose,
               candidate.status == .collected,
               input.branch.headCheckpointID == candidate.baseCheckpointID {
                result.append(availability(
                    .cloneCollectedProse(candidate.id),
                    blocker: candidateMutationBlocker(candidate, input: input, requiresCurrentBase: false)
                ))
            }
            return result
        case .interrupted, .superseded, .inheritedReadOnly:
            return []
        }
    }

    static func retryBlocker(
        for run: NovelActiveRunRecord,
        input: NovelSessionProjectionInput,
        excludingRunID: NovelRunID? = nil
    ) -> NovelSessionActionBlocker? {
        if let blocker = baseMutationBlocker(
            input: input,
            includePending: false,
            excludingRunID: excludingRunID
        ) {
            return blocker
        }
        if run.kind == .prose || run.kind == .polish {
            if run.kind == .polish, input.branch.syncStatus == .needsSync {
                return .branchNeedsSync
            }
            if input.pendingOperations.contains(where: { $0.branchID == input.branch.id }) {
                return .pendingOperation
            }
            if input.branch.headCheckpointID != run.baseCheckpointID ||
                input.branch.headRevision != run.baseHeadRevision {
                return .staleCandidate
            }
            if run.kind == .polish {
                guard let sourceVersionID = run.sourceChapterVersionID,
                      input.branch.workingChapterSelections.contains(where: {
                          $0.versionID == sourceVersionID
                      }) else {
                    return .sourceChapterChanged
                }
            }
        }
        return nil
    }

    static func transientRetryBlocker(
        tail: NovelSessionTransientTail,
        input: NovelSessionProjectionInput
    ) -> NovelSessionActionBlocker? {
        if let run = input.runs.first(where: { $0.id == tail.runID }) {
            return retryBlocker(for: run, input: input, excludingRunID: tail.runID)
        }
        if let blocker = baseMutationBlocker(
            input: input,
            includePending: false,
            excludingRunID: tail.runID
        ) {
            return blocker
        }
        if tail.kind == .proseCandidate || tail.kind == .polishCandidate {
            if tail.kind == .polishCandidate, input.branch.syncStatus == .needsSync {
                return .branchNeedsSync
            }
            if input.pendingOperations.contains(where: { $0.branchID == input.branch.id }) {
                return .pendingOperation
            }
        }
        return nil
    }

    static func candidateMutationBlocker(
        _ candidate: NovelCandidateRecord,
        input: NovelSessionProjectionInput,
        requiresCurrentBase: Bool = true
    ) -> NovelSessionActionBlocker? {
        if let blocker = baseMutationBlocker(input: input, includePending: true) {
            return blocker
        }
        if candidate.kind == .polish, input.branch.syncStatus == .needsSync {
            return .branchNeedsSync
        }
        if requiresCurrentBase,
           (candidate.baseCheckpointID != input.branch.headCheckpointID ||
            candidate.baseHeadRevision != input.branch.headRevision) {
            return .staleCandidate
        }
        if candidate.kind == .polish,
           let sourceID = candidate.sourceChapterVersionID,
           !input.branch.workingChapterSelections.contains(where: { $0.versionID == sourceID }) {
            return .sourceChapterChanged
        }
        return nil
    }

    static func baseMutationBlocker(
        input: NovelSessionProjectionInput,
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
            input.runs.contains(where: {
            $0.branchID == input.branch.id &&
                $0.status == .running &&
                $0.id != excludingRunID
        }) {
            return .generationRunning
        }
        if includePending,
           (input.pendingOperations.contains(where: {
               $0.branchID == input.branch.id && $0.id != excludingPendingID
           }) ||
            input.polishTransactions.contains(where: {
                $0.branchID == input.branch.id &&
                    $0.id != excludingPolishTransactionID &&
                    ($0.status == .pending || $0.status == .retryable || $0.status == .blocked)
            })) {
            return .pendingOperation
        }
        return nil
    }

    static func manualRewriteBlocker(
        sourceChapterVersionID: NovelChapterVersionID,
        input: NovelSessionProjectionInput
    ) -> NovelSessionActionBlocker? {
        if let blocker = baseMutationBlocker(input: input, includePending: true) {
            return blocker
        }
        guard input.branch.workingChapterSelections.contains(where: {
            $0.versionID == sourceChapterVersionID
        }) else {
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

    static func forkBlocker(
        input: NovelSessionProjectionInput
    ) -> NovelSessionActionBlocker? {
        baseMutationBlocker(input: input, includePending: false)
    }

    static func undoBlocker(
        input: NovelSessionProjectionInput
    ) -> NovelSessionActionBlocker? {
        if let blocker = baseMutationBlocker(input: input, includePending: true) {
            return blocker
        }
        return input.branch.syncStatus == .needsSync ? .branchNeedsSync : nil
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
        candidate: NovelSessionCandidatePresentation?,
        committedChange: NovelSessionCommittedChangeSummary?,
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
            "\($0.checkpointID):\($0.stateSnapshotID):\($0.eventSummaries.count)"
        } ?? "none"
        let actionToken = actions.map(actionToken).joined(separator: "|")
        return NovelSessionRowDigest(
            layout: "\(contentToken);\(runStatus?.rawValue ?? "-");\(candidateToken);" +
                "\(committedToken);\(actionToken)",
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
        event: NovelSessionBottomFollowEvent
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

        case .streamStarted, .streamDelta:
            switch next.mode {
            case .followingBottom:
                commands.append(.followBottom(animated: false))
            case .settlingTerminal:
                next.mode = .followingBottom
                commands.append(.followBottom(animated: false))
            case .awaitingInitialRows, .browsingHistory:
                break
            }

        case .userDragBegan(let isAtBottom):
            next.mode = .browsingHistory
            setBottomButton(!isAtBottom, state: &next, commands: &commands)

        case .userDragEnded(let isAtBottom):
            if isAtBottom {
                next.mode = .followingBottom
                setBottomButton(false, state: &next, commands: &commands)
            } else {
                next.mode = .browsingHistory
                setBottomButton(true, state: &next, commands: &commands)
            }

        case .viewportChanged(let isAtBottom):
            switch next.mode {
            case .followingBottom, .settlingTerminal:
                if !isAtBottom {
                    commands.append(.followBottom(animated: false))
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
            switch next.mode {
            case .followingBottom, .settlingTerminal:
                next.nextSettleToken &+= 1
                let token = next.nextSettleToken
                next.mode = .settlingTerminal(token: token)
                commands.append(.followBottom(animated: false))
                commands.append(.scheduleTerminalQuietSettle(
                    token: token,
                    delay: terminalQuietDelay
                ))
            case .awaitingInitialRows, .browsingHistory:
                break
            }

        case .terminalQuietElapsed(let token):
            guard next.mode == .settlingTerminal(token: token) else { break }
            next.mode = .followingBottom
            commands.append(.followBottom(animated: false))
        }

        return NovelSessionBottomFollowTransition(state: next, commands: commands)
    }
}

private extension NovelSessionBottomFollowPolicy {
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
