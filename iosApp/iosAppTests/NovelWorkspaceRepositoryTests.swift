import Foundation
import XCTest
@testable import iosApp

/// Workspace-native repository contract (full-migration Phase 1, contract
/// D-E): the markdown tree at `checkout/` is the runtime book, `.amber/`
/// holds ledger + objects + engine sections, commits carry REAL file trees,
/// undo is a ledger pointer move, and disk drift wins on load.
final class NovelWorkspaceRepositoryTests: XCTestCase {

    /// Quiet-rest persistence prunes receipts/terminal runs/their ledger
    /// entries; load therefore equals the PRUNED equivalent of the fixture.
    private func persisted(_ document: NovelProjectDocumentV1) -> NovelProjectDocumentV1 {
        NovelWorkspaceProjectStore.persistableAtRest(document)
    }

    // MARK: - Helpers

    private func makeRoot() throws -> URL {
        let root = try NovelTestFixtures.temporaryDirectory()
        addTeardownBlock {
            try? FileManager.default.removeItem(at: root)
        }
        return root
    }

    private func projectDirectory(_ root: URL, _ id: NovelProjectID) -> URL {
        root.appendingPathComponent("projects", isDirectory: true)
            .appendingPathComponent(id.description, isDirectory: true)
    }

    private func ledger(_ project: URL) -> NovelWorkspaceLedger.Store {
        NovelWorkspaceLedger.load(from: project)
    }

