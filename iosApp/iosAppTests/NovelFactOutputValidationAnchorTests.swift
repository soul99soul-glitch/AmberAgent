import XCTest
@testable import iosApp

/// Covers the anchor-based relaxation of evidence matching, now owned by the
/// single `NovelFactOutputValidation.evidenceAnchorRange(_:in:)` function:
/// strict literal substring matching (after whitespace/printing-variant
/// normalization) remains the fast path, but paraphrased/summarized evidence
/// that still anchors on a long-enough, high-enough-coverage literal run of
/// the manuscript is now accepted instead of being silently discarded.
///
/// These tests exercise `NovelFactTransactionReducer.sanitizedCollectionDelta`
/// directly, which is convenient for isolating the matching/ratio-threshold
/// behavior itself (fixture setup only needs a document/branch/baseState, not
/// a full pending-collection or pending-manual-sync lifecycle). Historically
/// `sanitizedCollectionDelta` and the separate `validateStateFacts` step some
/// callers layer on top afterward used two different matching rules, which
/// is exactly the bug this anchor unification fixes — both now go through
/// the same `evidenceAnchorRange`, so there is no separate "stricter re-check"
/// left to interact with. Full production-entry-point coverage (proving the
/// two steps agree end to end) lives in
/// `NovelFactTransactionLifecycleTests
/// .testManualChunkOutputAcceptsParaphrasedEvidenceAnchoredInManuscriptThroughFullChain`
/// and `NovelCollectionTests.testCollectionAcceptsParaphrasedEvidenceAnchoredInManuscript`.
final class NovelFactOutputValidationAnchorTests: XCTestCase {
    // MARK: - Fixtures shared across cases

    /// A manuscript sentence lifted verbatim from the task's own real-world
    /// repro (a rewritten sentence the model paraphrased while collecting a
    /// chapter). Includes a second, unrelated sentence so anchor tests have
    /// more manuscript context than just the one target sentence.
    private static let manuscript =
        "赵匡胤低头看着杯子里微微泛着褐色的茶水，把那句没有说出口的话重新咽了回去。" +
        "窗外的雪不知何时停了，只余下满城的寂静。"

    private func makeDocumentContext() throws -> (
        document: NovelProjectDocumentV1,
        branch: NovelBranchRecord,
        baseState: NovelStateSnapshotRecord
    ) {
        let document = try NovelTestFixtures.document()
        let branch = document.branches[0]
        let baseState = try XCTUnwrap(document.stateSnapshots.first)
        return (document, branch, baseState)
    }

