import CryptoKit
import Foundation

struct NovelInjectionOverrides: Codable, Equatable, Sendable {
    var forceIncludeMaterialIDs: [NovelMaterialID]
    var forceExcludeMaterialIDs: [NovelMaterialID]

    static let none = NovelInjectionOverrides(
        forceIncludeMaterialIDs: [],
        forceExcludeMaterialIDs: []
    )
}

struct NovelInjectionBudget: Codable, Equatable, Sendable {
    let maxEstimatedInputTokens: Int
    let chapterTailCharacterLimit: Int
    let maximumRecentSessionMessages: Int

    static let standard = NovelInjectionBudget(
        maxEstimatedInputTokens: 16_000,
        chapterTailCharacterLimit: 6_000,
        maximumRecentSessionMessages: 12
    )
}

struct NovelPendingStateInjection: Equatable, Sendable {
    let pendingID: NovelPendingOperationID
    let chunkIndex: Int
    let content: String
}

struct NovelInjectionPlanningRequest: Equatable, Sendable {
    let branchID: NovelBranchID
    let promptKind: NovelPromptKind
    let userText: String
    let sourceChapterVersionID: NovelChapterVersionID?
    let stateSnapshotIDOverride: NovelStateSnapshotID?
    let sessionCursorLimit: NovelSessionCursor?
    let includeUnsynchronizedStateWarning: Bool
    let pendingState: NovelPendingStateInjection?
    let overrides: NovelInjectionOverrides
    let budget: NovelInjectionBudget

    init(
        branchID: NovelBranchID,
        promptKind: NovelPromptKind,
        userText: String,
        sourceChapterVersionID: NovelChapterVersionID? = nil,
        stateSnapshotIDOverride: NovelStateSnapshotID? = nil,
        sessionCursorLimit: NovelSessionCursor? = nil,
        includeUnsynchronizedStateWarning: Bool = true,
        pendingState: NovelPendingStateInjection? = nil,
        overrides: NovelInjectionOverrides = .none,
        budget: NovelInjectionBudget = .standard
    ) {
        self.branchID = branchID
        self.promptKind = promptKind
        self.userText = userText
        self.sourceChapterVersionID = sourceChapterVersionID
        self.stateSnapshotIDOverride = stateSnapshotIDOverride
        self.sessionCursorLimit = sessionCursorLimit
        self.includeUnsynchronizedStateWarning = includeUnsynchronizedStateWarning
        self.pendingState = pendingState
        self.overrides = overrides
        self.budget = budget
    }
}

enum NovelInjectionSectionKind: Codable, Equatable, Sendable {
    case fixedPrompt
    case polishPreference
    case currentState(NovelStateSnapshotID)
    case pendingManualState(NovelPendingOperationID, chunkIndex: Int)
    case quickStartSeed
    case chapterContext(NovelChapterVersionID?)
    case sessionMessage(NovelMessageID)
    case storyEvent(NovelEventID)
    case material(NovelMaterialRevisionID)
    case userInput
}

enum NovelInjectionSelectionReason: String, Codable, Equatable, Sendable {
    case requiredPrompt
    case requiredPolishPreference
    case requiredUserInput
    case requiredCurrentState
    case requiredQuickStartSeed
    case currentChapterTail
    case previousChapterTail
    case fullSourceChapter
    case recentSession
    case branchEventHistory
    case branchOverride
    case always
    case forceIncluded
    case smartMatch
    case forceExcluded
    case disabled
    case noSmartMatch
    case budgetTrimmed
}

struct NovelInjectionSection: Codable, Equatable, Sendable {
    let kind: NovelInjectionSectionKind
    let label: String
    let content: String
    let reason: NovelInjectionSelectionReason
    let estimatedTokens: Int
    let contentSHA256: String
}

struct NovelMaterialInjectionDecision: Codable, Equatable, Sendable {
    let materialID: NovelMaterialID
    let revisionID: NovelMaterialRevisionID
    let included: Bool
    let reason: NovelInjectionSelectionReason
    let relevanceScore: Int
    let estimatedTokens: Int
    let contentSHA256: String
}

struct NovelInjectionReceiptSectionRecord: Codable, Equatable, Sendable {
    let kind: NovelInjectionSectionKind
    let label: String
    let reason: NovelInjectionSelectionReason
    let estimatedTokens: Int
    let contentSHA256: String
}

struct NovelInjectionReceiptRecord: Codable, Equatable, Sendable {
    let id: NovelReceiptID
    let runID: NovelRunID
    private(set) var projectID: NovelProjectID
    let branchID: NovelBranchID
    let promptVersion: String
    /// Effective transport provider UUID.
    let providerID: String
    let ownerProviderID: String
    let modelID: String
    let wireModelID: String
    let parameters: [String: String]
    let sections: [NovelInjectionReceiptSectionRecord]
    let materialDecisions: [NovelMaterialInjectionDecision]
    let forceIncludeMaterialIDs: [NovelMaterialID]
    let forceExcludeMaterialIDs: [NovelMaterialID]
    let requestedInputBudgetTokens: Int
    let maxEstimatedInputTokens: Int
    let estimatedInputTokens: Int
    let canonicalInputSHA256: String
    let factTransaction: NovelFactReceiptLink?
    let createdAt: Date

