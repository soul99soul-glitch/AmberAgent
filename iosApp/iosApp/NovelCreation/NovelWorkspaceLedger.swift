import Foundation

enum NovelWorkspaceLedger {
    static let directoryName = ".amber"
    static let storeFileName = "commits.json"
    static let highlightLimit = 8

    /// Contract v1.1 D-D: editing a middle chapter leaves later chapters'
    /// plot modules stale; until that is resolved (accept-as-canonical,
    /// fork, or rewriting the affected chapters) forward progress is gated.
    static let unresolvedPlotGateMessage =
        "改过前面的章节后，后面的剧情指针还没解开。请先在项目工作区确认无碍、Fork，或重写后续章节，再继续写后续内容。"

    /// True when the branch's current plot snapshot still carries stale
    /// chapter modules — the D-D unresolved state.
    static func hasUnresolvedChapterPlots(
        branchID: NovelBranchID,
        in document: NovelProjectDocumentV1
    ) -> Bool {
        guard let branch = document.branches.first(where: { $0.id == branchID }),
              let snapshot = document.stateSnapshots.first(where: {
                  $0.id == branch.currentStateSnapshotID
              }) else {
            return false
        }
        return snapshot.hasStaleChapterPlots
    }

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
        /// Branch id → checkpoint id. Thin-git pointers; `head` mirrors the main branch.
        var heads: [String: String]
        var commits: [Commit]

        init(head: String? = nil, heads: [String: String] = [:], commits: [Commit] = []) {
            self.head = head
            self.heads = heads
            self.commits = commits
        }

        var headCommit: Commit? {
            guard let head else { return nil }
            return commits.first { $0.id == head }
        }

        private enum CodingKeys: String, CodingKey {
            case head
            case heads
            case commits
        }

