import XCTest
@testable import iosApp

/// 复现「替换章节后剧情状态只加不减」。
///
/// 这些用例刻意走**活路径**：收录用 `commitCollectionWithoutStateSync`(→ `.needsSync`)，
/// 再由 manual sync 事务重建状态。既有的 NovelManualEditSyncTests / NovelCollectionTests
/// 用的是 `prepareCollection` + `finalizeCollection`，那条 `.stateDelta` 链路在生产里
/// 没有调用点（NovelFactTransactionLifecycle.swift 的 executeCollectionTransaction 已标注
/// 为死代码），因此不能用来判定线上行为。
final class NovelChapterReplacementStateTests: XCTestCase {
    private let origin = Date(timeIntervalSince1970: 1_700_300_000)
    private var clock: TimeInterval = 0

    private func tick() -> Date {
        clock += 1
        return origin.addingTimeInterval(clock)
    }

    private struct Chapter {
        let id: NovelChapterID
        let title: String
        let content: String
    }

    // MARK: - 主复现：替换第三章后，旧事实是否退场

    func testReplacingCollectedChapterDropsItsOldFactsFromTheRebuildBaseline() throws {
        var document = try NovelTestFixtures.document()

        try collectNewChapter(
            title: "Chapter One",
            content: "Mara guarded the gate.",
            in: &document
        )
        let firstEventID = try synchronize(
            summary: "Mara guards the gate.",
            eventID: "event-chapter-one",
            evidence: "Mara guarded the gate.",
            in: &document
        )

        try collectNewChapter(
            title: "Chapter Two",
            content: "Ivo crossed the bridge.",
            in: &document
        )
        let secondEventID = try synchronize(
            summary: "Mara guards the gate. Ivo crossed the bridge.",
            eventID: "event-chapter-two",
            evidence: "Ivo crossed the bridge.",
            in: &document
        )

        let three = try collectNewChapter(
            title: "Chapter Three",
            content: "Ivo died at the gate.",
            in: &document
        )
        let deathEventID = try synchronize(
            summary: "Mara guards the gate. Ivo crossed the bridge. Ivo died at the gate.",
            eventID: "event-chapter-three-death",
            evidence: "Ivo died at the gate.",
            in: &document
        )

        let headBeforeReplacement = try headSnapshot(in: document)
        XCTAssertEqual(
            headBeforeReplacement.eventIDs.count,
            3,
            "前置条件:三章各自贡献了一条事实。"
        )
        XCTAssertTrue(headBeforeReplacement.summary.contains("died"))

        // 重写第三章:Ivo 没有死。
        try replaceChapter(three, with: "Ivo escaped alive through the gate.", in: &document)

        let (pendingID, input) = try beginSynchronization(in: &document)

        // 判据一:重建基线必须仍然覆盖前两章的事实,否则替换会连带炸掉无关章节。
        XCTAssertTrue(
            input.baseStateSnapshot.eventIDs.contains(firstEventID),
            "重建基线丢了第一章的事实。"
        )
        XCTAssertTrue(
            input.baseStateSnapshot.eventIDs.contains(secondEventID),
            "重建基线丢了第二章的事实。"
        )
        // 判据二:被替换那一章的旧事实必须退出基线,否则「死了」和「活着」会并存。
        XCTAssertFalse(
            input.baseStateSnapshot.eventIDs.contains(deathEventID),
            "重建基线里仍留着被替换章节的旧事实。"
        )
        // 判据三:被替换章节的新正文必须进入重建输入,否则新事实无从抽取。
        XCTAssertEqual(input.chapters.map(\.chapterID), [three.id])
        XCTAssertEqual(input.chapters.first?.content, "Ivo escaped alive through the gate.")

        let committed = try finishSynchronization(
            pendingID: pendingID,
            summary: "Mara guards the gate. Ivo crossed the bridge. Ivo escaped alive.",
            eventID: "event-chapter-three-escape",
            evidence: "Ivo escaped alive through the gate.",
            in: &document
        )

        let headAfter = try headSnapshot(in: committed)
        XCTAssertFalse(
            headAfter.summary.contains("died"),
            "新的状态摘要里仍然留着被替换掉的死亡结局。"
        )
        XCTAssertFalse(
            headAfter.eventIDs.contains(deathEventID),
            "新的状态快照里仍然引用着被替换章节的旧事件。"
        )
        XCTAssertTrue(headAfter.eventIDs.contains(firstEventID))
        XCTAssertTrue(headAfter.eventIDs.contains(secondEventID))
        XCTAssertEqual(committed.branches[0].syncStatus, .synchronized)
    }

    // MARK: - 嫌疑 C：重建基线可能落在 collection 检查点上

