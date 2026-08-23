import XCTest
@testable import iosApp

final class NovelWorkspaceLedgerTests: XCTestCase {
    func testCommitStatusReportsChangedBookFiles() {
        let first = [
            NovelWorkspaceBackup.File(path: "branches/main/chapters/001.md", contents: "old"),
            NovelWorkspaceBackup.File(path: "branches/main/plot/current.md", contents: "s1"),
        ]
        let commit = NovelWorkspaceLedger.makeCommit(
            id: "c1",
            parentID: nil,
            files: NovelWorkspaceLedger.fileTree(from: first),
            message: "r1",
            now: Date(timeIntervalSince1970: 1)
        )
        let next = [
            NovelWorkspaceBackup.File(path: "branches/main/chapters/001.md", contents: "new"),
            NovelWorkspaceBackup.File(path: "branches/main/plot/current.md", contents: "s1"),
        ]
        let status = NovelWorkspaceLedger.status(
            head: commit,
            working: NovelWorkspaceLedger.fileTree(from: next),
            plotStale: false,
            unresolved: false
        )
        XCTAssertEqual(status.headID, commit.id)
        XCTAssertEqual(status.dirtyPaths, ["branches/main/chapters/001.md"])
        XCTAssertFalse(status.plotStale)
        XCTAssertFalse(status.unresolved)
    }

    func testFastForwardPlotKeepsSummaryAndPrependsHighlight() throws {
        let document = try makeNovelWorkspaceBackupFixture()
        let branch = document.branches[0]
        let next = try NovelWorkspacePlotCommit.applyFastForward(
            to: document,
            branchID: branch.id,
            chapterTitle: "入汴",
            chapterContent: "赵大勒马看城门。\n后面还有很多字。"
        )
        XCTAssertEqual(next.branches[0].syncStatus, .synchronized)
        XCTAssertEqual(next.stateSnapshots.last?.summary, "赵大已在陈桥。")
        XCTAssertEqual(
            next.stateSnapshots.last?.recentWrittenHighlights,
            ["入汴：赵大勒马看城门。后面还有很多字。"]
        )
        XCTAssertEqual(
            next.stateSnapshots.last?.chapterPlots.last?.text,
            "入汴：赵大勒马看城门。后面还有很多字。"
        )
        XCTAssertGreaterThan(next.checkpoints.count, document.checkpoints.count)
    }

    func testRelinkMiddleChapterKeepsLaterChapterModule() {
        let first = NovelChapterID()
        let second = NovelChapterID()
        let working = [
            NovelChapterSelection(chapterID: first, versionID: NovelChapterVersionID()),
            NovelChapterSelection(chapterID: second, versionID: NovelChapterVersionID()),
        ]
        let modules = NovelWorkspaceLedger.relinkChapterPlots(
            existing: [
                NovelChapterPlotModule(chapterID: first, text: "陈桥起事"),
                NovelChapterPlotModule(chapterID: second, text: "入汴开门"),
            ],
            working: working,
            seeds: [:],
            updatedChapterID: first,
            updatedText: "陈桥改写：风停了",
            markLaterStale: true
        )
        XCTAssertEqual(modules.map(\.chapterID), [first, second])
        XCTAssertEqual(modules.map(\.text), ["陈桥改写：风停了", "入汴开门"])
        XCTAssertEqual(modules.map(\.stale), [false, true])
    }

