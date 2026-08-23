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

    /// 把一段正文预处理成可反复比对的证据源。调用方对同一段正文只需归一化一次,
    /// 之后每条证据都拿这个结果去问 `isEvidenceAnchored`。
    static func normalizedEvidenceSource(_ manuscript: String) -> String {
        normalizeEvidenceWhitespace(manuscript)
    }

    /// 「这条证据是不是真出自这段正文」的对外入口。内部仍旧走 `evidenceMatches`
    /// 这个唯一所有者(见其文档注释),不另开一套判据。
    static func isEvidenceAnchored(
        _ evidence: String,
        inNormalizedSource source: String
    ) -> Bool {
        evidenceMatches(evidence, inNormalizedManuscript: source)
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

    /// Host-accepted empty rebuild: no facts, summary/outline kept from the
    /// projected base. Blank projects legally store empty snapshot strings;
    /// the model schema does not, so callers must not re-run `validate(_:)`.
    static func preservesBaseWithoutFacts(
        _ rebuild: NovelStateRebuildV1,
        summary: String,
        outline: String
    ) -> Bool {
        rebuild.events.isEmpty &&
            rebuild.characterStates.isEmpty &&
            rebuild.relationships.isEmpty &&
            rebuild.foreshadowing.isEmpty &&
            rebuild.stateSummary == summary &&
            rebuild.branchOutline == outline
    }

    /// Blank-project snapshots legally store empty summary/outline. Rebuild
    /// model-schema `validate` does not. Skip that schema only when the empty
    /// fields are exactly those durable base values.
    static func skipsRebuildModelSchema(
        _ rebuild: NovelStateRebuildV1,
        summary: String,
        outline: String
    ) -> Bool {
        let emptySummary = rebuild.stateSummary
            .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let emptyOutline = rebuild.branchOutline
            .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        guard emptySummary || emptyOutline else { return false }
        return (!emptySummary || rebuild.stateSummary == summary)
            && (!emptyOutline || rebuild.branchOutline == outline)
    }

    static func validateManualChunkOutput(
        _ value: NovelStateRebuildV1,
        evidenceSource: String,
        accumulated: NovelStateRebuildV1?,
        baseState: NovelStateSnapshotRecord,
        branchID: NovelBranchID,
        in document: NovelProjectDocumentV1,
        acceptEmptyFacts: Bool = false
    ) throws -> NovelStateRebuildV1 {
        let schemaBaseSummary = accumulated?.stateSummary ?? baseState.summary
        let schemaBaseOutline = accumulated?.branchOutline ?? baseState.branchOutline
        let validated = skipsRebuildModelSchema(
            value,
            summary: schemaBaseSummary,
            outline: schemaBaseOutline
        ) ? value : try validate(value)
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
            settingProposalIDs: baseState.settingProposalIDs,
            characterIdentityClarifications: baseState.characterIdentityClarifications,
            recentWrittenHighlights: baseState.recentWrittenHighlights
        )
        let sanitized = try sanitizedManualRebuild(
            validated,
            evidenceSource: evidenceSource,
            branch: branch,
            baseState: projectedBase,
            document: document,
            acceptEmptyFacts: acceptEmptyFacts
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
            identityClarifications: effectiveBaseState.characterIdentityClarifications,
            in: document
        )
    }

    static func sanitizedCollectionDelta(
        in value: NovelStateDeltaV1,
        evidenceSource: String,
        branch: NovelBranchRecord,
        baseState: NovelStateSnapshotRecord,
        document: NovelProjectDocumentV1,
        acceptEmptyFacts: Bool = false
    ) throws -> NovelStateDeltaV1 {
        // Normalized once and reused across every filter below, instead of
        // each `evidenceMatches` call re-normalizing the same (potentially
        // multi-thousand-character) manuscript from scratch per fact.
        let normalizedEvidenceSource = normalizeEvidenceWhitespace(evidenceSource)
        let events = partitionEvidence(
            value.events,
            inNormalizedManuscript: normalizedEvidenceSource
        ) { $0.evidence }
        let characterChanges = partitionEvidence(
            value.characterChanges,
            inNormalizedManuscript: normalizedEvidenceSource
        ) { $0.evidence }
        let relationshipChanges = partitionEvidence(
            value.relationshipChanges,
            inNormalizedManuscript: normalizedEvidenceSource
        ) { $0.evidence }
        let foreshadowingChanges = partitionEvidence(
            value.foreshadowingChanges,
            inNormalizedManuscript: normalizedEvidenceSource
        ) { $0.evidence }
        let hasEvidenceBackedFacts = !events.kept.isEmpty ||
            !characterChanges.kept.isEmpty ||
            !relationshipChanges.kept.isEmpty ||
            !foreshadowingChanges.kept.isEmpty
        let rawHasFacts = !value.events.isEmpty ||
            !value.characterChanges.isEmpty ||
            !value.relationshipChanges.isEmpty ||
            !value.foreshadowingChanges.isEmpty
        try requireEvidenceNotAllDiscarded(
            rawHasFacts: rawHasFacts,
            hasEvidenceBackedFacts: hasEvidenceBackedFacts,
            unmatchedEvidence: events.discarded + characterChanges.discarded
                + relationshipChanges.discarded + foreshadowingChanges.discarded,
            acceptEmptyFacts: acceptEmptyFacts
        )
        let settingProposals = value.settingProposals.filter {
            evidenceMatches($0.evidence, inNormalizedManuscript: normalizedEvidenceSource)
                && NovelSettingProposalFilter.isWorthProposing(title: $0.title, content: $0.content)
        }

        let referenced = events.kept.flatMap(\.entityReferences) +
            characterChanges.kept.map(\.characterName) +
            relationshipChanges.kept.flatMap { [$0.sourceEntity, $0.targetEntity] }
        let unresolved = try sanitizedUnresolvedEntityNames(
            base: baseState.unresolvedEntityNames,
            referenced: referenced,
            identityClarifications: baseState.characterIdentityClarifications,
            branch: branch,
            document: document
        )
        return NovelStateDeltaV1(
            schemaVersion: value.schemaVersion,
            stateSummary: hasEvidenceBackedFacts ? value.stateSummary : baseState.summary,
            events: events.kept,
            characterChanges: characterChanges.kept,
            relationshipChanges: relationshipChanges.kept,
            foreshadowingChanges: foreshadowingChanges.kept,
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
            identityClarifications: effectiveBaseState.characterIdentityClarifications,
            in: document
        )
    }

    private static func sanitizedManualRebuild(
        _ value: NovelStateRebuildV1,
        evidenceSource: String,
        branch: NovelBranchRecord,
        baseState: NovelStateSnapshotRecord,
        document: NovelProjectDocumentV1,
        acceptEmptyFacts: Bool = false
    ) throws -> NovelStateRebuildV1 {
        // Normalized once and reused across every filter below, instead of
        // each `evidenceMatches` call re-normalizing the same (potentially
        // multi-thousand-character) manuscript from scratch per fact.
        let normalizedEvidenceSource = normalizeEvidenceWhitespace(evidenceSource)
        let events = partitionEvidence(
            value.events,
            inNormalizedManuscript: normalizedEvidenceSource
        ) { $0.evidence }
        let characterStates = partitionEvidence(
            value.characterStates,
            inNormalizedManuscript: normalizedEvidenceSource
        ) { $0.evidence }
        let relationships = partitionEvidence(
            value.relationships,
            inNormalizedManuscript: normalizedEvidenceSource
        ) { $0.evidence }
        let foreshadowing = partitionEvidence(
            value.foreshadowing,
            inNormalizedManuscript: normalizedEvidenceSource
        ) { $0.evidence }
        let settingProposals = value.settingProposals.filter {
            evidenceMatches($0.evidence, inNormalizedManuscript: normalizedEvidenceSource)
                && NovelSettingProposalFilter.isWorthProposing(title: $0.title, content: $0.content)
        }
        let hasEvidenceBackedFacts = !events.kept.isEmpty ||
            !characterStates.kept.isEmpty ||
            !relationships.kept.isEmpty ||
            !foreshadowing.kept.isEmpty
        let rawHasFacts = !value.events.isEmpty ||
            !value.characterStates.isEmpty ||
            !value.relationships.isEmpty ||
            !value.foreshadowing.isEmpty
        try requireEvidenceNotAllDiscarded(
            rawHasFacts: rawHasFacts,
            hasEvidenceBackedFacts: hasEvidenceBackedFacts,
            unmatchedEvidence: events.discarded + characterStates.discarded
                + relationships.discarded + foreshadowing.discarded,
            acceptEmptyFacts: acceptEmptyFacts
        )
        let referenced = events.kept.flatMap(\.entityReferences) +
            characterStates.kept.map(\.characterName) +
            relationships.kept.flatMap { [$0.sourceEntity, $0.targetEntity] }
        let unresolved = try sanitizedUnresolvedEntityNames(
            base: baseState.unresolvedEntityNames,
            referenced: referenced,
            identityClarifications: baseState.characterIdentityClarifications,
            branch: branch,
            document: document
        )
        return NovelStateRebuildV1(
            schemaVersion: value.schemaVersion,
            stateSummary: hasEvidenceBackedFacts ? value.stateSummary : baseState.summary,
            branchOutline: hasEvidenceBackedFacts ? value.branchOutline : baseState.branchOutline,
            events: events.kept,
            characterStates: characterStates.kept,
            relationships: relationships.kept,
            foreshadowing: foreshadowing.kept,
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
            identityClarifications: state.characterIdentityClarifications,
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
            settingProposalIDs: state.settingProposalIDs,
            characterIdentityClarifications: state.characterIdentityClarifications,
            recentWrittenHighlights: state.recentWrittenHighlights
        )
    }

    private static func validateEvidence(
        _ evidence: [String],
        in manuscript: String
    ) throws {
        let source = normalizeEvidenceWhitespace(manuscript)
        for item in evidence {
            guard evidenceAnchorRange(item, in: source) != nil else {
                throw NovelError.invalidInput(
                    "A derived fact contains evidence outside the authoritative manuscript."
                )
            }
        }
    }

    /// Minimum length, in grapheme clusters (`Character`, not UTF-16 code
    /// units — so CJK/emoji are counted correctly), of a literal run shared
    /// between `evidence` and the manuscript before a paraphrased/summarized
    /// `evidence` string can be accepted via the anchor path below. A bare
    /// 2-3 character Chinese person name (or a handful of common function
    /// words) is not enough on its own to prove the model actually looked at
    /// real text — 8 contiguous characters is long enough that reproducing
    /// it verbatim is a strong signal the model quoted or closely
    /// paraphrased around genuine manuscript text rather than inventing it.
    private static let minimumAnchorLength = 8

    /// Minimum fraction of `evidence`'s own length that the longest shared
    /// literal run must cover. This guards against "mostly fabricated"
    /// evidence that smuggles in one short real phrase merely to clear
    /// `minimumAnchorLength` — e.g. an 80-character invented sentence that
    /// happens to contain one genuine 8-character fragment. 40% keeps the
    /// check tolerant of genuine paraphrase/summarization (the case this
    /// anchor path exists to unblock) while still rejecting evidence that is
    /// predominantly invented.
    private static let minimumAnchorRatio = 0.4

    /// Single owner of "is `evidence` anchored in the manuscript". Every
    /// call site that must decide whether a model-supplied evidence string
    /// corresponds to real manuscript text — filtering out unmatched facts
    /// (`evidenceMatches`), rejecting a request outright
    /// (`validateEvidence`), or ordering facts by where their evidence
    /// occurs (`chronologicalDrafts`) — goes through this function, so the
    /// three call sites can never drift into disagreeing about what counts
    /// as a match. (They previously did: two independent literal-substring
    /// checks plus one that additionally tolerated paraphrase, which let a
    /// fact survive filtering only to be rejected moments later by a
    /// stricter re-check over the very data that had already passed.)
    ///
    /// Matching tries a literal substring first — the fast path, identical
    /// to the original pre-anchor behavior and zero regression risk for the
    /// overwhelming majority of evidence, which quotes the manuscript
    /// verbatim — and only falls back to a paraphrase-tolerant anchor match
    /// (see `anchorRange`) when the literal check fails.
    ///
    /// Returns the matched range within `normalizedManuscript` (rather than
    /// a bare `Bool`) so `chronologicalDrafts` can order facts by where
    /// their evidence sits in the manuscript without re-searching; callers
    /// that only need a yes/no can simply compare the result against `nil`.
    ///
    /// `normalizedManuscript` must already be the output of
    /// `normalizeEvidenceWhitespace` — this function never re-normalizes
    /// it, so a caller checking many evidence strings against one
    /// manuscript can normalize the manuscript once and reuse it across
    /// every call instead of paying that cost per evidence item. `evidence`
    /// itself is normalized here, since it is expected to be short relative
    /// to the manuscript.
    private static func evidenceAnchorRange(
        _ evidence: String,
        in normalizedManuscript: String
    ) -> Range<String.Index>? {
        let normalizedEvidence = normalizeEvidenceWhitespace(evidence)
        guard !normalizedEvidence.isEmpty else { return nil }
        if let literalRange = normalizedManuscript.range(of: normalizedEvidence) {
            return literalRange
        }
        return anchorRange(for: normalizedEvidence, in: normalizedManuscript)
    }

    private static func evidenceMatches(
        _ evidence: String,
        inNormalizedManuscript normalizedManuscript: String
    ) -> Bool {
        evidenceAnchorRange(evidence, in: normalizedManuscript) != nil
    }

    /// Paraphrase-tolerant fallback used by `evidenceAnchorRange` once the
    /// literal substring check has already failed: answers whether
    /// `evidence` (already normalized) shares a contiguous run of
    /// characters with `manuscript` (already normalized) that is both at
    /// least `minimumAnchorLength` characters long and covers at least
    /// `minimumAnchorRatio` of `evidence`'s own length. Both are lower
    /// bounds on the same quantity (the longest common contiguous run), so
    /// they collapse into a single required `threshold` — no need to
    /// separately track the exact longest-common-substring length.
    ///
    /// If `evidence` is shorter than `minimumAnchorLength`, `threshold`
    /// exceeds `evidence`'s own length, which makes this path unsatisfiable
    /// by construction: a short evidence string can only pass via the
    /// literal fast path in `evidenceAnchorRange`. This is intentional —
    /// short evidence is exactly the case where paraphrase tolerance is
    /// least needed and most exploitable.
    private static func anchorRange(
        for evidence: String,
        in manuscript: String
    ) -> Range<String.Index>? {
        let evidenceCharacters = Array(evidence)
        let manuscriptCharacters = Array(manuscript)
        let threshold = max(
            minimumAnchorLength,
            Int((Double(evidenceCharacters.count) * minimumAnchorRatio).rounded(.up))
        )
        return manuscriptWindowRange(
            ofLength: threshold,
            from: evidenceCharacters,
            in: manuscriptCharacters,
            manuscript: manuscript
        )
    }

    /// Finds the range, within `manuscript`, of a contiguous run of exactly
    /// `length` characters that also occurs somewhere in
    /// `evidenceCharacters` — or `nil` if no such run exists. This only
    /// needs to answer an existence question ("does an anchor of at least
    /// `length` characters exist, and if so where?"), not compute the exact
    /// longest-common-substring length — so it avoids an O(manuscriptCount
    /// * evidenceCount) dynamic-programming table (which would also cost
    /// O(manuscriptCount * evidenceCount) memory).
    ///
    /// Algorithm: hash every `length`-character window of the manuscript
    /// into a `[String: Int]` map from window text to its starting offset —
    /// O(manuscriptCount) windows, each costing O(length) to
    /// materialize/hash — then probe every `length`-character window of the
    /// evidence against that map — O(evidenceCount) windows, same
    /// per-window cost. Total time is O((manuscriptCount + evidenceCount) *
    /// length). Because `length` is always bounded by
    /// `evidenceCharacters.count` (never by the manuscript's length) and
    /// evidence is expected to be a short quoted excerpt while the
    /// manuscript can be a multi-thousand-character chapter chunk, this
    /// stays cheap even for long manuscripts — the cost scales with the
    /// short side, not the long one.
    private static func manuscriptWindowRange(
        ofLength length: Int,
        from evidenceCharacters: [Character],
        in manuscriptCharacters: [Character],
        manuscript: String
    ) -> Range<String.Index>? {
        guard length > 0,
              length <= evidenceCharacters.count,
              length <= manuscriptCharacters.count else {
            return nil
        }
        var manuscriptWindowOffsets: [String: Int] = [:]
        manuscriptWindowOffsets.reserveCapacity(manuscriptCharacters.count - length + 1)
        for start in 0...(manuscriptCharacters.count - length) {
            let end = start + length
            let window = String(manuscriptCharacters[start..<end])
            if manuscriptWindowOffsets[window] == nil {
                manuscriptWindowOffsets[window] = start
            }
        }
        for start in 0...(evidenceCharacters.count - length) {
            let end = start + length
            let window = String(evidenceCharacters[start..<end])
            guard let manuscriptStart = manuscriptWindowOffsets[window] else { continue }
            let lowerBound = manuscript.index(manuscript.startIndex, offsetBy: manuscriptStart)
            let upperBound = manuscript.index(lowerBound, offsetBy: length)
            return lowerBound..<upperBound
        }
        return nil
    }

    private static func sanitizedUnresolvedEntityNames(
        base: [String],
        referenced: [String],
        identityClarifications: [NovelCharacterIdentityClarificationRecord],
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
        let clarified = Set(identityClarifications.map {
            normalizedEntity($0.mention)
        })
        var unresolved: [String] = []
        var unresolvedKeys: Set<String> = []
        for name in base + referenced {
            let key = normalizedEntity(name)
            // Places/orgs may appear in event entityReferences; they must not
            // become character-identity clarification cards.
            guard !key.isEmpty,
                  NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate(name),
                  !known.contains(key),
                  !clarified.contains(key),
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
        identityClarifications: [NovelCharacterIdentityClarificationRecord],
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
        let clarified = Set(identityClarifications.map {
            normalizedEntity($0.mention)
        })
        let resolved = known.union(clarified)
        // Identity-card contract is person-only; toponyms never enter this set
        // after sanitization, and stale place names in base may drop freely.
        let unresolvedKeys = Set(
            unresolved
                .filter(NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate)
                .map(normalizedEntity)
        )
        let baseUnresolvedKeys = Set(
            baseUnresolved
                .filter(NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate)
                .map(normalizedEntity)
        )
        let referenced = eventReferences + characterNames + relationshipNames
        let personReferencedKeys = Set(
            referenced
                .filter(NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate)
                .map(normalizedEntity)
        )
        let newlyUnresolved = unresolvedKeys.subtracting(baseUnresolvedKeys)
        guard newlyUnresolved.isSubset(of: personReferencedKeys) else {
            throw NovelError.invalidInput(
                "A newly unresolved entity is not referenced by an evidence-backed fact."
            )
        }
        guard unresolvedKeys.isDisjoint(with: resolved) else {
            throw NovelError.invalidInput(
                "A known or author-clarified entity cannot be listed as unresolved."
            )
        }
        guard baseUnresolvedKeys.subtracting(resolved).isSubset(of: unresolvedKeys) else {
            throw NovelError.invalidInput(
                "An unresolved entity disappeared without a matching project material."
            )
        }
        for name in referenced {
            // Non-person mentions (places, institutions) may appear in event
            // references without entering the character-identity unresolved list.
            guard NovelCharacterIdentityResolver.isLikelyCharacterIdentityCandidate(name) else {
                continue
            }
            let key = normalizedEntity(name)
            guard resolved.contains(key) || unresolvedKeys.contains(key) else {
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
            guard let range = evidenceAnchorRange(draft.evidence, in: normalizedSource) else {
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
    private static func partitionEvidence<Fact>(
        _ facts: [Fact],
        inNormalizedManuscript source: String,
        evidence: (Fact) -> String
    ) -> (kept: [Fact], discarded: [String]) {
        var kept: [Fact] = []
        var discarded: [String] = []
        kept.reserveCapacity(facts.count)
        for fact in facts {
            let quote = evidence(fact)
            if evidenceMatches(quote, inNormalizedManuscript: source) {
                kept.append(fact)
            } else {
                discarded.append(quote)
            }
        }
        return (kept, discarded)
    }

    private static func requireEvidenceNotAllDiscarded(
        rawHasFacts: Bool,
        hasEvidenceBackedFacts: Bool,
        unmatchedEvidence: [String],
        acceptEmptyFacts: Bool = false
    ) throws {
        guard rawHasFacts && !hasEvidenceBackedFacts else { return }
        if acceptEmptyFacts { return }
        var message = "模型给出的证据文字与正文对不上，本次状态同步未写入任何内容，请重试。"
        let listed = unmatchedEvidence.prefix(6).filter { !$0.isEmpty }
        if !listed.isEmpty {
            message += "\nUNMATCHED EVIDENCE\n" + listed.map { "- \($0)" }.joined(separator: "\n")
        }
        throw NovelStructuredModelExecutionFailure(
            code: "state_facts_evidence_unmatched",
            message: message,
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
