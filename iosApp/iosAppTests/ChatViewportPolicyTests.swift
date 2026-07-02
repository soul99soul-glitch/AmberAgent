import XCTest
@testable import iosApp

final class ChatViewportPolicyTests: XCTestCase {

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

    func testReachingBottomResumesFollowEvenAfterDragEnds() {
        XCTAssertFalse(
            ChatViewportPolicy.followPausedAfterGeometryChange(
                wasPaused: true,
                userDragging: false,
                atBottom: true
            )
        )
    }

    func testDraggingAwayFromBottomPausesFollow() {
        XCTAssertTrue(
            ChatViewportPolicy.followPausedAfterGeometryChange(
                wasPaused: false,
                userDragging: true,
                atBottom: false
            )
        )
    }

    func testGeometryAwayFromBottomPreservesPauseWhenNotDragging() {
        XCTAssertTrue(
            ChatViewportPolicy.followPausedAfterGeometryChange(
                wasPaused: true,
                userDragging: false,
                atBottom: false
            )
        )
    }
}
