import Foundation

enum NovelReducer {
    static func createProject(
        _ command: NovelCreateProjectCommand,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        let payloadSHA256 = try command.canonicalPayloadSHA256()
        guard command.context.expectedProjectRevision == nil,
              command.context.expectedConfigRevision == nil,
              command.context.expectedBranchHeadRevision == nil else {
            throw NovelError.invalidInput("Project creation must expect the project to be absent.")
        }
        let name = try normalizedRequired(command.name, field: "Project name")
        let branchName = try normalizedRequired(command.branchName, field: "Branch name")

        switch command.creationMode {
        case .blank:
            guard command.quickStartSeed == nil else {
                throw NovelError.invalidInput("Blank projects cannot contain a quick-start seed.")
            }
        case .quickStart:
            guard let seed = command.quickStartSeed,
                  !seed.genre.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !seed.coreIdea.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw NovelError.invalidInput("Quick-start projects require a genre and core idea.")
            }
        }

        let state = NovelStateSnapshotRecord(
            id: command.initialStateSnapshotID,
            eventIDs: [],
            summary: "",
            branchOutline: "",
            unresolvedEntityNames: [],
            createdAt: now
        )
        let session = NovelSessionRecord(
            id: command.sessionID,
            branchID: command.branchID,
            revision: 0,
            messages: []
        )
        let branch = NovelBranchRecord(
            id: command.branchID,
            name: branchName,
            sessionID: command.sessionID,
            createdAt: now,
            updatedAt: now,
            forkOrigin: nil,
            headCheckpointID: command.initialCheckpointID,
            currentStateSnapshotID: state.id,
            headRevision: 0,
            workingRevision: 0,
            syncStatus: .synchronized,
            lifecycle: .active,
            overrideRevisionIDs: [],
            workingChapterSelections: [],
            activeRunID: nil
        )
        let project = NovelProjectRecord(
            id: command.projectID,
            name: name,
            creationMode: command.creationMode,
            quickStartSeed: command.quickStartSeed,
            createdAt: now,
            updatedAt: now,
            revision: 1,
            configRevision: 1,
            mainBranchID: command.branchID,
            modelPolicy: .global,
            stateSyncModelPolicy: nil,
            lastGenerationGranularity: .wholeChapter,
            polishPreference: ""
        )
        let outcome = NovelOutcome.projectCreated(
            projectID: command.projectID,
            branchID: command.branchID
        )
        let applied = NovelAppliedOperationRecord(
            operationID: command.context.operationID,
            kind: .createProject,
            payloadSHA256: payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: project.revision,
            appliedAt: now
        )
        let initialCheckpoint = NovelBranchCheckpointRecord(
            id: command.initialCheckpointID,
            kind: .initial,
            createdOnBranchID: command.branchID,
            parentCheckpointID: nil,
            chapterSelections: [],
            stateSnapshotID: state.id,
            sessionCursor: .empty,
            branchOverrideRevisionIDs: [],
            sourceCandidateID: nil,
            baseHeadRevision: 0,
            operationID: command.context.operationID,
            createdAt: now
        )
        let document = NovelProjectDocumentV1(
            schemaVersion: NovelProjectDocumentV1.currentSchemaVersion,
            project: project,
            materials: [],
            materialRevisions: [],
            branches: [branch],
            sessions: [session],
            chapters: [],
            chapterVersions: [],
            events: [],
            stateSnapshots: [state],
            checkpoints: [initialCheckpoint],
            candidates: [],
            injectionReceipts: [],
            generationReceipts: [],
            factAttempts: [],
            polishTransactions: [],
            polishAttempts: [],
            polishAssessments: [],
            pendingOperations: [],
            activeRuns: [],
            settingProposals: [],
            appliedOperations: [applied]
        )
        try NovelDocumentValidator.validate(document)
        return (document, outcome)
    }

    static func apply(
        _ action: NovelAction,
        to document: NovelProjectDocumentV1,
        now: Date = Date()
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        guard action.projectID == document.project.id else {
            throw NovelError.projectNotFound(action.projectID)
        }
        let payloadSHA256 = try action.canonicalPayloadSHA256()
        if let outcome = try replayOutcome(
            context: action.context,
            kind: action.operationKind,
            payloadSHA256: payloadSHA256,
            in: document
        ) {
            return (document, outcome)
        }

        switch action {
        case .createProject:
            throw NovelError.projectAlreadyExists(document.project.id)
        case .renameProject(let command):
            return try renameProject(command, in: document, now: now)
        case .reviseMaterial(let command):
            return try reviseMaterial(command, in: document, now: now)
        case .deleteMaterial(let command):
            return try NovelProjectConfigurationReducer.deleteMaterial(
                command,
                in: document,
                now: now
            )
        case .setModelPolicy(let command):
            return try NovelProjectConfigurationReducer.setModelPolicy(
                command,
                in: document,
                now: now
            )
        case .resolveSettingProposal(let command):
            return try NovelProjectConfigurationReducer.resolveSettingProposal(
                command,
                in: document,
                now: now
            )
        case .setBranchMaterialOverride(let command):
            return try NovelProjectConfigurationReducer.setBranchMaterialOverride(
                command,
                in: document,
                now: now
            )
        case .setMainBranch(let command):
            return try setMainBranch(command, in: document, now: now)
        case .setPolishPreference(let command):
            return try setPolishPreference(command, in: document, now: now)
        case .forkBranch(let command):
            return try forkBranch(command, in: document, now: now)
        case .renameBranch(let command):
            return try renameBranch(command, in: document, now: now)
        case .deleteBranch(let command):
            return try deleteBranch(command, in: document, now: now)
        case .undoBranchHead(let command):
            return try undoBranchHead(command, in: document, now: now)
        case .archiveDiscussion(let command):
            return try archiveDiscussion(command, in: document, now: now)
        case .clarifyCharacterIdentity(let command):
            return try clarifyCharacterIdentity(command, in: document, now: now)
        case .cloneCandidate(let command):
            return try cloneCandidate(command, in: document, now: now)
        case .restoreChapterVersion(let command):
            return try NovelPolishTransactionReducer.restoreChapterVersion(
                command,
                in: document,
                now: now
            )
        case .abandonPolishTransaction(let command):
            return try NovelPolishTransactionReducer.abandonTransaction(
                command,
                in: document,
                now: now
            )
        case .discardChapter(let command):
            return try NovelChapterDiscardReducer.setDiscarded(
                true,
                chapterID: command.chapterID,
                projectID: command.projectID,
                branchID: command.branchID,
                context: command.context,
                payloadSHA256: payloadSHA256,
                in: document,
                now: now
            )
        case .restoreChapter(let command):
            return try NovelChapterDiscardReducer.setDiscarded(
                false,
                chapterID: command.chapterID,
                projectID: command.projectID,
                branchID: command.branchID,
                context: command.context,
                payloadSHA256: payloadSHA256,
                in: document,
                now: now
            )
        case .adoptPolishCandidate:
            throw NovelError.invalidInput(
                "Polish adoption must be applied by the two-phase transaction lifecycle."
            )
        case .cancelRun:
            throw NovelError.invalidInput("Run cancellation must be applied by the generation lifecycle.")
        case .saveManualEdit(let command):
            return try NovelFactTransactionReducer.saveManualEdit(
                command,
                payloadSHA256: payloadSHA256,
                in: document,
                now: now
            )
        case .collectCandidate, .syncManualEdits, .retryPending:
            throw NovelError.invalidInput(
                "Fact synchronization must be applied by the two-phase transaction lifecycle."
            )
        case .importProject, .restorePreviousProject, .deleteProject:
            throw NovelError.invalidInput(
                "Project lifecycle actions must be applied by the project repository lifecycle."
            )
        }
    }

    static func replayOutcome(
        context: NovelMutationContext,
        kind: NovelOperationKind,
        payloadSHA256: String,
        in document: NovelProjectDocumentV1
    ) throws -> NovelOutcome? {
        guard let applied = document.appliedOperations.first(where: {
            $0.operationID == context.operationID
        }) else {
            if let polish = document.polishTransactions.first(where: {
                $0.operationID == context.operationID
            }) {
                guard kind == .adoptPolishCandidate,
                      polish.payloadSHA256 == payloadSHA256 else {
                    throw NovelError.idempotencyConflict(context.operationID)
                }
            }
            if let attempt = document.factAttempts.first(where: {
                $0.attemptOperationID == context.operationID
            }) {
                guard kind == .retryPending,
                      attempt.attemptPayloadSHA256 == payloadSHA256 else {
                    throw NovelError.idempotencyConflict(context.operationID)
                }
            }
            return nil
        }
        guard applied.payloadSHA256 == payloadSHA256,
              applied.kind == kind else {
            throw NovelError.idempotencyConflict(context.operationID)
        }
        return applied.outcome
    }

    static func appendCheckpoint(
        _ checkpoint: NovelBranchCheckpointRecord,
        to document: inout NovelProjectDocumentV1,
        expectedHeadRevision: Int64,
        advancesWorkingRevision: Bool = true,
        now: Date = Date()
    ) throws {
        guard document.checkpoints.allSatisfy({ $0.id != checkpoint.id }) else {
            throw NovelError.immutableRecordConflict("checkpoint \(checkpoint.id)")
        }
        guard let branchIndex = document.branches.firstIndex(where: {
            $0.id == checkpoint.createdOnBranchID
        }) else {
            throw NovelError.branchNotFound(checkpoint.createdOnBranchID)
        }
        let branch = document.branches[branchIndex]
        guard branch.headRevision == expectedHeadRevision else {
            throw NovelError.staleBranchHeadRevision(
                expected: expectedHeadRevision,
                actual: branch.headRevision
            )
        }
        guard checkpoint.parentCheckpointID == branch.headCheckpointID else {
            throw NovelError.invalidInput("Checkpoint parent must match the current branch head.")
        }
        guard checkpoint.baseHeadRevision == expectedHeadRevision else {
            throw NovelError.invalidInput("Checkpoint base head revision does not match its guard.")
        }

        try validateCheckpointReferences(checkpoint, in: document, sessionID: branch.sessionID)
        document.checkpoints.append(checkpoint)
        document.branches[branchIndex].headCheckpointID = checkpoint.id
        document.branches[branchIndex].currentStateSnapshotID = checkpoint.stateSnapshotID
        document.branches[branchIndex].headRevision += 1
        if advancesWorkingRevision {
            document.branches[branchIndex].workingRevision += 1
        }
        document.branches[branchIndex].syncStatus = .synchronized
        document.branches[branchIndex].workingChapterSelections = checkpoint.chapterSelections
        document.branches[branchIndex].updatedAt = now
    }

    private static func renameProject(
        _ command: NovelRenameProjectCommand,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> (NovelProjectDocumentV1, NovelOutcome) {
        try requireProjectRevision(command.context, document: document)
        let name = try normalizedRequired(command.name, field: "Project name")

        var next = document
        next.project.name = name
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.projectRenamed(
            projectID: command.projectID,
            revision: next.project.revision
        )
        recordApplied(
            command.context,
            kind: .renameProject,
            payloadSHA256: try NovelAction.renameProject(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validate(next)
        return (next, outcome)
    }

    private static func reviseMaterial(
        _ command: NovelReviseMaterialCommand,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> (NovelProjectDocumentV1, NovelOutcome) {
        try requireConfigRevision(command.context, document: document)
        let title = try normalizedRequired(command.title, field: "Material title")
        let content = command.content.trimmingCharacters(in: .whitespacesAndNewlines)
        let aliases = command.kind == .character
            ? NovelCharacterIdentityResolver.normalizedAliases(command.aliases)
            : []
        guard document.materialRevisions.allSatisfy({ $0.id != command.revisionID }) else {
            throw NovelError.immutableRecordConflict("material revision \(command.revisionID)")
        }

        var next = document
        let materialIndex = next.materials.firstIndex(where: { $0.id == command.materialID })
        let revisionNumber: Int64
        if let materialIndex {
            let material = next.materials[materialIndex]
            guard !material.isDeleted else {
                throw NovelError.invalidInput("Deleted project material cannot be revised.")
            }
            guard material.kind == command.kind else {
                throw NovelError.immutableRecordConflict("material kind \(command.materialID)")
            }
            revisionNumber = Int64(material.revisionIDs.count + 1)
        } else {
            revisionNumber = 1
        }

        let revision = NovelMaterialRevisionRecord(
            id: command.revisionID,
            materialID: command.materialID,
            revision: revisionNumber,
            title: title,
            content: content,
            tags: normalizedTags(command.tags),
            injectionMode: command.injectionMode,
            createdAt: now,
            operationID: command.context.operationID
        )
        next.materialRevisions.append(revision)
        if let materialIndex {
            next.materials[materialIndex].currentRevisionID = revision.id
            next.materials[materialIndex].revisionIDs.append(revision.id)
            next.materials[materialIndex].aliases = aliases
        } else {
            next.materials.append(NovelMaterialRecord(
                id: command.materialID,
                kind: command.kind,
                currentRevisionID: revision.id,
                revisionIDs: [revision.id],
                aliases: aliases
            ))
        }
        next.project.revision += 1
        next.project.configRevision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.materialRevised(
            projectID: command.projectID,
            materialID: command.materialID,
            revisionID: revision.id,
            projectRevision: next.project.revision,
            configRevision: next.project.configRevision
        )
        recordApplied(
            command.context,
            kind: .reviseMaterial,
            payloadSHA256: try NovelAction.reviseMaterial(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validate(next)
        return (next, outcome)
    }

    private static func setMainBranch(
        _ command: NovelSetMainBranchCommand,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> (NovelProjectDocumentV1, NovelOutcome) {
        try requireProjectRevision(command.context, document: document)
        guard document.branches.contains(where: {
            $0.id == command.branchID && $0.lifecycle == .active
        }) else {
            throw NovelError.branchNotFound(command.branchID)
        }

        var next = document
        next.project.mainBranchID = command.branchID
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.mainBranchChanged(
            projectID: command.projectID,
            branchID: command.branchID,
            revision: next.project.revision
        )
        recordApplied(
            command.context,
            kind: .setMainBranch,
            payloadSHA256: try NovelAction.setMainBranch(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validate(next)
        return (next, outcome)
    }

    private static func setPolishPreference(
        _ command: NovelSetPolishPreferenceCommand,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> (NovelProjectDocumentV1, NovelOutcome) {
        try requireConfigRevision(command.context, document: document)
        let preference = command.preference.trimmingCharacters(in: .whitespacesAndNewlines)
        guard preference.count <= 8_000 else {
            throw NovelError.invalidInput("The project polish preference is too long.")
        }

        var next = document
        next.project.polishPreference = preference
        next.project.revision += 1
        next.project.configRevision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.polishPreferenceChanged(
            projectID: command.projectID,
            projectRevision: next.project.revision,
            configRevision: next.project.configRevision
        )
        recordApplied(
            command.context,
            kind: .setPolishPreference,
            payloadSHA256: try NovelAction.setPolishPreference(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validate(next)
        return (next, outcome)
    }

    static func requireProjectRevision(
        _ context: NovelMutationContext,
        document: NovelProjectDocumentV1
    ) throws {
        guard let expected = context.expectedProjectRevision else {
            throw NovelError.invalidInput("Expected project revision is missing.")
        }
        guard expected == document.project.revision else {
            throw NovelError.staleProjectRevision(
                expected: expected,
                actual: document.project.revision
            )
        }
    }

    static func requireConfigRevision(
        _ context: NovelMutationContext,
        document: NovelProjectDocumentV1
    ) throws {
        guard let expected = context.expectedConfigRevision else {
            throw NovelError.invalidInput("Expected config revision is missing.")
        }
        guard expected == document.project.configRevision else {
            throw NovelError.staleConfigRevision(
                expected: expected,
                actual: document.project.configRevision
            )
        }
    }

    private static func validateCheckpointReferences(
        _ checkpoint: NovelBranchCheckpointRecord,
        in document: NovelProjectDocumentV1,
        sessionID: NovelSessionID
    ) throws {
        guard document.stateSnapshots.contains(where: { $0.id == checkpoint.stateSnapshotID }) else {
            throw NovelError.stateSnapshotNotFound(checkpoint.stateSnapshotID)
        }
        if let parent = checkpoint.parentCheckpointID,
           !document.checkpoints.contains(where: { $0.id == parent }) {
            throw NovelError.checkpointNotFound(parent)
        }
        guard let session = document.sessions.first(where: { $0.id == sessionID }) else {
            throw NovelError.sessionNotFound(sessionID)
        }
        if case .through(let cursor) = checkpoint.sessionCursor,
           !session.messages.contains(where: { $0.sequence == cursor }) {
            throw NovelError.invalidInput("Checkpoint Session cursor is outside the Session history.")
        }
        for selection in checkpoint.chapterSelections {
            guard document.chapters.contains(where: { $0.id == selection.chapterID }),
                  document.chapterVersions.contains(where: {
                      $0.id == selection.versionID && $0.chapterID == selection.chapterID
                  }) else {
                throw NovelError.invalidInput("Checkpoint references an invalid chapter version.")
            }
        }
        for revisionID in checkpoint.branchOverrideRevisionIDs where
            !document.materialRevisions.contains(where: { $0.id == revisionID }) {
            throw NovelError.invalidInput("Checkpoint references an invalid branch override revision.")
        }
    }

    static func recordApplied(
        _ context: NovelMutationContext,
        kind: NovelOperationKind,
        payloadSHA256: String,
        outcome: NovelOutcome,
        in document: inout NovelProjectDocumentV1,
        now: Date
    ) {
        document.appliedOperations.append(NovelAppliedOperationRecord(
            operationID: context.operationID,
            kind: kind,
            payloadSHA256: payloadSHA256,
            outcome: outcome,
            appliedProjectRevision: document.project.revision,
            appliedAt: now
        ))
    }

    static func normalizedRequired(_ value: String, field: String) throws -> String {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            throw NovelError.invalidInput("\(field) cannot be empty.")
        }
        return normalized
    }

    static func normalizedTags(_ tags: [String]) -> [String] {
        var seen = Set<String>()
        return tags.compactMap { tag in
            let normalized = tag.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !normalized.isEmpty else { return nil }
            let key = normalized.lowercased()
            guard seen.insert(key).inserted else { return nil }
            return normalized
        }
    }
}

/// 章节废弃 / 恢复。只翻 `NovelChapterRecord.discardedAt` 这一个字段:
/// 章节记录、章节版本、事实事务、剧情状态快照全部原样保留,所以
/// `NovelCompatibilityLineageValidator` 校验的事实兼容链不受影响,恢复无损。
/// 「不参与上下文/剧情状态」由读取侧过滤实现,而不是在这里删数据。
enum NovelChapterDiscardReducer {
    static func setDiscarded(
        _ isDiscarded: Bool,
        chapterID: NovelChapterID,
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        context: NovelMutationContext,
        payloadSHA256: String,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> (NovelProjectDocumentV1, NovelOutcome) {
        try NovelReducer.requireProjectRevision(context, document: document)
        var next = document
        guard let index = next.chapters.firstIndex(where: { $0.id == chapterID }) else {
            throw NovelError.invalidInput("Chapter \(chapterID) does not exist.")
        }
        guard (next.chapters[index].discardedAt != nil) != isDiscarded else {
            throw NovelError.invalidInput(
                isDiscarded
                    ? "Chapter \(chapterID) is already discarded."
                    : "Chapter \(chapterID) is not discarded."
            )
        }
        next.chapters[index].discardedAt = isDiscarded ? now : nil
        next.project.revision += 1
        next.project.updatedAt = now
        let outcome = NovelOutcome.chapterDiscardStateChanged(
            projectID: projectID,
            branchID: branchID,
            chapterID: chapterID,
            isDiscarded: isDiscarded,
            revision: next.project.revision
        )
        NovelReducer.recordApplied(
            context,
            kind: isDiscarded ? .discardChapter : .restoreChapter,
            payloadSHA256: payloadSHA256,
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validate(next)
        return (next, outcome)
    }
}
