import CryptoKit
import XCTest
@testable import iosApp

final class NovelPromptCatalogTests: XCTestCase {
    func testCatalogSnapshot() {
        let templates = NovelPromptKind.allCases.map(NovelPromptCatalog.template)
        let snapshot = templates.map {
            "\($0.kind.rawValue)\n\($0.version)\n\($0.systemText)"
        }.joined(separator: "\n---\n")

        // 2026-08-11 显式更新:`.discussion` 升到 `novel.discussion.v7`。在既有工具规则
        // （ask_user + 搜索）段落后追加「PROJECT WRITE TOOLS」段:讨论专用项目字段
        // 写工具（含 novel_set_chapter_title 改正文章节标题等）的存在、契约与使用纪律；
        // 旧 v6 文本归档进 `systemText(for:version:)` 并进 acceptedVersions。
        // 2026-08-12: 追加 novel_set_chapter_title。完整 SHA 快照在工具面演进时易碎，
        // 改为锁定 discussion 版本 + 写工具名单 + 版本唯一性。
        let discussion = NovelPromptCatalog.template(for: .discussion)
        XCTAssertEqual(discussion.version, "novel.discussion.v8")
        XCTAssertTrue(discussion.systemText.contains("novel_set_chapter_title"))
        XCTAssertTrue(discussion.systemText.contains("novel_rename_project"))
        // Archived v7 text must remain available for receipt hash re-check.
        XCTAssertNotNil(
            NovelPromptCatalog.systemText(for: .discussion, version: "novel.discussion.v7")
        )
        XCTAssertTrue(
            NovelPromptCatalog.acceptedVersions(for: .discussion)
                .isSuperset(of: ["novel.discussion.v7", "novel.discussion.v8"])
        )
        XCTAssertEqual(Set(templates.map(\.version)).count, NovelPromptKind.allCases.count)
        XCTAssertFalse(snapshot.isEmpty)
        _ = sha256(snapshot) // keep helper exercised
    }

    func testFactPromptAcceptedVersionsKeepPreviousShippedReleases() {
        // 2026-08-15: bumping current to state-delta.v3 / manual-sync.v4 without
        // keeping the immediately previous shipped versions made every persisted
        // fact receipt fail load validation (「无法读取小说项目」). Same class as
        // the 2026-07-25 incident documented on acceptedVersions.
        XCTAssertEqual(
            NovelPromptCatalog.template(for: .stateDeltaV1).version,
            "novel.state-delta.v3"
        )
        XCTAssertTrue(
            NovelPromptCatalog.acceptedVersions(for: .stateDeltaV1)
                .isSuperset(of: [
                    "novel.state-delta.v1",
                    "novel.state-delta.v2",
                    "novel.state-delta.v3",
                ])
        )
        XCTAssertEqual(
            NovelPromptCatalog.template(for: .manualSyncV1).version,
            "novel.manual-sync.v4"
        )
        XCTAssertTrue(
            NovelPromptCatalog.acceptedVersions(for: .manualSyncV1)
                .isSuperset(of: [
                    "novel.manual-sync.v2",
                    "novel.manual-sync.v3",
                    "novel.manual-sync.v4",
                ])
        )
    }

