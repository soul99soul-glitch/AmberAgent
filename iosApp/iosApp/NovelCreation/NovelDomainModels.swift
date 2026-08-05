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

enum NovelProjectCreationMode: String, Codable, CaseIterable, Sendable {
    case blank
    case quickStart
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
}

struct NovelQuickStartSeed: Codable, Equatable, Sendable {
    let genre: String
    let coreIdea: String
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
    var lastGenerationGranularity: NovelGenerationGranularity
    var polishPreference: String

    func configuredModelPolicy(for purpose: NovelModelRole) -> NovelProjectModelPolicy {
        switch purpose {
        case .creation: modelPolicy
        case .stateSync: stateSyncModelPolicy ?? .global
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

struct NovelStateSnapshotRecord: Codable, Equatable, Sendable {
    let id: NovelStateSnapshotID
    let eventIDs: [NovelEventID]
    let summary: String
    let branchOutline: String
    let unresolvedEntityNames: [String]
    let characterIdentityClarifications: [NovelCharacterIdentityClarificationRecord]
    let settingProposalIDs: [NovelProposalID]
    let createdAt: Date

    init(
        id: NovelStateSnapshotID,
        eventIDs: [NovelEventID],
        summary: String,
        branchOutline: String,
        unresolvedEntityNames: [String],
        createdAt: Date,
        settingProposalIDs: [NovelProposalID] = [],
        characterIdentityClarifications: [NovelCharacterIdentityClarificationRecord] = []
    ) {
        self.id = id
        self.eventIDs = eventIDs
        self.summary = summary
        self.branchOutline = branchOutline
        self.unresolvedEntityNames = unresolvedEntityNames
        self.characterIdentityClarifications = characterIdentityClarifications
        self.settingProposalIDs = settingProposalIDs
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
        try container.encode(createdAt, forKey: .createdAt)
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
    case startRun
    case cancelRun
    case collectCandidate
    case saveManualEdit
    case syncManualEdits
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
    var appliedOperations: [NovelAppliedOperationRecord]
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
        case appliedOperations
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
        appliedOperations = try values.decode(
            [NovelAppliedOperationRecord].self,
            forKey: .appliedOperations
        )
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
        try values.encode(appliedOperations, forKey: .appliedOperations)
    }
}

extension NovelProjectDocumentV1 {
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
                  proposal.supersededByRunID == nil else { return false }
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
    let loadError: String?

    init(document: NovelProjectDocumentV1, isDegraded: Bool = false) {
        id = document.project.id
        name = document.project.name
        mainBranchID = document.project.mainBranchID
        updatedAt = document.project.updatedAt
        revision = document.project.revision
        self.isDegraded = isDegraded
        loadError = nil
    }

    init(unavailableProjectID: NovelProjectID, error: Error) {
        id = unavailableProjectID
        name = "Unavailable Project"
        mainBranchID = nil
        updatedAt = Date(timeIntervalSince1970: 0)
        revision = 0
        isDegraded = true
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
        access = loaded.access
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
