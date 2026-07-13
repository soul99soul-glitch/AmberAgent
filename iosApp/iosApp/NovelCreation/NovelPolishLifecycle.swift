import CryptoKit
import Foundation

private enum NovelPolishRaceOutcome: Sendable {
    case succeeded(NovelStructuredModelExecutionEvidence)
    case failed(NovelFailure)
    case timedOut
}

private final class NovelPolishRaceLatch: @unchecked Sendable {
    private let lock = NSLock()
    private var outcome: NovelPolishRaceOutcome?
    private var continuation: CheckedContinuation<NovelPolishRaceOutcome, Never>?

    func resolve(_ proposed: NovelPolishRaceOutcome) {
        let waiting: CheckedContinuation<NovelPolishRaceOutcome, Never>? = lock.withLock {
            guard outcome == nil else { return nil }
            outcome = proposed
            defer { continuation = nil }
            return continuation
        }
        waiting?.resume(returning: proposed)
    }

    func wait() async -> NovelPolishRaceOutcome {
        await withCheckedContinuation { waiting in
            let resolved: NovelPolishRaceOutcome? = lock.withLock {
                if let outcome { return outcome }
                continuation = waiting
                return nil
            }
            if let resolved {
                waiting.resume(returning: resolved)
            }
        }
    }
}

extension DefaultNovelCreation {
    func executeAdoptPolishCandidate(
        _ command: NovelAdoptPolishCandidateCommand,
        finalCommitAuthorization: NovelRepositoryCommitAuthorization
    ) async throws -> NovelOutcome {
        let payloadSHA256 = try NovelAction.adoptPolishCandidate(command)
            .canonicalPayloadSHA256()
        let loaded = try await loadCommittedProject(id: command.projectID)
        if let replay = try NovelReducer.replayOutcome(
            context: command.context,
            kind: .adoptPolishCandidate,
            payloadSHA256: payloadSHA256,
            in: loaded.document
        ) {
            return replay
        }
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: command.projectID)
        }
        let prepared = try NovelPolishTransactionReducer.prepareAdoption(
            command,
            payloadSHA256: payloadSHA256,
            in: loaded.document,
            now: now()
        )
        let pendingLoaded = try await commitPolishDocument(
            prepared.document,
            replacing: loaded
        )
        guard prepared.transaction.status != .blocked else {
            throw NovelError.invalidInput(
                prepared.transaction.lastFailure?.message ?? "The polish adoption is blocked."
            )
        }
        return try await executePolishAssessment(
            transactionID: command.transactionID,
            projectID: command.projectID,
            pendingDocument: pendingLoaded.document,
            finalCommitAuthorization: finalCommitAuthorization
        )
    }
}

