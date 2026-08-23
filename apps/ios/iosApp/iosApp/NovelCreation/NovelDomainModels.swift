import CryptoKit
import Foundation

struct NovelIdentifier<Tag: Sendable>: RawRepresentable, Codable, Hashable, Sendable, CustomStringConvertible {
    let rawValue: UUID

    init(rawValue: UUID) {
        self.rawValue = rawValue
    }

    init(_ rawValue: UUID = UUID()) {
        self.rawValue = rawValue
    }

    var description: String { rawValue.uuidString.lowercased() }
}

enum NovelProjectIDTag: Sendable {}
enum NovelBranchIDTag: Sendable {}
enum NovelSessionIDTag: Sendable {}
enum NovelMessageIDTag: Sendable {}
enum NovelCandidateIDTag: Sendable {}
enum NovelChapterIDTag: Sendable {}
enum NovelChapterVersionIDTag: Sendable {}
enum NovelMaterialIDTag: Sendable {}
enum NovelMaterialRevisionIDTag: Sendable {}
enum NovelStateSnapshotIDTag: Sendable {}
enum NovelEventIDTag: Sendable {}
enum NovelCheckpointIDTag: Sendable {}
enum NovelOperationIDTag: Sendable {}
enum NovelRunIDTag: Sendable {}
enum NovelPendingOperationIDTag: Sendable {}
enum NovelReceiptIDTag: Sendable {}
enum NovelProposalIDTag: Sendable {}
enum NovelChapterPlanIDTag: Sendable {}

typealias NovelProjectID = NovelIdentifier<NovelProjectIDTag>
typealias NovelBranchID = NovelIdentifier<NovelBranchIDTag>
typealias NovelSessionID = NovelIdentifier<NovelSessionIDTag>
typealias NovelMessageID = NovelIdentifier<NovelMessageIDTag>
typealias NovelCandidateID = NovelIdentifier<NovelCandidateIDTag>
typealias NovelChapterID = NovelIdentifier<NovelChapterIDTag>
typealias NovelChapterVersionID = NovelIdentifier<NovelChapterVersionIDTag>
typealias NovelMaterialID = NovelIdentifier<NovelMaterialIDTag>
typealias NovelMaterialRevisionID = NovelIdentifier<NovelMaterialRevisionIDTag>
typealias NovelStateSnapshotID = NovelIdentifier<NovelStateSnapshotIDTag>
typealias NovelEventID = NovelIdentifier<NovelEventIDTag>
typealias NovelCheckpointID = NovelIdentifier<NovelCheckpointIDTag>
typealias NovelOperationID = NovelIdentifier<NovelOperationIDTag>
typealias NovelRunID = NovelIdentifier<NovelRunIDTag>
typealias NovelPendingOperationID = NovelIdentifier<NovelPendingOperationIDTag>
typealias NovelReceiptID = NovelIdentifier<NovelReceiptIDTag>
typealias NovelProposalID = NovelIdentifier<NovelProposalIDTag>
typealias NovelChapterPlanID = NovelIdentifier<NovelChapterPlanIDTag>

enum NovelProjectCreationMode: String, Codable, CaseIterable, Sendable {
    case blank
    case quickStart
}

/// Project-level collaboration style. Ghostwrite auto-pipeline arrives in Phase 2;
/// Phase 1 persists the mode, enforces readiness, and requires a chapter plan.
enum NovelCollaborationMode: String, Codable, CaseIterable, Sendable {
    case cocreation
    case ghostwrite

    var displayName: String {
        switch self {
        case .cocreation: "共创模式"
        case .ghostwrite: "代笔模式"
        }
    }

    var shortSummary: String {
        switch self {
        case .cocreation: "一起商量，你点收录才进书"
        case .ghostwrite: "先定好这一章要写什么，再按章代笔；过关后自动进书"
        }
    }
}

enum NovelChapterPlanStatus: String, Codable, CaseIterable, Sendable {
    case draft
    case confirmed
}

struct NovelChapterPlanRecord: Codable, Equatable, Sendable {
    let id: NovelChapterPlanID
    let branchID: NovelBranchID
    var status: NovelChapterPlanStatus
    /// Short placement note such as "第 3 章 · 中段转折".
    var outlinePlacement: String
    var goalAndConflict: String
    var mustHappen: [String]
    var mustNotHappen: [String]
    var endingHook: String
    /// Facts the POV is allowed to know in this chapter.
    var visibleFacts: [String]
    var contentDigest: String
    var updatedAt: Date
    var confirmedAt: Date?

    var isConfirmed: Bool { status == .confirmed }

    func canonicalDigestPayload() -> String {
        let must = mustHappen.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
        let mustNot = mustNotHappen.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
        let visible = visibleFacts.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
        return [
            outlinePlacement.trimmingCharacters(in: .whitespacesAndNewlines),
            goalAndConflict.trimmingCharacters(in: .whitespacesAndNewlines),
            must,
            mustNot,
            endingHook.trimmingCharacters(in: .whitespacesAndNewlines),
            visible,
        ].joined(separator: "\n---\n")
    }

