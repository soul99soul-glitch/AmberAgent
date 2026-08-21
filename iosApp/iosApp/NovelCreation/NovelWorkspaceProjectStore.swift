import Foundation
import os

/// Workspace-native project store (contract D-E; D1① object library, D2
/// persistence swap). Inside `projects/{id}/`:
///
///   checkout/               the book tree (manifest.yaml … drafts/), replaced
///                           wholesale per commit — the runtime authority for
///                           chapter bodies
///   .amber/authority.json   `book: workspace` marker; presence gates the mode
///   .amber/commits.json     ledger — REAL file-tree commits keyed by checkpoint
///                           id, so undo/restore are pointer moves
///   .amber/objects/<sha256> content-addressed object library; every commit's
///                           file contents are retained, deduplicated by hash
///   .amber/engine/          sharded engine sections (sessions, snapshots,
///                           receipts, candidates …) — host-only state
///   legacy-package/         sealed pre-migration sharded package (renamed, not
///                           copied — rollback is a move back)
///
/// The old chain (layout.json + blobs/ at the project root, ledger inside
/// checkout/.amber/) stays untouched for un-migrated projects.
enum NovelWorkspaceProjectStore {
    static let bookWorkspace = "workspace"
    static let ledgerEngine = "amber-objects"
    static let markerFormat = "amber.novel.workspace"
    static let legacyDirectoryName = "legacy-package"
    static let corruptLedgerPrefix = "corrupt-"

    private static let logger = Logger(
        subsystem: "app.amber.ios.novel",
        category: "workspace-store"
    )

    // MARK: - Paths

    static func amberDirectory(in projectDirectory: URL) -> URL {
        projectDirectory.appendingPathComponent(NovelWorkspaceLedger.directoryName, isDirectory: true)
    }

    static func markerURL(in projectDirectory: URL) -> URL {
        amberDirectory(in: projectDirectory)
            .appendingPathComponent(NovelWorkspaceAuthority.markerFileName)
    }

    static func engineDirectory(in projectDirectory: URL) -> URL {
        amberDirectory(in: projectDirectory).appendingPathComponent("engine", isDirectory: true)
    }

    static func objectsDirectory(in projectDirectory: URL) -> URL {
        amberDirectory(in: projectDirectory).appendingPathComponent("objects", isDirectory: true)
    }

    static func checkoutDirectory(in projectDirectory: URL) -> URL {
        projectDirectory.appendingPathComponent("checkout", isDirectory: true)
    }

    static func legacyPackageDirectory(in projectDirectory: URL) -> URL {
        projectDirectory.appendingPathComponent(legacyDirectoryName, isDirectory: true)
    }

    static func isWorkspaceNative(
        projectDirectory: URL,
        fileManager: FileManager = .default
    ) -> Bool {
        guard let data = try? Data(contentsOf: markerURL(in: projectDirectory)),
              let marker = try? JSONDecoder().decode(NovelWorkspaceAuthority.Marker.self, from: data) else {
            return false
        }
        return marker.book == bookWorkspace
    }

    static func writeMarker(
        in projectDirectory: URL,
        fileManager: FileManager = .default
    ) throws {
        let url = markerURL(in: projectDirectory)
        try fileManager.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let marker = NovelWorkspaceAuthority.Marker(
            format: markerFormat,
            version: 1,
            book: bookWorkspace,
            ledger: ledgerEngine
        )
        let data = try JSONEncoder().encode(marker)
        try data.write(to: url, options: .atomic)
    }

    // MARK: - Object library (D1①)

    static func objectURL(sha256: String, in projectDirectory: URL) -> URL {
        objectsDirectory(in: projectDirectory).appendingPathComponent(sha256)
    }

    static func hasObject(
        sha256: String,
        in projectDirectory: URL,
        fileManager: FileManager = .default
    ) -> Bool {
        fileManager.fileExists(atPath: objectURL(sha256: sha256, in: projectDirectory).path)
    }

    static func writeObject(
        contents: String,
        sha256: String,
        in projectDirectory: URL,
        fileManager: FileManager = .default
    ) throws {
        let url = objectURL(sha256: sha256, in: projectDirectory)
        guard !fileManager.fileExists(atPath: url.path) else { return }
        try fileManager.createDirectory(
            at: objectsDirectory(in: projectDirectory),
            withIntermediateDirectories: true
        )
        try Data(contents.utf8).write(to: url, options: .atomic)
    }

