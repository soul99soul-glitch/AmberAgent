import CryptoKit
import Foundation

struct NovelProjectPackageLimits: Equatable, Sendable {
    static let standard = NovelProjectPackageLimits(
        maximumProjectBytes: 100 * 1_024 * 1_024,
        maximumEnvelopeBytes: 140 * 1_024 * 1_024
    )

    let maximumProjectBytes: Int
    let maximumEnvelopeBytes: Int
}

struct NovelProjectPackageArtifact: Equatable, Sendable {
    let projectID: NovelProjectID
    let projectName: String
    let projectByteCount: Int
    let projectSHA256: String
    let data: Data
}

struct NovelDecodedProjectPackage: Equatable, Sendable {
    let artifact: NovelProjectPackageArtifact
    let document: NovelProjectDocumentV1
}

struct NovelProjectImportPreview: Equatable, Sendable {
    let sourceProjectID: NovelProjectID
    let projectName: String
    let projectRevision: Int64
    let schemaVersion: Int
    let envelopeByteCount: Int
    let projectByteCount: Int
    let projectSHA256: String
    let runningRunCount: Int
    let existingProject: NovelProjectSummary?
}

enum NovelProjectImportPolicy: Codable, Equatable, Sendable {
    case reject
    case replace(expectedRevision: Int64)
    case keepBoth(destinationProjectID: NovelProjectID)
}

enum NovelProjectImportDisposition: String, Codable, Equatable, Sendable {
    case created
    case replaced
    case keptBoth
}

struct NovelMarkdownExportArtifact: Equatable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let fileName: String
    let markdown: String
}

enum NovelProjectPackageCodec {
    static let format = "amber.novel.project"
    static let envelopeVersion = 1

    private struct EnvelopeV1: Codable {
        let format: String
        let envelopeVersion: Int
        let projectSchemaVersion: Int
        let projectID: NovelProjectID
        let projectByteCount: Int
        let projectSHA256: String
        let projectJSONBase64: String
    }

    private struct EnvelopeHeader: Decodable {
        let format: String
        let envelopeVersion: Int
        let projectSchemaVersion: Int
    }

    private struct ProjectSchemaHeader: Decodable {
        let schemaVersion: Int
    }

    static func encode(
        _ document: NovelProjectDocumentV1,
        limits: NovelProjectPackageLimits = .standard
    ) throws -> NovelProjectPackageArtifact {
        try validate(limits)
        try NovelDocumentValidator.validate(document)
        let projectData = try makeEncoder().encode(document)
        guard projectData.count <= limits.maximumProjectBytes else {
            throw NovelError.packageTooLarge(maximumBytes: limits.maximumProjectBytes)
        }
        let projectSHA256 = sha256(projectData)
        let envelope = EnvelopeV1(
            format: format,
            envelopeVersion: envelopeVersion,
            projectSchemaVersion: document.schemaVersion,
            projectID: document.project.id,
            projectByteCount: projectData.count,
            projectSHA256: projectSHA256,
            projectJSONBase64: projectData.base64EncodedString()
        )
        let data = try makeEncoder().encode(envelope)
        guard data.count <= limits.maximumEnvelopeBytes else {
            throw NovelError.packageTooLarge(maximumBytes: limits.maximumEnvelopeBytes)
        }
        return NovelProjectPackageArtifact(
            projectID: document.project.id,
            projectName: document.project.name,
            projectByteCount: projectData.count,
            projectSHA256: projectSHA256,
            data: data
        )
    }

