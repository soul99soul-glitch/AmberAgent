import XCTest
@testable import iosApp

final class NovelRecentChapterRevertTests: XCTestCase {
    func testPlanRevertsLastTwoCollectedChaptersAndRestoresStateSnapshot() throws {
        let first = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "第一章：赵大进村。"
        )
        let three = try collectChapters(
            after: first,
            titlesAndContents: [
                ("口中名", "第二章：城里有人叫他的名字。"),
                ("山呼", "第三章：山里有人应声。"),
            ]
        )
        let branch = three.branches[0]
        let firstSnapshotID = first.branches[0].currentStateSnapshotID
        XCTAssertEqual(workingTitles(in: three), ["Chapter One", "口中名", "山呼"])
        XCTAssertNotEqual(branch.currentStateSnapshotID, firstSnapshotID)

        let plan = try NovelBranchSemantics.recentChapterRevertPlan(
            chapterCount: 2,
            branch: branch,
            document: three
        ).get()
        XCTAssertEqual(plan.chapters.map(\.title), ["口中名", "山呼"])
        XCTAssertEqual(plan.undoStepCount, 2)
        XCTAssertEqual(plan.targetCheckpointID, first.branches[0].headCheckpointID)

        let undone = try undo(three, steps: plan.undoStepCount)
        let after = undone.branches[0]
        XCTAssertEqual(after.headCheckpointID, plan.targetCheckpointID)
        XCTAssertEqual(after.currentStateSnapshotID, firstSnapshotID)
        XCTAssertEqual(workingTitles(in: undone), ["Chapter One"])
        XCTAssertNoThrow(try NovelDocumentValidator.validate(undone))
    }

    func testPlanRejectsManualEditUntilSynchronized() throws {
        let collected = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let two = try collectChapters(
            after: collected,
            titlesAndContents: [("山呼", "第二章。")]
        )
        let edited = try NovelBranchTestFixtures.saveManualEdit(
            in: two,
            branchID: two.branches[0].id,
            content: "手改后还没同步。"
        )
        let failure = NovelBranchSemantics.recentChapterRevertPlan(
            chapterCount: 1,
            branch: edited.branches[0],
            document: edited
        )
        XCTAssertEqual(failure, .failure(.workingDiverged))
    }

    func testPlanRejectsCountPastWorkingManuscript() throws {
        let collected = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let failure = NovelBranchSemantics.recentChapterRevertPlan(
            chapterCount: 2,
            branch: collected.branches[0],
            document: collected
        )
        XCTAssertEqual(failure, .failure(.notEnoughChapters))
    }

    func testGhostwriteSidecarDropsRevertedAutoCollectedChapters() throws {
        let first = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let two = try collectChapters(
            after: first,
            titlesAndContents: [("山呼", "第二章。")]
        )
        let secondCandidateID = try XCTUnwrap(two.candidates.last?.id)
        let firstCandidateID = try XCTUnwrap(first.candidates.first?.id)
        let startedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let record = NovelGhostwriteBatchProgressRecord(
            schemaVersion: NovelGhostwriteBatchProgressRecord.currentSchemaVersion,
            projectID: two.project.id,
            branchID: two.branches[0].id,
            phase: .paused,
            pauseReason: .batchCompleted,
            detailMessage: nil,
            candidateID: nil,
            chapterPlanDigest: nil,
            autoCollectedCandidateIDs: [firstCandidateID, secondCandidateID],
            startedAt: startedAt,
            updatedAt: startedAt,
            targetChapterCount: 5,
            completedChapterCount: 2,
            currentChapterIndex: 3,
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
        let plan = try NovelBranchSemantics.recentChapterRevertPlan(
            chapterCount: 1,
            branch: two.branches[0],
            document: two
        ).get()
        let undone = try undo(two, steps: plan.undoStepCount)
        let reconciled = record.reconciledAfterManuscriptRevert(
            document: undone,
            branch: undone.branches[0],
            now: startedAt.addingTimeInterval(10)
        )
        XCTAssertEqual(reconciled.autoCollectedCandidateIDs, [firstCandidateID])
        XCTAssertEqual(reconciled.completedChapterCount, 1)
        XCTAssertEqual(reconciled.pauseReason, .userPaused)
        XCTAssertEqual(reconciled.phase, .paused)
    }

    @MainActor
    func testPartialRevertStillReconcilesGhostwriteSidecar() async throws {
        let first = try NovelBranchTestFixtures.documentWithCollectedCandidate()
        let three = try collectChapters(
            after: first,
            titlesAndContents: [
                ("口中名", "第二章：城里有人叫他的名字。"),
                ("山呼", "第三章：山里有人应声。"),
            ]
        )
        let branch = three.branches[0]
        let working = NovelBranchSemantics.workingManuscriptChapters(
            branch: branch,
            document: three
        )
        let autoIDs = working.compactMap { chapter in
            three.chapterVersions.first(where: { $0.id == chapter.versionID })?.sourceCandidateID
        }
        XCTAssertEqual(autoIDs.count, 3)

        let repository = InMemoryNovelProjectRepository()
        _ = try await repository.createProject(three)
        let startedAt = Date(timeIntervalSince1970: 1_700_000_000)
        try await repository.saveGhostwriteBatchProgress(NovelGhostwriteBatchProgressRecord(
            schemaVersion: NovelGhostwriteBatchProgressRecord.currentSchemaVersion,
            projectID: three.project.id,
            branchID: branch.id,
            phase: .paused,
            pauseReason: .batchCompleted,
            detailMessage: nil,
            candidateID: nil,
            chapterPlanDigest: nil,
            autoCollectedCandidateIDs: autoIDs,
            startedAt: startedAt,
            updatedAt: startedAt,
            targetChapterCount: 5,
            completedChapterCount: 3,
            currentChapterIndex: 4,
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

        let creation = FailNthUndoNovelCreation(
            base: DefaultNovelCreation(repository: repository),
            failOnUndo: 2
        )
        let workspace = NovelCreationViewModel(creation: creation)
        await workspace.loadProjects(selecting: three.project.id)
        XCTAssertEqual(workspace.branchSnapshot?.branch.workingChapterSelections.count, 3)

        let plan = try NovelBranchSemantics.recentChapterRevertPlan(
            chapterCount: 2,
            branch: branch,
            document: three
        ).get()
        XCTAssertEqual(plan.undoStepCount, 2)
        let reverted = await workspace.revertRecentChapters(NovelManuscriptRevertProposal(
            chapterCount: plan.chapters.count,
            chapterIDs: plan.chapters.map(\.chapterID),
            chapterTitles: plan.chapters.map(\.title),
            chapterOrdinals: plan.chapters.map(\.ordinal),
            targetCheckpointID: plan.targetCheckpointID,
            expectedHeadRevision: branch.headRevision,
            expectedWorkingRevision: branch.workingRevision,
            reason: nil
        ))
        XCTAssertFalse(reverted)
        XCTAssertEqual(workspace.branchSnapshot?.branch.workingChapterSelections.count, 2)
        XCTAssertEqual(
            workspace.errorMessage?.contains("已回退 1/2"),
            true,
            workspace.errorMessage ?? "missing error"
        )

        let sidecar = try await repository.loadGhostwriteBatchProgress(
            projectID: three.project.id,
            branchID: branch.id
        )
        XCTAssertEqual(sidecar?.autoCollectedCandidateIDs, Array(autoIDs.prefix(2)))
        XCTAssertEqual(sidecar?.completedChapterCount, 2)
    }

    // MARK: - Fixtures

    private func collectChapters(
        after document: NovelProjectDocumentV1,
        titlesAndContents: [(String, String)]
    ) throws -> NovelProjectDocumentV1 {
        var current = document
        let branchID = current.branches[0].id
        for (title, content) in titlesAndContents {
            let generated = try NovelBranchTestFixtures.appendCompletedRun(
                to: current,
                branchID: branchID,
                kind: .prose,
                content: content
            )
            let candidateID = try XCTUnwrap(generated.candidateID)
            current = try NovelBranchTestFixtures.collectCandidate(
                candidateID,
                in: generated.document,
                title: title
            )
        }
        return current
    }

    private func undo(
        _ document: NovelProjectDocumentV1,
        steps: Int
    ) throws -> NovelProjectDocumentV1 {
        var current = document
        for _ in 0..<steps {
            let branch = current.branches[0]
            current = try NovelReducer.apply(
                .undoBranchHead(
                    NovelUndoBranchHeadCommand(
                        context: NovelBranchTestFixtures.mutationContext(
                            document: current,
                            branchID: branch.id
                        ),
                        projectID: current.project.id,
                        branchID: branch.id,
                        expectedWorkingRevision: branch.workingRevision
                    )
                ),
                to: current
            ).document
        }
        return current
    }

    private func workingTitles(in document: NovelProjectDocumentV1) -> [String] {
        NovelBranchSemantics.workingManuscriptChapters(
            branch: document.branches[0],
            document: document
        ).map(\.title)
    }
}

private actor FailNthUndoNovelCreation: NovelCreation {
    private let base: any NovelCreation
    private let failOnUndo: Int
    private var undoCount = 0

    init(base: any NovelCreation, failOnUndo: Int) {
        self.base = base
        self.failOnUndo = failOnUndo
    }

    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        try await base.snapshot(scope)
    }

    func perform(_ action: NovelAction) async throws -> NovelOutcome {
        if case .undoBranchHead = action {
            undoCount += 1
            if undoCount == failOnUndo {
                throw NovelError.projectBusy(action.projectID)
            }
        }
        return try await base.perform(action)
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

    func retryPendingTerminal(runID: NovelRunID) async throws {
        try await base.retryPendingTerminal(runID: runID)
    }

    func loadGhostwriteBatchProgress(
        projectID: NovelProjectID,
        branchID: NovelBranchID
    ) async throws -> NovelGhostwriteBatchProgressRecord? {
        try await base.loadGhostwriteBatchProgress(projectID: projectID, branchID: branchID)
    }

    func saveGhostwriteBatchProgress(_ record: NovelGhostwriteBatchProgressRecord) async throws {
        try await base.saveGhostwriteBatchProgress(record)
    }
}
