import CryptoKit
import Foundation

enum NovelDocumentValidator {
    static func validate(_ document: NovelProjectDocumentV1) throws {
        guard document.schemaVersion == NovelProjectDocumentV1.currentSchemaVersion else {
            throw NovelError.unsupportedSchema(document.schemaVersion)
        }

        var issues: [String] = []
        validateProject(document, issues: &issues)
        validateMaterials(document, issues: &issues)
        validateSessionsAndBranches(document, issues: &issues)
        validateChaptersAndState(document, issues: &issues)
        validateSettingProposals(document, issues: &issues)
        NovelCompatibilityLineageValidator.validate(document, issues: &issues)
        validateCheckpoints(document, issues: &issues)
        validateCandidates(document, issues: &issues)
        validateRunsAndPendingOperations(document, issues: &issues)
        NovelPolishDocumentValidator.validate(document, issues: &issues)
        NovelGenerationDocumentValidator.validate(document, issues: &issues)
        validateOperationLedger(document, issues: &issues)

        if !issues.isEmpty {
            throw NovelError.invalidDocument(Array(Set(issues)).sorted())
        }
    }

    static func validateRecovery(_ sidecar: NovelRecoverySidecarV1) throws {
        guard sidecar.schemaVersion == NovelRecoverySidecarV1.currentSchemaVersion else {
            throw NovelError.invalidRecovery("Unsupported schema version \(sidecar.schemaVersion).")
        }
        guard sidecar.sequence >= 0 else {
            throw NovelError.invalidRecovery("Sequence must be non-negative.")
        }
        guard sidecar.baseProjectRevision >= 1 else {
            throw NovelError.invalidRecovery("Base project revision must be positive.")
        }
        guard isSHA256(sidecar.partialSHA256) else {
            throw NovelError.invalidRecovery("Partial content hash is not SHA-256.")
        }
        guard sidecar.partialSHA256.lowercased() == sha256(sidecar.partialContent) else {
            throw NovelError.invalidRecovery("Partial content does not match its SHA-256 hash.")
        }
    }

