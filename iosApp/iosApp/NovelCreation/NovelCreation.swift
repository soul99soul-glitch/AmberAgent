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
    /// 多章代笔批进度 sidecar（跨进程恢复）；默认实现为空，文件仓覆盖。
    func loadGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelGhostwriteBatchProgressRecord?
    func saveGhostwriteBatchProgress(_ record: NovelGhostwriteBatchProgressRecord) async throws
    func removeGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws
}

extension NovelProjectPersisting {
    func loadGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelGhostwriteBatchProgressRecord? {
        _ = projectID
        _ = branchID
        return nil
    }

    func saveGhostwriteBatchProgress(_ record: NovelGhostwriteBatchProgressRecord) async throws {
        _ = record
    }

    func removeGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws {
        _ = projectID
        _ = branchID
    }

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
    let durableRunStore: IOSDurableRunStore?
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
    var recoveringGenerationProjectID: NovelProjectID?
    var recoveredGenerationProjectIDs: Set<NovelProjectID> = []
    var isReconcilingLifecycleOperations = false

    init(
        repository: any NovelProjectPersisting,
        modelRunner: any NovelModelRunning = UnavailableNovelModelAdapter(),
        generationPolicy: NovelGenerationPolicy = .standard,
        // 「连续无输出」阈值,不是总耗时上限:任何有效增量都会刷新计时。
        //
        // 2026-07-25 真机取证:deepseek-v4-pro 这类推理模型在思考阶段可能长时间不产出
        // 正文。适配层会把非空 reasoning 映射成只刷新心跳的 activity 事件；它不会
        // 混入结构化 JSON。原值 60s 仍会对完全没有任何活动的慢启动模型过于激进。
        //
        // 提到 240s:对推理模型给足思考空间,同时仍能在真正卡死(连续 4 分钟一个字
        // 都没有)时收口。这是放宽「判定卡死的耐心」,不是给模型的产出设上限。
        factRequestTimeout: TimeInterval = 240,
        polishAssessmentTimeout: TimeInterval = 20,
        defaultModelPolicy: @escaping @Sendable (NovelModelRole) -> NovelProjectModelPolicy = { _ in .global },
        durableRunStore: IOSDurableRunStore? = nil,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.repository = repository
        self.modelRunner = modelRunner
        self.durableRunStore = durableRunStore
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

    func distillDiscussionArchive(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        chapterID: NovelChapterID?
    ) async throws -> NovelDiscussionArchiveDraft {
        try await recoverGenerationStateIfNeeded(requiredProjectID: projectID)
        let loaded = try await loadCommittedProject(id: projectID)
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: projectID)
        }
        guard let branch = loaded.document.branches.first(where: {
            $0.id == branchID && $0.lifecycle == .active
        }) else {
            throw NovelError.branchNotFound(branchID)
        }
        guard branch.activeRunID == nil,
              branch.syncStatus == .synchronized else {
            throw NovelError.projectBusy(projectID)
        }
        guard let session = loaded.document.sessions.first(where: {
            $0.id == branch.sessionID && $0.branchID == branch.id
        }) else {
            throw NovelError.sessionNotFound(branch.sessionID)
        }
        if let chapterID,
           !loaded.document.chapters.contains(where: { $0.id == chapterID }) {
            throw NovelError.invalidInput("Discussion archive references a missing chapter.")
        }

        let previousSequence: Int64 = switch session.archiveCursor {
        case .through(let sequence): sequence
        case .empty, nil: -1
        }
        let discussionMessages = session.messages.filter {
            $0.sequence > previousSequence &&
                $0.mode == .discussPlan &&
                ($0.kind == .userInput || $0.kind == .discussion)
        }
        guard let throughSequence = discussionMessages.last?.sequence else {
            throw NovelError.invalidInput("当前没有可归档的新讨论。")
        }

        let effectiveMaterials = try NovelMaterialResolver.effectiveRevisions(
            document: loaded.document,
            branch: branch
        )
        let materialLines = effectiveMaterials.map {
            "\($0.material.id.description) | \($0.revision.title)"
        }
        let messageLines = discussionMessages.map { message in
            let role = switch message.role {
            case .user: "USER"
            case .assistant: "ASSISTANT"
            case .system: "SYSTEM"
            }
            return "[\(message.sequence)] \(role)\n\(message.content)"
        }
        let discussionInput = "AVAILABLE MATERIALS\n" +
            (materialLines.isEmpty ? "(none)" : materialLines.joined(separator: "\n")) +
            "\n\nDISCUSSION\n" + messageLines.joined(separator: "\n\n")

