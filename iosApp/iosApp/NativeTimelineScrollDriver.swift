import UIKit

@MainActor
final class NativeTimelineScrollDriver: NSObject {
    var onFallback: ((NativeTimelineScrollFallbackReason, Bool) -> Void)?

    private weak var scrollView: UIScrollView?
    private var state: NativeTimelineScrollState = .idle
    private var displayLink: CADisplayLink?
    private var lastDisplayLinkTimestamp: CFTimeInterval?
    private var generation: UInt64 = 0
    private var keyboardOverlap: CGFloat = 0
    private var composerHeight: CGFloat = 0
    private var hasAttachedScrollView = false
    private var fallbackReason: NativeTimelineScrollFallbackReason?
    private var horizontalOffsetDriftClampUsed = false

    func attach(_ scrollView: UIScrollView) {
        guard fallbackReason == nil else { return }
        guard self.scrollView !== scrollView else { return }
        let shouldReplayInitialBottom = !hasAttachedScrollView
        hasAttachedScrollView = true
        self.scrollView = scrollView
        horizontalOffsetDriftClampUsed = false
        scrollView.keyboardDismissMode = .interactive
        if shouldReplayInitialBottom {
            submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        }
    }

    func invalidate() {
        generation &+= 1
        stopFrameDriver()
        state = .idle
        scrollView = nil
        hasAttachedScrollView = false
        fallbackReason = nil
        horizontalOffsetDriftClampUsed = false
    }

    func submit(_ intent: NativeTimelineScrollIntent, now: TimeInterval = CACurrentMediaTime()) {
        guard fallbackReason == nil else { return }
        generation &+= 1
        let token = generation
        if case .userDragBegan = intent {
            cancelProgrammaticMotion()
        }
        reduceAndPerform(normalizedIntent(intent, token: token), token: token, now: now)
    }

    func handleLayoutMetricsChanged() {
        guard fallbackReason == nil else { return }
        guard let scrollView else { return }
        scrollView.layoutIfNeeded()
        reduceAndPerform(.layoutSettled(token: nil), token: generation)
    }

    func isAtBottomNow() -> Bool {
        sampleGeometry().isNearBottom
    }

    var isAttached: Bool {
        scrollView != nil && fallbackReason == nil
    }

    var isFollowingBottomOrKeyboardFocus: Bool {
        switch state {
        case .followingBottom, .keyboardFocus:
            return true
        case .idle, .pausedForUser:
            return false
        }
    }

    var isPausedForUser: Bool {
        if case .pausedForUser = state {
            return true
        }
        return false
    }

    private func reduceAndPerform(
        _ intent: NativeTimelineScrollIntent,
        token: UInt64,
        now: TimeInterval = CACurrentMediaTime()
    ) {
        guard validateScrollViewHealth() else { return }
        let result = NativeTimelineScrollCore.reduce(
            state: state,
            intent: intent,
            geometry: sampleGeometry(),
            now: now
        )
        state = result.state
        perform(result.actions, token: token)
    }

    func handleKeyboardWillChange(_ notification: Notification, composerHeight: CGFloat) {
        guard fallbackReason == nil else { return }
        guard let frameValue = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue,
              let scrollView else { return }
        let keyboardFrame = frameValue.cgRectValue
        let scrollFrame = scrollView.convert(scrollView.bounds, to: nil)
        let overlap = max(0, scrollFrame.maxY - keyboardFrame.minY)
        submit(
            .keyboardWillChange(
                token: generation &+ 1,
                overlap: overlap,
                composerHeight: composerHeight
            )
        )
    }

    private func perform(_ actions: [NativeTimelineScrollAction], token: UInt64) {
        guard fallbackReason == nil else { return }
        for action in actions {
            switch action {
            case let .requestBottomAnchor(animated, _):
                requestBottomAnchor(animated: animated, token: token)
            case let .writeOffsetY(offsetY):
                writeOffsetY(offsetY)
            case .startFrameDriver:
                startFrameDriver()
            case .stopFrameDriver:
                stopFrameDriver()
            case let .updateObstruction(keyboardOverlap, composerHeight):
                self.keyboardOverlap = keyboardOverlap
                self.composerHeight = composerHeight
            case .markKeyboardFocusComplete:
                break
            }
        }
    }

    private func requestBottomAnchor(animated: Bool, token: UInt64) {
        guard let scrollView else { return }
        scrollView.layoutIfNeeded()
        writeBottomTarget(animated: animated)
        scheduleBottomConvergence(
            generationToken: token,
            layoutToken: bottomConvergenceLayoutToken(defaultToken: token),
            remainingPasses: 6
        )
    }

