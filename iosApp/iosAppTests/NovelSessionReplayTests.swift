import Foundation
import XCTest
@testable import iosApp

final class NovelSessionReplayTests: XCTestCase {
    func testPresentationBufferPreservesFIFOReplacementAndRunIdentity() {
        let runID = NovelRunID()
        let messageID = NovelMessageID()
        let bindingToken = UUID()
        var buffer = NovelSessionPresentationBuffer(
            runID: runID,
            messageID: messageID,
            bindingToken: bindingToken,
            baseContent: "已显示"
        )

        buffer.append("旧前缀")
        buffer.replace(with: "完整替换")
        buffer.append("与后续")

        XCTAssertEqual(buffer.targetContent, "完整替换与后续")
        XCTAssertTrue(buffer.matches(
            runID: runID,
            messageID: messageID,
            bindingToken: bindingToken
        ))
        XCTAssertFalse(buffer.matches(
            runID: NovelRunID(),
            messageID: messageID,
            bindingToken: bindingToken
        ))
    }

    func testPresentationPacerSplitsLightBacklogIntoBoundedSteps() {
        let target = "已显示" + String(repeating: "字", count: 36)
        var displayed = "已显示"
        var steps = 0
        var intermediateAdvances: [Int] = []

        while displayed != target {
            let step = NovelSessionPresentationPacer.step(
                displayedContent: displayed,
                targetContent: target
            )
            let advanced = step.content.count - displayed.count
            intermediateAdvances.append(advanced)
            displayed = step.content
            steps += 1
            XCTAssertLessThanOrEqual(
                advanced,
                NovelSessionPresentationPacer.maximumTextAdvance
            )
            XCTAssertGreaterThan(advanced, 0)
            if steps > 20 {
                return XCTFail("Pacer failed to catch up within expected ticks.")
            }
        }

        XCTAssertGreaterThan(steps, 1, "A multi-line backlog must not publish in one frame.")
        XCTAssertEqual(
            intermediateAdvances.dropLast().allSatisfy {
                $0 >= NovelSessionPresentationPacer.minimumTextAdvance
            },
            true
        )
        XCTAssertEqual(displayed, target)
    }

    func testPresentationPacerAcceleratesWithLargeBacklogButStaysCapped() {
        let largeBacklog = 2_000
        XCTAssertEqual(
            NovelSessionPresentationPacer.textAdvance(backlogCount: 8),
            NovelSessionPresentationPacer.minimumTextAdvance
        )
        XCTAssertEqual(
            NovelSessionPresentationPacer.textAdvance(backlogCount: largeBacklog),
            NovelSessionPresentationPacer.maximumTextAdvance
        )

        let target = String(repeating: "章", count: largeBacklog)
        let first = NovelSessionPresentationPacer.step(
            displayedContent: "",
            targetContent: target
        )
        XCTAssertEqual(first.content.count, NovelSessionPresentationPacer.maximumTextAdvance)
        XCTAssertFalse(first.isCaughtUp)

        // Drain-window adaptive: backlog of preferredDrainTicks * minAdvance stays at floor.
        let light = NovelSessionPresentationPacer.preferredDrainTicks
            * NovelSessionPresentationPacer.minimumTextAdvance
        XCTAssertEqual(
            NovelSessionPresentationPacer.textAdvance(backlogCount: light),
            NovelSessionPresentationPacer.minimumTextAdvance
        )
    }

    func testPresentationPacerSnapsOnNonPrefixReplacement() {
        let step = NovelSessionPresentationPacer.step(
            displayedContent: "应被替换的旧文",
            targetContent: "最终前缀与结尾"
        )
        XCTAssertEqual(step.content, "最终前缀与结尾")
        XCTAssertTrue(step.isCaughtUp)
    }

    func testDragResumeUsesSharedNearBottomThreshold() {
        let threshold = ChatLayout.nearBottomResumeThreshold

        XCTAssertEqual(threshold, 96)
        XCTAssertGreaterThan(threshold, ChatLayout.bottomStickThreshold)
        XCTAssertTrue(NovelSessionBottomProximityPolicy.isNearBottom(
            distanceToBottom: threshold
        ))
        XCTAssertFalse(NovelSessionBottomProximityPolicy.isNearBottom(
            distanceToBottom: threshold + 0.5
        ))
    }

    func testLongHistoryWindowStartsAtRecentRowsAndExpandsInBoundedPages() {
        XCTAssertEqual(
            NovelSessionHistoryWindowPolicy.startIndex(
                totalCount: 160,
                limit: NovelSessionHistoryWindowPolicy.initialLimit
            ),
            156
        )
        XCTAssertEqual(
            NovelSessionHistoryWindowPolicy.expandedLimit(currentLimit: 4, totalCount: 160),
            28
        )
        XCTAssertEqual(
            NovelSessionHistoryWindowPolicy.expandedLimit(currentLimit: 4, totalCount: 20),
            20
        )
        XCTAssertEqual(
            NovelSessionHistoryWindowPolicy.startIndex(totalCount: 4, limit: 4),
            0
        )
    }