        let executor = NovelStructuredModelExecutor(modelRunner: modelRunner)
        let preparation = try await executor.prepare(
            modelPolicy: modelPolicy(for: .creation, in: loaded.document),
            taskKind: .discussionArchive,
            requestedInputBudgetTokens: 16_000
        )
        let estimatedTokens = max(1, (discussionInput.utf8.count + 3) / 4)
        guard estimatedTokens <= preparation.effectiveInputBudgetTokens else {
            throw NovelError.injectionBudgetExceeded(
                required: estimatedTokens,
                limit: preparation.effectiveInputBudgetTokens,
                items: [NovelInjectionBudgetItem(
                    label: "Discussion since last archive",
                    estimatedTokens: estimatedTokens
                )]
            )
        }
        let request = NovelStructuredModelExecutionRequest(
            runID: NovelRunID(),
            modelPolicy: preparation.modelPolicy,
            task: .discussionArchive(discussion: discussionInput)
        )
        let invocation = try executor.prepareInvocation(request, preparation: preparation)
        let evidence = try await executor.executePrepared(
            invocation,
            noOutputTimeout: factRequestTimeout
        )
        guard case .discussionArchive(let archive) = evidence.output else {
            throw NovelStructuredModelExecutionFailure(
                code: "invalid_structured_output",
                message: "讨论归档返回了错误的结构。",
                isRetryable: true
            )
        }

