import Foundation

struct NovelGenerationPolicy: Equatable, Sendable {
    let sidecarByteThreshold: Int
    let sidecarInterval: TimeInterval
    let chapterTailCharacterLimit: Int
    let maximumRecentSessionMessages: Int

    static let standard = NovelGenerationPolicy(
        sidecarByteThreshold: 8 * 1_024,
        sidecarInterval: 2,
        chapterTailCharacterLimit: 6_000,
        maximumRecentSessionMessages: 12
    )
}

actor UnavailableNovelModelAdapter: NovelModelRunning {
    func resolveModel(for policy: NovelProjectModelPolicy) async throws -> NovelResolvedModel {
        _ = policy
        throw NovelError.generationUnavailable
    }

    func start(_ request: NovelModelRequest) async throws -> AsyncStream<NovelModelEvent> {
        _ = request
        throw NovelError.generationUnavailable
    }

    func cancel(runID: NovelRunID) async {
        _ = runID
    }
}

enum NovelRunTerminalIntent: Equatable, Sendable {
    case completed
    case awaitingUser(NovelAskUserPrompt, preface: String)
    case interrupted(reason: NovelRunInterruptionReason, command: NovelCancelRunCommand?)
    case failed(NovelFailure)
}

enum NovelTerminalPersistenceState: Equatable, Sendable {
    case pending
    case persisting
    case blocked(NovelFailure)
}

struct NovelRunTerminalClaim: Equatable, Sendable {
    let intent: NovelRunTerminalIntent
    let partialContent: String
    let claimedAt: Date
    var persistenceState: NovelTerminalPersistenceState
}

private struct NovelTerminalCommit: Sendable {
    let outcome: NovelOutcome?
    let event: NovelRunEvent
}

private final class NovelBackgroundDeadlineGate: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Never>?
    private var isSignalled = false

    func wait() async {
        await withCheckedContinuation { continuation in
            lock.lock()
            if isSignalled {
                lock.unlock()
                continuation.resume()
            } else {
                self.continuation = continuation
                lock.unlock()
            }
        }
    }

    func signal() {
        lock.lock()
        guard !isSignalled else {
            lock.unlock()
            return
        }
        isSignalled = true
        let continuation = continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume()
    }
}

struct NovelRunRuntime: Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let kind: NovelRunKind
    let startProjectRevision: Int64
    var partialContent: String
    var usage: NovelModelUsage?
    var subscribers: [UUID: AsyncStream<NovelRunEvent>.Continuation]
    var modelTask: Task<Void, Never>?
    var sidecarSequence: Int64
    var lastSidecarAt: Date
    var lastSidecarByteCount: Int
    var terminalClaim: NovelRunTerminalClaim?
}

extension DefaultNovelCreation {
    func startGeneration(_ request: NovelRunRequest) async throws -> NovelRun {
        if let interruption = preStartInterruptions[request.id] {
            guard interruption.projectID == request.projectID else {
                throw NovelError.runNotFound(request.id)
            }
            preStartInterruptions[request.id] = nil
            throw CancellationError()
        }
        let payloadSHA256 = try request.canonicalPayloadSHA256()
        if let runtime = generationRuntimes[request.id],
           runtime.projectID != request.projectID {
            throw NovelError.invalidInput(
                "A live generation run ID belongs to another project."
            )
        }
        if let reservation = generationStartReservations[request.id] {
            guard reservation.projectID == request.projectID,
                  reservation.operationID == request.operationID,
                  reservation.payloadSHA256 == payloadSHA256 else {
                throw NovelError.idempotencyConflict(request.operationID)
            }
            _ = try await reservation.task.value
            return try await observeStartedRun(request)
        }
        if generationStartRunIDsByProject[request.projectID] != nil {
            throw NovelError.projectBusy(request.projectID)
        }
        if let pending = pendingLifecycleOperationsByProject[request.projectID],
           !pending.isEmpty {
            if pending.contains(request.operationID) {
                throw NovelError.idempotencyConflict(request.operationID)
            }
            throw NovelError.projectBusy(request.projectID)
        }
        guard !isRecoveringGenerationState,
              !mutationIsInFlight(projectID: request.projectID),
              !generationWriteProjectIDs.contains(request.projectID),
              !lifecycleReadProjectIDs.contains(request.projectID) else {
            throw NovelError.projectBusy(request.projectID)
        }

        generationStartProjectIDs.insert(request.projectID)
        generationStartRunIDsByProject[request.projectID] = request.id
        let task = Task { [self] in
            try await startGenerationCore(request)
        }
        generationStartReservations[request.id] = NovelGenerationStartReservation(
            projectID: request.projectID,
            operationID: request.operationID,
            payloadSHA256: payloadSHA256,
            task: task
        )

        do {
            let run = try await task.value
            clearGenerationStartReservation(request)
            return run
        } catch {
            clearGenerationStartReservation(request)
            throw error
        }
    }

