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

    func testNativeScrollDriverFlagDefaultsOff() {
        UserDefaults.standard.removeObject(forKey: NativeTimelineScrollFeatureFlags.key)

        XCTAssertFalse(NativeTimelineScrollFeatureFlags.isEnabled)
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
        driver.submit(.streamContentGrew)
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
            intent: .streamContentGrew,
            geometry: geometry(offsetY: 200, distanceToBottom: 500),
            now: 2
        )

        XCTAssertEqual(result.state, .pausedForUser)
        XCTAssertEqual(result.actions, [])
    }

    func testStreamGrowthAtBottomStartsFollowingDriverFromIdle() {
        let result = NativeTimelineScrollCore.reduce(
            state: .idle,
            intent: .streamContentGrew,
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
            intent: .streamContentGrew,
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
            intent: .streamContentGrew,
            geometry: geometry(offsetY: 500, contentHeight: 1_000),
            now: 2
        )
        XCTAssertEqual(
            dipped.state,
            .followingBottom(virtualOffset: 500, target: 700, lastFollowRequestAt: 2)
        )

        let grown = NativeTimelineScrollCore.reduce(
            state: dipped.state,
            intent: .streamContentGrew,
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

        guard case let .followingBottom(virtualOffset, target, _) = result.state else {
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
            intent: .streamContentGrew,
            geometry: geometry(offsetY: 520, contentHeight: 1_360, distanceToBottom: 40),
            now: 3
        )

        XCTAssertEqual(
            result.state,
            .followingBottom(virtualOffset: 520, target: 680, lastFollowRequestAt: 3)
        )
        XCTAssertEqual(result.actions, [.startFrameDriver])
    }
}
