import Foundation
import XCTest
@testable import iosApp

final class NovelSessionReplayTests: XCTestCase {
    func testProjectionUsesOneStableAssistantRowFromStreamingThroughDurableTerminal() throws {
        let fixture = try makeFixture()
        let run = makeRun(fixture: fixture, kind: .discussion)
        let user = makeMessage(
            id: run.userMessageID,
            sequence: 0,
            role: .user,
            mode: .discussPlan,
            kind: .userInput,
            content: "接下来该怎么安排？",
            runID: run.id
        )
        var session = fixture.session
        session.messages = [user]
        let tail = NovelSessionTransientTail(
            run: run,
            content: "先让主角",
            renderRevision: 1,
            phase: .streaming
        )

        let streaming = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [run],
            tail: tail
        ))
        XCTAssertEqual(streaming.rows.map(\.id), [user.id, run.messageID])
        XCTAssertEqual(streaming.activeTailID, run.messageID)
        XCTAssertTrue(streaming.rows[1].isStreaming)

        let terminal = makeMessage(
            id: run.messageID,
            sequence: 1,
            role: .assistant,
            mode: .discussPlan,
            kind: .discussion,
            content: "先让主角发现失踪信件。",
            runID: run.id
        )
        session.messages.append(terminal)
        let durable = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [terminalRun(run)],
            tail: tail
        ))

        XCTAssertEqual(durable.rows.map(\.id), [user.id, terminal.id])
        XCTAssertNil(durable.activeTailID)
        XCTAssertFalse(durable.rows[1].isTransient)
        XCTAssertEqual(durable.rows[1].content, terminal.content)
        XCTAssertNotEqual(streaming.rows[1].digest, durable.rows[1].digest)
    }

    func testWholeChapterStreamOnlyInvalidatesTailAndRespectsHistoryPause() throws {
        let fixture = try makeFixture()
        var session = fixture.session
        session.messages = (0..<24).map { index in
            makeMessage(
                sequence: Int64(index),
                role: index.isMultiple(of: 2) ? .user : .assistant,
                mode: index.isMultiple(of: 2) ? .writeProse : .discussPlan,
                kind: index.isMultiple(of: 2) ? .userInput : .discussion,
                content: "历史消息 \(index)"
            )
        }
        let run = makeRun(fixture: fixture, kind: .prose, candidateID: NovelCandidateID())
        var baseline = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [run],
            tail: NovelSessionTransientTail(
                run: run,
                content: "",
                renderRevision: 0,
                phase: .waitingForFirstToken
            )
        ))
        let historicalDigests = baseline.rows.dropLast().map(\.digest)
        var priorTailDigest = try XCTUnwrap(baseline.rows.last?.digest)

        var follow = NovelSessionBottomFollowPolicy.reduce(
            state: NovelSessionBottomFollowState(),
            event: .initialRowsPresented(hasRows: true)
        ).state
        var chapter = ""
        for index in 1...240 {
            chapter += "第 \(index) 段，主角沿着线索继续前行。\n\n"
            let projected = NovelSessionPresentation.project(makeInput(
                fixture: fixture,
                session: session,
                runs: [run],
                tail: NovelSessionTransientTail(
                    run: run,
                    content: chapter,
                    renderRevision: UInt64(index),
                    phase: .streaming
                )
            ))
            XCTAssertEqual(projected.rows.dropLast().map(\.digest), historicalDigests)
            XCTAssertNotEqual(projected.rows.last?.digest, priorTailDigest)
            XCTAssertEqual(projected.rows.filter { $0.id == run.messageID }.count, 1)
            priorTailDigest = try XCTUnwrap(projected.rows.last?.digest)
            baseline = projected

            if index == 30 {
                follow = NovelSessionBottomFollowPolicy.reduce(
                    state: follow,
                    event: .userDragBegan(isAtBottom: false)
                ).state
            } else if index == 90 {
                follow = NovelSessionBottomFollowPolicy.reduce(
                    state: follow,
                    event: .explicitBottomRequested
                ).state
            }
            let transition = NovelSessionBottomFollowPolicy.reduce(
                state: follow,
                event: .streamDelta
            )
            if (30..<90).contains(index) {
                XCTAssertFalse(transition.commands.contains { command in
                    if case .followBottom = command { return true }
                    return false
                })
            } else {
                XCTAssertTrue(transition.commands.contains(.followBottom(animated: false)))
            }
            follow = transition.state
        }

        XCTAssertGreaterThan(baseline.rows.last?.content.count ?? 0, 4_000)
        XCTAssertEqual(follow.mode, .followingBottom)
    }

    func testCandidateActionGatesAndOnlyCandidateActionDigestChanges() throws {
        let fixture = try makeFixture()
        let discussion = makeMessage(
            sequence: 0,
            role: .assistant,
            mode: .discussPlan,
            kind: .discussion,
            content: "我们可以先埋下线索。"
        )
        let candidateID = NovelCandidateID()
        let prose = makeMessage(
            sequence: 1,
            role: .assistant,
            mode: .writeProse,
            kind: .proseCandidate,
            content: "雨落在空荡的车站。",
            candidateID: candidateID
        )
        var session = fixture.session
        session.messages = [discussion, prose]
        let candidate = makeCandidate(
            fixture: fixture,
            id: candidateID,
            sourceMessageID: prose.id,
            kind: .prose,
            status: .available,
            content: prose.content
        )
        let available = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate]
        ))
        XCTAssertTrue(available.rows[0].actions.isEmpty)
        XCTAssertEqual(
            available.rows[1].actions,
            [.init(action: .collectProse(candidateID), blocker: nil)]
        )

        let readOnly = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate],
            access: .degradedPrevious(primaryFailure: "corrupt")
        ))
        XCTAssertEqual(readOnly.rows[1].actions.first?.blocker, .projectReadOnly)

        var needsSyncBranch = fixture.branch
        needsSyncBranch.syncStatus = .needsSync
        let needsSync = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: needsSyncBranch,
            session: session,
            candidates: [candidate]
        ))
        XCTAssertNil(needsSync.rows[1].actions.first?.blocker)

        let activeRun = makeRun(fixture: fixture, kind: .discussion)
        var runningBranch = fixture.branch
        runningBranch.activeRunID = activeRun.id
        let running = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: runningBranch,
            session: session,
            candidates: [candidate],
            runs: [activeRun]
        ))
        XCTAssertEqual(running.rows[1].actions.first?.blocker, .generationRunning)

        let startingRun = makeRun(fixture: fixture, kind: .discussion)
        let starting = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate],
            tail: NovelSessionTransientTail(
                run: startingRun,
                content: "",
                renderRevision: 0,
                phase: .waitingForFirstToken
            )
        ))
        XCTAssertEqual(
            starting.rows[1].actions.first?.blocker,
            .generationRunning,
            "The pre-durable live tail must block history actions without disabling the entire Markdown row tree."
        )

        let pending = makePending(fixture: fixture, candidateID: candidateID, status: .retryable)
        let retryable = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate],
            pending: [pending]
        ))
        XCTAssertEqual(
            retryable.rows[1].actions,
            [.init(action: .retryPending(pending.id), blocker: nil)]
        )
        XCTAssertEqual(retryable.rows[0].digest, available.rows[0].digest)
        XCTAssertNotEqual(retryable.rows[1].digest, available.rows[1].digest)

        var discoveredPending = pending
        discoveredPending.status = .pending
        let resumableAfterRestart = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate],
            pending: [discoveredPending]
        ))
        XCTAssertEqual(resumableAfterRestart.rows[1].actions, [
            .init(action: .retryPending(discoveredPending.id), blocker: nil)
        ])

        var staleBranch = fixture.branch
        staleBranch.headRevision += 1
        let stale = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: staleBranch,
            session: session,
            candidates: [candidate]
        ))
        XCTAssertEqual(stale.rows[1].actions.first?.blocker, .staleCandidate)
    }

    func testCollectedCandidateProjectsCommittedStateChangeAndForkAction() throws {
        let fixture = try makeFixture()
        let candidateID = NovelCandidateID()
        let message = makeMessage(
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .proseCandidate,
            content: "她终于拆开了信。",
            candidateID: candidateID
        )
        let event = NovelStoryEventRecord(
            id: NovelEventID(),
            sequence: 0,
            kind: "discovery",
            summary: "主角发现失踪信件",
            entityReferences: ["主角"],
            createdAt: Self.now
        )
        let state = NovelStateSnapshotRecord(
            id: NovelStateSnapshotID(),
            eventIDs: [event.id],
            summary: "主角掌握了第一条线索。",
            branchOutline: "追查寄信人",
            unresolvedEntityNames: ["寄信人"],
            createdAt: Self.now
        )
        let checkpoint = NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .collection,
            createdOnBranchID: fixture.branch.id,
            parentCheckpointID: fixture.branch.headCheckpointID,
            chapterSelections: [],
            stateSnapshotID: state.id,
            sessionCursor: .through(sequence: 0),
            branchOverrideRevisionIDs: [],
            sourceCandidateID: candidateID,
            baseHeadRevision: fixture.branch.headRevision,
            operationID: NovelOperationID(),
            createdAt: Self.now
        )
        let candidate = makeCandidate(
            fixture: fixture,
            id: candidateID,
            sourceMessageID: message.id,
            kind: .prose,
            status: .collected,
            content: message.content,
            collectedCheckpointID: checkpoint.id
        )
        var session = fixture.session
        session.messages = [message]
        let projected = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate],
            checkpoints: fixture.document.checkpoints + [checkpoint],
            states: fixture.document.stateSnapshots + [state],
            events: [event]
        ))

        XCTAssertEqual(projected.rows[0].committedChange?.stateSummary, state.summary)
        XCTAssertEqual(projected.rows[0].committedChange?.eventSummaries ?? [], [event.summary])
        XCTAssertTrue(projected.rows[0].actions.contains(
            NovelSessionRowActionAvailability(
                action: .forkFromCheckpoint(checkpoint.id),
                blocker: nil
            )
        ))

        var headBranch = fixture.branch
        headBranch.headCheckpointID = checkpoint.id
        headBranch.headRevision += 1
        let atHead = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: headBranch,
            session: session,
            candidates: [candidate],
            checkpoints: fixture.document.checkpoints + [checkpoint],
            states: fixture.document.stateSnapshots + [state],
            events: [event]
        ))
        XCTAssertTrue(atHead.rows[0].actions.contains(
            NovelSessionRowActionAvailability(
                action: .undoCommittedChange(checkpointID: checkpoint.id, kind: .prose),
                blocker: nil
            )
        ))

        headBranch.syncStatus = .needsSync
        let needsSync = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: headBranch,
            session: session,
            candidates: [candidate],
            checkpoints: fixture.document.checkpoints + [checkpoint],
            states: fixture.document.stateSnapshots + [state],
            events: [event]
        ))
        XCTAssertEqual(
            needsSync.rows[0].actions.first(where: {
                if case .undoCommittedChange = $0.action { return true }
                return false
            })?.blocker,
            .branchNeedsSync
        )
    }

    func testQuickStartMessageLinksToItsUnresolvedSettingProposals() throws {
        let fixture = try makeFixture()
        let run = makeRun(fixture: fixture, kind: .quickStart)
        let message = makeMessage(
            id: run.messageID,
            sequence: 0,
            role: .assistant,
            mode: .discussPlan,
            kind: .discussion,
            content: "创作建议",
            runID: run.id
        )
        let proposal = NovelSettingProposalRecord(
            id: NovelProposalID(),
            branchID: fixture.branch.id,
            title: "角色",
            content: "角色建议",
            createdAt: Self.now,
            isResolved: false,
            origin: .quickStart(runID: run.id, suggestedKind: .character)
        )
        var session = fixture.session
        session.messages = [message]

        let projected = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [run],
            proposals: [proposal]
        ))

        XCTAssertEqual(
            projected.rows[0].actions,
            [NovelSessionRowActionAvailability(
                action: .viewSettingProposals(.characters),
                blocker: nil
            )]
        )
    }

    func testPolishActionsExposeDriftManualRewriteAndAbandonPaths() throws {
        let fixture = try makeFixture()
        let sourceVersionID = NovelChapterVersionID()
        var branch = fixture.branch
        branch.workingChapterSelections = [NovelChapterSelection(
            chapterID: NovelChapterID(),
            versionID: sourceVersionID
        )]
        let candidateID = NovelCandidateID()
        let message = makeMessage(
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .polishCandidate,
            content: "润色后的完整章节",
            candidateID: candidateID
        )
        var session = fixture.session
        session.messages = [message]
        let availableCandidate = makeCandidate(
            fixture: fixture,
            id: candidateID,
            sourceMessageID: message.id,
            kind: .polish,
            status: .available,
            content: message.content,
            sourceVersionID: sourceVersionID
        )
        let available = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: branch,
            session: session,
            candidates: [availableCandidate]
        ))
        XCTAssertEqual(
            available.rows[0].actions,
            [.init(action: .adoptPolish(candidateID), blocker: nil)]
        )

        let retryableTransaction = makePolishTransaction(
            fixture: fixture,
            candidate: availableCandidate,
            status: .retryable
        )
        let retryable = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: branch,
            session: session,
            candidates: [availableCandidate],
            polish: [retryableTransaction]
        ))
        XCTAssertEqual(retryable.rows[0].actions.map(\.action), [
            .retryPolish(retryableTransaction.id),
            .abandonPolish(retryableTransaction.id)
        ])
        XCTAssertTrue(retryable.rows[0].actions.allSatisfy(\.isEnabled))

        var incompatibleCandidate = availableCandidate
        incompatibleCandidate.status = .superseded
        var incompatibleTransaction = retryableTransaction
        incompatibleTransaction.status = .incompatible
        let incompatible = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: branch,
            session: session,
            candidates: [incompatibleCandidate],
            polish: [incompatibleTransaction]
        ))
        XCTAssertEqual(incompatible.rows[0].candidate?.polishTransactionStatus, .incompatible)
        XCTAssertEqual(incompatible.rows[0].actions, [
            .init(
                action: .convertPolishToManualRewrite(
                    candidateID: candidateID,
                    sourceChapterVersionID: sourceVersionID
                ),
                blocker: nil
            )
        ])

        var changedSourceBranch = branch
        changedSourceBranch.workingChapterSelections = []
        let staleManualRewrite = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: changedSourceBranch,
            session: session,
            candidates: [incompatibleCandidate],
            polish: [incompatibleTransaction]
        ))
        XCTAssertEqual(staleManualRewrite.rows[0].actions.first?.blocker, .sourceChapterChanged)

        var blockedTransaction = retryableTransaction
        blockedTransaction.status = .blocked
        let blocked = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: branch,
            session: session,
            candidates: [availableCandidate],
            polish: [blockedTransaction]
        ))
        XCTAssertEqual(
            blocked.rows[0].actions,
            [.init(action: .abandonPolish(blockedTransaction.id), blocker: nil)]
        )
    }

    func testInterruptedAndErrorRowsExposeRetryAvailability() throws {
        let fixture = try makeFixture()
        let retryableRun = terminalRun(makeRun(fixture: fixture, kind: .prose))
        let failedRun = terminalRun(
            makeRun(fixture: fixture, kind: .discussion),
            status: .failed,
            failure: NovelFailure(code: "bad_request", message: "Bad request", isRetryable: false)
        )
        let interrupted = makeMessage(
            id: retryableRun.messageID,
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .interruptedDraft,
            content: "保留下来的草稿",
            runID: retryableRun.id
        )
        let error = makeMessage(
            id: failedRun.messageID,
            sequence: 1,
            role: .assistant,
            mode: .discussPlan,
            kind: .error,
            content: "Bad request",
            runID: failedRun.id
        )
        var session = fixture.session
        session.messages = [interrupted, error]
        let projected = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [retryableRun, failedRun]
        ))

        XCTAssertEqual(projected.rows[0].actions, [
            .init(action: .retryGeneration(retryableRun.id), blocker: nil)
        ])
        XCTAssertEqual(projected.rows[1].actions, [
            .init(action: .retryGeneration(failedRun.id), blocker: .failureNotRetryable)
        ])
        XCTAssertEqual(projected.rows[1].content, "生成没有完成，请检查项目模型或输入后重试。")

        let retryablePartialFailure = terminalRun(
            makeRun(fixture: fixture, kind: .discussion),
            status: .failed,
            failure: NovelFailure(code: "network", message: "断线", isRetryable: true)
        )
        let nonretryablePartialFailure = terminalRun(
            makeRun(fixture: fixture, kind: .discussion),
            status: .failed,
            failure: NovelFailure(code: "invalid", message: "请求无效", isRetryable: false)
        )
        session.messages = [
            makeMessage(
                id: retryablePartialFailure.messageID,
                sequence: 0,
                role: .assistant,
                mode: .discussPlan,
                kind: .interruptedDraft,
                content: "保留的部分回复",
                runID: retryablePartialFailure.id
            ),
            makeMessage(
                id: nonretryablePartialFailure.messageID,
                sequence: 1,
                role: .assistant,
                mode: .discussPlan,
                kind: .interruptedDraft,
                content: "另一段部分回复",
                runID: nonretryablePartialFailure.id
            )
        ]
        let partialFailures = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [retryablePartialFailure, nonretryablePartialFailure]
        ))
        XCTAssertEqual(partialFailures.rows.map(\.runStatus), [.failed, .failed])
        XCTAssertEqual(partialFailures.rows[0].actions, [
            .init(action: .retryGeneration(retryablePartialFailure.id), blocker: nil)
        ])
        XCTAssertEqual(partialFailures.rows[1].actions, [
            .init(
                action: .retryGeneration(nonretryablePartialFailure.id),
                blocker: .failureNotRetryable
            )
        ])

        let quickStartFailure = NovelFailure(
            code: "schema",
            message: "创作建议格式无效",
            isRetryable: true
        )
        let failedQuickStart = terminalRun(
            makeRun(fixture: fixture, kind: .quickStart),
            status: .failed,
            failure: quickStartFailure
        )
        session.messages = [makeMessage(
            id: failedQuickStart.messageID,
            sequence: 0,
            role: .assistant,
            mode: .discussPlan,
            kind: .interruptedDraft,
            content: #"{"world":"half-written""#,
            runID: failedQuickStart.id
        )]
        let hiddenStructuredPartial = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [failedQuickStart]
        ))
        XCTAssertEqual(hiddenStructuredPartial.rows[0].content, quickStartFailure.message)

        session.messages = [interrupted, error]

        var advancedBranch = fixture.branch
        advancedBranch.headRevision += 1
        let staleRetry = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: advancedBranch,
            session: session,
            runs: [retryableRun, failedRun]
        ))
        XCTAssertEqual(staleRetry.rows[0].actions, [
            .init(action: .retryGeneration(retryableRun.id), blocker: .staleCandidate)
        ])

        let oldSourceVersionID = NovelChapterVersionID()
        let polishRun = terminalRun(makeRun(
            fixture: fixture,
            kind: .polish,
            sourceVersionID: oldSourceVersionID
        ))
        let polishMessage = makeMessage(
            id: polishRun.messageID,
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .interruptedDraft,
            content: "旧润色草稿",
            runID: polishRun.id
        )
        session.messages = [polishMessage]
        var sourceChangedBranch = fixture.branch
        sourceChangedBranch.workingChapterSelections = [NovelChapterSelection(
            chapterID: NovelChapterID(),
            versionID: NovelChapterVersionID()
        )]
        let sourceChanged = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: sourceChangedBranch,
            session: session,
            runs: [polishRun]
        ))
        XCTAssertEqual(sourceChanged.rows[0].actions, [
            .init(action: .retryGeneration(polishRun.id), blocker: .sourceChapterChanged)
        ])
    }

    func testZeroTokenInterruptedRunSurvivesDurableRefreshAndReopen() throws {
        let fixture = try makeFixture()
        let interruptedRun = terminalRun(
            makeRun(fixture: fixture, kind: .prose),
            status: .interrupted
        )
        let user = makeMessage(
            id: interruptedRun.userMessageID,
            sequence: 0,
            role: .user,
            mode: .writeProse,
            kind: .userInput,
            content: "写这一章",
            runID: interruptedRun.id
        )
        var session = fixture.session
        session.messages = [user]

        let afterRefresh = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [interruptedRun]
        ))
        let afterReopen = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [interruptedRun],
            tail: nil
        ))

        XCTAssertEqual(afterRefresh, afterReopen)
        XCTAssertNil(afterReopen.activeTailID)
        XCTAssertEqual(afterReopen.rows.map(\.id), [user.id, interruptedRun.messageID])
        XCTAssertEqual(afterReopen.rows[1].kind, .interruptedDraft)
        XCTAssertEqual(afterReopen.rows[1].content, "")
        XCTAssertEqual(afterReopen.rows[1].actions, [
            .init(action: .retryGeneration(interruptedRun.id), blocker: nil)
        ])
    }

    func testTransientTerminalPhasesStopStreamingAndExposeExactRetry() throws {
        let fixture = try makeFixture()
        let run = makeRun(fixture: fixture, kind: .prose, candidateID: NovelCandidateID())
        let streamingTail = NovelSessionTransientTail(
            run: run,
            content: "第一段",
            renderRevision: 1,
            phase: .streaming
        )
        let streaming = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            runs: [run],
            tail: streamingTail
        )).rows[0]
        XCTAssertTrue(streaming.isStreaming)
        XCTAssertEqual(streaming.kind, .proseCandidate)

        let awaiting = streamingTail.updating(
            content: "完整章节",
            phase: .terminalAwaitingRefresh
        )
        let awaitingRow = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            runs: [run],
            tail: awaiting
        )).rows[0]
        XCTAssertFalse(awaitingRow.isStreaming)
        XCTAssertEqual(awaitingRow.kind, .proseCandidate)
        XCTAssertTrue(awaitingRow.actions.isEmpty)
        XCTAssertNotEqual(awaitingRow.digest, streaming.digest)

        let interrupted = awaiting.updating(content: "保留的部分", phase: .interrupted)
        let interruptedRow = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            runs: [run],
            tail: interrupted
        )).rows[0]
        XCTAssertFalse(interruptedRow.isStreaming)
        XCTAssertEqual(interruptedRow.kind, .interruptedDraft)
        XCTAssertEqual(interruptedRow.actions, [
            .init(action: .retryGeneration(run.id), blocker: nil)
        ])

        let failure = NovelFailure(code: "network", message: "断线", isRetryable: true)
        let failed = interrupted.updating(content: "", phase: .failed(failure))
        let failedRow = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            runs: [run],
            tail: failed
        )).rows[0]
        XCTAssertFalse(failedRow.isStreaming)
        XCTAssertEqual(failedRow.kind, .error)
        XCTAssertEqual(failedRow.actions, [
            .init(action: .retryGeneration(run.id), blocker: nil)
        ])
    }

    func testBottomFollowIgnoresStaleQuietTimerAndNeverPullsHistory() {
        var state = NovelSessionBottomFollowState()
        var transition = NovelSessionBottomFollowPolicy.reduce(
            state: state,
            event: .initialRowsPresented(hasRows: true)
        )
        XCTAssertEqual(transition.commands, [.anchorBottom])
        state = transition.state

        transition = NovelSessionBottomFollowPolicy.reduce(
            state: state,
            event: .viewportChanged(isAtBottom: false)
        )
        XCTAssertEqual(transition.state.mode, .followingBottom)
        XCTAssertEqual(transition.commands, [.followBottom(animated: false)])
        state = transition.state

        state = NovelSessionBottomFollowPolicy.reduce(
            state: state,
            event: .userDragEnded(isAtBottom: false)
        ).state
        XCTAssertEqual(state.mode, .browsingHistory)
        transition = NovelSessionBottomFollowPolicy.reduce(state: state, event: .streamDelta)
        XCTAssertTrue(transition.commands.isEmpty)
        transition = NovelSessionBottomFollowPolicy.reduce(
            state: transition.state,
            event: .viewportChanged(isAtBottom: true)
        )
        XCTAssertEqual(transition.state.mode, .browsingHistory)
        XCTAssertFalse(transition.state.showsBottomButton)
        XCTAssertFalse(transition.commands.contains { command in
            if case .followBottom = command { return true }
            return false
        })

        transition = NovelSessionBottomFollowPolicy.reduce(
            state: transition.state,
            event: .explicitBottomRequested
        )
        XCTAssertEqual(transition.state.mode, .followingBottom)
        XCTAssertTrue(transition.commands.contains(.followBottom(animated: true)))

        let firstTerminal = NovelSessionBottomFollowPolicy.reduce(
            state: transition.state,
            event: .terminalReached
        )
        guard case .settlingTerminal(let firstToken) = firstTerminal.state.mode else {
            return XCTFail("Expected terminal settle mode")
        }
        let lateGrowth = NovelSessionBottomFollowPolicy.reduce(
            state: firstTerminal.state,
            event: .terminalLayoutChanged
        )
        guard case .settlingTerminal(let secondToken) = lateGrowth.state.mode else {
            return XCTFail("Expected restarted terminal settle mode")
        }
        XCTAssertGreaterThan(secondToken, firstToken)
        let staleTimer = NovelSessionBottomFollowPolicy.reduce(
            state: lateGrowth.state,
            event: .terminalQuietElapsed(token: firstToken)
        )
        XCTAssertEqual(staleTimer.state.mode, .settlingTerminal(token: secondToken))
        XCTAssertTrue(staleTimer.commands.isEmpty)
        let settled = NovelSessionBottomFollowPolicy.reduce(
            state: staleTimer.state,
            event: .terminalQuietElapsed(token: secondToken)
        )
        XCTAssertEqual(settled.state.mode, .followingBottom)
        XCTAssertEqual(settled.commands, [.followBottom(animated: false)])

        let postQuietGrowth = NovelSessionBottomFollowPolicy.reduce(
            state: settled.state,
            event: .terminalLayoutChanged
        )
        guard case .settlingTerminal(let thirdToken) = postQuietGrowth.state.mode else {
            return XCTFail("Expected late Markdown growth to restart terminal settling")
        }
        XCTAssertGreaterThan(thirdToken, secondToken)
        XCTAssertTrue(postQuietGrowth.commands.contains(.followBottom(animated: false)))
        XCTAssertTrue(postQuietGrowth.commands.contains(
            .scheduleTerminalQuietSettle(
                token: thirdToken,
                delay: NovelSessionBottomFollowPolicy.terminalQuietDelay
            )
        ))
    }

    func testSessionViewFeedsMeasuredTerminalContentGrowthIntoFollowPolicy() throws {
        let iosRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let source = try String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/NovelCreation/NovelSessionView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("contentHeight: geometry.contentSize.height"))
        XCTAssertTrue(source.contains("abs(oldValue.contentHeight - newValue.contentHeight) > 0.5"))
        XCTAssertTrue(source.contains("dispatchFollowEvent(.terminalLayoutChanged)"))
        XCTAssertTrue(source.contains("!viewModel.branchPendingOperations.isEmpty"))
    }

}