    static func validateTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1
    ) throws {
        try validate(current)
        try validate(next)

        var issues: [String] = []
        if next.project.id != current.project.id {
            issues.append("A project transition changed the project ID.")
        }
        if next.project.createdAt != current.project.createdAt ||
            next.project.creationMode != current.project.creationMode ||
            next.project.quickStartSeed != current.project.quickStartSeed {
            issues.append("A project transition rewrote immutable creation metadata.")
        }

        appendUnchangedPrefixIssue(
            current.materialRevisions,
            next.materialRevisions,
            label: "material revision",
            issues: &issues
        )
        appendUnchangedPrefixIssue(current.chapters, next.chapters, label: "chapter", issues: &issues)
        appendUnchangedPrefixIssue(
            current.chapterVersions,
            next.chapterVersions,
            label: "chapter version",
            issues: &issues
        )
        appendUnchangedPrefixIssue(current.events, next.events, label: "story event", issues: &issues)
        appendUnchangedPrefixIssue(
            current.stateSnapshots,
            next.stateSnapshots,
            label: "state snapshot",
            issues: &issues
        )
        appendUnchangedPrefixIssue(
            current.checkpoints,
            next.checkpoints,
            label: "checkpoint",
            issues: &issues
        )
        appendUnchangedPrefixIssue(
            current.injectionReceipts,
            next.injectionReceipts,
            label: "injection receipt",
            issues: &issues
        )
        appendUnchangedPrefixIssue(
            current.generationReceipts,
            next.generationReceipts,
            label: "generation receipt",
            issues: &issues
        )
        appendUnchangedPrefixIssue(
            current.factAttempts,
            next.factAttempts,
            label: "fact attempt",
            issues: &issues
        )
        NovelPolishDocumentValidator.validateTransition(
            from: current,
            to: next,
            issues: &issues
        )
        appendUnchangedPrefixIssue(
            current.appliedOperations,
            next.appliedOperations,
            label: "applied operation",
            issues: &issues
        )

        validateMaterialTransition(from: current, to: next, issues: &issues)
        validateBranchTransition(from: current, to: next, issues: &issues)
        validateSessionTransition(from: current, to: next, issues: &issues)
        validateCandidateTransition(from: current, to: next, issues: &issues)
        validateSettingProposalTransition(from: current, to: next, issues: &issues)
        validatePendingOperationTransition(from: current, to: next, issues: &issues)
        validateFactAttemptTransition(from: current, to: next, issues: &issues)
        validateNewUndoTransitions(from: current, to: next, issues: &issues)
        NovelGenerationDocumentValidator.validateTransition(
            from: current,
            to: next,
            issues: &issues
        )

        if !issues.isEmpty {
            throw NovelError.invalidDocument(Array(Set(issues)).sorted())
        }
    }

    private static func validateNewUndoTransitions(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for operation in next.appliedOperations.dropFirst(current.appliedOperations.count) {
            guard operation.kind == .undoBranchHead,
                  case let .branchHeadMoved(
                      _, branchID, fromCheckpointID, toCheckpointID, _, _
                  ) = operation.outcome,
                  let branch = current.branches.first(where: { $0.id == branchID }),
                  let from = current.checkpoints.first(where: { $0.id == fromCheckpointID }) else {
                continue
            }
            let expected = NovelBranchSemantics.undoTarget(
                for: from,
                branch: branch,
                checkpoints: current.checkpoints
            )
            if branch.headCheckpointID != fromCheckpointID || expected?.id != toCheckpointID {
                issues.append("A new branch undo did not use the current semantic target.")
            }
        }
    }

    static func isSHA256(_ value: String) -> Bool {
        let normalized = value.lowercased()
        return normalized.count == 64 && normalized.unicodeScalars.allSatisfy { scalar in
            (48...57).contains(scalar.value) || (97...102).contains(scalar.value)
        }
    }

    static func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }

    private static func validateFactAttemptTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        guard current.factAttempts != next.factAttempts else { return }
        guard next.factAttempts.count == current.factAttempts.count + 1,
              Array(next.factAttempts.dropLast()) == current.factAttempts,
              let appended = next.factAttempts.last,
              let owner = current.pendingOperations.first(where: {
                  $0.id == appended.pendingID &&
                      $0.operationID == appended.ownerOperationID &&
                      $0.branchID == appended.branchID
              }) else {
            issues.append("A fact retry reservation did not append one matching attempt.")
            return
        }

        let expectedKind: NovelFactReceiptKind = owner.kind == .collection
            ? .stateDelta
            : .manualRebuild
        let expectedFirstChunk = owner.kind == .manualSync
            ? (owner.manualSyncProgress?.nextChunkIndex ?? 0)
            : nil
        if appended.attemptOperationID == owner.operationID ||
            appended.kind != expectedKind ||
            appended.firstChunkIndex != expectedFirstChunk {
            issues.append("A fact retry reservation does not match its pending owner.")
        }

        var expectedProject = current.project
        expectedProject.revision = next.project.revision
        expectedProject.updatedAt = next.project.updatedAt
        let isolated = next.schemaVersion == current.schemaVersion &&
            next.project == expectedProject &&
            next.project.revision == current.project.revision + 1 &&
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
            next.injectionReceipts == current.injectionReceipts &&
            next.generationReceipts == current.generationReceipts &&
            next.pendingOperations == current.pendingOperations &&
            next.activeRuns == current.activeRuns &&
            next.settingProposals == current.settingProposals &&
            next.appliedOperations == current.appliedOperations
        if !isolated {
            issues.append("A fact retry reservation was not an isolated atomic transition.")
        }
    }

    private static func validateProject(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let project = document.project
        if project.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            issues.append("Project name is empty.")
        }
        if project.revision < 1 { issues.append("Project revision must be positive.") }
        if project.configRevision < 1 { issues.append("Config revision must be positive.") }
        if project.updatedAt < project.createdAt { issues.append("Project timestamps move backwards.") }
        switch project.creationMode {
        case .blank where project.quickStartSeed != nil:
            issues.append("Blank project contains a quick-start seed.")
        case .quickStart where project.quickStartSeed == nil:
            issues.append("Quick-start project is missing its seed.")
        default:
            break
        }
        if case .fixed(let providerID, let modelID) = project.modelPolicy,
           providerID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            modelID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            issues.append("Fixed project model policy has an empty stable ID.")
        }
        if case .fixed(let providerID, let modelID) = project.stateSyncModelPolicy,
           providerID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            modelID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            issues.append("Fixed state-sync model policy has an empty stable ID.")
        }

        appendDuplicateIssue(document.branches, key: \.id, label: "branch", issues: &issues)
        appendDuplicateIssue(document.sessions, key: \.id, label: "Session", issues: &issues)
        appendDuplicateIssue(document.materials, key: \.id, label: "material", issues: &issues)
        appendDuplicateIssue(document.materialRevisions, key: \.id, label: "material revision", issues: &issues)
        appendDuplicateIssue(document.chapters, key: \.id, label: "chapter", issues: &issues)
        appendDuplicateIssue(document.chapterVersions, key: \.id, label: "chapter version", issues: &issues)
        appendDuplicateIssue(document.events, key: \.id, label: "event", issues: &issues)
        appendDuplicateIssue(document.stateSnapshots, key: \.id, label: "state snapshot", issues: &issues)
        appendDuplicateIssue(document.checkpoints, key: \.id, label: "checkpoint", issues: &issues)
        appendDuplicateIssue(document.candidates, key: \.id, label: "candidate", issues: &issues)
        appendDuplicateIssue(document.injectionReceipts, key: \.id, label: "injection receipt", issues: &issues)
        appendDuplicateIssue(document.generationReceipts, key: \.id, label: "generation receipt", issues: &issues)
        appendDuplicateIssue(
            document.factAttempts,
            key: \.attemptOperationID,
            label: "fact attempt",
            issues: &issues
        )
        appendDuplicateIssue(document.pendingOperations, key: \.id, label: "pending operation", issues: &issues)
        appendDuplicateIssue(document.activeRuns, key: \.id, label: "active run", issues: &issues)
        appendDuplicateIssue(document.settingProposals, key: \.id, label: "setting proposal", issues: &issues)
        appendDuplicateIssue(document.appliedOperations, key: \.operationID, label: "operation", issues: &issues)
    }

    private static func validateMaterials(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        var revisionByID: [NovelMaterialRevisionID: NovelMaterialRevisionRecord] = [:]
        for revision in document.materialRevisions where revisionByID[revision.id] == nil {
            revisionByID[revision.id] = revision
        }
        let operationIDs = Set(document.appliedOperations.map(\.operationID))
        let operationIndex = Dictionary(
            document.appliedOperations.enumerated().map { ($0.element.operationID, $0.offset) },
            uniquingKeysWith: { first, _ in first }
        )

        for material in document.materials {
            if material.kind != .character, !material.aliases.isEmpty {
                issues.append("Non-character material \(material.id) contains character aliases.")
            }
            if material.aliases != NovelCharacterIdentityResolver.normalizedAliases(material.aliases) {
                issues.append("Material \(material.id) contains invalid or duplicate aliases.")
            }
            if material.revisionIDs.isEmpty {
                issues.append("Material \(material.id) has no revisions.")
            }
            if Set(material.revisionIDs).count != material.revisionIDs.count {
                issues.append("Material \(material.id) repeats a revision reference.")
            }
            guard material.revisionIDs.contains(material.currentRevisionID) else {
                issues.append("Material \(material.id) current revision is not in its history.")
                continue
            }
            for (offset, revisionID) in material.revisionIDs.enumerated() {
                guard let revision = revisionByID[revisionID] else {
                    issues.append("Material \(material.id) references a missing revision.")
                    continue
                }
                if revision.materialID != material.id {
                    issues.append("Material revision \(revision.id) belongs to another material.")
                }
                if revision.revision != Int64(offset + 1) {
                    issues.append("Material \(material.id) revision numbers are not contiguous.")
                }
                if !operationIDs.contains(revision.operationID) {
                    issues.append("Material revision \(revision.id) has no applied operation.")
                }
            }
            let deleteOwners = document.appliedOperations.enumerated().filter { _, operation in
                guard operation.kind == .deleteMaterial,
                      case let .materialDeleted(_, materialID, _, _) = operation.outcome else {
                    return false
                }
                return materialID == material.id
            }
            if material.isDeleted {
                if deleteOwners.count != 1 {
                    issues.append("Deleted material \(material.id) has no unique delete operation.")
                } else if let deleteIndex = deleteOwners.first?.offset,
                          material.revisionIDs.contains(where: { revisionID in
                              guard let revision = revisionByID[revisionID],
                                    let revisionIndex = operationIndex[revision.operationID] else {
                                  return false
                              }
                              return revisionIndex >= deleteIndex
                          }) {
                    issues.append("Deleted material \(material.id) has a revision at or after deletion.")
                }
            } else if !deleteOwners.isEmpty {
                issues.append("Active material \(material.id) has a delete operation.")
            }
        }
        for revision in document.materialRevisions {
            guard let material = document.materials.first(where: { $0.id == revision.materialID }) else {
                issues.append("Material revision \(revision.id) has no material.")
                continue
            }
            if !material.revisionIDs.contains(revision.id) {
                issues.append("Material revision \(revision.id) is omitted from its material history.")
            }
        }
    }

    private static func validateSessionsAndBranches(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let activeBranches = document.branches.filter { $0.lifecycle == .active }
        if activeBranches.isEmpty { issues.append("Project has no active branches.") }
        guard let mainBranch = document.branches.first(where: {
            $0.id == document.project.mainBranchID
        }), mainBranch.lifecycle == .active else {
            issues.append("Main branch is missing or deleted.")
            return
        }

        if Set(document.branches.map(\.sessionID)).count != document.branches.count {
            issues.append("A creation Session is shared by multiple branches.")
        }
        let allMessageIDs = document.sessions.flatMap { $0.messages.map(\.id) }
        if Set(allMessageIDs).count != allMessageIDs.count {
            issues.append("A message ID is shared by multiple creation Sessions.")
        }
        for branch in document.branches {
            if branch.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                issues.append("Branch \(branch.id) has an empty name.")
            }
            if branch.headRevision < 0 || branch.workingRevision < 0 {
                issues.append("Branch \(branch.id) has a negative revision.")
            }
            guard let session = document.sessions.first(where: { $0.id == branch.sessionID }) else {
                issues.append("Branch \(branch.id) has no Session.")
                continue
            }
            if session.branchID != branch.id {
                issues.append("Branch \(branch.id) and Session \(session.id) disagree on ownership.")
            }
            let expectedSequences = session.messages.indices.map(Int64.init)
            if session.messages.map(\.sequence) != expectedSequences {
                issues.append("Session \(session.id) message sequence is not contiguous.")
            }
            if Set(session.messages.map(\.id)).count != session.messages.count {
                issues.append("Session \(session.id) repeats a message ID.")
            }
            validateSessionInteractions(session, issues: &issues)
            guard let head = document.checkpoints.first(where: {
                $0.id == branch.headCheckpointID
            }) else {
                issues.append("Branch \(branch.id) has no valid head checkpoint.")
                continue
            }
            if head.stateSnapshotID != branch.currentStateSnapshotID {
                issues.append("Branch \(branch.id) state does not match its head checkpoint.")
            }
            if branch.syncStatus == .synchronized,
               branch.workingChapterSelections != head.chapterSelections {
                issues.append("Synchronized branch \(branch.id) working manuscript differs from its head.")
            }
            if Set(branch.workingChapterSelections.map(\.chapterID)).count !=
                branch.workingChapterSelections.count {
                issues.append("Branch \(branch.id) working manuscript repeats a chapter.")
            }
            for selection in branch.workingChapterSelections where
                !document.chapterVersions.contains(where: {
                    $0.id == selection.versionID && $0.chapterID == selection.chapterID
                }) {
                issues.append("Branch \(branch.id) working manuscript has an invalid chapter version.")
            }
            for revisionID in branch.overrideRevisionIDs where
                !document.materialRevisions.contains(where: { $0.id == revisionID }) {
                issues.append("Branch \(branch.id) has a missing override revision.")
            }
            let overrideMaterialIDs = branch.overrideRevisionIDs.compactMap { revisionID in
                document.materialRevisions.first(where: { $0.id == revisionID })?.materialID
            }
            if Set(branch.overrideRevisionIDs).count != branch.overrideRevisionIDs.count ||
                Set(overrideMaterialIDs).count != overrideMaterialIDs.count {
                issues.append("Branch \(branch.id) repeats a material override.")
            }
            if let origin = branch.forkOrigin {
                if !document.branches.contains(where: { $0.id == origin.parentBranchID }) {
                    issues.append("Branch \(branch.id) has a missing fork parent.")
                }
                if !document.checkpoints.contains(where: { $0.id == origin.checkpointID }) {
                    issues.append("Branch \(branch.id) has a missing fork checkpoint.")
                }
                let forkOwners = document.appliedOperations.filter { operation in
                    guard operation.kind == .forkBranch,
                          case let .branchForked(
                              _, sourceBranchID, branchID, checkpointID, _
                          ) = operation.outcome else {
                        return false
                    }
                    return sourceBranchID == origin.parentBranchID &&
                        branchID == branch.id &&
                        checkpointID == origin.checkpointID
                }
                if forkOwners.count != 1 {
                    issues.append("Branch \(branch.id) has no unique fork operation.")
                }
            }
            if branch.lifecycle == .deleted {
                let deleteOwners = document.appliedOperations.filter { operation in
                    guard operation.kind == .deleteBranch,
                          case let .branchDeleted(_, branchID, _) = operation.outcome else {
                        return false
                    }
                    return branchID == branch.id
                }
                if deleteOwners.count != 1 {
                    issues.append("Deleted branch \(branch.id) has no unique delete operation.")
                }
            }
            if branch.lifecycle == .deleted,
               branch.activeRunID != nil ||
                document.pendingOperations.contains(where: { $0.branchID == branch.id }) ||
                document.polishTransactions.contains(where: {
                    $0.branchID == branch.id &&
                        ($0.status == .pending || $0.status == .retryable)
                }) {
                issues.append("Deleted branch \(branch.id) still owns active work.")
            }
        }
        for session in document.sessions where
            !document.branches.contains(where: { $0.id == session.branchID && $0.sessionID == session.id }) {
            issues.append("Session \(session.id) has no owning branch.")
        }
    }

    private static func validateSessionInteractions(
        _ session: NovelSessionRecord,
        issues: inout [String]
    ) {
        var askPrompts: [NovelMessageID: NovelAskUserPrompt] = [:]
        var answeredPromptIDs: Set<NovelMessageID> = []
        for message in session.messages {
            switch message.interaction {
            case .askUser(let prompt):
                if message.role != .assistant || message.mode != .discussPlan || message.kind != .discussion {
                    issues.append("Ask User message \(message.id) has an invalid role or mode.")
                }
                do {
                    try NovelGenerationReducer.validateAskUserPrompt(prompt)
                    askPrompts[message.id] = prompt
                } catch {
                    issues.append("Ask User message \(message.id) has an invalid prompt.")
                }
            case .askUserAnswer(let response):
                if message.role != .user || message.mode != .discussPlan || message.kind != .userInput {
                    issues.append("Ask User answer \(message.id) has an invalid role or mode.")
                }
                guard askPrompts[response.promptMessageID] != nil else {
                    issues.append("Ask User answer \(message.id) references a missing or later prompt.")
                    continue
                }
                if !answeredPromptIDs.insert(response.promptMessageID).inserted {
                    issues.append("Ask User prompt \(response.promptMessageID) has multiple answers.")
                }
            case nil:
                break
            }
        }
    }

    private static func validateChaptersAndState(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let operationIDs = Set(document.appliedOperations.map(\.operationID))
        for version in document.chapterVersions {
            if !document.chapters.contains(where: { $0.id == version.chapterID }) {
                issues.append("Chapter version \(version.id) has no chapter.")
            }
            if !operationIDs.contains(version.operationID) {
                issues.append("Chapter version \(version.id) has no applied operation.")
            }
        }
        var eventByID: [NovelEventID: NovelStoryEventRecord] = [:]
        for event in document.events where eventByID[event.id] == nil {
            eventByID[event.id] = event
        }
        let eventSequences = document.events.map(\.sequence)
        if eventSequences.contains(where: { $0 < 0 }) {
            issues.append("Story event sequences must be non-negative.")
        }
        if Set(eventSequences).count != eventSequences.count {
            issues.append("Story event sequences must be project-global and unique.")
        }
        if zip(eventSequences, eventSequences.dropFirst()).contains(where: { $0 >= $1 }) {
            issues.append("Story event history is not strictly increasing.")
        }
        for snapshot in document.stateSnapshots {
            if Set(snapshot.eventIDs).count != snapshot.eventIDs.count {
                issues.append("State snapshot \(snapshot.id) repeats an event.")
            }
            var lastSequence: Int64?
            for eventID in snapshot.eventIDs {
                guard let event = eventByID[eventID] else {
                    issues.append("State snapshot \(snapshot.id) references a missing event.")
                    continue
                }
                if let lastSequence, event.sequence <= lastSequence {
                    issues.append("State snapshot \(snapshot.id) event order is not strictly increasing.")
                }
                lastSequence = event.sequence
            }
            if Set(snapshot.settingProposalIDs).count != snapshot.settingProposalIDs.count {
                issues.append("State snapshot \(snapshot.id) repeats a setting proposal.")
            }
            for proposalID in snapshot.settingProposalIDs where
                !document.settingProposals.contains(where: { $0.id == proposalID }) {
                issues.append("State snapshot \(snapshot.id) references a missing setting proposal.")
            }
            if !document.checkpoints.contains(where: { $0.stateSnapshotID == snapshot.id }) {
                issues.append("State snapshot \(snapshot.id) has no checkpoint reference.")
            }
        }
    }

    private static func validateSettingProposals(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        var quickStartKindCountsByRun: [NovelRunID: [String: Int]] = [:]
        for proposal in document.settingProposals {
            guard case .some(.quickStart(let runID, let suggestedKind)) = proposal.origin else {
                continue
            }
            guard document.activeRuns.contains(where: {
                $0.id == runID &&
                    $0.branchID == proposal.branchID &&
                    $0.kind == .quickStart &&
                    $0.status == .completed
            }) else {
                issues.append("Quick-start proposal \(proposal.id) has no completed source run.")
                continue
            }
            let kindKey: String
            switch suggestedKind {
            case .world: kindKey = "world"
            case .character: kindKey = "character"
            case .masterOutline: kindKey = "masterOutline"
            case .writingRequirements: kindKey = "writingRequirements"
            case .custom:
                issues.append("Quick-start proposal \(proposal.id) suggests an unsupported material kind.")
                continue
            }
            quickStartKindCountsByRun[runID, default: [:]][kindKey, default: 0] += 1
        }
        for run in document.activeRuns where
            run.kind == .quickStart && run.status == .completed {
            let counts = quickStartKindCountsByRun[run.id] ?? [:]
            if counts["world"] != 1 ||
                counts["masterOutline"] != 1 ||
                counts["writingRequirements"] != 1 ||
                (counts["character"] ?? 0) < 1 {
                issues.append(
                    "Quick-start run \(run.id) does not own one fixed proposal per section and at least one character proposal."
                )
            }
        }

        for proposal in document.settingProposals where proposal.isResolved {
            let owners = document.appliedOperations.filter { operation in
                guard operation.kind == .resolveSettingProposal else { return false }
                switch operation.outcome {
                case .settingProposalAccepted(_, let proposalID, _, _, _, _),
                     .settingProposalRejected(_, let proposalID, _, _):
                    return proposalID == proposal.id
                default:
                    return false
                }
            }
            if owners.count != 1 {
                issues.append("Resolved setting proposal \(proposal.id) has no unique resolution operation.")
            }
        }
    }

    private static func validateCheckpoints(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        var checkpointByID: [NovelCheckpointID: NovelBranchCheckpointRecord] = [:]
        for checkpoint in document.checkpoints where checkpointByID[checkpoint.id] == nil {
            checkpointByID[checkpoint.id] = checkpoint
        }
        let operationIDs = Set(document.appliedOperations.map(\.operationID))
        let initialCheckpoints = document.checkpoints.filter { $0.kind == .initial }
        if initialCheckpoints.count != 1 {
            issues.append("Project must have exactly one initial checkpoint.")
        }

        for checkpoint in document.checkpoints {
            if checkpoint.kind == .initial && checkpoint.parentCheckpointID != nil {
                issues.append("Initial checkpoint \(checkpoint.id) has a parent.")
            }
            if checkpoint.baseHeadRevision < 0 {
                issues.append("Checkpoint \(checkpoint.id) has a negative base head revision.")
            }
            if checkpoint.kind == .initial && checkpoint.baseHeadRevision != 0 {
                issues.append("Initial checkpoint \(checkpoint.id) has a nonzero base head revision.")
            }
            if checkpoint.kind != .initial && checkpoint.parentCheckpointID == nil {
                issues.append("Checkpoint \(checkpoint.id) has no parent.")
            }
            if let parent = checkpoint.parentCheckpointID, checkpointByID[parent] == nil {
                issues.append("Checkpoint \(checkpoint.id) has a missing parent.")
            }
            guard let sourceBranch = document.branches.first(where: {
                $0.id == checkpoint.createdOnBranchID
            }), let session = document.sessions.first(where: { $0.id == sourceBranch.sessionID }) else {
                issues.append("Checkpoint \(checkpoint.id) has no source branch Session.")
                continue
            }
            if case .through(let sequence) = checkpoint.sessionCursor,
               !session.messages.contains(where: { $0.sequence == sequence }) {
                issues.append("Checkpoint \(checkpoint.id) has an invalid Session cursor.")
            }
            if !document.stateSnapshots.contains(where: { $0.id == checkpoint.stateSnapshotID }) {
                issues.append("Checkpoint \(checkpoint.id) has a missing state snapshot.")
            }
            if Set(checkpoint.chapterSelections.map(\.chapterID)).count != checkpoint.chapterSelections.count {
                issues.append("Checkpoint \(checkpoint.id) repeats a chapter.")
            }
            for selection in checkpoint.chapterSelections {
                if !document.chapterVersions.contains(where: {
                    $0.id == selection.versionID && $0.chapterID == selection.chapterID
                }) {
                    issues.append("Checkpoint \(checkpoint.id) has an invalid chapter selection.")
                }
            }
            for revisionID in checkpoint.branchOverrideRevisionIDs where
                !document.materialRevisions.contains(where: { $0.id == revisionID }) {
                issues.append("Checkpoint \(checkpoint.id) has a missing override revision.")
            }
            let overrideMaterialIDs = checkpoint.branchOverrideRevisionIDs.compactMap { revisionID in
                document.materialRevisions.first(where: { $0.id == revisionID })?.materialID
            }
            if Set(checkpoint.branchOverrideRevisionIDs).count !=
                checkpoint.branchOverrideRevisionIDs.count ||
                Set(overrideMaterialIDs).count != overrideMaterialIDs.count {
                issues.append("Checkpoint \(checkpoint.id) repeats a material override.")
            }
            if !operationIDs.contains(checkpoint.operationID) {
                issues.append("Checkpoint \(checkpoint.id) has no applied operation.")
            }
            switch checkpoint.kind {
            case .collection, .polish:
                guard let candidateID = checkpoint.sourceCandidateID,
                      let candidate = document.candidates.first(where: { $0.id == candidateID }) else {
                    issues.append("Checkpoint \(checkpoint.id) has no source candidate.")
                    continue
                }
                let baseMatches: Bool
                if checkpoint.kind == .collection, let parentID = checkpoint.parentCheckpointID {
                    baseMatches = NovelCandidateSemantics.collectionBaseMatches(
                        candidate,
                        targetCheckpointID: parentID,
                        targetHeadRevision: checkpoint.baseHeadRevision,
                        in: document
                    )
                } else {
                    baseMatches = candidate.baseCheckpointID == checkpoint.parentCheckpointID &&
                        candidate.baseHeadRevision == checkpoint.baseHeadRevision
                }
                if candidate.branchID != checkpoint.createdOnBranchID ||
                    candidate.sessionID != sourceBranch.sessionID ||
                    !baseMatches {
                    issues.append("Checkpoint \(checkpoint.id) crosses its source candidate branch or base.")
                }
                guard let sourceMessage = session.messages.first(where: {
                    $0.id == candidate.sourceMessageID
                }) else {
                    continue
                }
                if checkpoint.sessionCursor != .through(sequence: sourceMessage.sequence) {
                    issues.append("Checkpoint \(checkpoint.id) cursor does not match its source candidate.")
                }
                if checkpoint.kind == .collection && candidate.kind != .prose {
                    issues.append("Collection checkpoint \(checkpoint.id) has a non-prose candidate.")
                }
                if checkpoint.kind == .polish && candidate.kind != .polish {
                    issues.append("Polish checkpoint \(checkpoint.id) has a non-polish candidate.")
                }
            case .initial, .manualSync, .restore:
                if checkpoint.sourceCandidateID != nil {
                    issues.append("Checkpoint \(checkpoint.id) unexpectedly has a source candidate.")
                }
            }
            if checkpoint.kind == .polish || checkpoint.kind == .restore,
               let parentID = checkpoint.parentCheckpointID,
               let parent = checkpointByID[parentID] {
                NovelPolishDocumentValidator.validateFactPreservingCheckpoint(
                    checkpoint,
                    parent: parent,
                    document: document,
                    issues: &issues
                )
            }
        }

        for checkpoint in document.checkpoints {
            var visited: Set<NovelCheckpointID> = []
            var current: NovelBranchCheckpointRecord? = checkpoint
            while let node = current {
                guard visited.insert(node.id).inserted else {
                    issues.append("Checkpoint graph contains a cycle at \(node.id).")
                    break
                }
                current = node.parentCheckpointID.flatMap { checkpointByID[$0] }
            }
        }
        validateCheckpointLineage(
            document,
            checkpointByID: checkpointByID,
            initialCheckpoint: initialCheckpoints.first,
            issues: &issues
        )
    }

    private static func validateCandidates(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let checkpointByID = Dictionary(
            document.checkpoints.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let candidateByID = Dictionary(
            document.candidates.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let initialCheckpoint = document.checkpoints.first(where: { $0.kind == .initial })

        for branch in document.branches {
            guard let session = document.sessions.first(where: { $0.id == branch.sessionID }) else {
                continue
            }
            for message in session.messages {
                guard let candidateID = message.candidateID else { continue }
                guard let candidate = candidateByID[candidateID],
                      candidate.clonedFromCandidateID == nil,
                      candidate.branchID == branch.id,
                      candidate.sessionID == session.id,
                      candidate.sourceMessageID == message.id else {
                    issues.append("Session message \(message.id) has an invalid candidate reference.")
                    continue
                }
            }
        }

        for candidate in document.candidates {
            guard let session = document.sessions.first(where: { $0.id == candidate.sessionID }),
                  session.branchID == candidate.branchID else {
                issues.append("Candidate \(candidate.id) has an invalid branch Session.")
                continue
            }
            guard let message = session.messages.first(where: { $0.id == candidate.sourceMessageID }) else {
                issues.append("Candidate \(candidate.id) has no source message.")
                continue
            }
            if message.candidateID != NovelCandidateSemantics.rootCandidateID(
                for: candidate,
                candidatesByID: candidateByID
            ) {
                issues.append("Candidate \(candidate.id) and source message disagree.")
            }
            if let sourceID = candidate.clonedFromCandidateID {
                guard let source = candidateByID[sourceID],
                      source.id != candidate.id,
                      source.branchID == candidate.branchID,
                      source.sessionID == candidate.sessionID,
                      source.sourceMessageID == candidate.sourceMessageID,
                      source.kind == candidate.kind,
                      source.content == candidate.content,
                      source.sourceChapterVersionID == candidate.sourceChapterVersionID,
                      NovelCandidateSemantics.cloneBaseMatches(
                          source,
                          currentCheckpointID: candidate.baseCheckpointID,
                          in: document
                      ),
                      source.createdAt <= candidate.createdAt,
                      source.collectedCheckpointID != nil,
                      candidate.status != .inheritedReadOnly else {
                    issues.append("Candidate \(candidate.id) has invalid clone provenance.")
                    continue
                }
                let cloneOwners = document.appliedOperations.filter { operation in
                    guard operation.kind == .cloneCandidate,
                          case let .candidateCloned(
                              _, branchID, sourceCandidateID, candidateID, _
                          ) = operation.outcome else {
                        return false
                    }
                    return branchID == candidate.branchID &&
                        sourceCandidateID == sourceID &&
                        candidateID == candidate.id
                }
                if cloneOwners.count != 1 {
                    issues.append("Candidate \(candidate.id) has no unique clone operation.")
                }
            }
            if !document.checkpoints.contains(where: { $0.id == candidate.baseCheckpointID }) {
                issues.append("Candidate \(candidate.id) has a missing base checkpoint.")
            }
            if candidate.status != .inheritedReadOnly,
               checkpointByID[candidate.baseCheckpointID] != nil,
               let initialCheckpoint,
               let branch = document.branches.first(where: { $0.id == candidate.branchID }) {
                let boundaryID = branch.forkOrigin?.checkpointID ?? initialCheckpoint.id
                if !checkpoint(
                    candidate.baseCheckpointID,
                    belongsTo: branch,
                    boundaryID: boundaryID,
                    checkpointByID: checkpointByID
                ) {
                    issues.append("Candidate \(candidate.id) base is outside its branch lineage.")
                }
            }
            if candidate.baseHeadRevision < 0 {
                issues.append("Candidate \(candidate.id) has a negative base head revision.")
            }
            if message.role != .assistant || message.content != candidate.content {
                issues.append("Candidate \(candidate.id) source message has invalid role or content.")
            }
            if candidate.kind == .prose && message.kind != .proseCandidate {
                issues.append("Prose candidate \(candidate.id) has the wrong message kind.")
            }
            if candidate.kind == .polish && message.kind != .polishCandidate {
                issues.append("Polish candidate \(candidate.id) has the wrong message kind.")
            }
            if let versionID = candidate.sourceChapterVersionID,
               !document.chapterVersions.contains(where: { $0.id == versionID }) {
                issues.append("Candidate \(candidate.id) has a missing source chapter version.")
            }
            if let checkpointID = candidate.collectedCheckpointID {
                guard let checkpoint = document.checkpoints.first(where: { $0.id == checkpointID }) else {
                    issues.append("Candidate \(candidate.id) has a missing collected checkpoint.")
                    continue
                }
                if checkpoint.sourceCandidateID != candidate.id {
                    issues.append("Candidate \(candidate.id) and collected checkpoint disagree.")
                }
                let baseMatches: Bool
                if checkpoint.kind == .collection, let parentID = checkpoint.parentCheckpointID {
                    baseMatches = NovelCandidateSemantics.collectionBaseMatches(
                        candidate,
                        targetCheckpointID: parentID,
                        targetHeadRevision: checkpoint.baseHeadRevision,
                        in: document
                    )
                } else {
                    baseMatches = checkpoint.parentCheckpointID == candidate.baseCheckpointID &&
                        checkpoint.baseHeadRevision == candidate.baseHeadRevision
                }
                if checkpoint.createdOnBranchID != candidate.branchID || !baseMatches {
                    issues.append("Candidate \(candidate.id) was collected across a branch or base.")
                }
            } else if candidate.status == .collected || candidate.status == .adopted {
                issues.append("Collected candidate \(candidate.id) has no checkpoint.")
            }
        }
        for checkpoint in document.checkpoints {
            guard let candidateID = checkpoint.sourceCandidateID,
                  let candidate = document.candidates.first(where: { $0.id == candidateID }) else {
                continue
            }
            if candidate.collectedCheckpointID != checkpoint.id {
                issues.append("Checkpoint \(checkpoint.id) and source candidate disagree.")
            }
        }
    }

    private static func validateRunsAndPendingOperations(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let receiptIDs = Set(document.generationReceipts.map(\.id))
        for run in document.activeRuns {
            guard let branch = document.branches.first(where: { $0.id == run.branchID }),
                  let session = document.sessions.first(where: { $0.id == run.sessionID }) else {
                issues.append("Run \(run.id) has an invalid branch or Session.")
                continue
            }
            if session.id != branch.sessionID {
                issues.append("Run \(run.id) crosses branch Session ownership.")
            }
            if !receiptIDs.contains(run.receiptID) {
                issues.append("Run \(run.id) has no generation receipt.")
            }
            if run.status == .running && branch.activeRunID != run.id {
                issues.append("Running run \(run.id) is not the branch active run.")
            }
        }
        let runningGroups = Dictionary(grouping: document.activeRuns.filter {
            $0.status == .running
        }, by: \.sessionID)
        if runningGroups.values.contains(where: { $0.count > 1 }) {
            issues.append("A creation Session has multiple running runs.")
        }
        for branch in document.branches {
            guard let runID = branch.activeRunID else { continue }
            if !document.activeRuns.contains(where: {
                $0.id == runID && $0.branchID == branch.id && $0.sessionID == branch.sessionID && $0.status == .running
            }) {
                issues.append("Branch \(branch.id) active run reference is invalid.")
            }
        }

        let formalVersionIDs = Set(document.chapterVersions.map(\.id))
        let pendingOperationIDs = document.pendingOperations.map(\.operationID)
        if Set(pendingOperationIDs).count != pendingOperationIDs.count {
            issues.append("Pending operations repeat an operation ID.")
        }
        let appliedOperationIDs = Set(document.appliedOperations.map(\.operationID))
        if !Set(pendingOperationIDs).isDisjoint(with: appliedOperationIDs) {
            issues.append("A pending operation is already present in the applied ledger.")
        }
        let factAttemptOperationIDs = Set(document.factAttempts.map(\.attemptOperationID))
        if !Set(pendingOperationIDs).isDisjoint(with: factAttemptOperationIDs) {
            issues.append("A pending owner operation reuses a progress-attempt operation ID.")
        }
        let pendingBranchIDs = document.pendingOperations.map(\.branchID)
        if Set(pendingBranchIDs).count != pendingBranchIDs.count {
            issues.append("A branch has multiple pending fact transactions.")
        }
        let collectionPendings = document.pendingOperations.filter { $0.kind == .collection }
        let pendingCandidateIDs = collectionPendings.compactMap(\.candidateID)
        if Set(pendingCandidateIDs).count != pendingCandidateIDs.count {
            issues.append("A candidate has multiple pending collection operations.")
        }
        let proposedVersionIDs = collectionPendings.compactMap { $0.proposedChapterVersion?.id }
        if Set(proposedVersionIDs).count != proposedVersionIDs.count {
            issues.append("Pending collections repeat a proposed chapter version ID.")
        }
        let proposedCheckpointIDs = document.pendingOperations.compactMap(\.proposedCheckpointID)
        if Set(proposedCheckpointIDs).count != proposedCheckpointIDs.count {
            issues.append("Pending operations repeat a proposed checkpoint ID.")
        }
        let proposedStateSnapshotIDs = document.pendingOperations.compactMap(\.proposedStateSnapshotID)
        if Set(proposedStateSnapshotIDs).count != proposedStateSnapshotIDs.count {
            issues.append("Pending operations repeat a proposed state snapshot ID.")
        }
        let formalCheckpointIDs = Set(document.checkpoints.map(\.id))
        if !Set(proposedCheckpointIDs).isDisjoint(with: formalCheckpointIDs) {
            issues.append("A pending operation leaked its proposed checkpoint into formal history.")
        }
        let formalStateSnapshotIDs = Set(document.stateSnapshots.map(\.id))
        if !Set(proposedStateSnapshotIDs).isDisjoint(with: formalStateSnapshotIDs) {
            issues.append("A pending operation leaked its proposed state snapshot into formal history.")
        }
        for pending in document.pendingOperations {
            NovelManualSyncProgressValidator.validate(
                pending: pending,
                document: document,
                issues: &issues
            )
            guard let branch = document.branches.first(where: { $0.id == pending.branchID }) else {
                issues.append("Pending operation \(pending.id) has no branch.")
                continue
            }
            if !isSHA256(pending.payloadSHA256) {
                issues.append("Pending operation \(pending.id) has an invalid payload hash.")
            }
            if pending.baseHeadRevision < 0 || pending.baseWorkingRevision < 0 {
                issues.append("Pending operation \(pending.id) has a negative base revision.")
            }
            if !document.checkpoints.contains(where: { $0.id == pending.baseCheckpointID }) {
                issues.append("Pending operation \(pending.id) has a missing base checkpoint.")
            }
            if pending.selectedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                issues.append("Pending operation \(pending.id) has no durable selected manuscript.")
            }
            switch pending.status {
            case .pending where pending.lastError != nil:
                issues.append("Pending operation \(pending.id) has an error before becoming retryable.")
            case .retryable:
                if (pending.lastError ?? "")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .isEmpty {
                    issues.append("Retryable pending operation \(pending.id) has no failure message.")
                }
            default:
                break
            }
            if pending.proposedCheckpointID == nil || pending.proposedStateSnapshotID == nil {
                issues.append("Pending operation \(pending.id) has no reserved final record IDs.")
            }
            if let proposed = pending.proposedChapterVersion,
               formalVersionIDs.contains(proposed.id) {
                issues.append("Pending operation \(pending.id) leaked its proposed version into formal history.")
            }
            switch pending.kind {
            case .collection:
                guard let candidateID = pending.candidateID,
                      let candidate = document.candidates.first(where: { $0.id == candidateID }),
                      candidate.branchID == pending.branchID else {
                    issues.append("Collection pending operation \(pending.id) has no source candidate.")
                    continue
                }
                guard let target = pending.collectionTarget,
                      let proposed = pending.proposedChapterVersion else {
                    issues.append("Collection pending operation \(pending.id) has no durable chapter target.")
                    continue
                }
                if branch.headCheckpointID != pending.baseCheckpointID ||
                    branch.headRevision != pending.baseHeadRevision ||
                    branch.workingRevision != pending.baseWorkingRevision ||
                    branch.syncStatus != .synchronized {
                    issues.append("Collection pending operation \(pending.id) is stale for its branch head.")
                }
                if pending.rebuildBaseCheckpointID != nil {
                    issues.append("Collection pending operation \(pending.id) contains a rebuild base.")
                }
                if !NovelCandidateSemantics.collectionBaseMatches(
                    candidate,
                    targetCheckpointID: pending.baseCheckpointID,
                    targetHeadRevision: pending.baseHeadRevision,
                    in: document
                ) {
                    issues.append("Pending operation \(pending.id) does not match its candidate base guard.")
                }
                if candidate.kind != .prose ||
                    candidate.status != .available ||
                    candidate.collectedCheckpointID != nil {
                    issues.append("Pending operation \(pending.id) source candidate is not collectable prose.")
                }
                if proposed.sourceCandidateID != candidate.id ||
                    proposed.operationID != pending.operationID ||
                    proposed.kind != .collected {
                    issues.append("Pending operation \(pending.id) proposed version does not match its source.")
                }
                if let session = document.sessions.first(where: { $0.id == candidate.sessionID }),
                   let message = session.messages.first(where: { $0.id == candidate.sourceMessageID }),
                   pending.sessionCursor != .through(sequence: message.sequence) {
                    issues.append("Collection pending operation \(pending.id) cursor does not match its candidate.")
                }
                switch target {
                case .appendToChapter(let chapterID):
                    guard let checkpoint = document.checkpoints.first(where: {
                        $0.id == pending.baseCheckpointID
                    }), let selection = checkpoint.chapterSelections.first(where: {
                        $0.chapterID == chapterID
                    }), let baseVersion = document.chapterVersions.first(where: {
                        $0.id == selection.versionID
                    }) else {
                        issues.append("Pending operation \(pending.id) has an invalid append target.")
                        continue
                    }
                    let expectedContent = NovelChapterText.appending(
                        pending.selectedText,
                        to: baseVersion.content
                    )
                    if proposed.chapterID != chapterID ||
                        proposed.title != baseVersion.title ||
                        proposed.content != expectedContent {
                        issues.append("Pending operation \(pending.id) has an invalid appended chapter version.")
                    }
                case .createNextChapter(let chapterID, let title):
                    if proposed.chapterID != chapterID ||
                        proposed.title != title ||
                        proposed.content != pending.selectedText ||
                        document.chapters.contains(where: { $0.id == chapterID }) {
                        issues.append("Pending operation \(pending.id) has an invalid next-chapter target.")
                    }
                }
            case .manualSync:
                if pending.candidateID != nil ||
                    pending.collectionTarget != nil ||
                    pending.proposedChapterVersion != nil {
                    issues.append("Manual-sync pending operation \(pending.id) contains collection payload.")
                }
                if branch.headCheckpointID != pending.baseCheckpointID ||
                    branch.headRevision != pending.baseHeadRevision ||
                    branch.workingRevision != pending.baseWorkingRevision ||
                    branch.syncStatus != .needsSync {
                    issues.append("Manual-sync pending operation \(pending.id) is stale for its working branch.")
                }
                guard let rebuildBaseID = pending.rebuildBaseCheckpointID,
                      document.checkpoints.contains(where: { $0.id == rebuildBaseID }) else {
                    issues.append("Manual-sync pending operation \(pending.id) has no rebuild base checkpoint.")
                    continue
                }
                if !checkpointIsAncestorOrSelf(
                    rebuildBaseID,
                    of: pending.baseCheckpointID,
                    in: document
                ) {
                    issues.append("Manual-sync pending operation \(pending.id) rebuild base is outside its head lineage.")
                }
                if pending.sessionCursor == nil {
                    issues.append("Manual-sync pending operation \(pending.id) has no sync-start cursor.")
                } else if let session = document.sessions.first(where: { $0.id == branch.sessionID }),
                          case .through(let sequence) = pending.sessionCursor,
                          !session.messages.contains(where: { $0.sequence == sequence }) {
                    issues.append("Manual-sync pending operation \(pending.id) has an invalid sync-start cursor.")
                }
                do {
                    let chapters = try NovelFactTransactionReducer.decodeManualPayload(
                        pending.selectedText
                    )
                    try NovelFactTransactionReducer.validateManualPayload(
                        chapters,
                        pending: pending,
                        in: document
                    )
                } catch {
                    issues.append(
                        "Manual-sync pending operation \(pending.id) has an invalid durable rebuild payload."
                    )
                }
            }
        }
    }

    private static func checkpointIsAncestorOrSelf(
        _ ancestorID: NovelCheckpointID,
        of descendantID: NovelCheckpointID,
        in document: NovelProjectDocumentV1
    ) -> Bool {
        var checkpoints: [NovelCheckpointID: NovelBranchCheckpointRecord] = [:]
        for checkpoint in document.checkpoints where checkpoints[checkpoint.id] == nil {
            checkpoints[checkpoint.id] = checkpoint
        }
        var currentID: NovelCheckpointID? = descendantID
        var visited: Set<NovelCheckpointID> = []
        while let candidateID = currentID, visited.insert(candidateID).inserted {
            if candidateID == ancestorID { return true }
            currentID = checkpoints[candidateID]?.parentCheckpointID
        }
        return false
    }

    private static func validateOperationLedger(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let createOperations = document.appliedOperations.filter { $0.kind == .createProject }
        if createOperations.count != 1 || document.appliedOperations.first?.kind != .createProject {
            issues.append("Operation ledger must begin with exactly one project creation.")
        }
        for pair in zip(document.appliedOperations, document.appliedOperations.dropFirst())
        where pair.0.appliedProjectRevision > pair.1.appliedProjectRevision {
            issues.append("Operation ledger revisions are not monotonic.")
        }
        for operation in document.appliedOperations {
            if !isSHA256(operation.payloadSHA256) {
                issues.append("Operation \(operation.operationID) has an invalid payload hash.")
            }
            if operation.appliedProjectRevision < 1 ||
                operation.appliedProjectRevision > document.project.revision {
                issues.append("Operation \(operation.operationID) has an invalid applied revision.")
            }
            if operation.outcome.projectID != document.project.id {
                issues.append("Operation \(operation.operationID) outcome belongs to another project.")
            }
            switch (operation.kind, operation.outcome) {
            case let (.createProject, .projectCreated(projectID, branchID)):
                if projectID != document.project.id ||
                    operation.appliedProjectRevision != 1 ||
                    !document.branches.contains(where: { $0.id == branchID }) {
                    issues.append("Project creation operation \(operation.operationID) has invalid outcome data.")
                }
            case let (.renameProject, .projectRenamed(_, revision)):
                if revision != operation.appliedProjectRevision {
                    issues.append("Project rename operation \(operation.operationID) has an invalid revision.")
                }
            case let (
                .reviseMaterial,
                .materialRevised(_, materialID, revisionID, projectRevision, configRevision)
            ):
                if projectRevision != operation.appliedProjectRevision ||
                    configRevision < 1 ||
                    configRevision > document.project.configRevision ||
                    !document.materialRevisions.contains(where: {
                        $0.id == revisionID &&
                            $0.materialID == materialID &&
                            $0.operationID == operation.operationID
                    }) {
                    issues.append("Material operation \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .deleteMaterial,
                .materialDeleted(_, materialID, projectRevision, configRevision)
            ):
                if projectRevision != operation.appliedProjectRevision ||
                    configRevision < 1 ||
                    configRevision > document.project.configRevision ||
                    !document.materials.contains(where: {
                        $0.id == materialID && $0.isDeleted
                    }) {
                    issues.append("Material deletion \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .setModelPolicy,
                .modelPolicyChanged(_, projectRevision, configRevision)
            ):
                if projectRevision != operation.appliedProjectRevision ||
                    configRevision < 1 ||
                    configRevision > document.project.configRevision {
                    issues.append("Model-policy operation \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .resolveSettingProposal,
                .settingProposalAccepted(
                    _, proposalID, materialID, revisionID, projectRevision, configRevision
                )
            ):
                if projectRevision != operation.appliedProjectRevision ||
                    configRevision < 1 ||
                    configRevision > document.project.configRevision ||
                    !document.settingProposals.contains(where: {
                        $0.id == proposalID && $0.isResolved
                    }) ||
                    !document.materialRevisions.contains(where: {
                        $0.id == revisionID &&
                            $0.materialID == materialID &&
                            $0.operationID == operation.operationID
                    }) {
                    issues.append("Proposal acceptance \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .resolveSettingProposal,
                .settingProposalRejected(_, proposalID, projectRevision, configRevision)
            ):
                if projectRevision != operation.appliedProjectRevision ||
                    configRevision < 1 ||
                    configRevision > document.project.configRevision ||
                    !document.settingProposals.contains(where: {
                        $0.id == proposalID && $0.isResolved
                    }) {
                    issues.append("Proposal rejection \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .setBranchMaterialOverride,
                .branchMaterialOverrideChanged(
                    _, branchID, materialID, revisionID, projectRevision, configRevision
                )
            ):
                let selectedRevision = revisionID.flatMap { selectedID in
                    document.materialRevisions.first(where: {
                        $0.id == selectedID && $0.materialID == materialID
                    })
                }
                if projectRevision != operation.appliedProjectRevision ||
                    configRevision < 1 ||
                    configRevision > document.project.configRevision ||
                    !document.branches.contains(where: { $0.id == branchID }) ||
                    (revisionID != nil && selectedRevision == nil) {
                    issues.append("Branch override \(operation.operationID) has invalid outcome data.")
                }
            case let (.setMainBranch, .mainBranchChanged(_, branchID, revision)):
                if revision != operation.appliedProjectRevision ||
                    !document.branches.contains(where: { $0.id == branchID }) {
                    issues.append("Main-branch operation \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .setPolishPreference,
                .polishPreferenceChanged(_, projectRevision, configRevision)
            ):
                if projectRevision != operation.appliedProjectRevision ||
                    configRevision < 1 ||
                    configRevision > document.project.configRevision {
                    issues.append(
                        "Polish-preference operation \(operation.operationID) has invalid outcome data."
                    )
                }
            case let (
                .forkBranch,
                .branchForked(_, sourceBranchID, branchID, checkpointID, revision)
            ):
                if revision != operation.appliedProjectRevision ||
                    !document.branches.contains(where: {
                        $0.id == branchID &&
                            $0.forkOrigin == NovelForkOrigin(
                                parentBranchID: sourceBranchID,
                                checkpointID: checkpointID
                            )
                    }) {
                    issues.append("Fork operation \(operation.operationID) has invalid outcome data.")
                }
            case let (.renameBranch, .branchRenamed(_, branchID, revision)):
                if revision != operation.appliedProjectRevision ||
                    !document.branches.contains(where: { $0.id == branchID }) {
                    issues.append("Branch rename operation \(operation.operationID) has invalid outcome data.")
                }
            case let (.deleteBranch, .branchDeleted(_, branchID, revision)):
                if revision != operation.appliedProjectRevision ||
                    !document.branches.contains(where: {
                        $0.id == branchID && $0.lifecycle == .deleted
                    }) {
                    issues.append("Branch deletion operation \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .undoBranchHead,
                .branchHeadMoved(
                    _, branchID, fromCheckpointID, toCheckpointID, headRevision, revision
                )
            ):
                let from = document.checkpoints.first(where: { $0.id == fromCheckpointID })
                let branch = document.branches.first(where: { $0.id == branchID })
                let directParent = from.flatMap { checkpoint in
                    checkpoint.parentCheckpointID.flatMap { parentID in
                        document.checkpoints.first(where: { $0.id == parentID })
                    }
                }
                let expectedTarget = from.flatMap { checkpoint in
                    branch.flatMap {
                        NovelBranchSemantics.undoTarget(
                            for: checkpoint,
                            branch: $0,
                            checkpoints: document.checkpoints
                        )
                    }
                }
                let validTargetIDs = Set([directParent?.id, expectedTarget?.id].compactMap { $0 })
                if revision != operation.appliedProjectRevision ||
                    !validTargetIDs.contains(toCheckpointID) ||
                    (branch?.headRevision ?? -1) < headRevision {
                    issues.append("Branch undo operation \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .cloneCandidate,
                .candidateCloned(_, branchID, sourceCandidateID, candidateID, revision)
            ):
                if revision != operation.appliedProjectRevision ||
                    !document.candidates.contains(where: {
                        $0.id == candidateID &&
                            $0.branchID == branchID &&
                            $0.clonedFromCandidateID == sourceCandidateID
                    }) {
                    issues.append("Candidate clone operation \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .adoptPolishCandidate,
                .polishCandidateAdopted(
                    _, branchID, candidateID, checkpointID, chapterVersionID, revision
                )
            ):
                let transaction = document.polishTransactions.first(where: {
                    $0.operationID == operation.operationID &&
                        $0.candidateID == candidateID &&
                        $0.checkpointID == checkpointID &&
                        $0.proposedChapterVersionID == chapterVersionID &&
                        $0.status == .completed
                })
                if revision != operation.appliedProjectRevision ||
                    transaction?.branchID != branchID ||
                    !document.checkpoints.contains(where: {
                        $0.id == checkpointID &&
                            $0.kind == .polish &&
                            $0.operationID == operation.operationID
                    }) ||
                    !document.chapterVersions.contains(where: {
                        $0.id == chapterVersionID &&
                            $0.kind == .polish &&
                            $0.operationID == operation.operationID
                    }) {
                    issues.append("Polish adoption \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .adoptPolishCandidate,
                .polishCandidateRejected(_, branchID, candidateID, transactionID, revision)
            ):
                if revision != operation.appliedProjectRevision ||
                    !document.polishTransactions.contains(where: {
                        $0.id == transactionID &&
                            $0.operationID == operation.operationID &&
                            $0.branchID == branchID &&
                            $0.candidateID == candidateID &&
                            $0.status == .incompatible
                    }) {
                    issues.append("Polish rejection \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .abandonPolishTransaction,
                .polishTransactionAbandoned(
                    _, branchID, candidateID, transactionID, revision
                )
            ):
                if revision != operation.appliedProjectRevision ||
                    !document.polishTransactions.contains(where: {
                        $0.id == transactionID &&
                            $0.branchID == branchID &&
                            $0.candidateID == candidateID &&
                            $0.status == .abandoned
                    }) ||
                    !document.candidates.contains(where: {
                        $0.id == candidateID &&
                            $0.branchID == branchID &&
                            $0.status == .superseded
                    }) {
                    issues.append("Polish abandonment \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .restoreChapterVersion,
                .chapterVersionRestored(_, branchID, checkpointID, chapterVersionID, revision)
            ):
                if revision != operation.appliedProjectRevision ||
                    !document.checkpoints.contains(where: {
                        $0.id == checkpointID &&
                            $0.kind == .restore &&
                            $0.createdOnBranchID == branchID &&
                            $0.operationID == operation.operationID
                    }) ||
                    !document.chapterVersions.contains(where: {
                        $0.id == chapterVersionID &&
                            $0.kind == .restore &&
                            $0.operationID == operation.operationID
                    }) {
                    issues.append("Chapter restore \(operation.operationID) has invalid outcome data.")
                }
            case let (.startRun, .runStarted(_, branchID, runID, receiptID, revision)):
                if revision != operation.appliedProjectRevision ||
                    !document.activeRuns.contains(where: {
                        $0.id == runID &&
                            $0.branchID == branchID &&
                            $0.receiptID == receiptID &&
                            $0.operationID == operation.operationID
                    }) {
                    issues.append("Run-start operation \(operation.operationID) has invalid outcome data.")
                }
            case let (.cancelRun, .runInterrupted(_, runID, reason, revision)):
                if revision != operation.appliedProjectRevision ||
                    !document.activeRuns.contains(where: {
                        $0.id == runID &&
                            $0.status == .interrupted &&
                            $0.interruptionReason == reason
                }) {
                    issues.append("Run-cancel operation \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .collectCandidate,
                .candidateCollected(_, branchID, candidateID, checkpointID, chapterVersionID, revision)
            ):
                validateCollectionOutcome(
                    operation: operation,
                    branchID: branchID,
                    candidateID: candidateID,
                    checkpointID: checkpointID,
                    chapterVersionID: chapterVersionID,
                    revision: revision,
                    requiresMatchingOperationID: true,
                    document: document,
                    issues: &issues
                )
            case let (
                .saveManualEdit,
                .manualEditSaved(_, branchID, chapterVersionID, workingRevision, revision)
            ):
                if revision != operation.appliedProjectRevision ||
                    workingRevision < 1 ||
                    !document.branches.contains(where: {
                        $0.id == branchID && $0.workingRevision >= workingRevision
                    }) ||
                    !document.chapterVersions.contains(where: {
                        $0.id == chapterVersionID &&
                            $0.kind == .manualEdit &&
                            $0.operationID == operation.operationID
                    }) {
                    issues.append("Manual-edit operation \(operation.operationID) has invalid outcome data.")
                }
            case let (
                .syncManualEdits,
                .manualSyncCommitted(_, branchID, checkpointID, revision)
            ):
                validateManualSyncOutcome(
                    operation: operation,
                    branchID: branchID,
                    checkpointID: checkpointID,
                    revision: revision,
                    requiresMatchingOperationID: true,
                    document: document,
                    issues: &issues
                )
            case let (
                .retryPending,
                .candidateCollected(_, branchID, candidateID, checkpointID, chapterVersionID, revision)
            ):
                validateCollectionOutcome(
                    operation: operation,
                    branchID: branchID,
                    candidateID: candidateID,
                    checkpointID: checkpointID,
                    chapterVersionID: chapterVersionID,
                    revision: revision,
                    requiresMatchingOperationID: false,
                    document: document,
                    issues: &issues
                )
            case let (
                .retryPending,
                .manualSyncCommitted(_, branchID, checkpointID, revision)
            ):
                validateManualSyncOutcome(
                    operation: operation,
                    branchID: branchID,
                    checkpointID: checkpointID,
                    revision: revision,
                    requiresMatchingOperationID: false,
                    document: document,
                    issues: &issues
                )
            default:
                issues.append("Operation \(operation.operationID) kind does not match its outcome.")
            }
        }
        validateBranchOperationOrder(document, issues: &issues)
        validateLatestBranchOverrides(document, issues: &issues)
    }

    private static func validateLatestBranchOverrides(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        struct Key: Hashable {
            let branchID: NovelBranchID
            let materialID: NovelMaterialID
        }
        var latest: [Key: NovelMaterialRevisionID?] = [:]
        for operation in document.appliedOperations {
            guard operation.kind == .setBranchMaterialOverride,
                  case let .branchMaterialOverrideChanged(
                      _, branchID, materialID, revisionID, _, _
                  ) = operation.outcome else {
                continue
            }
            latest[Key(branchID: branchID, materialID: materialID)] = .some(revisionID)
        }
        for (key, expectedRevisionID) in latest {
            guard let branch = document.branches.first(where: { $0.id == key.branchID }) else {
                continue
            }
            let actual = branch.overrideRevisionIDs.filter { revisionID in
                document.materialRevisions.contains(where: {
                    $0.id == revisionID && $0.materialID == key.materialID
                })
            }
            let expected = expectedRevisionID.map { [$0] } ?? []
            if actual != expected {
                issues.append(
                    "Branch \(key.branchID) does not match its latest override operation for material \(key.materialID)."
                )
            }
        }
    }

    private static func validateBranchOperationOrder(
        _ document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let operationIndex = Dictionary(
            document.appliedOperations.enumerated().map {
                ($0.element.operationID, $0.offset)
            },
            uniquingKeysWith: { first, _ in first }
        )
        for operation in document.appliedOperations {
            let created = document.checkpoints.filter {
                $0.kind != .initial && $0.operationID == operation.operationID
            }
            if created.count > 1 {
                issues.append("Operation \(operation.operationID) creates multiple checkpoints.")
            }
        }

        for branch in document.branches {
            guard let origin = branch.forkOrigin else { continue }
            let forkOwners = document.appliedOperations.enumerated().filter { _, operation in
                guard operation.kind == .forkBranch,
                      case let .branchForked(
                          _, sourceBranchID, branchID, checkpointID, _
                      ) = operation.outcome else {
                    return false
                }
                return sourceBranchID == origin.parentBranchID &&
                    branchID == branch.id &&
                    checkpointID == origin.checkpointID
            }
            guard let forkIndex = forkOwners.first?.offset else { continue }
            if let originCheckpoint = document.checkpoints.first(where: {
                $0.id == origin.checkpointID
            }), let originOwnerIndex = operationIndex[originCheckpoint.operationID],
               forkIndex <= originOwnerIndex {
                issues.append("Branch \(branch.id) forks before its origin checkpoint exists.")
            }
            if let parent = document.branches.first(where: { $0.id == origin.parentBranchID }),
               let parentOrigin = parent.forkOrigin {
                let parentForkIndex = document.appliedOperations.firstIndex { operation in
                    guard operation.kind == .forkBranch,
                          case let .branchForked(_, _, branchID, checkpointID, _) = operation.outcome else {
                        return false
                    }
                    return branchID == parent.id && checkpointID == parentOrigin.checkpointID
                }
                if let parentForkIndex, forkIndex <= parentForkIndex {
                    issues.append("Branch \(branch.id) forks before its parent branch exists.")
                }
            }
            let parentDeleteIndex = document.appliedOperations.firstIndex { operation in
                guard operation.kind == .deleteBranch,
                      case let .branchDeleted(_, branchID, _) = operation.outcome else {
                    return false
                }
                return branchID == origin.parentBranchID
            }
            if let parentDeleteIndex, forkIndex >= parentDeleteIndex {
                issues.append("Branch \(branch.id) forks after its parent was deleted.")
            }

            for (index, operation) in document.appliedOperations.enumerated()
            where operation.kind != .forkBranch && operation.outcome.targetBranchID == branch.id {
                if index <= forkIndex {
                    issues.append("Branch \(branch.id) has an operation before its fork operation.")
                }
            }

            for checkpoint in document.checkpoints where
                checkpoint.kind != .initial && checkpoint.createdOnBranchID == branch.id {
                if let ownerIndex = operationIndex[checkpoint.operationID], ownerIndex <= forkIndex {
                    issues.append("Branch \(branch.id) has a checkpoint before its fork operation.")
                }
            }
            for (index, operation) in document.appliedOperations.enumerated() {
                guard operation.kind == .undoBranchHead,
                      case let .branchHeadMoved(_, branchID, _, _, _, _) = operation.outcome,
                      branchID == branch.id else {
                    continue
                }
                if index <= forkIndex {
                    issues.append("Branch \(branch.id) has an undo before its fork operation.")
                }
            }
        }
    }

    private static func validateCollectionOutcome(
        operation: NovelAppliedOperationRecord,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID,
        checkpointID: NovelCheckpointID,
        chapterVersionID: NovelChapterVersionID,
        revision: Int64,
        requiresMatchingOperationID: Bool,
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let checkpoint = document.checkpoints.first(where: { $0.id == checkpointID })
        let candidate = document.candidates.first(where: { $0.id == candidateID })
        let version = document.chapterVersions.first(where: { $0.id == chapterVersionID })
        let operationMatches = !requiresMatchingOperationID ||
            (checkpoint?.operationID == operation.operationID &&
                version?.operationID == operation.operationID)
        if revision != operation.appliedProjectRevision ||
            checkpoint?.kind != .collection ||
            checkpoint?.createdOnBranchID != branchID ||
            checkpoint?.sourceCandidateID != candidateID ||
            candidate?.collectedCheckpointID != checkpointID ||
            candidate?.status != .collected ||
            version?.sourceCandidateID != candidateID ||
            checkpoint?.chapterSelections.contains(where: {
                $0.chapterID == version?.chapterID && $0.versionID == chapterVersionID
            }) != true ||
            !operationMatches {
            issues.append("Collection operation \(operation.operationID) has invalid outcome data.")
        }
    }

    private static func validateManualSyncOutcome(
        operation: NovelAppliedOperationRecord,
        branchID: NovelBranchID,
        checkpointID: NovelCheckpointID,
        revision: Int64,
        requiresMatchingOperationID: Bool,
        document: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        let checkpoint = document.checkpoints.first(where: { $0.id == checkpointID })
        if revision != operation.appliedProjectRevision ||
            checkpoint?.kind != .manualSync ||
            checkpoint?.createdOnBranchID != branchID ||
            (requiresMatchingOperationID && checkpoint?.operationID != operation.operationID) {
            issues.append("Manual-sync operation \(operation.operationID) has invalid outcome data.")
        }
    }

    private static func appendDuplicateIssue<Element, ID: Hashable>(
        _ elements: [Element],
        key: KeyPath<Element, ID>,
        label: String,
        issues: inout [String]
    ) {
        let ids = elements.map { $0[keyPath: key] }
        if Set(ids).count != ids.count {
            issues.append("Duplicate \(label) ID.")
        }
    }

    private static func appendUnchangedPrefixIssue<Element: Equatable>(
        _ current: [Element],
        _ next: [Element],
        label: String,
        issues: inout [String]
    ) {
        guard next.count >= current.count else {
            issues.append("A project transition removed an immutable \(label).")
            return
        }
        if !zip(current, next).allSatisfy({ $0 == $1 }) {
            issues.append("A project transition rewrote an immutable \(label).")
        }
    }

    private static func validateMaterialTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for material in current.materials {
            guard let candidate = next.materials.first(where: { $0.id == material.id }) else {
                issues.append("A project transition removed material \(material.id).")
                continue
            }
            if candidate.kind != material.kind {
                issues.append("A project transition changed material \(material.id) kind.")
            }
            if candidate.revisionIDs.count < material.revisionIDs.count ||
                !zip(material.revisionIDs, candidate.revisionIDs).allSatisfy({ $0 == $1 }) {
                issues.append("A project transition rewrote material \(material.id) revision history.")
            }
            if material.isDeleted && !candidate.isDeleted {
                issues.append("A project transition reactivated deleted material \(material.id).")
            }
        }
    }

    private static func validateBranchTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for branch in current.branches {
            guard let candidate = next.branches.first(where: { $0.id == branch.id }) else {
                issues.append("A project transition removed branch \(branch.id).")
                continue
            }
            if candidate.sessionID != branch.sessionID ||
                candidate.createdAt != branch.createdAt ||
                candidate.forkOrigin != branch.forkOrigin {
                issues.append("A project transition rewrote branch \(branch.id) identity.")
            }
            if branch.lifecycle == .deleted && candidate.lifecycle != .deleted {
                issues.append("A project transition reactivated deleted branch \(branch.id).")
            }
            if candidate.headRevision < branch.headRevision ||
                candidate.workingRevision < branch.workingRevision {
                issues.append("A project transition moved branch \(branch.id) revisions backwards.")
            }
        }
    }

    private static func validateSessionTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for session in current.sessions {
            guard let candidate = next.sessions.first(where: { $0.id == session.id }) else {
                issues.append("A project transition removed Session \(session.id).")
                continue
            }
            if candidate.branchID != session.branchID {
                issues.append("A project transition changed Session \(session.id) ownership.")
            }
            if candidate.messages.count < session.messages.count ||
                !zip(session.messages, candidate.messages).allSatisfy({ $0 == $1 }) {
                issues.append("A project transition rewrote Session \(session.id) history.")
            }
        }
    }

    private static func validateCandidateTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for candidate in current.candidates {
            guard let updated = next.candidates.first(where: { $0.id == candidate.id }) else {
                issues.append("A project transition removed candidate \(candidate.id).")
                continue
            }
            if updated.kind != candidate.kind ||
                updated.branchID != candidate.branchID ||
                updated.sessionID != candidate.sessionID ||
                updated.sourceMessageID != candidate.sourceMessageID ||
                updated.baseCheckpointID != candidate.baseCheckpointID ||
                updated.baseHeadRevision != candidate.baseHeadRevision ||
                updated.content != candidate.content ||
                updated.sourceChapterVersionID != candidate.sourceChapterVersionID ||
                updated.clonedFromCandidateID != candidate.clonedFromCandidateID ||
                updated.createdAt != candidate.createdAt {
                issues.append("A project transition rewrote candidate \(candidate.id) identity or content.")
            }
            if let collectedCheckpointID = candidate.collectedCheckpointID,
               updated.collectedCheckpointID != collectedCheckpointID {
                issues.append("A project transition rewrote candidate \(candidate.id) collection history.")
            }
            if !candidateStatusCanTransition(from: candidate.status, to: updated.status) {
                issues.append("A project transition moved candidate \(candidate.id) status backwards.")
            }
        }
    }

    private static func validateSettingProposalTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for proposal in current.settingProposals {
            guard let updated = next.settingProposals.first(where: { $0.id == proposal.id }) else {
                issues.append("A project transition removed setting proposal \(proposal.id).")
                continue
            }
            if updated.branchID != proposal.branchID ||
                updated.title != proposal.title ||
                updated.content != proposal.content ||
                updated.createdAt != proposal.createdAt ||
                updated.origin != proposal.origin {
                issues.append("A project transition rewrote setting proposal \(proposal.id).")
            }
            if proposal.isResolved && !updated.isResolved {
                issues.append("A project transition reopened setting proposal \(proposal.id).")
            }
        }

    }

    private static func validatePendingOperationTransition(
        from current: NovelProjectDocumentV1,
        to next: NovelProjectDocumentV1,
        issues: inout [String]
    ) {
        for pending in current.pendingOperations {
            guard let updated = next.pendingOperations.first(where: { $0.id == pending.id }) else {
                continue
            }
            if updated.kind != pending.kind ||
                updated.branchID != pending.branchID ||
                updated.operationID != pending.operationID ||
                updated.payloadSHA256 != pending.payloadSHA256 ||
                updated.baseCheckpointID != pending.baseCheckpointID ||
                updated.baseHeadRevision != pending.baseHeadRevision ||
                updated.baseWorkingRevision != pending.baseWorkingRevision ||
                updated.candidateID != pending.candidateID ||
                updated.collectionTarget != pending.collectionTarget ||
                updated.selectedText != pending.selectedText ||
                updated.proposedChapterVersion != pending.proposedChapterVersion ||
                updated.proposedCheckpointID != pending.proposedCheckpointID ||
                updated.proposedStateSnapshotID != pending.proposedStateSnapshotID ||
                updated.rebuildBaseCheckpointID != pending.rebuildBaseCheckpointID ||
                updated.sessionCursor != pending.sessionCursor ||
                updated.createdAt != pending.createdAt {
                issues.append("A project transition rewrote pending operation \(pending.id) payload.")
            }
            if pending.status == .retryable && updated.status == .pending {
                issues.append("A project transition moved retryable pending operation \(pending.id) backwards.")
            }
            NovelManualSyncProgressValidator.validateTransition(
                from: pending,
                to: updated,
                currentDocument: current,
                nextDocument: next,
                issues: &issues
            )
        }
    }

    private static func validateCheckpointLineage(
        _ document: NovelProjectDocumentV1,
        checkpointByID: [NovelCheckpointID: NovelBranchCheckpointRecord],
        initialCheckpoint: NovelBranchCheckpointRecord?,
        issues: inout [String]
    ) {
        guard let initialCheckpoint else { return }
        let rootBranches = document.branches.filter { $0.forkOrigin == nil }
        if rootBranches.count != 1 || rootBranches.first?.id != initialCheckpoint.createdOnBranchID {
            issues.append("Project checkpoint history must have one root branch matching the initial checkpoint.")
        }

        for branch in document.branches {
            let boundaryID: NovelCheckpointID
            if let origin = branch.forkOrigin {
                if origin.parentBranchID == branch.id {
                    issues.append("Branch \(branch.id) forks from itself.")
                    continue
                }
                guard let parent = document.branches.first(where: { $0.id == origin.parentBranchID }) else {
                    continue
                }
                if checkpointByID[origin.checkpointID]?.kind == .initial {
                    issues.append("Branch \(branch.id) cannot fork from the internal initial checkpoint.")
                }
                let parentBoundary = parent.forkOrigin?.checkpointID ?? initialCheckpoint.id
                if !checkpoint(
                    origin.checkpointID,
                    belongsTo: parent,
                    boundaryID: parentBoundary,
                    checkpointByID: checkpointByID
                ) {
                    issues.append("Branch \(branch.id) fork checkpoint is outside its parent history.")
                }
                boundaryID = origin.checkpointID
            } else {
                boundaryID = initialCheckpoint.id
            }

            if !checkpoint(
                    branch.headCheckpointID,
                    belongsTo: branch,
                    boundaryID: boundaryID,
                    checkpointByID: checkpointByID
               ) {
                issues.append("Branch \(branch.id) head is outside its checkpoint lineage.")
            }

            validateBranchHeadTimeline(
                branch,
                boundaryID: boundaryID,
                document: document,
                checkpointByID: checkpointByID,
                issues: &issues
            )

            var visited: Set<NovelBranchID> = []
            var current: NovelBranchRecord? = branch
            while let node = current, let origin = node.forkOrigin {
                guard visited.insert(node.id).inserted else {
                    issues.append("Branch fork graph contains a cycle at \(node.id).")
                    break
                }
                current = document.branches.first(where: { $0.id == origin.parentBranchID })
            }
        }

        for checkpointRecord in document.checkpoints {
            guard let branch = document.branches.first(where: {
                $0.id == checkpointRecord.createdOnBranchID
            }) else {
                continue
            }
            let boundaryID = branch.forkOrigin?.checkpointID ?? initialCheckpoint.id
            if !checkpoint(
                checkpointRecord.id,
                belongsTo: branch,
                boundaryID: boundaryID,
                checkpointByID: checkpointByID
            ) {
                issues.append(
                    "Checkpoint \(checkpointRecord.id) is outside its creating branch lineage."
                )
            }
        }
    }

    private static func validateBranchHeadTimeline(
        _ branch: NovelBranchRecord,
        boundaryID: NovelCheckpointID,
        document: NovelProjectDocumentV1,
        checkpointByID: [NovelCheckpointID: NovelBranchCheckpointRecord],
        issues: inout [String]
    ) {
        var headID = boundaryID
        var headRevision: Int64 = 0
        var workingRevision: Int64 = 0
        var consumedCheckpointIDs: Set<NovelCheckpointID> = []

        for operation in document.appliedOperations {
            let created = document.checkpoints.filter {
                $0.kind != .initial &&
                    $0.createdOnBranchID == branch.id &&
                    $0.operationID == operation.operationID
            }
            for checkpoint in created {
                if checkpoint.parentCheckpointID != headID ||
                    checkpoint.baseHeadRevision != headRevision {
                    issues.append(
                        "Branch \(branch.id) checkpoint timeline is not contiguous at \(checkpoint.id)."
                    )
                }
                headID = checkpoint.id
                headRevision += 1
                if checkpoint.kind == .collection ||
                    checkpoint.kind == .polish ||
                    checkpoint.kind == .restore {
                    workingRevision += 1
                }
                consumedCheckpointIDs.insert(checkpoint.id)
            }

            if operation.kind == .saveManualEdit,
               case let .manualEditSaved(
                   _, branchID, _, outcomeWorkingRevision, _
               ) = operation.outcome,
               branchID == branch.id {
                if outcomeWorkingRevision != workingRevision + 1 {
                    issues.append("Branch \(branch.id) working revision timeline is not contiguous.")
                }
                workingRevision += 1
            }

            guard operation.kind == .undoBranchHead,
                  case let .branchHeadMoved(
                      _, branchID, fromCheckpointID, toCheckpointID, outcomeHeadRevision, _
                  ) = operation.outcome,
                  branchID == branch.id else {
                continue
            }
            let current = checkpointByID[fromCheckpointID]
            let directParent = current.flatMap { checkpoint in
                checkpoint.parentCheckpointID.flatMap { checkpointByID[$0] }
            }
            let expectedTarget = current.flatMap {
                NovelBranchSemantics.undoTarget(
                    for: $0,
                    branch: branch,
                    checkpoints: document.checkpoints
                )
            }
            let validTargetIDs = Set([directParent?.id, expectedTarget?.id].compactMap { $0 })
            if fromCheckpointID != headID ||
                !validTargetIDs.contains(toCheckpointID) ||
                outcomeHeadRevision != headRevision + 1 {
                issues.append("Branch \(branch.id) undo timeline is not contiguous.")
            }
            headID = toCheckpointID
            headRevision += 1
            workingRevision += 1
        }

        let ownedCheckpointIDs = Set(document.checkpoints.filter {
            $0.kind != .initial && $0.createdOnBranchID == branch.id
        }.map(\.id))
        if consumedCheckpointIDs != ownedCheckpointIDs ||
            headID != branch.headCheckpointID ||
            headRevision != branch.headRevision ||
            workingRevision != branch.workingRevision {
            issues.append("Branch \(branch.id) head does not match its operation timeline.")
        }
    }

    private static func candidateStatusCanTransition(
        from current: NovelCandidateStatus,
        to next: NovelCandidateStatus
    ) -> Bool {
        if current == next { return true }
        return switch (current, next) {
        case (.available, .collected),
             (.available, .adopted),
             (.available, .interrupted),
             (.available, .superseded),
             (.collected, .superseded),
             (.adopted, .superseded):
            true
        default:
            false
        }
    }

    private static func checkpoint(
        _ checkpointID: NovelCheckpointID,
        belongsTo branch: NovelBranchRecord,
        boundaryID: NovelCheckpointID,
        checkpointByID: [NovelCheckpointID: NovelBranchCheckpointRecord]
    ) -> Bool {
        var visited: Set<NovelCheckpointID> = []
        var currentID = checkpointID
        while currentID != boundaryID {
            guard visited.insert(currentID).inserted,
                  let checkpoint = checkpointByID[currentID],
                  checkpoint.createdOnBranchID == branch.id,
                  let parentID = checkpoint.parentCheckpointID else {
                return false
            }
            currentID = parentID
        }
        return checkpointByID[boundaryID] != nil
    }
}

extension NovelOutcome {
    var projectID: NovelProjectID {
        switch self {
        case .projectCreated(let projectID, _): projectID
        case .projectRenamed(let projectID, _): projectID
        case .materialRevised(let projectID, _, _, _, _): projectID
        case .materialDeleted(let projectID, _, _, _): projectID
        case .modelPolicyChanged(let projectID, _, _): projectID
        case .settingProposalAccepted(let projectID, _, _, _, _, _): projectID
        case .settingProposalRejected(let projectID, _, _, _): projectID
        case .branchMaterialOverrideChanged(let projectID, _, _, _, _, _): projectID
        case .mainBranchChanged(let projectID, _, _): projectID
        case .polishPreferenceChanged(let projectID, _, _): projectID
        case .branchForked(let projectID, _, _, _, _): projectID
        case .branchRenamed(let projectID, _, _): projectID
        case .branchDeleted(let projectID, _, _): projectID
        case .branchHeadMoved(let projectID, _, _, _, _, _): projectID
        case .candidateCloned(let projectID, _, _, _, _): projectID
        case .polishCandidateAdopted(let projectID, _, _, _, _, _): projectID
        case .polishCandidateRejected(let projectID, _, _, _, _): projectID
        case .polishTransactionAbandoned(let projectID, _, _, _, _): projectID
        case .chapterVersionRestored(let projectID, _, _, _, _): projectID
        case .runStarted(let projectID, _, _, _, _): projectID
        case .runInterrupted(let projectID, _, _, _): projectID
        case .candidateCollected(let projectID, _, _, _, _, _): projectID
        case .manualEditSaved(let projectID, _, _, _, _): projectID
        case .manualSyncCommitted(let projectID, _, _, _): projectID
        case .projectImported(_, let projectID, _, _, _): projectID
        case .previousProjectRestored(let projectID, _): projectID
        case .projectDeleted(let projectID): projectID
        }
    }

    var targetBranchID: NovelBranchID? {
        switch self {
        case .projectCreated(_, let branchID),
             .mainBranchChanged(_, let branchID, _),
             .branchMaterialOverrideChanged(_, let branchID, _, _, _, _),
             .branchRenamed(_, let branchID, _),
             .branchDeleted(_, let branchID, _),
             .branchHeadMoved(_, let branchID, _, _, _, _),
             .candidateCloned(_, let branchID, _, _, _),
             .polishCandidateAdopted(_, let branchID, _, _, _, _),
             .polishCandidateRejected(_, let branchID, _, _, _),
             .polishTransactionAbandoned(_, let branchID, _, _, _),
             .chapterVersionRestored(_, let branchID, _, _, _),
             .runStarted(_, let branchID, _, _, _),
             .candidateCollected(_, let branchID, _, _, _, _),
             .manualEditSaved(_, let branchID, _, _, _),
             .manualSyncCommitted(_, let branchID, _, _):
            branchID
        case .branchForked(_, _, let branchID, _, _):
            branchID
        case .projectRenamed,
             .materialRevised,
             .materialDeleted,
             .modelPolicyChanged,
             .settingProposalAccepted,
             .settingProposalRejected,
             .polishPreferenceChanged,
             .runInterrupted,
             .projectImported,
             .previousProjectRestored,
             .projectDeleted:
            nil
        }
    }
}