    /// collection 检查点的 stateSnapshotID 是**继承父节点的**,它落后于自己的
    /// chapterSelections。若 `rebuildBaseCheckpoint(before:)` 把这种检查点选作基线,
    /// 基线状态覆盖的章节会少于基线选集,而 suffix 又是按选集算的 —— 中间那些章
    /// 既不在基线状态里、也不在待重抽的正文里,凭空蒸发。
    func testReplacingChapterKeepsEarlierFactsWhenCollectionsSkippedSync() throws {
        var document = try NovelTestFixtures.document()

        _ = try collectNewChapter(
            title: "Chapter One",
            content: "Mara guarded the gate.",
            in: &document
        )
        let firstEventID = try synchronize(
            summary: "Mara guards the gate.",
            eventID: "event-one",
            evidence: "Mara guarded the gate.",
            in: &document
        )

        // 连收两章都不同步 —— 自动同步失败、离线、连续收录都会走到这里。
        let two = try collectNewChapter(
            title: "Chapter Two",
            content: "Ivo crossed the bridge.",
            in: &document
        )
        let three = try collectNewChapter(
            title: "Chapter Three",
            content: "Ivo died at the gate.",
            in: &document
        )
        let laterEventIDs = try synchronizeMany(
            summary: "Mara guards the gate. Ivo crossed the bridge. Ivo died at the gate.",
            facts: [
                ("event-two", "Ivo crossed the bridge."),
                ("event-three", "Ivo died at the gate."),
            ],
            in: &document
        )
        let secondEventID = laterEventIDs[0]

        try replaceChapter(three, with: "Ivo escaped alive through the gate.", in: &document)

        let (_, input) = try beginSynchronization(in: &document)

        XCTAssertTrue(
            input.baseStateSnapshot.eventIDs.contains(firstEventID),
            "重建基线丢了第一章的事实。"
        )
        XCTAssertTrue(
            input.baseStateSnapshot.eventIDs.contains(secondEventID) ||
                input.chapters.contains { $0.chapterID == two.id },
            "第二章既不在重建基线状态里,也不在待重抽的正文里 —— 它的事实无法恢复。"
        )
    }

    // MARK: - 活路径脚手架

    /// 走 `.createNextChapter` 收录一章（不做状态同步，与生产一致）。
    @discardableResult
    private func collectNewChapter(
        title: String,
        content: String,
        in document: inout NovelProjectDocumentV1
    ) throws -> Chapter {
        try makeProseCandidate(content: content, sourceChapterVersionID: nil, in: &document)
        let candidate = try XCTUnwrap(document.candidates.last)
        let chapterID = NovelChapterID()
        let command = collectCommand(
            document: document,
            candidate: candidate,
            target: .createNextChapter(chapterID: chapterID, title: title)
        )
        document = try NovelFactTransactionReducer.commitCollectionWithoutStateSync(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: tick()
        ).document
        return Chapter(id: chapterID, title: title, content: content)
    }

    /// 走 `.replaceChapter` 用一次整章重新生成的产物覆盖既有章节。
    private func replaceChapter(
        _ chapter: Chapter,
        with content: String,
        in document: inout NovelProjectDocumentV1
    ) throws {
        let currentVersionID = try XCTUnwrap(
            document.branches[0].workingChapterSelections
                .first { $0.chapterID == chapter.id }?.versionID
        )
        try makeProseCandidate(
            content: content,
            sourceChapterVersionID: currentVersionID,
            in: &document
        )
        let candidate = try XCTUnwrap(document.candidates.last)
        let command = collectCommand(
            document: document,
            candidate: candidate,
            target: .replaceChapter(chapter.id)
        )
        document = try NovelFactTransactionReducer.commitCollectionWithoutStateSync(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: tick()
        ).document
    }

    private func collectCommand(
        document: NovelProjectDocumentV1,
        candidate: NovelCandidateRecord,
        target: NovelCollectionTarget
    ) -> NovelCollectCandidateCommand {
        NovelCollectCandidateCommand(
            context: mutationContext(document: document),
            projectID: document.project.id,
            branchID: candidate.branchID,
            pendingID: NovelPendingOperationID(),
            candidateID: candidate.id,
            selection: NovelParagraphSelection(
                paragraphIDs: NovelParagraphParser.paragraphs(in: candidate.content).map(\.id)
            ),
            target: target,
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        )
    }

    /// 单条事实的同步，返回新事件的记录 ID。
    @discardableResult
    private func synchronize(
        summary: String,
        eventID: String,
        evidence: String,
        in document: inout NovelProjectDocumentV1
    ) throws -> NovelEventID {
        try synchronizeMany(
            summary: summary,
            facts: [(eventID, evidence)],
            in: &document
        )[0]
    }

    private func synchronizeMany(
        summary: String,
        facts: [(id: String, evidence: String)],
        in document: inout NovelProjectDocumentV1
    ) throws -> [NovelEventID] {
        let (pendingID, _) = try beginSynchronization(in: &document)
        let before = document.events.count
        document = try finishSynchronization(
            pendingID: pendingID,
            rebuild: rebuild(summary: summary, facts: facts),
            in: &document
        )
        return Array(document.events.dropFirst(before)).map(\.id)
    }

