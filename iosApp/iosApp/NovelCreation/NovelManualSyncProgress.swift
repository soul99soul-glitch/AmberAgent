import Foundation

struct NovelFactTransactionAttempt: Equatable, Sendable {
    let operationID: NovelOperationID
    let payloadSHA256: String

    init(
        pending: NovelPendingOperationRecord,
        retryCommand: NovelRetryPendingCommand?
    ) throws {
        if let retryCommand {
            operationID = retryCommand.context.operationID
            payloadSHA256 = try retryCommand.canonicalPayloadSHA256()
        } else {
            operationID = pending.operationID
            payloadSHA256 = pending.payloadSHA256
        }
        guard NovelDocumentValidator.isSHA256(payloadSHA256) else {
            throw NovelError.invalidInput("The fact attempt payload hash is invalid.")
        }
    }
}

struct NovelFactAttemptRecord: Codable, Equatable, Sendable {
    let pendingID: NovelPendingOperationID
    let ownerOperationID: NovelOperationID
    let attemptOperationID: NovelOperationID
    let attemptPayloadSHA256: String
    let branchID: NovelBranchID
    let kind: NovelFactReceiptKind
    let firstChunkIndex: Int?
    let createdAt: Date
}

struct NovelManualSyncCompletedChunk: Codable, Equatable, Sendable {
    let index: Int
    let startCharacterOffset: Int
    let endCharacterOffset: Int
    let manuscriptSHA256: String
    let modelInputSHA256: String
    let rebuild: NovelStateRebuildV1
    let attemptOperationID: NovelOperationID
    let attemptPayloadSHA256: String
    let injectionReceiptID: NovelReceiptID
    let generationReceiptID: NovelReceiptID
}

struct NovelManualSyncProgress: Codable, Equatable, Sendable {
    static let currentChunkingVersion = 1

    let chunkingVersion: Int
    let modelPolicy: NovelProjectModelPolicy
    let resolvedModel: NovelResolvedModel
    let parameters: NovelModelParameters
    let requestedInputBudgetTokens: Int
    let effectiveInputBudgetTokens: Int
    var completedChunks: [NovelManualSyncCompletedChunk]
    var accumulatedRebuild: NovelStateRebuildV1

    var nextChunkIndex: Int { completedChunks.count }
    var consumedCharacterCount: Int { completedChunks.last?.endCharacterOffset ?? 0 }

    var preparation: NovelStructuredModelPreparation {
        NovelStructuredModelPreparation(
            modelPolicy: modelPolicy,
            taskKind: .stateRebuild,
            resolvedModel: resolvedModel,
            parameters: parameters,
            requestedInputBudgetTokens: requestedInputBudgetTokens,
            effectiveInputBudgetTokens: effectiveInputBudgetTokens
        )
    }
}

struct NovelManualSyncChunkSelection: Equatable, Sendable {
    let index: Int
    let startCharacterOffset: Int
    let endCharacterOffset: Int
    let manuscript: String
    let modelInput: String
    let projectedStateContext: String
    let plan: NovelInjectionPlan
    let isFinal: Bool
}

enum NovelManualSyncChunker {
    private struct BaseProjectedState: Codable {
        let stateSummary: String
        let branchOutline: String
        let unresolvedEntityNames: [String]
        let characterIdentityClarifications: [NovelCharacterIdentityClarificationRecord]
        let eventCount: Int
        let eventIDsSHA256: String
    }

    private struct ProjectedState: Codable {
        let stateSummary: String
        let branchOutline: String
        let unresolvedEntityNames: [String]
        let eventCount: Int
        let characterStateCount: Int
        let relationshipCount: Int
        let foreshadowingCount: Int
        let settingProposalCount: Int
        let accumulatedFactsSHA256: String
    }

