import Foundation

struct NovelGenerationStartArtifacts: Equatable, Sendable {
    let injectionReceipt: NovelInjectionReceiptRecord
    let generationReceipt: NovelGenerationReceiptRecord
}

enum NovelGenerationReducer {
    static func normalizingLegacyInterruptedProseCandidates(
        _ document: NovelProjectDocumentV1
    ) -> NovelProjectDocumentV1 {
        var normalized = document
        var candidateIDs = Set(normalized.candidates.map(\.id))
        var candidateMessageIDs = Set(normalized.candidates.map(\.sourceMessageID))

        for run in normalized.activeRuns where
            run.status == .interrupted &&
            run.kind == .prose &&
            !run.partialContent.isEmpty {
            guard let candidateID = run.candidateID,
                  !candidateIDs.contains(candidateID),
                  !candidateMessageIDs.contains(run.messageID),
                  let sessionIndex = normalized.sessions.firstIndex(where: {
                      $0.id == run.sessionID && $0.branchID == run.branchID
                  }),
                  let messageIndex = normalized.sessions[sessionIndex].messages.firstIndex(where: {
                      $0.id == run.messageID
                  }) else {
                continue
            }
            let message = normalized.sessions[sessionIndex].messages[messageIndex]
            guard message.role == .assistant,
                  message.mode == run.mode,
                  message.kind == .interruptedDraft,
                  message.content == run.partialContent,
                  message.runID == run.id,
                  message.candidateID == nil,
                  message.interaction == nil else {
                continue
            }

            normalized.sessions[sessionIndex].messages[messageIndex] = NovelSessionMessageRecord(
                id: message.id,
                sequence: message.sequence,
                role: message.role,
                mode: message.mode,
                kind: message.kind,
                content: message.content,
                createdAt: message.createdAt,
                runID: message.runID,
                candidateID: candidateID
            )
            normalized.candidates.append(NovelCandidateRecord(
                id: candidateID,
                kind: .prose,
                branchID: run.branchID,
                sessionID: run.sessionID,
                sourceMessageID: run.messageID,
                baseCheckpointID: run.baseCheckpointID,
                baseHeadRevision: run.baseHeadRevision,
                status: .interrupted,
                content: run.partialContent,
                sourceChapterVersionID: run.sourceChapterVersionID,
                collectedCheckpointID: nil,
                chapterPlanDigest: run.chapterPlanDigest,
                ghostwritePlanID: run.ghostwritePlanID,
                createdAt: message.createdAt
            ))
            candidateIDs.insert(candidateID)
            candidateMessageIDs.insert(run.messageID)
        }
        return normalized
    }

