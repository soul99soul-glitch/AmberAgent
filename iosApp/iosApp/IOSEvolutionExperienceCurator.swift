import Foundation

// MARK: - Phase 3 Wave 1: Experience curator (§11.3 / §15 Phase 3)
//
// The policy layer over `IOSEvolutionExperienceStore`. §11.3 hard requirement:
// the curator MUST support add, merge, update, supersede and delete — the
// pool is a curated, bounded playbook, not an append-only lesson pile (§20).
//
// Deterministic heuristics (v1, no embeddings):
// - dedupe on add: an incoming (applicability + rule text) whose normalized
//   token overlap with an ACTIVE entry reaches `dedupeSimilarityThreshold`
//   (with a minimum absolute overlap) is NOT stored as a new entry — it
//   merges into the ORIGINAL id (acceptance 1: 同一经验重复出现时更新原 ID，
//   不新增近义垃圾条目).
// - conflict detection: two ACTIVE entries with overlapping applicability
//   tokens whose rule texts contain an opposing keyword pair (hardcoded
//   table below) get a symmetric conflict edge. Known heuristic boundary
//   (documented at the table): opposition expressed without one of these
//   exact token pairs is not detected.
// - retrieval: deterministic relevance score = fraction of the entry's tokens
//   present in the task context; only ACTIVE entries with score > 0; the two
//   sides of a conflicting pair are never both returned — the lower-scored
//   side is suppressed and the winner carries the suppressed id as a marker
//   for the caller to display (acceptance 2); output is bounded by topK AND
//   byteBudget (acceptance 4 — 经验数量增长不会线性撑爆每轮上下文; budget
//   semantics consistent with §8 invariant 15).
// - harmful counters: crossing `harmfulSupersedeThreshold` /
//   `harmfulDeleteThreshold` produces an APPROVAL-GATED suggestion object;
//   the entry stays `active` until approved (acceptance 3 — this wave only
//   produces the suggestion; the approval UI is a later wave).

// MARK: - Outcomes (typed results, fail-closed like the diagnoser)

struct IOSExperienceMergeReport: Equatable, Sendable {
    /// The surviving (original) entry id.
    let experienceId: String
    /// True when the merge actually changed the stored entry.
    let changedContent: Bool
    let updatedAtEpochMs: Int64
}

enum IOSExperienceAddOutcome: Equatable, Sendable {
    /// Genuinely new entry stored; `matchedTombstone` is set when the exact
    /// content was deleted before (防复读 — surfaced for the approval flow).
    case added(IOSEvolutionExperience, matchedTombstone: IOSExperienceTombstone?)
    /// Near-duplicate of an active entry — folded into the ORIGINAL id.
    case merged(IOSExperienceMergeReport)
    case rejected(IOSExperienceError)
}

enum IOSExperienceMergeOutcome: Equatable, Sendable {
    case merged(target: IOSEvolutionExperience, supersededSource: IOSEvolutionExperience)
    case rejected(IOSExperienceError)
}

enum IOSExperienceUpdateOutcome: Equatable, Sendable {
    case updated(IOSEvolutionExperience)
    case rejected(IOSExperienceError)
}

enum IOSExperienceSupersedeOutcome: Equatable, Sendable {
    case superseded(IOSEvolutionExperience)
    case rejected(IOSExperienceError)
}

enum IOSExperienceDeleteOutcome: Equatable, Sendable {
    case deleted(IOSExperienceTombstone)
    case rejected(IOSExperienceError)
}

enum IOSExperienceFeedbackOutcome: Equatable, Sendable {
    case recorded(experience: IOSEvolutionExperience, suggestion: IOSExperienceActionSuggestion?)
    case rejected(IOSExperienceError)
}

enum IOSExperienceRetrievalOutcome: Equatable, Sendable {
    case items([IOSExperienceRetrievalItem])
    case failed(IOSExperienceError)
}

// MARK: - Curator

struct IOSEvolutionExperienceCurator {
    // MARK: Deterministic heuristic constants (v1)

