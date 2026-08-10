import CryptoKit
import Foundation

struct NovelMutationContext: Codable, Equatable, Sendable {
    let operationID: NovelOperationID
    let expectedProjectRevision: Int64?
    let expectedConfigRevision: Int64?
    let expectedBranchHeadRevision: Int64?
}

struct NovelCreateProjectCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let sessionID: NovelSessionID
    let initialStateSnapshotID: NovelStateSnapshotID
    let initialCheckpointID: NovelCheckpointID
    let name: String
    let branchName: String
    let creationMode: NovelProjectCreationMode
    let quickStartSeed: NovelQuickStartSeed?
}

struct NovelRenameProjectCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let name: String
}

struct NovelReviseMaterialCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let materialID: NovelMaterialID
    let revisionID: NovelMaterialRevisionID
    let kind: NovelMaterialKind
    let title: String
    let content: String
    let tags: [String]
    let injectionMode: NovelInjectionMode
    let aliases: [String]

    init(
        context: NovelMutationContext,
        projectID: NovelProjectID,
        materialID: NovelMaterialID,
        revisionID: NovelMaterialRevisionID,
        kind: NovelMaterialKind,
        title: String,
        content: String,
        tags: [String],
        injectionMode: NovelInjectionMode,
        aliases: [String] = []
    ) {
        self.context = context
        self.projectID = projectID
        self.materialID = materialID
        self.revisionID = revisionID
        self.kind = kind
        self.title = title
        self.content = content
        self.tags = tags
        self.injectionMode = injectionMode
        self.aliases = aliases
    }
}

struct NovelDeleteMaterialCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let materialID: NovelMaterialID
}

struct NovelSetModelPolicyCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let purpose: NovelModelRole
    let policy: NovelProjectModelPolicy

    init(
        context: NovelMutationContext,
        projectID: NovelProjectID,
        purpose: NovelModelRole = .creation,
        policy: NovelProjectModelPolicy
    ) {
        self.context = context
        self.projectID = projectID
        self.purpose = purpose
        self.policy = policy
    }
}

enum NovelSettingProposalResolution: Codable, Equatable, Sendable {
    case accept(
        materialID: NovelMaterialID,
        revisionID: NovelMaterialRevisionID,
        kind: NovelMaterialKind,
        title: String,
        content: String,
        tags: [String],
        injectionMode: NovelInjectionMode,
        aliases: [String]
    )
    case reject
}

struct NovelResolveSettingProposalCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let proposalID: NovelProposalID
    let resolution: NovelSettingProposalResolution
}

enum NovelBranchMaterialOverrideChange: Codable, Equatable, Sendable {
    case inherit
    case useRevision(NovelMaterialRevisionID)
    case createRevision(
        revisionID: NovelMaterialRevisionID,
        title: String,
        content: String,
        tags: [String],
        injectionMode: NovelInjectionMode
    )
}

struct NovelSetBranchMaterialOverrideCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let materialID: NovelMaterialID
    let change: NovelBranchMaterialOverrideChange
}

struct NovelSetMainBranchCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

struct NovelSetPolishPreferenceCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let preference: String
}

struct NovelSetCollaborationModeCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let mode: NovelCollaborationMode
}

struct NovelSetPauseGhostwriteOnBlockingContinuityCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let enabled: Bool
}

struct NovelUpsertChapterPlanCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let planID: NovelChapterPlanID
    let status: NovelChapterPlanStatus
    let outlinePlacement: String
    let goalAndConflict: String
    let mustHappen: [String]
    let mustNotHappen: [String]
    let endingHook: String
    let visibleFacts: [String]
}

struct NovelClearChapterPlanCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

struct NovelUpsertUpcomingArcCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let beats: [String]
}

struct NovelClearUpcomingArcCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

struct NovelForkBranchCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let sourceBranchID: NovelBranchID
    let checkpointID: NovelCheckpointID
    let branchID: NovelBranchID
    let sessionID: NovelSessionID
    let name: String
}

struct NovelRenameBranchCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let name: String
}

struct NovelDeleteBranchCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
}

struct NovelUndoBranchHeadCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let expectedWorkingRevision: Int64
}

struct NovelConfirmedDiscussionDecision: Codable, Equatable, Sendable {
    let materialID: NovelMaterialID
    let revisionID: NovelMaterialRevisionID
    let topic: String
    let decision: String
    let relatedMaterialID: NovelMaterialID?
}

struct NovelArchiveDiscussionCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let archiveID: NovelMessageID
    let checkpointID: NovelCheckpointID
    let throughSequence: Int64
    let chapterID: NovelChapterID?
    let summary: String
    let decisions: [NovelConfirmedDiscussionDecision]
}

struct NovelClarifyCharacterIdentityCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let checkpointID: NovelCheckpointID
    let stateSnapshotID: NovelStateSnapshotID
    let mention: String
    let clarification: String
}

struct NovelCloneCandidateCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let sourceCandidateID: NovelCandidateID
    let candidateID: NovelCandidateID
}

struct NovelAdoptPolishCandidateCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let transactionID: NovelPendingOperationID
    let candidateID: NovelCandidateID
    let proposedChapterVersionID: NovelChapterVersionID
    let checkpointID: NovelCheckpointID
    let expectedWorkingRevision: Int64
}

struct NovelAbandonPolishTransactionCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let transactionID: NovelPendingOperationID
}

/// 标记废弃 / 恢复某一章。不删除任何记录:章节版本之间有事实兼容链,
/// 真删会断链。废弃后该章不参与生成上下文与剧情状态推导。
struct NovelDiscardChapterCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let chapterID: NovelChapterID
}

struct NovelRestoreChapterCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let chapterID: NovelChapterID
}

struct NovelRestoreChapterVersionCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let targetChapterVersionID: NovelChapterVersionID
    let proposedChapterVersionID: NovelChapterVersionID
    let checkpointID: NovelCheckpointID
    let expectedWorkingRevision: Int64
}

struct NovelCancelRunCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let runID: NovelRunID
    let reason: NovelRunInterruptionReason
}

enum NovelCollectionSource: String, Codable, Equatable, Sendable {
    case user
    case systemAutoCollect
}

struct NovelCollectCandidateCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let pendingID: NovelPendingOperationID
    let candidateID: NovelCandidateID
    let selection: NovelParagraphSelection
    let target: NovelCollectionTarget
    let proposedChapterVersionID: NovelChapterVersionID
    let checkpointID: NovelCheckpointID
    let stateSnapshotID: NovelStateSnapshotID
    let factCompatibilityID: UUID
    /// Who initiated collect. Defaults to `.user` for sheets; ghostwrite pipeline uses
    /// `.systemAutoCollect` and requires a matching chapter-plan digest.
    var source: NovelCollectionSource = .user
}

struct NovelSaveManualEditCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let chapterID: NovelChapterID
    let versionID: NovelChapterVersionID
    let title: String
    let content: String
    let factCompatibilityID: UUID
    let expectedWorkingRevision: Int64
}