private extension DefaultNovelCreation {
    func executePolishAssessment(
        transactionID: NovelPendingOperationID,
        projectID: NovelProjectID,
        pendingDocument: NovelProjectDocumentV1,
        finalCommitAuthorization: NovelRepositoryCommitAuthorization
    ) async throws -> NovelOutcome {
        let currentDocument = try await recoverAbandonedPolishAttemptIfNeeded(
            transactionID: transactionID,
            projectID: projectID,
            document: pendingDocument
        )
        guard let transaction = currentDocument.polishTransactions.first(where: {
            $0.id == transactionID
        }), let candidate = currentDocument.candidates.first(where: {
            $0.id == transaction.candidateID
        }), let source = currentDocument.chapterVersions.first(where: {
            $0.id == transaction.sourceChapterVersionID
        }) else {
            throw NovelError.invalidInput("The polish transaction inputs are unavailable.")
        }

        do {
            try NovelPolishTransactionReducer.validateCurrentTransaction(
                transactionID: transaction.id,
                in: currentDocument
            )
        } catch {
            if isTemporaryPolishGuardFailure(error) {
                throw error
            }
            let failure = NovelFailure(
                code: "polish_source_changed",
                message: error.localizedDescription,
                isRetryable: false
            )
            try await persistPolishPreflightFailure(
                transactionID: transaction.id,
                projectID: projectID,
                failure: failure
            )
            throw error
        }

        let executor = NovelStructuredModelExecutor(modelRunner: modelRunner)
        let preparation: NovelStructuredModelPreparation
        let invocation: NovelStructuredModelInvocation
        do {
            preparation = try await executor.prepare(
                modelPolicy: currentDocument.project.modelPolicy,
                taskKind: .polishDrift,
                requestedInputBudgetTokens: NovelStructuredModelExecutor
                    .maximumInternalInputBudgetTokens
            )
            invocation = try executor.prepareInvocation(
                NovelStructuredModelExecutionRequest(
                    runID: deterministicRunID(
                        projectID: projectID,
                        transactionID: transaction.id,
                        attemptIndex: transaction.attemptCount
                    ),
                    modelPolicy: currentDocument.project.modelPolicy,
                    task: .polishDrift(
                        sourceChapter: source.content,
                        candidate: candidate.content
                    )
                ),
                preparation: preparation
            )
            let evidencePlan = exactPolishPlan(
                source: source,
                candidate: candidate,
                maxEstimatedInputTokens: preparation.effectiveInputBudgetTokens
            )
            let requiredTokens = max(
                estimatedInputTokens(invocation.modelRequest.messages),
                evidencePlan.estimatedInputTokens
            )
            guard requiredTokens <= preparation.effectiveInputBudgetTokens else {
                throw NovelError.injectionBudgetExceeded(
                    required: requiredTokens,
                    limit: preparation.effectiveInputBudgetTokens,
                    items: [NovelInjectionBudgetItem(
                        label: "Complete source chapter and polish candidate",
                        estimatedTokens: requiredTokens
                    )]
                )
            }
            try Task.checkCancellation()
        } catch {
            let failure = polishFailure(from: error, fallbackCode: "polish_preflight_failed")
            try await persistPolishPreflightFailure(
                transactionID: transaction.id,
                projectID: projectID,
                failure: failure
            )
            throw error
        }

        let attemptIndex = transaction.attemptCount
        let artifacts = makePolishArtifacts(
            transaction: transaction,
            attemptIndex: attemptIndex,
            source: source,
            candidate: candidate,
            invocation: invocation,
            preparation: preparation,
            projectID: projectID,
            createdAt: now()
        )
        let beforeRequest = try await reloadPolishDocument(projectID: projectID)
        let requestDocument: NovelProjectDocumentV1
        do {
            requestDocument = try NovelPolishTransactionReducer.reserveAttempt(
                artifacts,
                transactionID: transaction.id,
                in: beforeRequest.document,
                now: now()
            )
        } catch {
            if isTemporaryPolishGuardFailure(error) {
                throw error
            }
            let failure = NovelFailure(
                code: "polish_source_changed",
                message: error.localizedDescription,
                isRetryable: false
            )
            try await persistPolishPreflightFailure(
                transactionID: transaction.id,
                projectID: projectID,
                failure: failure
            )
            throw error
        }
        _ = try await commitPolishDocument(requestDocument, replacing: beforeRequest)

        let race = await racePolishAssessment(
            executor: executor,
            invocation: invocation,
            timeout: polishAssessmentTimeout
        )
        if let replay = try await cancelledPolishOutcomeIfNeeded(
            transaction: transaction,
            attemptIndex: attemptIndex,
            runID: invocation.executionRequest.runID,
            projectID: projectID
        ) {
            return replay
        }
        switch race {
        case .succeeded(let evidence):
            guard case .polishDrift(let result) = evidence.output else {
                let failure = NovelFailure(
                    code: "invalid_polish_assessment",
                    message: "The polish assessment returned the wrong structured result.",
                    isRetryable: true
                )
                try await persistPolishAttemptFailure(
                    transactionID: transaction.id,
                    attemptIndex: attemptIndex,
                    runID: invocation.executionRequest.runID,
                    projectID: projectID,
                    failure: failure,
                    isBlocked: false
                )
                throw NovelError.invalidInput(failure.message)
            }
            let latest = try await reloadPolishDocument(projectID: projectID)
            if let replay = try await cancelledPolishOutcomeIfNeeded(
                transaction: transaction,
                attemptIndex: attemptIndex,
                runID: invocation.executionRequest.runID,
                projectID: projectID
            ) {
                return replay
            }
            let reduced: (document: NovelProjectDocumentV1, outcome: NovelOutcome)
            do {
                reduced = try NovelPolishTransactionReducer.finalizeAssessment(
                    transactionID: transaction.id,
                    attemptIndex: attemptIndex,
                    runID: invocation.executionRequest.runID,
                    result: result,
                    in: latest.document,
                    now: now()
                )
            } catch {
                let failure = polishFailure(
                    from: error,
                    fallbackCode: "polish_source_changed"
                )
                try await persistPolishAttemptFailure(
                    transactionID: transaction.id,
                    attemptIndex: attemptIndex,
                    runID: invocation.executionRequest.runID,
                    projectID: projectID,
                    failure: failure,
                    isBlocked: true
                )
                throw error
            }
            if let replay = try await cancelledPolishOutcomeIfNeeded(
                transaction: transaction,
                attemptIndex: attemptIndex,
                runID: invocation.executionRequest.runID,
                projectID: projectID
            ) {
                return replay
            }
            do {
                _ = try await commitPolishDocument(
                    reduced.document,
                    replacing: latest,
                    authorization: finalCommitAuthorization
                )
                return reduced.outcome
            } catch {
                let commitError = error
                do {
                    if let replay = try await reconcilePolishOutcome(
                        projectID: projectID,
                        operationID: transaction.operationID,
                        payloadSHA256: transaction.payloadSHA256
                    ) {
                        return replay
                    }
                } catch {
                    // The original commit error remains authoritative.
                }
                let failure = polishFailure(
                    from: commitError,
                    fallbackCode: "polish_commit_failed"
                )
                do {
                    if let replay = try await persistPolishAttemptFailure(
                        transactionID: transaction.id,
                        attemptIndex: attemptIndex,
                        runID: invocation.executionRequest.runID,
                        projectID: projectID,
                        failure: failure,
                        isBlocked: false
                    ) {
                        return replay
                    }
                } catch {
                    if requiresRepositoryReconciliation(error) {
                        frozenProjectIDs.insert(projectID)
                    }
                    // The original commit error remains authoritative.
                }
                throw commitError
            }

        case .failed(let failure):
            try await persistPolishAttemptFailure(
                transactionID: transaction.id,
                attemptIndex: attemptIndex,
                runID: invocation.executionRequest.runID,
                projectID: projectID,
                failure: failure,
                isBlocked: !failure.isRetryable
            )
            if failure.code == "cancelled" {
                throw CancellationError()
            }
            throw NovelError.invalidInput(failure.message)

        case .timedOut:
            let failure = NovelFailure(
                code: "polish_assessment_timeout",
                message: "The polish drift check timed out without a safe verdict.",
                isRetryable: true
            )
            Task { [modelRunner] in
                await modelRunner.cancel(runID: invocation.executionRequest.runID)
            }
            try await persistPolishAttemptFailure(
                transactionID: transaction.id,
                attemptIndex: attemptIndex,
                runID: invocation.executionRequest.runID,
                projectID: projectID,
                failure: failure,
                isBlocked: false
            )
            throw NovelError.invalidInput(failure.message)
        }
    }