    static func objectContents(
        sha256: String,
        in projectDirectory: URL,
        fileManager: FileManager = .default
    ) throws -> String {
        let url = objectURL(sha256: sha256, in: projectDirectory)
        guard let text = try? String(contentsOf: url, encoding: .utf8) else {
            throw NovelError.repositoryFailure("Workspace object missing: \(sha256)")
        }
        return text
    }

    /// Retain every file body the tree references. Content-addressed, so
    /// unchanged files dedupe to the same object across commits.
    static func writeObjects(
        for files: [NovelWorkspaceBackup.File],
        in projectDirectory: URL,
        fileManager: FileManager = .default
    ) throws {
        for file in files where file.path != "manifest.yaml" {
            try writeObject(
                contents: file.contents,
                sha256: NovelDocumentValidator.sha256(file.contents),
                in: projectDirectory,
                fileManager: fileManager
            )
        }
    }

    // MARK: - Ledger (real file trees)

    struct LedgerLoad {
        var store: NovelWorkspaceLedger.Store
        var isolatedCorruptStore: Bool
    }

    /// Conventions §5: a corrupt ledger is rename-isolated, never silently
    /// replaced with an empty store. The caller rebuilds from engine history.
    static func loadLedger(
        in projectDirectory: URL,
        fileManager: FileManager = .default
    ) -> LedgerLoad {
        let url = amberDirectory(in: projectDirectory)
            .appendingPathComponent(NovelWorkspaceLedger.storeFileName)
        guard fileManager.fileExists(atPath: url.path) else {
            return LedgerLoad(store: NovelWorkspaceLedger.Store(), isolatedCorruptStore: false)
        }
        guard let data = try? Data(contentsOf: url),
              let store = try? NovelWorkspaceLedger.decodeStore(data) else {
            let stamp = ISO8601DateFormatter().string(from: Date())
                .replacingOccurrences(of: ":", with: "-")
            let isolated = amberDirectory(in: projectDirectory)
                .appendingPathComponent("\(NovelWorkspaceLedger.storeFileName).\(corruptLedgerPrefix)\(stamp)")
            try? fileManager.moveItem(at: url, to: isolated)
            logger.error(
                "Workspace ledger was corrupt and got isolated for \(projectDirectory.lastPathComponent, privacy: .public)"
            )
            return LedgerLoad(store: NovelWorkspaceLedger.Store(), isolatedCorruptStore: true)
        }
        return LedgerLoad(store: store, isolatedCorruptStore: false)
    }

    static func saveLedger(
        _ store: NovelWorkspaceLedger.Store,
        in projectDirectory: URL,
        fileManager: FileManager = .default
    ) throws {
        try NovelWorkspaceLedger.save(
            store,
            to: projectDirectory,
            fileManager: fileManager
        )
    }

    static func realTree(from files: [NovelWorkspaceBackup.File]) -> [String: String] {
        NovelWorkspaceLedger.fileTree(from: files)
    }

    /// Mirror engine checkpoints into the ledger with REAL file trees.
    /// Idempotent per checkpoint id; heads track branch pointers, so undo and
    /// restore move pointers instead of appending commits.
    static func recordCommits(
        document: NovelProjectDocumentV1,
        tree: [String: String],
        into store: NovelWorkspaceLedger.Store
    ) -> NovelWorkspaceLedger.Store {
        var next = store
        var heads = next.heads
        for branch in document.branches where branch.lifecycle == .active {
            let checkpoint = document.checkpoints.first { $0.id == branch.headCheckpointID }
            let id = branch.headCheckpointID.description
            heads[branch.id.description] = id
            if next.commits.contains(where: { $0.id == id }) { continue }
            let commit = NovelWorkspaceLedger.makeCommit(
                id: id,
                parentID: checkpoint?.parentCheckpointID?.description
                    ?? next.heads[branch.id.description]
                    ?? next.head,
                files: tree,
                message: NovelWorkspaceLedger.commitMessage(for: checkpoint?.kind),
                now: checkpoint?.createdAt ?? document.project.updatedAt
            )
            next = NovelWorkspaceLedger.appending(commit, to: next)
        }
        next.heads = heads
        if let main = document.branches.first(where: { $0.id == document.project.mainBranchID }) {
            next.head = heads[main.id.description]
        } else {
            next.head = heads.values.first ?? next.head
        }
        return next
    }