    static func decode(
        _ data: Data,
        limits: NovelProjectPackageLimits = .standard
    ) throws -> NovelDecodedProjectPackage {
        try validate(limits)
        guard !data.isEmpty else {
            throw NovelError.invalidPackage("The novel project package is empty.")
        }
        guard data.count <= limits.maximumEnvelopeBytes else {
            throw NovelError.packageTooLarge(maximumBytes: limits.maximumEnvelopeBytes)
        }

        let header: EnvelopeHeader
        do {
            header = try makeDecoder().decode(EnvelopeHeader.self, from: data)
        } catch {
            throw NovelError.invalidPackage("The package envelope could not be decoded.")
        }
        guard header.format == format else {
            throw NovelError.invalidPackage("The package format is not recognized.")
        }
        guard header.envelopeVersion == envelopeVersion else {
            throw NovelError.invalidPackage(
                "Unsupported package envelope version \(header.envelopeVersion)."
            )
        }
        guard header.projectSchemaVersion == NovelProjectDocumentV1.currentSchemaVersion else {
            throw NovelError.unsupportedSchema(header.projectSchemaVersion)
        }

        let envelope: EnvelopeV1
        do {
            envelope = try makeDecoder().decode(EnvelopeV1.self, from: data)
        } catch {
            throw NovelError.invalidPackage("The package envelope is incomplete or invalid.")
        }
        guard envelope.projectByteCount >= 0 else {
            throw NovelError.invalidPackage("The project payload byte count is invalid.")
        }
        guard envelope.projectByteCount <= limits.maximumProjectBytes else {
            throw NovelError.packageTooLarge(maximumBytes: limits.maximumProjectBytes)
        }
        guard let projectData = Data(
            base64Encoded: envelope.projectJSONBase64,
            options: []
        ) else {
            throw NovelError.invalidPackage("The project payload is not strict Base64.")
        }
        guard projectData.base64EncodedString() == envelope.projectJSONBase64 else {
            throw NovelError.invalidPackage("The project payload is not canonical Base64.")
        }
        guard projectData.count == envelope.projectByteCount else {
            throw NovelError.invalidPackage("The project payload byte count does not match.")
        }
        guard sha256(projectData) == envelope.projectSHA256 else {
            throw NovelError.packageChecksumMismatch
        }

        let projectHeader: ProjectSchemaHeader
        do {
            projectHeader = try makeDecoder().decode(ProjectSchemaHeader.self, from: projectData)
        } catch {
            throw NovelError.invalidPackage("The project payload has no readable schema header.")
        }
        guard projectHeader.schemaVersion == envelope.projectSchemaVersion else {
            throw NovelError.invalidPackage("The envelope and project schema versions differ.")
        }
        guard projectHeader.schemaVersion == NovelProjectDocumentV1.currentSchemaVersion else {
            throw NovelError.unsupportedSchema(projectHeader.schemaVersion)
        }

        let document: NovelProjectDocumentV1
        do {
            let decoded = try makeDecoder().decode(NovelProjectDocumentV1.self, from: projectData)
            let generationNormalized = NovelGenerationReducer
                .normalizingLegacyInterruptedProseCandidates(decoded)
            document = NovelBranchSemantics.normalizingDecodedSyncStatus(generationNormalized)
        } catch {
            throw NovelError.invalidPackage("The project payload could not be decoded.")
        }
        guard document.project.id == envelope.projectID else {
            throw NovelError.invalidPackage("The envelope project ID does not match its payload.")
        }
        do {
            try NovelDocumentValidator.validate(document)
        } catch {
            throw NovelError.invalidPackage("The project payload failed integrity validation.")
        }

        return NovelDecodedProjectPackage(
            artifact: NovelProjectPackageArtifact(
                projectID: document.project.id,
                projectName: document.project.name,
                projectByteCount: projectData.count,
                projectSHA256: envelope.projectSHA256,
                data: data
            ),
            document: document
        )
    }

    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    private static func validate(_ limits: NovelProjectPackageLimits) throws {
        guard limits.maximumProjectBytes > 0,
              limits.maximumEnvelopeBytes >= limits.maximumProjectBytes else {
            throw NovelError.invalidInput("Novel project package limits are invalid.")
        }
    }

    private static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }

    private static func makeDecoder() -> JSONDecoder {
        JSONDecoder()
    }
}

enum NovelImportedProjectNormalizer {
    static func normalizeRunningRuns(
        in document: NovelProjectDocumentV1
    ) throws -> (document: NovelProjectDocumentV1, interruptedRunCount: Int) {
        var next = document
        let runningRunIDs = document.activeRuns
            .filter { $0.status == .running }
            .sorted {
                if $0.startedAt != $1.startedAt { return $0.startedAt < $1.startedAt }
                return $0.id.description < $1.id.description
            }
            .map(\.id)
        for runID in runningRunIDs {
            guard let run = next.activeRuns.first(where: { $0.id == runID }) else { continue }
            let normalizedAt = max(next.project.updatedAt, run.startedAt)
            next = try NovelGenerationReducer.interrupt(
                runID: runID,
                reason: .recovery,
                partialContent: run.partialContent,
                in: next,
                now: normalizedAt
            ).document
        }
        return (next, runningRunIDs.count)
    }
}

