import Foundation
import XCTest
@testable import iosApp

final class NovelProjectLifecycleOperationTests: XCTestCase {
    func testImportReplaysInProcessAndAcrossRepositoryRestartAndRejectsChangedPayload() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let document = try NovelTestFixtures.document()
        let artifact = try NovelProjectPackageCodec.encode(document)
        let operationID = NovelOperationID()
        let command = importCommand(
            document: document,
            artifact: artifact,
            operationID: operationID,
            policy: .reject
        )
        let expected = NovelOutcome.projectImported(
            sourceProjectID: document.project.id,
            projectID: document.project.id,
            disposition: .created,
            interruptedRunCount: 0,
            revision: document.project.revision
        )
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let creation = DefaultNovelCreation(repository: repository)

        let first = try await creation.perform(.importProject(command))
        let inProcessReplay = try await creation.perform(.importProject(command))
        let newCreationReplay = try await DefaultNovelCreation(repository: repository)
            .perform(.importProject(command))
        let restartedRepository = NovelFileProjectRepository(rootDirectory: root)
        let restartedReplay = try await DefaultNovelCreation(repository: restartedRepository)
            .perform(.importProject(command))

        XCTAssertEqual(first, expected)
        XCTAssertEqual(inProcessReplay, expected)
        XCTAssertEqual(newCreationReplay, expected)
        XCTAssertEqual(restartedReplay, expected)
        let storedImport = try await restartedRepository.lifecycleOperation(
            projectID: document.project.id,
            operationID: operationID
        )
        let completed = try XCTUnwrap(storedImport)
        XCTAssertEqual(completed.state, .completed)
        XCTAssertEqual(completed.outcome, expected)

