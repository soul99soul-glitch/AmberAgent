import XCTest
@testable import iosApp

final class ChatScrollArbiterCoreTests: XCTestCase {

    // MARK: - Helpers

    private func geometry(
        currentOffset: CGFloat = 0,
        bottomTarget: CGFloat = 0,
        userInteracting: Bool = false
    ) -> ChatScrollGeometrySample {
        ChatScrollGeometrySample(
            currentOffset: currentOffset,
            bottomTarget: bottomTarget,
            userInteracting: userInteracting
        )
    }

    // MARK: 1. userTakeover stops the motor from following

    func testUserTakeoverFromFollowingStopsDisplayLinkAndEntersUserControlled() {
        let following = ChatScrollArbiterState.following(virtualOffset: 100, target: 500, lastFollowRequestAt: 10)
        let result = ChatScrollArbiterCore.reduce(
            state: following,
            intent: .userTakeover,
            geometry: geometry(),
            now: 10
        )
        XCTAssertEqual(result.state, .userControlled)
        XCTAssertEqual(result.actions, [.stopDisplayLink])
    }

    // MARK: 2. userControlled ignores all programmatic intents

    func testUserControlledIgnoresFollowTail() {
        let result = ChatScrollArbiterCore.reduce(
            state: .userControlled,
            intent: .followTail,
            geometry: geometry(currentOffset: 10, bottomTarget: 200),
            now: 5
        )
        XCTAssertEqual(result.state, .userControlled)
        XCTAssertEqual(result.actions, [])
    }

    func testUserControlledIgnoresAnchorToBottom() {
        let result = ChatScrollArbiterCore.reduce(
            state: .userControlled,
            intent: .anchorToBottom,
            geometry: geometry(),
            now: 5
        )
        XCTAssertEqual(result.state, .userControlled)
        XCTAssertEqual(result.actions, [])
    }

    func testUserControlledIgnoresSnapToBottom() {
        let result = ChatScrollArbiterCore.reduce(
            state: .userControlled,
            intent: .snapToBottom(animated: true),
            geometry: geometry(),
            now: 5
        )
        XCTAssertEqual(result.state, .userControlled)
        XCTAssertEqual(result.actions, [])
    }

    // MARK: 3. userReleased only effective in userControlled

    func testUserReleasedFromUserControlledReturnsToIdle() {
        let result = ChatScrollArbiterCore.reduce(
            state: .userControlled,
            intent: .userReleased,
            geometry: geometry(),
            now: 5
        )
        XCTAssertEqual(result.state, .idle)
        XCTAssertEqual(result.actions, [])
    }

    func testUserReleasedFromOtherStatesIsIgnored() {
        let result = ChatScrollArbiterCore.reduce(
            state: .idle,
            intent: .userReleased,
            geometry: geometry(),
            now: 5
        )
        XCTAssertEqual(result.state, .idle)
        XCTAssertEqual(result.actions, [])
    }

    func testFollowTailCanReenterFollowingAfterRelease() {
        let released = ChatScrollArbiterCore.reduce(
            state: .userControlled,
            intent: .userReleased,
            geometry: geometry(),
            now: 5
        )
        let followed = ChatScrollArbiterCore.reduce(
            state: released.state,
            intent: .followTail,
            geometry: geometry(currentOffset: 20, bottomTarget: 300),
            now: 6
        )
        XCTAssertEqual(followed.state, .following(virtualOffset: 20, target: 300, lastFollowRequestAt: 6))
        XCTAssertEqual(followed.actions, [.startDisplayLink])
    }

    // MARK: 4. Monotonic target clamp during tick

