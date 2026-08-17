import CryptoKit
import Foundation

/// On-disk layout for a novel project package (directory) that stores each
/// document section as a content-addressed blob.
///
/// Layout:
/// ```
/// projects/{projectID}/
///   layout.json
///   previous-layout.json   // optional, for restore-previous
///   blobs/{sha256}.json
/// ```
///
/// The in-memory domain model remains a full `NovelProjectDocumentV1`. This type
/// only changes how the repository persists and reloads it so tool writes that
/// touch a few small sections do not re-encode multi‑MB injection receipts.
enum NovelProjectShardedStorage {
    static let layoutSchemaVersion = 2
    static let layoutFileName = "layout.json"
    static let previousLayoutFileName = "previous-layout.json"
    static let blobsDirectoryName = "blobs"

    struct LayoutV2: Codable, Equatable, Sendable {
        struct SectionRef: Codable, Equatable, Sendable {
            let digest: String
            let byteCount: Int
        }

        let schemaVersion: Int
        let documentSchemaVersion: Int
        let projectID: NovelProjectID
        let revision: Int64
        let updatedAt: TimeInterval
        let sections: [String: SectionRef]
    }

    enum SectionKey: String, CaseIterable, Sendable {
        case project
        case materials
        case materialRevisions
        case branches
        case sessions
        case chapters
        case chapterVersions
        case events
        case stateSnapshots
        case checkpoints
        case candidates
        case injectionReceipts
        case generationReceipts
        case factAttempts
        case polishTransactions
        case polishAttempts
        case polishAssessments
        case pendingOperations
        case activeRuns
        case settingProposals
        case chapterPlans
        case upcomingArcs
        case appliedOperations
    }

    /// Cached encode of one section so unchanged append-only payloads are not
    /// re-encoded on every tool write.
    struct SectionCacheEntry: Sendable {
        let fingerprint: String
        let digest: String
        let data: Data
    }

    typealias SectionCache = [String: SectionCacheEntry]

    /// Files in the markdown checkout. Project revision and branch
    /// `activeRunID` change on every generation persist — those must not
    /// rebuild 31 chapters.
    static let checkoutAffectingSections: Set<SectionKey> = [
        .materials,
        .materialRevisions,
        .chapters,
        .chapterVersions,
        .events,
        .stateSnapshots,
        .checkpoints,
        .settingProposals,
    ]

    static func checkoutSidecarNeedsRefresh(
        previous: SectionCache?,
        next: SectionCache
    ) -> Bool {
        guard let previous else { return true }
        return checkoutAffectingSections.contains { key in
            previous[key.rawValue]?.digest != next[key.rawValue]?.digest
        }
    }

    // MARK: - Paths

    static func packageDirectory(projectDirectory: URL, projectID: NovelProjectID) -> URL {
        projectDirectory.appendingPathComponent(projectID.description, isDirectory: true)
    }

    static func layoutURL(in packageDirectory: URL) -> URL {
        packageDirectory.appendingPathComponent(layoutFileName)
    }

    static func previousLayoutURL(in packageDirectory: URL) -> URL {
        packageDirectory.appendingPathComponent(previousLayoutFileName)
    }

    static func blobsDirectory(in packageDirectory: URL) -> URL {
        packageDirectory.appendingPathComponent(blobsDirectoryName, isDirectory: true)
    }

    static func blobURL(in packageDirectory: URL, digest: String) -> URL {
        blobsDirectory(in: packageDirectory).appendingPathComponent("\(digest).json")
    }

    static func isPackage(at url: URL, fileManager: FileManager) -> Bool {
        var isDir: ObjCBool = false
        guard fileManager.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue else {
            return false
        }
        return fileManager.fileExists(atPath: layoutURL(in: url).path)
            || fileManager.fileExists(atPath: previousLayoutURL(in: url).path)
    }

    // MARK: - Encode / decode document sections