    func injectionText() -> String {
        var lines = [
            "Status: \(status.rawValue)",
            "Digest: \(contentDigest)",
            "Placement: \(outlinePlacement)",
            "Goal and conflict:\n\(goalAndConflict)",
        ]
        if !mustHappen.isEmpty {
            lines.append("Must happen:\n" + mustHappen.map { "- \($0)" }.joined(separator: "\n"))
        }
        if !mustNotHappen.isEmpty {
            lines.append("Must not happen:\n" + mustNotHappen.map { "- \($0)" }.joined(separator: "\n"))
        }
        if !endingHook.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            lines.append("Ending hook:\n\(endingHook)")
        }
        if !visibleFacts.isEmpty {
            lines.append(
                "POV-visible facts:\n" + visibleFacts.map { "- \($0)" }.joined(separator: "\n")
            )
        }
        return lines.joined(separator: "\n\n")
    }

    static func digest(forCanonicalPayload payload: String) -> String {
        SHA256.hash(data: Data(payload.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func normalizedLines(_ lines: [String]) -> [String] {
        lines
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }
}

enum NovelGhostwriteReadinessIssue: String, Equatable, Hashable, Sendable, CaseIterable {
    case mainBranchRequired
    case missingMasterOutline
    case missingCharacter
    case missingWritingRequirements
    case branchNeedsSync
    case unresolvedPlot
    case pendingOperations
    case activeRun
    case missingChapterPlan

    var displayName: String {
        switch self {
        case .mainBranchRequired: "代笔仅支持当前主分支"
        case .missingMasterOutline: "缺少总剧情大纲"
        case .missingCharacter: "至少需要一名人物档案"
        case .missingWritingRequirements: "缺少写作要求"
        case .branchNeedsSync: "当前分支资料待同步"
        case .unresolvedPlot: NovelWorkspaceLedger.unresolvedPlotGateMessage
        case .pendingOperations: "仍有未完成的正文或同步操作"
        case .activeRun: "当前还有进行中的生成"
        case .missingChapterPlan: "还没有确认的本章计划"
        }
    }
}

enum NovelGhostwriteReadiness {
    static func issues(
        in document: NovelProjectDocumentV1,
        branchID: NovelBranchID,
        requireChapterPlan: Bool
    ) -> [NovelGhostwriteReadinessIssue] {
        issues(
            materials: document.materials,
            materialRevisions: document.materialRevisions,
            branches: document.branches,
            pendingOperations: document.pendingOperations,
            polishTransactions: document.polishTransactions,
            activeRuns: document.activeRuns,
            chapterPlans: document.chapterPlans,
            stateSnapshots: document.stateSnapshots,
            mainBranchID: document.project.mainBranchID,
            branchID: branchID,
            requireChapterPlan: requireChapterPlan
        )
    }

    static func issues(
        materials: [NovelMaterialRecord],
        materialRevisions: [NovelMaterialRevisionRecord],
        branches: [NovelBranchRecord],
        pendingOperations: [NovelPendingOperationRecord],
        polishTransactions: [NovelPendingPolishTransactionRecord],
        activeRuns: [NovelActiveRunRecord],
        chapterPlans: [NovelChapterPlanRecord],
        stateSnapshots: [NovelStateSnapshotRecord] = [],
        mainBranchID: NovelBranchID,
        branchID: NovelBranchID,
        requireChapterPlan: Bool
    ) -> [NovelGhostwriteReadinessIssue] {
        var issues: [NovelGhostwriteReadinessIssue] = []
        if branchID != mainBranchID {
            issues.append(.mainBranchRequired)
        }
        let activeMaterials = materials.filter { !$0.isDeleted }
        let revisionByID = Dictionary(
            uniqueKeysWithValues: materialRevisions.map { ($0.id, $0) }
        )
        func hasNonEmptyMaterial(kind: NovelMaterialKind) -> Bool {
            activeMaterials.contains { material in
                guard material.kind == kind,
                      let revision = revisionByID[material.currentRevisionID] else { return false }
                return !revision.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
        }
        if !hasNonEmptyMaterial(kind: .masterOutline) {
            issues.append(.missingMasterOutline)
        }
        if !hasNonEmptyMaterial(kind: .character) {
            issues.append(.missingCharacter)
        }
        if !hasNonEmptyMaterial(kind: .writingRequirements) {
            issues.append(.missingWritingRequirements)
        }
        guard let branch = branches.first(where: { $0.id == branchID }) else {
            issues.append(.branchNeedsSync)
            return issues
        }
        if branch.syncStatus != .synchronized {
            issues.append(.branchNeedsSync)
        }
        if let snapshot = stateSnapshots.first(where: { $0.id == branch.currentStateSnapshotID }),
           snapshot.hasStaleChapterPlots {
            issues.append(.unresolvedPlot)
        }
        if pendingOperations.contains(where: { $0.branchID == branchID }) ||
            polishTransactions.contains(where: {
                $0.branchID == branchID &&
                    ($0.status == .pending || $0.status == .retryable || $0.status == .blocked)
            }) {
            issues.append(.pendingOperations)
        }
        if branch.activeRunID != nil ||
            activeRuns.contains(where: {
                $0.branchID == branchID && $0.status == .running
            }) {
            issues.append(.activeRun)
        }
        if requireChapterPlan,
           chapterPlans.first(where: { $0.branchID == branchID && $0.isConfirmed }) == nil {
            issues.append(.missingChapterPlan)
        }
        return issues
    }
}

enum NovelGenerationGranularity: String, Codable, CaseIterable, Sendable {
    case continuation
    case wholeChapter
}

enum NovelProjectModelPolicy: Codable, Equatable, Sendable {
    case global
    /// Stable provider and model UUID strings. `modelID` is never the wire model name.
    case fixed(providerID: String, modelID: String)
}

enum NovelModelRole: String, Codable, CaseIterable, Sendable {
    case creation
    case stateSync
    /// 代笔合同验收与连续性软门；缺省跟随 App 默认（通常为 global）。
    case review
}

struct NovelQuickStartSeed: Codable, Equatable, Sendable {
    let genre: String
    let coreIdea: String
}

/// 分支级「往后几章」有界备注：软上下文，跨多章保留，直到用户改写或清除。
struct NovelUpcomingArcRecord: Codable, Equatable, Sendable {
    static let maxBeats = 8
    static let maxBeatCharacterCount = 160

    let branchID: NovelBranchID
    var beats: [String]
    var updatedAt: Date

    init(branchID: NovelBranchID, beats: [String], updatedAt: Date) {
        self.branchID = branchID
        self.beats = Self.normalizedBeats(beats)
        self.updatedAt = updatedAt
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        branchID = try values.decode(NovelBranchID.self, forKey: .branchID)
        beats = Self.normalizedBeats(try values.decode([String].self, forKey: .beats))
        updatedAt = try values.decode(Date.self, forKey: .updatedAt)
    }

    private enum CodingKeys: String, CodingKey {
        case branchID
        case beats
        case updatedAt
    }

    static func normalizedBeats(_ raw: [String]) -> [String] {
        var seen: Set<String> = []
        var result: [String] = []
        for item in raw {
            let trimmed = item.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            let clipped = trimmed.count > maxBeatCharacterCount
                ? String(trimmed.prefix(maxBeatCharacterCount))
                : trimmed
            let key = clipped.lowercased()
            guard seen.insert(key).inserted else { continue }
            result.append(clipped)
            if result.count >= maxBeats { break }
        }
        return result
    }

    func injectionText() -> String {
        beats.map { "- \($0)" }.joined(separator: "\n")
    }
}

enum NovelMaterialKind: Codable, Equatable, Sendable {
    case world
    case character
    /// Author-approved relationship planning. Runtime relationship facts remain
    /// in `NovelStateSnapshotRecord` and are still derived from manuscript evidence.
    case relationship
    case masterOutline
    case writingRequirements
    case decisionLog
    case custom(String)
}

enum NovelInjectionMode: String, Codable, CaseIterable, Sendable {
    case always
    case smart
    case off
}

enum NovelBranchSyncStatus: String, Codable, Sendable {
    case synchronized
    case needsSync
}

enum NovelBranchLifecycle: String, Codable, Sendable {
    case active
    case deleted
}

enum NovelCheckpointKind: String, Codable, CaseIterable, Sendable {
    case initial
    case collection
    case manualSync
    case discussionArchive
    case identityClarification
    case polish
    case restore
}

enum NovelChapterVersionKind: String, Codable, CaseIterable, Sendable {
    case collected
    case manualEdit
    case polish
    case restore
}

enum NovelSessionRole: String, Codable, Sendable {
    case user
    case assistant
    case system
}

enum NovelSessionMode: String, Codable, Sendable {
    case writeProse
    case discussPlan
}

enum NovelSessionMessageKind: String, Codable, Sendable {
    case userInput
    case discussion
    case proseCandidate
    case polishCandidate
    case interruptedDraft
    case error
}

struct NovelAskUserPrompt: Codable, Equatable, Sendable {
    let question: String
    let options: [String]
    /// Present only for `novel_revise_chapter` approval cards. Ordinary Ask User
    /// prompts leave this nil so historical documents keep decoding.
    let chapterRevision: NovelChapterRevisionProposal?
    /// Present only for `novel_revert_recent_chapters` approval cards.
    let manuscriptRevert: NovelManuscriptRevertProposal?
    /// Present only for `novel_delete_chapters` approval cards.
    let manuscriptDelete: NovelManuscriptDeleteProposal?
    /// Present only for `novel_workspace_write` of plot files.
    let workspacePlot: NovelWorkspacePlotProposal?

    init(
        question: String,
        options: [String],
        chapterRevision: NovelChapterRevisionProposal? = nil,
        manuscriptRevert: NovelManuscriptRevertProposal? = nil,
        manuscriptDelete: NovelManuscriptDeleteProposal? = nil,
        workspacePlot: NovelWorkspacePlotProposal? = nil
    ) {
        self.question = question
        self.options = options
        self.chapterRevision = chapterRevision
        self.manuscriptRevert = manuscriptRevert
        self.manuscriptDelete = manuscriptDelete
        self.workspacePlot = workspacePlot
    }
}

struct NovelWorkspacePlotProposal: Codable, Equatable, Sendable {
    let path: String
    let body: String
    let reason: String?
}

enum NovelWorkspacePlotApproval {
    static let approveOption = "写入剧情"
    static let rejectOption = "拒绝这次修改"
    static let options = [approveOption, rejectOption]
}

enum NovelChapterRevisionApproval {
    static let approveOption = "写入正文"
    static let rejectOption = "拒绝这次修改"
    static let options = [approveOption, rejectOption]
}

struct NovelChapterRevisionProposal: Codable, Equatable, Sendable {
    let chapterID: NovelChapterID
    let chapterOrdinal: Int
    let chapterTitle: String
    let startParagraph: Int
    let endParagraph: Int
    let oldText: String
    let newText: String
    let reason: String?
}

enum NovelManuscriptRevertApproval {
    static let approveOption = "回退这几章"
    static let rejectOption = "取消回退"
    static let options = [approveOption, rejectOption]
}

struct NovelManuscriptRevertProposal: Codable, Equatable, Sendable {
    let chapterCount: Int
    let chapterIDs: [NovelChapterID]
    let chapterTitles: [String]
    let chapterOrdinals: [Int]
    let targetCheckpointID: NovelCheckpointID
    let expectedHeadRevision: Int64
    let expectedWorkingRevision: Int64
    let reason: String?
}

enum NovelManuscriptDeleteApproval {
    static let approveOption = "从正文目录删除"
    static let rejectOption = "取消这次删除"
    static let options = [approveOption, rejectOption]
}

struct NovelManuscriptDeleteProposal: Codable, Equatable, Sendable {
    let chapterIDs: [NovelChapterID]
    let chapterTitles: [String]
    let chapterOrdinals: [Int]
    let expectedHeadRevision: Int64
    let expectedWorkingRevision: Int64
    let reason: String?
}

struct NovelAskUserResponse: Codable, Equatable, Sendable {
    let promptMessageID: NovelMessageID
    let answer: String
}

enum NovelSessionInteraction: Codable, Equatable, Sendable {
    case askUser(NovelAskUserPrompt)
    case askUserAnswer(NovelAskUserResponse)
}

enum NovelCandidateKind: String, Codable, Sendable {
    case prose
    case polish
}

enum NovelCandidateStatus: String, Codable, Sendable {
    case available
    case collected
    case adopted
    case interrupted
    case superseded
    case inheritedReadOnly
}

enum NovelRunStatus: String, Codable, Sendable {
    case running
    case completed
    case interrupted
    case failed
}

enum NovelRunInterruptionReason: String, Codable, Sendable {
    case user
    case background
    case routeExit
    case expiration
    case recovery
}

enum NovelPendingOperationKind: String, Codable, Sendable {
    case collection
    case manualSync
}

enum NovelPendingOperationStatus: String, Codable, Sendable {
    case pending
    case retryable
}

enum NovelCollectionTarget: Codable, Equatable, Sendable {
    case appendToChapter(NovelChapterID)
    /// 整章重新生成:用候选内容**替换**该章正文,而不是追加到末尾。
    /// 版本类型仍是 `.collected`,因此必须另起事实兼容链——这正是
    /// 「允许改变剧情事实」的形式化表达(见 NovelCompatibilityLineageValidator)。
    case replaceChapter(NovelChapterID)
    case createNextChapter(chapterID: NovelChapterID, title: String)
}

struct NovelProjectRecord: Codable, Equatable, Sendable {
    let id: NovelProjectID
    var name: String
    let creationMode: NovelProjectCreationMode
    let quickStartSeed: NovelQuickStartSeed?
    let createdAt: Date
    var updatedAt: Date
    var revision: Int64
    var configRevision: Int64
    var mainBranchID: NovelBranchID
    var modelPolicy: NovelProjectModelPolicy
    var stateSyncModelPolicy: NovelProjectModelPolicy?
    var reviewModelPolicy: NovelProjectModelPolicy?
    var lastGenerationGranularity: NovelGenerationGranularity
    var polishPreference: String
    var collaborationMode: NovelCollaborationMode
    /// 代笔：连续性检查出现 blocking（界面「严重」）时暂停自动收录。默认开启。
    var pauseGhostwriteOnBlockingContinuity: Bool

    init(
        id: NovelProjectID,
        name: String,
        creationMode: NovelProjectCreationMode,
        quickStartSeed: NovelQuickStartSeed?,
        createdAt: Date,
        updatedAt: Date,
        revision: Int64,
        configRevision: Int64,
        mainBranchID: NovelBranchID,
        modelPolicy: NovelProjectModelPolicy,
        stateSyncModelPolicy: NovelProjectModelPolicy?,
        lastGenerationGranularity: NovelGenerationGranularity,
        polishPreference: String,
        collaborationMode: NovelCollaborationMode = .cocreation,
        pauseGhostwriteOnBlockingContinuity: Bool = true,
        reviewModelPolicy: NovelProjectModelPolicy? = nil
    ) {
        self.id = id
        self.name = name
        self.creationMode = creationMode
        self.quickStartSeed = quickStartSeed
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.revision = revision
        self.configRevision = configRevision
        self.mainBranchID = mainBranchID
        self.modelPolicy = modelPolicy
        self.stateSyncModelPolicy = stateSyncModelPolicy
        self.reviewModelPolicy = reviewModelPolicy
        self.lastGenerationGranularity = lastGenerationGranularity
        self.polishPreference = polishPreference
        self.collaborationMode = collaborationMode
        self.pauseGhostwriteOnBlockingContinuity = pauseGhostwriteOnBlockingContinuity
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case name
        case creationMode
        case quickStartSeed
        case createdAt
        case updatedAt
        case revision
        case configRevision
        case mainBranchID
        case modelPolicy
        case stateSyncModelPolicy
        case reviewModelPolicy
        case lastGenerationGranularity
        case polishPreference
        case collaborationMode
        case pauseGhostwriteOnBlockingContinuity
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(NovelProjectID.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        creationMode = try values.decode(NovelProjectCreationMode.self, forKey: .creationMode)
        quickStartSeed = try values.decodeIfPresent(NovelQuickStartSeed.self, forKey: .quickStartSeed)
        createdAt = try values.decode(Date.self, forKey: .createdAt)
        updatedAt = try values.decode(Date.self, forKey: .updatedAt)
        revision = try values.decode(Int64.self, forKey: .revision)
        configRevision = try values.decode(Int64.self, forKey: .configRevision)
        mainBranchID = try values.decode(NovelBranchID.self, forKey: .mainBranchID)
        modelPolicy = try values.decode(NovelProjectModelPolicy.self, forKey: .modelPolicy)
        stateSyncModelPolicy = try values.decodeIfPresent(
            NovelProjectModelPolicy.self,
            forKey: .stateSyncModelPolicy
        )
        reviewModelPolicy = try values.decodeIfPresent(
            NovelProjectModelPolicy.self,
            forKey: .reviewModelPolicy
        )
        lastGenerationGranularity = try values.decode(
            NovelGenerationGranularity.self,
            forKey: .lastGenerationGranularity
        )
        polishPreference = try values.decode(String.self, forKey: .polishPreference)
        collaborationMode = try values.decodeIfPresent(
            NovelCollaborationMode.self,
            forKey: .collaborationMode
        ) ?? .cocreation
        pauseGhostwriteOnBlockingContinuity = try values.decodeIfPresent(
            Bool.self,
            forKey: .pauseGhostwriteOnBlockingContinuity
        ) ?? true
    }

    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(id, forKey: .id)
        try values.encode(name, forKey: .name)
        try values.encode(creationMode, forKey: .creationMode)
        try values.encodeIfPresent(quickStartSeed, forKey: .quickStartSeed)
        try values.encode(createdAt, forKey: .createdAt)
        try values.encode(updatedAt, forKey: .updatedAt)
        try values.encode(revision, forKey: .revision)
        try values.encode(configRevision, forKey: .configRevision)
        try values.encode(mainBranchID, forKey: .mainBranchID)
        try values.encode(modelPolicy, forKey: .modelPolicy)
        try values.encodeIfPresent(stateSyncModelPolicy, forKey: .stateSyncModelPolicy)
        try values.encodeIfPresent(reviewModelPolicy, forKey: .reviewModelPolicy)
        try values.encode(lastGenerationGranularity, forKey: .lastGenerationGranularity)
        try values.encode(polishPreference, forKey: .polishPreference)
        try values.encode(collaborationMode, forKey: .collaborationMode)
        try values.encode(
            pauseGhostwriteOnBlockingContinuity,
            forKey: .pauseGhostwriteOnBlockingContinuity
        )
    }

    func configuredModelPolicy(for purpose: NovelModelRole) -> NovelProjectModelPolicy {
        switch purpose {
        case .creation: modelPolicy
        case .stateSync: stateSyncModelPolicy ?? .global
        case .review: reviewModelPolicy ?? .global
        }
    }
}

struct NovelMaterialRecord: Codable, Equatable, Sendable {
    let id: NovelMaterialID
    let kind: NovelMaterialKind
    var currentRevisionID: NovelMaterialRevisionID
    var revisionIDs: [NovelMaterialRevisionID]
    var isDeleted: Bool
    var aliases: [String]

    init(
        id: NovelMaterialID,
        kind: NovelMaterialKind,
        currentRevisionID: NovelMaterialRevisionID,
        revisionIDs: [NovelMaterialRevisionID],
        isDeleted: Bool = false,
        aliases: [String] = []
    ) {
        self.id = id
        self.kind = kind
        self.currentRevisionID = currentRevisionID
        self.revisionIDs = revisionIDs
        self.isDeleted = isDeleted
        self.aliases = NovelCharacterIdentityResolver.normalizedAliases(aliases)
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case kind
        case currentRevisionID
        case revisionIDs
        case isDeleted
        case aliases
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(NovelMaterialID.self, forKey: .id)
        kind = try values.decode(NovelMaterialKind.self, forKey: .kind)
        currentRevisionID = try values.decode(
            NovelMaterialRevisionID.self,
            forKey: .currentRevisionID
        )
        revisionIDs = try values.decode([NovelMaterialRevisionID].self, forKey: .revisionIDs)
        isDeleted = try values.decodeIfPresent(Bool.self, forKey: .isDeleted) ?? false
        aliases = NovelCharacterIdentityResolver.normalizedAliases(
            try values.decodeIfPresent([String].self, forKey: .aliases) ?? []
        )
    }

    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(id, forKey: .id)
        try values.encode(kind, forKey: .kind)
        try values.encode(currentRevisionID, forKey: .currentRevisionID)
        try values.encode(revisionIDs, forKey: .revisionIDs)
        try values.encode(isDeleted, forKey: .isDeleted)
        try values.encode(aliases, forKey: .aliases)
    }
}

struct NovelCharacterIdentity: Equatable, Sendable {
    let materialID: NovelMaterialID
    let canonicalName: String
    let aliases: [String]
}

struct NovelCharacterIdentityResolver: Sendable {
    private let materialIDsByNormalizedName: [String: Set<NovelMaterialID>]

    init(identities: [NovelCharacterIdentity]) {
        var matches: [String: Set<NovelMaterialID>] = [:]
        for identity in identities {
            for name in [identity.canonicalName] + identity.aliases {
                let normalized = Self.normalize(name)
                guard !normalized.isEmpty else { continue }
                matches[normalized, default: []].insert(identity.materialID)
            }
        }
        materialIDsByNormalizedName = matches
    }

    func isKnown(_ name: String) -> Bool {
        materialIDsByNormalizedName[Self.normalize(name)]?.count == 1
    }

    static func normalize(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
    }

    static func normalizedAliases(_ aliases: [String]) -> [String] {
        var seen: Set<String> = []
        return aliases.compactMap { alias in
            let trimmed = alias.trimmingCharacters(in: .whitespacesAndNewlines)
            let key = normalize(trimmed)
            guard !key.isEmpty, seen.insert(key).inserted else { return nil }
            return trimmed
        }
    }

    /// Whether a free-text mention should ever surface as a **character identity**
    /// clarification card. Places, pure office/role titles (军需官), and generic
    /// crowd labels must not ask "对应哪位角色". Real personal names and
    /// surname+title forms (赵将军) stay eligible so they can alias to a dossier.
    static func isLikelyCharacterIdentityCandidate(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else { return false }
        if looksLikeChineseToponymOrInstitution(trimmed) { return false }
        if looksLikePolityOrRegime(trimmed) { return false }
        if looksLikeLatinToponymOrInstitution(trimmed) { return false }
        if looksLikePureOfficeOrRoleTitle(trimmed) { return false }
        if looksLikeGenericCrowdLabel(trimmed) { return false }
        return true
    }

    /// High-confidence Chinese place / institution endings (澶州、汴京、开封府).
    /// Keep the set tight: do not include 山/河/江 alone — they appear in person names.
    private static let chinesePlaceOrInstitutionSuffixes: [String] = [
        "特别行政区", "自治区", "自治州", "自治县",
        "省", "市", "州", "府", "县", "郡", "镇", "乡", "村", "庄", "堡", "寨",
        "国", "邦", "京", "都",
        "路", "街", "巷", "道",
        "关", "津", "渡", "湾", "港", "岛", "洲",
        "寺", "庙", "宫", "殿", "观", "庵",
        "衙", "署", "监", "营", "卫",
    ]

    private static func looksLikeChineseToponymOrInstitution(_ name: String) -> Bool {
        for suffix in chinesePlaceOrInstitutionSuffixes where name.hasSuffix(suffix) {
            // Suffix alone is not a mention; require a stem (澶+州, 汴+京).
            if name.count > suffix.count { return true }
        }
        return false
    }

    /// Dynasties, regimes, and ethnic polities used as places (契丹、北汉).
    /// Suffix rules miss these because they have no 州/京/府 ending.
    private static let namedPolitiesAndRegimes: Set<String> = [
        "契丹", "女真", "蒙古", "回鹘", "吐蕃", "党项", "渤海",
        "高丽", "新罗", "百济", "安南", "交趾",
        "西夏", "大理", "大辽", "大金", "大蒙古",
        "吴越", "荆南", "北齐", "东魏", "西魏",
    ]

    private static let dynastyDirectionPrefixes: Set<Character> = [
        "南", "北", "东", "西", "后", "前", "大",
    ]

    private static let dynastyStems: Set<Character> = [
        "汉", "唐", "宋", "齐", "周", "魏", "晋", "梁", "楚", "吴", "越", "辽", "金", "元", "明", "清",
    ]

    private static func looksLikePolityOrRegime(_ name: String) -> Bool {
        if namedPolitiesAndRegimes.contains(name) { return true }
        if name.count == 2,
           let prefix = name.first,
           let stem = name.last,
           dynastyDirectionPrefixes.contains(prefix),
           dynastyStems.contains(stem) {
            return true
        }
        if name.count == 2, name.hasSuffix("朝"),
           let stem = name.first, dynastyStems.contains(stem) {
            return true
        }
        return false
    }

    private static let latinPlaceOrInstitutionSuffixes: [String] = [
        " city", " county", " province", " kingdom", " empire", " republic",
        " street", " road", " avenue", " mountain", " river", " lake", " sea",
        " island", " bay", " harbor", " harbour", " castle", " fort", " temple",
        " palace", " abbey", " cathedral", " monastery",
    ]

    private static func looksLikeLatinToponymOrInstitution(_ name: String) -> Bool {
        let lowered = name.lowercased()
        return latinPlaceOrInstitutionSuffixes.contains { lowered.hasSuffix($0) }
    }

    /// Exact one-off office / role strings that are not personal identities.
    private static let pureRoleTitles: Set<String> = [
        "军需官", "粮草官", "转运使", "县令", "知县", "知府", "知州", "通判", "主簿", "县尉",
        "推官", "参军", "将军", "元帅", "都督", "节度使", "都头", "押司", "虞候", "教头",
        "都监", "提辖", "团练", "百户", "千户", "万户", "指挥使", "都指挥使",
        "侍卫", "侍女", "丫鬟", "太监", "公公", "掌柜", "店小二", "小二",
        "脚夫", "车夫", "船夫", "水手", "探子", "细作", "小厮", "家丁", "护院",
        "门子", "更夫", "厨子", "账房", "捕快", "差役", "衙役", "士卒", "兵士",
        "亲兵", "护卫", "驿卒", "信使", "使者", "商贾", "贩子", "路人", "行人",
        "客人", "来人", "某人", "那人", "此人", "对方", "众人", "群臣", "百官",
        "士兵", "兵丁", "校尉", "都尉", "司马", "太尉", "丞相", "宰相", "尚书",
        "侍郎", "给事中", "中书令", "枢密使", "宣徽使", "内侍", "内官",
    ]

    /// Longer suffixes first so 军需官 wins over bare 官.
    private static let roleTitleSuffixes: [String] = [
        "都指挥使", "节度使", "指挥使", "军需官", "粮草官", "转运使",
        "知州", "知府", "知县", "通判", "主簿", "县尉", "推官", "参军",
        "将军", "元帅", "都督", "都头", "押司", "虞候", "教头", "都监", "提辖", "团练",
        "侍卫", "侍女", "丫鬟", "太监", "掌柜", "小二", "脚夫", "车夫", "船夫",
        "探子", "细作", "小厮", "家丁", "护院", "门子", "更夫", "厨子", "账房",
        "捕快", "差役", "衙役", "士卒", "兵士", "亲兵", "护卫", "驿卒", "信使",
        "校尉", "都尉", "司马", "太尉", "丞相", "宰相", "尚书", "侍郎",
        "大人", "老爷", "夫人", "小姐", "公子", "殿下", "陛下", "官家",
        "师父", "师傅", "道人", "和尚", "尼姑", "先生",
        "县令", "使者",
        // Short occupational endings: only with non-person stems (see below).
        "官", "令", "丞", "尉", "吏", "使", "卒", "兵",
    ]

    private static let commonChineseSurnames: Set<Character> = Set(
        "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳酆鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁单杭洪包诸左石崔吉钮龚程嵇邢滑裴陆荣翁荀羊於惠甄曲家封芮羿储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘钭厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲邰从鄂索咸籍赖卓蔺屠蒙池乔阴郁胥能苍双闻莘党翟谭贡劳逄姬申扶堵冉宰郦雍却璩桑桂濮牛寿通边扈燕冀郏浦尚农温别庄晏柴瞿阎充慕连茹习宦艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公"
            .map { $0 }
    )

    private static let nonPersonRoleStems: Set<String> = [
        "殿前", "城门", "城中", "城外", "军中", "营中", "营前", "帐前", "帐下",
        "府中", "衙门", "衙内", "路上", "路边", "店中", "店里", "村中", "乡里",
        "禁中", "宫中", "宫里", "朝中", "朝堂", "堂上", "门外", "门内",
        "左右", "两侧", "周围", "附近", "当地", "本城", "本州", "本府", "本县",
        "敌军", "官军", "宋军", "汉军", "辽军", "唐军", "明军",
    ]

    private static func looksLikePureOfficeOrRoleTitle(_ name: String) -> Bool {
        if pureRoleTitles.contains(name) { return true }
        for suffix in roleTitleSuffixes {
            guard name.hasSuffix(suffix), name.count >= suffix.count else { continue }
            let stem = String(name.dropLast(suffix.count))
            if stem.isEmpty {
                // Bare title: 将军 / 军需官
                return true
            }
            if nonPersonRoleStems.contains(stem) {
                // 殿前军需官 / 城门守卒-style compounds without a person name.
                return true
            }
            // 赵将军 / 李知县 → personal reference; keep for alias matching.
            if stem.count == 1, let ch = stem.first, commonChineseSurnames.contains(ch) {
                return false
            }
            // Multi-character stem that looks like a real name → keep.
            if stem.count >= 2, looksLikePersonalNameStem(stem) {
                return false
            }
            // Title-ish compound with non-name stem (e.g. 北门守卒 if 守卒 in suffixes).
            if suffix.count >= 2 { return true }
        }
        return false
    }

    private static func looksLikePersonalNameStem(_ stem: String) -> Bool {
        guard let first = stem.first else { return false }
        // Common pattern: surname + 1–2 given-name chars.
        if commonChineseSurnames.contains(first), (2...3).contains(stem.count) {
            return true
        }
        // Latin personal token.
        if stem.unicodeScalars.allSatisfy({ CharacterSet.letters.contains($0) || $0 == "-" || $0 == "'" }),
           stem.count >= 2 {
            return true
        }
        return false
    }

    private static let genericCrowdLabels: Set<String> = [
        "路人", "行人", "众人", "群臣", "百官", "士兵", "兵丁", "来人", "客人",
        "某人", "那人", "此人", "对方", "百姓", "乡民", "村民", "市民", "官员",
        "将士", "将领", "部将", "手下", "随从", "侍从", "宾客", "僚属",
    ]

    private static func looksLikeGenericCrowdLabel(_ name: String) -> Bool {
        genericCrowdLabels.contains(name)
    }

    /// Deterministic best existing-character match for a free mention.
    /// Used as the identity-card one-tap default (no network). `nil` when no
    /// candidate clears the confidence floor — then the UI only offers create/ignore.
    static func recommendedIdentityMatch(
        mention: String,
        candidates: [(id: String, title: String, aliases: [String])]
    ) -> (id: String, title: String, score: Int)? {
        let mentionKey = normalize(mention)
        guard !mentionKey.isEmpty, isLikelyCharacterIdentityCandidate(mention) else { return nil }
        var best: (id: String, title: String, score: Int)?
        for candidate in candidates {
            let names = ([candidate.title] + candidate.aliases)
                .map { normalize($0) }
                .filter { !$0.isEmpty }
            guard !names.isEmpty else { continue }
            let score = names.map { identityMatchScore(mention: mentionKey, candidate: $0) }.max() ?? 0
            guard score >= identityRecommendationMinimumScore else { continue }
            if let current = best {
                if score > current.score ||
                    (score == current.score &&
                        candidate.title.localizedStandardCompare(current.title) == .orderedAscending) {
                    best = (candidate.id, candidate.title, score)
                }
            } else {
                best = (candidate.id, candidate.title, score)
            }
        }
        return best
    }

    /// Floor for showing a one-tap default. Below this, force explicit choice / create.
    static let identityRecommendationMinimumScore = 40

    static func identityMatchScore(mention: String, candidate: String) -> Int {
        if mention == candidate { return 1_000 }
        if mention.count >= 2, candidate.hasSuffix(mention) { return 520 }
        if candidate.count >= 2, mention.hasSuffix(candidate) { return 500 }
        if mention.count >= 2, candidate.contains(mention) { return 360 }
        if candidate.count >= 2, mention.contains(candidate) { return 340 }
        // Shared leading surname / token for CJK or Latin names.
        // Bare same-surname alone must stay BELOW the one-tap floor so
        // “赵云” does not force-confirm as “赵匡胤”.
        if mention.count >= 2, candidate.count >= 2,
           mention.prefix(1) == candidate.prefix(1) {
            let sharedTail = sharedSuffixLength(mention, candidate)
            if sharedTail >= 2 { return 220 + sharedTail * 10 }
            return 30
        }
        let shared = sharedCharacterCount(mention, candidate)
        if shared >= 2 {
            let ratio = Double(shared) / Double(max(mention.count, candidate.count))
            return Int(60.0 + ratio * 80.0)
        }
        return 0
    }

    private static func sharedSuffixLength(_ a: String, _ b: String) -> Int {
        var count = 0
        var ai = a.endIndex
        var bi = b.endIndex
        while ai > a.startIndex, bi > b.startIndex {
            ai = a.index(before: ai)
            bi = b.index(before: bi)
            guard a[ai] == b[bi] else { break }
            count += 1
        }
        return count
    }

    private static func sharedCharacterCount(_ a: String, _ b: String) -> Int {
        var bag: [Character: Int] = [:]
        for ch in a { bag[ch, default: 0] += 1 }
        var shared = 0
        for ch in b {
            guard let n = bag[ch], n > 0 else { continue }
            bag[ch] = n - 1
            shared += 1
        }
        return shared
    }
}

/// Derived-state setting proposals: keep recurring story-defining cards,
/// drop one-scene furniture (粮仓、谁家后院).
enum NovelSettingProposalFilter: Sendable {
    static func isWorthProposing(title: String, content: String = "") -> Bool {
        _ = content
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else { return false }
        return !isSceneDressingTitle(trimmed)
    }

    static func shouldSurface(_ proposal: NovelSettingProposalRecord) -> Bool {
        switch proposal.origin {
        case .some(.quickStart), .some(.contextualCharacter):
            return true
        case .some(.derivedState), nil:
            return isWorthProposing(title: proposal.title, content: proposal.content)
        }
    }

    private static let sceneDressingTitles: Set<String> = [
        "粮仓", "马厩", "马棚", "牛棚", "猪圈", "鸡窝",
        "厢房", "耳房", "偏房", "后院", "前院", "前厅", "正厅", "堂屋",
        "灶房", "厨房", "柴房", "茅厕", "茅房", "井台", "水井",
        "库房", "地窖", "阁楼", "客房", "酒窖", "柴堆",
        "院子", "宅院", "门房", "门楼", "庭院", "谁家",
    ]

    private static let sceneDressingSuffixes: [String] = [
        "粮仓", "马厩", "马棚", "后院", "前院", "厢房", "灶房", "厨房",
        "柴房", "茅厕", "院子", "宅院", "庭院", "门房",
    ]

    private static func isSceneDressingTitle(_ title: String) -> Bool {
        if sceneDressingTitles.contains(title) { return true }
        if title.count == 2, title.hasSuffix("家") { return true }
        for suffix in sceneDressingSuffixes where title.hasSuffix(suffix) && title.count > suffix.count {
            let stem = String(title.dropLast(suffix.count)).replacingOccurrences(of: "的", with: "")
            if stem.hasSuffix("家") || stem.count <= 2 { return true }
        }
        return false
    }
}

struct NovelMaterialRevisionRecord: Codable, Equatable, Sendable {
    let id: NovelMaterialRevisionID
    let materialID: NovelMaterialID
    let revision: Int64
    let title: String
    let content: String
    let tags: [String]
    let injectionMode: NovelInjectionMode
    let createdAt: Date
    let operationID: NovelOperationID
}

struct NovelForkOrigin: Codable, Equatable, Sendable {
    let parentBranchID: NovelBranchID
    let checkpointID: NovelCheckpointID
}

struct NovelCharacterIdentityClarificationRecord: Codable, Equatable, Sendable {
    let mention: String
    let clarification: String
    let operationID: NovelOperationID
    let createdAt: Date
}

struct NovelBranchRecord: Codable, Equatable, Sendable {
    let id: NovelBranchID
    var name: String
    let sessionID: NovelSessionID
    let createdAt: Date
    var updatedAt: Date
    var forkOrigin: NovelForkOrigin?
    var headCheckpointID: NovelCheckpointID
    var currentStateSnapshotID: NovelStateSnapshotID
    var headRevision: Int64
    var workingRevision: Int64
    var syncStatus: NovelBranchSyncStatus
    var lifecycle: NovelBranchLifecycle
    var overrideRevisionIDs: [NovelMaterialRevisionID]
    var workingChapterSelections: [NovelChapterSelection]
    var activeRunID: NovelRunID?
}

struct NovelDiscussionArchiveRecord: Codable, Equatable, Sendable {
    let id: NovelMessageID
    let checkpointID: NovelCheckpointID
    let throughSequence: Int64
    let messageCount: Int
    let chapterID: NovelChapterID?
    let summary: String
    let createdAt: Date
}

struct NovelSessionRecord: Codable, Equatable, Sendable {
    let id: NovelSessionID
    let branchID: NovelBranchID
    var revision: Int64
    var messages: [NovelSessionMessageRecord]
    var archiveCursor: NovelSessionCursor? = nil
    var discussionArchives: [NovelDiscussionArchiveRecord]? = nil
}

struct NovelSessionMessageRecord: Codable, Equatable, Sendable {
    let id: NovelMessageID
    let sequence: Int64
    let role: NovelSessionRole
    let mode: NovelSessionMode
    let kind: NovelSessionMessageKind
    let content: String
    let createdAt: Date
    let runID: NovelRunID?
    let candidateID: NovelCandidateID?
    let interaction: NovelSessionInteraction?

    init(
        id: NovelMessageID,
        sequence: Int64,
        role: NovelSessionRole,
        mode: NovelSessionMode,
        kind: NovelSessionMessageKind,
        content: String,
        createdAt: Date,
        runID: NovelRunID?,
        candidateID: NovelCandidateID?,
        interaction: NovelSessionInteraction? = nil
    ) {
        self.id = id
        self.sequence = sequence
        self.role = role
        self.mode = mode
        self.kind = kind
        self.content = content
        self.createdAt = createdAt
        self.runID = runID
        self.candidateID = candidateID
        self.interaction = interaction
    }
}

struct NovelCandidateRecord: Codable, Equatable, Sendable {
    let id: NovelCandidateID
    let kind: NovelCandidateKind
    let branchID: NovelBranchID
    let sessionID: NovelSessionID
    let sourceMessageID: NovelMessageID
    let baseCheckpointID: NovelCheckpointID
    let baseHeadRevision: Int64
    var status: NovelCandidateStatus
    let content: String
    let sourceChapterVersionID: NovelChapterVersionID?
    let clonedFromCandidateID: NovelCandidateID?
    var collectedCheckpointID: NovelCheckpointID?
    /// Digest of the confirmed chapter plan bound when this prose candidate was generated.
    /// Nil for legacy candidates and non-prose kinds. Collect rejects a mismatch.
    let chapterPlanDigest: String?
    /// Set only for candidates produced by the ghostwrite pipeline.
    let ghostwritePlanID: NovelChapterPlanID?
    let createdAt: Date

    init(
        id: NovelCandidateID,
        kind: NovelCandidateKind,
        branchID: NovelBranchID,
        sessionID: NovelSessionID,
        sourceMessageID: NovelMessageID,
        baseCheckpointID: NovelCheckpointID,
        baseHeadRevision: Int64,
        status: NovelCandidateStatus,
        content: String,
        sourceChapterVersionID: NovelChapterVersionID?,
        clonedFromCandidateID: NovelCandidateID? = nil,
        collectedCheckpointID: NovelCheckpointID?,
        chapterPlanDigest: String? = nil,
        ghostwritePlanID: NovelChapterPlanID? = nil,
        createdAt: Date
    ) {
        self.id = id
        self.kind = kind
        self.branchID = branchID
        self.sessionID = sessionID
        self.sourceMessageID = sourceMessageID
        self.baseCheckpointID = baseCheckpointID
        self.baseHeadRevision = baseHeadRevision
        self.status = status
        self.content = content
        self.sourceChapterVersionID = sourceChapterVersionID
        self.clonedFromCandidateID = clonedFromCandidateID
        self.collectedCheckpointID = collectedCheckpointID
        self.chapterPlanDigest = chapterPlanDigest
        self.ghostwritePlanID = ghostwritePlanID
        self.createdAt = createdAt
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case kind
        case branchID
        case sessionID
        case sourceMessageID
        case baseCheckpointID
        case baseHeadRevision
        case status
        case content
        case sourceChapterVersionID
        case clonedFromCandidateID
        case collectedCheckpointID
        case chapterPlanDigest
        case ghostwritePlanID
        case createdAt
    }

    init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(NovelCandidateID.self, forKey: .id)
        kind = try values.decode(NovelCandidateKind.self, forKey: .kind)
        branchID = try values.decode(NovelBranchID.self, forKey: .branchID)
        sessionID = try values.decode(NovelSessionID.self, forKey: .sessionID)
        sourceMessageID = try values.decode(NovelMessageID.self, forKey: .sourceMessageID)
        baseCheckpointID = try values.decode(NovelCheckpointID.self, forKey: .baseCheckpointID)
        baseHeadRevision = try values.decode(Int64.self, forKey: .baseHeadRevision)
        status = try values.decode(NovelCandidateStatus.self, forKey: .status)
        content = try values.decode(String.self, forKey: .content)
        sourceChapterVersionID = try values.decodeIfPresent(
            NovelChapterVersionID.self,
            forKey: .sourceChapterVersionID
        )
        clonedFromCandidateID = try values.decodeIfPresent(
            NovelCandidateID.self,
            forKey: .clonedFromCandidateID
        )
        collectedCheckpointID = try values.decodeIfPresent(
            NovelCheckpointID.self,
            forKey: .collectedCheckpointID
        )
        chapterPlanDigest = try values.decodeIfPresent(String.self, forKey: .chapterPlanDigest)
        ghostwritePlanID = try values.decodeIfPresent(NovelChapterPlanID.self, forKey: .ghostwritePlanID)
        createdAt = try values.decode(Date.self, forKey: .createdAt)
    }
}

struct NovelChapterRecord: Codable, Equatable, Sendable {
    let id: NovelChapterID
    let createdAt: Date
    /// 非 nil 表示该章已被标记废弃:不参与生成上下文与剧情状态推导,但保留在
    /// 存档里、可以恢复。用可选字段而不是真删,是因为章节版本之间有
    /// `factCompatibilityID` 事实链(见 `NovelCompatibilityLineageValidator`),
    /// 真删中间一章会断链并让后续章节的剧情状态失去依据。
    /// 老存档缺这个键:合成 Codable 对可选属性走 decodeIfPresent,可平滑读入。
    var discardedAt: Date?
}

struct NovelChapterVersionRecord: Codable, Equatable, Sendable {
    let id: NovelChapterVersionID
    let chapterID: NovelChapterID
    let kind: NovelChapterVersionKind
    let title: String
    let content: String
    let factCompatibilityID: UUID
    let sourceChapterVersionID: NovelChapterVersionID?
    let sourceCandidateID: NovelCandidateID?
    let createdAt: Date
    let operationID: NovelOperationID

    init(
        id: NovelChapterVersionID,
        chapterID: NovelChapterID,
        kind: NovelChapterVersionKind,
        title: String,
        content: String,
        factCompatibilityID: UUID,
        sourceChapterVersionID: NovelChapterVersionID? = nil,
        sourceCandidateID: NovelCandidateID?,
        createdAt: Date,
        operationID: NovelOperationID
    ) {
        self.id = id
        self.chapterID = chapterID
        self.kind = kind
        self.title = title
        self.content = content
        self.factCompatibilityID = factCompatibilityID
        self.sourceChapterVersionID = sourceChapterVersionID
        self.sourceCandidateID = sourceCandidateID
        self.createdAt = createdAt
        self.operationID = operationID
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case chapterID
        case kind
        case title
        case content
        case factCompatibilityID
        case sourceChapterVersionID
        case sourceCandidateID
        case createdAt
        case operationID
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(NovelChapterVersionID.self, forKey: .id)
        chapterID = try values.decode(NovelChapterID.self, forKey: .chapterID)
        kind = try values.decode(NovelChapterVersionKind.self, forKey: .kind)
        title = try values.decode(String.self, forKey: .title)
        content = try values.decode(String.self, forKey: .content)
        factCompatibilityID = try values.decode(UUID.self, forKey: .factCompatibilityID)
        sourceChapterVersionID = try values.decodeIfPresent(
            NovelChapterVersionID.self,
            forKey: .sourceChapterVersionID
        )
        sourceCandidateID = try values.decodeIfPresent(
            NovelCandidateID.self,
            forKey: .sourceCandidateID
        )
        createdAt = try values.decode(Date.self, forKey: .createdAt)
        operationID = try values.decode(NovelOperationID.self, forKey: .operationID)
    }

    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(id, forKey: .id)
        try values.encode(chapterID, forKey: .chapterID)
        try values.encode(kind, forKey: .kind)
        try values.encode(title, forKey: .title)
        try values.encode(content, forKey: .content)
        try values.encode(factCompatibilityID, forKey: .factCompatibilityID)
        try values.encodeIfPresent(sourceChapterVersionID, forKey: .sourceChapterVersionID)
        try values.encodeIfPresent(sourceCandidateID, forKey: .sourceCandidateID)
        try values.encode(createdAt, forKey: .createdAt)
        try values.encode(operationID, forKey: .operationID)
    }
}