    func testTickClampsTargetMonotonicallyAgainstEstimationDip() {
        let following = ChatScrollArbiterState.following(virtualOffset: 900, target: 1000, lastFollowRequestAt: 0)

        // Estimation dips to 400 — must be ignored, target stays 1000.
        let dipped = ChatScrollArbiterCore.tick(
            state: following,
            geometry: geometry(currentOffset: 900, bottomTarget: 400, userInteracting: false),
            now: 0.01,
            dt: 1.0 / 120.0
        )
        guard case .following(let virtual1, let target1, _) = dipped.state else {
            return XCTFail("expected following state")
        }
        XCTAssertEqual(target1, 1000)
        XCTAssertGreaterThan(virtual1, 900) // moving toward 1000, not 400
        if case .writeOffset(let written) = dipped.actions.first {
            XCTAssertGreaterThan(written, 900)
        } else {
            XCTFail("expected writeOffset action")
        }

        // Real growth to 1200 — target must update.
        let grown = ChatScrollArbiterCore.tick(
            state: dipped.state,
            geometry: geometry(currentOffset: virtual1, bottomTarget: 1200, userInteracting: false),
            now: 0.02,
            dt: 1.0 / 120.0
        )
        guard case .following(_, let target2, _) = grown.state else {
            return XCTFail("expected following state")
        }
        XCTAssertEqual(target2, 1200)
    }

    // MARK: 5. Virtual offset independence from real offset jumps

    func testTickWriteOffsetDerivesFromVirtualNotFromCompensatedRealOffset() {
        let following = ChatScrollArbiterState.following(virtualOffset: 100, target: 500, lastFollowRequestAt: 0)
        let dt: TimeInterval = 1.0 / 120.0

        // Simulate a batch-compensated teleport on the real offset far from virtual.
        let jumped = ChatScrollArbiterCore.tick(
            state: following,
            geometry: geometry(currentOffset: 9000, bottomTarget: 500, userInteracting: false),
            now: dt,
            dt: dt
        )

        let alpha = 1 - exp(-dt / ChatScrollArbiterCore.tau)
        let expectedVirtual = 100 + (500 - 100) * alpha

        guard case .writeOffset(let written) = jumped.actions.first else {
            return XCTFail("expected writeOffset action")
        }
        XCTAssertEqual(written, expectedVirtual, accuracy: 0.001)
        // Explicitly not derived from the teleported currentOffset (9000).
        XCTAssertLessThan(written, 200)
    }

    // MARK: 6. Arrival + idle timeout vs. still-fresh follow request

    func testTickAtArrivalWithStaleFollowRequestGoesIdle() {
        let following = ChatScrollArbiterState.following(virtualOffset: 499.8, target: 500, lastFollowRequestAt: 0)
        let result = ChatScrollArbiterCore.tick(
            state: following,
            geometry: geometry(currentOffset: 499.8, bottomTarget: 500, userInteracting: false),
            now: ChatScrollArbiterCore.idleStopInterval + 0.01,
            dt: 1.0 / 120.0
        )
        XCTAssertEqual(result.state, .idle)
        // Stop-the-motor is preceded by one precise writeOffset to eliminate
        // the arrivalEpsilon residual before the display link is torn down.
        XCTAssertEqual(result.actions, [.writeOffset(500), .stopDisplayLink])
    }

    func testTickAtArrivalWithFreshFollowRequestStaysFollowing() {
        let following = ChatScrollArbiterState.following(virtualOffset: 499.8, target: 500, lastFollowRequestAt: 0)
        let result = ChatScrollArbiterCore.tick(
            state: following,
            geometry: geometry(currentOffset: 499.8, bottomTarget: 500, userInteracting: false),
            now: 0.05,
            dt: 1.0 / 120.0
        )
        guard case .following(let virtual, let target, _) = result.state else {
            return XCTFail("expected following state")
        }
        XCTAssertEqual(virtual, 500)
        XCTAssertEqual(target, 500)
        XCTAssertEqual(result.actions, [.writeOffset(500)])
    }

    // MARK: 7. followTail bootstrap semantics

    func testFollowTailBootstrapSeedsVirtualFromCurrentRealOffset() {
        let result = ChatScrollArbiterCore.reduce(
            state: .idle,
            intent: .followTail,
            geometry: geometry(currentOffset: 42, bottomTarget: 900),
            now: 1
        )
        XCTAssertEqual(result.state, .following(virtualOffset: 42, target: 900, lastFollowRequestAt: 1))
        XCTAssertEqual(result.actions, [.startDisplayLink])
    }

    func testFollowTailBootstrapTargetClampsToCurrentOffsetWhenBottomTargetIsSmaller() {
        let result = ChatScrollArbiterCore.reduce(
            state: .idle,
            intent: .followTail,
            geometry: geometry(currentOffset: 500, bottomTarget: 100),
            now: 1
        )
        XCTAssertEqual(result.state, .following(virtualOffset: 500, target: 500, lastFollowRequestAt: 1))
        XCTAssertEqual(result.actions, [.startDisplayLink])
    }

