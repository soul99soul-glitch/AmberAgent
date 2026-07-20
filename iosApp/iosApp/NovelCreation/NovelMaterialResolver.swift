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
                    material: material,
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
                material: material,
                revision: revision,
                isBranchOverride: false
            )
        }
    }
}