    init(
        id: NovelReceiptID,
        runID: NovelRunID,
        projectID: NovelProjectID,
        branchID: NovelBranchID,
        plan: NovelInjectionPlan,
        overrides: NovelInjectionOverrides,
        providerID: String,
        ownerProviderID: String? = nil,
        modelID: String,
        wireModelID: String? = nil,
        parameters: [String: String],
        requestedInputBudgetTokens: Int? = nil,
        factTransaction: NovelFactReceiptLink? = nil,
        createdAt: Date
    ) {
        self.id = id
        self.runID = runID
        self.projectID = projectID
        self.branchID = branchID
        promptVersion = plan.prompt.version
        self.providerID = providerID
        self.ownerProviderID = ownerProviderID ?? providerID
        self.modelID = modelID
        self.wireModelID = wireModelID ?? modelID
        self.parameters = Self.removingSensitiveParameters(parameters)
        sections = plan.sections.map {
            NovelInjectionReceiptSectionRecord(
                kind: $0.kind,
                label: $0.label,
                reason: $0.reason,
                estimatedTokens: $0.estimatedTokens,
                contentSHA256: $0.contentSHA256
            )
        }
        materialDecisions = plan.materialDecisions
        forceIncludeMaterialIDs = Array(Set(overrides.forceIncludeMaterialIDs)).sorted {
            $0.description < $1.description
        }
        forceExcludeMaterialIDs = Array(Set(overrides.forceExcludeMaterialIDs)).sorted {
            $0.description < $1.description
        }
        self.requestedInputBudgetTokens = requestedInputBudgetTokens ?? plan.maxEstimatedInputTokens
        maxEstimatedInputTokens = plan.maxEstimatedInputTokens
        estimatedInputTokens = plan.estimatedInputTokens
        canonicalInputSHA256 = plan.canonicalInputSHA256
        self.factTransaction = factTransaction
        self.createdAt = createdAt
    }

    func remappingProjectID(to projectID: NovelProjectID) -> NovelInjectionReceiptRecord {
        var copy = self
        copy.projectID = projectID
        return copy
    }

    private static func removingSensitiveParameters(
        _ parameters: [String: String]
    ) -> [String: String] {
        let sensitiveFragments = [
            "apikey", "authorization", "baseurl", "cookie", "credential",
            "oauth", "password", "secret", "accesstoken", "refreshtoken", "idtoken"
        ]
        return parameters.filter { key, _ in
            let normalizedKey = key.lowercased().filter(\.isLetter)
            return !sensitiveFragments.contains(where: normalizedKey.contains)
        }
    }
}

struct NovelInjectionPlan: Codable, Equatable, Sendable {
    let prompt: NovelPromptTemplate
    let sections: [NovelInjectionSection]
    let materialDecisions: [NovelMaterialInjectionDecision]
    let maxEstimatedInputTokens: Int
    let estimatedInputTokens: Int
    let contextText: String
    let canonicalInput: String
    let canonicalInputSHA256: String
}

struct NovelInjectionBudgetItem: Equatable, Sendable {
    let label: String
    let estimatedTokens: Int
}

enum NovelInjectionPlanningError: Error, Equatable, Sendable {
    case invalidInput(String)
    case missingBranch(NovelBranchID)
    case missingSession(NovelSessionID)
    case missingStateSnapshot(NovelStateSnapshotID)
    case missingMaterialRevision(NovelMaterialRevisionID)
    case missingChapterVersion(NovelChapterVersionID)
    case branchRequiresSync(NovelBranchID)
    case requiredContentExceedsBudget(
        limit: Int,
        estimated: Int,
        items: [NovelInjectionBudgetItem]
    )
}

