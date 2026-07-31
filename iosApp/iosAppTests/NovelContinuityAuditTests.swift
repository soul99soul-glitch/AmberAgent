import XCTest
@testable import iosApp

/// 剧情矛盾检查的端到端用例:全部经由 `NovelCreation.auditContinuity` 这个真实入口
/// 发起,而不是直接调 mapper 或 decoder。上一轮的教训是「数据层全绿、入口一次都没
/// 跑过」,所以这里刻意让脚本化模型适配器去接真实的结构化任务请求,并且 harness 用
/// `any NovelCreation` 存在类型持有实现——一旦有人把方法从协议体挪走导致派发回落到
/// 默认抛错实现,这些用例会立刻红。
final class NovelContinuityAuditTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_500_000)

    private let firstChapterContent = "林岸在渡口第一次见到苏未晚，两个人交换了姓名，约好第二天再见。"
    private let secondChapterContent = "夜里落了雨，林岸独自守在渡口的棚子下，把湿透的外衣拧了又拧。"
    private let thirdChapterContent = "林岸推开茶馆的门，苏未晚抬起头，两人都说这是初次见面，谁也不认得谁。"

    // MARK: - 端到端主路径

    func testAuditMapsModelFindingsToJumpableChapterReferences() async throws {
        let harness = try await makeHarness(scripts: [script(identityDriftJSON)])

        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertEqual(report.scannedChapterCount, 3)
        XCTAssertEqual(report.chunkCount, 1)
        XCTAssertEqual(report.droppedIssueCount, 0)
        XCTAssertEqual(report.failedChunkCount, 0)
        XCTAssertEqual(
            report.promptVersion,
            NovelPromptCatalog.template(for: .continuityAuditV1).version
        )

        let issue = try XCTUnwrap(report.issues.first)
        XCTAssertEqual(report.issues.count, 1)
        XCTAssertEqual(issue.category, .identityDrift)
        XCTAssertEqual(issue.severity, .major)
        XCTAssertEqual(issue.references.count, 2)

        // 界面靠 chapterID 跳章:模型只报第几章,映射错了就跳到别处。
        XCTAssertEqual(issue.references[0].chapterOrdinal, 1)
        XCTAssertEqual(issue.references[0].chapterID, harness.chapterIDs[0])
        XCTAssertEqual(issue.references[0].chapterTitle, "渡口")
        XCTAssertTrue(firstChapterContent.contains(issue.references[0].evidence))
        XCTAssertEqual(issue.references[1].chapterOrdinal, 3)
        XCTAssertEqual(issue.references[1].chapterID, harness.chapterIDs[2])
        XCTAssertEqual(issue.references[1].chapterTitle, "茶馆")
        XCTAssertTrue(thirdChapterContent.contains(issue.references[1].evidence))

        let branch = try await harness.branch()
        XCTAssertFalse(report.isStale(against: branch, discardedChapterIDs: []))

        // 单块扫描不带前序台账,也确认请求确实发到了模型这一层。
        let requests = await harness.adapter.requests
        XCTAssertEqual(requests.count, 1)
        let system = try XCTUnwrap(requests[0].messages.first { $0.role == .system }?.content)
        XCTAssertFalse(system.contains("ISSUES ALREADY REPORTED"))
        let user = try XCTUnwrap(requests[0].messages.first { $0.role == .user }?.content)
        XCTAssertTrue(user.contains("# Chapter 1: 渡口"))
        XCTAssertTrue(user.contains("# Chapter 3: 茶馆"))
    }

    func testAuditReportsNoIssuesWhenModelFindsTheManuscriptConsistent() async throws {
        let harness = try await makeHarness(scripts: [script(consistentJSON)])

        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertTrue(report.issues.isEmpty)
        XCTAssertEqual(report.droppedIssueCount, 0)
    }

    /// 模型有权引用块里的章节标头(提示词说的是「本块正文任一连续片段」),
    /// 而标头不在正文字符串里——证据源必须覆盖标头,否则引用标题的条目会被冤枉丢掉。
    func testAuditKeepsIssuesThatQuoteTheChapterHeading() async throws {
        let harness = try await makeHarness(scripts: [script(headingEvidenceJSON)])

        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertEqual(report.issues.count, 1)
        XCTAssertEqual(report.droppedIssueCount, 0)
    }

    // MARK: - 编造的落点必须被丢弃,而且不能静默

    func testAuditDropsIssuesWhoseEvidenceIsNotInTheManuscript() async throws {
        let harness = try await makeHarness(scripts: [script(fabricatedEvidenceJSON)])

        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertTrue(report.issues.isEmpty)
        XCTAssertEqual(report.droppedIssueCount, 1)
    }

    func testAuditDropsIssuesPointingAtAChapterThatIsNotInTheBook() async throws {
        let harness = try await makeHarness(scripts: [script(unknownChapterJSON)])

        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertTrue(report.issues.isEmpty)
        XCTAssertEqual(report.droppedIssueCount, 1)
    }

    /// 「至少两处落点」的立意是矛盾天然成对。把同一句话复制两遍就能伪造一条矛盾,
    /// 必须按「章号 + 证据」去重后仍有两处才算数。
    func testAuditDropsIssuesWhoseTwoReferencesAreIdentical() async throws {
        let harness = try await makeHarness(scripts: [script(duplicatedReferenceJSON)])

        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertTrue(report.issues.isEmpty)
        XCTAssertEqual(report.droppedIssueCount, 1)
    }

    /// 模型偶尔会把块内第一章当成「第 1 章」。章号对不上但标题和原文都对得上时,
    /// 按标题回退解析,而不是把一条真问题丢掉。
    func testAuditResolvesAChapterByTitleWhenTheOrdinalIsWrong() async throws {
        let harness = try await makeHarness(scripts: [script(wrongOrdinalRightTitleJSON)])

        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        let issue = try XCTUnwrap(report.issues.first)
        XCTAssertEqual(report.droppedIssueCount, 0)
        XCTAssertEqual(issue.references[1].chapterID, harness.chapterIDs[2])
        XCTAssertEqual(issue.references[1].chapterOrdinal, 3)
    }

    // MARK: - 多块扫描

    /// 跨块矛盾是长篇最需要的那一类。模型在后一块里引用前一块的章节时,落点必须
    /// 能映射回全书章节,而不是因为「不在本块」被整条丢弃、还被算进丢弃数误导用户。
    func testAuditKeepsCrossChunkIssuesThatCiteAnEarlierChunk() async throws {
        let harness = try await makeLongHarness(scripts: [
            script(consistentJSON),
            script(crossChunkIssueJSON),
            script(consistentJSON),
            script(consistentJSON),
        ])

        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertGreaterThan(report.chunkCount, 1)
        XCTAssertEqual(report.droppedIssueCount, 0)
        let issue = try XCTUnwrap(report.issues.first)
        XCTAssertEqual(issue.references.count, 2)
        XCTAssertEqual(issue.references[0].chapterID, harness.chapterIDs[0])
        XCTAssertEqual(issue.references[1].chapterID, harness.chapterIDs[2])
    }

    func testAuditCarriesPriorFindingsIntoLaterChunks() async throws {
        let harness = try await makeLongHarness(scripts: [
            script(longFirstChunkIssueJSON),
            script(consistentJSON),
            script(consistentJSON),
            script(consistentJSON),
        ])

        _ = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        let requests = await harness.adapter.requests
        XCTAssertGreaterThan(requests.count, 1)
        let firstSystem = try XCTUnwrap(requests[0].messages.first { $0.role == .system }?.content)
        XCTAssertFalse(firstSystem.contains("ISSUES ALREADY REPORTED"))
        let secondSystem = try XCTUnwrap(requests[1].messages.first { $0.role == .system }?.content)
        XCTAssertTrue(secondSystem.contains("ISSUES ALREADY REPORTED"))
        XCTAssertTrue(secondSystem.contains("烧信这件事"))
    }

    /// 一块失败不该把前面已经扫完(已经花过钱)的结果一起作废。
    func testAuditKeepsResultsFromSucceededChunksWhenOneChunkFails() async throws {
        let harness = try await makeLongHarness(scripts: [
            script(longFirstChunkIssueJSON),
            NovelModelScript(steps: [.fail(NovelModelFailure(
                code: "provider_error",
                message: "模型暂时不可用。",
                isRetryable: true
            ))]),
            script(consistentJSON),
            script(consistentJSON),
        ])

        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertEqual(report.issues.count, 1)
        XCTAssertEqual(report.failedChunkCount, 1)
    }

    func testAuditFailsWhenEveryChunkFails() async throws {
        let harness = try await makeHarness(scripts: [
            NovelModelScript(steps: [.fail(NovelModelFailure(
                code: "provider_error",
                message: "模型暂时不可用。",
                isRetryable: true
            ))]),
        ])

        await NovelXCTAssertThrowsErrorAsync(
            try await harness.creation.auditContinuity(
                projectID: harness.projectID,
                branchID: harness.branchID
            )
        )
    }

    func testPriorFindingsDigestIsTruncatedToItsTokenBudget() {
        let issues = (1...200).map { index in
            NovelContinuityIssue(
                id: "issue-\(index)",
                category: .contradiction,
                severity: .major,
                summary: String(repeating: "这是一条很长的问题摘要。", count: 6) + "编号\(index)",
                references: [
                    NovelContinuityReference(
                        chapterID: NovelChapterID(),
                        chapterOrdinal: 1,
                        chapterTitle: "渡口",
                        evidence: "证据"
                    ),
                    NovelContinuityReference(
                        chapterID: NovelChapterID(),
                        chapterOrdinal: 2,
                        chapterTitle: "雨夜",
                        evidence: "证据"
                    ),
                ]
            )
        }

        let digest = NovelContinuityAuditMapper.priorFindingsDigest(issues, maximumTokens: 512)

        XCTAssertLessThanOrEqual(NovelContinuityAuditPlanner.estimatedTokens(digest), 512)
        // 截断保留最近报出来的问题(后一块最可能与它们重复),并明说被略去的条数。
        XCTAssertTrue(digest.contains("编号200"))
        XCTAssertFalse(digest.contains("编号1。"))
        XCTAssertTrue(digest.contains("omitted"))
    }

    // MARK: - 章号口径与过期判断

    /// 报告里的「第 N 章」必须和正文页的章号是同一个口径,否则用户按章号去核对会
    /// 对到别的章。正文页用的是分支章节选择的原始位置(废弃章也占号)。
    func testAuditChapterOrdinalsMatchTheReaderWhenAChapterIsDiscarded() async throws {
        let harness = try await makeHarness(scripts: [script(discardedBookJSON)], discarding: 1)

        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertEqual(report.scannedChapterCount, 2)
        let requests = await harness.adapter.requests
        let user = try XCTUnwrap(requests[0].messages.first { $0.role == .user }?.content)
        XCTAssertTrue(user.contains("# Chapter 1: 渡口"))
        XCTAssertFalse(user.contains("# Chapter 2:"))
        XCTAssertTrue(user.contains("# Chapter 3: 茶馆"))

        let issue = try XCTUnwrap(report.issues.first)
        XCTAssertEqual(issue.references[1].chapterOrdinal, 3)
        XCTAssertEqual(issue.references[1].chapterID, harness.chapterIDs[2])
    }

    /// 丢弃/恢复章节不推进分支版本号,所以「结果是否过期」不能只看版本号,
    /// 必须比对扫过的那份章节清单本身。
    func testReportBecomesStaleAfterAChapterIsDiscarded() async throws {
        let harness = try await makeHarness(scripts: [script(consistentJSON)])
        let report = try await harness.creation.auditContinuity(
            projectID: harness.projectID,
            branchID: harness.branchID
        )
        let branch = try await harness.branch()
        XCTAssertFalse(report.isStale(against: branch, discardedChapterIDs: []))

        XCTAssertTrue(report.isStale(
            against: branch,
            discardedChapterIDs: [harness.chapterIDs[1]]
        ))
    }

    // MARK: - 发起前的预估与前置条件

    func testPlanReportsChapterAndChunkCounts() async throws {
        let harness = try await makeHarness(scripts: [])

        let plan = try await harness.creation.planContinuityAudit(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertEqual(plan.chapterCount, 3)
        XCTAssertEqual(plan.chunkCount, 1)
        XCTAssertEqual(
            plan.totalCharacterCount,
            firstChapterContent.count + secondChapterContent.count + thirdChapterContent.count
        )
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty, "预估不应该消耗一次模型调用")
    }

    func testAuditRefusesWhenTheBranchHasNoManuscript() async throws {
        let repository = InMemoryNovelProjectRepository()
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        _ = try await repository.createProject(document)
        let adapter = ScriptedNovelModelAdapter(resolvedModel: resolvedModel, scripts: [])
        let creation: any NovelCreation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter
        )

        do {
            _ = try await creation.auditContinuity(
                projectID: document.project.id,
                branchID: document.branches[0].id
            )
            XCTFail("没有正文时不应该发起扫描")
        } catch let error as NovelError {
            guard case .invalidInput = error else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
        }
    }

    /// 只有空白字符的章节等同于空章:让它占一个正文位只会浪费预算,
    /// 而且它的证据源为空,任何指向它的落点都必然被丢。
    func testAuditTreatsWhitespaceOnlyChaptersAsEmpty() async throws {
        let harness = try await makeHarness(
            scripts: [script(consistentJSON)],
            blankingChapterAt: 1
        )

        let plan = try await harness.creation.planContinuityAudit(
            projectID: harness.projectID,
            branchID: harness.branchID
        )

        XCTAssertEqual(plan.chapterCount, 2)
    }

    // MARK: - 分块

    func testPlannerPacksWholeChaptersAndNeverSplitsOne() throws {
        let chapters = (1...4).map {
            NovelContinuityAuditChapter(
                chapterID: NovelChapterID(),
                ordinal: $0,
                title: "第 \($0) 章",
                content: String(repeating: "字", count: 200)
            )
        }
        let perChapter = NovelContinuityAuditPlanner.estimatedTokens(chapters[0].manuscriptBlock)

        let chunks = try NovelContinuityAuditPlanner.chunks(
            chapters: chapters,
            maximumChunkTokens: perChapter * 2
        )

        XCTAssertEqual(chunks.map(\.index), [0, 1])
        XCTAssertEqual(chunks[0].chapters.map(\.ordinal), [1, 2])
        XCTAssertEqual(chunks[1].chapters.map(\.ordinal), [3, 4])
        // 分块只在章与章之间切,块内正文必须是整章原文。
        XCTAssertTrue(chunks[0].manuscript.contains("# Chapter 1: 第 1 章"))
        XCTAssertTrue(chunks[0].manuscript.contains("# Chapter 2: 第 2 章"))
    }

    func testPlannerRefusesASingleChapterLargerThanTheBudget() throws {
        let chapter = NovelContinuityAuditChapter(
            chapterID: NovelChapterID(),
            ordinal: 1,
            title: "长章",
            content: String(repeating: "字", count: 5_000)
        )

        XCTAssertThrowsError(
            try NovelContinuityAuditPlanner.chunks(chapters: [chapter], maximumChunkTokens: 100)
        ) { error in
            guard case .injectionBudgetExceeded = error as? NovelError else {
                return XCTFail("Expected injectionBudgetExceeded, got \(error)")
            }
        }
    }

    // MARK: - StrictJSON 严格校验(解码器不可被绕过)

    func testDecoderRejectsUnknownKeys() {
        XCTAssertThrowsError(try NovelStructuredOutputDecoder.decodeContinuityAudit(from: """
        {"schemaVersion": 1, "consistent": true, "issues": [], "confidence": 0.9}
        """))
    }

    func testDecoderRejectsDuplicateKeys() {
        XCTAssertThrowsError(try NovelStructuredOutputDecoder.decodeContinuityAudit(from: """
        {"schemaVersion": 1, "consistent": true, "issues": [], "consistent": false}
        """))
    }

    func testDecoderRejectsAnIssueWithASingleReference() {
        XCTAssertThrowsError(try NovelStructuredOutputDecoder.decodeContinuityAudit(from: """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-1",
            "category": "contradiction",
            "severity": "major",
            "summary": "只给了一处落点。",
            "references": [
              {"chapterOrdinal": 1, "chapterTitle": "渡口", "evidence": "两个人交换了姓名"}
            ]
          }]
        }
        """))
    }

    func testDecoderRejectsConsistentTrueWithIssues() {
        XCTAssertThrowsError(
            try NovelStructuredOutputDecoder.decodeContinuityAudit(from: identityDriftConsistentJSON)
        )
    }

    func testDecoderRejectsAnUnknownCategory() {
        XCTAssertThrowsError(try NovelStructuredOutputDecoder.decodeContinuityAudit(from: """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-1",
            "category": "vibes",
            "severity": "major",
            "summary": "类别不在白名单里。",
            "references": [
              {"chapterOrdinal": 1, "chapterTitle": "渡口", "evidence": "两个人交换了姓名"},
              {"chapterOrdinal": 3, "chapterTitle": "茶馆", "evidence": "两人都说这是初次见面"}
            ]
          }]
        }
        """))
    }

    func testDecoderAcceptsTheContractedShape() {
        XCTAssertNoThrow(
            try NovelStructuredOutputDecoder.decodeContinuityAudit(from: identityDriftJSON)
        )
    }

    // MARK: - 脚手架

    private struct Harness {
        let repository: any NovelProjectPersisting
        let adapter: ScriptedNovelModelAdapter
        let creation: any NovelCreation
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let chapterIDs: [NovelChapterID]

        func branch() async throws -> NovelBranchRecord {
            let document = try await repository.loadProject(id: projectID).document
            return document.branches[0]
        }
    }

    private var resolvedModel: NovelResolvedModel {
        NovelResolvedModel(
            providerID: "audit-provider",
            ownerProviderID: "audit-owner",
            modelID: "audit-model-id",
            wireModelID: "audit-model-wire",
            displayName: "Audit Model",
            contextWindowTokens: 128_000
        )
    }

    private var smallWindowModel: NovelResolvedModel {
        NovelResolvedModel(
            providerID: "audit-provider",
            ownerProviderID: "audit-owner",
            modelID: "audit-model-id",
            wireModelID: "audit-model-wire",
            displayName: "Audit Model",
            contextWindowTokens: 8_192
        )
    }

    private func makeHarness(
        scripts: [NovelModelScript],
        discarding discardedIndex: Int? = nil,
        blankingChapterAt blankIndex: Int? = nil
    ) async throws -> Harness {
        var fixture = try documentWithChapters([
            ("渡口", firstChapterContent),
            ("雨夜", blankIndex == 1 ? "   \n  " : secondChapterContent),
            ("茶馆", thirdChapterContent),
        ])
        if let discardedIndex {
            let chapterID = fixture.chapterIDs[discardedIndex]
            let index = try XCTUnwrap(fixture.document.chapters.firstIndex { $0.id == chapterID })
            fixture.document.chapters[index].discardedAt = now
            try NovelDocumentValidator.validate(fixture.document)
        }
        return try await makeHarness(
            fixture: fixture,
            scripts: scripts,
            model: resolvedModel
        )
    }

    /// 长章 + 小窗模型,用来逼出多块扫描。
    private func makeLongHarness(scripts: [NovelModelScript]) async throws -> Harness {
        let fixture = try documentWithChapters([
            ("渡口", longChapter(firstChapterContent)),
            ("雨夜", longChapter("苏未晚把那封信烧了，灰烬落进河里。")),
            ("茶馆", longChapter(thirdChapterContent)),
            ("清晨", longChapter("第二天清晨，渡船照常开走了。")),
        ])
        return try await makeHarness(
            fixture: fixture,
            scripts: scripts,
            model: smallWindowModel
        )
    }

    private func makeHarness(
        fixture: (document: NovelProjectDocumentV1, chapterIDs: [NovelChapterID]),
        scripts: [NovelModelScript],
        model: NovelResolvedModel
    ) async throws -> Harness {
        let repository = InMemoryNovelProjectRepository()
        _ = try await repository.createProject(fixture.document)
        let adapter = ScriptedNovelModelAdapter(resolvedModel: model, scripts: scripts)
        let creation: any NovelCreation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter,
            now: { Date(timeIntervalSince1970: 1_700_500_000) }
        )
        return Harness(
            repository: repository,
            adapter: adapter,
            creation: creation,
            projectID: fixture.document.project.id,
            branchID: fixture.document.branches[0].id,
            chapterIDs: fixture.chapterIDs
        )
    }

    private func longChapter(_ marker: String) -> String {
        marker + String(repeating: "夜色如水，河面上浮着薄雾。", count: 80)
    }

    private func documentWithChapters(
        _ chapters: [(title: String, content: String)]
    ) throws -> (document: NovelProjectDocumentV1, chapterIDs: [NovelChapterID]) {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let branch = document.branches[0]
        let operationID = document.appliedOperations[0].operationID
        var chapterIDs: [NovelChapterID] = []
        var selections: [NovelChapterSelection] = []
        for chapter in chapters {
            let chapterID = NovelChapterID()
            let versionID = NovelChapterVersionID()
            chapterIDs.append(chapterID)
            document.chapters.append(NovelChapterRecord(id: chapterID, createdAt: now))
            document.chapterVersions.append(NovelChapterVersionRecord(
                id: versionID,
                chapterID: chapterID,
                kind: .collected,
                title: chapter.title,
                content: chapter.content,
                factCompatibilityID: UUID(),
                sourceCandidateID: nil,
                createdAt: now,
                operationID: operationID
            ))
            selections.append(NovelChapterSelection(chapterID: chapterID, versionID: versionID))
        }
        let checkpointIndex = try XCTUnwrap(document.checkpoints.firstIndex {
            $0.id == branch.headCheckpointID
        })
        let checkpoint = document.checkpoints[checkpointIndex]
        document.checkpoints[checkpointIndex] = NovelBranchCheckpointRecord(
            id: checkpoint.id,
            kind: checkpoint.kind,
            createdOnBranchID: checkpoint.createdOnBranchID,
            parentCheckpointID: checkpoint.parentCheckpointID,
            chapterSelections: selections,
            stateSnapshotID: checkpoint.stateSnapshotID,
            sessionCursor: checkpoint.sessionCursor,
            branchOverrideRevisionIDs: checkpoint.branchOverrideRevisionIDs,
            sourceCandidateID: checkpoint.sourceCandidateID,
            baseHeadRevision: checkpoint.baseHeadRevision,
            operationID: checkpoint.operationID,
            createdAt: checkpoint.createdAt
        )
        document.branches[0].workingChapterSelections = selections
        try NovelDocumentValidator.validate(document)
        return (document, chapterIDs)
    }

    private func script(_ json: String) -> NovelModelScript {
        NovelModelScript(steps: [.delta(json), .complete])
    }

    // MARK: - 模型输出样本

    private var identityDriftJSON: String {
        """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-1",
            "category": "identityDrift",
            "severity": "major",
            "summary": "林岸与苏未晚在第一章已经认识，第三章却写成初次见面。",
            "references": [
              {
                "chapterOrdinal": 1,
                "chapterTitle": "渡口",
                "evidence": "林岸在渡口第一次见到苏未晚，两个人交换了姓名"
              },
              {
                "chapterOrdinal": 3,
                "chapterTitle": "茶馆",
                "evidence": "两人都说这是初次见面，谁也不认得谁"
              }
            ]
          }]
        }
        """
    }

    private var identityDriftConsistentJSON: String {
        identityDriftJSON.replacingOccurrences(
            of: "\"consistent\": false",
            with: "\"consistent\": true"
        )
    }

    private var consistentJSON: String {
        """
        {"schemaVersion": 1, "consistent": true, "issues": []}
        """
    }

    private var headingEvidenceJSON: String {
        """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-1",
            "category": "chronology",
            "severity": "minor",
            "summary": "章节标头本身也是可引用的原文。",
            "references": [
              {
                "chapterOrdinal": 1,
                "chapterTitle": "渡口",
                "evidence": "# Chapter 1: 渡口"
              },
              {
                "chapterOrdinal": 3,
                "chapterTitle": "茶馆",
                "evidence": "两人都说这是初次见面，谁也不认得谁"
              }
            ]
          }]
        }
        """
    }

    private var duplicatedReferenceJSON: String {
        """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-1",
            "category": "contradiction",
            "severity": "major",
            "summary": "同一句话被复制成两处落点。",
            "references": [
              {
                "chapterOrdinal": 1,
                "chapterTitle": "渡口",
                "evidence": "两个人交换了姓名，约好第二天再见"
              },
              {
                "chapterOrdinal": 1,
                "chapterTitle": "渡口",
                "evidence": "两个人交换了姓名，约好第二天再见"
              }
            ]
          }]
        }
        """
    }

    private var wrongOrdinalRightTitleJSON: String {
        """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-1",
            "category": "identityDrift",
            "severity": "major",
            "summary": "章号写错了，但标题和原文都对得上。",
            "references": [
              {
                "chapterOrdinal": 1,
                "chapterTitle": "渡口",
                "evidence": "林岸在渡口第一次见到苏未晚，两个人交换了姓名"
              },
              {
                "chapterOrdinal": 2,
                "chapterTitle": "茶馆",
                "evidence": "两人都说这是初次见面，谁也不认得谁"
              }
            ]
          }]
        }
        """
    }

    private var discardedBookJSON: String {
        """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-1",
            "category": "identityDrift",
            "severity": "major",
            "summary": "废弃章不参与扫描，但章号仍按分支原始位置计。",
            "references": [
              {
                "chapterOrdinal": 1,
                "chapterTitle": "渡口",
                "evidence": "林岸在渡口第一次见到苏未晚，两个人交换了姓名"
              },
              {
                "chapterOrdinal": 3,
                "chapterTitle": "茶馆",
                "evidence": "两人都说这是初次见面，谁也不认得谁"
              }
            ]
          }]
        }
        """
    }

    private var crossChunkIssueJSON: String {
        """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-cross",
            "category": "identityDrift",
            "severity": "major",
            "summary": "第一章已经认识，第三章却写成初次见面。",
            "references": [
              {
                "chapterOrdinal": 1,
                "chapterTitle": "渡口",
                "evidence": "林岸在渡口第一次见到苏未晚，两个人交换了姓名"
              },
              {
                "chapterOrdinal": 3,
                "chapterTitle": "茶馆",
                "evidence": "两人都说这是初次见面，谁也不认得谁"
              }
            ]
          }]
        }
        """
    }

    private var longFirstChunkIssueJSON: String {
        """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-first",
            "category": "duplicatedPlot",
            "severity": "minor",
            "summary": "烧信这件事写了两遍。",
            "references": [
              {
                "chapterOrdinal": 1,
                "chapterTitle": "渡口",
                "evidence": "林岸在渡口第一次见到苏未晚，两个人交换了姓名"
              },
              {
                "chapterOrdinal": 2,
                "chapterTitle": "雨夜",
                "evidence": "苏未晚把那封信烧了，灰烬落进河里"
              }
            ]
          }]
        }
        """
    }

    private var fabricatedEvidenceJSON: String {
        """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-1",
            "category": "contradiction",
            "severity": "blocking",
            "summary": "模型编造了一段正文里根本没有的话。",
            "references": [
              {
                "chapterOrdinal": 1,
                "chapterTitle": "渡口",
                "evidence": "林岸在渡口第一次见到苏未晚，两个人交换了姓名"
              },
              {
                "chapterOrdinal": 3,
                "chapterTitle": "茶馆",
                "evidence": "城墙外的骑兵吹响了号角，所有商队掉头返回北境。"
              }
            ]
          }]
        }
        """
    }

    private var unknownChapterJSON: String {
        """
        {
          "schemaVersion": 1,
          "consistent": false,
          "issues": [{
            "id": "issue-1",
            "category": "chronology",
            "severity": "minor",
            "summary": "落点指向了一个不存在的章节。",
            "references": [
              {
                "chapterOrdinal": 1,
                "chapterTitle": "渡口",
                "evidence": "林岸在渡口第一次见到苏未晚，两个人交换了姓名"
              },
              {
                "chapterOrdinal": 99,
                "chapterTitle": "不存在的一章",
                "evidence": "两人都说这是初次见面，谁也不认得谁"
              }
            ]
          }]
        }
        """
    }
}