    private func startGenerationCore(_ request: NovelRunRequest) async throws -> NovelRun {
        if try await repository.lifecycleOperation(
            projectID: request.projectID,
            operationID: request.operationID
        ) != nil {
            throw NovelError.idempotencyConflict(request.operationID)
        }

        let loaded = try await loadCommittedProject(id: request.projectID)
        if let replay = try replayOrObserve(request, document: loaded.document) {
            return replay
        }
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: request.projectID)
        }

        let model = try await resolveModel(for: modelPolicy(for: .creation, in: loaded.document))
        let parameters = modelParameters(for: request)
        let effectiveInputBudget = try effectiveInputBudget(
            request,
            model: model,
            parameters: parameters
        )

        let promptKind = promptKind(for: request)
        let planningRequest = NovelInjectionPlanningRequest(
            branchID: request.branchID,
            promptKind: promptKind,
            userText: request.userText,
            sourceChapterVersionID: request.sourceChapterVersionID,
            overrides: request.injectionOverrides,
            budget: NovelInjectionBudget(
                maxEstimatedInputTokens: effectiveInputBudget,
                chapterTailCharacterLimit: generationPolicy.chapterTailCharacterLimit,
                maximumRecentSessionMessages: generationPolicy.maximumRecentSessionMessages
            )
        )
        let plan: NovelInjectionPlan
        do {
            plan = try NovelInjectionPlanner.plan(
                document: loaded.document,
                request: planningRequest
            )
        } catch {
            throw mapInjectionError(error)
        }

        let modelRequest = NovelModelRequest(
            runID: request.id,
            model: model,
            purpose: modelPurpose(for: request),
            messages: modelMessages(plan: plan, userText: request.userText),
            parameters: parameters
        )
        let parameterEvidence = parameters.evidenceDictionary
        let startedAt = now()
        let injectionReceipt = NovelInjectionReceiptRecord(
            id: request.injectionReceiptID,
            runID: request.id,
            projectID: request.projectID,
            branchID: request.branchID,
            plan: plan,
            overrides: request.injectionOverrides,
            providerID: model.providerID,
            ownerProviderID: model.ownerProviderID,
            modelID: model.modelID,
            wireModelID: model.wireModelID,
            parameters: parameterEvidence,
            requestedInputBudgetTokens: request.inputBudgetTokens,
            createdAt: startedAt
        )
        let generationReceipt = NovelGenerationReceiptRecord(
            id: request.generationReceiptID,
            runID: request.id,
            providerID: model.providerID,
            ownerProviderID: model.ownerProviderID,
            modelID: model.modelID,
            wireModelID: model.wireModelID,
            promptVersion: plan.prompt.version,
            injectionReceiptID: request.injectionReceiptID,
            parameters: injectionReceipt.parameters,
            requestSHA256: try modelRequest.canonicalSHA256(),
            createdAt: startedAt
        )
        try Task.checkCancellation()
        let reduced = try NovelGenerationReducer.begin(
            request,
            artifacts: NovelGenerationStartArtifacts(
                injectionReceipt: injectionReceipt,
                generationReceipt: generationReceipt
            ),
            in: loaded.document,
            now: startedAt
        )
        try Task.checkCancellation()

        let committed: NovelLoadedProject
        do {
            committed = try await repository.commitProject(
                reduced.document,
                expectedRevision: loaded.document.project.revision
            )
        } catch {
            if requiresRepositoryReconciliation(error) {
                frozenProjectIDs.insert(request.projectID)
                if let reconciled = try? await loadCommittedProject(id: request.projectID),
                   reconciled.document.activeRuns.contains(where: {
                       $0.id == request.id && $0.operationID == request.operationID
                   }) {
                    return try await resumeDurablyStartedRun(
                        request,
                        modelRequest: modelRequest,
                        loaded: reconciled
                    )
                }
            }
            throw error
        }
        guard committed.document == reduced.document else {
            frozenProjectIDs.insert(request.projectID)
            throw NovelError.storageIndeterminate(request.projectID)
        }
        _ = try installLoadedProject(
            committed,
            id: request.projectID,
            allowsRollback: false
        )
        try Task.checkCancellation()
        return try await resumeDurablyStartedRun(
            request,
            modelRequest: modelRequest,
            loaded: committed
        )
    }

    private func clearGenerationStartReservation(_ request: NovelRunRequest) {
        clearGenerationStartReservation(
            runID: request.id,
            projectID: request.projectID,
            operationID: request.operationID
        )
    }

    private func clearGenerationStartReservation(
        runID: NovelRunID,
        projectID: NovelProjectID,
        operationID: NovelOperationID
    ) {
        guard let reservation = generationStartReservations[runID],
              reservation.projectID == projectID,
              reservation.operationID == operationID else {
            return
        }
        generationStartReservations[runID] = nil
        if generationStartRunIDsByProject[projectID] == runID {
            generationStartRunIDsByProject[projectID] = nil
            generationStartProjectIDs.remove(projectID)
        }
    }

    func interruptRun(_ command: NovelCancelRunCommand) async throws {
        let reservation = generationStartReservations[command.runID]
        if generationRuntimes[command.runID] != nil {
            // Once the durable runtime exists, terminal ownership must be claimed
            // before cancelling the still-returning start task. Otherwise a
            // CancellationError from modelRunner.start can win as a failed run,
            // or an uncooperative start can keep route exit waiting forever.
            _ = try await executeCancelRun(command)
            reservation?.task.cancel()
            if let reservation {
                clearGenerationStartReservation(
                    runID: command.runID,
                    projectID: reservation.projectID,
                    operationID: reservation.operationID
                )
            }
            return
        }

        if let reservation {
            guard reservation.projectID == command.projectID else {
                throw NovelError.runNotFound(command.runID)
            }
            reservation.task.cancel()
            _ = try? await reservation.task.value
            clearGenerationStartReservation(
                runID: command.runID,
                projectID: reservation.projectID,
                operationID: reservation.operationID
            )
        }

        if generationRuntimes[command.runID] != nil {
            _ = try await executeCancelRun(command)
            return
        }

        if reservation == nil {
            try registerPreStartInterruption(command)
        }
        let loaded: NovelLoadedProject
        do {
            loaded = try await loadCommittedProject(id: command.projectID)
        } catch {
            if let novelError = error as? NovelError,
               case .projectNotFound = novelError {
                clearPreStartInterruption(command)
            }
            throw error
        }
        if let run = loaded.document.activeRuns.first(where: { $0.id == command.runID }) {
            if run.status == .running {
                _ = try await executeCancelRun(command)
            }
            clearPreStartInterruption(command)
            return
        }

        // With no reservation or durable run, the tombstone remains for a
        // start call that has not entered this actor yet.
    }

    private func registerPreStartInterruption(
        _ command: NovelCancelRunCommand
    ) throws {
        if let existing = preStartInterruptions[command.runID] {
            guard existing.projectID == command.projectID else {
                throw NovelError.runNotFound(command.runID)
            }
            return
        }
        preStartInterruptions[command.runID] = command
    }

    private func clearPreStartInterruption(_ command: NovelCancelRunCommand) {
        guard preStartInterruptions[command.runID] == command else { return }
        preStartInterruptions[command.runID] = nil
    }

    private func observeStartedRun(_ request: NovelRunRequest) async throws -> NovelRun {
        let loaded = try await loadCommittedProject(id: request.projectID)
        guard let replay = try replayOrObserve(request, document: loaded.document) else {
            throw NovelError.storageIndeterminate(request.projectID)
        }
        return replay
    }

    func executeCancelRun(_ command: NovelCancelRunCommand) async throws -> NovelOutcome {
        if let runtime = generationRuntimes[command.runID] {
            guard runtime.projectID == command.projectID else {
                throw NovelError.runNotFound(command.runID)
            }
            let intent = NovelRunTerminalIntent.interrupted(
                reason: command.reason,
                command: command
            )
            let isNewClaim = generationRuntimes[command.runID]?.terminalClaim == nil
            guard claimTerminal(runID: command.runID, intent: intent) else {
                throw NovelError.projectBusy(command.projectID)
            }
            if isNewClaim {
                cancelProviderWithoutWaiting(runID: command.runID)
            }
            let committed = try await persistTerminalClaim(command.runID)
            guard let outcome = committed?.outcome else {
                throw NovelError.runNotFound(command.runID)
            }
            return outcome
        }

        let result: (
            outcome: NovelOutcome?,
            message: NovelSessionMessageSnapshot?
        ) = try await commitGenerationMutation(
            projectID: command.projectID,
            waitForMutations: false
        ) { document in
            let reduced = try NovelGenerationReducer.interrupt(
                command,
                partialContent: "",
                in: document,
                now: self.now()
            )
            return (reduced.document, (reduced.outcome, reduced.message))
        }

        guard let outcome = result.outcome else {
            throw NovelError.runNotFound(command.runID)
        }
        try? await repository.removeRecoverySidecar(
            projectID: command.projectID,
            runID: command.runID
        )
        finishRuntime(
            runID: command.runID,
            event: .interrupted(result.message)
        )
        return outcome
    }

    func interruptForBackground(
        projectID: NovelProjectID,
        deadline: Date,
        runID: NovelRunID?
    ) async {
        let startedAt = now()
        let reason: NovelRunInterruptionReason = startedAt >= deadline
            ? .expiration
            : .background
        if let runID,
           generationStartReservations[runID] == nil,
           generationRuntimes[runID] == nil {
            let command = NovelCancelRunCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: nil,
                    expectedConfigRevision: nil,
                    expectedBranchHeadRevision: nil
                ),
                projectID: projectID,
                runID: runID,
                reason: reason
            )
            try? registerPreStartInterruption(command)
        }
        let preDurableCommands = generationStartReservations.compactMap {
            runID, reservation -> NovelCancelRunCommand? in
            guard reservation.projectID == projectID,
                  generationRuntimes[runID] == nil else { return nil }
            return NovelCancelRunCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: nil,
                    expectedConfigRevision: nil,
                    expectedBranchHeadRevision: nil
                ),
                projectID: projectID,
                runID: runID,
                reason: reason
            )
        }
        // Publish cancellation before the first suspension in this method so a
        // commit that resumes concurrently cannot continue into provider start.
        for command in preDurableCommands {
            generationStartReservations[command.runID]?.task.cancel()
        }
        let startTasks = preDurableCommands.map { command in
            Task { [weak self] in
                try await self?.interruptRun(command)
            }
        }
        var claimedRunIDs: [NovelRunID] = []
        let mutationTasks = cancelInFlightBackgroundMutationTasks(
            projectID: projectID,
            includePolishAdoption: true
        )
        for runID in generationRuntimes.keys.sorted(by: {
            $0.description < $1.description
        }) {
            guard let runtime = generationRuntimes[runID],
                  runtime.projectID == projectID else {
                continue
            }
            if let claim = runtime.terminalClaim {
                if case .interrupted(let existingReason, _) = claim.intent,
                   existingReason == .background || existingReason == .expiration,
                   claim.persistenceState != .persisting {
                    claimedRunIDs.append(runID)
                }
                continue
            }
            guard claimTerminal(
                runID: runID,
                intent: .interrupted(reason: reason, command: nil)
            ) else {
                continue
            }
            claimedRunIDs.append(runID)
            cancelProviderWithoutWaiting(runID: runID)
            if let reservation = generationStartReservations[runID] {
                reservation.task.cancel()
                clearGenerationStartReservation(
                    runID: runID,
                    projectID: reservation.projectID,
                    operationID: reservation.operationID
                )
            }
        }

        guard !claimedRunIDs.isEmpty || !mutationTasks.isEmpty || !startTasks.isEmpty else {
            return
        }
        let gate = NovelBackgroundDeadlineGate()
        Task { [weak self] in
            for task in startTasks {
                _ = try? await task.value
            }
            for runID in claimedRunIDs {
                _ = try? await self?.persistTerminalClaim(runID)
            }
            for task in mutationTasks {
                _ = try? await task.value
            }
            gate.signal()
        }

        let waitSeconds = min(5, max(0, deadline.timeIntervalSince(startedAt)))
        guard waitSeconds > 0 else { return }
        let nanoseconds = UInt64(waitSeconds * 1_000_000_000)
        let timeoutTask = Task {
            try? await Task.sleep(nanoseconds: nanoseconds)
            if !Task.isCancelled {
                gate.signal()
            }
        }
        await gate.wait()
        timeoutTask.cancel()
    }

    func retryPendingTerminal(runID: NovelRunID) async throws {
        guard let runtime = generationRuntimes[runID] else {
            throw NovelError.runNotFound(runID)
        }
        guard let claim = runtime.terminalClaim else {
            throw NovelError.invalidInput("The run has no pending terminal state.")
        }
        guard claim.persistenceState != .persisting else {
            throw NovelError.projectBusy(runtime.projectID)
        }
        guard try await persistTerminalClaim(runID) != nil else {
            throw NovelError.projectBusy(runtime.projectID)
        }
    }

    func recoverGenerationStateIfNeeded(
        requiredProjectID: NovelProjectID? = nil,
        allowsMissingRequiredProject: Bool = false,
        allowsConcurrentMutationOnRequiredProject: Bool = false
    ) async throws {
        if didRecoverGenerationState { return }
        if isRecoveringGenerationState {
            if allowsConcurrentMutationOnRequiredProject,
               recoveringGenerationProjectID == requiredProjectID {
                return
            }
            while isRecoveringGenerationState {
                await Task.yield()
            }
            return try await recoverGenerationStateIfNeeded(
                requiredProjectID: requiredProjectID,
                allowsMissingRequiredProject: allowsMissingRequiredProject,
                allowsConcurrentMutationOnRequiredProject: allowsConcurrentMutationOnRequiredProject
            )
        }
        if let requiredProjectID,
           recoveredGenerationProjectIDs.contains(requiredProjectID) {
            return
        }
        // Recovery may be waiting on storage while another user action arrives.
        // Lifecycle-ledger reconciliation is the global write barrier. Once it
        // finishes, durable run markers and revision checks protect project-local
        // mutations while the remaining recovery scan continues.
        isRecoveringGenerationState = true
        recoveringGenerationProjectID = requiredProjectID
        defer {
            recoveringGenerationProjectID = nil
            isRecoveringGenerationState = false
        }

        isReconcilingLifecycleOperations = true
        do {
            try await reconcilePendingLifecycleOperations()
            isReconcilingLifecycleOperations = false
        } catch {
            isReconcilingLifecycleOperations = false
            throw error
        }
        let sidecars = try await repository.listRecoverySidecars()
        let summaries: [NovelProjectSummary]
        let scopedLoadedProject: NovelLoadedProject?
        if let requiredProjectID {
            do {
                // Install into the in-memory cache so the caller's subsequent
                // loadCommittedProject does not re-decode the same large document.
                let loaded = try installLoadedProject(
                    try await repository.loadProject(id: requiredProjectID),
                    id: requiredProjectID,
                    allowsRollback: frozenProjectIDs.contains(requiredProjectID)
                )
                scopedLoadedProject = loaded
                summaries = [NovelProjectSummary(
                    document: loaded.document,
                    isDegraded: loaded.access != .readWrite
                )]
            } catch NovelError.projectNotFound where allowsMissingRequiredProject {
                recoveredGenerationProjectIDs.insert(requiredProjectID)
                return
            }
        } else {
            scopedLoadedProject = nil
            summaries = try await repository.listProjects()
        }
        var hasDeferredRunningRecovery = false
        for summary in summaries where summary.loadError == nil {
            let loaded: NovelLoadedProject
            if let scopedLoadedProject {
                loaded = scopedLoadedProject
            } else {
                do {
                    loaded = try await repository.loadProject(id: summary.id)
                } catch NovelError.projectNotFound {
                    // The project may be deleted after the summary snapshot.
                    continue
                }
            }
            let hasLifecycleWriteBarrier = blockedLifecycleProjectIDs.contains(summary.id) ||
                !(pendingLifecycleOperationsByProject[summary.id]?.isEmpty ?? true)
            if hasLifecycleWriteBarrier {
                if loaded.document.activeRuns.contains(where: { $0.status == .running }) ||
                    sidecars.contains(where: { $0.projectID == summary.id }) {
                    hasDeferredRunningRecovery = true
                }
                continue
            }
            for run in loaded.document.activeRuns {
                if run.status == .running {
                    guard loaded.access == .readWrite else {
                        // A degraded snapshot is not authoritative for writes.
                        // Keep its recovery sidecar until a writable primary is
                        // available or a durable terminal is observed.
                        hasDeferredRunningRecovery = true
                        continue
                    }
                    let matchingRunSidecars = sidecars.filter {
                        $0.projectID == summary.id && $0.runID == run.id
                    }
                    let startRevision = loaded.document.appliedOperations.first {
                        $0.operationID == run.operationID && $0.kind == .startRun
                    }?.appliedProjectRevision
                    let sidecar = matchingRunSidecars
                        .filter {
                            $0.branchID == run.branchID &&
                                $0.sessionID == run.sessionID &&
                                $0.messageID == run.messageID &&
                                $0.baseProjectRevision == startRevision
                        }
                        .max { $0.sequence < $1.sequence }
                    // An identity mismatch never contributes text to a run. The
                    // durable marker is the only safe fallback.
                    let partial = sidecar?.partialContent ?? run.partialContent
                    do {
                        let _: NovelSessionMessageSnapshot? = try await commitGenerationMutation(
                            projectID: summary.id,
                            waitForMutations: true
                        ) { document in
                            let reduced = try NovelGenerationReducer.interrupt(
                                runID: run.id,
                                reason: .recovery,
                                partialContent: partial,
                                in: document,
                                now: self.now()
                            )
                            return (reduced.document, reduced.message)
                        }
                        try? await repository.removeRecoverySidecar(
                            projectID: summary.id,
                            runID: run.id
                        )
                    } catch {
                        frozenProjectIDs.insert(summary.id)
                        throw error
                    }
                } else if sidecars.contains(where: {
                    $0.projectID == summary.id && $0.runID == run.id
                }) {
                    try? await repository.removeRecoverySidecar(
                        projectID: summary.id,
                        runID: run.id
                    )
                }
            }
        }
        if requiredProjectID == nil {
            didRecoverGenerationState = !hasDeferredRunningRecovery
        } else if !hasDeferredRunningRecovery, let requiredProjectID {
            recoveredGenerationProjectIDs.insert(requiredProjectID)
        }
    }
}

