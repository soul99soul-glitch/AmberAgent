import Foundation
import XCTest
@testable import iosApp

final class NovelBranchLifecycleTests: XCTestCase {
    func testValidatorRejectsHeadMoveWithoutUndoLedger() throws {
        let collected = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let branchBefore = collected.branches[0]
        let current = try NovelBranchTestFixtures.checkpoint(
            branchBefore.headCheckpointID,
            in: collected
        )
        let parent = try NovelBranchTestFixtures.checkpoint(
            try XCTUnwrap(current.parentCheckpointID),
            in: collected
        )
        var forged = collected
        forged.branches[0].headCheckpointID = parent.id
        forged.branches[0].currentStateSnapshotID = parent.stateSnapshotID
        forged.branches[0].workingChapterSelections = parent.chapterSelections
        forged.branches[0].overrideRevisionIDs = parent.branchOverrideRevisionIDs
        forged.branches[0].headRevision += 1
        forged.branches[0].workingRevision += 1
        forged.branches[0].updatedAt = forged.project.updatedAt.addingTimeInterval(1)
        forged.project.revision += 1
        forged.project.updatedAt = forged.project.updatedAt.addingTimeInterval(1)

        XCTAssertThrowsError(
            try NovelDocumentValidator.validateTransition(from: collected, to: forged)
        ) { error in
            guard case .invalidDocument(let issues) = error as? NovelError else {
                return XCTFail("Expected invalidDocument, got \(error)")
            }
            XCTAssertFalse(issues.isEmpty)
        }
    }

    func testValidatorRejectsCandidateCloneWithoutCloneLedger() throws {
        let collected = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let branchID = collected.branches[0].id
        let sourceCandidateID = try XCTUnwrap(collected.candidates.first?.id)
        let undone = try NovelReducer.apply(
            .undoBranchHead(undoCommand(document: collected, branchID: branchID)),
            to: collected
        ).document
        let cloneID = NovelCandidateID()
        let cloned = try NovelReducer.apply(
            .cloneCandidate(cloneCommand(
                document: undone,
                branchID: branchID,
                sourceCandidateID: sourceCandidateID,
                candidateID: cloneID
            )),
            to: undone
        ).document
        var forged = cloned
        XCTAssertEqual(forged.appliedOperations.last?.kind, .cloneCandidate)
        forged.appliedOperations.removeLast()

        XCTAssertThrowsError(
            try NovelDocumentValidator.validateTransition(from: undone, to: forged)
        ) { error in
            guard case .invalidDocument(let issues) = error as? NovelError else {
                return XCTFail("Expected invalidDocument, got \(error)")
            }
            XCTAssertFalse(issues.isEmpty)
        }
    }

