import Foundation

struct NovelEffectiveMaterialRevision: Equatable, Sendable {
    let material: NovelMaterialRecord
    let revision: NovelMaterialRevisionRecord
    let isBranchOverride: Bool
}

enum NovelMaterialResolutionError: Error, Equatable, Sendable {
    case missingRevision(NovelMaterialRevisionID)
}

enum NovelMaterialResolver {
    static func effectiveRevisions(
        document: NovelProjectDocumentV1,
        branch: NovelBranchRecord
    ) throws -> [NovelEffectiveMaterialRevision] {
        let revisionByID = Dictionary(
            document.materialRevisions.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let operationKindByID = Dictionary(
            document.appliedOperations.map { ($0.operationID, $0.kind) },
            uniquingKeysWith: { first, _ in first }
        )
        let acceptedProposalTitlesByMaterialID = acceptedProposalTitlesByMaterialID(
            proposals: document.settingProposals,
            operations: document.appliedOperations,
            revisionByID: revisionByID
        )
        var overridesByMaterial: [NovelMaterialID: NovelMaterialRevisionRecord] = [:]
        for revisionID in branch.overrideRevisionIDs {
            guard let revision = revisionByID[revisionID] else {
                throw NovelMaterialResolutionError.missingRevision(revisionID)
            }
            if let current = overridesByMaterial[revision.materialID] {
                if current.revision < revision.revision ||
                    (current.revision == revision.revision &&
                        current.id.description < revision.id.description) {
                    overridesByMaterial[revision.materialID] = revision
                }
            } else {
                overridesByMaterial[revision.materialID] = revision
            }
        }

        return try document.materials.filter { !$0.isDeleted }.compactMap { material in
            if let override = overridesByMaterial[material.id] {
                return NovelEffectiveMaterialRevision(
                    material: materialResolvingIdentityAliases(
                        material,
                        effectiveRevision: override,
                        revisionByID: revisionByID,
                        operationKindByID: operationKindByID,
                        acceptedProposalTitles: acceptedProposalTitlesByMaterialID[material.id] ?? []
                    ),
                    revision: override,
                    isBranchOverride: true
                )
            }
            if material.kind == .decisionLog {
                return nil
            }
            guard let revision = revisionByID[material.currentRevisionID] else {
                throw NovelMaterialResolutionError.missingRevision(material.currentRevisionID)
            }
            return NovelEffectiveMaterialRevision(
                material: materialResolvingIdentityAliases(
                    material,
                    effectiveRevision: revision,
                    revisionByID: revisionByID,
                    operationKindByID: operationKindByID,
                    acceptedProposalTitles: acceptedProposalTitlesByMaterialID[material.id] ?? []
                ),
                revision: revision,
                isBranchOverride: false
            )
        }
    }

    static func effectiveAliases(
        for material: NovelMaterialRecord,
        effectiveRevision: NovelMaterialRevisionRecord,
        materialRevisions: [NovelMaterialRevisionRecord],
        proposals: [NovelSettingProposalRecord],
        appliedOperations: [NovelAppliedOperationRecord]
    ) -> [String] {
        let revisionByID = Dictionary(
            materialRevisions.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let operationKindByID = Dictionary(
            appliedOperations.map { ($0.operationID, $0.kind) },
            uniquingKeysWith: { first, _ in first }
        )
        let proposalTitles = acceptedProposalTitlesByMaterialID(
            proposals: proposals,
            operations: appliedOperations,
            revisionByID: revisionByID
        )[material.id] ?? []
        return materialResolvingIdentityAliases(
            material,
            effectiveRevision: effectiveRevision,
            revisionByID: revisionByID,
            operationKindByID: operationKindByID,
            acceptedProposalTitles: proposalTitles
        ).aliases
    }

    private static func materialResolvingIdentityAliases(
        _ material: NovelMaterialRecord,
        effectiveRevision: NovelMaterialRevisionRecord,
        revisionByID: [NovelMaterialRevisionID: NovelMaterialRevisionRecord],
        operationKindByID: [NovelOperationID: NovelOperationKind],
        acceptedProposalTitles: [String]
    ) -> NovelMaterialRecord {
        guard material.kind == .character else { return material }
        let canonicalKey = NovelCharacterIdentityResolver.normalize(effectiveRevision.title)
        let historicalTitles = material.revisionIDs.compactMap { revisionID -> String? in
            guard let revision = revisionByID[revisionID],
                  revision.revision < effectiveRevision.revision,
                  let operationKind = operationKindByID[revision.operationID],
                  operationKind == .reviseMaterial ||
                    operationKind == .resolveSettingProposal else { return nil }
            return revision.title
        }
        var resolved = material
        resolved.aliases = NovelCharacterIdentityResolver
            .normalizedAliases(material.aliases + historicalTitles + acceptedProposalTitles)
            .filter { NovelCharacterIdentityResolver.normalize($0) != canonicalKey }
        return resolved
    }

    private static func acceptedProposalTitlesByMaterialID(
        proposals: [NovelSettingProposalRecord],
        operations: [NovelAppliedOperationRecord],
        revisionByID: [NovelMaterialRevisionID: NovelMaterialRevisionRecord]
    ) -> [NovelMaterialID: [String]] {
        let proposalByID = Dictionary(
            proposals.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var titles: [NovelMaterialID: [String]] = [:]
        for operation in operations {
            guard operation.kind == .resolveSettingProposal,
                  case .settingProposalAccepted(
                      _, let proposalID, let materialID, let revisionID, _, _
                  ) = operation.outcome,
                  let proposal = proposalByID[proposalID],
                  let revision = revisionByID[revisionID],
                  revision.materialID == materialID,
                  NovelCharacterIdentityResolver.normalize(proposal.title) !=
                    NovelCharacterIdentityResolver.normalize(revision.title) else { continue }
            titles[materialID, default: []].append(proposal.title)
        }
        return titles
    }
}