    func racePolishAssessment(
        executor: NovelStructuredModelExecutor,
        invocation: NovelStructuredModelInvocation,
        timeout: TimeInterval
    ) async -> NovelPolishRaceOutcome {
        guard !Task.isCancelled else {
            return .failed(NovelFailure(
                code: "cancelled",
                message: "The polish drift check was cancelled.",
                isRetryable: true
            ))
        }
        let latch = NovelPolishRaceLatch()
        let providerTask = Task.detached {
            do {
                latch.resolve(.succeeded(
                    try await executor.executePrepared(invocation)
                ))
            } catch {
                latch.resolve(.failed(
                    polishFailure(from: error, fallbackCode: "polish_assessment_failed")
                ))
            }
        }
        let timeoutTask = Task.detached {
            let nanoseconds = UInt64(max(0.01, timeout) * 1_000_000_000)
            try? await Task.sleep(nanoseconds: nanoseconds)
            guard !Task.isCancelled else { return }
            latch.resolve(.timedOut)
        }
        let first = await withTaskCancellationHandler {
            await latch.wait()
        } onCancel: {
            latch.resolve(.failed(NovelFailure(
                code: "cancelled",
                message: "The polish drift check was cancelled.",
                isRetryable: true
            )))
            providerTask.cancel()
            timeoutTask.cancel()
            Task { [modelRunner] in
                await modelRunner.cancel(runID: invocation.executionRequest.runID)
            }
        }
        timeoutTask.cancel()
        if case .timedOut = first {
            providerTask.cancel()
        }
        return first
    }