/// 报告归当前选中的项目/分支所有。换项目、fork、删分支等路径不走 `selectProject`,
/// 靠「每个变更点手动清一次」必然漏,所以读取侧必须自己过滤。
@MainActor
final class NovelContinuityAuditViewModelTests: XCTestCase {
    func testCancellationWinsWhenContinuityRuntimeReturnsAfterIgnoringIt() async throws {
        let repository = InMemoryNovelProjectRepository()
        let fixture = try documentWithOneChapter()
        _ = try await repository.createProject(fixture)
        let runtime = CancellationIgnoringContinuityCreation(
            base: DefaultNovelCreation(repository: repository)
        )
        let viewModel = NovelCreationViewModel(creation: runtime)
        let didSelect = await viewModel.selectProject(fixture.project.id)
        XCTAssertTrue(didSelect)
        let branchID = try XCTUnwrap(viewModel.selectedBranchID)

        viewModel.startContinuityAuditPlanning()
        let planningStarted = await eventually { await runtime.hasStartedPlanning }
        XCTAssertTrue(planningStarted)
        viewModel.cancelContinuityAudit()
        await runtime.resumePlanning(NovelContinuityAuditPlan(
            projectID: fixture.project.id,
            branchID: branchID,
            chapterCount: 1,
            chunkCount: 1,
            totalCharacterCount: 20
        ))
        let planningStopped = await eventually { !viewModel.isContinuityOperationRunning }
        XCTAssertTrue(planningStopped)
        XCTAssertNil(viewModel.continuityAuditPlan)

        viewModel.startContinuityAudit()
        let auditStarted = await eventually { await runtime.hasStartedAudit }
        XCTAssertTrue(auditStarted)
        viewModel.cancelContinuityAudit()
        await runtime.resumeAudit(NovelContinuityAuditReport(
            projectID: fixture.project.id,
            branchID: branchID,
            auditedChapterSelections: viewModel.branchSnapshot?.chapterSelections ?? [],
            promptVersion: "test",
            scannedChapterCount: 1,
            chunkCount: 1,
            failedChunkCount: 0,
            issues: [],
            droppedIssueCount: 0,
            createdAt: Date()
        ))
        let auditStopped = await eventually { !viewModel.isContinuityOperationRunning }
        XCTAssertTrue(auditStopped)
        XCTAssertNil(viewModel.continuityAudit)
    }