    static func projectedStateContext(
        baseState: NovelStateSnapshotRecord,
        accumulated: NovelStateRebuildV1?
    ) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let base = BaseProjectedState(
            stateSummary: baseState.summary,
            branchOutline: baseState.branchOutline,
            unresolvedEntityNames: baseState.unresolvedEntityNames,
            characterIdentityClarifications: baseState.characterIdentityClarifications,
            eventCount: baseState.eventIDs.count,
            eventIDsSHA256: NovelDocumentValidator.sha256(
                baseState.eventIDs.map(\.description).joined(separator: "\n")
            )
        )
        let baseData = try encoder.encode(base)
        var result = "BASE CHECKPOINT STATE\n" + String(decoding: baseData, as: UTF8.self)
        if let accumulated {
            let fullData = try encoder.encode(accumulated)
            let projected = ProjectedState(
                stateSummary: accumulated.stateSummary,
                branchOutline: accumulated.branchOutline,
                unresolvedEntityNames: accumulated.unresolvedEntityNames,
                eventCount: accumulated.events.count,
                characterStateCount: accumulated.characterStates.count,
                relationshipCount: accumulated.relationships.count,
                foreshadowingCount: accumulated.foreshadowing.count,
                settingProposalCount: accumulated.settingProposals.count,
                accumulatedFactsSHA256: NovelDocumentValidator.sha256(
                    String(decoding: fullData, as: UTF8.self)
                )
            )
            let data = try encoder.encode(projected)
            guard let json = String(data: data, encoding: .utf8) else {
                throw NovelError.invalidInput("The projected manual-sync state is not UTF-8.")
            }
            result += "\n\nCOMPACT PROJECTED STATE THROUGH PREVIOUS CHUNKS\n" + json
        }
        return result
    }

    static func modelInput(chunk: String, index: Int) -> String {
        """
        MANUAL SYNC CHUNK \(index + 1)
        Update stateSummary, branchOutline, and unresolvedEntityNames cumulatively from the projected state.
        Return events, characterStates, relationships, foreshadowing, and settingProposals only for facts whose
        evidence occurs in CURRENT MANUSCRIPT CHUNK. Do not repeat facts from prior chunks.

        CURRENT MANUSCRIPT CHUNK
        \(chunk)
        """
    }

    static func selectNext(
        manuscript: String,
        consumedCharacterCount: Int,
        chunkIndex: Int,
        projectedStateContext: String,
        makePlan: (String) throws -> NovelInjectionPlan
    ) throws -> NovelManualSyncChunkSelection {
        let characters = Array(manuscript)
        guard consumedCharacterCount >= 0,
              consumedCharacterCount < characters.count else {
            throw NovelError.invalidInput("The manual-sync chunk cursor is outside the manuscript.")
        }

        var low = consumedCharacterCount + 1
        var high = characters.count
        var best: (end: Int, input: String, plan: NovelInjectionPlan)?
        var smallestBudgetError: Error?
        while low <= high {
            let middle = low + (high - low) / 2
            let chunk = String(characters[consumedCharacterCount..<middle])
            let input = modelInput(chunk: chunk, index: chunkIndex)
            do {
                let plan = try makePlan(input)
                best = (middle, input, plan)
                low = middle + 1
            } catch let error as NovelInjectionPlanningError {
                guard case .requiredContentExceedsBudget = error else { throw error }
                smallestBudgetError = error
                high = middle - 1
            }
        }

        guard var selected = best else {
            if let smallestBudgetError { throw smallestBudgetError }
            throw NovelError.invalidInput("No manual-sync manuscript chunk fits the model context.")
        }
        if selected.end < characters.count {
            let minimumPreferred = consumedCharacterCount +
                max(1, (selected.end - consumedCharacterCount) / 2)
            if let boundary = preferredBoundary(
                in: characters,
                lowerBound: minimumPreferred,
                upperBound: selected.end
            ) {
                let chunk = String(characters[consumedCharacterCount..<boundary])
                let input = modelInput(chunk: chunk, index: chunkIndex)
                selected = (boundary, input, try makePlan(input))
            }
        }

        let chunk = String(characters[consumedCharacterCount..<selected.end])
        return NovelManualSyncChunkSelection(
            index: chunkIndex,
            startCharacterOffset: consumedCharacterCount,
            endCharacterOffset: selected.end,
            manuscript: chunk,
            modelInput: selected.input,
            projectedStateContext: projectedStateContext,
            plan: selected.plan,
            isFinal: selected.end == characters.count
        )
    }

    private static func preferredBoundary(
        in characters: [Character],
        lowerBound: Int,
        upperBound: Int
    ) -> Int? {
        guard lowerBound < upperBound else { return nil }
        let sentenceEndings: Set<Character> = [".", "!", "?", "。", "！", "？", "\n"]
        for end in stride(from: upperBound, through: lowerBound, by: -1) {
            if sentenceEndings.contains(characters[end - 1]) {
                return end
            }
        }
        return nil
    }
}

