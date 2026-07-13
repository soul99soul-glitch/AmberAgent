import Foundation

enum NovelCompatibilityLineageValidator {
    private static let zeroCompatibilityID = UUID(
        uuidString: "00000000-0000-0000-0000-000000000000"
    )!

    static func validate(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let committedCount = document.chapterVersions.count
        let pendingVersions = document.pendingOperations.compactMap(\.proposedChapterVersion)
        let versions = document.chapterVersions + pendingVersions
        guard !versions.isEmpty || !document.chapters.isEmpty else { return }

        var versionByID: [NovelChapterVersionID: NovelChapterVersionRecord] = [:]
        var indexByID: [NovelChapterVersionID: Int] = [:]
        for (index, version) in versions.enumerated() {
            if versionByID[version.id] != nil {
                issues.append("Chapter compatibility lineage repeats version \(version.id).")
                continue
            }
            versionByID[version.id] = version
            indexByID[version.id] = index
        }
        let committedIDs = Set(document.chapterVersions.map(\.id))

        for (index, version) in versions.enumerated() {
            if version.factCompatibilityID == zeroCompatibilityID {
                issues.append("Chapter version \(version.id) has a zero fact compatibility ID.")
            }

            guard let sourceID = version.sourceChapterVersionID else {
                if version.kind != .collected {
                    issues.append("Chapter version \(version.id) must have a source version.")
                }
                continue
            }
            guard let source = versionByID[sourceID],
                  let sourceIndex = indexByID[sourceID] else {
                issues.append("Chapter version \(version.id) has a missing source version.")
                continue
            }
            if index >= committedCount, !committedIDs.contains(sourceID) {
                issues.append("Pending chapter version \(version.id) has an uncommitted source version.")
            }
            if sourceIndex >= index {
                issues.append("Chapter version \(version.id) does not point to an earlier source version.")
            }
            if source.chapterID != version.chapterID {
                issues.append("Chapter version \(version.id) points to a source in another chapter.")
            }

            switch version.kind {
            case .collected, .manualEdit:
                if version.factCompatibilityID == source.factCompatibilityID {
                    issues.append("Chapter version \(version.id) must start a new fact compatibility lineage.")
                }
            case .polish, .restore:
                if version.factCompatibilityID != source.factCompatibilityID {
                    issues.append("Chapter version \(version.id) does not preserve source compatibility.")
                }
            }
        }

        validateChapterRoots(document: document, versions: versions, issues: &issues)
        validateAcyclic(versions: versions, versionByID: versionByID, issues: &issues)
        validateCompatibilityGroups(
            versions: versions,
            versionByID: versionByID,
            issues: &issues
        )
        validatePendingSources(document: document, issues: &issues)
    }

    private static func validateChapterRoots(
        document: NovelProjectDocumentV1,
        versions: [NovelChapterVersionRecord],
        issues: inout [String]
    ) {
        let chapterIDs = Set(document.chapters.map(\.id)).union(Set(versions.map(\.chapterID)))
        for chapterID in chapterIDs {
            let roots = versions.filter {
                $0.chapterID == chapterID && $0.sourceChapterVersionID == nil
            }
            if roots.count != 1 {
                issues.append("Chapter \(chapterID) must have exactly one version root.")
            }
        }
    }

    private static func validateAcyclic(
        versions: [NovelChapterVersionRecord],
        versionByID: [NovelChapterVersionID: NovelChapterVersionRecord],
        issues: inout [String]
    ) {
        for version in versions {
            var visited: Set<NovelChapterVersionID> = []
            var current: NovelChapterVersionRecord? = version
            while let node = current {
                guard visited.insert(node.id).inserted else {
                    issues.append("Chapter version lineage contains a cycle at \(node.id).")
                    break
                }
                current = node.sourceChapterVersionID.flatMap { versionByID[$0] }
            }
        }
    }

    private static func validateCompatibilityGroups(
        versions: [NovelChapterVersionRecord],
        versionByID: [NovelChapterVersionID: NovelChapterVersionRecord],
        issues: inout [String]
    ) {
        let groups = Dictionary(grouping: versions, by: \.factCompatibilityID)
        for (compatibilityID, members) in groups {
            if Set(members.map(\.chapterID)).count != 1 {
                issues.append(
                    "Fact compatibility ID \(compatibilityID) is shared across chapters."
                )
            }
            let roots = members.filter { member in
                guard let sourceID = member.sourceChapterVersionID,
                      let source = versionByID[sourceID] else {
                    return true
                }
                return source.factCompatibilityID != compatibilityID
            }
            if roots.count != 1 {
                issues.append(
                    "Fact compatibility ID \(compatibilityID) has a disconnected lineage."
                )
            }
        }
    }

    private static func validatePendingSources(
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for pending in document.pendingOperations {
            guard pending.kind == .collection,
                  let proposed = pending.proposedChapterVersion,
                  let target = pending.collectionTarget else { continue }
            switch target {
            case .appendToChapter(let chapterID):
                let expectedSource = document.checkpoints.first(where: {
                    $0.id == pending.baseCheckpointID
                })?.chapterSelections.first(where: {
                    $0.chapterID == chapterID
                })?.versionID
                if proposed.sourceChapterVersionID != expectedSource {
                    issues.append(
                        "Pending append version \(proposed.id) does not point to its selected source."
                    )
                }
            case .createNextChapter:
                if proposed.sourceChapterVersionID != nil {
                    issues.append("Pending root version \(proposed.id) unexpectedly has a source.")
                }
            }
        }
    }
}
