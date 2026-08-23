import Foundation
import XCTest
@preconcurrency import Shared
@testable import iosApp

/// 小说讨论会话内项目字段写工具（IOSNovelProjectToolExecutor）的行为契约：
/// happy path 落盘、非法参数/超限拒绝、代笔中守卫，以及声明进入
/// makeParameters 组装点的端到端契约（S1 教训：声明不进组装点 = 生产不可达）。
final class IOSNovelProjectToolExecutorTests: XCTestCase {
    private struct Harness {
        let root: URL
        let creation: DefaultNovelCreation
        let projectID: NovelProjectID
        let branchID: NovelBranchID
        let executor: IOSNovelProjectToolExecutor

        func snapshot() async throws -> NovelProjectSnapshot {
            guard case .project(let snapshot) = try await creation.snapshot(.project(projectID)) else {
                throw NovelError.invalidInput("Expected a project snapshot.")
            }
            return snapshot
        }

        func execute(_ name: String, _ arguments: String) async -> IOSAgentToolOutcome {
            await executor.execute(name: name, arguments: arguments, isUserInitiated: false)
        }
    }

    private func makeHarness() async throws -> Harness {
        let root = try NovelTestFixtures.temporaryDirectory()
        let creation = DefaultNovelCreation(
            repository: NovelFileProjectRepository(rootDirectory: root)
        )
        let projectID = NovelProjectID()
        let branchID = NovelBranchID()
        _ = try await creation.perform(.createProject(NovelTestFixtures.createCommand(
            projectID: projectID,
            branchID: branchID
        )))
        let executor = IOSNovelProjectToolExecutor(
            projectContext: NovelProjectToolRunContext(projectID: projectID, branchID: branchID),
            creation: creation
        )
        return Harness(
            root: root,
            creation: creation,
            projectID: projectID,
            branchID: branchID,
            executor: executor
        )
    }

    /// Seed one working chapter via repository create of a validated document.
    private func makeHarnessWithChapter(
        title: String = "旧标题",
        content: String = "第一章正文。"
    ) async throws -> (Harness, NovelChapterID) {
        let (harness, ids) = try await makeHarnessWithChapters([(title, content)])
        return (harness, ids[0])
    }