extension DefaultNovelCreation {
    func makeInjectionPreview(
        _ request: NovelInjectionPreviewRequest
    ) async throws -> NovelInjectionPreviewSnapshot {
        let first = try await loadCommittedProject(id: request.projectID)
        guard first.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: request.projectID)
        }
        guard let firstBranch = first.document.branches.first(where: {
            $0.id == request.branchID && $0.lifecycle == .active
        }) else {
            throw NovelError.branchNotFound(request.branchID)
        }
        guard firstBranch.activeRunID == nil else {
            throw NovelError.projectBusy(request.projectID)
        }

        let firstModelPolicy = modelPolicy(for: .creation, in: first.document)
        let model = try await resolveModel(for: firstModelPolicy)
        let current = try await loadCommittedProject(id: request.projectID)
        guard current.document.project.revision == first.document.project.revision,
              current.document.project.configRevision == first.document.project.configRevision,
              modelPolicy(for: .creation, in: current.document) == firstModelPolicy,
              let branch = current.document.branches.first(where: {
                  $0.id == request.branchID && $0.lifecycle == .active
              }),
              branch.headRevision == firstBranch.headRevision,
              branch.activeRunID == nil else {
            throw NovelError.projectBusy(request.projectID)
        }

        let candidateID: NovelCandidateID?
        switch request.kind {
        case .prose, .polish, .regenerate:
            candidateID = NovelCandidateID()
        case .quickStart, .discussion:
            candidateID = nil
        }
        let synthetic = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: request.projectID,
            branchID: request.branchID,
            kind: request.kind,
            mode: request.mode,
            granularity: request.granularity,
            userText: request.userText,
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: candidateID,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: request.sourceChapterVersionID,
            injectionOverrides: request.injectionOverrides,
            inputBudgetTokens: request.inputBudgetTokens,
            expectedProjectRevision: current.document.project.revision,
            expectedConfigRevision: current.document.project.configRevision,
            expectedBranchHeadRevision: branch.headRevision
        )
        let parameters = modelParameters(for: synthetic)
        let effectiveBudget = try effectiveInputBudget(
            synthetic,
            model: model,
            parameters: parameters
        )
        let planning = NovelInjectionPlanningRequest(
            branchID: request.branchID,
            promptKind: promptKind(for: synthetic),
            userText: request.userText,
            sourceChapterVersionID: request.sourceChapterVersionID,
            overrides: request.injectionOverrides,
            budget: NovelInjectionBudget(
                maxEstimatedInputTokens: effectiveBudget,
                chapterTailCharacterLimit: generationPolicy.chapterTailCharacterLimit,
                maximumRecentSessionMessages: generationPolicy.maximumRecentSessionMessages
            )
        )
        let plan: NovelInjectionPlan
        do {
            plan = try NovelInjectionPlanner.plan(document: current.document, request: planning)
        } catch {
            throw mapInjectionError(error)
        }
        return NovelInjectionPreviewSnapshot(
            projectID: request.projectID,
            branchID: request.branchID,
            projectRevision: current.document.project.revision,
            configRevision: current.document.project.configRevision,
            branchHeadRevision: branch.headRevision,
            resolvedModel: model,
            parameters: parameters,
            requestedInputBudgetTokens: request.inputBudgetTokens,
            effectiveInputBudgetTokens: effectiveBudget,
            plan: plan
        )
    }
}