    /// `git init` semantics: one baseline commit per active branch head, full
    /// tree, no parents. Used by migration and by corrupt-ledger rebuild —
    /// earlier history stays queryable through engine checkpoints.
    static func baselineStore(
        document: NovelProjectDocumentV1,
        tree: [String: String],
        message: String
    ) -> NovelWorkspaceLedger.Store {
        var store = NovelWorkspaceLedger.Store()
        var heads: [String: String] = [:]
        for branch in document.branches where branch.lifecycle == .active {
            let checkpoint = document.checkpoints.first { $0.id == branch.headCheckpointID }
            let id = branch.headCheckpointID.description
            heads[branch.id.description] = id
            let commit = NovelWorkspaceLedger.makeCommit(
                id: id,
                parentID: nil,
                files: tree,
                message: message,
                now: checkpoint?.createdAt ?? document.project.updatedAt
            )
            store = NovelWorkspaceLedger.appending(commit, to: store)
        }
        store.heads = heads
        if let main = document.branches.first(where: { $0.id == document.project.mainBranchID }) {
            store.head = heads[main.id.description]
        } else {
            store.head = heads.values.first ?? store.head
        }
        return store
    }

    // MARK: - Publish (the inverted write path)

    /// Book-affecting persist for workspace-native projects: engine sections
    /// are already written by the caller; here the tree is printed, objects
    /// retained, the tree swapped, and the commit sealed. Objects go first
    /// (idempotent); a crash between swap and ledger save is healed on load by
    /// re-recording from engine checkpoints.
    static func publish(
        document: NovelProjectDocumentV1,
        projectDirectory: URL,
        exportedAt: Date = Date(),
        fileManager: FileManager = .default
    ) throws {
        let files = try NovelWorkspaceBackup.export(document, exportedAt: exportedAt)
        try writeObjects(for: files, in: projectDirectory, fileManager: fileManager)
        try NovelWorkspaceBackup.writeWorkspaceTree(
            files,
            to: checkoutDirectory(in: projectDirectory),
            fileManager: fileManager
        )
        let load = loadLedger(in: projectDirectory, fileManager: fileManager)
        let tree = realTree(from: files)
        let store: NovelWorkspaceLedger.Store
        if load.isolatedCorruptStore {
            // The corrupt ledger was rename-isolated; rebuild from baseline —
            // earlier history remains queryable through engine checkpoints.
            logger.error(
                "Workspace ledger rebuilt from baseline for \(projectDirectory.lastPathComponent, privacy: .public)"
            )
            store = baselineStore(document: document, tree: tree, message: "账本重建基线")
        } else {
            store = recordCommits(document: document, tree: tree, into: load.store)
        }
        try saveLedger(store, in: projectDirectory, fileManager: fileManager)
        try writeMarker(in: projectDirectory, fileManager: fileManager)
    }

    /// Ledger catch-up on load: engine sections are durable, the ledger may
    /// lag a crash window behind the swapped tree.
    static func healLedgerIfNeeded(
        document: NovelProjectDocumentV1,
        projectDirectory: URL,
        fileManager: FileManager = .default
    ) {
        let load = loadLedger(in: projectDirectory, fileManager: fileManager)
        // Cheap short-circuit: the ledger already mirrors every branch head.
        let headsMatch = document.branches
            .filter { $0.lifecycle == .active }
            .allSatisfy {
                load.store.heads[$0.id.description] == $0.headCheckpointID.description
            }
        if headsMatch, let main = document.branches.first(where: {
            $0.id == document.project.mainBranchID
        }) {
            if load.store.head == load.store.heads[main.id.description] { return }
        }
        let files = (try? NovelWorkspaceBackup.export(document)) ?? []
        guard !files.isEmpty else { return }
        // After a corrupt-ledger isolation, rebuild from BASELINE: the
        // baseline's tree is the current print, which keeps the known-print
        // rollback guard effective for the immediately-previous state.
        let store = load.isolatedCorruptStore
            ? baselineStore(document: document, tree: realTree(from: files), message: "账本重建基线")
            : recordCommits(document: document, tree: realTree(from: files), into: load.store)
        try? saveLedger(store, in: projectDirectory, fileManager: fileManager)
    }

    // MARK: - Load reconcile (who is ahead?)

