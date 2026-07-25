import Foundation

extension DefaultNovelCreation {
    func executeCollectCandidate(
        _ command: NovelCollectCandidateCommand
    ) async throws -> NovelOutcome {
        let payloadSHA256 = try command.canonicalPayloadSHA256()
        let loaded = try await loadCommittedProject(id: command.projectID)
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: command.projectID)
        }
        if let replay = try NovelFactTransactionReducer.replayOutcome(
            context: command.context,
            kind: .collectCandidate,
            payloadSHA256: payloadSHA256,
            in: loaded.document
        ) {
            return replay
        }

        let committed = try NovelFactTransactionReducer.commitCollectionWithoutStateSync(
            command,
            payloadSHA256: payloadSHA256,
            in: loaded.document,
            now: now()
        )
        do {
            _ = try await commitFactDocument(committed.document, replacing: loaded)
            return committed.outcome
        } catch {
            if let replay = try await reconcileFactOutcome(
                projectID: command.projectID,
                context: command.context,
                kind: .collectCandidate,
                payloadSHA256: payloadSHA256
            ) {
                return replay
            }
            throw error
        }
    }

    func executeSyncManualEdits(
        _ command: NovelSyncManualEditsCommand
    ) async throws -> NovelOutcome {
        let payloadSHA256 = try command.canonicalPayloadSHA256()
        let loaded = try await loadCommittedProject(id: command.projectID)
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: command.projectID)
        }
        if let replay = try NovelFactTransactionReducer.replayOutcome(
            context: command.context,
            kind: .syncManualEdits,
            payloadSHA256: payloadSHA256,
            in: loaded.document
        ) {
            return replay
        }

        let prepared = try NovelFactTransactionReducer.prepareManualSync(
            command,
            payloadSHA256: payloadSHA256,
            in: loaded.document,
            now: now()
        )
        guard prepared.pending.status == .pending else {
            throw NovelError.invalidInput(
                "This synchronization previously failed and must be resumed with its retry action."
            )
        }
        let pendingLoaded = try await commitFactDocument(
            prepared.document,
            replacing: loaded
        )
        return try await executeManualSyncTransaction(
            projectID: command.projectID,
            pendingID: command.pendingID,
            retryCommand: nil,
            replayContext: command.context,
            replayKind: .syncManualEdits,
            replayPayloadSHA256: payloadSHA256,
            pendingDocument: pendingLoaded.document
        )
    }

    func executeRetryPending(
        _ command: NovelRetryPendingCommand
    ) async throws -> NovelOutcome {
        let payloadSHA256 = try command.canonicalPayloadSHA256()
        let loaded = try await loadCommittedProject(id: command.projectID)
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: command.projectID)
        }
        if let replay = try NovelFactTransactionReducer.replayOutcome(
            context: command.context,
            kind: .retryPending,
            payloadSHA256: payloadSHA256,
            in: loaded.document
        ) {
            return replay
        }
        let pending = try NovelFactTransactionReducer.validateRetryCommand(
            command,
            in: loaded.document
        )
        guard let branch = loaded.document.branches.first(where: {
            $0.id == pending.branchID
        }) else {
            throw NovelError.branchNotFound(pending.branchID)
        }
        guard branch.activeRunID == nil else {
            throw NovelError.projectBusy(command.projectID)
        }
        if pending.kind == .collection {
            let committed = try NovelFactTransactionReducer.recoverPendingCollectionWithoutStateSync(
                command,
                in: loaded.document,
                now: now()
            )
            do {
                _ = try await commitFactDocument(committed.document, replacing: loaded)
                return committed.outcome
            } catch {
                if let replay = try await reconcileFactOutcome(
                    projectID: command.projectID,
                    context: command.context,
                    kind: .retryPending,
                    payloadSHA256: payloadSHA256
                ) {
                    return replay
                }
                throw error
            }
        }
        let needsProviderRequest: Bool
        if let progress = pending.manualSyncProgress {
            let input = try NovelFactTransactionReducer.manualRebuildInput(
                pendingID: pending.id,
                in: loaded.document
            )
            needsProviderRequest = !NovelManualSyncProgressReducer.isComplete(
                progress,
                manuscript: input.manuscript
            )
        } else { needsProviderRequest = true }
        let reservedLoaded: NovelLoadedProject
        if needsProviderRequest {
            let reservedDocument = try NovelManualSyncProgressReducer.reserveRetryAttempt(
                command,
                pending: pending,
                in: loaded.document,
                now: now()
            )
            reservedLoaded = try await commitFactDocument(
                reservedDocument,
                replacing: loaded
            )
        } else {
            // A completed manual-sync progress record already owns all model evidence.
            // This retry only publishes the durable checkpoint, so it must not claim a
            // fact attempt or imply that another provider request was dispatched.
            reservedLoaded = loaded
        }
        guard let reservedPending = reservedLoaded.document.pendingOperations.first(where: {
            $0.id == pending.id
        }) else {
            throw NovelError.invalidInput("The retry reservation lost its pending operation.")
        }

        guard reservedPending.kind == .manualSync else {
            throw NovelError.invalidInput("Only material synchronization can reach model retry.")
        }
        return try await executeManualSyncTransaction(
            projectID: command.projectID,
            pendingID: command.pendingID,
            retryCommand: command,
            replayContext: command.context,
            replayKind: .retryPending,
            replayPayloadSHA256: payloadSHA256,
            pendingDocument: reservedLoaded.document
        )
    }
}

