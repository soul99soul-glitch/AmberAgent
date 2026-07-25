import Foundation

enum NovelPolishDocumentValidator {
    static func validate(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        appendDuplicateIssue(document.polishTransactions.map(\.id), label: "polish transaction", issues: &issues)
        appendDuplicateIssue(
            document.polishTransactions.map(\.operationID),
            label: "polish owner operation",
            issues: &issues
        )
        appendDuplicateIssue(
            document.polishTransactions.map(\.proposedChapterVersionID),
            label: "polish proposed chapter version",
            issues: &issues
        )
        appendDuplicateIssue(
            document.polishTransactions.map(\.checkpointID),
            label: "polish proposed checkpoint",
            issues: &issues
        )
        let attemptsByTransaction = Dictionary(grouping: document.polishAttempts, by: \.transactionID)
        let assessmentsByTransaction = Dictionary(grouping: document.polishAssessments, by: \.transactionID)
        let transactionByID = Dictionary(
            document.polishTransactions.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let polishOwnerIDs = Set(document.polishTransactions.map(\.operationID))
        let conflictingOwnerIDs = Set(document.pendingOperations.map(\.operationID))
            .union(document.factAttempts.map(\.attemptOperationID))
            .union(document.activeRuns.map(\.operationID))
        if !polishOwnerIDs.isDisjoint(with: conflictingOwnerIDs) {
            issues.append("A polish transaction reuses another operation identity.")
        }
        if !Set(document.polishTransactions.map(\.id)).isDisjoint(
            with: Set(document.pendingOperations.map(\.id))
        ) {
            issues.append("Polish and fact transactions share a pending identity.")
        }
        if !Set(document.polishTransactions.map(\.proposedChapterVersionID)).isDisjoint(
            with: Set(document.pendingOperations.compactMap { $0.proposedChapterVersion?.id })
        ) || !Set(document.polishTransactions.map(\.checkpointID)).isDisjoint(
            with: Set(document.pendingOperations.compactMap(\.proposedCheckpointID))
        ) {
            issues.append("Polish and fact transactions share a reserved formal identity.")
        }
        if Set(document.polishTransactions.map(\.candidateID)).count !=
            document.polishTransactions.count {
            issues.append("A polish candidate has multiple adoption transactions.")
        }

        let attemptKeys = document.polishAttempts.map {
            "\($0.transactionID.description):\($0.attemptIndex)"
        }
        appendDuplicateIssue(attemptKeys, label: "polish attempt", issues: &issues)
        appendDuplicateIssue(document.polishAttempts.map(\.runID), label: "polish run", issues: &issues)
        appendDuplicateIssue(
            document.polishAttempts.flatMap { [$0.injectionReceiptID, $0.generationReceiptID] },
            label: "polish receipt",
            issues: &issues
        )
        let assessmentKeys = document.polishAssessments.map {
            "\($0.transactionID.description):\($0.attemptIndex)"
        }
        appendDuplicateIssue(assessmentKeys, label: "polish assessment", issues: &issues)

        for transaction in document.polishTransactions {
            validateTransaction(
                transaction,
                attempts: attemptsByTransaction[transaction.id] ?? [],
                assessments: assessmentsByTransaction[transaction.id] ?? [],
                document: document,
                issues: &issues
            )
        }
        for attempt in document.polishAttempts {
            guard let transaction = transactionByID[attempt.transactionID] else {
                issues.append("Polish attempt has no owning transaction.")
                continue
            }
            if attempt.attemptIndex < 0 ||
                attempt.sourceContentSHA256 != transaction.sourceContentSHA256 ||
                attempt.candidateContentSHA256 != transaction.candidateContentSHA256 {
                issues.append("Polish attempt does not match its transaction input.")
            }
            guard let injection = document.injectionReceipts.first(where: {
                $0.id == attempt.injectionReceiptID
            }), let generation = document.generationReceipts.first(where: {
                $0.id == attempt.generationReceiptID
            }) else {
                issues.append("Polish attempt has incomplete request receipts.")
                continue
            }
            if injection.runID != attempt.runID ||
                generation.runID != attempt.runID ||
                generation.injectionReceiptID != injection.id ||
                injection.factTransaction != nil ||
                generation.factTransaction != nil ||
                // 同 NovelGenerationDocumentValidator:按历史版本集合判定,避免将来
                // 推进 polish-drift 版本时把老项目判成损坏(2026-07-25 事故的同类地雷)。
                !NovelPromptCatalog.acceptedVersions(for: .polishDriftV1)
                    .contains(injection.promptVersion) ||
                generation.promptVersion != injection.promptVersion {
                issues.append("Polish attempt request receipts disagree.")
            }
            validateAttemptReceiptContent(
                attempt: attempt,
                transaction: transaction,
                injection: injection,
                document: document,
                issues: &issues
            )
        }
        for assessment in document.polishAssessments {
            guard document.polishAttempts.contains(where: {
                $0.transactionID == assessment.transactionID &&
                    $0.attemptIndex == assessment.attemptIndex &&
                    $0.runID == assessment.runID
            }) else {
                issues.append("Polish assessment has no matching attempt.")
                continue
            }
            if (assessment.result == nil) == (assessment.failure == nil) {
                issues.append("Polish assessment must contain exactly one terminal result.")
            }
            if let result = assessment.result {
                do {
                    let encoded = try JSONEncoder().encode(result)
                    _ = try NovelStructuredOutputDecoder.decodePolishDrift(from: encoded)
                } catch {
                    issues.append("Polish assessment contains an invalid drift verdict.")
                }
            }
        }
    }

    static func validateTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        appendPrefixIssue(
            current.polishAttempts,
            next.polishAttempts,
            label: "polish attempt",
            issues: &issues
        )
        appendPrefixIssue(
            current.polishAssessments,
            next.polishAssessments,
            label: "polish assessment",
            issues: &issues
        )
        validateAttemptReservation(from: current, to: next, issues: &issues)
        for transaction in current.polishTransactions {
            guard let updated = next.polishTransactions.first(where: { $0.id == transaction.id }) else {
                issues.append("A project transition removed polish transaction \(transaction.id).")
                continue
            }
            if immutableIdentity(of: updated) != immutableIdentity(of: transaction) {
                issues.append("A project transition rewrote polish transaction \(transaction.id).")
            }
            if updated.attemptCount < transaction.attemptCount ||
                !statusCanTransition(from: transaction.status, to: updated.status) {
                issues.append("A project transition moved polish transaction \(transaction.id) backwards.")
            }
        }
    }