    func testApplyChapterModuleUpdatesOnlyTheEditedChapter() throws {
        var document = try makeNovelWorkspaceBackupFixture()
        let firstID = document.branches[0].workingChapterSelections[0].chapterID
        let secondID = NovelChapterID()
        let secondVersionID = NovelChapterVersionID()
        let owner = document.appliedOperations[0].operationID
        document.chapters.append(NovelChapterRecord(id: secondID, createdAt: document.project.updatedAt))
        document.chapterVersions.append(
            NovelChapterVersionRecord(
                id: secondVersionID,
                chapterID: secondID,
                kind: .collected,
                title: "入汴",
                content: "城门开了。",
                factCompatibilityID: UUID(),
                sourceChapterVersionID: nil,
                sourceCandidateID: nil,
                createdAt: document.project.updatedAt,
                operationID: owner
            )
        )
        document.branches[0].workingChapterSelections.append(
            NovelChapterSelection(chapterID: secondID, versionID: secondVersionID)
        )
        if let checkpointIndex = document.checkpoints.firstIndex(where: {
            $0.id == document.branches[0].headCheckpointID
        }) {
            let oldCheckpoint = document.checkpoints[checkpointIndex]
            document.checkpoints[checkpointIndex] = NovelBranchCheckpointRecord(
                id: oldCheckpoint.id,
                kind: oldCheckpoint.kind,
                createdOnBranchID: oldCheckpoint.createdOnBranchID,
                parentCheckpointID: oldCheckpoint.parentCheckpointID,
                chapterSelections: document.branches[0].workingChapterSelections,
                stateSnapshotID: oldCheckpoint.stateSnapshotID,
                sessionCursor: oldCheckpoint.sessionCursor,
                branchOverrideRevisionIDs: oldCheckpoint.branchOverrideRevisionIDs,
                sourceCandidateID: oldCheckpoint.sourceCandidateID,
                baseHeadRevision: oldCheckpoint.baseHeadRevision,
                operationID: oldCheckpoint.operationID,
                createdAt: oldCheckpoint.createdAt
            )
        }
        if let snapshotIndex = document.stateSnapshots.firstIndex(where: {
            $0.id == document.branches[0].currentStateSnapshotID
        }) {
            let old = document.stateSnapshots[snapshotIndex]
            document.stateSnapshots[snapshotIndex] = NovelStateSnapshotRecord(
                id: old.id,
                eventIDs: old.eventIDs,
                summary: old.summary,
                branchOutline: old.branchOutline,
                unresolvedEntityNames: old.unresolvedEntityNames,
                createdAt: old.createdAt,
                settingProposalIDs: old.settingProposalIDs,
                characterIdentityClarifications: old.characterIdentityClarifications,
                recentWrittenHighlights: ["山呼：陈桥驿的风先到。", "入汴：城门开了。"],
                chapterPlots: [
                    NovelChapterPlotModule(chapterID: firstID, text: "山呼：陈桥驿的风先到。"),
                    NovelChapterPlotModule(chapterID: secondID, text: "入汴：城门开了。"),
                ]
            )
        }
        try NovelDocumentValidator.validate(document)

        let next = try NovelWorkspacePlotCommit.applyChapterModule(
            to: document,
            branchID: document.branches[0].id,
            chapterID: firstID,
            chapterTitle: "山呼",
            chapterContent: "风停了，赵大还在驿站。"
        )
        XCTAssertEqual(next.branches[0].syncStatus, .synchronized)
        XCTAssertEqual(next.stateSnapshots.last?.summary, "赵大已在陈桥。")
        XCTAssertEqual(
            next.stateSnapshots.last?.recentWrittenHighlights,
            ["山呼：风停了，赵大还在驿站。", "入汴：城门开了。"]
        )
        XCTAssertEqual(
            next.chapterVersions.first { $0.chapterID == secondID }?.content,
            "城门开了。"
        )
        XCTAssertEqual(
            next.stateSnapshots.last?.chapterPlots.map(\.text),
            ["山呼：风停了，赵大还在驿站。", "入汴：城门开了。"]
        )
        XCTAssertEqual(
            next.stateSnapshots.last?.chapterPlots.map(\.stale),
            [false, true]
        )
    }

