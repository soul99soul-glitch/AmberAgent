import Foundation

enum NovelWorkspaceLedger {
    static let directoryName = ".amber"
    static let storeFileName = "commits.json"
    static let highlightLimit = 8

    struct Commit: Codable, Equatable, Sendable {
        var id: String
        var parentID: String?
        var createdAt: Date
        var message: String
        var treeSHA256: String
        var files: [String: String]
    }

    struct Store: Codable, Equatable, Sendable {
        var head: String?
        var commits: [Commit]

        var headCommit: Commit? {
            guard let head else { return nil }
            return commits.first { $0.id == head }
        }
    }

    struct Status: Equatable, Sendable {
        var headID: String?
        var message: String?
        var dirtyPaths: [String]
        var plotStale: Bool
        var unresolved: Bool
    }

    static func fileTree(from files: [NovelWorkspaceBackup.File]) -> [String: String] {
        Dictionary(uniqueKeysWithValues: files.compactMap { file -> (String, String)? in
            if file.path == "manifest.yaml" { return nil }
            return (file.path, NovelDocumentValidator.sha256(file.contents))
        })
    }

    static func treeSHA256(_ tree: [String: String]) -> String {
        let payload = tree.keys.sorted().map { key in
            "\(key)\t\(tree[key] ?? "")"
        }.joined(separator: "\n")
        return NovelDocumentValidator.sha256(payload)
    }

    static func makeCommit(
        parentID: String?,
        files: [String: String],
        message: String,
        now: Date
    ) -> Commit {
        let tree = treeSHA256(files)
        return Commit(
            id: String(tree.prefix(16)),
            parentID: parentID,
            createdAt: now,
            message: message,
            treeSHA256: tree,
            files: files
        )
    }

    static func appending(_ commit: Commit, to store: Store) -> Store {
        var next = store
        if !next.commits.contains(where: { $0.id == commit.id }) {
            next.commits.append(commit)
        }
        next.head = commit.id
        return next
    }

    static func status(
        head: Commit?,
        working: [String: String],
        plotStale: Bool,
        unresolved: Bool
    ) -> Status {
        let previous = head?.files ?? [:]
        var dirty = Set(working.keys).union(previous.keys).filter { path in
            working[path] != previous[path]
        }
        if plotStale {
            dirty.insert("plot/")
        }
        return Status(
            headID: head?.id,
            message: head?.message,
            dirtyPaths: dirty.sorted(),
            plotStale: plotStale,
            unresolved: unresolved
        )
    }

    static func liveWorkingSelections(
        branch: NovelBranchRecord,
        in document: NovelProjectDocumentV1
    ) -> [NovelChapterSelection] {
        branch.workingChapterSelections.filter { selection in
            document.chapters.first { $0.id == selection.chapterID }?.discardedAt == nil
        }
    }

    static func isFastForward(branch: NovelBranchRecord, chapterID: NovelChapterID) -> Bool {
        branch.workingChapterSelections.last?.chapterID == chapterID
    }

    static func isFastForwardCollect(
        _ target: NovelCollectionTarget,
        branch: NovelBranchRecord
    ) -> Bool {
        switch target {
        case .createNextChapter:
            return true
        case .appendToChapter(let chapterID), .replaceChapter(let chapterID):
            return isFastForward(branch: branch, chapterID: chapterID)
        }
    }

