import CryptoKit
import XCTest
@testable import iosApp

final class NovelPromptCatalogTests: XCTestCase {
    func testCatalogSnapshot() {
        let templates = NovelPromptKind.allCases.map(NovelPromptCatalog.template)
        let snapshot = templates.map {
            "\($0.kind.rawValue)\n\($0.version)\n\($0.systemText)"
        }.joined(separator: "\n---\n")

        XCTAssertEqual(
            sha256(snapshot),
            "9f35846459ff25dd841e1005c83893a734a5fd681493e8cdc108d51a40613920"
        )
        XCTAssertEqual(Set(templates.map(\.version)).count, NovelPromptKind.allCases.count)
    }

    func testUserVisiblePromptsPreserveDomainBoundaries() {
        let discussion = NovelPromptCatalog.template(for: .discussion).systemText
        let continuation = NovelPromptCatalog.template(for: .proseContinuation).systemText
        let wholeChapter = NovelPromptCatalog.template(for: .proseWholeChapter).systemText
        let polish = NovelPromptCatalog.template(for: .wholeChapterPolish).systemText

        XCTAssertTrue(discussion.contains("Do not write canonical manuscript"))
        XCTAssertTrue(discussion.contains("ask one focused question"))
        XCTAssertTrue(discussion.contains("recommended option"))
        XCTAssertTrue(discussion.contains("own words"))
        XCTAssertTrue(continuation.contains("does not become canonical"))
        XCTAssertTrue(continuation.contains("one focused scene or passage"))
        XCTAssertTrue(continuation.contains("Do not close the chapter"))
        XCTAssertTrue(wholeChapter.contains("complete next chapter"))
        XCTAssertTrue(wholeChapter.contains("chapter-level arc"))
        XCTAssertTrue(wholeChapter.contains("Markdown H1 chapter heading"))
        XCTAssertTrue(polish.contains("must not add, remove, reorder"))
        XCTAssertTrue(polish.contains("relationships, motivations, secrets, outcomes"))
        XCTAssertTrue(polish.contains(NovelPromptCatalog.polishCompletionSentinel))
    }

    func testHistoricalUserVisiblePromptsRemainVerifiable() throws {
        let quickStartV2 = try XCTUnwrap(NovelPromptCatalog.systemText(
            for: .quickStart,
            version: "novel.quick-start.v2"
        ))
        XCTAssertEqual(
            sha256(quickStartV2),
            "68cfc3eb39c5ee7b963bef2ce639fe83737de3c013ca72262387445313d66828"
        )
        XCTAssertNotNil(NovelPromptCatalog.systemText(
            for: .discussion,
            version: "novel.discussion.v1"
        ))
        XCTAssertNotNil(NovelPromptCatalog.systemText(
            for: .proseContinuation,
            version: "novel.prose-continuation.v1"
        ))
        XCTAssertNotNil(NovelPromptCatalog.systemText(
            for: .proseWholeChapter,
            version: "novel.prose-whole-chapter.v1"
        ))
        XCTAssertNotNil(NovelPromptCatalog.systemText(
            for: .proseWholeChapter,
            version: "novel.prose-whole-chapter.v2"
        ))
        XCTAssertNil(NovelPromptCatalog.systemText(
            for: .discussion,
            version: "novel.discussion.unknown"
        ))
    }