enum NovelProjectIdentityRemapper {
    static func remap(
        _ document: NovelProjectDocumentV1,
        to projectID: NovelProjectID
    ) throws -> NovelProjectDocumentV1 {
        guard document.project.id != projectID else { return document }
        var next = document
        let project = document.project
        next.project = NovelProjectRecord(
            id: projectID,
            name: project.name,
            creationMode: project.creationMode,
            quickStartSeed: project.quickStartSeed,
            createdAt: project.createdAt,
            updatedAt: project.updatedAt,
            revision: project.revision,
            configRevision: project.configRevision,
            mainBranchID: project.mainBranchID,
            modelPolicy: project.modelPolicy,
            stateSyncModelPolicy: project.stateSyncModelPolicy,
            lastGenerationGranularity: project.lastGenerationGranularity,
            polishPreference: project.polishPreference,
            collaborationMode: project.collaborationMode,
            pauseGhostwriteOnBlockingContinuity: project.pauseGhostwriteOnBlockingContinuity,
            reviewModelPolicy: project.reviewModelPolicy
        )
        next.injectionReceipts = document.injectionReceipts.map {
            $0.remappingProjectID(to: projectID)
        }
        next.appliedOperations = document.appliedOperations.map {
            NovelAppliedOperationRecord(
                operationID: $0.operationID,
                kind: $0.kind,
                payloadSHA256: $0.payloadSHA256,
                outcome: $0.outcome.remappingProjectID(to: projectID),
                appliedProjectRevision: $0.appliedProjectRevision,
                appliedAt: $0.appliedAt
            )
        }
        try NovelDocumentValidator.validate(next)
        return next
    }
}

enum NovelMarkdownExporter {
    static func export(
        _ document: NovelProjectDocumentV1,
        branchID: NovelBranchID
    ) throws -> NovelMarkdownExportArtifact {
        try NovelDocumentValidator.validate(document)
        guard let branch = document.branches.first(where: { $0.id == branchID }),
              branch.lifecycle == .active else {
            throw NovelError.branchNotFound(branchID)
        }
        guard let checkpoint = document.checkpoints.first(where: {
            $0.id == branch.headCheckpointID
        }) else {
            throw NovelError.checkpointNotFound(branch.headCheckpointID)
        }

        // 已废弃的章不进成稿:这正是「废弃」的用户语义。此前只有生成上下文
        // 排除了它们(NovelInjectionPlanner),导出仍照单全收,表现为
        // 「明明废弃了，导出来还在」。
        let discarded = Set(
            document.chapters.filter { $0.discardedAt != nil }.map(\.id)
        )
        let exportedSelections = checkpoint.chapterSelections.filter {
            !discarded.contains($0.chapterID)
        }
        let sections = try exportedSelections.enumerated().map { index, selection in
            guard let version = document.chapterVersions.first(where: {
                $0.id == selection.versionID && $0.chapterID == selection.chapterID
            }) else {
                throw NovelError.invalidDocument([
                    "Head checkpoint references a missing chapter version.",
                ])
            }
            let trimmedTitle = version.title.trimmingCharacters(in: .whitespacesAndNewlines)
            let title = trimmedTitle.isEmpty ? "Chapter \(index + 1)" : trimmedTitle
            let content = version.content.trimmingCharacters(in: .whitespacesAndNewlines)
            return "# \(title)\n\n\(content)"
        }
        let stem = sanitizedFileStem("\(document.project.name)-\(branch.name)")
        return NovelMarkdownExportArtifact(
            projectID: document.project.id,
            branchID: branchID,
            fileName: "\(stem).md",
            markdown: sections.joined(separator: "\n\n") + (sections.isEmpty ? "" : "\n")
        )
    }

    private static func sanitizedFileStem(_ value: String) -> String {
        let forbidden = CharacterSet(charactersIn: "/\\:?%*|\"<>")
        let scalars = value.unicodeScalars.map { scalar in
            forbidden.contains(scalar) ? "-" : String(scalar)
        }
        let trimmed = scalars.joined().trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Novel" : trimmed
    }
}

