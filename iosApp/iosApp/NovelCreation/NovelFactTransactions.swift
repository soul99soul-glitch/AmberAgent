import Foundation

struct NovelManualRebuildChapter: Codable, Equatable, Sendable {
    let chapterID: NovelChapterID
    let versionID: NovelChapterVersionID
    let title: String
    let content: String
    let factCompatibilityID: UUID
}

struct NovelCollectionExtractionInput: Equatable, Sendable {
    let structuredRunID: NovelRunID
    let pending: NovelPendingOperationRecord
    let baseCheckpoint: NovelBranchCheckpointRecord
    let baseStateSnapshot: NovelStateSnapshotRecord
    let baseContext: String
    let manuscript: String
}

struct NovelManualRebuildInput: Equatable, Sendable {
    let structuredRunID: NovelRunID
    let pending: NovelPendingOperationRecord
    let rebuildBaseCheckpoint: NovelBranchCheckpointRecord
    let baseStateSnapshot: NovelStateSnapshotRecord
    let chapters: [NovelManualRebuildChapter]
    let sessionCursor: NovelSessionCursor
    let baseContext: String
    let manuscript: String
}

struct NovelFactTransactionReceiptArtifacts: Equatable, Sendable {
    let injectionReceipt: NovelInjectionReceiptRecord
    let generationReceipt: NovelGenerationReceiptRecord
}

typealias NovelPendingFactTransactionResult = (document: NovelProjectDocumentV1, pending: NovelPendingOperationRecord)
typealias NovelFactTransactionResult = (document: NovelProjectDocumentV1, outcome: NovelOutcome)

/// Manual-sync may finish without a model call when the working manuscript is
/// already fully described by a compatible rebuild base (e.g. trailing chapter
/// delete only).
enum NovelManualSyncPreparation: Sendable {
    case needsModel(document: NovelProjectDocumentV1, pending: NovelPendingOperationRecord)
    case completed(document: NovelProjectDocumentV1, outcome: NovelOutcome)

    /// Test / call-site convenience: document after preparation.
    var document: NovelProjectDocumentV1 {
        switch self {
        case .needsModel(let document, _), .completed(let document, _):
            document
        }
    }

    /// Pending only when a model rebuild is still required.
    var pending: NovelPendingOperationRecord? {
        if case .needsModel(_, let pending) = self { return pending }
        return nil
    }
}

enum NovelFactTransactionReducer {
    static func replayOutcome(
        context: NovelMutationContext,
        kind: NovelOperationKind,
        payloadSHA256: String,
        in document: NovelProjectDocumentV1
    ) throws -> NovelOutcome? {
        try NovelReducer.replayOutcome(
            context: context,
            kind: kind,
            payloadSHA256: payloadSHA256,
            in: document
        )
    }