    /// Load-time authority decision for the book tree.
    ///
    /// * Tree matches the engine projection → nothing to do.
    /// * Tree matches ANY ledger commit's tree, or the checkout-write-failure
    ///   marker exists, or expected chapter files are MISSING → the engine is
    ///   AHEAD of the printed tree (a publish that failed or crashed before
    ///   the swap). Disk is NOT an out-of-band edit: reprint from the engine
    ///   and re-seal. Adopting here would silently roll the user's last
    ///   commit back — the exact failure mode this guards against.
    /// * Tree differs from every known print → genuine out-of-band hand edit:
    ///   disk WINS, replayed through the real `saveManualEdit` transaction.
    static func reconcileBookTree(
        document: NovelProjectDocumentV1,
        projectDirectory: URL,
        now: Date = Date(),
        fileManager: FileManager = .default
    ) throws -> NovelProjectDocumentV1 {
        let checkout = checkoutDirectory(in: projectDirectory)
        guard let files = NovelWorkspaceAuthority.filesOnDisk(at: checkout, fileManager: fileManager) else {
            // A checkout that exists but cannot be read as a workspace (e.g.
            // missing manifest) is a BROKEN PRINT, not an out-of-band edit —
            // the engine wins and the tree is reprinted.
            if fileManager.fileExists(atPath: checkout.path) {
                try publish(
                    document: document,
                    projectDirectory: projectDirectory,
                    fileManager: fileManager
                )
                logger.error(
                    "Workspace tree unreadable, reprinted for \(projectDirectory.lastPathComponent, privacy: .public)"
                )
            }
            return document
        }
        guard !NovelWorkspaceAuthority.worktreeCoversWorkingManuscript(document, files: files) else {
            return document
        }
        let diskTree = realTree(from: files)
        let knownPrint = loadLedger(in: projectDirectory, fileManager: fileManager)
            .store.commits.contains { $0.files == diskTree }
        let failedPublish = NovelProjectShardedStorage.checkoutWriteFailureMessage(
            in: projectDirectory,
            fileManager: fileManager
        ) != nil
        let missingOnDisk = !missingChapterKeys(document: document, files: files).isEmpty
        if knownPrint || failedPublish || missingOnDisk {
            try publish(
                document: document,
                projectDirectory: projectDirectory,
                fileManager: fileManager
            )
            NovelProjectShardedStorage.clearCheckoutWriteFailure(
                in: projectDirectory,
                fileManager: fileManager
            )
            logger.error(
                "Workspace tree behind engine, reprinted for \(projectDirectory.lastPathComponent, privacy: .public)"
            )
            return document
        }
        // Genuine out-of-band edit: disk wins. The adoption must be PERSISTED
        // in the same step — an in-memory-only adoption would re-run with
        // fresh version/operation ids on every load and permanently break
        // subsequent commits (immutable-version transition mismatch). A
        // failing adoption (empty body/title, busy branch) degrades to
        // ignoring that chapter's drift; it must never fail the whole load.
        do {
            let adopted = try adoptDiskChapterBodies(
                document: document,
                projectDirectory: projectDirectory,
                now: now,
                fileManager: fileManager
            )
            guard adopted != document else { return document }
            try writeEngineSections(
                document: adopted,
                projectDirectory: projectDirectory,
                fileManager: fileManager
            )
            try publish(
                document: adopted,
                projectDirectory: projectDirectory,
                fileManager: fileManager
            )
            return adopted
        } catch {
            logger.error(
                "Workspace drift adoption skipped: \(String(describing: error), privacy: .public)"
            )
            return document
        }
    }

    /// Branch-scoped chapter keys the projection expects but the disk tree
    /// does not carry — a broken print, not a hand edit.
    private static func missingChapterKeys(
        document: NovelProjectDocumentV1,
        files: [NovelWorkspaceBackup.File]
    ) -> [String] {
        let onDisk = Set(NovelWorkspaceAuthority.diskChapterBodies(in: files).keys)
        var missing: [String] = []
        for branch in document.branches where branch.lifecycle == .active {
            let branchSlug = NovelWorkspaceBackup.slug(branch.name)
            for selection in branch.workingChapterSelections {
                guard document.chapters.first(where: { $0.id == selection.chapterID })?.discardedAt == nil else {
                    continue
                }
                let key = NovelWorkspaceAuthority.chapterBodyKey(
                    branchSlug: branchSlug,
                    chapterID: selection.chapterID
                )
                if !onDisk.contains(key) { missing.append(key) }
            }
        }
        return missing
    }