struct NovelSyncManualEditsCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let pendingID: NovelPendingOperationID
    let checkpointID: NovelCheckpointID
    let stateSnapshotID: NovelStateSnapshotID
    let expectedWorkingRevision: Int64
}

struct NovelRetryPendingCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let pendingID: NovelPendingOperationID
}

struct NovelImportProjectCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
    let packageData: Data
    let policy: NovelProjectImportPolicy
}

struct NovelInjectionPreviewRequest: Equatable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let kind: NovelRunKind
    let mode: NovelSessionMode
    let granularity: NovelGenerationGranularity?
    let userText: String
    let sourceChapterVersionID: NovelChapterVersionID?
    let injectionOverrides: NovelInjectionOverrides
    let inputBudgetTokens: Int
}

struct NovelInjectionPreviewSnapshot: Equatable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let projectRevision: Int64
    let configRevision: Int64
    let branchHeadRevision: Int64
    let resolvedModel: NovelResolvedModel
    let parameters: NovelModelParameters
    let requestedInputBudgetTokens: Int
    let effectiveInputBudgetTokens: Int
    let plan: NovelInjectionPlan
}

struct NovelDeleteProjectCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
}

struct NovelRestorePreviousProjectCommand: Equatable, Sendable {
    let context: NovelMutationContext
    let projectID: NovelProjectID
}

enum NovelAction: Equatable, Sendable {
    case createProject(NovelCreateProjectCommand)
    case renameProject(NovelRenameProjectCommand)
    case reviseMaterial(NovelReviseMaterialCommand)
    case deleteMaterial(NovelDeleteMaterialCommand)
    case setModelPolicy(NovelSetModelPolicyCommand)
    case resolveSettingProposal(NovelResolveSettingProposalCommand)
    case setBranchMaterialOverride(NovelSetBranchMaterialOverrideCommand)
    case setMainBranch(NovelSetMainBranchCommand)
    case setPolishPreference(NovelSetPolishPreferenceCommand)
    case setCollaborationMode(NovelSetCollaborationModeCommand)
    case setPauseGhostwriteOnBlockingContinuity(NovelSetPauseGhostwriteOnBlockingContinuityCommand)
    case upsertChapterPlan(NovelUpsertChapterPlanCommand)
    case clearChapterPlan(NovelClearChapterPlanCommand)
    case upsertUpcomingArc(NovelUpsertUpcomingArcCommand)
    case clearUpcomingArc(NovelClearUpcomingArcCommand)
    case forkBranch(NovelForkBranchCommand)
    case renameBranch(NovelRenameBranchCommand)
    case deleteBranch(NovelDeleteBranchCommand)
    case undoBranchHead(NovelUndoBranchHeadCommand)
    case archiveDiscussion(NovelArchiveDiscussionCommand)
    case clarifyCharacterIdentity(NovelClarifyCharacterIdentityCommand)
    case cloneCandidate(NovelCloneCandidateCommand)
    case adoptPolishCandidate(NovelAdoptPolishCandidateCommand)
    case abandonPolishTransaction(NovelAbandonPolishTransactionCommand)
    case restoreChapterVersion(NovelRestoreChapterVersionCommand)
    case discardChapter(NovelDiscardChapterCommand)
    case restoreChapter(NovelRestoreChapterCommand)
    case cancelRun(NovelCancelRunCommand)
    case collectCandidate(NovelCollectCandidateCommand)
    case saveManualEdit(NovelSaveManualEditCommand)
    case syncManualEdits(NovelSyncManualEditsCommand)
    case retryPending(NovelRetryPendingCommand)
    case importProject(NovelImportProjectCommand)
    case restorePreviousProject(NovelRestorePreviousProjectCommand)
    case deleteProject(NovelDeleteProjectCommand)

    var projectID: NovelProjectID {
        switch self {
        case .createProject(let command): command.projectID
        case .renameProject(let command): command.projectID
        case .reviseMaterial(let command): command.projectID
        case .deleteMaterial(let command): command.projectID
        case .setModelPolicy(let command): command.projectID
        case .resolveSettingProposal(let command): command.projectID
        case .setBranchMaterialOverride(let command): command.projectID
        case .setMainBranch(let command): command.projectID
        case .setPolishPreference(let command): command.projectID
        case .setCollaborationMode(let command): command.projectID
        case .setPauseGhostwriteOnBlockingContinuity(let command): command.projectID
        case .upsertChapterPlan(let command): command.projectID
        case .clearChapterPlan(let command): command.projectID
        case .upsertUpcomingArc(let command): command.projectID
        case .clearUpcomingArc(let command): command.projectID
        case .forkBranch(let command): command.projectID
        case .renameBranch(let command): command.projectID
        case .deleteBranch(let command): command.projectID
        case .undoBranchHead(let command): command.projectID
        case .archiveDiscussion(let command): command.projectID
        case .clarifyCharacterIdentity(let command): command.projectID
        case .cloneCandidate(let command): command.projectID
        case .adoptPolishCandidate(let command): command.projectID
        case .abandonPolishTransaction(let command): command.projectID
        case .restoreChapterVersion(let command): command.projectID
        case .discardChapter(let command): command.projectID
        case .restoreChapter(let command): command.projectID
        case .cancelRun(let command): command.projectID
        case .collectCandidate(let command): command.projectID
        case .saveManualEdit(let command): command.projectID
        case .syncManualEdits(let command): command.projectID
        case .retryPending(let command): command.projectID
        case .importProject(let command): command.projectID
        case .restorePreviousProject(let command): command.projectID
        case .deleteProject(let command): command.projectID
        }
    }

    var context: NovelMutationContext {
        switch self {
        case .createProject(let command): command.context
        case .renameProject(let command): command.context
        case .reviseMaterial(let command): command.context
        case .deleteMaterial(let command): command.context
        case .setModelPolicy(let command): command.context
        case .resolveSettingProposal(let command): command.context
        case .setBranchMaterialOverride(let command): command.context
        case .setMainBranch(let command): command.context
        case .setPolishPreference(let command): command.context
        case .setCollaborationMode(let command): command.context
        case .setPauseGhostwriteOnBlockingContinuity(let command): command.context
        case .upsertChapterPlan(let command): command.context
        case .clearChapterPlan(let command): command.context
        case .upsertUpcomingArc(let command): command.context
        case .clearUpcomingArc(let command): command.context
        case .forkBranch(let command): command.context
        case .renameBranch(let command): command.context
        case .deleteBranch(let command): command.context
        case .undoBranchHead(let command): command.context
        case .archiveDiscussion(let command): command.context
        case .clarifyCharacterIdentity(let command): command.context
        case .cloneCandidate(let command): command.context
        case .adoptPolishCandidate(let command): command.context
        case .abandonPolishTransaction(let command): command.context
        case .restoreChapterVersion(let command): command.context
        case .discardChapter(let command): command.context
        case .restoreChapter(let command): command.context
        case .cancelRun(let command): command.context
        case .collectCandidate(let command): command.context
        case .saveManualEdit(let command): command.context
        case .syncManualEdits(let command): command.context
        case .retryPending(let command): command.context
        case .importProject(let command): command.context
        case .restorePreviousProject(let command): command.context
        case .deleteProject(let command): command.context
        }
    }