    static func prepareCollection(
        _ command: NovelCollectCandidateCommand,
        payloadSHA256: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelPendingFactTransactionResult {
        try requireProject(command.projectID, in: document)
        try requirePayloadHash(payloadSHA256)
        if let existing = try matchingPending(
            id: command.pendingID,
            operationID: command.context.operationID,
            kind: .collection,
            payloadSHA256: payloadSHA256,
            in: document
        ) {
            return (document, existing)
        }
        guard let branch = document.branches.first(where: { $0.id == command.branchID }),
              branch.syncStatus == .synchronized else {
            throw NovelError.invalidInput("The working manuscript must be synchronized before starting legacy collection recovery.")
        }
        let pending = try makeCollectionPending(
            command,
            payloadSHA256: payloadSHA256,
            in: document,
            now: now
        )
        var next = document
        next.pendingOperations.append(pending)
        advanceProjectRevision(in: &next, now: now)
        try validateTransition(from: document, to: next)
        return (next, pending)
    }

    static func recoverPendingCollectionWithoutStateSync(
        _ command: NovelRetryPendingCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelFactTransactionResult {
        let pending = try requirePending(command.pendingID, kind: .collection, in: document)
        try validateRetry(command, for: pending, in: document)
        return try commitPreparedCollection(
            pending,
            retryCommand: command,
            in: document,
            now: now
        )
    }

    static func commitCollectionWithoutStateSync(
        _ command: NovelCollectCandidateCommand,
        payloadSHA256: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelFactTransactionResult {
        try requireProject(command.projectID, in: document)
        try requirePayloadHash(payloadSHA256)
        let pending = try makeCollectionPending(
            command,
            payloadSHA256: payloadSHA256,
            in: document,
            now: now
        )
        return try commitPreparedCollection(
            pending,
            retryCommand: nil,
            in: document,
            now: now
        )
    }

    /// Contract v1.1 D-B: the collected chapter and the plot module it
    /// updates land in ONE atomic commit. The relinked plot snapshot is
    /// computed by the caller before this transaction and carried by the
    /// single `.collection` checkpoint — no follow-up pointer commit.
    static func commitCollectionWithPlot(
        _ command: NovelCollectCandidateCommand,
        payloadSHA256: String,
        moduleText: String?,
        summaryOverride: String?,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelFactTransactionResult {
        try requireProject(command.projectID, in: document)
        try requirePayloadHash(payloadSHA256)
        guard let branch = document.branches.first(where: { $0.id == command.branchID }) else {
            throw NovelError.branchNotFound(command.branchID)
        }
        let chapterID: NovelChapterID
        switch command.target {
        case .createNextChapter(let id, _):
            chapterID = id
        case .replaceChapter(let id), .appendToChapter(let id):
            chapterID = id
        }
        let pending = try makeCollectionPending(
            command,
            payloadSHA256: payloadSHA256,
            in: document,
            now: now
        )
        let plotUpdate = CollectionPlotUpdate(
            chapterID: chapterID,
            snapshotID: command.stateSnapshotID,
            moduleText: moduleText,
            summaryOverride: summaryOverride,
            markLaterStale: !NovelWorkspaceLedger.isFastForwardCollect(
                command.target,
                branch: branch
            )
        )
        return try commitPreparedCollection(
            pending,
            retryCommand: nil,
            plotUpdate: plotUpdate,
            in: document,
            now: now
        )
    }

    struct CollectionPlotUpdate {
        let chapterID: NovelChapterID
        let snapshotID: NovelStateSnapshotID
        let moduleText: String?
        let summaryOverride: String?
        let markLaterStale: Bool
    }

    private static func commitPreparedCollection(
        _ pending: NovelPendingOperationRecord,
        retryCommand: NovelRetryPendingCommand?,
        plotUpdate: CollectionPlotUpdate? = nil,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> NovelFactTransactionResult {
        var next = document
        let branchIndex = try requireBranch(pending.branchID, in: next)
        let branch = next.branches[branchIndex]
        try requirePendingGuards(
            pending,
            branch: branch,
            projectID: document.project.id
        )
        guard let candidateIndex = next.candidates.firstIndex(where: {
            $0.id == pending.candidateID
        }), let proposedVersion = pending.proposedChapterVersion,
        let checkpointID = pending.proposedCheckpointID,
        let target = pending.collectionTarget,
        let sessionCursor = pending.sessionCursor else {
            throw NovelError.invalidInput("The prepared collection is incomplete.")
        }
        let candidate = next.candidates[candidateIndex]
        guard let sessionIndex = next.sessions.firstIndex(where: {
            $0.id == candidate.sessionID && $0.branchID == branch.id
        }) else {
            throw NovelError.invalidInput("The prepared collection lost its owning Session.")
        }
        let baseCheckpoint = try checkpoint(pending.baseCheckpointID, in: next)
        var chapterSelections = branch.workingChapterSelections
        var chapterToAdd: NovelChapterRecord?
        switch target {
        case .appendToChapter(let chapterID), .replaceChapter(let chapterID):
            guard let index = chapterSelections.firstIndex(where: { $0.chapterID == chapterID }),
                  proposedVersion.chapterID == chapterID else {
                throw NovelError.invalidInput("The collection append target is stale.")
            }
            chapterSelections[index] = NovelChapterSelection(
                chapterID: chapterID,
                versionID: proposedVersion.id
            )
        case .createNextChapter(let chapterID, _):
            guard proposedVersion.chapterID == chapterID,
                  !next.chapters.contains(where: { $0.id == chapterID }) else {
                throw NovelError.immutableRecordConflict("chapter \(chapterID)")
            }
            chapterToAdd = NovelChapterRecord(id: chapterID, createdAt: now)
            chapterSelections.append(NovelChapterSelection(
                chapterID: chapterID,
                versionID: proposedVersion.id
            ))
        }

        let finalRevision = document.project.revision + 1
        let outcome = NovelOutcome.candidateCollected(
            projectID: document.project.id,
            branchID: branch.id,
            candidateID: candidate.id,
            checkpointID: checkpointID,
            chapterVersionID: proposedVersion.id,
            revision: finalRevision
        )

        if let chapterToAdd { next.chapters.append(chapterToAdd) }
        next.chapterVersions.append(proposedVersion)

        var snapshotID = baseCheckpoint.stateSnapshotID
        if let plotUpdate {
            let plotSnapshot = try NovelWorkspaceLedger.updatedPlotSnapshot(
                id: plotUpdate.snapshotID,
                replacing: baseCheckpoint.stateSnapshotID,
                workingSelections: chapterSelections,
                updatedChapterID: plotUpdate.chapterID,
                updatedTitle: proposedVersion.title,
                updatedContent: proposedVersion.content,
                moduleText: plotUpdate.moduleText,
                summaryOverride: plotUpdate.summaryOverride,
                markLaterStale: plotUpdate.markLaterStale,
                in: next,
                now: now
            )
            next.stateSnapshots.append(plotSnapshot)
            snapshotID = plotSnapshot.id
        }

        let checkpoint = NovelBranchCheckpointRecord(
            id: checkpointID,
            kind: .collection,
            createdOnBranchID: branch.id,
            parentCheckpointID: pending.baseCheckpointID,
            chapterSelections: chapterSelections,
            stateSnapshotID: snapshotID,
            sessionCursor: sessionCursor,
            branchOverrideRevisionIDs: branch.overrideRevisionIDs,
            sourceCandidateID: candidate.id,
            baseHeadRevision: pending.baseHeadRevision,
            operationID: pending.operationID,
            createdAt: now
        )

        try appendLedger(
            pending: pending,
            outcome: outcome,
            retryCommand: retryCommand,
            appliedRevision: finalRevision,
            to: &next,
            now: now
        )
        let currentOperationIDs = Set([
            pending.operationID,
            retryCommand?.context.operationID
        ].compactMap { $0 })
        for attempt in next.factAttempts where
            attempt.pendingID == pending.id &&
            !currentOperationIDs.contains(attempt.attemptOperationID) &&
            !next.appliedOperations.contains(where: {
                $0.operationID == attempt.attemptOperationID
            }) {
            next.appliedOperations.append(NovelAppliedOperationRecord(
                operationID: attempt.attemptOperationID,
                kind: .retryPending,
                payloadSHA256: attempt.attemptPayloadSHA256,
                outcome: outcome,
                appliedProjectRevision: finalRevision,
                appliedAt: now
            ))
        }
        next.candidates[candidateIndex].status = .collected
        next.candidates[candidateIndex].collectedCheckpointID = checkpoint.id
        next.sessions[sessionIndex].revision += 1
        try NovelReducer.appendCheckpoint(
            checkpoint,
            to: &next,
            expectedHeadRevision: pending.baseHeadRevision,
            now: now
        )
        next.branches[branchIndex].syncStatus = plotUpdate == nil ? .needsSync : .synchronized
        next.pendingOperations.removeAll { $0.id == pending.id }
        advanceProjectRevision(in: &next, now: now)
        guard next.project.revision == finalRevision else {
            throw NovelError.invalidInput("Collection revision accounting failed.")
        }
        try validateTransition(from: document, to: next)
        return (next, outcome)
    }

    private static func makeCollectionPending(
        _ command: NovelCollectCandidateCommand,
        payloadSHA256: String,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> NovelPendingOperationRecord {
        try requireUnusedOperation(command.context.operationID, in: document)
        let branchIndex = try requireBranch(command.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requireContext(command.context, branch: branch, document: document)
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        guard branch.activeRunID == nil else {
            throw NovelError.projectBusy(command.projectID)
        }
        guard !document.pendingOperations.contains(where: { $0.branchID == branch.id }) else {
            throw NovelError.projectBusy(command.projectID)
        }
        // Contract v1.1 D-D: collecting NEW forward content is gated while
        // later chapters' plot modules are unresolved after a middle-chapter
        // edit. Replacing an already-stale chapter IS one of the sanctioned
        // resolutions, so it stays open.
        if NovelWorkspaceLedger.hasUnresolvedChapterPlots(
            branchID: command.branchID,
            in: document
        ) {
            let replacesStaleChapter: Bool
            if case .replaceChapter(let chapterID) = command.target {
                replacesStaleChapter = document.stateSnapshots
                    .first { $0.id == branch.currentStateSnapshotID }?
                    .chapterPlots
                    .contains { $0.chapterID == chapterID && $0.stale } == true
            } else {
                replacesStaleChapter = false
            }
            guard replacesStaleChapter else {
                throw NovelError.invalidInput(
                    NovelWorkspaceLedger.unresolvedPlotGateMessage
                )
            }
        }
        guard let candidate = document.candidates.first(where: {
            $0.id == command.candidateID
        }) else {
            throw NovelError.invalidInput("The prose candidate was not found.")
        }
        guard candidate.branchID == branch.id,
              candidate.sessionID == branch.sessionID,
              candidate.kind == .prose,
              candidate.status == .available || candidate.status == .interrupted,
              candidate.collectedCheckpointID == nil else {
            throw NovelError.invalidInput("The candidate is not collectable prose for this branch.")
        }
        if let boundDigest = candidate.chapterPlanDigest {
            let currentDigest = document.confirmedChapterPlan(for: branch.id)?.contentDigest
            guard currentDigest == boundDigest else {
                throw NovelError.invalidInput(
                    "The candidate no longer matches the confirmed chapter plan."
                )
            }
        } else if command.source == .systemAutoCollect {
            throw NovelError.invalidInput(
                "Automatic collection requires a candidate bound to a confirmed chapter plan."
            )
        }
        guard NovelCandidateSemantics.collectionBaseMatches(
            candidate,
            targetCheckpointID: branch.headCheckpointID,
            targetHeadRevision: branch.headRevision,
            in: document
        ) else {
            throw NovelError.staleBranchHeadRevision(
                expected: candidate.baseHeadRevision,
                actual: branch.headRevision
            )
        }
        guard let rootCandidateID = NovelCandidateSemantics.rootCandidateID(
            for: candidate,
            in: document.candidates
        ),
              let session = document.sessions.first(where: { $0.id == branch.sessionID }),
              let sourceMessage = session.messages.first(where: {
                  $0.id == candidate.sourceMessageID && $0.candidateID == rootCandidateID
              }) else {
            throw NovelError.invalidInput("The candidate source message is unavailable.")
        }

        let selectedText = try NovelParagraphParser.selectedText(
            for: command.selection,
            in: candidate.content
        )
        try requireReservedIDs(
            chapterVersionID: command.proposedChapterVersionID,
            checkpointID: command.checkpointID,
            stateSnapshotID: command.stateSnapshotID,
            pendingID: command.pendingID,
            in: document
        )
        try requireNewFactCompatibilityID(command.factCompatibilityID, in: document)

        let proposedVersion: NovelChapterVersionRecord
        switch command.target {
        case .appendToChapter(let chapterID):
            guard let selection = branch.workingChapterSelections.first(where: {
                $0.chapterID == chapterID
            }), let baseVersion = document.chapterVersions.first(where: {
                $0.id == selection.versionID && $0.chapterID == chapterID
            }) else {
                throw NovelError.invalidInput("The append target is not in the current manuscript.")
            }
            proposedVersion = NovelChapterVersionRecord(
                id: command.proposedChapterVersionID,
                chapterID: chapterID,
                kind: .collected,
                title: baseVersion.title,
                content: NovelChapterText.appending(selectedText, to: baseVersion.content),
                factCompatibilityID: command.factCompatibilityID,
                sourceChapterVersionID: baseVersion.id,
                sourceCandidateID: candidate.id,
                createdAt: now,
                operationID: command.context.operationID
            )
        case .replaceChapter(let chapterID):
            guard let selection = branch.workingChapterSelections.first(where: {
                $0.chapterID == chapterID
            }), let baseVersion = document.chapterVersions.first(where: {
                $0.id == selection.versionID && $0.chapterID == chapterID
            }) else {
                throw NovelError.invalidInput("The replace target is not in the current manuscript.")
            }
            // 替换只能落到**这个候选自己重写的那一章**。此前该约束只存在于
            // 收录面板,任何绕过 UI 的调用方都能拿一段无关正文整章覆盖。
            guard let sourceVersionID = candidate.sourceChapterVersionID,
                  document.chapterVersions.contains(where: {
                      $0.id == sourceVersionID && $0.chapterID == chapterID
                  }) else {
                throw NovelError.invalidInput("The candidate did not rewrite this chapter.")
            }
            proposedVersion = NovelChapterVersionRecord(
                id: command.proposedChapterVersionID,
                chapterID: chapterID,
                kind: .collected,
                title: baseVersion.title,
                content: selectedText,
                factCompatibilityID: command.factCompatibilityID,
                sourceChapterVersionID: baseVersion.id,
                sourceCandidateID: candidate.id,
                createdAt: now,
                operationID: command.context.operationID
            )
        case .createNextChapter(let chapterID, let title):
            let normalizedTitle = try normalizedRequired(title, field: "Chapter title")
            guard normalizedTitle == title else {
                throw NovelError.invalidInput("Chapter title must not contain surrounding whitespace.")
            }
            guard !document.chapters.contains(where: { $0.id == chapterID }),
                  !document.pendingOperations.contains(where: {
                      if case .createNextChapter(let pendingChapterID, _) = $0.collectionTarget {
                          return pendingChapterID == chapterID
                      }
                      return false
                  }) else {
                throw NovelError.immutableRecordConflict("chapter \(chapterID)")
            }
            proposedVersion = NovelChapterVersionRecord(
                id: command.proposedChapterVersionID,
                chapterID: chapterID,
                kind: .collected,
                title: title,
                content: selectedText,
                factCompatibilityID: command.factCompatibilityID,
                sourceChapterVersionID: nil,
                sourceCandidateID: candidate.id,
                createdAt: now,
                operationID: command.context.operationID
            )
        }

        return NovelPendingOperationRecord(
            id: command.pendingID,
            kind: .collection,
            status: .pending,
            branchID: branch.id,
            operationID: command.context.operationID,
            payloadSHA256: payloadSHA256,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            baseWorkingRevision: branch.workingRevision,
            candidateID: candidate.id,
            collectionTarget: command.target,
            selectedText: selectedText,
            proposedChapterVersion: proposedVersion,
            proposedCheckpointID: command.checkpointID,
            proposedStateSnapshotID: command.stateSnapshotID,
            rebuildBaseCheckpointID: nil,
            sessionCursor: .through(sequence: sourceMessage.sequence),
            createdAt: now,
            lastError: nil
        )
    }

    static func collectionExtractionInput(
        pendingID: NovelPendingOperationID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelCollectionExtractionInput {
        let pending = try requirePending(pendingID, kind: .collection, in: document)
        let baseCheckpoint = try checkpoint(pending.baseCheckpointID, in: document)
        let baseState = try stateSnapshot(baseCheckpoint.stateSnapshotID, in: document)
        return NovelCollectionExtractionInput(
            structuredRunID: NovelRunID(pending.id.rawValue),
            pending: pending,
            baseCheckpoint: baseCheckpoint,
            baseStateSnapshot: baseState,
            baseContext: try stateContext(baseState, branchID: pending.branchID, in: document),
            manuscript: pending.selectedText
        )
    }

    static func finalizeCollection(
        pendingID: NovelPendingOperationID,
        delta: NovelStateDeltaV1,
        retryCommand: NovelRetryPendingCommand? = nil,
        artifacts: NovelFactTransactionReceiptArtifacts,
        in document: NovelProjectDocumentV1,
        now: Date = Date(),
        acceptEmptyFacts: Bool = false
    ) throws -> NovelFactTransactionResult {
        var validatedDelta = try validate(delta)
        let pending = try requirePending(pendingID, kind: .collection, in: document)
        try validateRetry(retryCommand, for: pending, in: document)
        let branchIndex = try requireBranch(pending.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requirePendingGuards(
            pending,
            branch: branch,
            projectID: document.project.id
        )
        guard branch.syncStatus == .synchronized else {
            throw NovelError.invalidInput("Manual edits must be synchronized before collecting prose.")
        }
        guard let candidateIndex = document.candidates.firstIndex(where: {
            $0.id == pending.candidateID
        }) else {
            throw NovelError.invalidInput("The pending collection lost its candidate.")
        }
        let candidate = document.candidates[candidateIndex]
        guard let sessionIndex = document.sessions.firstIndex(where: {
            $0.id == candidate.sessionID && $0.branchID == branch.id
        }) else {
            throw NovelError.invalidInput("The pending collection lost its owning Session.")
        }
        guard candidate.status == .available || candidate.status == .interrupted,
              candidate.collectedCheckpointID == nil,
              NovelCandidateSemantics.collectionBaseMatches(
                  candidate,
                  targetCheckpointID: pending.baseCheckpointID,
                  targetHeadRevision: pending.baseHeadRevision,
                  in: document
              ) else {
            throw NovelError.invalidInput("The pending collection candidate is stale.")
        }
        guard let proposedVersion = pending.proposedChapterVersion,
              let checkpointID = pending.proposedCheckpointID,
              let snapshotID = pending.proposedStateSnapshotID,
              let target = pending.collectionTarget,
              let sessionCursor = pending.sessionCursor else {
            throw NovelError.invalidInput("The pending collection is incomplete.")
        }
        let baseCheckpoint = try checkpoint(pending.baseCheckpointID, in: document)
        let baseState = try stateSnapshot(baseCheckpoint.stateSnapshotID, in: document)
        validatedDelta = try sanitizedCollectionDelta(
            in: validatedDelta,
            evidenceSource: pending.selectedText,
            branch: branch,
            baseState: baseState,
            document: document,
            acceptEmptyFacts: acceptEmptyFacts
        )
        try validateStateFacts(
            validatedDelta,
            evidenceSource: pending.selectedText,
            branch: branch,
            baseState: baseState,
            in: document
        )
        let newEvents = try storyEvents(
            storyEventDrafts(
                validatedDelta,
                evidenceSource: pending.selectedText
            ),
            namespace: pending.operationID,
            baseState: baseState,
            in: document,
            now: now
        )
        let proposals = try settingProposals(
            validatedDelta.settingProposals,
            namespace: pending.operationID,
            branchID: branch.id,
            in: document,
            now: now
        )
        let newSnapshot = NovelStateSnapshotRecord(
            id: snapshotID,
            eventIDs: baseState.eventIDs + newEvents.map(\.id),
            summary: validatedDelta.stateSummary,
            branchOutline: validatedDelta.branchOutlinePatch ?? baseState.branchOutline,
            unresolvedEntityNames: validatedDelta.unresolvedEntityNames,
            createdAt: now,
            settingProposalIDs: baseState.settingProposalIDs + proposals.map(\.id),
            characterIdentityClarifications: baseState.characterIdentityClarifications,
            recentWrittenHighlights: NovelStateSnapshotRecord.mergedHighlights(
                prior: baseState.recentWrittenHighlights,
                newEventSummaries: newEvents.map(\.summary)
            )
        )

        var chapterSelections = branch.workingChapterSelections
        var chapterToAdd: NovelChapterRecord?
        switch target {
        case .appendToChapter(let chapterID), .replaceChapter(let chapterID):
            guard let index = chapterSelections.firstIndex(where: { $0.chapterID == chapterID }),
                  proposedVersion.chapterID == chapterID else {
                throw NovelError.invalidInput("The pending append target is stale.")
            }
            chapterSelections[index] = NovelChapterSelection(
                chapterID: chapterID,
                versionID: proposedVersion.id
            )
        case .createNextChapter(let chapterID, _):
            guard proposedVersion.chapterID == chapterID,
                  !document.chapters.contains(where: { $0.id == chapterID }) else {
                throw NovelError.immutableRecordConflict("chapter \(chapterID)")
            }
            chapterToAdd = NovelChapterRecord(id: chapterID, createdAt: now)
            chapterSelections.append(NovelChapterSelection(
                chapterID: chapterID,
                versionID: proposedVersion.id
            ))
        }

        let finalRevision = document.project.revision + 1
        let outcome = NovelOutcome.candidateCollected(
            projectID: document.project.id,
            branchID: branch.id,
            candidateID: candidate.id,
            checkpointID: checkpointID,
            chapterVersionID: proposedVersion.id,
            revision: finalRevision
        )
        let checkpoint = NovelBranchCheckpointRecord(
            id: checkpointID,
            kind: .collection,
            createdOnBranchID: branch.id,
            parentCheckpointID: pending.baseCheckpointID,
            chapterSelections: chapterSelections,
            stateSnapshotID: newSnapshot.id,
            sessionCursor: sessionCursor,
            branchOverrideRevisionIDs: branch.overrideRevisionIDs,
            sourceCandidateID: candidate.id,
            baseHeadRevision: pending.baseHeadRevision,
            operationID: pending.operationID,
            createdAt: now
        )

        var next = document
        if let chapterToAdd { next.chapters.append(chapterToAdd) }
        next.chapterVersions.append(proposedVersion)
        next.events.append(contentsOf: newEvents)
        next.stateSnapshots.append(newSnapshot)
        next.settingProposals.append(contentsOf: proposals)
        try appendLedger(
            pending: pending,
            outcome: outcome,
            retryCommand: retryCommand,
            appliedRevision: finalRevision,
            to: &next,
            now: now
        )
        try appendFactReceipts(
            artifacts,
            pending: pending,
            retryCommand: retryCommand,
            kind: .stateDelta,
            to: &next
        )
        next.candidates[candidateIndex].status = .collected
        next.candidates[candidateIndex].collectedCheckpointID = checkpoint.id
        next.sessions[sessionIndex].revision += 1
        try NovelReducer.appendCheckpoint(
            checkpoint,
            to: &next,
            expectedHeadRevision: pending.baseHeadRevision,
            now: now
        )
        next.pendingOperations.removeAll { $0.id == pending.id }
        advanceProjectRevision(in: &next, now: now)
        guard next.project.revision == finalRevision else {
            throw NovelError.invalidInput("Collection revision accounting failed.")
        }
        try validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func saveManualEdit(
        _ command: NovelSaveManualEditCommand,
        payloadSHA256: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelFactTransactionResult {
        try requireProject(command.projectID, in: document)
        try requirePayloadHash(payloadSHA256)
        if let outcome = try replayOutcome(
            context: command.context,
            kind: .saveManualEdit,
            payloadSHA256: payloadSHA256,
            in: document
        ) {
            return (document, outcome)
        }
        try requireUnusedOperation(command.context.operationID, in: document)
        let branchIndex = try requireBranch(command.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requireContext(command.context, branch: branch, document: document)
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        // Discussion field-write tools run inside a live discussion run. Block only
        // non-discussion active runs (prose/polish/regenerate/…), not the discussion
        // agent itself — otherwise chapter-title tools self-lock.
        if let activeRunID = branch.activeRunID {
            let activeKind = document.activeRuns.first(where: { $0.id == activeRunID })?.kind
            guard activeKind == .discussion else {
                throw NovelError.projectBusy(command.projectID)
            }
        }
        guard branch.workingRevision == command.expectedWorkingRevision else {
            throw NovelError.invalidInput(
                "The working manuscript changed before this edit was saved."
            )
        }
        guard !document.pendingOperations.contains(where: { $0.branchID == branch.id }) else {
            throw NovelError.projectBusy(command.projectID)
        }
        guard let selectionIndex = branch.workingChapterSelections.firstIndex(where: {
            $0.chapterID == command.chapterID
        }), let sourceVersion = document.chapterVersions.first(where: {
            $0.id == branch.workingChapterSelections[selectionIndex].versionID &&
                $0.chapterID == command.chapterID
        }) else {
            throw NovelError.invalidInput("The edited chapter is not in the working manuscript.")
        }
        guard document.chapterVersions.allSatisfy({ $0.id != command.versionID }),
              document.pendingOperations.allSatisfy({
                  $0.proposedChapterVersion?.id != command.versionID
              }) else {
            throw NovelError.immutableRecordConflict("chapter version \(command.versionID)")
        }
        let title = try normalizedRequired(command.title, field: "Chapter title")
        let content = normalizeLineEndings(command.content)
        guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NovelError.invalidInput("Chapter content cannot be empty.")
        }
        guard title != sourceVersion.title || content != sourceVersion.content else {
            throw NovelError.invalidInput("The manual edit does not change the chapter.")
        }
        try requireNewFactCompatibilityID(command.factCompatibilityID, in: document)

        let version = NovelChapterVersionRecord(
            id: command.versionID,
            chapterID: command.chapterID,
            kind: .manualEdit,
            title: title,
            content: content,
            factCompatibilityID: command.factCompatibilityID,
            sourceChapterVersionID: sourceVersion.id,
            sourceCandidateID: nil,
            createdAt: now,
            operationID: command.context.operationID
        )
        let finalRevision = document.project.revision + 1
        let finalWorkingRevision = branch.workingRevision + 1
        let outcome = NovelOutcome.manualEditSaved(
            projectID: document.project.id,
            branchID: branch.id,
            chapterVersionID: version.id,
            workingRevision: finalWorkingRevision,
            revision: finalRevision
        )
        var next = document
        next.chapterVersions.append(version)
        next.branches[branchIndex].workingChapterSelections[selectionIndex] = NovelChapterSelection(
            chapterID: command.chapterID,
            versionID: version.id
        )
        next.branches[branchIndex].workingRevision = finalWorkingRevision
        next.branches[branchIndex].syncStatus = .needsSync
        next.branches[branchIndex].updatedAt = now
        next.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: command.context.operationID,
            kind: .saveManualEdit,
            payloadSHA256: payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: finalRevision,
            appliedAt: now
        ))
        advanceProjectRevision(in: &next, now: now)
        try validateTransition(from: document, to: next)
        return (next, outcome)
    }

    /// Contract v1.1 D-B: the manual edit and the plot module it triggers
    /// commit as ONE mutation (single revision step, single `.manualSync`
    /// checkpoint carrying both the new version and the relinked snapshot).
    /// `moduleText`/`summaryOverride` are precomputed by the lifecycle
    /// (model draft) before this transaction runs.
    static func saveManualEditWithPlot(
        _ command: NovelSaveManualEditCommand,
        payloadSHA256: String,
        moduleText: String?,
        summaryOverride: String?,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelFactTransactionResult {
        try requireProject(command.projectID, in: document)
        try requirePayloadHash(payloadSHA256)
        if let outcome = try replayOutcome(
            context: command.context,
            kind: .saveManualEdit,
            payloadSHA256: payloadSHA256,
            in: document
        ) {
            return (document, outcome)
        }
        try requireUnusedOperation(command.context.operationID, in: document)
        let branchIndex = try requireBranch(command.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requireContext(command.context, branch: branch, document: document)
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        // Discussion field-write tools run inside a live discussion run. Block only
        // non-discussion active runs (prose/polish/regenerate/…), not the discussion
        // agent itself — otherwise chapter-title tools self-lock.
        if let activeRunID = branch.activeRunID {
            let activeKind = document.activeRuns.first(where: { $0.id == activeRunID })?.kind
            guard activeKind == .discussion else {
                throw NovelError.projectBusy(command.projectID)
            }
        }
        guard branch.workingRevision == command.expectedWorkingRevision else {
            throw NovelError.invalidInput(
                "The working manuscript changed before this edit was saved."
            )
        }
        guard !document.pendingOperations.contains(where: { $0.branchID == branch.id }) else {
            throw NovelError.projectBusy(command.projectID)
        }
        guard let selectionIndex = branch.workingChapterSelections.firstIndex(where: {
            $0.chapterID == command.chapterID
        }), let sourceVersion = document.chapterVersions.first(where: {
            $0.id == branch.workingChapterSelections[selectionIndex].versionID &&
                $0.chapterID == command.chapterID
        }) else {
            throw NovelError.invalidInput("The edited chapter is not in the working manuscript.")
        }
        guard document.chapterVersions.allSatisfy({ $0.id != command.versionID }),
              document.pendingOperations.allSatisfy({
                  $0.proposedChapterVersion?.id != command.versionID
              }) else {
            throw NovelError.immutableRecordConflict("chapter version \(command.versionID)")
        }
        let title = try normalizedRequired(command.title, field: "Chapter title")
        let content = normalizeLineEndings(command.content)
        guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NovelError.invalidInput("Chapter content cannot be empty.")
        }
        guard title != sourceVersion.title || content != sourceVersion.content else {
            throw NovelError.invalidInput("The manual edit does not change the chapter.")
        }
        try requireNewFactCompatibilityID(command.factCompatibilityID, in: document)

        let version = NovelChapterVersionRecord(
            id: command.versionID,
            chapterID: command.chapterID,
            kind: .manualEdit,
            title: title,
            content: content,
            factCompatibilityID: command.factCompatibilityID,
            sourceChapterVersionID: sourceVersion.id,
            sourceCandidateID: nil,
            createdAt: now,
            operationID: command.context.operationID
        )
        var next = document
        next.chapterVersions.append(version)
        next.branches[branchIndex].workingChapterSelections[selectionIndex] = NovelChapterSelection(
            chapterID: command.chapterID,
            versionID: version.id
        )
        next.branches[branchIndex].workingRevision = branch.workingRevision + 1
        next.branches[branchIndex].updatedAt = now

        let plotSnapshot = try NovelWorkspaceLedger.updatedPlotSnapshot(
            id: NovelStateSnapshotID(),
            replacing: branch.currentStateSnapshotID,
            workingSelections: next.branches[branchIndex].workingChapterSelections,
            updatedChapterID: command.chapterID,
            updatedTitle: title,
            updatedContent: content,
            moduleText: moduleText,
            summaryOverride: summaryOverride,
            markLaterStale: !NovelWorkspaceLedger.isFastForward(
                branch: branch,
                chapterID: command.chapterID
            ),
            in: next,
            now: now
        )
        next.stateSnapshots.append(plotSnapshot)

        let finalRevision = document.project.revision + 1
        let outcome = NovelOutcome.manualEditSaved(
            projectID: document.project.id,
            branchID: branch.id,
            chapterVersionID: version.id,
            workingRevision: next.branches[branchIndex].workingRevision,
            revision: finalRevision
        )
        let session = next.sessions.first { $0.id == branch.sessionID }
        let cursor: NovelSessionCursor = session?.messages.last.map {
            .through(sequence: $0.sequence)
        } ?? .empty
        try NovelReducer.appendCheckpoint(
            NovelBranchCheckpointRecord(
                id: NovelCheckpointID(),
                kind: .manualSync,
                createdOnBranchID: branch.id,
                parentCheckpointID: branch.headCheckpointID,
                chapterSelections: next.branches[branchIndex].workingChapterSelections,
                stateSnapshotID: plotSnapshot.id,
                sessionCursor: cursor,
                branchOverrideRevisionIDs: branch.overrideRevisionIDs,
                sourceCandidateID: nil,
                baseHeadRevision: branch.headRevision,
                operationID: command.context.operationID,
                createdAt: now
            ),
            to: &next,
            expectedHeadRevision: branch.headRevision,
            advancesWorkingRevision: false,
            now: now
        )
        next.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: command.context.operationID,
            kind: .saveManualEdit,
            payloadSHA256: payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: finalRevision,
            appliedAt: now
        ))
        advanceProjectRevision(in: &next, now: now)
        try validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func prepareManualSync(
        _ command: NovelSyncManualEditsCommand,
        payloadSHA256: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelManualSyncPreparation {
        try requireProject(command.projectID, in: document)
        try requirePayloadHash(payloadSHA256)
        if let existing = try matchingPending(
            id: command.pendingID,
            operationID: command.context.operationID,
            kind: .manualSync,
            payloadSHA256: payloadSHA256,
            in: document
        ) {
            return .needsModel(document: document, pending: existing)
        }
        try requireUnusedOperation(command.context.operationID, in: document)
        let branchIndex = try requireBranch(command.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requireContext(command.context, branch: branch, document: document)
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        guard branch.activeRunID == nil else {
            throw NovelError.projectBusy(command.projectID)
        }
        guard branch.syncStatus == .needsSync else {
            throw NovelError.invalidInput("The branch has no manual edits to synchronize.")
        }
        guard branch.workingRevision == command.expectedWorkingRevision else {
            throw NovelError.invalidInput(
                "The working manuscript changed before synchronization started."
            )
        }
        guard !document.pendingOperations.contains(where: { $0.branchID == branch.id }) else {
            throw NovelError.projectBusy(command.projectID)
        }
        try requireReservedIDs(
            chapterVersionID: nil,
            checkpointID: command.checkpointID,
            stateSnapshotID: command.stateSnapshotID,
            pendingID: command.pendingID,
            in: document
        )
        let head = try checkpoint(branch.headCheckpointID, in: document)
        let workingSelections = branch.workingChapterSelections
        // Working may diverge from head by version edits AND by structural change
        // (deleteFromManuscript removes a chapter from working while head still lists it).
        // The old equal-chapterID-list guard made every post-delete sync fail instantly.
        try validateWorkingManuscriptEvolution(
            from: head.chapterSelections,
            to: workingSelections
        )
        let stateBase = try staleStateBaseCheckpoint(
            from: head,
            in: document
        )
        let affectedIndex = firstChangedChapterIndex(
            from: stateBase.chapterSelections,
            to: workingSelections
        )
        guard let affectedIndex else {
            // needsSync but working already matches the state base: nothing to rebuild.
            throw NovelError.invalidInput(
                "当前没有需要同步的正文改动。若仍提示待同步，请点「重新载入」后再试。"
            )
        }
        // Pivot chapter for rebuild-base search:
        // - version edit / insertion: index falls inside working
        // - trailing deletion: index equals working.count (extra chapters only on base)
        let pivotChapterID: NovelChapterID
        if affectedIndex < workingSelections.count {
            pivotChapterID = workingSelections[affectedIndex].chapterID
        } else if affectedIndex < stateBase.chapterSelections.count {
            pivotChapterID = stateBase.chapterSelections[affectedIndex].chapterID
        } else {
            throw NovelError.invalidInput(
                "无法定位需要同步的章节改动，请点「重新载入」后再试。"
            )
        }
        let candidateBase = try rebuildBaseCheckpoint(
            before: pivotChapterID,
            headCheckpointID: head.id,
            in: document
        )
        let rebuildBase = try latestCompatibleRebuildBase(
            atOrBefore: candidateBase,
            workingSelections: workingSelections,
            in: document
        )
        let suffixStart = firstChangedChapterIndex(
            from: rebuildBase.chapterSelections,
            to: workingSelections
        ) ?? rebuildBase.chapterSelections.count
        guard suffixStart < workingSelections.count else {
            // Trailing-only structural removal whose content is already described by
            // rebuildBase: promote working to synchronized without a model call.
            return try commitStructuralManualSync(
                command,
                payloadSHA256: payloadSHA256,
                rebuildBase: rebuildBase,
                in: document,
                now: now
            )
        }
        var chapters: [NovelManualRebuildChapter] = []
        for selection in branch.workingChapterSelections[suffixStart...] {
            guard let version = document.chapterVersions.first(where: {
                $0.id == selection.versionID && $0.chapterID == selection.chapterID
            }) else {
                throw NovelError.invalidInput("The working manuscript contains a missing chapter version.")
            }
            chapters.append(NovelManualRebuildChapter(
                chapterID: selection.chapterID,
                versionID: version.id,
                title: version.title,
                content: version.content,
                factCompatibilityID: version.factCompatibilityID
            ))
        }
        let durablePayload = try encodeManualPayload(chapters)
        guard let session = document.sessions.first(where: { $0.id == branch.sessionID }) else {
            throw NovelError.sessionNotFound(branch.sessionID)
        }
        let cursor: NovelSessionCursor = session.messages.last.map {
            .through(sequence: $0.sequence)
        } ?? .empty
        let pending = NovelPendingOperationRecord(
            id: command.pendingID,
            kind: .manualSync,
            status: .pending,
            branchID: branch.id,
            operationID: command.context.operationID,
            payloadSHA256: payloadSHA256,
            baseCheckpointID: head.id,
            baseHeadRevision: branch.headRevision,
            baseWorkingRevision: branch.workingRevision,
            candidateID: nil,
            collectionTarget: nil,
            selectedText: durablePayload,
            proposedChapterVersion: nil,
            proposedCheckpointID: command.checkpointID,
            proposedStateSnapshotID: command.stateSnapshotID,
            rebuildBaseCheckpointID: rebuildBase.id,
            sessionCursor: cursor,
            createdAt: now,
            lastError: nil
        )
        var next = document
        next.pendingOperations.append(pending)
        advanceProjectRevision(in: &next, now: now)
        try validateTransition(from: document, to: next)
        return .needsModel(document: next, pending: pending)
    }

    /// Working manuscript is already fully covered by `rebuildBase` (same chapter
    /// versions). Typical case: a chapter was removed from the working list while
    /// earlier content is unchanged — no model call is required to clear needsSync.
    private static func commitStructuralManualSync(
        _ command: NovelSyncManualEditsCommand,
        payloadSHA256: String,
        rebuildBase: NovelBranchCheckpointRecord,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> NovelManualSyncPreparation {
        let branchIndex = try requireBranch(command.branchID, in: document)
        let branch = document.branches[branchIndex]
        guard rebuildBase.chapterSelections == branch.workingChapterSelections else {
            throw NovelError.invalidInput(
                "剧情同步需要重新分析正文，但未能切出有效片段。请点「重新载入」后再试。"
            )
        }
        let baseState = try stateSnapshot(rebuildBase.stateSnapshotID, in: document)
        guard let session = document.sessions.first(where: { $0.id == branch.sessionID }) else {
            throw NovelError.sessionNotFound(branch.sessionID)
        }
        let cursor: NovelSessionCursor = session.messages.last.map {
            .through(sequence: $0.sequence)
        } ?? .empty
        let snapshotID = command.stateSnapshotID
        let checkpointID = command.checkpointID
        let newSnapshot = NovelStateSnapshotRecord(
            id: snapshotID,
            eventIDs: baseState.eventIDs,
            summary: baseState.summary,
            branchOutline: baseState.branchOutline,
            unresolvedEntityNames: baseState.unresolvedEntityNames,
            createdAt: now,
            settingProposalIDs: baseState.settingProposalIDs,
            characterIdentityClarifications: baseState.characterIdentityClarifications,
            recentWrittenHighlights: baseState.recentWrittenHighlights
        )
        let finalRevision = document.project.revision + 1
        let outcome = NovelOutcome.manualSyncCommitted(
            projectID: document.project.id,
            branchID: branch.id,
            checkpointID: checkpointID,
            revision: finalRevision
        )
        let checkpoint = NovelBranchCheckpointRecord(
            id: checkpointID,
            kind: .manualSync,
            createdOnBranchID: branch.id,
            parentCheckpointID: branch.headCheckpointID,
            chapterSelections: branch.workingChapterSelections,
            stateSnapshotID: snapshotID,
            sessionCursor: cursor,
            branchOverrideRevisionIDs: branch.overrideRevisionIDs,
            sourceCandidateID: nil,
            baseHeadRevision: branch.headRevision,
            operationID: command.context.operationID,
            createdAt: now
        )
        var next = document
        next.stateSnapshots.append(newSnapshot)
        next.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: command.context.operationID,
            kind: .syncManualEdits,
            payloadSHA256: payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: finalRevision,
            appliedAt: now
        ))
        try NovelReducer.appendCheckpoint(
            checkpoint,
            to: &next,
            expectedHeadRevision: branch.headRevision,
            advancesWorkingRevision: false,
            now: now
        )
        advanceProjectRevision(in: &next, now: now)
        guard next.project.revision == finalRevision else {
            throw NovelError.invalidInput("Manual synchronization revision accounting failed.")
        }
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return .completed(document: next, outcome: outcome)
    }

    static func manualRebuildInput(
        pendingID: NovelPendingOperationID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelManualRebuildInput {
        let pending = try requirePending(pendingID, kind: .manualSync, in: document)
        guard let rebuildBaseCheckpointID = pending.rebuildBaseCheckpointID,
              let sessionCursor = pending.sessionCursor else {
            throw NovelError.invalidInput("The manual synchronization pending payload is incomplete.")
        }
        let rebuildBase = try checkpoint(rebuildBaseCheckpointID, in: document)
        let baseState = try stateSnapshot(rebuildBase.stateSnapshotID, in: document)
        let chapters = try decodeManualPayload(pending.selectedText)
        try validateManualPayload(chapters, pending: pending, in: document)
        let manuscript = manualRebuildManuscript(chapters)
        return NovelManualRebuildInput(
            structuredRunID: NovelRunID(pending.id.rawValue),
            pending: pending,
            rebuildBaseCheckpoint: rebuildBase,
            baseStateSnapshot: baseState,
            chapters: chapters,
            sessionCursor: sessionCursor,
            baseContext: try stateContext(baseState, branchID: pending.branchID, in: document),
            manuscript: manuscript
        )
    }

    static func validateRetryCommand(
        _ command: NovelRetryPendingCommand,
        in document: NovelProjectDocumentV1
    ) throws -> NovelPendingOperationRecord {
        guard let pending = document.pendingOperations.first(where: {
            $0.id == command.pendingID
        }) else {
            throw NovelError.invalidInput("The pending fact transaction was not found.")
        }
        let payloadSHA256 = try command.canonicalPayloadSHA256()
        if let attempt = document.factAttempts.first(where: {
            $0.attemptOperationID == command.context.operationID
        }) {
            let expectedKind: NovelFactReceiptKind = pending.kind == .collection
                ? .stateDelta
                : .manualRebuild
            guard command.projectID == document.project.id,
                  attempt.pendingID == pending.id,
                  attempt.ownerOperationID == pending.operationID,
                  attempt.attemptPayloadSHA256 == payloadSHA256,
                  attempt.branchID == pending.branchID,
                  attempt.kind == expectedKind else {
                throw NovelError.idempotencyConflict(command.context.operationID)
            }
            return pending
        }
        try validateRetry(command, for: pending, in: document)
        return pending
    }

    static func finalizeManualSync(
        pendingID: NovelPendingOperationID,
        rebuild: NovelStateRebuildV1,
        retryCommand: NovelRetryPendingCommand? = nil,
        validatedAttempt: NovelFactTransactionAttempt? = nil,
        artifacts: NovelFactTransactionReceiptArtifacts? = nil,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelFactTransactionResult {
        let pending = try requirePending(pendingID, kind: .manualSync, in: document)
        let attempt: NovelFactTransactionAttempt
        if let validatedAttempt {
            guard retryCommand == nil else {
                throw NovelError.invalidInput("Manual sync received two retry ownership proofs.")
            }
            try validateDurableAttempt(validatedAttempt, for: pending, in: document)
            attempt = validatedAttempt
        } else {
            try validateRetry(retryCommand, for: pending, in: document)
            attempt = try NovelFactTransactionAttempt(
                pending: pending,
                retryCommand: retryCommand
            )
        }
        let branchIndex = try requireBranch(pending.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requirePendingGuards(
            pending,
            branch: branch,
            projectID: document.project.id
        )
        guard branch.syncStatus == .needsSync else {
            throw NovelError.invalidInput("The branch no longer requires manual synchronization.")
        }
        let input = try manualRebuildInput(pendingID: pendingID, in: document)
        if let progress = pending.manualSyncProgress {
            guard NovelManualSyncProgressReducer.isComplete(
                progress,
                manuscript: input.manuscript
            ), progress.accumulatedRebuild == rebuild,
            artifacts == nil else {
                throw NovelError.invalidInput("Manual synchronization progress is not ready to finalize.")
            }
        } else if artifacts == nil {
            throw NovelError.invalidInput("Manual synchronization has no completed model evidence.")
        }
        // Host-accepted empty rebuilds may keep a blank project's empty
        // summary/outline. Model-schema `validate` rejects those empty
        // strings, but they are legal durable snapshot values. Mapped
        // last-chapter deltas may also keep an empty outline when the
        // model omitted `branchOutlinePatch`.
        let validatedRebuild = skipsRebuildModelSchema(
            rebuild,
            summary: input.baseStateSnapshot.summary,
            outline: input.baseStateSnapshot.branchOutline
        ) ? rebuild : try validate(rebuild)
        try validateStateFacts(
            validatedRebuild,
            evidenceSource: input.manuscript,
            branch: branch,
            baseState: input.baseStateSnapshot,
            in: document
        )
        guard let checkpointID = pending.proposedCheckpointID,
              let snapshotID = pending.proposedStateSnapshotID else {
            throw NovelError.invalidInput("The manual synchronization reserved IDs are missing.")
        }
        let eventDrafts: [StoryEventDraft]
        if let progress = pending.manualSyncProgress {
            eventDrafts = try storyEventDrafts(
                progress,
                manuscript: input.manuscript
            )
        } else {
            eventDrafts = try storyEventDrafts(
                validatedRebuild,
                evidenceSource: input.manuscript
            )
        }
        let newEvents = try storyEvents(
            eventDrafts,
            namespace: pending.operationID,
            baseState: input.baseStateSnapshot,
            in: document,
            now: now
        )
        let proposals = try settingProposals(
            validatedRebuild.settingProposals,
            namespace: pending.operationID,
            branchID: branch.id,
            in: document,
            now: now
        )
        let newSnapshot = NovelStateSnapshotRecord(
            id: snapshotID,
            eventIDs: input.baseStateSnapshot.eventIDs + newEvents.map(\.id),
            summary: validatedRebuild.stateSummary,
            branchOutline: validatedRebuild.branchOutline,
            unresolvedEntityNames: validatedRebuild.unresolvedEntityNames,
            createdAt: now,
            settingProposalIDs: input.baseStateSnapshot.settingProposalIDs + proposals.map(\.id),
            characterIdentityClarifications: input.baseStateSnapshot.characterIdentityClarifications,
            recentWrittenHighlights: NovelStateSnapshotRecord.mergedHighlights(
                prior: input.baseStateSnapshot.recentWrittenHighlights,
                newEventSummaries: newEvents.map(\.summary)
            )
        )
        let finalRevision = document.project.revision + 1
        let outcome = NovelOutcome.manualSyncCommitted(
            projectID: document.project.id,
            branchID: branch.id,
            checkpointID: checkpointID,
            revision: finalRevision
        )
        let checkpoint = NovelBranchCheckpointRecord(
            id: checkpointID,
            kind: .manualSync,
            createdOnBranchID: branch.id,
            parentCheckpointID: pending.baseCheckpointID,
            chapterSelections: branch.workingChapterSelections,
            stateSnapshotID: snapshotID,
            sessionCursor: input.sessionCursor,
            branchOverrideRevisionIDs: branch.overrideRevisionIDs,
            sourceCandidateID: nil,
            baseHeadRevision: pending.baseHeadRevision,
            operationID: pending.operationID,
            createdAt: now
        )

        var next = document
        next.events.append(contentsOf: newEvents)
        next.stateSnapshots.append(newSnapshot)
        next.settingProposals.append(contentsOf: proposals)
        if validatedAttempt != nil {
            try appendLedger(
                pending: pending,
                outcome: outcome,
                attempt: attempt,
                appliedRevision: finalRevision,
                to: &next,
                now: now
            )
        } else {
            try appendLedger(
                pending: pending,
                outcome: outcome,
                retryCommand: retryCommand,
                appliedRevision: finalRevision,
                to: &next,
                now: now
            )
        }
        if let artifacts {
            try appendFactReceipts(
                artifacts,
                pending: pending,
                retryCommand: retryCommand,
                kind: .manualRebuild,
                to: &next
            )
        }
        try NovelReducer.appendCheckpoint(
            checkpoint,
            to: &next,
            expectedHeadRevision: pending.baseHeadRevision,
            advancesWorkingRevision: false,
            now: now
        )
        next.pendingOperations.removeAll { $0.id == pending.id }
        advanceProjectRevision(in: &next, now: now)
        guard next.project.revision == finalRevision else {
            throw NovelError.invalidInput("Manual synchronization revision accounting failed.")
        }
        try validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func markRetryable(
        pendingID: NovelPendingOperationID,
        message: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        let normalizedMessage = try normalizedRequired(message, field: "Retry failure")
        guard let index = document.pendingOperations.firstIndex(where: {
            $0.id == pendingID
        }) else {
            throw NovelError.invalidInput("The pending operation was not found.")
        }
        if document.pendingOperations[index].status == .retryable,
           document.pendingOperations[index].lastError == normalizedMessage {
            return document
        }
        var next = document
        next.pendingOperations[index].status = .retryable
        next.pendingOperations[index].lastError = normalizedMessage
        advanceProjectRevision(in: &next, now: now)
        try validateTransition(from: document, to: next)
        return next
    }

    private struct ManualPayload: Codable {
        let schemaVersion: Int
        let chapters: [NovelManualRebuildChapter]
    }

    private struct StateContextPayload: Codable {
        let summary: String
        let branchOutline: String
        let unresolvedEntityNames: [String]
        let characterIdentityClarifications: [NovelCharacterIdentityClarificationRecord]
        let events: [NovelStoryEventRecord]
    }

    private static func matchingPending(
        id: NovelPendingOperationID,
        operationID: NovelOperationID,
        kind: NovelPendingOperationKind,
        payloadSHA256: String,
        in document: NovelProjectDocumentV1
    ) throws -> NovelPendingOperationRecord? {
        let byID = document.pendingOperations.first(where: { $0.id == id })
        let byOperation = document.pendingOperations.first(where: {
            $0.operationID == operationID
        })
        guard byID != nil || byOperation != nil else { return nil }
        guard let existing = byID,
              existing == byOperation,
              existing.operationID == operationID,
              existing.kind == kind,
              existing.payloadSHA256 == payloadSHA256 else {
            throw NovelError.idempotencyConflict(operationID)
        }
        return existing
    }

    private static func requireUnusedOperation(
        _ operationID: NovelOperationID,
        in document: NovelProjectDocumentV1
    ) throws {
        if document.appliedOperations.contains(where: { $0.operationID == operationID }) ||
            document.factAttempts.contains(where: { $0.attemptOperationID == operationID }) ||
            document.pendingOperations.contains(where: { $0.operationID == operationID }) ||
            document.polishTransactions.contains(where: { $0.operationID == operationID }) {
            throw NovelError.idempotencyConflict(operationID)
        }
    }

    private static func requireProject(
        _ projectID: NovelProjectID,
        in document: NovelProjectDocumentV1
    ) throws {
        guard projectID == document.project.id else {
            throw NovelError.projectNotFound(projectID)
        }
    }

    private static func requireBranch(
        _ branchID: NovelBranchID,
        in document: NovelProjectDocumentV1
    ) throws -> Int {
        guard let index = document.branches.firstIndex(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        return index
    }

    private static func requireContext(
        _ context: NovelMutationContext,
        branch: NovelBranchRecord,
        document: NovelProjectDocumentV1
    ) throws {
        guard let expectedProject = context.expectedProjectRevision else {
            throw NovelError.invalidInput("Expected project revision is missing.")
        }
        guard expectedProject == document.project.revision else {
            throw NovelError.staleProjectRevision(
                expected: expectedProject,
                actual: document.project.revision
            )
        }
        if let expectedConfig = context.expectedConfigRevision,
           expectedConfig != document.project.configRevision {
            throw NovelError.staleConfigRevision(
                expected: expectedConfig,
                actual: document.project.configRevision
            )
        }
        guard let expectedHead = context.expectedBranchHeadRevision else {
            throw NovelError.invalidInput("Expected branch head revision is missing.")
        }
        guard expectedHead == branch.headRevision else {
            throw NovelError.staleBranchHeadRevision(
                expected: expectedHead,
                actual: branch.headRevision
            )
        }
    }

    private static func requirePending(
        _ pendingID: NovelPendingOperationID,
        kind: NovelPendingOperationKind,
        in document: NovelProjectDocumentV1
    ) throws -> NovelPendingOperationRecord {
        guard let pending = document.pendingOperations.first(where: {
            $0.id == pendingID && $0.kind == kind
        }) else {
            throw NovelError.invalidInput("The pending fact transaction was not found.")
        }
        return pending
    }

    private static func requirePendingGuards(
        _ pending: NovelPendingOperationRecord,
        branch: NovelBranchRecord,
        projectID: NovelProjectID
    ) throws {
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(branch.id)
        }
        guard branch.activeRunID == nil else {
            throw NovelError.projectBusy(projectID)
        }
        guard branch.headCheckpointID == pending.baseCheckpointID,
              branch.headRevision == pending.baseHeadRevision else {
            throw NovelError.staleBranchHeadRevision(
                expected: pending.baseHeadRevision,
                actual: branch.headRevision
            )
        }
        guard branch.workingRevision == pending.baseWorkingRevision else {
            throw NovelError.invalidInput(
                "The working manuscript changed while fact synchronization was pending."
            )
        }
    }

    private static func requirePayloadHash(_ payloadSHA256: String) throws {
        guard NovelDocumentValidator.isSHA256(payloadSHA256) else {
            throw NovelError.invalidInput("The fact transaction payload hash is invalid.")
        }
    }

    private static func requireReservedIDs(
        chapterVersionID: NovelChapterVersionID?,
        checkpointID: NovelCheckpointID,
        stateSnapshotID: NovelStateSnapshotID,
        pendingID: NovelPendingOperationID,
        in document: NovelProjectDocumentV1
    ) throws {
        let completedPendingIDs = Set(document.injectionReceipts.compactMap {
            $0.factTransaction?.pendingID
        })
        guard document.pendingOperations.allSatisfy({ $0.id != pendingID }),
              !completedPendingIDs.contains(pendingID) else {
            throw NovelError.immutableRecordConflict("pending operation \(pendingID)")
        }
        if let chapterVersionID {
            guard document.chapterVersions.allSatisfy({ $0.id != chapterVersionID }),
                  document.pendingOperations.allSatisfy({
                      $0.proposedChapterVersion?.id != chapterVersionID
                  }) else {
                throw NovelError.immutableRecordConflict("chapter version \(chapterVersionID)")
            }
        }
        guard document.checkpoints.allSatisfy({ $0.id != checkpointID }),
              document.pendingOperations.allSatisfy({
                  $0.proposedCheckpointID != checkpointID
              }) else {
            throw NovelError.immutableRecordConflict("checkpoint \(checkpointID)")
        }
        guard document.stateSnapshots.allSatisfy({ $0.id != stateSnapshotID }),
              document.pendingOperations.allSatisfy({
                  $0.proposedStateSnapshotID != stateSnapshotID
              }) else {
            throw NovelError.immutableRecordConflict("state snapshot \(stateSnapshotID)")
        }
    }

    private static func requireNewFactCompatibilityID(
        _ id: UUID,
        in document: NovelProjectDocumentV1
    ) throws {
        guard id != UUID(uuidString: "00000000-0000-0000-0000-000000000000"),
              document.chapterVersions.allSatisfy({ $0.factCompatibilityID != id }),
              document.pendingOperations.allSatisfy({
                  $0.proposedChapterVersion?.factCompatibilityID != id
              }) else {
            throw NovelError.invalidInput("A fact compatibility ID must identify a new fact lineage.")
        }
    }

    private static func validateRetry(
        _ command: NovelRetryPendingCommand?,
        for pending: NovelPendingOperationRecord,
        in document: NovelProjectDocumentV1
    ) throws {
        switch (pending.status, command) {
        case (.pending, nil):
            return
        case (.pending, .some(let command)), (.retryable, .some(let command)):
            guard command.projectID == document.project.id,
                  command.pendingID == pending.id,
                  command.context.operationID != pending.operationID else {
                throw NovelError.invalidInput("The retry command does not match its pending operation.")
            }
            let hash = try command.canonicalPayloadSHA256()
            try requirePayloadHash(hash)
            let expectedKind: NovelFactReceiptKind = pending.kind == .collection
                ? .stateDelta
                : .manualRebuild
            if let attempt = document.factAttempts.first(where: {
                $0.attemptOperationID == command.context.operationID
            }) {
                guard attempt.pendingID == pending.id,
                      attempt.ownerOperationID == pending.operationID,
                      attempt.attemptPayloadSHA256 == hash,
                      attempt.branchID == pending.branchID,
                      attempt.kind == expectedKind else {
                    throw NovelError.idempotencyConflict(command.context.operationID)
                }
                return
            }
            try requireUnusedOperation(command.context.operationID, in: document)
            guard let expectedProject = command.context.expectedProjectRevision else {
                throw NovelError.invalidInput("Expected project revision is missing for retry.")
            }
            guard expectedProject == document.project.revision else {
                throw NovelError.staleProjectRevision(
                    expected: expectedProject,
                    actual: document.project.revision
                )
            }
            if let expectedConfig = command.context.expectedConfigRevision,
               expectedConfig != document.project.configRevision {
                throw NovelError.staleConfigRevision(
                    expected: expectedConfig,
                    actual: document.project.configRevision
                )
            }
            let branchIndex = try requireBranch(pending.branchID, in: document)
            if let expectedHead = command.context.expectedBranchHeadRevision,
               expectedHead != document.branches[branchIndex].headRevision {
                throw NovelError.staleBranchHeadRevision(
                    expected: expectedHead,
                    actual: document.branches[branchIndex].headRevision
                )
            }
        case (.retryable, nil):
            throw NovelError.invalidInput("Retry ownership does not match the pending status.")
        }
    }

    private static func validateDurableAttempt(
        _ attempt: NovelFactTransactionAttempt,
        for pending: NovelPendingOperationRecord,
        in document: NovelProjectDocumentV1
    ) throws {
        if attempt.operationID == pending.operationID {
            guard attempt.payloadSHA256 == pending.payloadSHA256 else {
                throw NovelError.idempotencyConflict(attempt.operationID)
            }
            return
        }
        guard document.factAttempts.contains(where: {
            $0.pendingID == pending.id &&
                $0.ownerOperationID == pending.operationID &&
                $0.attemptOperationID == attempt.operationID &&
                $0.attemptPayloadSHA256 == attempt.payloadSHA256 &&
                $0.branchID == pending.branchID &&
                $0.kind == .manualRebuild
        }) else {
            throw NovelError.invalidInput("The retry attempt has no durable manual-sync progress.")
        }
    }

    private static func appendLedger(
        pending: NovelPendingOperationRecord,
        outcome: NovelOutcome,
        retryCommand: NovelRetryPendingCommand?,
        appliedRevision: Int64,
        to document: inout NovelProjectDocumentV1,
        now: Date
    ) throws {
        let originalKind: NovelOperationKind = pending.kind == .collection
            ? .collectCandidate
            : .syncManualEdits
        document.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: pending.operationID,
            kind: originalKind,
            payloadSHA256: pending.payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: appliedRevision,
            appliedAt: now
        ))
        if let retryCommand {
            document.appliedOperations.append(NovelAppliedOperationRecord(
                operationID: retryCommand.context.operationID,
                kind: .retryPending,
                payloadSHA256: try retryCommand.canonicalPayloadSHA256(),
                outcome: outcome,
                appliedProjectRevision: appliedRevision,
                appliedAt: now
            ))
        }
    }

    private static func appendLedger(
        pending: NovelPendingOperationRecord,
        outcome: NovelOutcome,
        attempt: NovelFactTransactionAttempt,
        appliedRevision: Int64,
        to document: inout NovelProjectDocumentV1,
        now: Date
    ) throws {
        let originalKind: NovelOperationKind = pending.kind == .collection
            ? .collectCandidate
            : .syncManualEdits
        document.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: pending.operationID,
            kind: originalKind,
            payloadSHA256: pending.payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: appliedRevision,
            appliedAt: now
        ))
        guard attempt.operationID != pending.operationID else { return }
        document.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: attempt.operationID,
            kind: .retryPending,
            payloadSHA256: attempt.payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: appliedRevision,
            appliedAt: now
        ))
    }

    private static func appendFactReceipts(
        _ artifacts: NovelFactTransactionReceiptArtifacts,
        pending: NovelPendingOperationRecord,
        retryCommand: NovelRetryPendingCommand?,
        kind: NovelFactReceiptKind,
        to document: inout NovelProjectDocumentV1
    ) throws {
        let injection = artifacts.injectionReceipt
        let generation = artifacts.generationReceipt
        let expectedLink = NovelFactReceiptLink(
            pendingID: pending.id,
            ownerOperationID: pending.operationID,
            attemptOperationID: retryCommand?.context.operationID ?? pending.operationID,
            attemptPayloadSHA256: try retryCommand?.canonicalPayloadSHA256() ??
                pending.payloadSHA256,
            kind: kind,
            chunkIndex: injection.factTransaction?.chunkIndex
        )
        guard injection.projectID == document.project.id,
              injection.branchID == pending.branchID,
              injection.runID == generation.runID,
              injection.factTransaction == expectedLink,
              generation.factTransaction == expectedLink,
              generation.injectionReceiptID == injection.id else {
            throw NovelError.invalidInput("Fact-model receipts do not match the pending attempt.")
        }

        let existingInjection = document.injectionReceipts.first(where: {
            $0.id == injection.id
        })
        let existingGeneration = document.generationReceipts.first(where: {
            $0.id == generation.id
        })
        if existingInjection != nil || existingGeneration != nil {
            guard existingInjection == injection,
                  existingGeneration == generation else {
                throw NovelError.immutableRecordConflict("fact-model receipt identity")
            }
            return
        }

        let existingReceiptIDs = Set(document.injectionReceipts.map(\.id))
            .union(document.generationReceipts.map(\.id))
        guard injection.id != generation.id,
              !existingReceiptIDs.contains(injection.id),
              !existingReceiptIDs.contains(generation.id),
              !document.injectionReceipts.contains(where: { $0.runID == injection.runID }),
              !document.generationReceipts.contains(where: { $0.runID == generation.runID }) else {
            throw NovelError.immutableRecordConflict("fact-model receipt identity")
        }

        document.injectionReceipts.append(injection)
        document.generationReceipts.append(generation)
    }

    private static func advanceProjectRevision(
        in document: inout NovelProjectDocumentV1,
        now: Date
    ) {
        document.project.revision += 1
        document.project.updatedAt = now
    }

    private static func checkpoint(
        _ id: NovelCheckpointID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelBranchCheckpointRecord {
        guard let checkpoint = document.checkpoints.first(where: { $0.id == id }) else {
            throw NovelError.checkpointNotFound(id)
        }
        return checkpoint
    }

    private static func stateSnapshot(
        _ id: NovelStateSnapshotID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelStateSnapshotRecord {
        guard let snapshot = document.stateSnapshots.first(where: { $0.id == id }) else {
            throw NovelError.stateSnapshotNotFound(id)
        }
        return snapshot
    }

    private static func rebuildBaseCheckpoint(
        before chapterID: NovelChapterID,
        headCheckpointID: NovelCheckpointID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelBranchCheckpointRecord {
        var reversed: [NovelBranchCheckpointRecord] = []
        var visited: Set<NovelCheckpointID> = []
        var current: NovelBranchCheckpointRecord? = try checkpoint(headCheckpointID, in: document)
        while let node = current {
            guard visited.insert(node.id).inserted else {
                throw NovelError.invalidInput("The checkpoint lineage contains a cycle.")
            }
            reversed.append(node)
            current = try node.parentCheckpointID.map { try checkpoint($0, in: document) }
        }
        let lineage = reversed.reversed()
        guard let introduction = lineage.first(where: {
            $0.chapterSelections.contains(where: { $0.chapterID == chapterID })
        }) else {
            throw NovelError.invalidInput("The edited chapter has no checkpoint introduction.")
        }
        if let parentID = introduction.parentCheckpointID {
            return try checkpoint(parentID, in: document)
        }
        return introduction
    }

    /// 重建基线必须满足一条不变量：它的 stateSnapshot 恰好是**为它自己那份 chapterSelections
    /// 重建出来的**。只有 `.initial`(空选集空状态)和 `.manualSync`(状态由本次重建产出)满足。
    ///
    /// 其余 kind 都是把 `branch.currentStateSnapshotID` 原样继承下来的：`.collection`
    /// 尤其危险,它的选集比快照多一章。一旦被选作基线,suffix 按选集算就会跳过那些
    /// 「在选集里、但不在快照里」的章节 —— 它们既不在基线状态里,也不在待重抽的正文里,
    /// 事实凭空蒸发。见 NovelChapterReplacementStateTests
    /// .testReplacingChapterKeepsEarlierFactsWhenCollectionsSkippedSync。
    private static func latestCompatibleRebuildBase(
        atOrBefore checkpoint: NovelBranchCheckpointRecord,
        workingSelections: [NovelChapterSelection],
        in document: NovelProjectDocumentV1
    ) throws -> NovelBranchCheckpointRecord {
        var current: NovelBranchCheckpointRecord? = checkpoint
        var visited: Set<NovelCheckpointID> = []
        while let candidate = current {
            guard visited.insert(candidate.id).inserted else {
                throw NovelError.invalidInput("The checkpoint lineage contains a cycle.")
            }
            if candidate.stateSnapshotDescribesOwnSelections,
               candidate.chapterSelections.count <= workingSelections.count,
               zip(candidate.chapterSelections, workingSelections).allSatisfy({
                   $0.chapterID == $1.chapterID && $0.versionID == $1.versionID
               }) {
                return candidate
            }
            current = try candidate.parentCheckpointID.map {
                try self.checkpoint($0, in: document)
            }
        }
        throw NovelError.invalidInput("No valid rebuild base exists for the working manuscript.")
    }

    private static func staleStateBaseCheckpoint(
        from head: NovelBranchCheckpointRecord,
        in document: NovelProjectDocumentV1
    ) throws -> NovelBranchCheckpointRecord {
        var current = head
        var visited: Set<NovelCheckpointID> = []
        while current.kind == .collection,
              let parentID = current.parentCheckpointID {
            guard visited.insert(current.id).inserted else {
                throw NovelError.invalidInput("The checkpoint lineage contains a cycle.")
            }
            let parent = try checkpoint(parentID, in: document)
            guard parent.stateSnapshotID == current.stateSnapshotID else { break }
            current = parent
        }
        return current
    }

    private static func firstChangedChapterIndex(
        from base: [NovelChapterSelection],
        to current: [NovelChapterSelection]
    ) -> Int? {
        let sharedCount = min(base.count, current.count)
        if let changed = (0..<sharedCount).first(where: { base[$0] != current[$0] }) {
            return changed
        }
        return base.count == current.count ? nil : sharedCount
    }

    /// Accept version edits, order-preserving deletes (including middle), and trailing
    /// appends. Reject reorder / duplicate chapter IDs.
    private static func validateWorkingManuscriptEvolution(
        from head: [NovelChapterSelection],
        to working: [NovelChapterSelection]
    ) throws {
        let headIDs = head.map(\.chapterID)
        let workingIDs = working.map(\.chapterID)
        guard Set(workingIDs).count == workingIDs.count else {
            throw NovelError.invalidInput(
                "工作稿章节列表重复，无法同步。请点「重新载入」后再试。"
            )
        }
        if headIDs == workingIDs { return }
        // Any deletes (end or middle) that keep relative order of remaining chapters.
        if isOrderPreservingSubsequence(workingIDs, of: headIDs) { return }
        // Trailing append(s) of brand-new chapters after the previous head list.
        if workingIDs.starts(with: headIDs) { return }
        // Deletes + trailing appends: a prefix of working is a subsequence of head,
        // then only brand-new chapter IDs follow.
        let keptCount = orderPreservingSubsequenceLength(workingIDs, of: headIDs)
        if keptCount > 0, keptCount < workingIDs.count {
            let newTail = Array(workingIDs[keptCount...])
            let headSet = Set(headIDs)
            if newTail.allSatisfy({ !headSet.contains($0) }) {
                return
            }
        }
        throw NovelError.invalidInput(
            "工作稿章节顺序与存档不一致，无法同步。请点「重新载入」后再试。"
        )
    }

    /// Whether `needle` appears in order inside `haystack` (not necessarily contiguous).
    private static func isOrderPreservingSubsequence(
        _ needle: [NovelChapterID],
        of haystack: [NovelChapterID]
    ) -> Bool {
        orderPreservingSubsequenceLength(needle, of: haystack) == needle.count
    }

    /// How many leading elements of `needle` form an order-preserving subsequence of
    /// `haystack`.
    private static func orderPreservingSubsequenceLength(
        _ needle: [NovelChapterID],
        of haystack: [NovelChapterID]
    ) -> Int {
        var i = 0
        for id in haystack {
            if i < needle.count, needle[i] == id {
                i += 1
            }
        }
        return i
    }

    private static func encodeManualPayload(
        _ chapters: [NovelManualRebuildChapter]
    ) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(ManualPayload(schemaVersion: 1, chapters: chapters))
        guard let value = String(data: data, encoding: .utf8) else {
            throw NovelError.invalidInput("The manual synchronization payload is not UTF-8.")
        }
        return value
    }

    static func decodeManualPayload(
        _ value: String
    ) throws -> [NovelManualRebuildChapter] {
        do {
            let payload = try JSONDecoder().decode(ManualPayload.self, from: Data(value.utf8))
            guard payload.schemaVersion == 1, !payload.chapters.isEmpty else {
                throw NovelError.invalidInput("The manual synchronization payload is invalid.")
            }
            return payload.chapters
        } catch let error as NovelError {
            throw error
        } catch {
            throw NovelError.invalidInput("The manual synchronization payload is unreadable: \(error)")
        }
    }

    static func manualRebuildManuscript(_ chapters: [NovelManualRebuildChapter]) -> String {
        chapters.map {
            "# \($0.title)\n\n\($0.content)"
        }.joined(separator: "\n\n")
    }

    static func validateManualPayload(
        _ chapters: [NovelManualRebuildChapter],
        pending: NovelPendingOperationRecord,
        in document: NovelProjectDocumentV1
    ) throws {
        guard Set(chapters.map(\.chapterID)).count == chapters.count,
              Set(chapters.map(\.versionID)).count == chapters.count else {
            throw NovelError.invalidInput("The manual synchronization suffix repeats a chapter.")
        }
        let branchIndex = try requireBranch(pending.branchID, in: document)
        let working = document.branches[branchIndex].workingChapterSelections
        guard let rebuildBaseID = pending.rebuildBaseCheckpointID else {
            throw NovelError.invalidInput("The durable manual synchronization rebuild base is missing.")
        }
        let rebuildBase = try checkpoint(rebuildBaseID, in: document)
        let suffixSelections = chapters.map {
            NovelChapterSelection(chapterID: $0.chapterID, versionID: $0.versionID)
        }
        guard rebuildBase.chapterSelections + suffixSelections == working else {
            throw NovelError.invalidInput("The durable manual synchronization suffix is stale.")
        }
        for chapter in chapters {
            guard document.chapterVersions.contains(where: {
                $0.id == chapter.versionID &&
                    $0.chapterID == chapter.chapterID &&
                    $0.title == chapter.title &&
                    $0.content == chapter.content &&
                    $0.factCompatibilityID == chapter.factCompatibilityID
            }) else {
                throw NovelError.invalidInput("A durable manual synchronization chapter changed.")
            }
        }
    }

    private static func stateContext(
        _ snapshot: NovelStateSnapshotRecord,
        branchID: NovelBranchID,
        in document: NovelProjectDocumentV1
    ) throws -> String {
        let branchIndex = try requireBranch(branchID, in: document)
        let snapshot = try effectiveStateSnapshot(
            snapshot,
            branch: document.branches[branchIndex],
            document: document
        )
        var events: [NovelStoryEventRecord] = []
        for eventID in snapshot.eventIDs {
            guard let event = document.events.first(where: { $0.id == eventID }) else {
                throw NovelError.invalidInput("The base state references a missing event.")
            }
            events.append(event)
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(StateContextPayload(
            summary: snapshot.summary,
            branchOutline: snapshot.branchOutline,
            unresolvedEntityNames: snapshot.unresolvedEntityNames,
            characterIdentityClarifications: snapshot.characterIdentityClarifications,
            events: events
        ))
        guard let value = String(data: data, encoding: .utf8) else {
            throw NovelError.invalidInput("The state context is not UTF-8.")
        }
        return value
    }

    private static func normalizedRequired(_ value: String, field: String) throws -> String {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            throw NovelError.invalidInput("\(field) cannot be empty.")
        }
        return normalized
    }

    private static func normalizeLineEndings(_ value: String) -> String {
        value.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
    }

    private static func validateTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1
    ) throws {
        try NovelDocumentValidator.validateTransition(from: current, to: next)
    }
}

private extension NovelBranchCheckpointRecord {
    /// 这个检查点的 stateSnapshot 是否恰好描述它自己那份 chapterSelections。
    ///
    /// `.initial` 是空选集配空状态;`.manualSync` 的快照由该次重建产出,覆盖
    /// 「基线 + suffix」= 自身选集;`.identityClarification` 不改变正文选集，只在
    /// 同一份派生状态上追加作者裁决。其余 kind 都只是把分支当前快照原样带下来，
    /// 快照落后于选集,不能用作重建基线。
    var stateSnapshotDescribesOwnSelections: Bool {
        switch kind {
        case .initial, .manualSync, .identityClarification:
            true
        case .collection, .discussionArchive, .polish, .restore:
            false
        }
    }
}