    static func begin(
        _ request: NovelRunRequest,
        artifacts: NovelGenerationStartArtifacts,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        guard request.projectID == document.project.id else {
            throw NovelError.projectNotFound(request.projectID)
        }

        let payloadSHA256 = try request.canonicalPayloadSHA256()
        if let applied = document.appliedOperations.first(where: {
            $0.operationID == request.operationID
        }) {
            guard applied.kind == .startRun,
                  applied.payloadSHA256 == payloadSHA256,
                  case .runStarted(
                    request.projectID,
                    request.branchID,
                    request.id,
                    request.generationReceiptID,
                    _
                  ) = applied.outcome else {
                throw NovelError.idempotencyConflict(request.operationID)
            }
            return (document, applied.outcome)
        }
        guard document.factAttempts.allSatisfy({
            $0.attemptOperationID != request.operationID
        }), document.polishTransactions.allSatisfy({
            $0.operationID != request.operationID
        }) else {
            throw NovelError.idempotencyConflict(request.operationID)
        }

        let branchIndex = try branchIndex(for: request.branchID, in: document)
        let branch = document.branches[branchIndex]
        guard branch.lifecycle == .active else {
            throw NovelError.branchNotFound(request.branchID)
        }
        guard let sessionIndex = document.sessions.firstIndex(where: {
            $0.id == branch.sessionID && $0.branchID == branch.id
        }) else {
            throw NovelError.sessionNotFound(branch.sessionID)
        }
        guard request.expectedProjectRevision == document.project.revision else {
            throw NovelError.staleProjectRevision(
                expected: request.expectedProjectRevision,
                actual: document.project.revision
            )
        }
        guard request.expectedConfigRevision == document.project.configRevision else {
            throw NovelError.staleConfigRevision(
                expected: request.expectedConfigRevision,
                actual: document.project.configRevision
            )
        }
        guard request.expectedBranchHeadRevision == branch.headRevision else {
            throw NovelError.staleBranchHeadRevision(
                expected: request.expectedBranchHeadRevision,
                actual: branch.headRevision
            )
        }
        guard branch.activeRunID == nil else {
            throw NovelError.projectBusy(document.project.id)
        }

        try validateShape(request, branch: branch, document: document)
        try validateUniqueIDs(request, document: document)
        try validateArtifacts(artifacts, request: request, document: document)

        let session = document.sessions[sessionIndex]
        if let response = request.askUserResponse {
            try validateAskUserResponse(
                response,
                requestKind: request.kind,
                in: session,
                document: document
            )
        }
        let userMessage = NovelSessionMessageRecord(
            id: request.userMessageID,
            sequence: Int64(session.messages.count),
            role: .user,
            mode: request.mode,
            kind: .userInput,
            content: request.userText,
            createdAt: now,
            runID: request.id,
            candidateID: nil,
            interaction: request.askUserResponse.map(NovelSessionInteraction.askUserAnswer)
        )
        let boundPlanDigest: String?
        if request.kind == .prose, request.granularity == .wholeChapter {
            boundPlanDigest = document.confirmedChapterPlan(for: branch.id)?.contentDigest
        } else {
            boundPlanDigest = nil
        }
        let activeRun = NovelActiveRunRecord(
            id: request.id,
            operationID: request.operationID,
            requestPayloadSHA256: payloadSHA256,
            branchID: request.branchID,
            sessionID: session.id,
            kind: request.kind,
            mode: request.mode,
            granularity: request.granularity,
            userMessageID: request.userMessageID,
            messageID: request.assistantMessageID,
            candidateID: request.candidateID,
            sourceChapterVersionID: request.sourceChapterVersionID,
            contextualCharacterMention: request.contextualCharacterMention,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            status: .running,
            partialContent: "",
            receiptID: request.generationReceiptID,
            startedAt: now,
            terminalAt: nil,
            interruptionReason: nil,
            terminalFailure: nil,
            chapterPlanDigest: boundPlanDigest,
            ghostwritePlanID: request.ghostwritePlanID
        )

        var next = document
        next.sessions[sessionIndex].messages.append(userMessage)
        next.sessions[sessionIndex].revision += 1
        next.branches[branchIndex].activeRunID = request.id
        next.branches[branchIndex].updatedAt = now
        next.activeRuns.append(activeRun)
        next.injectionReceipts.append(artifacts.injectionReceipt)
        next.generationReceipts.append(artifacts.generationReceipt)
        if request.kind == .prose, let granularity = request.granularity {
            next.project.lastGenerationGranularity = granularity
        }
        next.project.revision += 1
        next.project.updatedAt = now

        let outcome = NovelOutcome.runStarted(
            projectID: request.projectID,
            branchID: request.branchID,
            runID: request.id,
            receiptID: request.generationReceiptID,
            revision: next.project.revision
        )
        next.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: request.operationID,
            kind: .startRun,
            payloadSHA256: payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: next.project.revision,
            appliedAt: now
        ))

        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome)
    }

    static func complete(
        runID: NovelRunID,
        content: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (
        document: NovelProjectDocumentV1,
        message: NovelSessionMessageSnapshot?
    ) {
        guard let runIndex = document.activeRuns.firstIndex(where: { $0.id == runID }) else {
            return (document, nil)
        }
        let existingRun = document.activeRuns[runIndex]
        if existingRun.status == .completed,
           existingRun.partialContent == content,
           let session = document.sessions.first(where: { $0.id == existingRun.sessionID }),
           let message = session.messages.first(where: { $0.id == existingRun.messageID }) {
            return (
                document,
                NovelSessionMessageSnapshot(
                    projectID: document.project.id,
                    branchID: existingRun.branchID,
                    message: message
                )
            )
        }
        guard existingRun.status == .running else { return (document, nil) }
        guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NovelError.invalidInput("A completed generation cannot be empty.")
        }

        let run = document.activeRuns[runIndex]
        let branchIndex = try branchIndex(for: run.branchID, in: document)
        guard document.branches[branchIndex].activeRunID == run.id else {
            return (document, nil)
        }
        guard let sessionIndex = document.sessions.firstIndex(where: {
            $0.id == run.sessionID && $0.branchID == run.branchID
        }) else {
            throw NovelError.sessionNotFound(run.sessionID)
        }

        let messageKind: NovelSessionMessageKind
        let quickStartSuggestions: NovelQuickStartSuggestionsV2?
        let characterProposal: NovelCharacterProposalV1?
        switch run.kind {
        case .quickStart:
            messageKind = .discussion
            quickStartSuggestions = try NovelStructuredOutputDecoder
                .decodeQuickStartSuggestions(from: content)
            characterProposal = nil
        case .characterProposal:
            messageKind = .discussion
            quickStartSuggestions = nil
            characterProposal = try NovelStructuredOutputDecoder
                .decodeCharacterProposal(from: content)
        case .discussion:
            messageKind = .discussion
            quickStartSuggestions = nil
            characterProposal = nil
        case .prose, .regenerate:
            messageKind = .proseCandidate
            quickStartSuggestions = nil
            characterProposal = nil
        case .polish:
            messageKind = .polishCandidate
            quickStartSuggestions = nil
            characterProposal = nil
        }
        let messageContent = quickStartSuggestions.map(quickStartMarkdown) ??
            characterProposal.map(characterProposalMarkdown) ?? content
        let message = NovelSessionMessageRecord(
            id: run.messageID,
            sequence: Int64(document.sessions[sessionIndex].messages.count),
            role: .assistant,
            mode: run.mode,
            kind: messageKind,
            content: messageContent,
            createdAt: now,
            runID: run.id,
            candidateID: run.candidateID
        )

        var next = document
        next.sessions[sessionIndex].messages.append(message)
        next.sessions[sessionIndex].revision += 1
        if let quickStartSuggestions {
            let proposals = try quickStartProposals(
                quickStartSuggestions,
                run: run,
                document: document,
                now: now
            )
            for index in next.settingProposals.indices {
                let proposal = next.settingProposals[index]
                guard proposal.branchID == run.branchID,
                      !proposal.isResolved,
                      proposal.supersededByRunID == nil,
                      case .some(.quickStart) = proposal.origin else { continue }
                next.settingProposals[index].supersededByRunID = run.id
            }
            next.settingProposals.append(contentsOf: proposals)
        }
        if let characterProposal {
            next.settingProposals.append(contentsOf: try contextualCharacterProposals(
                characterProposal,
                run: run,
                document: document,
                now: now
            ))
        }
        if let candidateID = run.candidateID {
            let candidateKind: NovelCandidateKind = run.kind == .polish ? .polish : .prose
            next.candidates.append(NovelCandidateRecord(
                id: candidateID,
                kind: candidateKind,
                branchID: run.branchID,
                sessionID: run.sessionID,
                sourceMessageID: run.messageID,
                baseCheckpointID: run.baseCheckpointID,
                baseHeadRevision: run.baseHeadRevision,
                status: .available,
                content: content,
                sourceChapterVersionID: run.sourceChapterVersionID,
                collectedCheckpointID: nil,
                chapterPlanDigest: run.chapterPlanDigest,
                ghostwritePlanID: run.ghostwritePlanID,
                createdAt: now
            ))
        }
        finish(
            runIndex: runIndex,
            branchIndex: branchIndex,
            status: .completed,
            content: content,
            interruptionReason: nil,
            failure: nil,
            now: now,
            document: &next
        )

        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (
            next,
            NovelSessionMessageSnapshot(
                projectID: next.project.id,
                branchID: run.branchID,
                message: message
            )
        )
    }

    static func completeAwaitingUser(
        runID: NovelRunID,
        prompt: NovelAskUserPrompt,
        preface: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (
        document: NovelProjectDocumentV1,
        message: NovelSessionMessageSnapshot?
    ) {
        try validateAskUserPrompt(prompt)
        guard let runIndex = document.activeRuns.firstIndex(where: { $0.id == runID }) else {
            return (document, nil)
        }
        let existingRun = document.activeRuns[runIndex]
        if existingRun.status == .completed,
           let session = document.sessions.first(where: { $0.id == existingRun.sessionID }),
           let message = session.messages.first(where: { $0.id == existingRun.messageID }),
           message.interaction == .askUser(prompt) {
            return (
                document,
                NovelSessionMessageSnapshot(
                    projectID: document.project.id,
                    branchID: existingRun.branchID,
                    message: message
                )
            )
        }
        guard existingRun.status == .running,
              existingRun.kind == .discussion || existingRun.kind == .quickStart else {
            return (document, nil)
        }
        let branchIndex = try branchIndex(for: existingRun.branchID, in: document)
        guard document.branches[branchIndex].activeRunID == runID,
              let sessionIndex = document.sessions.firstIndex(where: {
                  $0.id == existingRun.sessionID && $0.branchID == existingRun.branchID
              }) else {
            return (document, nil)
        }
        let content = preface.trimmingCharacters(in: .whitespacesAndNewlines)
        let message = NovelSessionMessageRecord(
            id: existingRun.messageID,
            sequence: Int64(document.sessions[sessionIndex].messages.count),
            role: .assistant,
            mode: existingRun.mode,
            kind: .discussion,
            content: content,
            createdAt: now,
            runID: runID,
            candidateID: nil,
            interaction: .askUser(prompt)
        )

        var next = document
        next.sessions[sessionIndex].messages.append(message)
        next.sessions[sessionIndex].revision += 1
        finish(
            runIndex: runIndex,
            branchIndex: branchIndex,
            status: .completed,
            content: content,
            interruptionReason: nil,
            failure: nil,
            now: now,
            document: &next
        )
        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (
            next,
            NovelSessionMessageSnapshot(
                projectID: next.project.id,
                branchID: existingRun.branchID,
                message: message
            )
        )
    }

    static func quickStartMarkdown(
        _ suggestions: NovelQuickStartSuggestionsV2
    ) -> String {
        let sections = [("世界观", suggestions.world)] +
            suggestions.characters.map { ("人物", $0) } +
            [
                ("总剧情大纲", suggestions.masterOutline),
                ("写作要求", suggestions.writingRequirements)
            ]
        let body = sections.map { heading, suggestion in
            "## \(heading)：\(suggestion.title)\n\n\(suggestion.content)"
        }.joined(separator: "\n\n")
        return "# 创作建议\n\n\(suggestions.overview)\n\n\(body)"
    }

    static func characterProposalMarkdown(_ proposal: NovelCharacterProposalV1) -> String {
        var sections = [
            "# 人物建议：\(proposal.character.title)\n\n\(proposal.character.content)"
        ]
        if !proposal.relatedSuggestions.isEmpty {
            sections.append(contentsOf: proposal.relatedSuggestions.map { suggestion in
                let heading = switch suggestion.kind {
                case .relationship: "人物关系"
                case .world: "世界观"
                case .plot: "剧情"
                }
                return "## 相关\(heading)：\(suggestion.title)\n\n\(suggestion.content)"
            })
        }
        return sections.joined(separator: "\n\n")
    }

    private static func contextualCharacterProposals(
        _ output: NovelCharacterProposalV1,
        run: NovelActiveRunRecord,
        document: NovelProjectDocumentV1,
        now: Date
    ) throws -> [NovelSettingProposalRecord] {
        guard let sourceMention = run.contextualCharacterMention else {
            throw NovelError.invalidInput("The character-proposal run lost its source mention.")
        }
        let typed: [(stableID: String, kind: NovelMaterialKind, suggestion: NovelQuickStartSuggestionV1)] = [
            (
                "character",
                .character,
                NovelQuickStartSuggestionV1(
                    title: output.character.title,
                    content: output.character.content,
                    aliases: NovelCharacterIdentityResolver.normalizedAliases(
                        [sourceMention] + (output.character.aliases ?? [])
                    )
                )
            )
        ] + output.relatedSuggestions.map { suggestion in
            let kind: NovelMaterialKind = switch suggestion.kind {
            case .relationship: .relationship
            case .world: .world
            case .plot: .masterOutline
            }
            return (
                "related-\(suggestion.kind.rawValue)",
                kind,
                NovelQuickStartSuggestionV1(
                    title: suggestion.title,
                    content: suggestion.content
                )
            )
        }
        return try typed.map { item in
            let id = NovelProposalID(deterministicProposalID(
                operationID: run.operationID,
                namespace: "contextual-character",
                stableID: item.stableID
            ))
            guard document.settingProposals.allSatisfy({ $0.id != id }) else {
                throw NovelError.immutableRecordConflict("setting proposal \(id)")
            }
            return NovelSettingProposalRecord(
                id: id,
                branchID: run.branchID,
                title: item.suggestion.title,
                content: item.suggestion.content,
                createdAt: now,
                isResolved: false,
                origin: .contextualCharacter(
                    runID: run.id,
                    sourceMention: sourceMention,
                    suggestedKind: item.kind
                ),
                suggestedCharacterAliases: item.kind == .character
                    ? item.suggestion.aliases ?? []
                    : nil
            )
        }
    }

    private static func quickStartProposals(
        _ suggestions: NovelQuickStartSuggestionsV2,
        run: NovelActiveRunRecord,
        document: NovelProjectDocumentV1,
        now: Date
    ) throws -> [NovelSettingProposalRecord] {
        let typed = [("world", NovelMaterialKind.world, suggestions.world)] +
            suggestions.characters.enumerated().map { index, character in
                ("character-\(index)", NovelMaterialKind.character, character)
            } +
            [
                ("master-outline", NovelMaterialKind.masterOutline, suggestions.masterOutline),
                ("writing-requirements", NovelMaterialKind.writingRequirements,
                 suggestions.writingRequirements)
            ]
        return try typed.map { stableID, kind, suggestion in
            let id = NovelProposalID(deterministicQuickStartProposalID(
                operationID: run.operationID,
                stableID: stableID
            ))
            guard document.settingProposals.allSatisfy({ $0.id != id }) else {
                throw NovelError.immutableRecordConflict("setting proposal \(id)")
            }
            return NovelSettingProposalRecord(
                id: id,
                branchID: run.branchID,
                title: suggestion.title,
                content: suggestion.content,
                createdAt: now,
                isResolved: false,
                origin: .quickStart(runID: run.id, suggestedKind: kind),
                suggestedCharacterAliases: kind == .character ? suggestion.aliases ?? [] : nil
            )
        }
    }

    private static func deterministicQuickStartProposalID(
        operationID: NovelOperationID,
        stableID: String
    ) -> UUID {
        deterministicProposalID(
            operationID: operationID,
            namespace: "quick-start",
            stableID: stableID
        )
    }

    private static func deterministicProposalID(
        operationID: NovelOperationID,
        namespace: String,
        stableID: String
    ) -> UUID {
        let hex = NovelDocumentValidator.sha256(
            operationID.description + "|\(namespace)-proposal|" + stableID
        )
        let value = String(hex.prefix(8)) + "-" +
            String(hex.dropFirst(8).prefix(4)) + "-" +
            String(hex.dropFirst(12).prefix(4)) + "-" +
            String(hex.dropFirst(16).prefix(4)) + "-" +
            String(hex.dropFirst(20).prefix(12))
        return UUID(uuidString: value)!
    }

    static func interrupt(
        runID: NovelRunID,
        reason: NovelRunInterruptionReason,
        partialContent: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (
        document: NovelProjectDocumentV1,
        message: NovelSessionMessageSnapshot?
    ) {
        let result = try interruptedDocument(
            runID: runID,
            reason: reason,
            partialContent: partialContent,
            in: document,
            now: now
        )
        guard result.didTransition else { return (document, nil) }
        try NovelDocumentValidator.validateTransition(from: document, to: result.document)
        return (result.document, result.message)
    }

    static func interrupt(
        _ command: NovelCancelRunCommand,
        partialContent: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (
        document: NovelProjectDocumentV1,
        outcome: NovelOutcome?,
        message: NovelSessionMessageSnapshot?
    ) {
        guard command.projectID == document.project.id else {
            throw NovelError.projectNotFound(command.projectID)
        }
        let payloadSHA256 = try NovelAction.cancelRun(command).canonicalPayloadSHA256()
        if let applied = document.appliedOperations.first(where: {
            $0.operationID == command.context.operationID
        }) {
            guard applied.kind == .cancelRun,
                  applied.payloadSHA256 == payloadSHA256,
                  case .runInterrupted(
                    command.projectID,
                    command.runID,
                    command.reason,
                    _
                  ) = applied.outcome else {
                throw NovelError.idempotencyConflict(command.context.operationID)
            }
            return (document, applied.outcome, nil)
        }
        guard document.factAttempts.allSatisfy({
            $0.attemptOperationID != command.context.operationID
        }), document.polishTransactions.allSatisfy({
            $0.operationID != command.context.operationID
        }) else {
            throw NovelError.idempotencyConflict(command.context.operationID)
        }

        guard let run = document.activeRuns.first(where: { $0.id == command.runID }),
              run.status == .running else {
            return (document, nil, nil)
        }
        let result = try interruptedDocument(
            runID: command.runID,
            reason: command.reason,
            partialContent: partialContent,
            in: document,
            now: now
        )
        guard result.didTransition else { return (document, nil, nil) }

        var next = result.document
        let outcome = NovelOutcome.runInterrupted(
            projectID: command.projectID,
            runID: command.runID,
            reason: command.reason,
            revision: next.project.revision
        )
        next.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: command.context.operationID,
            kind: .cancelRun,
            payloadSHA256: payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: next.project.revision,
            appliedAt: now
        ))

        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (next, outcome, result.message)
    }

    static func fail(
        runID: NovelRunID,
        failure: NovelFailure,
        partialContent: String,
        in document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (
        document: NovelProjectDocumentV1,
        message: NovelSessionMessageSnapshot?
    ) {
        guard let runIndex = document.activeRuns.firstIndex(where: { $0.id == runID }),
              document.activeRuns[runIndex].status == .running else {
            return (document, nil)
        }
        guard !failure.code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !failure.message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NovelError.invalidInput("A generation failure requires a code and message.")
        }

        let run = document.activeRuns[runIndex]
        let branchIndex = try branchIndex(for: run.branchID, in: document)
        guard document.branches[branchIndex].activeRunID == run.id else {
            return (document, nil)
        }
        guard let sessionIndex = document.sessions.firstIndex(where: {
            $0.id == run.sessionID && $0.branchID == run.branchID
        }) else {
            throw NovelError.sessionNotFound(run.sessionID)
        }

        let messageContent = partialContent.isEmpty ? failure.message : partialContent
        let messageKind: NovelSessionMessageKind = partialContent.isEmpty ? .error : .interruptedDraft
        let message = NovelSessionMessageRecord(
            id: run.messageID,
            sequence: Int64(document.sessions[sessionIndex].messages.count),
            role: .assistant,
            mode: run.mode,
            kind: messageKind,
            content: messageContent,
            createdAt: now,
            runID: run.id,
            candidateID: nil
        )

        var next = document
        next.sessions[sessionIndex].messages.append(message)
        next.sessions[sessionIndex].revision += 1
        finish(
            runIndex: runIndex,
            branchIndex: branchIndex,
            status: .failed,
            content: partialContent,
            interruptionReason: nil,
            failure: failure,
            now: now,
            document: &next
        )

        try NovelDocumentValidator.validateTransition(from: document, to: next)
        return (
            next,
            NovelSessionMessageSnapshot(
                projectID: next.project.id,
                branchID: run.branchID,
                message: message
            )
        )
    }
}