    // MARK: 8. Repeated followTail while already following

    func testRepeatedFollowTailWhileFollowingUpdatesTargetMonotonicallyAndRefreshesTimestampWithoutRestartingDisplayLink() {
        let following = ChatScrollArbiterState.following(virtualOffset: 50, target: 600, lastFollowRequestAt: 1)

        // Smaller bottomTarget must not decrease target.
        let first = ChatScrollArbiterCore.reduce(
            state: following,
            intent: .followTail,
            geometry: geometry(currentOffset: 50, bottomTarget: 300),
            now: 2
        )
        XCTAssertEqual(first.state, .following(virtualOffset: 50, target: 600, lastFollowRequestAt: 2))
        XCTAssertEqual(first.actions, [])

        // Larger bottomTarget must bump target up.
        let second = ChatScrollArbiterCore.reduce(
            state: first.state,
            intent: .followTail,
            geometry: geometry(currentOffset: 50, bottomTarget: 800),
            now: 3
        )
        XCTAssertEqual(second.state, .following(virtualOffset: 50, target: 800, lastFollowRequestAt: 3))
        XCTAssertEqual(second.actions, [])
    }

    // MARK: 9. conversationReset

    func testConversationResetFromFollowingReturnsToIdleAndStopsDisplayLink() {
        let following = ChatScrollArbiterState.following(virtualOffset: 50, target: 600, lastFollowRequestAt: 1)
        let result = ChatScrollArbiterCore.reduce(
            state: following,
            intent: .conversationReset,
            geometry: geometry(),
            now: 2
        )
        XCTAssertEqual(result.state, .idle)
        XCTAssertEqual(result.actions, [.stopDisplayLink])
    }

    func testConversationResetFromIdleIsNoop() {
        let result = ChatScrollArbiterCore.reduce(
            state: .idle,
            intent: .conversationReset,
            geometry: geometry(),
            now: 2
        )
        XCTAssertEqual(result.state, .idle)
        XCTAssertEqual(result.actions, [])
    }

    // MARK: 10. tick is a no-op outside following

    func testTickIsNoopInIdle() {
        let result = ChatScrollArbiterCore.tick(state: .idle, geometry: geometry(), now: 1, dt: 1.0 / 120.0)
        XCTAssertEqual(result.state, .idle)
        XCTAssertEqual(result.actions, [.none])
    }

    func testTickIsNoopInUserControlled() {
        let result = ChatScrollArbiterCore.tick(state: .userControlled, geometry: geometry(), now: 1, dt: 1.0 / 120.0)
        XCTAssertEqual(result.state, .userControlled)
        XCTAssertEqual(result.actions, [.none])
    }

    func testTickIsNoopInAnchoring() {
        let result = ChatScrollArbiterCore.tick(state: .anchoring, geometry: geometry(), now: 1, dt: 1.0 / 120.0)
        XCTAssertEqual(result.state, .anchoring)
        XCTAssertEqual(result.actions, [.none])
    }

    func testTickIsNoopInSnapping() {
        let result = ChatScrollArbiterCore.tick(state: .snapping, geometry: geometry(), now: 1, dt: 1.0 / 120.0)
        XCTAssertEqual(result.state, .snapping)
        XCTAssertEqual(result.actions, [.none])
    }

    // MARK: 11. tick user interaction takeover

    func testTickWithUserInteractingTakesOverAndStopsDisplayLink() {
        let following = ChatScrollArbiterState.following(virtualOffset: 50, target: 600, lastFollowRequestAt: 1)
        let result = ChatScrollArbiterCore.tick(
            state: following,
            geometry: geometry(currentOffset: 50, bottomTarget: 600, userInteracting: true),
            now: 1.01,
            dt: 1.0 / 120.0
        )
        XCTAssertEqual(result.state, .userControlled)
        XCTAssertEqual(result.actions, [.stopDisplayLink])
    }

    // MARK: 12. anchorToBottom / snapToBottom from following stop the motor first