    private func scheduleBottomConvergence(generationToken: UInt64, layoutToken: UInt64, remainingPasses: Int) {
        guard remainingPasses > 0 else {
            reportFallback(.bottomConvergenceExhausted)
            return
        }
        Task { @MainActor [weak self] in
            await Task.yield()
            guard let self,
                  self.fallbackReason == nil,
                  self.generation == generationToken,
                  let scrollView = self.scrollView else { return }
            scrollView.layoutIfNeeded()
            self.reduceAndPerform(.layoutSettled(token: layoutToken), token: generationToken)
            guard self.canContinueBottomConvergence(
                generationToken: generationToken,
                layoutToken: layoutToken,
                in: scrollView
            ) else { return }
            if self.distanceToBottom(in: scrollView) <= NativeTimelineScrollCore.bottomEpsilon {
                if self.isKeyboardFocus(token: layoutToken) {
                    self.scheduleBottomConvergence(
                        generationToken: generationToken,
                        layoutToken: layoutToken,
                        remainingPasses: remainingPasses - 1
                    )
                }
                return
            }
            self.writeBottomTarget(animated: false)
            self.scheduleBottomConvergence(
                generationToken: generationToken,
                layoutToken: layoutToken,
                remainingPasses: remainingPasses - 1
            )
        }
    }

    private func writeBottomTarget(animated: Bool) {
        guard let scrollView else { return }
        let target = bottomTarget(in: scrollView)
        guard animated else {
            UIView.performWithoutAnimation { [weak self] in
                self?.writeOffsetY(target)
            }
            return
        }
        UIView.animate(
            withDuration: 0.18,
            delay: 0,
            options: [.curveEaseOut, .allowUserInteraction, .beginFromCurrentState],
            animations: { [weak self] in
                self?.writeOffsetY(target)
            }
        )
    }