    var operationKind: NovelOperationKind {
        switch self {
        case .createProject: .createProject
        case .renameProject: .renameProject
        case .reviseMaterial: .reviseMaterial
        case .deleteMaterial: .deleteMaterial
        case .setModelPolicy: .setModelPolicy
        case .resolveSettingProposal: .resolveSettingProposal
        case .setBranchMaterialOverride: .setBranchMaterialOverride
        case .setMainBranch: .setMainBranch
        case .setPolishPreference: .setPolishPreference
        case .setCollaborationMode: .setCollaborationMode
        case .setPauseGhostwriteOnBlockingContinuity: .setPauseGhostwriteOnBlockingContinuity
        case .upsertChapterPlan: .upsertChapterPlan
        case .clearChapterPlan: .clearChapterPlan
        case .upsertUpcomingArc: .upsertUpcomingArc
        case .clearUpcomingArc: .clearUpcomingArc
        case .forkBranch: .forkBranch
        case .renameBranch: .renameBranch
        case .deleteBranch: .deleteBranch
        case .undoBranchHead: .undoBranchHead
        case .archiveDiscussion: .archiveDiscussion
        case .clarifyCharacterIdentity: .clarifyCharacterIdentity
        case .cloneCandidate: .cloneCandidate
        case .adoptPolishCandidate: .adoptPolishCandidate
        case .abandonPolishTransaction: .abandonPolishTransaction
        case .restoreChapterVersion: .restoreChapterVersion
        case .discardChapter: .discardChapter
        case .restoreChapter: .restoreChapter
        case .cancelRun: .cancelRun
        case .collectCandidate: .collectCandidate
        case .saveManualEdit: .saveManualEdit
        case .syncManualEdits: .syncManualEdits
        case .retryPending: .retryPending
        case .importProject: .importProject
        case .restorePreviousProject: .restorePreviousProject
        case .deleteProject: .deleteProject
        }
    }

    var isProjectCreation: Bool {
        switch self {
        case .createProject, .importProject: true
        default: false
        }
    }

    var isRunCancellation: Bool {
        if case .cancelRun = self { return true }
        return false
    }

    var isProjectLifecycle: Bool {
        switch self {
        case .importProject, .restorePreviousProject, .deleteProject: true
        default: false
        }
    }

    func canonicalPayloadSHA256() throws -> String {
        let payload: NovelCanonicalActionPayload
        switch self {
        case .createProject(let command):
            payload = .createProject(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                sessionID: command.sessionID,
                initialStateSnapshotID: command.initialStateSnapshotID,
                initialCheckpointID: command.initialCheckpointID,
                name: command.name,
                branchName: command.branchName,
                creationMode: command.creationMode,
                quickStartSeed: command.quickStartSeed
            ))
        case .renameProject(let command):
            payload = .renameProject(.init(projectID: command.projectID, name: command.name))
        case .reviseMaterial(let command):
            payload = .reviseMaterial(.init(
                projectID: command.projectID,
                materialID: command.materialID,
                revisionID: command.revisionID,
                kind: command.kind,
                title: command.title,
                content: command.content,
                tags: command.tags,
                injectionMode: command.injectionMode,
                aliases: command.aliases
            ))
        case .deleteMaterial(let command):
            payload = .deleteMaterial(.init(
                projectID: command.projectID,
                materialID: command.materialID
            ))
        case .setModelPolicy(let command):
            payload = .setModelPolicy(.init(
                projectID: command.projectID,
                purpose: command.purpose,
                policy: command.policy
            ))
        case .resolveSettingProposal(let command):
            payload = .resolveSettingProposal(.init(
                projectID: command.projectID,
                proposalID: command.proposalID,
                resolution: command.resolution
            ))
        case .setBranchMaterialOverride(let command):
            payload = .setBranchMaterialOverride(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                materialID: command.materialID,
                change: command.change
            ))
        case .setMainBranch(let command):
            payload = .setMainBranch(.init(
                projectID: command.projectID,
                branchID: command.branchID
            ))
        case .setPolishPreference(let command):
            payload = .setPolishPreference(.init(
                projectID: command.projectID,
                preference: command.preference
            ))
        case .setCollaborationMode(let command):
            payload = .setCollaborationMode(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                mode: command.mode
            ))
        case .setPauseGhostwriteOnBlockingContinuity(let command):
            payload = .setPauseGhostwriteOnBlockingContinuity(.init(
                projectID: command.projectID,
                enabled: command.enabled
            ))
        case .upsertChapterPlan(let command):
            payload = .upsertChapterPlan(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                planID: command.planID,
                status: command.status,
                outlinePlacement: command.outlinePlacement,
                goalAndConflict: command.goalAndConflict,
                mustHappen: command.mustHappen,
                mustNotHappen: command.mustNotHappen,
                endingHook: command.endingHook,
                visibleFacts: command.visibleFacts
            ))
        case .clearChapterPlan(let command):
            payload = .clearChapterPlan(.init(
                projectID: command.projectID,
                branchID: command.branchID
            ))
        case .upsertUpcomingArc(let command):
            payload = .upsertUpcomingArc(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                beats: command.beats
            ))
        case .clearUpcomingArc(let command):
            payload = .clearUpcomingArc(.init(
                projectID: command.projectID,
                branchID: command.branchID
            ))
        case .forkBranch(let command):
            payload = .forkBranch(.init(
                projectID: command.projectID,
                sourceBranchID: command.sourceBranchID,
                checkpointID: command.checkpointID,
                branchID: command.branchID,
                sessionID: command.sessionID,
                name: command.name
            ))
        case .renameBranch(let command):
            payload = .renameBranch(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                name: command.name
            ))
        case .deleteBranch(let command):
            payload = .deleteBranch(.init(
                projectID: command.projectID,
                branchID: command.branchID
            ))
        case .undoBranchHead(let command):
            payload = .undoBranchHead(.init(
                projectID: command.projectID,
                branchID: command.branchID
            ))
        case .archiveDiscussion(let command):
            payload = .archiveDiscussion(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                archiveID: command.archiveID,
                checkpointID: command.checkpointID,
                throughSequence: command.throughSequence,
                chapterID: command.chapterID,
                summary: command.summary,
                decisions: command.decisions
            ))
        case .clarifyCharacterIdentity(let command):
            payload = .clarifyCharacterIdentity(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                checkpointID: command.checkpointID,
                stateSnapshotID: command.stateSnapshotID,
                mention: command.mention,
                clarification: command.clarification
            ))
        case .cloneCandidate(let command):
            payload = .cloneCandidate(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                sourceCandidateID: command.sourceCandidateID,
                candidateID: command.candidateID
            ))
        case .adoptPolishCandidate(let command):
            payload = .adoptPolishCandidate(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                transactionID: command.transactionID,
                candidateID: command.candidateID,
                proposedChapterVersionID: command.proposedChapterVersionID,
                checkpointID: command.checkpointID
            ))
        case .discardChapter(let command):
            payload = .discardChapter(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                chapterID: command.chapterID
            ))
        case .restoreChapter(let command):
            payload = .restoreChapter(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                chapterID: command.chapterID
            ))
        case .abandonPolishTransaction(let command):
            payload = .abandonPolishTransaction(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                transactionID: command.transactionID
            ))
        case .restoreChapterVersion(let command):
            payload = .restoreChapterVersion(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                targetChapterVersionID: command.targetChapterVersionID,
                proposedChapterVersionID: command.proposedChapterVersionID,
                checkpointID: command.checkpointID
            ))
        case .cancelRun(let command):
            payload = .cancelRun(.init(
                projectID: command.projectID,
                runID: command.runID,
                reason: command.reason
            ))
        case .collectCandidate(let command):
            payload = .collectCandidate(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                pendingID: command.pendingID,
                candidateID: command.candidateID,
                selection: command.selection,
                target: command.target,
                proposedChapterVersionID: command.proposedChapterVersionID,
                checkpointID: command.checkpointID,
                stateSnapshotID: command.stateSnapshotID,
                factCompatibilityID: command.factCompatibilityID,
                source: command.source
            ))
        case .saveManualEdit(let command):
            payload = .saveManualEdit(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                chapterID: command.chapterID,
                versionID: command.versionID,
                title: command.title,
                content: command.content,
                factCompatibilityID: command.factCompatibilityID
            ))
        case .syncManualEdits(let command):
            payload = .syncManualEdits(.init(
                projectID: command.projectID,
                branchID: command.branchID,
                pendingID: command.pendingID,
                checkpointID: command.checkpointID,
                stateSnapshotID: command.stateSnapshotID
            ))
        case .retryPending(let command):
            payload = .retryPending(.init(
                projectID: command.projectID,
                pendingID: command.pendingID
            ))
        case .importProject(let command):
            let policy: NovelCanonicalActionPayload.ImportPolicy
            switch command.policy {
            case .reject:
                policy = .reject
            case .replace:
                policy = .replace
            case .keepBoth(let destinationProjectID):
                policy = .keepBoth(destinationProjectID: destinationProjectID)
            }
            payload = .importProject(.init(
                projectID: command.projectID,
                packageSHA256: NovelProjectPackageCodec.sha256(command.packageData),
                policy: policy
            ))
        case .restorePreviousProject(let command):
            payload = .restorePreviousProject(.init(projectID: command.projectID))
        case .deleteProject(let command):
            payload = .deleteProject(.init(projectID: command.projectID))
        }

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(payload)
        return SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }
}

