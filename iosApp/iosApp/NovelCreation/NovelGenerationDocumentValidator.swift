import Foundation

enum NovelGenerationDocumentValidator {
    static func validate(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let generationByID = Dictionary(
            document.generationReceipts.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let injectionByID = Dictionary(
            document.injectionReceipts.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )

        let allReceiptIDs = document.generationReceipts.map(\.id)
            + document.injectionReceipts.map(\.id)
        if Set(allReceiptIDs).count != allReceiptIDs.count {
            issues.append("Generation and injection receipts share an ID.")
        }

        validateInjectionReceipts(document, issues: &issues)
        validateGenerationReceipts(
            document,
            injectionByID: injectionByID,
            issues: &issues
        )
        validateFactReceiptCompleteness(document, issues: &issues)
        validatePolishReceiptCompleteness(document, issues: &issues)
        validateFactAttempts(document, issues: &issues)

        let runOperationIDs = document.activeRuns.map(\.operationID)
        if Set(runOperationIDs).count != runOperationIDs.count {
            issues.append("Generation runs repeat a start operation ID.")
        }
        let userMessageIDs = document.activeRuns.map(\.userMessageID)
        if Set(userMessageIDs).count != userMessageIDs.count {
            issues.append("Generation runs repeat a user message ID.")
        }
        let outputMessageIDs = document.activeRuns.map(\.messageID)
        if Set(outputMessageIDs).count != outputMessageIDs.count {
            issues.append("Generation runs repeat an output message ID.")
        }

        for run in document.activeRuns {
            validateRun(
                run,
                document: document,
                generationByID: generationByID,
                injectionByID: injectionByID,
                issues: &issues
            )
        }
    }

    static func validateTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for run in current.activeRuns {
            guard let updated = next.activeRuns.first(where: { $0.id == run.id }) else {
                issues.append("A project transition removed generation run \(run.id).")
                continue
            }
            if !sameIdentity(run, updated) {
                issues.append("A project transition rewrote generation run \(run.id) identity.")
            }
            if run.status != .running {
                if updated != run {
                    issues.append("A project transition rewrote terminal generation run \(run.id).")
                }
            } else if updated.status == .running && updated != run {
                issues.append("A project transition rewrote an active generation run in place.")
            }
        }

        for run in next.activeRuns where
            !current.activeRuns.contains(where: { $0.id == run.id }) && run.status != .running {
            issues.append("A project transition introduced a generation run already terminal.")
        }
        validateFactRequestReceiptTransition(from: current, to: next, issues: &issues)
    }

    private static func validateFactRequestReceiptTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        guard next.injectionReceipts.count >= current.injectionReceipts.count,
              next.generationReceipts.count >= current.generationReceipts.count else {
            return
        }
        let addedInjections = next.injectionReceipts.dropFirst(current.injectionReceipts.count)
        let factInjections = addedInjections.filter { $0.factTransaction != nil }
        guard !factInjections.isEmpty else { return }
        let addedGenerations = next.generationReceipts.dropFirst(current.generationReceipts.count)
        guard factInjections.count == 1,
              addedInjections.count == 1,
              addedGenerations.count == 1,
              let injection = factInjections.first,
              let generation = addedGenerations.first,
              generation.injectionReceiptID == injection.id,
              generation.factTransaction == injection.factTransaction,
              let link = injection.factTransaction else {
            issues.append("A fact request transition did not append one receipt pair.")
            return
        }

        var expectedProject = current.project
        expectedProject.revision = next.project.revision
        expectedProject.updatedAt = next.project.updatedAt
        let isolatedReservation = next.schemaVersion == current.schemaVersion &&
            next.project == expectedProject &&
            next.project.revision == current.project.revision + 1 &&
            next.project.updatedAt >= current.project.updatedAt &&
            next.materials == current.materials &&
            next.materialRevisions == current.materialRevisions &&
            next.branches == current.branches &&
            next.sessions == current.sessions &&
            next.chapters == current.chapters &&
            next.chapterVersions == current.chapterVersions &&
            next.events == current.events &&
            next.stateSnapshots == current.stateSnapshots &&
            next.checkpoints == current.checkpoints &&
            next.candidates == current.candidates &&
            next.factAttempts == current.factAttempts &&
            next.pendingOperations == current.pendingOperations &&
            next.activeRuns == current.activeRuns &&
            next.settingProposals == current.settingProposals &&
            next.appliedOperations == current.appliedOperations
        if isolatedReservation { return }

        let manualProgressCommit = current.pendingOperations.contains(where: {
            $0.id == link.pendingID && $0.kind == .manualSync
        }) && next.pendingOperations.contains(where: { pending in
            pending.id == link.pendingID &&
                pending.manualSyncProgress?.completedChunks.last?.injectionReceiptID ==
                    injection.id &&
                pending.manualSyncProgress?.completedChunks.last?.generationReceiptID ==
                    generation.id
        })
        if manualProgressCommit { return }