    private func beginSynchronization(
        in document: inout NovelProjectDocumentV1
    ) throws -> (NovelPendingOperationID, NovelManualRebuildInput) {
        let command = NovelSyncManualEditsCommand(
            context: mutationContext(document: document),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: document.branches[0].workingRevision
        )
        document = try NovelFactTransactionReducer.prepareManualSync(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: document,
            now: tick()
        ).document
        let input = try NovelFactTransactionReducer.manualRebuildInput(
            pendingID: command.pendingID,
            in: document
        )
        return (command.pendingID, input)
    }

    private func finishSynchronization(
        pendingID: NovelPendingOperationID,
        summary: String,
        eventID: String,
        evidence: String,
        in document: inout NovelProjectDocumentV1
    ) throws -> NovelProjectDocumentV1 {
        try finishSynchronization(
            pendingID: pendingID,
            rebuild: rebuild(summary: summary, facts: [(eventID, evidence)]),
            in: &document
        )
    }

    private func finishSynchronization(
        pendingID: NovelPendingOperationID,
        rebuild: NovelStateRebuildV1,
        in document: inout NovelProjectDocumentV1
    ) throws -> NovelProjectDocumentV1 {
        try NovelFactTransactionReducer.finalizeManualSync(
            pendingID: pendingID,
            rebuild: rebuild,
            artifacts: NovelTestFixtures.factTransactionArtifacts(
                document: document,
                pendingID: pendingID
            ),
            in: document,
            now: tick()
        ).document
    }

    private func rebuild(
        summary: String,
        facts: [(id: String, evidence: String)]
    ) -> NovelStateRebuildV1 {
        NovelStateRebuildV1(
            schemaVersion: 1,
            stateSummary: summary,
            branchOutline: "The branch continues from " + summary,
            events: facts.map {
                NovelStateEventV1(
                    id: $0.id,
                    kind: "fact",
                    summary: $0.evidence,
                    entityReferences: [],
                    evidence: $0.evidence
                )
            },
            characterStates: [],
            relationships: [],
            foreshadowing: [],
            unresolvedEntityNames: [],
            settingProposals: []
        )
    }

    private func headSnapshot(
        in document: NovelProjectDocumentV1
    ) throws -> NovelStateSnapshotRecord {
        try XCTUnwrap(document.stateSnapshots.first {
            $0.id == document.branches[0].currentStateSnapshotID
        })
    }

    private func mutationContext(
        document: NovelProjectDocumentV1
    ) -> NovelMutationContext {
        NovelMutationContext(
            operationID: NovelOperationID(),
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: document.branches[0].headRevision
        )
    }

    /// 生成一条已完成的正文候选。`sourceChapterVersionID` 非 nil 时走 `.regenerate`
    /// 形状 —— `.replaceChapter` 的领域守卫要求候选确实重写了那一章。
    private func makeProseCandidate(
        content: String,
        sourceChapterVersionID: NovelChapterVersionID?,
        in document: inout NovelProjectDocumentV1
    ) throws {
        let branch = document.branches[0]
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: document.project.id,
            branchID: branch.id,
            kind: sourceChapterVersionID == nil ? .prose : .regenerate,
            mode: .writeProse,
            granularity: .wholeChapter,
            userText: "Write a chapter.",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: NovelCandidateID(),
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: sourceChapterVersionID,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: branch.headRevision
        )
        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: branch.id,
                promptKind: sourceChapterVersionID == nil
                    ? .proseWholeChapter
                    : .wholeChapterRegeneration,
                userText: request.userText,
                sourceChapterVersionID: sourceChapterVersionID,
                budget: NovelInjectionBudget(
                    maxEstimatedInputTokens: request.inputBudgetTokens,
                    chapterTailCharacterLimit: 6_000,
                    maximumRecentSessionMessages: 12
                )
            )
        )
        let injection = NovelInjectionReceiptRecord(
            id: request.injectionReceiptID,
            runID: request.id,
            projectID: request.projectID,
            branchID: request.branchID,
            plan: plan,
            overrides: .none,
            providerID: "provider-id",
            modelID: "model-id",
            parameters: [:],
            createdAt: tick()
        )
        let generation = NovelGenerationReceiptRecord(
            id: request.generationReceiptID,
            runID: request.id,
            providerID: injection.providerID,
            modelID: injection.modelID,
            promptVersion: injection.promptVersion,
            injectionReceiptID: injection.id,
            parameters: [:],
            requestSHA256: NovelDocumentValidator.sha256(plan.canonicalInput + "\nMODEL REQUEST"),
            createdAt: tick()
        )
        document = try NovelGenerationReducer.begin(
            request,
            artifacts: NovelGenerationStartArtifacts(
                injectionReceipt: injection,
                generationReceipt: generation
            ),
            in: document,
            now: tick()
        ).document
        document = try NovelGenerationReducer.complete(
            runID: request.id,
            content: content,
            in: document,
            now: tick()
        ).document
    }
}
