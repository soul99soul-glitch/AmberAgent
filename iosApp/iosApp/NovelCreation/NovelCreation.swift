import Foundation

protocol NovelProjectPersisting: AnyObject, Sendable {
    func listProjects() async throws -> [NovelProjectSummary]
    func loadProject(id: NovelProjectID) async throws -> NovelLoadedProject
    func createProject(_ document: NovelProjectDocumentV1) async throws -> NovelLoadedProject
    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64,
        authorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelLoadedProject
    func replaceProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64
    ) async throws -> NovelLoadedProject
    func deleteProject(id: NovelProjectID, expectedRevision: Int64) async throws
    func discardUnavailableProject(id: NovelProjectID) async throws
    func lifecycleOperation(
        projectID: NovelProjectID,
        operationID: NovelOperationID
    ) async throws -> NovelProjectLifecycleOperationRecord?
    func lifecycleOperationIDs(projectID: NovelProjectID) async throws -> Set<NovelOperationID>
    func listPendingLifecycleOperations() async throws -> [NovelProjectLifecycleOperationRecord]
    func blockedLifecycleProjectIDs() async throws -> Set<NovelProjectID>
    func writeLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) async throws
    func removeLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) async throws
    func restorePreviousProject(
        id: NovelProjectID,
        expectedDocumentSHA256: String
    ) async throws -> NovelLoadedProject
    func listRecoverySidecars() async throws -> [NovelRecoverySidecarV1]
    func writeRecoverySidecar(_ sidecar: NovelRecoverySidecarV1) async throws
    func removeRecoverySidecar(projectID: NovelProjectID, runID: NovelRunID) async throws
}

extension NovelProjectPersisting {
    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64
    ) async throws -> NovelLoadedProject {
        try await commitProject(
            document,
            expectedRevision: expectedRevision,
            authorization: nil
        )
    }

    func replaceProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64
    ) async throws -> NovelLoadedProject {
        throw NovelError.repositoryFailure("This novel repository cannot replace projects.")
    }

    func deleteProject(id: NovelProjectID, expectedRevision: Int64) async throws {
        throw NovelError.repositoryFailure("This novel repository cannot delete projects.")
    }

    func discardUnavailableProject(id: NovelProjectID) async throws {
        throw NovelError.repositoryFailure("This novel repository cannot discard unavailable projects.")
    }

    func lifecycleOperation(
        projectID: NovelProjectID,
        operationID: NovelOperationID
    ) async throws -> NovelProjectLifecycleOperationRecord? {
        nil
    }

    func lifecycleOperationIDs(projectID: NovelProjectID) async throws -> Set<NovelOperationID> {
        []
    }

    func listPendingLifecycleOperations() async throws -> [NovelProjectLifecycleOperationRecord] {
        []
    }

    func blockedLifecycleProjectIDs() async throws -> Set<NovelProjectID> {
        []
    }

    func writeLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) async throws {
        throw NovelError.repositoryFailure("This novel repository cannot persist lifecycle operations.")
    }

    func removeLifecycleOperation(_ record: NovelProjectLifecycleOperationRecord) async throws {
        throw NovelError.repositoryFailure("This novel repository cannot remove lifecycle operations.")
    }
}

final class NovelRepositoryCommitAuthorization: @unchecked Sendable {
    private enum State: Equatable {
        case available
        case claimed
        case revoked
    }

    private let lock = NSLock()
    private let projectID: NovelProjectID
    private var state: State = .available

    init(projectID: NovelProjectID) {
        self.projectID = projectID
    }

    func revoke() {
        lock.withLock {
            guard state == .available else { return }
            state = .revoked
        }
    }

    func claim() throws {
        try lock.withLock {
            switch state {
            case .available:
                state = .claimed
            case .revoked:
                throw CancellationError()
            case .claimed:
                throw NovelError.storageIndeterminate(projectID)
            }
        }
    }

    var isRevoked: Bool {
        lock.withLock { state == .revoked }
    }
}