    func testContinuityFailureIsHiddenAfterSelectionMovesToAnotherProject() async throws {
        let repository = InMemoryNovelProjectRepository()
        let first = try documentWithOneChapter()
        let second = try NovelTestFixtures.documentWithForkableCheckpoint()
        _ = try await repository.createProject(first)
        _ = try await repository.createProject(second)
        let runtime = CancellationIgnoringContinuityCreation(
            base: DefaultNovelCreation(repository: repository)
        )
        let viewModel = NovelCreationViewModel(creation: runtime)
        let didSelectFirst = await viewModel.selectProject(first.project.id)
        XCTAssertTrue(didSelectFirst)

        viewModel.startContinuityAuditPlanning()
        let planningStarted = await eventually { await runtime.hasStartedPlanning }
        XCTAssertTrue(planningStarted)
        await runtime.failPlanning()
        let failurePublished = await eventually { viewModel.continuityAuditFailure != nil }
        XCTAssertTrue(failurePublished)

        let didSelectSecond = await viewModel.selectProject(second.project.id)
        XCTAssertTrue(didSelectSecond)
        XCTAssertNil(viewModel.continuityAuditFailure)
    }

    func testPlanningTaskRemainsVisibleAndCanBeStoppedAfterItsViewDisappears() async throws {
        let repository = InMemoryNovelProjectRepository()
        let fixture = try documentWithOneChapter()
        _ = try await repository.createProject(fixture)
        let blocking = BlockingContinuityPlanningCreation(
            base: DefaultNovelCreation(repository: repository)
        )
        let viewModel = NovelCreationViewModel(creation: blocking)
        let didSelect = await viewModel.selectProject(fixture.project.id)
        XCTAssertTrue(didSelect)

        viewModel.startContinuityAuditPlanning()
        let started = await eventually {
            await blocking.hasStartedPlanning &&
                viewModel.isPlanningContinuity &&
                viewModel.isContinuityOperationRunning &&
                viewModel.isPerforming
        }
        XCTAssertTrue(started)

        viewModel.cancelContinuityAudit()

        let stopped = await eventually {
            !viewModel.isContinuityOperationRunning && !viewModel.isPerforming
        }
        XCTAssertTrue(stopped)
        XCTAssertNil(viewModel.continuityAuditPlan)
        XCTAssertNil(viewModel.continuityAuditFailure)
    }

