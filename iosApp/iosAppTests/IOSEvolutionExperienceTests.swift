import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Phase 3 Wave 1 contract tests for the Experience/Playbook core (§11.3 /
/// §15 Phase 3 / §18.3 / §20): the real `IOSEvolutionExperienceStore` in
/// temp directories (directory created FIRST, per the wave contract) and the
/// real `IOSEvolutionExperienceCurator`. No source-string anchors.
///
/// Acceptance covered:
///   1. the same experience re-appearing updates the ORIGINAL id and never
///      creates near-synonymous junk entries (dedupe merge);
///   2. conflicting active rules are never both injected without notice —
///      retrieval suppresses the lower-scored side and returns a marker;
///   3. harmful counts reaching the threshold produce supersede/delete
///      SUGGESTIONS only — the entry stays active (approval required);
///   4. retrieval output is bounded by BOTH topK and byteBudget — with 100
///      entries the injected bytes stay strictly bounded (no linear blowup);
///   plus the supersede/delete/update state machine, merge, tombstones and
///   typed store-limit rejections.
final class IOSEvolutionExperienceTests: XCTestCase {
    private var tempRoots: [URL] = []

    override func tearDown() {
        for root in tempRoots {
            try? FileManager.default.removeItem(at: root)
        }
        tempRoots.removeAll()
    }

    // MARK: - Acceptance 1: dedupe merges the ORIGINAL id, no junk entries

    func testDuplicateExperienceMergesOriginalIdWithoutJunkEntries() throws {
        let store = IOSEvolutionExperienceStore(baseDirectory: try makeTempRoot())
        let curator = IOSEvolutionExperienceCurator(store: store)
        let applicability = "RSS 简报整理任务"

        let first = try added(curator.add(
            applicability: applicability,
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "总是先搜索再写文件"
        ))
        let originalID = first.id

        // Exact repeat → merged into the ORIGINAL id, no new entry.
        let repeatOutcome = curator.add(
            applicability: applicability,
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "总是先搜索再写文件"
        )
        guard case .merged(let repeatReport) = repeatOutcome else {
            return XCTFail("exact repeat must merge, got \(repeatOutcome)")
        }
        XCTAssertEqual(repeatReport.experienceId, originalID)

        // Wording-level variant (comma split) → still merged, still one entry.
        let variantOutcome = curator.add(
            applicability: applicability,
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "总是先搜索，再写文件"
        )
        guard case .merged = variantOutcome else {
            return XCTFail("near-synonymous variant must merge, got \(variantOutcome)")
        }
        XCTAssertEqual(try store.allExperiences().count, 1, "近义垃圾条目不得新增")
        XCTAssertEqual(try store.experience(id: originalID)?.id, originalID)

        // A genuinely different rule → new entry, pool grows to 2.
        let differentOutcome = curator.add(
            applicability: "翻译任务",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "先确认目标语言再翻译"
        )
        guard case .added = differentOutcome else {
            return XCTFail("different rule must be added, got \(differentOutcome)")
        }
        XCTAssertEqual(try store.allExperiences().count, 2)
    }

    func testDedupeMergeFoldsAuxiliaryFieldsIntoTarget() throws {
        let store = IOSEvolutionExperienceStore(baseDirectory: try makeTempRoot())
        let curator = IOSEvolutionExperienceCurator(store: store)

        let first = try added(curator.add(
            applicability: "RSS 简报整理任务",
            counterexamples: ["凌晨的源不处理"],
            evidenceRefs: [IOSEvidenceRef(kind: .ledgerEvent, id: "ev-1")],
            ruleText: "总是先搜索再写文件",
            helpfulCount: 2
        ))
        let outcome = curator.add(
            applicability: "RSS 简报整理任务",
            counterexamples: ["凌晨的源不处理", "已断更的源不处理"],
            evidenceRefs: [IOSEvidenceRef(kind: .ledgerEvent, id: "ev-2")],
            ruleText: "总是先搜索，再写文件",
            helpfulCount: 1
        )
        guard case .merged(let report) = outcome else {
            return XCTFail("re-appearing experience must merge, got \(outcome)")
        }
        XCTAssertEqual(report.experienceId, first.id)
        let merged = try XCTUnwrap(store.experience(id: first.id))
        XCTAssertEqual(merged.helpfulCount, 3, "counts must be summed")
        XCTAssertTrue(merged.evidenceRefs.contains(IOSEvidenceRef(kind: .ledgerEvent, id: "ev-1")))
        XCTAssertTrue(merged.evidenceRefs.contains(IOSEvidenceRef(kind: .ledgerEvent, id: "ev-2")))
        XCTAssertTrue(merged.counterexamples.contains("已断更的源不处理"))
        XCTAssertEqual(try store.allExperiences().count, 1)
    }

    // MARK: - Acceptance 2: conflicting rules never injected silently

    func testConflictingRulesNeverInjectedSilentlyAndCarryMarkers() throws {
        let store = IOSEvolutionExperienceStore(baseDirectory: try makeTempRoot())
        let curator = IOSEvolutionExperienceCurator(store: store)

        let always = try added(curator.add(
            applicability: "写文件决策",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "写文件前总是先搜索"
        ))
        let never = try added(curator.add(
            applicability: "写文件决策",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "写文件前从不搜索"
        ))

        // Symmetric conflict edges recorded at add time (v1 heuristic:
        // applicability overlap + opposing keyword pair).
        let storedAlways = try XCTUnwrap(store.experience(id: always.id))
        let storedNever = try XCTUnwrap(store.experience(id: never.id))
        let alwaysEdge = try XCTUnwrap(storedAlways.conflicts.first { $0.otherExperienceId == never.id })
        XCTAssertTrue(alwaysEdge.detectedKeywordPair.contains("总是"))
        XCTAssertTrue(alwaysEdge.detectedKeywordPair.contains("从不"))
        XCTAssertTrue(storedNever.conflicts.contains { $0.otherExperienceId == always.id },
                      "conflict edges must be recorded symmetrically")

        // Retrieval: NEVER both sides; the winner carries the marker.
        let outcome = curator.retrieve(
            taskContext: "写文件之前要不要先搜索一下", topK: 10, byteBudget: 100_000
        )
        guard case .items(let items) = outcome else {
            return XCTFail("expected items, got \(outcome)")
        }
        let returnedIDs = Set(items.map(\.experience.id))
        XCTAssertFalse(
            returnedIDs.contains(always.id) && returnedIDs.contains(never.id),
            "冲突双方不得同时无提示返回"
        )
        let winner = try XCTUnwrap(items.first)
        XCTAssertEqual(winner.experience.id, always.id, "higher-scored side wins deterministically")
        XCTAssertEqual(
            winner.suppressedConflictingExperienceIds, [never.id],
            "winner must carry the suppressed opponent as a marker"
        )
    }

    func testNeutralizedOppositionDropsConflictEdgeOnBothSides() throws {
        let store = IOSEvolutionExperienceStore(baseDirectory: try makeTempRoot())
        let curator = IOSEvolutionExperienceCurator(store: store)

        let always = try added(curator.add(
            applicability: "写文件决策",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "写文件前总是先搜索"
        ))
        let never = try added(curator.add(
            applicability: "写文件决策",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "写文件前从不搜索"
        ))
        XCTAssertTrue(try store.experience(id: always.id)?
            .conflicts.contains { $0.otherExperienceId == never.id } ?? false)

        // Updating the opposition away must resync BOTH sides' edges.
        guard case .updated = curator.update(experienceId: never.id, ruleText: "写文件前总是先搜索") else {
            return XCTFail("update must succeed")
        }
        XCTAssertFalse(try store.experience(id: never.id)?
            .conflicts.contains { $0.otherExperienceId == always.id } ?? true)
        XCTAssertFalse(try store.experience(id: always.id)?
            .conflicts.contains { $0.otherExperienceId == never.id } ?? true)
    }

    // MARK: - Acceptance 3: harmful threshold → approval-gated suggestion

    func testHarmfulThresholdProducesSuggestionButEntryStaysActive() throws {
        let store = IOSEvolutionExperienceStore(baseDirectory: try makeTempRoot())
        let curator = IOSEvolutionExperienceCurator(store: store)
        let entry = try added(curator.add(
            applicability: "RSS 简报整理任务",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "总是先搜索再写文件"
        ))

        var lastSuggestion: IOSExperienceActionSuggestion?
        for _ in 0..<3 {
            guard case .recorded(let experience, let suggestion) =
                curator.recordHarmful(experienceId: entry.id) else {
                return XCTFail("recordHarmful must record")
            }
            XCTAssertEqual(experience.status, .active)
            lastSuggestion = suggestion ?? lastSuggestion
        }
        let supersedeSuggestion = try XCTUnwrap(lastSuggestion, "harmful==3 must produce a suggestion")
        XCTAssertEqual(supersedeSuggestion.kind, .supersede)
        XCTAssertEqual(supersedeSuggestion.experienceId, entry.id)
        XCTAssertEqual(supersedeSuggestion.harmfulCount, 3)

        // Not approved → not applied: the entry stays active (acceptance 3).
        let after3 = try XCTUnwrap(store.experience(id: entry.id))
        XCTAssertEqual(after3.status, .active)
        XCTAssertEqual(after3.harmfulCount, 3)

        // No NEW suggestion between the thresholds (harmful == 4).
        guard case .recorded(let at4, let at4Suggestion) =
            curator.recordHarmful(experienceId: entry.id) else {
            return XCTFail("recordHarmful must record")
        }
        XCTAssertEqual(at4.harmfulCount, 4)
        XCTAssertNil(at4Suggestion, "harmful==4 must not produce a new suggestion")

        // Crossing the delete threshold (5) → delete suggestion; still active.
        guard case .recorded(_, let at5Suggestion) =
            curator.recordHarmful(experienceId: entry.id) else {
            return XCTFail("recordHarmful must record")
        }
        let deleteSuggestion = try XCTUnwrap(at5Suggestion, "harmful==5 must produce a delete suggestion")
        XCTAssertEqual(deleteSuggestion.kind, .delete)
        XCTAssertEqual(deleteSuggestion.harmfulCount, 5)
        let after5 = try XCTUnwrap(store.experience(id: entry.id))
        XCTAssertEqual(after5.status, .active, "suggestion must not apply itself")
        XCTAssertEqual(after5.harmfulCount, 5)

        // helpful signals update counters and produce no suggestion.
        guard case .recorded(let helped, let helpedSuggestion) =
            curator.recordHelpful(experienceId: entry.id) else {
            return XCTFail("recordHelpful must record")
        }
        XCTAssertEqual(helped.helpfulCount, 1)
        XCTAssertNil(helpedSuggestion)
    }

    // MARK: - Acceptance 4: topK AND byteBudget double caps, bounded with 100 entries

    func testRetrievalRespectsTopKAndByteBudgetWith100Entries() throws {
        let store = IOSEvolutionExperienceStore(baseDirectory: try makeTempRoot())
        let curator = IOSEvolutionExperienceCurator(store: store)
        for index in 0..<100 {
            let result = store.add(IOSEvolutionExperience(
                id: "exp-budget-\(index)",
                applicability: "RSS 简报整理任务",
                counterexamples: [],
                evidenceRefs: [],
                helpfulCount: 0,
                harmfulCount: 0,
                status: .active,
                supersededByExperienceId: nil,
                conflicts: [],
                ruleText: "总是先搜索再写文件，编号 \(index)",
                createdAtEpochMs: 1_000,
                updatedAtEpochMs: 1_000
            ))
            guard case .added = result else {
                return XCTFail("bulk add \(index) failed: \(result)")
            }
        }

        // topK caps first when the budget is generous.
        guard case .items(let capped) = curator.retrieve(
            taskContext: "总是先搜索再写文件", topK: 5, byteBudget: 1_000_000
        ) else {
            return XCTFail("expected items")
        }
        XCTAssertEqual(capped.count, 5)

        // byteBudget caps second: with 100 entries the injected bytes stay
        // strictly bounded (acceptance 4 — no linear prompt blowup).
        let byteBudget = 600
        guard case .items(let bounded) = curator.retrieve(
            taskContext: "总是先搜索再写文件", topK: 100, byteBudget: byteBudget
        ) else {
            return XCTFail("expected items")
        }
        XCTAssertFalse(bounded.isEmpty)
        XCTAssertLessThan(bounded.count, 100, "byte budget must bind with 100 entries")
        let totalBytes = bounded.reduce(0) { partial, item in
            partial + IOSExperienceByteAccounting.encodedByteCount(
                experience: item.experience,
                suppressedConflictingExperienceIds: item.suppressedConflictingExperienceIds
            )
        }
        XCTAssertLessThanOrEqual(totalBytes, byteBudget, "retrieval output bytes must respect byteBudget")

        // Deterministic: identical queries → identical results.
        guard case .items(let again) = curator.retrieve(
            taskContext: "总是先搜索再写文件", topK: 100, byteBudget: byteBudget
        ) else {
            return XCTFail("expected items")
        }
        XCTAssertEqual(bounded, again)
    }

    func testRetrievalSkipsInactiveRejectedAndIrrelevant() throws {
        let store = IOSEvolutionExperienceStore(baseDirectory: try makeTempRoot())
        let curator = IOSEvolutionExperienceCurator(store: store)
        _ = try added(curator.add(
            applicability: "RSS 简报整理任务",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "总是先搜索再写文件"
        ))
        guard case .items(let matched) = curator.retrieve(
            taskContext: "总是先搜索", topK: 10, byteBudget: 10_000
        ) else {
            return XCTFail("expected items")
        }
        XCTAssertEqual(matched.count, 1)

        guard case .items(let none) = curator.retrieve(
            taskContext: "今天天气如何", topK: 10, byteBudget: 10_000
        ) else {
            return XCTFail("expected items")
        }
        XCTAssertTrue(none.isEmpty, "irrelevant context must inject nothing")

        // Zero/negative caps are honored.
        guard case .items(let emptyTopK) = curator.retrieve(
            taskContext: "总是先搜索", topK: 0, byteBudget: 10_000
        ) else {
            return XCTFail("expected items")
        }
        XCTAssertTrue(emptyTopK.isEmpty)
        guard case .items(let emptyBudget) = curator.retrieve(
            taskContext: "总是先搜索", topK: 10, byteBudget: 0
        ) else {
            return XCTFail("expected items")
        }
        XCTAssertTrue(emptyBudget.isEmpty)
    }

    // MARK: - State machine: update / supersede / delete / rejected

    func testSupersedeDeleteUpdateStateMachine() throws {
        let store = IOSEvolutionExperienceStore(baseDirectory: try makeTempRoot())
        let curator = IOSEvolutionExperienceCurator(store: store)

        let original = try added(curator.add(
            applicability: "RSS 简报整理任务",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "总是先搜索再写文件"
        ))
        let replacement = try added(curator.add(
            applicability: "翻译任务",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "先确认目标语言再翻译"
        ))

        // update: content change keeps the id.
        guard case .updated(let updated) = curator.update(
            experienceId: original.id,
            ruleText: "总是先搜索，然后确认来源再写文件"
        ) else {
            return XCTFail("update must succeed")
        }
        XCTAssertEqual(updated.id, original.id)
        XCTAssertEqual(updated.ruleText, "总是先搜索，然后确认来源再写文件")

        // supersede: old → superseded pointing at the new entry.
        guard case .superseded(let retired) = curator.supersede(
            experienceId: original.id, newExperienceId: replacement.id
        ) else {
            return XCTFail("supersede must succeed")
        }
        XCTAssertEqual(retired.status, .superseded)
        XCTAssertEqual(retired.supersededByExperienceId, replacement.id)
        XCTAssertEqual(try store.experience(id: replacement.id)?.status, .active)

        // Superseding a non-active entry is a typed rejection.
        guard case .rejected = curator.supersede(
            experienceId: original.id, newExperienceId: replacement.id
        ) else {
            return XCTFail("re-superseding a retired entry must be rejected")
        }

        // Retrieval never injects retired entries.
        guard case .items(let items) = curator.retrieve(
            taskContext: "总是先搜索再写文件", topK: 10, byteBudget: 100_000
        ) else {
            return XCTFail("expected items")
        }
        XCTAssertTrue(items.allSatisfy { $0.experience.status == .active })
        XCTAssertFalse(items.contains { $0.experience.id == original.id })

        // delete: physical removal + tombstone (防复读).
        guard case .deleted(let tombstone) = curator.delete(experienceId: replacement.id, reason: "过时") else {
            return XCTFail("delete must succeed")
        }
        XCTAssertEqual(tombstone.id, replacement.id)
        XCTAssertEqual(tombstone.reason, "过时")
        XCTAssertNil(try store.experience(id: replacement.id), "delete must physically remove the entry")
        XCTAssertTrue(try store.tombstones().contains { $0.id == replacement.id })
        XCTAssertEqual(try store.allExperiences().count, 1)
        guard case .rejected(.experienceNotFound) = curator.delete(experienceId: replacement.id) else {
            return XCTFail("deleting an unknown id must be a typed rejection")
        }

        // Re-adding the SAME content surfaces the tombstone instead of being
        // re-created blindly.
        let readd = store.add(IOSEvolutionExperience(
            id: "exp-readd",
            applicability: "翻译任务",
            counterexamples: [],
            evidenceRefs: [],
            helpfulCount: 0,
            harmfulCount: 0,
            status: .active,
            supersededByExperienceId: nil,
            conflicts: [],
            ruleText: "先确认目标语言再翻译",
            createdAtEpochMs: 2_000,
            updatedAtEpochMs: 2_000
        ))
        guard case .added(_, let matchedTombstone) = readd else {
            return XCTFail("re-add must be allowed, got \(readd)")
        }
        XCTAssertEqual(matchedTombstone?.id, replacement.id, "tombstone must be surfaced on re-add")

        // rejected status via update: excluded from retrieval.
        guard case .updated(let rejected) = curator.update(experienceId: "exp-readd", status: .rejected) else {
            return XCTFail("update to rejected must succeed")
        }
        XCTAssertEqual(rejected.status, .rejected)
        XCTAssertNil(rejected.supersededByExperienceId)
        guard case .items(let afterReject) = curator.retrieve(
            taskContext: "先确认目标语言", topK: 10, byteBudget: 100_000
        ) else {
            return XCTFail("expected items")
        }
        XCTAssertFalse(afterReject.contains { $0.experience.id == "exp-readd" })
    }

    func testMergeConsolidatesIntoTargetAndSupersedesSource() throws {
        let store = IOSEvolutionExperienceStore(baseDirectory: try makeTempRoot())
        let curator = IOSEvolutionExperienceCurator(store: store)

        let first = try added(curator.add(
            applicability: "RSS 简报整理任务",
            counterexamples: ["凌晨的源不处理"],
            evidenceRefs: [IOSEvidenceRef(kind: .ledgerEvent, id: "ev-1")],
            ruleText: "总是先搜索再写文件",
            helpfulCount: 2
        ))
        let second = try added(curator.add(
            applicability: "RSS 简报整理任务",
            counterexamples: [],
            evidenceRefs: [IOSEvidenceRef(kind: .ledgerEvent, id: "ev-2")],
            ruleText: "从不直接写文件",
            helpfulCount: 3
        ))
        // Same applicability + 总是/从不 opposition → add-time conflict edges.
        XCTAssertTrue(try store.experience(id: first.id)?
            .conflicts.contains { $0.otherExperienceId == second.id } ?? false)
        XCTAssertTrue(try store.experience(id: second.id)?
            .conflicts.contains { $0.otherExperienceId == first.id } ?? false)

        guard case .merged(let target, let supersededSource) = curator.merge(
            sourceExperienceId: first.id, into: second.id
        ) else {
            return XCTFail("merge must succeed")
        }
        XCTAssertEqual(target.id, second.id)
        XCTAssertEqual(target.ruleText, "从不直接写文件", "merge keeps the target's rule text")
        XCTAssertEqual(target.helpfulCount, 5, "counters must be summed")
        XCTAssertTrue(target.evidenceRefs.contains(IOSEvidenceRef(kind: .ledgerEvent, id: "ev-1")))
        XCTAssertTrue(target.evidenceRefs.contains(IOSEvidenceRef(kind: .ledgerEvent, id: "ev-2")))
        XCTAssertTrue(target.counterexamples.contains("凌晨的源不处理"))
        XCTAssertEqual(supersededSource.id, first.id)
        XCTAssertEqual(supersededSource.status, .superseded)
        XCTAssertEqual(supersededSource.supersededByExperienceId, second.id)
        // The target's stale edge to the (now retired) source is resynced away.
        XCTAssertFalse(try store.experience(id: second.id)?
            .conflicts.contains { $0.otherExperienceId == first.id } ?? true)

        // Feedback on the retired source is a typed rejection.
        guard case .rejected = curator.recordHarmful(experienceId: first.id) else {
            return XCTFail("feedback on a superseded entry must be rejected")
        }
        // Merging into a retired entry is a typed rejection.
        guard case .rejected = curator.merge(sourceExperienceId: second.id, into: first.id) else {
            return XCTFail("merging into a retired entry must be rejected")
        }
    }

    // MARK: - Store hard caps: typed rejections

    func testStoreLimitsRejectTypedAdds() throws {
        let store = IOSEvolutionExperienceStore(
            baseDirectory: try makeTempRoot(),
            limits: IOSExperienceStoreLimits(
                maxExperienceCount: 2,
                maxTotalBytes: 4_096,
                maxTombstoneCount: 2
            )
        )
        func makeEntry(_ id: String, rule: String) -> IOSEvolutionExperience {
            IOSEvolutionExperience(
                id: id,
                applicability: "测试场景",
                counterexamples: [],
                evidenceRefs: [],
                helpfulCount: 0,
                harmfulCount: 0,
                status: .active,
                supersededByExperienceId: nil,
                conflicts: [],
                ruleText: rule,
                createdAtEpochMs: 1,
                updatedAtEpochMs: 1
            )
        }

        guard case .added = store.add(makeEntry("a", rule: "总是先搜索再写文件")) else {
            return XCTFail("first add must succeed")
        }
        guard case .added = store.add(makeEntry("b", rule: "先确认目标语言再翻译")) else {
            return XCTFail("second add must succeed")
        }
        guard case .rejected(.overEntryLimit(let current, let limit)) =
            store.add(makeEntry("c", rule: "从不直接写文件")) else {
            return XCTFail("3rd entry over maxExperienceCount=2 must be a typed rejection")
        }
        XCTAssertEqual(current, 2)
        XCTAssertEqual(limit, 2)
        XCTAssertEqual(try store.allExperiences().count, 2, "a rejected add must not mutate the pool")

        // Byte cap: an entry whose encoded document exceeds the cap is a
        // typed rejection, not a truncation.
        let byteStore = IOSEvolutionExperienceStore(
            baseDirectory: try makeTempRoot(),
            limits: IOSExperienceStoreLimits(
                maxExperienceCount: 10,
                maxTotalBytes: 400,
                maxTombstoneCount: 2
            )
        )
        let longRule = "总是先搜索再写文件" + String(repeating: "，内容补充", count: 60)
        guard case .rejected(.overByteLimit) = byteStore.add(makeEntry("big", rule: longRule)) else {
            return XCTFail("oversized entry must be a typed byte-limit rejection")
        }
        XCTAssertEqual(try byteStore.allExperiences().count, 0)

        // Tombstones are FIFO-capped so deletions cannot grow the document
        // without bound either.
        let fifoStore = IOSEvolutionExperienceStore(
            baseDirectory: try makeTempRoot(),
            limits: IOSExperienceStoreLimits(
                maxExperienceCount: 10,
                maxTotalBytes: 4_096,
                maxTombstoneCount: 2
            )
        )
        for id in ["t1", "t2", "t3"] {
            guard case .added = fifoStore.add(makeEntry(id, rule: "规则 \(id)")) else {
                return XCTFail("add \(id) must succeed")
            }
            guard case .deleted = fifoStore.delete(id: id, reason: nil) else {
                return XCTFail("delete \(id) must succeed")
            }
        }
        let tombstones = try fifoStore.tombstones()
        XCTAssertEqual(tombstones.count, 2, "tombstones must be FIFO-capped")
        XCTAssertFalse(tombstones.contains { $0.id == "t1" }, "oldest tombstone must be dropped")
        XCTAssertTrue(tombstones.contains { $0.id == "t2" })
        XCTAssertTrue(tombstones.contains { $0.id == "t3" })
    }

    func testCuratorAddSurfacesStoreLimitRejection() throws {
        let store = IOSEvolutionExperienceStore(
            baseDirectory: try makeTempRoot(),
            limits: IOSExperienceStoreLimits(
                maxExperienceCount: 1,
                maxTotalBytes: 4_096,
                maxTombstoneCount: 2
            )
        )
        let curator = IOSEvolutionExperienceCurator(store: store)
        guard case .added = curator.add(
            applicability: "RSS 简报整理任务",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "总是先搜索再写文件"
        ) else {
            return XCTFail("first add must succeed")
        }
        guard case .rejected(.overEntryLimit) = curator.add(
            applicability: "翻译任务",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "先确认目标语言再翻译"
        ) else {
            return XCTFail("over-limit curator add must surface the typed rejection")
        }
        XCTAssertEqual(try store.allExperiences().count, 1, "curator must not evict or truncate")
    }

    // MARK: - Fixtures

    private func makeTempRoot() throws -> URL {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSEvolutionExperienceTests-\(UUID().uuidString)", isDirectory: true)
        // The wave contract requires tests to create the temp directory
        // explicitly before handing it to the store.
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        tempRoots.append(root)
        return root
    }

    private enum TestUnwrapError: Error, CustomStringConvertible {
        case unexpected(String)
        var description: String {
            if case .unexpected(let message) = self { return message }
            return ""
        }
    }

    private func added(_ outcome: IOSExperienceAddOutcome) throws -> IOSEvolutionExperience {
        guard case .added(let experience, _) = outcome else {
            throw TestUnwrapError.unexpected("expected .added, got \(outcome)")
        }
        return experience
    }
}