    /// Dedupe similarity threshold (Jaccard on normalized token sets of
    /// applicability + rule text). Chosen so wording-level variants of the
    /// same experience merge, while genuinely different rules (different
    /// bigram neighborhoods) stay separate. Deterministic; re-tune when real
    /// pool data exists.
    static let dedupeSimilarityThreshold = 0.75
    /// Absolute minimum shared tokens for a dedupe merge — two very short
    /// texts that only share a couple of words must not collapse.
    static let minimumDedupeOverlapTokens = 4
    /// Conflict detection: applicability token overlap must reach this
    /// (Jaccard) before rule-text opposition is consulted.
    static let conflictApplicabilityOverlapThreshold = 0.5
    /// §15 Phase 3 acceptance 3: harmful >= 3 → supersede suggestion.
    static let harmfulSupersedeThreshold = 3
    /// harmful >= 5 → delete suggestion (stronger signal, still approval-gated).
    static let harmfulDeleteThreshold = 5

    /// v1 opposing keyword pairs. The comment acknowledges the heuristic
    /// boundary: semantic opposition expressed without one of these exact
    /// token pairs (e.g. reversed ordering rules, antonyms outside the
    /// table) is NOT detected and would need a model-assisted pass in a
    /// later phase.
    static let opposingKeywordPairs: [(String, String)] = [
        ("总是", "从不"),
        ("always", "never"),
        ("必须", "禁止"),
        ("必须", "不要"),
        ("must", "never"),
        ("允许", "禁止"),
        ("allow", "forbid"),
        ("allow", "prohibit"),
        ("可以", "不可以"),
        ("应该", "不应该"),
    ]

    let store: IOSEvolutionExperienceStore

    init(store: IOSEvolutionExperienceStore) {
        self.store = store
    }

    // MARK: add — dedupe first, then store with hard caps

    /// §11.3 add. Never creates a near-synonymous junk entry (acceptance 1):
    /// when the incoming content is similar enough to an ACTIVE entry, the
    /// ORIGINAL entry is merged/updated under its original id. When the
    /// store rejects (hard caps, §18.3), the typed rejection is surfaced —
    /// the curator does not evict or truncate; the caller decides
    /// merge/supersede.
    func add(
        applicability: String,
        counterexamples: [String],
        evidenceRefs: [IOSEvidenceRef],
        ruleText: String,
        sourceArtifactId: String? = nil,
        sourceArtifactVersion: String? = nil,
        helpfulCount: Int = 0,
        harmfulCount: Int = 0
    ) -> IOSExperienceAddOutcome {
        let trimmedApplicability = applicability.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedRuleText = ruleText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedApplicability.isEmpty else { return .rejected(.emptyApplicability) }
        guard !trimmedRuleText.isEmpty else { return .rejected(.emptyRuleText) }
        do {
            let incomingTokens = IOSEvolutionTokenization.tokenSet(
                trimmedApplicability + "\n" + trimmedRuleText
            )
            var bestMatch: (id: String, similarity: Double)?
            for candidate in try store.activeExperiences() {
                guard candidate.sourceArtifactId == sourceArtifactId,
                      candidate.sourceArtifactVersion == sourceArtifactVersion else {
                    continue
                }
                let candidateTokens = IOSEvolutionTokenization.tokenSet(
                    candidate.applicability + "\n" + candidate.ruleText
                )
                let similarity = IOSEvolutionTokenization.jaccard(incomingTokens, candidateTokens)
                guard similarity >= Self.dedupeSimilarityThreshold,
                      incomingTokens.intersection(candidateTokens).count >= Self.minimumDedupeOverlapTokens
                else { continue }
                if bestMatch == nil
                    || similarity > bestMatch!.similarity
                    || (similarity == bestMatch!.similarity && candidate.id < bestMatch!.id) {
                    bestMatch = (candidate.id, similarity)
                }
            }
            if let bestMatch {
                return try mergeInto(
                    experienceId: bestMatch.id,
                    applicability: trimmedApplicability,
                    counterexamples: counterexamples,
                    evidenceRefs: evidenceRefs,
                    ruleText: trimmedRuleText,
                    helpfulCount: helpfulCount,
                    harmfulCount: harmfulCount
                )
            }

            let now = Self.nowMillis()
            let entry = IOSEvolutionExperience(
                id: "exp-\(UUID().uuidString)",
                applicability: trimmedApplicability,
                counterexamples: Self.cleanedStrings(counterexamples),
                evidenceRefs: evidenceRefs,
                sourceArtifactId: sourceArtifactId,
                sourceArtifactVersion: sourceArtifactVersion,
                helpfulCount: max(0, helpfulCount),
                harmfulCount: max(0, harmfulCount),
                status: .active,
                supersededByExperienceId: nil,
                conflicts: [],
                ruleText: trimmedRuleText,
                createdAtEpochMs: now,
                updatedAtEpochMs: now
            )
            switch store.add(entry) {
            case .rejected(let error):
                return .rejected(error)
            case .added(let stored, let matchedTombstone):
                // Conflict edges are derived state; a resync failure leaves
                // the entry stored with possibly stale edges, which retrieval
                // ignores defensively (only ACTIVE endpoints count).
                resyncConflicts(experienceId: stored.id)
                let reloaded = (try? store.experience(id: stored.id)) ?? stored
                return .added(reloaded, matchedTombstone: matchedTombstone)
            }
        } catch {
            return .rejected(Self.foldedError(error))
        }
    }

