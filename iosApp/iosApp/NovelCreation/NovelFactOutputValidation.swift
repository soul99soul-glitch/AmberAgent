import Foundation

extension NovelFactTransactionReducer {
    struct StoryEventDraft {
        let stableID: String
        let kind: String
        let summary: String
        let entityReferences: [String]
        let chronologicalGroup: Int
        let evidenceOffset: Int
        let stableOrder: Int
    }

    private struct RawStoryEventDraft {
        let stableID: String
        let kind: String
        let summary: String
        let entityReferences: [String]
        let evidence: String
    }

    static func validate(_ value: NovelStateDeltaV1) throws -> NovelStateDeltaV1 {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let encodedObject = try JSONSerialization.jsonObject(with: encoder.encode(value))
        guard var object = encodedObject as? [String: Any] else {
            throw NovelError.invalidInput("The state delta is not a JSON object.")
        }
        if value.branchOutlinePatch == nil {
            object["branchOutlinePatch"] = NSNull()
        }
        return try NovelStructuredOutputDecoder.decodeStateDelta(
            from: JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        )
    }

    static func validate(_ value: NovelStateRebuildV1) throws -> NovelStateRebuildV1 {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try NovelStructuredOutputDecoder.decodeStateRebuild(from: encoder.encode(value))
    }

    static func validateManualChunkOutput(
        _ value: NovelStateRebuildV1,
        evidenceSource: String,
        accumulated: NovelStateRebuildV1?,
        baseState: NovelStateSnapshotRecord,
        branchID: NovelBranchID,
        in document: NovelProjectDocumentV1
    ) throws -> NovelStateRebuildV1 {
        let validated = try validate(value)
        guard let branch = document.branches.first(where: { $0.id == branchID }) else {
            throw NovelError.branchNotFound(branchID)
        }
        let projectedBase = NovelStateSnapshotRecord(
            id: baseState.id,
            eventIDs: baseState.eventIDs,
            summary: accumulated?.stateSummary ?? baseState.summary,
            branchOutline: accumulated?.branchOutline ?? baseState.branchOutline,
            unresolvedEntityNames: accumulated?.unresolvedEntityNames ??
                baseState.unresolvedEntityNames,
            createdAt: baseState.createdAt,
            settingProposalIDs: baseState.settingProposalIDs
        )
        let sanitized = try sanitizedManualRebuild(
            validated,
            evidenceSource: evidenceSource,
            branch: branch,
            baseState: projectedBase,
            document: document
        )
        try validateStateFacts(
            sanitized,
            evidenceSource: evidenceSource,
            branch: branch,
            baseState: projectedBase,
            in: document
        )
        return sanitized
    }

    static func validateStateFacts(
        _ value: NovelStateDeltaV1,
        evidenceSource: String,
        branch: NovelBranchRecord,
        baseState: NovelStateSnapshotRecord,
        in document: NovelProjectDocumentV1
    ) throws {
        let effectiveBaseState = try effectiveStateSnapshot(
            baseState,
            branch: branch,
            document: document
        )
        let factEvidence = value.events.map(\.evidence) +
            value.characterChanges.map(\.evidence) +
            value.relationshipChanges.map(\.evidence) +
            value.foreshadowingChanges.map(\.evidence)
        try validateEvidence(
            factEvidence + value.settingProposals.map(\.evidence),
            in: evidenceSource
        )
        try validateDerivedStateChange(
            stateSummary: value.stateSummary,
            branchOutline: value.branchOutlinePatch ?? baseState.branchOutline,
            unresolved: value.unresolvedEntityNames,
            baseState: effectiveBaseState,
            hasEvidenceBackedFacts: !factEvidence.isEmpty
        )
        try validateEntities(
            eventReferences: value.events.flatMap(\.entityReferences),
            characterNames: value.characterChanges.map(\.characterName),
            relationshipNames: value.relationshipChanges.flatMap {
                [$0.sourceEntity, $0.targetEntity]
            },
            unresolved: value.unresolvedEntityNames,
            branch: branch,
            baseUnresolved: effectiveBaseState.unresolvedEntityNames,
            in: document
        )
    }