enum NovelInjectionPlanner {
    static func plan(
        document: NovelProjectDocumentV1,
        request: NovelInjectionPlanningRequest
    ) throws -> NovelInjectionPlan {
        try validate(request)

        guard let branch = document.branches.first(where: { $0.id == request.branchID }) else {
            throw NovelInjectionPlanningError.missingBranch(request.branchID)
        }
        guard let session = document.sessions.first(where: { $0.id == branch.sessionID }) else {
            throw NovelInjectionPlanningError.missingSession(branch.sessionID)
        }
        let stateSnapshotID = request.stateSnapshotIDOverride ?? branch.currentStateSnapshotID
        guard let state = document.stateSnapshots.first(where: {
            $0.id == stateSnapshotID
        }) else {
            throw NovelInjectionPlanningError.missingStateSnapshot(stateSnapshotID)
        }
        if request.promptKind.requiresSynchronizedBranch,
           branch.syncStatus != .synchronized {
            throw NovelInjectionPlanningError.branchRequiresSync(branch.id)
        }
        if request.promptKind.requiresIdleBranch,
           document.pendingOperations.contains(where: {
               $0.branchID == branch.id &&
                   (!request.promptKind.allowsRetryableManualSync || $0.blocksProseGeneration)
           }) {
            throw NovelInjectionPlanningError.branchRequiresSync(branch.id)
        }

        let prompt = NovelPromptCatalog.template(for: request.promptKind)
        let materialSources = try effectiveMaterials(document: document, branch: branch)
        let characterIdentities = materialSources.compactMap { source -> NovelCharacterIdentity? in
            guard source.material.kind == .character else { return nil }
            return NovelCharacterIdentity(
                materialID: source.material.id,
                canonicalName: source.revision.title,
                aliases: source.material.aliases
            )
        }
        let chapterSection = try makeChapterSection(
            document: document,
            branch: branch,
            request: request
        )
        let stateSection = request.pendingState.map(makePendingStateSection) ?? makeStateSection(
            state: state,
            branch: branch,
            characterIdentities: characterIdentities,
            includeUnsynchronizedWarning: request.includeUnsynchronizedStateWarning
        )
        let seedSection = makeQuickStartSeedSection(document: document, request: request)
        let polishPreferenceSection: NovelInjectionSection?
        if request.promptKind == .wholeChapterPolish {
            let preference = document.project.polishPreference
                .trimmingCharacters(in: .whitespacesAndNewlines)
            polishPreferenceSection = makeSection(
                kind: .polishPreference,
                label: "PROJECT POLISH PREFERENCE - SUBORDINATE TO FIXED CONSTRAINTS",
                content: preference.isEmpty ? "No additional preference." : preference,
                reason: .requiredPolishPreference
            )
        } else {
            polishPreferenceSection = nil
        }
        let promptSection = makeSection(
            kind: .fixedPrompt,
            label: "SYSTEM PROMPT",
            content: prompt.systemText,
            reason: .requiredPrompt
        )
        let userSection = makeSection(
            kind: .userInput,
            label: "USER REQUEST",
            content: request.userText,
            reason: .requiredUserInput
        )

        let query = smartQuery(
            userText: request.userText,
            state: state,
            chapterContent: chapterSection?.content ?? ""
        )
        let classified = try classifyMaterials(
            materialSources,
            request: request,
            query: query
        )
        let eventCandidates = try storyEventCandidates(
            document: document,
            state: state,
            query: query
        ).sorted(by: storyEventPriority)
        let protectedMaterials = classified.filter(\.isProtected).sorted(by: materialPriority)
        let protectedSections = protectedMaterials.map(makeMaterialSection)

        var requiredSections = [promptSection]
        if let polishPreferenceSection { requiredSections.append(polishPreferenceSection) }
        requiredSections.append(stateSection)
        if let seedSection { requiredSections.append(seedSection) }
        if let chapterSection { requiredSections.append(chapterSection) }
        requiredSections.append(contentsOf: protectedSections)
        requiredSections.append(userSection)

        let requiredInput = render(requiredSections)
        let requiredTokens = estimatedTokens(requiredInput)
        guard requiredTokens <= request.budget.maxEstimatedInputTokens else {
            throw NovelInjectionPlanningError.requiredContentExceedsBudget(
                limit: request.budget.maxEstimatedInputTokens,
                estimated: requiredTokens,
                items: requiredSections.map {
                    NovelInjectionBudgetItem(label: $0.label, estimatedTokens: $0.estimatedTokens)
                }
            )
        }

        var selectedSessionSections: [NovelInjectionSection] = []
        let eligibleMessages: [NovelSessionMessageRecord]
        switch request.sessionCursorLimit {
        case nil:
            eligibleMessages = session.messages
        case .some(.empty):
            eligibleMessages = []
        case .some(.through(let sequence)):
            guard session.messages.contains(where: { $0.sequence == sequence }) else {
                throw NovelInjectionPlanningError.invalidInput(
                    "The Session cursor limit is outside the branch Session."
                )
            }
            eligibleMessages = session.messages.filter { $0.sequence <= sequence }
        }
        let recentMessages = Array(
            eligibleMessages.suffix(request.budget.maximumRecentSessionMessages)
        )
        for message in recentMessages.reversed() {
            let section = makeSessionSection(message)
            let proposed = orderedSections(
                prompt: promptSection,
                polishPreference: polishPreferenceSection,
                state: stateSection,
                seed: seedSection,
                chapter: chapterSection,
                sessions: selectedSessionSections + [section],
                events: [],
                materials: protectedSections,
                user: userSection
            )
            if estimatedTokens(render(proposed)) <= request.budget.maxEstimatedInputTokens {
                selectedSessionSections.append(section)
            } else {
                break
            }
        }
        selectedSessionSections.sort(by: sessionSectionOrder)

        var selectedEvents: [ClassifiedStoryEvent] = []
        for candidate in eventCandidates where candidate.relevanceScore > 0 {
            let proposedEvents = (selectedEvents + [candidate])
                .sorted(by: storyEventChronologicalOrder)
                .map(makeStoryEventSection)
            let proposed = orderedSections(
                prompt: promptSection,
                polishPreference: polishPreferenceSection,
                state: stateSection,
                seed: seedSection,
                chapter: chapterSection,
                sessions: selectedSessionSections,
                events: proposedEvents,
                materials: protectedSections,
                user: userSection
            )
            if estimatedTokens(render(proposed)) <= request.budget.maxEstimatedInputTokens {
                selectedEvents.append(candidate)
            }
        }
        let eventSections = selectedEvents
            .sorted(by: storyEventChronologicalOrder)
            .map(makeStoryEventSection)

        var selectedSmart: [ClassifiedMaterial] = []
        var trimmedSmartIDs: Set<NovelMaterialID> = []
        let smartCandidates = classified.filter(\.isSmartCandidate).sorted(by: smartPriority)
        for candidate in smartCandidates {
            let proposedSmart = selectedSmart + [candidate]
            let proposed = orderedSections(
                prompt: promptSection,
                polishPreference: polishPreferenceSection,
                state: stateSection,
                seed: seedSection,
                chapter: chapterSection,
                sessions: selectedSessionSections,
                events: eventSections,
                materials: protectedSections + proposedSmart.map(makeMaterialSection),
                user: userSection
            )
            if estimatedTokens(render(proposed)) <= request.budget.maxEstimatedInputTokens {
                selectedSmart.append(candidate)
            } else {
                trimmedSmartIDs.insert(candidate.source.material.id)
            }
        }

        let materialSections = protectedSections + selectedSmart.map(makeMaterialSection)
        for candidate in eventCandidates where candidate.relevanceScore == 0 {
            let proposedEvents = (selectedEvents + [candidate])
                .sorted(by: storyEventChronologicalOrder)
                .map(makeStoryEventSection)
            let proposed = orderedSections(
                prompt: promptSection,
                polishPreference: polishPreferenceSection,
                state: stateSection,
                seed: seedSection,
                chapter: chapterSection,
                sessions: selectedSessionSections,
                events: proposedEvents,
                materials: materialSections,
                user: userSection
            )
            if estimatedTokens(render(proposed)) <= request.budget.maxEstimatedInputTokens {
                selectedEvents.append(candidate)
            }
        }
        let finalEventSections = selectedEvents
            .sorted(by: storyEventChronologicalOrder)
            .map(makeStoryEventSection)
        let sections = orderedSections(
            prompt: promptSection,
            polishPreference: polishPreferenceSection,
            state: stateSection,
            seed: seedSection,
            chapter: chapterSection,
            sessions: selectedSessionSections,
            events: finalEventSections,
            materials: materialSections,
            user: userSection
        )
        let canonicalInput = render(sections)
        let contextSections = sections.filter {
            if case .fixedPrompt = $0.kind { return false }
            if case .userInput = $0.kind { return false }
            return true
        }
        let selectedSmartIDs = Set(selectedSmart.map { $0.source.material.id })
        let decisions = classified
            .sorted(by: materialStableOrder)
            .map { candidate in
                let selected = candidate.isProtected || selectedSmartIDs.contains(candidate.source.material.id)
                let reason = trimmedSmartIDs.contains(candidate.source.material.id)
                    ? NovelInjectionSelectionReason.budgetTrimmed
                    : candidate.reason
                return NovelMaterialInjectionDecision(
                    materialID: candidate.source.material.id,
                    revisionID: candidate.source.revision.id,
                    included: selected,
                    reason: reason,
                    relevanceScore: candidate.relevanceScore,
                    estimatedTokens: candidate.estimatedTokens,
                    contentSHA256: sha256(candidate.source.revision.content)
                )
            }

        return NovelInjectionPlan(
            prompt: prompt,
            sections: sections,
            materialDecisions: decisions,
            maxEstimatedInputTokens: request.budget.maxEstimatedInputTokens,
            estimatedInputTokens: estimatedTokens(canonicalInput),
            contextText: render(contextSections),
            canonicalInput: canonicalInput,
            canonicalInputSHA256: sha256(canonicalInput)
        )
    }
}