    // MARK: merge

    /// §11.3 merge: folds `sourceExperienceId` into `targetExperienceId`.
    /// The target keeps its own rule text / applicability (its identity) and
    /// absorbs the source's counterexamples, evidence refs and counters; the
    /// source becomes `superseded` pointing at the target, so the
    /// consolidation stays auditable. Both entries must be active.
    func merge(sourceExperienceId: String, into targetExperienceId: String) -> IOSExperienceMergeOutcome {
        guard sourceExperienceId != targetExperienceId else { return .rejected(.cannotMergeIntoSelf) }
        do {
            guard let source = try store.experience(id: sourceExperienceId) else {
                return .rejected(.experienceNotFound(sourceExperienceId))
            }
            guard let target = try store.experience(id: targetExperienceId) else {
                return .rejected(.experienceNotFound(targetExperienceId))
            }
            guard source.status == .active else { return .rejected(.mergeSourceNotActive(sourceExperienceId)) }
            guard target.status == .active else { return .rejected(.mergeTargetNotActive(targetExperienceId)) }

            var mergedTarget = target
            for counterexample in source.counterexamples
            where !mergedTarget.counterexamples.contains(counterexample) {
                mergedTarget.counterexamples.append(counterexample)
            }
            for ref in source.evidenceRefs where !mergedTarget.evidenceRefs.contains(ref) {
                mergedTarget.evidenceRefs.append(ref)
            }
            mergedTarget.helpfulCount += source.helpfulCount
            mergedTarget.harmfulCount += source.harmfulCount
            mergedTarget.updatedAtEpochMs = Self.nowMillis()

            var supersededSource = source
            supersededSource.status = .superseded
            supersededSource.supersededByExperienceId = target.id
            supersededSource.updatedAtEpochMs = Self.nowMillis()

            switch store.update(mergedTarget) {
            case .rejected(let error):
                return .rejected(error)
            case .updated(let storedTarget):
                switch store.update(supersededSource) {
                case .rejected(let error):
                    return .rejected(error)
                case .updated(let storedSource):
                    // Hygiene only: retrieval already ignores edges whose
                    // other endpoint is not active.
                    stripEdgesReferencing(storedSource.id)
                    resyncConflicts(experienceId: storedTarget.id)
                    let reloadedTarget = (try? store.experience(id: storedTarget.id)) ?? storedTarget
                    let reloadedSource = (try? store.experience(id: storedSource.id)) ?? storedSource
                    return .merged(target: reloadedTarget, supersededSource: reloadedSource)
                }
            }
        } catch {
            return .rejected(Self.foldedError(error))
        }
    }

    // MARK: update