extension NovelCreateProjectCommand {
    func canonicalPayloadSHA256() throws -> String {
        try NovelAction.createProject(self).canonicalPayloadSHA256()
    }
}

extension NovelCollectCandidateCommand {
    func canonicalPayloadSHA256() throws -> String {
        try NovelAction.collectCandidate(self).canonicalPayloadSHA256()
    }
}

extension NovelSaveManualEditCommand {
    func canonicalPayloadSHA256() throws -> String {
        try NovelAction.saveManualEdit(self).canonicalPayloadSHA256()
    }
}

extension NovelSyncManualEditsCommand {
    func canonicalPayloadSHA256() throws -> String {
        try NovelAction.syncManualEdits(self).canonicalPayloadSHA256()
    }
}

extension NovelRetryPendingCommand {
    func canonicalPayloadSHA256() throws -> String {
        try NovelAction.retryPending(self).canonicalPayloadSHA256()
    }
}

private enum NovelCanonicalActionPayload: Codable {
    struct CreateProject: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let sessionID: NovelSessionID
        let initialStateSnapshotID: NovelStateSnapshotID
        let initialCheckpointID: NovelCheckpointID
        let name: String
        let branchName: String
        let creationMode: NovelProjectCreationMode
        let quickStartSeed: NovelQuickStartSeed?
    }

    struct RenameProject: Codable {
        let projectID: NovelProjectID
        let name: String
    }

    struct ReviseMaterial: Codable {
        let projectID: NovelProjectID
        let materialID: NovelMaterialID
        let revisionID: NovelMaterialRevisionID
        let kind: NovelMaterialKind
        let title: String
        let content: String
        let tags: [String]
        let injectionMode: NovelInjectionMode
        let aliases: [String]
    }

    struct DeleteMaterial: Codable {
        let projectID: NovelProjectID
        let materialID: NovelMaterialID
    }

    struct SetModelPolicy: Codable {
        let projectID: NovelProjectID
        let purpose: NovelModelRole
        let policy: NovelProjectModelPolicy
    }

    struct ResolveSettingProposal: Codable {
        let projectID: NovelProjectID
        let proposalID: NovelProposalID
        let resolution: NovelSettingProposalResolution
    }

    struct SetBranchMaterialOverride: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let materialID: NovelMaterialID
        let change: NovelBranchMaterialOverrideChange
    }

    struct SetMainBranch: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
    }

    struct SetPolishPreference: Codable {
        let projectID: NovelProjectID
        let preference: String
    }

    struct SetCollaborationMode: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let mode: NovelCollaborationMode
    }

    struct SetPauseGhostwriteOnBlockingContinuity: Codable {
        let projectID: NovelProjectID
        let enabled: Bool
    }

    struct UpsertChapterPlan: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let planID: NovelChapterPlanID
        let status: NovelChapterPlanStatus
        let outlinePlacement: String
        let goalAndConflict: String
        let mustHappen: [String]
        let mustNotHappen: [String]
        let endingHook: String
        let visibleFacts: [String]
    }

    struct ClearChapterPlan: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
    }

    struct UpsertUpcomingArc: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let beats: [String]
    }

    struct ClearUpcomingArc: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
    }

    struct ForkBranch: Codable {
        let projectID: NovelProjectID
        let sourceBranchID: NovelBranchID
        let checkpointID: NovelCheckpointID
        let branchID: NovelBranchID
        let sessionID: NovelSessionID
        let name: String
    }

    struct RenameBranch: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let name: String
    }

    struct DeleteBranch: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
    }

    struct UndoBranchHead: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
    }

    struct ArchiveDiscussion: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let archiveID: NovelMessageID
        let checkpointID: NovelCheckpointID
        let throughSequence: Int64
        let chapterID: NovelChapterID?
        let summary: String
        let decisions: [NovelConfirmedDiscussionDecision]
    }

    struct ClarifyCharacterIdentity: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let checkpointID: NovelCheckpointID
        let stateSnapshotID: NovelStateSnapshotID
        let mention: String
        let clarification: String
    }

    struct CloneCandidate: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let sourceCandidateID: NovelCandidateID
        let candidateID: NovelCandidateID
    }

    struct AdoptPolishCandidate: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let transactionID: NovelPendingOperationID
        let candidateID: NovelCandidateID
        let proposedChapterVersionID: NovelChapterVersionID
        let checkpointID: NovelCheckpointID
    }

    struct AbandonPolishTransaction: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let transactionID: NovelPendingOperationID
    }

    struct ChapterDiscardScope: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let chapterID: NovelChapterID
    }

    struct RestoreChapterVersion: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let targetChapterVersionID: NovelChapterVersionID
        let proposedChapterVersionID: NovelChapterVersionID
        let checkpointID: NovelCheckpointID
    }

    struct CancelRun: Codable {
        let projectID: NovelProjectID
        let runID: NovelRunID
        let reason: NovelRunInterruptionReason
    }

    struct CollectCandidate: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let pendingID: NovelPendingOperationID
        let candidateID: NovelCandidateID
        let selection: NovelParagraphSelection
        let target: NovelCollectionTarget
        let proposedChapterVersionID: NovelChapterVersionID
        let checkpointID: NovelCheckpointID
        let stateSnapshotID: NovelStateSnapshotID
        let factCompatibilityID: UUID
        let source: NovelCollectionSource

        init(
            projectID: NovelProjectID,
            branchID: NovelBranchID,
            pendingID: NovelPendingOperationID,
            candidateID: NovelCandidateID,
            selection: NovelParagraphSelection,
            target: NovelCollectionTarget,
            proposedChapterVersionID: NovelChapterVersionID,
            checkpointID: NovelCheckpointID,
            stateSnapshotID: NovelStateSnapshotID,
            factCompatibilityID: UUID,
            source: NovelCollectionSource = .user
        ) {
            self.projectID = projectID
            self.branchID = branchID
            self.pendingID = pendingID
            self.candidateID = candidateID
            self.selection = selection
            self.target = target
            self.proposedChapterVersionID = proposedChapterVersionID
            self.checkpointID = checkpointID
            self.stateSnapshotID = stateSnapshotID
            self.factCompatibilityID = factCompatibilityID
            self.source = source
        }

        init(from decoder: any Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            projectID = try values.decode(NovelProjectID.self, forKey: .projectID)
            branchID = try values.decode(NovelBranchID.self, forKey: .branchID)
            pendingID = try values.decode(NovelPendingOperationID.self, forKey: .pendingID)
            candidateID = try values.decode(NovelCandidateID.self, forKey: .candidateID)
            selection = try values.decode(NovelParagraphSelection.self, forKey: .selection)
            target = try values.decode(NovelCollectionTarget.self, forKey: .target)
            proposedChapterVersionID = try values.decode(
                NovelChapterVersionID.self,
                forKey: .proposedChapterVersionID
            )
            checkpointID = try values.decode(NovelCheckpointID.self, forKey: .checkpointID)
            stateSnapshotID = try values.decode(NovelStateSnapshotID.self, forKey: .stateSnapshotID)
            factCompatibilityID = try values.decode(UUID.self, forKey: .factCompatibilityID)
            source = try values.decodeIfPresent(NovelCollectionSource.self, forKey: .source) ?? .user
        }
    }

    struct SaveManualEdit: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let chapterID: NovelChapterID
        let versionID: NovelChapterVersionID
        let title: String
        let content: String
        let factCompatibilityID: UUID
    }

    struct SyncManualEdits: Codable {
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let pendingID: NovelPendingOperationID
        let checkpointID: NovelCheckpointID
        let stateSnapshotID: NovelStateSnapshotID
    }

    struct RetryPending: Codable {
        let projectID: NovelProjectID
        let pendingID: NovelPendingOperationID
    }

    struct ImportProject: Codable {
        let projectID: NovelProjectID
        let packageSHA256: String
        let policy: ImportPolicy
    }

    enum ImportPolicy: Codable {
        case reject
        case replace
        case keepBoth(destinationProjectID: NovelProjectID)
    }

    struct DeleteProject: Codable {
        let projectID: NovelProjectID
    }

    struct RestorePreviousProject: Codable {
        let projectID: NovelProjectID
    }

    case createProject(CreateProject)
    case renameProject(RenameProject)
    case reviseMaterial(ReviseMaterial)
    case deleteMaterial(DeleteMaterial)
    case setModelPolicy(SetModelPolicy)
    case resolveSettingProposal(ResolveSettingProposal)
    case setBranchMaterialOverride(SetBranchMaterialOverride)
    case setMainBranch(SetMainBranch)
    case setPolishPreference(SetPolishPreference)
    case setCollaborationMode(SetCollaborationMode)
    case setPauseGhostwriteOnBlockingContinuity(SetPauseGhostwriteOnBlockingContinuity)
    case upsertChapterPlan(UpsertChapterPlan)
    case clearChapterPlan(ClearChapterPlan)
    case upsertUpcomingArc(UpsertUpcomingArc)
    case clearUpcomingArc(ClearUpcomingArc)
    case forkBranch(ForkBranch)
    case renameBranch(RenameBranch)
    case deleteBranch(DeleteBranch)
    case undoBranchHead(UndoBranchHead)
    case archiveDiscussion(ArchiveDiscussion)
    case clarifyCharacterIdentity(ClarifyCharacterIdentity)
    case cloneCandidate(CloneCandidate)
    case adoptPolishCandidate(AdoptPolishCandidate)
    case abandonPolishTransaction(AbandonPolishTransaction)
    case restoreChapterVersion(RestoreChapterVersion)
    case discardChapter(ChapterDiscardScope)
    case restoreChapter(ChapterDiscardScope)
    case cancelRun(CancelRun)
    case collectCandidate(CollectCandidate)
    case saveManualEdit(SaveManualEdit)
    case syncManualEdits(SyncManualEdits)
    case retryPending(RetryPending)
    case importProject(ImportProject)
    case restorePreviousProject(RestorePreviousProject)
    case deleteProject(DeleteProject)
}

