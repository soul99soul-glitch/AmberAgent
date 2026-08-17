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

        // 续写/末章：host 写 plot/ 并标 synchronized，不再抽 JSON。
        // 改中间章仍走原 inline stateDelta / withoutSync。
        if canWorkspaceFastForwardCollect(command, in: loaded.document) {
            return try await executeWorkspaceFastForwardCollect(
                command,
                payloadSHA256: payloadSHA256,
                loaded: loaded
            )
        }

        // 代笔自动收录且分支已同步：一次 stateDelta 直接落新 state，避免
        //「先无状态收录 → 多 chunk rebuild」的慢路径。失败再回退 withoutSync+rebuild。
        if prefersInlineStateDeltaCollect(command, in: loaded.document) {
            do {
                return try await executeInlineStateDeltaCollect(
                    command,
                    payloadSHA256: payloadSHA256,
                    loaded: loaded
                )
            } catch is CancellationError {
                throw CancellationError()
            } catch let failure as NovelStructuredModelExecutionFailure
                where failure.failure.code == "cancelled" {
                throw failure
            } catch is NovelStructuredModelExecutionFailure {
                return try await executeCollectionWithoutStateSyncFallback(
                    command,
                    payloadSHA256: payloadSHA256
                )
            }
        }

        return try await commitCollectionWithoutStateSync(
            command,
            payloadSHA256: payloadSHA256,
            loaded: loaded
        )
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
        switch prepared {
        case .completed(let document, let outcome):
            _ = try await commitFactDocument(document, replacing: loaded)
            return outcome
        case .needsModel(let document, let pending):
            guard pending.status == .pending else {
                throw NovelError.invalidInput(
                    "上次同步已中断，请点「重试同步」继续。"
                )
            }
            let pendingLoaded = try await commitFactDocument(
                document,
                replacing: loaded
            )
        return try await executeManualSyncTransaction(
            projectID: command.projectID,
            pendingID: command.pendingID,
            retryCommand: nil,
            replayContext: command.context,
            replayKind: .syncManualEdits,
            replayPayloadSHA256: payloadSHA256,
            pendingDocument: pendingLoaded.document,
            preferStateDelta: command.preferStateDelta
        )
        }
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
            pendingDocument: reservedLoaded.document,
            preferStateDelta: false
        )
    }
}

private extension DefaultNovelCreation {
    func canWorkspaceFastForwardCollect(
        _ command: NovelCollectCandidateCommand,
        in document: NovelProjectDocumentV1
    ) -> Bool {
        prefersInlineStateDeltaCollect(command, in: document)
    }