enum NovelManualSyncProgressReducer {
    static func reserveRetryAttempt(
        _ command: NovelRetryPendingCommand,
        pending: NovelPendingOperationRecord,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        let validatedPending = try NovelFactTransactionReducer.validateRetryCommand(
            command,
            in: document
        )
        guard validatedPending == pending else {
            throw NovelError.invalidInput("The retry reservation pending operation does not match.")
        }
        let payloadSHA256 = try command.canonicalPayloadSHA256()
        let kind: NovelFactReceiptKind = pending.kind == .collection
            ? .stateDelta
            : .manualRebuild
        guard document.pendingOperations.contains(where: {
            $0.id == pending.id && $0 == pending
        }) else {
            throw NovelError.invalidInput("The pending fact transaction changed before retry reservation.")
        }
        if let existing = document.factAttempts.first(where: {
            $0.attemptOperationID == command.context.operationID
        }) {
            guard existing.pendingID == pending.id,
                  existing.ownerOperationID == pending.operationID,
                  existing.attemptPayloadSHA256 == payloadSHA256,
                  existing.branchID == pending.branchID,
                  existing.kind == kind else {
                throw NovelError.idempotencyConflict(command.context.operationID)
            }
            return document
        }
        guard document.appliedOperations.allSatisfy({
            $0.operationID != command.context.operationID
        }), document.factAttempts.allSatisfy({
            $0.attemptOperationID != command.context.operationID
        }), document.pendingOperations.allSatisfy({ candidate in
            candidate.operationID != command.context.operationID
        }) else {
            throw NovelError.idempotencyConflict(command.context.operationID)
        }

        var next = document
        next.factAttempts.append(NovelFactAttemptRecord(
            pendingID: pending.id,
            ownerOperationID: pending.operationID,
            attemptOperationID: command.context.operationID,
            attemptPayloadSHA256: payloadSHA256,
            branchID: pending.branchID,
            kind: kind,
            firstChunkIndex: pending.kind == .manualSync
                ? (pending.manualSyncProgress?.nextChunkIndex ?? 0)
                : nil,
            createdAt: now
        ))
        next.project.revision += 1
        next.project.updatedAt = now
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return next
    }

    /// Discards durable manual-sync progress so the next `commitChunk` call starts over at
    /// chunk 0 under a newly resolved model. This is the sole legitimate way to null out
    /// `manualSyncProgress`; every other reducer treats it as append-only (see
    /// `NovelManualSyncProgressValidator.validateTransition`). Callers must only invoke this
    /// when the resolved state-sync model policy has actually changed from the one locked
    /// into the existing progress, so different models' chunks are never mixed into one
    /// `accumulatedRebuild` — a fresh restart re-derives chunk 0 from scratch under the new
    /// model instead of continuing the old accumulation.
    static func resetProgress(
        pendingID: NovelPendingOperationID,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        guard let pendingIndex = document.pendingOperations.firstIndex(where: {
            $0.id == pendingID && $0.kind == .manualSync
        }) else {
            throw NovelError.invalidInput("The pending manual synchronization is unavailable.")
        }
        let pending = document.pendingOperations[pendingIndex]
        guard pending.manualSyncProgress != nil else {
            return document
        }
        guard let branch = document.branches.first(where: { $0.id == pending.branchID }),
              branch.headCheckpointID == pending.baseCheckpointID,
              branch.headRevision == pending.baseHeadRevision,
              branch.workingRevision == pending.baseWorkingRevision,
              branch.syncStatus == .needsSync,
              branch.activeRunID == nil else {
            throw NovelError.invalidInput("The pending manual synchronization is stale.")
        }

        var next = document
        next.pendingOperations[pendingIndex].manualSyncProgress = nil
        next.project.revision += 1
        next.project.updatedAt = now
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return next
    }