    func testReportIsHiddenAfterTheSelectionMovesToAnotherProject() async throws {
        let repository = InMemoryNovelProjectRepository()
        let fixture = try documentWithOneChapter()
        _ = try await repository.createProject(fixture)
        let other = try NovelTestFixtures.documentWithForkableCheckpoint()
        _ = try await repository.createProject(other)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "audit-provider",
                ownerProviderID: "audit-owner",
                modelID: "audit-model-id",
                wireModelID: "audit-model-wire",
                displayName: "Audit Model",
                contextWindowTokens: 128_000
            ),
            scripts: [NovelModelScript(steps: [
                .delta("{\"schemaVersion\": 1, \"consistent\": true, \"issues\": []}"),
                .complete,
            ])]
        )
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository, modelRunner: adapter)
        )

        let didSelectAudited = await viewModel.selectProject(fixture.project.id)
        XCTAssertTrue(didSelectAudited)
        await viewModel.auditContinuity()
        XCTAssertNotNil(viewModel.continuityAudit)

        let didSelectOther = await viewModel.selectProject(other.project.id)
        XCTAssertTrue(didSelectOther)
        XCTAssertNil(
            viewModel.continuityAudit,
            "换项目之后不能再显示上一个项目的扫描结果"
        )
    }

    private func documentWithOneChapter() throws -> NovelProjectDocumentV1 {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let branch = document.branches[0]
        let operationID = document.appliedOperations[0].operationID
        let chapterID = NovelChapterID()
        let versionID = NovelChapterVersionID()
        let createdAt = Date(timeIntervalSince1970: 1_700_500_000)
        document.chapters.append(NovelChapterRecord(id: chapterID, createdAt: createdAt))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: versionID,
            chapterID: chapterID,
            kind: .collected,
            title: "渡口",
            content: "林岸在渡口第一次见到苏未晚，两个人交换了姓名。",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: createdAt,
            operationID: operationID
        ))
        let selection = NovelChapterSelection(chapterID: chapterID, versionID: versionID)
        let checkpointIndex = try XCTUnwrap(document.checkpoints.firstIndex {
            $0.id == branch.headCheckpointID
        })
        let checkpoint = document.checkpoints[checkpointIndex]
        document.checkpoints[checkpointIndex] = NovelBranchCheckpointRecord(
            id: checkpoint.id,
            kind: checkpoint.kind,
            createdOnBranchID: checkpoint.createdOnBranchID,
            parentCheckpointID: checkpoint.parentCheckpointID,
            chapterSelections: [selection],
            stateSnapshotID: checkpoint.stateSnapshotID,
            sessionCursor: checkpoint.sessionCursor,
            branchOverrideRevisionIDs: checkpoint.branchOverrideRevisionIDs,
            sourceCandidateID: checkpoint.sourceCandidateID,
            baseHeadRevision: checkpoint.baseHeadRevision,
            operationID: checkpoint.operationID,
            createdAt: checkpoint.createdAt
        )
        document.branches[0].workingChapterSelections = [selection]
        try NovelDocumentValidator.validate(document)
        return document
    }

    private func eventually(
        timeout: TimeInterval = 2,
        condition: @MainActor () async -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return await condition()
    }
}