private extension NovelSessionReplayTests {
    static let now = Date(timeIntervalSince1970: 1_710_000_000)

    struct Fixture {
        let document: NovelProjectDocumentV1
        let branch: NovelBranchRecord
        let session: NovelSessionRecord
    }

    func makeFixture() throws -> Fixture {
        let document = try NovelTestFixtures.document(now: Self.now)
        return Fixture(
            document: document,
            branch: try XCTUnwrap(document.branches.first),
            session: try XCTUnwrap(document.sessions.first)
        )
    }

    func makeInput(
        fixture: Fixture,
        branch: NovelBranchRecord? = nil,
        session: NovelSessionRecord? = nil,
        candidates: [NovelCandidateRecord] = [],
        runs: [NovelActiveRunRecord] = [],
        pending: [NovelPendingOperationRecord] = [],
        polish: [NovelPendingPolishTransactionRecord] = [],
        checkpoints: [NovelBranchCheckpointRecord]? = nil,
        states: [NovelStateSnapshotRecord]? = nil,
        events: [NovelStoryEventRecord] = [],
        proposals: [NovelSettingProposalRecord] = [],
        access: NovelProjectLoadAccess = .readWrite,
        tail: NovelSessionTransientTail? = nil
    ) -> NovelSessionProjectionInput {
        NovelSessionProjectionInput(
            branch: branch ?? fixture.branch,
            session: session ?? fixture.session,
            candidates: candidates,
            runs: runs,
            pendingOperations: pending,
            polishTransactions: polish,
            checkpoints: checkpoints ?? fixture.document.checkpoints,
            stateSnapshots: states ?? fixture.document.stateSnapshots,
            events: events,
            settingProposals: proposals,
            access: access,
            transientTail: tail
        )
    }