    static func sanitizedCollectionDelta(
        in value: NovelStateDeltaV1,
        evidenceSource: String,
        branch: NovelBranchRecord,
        baseState: NovelStateSnapshotRecord,
        document: NovelProjectDocumentV1
    ) throws -> NovelStateDeltaV1 {
        let events = value.events.filter { evidenceMatches($0.evidence, in: evidenceSource) }
        let characterChanges = value.characterChanges.filter {
            evidenceMatches($0.evidence, in: evidenceSource)
        }
        let relationshipChanges = value.relationshipChanges.filter {
            evidenceMatches($0.evidence, in: evidenceSource)
        }
        let foreshadowingChanges = value.foreshadowingChanges.filter {
            evidenceMatches($0.evidence, in: evidenceSource)
        }
        let hasEvidenceBackedFacts = !events.isEmpty ||
            !characterChanges.isEmpty ||
            !relationshipChanges.isEmpty ||
            !foreshadowingChanges.isEmpty
        let rawHasFacts = !value.events.isEmpty ||
            !value.characterChanges.isEmpty ||
            !value.relationshipChanges.isEmpty ||
            !value.foreshadowingChanges.isEmpty
        try requireEvidenceNotAllDiscarded(
            rawHasFacts: rawHasFacts,
            hasEvidenceBackedFacts: hasEvidenceBackedFacts
        )
        let settingProposals = value.settingProposals.filter {
            evidenceMatches($0.evidence, in: evidenceSource)
        }

        let referenced = events.flatMap(\.entityReferences) +
            characterChanges.map(\.characterName) +
            relationshipChanges.flatMap { [$0.sourceEntity, $0.targetEntity] }
        let unresolved = try sanitizedUnresolvedEntityNames(
            base: baseState.unresolvedEntityNames,
            referenced: referenced,
            branch: branch,
            document: document
        )
        return NovelStateDeltaV1(
            schemaVersion: value.schemaVersion,
            stateSummary: hasEvidenceBackedFacts ? value.stateSummary : baseState.summary,
            events: events,
            characterChanges: characterChanges,
            relationshipChanges: relationshipChanges,
            foreshadowingChanges: foreshadowingChanges,
            unresolvedEntityNames: unresolved,
            branchOutlinePatch: hasEvidenceBackedFacts ? value.branchOutlinePatch : nil,
            settingProposals: settingProposals
        )
    }

    static func validateStateFacts(
        _ value: NovelStateRebuildV1,
        evidenceSource: String,
        branch: NovelBranchRecord,
        baseState: NovelStateSnapshotRecord,
        in document: NovelProjectDocumentV1
    ) throws {
        let effectiveBaseState = try effectiveStateSnapshot(
            baseState,
            branch: branch,
            document: document
        )
        let factEvidence = value.events.map(\.evidence) +
            value.characterStates.map(\.evidence) +
            value.relationships.map(\.evidence) +
            value.foreshadowing.map(\.evidence)
        try validateEvidence(
            factEvidence + value.settingProposals.map(\.evidence),
            in: evidenceSource
        )
        try validateDerivedStateChange(
            stateSummary: value.stateSummary,
            branchOutline: value.branchOutline,
            unresolved: value.unresolvedEntityNames,
            baseState: effectiveBaseState,
            hasEvidenceBackedFacts: !factEvidence.isEmpty
        )
        try validateEntities(
            eventReferences: value.events.flatMap(\.entityReferences),
            characterNames: value.characterStates.map(\.characterName),
            relationshipNames: value.relationships.flatMap {
                [$0.sourceEntity, $0.targetEntity]
            },
            unresolved: value.unresolvedEntityNames,
            branch: branch,
            baseUnresolved: effectiveBaseState.unresolvedEntityNames,
            in: document
        )
    }