private final class NovelMutationJoinLatch: @unchecked Sendable {
    enum Resolution {
        case outcome(NovelOutcome)
        case failure(Error)
        case cancelled
    }

    private let lock = NSLock()
    private var resolution: Resolution?
    private var continuation: CheckedContinuation<Resolution, Never>?

    func resolve(_ proposed: Resolution) {
        let waiting: CheckedContinuation<Resolution, Never>? = lock.withLock {
            guard resolution == nil else { return nil }
            resolution = proposed
            defer { continuation = nil }
            return continuation
        }
        waiting?.resume(returning: proposed)
    }

    func wait() async -> Resolution {
        await withCheckedContinuation { waiting in
            let completed: Resolution? = lock.withLock {
                if let resolution { return resolution }
                continuation = waiting
                return nil
            }
            if let completed {
                waiting.resume(returning: completed)
            }
        }
    }
}

struct NovelGenerationStartReservation: Sendable {
    let projectID: NovelProjectID
    let operationID: NovelOperationID
    let payloadSHA256: String
    let task: Task<NovelRun, Error>
}

actor DefaultNovelCreation: NovelCreation {
    private struct InFlightKey: Hashable, Sendable {
        let projectID: NovelProjectID
        let operationID: NovelOperationID
    }

    private struct InFlightMutation: Sendable {
        let projectID: NovelProjectID
        let kind: NovelOperationKind
        let payloadSHA256: String
        let finalCommitAuthorization: NovelRepositoryCommitAuthorization?
        let task: Task<NovelOutcome, Error>
    }

    let repository: any NovelProjectPersisting
    let modelRunner: any NovelModelRunning
    let now: @Sendable () -> Date
    let generationPolicy: NovelGenerationPolicy
    let factRequestTimeout: TimeInterval
    let polishAssessmentTimeout: TimeInterval
    let defaultModelPolicy: @Sendable (NovelModelRole) -> NovelProjectModelPolicy
    var committedProjects: [NovelProjectID: NovelLoadedProject] = [:]
    private var inFlightByOperation: [InFlightKey: InFlightMutation] = [:]
    private var inFlightProjectIDs: Set<NovelProjectID> = []
    private var inFlightCreationProjectIDs: Set<NovelProjectID> = []
    var frozenProjectIDs: Set<NovelProjectID> = []
    var generationStartProjectIDs: Set<NovelProjectID> = []
    var generationStartReservations: [NovelRunID: NovelGenerationStartReservation] = [:]
    var generationStartRunIDsByProject: [NovelProjectID: NovelRunID] = [:]
    var preStartInterruptions: [NovelRunID: NovelCancelRunCommand] = [:]
    var generationWriteProjectIDs: Set<NovelProjectID> = []
    var generationRuntimes: [NovelRunID: NovelRunRuntime] = [:]
    var lifecycleReadProjectIDs: Set<NovelProjectID> = []
    var pendingLifecycleOperationsByProject: [
        NovelProjectID: Set<NovelOperationID>
    ] = [:]
    var blockedLifecycleProjectIDs: Set<NovelProjectID> = []
    var didRecoverGenerationState = false
    var isRecoveringGenerationState = false
    var isReconcilingLifecycleOperations = false

    init(
        repository: any NovelProjectPersisting,
        modelRunner: any NovelModelRunning = UnavailableNovelModelAdapter(),
        generationPolicy: NovelGenerationPolicy = .standard,
        factRequestTimeout: TimeInterval = 60,
        polishAssessmentTimeout: TimeInterval = 20,
        defaultModelPolicy: @escaping @Sendable (NovelModelRole) -> NovelProjectModelPolicy = { _ in .global },
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.repository = repository
        self.modelRunner = modelRunner
        self.generationPolicy = generationPolicy
        self.factRequestTimeout = max(0.01, factRequestTimeout)
        self.polishAssessmentTimeout = max(0.01, polishAssessmentTimeout)
        self.defaultModelPolicy = defaultModelPolicy
        self.now = now
    }

    func modelPolicy(
        for purpose: NovelModelRole,
        in document: NovelProjectDocumentV1
    ) -> NovelProjectModelPolicy {
        let configured = document.project.configuredModelPolicy(for: purpose)
        guard case .global = configured else { return configured }
        return defaultModelPolicy(purpose)
    }

    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        if case .projectImportPreview(let data) = scope {
            let decoded = try NovelProjectPackageCodec.decode(data)
            try await recoverGenerationStateIfNeeded()
            let summaries = try await repository.listProjects()
            let existing = summaries.first(where: { $0.id == decoded.document.project.id })
            return .projectImportPreview(NovelProjectImportPreview(
                sourceProjectID: decoded.document.project.id,
                projectName: decoded.document.project.name,
                projectRevision: decoded.document.project.revision,
                schemaVersion: decoded.document.schemaVersion,
                envelopeByteCount: data.count,
                projectByteCount: decoded.artifact.projectByteCount,
                projectSHA256: decoded.artifact.projectSHA256,
                runningRunCount: decoded.document.activeRuns.filter { $0.status == .running }.count,
                existingProject: existing
            ))
        }
        try await recoverGenerationStateIfNeeded()
        switch scope {
        case .projects:
            var summaries = try await repository.listProjects()
            summaries.removeAll { summary in
                inFlightCreationProjectIDs.contains(summary.id) &&
                    committedProjects[summary.id] == nil
            }
            for projectID in inFlightProjectIDs {
                guard let cached = committedProjects[projectID] else { continue }
                let replacement = NovelProjectSummary(
                    document: cached.document,
                    isDegraded: cached.access != .readWrite
                )
                if let index = summaries.firstIndex(where: { $0.id == projectID }) {
                    summaries[index] = replacement
                } else {
                    summaries.append(replacement)
                }
            }
            summaries.sort(by: NovelProjectSummary.listOrder)
            return .projects(summaries)

        case .project(let projectID):
            if inFlightCreationProjectIDs.contains(projectID),
               committedProjects[projectID] == nil {
                throw NovelError.projectNotFound(projectID)
            }
            let loaded = try await loadCommittedProject(id: projectID)
            return .project(NovelProjectSnapshot(loaded: loaded))

        case .branch(let projectID, let branchID):
            if inFlightCreationProjectIDs.contains(projectID),
               committedProjects[projectID] == nil {
                throw NovelError.projectNotFound(projectID)
            }
            let loaded = try await loadCommittedProject(id: projectID)
            return .branch(try makeBranchSnapshot(
                loaded: loaded,
                projectID: projectID,
                branchID: branchID
            ))
        case .projectPackage(let projectID):
            return .package(try await exportProjectPackage(projectID: projectID))
        case .branchMarkdown(let projectID, let branchID):
            return .markdown(try await exportBranchMarkdown(
                projectID: projectID,
                branchID: branchID
            ))
        case .injectionPreview(let request):
            return .injectionPreview(try await makeInjectionPreview(request))
        case .projectImportPreview:
            throw NovelError.invalidInput("The project import preview was not handled.")
        }
    }

    func perform(_ action: NovelAction) async throws -> NovelOutcome {
        let preparedImport: NovelPreparedProjectImport?
        if case .importProject(let command) = action {
            preparedImport = try NovelProjectLifecycle.prepareImport(command)
        } else {
            preparedImport = nil
        }
        try await recoverGenerationStateIfNeeded()
        guard !isReconcilingLifecycleOperations else {
            throw NovelError.projectBusy(action.projectID)
        }
        guard !blockedLifecycleProjectIDs.contains(action.projectID) else {
            throw NovelError.storageIndeterminate(action.projectID)
        }
        let payloadSHA256 = try action.canonicalPayloadSHA256()
        let inFlightKey = InFlightKey(
            projectID: action.projectID,
            operationID: action.context.operationID
        )
        if let existing = inFlightByOperation[inFlightKey] {
            guard existing.payloadSHA256 == payloadSHA256,
                  existing.kind == action.operationKind else {
                throw NovelError.idempotencyConflict(action.context.operationID)
            }
            return try await awaitJoinedMutationTask(existing.task)
        }
        if action.isRunCancellation {
            await waitForGenerationWrite(projectID: action.projectID)
            await waitForInFlightMutation(projectID: action.projectID)
        }
        if let pending = pendingLifecycleOperationsByProject[action.projectID],
           !pending.isEmpty {
            if pending.contains(action.context.operationID) {
                guard action.isProjectLifecycle else {
                    throw NovelError.idempotencyConflict(action.context.operationID)
                }
            } else {
                throw NovelError.projectBusy(action.projectID)
            }
        }
        guard !inFlightProjectIDs.contains(action.projectID),
              !generationStartProjectIDs.contains(action.projectID),
              !generationWriteProjectIDs.contains(action.projectID),
              !lifecycleReadProjectIDs.contains(action.projectID) else {
            throw NovelError.projectBusy(action.projectID)
        }

        let finalCommitAuthorization = action.operationKind == .adoptPolishCandidate
            ? NovelRepositoryCommitAuthorization(projectID: action.projectID)
            : nil
        let task = Task { [self] in
            try await execute(
                action,
                finalCommitAuthorization: finalCommitAuthorization,
                preparedImport: preparedImport
            )
        }
        let inFlight = InFlightMutation(
            projectID: action.projectID,
            kind: action.operationKind,
            payloadSHA256: payloadSHA256,
            finalCommitAuthorization: finalCommitAuthorization,
            task: task
        )
        inFlightByOperation[inFlightKey] = inFlight
        inFlightProjectIDs.insert(action.projectID)
        if action.isProjectCreation {
            inFlightCreationProjectIDs.insert(action.projectID)
        }

        do {
            let outcome = try await awaitMutationTask(
                task,
                finalCommitAuthorization: finalCommitAuthorization
            )
            clearInFlight(action)
            return outcome
        } catch {
            if requiresRepositoryReconciliation(error) {
                frozenProjectIDs.insert(action.projectID)
            }
            clearInFlight(action)
            throw error
        }
    }

    private func awaitMutationTask(
        _ task: Task<NovelOutcome, Error>,
        finalCommitAuthorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelOutcome {
        try await withTaskCancellationHandler {
            try await task.value
        } onCancel: {
            finalCommitAuthorization?.revoke()
            task.cancel()
        }
    }

    private func awaitJoinedMutationTask(
        _ task: Task<NovelOutcome, Error>
    ) async throws -> NovelOutcome {
        let latch = NovelMutationJoinLatch()
        Task {
            do {
                latch.resolve(.outcome(try await task.value))
            } catch {
                latch.resolve(.failure(error))
            }
        }
        let resolution = await withTaskCancellationHandler {
            await latch.wait()
        } onCancel: {
            latch.resolve(.cancelled)
        }
        switch resolution {
        case .outcome(let outcome):
            return outcome
        case .failure(let error):
            throw error
        case .cancelled:
            throw CancellationError()
        }
    }

    func cancelInFlightBackgroundMutations(
        projectID: NovelProjectID
    ) -> [Task<NovelOutcome, Error>] {
        let tasks: [Task<NovelOutcome, Error>] = inFlightByOperation.values.compactMap {
            mutation -> Task<NovelOutcome, Error>? in
            guard mutation.projectID == projectID else { return nil }
            switch mutation.kind {
            case .adoptPolishCandidate, .syncManualEdits, .retryPending:
                break
            default:
                return nil
            }
            mutation.finalCommitAuthorization?.revoke()
            return mutation.task
        }
        tasks.forEach { $0.cancel() }
        return tasks
    }

    func start(_ request: NovelRunRequest) async throws -> NovelRun {
        try await recoverGenerationStateIfNeeded()
        guard !isRecoveringGenerationState else {
            throw NovelError.projectBusy(request.projectID)
        }
        guard !blockedLifecycleProjectIDs.contains(request.projectID) else {
            throw NovelError.storageIndeterminate(request.projectID)
        }
        return try await startGeneration(request)
    }

    private func execute(
        _ action: NovelAction,
        finalCommitAuthorization: NovelRepositoryCommitAuthorization?,
        preparedImport: NovelPreparedProjectImport?
    ) async throws -> NovelOutcome {
        if !action.isProjectLifecycle,
           try await repository.lifecycleOperation(
               projectID: action.projectID,
               operationID: action.context.operationID
           ) != nil {
            throw NovelError.idempotencyConflict(action.context.operationID)
        }
        switch action {
        case .createProject(let command):
            return try await executeCreate(command)
        case .cancelRun(let command):
            return try await executeCancelRun(command)
        case .collectCandidate(let command):
            return try await executeCollectCandidate(command)
        case .syncManualEdits(let command):
            return try await executeSyncManualEdits(command)
        case .retryPending(let command):
            return try await executeRetryPending(command)
        case .adoptPolishCandidate(let command):
            guard let finalCommitAuthorization else {
                throw NovelError.storageIndeterminate(command.projectID)
            }
            return try await executeAdoptPolishCandidate(
                command,
                finalCommitAuthorization: finalCommitAuthorization
            )
        case .importProject(let command):
            guard let preparedImport else {
                throw NovelError.invalidPackage("The import was not prepared.")
            }
            return try await executeImportProject(command, prepared: preparedImport)
        case .restorePreviousProject(let command):
            return try await executeRestorePreviousProject(command)
        case .deleteProject(let command):
            return try await executeDeleteProject(command)
        default:
            return try await executeMutation(action)
        }
    }

    private func executeCreate(_ command: NovelCreateProjectCommand) async throws -> NovelOutcome {
        do {
            let loaded = try await loadCommittedProject(id: command.projectID)
            if let replay = try NovelReducer.replayOutcome(
                context: command.context,
                kind: .createProject,
                payloadSHA256: try command.canonicalPayloadSHA256(),
                in: loaded.document
            ) {
                return replay
            }
            throw NovelError.projectAlreadyExists(command.projectID)
        } catch NovelError.projectNotFound {
            // Expected absence is the creation guard.
        }

        let reduced = try NovelReducer.createProject(command)
        let loaded = try await repository.createProject(reduced.document)
        guard loaded.document == reduced.document else {
            throw NovelError.storageIndeterminate(command.projectID)
        }
        _ = try installLoadedProject(loaded, id: command.projectID, allowsRollback: false)
        return reduced.outcome
    }

    private func executeMutation(_ action: NovelAction) async throws -> NovelOutcome {
        let loaded = try await loadCommittedProject(id: action.projectID)
        if let replay = try NovelReducer.replayOutcome(
            context: action.context,
            kind: action.operationKind,
            payloadSHA256: try action.canonicalPayloadSHA256(),
            in: loaded.document
        ) {
            return replay
        }
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: action.projectID)
        }
        let reduced = try NovelReducer.apply(action, to: loaded.document)
        if reduced.document == loaded.document {
            return reduced.outcome
        }

        let committed = try await repository.commitProject(
            reduced.document,
            expectedRevision: loaded.document.project.revision
        )
        guard committed.document == reduced.document else {
            throw NovelError.storageIndeterminate(action.projectID)
        }
        _ = try installLoadedProject(committed, id: action.projectID, allowsRollback: false)
        return reduced.outcome
    }

    func loadCommittedProject(id: NovelProjectID) async throws -> NovelLoadedProject {
        let allowsRollback = frozenProjectIDs.contains(id)
        if let cached = committedProjects[id], !allowsRollback {
            return cached
        }
        let loaded = try await repository.loadProject(id: id)
        let installed = try installLoadedProject(
            loaded,
            id: id,
            allowsRollback: allowsRollback
        )
        if allowsRollback {
            frozenProjectIDs.remove(id)
        }
        return installed
    }

    func installLoadedProject(
        _ loaded: NovelLoadedProject,
        id: NovelProjectID,
        allowsRollback: Bool
    ) throws -> NovelLoadedProject {
        guard let current = committedProjects[id], !allowsRollback else {
            committedProjects[id] = loaded
            return loaded
        }
        if current.document.project.revision > loaded.document.project.revision {
            return current
        }
        if current.document.project.revision == loaded.document.project.revision,
           current.document != loaded.document {
            frozenProjectIDs.insert(id)
            throw NovelError.storageIndeterminate(id)
        }
        committedProjects[id] = loaded
        return loaded
    }

    private func makeBranchSnapshot(
        loaded: NovelLoadedProject,
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) throws -> NovelBranchSnapshot {
        let document = loaded.document
        guard let branch = document.branches.first(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        guard let session = document.sessions.first(where: { $0.id == branch.sessionID }) else {
            throw NovelError.sessionNotFound(branch.sessionID)
        }
        guard let state = document.stateSnapshots.first(where: {
            $0.id == branch.currentStateSnapshotID
        }) else {
            throw NovelError.stateSnapshotNotFound(branch.currentStateSnapshotID)
        }
        guard let checkpoint = document.checkpoints.first(where: {
            $0.id == branch.headCheckpointID
        }) else {
            throw NovelError.checkpointNotFound(branch.headCheckpointID)
        }
        return NovelBranchSnapshot(
            projectID: projectID,
            projectRevision: document.project.revision,
            configRevision: document.project.configRevision,
            branch: branch,
            session: session,
            headCheckpoint: checkpoint,
            currentState: state,
            chapterSelections: branch.workingChapterSelections,
            activeSettingProposals: document.activeSettingProposals(for: branchID),
            access: loaded.access
        )
    }

    private func clearInFlight(_ action: NovelAction) {
        inFlightByOperation[InFlightKey(
            projectID: action.projectID,
            operationID: action.context.operationID
        )] = nil
        inFlightProjectIDs.remove(action.projectID)
        if action.isProjectCreation {
            inFlightCreationProjectIDs.remove(action.projectID)
        }
    }

    func waitForInFlightMutation(projectID: NovelProjectID) async {
        while let entry = inFlightByOperation.first(where: {
            $0.value.projectID == projectID
        }) {
            do {
                _ = try await entry.value.task.value
            } catch {
                if requiresRepositoryReconciliation(error) {
                    frozenProjectIDs.insert(projectID)
                }
            }
            inFlightByOperation[entry.key] = nil
            inFlightProjectIDs.remove(projectID)
            if entry.value.kind == .createProject {
                inFlightCreationProjectIDs.remove(projectID)
            }
        }
    }

    func waitForGenerationWrite(projectID: NovelProjectID) async {
        while generationWriteProjectIDs.contains(projectID) {
            await Task.yield()
        }
    }

    func mutationIsInFlight(projectID: NovelProjectID) -> Bool {
        inFlightProjectIDs.contains(projectID)
    }

    func requiresRepositoryReconciliation(_ error: Error) -> Bool {
        guard let error = error as? NovelError else { return false }
        switch error {
        case .storageIndeterminate,
             .degradedReadOnly,
             .corruptedProject,
             .unsupportedSchema,
             .storageUnavailable,
             .staleProjectRevision,
             .projectNotFound:
            return true
        default:
            return false
        }
    }
}

extension NovelProjectSummary {
    static func listOrder(_ lhs: Self, _ rhs: Self) -> Bool {
        if lhs.updatedAt != rhs.updatedAt { return lhs.updatedAt > rhs.updatedAt }
        return lhs.id.description < rhs.id.description
    }
}
