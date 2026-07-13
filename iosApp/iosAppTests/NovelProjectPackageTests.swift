import Foundation
import XCTest
@testable import iosApp

final class NovelProjectPackageTests: NovelPolishTestCase {
    func testPackageRoundTripPreservesComplexDocumentAndRawPayloadEvidence() throws {
        let document = try complexRunningDocument()

        let artifact = try NovelProjectPackageCodec.encode(document)
        let decoded = try NovelProjectPackageCodec.decode(artifact.data)
        let envelope = try envelopeObject(from: artifact.data)
        let payload = try XCTUnwrap(projectPayload(from: envelope))

        XCTAssertEqual(decoded.document, document)
        XCTAssertEqual(decoded.artifact, artifact)
        XCTAssertEqual(artifact.projectByteCount, payload.count)
        XCTAssertEqual(artifact.projectSHA256, NovelProjectPackageCodec.sha256(payload))
        XCTAssertEqual((envelope["projectByteCount"] as? NSNumber)?.intValue, payload.count)
        XCTAssertEqual(envelope["projectSHA256"] as? String, artifact.projectSHA256)
        XCTAssertEqual(
            try JSONDecoder().decode(NovelProjectDocumentV1.self, from: payload),
            document
        )
        XCTAssertEqual(try NovelProjectPackageCodec.encode(decoded.document).data, artifact.data)
    }

    func testPackageRejectsTamperedByteCountHashBase64AndPayload() throws {
        let artifact = try NovelProjectPackageCodec.encode(documentWithChapterAndState().document)

        let wrongCount = try mutateEnvelope(artifact.data) { envelope in
            let count = try XCTUnwrap((envelope["projectByteCount"] as? NSNumber)?.intValue)
            envelope["projectByteCount"] = count + 1
        }
        assertInvalidPackage(wrongCount)

        let wrongHash = try mutateEnvelope(artifact.data) { envelope in
            envelope["projectSHA256"] = String(repeating: "0", count: 64)
        }
        XCTAssertThrowsError(try NovelProjectPackageCodec.decode(wrongHash)) { error in
            XCTAssertEqual(error as? NovelError, .packageChecksumMismatch)
        }

        let invalidBase64 = try mutateEnvelope(artifact.data) { envelope in
            envelope["projectJSONBase64"] = "%%%not-base64%%%"
        }
        assertInvalidPackage(invalidBase64)

        let changedPayload = try mutateEnvelope(artifact.data) { envelope in
            var payload = try XCTUnwrap(projectPayload(from: envelope))
            payload[payload.startIndex] ^= 0x01
            envelope["projectJSONBase64"] = payload.base64EncodedString()
        }
        XCTAssertThrowsError(try NovelProjectPackageCodec.decode(changedPayload)) { error in
            XCTAssertEqual(error as? NovelError, .packageChecksumMismatch)
        }
    }

    func testPackageRejectsUnsupportedAndMismatchedSchemas() throws {
        let artifact = try NovelProjectPackageCodec.encode(documentWithChapterAndState().document)
        let futureSchema = NovelProjectDocumentV1.currentSchemaVersion + 1

        let unsupportedEnvelope = try mutateEnvelope(artifact.data) { envelope in
            envelope["projectSchemaVersion"] = futureSchema
        }
        XCTAssertThrowsError(try NovelProjectPackageCodec.decode(unsupportedEnvelope)) { error in
            XCTAssertEqual(error as? NovelError, .unsupportedSchema(futureSchema))
        }

        let mismatchedPayload = try mutateEnvelope(artifact.data) { envelope in
            let payload = try XCTUnwrap(projectPayload(from: envelope))
            var object = try XCTUnwrap(
                JSONSerialization.jsonObject(with: payload) as? [String: Any]
            )
            object["schemaVersion"] = futureSchema
            let changed = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
            envelope["projectByteCount"] = changed.count
            envelope["projectSHA256"] = NovelProjectPackageCodec.sha256(changed)
            envelope["projectJSONBase64"] = changed.base64EncodedString()
        }
        assertInvalidPackage(mismatchedPayload)
    }