private actor BlockingContinuityPlanningCreation: NovelCreation {
    private let base: any NovelCreation
    private(set) var hasStartedPlanning = false

    init(base: any NovelCreation) {
        self.base = base
    }

    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        try await base.snapshot(scope)
    }

    func perform(_ action: NovelAction) async throws -> NovelOutcome {
        try await base.perform(action)
    }

    func start(_ request: NovelRunRequest) async throws -> NovelRun {
        try await base.start(request)
    }

    func interruptForBackground(
        projectID: NovelProjectID,
        deadline: Date,
        runID: NovelRunID?
    ) async {
        await base.interruptForBackground(
            projectID: projectID,
            deadline: deadline,
            runID: runID
        )
    }

    func cancelInFlightBackgroundMutations(projectID: NovelProjectID) async {
        await base.cancelInFlightBackgroundMutations(projectID: projectID)
    }

    func retryPendingTerminal(runID: NovelRunID) async throws {
        try await base.retryPendingTerminal(runID: runID)
    }

    func planContinuityAudit(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelContinuityAuditPlan {
        hasStartedPlanning = true
        try await Task.sleep(for: .seconds(60))
        return try await base.planContinuityAudit(projectID: projectID, branchID: branchID)
    }
}

private actor CancellationIgnoringContinuityCreation: NovelCreation {
    private let base: any NovelCreation
    private var planningContinuation: CheckedContinuation<NovelContinuityAuditPlan, Error>?
    private var auditContinuation: CheckedContinuation<NovelContinuityAuditReport, Error>?
    private(set) var hasStartedPlanning = false
    private(set) var hasStartedAudit = false

    init(base: any NovelCreation) {
        self.base = base
    }

    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        try await base.snapshot(scope)
    }

    func perform(_ action: NovelAction) async throws -> NovelOutcome {
        try await base.perform(action)
    }

    func start(_ request: NovelRunRequest) async throws -> NovelRun {
        try await base.start(request)
    }

    func interruptForBackground(
        projectID: NovelProjectID,
        deadline: Date,
        runID: NovelRunID?
    ) async {
        await base.interruptForBackground(
            projectID: projectID,
            deadline: deadline,
            runID: runID
        )
    }

    func cancelInFlightBackgroundMutations(projectID: NovelProjectID) async {
        await base.cancelInFlightBackgroundMutations(projectID: projectID)
    }

    func retryPendingTerminal(runID: NovelRunID) async throws {
        try await base.retryPendingTerminal(runID: runID)
    }

    func planContinuityAudit(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelContinuityAuditPlan {
        hasStartedPlanning = true
        return try await withCheckedThrowingContinuation { continuation in
            planningContinuation = continuation
        }
    }

    func auditContinuity(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelContinuityAuditReport {
        hasStartedAudit = true
        return try await withCheckedThrowingContinuation { continuation in
            auditContinuation = continuation
        }
    }

    func resumePlanning(_ plan: NovelContinuityAuditPlan) {
        planningContinuation?.resume(returning: plan)
        planningContinuation = nil
    }

    func failPlanning() {
        planningContinuation?.resume(
            throwing: NovelError.repositoryFailure("Injected continuity planning failure.")
        )
        planningContinuation = nil
    }

    func resumeAudit(_ report: NovelContinuityAuditReport) {
        auditContinuation?.resume(returning: report)
        auditContinuation = nil
    }
}
