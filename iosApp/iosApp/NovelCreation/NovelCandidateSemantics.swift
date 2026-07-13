import Foundation

enum NovelCandidateSemantics {
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