private extension NovelPromptKind {
    var requiresSynchronizedBranch: Bool {
        switch self {
        case .wholeChapterPolish:
            true
        case .quickStart, .discussion, .proseContinuation, .proseWholeChapter,
             .stateDeltaV1, .manualSyncV1, .polishDriftV1:
            false
        }
    }

    var requiresIdleBranch: Bool {
        switch self {
        case .proseContinuation, .proseWholeChapter, .wholeChapterPolish:
            true
        case .quickStart, .discussion, .stateDeltaV1, .manualSyncV1, .polishDriftV1:
            false
        }
    }

    var allowsRetryableManualSync: Bool {
        self == .proseContinuation || self == .proseWholeChapter
    }
}

private extension NovelInjectionPlanner {
    struct MaterialSource {
        let material: NovelMaterialRecord
        let revision: NovelMaterialRevisionRecord
        let isBranchOverride: Bool
    }

    struct ClassifiedMaterial {
        let source: MaterialSource
        let reason: NovelInjectionSelectionReason
        let relevanceScore: Int
        let isProtected: Bool
        let isSmartCandidate: Bool
        let estimatedTokens: Int
    }

    struct ClassifiedStoryEvent {
        let event: NovelStoryEventRecord
        let relevanceScore: Int
    }

    static func validate(_ request: NovelInjectionPlanningRequest) throws {
        guard request.budget.maxEstimatedInputTokens > 0 else {
            throw NovelInjectionPlanningError.invalidInput("Input token budget must be positive.")
        }
        guard request.budget.chapterTailCharacterLimit >= 0,
              request.budget.maximumRecentSessionMessages >= 0 else {
            throw NovelInjectionPlanningError.invalidInput("Injection limits cannot be negative.")
        }
        let included = Set(request.overrides.forceIncludeMaterialIDs)
        let excluded = Set(request.overrides.forceExcludeMaterialIDs)
        guard included.isDisjoint(with: excluded) else {
            throw NovelInjectionPlanningError.invalidInput(
                "A material cannot be force-included and force-excluded in the same request."
            )
        }
    }