private extension DefaultNovelCreation {
    /// 注意：本函数当前无生产调用点（死代码）。它是「收录后只跑一次 .stateDelta」的
    /// 轻量路径，而状态同步实际走的是 executeManualSyncTransaction（多块 .stateRebuild
    /// 串行）。接线它属于行为变更，需单独设计与验证，此处只做标记、不删除、不改语义。
    func executeCollectionTransaction(
        projectID: NovelProjectID,
        pendingID: NovelPendingOperationID,
        retryCommand: NovelRetryPendingCommand?,
        replayContext: NovelMutationContext,
        replayKind: NovelOperationKind,
        replayPayloadSHA256: String,
        pendingDocument: NovelProjectDocumentV1
    ) async throws -> NovelOutcome {
        guard let pending = pendingDocument.pendingOperations.first(where: {
            $0.id == pendingID && $0.kind == .collection
        }) else {
            throw NovelError.invalidInput("The pending collection is unavailable.")
        }
        do {
            let extractionInput = try NovelFactTransactionReducer.collectionExtractionInput(
                pendingID: pendingID,
                in: pendingDocument
            )
            let executor = NovelStructuredModelExecutor(modelRunner: modelRunner)
            let stateSyncPolicy = modelPolicy(for: .stateSync, in: pendingDocument)
            let preparation = try await executor.prepare(
                modelPolicy: stateSyncPolicy,
                taskKind: .stateDelta,
                requestedInputBudgetTokens: NovelStructuredModelExecutor
                    .maximumInternalInputBudgetTokens
            )
            let plan = try NovelInjectionPlanner.plan(
                document: pendingDocument,
                request: NovelInjectionPlanningRequest(
                    branchID: pending.branchID,
                    promptKind: .stateDeltaV1,
                    userText: extractionInput.manuscript,
                    stateSnapshotIDOverride: extractionInput.baseStateSnapshot.id,
                    sessionCursorLimit: extractionInput.pending.sessionCursor,
                    budget: factInjectionBudget(
                        maxEstimatedInputTokens: preparation.effectiveInputBudgetTokens
                    )
                )
            )
            let attemptStartedAt = now()
            let attempt = try NovelFactTransactionAttempt(
                pending: pending,
                retryCommand: retryCommand
            )
            let invocation = try executor.prepareInvocation(
                NovelStructuredModelExecutionRequest(
                    runID: NovelRunID(),
                    modelPolicy: stateSyncPolicy,
                    task: .stateDelta(
                        context: plan.contextText,
                        manuscript: extractionInput.manuscript
                    )
                ),
                preparation: preparation
            )
            let receipts = makeFactReceipts(
                projectID: projectID,
                branchID: pending.branchID,
                pending: pending,
                attempt: attempt,
                kind: .stateDelta,
                plan: plan,
                invocation: invocation,
                requestedInputBudgetTokens: preparation.requestedInputBudgetTokens,
                createdAt: attemptStartedAt
            )

            try Task.checkCancellation()
            let beforeRequest = try await reloadFactDocument(projectID: projectID)
            try validatePendingGuard(pending, in: beforeRequest.document)
            let requestDocument = try NovelFactRequestReceiptReducer.reserve(
                receipts,
                pendingID: pendingID,
                in: beforeRequest.document,
                now: now()
            )
            _ = try await commitFactDocument(
                requestDocument,
                replacing: beforeRequest
            )
            try Task.checkCancellation()
            let execution = try await executor.executePrepared(
                invocation,
                timeout: factRequestTimeout
            )
            guard case .stateDelta(let delta) = execution.output else {
                throw NovelError.invalidInput("The collection returned the wrong structured result.")
            }

            let reloaded = try await reloadFactDocument(projectID: projectID)
            try validatePendingGuard(pending, in: reloaded.document)
            let finalized = try NovelFactTransactionReducer.finalizeCollection(
                pendingID: pendingID,
                delta: delta,
                retryCommand: retryCommand,
                artifacts: receipts,
                in: reloaded.document,
                now: now()
            )
            do {
                _ = try await commitFactDocument(
                    finalized.document,
                    replacing: reloaded
                )
                return finalized.outcome
            } catch {
                if let replay = try await reconcileFactOutcome(
                    projectID: projectID,
                    context: replayContext,
                    kind: replayKind,
                    payloadSHA256: replayPayloadSHA256
                ) {
                    return replay
                }
                throw error
            }
        } catch {
            await markPendingRetryable(
                projectID: projectID,
                pendingID: pendingID,
                error: error
            )
            throw error
        }
    }