enum NovelOutcome: Codable, Equatable, Sendable {
    case projectCreated(projectID: NovelProjectID, branchID: NovelBranchID)
    case projectRenamed(projectID: NovelProjectID, revision: Int64)
    case materialRevised(
        projectID: NovelProjectID,
        materialID: NovelMaterialID,
        revisionID: NovelMaterialRevisionID,
        projectRevision: Int64,
        configRevision: Int64
    )
    case materialDeleted(
        projectID: NovelProjectID,
        materialID: NovelMaterialID,
        projectRevision: Int64,
        configRevision: Int64
    )
    case modelPolicyChanged(
        projectID: NovelProjectID,
        projectRevision: Int64,
        configRevision: Int64
    )
    case settingProposalAccepted(
        projectID: NovelProjectID,
        proposalID: NovelProposalID,
        materialID: NovelMaterialID,
        revisionID: NovelMaterialRevisionID,
        projectRevision: Int64,
        configRevision: Int64
    )
    case settingProposalRejected(
        projectID: NovelProjectID,
        proposalID: NovelProposalID,
        projectRevision: Int64,
        configRevision: Int64
    )
    case branchMaterialOverrideChanged(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        materialID: NovelMaterialID,
        revisionID: NovelMaterialRevisionID?,
        projectRevision: Int64,
        configRevision: Int64
    )
    case mainBranchChanged(projectID: NovelProjectID, branchID: NovelBranchID, revision: Int64)
    case polishPreferenceChanged(
        projectID: NovelProjectID,
        projectRevision: Int64,
        configRevision: Int64
    )
    case collaborationModeChanged(
        projectID: NovelProjectID,
        mode: NovelCollaborationMode,
        projectRevision: Int64,
        configRevision: Int64
    )
    case pauseGhostwriteOnBlockingContinuityChanged(
        projectID: NovelProjectID,
        enabled: Bool,
        projectRevision: Int64,
        configRevision: Int64
    )
    case chapterPlanUpserted(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        planID: NovelChapterPlanID,
        status: NovelChapterPlanStatus,
        contentDigest: String,
        projectRevision: Int64,
        configRevision: Int64
    )
    case chapterPlanCleared(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        projectRevision: Int64,
        configRevision: Int64
    )
    case upcomingArcUpserted(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        beatCount: Int,
        projectRevision: Int64,
        configRevision: Int64
    )
    case upcomingArcCleared(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        projectRevision: Int64,
        configRevision: Int64
    )
    case branchForked(
        projectID: NovelProjectID,
        sourceBranchID: NovelBranchID,
        branchID: NovelBranchID,
        checkpointID: NovelCheckpointID,
        revision: Int64
    )
    case branchRenamed(projectID: NovelProjectID, branchID: NovelBranchID, revision: Int64)
    case branchDeleted(projectID: NovelProjectID, branchID: NovelBranchID, revision: Int64)
    case branchHeadMoved(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        fromCheckpointID: NovelCheckpointID,
        toCheckpointID: NovelCheckpointID,
        headRevision: Int64,
        revision: Int64
    )
    case discussionArchived(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        archiveID: NovelMessageID,
        checkpointID: NovelCheckpointID,
        decisionRevisionIDs: [NovelMaterialRevisionID],
        projectRevision: Int64,
        configRevision: Int64
    )
    case characterIdentityClarified(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        mention: String,
        checkpointID: NovelCheckpointID,
        stateSnapshotID: NovelStateSnapshotID,
        revision: Int64
    )
    case candidateCloned(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        sourceCandidateID: NovelCandidateID,
        candidateID: NovelCandidateID,
        revision: Int64
    )
    case polishCandidateAdopted(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID,
        checkpointID: NovelCheckpointID,
        chapterVersionID: NovelChapterVersionID,
        revision: Int64
    )
    case polishCandidateRejected(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID,
        transactionID: NovelPendingOperationID,
        revision: Int64
    )
    case polishTransactionAbandoned(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID,
        transactionID: NovelPendingOperationID,
        revision: Int64
    )
    case chapterDiscardStateChanged(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        chapterID: NovelChapterID,
        isDiscarded: Bool,
        revision: Int64
    )
    case chapterVersionRestored(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        checkpointID: NovelCheckpointID,
        chapterVersionID: NovelChapterVersionID,
        revision: Int64
    )
    case runStarted(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        runID: NovelRunID,
        receiptID: NovelReceiptID,
        revision: Int64
    )
    case runInterrupted(
        projectID: NovelProjectID,
        runID: NovelRunID,
        reason: NovelRunInterruptionReason,
        revision: Int64
    )
    case candidateCollected(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID,
        checkpointID: NovelCheckpointID,
        chapterVersionID: NovelChapterVersionID,
        revision: Int64
    )
    case manualEditSaved(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        chapterVersionID: NovelChapterVersionID,
        workingRevision: Int64,
        revision: Int64
    )
    case manualSyncCommitted(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        checkpointID: NovelCheckpointID,
        revision: Int64
    )
    case projectImported(
        sourceProjectID: NovelProjectID,
        projectID: NovelProjectID,
        disposition: NovelProjectImportDisposition,
        interruptedRunCount: Int,
        revision: Int64
    )
    case previousProjectRestored(projectID: NovelProjectID, revision: Int64)
    case projectDeleted(projectID: NovelProjectID)
}