struct NovelChapterSelection: Codable, Equatable, Hashable, Sendable {
    let chapterID: NovelChapterID
    let versionID: NovelChapterVersionID
}

enum NovelChapterText {
    static func appending(_ addition: String, to existing: String) -> String {
        guard !existing.isEmpty else { return addition }
        guard !addition.isEmpty else { return existing }
        return existing + "\n\n" + addition
    }
}

struct NovelStoryEventRecord: Codable, Equatable, Sendable {
    let id: NovelEventID
    let sequence: Int64
    let kind: String
    let summary: String
    let entityReferences: [String]
    let createdAt: Date
}

struct NovelChapterPlotModule: Codable, Equatable, Sendable {
    var chapterID: NovelChapterID
    var text: String
    /// Later chapters after an earlier-chapter edit. Text is kept; injection must
    /// treat it as a stale pointer, not a current commit.
    var stale: Bool

    init(chapterID: NovelChapterID, text: String, stale: Bool = false) {
        self.chapterID = chapterID
        self.text = text
        self.stale = stale
    }

    private enum CodingKeys: String, CodingKey {
        case chapterID
        case text
        case stale
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        chapterID = try container.decode(NovelChapterID.self, forKey: .chapterID)
        text = try container.decode(String.self, forKey: .text)
        stale = try container.decodeIfPresent(Bool.self, forKey: .stale) ?? false
    }