        init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            head = try container.decodeIfPresent(String.self, forKey: .head)
            heads = try container.decodeIfPresent([String: String].self, forKey: .heads) ?? [:]
            commits = try container.decodeIfPresent([Commit].self, forKey: .commits) ?? []
        }

        func encode(to encoder: any Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encodeIfPresent(head, forKey: .head)
            if !heads.isEmpty {
                try container.encode(heads, forKey: .heads)
            }
            try container.encode(commits, forKey: .commits)
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
        id: String,
        parentID: String?,
        files: [String: String],
        message: String,
        now: Date
    ) -> Commit {
        let tree = treeSHA256(files)
        return Commit(
            id: id,
            parentID: parentID,
            createdAt: now,
            message: message,
            treeSHA256: tree,
            files: files
        )
    }

    static func commitMessage(for kind: NovelCheckpointKind?) -> String {
        switch kind {
        case .initial: "初始"
        case .collection: "收录"
        case .manualSync: "剧情指针"
        case .discussionArchive: "讨论归档"
        case .identityClarification: "人物说明"
        case .polish: "润色"
        case .restore: "还原"
        case nil: "提交"
        }
    }

    static func branchTree(
        branch: NovelBranchRecord,
        in document: NovelProjectDocumentV1
    ) -> [String: String] {
        var tree: [String: String] = [:]
        for selection in liveWorkingSelections(branch: branch, in: document) {
            guard let version = document.chapterVersions.first(where: {
                $0.id == selection.versionID && $0.chapterID == selection.chapterID
            }) else { continue }
            tree["chapters/\(selection.chapterID)"] = NovelDocumentValidator.sha256(
                version.title + "\n" + version.content
            )
        }
        if let snapshot = document.stateSnapshots.first(where: {
            $0.id == branch.currentStateSnapshotID
        }) {
            tree["plot/summary"] = NovelDocumentValidator.sha256(snapshot.summary)
            for module in snapshot.chapterPlots {
                tree["plot/\(module.chapterID)"] = NovelDocumentValidator.sha256(
                    (module.stale ? "1\n" : "0\n") + module.text
                )
            }
        }
        return tree
    }

    static func record(
        _ document: NovelProjectDocumentV1,
        into store: Store
    ) -> Store {
        var next = store
        var heads = next.heads
        for branch in document.branches where branch.lifecycle == .active {
            let checkpoint = document.checkpoints.first { $0.id == branch.headCheckpointID }
            let id = branch.headCheckpointID.description
            heads[branch.id.description] = id
            if next.commits.contains(where: { $0.id == id }) { continue }
            let commit = makeCommit(
                id: id,
                parentID: checkpoint?.parentCheckpointID?.description
                    ?? next.heads[branch.id.description]
                    ?? next.head,
                files: branchTree(branch: branch, in: document),
                message: commitMessage(for: checkpoint?.kind),
                now: checkpoint?.createdAt ?? document.project.updatedAt
            )
            next = appending(commit, to: next)
        }
        next.heads = heads
        if let main = document.branches.first(where: { $0.id == document.project.mainBranchID }) {
            next.head = heads[main.id.description]
        } else {
            next.head = heads.values.first ?? next.head
        }
        return next
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
        excerpt(title: title, content: content)
    }

    static func excerpt(title: String, content: String) -> String {
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = content
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .prefix(3)
            .joined(separator: "")
        var text = trimmedTitle.isEmpty
            ? body
            : body.isEmpty ? trimmedTitle : "\(trimmedTitle)：\(body)"
        let limit = NovelStateSnapshotRecord.maxHighlightCharacterCount
        if text.count > limit {
            text = String(text.prefix(limit))
        }
        return text
    }

    static func seedTexts(
        working: [NovelChapterSelection],
        in document: NovelProjectDocumentV1
    ) -> [NovelChapterID: String] {
        var seeds: [NovelChapterID: String] = [:]
        for selection in working {
            guard let version = document.chapterVersions.first(where: {
                $0.id == selection.versionID && $0.chapterID == selection.chapterID
            }) else { continue }
            seeds[selection.chapterID] = excerpt(
                title: version.title,
                content: version.content
            )
        }
        return seeds
    }

    static func alignedModules(
        existing: [NovelChapterPlotModule],
        working: [NovelChapterSelection],
        seeds: [NovelChapterID: String],
        markStaleAfterIndex: Int? = nil
    ) -> [NovelChapterPlotModule] {
        working.enumerated().map { index, selection in
            let existingModule = existing.first { $0.chapterID == selection.chapterID }
            let text = existingModule?.text.isEmpty == false
                ? existingModule?.text ?? ""
                : seeds[selection.chapterID] ?? existingModule?.text ?? ""
            let stale = (existingModule?.stale ?? false)
                || (markStaleAfterIndex.map { index > $0 } ?? false)
            return NovelChapterPlotModule(
                chapterID: selection.chapterID,
                text: text,
                stale: stale
            )
        }
    }

    static func relinkChapterPlots(
        existing: [NovelChapterPlotModule],
        working: [NovelChapterSelection],
        seeds: [NovelChapterID: String],
        updatedChapterID: NovelChapterID,
        updatedText: String,
        markLaterStale: Bool
    ) -> [NovelChapterPlotModule] {
        let updatedIndex = working.firstIndex { $0.chapterID == updatedChapterID }
        var modules = alignedModules(
            existing: existing,
            working: working,
            seeds: seeds,
            markStaleAfterIndex: markLaterStale ? updatedIndex : nil
        )
        if let index = modules.firstIndex(where: { $0.chapterID == updatedChapterID }) {
            modules[index] = NovelChapterPlotModule(
                chapterID: updatedChapterID,
                text: updatedText,
                stale: false
            )
        }
        return modules
    }

    /// Relinked plot snapshot for a chapter update, computed **before** the
    /// body change commits so both can land in one atomic checkpoint
    /// (core contract v1.1 D-B). The caller supplies the fresh snapshot id
    /// and commits it inside its own transaction.
    static func updatedPlotSnapshot(
        id: NovelStateSnapshotID,
        replacing snapshotID: NovelStateSnapshotID,
        workingSelections: [NovelChapterSelection],
        updatedChapterID: NovelChapterID,
        updatedTitle: String,
        updatedContent: String,
        moduleText: String?,
        summaryOverride: String?,
        markLaterStale: Bool,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> NovelStateSnapshotRecord {
        guard let old = document.stateSnapshots.first(where: { $0.id == snapshotID }) else {
            throw NovelError.invalidInput("The branch has no current plot snapshot.")
        }
        let live = workingSelections.filter { selection in
            document.chapters.first { $0.id == selection.chapterID }?.discardedAt == nil
        }
        let modules = relinkChapterPlots(
            existing: old.chapterPlots,
            working: live,
            seeds: seedTexts(working: live, in: document),
            updatedChapterID: updatedChapterID,
            updatedText: moduleText ?? excerpt(
                title: updatedTitle,
                content: updatedContent
            ),
            markLaterStale: markLaterStale
        )
        return NovelStateSnapshotRecord(
            id: id,
            eventIDs: old.eventIDs,
            summary: summaryOverride ?? old.summary,
            branchOutline: old.branchOutline,
            unresolvedEntityNames: old.unresolvedEntityNames,
            createdAt: now,
            settingProposalIDs: old.settingProposalIDs,
            characterIdentityClarifications: old.characterIdentityClarifications,
            recentWrittenHighlights: foldedHighlightTexts(modules),
            chapterPlots: modules
        )
    }

    static func reconcileModules(
        existing: [NovelChapterPlotModule],
        working: [NovelChapterSelection],
        seeds: [NovelChapterID: String],
        headSelections: [NovelChapterSelection]
    ) -> [NovelChapterPlotModule] {
        var modules = modulesAfterRemoval(
            existing: existing,
            working: working,
            seeds: seeds
        )
        let headVersion = Dictionary(
            uniqueKeysWithValues: headSelections.map { ($0.chapterID, $0.versionID) }
        )
        let lastID = working.last?.chapterID
        for (index, selection) in working.enumerated() {
            guard headVersion[selection.chapterID] != selection.versionID,
                  let text = seeds[selection.chapterID],
                  modules.indices.contains(index) else { continue }
            modules[index] = NovelChapterPlotModule(
                chapterID: selection.chapterID,
                text: text,
                stale: false
            )
            if selection.chapterID != lastID {
                for later in (index + 1)..<modules.count {
                    let old = modules[later]
                    modules[later] = NovelChapterPlotModule(
                        chapterID: old.chapterID,
                        text: old.text,
                        stale: true
                    )
                }
            }
        }
        return modules
    }

    static func modulesAfterRemoval(
        existing: [NovelChapterPlotModule],
        working: [NovelChapterSelection],
        seeds: [NovelChapterID: String]
    ) -> [NovelChapterPlotModule] {
        let previousIDs = existing.map(\.chapterID)
        let remainingIDs = Set(working.map(\.chapterID))
        let cutoff = previousIDs.indices.first { !remainingIDs.contains(previousIDs[$0]) }
        return working.map { selection in
            let existingModule = existing.first { $0.chapterID == selection.chapterID }
            let text = existingModule?.text.isEmpty == false
                ? existingModule?.text ?? ""
                : seeds[selection.chapterID] ?? existingModule?.text ?? ""
            let oldIndex = previousIDs.firstIndex(of: selection.chapterID)
            let stale = (existingModule?.stale ?? false)
                || (cutoff.map { cut in oldIndex.map { $0 > cut } ?? false } ?? false)
            return NovelChapterPlotModule(
                chapterID: selection.chapterID,
                text: text,
                stale: stale
            )
        }
    }

    static func foldedHighlightTexts(_ modules: [NovelChapterPlotModule]) -> [String] {
        Array(modules.suffix(highlightLimit)).compactMap { module in
            let text = module.text.trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? nil : text
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
        moduleText: String? = nil,
        summaryOverride: String? = nil,
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
        let text = moduleText ?? NovelWorkspaceLedger.excerpt(
            title: chapterTitle,
            content: chapterContent
        )
        let modules = NovelWorkspaceLedger.relinkChapterPlots(
            existing: old.chapterPlots,
            working: working,
            seeds: NovelWorkspaceLedger.seedTexts(working: working, in: document),
            updatedChapterID: chapterID,
            updatedText: text,
            markLaterStale: !NovelWorkspaceLedger.isFastForward(
                branch: branch,
                chapterID: chapterID
            )
        )
        let body = NovelWorkspaceLedger.plotCurrentBody(
            summary: summaryOverride ?? old.summary,
            highlights: NovelWorkspaceLedger.foldedHighlightTexts(modules)
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
        let leftover = document.pendingOperations.filter {
            $0.branchID == branchID && $0.kind == .manualSync
        }
        guard leftover.count <= 1 else {
            throw NovelError.invalidInput("当前有多个未完成的同步任务，请重新打开项目后再试。")
        }
        let working = NovelWorkspaceLedger.liveWorkingSelections(branch: branch, in: document)
        let headSelections = document.checkpoints.first {
            $0.id == branch.headCheckpointID
        }?.chapterSelections ?? branch.workingChapterSelections
        let modules = NovelWorkspaceLedger.reconcileModules(
            existing: old.chapterPlots,
            working: working,
            seeds: NovelWorkspaceLedger.seedTexts(working: working, in: document),
            headSelections: headSelections
        )
        let body = NovelWorkspaceLedger.plotCurrentBody(
            summary: old.summary,
            highlights: NovelWorkspaceLedger.foldedHighlightTexts(modules)
        )
        if let pending = leftover.first {
            return try completeLeftoverManualSync(
                in: document,
                pending: pending,
                body: body,
                chapterPlots: modules,
                now: now
            )
        }
        return try apply(
            to: document,
            branchID: branchID,
            path: "plot/current.md",
            body: body,
            now: now,
            chapterPlots: modules
        )
    }

    /// Finish a leftover cancelled JSON extract as a pointer commit.
    /// Stripping the pending alone orphans its factAttempts / receipts.
    private static func completeLeftoverManualSync(
        in document: NovelProjectDocumentV1,
        pending: NovelPendingOperationRecord,
        body: String,
        chapterPlots: [NovelChapterPlotModule],
        now: Date
    ) throws -> NovelProjectDocumentV1 {
        guard let branch = document.branches.first(where: { $0.id == pending.branchID }) else {
            throw NovelError.branchNotFound(pending.branchID)
        }
        guard let old = document.stateSnapshots.first(where: {
            $0.id == branch.currentStateSnapshotID
        }) else {
            throw NovelError.invalidInput("The branch has no current plot snapshot.")
        }
        guard let checkpointID = pending.proposedCheckpointID,
              let snapshotID = pending.proposedStateSnapshotID else {
            throw NovelError.invalidInput("The leftover sync has no reserved record IDs.")
        }
        let split = NovelWorkspaceMarkdown.splitHighlights(body)
        var next = document
        next.stateSnapshots.append(
            NovelStateSnapshotRecord(
                id: snapshotID,
                eventIDs: old.eventIDs,
                summary: split.body,
                branchOutline: old.branchOutline,
                unresolvedEntityNames: old.unresolvedEntityNames,
                createdAt: now,
                settingProposalIDs: old.settingProposalIDs,
                characterIdentityClarifications: old.characterIdentityClarifications,
                recentWrittenHighlights: split.highlights ?? old.recentWrittenHighlights,
                chapterPlots: chapterPlots
            )
        )
        let finalRevision = document.project.revision + 1
        let outcome = NovelOutcome.manualSyncCommitted(
            projectID: document.project.id,
            branchID: pending.branchID,
            checkpointID: checkpointID,
            revision: finalRevision
        )
        next.appliedOperations.append(
            NovelAppliedOperationRecord(
                operationID: pending.operationID,
                kind: .syncManualEdits,
                payloadSHA256: pending.payloadSHA256,
                outcome: outcome,
                appliedProjectRevision: finalRevision,
                appliedAt: now
            )
        )
        try NovelReducer.appendCheckpoint(
            NovelBranchCheckpointRecord(
                id: checkpointID,
                kind: .manualSync,
                createdOnBranchID: pending.branchID,
                parentCheckpointID: pending.baseCheckpointID,
                chapterSelections: branch.workingChapterSelections,
                stateSnapshotID: snapshotID,
                sessionCursor: pending.sessionCursor ?? .empty,
                branchOverrideRevisionIDs: branch.overrideRevisionIDs,
                sourceCandidateID: nil,
                baseHeadRevision: pending.baseHeadRevision,
                operationID: pending.operationID,
                createdAt: now
            ),
            to: &next,
            expectedHeadRevision: pending.baseHeadRevision,
            advancesWorkingRevision: false,
            now: now
        )
        next.pendingOperations.removeAll { $0.id == pending.id }
        next.project.revision = finalRevision
        next.project.updatedAt = now
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return next
    }

    static func applyAcceptStale(
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
        let modules = old.chapterPlots.map {
            NovelChapterPlotModule(chapterID: $0.chapterID, text: $0.text, stale: false)
        }
        let body = NovelWorkspaceLedger.plotCurrentBody(
            summary: old.summary,
            highlights: NovelWorkspaceLedger.foldedHighlightTexts(modules)
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
}

struct NovelWorkspacePlotDraft: Equatable, Sendable {
    var chapterText: String
    var summary: String

    static let maxChapterCharacters = 400
    static let maxSummaryCharacters = 800

    static func parse(_ raw: String) throws -> NovelWorkspacePlotDraft {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            throw NovelError.invalidInput("Plot draft is empty.")
        }
        var chapterLines: [String] = []
        var summaryLines: [String] = []
        var section: Section?
        for line in text.split(omittingEmptySubsequences: false, whereSeparator: \.isNewline) {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if let next = Section(heading: trimmed) {
                section = next
                continue
            }
            switch section {
            case .chapter:
                chapterLines.append(String(line))
            case .summary:
                summaryLines.append(String(line))
            case nil:
                continue
            }
        }
        let chapterText = clipped(
            chapterLines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines),
            maxChapterCharacters
        )
        let summary = clipped(
            summaryLines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines),
            maxSummaryCharacters
        )
        guard !chapterText.isEmpty else {
            throw NovelError.invalidInput("Plot draft is missing the chapter section.")
        }
        return NovelWorkspacePlotDraft(chapterText: chapterText, summary: summary)
    }

    private enum Section {
        case chapter
        case summary

        init?(heading: String) {
            let stripped = heading.replacingOccurrences(
                of: #"^#{1,3}\s*"#,
                with: "",
                options: .regularExpression
            )
            switch stripped {
            case "本章":
                self = .chapter
            case "当前":
                self = .summary
            default:
                return nil
            }
        }
    }

    private static func clipped(_ text: String, _ limit: Int) -> String {
        text.count > limit ? String(text.prefix(limit)) : text
    }
}