extension NovelGenerationReducer {
    static func validateAskUserPrompt(_ prompt: NovelAskUserPrompt) throws {
        guard !prompt.question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NovelError.invalidInput("Ask User requires question text.")
        }
        let options = NovelCharacterIdentityResolver.normalizedAliases(prompt.options)
        guard options.count == prompt.options.count else {
            throw NovelError.invalidInput("Ask User options must be unique and non-empty.")
        }
        guard options.isEmpty || (2...6).contains(options.count) else {
            throw NovelError.invalidInput("Ask User requires no options or two to six choices.")
        }
    }
}

private extension NovelGenerationReducer {
    struct InterruptedResult {
        let document: NovelProjectDocumentV1
        let message: NovelSessionMessageSnapshot?
        let didTransition: Bool
    }

    static func interruptedDocument(
        runID: NovelRunID,
        reason: NovelRunInterruptionReason,
        partialContent: String,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> InterruptedResult {
        guard let runIndex = document.activeRuns.firstIndex(where: { $0.id == runID }),
              document.activeRuns[runIndex].status == .running else {
            return InterruptedResult(document: document, message: nil, didTransition: false)
        }
        let run = document.activeRuns[runIndex]
        let branchIndex = try branchIndex(for: run.branchID, in: document)
        guard document.branches[branchIndex].activeRunID == run.id else {
            return InterruptedResult(document: document, message: nil, didTransition: false)
        }
        guard let sessionIndex = document.sessions.firstIndex(where: {
            $0.id == run.sessionID && $0.branchID == run.branchID
        }) else {
            throw NovelError.sessionNotFound(run.sessionID)
        }

        var next = document
        var snapshot: NovelSessionMessageSnapshot?
        if !partialContent.isEmpty {
            let interruptedCandidateID = (run.kind == .prose || run.kind == .regenerate)
                ? run.candidateID
                : nil
            let message = NovelSessionMessageRecord(
                id: run.messageID,
                sequence: Int64(document.sessions[sessionIndex].messages.count),
                role: .assistant,
                mode: run.mode,
                kind: .interruptedDraft,
                content: partialContent,
                createdAt: now,
                runID: run.id,
                candidateID: interruptedCandidateID
            )
            next.sessions[sessionIndex].messages.append(message)
            next.sessions[sessionIndex].revision += 1
            if let candidateID = interruptedCandidateID {
                next.candidates.append(NovelCandidateRecord(
                    id: candidateID,
                    kind: .prose,
                    branchID: run.branchID,
                    sessionID: run.sessionID,
                    sourceMessageID: run.messageID,
                    baseCheckpointID: run.baseCheckpointID,
                    baseHeadRevision: run.baseHeadRevision,
                    status: .interrupted,
                    content: partialContent,
                    // 保留来源章版本:prose run 恒为 nil(行为不变),而 regenerate
                    // 若在此清空,中断后收录面板就找不到替换目标,会静默翻成
                    // 「新开一章」,在书尾造出与原章重复的正文。
                    sourceChapterVersionID: run.sourceChapterVersionID,
                    collectedCheckpointID: nil,
                    chapterPlanDigest: run.chapterPlanDigest,
                    ghostwritePlanID: run.ghostwritePlanID,
                    createdAt: now
                ))
            }
            snapshot = NovelSessionMessageSnapshot(
                projectID: next.project.id,
                branchID: run.branchID,
                message: message
            )
        }
        finish(
            runIndex: runIndex,
            branchIndex: branchIndex,
            status: .interrupted,
            content: partialContent,
            interruptionReason: reason,
            failure: nil,
            now: now,
            document: &next
        )
        return InterruptedResult(document: next, message: snapshot, didTransition: true)
    }

    static func finish(
        runIndex: Int,
        branchIndex: Int,
        status: NovelRunStatus,
        content: String,
        interruptionReason: NovelRunInterruptionReason?,
        failure: NovelFailure?,
        now: Date,
        document: inout NovelProjectDocumentV1
    ) {
        document.activeRuns[runIndex].status = status
        document.activeRuns[runIndex].partialContent = content
        document.activeRuns[runIndex].terminalAt = now
        document.activeRuns[runIndex].interruptionReason = interruptionReason
        document.activeRuns[runIndex].terminalFailure = failure
        document.branches[branchIndex].activeRunID = nil
        document.branches[branchIndex].updatedAt = now
        document.project.revision += 1
        document.project.updatedAt = now
    }

    static func branchIndex(
        for branchID: NovelBranchID,
        in document: NovelProjectDocumentV1
    ) throws -> Int {
        guard let index = document.branches.firstIndex(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        return index
    }

    static func validateShape(
        _ request: NovelRunRequest,
        branch: NovelBranchRecord,
        document: NovelProjectDocumentV1
    ) throws {
        guard !request.userText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NovelError.invalidInput("A generation request cannot be empty.")
        }
        guard request.inputBudgetTokens > 0 else {
            throw NovelError.invalidInput("The generation input budget must be positive.")
        }

        switch request.kind {
        case .quickStart:
            guard document.project.creationMode == .quickStart,
                  request.mode == .discussPlan,
                  request.granularity == nil,
                  request.candidateID == nil,
                  request.sourceChapterVersionID == nil else {
                throw NovelError.invalidInput("The quick-start run shape is invalid.")
            }
        case .characterProposal:
            guard request.mode == .discussPlan,
                  request.granularity == nil,
                  request.candidateID == nil,
                  request.sourceChapterVersionID == nil,
                  request.askUserResponse == nil,
                  let mention = request.contextualCharacterMention?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                  !mention.isEmpty,
                  mention.count <= 200,
                  let state = document.stateSnapshots.first(where: {
                      $0.id == branch.currentStateSnapshotID
                  }),
                  state.unresolvedEntityNames.contains(where: {
                      NovelCharacterIdentityResolver.normalize($0) ==
                        NovelCharacterIdentityResolver.normalize(mention)
                  }),
                  !document.activeSettingProposals(for: branch.id).contains(where: { proposal in
                      guard case .some(.contextualCharacter(_, let sourceMention, .character)) =
                        proposal.origin else { return false }
                      return NovelCharacterIdentityResolver.normalize(sourceMention) ==
                        NovelCharacterIdentityResolver.normalize(mention)
                  }) else {
                throw NovelError.invalidInput("The character-proposal run shape is invalid.")
            }
        case .discussion:
            guard request.mode == .discussPlan,
                  request.granularity == nil,
                  request.candidateID == nil,
                  request.sourceChapterVersionID == nil else {
                throw NovelError.invalidInput("The discussion run shape is invalid.")
            }
        case .prose:
            guard branch.syncStatus == .synchronized else {
                throw NovelError.invalidInput(
                    "The branch must be synchronized before writing prose."
                )
            }
            guard !document.pendingOperations.contains(where: {
                $0.branchID == branch.id && $0.blocksProseGeneration
            }) else {
                throw NovelError.invalidInput(
                    "Pending manuscript synchronization must finish before writing prose."
                )
            }
            guard request.mode == .writeProse,
                  request.granularity != nil,
                  request.candidateID != nil,
                  request.sourceChapterVersionID == nil,
                  request.askUserResponse == nil else {
                throw NovelError.invalidInput("The prose run shape is invalid.")
            }
            if document.project.collaborationMode == .ghostwrite,
               request.granularity == .wholeChapter,
               document.confirmedChapterPlan(for: branch.id) == nil {
                throw NovelError.invalidInput(
                    "代笔模式下写整章前，需要先确认本章计划。"
                )
            }
        case .regenerate:
            guard branch.syncStatus == .synchronized else {
                throw NovelError.invalidInput("The branch must be synchronized before rewriting a chapter.")
            }
            guard !document.pendingOperations.contains(where: { $0.branchID == branch.id }) else {
                throw NovelError.invalidInput("Pending manuscript work must finish before rewriting a chapter.")
            }
            guard request.mode == .writeProse,
                  request.granularity == .wholeChapter,
                  request.candidateID != nil,
                  let sourceID = request.sourceChapterVersionID,
                  branch.workingChapterSelections.contains(where: { $0.versionID == sourceID }),
                  let sourceVersion = document.chapterVersions.first(where: { $0.id == sourceID }),
                  document.chapters.contains(where: {
                      $0.id == sourceVersion.chapterID && $0.discardedAt == nil
                  }),
                  request.askUserResponse == nil else {
                throw NovelError.invalidInput(
                    "The regeneration run shape or source version is invalid."
                )
            }
        case .polish:
            guard branch.syncStatus == .synchronized else {
                throw NovelError.invalidInput("The branch must be synchronized before polishing prose.")
            }
            guard !document.pendingOperations.contains(where: { $0.branchID == branch.id }) else {
                throw NovelError.invalidInput(
                    "Pending manuscript synchronization must finish before polishing prose."
                )
            }
            guard request.mode == .writeProse,
                  request.granularity == nil,
                  request.candidateID != nil,
                  request.askUserResponse == nil,
                  let sourceID = request.sourceChapterVersionID,
                  branch.workingChapterSelections.contains(where: { $0.versionID == sourceID }),
                  let sourceVersion = document.chapterVersions.first(where: { $0.id == sourceID }),
                  document.chapters.contains(where: {
                      $0.id == sourceVersion.chapterID && $0.discardedAt == nil
                  }) else {
                throw NovelError.invalidInput("The polish run shape or source version is invalid.")
            }
        }
    }

    static func validateAskUserResponse(
        _ response: NovelAskUserResponse,
        requestKind: NovelRunKind,
        in session: NovelSessionRecord,
        document: NovelProjectDocumentV1
    ) throws {
        guard let promptMessage = session.messages.first(where: {
            $0.id == response.promptMessageID && $0.role == .assistant
        }), case .some(.askUser(let prompt)) = promptMessage.interaction else {
            throw NovelError.invalidInput("The Ask User prompt no longer belongs to this session.")
        }
        guard let promptRunID = promptMessage.runID,
              document.activeRuns.first(where: { $0.id == promptRunID })?.kind == requestKind else {
            throw NovelError.invalidInput("The Ask User answer must continue the same run kind.")
        }
        guard !session.messages.contains(where: { message in
            guard case .some(.askUserAnswer(let existing)) = message.interaction else {
                return false
            }
            return existing.promptMessageID == response.promptMessageID
        }) else {
            throw NovelError.invalidInput("This Ask User prompt has already been answered.")
        }
        try validateAskUserPrompt(prompt)
        guard !response.answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NovelError.invalidInput("Ask User requires a non-empty answer.")
        }
    }