    static func commitChunk(
        pendingID: NovelPendingOperationID,
        selection: NovelManualSyncChunkSelection,
        rebuild: NovelStateRebuildV1,
        preparation: NovelStructuredModelPreparation,
        attempt: NovelFactTransactionAttempt,
        artifacts: NovelFactTransactionReceiptArtifacts,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        guard let pendingIndex = document.pendingOperations.firstIndex(where: {
            $0.id == pendingID && $0.kind == .manualSync
        }) else {
            throw NovelError.invalidInput("The pending manual synchronization is unavailable.")
        }
        let pending = document.pendingOperations[pendingIndex]
        guard let branch = document.branches.first(where: { $0.id == pending.branchID }),
              branch.headCheckpointID == pending.baseCheckpointID,
              branch.headRevision == pending.baseHeadRevision,
              branch.workingRevision == pending.baseWorkingRevision,
              branch.syncStatus == .needsSync,
              branch.activeRunID == nil else {
            throw NovelError.invalidInput("The pending manual synchronization is stale.")
        }
        let input = try NovelFactTransactionReducer.manualRebuildInput(
            pendingID: pendingID,
            in: document
        )
        let characters = Array(input.manuscript)
        guard selection.startCharacterOffset >= 0,
              selection.endCharacterOffset > selection.startCharacterOffset,
              selection.endCharacterOffset <= characters.count,
              String(characters[selection.startCharacterOffset..<selection.endCharacterOffset]) ==
                selection.manuscript else {
            throw NovelError.invalidInput("The manual-sync chunk does not match the durable manuscript.")
        }

        let existingProgress = pending.manualSyncProgress
        let expectedIndex = existingProgress?.nextChunkIndex ?? 0
        let expectedStart = existingProgress?.consumedCharacterCount ?? 0
        guard selection.index == expectedIndex,
              selection.startCharacterOffset == expectedStart,
              preparation.taskKind == .stateRebuild else {
            throw NovelError.invalidInput("The manual-sync chunk does not continue durable progress.")
        }
        if let existingProgress {
            guard existingProgress.preparation == preparation else {
                throw NovelError.invalidInput(
                    "The configured model changed after manual synchronization made progress."
                )
            }
        }

        let decoded = try strictRebuild(rebuild)
        let validated = try NovelFactTransactionReducer.validateManualChunkOutput(
            decoded,
            evidenceSource: selection.manuscript,
            accumulated: existingProgress?.accumulatedRebuild,
            baseState: input.baseStateSnapshot,
            branchID: pending.branchID,
            in: document
        )
        let accumulated = merge(
            validated,
            into: existingProgress?.accumulatedRebuild,
            chunkIndex: selection.index
        )
        _ = try strictRebuild(accumulated)
        if !selection.isFinal {
            try validateNextChunkCanFit(
                accumulated: accumulated,
                nextCharacter: characters[selection.endCharacterOffset],
                nextChunkIndex: selection.index + 1,
                pending: pending,
                input: input,
                preparation: preparation,
                document: document
            )
        }

        let expectedLink = NovelFactReceiptLink(
            pendingID: pending.id,
            ownerOperationID: pending.operationID,
            attemptOperationID: attempt.operationID,
            attemptPayloadSHA256: attempt.payloadSHA256,
            kind: .manualRebuild,
            chunkIndex: selection.index
        )
        try validateArtifacts(
            artifacts,
            expectedLink: expectedLink,
            pending: pending,
            selection: selection,
            preparation: preparation,
            identityClarifications: input.baseStateSnapshot.characterIdentityClarifications,
            document: document
        )

        let completed = NovelManualSyncCompletedChunk(
            index: selection.index,
            startCharacterOffset: selection.startCharacterOffset,
            endCharacterOffset: selection.endCharacterOffset,
            manuscriptSHA256: NovelDocumentValidator.sha256(selection.manuscript),
            modelInputSHA256: NovelDocumentValidator.sha256(selection.modelInput),
            rebuild: validated,
            attemptOperationID: attempt.operationID,
            attemptPayloadSHA256: attempt.payloadSHA256,
            injectionReceiptID: artifacts.injectionReceipt.id,
            generationReceiptID: artifacts.generationReceipt.id
        )
        var progress = existingProgress ?? NovelManualSyncProgress(
            chunkingVersion: NovelManualSyncProgress.currentChunkingVersion,
            modelPolicy: preparation.modelPolicy,
            resolvedModel: preparation.resolvedModel,
            parameters: preparation.parameters,
            requestedInputBudgetTokens: preparation.requestedInputBudgetTokens,
            effectiveInputBudgetTokens: preparation.effectiveInputBudgetTokens,
            completedChunks: [],
            accumulatedRebuild: accumulated
        )
        progress.completedChunks.append(completed)
        progress.accumulatedRebuild = accumulated

        var next = document
        next.pendingOperations[pendingIndex].manualSyncProgress = progress
        if !next.injectionReceipts.contains(where: {
            $0.id == artifacts.injectionReceipt.id
        }) {
            next.injectionReceipts.append(artifacts.injectionReceipt)
            next.generationReceipts.append(artifacts.generationReceipt)
        }
        let attemptRecord = NovelFactAttemptRecord(
            pendingID: pending.id,
            ownerOperationID: pending.operationID,
            attemptOperationID: attempt.operationID,
            attemptPayloadSHA256: attempt.payloadSHA256,
            branchID: pending.branchID,
            kind: .manualRebuild,
            firstChunkIndex: selection.index,
            createdAt: now
        )
        if attempt.operationID != pending.operationID,
           !next.factAttempts.contains(where: {
               $0.attemptOperationID == attemptRecord.attemptOperationID
           }) {
            throw NovelError.invalidInput("The retry attempt was not durably reserved before model use.")
        }
        next.project.revision += 1
        next.project.updatedAt = now
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return next
    }