    private static func sanitizedManualRebuild(
        _ value: NovelStateRebuildV1,
        evidenceSource: String,
        branch: NovelBranchRecord,
        baseState: NovelStateSnapshotRecord,
        document: NovelProjectDocumentV1
    ) throws -> NovelStateRebuildV1 {
        let events = value.events.filter { evidenceMatches($0.evidence, in: evidenceSource) }
        let characterStates = value.characterStates.filter {
            evidenceMatches($0.evidence, in: evidenceSource)
        }
        let relationships = value.relationships.filter {
            evidenceMatches($0.evidence, in: evidenceSource)
        }
        let foreshadowing = value.foreshadowing.filter {
            evidenceMatches($0.evidence, in: evidenceSource)
        }
        let settingProposals = value.settingProposals.filter {
            evidenceMatches($0.evidence, in: evidenceSource)
        }
        let hasEvidenceBackedFacts = !events.isEmpty ||
            !characterStates.isEmpty ||
            !relationships.isEmpty ||
            !foreshadowing.isEmpty
        let rawHasFacts = !value.events.isEmpty ||
            !value.characterStates.isEmpty ||
            !value.relationships.isEmpty ||
            !value.foreshadowing.isEmpty
        try requireEvidenceNotAllDiscarded(
            rawHasFacts: rawHasFacts,
            hasEvidenceBackedFacts: hasEvidenceBackedFacts
        )
        let referenced = events.flatMap(\.entityReferences) +
            characterStates.map(\.characterName) +
            relationships.flatMap { [$0.sourceEntity, $0.targetEntity] }
        let unresolved = try sanitizedUnresolvedEntityNames(
            base: baseState.unresolvedEntityNames,
            referenced: referenced,
            branch: branch,
            document: document
        )
        return NovelStateRebuildV1(
            schemaVersion: value.schemaVersion,
            stateSummary: hasEvidenceBackedFacts ? value.stateSummary : baseState.summary,
            branchOutline: hasEvidenceBackedFacts ? value.branchOutline : baseState.branchOutline,
            events: events,
            characterStates: characterStates,
            relationships: relationships,
            foreshadowing: foreshadowing,
            unresolvedEntityNames: unresolved,
            settingProposals: settingProposals
        )
    }

    static func storyEventDrafts(
        _ value: NovelStateDeltaV1,
        evidenceSource: String
    ) throws -> [StoryEventDraft] {
        try chronologicalDrafts(
            rawStoryEventDrafts(value),
            evidenceSource: evidenceSource,
            chronologicalGroup: 0,
            stableIDPrefix: ""
        )
    }

    static func storyEventDrafts(
        _ value: NovelStateRebuildV1,
        evidenceSource: String
    ) throws -> [StoryEventDraft] {
        try chronologicalDrafts(
            rawStoryEventDrafts(value),
            evidenceSource: evidenceSource,
            chronologicalGroup: 0,
            stableIDPrefix: ""
        )
    }

    static func storyEventDrafts(
        _ progress: NovelManualSyncProgress,
        manuscript: String
    ) throws -> [StoryEventDraft] {
        let characters = Array(manuscript)
        return try progress.completedChunks.flatMap { chunk in
            guard chunk.startCharacterOffset >= 0,
                  chunk.endCharacterOffset > chunk.startCharacterOffset,
                  chunk.endCharacterOffset <= characters.count else {
                throw NovelError.invalidInput(
                    "Manual-sync event ordering encountered an invalid chunk range."
                )
            }
            let evidenceSource = String(
                characters[chunk.startCharacterOffset..<chunk.endCharacterOffset]
            )
            let namespaced = NovelManualSyncProgressReducer.merge(
                chunk.rebuild,
                into: nil,
                chunkIndex: chunk.index
            )
            return try chronologicalDrafts(
                rawStoryEventDrafts(namespaced),
                evidenceSource: evidenceSource,
                chronologicalGroup: chunk.index,
                stableIDPrefix: ""
            )
        }
    }

