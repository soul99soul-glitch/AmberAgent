import Foundation
import XCTest
@testable import iosApp

final class NovelProjectRepositoryTests: XCTestCase {
    func testCreateListLoadAndRestartRoundTrip() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let document = try NovelTestFixtures.document()
        let repository = NovelFileProjectRepository(rootDirectory: root)

        _ = try await repository.createProject(document)
        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let summaries = try await restarted.listProjects()
        let loaded = try await restarted.loadProject(id: document.project.id)

        XCTAssertEqual(summaries.map(\.id), [document.project.id])
        XCTAssertEqual(loaded.document, document)
        XCTAssertEqual(loaded.access, .readWrite)
    }

    func testListProjectsUsesFreshIndexWithoutRequiringFullRescanAfterRestart() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let document = try NovelTestFixtures.document()
        let repository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await repository.createProject(document)

        // First list builds and writes the lightweight index.
        let first = try await repository.listProjects()
        XCTAssertEqual(first.map(\.id), [document.project.id])
        XCTAssertTrue(FileManager.default.fileExists(atPath: indexURL(root: root).path))

        // Second list on a cold repository must stay correct while preferring the
        // cached inventory (no project file is newer than the index).
        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let second = try await restarted.listProjects()
        XCTAssertEqual(second.map(\.id), [document.project.id])
        XCTAssertEqual(second.first?.name, document.project.name)
        XCTAssertEqual(second.first?.revision, document.project.revision)
    }

    func testSuccessfulCommitsRotateOnlyThePreviousValidatedVersion() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        let second = try renamed(first, name: "Version Two")
        _ = try await repository.commitProject(second, expectedRevision: 1)
        let third = try renamed(second, name: "Version Three")
        _ = try await repository.commitProject(third, expectedRevision: 2)

        try Data("corrupt".utf8).write(to: primaryURL(root: root, id: first.project.id), options: [.atomic])
        let degraded = try await NovelFileProjectRepository(rootDirectory: root)
            .loadProject(id: first.project.id)

        XCTAssertEqual(degraded.document, second)
        guard case .degradedPrevious = degraded.access else {
            return XCTFail("Expected the previous validated project in degraded mode.")
        }
    }

    func testFailureBeforePrimaryInstallLeavesOfficialDocumentUnchanged() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let baseRepository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await baseRepository.createProject(first)
        let second = try renamed(first, name: "Must Not Install")
        let failing = NovelFileProjectRepository(
            rootDirectory: root,
            failingStages: [.beforePrimaryInstall]
        )

        await NovelXCTAssertThrowsErrorAsync(
            try await failing.commitProject(second, expectedRevision: first.project.revision)
        )
        let loaded = try await baseRepository.loadProject(id: first.project.id)

        XCTAssertEqual(loaded.document, first)
        XCTAssertFalse(FileManager.default.fileExists(atPath: previousURL(root: root, id: first.project.id).path))
    }

    func testIndexFailureDoesNotRollBackProjectAndNextLaunchRebuildsIndex() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let baseRepository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await baseRepository.createProject(first)
        let second = try renamed(first, name: "Committed")
        let failingIndex = NovelFileProjectRepository(
            rootDirectory: root,
            failingStages: [.beforeIndexWrite]
        )

        let committed = try await failingIndex.commitProject(second, expectedRevision: 1)
        XCTAssertEqual(committed.document, second)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("index.json").path))

        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let summaries = try await restarted.listProjects()
        XCTAssertEqual(summaries.first?.revision, 2)
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("index.json").path))
    }

    func testCrashAfterPrimaryInstallIsRecoveredFromOfficialDocument() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let baseRepository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await baseRepository.createProject(first)
        let second = try renamed(first, name: "Durable Before Crash")
        let crashing = NovelFileProjectRepository(
            rootDirectory: root,
            failingStages: [.afterPrimaryInstallBeforeIndex]
        )

        await NovelXCTAssertThrowsErrorAsync(
            try await crashing.commitProject(second, expectedRevision: 1)
        )
        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let loaded = try await restarted.loadProject(id: first.project.id)

        XCTAssertEqual(loaded.document, second)
        let summaries = try await restarted.listProjects()
        XCTAssertEqual(summaries.first?.revision, 2)
    }

    func testCorruptPrimaryLoadsPreviousReadOnlyAndExplicitRestorePreservesBackup() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        let second = try renamed(first, name: "Second")
        _ = try await repository.commitProject(second, expectedRevision: 1)
        try Data("broken".utf8).write(to: primaryURL(root: root, id: first.project.id), options: [.atomic])

        let degraded = try await repository.loadProject(id: first.project.id)
        XCTAssertEqual(degraded.document, first)
        guard case .degradedPrevious = degraded.access else {
            return XCTFail("Expected degraded access.")
        }
        await NovelXCTAssertThrowsErrorAsync(
            try await repository.commitProject(second, expectedRevision: first.project.revision)
        ) { error in
            XCTAssertEqual(error as? NovelError, .degradedReadOnly(projectID: first.project.id))
        }

        let restored = try await repository.restorePreviousProject(
            id: first.project.id,
            expectedDocumentSHA256: try NovelProjectPackageCodec.encode(first).projectSHA256
        )
        XCTAssertEqual(restored.document, first)
        XCTAssertEqual(restored.access, .readWrite)
        XCTAssertTrue(FileManager.default.fileExists(atPath: previousURL(root: root, id: first.project.id).path))
    }

    func testPreviousRestoreRejectsStaleExpectedHashBeforeReplacingPrimary() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        let second = try renamed(first, name: "Second")
        _ = try await repository.commitProject(second, expectedRevision: first.project.revision)
        let primary = primaryURL(root: root, id: first.project.id)
        let brokenBytes = Data("broken".utf8)
        try brokenBytes.write(to: primary, options: [.atomic])

        await NovelXCTAssertThrowsErrorAsync(
            try await repository.restorePreviousProject(
                id: first.project.id,
                expectedDocumentSHA256: NovelTestFixtures.hashA
            )
        ) { error in
            XCTAssertEqual(error as? NovelError, .storageIndeterminate(first.project.id))
        }
        XCTAssertEqual(try Data(contentsOf: primary), brokenBytes)
        let degraded = try await repository.loadProject(id: first.project.id)
        XCTAssertEqual(degraded.document, first)
        guard case .degradedPrevious = degraded.access else {
            return XCTFail("Expected the previous copy to remain read-only.")
        }
    }

    func testPreviousOnlyProjectRemainsVisibleAsDegraded() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        let second = try renamed(first, name: "Second")
        _ = try await repository.commitProject(second, expectedRevision: first.project.revision)
        try FileManager.default.removeItem(at: primaryURL(root: root, id: first.project.id))
        try? FileManager.default.removeItem(at: root.appendingPathComponent("index.json"))

        let summaries = try await repository.listProjects()
        let loaded = try await repository.loadProject(id: first.project.id)

        XCTAssertEqual(summaries.count, 1)
        let summary = try XCTUnwrap(summaries.first)
        XCTAssertEqual(summary.id, first.project.id)
        XCTAssertEqual(summary.revision, first.project.revision)
        XCTAssertEqual(summary.isDegraded, true)
        XCTAssertEqual(loaded.document, first)
        guard case .degradedPrevious = loaded.access else {
            return XCTFail("Expected previous-only project to load read-only")
        }
    }

    func testHigherSchemaNeverFallsBackToPrevious() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        let second = try renamed(first, name: "Second")
        _ = try await repository.commitProject(second, expectedRevision: 1)

        let url = primaryURL(root: root, id: first.project.id)
        var json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        )
        json["schemaVersion"] = 99
        try JSONSerialization.data(withJSONObject: json).write(to: url, options: [.atomic])

        await NovelXCTAssertThrowsErrorAsync(try await repository.loadProject(id: first.project.id)) { error in
            XCTAssertEqual(error as? NovelError, .unsupportedSchema(99))
        }
    }

    func testRecoverySidecarPersistsAndRejectsSequenceRollback() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let projectID = NovelProjectID()
        let runID = NovelRunID()
        let first = recovery(projectID: projectID, runID: runID, sequence: 1, content: "one")
        let second = recovery(projectID: projectID, runID: runID, sequence: 2, content: "two")

        try await repository.writeRecoverySidecar(first)
        try await repository.writeRecoverySidecar(second)
        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let restoredSidecars = try await restarted.listRecoverySidecars()
        XCTAssertEqual(restoredSidecars, [second])

        await NovelXCTAssertThrowsErrorAsync(try await restarted.writeRecoverySidecar(first)) { error in
            guard let novelError = error as? NovelError,
                  case .invalidRecovery = novelError else {
                return XCTFail("Expected invalidRecovery, got \(error)")
            }
        }
        try await restarted.removeRecoverySidecar(projectID: projectID, runID: runID)
        let remainingSidecars = try await restarted.listRecoverySidecars()
        XCTAssertEqual(remainingSidecars, [])
    }

    func testRecoverySidecarPersistsCompleteResponsesCursorAndRejectsHalfCursor() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let base = recovery(
            projectID: NovelProjectID(),
            runID: NovelRunID(),
            sequence: 3,
            content: "已保存正文"
        )
        let resumable = NovelRecoverySidecarV1(
            schemaVersion: base.schemaVersion,
            projectID: base.projectID,
            runID: base.runID,
            branchID: base.branchID,
            sessionID: base.sessionID,
            messageID: base.messageID,
            baseProjectRevision: base.baseProjectRevision,
            sequence: base.sequence,
            partialContent: base.partialContent,
            partialSHA256: base.partialSHA256,
            updatedAt: base.updatedAt,
            responseID: "resp_123",
            responseSequenceNumber: 17
        )

        try await repository.writeRecoverySidecar(resumable)
        let restored = try await NovelFileProjectRepository(rootDirectory: root)
            .listRecoverySidecars()
        XCTAssertEqual(restored, [resumable])

        let incomplete = NovelRecoverySidecarV1(
            schemaVersion: base.schemaVersion,
            projectID: NovelProjectID(),
            runID: NovelRunID(),
            branchID: base.branchID,
            sessionID: base.sessionID,
            messageID: base.messageID,
            baseProjectRevision: base.baseProjectRevision,
            sequence: base.sequence,
            partialContent: base.partialContent,
            partialSHA256: base.partialSHA256,
            updatedAt: base.updatedAt,
            responseID: "resp_missing_sequence"
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await repository.writeRecoverySidecar(incomplete)
        ) { error in
            guard let novelError = error as? NovelError,
                  case .invalidRecovery = novelError else {
                return XCTFail("Expected invalidRecovery, got \(error)")
            }
        }
    }

    func testRecoveryRejectsHashMismatchAndEqualSequenceConflict() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let projectID = NovelProjectID()
        let runID = NovelRunID()
        let first = recovery(projectID: projectID, runID: runID, sequence: 1, content: "one")
        var mismatched = recovery(
            projectID: projectID,
            runID: NovelRunID(),
            sequence: 1,
            content: "mismatch"
        )
        mismatched = NovelRecoverySidecarV1(
            schemaVersion: mismatched.schemaVersion,
            projectID: mismatched.projectID,
            runID: mismatched.runID,
            branchID: mismatched.branchID,
            sessionID: mismatched.sessionID,
            messageID: mismatched.messageID,
            baseProjectRevision: mismatched.baseProjectRevision,
            sequence: mismatched.sequence,
            partialContent: mismatched.partialContent,
            partialSHA256: NovelTestFixtures.hashA,
            updatedAt: mismatched.updatedAt
        )

        await NovelXCTAssertThrowsErrorAsync(
            try await repository.writeRecoverySidecar(mismatched)
        )
        try await repository.writeRecoverySidecar(first)
        try await repository.writeRecoverySidecar(first)
        let conflict = recovery(
            projectID: projectID,
            runID: runID,
            sequence: 1,
            content: "different"
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await repository.writeRecoverySidecar(conflict)
        ) { error in
            guard let novelError = error as? NovelError,
                  case .invalidRecovery = novelError else {
                return XCTFail("Expected invalidRecovery, got \(error)")
            }
        }
        let sidecars = try await repository.listRecoverySidecars()
        XCTAssertEqual(sidecars, [first])
    }

    func testCorruptRecoveryFilesDoNotBlockOtherProjectsOrTrustPayloadOwnership() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        let second = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        _ = try await repository.createProject(second)

        let firstRequest = discussionRequest(document: first, userText: "First pending run")
        let secondRequest = discussionRequest(document: second, userText: "Second pending run")
        let firstRunning = try runningDocument(first, request: firstRequest)
        let secondRunning = try runningDocument(second, request: secondRequest)
        _ = try await repository.commitProject(firstRunning, expectedRevision: first.project.revision)
        _ = try await repository.commitProject(secondRunning, expectedRevision: second.project.revision)

        let secondRun = try XCTUnwrap(secondRunning.activeRuns.first)
        let poison = "must not be restored"
        let crossOwnedPayload = NovelRecoverySidecarV1(
            schemaVersion: NovelRecoverySidecarV1.currentSchemaVersion,
            projectID: second.project.id,
            runID: secondRequest.id,
            branchID: secondRun.branchID,
            sessionID: secondRun.sessionID,
            messageID: secondRun.messageID,
            baseProjectRevision: secondRunning.project.revision,
            sequence: 1,
            partialContent: poison,
            partialSHA256: NovelDocumentValidator.sha256(poison),
            updatedAt: secondRunning.project.updatedAt
        )
        let firstRecoveryURL = recoveryURL(
            root: root,
            projectID: first.project.id,
            runID: firstRequest.id
        )
        try JSONEncoder().encode(crossOwnedPayload).write(to: firstRecoveryURL, options: [.atomic])

        let malformedRecoveryURL = recoveryURL(
            root: root,
            projectID: NovelProjectID(),
            runID: NovelRunID()
        )
        try Data("not-json".utf8).write(to: malformedRecoveryURL, options: [.atomic])

        let restartedRepository = NovelFileProjectRepository(rootDirectory: root)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "provider-id",
                ownerProviderID: "provider-id",
                modelID: "model-id",
                wireModelID: "novel-model",
                displayName: "Novel Model",
                contextWindowTokens: 128_000
            ),
            scripts: [NovelModelScript(steps: [.delta("Healthy reply"), .complete])]
        )
        let module = DefaultNovelCreation(
            repository: restartedRepository,
            modelRunner: adapter
        )

        guard case .project(let healthySnapshot) = try await module.snapshot(.project(second.project.id)) else {
            return XCTFail("Expected the healthy project snapshot.")
        }
        XCTAssertEqual(healthySnapshot.project.id, second.project.id)

        let firstBeforeOpen = try await restartedRepository.loadProject(id: first.project.id)
        let recoveredSecond = try await restartedRepository.loadProject(id: second.project.id)
        XCTAssertEqual(firstBeforeOpen.document.activeRuns.first?.status, .running)
        XCTAssertEqual(recoveredSecond.document.activeRuns.first?.status, .interrupted)
        XCTAssertEqual(recoveredSecond.document.activeRuns.first?.interruptionReason, .recovery)
        XCTAssertEqual(recoveredSecond.document.activeRuns.first?.partialContent, "")
        XCTAssertFalse(recoveredSecond.document.sessions[0].messages.contains {
            $0.content == poison
        })

        _ = try await module.snapshot(.project(first.project.id))
        let recoveredFirst = try await restartedRepository.loadProject(id: first.project.id)
        XCTAssertEqual(recoveredFirst.document.activeRuns.first?.status, .interrupted)
        XCTAssertEqual(recoveredFirst.document.activeRuns.first?.interruptionReason, .recovery)
        XCTAssertEqual(recoveredFirst.document.activeRuns.first?.partialContent, "")
        XCTAssertFalse(FileManager.default.fileExists(atPath: firstRecoveryURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: malformedRecoveryURL.path))
        let remainingSidecars = try await restartedRepository.listRecoverySidecars()
        XCTAssertTrue(remainingSidecars.isEmpty)

        let nextRequest = discussionRequest(
            document: recoveredSecond.document,
            userText: "Continue after recovery"
        )
        let run = try await module.start(nextRequest)
        var completed: NovelSessionMessageSnapshot?
        for await event in run.events {
            if case .completed(let snapshot) = event {
                completed = snapshot
            }
        }
        XCTAssertEqual(completed?.message.content, "Healthy reply")
        let finalSecond = try await restartedRepository.loadProject(id: second.project.id)
        XCTAssertEqual(
            finalSecond.document.activeRuns.first(where: { $0.id == nextRequest.id })?.status,
            .completed
        )
    }

    func testRepositoriesRejectRewrittenImmutableHistory() async throws {
        let first = try NovelTestFixtures.document()
        let materialDocument = try NovelReducer.apply(
            NovelTestFixtures.materialAction(document: first),
            to: first
        ).document
        var rewritten = materialDocument
        let revision = rewritten.materialRevisions[0]
        rewritten.materialRevisions[0] = NovelMaterialRevisionRecord(
            id: revision.id,
            materialID: revision.materialID,
            revision: revision.revision,
            title: revision.title,
            content: "Rewritten history",
            tags: revision.tags,
            injectionMode: revision.injectionMode,
            createdAt: revision.createdAt,
            operationID: revision.operationID
        )
        rewritten.project.revision += 1
        rewritten.project.updatedAt = rewritten.project.updatedAt.addingTimeInterval(1)

        let memory = InMemoryNovelProjectRepository()
        _ = try await memory.createProject(materialDocument)
        await NovelXCTAssertThrowsErrorAsync(
            try await memory.commitProject(
                rewritten,
                expectedRevision: materialDocument.project.revision
            )
        )

        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let file = NovelFileProjectRepository(rootDirectory: root)
        _ = try await file.createProject(materialDocument)
        await NovelXCTAssertThrowsErrorAsync(
            try await file.commitProject(
                rewritten,
                expectedRevision: materialDocument.project.revision
            )
        )
        let stored = try await file.loadProject(id: materialDocument.project.id)
        XCTAssertEqual(stored.document, materialDocument)
    }

    func testOperationIDsAreScopedToTheirProject() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let operationID = NovelOperationID()
        let first = try NovelTestFixtures.document(operationID: operationID)
        let second = try NovelTestFixtures.document(
            operationID: operationID
        )
        _ = try await repository.createProject(first)
        _ = try await repository.createProject(second)

        let summaries = try await repository.listProjects()
        let loadedFirst = try await repository.loadProject(id: first.project.id)
        let loadedSecond = try await repository.loadProject(id: second.project.id)
        XCTAssertEqual(summaries.count, 2)
        XCTAssertEqual(loadedFirst.document.appliedOperations.first?.operationID, operationID)
        XCTAssertEqual(loadedSecond.document.appliedOperations.first?.operationID, operationID)
    }

    func testUnreadableProjectDoesNotBlockHealthyProjectListOrMutation() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        let second = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        _ = try await repository.createProject(second)
        try Data("corrupt".utf8).write(
            to: primaryURL(root: root, id: second.project.id),
            options: [.atomic]
        )
        let index = root.appendingPathComponent("index.json")
        try? FileManager.default.removeItem(at: index)

        let summaries = try await repository.listProjects()
        XCTAssertEqual(Set(summaries.map(\.id)), Set([first.project.id, second.project.id]))
        XCTAssertNil(summaries.first(where: { $0.id == first.project.id })?.loadError)
        XCTAssertNotNil(summaries.first(where: { $0.id == second.project.id })?.loadError)

        let module = DefaultNovelCreation(repository: repository)
        let outcome = try await module.perform(NovelTestFixtures.renameAction(
            document: first,
            operationID: NovelOperationID(),
            name: "Healthy"
        ))
        let healthy = try await repository.loadProject(id: first.project.id)
        XCTAssertEqual(outcome, .projectRenamed(projectID: first.project.id, revision: 2))
        XCTAssertEqual(healthy.document.project.name, "Healthy")
    }

    func testUnavailableProjectCanBeDiscardedWithoutLoadingItsDocument() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let document = try NovelTestFixtures.document()
        _ = try await repository.createProject(document)
        try Data("corrupt".utf8).write(
            to: primaryURL(root: root, id: document.project.id),
            options: [.atomic]
        )

        let summaries = try await repository.listProjects()
        let unavailable = try XCTUnwrap(summaries.first)
        XCTAssertEqual(unavailable.id, document.project.id)
        XCTAssertNotNil(unavailable.loadError)

        let creation = DefaultNovelCreation(repository: repository)
        let outcome = try await creation.perform(.deleteProject(NovelDeleteProjectCommand(
            context: NovelTestFixtures.context(projectRevision: unavailable.revision),
            projectID: unavailable.id
        )))

        XCTAssertEqual(outcome, .projectDeleted(projectID: document.project.id))
        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let remainingProjects = try await restarted.listProjects()
        XCTAssertTrue(remainingProjects.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: primaryURL(root: root, id: document.project.id).path
        ))
    }

    func testDeletionTombstonePreventsEveryStaleArtifactFromResurrectingAfterRestart() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        let second = try renamed(first, name: "Deleted Version")
        _ = try await repository.commitProject(
            second,
            expectedRevision: first.project.revision
        )
        let sidecar = recovery(
            projectID: first.project.id,
            runID: NovelRunID(),
            sequence: 1,
            content: "stale partial prose"
        )
        try await repository.writeRecoverySidecar(sidecar)
        _ = try await repository.listProjects()
        let staleIndex = try Data(contentsOf: indexURL(root: root))

        let interruptedDeletion = NovelFileProjectRepository(
            rootDirectory: root,
            failingStages: [.afterDeletionTombstoneWrite]
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await interruptedDeletion.deleteProject(
                id: first.project.id,
                expectedRevision: second.project.revision
            )
        ) { error in
            XCTAssertEqual(error as? NovelError, .storageIndeterminate(first.project.id))
        }

        XCTAssertTrue(FileManager.default.fileExists(
            atPath: tombstoneURL(root: root, id: first.project.id).path
        ))
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: primaryURL(root: root, id: first.project.id).path
        ))
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: previousURL(root: root, id: first.project.id).path
        ))
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: recoveryURL(root: root, projectID: first.project.id, runID: sidecar.runID).path
        ))

        // Simulate an index writer that lands after the tombstone was made durable.
        try staleIndex.write(to: indexURL(root: root), options: [.atomic])
        let restarted = NovelFileProjectRepository(rootDirectory: root)
        let survivingSidecars = try await restarted.listRecoverySidecars()
        XCTAssertEqual(survivingSidecars, [])
        await NovelXCTAssertThrowsErrorAsync(
            try await restarted.loadProject(id: first.project.id)
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectNotFound(first.project.id))
        }
        let survivingProjects = try await restarted.listProjects()
        XCTAssertEqual(survivingProjects, [])

        XCTAssertFalse(FileManager.default.fileExists(
            atPath: primaryURL(root: root, id: first.project.id).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: previousURL(root: root, id: first.project.id).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: recoveryURL(root: root, projectID: first.project.id, runID: sidecar.runID).path
        ))
        // The marker may remain if cleanup was only partially confirmed; either state is safe
        // as long as every public read remains absent and no stale artifact survives.
    }

    func testDeletionTombstoneRejectsLateCommitAndRecoveryWrite() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        let second = try renamed(first, name: "Last Official Version")
        _ = try await repository.commitProject(
            second,
            expectedRevision: first.project.revision
        )

        let interruptedDeletion = NovelFileProjectRepository(
            rootDirectory: root,
            failingStages: [.afterDeletionTombstoneWrite]
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await interruptedDeletion.deleteProject(
                id: first.project.id,
                expectedRevision: second.project.revision
            )
        )
        let lateWriter = NovelFileProjectRepository(rootDirectory: root)
        let lateCommit = try renamed(second, name: "Must Not Return")
        await NovelXCTAssertThrowsErrorAsync(
            try await lateWriter.commitProject(
                lateCommit,
                expectedRevision: second.project.revision
            )
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectNotFound(first.project.id))
        }
        let lateSidecar = recovery(
            projectID: first.project.id,
            runID: NovelRunID(),
            sequence: 1,
            content: "must not be persisted"
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await lateWriter.writeRecoverySidecar(lateSidecar)
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectNotFound(first.project.id))
        }

        XCTAssertTrue(FileManager.default.fileExists(
            atPath: tombstoneURL(root: root, id: first.project.id).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: recoveryURL(
                root: root,
                projectID: first.project.id,
                runID: lateSidecar.runID
            ).path
        ))
    }

    func testInterruptedReplacementNeverFallsBackToOldPreviousWhenNewPrimaryIsCorrupt() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        let oldCurrent = try renamed(first, name: "Old Current")
        _ = try await repository.commitProject(
            oldCurrent,
            expectedRevision: first.project.revision
        )
        let replacement = try renamed(
            NovelTestFixtures.document(projectID: first.project.id),
            name: "Imported Replacement"
        )
        let interruptedReplacement = NovelFileProjectRepository(
            rootDirectory: root,
            failingStages: [.afterReplacementInstallBeforeCleanup]
        )
        await NovelXCTAssertThrowsErrorAsync(
            try await interruptedReplacement.replaceProject(
                replacement,
                expectedRevision: oldCurrent.project.revision
            )
        ) { error in
            XCTAssertEqual(error as? NovelError, .storageIndeterminate(first.project.id))
        }
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: replacementMarkerURL(root: root, id: first.project.id).path
        ))
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: previousURL(root: root, id: first.project.id).path
        ))
        try Data("corrupt replacement".utf8).write(
            to: primaryURL(root: root, id: first.project.id),
            options: [.atomic]
        )

        let restarted = NovelFileProjectRepository(rootDirectory: root)
        await NovelXCTAssertThrowsErrorAsync(
            try await restarted.loadProject(id: first.project.id)
        ) { error in
            XCTAssertEqual(error as? NovelError, .storageIndeterminate(first.project.id))
        }
        let summaries = try await restarted.listProjects()
        let summary = try XCTUnwrap(
            summaries.first(where: { $0.id == first.project.id })
        )
        XCTAssertNotNil(summary.loadError)
        XCTAssertEqual(summary.name, "Unavailable Project")
        XCTAssertNotEqual(summary.name, first.project.name)
    }

    func testExportDeleteRestartAndRejectImportRestoresExactDocument() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        let original = try renamed(first, name: "Portable Novel")
        _ = try await repository.createProject(original)
        let module = DefaultNovelCreation(repository: repository)
        guard case .package(let package) = try await module.snapshot(
            .projectPackage(original.project.id)
        ) else {
            return XCTFail("Expected a project package snapshot.")
        }

        let deletion = NovelDeleteProjectCommand(
            context: NovelTestFixtures.context(
                projectRevision: original.project.revision
            ),
            projectID: original.project.id
        )
        let deletionOutcome = try await module.perform(.deleteProject(deletion))
        XCTAssertEqual(deletionOutcome, .projectDeleted(projectID: original.project.id))

        let restartedRepository = NovelFileProjectRepository(rootDirectory: root)
        let restartedModule = DefaultNovelCreation(repository: restartedRepository)
        guard case .projects(let projects) = try await restartedModule.snapshot(.projects) else {
            return XCTFail("Expected project summaries after restart.")
        }
        XCTAssertTrue(projects.isEmpty)
        let importCommand = NovelImportProjectCommand(
            context: NovelTestFixtures.context(),
            projectID: original.project.id,
            packageData: package.data,
            policy: .reject
        )
        let importOutcome = try await restartedModule.perform(.importProject(importCommand))
        XCTAssertEqual(
            importOutcome,
            .projectImported(
                sourceProjectID: original.project.id,
                projectID: original.project.id,
                disposition: .created,
                interruptedRunCount: 0,
                revision: original.project.revision
            )
        )

        let reloaded = try await NovelFileProjectRepository(rootDirectory: root)
            .loadProject(id: original.project.id)
        XCTAssertEqual(reloaded.document, original)
        XCTAssertEqual(reloaded.access, .readWrite)
    }

    private func renamed(
        _ document: NovelProjectDocumentV1,
        name: String
    ) throws -> NovelProjectDocumentV1 {
        try NovelReducer.apply(
            NovelTestFixtures.renameAction(
                document: document,
                name: name
            ),
            to: document
        ).document
    }

    private func recovery(
        projectID: NovelProjectID,
        runID: NovelRunID,
        sequence: Int64,
        content: String
    ) -> NovelRecoverySidecarV1 {
        NovelRecoverySidecarV1(
            schemaVersion: NovelRecoverySidecarV1.currentSchemaVersion,
            projectID: projectID,
            runID: runID,
            branchID: NovelBranchID(),
            sessionID: NovelSessionID(),
            messageID: NovelMessageID(),
            baseProjectRevision: 1,
            sequence: sequence,
            partialContent: content,
            partialSHA256: NovelDocumentValidator.sha256(content),
            updatedAt: Date(timeIntervalSince1970: TimeInterval(sequence))
        )
    }

    private func discussionRequest(
        document: NovelProjectDocumentV1,
        userText: String
    ) -> NovelRunRequest {
        NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            kind: .discussion,
            mode: .discussPlan,
            granularity: nil,
            userText: userText,
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

    private func runningDocument(
        _ document: NovelProjectDocumentV1,
        request: NovelRunRequest
    ) throws -> NovelProjectDocumentV1 {
        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: request.branchID,
                promptKind: .discussion,
                userText: request.userText,
                budget: NovelInjectionBudget(
                    maxEstimatedInputTokens: request.inputBudgetTokens,
                    chapterTailCharacterLimit: 6_000,
                    maximumRecentSessionMessages: 12
                )
            )
        )
        let timestamp = document.project.updatedAt.addingTimeInterval(1)
        let injection = NovelInjectionReceiptRecord(
            id: request.injectionReceiptID,
            runID: request.id,
            projectID: request.projectID,
            branchID: request.branchID,
            plan: plan,
            overrides: .none,
            providerID: "provider-id",
            modelID: "model-id",
            parameters: [:],
            createdAt: timestamp
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
            createdAt: timestamp
        )
        return try NovelGenerationReducer.begin(
            request,
            artifacts: NovelGenerationStartArtifacts(
                injectionReceipt: injection,
                generationReceipt: generation
            ),
            in: document,
            now: timestamp
        ).document
    }

    private func primaryURL(root: URL, id: NovelProjectID) -> URL {
        root.appendingPathComponent("projects", isDirectory: true)
            .appendingPathComponent("\(id.description).json")
    }

    private func previousURL(root: URL, id: NovelProjectID) -> URL {
        root.appendingPathComponent("projects", isDirectory: true)
            .appendingPathComponent("\(id.description).previous.json")
    }

    private func tombstoneURL(root: URL, id: NovelProjectID) -> URL {
        root.appendingPathComponent("tombstones", isDirectory: true)
            .appendingPathComponent("\(id.description).json")
    }

    private func replacementMarkerURL(root: URL, id: NovelProjectID) -> URL {
        root.appendingPathComponent("replacements", isDirectory: true)
            .appendingPathComponent("\(id.description).json")
    }

    private func indexURL(root: URL) -> URL {
        root.appendingPathComponent("index.json")
    }

    private func recoveryURL(
        root: URL,
        projectID: NovelProjectID,
        runID: NovelRunID
    ) -> URL {
        root.appendingPathComponent("recovery", isDirectory: true)
            .appendingPathComponent("\(projectID.description)-\(runID.description).json")
    }
}