private extension DefaultNovelCreation {
    func resumeDurablyStartedRun(
        _ request: NovelRunRequest,
        modelRequest: NovelModelRequest,
        loaded: NovelLoadedProject
    ) async throws -> NovelRun {
        guard let runRecord = loaded.document.activeRuns.first(where: {
            $0.id == request.id && $0.status == .running
        }) else {
            if let replay = try replayOrObserve(request, document: loaded.document) {
                return replay
            }
            throw NovelError.storageIndeterminate(request.projectID)
        }

        let run = makeRuntimeAndStream(
            run: runRecord,
            projectID: request.projectID,
            startProjectRevision: loaded.document.project.revision
        )
        broadcast(
            .started(NovelRunReceipt(
                runID: request.id,
                projectID: request.projectID,
                branchID: request.branchID,
                receiptID: request.generationReceiptID
            )),
            runID: request.id
        )

        do {
            let modelEvents = try await modelRunner.start(modelRequest)
            let task = Task { [weak self] in
                var transportTerminal = false
                for await event in modelEvents {
                    if case .completed = event { transportTerminal = true }
                    if case .askUser = event { transportTerminal = true }
                    if case .failed = event { transportTerminal = true }
                    await self?.consumeModelEvent(event, runID: request.id)
                }
                await self?.modelStreamEnded(
                    runID: request.id,
                    hadTransportTerminal: transportTerminal
                )
            }
            if generationRuntimes[request.id] != nil {
                generationRuntimes[request.id]?.modelTask = task
            } else {
                task.cancel()
            }
        } catch is CancellationError {
            // Reservation cancellation is owned by interruptRun/background.
            // Those paths claim and persist interruption before cancelling this
            // task, so cancellation must never overwrite it as model_start_failed.
        } catch {
            await failRun(
                request.id,
                failure: failure(from: error, fallbackCode: "model_start_failed")
            )
        }
        return run
    }