    private func makeHarnessWithChapters(
        _ chapters: [(title: String, content: String)]
    ) async throws -> (Harness, [NovelChapterID]) {
        let root = try NovelTestFixtures.temporaryDirectory()
        let repository = NovelFileProjectRepository(rootDirectory: root)
        var document = try NovelTestFixtures.document()
        let projectID = document.project.id
        let branchID = document.branches[0].id
        let now = document.project.updatedAt
        var ids: [NovelChapterID] = []
        var selections: [NovelChapterSelection] = []
        for chapter in chapters {
            let chapterID = NovelChapterID()
            let versionID = NovelChapterVersionID()
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
                operationID: document.appliedOperations[0].operationID
            ))
            ids.append(chapterID)
            selections.append(NovelChapterSelection(chapterID: chapterID, versionID: versionID))
        }
        // Only attach manuscript to existing head checkpoint. Do not bump
        // workingRevision without a matching applied operation (timeline check).
        document.branches[0].workingChapterSelections = selections
        if let idx = document.checkpoints.firstIndex(where: {
            $0.id == document.branches[0].headCheckpointID
        }) {
            let checkpoint = document.checkpoints[idx]
            document.checkpoints[idx] = NovelBranchCheckpointRecord(
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
        }
        try NovelDocumentValidator.validate(document)
        _ = try await repository.createProject(document)
        let creation = DefaultNovelCreation(repository: repository)
        let executor = IOSNovelProjectToolExecutor(
            projectContext: NovelProjectToolRunContext(projectID: projectID, branchID: branchID),
            creation: creation
        )
        let harness = Harness(
            root: root,
            creation: creation,
            projectID: projectID,
            branchID: branchID,
            executor: executor
        )
        return (harness, ids)
    }

    private func makeHarnessWithSettingProposals(
        _ titles: [String]
    ) async throws -> (Harness, [NovelProposalID]) {
        let root = try NovelTestFixtures.temporaryDirectory()
        let repository = NovelFileProjectRepository(rootDirectory: root)
        var document = try NovelTestFixtures.document()
        let now = document.project.updatedAt
        var ids: [NovelProposalID] = []
        for title in titles {
            let proposal = NovelSettingProposalRecord(
                id: NovelProposalID(),
                branchID: document.branches[0].id,
                title: title,
                content: "\(title) 的说明。",
                createdAt: now,
                isResolved: false
            )
            document.settingProposals.append(proposal)
            ids.append(proposal.id)
        }
        let source = document.stateSnapshots[0]
        document.stateSnapshots[0] = NovelStateSnapshotRecord(
            id: source.id,
            eventIDs: source.eventIDs,
            summary: source.summary,
            branchOutline: source.branchOutline,
            unresolvedEntityNames: source.unresolvedEntityNames,
            createdAt: source.createdAt,
            settingProposalIDs: ids
        )
        try NovelDocumentValidator.validate(document)
        _ = try await repository.createProject(document)
        let creation = DefaultNovelCreation(repository: repository)
        let executor = IOSNovelProjectToolExecutor(
            projectContext: NovelProjectToolRunContext(
                projectID: document.project.id,
                branchID: document.branches[0].id
            ),
            creation: creation
        )
        let harness = Harness(
            root: root,
            creation: creation,
            projectID: document.project.id,
            branchID: document.branches[0].id,
            executor: executor
        )
        return (harness, ids)
    }

    /// Real collection lineage so undo can walk one checkpoint per chapter.
    private func makeHarnessWithCollectedLineage(
        additionalChapters: [(title: String, content: String)]
    ) async throws -> (Harness, NovelStateSnapshotID) {
        let root = try NovelTestFixtures.temporaryDirectory()
        let repository = NovelFileProjectRepository(rootDirectory: root)
        var document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "第一章：留下的正文。"
        )
        let firstSnapshotID = document.branches[0].currentStateSnapshotID
        let branchID = document.branches[0].id
        for chapter in additionalChapters {
            let generated = try NovelBranchTestFixtures.appendCompletedRun(
                to: document,
                branchID: branchID,
                kind: .prose,
                content: chapter.content
            )
            let candidateID = try XCTUnwrap(generated.candidateID)
            document = try NovelBranchTestFixtures.collectCandidate(
                candidateID,
                in: generated.document,
                title: chapter.title
            )
        }
        try NovelDocumentValidator.validate(document)
        _ = try await repository.createProject(document)
        let creation = DefaultNovelCreation(repository: repository)
        let executor = IOSNovelProjectToolExecutor(
            projectContext: NovelProjectToolRunContext(
                projectID: document.project.id,
                branchID: branchID
            ),
            creation: creation
        )
        let harness = Harness(
            root: root,
            creation: creation,
            projectID: document.project.id,
            branchID: branchID,
            executor: executor
        )
        return (harness, firstSnapshotID)
    }

    private func jsonArgs(_ object: [String: Any]) -> String {
        let data = try! JSONSerialization.data(withJSONObject: object)
        return String(data: data, encoding: .utf8)!
    }

    // MARK: - Happy paths

    func testRenameProjectWritesDocumentAndRecordsOperation() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute(
            "novel_rename_project",
            jsonArgs(["title": "新书名", "reason": "用户要求的改名"])
        )
        guard case .filled(let receipt) = outcome else {
            XCTFail("Expected filled, got \(outcome)")
            return
        }
        XCTAssertTrue(receipt.contains("新书名"))

        let snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.project.name, "新书名")
        XCTAssertTrue(snapshot.appliedOperations.contains { $0.kind == .renameProject })
    }

    func testSetPolishPreferenceWritesAndEmptyStringClears() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let set = await harness.execute(
            "novel_set_polish_preference",
            jsonArgs(["preference": "多用短句，少用长定语"])
        )
        guard case .filled = set else {
            XCTFail("Expected filled, got \(set)")
            return
        }
        var snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.project.polishPreference, "多用短句，少用长定语")
        XCTAssertTrue(snapshot.appliedOperations.contains { $0.kind == .setPolishPreference })

        let clear = await harness.execute(
            "novel_set_polish_preference",
            jsonArgs(["preference": ""])
        )
        guard case .filled(let receipt) = clear else {
            XCTFail("Expected filled, got \(clear)")
            return
        }
        XCTAssertTrue(receipt.contains("清除"))
        snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.project.polishPreference, "")
    }

    func testUpsertUpcomingArcWritesBeatsAndClearRemovesThem() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let upsert = await harness.execute(
            "novel_upsert_upcoming_arc",
            jsonArgs(["beats": ["第一次冲突爆发", "主角被迫离乡"]])
        )
        guard case .filled = upsert else {
            XCTFail("Expected filled, got \(upsert)")
            return
        }
        var snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.upcomingArc(for: harness.branchID)?.beats, ["第一次冲突爆发", "主角被迫离乡"])
        XCTAssertTrue(snapshot.appliedOperations.contains { $0.kind == .upsertUpcomingArc })

        let clear = await harness.execute("novel_clear_upcoming_arc", "{}")
        guard case .filled = clear else {
            XCTFail("Expected filled, got \(clear)")
            return
        }
        snapshot = try await harness.snapshot()
        XCTAssertNil(snapshot.upcomingArc(for: harness.branchID))
        XCTAssertTrue(snapshot.appliedOperations.contains { $0.kind == .clearUpcomingArc })
    }

    func testReviseMaterialCreatesThenUpdatesSameMaterial() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let create = await harness.execute(
            "novel_revise_material",
            jsonArgs(["kind": "world", "title": "魔法规则", "content": "魔法有代价。", "aliases": ["规则"]])
        )
        guard case .filled(let createReceipt) = create else {
            XCTFail("Expected filled, got \(create)")
            return
        }
        XCTAssertTrue(createReceipt.contains("新建"))

        var snapshot = try await harness.snapshot()
        let material = try XCTUnwrap(snapshot.materials.first(where: { $0.kind == .world }))
        XCTAssertEqual(snapshot.materialRevisions.first(where: {
            $0.id == material.currentRevisionID
        })?.title, "魔法规则")

        let update = await harness.execute(
            "novel_revise_material",
            jsonArgs([
                "material_id": material.id.description,
                "kind": "world",
                "title": "魔法规则（修订）",
                "content": "魔法有代价，且代价必须当场支付。",
            ])
        )
        guard case .filled(let updateReceipt) = update else {
            XCTFail("Expected filled, got \(update)")
            return
        }
        XCTAssertTrue(updateReceipt.contains("魔法规则"))
        XCTAssertTrue(updateReceipt.contains("修订"))

        snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.materials.filter { $0.id == material.id }.count, 1)
        XCTAssertEqual(
            snapshot.materialRevisions.filter { $0.materialID == material.id }.count,
            2
        )
        let updatedMaterial = try XCTUnwrap(snapshot.materials.first(where: { $0.id == material.id }))
        XCTAssertEqual(snapshot.materialRevisions.first(where: {
            $0.id == updatedMaterial.currentRevisionID
        })?.title, "魔法规则（修订）")
        XCTAssertTrue(snapshot.appliedOperations.contains { $0.kind == .reviseMaterial })
    }

    func testSetChapterTitleRenamesLatestWorkingChapterWithoutChangingBody() async throws {
        let (harness, chapterID) = try await makeHarnessWithChapter()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let empty = await harness.execute("novel_set_chapter_title", jsonArgs(["title": ""]))
        guard case .failed = empty else {
            XCTFail("空标题应失败，实际 \(empty)")
            return
        }

        let outcome = await harness.execute(
            "novel_set_chapter_title",
            jsonArgs(["title": "同行"])
        )
        guard case .filled(let receipt) = outcome else {
            XCTFail("Expected filled, got \(outcome)")
            return
        }
        XCTAssertTrue(receipt.contains("旧标题"))
        XCTAssertTrue(receipt.contains("同行"))

        let snapshot = try await harness.snapshot()
        let selection = try XCTUnwrap(snapshot.branches.first?.workingChapterSelections.first)
        XCTAssertEqual(selection.chapterID, chapterID)
        let version = try XCTUnwrap(snapshot.chapterVersions.first(where: {
            $0.id == selection.versionID
        }))
        XCTAssertEqual(version.title, "同行")
        XCTAssertEqual(version.content, "第一章正文。")
        XCTAssertTrue(snapshot.appliedOperations.contains { $0.kind == .saveManualEdit })
        XCTAssertEqual(snapshot.branches.first?.syncStatus, .needsSync)
    }

    func testSetChapterTitleByOrdinalAndRejectsMissingManuscript() async throws {
        let emptyHarness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: emptyHarness.root) }
        let missing = await emptyHarness.execute(
            "novel_set_chapter_title",
            jsonArgs(["title": "新名"])
        )
        guard case .failed(let reason) = missing else {
            XCTFail("无正文时应失败，实际 \(missing)")
            return
        }
        XCTAssertTrue(reason.contains("还没有收录正文"))

        let (harness, _) = try await makeHarnessWithChapter(title: "甲", content: "A")
        defer { try? FileManager.default.removeItem(at: harness.root) }
        let byOrdinal = await harness.execute(
            "novel_set_chapter_title",
            jsonArgs(["title": "乙", "chapter_ordinal": 1])
        )
        guard case .filled = byOrdinal else {
            XCTFail("Expected filled for ordinal 1, got \(byOrdinal)")
            return
        }
        let snapshot = try await harness.snapshot()
        let versionID = try XCTUnwrap(snapshot.branches.first?.workingChapterSelections.first?.versionID)
        XCTAssertEqual(snapshot.chapterVersions.first(where: { $0.id == versionID })?.title, "乙")
    }

    func testListAndReadChapterReturnNumberedWorkingManuscript() async throws {
        let (harness, chapterIDs) = try await makeHarnessWithChapters([
            ("旧章", "第一段。\n\n第二段。"),
            ("新章", "后章第一段。\n\n后章第二段。\n\n后章第三段。"),
        ])
        let firstID = chapterIDs[0]
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let listed = await harness.execute("novel_list_chapters", "{}")
        guard case .filled(let listText) = listed else {
            XCTFail("列章应成功，实际 \(listed)")
            return
        }
        XCTAssertTrue(listText.contains("工作章节 2 章"))
        XCTAssertTrue(listText.contains("《旧章》"))
        XCTAssertTrue(listText.contains("《新章》"))
        XCTAssertTrue(listText.contains("3 段"))

        let latest = await harness.execute("novel_read_chapter", "{}")
        guard case .filled(let latestText) = latest else {
            XCTFail("读最新章应成功，实际 \(latest)")
            return
        }
        XCTAssertTrue(latestText.contains("第 2 章 《新章》"))
        XCTAssertTrue(latestText.contains("[1] 后章第一段。"))
        XCTAssertTrue(latestText.contains("[3] 后章第三段。"))

        let ranged = await harness.execute(
            "novel_read_chapter",
            jsonArgs([
                "chapter_id": firstID.description,
                "start_paragraph": 2,
                "end_paragraph": 2,
            ])
        )
        guard case .filled(let rangedText) = ranged else {
            XCTFail("按 id 读段应成功，实际 \(ranged)")
            return
        }
        XCTAssertTrue(rangedText.contains("第 1 章 《旧章》"))
        XCTAssertTrue(rangedText.contains("[2] 第二段。"))
        XCTAssertFalse(rangedText.contains("[1] 第一段。"))
    }

    func testReviseChapterPausesForApprovalThenWritesOnUserInitiation() async throws {
        let (harness, chapterID) = try await makeHarnessWithChapter(
            title: "旧标题",
            content: "第一段。\n\n第二段有矛盾。\n\n第三段。"
        )
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let arguments = jsonArgs([
            "start_paragraph": 2,
            "end_paragraph": 2,
            "new_text": "第二段已经改掉了那个矛盾。",
            "reason": "事实自相矛盾",
        ])
        let paused = await harness.execute("novel_revise_chapter", arguments)
        guard case .needsApproval(let reason) = paused else {
            XCTFail("模型发起改正文应停在审批卡，实际 \(paused)")
            return
        }
        XCTAssertTrue(reason.contains("确认"))

        var snapshot = try await harness.snapshot()
        var versionID = try XCTUnwrap(snapshot.branches.first?.workingChapterSelections.first?.versionID)
        XCTAssertEqual(
            snapshot.chapterVersions.first(where: { $0.id == versionID })?.content,
            "第一段。\n\n第二段有矛盾。\n\n第三段。",
            "审批前不得改写正文"
        )
        XCTAssertFalse(snapshot.appliedOperations.contains { $0.kind == .saveManualEdit })

        let prompt = try await harness.executor.revisionApprovalPrompt(from: arguments).get()
        XCTAssertEqual(prompt.options, NovelChapterRevisionApproval.options)
        XCTAssertEqual(prompt.chapterRevision?.chapterID, chapterID)
        XCTAssertEqual(prompt.chapterRevision?.oldText, "第二段有矛盾。")
        XCTAssertEqual(prompt.chapterRevision?.newText, "第二段已经改掉了那个矛盾。")

        let written = await harness.executor.execute(
            name: "novel_revise_chapter",
            arguments: arguments,
            isUserInitiated: true
        )
        guard case .filled(let receipt) = written else {
            XCTFail("作者确认后应写入正文，实际 \(written)")
            return
        }
        XCTAssertTrue(receipt.contains("已写入"))
        XCTAssertTrue(receipt.contains("第 2 段"))

        snapshot = try await harness.snapshot()
        versionID = try XCTUnwrap(snapshot.branches.first?.workingChapterSelections.first?.versionID)
        XCTAssertEqual(
            snapshot.chapterVersions.first(where: { $0.id == versionID })?.content,
            "第一段。\n\n第二段已经改掉了那个矛盾。\n\n第三段。"
        )
        XCTAssertTrue(snapshot.appliedOperations.contains { $0.kind == .saveManualEdit })
        XCTAssertEqual(snapshot.branches.first?.syncStatus, .needsSync)
    }

    func testReviseChapterRejectsInvalidRangeWithoutWriting() async throws {
        let (harness, _) = try await makeHarnessWithChapter(content: "只有一段。")
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute(
            "novel_revise_chapter",
            jsonArgs([
                "start_paragraph": 2,
                "end_paragraph": 3,
                "new_text": "不该写进去",
            ])
        )
        guard case .failed(let message) = outcome else {
            XCTFail("越界改正文应失败，实际 \(outcome)")
            return
        }
        XCTAssertTrue(message.contains("out of bounds") || message.contains("段落"))
        let snapshot = try await harness.snapshot()
        XCTAssertFalse(snapshot.appliedOperations.contains { $0.kind == .saveManualEdit })
    }

    func testRevertRecentChaptersPausesForApprovalThenRollsBackOnConfirm() async throws {
        let (harness, firstSnapshotID) = try await makeHarnessWithCollectedLineage(
            additionalChapters: [
                (title: "口中名", content: "第二章：城里有人叫他的名字。"),
                (title: "山呼", content: "第三章：山里有人应声。"),
            ]
        )
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let arguments = jsonArgs([
            "chapter_count": 2,
            "reason": "最近两章比较水",
        ])
        let paused = await harness.execute("novel_revert_recent_chapters", arguments)
        guard case .needsApproval(let reason) = paused else {
            XCTFail("模型发起回退应停在审批卡，实际 \(paused)")
            return
        }
        XCTAssertTrue(reason.contains("确认"))

        var snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.branches.first?.workingChapterSelections.count, 3)
        XCTAssertFalse(snapshot.appliedOperations.contains { $0.kind == .undoBranchHead })

        let prompt = try await harness.executor.revertApprovalPrompt(from: arguments).get()
        XCTAssertEqual(prompt.options, NovelManuscriptRevertApproval.options)
        XCTAssertEqual(prompt.manuscriptRevert?.chapterTitles, ["口中名", "山呼"])
        XCTAssertEqual(prompt.manuscriptRevert?.chapterCount, 2)

        let written = await harness.executor.execute(
            name: "novel_revert_recent_chapters",
            arguments: arguments,
            isUserInitiated: true
        )
        guard case .filled(let receipt) = written else {
            XCTFail("作者确认后应回退章节，实际 \(written)")
            return
        }
        XCTAssertTrue(receipt.contains("口中名"))
        XCTAssertTrue(receipt.contains("山呼"))

        snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.branches.first?.workingChapterSelections.count, 1)
        XCTAssertEqual(snapshot.branches.first?.currentStateSnapshotID, firstSnapshotID)
        XCTAssertEqual(
            snapshot.appliedOperations.filter { $0.kind == .undoBranchHead }.count,
            2
        )
    }

    func testRevertRecentChaptersRejectsInvalidCountWithoutWriting() async throws {
        let (harness, _) = try await makeHarnessWithCollectedLineage(additionalChapters: [])
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute(
            "novel_revert_recent_chapters",
            jsonArgs(["chapter_count": 3])
        )
        guard case .failed(let message) = outcome else {
            XCTFail("章数不够应失败，实际 \(outcome)")
            return
        }
        XCTAssertTrue(message.contains("没有这么多章") || message.contains("章"))
        let snapshot = try await harness.snapshot()
        XCTAssertFalse(snapshot.appliedOperations.contains { $0.kind == .undoBranchHead })
    }

    func testGhostwriteBlocksRecentChapterRevert() async throws {
        let (harness, _) = try await makeHarnessWithCollectedLineage(
            additionalChapters: [(title: "山呼", content: "第二章。")]
        )
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let startedAt = Date(timeIntervalSince1970: 1_700_000_000)
        try await harness.creation.saveGhostwriteBatchProgress(NovelGhostwriteBatchProgressRecord(
            schemaVersion: NovelGhostwriteBatchProgressRecord.currentSchemaVersion,
            projectID: harness.projectID,
            branchID: harness.branchID,
            phase: .writing,
            pauseReason: nil,
            detailMessage: nil,
            candidateID: nil,
            chapterPlanDigest: nil,
            autoCollectedCandidateIDs: [],
            startedAt: startedAt,
            updatedAt: startedAt,
            targetChapterCount: 1,
            completedChapterCount: 0,
            currentChapterIndex: 1,
            lastCompletedPlanSummary: nil,
            pendingSyncChapterCredit: false,
            qualityAttemptIndex: 0,
            maxQualityAttempts: 3,
            lastFailureReceipt: nil,
            supersededCandidateIDs: [],
            recentFailureFingerprints: [],
            revisionBriefOverride: nil,
            didThinContractAmendThisChapter: false,
            contractAmendments: []
        ))

        let outcome = await harness.execute(
            "novel_revert_recent_chapters",
            jsonArgs(["chapter_count": 1])
        )
        guard case .failed(let message) = outcome else {
            XCTFail("代笔中回退应被拒绝，实际 \(outcome)")
            return
        }
        XCTAssertTrue(message.contains("代笔正在推进本章"))
        let snapshot = try await harness.snapshot()
        XCTAssertFalse(snapshot.appliedOperations.contains { $0.kind == .undoBranchHead })
    }

    func testProposeChapterPlanSavesDraftAndReusesExistingPlanID() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let arguments = jsonArgs([
            "outline_placement": "第 1 章 · 结尾转折",
            "goal_and_conflict": "主角必须决定是否离开小镇",
            "must_happen": ["主角做出决定"],
            "must_not_happen": ["主角直接离开"],
            "ending_hook": "门外传来敲门声",
            "visible_facts": ["主角怕水"],
        ])
        let first = await harness.execute("novel_propose_chapter_plan", arguments)
        guard case .filled(let firstReceipt) = first else {
            XCTFail("Expected filled, got \(first)")
            return
        }
        XCTAssertTrue(firstReceipt.contains("草稿"))

        var snapshot = try await harness.snapshot()
        let plan = try XCTUnwrap(snapshot.chapterPlan(for: harness.branchID))
        XCTAssertEqual(plan.status, .draft, "永远存为草稿，绝不直接 confirmed")
        XCTAssertEqual(plan.goalAndConflict, "主角必须决定是否离开小镇")
        XCTAssertEqual(plan.mustHappen, ["主角做出决定"])
        XCTAssertEqual(plan.outlinePlacement, "第 1 章 · 结尾转折")
        XCTAssertTrue(snapshot.appliedOperations.contains { $0.kind == .upsertChapterPlan })

        // 第二次拟定复用同一 planID，reducer 不允许不同 ID 覆盖。
        let second = await harness.execute("novel_propose_chapter_plan", arguments)
        guard case .filled = second else {
            XCTFail("Expected filled, got \(second)")
            return
        }
        snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.chapterPlan(for: harness.branchID)?.id, plan.id)
        XCTAssertEqual(snapshot.chapterPlan(for: harness.branchID)?.status, .draft)
    }

    // MARK: - Invalid arguments

    func testInvalidArgumentsFailWithExplanationAndLeaveDocumentUntouched() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let cases: [(String, String, String)] = [
            ("novel_rename_project", "{}", "title"),
            ("novel_rename_project", jsonArgs(["title": "   "]), "不能为空"),
            ("novel_set_polish_preference", "{}", "preference"),
            ("novel_upsert_upcoming_arc", "{}", "beats"),
            ("novel_upsert_upcoming_arc", jsonArgs(["beats": []]), "至少需要一条"),
            ("novel_revise_material", jsonArgs(["title": "t", "content": "c"]), "kind"),
            ("novel_revise_material", jsonArgs(["kind": "galaxy", "title": "t", "content": "c"]), "kind 非法"),
            ("novel_revise_material", jsonArgs(["kind": "world", "title": "", "content": "c"]), "title"),
            ("novel_propose_chapter_plan", jsonArgs([
                "outline_placement": "x", "must_happen": [], "must_not_happen": [],
                "ending_hook": "", "visible_facts": [],
            ]), "goal_and_conflict"),
            ("novel_propose_chapter_plan", jsonArgs([
                "outline_placement": "x", "goal_and_conflict": "   ", "must_happen": [],
                "must_not_happen": [], "ending_hook": "", "visible_facts": [],
            ]), "不能为空"),
            ("novel_revise_chapter", "{}", "start_paragraph"),
            ("novel_revise_chapter", jsonArgs([
                "start_paragraph": 1, "end_paragraph": 1, "new_text": "   ",
            ]), "不能为空"),
            ("novel_revert_recent_chapters", "{}", "chapter_count"),
        ]
        for (name, arguments, expectedFragment) in cases {
            let outcome = await harness.execute(name, arguments)
            guard case .failed(let message) = outcome else {
                XCTFail("\(name) 应返回 failed，实际 \(outcome)")
                continue
            }
            XCTAssertTrue(
                message.contains(expectedFragment),
                "\(name) 的失败文案应包含「\(expectedFragment)」，实际：\(message)"
            )
        }

        // 全部失败后项目字段保持原样。
        let snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.project.name, "Test Novel")
        XCTAssertTrue(
            snapshot.appliedOperations.allSatisfy { $0.kind == .createProject },
            "非法调用不得写入任何操作（除建项目外）"
        )
        XCTAssertTrue(snapshot.materials.isEmpty)
        XCTAssertNil(snapshot.chapterPlan(for: harness.branchID))
    }

    func testReviseMaterialUnknownMaterialIDFails() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute(
            "novel_revise_material",
            jsonArgs([
                "material_id": UUID().uuidString,
                "kind": "world",
                "title": "标题",
                "content": "内容",
            ])
        )
        guard case .failed(let message) = outcome else {
            XCTFail("Expected failed, got \(outcome)")
            return
        }
        XCTAssertTrue(message.contains("找不到指定资料"))
    }

    func testUpcomingArcRejectsOverLimitBeats() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let tooMany = await harness.execute(
            "novel_upsert_upcoming_arc",
            jsonArgs(["beats": (1...9).map { "节拍\($0)" }])
        )
        guard case .failed(let countMessage) = tooMany else {
            XCTFail("Expected failed for 9 beats, got \(tooMany)")
            return
        }
        XCTAssertTrue(countMessage.contains("最多 8 条"))

        let tooLong = await harness.execute(
            "novel_upsert_upcoming_arc",
            jsonArgs(["beats": [String(repeating: "长", count: 161)]])
        )
        guard case .failed(let lengthMessage) = tooLong else {
            XCTFail("Expected failed for 161-char beat, got \(tooLong)")
            return
        }
        XCTAssertTrue(lengthMessage.contains("160 字"))

        // 恰好 8 条、每条 160 字以内可以通过。
        let boundary = await harness.execute(
            "novel_upsert_upcoming_arc",
            jsonArgs(["beats": (1...8).map { "节拍\($0)" }])
        )
        guard case .filled = boundary else {
            XCTFail("Expected filled at boundary, got \(boundary)")
            return
        }
        let snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.upcomingArc(for: harness.branchID)?.beats.count, 8)
    }

    func testClearUpcomingArcWithoutArcFails() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute("novel_clear_upcoming_arc", "{}")
        guard case .failed(let message) = outcome else {
            XCTFail("Expected failed, got \(outcome)")
            return
        }
        XCTAssertTrue(message.contains("没有「往后几章」备注"))
    }

    // MARK: - Setting proposals

    func testListSettingProposalsEmptyIsFilled() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute("novel_list_setting_proposals", "{}")
        guard case .filled(let text) = outcome else {
            XCTFail("空列表应返回 filled，实际 \(outcome)")
            return
        }
        XCTAssertTrue(text.contains("当前没有待确认的设定建议"))
    }

    func testListSettingProposalsIncludesIdsAndTitles() async throws {
        let (harness, ids) = try await makeHarnessWithSettingProposals(["粮仓", "殿前司"])
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute("novel_list_setting_proposals", "{}")
        guard case .filled(let text) = outcome else {
            XCTFail("Expected filled, got \(outcome)")
            return
        }
        XCTAssertTrue(text.contains("粮仓"))
        XCTAssertTrue(text.contains("殿前司"))
        XCTAssertTrue(text.contains(ids[0].description))
        XCTAssertTrue(text.contains(ids[1].description))
        let snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.activeSettingProposals(for: harness.branchID).count, 2)
    }

    func testRejectSettingProposalsOmittingIdsRejectsAll() async throws {
        let (harness, _) = try await makeHarnessWithSettingProposals(["粮仓", "马厩", "殿前司"])
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute("novel_reject_setting_proposals", "{}")
        guard case .filled(let text) = outcome else {
            XCTFail("全拒应返回 filled，实际 \(outcome)")
            return
        }
        XCTAssertTrue(text.contains("已拒绝 3 条"))
        XCTAssertTrue(text.contains("粮仓"))
        XCTAssertTrue(text.contains("马厩"))
        XCTAssertTrue(text.contains("殿前司"))

        let snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.activeSettingProposals(for: harness.branchID).count, 0)
        XCTAssertEqual(snapshot.settingProposals.filter(\.isResolved).count, 3)
        XCTAssertEqual(
            snapshot.appliedOperations.filter { $0.kind == .resolveSettingProposal }.count,
            3
        )
    }

    func testRejectSettingProposalsEmptyArrayRejectsAll() async throws {
        let (harness, _) = try await makeHarnessWithSettingProposals(["粮仓"])
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute(
            "novel_reject_setting_proposals",
            jsonArgs(["proposal_ids": [] as [String]])
        )
        guard case .filled = outcome else {
            XCTFail("空数组应全拒，实际 \(outcome)")
            return
        }
        let snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.activeSettingProposals(for: harness.branchID).count, 0)
    }

    func testRejectSettingProposalsSpecifiedIdLeavesTheRest() async throws {
        let (harness, ids) = try await makeHarnessWithSettingProposals(["粮仓", "殿前司"])
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute(
            "novel_reject_setting_proposals",
            jsonArgs(["proposal_ids": [ids[0].rawValue.uuidString]])
        )
        guard case .filled(let text) = outcome else {
            XCTFail("指定拒绝应返回 filled，实际 \(outcome)")
            return
        }
        XCTAssertTrue(text.contains("粮仓"))
        XCTAssertFalse(text.contains("殿前司"))

        let snapshot = try await harness.snapshot()
        let remaining = snapshot.activeSettingProposals(for: harness.branchID)
        XCTAssertEqual(remaining.map(\.title), ["殿前司"])
    }

    func testRejectSettingProposalsUnknownIdFailsAndLeavesDocument() async throws {
        let (harness, ids) = try await makeHarnessWithSettingProposals(["粮仓"])
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute(
            "novel_reject_setting_proposals",
            jsonArgs(["proposal_ids": [UUID().uuidString]])
        )
        guard case .failed(let message) = outcome else {
            XCTFail("未知 id 应失败，实际 \(outcome)")
            return
        }
        XCTAssertTrue(message.contains("找不到待确认的设定建议"))

        let snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.activeSettingProposals(for: harness.branchID).map(\.id), ids)
    }

    func testRejectSettingProposalsInvalidJSONFails() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute("novel_reject_setting_proposals", "{")
        guard case .failed(let message) = outcome else {
            XCTFail("非法 JSON 应失败，实际 \(outcome)")
            return
        }
        XCTAssertTrue(message.contains("参数无效"))
    }

    // MARK: - Ghostwrite guard

    func testGhostwriteRunningBlocksPlanAndMaterialButNotRenameOrArc() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let startedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let record = NovelGhostwriteBatchProgressRecord(
            schemaVersion: NovelGhostwriteBatchProgressRecord.currentSchemaVersion,
            projectID: harness.projectID,
            branchID: harness.branchID,
            phase: .writing,
            pauseReason: nil,
            detailMessage: nil,
            candidateID: nil,
            chapterPlanDigest: nil,
            autoCollectedCandidateIDs: [],
            startedAt: startedAt,
            updatedAt: startedAt,
            targetChapterCount: 3,
            completedChapterCount: 0,
            currentChapterIndex: 1,
            lastCompletedPlanSummary: nil,
            pendingSyncChapterCredit: false,
            qualityAttemptIndex: 0,
            maxQualityAttempts: 3,
            lastFailureReceipt: nil,
            supersededCandidateIDs: [],
            recentFailureFingerprints: [],
            revisionBriefOverride: nil,
            didThinContractAmendThisChapter: false,
            contractAmendments: []
        )
        try await harness.creation.saveGhostwriteBatchProgress(record)

        let plan = await harness.execute("novel_propose_chapter_plan", jsonArgs([
            "outline_placement": "x",
            "goal_and_conflict": "目标",
            "must_happen": [], "must_not_happen": [], "ending_hook": "", "visible_facts": [],
        ]))
        guard case .failed(let planMessage) = plan else {
            XCTFail("代笔中拟计划应被拒绝，实际 \(plan)")
            return
        }
        XCTAssertTrue(planMessage.contains("代笔正在推进本章"))

        let material = await harness.execute(
            "novel_revise_material",
            jsonArgs(["kind": "world", "title": "标题", "content": "内容"])
        )
        guard case .failed(let materialMessage) = material else {
            XCTFail("代笔中改资料应被拒绝，实际 \(material)")
            return
        }
        XCTAssertTrue(materialMessage.contains("代笔正在推进本章"))

        // rename / preference / arc 不挡。
        let rename = await harness.execute(
            "novel_rename_project",
            jsonArgs(["title": "代笔中也可改名"])
        )
        guard case .filled = rename else {
            XCTFail("代笔中改名不应被挡，实际 \(rename)")
            return
        }
        let arc = await harness.execute(
            "novel_upsert_upcoming_arc",
            jsonArgs(["beats": ["照常记录"]])
        )
        guard case .filled = arc else {
            XCTFail("代笔中记录下一弧不应被挡，实际 \(arc)")
            return
        }
        let list = await harness.execute("novel_list_setting_proposals", "{}")
        guard case .filled = list else {
            XCTFail("代笔中应允许列出设定建议，实际 \(list)")
            return
        }
        let reject = await harness.execute("novel_reject_setting_proposals", "{}")
        guard case .filled = reject else {
            XCTFail("代笔中应允许清设定建议，实际 \(reject)")
            return
        }
        let snapshot = try await harness.snapshot()
        XCTAssertNil(snapshot.chapterPlan(for: harness.branchID), "被拒的拟计划不得落盘")
        XCTAssertTrue(snapshot.materials.isEmpty, "被拒的资料不得落盘")
        XCTAssertEqual(snapshot.project.name, "代笔中也可改名")
        XCTAssertEqual(snapshot.upcomingArc(for: harness.branchID)?.beats, ["照常记录"])
    }

    func testGhostwriteBlocksChapterRevision() async throws {
        let (harness, _) = try await makeHarnessWithChapter()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let startedAt = Date(timeIntervalSince1970: 1_700_000_000)
        try await harness.creation.saveGhostwriteBatchProgress(NovelGhostwriteBatchProgressRecord(
            schemaVersion: NovelGhostwriteBatchProgressRecord.currentSchemaVersion,
            projectID: harness.projectID,
            branchID: harness.branchID,
            phase: .writing,
            pauseReason: nil,
            detailMessage: nil,
            candidateID: nil,
            chapterPlanDigest: nil,
            autoCollectedCandidateIDs: [],
            startedAt: startedAt,
            updatedAt: startedAt,
            targetChapterCount: 1,
            completedChapterCount: 0,
            currentChapterIndex: 1,
            lastCompletedPlanSummary: nil,
            pendingSyncChapterCredit: false,
            qualityAttemptIndex: 0,
            maxQualityAttempts: 3,
            lastFailureReceipt: nil,
            supersededCandidateIDs: [],
            recentFailureFingerprints: [],
            revisionBriefOverride: nil,
            didThinContractAmendThisChapter: false,
            contractAmendments: []
        ))

        let outcome = await harness.execute(
            "novel_revise_chapter",
            jsonArgs([
                "start_paragraph": 1,
                "end_paragraph": 1,
                "new_text": "代笔中不该改",
            ])
        )
        guard case .failed(let message) = outcome else {
            XCTFail("代笔中改正文应被拒绝，实际 \(outcome)")
            return
        }
        XCTAssertTrue(message.contains("代笔正在推进本章"))
        let snapshot = try await harness.snapshot()
        XCTAssertFalse(snapshot.appliedOperations.contains { $0.kind == .saveManualEdit })
    }

    func testPausedGhostwriteAllowsPlanDraft() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let startedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let record = NovelGhostwriteBatchProgressRecord(
            schemaVersion: NovelGhostwriteBatchProgressRecord.currentSchemaVersion,
            projectID: harness.projectID,
            branchID: harness.branchID,
            phase: .paused,
            pauseReason: .userPaused,
            detailMessage: nil,
            candidateID: nil,
            chapterPlanDigest: nil,
            autoCollectedCandidateIDs: [],
            startedAt: startedAt,
            updatedAt: startedAt,
            targetChapterCount: 3,
            completedChapterCount: 0,
            currentChapterIndex: 1,
            lastCompletedPlanSummary: nil,
            pendingSyncChapterCredit: false,
            qualityAttemptIndex: 0,
            maxQualityAttempts: 3,
            lastFailureReceipt: nil,
            supersededCandidateIDs: [],
            recentFailureFingerprints: [],
            revisionBriefOverride: nil,
            didThinContractAmendThisChapter: false,
            contractAmendments: []
        )
        try await harness.creation.saveGhostwriteBatchProgress(record)

        let plan = await harness.execute("novel_propose_chapter_plan", jsonArgs([
            "outline_placement": "x",
            "goal_and_conflict": "目标",
            "must_happen": [], "must_not_happen": [], "ending_hook": "", "visible_facts": [],
        ]))
        guard case .filled = plan else {
            XCTFail("暂停代笔时应允许拟计划，实际 \(plan)")
            return
        }
    }

    /// 自定义卡的显示名是关联值：agent 只能传 kind="custom"，更新既有
    /// .custom("魔法体系") 卡时必须保留原显示名，且不被关联值判等误拦。
    func testReviseMaterialPreservesExistingCustomKindName() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let snapshot = try await harness.snapshot()
        let materialID = NovelMaterialID()
        _ = try await harness.creation.perform(.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: nil,
                expectedConfigRevision: snapshot.project.configRevision,
                expectedBranchHeadRevision: nil
            ),
            projectID: harness.projectID,
            materialID: materialID,
            revisionID: NovelMaterialRevisionID(),
            kind: .custom("魔法体系"),
            title: "魔法体系",
            content: "初版设定",
            tags: [],
            injectionMode: .smart,
            aliases: []
        )))

        let outcome = await harness.execute(
            "novel_revise_material",
            jsonArgs([
                "material_id": materialID.rawValue.uuidString,
                "kind": "custom",
                "title": "魔法体系",
                "content": "修订后的设定",
            ])
        )
        guard case .filled = outcome else {
            XCTFail("更新具名 custom 卡不应被 kind 判等误拦，实际 \(outcome)")
            return
        }
        let after = try await harness.snapshot()
        let material = after.materials.first(where: { $0.id == materialID })
        XCTAssertEqual(material?.kind, .custom("魔法体系"), "更新不得把显示名冲成「自定义」")
        let revision = after.materialRevisions.first(where: { $0.id == material?.currentRevisionID })
        XCTAssertEqual(revision?.content, "修订后的设定")
    }

    /// 新建 custom 卡可用 custom_name 命名，否则多张 agent 建的卡并列都叫「自定义」。
    func testReviseMaterialCreateCustomUsesCustomName() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let outcome = await harness.execute(
            "novel_revise_material",
            jsonArgs(["kind": "custom", "title": "伏笔跟踪", "content": "待回收的伏笔清单", "custom_name": "伏笔体系"])
        )
        guard case .filled = outcome else {
            XCTFail("新建 custom 卡失败，实际 \(outcome)")
            return
        }
        let snapshot = try await harness.snapshot()
        XCTAssertEqual(snapshot.materials.first?.kind, .custom("伏笔体系"))
    }

    /// 回归锁：讨论 run 本身以 .running 登记在 activeRuns 里（checker 抓到的
    /// 自锁 CRITICAL）——守卫只查代笔相位，不得被调用方自己的 run 误拦。
    /// 用脚本化 adapter 起一条真实 discussion run 并停在 .pause，还原生产形态。
    func testRunningDiscussionActiveRunDoesNotSelfBlock() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let repository = NovelFileProjectRepository(rootDirectory: harness.root)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "provider-id",
                ownerProviderID: "provider-id",
                modelID: "model-uuid",
                wireModelID: "novel-model-v1",
                displayName: "Novel Model",
                contextWindowTokens: 128_000
            ),
            scripts: [NovelModelScript(steps: [.pause, .delta("讨论中"), .complete])]
        )
        let creation = DefaultNovelCreation(repository: repository, modelRunner: adapter)
        let loaded = try await repository.loadProject(id: harness.projectID)
        let branch = loaded.document.branches[0]
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: harness.projectID,
            branchID: branch.id,
            kind: .discussion,
            mode: .discussPlan,
            granularity: nil,
            userText: "讨论下一章",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: nil,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            contextualCharacterMention: nil,
            inputBudgetTokens: 16_000,
            expectedProjectRevision: loaded.document.project.revision,
            expectedConfigRevision: loaded.document.project.configRevision,
            expectedBranchHeadRevision: branch.headRevision
        )
        let run = try await creation.start(request)
        let persisted = try await repository.loadProject(id: harness.projectID)
        XCTAssertEqual(persisted.document.activeRuns.first?.status, .running, "讨论 run 应处于进行中")

        let executor = IOSNovelProjectToolExecutor(
            projectContext: NovelProjectToolRunContext(projectID: harness.projectID, branchID: branch.id),
            creation: creation
        )
        let material = await executor.execute(
            name: "novel_revise_material",
            arguments: jsonArgs(["kind": "world", "title": "讨论中沉淀", "content": "设定内容"]),
            isUserInitiated: false
        )
        guard case .filled = material else {
            XCTFail("讨论 run 在场不应拦截资料写入，实际 \(material)")
            return
        }
        let plan = await executor.execute(
            name: "novel_propose_chapter_plan",
            arguments: jsonArgs([
                "outline_placement": "x",
                "goal_and_conflict": "目标",
                "must_happen": [], "must_not_happen": [], "ending_hook": "", "visible_facts": [],
            ]),
            isUserInitiated: false
        )
        guard case .filled = plan else {
            XCTFail("讨论 run 在场不应拦截计划草稿，实际 \(plan)")
            return
        }

        await adapter.resume(runID: request.id)
        for await _ in run.events {}
    }

    /// 已确认合同不得被讨论工具静默降级为草稿（reducer 只按 planID 判撞）。
    func testConfirmedChapterPlanRefusesDraftDemotion() async throws {
        let harness = try await makeHarness()
        defer { try? FileManager.default.removeItem(at: harness.root) }

        let before = try await harness.snapshot()
        _ = try await harness.creation.perform(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: nil,
                expectedConfigRevision: before.project.configRevision,
                expectedBranchHeadRevision: nil
            ),
            projectID: harness.projectID,
            branchID: harness.branchID,
            planID: NovelChapterPlanID(),
            status: .confirmed,
            outlinePlacement: "第一卷",
            goalAndConflict: "已确认的目标",
            mustHappen: ["主角离开旧城"],
            mustNotHappen: [],
            endingHook: "",
            visibleFacts: []
        )))

        let outcome = await harness.execute("novel_propose_chapter_plan", jsonArgs([
            "outline_placement": "x",
            "goal_and_conflict": "讨论出的新目标",
            "must_happen": [], "must_not_happen": [], "ending_hook": "", "visible_facts": [],
        ]))
        guard case .failed(let message) = outcome else {
            XCTFail("已确认合同应拒绝草稿降级，实际 \(outcome)")
            return
        }
        XCTAssertTrue(message.contains("已有确认的本章合同"))

        let snapshot = try await harness.snapshot()
        let plan = snapshot.chapterPlan(for: harness.branchID)
        XCTAssertEqual(plan?.status, .confirmed, "确认合同不得被改动")
        XCTAssertEqual(plan?.goalAndConflict, "已确认的目标")
    }

    // MARK: - Assembly chain (adapter start → makeParameters → transport)

    func testAdapterStartThreadsProjectContextIntoDiscussionTransport() async throws {
        let model = makeModel()
        let provider = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Discussion",
            models: [model],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "test-key",
            baseUrl: "https://example.test/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        let captured = TestBox<NovelLiveTransportRequest>()
        let adapter = NovelLiveModelAdapter(
            catalogProvider: {
                NovelLiveModelCatalog(currentModel: model, providers: [provider])
            },
            kmpTransport: { _, callbacks in
                callbacks.onComplete()
                return nil
            },
            discussionTransport: { request, callbacks in
                captured.set(request)
                callbacks.onComplete()
                return nil
            },
            discussionSearchEnabled: { true }
        )

        let projectID = NovelProjectID()
        let branchID = NovelBranchID()
        let resolved = try await adapter.resolveModel(for: .global)
        let request = NovelModelRequest(
            runID: NovelRunID(),
            model: resolved,
            purpose: .discussion,
            messages: [],
            parameters: NovelModelParameters(
                temperature: nil,
                topP: nil,
                maxOutputTokens: nil,
                reasoningLevel: .off
            ),
            projectID: projectID,
            branchID: branchID
        )
        let events = try await adapter.start(request)
        for await _ in events { /* drain until completion */ }

        let transportRequest = try XCTUnwrap(captured.value)
        XCTAssertEqual(transportRequest.novelProjectContext?.projectID, projectID)
        XCTAssertEqual(transportRequest.novelProjectContext?.branchID, branchID)
        let toolNames = transportRequest.parameters.tools.map { $0.name }
        for name in IOSNovelProjectToolExecutor.supportedToolNames {
            XCTAssertTrue(toolNames.contains(name), "adapter.start 必须带上 \(name)")
        }
        XCTAssertTrue(toolNames.contains("ask_user"))
        XCTAssertTrue(toolNames.contains("search_web"))
    }

    func testAdapterStartOmitsProjectToolsForProsePurpose() async throws {
        let model = makeModel()
        let provider = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Discussion",
            models: [model],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "test-key",
            baseUrl: "https://example.test/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        let captured = TestBox<NovelLiveTransportRequest>()
        let adapter = NovelLiveModelAdapter(
            catalogProvider: {
                NovelLiveModelCatalog(currentModel: model, providers: [provider])
            },
            kmpTransport: { request, callbacks in
                captured.set(request)
                callbacks.onComplete()
                return nil
            },
            discussionTransport: { _, callbacks in
                callbacks.onComplete()
                return nil
            },
            discussionSearchEnabled: { true }
        )

        let resolved = try await adapter.resolveModel(for: .global)
        let request = NovelModelRequest(
            runID: NovelRunID(),
            model: resolved,
            purpose: .prose,
            messages: [],
            parameters: NovelModelParameters(
                temperature: nil,
                topP: nil,
                maxOutputTokens: nil,
                reasoningLevel: .off
            ),
            projectID: NovelProjectID(),
            branchID: NovelBranchID()
        )
        let events = try await adapter.start(request)
        for await _ in events { /* drain until completion */ }

        let transportRequest = try XCTUnwrap(captured.value)
        XCTAssertNil(transportRequest.novelProjectContext, "非 discussion purpose 不得注入项目上下文")
        XCTAssertTrue(
            transportRequest.parameters.tools.map { $0.name }.allSatisfy {
                !IOSNovelProjectToolExecutor.supportedToolNames.contains($0)
            },
            "非 discussion purpose 不得注入小说写工具"
        )
    }

    // MARK: - Helpers

    private func makeModel() -> Model {
        Model(
            modelId: "novel-live",
            displayName: "Novel Live",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: KotlinInt(value: 128_000),
            providerOverwrite: nil
        )
    }
}

/// 跨隔离域捕获工具请求的小盒子（测试专用）。
private final class TestBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Value?

    func set(_ value: Value) {
        lock.lock()
        storage = value
        lock.unlock()
    }

    var value: Value? {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }
}