    static func validateFactPreservingCheckpoint(
        _ checkpoint: NovelBranchCheckpointRecord,
        parent: NovelBranchCheckpointRecord,
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let parentChapterIDs = parent.chapterSelections.map(\.chapterID)
        let chapterIDs = checkpoint.chapterSelections.map(\.chapterID)
        let parentSelections = Dictionary(
            parent.chapterSelections.map { ($0.chapterID, $0.versionID) },
            uniquingKeysWith: { first, _ in first }
        )
        let selections = Dictionary(
            checkpoint.chapterSelections.map { ($0.chapterID, $0.versionID) },
            uniquingKeysWith: { first, _ in first }
        )
        let changedChapterIDs = chapterIDs.filter {
            parentSelections[$0] != selections[$0]
        }
        guard checkpoint.stateSnapshotID == parent.stateSnapshotID,
              checkpoint.branchOverrideRevisionIDs == parent.branchOverrideRevisionIDs,
              chapterIDs == parentChapterIDs,
              changedChapterIDs.count == 1,
              let chapterID = changedChapterIDs.first,
              let parentVersionID = parentSelections[chapterID],
              let versionID = selections[chapterID],
              let parentVersion = document.chapterVersions.first(where: {
                  $0.id == parentVersionID && $0.chapterID == chapterID
              }),
              let version = document.chapterVersions.first(where: {
                  $0.id == versionID && $0.chapterID == chapterID
              }) else {
            issues.append(
                "Fact-preserving checkpoint \(checkpoint.id) changes more than one story fact surface."
            )
            return
        }

        switch checkpoint.kind {
        case .polish:
            guard let candidateID = checkpoint.sourceCandidateID,
                  let candidate = document.candidates.first(where: { $0.id == candidateID }),
                  let transaction = document.polishTransactions.first(where: {
                      $0.operationID == checkpoint.operationID &&
                          $0.checkpointID == checkpoint.id &&
                          $0.proposedChapterVersionID == version.id &&
                          $0.status == .completed
                  }),
                  transaction.branchID == checkpoint.createdOnBranchID,
                  transaction.candidateID == candidate.id,
                  transaction.sourceChapterVersionID == parentVersion.id,
                  transaction.baseCheckpointID == parent.id,
                  transaction.baseHeadRevision == checkpoint.baseHeadRevision,
                  version.kind == .polish,
                  version.operationID == checkpoint.operationID,
                  version.sourceChapterVersionID == parentVersion.id,
                  version.sourceCandidateID == candidate.id,
                  version.title == parentVersion.title,
                  version.content == candidate.content,
                  version.factCompatibilityID == parentVersion.factCompatibilityID,
                  candidate.sourceChapterVersionID == parentVersion.id else {
                issues.append("Polish checkpoint \(checkpoint.id) has invalid compatibility lineage.")
                return
            }
        case .restore:
            guard version.kind == .restore,
                  version.operationID == checkpoint.operationID,
                  version.sourceCandidateID == nil,
                  let targetID = version.sourceChapterVersionID,
                  let target = document.chapterVersions.first(where: {
                      $0.id == targetID && $0.chapterID == chapterID
                  }),
                  version.title == target.title,
                  version.content == target.content,
                  version.factCompatibilityID == target.factCompatibilityID,
                  parentVersion.factCompatibilityID == target.factCompatibilityID else {
                issues.append("Restore checkpoint \(checkpoint.id) crosses fact compatibility.")
                return
            }
        case .initial, .collection, .manualSync, .discussionArchive:
            break
        }
    }
}