    /// The markdown tree is the runtime book: when a chapter body on disk
    /// disagrees with the engine projection AND the tree is not a known print
    /// (see `reconcileBookTree`), disk wins. Adoption replays the drifted
    /// chapters through the REAL `saveManualEdit` transaction (new fact
    /// lineage, applied operation, needsSync — domain rules hold by
    /// construction).
    static func adoptDiskChapterBodies(
        document: NovelProjectDocumentV1,
        projectDirectory: URL,
        now: Date = Date(),
        fileManager: FileManager = .default
    ) throws -> NovelProjectDocumentV1 {
        let checkout = checkoutDirectory(in: projectDirectory)
        guard let files = NovelWorkspaceAuthority.filesOnDisk(at: checkout, fileManager: fileManager),
              !NovelWorkspaceAuthority.worktreeCoversWorkingManuscript(document, files: files) else {
            return document
        }
        let onDisk = NovelWorkspaceAuthority.diskChapterBodies(in: files)
        var next = document
        var adopted = false
        for branchIndex in next.branches.indices where next.branches[branchIndex].lifecycle == .active {
            let branch = next.branches[branchIndex]
            let branchSlug = NovelWorkspaceBackup.slug(branch.name)
            for selection in branch.workingChapterSelections {
                guard next.chapters.first(where: { $0.id == selection.chapterID })?.discardedAt == nil,
                      let disk = onDisk[NovelWorkspaceAuthority.chapterBodyKey(
                          branchSlug: branchSlug,
                          chapterID: selection.chapterID
                      )],
                      let current = next.chapterVersions.first(where: {
                          $0.id == selection.versionID && $0.chapterID == selection.chapterID
                      }) else { continue }
                guard current.title != disk.title || current.content != disk.content else { continue }
                let command = NovelSaveManualEditCommand(
                    context: NovelMutationContext(
                        operationID: NovelOperationID(),
                        expectedProjectRevision: next.project.revision,
                        expectedConfigRevision: next.project.configRevision,
                        expectedBranchHeadRevision: next.branches[branchIndex].headRevision
                    ),
                    projectID: next.project.id,
                    branchID: branch.id,
                    chapterID: selection.chapterID,
                    versionID: NovelChapterVersionID(),
                    title: disk.title,
                    content: disk.content,
                    factCompatibilityID: UUID(),
                    expectedWorkingRevision: next.branches[branchIndex].workingRevision
                )
                next = try NovelFactTransactionReducer.saveManualEdit(
                    command,
                    payloadSHA256: command.canonicalPayloadSHA256(),
                    in: next,
                    now: adopted ? now.addingTimeInterval(1) : now
                ).document
                adopted = true
            }
        }
        if adopted {
            logger.error(
                "Workspace tree drift adopted from disk for \(projectDirectory.lastPathComponent, privacy: .public)"
            )
        }
        return next
    }

    // MARK: - Engine sections

