import XCTest
@testable import iosApp

final class NovelReducerTests: XCTestCase {
    func testBlankProjectCreatesInitialCheckpointStateBranchAndSession() throws {
        let command = NovelTestFixtures.createCommand()
        let result = try NovelReducer.createProject(
            command,
            now: Date(timeIntervalSince1970: 100)
        )
        let document = result.document

        XCTAssertEqual(document.project.revision, 1)
        XCTAssertEqual(document.project.configRevision, 1)
        XCTAssertEqual(document.project.mainBranchID, command.branchID)
        XCTAssertEqual(document.branches.count, 1)
        XCTAssertEqual(document.sessions.count, 1)
        XCTAssertEqual(document.stateSnapshots.count, 1)
        XCTAssertEqual(document.checkpoints.count, 1)
        XCTAssertEqual(document.checkpoints.first?.kind, .initial)
        XCTAssertEqual(document.branches.first?.headCheckpointID, command.initialCheckpointID)
        XCTAssertEqual(document.branches.first?.currentStateSnapshotID, command.initialStateSnapshotID)
        XCTAssertEqual(document.branches.first?.workingChapterSelections, [])
        XCTAssertEqual(document.branches.first?.headRevision, 0)
        XCTAssertEqual(document.branches.first?.workingRevision, 0)
        XCTAssertEqual(document.appliedOperations.first?.outcome, result.outcome)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(document))
    }

    func testMaterialRevisionIsAppendOnlyAndDoesNotChangeBranchHeads() throws {
        let source = try NovelTestFixtures.documentWithForkableCheckpoint()
        let parent = source.branches[0]
        let childID = NovelBranchID()
        let childSessionID = NovelSessionID()
        let document = try NovelReducer.apply(.forkBranch(NovelForkBranchCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: source.project.revision,
                expectedConfigRevision: source.project.configRevision,
                expectedBranchHeadRevision: parent.headRevision
            ),
            projectID: source.project.id,
            sourceBranchID: parent.id,
            checkpointID: parent.headCheckpointID,
            branchID: childID,
            sessionID: childSessionID,
            name: "Alternate"
        )), to: source).document
        try NovelDocumentValidator.validate(document)

        let beforeBranches = document.branches
        let materialID = NovelMaterialID()
        let first = try NovelReducer.apply(
            NovelTestFixtures.materialAction(document: document, materialID: materialID),
            to: document
        ).document
        let oldRevision = first.materialRevisions[0]
        let second = try NovelReducer.apply(
            NovelTestFixtures.materialAction(
                document: first,
                materialID: materialID,
                revisionID: NovelMaterialRevisionID(),
                operationID: NovelOperationID(),
                content: "Magic always leaves a trace."
            ),
            to: first
        ).document

        XCTAssertEqual(first.project.revision, document.project.revision + 1)
        XCTAssertEqual(first.project.configRevision, document.project.configRevision + 1)
        XCTAssertEqual(first.branches, beforeBranches)
        XCTAssertEqual(second.materialRevisions.count, 2)
        XCTAssertEqual(second.materialRevisions[0], oldRevision)
        XCTAssertEqual(second.materials[0].currentRevisionID, second.materialRevisions[1].id)
        XCTAssertEqual(second.materialRevisions[0].tags, ["magic"])
        XCTAssertEqual(second.branches, beforeBranches)
    }

    func testAppendCheckpointMovesOnlyTargetBranchAndRejectsStaleHead() throws {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let root = document.branches[0]
        let childID = NovelBranchID()
        let childSessionID = NovelSessionID()
        document.branches.append(NovelBranchRecord(
            id: childID,
            name: "Alternate",
            sessionID: childSessionID,
            createdAt: root.createdAt,
            updatedAt: root.updatedAt,
            forkOrigin: NovelForkOrigin(
                parentBranchID: root.id,
                checkpointID: root.headCheckpointID
            ),
            headCheckpointID: root.headCheckpointID,
            currentStateSnapshotID: root.currentStateSnapshotID,
            headRevision: 0,
            workingRevision: 0,
            syncStatus: .synchronized,
            lifecycle: .active,
            overrideRevisionIDs: [],
            workingChapterSelections: [],
            activeRunID: nil
        ))
        document.sessions.append(NovelSessionRecord(
            id: childSessionID,
            branchID: childID,
            revision: 0,
            messages: []
        ))
        let state = NovelStateSnapshotRecord(
            id: NovelStateSnapshotID(),
            eventIDs: [],
            summary: "Alternate state",
            branchOutline: "Alternate outline",
            unresolvedEntityNames: [],
            createdAt: root.createdAt.addingTimeInterval(1)
        )
        document.stateSnapshots.append(state)
        let checkpoint = NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .manualSync,
            createdOnBranchID: childID,
            parentCheckpointID: root.headCheckpointID,
            chapterSelections: [],
            stateSnapshotID: state.id,
            sessionCursor: .empty,
            branchOverrideRevisionIDs: [],
            sourceCandidateID: nil,
            baseHeadRevision: 0,
            operationID: document.appliedOperations[0].operationID,
            createdAt: state.createdAt
        )
        let rootBefore = document.branches[0]

        try NovelReducer.appendCheckpoint(
            checkpoint,
            to: &document,
            expectedHeadRevision: 0,
            now: state.createdAt
        )

        XCTAssertEqual(document.branches[0], rootBefore)
        let child = try XCTUnwrap(document.branches.first(where: { $0.id == childID }))
        XCTAssertEqual(child.headCheckpointID, checkpoint.id)
        XCTAssertEqual(child.currentStateSnapshotID, state.id)
        XCTAssertEqual(child.headRevision, 1)
        XCTAssertEqual(child.workingRevision, 1)
        let staleCheckpoint = NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .manualSync,
            createdOnBranchID: childID,
            parentCheckpointID: checkpoint.id,
            chapterSelections: [],
            stateSnapshotID: state.id,
            sessionCursor: .empty,
            branchOverrideRevisionIDs: [],
            sourceCandidateID: nil,
            baseHeadRevision: 1,
            operationID: document.appliedOperations[0].operationID,
            createdAt: state.createdAt
        )
        XCTAssertThrowsError(try NovelReducer.appendCheckpoint(
            staleCheckpoint,
            to: &document,
            expectedHeadRevision: 0,
            now: state.createdAt
        )) { error in
            XCTAssertEqual(
                error as? NovelError,
                .staleBranchHeadRevision(expected: 0, actual: 1)
            )
        }
    }

    func testSameOperationAndPayloadReplaysBeforeRevisionGuards() throws {
        let document = try NovelTestFixtures.document()
        let operationID = NovelOperationID()
        let action = NovelTestFixtures.materialAction(
            document: document,
            operationID: operationID
        )
        let first = try NovelReducer.apply(action, to: document)
        let replay = try NovelReducer.apply(action, to: first.document)

        XCTAssertEqual(replay.outcome, first.outcome)
        XCTAssertEqual(replay.document, first.document)
        XCTAssertEqual(replay.document.appliedOperations.count, 2)
    }

    func testSameOperationWithDifferentPayloadConflictsBeforeStaleGuard() throws {
        let document = try NovelTestFixtures.document()
        let operationID = NovelOperationID()
        let firstAction = NovelTestFixtures.materialAction(
            document: document,
            operationID: operationID
        )
        let first = try NovelReducer.apply(firstAction, to: document)
        let conflicting = NovelTestFixtures.materialAction(
            document: document,
            operationID: operationID
        )

        XCTAssertThrowsError(try NovelReducer.apply(conflicting, to: first.document)) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(operationID))
        }
    }

    func testSameOperationAndKindWithChangedSemanticPayloadConflicts() throws {
        let document = try NovelTestFixtures.document()
        let operationID = NovelOperationID()
        let firstAction = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                projectRevision: document.project.revision
            ),
            projectID: document.project.id,
            name: "First Name"
        ))
        let first = try NovelReducer.apply(firstAction, to: document)
        let changedPayload = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                projectRevision: document.project.revision
            ),
            projectID: document.project.id,
            name: "Different Name"
        ))

        XCTAssertThrowsError(try NovelReducer.apply(changedPayload, to: first.document)) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(operationID))
        }
    }

    func testStaleConfigRevisionDoesNotMutateInput() throws {
        let document = try NovelTestFixtures.document()
        let staleAction = NovelAction.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelTestFixtures.context(
                configRevision: document.project.configRevision + 1
            ),
            projectID: document.project.id,
            materialID: NovelMaterialID(),
            revisionID: NovelMaterialRevisionID(),
            kind: .character,
            title: "Hero",
            content: "Patient and stubborn.",
            tags: [],
            injectionMode: .smart
        ))

        XCTAssertThrowsError(try NovelReducer.apply(staleAction, to: document)) { error in
            XCTAssertEqual(
                error as? NovelError,
                .staleConfigRevision(
                    expected: document.project.configRevision + 1,
                    actual: document.project.configRevision
                )
            )
        }
        XCTAssertEqual(document.materials, [])
        XCTAssertEqual(document.appliedOperations.count, 1)
    }

    func testSetMainBranchRejectsDeletedBranch() throws {
        let source = try NovelTestFixtures.documentWithForkableCheckpoint()
        let deletedID = NovelBranchID()
        let deletedSessionID = NovelSessionID()
        let root = source.branches[0]
        let forked = try NovelReducer.apply(.forkBranch(NovelForkBranchCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: source.project.revision,
                expectedConfigRevision: source.project.configRevision,
                expectedBranchHeadRevision: root.headRevision
            ),
            projectID: source.project.id,
            sourceBranchID: root.id,
            checkpointID: root.headCheckpointID,
            branchID: deletedID,
            sessionID: deletedSessionID,
            name: "Deleted"
        )), to: source).document
        let forkedBranch = try XCTUnwrap(forked.branches.first(where: { $0.id == deletedID }))
        let document = try NovelReducer.apply(.deleteBranch(NovelDeleteBranchCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: forked.project.revision,
                expectedConfigRevision: forked.project.configRevision,
                expectedBranchHeadRevision: forkedBranch.headRevision
            ),
            projectID: forked.project.id,
            branchID: deletedID
        )), to: forked).document
        try NovelDocumentValidator.validate(document)

        let action = NovelAction.setMainBranch(NovelSetMainBranchCommand(
            context: NovelTestFixtures.context(
                projectRevision: document.project.revision
            ),
            projectID: document.project.id,
            branchID: deletedID
        ))
        XCTAssertThrowsError(try NovelReducer.apply(action, to: document)) { error in
            XCTAssertEqual(error as? NovelError, .branchNotFound(deletedID))
        }
    }
}
