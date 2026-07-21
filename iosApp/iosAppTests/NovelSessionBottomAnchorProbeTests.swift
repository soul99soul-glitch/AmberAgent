import XCTest
import SwiftUI
import QuartzCore
@testable import iosApp

/// 小说创作页「底部锚点稳定性」探针。
///
/// 背景:小说会话流式期间,候选气泡底部的状态标签(「完整章节 · 收录后成为新章」)
/// 应当在屏幕上绝对固定,内容向上生长。现有实现是「追赶」架构——
/// onScrollGeometryChange 观测到内容增长后,再用 0.08s 动画 scrollTo 追底部。
///
/// 探针已实证的结论(本套件即证据):
/// - 生产旧架构「追赶」(每次增长事件 → withAnimation(.linear 0.08) scrollTo)
///   会在突发增长后留下可采样的瞬态欠账——锚点先被顶下去、动画再追回,
///   这就是真机上「完整章节 · 收录后成为新章」标签突然下移的机制。
/// - ScrollPosition 的底部锚定(initialOffset / scrollTo 底部锚点)在持续增长下
///   不可靠:欠账会逐拍累积。收缩能停在底部只是 UIKit clamp 的自然结果。
///   因此「删掉追赶、依赖天然锚定」不是可行简化,已被证伪并锁定。
/// - `.defaultScrollAnchor(.bottom, for: .sizeChanges)`(iOS 18+)在同一布局
///   事务内同步钉住底部:33/120pt 突发增长与收缩都逐帧零欠账;行锚定位置
///   (浏览历史的等价物)不被增长拽走,与浏览历史语义兼容。
/// 生产采用:sizeChanges 底锚为流式底部锚点唯一所有者(按「跟随生成」与
/// 原生滚动驱动门控),一次性归位命令(回底/终态)统一为无动画。
@MainActor
final class NovelSessionBottomAnchorProbeTests: XCTestCase {

    // MARK: - Harness

    private final class ReplicaModel: ObservableObject {
        @Published var tailHeight: CGFloat = 240
        @Published var scrollToBottomTrigger = 0
        @Published var scrollToHistoryTrigger = 0
    }

    private enum FollowStyle {
        /// 不追赶:只依靠 ScrollPosition 锚定。
        case none
        /// 生产算法:每次增长事件用 0.08s 线性动画 scrollTo 底部锚点。
        case animatedChase
    }

    private struct FollowStyleModifier: ViewModifier {
        let style: FollowStyle
        let tailHeight: CGFloat
        let scrollPosition: Binding<ScrollPosition>

        func body(content: Content) -> some View {
            switch style {
            case .none:
                content
            case .animatedChase:
                content.onChange(of: tailHeight) { _, _ in
                    withAnimation(.linear(duration: 0.08)) {
                        scrollPosition.wrappedValue.scrollTo(
                            id: TranscriptReplica.bottomAnchorID,
                            anchor: .bottom
                        )
                    }
                }
            }
        }
    }

    private struct SizeChangesPinModifier: ViewModifier {
        let enabled: Bool
        func body(content: Content) -> some View {
            if enabled {
                content.defaultScrollAnchor(.bottom, for: .sizeChanges)
            } else {
                content
            }
        }
    }

    /// 与 NovelSessionView.transcript 同构的最小复刻:历史行 + 可变高度尾部
    /// (代理流式气泡)+ 状态标签 + 96pt 底部 spacer。高度全部显式给定,
    /// 让每次内容增长量精确可控,断言不依赖文本排版。
    private struct TranscriptReplica: View {
        @ObservedObject var model: ReplicaModel
        let pinsSizeChanges: Bool
        let followStyle: FollowStyle
        @State private var scrollPosition = ScrollPosition()