    func makePolishArtifacts(
        transaction: NovelPendingPolishTransactionRecord,
        attemptIndex: Int,
        source: NovelChapterVersionRecord,
        candidate: NovelCandidateRecord,
        invocation: NovelStructuredModelInvocation,
        preparation: NovelStructuredModelPreparation,
        projectID: NovelProjectID,
        createdAt: Date
    ) -> NovelPolishRequestArtifacts {
        let runID = invocation.executionRequest.runID
        let injectionID = NovelReceiptID(deterministicUUID(
            projectID: projectID,
            transactionID: transaction.id,
            attemptIndex: attemptIndex,
            label: "polish-injection"
        ))
        let generationID = NovelReceiptID(deterministicUUID(
            projectID: projectID,
            transactionID: transaction.id,
            attemptIndex: attemptIndex,
            label: "polish-generation"
        ))
        let plan = exactPolishPlan(
            source: source,
            candidate: candidate,
            maxEstimatedInputTokens: preparation.effectiveInputBudgetTokens
        )
        let injection = NovelInjectionReceiptRecord(
            id: injectionID,
            runID: runID,
            projectID: projectID,
            branchID: transaction.branchID,
            plan: plan,
            overrides: .none,
            providerID: invocation.resolvedModel.providerID,
            ownerProviderID: invocation.resolvedModel.ownerProviderID,
            modelID: invocation.resolvedModel.modelID,
            wireModelID: invocation.resolvedModel.wireModelID,
            parameters: invocation.parameters,
            requestedInputBudgetTokens: preparation.requestedInputBudgetTokens,
            createdAt: createdAt
        )
        let generation = NovelGenerationReceiptRecord(
            id: generationID,
            runID: runID,
            providerID: invocation.resolvedModel.providerID,
            ownerProviderID: invocation.resolvedModel.ownerProviderID,
            modelID: invocation.resolvedModel.modelID,
            wireModelID: invocation.resolvedModel.wireModelID,
            promptVersion: plan.prompt.version,
            injectionReceiptID: injection.id,
            parameters: invocation.parameters,
            requestSHA256: invocation.requestSHA256,
            createdAt: createdAt
        )
        return NovelPolishRequestArtifacts(
            attempt: NovelPolishAttemptRecord(
                transactionID: transaction.id,
                attemptIndex: attemptIndex,
                runID: runID,
                injectionReceiptID: injection.id,
                generationReceiptID: generation.id,
                sourceContentSHA256: transaction.sourceContentSHA256,
                candidateContentSHA256: transaction.candidateContentSHA256,
                createdAt: createdAt
            ),
            injectionReceipt: injection,
            generationReceipt: generation
        )
    }