    static func encodeSection(
        _ key: SectionKey,
        document: NovelProjectDocumentV1,
        encoder: JSONEncoder
    ) throws -> Data {
        switch key {
        case .project: try encoder.encode(document.project)
        case .materials: try encoder.encode(document.materials)
        case .materialRevisions: try encoder.encode(document.materialRevisions)
        case .branches: try encoder.encode(document.branches)
        case .sessions: try encoder.encode(document.sessions)
        case .chapters: try encoder.encode(document.chapters)
        case .chapterVersions: try encoder.encode(document.chapterVersions)
        case .events: try encoder.encode(document.events)
        case .stateSnapshots: try encoder.encode(document.stateSnapshots)
        case .checkpoints: try encoder.encode(document.checkpoints)
        case .candidates: try encoder.encode(document.candidates)
        case .injectionReceipts: try encoder.encode(document.injectionReceipts)
        case .generationReceipts: try encoder.encode(document.generationReceipts)
        case .factAttempts: try encoder.encode(document.factAttempts)
        case .polishTransactions: try encoder.encode(document.polishTransactions)
        case .polishAttempts: try encoder.encode(document.polishAttempts)
        case .polishAssessments: try encoder.encode(document.polishAssessments)
        case .pendingOperations: try encoder.encode(document.pendingOperations)
        case .activeRuns: try encoder.encode(document.activeRuns)
        case .settingProposals: try encoder.encode(document.settingProposals)
        case .chapterPlans: try encoder.encode(document.chapterPlans)
        case .upcomingArcs: try encoder.encode(document.upcomingArcs)
        case .appliedOperations: try encoder.encode(document.appliedOperations)
        }
    }

    static func fingerprint(for key: SectionKey, document: NovelProjectDocumentV1) -> String {
        // Sound for append-only / small-mutable sections used by domain reducers.
        // If a future mutation rewrites past elements without changing these marks,
        // fall back to a full re-encode path by invalidating the repository cache.
        switch key {
        case .project:
            return "rev=\(document.project.revision)|cfg=\(document.project.configRevision)|u=\(document.project.updatedAt.timeIntervalSince1970)"
        case .materials:
            return "n=\(document.materials.count)|last=\(document.materials.last?.id.description ?? "-")"
        case .materialRevisions:
            return appendOnlyFingerprint(
                count: document.materialRevisions.count,
                lastID: document.materialRevisions.last?.id.description
            )
        case .branches:
            // Working selections / activeRun / sync flip often; include full cheap marks.
            let marks = document.branches.map {
                let selections = $0.workingChapterSelections
                    .map { "\($0.chapterID):\($0.versionID)" }
                    .joined(separator: ";")
                let overrides = $0.overrideRevisionIDs.map(\.description).joined(separator: ";")
                return [
                    $0.id.description,
                    "\($0.headRevision)",
                    "\($0.workingRevision)",
                    $0.syncStatus.rawValue,
                    $0.lifecycle.rawValue,
                    $0.headCheckpointID.description,
                    $0.currentStateSnapshotID.description,
                    $0.activeRunID?.description ?? "-",
                    "\($0.updatedAt.timeIntervalSince1970)",
                    selections,
                    overrides,
                ].joined(separator: ":")
            }.joined(separator: ",")
            return marks
        case .sessions:
            let session = document.sessions.first
            let lastMessage = session?.messages.last
            let archives = session?.discussionArchives?.count ?? 0
            return "n=\(document.sessions.count)|rev=\(session?.revision ?? -1)|msg=\(session?.messages.count ?? 0)|last=\(lastMessage?.id.description ?? "-")|arc=\(archives)"
        case .chapters:
            let marks = document.chapters.map {
                "\($0.id):\($0.discardedAt?.timeIntervalSince1970 ?? -1)"
            }.joined(separator: ",")
            return marks
        case .chapterVersions:
            return appendOnlyFingerprint(
                count: document.chapterVersions.count,
                lastID: document.chapterVersions.last?.id.description
            )
        case .events:
            return appendOnlyFingerprint(
                count: document.events.count,
                lastID: document.events.last.map { "\($0.id):\($0.sequence)" }
            )
        case .stateSnapshots:
            return appendOnlyFingerprint(
                count: document.stateSnapshots.count,
                lastID: document.stateSnapshots.last?.id.description
            )
        case .checkpoints:
            return appendOnlyFingerprint(
                count: document.checkpoints.count,
                lastID: document.checkpoints.last?.id.description
            )
        case .candidates:
            let marks = document.candidates.map { "\($0.id):\($0.status.rawValue)" }.joined(separator: ",")
            return "n=\(document.candidates.count)|\(marks)"
        case .injectionReceipts:
            return appendOnlyFingerprint(
                count: document.injectionReceipts.count,
                lastID: document.injectionReceipts.last?.id.description
            )
        case .generationReceipts:
            return appendOnlyFingerprint(
                count: document.generationReceipts.count,
                lastID: document.generationReceipts.last?.id.description
            )
        case .factAttempts:
            return appendOnlyFingerprint(
                count: document.factAttempts.count,
                lastID: document.factAttempts.last.map {
                    "\($0.pendingID):\($0.attemptOperationID)"
                }
            )
        case .polishTransactions:
            let marks = document.polishTransactions.map {
                "\($0.id):\($0.status.rawValue)"
            }.joined(separator: ",")
            return "n=\(document.polishTransactions.count)|\(marks)"
        case .polishAttempts:
            return appendOnlyFingerprint(
                count: document.polishAttempts.count,
                lastID: document.polishAttempts.last.map {
                    "\($0.transactionID):\($0.attemptIndex)"
                }
            )
        case .polishAssessments:
            return appendOnlyFingerprint(
                count: document.polishAssessments.count,
                lastID: document.polishAssessments.last.map {
                    "\($0.transactionID):\($0.attemptIndex)"
                }
            )
        case .pendingOperations:
            let marks = document.pendingOperations.map { "\($0.id):\($0.status.rawValue)" }
                .joined(separator: ",")
            return "n=\(document.pendingOperations.count)|\(marks)"
        case .activeRuns:
            let marks = document.activeRuns.map {
                "\($0.id):\($0.status.rawValue):\($0.partialContent.count):\($0.terminalAt?.timeIntervalSince1970 ?? -1)"
            }.joined(separator: ",")
            return "n=\(document.activeRuns.count)|\(marks)"
        case .settingProposals:
            let marks = document.settingProposals.map {
                "\($0.id):\($0.isResolved):\($0.supersededByRunID?.description ?? "-")"
            }.joined(separator: ",")
            return "n=\(document.settingProposals.count)|\(marks)"
        case .chapterPlans:
            let marks = document.chapterPlans.map { "\($0.id):\($0.status.rawValue):\($0.updatedAt.timeIntervalSince1970)" }
                .joined(separator: ",")
            return "n=\(document.chapterPlans.count)|\(marks)"
        case .upcomingArcs:
            let marks = document.upcomingArcs.map { "\($0.branchID):\($0.beats.count):\($0.updatedAt.timeIntervalSince1970)" }
                .joined(separator: ",")
            return "n=\(document.upcomingArcs.count)|\(marks)"
        case .appliedOperations:
            return appendOnlyFingerprint(
                count: document.appliedOperations.count,
                lastID: document.appliedOperations.last?.operationID.description
            )
        }
    }

