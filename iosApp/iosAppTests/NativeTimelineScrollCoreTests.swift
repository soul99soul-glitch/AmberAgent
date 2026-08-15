import CoreGraphics
import UIKit
import XCTest
@testable import iosApp

final class NativeTimelineScrollCoreTests: XCTestCase {
    private final class InteractionReportingScrollView: UIScrollView {
        var reportsTracking = false
        var reportsDragging = false
        var reportsDecelerating = false

        override var isTracking: Bool { reportsTracking }
        override var isDragging: Bool { reportsDragging }
        override var isDecelerating: Bool { reportsDecelerating }
    }

    private final class NonConvergingScrollView: UIScrollView {
        override func setContentOffset(_ contentOffset: CGPoint, animated: Bool) {
            super.setContentOffset(CGPoint(x: 0, y: 0), animated: false)
        }
    }

    private final class LayoutCountingScrollView: UIScrollView {
        var layoutIfNeededCallCount = 0

        override func layoutIfNeeded() {
            layoutIfNeededCallCount += 1
            super.layoutIfNeeded()
        }
    }

    private func geometry(
        offsetY: CGFloat = 0,
        contentHeight: CGFloat = 1_200,
        viewportHeight: CGFloat = 800,
        adjustedInsetTop: CGFloat = 0,
        adjustedInsetBottom: CGFloat = 120,
        distanceToBottom: CGFloat = 0,
        userInteracting: Bool = false
    ) -> NativeTimelineScrollGeometry {
        NativeTimelineScrollGeometry(
            offsetY: offsetY,
            contentHeight: contentHeight,
            viewportHeight: viewportHeight,
            adjustedInsetTop: adjustedInsetTop,
            adjustedInsetBottom: adjustedInsetBottom,
            distanceToBottom: distanceToBottom,
            userInteracting: userInteracting
        )
    }

    @MainActor
    func testAttachOnlyConnectsScrollViewWithoutChangingPositionOrFollowState() {
        let driver = NativeTimelineScrollDriver()
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        scrollView.contentOffset = CGPoint(x: 0, y: 220)

        driver.attach(scrollView)

        XCTAssertEqual(scrollView.contentOffset.y, 220, accuracy: 0.5)
        XCTAssertFalse(driver.isFollowingBottomOrKeyboardFocus)
    }

    @MainActor
    func testReplacingAttachedScrollViewDropsMotionStateWithoutMovingReplacement() {
        let driver = NativeTimelineScrollDriver()
        let first = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        first.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(first)
        driver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        XCTAssertTrue(driver.isFollowingBottomOrKeyboardFocus)

        let replacement = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        replacement.contentSize = CGSize(width: 390, height: 1_800)
        replacement.contentOffset = CGPoint(x: 0, y: 240)
        let didAttach = driver.attach(replacement)

        XCTAssertTrue(didAttach)
        XCTAssertEqual(replacement.contentOffset.y, 240, accuracy: 0.5)
        XCTAssertFalse(driver.isFollowingBottomOrKeyboardFocus)
    }

    @MainActor
    func testDisablingAutomaticFollowStopsLayoutGrowthFromAdvancingOffset() {
        let driver = NativeTimelineScrollDriver()
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)
        driver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        XCTAssertEqual(scrollView.contentOffset.y, 600, accuracy: 0.5)

        driver.setAutomaticFollowEnabled(false)
        scrollView.contentSize = CGSize(width: 390, height: 1_800)
        driver.handleLayoutMetricsChanged()

