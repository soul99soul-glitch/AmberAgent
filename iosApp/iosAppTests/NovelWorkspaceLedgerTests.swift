import XCTest
@testable import iosApp

final class NovelWorkspaceLedgerTests: XCTestCase {
    func testCommitStatusReportsChangedBookFiles() {
        let first = [
            NovelWorkspaceBackup.File(path: "branches/main/chapters/001.md", contents: "old"),
            NovelWorkspaceBackup.File(path: "branches/main/plot/current.md", contents: "s1"),
        ]
        let commit = NovelWorkspaceLedger.makeCommit(
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
            ["入汴：赵大勒马看城门。"]
        )
        XCTAssertEqual(
            next.stateSnapshots.last?.chapterPlots.last?.text,
            "入汴：赵大勒马看城门。"
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
            previousHighlights: ["陈桥起事", "入汴开门"],
            updatedChapterID: first,
            updatedText: "陈桥改写：风停了"
        )
        XCTAssertEqual(modules.map(\.chapterID), [first, second])
        XCTAssertEqual(modules.map(\.text), ["陈桥改写：风停了", "入汴开门"])
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
    }

    func testCheckoutWritePreservesLedgerAndAppendsWhenTreeChanges() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("amber-ledger-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let first = try makeNovelWorkspaceBackupFixture()
        try NovelWorkspaceBackup.write(first, to: directory, exportedAt: Date(timeIntervalSince1970: 10))
        let firstStore = NovelWorkspaceLedger.load(from: directory)
        XCTAssertEqual(firstStore.commits.count, 1)
        XCTAssertEqual(firstStore.headCommit?.message, "r\(first.project.revision)")

        var second = first
        second.project.revision += 1
        try NovelWorkspaceBackup.write(second, to: directory, exportedAt: Date(timeIntervalSince1970: 20))
        let sameTree = NovelWorkspaceLedger.load(from: directory)
        XCTAssertEqual(sameTree.commits.count, 1, "identical book tree must not add a commit")

        if let index = second.stateSnapshots.firstIndex(where: {
            $0.id == second.branches[0].currentStateSnapshotID
        }) {
            let old = second.stateSnapshots[index]
            second.stateSnapshots[index] = NovelStateSnapshotRecord(
                id: old.id,
                eventIDs: old.eventIDs,
                summary: "城门已开。",
                branchOutline: old.branchOutline,
                unresolvedEntityNames: old.unresolvedEntityNames,
                createdAt: old.createdAt,
                settingProposalIDs: old.settingProposalIDs,
                characterIdentityClarifications: old.characterIdentityClarifications,
                recentWrittenHighlights: old.recentWrittenHighlights
            )
        }
        second.project.revision += 1
        try NovelWorkspaceBackup.write(second, to: directory, exportedAt: Date(timeIntervalSince1970: 30))
        let changed = NovelWorkspaceLedger.load(from: directory)
        XCTAssertEqual(changed.commits.count, 2)
        XCTAssertEqual(changed.headCommit?.parentID, firstStore.head)
        XCTAssertEqual(changed.headCommit?.message, "r\(second.project.revision)")
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
}