        let changedDocument = try renamed(document, name: "Changed Package")
        let changedArtifact = try NovelProjectPackageCodec.encode(changedDocument)
        let changedCommand = importCommand(
            document: document,
            artifact: changedArtifact,
            operationID: operationID,
            policy: .reject
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await DefaultNovelCreation(repository: restartedRepository)
                .perform(.importProject(changedCommand))
        ) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(operationID))
        }
    }

    func testDeleteReplaysInProcessAndAcrossRepositoryRestart() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let document = try NovelTestFixtures.document()
        let operationID = NovelOperationID()
        let command = deleteCommand(document: document, operationID: operationID)
        let expected = NovelOutcome.projectDeleted(projectID: document.project.id)
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(document)
        let creation = DefaultNovelCreation(repository: repository)

        let first = try await creation.perform(.deleteProject(command))
        let inProcessReplay = try await creation.perform(.deleteProject(command))
        let newCreationReplay = try await DefaultNovelCreation(repository: repository)
            .perform(.deleteProject(command))
        let restartedRepository = NovelFileProjectRepository(rootDirectory: root)
        let restartedReplay = try await DefaultNovelCreation(repository: restartedRepository)
            .perform(.deleteProject(command))

        XCTAssertEqual(first, expected)
        XCTAssertEqual(inProcessReplay, expected)
        XCTAssertEqual(newCreationReplay, expected)
        XCTAssertEqual(restartedReplay, expected)
        let storedDelete = try await restartedRepository.lifecycleOperation(
            projectID: document.project.id,
            operationID: operationID
        )
        let completed = try XCTUnwrap(storedDelete)
        XCTAssertEqual(completed.state, .completed)
        await NovelXCTAssertThrowsErrorAsync(
            try await restartedRepository.loadProject(id: document.project.id)
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectNotFound(document.project.id))
        }
    }

    func testExplicitPreviousRestoreBecomesWritableAndReplaysAcrossRestart() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let previous = try NovelTestFixtures.document()
        _ = try await repository.createProject(previous)
        let newer = try renamed(previous, name: "Newer But Corrupt")
        _ = try await repository.commitProject(
            newer,
            expectedRevision: previous.project.revision
        )
        let primaryURL = root.appendingPathComponent("projects", isDirectory: true)
            .appendingPathComponent("\(previous.project.id.description).json")
        let previousURL = root.appendingPathComponent("projects", isDirectory: true)
            .appendingPathComponent("\(previous.project.id.description).previous.json")
        try Data("broken primary".utf8).write(to: primaryURL, options: [.atomic])

        let creation = DefaultNovelCreation(repository: repository)
        guard case .project(let degraded) = try await creation.snapshot(
            .project(previous.project.id)
        ) else {
            return XCTFail("Expected a project snapshot.")
        }
        guard case .degradedPrevious = degraded.access else {
            return XCTFail("Expected the previous copy to be read-only before confirmation.")
        }

        let operationID = NovelOperationID()
        let command = NovelRestorePreviousProjectCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                projectRevision: previous.project.revision
            ),
            projectID: previous.project.id
        )
        let expected = NovelOutcome.previousProjectRestored(
            projectID: previous.project.id,
            revision: previous.project.revision
        )
        let restored = try await creation.perform(.restorePreviousProject(command))
        let inProcessReplay = try await creation.perform(.restorePreviousProject(command))
        XCTAssertEqual(restored, expected)
        XCTAssertEqual(inProcessReplay, expected)
        XCTAssertTrue(FileManager.default.fileExists(atPath: previousURL.path))

        let restartedRepository = NovelFileProjectRepository(rootDirectory: root)
        let restarted = DefaultNovelCreation(repository: restartedRepository)
        let restartedReplay = try await restarted.perform(.restorePreviousProject(command))
        XCTAssertEqual(restartedReplay, expected)
        let loaded = try await restartedRepository.loadProject(id: previous.project.id)
        XCTAssertEqual(loaded.access, .readWrite)
        XCTAssertEqual(loaded.document, previous)
        let storedReceipt = try await restartedRepository.lifecycleOperation(
            projectID: previous.project.id,
            operationID: operationID
        )
        let receipt = try XCTUnwrap(storedReceipt)
        XCTAssertEqual(receipt.state, .completed)
        XCTAssertEqual(receipt.outcome, expected)

        let renamedOutcome = try await restarted.perform(NovelTestFixtures.renameAction(
            document: previous,
            name: "Writable Again"
        ))
        XCTAssertEqual(
            renamedOutcome,
            .projectRenamed(
                projectID: previous.project.id,
                revision: previous.project.revision + 1
            )
        )
    }

    func testLifecycleAndDocumentOperationIDsCannotCrossIntoEachOther() async throws {
        let lifecycleRepository = InMemoryNovelProjectRepository()
        let imported = try NovelTestFixtures.document()
        let artifact = try NovelProjectPackageCodec.encode(imported)
        let lifecycleOperationID = NovelOperationID()
        let importAction = NovelAction.importProject(importCommand(
            document: imported,
            artifact: artifact,
            operationID: lifecycleOperationID,
            policy: .reject
        ))
        let lifecycleCreation = DefaultNovelCreation(repository: lifecycleRepository)
        _ = try await lifecycleCreation.perform(importAction)

        let collidingRename = NovelTestFixtures.renameAction(
            document: imported,
            operationID: lifecycleOperationID,
            name: "Must Conflict"
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await lifecycleCreation.perform(collidingRename)
        ) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(lifecycleOperationID))
        }
        let collidingRun = discussionRequest(
            document: imported,
            operationID: lifecycleOperationID
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await lifecycleCreation.start(collidingRun)
        ) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(lifecycleOperationID))
        }

        let documentRepository = InMemoryNovelProjectRepository()
        let current = try NovelTestFixtures.document()
        _ = try await documentRepository.createProject(current)
        let documentCreation = DefaultNovelCreation(repository: documentRepository)
        let documentOperationID = try XCTUnwrap(current.appliedOperations.first?.operationID)
        let collidingDelete = deleteCommand(
            document: current,
            operationID: documentOperationID
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await documentCreation.perform(.deleteProject(collidingDelete))
        ) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(documentOperationID))
        }

        let replacement = try NovelTestFixtures.document(projectID: current.project.id)
        let replacementArtifact = try NovelProjectPackageCodec.encode(replacement)
        let collidingImport = importCommand(
            document: current,
            artifact: replacementArtifact,
            operationID: documentOperationID,
            policy: .replace(expectedRevision: current.project.revision)
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await documentCreation.perform(.importProject(collidingImport))
        ) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(documentOperationID))
        }
        let unchanged = try await documentRepository.loadProject(id: current.project.id)
        XCTAssertEqual(unchanged.document, current)
    }

    func testAmbiguousPendingReceiptBlocksMutationGenerationAndExport() async throws {
        let repository = InMemoryNovelProjectRepository()
        let baseDocument = try NovelTestFixtures.document()
        _ = try await repository.createProject(baseDocument)
        let paused = try await startPausedDiscussion(
            document: baseDocument,
            repository: repository
        )
        let document = paused.document
        let pendingOperationID = NovelOperationID()
        let pending = NovelProjectLifecycleOperationRecord(
            projectID: document.project.id,
            operationID: pendingOperationID,
            kind: .importProject,
            payloadSHA256: NovelTestFixtures.hashA,
            intent: .importReplace(expectedRevision: document.project.revision),
            sourceProjectSHA256: NovelTestFixtures.hashB,
            targetProjectSHA256: NovelTestFixtures.hashC,
            outcome: .projectImported(
                sourceProjectID: document.project.id,
                projectID: document.project.id,
                disposition: .replaced,
                interruptedRunCount: 0,
                revision: document.project.revision
            )
        )
        try await repository.writeLifecycleOperation(pending)
        let creation = DefaultNovelCreation(repository: repository)
        let busy = NovelError.projectBusy(document.project.id)

        await NovelXCTAssertThrowsErrorAsync(
            try await creation.perform(NovelTestFixtures.renameAction(document: document))
        ) { error in
            XCTAssertEqual(error as? NovelError, busy)
        }
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.start(discussionRequest(
                document: document,
                operationID: NovelOperationID()
            ))
        ) { error in
            XCTAssertEqual(error as? NovelError, busy)
        }
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.snapshot(.projectPackage(document.project.id))
        ) { error in
            XCTAssertEqual(error as? NovelError, busy)
        }
        let stored = try await repository.lifecycleOperation(
            projectID: document.project.id,
            operationID: pendingOperationID
        )
        XCTAssertEqual(stored, pending)
        let unchanged = try await repository.loadProject(id: document.project.id)
        XCTAssertEqual(unchanged.document, document)

        await paused.adapter.resume(runID: paused.run.id)
        for await _ in paused.run.events {}
    }

    func testRecoverySafelyAbandonsPendingCreateReplaceAndDeleteWhenSourceIsUnchanged() async throws {
        try await assertPendingCreateAbsentIsAbandoned()
        try await assertPendingReplaceSourceIsAbandoned()
        try await assertPendingDeleteSourceIsAbandoned()
    }

    func testRecoveryPromotesImportedTargetAndDeletedTargetAfterCompletionWriteFailure() async throws {
        let importBase = InMemoryNovelProjectRepository()
        let importRepository = LifecycleCompletionWriteFailingRepository(base: importBase)
        let document = try NovelTestFixtures.document()
        let artifact = try NovelProjectPackageCodec.encode(document)
        let importOperationID = NovelOperationID()
        let importRequest = importCommand(
            document: document,
            artifact: artifact,
            operationID: importOperationID,
            policy: .reject
        )
        await importRepository.failNextCompletedLifecycleWrite()
        await NovelXCTAssertThrowsErrorAsync(
            try await DefaultNovelCreation(repository: importRepository)
                .perform(.importProject(importRequest))
        ) { error in
            XCTAssertEqual(error as? NovelError, .storageIndeterminate(document.project.id))
        }
        let importedTarget = try await importRepository.loadProject(id: document.project.id)
        XCTAssertEqual(importedTarget.document, document)
        let pendingImport = try await importRepository.lifecycleOperation(
            projectID: document.project.id,
            operationID: importOperationID
        )
        XCTAssertEqual(pendingImport?.state, .pending)

        let recoveredImport = try await DefaultNovelCreation(repository: importRepository)
            .perform(.importProject(importRequest))
        XCTAssertEqual(
            recoveredImport,
            .projectImported(
                sourceProjectID: document.project.id,
                projectID: document.project.id,
                disposition: .created,
                interruptedRunCount: 0,
                revision: document.project.revision
            )
        )
        let completedImport = try await importRepository.lifecycleOperation(
            projectID: document.project.id,
            operationID: importOperationID
        )
        XCTAssertEqual(completedImport?.state, .completed)

        let deleteBase = InMemoryNovelProjectRepository()
        let deleteRepository = LifecycleCompletionWriteFailingRepository(base: deleteBase)
        let deletedDocument = try NovelTestFixtures.document()
        _ = try await deleteRepository.createProject(deletedDocument)
        let deleteOperationID = NovelOperationID()
        let delete = deleteCommand(
            document: deletedDocument,
            operationID: deleteOperationID
        )
        await deleteRepository.failNextCompletedLifecycleWrite()
        await NovelXCTAssertThrowsErrorAsync(
            try await DefaultNovelCreation(repository: deleteRepository)
                .perform(.deleteProject(delete))
        ) { error in
            XCTAssertEqual(error as? NovelError, .storageIndeterminate(deletedDocument.project.id))
        }
        await NovelXCTAssertThrowsErrorAsync(
            try await deleteRepository.loadProject(id: deletedDocument.project.id)
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectNotFound(deletedDocument.project.id))
        }
        let pendingDelete = try await deleteRepository.lifecycleOperation(
            projectID: deletedDocument.project.id,
            operationID: deleteOperationID
        )
        XCTAssertEqual(pendingDelete?.state, .pending)

        let recoveredDelete = try await DefaultNovelCreation(repository: deleteRepository)
            .perform(.deleteProject(delete))
        XCTAssertEqual(
            recoveredDelete,
            .projectDeleted(projectID: deletedDocument.project.id)
        )
        let completedDelete = try await deleteRepository.lifecycleOperation(
            projectID: deletedDocument.project.id,
            operationID: deleteOperationID
        )
        XCTAssertEqual(completedDelete?.state, .completed)
    }

    func testRecoveryNeverABAOverwritesStateWrittenAfterImportedTarget() async throws {
        let base = InMemoryNovelProjectRepository()
        let repository = LifecycleCompletionWriteFailingRepository(base: base)
        let imported = try NovelTestFixtures.document()
        let artifact = try NovelProjectPackageCodec.encode(imported)
        let operationID = NovelOperationID()
        let command = importCommand(
            document: imported,
            artifact: artifact,
            operationID: operationID,
            policy: .reject
        )
        await repository.failNextCompletedLifecycleWrite()
        await NovelXCTAssertThrowsErrorAsync(
            try await DefaultNovelCreation(repository: repository)
                .perform(.importProject(command))
        )

        let later = try renamed(imported, name: "Later Authoritative State")
        _ = try await repository.commitProject(
            later,
            expectedRevision: imported.project.revision,
            authorization: nil
        )
        let recovering = DefaultNovelCreation(repository: repository)
        await NovelXCTAssertThrowsErrorAsync(
            try await recovering.snapshot(.projectPackage(imported.project.id))
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectBusy(imported.project.id))
        }

        let unchanged = try await repository.loadProject(id: imported.project.id)
        XCTAssertEqual(unchanged.document, later)
        let stillPending = try await repository.lifecycleOperation(
            projectID: imported.project.id,
            operationID: operationID
        )
        XCTAssertEqual(stillPending?.state, .pending)
    }

    func testPendingWriteResponseLossAndLookupFailureGatesActorUntilSameOperationRecovers() async throws {
        let base = InMemoryNovelProjectRepository()
        let repository = LifecycleCompletionWriteFailingRepository(base: base)
        let document = try NovelTestFixtures.document()
        let artifact = try NovelProjectPackageCodec.encode(document)
        let operationID = NovelOperationID()
        let command = importCommand(
            document: document,
            artifact: artifact,
            operationID: operationID,
            policy: .reject
        )
        let creation = DefaultNovelCreation(repository: repository)
        await repository.failNextPendingLifecycleWriteAfterInstallAndLookup()

        await NovelXCTAssertThrowsErrorAsync(
            try await creation.perform(.importProject(command))
        ) { error in
            XCTAssertEqual(error as? NovelError, .storageIndeterminate(document.project.id))
        }
        let durablePending = try await base.lifecycleOperation(
            projectID: document.project.id,
            operationID: operationID
        )
        XCTAssertEqual(durablePending?.state, .pending)

        let busy = NovelError.projectBusy(document.project.id)
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.perform(NovelTestFixtures.renameAction(document: document))
        ) { error in
            XCTAssertEqual(error as? NovelError, busy)
        }
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.start(discussionRequest(
                document: document,
                operationID: NovelOperationID()
            ))
        ) { error in
            XCTAssertEqual(error as? NovelError, busy)
        }
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.snapshot(.projectPackage(document.project.id))
        ) { error in
            XCTAssertEqual(error as? NovelError, busy)
        }

        let recovered = try await creation.perform(.importProject(command))
        XCTAssertEqual(
            recovered,
            .projectImported(
                sourceProjectID: document.project.id,
                projectID: document.project.id,
                disposition: .created,
                interruptedRunCount: 0,
                revision: document.project.revision
            )
        )
        guard case .package(let exported) = try await creation.snapshot(
            .projectPackage(document.project.id)
        ) else {
            return XCTFail("Expected export after pending operation recovery.")
        }
        XCTAssertEqual(exported.data, artifact.data)
    }

    func testCorruptPendingAndCompletedFileReceiptsFailClosedWithoutDeletingEvidence() async throws {
        try await assertCorruptFileReceiptFailsClosed(state: .pending)
        try await assertCorruptFileReceiptFailsClosed(state: .completed)
    }

    func testPendingReplaceAndDeleteRejectSameRevisionWithDifferentSourceHash() async throws {
        let replaceRepository = InMemoryNovelProjectRepository()
        let replaceBase = try NovelTestFixtures.document()
        let replaceCurrent = try renamed(replaceBase, name: "Current Replace Source")
        let recordedReplaceSource = try renamed(replaceBase, name: "Recorded Replace Source")
        _ = try await replaceRepository.createProject(replaceCurrent)
        let replacement = try NovelTestFixtures.document(projectID: replaceCurrent.project.id)
        let replacementArtifact = try NovelProjectPackageCodec.encode(replacement)
        let replaceOperationID = NovelOperationID()
        let replaceCommand = importCommand(
            document: replaceCurrent,
            artifact: replacementArtifact,
            operationID: replaceOperationID,
            policy: .replace(expectedRevision: replaceCurrent.project.revision)
        )
        let pendingReplace = NovelProjectLifecycleOperationRecord(
            projectID: replaceCurrent.project.id,
            operationID: replaceOperationID,
            kind: .importProject,
            payloadSHA256: try NovelAction.importProject(replaceCommand).canonicalPayloadSHA256(),
            intent: .importReplace(expectedRevision: replaceCurrent.project.revision),
            sourceProjectSHA256: try NovelProjectPackageCodec
                .encode(recordedReplaceSource).projectSHA256,
            targetProjectSHA256: replacementArtifact.projectSHA256,
            outcome: .projectImported(
                sourceProjectID: replacement.project.id,
                projectID: replaceCurrent.project.id,
                disposition: .replaced,
                interruptedRunCount: 0,
                revision: replacement.project.revision
            )
        )
        try await replaceRepository.writeLifecycleOperation(pendingReplace)

        await NovelXCTAssertThrowsErrorAsync(
            try await DefaultNovelCreation(repository: replaceRepository)
                .perform(.importProject(replaceCommand))
        ) { error in
            XCTAssertEqual(
                error as? NovelError,
                .storageIndeterminate(replaceCurrent.project.id)
            )
        }
        let replaceUnchanged = try await replaceRepository.loadProject(
            id: replaceCurrent.project.id
        )
        XCTAssertEqual(replaceUnchanged.document, replaceCurrent)
        let replaceReceipt = try await replaceRepository.lifecycleOperation(
            projectID: replaceCurrent.project.id,
            operationID: replaceOperationID
        )
        XCTAssertEqual(replaceReceipt?.state, .pending)

        let deleteRepository = InMemoryNovelProjectRepository()
        let deleteBase = try NovelTestFixtures.document()
        let deleteCurrent = try renamed(deleteBase, name: "Current Delete Source")
        let recordedDeleteSource = try renamed(deleteBase, name: "Recorded Delete Source")
        _ = try await deleteRepository.createProject(deleteCurrent)
        let deleteOperationID = NovelOperationID()
        let deleteRequest = deleteCommand(
            document: deleteCurrent,
            operationID: deleteOperationID
        )
        let pendingDelete = NovelProjectLifecycleOperationRecord(
            projectID: deleteCurrent.project.id,
            operationID: deleteOperationID,
            kind: .deleteProject,
            payloadSHA256: try NovelAction.deleteProject(deleteRequest).canonicalPayloadSHA256(),
            intent: .delete(expectedRevision: deleteCurrent.project.revision),
            sourceProjectSHA256: try NovelProjectPackageCodec
                .encode(recordedDeleteSource).projectSHA256,
            targetProjectSHA256: nil,
            outcome: .projectDeleted(projectID: deleteCurrent.project.id)
        )
        try await deleteRepository.writeLifecycleOperation(pendingDelete)

        await NovelXCTAssertThrowsErrorAsync(
            try await DefaultNovelCreation(repository: deleteRepository)
                .perform(.deleteProject(deleteRequest))
        ) { error in
            XCTAssertEqual(
                error as? NovelError,
                .storageIndeterminate(deleteCurrent.project.id)
            )
        }
        let deleteUnchanged = try await deleteRepository.loadProject(id: deleteCurrent.project.id)
        XCTAssertEqual(deleteUnchanged.document, deleteCurrent)
        let deleteReceipt = try await deleteRepository.lifecycleOperation(
            projectID: deleteCurrent.project.id,
            operationID: deleteOperationID
        )
        XCTAssertEqual(deleteReceipt?.state, .pending)
    }

    func testImportedDocumentCannotReuseAnotherCompletedLifecycleOperationID() async throws {
        let repository = InMemoryNovelProjectRepository()
        let destinationID = NovelProjectID()
        let completedOperationID = NovelOperationID()
        let document = try NovelTestFixtures.document(
            projectID: destinationID,
            operationID: completedOperationID
        )
        let artifact = try NovelProjectPackageCodec.encode(document)
        let completedDelete = NovelProjectLifecycleOperationRecord(
            projectID: destinationID,
            operationID: completedOperationID,
            kind: .deleteProject,
            payloadSHA256: NovelTestFixtures.hashA,
            intent: .delete(expectedRevision: document.project.revision),
            sourceProjectSHA256: nil,
            targetProjectSHA256: nil,
            outcome: .projectDeleted(projectID: destinationID)
        ).completed()
        try await repository.writeLifecycleOperation(completedDelete)
        let importOperationID = NovelOperationID()
        let command = importCommand(
            document: document,
            artifact: artifact,
            operationID: importOperationID,
            policy: .reject
        )

        await NovelXCTAssertThrowsErrorAsync(
            try await DefaultNovelCreation(repository: repository)
                .perform(.importProject(command))
        ) { error in
            XCTAssertEqual(
                error as? NovelError,
                .idempotencyConflict(completedOperationID)
            )
        }
        await NovelXCTAssertThrowsErrorAsync(
            try await repository.loadProject(id: destinationID)
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectNotFound(destinationID))
        }
        let preserved = try await repository.lifecycleOperation(
            projectID: destinationID,
            operationID: completedOperationID
        )
        XCTAssertEqual(preserved, completedDelete)
    }

    private func assertCorruptFileReceiptFailsClosed(
        state: NovelProjectLifecycleOperationState
    ) async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let baseDocument = try NovelTestFixtures.document()
        _ = try await repository.createProject(baseDocument)
        let paused = try await startPausedDiscussion(
            document: baseDocument,
            repository: repository
        )
        let document = paused.document
        let operationID = NovelOperationID()
        let pending = NovelProjectLifecycleOperationRecord(
            projectID: document.project.id,
            operationID: operationID,
            kind: .importProject,
            payloadSHA256: NovelTestFixtures.hashA,
            intent: .importReplace(expectedRevision: document.project.revision),
            sourceProjectSHA256: try NovelProjectPackageCodec.encode(document).projectSHA256,
            targetProjectSHA256: NovelTestFixtures.hashB,
            outcome: .projectImported(
                sourceProjectID: document.project.id,
                projectID: document.project.id,
                disposition: .replaced,
                interruptedRunCount: 0,
                revision: document.project.revision
            )
        )
        let record = state == .pending ? pending : pending.completed()
        try await repository.writeLifecycleOperation(record)
        let receiptURL = lifecycleReceiptURL(
            root: root,
            projectID: document.project.id,
            operationID: operationID
        )
        let corruptBytes = Data("corrupt-\(state.rawValue)-receipt".utf8)
        try corruptBytes.write(to: receiptURL, options: [.atomic])
        let primaryURL = root.appendingPathComponent("projects", isDirectory: true)
            .appendingPathComponent("\(document.project.id.description).json")
        let primaryBytes = try Data(contentsOf: primaryURL)

        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let creation = DefaultNovelCreation(repository: restarted)
        let blocked = NovelError.storageIndeterminate(document.project.id)
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.perform(NovelTestFixtures.renameAction(document: document))
        ) { error in
            XCTAssertEqual(error as? NovelError, blocked)
        }
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.start(discussionRequest(
                document: document,
                operationID: NovelOperationID()
            ))
        ) { error in
            XCTAssertEqual(error as? NovelError, blocked)
        }
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.snapshot(.projectPackage(document.project.id))
        ) { error in
            XCTAssertEqual(error as? NovelError, blocked)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: receiptURL.path))
        XCTAssertEqual(try Data(contentsOf: receiptURL), corruptBytes)
        XCTAssertEqual(try Data(contentsOf: primaryURL), primaryBytes)
        let unchanged = try await restarted.loadProject(id: document.project.id)
        XCTAssertEqual(unchanged.document, document)
        XCTAssertEqual(unchanged.document.activeRuns.first?.status, .running)

        await paused.adapter.resume(runID: paused.run.id)
        for await _ in paused.run.events {}
    }

    private func assertPendingCreateAbsentIsAbandoned() async throws {
        let repository = InMemoryNovelProjectRepository()
        let document = try NovelTestFixtures.document()
        let artifact = try NovelProjectPackageCodec.encode(document)
        let operationID = NovelOperationID()
        let command = importCommand(
            document: document,
            artifact: artifact,
            operationID: operationID,
            policy: .reject
        )
        let record = NovelProjectLifecycleOperationRecord(
            projectID: document.project.id,
            operationID: operationID,
            kind: .importProject,
            payloadSHA256: try NovelAction.importProject(command).canonicalPayloadSHA256(),
            intent: .importCreate,
            sourceProjectSHA256: nil,
            targetProjectSHA256: artifact.projectSHA256,
            outcome: .projectImported(
                sourceProjectID: document.project.id,
                projectID: document.project.id,
                disposition: .created,
                interruptedRunCount: 0,
                revision: document.project.revision
            )
        )
        try await repository.writeLifecycleOperation(record)
        let creation = DefaultNovelCreation(repository: repository)
        _ = try await creation.snapshot(.projects)
        let abandoned = try await repository.lifecycleOperation(
            projectID: document.project.id,
            operationID: operationID
        )
        XCTAssertNil(abandoned)

        let outcome = try await creation.perform(.importProject(command))
        XCTAssertEqual(
            outcome,
            .projectImported(
                sourceProjectID: document.project.id,
                projectID: document.project.id,
                disposition: .created,
                interruptedRunCount: 0,
                revision: document.project.revision
            )
        )
    }

    private func assertPendingReplaceSourceIsAbandoned() async throws {
        let repository = InMemoryNovelProjectRepository()
        let source = try NovelTestFixtures.document()
        _ = try await repository.createProject(source)
        let target = try NovelTestFixtures.document(projectID: source.project.id)
        let artifact = try NovelProjectPackageCodec.encode(target)
        let operationID = NovelOperationID()
        let command = importCommand(
            document: source,
            artifact: artifact,
            operationID: operationID,
            policy: .replace(expectedRevision: source.project.revision)
        )
        let record = NovelProjectLifecycleOperationRecord(
            projectID: source.project.id,
            operationID: operationID,
            kind: .importProject,
            payloadSHA256: try NovelAction.importProject(command).canonicalPayloadSHA256(),
            intent: .importReplace(expectedRevision: source.project.revision),
            sourceProjectSHA256: try NovelProjectPackageCodec.encode(source).projectSHA256,
            targetProjectSHA256: artifact.projectSHA256,
            outcome: .projectImported(
                sourceProjectID: target.project.id,
                projectID: source.project.id,
                disposition: .replaced,
                interruptedRunCount: 0,
                revision: target.project.revision
            )
        )
        try await repository.writeLifecycleOperation(record)
        let creation = DefaultNovelCreation(repository: repository)
        _ = try await creation.snapshot(.project(source.project.id))
        let abandoned = try await repository.lifecycleOperation(
            projectID: source.project.id,
            operationID: operationID
        )
        XCTAssertNil(abandoned)

        let rename = try await creation.perform(NovelTestFixtures.renameAction(
            document: source,
            name: "Unlocked Replace Source"
        ))
        XCTAssertEqual(
            rename,
            .projectRenamed(projectID: source.project.id, revision: source.project.revision + 1)
        )
    }

    private func assertPendingDeleteSourceIsAbandoned() async throws {
        let repository = InMemoryNovelProjectRepository()
        let source = try NovelTestFixtures.document()
        _ = try await repository.createProject(source)
        let operationID = NovelOperationID()
        let command = deleteCommand(document: source, operationID: operationID)
        let record = NovelProjectLifecycleOperationRecord(
            projectID: source.project.id,
            operationID: operationID,
            kind: .deleteProject,
            payloadSHA256: try NovelAction.deleteProject(command).canonicalPayloadSHA256(),
            intent: .delete(expectedRevision: source.project.revision),
            sourceProjectSHA256: try NovelProjectPackageCodec.encode(source).projectSHA256,
            targetProjectSHA256: nil,
            outcome: .projectDeleted(projectID: source.project.id)
        )
        try await repository.writeLifecycleOperation(record)
        let creation = DefaultNovelCreation(repository: repository)
        _ = try await creation.snapshot(.project(source.project.id))
        let abandoned = try await repository.lifecycleOperation(
            projectID: source.project.id,
            operationID: operationID
        )
        XCTAssertNil(abandoned)

        let rename = try await creation.perform(NovelTestFixtures.renameAction(
            document: source,
            name: "Unlocked Delete Source"
        ))
        XCTAssertEqual(
            rename,
            .projectRenamed(projectID: source.project.id, revision: source.project.revision + 1)
        )
    }

    private func importCommand(
        document: NovelProjectDocumentV1,
        artifact: NovelProjectPackageArtifact,
        operationID: NovelOperationID,
        policy: NovelProjectImportPolicy
    ) -> NovelImportProjectCommand {
        let expectedRevision: Int64?
        if case .replace(let revision) = policy {
            expectedRevision = revision
        } else {
            expectedRevision = nil
        }
        return NovelImportProjectCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                projectRevision: expectedRevision
            ),
            projectID: document.project.id,
            packageData: artifact.data,
            policy: policy
        )
    }

    private func deleteCommand(
        document: NovelProjectDocumentV1,
        operationID: NovelOperationID
    ) -> NovelDeleteProjectCommand {
        NovelDeleteProjectCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                projectRevision: document.project.revision
            ),
            projectID: document.project.id
        )
    }

    private func renamed(
        _ document: NovelProjectDocumentV1,
        name: String
    ) throws -> NovelProjectDocumentV1 {
        try NovelReducer.apply(
            NovelTestFixtures.renameAction(document: document, name: name),
            to: document
        ).document
    }

    private func lifecycleReceiptURL(
        root: URL,
        projectID: NovelProjectID,
        operationID: NovelOperationID
    ) -> URL {
        root.appendingPathComponent("lifecycle", isDirectory: true)
            .appendingPathComponent("\(projectID.description)-\(operationID.description).json")
    }

    private func discussionRequest(
        document: NovelProjectDocumentV1,
        operationID: NovelOperationID
    ) -> NovelRunRequest {
        NovelRunRequest(
            id: NovelRunID(),
            operationID: operationID,
            projectID: document.project.id,
            branchID: document.branches[0].id,
            kind: .discussion,
            mode: .discussPlan,
            granularity: nil,
            userText: "Collision canary",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: nil,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            inputBudgetTokens: 16_000,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: document.branches[0].headRevision
        )
    }

    private struct PausedDiscussion {
        let creation: DefaultNovelCreation
        let adapter: ScriptedNovelModelAdapter
        let run: NovelRun
        let document: NovelProjectDocumentV1
    }

    private func startPausedDiscussion(
        document: NovelProjectDocumentV1,
        repository: any NovelProjectPersisting
    ) async throws -> PausedDiscussion {
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "lifecycle-test-provider",
                ownerProviderID: "lifecycle-test-provider",
                modelID: "lifecycle-test-model",
                wireModelID: "lifecycle-test-model",
                displayName: "Lifecycle Test Model",
                contextWindowTokens: 128_000
            ),
            scripts: [NovelModelScript(steps: [.pause, .delta("Cleanup"), .complete])]
        )
        let creation = DefaultNovelCreation(repository: repository, modelRunner: adapter)
        let run = try await creation.start(discussionRequest(
            document: document,
            operationID: NovelOperationID()
        ))
        let running = try await repository.loadProject(id: document.project.id).document
        return PausedDiscussion(
            creation: creation,
            adapter: adapter,
            run: run,
            document: running
        )
    }
}