extension NovelOutcome {
    func remappingProjectID(to projectID: NovelProjectID) -> NovelOutcome {
        switch self {
        case .projectCreated(_, let branchID):
            return .projectCreated(projectID: projectID, branchID: branchID)
        case .projectRenamed(_, let revision):
            return .projectRenamed(projectID: projectID, revision: revision)
        case .materialRevised(_, let materialID, let revisionID, let projectRevision, let configRevision):
            return .materialRevised(
                projectID: projectID,
                materialID: materialID,
                revisionID: revisionID,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .materialDeleted(_, let materialID, let projectRevision, let configRevision):
            return .materialDeleted(
                projectID: projectID,
                materialID: materialID,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .modelPolicyChanged(_, let projectRevision, let configRevision):
            return .modelPolicyChanged(
                projectID: projectID,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .settingProposalAccepted(
            _, let proposalID, let materialID, let revisionID, let projectRevision, let configRevision
        ):
            return .settingProposalAccepted(
                projectID: projectID,
                proposalID: proposalID,
                materialID: materialID,
                revisionID: revisionID,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .settingProposalRejected(_, let proposalID, let projectRevision, let configRevision):
            return .settingProposalRejected(
                projectID: projectID,
                proposalID: proposalID,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .branchMaterialOverrideChanged(
            _, let branchID, let materialID, let revisionID, let projectRevision, let configRevision
        ):
            return .branchMaterialOverrideChanged(
                projectID: projectID,
                branchID: branchID,
                materialID: materialID,
                revisionID: revisionID,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .mainBranchChanged(_, let branchID, let revision):
            return .mainBranchChanged(projectID: projectID, branchID: branchID, revision: revision)
        case .polishPreferenceChanged(_, let projectRevision, let configRevision):
            return .polishPreferenceChanged(
                projectID: projectID,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .collaborationModeChanged(_, let mode, let projectRevision, let configRevision):
            return .collaborationModeChanged(
                projectID: projectID,
                mode: mode,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .pauseGhostwriteOnBlockingContinuityChanged(
            _, let enabled, let projectRevision, let configRevision
        ):
            return .pauseGhostwriteOnBlockingContinuityChanged(
                projectID: projectID,
                enabled: enabled,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .chapterPlanUpserted(
            _, let branchID, let planID, let status, let contentDigest, let projectRevision, let configRevision
        ):
            return .chapterPlanUpserted(
                projectID: projectID,
                branchID: branchID,
                planID: planID,
                status: status,
                contentDigest: contentDigest,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .chapterPlanCleared(_, let branchID, let projectRevision, let configRevision):
            return .chapterPlanCleared(
                projectID: projectID,
                branchID: branchID,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .upcomingArcUpserted(_, let branchID, let beatCount, let projectRevision, let configRevision):
            return .upcomingArcUpserted(
                projectID: projectID,
                branchID: branchID,
                beatCount: beatCount,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .upcomingArcCleared(_, let branchID, let projectRevision, let configRevision):
            return .upcomingArcCleared(
                projectID: projectID,
                branchID: branchID,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .characterIdentityClarified(
            _, let branchID, let mention, let checkpointID, let stateSnapshotID, let revision
        ):
            return .characterIdentityClarified(
                projectID: projectID,
                branchID: branchID,
                mention: mention,
                checkpointID: checkpointID,
                stateSnapshotID: stateSnapshotID,
                revision: revision
            )
        case .branchForked(_, let sourceBranchID, let branchID, let checkpointID, let revision):
            return .branchForked(
                projectID: projectID,
                sourceBranchID: sourceBranchID,
                branchID: branchID,
                checkpointID: checkpointID,
                revision: revision
            )
        case .branchRenamed(_, let branchID, let revision):
            return .branchRenamed(projectID: projectID, branchID: branchID, revision: revision)
        case .branchDeleted(_, let branchID, let revision):
            return .branchDeleted(projectID: projectID, branchID: branchID, revision: revision)
        case .branchHeadMoved(
            _, let branchID, let fromCheckpointID, let toCheckpointID, let headRevision, let revision
        ):
            return .branchHeadMoved(
                projectID: projectID,
                branchID: branchID,
                fromCheckpointID: fromCheckpointID,
                toCheckpointID: toCheckpointID,
                headRevision: headRevision,
                revision: revision
            )
        case .discussionArchived(
            _, let branchID, let archiveID, let checkpointID, let decisionRevisionIDs,
            let projectRevision, let configRevision
        ):
            return .discussionArchived(
                projectID: projectID,
                branchID: branchID,
                archiveID: archiveID,
                checkpointID: checkpointID,
                decisionRevisionIDs: decisionRevisionIDs,
                projectRevision: projectRevision,
                configRevision: configRevision
            )
        case .candidateCloned(_, let branchID, let sourceCandidateID, let candidateID, let revision):
            return .candidateCloned(
                projectID: projectID,
                branchID: branchID,
                sourceCandidateID: sourceCandidateID,
                candidateID: candidateID,
                revision: revision
            )
        case .polishCandidateAdopted(
            _, let branchID, let candidateID, let checkpointID, let chapterVersionID, let revision
        ):
            return .polishCandidateAdopted(
                projectID: projectID,
                branchID: branchID,
                candidateID: candidateID,
                checkpointID: checkpointID,
                chapterVersionID: chapterVersionID,
                revision: revision
            )
        case .polishCandidateRejected(
            _, let branchID, let candidateID, let transactionID, let revision
        ):
            return .polishCandidateRejected(
                projectID: projectID,
                branchID: branchID,
                candidateID: candidateID,
                transactionID: transactionID,
                revision: revision
            )
        case .polishTransactionAbandoned(
            _, let branchID, let candidateID, let transactionID, let revision
        ):
            return .polishTransactionAbandoned(
                projectID: projectID,
                branchID: branchID,
                candidateID: candidateID,
                transactionID: transactionID,
                revision: revision
            )
        case .chapterDiscardStateChanged(_, let branchID, let chapterID, let isDiscarded, let revision):
            return .chapterDiscardStateChanged(
                projectID: projectID,
                branchID: branchID,
                chapterID: chapterID,
                isDiscarded: isDiscarded,
                revision: revision
            )
        case .chapterVersionRestored(_, let branchID, let checkpointID, let chapterVersionID, let revision):
            return .chapterVersionRestored(
                projectID: projectID,
                branchID: branchID,
                checkpointID: checkpointID,
                chapterVersionID: chapterVersionID,
                revision: revision
            )
        case .runStarted(_, let branchID, let runID, let receiptID, let revision):
            return .runStarted(
                projectID: projectID,
                branchID: branchID,
                runID: runID,
                receiptID: receiptID,
                revision: revision
            )
        case .runInterrupted(_, let runID, let reason, let revision):
            return .runInterrupted(
                projectID: projectID,
                runID: runID,
                reason: reason,
                revision: revision
            )
        case .candidateCollected(
            _, let branchID, let candidateID, let checkpointID, let chapterVersionID, let revision
        ):
            return .candidateCollected(
                projectID: projectID,
                branchID: branchID,
                candidateID: candidateID,
                checkpointID: checkpointID,
                chapterVersionID: chapterVersionID,
                revision: revision
            )
        case .manualEditSaved(_, let branchID, let chapterVersionID, let workingRevision, let revision):
            return .manualEditSaved(
                projectID: projectID,
                branchID: branchID,
                chapterVersionID: chapterVersionID,
                workingRevision: workingRevision,
                revision: revision
            )
        case .manualSyncCommitted(_, let branchID, let checkpointID, let revision):
            return .manualSyncCommitted(
                projectID: projectID,
                branchID: branchID,
                checkpointID: checkpointID,
                revision: revision
            )
        case .projectImported(
            let sourceProjectID, _, let disposition, let interruptedRunCount, let revision
        ):
            return .projectImported(
                sourceProjectID: sourceProjectID,
                projectID: projectID,
                disposition: disposition,
                interruptedRunCount: interruptedRunCount,
                revision: revision
            )
        case .previousProjectRestored(_, let revision):
            return .previousProjectRestored(projectID: projectID, revision: revision)
        case .projectDeleted:
            return .projectDeleted(projectID: projectID)
        }
    }
}