    func replayOrObserve(
        _ request: NovelRunRequest,
        document: NovelProjectDocumentV1
    ) throws -> NovelRun? {
        guard let operation = document.appliedOperations.first(where: {
            $0.operationID == request.operationID
        }) else {
            return nil
        }
        let payloadSHA256 = try request.canonicalPayloadSHA256()
        guard operation.kind == .startRun,
              operation.payloadSHA256 == payloadSHA256,
              let run = document.activeRuns.first(where: { $0.id == request.id }),
              run.operationID == request.operationID else {
            throw NovelError.idempotencyConflict(request.operationID)
        }

        if run.status == .running {
            guard generationRuntimes[run.id] != nil else {
                throw NovelError.projectBusy(request.projectID)
            }
            return attachSubscriber(to: run)
        }

        let pair = AsyncStream<NovelRunEvent>.makeStream(bufferingPolicy: .unbounded)
        pair.continuation.yield(.started(NovelRunReceipt(
            runID: run.id,
            projectID: request.projectID,
            branchID: run.branchID,
            receiptID: run.receiptID
        )))
        yieldReplayTerminal(run, document: document, to: pair.continuation)
        pair.continuation.finish()
        return NovelRun(id: run.id, events: pair.stream)
    }

    func makeRuntimeAndStream(
        run: NovelActiveRunRecord,
        projectID: NovelProjectID,
        startProjectRevision: Int64
    ) -> NovelRun {
        let pair = AsyncStream<NovelRunEvent>.makeStream(bufferingPolicy: .unbounded)
        let subscriberID = UUID()
        pair.continuation.onTermination = { [weak self] _ in
            Task { await self?.removeSubscriber(subscriberID, runID: run.id) }
        }
        generationRuntimes[run.id] = NovelRunRuntime(
            projectID: projectID,
            branchID: run.branchID,
            kind: run.kind,
            startProjectRevision: startProjectRevision,
            partialContent: run.partialContent,
            usage: nil,
            subscribers: [subscriberID: pair.continuation],
            modelTask: nil,
            sidecarSequence: 0,
            lastSidecarAt: now(),
            lastSidecarByteCount: run.partialContent.utf8.count,
            terminalClaim: nil
        )
        return NovelRun(id: run.id, events: pair.stream)
    }

    func attachSubscriber(to run: NovelActiveRunRecord) -> NovelRun {
        let pair = AsyncStream<NovelRunEvent>.makeStream(bufferingPolicy: .unbounded)
        let subscriberID = UUID()
        pair.continuation.onTermination = { [weak self] _ in
            Task { await self?.removeSubscriber(subscriberID, runID: run.id) }
        }
        guard var runtime = generationRuntimes[run.id] else {
            pair.continuation.finish()
            return NovelRun(id: run.id, events: pair.stream)
        }
        runtime.subscribers[subscriberID] = pair.continuation
        generationRuntimes[run.id] = runtime
        pair.continuation.yield(.started(NovelRunReceipt(
            runID: run.id,
            projectID: runtime.projectID,
            branchID: run.branchID,
            receiptID: run.receiptID
        )))
        if !runtime.partialContent.isEmpty {
            pair.continuation.yield(.replaced(runtime.partialContent))
        }
        if case .blocked(let failure) = runtime.terminalClaim?.persistenceState {
            pair.continuation.yield(.persistenceBlocked(failure))
        }
        return NovelRun(id: run.id, events: pair.stream)
    }

    func removeSubscriber(_ subscriberID: UUID, runID: NovelRunID) {
        generationRuntimes[runID]?.subscribers[subscriberID] = nil
    }

    func broadcast(_ event: NovelRunEvent, runID: NovelRunID) {
        generationRuntimes[runID]?.subscribers.values.forEach { $0.yield(event) }
    }

    func finishRuntime(runID: NovelRunID, event: NovelRunEvent) {
        guard let runtime = generationRuntimes.removeValue(forKey: runID) else { return }
        runtime.subscribers.values.forEach {
            $0.yield(event)
            $0.finish()
        }
    }

    func claimTerminal(runID: NovelRunID, intent: NovelRunTerminalIntent) -> Bool {
        guard var runtime = generationRuntimes[runID] else { return false }
        if let claim = runtime.terminalClaim {
            return claim.intent == intent && claim.persistenceState != .persisting
        }
        // Manuscript-like runs: strip mistaken outer ```html / ```markdown fences once
        // at the terminal boundary so complete, interrupt, and fail share one clean
        // snapshot for messages, candidates, collect, and adopt. Idempotent if complete
        // path already normalized (e.g. polish sentinel handling).
        if let normalized = Self.normalizedManuscriptPartial(
            kind: runtime.kind,
            partialContent: runtime.partialContent
        ), normalized != runtime.partialContent {
            runtime.partialContent = normalized
            generationRuntimes[runID] = runtime
            broadcast(.replaced(normalized), runID: runID)
        }
        runtime.terminalClaim = NovelRunTerminalClaim(
            intent: intent,
            partialContent: runtime.partialContent,
            claimedAt: now(),
            persistenceState: .pending
        )
        generationRuntimes[runID] = runtime
        return true
    }

    /// Returns cleaned manuscript partial when this run kind produces user-collectable prose.
    private static func normalizedManuscriptPartial(
        kind: NovelRunKind,
        partialContent: String
    ) -> String? {
        switch kind {
        case .prose, .regenerate:
            return NovelPromptCatalog.normalizedCandidateProse(partialContent)
        case .polish:
            // Prefer full polish contract (sentinel + strip). Fall back to fence-only
            // strip for interrupted/partial polish messages.
            if let completed = NovelPromptCatalog.completedPolishContent(from: partialContent) {
                return completed
            }
            return NovelPromptCatalog.normalizedCandidateProse(partialContent)
        case .quickStart, .discussion:
            return nil
        }
    }

    func cancelProviderWithoutWaiting(runID: NovelRunID) {
        Task { [modelRunner] in
            await modelRunner.cancel(runID: runID)
        }
    }

    func consumeModelEvent(_ event: NovelModelEvent, runID: NovelRunID) async {
        guard var runtime = generationRuntimes[runID],
              runtime.terminalClaim == nil else {
            return
        }
        switch event {
        case .textDelta(let text):
            guard !text.isEmpty else { return }
            runtime.partialContent += text
            generationRuntimes[runID] = runtime
            broadcast(.delta(text), runID: runID)
            await flushRecoverySidecar(runID: runID, force: false)
        case .textReplacement(let text):
            runtime.partialContent = text
            generationRuntimes[runID] = runtime
            broadcast(.replaced(text), runID: runID)
            await flushRecoverySidecar(runID: runID, force: false)
        case .usage(let usage):
            runtime.usage = usage
            generationRuntimes[runID] = runtime
        case .askUser(let prompt, let preface):
            await awaitUser(runID, prompt: prompt, preface: preface)
        case .completed:
            await completeRun(runID)
        case .failed(let failure):
            await failRun(
                runID,
                failure: NovelFailure(
                    code: failure.code,
                    message: failure.message,
                    isRetryable: failure.isRetryable
                )
            )
        }
    }