    func testCheckoutSidecarSkipsSessionOnlyCacheChanges() {
        let sessions = NovelProjectShardedStorage.SectionCacheEntry(
            fingerprint: "s1",
            digest: "sessions-a",
            data: Data()
        )
        let chapters = NovelProjectShardedStorage.SectionCacheEntry(
            fingerprint: "c1",
            digest: "chapters-a",
            data: Data()
        )
        let previous: NovelProjectShardedStorage.SectionCache = [
            NovelProjectShardedStorage.SectionKey.sessions.rawValue: sessions,
            NovelProjectShardedStorage.SectionKey.chapters.rawValue: chapters,
            NovelProjectShardedStorage.SectionKey.project.rawValue:
                NovelProjectShardedStorage.SectionCacheEntry(
                    fingerprint: "p1",
                    digest: "project-a",
                    data: Data()
                ),
            NovelProjectShardedStorage.SectionKey.branches.rawValue:
                NovelProjectShardedStorage.SectionCacheEntry(
                    fingerprint: "b1",
                    digest: "branches-a",
                    data: Data()
                ),
        ]
        var next = previous
        next[NovelProjectShardedStorage.SectionKey.sessions.rawValue] =
            NovelProjectShardedStorage.SectionCacheEntry(
                fingerprint: "s2",
                digest: "sessions-b",
                data: Data()
            )
        XCTAssertFalse(
            NovelProjectShardedStorage.checkoutSidecarNeedsRefresh(
                previous: previous,
                next: next
            )
        )
        next[NovelProjectShardedStorage.SectionKey.project.rawValue] =
            NovelProjectShardedStorage.SectionCacheEntry(
                fingerprint: "p2",
                digest: "project-b",
                data: Data()
            )
        next[NovelProjectShardedStorage.SectionKey.branches.rawValue] =
            NovelProjectShardedStorage.SectionCacheEntry(
                fingerprint: "b2",
                digest: "branches-b",
                data: Data()
            )
        XCTAssertFalse(
            NovelProjectShardedStorage.checkoutSidecarNeedsRefresh(
                previous: previous,
                next: next
            ),
            "Revision and active-run flips must not rebuild checkout."
        )
        next[NovelProjectShardedStorage.SectionKey.chapters.rawValue] =
            NovelProjectShardedStorage.SectionCacheEntry(
                fingerprint: "c2",
                digest: "chapters-b",
                data: Data()
            )
        XCTAssertTrue(
            NovelProjectShardedStorage.checkoutSidecarNeedsRefresh(
                previous: previous,
                next: next
            )
        )
        XCTAssertTrue(
            NovelProjectShardedStorage.checkoutSidecarNeedsRefresh(
                previous: nil,
                next: next
            )
        )
    }