    func testUndoMovesOnlyHeadAndPreservesImmutableHistoryAndCandidateCollection() throws {
        let collected = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let branchBefore = collected.branches[0]
        let currentCheckpoint = try NovelBranchTestFixtures.checkpoint(
            branchBefore.headCheckpointID,
            in: collected
        )
        let parentID = try XCTUnwrap(currentCheckpoint.parentCheckpointID)
        let parentCheckpoint = try NovelBranchTestFixtures.checkpoint(parentID, in: collected)
        let candidateHistory = collected.candidates
        let sessionHistory = collected.sessions
        let appliedCount = collected.appliedOperations.count

        let undone = try NovelReducer.apply(
            .undoBranchHead(undoCommand(document: collected, branchID: branchBefore.id)),
            to: collected
        ).document
        let branchAfter = try NovelBranchTestFixtures.branch(branchBefore.id, in: undone)

        XCTAssertEqual(branchAfter.headCheckpointID, parentCheckpoint.id)
        XCTAssertEqual(branchAfter.currentStateSnapshotID, parentCheckpoint.stateSnapshotID)
        XCTAssertEqual(branchAfter.workingChapterSelections, parentCheckpoint.chapterSelections)
        XCTAssertEqual(branchAfter.overrideRevisionIDs, parentCheckpoint.branchOverrideRevisionIDs)
        XCTAssertEqual(branchAfter.headRevision, branchBefore.headRevision + 1)
        XCTAssertEqual(branchAfter.workingRevision, branchBefore.workingRevision + 1)
        XCTAssertEqual(branchAfter.syncStatus, .synchronized)
        XCTAssertEqual(undone.project.revision, collected.project.revision + 1)
        XCTAssertEqual(undone.project.configRevision, collected.project.configRevision)
        XCTAssertEqual(undone.candidates, candidateHistory)
        XCTAssertEqual(undone.sessions, sessionHistory)
        XCTAssertEqual(undone.appliedOperations.count, appliedCount + 1)
        XCTAssertEqual(undone.appliedOperations.last?.kind, .undoBranchHead)
        NovelBranchTestFixtures.assertGlobalImmutableRecordsEqual(
            collected,
            undone,
            file: #filePath,
            line: #line
        )
        XCTAssertNoThrow(try NovelDocumentValidator.validate(undone))

        let secondUndo = undoCommand(document: undone, branchID: branchBefore.id)
        XCTAssertThrowsError(try NovelReducer.apply(.undoBranchHead(secondUndo), to: undone)) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected history-boundary invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("history boundary"))
        }
    }

    func testUndoRejectsNeedsSyncPendingRunAndWorkingRevisionDrift() throws {
        let collected = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let branchID = collected.branches[0].id

        let edited = try NovelBranchTestFixtures.saveManualEdit(
            in: collected,
            branchID: branchID,
            content: "Mara rewrote the collected fact before synchronizing."
        )
        let needsSyncUndo = undoCommand(document: edited, branchID: branchID)
        XCTAssertThrowsError(
            try NovelReducer.apply(.undoBranchHead(needsSyncUndo), to: edited)
        ) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected needsSync invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("Synchronize"))
        }

        let editedBranch = try NovelBranchTestFixtures.branch(branchID, in: edited)
        let sync = NovelSyncManualEditsCommand(
            context: NovelBranchTestFixtures.mutationContext(
                document: edited,
                branchID: branchID
            ),
            projectID: edited.project.id,
            branchID: branchID,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: editedBranch.workingRevision
        )
        let pending = try NovelFactTransactionReducer.prepareManualSync(
            sync,
            payloadSHA256: sync.canonicalPayloadSHA256(),
            in: edited
        ).document
        let pendingUndo = undoCommand(document: pending, branchID: branchID)
        XCTAssertThrowsError(
            try NovelReducer.apply(.undoBranchHead(pendingUndo), to: pending)
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectBusy(pending.project.id))
        }

        let running = try NovelBranchTestFixtures.beginDiscussionRun(
            in: collected,
            branchID: branchID
        )
        let runningUndo = undoCommand(document: running, branchID: branchID)
        XCTAssertThrowsError(
            try NovelReducer.apply(.undoBranchHead(runningUndo), to: running)
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectBusy(running.project.id))
        }

        let drifted = NovelUndoBranchHeadCommand(
            context: NovelBranchTestFixtures.mutationContext(
                document: collected,
                branchID: branchID
            ),
            projectID: collected.project.id,
            branchID: branchID,
            expectedWorkingRevision: collected.branches[0].workingRevision + 1
        )
        XCTAssertThrowsError(
            try NovelReducer.apply(.undoBranchHead(drifted), to: collected)
        ) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected working revision invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("Synchronize"))
        }
    }

    func testCollectUndoCloneRecollectRepeatedChainSurvivesRestart() async throws {
        let firstCollection = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "Mara crossed the threshold."
        )
        let branchID = firstCollection.branches[0].id
        let rootCandidate = try XCTUnwrap(firstCollection.candidates.first)
        let firstCheckpointID = try XCTUnwrap(rootCandidate.collectedCheckpointID)

        let firstUndo = try NovelReducer.apply(
            .undoBranchHead(undoCommand(document: firstCollection, branchID: branchID)),
            to: firstCollection
        ).document
        let firstCloneID = NovelCandidateID()
        let firstClone = try NovelReducer.apply(
            .cloneCandidate(cloneCommand(
                document: firstUndo,
                branchID: branchID,
                sourceCandidateID: rootCandidate.id,
                candidateID: firstCloneID
            )),
            to: firstUndo
        ).document
        let secondCollection = try NovelBranchTestFixtures.collectCandidate(
            firstCloneID,
            in: firstClone,
            title: "Chapter One Again"
        )

        let secondUndo = try NovelReducer.apply(
            .undoBranchHead(undoCommand(document: secondCollection, branchID: branchID)),
            to: secondCollection
        ).document
        let secondCloneID = NovelCandidateID()
        let secondClone = try NovelReducer.apply(
            .cloneCandidate(cloneCommand(
                document: secondUndo,
                branchID: branchID,
                sourceCandidateID: firstCloneID,
                candidateID: secondCloneID
            )),
            to: secondUndo
        ).document
        let thirdCollection = try NovelBranchTestFixtures.collectCandidate(
            secondCloneID,
            in: secondClone,
            title: "Chapter One, Third Path"
        )

        let root = try NovelBranchTestFixtures.candidate(rootCandidate.id, in: thirdCollection)
        let first = try NovelBranchTestFixtures.candidate(firstCloneID, in: thirdCollection)
        let second = try NovelBranchTestFixtures.candidate(secondCloneID, in: thirdCollection)
        let collectionCheckpointIDs = try [root, first, second].map {
            try XCTUnwrap($0.collectedCheckpointID)
        }

        XCTAssertEqual(root.status, .collected)
        XCTAssertEqual(first.status, .collected)
        XCTAssertEqual(second.status, .collected)
        XCTAssertNil(root.clonedFromCandidateID)
        XCTAssertEqual(first.clonedFromCandidateID, root.id)
        XCTAssertEqual(second.clonedFromCandidateID, first.id)
        XCTAssertEqual(Set([root.content, first.content, second.content]), [root.content])
        XCTAssertEqual(Set([root.sourceMessageID, first.sourceMessageID, second.sourceMessageID]).count, 1)
        XCTAssertEqual(Set(collectionCheckpointIDs).count, 3)
        XCTAssertTrue(collectionCheckpointIDs.contains(firstCheckpointID))
        XCTAssertEqual(thirdCollection.checkpoints.filter { $0.kind == .collection }.count, 3)
        XCTAssertEqual(thirdCollection.chapterVersions.filter { $0.kind == .collected }.count, 3)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(thirdCollection))

        let directory = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let repository = NovelFileProjectRepository(rootDirectory: directory)
        _ = try await repository.createProject(thirdCollection)
        let restarted = NovelFileProjectRepository(rootDirectory: directory)
        let loaded = try await restarted.loadProject(id: thirdCollection.project.id)

        XCTAssertEqual(loaded.document, thirdCollection)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(loaded.document))
    }

    func testRenameSetMainAndDeleteParentPreserveChildrenAndImmutableRecords() async throws {
        let collected = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let parentID = collected.branches[0].id
        let checkpointID = collected.branches[0].headCheckpointID
        let childFork = NovelBranchTestFixtures.forkCommand(
            document: collected,
            sourceBranchID: parentID,
            checkpointID: checkpointID,
            name: "Child"
        )
        let forked = try NovelReducer.apply(.forkBranch(childFork), to: collected).document
        let childBeforeRename = try NovelBranchTestFixtures.branch(childFork.branchID, in: forked)
        let rename = NovelRenameBranchCommand(
            context: NovelBranchTestFixtures.mutationContext(
                document: forked,
                branchID: childFork.branchID
            ),
            projectID: forked.project.id,
            branchID: childFork.branchID,
            name: "  Alternate Child  "
        )
        let renamed = try NovelReducer.apply(.renameBranch(rename), to: forked).document
        let renamedChild = try NovelBranchTestFixtures.branch(childFork.branchID, in: renamed)
        XCTAssertEqual(renamedChild.name, "Alternate Child")
        XCTAssertEqual(renamedChild.headCheckpointID, childBeforeRename.headCheckpointID)
        NovelBranchTestFixtures.assertGlobalImmutableRecordsEqual(
            forked,
            renamed,
            file: #filePath,
            line: #line
        )

        let grandchildFork = NovelBranchTestFixtures.forkCommand(
            document: renamed,
            sourceBranchID: childFork.branchID,
            checkpointID: checkpointID,
            name: "Grandchild"
        )
        let hierarchy = try NovelReducer.apply(.forkBranch(grandchildFork), to: renamed).document

        let staleRename = NovelRenameBranchCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: hierarchy.project.revision - 1,
                expectedConfigRevision: hierarchy.project.configRevision,
                expectedBranchHeadRevision: hierarchy.branches.first {
                    $0.id == childFork.branchID
                }?.headRevision
            ),
            projectID: hierarchy.project.id,
            branchID: childFork.branchID,
            name: "Stale"
        )
        XCTAssertThrowsError(try NovelReducer.apply(.renameBranch(staleRename), to: hierarchy)) {
            guard case .staleProjectRevision = $0 as? NovelError else {
                return XCTFail("Expected staleProjectRevision, got \($0)")
            }
        }

        let runningChild = try NovelBranchTestFixtures.beginDiscussionRun(
            in: hierarchy,
            branchID: childFork.branchID
        )
        XCTAssertThrowsError(try NovelReducer.apply(
            .deleteBranch(deleteCommand(
                document: runningChild,
                branchID: childFork.branchID
            )),
            to: runningChild
        )) { error in
            XCTAssertEqual(error as? NovelError, .projectBusy(runningChild.project.id))
        }

        let childCandidateRun = try NovelBranchTestFixtures.appendCompletedRun(
            to: hierarchy,
            branchID: childFork.branchID,
            kind: .prose,
            content: "Mara chose a new road on the child branch."
        )
        let pendingChild = try NovelBranchTestFixtures.prepareCollection(
            try XCTUnwrap(childCandidateRun.candidateID),
            in: childCandidateRun.document
        ).document
        XCTAssertThrowsError(try NovelReducer.apply(
            .deleteBranch(deleteCommand(
                document: pendingChild,
                branchID: childFork.branchID
            )),
            to: pendingChild
        )) { error in
            XCTAssertEqual(error as? NovelError, .projectBusy(pendingChild.project.id))
        }

        XCTAssertThrowsError(try NovelReducer.apply(
            .deleteBranch(deleteCommand(document: hierarchy, branchID: parentID)),
            to: hierarchy
        )) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected main-branch guard, got \(error)")
            }
            XCTAssertTrue(message.contains("main branch"))
        }

        let mainChanged = try NovelReducer.apply(
            .setMainBranch(setMainCommand(
                document: hierarchy,
                branchID: grandchildFork.branchID
            )),
            to: hierarchy
        ).document
        let childBeforeDelete = try NovelBranchTestFixtures.branch(childFork.branchID, in: mainChanged)
        let grandchildBeforeDelete = try NovelBranchTestFixtures.branch(
            grandchildFork.branchID,
            in: mainChanged
        )
        let sessionsBeforeDelete = mainChanged.sessions
        let candidatesBeforeDelete = mainChanged.candidates
        let deleted = try NovelReducer.apply(
            .deleteBranch(deleteCommand(document: mainChanged, branchID: parentID)),
            to: mainChanged
        ).document

        XCTAssertEqual(deleted.project.mainBranchID, grandchildFork.branchID)
        XCTAssertEqual(try NovelBranchTestFixtures.branch(parentID, in: deleted).lifecycle, .deleted)
        XCTAssertEqual(
            try NovelBranchTestFixtures.branch(childFork.branchID, in: deleted),
            childBeforeDelete
        )
        XCTAssertEqual(
            try NovelBranchTestFixtures.branch(grandchildFork.branchID, in: deleted),
            grandchildBeforeDelete
        )
        XCTAssertEqual(childBeforeDelete.forkOrigin?.parentBranchID, parentID)
        XCTAssertEqual(grandchildBeforeDelete.forkOrigin?.parentBranchID, childFork.branchID)
        XCTAssertEqual(deleted.sessions, sessionsBeforeDelete)
        XCTAssertEqual(deleted.candidates, candidatesBeforeDelete)
        NovelBranchTestFixtures.assertGlobalImmutableRecordsEqual(
            mainChanged,
            deleted,
            file: #filePath,
            line: #line
        )
        XCTAssertNoThrow(try NovelDocumentValidator.validate(deleted))

        XCTAssertThrowsError(try NovelReducer.apply(
            .setMainBranch(setMainCommand(document: deleted, branchID: parentID)),
            to: deleted
        )) { error in
            XCTAssertEqual(error as? NovelError, .branchNotFound(parentID))
        }
        XCTAssertThrowsError(try NovelReducer.apply(
            .renameBranch(NovelRenameBranchCommand(
                context: NovelBranchTestFixtures.mutationContext(
                    document: deleted,
                    branchID: parentID
                ),
                projectID: deleted.project.id,
                branchID: parentID,
                name: "Cannot Reactivate"
            )),
            to: deleted
        )) { error in
            XCTAssertEqual(error as? NovelError, .branchNotFound(parentID))
        }

        let directory = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let repository = NovelFileProjectRepository(rootDirectory: directory)
        _ = try await repository.createProject(deleted)
        let restarted = NovelFileProjectRepository(rootDirectory: directory)
        let loaded = try await restarted.loadProject(id: deleted.project.id)

        XCTAssertEqual(loaded.document, deleted)
        XCTAssertEqual(
            try NovelBranchTestFixtures.branch(childFork.branchID, in: loaded.document).lifecycle,
            .active
        )
        XCTAssertEqual(
            try NovelBranchTestFixtures.branch(parentID, in: loaded.document).lifecycle,
            .deleted
        )
        XCTAssertNoThrow(try NovelDocumentValidator.validate(loaded.document))
    }
}