    func testAnchorToBottomFromFollowingStopsDisplayLinkThenAnchors() {
        let following = ChatScrollArbiterState.following(virtualOffset: 50, target: 600, lastFollowRequestAt: 1)
        let result = ChatScrollArbiterCore.reduce(
            state: following,
            intent: .anchorToBottom,
            geometry: geometry(),
            now: 2
        )
        XCTAssertEqual(result.state, .anchoring)
        XCTAssertEqual(result.actions, [.stopDisplayLink, .performAnchor])
    }

    func testSnapToBottomFromFollowingStopsDisplayLinkThenSnaps() {
        let following = ChatScrollArbiterState.following(virtualOffset: 50, target: 600, lastFollowRequestAt: 1)
        let result = ChatScrollArbiterCore.reduce(
            state: following,
            intent: .snapToBottom(animated: false),
            geometry: geometry(),
            now: 2
        )
        XCTAssertEqual(result.state, .snapping)
        XCTAssertEqual(result.actions, [.stopDisplayLink, .performSnap(animated: false)])
    }

    // MARK: 13. Precise interpolation numerics

    func testTickInterpolationNumericPrecision() {
        let dt: TimeInterval = 1.0 / 120.0
        let alpha = 1 - exp(-dt / ChatScrollArbiterCore.tau)
        XCTAssertEqual(alpha, 0.0989, accuracy: 0.0005)

        let virtualOffset: CGFloat = 200
        let target: CGFloat = 1000
        let following = ChatScrollArbiterState.following(virtualOffset: virtualOffset, target: target, lastFollowRequestAt: 0)

        let result = ChatScrollArbiterCore.tick(
            state: following,
            geometry: geometry(currentOffset: virtualOffset, bottomTarget: target, userInteracting: false),
            now: dt,
            dt: dt
        )

        let expected = virtualOffset + (target - virtualOffset) * CGFloat(alpha)
        guard case .writeOffset(let written) = result.actions.first else {
            return XCTFail("expected writeOffset action")
        }
        XCTAssertEqual(written, expected, accuracy: 0.001)
    }

    // MARK: 14. Negative dt does not overshoot or reverse

    func testTickWithNegativeDtDoesNotOvershootOrReverse() {
        let following = ChatScrollArbiterState.following(virtualOffset: 100, target: 500, lastFollowRequestAt: 0)
        let result = ChatScrollArbiterCore.tick(
            state: following,
            geometry: geometry(currentOffset: 100, bottomTarget: 500, userInteracting: false),
            now: 0.01,
            dt: -0.5
        )
        // safeDt clamps negative dt to 0 → alpha == 0 → virtual stays exactly
        // at its previous value: never jumps past target, never reverses
        // below its starting point.
        guard case .writeOffset(let written) = result.actions.first else {
            return XCTFail("expected writeOffset action")
        }
        XCTAssertEqual(written, 100)
        guard case .following(let virtual, let target, _) = result.state else {
            return XCTFail("expected following state")
        }
        XCTAssertEqual(virtual, 100)
        XCTAssertEqual(target, 500)
    }

    // MARK: 15. Negative geometry (content shorter than one screen)

    func testNegativeGeometryFollowTailBootstrapAndTickArithmetic() {
        // bottomTarget can be negative when content is shorter than the
        // viewport (contentSize - viewportHeight < 0).
        let bootstrap = ChatScrollArbiterCore.reduce(
            state: .idle,
            intent: .followTail,
            geometry: geometry(currentOffset: -10, bottomTarget: -40),
            now: 1
        )
        XCTAssertEqual(bootstrap.state, .following(virtualOffset: -10, target: -10, lastFollowRequestAt: 1))
        XCTAssertEqual(bootstrap.actions, [.startDisplayLink])

        // Tick arithmetic must stay correct with an all-negative virtual/target pair.
        let following = ChatScrollArbiterState.following(virtualOffset: -40, target: -10, lastFollowRequestAt: 1)
        let dt: TimeInterval = 1.0 / 120.0
        let result = ChatScrollArbiterCore.tick(
            state: following,
            geometry: geometry(currentOffset: -40, bottomTarget: -40, userInteracting: false),
            now: 1.01,
            dt: dt
        )
        let alpha = 1 - exp(-dt / ChatScrollArbiterCore.tau)
        let expectedVirtual = -40 + (-10 - (-40)) * CGFloat(alpha)
        guard case .writeOffset(let written) = result.actions.first else {
            return XCTFail("expected writeOffset action")
        }
        XCTAssertEqual(written, expectedVirtual, accuracy: 0.001)
        XCTAssertGreaterThan(written, -40) // moving toward -10, i.e. upward
    }