    func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(chapterID, forKey: .chapterID)
        try container.encode(text, forKey: .text)
        if stale {
            try container.encode(true, forKey: .stale)
        }
    }
}

struct NovelStateSnapshotRecord: Codable, Equatable, Sendable {
    /// Cap for cross-chapter anti-repeat highlights carried on each snapshot.
    static let maxRecentWrittenHighlights = 24
    /// Cap each highlight line so injection stays beat-sized, not a second manuscript.
    static let maxHighlightCharacterCount = 160

    let id: NovelStateSnapshotID
    let eventIDs: [NovelEventID]
    let summary: String
    let branchOutline: String
    let unresolvedEntityNames: [String]
    let characterIdentityClarifications: [NovelCharacterIdentityClarificationRecord]
    let settingProposalIDs: [NovelProposalID]
    /// Bounded beat list derived from recent story-event summaries for anti-repeat injection.
    let recentWrittenHighlights: [String]
    /// Per-chapter plot exports. `current.md` is the host-linked fold of these modules.
    let chapterPlots: [NovelChapterPlotModule]
    let createdAt: Date

    init(
        id: NovelStateSnapshotID,
        eventIDs: [NovelEventID],
        summary: String,
        branchOutline: String,
        unresolvedEntityNames: [String],
        createdAt: Date,
        settingProposalIDs: [NovelProposalID] = [],
        characterIdentityClarifications: [NovelCharacterIdentityClarificationRecord] = [],
        recentWrittenHighlights: [String] = [],
        chapterPlots: [NovelChapterPlotModule] = []
    ) {
        self.id = id
        self.eventIDs = eventIDs
        self.summary = summary
        self.branchOutline = branchOutline
        self.unresolvedEntityNames = unresolvedEntityNames
        self.characterIdentityClarifications = characterIdentityClarifications
        self.settingProposalIDs = settingProposalIDs
        self.recentWrittenHighlights = Self.normalizedHighlights(recentWrittenHighlights)
        self.chapterPlots = chapterPlots
        self.createdAt = createdAt
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case eventIDs
        case summary
        case branchOutline
        case unresolvedEntityNames
        case characterIdentityClarifications
        case settingProposalIDs
        case recentWrittenHighlights
        case chapterPlots
        case createdAt
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(NovelStateSnapshotID.self, forKey: .id)
        eventIDs = try container.decode([NovelEventID].self, forKey: .eventIDs)
        summary = try container.decode(String.self, forKey: .summary)
        branchOutline = try container.decode(String.self, forKey: .branchOutline)
        unresolvedEntityNames = try container.decode(
            [String].self,
            forKey: .unresolvedEntityNames
        )
        characterIdentityClarifications = try container.decodeIfPresent(
            [NovelCharacterIdentityClarificationRecord].self,
            forKey: .characterIdentityClarifications
        ) ?? []
        settingProposalIDs = try container.decodeIfPresent(
            [NovelProposalID].self,
            forKey: .settingProposalIDs
        ) ?? []
        recentWrittenHighlights = Self.normalizedHighlights(
            try container.decodeIfPresent([String].self, forKey: .recentWrittenHighlights) ?? []
        )
        chapterPlots = try container.decodeIfPresent(
            [NovelChapterPlotModule].self,
            forKey: .chapterPlots
        ) ?? []
        createdAt = try container.decode(Date.self, forKey: .createdAt)
    }