    /// §11.3 update: patches an existing entry in place (nil = keep).
    /// Content changes re-run conflict detection for the entry (stale edges
    /// dropped, new ones recorded symmetrically). `status` transitions are
    /// allowed (e.g. a future approval flow marking an entry `rejected`);
    /// `.superseded` is NOT settable here — it is produced only by the
    /// supersede operation, keeping the state machine honest.
    func update(
        experienceId: String,
        applicability: String? = nil,
        counterexamples: [String]? = nil,
        evidenceRefs: [IOSEvidenceRef]? = nil,
        ruleText: String? = nil,
        status: IOSExperienceStatus? = nil
    ) -> IOSExperienceUpdateOutcome {
        do {
            guard var entry = try store.experience(id: experienceId) else {
                return .rejected(.experienceNotFound(experienceId))
            }
            if let applicability {
                let trimmed = applicability.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { return .rejected(.emptyApplicability) }
                entry.applicability = trimmed
            }
            if let counterexamples {
                entry.counterexamples = Self.cleanedStrings(counterexamples)
            }
            if let evidenceRefs {
                entry.evidenceRefs = evidenceRefs
            }
            if let ruleText {
                let trimmed = ruleText.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { return .rejected(.emptyRuleText) }
                entry.ruleText = trimmed
            }
            if let status {
                guard status != .superseded else {
                    return .rejected(.invalidStatusTransition("superseded 只能通过 supersede 操作产生"))
                }
                entry.status = status
                entry.supersededByExperienceId = nil
            }
            entry.updatedAtEpochMs = Self.nowMillis()
            switch store.update(entry) {
            case .rejected(let error):
                return .rejected(error)
            case .updated(let stored):
                guard stored.status == .active else {
                    // 转为 rejected 等非 active 态后，其它条目中指向它的冲突边
                    // 一并清理（与 supersede/delete 路径一致），否则 UI 冲突徽章
                    // 会对着一个已不可注入的条目误亮。
                    stripEdgesReferencing(stored.id)
                    let reloaded = (try? store.experience(id: stored.id)) ?? stored
                    return .updated(reloaded)
                }
                resyncConflicts(experienceId: stored.id)
                let reloaded = (try? store.experience(id: stored.id)) ?? stored
                return .updated(reloaded)
            }
        } catch {
            return .rejected(Self.foldedError(error))
        }
    }

    // MARK: supersede

    /// §11.3 supersede: marks `experienceId` as superseded, pointing at
    /// `newExperienceId` (§15 Phase 3: 旧条目标 superseded + 指向新条目 ID).
    /// The new entry must exist and be active. Edges referencing the retired
    /// entry are stripped (hygiene; retrieval ignores inactive endpoints
    /// defensively).
    func supersede(experienceId: String, newExperienceId: String) -> IOSExperienceSupersedeOutcome {
        guard experienceId != newExperienceId else { return .rejected(.cannotSupersedeSelf) }
        do {
            guard let newEntry = try store.experience(id: newExperienceId) else {
                return .rejected(.experienceNotFound(newExperienceId))
            }
            guard newEntry.status == .active else {
                return .rejected(.supersedeTargetNotActive(newExperienceId))
            }
            guard var old = try store.experience(id: experienceId) else {
                return .rejected(.experienceNotFound(experienceId))
            }
            guard old.status == .active else {
                return .rejected(.invalidStatusTransition("只能 supersede active 条目"))
            }
            old.status = .superseded
            old.supersededByExperienceId = newExperienceId
            old.updatedAtEpochMs = Self.nowMillis()
            switch store.update(old) {
            case .rejected(let error):
                return .rejected(error)
            case .updated(let stored):
                stripEdgesReferencing(stored.id)
                return .superseded(stored)
            }
        } catch {
            return .rejected(Self.foldedError(error))
        }
    }

    // MARK: delete

    /// §11.3 delete: physical removal; the store keeps a tombstone summary
    /// (防复读) and strips dangling conflict edges.
    func delete(experienceId: String, reason: String? = nil) -> IOSExperienceDeleteOutcome {
        switch store.delete(id: experienceId, reason: reason) {
        case .deleted(let tombstone):
            return .deleted(tombstone)
        case .rejected(let error):
            return .rejected(error)
        }
    }

    // MARK: helpful / harmful feedback

