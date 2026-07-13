import Foundation
import XCTest
@testable import iosApp

enum NovelTestFixtures {
    static let hashA = String(repeating: "a", count: 64)
    static let hashB = String(repeating: "b", count: 64)
    static let hashC = String(repeating: "c", count: 64)

    static func createCommand(
        projectID: NovelProjectID = NovelProjectID(),
        branchID: NovelBranchID = NovelBranchID(),
        sessionID: NovelSessionID = NovelSessionID(),
        operationID: NovelOperationID = NovelOperationID(),
        name: String = "Test Novel"
    ) -> NovelCreateProjectCommand {
        NovelCreateProjectCommand(
            context: NovelMutationContext(
                operationID: operationID,
                expectedProjectRevision: nil,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: projectID,
            branchID: branchID,
            sessionID: sessionID,
            initialStateSnapshotID: NovelStateSnapshotID(),
            initialCheckpointID: NovelCheckpointID(),
            name: name,
            branchName: "Main",
            creationMode: .blank,
            quickStartSeed: nil
        )
    }

    static func document(
        projectID: NovelProjectID = NovelProjectID(),
        operationID: NovelOperationID = NovelOperationID(),
        now: Date = Date(timeIntervalSince1970: 1_700_000_000)
    ) throws -> NovelProjectDocumentV1 {
        try NovelReducer.createProject(
            createCommand(
                projectID: projectID,
                operationID: operationID
            ),
            now: now
        ).document
    }

    static func documentWithForkableCheckpoint() throws -> NovelProjectDocumentV1 {
        var document = try self.document()
        let root = document.branches[0]
        let checkpoint = NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .manualSync,
            createdOnBranchID: root.id,
            parentCheckpointID: root.headCheckpointID,
            chapterSelections: [],
            stateSnapshotID: root.currentStateSnapshotID,
            sessionCursor: .empty,
            branchOverrideRevisionIDs: [],
            sourceCandidateID: nil,
            baseHeadRevision: 0,
            operationID: document.appliedOperations[0].operationID,
            createdAt: document.project.updatedAt
        )
        document.checkpoints.append(checkpoint)
        document.branches[0].headCheckpointID = checkpoint.id
        document.branches[0].headRevision = 1
        document.branches[0].workingRevision = 0
        try NovelDocumentValidator.validate(document)
        return document
    }

    static func context(
        operationID: NovelOperationID = NovelOperationID(),
        projectRevision: Int64? = nil,
        configRevision: Int64? = nil,
        branchHeadRevision: Int64? = nil
    ) -> NovelMutationContext {
        NovelMutationContext(
            operationID: operationID,
            expectedProjectRevision: projectRevision,
            expectedConfigRevision: configRevision,
            expectedBranchHeadRevision: branchHeadRevision
        )
    }

    static func renameAction(
        document: NovelProjectDocumentV1,
        operationID: NovelOperationID = NovelOperationID(),
        name: String = "Renamed Novel"
    ) -> NovelAction {
        .renameProject(NovelRenameProjectCommand(
            context: context(
                operationID: operationID,
                projectRevision: document.project.revision
            ),
            projectID: document.project.id,
            name: name
        ))
    }

    static func materialAction(
        document: NovelProjectDocumentV1,
        materialID: NovelMaterialID = NovelMaterialID(),
        revisionID: NovelMaterialRevisionID = NovelMaterialRevisionID(),
        operationID: NovelOperationID = NovelOperationID(),
        title: String = "World Rule",
        content: String = "Magic has a cost."
    ) -> NovelAction {
        .reviseMaterial(NovelReviseMaterialCommand(
            context: context(
                operationID: operationID,
                configRevision: document.project.configRevision
            ),
            projectID: document.project.id,
            materialID: materialID,
            revisionID: revisionID,
            kind: .world,
            title: title,
            content: content,
            tags: ["magic", "Magic", ""],
            injectionMode: .always
        ))
    }

    static func temporaryDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("NovelCreationTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    static func factTransactionArtifacts(
        document: NovelProjectDocumentV1,
        pendingID: NovelPendingOperationID,
        retryCommand: NovelRetryPendingCommand? = nil,
        runID: NovelRunID = NovelRunID(),
        now: Date = Date(timeIntervalSince1970: 1_700_000_100)
    ) throws -> NovelFactTransactionReceiptArtifacts {
        guard let pending = document.pendingOperations.first(where: { $0.id == pendingID }) else {
            throw NovelError.invalidInput("The fact receipt fixture requires a pending operation.")
        }

        let kind: NovelFactReceiptKind
        let request: NovelInjectionPlanningRequest
        switch pending.kind {
        case .collection:
            let input = try NovelFactTransactionReducer.collectionExtractionInput(
                pendingID: pendingID,
                in: document
            )
            kind = .stateDelta
            request = NovelInjectionPlanningRequest(
                branchID: pending.branchID,
                promptKind: .stateDeltaV1,
                userText: input.manuscript,
                stateSnapshotIDOverride: input.baseStateSnapshot.id,
                sessionCursorLimit: input.pending.sessionCursor
            )
        case .manualSync:
            let input = try NovelFactTransactionReducer.manualRebuildInput(
                pendingID: pendingID,
                in: document
            )
            kind = .manualRebuild
            request = NovelInjectionPlanningRequest(
                branchID: pending.branchID,
                promptKind: .manualSyncV1,
                userText: input.manuscript,
                stateSnapshotIDOverride: input.baseStateSnapshot.id,
                sessionCursorLimit: input.sessionCursor,
                includeUnsynchronizedStateWarning: false
            )
        }

        let plan = try NovelInjectionPlanner.plan(document: document, request: request)
        let link = NovelFactReceiptLink(
            pendingID: pending.id,
            ownerOperationID: pending.operationID,
            attemptOperationID: retryCommand?.context.operationID ?? pending.operationID,
            attemptPayloadSHA256: try retryCommand?.canonicalPayloadSHA256() ??
                pending.payloadSHA256,
            kind: kind
        )
        let injectionID = NovelReceiptID()
        let injection = NovelInjectionReceiptRecord(
            id: injectionID,
            runID: runID,
            projectID: document.project.id,
            branchID: pending.branchID,
            plan: plan,
            overrides: .none,
            providerID: "fact-test-provider",
            modelID: "fact-test-model",
            parameters: [:],
            factTransaction: link,
            createdAt: now
        )
        let generation = NovelGenerationReceiptRecord(
            id: NovelReceiptID(),
            runID: runID,
            providerID: injection.providerID,
            modelID: injection.modelID,
            promptVersion: injection.promptVersion,
            injectionReceiptID: injection.id,
            parameters: [:],
            requestSHA256: hashA,
            createdAt: now,
            factTransaction: link
        )
        return NovelFactTransactionReceiptArtifacts(
            injectionReceipt: injection,
            generationReceipt: generation
        )
    }
}

func NovelXCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    _ errorHandler: (Error) -> Void = { _ in },
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail("Expected expression to throw.", file: file, line: line)
    } catch {
        errorHandler(error)
    }
}