    func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(eventIDs, forKey: .eventIDs)
        try container.encode(summary, forKey: .summary)
        try container.encode(branchOutline, forKey: .branchOutline)
        try container.encode(unresolvedEntityNames, forKey: .unresolvedEntityNames)
        try container.encode(
            characterIdentityClarifications,
            forKey: .characterIdentityClarifications
        )
        try container.encode(settingProposalIDs, forKey: .settingProposalIDs)
        try container.encode(recentWrittenHighlights, forKey: .recentWrittenHighlights)
        if !chapterPlots.isEmpty {
            try container.encode(chapterPlots, forKey: .chapterPlots)
        }
        try container.encode(createdAt, forKey: .createdAt)
    }

    /// Merge prior highlights with new event summaries; keep the newest capped window.
    static func mergedHighlights(
        prior: [String],
        newEventSummaries: [String]
    ) -> [String] {
        normalizedHighlights(prior + newEventSummaries)
    }

    static func normalizedHighlights(_ raw: [String]) -> [String] {
        var seen: Set<String> = []
        var result: [String] = []
        for item in raw {
            let trimmed = item.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            let clipped: String
            if trimmed.count > maxHighlightCharacterCount {
                clipped = String(trimmed.prefix(maxHighlightCharacterCount))
            } else {
                clipped = trimmed
            }
            let key = clipped.lowercased()
            guard seen.insert(key).inserted else { continue }
            result.append(clipped)
        }
        if result.count > maxRecentWrittenHighlights {
            return Array(result.suffix(maxRecentWrittenHighlights))
        }
        return result
    }

    func injectionHighlightsText() -> String {
        let source: [(text: String, stale: Bool)]
        if chapterPlots.isEmpty {
            source = recentWrittenHighlights.map { (text: $0, stale: false) }
        } else {
            source = chapterPlots
                .suffix(Self.maxRecentWrittenHighlights)
                .map { (text: $0.text, stale: $0.stale) }
        }
        return source.compactMap { item in
            let text = item.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            return item.stale ? "- \(text)（后续可能过期）" : "- \(text)"
        }.joined(separator: "\n")
    }

    var hasStaleChapterPlots: Bool {
        chapterPlots.contains(where: \.stale)
    }
}

