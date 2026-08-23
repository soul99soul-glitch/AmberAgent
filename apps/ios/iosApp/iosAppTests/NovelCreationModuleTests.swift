import XCTest
@testable import iosApp

final class NovelCreationModuleTests: XCTestCase {
    func testTwoProjectsRemainIsolated() async throws {
        let repository = InMemoryNovelProjectRepository()
        let module = DefaultNovelCreation(repository: repository)
        let first = NovelTestFixtures.createCommand(name: "First")
        let second = NovelTestFixtures.createCommand(
            operationID: NovelOperationID(),
            name: "Second"
        )
        _ = try await module.perform(.createProject(first))
        _ = try await module.perform(.createProject(second))
        let beforeSecond = try await projectSnapshot(module, id: second.projectID)

        let firstSnapshot = try await projectSnapshot(module, id: first.projectID)
        let rename = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: NovelOperationID(),
                projectRevision: firstSnapshot.project.revision
            ),
            projectID: first.projectID,
            name: "First Revised"
        ))
        _ = try await module.perform(rename)
        let afterSecond = try await projectSnapshot(module, id: second.projectID)

        XCTAssertEqual(beforeSecond, afterSecond)
        let revisedFirst = try await projectSnapshot(module, id: first.projectID)
        XCTAssertEqual(revisedFirst.project.name, "First Revised")
    }

    func testConcurrentSameOperationReturnsSameOutcomeOnce() async throws {
        let repository = InMemoryNovelProjectRepository()
        let module = DefaultNovelCreation(repository: repository)
        let command = NovelTestFixtures.createCommand()
        _ = try await module.perform(.createProject(command))
        let snapshot = try await projectSnapshot(module, id: command.projectID)
        let operationID = NovelOperationID()
        let action = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                projectRevision: snapshot.project.revision
            ),
            projectID: command.projectID,
            name: "Concurrent"
        ))

        async let first = module.perform(action)
        async let second = module.perform(action)
        let outcomes = try await [first, second]
        let final = try await projectSnapshot(module, id: command.projectID)

        XCTAssertEqual(outcomes[0], outcomes[1])
        XCTAssertEqual(final.project.revision, 2)
        let loaded = try await repository.loadProject(id: command.projectID)
        XCTAssertEqual(loaded.document.appliedOperations.filter {
            $0.operationID == operationID
        }.count, 1)
    }

    func testDifferentConcurrentOperationsWithSameRevisionOnlyCommitOnce() async throws {
        let repository = InMemoryNovelProjectRepository()
        let module = DefaultNovelCreation(repository: repository)
        let command = NovelTestFixtures.createCommand()
        _ = try await module.perform(.createProject(command))
        let snapshot = try await projectSnapshot(module, id: command.projectID)
        let firstAction = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                projectRevision: snapshot.project.revision
            ),
            projectID: command.projectID,
            name: "One"
        ))
        let secondAction = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                projectRevision: snapshot.project.revision
            ),
            projectID: command.projectID,
            name: "Two"
        ))

        let firstTask = Task { await capture { try await module.perform(firstAction) } }
        let secondTask = Task { await capture { try await module.perform(secondAction) } }
        let results = await [firstTask.value, secondTask.value]
        let successCount = results.filter {
            if case .success = $0 { return true }
            return false
        }.count

        XCTAssertEqual(successCount, 1)
        let final = try await projectSnapshot(module, id: command.projectID)
        XCTAssertEqual(final.project.revision, 2)
    }

    func testSuspendedCommitJoinsSameOperationKeepsOldSnapshotAndRejectsDifferentOperation() async throws {
        let repository = SuspendingNovelProjectRepository()
        let module = DefaultNovelCreation(repository: repository)
        let command = NovelTestFixtures.createCommand()
        _ = try await module.perform(.createProject(command))
        let before = try await projectSnapshot(module, id: command.projectID)
        let operationID = NovelOperationID()
        let action = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                projectRevision: before.project.revision
            ),
            projectID: command.projectID,
            name: "After Commit"
        ))
        await repository.pauseNextCommit()

        let first = Task { try await module.perform(action) }
        await repository.waitUntilCommitPersisted()
        let sameOperation = Task { try await module.perform(action) }
        let during = try await projectSnapshot(module, id: command.projectID)
        guard case .projects(let summariesDuring) = try await module.snapshot(.projects) else {
            return XCTFail("Expected project summaries")
        }
        let competing = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: NovelOperationID(),
                projectRevision: before.project.revision
            ),
            projectID: command.projectID,
            name: "Must Be Busy"
        ))
        await NovelXCTAssertThrowsErrorAsync(try await module.perform(competing)) { error in
            XCTAssertEqual(error as? NovelError, .projectBusy(command.projectID))
        }
        let wrongKind = NovelTestFixtures.materialAction(
            document: try await repository.loadProject(id: command.projectID).document,
            operationID: operationID
        )
        await NovelXCTAssertThrowsErrorAsync(try await module.perform(wrongKind)) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(operationID))
        }
        let changedPayload = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                projectRevision: before.project.revision
            ),
            projectID: command.projectID,
            name: "Different Payload"
        ))
        await NovelXCTAssertThrowsErrorAsync(try await module.perform(changedPayload)) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(operationID))
        }

        XCTAssertEqual(during, before)
        XCTAssertEqual(
            summariesDuring.first(where: { $0.id == command.projectID })?.revision,
            before.project.revision
        )
        await repository.resumeCommit()
        let outcomes = try await [first.value, sameOperation.value]
        let after = try await projectSnapshot(module, id: command.projectID)
        let commitCount = await repository.commitCount()

        XCTAssertEqual(outcomes[0], outcomes[1])
        XCTAssertEqual(after.project.name, "After Commit")
        XCTAssertEqual(commitCount, 1)
    }

    func testSameOperationIDCanRunConcurrentlyInDifferentProjects() async throws {
        let repository = SuspendingNovelProjectRepository()
        let module = DefaultNovelCreation(repository: repository)
        let operationID = NovelOperationID()
        let first = NovelTestFixtures.createCommand(operationID: operationID)
        let second = NovelTestFixtures.createCommand(
            operationID: operationID,
            name: "Second"
        )
        await repository.pauseNextCreate()

        let firstTask = Task { try await module.perform(.createProject(first)) }
        await repository.waitUntilCreatePersisted()
        let secondOutcome = try await module.perform(.createProject(second))
        await repository.resumeCreate()
        let firstOutcome = try await firstTask.value

        XCTAssertEqual(
            firstOutcome,
            .projectCreated(projectID: first.projectID, branchID: first.branchID)
        )
        XCTAssertEqual(
            secondOutcome,
            .projectCreated(projectID: second.projectID, branchID: second.branchID)
        )
    }

    func testLateOldReadCannotDowngradeNewerCommittedCache() async throws {
        let repository = SuspendingNovelProjectRepository()
        let creator = DefaultNovelCreation(repository: repository)
        let command = NovelTestFixtures.createCommand()
        _ = try await creator.perform(.createProject(command))
        let module = DefaultNovelCreation(repository: repository)
        await repository.pauseNextLoad()

        let slowSnapshot = Task {
            try await module.snapshot(.project(command.projectID))
        }
        await repository.waitUntilLoadCaptured()
        let action = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: NovelOperationID(),
                projectRevision: 1
            ),
            projectID: command.projectID,
            name: "Newer"
        ))
        _ = try await module.perform(action)
        await repository.resumeLoad()

        guard case .project(let resumedSnapshot) = try await slowSnapshot.value else {
            return XCTFail("Expected project snapshot")
        }
        let finalSnapshot = try await projectSnapshot(module, id: command.projectID)
        XCTAssertEqual(resumedSnapshot.project.revision, 2)
        XCTAssertEqual(resumedSnapshot.project.name, "Newer")
        XCTAssertEqual(finalSnapshot, resumedSnapshot)
    }

    func testDurablyCreatedProjectStaysHiddenUntilRepositoryReturns() async throws {
        let repository = SuspendingNovelProjectRepository()
        let module = DefaultNovelCreation(repository: repository)
        let command = NovelTestFixtures.createCommand()
        await repository.pauseNextCreate()

        let creation = Task { try await module.perform(.createProject(command)) }
        await repository.waitUntilCreatePersisted()
        guard case .projects(let summariesDuring) = try await module.snapshot(.projects) else {
            return XCTFail("Expected project summaries")
        }
        XCTAssertTrue(summariesDuring.isEmpty)
        await NovelXCTAssertThrowsErrorAsync(
            try await module.snapshot(.project(command.projectID))
        ) { error in
            XCTAssertEqual(error as? NovelError, .projectNotFound(command.projectID))
        }
        let durablyLoaded = try await repository.loadProject(id: command.projectID)
        XCTAssertEqual(durablyLoaded.document.project.id, command.projectID)

        await repository.resumeCreate()
        _ = try await creation.value
        let visible = try await projectSnapshot(module, id: command.projectID)
        XCTAssertEqual(visible.project.id, command.projectID)
    }

    func testRepositoryFailureDoesNotPublishNextSnapshotOrLedger() async throws {
        let repository = InMemoryNovelProjectRepository()
        let module = DefaultNovelCreation(repository: repository)
        let command = NovelTestFixtures.createCommand()
        _ = try await module.perform(.createProject(command))
        let before = try await projectSnapshot(module, id: command.projectID)
        let action = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                projectRevision: before.project.revision
            ),
            projectID: command.projectID,
            name: "Must Not Publish"
        ))
        await repository.failNextWrite()

        await NovelXCTAssertThrowsErrorAsync(try await module.perform(action))
        let after = try await projectSnapshot(module, id: command.projectID)
        let stored = try await repository.loadProject(id: command.projectID)

        XCTAssertEqual(after, before)
        XCTAssertEqual(stored.document.project.name, before.project.name)
        XCTAssertEqual(stored.document.appliedOperations.count, 1)
    }

    func testRestartedModuleReplaysPersistedOperation() async throws {
        let repository = InMemoryNovelProjectRepository()
        let firstModule = DefaultNovelCreation(repository: repository)
        let command = NovelTestFixtures.createCommand()
        _ = try await firstModule.perform(.createProject(command))
        let before = try await projectSnapshot(firstModule, id: command.projectID)
        let action = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                projectRevision: before.project.revision
            ),
            projectID: command.projectID,
            name: "Persisted Rename"
        ))
        let original = try await firstModule.perform(action)

        let restarted = DefaultNovelCreation(repository: repository)
        let replay = try await restarted.perform(action)
        let final = try await projectSnapshot(restarted, id: command.projectID)

        XCTAssertEqual(replay, original)
        XCTAssertEqual(final.project.revision, 2)
        XCTAssertEqual(final.project.name, "Persisted Rename")
    }

    func testFileBackedModuleCreatesAndReloadsAProject() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let command = NovelTestFixtures.createCommand()
        let module = DefaultNovelCreation(
            repository: NovelFileProjectRepository(rootDirectory: root)
        )

        let outcome = try await module.perform(.createProject(command))
        let restarted = DefaultNovelCreation(
            repository: NovelFileProjectRepository(rootDirectory: root)
        )
        let snapshot = try await projectSnapshot(restarted, id: command.projectID)

        XCTAssertEqual(
            outcome,
            .projectCreated(projectID: command.projectID, branchID: command.branchID)
        )
        XCTAssertEqual(snapshot.project.id, command.projectID)
        XCTAssertEqual(snapshot.project.name, command.name)
        XCTAssertEqual(snapshot.branches.map(\.id), [command.branchID])
        XCTAssertEqual(snapshot.sessions.map(\.id), [command.sessionID])
    }

    func testDurableInstallErrorReconcilesCachedAndRestartedSnapshots() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let command = NovelTestFixtures.createCommand()
        let baseRepository = NovelFileProjectRepository(rootDirectory: root)
        _ = try await baseRepository.createProject(
            NovelReducer.createProject(command).document
        )
        let crashingRepository = NovelFileProjectRepository(
            rootDirectory: root,
            failingStages: [.afterPrimaryInstallBeforeIndex]
        )
        let module = DefaultNovelCreation(repository: crashingRepository)
        let before = try await projectSnapshot(module, id: command.projectID)
        let operationID = NovelOperationID()
        let action = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                projectRevision: before.project.revision
            ),
            projectID: command.projectID,
            name: "Durable Rename"
        ))

        await NovelXCTAssertThrowsErrorAsync(try await module.perform(action)) { error in
            XCTAssertEqual(error as? NovelError, .storageIndeterminate(command.projectID))
        }

        let replay = try await module.perform(action)
        let reconciled = try await projectSnapshot(module, id: command.projectID)
        guard case .projects(let summaries) = try await module.snapshot(.projects) else {
            return XCTFail("Expected project summaries")
        }
        let restarted = DefaultNovelCreation(
            repository: NovelFileProjectRepository(rootDirectory: root)
        )
        let restartedSnapshot = try await projectSnapshot(restarted, id: command.projectID)
        let stored = try await baseRepository.loadProject(id: command.projectID)

        XCTAssertEqual(reconciled.project.name, "Durable Rename")
        XCTAssertEqual(reconciled, restartedSnapshot)
        XCTAssertEqual(summaries.first(where: { $0.id == command.projectID })?.revision, 2)
        XCTAssertEqual(
            replay,
            .projectRenamed(projectID: command.projectID, revision: 2)
        )
        XCTAssertEqual(
            stored.document.appliedOperations.filter { $0.operationID == operationID }.count,
            1
        )
    }

    func testDegradedCommitFailureReconcilesCachedProjectToReadOnlyPrevious() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        let secondAction = NovelTestFixtures.renameAction(
            document: first,
            name: "Second"
        )
        let second = try NovelReducer.apply(secondAction, to: first).document
        _ = try await repository.commitProject(second, expectedRevision: first.project.revision)
        let module = DefaultNovelCreation(repository: repository)
        let cached = try await projectSnapshot(module, id: first.project.id)
        XCTAssertEqual(cached.project.revision, 2)
        let primary = root.appendingPathComponent("projects", isDirectory: true)
            .appendingPathComponent("\(first.project.id.description).json")
        try Data("corrupt".utf8).write(to: primary, options: [.atomic])
        let mutation = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: NovelOperationID(),
                projectRevision: cached.project.revision
            ),
            projectID: first.project.id,
            name: "Must Stay Read Only"
        ))

        await NovelXCTAssertThrowsErrorAsync(try await module.perform(mutation)) { error in
            XCTAssertEqual(error as? NovelError, .degradedReadOnly(projectID: first.project.id))
        }
        let reconciled = try await projectSnapshot(module, id: first.project.id)
        guard case .projects(let summaries) = try await module.snapshot(.projects) else {
            return XCTFail("Expected project summaries")
        }
        XCTAssertEqual(reconciled.project.revision, 1)
        guard case .degradedPrevious = reconciled.access else {
            return XCTFail("Expected the previous project in degraded read-only mode")
        }
        let summary = summaries.first(where: { $0.id == first.project.id })
        XCTAssertEqual(summary?.revision, reconciled.project.revision)
        XCTAssertEqual(summary?.isDegraded, true)
        let retry = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: NovelOperationID(),
                projectRevision: reconciled.project.revision
            ),
            projectID: first.project.id,
            name: "Still Blocked"
        ))
        await NovelXCTAssertThrowsErrorAsync(try await module.perform(retry)) { error in
            XCTAssertEqual(error as? NovelError, .degradedReadOnly(projectID: first.project.id))
        }
    }

    func testDegradedPreviousCanReplayButNotConflictWithPersistedOperation() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let first = try NovelTestFixtures.document()
        _ = try await repository.createProject(first)
        let operationID = NovelOperationID()
        let replayedAction = NovelTestFixtures.renameAction(
            document: first,
            operationID: operationID,
            name: "Replay Me"
        )
        let secondResult = try NovelReducer.apply(replayedAction, to: first)
        _ = try await repository.commitProject(
            secondResult.document,
            expectedRevision: first.project.revision
        )
        let thirdAction = NovelTestFixtures.renameAction(
            document: secondResult.document,
            operationID: NovelOperationID(),
            name: "Corrupt Me"
        )
        let third = try NovelReducer.apply(thirdAction, to: secondResult.document).document
        _ = try await repository.commitProject(
            third,
            expectedRevision: secondResult.document.project.revision
        )
        let primary = root.appendingPathComponent("projects", isDirectory: true)
            .appendingPathComponent("\(first.project.id.description).json")
        try Data("corrupt".utf8).write(to: primary, options: [.atomic])
        let module = DefaultNovelCreation(repository: repository)

        let replay = try await module.perform(replayedAction)
        XCTAssertEqual(replay, secondResult.outcome)
        let conflicting = NovelAction.renameProject(NovelRenameProjectCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                projectRevision: 1
            ),
            projectID: first.project.id,
            name: "Conflict"
        ))
        await NovelXCTAssertThrowsErrorAsync(try await module.perform(conflicting)) { error in
            XCTAssertEqual(error as? NovelError, .idempotencyConflict(operationID))
        }
    }

    func testDefaultModuleReportsGenerationUnavailableWithoutAModelAdapter() async throws {
        let module = DefaultNovelCreation(repository: InMemoryNovelProjectRepository())
        let command = NovelTestFixtures.createCommand()
        _ = try await module.perform(.createProject(command))
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: command.projectID,
            branchID: command.branchID,
            kind: .prose,
            mode: .writeProse,
            granularity: .wholeChapter,
            userText: "Write",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: NovelCandidateID(),
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            expectedProjectRevision: 1,
            expectedConfigRevision: 1,
            expectedBranchHeadRevision: 0
        )

        await NovelXCTAssertThrowsErrorAsync(try await module.start(request)) { error in
            XCTAssertEqual(error as? NovelError, .generationUnavailable)
        }
    }

    private func projectSnapshot(
        _ module: DefaultNovelCreation,
        id: NovelProjectID
    ) async throws -> NovelProjectSnapshot {
        guard case .project(let snapshot) = try await module.snapshot(.project(id)) else {
            throw XCTSkip("Unexpected snapshot kind")
        }
        return snapshot
    }
}