    func modelStreamEnded(runID: NovelRunID, hadTransportTerminal: Bool) async {
        guard !hadTransportTerminal,
              generationRuntimes[runID]?.terminalClaim == nil else {
            return
        }
        await failRun(
            runID,
            failure: NovelFailure(
                code: "stream_ended",
                message: "模型流式响应异常结束，未收到完整回复。",
                isRetryable: true
            )
        )
    }

    func completeRun(_ runID: NovelRunID) async {
        guard var runtime = generationRuntimes[runID] else { return }
        if runtime.kind == .discussion,
           let prompt = try? Self.decodeAskUserFallback(runtime.partialContent) {
            runtime.partialContent = ""
            generationRuntimes[runID] = runtime
            await awaitUser(runID, prompt: prompt, preface: "")
            return
        }
        guard !runtime.partialContent.trimmingCharacters(
            in: .whitespacesAndNewlines
        ).isEmpty else {
            await failRun(
                runID,
                failure: NovelFailure(
                    code: "empty_completion",
                    message: "The model completed without returning any text.",
                    isRetryable: true
                )
            )
            return
        }
        if runtime.kind == .quickStart {
            do {
                _ = try NovelStructuredOutputDecoder.decodeQuickStartSuggestions(
                    from: runtime.partialContent
                )
            } catch {
                await failRun(
                    runID,
                    failure: NovelFailure(
                        code: "invalid_quick_start_output",
                        message: "The quick-start suggestions were not valid structured output.",
                        isRetryable: true
                    )
                )
                return
            }
        }
        if runtime.kind == .polish {
            // Sentinel is a hard completion contract; fence-stripping happens inside
            // completedPolishContent and again at claimTerminal for interrupt paths.
            guard let completed = NovelPromptCatalog.completedPolishContent(
                from: runtime.partialContent
            ) else {
                await failRun(
                    runID,
                    failure: NovelFailure(
                        code: "incomplete_polish_output",
                        message: "The whole-chapter polish did not return a verified complete chapter.",
                        isRetryable: true
                    )
                )
                return
            }
            runtime.partialContent = completed
            generationRuntimes[runID] = runtime
            broadcast(.replaced(completed), runID: runID)
        }
        // claimTerminal normalizes prose/regenerate/polish partials so complete,
        // interrupt, and fail all share one durable clean snapshot.
        guard claimTerminal(runID: runID, intent: .completed) else { return }
        _ = try? await persistTerminalClaim(runID)
    }

