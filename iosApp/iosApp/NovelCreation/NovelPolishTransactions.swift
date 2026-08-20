import Foundation

struct NovelPolishRequestArtifacts: Equatable, Sendable {
    let attempt: NovelPolishAttemptRecord
    let injectionReceipt: NovelInjectionReceiptRecord
    let generationReceipt: NovelGenerationReceiptRecord
}

enum NovelPolishTransactionReducer {
    static func validateCurrentTransaction(
        transactionID: NovelPendingOperationID,
        in document: NovelProjectDocumentV1
    ) throws {
        guard let transaction = document.polishTransactions.first(where: {
            $0.id == transactionID && ($0.status == .pending || $0.status == .retryable)
        }) else {
            throw NovelError.invalidInput("The polish transaction is unavailable.")
        }
        try requireCurrentTransactionGuard(transaction, in: document)
    }

    static func prepareAdoption(
        _ command: NovelAdoptPolishCandidateCommand,
        payloadSHA256: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (
        document: NovelProjectDocumentV1,
        transaction: NovelPendingPolishTransactionRecord
    ) {
        try requireProject(command.projectID, in: document)
        if let existing = document.polishTransactions.first(where: {
            $0.id == command.transactionID || $0.operationID == command.context.operationID
        }) {
            guard existing.id == command.transactionID,
                  existing.operationID == command.context.operationID,
                  existing.payloadSHA256 == payloadSHA256,
                  existing.branchID == command.branchID,
                  existing.candidateID == command.candidateID,
                  existing.proposedChapterVersionID == command.proposedChapterVersionID,
                  existing.checkpointID == command.checkpointID else {
                throw NovelError.idempotencyConflict(command.context.operationID)
            }
            return (document, existing)
        }
        try requireUnusedOperation(command.context.operationID, in: document)
        guard NovelDocumentValidator.isSHA256(payloadSHA256) else {
            throw NovelError.invalidInput("The polish adoption payload hash is invalid.")
        }
        let branchIndex = try requireBranch(command.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requireContext(
            command.context,
            expectedWorkingRevision: command.expectedWorkingRevision,
            branch: branch,
            document: document
        )
        try requireIdleSynchronizedBranch(branch, in: document)
        guard let candidate = document.candidates.first(where: {
            $0.id == command.candidateID &&
                $0.branchID == branch.id &&
                $0.sessionID == branch.sessionID &&
                $0.kind == .polish &&
                $0.status == .available &&
                $0.collectedCheckpointID == nil
        }), let sourceID = candidate.sourceChapterVersionID,
            let source = document.chapterVersions.first(where: { $0.id == sourceID }),
            document.chapters.contains(where: {
                $0.id == source.chapterID && $0.discardedAt == nil
            }),
            branch.workingChapterSelections.contains(where: {
                $0.chapterID == source.chapterID && $0.versionID == source.id
            }) else {
            throw NovelError.invalidInput("The polish candidate source is no longer current.")
        }
        guard candidate.baseCheckpointID == branch.headCheckpointID,
              candidate.baseHeadRevision == branch.headRevision else {
            throw NovelError.staleBranchHeadRevision(
                expected: candidate.baseHeadRevision,
                actual: branch.headRevision
            )
        }
        guard document.polishTransactions.allSatisfy({
            $0.candidateID != candidate.id
        }) else {
            throw NovelError.invalidInput("This polish candidate already has an adoption transaction.")
        }
        guard let session = document.sessions.first(where: { $0.id == branch.sessionID }),
              let sourceMessage = session.messages.first(where: {
                  $0.id == candidate.sourceMessageID && $0.candidateID == candidate.id
              }) else {
            throw NovelError.invalidInput("The polish candidate source message is unavailable.")
        }
        try requireReservedIDs(
            command,
            in: document
        )

        let transaction = NovelPendingPolishTransactionRecord(
            id: command.transactionID,
            operationID: command.context.operationID,
            payloadSHA256: payloadSHA256,
            branchID: branch.id,
            candidateID: candidate.id,
            sourceChapterVersionID: source.id,
            proposedChapterVersionID: command.proposedChapterVersionID,
            checkpointID: command.checkpointID,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            baseWorkingRevision: branch.workingRevision,
            sessionCursor: .through(sequence: sourceMessage.sequence),
            sourceContentSHA256: NovelDocumentValidator.sha256(source.content),
            candidateContentSHA256: NovelDocumentValidator.sha256(candidate.content),
            createdAt: now,
            status: .pending,
            attemptCount: 0,
            lastFailure: nil,
            lastFailureAttemptIndex: nil
        )
        var next = document
        next.polishTransactions.append(transaction)
        next.project.revision += 1
        next.project.updatedAt = now
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, transaction)
    }

    static func reserveAttempt(
        _ artifacts: NovelPolishRequestArtifacts,
        transactionID: NovelPendingOperationID,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        guard let transactionIndex = document.polishTransactions.firstIndex(where: {
            $0.id == transactionID
        }) else {
            throw NovelError.invalidInput("The polish transaction is unavailable.")
        }
        let transaction = document.polishTransactions[transactionIndex]
        try requireCurrentTransactionGuard(transaction, in: document)
        guard transaction.status == .pending || transaction.status == .retryable,
              artifacts.attempt.transactionID == transaction.id,
              artifacts.attempt.attemptIndex == transaction.attemptCount,
              artifacts.attempt.sourceContentSHA256 == transaction.sourceContentSHA256,
              artifacts.attempt.candidateContentSHA256 == transaction.candidateContentSHA256,
              artifacts.injectionReceipt.id == artifacts.attempt.injectionReceiptID,
              artifacts.generationReceipt.id == artifacts.attempt.generationReceiptID,
              artifacts.injectionReceipt.runID == artifacts.attempt.runID,
              artifacts.generationReceipt.runID == artifacts.attempt.runID,
              artifacts.generationReceipt.injectionReceiptID == artifacts.injectionReceipt.id,
              artifacts.injectionReceipt.factTransaction == nil,
              artifacts.generationReceipt.factTransaction == nil else {
            throw NovelError.invalidInput("The polish request artifacts do not match their transaction.")
        }
        let receiptIDs = Set(document.injectionReceipts.map(\.id))
            .union(document.generationReceipts.map(\.id))
        guard !receiptIDs.contains(artifacts.injectionReceipt.id),
              !receiptIDs.contains(artifacts.generationReceipt.id),
              artifacts.injectionReceipt.id != artifacts.generationReceipt.id,
              document.polishAttempts.allSatisfy({
                  $0.runID != artifacts.attempt.runID &&
                      !($0.transactionID == transaction.id &&
                        $0.attemptIndex == artifacts.attempt.attemptIndex)
              }) else {
            throw NovelError.immutableRecordConflict("polish request identity")
        }

        var next = document
        next.polishAttempts.append(artifacts.attempt)
        next.injectionReceipts.append(artifacts.injectionReceipt)
        next.generationReceipts.append(artifacts.generationReceipt)
        next.polishTransactions[transactionIndex].attemptCount += 1
        next.polishTransactions[transactionIndex].status = .pending
        next.polishTransactions[transactionIndex].lastFailure = nil
        next.polishTransactions[transactionIndex].lastFailureAttemptIndex = nil
        next.project.revision += 1
        next.project.updatedAt = now
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return next
    }

    static func recordFailure(
        transactionID: NovelPendingOperationID,
        attemptIndex: Int,
        runID: NovelRunID,
        failure: NovelFailure,
        isBlocked: Bool,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        guard let transactionIndex = document.polishTransactions.firstIndex(where: {
            $0.id == transactionID
        }) else {
            throw NovelError.invalidInput("The polish failure has no durable transaction.")
        }
        let transaction = document.polishTransactions[transactionIndex]
        guard attemptIndex == transaction.attemptCount - 1,
              document.polishAttempts.contains(where: {
            $0.transactionID == transactionID &&
                $0.attemptIndex == attemptIndex &&
                $0.runID == runID
        }) else {
            throw NovelError.invalidInput("The polish failure has no durable attempt.")
        }
        if document.polishAssessments.contains(where: {
            $0.transactionID == transactionID && $0.attemptIndex == attemptIndex
        }) {
            return document
        }
        var next = document
        next.polishAssessments.append(NovelPolishAssessmentRecord(
            transactionID: transactionID,
            attemptIndex: attemptIndex,
            runID: runID,
            result: nil,
            failure: failure,
            createdAt: now
        ))
        next.polishTransactions[transactionIndex].status = isBlocked ? .blocked : .retryable
        next.polishTransactions[transactionIndex].lastFailure = failure
        next.polishTransactions[transactionIndex].lastFailureAttemptIndex = attemptIndex
        next.project.revision += 1
        next.project.updatedAt = now
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return next
    }

    static func recordPreflightFailure(
        transactionID: NovelPendingOperationID,
        failure: NovelFailure,
        isBlocked: Bool,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        guard let transactionIndex = document.polishTransactions.firstIndex(where: {
            $0.id == transactionID && ($0.status == .pending || $0.status == .retryable)
        }) else {
            throw NovelError.invalidInput("The polish transaction is unavailable.")
        }
        var next = document
        next.polishTransactions[transactionIndex].status = isBlocked ? .blocked : .retryable
        next.polishTransactions[transactionIndex].lastFailure = failure
        next.polishTransactions[transactionIndex].lastFailureAttemptIndex = nil
        next.project.revision += 1
        next.project.updatedAt = now
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return next
    }

    static func finalizeAssessment(
        transactionID: NovelPendingOperationID,
        attemptIndex: Int,
        runID: NovelRunID,
        result: NovelPolishDriftV1,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        guard let transactionIndex = document.polishTransactions.firstIndex(where: {
            $0.id == transactionID
        }) else {
            throw NovelError.invalidInput("The polish transaction is unavailable.")
        }
        let transaction = document.polishTransactions[transactionIndex]
        let priorAssessmentIndices = Set(document.polishAssessments.compactMap {
            $0.transactionID == transaction.id && $0.attemptIndex < attemptIndex
                ? $0.attemptIndex
                : nil
        })
        guard transaction.status == .pending,
              attemptIndex == transaction.attemptCount - 1,
              priorAssessmentIndices == Set(0..<attemptIndex),
              document.polishAttempts.contains(where: {
                  $0.transactionID == transaction.id &&
                      $0.attemptIndex == attemptIndex &&
                      $0.runID == runID
              }),
              !document.polishAssessments.contains(where: {
                  $0.transactionID == transaction.id && $0.attemptIndex == attemptIndex
              }) else {
            throw NovelError.invalidInput("The polish assessment attempt is no longer current.")
        }

        var next = document
        next.polishAssessments.append(NovelPolishAssessmentRecord(
            transactionID: transaction.id,
            attemptIndex: attemptIndex,
            runID: runID,
            result: result,
            failure: nil,
            createdAt: now
        ))
        let outcome: NovelOutcome
        if result.compatible {
            outcome = try finalizeCompatible(
                transaction: transaction,
                transactionIndex: transactionIndex,
                document: &next,
                now: now
            )
        } else {
            guard let candidateIndex = next.candidates.firstIndex(where: {
                $0.id == transaction.candidateID && $0.status == .available
            }) else {
                throw NovelError.invalidInput("The polish candidate is no longer available.")
            }
            next.candidates[candidateIndex].status = .superseded
            next.polishTransactions[transactionIndex].status = .incompatible
            next.polishTransactions[transactionIndex].lastFailure = nil
            next.polishTransactions[transactionIndex].lastFailureAttemptIndex = nil
            next.project.revision += 1
            next.project.updatedAt = now
            outcome = .polishCandidateRejected(
                projectID: document.project.id,
                branchID: transaction.branchID,
                candidateID: transaction.candidateID,
                transactionID: transaction.id,
                revision: next.project.revision
            )
        }
        next.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: transaction.operationID,
            kind: .adoptPolishCandidate,
            payloadSHA256: transaction.payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: next.project.revision,
            appliedAt: now
        ))
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func abandonTransaction(
        _ command: NovelAbandonPolishTransactionCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        try requireProject(command.projectID, in: document)
        try requireUnusedOperation(command.context.operationID, in: document)
        let branchIndex = try requireBranch(command.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requireContext(
            command.context,
            expectedWorkingRevision: branch.workingRevision,
            branch: branch,
            document: document
        )
        guard branch.lifecycle == .active,
              let transactionIndex = document.polishTransactions.firstIndex(where: {
                  $0.id == command.transactionID &&
                      $0.branchID == branch.id &&
                      ($0.status == .pending ||
                        $0.status == .retryable ||
                        $0.status == .blocked)
              }),
              let candidateIndex = document.candidates.firstIndex(where: {
                  $0.id == document.polishTransactions[transactionIndex].candidateID &&
                      $0.status == .available &&
                      $0.collectedCheckpointID == nil
              }) else {
            throw NovelError.invalidInput("The polish transaction cannot be abandoned.")
        }

        let transaction = document.polishTransactions[transactionIndex]
        let assessedIndices = Set(document.polishAssessments.compactMap {
            $0.transactionID == transaction.id ? $0.attemptIndex : nil
        })
        let unfinishedAttempts = document.polishAttempts.filter {
            $0.transactionID == transaction.id &&
                !assessedIndices.contains($0.attemptIndex)
        }
        guard unfinishedAttempts.count <= 1,
              unfinishedAttempts.first?.attemptIndex == nil ||
                unfinishedAttempts.first?.attemptIndex == transaction.attemptCount - 1 else {
            throw NovelError.invalidDocument([
                "The abandoned polish transaction has incoherent attempt history."
            ])
        }

        var next = document
        if let attempt = unfinishedAttempts.first {
            next.polishAssessments.append(NovelPolishAssessmentRecord(
                transactionID: transaction.id,
                attemptIndex: attempt.attemptIndex,
                runID: attempt.runID,
                result: nil,
                failure: NovelFailure(
                    code: "polish_abandoned",
                    message: "The polish adoption was abandoned.",
                    isRetryable: false
                ),
                createdAt: now
            ))
        }
        next.polishTransactions[transactionIndex].status = .abandoned
        next.polishTransactions[transactionIndex].lastFailure = nil
        next.polishTransactions[transactionIndex].lastFailureAttemptIndex = nil
        next.candidates[candidateIndex].status = .superseded
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.polishTransactionAbandoned(
            projectID: command.projectID,
            branchID: branch.id,
            candidateID: next.candidates[candidateIndex].id,
            transactionID: transaction.id,
            revision: next.project.revision
        )
        next.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: command.context.operationID,
            kind: .abandonPolishTransaction,
            payloadSHA256: try NovelAction.abandonPolishTransaction(command)
                .canonicalPayloadSHA256(),
            outcome: outcome,
            appliedProjectRevision: next.project.revision,
            appliedAt: now
        ))
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func restoreChapterVersion(
        _ command: NovelRestoreChapterVersionCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        try requireProject(command.projectID, in: document)
        try requireUnusedOperation(command.context.operationID, in: document)
        let branchIndex = try requireBranch(command.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requireContext(
            command.context,
            expectedWorkingRevision: command.expectedWorkingRevision,
            branch: branch,
            document: document
        )
        try requireIdleSynchronizedBranch(branch, in: document)
        guard let target = document.chapterVersions.first(where: {
            $0.id == command.targetChapterVersionID
        }), let currentSelection = branch.workingChapterSelections.first(where: {
            $0.chapterID == target.chapterID
        }), let current = document.chapterVersions.first(where: {
            $0.id == currentSelection.versionID
        }) else {
            throw NovelError.invalidInput("The chapter version cannot be restored on this branch.")
        }
        guard current.factCompatibilityID == target.factCompatibilityID else {
            throw NovelError.invalidInput(
                "This chapter version may change story facts and must be restored as a manual edit."
            )
        }
        guard document.chapterVersions.allSatisfy({ $0.id != command.proposedChapterVersionID }),
              document.checkpoints.allSatisfy({ $0.id != command.checkpointID }),
              document.polishTransactions.allSatisfy({
                  $0.proposedChapterVersionID != command.proposedChapterVersionID &&
                      $0.checkpointID != command.checkpointID
              }) else {
            throw NovelError.immutableRecordConflict("chapter restore identity")
        }
        guard let session = document.sessions.first(where: { $0.id == branch.sessionID }) else {
            throw NovelError.sessionNotFound(branch.sessionID)
        }

        let restored = NovelChapterVersionRecord(
            id: command.proposedChapterVersionID,
            chapterID: target.chapterID,
            kind: .restore,
            title: target.title,
            content: target.content,
            factCompatibilityID: target.factCompatibilityID,
            sourceChapterVersionID: target.id,
            sourceCandidateID: nil,
            createdAt: now,
            operationID: command.context.operationID
        )
        var selections = branch.workingChapterSelections
        guard let selectionIndex = selections.firstIndex(where: {
            $0.chapterID == target.chapterID
        }) else {
            throw NovelError.invalidInput("The restore chapter selection disappeared.")
        }
        selections[selectionIndex] = NovelChapterSelection(
            chapterID: target.chapterID,
            versionID: restored.id
        )
        var next = document
        next.chapterVersions.append(restored)
        next.branches[branchIndex].workingChapterSelections = selections
        // Contract v1.1 D-D: restoring an older version rewrites the chapter,
        // so its plot module updates in the same commit and later chapters
        // are marked stale exactly like a manual edit (no gate blind spot).
        let plotSnapshot = try NovelWorkspaceLedger.updatedPlotSnapshot(
            id: NovelStateSnapshotID(),
            replacing: branch.currentStateSnapshotID,
            workingSelections: selections,
            updatedChapterID: target.chapterID,
            updatedTitle: target.title,
            updatedContent: target.content,
            moduleText: nil,
            summaryOverride: nil,
            markLaterStale: !NovelWorkspaceLedger.isFastForward(
                branch: branch,
                chapterID: target.chapterID
            ),
            in: next,
            now: now
        )
        next.stateSnapshots.append(plotSnapshot)
        let checkpoint = NovelBranchCheckpointRecord(
            id: command.checkpointID,
            kind: .restore,
            createdOnBranchID: branch.id,
            parentCheckpointID: branch.headCheckpointID,
            chapterSelections: selections,
            stateSnapshotID: plotSnapshot.id,
            sessionCursor: session.messages.last.map {
                .through(sequence: $0.sequence)
            } ?? .empty,
            branchOverrideRevisionIDs: branch.overrideRevisionIDs,
            sourceCandidateID: nil,
            baseHeadRevision: branch.headRevision,
            operationID: command.context.operationID,
            createdAt: now
        )
        try NovelReducer.appendCheckpoint(
            checkpoint,
            to: &next,
            expectedHeadRevision: branch.headRevision,
            now: now
        )
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.chapterVersionRestored(
            projectID: command.projectID,
            branchID: branch.id,
            checkpointID: checkpoint.id,
            chapterVersionID: restored.id,
            revision: next.project.revision
        )
        next.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: command.context.operationID,
            kind: .restoreChapterVersion,
            payloadSHA256: try NovelAction.restoreChapterVersion(command).canonicalPayloadSHA256(),
            outcome: outcome,
            appliedProjectRevision: next.project.revision,
            appliedAt: now
        ))
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }
}

