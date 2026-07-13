import CryptoKit
import Foundation

enum NovelBranchSemantics {
    static func checkpointBelongsToBranch(
        _ checkpointID: NovelCheckpointID,
        branch: NovelBranchRecord,
        document: NovelProjectDocumentV1
    ) -> Bool {
        guard let initial = document.checkpoints.first(where: { $0.kind == .initial }) else {
            return false
        }
        let boundaryID = branch.forkOrigin?.checkpointID ?? initial.id
        let checkpoints = Dictionary(
            document.checkpoints.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var visited: Set<NovelCheckpointID> = []
        var currentID = checkpointID
        while currentID != boundaryID {
            guard visited.insert(currentID).inserted,
                  let checkpoint = checkpoints[currentID],
                  checkpoint.createdOnBranchID == branch.id,
                  let parentID = checkpoint.parentCheckpointID else {
                return false
            }
            currentID = parentID
        }
        return checkpoints[boundaryID] != nil
    }

    static func inheritedMessageID(
        operationID: NovelOperationID,
        sourceID: NovelMessageID
    ) -> NovelMessageID {
        NovelMessageID(deterministicUUID(
            namespace: operationID,
            label: "fork-message",
            source: sourceID.description
        ))
    }

    static func inheritedCandidateID(
        operationID: NovelOperationID,
        sourceID: NovelCandidateID
    ) -> NovelCandidateID {
        NovelCandidateID(deterministicUUID(
            namespace: operationID,
            label: "fork-candidate",
            source: sourceID.description
        ))
    }

    private static func deterministicUUID(
        namespace: NovelOperationID,
        label: String,
        source: String
    ) -> UUID {
        var bytes = Array(SHA256.hash(
            data: Data("\(namespace.description):\(label):\(source)".utf8)
        ).prefix(16))
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }
}

extension NovelReducer {
    static func forkBranch(
        _ command: NovelForkBranchCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let sourceIndex = try requireBranchMutation(
            command.context,
            branchID: command.sourceBranchID,
            in: document
        )
        let source = document.branches[sourceIndex]
        guard source.lifecycle == .active else {
            throw NovelError.branchNotFound(command.sourceBranchID)
        }
        guard source.activeRunID == nil else {
            throw NovelError.projectBusy(command.projectID)
        }
        guard let checkpoint = document.checkpoints.first(where: {
            $0.id == command.checkpointID
        }) else {
            throw NovelError.checkpointNotFound(command.checkpointID)
        }
        guard checkpoint.kind != .initial,
              NovelBranchSemantics.checkpointBelongsToBranch(
                  checkpoint.id,
                  branch: source,
                  document: document
              ) else {
            throw NovelError.invalidInput("The selected checkpoint is not forkable on this branch.")
        }
        guard document.branches.allSatisfy({ $0.id != command.branchID }),
              document.sessions.allSatisfy({ $0.id != command.sessionID }) else {
            throw NovelError.immutableRecordConflict("fork branch or Session identity")
        }
        try requireUnusedBranchOperation(command.context.operationID, in: document)
        let name = try normalizedBranchName(command.name)
        let sourceSession = try requireSession(source.sessionID, in: document)
        let prefix = sessionPrefix(sourceSession, through: checkpoint.sessionCursor)
        let sourceCandidateByID = Dictionary(
            document.candidates.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var inheritedCandidateIDs: [NovelCandidateID: NovelCandidateID] = [:]
        for message in prefix {
            guard let sourceCandidateID = message.candidateID else { continue }
            guard sourceCandidateByID[sourceCandidateID] != nil else {
                throw NovelError.invalidInput(
                    "The forked Session contains an unresolved candidate reference."
                )
            }
            inheritedCandidateIDs[sourceCandidateID] = NovelBranchSemantics.inheritedCandidateID(
                operationID: command.context.operationID,
                sourceID: sourceCandidateID
            )
        }
        let messageIDs = prefix.map {
            NovelBranchSemantics.inheritedMessageID(
                operationID: command.context.operationID,
                sourceID: $0.id
            )
        }
        let existingMessageIDs = Set(document.sessions.flatMap { $0.messages.map(\.id) })
        let existingCandidateIDs = Set(document.candidates.map(\.id))
        guard Set(messageIDs).count == messageIDs.count,
              Set(messageIDs).isDisjoint(with: existingMessageIDs),
              Set(inheritedCandidateIDs.values).count == inheritedCandidateIDs.count,
              Set(inheritedCandidateIDs.values).isDisjoint(with: existingCandidateIDs) else {
            throw NovelError.immutableRecordConflict("forked Session identity")
        }

        let messages = zip(prefix, messageIDs).map { sourceMessage, messageID in
            NovelSessionMessageRecord(
                id: messageID,
                sequence: sourceMessage.sequence,
                role: sourceMessage.role,
                mode: sourceMessage.mode,
                kind: sourceMessage.kind,
                content: sourceMessage.content,
                createdAt: sourceMessage.createdAt,
                runID: nil,
                candidateID: sourceMessage.candidateID.flatMap { inheritedCandidateIDs[$0] }
            )
        }
        let messageIDBySource = Dictionary(
            uniqueKeysWithValues: zip(prefix.map(\.id), messageIDs)
        )
        let inheritedCandidates: [NovelCandidateRecord] = inheritedCandidateIDs.compactMap {
            entry -> NovelCandidateRecord? in
            let (sourceID, inheritedID) = entry
            guard let candidate = sourceCandidateByID[sourceID],
                  let sourceMessageID = messageIDBySource[candidate.sourceMessageID] else {
                return nil
            }
            return NovelCandidateRecord(
                id: inheritedID,
                kind: candidate.kind,
                branchID: command.branchID,
                sessionID: command.sessionID,
                sourceMessageID: sourceMessageID,
                baseCheckpointID: candidate.baseCheckpointID,
                baseHeadRevision: candidate.baseHeadRevision,
                status: .inheritedReadOnly,
                content: candidate.content,
                sourceChapterVersionID: candidate.sourceChapterVersionID,
                collectedCheckpointID: nil,
                createdAt: candidate.createdAt
            )
        }.sorted { $0.sourceMessageID.description < $1.sourceMessageID.description }
        guard inheritedCandidates.count == inheritedCandidateIDs.count else {
            throw NovelError.invalidInput("The forked Session has incomplete candidate history.")
        }

        let session = NovelSessionRecord(
            id: command.sessionID,
            branchID: command.branchID,
            revision: 0,
            messages: messages
        )
        let branch = NovelBranchRecord(
            id: command.branchID,
            name: name,
            sessionID: command.sessionID,
            createdAt: now,
            updatedAt: now,
            forkOrigin: NovelForkOrigin(
                parentBranchID: source.id,
                checkpointID: checkpoint.id
            ),
            headCheckpointID: checkpoint.id,
            currentStateSnapshotID: checkpoint.stateSnapshotID,
            headRevision: 0,
            workingRevision: 0,
            syncStatus: .synchronized,
            lifecycle: .active,
            overrideRevisionIDs: checkpoint.branchOverrideRevisionIDs,
            workingChapterSelections: checkpoint.chapterSelections,
            activeRunID: nil
        )

        var next = document
        next.branches.append(branch)
        next.sessions.append(session)
        next.candidates.append(contentsOf: inheritedCandidates)
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.branchForked(
            projectID: command.projectID,
            sourceBranchID: source.id,
            branchID: branch.id,
            checkpointID: checkpoint.id,
            revision: next.project.revision
        )
        recordBranchOperation(
            command.context,
            kind: .forkBranch,
            payloadSHA256: try NovelAction.forkBranch(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func renameBranch(
        _ command: NovelRenameBranchCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let index = try requireBranchMutation(
            command.context,
            branchID: command.branchID,
            in: document
        )
        guard document.branches[index].lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        try requireUnusedBranchOperation(command.context.operationID, in: document)
        var next = document
        next.branches[index].name = try normalizedBranchName(command.name)
        next.branches[index].updatedAt = now
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.branchRenamed(
            projectID: command.projectID,
            branchID: command.branchID,
            revision: next.project.revision
        )
        recordBranchOperation(
            command.context,
            kind: .renameBranch,
            payloadSHA256: try NovelAction.renameBranch(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func deleteBranch(
        _ command: NovelDeleteBranchCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let index = try requireBranchMutation(
            command.context,
            branchID: command.branchID,
            in: document
        )
        let branch = document.branches[index]
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        guard branch.id != document.project.mainBranchID else {
            throw NovelError.invalidInput("Choose another main branch before deleting this branch.")
        }
        guard document.branches.filter({ $0.lifecycle == .active }).count > 1 else {
            throw NovelError.invalidInput("The only active branch cannot be deleted.")
        }
        try requireIdleBranch(branch, in: document)
        try requireUnusedBranchOperation(command.context.operationID, in: document)

        var next = document
        next.branches[index].lifecycle = .deleted
        next.branches[index].updatedAt = now
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.branchDeleted(
            projectID: command.projectID,
            branchID: command.branchID,
            revision: next.project.revision
        )
        recordBranchOperation(
            command.context,
            kind: .deleteBranch,
            payloadSHA256: try NovelAction.deleteBranch(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func undoBranchHead(
        _ command: NovelUndoBranchHeadCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let index = try requireBranchMutation(
            command.context,
            branchID: command.branchID,
            in: document
        )
        let branch = document.branches[index]
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        try requireIdleBranch(branch, in: document)
        guard branch.syncStatus == .synchronized,
              branch.workingRevision == command.expectedWorkingRevision else {
            throw NovelError.invalidInput("Synchronize the working manuscript before undoing its head.")
        }
        let boundaryID = branch.forkOrigin?.checkpointID ?? document.checkpoints.first(where: {
            $0.kind == .initial
        })?.id
        guard branch.headCheckpointID != boundaryID,
              let current = document.checkpoints.first(where: {
                  $0.id == branch.headCheckpointID
              }), let parentID = current.parentCheckpointID,
              let parent = document.checkpoints.first(where: { $0.id == parentID }),
              NovelBranchSemantics.checkpointBelongsToBranch(
                  parent.id,
                  branch: branch,
                  document: document
              ) else {
            throw NovelError.invalidInput("The branch head cannot move past its history boundary.")
        }
        try requireUnusedBranchOperation(command.context.operationID, in: document)

        var next = document
        next.branches[index].headCheckpointID = parent.id
        next.branches[index].currentStateSnapshotID = parent.stateSnapshotID
        next.branches[index].workingChapterSelections = parent.chapterSelections
        next.branches[index].overrideRevisionIDs = parent.branchOverrideRevisionIDs
        next.branches[index].headRevision += 1
        next.branches[index].workingRevision += 1
        next.branches[index].syncStatus = .synchronized
        next.branches[index].updatedAt = now
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.branchHeadMoved(
            projectID: command.projectID,
            branchID: branch.id,
            fromCheckpointID: current.id,
            toCheckpointID: parent.id,
            headRevision: next.branches[index].headRevision,
            revision: next.project.revision
        )
        recordBranchOperation(
            command.context,
            kind: .undoBranchHead,
            payloadSHA256: try NovelAction.undoBranchHead(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func cloneCandidate(
        _ command: NovelCloneCandidateCommand,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let branchIndex = try requireBranchMutation(
            command.context,
            branchID: command.branchID,
            in: document
        )
        let branch = document.branches[branchIndex]
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(command.branchID)
        }
        try requireIdleBranch(branch, in: document)
        guard branch.syncStatus == .synchronized else {
            throw NovelError.invalidInput("Synchronize the working manuscript before cloning a candidate.")
        }
        guard let source = document.candidates.first(where: {
            $0.id == command.sourceCandidateID &&
                $0.branchID == branch.id &&
                $0.sessionID == branch.sessionID
        }), source.kind == .prose,
        source.status == .collected,
        source.collectedCheckpointID != nil,
        branch.headCheckpointID == source.baseCheckpointID else {
            throw NovelError.invalidInput("Only previously collected prose can be cloned.")
        }
        guard document.candidates.allSatisfy({ $0.id != command.candidateID }) else {
            throw NovelError.immutableRecordConflict("candidate \(command.candidateID)")
        }
        try requireUnusedBranchOperation(command.context.operationID, in: document)

        let clone = NovelCandidateRecord(
            id: command.candidateID,
            kind: source.kind,
            branchID: branch.id,
            sessionID: branch.sessionID,
            sourceMessageID: source.sourceMessageID,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            status: .available,
            content: source.content,
            sourceChapterVersionID: source.sourceChapterVersionID,
            clonedFromCandidateID: source.id,
            collectedCheckpointID: nil,
            createdAt: now
        )
        guard let sessionIndex = document.sessions.firstIndex(where: {
            $0.id == branch.sessionID
        }) else {
            throw NovelError.sessionNotFound(branch.sessionID)
        }
        var next = document
        next.candidates.append(clone)
        next.sessions[sessionIndex].revision += 1
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.candidateCloned(
            projectID: command.projectID,
            branchID: branch.id,
            sourceCandidateID: source.id,
            candidateID: clone.id,
            revision: next.project.revision
        )
        recordBranchOperation(
            command.context,
            kind: .cloneCandidate,
            payloadSHA256: try NovelAction.cloneCandidate(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }
}

private extension NovelReducer {
    static func requireBranchMutation(
        _ context: NovelMutationContext,
        branchID: NovelBranchID,
        in document: NovelProjectDocumentV1
    ) throws -> Int {
        guard let expectedProjectRevision = context.expectedProjectRevision else {
            throw NovelError.invalidInput("Expected project revision is missing.")
        }
        guard expectedProjectRevision == document.project.revision else {
            throw NovelError.staleProjectRevision(
                expected: expectedProjectRevision,
                actual: document.project.revision
            )
        }
        guard let index = document.branches.firstIndex(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        guard let expectedHeadRevision = context.expectedBranchHeadRevision else {
            throw NovelError.invalidInput("Expected branch head revision is missing.")
        }
        guard expectedHeadRevision == document.branches[index].headRevision else {
            throw NovelError.staleBranchHeadRevision(
                expected: expectedHeadRevision,
                actual: document.branches[index].headRevision
            )
        }
        return index
    }

    static func requireIdleBranch(
        _ branch: NovelBranchRecord,
        in document: NovelProjectDocumentV1
    ) throws {
        guard branch.activeRunID == nil,
              !document.pendingOperations.contains(where: { $0.branchID == branch.id }),
              !document.polishTransactions.contains(where: {
                  $0.branchID == branch.id &&
                      ($0.status == .pending || $0.status == .retryable)
              }) else {
            throw NovelError.projectBusy(document.project.id)
        }
    }

    static func requireSession(
        _ id: NovelSessionID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelSessionRecord {
        guard let session = document.sessions.first(where: { $0.id == id }) else {
            throw NovelError.sessionNotFound(id)
        }
        return session
    }

    static func sessionPrefix(
        _ session: NovelSessionRecord,
        through cursor: NovelSessionCursor
    ) -> [NovelSessionMessageRecord] {
        switch cursor {
        case .empty:
            []
        case .through(let sequence):
            session.messages.filter { $0.sequence <= sequence }
        }
    }

    static func requireUnusedBranchOperation(
        _ operationID: NovelOperationID,
        in document: NovelProjectDocumentV1
    ) throws {
        let used = document.appliedOperations.contains { $0.operationID == operationID } ||
            document.factAttempts.contains { $0.attemptOperationID == operationID } ||
            document.pendingOperations.contains { $0.operationID == operationID } ||
            document.polishTransactions.contains { $0.operationID == operationID } ||
            document.activeRuns.contains { $0.operationID == operationID }
        guard !used else { throw NovelError.idempotencyConflict(operationID) }
    }

    static func normalizedBranchName(_ value: String) throws -> String {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            throw NovelError.invalidInput("Branch name cannot be empty.")
        }
        return normalized
    }

    static func recordBranchOperation(
        _ context: NovelMutationContext,
        kind: NovelOperationKind,
        payloadSHA256: String,
        outcome: NovelOutcome,
        in document: inout NovelProjectDocumentV1,
        now: Date
    ) {
        document.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: context.operationID,
            kind: kind,
            payloadSHA256: payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: document.project.revision,
            appliedAt: now
        ))
    }
}