    /// Records a helpful signal (monotonic). The entry must be active.
    func recordHelpful(
        experienceId: String,
        evidenceRefs: [IOSEvidenceRef] = []
    ) -> IOSExperienceFeedbackOutcome {
        feedback(experienceId: experienceId, harmful: false, evidenceRefs: evidenceRefs)
    }

    /// Records a harmful signal; crossing `harmfulSupersedeThreshold` (3) or
    /// `harmfulDeleteThreshold` (5) produces an APPROVAL-GATED suggestion.
    /// The entry STAYS active until the caller approves (§15 Phase 3
    /// acceptance 3 — this wave only produces the suggestion object; the
    /// approval UI is a later wave).
    func recordHarmful(
        experienceId: String,
        evidenceRefs: [IOSEvidenceRef] = []
    ) -> IOSExperienceFeedbackOutcome {
        feedback(experienceId: experienceId, harmful: true, evidenceRefs: evidenceRefs)
    }

    // MARK: retrieve

    /// Current approval-gated suggestions, deterministically projected from
    /// the ACTIVE pool's counters (§15 Phase 3 acceptance 3): harmful ≥
    /// `harmfulDeleteThreshold` → `.delete`; harmful ≥
    /// `harmfulSupersedeThreshold` → `.supersede`. Suggestions are NOT
    /// persisted — `recordHarmful` produces one at the crossing count, and
    /// this projection lets the management UI re-derive the current set at
    /// any time (approval/rejection lives in the UI layer; applying is done
    /// through the curator's own supersede/delete/update ops). Deterministic
    /// order: experience id, then kind.
    func currentSuggestions(now: Int64 = Self.nowMillis()) -> [IOSExperienceActionSuggestion] {
        guard let active = try? store.activeExperiences() else { return [] }
        return active.compactMap { entry in
            if entry.harmfulCount >= Self.harmfulDeleteThreshold {
                return makeSuggestion(experience: entry, kind: .delete, createdAtEpochMs: now)
            }
            if entry.harmfulCount >= Self.harmfulSupersedeThreshold {
                return makeSuggestion(experience: entry, kind: .supersede, createdAtEpochMs: now)
            }
            return nil
        }.sorted {
            $0.experienceId != $1.experienceId
                ? $0.experienceId < $1.experienceId
                : $0.kind.rawValue < $1.kind.rawValue
        }
    }

