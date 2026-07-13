import UIKit
import ChatLayout

enum ChatScrollArbiterFeatureFlags {
    static let userDefaultsKey = "chat.scroll.arbiter.enabled"

    static var isProgrammaticScrollArbiterEnabled: Bool {
        guard UserDefaults.standard.object(forKey: userDefaultsKey) != nil else {
            // Default OFF: field testing (2026-07-03) surfaced follow misalignment, wild
            // re-entry jumps, and stuck-content symptoms with the arbiter wired in. Legacy
            // path remains the daily driver until the P3.2 integration passes a structural
            // (tool cards + reasoning collapse + user interaction) replay campaign.
            return false
        }
        return UserDefaults.standard.bool(forKey: userDefaultsKey)
    }
}

@MainActor
final class ChatScrollArbiter: NSObject {
    typealias BottomIndexPathProvider = () -> IndexPath?

    private enum ExpectedAsyncState {
        case anchoring
        case snapping
    }

    private weak var collectionView: UICollectionView?
    private let bottomIndexPathProvider: BottomIndexPathProvider

    private var state: ChatScrollArbiterState = .idle
    private var generation: UInt64 = 0
    private var displayLink: CADisplayLink?
    private var lastDisplayLinkTimestamp: CFTimeInterval?

    init(
        collectionView: UICollectionView,
        bottomIndexPathProvider: @escaping BottomIndexPathProvider
    ) {
        self.collectionView = collectionView
        self.bottomIndexPathProvider = bottomIndexPathProvider
        super.init()
    }

    deinit {
        guard Thread.isMainThread else { return }
        MainActor.assumeIsolated {
            displayLink?.invalidate()
        }
    }

    func anchorToBottom() {
        submit(.anchorToBottom)
    }

    func followTail() {
        submit(.followTail)
        guard case .following = state,
              let collectionView,
              !(collectionView.isTracking || collectionView.isDragging || collectionView.isDecelerating) else { return }
        writeContentOffsetY(bottomTarget(in: collectionView))
    }

    func snapToBottom(animated: Bool) {
        submit(.snapToBottom(animated: animated))
    }

    func userTakeover() {
        cancelProgrammaticAnimations()
        submit(.userTakeover)
    }

    func userReleased() {
        submit(.userReleased)
    }

    func conversationReset() {
        cancelProgrammaticAnimations()
        submit(.conversationReset)
    }

    func invalidate() {
        generation &+= 1
        stopDisplayLink()
        cancelProgrammaticAnimations()
        state = .idle
    }

    private func submit(_ intent: ChatScrollIntent) {
        if shouldCancelRunningAnimation(before: intent) {
            cancelProgrammaticAnimations()
        }
        generation &+= 1
        let token = generation
        let result = ChatScrollArbiterCore.reduce(
            state: state,
            intent: intent,
            geometry: sampleGeometry(),
            now: CACurrentMediaTime()
        )
        state = result.state
        perform(result.actions, token: token)
    }

    private func shouldCancelRunningAnimation(before intent: ChatScrollIntent) -> Bool {
        guard case .snapping = state else { return false }
        if case .snapToBottom = intent {
            return false
        }
        return true
    }

    private func perform(_ actions: [ChatScrollArbiterAction], token: UInt64) {
        for action in actions {
            switch action {
            case .none:
                break
            case .startDisplayLink:
                startDisplayLink()
            case .stopDisplayLink:
                stopDisplayLink()
            case let .writeOffset(offset):
                writeContentOffsetY(offset)
            case .performAnchor:
                performAnchor(token: token)
            case let .performSnap(animated):
                performSnap(animated: animated, token: token)
            }
        }
    }