    func makeMessage(
        id: NovelMessageID = NovelMessageID(),
        sequence: Int64,
        role: NovelSessionRole,
        mode: NovelSessionMode,
        kind: NovelSessionMessageKind,
        content: String,
        runID: NovelRunID? = nil,
        candidateID: NovelCandidateID? = nil
    ) -> NovelSessionMessageRecord {
        NovelSessionMessageRecord(
            id: id,
            sequence: sequence,
            role: role,
            mode: mode,
            kind: kind,
            content: content,
            createdAt: Self.now,
            runID: runID,
            candidateID: candidateID
        )
    }

    func makeRun(
        fixture: Fixture,
        kind: NovelRunKind,
        candidateID: NovelCandidateID? = nil,
        sourceVersionID: NovelChapterVersionID? = nil
    ) -> NovelActiveRunRecord {
        NovelActiveRunRecord(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            requestPayloadSHA256: NovelTestFixtures.hashA,
            branchID: fixture.branch.id,
            sessionID: fixture.session.id,
            kind: kind,
            mode: kind == .discussion || kind == .quickStart ? .discussPlan : .writeProse,
            granularity: kind == .prose ? .wholeChapter : nil,
            userMessageID: NovelMessageID(),
            messageID: NovelMessageID(),
            candidateID: candidateID,
            sourceChapterVersionID: sourceVersionID,
            baseCheckpointID: fixture.branch.headCheckpointID,
            baseHeadRevision: fixture.branch.headRevision,
            status: .running,
            partialContent: "",
            receiptID: NovelReceiptID(),
            startedAt: Self.now,
            terminalAt: nil,
            interruptionReason: nil,
            terminalFailure: nil
        )
    }