        var body: some View {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(0..<24, id: \.self) { index in
                        Text("历史行 \(index)")
                            .frame(maxWidth: .infinity, minHeight: 80, alignment: .leading)
                            .id("history-\(index)")
                    }
                    // 流式气泡代理:高度随 model 变化,等价于气泡内 Markdown 增长。
                    Color.blue.opacity(0.1)
                        .frame(height: model.tailHeight)
                    Text("完整章节 · 收录后成为新章")
                        .font(.caption)
                    Color.clear
                        .frame(height: 96)
                        .id(Self.bottomAnchorID)
                }
                .padding(.horizontal, 16)
                .scrollTargetLayout()
            }
            .scrollPosition($scrollPosition)
            .defaultScrollAnchor(.bottom, for: .initialOffset)
            .defaultScrollAnchor(.top, for: .alignment)
            .scrollIndicators(.hidden)
            .modifier(SizeChangesPinModifier(enabled: pinsSizeChanges))
            .modifier(FollowStyleModifier(
                style: followStyle,
                tailHeight: model.tailHeight,
                scrollPosition: $scrollPosition
            ))
            .onChange(of: model.scrollToBottomTrigger) { _, _ in
                var transaction = Transaction()
                transaction.animation = nil
                withTransaction(transaction) {
                    scrollPosition.scrollTo(id: Self.bottomAnchorID, anchor: .bottom)
                }
            }
            .onChange(of: model.scrollToHistoryTrigger) { _, _ in
                var transaction = Transaction()
                transaction.animation = nil
                withTransaction(transaction) {
                    scrollPosition.scrollTo(id: "history-2", anchor: .top)
                }
            }
        }

        static let bottomAnchorID = "probe-bottom-anchor"
    }

    private struct Fixture {
        let model: ReplicaModel
        let window: UIWindow
        let host: UIHostingController<TranscriptReplica>

        var scrollView: UIScrollView? {
            Self.findScrollView(in: host.view)
        }

        static func findScrollView(in view: UIView) -> UIScrollView? {
            if let scrollView = view as? UIScrollView { return scrollView }
            for subview in view.subviews {
                if let found = findScrollView(in: subview) { return found }
            }
            return nil
        }

        func tearDown() {
            window.isHidden = true
            window.rootViewController = nil
        }
    }

    private final class BottomDebtProbe: NSObject {
        private weak var scrollView: UIScrollView?
        private var displayLink: CADisplayLink?
        private(set) var samples: [CGFloat] = []

        init(scrollView: UIScrollView) {
            self.scrollView = scrollView
        }

        func start() {
            let displayLink = CADisplayLink(target: self, selector: #selector(tick))
            displayLink.add(to: .main, forMode: .common)
            self.displayLink = displayLink
        }

        func stop() {
            displayLink?.invalidate()
            displayLink = nil
        }

        @objc private func tick() {
            guard let scrollView else { return }
            let visibleBottom = scrollView.contentOffset.y + scrollView.bounds.height
                - scrollView.adjustedContentInset.bottom
            samples.append(max(0, scrollView.contentSize.height - visibleBottom))
        }
    }

    private func makeFixture(
        pinsSizeChanges: Bool,
        followStyle: FollowStyle = .none
    ) -> Fixture {
        let model = ReplicaModel()
        let host = UIHostingController(rootView: TranscriptReplica(
            model: model,
            pinsSizeChanges: pinsSizeChanges,
            followStyle: followStyle
        ))
        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
            window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        } else {
            window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        }
        window.rootViewController = host
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        return Fixture(model: model, window: window, host: host)
    }

    private func pump(seconds: TimeInterval) {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01))
        }
    }

    @discardableResult
    private func pumpUntil(timeout: TimeInterval, _ predicate: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return true }
            pump(seconds: 0.05)
        }
        return predicate()
    }

    private func distanceToBottom(_ scrollView: UIScrollView) -> CGFloat {
        let visibleBottom = scrollView.contentOffset.y + scrollView.bounds.height
            - scrollView.adjustedContentInset.bottom
        return max(0, scrollView.contentSize.height - visibleBottom)
    }

    private func scrollToExactBottom(_ fixture: Fixture) {
        fixture.model.scrollToBottomTrigger += 1
        XCTAssertTrue(pumpUntil(timeout: 2.0) {
            guard let scrollView = fixture.scrollView else { return false }
            return distanceToBottom(scrollView) <= 1.0
        }, "必须能精确到达底部")
    }

    // MARK: - 被证伪假设的锁定(防止未来误简化)

    /// 锁定:没有任何钉住机制时,ScrollPosition 底部锚定在持续增长下不可靠,
    /// 欠账逐拍累积。「删掉追赶、依赖天然锚定」不是可行的简化方向。
    func testNaturalAnchorWithoutPinningAccumulatesDebtUnderContinuousGrowth() {
        let fixture = makeFixture(pinsSizeChanges: false, followStyle: .none)
        defer { fixture.tearDown() }
        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected scroll view")
        }
        scrollToExactBottom(fixture)

        for _ in 0..<10 {
            fixture.model.tailHeight += 66
            pump(seconds: 0.048)
        }
        pump(seconds: 0.3)

        let debt = distanceToBottom(scrollView)
        XCTAssertGreaterThan(
            debt,
            300,
            "天然锚定在持续增长下必须暴露欠账(被证伪假设的锁定): \(debt)"
        )
    }

    /// 收缩能停在底部只是 UIKit clamp 的自然结果,不是 SwiftUI 锚定在工作。
    func testNaturalBottomAnchorBehaviorOnShrink() {
        let fixture = makeFixture(pinsSizeChanges: false, followStyle: .none)
        defer { fixture.tearDown() }
        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected scroll view")
        }
        scrollToExactBottom(fixture)

        let probe = BottomDebtProbe(scrollView: scrollView)
        probe.start()
        fixture.model.tailHeight -= 160
        pump(seconds: 0.4)
        probe.stop()

        let maxDebt = probe.samples.max() ?? .greatestFiniteMagnitude
        XCTAssertLessThanOrEqual(
            maxDebt,
            1.0,
            "收缩时天然钉住不应在底部留空白: \(maxDebt)"
        )
    }

    // MARK: - 旧追赶架构的抖动证据(生产已移除追赶,本用例保留机制证据)

    /// 旧生产算法(增长事件 → 0.08s 动画 scrollTo)在复刻中必然产生可采样的
    /// 瞬态欠账:每个突发先把锚点顶下去,动画再追回。这是真机截图中状态标签
    /// 突然下移的机制,也是生产改由 sizeChanges 底锚唯一持有的原因。
    func testAnimatedChaseIntroducesTransientDebtOnBursts() {
        let fixture = makeFixture(pinsSizeChanges: false, followStyle: .animatedChase)
        defer { fixture.tearDown() }
        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected scroll view")
        }
        scrollToExactBottom(fixture)

        let probe = BottomDebtProbe(scrollView: scrollView)
        probe.start()
        for _ in 0..<6 {
            fixture.model.tailHeight += 120
            pump(seconds: 0.16)
        }
        pump(seconds: 0.6)
        probe.stop()

        let transientDebt = probe.samples.max() ?? 0
        XCTAssertGreaterThan(
            transientDebt,
            20,
            "追赶架构必须在突发后留下可见瞬态欠账(当前生产症状),实测 \(transientDebt)"
        )
    }

    // MARK: - sizeChanges 钉住(已通过首轮探针,保留为永久 canary)

    func testSizeChangesPinningKeepsZeroDebtThroughBurstGrowth() {
        let fixture = makeFixture(pinsSizeChanges: true)
        defer { fixture.tearDown() }
        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected scroll view")
        }
        scrollToExactBottom(fixture)

        let probe = BottomDebtProbe(scrollView: scrollView)
        probe.start()
        for _ in 0..<20 {
            fixture.model.tailHeight += 33
            pump(seconds: 0.048)
        }
        for _ in 0..<5 {
            fixture.model.tailHeight += 120
            pump(seconds: 0.048)
        }
        pump(seconds: 0.4)
        probe.stop()

        XCTAssertGreaterThan(probe.samples.count, 10, "采样不足")
        let maxDebt = probe.samples.max() ?? .greatestFiniteMagnitude
        XCTAssertLessThanOrEqual(
            maxDebt,
            1.0,
            "sizeChanges 钉住必须在每一帧同步消除欠账,观测到最大欠账 \(maxDebt)"
        )
    }

    func testSizeChangesPinningKeepsZeroDebtThroughShrink() {
        let fixture = makeFixture(pinsSizeChanges: true)
        defer { fixture.tearDown() }
        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected scroll view")
        }
        scrollToExactBottom(fixture)

        let probe = BottomDebtProbe(scrollView: scrollView)
        probe.start()
        fixture.model.tailHeight -= 160
        pump(seconds: 0.4)
        probe.stop()

        let maxDebt = probe.samples.max() ?? .greatestFiniteMagnitude
        XCTAssertLessThanOrEqual(
            maxDebt,
            1.0,
            "收缩时钉住必须同步吸收,不能在底部留空白: \(maxDebt)"
        )
    }

    // MARK: - 浏览历史兼容性

    /// 行锚定位置(用户上滑阅读历史的等价物)在内容增长时不得被拽走。
    /// 分别对天然锚定与 sizeChanges 钉住验证——若 sizeChanges 会把行锚定
    /// 位置拽回底部,则不能无条件启用,必须按跟随模式门控。
    func testRowAnchoredPositionIsNotDraggedByGrowth() {
        for pinsSizeChanges in [false, true] {
            let fixture = makeFixture(pinsSizeChanges: pinsSizeChanges)
            guard let scrollView = fixture.scrollView else {
                fixture.tearDown()
                return XCTFail("Expected scroll view")
            }
            scrollToExactBottom(fixture)

            fixture.model.scrollToHistoryTrigger += 1
            XCTAssertTrue(pumpUntil(timeout: 2.0) {
                distanceToBottom(scrollView) > 200
            }, "必须确实离开底部进入历史行")
            let offsetBefore = scrollView.contentOffset.y

            fixture.model.tailHeight += 120
            pump(seconds: 0.3)

            let offsetAfter = scrollView.contentOffset.y
            XCTAssertEqual(
                offsetAfter,
                offsetBefore,
                accuracy: 1.0,
                "行锚定位置不得被增长拽走(pinsSizeChanges=\(pinsSizeChanges)): " +
                    "\(offsetBefore) -> \(offsetAfter)"
            )
            fixture.tearDown()
        }
    }

    /// 从历史行显式回底后,sizeChanges 钉住必须恢复——与生产的回底恢复语义一致。
    func testPinningResumesAfterExplicitReturnToBottom() {
        let fixture = makeFixture(pinsSizeChanges: true, followStyle: .none)
        defer { fixture.tearDown() }
        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected scroll view")
        }
        scrollToExactBottom(fixture)

        fixture.model.scrollToHistoryTrigger += 1
        XCTAssertTrue(pumpUntil(timeout: 2.0) {
            distanceToBottom(scrollView) > 200
        })

        scrollToExactBottom(fixture)

        let probe = BottomDebtProbe(scrollView: scrollView)
        probe.start()
        for _ in 0..<5 {
            fixture.model.tailHeight += 66
            pump(seconds: 0.048)
        }
        pump(seconds: 0.3)
        probe.stop()

        let maxDebt = probe.samples.max() ?? .greatestFiniteMagnitude
        XCTAssertLessThanOrEqual(
            maxDebt,
            1.0,
            "显式回底后钉住必须恢复: \(maxDebt)"
        )
    }
}