    func executeWorkspaceFastForwardCollect(
        _ command: NovelCollectCandidateCommand,
        payloadSHA256: String,
        loaded: NovelLoadedProject
    ) async throws -> NovelOutcome {
        let committed = try NovelFactTransactionReducer.commitCollectionWithoutStateSync(
            command,
            payloadSHA256: payloadSHA256,
            in: loaded.document,
            now: now()
        )
        let collectedLoaded: NovelLoadedProject
        do {
            collectedLoaded = try await commitFactDocument(committed.document, replacing: loaded)
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
        let chapterID: NovelChapterID
        switch command.target {
        case .createNextChapter(let id, _):
            chapterID = id
        case .replaceChapter(let id), .appendToChapter(let id):
            chapterID = id
        }
        guard let selection = collectedLoaded.document.branches
            .first(where: { $0.id == command.branchID })?
            .workingChapterSelections
            .first(where: { $0.chapterID == chapterID }),
              let version = collectedLoaded.document.chapterVersions.first(where: {
                  $0.id == selection.versionID && $0.chapterID == chapterID
              }) else {
            throw NovelError.invalidInput("The collected chapter is missing from the working manuscript.")
        }
        let next: NovelProjectDocumentV1
        do {
            next = try await applyChapterPlotPointer(
                to: collectedLoaded.document,
                branchID: command.branchID,
                chapterID: chapterID,
                chapterTitle: version.title,
                chapterContent: version.content
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            next = try NovelWorkspacePlotCommit.applyChapterModule(
                to: collectedLoaded.document,
                branchID: command.branchID,
                chapterID: chapterID,
                chapterTitle: version.title,
                chapterContent: version.content,
                now: now()
            )
        }
        _ = try await commitFactDocument(next, replacing: collectedLoaded)
        return committed.outcome
    }

    /// 代笔自动收录或共创点收录：分支已同步、无挂起事务、无在途 run 时优先单章 stateDelta。
    func prefersInlineStateDeltaCollect(
        _ command: NovelCollectCandidateCommand,
        in document: NovelProjectDocumentV1
    ) -> Bool {
        guard command.source == .systemAutoCollect || command.source == .user else {
            return false
        }
        guard let branch = document.branches.first(where: { $0.id == command.branchID }) else {
            return false
        }
        guard branch.syncStatus == .synchronized,
              branch.activeRunID == nil,
              branch.lifecycle == .active else {
            return false
        }
        return !document.pendingOperations.contains(where: { $0.branchID == branch.id })
    }

    func executeInlineStateDeltaCollect(
        _ command: NovelCollectCandidateCommand,
        payloadSHA256: String,
        loaded: NovelLoadedProject
    ) async throws -> NovelOutcome {
        let prepared = try NovelFactTransactionReducer.prepareCollection(
            command,
            payloadSHA256: payloadSHA256,
            in: loaded.document,
            now: now()
        )
        guard prepared.pending.status == .pending else {
            throw NovelError.invalidInput(
                "This collection previously failed and must be resumed with its retry action."
            )
        }
        let pendingLoaded = try await commitFactDocument(
            prepared.document,
            replacing: loaded
        )
        return try await executeCollectionTransaction(
            projectID: command.projectID,
            pendingID: command.pendingID,
            retryCommand: nil,
            replayContext: command.context,
            replayKind: .collectCandidate,
            replayPayloadSHA256: payloadSHA256,
            pendingDocument: pendingLoaded.document
        )
    }

    /// stateDelta 失败后：若仍有 collection pending 则无状态提交（再靠 manual rebuild）；
    /// 否则按原 command 直接 withoutSync 收录。
    func executeCollectionWithoutStateSyncFallback(
        _ command: NovelCollectCandidateCommand,
        payloadSHA256: String
    ) async throws -> NovelOutcome {
        let reloaded = try await reloadFactDocument(projectID: command.projectID)
        if let replay = try NovelFactTransactionReducer.replayOutcome(
            context: command.context,
            kind: .collectCandidate,
            payloadSHA256: payloadSHA256,
            in: reloaded.document
        ) {
            return replay
        }
        if let applied = reloaded.document.appliedOperations.last(where: { record in
            if record.operationID == command.context.operationID {
                return true
            }
            guard record.kind == .collectCandidate,
                  case .candidateCollected(_, _, let candidateID, _, _, _) = record.outcome else {
                return false
            }
            return candidateID == command.candidateID
        }) {
            return applied.outcome
        }
        if reloaded.document.pendingOperations.contains(where: {
            $0.id == command.pendingID && $0.kind == .collection
        }) {
            let branch = reloaded.document.branches.first(where: { $0.id == command.branchID })
            let retry = NovelRetryPendingCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: reloaded.document.project.revision,
                    expectedConfigRevision: reloaded.document.project.configRevision,
                    expectedBranchHeadRevision: branch?.headRevision
                ),
                projectID: command.projectID,
                pendingID: command.pendingID
            )
            let committed = try NovelFactTransactionReducer.recoverPendingCollectionWithoutStateSync(
                retry,
                in: reloaded.document,
                now: now()
            )
            do {
                _ = try await commitFactDocument(committed.document, replacing: reloaded)
                return committed.outcome
            } catch {
                if let replay = try await reconcileFactOutcome(
                    projectID: command.projectID,
                    context: retry.context,
                    kind: .retryPending,
                    payloadSHA256: try retry.canonicalPayloadSHA256()
                ) {
                    return replay
                }
                throw error
            }
        }
        return try await commitCollectionWithoutStateSync(
            command,
            payloadSHA256: payloadSHA256,
            loaded: reloaded
        )
    }