    func executeManualSyncTransaction(
        projectID: NovelProjectID,
        pendingID: NovelPendingOperationID,
        retryCommand: NovelRetryPendingCommand?,
        replayContext: NovelMutationContext,
        replayKind: NovelOperationKind,
        replayPayloadSHA256: String,
        pendingDocument: NovelProjectDocumentV1
    ) async throws -> NovelOutcome {
        guard let pending = pendingDocument.pendingOperations.first(where: {
            $0.id == pendingID && $0.kind == .manualSync
        }) else {
            throw NovelError.invalidInput("The pending manual synchronization is unavailable.")
        }
        do {
            let executor = NovelStructuredModelExecutor(modelRunner: modelRunner)
            let attempt = try NovelFactTransactionAttempt(
                pending: pending,
                retryCommand: retryCommand
            )
            var currentDocument = pendingDocument
            var lockedPreparation: NovelStructuredModelPreparation?
            var committedChunkInThisInvocation = false
            while true {
                try Task.checkCancellation()
                guard let currentPending = currentDocument.pendingOperations.first(where: {
                    $0.id == pendingID && $0.kind == .manualSync
                }) else {
                    throw NovelError.invalidInput("Manual synchronization progress disappeared.")
                }
                let rebuildInput = try NovelFactTransactionReducer.manualRebuildInput(
                    pendingID: pendingID,
                    in: currentDocument
                )
                if let progress = currentPending.manualSyncProgress,
                   NovelManualSyncProgressReducer.isComplete(
                       progress,
                       manuscript: rebuildInput.manuscript
                   ) {
                    break
                }

                let preparation: NovelStructuredModelPreparation
                if let progress = currentPending.manualSyncProgress {
                    preparation = progress.preparation
                    lockedPreparation = preparation
                } else if let lockedPreparation {
                    preparation = lockedPreparation
                } else {
                    let stateSyncPolicy = modelPolicy(for: .stateSync, in: currentDocument)
                    preparation = try await executor.prepare(
                        modelPolicy: stateSyncPolicy,
                        taskKind: .stateRebuild,
                        requestedInputBudgetTokens: NovelStructuredModelExecutor
                            .maximumInternalInputBudgetTokens
                    )
                    lockedPreparation = preparation
                }

                let progress = currentPending.manualSyncProgress
                let projectedState = try NovelManualSyncChunker.projectedStateContext(
                    baseState: rebuildInput.baseStateSnapshot,
                    accumulated: progress?.accumulatedRebuild
                )
                let chunkIndex = progress?.nextChunkIndex ?? 0
                let selection = try NovelManualSyncChunker.selectNext(
                    manuscript: rebuildInput.manuscript,
                    consumedCharacterCount: progress?.consumedCharacterCount ?? 0,
                    chunkIndex: chunkIndex,
                    projectedStateContext: projectedState
                ) { modelInput in
                    try NovelInjectionPlanner.plan(
                        document: currentDocument,
                        request: NovelInjectionPlanningRequest(
                            branchID: currentPending.branchID,
                            promptKind: .manualSyncV1,
                            userText: modelInput,
                            stateSnapshotIDOverride: rebuildInput.baseStateSnapshot.id,
                            sessionCursorLimit: .empty,
                            includeUnsynchronizedStateWarning: false,
                            pendingState: NovelPendingStateInjection(
                                pendingID: pendingID,
                                chunkIndex: chunkIndex,
                                content: projectedState
                            ),
                            budget: factInjectionBudget(
                                maxEstimatedInputTokens: preparation
                                    .effectiveInputBudgetTokens
                            )
                        )
                    )
                }
                let attemptStartedAt = now()
                try Task.checkCancellation()
                let invocation = try executor.prepareInvocation(
                    NovelStructuredModelExecutionRequest(
                        runID: NovelRunID(),
                        modelPolicy: preparation.modelPolicy,
                        task: .stateRebuild(
                            baseContext: selection.plan.contextText,
                            manuscript: selection.modelInput
                        )
                    ),
                    preparation: preparation
                )
                let receipts = makeFactReceipts(
                    projectID: projectID,
                    branchID: currentPending.branchID,
                    pending: currentPending,
                    attempt: attempt,
                    kind: .manualRebuild,
                    chunkIndex: chunkIndex,
                    plan: selection.plan,
                    invocation: invocation,
                    requestedInputBudgetTokens: preparation.requestedInputBudgetTokens,
                    createdAt: attemptStartedAt
                )

                let beforeRequest = try await reloadFactDocument(projectID: projectID)
                try validatePendingGuard(currentPending, in: beforeRequest.document)
                let requestDocument = try NovelFactRequestReceiptReducer.reserve(
                    receipts,
                    pendingID: pendingID,
                    in: beforeRequest.document,
                    now: now()
                )
                currentDocument = try await commitFactDocument(
                    requestDocument,
                    replacing: beforeRequest
                ).document
                try Task.checkCancellation()
                // 用「连续无输出」超时而非绝对墙钟：结构化状态同步是长生成任务，
                // 模型积极流式输出时也可能超过 factRequestTimeout；与讨论归档/议会主持人
                // 综合保持同一超时语义，任意有效增量都会刷新计时，只在真正卡死时才判超时。
                let execution = try await executor.executePrepared(
                    invocation,
                    noOutputTimeout: factRequestTimeout
                )
                guard case .stateRebuild(let rebuild) = execution.output else {
                    throw NovelError.invalidInput(
                        "Manual synchronization returned the wrong structured result."
                    )
                }

                let reloaded = try await reloadFactDocument(projectID: projectID)
                try validatePendingGuard(currentPending, in: reloaded.document)
                let progressed = try NovelManualSyncProgressReducer.commitChunk(
                    pendingID: pendingID,
                    selection: selection,
                    rebuild: rebuild,
                    preparation: preparation,
                    attempt: attempt,
                    artifacts: receipts,
                    in: reloaded.document,
                    now: now()
                )
                currentDocument = try await commitFactDocument(
                    progressed,
                    replacing: reloaded
                ).document
                committedChunkInThisInvocation = true
                try Task.checkCancellation()
            }

            guard let completedPending = currentDocument.pendingOperations.first(where: {
                $0.id == pendingID
            }), let progress = completedPending.manualSyncProgress else {
                throw NovelError.invalidInput("Manual synchronization has no completed progress.")
            }
            let reloaded = try await reloadFactDocument(projectID: projectID)
            try validatePendingGuard(completedPending, in: reloaded.document)
            let hasDurableAttempt = reloaded.document.factAttempts.contains(where: {
                $0.pendingID == pendingID &&
                    $0.attemptOperationID == attempt.operationID &&
                    $0.attemptPayloadSHA256 == attempt.payloadSHA256
            })
            try Task.checkCancellation()
            let finalized = try NovelFactTransactionReducer.finalizeManualSync(
                pendingID: pendingID,
                rebuild: progress.accumulatedRebuild,
                retryCommand: committedChunkInThisInvocation || hasDurableAttempt
                    ? nil
                    : retryCommand,
                validatedAttempt: committedChunkInThisInvocation || hasDurableAttempt
                    ? attempt
                    : nil,
                in: reloaded.document,
                now: now()
            )
            do {
                _ = try await commitFactDocument(
                    finalized.document,
                    replacing: reloaded
                )
                return finalized.outcome
            } catch {
                if let replay = try await reconcileFactOutcome(
                    projectID: projectID,
                    context: replayContext,
                    kind: replayKind,
                    payloadSHA256: replayPayloadSHA256
                ) {
                    return replay
                }
                throw error
            }
        } catch {
            await markPendingRetryable(
                projectID: projectID,
                pendingID: pendingID,
                error: error
            )
            throw error
        }
    }