    /// Recursive (path → contents) under a directory, relative paths,
    /// `.amber/` excluded — the visible book tree.
    private func visibleTree(at directory: URL) throws -> [String: String] {
        var result: [String: String] = [:]
        let fm = FileManager.default
        guard fm.fileExists(atPath: directory.path) else { return result }
        let base = directory.path
        if let enumerator = fm.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey]
        ) {
            for case let url as URL in enumerator {
                let values = try? url.resourceValues(forKeys: [.isRegularFileKey])
                guard values?.isRegularFile == true else { continue }
                let relative = String(url.path.dropFirst(base.count + 1))
                if relative.contains(".amber/") { continue }
                result[relative] = try String(contentsOf: url, encoding: .utf8)
            }
        }
        return result
    }

    private func chapterFiles(
        in project: URL,
        branchSlugContains: String = ""
    ) throws -> [URL] {
        let chaptersRoot = NovelWorkspaceProjectStore.checkoutDirectory(in: project)
        let fm = FileManager.default
        var urls: [URL] = []
        if let enumerator = fm.enumerator(
            at: chaptersRoot,
            includingPropertiesForKeys: [.isRegularFileKey]
        ) {
            for case let url as URL in enumerator {
                let values = try? url.resourceValues(forKeys: [.isRegularFileKey])
                guard values?.isRegularFile == true,
                      url.path.contains("/chapters/"),
                      url.path.hasSuffix(".md") else { continue }
                urls.append(url)
            }
        }
        return urls.sorted { $0.path < $1.path }
    }

    /// Real fact-transaction collect split into its two host commits (prepare
    /// +1, finalize +1) so every `commitProject` advances exactly one revision.
    private func collect(
        candidateID: NovelCandidateID,
        base document: NovelProjectDocumentV1,
        title: String
    ) throws -> (prepared: NovelProjectDocumentV1, collected: NovelProjectDocumentV1) {
        let command = try NovelBranchTestFixtures.collectCommand(
            candidateID,
            document: document,
            title: title
        )
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: NovelBranchTestFixtures.timestamp(for: document, offset: 3)
        ).document
        let prose = try NovelBranchTestFixtures.candidate(candidateID, in: document)
        let delta = NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: prose.content,
            events: [NovelStateEventV1(
                id: "event-\(command.context.operationID.description)",
                kind: "progress",
                summary: prose.content,
                entityReferences: ["Mara"],
                evidence: prose.content
            )],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: ["Mara"],
            branchOutlinePatch: nil,
            settingProposals: []
        )
        let collected = try NovelFactTransactionReducer.finalizeCollection(
            pendingID: command.pendingID,
            delta: delta,
            artifacts: NovelTestFixtures.factTransactionArtifacts(
                document: prepared,
                pendingID: command.pendingID,
                now: NovelBranchTestFixtures.timestamp(for: prepared, offset: 4)
            ),
            in: prepared,
            now: NovelBranchTestFixtures.timestamp(for: prepared, offset: 5)
        ).document
        return (prepared, collected)
    }

    // MARK: - Engine-ahead crash window (no silent rollback)

    func testEngineAheadOfTreeReprintsInsteadOfAdopting() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let generated = try NovelBranchTestFixtures.appendCompletedRun(
            to: document,
            branchID: document.branches[0].id,
            kind: .prose,
            content: "城门开了，后面还有很多字。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(generated.document, workspaceNative: true)
        let project = projectDirectory(root, document.project.id)
        let checkout = NovelWorkspaceProjectStore.checkoutDirectory(in: project)
        let baselineTree = try visibleTree(at: checkout)

        let base = try await repository.loadProject(id: document.project.id).document
        let second = try collect(
            candidateID: try XCTUnwrap(generated.candidateID),
            base: base,
            title: "入汴"
        )
        _ = try await repository.commitProject(
            second.prepared,
            expectedRevision: base.project.revision
        )
        let twoChapters = second.collected
        _ = try await repository.commitProject(
            twoChapters,
            expectedRevision: second.prepared.project.revision
        )

        // Simulate a crash between the engine write and the tree swap: the
        // disk tree still shows the last SEALED commit (baseline), the engine
        // is ahead. Loading must reprint from the engine, NOT adopt the stale
        // disk bodies back over the collected chapter.
        let baselineFiles = baselineTree.map {
            NovelWorkspaceBackup.File(path: $0.key, contents: $0.value)
        }
        try NovelWorkspaceBackup.writeWorkspaceTree(baselineFiles, to: checkout)

        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let loaded = try await restarted.loadProject(id: document.project.id)
        XCTAssertEqual(loaded.document, persisted(twoChapters), "engine ahead must not roll back")
        XCTAssertEqual(try chapterFiles(in: project).count, 2, "tree reprinted from engine")
        let selection = try XCTUnwrap(loaded.document.branches[0].workingChapterSelections.last)
        let version = try XCTUnwrap(
            loaded.document.chapterVersions.first {
                $0.id == selection.versionID && $0.chapterID == selection.chapterID
            }
        )
        XCTAssertEqual(version.kind, .collected, "no synthetic manualEdit may appear")
    }

    // MARK: - Unreadable tree

    func testMissingManifestReprintsInsteadOfNoOp() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(document, workspaceNative: true)
        let project = projectDirectory(root, document.project.id)

        // A broken print (manifest gone) must heal from the engine on load,
        // not silently no-op forever.
        try FileManager.default.removeItem(
            at: NovelWorkspaceProjectStore.checkoutDirectory(in: project)
                .appendingPathComponent("manifest.yaml")
        )
        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let loaded = try await restarted.loadProject(id: document.project.id)
        XCTAssertEqual(loaded.document, persisted(document))
        XCTAssertTrue(
            FileManager.default.fileExists(
                atPath: NovelWorkspaceProjectStore.checkoutDirectory(in: project)
                    .appendingPathComponent("manifest.yaml").path
            ),
            "tree must be reprinted with its manifest"
        )
    }

    // MARK: - Forked branches never cross-adopt

    func testForkedBranchesDoNotCrossAdopt() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let main = document.branches[0]
        let forked = try NovelReducer.apply(
            .forkBranch(NovelBranchTestFixtures.forkCommand(
                document: document,
                sourceBranchID: main.id,
                checkpointID: main.headCheckpointID,
                name: "b-line"
            )),
            to: document
        ).document

        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(forked, workspaceNative: true)
        let project = projectDirectory(root, document.project.id)

        // No drift: a fork sharing chapter ids must not trip phantom adoption.
        let clean = NovelFileProjectRepository(rootDirectory: root)
        let loadedClean = try await clean.loadProject(id: document.project.id)
        XCTAssertEqual(loadedClean.document, persisted(forked))

        // Hand-edit ONLY the fork branch's copy of the shared chapter.
        let checkout = NovelWorkspaceProjectStore.checkoutDirectory(in: project)
        let forkSlug = NovelWorkspaceBackup.slug("b-line")
        let allChapters = try chapterFiles(in: project)
        let forkChapterURL = try XCTUnwrap(allChapters.first {
            // branches/<slug>/chapters/x.md → <slug>
            $0.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent == forkSlug
        })
        let original = try String(contentsOf: forkChapterURL, encoding: .utf8)
        try (original + "\n支线手改：夜里换岗。\n").write(
            to: forkChapterURL,
            atomically: true,
            encoding: .utf8
        )

        let drifted = NovelFileProjectRepository(rootDirectory: root)
        let loaded = try await drifted.loadProject(id: document.project.id)
        let forkBranch = try XCTUnwrap(loaded.document.branches.first {
            NovelWorkspaceBackup.slug($0.name) == forkSlug
        })
        let mainBranch = try XCTUnwrap(loaded.document.branches.first { $0.id == main.id })
        let forkSelection = try XCTUnwrap(forkBranch.workingChapterSelections.first)
        let forkVersion = try XCTUnwrap(
            loaded.document.chapterVersions.first {
                $0.id == forkSelection.versionID && $0.chapterID == forkSelection.chapterID
            }
        )
        XCTAssertEqual(forkVersion.kind, .manualEdit)
        XCTAssertTrue(forkVersion.content.contains("支线手改：夜里换岗。"))

        // The MAIN branch keeps its untouched selection — no cross-branch bleed.
        let mainSelection = try XCTUnwrap(mainBranch.workingChapterSelections.first)
        XCTAssertEqual(
            mainSelection.versionID,
            forked.branches.first { $0.id == main.id }?.workingChapterSelections.first?.versionID
        )
    }

    // MARK: - Workspace engine corruption safety net

    func testWorkspaceEngineCorruptionFallsBackToPreviousAndRestores() async throws {
        let root = try makeRoot()
        let first = try NovelTestFixtures.document()
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(first, workspaceNative: true)
        let second = try NovelReducer.apply(
            NovelTestFixtures.renameAction(document: first, name: "Second"),
            to: first
        ).document
        _ = try await repository.commitProject(
            second,
            expectedRevision: first.project.revision
        )

        let project = projectDirectory(root, first.project.id)
        try Data("broken".utf8).write(
            to: NovelWorkspaceProjectStore.engineDirectory(in: project)
                .appendingPathComponent("layout.json"),
            options: [.atomic]
        )

        let degraded = try await repository.loadProject(id: first.project.id)
        XCTAssertEqual(degraded.document, first)
        guard case .degradedPrevious = degraded.access else {
            return XCTFail("Expected degraded access, got \(degraded.access)")
        }

        let restored = try await repository.restorePreviousProject(
            id: first.project.id,
            expectedDocumentSHA256: try NovelProjectPackageCodec.encode(first).projectSHA256
        )
        XCTAssertEqual(restored.document, first)
        XCTAssertEqual(restored.access, .readWrite)
    }

    // MARK: - Creation

    func testWorkspaceCreatePublishesTreeEngineLedgerAndObjects() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(document, workspaceNative: true)

        let project = projectDirectory(root, document.project.id)
        let fm = FileManager.default

        // Book tree + mode marker + engine sections; NO sharded package at
        // the project root.
        XCTAssertTrue(fm.fileExists(atPath: project.appendingPathComponent("checkout/manifest.yaml").path))
        XCTAssertTrue(NovelWorkspaceProjectStore.isWorkspaceNative(projectDirectory: project))
        XCTAssertTrue(fm.fileExists(atPath: NovelWorkspaceProjectStore.engineDirectory(in: project).appendingPathComponent("layout.json").path))
        XCTAssertFalse(fm.fileExists(atPath: project.appendingPathComponent("layout.json").path))

        // Ledger: one commit for the branch head checkpoint, REAL tree paths,
        // manifest excluded.
        let store = ledger(project)
        let branch = document.branches[0]
        XCTAssertEqual(store.head, branch.headCheckpointID.description)
        XCTAssertEqual(store.commits.count, 1)
        let commit = try XCTUnwrap(store.commits.first)
        XCTAssertTrue(
            commit.files.keys.contains {
                $0.hasPrefix("branches/") && $0.contains("/chapters/") && $0.hasSuffix(".md")
            },
            "commit tree must carry real chapter paths, got: \(commit.files.keys.sorted())"
        )
        XCTAssertFalse(commit.files.keys.contains("manifest.yaml"))

        // Object library retains every file body the tree references.
        let chapters = try chapterFiles(in: project)
        XCTAssertFalse(chapters.isEmpty)
        for url in try XCTUnwrap(chapters.first.map { [$0] }) {
            let contents = try String(contentsOf: url, encoding: .utf8)
            XCTAssertTrue(
                NovelWorkspaceProjectStore.hasObject(
                    sha256: NovelDocumentValidator.sha256(contents),
                    in: project
                ),
                "chapter object missing for \(url.lastPathComponent)"
            )
        }
    }

    // MARK: - Commit + undo as pointer move

    func testWorkspaceCommitAppendsRealTreeCommitAndUndoIsPointerMove() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        // The second chapter's run exists BEFORE creation so every later host
        // commit advances exactly one revision (runs bump per begin/complete).
        let generated = try NovelBranchTestFixtures.appendCompletedRun(
            to: document,
            branchID: document.branches[0].id,
            kind: .prose,
            content: "城门开了，后面还有很多字。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(generated.document, workspaceNative: true)
        let project = projectDirectory(root, document.project.id)

        let base = try await repository.loadProject(id: document.project.id).document
        let second = try collect(
            candidateID: try XCTUnwrap(generated.candidateID),
            base: base,
            title: "入汴"
        )
        _ = try await repository.commitProject(
            second.prepared,
            expectedRevision: base.project.revision
        )
        let collectedReturn = try await repository.commitProject(
            second.collected,
            expectedRevision: second.prepared.project.revision
        )
        let twoChapters = collectedReturn.document

        var store = ledger(project)
        XCTAssertEqual(store.commits.count, 2)
        let head = try XCTUnwrap(store.headCommit)
        XCTAssertEqual(head.id, twoChapters.branches[0].headCheckpointID.description)
        XCTAssertEqual(head.parentID, document.branches[0].headCheckpointID.description)
        let chapterPaths = head.files.keys.filter {
            $0.contains("/chapters/") && $0.hasSuffix(".md")
        }
        XCTAssertEqual(chapterPaths.count, 2, "both chapters must be in the head tree")

        // Undo one step through the reducer, then commit.
        let branch = twoChapters.branches[0]
        let command = NovelUndoBranchHeadCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: twoChapters.project.revision,
                expectedConfigRevision: twoChapters.project.configRevision,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: twoChapters.project.id,
            branchID: branch.id,
            expectedWorkingRevision: branch.workingRevision
        )
        let undone = try NovelReducer.apply(.undoBranchHead(command), to: twoChapters).document
        _ = try await repository.commitProject(
            undone,
            expectedRevision: twoChapters.project.revision
        )

        // Undo is a ledger POINTER MOVE: no new commit, head back to the
        // first collection, the second chapter leaves the tree.
        store = ledger(project)
        XCTAssertEqual(store.commits.count, 2, "undo must not append a commit")
        XCTAssertEqual(store.head, document.branches[0].headCheckpointID.description)
        let chaptersAfterUndo = try chapterFiles(in: project)
        XCTAssertEqual(chaptersAfterUndo.count, 1)

        // The removed chapter's content is still retained as an object —
        // restore/redo remains possible.
        let headFiles = try XCTUnwrap(store.headCommit).files
        let removedPath = try XCTUnwrap(
            head.files.keys
                .filter { $0.contains("/chapters/") }
                .first { headFiles[$0] == nil }
        )
        let removedHash = try XCTUnwrap(head.files[removedPath])
        XCTAssertTrue(
            NovelWorkspaceProjectStore.hasObject(sha256: removedHash, in: project),
            "removed chapter object must be retained for restore"
        )
        let contents = try NovelWorkspaceProjectStore.objectContents(sha256: removedHash, in: project)
        XCTAssertTrue(contents.contains("城门开了"), "object must carry the removed chapter body")
    }

    // MARK: - Restart round trip

    func testWorkspaceRestartLoadRoundTripsThroughEngineSections() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(document, workspaceNative: true)

        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let summaries = try await restarted.listProjects()
        XCTAssertEqual(summaries.map(\.id), [document.project.id])
        let loaded = try await restarted.loadProject(id: document.project.id)
        XCTAssertEqual(loaded.document, persisted(document))
    }

    // MARK: - Disk wins on drift

    func testDiskChapterDriftWinsOnLoad() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(document, workspaceNative: true)
        let project = projectDirectory(root, document.project.id)

        // Hand-edit the chapter body on disk, frontmatter untouched.
        let chapterURL = try XCTUnwrap(try chapterFiles(in: project).first)
        let original = try String(contentsOf: chapterURL, encoding: .utf8)
        try (original + "\n手改补记：风其实没到。\n").write(
            to: chapterURL,
            atomically: true,
            encoding: .utf8
        )

        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let loaded = try await restarted.loadProject(id: document.project.id)
        let branch = loaded.document.branches[0]
        let selection = try XCTUnwrap(branch.workingChapterSelections.first)
        let version = try XCTUnwrap(
            loaded.document.chapterVersions.first {
                $0.id == selection.versionID && $0.chapterID == selection.chapterID
            }
        )
        XCTAssertEqual(version.kind, .manualEdit)
        XCTAssertTrue(
            version.content.contains("手改补记：风其实没到。"),
            "disk body must win; got: \(version.content.suffix(40))"
        )
        XCTAssertTrue(
            version.content.contains("陈桥驿的风先到。"),
            "adopted body keeps the original text before the hand edit"
        )
    }

    // MARK: - Corrupt ledger isolation

    func testCorruptLedgerIsIsolatedAndRebuiltAsBaseline() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root, automaticWorkspaceMigration: true)
        _ = try await repository.createProject(document, workspaceNative: true)
        let project = projectDirectory(root, document.project.id)

        let commitsURL = project.appendingPathComponent(".amber/commits.json")
        try Data("not a ledger".utf8).write(to: commitsURL)

        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let loaded = try await restarted.loadProject(id: document.project.id)
        XCTAssertEqual(loaded.document, persisted(document), "corrupt ledger must not brick the book")

        let fm = FileManager.default
        let amber = project.appendingPathComponent(".amber", isDirectory: true)
        let isolated = try fm.contentsOfDirectory(at: amber, includingPropertiesForKeys: nil)
            .filter { $0.lastPathComponent.hasPrefix("commits.json.corrupt-") }
        XCTAssertEqual(isolated.count, 1, "corrupt ledger must be rename-isolated")

        // The ledger was rebuilt with a baseline head commit.
        let store = ledger(project)
        XCTAssertEqual(store.head, document.branches[0].headCheckpointID.description)
        XCTAssertFalse(store.commits.isEmpty)
    }

    // MARK: - Auto-migration on open

    func testLegacyProjectAutoMigratesOnOpen() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root, automaticWorkspaceMigration: true)
        _ = try await repository.createProject(document)

        // First open after the cutover migrates the project; the same call
        // still returns the loaded book.
        let restarted = NovelFileProjectRepository(rootDirectory: root, automaticWorkspaceMigration: true)
        let loaded = try await restarted.loadProject(id: document.project.id)
        XCTAssertEqual(loaded.document, persisted(document))

        let project = projectDirectory(root, document.project.id)
        XCTAssertTrue(NovelWorkspaceProjectStore.isWorkspaceNative(projectDirectory: project))
        let fm = FileManager.default
        XCTAssertFalse(fm.fileExists(atPath: project.appendingPathComponent("layout.json").path))
        XCTAssertTrue(
            fm.fileExists(atPath: project.appendingPathComponent("legacy-package/layout.json").path)
        )
        XCTAssertEqual(
            ledger(project).head,
            document.branches[0].headCheckpointID.description
        )

        // The second open runs on the workspace path end to end.
        let afterMigration = NovelFileProjectRepository(rootDirectory: root)
        let again = try await afterMigration.loadProject(id: document.project.id)
        XCTAssertEqual(again.document, persisted(document))
    }

    // MARK: - Legacy migration

    func testLegacyProjectMigratesSealingPackageAndPreservingTree() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let generated = try NovelBranchTestFixtures.appendCompletedRun(
            to: document,
            branchID: document.branches[0].id,
            kind: .prose,
            content: "城门开了，后面还有很多字。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root, automaticWorkspaceMigration: true)
        _ = try await repository.createProject(generated.document)

        let base = try await repository.loadProject(id: document.project.id).document
        let second = try collect(
            candidateID: try XCTUnwrap(generated.candidateID),
            base: base,
            title: "入汴"
        )
        _ = try await repository.commitProject(
            second.prepared,
            expectedRevision: base.project.revision
        )
        let twoChapters = second.collected
        _ = try await repository.commitProject(
            twoChapters,
            expectedRevision: second.prepared.project.revision
        )

        let project = projectDirectory(root, document.project.id)
        let fm = FileManager.default

        // The first commit's internal load already performed the cutover:
        // legacy package sealed by rename, tree preserved byte-for-byte.
        XCTAssertTrue(NovelWorkspaceProjectStore.isWorkspaceNative(projectDirectory: project))
        XCTAssertFalse(fm.fileExists(atPath: project.appendingPathComponent("layout.json").path))
        XCTAssertTrue(
            fm.fileExists(atPath: project.appendingPathComponent("legacy-package/layout.json").path),
            "legacy package must be sealed for rollback"
        )
        let checkout = NovelWorkspaceProjectStore.checkoutDirectory(in: project)
        let treeAfterCutover = try visibleTree(at: checkout)
        XCTAssertFalse(treeAfterCutover.isEmpty)

        // Explicit migration is idempotent once the cutover happened.
        try NovelWorkspaceProjectStore.migrateLegacyProject(
            document: twoChapters,
            projectDirectory: project
        )
        XCTAssertEqual(try visibleTree(at: checkout), treeAfterCutover)

        // Ledger baseline + the collected checkpoint's real-tree commit.
        let store = ledger(project)
        XCTAssertEqual(store.commits.count, 2)
        XCTAssertEqual(store.head, twoChapters.branches[0].headCheckpointID.description)

        // The migrated project loads and commits through the workspace path.
        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let loaded = try await restarted.loadProject(id: document.project.id)
        XCTAssertEqual(loaded.document, persisted(twoChapters))

        // Third chapter after migration, split into single-revision host
        // commits: run begin, run complete, prepare, finalize. Built on the
        // reloaded (pruned) base — persistence drops receipts/terminal runs
        // at rest and transitions validate loaded → next.
        let migratedBase = loaded.document
        let request = try NovelBranchTestFixtures.runRequest(
            document: migratedBase,
            branchID: migratedBase.branches[0].id,
            kind: .prose,
            userText: "第三章。"
        )
        let started = try NovelGenerationReducer.begin(
            request,
            artifacts: try NovelBranchTestFixtures.generationArtifacts(
                document: migratedBase,
                request: request
            ),
            in: migratedBase,
            now: NovelBranchTestFixtures.timestamp(for: migratedBase, offset: 1)
        ).document
        _ = try await restarted.commitProject(
            started,
            expectedRevision: migratedBase.project.revision
        )
        let completed = try NovelGenerationReducer.complete(
            runID: request.id,
            content: "第三章：山呼。",
            in: started,
            now: NovelBranchTestFixtures.timestamp(for: started, offset: 2)
        ).document
        let completedReturn = try await restarted.commitProject(
            completed,
            expectedRevision: started.project.revision
        )
        let third = try collect(
            candidateID: try XCTUnwrap(request.candidateID),
            base: completedReturn.document,
            title: "山呼"
        )
        _ = try await restarted.commitProject(
            third.prepared,
            expectedRevision: completed.project.revision
        )
        let threeChapters = third.collected
        _ = try await restarted.commitProject(
            threeChapters,
            expectedRevision: third.prepared.project.revision
        )
        XCTAssertEqual(try chapterFiles(in: project).count, 3)
        // Baseline + second collect + third collect — run begin/complete and
        // prepare add no checkpoints.
        XCTAssertEqual(ledger(project).commits.count, 3)
        XCTAssertEqual(
            ledger(project).head,
            threeChapters.branches[0].headCheckpointID.description
        )
    }

    // MARK: - Adoption persists (post-review closure)

    func testAdoptedDriftPersistsAndAllowsSubsequentCommits() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(document, workspaceNative: true)
        let project = projectDirectory(root, document.project.id)

        // Hand-edit, then load: the adoption must be persisted in the same
        // step, otherwise every later load re-adopts with fresh ids and all
        // commits fail on the immutable-version transition check.
        let chapterURL = try XCTUnwrap(try chapterFiles(in: project).first)
        let original = try String(contentsOf: chapterURL, encoding: .utf8)
        try (original + "\n手改补记：风其实没到。\n").write(
            to: chapterURL,
            atomically: true,
            encoding: .utf8
        )
        let drifted = NovelFileProjectRepository(rootDirectory: root)
        let adopted = try await drifted.loadProject(id: document.project.id)

        // A follow-up mutation through the real reducer must commit cleanly.
        let renamed = try NovelReducer.apply(
            NovelTestFixtures.renameAction(document: adopted.document, name: "改名之后"),
            to: adopted.document
        ).document
        _ = try await drifted.commitProject(
            renamed,
            expectedRevision: adopted.document.project.revision
        )

        // Third load is stable: no re-adoption, version identity persists.
        let third = NovelFileProjectRepository(rootDirectory: root)
        let reloaded = try await third.loadProject(id: document.project.id)
        XCTAssertEqual(reloaded.document, renamed)
    }

    func testEmptyDiskBodySkipsAdoptionWithoutDegrading() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(document, workspaceNative: true)
        let project = projectDirectory(root, document.project.id)

        // Empty body cannot pass saveManualEdit; adoption must skip that
        // chapter instead of degrading the whole project to read-only.
        let chapterURL = try XCTUnwrap(try chapterFiles(in: project).first)
        let original = try String(contentsOf: chapterURL, encoding: .utf8)
        let frontmatter = String(
            original[..<(original.range(of: "\n---\n", range: original.startIndex..<original.endIndex)
                .map { original.index(after: $0.upperBound) } ?? original.endIndex)]
        )
        try (frontmatter + "\n").write(to: chapterURL, atomically: true, encoding: .utf8)

        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let loaded = try await restarted.loadProject(id: document.project.id)
        XCTAssertEqual(loaded.access, .readWrite, "adoption failure must not degrade the load")
        XCTAssertEqual(loaded.document, persisted(document))
    }

    func testMonofileEraProjectSealsMonofileIntoLegacyPackage() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let projectsRoot = root.appendingPathComponent("projects", isDirectory: true)
        try FileManager.default.createDirectory(at: projectsRoot, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(document).write(
            to: projectsRoot.appendingPathComponent("\(document.project.id.description).json"),
            options: [.atomic]
        )
        // The worktree-era print that qualifies the book for auto-migration.
        let project = projectDirectory(root, document.project.id)
        try NovelWorkspaceBackup.writeWorkspaceTree(
            try NovelWorkspaceBackup.export(document),
            to: NovelWorkspaceProjectStore.checkoutDirectory(in: project)
        )

        let repository = NovelFileProjectRepository(
            rootDirectory: root,
            automaticWorkspaceMigration: true
        )
        let loaded = try await repository.loadProject(id: document.project.id)
        XCTAssertEqual(loaded.document, persisted(document))
        XCTAssertTrue(NovelWorkspaceProjectStore.isWorkspaceNative(projectDirectory: project))
        let legacy = NovelWorkspaceProjectStore.legacyPackageDirectory(in: project)
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: legacy.appendingPathComponent("\(document.project.id.description).json").path),
            "the monofile beside the project dir must be sealed"
        )
        XCTAssertFalse(
            FileManager.default.fileExists(
                atPath: projectsRoot.appendingPathComponent("\(document.project.id.description).json").path
            )
        )
    }

    // MARK: - Receipt slimming at rest

    func testQuietWorkspaceCommitDropsReceipts() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        // A completed run leaves receipts behind in the document; at rest
        // (no active run, no pendings) persistence must drop them.
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(
            document,
            workspaceNative: true
        )
        XCTAssertFalse(document.injectionReceipts.isEmpty || document.generationReceipts.isEmpty)

        let loaded = try await repository.loadProject(id: document.project.id)
        XCTAssertTrue(loaded.document.injectionReceipts.isEmpty, "quiet rest drops injection receipts")
        XCTAssertTrue(loaded.document.generationReceipts.isEmpty, "quiet rest drops generation receipts")
        // Follow-up commits still validate (unchanged-prefix grows from empty).
        let renamed = try NovelReducer.apply(
            NovelTestFixtures.renameAction(document: loaded.document, name: "瘦身后改名"),
            to: loaded.document
        ).document
        _ = try await repository.commitProject(
            renamed,
            expectedRevision: loaded.document.project.revision
        )

        // With an ACTIVE run the receipts stay (validator cross-checks
        // activeRun → generation receipt).
        let request = try NovelBranchTestFixtures.runRequest(
            document: renamed,
            branchID: renamed.branches[0].id,
            kind: .prose,
            userText: "下一章。"
        )
        let started = try NovelGenerationReducer.begin(
            request,
            artifacts: try NovelBranchTestFixtures.generationArtifacts(
                document: renamed,
                request: request
            ),
            in: renamed,
            now: NovelBranchTestFixtures.timestamp(for: renamed, offset: 1)
        ).document
        _ = try await repository.commitProject(
            started,
            expectedRevision: renamed.project.revision
        )
        let active = try await repository.loadProject(id: document.project.id)
        XCTAssertFalse(active.document.generationReceipts.isEmpty, "active runs keep their receipts")
    }

    // MARK: - Through the production creation actor

    func testCreationActorWithFileRepositoryStaysCommittable() async throws {
        let root = try makeRoot()
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let creation = DefaultNovelCreation(repository: repository)

        // Blank-book creation goes through the production cast: the project
        // is workspace-native from birth.
        let command = try NovelTestFixtures.createCommand()
        _ = try await creation.perform(.createProject(command))
        let project = projectDirectory(root, command.projectID)
        XCTAssertTrue(NovelWorkspaceProjectStore.isWorkspaceNative(projectDirectory: project))
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: project.appendingPathComponent("layout.json").path)
        )

        // Mutations through the actor (its own cache + equality guards)
        // keep committing across repeated loads.
        for name in ["第一改", "第二改", "第三改"] {
            let snapshot = try await creation.snapshot(.project(command.projectID))
            guard case .project(let project) = snapshot else {
                return XCTFail("expected project snapshot")
            }
            _ = try await creation.perform(
                NovelTestFixtures.renameAction(document: project.document, name: name)
            )
        }
        let final = try await repository.loadProject(id: command.projectID)
        XCTAssertEqual(final.document.project.name, "第三改")
        XCTAssertEqual(final.access, .readWrite)
    }

    // MARK: - Interrupted runs block pruning (F1 contract)

    func testInterruptedRunWithLaterCompletedRunStaysLoadable() async throws {
        let root = try makeRoot()
        let document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(document, workspaceNative: true)
        let project = projectDirectory(root, document.project.id)

        // Run A: interrupted (kept forever — anchors its interrupted draft).
        // Built on the reloaded base — creation persisted the pruned view.
        let base0 = try await repository.loadProject(id: document.project.id).document
        let firstRequest = try NovelBranchTestFixtures.runRequest(
            document: base0,
            branchID: base0.branches[0].id,
            kind: .prose,
            userText: "被中断的一章。"
        )
        let started = try NovelGenerationReducer.begin(
            firstRequest,
            artifacts: try NovelBranchTestFixtures.generationArtifacts(
                document: base0,
                request: firstRequest
            ),
            in: base0,
            now: NovelBranchTestFixtures.timestamp(for: base0, offset: 1)
        ).document
        _ = try await repository.commitProject(
            started,
            expectedRevision: base0.project.revision
        )
        let base = try await repository.loadProject(id: document.project.id).document
        let interrupted = try NovelGenerationReducer.interrupt(
            NovelCancelRunCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: base.project.revision,
                    expectedConfigRevision: base.project.configRevision,
                    expectedBranchHeadRevision: base.branches[0].headRevision
                ),
                projectID: document.project.id,
                runID: firstRequest.id,
                reason: .user
            ),
            partialContent: "被中断的半章。",
            in: base,
            now: NovelBranchTestFixtures.timestamp(for: base, offset: 2)
        ).document
        _ = try await repository.commitProject(
            interrupted,
            expectedRevision: base.project.revision
        )

        // Run B: completed. The mixed shape (interrupted + completed) must
        // not produce a pruned document the validator rejects.
        let secondRequest = try NovelBranchTestFixtures.runRequest(
            document: interrupted,
            branchID: document.branches[0].id,
            kind: .prose,
            userText: "写成的一章。"
        )
        let secondStarted = try NovelGenerationReducer.begin(
            secondRequest,
            artifacts: try NovelBranchTestFixtures.generationArtifacts(
                document: interrupted,
                request: secondRequest
            ),
            in: interrupted,
            now: NovelBranchTestFixtures.timestamp(for: interrupted, offset: 3)
        ).document
        _ = try await repository.commitProject(
            secondStarted,
            expectedRevision: interrupted.project.revision
        )
        let secondBase = try await repository.loadProject(id: document.project.id).document
        let secondCompleted = try NovelGenerationReducer.complete(
            runID: secondRequest.id,
            content: "写完的一章。",
            in: secondBase,
            now: NovelBranchTestFixtures.timestamp(for: secondBase, offset: 4)
        ).document
        _ = try await repository.commitProject(
            secondCompleted,
            expectedRevision: secondBase.project.revision
        )

        // Reload must stay valid and read-write — the interrupted run pins
        // the whole history (no partial pruning).
        let reloaded = try await NovelFileProjectRepository(rootDirectory: root)
            .loadProject(id: document.project.id)
        XCTAssertEqual(reloaded.access, .readWrite)
        XCTAssertFalse(reloaded.document.activeRuns.isEmpty)
        _ = project
    }
}