    func testCheckoutWriteFailureMarkerIsVisibleThenCleared() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("checkout-write-failure-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        XCTAssertNil(
            NovelProjectShardedStorage.checkoutWriteFailureMessage(in: directory)
        )
        NovelProjectShardedStorage.recordCheckoutWriteFailure(
            "disk full",
            in: directory,
            fileManager: .default
        )
        XCTAssertEqual(
            NovelProjectShardedStorage.checkoutWriteFailureMessage(in: directory),
            "disk full"
        )
        NovelProjectShardedStorage.clearCheckoutWriteFailure(
            in: directory,
            fileManager: .default
        )
        XCTAssertNil(
            NovelProjectShardedStorage.checkoutWriteFailureMessage(in: directory)
        )
    }

    func testCheckoutWritePreservesLedgerAndAppendsWhenTreeChanges() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("amber-ledger-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let first = try makeNovelWorkspaceBackupFixture()
        try NovelWorkspaceBackup.write(first, to: directory, exportedAt: Date(timeIntervalSince1970: 10))
        let firstStore = NovelWorkspaceLedger.load(from: directory)
        XCTAssertEqual(firstStore.commits.count, 1)
        XCTAssertEqual(firstStore.head, first.branches[0].headCheckpointID.description)
        XCTAssertEqual(
            firstStore.heads[first.branches[0].id.description],
            first.branches[0].headCheckpointID.description
        )

        var second = first
        second.project.revision += 1
        try NovelWorkspaceBackup.write(second, to: directory, exportedAt: Date(timeIntervalSince1970: 20))
        let sameCheckpoint = NovelWorkspaceLedger.load(from: directory)
        XCTAssertEqual(sameCheckpoint.commits.count, 1, "same checkpoint must not add a commit")

        let next = try NovelWorkspacePlotCommit.applyFastForward(
            to: second,
            branchID: second.branches[0].id,
            chapterTitle: "入汴",
            chapterContent: "赵大勒马看城门。"
        )
        try NovelWorkspaceBackup.write(next, to: directory, exportedAt: Date(timeIntervalSince1970: 30))
        let changed = NovelWorkspaceLedger.load(from: directory)
        XCTAssertEqual(changed.commits.count, 2)
        XCTAssertEqual(changed.head, next.branches[0].headCheckpointID.description)
        XCTAssertEqual(changed.headCommit?.parentID, firstStore.head)
        XCTAssertEqual(changed.headCommit?.message, "剧情指针")
    }

    func testLastChapterIsFastForwardAndMiddleChapterIsNot() throws {
        let document = try makeNovelWorkspaceBackupFixture()
        let branch = document.branches[0]
        let last = try XCTUnwrap(branch.workingChapterSelections.last?.chapterID)
        XCTAssertTrue(NovelWorkspaceLedger.isFastForward(branch: branch, chapterID: last))
        XCTAssertTrue(
            NovelWorkspaceLedger.isFastForwardCollect(
                .createNextChapter(chapterID: NovelChapterID(), title: "下一章"),
                branch: branch
            )
        )
        if branch.workingChapterSelections.count > 1 {
            let first = branch.workingChapterSelections[0].chapterID
            XCTAssertFalse(NovelWorkspaceLedger.isFastForward(branch: branch, chapterID: first))
        }
    }

    func testExcerptUsesOpeningParagraphsInsteadOfFirstFortyCharacters() {
        let text = NovelWorkspaceLedger.excerpt(
            title: "入汴",
            content: "赵大勒马看城门。\n城门未关。\n后面还有很多字。"
        )
        XCTAssertTrue(text.hasPrefix("入汴：赵大勒马看城门。城门未关。"))
        XCTAssertGreaterThan(text.count, 20)
        XCTAssertLessThanOrEqual(text.count, NovelStateSnapshotRecord.maxHighlightCharacterCount)
    }

    func testEmptyPlotsSeedFromEachChapterBody() throws {
        let first = NovelChapterID()
        let second = NovelChapterID()
        let working = [
            NovelChapterSelection(chapterID: first, versionID: NovelChapterVersionID()),
            NovelChapterSelection(chapterID: second, versionID: NovelChapterVersionID()),
        ]
        let modules = NovelWorkspaceLedger.relinkChapterPlots(
            existing: [],
            working: working,
            seeds: [
                first: "山呼：陈桥驿的风先到。",
                second: "入汴：城门开了。",
            ],
            updatedChapterID: first,
            updatedText: "山呼：风停了",
            markLaterStale: false
        )
        XCTAssertEqual(modules.map(\.text), ["山呼：风停了", "入汴：城门开了。"])
        XCTAssertEqual(modules.map(\.stale), [false, false])
    }

    func testParseWorkspacePlotDraftReadsChapterAndSummary() throws {
        let draft = try NovelWorkspacePlotDraft.parse(
            """
            # 本章
            - 赵大勒马入城
            - 城门未关

            # 当前
            赵大已经进了汴京，军心未定。
            """
        )
        XCTAssertTrue(draft.chapterText.contains("赵大勒马入城"))
        XCTAssertEqual(draft.summary, "赵大已经进了汴京，军心未定。")
    }

    func testApplyRelinkMarksLaterModulesStaleAfterMiddleDelete() throws {
        var document = try makeNovelWorkspaceBackupFixture()
        let firstID = document.branches[0].workingChapterSelections[0].chapterID
        let secondID = NovelChapterID()
        let secondVersionID = NovelChapterVersionID()
        let owner = document.appliedOperations[0].operationID
        document.chapters.append(NovelChapterRecord(id: secondID, createdAt: document.project.updatedAt))
        document.chapterVersions.append(
            NovelChapterVersionRecord(
                id: secondVersionID,
                chapterID: secondID,
                kind: .collected,
                title: "入汴",
                content: "城门开了。",
                factCompatibilityID: UUID(),
                sourceChapterVersionID: nil,
                sourceCandidateID: nil,
                createdAt: document.project.updatedAt,
                operationID: owner
            )
        )
        document.branches[0].workingChapterSelections.append(
            NovelChapterSelection(chapterID: secondID, versionID: secondVersionID)
        )
        if let checkpointIndex = document.checkpoints.firstIndex(where: {
            $0.id == document.branches[0].headCheckpointID
        }) {
            let old = document.checkpoints[checkpointIndex]
            document.checkpoints[checkpointIndex] = NovelBranchCheckpointRecord(
                id: old.id,
                kind: old.kind,
                createdOnBranchID: old.createdOnBranchID,
                parentCheckpointID: old.parentCheckpointID,
                chapterSelections: document.branches[0].workingChapterSelections,
                stateSnapshotID: old.stateSnapshotID,
                sessionCursor: old.sessionCursor,
                branchOverrideRevisionIDs: old.branchOverrideRevisionIDs,
                sourceCandidateID: old.sourceCandidateID,
                baseHeadRevision: old.baseHeadRevision,
                operationID: old.operationID,
                createdAt: old.createdAt
            )
        }
        if let snapshotIndex = document.stateSnapshots.firstIndex(where: {
            $0.id == document.branches[0].currentStateSnapshotID
        }) {
            let old = document.stateSnapshots[snapshotIndex]
            document.stateSnapshots[snapshotIndex] = NovelStateSnapshotRecord(
                id: old.id,
                eventIDs: old.eventIDs,
                summary: old.summary,
                branchOutline: old.branchOutline,
                unresolvedEntityNames: old.unresolvedEntityNames,
                createdAt: old.createdAt,
                settingProposalIDs: old.settingProposalIDs,
                characterIdentityClarifications: old.characterIdentityClarifications,
                recentWrittenHighlights: ["山呼：陈桥驿的风先到。", "入汴：城门开了。"],
                chapterPlots: [
                    NovelChapterPlotModule(chapterID: firstID, text: "山呼：陈桥驿的风先到。"),
                    NovelChapterPlotModule(chapterID: secondID, text: "入汴：城门开了。"),
                ]
            )
        }
        document.branches[0].workingChapterSelections.removeAll { $0.chapterID == firstID }
        document.branches[0].syncStatus = .needsSync

        let next = try NovelWorkspacePlotCommit.applyRelink(
            to: document,
            branchID: document.branches[0].id
        )
        XCTAssertEqual(next.stateSnapshots.last?.chapterPlots.map(\.chapterID), [secondID])
        XCTAssertEqual(next.stateSnapshots.last?.chapterPlots.map(\.stale), [true])
        XCTAssertEqual(next.branches[0].syncStatus, .synchronized)
    }

    func testApplyRelinkOnDeviceZhaoDaPackage() throws {
        let package = URL(fileURLWithPath: "/tmp/amber-repro-pkg")
        let layout = package.appendingPathComponent("layout.json")
        guard FileManager.default.fileExists(atPath: layout.path) else {
            throw XCTSkip("Device package is not copied to /tmp/amber-repro-pkg")
        }
        let projectID = NovelProjectID(
            rawValue: UUID(uuidString: "AE60366A-C41A-4117-B39E-A69678F165D9")!
        )
        let loaded = try NovelProjectShardedStorage.loadDocument(
            packageDirectory: package,
            projectID: projectID,
            decoder: JSONDecoder(),
            fileManager: .default
        )
        let document = loaded.document
        let branch = try XCTUnwrap(document.branches.first)
        do {
            try NovelDocumentValidator.validate(document)
        } catch {
            XCTFail("on-disk document already invalid: \(error)")
            return
        }
        do {
            let next = try NovelWorkspacePlotCommit.applyRelink(
                to: document,
                branchID: branch.id
            )
            XCTAssertEqual(next.branches[0].syncStatus, .synchronized)
            XCTAssertTrue(next.pendingOperations.filter { $0.kind == .manualSync }.isEmpty)
        } catch {
            XCTFail("applyRelink failed: \(error)")
        }
    }

    func testRecordMovesHeadOnRevertWithoutAddingACommit() throws {
        let document = try makeNovelWorkspaceBackupFixture()
        let firstHead = document.branches[0].headCheckpointID
        var store = NovelWorkspaceLedger.record(document, into: .init())
        XCTAssertEqual(store.commits.count, 1)

        let afterEdit = try NovelWorkspacePlotCommit.applyChapterModule(
            to: document,
            branchID: document.branches[0].id,
            chapterID: document.branches[0].workingChapterSelections[0].chapterID,
            chapterTitle: "山呼",
            chapterContent: "风停了。"
        )
        store = NovelWorkspaceLedger.record(afterEdit, into: store)
        XCTAssertEqual(store.commits.count, 2)
        XCTAssertEqual(store.head, afterEdit.branches[0].headCheckpointID.description)

        var reverted = afterEdit
        reverted.branches[0].headCheckpointID = firstHead
        reverted.branches[0].currentStateSnapshotID = reverted.checkpoints.first {
            $0.id == firstHead
        }!.stateSnapshotID
        store = NovelWorkspaceLedger.record(reverted, into: store)
        XCTAssertEqual(store.commits.count, 2)
        XCTAssertEqual(store.head, firstHead.description)
        XCTAssertEqual(
            store.heads[reverted.branches[0].id.description],
            firstHead.description
        )
    }

    func testReconcileRefreshesChangedChapterAndMarksLaterStale() {
        let first = NovelChapterID()
        let second = NovelChapterID()
        let firstVersion = NovelChapterVersionID()
        let secondVersion = NovelChapterVersionID()
        let editedVersion = NovelChapterVersionID()
        let working = [
            NovelChapterSelection(chapterID: first, versionID: editedVersion),
            NovelChapterSelection(chapterID: second, versionID: secondVersion),
        ]
        let modules = NovelWorkspaceLedger.reconcileModules(
            existing: [
                NovelChapterPlotModule(chapterID: first, text: "山呼：旧稿"),
                NovelChapterPlotModule(chapterID: second, text: "入汴：城门开了"),
            ],
            working: working,
            seeds: [
                first: "山呼：风停了",
                second: "入汴：城门开了",
            ],
            headSelections: [
                NovelChapterSelection(chapterID: first, versionID: firstVersion),
                NovelChapterSelection(chapterID: second, versionID: secondVersion),
            ]
        )
        XCTAssertEqual(modules.map(\.text), ["山呼：风停了", "入汴：城门开了"])
        XCTAssertEqual(modules.map(\.stale), [false, true])
    }

    func testAcceptStaleClearsLaterPlotFlags() throws {
        var document = try makeNovelWorkspaceBackupFixture()
        let firstID = document.branches[0].workingChapterSelections[0].chapterID
        if let snapshotIndex = document.stateSnapshots.firstIndex(where: {
            $0.id == document.branches[0].currentStateSnapshotID
        }) {
            let old = document.stateSnapshots[snapshotIndex]
            document.stateSnapshots[snapshotIndex] = NovelStateSnapshotRecord(
                id: old.id,
                eventIDs: old.eventIDs,
                summary: old.summary,
                branchOutline: old.branchOutline,
                unresolvedEntityNames: old.unresolvedEntityNames,
                createdAt: old.createdAt,
                settingProposalIDs: old.settingProposalIDs,
                characterIdentityClarifications: old.characterIdentityClarifications,
                recentWrittenHighlights: old.recentWrittenHighlights,
                chapterPlots: [
                    NovelChapterPlotModule(chapterID: firstID, text: "山呼：风停了", stale: false),
                    NovelChapterPlotModule(chapterID: NovelChapterID(), text: "入汴：城门开了", stale: true),
                ]
            )
        }
        let next = try NovelWorkspacePlotCommit.applyAcceptStale(
            to: document,
            branchID: document.branches[0].id
        )
        XCTAssertEqual(next.stateSnapshots.last?.chapterPlots.map(\.stale), [false, false])
        XCTAssertEqual(next.branches[0].syncStatus, .synchronized)
        XCTAssertFalse(next.stateSnapshots.last?.hasStaleChapterPlots == true)
    }

    func testPublishMarksWorktreeAsBookAndCoversWorkingChapters() throws {
        let document = try makeNovelWorkspaceBackupFixture()
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("worktree-authority-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        XCTAssertFalse(
            NovelWorkspaceAuthority.worktreeCoversWorkingManuscript(
                document,
                checkoutDirectory: directory
            )
        )
        try NovelWorkspaceAuthority.publish(document, to: directory)
        XCTAssertTrue(
            NovelWorkspaceAuthority.worktreeCoversWorkingManuscript(
                document,
                checkoutDirectory: directory
            )
        )
        XCTAssertTrue(NovelWorkspaceAuthority.isWorktreeBook(at: directory))
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: directory.path + ".next")
        )
        let files = try XCTUnwrap(NovelWorkspaceAuthority.filesOnDisk(at: directory))
        XCTAssertTrue(files.contains(where: { $0.path.hasSuffix("manifest.yaml") }))
        XCTAssertTrue(files.contains(where: { $0.path.contains("/chapters/") }))
    }

    func testWorktreeCoverFailsWhenAChapterBodyDrifts() throws {
        let document = try makeNovelWorkspaceBackupFixture()
        let files = try NovelWorkspaceBackup.export(document)
        XCTAssertTrue(
            NovelWorkspaceAuthority.worktreeCoversWorkingManuscript(document, files: files)
        )
        var drifted = files
        guard let index = drifted.firstIndex(where: { $0.path.contains("/chapters/") }) else {
            return XCTFail("fixture must export a chapter file")
        }
        drifted[index] = NovelWorkspaceBackup.File(
            path: drifted[index].path,
            contents: drifted[index].contents + "\n被改过的复印件。"
        )
        XCTAssertFalse(
            NovelWorkspaceAuthority.worktreeCoversWorkingManuscript(document, files: drifted)
        )
    }

    func testPublishDraftsAddsAndRemovesDraftsWithoutTouchingChapters() throws {
        var document = try makeNovelWorkspaceBackupFixture()
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("worktree-drafts-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try NovelWorkspaceAuthority.publish(document, to: directory)

        let chapterRoot = directory.appendingPathComponent("branches", isDirectory: true)
        let chapterFiles = try FileManager.default.subpathsOfDirectory(atPath: chapterRoot.path)
            .filter { $0.contains("/chapters/") && $0.hasSuffix(".md") }
            .map { chapterRoot.appendingPathComponent($0) }
        let chapterBefore = try XCTUnwrap(chapterFiles.first)
        let chapterBytes = try Data(contentsOf: chapterBefore)
        let chapterModified = try chapterBefore.resourceValues(
            forKeys: [.contentModificationDateKey]
        ).contentModificationDate

        let candidateID = NovelCandidateID()
        document.candidates.append(
            NovelCandidateRecord(
                id: candidateID,
                kind: .prose,
                branchID: document.branches[0].id,
                sessionID: document.sessions[0].id,
                sourceMessageID: NovelMessageID(),
                baseCheckpointID: document.branches[0].headCheckpointID,
                baseHeadRevision: document.branches[0].headRevision,
                status: .available,
                content: "新草稿正文。",
                sourceChapterVersionID: nil,
                collectedCheckpointID: nil,
                createdAt: Date()
            )
        )
        try NovelWorkspaceAuthority.publishDrafts(document, to: directory)

        XCTAssertEqual(try Data(contentsOf: chapterBefore), chapterBytes)
        XCTAssertEqual(
            try chapterBefore.resourceValues(forKeys: [.contentModificationDateKey])
                .contentModificationDate,
            chapterModified
        )
        let draftsDir = directory.appendingPathComponent("drafts")
        let draftFiles = try FileManager.default.contentsOfDirectory(
            at: draftsDir,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ).filter { $0.pathExtension == "md" }
        XCTAssertEqual(draftFiles.count, 1)
        let draftText = try String(contentsOf: try XCTUnwrap(draftFiles.first), encoding: .utf8)
        XCTAssertTrue(draftText.contains("新草稿正文。"))
        XCTAssertTrue(draftText.contains(candidateID.description))
        XCTAssertEqual(
            NovelWorkspaceAuthority.draftBody(candidateID: candidateID, in: directory),
            "新草稿正文。"
        )
        XCTAssertTrue(
            NovelWorkspaceAuthority.draftBodiesMatch(
                "\r\n新草稿正文。\n",
                "新草稿正文。"
            )
        )
        XCTAssertFalse(
            NovelWorkspaceAuthority.draftBodiesMatch("新草稿正文。", "另一篇。")
        )
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: draftsDir.path + ".next")
                || FileManager.default.fileExists(
                    atPath: directory.appendingPathComponent("drafts.next").path
                )
        )

        let index = try XCTUnwrap(document.candidates.firstIndex(where: { $0.id == candidateID }))
        document.candidates[index].status = .adopted
        try NovelWorkspaceAuthority.publishDrafts(document, to: directory)
        let remaining = try FileManager.default.contentsOfDirectory(
            at: draftsDir,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ).filter { $0.pathExtension == "md" }
        XCTAssertTrue(remaining.isEmpty)
        XCTAssertNil(
            NovelWorkspaceAuthority.draftBody(candidateID: candidateID, in: directory)
        )
    }

    func testCheckoutDraftsNeedRefreshIgnoresUnchangedCandidateDigest() {
        let entry = NovelProjectShardedStorage.SectionCacheEntry(
            fingerprint: "c1",
            digest: "same",
            data: Data()
        )
        let previous = [NovelProjectShardedStorage.SectionKey.candidates.rawValue: entry]
        var next = previous
        XCTAssertFalse(
            NovelProjectShardedStorage.checkoutDraftsNeedRefresh(previous: previous, next: next)
        )
        next[NovelProjectShardedStorage.SectionKey.candidates.rawValue] =
            NovelProjectShardedStorage.SectionCacheEntry(
                fingerprint: "c2",
                digest: "changed",
                data: Data()
            )
        XCTAssertTrue(
            NovelProjectShardedStorage.checkoutDraftsNeedRefresh(previous: previous, next: next)
        )
        XCTAssertFalse(
            NovelProjectShardedStorage.checkoutSidecarNeedsRefresh(previous: previous, next: next),
            "candidate-only changes must not reprint the whole worktree"
        )
    }
}