        let availableMaterialIDs = Set(effectiveMaterials.map(\.material.id))
        let decisions = try archive.decisions.map { item in
            let relatedMaterialID: NovelMaterialID?
            if let text = item.relatedMaterialID {
                guard let uuid = UUID(uuidString: text) else {
                    throw NovelStructuredModelExecutionFailure(
                        code: "invalid_structured_output",
                        message: "讨论归档引用了无效的资料 ID。",
                        isRetryable: true
                    )
                }
                let materialID = NovelMaterialID(uuid)
                guard availableMaterialIDs.contains(materialID) else {
                    throw NovelStructuredModelExecutionFailure(
                        code: "invalid_structured_output",
                        message: "讨论归档引用了当前项目中不存在的资料。",
                        isRetryable: true
                    )
                }
                relatedMaterialID = materialID
            } else {
                relatedMaterialID = nil
            }
            return NovelDiscussionArchiveDraftDecision(
                id: UUID(),
                topic: item.topic,
                decision: item.decision,
                relatedMaterialID: relatedMaterialID
            )
        }
        return NovelDiscussionArchiveDraft(
            projectID: projectID,
            branchID: branchID,
            sessionID: session.id,
            throughSequence: throughSequence,
            chapterID: chapterID,
            summary: archive.summary,
            decisions: decisions
        )
    }

    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        if case .projects = scope {
            // The project list is a read-only inventory. Waiting for every project's
            // crash-recovery scan here makes one large or unfinished project block the
            // whole entry screen. Opening a project and every mutation still run the
            // recovery barrier below before reading or writing project contents.
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
        }
        if case .projectImportPreview(let data) = scope {
            let decoded = try NovelProjectPackageCodec.decode(data)
            let sourceProjectID = decoded.document.project.id
            let summaries: [NovelProjectSummary]
            do {
                try await recoverGenerationStateIfNeeded(
                    requiredProjectID: sourceProjectID,
                    allowsMissingRequiredProject: true
                )
                summaries = try await repository.listProjects()
            } catch {
                let recoveryError = error
                let currentSummaries = try await repository.listProjects()
                guard currentSummaries.contains(where: {
                    $0.id == sourceProjectID && $0.loadError != nil
                }) else {
                    throw recoveryError
                }
                summaries = currentSummaries
            }
            let existing = summaries.first(where: { $0.id == decoded.document.project.id })
            return .projectImportPreview(NovelProjectImportPreview(
                sourceProjectID: sourceProjectID,
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
        let recoveryProjectID: NovelProjectID? = switch scope {
        case .project(let projectID), .projectPackage(let projectID), .workspace(let projectID):
            projectID
        case .branch(let projectID, _), .branchMarkdown(let projectID, _):
            projectID
        case .injectionPreview(let request):
            request.projectID
        case .projects, .projectImportPreview:
            nil
        }
        try await recoverGenerationStateIfNeeded(requiredProjectID: recoveryProjectID)
        switch scope {
        case .projects:
            throw NovelError.invalidInput("The project list was not handled.")

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
        case .workspace(let projectID):
            return .workspace(try await exportWorkspace(projectID: projectID))
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
        do {
            try await recoverGenerationStateIfNeeded(
                requiredProjectID: action.projectID,
                allowsMissingRequiredProject: action.isProjectCreation ||
                    action.operationKind == .deleteProject,
                allowsConcurrentMutationOnRequiredProject: true
            )
        } catch {
            let recoveryError = error
            guard case .deleteProject = action else { throw recoveryError }
            let summaries = try await repository.listProjects()
            guard summaries.contains(where: {
                $0.id == action.projectID && $0.loadError != nil
            }) else {
                throw recoveryError
            }
            // Deleting an explicitly confirmed unreadable project does not need
            // its document to pass generation recovery. The deletion lifecycle
            // below still owns tombstoning, idempotency, and artifact cleanup.
        }
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
            publishMutation(action)
            return outcome
        } catch {
            if requiresRepositoryReconciliation(error) {
                frozenProjectIDs.insert(action.projectID)
            }
            clearInFlight(action)
            throw error
        }
    }

    // MARK: - Mutation notifications

    private var mutationContinuations: [UUID: AsyncStream<NovelProjectMutationEvent>.Continuation] = [:]

    func mutationEvents() -> AsyncStream<NovelProjectMutationEvent> {
        let id = UUID()
        return AsyncStream { continuation in
            mutationContinuations[id] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { await self?.removeMutationContinuation(id) }
            }
        }
    }

    private func removeMutationContinuation(_ id: UUID) {
        mutationContinuations[id] = nil
    }

    private func publishMutation(_ action: NovelAction) {
        guard !mutationContinuations.isEmpty else { return }
        let event = NovelProjectMutationEvent(
            projectID: action.projectID,
            operationID: action.context.operationID
        )
        for continuation in mutationContinuations.values {
            continuation.yield(event)
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

    /// Protocol surface used by ViewModel Stop: only fact-sync mutations.
    /// Does not cancel polish adopt — that belongs to background interrupt.
    func cancelInFlightBackgroundMutations(projectID: NovelProjectID) async {
        _ = cancelInFlightBackgroundMutationTasks(
            projectID: projectID,
            includePolishAdoption: false
        )
    }

    /// Cancel background mutations for a project. Background interrupt passes
    /// `includePolishAdoption: true` so adopted polish work can wind down too.
    @discardableResult
    func cancelInFlightBackgroundMutationTasks(
        projectID: NovelProjectID,
        includePolishAdoption: Bool = true
    ) -> [Task<NovelOutcome, Error>] {
        let tasks: [Task<NovelOutcome, Error>] = inFlightByOperation.values.compactMap {
            mutation -> Task<NovelOutcome, Error>? in
            guard mutation.projectID == projectID else { return nil }
            switch mutation.kind {
            case .adoptPolishCandidate:
                guard includePolishAdoption else { return nil }
            case .syncManualEdits, .retryPending:
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
        try await recoverGenerationStateIfNeeded(requiredProjectID: request.projectID)
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
        case .saveManualEdit(let command):
            // Contract v1.1 D-B: text + its plot module commit atomically.
            return try await executeSaveManualEditWithPlot(command)
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

    func loadGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelGhostwriteBatchProgressRecord? {
        try await repository.loadGhostwriteBatchProgress(
            projectID: projectID,
            branchID: branchID
        )
    }

    func saveGhostwriteBatchProgress(_ record: NovelGhostwriteBatchProgressRecord) async throws {
        try await repository.saveGhostwriteBatchProgress(record)
    }

    func removeGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws {
        try await repository.removeGhostwriteBatchProgress(
            projectID: projectID,
            branchID: branchID
        )
    }
}

extension NovelProjectSummary {
    static func listOrder(_ lhs: Self, _ rhs: Self) -> Bool {
        if lhs.updatedAt != rhs.updatedAt { return lhs.updatedAt > rhs.updatedAt }
        return lhs.id.description < rhs.id.description
    }
}