    func makeFactReceipts(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        pending: NovelPendingOperationRecord,
        attempt: NovelFactTransactionAttempt,
        kind: NovelFactReceiptKind,
        chunkIndex: Int? = nil,
        plan: NovelInjectionPlan,
        invocation: NovelStructuredModelInvocation,
        requestedInputBudgetTokens: Int,
        createdAt: Date
    ) -> NovelFactTransactionReceiptArtifacts {
        let link = NovelFactReceiptLink(
            pendingID: pending.id,
            ownerOperationID: pending.operationID,
            attemptOperationID: attempt.operationID,
            attemptPayloadSHA256: attempt.payloadSHA256,
            kind: kind,
            chunkIndex: chunkIndex
        )
        let injectionID = NovelReceiptID()
        let injection = NovelInjectionReceiptRecord(
            id: injectionID,
            runID: invocation.modelRequest.runID,
            projectID: projectID,
            branchID: branchID,
            plan: plan,
            overrides: .none,
            providerID: invocation.resolvedModel.providerID,
            ownerProviderID: invocation.resolvedModel.ownerProviderID,
            modelID: invocation.resolvedModel.modelID,
            wireModelID: invocation.resolvedModel.wireModelID,
            parameters: invocation.parameters,
            requestedInputBudgetTokens: requestedInputBudgetTokens,
            factTransaction: link,
            createdAt: createdAt
        )
        let generation = NovelGenerationReceiptRecord(
            id: NovelReceiptID(),
            runID: invocation.modelRequest.runID,
            providerID: invocation.resolvedModel.providerID,
            ownerProviderID: invocation.resolvedModel.ownerProviderID,
            modelID: invocation.resolvedModel.modelID,
            wireModelID: invocation.resolvedModel.wireModelID,
            promptVersion: plan.prompt.version,
            injectionReceiptID: injectionID,
            parameters: invocation.parameters,
            requestSHA256: invocation.requestSHA256,
            createdAt: createdAt,
            factTransaction: link
        )
        return NovelFactTransactionReceiptArtifacts(
            injectionReceipt: injection,
            generationReceipt: generation
        )
    }