    static func writeEngineSections(
        document: NovelProjectDocumentV1,
        projectDirectory: URL,
        fileManager: FileManager = .default
    ) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        _ = try NovelProjectShardedStorage.writePackage(
            document: persistableAtRest(document),
            packageDirectory: engineDirectory(in: projectDirectory),
            encoder: encoder,
            fileManager: fileManager,
            cache: nil
        )
    }

    /// At quiet rest the bulky receipt evidence and completed run records
    /// are dropped from persistence: transitions only need an unchanged
    /// prefix, and replay identity lives in appliedOperations, which is
    /// kept. The projection is deliberately CONSERVATIVE — the load-time
    /// validator requires receipts for manual-sync ledger entries, polish
    /// attempts and fact attempts, and interrupted runs back interrupted
    /// prose candidates, so any document carrying that history keeps
    /// everything and prunes nothing.
    static func persistableAtRest(_ document: NovelProjectDocumentV1) -> NovelProjectDocumentV1 {
        var persistable = document
        guard document.pendingOperations.isEmpty,
              document.polishAttempts.isEmpty,
              document.factAttempts.isEmpty,
              !document.appliedOperations.contains(where: { $0.kind == .syncManualEdits })
        else { return persistable }
        // Prune ONLY when every run is completed/failed. Any running or
        // interrupted run anchors receipts, its startRun ledger entry, or an
        // interrupted-draft candidate — a partial prune would leave receipts
        // and ledger rows pointing at runs that no longer exist, which the
        // load-time validator rejects.
        let kept = document.activeRuns.filter { $0.status != .completed && $0.status != .failed }
        guard kept.isEmpty else { return document }
        persistable.activeRuns = []
        persistable.injectionReceipts = []
        persistable.generationReceipts = []
        // Pruned runs take their ledger entries with them — the validator
        // requires every startRun operation to point at a run that still
        // exists. Collect/edit operations stay.
        persistable.appliedOperations = document.appliedOperations.filter { operation in
            if case .runStarted(_, _, _, _, _) = operation.outcome {
                return false
            }
            return true
        }
        return persistable
    }

    // MARK: - Legacy migration (one-shot cutover)

    /// Convert a legacy-chain project (sharded package at the project root,
    /// ledger inside checkout/.amber) into a workspace-native project. The
    /// book tree already on disk is adopted as the migration baseline; engine
    /// sections are re-encoded into `.amber/engine`; the old package files are
    /// RENAMED into `legacy-package/` (no duplication — rollback moves them
    /// back and deletes `.amber/`). The marker flips the mode BEFORE the
    /// destructive seal, and the seal itself is idempotent so an interrupted
    /// seal is completed on the next open.
    static func migrateLegacyProject(
        document: NovelProjectDocumentV1,
        projectDirectory: URL,
        exportedAt: Date = Date(),
        fileManager: FileManager = .default
    ) throws {
        guard !isWorkspaceNative(projectDirectory: projectDirectory, fileManager: fileManager) else {
            try sealLegacyPackage(projectDirectory: projectDirectory, fileManager: fileManager)
            return
        }
        let files = try NovelWorkspaceBackup.export(document, exportedAt: exportedAt)
        try writeEngineSections(
            document: document,
            projectDirectory: projectDirectory,
            fileManager: fileManager
        )
        try writeObjects(for: files, in: projectDirectory, fileManager: fileManager)
        try NovelWorkspaceBackup.writeWorkspaceTree(
            files,
            to: checkoutDirectory(in: projectDirectory),
            fileManager: fileManager
        )

        // Migration baseline: `git init` semantics. Older history stays
        // queryable through the engine checkpoints; the ledger mirrors from
        // here on.
        let store = baselineStore(document: document, tree: realTree(from: files), message: "迁移基线")
        try saveLedger(store, in: projectDirectory, fileManager: fileManager)
        try writeMarker(in: projectDirectory, fileManager: fileManager)
        try sealLegacyPackage(projectDirectory: projectDirectory, fileManager: fileManager)
    }

    /// Rename the legacy package files out of the project root into
    /// `legacy-package/`, and drop the old in-tree ledger. Every step is
    /// `if exists`, so an interrupted seal resumes cleanly. The legacy
    /// monofiles (`{id}.json` / `{id}.previous.json`) live BESIDE the project
    /// directory in `projects/`, not inside it.
    static func sealLegacyPackage(
        projectDirectory: URL,
        fileManager: FileManager = .default
    ) throws {
        let legacy = legacyPackageDirectory(in: projectDirectory)
        try fileManager.createDirectory(at: legacy, withIntermediateDirectories: true)
        for name in [
            "layout.json",
            "previous-layout.json",
            "blobs",
        ] {
            let source = projectDirectory.appendingPathComponent(name)
            if fileManager.fileExists(atPath: source.path) {
                try fileManager.moveItem(at: source, to: legacy.appendingPathComponent(name))
            }
        }
        let ownerID = projectDirectory.lastPathComponent
        let monofileParent = projectDirectory.deletingLastPathComponent()
        for fileName in ["\(ownerID).json", "\(ownerID).previous.json"] {
            let source = monofileParent.appendingPathComponent(fileName)
            if fileManager.fileExists(atPath: source.path) {
                try fileManager.moveItem(
                    at: source,
                    to: legacy.appendingPathComponent(fileName)
                )
            }
        }
        let oldInTreeAmber = checkoutDirectory(in: projectDirectory)
            .appendingPathComponent(NovelWorkspaceLedger.directoryName, isDirectory: true)
        if fileManager.fileExists(atPath: oldInTreeAmber.path) {
            try? fileManager.removeItem(at: oldInTreeAmber)
        }
    }

    /// True while any legacy package artifact is still unsealed — drives the
    /// finalize-path completion of an interrupted seal.
    static func hasUnsealedLegacyArtifacts(
        projectDirectory: URL,
        fileManager: FileManager = .default
    ) -> Bool {
        for name in ["layout.json", "previous-layout.json", "blobs"] {
            if fileManager.fileExists(atPath: projectDirectory.appendingPathComponent(name).path) {
                return true
            }
        }
        let ownerID = projectDirectory.lastPathComponent
        let monofileParent = projectDirectory.deletingLastPathComponent()
        for fileName in ["\(ownerID).json", "\(ownerID).previous.json"] {
            if fileManager.fileExists(atPath: monofileParent.appendingPathComponent(fileName).path) {
                return true
            }
        }
        return false
    }
}