    func commitCollectionWithoutStateSync(
        _ command: NovelCollectCandidateCommand,
        payloadSHA256: String,
        loaded: NovelLoadedProject
    ) async throws -> NovelOutcome {
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

    /// 单章 stateDelta 收录：一次结构化抽取后带新 state 提交，分支保持 synchronized。
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
            return try await executeStateDeltaAllowingRepair(
                executor: executor,
                preparation: preparation,
                invocation: invocation,
                manuscript: extractionInput.manuscript,
                contextText: plan.contextText
            ) { delta, acceptEmptyFacts in
                let reloaded = try await reloadFactDocument(projectID: projectID)
                try validatePendingGuard(pending, in: reloaded.document)
                let finalized = try NovelFactTransactionReducer.finalizeCollection(
                    pendingID: pendingID,
                    delta: delta,
                    retryCommand: retryCommand,
                    artifacts: receipts,
                    in: reloaded.document,
                    now: now(),
                    acceptEmptyFacts: acceptEmptyFacts
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
        pendingDocument: NovelProjectDocumentV1,
        preferStateDelta: Bool = false
    ) async throws -> NovelOutcome {
        guard let pending = pendingDocument.pendingOperations.first(where: {
            $0.id == pendingID && $0.kind == .manualSync
        }) else {
            throw NovelError.invalidInput("The pending manual synchronization is unavailable.")
        }
        // 段内流式字数是呈现层遥测：入口先清掉上一次调用可能残留的值（超时/取消后
        // 迟到的 delta 可能在 defer 清理后再写一次），退出时再清一次。
        NovelStateSyncStreamProgress.shared.clear(pendingID: pendingID)
        defer { NovelStateSyncStreamProgress.shared.clear(pendingID: pendingID) }
        do {
            let executor = NovelStructuredModelExecutor(modelRunner: modelRunner)
            let attempt = try NovelFactTransactionAttempt(
                pending: pending,
                retryCommand: retryCommand
            )
            if preferStateDelta {
                let rebuildInput = try NovelFactTransactionReducer.manualRebuildInput(
                    pendingID: pendingID,
                    in: pendingDocument
                )
                if rebuildInput.chapters.count == 1 {
                    return try await executeSingleChapterDeltaThenFinalize(
                        projectID: projectID,
                        pendingID: pendingID,
                        retryCommand: retryCommand,
                        replayContext: replayContext,
                        replayKind: replayKind,
                        replayPayloadSHA256: replayPayloadSHA256,
                        pending: pending,
                        rebuildInput: rebuildInput,
                        executor: executor,
                        attempt: attempt
                    )
                }
            }
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
                    // 只比较策略（`NovelProjectModelPolicy`,纯内存、无网络/配置查找),不比较完整
                    // preparation:`executor.prepare` 可能触发实际的模型解析成本,只有确认策略
                    // 变化后才值得付出这个成本。策略未变时沿用锁定进度,是零回归的原有行为。
                    let stateSyncPolicy = modelPolicy(for: .stateSync, in: currentDocument)
                    if stateSyncPolicy == progress.modelPolicy {
                        preparation = progress.preparation
                        lockedPreparation = preparation
                    } else {
                        // 用户改了「剧情同步模型」：丢弃锁定在旧模型上的进度,不与旧分块混合,
                        // 用新策略重新 prepare 后从第 0 块重新开始。跨模型不混合分块的不变量仍由
                        // commitChunk 里 `existingProgress.preparation == preparation` 的守卫保证——
                        // 这里只是让"没有旧进度可比对"的重新开始成为可能。
                        let freshPreparation = try await executor.prepare(
                            modelPolicy: stateSyncPolicy,
                            taskKind: .stateRebuild,
                            requestedInputBudgetTokens: NovelStructuredModelExecutor
                                .maximumInternalInputBudgetTokens
                        )
                        let reloadedForReset = try await reloadFactDocument(projectID: projectID)
                        try validatePendingGuard(currentPending, in: reloadedForReset.document)
                        let resetDocument = try NovelManualSyncProgressReducer.resetProgress(
                            pendingID: pendingID,
                            in: reloadedForReset.document,
                            now: now()
                        )
                        currentDocument = try await commitFactDocument(
                            resetDocument,
                            replacing: reloadedForReset
                        ).document
                        lockedPreparation = freshPreparation
                        try Task.checkCancellation()
                        continue
                    }
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
                            manuscript: NovelManualSyncChunker.modelInput(
                                chunk: selection.manuscript,
                                index: selection.index,
                                previousError: committedChunkInThisInvocation
                                    ? nil
                                    : currentPending.lastError
                            )
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
                // 每段请求前清零：否则段间间隔里 banner 会把上一段的最终字数
                // 安到「正在处理第 N+1 段」头上，新段首个 delta 到达时可见回跳。
                NovelStateSyncStreamProgress.shared.set(pendingID: pendingID, characters: 0)
                let rebuild = try await executeStateRebuildAllowingRepair(
                    executor: executor,
                    preparation: preparation,
                    invocation: invocation,
                    selection: selection,
                    currentPending: currentPending,
                    progress: progress,
                    document: currentDocument,
                    pendingID: pendingID
                )

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

    /// Same reserved attempt: if the model emitted text that failed decode or
    /// fact validation, feed that text back instead of discarding it.
    private func executeStateRebuildAllowingRepair(
        executor: NovelStructuredModelExecutor,
        preparation: NovelStructuredModelPreparation,
        invocation initialInvocation: NovelStructuredModelInvocation,
        selection: NovelManualSyncChunkSelection,
        currentPending: NovelPendingOperationRecord,
        progress: NovelManualSyncProgress?,
        document: NovelProjectDocumentV1,
        pendingID: NovelPendingOperationID
    ) async throws -> NovelStateRebuildV1 {
        var invocation = initialInvocation
        var lastOutputText: String?
        var repairCount = 0
        while true {
            try Task.checkCancellation()
            do {
                let execution = try await executor.executePrepared(
                    invocation,
                    noOutputTimeout: factRequestTimeout,
                    onStreamedText: { characters in
                        NovelStateSyncStreamProgress.shared.set(
                            pendingID: pendingID,
                            characters: characters
                        )
                    }
                )
                guard case .stateRebuild(let decoded) = execution.output else {
                    throw NovelError.invalidInput(
                        "Manual synchronization returned the wrong structured result."
                    )
                }
                lastOutputText = encodeRebuildForRepair(decoded)
                return try NovelFactTransactionReducer.validateManualChunkOutput(
                    decoded,
                    evidenceSource: selection.manuscript,
                    accumulated: progress?.accumulatedRebuild,
                    baseState: try NovelFactTransactionReducer.manualRebuildInput(
                        pendingID: pendingID,
                        in: document
                    ).baseStateSnapshot,
                    branchID: currentPending.branchID,
                    in: document
                )
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if let failure = error as? NovelStructuredModelExecutionFailure,
                   !failure.allowsOutputRepair {
                    throw error
                }
                let previous = (error as? NovelStructuredModelExecutionFailure)?.rawText
                    ?? lastOutputText
                if repairCount >= NovelManualSyncChunker.maxOutputRepairAttempts,
                   let failure = error as? NovelStructuredModelExecutionFailure,
                   failure.failure.code == "state_facts_evidence_unmatched",
                   let lastOutputText,
                   let decoded = decodeRebuildForRepair(lastOutputText) {
                    return try NovelFactTransactionReducer.validateManualChunkOutput(
                        decoded,
                        evidenceSource: selection.manuscript,
                        accumulated: progress?.accumulatedRebuild,
                        baseState: try NovelFactTransactionReducer.manualRebuildInput(
                            pendingID: pendingID,
                            in: document
                        ).baseStateSnapshot,
                        branchID: currentPending.branchID,
                        in: document,
                        acceptEmptyFacts: true
                    )
                }
                guard repairCount < NovelManualSyncChunker.maxOutputRepairAttempts,
                      let previous,
                      !previous.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    throw error
                }
                repairCount += 1
                lastOutputText = previous
                invocation = try executor.prepareInvocation(
                    NovelStructuredModelExecutionRequest(
                        runID: NovelRunID(),
                        modelPolicy: preparation.modelPolicy,
                        task: .stateRebuild(
                            baseContext: selection.plan.contextText,
                            manuscript: NovelManualSyncChunker.repairManuscript(
                                chunk: selection.manuscript,
                                index: selection.index,
                                previousOutput: previous,
                                error: factFailureMessage(error)
                            )
                        )
                    ),
                    preparation: preparation
                )
                NovelStateSyncStreamProgress.shared.set(pendingID: pendingID, characters: 0)
            }
        }
    }

    private func encodeRebuildForRepair(_ rebuild: NovelStateRebuildV1) -> String? {
        encodeJSONForRepair(rebuild)
    }

    private func decodeRebuildForRepair(_ text: String) -> NovelStateRebuildV1? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(NovelStateRebuildV1.self, from: data)
    }

    private func encodeJSONForRepair<Value: Encodable>(_ value: Value) -> String? {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(value),
              let text = String(data: data, encoding: .utf8) else {
            return nil
        }
        return text
    }

    private func executeStateDeltaAllowingRepair<Result>(
        executor: NovelStructuredModelExecutor,
        preparation: NovelStructuredModelPreparation,
        invocation initialInvocation: NovelStructuredModelInvocation,
        manuscript: String,
        contextText: String,
        commit: (NovelStateDeltaV1, Bool) async throws -> Result
    ) async throws -> Result {
        var invocation = initialInvocation
        var lastDelta: NovelStateDeltaV1?
        var lastOutputText: String?
        var repairCount = 0
        while true {
            try Task.checkCancellation()
            do {
                let execution = try await executor.executePrepared(
                    invocation,
                    noOutputTimeout: factRequestTimeout
                )
                guard case .stateDelta(let decoded) = execution.output else {
                    throw NovelError.invalidInput("The collection returned the wrong structured result.")
                }
                lastDelta = decoded
                lastOutputText = encodeJSONForRepair(decoded)
                return try await commit(decoded, false)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                guard let failure = error as? NovelStructuredModelExecutionFailure else {
                    throw error
                }
                if !failure.allowsOutputRepair {
                    throw error
                }
                let previous = failure.rawText ?? lastOutputText
                if repairCount >= NovelManualSyncChunker.maxOutputRepairAttempts,
                   failure.failure.code == "state_facts_evidence_unmatched",
                   let lastDelta {
                    return try await commit(lastDelta, true)
                }
                guard repairCount < NovelManualSyncChunker.maxOutputRepairAttempts,
                      let previous,
                      !previous.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    throw error
                }
                repairCount += 1
                lastOutputText = previous
                invocation = try executor.prepareInvocation(
                    NovelStructuredModelExecutionRequest(
                        runID: NovelRunID(),
                        modelPolicy: preparation.modelPolicy,
                        task: .stateDelta(
                            context: contextText,
                            manuscript: NovelManualSyncChunker.repairManuscript(
                                chunk: manuscript,
                                index: 0,
                                previousOutput: previous,
                                error: factFailureMessage(error)
                            )
                        )
                    ),
                    preparation: preparation
                )
            }
        }
    }

    private func executeSingleChapterDeltaThenFinalize(
        projectID: NovelProjectID,
        pendingID: NovelPendingOperationID,
        retryCommand: NovelRetryPendingCommand?,
        replayContext: NovelMutationContext,
        replayKind: NovelOperationKind,
        replayPayloadSHA256: String,
        pending: NovelPendingOperationRecord,
        rebuildInput: NovelManualRebuildInput,
        executor: NovelStructuredModelExecutor,
        attempt: NovelFactTransactionAttempt
    ) async throws -> NovelOutcome {
        let document = try await reloadFactDocument(projectID: projectID).document
        let policy = modelPolicy(for: .stateSync, in: document)
        let preparation = try await executor.prepare(
            modelPolicy: policy,
            taskKind: .stateDelta,
            requestedInputBudgetTokens: NovelStructuredModelExecutor
                .maximumInternalInputBudgetTokens
        )
        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: pending.branchID,
                promptKind: .stateDeltaV1,
                userText: rebuildInput.manuscript,
                stateSnapshotIDOverride: rebuildInput.baseStateSnapshot.id,
                sessionCursorLimit: rebuildInput.sessionCursor,
                budget: factInjectionBudget(
                    maxEstimatedInputTokens: preparation.effectiveInputBudgetTokens
                )
            )
        )
        let invocation = try executor.prepareInvocation(
            NovelStructuredModelExecutionRequest(
                runID: NovelRunID(),
                modelPolicy: policy,
                task: .stateDelta(
                    context: plan.contextText,
                    manuscript: rebuildInput.manuscript
                )
            ),
            preparation: preparation
        )
        let receipts = makeFactReceipts(
            projectID: projectID,
            branchID: pending.branchID,
            pending: pending,
            attempt: attempt,
            kind: .manualRebuild,
            chunkIndex: 0,
            plan: plan,
            invocation: invocation,
            requestedInputBudgetTokens: preparation.requestedInputBudgetTokens,
            createdAt: now()
        )
        let beforeRequest = try await reloadFactDocument(projectID: projectID)
        try validatePendingGuard(pending, in: beforeRequest.document)
        let requestDocument = try NovelFactRequestReceiptReducer.reserve(
            receipts,
            pendingID: pendingID,
            in: beforeRequest.document,
            now: now()
        )
        _ = try await commitFactDocument(requestDocument, replacing: beforeRequest)
        return try await executeStateDeltaAllowingRepair(
            executor: executor,
            preparation: preparation,
            invocation: invocation,
            manuscript: rebuildInput.manuscript,
            contextText: plan.contextText
        ) { delta, acceptEmptyFacts in
            let mapped = NovelStateRebuildV1(
                schemaVersion: delta.schemaVersion,
                stateSummary: delta.stateSummary,
                branchOutline: delta.branchOutlinePatch ?? rebuildInput.baseStateSnapshot.branchOutline,
                events: delta.events,
                characterStates: delta.characterChanges,
                relationships: delta.relationshipChanges,
                foreshadowing: delta.foreshadowingChanges,
                unresolvedEntityNames: delta.unresolvedEntityNames,
                settingProposals: delta.settingProposals
            )
            let validated = try NovelFactTransactionReducer.validateManualChunkOutput(
                mapped,
                evidenceSource: rebuildInput.manuscript,
                accumulated: nil,
                baseState: rebuildInput.baseStateSnapshot,
                branchID: pending.branchID,
                in: try await reloadFactDocument(projectID: projectID).document,
                acceptEmptyFacts: acceptEmptyFacts
            )
            let reloaded = try await reloadFactDocument(projectID: projectID)
            try validatePendingGuard(pending, in: reloaded.document)
            let finalized = try NovelFactTransactionReducer.finalizeManualSync(
                pendingID: pendingID,
                rebuild: validated,
                retryCommand: retryCommand,
                artifacts: receipts,
                in: reloaded.document,
                now: now()
            )
            do {
                _ = try await commitFactDocument(finalized.document, replacing: reloaded)
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
        let cancellationMessage = "剧情状态同步已取消，可以重试。"
        if let failure = error as? NovelStructuredModelExecutionFailure {
            if failure.failure.code == "cancelled" {
                return cancellationMessage
            }
            // 结构化输出失败落具体中文原因：英文技术细节进 banner 会被折叠成
            // 通用文案，模型的 schema/指令遵循问题将无从诊断。
            if let outputFailure = failure.structuredOutputFailure {
                return NovelPresentation.stateSyncStructuredFailureMessage(outputFailure)
            }
            return failure.failure.message
        }
        if error is CancellationError {
            return cancellationMessage
        }
        if case .invalidInput(let detail) = error as? NovelError {
            return detail
        }
        return error.localizedDescription
    }
}