private extension NovelPolishTransactionReducer {
    static func finalizeCompatible(
        transaction: NovelPendingPolishTransactionRecord,
        transactionIndex: Int,
        document: inout NovelProjectDocumentV1,
        now: Date
    ) throws -> NovelOutcome {
        let branchIndex = try requireBranch(transaction.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requireIdleSynchronizedBranch(branch, in: document)
        guard branch.headCheckpointID == transaction.baseCheckpointID,
              branch.headRevision == transaction.baseHeadRevision else {
            throw NovelError.staleBranchHeadRevision(
                expected: transaction.baseHeadRevision,
                actual: branch.headRevision
            )
        }
        guard branch.workingRevision == transaction.baseWorkingRevision else {
            throw NovelError.invalidInput("The working chapter changed during polish assessment.")
        }
        guard let candidateIndex = document.candidates.firstIndex(where: {
            $0.id == transaction.candidateID &&
                $0.status == .available &&
                $0.collectedCheckpointID == nil
        }), let source = document.chapterVersions.first(where: {
            $0.id == transaction.sourceChapterVersionID
        }), document.chapters.contains(where: {
            $0.id == source.chapterID && $0.discardedAt == nil
        }), NovelDocumentValidator.sha256(source.content) == transaction.sourceContentSHA256,
            NovelDocumentValidator.sha256(document.candidates[candidateIndex].content) ==
                transaction.candidateContentSHA256,
            branch.workingChapterSelections.contains(where: {
                $0.chapterID == source.chapterID && $0.versionID == source.id
            }) else {
            throw NovelError.invalidInput("The polish source changed during assessment.")
        }
        guard document.chapterVersions.allSatisfy({
            $0.id != transaction.proposedChapterVersionID
        }), document.checkpoints.allSatisfy({ $0.id != transaction.checkpointID }) else {
            throw NovelError.immutableRecordConflict("polish adoption identity")
        }

        let candidate = document.candidates[candidateIndex]
        let version = NovelChapterVersionRecord(
            id: transaction.proposedChapterVersionID,
            chapterID: source.chapterID,
            kind: .polish,
            title: source.title,
            content: candidate.content,
            factCompatibilityID: source.factCompatibilityID,
            sourceChapterVersionID: source.id,
            sourceCandidateID: candidate.id,
            createdAt: now,
            operationID: transaction.operationID
        )
        var selections = branch.workingChapterSelections
        guard let selectionIndex = selections.firstIndex(where: {
            $0.chapterID == source.chapterID
        }) else {
            throw NovelError.invalidInput("The polish chapter selection disappeared.")
        }
        selections[selectionIndex] = NovelChapterSelection(
            chapterID: source.chapterID,
            versionID: version.id
        )
        let checkpoint = NovelBranchCheckpointRecord(
            id: transaction.checkpointID,
            kind: .polish,
            createdOnBranchID: branch.id,
            parentCheckpointID: branch.headCheckpointID,
            chapterSelections: selections,
            stateSnapshotID: branch.currentStateSnapshotID,
            sessionCursor: transaction.sessionCursor,
            branchOverrideRevisionIDs: branch.overrideRevisionIDs,
            sourceCandidateID: candidate.id,
            baseHeadRevision: branch.headRevision,
            operationID: transaction.operationID,
            createdAt: now
        )
        document.chapterVersions.append(version)
        document.candidates[candidateIndex].status = .adopted
        document.candidates[candidateIndex].collectedCheckpointID = checkpoint.id
        try NovelReducer.appendCheckpoint(
            checkpoint,
            to: &document,
            expectedHeadRevision: branch.headRevision,
            now: now
        )
        document.polishTransactions[transactionIndex].status = .completed
        document.polishTransactions[transactionIndex].lastFailure = nil
        document.polishTransactions[transactionIndex].lastFailureAttemptIndex = nil
        document.project.revision += 1
        document.project.updatedAt = now
        return .polishCandidateAdopted(
            projectID: document.project.id,
            branchID: branch.id,
            candidateID: candidate.id,
            checkpointID: checkpoint.id,
            chapterVersionID: version.id,
            revision: document.project.revision
        )
    }

    static func requireProject(
        _ projectID: NovelProjectID,
        in document: NovelProjectDocumentV1
    ) throws {
        guard projectID == document.project.id else {
            throw NovelError.projectNotFound(projectID)
        }
    }

    static func requireBranch(
        _ branchID: NovelBranchID,
        in document: NovelProjectDocumentV1
    ) throws -> Int {
        guard let index = document.branches.firstIndex(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        return index
    }

    static func requireContext(
        _ context: NovelMutationContext,
        expectedWorkingRevision: Int64,
        branch: NovelBranchRecord,
        document: NovelProjectDocumentV1
    ) throws {
        guard let projectRevision = context.expectedProjectRevision else {
            throw NovelError.invalidInput("Expected project revision is missing.")
        }
        guard projectRevision == document.project.revision else {
            throw NovelError.staleProjectRevision(
                expected: projectRevision,
                actual: document.project.revision
            )
        }
        guard let headRevision = context.expectedBranchHeadRevision else {
            throw NovelError.invalidInput("Expected branch head revision is missing.")
        }
        guard headRevision == branch.headRevision else {
            throw NovelError.staleBranchHeadRevision(
                expected: headRevision,
                actual: branch.headRevision
            )
        }
        guard expectedWorkingRevision == branch.workingRevision else {
            throw NovelError.invalidInput("The working chapter revision is stale.")
        }
    }

    static func requireIdleSynchronizedBranch(
        _ branch: NovelBranchRecord,
        in document: NovelProjectDocumentV1
    ) throws {
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(branch.id)
        }
        guard branch.activeRunID == nil,
              !document.pendingOperations.contains(where: { $0.branchID == branch.id }) else {
            throw NovelError.projectBusy(document.project.id)
        }
        guard branch.syncStatus == .synchronized else {
            throw NovelError.invalidInput("Synchronize the working manuscript first.")
        }
    }

    static func requireReservedIDs(
        _ command: NovelAdoptPolishCandidateCommand,
        in document: NovelProjectDocumentV1
    ) throws {
        guard document.polishTransactions.allSatisfy({ $0.id != command.transactionID }),
              document.polishTransactions.allSatisfy({
                  $0.proposedChapterVersionID != command.proposedChapterVersionID &&
                      $0.checkpointID != command.checkpointID
              }),
              document.pendingOperations.allSatisfy({ $0.id != command.transactionID }),
              document.chapterVersions.allSatisfy({
                  $0.id != command.proposedChapterVersionID
              }),
              document.pendingOperations.allSatisfy({
                  $0.proposedChapterVersion?.id != command.proposedChapterVersionID
              }),
              document.checkpoints.allSatisfy({ $0.id != command.checkpointID }),
              document.pendingOperations.allSatisfy({
                  $0.proposedCheckpointID != command.checkpointID
              }) else {
            throw NovelError.immutableRecordConflict("polish adoption identity")
        }
    }

    static func requireCurrentTransactionGuard(
        _ transaction: NovelPendingPolishTransactionRecord,
        in document: NovelProjectDocumentV1
    ) throws {
        let branchIndex = try requireBranch(transaction.branchID, in: document)
        let branch = document.branches[branchIndex]
        try requireIdleSynchronizedBranch(branch, in: document)
        guard branch.headCheckpointID == transaction.baseCheckpointID,
              branch.headRevision == transaction.baseHeadRevision else {
            throw NovelError.staleBranchHeadRevision(
                expected: transaction.baseHeadRevision,
                actual: branch.headRevision
            )
        }
        guard branch.workingRevision == transaction.baseWorkingRevision,
              let candidate = document.candidates.first(where: {
                  $0.id == transaction.candidateID &&
                      $0.status == .available &&
                      $0.collectedCheckpointID == nil
              }), let source = document.chapterVersions.first(where: {
                  $0.id == transaction.sourceChapterVersionID
              }), document.chapters.contains(where: {
                  $0.id == source.chapterID && $0.discardedAt == nil
              }), candidate.sourceChapterVersionID == source.id,
            candidate.baseCheckpointID == transaction.baseCheckpointID,
            candidate.baseHeadRevision == transaction.baseHeadRevision,
            NovelDocumentValidator.sha256(source.content) == transaction.sourceContentSHA256,
            NovelDocumentValidator.sha256(candidate.content) == transaction.candidateContentSHA256,
            branch.workingChapterSelections.contains(where: {
                $0.chapterID == source.chapterID && $0.versionID == source.id
            }) else {
            throw NovelError.invalidInput("The polish transaction input is no longer current.")
        }
    }

    static func requireUnusedOperation(
        _ operationID: NovelOperationID,
        in document: NovelProjectDocumentV1
    ) throws {
        guard document.appliedOperations.allSatisfy({ $0.operationID != operationID }),
              document.factAttempts.allSatisfy({ $0.attemptOperationID != operationID }),
              document.pendingOperations.allSatisfy({ $0.operationID != operationID }),
              document.polishTransactions.allSatisfy({ $0.operationID != operationID }),
              document.activeRuns.allSatisfy({ $0.operationID != operationID }) else {
            throw NovelError.idempotencyConflict(operationID)
        }
    }
}