    static func isComplete(
        _ progress: NovelManualSyncProgress,
        manuscript: String
    ) -> Bool {
        progress.consumedCharacterCount == manuscript.count
    }

    private static func strictRebuild(
        _ rebuild: NovelStateRebuildV1
    ) throws -> NovelStateRebuildV1 {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try NovelStructuredOutputDecoder.decodeStateRebuild(from: encoder.encode(rebuild))
    }

    private static func validateNextChunkCanFit(
        accumulated: NovelStateRebuildV1,
        nextCharacter: Character,
        nextChunkIndex: Int,
        pending: NovelPendingOperationRecord,
        input: NovelManualRebuildInput,
        preparation: NovelStructuredModelPreparation,
        document: NovelProjectDocumentV1
    ) throws {
        let projected = try NovelManualSyncChunker.projectedStateContext(
            baseState: input.baseStateSnapshot,
            accumulated: accumulated
        )
        _ = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: pending.branchID,
                promptKind: .manualSyncV1,
                userText: NovelManualSyncChunker.modelInput(
                    chunk: String(nextCharacter),
                    index: nextChunkIndex
                ),
                stateSnapshotIDOverride: input.baseStateSnapshot.id,
                sessionCursorLimit: .empty,
                includeUnsynchronizedStateWarning: false,
                pendingState: NovelPendingStateInjection(
                    pendingID: pending.id,
                    chunkIndex: nextChunkIndex,
                    content: projected
                ),
                budget: NovelInjectionBudget(
                    maxEstimatedInputTokens: preparation.effectiveInputBudgetTokens,
                    chapterTailCharacterLimit: NovelInjectionBudget.standard
                        .chapterTailCharacterLimit,
                    maximumRecentSessionMessages: NovelInjectionBudget.standard
                        .maximumRecentSessionMessages
                )
            )
        )
    }

    static func merge(
        _ chunk: NovelStateRebuildV1,
        into accumulated: NovelStateRebuildV1?,
        chunkIndex: Int
    ) -> NovelStateRebuildV1 {
        let events = chunk.events.map {
            NovelStateEventV1(
                id: mergedStableID(
                    chunkIndex: chunkIndex,
                    category: "event",
                    originalID: $0.id
                ),
                kind: $0.kind,
                summary: $0.summary,
                entityReferences: $0.entityReferences,
                evidence: $0.evidence
            )
        }
        let characters = chunk.characterStates.map {
            NovelCharacterStateFactV1(
                id: mergedStableID(
                    chunkIndex: chunkIndex,
                    category: "character",
                    originalID: $0.id
                ),
                characterName: $0.characterName,
                attribute: $0.attribute,
                value: $0.value,
                evidence: $0.evidence
            )
        }
        let relationships = chunk.relationships.map {
            NovelRelationshipFactV1(
                id: mergedStableID(
                    chunkIndex: chunkIndex,
                    category: "relationship",
                    originalID: $0.id
                ),
                sourceEntity: $0.sourceEntity,
                targetEntity: $0.targetEntity,
                relationship: $0.relationship,
                state: $0.state,
                evidence: $0.evidence
            )
        }
        let foreshadowing = chunk.foreshadowing.map {
            NovelForeshadowingFactV1(
                id: mergedStableID(
                    chunkIndex: chunkIndex,
                    category: "foreshadowing",
                    originalID: $0.id
                ),
                thread: $0.thread,
                status: $0.status,
                summary: $0.summary,
                evidence: $0.evidence
            )
        }
        let proposals = chunk.settingProposals.map {
            NovelSettingProposalDraftV1(
                id: mergedStableID(
                    chunkIndex: chunkIndex,
                    category: "proposal",
                    originalID: $0.id
                ),
                title: $0.title,
                content: $0.content,
                evidence: $0.evidence
            )
        }
        return NovelStateRebuildV1(
            schemaVersion: NovelStateRebuildV1.currentSchemaVersion,
            stateSummary: chunk.stateSummary,
            branchOutline: chunk.branchOutline,
            events: (accumulated?.events ?? []) + events,
            characterStates: (accumulated?.characterStates ?? []) + characters,
            relationships: (accumulated?.relationships ?? []) + relationships,
            foreshadowing: (accumulated?.foreshadowing ?? []) + foreshadowing,
            unresolvedEntityNames: chunk.unresolvedEntityNames,
            settingProposals: (accumulated?.settingProposals ?? []) + proposals
        )
    }

    static func mergedStableID(
        chunkIndex: Int,
        category: String,
        originalID: String
    ) -> String {
        "chunk-" + NovelDocumentValidator.sha256(
            "\(chunkIndex)|\(category)|\(originalID)"
        )
    }

    private static func validateArtifacts(
        _ artifacts: NovelFactTransactionReceiptArtifacts,
        expectedLink: NovelFactReceiptLink,
        pending: NovelPendingOperationRecord,
        selection: NovelManualSyncChunkSelection,
        preparation: NovelStructuredModelPreparation,
        identityClarifications: [NovelCharacterIdentityClarificationRecord],
        document: NovelProjectDocumentV1
    ) throws {
        let injection = artifacts.injectionReceipt
        let generation = artifacts.generationReceipt
        guard injection.projectID == document.project.id,
              injection.branchID == pending.branchID,
              injection.runID == generation.runID,
              injection.factTransaction == expectedLink,
              generation.factTransaction == expectedLink,
              generation.injectionReceiptID == injection.id,
              injection.providerID == preparation.resolvedModel.providerID,
              injection.ownerProviderID == preparation.resolvedModel.ownerProviderID,
              injection.modelID == preparation.resolvedModel.modelID,
              injection.wireModelID == preparation.resolvedModel.wireModelID,
              injection.parameters == generation.parameters,
              injection.requestedInputBudgetTokens == preparation.requestedInputBudgetTokens,
              injection.maxEstimatedInputTokens == preparation.effectiveInputBudgetTokens,
              injection.canonicalInputSHA256 == selection.plan.canonicalInputSHA256 else {
            throw NovelError.invalidInput("Manual-sync receipts do not match the completed chunk.")
        }
        let userInputHashes = injection.sections.compactMap { section -> String? in
            if case .userInput = section.kind { return section.contentSHA256 }
            return nil
        }
        let projectedStateHashes = injection.sections.compactMap { section -> String? in
            if case .pendingManualState(pending.id, chunkIndex: selection.index) = section.kind {
                return section.contentSHA256
            }
            return nil
        }
        let expectedProjectedStateContent = NovelInjectionPlanner.pendingStateContent(
            selection.projectedStateContext,
            identityClarifications: identityClarifications
        )
        guard userInputHashes == [NovelDocumentValidator.sha256(selection.modelInput)],
              projectedStateHashes == [
                NovelDocumentValidator.sha256(expectedProjectedStateContent)
              ] else {
            throw NovelError.invalidInput("Manual-sync receipt input evidence is incomplete.")
        }
        let existingIDs = Set(document.injectionReceipts.map(\.id))
            .union(document.generationReceipts.map(\.id))
        guard injection.id != generation.id else {
            throw NovelError.immutableRecordConflict("manual-sync receipt identity")
        }
        let existingInjection = document.injectionReceipts.first(where: {
            $0.id == injection.id
        })
        let existingGeneration = document.generationReceipts.first(where: {
            $0.id == generation.id
        })
        if existingInjection != nil || existingGeneration != nil {
            guard existingInjection == injection,
                  existingGeneration == generation else {
                throw NovelError.immutableRecordConflict("manual-sync receipt identity")
            }
        } else if existingIDs.contains(injection.id) || existingIDs.contains(generation.id) {
            throw NovelError.immutableRecordConflict("manual-sync receipt identity")
        }
    }
}