    /// §15 Phase 3 retrieval: deterministic relevance scoring, ACTIVE entries
    /// only with score > 0, at most one side of each conflicting pair (the
    /// winner carries the suppressed opponent as a marker — acceptance 2),
    /// and output bytes ≤ byteBudget (acceptance 4: 经验数量增长不会线性撑爆
    /// 每轮上下文; budget semantics consistent with §8 invariant 15). A
    /// corrupt/unreadable store is a typed failure, not an empty injection.
    func retrieve(taskContext: String, topK: Int, byteBudget: Int) -> IOSExperienceRetrievalOutcome {
        guard topK > 0, byteBudget > 0 else { return .items([]) }
        do {
            let contextTokens = IOSEvolutionTokenization.tokenSet(taskContext)
            guard !contextTokens.isEmpty else { return .items([]) }
            let active = try store.activeExperiences()

            // Deterministic relevance: fraction of the entry's tokens present
            // in the task context; ties broken by id.
            var ranked: [(experience: IOSEvolutionExperience, score: Double)] = []
            for entry in active {
                let entryTokens = IOSEvolutionTokenization.tokenSet(
                    entry.applicability + "\n" + entry.ruleText
                )
                guard !entryTokens.isEmpty else { continue }
                let overlap = contextTokens.intersection(entryTokens).count
                guard overlap > 0 else { continue }
                ranked.append((entry, Double(overlap) / Double(entryTokens.count)))
            }
            ranked.sort {
                $0.score != $1.score ? $0.score > $1.score : $0.experience.id < $1.experience.id
            }

            // Conflict suppression: never return both sides of an active
            // conflict. The higher-scored side wins; the loser is dropped
            // from the pool and recorded as the winner's marker.
            let activeIds = Set(active.map(\.id))
            var pairs: [(String, String)] = []
            for entry in active.sorted(by: { $0.id < $1.id }) {
                for edge in entry.conflicts.sorted(by: { $0.otherExperienceId < $1.otherExperienceId })
                where entry.id < edge.otherExperienceId && activeIds.contains(edge.otherExperienceId) {
                    pairs.append((entry.id, edge.otherExperienceId))
                }
            }
            var pool = ranked
            var suppressedBy: [String: Set<String>] = [:]
            for pair in pairs {
                guard let aIndex = pool.firstIndex(where: { $0.experience.id == pair.0 }),
                      let bIndex = pool.firstIndex(where: { $0.experience.id == pair.1 }) else { continue }
                let winnerIndex = min(aIndex, bIndex)
                let loserIndex = max(aIndex, bIndex)
                let winnerId = pool[winnerIndex].experience.id
                let loserId = pool[loserIndex].experience.id
                pool.remove(at: loserIndex)
                suppressedBy[winnerId, default: []].insert(loserId)
            }

            // topK AND byteBudget double caps. Entries that would overflow
            // the remaining budget are skipped (greedy fit), so a big entry
            // cannot starve smaller relevant ones — and the output stays
            // strictly ≤ byteBudget.
            var totalBytes = 0
            var items: [IOSExperienceRetrievalItem] = []
            for candidate in pool {
                if items.count >= topK { break }
                let suppressed = suppressedBy[candidate.experience.id, default: []].sorted()
                let bytes = IOSExperienceByteAccounting.encodedByteCount(
                    experience: candidate.experience,
                    suppressedConflictingExperienceIds: suppressed
                )
                if totalBytes + bytes > byteBudget { continue }
                totalBytes += bytes
                items.append(IOSExperienceRetrievalItem(
                    experience: candidate.experience,
                    suppressedConflictingExperienceIds: suppressed
                ))
            }
            return .items(items)
        } catch {
            return .failed(Self.foldedError(error))
        }
    }

    // MARK: Private — dedupe merge

    private func mergeInto(
        experienceId: String,
        applicability: String,
        counterexamples: [String],
        evidenceRefs: [IOSEvidenceRef],
        ruleText: String,
        helpfulCount: Int,
        harmfulCount: Int
    ) throws -> IOSExperienceAddOutcome {
        guard var target = try store.experience(id: experienceId) else {
            return .rejected(.experienceNotFound(experienceId))
        }
        let (merged, changed) = Self.mergedContent(
            target: target,
            applicability: applicability,
            counterexamples: counterexamples,
            evidenceRefs: evidenceRefs,
            ruleText: ruleText,
            helpfulCount: helpfulCount,
            harmfulCount: harmfulCount
        )
        target = merged
        target.updatedAtEpochMs = Self.nowMillis()
        switch store.update(target) {
        case .rejected(let error):
            return .rejected(error)
        case .updated(let stored):
            resyncConflicts(experienceId: stored.id)
            let reloaded = (try? store.experience(id: stored.id)) ?? stored
            return .merged(IOSExperienceMergeReport(
                experienceId: reloaded.id,
                changedContent: changed,
                updatedAtEpochMs: reloaded.updatedAtEpochMs
            ))
        }
    }

    /// Deterministic content merge for dedupe: rule text / applicability keep
    /// the LONGER (more token-specific) side, ties keep the existing side;
    /// counterexamples and evidence refs are unioned (order-preserving,
    /// deduped); helpful/harmful counts are summed.
    private static func mergedContent(
        target: IOSEvolutionExperience,
        applicability: String,
        counterexamples: [String],
        evidenceRefs: [IOSEvidenceRef],
        ruleText: String,
        helpfulCount: Int,
        harmfulCount: Int
    ) -> (experience: IOSEvolutionExperience, changed: Bool) {
        var merged = target
        var changed = false
        if IOSEvolutionTokenization.tokenSet(ruleText).count > IOSEvolutionTokenization.tokenSet(target.ruleText).count {
            merged.ruleText = ruleText
            changed = true
        }
        if IOSEvolutionTokenization.tokenSet(applicability).count
            > IOSEvolutionTokenization.tokenSet(target.applicability).count {
            merged.applicability = applicability
            changed = true
        }
        for counterexample in counterexamples where !merged.counterexamples.contains(counterexample) {
            merged.counterexamples.append(counterexample)
            changed = true
        }
        for ref in evidenceRefs where !merged.evidenceRefs.contains(ref) {
            merged.evidenceRefs.append(ref)
            changed = true
        }
        if helpfulCount > 0 {
            merged.helpfulCount += helpfulCount
            changed = true
        }
        if harmfulCount > 0 {
            merged.harmfulCount += harmfulCount
            changed = true
        }
        return (merged, changed)
    }