    func factInjectionBudget(maxEstimatedInputTokens: Int) -> NovelInjectionBudget {
        NovelInjectionBudget(
            maxEstimatedInputTokens: maxEstimatedInputTokens,
            chapterTailCharacterLimit: NovelInjectionBudget.standard.chapterTailCharacterLimit,
            maximumRecentSessionMessages: NovelInjectionBudget.standard.maximumRecentSessionMessages
        )
    }

    func commitFactDocument(
        _ document: NovelProjectDocumentV1,
        replacing loaded: NovelLoadedProject
    ) async throws -> NovelLoadedProject {
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: document.project.id)
        }
        if document == loaded.document { return loaded }
        let committed = try await repository.commitProject(
            document,
            expectedRevision: loaded.document.project.revision
        )
        guard committed.document == document else {
            frozenProjectIDs.insert(document.project.id)
            throw NovelError.storageIndeterminate(document.project.id)
        }
        return try installLoadedProject(
            committed,
            id: document.project.id,
            allowsRollback: false
        )
    }

    func reloadFactDocument(projectID: NovelProjectID) async throws -> NovelLoadedProject {
        let loaded = try await repository.loadProject(id: projectID)
        return try installLoadedProject(
            loaded,
            id: projectID,
            allowsRollback: false
        )
    }

    func validatePendingGuard(
        _ expected: NovelPendingOperationRecord,
        in document: NovelProjectDocumentV1
    ) throws {
        guard let current = document.pendingOperations.first(where: {
            $0.id == expected.id
        }), current == expected else {
            throw NovelError.invalidInput("The pending novel operation changed while awaiting the model.")
        }
    }

    func reconcileFactOutcome(
        projectID: NovelProjectID,
        context: NovelMutationContext,
        kind: NovelOperationKind,
        payloadSHA256: String
    ) async throws -> NovelOutcome? {
        let loaded = try await repository.loadProject(id: projectID)
        guard let outcome = try NovelFactTransactionReducer.replayOutcome(
            context: context,
            kind: kind,
            payloadSHA256: payloadSHA256,
            in: loaded.document
        ) else {
            return nil
        }
        _ = try installLoadedProject(
            loaded,
            id: projectID,
            allowsRollback: true
        )
        return outcome
    }

    func markPendingRetryable(
        projectID: NovelProjectID,
        pendingID: NovelPendingOperationID,
        error: Error
    ) async {
        do {
            let loaded = try await repository.loadProject(id: projectID)
            guard loaded.document.pendingOperations.contains(where: {
                $0.id == pendingID
            }) else {
                return
            }
            let reduced = try NovelFactTransactionReducer.markRetryable(
                pendingID: pendingID,
                message: factFailureMessage(error),
                in: loaded.document,
                now: now()
            )
            _ = try await commitFactDocument(reduced, replacing: loaded)
        } catch {
            // The prepared pending record remains the durable recovery point.
            // A later retry reloads storage instead of publishing uncommitted final state.
        }
    }

    func factFailureMessage(_ error: Error) -> String {
        if let failure = error as? NovelStructuredModelExecutionFailure {
            return failure.failure.message
        }
        if error is CancellationError {
            return "The fact synchronization was cancelled and can be retried."
        }
        if case .invalidInput(let detail) = error as? NovelError {
            return detail
        }
        return error.localizedDescription
    }
}