enum NovelSessionCursor: Codable, Equatable, Sendable {
    case empty
    case through(sequence: Int64)
}

struct NovelBranchCheckpointRecord: Codable, Equatable, Sendable {
    let id: NovelCheckpointID
    let kind: NovelCheckpointKind
    let createdOnBranchID: NovelBranchID
    let parentCheckpointID: NovelCheckpointID?
    let chapterSelections: [NovelChapterSelection]
    let stateSnapshotID: NovelStateSnapshotID
    let sessionCursor: NovelSessionCursor
    let branchOverrideRevisionIDs: [NovelMaterialRevisionID]
    let sourceCandidateID: NovelCandidateID?
    let baseHeadRevision: Int64
    let operationID: NovelOperationID
    let createdAt: Date
}

enum NovelFactReceiptKind: String, Codable, Equatable, Sendable {
    case stateDelta
    case manualRebuild
}

struct NovelFactReceiptLink: Codable, Equatable, Sendable {
    let pendingID: NovelPendingOperationID
    let ownerOperationID: NovelOperationID
    let attemptOperationID: NovelOperationID
    let attemptPayloadSHA256: String
    let kind: NovelFactReceiptKind
    let chunkIndex: Int?

    init(
        pendingID: NovelPendingOperationID,
        ownerOperationID: NovelOperationID,
        attemptOperationID: NovelOperationID,
        attemptPayloadSHA256: String,
        kind: NovelFactReceiptKind,
        chunkIndex: Int? = nil
    ) {
        self.pendingID = pendingID
        self.ownerOperationID = ownerOperationID
        self.attemptOperationID = attemptOperationID
        self.attemptPayloadSHA256 = attemptPayloadSHA256
        self.kind = kind
        self.chunkIndex = chunkIndex
    }
}

struct NovelGenerationReceiptRecord: Codable, Equatable, Sendable {
    let id: NovelReceiptID
    let runID: NovelRunID
    /// Effective transport provider UUID.
    let providerID: String
    /// Provider UUID that owns the selected model in project settings.
    let ownerProviderID: String
    let modelID: String
    let wireModelID: String
    let promptVersion: String
    let injectionReceiptID: NovelReceiptID
    let parameters: [String: String]
    let requestSHA256: String
    let createdAt: Date
    let factTransaction: NovelFactReceiptLink?

    init(
        id: NovelReceiptID,
        runID: NovelRunID,
        providerID: String,
        ownerProviderID: String? = nil,
        modelID: String,
        wireModelID: String? = nil,
        promptVersion: String,
        injectionReceiptID: NovelReceiptID,
        parameters: [String: String],
        requestSHA256: String,
        createdAt: Date,
        factTransaction: NovelFactReceiptLink? = nil
    ) {
        self.id = id
        self.runID = runID
        self.providerID = providerID
        self.ownerProviderID = ownerProviderID ?? providerID
        self.modelID = modelID
        self.wireModelID = wireModelID ?? modelID
        self.promptVersion = promptVersion
        self.injectionReceiptID = injectionReceiptID
        self.parameters = parameters
        self.requestSHA256 = requestSHA256
        self.createdAt = createdAt
        self.factTransaction = factTransaction
    }
}

struct NovelActiveRunRecord: Codable, Equatable, Sendable {
    let id: NovelRunID
    let operationID: NovelOperationID
    let requestPayloadSHA256: String
    let branchID: NovelBranchID
    let sessionID: NovelSessionID
    let kind: NovelRunKind
    let mode: NovelSessionMode
    let granularity: NovelGenerationGranularity?
    let userMessageID: NovelMessageID
    let messageID: NovelMessageID
    let candidateID: NovelCandidateID?
    let sourceChapterVersionID: NovelChapterVersionID?
    /// The unresolved surface name owned by a contextual character-proposal run.
    /// Nil for every legacy run kind and for documents written before this field existed.
    let contextualCharacterMention: String?
    let baseCheckpointID: NovelCheckpointID
    let baseHeadRevision: Int64
    var status: NovelRunStatus
    var partialContent: String
    let receiptID: NovelReceiptID
    let startedAt: Date
    var terminalAt: Date?
    var interruptionReason: NovelRunInterruptionReason?
    var terminalFailure: NovelFailure?
    /// Confirmed chapter-plan digest captured at run begin for whole-chapter prose.
    /// Bound onto the resulting candidate so collect can reject stale drafts.
    let chapterPlanDigest: String?
    /// Non-nil only when this run is owned by the ghostwrite pipeline.
    var ghostwritePlanID: NovelChapterPlanID? = nil
}