    func testPackageEnforcesPayloadAndEnvelopeLimits() throws {
        let document = try documentWithChapterAndState().document
        let payloadLimited = NovelProjectPackageLimits(
            maximumProjectBytes: 1,
            maximumEnvelopeBytes: 1_024
        )
        XCTAssertThrowsError(try NovelProjectPackageCodec.encode(
            document,
            limits: payloadLimited
        )) { error in
            XCTAssertEqual(error as? NovelError, .packageTooLarge(maximumBytes: 1))
        }

        let artifact = try NovelProjectPackageCodec.encode(document)
        let envelopeLimit = artifact.data.count - 1
        XCTAssertGreaterThan(envelopeLimit, 1)
        XCTAssertThrowsError(try NovelProjectPackageCodec.decode(
            artifact.data,
            limits: NovelProjectPackageLimits(
                maximumProjectBytes: 1,
                maximumEnvelopeBytes: envelopeLimit
            )
        )) { error in
            XCTAssertEqual(
                error as? NovelError,
                .packageTooLarge(maximumBytes: envelopeLimit)
            )
        }
    }

    func testHigherSchemaImportDoesNotTouchRepository() async throws {
        let document = try documentWithChapterAndState().document
        let artifact = try NovelProjectPackageCodec.encode(document)
        let futureSchema = NovelProjectDocumentV1.currentSchemaVersion + 1
        let unsupported = try mutateEnvelope(artifact.data) { envelope in
            envelope["projectSchemaVersion"] = futureSchema
        }
        let repository = CountingNovelProjectRepository()
        let creation = DefaultNovelCreation(repository: repository)
        let command = NovelImportProjectCommand(
            context: NovelTestFixtures.context(),
            projectID: document.project.id,
            packageData: unsupported,
            policy: .reject
        )

        await NovelXCTAssertThrowsErrorAsync(
            try await creation.perform(.importProject(command))
        ) { error in
            XCTAssertEqual(error as? NovelError, .unsupportedSchema(futureSchema))
        }
        let repositoryCalls = await repository.callCount()
        XCTAssertEqual(repositoryCalls, 0)
    }

    func testKeepBothRemapsTypedProjectReferencesWithoutChangingHistoricalHashes() throws {
        let source = try complexRunningDocument()
        let destinationID = NovelProjectID()
        let artifact = try NovelProjectPackageCodec.encode(source)
        let command = NovelImportProjectCommand(
            context: NovelTestFixtures.context(),
            projectID: destinationID,
            packageData: artifact.data,
            policy: .keepBoth(destinationProjectID: destinationID)
        )

        let prepared = try NovelProjectLifecycle.prepareImport(command)
        let imported = prepared.document
        let historical = Array(imported.appliedOperations.prefix(source.appliedOperations.count))

        XCTAssertEqual(prepared.sourceProjectID, source.project.id)
        XCTAssertEqual(prepared.destinationProjectID, destinationID)
        XCTAssertEqual(prepared.disposition, .keptBoth)
        XCTAssertEqual(prepared.interruptedRunCount, 1)
        XCTAssertEqual(imported.project.id, destinationID)
        XCTAssertTrue(imported.injectionReceipts.allSatisfy { $0.projectID == destinationID })
        XCTAssertTrue(imported.appliedOperations.allSatisfy { $0.outcome.projectID == destinationID })
        XCTAssertEqual(historical.map(\.operationID), source.appliedOperations.map(\.operationID))
        XCTAssertEqual(historical.map(\.payloadSHA256), source.appliedOperations.map(\.payloadSHA256))
        XCTAssertEqual(historical.map(\.kind), source.appliedOperations.map(\.kind))
        XCTAssertEqual(source.injectionReceipts.map(\.projectID), [source.project.id])
        XCTAssertNoThrow(try NovelDocumentValidator.validate(imported))
    }