private actor SuspendingNovelProjectRepository: NovelProjectPersisting {
    private let base = InMemoryNovelProjectRepository()
    private var shouldPauseCreate = false
    private var shouldPauseCommit = false
    private var createPersisted = false
    private var commitPersisted = false
    private var loadCaptured = false
    private var shouldPauseLoad = false
    private var createWaiters: [CheckedContinuation<Void, Never>] = []
    private var commitWaiters: [CheckedContinuation<Void, Never>] = []
    private var loadWaiters: [CheckedContinuation<Void, Never>] = []
    private var createRelease: CheckedContinuation<Void, Never>?
    private var commitRelease: CheckedContinuation<Void, Never>?
    private var loadRelease: CheckedContinuation<Void, Never>?
    private var completedCommitCount = 0

    func pauseNextCreate() {
        shouldPauseCreate = true
        createPersisted = false
    }

    func pauseNextCommit() {
        shouldPauseCommit = true
        commitPersisted = false
    }

    func pauseNextLoad() {
        shouldPauseLoad = true
        loadCaptured = false
    }

    func waitUntilCreatePersisted() async {
        guard !createPersisted else { return }
        await withCheckedContinuation { createWaiters.append($0) }
    }

    func waitUntilCommitPersisted() async {
        guard !commitPersisted else { return }
        await withCheckedContinuation { commitWaiters.append($0) }
    }

    func waitUntilLoadCaptured() async {
        guard !loadCaptured else { return }
        await withCheckedContinuation { loadWaiters.append($0) }
    }

    func resumeCreate() {
        createRelease?.resume()
        createRelease = nil
    }

    func resumeCommit() {
        commitRelease?.resume()
        commitRelease = nil
    }

    func resumeLoad() {
        loadRelease?.resume()
        loadRelease = nil
    }

    func commitCount() -> Int { completedCommitCount }

    func listProjects() async throws -> [NovelProjectSummary] {
        try await base.listProjects()
    }

    func loadProject(id: NovelProjectID) async throws -> NovelLoadedProject {
        let loaded = try await base.loadProject(id: id)
        if shouldPauseLoad {
            shouldPauseLoad = false
            loadCaptured = true
            loadWaiters.forEach { $0.resume() }
            loadWaiters.removeAll()
            await withCheckedContinuation { loadRelease = $0 }
        }
        return loaded
    }

    func createProject(_ document: NovelProjectDocumentV1) async throws -> NovelLoadedProject {
        let loaded = try await base.createProject(document)
        if shouldPauseCreate {
            shouldPauseCreate = false
            createPersisted = true
            createWaiters.forEach { $0.resume() }
            createWaiters.removeAll()
            await withCheckedContinuation { createRelease = $0 }
        }
        return loaded
    }

    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64,
        authorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelLoadedProject {
        let loaded = try await base.commitProject(
            document,
            expectedRevision: expectedRevision,
            authorization: authorization
        )
        completedCommitCount += 1
        if shouldPauseCommit {
            shouldPauseCommit = false
            commitPersisted = true
            commitWaiters.forEach { $0.resume() }
            commitWaiters.removeAll()
            await withCheckedContinuation { commitRelease = $0 }
        }
        return loaded
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

private func capture<T: Sendable>(
    _ operation: @Sendable () async throws -> T
) async -> Result<T, Error> {
    do {
        return .success(try await operation())
    } catch {
        return .failure(error)
    }
}