    static func makeStateSection(
        state: NovelStateSnapshotRecord,
        branch: NovelBranchRecord,
        characterIdentities: [NovelCharacterIdentity],
        includeUnsynchronizedWarning: Bool
    ) -> NovelInjectionSection {
        let identityResolver = NovelCharacterIdentityResolver(identities: characterIdentities)
        let effectiveUnresolved = state.unresolvedEntityNames.filter { !identityResolver.isKnown($0) }
        let unresolved = effectiveUnresolved.isEmpty
            ? "(none)"
            : effectiveUnresolved.map { "- \($0)" }.joined(separator: "\n")
        let identityMap = characterIdentities.isEmpty
            ? "(none)"
            : characterIdentities.map { identity in
                let aliases = identity.aliases.isEmpty
                    ? "(none)"
                    : identity.aliases.joined(separator: ", ")
                return "- \(identity.canonicalName) | aliases: \(aliases)"
            }.joined(separator: "\n")
        var content = "Current summary:\n\(state.summary)\n\n" +
            "Branch outline:\n\(state.branchOutline)\n\n" +
            "Character identity map (authoritative across branches):\n\(identityMap)\n\n" +
            "Unresolved entities:\n\(unresolved)"
        if includeUnsynchronizedWarning, branch.syncStatus == .needsSync {
            content += "\n\nWarning: the working manuscript has unsynchronized edits. " +
                "Treat derived state as potentially stale."
        }
        return makeSection(
            kind: .currentState(state.id),
            label: "CURRENT BRANCH STATE",
            content: content,
            reason: .requiredCurrentState
        )
    }

    static func makePendingStateSection(
        _ pendingState: NovelPendingStateInjection
    ) -> NovelInjectionSection {
        makeSection(
            kind: .pendingManualState(
                pendingState.pendingID,
                chunkIndex: pendingState.chunkIndex
            ),
            label: "PROJECTED MANUAL-SYNC STATE",
            content: pendingState.content,
            reason: .requiredCurrentState
        )
    }

    static func makeQuickStartSeedSection(
        document: NovelProjectDocumentV1,
        request: NovelInjectionPlanningRequest
    ) -> NovelInjectionSection? {
        guard request.promptKind == .quickStart,
              let seed = document.project.quickStartSeed else {
            return nil
        }
        return makeSection(
            kind: .quickStartSeed,
            label: "QUICK START SEED",
            content: "Project: \(document.project.name)\nGenre: \(seed.genre)\nCore idea: \(seed.coreIdea)",
            reason: .requiredQuickStartSeed
        )
    }

