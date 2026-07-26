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

    @discardableResult
    func attach(_ scrollView: UIScrollView) -> Bool {
        guard fallbackReason == nil else { return false }
        guard self.scrollView !== scrollView else { return false }
        if self.scrollView != nil {
            generation &+= 1
            cancelProgrammaticMotion()
            stopFrameDriver()
            state = .idle
        }
        self.scrollView = scrollView
        lastViewportMetrics = ViewportMetrics(scrollView)
        horizontalOffsetDriftClampUsed = false
        scrollView.keyboardDismissMode = .interactive
        _ = validateScrollViewHealth()
        return isAttached
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
            // 耗尽不交出所有权（契约见 testBottomConvergenceExhaustionKeepsNativeDriverAsOwner），
            // 但未到底时必须留下可观测痕迹，否则"停在略高于底部"在生产不可排查。
            if let scrollView, distanceToBottom(in: scrollView) > NativeTimelineScrollCore.bottomEpsilon {
                NativeTimelineScrollDiagnostics.logBottomConvergenceExhausted(geometry: sampleGeometry())
            }
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
/// layout metric changes to the shared bottom-follow driver.
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
        struct Metrics: Equatable {
            let contentSize: CGSize
            let viewportSize: CGSize
            let adjustedContentInset: UIEdgeInsets

            init(_ scrollView: UIScrollView) {
                contentSize = scrollView.contentSize
                viewportSize = scrollView.bounds.size
                adjustedContentInset = scrollView.adjustedContentInset
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

/// [TEMP-DIAG] 临时诊断:定位「生成一段时间后整列表大幅位移 + 本条回复回退到更早内容」。
/// 常驻采样 + 自动判定异常 + 异常时 dump 前后窗口,不依赖人眼盯屏。
/// 根因定罪后整体删除(搜索 TEMP-DIAG)。
@MainActor
enum ChatStreamAnomalyRecorder {
    struct Sample {
        let time: Double
        let offsetY: CGFloat
        let contentHeight: CGFloat
        let distanceToBottom: CGFloat
        let farFromBottom: Bool
        /// 数据源里这条回复的字数(真实进度)。
        let sourceLength: Int
        /// 实际喂给渲染的字数。与 sourceLength 背离 = 显示停在旧快照上。
        let displayedLength: Int
        let skips: Int
        let dragging: Bool
        let fallback: String
        let rows: Int
        let appears: Int
        let disappears: Int
    }

    /// 判定阈值:offset 跳变按「超过半屏」算,高度塌陷按 200pt 算。
    private static let offsetJumpThreshold: CGFloat = 300
    private static let heightCollapseThreshold: CGFloat = 200
    private static let windowBefore = 24
    private static let windowAfter = 8

    private static var samples: [Sample] = []
    private static var startTime: CFAbsoluteTime?
    private static var skipCount = 0
    private static var pendingDump: (reason: String, atIndex: Int)?
    private static var lastDumpIndex = -1_000
    private static var dumpCount = 0

    static func noteLiveTailUpdateSkipped() {
        #if DEBUG
        skipCount += 1
        #endif
    }

    /// 物化行计数。注意:这是 Chat 与小说**共享**的全局静态,视图整体销毁时
    /// `onDisappear` 不保证成对触发,计数会漂移——只可用于「突变时是否同时在动」
    /// 这种相对判断,不可当作绝对行数。
    static func noteRowAppeared() {
        #if DEBUG
        materializedRows += 1
        rowAppearances += 1
        #endif
    }

    static func noteRowDisappeared() {
        #if DEBUG
        materializedRows -= 1
        rowDisappearances += 1
        #endif
    }

    /// [TEMP-DIAG] 小说创作的呈现节奏层。`targetContent` 被整体替换时,
    /// 若新目标不再以已显示内容为前缀,pacer 会当场跳到新目标——内容可能变短。
    static var novelSourceLength = 0
    static var novelDisplayedLength = 0
    /// [TEMP-DIAG] LazyVStack 当前物化(已上屏并被测量)的行数。
    /// 若 contentHeight 的突变每次都伴随这个数变化,即坐实「估算高度 ↔ 实测高度」震荡。
    static var materializedRows = 0
    static var rowAppearances = 0
    static var rowDisappearances = 0

    static func noteNovelPresentation(displayed: Int, target: Int, next: Int, prefixHeld: Bool) {
        #if DEBUG
        novelSourceLength = target
        novelDisplayedLength = next
        guard !prefixHeld || next < displayed else { return }
        skipCount += 1
        NSLog("%@", String(
            format: "[AA-NOVEL] 呈现回退 displayed=%d target=%d next=%d prefixHeld=%@",
            displayed, target, next, prefixHeld ? "YES" : "no"
        ))
        #endif
    }

    static func record(
        offsetY: CGFloat,
        contentHeight: CGFloat,
        distanceToBottom: CGFloat,
        farFromBottom: Bool,
        sourceLength: Int,
        displayedLength: Int,
        dragging: Bool,
        fallback: String
    ) {
        #if DEBUG
        let now = CFAbsoluteTimeGetCurrent()
        if startTime == nil { startTime = now }
        let sample = Sample(
            time: now - (startTime ?? now),
            offsetY: offsetY,
            contentHeight: contentHeight,
            distanceToBottom: distanceToBottom,
            farFromBottom: farFromBottom,
            sourceLength: sourceLength,
            displayedLength: displayedLength,
            skips: skipCount,
            dragging: dragging,
            fallback: fallback,
            rows: materializedRows,
            appears: rowAppearances,
            disappears: rowDisappearances
        )

        let previous = samples.last
        samples.append(sample)
        if samples.count > 600 {
            // 环形截断会把所有下标左移,必须同步修正记录的绝对下标——否则
            // pendingDump/lastDumpIndex 会变成永远追不上的未来值,异常检测
            // 静默死掉,而心跳照常输出,日志上完全区分不出「没异常」和「检测器已死」。
            samples.removeFirst(200)
            lastDumpIndex -= 200
            if let pending = pendingDump {
                pendingDump = (pending.reason, pending.atIndex - 200)
            }
        }

        // 心跳:证明记录器真的在跑,否则「没有异常」与「从未采样」无法区分。
        if samples.count == 1 || samples.count % 120 == 0 {
            NSLog("%@", String(
                format: "[AA-HEARTBEAT] samples=%d content=%.0f rows=%d src=%d shown=%d dist=%.0f fallback=%@",
                samples.count, contentHeight, materializedRows, sourceLength,
                displayedLength, distanceToBottom, fallback
            ))
        }

        // 已经在等 dump 的话,先把异常之后的窗口攒够再输出。
        if let pending = pendingDump, samples.count - pending.atIndex >= windowAfter {
            emit(reason: pending.reason, anomalyIndex: pending.atIndex)
            pendingDump = nil
            return
        }

        guard let previous, pendingDump == nil,
              samples.count - lastDumpIndex > windowBefore else { return }

        var reasons: [String] = []
        if displayedLength < previous.displayedLength {
            reasons.append("显示内容回退 \(previous.displayedLength)→\(displayedLength)")
        }
        if abs(contentHeight - previous.contentHeight) > 2000 {
            reasons.append(String(
                format: "高度突变 %.0f→%.0f", previous.contentHeight, contentHeight
            ))
        }
        if previous.contentHeight - contentHeight > heightCollapseThreshold {
            reasons.append(String(
                format: "内容高度塌陷 %.0f→%.0f", previous.contentHeight, contentHeight
            ))
        }
        if !dragging, abs(offsetY - previous.offsetY) > offsetJumpThreshold {
            reasons.append(String(
                format: "offset 跳变 %.0f→%.0f", previous.offsetY, offsetY
            ))
        }
        guard !reasons.isEmpty else { return }
        pendingDump = (reasons.joined(separator: " | "), samples.count - 1)
        #endif
    }

    private static func emit(reason: String, anomalyIndex: Int) {
        dumpCount += 1
        lastDumpIndex = samples.count
        let lower = max(0, anomalyIndex - windowBefore)
        let upper = min(samples.count - 1, anomalyIndex + windowAfter)
        NSLog("%@", "[AA-ANOMALY] #\(dumpCount) \(reason)")
        NSLog("%@", "[AA-ANOMALY] t | offsetY | content | distToBottom | far | src | shown | rows | +app | -dis | drag | fallback")
        for index in lower...upper {
            let sample = samples[index]
            let marker = index == anomalyIndex ? " <<< 异常" : ""
            NSLog("%@", String(
                format: "[AA-ANOMALY] %6.2f %8.1f %9.1f %8.1f %5@ %6d %6d %5d %5d %5d %5@ %@%@",
                sample.time,
                sample.offsetY,
                sample.contentHeight,
                sample.distanceToBottom,
                sample.farFromBottom ? "YES" : "no",
                sample.sourceLength,
                sample.displayedLength,
                sample.rows,
                sample.appears,
                sample.disappears,
                sample.dragging ? "YES" : "no",
                sample.fallback,
                marker
            ))
        }
    }
}

enum NativeTimelineScrollDiagnostics {
    static func logFallbackActivated(reason: NativeTimelineScrollFallbackReason) {
        print("[AA-NATIVE-SCROLL] fallbackActivated reason=\(reason.rawValue)")
    }

    static func logBottomConvergenceExhausted(geometry: NativeTimelineScrollGeometry) {
        print(
            "[AA-NATIVE-SCROLL] bottomConvergenceExhausted " +
                "offsetY=\(String(format: "%.1f", geometry.offsetY)) " +
                "distance=\(String(format: "%.1f", geometry.distanceToBottom)) " +
                "content=\(String(format: "%.1f", geometry.contentHeight)) " +
                "viewport=\(String(format: "%.1f", geometry.viewportHeight))"
        )
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