enum NovelManualSyncProgressValidator {
    static func validate(
        pending: NovelPendingOperationRecord,
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        guard pending.kind == .manualSync else {
            if pending.manualSyncProgress != nil {
                issues.append("Collection pending operation \(pending.id) contains manual-sync progress.")
            }
            return
        }
        guard let progress = pending.manualSyncProgress else { return }
        let configuredPolicy = document.project.configuredModelPolicy(for: .stateSync)
        let modelPolicyMatchesProject = switch configuredPolicy {
        case .global: true
        case .fixed: progress.modelPolicy == configuredPolicy
        }
        guard progress.chunkingVersion == NovelManualSyncProgress.currentChunkingVersion,
              modelPolicyMatchesProject,
              progress.requestedInputBudgetTokens > 0,
              progress.effectiveInputBudgetTokens > 0,
              progress.effectiveInputBudgetTokens <= progress.requestedInputBudgetTokens,
              !progress.completedChunks.isEmpty else {
            issues.append("Pending manual synchronization \(pending.id) has invalid progress metadata.")
            return
        }

        let input: NovelManualRebuildInput
        do {
            input = try NovelFactTransactionReducer.manualRebuildInput(
                pendingID: pending.id,
                in: document
            )
        } catch {
            issues.append("Pending manual synchronization \(pending.id) has unreadable progress input.")
            return
        }
        let characters = Array(input.manuscript)
        var cursor = 0
        var accumulated: NovelStateRebuildV1?
        for (expectedIndex, chunk) in progress.completedChunks.enumerated() {
            guard chunk.index == expectedIndex,
                  chunk.startCharacterOffset == cursor,
                  chunk.endCharacterOffset > cursor,
                  chunk.endCharacterOffset <= characters.count else {
                issues.append("Pending manual synchronization \(pending.id) has a discontinuous chunk range.")
                return
            }
            let manuscript = String(characters[cursor..<chunk.endCharacterOffset])
            let modelInput = NovelManualSyncChunker.modelInput(
                chunk: manuscript,
                index: chunk.index
            )
            if chunk.manuscriptSHA256 != NovelDocumentValidator.sha256(manuscript) ||
                chunk.modelInputSHA256 != NovelDocumentValidator.sha256(modelInput) ||
                !NovelDocumentValidator.isSHA256(chunk.attemptPayloadSHA256) {
                issues.append("Pending manual synchronization \(pending.id) has invalid chunk evidence.")
            }
            do {
                _ = try NovelFactTransactionReducer.validateManualChunkOutput(
                    chunk.rebuild,
                    evidenceSource: manuscript,
                    accumulated: accumulated,
                    baseState: input.baseStateSnapshot,
                    branchID: pending.branchID,
                    in: document
                )
            } catch {
                issues.append("Pending manual synchronization \(pending.id) contains an invalid chunk result.")
            }

            let expectedLink = NovelFactReceiptLink(
                pendingID: pending.id,
                ownerOperationID: pending.operationID,
                attemptOperationID: chunk.attemptOperationID,
                attemptPayloadSHA256: chunk.attemptPayloadSHA256,
                kind: .manualRebuild,
                chunkIndex: chunk.index
            )
            guard let injection = document.injectionReceipts.first(where: {
                $0.id == chunk.injectionReceiptID
            }), let generation = document.generationReceipts.first(where: {
                $0.id == chunk.generationReceiptID
            }), injection.factTransaction == expectedLink,
            generation.factTransaction == expectedLink,
            generation.injectionReceiptID == injection.id else {
                issues.append("Pending manual synchronization \(pending.id) has a chunk without receipts.")
                return
            }
            let userHashes = injection.sections.compactMap { section -> String? in
                if case .userInput = section.kind { return section.contentSHA256 }
                return nil
            }
            let projectedHashes = injection.sections.compactMap { section -> String? in
                guard case .pendingManualState(let id, let index) = section.kind,
                      id == pending.id,
                      index == chunk.index else { return nil }
                return section.contentSHA256
            }
            let projected = try? NovelManualSyncChunker.projectedStateContext(
                baseState: input.baseStateSnapshot,
                accumulated: accumulated
            )
            let projectedReceiptContent = projected.map {
                NovelInjectionPlanner.pendingStateContent(
                    $0,
                    identityClarifications: input.baseStateSnapshot.characterIdentityClarifications
                )
            }
            if userHashes != [chunk.modelInputSHA256] ||
                projectedHashes != projectedReceiptContent.map({
                    [NovelDocumentValidator.sha256($0)]
                }) {
                issues.append("Pending manual synchronization \(pending.id) has mismatched chunk input receipts.")
            }
            let hasAttemptEvidence = chunk.attemptOperationID == pending.operationID ||
                document.factAttempts.contains(where: {
                    $0.pendingID == pending.id &&
                        $0.ownerOperationID == pending.operationID &&
                        $0.attemptOperationID == chunk.attemptOperationID &&
                        $0.attemptPayloadSHA256 == chunk.attemptPayloadSHA256 &&
                        $0.branchID == pending.branchID &&
                        $0.kind == .manualRebuild
                })
            guard hasAttemptEvidence else {
                issues.append("Pending manual synchronization \(pending.id) has no attempt evidence.")
                return
            }
            accumulated = NovelManualSyncProgressReducer.merge(
                chunk.rebuild,
                into: accumulated,
                chunkIndex: chunk.index
            )
            cursor = chunk.endCharacterOffset
        }
        if accumulated != progress.accumulatedRebuild || cursor > characters.count {
            issues.append("Pending manual synchronization \(pending.id) accumulated state is inconsistent.")
        }
    }

