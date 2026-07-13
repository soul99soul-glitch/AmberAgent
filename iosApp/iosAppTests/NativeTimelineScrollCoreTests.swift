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

    private func geometry(
        offsetY: CGFloat = 0,
        contentHeight: CGFloat = 1_200,
        viewportHeight: CGFloat = 800,
        adjustedInsetTop: CGFloat = 0,
        adjustedInsetBottom: CGFloat = 120,
        keyboardOverlap: CGFloat = 0,
        composerHeight: CGFloat = 72,
        distanceToBottom: CGFloat = 0,
        userInteracting: Bool = false
    ) -> NativeTimelineScrollGeometry {
        NativeTimelineScrollGeometry(
            offsetY: offsetY,
            contentHeight: contentHeight,
            viewportHeight: viewportHeight,
            adjustedInsetTop: adjustedInsetTop,
            adjustedInsetBottom: adjustedInsetBottom,
            keyboardOverlap: keyboardOverlap,
            composerHeight: composerHeight,
            distanceToBottom: distanceToBottom,
            userInteracting: userInteracting
        )
    }

    func testNativeScrollDriverFlagDefaultsOff() {
        UserDefaults.standard.removeObject(forKey: NativeTimelineScrollFeatureFlags.key)

        XCTAssertFalse(NativeTimelineScrollFeatureFlags.isEnabled)
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

    func testBottomTargetAccountsForKeyboardAndComposerObstruction() {
        let geo = geometry(
            contentHeight: 1_600,
            viewportHeight: 800,
            adjustedInsetBottom: 80,
            keyboardOverlap: 300,
            composerHeight: 72
        )

        XCTAssertEqual(geo.effectiveBottomInset, 372)
        XCTAssertEqual(geo.bottomTarget, 1_172)
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

    func testKeyboardWillChangeDuringFocusUpdatesObstructionAndKeepsBottomAnchored() {
        let state = NativeTimelineScrollState.keyboardFocus(
            NativeTimelineKeyboardFocusTransaction(token: 1, stableFrames: 1)
        )

        let result = NativeTimelineScrollCore.reduce(
            state: state,
            intent: .keyboardWillChange(token: 2, overlap: 336, composerHeight: 72),
            geometry: geometry(offsetY: 200, distanceToBottom: 200),
            now: 2
        )

        XCTAssertEqual(
            result.state,
            .keyboardFocus(NativeTimelineKeyboardFocusTransaction(token: 2, stableFrames: 0))
        )
        XCTAssertEqual(
            result.actions,
            [
                .updateObstruction(keyboardOverlap: 336, composerHeight: 72),
                .requestBottomAnchor(animated: true, source: .keyboardChange)
            ]
        )
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