enum NovelSnapshotScope: Equatable, Sendable {
    case projects
    case project(NovelProjectID)
    case branch(projectID: NovelProjectID, branchID: NovelBranchID)
    case projectPackage(NovelProjectID)
    case branchMarkdown(projectID: NovelProjectID, branchID: NovelBranchID)
    case injectionPreview(NovelInjectionPreviewRequest)
    case projectImportPreview(Data)
}

enum NovelSnapshot: Equatable, Sendable {
    case projects([NovelProjectSummary])
    case project(NovelProjectSnapshot)
    case branch(NovelBranchSnapshot)
    case package(NovelProjectPackageArtifact)
    case markdown(NovelMarkdownExportArtifact)
    case injectionPreview(NovelInjectionPreviewSnapshot)
    case projectImportPreview(NovelProjectImportPreview)
}

enum NovelRunKind: String, Codable, Sendable {
    case quickStart
    case characterProposal
    case discussion
    case prose
    case polish
    /// 整章重新生成。与 `.prose` 的区别是它**必须**携带被重写章节的版本 id
    /// (`sourceChapterVersionID`),与 `.polish` 的区别是它**允许改变剧情事实**
    /// ——不走润色的漂移闸,产出普通 prose 候选,由用户以 `.replaceChapter` 收录。
    case regenerate

    /// 「给输出留多少上下文空间」——**纯本地算术,绝不发给 provider**。
    ///
    /// 与 `NovelStructuredModelTaskKind.outputReservationTokens` 同一语义,分别服务于
    /// 生成侧与结构化侧的输入预算反推。两处的**唯一**职责就是这个;发给模型的硬上限
    /// 已一律取消(`maxOutputTokens: nil`),不要再把这两件事合并回一个字段——那正是
    /// 2026-07-26「模型回复达到输出上限」故障的根源。
    var outputReservationTokens: Int {
        switch self {
        case .prose, .polish, .regenerate: 8_192
        case .quickStart, .characterProposal, .discussion: 4_096
        }
    }
}

struct NovelRunRequest: Equatable, Sendable {
    let id: NovelRunID
    let operationID: NovelOperationID
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let kind: NovelRunKind
    let mode: NovelSessionMode
    let granularity: NovelGenerationGranularity?
    let userText: String
    let userMessageID: NovelMessageID
    let assistantMessageID: NovelMessageID
    let candidateID: NovelCandidateID?
    let generationReceiptID: NovelReceiptID
    let injectionReceiptID: NovelReceiptID
    let sourceChapterVersionID: NovelChapterVersionID?
    let askUserResponse: NovelAskUserResponse?
    let contextualCharacterMention: String?
    let ghostwritePlanID: NovelChapterPlanID?
    let injectionOverrides: NovelInjectionOverrides
    let inputBudgetTokens: Int
    /// 代笔自愈/润修：规划时不带近期会话消息（仍注入合同/状态/要点）。
    let suppressRecentSessionMessages: Bool
    let expectedProjectRevision: Int64
    let expectedConfigRevision: Int64
    let expectedBranchHeadRevision: Int64