    func terminalRun(
        _ run: NovelActiveRunRecord,
        status: NovelRunStatus = .completed,
        failure: NovelFailure? = nil
    ) -> NovelActiveRunRecord {
        NovelActiveRunRecord(
            id: run.id,
            operationID: run.operationID,
            requestPayloadSHA256: run.requestPayloadSHA256,
            branchID: run.branchID,
            sessionID: run.sessionID,
            kind: run.kind,
            mode: run.mode,
            granularity: run.granularity,
            userMessageID: run.userMessageID,
            messageID: run.messageID,
            candidateID: run.candidateID,
            sourceChapterVersionID: run.sourceChapterVersionID,
            baseCheckpointID: run.baseCheckpointID,
            baseHeadRevision: run.baseHeadRevision,
            status: status,
            partialContent: run.partialContent,
            receiptID: run.receiptID,
            startedAt: run.startedAt,
            terminalAt: Self.now,
            interruptionReason: status == .interrupted ? .user : nil,
            terminalFailure: failure
        )
    }

    func makeCandidate(
        fixture: Fixture,
        id: NovelCandidateID,
        sourceMessageID: NovelMessageID,
        kind: NovelCandidateKind,
        status: NovelCandidateStatus,
        content: String,
        sourceVersionID: NovelChapterVersionID? = nil,
        collectedCheckpointID: NovelCheckpointID? = nil
    ) -> NovelCandidateRecord {
        NovelCandidateRecord(
            id: id,
            kind: kind,
            branchID: fixture.branch.id,
            sessionID: fixture.session.id,
            sourceMessageID: sourceMessageID,
            baseCheckpointID: fixture.branch.headCheckpointID,
            baseHeadRevision: fixture.branch.headRevision,
            status: status,
            content: content,
            sourceChapterVersionID: sourceVersionID,
            collectedCheckpointID: collectedCheckpointID,
            createdAt: Self.now
        )
    }

