import Foundation

enum NovelCandidateSemantics {
    static func collectionBaseMatches(
        _ candidate: NovelCandidateRecord,
        targetCheckpointID: NovelCheckpointID,
        targetHeadRevision: Int64,
        in document: NovelProjectDocumentV1
    ) -> Bool {
        let sourceMessage = document.sessions
            .first(where: { $0.id == candidate.sessionID })?
            .messages.first(where: { $0.id == candidate.sourceMessageID })
        return collectionBaseMatches(
            candidate,
            targetCheckpointID: targetCheckpointID,
            targetHeadRevision: targetHeadRevision,
            checkpoints: document.checkpoints,
            sourceMessage: sourceMessage
        )
    }

    static func collectionBaseMatches(
        _ candidate: NovelCandidateRecord,
        targetCheckpointID: NovelCheckpointID,
        targetHeadRevision: Int64,
        checkpoints: [NovelBranchCheckpointRecord],
        sourceMessage: NovelSessionMessageRecord?
    ) -> Bool {
        if candidate.baseCheckpointID == targetCheckpointID,
           candidate.baseHeadRevision == targetHeadRevision {
            return true
        }

        guard candidate.kind == .prose,
              candidate.clonedFromCandidateID == nil,
              targetHeadRevision == candidate.baseHeadRevision + 1,
              let sourceMessage,
              let checkpoint = checkpoints.first(where: { $0.id == targetCheckpointID }),
              checkpoint.kind == .manualSync,
              checkpoint.createdOnBranchID == candidate.branchID,
              checkpoint.parentCheckpointID == candidate.baseCheckpointID,
              checkpoint.baseHeadRevision == candidate.baseHeadRevision else {
            return false
        }
        switch checkpoint.sessionCursor {
        case .empty:
            return true
        case .through(let sequence):
            return sourceMessage.sequence > sequence
        }
    }

    static func cloneBaseMatches(
        _ candidate: NovelCandidateRecord,
        currentCheckpointID: NovelCheckpointID,
        in document: NovelProjectDocumentV1
    ) -> Bool {
        let sourceMessage = document.sessions
            .first(where: { $0.id == candidate.sessionID })?
            .messages.first(where: { $0.id == candidate.sourceMessageID })
        return cloneBaseMatches(
            candidate,
            currentCheckpointID: currentCheckpointID,
            checkpoints: document.checkpoints,
            sourceMessage: sourceMessage
        )
    }

    static func cloneBaseMatches(
        _ candidate: NovelCandidateRecord,
        currentCheckpointID: NovelCheckpointID,
        checkpoints: [NovelBranchCheckpointRecord],
        sourceMessage: NovelSessionMessageRecord?
    ) -> Bool {
        if candidate.baseCheckpointID == currentCheckpointID {
            return true
        }
        guard let collectedCheckpointID = candidate.collectedCheckpointID,
              let collection = checkpoints.first(where: {
                  $0.id == collectedCheckpointID &&
                      $0.kind == .collection &&
                      $0.createdOnBranchID == candidate.branchID &&
                      $0.sourceCandidateID == candidate.id
              }),
              collection.parentCheckpointID == currentCheckpointID else {
            return false
        }
        return collectionBaseMatches(
            candidate,
            targetCheckpointID: currentCheckpointID,
            targetHeadRevision: collection.baseHeadRevision,
            checkpoints: checkpoints,
            sourceMessage: sourceMessage
        )
    }

    static func rootCandidateID(
        for candidate: NovelCandidateRecord,
        in candidates: [NovelCandidateRecord]
    ) -> NovelCandidateID? {
        rootCandidateID(
            for: candidate,
            candidatesByID: Dictionary(
                candidates.map { ($0.id, $0) },
                uniquingKeysWith: { first, _ in first }
            )
        )
    }

    static func rootCandidateID(
        for candidate: NovelCandidateRecord,
        candidatesByID: [NovelCandidateID: NovelCandidateRecord]
    ) -> NovelCandidateID? {
        var visited: Set<NovelCandidateID> = []
        var current = candidate
        while let sourceID = current.clonedFromCandidateID {
            guard visited.insert(current.id).inserted,
                  let source = candidatesByID[sourceID] else {
                return nil
            }
            current = source
        }
        return visited.insert(current.id).inserted ? current.id : nil
    }
}