    static func validateUniqueIDs(
        _ request: NovelRunRequest,
        document: NovelProjectDocumentV1
    ) throws {
        guard request.userMessageID != request.assistantMessageID else {
            throw NovelError.invalidInput("Generation input and output messages need distinct IDs.")
        }
        guard !document.activeRuns.contains(where: { $0.id == request.id }) else {
            throw NovelError.immutableRecordConflict("generation run \(request.id)")
        }

        let messageIDs = Set(document.sessions.flatMap { $0.messages.map(\.id) })
            .union(document.activeRuns.map(\.userMessageID))
            .union(document.activeRuns.map(\.messageID))
        guard !messageIDs.contains(request.userMessageID),
              !messageIDs.contains(request.assistantMessageID) else {
            throw NovelError.immutableRecordConflict("generation message ID")
        }
        if let candidateID = request.candidateID {
            let reservedCandidateIDs = Set(document.candidates.map(\.id))
                .union(document.activeRuns.compactMap(\.candidateID))
            guard !reservedCandidateIDs.contains(candidateID) else {
                throw NovelError.immutableRecordConflict("candidate \(candidateID)")
            }
        }
    }

    static func validateArtifacts(
        _ artifacts: NovelGenerationStartArtifacts,
        request: NovelRunRequest,
        document: NovelProjectDocumentV1
    ) throws {
        let injection = artifacts.injectionReceipt
        let generation = artifacts.generationReceipt
        let expectedPromptVersion = NovelPromptCatalog.template(
            for: promptKind(for: request)
        ).version

        guard injection.id == request.injectionReceiptID,
              injection.runID == request.id,
              injection.projectID == request.projectID,
              injection.branchID == request.branchID,
              generation.id == request.generationReceiptID,
              generation.runID == request.id,
              generation.injectionReceiptID == injection.id,
              generation.providerID == injection.providerID,
              generation.ownerProviderID == injection.ownerProviderID,
              generation.modelID == injection.modelID,
              generation.wireModelID == injection.wireModelID,
              generation.promptVersion == injection.promptVersion,
              generation.parameters == injection.parameters,
              injection.promptVersion == expectedPromptVersion else {
            throw NovelError.invalidInput("Generation receipts do not match the run request.")
        }
        guard request.generationReceiptID != request.injectionReceiptID else {
            throw NovelError.invalidInput("Generation and injection receipts need distinct IDs.")
        }
        guard injection.requestedInputBudgetTokens == request.inputBudgetTokens,
              injection.maxEstimatedInputTokens > 0,
              injection.maxEstimatedInputTokens <= injection.requestedInputBudgetTokens,
              injection.estimatedInputTokens >= 0,
              injection.estimatedInputTokens <= injection.maxEstimatedInputTokens else {
            throw NovelError.invalidInput("The persisted injection budget does not match the request.")
        }
        guard NovelDocumentValidator.isSHA256(injection.canonicalInputSHA256),
              NovelDocumentValidator.isSHA256(generation.requestSHA256) else {
            throw NovelError.invalidInput("Generation receipts require canonical SHA-256 evidence.")
        }
        let fixedPromptHash = NovelDocumentValidator.sha256(
            NovelPromptCatalog.template(for: promptKind(for: request)).systemText
        )
        let userInputHash = NovelDocumentValidator.sha256(request.userText)
        guard injection.sections.filter({
            if case .fixedPrompt = $0.kind { return true }
            return false
        }).map(\.contentSHA256) == [fixedPromptHash],
        injection.sections.filter({
            if case .userInput = $0.kind { return true }
            return false
        }).map(\.contentSHA256) == [userInputHash] else {
            throw NovelError.invalidInput("Generation receipt Prompt or user input evidence is invalid.")
        }

        let included = Set(request.injectionOverrides.forceIncludeMaterialIDs)
        let excluded = Set(request.injectionOverrides.forceExcludeMaterialIDs)
        guard included.isDisjoint(with: excluded),
              included == Set(injection.forceIncludeMaterialIDs),
              excluded == Set(injection.forceExcludeMaterialIDs) else {
            throw NovelError.invalidInput("The persisted injection overrides do not match the request.")
        }
        let existingReceiptIDs = Set(document.injectionReceipts.map(\.id))
            .union(document.generationReceipts.map(\.id))
        guard !existingReceiptIDs.contains(injection.id),
              !existingReceiptIDs.contains(generation.id) else {
            throw NovelError.immutableRecordConflict("generation receipt ID")
        }
    }

    static func promptKind(for request: NovelRunRequest) -> NovelPromptKind {
        switch request.kind {
        case .quickStart:
            .quickStart
        case .characterProposal:
            .characterProposal
        case .discussion:
            .discussion
        case .prose:
            request.granularity == .continuation
                ? .proseContinuation
                : .proseWholeChapter
        case .polish:
            .wholeChapterPolish
        case .regenerate:
            .wholeChapterRegeneration
        }
    }

}