    static func highlight(title: String, content: String) -> String {
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let excerpt = content
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty } ?? ""
        let clipped = excerpt.count > 40 ? String(excerpt.prefix(40)) + "…" : excerpt
        if trimmedTitle.isEmpty { return clipped }
        if clipped.isEmpty { return trimmedTitle }
        return "\(trimmedTitle)：\(clipped)"
    }

    static func relinkChapterPlots(
        existing: [NovelChapterPlotModule],
        working: [NovelChapterSelection],
        previousHighlights: [String],
        updatedChapterID: NovelChapterID,
        updatedText: String
    ) -> [NovelChapterPlotModule] {
        var byID: [NovelChapterID: String] = [:]
        for module in existing {
            if working.contains(where: { $0.chapterID == module.chapterID }) {
                byID[module.chapterID] = module.text
            }
        }
        if existing.isEmpty {
            seedChapterPlots(
                into: &byID,
                working: working,
                previousHighlights: previousHighlights,
                updatedChapterID: updatedChapterID
            )
        }
        byID[updatedChapterID] = updatedText
        return working.map { selection in
            NovelChapterPlotModule(
                chapterID: selection.chapterID,
                text: byID[selection.chapterID] ?? ""
            )
        }
    }

    private static func seedChapterPlots(
        into byID: inout [NovelChapterID: String],
        working: [NovelChapterSelection],
        previousHighlights: [String],
        updatedChapterID: NovelChapterID
    ) {
        let ids = working.map(\.chapterID)
        guard !ids.isEmpty, !previousHighlights.isEmpty else { return }
        if previousHighlights.count == ids.count {
            for (id, text) in zip(ids, previousHighlights) where id != updatedChapterID {
                byID[id] = text
            }
            return
        }
        let older = Array(previousHighlights.dropFirst())
        for (index, id) in ids.dropLast().reversed().enumerated() where index < older.count {
            if id != updatedChapterID {
                byID[id] = older[index]
            }
        }
    }

    static func plotCurrentBody(summary: String, highlights: [String]) -> String {
        var body = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        let kept = highlights
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        if !kept.isEmpty {
            if !body.isEmpty { body += "\n\n" }
            body += "## 近期已写\n\n" + kept.map { "- \($0)" }.joined(separator: "\n")
        }
        return body
    }

    static func load(from checkout: URL, fileManager: FileManager = .default) -> Store {
        let url = checkout
            .appendingPathComponent(directoryName, isDirectory: true)
            .appendingPathComponent(storeFileName)
        guard fileManager.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url),
              let store = try? decoder.decode(Store.self, from: data) else {
            return Store(head: nil, commits: [])
        }
        return store
    }

    static func save(
        _ store: Store,
        to checkout: URL,
        fileManager: FileManager = .default
    ) throws {
        let directory = checkout.appendingPathComponent(directoryName, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try encoder.encode(store)
        try data.write(
            to: directory.appendingPathComponent(storeFileName),
            options: .atomic
        )
    }

    private static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}