    init(
        id: NovelRunID,
        operationID: NovelOperationID,
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        kind: NovelRunKind,
        mode: NovelSessionMode,
        granularity: NovelGenerationGranularity?,
        userText: String,
        userMessageID: NovelMessageID,
        assistantMessageID: NovelMessageID,
        candidateID: NovelCandidateID?,
        generationReceiptID: NovelReceiptID,
        injectionReceiptID: NovelReceiptID,
        sourceChapterVersionID: NovelChapterVersionID?,
        askUserResponse: NovelAskUserResponse? = nil,
        contextualCharacterMention: String? = nil,
        ghostwritePlanID: NovelChapterPlanID? = nil,
        injectionOverrides: NovelInjectionOverrides = .none,
        inputBudgetTokens: Int = 16_000,
        suppressRecentSessionMessages: Bool = false,
        expectedProjectRevision: Int64,
        expectedConfigRevision: Int64,
        expectedBranchHeadRevision: Int64
    ) {
        self.id = id
        self.operationID = operationID
        self.projectID = projectID
        self.branchID = branchID
        self.kind = kind
        self.mode = mode
        self.granularity = granularity
        self.userText = userText
        self.userMessageID = userMessageID
        self.assistantMessageID = assistantMessageID
        self.candidateID = candidateID
        self.generationReceiptID = generationReceiptID
        self.injectionReceiptID = injectionReceiptID
        self.sourceChapterVersionID = sourceChapterVersionID
        self.askUserResponse = askUserResponse
        self.contextualCharacterMention = contextualCharacterMention
        self.ghostwritePlanID = ghostwritePlanID
        self.injectionOverrides = NovelInjectionOverrides(
            forceIncludeMaterialIDs: Array(Set(injectionOverrides.forceIncludeMaterialIDs)).sorted {
                $0.description < $1.description
            },
            forceExcludeMaterialIDs: Array(Set(injectionOverrides.forceExcludeMaterialIDs)).sorted {
                $0.description < $1.description
            }
        )
        self.inputBudgetTokens = inputBudgetTokens
        self.suppressRecentSessionMessages = suppressRecentSessionMessages
        self.expectedProjectRevision = expectedProjectRevision
        self.expectedConfigRevision = expectedConfigRevision
        self.expectedBranchHeadRevision = expectedBranchHeadRevision
    }

    func canonicalPayloadSHA256() throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let payload = NovelCanonicalRunPayload(
            id: id,
            projectID: projectID,
            branchID: branchID,
            kind: kind,
            mode: mode,
            granularity: granularity,
            userText: userText,
            userMessageID: userMessageID,
            assistantMessageID: assistantMessageID,
            candidateID: candidateID,
            generationReceiptID: generationReceiptID,
            injectionReceiptID: injectionReceiptID,
            sourceChapterVersionID: sourceChapterVersionID,
            askUserResponse: askUserResponse,
            contextualCharacterMention: contextualCharacterMention,
            ghostwritePlanID: ghostwritePlanID,
            injectionOverrides: injectionOverrides,
            inputBudgetTokens: inputBudgetTokens,
            suppressRecentSessionMessages: suppressRecentSessionMessages
        )
        let data = try encoder.encode(payload)
        return SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }
}

private struct NovelCanonicalRunPayload: Codable {
    let id: NovelRunID
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let kind: NovelRunKind
    let mode: NovelSessionMode
    let granularity: NovelGenerationGranularity?
    let userText: String
    let userMessageID: NovelMessageID
    let assistantMessageID: NovelMessageID
    let candidateID: NovelCandidateID?
    let generationReceiptID: NovelReceiptID
    let injectionReceiptID: NovelReceiptID
    let sourceChapterVersionID: NovelChapterVersionID?
    let askUserResponse: NovelAskUserResponse?
    let contextualCharacterMention: String?
    let ghostwritePlanID: NovelChapterPlanID?
    let injectionOverrides: NovelInjectionOverrides
    let inputBudgetTokens: Int
    let suppressRecentSessionMessages: Bool
}

struct NovelRunReceipt: Codable, Equatable, Sendable {
    let runID: NovelRunID
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let receiptID: NovelReceiptID
}

struct NovelSessionMessageSnapshot: Codable, Equatable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let message: NovelSessionMessageRecord
}

struct NovelFailure: Codable, Equatable, Sendable {
    let code: String
    let message: String
    let isRetryable: Bool
}

struct NovelDiscussionArchiveDraftDecision: Identifiable, Equatable, Sendable {
    let id: UUID
    var topic: String
    var decision: String
    let relatedMaterialID: NovelMaterialID?
}

struct NovelDiscussionArchiveDraft: Equatable, Sendable {
    let projectID: NovelProjectID
    let branchID: NovelBranchID
    let sessionID: NovelSessionID
    let throughSequence: Int64
    let chapterID: NovelChapterID?
    let summary: String
    let decisions: [NovelDiscussionArchiveDraftDecision]
}

struct NovelRun: Sendable {
    let id: NovelRunID
    let events: AsyncStream<NovelRunEvent>
}

enum NovelRunEvent: Sendable {
    case started(NovelRunReceipt)
    /// Presentation-only thinking stream. Must not be written into manuscript partials.
    case reasoningDelta(String)
    case delta(String)
    case replaced(String)
    case completed(NovelSessionMessageSnapshot)
    case interrupted(NovelSessionMessageSnapshot?)
    case failed(NovelFailure)
    case persistenceBlocked(NovelFailure)
}