    private static func appendOnlyFingerprint(count: Int, lastID: String?) -> String {
        "n=\(count)|last=\(lastID ?? "-")"
    }

    static func digest(for data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Heavy, append-mostly sections where fingerprint skip is worth the risk.
    /// Small mutable sections (branches/sessions/activeRuns/…) always re-encode so
    /// a missed mark cannot leave `activeRunID` or similar out of date on disk.
    static func usesEncodeCache(_ key: SectionKey) -> Bool {
        switch key {
        case .injectionReceipts,
             .generationReceipts,
             .stateSnapshots,
             .events,
             .chapterVersions,
             .materialRevisions,
             .appliedOperations,
             .checkpoints,
             .polishAttempts,
             .polishAssessments,
             .factAttempts:
            return true
        case .project,
             .materials,
             .branches,
             .sessions,
             .chapters,
             .candidates,
             .polishTransactions,
             .pendingOperations,
             .activeRuns,
             .settingProposals,
             .chapterPlans,
             .upcomingArcs:
            return false
        }
    }

    /// Encode every section, reusing cached payloads when fingerprints match.
    static func prepareSections(
        document: NovelProjectDocumentV1,
        encoder: JSONEncoder,
        cache: SectionCache?
    ) throws -> (layout: LayoutV2, sections: SectionCache, totalBytes: Int) {
        var nextCache: SectionCache = [:]
        var totalBytes = 0
        var sectionRefs: [String: LayoutV2.SectionRef] = [:]

        for key in SectionKey.allCases {
            let fingerprint = fingerprint(for: key, document: document)
            let entry: SectionCacheEntry
            if usesEncodeCache(key),
               let cached = cache?[key.rawValue],
               cached.fingerprint == fingerprint {
                entry = cached
            } else {
                let data = try encodeSection(key, document: document, encoder: encoder)
                entry = SectionCacheEntry(
                    fingerprint: fingerprint,
                    digest: digest(for: data),
                    data: data
                )
            }
            nextCache[key.rawValue] = entry
            totalBytes += entry.data.count
            sectionRefs[key.rawValue] = LayoutV2.SectionRef(
                digest: entry.digest,
                byteCount: entry.data.count
            )
        }

        let layout = LayoutV2(
            schemaVersion: layoutSchemaVersion,
            documentSchemaVersion: NovelProjectDocumentV1.currentSchemaVersion,
            projectID: document.project.id,
            revision: document.project.revision,
            updatedAt: document.project.updatedAt.timeIntervalSince1970,
            sections: sectionRefs
        )
        return (layout, nextCache, totalBytes)
    }

    static func writePackage(
        document: NovelProjectDocumentV1,
        packageDirectory: URL,
        encoder: JSONEncoder,
        fileManager: FileManager,
        cache: SectionCache?
    ) throws -> SectionCache {
        let prepared = try prepareSections(document: document, encoder: encoder, cache: cache)
        guard prepared.totalBytes <= NovelFileProjectRepository.maximumProjectBytes else {
            throw NovelError.invalidDocument(["Project exceeds the 100 MB storage limit."])
        }

        try fileManager.createDirectory(
            at: packageDirectory,
            withIntermediateDirectories: true
        )
        let blobs = blobsDirectory(in: packageDirectory)
        try fileManager.createDirectory(at: blobs, withIntermediateDirectories: true)

        // Preserve previous layout for restorePrevious before overwriting current.
        let layoutURL = layoutURL(in: packageDirectory)
        let previousLayoutURL = previousLayoutURL(in: packageDirectory)
        if fileManager.fileExists(atPath: layoutURL.path) {
            if fileManager.fileExists(atPath: previousLayoutURL.path) {
                try fileManager.removeItem(at: previousLayoutURL)
            }
            try fileManager.copyItem(at: layoutURL, to: previousLayoutURL)
        }

        for key in SectionKey.allCases {
            guard let entry = prepared.sections[key.rawValue] else { continue }
            let blob = blobURL(in: packageDirectory, digest: entry.digest)
            if fileManager.fileExists(atPath: blob.path) { continue }
            let temp = blobs.appendingPathComponent(".\(UUID().uuidString).tmp")
            try entry.data.write(to: temp, options: [])
            if fileManager.fileExists(atPath: blob.path) {
                try? fileManager.removeItem(at: temp)
            } else {
                try fileManager.moveItem(at: temp, to: blob)
            }
        }

        let layoutData = try encoder.encode(prepared.layout)
        let layoutTemp = packageDirectory.appendingPathComponent(".\(UUID().uuidString).layout.tmp")
        try layoutData.write(to: layoutTemp, options: [])
        if fileManager.fileExists(atPath: layoutURL.path) {
            _ = try fileManager.replaceItemAt(
                layoutURL,
                withItemAt: layoutTemp,
                backupItemName: nil,
                options: []
            )
        } else {
            try fileManager.moveItem(at: layoutTemp, to: layoutURL)
        }

        try garbageCollectBlobs(
            in: packageDirectory,
            fileManager: fileManager,
            keep: referencedDigests(packageDirectory: packageDirectory, fileManager: fileManager)
        )

        return prepared.sections
    }

    static func loadDocument(
        packageDirectory: URL,
        projectID: NovelProjectID,
        decoder: JSONDecoder,
        fileManager: FileManager,
        layoutFileName: String = layoutFileName
    ) throws -> (document: NovelProjectDocumentV1, cache: SectionCache) {
        let layoutData = try Data(
            contentsOf: packageDirectory.appendingPathComponent(layoutFileName),
            options: [.mappedIfSafe]
        )
        let layout = try decoder.decode(LayoutV2.self, from: layoutData)
        guard layout.schemaVersion == layoutSchemaVersion else {
            throw NovelError.unsupportedSchema(layout.schemaVersion)
        }
        guard layout.documentSchemaVersion == NovelProjectDocumentV1.currentSchemaVersion else {
            throw NovelError.unsupportedSchema(layout.documentSchemaVersion)
        }
        guard layout.projectID == projectID else {
            throw NovelError.corruptedProject(
                projectID: projectID,
                details: "Sharded layout project ID does not match package directory."
            )
        }

        var cache: SectionCache = [:]
        var sectionData: [SectionKey: Data] = [:]
        for key in SectionKey.allCases {
            guard let ref = layout.sections[key.rawValue] else {
                throw NovelError.corruptedProject(
                    projectID: projectID,
                    details: "Sharded layout is missing section \(key.rawValue)."
                )
            }
            let data = try Data(
                contentsOf: blobURL(in: packageDirectory, digest: ref.digest),
                options: [.mappedIfSafe]
            )
            guard data.count == ref.byteCount else {
                throw NovelError.corruptedProject(
                    projectID: projectID,
                    details: "Sharded blob size mismatch for \(key.rawValue)."
                )
            }
            sectionData[key] = data
            // Fingerprint filled after full document assembly.
            cache[key.rawValue] = SectionCacheEntry(
                fingerprint: "",
                digest: ref.digest,
                data: data
            )
        }

        let document = try assembleDocument(sectionData: sectionData, decoder: decoder)
        guard document.project.id == projectID else {
            throw NovelError.corruptedProject(
                projectID: projectID,
                details: "Sharded document project ID does not match package directory."
            )
        }

        // Refresh fingerprints for the encode cache.
        for key in SectionKey.allCases {
            if let entry = cache[key.rawValue] {
                cache[key.rawValue] = SectionCacheEntry(
                    fingerprint: fingerprint(for: key, document: document),
                    digest: entry.digest,
                    data: entry.data
                )
            }
        }
        return (document, cache)
    }

    private static func assembleDocument(
        sectionData: [SectionKey: Data],
        decoder: JSONDecoder
    ) throws -> NovelProjectDocumentV1 {
        // Rebuild a monofile-shaped JSON envelope by splicing section payloads
        // that were encoded with the same JSONEncoder strategy. Avoids
        // JSONSerialization (which can rewrite numbers/dates) and keeps
        // Codable synthesis for the full document type.
        var parts: [String] = [
            "\"schemaVersion\":\(NovelProjectDocumentV1.currentSchemaVersion)"
        ]
        for key in SectionKey.allCases {
            guard let data = sectionData[key],
                  let text = String(data: data, encoding: .utf8) else {
                throw NovelError.corruptedProject(
                    projectID: NovelProjectID(),
                    details: "Missing section data \(key.rawValue)."
                )
            }
            parts.append("\"\(key.rawValue)\":\(text)")
        }
        let envelope = Data("{\(parts.joined(separator: ","))}".utf8)
        return try decoder.decode(NovelProjectDocumentV1.self, from: envelope)
    }

    private static func referencedDigests(
        packageDirectory: URL,
        fileManager: FileManager
    ) -> Set<String> {
        var digests: Set<String> = []
        let decoder = JSONDecoder()
        for name in [layoutFileName, previousLayoutFileName] {
            let url = packageDirectory.appendingPathComponent(name)
            guard fileManager.fileExists(atPath: url.path),
                  let data = try? Data(contentsOf: url),
                  let layout = try? decoder.decode(LayoutV2.self, from: data) else {
                continue
            }
            for ref in layout.sections.values {
                digests.insert(ref.digest)
            }
        }
        return digests
    }

    private static func garbageCollectBlobs(
        in packageDirectory: URL,
        fileManager: FileManager,
        keep: Set<String>
    ) throws {
        let blobs = blobsDirectory(in: packageDirectory)
        guard fileManager.fileExists(atPath: blobs.path) else { return }
        let urls = try fileManager.contentsOfDirectory(
            at: blobs,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )
        for url in urls where url.pathExtension == "json" {
            let digest = url.deletingPathExtension().lastPathComponent
            if !keep.contains(digest) {
                try? fileManager.removeItem(at: url)
            }
        }
    }
}