private actor LifecycleCompletionWriteFailingRepository: NovelProjectPersisting {
    private let base: any NovelProjectPersisting
    private var completedLifecycleWriteFailures = 0
    private var pendingWriteResponseLosses = 0
    private var lifecycleReadFailures = 0

    init(base: any NovelProjectPersisting) {
        self.base = base
    }

    func failNextCompletedLifecycleWrite() {
        completedLifecycleWriteFailures += 1
    }

    func failNextPendingLifecycleWriteAfterInstallAndLookup() {
        pendingWriteResponseLosses += 1
    }

    func listProjects() async throws -> [NovelProjectSummary] {
        try await base.listProjects()
    }

    func loadProject(id: NovelProjectID) async throws -> NovelLoadedProject {
        try await base.loadProject(id: id)
    }

    func createProject(_ document: NovelProjectDocumentV1) async throws -> NovelLoadedProject {
        try await base.createProject(document)
    }

    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64,
        authorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelLoadedProject {
        try await base.commitProject(
            document,
            expectedRevision: expectedRevision,
            authorization: authorization
        )
    }

    func replaceProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64
    ) async throws -> NovelLoadedProject {
        try await base.replaceProject(document, expectedRevision: expectedRevision)
    }

    func deleteProject(id: NovelProjectID, expectedRevision: Int64) async throws {
        try await base.deleteProject(id: id, expectedRevision: expectedRevision)
    }

    func lifecycleOperation(
        projectID: NovelProjectID,
        operationID: NovelOperationID
    ) async throws -> NovelProjectLifecycleOperationRecord? {
        if lifecycleReadFailures > 0 {
            lifecycleReadFailures -= 1
            throw NovelError.repositoryFailure("Injected lifecycle lookup failure.")
        }
        return try await base.lifecycleOperation(
            projectID: projectID,
            operationID: operationID
        )
    }

    func lifecycleOperationIDs(projectID: NovelProjectID) async throws -> Set<NovelOperationID> {
        try await base.lifecycleOperationIDs(projectID: projectID)
    }

    func listPendingLifecycleOperations() async throws -> [NovelProjectLifecycleOperationRecord] {
        try await base.listPendingLifecycleOperations()
    }

    func blockedLifecycleProjectIDs() async throws -> Set<NovelProjectID> {
        try await base.blockedLifecycleProjectIDs()
    }

    func writeLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) async throws {
        if record.state == .pending, pendingWriteResponseLosses > 0 {
            pendingWriteResponseLosses -= 1
            try await base.writeLifecycleOperation(record)
            lifecycleReadFailures += 1
            throw NovelError.repositoryFailure("Injected pending lifecycle response loss.")
        }
        if record.state == .completed, completedLifecycleWriteFailures > 0 {
            completedLifecycleWriteFailures -= 1
            throw NovelError.repositoryFailure("Injected completed lifecycle write failure.")
        }
        try await base.writeLifecycleOperation(record)
    }

    func removeLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) async throws {
        try await base.removeLifecycleOperation(record)
    }

    func restorePreviousProject(
        id: NovelProjectID,
        expectedDocumentSHA256: String
    ) async throws -> NovelLoadedProject {
        try await base.restorePreviousProject(
            id: id,
            expectedDocumentSHA256: expectedDocumentSHA256
        )
    }

    func listRecoverySidecars() async throws -> [NovelRecoverySidecarV1] {
        try await base.listRecoverySidecars()
    }

    func writeRecoverySidecar(_ sidecar: NovelRecoverySidecarV1) async throws {
        try await base.writeRecoverySidecar(sidecar)
    }

    func removeRecoverySidecar(projectID: NovelProjectID, runID: NovelRunID) async throws {
        try await base.removeRecoverySidecar(projectID: projectID, runID: runID)
    }
}