    private func delta(evidence: String) -> NovelStateDeltaV1 {
        NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: "赵匡胤欲言又止。",
            events: [NovelStateEventV1(
                id: "event-under-test",
                kind: "dialogue",
                summary: "赵匡胤把话咽了回去。",
                entityReferences: ["赵匡胤"],
                evidence: evidence
            )],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: ["赵匡胤"],
            branchOutlinePatch: "赵匡胤欲言又止。",
            settingProposals: []
        )
    }

    /// Same shape as `delta(evidence:)`, but paired with a second, always-
    /// literal-matching decoy event. `sanitizedCollectionDelta` throws
    /// `state_facts_evidence_unmatched` when *every* raw fact is discarded
    /// (see `requireEvidenceNotAllDiscarded`), so a single-event delta can't
    /// be used to observe silent per-item dropping — it only ever throws or
    /// fully survives. Pairing with a decoy keeps `hasEvidenceBackedFacts`
    /// true, letting these tests assert that only the target event under
    /// test was dropped.
    private func deltaWithDecoy(targetEvidence: String) -> NovelStateDeltaV1 {
        NovelStateDeltaV1(
            schemaVersion: 1,
            stateSummary: "赵匡胤欲言又止。",
            events: [
                NovelStateEventV1(
                    id: "event-decoy",
                    kind: "scene",
                    summary: "窗外的雪停了。",
                    entityReferences: [],
                    evidence: "窗外的雪不知何时停了，只余下满城的寂静。"
                ),
                NovelStateEventV1(
                    id: "event-under-test",
                    kind: "dialogue",
                    summary: "赵匡胤把话咽了回去。",
                    entityReferences: ["赵匡胤"],
                    evidence: targetEvidence
                ),
            ],
            characterChanges: [],
            relationshipChanges: [],
            foreshadowingChanges: [],
            unresolvedEntityNames: ["赵匡胤"],
            branchOutlinePatch: "赵匡胤欲言又止。",
            settingProposals: []
        )
    }

    // MARK: - 1. Exact excerpt regression

    func testExactExcerptStillMatches() throws {
        let (document, branch, baseState) = try makeDocumentContext()
        let sanitized = try NovelFactTransactionReducer.sanitizedCollectionDelta(
            in: delta(evidence: "赵匡胤低头看着杯子里微微泛着褐色的茶水，把那句没有说出口的话重新咽了回去。"),
            evidenceSource: Self.manuscript,
            branch: branch,
            baseState: baseState,
            document: document
        )
        XCTAssertEqual(sanitized.events.map(\.id), ["event-under-test"])
    }

    // MARK: - 2. Paraphrase with a long anchor: the core fix

    func testParaphraseWithLongAnchorIsAccepted() throws {
        let (document, branch, baseState) = try makeDocumentContext()
        // Paraphrased: same leading clause verbatim (20-character anchor,
        // well above the 8-character floor and the 40% ratio floor of this
        // 29-character evidence string), but the tail is reworded instead
        // of quoted. The old literal-substring-only implementation rejects
        // this outright — this is the red case the task exists to fix.
        let paraphrased = "赵匡胤低头看着杯子里微微泛着褐色的茶水，咽回了没说出口的话"
        let sanitized = try NovelFactTransactionReducer.sanitizedCollectionDelta(
            in: delta(evidence: paraphrased),
            evidenceSource: Self.manuscript,
            branch: branch,
            baseState: baseState,
            document: document
        )
        XCTAssertEqual(
            sanitized.events.map(\.id),
            ["event-under-test"],
            "A paraphrase that anchors on a 20-character verbatim run (69% of its own length) must survive sanitization."
        )
    }

    // MARK: - 3. Pure fabrication still rejected

    func testPureFabricationIsStillRejected() throws {
        let (document, branch, baseState) = try makeDocumentContext()
        // Shares only the 3-character name "赵匡胤" with the manuscript; the
        // longest common contiguous run is 3 characters (well under the
        // 8-character floor), so this must remain rejected exactly as
        // before the anchor relaxation.
        let fabricated = "赵匡胤怒吼一声，拔出腰间长剑直取城楼上的敌将首级，鲜血溅满了城墙。"
        let sanitized = try NovelFactTransactionReducer.sanitizedCollectionDelta(
            in: deltaWithDecoy(targetEvidence: fabricated),
            evidenceSource: Self.manuscript,
            branch: branch,
            baseState: baseState,
            document: document
        )
        XCTAssertEqual(
            sanitized.events.map(\.id),
            ["event-decoy"],
            "Fabricated evidence sharing only a few common characters with the manuscript must not pass the anchor check."
        )
    }

    // MARK: - 4. Small genuine fragment padded with fabrication: ratio gate

    func testSmallGenuineFragmentPaddedWithFabricationIsRejected() throws {
        let (document, branch, baseState) = try makeDocumentContext()
        // Contains a genuine 13-character anchor ("杯子里微微泛着褐色的茶水，那"-adjacent
        // run) which alone clears the 8-character floor, but the evidence
        // string is 38 characters long, so the anchor covers only ~34% of
        // it — below the 40% ratio floor. Must still be rejected: a short
        // real quote cannot be used to smuggle a mostly-invented sentence.
        let mostlyFabricated = "宫殿之外传来阵阵金属交击之声，杯子里微微泛着褐色的茶水，那是敌军攻城的信号。"
        let sanitized = try NovelFactTransactionReducer.sanitizedCollectionDelta(
            in: deltaWithDecoy(targetEvidence: mostlyFabricated),
            evidenceSource: Self.manuscript,
            branch: branch,
            baseState: baseState,
            document: document
        )
        XCTAssertEqual(
            sanitized.events.map(\.id),
            ["event-decoy"],
            "A short genuine fragment padded with a large fabricated majority must fail the 40% coverage ratio."
        )
    }

    // MARK: - 5. Short evidence must still match literally

    func testShortEvidenceRequiresLiteralMatch() throws {
        let (document, branch, baseState) = try makeDocumentContext()
        // 7 characters — shorter than minimumAnchorLength (8) — and not a
        // literal substring of the manuscript. The anchor threshold for
        // evidence this short exceeds the evidence's own length, so it can
        // only pass via the literal fast path, which it does not clear.
        let shortParaphrase = "赵匡胤把话咽下"
        XCTAssertEqual(shortParaphrase.count, 7)
        let sanitized = try NovelFactTransactionReducer.sanitizedCollectionDelta(
            in: deltaWithDecoy(targetEvidence: shortParaphrase),
            evidenceSource: Self.manuscript,
            branch: branch,
            baseState: baseState,
            document: document
        )
        XCTAssertEqual(
            sanitized.events.map(\.id),
            ["event-decoy"],
            "Short paraphrased evidence must not be granted anchor tolerance; it must still match literally."
        )
    }

    // MARK: - 6. Printing-variant regression

    func testPrintingVariantEvidenceStillMatches() throws {
        let (document, branch, baseState) = try makeDocumentContext()
        // Manuscript style differs only in guillemet quotes vs straight
        // quotes and fullwidth vs halfwidth comma — normalizePrintingVariants
        // already handles this; this must stay on the literal fast path.
        let manuscript = "「赵匡胤" + "低头看着杯子里微微泛着褐色的茶水」，把那句没有说出口的话重新咽了回去。"
        let variantEvidence = "\"赵匡胤低头看着杯子里微微泛着褐色的茶水\",把那句没有说出口的话重新咽了回去。"
        let sanitized = try NovelFactTransactionReducer.sanitizedCollectionDelta(
            in: delta(evidence: variantEvidence),
            evidenceSource: manuscript,
            branch: branch,
            baseState: baseState,
            document: document
        )
        XCTAssertEqual(sanitized.events.map(\.id), ["event-under-test"])
    }

    // MARK: - 7. Long manuscript + short evidence stays fast

    func testLongManuscriptWithShortEvidenceIsNotSlow() throws {
        let (document, branch, baseState) = try makeDocumentContext()
        let filler = String(repeating: "落花人独立，微雨燕双飞。庭院深深深几许，杨柳堆烟，帘幕无重数。", count: 400)
        XCTAssertGreaterThanOrEqual(filler.count, 10_000)
        let longManuscript = filler + Self.manuscript + filler
        let paraphrased = "赵匡胤低头看着杯子里微微泛着褐色的茶水，咽回了没说出口的话"

        let start = CFAbsoluteTimeGetCurrent()
        let sanitized = try NovelFactTransactionReducer.sanitizedCollectionDelta(
            in: delta(evidence: paraphrased),
            evidenceSource: longManuscript,
            branch: branch,
            baseState: baseState,
            document: document
        )
        let elapsed = CFAbsoluteTimeGetCurrent() - start

        XCTAssertEqual(sanitized.events.map(\.id), ["event-under-test"])
        XCTAssertLessThan(
            elapsed,
            2.0,
            "Anchor matching against a >=10k-character manuscript with short evidence should not noticeably stall."
        )
    }
}