struct NovelRecoverySidecarV1: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1

    let schemaVersion: Int
    let projectID: NovelProjectID
    let runID: NovelRunID
    let branchID: NovelBranchID
    let sessionID: NovelSessionID
    let messageID: NovelMessageID
    let baseProjectRevision: Int64
    let sequence: Int64
    let partialContent: String
    let partialSHA256: String
    let updatedAt: Date
    /// First-party OpenAI Responses cursor. Both values are either present
    /// together or absent; older sidecars decode as the non-resumable form.
    let responseID: String?
    let responseSequenceNumber: Int64?

    init(
        schemaVersion: Int,
        projectID: NovelProjectID,
        runID: NovelRunID,
        branchID: NovelBranchID,
        sessionID: NovelSessionID,
        messageID: NovelMessageID,
        baseProjectRevision: Int64,
        sequence: Int64,
        partialContent: String,
        partialSHA256: String,
        updatedAt: Date,
        responseID: String? = nil,
        responseSequenceNumber: Int64? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.projectID = projectID
        self.runID = runID
        self.branchID = branchID
        self.sessionID = sessionID
        self.messageID = messageID
        self.baseProjectRevision = baseProjectRevision
        self.sequence = sequence
        self.partialContent = partialContent
        self.partialSHA256 = partialSHA256
        self.updatedAt = updatedAt
        self.responseID = responseID
        self.responseSequenceNumber = responseSequenceNumber
    }
}

struct NovelPendingOperationRecord: Codable, Equatable, Sendable {
    let id: NovelPendingOperationID
    let kind: NovelPendingOperationKind
    var status: NovelPendingOperationStatus
    let branchID: NovelBranchID
    let operationID: NovelOperationID
    let payloadSHA256: String
    let baseCheckpointID: NovelCheckpointID
    let baseHeadRevision: Int64
    let baseWorkingRevision: Int64
    let candidateID: NovelCandidateID?
    let collectionTarget: NovelCollectionTarget?
    let selectedText: String
    let proposedChapterVersion: NovelChapterVersionRecord?
    let proposedCheckpointID: NovelCheckpointID?
    let proposedStateSnapshotID: NovelStateSnapshotID?
    let rebuildBaseCheckpointID: NovelCheckpointID?
    let sessionCursor: NovelSessionCursor?
    var manualSyncProgress: NovelManualSyncProgress?
    let createdAt: Date
    var lastError: String?

    init(
        id: NovelPendingOperationID,
        kind: NovelPendingOperationKind,
        status: NovelPendingOperationStatus,
        branchID: NovelBranchID,
        operationID: NovelOperationID,
        payloadSHA256: String,
        baseCheckpointID: NovelCheckpointID,
        baseHeadRevision: Int64,
        baseWorkingRevision: Int64 = 0,
        candidateID: NovelCandidateID?,
        collectionTarget: NovelCollectionTarget?,
        selectedText: String,
        proposedChapterVersion: NovelChapterVersionRecord?,
        proposedCheckpointID: NovelCheckpointID? = nil,
        proposedStateSnapshotID: NovelStateSnapshotID? = nil,
        rebuildBaseCheckpointID: NovelCheckpointID? = nil,
        sessionCursor: NovelSessionCursor? = nil,
        manualSyncProgress: NovelManualSyncProgress? = nil,
        createdAt: Date,
        lastError: String?
    ) {
        self.id = id
        self.kind = kind
        self.status = status
        self.branchID = branchID
        self.operationID = operationID
        self.payloadSHA256 = payloadSHA256
        self.baseCheckpointID = baseCheckpointID
        self.baseHeadRevision = baseHeadRevision
        self.baseWorkingRevision = baseWorkingRevision
        self.candidateID = candidateID
        self.collectionTarget = collectionTarget
        self.selectedText = selectedText
        self.proposedChapterVersion = proposedChapterVersion
        self.proposedCheckpointID = proposedCheckpointID
        self.proposedStateSnapshotID = proposedStateSnapshotID
        self.rebuildBaseCheckpointID = rebuildBaseCheckpointID
        self.sessionCursor = sessionCursor
        self.manualSyncProgress = manualSyncProgress
        self.createdAt = createdAt
        self.lastError = lastError
    }

    var blocksProseGeneration: Bool {
        kind != .manualSync || status != .retryable
    }
}

enum NovelSettingProposalOrigin: Codable, Equatable, Sendable {
    case derivedState
    case quickStart(runID: NovelRunID, suggestedKind: NovelMaterialKind)
    case contextualCharacter(
        runID: NovelRunID,
        sourceMention: String,
        suggestedKind: NovelMaterialKind
    )
}

struct NovelSettingProposalRecord: Codable, Equatable, Sendable {
    let id: NovelProposalID
    let branchID: NovelBranchID
    let title: String
    let content: String
    let createdAt: Date
    var isResolved: Bool
    var supersededByRunID: NovelRunID?
    let origin: NovelSettingProposalOrigin?
    let suggestedCharacterAliases: [String]?

    init(
        id: NovelProposalID,
        branchID: NovelBranchID,
        title: String,
        content: String,
        createdAt: Date,
        isResolved: Bool,
        supersededByRunID: NovelRunID? = nil,
        origin: NovelSettingProposalOrigin? = nil,
        suggestedCharacterAliases: [String]? = nil
    ) {
        self.id = id
        self.branchID = branchID
        self.title = title
        self.content = content
        self.createdAt = createdAt
        self.isResolved = isResolved
        self.supersededByRunID = supersededByRunID
        self.origin = origin
        self.suggestedCharacterAliases = suggestedCharacterAliases
    }
}

/// Opaque workspace content that iOS does not interpret but must preserve
/// across import/export round trips. Novel workspace core contract v1.1 §3.6:
/// unknown frontmatter fields and unknown files pass through, never dropped
/// (Android node extensions such as `status`/`relations`/foreshadowing would
/// otherwise be lost on an Android→iOS→Android round trip).
struct NovelWorkspacePassthroughRecord: Codable, Equatable, Sendable {
    /// Raw frontmatter lines kept verbatim, keyed by the anchor id used at
    /// export time: bare entity ids for materials/chapters/proposals, and
    /// role-prefixed ids for files that share an entity (e.g.
    /// `project:<id>`, `branch:<id>`, `upcoming:<id>`, `plot-current:<id>`).
    var frontmatterExtensions: [String: [String]]
    /// Tree-relative path → raw file contents for files iOS has no semantic
    /// mapping for (foreshadowing nodes, unknown directories, drafts, …).
    var opaqueFiles: [String: String]

    static let empty = NovelWorkspacePassthroughRecord(
        frontmatterExtensions: [:],
        opaqueFiles: [:]
    )

    var isEmpty: Bool {
        frontmatterExtensions.isEmpty && opaqueFiles.isEmpty
    }
}

enum NovelOperationKind: String, Codable, Sendable {
    case createProject
    case renameProject
    case reviseMaterial
    case deleteMaterial
    case setModelPolicy
    case resolveSettingProposal
    case setBranchMaterialOverride
    case setMainBranch
    case setPolishPreference
    case setCollaborationMode
    case setPauseGhostwriteOnBlockingContinuity
    case upsertChapterPlan
    case clearChapterPlan
    case upsertUpcomingArc
    case clearUpcomingArc
    case forkBranch
    case renameBranch
    case deleteBranch
    case undoBranchHead
    case archiveDiscussion
    case clarifyCharacterIdentity
    case cloneCandidate
    case adoptPolishCandidate
    case abandonPolishTransaction
    case restoreChapterVersion
    case discardChapter
    case restoreChapter
    case deleteChapterFromManuscript
    case startRun
    case cancelRun
    case collectCandidate
    case saveManualEdit
    case syncManualEdits
    case workspacePlot
    case retryPending
    case importProject
    case restorePreviousProject
    case deleteProject
}

struct NovelAppliedOperationRecord: Codable, Equatable, Sendable {
    let operationID: NovelOperationID
    let kind: NovelOperationKind
    let payloadSHA256: String
    let outcome: NovelOutcome
    let appliedProjectRevision: Int64
    let appliedAt: Date
}

struct NovelProjectDocumentV1: Codable, Equatable, Sendable {
    static let currentSchemaVersion = 1

    var schemaVersion: Int
    var project: NovelProjectRecord
    var materials: [NovelMaterialRecord]
    var materialRevisions: [NovelMaterialRevisionRecord]
    var branches: [NovelBranchRecord]
    var sessions: [NovelSessionRecord]
    var chapters: [NovelChapterRecord]
    var chapterVersions: [NovelChapterVersionRecord]
    var events: [NovelStoryEventRecord]
    var stateSnapshots: [NovelStateSnapshotRecord]
    var checkpoints: [NovelBranchCheckpointRecord]
    var candidates: [NovelCandidateRecord]
    var injectionReceipts: [NovelInjectionReceiptRecord]
    var generationReceipts: [NovelGenerationReceiptRecord]
    var factAttempts: [NovelFactAttemptRecord]
    var polishTransactions: [NovelPendingPolishTransactionRecord]
    var polishAttempts: [NovelPolishAttemptRecord]
    var polishAssessments: [NovelPolishAssessmentRecord]
    var pendingOperations: [NovelPendingOperationRecord]
    var activeRuns: [NovelActiveRunRecord]
    var settingProposals: [NovelSettingProposalRecord]
    var chapterPlans: [NovelChapterPlanRecord] = []
    var upcomingArcs: [NovelUpcomingArcRecord] = []
    var appliedOperations: [NovelAppliedOperationRecord]
    var workspacePassthrough: NovelWorkspacePassthroughRecord = NovelWorkspacePassthroughRecord.empty
}