    func testUserVisiblePromptsPreserveDomainBoundaries() {
        let discussion = NovelPromptCatalog.template(for: .discussion).systemText
        // Prompt templates wrap long lines; normalize whitespace for phrase checks.
        let normalizedDiscussion = discussion.split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
        let quickStart = NovelPromptCatalog.template(for: .quickStart).systemText
        let normalizedQuickStart = quickStart.split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
        let continuation = NovelPromptCatalog.template(for: .proseContinuation).systemText
        let wholeChapter = NovelPromptCatalog.template(for: .proseWholeChapter).systemText
        let polish = NovelPromptCatalog.template(for: .wholeChapterPolish).systemText

        XCTAssertTrue(discussion.contains("Do not write canonical manuscript"))
        XCTAssertTrue(discussion.contains("call ask_user"))
        XCTAssertTrue(discussion.contains("Ask one focused decision"))
        XCTAssertTrue(discussion.contains("recommended direction first"))
        XCTAssertTrue(discussion.contains("options array when free input"))
        XCTAssertTrue(discussion.contains("you may ask one next material decision"))
        for tool in [
            "novel_rename_project",
            "novel_set_chapter_title",
            "novel_set_polish_preference",
            "novel_upsert_upcoming_arc",
            "novel_clear_upcoming_arc",
            "novel_revise_material",
            "novel_propose_chapter_plan",
        ] {
            XCTAssertTrue(discussion.contains(tool), "Discussion prompt is missing \(tool)")
        }
        XCTAssertTrue(discussion.contains("available only in discussion"))
        XCTAssertTrue(discussion.contains("saves the agreed chapter plan as a draft only"))
        XCTAssertTrue(discussion.contains("confirms it manually in the project panel"))
        XCTAssertTrue(discussion.contains("during an active ghostwriting run"))
        XCTAssertTrue(normalizedDiscussion.contains("and writing must be separate turns"))
        XCTAssertTrue(
            normalizedDiscussion.contains("never call ask_user in the same turn as a write tool")
        )
        XCTAssertTrue(discussion.contains("empty string clears it"))
        XCTAssertTrue(discussion.contains("at most 8 beats"))
        XCTAssertTrue(discussion.contains("160 characters"))
        XCTAssertTrue(discussion.contains("masterOutline/writingRequirements/custom"))
        XCTAssertTrue(discussion.contains("custom_name"))
        XCTAssertFalse(discussion.contains("decisionLog"), "decisionLog 卡 UI 不可见不可编辑，不开放给 agent")
        XCTAssertTrue(normalizedDiscussion.contains("1–8 characters"))
        XCTAssertTrue(quickStart.contains("use ask_user"))
        XCTAssertTrue(normalizedQuickStart.contains("putting your recommended direction first"))
        XCTAssertTrue(quickStart.contains("you may ask one next material decision"))
        XCTAssertTrue(continuation.contains("does not become canonical"))
        XCTAssertTrue(continuation.contains("one focused scene or passage"))
        XCTAssertTrue(continuation.contains("Do not close the chapter"))
        XCTAssertTrue(continuation.contains("Do not wrap the prose in Markdown code fences"))
        XCTAssertTrue(wholeChapter.contains("complete next chapter"))
        XCTAssertTrue(wholeChapter.contains("chapter-level arc"))
        XCTAssertTrue(wholeChapter.contains("Markdown H1 chapter heading"))
        XCTAssertTrue(wholeChapter.contains("Markdown code fences"))
        XCTAssertTrue(polish.contains("must not add, remove, reorder"))
        XCTAssertTrue(polish.contains("relationships, motivations, secrets, outcomes"))
        XCTAssertTrue(polish.contains(NovelPromptCatalog.polishCompletionSentinel))
        XCTAssertTrue(polish.contains("Markdown code fences"))
    }