    func exactPolishPlan(
        source: NovelChapterVersionRecord,
        candidate: NovelCandidateRecord,
        maxEstimatedInputTokens: Int
    ) -> NovelInjectionPlan {
        let prompt = NovelPromptCatalog.template(for: .polishDriftV1)
        let messages = NovelStructuredModelTask.polishDriftMessages(
            sourceChapter: source.content,
            candidate: candidate.content
        )
        let sections = messages.map { message in
            let isSystem = message.role == .system
            return polishSection(
                kind: isSystem ? .fixedPrompt : .userInput,
                label: "\(message.role.rawValue.uppercased()) MESSAGE",
                content: message.content,
                reason: isSystem ? .requiredPrompt : .requiredUserInput
            )
        }
        let canonical = sections.map {
            "[\($0.label)]\n\($0.content)"
        }.joined(separator: "\n\n")
        return NovelInjectionPlan(
            prompt: prompt,
            sections: sections,
            materialDecisions: [],
            maxEstimatedInputTokens: maxEstimatedInputTokens,
            estimatedInputTokens: estimatedTokens(canonical),
            contextText: canonical,
            canonicalInput: canonical,
            canonicalInputSHA256: NovelDocumentValidator.sha256(canonical)
        )
    }

    func polishSection(
        kind: NovelInjectionSectionKind,
        label: String,
        content: String,
        reason: NovelInjectionSelectionReason
    ) -> NovelInjectionSection {
        NovelInjectionSection(
            kind: kind,
            label: label,
            content: content,
            reason: reason,
            estimatedTokens: estimatedTokens(content),
            contentSHA256: NovelDocumentValidator.sha256(content)
        )
    }

    func persistPolishPreflightFailure(
        transactionID: NovelPendingOperationID,
        projectID: NovelProjectID,
        failure: NovelFailure
    ) async throws {
        let latest = try await reloadPolishDocument(projectID: projectID)
        let failed = try NovelPolishTransactionReducer.recordPreflightFailure(
            transactionID: transactionID,
            failure: failure,
            isBlocked: !failure.isRetryable,
            in: latest.document,
            now: now()
        )
        _ = try await commitPolishDocument(failed, replacing: latest)
    }

    func recoverAbandonedPolishAttemptIfNeeded(
        transactionID: NovelPendingOperationID,
        projectID: NovelProjectID,
        document: NovelProjectDocumentV1
    ) async throws -> NovelProjectDocumentV1 {
        let assessed = Set(document.polishAssessments.filter {
            $0.transactionID == transactionID
        }.map(\.attemptIndex))
        guard let abandoned = document.polishAttempts
            .filter({ $0.transactionID == transactionID && !assessed.contains($0.attemptIndex) })
            .max(by: { $0.attemptIndex < $1.attemptIndex }) else {
            return document
        }
        let latest = try await reloadPolishDocument(projectID: projectID)
        let latestAssessed = Set(latest.document.polishAssessments.filter {
            $0.transactionID == transactionID
        }.map(\.attemptIndex))
        guard latest.document.polishAttempts.contains(where: {
            $0.transactionID == transactionID &&
                $0.attemptIndex == abandoned.attemptIndex &&
                $0.runID == abandoned.runID
        }), !latestAssessed.contains(abandoned.attemptIndex) else {
            return latest.document
        }
        let failure = NovelFailure(
            code: "polish_attempt_interrupted",
            message: "A previous polish drift check ended before recording a verdict.",
            isRetryable: true
        )
        let recovered = try NovelPolishTransactionReducer.recordFailure(
            transactionID: transactionID,
            attemptIndex: abandoned.attemptIndex,
            runID: abandoned.runID,
            failure: failure,
            isBlocked: false,
            in: latest.document,
            now: now()
        )
        let committed = try await commitPolishDocument(recovered, replacing: latest)
        return committed.document
    }

    @discardableResult
    func persistPolishAttemptFailure(
        transactionID: NovelPendingOperationID,
        attemptIndex: Int,
        runID: NovelRunID,
        projectID: NovelProjectID,
        failure: NovelFailure,
        isBlocked: Bool
    ) async throws -> NovelOutcome? {
        let latest = try await reloadPolishDocument(projectID: projectID)
        if let transaction = latest.document.polishTransactions.first(where: {
            $0.id == transactionID
        }), let operation = latest.document.appliedOperations.first(where: {
            $0.operationID == transaction.operationID &&
                $0.kind == .adoptPolishCandidate &&
                $0.payloadSHA256 == transaction.payloadSHA256
        }) {
            return operation.outcome
        }
        let failed = try NovelPolishTransactionReducer.recordFailure(
            transactionID: transactionID,
            attemptIndex: attemptIndex,
            runID: runID,
            failure: failure,
            isBlocked: isBlocked,
            in: latest.document,
            now: now()
        )
        _ = try await commitPolishDocument(failed, replacing: latest)
        return nil
    }