    static func storyEvents(
        _ drafts: [StoryEventDraft],
        namespace: NovelOperationID,
        baseState: NovelStateSnapshotRecord,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> [NovelStoryEventRecord] {
        for eventID in baseState.eventIDs where
            !document.events.contains(where: { $0.id == eventID }) {
            throw NovelError.invalidInput("The base state references a missing event.")
        }
        let orderedDrafts = drafts.sorted { lhs, rhs in
            if lhs.chronologicalGroup != rhs.chronologicalGroup {
                return lhs.chronologicalGroup < rhs.chronologicalGroup
            }
            if lhs.evidenceOffset != rhs.evidenceOffset {
                return lhs.evidenceOffset < rhs.evidenceOffset
            }
            if lhs.stableOrder != rhs.stableOrder {
                return lhs.stableOrder < rhs.stableOrder
            }
            return lhs.stableID < rhs.stableID
        }
        let baseSequence = document.events.map(\.sequence).max() ?? -1
        var ids: Set<NovelEventID> = []
        return try orderedDrafts.enumerated().map { index, draft in
            let id = NovelEventID(deterministicUUID(
                namespace: namespace,
                category: "event",
                stableID: draft.stableID
            ))
            guard ids.insert(id).inserted,
                  document.events.allSatisfy({ $0.id != id }) else {
                throw NovelError.immutableRecordConflict("event \(id)")
            }
            return NovelStoryEventRecord(
                id: id,
                sequence: baseSequence + Int64(index) + 1,
                kind: draft.kind,
                summary: draft.summary,
                entityReferences: draft.entityReferences,
                createdAt: now
            )
        }
    }

    static func settingProposals(
        _ drafts: [NovelSettingProposalDraftV1],
        namespace: NovelOperationID,
        branchID: NovelBranchID,
        in document: NovelProjectDocumentV1,
        now: Date
    ) throws -> [NovelSettingProposalRecord] {
        var ids: Set<NovelProposalID> = []
        return try drafts.map { draft in
            let id = NovelProposalID(deterministicUUID(
                namespace: namespace,
                category: "proposal",
                stableID: draft.id
            ))
            guard ids.insert(id).inserted,
                  document.settingProposals.allSatisfy({ $0.id != id }) else {
                throw NovelError.immutableRecordConflict("setting proposal \(id)")
            }
            return NovelSettingProposalRecord(
                id: id,
                branchID: branchID,
                title: draft.title,
                content: draft.content,
                createdAt: now,
                isResolved: false,
                origin: .derivedState
            )
        }
    }

    private static func validateDerivedStateChange(
        stateSummary: String,
        branchOutline: String,
        unresolved: [String],
        baseState: NovelStateSnapshotRecord,
        hasEvidenceBackedFacts: Bool
    ) throws {
        let changed = stateSummary != baseState.summary ||
            branchOutline != baseState.branchOutline ||
            Set(unresolved.map(normalizedEntity)) !=
                Set(baseState.unresolvedEntityNames.map(normalizedEntity))
        guard !changed || hasEvidenceBackedFacts else {
            throw NovelError.invalidInput(
                "Derived summary, outline, or unresolved entities changed without evidence-backed facts."
            )
        }
    }

    static func effectiveStateSnapshot(
        _ state: NovelStateSnapshotRecord,
        branch: NovelBranchRecord,
        document: NovelProjectDocumentV1
    ) throws -> NovelStateSnapshotRecord {
        let unresolved = try sanitizedUnresolvedEntityNames(
            base: state.unresolvedEntityNames,
            referenced: [],
            branch: branch,
            document: document
        )
        guard unresolved != state.unresolvedEntityNames else { return state }
        return NovelStateSnapshotRecord(
            id: state.id,
            eventIDs: state.eventIDs,
            summary: state.summary,
            branchOutline: state.branchOutline,
            unresolvedEntityNames: unresolved,
            createdAt: state.createdAt,
            settingProposalIDs: state.settingProposalIDs
        )
    }

    private static func validateEvidence(
        _ evidence: [String],
        in manuscript: String
    ) throws {
        let source = normalizeEvidenceWhitespace(manuscript)
        for item in evidence {
            let normalized = normalizeEvidenceWhitespace(item)
            guard !normalized.isEmpty,
                  source.contains(normalized) else {
                throw NovelError.invalidInput(
                    "A derived fact contains evidence outside the authoritative manuscript."
                )
            }
        }
    }

    private static func evidenceMatches(_ evidence: String, in manuscript: String) -> Bool {
        let source = normalizeEvidenceWhitespace(manuscript)
        let normalized = normalizeEvidenceWhitespace(evidence)
        return !normalized.isEmpty && source.contains(normalized)
    }

    private static func sanitizedUnresolvedEntityNames(
        base: [String],
        referenced: [String],
        branch: NovelBranchRecord,
        document: NovelProjectDocumentV1
    ) throws -> [String] {
        let effective: [NovelEffectiveMaterialRevision]
        do {
            effective = try NovelMaterialResolver.effectiveRevisions(
                document: document,
                branch: branch
            )
        } catch NovelMaterialResolutionError.missingRevision(let revisionID) {
            throw NovelError.invalidInput(
                "The branch references missing material revision \(revisionID)."
            )
        }
        let known = knownEntityNames(in: effective)
        var unresolved: [String] = []
        var unresolvedKeys: Set<String> = []
        for name in base + referenced {
            let key = normalizedEntity(name)
            guard !key.isEmpty,
                  !known.contains(key),
                  unresolvedKeys.insert(key).inserted else { continue }
            unresolved.append(name)
        }
        return unresolved
    }

    private static func validateEntities(
        eventReferences: [String],
        characterNames: [String],
        relationshipNames: [String],
        unresolved: [String],
        branch: NovelBranchRecord,
        baseUnresolved: [String],
        in document: NovelProjectDocumentV1
    ) throws {
        let effective: [NovelEffectiveMaterialRevision]
        do {
            effective = try NovelMaterialResolver.effectiveRevisions(
                document: document,
                branch: branch
            )
        } catch NovelMaterialResolutionError.missingRevision(let revisionID) {
            throw NovelError.invalidInput(
                "The branch references missing material revision \(revisionID)."
            )
        }
        let known = knownEntityNames(in: effective)
        let unresolvedKeys = Set(unresolved.map(normalizedEntity))
        let baseUnresolvedKeys = Set(baseUnresolved.map(normalizedEntity))
        let referenced = eventReferences + characterNames + relationshipNames
        let referencedKeys = Set(referenced.map(normalizedEntity))
        let newlyUnresolved = unresolvedKeys.subtracting(baseUnresolvedKeys)
        guard newlyUnresolved.isSubset(of: referencedKeys) else {
            throw NovelError.invalidInput(
                "A newly unresolved entity is not referenced by an evidence-backed fact."
            )
        }
        guard unresolvedKeys.isDisjoint(with: known) else {
            throw NovelError.invalidInput(
                "A known project material cannot be listed as an unresolved entity."
            )
        }
        guard baseUnresolvedKeys.subtracting(known).isSubset(of: unresolvedKeys) else {
            throw NovelError.invalidInput(
                "An unresolved entity disappeared without a matching project material."
            )
        }
        for name in referenced {
            let key = normalizedEntity(name)
            guard known.contains(key) || unresolvedKeys.contains(key) else {
                throw NovelError.invalidInput(
                    "Unknown entity '\(name)' must be listed as unresolved."
                )
            }
        }
    }

    private static func knownEntityNames(
        in revisions: [NovelEffectiveMaterialRevision]
    ) -> Set<String> {
        let identities = revisions.compactMap { item -> NovelCharacterIdentity? in
            guard item.material.kind == .character else { return nil }
            return NovelCharacterIdentity(
                materialID: item.material.id,
                canonicalName: item.revision.title,
                aliases: item.material.aliases
            )
        }
        let resolver = NovelCharacterIdentityResolver(identities: identities)
        let aliases = identities.flatMap(\.aliases).filter(resolver.isKnown)
        return Set((revisions.map { $0.revision.title } + aliases).map(normalizedEntity))
    }

    private static func rawStoryEventDrafts(
        _ value: NovelStateDeltaV1
    ) -> [RawStoryEventDraft] {
        value.events.map {
            RawStoryEventDraft(
                stableID: $0.id,
                kind: $0.kind,
                summary: $0.summary,
                entityReferences: $0.entityReferences,
                evidence: $0.evidence
            )
        } + value.characterChanges.map {
            RawStoryEventDraft(
                stableID: $0.id,
                kind: "character.\($0.attribute)",
                summary: "\($0.characterName): \($0.attribute) = \($0.value)",
                entityReferences: [$0.characterName],
                evidence: $0.evidence
            )
        } + value.relationshipChanges.map {
            RawStoryEventDraft(
                stableID: $0.id,
                kind: "relationship.\($0.relationship)",
                summary: "\($0.sourceEntity) -> \($0.targetEntity): \($0.state)",
                entityReferences: [$0.sourceEntity, $0.targetEntity],
                evidence: $0.evidence
            )
        } + value.foreshadowingChanges.map {
            RawStoryEventDraft(
                stableID: $0.id,
                kind: "foreshadowing.\($0.status.rawValue)",
                summary: "\($0.thread): \($0.summary)",
                entityReferences: [],
                evidence: $0.evidence
            )
        }
    }

    private static func rawStoryEventDrafts(
        _ value: NovelStateRebuildV1
    ) -> [RawStoryEventDraft] {
        value.events.map {
            RawStoryEventDraft(
                stableID: $0.id,
                kind: $0.kind,
                summary: $0.summary,
                entityReferences: $0.entityReferences,
                evidence: $0.evidence
            )
        } + value.characterStates.map {
            RawStoryEventDraft(
                stableID: $0.id,
                kind: "character.\($0.attribute)",
                summary: "\($0.characterName): \($0.attribute) = \($0.value)",
                entityReferences: [$0.characterName],
                evidence: $0.evidence
            )
        } + value.relationships.map {
            RawStoryEventDraft(
                stableID: $0.id,
                kind: "relationship.\($0.relationship)",
                summary: "\($0.sourceEntity) -> \($0.targetEntity): \($0.state)",
                entityReferences: [$0.sourceEntity, $0.targetEntity],
                evidence: $0.evidence
            )
        } + value.foreshadowing.map {
            RawStoryEventDraft(
                stableID: $0.id,
                kind: "foreshadowing.\($0.status.rawValue)",
                summary: "\($0.thread): \($0.summary)",
                entityReferences: [],
                evidence: $0.evidence
            )
        }
    }

    private static func chronologicalDrafts(
        _ drafts: [RawStoryEventDraft],
        evidenceSource: String,
        chronologicalGroup: Int,
        stableIDPrefix: String
    ) throws -> [StoryEventDraft] {
        let normalizedSource = normalizeEvidenceWhitespace(evidenceSource)
        return try drafts.enumerated().map { stableOrder, draft in
            let evidence = normalizeEvidenceWhitespace(draft.evidence)
            guard !evidence.isEmpty,
                  let range = normalizedSource.range(of: evidence) else {
                throw NovelError.invalidInput(
                    "A story event cannot be ordered outside the authoritative manuscript."
                )
            }
            return StoryEventDraft(
                stableID: stableIDPrefix + draft.stableID,
                kind: draft.kind,
                summary: draft.summary,
                entityReferences: draft.entityReferences,
                chronologicalGroup: chronologicalGroup,
                evidenceOffset: normalizedSource.distance(
                    from: normalizedSource.startIndex,
                    to: range.lowerBound
                ),
                stableOrder: stableOrder
            )
        }
    }

    /// Judges whether a structured-output evidence check discarded every
    /// fact it was given: the model returned at least one fact
    /// (`rawHasFacts`), but none of them survived evidence matching
    /// (`!hasEvidenceBackedFacts`). A model that legitimately extracted no
    /// facts at all (`!rawHasFacts`) is not a failure — that is a valid
    /// "nothing changed this chapter" result and must keep committing.
    private static func requireEvidenceNotAllDiscarded(
        rawHasFacts: Bool,
        hasEvidenceBackedFacts: Bool
    ) throws {
        guard rawHasFacts && !hasEvidenceBackedFacts else { return }
        throw NovelStructuredModelExecutionFailure(
            code: "state_facts_evidence_unmatched",
            message: "模型给出的证据文字与正文对不上，本次状态同步未写入任何内容，请重试。",
            isRetryable: true
        )
    }

    private static func normalizeEvidenceWhitespace(_ value: String) -> String {
        normalizePrintingVariants(value)
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
    }

    /// Normalizes typographic variants that a model commonly introduces when
    /// transcribing evidence (curly vs. straight quotes, fullwidth vs.
    /// halfwidth punctuation/digits/letters, ellipsis runs, and dash
    /// variants) so semantically identical text is not rejected as
    /// fabricated evidence. This is a pure character-level substitution
    /// applied identically to both the manuscript and the evidence — it
    /// never folds CJK script variants (e.g. simplified/traditional) and
    /// never performs fuzzy matching, so it cannot let fabricated content
    /// pass as authoritative evidence.
    private static func normalizePrintingVariants(_ value: String) -> String {
        let widthNormalized = value.folding(options: [.widthInsensitive], locale: nil)
        let ellipsisNormalized = widthNormalized.replacingOccurrences(
            of: "[\u{2026}]+|\\.{2,}",
            with: "\u{2026}",
            options: .regularExpression
        )
        let quoteAndDash: [Character: Character] = [
            "\u{201C}": "\"", "\u{201D}": "\"", "\u{2018}": "\"", "\u{2019}": "\"",
            "\u{300C}": "\"", "\u{300D}": "\"", "\u{300E}": "\"", "\u{300F}": "\"",
            "'": "\"",
            "\u{2014}": "-", "\u{2013}": "-", "\u{2010}": "-", "\u{2212}": "-",
        ]
        return String(ellipsisNormalized.map { quoteAndDash[$0] ?? $0 })
    }

    private static func deterministicUUID(
        namespace: NovelOperationID,
        category: String,
        stableID: String
    ) -> UUID {
        let hex = NovelDocumentValidator.sha256(
            namespace.description + "|" + category + "|" + stableID
        )
        let value = String(hex.prefix(8)) + "-" +
            String(hex.dropFirst(8).prefix(4)) + "-" +
            String(hex.dropFirst(12).prefix(4)) + "-" +
            String(hex.dropFirst(16).prefix(4)) + "-" +
            String(hex.dropFirst(20).prefix(12))
        return UUID(uuidString: value)!
    }

    private static func normalizedEntity(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(
                options: [.caseInsensitive, .diacriticInsensitive],
                locale: Locale(identifier: "en_US_POSIX")
            )
    }
}
