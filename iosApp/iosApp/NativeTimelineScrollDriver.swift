import SwiftUI
import UIKit

@MainActor
final class NativeTimelineScrollDriver: NSObject {
    var onFallback: ((NativeTimelineScrollFallbackReason, Bool) -> Void)?

    private weak var scrollView: UIScrollView?
    private var state: NativeTimelineScrollState = .idle
    private var displayLink: CADisplayLink?
    private var lastDisplayLinkTimestamp: CFTimeInterval?
    private var generation: UInt64 = 0
    private var lastViewportMetrics: ViewportMetrics?
    private var automaticFollowEnabled = true
    private var fallbackReason: NativeTimelineScrollFallbackReason?
    private var horizontalOffsetDriftClampUsed = false

    func attach(_ scrollView: UIScrollView) {
        guard fallbackReason == nil else { return }
        guard self.scrollView !== scrollView else { return }
        self.scrollView = scrollView
        lastViewportMetrics = ViewportMetrics(scrollView)
        horizontalOffsetDriftClampUsed = false
        scrollView.keyboardDismissMode = .interactive
        _ = validateScrollViewHealth()
    }

    func invalidate() {
        generation &+= 1
        stopFrameDriver()
        state = .idle
        scrollView = nil
        lastViewportMetrics = nil
        fallbackReason = nil
        horizontalOffsetDriftClampUsed = false
    }

    func setAutomaticFollowEnabled(_ enabled: Bool) {
        guard automaticFollowEnabled != enabled else { return }
        automaticFollowEnabled = enabled
        generation &+= 1
        stopFrameDriver()
        state = .idle
        if !enabled {
            cancelProgrammaticMotion()
        }
    }

    func submit(_ intent: NativeTimelineScrollIntent, now: TimeInterval = CACurrentMediaTime()) {
        guard fallbackReason == nil else { return }
        if !automaticFollowEnabled {
            switch intent {
            case .streamContentGrew, .userDragEnded(isAtBottom: true):
                return
            default:
                break
            }
        }
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
        guard validateScrollViewHealth() else { return }
        guard automaticFollowEnabled else { return }
        scrollView.layoutIfNeeded()
        let viewportMetrics = ViewportMetrics(scrollView)
        if viewportMetrics != lastViewportMetrics {
            lastViewportMetrics = viewportMetrics
            submit(.viewportChanged)
            return
        }
        reduceAndPerform(.layoutSettled(token: nil), token: generation)
    }

    func isAtBottomNow() -> Bool {
        guard let distance = distanceToBottomNow() else { return false }
        return distance <= NativeTimelineScrollCore.resumeEpsilon
    }

    func distanceToBottomNow() -> CGFloat? {
        guard let scrollView else { return nil }
        return distanceToBottom(in: scrollView)
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
        scrollView.adjustedContentInset.bottom
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

@MainActor
private struct ViewportMetrics: Equatable {
    let size: CGSize
    let adjustedContentInset: UIEdgeInsets

    init(_ scrollView: UIScrollView) {
        size = scrollView.bounds.size
        adjustedContentInset = scrollView.adjustedContentInset
    }
}

/// Resolves the `UIScrollView` owned by a SwiftUI `ScrollView` and reports native
/// layout/interaction metric changes to the shared bottom-follow driver.
struct NativeTimelineScrollViewResolver: UIViewRepresentable {
    let onResolve: (UIScrollView) -> Void
    let onMetricsChanged: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onMetricsChanged: onMetricsChanged)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isHidden = true
        return view
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.invalidate()
    }

    func updateUIView(_ view: UIView, context: Context) {
        DispatchQueue.main.async { [weak view] in
            guard let scrollView = view?.nativeTimelineAncestor(of: UIScrollView.self) else { return }
            onResolve(scrollView)
            context.coordinator.observe(scrollView)
        }
    }

    @MainActor
    final class Coordinator: NSObject {
        private let onMetricsChanged: () -> Void
        private weak var observedScrollView: UIScrollView?
        private var displayLink: CADisplayLink?
        private var pendingCallback = false
        private var lastMetrics: Metrics?
        private var invalidated = false

        init(onMetricsChanged: @escaping () -> Void) {
            self.onMetricsChanged = onMetricsChanged
        }

        func invalidate() {
            invalidated = true
            displayLink?.invalidate()
            displayLink = nil
            observedScrollView = nil
        }

        func observe(_ scrollView: UIScrollView) {
            guard !invalidated else { return }
            guard observedScrollView !== scrollView else { return }
            observedScrollView = scrollView
            lastMetrics = Metrics(scrollView)
            startDisplayLinkIfNeeded()
            scheduleCallback()
        }

        private func startDisplayLinkIfNeeded() {
            guard displayLink == nil else { return }
            let link = CADisplayLink(target: self, selector: #selector(displayLinkTick))
            link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 120, preferred: 60)
            link.add(to: .main, forMode: .common)
            displayLink = link
        }

        @objc
        private func displayLinkTick() {
            guard let scrollView = observedScrollView else { return }
            let metrics = Metrics(scrollView)
            guard metrics != lastMetrics else { return }
            lastMetrics = metrics
            scheduleCallback()
        }

        private func scheduleCallback() {
            guard !invalidated else { return }
            guard !pendingCallback else { return }
            pendingCallback = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                pendingCallback = false
                guard !invalidated else { return }
                onMetricsChanged()
            }
        }

        @MainActor
        private struct Metrics: Equatable {
            let contentOffset: CGPoint
            let contentSize: CGSize
            let bounds: CGRect
            let adjustedContentInset: UIEdgeInsets
            let isDragging: Bool
            let isTracking: Bool
            let isDecelerating: Bool

            init(_ scrollView: UIScrollView) {
                contentOffset = scrollView.contentOffset
                contentSize = scrollView.contentSize
                bounds = scrollView.bounds
                adjustedContentInset = scrollView.adjustedContentInset
                isDragging = scrollView.isDragging
                isTracking = scrollView.isTracking
                isDecelerating = scrollView.isDecelerating
            }
        }
    }
}

private extension UIView {
    func nativeTimelineAncestor<T: UIView>(of type: T.Type) -> T? {
        var current = superview
        while let view = current {
            if let match = view as? T {
                return match
            }
            current = view.superview
        }
        return nil
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