    func testRunningNormalizationIsDeterministicAndIdempotent() throws {
        let source = try complexRunningDocument()

        let first = try NovelImportedProjectNormalizer.normalizeRunningRuns(in: source)
        let replay = try NovelImportedProjectNormalizer.normalizeRunningRuns(in: source)
        let idempotent = try NovelImportedProjectNormalizer.normalizeRunningRuns(in: first.document)

        XCTAssertEqual(first.document, replay.document)
        XCTAssertEqual(first.interruptedRunCount, 1)
        XCTAssertEqual(replay.interruptedRunCount, 1)
        XCTAssertEqual(idempotent.document, first.document)
        XCTAssertEqual(idempotent.interruptedRunCount, 0)
        XCTAssertEqual(first.document.activeRuns.first?.status, .interrupted)
        XCTAssertEqual(first.document.activeRuns.first?.interruptionReason, .recovery)
        XCTAssertNil(first.document.branches.first?.activeRunID)
        XCTAssertEqual(first.document.project.revision, source.project.revision + 1)
        XCTAssertEqual(first.document.appliedOperations, source.appliedOperations)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(first.document))
    }

    func testMarkdownUsesHeadCheckpointInsteadOfUnsynchronizedWorkingVersion() throws {
        let fixture = try documentWithChapterAndState()
        let source = fixture.document
        let branch = try XCTUnwrap(source.branches.first)
        let chapterID = try XCTUnwrap(branch.workingChapterSelections.first?.chapterID)
        let workingContent = "Mara opens the sealed gate, changing the unresolved plot."
        let command = NovelSaveManualEditCommand(
            context: mutationContext(document: source),
            projectID: source.project.id,
            branchID: branch.id,
            chapterID: chapterID,
            versionID: NovelChapterVersionID(),
            title: "Working Rewrite",
            content: workingContent,
            factCompatibilityID: UUID(),
            expectedWorkingRevision: branch.workingRevision
        )
        let edited = try NovelFactTransactionReducer.saveManualEdit(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: source,
            now: now.addingTimeInterval(10)
        ).document

        let exported = try NovelMarkdownExporter.export(edited, branchID: branch.id)

        XCTAssertEqual(edited.branches[0].syncStatus, .needsSync)
        XCTAssertNotEqual(
            edited.branches[0].workingChapterSelections,
            source.branches[0].workingChapterSelections
        )
        XCTAssertTrue(exported.markdown.contains(fixture.sourceContent))
        XCTAssertFalse(exported.markdown.contains(workingContent))
        XCTAssertTrue(exported.markdown.contains("# Chapter One"))
        XCTAssertTrue(exported.markdown.hasSuffix("\n"))
    }

    func testRejectReplaceExportAndDeleteLifecycle() async throws {
        let document = try documentWithChapterAndState().document
        let artifact = try NovelProjectPackageCodec.encode(document)
        let repository = InMemoryNovelProjectRepository()
        let creation = DefaultNovelCreation(repository: repository)
        let importCommand = NovelImportProjectCommand(
            context: NovelTestFixtures.context(),
            projectID: document.project.id,
            packageData: artifact.data,
            policy: .reject
        )

        let importedOutcome = try await creation.perform(.importProject(importCommand))
        XCTAssertEqual(
            importedOutcome,
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
            return XCTFail("Expected a project package snapshot.")
        }
        XCTAssertEqual(exported.data, artifact.data)

        let duplicate = NovelImportProjectCommand(
            context: NovelTestFixtures.context(),
            projectID: document.project.id,
            packageData: artifact.data,
            policy: .reject
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.perform(.importProject(duplicate))
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectAlreadyExists(document.project.id))
        }

        let imported = try await repository.loadProject(id: document.project.id).document
        _ = try await creation.perform(NovelTestFixtures.renameAction(document: imported))
        let renamed = try await repository.loadProject(id: document.project.id).document
        let replace = NovelImportProjectCommand(
            context: NovelTestFixtures.context(projectRevision: renamed.project.revision),
            projectID: document.project.id,
            packageData: artifact.data,
            policy: .replace(expectedRevision: renamed.project.revision)
        )
        let replacedOutcome = try await creation.perform(.importProject(replace))
        XCTAssertEqual(
            replacedOutcome,
            .projectImported(
                sourceProjectID: document.project.id,
                projectID: document.project.id,
                disposition: .replaced,
                interruptedRunCount: 0,
                revision: document.project.revision
            )
        )
        let replacedDocument = try await repository.loadProject(id: document.project.id).document
        XCTAssertEqual(replacedDocument, document)

        let delete = NovelDeleteProjectCommand(
            context: NovelTestFixtures.context(projectRevision: document.project.revision),
            projectID: document.project.id
        )
        let deletedOutcome = try await creation.perform(.deleteProject(delete))
        XCTAssertEqual(
            deletedOutcome,
            .projectDeleted(projectID: document.project.id)
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.snapshot(.project(document.project.id))
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectNotFound(document.project.id))
        }
    }

    func testActiveRunBlocksExportsRejectReplaceAndDelete() async throws {
        let document = try documentWithChapterAndState().document
        let artifact = try NovelProjectPackageCodec.encode(document)
        let repository = InMemoryNovelProjectRepository()
        _ = try await repository.createProject(document)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: resolvedModel,
            scripts: [NovelModelScript(steps: [
                .pause,
                .delta("The plan remains unchanged."),
                .complete,
            ])]
        )
        let creation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter,
            now: { Date(timeIntervalSince1970: 1_700_500_100) }
        )
        let request = discussionRequest(for: document)
        let run = try await creation.start(request)
        let busy = NovelError.projectBusy(document.project.id)

        await NovelXCTAssertThrowsErrorAsync(
            try await creation.snapshot(.projectPackage(document.project.id))
        ) { XCTAssertEqual($0 as? NovelError, busy) }
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.snapshot(.branchMarkdown(
                projectID: document.project.id,
                branchID: document.branches[0].id
            ))
        ) { XCTAssertEqual($0 as? NovelError, busy) }

        let reject = NovelImportProjectCommand(
            context: NovelTestFixtures.context(),
            projectID: document.project.id,
            packageData: artifact.data,
            policy: .reject
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.perform(.importProject(reject))
        ) { XCTAssertEqual($0 as? NovelError, busy) }

        let runningRevision = document.project.revision + 1
        let replace = NovelImportProjectCommand(
            context: NovelTestFixtures.context(projectRevision: runningRevision),
            projectID: document.project.id,
            packageData: artifact.data,
            policy: .replace(expectedRevision: runningRevision)
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.perform(.importProject(replace))
        ) { XCTAssertEqual($0 as? NovelError, busy) }

        let delete = NovelDeleteProjectCommand(
            context: NovelTestFixtures.context(projectRevision: runningRevision),
            projectID: document.project.id
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await creation.perform(.deleteProject(delete))
        ) { XCTAssertEqual($0 as? NovelError, busy) }

        await adapter.resume(runID: request.id)
        var didComplete = false
        for await event in run.events {
            if case .completed = event {
                didComplete = true
            }
        }
        XCTAssertTrue(didComplete)
    }
}