    // MARK: 16. remaining == arrivalEpsilon strict boundary

    func testRemainingExactlyAtArrivalEpsilonIsNotYetArrived() {
        let target: CGFloat = 500
        let virtualOffset = target - ChatScrollArbiterCore.arrivalEpsilon // remaining == epsilon exactly
        let following = ChatScrollArbiterState.following(virtualOffset: virtualOffset, target: target, lastFollowRequestAt: 0)
        let dt: TimeInterval = 1.0 / 120.0
        let result = ChatScrollArbiterCore.tick(
            state: following,
            geometry: geometry(currentOffset: virtualOffset, bottomTarget: target, userInteracting: false),
            now: 0.01,
            dt: dt
        )
        // Current semantics: `abs(remaining) < arrivalEpsilon` is a strict
        // inequality, so remaining == arrivalEpsilon still takes the
        // interpolation branch, not the arrival/settle branch. This test
        // locks that in; changing the comparison to `<=` would be a
        // deliberate, separately-reviewed decision.
        guard case .following(let newVirtual, let newTarget, _) = result.state else {
            return XCTFail("expected following state (not yet arrived)")
        }
        XCTAssertEqual(newTarget, target)
        XCTAssertNotEqual(newVirtual, target) // still interpolating, hasn't snapped
        guard case .writeOffset(let written) = result.actions.first else {
            return XCTFail("expected writeOffset action")
        }
        XCTAssertNotEqual(written, target)
    }

    // MARK: 17. userTakeover / conversationReset from anchoring and snapping (four combinations)

    func testUserTakeoverAndConversationResetFromAnchoringAndSnapping() {
        // anchoring + userTakeover: effectiveState is idle (not following), so no stopDisplayLink.
        let anchoringTakeover = ChatScrollArbiterCore.reduce(state: .anchoring, intent: .userTakeover, geometry: geometry(), now: 1)
        XCTAssertEqual(anchoringTakeover.state, .userControlled)
        XCTAssertEqual(anchoringTakeover.actions, [])

        // anchoring + conversationReset: same idle-equivalent treatment.
        let anchoringReset = ChatScrollArbiterCore.reduce(state: .anchoring, intent: .conversationReset, geometry: geometry(), now: 1)
        XCTAssertEqual(anchoringReset.state, .idle)
        XCTAssertEqual(anchoringReset.actions, [])

        // snapping + userTakeover.
        let snappingTakeover = ChatScrollArbiterCore.reduce(state: .snapping, intent: .userTakeover, geometry: geometry(), now: 1)
        XCTAssertEqual(snappingTakeover.state, .userControlled)
        XCTAssertEqual(snappingTakeover.actions, [])

        // snapping + conversationReset.
        let snappingReset = ChatScrollArbiterCore.reduce(state: .snapping, intent: .conversationReset, geometry: geometry(), now: 1)
        XCTAssertEqual(snappingReset.state, .idle)
        XCTAssertEqual(snappingReset.actions, [])
    }

    // MARK: 18. NaN bottomTarget / currentOffset protection

    func testNaNBottomTargetInFollowingTickLeavesStateAndWritesUnchanged() {
        let following = ChatScrollArbiterState.following(virtualOffset: 100, target: 500, lastFollowRequestAt: 0)
        let result = ChatScrollArbiterCore.tick(
            state: following,
            geometry: geometry(currentOffset: 100, bottomTarget: CGFloat.nan, userInteracting: false),
            now: 0.01,
            dt: 1.0 / 120.0
        )
        XCTAssertEqual(result.state, following)
        XCTAssertEqual(result.actions, [.none])
    }

    func testFollowTailBootstrapRejectsNaNCurrentOffset() {
        let result = ChatScrollArbiterCore.reduce(
            state: .idle,
            intent: .followTail,
            geometry: geometry(currentOffset: CGFloat.nan, bottomTarget: 900),
            now: 1
        )
        XCTAssertEqual(result.state, .idle)
        XCTAssertEqual(result.actions, [])
    }
}
