import Foundation

enum NovelProjectConfigurationReducer {
    static func deleteMaterial(
        _ command: NovelDeleteMaterialCommand,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        try NovelReducer.requireConfigRevision(command.context, document: document)
        guard let materialIndex = document.materials.firstIndex(where: {
            $0.id == command.materialID && !$0.isDeleted
        }) else {
            throw NovelError.invalidInput("Project material was not found or is already deleted.")
        }

        var next = document
        next.materials[materialIndex].isDeleted = true
        advanceConfigurationRevision(in: &next, now: now)
        let outcome = NovelOutcome.materialDeleted(
            projectID: command.projectID,
            materialID: command.materialID,
            projectRevision: next.project.revision,
            configRevision: next.project.configRevision
        )
        NovelReducer.recordApplied(
            command.context,
            kind: .deleteMaterial,
            payloadSHA256: try NovelAction.deleteMaterial(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validate(next)
        return (next, outcome)
    }

    static func setModelPolicy(
        _ command: NovelSetModelPolicyCommand,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        try NovelReducer.requireConfigRevision(command.context, document: document)
        guard !document.pendingOperations.contains(where: { $0.manualSyncProgress != nil }) else {
            throw NovelError.invalidInput(
                "Finish the in-progress manuscript synchronization before changing the project model."
            )
        }
        let policy = try normalizedModelPolicy(command.policy)

        var next = document
        switch command.purpose {
        case .creation:
            next.project.modelPolicy = policy
        case .stateSync:
            next.project.stateSyncModelPolicy = policy
        case .review:
            next.project.reviewModelPolicy = policy
        }
        advanceConfigurationRevision(in: &next, now: now)
        let outcome = NovelOutcome.modelPolicyChanged(
            projectID: command.projectID,
            projectRevision: next.project.revision,
            configRevision: next.project.configRevision
        )
        NovelReducer.recordApplied(
            command.context,
            kind: .setModelPolicy,
            payloadSHA256: try NovelAction.setModelPolicy(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validate(next)
        return (next, outcome)
    }

    static func resolveSettingProposal(
        _ command: NovelResolveSettingProposalCommand,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        try NovelReducer.requireConfigRevision(command.context, document: document)
        try NovelReducer.requireProjectRevision(command.context, document: document)
        guard let proposalIndex = document.settingProposals.firstIndex(where: {
            $0.id == command.proposalID &&
                !$0.isResolved &&
                $0.supersededByRunID == nil
        }) else {
            throw NovelError.invalidInput("The project-setting proposal is missing or no longer active.")
        }
        let proposal = document.settingProposals[proposalIndex]
        guard let branch = document.branches.first(where: {
            $0.id == proposal.branchID && $0.lifecycle == .active
        }) else {
            throw NovelError.branchNotFound(proposal.branchID)
        }
        guard let expectedHead = command.context.expectedBranchHeadRevision else {
            throw NovelError.invalidInput("Expected branch head revision is missing.")
        }
        guard branch.headRevision == expectedHead else {
            throw NovelError.staleBranchHeadRevision(
                expected: expectedHead,
                actual: branch.headRevision
            )
        }
        guard document.activeSettingProposals(for: proposal.branchID).contains(where: {
            $0.id == proposal.id
        }) else {
            throw NovelError.invalidInput("The project-setting proposal is no longer active on its branch.")
        }

        var next = document
        next.settingProposals[proposalIndex].isResolved = true
        let outcome: NovelOutcome
        switch command.resolution {
        case .accept(
            let materialID,
            let revisionID,
            let kind,
            let title,
            let content,
            let tags,
            let injectionMode,
            let aliases
        ):
            let contextualSourceMention: String? = {
                guard case .some(.contextualCharacter(_, let sourceMention, .character)) =
                    proposal.origin else { return nil }
                return sourceMention
            }()
            if contextualSourceMention != nil, kind != .character {
                throw NovelError.invalidInput(
                    "A contextual character proposal must be accepted as a character material."
                )
            }
            let acceptedAliases = kind == .character
                ? NovelCharacterIdentityResolver.normalizedAliases(
                    aliases + [contextualSourceMention].compactMap { $0 }
                )
                : []
            guard next.materialRevisions.allSatisfy({ $0.id != revisionID }) else {
                throw NovelError.immutableRecordConflict("material revision \(revisionID)")
            }
            let materialIndex = next.materials.firstIndex(where: { $0.id == materialID })
            let revisionNumber: Int64
            if let materialIndex {
                let material = next.materials[materialIndex]
                guard !material.isDeleted, material.kind == kind else {
                    throw NovelError.invalidInput("The proposal target material is unavailable or has another kind.")
                }
                revisionNumber = Int64(material.revisionIDs.count + 1)
            } else {
                revisionNumber = 1
            }
            let revision = NovelMaterialRevisionRecord(
                id: revisionID,
                materialID: materialID,
                revision: revisionNumber,
                title: try NovelReducer.normalizedRequired(title, field: "Proposal title"),
                content: try NovelReducer.normalizedRequired(content, field: "Proposal content"),
                tags: NovelReducer.normalizedTags(tags),
                injectionMode: injectionMode,
                createdAt: now,
                operationID: command.context.operationID
            )
            next.materialRevisions.append(revision)
            if let materialIndex {
                next.materials[materialIndex].currentRevisionID = revisionID
                next.materials[materialIndex].revisionIDs.append(revisionID)
                if kind == .character {
                    next.materials[materialIndex].aliases = NovelCharacterIdentityResolver
                        .normalizedAliases(next.materials[materialIndex].aliases + acceptedAliases)
                }
            } else {
                next.materials.append(NovelMaterialRecord(
                    id: materialID,
                    kind: kind,
                    currentRevisionID: revisionID,
                    revisionIDs: [revisionID],
                    aliases: acceptedAliases
                ))
            }
            advanceConfigurationRevision(in: &next, now: now)
            outcome = .settingProposalAccepted(
                projectID: command.projectID,
                proposalID: command.proposalID,
                materialID: materialID,
                revisionID: revisionID,
                projectRevision: next.project.revision,
                configRevision: next.project.configRevision
            )

        case .reject:
            advanceProjectRevision(in: &next, now: now)
            outcome = .settingProposalRejected(
                projectID: command.projectID,
                proposalID: command.proposalID,
                projectRevision: next.project.revision,
                configRevision: next.project.configRevision
            )
        }
        NovelReducer.recordApplied(
            command.context,
            kind: .resolveSettingProposal,
            payloadSHA256: try NovelAction.resolveSettingProposal(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validate(next)
        return (next, outcome)
    }

    static func setBranchMaterialOverride(
        _ command: NovelSetBranchMaterialOverrideCommand,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> (document: NovelProjectDocumentV1, outcome: NovelOutcome) {
        try NovelReducer.requireConfigRevision(command.context, document: document)
        try NovelReducer.requireProjectRevision(command.context, document: document)
        guard let expectedHead = command.context.expectedBranchHeadRevision else {
            throw NovelError.invalidInput("Expected branch head revision is missing.")
        }
        guard let branchIndex = document.branches.firstIndex(where: {
            $0.id == command.branchID && $0.lifecycle == .active
        }) else {
            throw NovelError.branchNotFound(command.branchID)
        }
        let branch = document.branches[branchIndex]
        guard branch.headRevision == expectedHead else {
            throw NovelError.staleBranchHeadRevision(expected: expectedHead, actual: branch.headRevision)
        }
        guard branch.syncStatus == .synchronized,
              branch.activeRunID == nil,
              !document.pendingOperations.contains(where: { $0.branchID == branch.id }),
              !document.polishTransactions.contains(where: {
                  $0.branchID == branch.id && ($0.status == .pending || $0.status == .retryable)
              }) else {
            throw NovelError.projectBusy(command.projectID)
        }
        guard let materialIndex = document.materials.firstIndex(where: {
            $0.id == command.materialID && !$0.isDeleted
        }) else {
            throw NovelError.invalidInput("The branch override material is unavailable.")
        }

        var next = document
        let material = next.materials[materialIndex]
        let revisionByID = Dictionary(
            next.materialRevisions.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        next.branches[branchIndex].overrideRevisionIDs.removeAll { revisionID in
            revisionByID[revisionID]?.materialID == command.materialID
        }

        let selectedRevisionID: NovelMaterialRevisionID?
        switch command.change {
        case .inherit:
            selectedRevisionID = nil

        case .useRevision(let revisionID):
            guard material.revisionIDs.contains(revisionID),
                  revisionByID[revisionID]?.materialID == material.id else {
                throw NovelError.invalidInput("The selected override revision does not belong to the material.")
            }
            selectedRevisionID = revisionID

        case .createRevision(let revisionID, let title, let content, let tags, let injectionMode):
            guard revisionByID[revisionID] == nil else {
                throw NovelError.immutableRecordConflict("material revision \(revisionID)")
            }
            let revision = NovelMaterialRevisionRecord(
                id: revisionID,
                materialID: material.id,
                revision: Int64(material.revisionIDs.count + 1),
                title: try NovelReducer.normalizedRequired(title, field: "Override title"),
                content: content.trimmingCharacters(in: .whitespacesAndNewlines),
                tags: NovelReducer.normalizedTags(tags),
                injectionMode: injectionMode,
                createdAt: now,
                operationID: command.context.operationID
            )
            next.materialRevisions.append(revision)
            next.materials[materialIndex].revisionIDs.append(revisionID)
            selectedRevisionID = revisionID
        }
        if let selectedRevisionID {
            next.branches[branchIndex].overrideRevisionIDs.append(selectedRevisionID)
        }
        next.branches[branchIndex].overrideRevisionIDs.sort { $0.description < $1.description }
        next.branches[branchIndex].updatedAt = now
        advanceConfigurationRevision(in: &next, now: now)
        let outcome = NovelOutcome.branchMaterialOverrideChanged(
            projectID: command.projectID,
            branchID: command.branchID,
            materialID: command.materialID,
            revisionID: selectedRevisionID,
            projectRevision: next.project.revision,
            configRevision: next.project.configRevision
        )
        NovelReducer.recordApplied(
            command.context,
            kind: .setBranchMaterialOverride,
            payloadSHA256: try NovelAction.setBranchMaterialOverride(command).canonicalPayloadSHA256(),
            outcome: outcome,
            in: &next,
            now: now
        )
        try NovelDocumentValidator.validate(next)
        return (next, outcome)
    }

    private static func normalizedModelPolicy(
        _ policy: NovelProjectModelPolicy
    ) throws -> NovelProjectModelPolicy {
        switch policy {
        case .global:
            return .global
        case .fixed(let providerID, let modelID):
            let provider = providerID.trimmingCharacters(in: .whitespacesAndNewlines)
            let model = modelID.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !provider.isEmpty, !model.isEmpty,
                  provider.count <= 500, model.count <= 500 else {
                throw NovelError.invalidInput("A fixed project model requires stable provider and model IDs.")
            }
            return .fixed(providerID: provider, modelID: model)
        }
    }

    private static func advanceConfigurationRevision(
        in document: inout NovelProjectDocumentV1,
        now: Date
    ) {
        document.project.revision += 1
        document.project.configRevision += 1
        document.project.updatedAt = now
    }

    private static func advanceProjectRevision(
        in document: inout NovelProjectDocumentV1,
        now: Date
    ) {
        document.project.revision += 1
        document.project.updatedAt = now
    }
}