    func makePending(
        fixture: Fixture,
        candidateID: NovelCandidateID,
        status: NovelPendingOperationStatus
    ) -> NovelPendingOperationRecord {
        NovelPendingOperationRecord(
            id: NovelPendingOperationID(),
            kind: .collection,
            status: status,
            branchID: fixture.branch.id,
            operationID: NovelOperationID(),
            payloadSHA256: NovelTestFixtures.hashA,
            baseCheckpointID: fixture.branch.headCheckpointID,
            baseHeadRevision: fixture.branch.headRevision,
            candidateID: candidateID,
            collectionTarget: nil,
            selectedText: "text",
            proposedChapterVersion: nil,
            createdAt: Self.now,
            lastError: status == .retryable ? "retry" : nil
        )
    }

    func makePolishTransaction(
        fixture: Fixture,
        candidate: NovelCandidateRecord,
        status: NovelPolishTransactionStatus
    ) -> NovelPendingPolishTransactionRecord {
        NovelPendingPolishTransactionRecord(
            id: NovelPendingOperationID(),
            operationID: NovelOperationID(),
            payloadSHA256: NovelTestFixtures.hashA,
            branchID: fixture.branch.id,
            candidateID: candidate.id,
            sourceChapterVersionID: candidate.sourceChapterVersionID ?? NovelChapterVersionID(),
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            baseCheckpointID: candidate.baseCheckpointID,
            baseHeadRevision: candidate.baseHeadRevision,
            baseWorkingRevision: fixture.branch.workingRevision,
            sessionCursor: .through(sequence: 0),
            sourceContentSHA256: NovelTestFixtures.hashA,
            candidateContentSHA256: NovelTestFixtures.hashB,
            createdAt: Self.now,
            status: status,
            attemptCount: 1,
            lastFailure: status == .retryable
                ? NovelFailure(code: "retry", message: "Retry", isRetryable: true)
                : nil,
            lastFailureAttemptIndex: status == .retryable ? 0 : nil
        )
    }
}