    func awaitUser(
        _ runID: NovelRunID,
        prompt: NovelAskUserPrompt,
        preface: String
    ) async {
        do {
            try NovelGenerationReducer.validateAskUserPrompt(prompt)
        } catch {
            await failRun(
                runID,
                failure: NovelFailure(
                    code: "invalid_ask_user_output",
                    message: "模型提出的问题格式无法读取，请重试。",
                    isRetryable: true
                )
            )
            return
        }
        guard claimTerminal(
            runID: runID,
            intent: .awaitingUser(
                prompt,
                preface: preface.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        ) else { return }
        _ = try? await persistTerminalClaim(runID)
    }

    func failRun(_ runID: NovelRunID, failure: NovelFailure) async {
        guard claimTerminal(runID: runID, intent: .failed(failure)) else { return }
        _ = try? await persistTerminalClaim(runID)
    }

    func persistTerminalClaim(_ runID: NovelRunID) async throws -> NovelTerminalCommit? {
        guard var runtime = generationRuntimes[runID],
              var claim = runtime.terminalClaim,
              claim.persistenceState != .persisting else {
            return nil
        }
        claim.persistenceState = .persisting
        runtime.terminalClaim = claim
        generationRuntimes[runID] = runtime

        await flushRecoverySidecar(runID: runID, force: true)
        do {
            let waitForMutations: Bool
            if case .interrupted(_, let command) = claim.intent, command != nil {
                waitForMutations = false
            } else {
                waitForMutations = true
            }
            let committed: NovelTerminalCommit = try await commitGenerationMutation(
                projectID: runtime.projectID,
                waitForMutations: waitForMutations
            ) { document in
                try self.reduceTerminalClaim(
                    runID: runID,
                    claim: claim,
                    document: document
                )
            }
            try? await repository.removeRecoverySidecar(
                projectID: runtime.projectID,
                runID: runID
            )
            finishRuntime(runID: runID, event: committed.event)
            return committed
        } catch {
            let persistenceFailure = failure(
                from: error,
                fallbackCode: "terminal_persist_failed"
            )
            if var current = generationRuntimes[runID],
               var currentClaim = current.terminalClaim,
               currentClaim.intent == claim.intent,
               currentClaim.claimedAt == claim.claimedAt {
                currentClaim.persistenceState = .blocked(persistenceFailure)
                current.terminalClaim = currentClaim
                generationRuntimes[runID] = current
                broadcast(.persistenceBlocked(persistenceFailure), runID: runID)
            }
            throw error
        }
    }

    func reduceTerminalClaim(
        runID: NovelRunID,
        claim: NovelRunTerminalClaim,
        document: NovelProjectDocumentV1
    ) throws -> (document: NovelProjectDocumentV1, result: NovelTerminalCommit) {
        switch claim.intent {
        case .completed:
            if let replay = try replayTerminalCommit(
                runID: runID,
                claim: claim,
                document: document
            ) {
                return (document, replay)
            }
            let reduced = try NovelGenerationReducer.complete(
                runID: runID,
                content: claim.partialContent,
                in: document,
                now: claim.claimedAt
            )
            guard let message = reduced.message else {
                throw NovelError.storageIndeterminate(document.project.id)
            }
            return (
                reduced.document,
                NovelTerminalCommit(outcome: nil, event: .completed(message))
            )

        case .awaitingUser(let prompt, let preface):
            if let replay = try replayTerminalCommit(
                runID: runID,
                claim: claim,
                document: document
            ) {
                return (document, replay)
            }
            let reduced = try NovelGenerationReducer.completeAwaitingUser(
                runID: runID,
                prompt: prompt,
                preface: preface,
                in: document,
                now: claim.claimedAt
            )
            guard let message = reduced.message else {
                throw NovelError.storageIndeterminate(document.project.id)
            }
            return (
                reduced.document,
                NovelTerminalCommit(outcome: nil, event: .completed(message))
            )

        case .interrupted(let reason, let command):
            if let command {
                let reduced = try NovelGenerationReducer.interrupt(
                    command,
                    partialContent: claim.partialContent,
                    in: document,
                    now: claim.claimedAt
                )
                guard let outcome = reduced.outcome else {
                    throw NovelError.storageIndeterminate(document.project.id)
                }
                let message = reduced.message ?? sessionMessageSnapshot(
                    runID: runID,
                    document: reduced.document
                )
                return (
                    reduced.document,
                    NovelTerminalCommit(
                        outcome: outcome,
                        event: .interrupted(message)
                    )
                )
            }
            if let replay = try replayTerminalCommit(
                runID: runID,
                claim: claim,
                document: document
            ) {
                return (document, replay)
            }
            let reduced = try NovelGenerationReducer.interrupt(
                runID: runID,
                reason: reason,
                partialContent: claim.partialContent,
                in: document,
                now: claim.claimedAt
            )
            return (
                reduced.document,
                NovelTerminalCommit(
                    outcome: nil,
                    event: .interrupted(reduced.message)
                )
            )

        case .failed(let failure):
            if let replay = try replayTerminalCommit(
                runID: runID,
                claim: claim,
                document: document
            ) {
                return (document, replay)
            }
            let reduced = try NovelGenerationReducer.fail(
                runID: runID,
                failure: failure,
                partialContent: claim.partialContent,
                in: document,
                now: claim.claimedAt
            )
            return (
                reduced.document,
                NovelTerminalCommit(outcome: nil, event: .failed(failure))
            )
        }
    }

    func replayTerminalCommit(
        runID: NovelRunID,
        claim: NovelRunTerminalClaim,
        document: NovelProjectDocumentV1
    ) throws -> NovelTerminalCommit? {
        guard let run = document.activeRuns.first(where: { $0.id == runID }) else {
            throw NovelError.runNotFound(runID)
        }
        guard run.status != .running else { return nil }
        guard run.partialContent == claim.partialContent else {
            throw NovelError.storageIndeterminate(document.project.id)
        }

        switch claim.intent {
        case .completed:
            guard run.status == .completed,
                  let message = sessionMessageSnapshot(
                    runID: runID,
                    document: document
                  ) else {
                throw NovelError.storageIndeterminate(document.project.id)
            }
            return NovelTerminalCommit(outcome: nil, event: .completed(message))
        case .awaitingUser(let prompt, _):
            guard run.status == .completed,
                  let message = sessionMessageSnapshot(
                      runID: runID,
                      document: document
                  ),
                  message.message.interaction == .askUser(prompt) else {
                throw NovelError.storageIndeterminate(document.project.id)
            }
            return NovelTerminalCommit(outcome: nil, event: .completed(message))
        case .interrupted(let reason, let command):
            guard command == nil,
                  run.status == .interrupted,
                  run.interruptionReason == reason else {
                throw NovelError.storageIndeterminate(document.project.id)
            }
            return NovelTerminalCommit(
                outcome: nil,
                event: .interrupted(sessionMessageSnapshot(
                    runID: runID,
                    document: document
                ))
            )
        case .failed(let failure):
            guard run.status == .failed,
                  run.terminalFailure == failure else {
                throw NovelError.storageIndeterminate(document.project.id)
            }
            return NovelTerminalCommit(outcome: nil, event: .failed(failure))
        }
    }

    static func decodeAskUserFallback(_ output: String) throws -> NovelAskUserPrompt? {
        struct FallbackEnvelope: Decodable {
            let amberAskUser: NovelAskUserPrompt
        }
        let trimmed = output.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.first == "{", trimmed.last == "}",
              let data = trimmed.data(using: .utf8) else { return nil }
        let object = try JSONSerialization.jsonObject(with: data)
        guard let dictionary = object as? [String: Any],
              Set(dictionary.keys) == ["amberAskUser"] else { return nil }
        let prompt = try JSONDecoder().decode(FallbackEnvelope.self, from: data).amberAskUser
        try NovelGenerationReducer.validateAskUserPrompt(prompt)
        return prompt
    }

    func sessionMessageSnapshot(
        runID: NovelRunID,
        document: NovelProjectDocumentV1
    ) -> NovelSessionMessageSnapshot? {
        guard let run = document.activeRuns.first(where: { $0.id == runID }),
              let message = document.sessions
                .first(where: { $0.id == run.sessionID })?
                .messages
                .first(where: { $0.id == run.messageID }) else {
            return nil
        }
        return NovelSessionMessageSnapshot(
            projectID: document.project.id,
            branchID: run.branchID,
            message: message
        )
    }

    func flushRecoverySidecar(runID: NovelRunID, force: Bool) async {
        guard var runtime = generationRuntimes[runID] else { return }
        let timestamp = now()
        let byteCount = runtime.partialContent.utf8.count
        let bytesAdded = max(0, byteCount - runtime.lastSidecarByteCount)
        guard force ||
            bytesAdded >= generationPolicy.sidecarByteThreshold ||
            timestamp.timeIntervalSince(runtime.lastSidecarAt) >= generationPolicy.sidecarInterval else {
            return
        }

        runtime.sidecarSequence += 1
        runtime.lastSidecarAt = timestamp
        runtime.lastSidecarByteCount = byteCount
        generationRuntimes[runID] = runtime
        guard let loaded = try? await loadCommittedProject(id: runtime.projectID),
              let run = loaded.document.activeRuns.first(where: { $0.id == runID }) else {
            return
        }
        let sidecar = NovelRecoverySidecarV1(
            schemaVersion: NovelRecoverySidecarV1.currentSchemaVersion,
            projectID: runtime.projectID,
            runID: runID,
            branchID: run.branchID,
            sessionID: run.sessionID,
            messageID: run.messageID,
            baseProjectRevision: runtime.startProjectRevision,
            sequence: runtime.sidecarSequence,
            partialContent: runtime.partialContent,
            partialSHA256: NovelDocumentValidator.sha256(runtime.partialContent),
            updatedAt: timestamp
        )
        do {
            try await repository.writeRecoverySidecar(sidecar)
            if generationRuntimes[runID] == nil {
                try? await repository.removeRecoverySidecar(
                    projectID: runtime.projectID,
                    runID: runID
                )
            }
        } catch {
            // The project terminal remains authoritative. A later forced flush
            // or startup recovery retries without publishing false durability.
        }
    }

    func commitGenerationMutation<Result: Sendable>(
        projectID: NovelProjectID,
        waitForMutations: Bool,
        reducer: (NovelProjectDocumentV1) throws -> (
            document: NovelProjectDocumentV1,
            result: Result
        )
    ) async throws -> Result {
        if waitForMutations {
            await waitForInFlightMutation(projectID: projectID)
        }
        await waitForGenerationWrite(projectID: projectID)
        generationWriteProjectIDs.insert(projectID)
        defer { generationWriteProjectIDs.remove(projectID) }

        var lastError: Error?
        for _ in 0..<3 {
            let loaded = try await loadCommittedProject(id: projectID)
            guard loaded.access == .readWrite else {
                throw NovelError.degradedReadOnly(projectID: projectID)
            }
            let reduced = try reducer(loaded.document)
            if reduced.document == loaded.document {
                return reduced.result
            }
            do {
                let committed = try await repository.commitProject(
                    reduced.document,
                    expectedRevision: loaded.document.project.revision
                )
                guard committed.document == reduced.document else {
                    throw NovelError.storageIndeterminate(projectID)
                }
                _ = try installLoadedProject(
                    committed,
                    id: projectID,
                    allowsRollback: false
                )
                return reduced.result
            } catch {
                lastError = error
                if requiresRepositoryReconciliation(error) {
                    frozenProjectIDs.insert(projectID)
                } else if case NovelError.repositoryFailure = error {
                    // One retry covers injected pre-install failures while the
                    // reducer replays from the still-authoritative snapshot.
                } else {
                    throw error
                }
                await Task.yield()
            }
        }
        throw lastError ?? NovelError.storageIndeterminate(projectID)
    }

    func resolveModel(for policy: NovelProjectModelPolicy) async throws -> NovelResolvedModel {
        do {
            return try await modelRunner.resolveModel(for: policy)
        } catch let error as NovelError {
            throw error
        } catch let failure as NovelModelFailure {
            throw NovelError.modelUnavailable(failure.message)
        } catch {
            throw NovelError.modelUnavailable(error.localizedDescription)
        }
    }

    func promptKind(for request: NovelRunRequest) -> NovelPromptKind {
        switch request.kind {
        case .quickStart: .quickStart
        case .discussion: .discussion
        case .prose:
            request.granularity == .continuation ? .proseContinuation : .proseWholeChapter
        case .polish: .wholeChapterPolish
        case .regenerate: .wholeChapterRegeneration
        }
    }

    func modelPurpose(for request: NovelRunRequest) -> NovelModelPurpose {
        switch request.kind {
        case .quickStart: .quickStart
        case .discussion: .discussion
        case .prose, .regenerate: .prose
        case .polish: .polish
        }
    }

    /// 2026-07-26 用户明确裁决:四类生成任务一律不人为设置输出上限,思考等级一律 `.automatic`。
    ///
    /// 原实现按任务分配 2K/4K/8K 上限并对 prose/polish 关闭思考。真机取证(deepseek-v4-pro,
    /// 推理模型)显示 quickStart 的 2048 上限会被思考 token 吃掉,结构化建议还没写完就
    /// 触发 `finishReason == "length"`,整轮以「模型回复达到输出上限」硬失败——人为上限
    /// 制造了本不存在的失败。讨论模式早在 2026-07-17 就因同一原因改为 nil,其余三类被漏掉。
    ///
    /// 输入侧仍有独立护栏:`effectiveInputBudget` 用 `request.inputBudgetTokens` 封顶
    /// (用户可在「写作上下文预算」里配置),因此取消输出上限不会让输入无限膨胀。
    /// 已知边界:若用户把输入预算调到接近模型窗口上限,留给输出的空间会变小,模型可能
    /// 仍由 provider 侧自然截断——该情形沿用既有 `reachedOutputLimit` 提示,不在此层兜底。
    func modelParameters(for request: NovelRunRequest) -> NovelModelParameters {
        switch request.kind {
        case .quickStart:
            NovelModelParameters(
                temperature: 0.7,
                topP: 0.95,
                maxOutputTokens: nil,
                reasoningLevel: .automatic
            )
        case .discussion:
            NovelModelParameters(
                temperature: 0.7,
                topP: 0.95,
                maxOutputTokens: nil,
                reasoningLevel: .automatic
            )
        case .prose:
            NovelModelParameters(
                temperature: 0.85,
                topP: 0.95,
                maxOutputTokens: nil,
                reasoningLevel: .automatic
            )
        case .polish:
            NovelModelParameters(
                temperature: 0.35,
                topP: 0.9,
                maxOutputTokens: nil,
                reasoningLevel: .automatic
            )
        case .regenerate:
            // 重写允许改剧情,创作自由度按正文生成给,而不是润色的 0.35。
            NovelModelParameters(
                temperature: 0.7,
                topP: 0.95,
                maxOutputTokens: nil,
                reasoningLevel: .automatic
            )
        }
    }

    func modelMessages(plan: NovelInjectionPlan, userText: String) -> [NovelModelMessage] {
        var system = plan.prompt.systemText
        if !plan.contextText.isEmpty {
            system += "\n\n" + plan.contextText
        }
        return [
            NovelModelMessage(role: .system, content: system),
            NovelModelMessage(role: .user, content: userText)
        ]
    }

    func effectiveInputBudget(
        _ request: NovelRunRequest,
        model: NovelResolvedModel,
        parameters: NovelModelParameters
    ) throws -> Int {
        guard request.inputBudgetTokens > 0 else {
            throw NovelError.invalidInput("The generation input budget must be positive.")
        }
        guard let window = model.contextWindowTokens else {
            return request.inputBudgetTokens
        }
        // 用**预留值**反推输入预算,而不是 `parameters.maxOutputTokens`。
        // 2026-07-25 取消四类生成任务的硬上限时,该字段变为 nil,这里的留位随之
        // 归零——输入预算可以吃到「窗口 − 1024」,反过来挤掉输出空间。那是当时
        // 未察觉的连带损伤,此处补回:上限归上限(不发给模型),留位归留位(纯本地)。
        let output = request.kind.outputReservationTokens
        let available = max(0, window - output - 1_024)
        guard available > 0 else {
            throw NovelError.injectionBudgetExceeded(
                required: 1,
                limit: available,
                items: [NovelInjectionBudgetItem(
                    label: "Minimum input context",
                    estimatedTokens: 1
                )]
            )
        }
        return min(request.inputBudgetTokens, available)
    }

    func mapInjectionError(_ error: Error) -> NovelError {
        guard let error = error as? NovelInjectionPlanningError else {
            return .invalidInput(error.localizedDescription)
        }
        return switch error {
        case .requiredContentExceedsBudget(let limit, let estimated, let items):
            NovelError.injectionBudgetExceeded(
                required: estimated,
                limit: limit,
                items: items
            )
        case .missingBranch(let id): NovelError.branchNotFound(id)
        case .missingSession(let id): NovelError.sessionNotFound(id)
        case .missingStateSnapshot(let id): NovelError.stateSnapshotNotFound(id)
        case .missingChapterVersion(let id):
            NovelError.invalidInput("Chapter version \(id) is unavailable.")
        case .missingMaterialRevision(let id):
            NovelError.invalidInput("Material revision \(id) is unavailable.")
        case .branchRequiresSync:
            NovelError.invalidInput("The branch must be synchronized before formal generation.")
        case .invalidInput(let message): NovelError.invalidInput(message)
        }
    }

    func failure(from error: Error, fallbackCode: String) -> NovelFailure {
        if let failure = error as? NovelModelFailure {
            return NovelFailure(
                code: failure.code,
                message: failure.message,
                isRetryable: failure.isRetryable
            )
        }
        return NovelFailure(
            code: fallbackCode,
            message: error.localizedDescription,
            isRetryable: true
        )
    }

    func yieldReplayTerminal(
        _ run: NovelActiveRunRecord,
        document: NovelProjectDocumentV1,
        to continuation: AsyncStream<NovelRunEvent>.Continuation
    ) {
        let message = document.sessions
            .first(where: { $0.id == run.sessionID })?
            .messages
            .first(where: { $0.id == run.messageID })
        let snapshot = message.map {
            NovelSessionMessageSnapshot(
                projectID: document.project.id,
                branchID: run.branchID,
                message: $0
            )
        }
        switch run.status {
        case .running:
            break
        case .completed:
            if let snapshot { continuation.yield(.completed(snapshot)) }
        case .interrupted:
            continuation.yield(.interrupted(snapshot))
        case .failed:
            continuation.yield(.failed(run.terminalFailure ?? NovelFailure(
                code: "generation_failed",
                message: "Novel generation failed.",
                isRetryable: true
            )))
        }
    }
}
