import XCTest
@testable import iosApp

final class ChatViewportPolicyTests: XCTestCase {

    func testSwiftUINearBottomResumeRequiresARealUserScrollAndUses96PointThreshold() {
        XCTAssertEqual(ChatLayout.bottomStickThreshold, 40)
        XCTAssertEqual(ChatLayout.nearBottomResumeThreshold, 96)

        for distance in [CGFloat(95), CGFloat(96)] {
            XCTAssertTrue(
                ChatSwiftUINearBottomResumePolicy.shouldResume(
                    followPaused: true,
                    userScrollActive: true,
                    userScrollJustEnded: false,
                    distanceToBottom: distance
                ),
                "真实用户滚动仍 active 时，距底 \(distance)pt 应恢复跟随。"
            )
            XCTAssertTrue(
                ChatSwiftUINearBottomResumePolicy.shouldResume(
                    followPaused: true,
                    userScrollActive: false,
                    userScrollJustEnded: true,
                    distanceToBottom: distance
                ),
                "真实用户滚动结束时，距底 \(distance)pt 应恢复跟随。"
            )
        }

        for userScrollActive in [false, true] {
            XCTAssertFalse(
                ChatSwiftUINearBottomResumePolicy.shouldResume(
                    followPaused: true,
                    userScrollActive: userScrollActive,
                    userScrollJustEnded: !userScrollActive,
                    distanceToBottom: 97
                ),
                "距底 97pt 已越过恢复阈值，不能自动抢回视口。"
            )
        }
        XCTAssertFalse(
            ChatSwiftUINearBottomResumePolicy.shouldResume(
                followPaused: true,
                userScrollActive: false,
                userScrollJustEnded: false,
                distanceToBottom: 40
            ),
            "即使位于 40pt true-bottom 范围，程序滚动也不能冒充真实用户恢复跟随。"
        )
        XCTAssertFalse(
            ChatSwiftUINearBottomResumePolicy.shouldResume(
                followPaused: false,
                userScrollActive: true,
                userScrollJustEnded: false,
                distanceToBottom: 0
            ),
            "未暂停时不需要制造一次新的恢复事件。"
        )
    }