    // MARK: Private — conflict detection / resync

    /// Recomputes ALL conflict edges involving `experienceId` against the
    /// other ACTIVE entries: edges that no longer hold are dropped on both
    /// sides, new ones are recorded symmetrically. Deterministic. Failures
    /// are logged — a failed resync leaves possibly stale edges, which
    /// retrieval ignores defensively (only edges between two ACTIVE entries
    /// participate in suppression).
    private func resyncConflicts(experienceId: String) {
        do {
            guard let entry = try store.experience(id: experienceId) else { return }
            let others = try store.activeExperiences().filter { $0.id != experienceId }
            var desired: [String: (edge: IOSExperienceConflict, symmetric: IOSExperienceConflict)] = [:]
            for other in others {
                guard let detected = detectConflict(entry, other) else { continue }
                desired[other.id] = detected
            }

            var updatedEntry = entry
            updatedEntry.conflicts = desired.keys.sorted().map { desired[$0]!.edge }
            if updatedEntry.conflicts != entry.conflicts {
                if case .rejected(let error) = store.update(updatedEntry) {
                    print("[AmberChat] experience conflict resync failed entry=\(experienceId): \(error)")
                }
            }
            for other in others {
                let symmetric = desired[other.id]?.symmetric
                var updatedOther = other
                if let symmetric,
                   !other.conflicts.contains(where: { $0.otherExperienceId == experienceId }) {
                    updatedOther.conflicts.append(symmetric)
                    updatedOther.conflicts.sort { $0.otherExperienceId < $1.otherExperienceId }
                    if case .rejected(let error) = store.update(updatedOther) {
                        print("[AmberChat] experience conflict resync failed entry=\(other.id): \(error)")
                    }
                } else if symmetric == nil,
                          other.conflicts.contains(where: { $0.otherExperienceId == experienceId }) {
                    updatedOther.conflicts.removeAll { $0.otherExperienceId == experienceId }
                    if case .rejected(let error) = store.update(updatedOther) {
                        print("[AmberChat] experience conflict resync failed entry=\(other.id): \(error)")
                    }
                }
            }
        } catch {
            print("[AmberChat] experience conflict resync failed entry=\(experienceId): \(error)")
        }
    }

    /// Deterministic v1 conflict heuristic: overlapping applicability tokens
    /// AND an opposing keyword pair split between the two rule texts.
    /// Returns the symmetric edges when they conflict. Known boundary:
    /// only the hardcoded pair table is recognized (§15 Phase 3 — 注释承认
    /// 启发式边界).
    private func detectConflict(
        _ a: IOSEvolutionExperience,
        _ b: IOSEvolutionExperience
    ) -> (edge: IOSExperienceConflict, symmetric: IOSExperienceConflict)? {
        let applicabilityOverlap = IOSEvolutionTokenization.jaccard(
            IOSEvolutionTokenization.tokenSet(a.applicability),
            IOSEvolutionTokenization.tokenSet(b.applicability)
        )
        guard applicabilityOverlap >= Self.conflictApplicabilityOverlapThreshold else { return nil }
        let aTokens = IOSEvolutionTokenization.tokenSet(a.ruleText)
        let bTokens = IOSEvolutionTokenization.tokenSet(b.ruleText)
        for (left, right) in Self.opposingKeywordPairs {
            guard (aTokens.contains(left) && bTokens.contains(right))
                || (aTokens.contains(right) && bTokens.contains(left)) else { continue }
            let reason = "适用条件重叠（\(String(format: "%.2f", applicabilityOverlap))）且规则文本包含对立关键词「\(left)」/「\(right)」"
            let pair = "\(left)↔\(right)"
            return (
                IOSExperienceConflict(otherExperienceId: b.id, reason: reason, detectedKeywordPair: pair),
                IOSExperienceConflict(otherExperienceId: a.id, reason: reason, detectedKeywordPair: pair)
            )
        }
        return nil
    }