    static func makeChapterSection(
        document: NovelProjectDocumentV1,
        branch: NovelBranchRecord,
        request: NovelInjectionPlanningRequest
    ) throws -> NovelInjectionSection? {
        if request.promptKind == .wholeChapterPolish {
            guard let sourceID = request.sourceChapterVersionID else {
                throw NovelInjectionPlanningError.invalidInput(
                    "Whole-chapter polish requires a source chapter version."
                )
            }
            guard branch.workingChapterSelections.contains(where: { $0.versionID == sourceID }) else {
                throw NovelInjectionPlanningError.invalidInput(
                    "The polish source must be an active chapter version on the target branch."
                )
            }
            guard let version = document.chapterVersions.first(where: { $0.id == sourceID }) else {
                throw NovelInjectionPlanningError.missingChapterVersion(sourceID)
            }
            return makeSection(
                kind: .chapterContext(version.id),
                label: "SOURCE CHAPTER - COMPLETE AND UNTRUNCATED",
                content: "Title: \(version.title)\n\n\(version.content)",
                reason: .fullSourceChapter
            )
        }

        guard request.promptKind == .discussion ||
                request.promptKind == .proseContinuation ||
                request.promptKind == .proseWholeChapter else {
            return nil
        }
        guard let selection = branch.workingChapterSelections.last else {
            let isWholeChapter = request.promptKind == .proseWholeChapter
            let label = isWholeChapter ? "PREVIOUS CHAPTER TAIL" :
                request.promptKind == .discussion ? "CURRENT MANUSCRIPT TAIL" : "CURRENT CHAPTER TAIL"
            let reason: NovelInjectionSelectionReason = isWholeChapter
                ? .previousChapterTail
                : .currentChapterTail
            return makeSection(
                kind: .chapterContext(nil),
                label: label,
                content: "No manuscript chapter exists yet.",
                reason: reason
            )
        }
        guard let version = document.chapterVersions.first(where: {
            $0.id == selection.versionID && $0.chapterID == selection.chapterID
        }) else {
            throw NovelInjectionPlanningError.missingChapterVersion(selection.versionID)
        }
        let wasTruncated = version.content.count > request.budget.chapterTailCharacterLimit
        let tail = String(version.content.suffix(request.budget.chapterTailCharacterLimit))
        let content = "Title: \(version.title)\n\n" +
            (wasTruncated ? "[Earlier chapter text omitted]\n" : "") + tail
        if request.promptKind == .discussion {
            return makeSection(
                kind: .chapterContext(version.id),
                label: "CURRENT MANUSCRIPT TAIL",
                content: content,
                reason: .currentChapterTail
            )
        }
        if request.promptKind == .proseContinuation {
            return makeSection(
                kind: .chapterContext(version.id),
                label: "CURRENT CHAPTER TAIL",
                content: content,
                reason: .currentChapterTail
            )
        }
        return makeSection(
            kind: .chapterContext(version.id),
            label: "PREVIOUS CHAPTER TAIL",
            content: content,
            reason: .previousChapterTail
        )
    }

    static func effectiveMaterials(
        document: NovelProjectDocumentV1,
        branch: NovelBranchRecord
    ) throws -> [MaterialSource] {
        do {
            return try NovelMaterialResolver.effectiveRevisions(
                document: document,
                branch: branch
            ).map {
                MaterialSource(
                    material: $0.material,
                    revision: $0.revision,
                    isBranchOverride: $0.isBranchOverride
                )
            }
        } catch NovelMaterialResolutionError.missingRevision(let revisionID) {
            throw NovelInjectionPlanningError.missingMaterialRevision(revisionID)
        }
    }

    static func classifyMaterials(
        _ sources: [MaterialSource],
        request: NovelInjectionPlanningRequest,
        query: String
    ) throws -> [ClassifiedMaterial] {
        let sourceIDs = Set(sources.map { $0.material.id })
        let forcedInclude = Set(request.overrides.forceIncludeMaterialIDs)
        let forcedExclude = Set(request.overrides.forceExcludeMaterialIDs)
        guard forcedInclude.isSubset(of: sourceIDs), forcedExclude.isSubset(of: sourceIDs) else {
            throw NovelInjectionPlanningError.invalidInput(
                "A temporary injection override references a missing material."
            )
        }

        return sources.map { source in
            let rendered = renderMaterial(source)
            let tokenCount = estimatedTokens(rendered)
            if source.isBranchOverride {
                return ClassifiedMaterial(
                    source: source,
                    reason: .branchOverride,
                    relevanceScore: Int.max,
                    isProtected: true,
                    isSmartCandidate: false,
                    estimatedTokens: tokenCount
                )
            }
            if forcedExclude.contains(source.material.id) {
                return ClassifiedMaterial(
                    source: source,
                    reason: .forceExcluded,
                    relevanceScore: 0,
                    isProtected: false,
                    isSmartCandidate: false,
                    estimatedTokens: tokenCount
                )
            }
            if source.revision.injectionMode == .always {
                return ClassifiedMaterial(
                    source: source,
                    reason: .always,
                    relevanceScore: 0,
                    isProtected: true,
                    isSmartCandidate: false,
                    estimatedTokens: tokenCount
                )
            }
            if forcedInclude.contains(source.material.id) {
                return ClassifiedMaterial(
                    source: source,
                    reason: .forceIncluded,
                    relevanceScore: 0,
                    isProtected: true,
                    isSmartCandidate: false,
                    estimatedTokens: tokenCount
                )
            }
            if source.revision.injectionMode == .off {
                return ClassifiedMaterial(
                    source: source,
                    reason: .disabled,
                    relevanceScore: 0,
                    isProtected: false,
                    isSmartCandidate: false,
                    estimatedTokens: tokenCount
                )
            }
            let score = relevanceScore(source.revision, query: query)
            return ClassifiedMaterial(
                source: source,
                reason: score > 0 ? .smartMatch : .noSmartMatch,
                relevanceScore: score,
                isProtected: false,
                isSmartCandidate: score > 0,
                estimatedTokens: tokenCount
            )
        }
    }