    func testStructuredPromptsPublishDecoderExactContracts() throws {
        let quickStart = NovelPromptCatalog.template(for: .quickStart).systemText
        let state = NovelPromptCatalog.template(for: .stateDeltaV1).systemText
        let rebuild = NovelPromptCatalog.template(for: .manualSyncV1).systemText
        let drift = NovelPromptCatalog.template(for: .polishDriftV1).systemText

        for field in [
            "schemaVersion", "overview", "world", "characters", "masterOutline",
            "writingRequirements", "title", "content"
        ] {
            XCTAssertTrue(
                quickStart.contains("\"\(field)\""),
                "Quick Start Prompt is missing \(field)"
            )
        }
        XCTAssertEqual(NovelPromptCatalog.template(for: .quickStart).version, "novel.quick-start.v3")
        XCTAssertNoThrow(
            try NovelStructuredOutputDecoder.decodeQuickStartSuggestions(from: quickStartExample)
        )

        for field in [
            "schemaVersion", "stateSummary", "events", "characterChanges",
            "relationshipChanges", "foreshadowingChanges", "unresolvedEntityNames",
            "branchOutlinePatch", "settingProposals", "entityReferences", "evidence"
        ] {
            XCTAssertTrue(state.contains("\"\(field)\""), "State delta Prompt is missing \(field)")
        }
        for field in [
            "schemaVersion", "stateSummary", "branchOutline", "events", "characterStates",
            "relationships", "foreshadowing", "unresolvedEntityNames", "settingProposals"
        ] {
            XCTAssertTrue(rebuild.contains("\"\(field)\""), "State rebuild Prompt is missing \(field)")
        }
        for field in [
            "schemaVersion", "compatible", "differences", "category", "sourceEvidence",
            "candidateEvidence"
        ] {
            XCTAssertTrue(drift.contains("\"\(field)\""), "Polish drift Prompt is missing \(field)")
        }
        for prompt in [state, rebuild, drift] {
            XCTAssertTrue(prompt.contains("Return exactly one raw JSON object"))
            XCTAssertTrue(prompt.contains("Do not use Markdown fences"))
            XCTAssertTrue(prompt.contains("Do not add unknown keys"))
        }
        XCTAssertTrue(state.contains("introduced|advanced|resolved|reopened"))
        XCTAssertTrue(state.contains("complete current branch"))
        XCTAssertTrue(state.contains("replacement branch outline"))
        XCTAssertTrue(rebuild.contains("introduced|advanced|resolved|reopened"))
        XCTAssertTrue(drift.contains("event|chronology|relationship|motivation|secret|outcome"))
        XCTAssertTrue(drift.contains("fail closed"))

        XCTAssertNoThrow(try NovelStructuredOutputDecoder.decodeStateDelta(from: stateDeltaExample))
        XCTAssertNoThrow(try NovelStructuredOutputDecoder.decodeStateRebuild(from: stateRebuildExample))
        XCTAssertNoThrow(try NovelStructuredOutputDecoder.decodePolishDrift(from: polishDriftExample))
    }

    private func sha256(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }

    private var stateDeltaExample: String {
        """
        {
          "schemaVersion": 1,
          "stateSummary": "Mara escaped with the key.",
          "events": [{
            "id": "event-1",
            "kind": "escape",
            "summary": "Mara escaped the archive.",
            "entityReferences": ["Mara"],
            "evidence": "Mara crossed the gate before it closed."
          }],
          "characterChanges": [{
            "id": "character-1",
            "characterName": "Mara",
            "attribute": "possession",
            "value": "archive key",
            "evidence": "The key remained in her hand."
          }],
          "relationshipChanges": [{
            "id": "relationship-1",
            "sourceEntity": "Mara",
            "targetEntity": "Ivo",
            "relationship": "trust",
            "state": "damaged",
            "evidence": "She left Ivo behind."
          }],
          "foreshadowingChanges": [{
            "id": "thread-1",
            "thread": "the sealed vault",
            "status": "advanced",
            "summary": "The stolen key can open the vault.",
            "evidence": "The key bore the vault sigil."
          }],
          "unresolvedEntityNames": ["the masked archivist"],
          "branchOutlinePatch": "Mara must decide whether to return for Ivo.",
          "settingProposals": [{
            "id": "proposal-1",
            "title": "Archive gate rule",
            "content": "The gate may close at midnight.",
            "evidence": "The bells marked midnight as the gate closed."
          }]
        }
        """
    }

    private var quickStartExample: String {
        """
        {
          "schemaVersion": 2,
          "overview": "A memory mystery.",
          "world": {"title": "Rules", "content": "Memories can testify once."},
          "characters": [
            {"title": "Mara", "content": "An advocate forgets her past."},
            {"title": "Ivo", "content": "A witness remembers the wrong trial."}
          ],
          "masterOutline": {"title": "Appeal", "content": "A false memory reopens the case."},
          "writingRequirements": {"title": "Voice", "content": "Use concrete clues."}
        }
        """
    }

    private var stateRebuildExample: String {
        """
        {
          "schemaVersion": 1,
          "stateSummary": "Mara is outside the archive.",
          "branchOutline": "Mara prepares to return for Ivo.",
          "events": [],
          "characterStates": [],
          "relationships": [],
          "foreshadowing": [],
          "unresolvedEntityNames": [],
          "settingProposals": []
        }
        """
    }

    private var polishDriftExample: String {
        """
        {
          "schemaVersion": 1,
          "compatible": false,
          "differences": [{
            "id": "difference-1",
            "category": "chronology",
            "summary": "The candidate moves the escape before midnight.",
            "sourceEvidence": "The bells rang before Mara crossed the gate.",
            "candidateEvidence": "Mara crossed first; only then did the bells ring."
          }]
        }
        """
    }
}