    func testDiscussionArchiveCardCountsAsOneRowAndExpansionPreservesRawRowIdentity() throws {
        let fixture = try makeFixture()
        let messages = (0..<4).map { index in
            makeMessage(
                sequence: Int64(index),
                role: index.isMultiple(of: 2) ? .user : .assistant,
                mode: .discussPlan,
                kind: index.isMultiple(of: 2) ? .userInput : .discussion,
                content: "讨论 \(index)"
            )
        }
        let archiveID = NovelMessageID()
        var session = fixture.session
        session.messages = messages
        session.archiveCursor = .through(sequence: 1)
        session.discussionArchives = [NovelDiscussionArchiveRecord(
            id: archiveID,
            checkpointID: fixture.branch.headCheckpointID,
            throughSequence: 1,
            messageCount: 2,
            chapterID: nil,
            summary: "确认主角在第三章末揭示身世。",
            createdAt: Self.now
        )]

        let collapsed = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session
        ))
        XCTAssertEqual(collapsed.rows.map(\.id), [archiveID, messages[2].id, messages[3].id])
        XCTAssertEqual(collapsed.historicalRows.count, 3)
        XCTAssertEqual(collapsed.rows.first?.archive?.messageCount, 2)
        XCTAssertEqual(collapsed.rows.first?.archive?.isExpanded, false)

        let expanded = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            expandedArchiveIDs: [archiveID]
        ))
        XCTAssertEqual(
            expanded.rows.map(\.id),
            [archiveID, messages[0].id, messages[1].id, messages[2].id, messages[3].id]
        )
        XCTAssertEqual(expanded.rows.first?.archive?.isExpanded, true)
        XCTAssertEqual(collapsed.rows.first?.digest.layout, expanded.rows.first?.digest.layout)
        XCTAssertNotEqual(
            collapsed.rows.first?.digest.presentation,
            expanded.rows.first?.digest.presentation
        )
    }

    func testCollapsedArchiveKeepsUncollectedProseVisibleForCollect() throws {
        let fixture = try makeFixture()
        let candidateID = NovelCandidateID()
        let discussionUser = makeMessage(
            sequence: 0,
            role: .user,
            mode: .discussPlan,
            kind: .userInput,
            content: "先讨论再写"
        )
        let discussionAssistant = makeMessage(
            sequence: 1,
            role: .assistant,
            mode: .discussPlan,
            kind: .discussion,
            content: "同意先埋伏笔"
        )
        let proseMessage = makeMessage(
            sequence: 2,
            role: .assistant,
            mode: .writeProse,
            kind: .proseCandidate,
            content: "未收录正文仍应可点收录",
            candidateID: candidateID
        )
        let laterDiscussion = makeMessage(
            sequence: 3,
            role: .user,
            mode: .discussPlan,
            kind: .userInput,
            content: "就按这个定"
        )
        let candidate = makeCandidate(
            fixture: fixture,
            id: candidateID,
            sourceMessageID: proseMessage.id,
            kind: .prose,
            status: .interrupted,
            content: proseMessage.content
        )
        let archiveID = NovelMessageID()
        var session = fixture.session
        session.messages = [discussionUser, discussionAssistant, proseMessage, laterDiscussion]
        session.archiveCursor = .through(sequence: 3)
        session.discussionArchives = [NovelDiscussionArchiveRecord(
            id: archiveID,
            checkpointID: fixture.branch.headCheckpointID,
            throughSequence: 3,
            messageCount: 4,
            chapterID: nil,
            summary: "讨论归档后未收录正文仍可见。",
            createdAt: Self.now
        )]

        let collapsed = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate]
        ))
        XCTAssertEqual(collapsed.rows.map(\.id), [archiveID, proseMessage.id])
        XCTAssertEqual(collapsed.rows.last?.candidate?.status, .interrupted)
        XCTAssertTrue(collapsed.rows.last?.actions.contains {
            if case .collectProse = $0.action { return true }
            return false
        } == true)
        let revealedRowCount = try XCTUnwrap(
            collapsed.rows.first?.archive?.revealedRowCount
        )
        XCTAssertEqual(revealedRowCount, 3)
        XCTAssertEqual(
            NovelSessionHistoryWindowPolicy.limitAfterArchiveExpansion(
                currentLimit: 4,
                revealedRowCount: revealedRowCount
            ),
            7
        )

        let expanded = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate],
            expandedArchiveIDs: [archiveID]
        ))
        XCTAssertEqual(
            expanded.rows.map(\.id),
            [archiveID, discussionUser.id, discussionAssistant.id, proseMessage.id, laterDiscussion.id]
        )
    }

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
        let terminalTail = tail.updating(
            content: terminal.content,
            renderRevision: 2,
            phase: .terminalAwaitingRefresh
        )
        let settling = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [terminalRun(run)],
            tail: terminalTail
        ))

        XCTAssertEqual(settling.rows.map(\.id), [user.id, terminal.id])
        XCTAssertEqual(settling.activeTailID, terminal.id)
        XCTAssertTrue(settling.rows[1].isTransient)
        XCTAssertEqual(settling.rows[1].transientPhase, .terminalAwaitingRefresh)
        XCTAssertEqual(settling.rows[1].content, terminal.content)
        XCTAssertNotEqual(streaming.rows[1].digest, settling.rows[1].digest)

        let durable = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            runs: [terminalRun(run)],
            tail: nil
        ))
        XCTAssertEqual(durable.rows.map(\.id), [user.id, terminal.id])
        XCTAssertNil(durable.activeTailID)
        XCTAssertFalse(durable.rows[1].isTransient)
        XCTAssertEqual(durable.rows[1].content, terminal.content)
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
            let wasBrowsingHistory = follow.mode == .browsingHistory
            let transition = NovelSessionBottomFollowPolicy.reduce(
                state: follow,
                event: .measuredStreamGrowth(isAtBottom: false)
            )
            if wasBrowsingHistory {
                // 「respects history pause」的真正契约:用户上滑浏览历史时,
                // 测得的流式增长不得把视口拽回底部。
                XCTAssertFalse(transition.commands.contains { command in
                    if case .followBottom = command { return true }
                    return false
                })
            } else {
                // 2026-07-26 撤锚更正:sizeChanges 底锚不再是流式增长的写者
                // (真机录屏实测 -390px 结构性跳变,详见 NovelSessionView.swift
                // 撤锚注释)。measured-geometry 回调重新成为跟随底部的唯一写者,
                // 命令必须是 animated:false——执行侧 scrollToBottomWithoutAnimation()
                // 用 Transaction(animation: nil) 禁用动画,不经过 startExplicitBottomAnimation()
                // 的 0.2s easeOut,这正是 PROJECT_STATE 2026-07-21 记录的「先欠账
                // 0.08s 动画追回」53pt 回归不复发的关键。
                XCTAssertTrue(transition.commands.contains(.followBottom(animated: false)))
            }
            follow = transition.state
        }

        XCTAssertGreaterThan(baseline.rows.last?.content.count ?? 0, 4_000)
        XCTAssertEqual(follow.mode, .followingBottom)
    }

    func testLargeSessionProjectionPerformance() throws {
        let fixture = try makeFixture()
        var session = fixture.session
        var messages: [NovelSessionMessageRecord] = []
        var candidates: [NovelCandidateRecord] = []
        var pendingOperations: [NovelPendingOperationRecord] = []
        messages.reserveCapacity(500)
        candidates.reserveCapacity(500)
        pendingOperations.reserveCapacity(500)

        for index in 0..<500 {
            let candidateID = NovelCandidateID()
            let message = makeMessage(
                sequence: Int64(index),
                role: .assistant,
                mode: .writeProse,
                kind: .proseCandidate,
                content: "长会话候选正文 \(index)",
                candidateID: candidateID
            )
            messages.append(message)
            candidates.append(makeCandidate(
                fixture: fixture,
                id: candidateID,
                sourceMessageID: message.id,
                kind: .prose,
                status: .available,
                content: message.content
            ))
            pendingOperations.append(makePending(
                fixture: fixture,
                candidateID: candidateID,
                status: .retryable
            ))
        }
        session.messages = messages
        let input = makeInput(
            fixture: fixture,
            session: session,
            candidates: candidates,
            pending: pendingOperations
        )
        XCTAssertEqual(NovelSessionPresentation.project(input).rows.count, messages.count)

        let options = XCTMeasureOptions()
        options.iterationCount = 5
        measure(metrics: [XCTClockMetric()], options: options) {
            _ = NovelSessionPresentation.project(input)
        }
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
        XCTAssertEqual(needsSync.rows[1].actions.first?.blocker, .branchNeedsSync)

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

        let manualSyncPending = makePending(
            fixture: fixture,
            candidateID: nil,
            status: .retryable,
            kind: .manualSync
        )
        let blockedByStateSync = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate],
            pending: [manualSyncPending]
        ))
        XCTAssertEqual(
            blockedByStateSync.rows[1].actions.first?.blocker,
            .branchNeedsSync
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

    func testRegenerationCandidateCannotBeCollectedAfterItsSourceChapterIsDiscarded() throws {
        let fixture = try makeFixture()
        let sourceVersionID = NovelChapterVersionID()
        let candidateID = NovelCandidateID()
        let message = makeMessage(
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .proseCandidate,
            content: "重写后的整章正文",
            candidateID: candidateID
        )
        var session = fixture.session
        session.messages = [message]
        let candidate = makeCandidate(
            fixture: fixture,
            id: candidateID,
            sourceMessageID: message.id,
            kind: .prose,
            status: .available,
            content: message.content,
            sourceVersionID: sourceVersionID
        )
        var branch = fixture.branch
        branch.workingChapterSelections = [NovelChapterSelection(
            chapterID: NovelChapterID(),
            versionID: sourceVersionID
        )]

        let available = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: branch,
            session: session,
            candidates: [candidate]
        ))
        XCTAssertNil(available.rows[0].actions.first?.blocker)

        let discarded = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: branch,
            session: session,
            candidates: [candidate],
            discardedChapterVersionIDs: [sourceVersionID]
        ))
        XCTAssertEqual(
            discarded.rows[0].actions,
            [.init(action: .collectProse(candidateID), blocker: .sourceChapterChanged)]
        )
    }

    func testProseCandidateRowPreservesItsGenerationGranularity() throws {
        let fixture = try makeFixture()
        let candidateID = NovelCandidateID()
        let run = makeRun(
            fixture: fixture,
            kind: .prose,
            candidateID: candidateID,
            granularity: .continuation
        )
        let message = makeMessage(
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .proseCandidate,
            content: "雨水沿着屋檐落下。",
            runID: run.id,
            candidateID: candidateID
        )
        var session = fixture.session
        session.messages = [message]
        let candidate = makeCandidate(
            fixture: fixture,
            id: candidateID,
            sourceMessageID: message.id,
            kind: .prose,
            status: .available,
            content: message.content
        )

        let model = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate],
            runs: [terminalRun(run)]
        ))

        XCTAssertEqual(model.rows.first?.granularity, .continuation)
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
        XCTAssertEqual(projected.rows[0].committedChange?.branchSyncStatus, .synchronized)
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
        XCTAssertEqual(atHead.rows[0].committedChange?.branchSyncStatus, .synchronized)

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
        // The branch's live syncStatus is a branch-level fact shared by every committed
        // row (see NovelSessionCommittedChangeSummary.branchSyncStatus doc comment) — it
        // is not a per-row history stamp, so it flips for this already-committed row too.
        XCTAssertEqual(needsSync.rows[0].committedChange?.branchSyncStatus, .needsSync)
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

        let discardedRetryable = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: branch,
            session: session,
            candidates: [availableCandidate],
            polish: [retryableTransaction],
            discardedChapterVersionIDs: [sourceVersionID]
        ))
        XCTAssertEqual(discardedRetryable.rows[0].actions, [
            .init(action: .retryPolish(retryableTransaction.id), blocker: .sourceChapterChanged),
            .init(action: .abandonPolish(retryableTransaction.id), blocker: nil),
        ])

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

        let regeneratedSourceVersionID = NovelChapterVersionID()
        let regenerateRun = terminalRun(makeRun(
            fixture: fixture,
            kind: .regenerate,
            sourceVersionID: regeneratedSourceVersionID
        ))
        session.messages = [makeMessage(
            id: regenerateRun.messageID,
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .interruptedDraft,
            content: "保留的重写草稿",
            runID: regenerateRun.id
        )]
        var regenerateBranch = fixture.branch
        regenerateBranch.workingChapterSelections = [NovelChapterSelection(
            chapterID: NovelChapterID(),
            versionID: regeneratedSourceVersionID
        )]
        let exactRegenerateRetry = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: regenerateBranch,
            session: session,
            runs: [regenerateRun]
        ))
        XCTAssertEqual(exactRegenerateRetry.rows[0].actions, [
            .init(action: .retryGeneration(regenerateRun.id), blocker: nil)
        ])

        let discardedRegenerateSource = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: regenerateBranch,
            session: session,
            runs: [regenerateRun],
            discardedChapterVersionIDs: [regeneratedSourceVersionID]
        ))
        XCTAssertEqual(
            discardedRegenerateSource.rows[0].actions.first?.blocker,
            .sourceChapterChanged
        )

        regenerateBranch.headRevision += 1
        let staleRegenerateRetry = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: regenerateBranch,
            session: session,
            runs: [regenerateRun]
        ))
        XCTAssertEqual(staleRegenerateRetry.rows[0].actions.first?.blocker, .staleCandidate)

        regenerateBranch.headRevision = fixture.branch.headRevision
        regenerateBranch.workingChapterSelections = []
        let changedRegenerateSource = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: regenerateBranch,
            session: session,
            runs: [regenerateRun]
        ))
        XCTAssertEqual(
            changedRegenerateSource.rows[0].actions.first?.blocker,
            .sourceChapterChanged
        )

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

    func testPolishAndRegenerateRetryRespectTheSameBranchGatesAsStartingANewRun() throws {
        let fixture = try makeFixture()
        let sourceVersionID = NovelChapterVersionID()
        var branch = fixture.branch
        branch.workingChapterSelections = [NovelChapterSelection(
            chapterID: NovelChapterID(),
            versionID: sourceVersionID
        )]

        for kind in [NovelRunKind.polish, .regenerate] {
            let run = terminalRun(
                makeRun(fixture: fixture, kind: kind, sourceVersionID: sourceVersionID),
                status: .interrupted
            )
            var session = fixture.session
            session.messages = [makeMessage(
                id: run.messageID,
                sequence: 0,
                role: .assistant,
                mode: .writeProse,
                kind: .interruptedDraft,
                content: "保留的整章草稿",
                runID: run.id
            )]

            var needsSyncBranch = branch
            needsSyncBranch.syncStatus = .needsSync
            let needsSync = NovelSessionPresentation.project(makeInput(
                fixture: fixture,
                branch: needsSyncBranch,
                session: session,
                runs: [run]
            ))
            XCTAssertEqual(
                needsSync.rows[0].actions.first?.blocker,
                .branchNeedsSync,
                "\(kind) retry must not bypass the start gate for an unsynchronized branch."
            )

            let retryableManualSync = makePending(
                fixture: fixture,
                candidateID: nil,
                status: .retryable,
                kind: .manualSync
            )
            let pending = NovelSessionPresentation.project(makeInput(
                fixture: fixture,
                branch: branch,
                session: session,
                runs: [run],
                pending: [retryableManualSync]
            ))
            XCTAssertEqual(
                pending.rows[0].actions.first?.blocker,
                .branchNeedsSync,
                "\(kind) retry must wait for every branch operation, including retryable manual sync."
            )

            let transactionCandidate = makeCandidate(
                fixture: fixture,
                id: NovelCandidateID(),
                sourceMessageID: NovelMessageID(),
                kind: .polish,
                status: .available,
                content: "待检查的润色正文",
                sourceVersionID: sourceVersionID
            )
            let unresolvedTransaction = makePolishTransaction(
                fixture: fixture,
                candidate: transactionCandidate,
                status: .blocked
            )
            let unresolvedPolish = NovelSessionPresentation.project(makeInput(
                fixture: fixture,
                branch: branch,
                session: session,
                runs: [run],
                polish: [unresolvedTransaction]
            ))
            XCTAssertEqual(
                unresolvedPolish.rows[0].actions.first?.blocker,
                .pendingOperation,
                "\(kind) retry must not start while the previous polish check is unresolved."
            )
        }
    }

    func testProseRetryRespectsNeedsSyncLikeStartingANewRun() throws {
        let fixture = try makeFixture()
        let run = terminalRun(
            makeRun(fixture: fixture, kind: .prose, candidateID: NovelCandidateID()),
            status: .interrupted
        )
        var session = fixture.session
        session.messages = [makeMessage(
            id: run.messageID,
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .interruptedDraft,
            content: "保留的正文草稿",
            runID: run.id,
            candidateID: run.candidateID
        )]

        var needsSyncBranch = fixture.branch
        needsSyncBranch.syncStatus = .needsSync
        let needsSync = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            branch: needsSyncBranch,
            session: session,
            runs: [run]
        ))
        XCTAssertEqual(
            needsSync.rows[0].actions.first(where: {
                if case .retryGeneration = $0.action { return true }
                return false
            })?.blocker,
            .branchNeedsSync,
            "Prose retry must not bypass the start gate for an unsynchronized branch."
        )
    }

    func testInterruptedProseRowsExposeCollectBesideRetryAndKeepSyncBlocker() throws {
        let fixture = try makeFixture()
        let candidateID = NovelCandidateID()
        let run = terminalRun(
            makeRun(fixture: fixture, kind: .prose, candidateID: candidateID),
            status: .interrupted
        )
        let message = makeMessage(
            id: run.messageID,
            sequence: 0,
            role: .assistant,
            mode: .writeProse,
            kind: .interruptedDraft,
            content: "保留的正文",
            runID: run.id,
            candidateID: candidateID
        )
        let candidate = makeCandidate(
            fixture: fixture,
            id: candidateID,
            sourceMessageID: message.id,
            kind: .prose,
            status: .interrupted,
            content: message.content
        )
        var session = fixture.session
        session.messages = [message]

        let durable = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate],
            runs: [run]
        ))
        XCTAssertEqual(durable.rows[0].actions, [
            .init(action: .collectProse(candidateID), blocker: nil),
            .init(action: .retryGeneration(run.id), blocker: nil),
        ])

        let manualSync = makePending(
            fixture: fixture,
            candidateID: nil,
            status: .retryable,
            kind: .manualSync
        )
        let blocked = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            session: session,
            candidates: [candidate],
            runs: [run],
            pending: [manualSync]
        ))
        XCTAssertEqual(blocked.rows[0].actions.first?.blocker, .branchNeedsSync)

        let transientRun = makeRun(
            fixture: fixture,
            kind: .prose,
            candidateID: candidateID
        )
        let tail = NovelSessionTransientTail(
            run: transientRun,
            content: "保留的正文",
            renderRevision: 1,
            phase: .interrupted
        )
        let transient = NovelSessionPresentation.project(makeInput(
            fixture: fixture,
            runs: [transientRun],
            tail: tail
        ))
        XCTAssertEqual(transient.rows[0].actions, [
            .init(action: .collectProse(candidateID), blocker: nil),
            .init(action: .retryGeneration(transientRun.id), blocker: nil),
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
            .init(action: .collectProse(try XCTUnwrap(run.candidateID)), blocker: nil),
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

    /// 2026-07-26 撤锚更正的核心锁:`.sizeChanges` 底锚被真机录屏(−390px 结构性
    /// 跳变)推翻后,measured-geometry 回调重新成为流式跟随底部的唯一写者。这条
    /// canary 锁住两件事,任何一件被破坏都会让 PROJECT_STATE 2026-07-21 记录的
    /// 53pt「先欠账、再用 0.08s 动画追回」回归复发:
    /// 1. `.followingBottom` / `.settlingTerminal`(收口为 `.followingBottom`)下的
    ///    `.measuredStreamGrowth` 必须发出 `.followBottom` 命令(不能再是 no-op);
    /// 2. 该命令必须显式 `animated: false`——执行侧
    ///    `NovelSessionView.scrollToBottomWithoutAnimation()` 用
    ///    `Transaction(animation: nil)` 禁用动画,不经过
    ///    `startExplicitBottomAnimation()` 的 0.2s easeOut(那个动画只保留给
    ///    `.explicitBottomRequested` 这类用户主动点击回底的语义,两者不可混淆)。
    func testMeasuredStreamGrowthWhileFollowingRestoresUnanimatedFollowWriter() {
        let followingTransition = NovelSessionBottomFollowPolicy.reduce(
            state: NovelSessionBottomFollowState(mode: .followingBottom),
            event: .measuredStreamGrowth(isAtBottom: false)
        )
        XCTAssertEqual(followingTransition.state.mode, .followingBottom)
        XCTAssertEqual(followingTransition.commands, [.followBottom(animated: false)])

        let settlingTransition = NovelSessionBottomFollowPolicy.reduce(
            state: NovelSessionBottomFollowState(mode: .settlingTerminal(token: 7)),
            event: .measuredStreamGrowth(isAtBottom: false)
        )
        XCTAssertEqual(settlingTransition.state.mode, .followingBottom)
        XCTAssertEqual(settlingTransition.commands, [.followBottom(animated: false)])

        // 浏览历史时仍然必须保持静默:这条负向断言防止「恢复写者」的改动
        // 误伤既有「不拽用户回底」契约。
        let browsingTransition = NovelSessionBottomFollowPolicy.reduce(
            state: NovelSessionBottomFollowState(mode: .browsingHistory),
            event: .measuredStreamGrowth(isAtBottom: false)
        )
        XCTAssertEqual(browsingTransition.state.mode, .browsingHistory)
        XCTAssertTrue(browsingTransition.commands.isEmpty)
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
        transition = NovelSessionBottomFollowPolicy.reduce(
            state: state,
            event: .measuredStreamGrowth(isAtBottom: false)
        )
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

    func testStreamStartReanchorsTheUserMessageAndWaitingTail() {
        let transition = NovelSessionBottomFollowPolicy.reduce(
            state: NovelSessionBottomFollowState(mode: .browsingHistory, showsBottomButton: true),
            event: .streamStarted
        )

        XCTAssertEqual(transition.state.mode, .followingBottom)
        XCTAssertFalse(transition.state.showsBottomButton)
        XCTAssertEqual(transition.commands, [
            .setBottomButton(false),
            .anchorBottom,
        ])
    }

    func testSessionViewPresentsAlreadyLoadedRowsFromAwaitingInitialState() throws {
        let iosRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let source = try String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/NovelCreation/NovelSessionView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("@State private var followState = NovelSessionBottomFollowState()"))
        XCTAssertTrue(source.contains(".task(id: listSignal.sessionID)"))
        XCTAssertTrue(source.contains("presentInitialRowsIfNeeded(listSignal)"))
    }

    func testUserDragEndingNearBottomCommitsSemanticBottomFollow() {
        let state = NovelSessionBottomFollowState(
            mode: .browsingHistory,
            showsBottomButton: true
        )

        let transition = NovelSessionBottomFollowPolicy.reduce(
            state: state,
            event: .userDragEnded(isAtBottom: true)
        )

        XCTAssertEqual(transition.state.mode, .followingBottom)
        XCTAssertFalse(transition.state.showsBottomButton)
        XCTAssertTrue(transition.commands.contains(.followBottom(animated: false)))
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
        XCTAssertTrue(source.contains("NovelSessionScrollGeometryPolicy.events("))
        XCTAssertTrue(source.contains("for event in followEvents"))
        XCTAssertTrue(source.contains("!viewModel.retryableBranchPendingOperations.isEmpty"))
        XCTAssertTrue(source.contains("NativeTimelineScrollReturnPolicy.returnedToBottom("))
        XCTAssertTrue(source.contains("dispatchFollowEvent(.userDragEnded(isAtBottom: returnedToBottom))"))
        XCTAssertTrue(source.contains("ChatLayout.nearBottomResumeThreshold"))
        XCTAssertTrue(source.contains("NativeChatTimelineView.shouldBeginNativeUserDrag("))
        XCTAssertTrue(
            source.contains("isUIKitUserInteracting: scrollDriver.isUIKitUserInteracting"),
            "程序化 setContentOffset 产生的 interacting 不能被小说页误判成用户拖拽。"
        )
        XCTAssertTrue(
            source.contains("NovelSessionHistoryWindowPolicy.limitAfterActiveRunReturnsToHistory("),
            "完成瞬间必须吸收转入历史的行数，否则窗口会裁掉完成前已可见的旧行。"
        )
    }

    func testMeasuredContentGrowthUsesLiveOrTerminalFollowEvent() {
        XCTAssertEqual(
            NovelSessionScrollGeometryPolicy.events(
                previousContentHeight: 120,
                currentContentHeight: 132,
                userDragging: false,
                isLiveTail: true,
                isSettlingTerminal: false,
                previousIsAtBottom: true,
                currentIsAtBottom: false
            ),
            [.measuredStreamGrowth(isAtBottom: false)]
        )
        XCTAssertEqual(
            NovelSessionScrollGeometryPolicy.events(
                previousContentHeight: 132,
                currentContentHeight: 148,
                userDragging: false,
                isLiveTail: false,
                isSettlingTerminal: false,
                previousIsAtBottom: true,
                currentIsAtBottom: false
            ),
            [.staticContentGrowth(isAtBottom: false)]
        )
        XCTAssertEqual(
            NovelSessionScrollGeometryPolicy.events(
                previousContentHeight: 148,
                currentContentHeight: 164,
                userDragging: false,
                isLiveTail: false,
                isSettlingTerminal: true,
                previousIsAtBottom: true,
                currentIsAtBottom: false
            ),
            [.measuredTerminalGrowth(isAtBottom: false)]
        )
        XCTAssertEqual(
            NovelSessionScrollGeometryPolicy.events(
                previousContentHeight: 164,
                currentContentHeight: 180,
                userDragging: true,
                isLiveTail: true,
                isSettlingTerminal: false,
                previousIsAtBottom: true,
                currentIsAtBottom: false
            ),
            []
        )
        XCTAssertEqual(
            NovelSessionScrollGeometryPolicy.events(
                previousContentHeight: 180,
                currentContentHeight: 168,
                userDragging: false,
                isLiveTail: true,
                isSettlingTerminal: false,
                previousIsAtBottom: true,
                currentIsAtBottom: false
            ),
            [.viewportChanged(isAtBottom: false)]
        )
    }

    func testStaticContentGrowthDoesNotFallThroughToViewportFollow() {
        // 到底状态翻转时只产生按钮语义事件，不落入会触发回底的 viewportChanged。
        XCTAssertEqual(
            NovelSessionScrollGeometryPolicy.events(
                previousContentHeight: 120,
                currentContentHeight: 180,
                userDragging: false,
                isLiveTail: false,
                isSettlingTerminal: false,
                previousIsAtBottom: true,
                currentIsAtBottom: false
            ),
            [.staticContentGrowth(isAtBottom: false)]
        )
        // 未翻转的静态增长依旧完全静默。
        XCTAssertEqual(
            NovelSessionScrollGeometryPolicy.events(
                previousContentHeight: 120,
                currentContentHeight: 180,
                userDragging: false,
                isLiveTail: false,
                isSettlingTerminal: false,
                previousIsAtBottom: true,
                currentIsAtBottom: true
            ),
            []
        )
    }

    func testStaticContentGrowthOnlyUpdatesBottomButtonWithoutScrollCommands() {
        let shifted = NovelSessionBottomFollowPolicy.reduce(
            state: NovelSessionBottomFollowState(mode: .followingBottom),
            event: .staticContentGrowth(isAtBottom: false)
        )
        XCTAssertEqual(shifted.state.mode, .followingBottom)
        XCTAssertTrue(shifted.state.showsBottomButton)
        XCTAssertEqual(shifted.commands, [.setBottomButton(true)])

        let browsing = NovelSessionBottomFollowPolicy.reduce(
            state: NovelSessionBottomFollowState(mode: .browsingHistory, showsBottomButton: true),
            event: .staticContentGrowth(isAtBottom: true)
        )
        XCTAssertEqual(browsing.state.mode, .browsingHistory)
        XCTAssertFalse(browsing.state.showsBottomButton)
        XCTAssertEqual(browsing.commands, [.setBottomButton(false)])

        let awaiting = NovelSessionBottomFollowPolicy.reduce(
            state: NovelSessionBottomFollowState(),
            event: .staticContentGrowth(isAtBottom: false)
        )
        XCTAssertEqual(awaiting.state.mode, .awaitingInitialRows)
        XCTAssertTrue(awaiting.commands.isEmpty)
    }

    func testViewportReturnToBottomClearsStaleBottomButtonWhileFollowing() {
        // staticContentGrowth 会在 followingBottom 下亮按钮；随后几何回底
        // （viewportChanged(true)）必须收掉按钮，否则箭头与真实位置不一致。
        let shifted = NovelSessionBottomFollowPolicy.reduce(
            state: NovelSessionBottomFollowState(mode: .followingBottom),
            event: .staticContentGrowth(isAtBottom: false)
        )
        XCTAssertTrue(shifted.state.showsBottomButton)

        let returned = NovelSessionBottomFollowPolicy.reduce(
            state: shifted.state,
            event: .viewportChanged(isAtBottom: true)
        )
        XCTAssertEqual(returned.state.mode, .followingBottom)
        XCTAssertFalse(returned.state.showsBottomButton)
        XCTAssertEqual(returned.commands, [.setBottomButton(false)])
    }

    /// 不变量:会话进行中追加新行,已经渲染过的顶部行不得被窗口踢出。
    /// 违反时 `startIndex` 前移,顶部长正文行(可达数万 pt)从 contentSize 里
    /// 整体消失,而消失发生在视口上方且无锚点补偿 → 整列表大幅位移。
    func testAppendingRowsNeverEvictsAlreadyRenderedTopRows() {
        var limit = NovelSessionHistoryWindowPolicy.initialLimit
        var rowCount = limit
        let startIndexBefore = NovelSessionHistoryWindowPolicy.startIndex(
            totalCount: rowCount,
            limit: limit
        )
        // 连续追加 20 行(一次生成里 user/assistant/工具行很容易到这个量级)。
        for _ in 0..<20 {
            let previousRowCount = rowCount
            rowCount += 1
            limit = NovelSessionHistoryWindowPolicy.limitAfterRowsAppended(
                currentLimit: limit,
                previousRowCount: previousRowCount,
                currentRowCount: rowCount
            )
            XCTAssertEqual(
                NovelSessionHistoryWindowPolicy.startIndex(totalCount: rowCount, limit: limit),
                startIndexBefore,
                "追加第 \(rowCount) 行后窗口起点前移,已渲染的顶部行被踢出"
            )
        }
    }

    /// 视图侧不得再用「是否贴底」当作是否吸收新增行的条件:贴底只影响可见性,
    /// 不影响这条不变量。带条件的旧写法正是位移的来源。
    func testHistoryWindowAbsorbsAppendedRowsRegardlessOfBottomProximity() throws {
        let source = try String(
            contentsOf: URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent()
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/NovelCreation/NovelSessionView.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(
            source.contains("NovelSessionHistoryWindowPolicy.limitAfterRowsAppended("),
            "追加行的吸收必须走策略层,便于单测锁不变量"
        )
        XCTAssertFalse(
            source.contains("newValue.rowCount > oldValue.rowCount, !latestAtBottom"),
            "不得再用 !latestAtBottom 门控窗口吸收"
        )
    }

    func testCompletionRaisesHistoryWindowLimitOnlyForRowsThatBecameHistorical() {
        // 完成：run 的 2 行全部转入历史，窗口上限吸收 2 行，完成前可见的旧行保持可见。
        XCTAssertEqual(
            NovelSessionHistoryWindowPolicy.limitAfterActiveRunReturnsToHistory(
                currentLimit: 4,
                activeRunRowCount: 2,
                previousRowCount: 12,
                currentRowCount: 12
            ),
            6
        )
        // 启动失败恢复：临时行整体消失，没有行转入历史，不得扩窗。
        XCTAssertEqual(
            NovelSessionHistoryWindowPolicy.limitAfterActiveRunReturnsToHistory(
                currentLimit: 4,
                activeRunRowCount: 2,
                previousRowCount: 12,
                currentRowCount: 10
            ),
            4
        )
        // 部分留存（user 行已耐久、assistant 行消失）：只吸收留存的那一行。
        XCTAssertEqual(
            NovelSessionHistoryWindowPolicy.limitAfterActiveRunReturnsToHistory(
                currentLimit: 4,
                activeRunRowCount: 2,
                previousRowCount: 12,
                currentRowCount: 11
            ),
            5
        )
    }

    func testRawStreamDeltaDoesNotOwnScrollingAndDisabledFollowShowsBottomButtonAfterGrowth() {
        let following = NovelSessionBottomFollowState(mode: .followingBottom)

        let rawDelta = NovelSessionBottomFollowPolicy.reduce(
            state: following,
            event: .streamDelta
        )
        XCTAssertTrue(rawDelta.commands.isEmpty)

        let disabledGrowth = NovelSessionBottomFollowPolicy.reduce(
            state: following,
            event: .measuredStreamGrowth(isAtBottom: false),
            followEnabled: false
        )
        XCTAssertEqual(disabledGrowth.state.mode, .browsingHistory)
        XCTAssertTrue(disabledGrowth.state.showsBottomButton)
        XCTAssertFalse(disabledGrowth.commands.contains { command in
            if case .followBottom = command { return true }
            return false
        })

        let shortContentGrowth = NovelSessionBottomFollowPolicy.reduce(
            state: following,
            event: .measuredStreamGrowth(isAtBottom: true),
            followEnabled: false
        )
        XCTAssertEqual(shortContentGrowth.state.mode, .followingBottom)
        XCTAssertFalse(shortContentGrowth.state.showsBottomButton)
        XCTAssertTrue(shortContentGrowth.commands.isEmpty)

        let disabledTerminalGrowth = NovelSessionBottomFollowPolicy.reduce(
            state: following,
            event: .measuredTerminalGrowth(isAtBottom: false),
            followEnabled: false
        )
        XCTAssertEqual(disabledTerminalGrowth.state.mode, .browsingHistory)
        XCTAssertTrue(disabledTerminalGrowth.state.showsBottomButton)

        let disabledNearBottomDrag = NovelSessionBottomFollowPolicy.reduce(
            state: NovelSessionBottomFollowState(
                mode: .browsingHistory,
                showsBottomButton: true
            ),
            event: .userDragEnded(isAtBottom: true),
            followEnabled: false
        )
        XCTAssertEqual(disabledNearBottomDrag.state.mode, .browsingHistory)
        XCTAssertFalse(disabledNearBottomDrag.state.showsBottomButton)
        XCTAssertFalse(disabledNearBottomDrag.commands.contains { command in
            if case .followBottom = command { return true }
            return false
        })
    }

    func testExplicitBottomAnimationOwnsItsFullWindowAndReplaysOnePendingFollow() throws {
        let iosRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let source = try String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/NovelCreation/NovelSessionView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("explicitBottomAnimationTask != nil"))
        XCTAssertTrue(source.contains("explicitBottomFollowPending = true"))
        XCTAssertTrue(source.contains("try await Task.sleep(for: .seconds(0.2))"))
        XCTAssertTrue(source.contains("if shouldReplay"))
        XCTAssertTrue(source.contains("case .reset, .userDragBegan:"))
    }

    func testPolishRetryEntrypointsShareOwnedTaskWithVisibleStopFeedback() throws {
        let iosRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sessionView = try String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/NovelCreation/NovelSessionView.swift"),
            encoding: .utf8
        )
        let bubble = try String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/NovelCreation/NovelSessionBubble.swift"),
            encoding: .utf8
        )

        let sessionViewModel = try String(
            contentsOf: iosRoot.appendingPathComponent(
                "iosApp/NovelCreation/NovelSessionViewModel.swift"
            ),
            encoding: .utf8
        )

        XCTAssertFalse(sessionView.contains("@State private var polishRetryTask"))
        XCTAssertTrue(sessionView.contains("viewModel.startPolishRetry(transaction.id)"))
        XCTAssertTrue(sessionView.contains("case .retryPolish(let transactionID):"))
        XCTAssertTrue(sessionView.contains("viewModel.startPolishRetry(transactionID)"))
        XCTAssertTrue(sessionView.contains("viewModel.cancelPolishRetry()"))
        XCTAssertTrue(sessionViewModel.contains("private var polishRetryTask: Task<Void, Never>?"))
        XCTAssertTrue(sessionViewModel.contains("private(set) var polishRetryTransactionID"))
        XCTAssertTrue(sessionView.contains("Text(\"正在检查剧情一致性…\")"))
        XCTAssertTrue(bubble.contains("Button(\"停止检查\")"))
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
        discardedChapterVersionIDs: Set<NovelChapterVersionID> = [],
        checkpoints: [NovelBranchCheckpointRecord]? = nil,
        states: [NovelStateSnapshotRecord]? = nil,
        events: [NovelStoryEventRecord] = [],
        proposals: [NovelSettingProposalRecord] = [],
        access: NovelProjectLoadAccess = .readWrite,
        expandedArchiveIDs: Set<NovelMessageID> = [],
        tail: NovelSessionTransientTail? = nil
    ) -> NovelSessionProjectionInput {
        NovelSessionProjectionInput(
            branch: branch ?? fixture.branch,
            session: session ?? fixture.session,
            candidates: candidates,
            runs: runs,
            pendingOperations: pending,
            polishTransactions: polish,
            discardedChapterVersionIDs: discardedChapterVersionIDs,
            checkpoints: checkpoints ?? fixture.document.checkpoints,
            stateSnapshots: states ?? fixture.document.stateSnapshots,
            events: events,
            settingProposals: proposals,
            access: access,
            expandedArchiveIDs: expandedArchiveIDs,
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
        sourceVersionID: NovelChapterVersionID? = nil,
        granularity: NovelGenerationGranularity? = nil
    ) -> NovelActiveRunRecord {
        NovelActiveRunRecord(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            requestPayloadSHA256: NovelTestFixtures.hashA,
            branchID: fixture.branch.id,
            sessionID: fixture.session.id,
            kind: kind,
            mode: kind == .discussion || kind == .quickStart ? .discussPlan : .writeProse,
            granularity: kind == .prose ? granularity ?? .wholeChapter : nil,
            userMessageID: NovelMessageID(),
            messageID: NovelMessageID(),
            candidateID: candidateID,
            sourceChapterVersionID: sourceVersionID,
            contextualCharacterMention: nil,
            baseCheckpointID: fixture.branch.headCheckpointID,
            baseHeadRevision: fixture.branch.headRevision,
            status: .running,
            partialContent: "",
            receiptID: NovelReceiptID(),
            startedAt: Self.now,
            terminalAt: nil,
            interruptionReason: nil,
            terminalFailure: nil,
            chapterPlanDigest: nil
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
            contextualCharacterMention: run.contextualCharacterMention,
            baseCheckpointID: run.baseCheckpointID,
            baseHeadRevision: run.baseHeadRevision,
            status: status,
            partialContent: run.partialContent,
            receiptID: run.receiptID,
            startedAt: run.startedAt,
            terminalAt: Self.now,
            interruptionReason: status == .interrupted ? .user : nil,
            terminalFailure: failure,
            chapterPlanDigest: run.chapterPlanDigest
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
        candidateID: NovelCandidateID?,
        status: NovelPendingOperationStatus,
        kind: NovelPendingOperationKind = .collection
    ) -> NovelPendingOperationRecord {
        NovelPendingOperationRecord(
            id: NovelPendingOperationID(),
            kind: kind,
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