    func cancelledPolishOutcomeIfNeeded(
        transaction: NovelPendingPolishTransactionRecord,
        attemptIndex: Int,
        runID: NovelRunID,
        projectID: NovelProjectID
    ) async throws -> NovelOutcome? {
        guard Task.isCancelled else { return nil }
        let failure = NovelFailure(
            code: "cancelled",
            message: "The polish drift check was cancelled.",
            isRetryable: true
        )
        if let replay = try await persistPolishAttemptFailure(
            transactionID: transaction.id,
            attemptIndex: attemptIndex,
            runID: runID,
            projectID: projectID,
            failure: failure,
            isBlocked: false
        ) {
            return replay
        }
        throw CancellationError()
    }

    func commitPolishDocument(
        _ document: NovelProjectDocumentV1,
        replacing loaded: NovelLoadedProject,
        authorization: NovelRepositoryCommitAuthorization? = nil
    ) async throws -> NovelLoadedProject {
        guard loaded.access == .readWrite else {
            throw NovelError.degradedReadOnly(projectID: document.project.id)
        }
        if document == loaded.document { return loaded }
        let committed = try await repository.commitProject(
            document,
            expectedRevision: loaded.document.project.revision,
            authorization: authorization
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

    func reloadPolishDocument(projectID: NovelProjectID) async throws -> NovelLoadedProject {
        let loaded = try await repository.loadProject(id: projectID)
        return try installLoadedProject(loaded, id: projectID, allowsRollback: false)
    }

    func reconcilePolishOutcome(
        projectID: NovelProjectID,
        operationID: NovelOperationID,
        payloadSHA256: String
    ) async throws -> NovelOutcome? {
        let loaded = try await repository.loadProject(id: projectID)
        guard let operation = loaded.document.appliedOperations.first(where: {
            $0.operationID == operationID
        }) else {
            return nil
        }
        guard operation.kind == .adoptPolishCandidate,
              operation.payloadSHA256 == payloadSHA256 else {
            throw NovelError.idempotencyConflict(operationID)
        }
        _ = try installLoadedProject(
            loaded,
            id: projectID,
            allowsRollback: true
        )
        return operation.outcome
    }

    func deterministicRunID(
        projectID: NovelProjectID,
        transactionID: NovelPendingOperationID,
        attemptIndex: Int
    ) -> NovelRunID {
        NovelRunID(deterministicUUID(
            projectID: projectID,
            transactionID: transactionID,
            attemptIndex: attemptIndex,
            label: "polish-run"
        ))
    }

    func deterministicUUID(
        projectID: NovelProjectID,
        transactionID: NovelPendingOperationID,
        attemptIndex: Int,
        label: String
    ) -> UUID {
        var bytes = Array(SHA256.hash(
            data: Data(
                "\(projectID.description):\(transactionID.description):\(attemptIndex):\(label)".utf8
            )
        ).prefix(16))
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }

    func estimatedInputTokens(_ messages: [NovelModelMessage]) -> Int {
        estimatedTokens(messages.map(\.content).joined(separator: "\n\n")) +
            max(8, messages.count * 4)
    }

    func estimatedTokens(_ text: String) -> Int {
        guard !text.isEmpty else { return 0 }
        return max(1, (text.utf8.count + 3) / 4)
    }
}

private func polishFailure(from error: Error, fallbackCode: String) -> NovelFailure {
    if error is CancellationError {
        return NovelFailure(
            code: "cancelled",
            message: "The polish drift check was cancelled.",
            isRetryable: true
        )
    }
    if let failure = error as? NovelStructuredModelExecutionFailure {
        return failure.failure
    }
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

private func isTemporaryPolishGuardFailure(_ error: Error) -> Bool {
    guard let novelError = error as? NovelError else { return false }
    if case .projectBusy = novelError { return true }
    return false
}