private extension NovelPolishDocumentValidator {
    struct TransactionIdentity: Equatable {
        let operationID: NovelOperationID
        let payloadSHA256: String
        let branchID: NovelBranchID
        let candidateID: NovelCandidateID
        let sourceChapterVersionID: NovelChapterVersionID
        let proposedChapterVersionID: NovelChapterVersionID
        let checkpointID: NovelCheckpointID
        let baseCheckpointID: NovelCheckpointID
        let baseHeadRevision: Int64
        let baseWorkingRevision: Int64
        let sessionCursor: NovelSessionCursor
        let sourceContentSHA256: String
        let candidateContentSHA256: String
        let createdAt: Date
    }

    static func immutableIdentity(
        of transaction: NovelPendingPolishTransactionRecord
    ) -> TransactionIdentity {
        TransactionIdentity(
            operationID: transaction.operationID,
            payloadSHA256: transaction.payloadSHA256,
            branchID: transaction.branchID,
            candidateID: transaction.candidateID,
            sourceChapterVersionID: transaction.sourceChapterVersionID,
            proposedChapterVersionID: transaction.proposedChapterVersionID,
            checkpointID: transaction.checkpointID,
            baseCheckpointID: transaction.baseCheckpointID,
            baseHeadRevision: transaction.baseHeadRevision,
            baseWorkingRevision: transaction.baseWorkingRevision,
            sessionCursor: transaction.sessionCursor,
            sourceContentSHA256: transaction.sourceContentSHA256,
            candidateContentSHA256: transaction.candidateContentSHA256,
            createdAt: transaction.createdAt
        )
    }