extension NovelProjectDocumentV1 {
    private enum CodingKeys: String, CodingKey {
        case schemaVersion
        case project
        case materials
        case materialRevisions
        case branches
        case sessions
        case chapters
        case chapterVersions
        case events
        case stateSnapshots
        case checkpoints
        case candidates
        case injectionReceipts
        case generationReceipts
        case factAttempts
        case polishTransactions
        case polishAttempts
        case polishAssessments
        case pendingOperations
        case activeRuns
        case settingProposals
        case chapterPlans
        case upcomingArcs
        case appliedOperations
        case workspacePassthrough
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try values.decode(Int.self, forKey: .schemaVersion)
        project = try values.decode(NovelProjectRecord.self, forKey: .project)
        materials = try values.decode([NovelMaterialRecord].self, forKey: .materials)
        materialRevisions = try values.decode(
            [NovelMaterialRevisionRecord].self,
            forKey: .materialRevisions
        )
        branches = try values.decode([NovelBranchRecord].self, forKey: .branches)
        sessions = try values.decode([NovelSessionRecord].self, forKey: .sessions)
        chapters = try values.decode([NovelChapterRecord].self, forKey: .chapters)
        chapterVersions = try values.decode(
            [NovelChapterVersionRecord].self,
            forKey: .chapterVersions
        )
        events = try values.decode([NovelStoryEventRecord].self, forKey: .events)
        stateSnapshots = try values.decode(
            [NovelStateSnapshotRecord].self,
            forKey: .stateSnapshots
        )
        checkpoints = try values.decode(
            [NovelBranchCheckpointRecord].self,
            forKey: .checkpoints
        )
        candidates = try values.decode([NovelCandidateRecord].self, forKey: .candidates)
        injectionReceipts = try values.decode(
            [NovelInjectionReceiptRecord].self,
            forKey: .injectionReceipts
        )
        generationReceipts = try values.decode(
            [NovelGenerationReceiptRecord].self,
            forKey: .generationReceipts
        )
        factAttempts = try values.decodeIfPresent(
            [NovelFactAttemptRecord].self,
            forKey: .factAttempts
        ) ?? []
        polishTransactions = try values.decodeIfPresent(
            [NovelPendingPolishTransactionRecord].self,
            forKey: .polishTransactions
        ) ?? []
        polishAttempts = try values.decodeIfPresent(
            [NovelPolishAttemptRecord].self,
            forKey: .polishAttempts
        ) ?? []
        polishAssessments = try values.decodeIfPresent(
            [NovelPolishAssessmentRecord].self,
            forKey: .polishAssessments
        ) ?? []
        pendingOperations = try values.decode(
            [NovelPendingOperationRecord].self,
            forKey: .pendingOperations
        )
        activeRuns = try values.decode([NovelActiveRunRecord].self, forKey: .activeRuns)
        settingProposals = try values.decode(
            [NovelSettingProposalRecord].self,
            forKey: .settingProposals
        )
        chapterPlans = try values.decodeIfPresent(
            [NovelChapterPlanRecord].self,
            forKey: .chapterPlans
        ) ?? []
        upcomingArcs = try values.decodeIfPresent(
            [NovelUpcomingArcRecord].self,
            forKey: .upcomingArcs
        ) ?? []
        appliedOperations = try values.decode(
            [NovelAppliedOperationRecord].self,
            forKey: .appliedOperations
        )
        workspacePassthrough = try values.decodeIfPresent(
            NovelWorkspacePassthroughRecord.self,
            forKey: .workspacePassthrough
        ) ?? .empty
    }

    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(schemaVersion, forKey: .schemaVersion)
        try values.encode(project, forKey: .project)
        try values.encode(materials, forKey: .materials)
        try values.encode(materialRevisions, forKey: .materialRevisions)
        try values.encode(branches, forKey: .branches)
        try values.encode(sessions, forKey: .sessions)
        try values.encode(chapters, forKey: .chapters)
        try values.encode(chapterVersions, forKey: .chapterVersions)
        try values.encode(events, forKey: .events)
        try values.encode(stateSnapshots, forKey: .stateSnapshots)
        try values.encode(checkpoints, forKey: .checkpoints)
        try values.encode(candidates, forKey: .candidates)
        try values.encode(injectionReceipts, forKey: .injectionReceipts)
        try values.encode(generationReceipts, forKey: .generationReceipts)
        try values.encode(factAttempts, forKey: .factAttempts)
        try values.encode(polishTransactions, forKey: .polishTransactions)
        try values.encode(polishAttempts, forKey: .polishAttempts)
        try values.encode(polishAssessments, forKey: .polishAssessments)
        try values.encode(pendingOperations, forKey: .pendingOperations)
        try values.encode(activeRuns, forKey: .activeRuns)
        try values.encode(settingProposals, forKey: .settingProposals)
        try values.encode(chapterPlans, forKey: .chapterPlans)
        try values.encode(upcomingArcs, forKey: .upcomingArcs)
        try values.encode(appliedOperations, forKey: .appliedOperations)
        try values.encode(workspacePassthrough, forKey: .workspacePassthrough)
    }
}

extension NovelProjectDocumentV1 {
    func chapterPlan(for branchID: NovelBranchID) -> NovelChapterPlanRecord? {
        chapterPlans.first(where: { $0.branchID == branchID })
    }

    func confirmedChapterPlan(for branchID: NovelBranchID) -> NovelChapterPlanRecord? {
        chapterPlan(for: branchID).flatMap { $0.isConfirmed ? $0 : nil }
    }

    func upcomingArc(for branchID: NovelBranchID) -> NovelUpcomingArcRecord? {
        upcomingArcs.first(where: { $0.branchID == branchID })
    }

    func activeSettingProposals(for branchID: NovelBranchID) -> [NovelSettingProposalRecord] {
        guard let branch = branches.first(where: { $0.id == branchID }),
              let state = stateSnapshots.first(where: {
                  $0.id == branch.currentStateSnapshotID
              }) else {
            return []
        }
        let activeIDs = Set(state.settingProposalIDs)
        return settingProposals.filter { proposal in
            guard proposal.branchID == branchID,
                  !proposal.isResolved,
                  proposal.supersededByRunID == nil,
                  NovelSettingProposalFilter.shouldSurface(proposal) else { return false }
            if activeIDs.contains(proposal.id) { return true }
            if case .some(.quickStart) = proposal.origin { return true }
            if case .some(.contextualCharacter) = proposal.origin { return true }
            return false
        }
    }
}

enum NovelProjectLoadAccess: Equatable, Sendable {
    case readWrite
    case degradedPrevious(primaryFailure: String)
}

struct NovelLoadedProject: Equatable, Sendable {
    let document: NovelProjectDocumentV1
    let access: NovelProjectLoadAccess
}

struct NovelProjectSummary: Codable, Equatable, Sendable {
    let id: NovelProjectID
    let name: String
    let mainBranchID: NovelBranchID?
    let updatedAt: Date
    let revision: Int64
    let isDegraded: Bool
    let hasRunningRun: Bool
    let loadError: String?

    init(document: NovelProjectDocumentV1, isDegraded: Bool = false) {
        id = document.project.id
        name = document.project.name
        mainBranchID = document.project.mainBranchID
        updatedAt = document.project.updatedAt
        revision = document.project.revision
        self.isDegraded = isDegraded
        hasRunningRun = document.activeRuns.contains(where: { $0.status == .running })
        loadError = nil
    }

    init(unavailableProjectID: NovelProjectID, error: Error) {
        id = unavailableProjectID
        name = "Unavailable Project"
        mainBranchID = nil
        updatedAt = Date(timeIntervalSince1970: 0)
        revision = 0
        isDegraded = true
        hasRunningRun = false
        loadError = String(describing: error)
    }
}

struct NovelProjectSnapshot: Equatable, Sendable {
    let project: NovelProjectRecord
    let materials: [NovelMaterialRecord]
    let materialRevisions: [NovelMaterialRevisionRecord]
    let branches: [NovelBranchRecord]
    let sessions: [NovelSessionRecord]
    let chapters: [NovelChapterRecord]
    let chapterVersions: [NovelChapterVersionRecord]
    let events: [NovelStoryEventRecord]
    let stateSnapshots: [NovelStateSnapshotRecord]
    let checkpoints: [NovelBranchCheckpointRecord]
    let candidates: [NovelCandidateRecord]
    let chapterPlans: [NovelChapterPlanRecord]
    let upcomingArcs: [NovelUpcomingArcRecord]
    let injectionReceipts: [NovelInjectionReceiptRecord]
    let generationReceipts: [NovelGenerationReceiptRecord]
    let factAttempts: [NovelFactAttemptRecord]
    let polishTransactions: [NovelPendingPolishTransactionRecord]
    let polishAttempts: [NovelPolishAttemptRecord]
    let polishAssessments: [NovelPolishAssessmentRecord]
    let pendingOperations: [NovelPendingOperationRecord]
    let activeRuns: [NovelActiveRunRecord]
    let settingProposals: [NovelSettingProposalRecord]
    let appliedOperations: [NovelAppliedOperationRecord]
    let workspacePassthrough: NovelWorkspacePassthroughRecord
    let access: NovelProjectLoadAccess

    init(loaded: NovelLoadedProject) {
        let document = loaded.document
        project = document.project
        materials = document.materials
        materialRevisions = document.materialRevisions
        branches = document.branches
        sessions = document.sessions
        chapters = document.chapters
        chapterVersions = document.chapterVersions
        events = document.events
        stateSnapshots = document.stateSnapshots
        checkpoints = document.checkpoints
        candidates = document.candidates
        chapterPlans = document.chapterPlans
        upcomingArcs = document.upcomingArcs
        injectionReceipts = document.injectionReceipts
        generationReceipts = document.generationReceipts
        factAttempts = document.factAttempts
        polishTransactions = document.polishTransactions
        polishAttempts = document.polishAttempts
        polishAssessments = document.polishAssessments
        pendingOperations = document.pendingOperations
        activeRuns = document.activeRuns
        settingProposals = document.settingProposals
        appliedOperations = document.appliedOperations
        workspacePassthrough = document.workspacePassthrough
        access = loaded.access
    }

    func chapterPlan(for branchID: NovelBranchID) -> NovelChapterPlanRecord? {
        chapterPlans.first(where: { $0.branchID == branchID })
    }

    func confirmedChapterPlan(for branchID: NovelBranchID) -> NovelChapterPlanRecord? {
        chapterPlan(for: branchID).flatMap { $0.isConfirmed ? $0 : nil }
    }

    func upcomingArc(for branchID: NovelBranchID) -> NovelUpcomingArcRecord? {
        upcomingArcs.first(where: { $0.branchID == branchID })
    }

    func activeSettingProposals(for branchID: NovelBranchID) -> [NovelSettingProposalRecord] {
        guard let branch = branches.first(where: { $0.id == branchID }),
              let state = stateSnapshots.first(where: {
                  $0.id == branch.currentStateSnapshotID
              }) else {
            return []
        }
        let activeIDs = Set(state.settingProposalIDs)
        return settingProposals.filter { proposal in
            guard proposal.branchID == branchID,
                  !proposal.isResolved,
                  proposal.supersededByRunID == nil,
                  NovelSettingProposalFilter.shouldSurface(proposal) else { return false }
            if activeIDs.contains(proposal.id) { return true }
            if case .some(.quickStart) = proposal.origin { return true }
            if case .some(.contextualCharacter) = proposal.origin { return true }
            return false
        }
    }
}

struct NovelBranchSnapshot: Equatable, Sendable {
    let projectID: NovelProjectID
    let projectRevision: Int64
    let configRevision: Int64
    let branch: NovelBranchRecord
    let session: NovelSessionRecord
    let headCheckpoint: NovelBranchCheckpointRecord
    let currentState: NovelStateSnapshotRecord
    let chapterSelections: [NovelChapterSelection]
    let activeSettingProposals: [NovelSettingProposalRecord]
    let access: NovelProjectLoadAccess
}