enum NovelWorkspacePlotCommit {
    static func apply(
        to document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        path: String,
        body: String,
        now: Date = Date(),
        chapterPlots: [NovelChapterPlotModule]? = nil
    ) throws -> NovelProjectDocumentV1 {
        guard let branch = document.branches.first(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        guard let old = document.stateSnapshots.first(where: {
            $0.id == branch.currentStateSnapshotID
        }) else {
            throw NovelError.invalidInput("The branch has no current plot snapshot.")
        }
        var summary = old.summary
        var outline = old.branchOutline
        var eventIDs = old.eventIDs
        var highlights = old.recentWrittenHighlights
        var next = document
        if path.hasSuffix("current.md") {
            let split = NovelWorkspaceMarkdown.splitHighlights(body)
            summary = split.body
            if let nextHighlights = split.highlights {
                highlights = nextHighlights
            }
        } else if path.hasSuffix("outline.md") {
            outline = body
        } else if path.hasSuffix("events.md") {
            let lines = NovelWorkspaceMarkdown.bullets(body)
            var nextSequence = (document.events.map(\.sequence).max() ?? -1) + 1
            var created: [NovelEventID] = []
            for line in lines {
                let event = NovelStoryEventRecord(
                    id: NovelEventID(),
                    sequence: nextSequence,
                    kind: "workspace",
                    summary: line,
                    entityReferences: [],
                    createdAt: now
                )
                nextSequence += 1
                next.events.append(event)
                created.append(event.id)
            }
            eventIDs = created
        } else {
            throw NovelError.invalidInput("Unsupported plot path \(path).")
        }
        let operationID = NovelOperationID()
        let snapshotID = NovelStateSnapshotID()
        let checkpointID = NovelCheckpointID()
        next.stateSnapshots.append(
            NovelStateSnapshotRecord(
                id: snapshotID,
                eventIDs: eventIDs,
                summary: summary,
                branchOutline: outline,
                unresolvedEntityNames: old.unresolvedEntityNames,
                createdAt: now,
                settingProposalIDs: old.settingProposalIDs,
                characterIdentityClarifications: old.characterIdentityClarifications,
                recentWrittenHighlights: highlights,
                chapterPlots: chapterPlots ?? old.chapterPlots
            )
        )
        let session = next.sessions.first { $0.id == branch.sessionID }
        let cursor: NovelSessionCursor = session?.messages.last.map {
            .through(sequence: $0.sequence)
        } ?? .empty
        let finalRevision = document.project.revision + 1
        let outcome = NovelOutcome.workspacePlotCommitted(
            projectID: document.project.id,
            branchID: branchID,
            checkpointID: checkpointID,
            revision: finalRevision
        )
        next.appliedOperations.append(
            NovelAppliedOperationRecord(
                operationID: operationID,
                kind: .workspacePlot,
                payloadSHA256: NovelDocumentValidator.sha256(path + "\n" + body),
                outcome: outcome,
                appliedProjectRevision: finalRevision,
                appliedAt: now
            )
        )
        try NovelReducer.appendCheckpoint(
            NovelBranchCheckpointRecord(
                id: checkpointID,
                kind: .manualSync,
                createdOnBranchID: branchID,
                parentCheckpointID: branch.headCheckpointID,
                chapterSelections: branch.workingChapterSelections,
                stateSnapshotID: snapshotID,
                sessionCursor: cursor,
                branchOverrideRevisionIDs: branch.overrideRevisionIDs,
                sourceCandidateID: nil,
                baseHeadRevision: branch.headRevision,
                operationID: operationID,
                createdAt: now
            ),
            to: &next,
            expectedHeadRevision: branch.headRevision,
            advancesWorkingRevision: false,
            now: now
        )
        next.project.revision = finalRevision
        next.project.updatedAt = now
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return next
    }

    static func applyFastForward(
        to document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        chapterTitle: String,
        chapterContent: String,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        guard let branch = document.branches.first(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        guard let chapterID = NovelWorkspaceLedger.liveWorkingSelections(branch: branch, in: document).last?.chapterID else {
            throw NovelError.invalidInput("The branch has no working chapter to update.")
        }
        return try applyChapterModule(
            to: document,
            branchID: branchID,
            chapterID: chapterID,
            chapterTitle: chapterTitle,
            chapterContent: chapterContent,
            now: now
        )
    }

    static func applyChapterModule(
        to document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        chapterID: NovelChapterID,
        chapterTitle: String,
        chapterContent: String,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        guard let branch = document.branches.first(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        guard let old = document.stateSnapshots.first(where: {
            $0.id == branch.currentStateSnapshotID
        }) else {
            throw NovelError.invalidInput("The branch has no current plot snapshot.")
        }
        let working = NovelWorkspaceLedger.liveWorkingSelections(branch: branch, in: document)
        guard working.contains(where: { $0.chapterID == chapterID }) else {
            throw NovelError.invalidInput("The edited chapter is not in the working manuscript.")
        }
        let text = NovelWorkspaceLedger.highlight(
            title: chapterTitle,
            content: chapterContent
        )
        let modules = NovelWorkspaceLedger.relinkChapterPlots(
            existing: old.chapterPlots,
            working: working,
            previousHighlights: old.recentWrittenHighlights,
            updatedChapterID: chapterID,
            updatedText: text
        )
        let highlights = modules.map(\.text).filter { !$0.isEmpty }
        let body = NovelWorkspaceLedger.plotCurrentBody(
            summary: old.summary,
            highlights: highlights
        )
        return try apply(
            to: document,
            branchID: branchID,
            path: "plot/current.md",
            body: body,
            now: now,
            chapterPlots: modules
        )
    }

    static func applyRelink(
        to document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        guard let branch = document.branches.first(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        guard let old = document.stateSnapshots.first(where: {
            $0.id == branch.currentStateSnapshotID
        }) else {
            throw NovelError.invalidInput("The branch has no current plot snapshot.")
        }
        let working = NovelWorkspaceLedger.liveWorkingSelections(branch: branch, in: document)
        let workingIDs = Set(working.map(\.chapterID))
        let modules = old.chapterPlots.filter { workingIDs.contains($0.chapterID) }
        let ordered = working.compactMap { selection in
            modules.first { $0.chapterID == selection.chapterID }
        }
        let highlights = ordered.map(\.text).filter { !$0.isEmpty }
        let body = NovelWorkspaceLedger.plotCurrentBody(
            summary: old.summary,
            highlights: highlights
        )
        return try apply(
            to: document,
            branchID: branchID,
            path: "plot/current.md",
            body: body,
            now: now,
            chapterPlots: ordered
        )
    }
}