protocol NovelCreation: Sendable {
    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot
    func perform(_ action: NovelAction) async throws -> NovelOutcome
    func start(_ request: NovelRunRequest) async throws -> NovelRun
    /// Closes a user-visible run across the pre-durable start and durable runtime boundary.
    /// Success means the requested run can no longer become an active hidden run.
    func interruptRun(_ command: NovelCancelRunCommand) async throws
    func interruptForBackground(
        projectID: NovelProjectID,
        deadline: Date,
        runID: NovelRunID?
    ) async
    /// Reconnects only server-backed runs that were locally detached when iOS
    /// reclaimed background execution time. Non-resumable providers are ignored.
    func resumeDetachedGenerationRuns() async
    func retryPendingTerminal(runID: NovelRunID) async throws
    /// Cancels in-flight fact-sync mutations for a project so UI Stop can interrupt
    /// long model work instead of only cancelling the outer awaiter.
    /// Production implementations must not cancel polish-adopt from this entry.
    func cancelInFlightBackgroundMutations(projectID: NovelProjectID) async
    func distillDiscussionArchive(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        chapterID: NovelChapterID?
    ) async throws -> NovelDiscussionArchiveDraft
    /// 发起前的预估:扫几章、切几块。块数等于这次要发多少个模型请求。
    func planContinuityAudit(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelContinuityAuditPlan
    /// 扫描全书正文,列出前后打架的地方。结果只在内存里,不写进项目文档。
    func auditContinuity(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelContinuityAuditReport
    /// 代笔软门：把尚未收录的整章候选当作下一章，与已有正文一并做连续性检查。不写盘。
    func auditContinuityIncludingCandidate(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID
    ) async throws -> NovelContinuityAuditReport
    /// 代笔验收：用审稿模型核对候选是否满足已确认本章计划。结果不写进项目文档。
    func acceptChapterPlan(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID
    ) async throws -> NovelChapterPlanAcceptanceV1
    /// 代笔多章：自动拟定并确认下一章合同（创作模型）。首章仍须用户确认。
    func proposeAndConfirmNextChapterPlan(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        nextChapterOrdinal: Int,
        previousPlanSummary: String?
    ) async throws -> NovelChapterPlanRecord
    /// 跨进程代笔批进度 sidecar。
    func loadGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelGhostwriteBatchProgressRecord?
    func saveGhostwriteBatchProgress(_ record: NovelGhostwriteBatchProgressRecord) async throws
    func removeGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws
}

extension NovelCreation {
    func loadGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelGhostwriteBatchProgressRecord? {
        _ = projectID
        _ = branchID
        return nil
    }

    func saveGhostwriteBatchProgress(_ record: NovelGhostwriteBatchProgressRecord) async throws {
        _ = record
    }

    func removeGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws {
        _ = projectID
        _ = branchID
    }

    func resumeDetachedGenerationRuns() async {
        // Test doubles and non-live runtimes do not own resumable provider jobs.
    }

    func cancelInFlightBackgroundMutations(projectID: NovelProjectID) async {
        // Default no-op for test doubles that never schedule background mutations.
    }

    func distillDiscussionArchive(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        chapterID: NovelChapterID?
    ) async throws -> NovelDiscussionArchiveDraft {
        throw NovelError.invalidInput("This novel runtime cannot distill discussion archives.")
    }

    func planContinuityAudit(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelContinuityAuditPlan {
        throw NovelError.invalidInput("This novel runtime cannot audit story continuity.")
    }

    func auditContinuity(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelContinuityAuditReport {
        throw NovelError.invalidInput("This novel runtime cannot audit story continuity.")
    }

    func auditContinuityIncludingCandidate(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID
    ) async throws -> NovelContinuityAuditReport {
        throw NovelError.invalidInput("This novel runtime cannot audit story continuity.")
    }

    func acceptChapterPlan(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        candidateID: NovelCandidateID
    ) async throws -> NovelChapterPlanAcceptanceV1 {
        throw NovelError.invalidInput("This novel runtime cannot accept chapter-plan candidates.")
    }

    func proposeAndConfirmNextChapterPlan(
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        nextChapterOrdinal: Int,
        previousPlanSummary: String?
    ) async throws -> NovelChapterPlanRecord {
        throw NovelError.invalidInput("This novel runtime cannot propose chapter plans.")
    }

    func interruptRun(_ command: NovelCancelRunCommand) async throws {
        _ = try await perform(.cancelRun(command))
    }

    func interruptForBackground(projectID: NovelProjectID, deadline: Date) async {
        await interruptForBackground(
            projectID: projectID,
            deadline: deadline,
            runID: nil
        )
    }

    func interruptForBackground(projectID: NovelProjectID) async {
        await interruptForBackground(
            projectID: projectID,
            deadline: Date().addingTimeInterval(5),
            runID: nil
        )
    }
}

enum NovelError: Error, Equatable, Sendable {
    case invalidInput(String)
    case projectNotFound(NovelProjectID)
    case projectAlreadyExists(NovelProjectID)
    case branchNotFound(NovelBranchID)
    case sessionNotFound(NovelSessionID)
    case stateSnapshotNotFound(NovelStateSnapshotID)
    case checkpointNotFound(NovelCheckpointID)
    case runNotFound(NovelRunID)
    case staleProjectRevision(expected: Int64, actual: Int64)
    case staleConfigRevision(expected: Int64, actual: Int64)
    case staleBranchHeadRevision(expected: Int64, actual: Int64)
    case idempotencyConflict(NovelOperationID)
    case immutableRecordConflict(String)
    case invalidDocument([String])
    case corruptedProject(projectID: NovelProjectID, details: String)
    case degradedReadOnly(projectID: NovelProjectID)
    case repositoryFailure(String)
    case storageUnavailable(String)
    case storageIndeterminate(NovelProjectID)
    case invalidRecovery(String)
    case invalidPackage(String)
    case packageTooLarge(maximumBytes: Int)
    case packageChecksumMismatch
    case injectionBudgetExceeded(
        required: Int,
        limit: Int,
        items: [NovelInjectionBudgetItem]
    )
    case modelUnavailable(String)
    case generationUnavailable
    case unsupportedSchema(Int)
    case projectBusy(NovelProjectID)
}

extension NovelError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .invalidInput: "输入内容或当前状态不符合要求，请检查后重试。"
        case .projectNotFound: "找不到这个小说项目。"
        case .projectAlreadyExists: "这个小说项目已经存在。"
        case .branchNotFound: "找不到这个剧情分支。"
        case .sessionNotFound: "找不到这次创作会话。"
        case .stateSnapshotNotFound: "找不到对应的剧情状态。"
        case .checkpointNotFound: "找不到对应的存档点。"
        case .runNotFound: "找不到这次生成记录。"
        case .staleProjectRevision: "项目已发生变化，请刷新后重试。"
        case .staleConfigRevision: "项目设置已发生变化，请刷新后重试。"
        case .staleBranchHeadRevision: "当前分支已发生变化，请刷新后重试。"
        case .idempotencyConflict: "检测到重复但内容不同的操作，请刷新后重试。"
        case .immutableRecordConflict: "历史记录与当前数据冲突，请重新载入项目。"
        case .invalidDocument: "项目数据不完整或不一致，暂时无法继续编辑。"
        case .corruptedProject: "项目文件已损坏，请尝试恢复上一个有效版本。"
        case .degradedReadOnly: "项目已从上一个有效版本恢复，目前只能阅读。"
        case .repositoryFailure: "项目保存失败，请稍后重试。"
        case .storageUnavailable: "暂时无法访问项目存储。"
        case .storageIndeterminate: "无法确认项目是否已保存，请重新载入后再继续。"
        case .invalidRecovery: "项目恢复数据无效。"
        case .invalidPackage: "项目包无效或不完整。"
        case .packageTooLarge(let maximumBytes):
            "项目包超过大小限制（最多 \(maximumBytes) 字节）。"
        case .packageChecksumMismatch: "项目包校验失败，文件可能不完整。"
        case .injectionBudgetExceeded(let required, let limit, _):
            "所需上下文约为 \(required)，超过当前长度限制 \(limit)。"
        case .modelUnavailable: "项目模型当前不可用，请在“设定 > 更多”中重新选择。"
        case .generationUnavailable: "小说生成功能当前不可用。"
        case .unsupportedSchema(let version): "暂不支持版本 \(version) 的小说项目。"
        case .projectBusy: "项目正在处理其他操作。请先停止生成，或稍后再试。"
        }
    }
}