private extension NovelBranchLifecycleTests {
    func undoCommand(
        document: NovelProjectDocumentV1,
        branchID: NovelBranchID
    ) -> NovelUndoBranchHeadCommand {
        let branch = document.branches.first { $0.id == branchID }
        return NovelUndoBranchHeadCommand(
            context: NovelBranchTestFixtures.mutationContext(
                document: document,
                branchID: branchID
            ),
            projectID: document.project.id,
            branchID: branchID,
            expectedWorkingRevision: branch?.workingRevision ?? -1
        )
    }

    func cloneCommand(
        document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        sourceCandidateID: NovelCandidateID,
        candidateID: NovelCandidateID
    ) -> NovelCloneCandidateCommand {
        NovelCloneCandidateCommand(
            context: NovelBranchTestFixtures.mutationContext(
                document: document,
                branchID: branchID
            ),
            projectID: document.project.id,
            branchID: branchID,
            sourceCandidateID: sourceCandidateID,
            candidateID: candidateID
        )
    }

    func deleteCommand(
        document: NovelProjectDocumentV1,
        branchID: NovelBranchID
    ) -> NovelDeleteBranchCommand {
        NovelDeleteBranchCommand(
            context: NovelBranchTestFixtures.mutationContext(
                document: document,
                branchID: branchID
            ),
            projectID: document.project.id,
            branchID: branchID
        )
    }

    func setMainCommand(
        document: NovelProjectDocumentV1,
        branchID: NovelBranchID
    ) -> NovelSetMainBranchCommand {
        NovelSetMainBranchCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: document.project.revision,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: document.project.id,
            branchID: branchID
        )
    }
}