    static func validateTransition(
        from current: NovelPendingOperationRecord,
        to updated: NovelPendingOperationRecord,
        currentDocument: NovelProjectDocumentV1,
        nextDocument: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        guard current.manualSyncProgress != updated.manualSyncProgress else { return }
        guard current.kind == .manualSync else {
            issues.append("A pending operation rewrote or removed manual-sync progress.")
            return
        }
        guard let nextProgress = updated.manualSyncProgress else {
            // The only legitimate way to null out existing progress is
            // `NovelManualSyncProgressReducer.resetProgress`, which discards progress locked
            // to a stale model so the next chunk restarts at index 0 under the newly
            // resolved one. There is nothing else to structurally check here: the
            // subsequent `commitChunk` call is what re-validates everything from scratch.
            return
        }
        if let currentProgress = current.manualSyncProgress {
            if currentProgress.chunkingVersion != nextProgress.chunkingVersion ||
                currentProgress.modelPolicy != nextProgress.modelPolicy ||
                currentProgress.resolvedModel != nextProgress.resolvedModel ||
                currentProgress.parameters != nextProgress.parameters ||
                currentProgress.requestedInputBudgetTokens !=
                    nextProgress.requestedInputBudgetTokens ||
                currentProgress.effectiveInputBudgetTokens !=
                    nextProgress.effectiveInputBudgetTokens ||
                nextProgress.completedChunks.count != currentProgress.completedChunks.count + 1 ||
                Array(nextProgress.completedChunks.dropLast()) != currentProgress.completedChunks {
                issues.append("A manual-sync transition rewrote completed progress.")
            }
        } else if nextProgress.completedChunks.count != 1 {
            issues.append("A manual-sync transition must start with exactly one completed chunk.")
        }

        guard let appended = nextProgress.completedChunks.last else { return }
        let reusedReservedPair = nextDocument.injectionReceipts == currentDocument.injectionReceipts &&
            nextDocument.generationReceipts == currentDocument.generationReceipts &&
            nextDocument.injectionReceipts.contains(where: {
                $0.id == appended.injectionReceiptID
            }) &&
            nextDocument.generationReceipts.contains(where: {
                $0.id == appended.generationReceiptID
            })
        let appendedPair = nextDocument.injectionReceipts.count ==
            currentDocument.injectionReceipts.count + 1 &&
            nextDocument.generationReceipts.count ==
                currentDocument.generationReceipts.count + 1 &&
            nextDocument.injectionReceipts.last?.id == appended.injectionReceiptID &&
            nextDocument.generationReceipts.last?.id == appended.generationReceiptID
        if !reusedReservedPair && !appendedPair {
            issues.append("A manual-sync progress transition did not preserve its request receipt pair.")
        }
        if currentDocument.events != nextDocument.events ||
            currentDocument.stateSnapshots != nextDocument.stateSnapshots ||
            currentDocument.checkpoints != nextDocument.checkpoints ||
            currentDocument.chapters != nextDocument.chapters ||
            currentDocument.chapterVersions != nextDocument.chapterVersions ||
            currentDocument.settingProposals != nextDocument.settingProposals ||
            currentDocument.appliedOperations != nextDocument.appliedOperations {
            issues.append("A manual-sync progress transition leaked formal story state.")
        }
        if nextDocument.project.revision != currentDocument.project.revision + 1 {
            issues.append("A manual-sync progress transition has invalid revision accounting.")
        }
    }
}