    func testNormalizedCandidateProseStripsSpuriousOuterFences() {
        let fenced = """
        ```html
        # 第二章 灯火

        季遥在急诊室长椅上坐到凌晨两点。
        ```
        """
        XCTAssertEqual(
            NovelPromptCatalog.normalizedCandidateProse(fenced),
            """
            # 第二章 灯火

            季遥在急诊室长椅上坐到凌晨两点。
            """
        )

        let incomplete = """
        ```markdown
        季遥扫了眼账单数字。
        """
        XCTAssertEqual(
            NovelPromptCatalog.normalizedCandidateProse(incomplete),
            "季遥扫了眼账单数字。"
        )

        let plain = "季遥扫了眼账单数字。"
        XCTAssertEqual(NovelPromptCatalog.normalizedCandidateProse(plain), plain)
        XCTAssertEqual(NovelPromptCatalog.normalizedStreamingCandidateProse(plain), plain)
        XCTAssertEqual(
            NovelPromptCatalog.normalizedStreamingCandidateProse(incomplete),
            "季遥扫了眼账单数字。"
        )

        let sentinel = NovelPromptCatalog.polishCompletionSentinel
        let polishOutput = """
        ```html
        # 第二章 灯火

        正文
        ```
        \(sentinel)
        """
        XCTAssertEqual(
            NovelPromptCatalog.completedPolishContent(from: polishOutput),
            """
            # 第二章 灯火

            正文
            """
        )
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
            for: .discussion,
            version: "novel.discussion.v2"
        ))
        XCTAssertNotNil(NovelPromptCatalog.systemText(
            for: .quickStart,
            version: "novel.quick-start.v3"
        ))
        XCTAssertNotNil(NovelPromptCatalog.systemText(
            for: .quickStart,
            version: "novel.quick-start.v4"
        ))
        XCTAssertNotNil(NovelPromptCatalog.systemText(
            for: .discussion,
            version: "novel.discussion.v4"
        ))
        XCTAssertNotNil(NovelPromptCatalog.systemText(
            for: .discussion,
            version: "novel.discussion.v5"
        ))
        XCTAssertNotNil(NovelPromptCatalog.systemText(
            for: .discussion,
            version: "novel.discussion.v6"
        ))
        XCTAssertTrue(NovelPromptCatalog.acceptedVersions(for: .discussion).contains("novel.discussion.v6"))
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
        let archive = NovelPromptCatalog.template(for: .discussionArchiveV1).systemText
        let drift = NovelPromptCatalog.template(for: .polishDriftV1).systemText
        let continuity = NovelPromptCatalog.template(for: .continuityAuditV1).systemText

        for field in [
            "schemaVersion", "overview", "world", "characters", "masterOutline",
            "writingRequirements", "title", "content", "aliases"
        ] {
            XCTAssertTrue(
                quickStart.contains("\"\(field)\""),
                "Quick Start Prompt is missing \(field)"
            )
        }
        XCTAssertEqual(NovelPromptCatalog.template(for: .quickStart).version, "novel.quick-start.v5")
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
            "schemaVersion", "decisions", "topic", "decision", "relatedMaterialID", "summary"
        ] {
            XCTAssertTrue(archive.contains("\"\(field)\""), "Discussion archive Prompt is missing \(field)")
        }
        for field in [
            "schemaVersion", "compatible", "differences", "category", "sourceEvidence",
            "candidateEvidence"
        ] {
            XCTAssertTrue(drift.contains("\"\(field)\""), "Polish drift Prompt is missing \(field)")
        }
        for field in [
            "schemaVersion", "consistent", "issues", "category", "severity",
            "chapterOrdinal", "chapterTitle", "evidence"
        ] {
            XCTAssertTrue(
                continuity.contains("\"\(field)\""),
                "Continuity audit Prompt is missing \(field)"
            )
        }
        // 提示词里公布的类别白名单必须与解码器实际接受的枚举一致,否则模型按提示词
        // 写出来的类别会在 StrictJSON 那一层被整份拒收。
        for category in NovelContinuityIssueCategoryV1.allCases {
            XCTAssertTrue(
                continuity.contains(category.rawValue),
                "Continuity audit Prompt does not publish category \(category.rawValue)"
            )
        }
        for prompt in [state, rebuild, drift, continuity] {
            XCTAssertTrue(prompt.contains("Return exactly one raw JSON object"))
            XCTAssertTrue(prompt.contains("Do not use Markdown fences"))
            XCTAssertTrue(prompt.contains("Do not add unknown keys"))
        }
        XCTAssertTrue(archive.contains("Return exactly one raw JSON object"))
        XCTAssertTrue(archive.contains("no Markdown fence"))
        XCTAssertTrue(archive.contains("Do not add unknown keys"))
        XCTAssertTrue(state.contains("introduced|advanced|resolved|reopened"))
        XCTAssertTrue(state.contains("complete current branch"))
        XCTAssertTrue(state.contains("replacement branch outline"))
        XCTAssertTrue(rebuild.contains("introduced|advanced|resolved|reopened"))
        XCTAssertTrue(drift.contains("event|chronology|relationship|motivation|secret|outcome"))
        XCTAssertTrue(drift.contains("fail closed"))

        // The evidence field must be described as a literal, character-for-character
        // manuscript excerpt, not a loose paraphrase, in both fact-extraction contracts.
        XCTAssertTrue(
            state.contains("EXACT verbatim substring copied character-for-character"),
            "State delta Prompt does not demand verbatim evidence"
        )
        XCTAssertTrue(
            rebuild.contains("EXACT verbatim substring copied character-for-character"),
            "State rebuild Prompt does not demand verbatim evidence"
        )
        for prompt in [state, rebuild] {
            XCTAssertTrue(prompt.contains("Evidence integrity is mandatory"))
            XCTAssertTrue(prompt.contains("verbatim"))
            XCTAssertTrue(prompt.contains("Never paraphrase"))
            XCTAssertTrue(prompt.contains("omit that"))
            XCTAssertTrue(prompt.contains("Forbidden"))
            XCTAssertTrue(prompt.contains("discarded by the"))
        }

        XCTAssertNoThrow(try NovelStructuredOutputDecoder.decodeStateDelta(from: stateDeltaExample))
        XCTAssertNoThrow(try NovelStructuredOutputDecoder.decodeStateRebuild(from: stateRebuildExample))
        XCTAssertNoThrow(try NovelStructuredOutputDecoder.decodePolishDrift(from: polishDriftExample))
    }

    /// Ties the manualSyncV1 prompt's "verbatim substring" evidence requirement to
    /// NovelFactOutputValidation's actual evidence-matching behavior, so the prompt's
    /// promise and the validator's enforcement cannot silently drift apart.
    ///
    /// Contract update (2026-07): the judging criterion changed from "is the evidence
    /// character-for-character identical to the manuscript" to "is the evidence anchored
    /// in real manuscript text" (see `NovelFactOutputValidation.evidenceAnchorRange`).
    /// Verbatim-only matching was found to reject genuine LLM output for a reason that
    /// never actually protected against fabrication: model output is inherently a little
    /// lossy on exact transcription (whitespace, punctuation, minor rewording), and a
    /// literal-substring check was never a proof that the *fact* was true anyway — it
    /// only proved the *quoted fragment* existed verbatim somewhere in the manuscript.
    /// Demanding exact transcription was therefore all cost (legitimate facts silently
    /// dropped, or the whole batch hard-failing once other call sites layered a stricter
    /// re-check on top) for little anti-fabrication benefit. The new anchor rule keeps
    /// that benefit — evidence sharing only a few incidental characters (a name, a
    /// function word) with the manuscript is still rejected — while tolerating the
    /// paraphrase/rewording the prompt's "verbatim" wording cannot realistically prevent.
    ///
    /// This test therefore asserts the two-way split the new contract makes: (1) a
    /// reworded evidence string that still anchors on a long, high-coverage literal run of
    /// the manuscript must survive, and (2) a reworded evidence string that shares only
    /// a couple of incidental characters with the manuscript (i.e. is effectively
    /// fabricated) must still be discarded. The literal/verbatim case remains covered as
    /// the fast-path regression check.
    func testEvidenceContractAlignsWithVerbatimValidationBehavior() throws {
        let document = try NovelTestFixtures.document()
        let branch = document.branches[0]
        let baseState = try XCTUnwrap(document.stateSnapshots.first)
        let manuscript = "夜里下起了小雨，阿云站在屋檐下，看着雨水顺着瓦片一滴一滴地滑落。"
        let rebuild = NovelStateRebuildV1(
            schemaVersion: 1,
            stateSummary: "阿云在雨夜里等待。",
            branchOutline: "阿云在雨夜里等待。",
            events: [
                NovelStateEventV1(
                    id: "event-verbatim",
                    kind: "fact",
                    summary: "阿云站在屋檐下看雨。",
                    entityReferences: [],
                    evidence: "阿云站在屋檐下，看着雨水顺着瓦片一滴一滴地滑落。"
                ),
                NovelStateEventV1(
                    id: "event-paraphrase-anchored",
                    kind: "fact",
                    summary: "阿云看着雨心情渐渐平静。",
                    entityReferences: [],
                    // Reworded tail, but shares a 16-character verbatim run with the
                    // manuscript (well above the 8-character floor and the 40% coverage
                    // floor of this 26-character string) — must now survive.
                    evidence: "阿云站在屋檐下，看着雨水顺着瓦片，心里渐渐平静下来。"
                ),
                NovelStateEventV1(
                    id: "event-paraphrase-unanchored",
                    kind: "fact",
                    summary: "阿云转身走进屋内。",
                    entityReferences: [],
                    // Shares only the 2-character name "阿云" with the manuscript — no
                    // anchor of any meaningful length — must still be discarded.
                    evidence: "阿云转身走进屋内，心情十分平静。"
                ),
            ],
            characterStates: [],
            relationships: [],
            foreshadowing: [],
            unresolvedEntityNames: [],
            settingProposals: []
        )

        let validated = try NovelFactTransactionReducer.validateManualChunkOutput(
            rebuild,
            evidenceSource: manuscript,
            accumulated: nil,
            baseState: baseState,
            branchID: branch.id,
            in: document
        )

        XCTAssertEqual(
            validated.events.map(\.id),
            ["event-verbatim", "event-paraphrase-anchored"]
        )
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
          "schemaVersion": 3,
          "overview": "A memory mystery.",
          "world": {"title": "Rules", "content": "Memories can testify once."},
          "characters": [
            {"title": "Mara", "content": "An advocate forgets her past.", "aliases": ["The Advocate"]},
            {"title": "Ivo", "content": "A witness remembers the wrong trial.", "aliases": []}
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