    static func smartQuery(
        userText: String,
        state: NovelStateSnapshotRecord,
        chapterContent: String
    ) -> String {
        normalized([userText, state.summary, state.branchOutline, chapterContent].joined(separator: "\n"))
    }

    static func relevanceScore(
        _ revision: NovelMaterialRevisionRecord,
        query: String
    ) -> Int {
        guard !query.isEmpty else { return 0 }
        var score = 0
        let title = normalized(revision.title)
        if title.count >= 2, query.contains(title) { score += 100 }
        let tags = Set(revision.tags.map(normalized).filter { $0.count >= 2 })
        score += tags.filter(query.contains).count * 20

        let content = normalized(revision.content)
        let queryTerms = Set(query.components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { $0.count >= 3 })
        score += min(20, queryTerms.filter(content.contains).count)
        return score
    }

    static func storyEventCandidates(
        document: NovelProjectDocumentV1,
        state: NovelStateSnapshotRecord,
        query: String
    ) throws -> [ClassifiedStoryEvent] {
        let eventsByID = Dictionary(
            document.events.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        return try state.eventIDs.map { eventID in
            guard let event = eventsByID[eventID] else {
                throw NovelInjectionPlanningError.invalidInput(
                    "The current branch state references unavailable story event \(eventID)."
                )
            }
            return ClassifiedStoryEvent(
                event: event,
                relevanceScore: storyEventRelevance(event, query: query)
            )
        }
    }

    static func storyEventRelevance(
        _ event: NovelStoryEventRecord,
        query: String
    ) -> Int {
        guard !query.isEmpty else { return 0 }
        var score = 0
        for entity in Set(event.entityReferences.map(normalized)) where entity.count >= 2 {
            if query.contains(entity) { score += 100 }
        }
        let kind = normalized(event.kind)
        if kind.count >= 2, query.contains(kind) { score += 30 }

        let summary = normalized(event.summary)
        let queryTerms = Set(query.components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { $0.count >= 3 })
        score += min(30, queryTerms.filter(summary.contains).count * 3)
        return score
    }

    static func makeStoryEventSection(
        _ candidate: ClassifiedStoryEvent
    ) -> NovelInjectionSection {
        let event = candidate.event
        let entities = event.entityReferences
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
        return makeSection(
            kind: .storyEvent(event.id),
            label: "BRANCH EVENT #\(event.sequence)",
            content: "Kind: \(event.kind)\nEntities: \(entities)\n\n\(event.summary)",
            reason: .branchEventHistory
        )
    }

    static func makeMaterialSection(_ candidate: ClassifiedMaterial) -> NovelInjectionSection {
        makeSection(
            kind: .material(candidate.source.revision.id),
            label: "PROJECT MATERIAL",
            content: renderMaterial(candidate.source),
            reason: candidate.reason
        )
    }

    static func renderMaterial(_ source: MaterialSource) -> String {
        let tags = source.revision.tags
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .sorted()
            .joined(separator: ", ")
        let aliases = source.material.kind == .character && !source.material.aliases.isEmpty
            ? source.material.aliases.joined(separator: ", ")
            : "(none)"
        return "Type: \(materialKindName(source.material.kind))\n" +
            "Title: \(source.revision.title)\n" +
            "Aliases: \(aliases)\n" +
            "Tags: \(tags)\n\n" + source.revision.content
    }

    static func makeSessionSection(
        _ message: NovelSessionMessageRecord
    ) -> NovelInjectionSection {
        let interaction: String
        switch message.interaction {
        case .askUser(let prompt):
            interaction = "Question: \(prompt.question)"
        case .askUserAnswer(let response):
            interaction = "Answer: \(response.answer)"
        case nil:
            interaction = ""
        }
        let body = [message.content, interaction]
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
        return makeSection(
            kind: .sessionMessage(message.id),
            label: "RECENT SESSION MESSAGE #\(message.sequence)",
            content: "Role: \(message.role.rawValue)\n\(body)",
            reason: .recentSession
        )
    }

    static func makeSection(
        kind: NovelInjectionSectionKind,
        label: String,
        content: String,
        reason: NovelInjectionSelectionReason
    ) -> NovelInjectionSection {
        NovelInjectionSection(
            kind: kind,
            label: label,
            content: content,
            reason: reason,
            estimatedTokens: estimatedTokens("[\(label)]\n\(content)"),
            contentSHA256: sha256(content)
        )
    }

    static func orderedSections(
        prompt: NovelInjectionSection,
        polishPreference: NovelInjectionSection?,
        state: NovelInjectionSection,
        seed: NovelInjectionSection?,
        chapter: NovelInjectionSection?,
        sessions: [NovelInjectionSection],
        events: [NovelInjectionSection],
        materials: [NovelInjectionSection],
        user: NovelInjectionSection
    ) -> [NovelInjectionSection] {
        var result = [prompt]
        if let polishPreference { result.append(polishPreference) }
        result.append(state)
        if let seed { result.append(seed) }
        if let chapter { result.append(chapter) }
        result.append(contentsOf: sessions.sorted(by: sessionSectionOrder))
        result.append(contentsOf: events)
        result.append(contentsOf: materials)
        result.append(user)
        return result
    }

    static func render(_ sections: [NovelInjectionSection]) -> String {
        sections.map { "[\($0.label)]\n\($0.content)" }.joined(separator: "\n\n")
    }

    static func estimatedTokens(_ text: String) -> Int {
        guard !text.isEmpty else { return 0 }
        return max(1, (text.utf8.count + 3) / 4)
    }

    static func sha256(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }

    static func normalized(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func materialKindName(_ kind: NovelMaterialKind) -> String {
        switch kind {
        case .world: "world"
        case .character: "character"
        case .masterOutline: "master-outline"
        case .writingRequirements: "writing-requirements"
        case .custom(let name): "custom:\(name)"
        }
    }

    static func materialKindRank(_ kind: NovelMaterialKind) -> Int {
        switch kind {
        case .world: 0
        case .character: 1
        case .masterOutline: 2
        case .writingRequirements: 3
        case .custom: 4
        }
    }

    static func materialStableOrder(
        _ lhs: ClassifiedMaterial,
        _ rhs: ClassifiedMaterial
    ) -> Bool {
        let lhsRank = materialKindRank(lhs.source.material.kind)
        let rhsRank = materialKindRank(rhs.source.material.kind)
        if lhsRank != rhsRank { return lhsRank < rhsRank }
        let lhsTitle = normalized(lhs.source.revision.title)
        let rhsTitle = normalized(rhs.source.revision.title)
        if lhsTitle != rhsTitle { return lhsTitle < rhsTitle }
        return lhs.source.material.id.description < rhs.source.material.id.description
    }

    static func materialPriority(
        _ lhs: ClassifiedMaterial,
        _ rhs: ClassifiedMaterial
    ) -> Bool {
        let lhsRank = protectedReasonRank(lhs.reason)
        let rhsRank = protectedReasonRank(rhs.reason)
        if lhsRank != rhsRank { return lhsRank < rhsRank }
        return materialStableOrder(lhs, rhs)
    }

    static func smartPriority(
        _ lhs: ClassifiedMaterial,
        _ rhs: ClassifiedMaterial
    ) -> Bool {
        if lhs.relevanceScore != rhs.relevanceScore {
            return lhs.relevanceScore > rhs.relevanceScore
        }
        return materialStableOrder(lhs, rhs)
    }

    static func storyEventPriority(
        _ lhs: ClassifiedStoryEvent,
        _ rhs: ClassifiedStoryEvent
    ) -> Bool {
        if lhs.relevanceScore != rhs.relevanceScore {
            return lhs.relevanceScore > rhs.relevanceScore
        }
        if lhs.event.sequence != rhs.event.sequence {
            return lhs.event.sequence > rhs.event.sequence
        }
        return lhs.event.id.description < rhs.event.id.description
    }

    static func storyEventChronologicalOrder(
        _ lhs: ClassifiedStoryEvent,
        _ rhs: ClassifiedStoryEvent
    ) -> Bool {
        if lhs.event.sequence != rhs.event.sequence {
            return lhs.event.sequence < rhs.event.sequence
        }
        return lhs.event.id.description < rhs.event.id.description
    }

    static func protectedReasonRank(_ reason: NovelInjectionSelectionReason) -> Int {
        switch reason {
        case .branchOverride: 0
        case .always: 1
        case .forceIncluded: 2
        default: 3
        }
    }

    static func sessionSectionOrder(
        _ lhs: NovelInjectionSection,
        _ rhs: NovelInjectionSection
    ) -> Bool {
        guard case .sessionMessage(let lhsID) = lhs.kind,
              case .sessionMessage(let rhsID) = rhs.kind else {
            return lhs.label < rhs.label
        }
        let lhsSequence = Int64(lhs.label.components(separatedBy: "#").last ?? "") ?? 0
        let rhsSequence = Int64(rhs.label.components(separatedBy: "#").last ?? "") ?? 0
        if lhsSequence != rhsSequence { return lhsSequence < rhsSequence }
        return lhsID.description < rhsID.description
    }
}