    private func startDisplayLink() {
        guard displayLink == nil else { return }
        lastDisplayLinkTimestamp = nil
        let link = CADisplayLink(target: self, selector: #selector(displayLinkTick(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
        lastDisplayLinkTimestamp = nil
    }

    @objc private func displayLinkTick(_ link: CADisplayLink) {
        let now = link.timestamp
        let dt = lastDisplayLinkTimestamp.map { now - $0 } ?? link.duration
        lastDisplayLinkTimestamp = now

        let result = ChatScrollArbiterCore.tick(
            state: state,
            geometry: sampleGeometry(),
            now: now,
            dt: dt
        )
        state = result.state
        perform(result.actions, token: generation)
    }

    private func performAnchor(token: UInt64) {
        guard isAsyncActionValid(token: token, expected: .anchoring) else { return }
        scrollToBottomOnce(animated: false, token: token, expected: .anchoring)
        guard collectionView?.bounds.height ?? 0 > 0 else { return }
        scheduleAnchorConvergence(token: token, remainingPasses: 2)
    }

    private func scheduleAnchorConvergence(token: UInt64, remainingPasses: Int) {
        Task { @MainActor [weak self] in
            await Task.yield()
            guard let self,
                  remainingPasses > 0,
                  self.isAsyncActionValid(token: token, expected: .anchoring),
                  let collectionView = self.collectionView else { return }

            collectionView.layoutIfNeeded()
            if self.distanceToBottom(in: collectionView) <= 1 { return }

            self.scrollToBottomOnce(animated: false, token: token, expected: .anchoring)
            self.scheduleAnchorConvergence(token: token, remainingPasses: remainingPasses - 1)
        }
    }

    private func performSnap(animated: Bool, token: UInt64) {
        guard isAsyncActionValid(token: token, expected: .snapping) else { return }
        scrollToBottomOnce(animated: animated, token: token, expected: .snapping)
        guard collectionView?.bounds.height ?? 0 > 0 else { return }
        scheduleSnapConvergence(token: token, remainingPasses: 2)
    }

    private func scheduleSnapConvergence(token: UInt64, remainingPasses: Int) {
        Task { @MainActor [weak self] in
            await Task.yield()
            guard let self,
                  remainingPasses > 0,
                  self.isAsyncActionValid(token: token, expected: .snapping),
                  let collectionView = self.collectionView else { return }

            collectionView.layoutIfNeeded()
            if self.distanceToBottom(in: collectionView) <= 1 { return }

            self.scrollToBottomOnce(animated: false, token: token, expected: .snapping)
            self.scheduleSnapConvergence(token: token, remainingPasses: remainingPasses - 1)
        }
    }

    private func scrollToBottomOnce(animated: Bool, token: UInt64, expected: ExpectedAsyncState) {
        guard isAsyncActionValid(token: token, expected: expected) else { return }

        guard animated else {
            UIView.performWithoutAnimation { [weak self] in
                self?.writeBottomAnchorIfValid(token: token, expected: expected)
            }
            return
        }

        UIView.animate(
            withDuration: 0.18,
            delay: 0,
            options: [.curveEaseOut, .allowUserInteraction, .beginFromCurrentState],
            animations: { [weak self] in
                self?.writeBottomAnchorIfValid(token: token, expected: expected)
            }
        )
    }

    private func writeBottomAnchorIfValid(token: UInt64, expected: ExpectedAsyncState) {
        guard isAsyncActionValid(token: token, expected: expected) else { return }
        writeBottomAnchor()
    }

    private func writeBottomAnchor() {
        guard let collectionView else { return }
        collectionView.layoutIfNeeded()
        writeContentOffsetY(bottomTarget(in: collectionView))
    }

    private func writeContentOffsetY(_ rawOffsetY: CGFloat) {
        guard let collectionView else { return }
        let offsetY = clampedOffsetY(rawOffsetY, in: collectionView)
        collectionView.setContentOffset(
            CGPoint(x: collectionView.contentOffset.x, y: offsetY),
            animated: false
        )
    }

    private func sampleGeometry() -> ChatScrollGeometrySample {
        guard let collectionView else {
            return ChatScrollGeometrySample(currentOffset: 0, bottomTarget: 0, userInteracting: false)
        }
        return ChatScrollGeometrySample(
            currentOffset: collectionView.contentOffset.y,
            bottomTarget: bottomTarget(in: collectionView),
            userInteracting: collectionView.isTracking || collectionView.isDragging || collectionView.isDecelerating
        )
    }

    private func bottomTarget(in scrollView: UIScrollView) -> CGFloat {
        scrollView.contentSize.height - scrollView.bounds.height + scrollView.adjustedContentInset.bottom
    }

    private func clampedOffsetY(_ offsetY: CGFloat, in scrollView: UIScrollView) -> CGFloat {
        guard offsetY.isFinite else { return scrollView.contentOffset.y }
        let minimumY = -scrollView.adjustedContentInset.top
        let maximumY = max(minimumY, bottomTarget(in: scrollView))
        return min(max(offsetY, minimumY), maximumY)
    }

    private func distanceToBottom(in scrollView: UIScrollView) -> CGFloat {
        let visibleHeight = scrollView.bounds.height
            - scrollView.adjustedContentInset.top
            - scrollView.adjustedContentInset.bottom
        let visibleMaxY = scrollView.contentOffset.y
            + scrollView.adjustedContentInset.top
            + visibleHeight
        return scrollView.contentSize.height - visibleMaxY
    }

    private func isAsyncActionValid(token: UInt64, expected: ExpectedAsyncState) -> Bool {
        guard generation == token else { return false }
        switch (expected, state) {
        case (.anchoring, .anchoring), (.snapping, .snapping):
            return true
        default:
            return false
        }
    }

    private func cancelProgrammaticAnimations() {
        collectionView?.layer.removeAllAnimations()
    }
}