        XCTAssertEqual(scrollView.contentOffset.y, 600, accuracy: 0.5)
        XCTAssertFalse(driver.isFollowingBottomOrKeyboardFocus)
    }

    @MainActor
    func testDisabledAutomaticFollowDoesNotReplayBottomAfterFallback() {
        var shouldReplayBottom: Bool?
        let driver = NativeTimelineScrollDriver()
        driver.onFallback = { _, replayBottom in
            shouldReplayBottom = replayBottom
        }
        driver.setAutomaticFollowEnabled(false)

        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)
        driver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))

        scrollView.contentOffset.x = 12
        driver.handleLayoutMetricsChanged()
        scrollView.contentOffset.x = 12
        driver.handleLayoutMetricsChanged()

        XCTAssertEqual(shouldReplayBottom, false)
    }

    @MainActor
    func testTerminalSettleFollowsLateLayoutThenReleasesBottomOwnership() async {
        let driver = NativeTimelineScrollDriver()
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)
        driver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))

        driver.submit(.generationTerminated)
        XCTAssertTrue(driver.isFollowingBottomOrKeyboardFocus)

        try? await Task.sleep(for: .milliseconds(100))
        scrollView.contentSize = CGSize(width: 390, height: 1_700)
        driver.handleLayoutMetricsChanged()
        XCTAssertTrue(driver.isFollowingBottomOrKeyboardFocus)

        // 晚到增长改为基础 τ 缓动追入后，收敛需要 ~5τ（≈0.3s）+ 观察器
        // 唤醒延迟；旧瞬时钉底一帧收敛故曾是 450ms。交还契约不变，仅放宽等待。
        try? await Task.sleep(for: .milliseconds(900))
        XCTAssertEqual(scrollView.contentOffset.y, 900, accuracy: 2)
        XCTAssertFalse(
            driver.isFollowingBottomOrKeyboardFocus,
            "终态晚到布局收敛后必须交还滚动所有权"
        )
    }

    func testGenerationTerminalRebasesFinalHeightAndBecomesIdleAfterQuietInterval() {
        let following = NativeTimelineScrollState.followingBottom(
            virtualOffset: 700,
            target: 700,
            lastFollowRequestAt: 1
        )
        let terminal = NativeTimelineScrollCore.reduce(
            state: following,
            intent: .generationTerminated,
            geometry: geometry(offsetY: 520, contentHeight: 1_200, distanceToBottom: 0),
            now: 2
        )

        XCTAssertEqual(
            terminal.state,
            .settlingAfterTerminal(virtualOffset: 520, target: 520, lastLayoutAt: 2)
        )
        XCTAssertEqual(terminal.actions, [.startFrameDriver])

        let settled = NativeTimelineScrollCore.tick(
            state: terminal.state,
            geometry: geometry(offsetY: 520, contentHeight: 1_200, distanceToBottom: 0),
            now: 2 + NativeTimelineScrollCore.idleStopInterval + 0.01,
            dt: 1.0 / 120.0
        )
        XCTAssertEqual(settled.state, .idle)
        XCTAssertEqual(settled.actions, [.stopFrameDriver])
    }

    /// 终态收锚契约：晚到布局改变 bottomTarget 时，必须一拍钉住新底，
    /// 不允许指数缓动的中间值（缓动拖出的"再滑一段"就是完成后的不流畅感）。
    func testTerminalSettleSnapsToLateBottomTargetWithoutEasing() {
        let terminal = NativeTimelineScrollState.settlingAfterTerminal(
            virtualOffset: 520,
            target: 520,
            lastLayoutAt: 2
        )
        // 终态布局落地：内容收缩，bottomTarget 520 → 320（contentHeight 1000）。
        let ticked = NativeTimelineScrollCore.tick(
            state: terminal,
            geometry: geometry(offsetY: 520, contentHeight: 1_000, distanceToBottom: 200),
            now: 2 + 1.0 / 120.0,
            dt: 1.0 / 120.0
        )
        XCTAssertEqual(ticked.actions, [.writeOffsetY(320)], "终态收锚必须一拍贴底")
        XCTAssertEqual(
            ticked.state,
            .settlingAfterTerminal(
                virtualOffset: 320,
                target: 320,
                lastLayoutAt: 2 + 1.0 / 120.0
            )
        )

        // 目标稳定后静默期满交还所有权，不再有额外写入。
        let settled = NativeTimelineScrollCore.tick(
            state: ticked.state,
            geometry: geometry(offsetY: 320, contentHeight: 1_000, distanceToBottom: 0),
            now: 2 + 1.0 / 120.0 + NativeTimelineScrollCore.idleStopInterval + 0.01,
            dt: 1.0 / 120.0
        )
        XCTAssertEqual(settled.state, .idle)
        XCTAssertEqual(settled.actions, [.stopFrameDriver])
    }

    func testGenerationTerminalDoesNotTakeBottomFromPausedUser() {
        let result = NativeTimelineScrollCore.reduce(
            state: .pausedForUser,
            intent: .generationTerminated,
            geometry: geometry(offsetY: 520, distanceToBottom: 0),
            now: 2
        )

        XCTAssertEqual(result.state, .pausedForUser)
        XCTAssertEqual(result.actions, [])
    }

    func testUserDragEndedResumesFollowingOnlyAtBottom() {
        let cases: [(isAtBottom: Bool, expected: NativeTimelineScrollState)] = [
            (false, .pausedForUser),
            (true, .followingBottom(virtualOffset: 520, target: 520, lastFollowRequestAt: 9)),
        ]

        for testCase in cases {
            let result = NativeTimelineScrollCore.reduce(
                state: .pausedForUser,
                intent: .userDragEnded(isAtBottom: testCase.isAtBottom),
                geometry: geometry(offsetY: 520, distanceToBottom: testCase.isAtBottom ? 0 : 200),
                now: 9
            )

            XCTAssertEqual(result.state, testCase.expected)
            XCTAssertEqual(result.actions, [])
        }
    }

    @MainActor
    func testInvalidateAndReattachPreservesExistingHistoryPosition() {
        let driver = NativeTimelineScrollDriver()
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)
        driver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        driver.submit(.userDragBegan)
        scrollView.contentOffset = CGPoint(x: 0, y: 180)

        driver.invalidate()
        driver.attach(scrollView)

        XCTAssertEqual(scrollView.contentOffset.y, 180, accuracy: 0.5)
        XCTAssertFalse(driver.isFollowingBottomOrKeyboardFocus)
    }

    func testReturnToBottomPolicyUsesLiveNativeDistanceInsteadOfStaleCachedGeometry() {
        XCTAssertFalse(
            NativeTimelineScrollReturnPolicy.returnedToBottom(
                liveDistanceToBottom: 400,
                cachedNearBottom: true,
                threshold: 96
            )
        )
        XCTAssertTrue(
            NativeTimelineScrollReturnPolicy.returnedToBottom(
                liveDistanceToBottom: 24,
                cachedNearBottom: false,
                threshold: 96
            )
        )
        XCTAssertTrue(
            NativeTimelineScrollReturnPolicy.returnedToBottom(
                liveDistanceToBottom: nil,
                cachedNearBottom: true,
                threshold: 96
            )
        )
    }

    func testMessageAnchorWaitsForTheOwningConversationAndConsumesOnce() {
        let anchor = ChatMessageAnchor(
            conversationID: "conversation-a",
            messageID: "message-image",
            toolCallID: "tool-image"
        )

        XCTAssertNil(NativeTimelineMessageAnchorPolicy.targetEntryID(
            request: anchor,
            consumed: nil,
            currentConversationID: "conversation-b",
            availableMessageIDs: ["message-image"],
            availableImageToolCallIDs: ["tool-image"]
        ))
        XCTAssertNil(NativeTimelineMessageAnchorPolicy.targetEntryID(
            request: anchor,
            consumed: nil,
            currentConversationID: "conversation-a",
            availableMessageIDs: [],
            availableImageToolCallIDs: ["tool-image"]
        ))
        XCTAssertNil(NativeTimelineMessageAnchorPolicy.targetEntryID(
            request: anchor,
            consumed: nil,
            currentConversationID: "conversation-a",
            availableMessageIDs: ["message-image"],
            availableImageToolCallIDs: []
        ))
        XCTAssertEqual(NativeTimelineMessageAnchorPolicy.targetEntryID(
            request: anchor,
            consumed: nil,
            currentConversationID: "conversation-a",
            availableMessageIDs: ["message-image"],
            availableImageToolCallIDs: ["tool-image"]
        ), ChatImageGenerationAnchorTarget.id(toolCallID: "tool-image"))
        XCTAssertNil(NativeTimelineMessageAnchorPolicy.targetEntryID(
            request: anchor,
            consumed: anchor,
            currentConversationID: "conversation-a",
            availableMessageIDs: ["message-image"],
            availableImageToolCallIDs: ["tool-image"]
        ))

        XCTAssertFalse(NativeTimelineMessageAnchorPolicy.canSchedule(
            nativeDriverActive: false,
            fallbackActive: false
        ))
        XCTAssertTrue(NativeTimelineMessageAnchorPolicy.canSchedule(
            nativeDriverActive: true,
            fallbackActive: false
        ))
        XCTAssertTrue(NativeTimelineMessageAnchorPolicy.canSchedule(
            nativeDriverActive: false,
            fallbackActive: true
        ))
    }

    @MainActor
    func testTrackingPhaseBeginsUserDragBeforeUIKitFlagsCatchUp() {
        XCTAssertTrue(
            NativeChatTimelineView.shouldBeginNativeUserDrag(
                phase: .tracking,
                isUIKitUserInteracting: false
            )
        )
        XCTAssertFalse(
            NativeChatTimelineView.shouldBeginNativeUserDrag(
                phase: .interacting,
                isUIKitUserInteracting: false
            ),
            "程序化滚动可能报告 interacting，但没有 UIKit 手势时不能暂停跟随"
        )
        XCTAssertTrue(
            NativeChatTimelineView.shouldBeginNativeUserDrag(
                phase: .interacting,
                isUIKitUserInteracting: true
            )
        )
    }

    @MainActor
    func testDriverClampsHorizontalOffsetDriftWithoutFallingBack() {
        var fallbackReason: NativeTimelineScrollFallbackReason?
        var shouldReplayBottom: Bool?
        let driver = NativeTimelineScrollDriver()
        driver.onFallback = { reason, replayBottom in
            fallbackReason = reason
            shouldReplayBottom = replayBottom
        }
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        scrollView.contentOffset = CGPoint(x: 4, y: 0)

        driver.attach(scrollView)

        XCTAssertNil(fallbackReason)
        XCTAssertNil(shouldReplayBottom)
        XCTAssertEqual(scrollView.contentOffset.x, 0, accuracy: 0.5)
    }

    @MainActor
    func testDriverFallsBackOnRepeatedHorizontalOffsetDrift() {
        var fallbackReason: NativeTimelineScrollFallbackReason?
        var shouldReplayBottom: Bool?
        let driver = NativeTimelineScrollDriver()
        driver.onFallback = { reason, replayBottom in
            fallbackReason = reason
            shouldReplayBottom = replayBottom
        }
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        scrollView.contentOffset = CGPoint(x: 4, y: 0)

        driver.attach(scrollView)
        driver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        scrollView.contentOffset.x = 4
        driver.handleLayoutMetricsChanged()

        XCTAssertEqual(fallbackReason, .horizontalOffsetDrift)
        XCTAssertEqual(shouldReplayBottom, true)
        XCTAssertFalse(driver.isAttached)
    }

    @MainActor
    func testDriverKeepsFollowingAfterHorizontalOffsetClamp() {
        var fallbackReason: NativeTimelineScrollFallbackReason?
        let driver = NativeTimelineScrollDriver()
        driver.onFallback = { reason, _ in
            fallbackReason = reason
        }
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)
        driver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        scrollView.contentOffset.x = 4

        driver.handleLayoutMetricsChanged()

        XCTAssertNil(fallbackReason)
        XCTAssertTrue(driver.isFollowingBottomOrKeyboardFocus)
        XCTAssertEqual(scrollView.contentOffset.x, 0, accuracy: 0.5)
    }

    @MainActor
    func testDriverKeepsKeyboardFocusAfterHorizontalOffsetClamp() {
        var fallbackReason: NativeTimelineScrollFallbackReason?
        let driver = NativeTimelineScrollDriver()
        driver.onFallback = { reason, _ in
            fallbackReason = reason
        }
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)
        driver.submit(.explicitBottom(source: .composerFocus, animated: false, keyboardToken: nil))
        scrollView.contentOffset.x = 4

        driver.handleLayoutMetricsChanged()

        XCTAssertNil(fallbackReason)
        XCTAssertTrue(driver.isFollowingBottomOrKeyboardFocus)
        XCTAssertEqual(scrollView.contentOffset.x, 0, accuracy: 0.5)
    }

    @MainActor
    func testDriverClampsHorizontalOffsetEvenWhileUserIsInteracting() {
        var fallbackReason: NativeTimelineScrollFallbackReason?
        let driver = NativeTimelineScrollDriver()
        driver.onFallback = { reason, _ in
            fallbackReason = reason
        }
        let scrollView = InteractionReportingScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)
        scrollView.reportsDragging = true
        scrollView.contentOffset.x = 4

        driver.handleLayoutMetricsChanged()

        XCTAssertNil(fallbackReason)
        XCTAssertEqual(scrollView.contentOffset.x, 0, accuracy: 0.5)
    }

    @MainActor
    func testPendingBottomConvergenceDoesNotPullDuringUserDrag() async {
        let driver = NativeTimelineScrollDriver()
        let scrollView = InteractionReportingScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)

        driver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        scrollView.contentOffset = CGPoint(x: 0, y: 260)
        scrollView.reportsDragging = true

        await Task.yield()
        await Task.yield()

        XCTAssertEqual(scrollView.contentOffset.y, 260)
    }

    @MainActor
    func testBottomConvergenceExhaustionKeepsNativeDriverAsOwner() async {
        var fallbackReason: NativeTimelineScrollFallbackReason?
        let driver = NativeTimelineScrollDriver()
        driver.onFallback = { reason, _ in fallbackReason = reason }
        let scrollView = NonConvergingScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)

        driver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        for _ in 0..<12 {
            await Task.yield()
        }

        XCTAssertNil(fallbackReason)
        XCTAssertTrue(driver.isAttached)
        XCTAssertTrue(driver.isFollowingBottomOrKeyboardFocus)
    }

    @MainActor
    func testObservedMetricsChangeDoesNotForceASecondScrollLayoutPass() {
        let driver = NativeTimelineScrollDriver()
        let scrollView = LayoutCountingScrollView(
            frame: CGRect(x: 0, y: 0, width: 390, height: 800)
        )
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)
        scrollView.layoutIfNeededCallCount = 0

        driver.handleLayoutMetricsChanged()

        XCTAssertEqual(scrollView.layoutIfNeededCallCount, 0)
    }

    @MainActor
    func testResolverMetricsIgnorePureContentOffsetChanges() {
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        let initial = NativeTimelineScrollViewResolver.Coordinator.Metrics(scrollView)

        scrollView.contentOffset.y = 240

        XCTAssertEqual(
            NativeTimelineScrollViewResolver.Coordinator.Metrics(scrollView),
            initial
        )
    }

    @MainActor
    func testStreamGrowthDuringKeyboardFocusKeepsConvergenceAlive() async {
        let driver = NativeTimelineScrollDriver()
        let scrollView = InteractionReportingScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        driver.attach(scrollView)

        driver.submit(.explicitBottom(source: .composerFocus, animated: false, keyboardToken: nil))
        driver.submit(.streamContentGrew())
        scrollView.contentOffset = CGPoint(x: 0, y: 260)

        try? await Task.sleep(nanoseconds: 50_000_000)

        XCTAssertEqual(scrollView.contentOffset.y, 600, accuracy: 0.5)
    }

    @MainActor
    func testDriverDoesNotReattachAfterFallback() {
        let driver = NativeTimelineScrollDriver()
        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        scrollView.contentSize = CGSize(width: 390, height: 1_400)
        scrollView.contentOffset = CGPoint(x: 4, y: 0)
        driver.attach(scrollView)
        scrollView.contentOffset = .zero

        driver.attach(scrollView)

        XCTAssertEqual(scrollView.contentOffset, .zero)
    }

    func testComposerFocusOverridesHistoryPauseAndRequestsBottomImmediately() {
        let result = NativeTimelineScrollCore.reduce(
            state: .pausedForUser,
            intent: .explicitBottom(source: .composerFocus, animated: false, keyboardToken: 7),
            geometry: geometry(offsetY: 120, distanceToBottom: 600),
            now: 10
        )

        XCTAssertEqual(
            result.state,
            .keyboardFocus(NativeTimelineKeyboardFocusTransaction(token: 7, stableFrames: 0))
        )
        XCTAssertEqual(
            result.actions,
            [.requestBottomAnchor(animated: false, source: .composerFocus)]
        )
    }

    func testComposerFocusOverridesActiveUserInteraction() {
        let result = NativeTimelineScrollCore.reduce(
            state: .pausedForUser,
            intent: .explicitBottom(source: .composerFocus, animated: false, keyboardToken: 9),
            geometry: geometry(offsetY: 120, distanceToBottom: 600, userInteracting: true),
            now: 10
        )

        XCTAssertEqual(
            result.state,
            .keyboardFocus(NativeTimelineKeyboardFocusTransaction(token: 9, stableFrames: 0))
        )
        XCTAssertEqual(
            result.actions,
            [.requestBottomAnchor(animated: false, source: .composerFocus)]
        )
    }

    func testButtonBottomOverridesActiveUserInteraction() {
        let result = NativeTimelineScrollCore.reduce(
            state: .pausedForUser,
            intent: .explicitBottom(source: .button, animated: true, keyboardToken: nil),
            geometry: geometry(offsetY: 120, distanceToBottom: 600, userInteracting: true),
            now: 10
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 120, target: 520, lastFollowRequestAt: 10)
        )
        XCTAssertEqual(
            result.actions,
            [.requestBottomAnchor(animated: true, source: .button)]
        )
    }

    func testBottomTargetUsesOnlyUIKitAdjustedInset() {
        let geo = geometry(
            contentHeight: 1_600,
            viewportHeight: 800,
            adjustedInsetBottom: 80
        )

        XCTAssertEqual(geo.effectiveBottomInset, 80)
        XCTAssertEqual(geo.bottomTarget, 880)
    }

    @MainActor
    func testExplicitBottomNeverScrollsPastUIKitBottomIntoBlankSpace() {
        let driver = NativeTimelineScrollDriver()
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        let viewController = UIViewController()
        window.rootViewController = viewController
        window.makeKeyAndVisible()
        defer { window.isHidden = true }

        let scrollView = UIScrollView(frame: viewController.view.bounds)
        viewController.view.addSubview(scrollView)
        scrollView.contentSize = CGSize(width: 390, height: 1_600)
        scrollView.contentInset.bottom = 80
        driver.attach(scrollView)
        driver.submit(.explicitBottom(source: .composerFocus, animated: false, keyboardToken: nil))

        XCTAssertEqual(
            scrollView.contentOffset.y,
            880,
            accuracy: 0.5,
            "SwiftUI safe-area and keyboard layout already define the viewport; native follow must not expose blank space."
        )
    }

    func testUserDragBeganCancelsFollowingAndPausesProgrammaticScroll() {
        let following = NativeTimelineScrollState.followingBottom(
            virtualOffset: 400,
            target: 520,
            lastFollowRequestAt: 1
        )

        let result = NativeTimelineScrollCore.reduce(
            state: following,
            intent: .userDragBegan,
            geometry: geometry(offsetY: 400, distanceToBottom: 120),
            now: 2
        )

        XCTAssertEqual(result.state, .pausedForUser)
        XCTAssertEqual(result.actions, [.stopFrameDriver])
    }

    func testStreamGrowthIsIgnoredWhilePausedForUser() {
        let result = NativeTimelineScrollCore.reduce(
            state: .pausedForUser,
            intent: .streamContentGrew(),
            geometry: geometry(offsetY: 200, distanceToBottom: 500),
            now: 2
        )

        XCTAssertEqual(result.state, .pausedForUser)
        XCTAssertEqual(result.actions, [])
    }

    func testStreamGrowthAtBottomStartsFollowingDriverFromIdle() {
        let result = NativeTimelineScrollCore.reduce(
            state: .idle,
            intent: .streamContentGrew(),
            geometry: geometry(offsetY: 520, distanceToBottom: 0),
            now: 2
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 520, target: 520, lastFollowRequestAt: 2)
        )
        XCTAssertEqual(result.actions, [.startFrameDriver])
    }

    func testStreamGrowthNearBottomStartsFollowingDriverFromIdle() {
        let result = NativeTimelineScrollCore.reduce(
            state: .idle,
            intent: .streamContentGrew(),
            geometry: geometry(offsetY: 500, distanceToBottom: NativeTimelineScrollCore.resumeEpsilon - 1),
            now: 2
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 500, target: 520, lastFollowRequestAt: 2)
        )
        XCTAssertEqual(result.actions, [.startFrameDriver])
    }

    func testRepeatedStreamGrowthClampsTargetMonotonically() {
        let following = NativeTimelineScrollState.followingBottom(
            virtualOffset: 500,
            target: 700,
            lastFollowRequestAt: 1
        )

        let dipped = NativeTimelineScrollCore.reduce(
            state: following,
            intent: .streamContentGrew(),
            geometry: geometry(offsetY: 500, contentHeight: 1_000),
            now: 2
        )
        XCTAssertEqual(
            dipped.state,
            .followingBottom(virtualOffset: 500, target: 700, lastFollowRequestAt: 2)
        )

        let grown = NativeTimelineScrollCore.reduce(
            state: dipped.state,
            intent: .streamContentGrew(),
            geometry: geometry(offsetY: 500, contentHeight: 1_600),
            now: 3
        )
        XCTAssertEqual(
            grown.state,
            .followingBottom(virtualOffset: 500, target: 920, lastFollowRequestAt: 3)
        )
    }

    func testViewportChangeRebasesFollowingTargetAfterKeyboardDismissal() {
        let state = NativeTimelineScrollState.followingBottom(
            virtualOffset: 1_172,
            target: 1_172,
            lastFollowRequestAt: 1
        )

        let result = NativeTimelineScrollCore.reduce(
            state: state,
            intent: .viewportChanged,
            geometry: geometry(
                offsetY: 880,
                contentHeight: 1_600,
                viewportHeight: 800,
                adjustedInsetBottom: 80,
                distanceToBottom: 0
            ),
            now: 2
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 880, target: 880, lastFollowRequestAt: 2)
        )
        XCTAssertEqual(result.actions, [])
    }

    func testViewportChangeUsesFrameDriverInsteadOfRestartingBottomAnimation() {
        let result = NativeTimelineScrollCore.reduce(
            state: .keyboardFocus(NativeTimelineKeyboardFocusTransaction(token: 1, stableFrames: 0)),
            intent: .viewportChanged,
            geometry: geometry(
                offsetY: 880,
                contentHeight: 1_600,
                viewportHeight: 500,
                adjustedInsetBottom: 80,
                distanceToBottom: 300
            ),
            now: 2
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 880, target: 1_180, lastFollowRequestAt: 2)
        )
        XCTAssertEqual(result.actions, [.startFrameDriver])
    }

    func testViewportChangeDuringUserInteractionPausesInsteadOfPulling() {
        let state = NativeTimelineScrollState.followingBottom(
            virtualOffset: 880,
            target: 880,
            lastFollowRequestAt: 1
        )

        let result = NativeTimelineScrollCore.reduce(
            state: state,
            intent: .viewportChanged,
            geometry: geometry(
                offsetY: 700,
                contentHeight: 1_600,
                viewportHeight: 500,
                adjustedInsetBottom: 80,
                distanceToBottom: 480,
                userInteracting: true
            ),
            now: 2
        )

        XCTAssertEqual(result.state, .pausedForUser)
        XCTAssertEqual(result.actions, [.stopFrameDriver])
    }

    func testKeyboardFocusCompletesOnTokenedBottomLayoutFrame() {
        let result = NativeTimelineScrollCore.reduce(
            state: .keyboardFocus(NativeTimelineKeyboardFocusTransaction(token: 7, stableFrames: 0)),
            intent: .layoutSettled(token: 7),
            geometry: geometry(offsetY: 520, distanceToBottom: 0),
            now: 3
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 520, target: 520, lastFollowRequestAt: 3)
        )
        XCTAssertEqual(result.actions, [.markKeyboardFocusComplete])
    }

    func testKeyboardFocusNotAtBottomRequestsAnotherBottomAnchorPass() {
        let result = NativeTimelineScrollCore.reduce(
            state: .keyboardFocus(NativeTimelineKeyboardFocusTransaction(token: 7, stableFrames: 1)),
            intent: .layoutSettled(token: 7),
            geometry: geometry(offsetY: 300, distanceToBottom: 18),
            now: 4
        )

        XCTAssertEqual(
            result.state,
            .keyboardFocus(NativeTimelineKeyboardFocusTransaction(token: 7, stableFrames: 0))
        )
        XCTAssertEqual(result.actions, [])
    }

    func testKeyboardFocusIgnoresStaleLayoutSettledToken() {
        let state = NativeTimelineScrollState.keyboardFocus(
            NativeTimelineKeyboardFocusTransaction(token: 7, stableFrames: 1)
        )

        let result = NativeTimelineScrollCore.reduce(
            state: state,
            intent: .layoutSettled(token: 6),
            geometry: geometry(offsetY: 520, distanceToBottom: 0),
            now: 4
        )

        XCTAssertEqual(result.state, state)
        XCTAssertEqual(result.actions, [])
    }

    func testLayoutMetricsGrowthRefreshesFollowingBottomTarget() {
        let following = NativeTimelineScrollState.followingBottom(
            virtualOffset: 520,
            target: 520,
            lastFollowRequestAt: 1
        )

        let result = NativeTimelineScrollCore.reduce(
            state: following,
            intent: .layoutSettled(token: nil),
            geometry: geometry(offsetY: 520, contentHeight: 1_360, distanceToBottom: 40),
            now: 4
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 520, target: 680, lastFollowRequestAt: 4)
        )
        XCTAssertEqual(result.actions, [.startFrameDriver])
    }

    func testLayoutMetricsSettledDoesNotResumePausedHistoryPosition() {
        let result = NativeTimelineScrollCore.reduce(
            state: .pausedForUser,
            intent: .layoutSettled(token: nil),
            geometry: geometry(offsetY: 200, contentHeight: 1_360, distanceToBottom: 480),
            now: 4
        )

        XCTAssertEqual(result.state, .pausedForUser)
        XCTAssertEqual(result.actions, [])
    }

    func testTokenedLayoutSettledDoesNotRefreshFollowingBottom() {
        let following = NativeTimelineScrollState.followingBottom(
            virtualOffset: 520,
            target: 520,
            lastFollowRequestAt: 1
        )

        let result = NativeTimelineScrollCore.reduce(
            state: following,
            intent: .layoutSettled(token: 7),
            geometry: geometry(offsetY: 520, contentHeight: 1_360, distanceToBottom: 40),
            now: 4
        )

        XCTAssertEqual(result.state, following)
        XCTAssertEqual(result.actions, [])
    }

    func testTickForwardAdoptsExternalOffsetAndNeverWritesBackward() {
        let following = NativeTimelineScrollState.followingBottom(
            virtualOffset: 400,
            target: 800,
            lastFollowRequestAt: 1
        )

        let result = NativeTimelineScrollCore.tick(
            state: following,
            geometry: geometry(offsetY: 650, contentHeight: 1_480),
            now: 2,
            dt: 1.0 / 120.0
        )

        guard case let .followingBottom(virtualOffset, target, _, _) = result.state else {
            return XCTFail("expected followingBottom")
        }
        XCTAssertEqual(target, 800)
        XCTAssertGreaterThan(virtualOffset, 650)
        guard case let .writeOffsetY(offsetY) = result.actions.first else {
            return XCTFail("expected writeOffsetY")
        }
        XCTAssertGreaterThan(offsetY, 650)
    }

    func testSettledFollowingStopsFrameDriverButKeepsBottomOwnership() {
        let following = NativeTimelineScrollState.followingBottom(
            virtualOffset: 520,
            target: 520,
            lastFollowRequestAt: 1
        )

        let result = NativeTimelineScrollCore.tick(
            state: following,
            geometry: geometry(offsetY: 520, distanceToBottom: 0),
            now: 2,
            dt: 1.0 / 120.0
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 520, target: 520, lastFollowRequestAt: 2)
        )
        XCTAssertEqual(result.actions, [.writeOffsetY(520), .stopFrameDriver])
    }

    func testStreamGrowthRestartsFrameDriverAfterSettledFollowingStopped() {
        let following = NativeTimelineScrollState.followingBottom(
            virtualOffset: 520,
            target: 520,
            lastFollowRequestAt: 1
        )

        let result = NativeTimelineScrollCore.reduce(
            state: following,
            intent: .streamContentGrew(),
            geometry: geometry(offsetY: 520, contentHeight: 1_360, distanceToBottom: 40),
            now: 3
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 520, target: 680, lastFollowRequestAt: 3)
        )
        XCTAssertEqual(result.actions, [.startFrameDriver])
    }

    // MARK: - 终态排空滞后允许度（lagAllowance）

    /// 流式拍携带的 allowance 进入跟随状态，供 tick 连续收紧时间常数。
    func testStreamGrowthCarriesLagAllowanceIntoFollowingState() {
        let following = NativeTimelineScrollState.followingBottom(
            virtualOffset: 500,
            target: 700,
            lastFollowRequestAt: 1
        )

        let result = NativeTimelineScrollCore.reduce(
            state: following,
            intent: .streamContentGrew(lagAllowance: 0.25),
            geometry: geometry(offsetY: 500, contentHeight: 1_600),
            now: 2
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 500, target: 920, lastFollowRequestAt: 2, lagAllowance: 0.25)
        )
    }

    /// τ_eff = τ × allowance：同一帧的闭合比例按公式连续缩放，无离散跳变。
    func testLagAllowanceTightensTauContinuously() {
        let following = NativeTimelineScrollState.followingBottom(
            virtualOffset: 500,
            target: 800,
            lastFollowRequestAt: 1,
            lagAllowance: 0.25
        )
        let dt = 1.0 / 120.0

        let result = NativeTimelineScrollCore.tick(
            state: following,
            geometry: geometry(offsetY: 500, contentHeight: 1_480),
            now: 2,
            dt: dt
        )

        guard case let .writeOffsetY(offsetY) = result.actions.first else {
            return XCTFail("expected writeOffsetY")
        }
        let tauEff = NativeTimelineScrollCore.tau * 0.25
        let expected = 500 + 300 * (1 - exp(-dt / tauEff))
        XCTAssertEqual(offsetY, expected, accuracy: 0.5)
    }

    /// allowance 越界读数钳制：≤0 用下限（保持严格小于 1 的每帧闭合率），
    /// ≥1 与流式期完全一致。
    func testLagAllowanceClampedToMinimumAndOne() {
        let dt = 1.0 / 120.0
        func tickedVirtual(_ allowance: CGFloat) -> CGFloat {
            let result = NativeTimelineScrollCore.tick(
                state: .followingBottom(
                    virtualOffset: 500,
                    target: 800,
                    lastFollowRequestAt: 1,
                    lagAllowance: allowance
                ),
                geometry: geometry(offsetY: 500, contentHeight: 1_480),
                now: 2,
                dt: dt
            )
            guard case let .writeOffsetY(offsetY) = result.actions.first else {
                XCTFail("expected writeOffsetY")
                return 0
            }
            return offsetY
        }

        XCTAssertEqual(
            tickedVirtual(0),
            tickedVirtual(NativeTimelineScrollCore.minimumLagAllowance),
            accuracy: 0.001
        )
        XCTAssertEqual(tickedVirtual(5), tickedVirtual(1), accuracy: 0.001)
        // 下限 τ_eff 仍保持指数形态：单帧不瞬时贴底，残余交给终态钉底收口。
        XCTAssertLessThan(tickedVirtual(NativeTimelineScrollCore.minimumLagAllowance), 799)
    }

    /// 端到端时序契约：排空尾拍 allowance 收紧后，滞后在流式段内清零，
    /// generationTerminated 的钉底不再产生任何位移写入——完成零跳变。
    func testTightenedAllowanceConvergesBeforeTerminalPin() {
        let almostThere = NativeTimelineScrollState.followingBottom(
            virtualOffset: 500,
            target: 520,
            lastFollowRequestAt: 1,
            lagAllowance: NativeTimelineScrollCore.minimumLagAllowance
        )

        let ticked = NativeTimelineScrollCore.tick(
            state: almostThere,
            geometry: geometry(offsetY: 500, contentHeight: 1_200),
            now: 2,
            dt: 1.0 / 60.0
        )
        guard case let .followingBottom(virtualOffset, _, _, _) = ticked.state else {
            return XCTFail("expected followingBottom")
        }
        XCTAssertLessThan(520 - virtualOffset, NativeTimelineScrollCore.arrivalEpsilon)

        let terminal = NativeTimelineScrollCore.reduce(
            state: ticked.state,
            intent: .generationTerminated,
            geometry: geometry(offsetY: virtualOffset, contentHeight: 1_200, distanceToBottom: 0),
            now: 3
        )
        let settledTick = NativeTimelineScrollCore.tick(
            state: terminal.state,
            geometry: geometry(offsetY: virtualOffset, contentHeight: 1_200, distanceToBottom: 0),
            now: 3 + 1.0 / 120.0,
            dt: 1.0 / 120.0
        )
        let writeActions = settledTick.actions.filter {
            if case .writeOffsetY = $0 { return true }
            return false
        }
        XCTAssertTrue(
            writeActions.isEmpty,
            "滞后已在排空期清零，终态收锚首拍不得再写入位移：\(settledTick.actions)"
        )
    }

    /// 终态收锚的方向语义：晚到的「增长」（最后一拍文本布局延迟落地）以基础
    /// τ 缓动追入（书写的延续，非跳变）；「收缩」（推理卡收起）保持瞬时钉底
    /// （既有契约，防"再滑一段"复发）。
    func testTerminalSettleEasesLateGrowthButSnapsShrink() {
        let settling = NativeTimelineScrollState.settlingAfterTerminal(
            virtualOffset: 520,
            target: 520,
            lastLayoutAt: 2
        )
        let dt = 1.0 / 120.0

        // 晚到增长：底部 520 → 570（contentHeight 1_250）。
        let grown = NativeTimelineScrollCore.tick(
            state: settling,
            geometry: geometry(offsetY: 520, contentHeight: 1_250, distanceToBottom: 50),
            now: 2 + dt,
            dt: dt
        )
        guard case let .writeOffsetY(grownOffset) = grown.actions.first else {
            return XCTFail("expected writeOffsetY for late growth")
        }
        let alpha = 1 - exp(-dt / NativeTimelineScrollCore.tau)
        XCTAssertEqual(
            grownOffset,
            520 + 50 * alpha,
            accuracy: 0.5,
            "晚到增长必须按基础 τ 的指数闭合追入，不得一帧钉死"
        )

        // 收缩：底部 520 → 320（contentHeight 1_000），瞬时钉底。
        let shrunk = NativeTimelineScrollCore.tick(
            state: settling,
            geometry: geometry(offsetY: 520, contentHeight: 1_000, distanceToBottom: 200),
            now: 2 + dt,
            dt: dt
        )
        XCTAssertEqual(
            shrunk.actions,
            [.writeOffsetY(320)],
            "收缩方向保持瞬时钉底（推理卡收起 ramp 已是连续高度源）"
        )
    }

    // MARK: - 轻点误伤恢复 & 磁吸回底

    @MainActor
    func testAccidentalTapRestoresFollowOnlyForTrueTapsDuringGeneration() {
        XCTAssertTrue(NativeChatTimelineView.shouldRestoreFollowAfterAccidentalTap(
            wasFollowingAtTouchDown: true,
            dragPhaseOccurred: false,
            generationActive: true
        ))
        XCTAssertFalse(NativeChatTimelineView.shouldRestoreFollowAfterAccidentalTap(
            wasFollowingAtTouchDown: true,
            dragPhaseOccurred: true,
            generationActive: true
        ), "真实拖拽（出现过 dragging 相位）交给静止判定，不在此恢复"
        )
        XCTAssertFalse(NativeChatTimelineView.shouldRestoreFollowAfterAccidentalTap(
            wasFollowingAtTouchDown: false,
            dragPhaseOccurred: false,
            generationActive: true
        ), "按下前本就在读历史（非跟随），轻点不得改变语义"
        )
        XCTAssertFalse(NativeChatTimelineView.shouldRestoreFollowAfterAccidentalTap(
            wasFollowingAtTouchDown: true,
            dragPhaseOccurred: false,
            generationActive: false
        ), "生成已结束无跟随可恢复"
        )
    }

    func testMagneticSnapPredictsLandingPointContinuously() {
        XCTAssertTrue(NativeTimelineMagneticBottomPolicy.shouldSnap(
            releaseVelocityY: -800,
            distanceToBottom: 200
        ), "预测行程 200pt 恰好落底，命中容差"
        )
        XCTAssertFalse(NativeTimelineMagneticBottomPolicy.shouldSnap(
            releaseVelocityY: -200,
            distanceToBottom: 200
        ), "弱甩预测落点距底 150pt，等静止判定"
        )
        XCTAssertFalse(NativeTimelineMagneticBottomPolicy.shouldSnap(
            releaseVelocityY: 300,
            distanceToBottom: 10
        ), "向下甩（远离底部）不吸"
        )
        XCTAssertTrue(NativeTimelineMagneticBottomPolicy.shouldSnap(
            releaseVelocityY: -400,
            distanceToBottom: 30
        ), "近底轻甩预测过冲，直接接管"
        )
    }
}