    func testSwiftUIStreamingTailSuspendsOnlyAfterTailIsConfirmedOffscreen() {
        let messageID = "tail"

        XCTAssertFalse(
            ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
                isLastAssistant: true,
                hasEverStreamed: true,
                messageID: messageID,
                visibility: .init()
            ),
            "未知可见性必须保守保持 live，不能把可能已在屏内的长尾行降级。"
        )
        XCTAssertFalse(
            ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
                isLastAssistant: true,
                hasEverStreamed: true,
                messageID: messageID,
                visibility: .init(messageID: messageID, isVisible: true)
            )
        )
        XCTAssertTrue(
            ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
                isLastAssistant: true,
                hasEverStreamed: true,
                messageID: messageID,
                visibility: .init(messageID: messageID, isVisible: false)
            ),
            "曾流式的尾行确认离屏后保持冻结，tool/terminal 不能唤醒全文渲染。"
        )
        XCTAssertFalse(
            ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
                isLastAssistant: true,
                hasEverStreamed: false,
                messageID: messageID,
                visibility: .init(messageID: messageID, isVisible: false)
            ),
            "从未流式过的普通尾行不能进入流式 LOD。"
        )
        XCTAssertFalse(
            ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
                isLastAssistant: false,
                hasEverStreamed: true,
                messageID: messageID,
                visibility: .init(messageID: messageID, isVisible: false)
            ),
            "新消息追加后，旧 assistant 不能继续沿用过期的尾行可见性冻结。"
        )
    }

    func testExplicitBottomKeepsTailLiveUntilTheRealTailAndBottomAreBothVisible() {
        let visibleTail = ChatSwiftUIStreamingTailVisibilityState(messageID: "tail", isVisible: true)

        XCTAssertFalse(
            ChatSwiftUIExplicitBottomLiveTailPolicy.shouldRelease(
                forceActive: true,
                messageID: "tail",
                visibility: visibleTail,
                distanceToBottom: 400
            ),
            "LazyVStack 只实例化了尾行或尾行仅部分相交时，不能提前恢复离屏冻结"
        )
        XCTAssertFalse(
            ChatSwiftUIExplicitBottomLiveTailPolicy.shouldRelease(
                forceActive: true,
                messageID: "tail",
                visibility: .init(messageID: "tail", isVisible: false),
                distanceToBottom: 0
            ),
            "旧 contentHeight 的假底部不能替代真实尾行可见性"
        )
        XCTAssertFalse(
            ChatSwiftUIExplicitBottomLiveTailPolicy.shouldRelease(
                forceActive: true,
                messageID: "new-tail",
                visibility: visibleTail,
                distanceToBottom: 0
            ),
            "旧尾行的可见样本不能释放新尾行的回底所有权"
        )
        XCTAssertTrue(
            ChatSwiftUIExplicitBottomLiveTailPolicy.shouldRelease(
                forceActive: true,
                messageID: "tail",
                visibility: visibleTail,
                distanceToBottom: 0
            )
        )
    }

    func testExplicitBottomCancelsAtTerminalOnlyWhenNoAssistantTailExists() {
        XCTAssertTrue(
            ChatSwiftUIExplicitBottomLiveTailPolicy.shouldCancelAtTerminal(
                forceActive: true,
                hasAssistantTail: false
            )
        )
        XCTAssertFalse(
            ChatSwiftUIExplicitBottomLiveTailPolicy.shouldCancelAtTerminal(
                forceActive: true,
                hasAssistantTail: true
            )
        )
        XCTAssertFalse(
            ChatSwiftUIExplicitBottomLiveTailPolicy.shouldCancelAtTerminal(
                forceActive: false,
                hasAssistantTail: false
            )
        )
    }

    func testConversationAnchorRetryWaitsThroughTransientUserScroll() {
        XCTAssertEqual(
            ChatSwiftUIConversationAnchorRetryPolicy.decision(
                taskCancelled: false,
                tokenMatches: true,
                canRunNow: false
            ),
            .wait
        )
        XCTAssertEqual(
            ChatSwiftUIConversationAnchorRetryPolicy.decision(
                taskCancelled: false,
                tokenMatches: true,
                canRunNow: true
            ),
            .attempt
        )
        XCTAssertEqual(
            ChatSwiftUIConversationAnchorRetryPolicy.decision(
                taskCancelled: true,
                tokenMatches: true,
                canRunNow: true
            ),
            .abort
        )
        XCTAssertEqual(
            ChatSwiftUIConversationAnchorRetryPolicy.decision(
                taskCancelled: false,
                tokenMatches: false,
                canRunNow: true
            ),
            .abort
        )
    }

    func testSwiftUIExplicitBottomButtonPreservesMeasuredGeometryUntilScrollArrives() {
        let current = ChatViewportState(
            followPaused: true,
            userDragging: true,
            showScrollToBottom: true,
            isAtBottom: false,
            isContentScrollable: true,
            liveRenderingFarFromBottom: true,
            conversationLoadToken: 0
        )

        let plan = ChatSwiftUIExplicitBottomPolicy.plan(
            current: current,
            source: .button,
            distanceToBottom: 900
        )

        XCTAssertFalse(plan.viewportState.followPaused)
        XCTAssertFalse(plan.viewportState.userDragging)
        XCTAssertFalse(plan.viewportState.showScrollToBottom)
        XCTAssertFalse(plan.viewportState.isAtBottom, "真实几何到达底部前不能伪造 isAtBottom")
        XCTAssertTrue(plan.viewportState.liveRenderingFarFromBottom, "回底动画开始前不能提前恢复离屏 live render")
        XCTAssertTrue(plan.animated)
    }

    func testSwiftUIComposerFocusBottomRequestDoesNotCompeteWithKeyboardAnimation() {
        let plan = ChatSwiftUIExplicitBottomPolicy.plan(
            current: ChatViewportState(
                followPaused: true,
                userDragging: false,
                showScrollToBottom: true,
                isAtBottom: false,
                isContentScrollable: true,
                liveRenderingFarFromBottom: true,
                conversationLoadToken: 0
            ),
            source: .composerFocus,
            distanceToBottom: 500
        )

        XCTAssertFalse(plan.animated)
        XCTAssertFalse(plan.viewportState.isAtBottom)
        XCTAssertTrue(plan.viewportState.liveRenderingFarFromBottom)
    }

    func testMeasuredGrowthFollowCoversActiveGenerationAndTerminalSettle() {
        XCTAssertFalse(
            ChatSwiftUIMeasuredGrowthFollowPolicy.shouldFollow(
                generationActive: false,
                endSettleActive: false,
                explicitBottomCatchUpActive: false,
                explicitBottomAnimationActive: false,
                followEnabled: true,
                followMode: .followingBottom,
                state: ChatViewportState(isAtBottom: false, isContentScrollable: true),
                userScrollActive: false,
                previousContentHeight: 800,
                currentContentHeight: 840,
                distanceToBottom: 40
            ),
            "没有活跃生成或 terminal settle 所有权时，geometry 不得自行写底锚"
        )
        XCTAssertTrue(
            ChatSwiftUIMeasuredGrowthFollowPolicy.shouldFollow(
                generationActive: false,
                endSettleActive: true,
                explicitBottomCatchUpActive: false,
                explicitBottomAnimationActive: false,
                followEnabled: true,
                followMode: .followingBottom,
                state: ChatViewportState(isAtBottom: false, isContentScrollable: true),
                userScrollActive: false,
                previousContentHeight: 800,
                currentContentHeight: 840,
                distanceToBottom: 40
            )
        )
        XCTAssertTrue(
            ChatSwiftUIMeasuredGrowthFollowPolicy.shouldFollow(
                generationActive: true,
                endSettleActive: false,
                explicitBottomCatchUpActive: false,
                explicitBottomAnimationActive: false,
                followEnabled: true,
                followMode: .followingBottom,
                state: ChatViewportState(isAtBottom: false, isContentScrollable: true),
                userScrollActive: false,
                previousContentHeight: 800,
                currentContentHeight: 840,
                distanceToBottom: 40
            ),
            "stream signal 早于 Markdown 真实高度发布时，measured growth 必须承接贴底"
        )
        XCTAssertFalse(
            ChatSwiftUIMeasuredGrowthFollowPolicy.shouldFollow(
                generationActive: true,
                endSettleActive: false,
                explicitBottomCatchUpActive: false,
                explicitBottomAnimationActive: true,
                followEnabled: true,
                followMode: .followingBottom,
                state: ChatViewportState(isAtBottom: false, isContentScrollable: true),
                userScrollActive: false,
                previousContentHeight: 800,
                currentContentHeight: 840,
                distanceToBottom: 40
            ),
            "显式回底动画持有滚动权时，measured growth 不得竞争写底锚"
        )
        XCTAssertTrue(
            ChatSwiftUIMeasuredGrowthFollowPolicy.shouldFollow(
                generationActive: false,
                endSettleActive: false,
                explicitBottomCatchUpActive: true,
                explicitBottomAnimationActive: false,
                followEnabled: true,
                followMode: .followingBottom,
                state: ChatViewportState(isAtBottom: false, isContentScrollable: true),
                userScrollActive: false,
                previousContentHeight: 800,
                currentContentHeight: 840,
                distanceToBottom: 40
            ),
            "terminal 尾行解冻后的真实高度增长由 explicit bottom 临时所有权承接"
        )
        XCTAssertTrue(
            ChatSwiftUIMeasuredGrowthFollowPolicy.shouldTrackGrowth(
                generationActive: false,
                endSettleActive: true,
                explicitBottomCatchUpActive: false,
                followEnabled: true,
                followMode: .followingBottom,
                state: ChatViewportState(isAtBottom: true, isContentScrollable: false),
                userScrollActive: false,
                previousContentHeight: 800,
                currentContentHeight: 840
            ),
            "即使增长时仍贴底或不足一屏，也要重置 settle 静默窗口以等待后续连锁重排"
        )
    }

    func testExplicitBottomReanchorsWhenTheRestoredTailChangesHeight() {
        let baseState = ChatViewportState(isAtBottom: true, isContentScrollable: true)

        XCTAssertTrue(
            ChatSwiftUIExplicitBottomLayoutPolicy.shouldReanchor(
                forceActive: true,
                explicitBottomAnimationActive: false,
                state: baseState,
                userScrollActive: false,
                previousContentHeight: 13_200,
                currentContentHeight: 9_900
            ),
            "尾行由 fallback 切到最终 Markdown 后即使高度收缩，也必须重新解析同一个语义底锚"
        )
        XCTAssertTrue(
            ChatSwiftUIExplicitBottomLayoutPolicy.shouldReanchor(
                forceActive: true,
                explicitBottomAnimationActive: false,
                state: baseState,
                userScrollActive: false,
                previousContentHeight: 9_900,
                currentContentHeight: 10_200
            ),
            "显式回底收敛期间的迟到高度增长仍需跟到底部"
        )
        XCTAssertFalse(
            ChatSwiftUIExplicitBottomLayoutPolicy.shouldReanchor(
                forceActive: false,
                explicitBottomAnimationActive: false,
                state: baseState,
                userScrollActive: false,
                previousContentHeight: 13_200,
                currentContentHeight: 9_900
            ),
            "没有显式回底所有权时，普通布局收缩不能主动抢滚动"
        )
        XCTAssertFalse(
            ChatSwiftUIExplicitBottomLayoutPolicy.shouldReanchor(
                forceActive: true,
                explicitBottomAnimationActive: false,
                state: ChatViewportState(followPaused: true, isContentScrollable: true),
                userScrollActive: true,
                previousContentHeight: 13_200,
                currentContentHeight: 9_900
            ),
            "用户手势或暂停态必须优先于回底收敛"
        )
    }

    func testImmediateBottomWritesYieldToExplicitBottomAnimation() {
        XCTAssertFalse(
            ChatSwiftUIBottomWritePolicy.canIssueImmediateWrite(
                explicitBottomAnimationActive: true
            )
        )
        XCTAssertTrue(
            ChatSwiftUIBottomWritePolicy.canIssueImmediateWrite(
                explicitBottomAnimationActive: false
            )
        )
    }

    func testGenerationEndSettleUsesRollingQuietWindowWithAbsoluteLimit() {
        XCTAssertFalse(
            ChatSwiftUIGenerationEndSettlePolicy.shouldFinish(
                elapsedFrames: 12,
                quietElapsed: ChatSwiftUIGenerationEndSettlePolicy.quietDuration / 2,
                explicitBottomAnimationActive: false
            )
        )
        XCTAssertTrue(
            ChatSwiftUIGenerationEndSettlePolicy.shouldFinish(
                elapsedFrames: ChatSwiftUIGenerationEndSettlePolicy.quietFrames,
                quietElapsed: ChatSwiftUIGenerationEndSettlePolicy.quietDuration,
                explicitBottomAnimationActive: false
            )
        )
        XCTAssertFalse(
            ChatSwiftUIGenerationEndSettlePolicy.shouldFinish(
                elapsedFrames: ChatSwiftUIGenerationEndSettlePolicy.quietFrames,
                quietElapsed: ChatSwiftUIGenerationEndSettlePolicy.quietDuration,
                explicitBottomAnimationActive: true
            )
        )
        XCTAssertTrue(
            ChatSwiftUIGenerationEndSettlePolicy.shouldFinish(
                elapsedFrames: ChatSwiftUIGenerationEndSettlePolicy.maxFrames,
                quietElapsed: 0,
                explicitBottomAnimationActive: true
            )
        )
    }

    func testDefaultSwiftUIListWiresTheFocusedBottomPolicies() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory.deletingLastPathComponent()
                .appendingPathComponent("iosApp/ChatCollectionMessageList.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("ChatSwiftUIExplicitBottomPolicy.plan("))
        XCTAssertTrue(source.contains("guard !reduceMotion else"))
        XCTAssertTrue(source.contains("completionCriteria: .logicallyComplete"))
        XCTAssertTrue(source.contains("completeExplicitBottomAnimationIfNeeded"))
        guard let completionStart = source.range(of: "private func completeExplicitBottomAnimationIfNeeded()"),
              let completionEnd = source.range(
                of: "private func cancelExplicitBottomAnimation()",
                range: completionStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected the explicit-bottom completion body")
        }
        let completionBody = source[completionStart.upperBound..<completionEnd.lowerBound]
        XCTAssertTrue(completionBody.contains("runtime.explicitBottomAnimationActive = false"))
        XCTAssertTrue(completionBody.contains("runtime.followMode = .followingBottom"))
        XCTAssertTrue(completionBody.contains("scheduleStreamBottomFollow()"))
        XCTAssertTrue(completionBody.contains("scrollToBottomIfScrollable()"))
        XCTAssertTrue(source.contains("case .wait:\n                    continue"))
        XCTAssertTrue(source.contains("runtime.userScrollActive = false"))
        XCTAssertTrue(source.contains("ChatSwiftUIMeasuredGrowthFollowPolicy.shouldFollow("))
        XCTAssertTrue(source.contains("followMeasuredStreamGrowthToBottom()"))
        guard let growthFollowStart = source.range(of: "private func followMeasuredStreamGrowthToBottom()"),
              let growthFollowEnd = source.range(
                of: "private func scrollToBottomAnchor(",
                range: growthFollowStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected the measured-growth follow body")
        }
        let growthFollowBody = source[growthFollowStart.upperBound..<growthFollowEnd.lowerBound]
        XCTAssertTrue(growthFollowBody.contains("await Task.yield()"))
        XCTAssertTrue(growthFollowBody.contains("withAnimation(.linear(duration: 0.08))"))
        XCTAssertTrue(growthFollowBody.contains("displaySetting.showBottomFollowAnimation"))
        XCTAssertTrue(
            source.contains(
                "animateMeasuredGrowth: shouldFollowMeasuredGrowth && !shouldReanchorExplicitBottomLayout"
            )
        )
        guard let handlerStart = source.range(of: "private func handleSignal("),
              let deltaStart = source.range(
                of: "case .assistantStreamDelta:",
                range: handlerStart.upperBound..<source.endIndex
              ),
              let deltaEnd = source.range(
                of: "case .generationCompleted",
                range: deltaStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected the assistant stream delta handler")
        }
        let deltaBody = source[deltaStart.upperBound..<deltaEnd.lowerBound]
        XCTAssertFalse(
            deltaBody.contains("scheduleStreamBottomFollow()"),
            "delta 到来时的旧高度不能抢在真实布局增长前无动画重锚"
        )
        XCTAssertTrue(source.contains(".onGeometryChange(for: Bool?.self)"))
        XCTAssertFalse(source.contains("onVisibilityChanged(true)"))
        XCTAssertTrue(source.contains("releaseExplicitBottomLiveTailIfSettled()"))
        XCTAssertTrue(source.contains("scrollToBottomSource: NativeTimelineBottomIntentSource"))
        XCTAssertTrue(source.contains(".transaction(value: signal)"))
        XCTAssertFalse(source.contains(".transaction { transaction in\n            if signal.event == .assistantStreamDelta"))
    }

    func testLegacyStreamFinishMapsToStreamClosedEvent() {
        XCTAssertEqual(ChatMessageUpdateReason.streamFinish.event, .assistantStreamClosed)
    }

    func testAssistantStreamClosedIsNotTerminalGenerationComplete() {
        XCTAssertEqual(ChatEvent.assistantStreamClosed.contract.payload, .stream)
        XCTAssertEqual(ChatEvent.generationCompleted.contract.payload, .generation)
        XCTAssertTrue(ChatEvent.assistantStreamClosed.contract.affectsViewport)
        XCTAssertTrue(ChatEvent.generationCompleted.contract.affectsViewport)
    }

    func testConversationEventsResetViewportStateWithoutManualScroll() {
        var state = ChatViewportState(
            followPaused: true,
            userDragging: true,
            showScrollToBottom: true,
            isAtBottom: false,
            isContentScrollable: true,
            conversationLoadToken: 4
        )

        let commands = ChatViewportReducer.reduce(
            event: .conversationSwitched,
            state: &state,
            environment: .init(followEnabled: true, generationActive: false)
        )

        XCTAssertEqual(commands, [.resetForConversationSwitch])
        XCTAssertFalse(state.followPaused)
        XCTAssertFalse(state.userDragging)
        XCTAssertFalse(state.showScrollToBottom)
        XCTAssertFalse(state.isContentScrollable)
        XCTAssertEqual(state.conversationLoadToken, 5)
    }

    func testStreamDeltaFollowsBottomWithLightAnimationWhenAutoFollowing() {
        let command = ChatViewportPolicy.commandForMessageUpdate(
            reason: .streamDelta,
            canAutoFollow: true,
            isContentScrollable: true
        )

        XCTAssertEqual(
            command,
            .followBottom(animated: true, targetBottomAnchor: true, deferred: false)
        )
    }

    func testUserAppendDoesNotScrollWhenContentIsNotScrollable() {
        let command = ChatViewportPolicy.commandForMessageUpdate(
            reason: .userAppend,
            canAutoFollow: true,
            isContentScrollable: false
        )

        XCTAssertEqual(command, .none)
    }

    func testUserAppendFollowsBottomWithoutAnimationWhenScrollable() {
        // 滚动本身无动画:行入场 spring transition 已驱动上屏动效,
        // 滚动若再走动画会和入场抢,产生画面跳动。
        let command = ChatViewportPolicy.commandForMessageUpdate(
            reason: .userAppend,
            canAutoFollow: true,
            isContentScrollable: true
        )

        XCTAssertEqual(
            command,
            .followBottom(animated: false, targetBottomAnchor: true, deferred: false)
        )
    }

    func testUserAppendResumesFollowWhenControllerWasPausedByHistoryDrag() {
        var state = ChatViewportState(
            followPaused: true,
            userDragging: true,
            showScrollToBottom: true,
            isAtBottom: false,
            isContentScrollable: true,
            liveRenderingFarFromBottom: false,
            conversationLoadToken: 0
        )

        let commands = ChatViewportReducer.reduce(
            event: .userMessageAppended,
            state: &state,
            environment: .init(followEnabled: true, generationActive: true)
        )

        XCTAssertEqual(
            commands,
            [.followBottom(animated: false, targetBottomAnchor: true, deferred: false)]
        )
        XCTAssertFalse(state.followPaused)
        XCTAssertFalse(state.userDragging)
    }

    func testSettingsRefreshDoesNotStealViewport() {
        let command = ChatViewportPolicy.commandForMessageUpdate(
            reason: .settingsRefresh,
            canAutoFollow: true,
            isContentScrollable: true
        )

        XCTAssertEqual(command, .none)
    }

    func testBranchChangeDoesNotStealViewport() {
        let command = ChatViewportPolicy.commandForMessageUpdate(
            reason: .branchChange,
            canAutoFollow: true,
            isContentScrollable: true
        )

        XCTAssertEqual(command, .none)
    }

    func testInitialLoadDoesNotIssueManualScrollCommand() {
        let command = ChatViewportPolicy.commandForMessageUpdate(
            reason: .initialLoad,
            canAutoFollow: true,
            isContentScrollable: true
        )

        XCTAssertEqual(command, .none)
    }

    func testConversationSwitchDoesNotIssueManualScrollCommand() {
        let command = ChatViewportPolicy.commandForMessageUpdate(
            reason: .conversationSwitch,
            canAutoFollow: true,
            isContentScrollable: true
        )

        XCTAssertEqual(command, .none)
    }

    func testContentBecomingScrollableFollowsStreamingWithoutAnimation() {
        let command = ChatViewportPolicy.commandForContentBecameScrollable(
            canAutoFollow: true,
            isStreamingFollowActive: true
        )

        XCTAssertEqual(
            command,
            .followBottom(animated: false, targetBottomAnchor: true, deferred: false)
        )
    }

    func testGeometryTransitionToScrollableFollowsActiveGeneration() {
        var state = ChatViewportState(
            followPaused: false,
            userDragging: false,
            showScrollToBottom: false,
            isAtBottom: true,
            isContentScrollable: false,
            liveRenderingFarFromBottom: false,
            conversationLoadToken: 0
        )

        let commands = ChatViewportReducer.reduceGeometry(
            ChatViewportGeometrySnapshot(
                atBottom: false,
                isContentScrollable: true,
                liveRenderingFarFromBottom: false
            ),
            hasMessages: true,
            state: &state,
            environment: .init(followEnabled: true, generationActive: true)
        )

        XCTAssertEqual(
            commands,
            [.followBottom(animated: false, targetBottomAnchor: true, deferred: false)]
        )
        XCTAssertTrue(state.isContentScrollable)
        XCTAssertFalse(state.showScrollToBottom)
    }

    func testGeometryTransitionToScrollableDoesNotStealViewportWhenPaused() {
        var state = ChatViewportState(
            followPaused: true,
            userDragging: false,
            showScrollToBottom: false,
            isAtBottom: false,
            isContentScrollable: false,
            liveRenderingFarFromBottom: false,
            conversationLoadToken: 0
        )

        let commands = ChatViewportReducer.reduceGeometry(
            ChatViewportGeometrySnapshot(
                atBottom: false,
                isContentScrollable: true,
                liveRenderingFarFromBottom: false
            ),
            hasMessages: true,
            state: &state,
            environment: .init(followEnabled: true, generationActive: true)
        )

        XCTAssertEqual(commands, [.showBottomButton(true)])
        XCTAssertTrue(state.isContentScrollable)
        XCTAssertTrue(state.showScrollToBottom)
    }

    func testExplicitBottomRequestUsesBottomAnchor() {
        XCTAssertEqual(
            ChatViewportPolicy.commandForExplicitBottomRequest(),
            .followBottom(animated: true, targetBottomAnchor: true, deferred: false)
        )
    }

    func testConversationBottomAnchorRetryStopsDuringUserScrollOrPause() {
        XCTAssertTrue(
            ChatViewportPolicy.canRunConversationBottomAnchorRetry(
                userScrollActive: false,
                followPaused: false
            )
        )
        XCTAssertFalse(
            ChatViewportPolicy.canRunConversationBottomAnchorRetry(
                userScrollActive: true,
                followPaused: false
            )
        )
        XCTAssertFalse(
            ChatViewportPolicy.canRunConversationBottomAnchorRetry(
                userScrollActive: false,
                followPaused: true
            )
        )
    }

    func testReachingBottomResumesFollowEvenAfterDragEnds() {
        // 该用例模拟用户自己惯性滑回底部(userScrollActive=true),因此仍应清除暂停。
        XCTAssertFalse(
            ChatViewportPolicy.followPausedAfterGeometryChange(
                wasPaused: true,
                userDragging: false,
                atBottom: true,
                userScrollActive: true
            )
        )
    }

    func testDraggingAwayFromBottomPausesFollow() {
        XCTAssertTrue(
            ChatViewportPolicy.followPausedAfterGeometryChange(
                wasPaused: false,
                userDragging: true,
                atBottom: false,
                userScrollActive: false
            )
        )
    }

    func testGeometryAwayFromBottomPreservesPauseWhenNotDragging() {
        XCTAssertTrue(
            ChatViewportPolicy.followPausedAfterGeometryChange(
                wasPaused: true,
                userDragging: false,
                atBottom: false,
                userScrollActive: false
            )
        )
    }

    func testFakeAtBottomFromEstimatedHeightDoesNotClearPause() {
        // 估算高度失真导致的瞬时假 atBottom(既非拖拽也非用户主动滚动)不应清掉暂停。
        XCTAssertTrue(
            ChatViewportPolicy.followPausedAfterGeometryChange(
                wasPaused: true,
                userDragging: false,
                atBottom: true,
                userScrollActive: false
            )
        )
    }

    func testTimelineFollowStartGenerationArmsBottomFollowNearEnd() {
        var state = ChatTimelineFollowState()

        let command = ChatTimelineFollowPolicy.startGeneration(
            autoScrollEnabled: true,
            isAtBottom: false,
            isNearEnd: true,
            state: &state
        )

        XCTAssertEqual(state.mode, .followingBottom)
        XCTAssertEqual(command, .requestBottom(reason: "generation-start", animated: false))
        XCTAssertFalse(state.showBottomButton)
    }

    func testTimelineProgrammaticScrollDoesNotPauseFollow() {
        var state = ChatTimelineFollowState(mode: .followingBottom)
        _ = state.beginProgrammaticScroll()

        let command = ChatTimelineFollowPolicy.scrollProgressChanged(
            isScrollInProgress: true,
            userScrollInTimeline: true,
            userDragInTimeline: false,
            activeGeneration: true,
            isAtBottom: false,
            state: &state
        )

        XCTAssertEqual(command, .none)
        XCTAssertEqual(state.mode, .followingBottom)
    }

    func testTimelineUserDragDuringGenerationPausesFollow() {
        var state = ChatTimelineFollowState(mode: .followingBottom)

        let command = ChatTimelineFollowPolicy.scrollProgressChanged(
            isScrollInProgress: true,
            userScrollInTimeline: true,
            userDragInTimeline: true,
            activeGeneration: true,
            isAtBottom: false,
            state: &state
        )

        XCTAssertEqual(state.mode, .pausedForUser)
        XCTAssertEqual(command, .showBottomButton(true))
        XCTAssertTrue(state.showBottomButton)
    }

    func testTimelineUserReleaseAtBottomResumesFollow() {
        var state = ChatTimelineFollowState(mode: .pausedForUser, showBottomButton: true)

        let command = ChatTimelineFollowPolicy.scrollProgressChanged(
            isScrollInProgress: false,
            userScrollInTimeline: false,
            userDragInTimeline: false,
            activeGeneration: true,
            isAtBottom: true,
            state: &state
        )

        XCTAssertEqual(state.mode, .followingBottom)
        XCTAssertEqual(command, .showBottomButton(false))
        XCTAssertFalse(state.showBottomButton)
    }

    func testTimelineStreamChunkRequestsImmediateBottomOnlyWhenFollowing() {
        let following = ChatTimelineFollowState(mode: .followingBottom)
        let paused = ChatTimelineFollowState(mode: .pausedForUser)

        XCTAssertEqual(
            ChatTimelineFollowPolicy.streamChunk(
                autoScrollEnabled: true,
                userScrollInTimeline: false,
                state: following
            ),
            .requestBottom(reason: "stream-chunk", animated: false)
        )
        XCTAssertEqual(
            ChatTimelineFollowPolicy.streamChunk(
                autoScrollEnabled: true,
                userScrollInTimeline: false,
                state: paused
            ),
            .none
        )
    }

    func testTimelineGenerationEndSettlePlanMatchesAndroidPolicyShape() {
        XCTAssertEqual(
            ChatTimelineGenerationEndSettlePolicy.effectPlan(
                wasActiveGeneration: true,
                activeGeneration: false,
                autoScrollEnabled: true
            ),
            ChatTimelineGenerationEndEffectPlan(
                runEndSettleBeforeIdle: true,
                enterIdleAfterEndSettle: true
            )
        )
        XCTAssertTrue(
            ChatTimelineGenerationEndSettlePolicy.canSettleNow(
                mode: .followingBottom,
                userScrollInTimeline: false,
                scrollInProgress: false
            )
        )
        XCTAssertFalse(
            ChatTimelineGenerationEndSettlePolicy.canAttemptSettle(
                mode: .pausedForUser,
                userScrollInTimeline: false
            )
        )
    }

    func testUserScrollActiveAtBottomResumesFollow() {
        // 惯性减速中(isDecelerating)滑到底部,应恢复跟随。
        XCTAssertFalse(
            ChatViewportPolicy.followPausedAfterGeometryChange(
                wasPaused: true,
                userDragging: false,
                atBottom: true,
                userScrollActive: true
            )
        )
    }

    func testReduceGeometryDoesNotDowngradeLODWhileAutoFollowing() {
        var state = ChatViewportState(
            followPaused: false,
            userDragging: false,
            showScrollToBottom: false,
            isAtBottom: true,
            isContentScrollable: true,
            liveRenderingFarFromBottom: false,
            conversationLoadToken: 0
        )

        _ = ChatViewportReducer.reduceGeometry(
            ChatViewportGeometrySnapshot(
                atBottom: false,
                isContentScrollable: true,
                liveRenderingFarFromBottom: true,
                userScrollActive: false
            ),
            hasMessages: true,
            state: &state,
            environment: .init(followEnabled: true, generationActive: true)
        )

        XCTAssertFalse(state.followPaused)
        XCTAssertFalse(state.liveRenderingFarFromBottom)
    }

    func testReduceGeometryDowngradesLODWhenUserHasLeftBottom() {
        var state = ChatViewportState(
            followPaused: false,
            userDragging: true,
            showScrollToBottom: false,
            isAtBottom: true,
            isContentScrollable: true,
            liveRenderingFarFromBottom: false,
            conversationLoadToken: 0
        )

        _ = ChatViewportReducer.reduceGeometry(
            ChatViewportGeometrySnapshot(
                atBottom: false,
                isContentScrollable: true,
                liveRenderingFarFromBottom: true,
                userScrollActive: false
            ),
            hasMessages: true,
            state: &state,
            environment: .init(followEnabled: true, generationActive: true)
        )

        XCTAssertTrue(state.userDragging)
        XCTAssertTrue(state.liveRenderingFarFromBottom)
    }
}