    static func validateAttemptReceiptContent(
        attempt: NovelPolishAttemptRecord,
        transaction: NovelPendingPolishTransactionRecord,
        injection: NovelInjectionReceiptRecord,
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        guard let source = document.chapterVersions.first(where: {
            $0.id == transaction.sourceChapterVersionID
        }), let candidate = document.candidates.first(where: {
            $0.id == transaction.candidateID
        }) else {
            return
        }
        let prompt = NovelPromptCatalog.template(for: .polishDriftV1)
        let messages = NovelStructuredModelTask.polishDriftMessages(
            sourceChapter: source.content,
            candidate: candidate.content
        )
        let expected: [(
            kind: NovelInjectionSectionKind,
            label: String,
            reason: NovelInjectionSelectionReason,
            hash: String
        )] = messages.map { message in
            let isSystem = message.role == .system
            return (
                isSystem ? .fixedPrompt : .userInput,
                "\(message.role.rawValue.uppercased()) MESSAGE",
                isSystem ? .requiredPrompt : .requiredUserInput,
                NovelDocumentValidator.sha256(message.content)
            )
        }
        let sectionsMatch = injection.sections.count == expected.count &&
            zip(injection.sections, expected).allSatisfy { actual, expected in
                actual.kind == expected.kind &&
                    actual.label == expected.label &&
                    actual.reason == expected.reason &&
                    actual.contentSHA256 == expected.hash
            }
        let canonical = zip(expected, messages).map { expected, message in
            "[\(expected.label)]\n\(message.content)"
        }.joined(separator: "\n\n")
        if injection.projectID != document.project.id ||
            injection.branchID != transaction.branchID ||
            injection.promptVersion != prompt.version ||
            !sectionsMatch ||
            !injection.materialDecisions.isEmpty ||
            !injection.forceIncludeMaterialIDs.isEmpty ||
            !injection.forceExcludeMaterialIDs.isEmpty ||
            injection.canonicalInputSHA256 != NovelDocumentValidator.sha256(canonical) ||
            attempt.sourceContentSHA256 != NovelDocumentValidator.sha256(source.content) ||
            attempt.candidateContentSHA256 != NovelDocumentValidator.sha256(candidate.content) {
            issues.append("Polish attempt receipt does not prove exact untruncated inputs.")
        }
    }