        let factFinalization = current.pendingOperations.contains(where: {
            $0.id == link.pendingID && $0.operationID == link.ownerOperationID
        }) && !next.pendingOperations.contains(where: { $0.id == link.pendingID }) &&
            next.appliedOperations.contains(where: {
                $0.operationID == link.ownerOperationID &&
                    (($0.kind == .collectCandidate && link.kind == .stateDelta) ||
                        ($0.kind == .syncManualEdits && link.kind == .manualRebuild))
            })
        if !factFinalization {
            issues.append("A fact request receipt pair was appended outside its lifecycle transition.")
        }
    }

    private static func validateInjectionReceipts(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for receipt in document.injectionReceipts {
            if receipt.projectID != document.project.id ||
                !document.branches.contains(where: { $0.id == receipt.branchID }) {
                issues.append("Injection receipt \(receipt.id) has invalid project or branch ownership.")
            }
            if receipt.promptVersion.isEmpty ||
                receipt.providerID.isEmpty ||
                receipt.ownerProviderID.isEmpty ||
                receipt.modelID.isEmpty ||
                receipt.wireModelID.isEmpty {
                issues.append("Injection receipt \(receipt.id) is missing model or Prompt identity.")
            }
            if receipt.requestedInputBudgetTokens <= 0 ||
                receipt.maxEstimatedInputTokens <= 0 ||
                receipt.maxEstimatedInputTokens > receipt.requestedInputBudgetTokens ||
                receipt.estimatedInputTokens < 0 ||
                receipt.estimatedInputTokens > receipt.maxEstimatedInputTokens {
                issues.append("Injection receipt \(receipt.id) has an invalid budget.")
            }
            if !NovelDocumentValidator.isSHA256(receipt.canonicalInputSHA256) {
                issues.append("Injection receipt \(receipt.id) has an invalid input hash.")
            }
            if !Set(receipt.forceIncludeMaterialIDs).isDisjoint(
                with: Set(receipt.forceExcludeMaterialIDs)
            ) {
                issues.append("Injection receipt \(receipt.id) has conflicting temporary overrides.")
            }
            if Set(receipt.forceIncludeMaterialIDs).count != receipt.forceIncludeMaterialIDs.count ||
                Set(receipt.forceExcludeMaterialIDs).count != receipt.forceExcludeMaterialIDs.count {
                issues.append("Injection receipt \(receipt.id) repeats a temporary override.")
            }
            if containsSensitiveParameter(receipt.parameters) {
                issues.append("Injection receipt \(receipt.id) contains a sensitive model parameter.")
            }
            for section in receipt.sections where
                section.estimatedTokens < 0 ||
                    !NovelDocumentValidator.isSHA256(section.contentSHA256) {
                issues.append("Injection receipt \(receipt.id) has an invalid section record.")
            }
            for decision in receipt.materialDecisions {
                guard let revision = document.materialRevisions.first(where: {
                    $0.id == decision.revisionID && $0.materialID == decision.materialID
                }) else {
                    issues.append("Injection receipt \(receipt.id) references a missing material revision.")
                    continue
                }
                if decision.estimatedTokens < 0 ||
                    !NovelDocumentValidator.isSHA256(decision.contentSHA256) ||
                    decision.contentSHA256 != NovelDocumentValidator.sha256(revision.content) {
                    issues.append("Injection receipt \(receipt.id) has invalid material evidence.")
                }
            }
            if Set(receipt.materialDecisions.map(\.materialID)).count !=
                receipt.materialDecisions.count {
                issues.append("Injection receipt \(receipt.id) repeats a material decision.")
            }
            if let includedMaterials = receipt.includedMaterials {
                let includedIDs = includedMaterials.map(\.materialID)
                let expectedIDs = receipt.materialDecisions.filter(\.included).map(\.materialID)
                if Set(includedIDs).count != includedIDs.count ||
                    Set(includedIDs) != Set(expectedIDs) {
                    issues.append("Injection receipt \(receipt.id) has an invalid included material list.")
                }
                for item in includedMaterials {
                    guard let material = document.materials.first(where: { $0.id == item.materialID }),
                          let decision = receipt.materialDecisions.first(where: {
                              $0.materialID == item.materialID && $0.included
                          }),
                          let revision = document.materialRevisions.first(where: {
                              $0.id == decision.revisionID && $0.materialID == item.materialID
                          }),
                          material.kind == item.kind,
                          revision.title == item.title else {
                        issues.append("Injection receipt \(receipt.id) has invalid material display evidence.")
                        continue
                    }
                }
            }
            if let includesPlotState = receipt.includesPlotState {
                let expected = receipt.sections.contains { section in
                    switch section.kind {
                    case .currentState, .pendingManualState: true
                    default: false
                    }
                }
                if includesPlotState != expected {
                    issues.append("Injection receipt \(receipt.id) has invalid state-presence evidence.")
                }
            }
            if let count = receipt.recentMessageRoundCount, count < 0 {
                issues.append("Injection receipt \(receipt.id) has a negative recent-message count.")
            }
            if let count = receipt.budgetExcludedItemCount, count < 0 {
                issues.append("Injection receipt \(receipt.id) has a negative budget exclusion count.")
            }
            if let factTransaction = receipt.factTransaction {
                let expectedPrompt: NovelPromptKind = factTransaction.kind == .stateDelta
                    ? .stateDeltaV1
                    : .manualSyncV1
                // 必须按「历史上发布过的版本集合」判定,不能拿当前模板版本做等值比较:
                // receipt 是不可变的历史记录,等值比较会让任何一次提示词版本推进把所有
                // 老项目判成损坏(2026-07-25 真机事故,见 acceptedVersions 注释)。
                if !NovelPromptCatalog.acceptedVersions(for: expectedPrompt)
                    .contains(receipt.promptVersion) {
                    issues.append("Injection receipt \(receipt.id) has the wrong fact Prompt version.")
                }
                validateFactReceiptLink(
                    factTransaction,
                    branchID: receipt.branchID,
                    runID: receipt.runID,
                    document: document,
                    issues: &issues
                )
            } else if !document.activeRuns.contains(where: {
                $0.id == receipt.runID && $0.branchID == receipt.branchID
            }) && !document.polishAttempts.contains(where: { attempt in
                attempt.runID == receipt.runID &&
                    attempt.injectionReceiptID == receipt.id &&
                    document.polishTransactions.contains(where: {
                        $0.id == attempt.transactionID && $0.branchID == receipt.branchID
                    })
            }) {
                issues.append("Injection receipt \(receipt.id) has no owning generation run.")
            }
        }
    }

    private static func validateGenerationReceipts(
        _ document: NovelProjectDocumentV1,
        injectionByID: [NovelReceiptID: NovelInjectionReceiptRecord],
        issues: inout [String]
    ) {
        for receipt in document.generationReceipts {
            guard let injection = injectionByID[receipt.injectionReceiptID] else {
                issues.append("Generation receipt \(receipt.id) has no injection receipt.")
                continue
            }
            if receipt.runID != injection.runID ||
                receipt.providerID != injection.providerID ||
                receipt.ownerProviderID != injection.ownerProviderID ||
                receipt.modelID != injection.modelID ||
                receipt.wireModelID != injection.wireModelID ||
                receipt.promptVersion != injection.promptVersion ||
                receipt.parameters != injection.parameters ||
                receipt.factTransaction != injection.factTransaction {
                issues.append("Generation receipt \(receipt.id) disagrees with its injection receipt.")
            }
            if !NovelDocumentValidator.isSHA256(receipt.requestSHA256) {
                issues.append("Generation receipt \(receipt.id) has an invalid request hash.")
            }
            if containsSensitiveParameter(receipt.parameters) {
                issues.append("Generation receipt \(receipt.id) contains a sensitive model parameter.")
            }
            if receipt.factTransaction == nil,
               !document.activeRuns.contains(where: { $0.id == receipt.runID }),
               !document.polishAttempts.contains(where: {
                   $0.runID == receipt.runID && $0.generationReceiptID == receipt.id
               }) {
                issues.append("Generation receipt \(receipt.id) has no owning generation run.")
            }
        }
    }

    private static func validatePolishReceiptCompleteness(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for attempt in document.polishAttempts {
            if document.activeRuns.contains(where: { $0.id == attempt.runID }) {
                issues.append("Polish assessment run collides with a user-visible run.")
            }
            let injections = document.injectionReceipts.filter {
                $0.id == attempt.injectionReceiptID && $0.runID == attempt.runID
            }
            let generations = document.generationReceipts.filter {
                $0.id == attempt.generationReceiptID &&
                    $0.runID == attempt.runID &&
                    $0.injectionReceiptID == attempt.injectionReceiptID
            }
            if injections.count != 1 || generations.count != 1 {
                issues.append("Polish assessment attempt has incomplete request receipts.")
            }
        }
    }

    private static func validateFactReceiptCompleteness(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let factInjections = document.injectionReceipts.filter { $0.factTransaction != nil }
        let factGenerations = document.generationReceipts.filter { $0.factTransaction != nil }
        if Set(factInjections.map(\.runID)).count != factInjections.count ||
            Set(factGenerations.map(\.runID)).count != factGenerations.count {
            issues.append("Fact-model request receipts reuse a request run ID.")
        }

        for injection in factInjections {
            let matchingGenerations = document.generationReceipts.filter {
                $0.injectionReceiptID == injection.id
            }
            if matchingGenerations.count != 1 {
                issues.append("A fact-model request must have exactly one generation receipt.")
            }
        }

        for operation in document.appliedOperations {
            let expectedKind: NovelFactReceiptKind? = switch (operation.kind, operation.outcome) {
            case (.collectCandidate, .candidateCollected(_, _, _, _, _, _)):
                .stateDelta
            case (.syncManualEdits, .manualSyncCommitted(_, _, _, _)):
                .manualRebuild
            default:
                nil
            }
            guard let expectedKind else { continue }
            let matchingInjections = document.injectionReceipts.filter {
                $0.factTransaction?.ownerOperationID == operation.operationID &&
                    $0.factTransaction?.kind == expectedKind
            }
            switch expectedKind {
            case .stateDelta where matchingInjections.contains(where: {
                $0.factTransaction?.chunkIndex != nil
            }):
                issues.append("A successful collection has invalid request receipts.")
            case .manualRebuild:
                let indices = matchingInjections.compactMap { $0.factTransaction?.chunkIndex }
                let validSingle = matchingInjections.count == 1 && indices.isEmpty
                let uniqueIndices = Set(indices).sorted()
                let validChunks = indices.count == matchingInjections.count &&
                    !uniqueIndices.isEmpty &&
                    uniqueIndices == Array(0...uniqueIndices.last!)
                if !validSingle && !validChunks {
                    issues.append("A successful manual sync has incomplete chunk receipts.")
                }
            default:
                break
            }
        }
    }

    private static func validateFactAttempts(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for attempt in document.factAttempts {
            if attempt.attemptOperationID == attempt.ownerOperationID ||
                (attempt.firstChunkIndex.map { $0 < 0 } ?? false) ||
                (attempt.kind == .stateDelta && attempt.firstChunkIndex != nil) ||
                (attempt.kind == .manualRebuild && attempt.firstChunkIndex == nil) ||
                !NovelDocumentValidator.isSHA256(attempt.attemptPayloadSHA256) ||
                !document.branches.contains(where: { $0.id == attempt.branchID }) {
                issues.append("A fact progress-attempt record is malformed.")
            }
            let pendingOwner = document.pendingOperations.contains(where: {
                $0.id == attempt.pendingID &&
                    $0.operationID == attempt.ownerOperationID &&
                    $0.branchID == attempt.branchID &&
                    ((attempt.kind == .stateDelta && $0.kind == .collection) ||
                        (attempt.kind == .manualRebuild && $0.kind == .manualSync))
            })
            let completedOwner = document.appliedOperations.first(where: { operation in
                guard operation.operationID == attempt.ownerOperationID else { return false }
                return switch (attempt.kind, operation.kind, operation.outcome) {
                case let (
                    .stateDelta,
                    .collectCandidate,
                    .candidateCollected(_, branchID, _, _, _, _)
                ):
                    branchID == attempt.branchID
                case let (
                    .manualRebuild,
                    .syncManualEdits,
                    .manualSyncCommitted(_, branchID, _, _)
                ):
                    branchID == attempt.branchID
                default:
                    false
                }
            })
            let hasCompletedOwnerReceipt = document.injectionReceipts.contains(where: {
                $0.factTransaction?.pendingID == attempt.pendingID &&
                    $0.factTransaction?.ownerOperationID == attempt.ownerOperationID &&
                    $0.factTransaction?.kind == attempt.kind &&
                    $0.branchID == attempt.branchID
            })
            let appliedAttempt = document.appliedOperations.first(where: {
                $0.operationID == attempt.attemptOperationID
            })
            let recoveredLegacyCollection = attempt.kind == .stateDelta &&
                completedOwner != nil &&
                appliedAttempt?.kind == .retryPending &&
                appliedAttempt?.payloadSHA256 == attempt.attemptPayloadSHA256 &&
                appliedAttempt?.outcome == completedOwner?.outcome &&
                appliedAttempt?.appliedProjectRevision == completedOwner?.appliedProjectRevision
            if !pendingOwner &&
                (completedOwner == nil || (!hasCompletedOwnerReceipt && !recoveredLegacyCollection)) {
                issues.append("A fact progress-attempt record has no pending or completed owner.")
            }
            let receiptLinks = document.injectionReceipts.compactMap(\.factTransaction).filter {
                $0.attemptOperationID == attempt.attemptOperationID
            }
            if receiptLinks.contains(where: {
                $0.pendingID != attempt.pendingID ||
                    $0.ownerOperationID != attempt.ownerOperationID ||
                    $0.attemptPayloadSHA256 != attempt.attemptPayloadSHA256 ||
                    $0.kind != attempt.kind ||
                    (attempt.kind == .stateDelta && $0.chunkIndex != nil) ||
                    (attempt.kind == .manualRebuild && $0.chunkIndex == nil)
            }) {
                issues.append("A fact progress-attempt receipt disagrees with its reservation.")
            }
            if let firstChunkIndex = attempt.firstChunkIndex,
               let firstDispatched = receiptLinks.compactMap(\.chunkIndex).min(),
               firstDispatched != firstChunkIndex,
               // A model-policy-change reset (`NovelManualSyncProgressReducer.resetProgress`)
               // can discard progress *after* this same attempt was already durably reserved
               // to continue from `firstChunkIndex`, so its receipts legitimately restart at
               // chunk 0 instead. `firstChunkIndex` is immutable once recorded (fact attempts
               // are append-only), so this is the only way that reconciliation can show up
               // here. Any other mismatch (dispatching from some other unexpected index) is
               // still rejected.
               firstDispatched != 0 {
                issues.append("A fact progress-attempt request does not begin at its reservation.")
            }
            if let operation = appliedAttempt {
                guard let completedOwner,
                      !receiptLinks.isEmpty || recoveredLegacyCollection,
                      operation.kind == .retryPending,
                      operation.payloadSHA256 == attempt.attemptPayloadSHA256,
                      operation.outcome == completedOwner.outcome,
                      operation.appliedProjectRevision ==
                        completedOwner.appliedProjectRevision else {
                    issues.append("A fact progress-attempt record conflicts with an applied operation.")
                    continue
                }
            }
        }
    }

    private static func validateRun(
        _ run: NovelActiveRunRecord,
        document: NovelProjectDocumentV1,
        generationByID: [NovelReceiptID: NovelGenerationReceiptRecord],
        injectionByID: [NovelReceiptID: NovelInjectionReceiptRecord],
        issues: inout [String]
    ) {
        guard let branch = document.branches.first(where: { $0.id == run.branchID }),
              let session = document.sessions.first(where: {
                  $0.id == run.sessionID && $0.branchID == run.branchID
              }) else {
            return
        }
        if !NovelDocumentValidator.isSHA256(run.requestPayloadSHA256) {
            issues.append("Run \(run.id) has an invalid request hash.")
        }
        if run.baseHeadRevision < 0 ||
            !document.checkpoints.contains(where: { $0.id == run.baseCheckpointID }) {
            issues.append("Run \(run.id) has an invalid base guard.")
        }
        guard let receipt = generationByID[run.receiptID] else {
            issues.append("Run \(run.id) has no generation receipt record.")
            return
        }
        guard receipt.runID == run.id else {
            issues.append("Run \(run.id) points to a generation receipt owned by another run.")
            return
        }
        if receipt.factTransaction != nil ||
            injectionByID[receipt.injectionReceiptID]?.factTransaction != nil {
            issues.append("Run \(run.id) points to an internal fact-model receipt.")
        }
        if !document.appliedOperations.contains(where: {
            $0.operationID == run.operationID && $0.kind == .startRun
        }) {
            issues.append("Run \(run.id) has no start operation.")
        }

        guard let userMessage = session.messages.first(where: { $0.id == run.userMessageID }) else {
            issues.append("Run \(run.id) has no durable user message.")
            return
        }
        if userMessage.role != .user ||
            userMessage.kind != .userInput ||
            userMessage.runID != run.id ||
            userMessage.candidateID != nil {
            issues.append("Run \(run.id) has an invalid user message.")
        }
        validateReceiptEvidence(
            run: run,
            userMessage: userMessage,
            generationReceipt: receipt,
            injectionByID: injectionByID,
            issues: &issues
        )

        validateRunShape(run, document: document, issues: &issues)

        let outputMessage = session.messages.first(where: { $0.id == run.messageID })
        switch run.status {
        case .running:
            if run.terminalAt != nil ||
                run.interruptionReason != nil ||
                run.terminalFailure != nil ||
                outputMessage != nil ||
                branch.activeRunID != run.id {
                issues.append("Running run \(run.id) contains terminal state.")
            }
        case .completed:
            if run.terminalAt == nil ||
                run.interruptionReason != nil ||
                run.terminalFailure != nil ||
                branch.activeRunID == run.id {
                issues.append("Completed run \(run.id) has invalid terminal state.")
            }
            validateCompletedOutput(run, message: outputMessage, document: document, issues: &issues)
        case .interrupted:
            if run.terminalAt == nil ||
                run.interruptionReason == nil ||
                run.terminalFailure != nil ||
                branch.activeRunID == run.id {
                issues.append("Interrupted run \(run.id) has invalid terminal state.")
            }
            if !run.partialContent.isEmpty {
                let expectedCandidateID = (run.kind == .prose || run.kind == .regenerate)
                    ? run.candidateID
                    : nil
                if outputMessage?.role != .assistant ||
                    outputMessage?.kind != .interruptedDraft ||
                    outputMessage?.content != run.partialContent ||
                    outputMessage?.runID != run.id ||
                    outputMessage?.candidateID != expectedCandidateID {
                    issues.append("Interrupted run \(run.id) has an invalid partial message.")
                }
            } else if outputMessage != nil {
                issues.append("Interrupted run \(run.id) unexpectedly persisted an empty message.")
            }
            if run.kind == .prose || run.kind == .regenerate,
               !run.partialContent.isEmpty,
               let candidateID = run.candidateID {
                guard let candidate = document.candidates.first(where: {
                    $0.id == candidateID &&
                        $0.kind == .prose &&
                        $0.branchID == run.branchID &&
                        $0.sessionID == run.sessionID &&
                        $0.sourceMessageID == run.messageID &&
                        $0.baseCheckpointID == run.baseCheckpointID &&
                        $0.baseHeadRevision == run.baseHeadRevision &&
                        $0.content == run.partialContent &&
                        $0.sourceChapterVersionID == run.sourceChapterVersionID
                }) else {
                    issues.append("Interrupted prose run \(run.id) has no matching candidate.")
                    return
                }
                if candidate.status != .interrupted && candidate.status != .collected {
                    issues.append("Interrupted prose run \(run.id) has an invalid candidate lifecycle.")
                }
            } else if document.candidates.contains(where: {
                $0.sourceMessageID == run.messageID ||
                    (run.candidateID != nil && $0.id == run.candidateID)
            }) {
                issues.append("Non-prose interrupted run \(run.id) unexpectedly persisted a candidate.")
            }
        case .failed:
            if run.terminalAt == nil ||
                run.interruptionReason != nil ||
                run.terminalFailure == nil ||
                branch.activeRunID == run.id {
                issues.append("Failed run \(run.id) has invalid terminal state.")
            }
            let expectedContent = run.partialContent.isEmpty
                ? run.terminalFailure?.message
                : run.partialContent
            let expectedKind: NovelSessionMessageKind = run.partialContent.isEmpty
                ? .error
                : .interruptedDraft
            if outputMessage?.role != .assistant ||
                outputMessage?.kind != expectedKind ||
                outputMessage?.content != expectedContent ||
                outputMessage?.runID != run.id ||
                outputMessage?.candidateID != nil {
                issues.append("Failed run \(run.id) has an invalid terminal message.")
            }
            if document.candidates.contains(where: {
                $0.sourceMessageID == run.messageID ||
                    (run.candidateID != nil && $0.id == run.candidateID)
            }) {
                issues.append("Failed run \(run.id) unexpectedly persisted a candidate.")
            }
        }
    }

    private static func validateReceiptEvidence(
        run: NovelActiveRunRecord,
        userMessage: NovelSessionMessageRecord,
        generationReceipt: NovelGenerationReceiptRecord,
        injectionByID: [NovelReceiptID: NovelInjectionReceiptRecord],
        issues: inout [String]
    ) {
        guard let injection = injectionByID[generationReceipt.injectionReceiptID],
              injection.runID == run.id else {
            return
        }
        let promptKind = promptKind(for: run)
        let promptSections = injection.sections.filter {
            if case .fixedPrompt = $0.kind { return true }
            return false
        }
        if let promptText = NovelPromptCatalog.systemText(
            for: promptKind,
            version: injection.promptVersion
        ) {
            let promptHash = NovelDocumentValidator.sha256(promptText)
            // Only fail-closed when the version is not even accepted. Same version string
            // with a later catalog text edit (or missing archival copy) must not brick load —
            // that is the 2026-07-25 / 2026-08-12 corruption class of bugs.
            if promptSections.map(\.contentSHA256) != [promptHash],
               !NovelPromptCatalog.acceptedVersions(for: promptKind)
                .contains(injection.promptVersion) {
                issues.append("Run \(run.id) has invalid fixed Prompt receipt evidence.")
            }
        }
        // Missing historical system text: skip hash re-check only.
        let preferenceSections = injection.sections.filter {
            if case .polishPreference = $0.kind { return true }
            return false
        }
        if (run.kind == .polish && preferenceSections.count != 1) ||
            (run.kind != .polish && !preferenceSections.isEmpty) {
            issues.append("Run \(run.id) has invalid polish-preference receipt evidence.")
        }

        let userInputHash = NovelDocumentValidator.sha256(userMessage.content)
        let userSections = injection.sections.filter {
            if case .userInput = $0.kind { return true }
            return false
        }
        if userSections.map(\.contentSHA256) != [userInputHash] {
            issues.append("Run \(run.id) has invalid user input receipt evidence.")
        }
    }

    private static func promptKind(for run: NovelActiveRunRecord) -> NovelPromptKind {
        switch run.kind {
        case .quickStart:
            .quickStart
        case .characterProposal:
            .characterProposal
        case .discussion:
            .discussion
        case .prose:
            run.granularity == .continuation ? .proseContinuation : .proseWholeChapter
        case .polish:
            .wholeChapterPolish
        case .regenerate:
            .wholeChapterRegeneration
        }
    }

    private static func validateRunShape(
        _ run: NovelActiveRunRecord,
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        switch run.kind {
        case .quickStart, .discussion, .characterProposal:
            if run.mode != .discussPlan ||
                run.granularity != nil ||
                run.candidateID != nil ||
                run.sourceChapterVersionID != nil {
                issues.append("Run \(run.id) has an invalid discussion shape.")
            }
            if run.kind == .characterProposal,
               run.contextualCharacterMention?
                .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false {
                issues.append("Run \(run.id) has no contextual character mention.")
            }
        case .prose:
            if run.mode != .writeProse ||
                run.granularity == nil ||
                run.candidateID == nil ||
                run.sourceChapterVersionID != nil {
                issues.append("Run \(run.id) has an invalid prose shape.")
            }
        case .regenerate:
            // 与 prose 的唯一差别:必须携带被重写章的版本 id。
            if run.mode != .writeProse ||
                run.granularity != .wholeChapter ||
                run.candidateID == nil ||
                run.sourceChapterVersionID == nil {
                issues.append("Run \(run.id) has an invalid regeneration shape.")
            }
            if let versionID = run.sourceChapterVersionID {
                if !document.chapterVersions.contains(where: { $0.id == versionID }) {
                    issues.append("Run \(run.id) has a missing regeneration source version.")
                }
                if let baseCheckpoint = document.checkpoints.first(where: {
                    $0.id == run.baseCheckpointID
                }), !baseCheckpoint.chapterSelections.contains(where: {
                    $0.versionID == versionID
                }) {
                    issues.append(
                        "Run \(run.id) has a regeneration source outside its base checkpoint."
                    )
                }
            }
        case .polish:
            if run.mode != .writeProse ||
                run.granularity != nil ||
                run.candidateID == nil ||
                run.sourceChapterVersionID == nil {
                issues.append("Run \(run.id) has an invalid polish shape.")
            }
            if let versionID = run.sourceChapterVersionID,
               !document.chapterVersions.contains(where: { $0.id == versionID }) {
                issues.append("Run \(run.id) has a missing polish source version.")
            }
        }
    }

    private static func validateCompletedOutput(
        _ run: NovelActiveRunRecord,
        message: NovelSessionMessageRecord?,
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        guard let message else {
            issues.append("Completed run \(run.id) has no output message.")
            return
        }
        let expectedKind: NovelSessionMessageKind = switch run.kind {
        case .quickStart, .characterProposal, .discussion: .discussion
        case .prose, .regenerate: .proseCandidate
        case .polish: .polishCandidate
        }
        let expectedContent: String
        let isAskUserCompletion: Bool
        if case .some(.askUser(_)) = message.interaction {
            isAskUserCompletion = true
        } else {
            isAskUserCompletion = false
        }
        if run.kind == .quickStart && !isAskUserCompletion {
            if let suggestions = try? NovelStructuredOutputDecoder.decodeQuickStartSuggestions(
                from: run.partialContent
            ) {
                expectedContent = NovelGenerationReducer.quickStartMarkdown(suggestions)
            } else {
                issues.append("Completed quick-start run \(run.id) has invalid structured output.")
                expectedContent = run.partialContent
            }
        } else if run.kind == .characterProposal {
            if let proposal = try? NovelStructuredOutputDecoder.decodeCharacterProposal(
                from: run.partialContent
            ) {
                expectedContent = NovelGenerationReducer.characterProposalMarkdown(proposal)
            } else {
                issues.append(
                    "Completed character-proposal run \(run.id) has invalid structured output."
                )
                expectedContent = run.partialContent
            }
        } else {
            expectedContent = run.partialContent
        }
        if message.role != .assistant ||
            message.kind != expectedKind ||
            message.content != expectedContent ||
            message.runID != run.id ||
            message.candidateID != run.candidateID {
            issues.append("Completed run \(run.id) has an invalid output message.")
        }
        guard let candidateID = run.candidateID else { return }
        guard let candidate = document.candidates.first(where: {
            $0.id == candidateID &&
                $0.branchID == run.branchID &&
                $0.sessionID == run.sessionID &&
                $0.sourceMessageID == run.messageID &&
                $0.baseCheckpointID == run.baseCheckpointID &&
                $0.baseHeadRevision == run.baseHeadRevision &&
                $0.content == run.partialContent &&
                $0.sourceChapterVersionID == run.sourceChapterVersionID
        }) else {
            issues.append("Completed run \(run.id) has no matching candidate.")
            return
        }
        let validLifecycle = switch (run.kind, candidate.kind, candidate.status) {
        case (.prose, .prose, .available),
             (.prose, .prose, .collected),
             (.prose, .prose, .superseded),
             // 整章重新生成产出的候选 kind 就是 .prose(见 NovelGenerationReducer 的
             // `run.kind == .polish ? .polish : .prose`),但这里此前只列了 .prose 与
             // .polish 两族,于是任何 .regenerate 的 run 一完成就判文档非法、写盘失败。
             (.regenerate, .prose, .available),
             (.regenerate, .prose, .collected),
             (.regenerate, .prose, .superseded),
             (.polish, .polish, .available),
             (.polish, .polish, .adopted),
             (.polish, .polish, .superseded):
            true
        default:
            false
        }
        if !validLifecycle {
            issues.append("Completed run \(run.id) candidate has an invalid lifecycle state.")
        }
    }

    private static func validateFactReceiptLink(
        _ link: NovelFactReceiptLink,
        branchID: NovelBranchID,
        runID: NovelRunID,
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        if document.activeRuns.contains(where: { $0.id == runID }) {
            issues.append("Fact-model receipt run \(runID) collides with a user-visible run.")
        }
        if !NovelDocumentValidator.isSHA256(link.attemptPayloadSHA256) {
            issues.append("Fact-model receipt has an invalid attempt payload hash.")
        }
        if link.kind == .stateDelta, link.chunkIndex != nil {
            issues.append("Collection fact-model receipt unexpectedly contains a chunk index.")
        }
        if let owner = document.appliedOperations.first(where: {
            $0.operationID == link.ownerOperationID
        }) {
            validateCompletedFactReceiptLink(
                link,
                branchID: branchID,
                owner: owner,
                document: document,
                issues: &issues
            )
            return
        }

        guard let pending = document.pendingOperations.first(where: {
            $0.id == link.pendingID &&
                $0.operationID == link.ownerOperationID &&
                $0.branchID == branchID &&
                ((link.kind == .stateDelta && $0.kind == .collection) ||
                    (link.kind == .manualRebuild && $0.kind == .manualSync))
        }) else {
            issues.append("Fact-model receipt has no pending or completed owner.")
            return
        }
        if link.kind == .manualRebuild {
            guard let chunkIndex = link.chunkIndex else {
                issues.append("Manual-sync request receipt has no chunk index.")
                return
            }
            // When `manualSyncProgress` is nil, this is either a genuinely fresh pending
            // (never attempted) or one whose progress a legitimate model-policy-change reset
            // just discarded (`NovelManualSyncProgressReducer.resetProgress`). A reset cannot
            // retroactively delete the stale chunk-N request receipt an abandoned attempt
            // sequence already reserved (receipts are append-only), so there is no single
            // "expected next chunk" to check against in that state — tolerate any chunk index
            // here; `hasAttemptEvidence` below still requires durable reservation evidence, so
            // this cannot admit a fabricated receipt. With progress present, the strict
            // completed-or-current check remains unchanged.
            if let progress = pending.manualSyncProgress {
                let isCompletedChunk = progress.completedChunks.contains(where: {
                    $0.index == chunkIndex
                })
                let isCurrentChunk = chunkIndex == progress.nextChunkIndex
                if !isCompletedChunk && !isCurrentChunk {
                    issues.append("Manual-sync request receipt is outside durable progress.")
                }
            }
        }
        let hasAttemptEvidence = link.attemptOperationID == link.ownerOperationID
            ? link.attemptPayloadSHA256 == pending.payloadSHA256
            : document.factAttempts.contains(where: {
                $0.pendingID == link.pendingID &&
                    $0.ownerOperationID == link.ownerOperationID &&
                    $0.attemptOperationID == link.attemptOperationID &&
                    $0.attemptPayloadSHA256 == link.attemptPayloadSHA256 &&
                    $0.branchID == branchID &&
                    $0.kind == link.kind
            })
        if !hasAttemptEvidence {
            issues.append("Fact-model receipt has no durable attempt evidence.")
        }
    }

    private static func validateCompletedFactReceiptLink(
        _ link: NovelFactReceiptLink,
        branchID: NovelBranchID,
        owner: NovelAppliedOperationRecord,
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {

        let ownerMatches: Bool = switch (link.kind, owner.kind, owner.outcome) {
        case let (
            .stateDelta,
            .collectCandidate,
            .candidateCollected(_, outcomeBranchID, _, _, _, _)
        ):
            outcomeBranchID == branchID
        case let (
            .manualRebuild,
            .syncManualEdits,
            .manualSyncCommitted(_, outcomeBranchID, _, _)
        ):
            outcomeBranchID == branchID
        default:
            false
        }
        guard ownerMatches else {
            issues.append("Fact-model receipt owner kind, branch, or outcome is invalid.")
            return
        }

        if link.attemptOperationID == link.ownerOperationID {
            if link.attemptPayloadSHA256 != owner.payloadSHA256 {
                issues.append("Fact-model receipt first attempt disagrees with its owner.")
            }
            return
        }
        if let attempt = document.appliedOperations.first(where: {
            $0.operationID == link.attemptOperationID
        }) {
            if attempt.kind != .retryPending ||
                attempt.payloadSHA256 != link.attemptPayloadSHA256 ||
                attempt.outcome != owner.outcome ||
                attempt.appliedProjectRevision != owner.appliedProjectRevision {
                issues.append("Fact-model receipt retry attempt is not linked to its owner outcome.")
            }
        } else if !document.factAttempts.contains(where: {
            $0.pendingID == link.pendingID &&
                $0.ownerOperationID == link.ownerOperationID &&
                $0.attemptOperationID == link.attemptOperationID &&
                $0.attemptPayloadSHA256 == link.attemptPayloadSHA256 &&
                $0.branchID == branchID &&
                $0.kind == link.kind
        }) {
            issues.append("Fact-model receipt has no durable progress-attempt evidence.")
        }
    }

    private static func sameIdentity(
        _ lhs: NovelActiveRunRecord,
        _ rhs: NovelActiveRunRecord
    ) -> Bool {
        lhs.id == rhs.id &&
            lhs.operationID == rhs.operationID &&
            lhs.requestPayloadSHA256 == rhs.requestPayloadSHA256 &&
            lhs.branchID == rhs.branchID &&
            lhs.sessionID == rhs.sessionID &&
            lhs.kind == rhs.kind &&
            lhs.mode == rhs.mode &&
            lhs.granularity == rhs.granularity &&
            lhs.userMessageID == rhs.userMessageID &&
            lhs.messageID == rhs.messageID &&
            lhs.candidateID == rhs.candidateID &&
            lhs.sourceChapterVersionID == rhs.sourceChapterVersionID &&
            lhs.baseCheckpointID == rhs.baseCheckpointID &&
            lhs.baseHeadRevision == rhs.baseHeadRevision &&
            lhs.receiptID == rhs.receiptID &&
            lhs.startedAt == rhs.startedAt
    }

    private static func containsSensitiveParameter(_ parameters: [String: String]) -> Bool {
        let sensitive = [
            "apikey", "authorization", "cookie", "credential", "oauth",
            "password", "secret", "accesstoken", "refreshtoken", "bearer"
        ]
        return parameters.keys.contains { key in
            let normalized = key.lowercased().filter(\.isLetter)
            return sensitive.contains(where: normalized.contains)
        }
    }
}