private extension NovelProjectPackageTests {
    func complexRunningDocument() throws -> NovelProjectDocumentV1 {
        var document = try documentWithChapterAndState().document
        document = try NovelReducer.apply(
            NovelTestFixtures.materialAction(
                document: document,
                title: "Gate Covenant",
                content: "The sealed gate records every promise spoken nearby."
            ),
            to: document,
            now: now.addingTimeInterval(1)
        ).document
        let request = discussionRequest(for: document)
        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: request.branchID,
                promptKind: .discussion,
                userText: request.userText
            )
        )
        let injection = NovelInjectionReceiptRecord(
            id: request.injectionReceiptID,
            runID: request.id,
            projectID: request.projectID,
            branchID: request.branchID,
            plan: plan,
            overrides: request.injectionOverrides,
            providerID: "package-provider",
            modelID: "package-model",
            parameters: ["temperature": "0.7"],
            createdAt: now.addingTimeInterval(2)
        )
        let generation = NovelGenerationReceiptRecord(
            id: request.generationReceiptID,
            runID: request.id,
            providerID: injection.providerID,
            modelID: injection.modelID,
            promptVersion: injection.promptVersion,
            injectionReceiptID: injection.id,
            parameters: injection.parameters,
            requestSHA256: NovelTestFixtures.hashA,
            createdAt: now.addingTimeInterval(2)
        )
        return try NovelGenerationReducer.begin(
            request,
            artifacts: NovelGenerationStartArtifacts(
                injectionReceipt: injection,
                generationReceipt: generation
            ),
            in: document,
            now: now.addingTimeInterval(2)
        ).document
    }

    func discussionRequest(for document: NovelProjectDocumentV1) -> NovelRunRequest {
        let branch = document.branches[0]
        return NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: document.project.id,
            branchID: branch.id,
            kind: .discussion,
            mode: .discussPlan,
            granularity: nil,
            userText: "Should the sealed gate remain closed?",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: nil,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: branch.headRevision
        )
    }

    func mutationContext(document: NovelProjectDocumentV1) -> NovelMutationContext {
        NovelMutationContext(
            operationID: NovelOperationID(),
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: document.branches[0].headRevision
        )
    }

    func envelopeObject(from data: Data) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func projectPayload(from envelope: [String: Any]) -> Data? {
        guard let encoded = envelope["projectJSONBase64"] as? String else { return nil }
        return Data(base64Encoded: encoded, options: [])
    }

    func mutateEnvelope(
        _ data: Data,
        _ mutation: (inout [String: Any]) throws -> Void
    ) throws -> Data {
        var envelope = try envelopeObject(from: data)
        try mutation(&envelope)
        return try JSONSerialization.data(withJSONObject: envelope, options: [.sortedKeys])
    }

    func assertInvalidPackage(
        _ data: Data,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertThrowsError(
            try NovelProjectPackageCodec.decode(data),
            file: file,
            line: line
        ) { error in
            guard case .invalidPackage = error as? NovelError else {
                return XCTFail("Expected invalidPackage, got \(error)", file: file, line: line)
            }
        }
    }
}