    private func startFrameDriver() {
        guard displayLink == nil else { return }
        lastDisplayLinkTimestamp = nil
        let link = CADisplayLink(target: self, selector: #selector(displayLinkTick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func stopFrameDriver() {
        displayLink?.invalidate()
        displayLink = nil
        lastDisplayLinkTimestamp = nil
    }

    private func cancelProgrammaticMotion() {
        scrollView?.layer.removeAllAnimations()
        scrollView?.superview?.layer.removeAllAnimations()
    }

    @objc private func displayLinkTick(_ link: CADisplayLink) {
        guard validateScrollViewHealth() else { return }
        let now = link.timestamp
        let dt = lastDisplayLinkTimestamp.map { now - $0 } ?? link.duration
        lastDisplayLinkTimestamp = now
        let result = NativeTimelineScrollCore.tick(
            state: state,
            geometry: sampleGeometry(),
            now: now,
            dt: dt
        )
        state = result.state
        perform(result.actions, token: generation)
    }

    private func sampleGeometry() -> NativeTimelineScrollGeometry {
        guard let scrollView else {
            return NativeTimelineScrollGeometry(
                offsetY: 0,
                contentHeight: 0,
                viewportHeight: 1,
                adjustedInsetTop: 0,
                adjustedInsetBottom: 0,
                keyboardOverlap: keyboardOverlap,
                composerHeight: composerHeight,
                distanceToBottom: 0,
                userInteracting: false
            )
        }
        return NativeTimelineScrollGeometry(
            offsetY: scrollView.contentOffset.y,
            contentHeight: scrollView.contentSize.height,
            viewportHeight: scrollView.bounds.height,
            adjustedInsetTop: scrollView.adjustedContentInset.top,
            adjustedInsetBottom: scrollView.adjustedContentInset.bottom,
            keyboardOverlap: keyboardOverlap,
            composerHeight: composerHeight,
            distanceToBottom: distanceToBottom(in: scrollView),
            userInteracting: scrollView.isTracking || scrollView.isDragging || scrollView.isDecelerating
        )
    }

    private func writeOffsetY(_ rawOffsetY: CGFloat) {
        guard let scrollView else { return }
        guard rawOffsetY.isFinite else {
            reportFallback(.nonFiniteOffset)
            return
        }
        scrollView.setContentOffset(
            CGPoint(x: 0, y: clampedOffsetY(rawOffsetY, in: scrollView)),
            animated: false
        )
    }

    private func bottomTarget(in scrollView: UIScrollView) -> CGFloat {
        max(
            -scrollView.adjustedContentInset.top,
            scrollView.contentSize.height - scrollView.bounds.height + effectiveBottomInset(in: scrollView)
        )
    }

    private func clampedOffsetY(_ offsetY: CGFloat, in scrollView: UIScrollView) -> CGFloat {
        guard offsetY.isFinite else { return scrollView.contentOffset.y }
        let minimumY = -scrollView.adjustedContentInset.top
        let maximumY = max(minimumY, bottomTarget(in: scrollView))
        return min(max(offsetY, minimumY), maximumY)
    }

    private func distanceToBottom(in scrollView: UIScrollView) -> CGFloat {
        let visibleHeight = max(
            1,
            scrollView.bounds.height -
                scrollView.adjustedContentInset.top -
                effectiveBottomInset(in: scrollView)
        )
        let visibleMaxY = scrollView.contentOffset.y +
            scrollView.adjustedContentInset.top +
            visibleHeight
        return max(0, scrollView.contentSize.height - visibleMaxY)
    }

    private func effectiveBottomInset(in scrollView: UIScrollView) -> CGFloat {
        max(scrollView.adjustedContentInset.bottom, keyboardOverlap + composerHeight)
    }

    private func isKeyboardFocus(token: UInt64) -> Bool {
        guard case let .keyboardFocus(transaction) = state else { return false }
        return transaction.token == token
    }

    private func bottomConvergenceLayoutToken(defaultToken: UInt64) -> UInt64 {
        guard case let .keyboardFocus(transaction) = state else { return defaultToken }
        return transaction.token
    }

    private func canContinueBottomConvergence(
        generationToken: UInt64,
        layoutToken: UInt64,
        in scrollView: UIScrollView
    ) -> Bool {
        guard fallbackReason == nil, generation == generationToken else { return false }
        guard !(scrollView.isTracking || scrollView.isDragging || scrollView.isDecelerating) else { return false }
        switch state {
        case .followingBottom:
            return true
        case let .keyboardFocus(transaction):
            return transaction.token == layoutToken
        case .idle, .pausedForUser:
            return false
        }
    }

    private func normalizedIntent(
        _ intent: NativeTimelineScrollIntent,
        token: UInt64
    ) -> NativeTimelineScrollIntent {
        switch intent {
        case let .explicitBottom(source, animated, _):
            guard source == .composerFocus else { return intent }
            return .explicitBottom(source: source, animated: animated, keyboardToken: token)
        case let .keyboardWillChange(_, overlap, composerHeight):
            return .keyboardWillChange(token: token, overlap: overlap, composerHeight: composerHeight)
        default:
            return intent
        }
    }

    private func validateScrollViewHealth() -> Bool {
        guard let scrollView else { return true }
        if abs(scrollView.contentOffset.x) > 0.5 {
            scrollView.contentOffset.x = 0
            guard !horizontalOffsetDriftClampUsed else {
                reportFallback(.horizontalOffsetDrift)
                return false
            }
            horizontalOffsetDriftClampUsed = true
        }
        return true
    }

    private func reportFallback(_ reason: NativeTimelineScrollFallbackReason) {
        guard fallbackReason == nil else { return }
        let shouldReplayBottom = shouldReplayBottomAfterFallback()
        let geometry = sampleGeometry()
        fallbackReason = reason
        generation &+= 1
        cancelProgrammaticMotion()
        stopFrameDriver()
        state = .idle
        scrollView = nil
        hasAttachedScrollView = false
        NativeTimelineScrollDiagnostics.logFallback(
            reason: reason,
            geometry: geometry
        )
        onFallback?(reason, shouldReplayBottom)
    }

    private func shouldReplayBottomAfterFallback() -> Bool {
        if let scrollView,
           scrollView.isTracking || scrollView.isDragging || scrollView.isDecelerating {
            return false
        }
        switch state {
        case .followingBottom, .keyboardFocus:
            return true
        case .idle, .pausedForUser:
            return false
        }
    }
}

enum NativeTimelineScrollDiagnostics {
    static func logFallbackActivated(reason: NativeTimelineScrollFallbackReason) {
        print("[AA-NATIVE-SCROLL] fallbackActivated reason=\(reason.rawValue)")
    }

    static func logFallback(
        reason: NativeTimelineScrollFallbackReason,
        geometry: NativeTimelineScrollGeometry
    ) {
        print(
            "[AA-NATIVE-SCROLL] fallback reason=\(reason.rawValue) " +
                "offsetY=\(String(format: "%.1f", geometry.offsetY)) " +
                "distance=\(String(format: "%.1f", geometry.distanceToBottom)) " +
                "content=\(String(format: "%.1f", geometry.contentHeight)) " +
                "viewport=\(String(format: "%.1f", geometry.viewportHeight))"
        )
    }
}