    static func validateTransaction(
        _ transaction: NovelPendingPolishTransactionRecord,
        attempts: [NovelPolishAttemptRecord],
        assessments: [NovelPolishAssessmentRecord],
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        guard NovelDocumentValidator.isSHA256(transaction.payloadSHA256),
              NovelDocumentValidator.isSHA256(transaction.sourceContentSHA256),
              NovelDocumentValidator.isSHA256(transaction.candidateContentSHA256) else {
            issues.append("Polish transaction \(transaction.id) has invalid hashes.")
            return
        }
        guard document.branches.contains(where: { $0.id == transaction.branchID }),
              let candidate = document.candidates.first(where: {
                  $0.id == transaction.candidateID &&
                      $0.branchID == transaction.branchID &&
                      $0.kind == .polish
              }),
              let source = document.chapterVersions.first(where: {
                  $0.id == transaction.sourceChapterVersionID
              }) else {
            issues.append("Polish transaction \(transaction.id) has missing input records.")
            return
        }
        if candidate.sourceChapterVersionID != source.id ||
            candidate.baseCheckpointID != transaction.baseCheckpointID ||
            candidate.baseHeadRevision != transaction.baseHeadRevision ||
            NovelDocumentValidator.sha256(source.content) != transaction.sourceContentSHA256 ||
            NovelDocumentValidator.sha256(candidate.content) != transaction.candidateContentSHA256 {
            issues.append("Polish transaction \(transaction.id) input evidence disagrees.")
        }
        if transaction.attemptCount != attempts.count ||
            attempts.map(\.attemptIndex).sorted() != Array(0..<attempts.count) {
            issues.append("Polish transaction \(transaction.id) has non-contiguous attempts.")
        }
        if assessments.count > attempts.count {
            issues.append("Polish transaction \(transaction.id) has excess assessments.")
        }
        let attemptByIndex = Dictionary(
            attempts.map { ($0.attemptIndex, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let assessmentByIndex = Dictionary(
            assessments.map { ($0.attemptIndex, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let assessedIndices = Set(assessments.map(\.attemptIndex))
        let unassessedIndices = attempts
            .map(\.attemptIndex)
            .filter { !assessedIndices.contains($0) }
            .sorted()
        if unassessedIndices.count > 1 {
            issues.append("Polish transaction \(transaction.id) has multiple unfinished attempts.")
        }
        for attempt in attempts {
            if attempt.createdAt < transaction.createdAt {
                issues.append("Polish transaction \(transaction.id) has an attempt before creation.")
            }
            if let assessment = assessmentByIndex[attempt.attemptIndex],
               assessment.createdAt < attempt.createdAt {
                issues.append("Polish transaction \(transaction.id) has a verdict before its attempt.")
            }
        }
        if attempts.count > 1 {
            for index in 1..<attempts.count {
                if let previous = attemptByIndex[index - 1],
                   let current = attemptByIndex[index],
                   current.createdAt < previous.createdAt {
                    issues.append("Polish transaction \(transaction.id) attempt time moves backwards.")
                }
            }
        }

        let proposedVersions = document.chapterVersions.filter {
            $0.id == transaction.proposedChapterVersionID
        }
        let proposedCheckpoints = document.checkpoints.filter {
            $0.id == transaction.checkpointID
        }
        let failureOnlyAssessments = assessments.allSatisfy {
            $0.failure != nil && $0.result == nil
        }
        let earlierAssessmentsAreFailures = assessments.allSatisfy {
            $0.attemptIndex == transaction.attemptCount - 1 ||
                ($0.failure != nil && $0.result == nil)
        }
        let owners = document.appliedOperations.filter {
            $0.operationID == transaction.operationID && $0.kind == .adoptPolishCandidate
        }
        let abandonOwners = document.appliedOperations.filter { operation in
            guard operation.kind == .abandonPolishTransaction,
                  case let .polishTransactionAbandoned(
                      _, branchID, candidateID, transactionID, _
                  ) = operation.outcome else {
                return false
            }
            return branchID == transaction.branchID &&
                candidateID == transaction.candidateID &&
                transactionID == transaction.id
        }
        if transaction.status != .abandoned && !abandonOwners.isEmpty {
            issues.append("Active polish transaction \(transaction.id) has an abandon owner.")
        }
        switch transaction.status {
        case .pending:
            let expectedUnassessed = transaction.attemptCount == 0
                ? []
                : [transaction.attemptCount - 1]
            if !owners.isEmpty || transaction.lastFailure != nil ||
                transaction.lastFailureAttemptIndex != nil ||
                unassessedIndices != expectedUnassessed ||
                !failureOnlyAssessments ||
                !proposedVersions.isEmpty || !proposedCheckpoints.isEmpty {
                issues.append("Pending polish transaction \(transaction.id) has incoherent state.")
            }
        case .retryable, .blocked:
            let failureMatches: Bool
            if let failureIndex = transaction.lastFailureAttemptIndex {
                failureMatches = failureIndex == transaction.attemptCount - 1 &&
                    assessmentByIndex[failureIndex]?.failure == transaction.lastFailure &&
                    assessmentByIndex[failureIndex]?.result == nil
            } else {
                failureMatches = transaction.lastFailure != nil
            }
            if !owners.isEmpty || transaction.lastFailure == nil ||
                !failureMatches || !unassessedIndices.isEmpty ||
                assessments.count != attempts.count ||
                !failureOnlyAssessments ||
                !proposedVersions.isEmpty || !proposedCheckpoints.isEmpty {
                issues.append("Failed polish transaction \(transaction.id) has incoherent state.")
            }
        case .incompatible:
            let latest = assessmentByIndex[transaction.attemptCount - 1]
            if owners.count != 1 || transaction.attemptCount == 0 ||
                assessments.count != attempts.count || !unassessedIndices.isEmpty ||
                latest?.result?.compatible != false || latest?.failure != nil ||
                !earlierAssessmentsAreFailures ||
                transaction.lastFailure != nil || transaction.lastFailureAttemptIndex != nil ||
                candidate.status != .superseded ||
                !proposedVersions.isEmpty || !proposedCheckpoints.isEmpty {
                issues.append("Incompatible polish transaction \(transaction.id) is incomplete.")
            }
        case .completed:
            let latest = assessmentByIndex[transaction.attemptCount - 1]
            let formalRecordsMatch = proposedVersions.count == 1 &&
                proposedVersions.first?.kind == .polish &&
                proposedVersions.first?.operationID == transaction.operationID &&
                proposedCheckpoints.count == 1 &&
                proposedCheckpoints.first?.kind == .polish &&
                proposedCheckpoints.first?.operationID == transaction.operationID
            if owners.count != 1 || transaction.attemptCount == 0 ||
                assessments.count != attempts.count || !unassessedIndices.isEmpty ||
                latest?.result?.compatible != true || latest?.failure != nil ||
                !earlierAssessmentsAreFailures ||
                transaction.lastFailure != nil || transaction.lastFailureAttemptIndex != nil ||
                candidate.status != .adopted ||
                candidate.collectedCheckpointID != transaction.checkpointID ||
                !formalRecordsMatch {
                issues.append("Completed polish transaction \(transaction.id) is incomplete.")
            }
        case .abandoned:
            if !owners.isEmpty || abandonOwners.count != 1 ||
                assessments.count != attempts.count || !unassessedIndices.isEmpty ||
                !failureOnlyAssessments || transaction.lastFailure != nil ||
                transaction.lastFailureAttemptIndex != nil ||
                candidate.status != .superseded ||
                !proposedVersions.isEmpty || !proposedCheckpoints.isEmpty {
                issues.append("Abandoned polish transaction \(transaction.id) is incomplete.")
            }
        }
    }

    static func statusCanTransition(
        from current: NovelPolishTransactionStatus,
        to next: NovelPolishTransactionStatus
    ) -> Bool {
        if current == next { return true }
        return switch (current, next) {
        case (.pending, .retryable),
             (.pending, .blocked),
             (.pending, .incompatible),
             (.pending, .completed),
             (.pending, .abandoned),
             (.retryable, .pending),
             (.retryable, .retryable),
             (.retryable, .blocked),
             (.retryable, .incompatible),
             (.retryable, .completed):
             true
        case (.retryable, .abandoned),
             (.blocked, .abandoned):
            true
        default:
            false
        }
    }

    static func validateAttemptReservation(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        guard next.polishAttempts.count != current.polishAttempts.count else { return }
        guard next.polishAttempts.count == current.polishAttempts.count + 1,
              Array(next.polishAttempts.dropLast()) == current.polishAttempts,
              let attempt = next.polishAttempts.last,
              next.injectionReceipts.count == current.injectionReceipts.count + 1,
              next.generationReceipts.count == current.generationReceipts.count + 1,
              let injection = next.injectionReceipts.last,
              let generation = next.generationReceipts.last,
              injection.id == attempt.injectionReceiptID,
              generation.id == attempt.generationReceiptID,
              injection.runID == attempt.runID,
              generation.runID == attempt.runID,
              generation.injectionReceiptID == injection.id else {
            issues.append("A polish attempt reservation did not append one request receipt pair.")
            return
        }

        var expectedProject = current.project
        expectedProject.revision = next.project.revision
        expectedProject.updatedAt = next.project.updatedAt
        var expectedTransactions = current.polishTransactions
        guard let currentIndex = expectedTransactions.firstIndex(where: {
            $0.id == attempt.transactionID
        }), let nextTransaction = next.polishTransactions.first(where: {
            $0.id == attempt.transactionID
        }) else {
            issues.append("A polish attempt reservation lost its transaction owner.")
            return
        }
        expectedTransactions[currentIndex] = nextTransaction
        let isolated = next.schemaVersion == current.schemaVersion &&
            next.project == expectedProject &&
            next.project.revision == current.project.revision + 1 &&
            next.materials == current.materials &&
            next.materialRevisions == current.materialRevisions &&
            next.branches == current.branches &&
            next.sessions == current.sessions &&
            next.chapters == current.chapters &&
            next.chapterVersions == current.chapterVersions &&
            next.events == current.events &&
            next.stateSnapshots == current.stateSnapshots &&
            next.checkpoints == current.checkpoints &&
            next.candidates == current.candidates &&
            Array(next.injectionReceipts.dropLast()) == current.injectionReceipts &&
            Array(next.generationReceipts.dropLast()) == current.generationReceipts &&
            next.factAttempts == current.factAttempts &&
            next.polishTransactions == expectedTransactions &&
            next.polishAssessments == current.polishAssessments &&
            next.pendingOperations == current.pendingOperations &&
            next.activeRuns == current.activeRuns &&
            next.settingProposals == current.settingProposals &&
            next.appliedOperations == current.appliedOperations
        if !isolated {
            issues.append("A polish attempt reservation changed unrelated domain state.")
        }
    }

    static func appendDuplicateIssue<ID: Hashable>(
        _ ids: [ID],
        label: String,
        issues: inout [String]
    ) {
        if Set(ids).count != ids.count {
            issues.append("Duplicate \(label) identity.")
        }
    }

    static func appendPrefixIssue<Element: Equatable>(
        _ current: [Element],
        _ next: [Element],
        label: String,
        issues: inout [String]
    ) {
        guard next.count >= current.count,
              zip(current, next).allSatisfy({ $0 == $1 }) else {
            issues.append("A project transition rewrote immutable \(label) history.")
            return
        }
    }
}