private actor CountingNovelProjectRepository: NovelProjectPersisting {
    private var calls = 0

    func callCount() -> Int { calls }

    func listProjects() async throws -> [NovelProjectSummary] {
        calls += 1
        throw unexpectedCall()
    }

    func loadProject(id: NovelProjectID) async throws -> NovelLoadedProject {
        calls += 1
        throw unexpectedCall()
    }

    func createProject(_ document: NovelProjectDocumentV1) async throws -> NovelLoadedProject {
        calls += 1
        throw unexpectedCall()
    }

    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64,
        authorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelLoadedProject {
        calls += 1
        throw unexpectedCall()
    }

    func replaceProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64
    ) async throws -> NovelLoadedProject {
        calls += 1
        throw unexpectedCall()
    }

    func deleteProject(id: NovelProjectID, expectedRevision: Int64) async throws {
        calls += 1
        throw unexpectedCall()
    }

    func restorePreviousProject(
        id: NovelProjectID,
        expectedDocumentSHA256: String
    ) async throws -> NovelLoadedProject {
        calls += 1
        throw unexpectedCall()
    }

    func listRecoverySidecars() async throws -> [NovelRecoverySidecarV1] {
        calls += 1
        throw unexpectedCall()
    }

    func writeRecoverySidecar(_ sidecar: NovelRecoverySidecarV1) async throws {
        calls += 1
        throw unexpectedCall()
    }

    func removeRecoverySidecar(projectID: NovelProjectID, runID: NovelRunID) async throws {
        calls += 1
        throw unexpectedCall()
    }

    private func unexpectedCall() -> NovelError {
        .repositoryFailure("Repository must not be touched during package preflight.")
    }
}