    /// Hygiene: removes conflict edges referencing a retired id from the
    /// remaining entries. Retrieval already ignores edges whose other
    /// endpoint is not active, so a missed strip is harmless — best-effort.
    private func stripEdgesReferencing(_ experienceId: String) {
        guard let all = try? store.allExperiences() else { return }
        for entry in all
        where entry.id != experienceId
            && entry.conflicts.contains(where: { $0.otherExperienceId == experienceId }) {
            var updated = entry
            updated.conflicts.removeAll { $0.otherExperienceId == experienceId }
            if case .rejected(let error) = store.update(updated) {
                print("[AmberChat] experience edge strip failed entry=\(entry.id): \(error)")
            }
        }
    }

    // MARK: Private — feedback / helpers

    private func feedback(
        experienceId: String,
        harmful: Bool,
        evidenceRefs: [IOSEvidenceRef]
    ) -> IOSExperienceFeedbackOutcome {
        do {
            guard var entry = try store.experience(id: experienceId) else {
                return .rejected(.experienceNotFound(experienceId))
            }
            guard entry.status == .active else {
                return .rejected(.feedbackOnInactiveExperience(experienceId))
            }
            let previous = harmful ? entry.harmfulCount : entry.helpfulCount
            if harmful { entry.harmfulCount += 1 } else { entry.helpfulCount += 1 }
            for ref in evidenceRefs where !entry.evidenceRefs.contains(ref) {
                entry.evidenceRefs.append(ref)
            }
            entry.updatedAtEpochMs = Self.nowMillis()

            var suggestion: IOSExperienceActionSuggestion?
            if harmful {
                if entry.harmfulCount == Self.harmfulSupersedeThreshold
                    && previous < Self.harmfulSupersedeThreshold {
                    suggestion = makeSuggestion(experience: entry, kind: .supersede)
                } else if entry.harmfulCount == Self.harmfulDeleteThreshold
                            && previous < Self.harmfulDeleteThreshold {
                    suggestion = makeSuggestion(experience: entry, kind: .delete)
                }
            }
            switch store.update(entry) {
            case .rejected(let error):
                return .rejected(error)
            case .updated(let stored):
                return .recorded(experience: stored, suggestion: suggestion)
            }
        } catch {
            return .rejected(Self.foldedError(error))
        }
    }

    private func makeSuggestion(
        experience: IOSEvolutionExperience,
        kind: IOSExperienceActionSuggestionKind,
        createdAtEpochMs: Int64 = Self.nowMillis()
    ) -> IOSExperienceActionSuggestion {
        let reason: String
        switch kind {
        case .supersede:
            reason = "harmful 计数达到 \(experience.harmfulCount)（supersede 阈值 \(Self.harmfulSupersedeThreshold)）：建议降级该经验，需批准后生效。"
        case .delete:
            reason = "harmful 计数达到 \(experience.harmfulCount)（delete 阈值 \(Self.harmfulDeleteThreshold)）：建议删除该经验，需批准后生效。"
        }
        return IOSExperienceActionSuggestion(
            id: "sug-\(UUID().uuidString)",
            experienceId: experience.id,
            kind: kind,
            helpfulCount: experience.helpfulCount,
            harmfulCount: experience.harmfulCount,
            reason: reason,
            createdAtEpochMs: createdAtEpochMs
        )
    }

    private static func cleanedStrings(_ values: [String]) -> [String] {
        values.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private static func foldedError(_ error: Error) -> IOSExperienceError {
        if let experienceError = error as? IOSExperienceError { return experienceError }
        return .ioFailure(String(describing: error))
    }
}
