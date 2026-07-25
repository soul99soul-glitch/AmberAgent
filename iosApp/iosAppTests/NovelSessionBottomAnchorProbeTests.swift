import XCTest
import SwiftUI
import QuartzCore
@testable import iosApp

/// 小说创作页「底部锚点稳定性」探针。
///
/// 背景:小说会话流式期间,候选气泡底部的状态标签(「完整章节 · 收录后成为新章」)
/// 应当在屏幕上绝对固定,内容向上生长。2026-07-21 曾改为「sizeChanges 底锚唯一
/// 所有者」架构(见下文「2026-07-26 撤锚更正」)。
///
/// 探针已实证的结论(本套件即证据):
/// - 生产旧架构「追赶」(每次增长事件 → withAnimation(.linear 0.08) scrollTo)
///   会在突发增长后留下可采样的瞬态欠账——锚点先被顶下去、动画再追回,
///   这就是真机上「完整章节 · 收录后成为新章」标签突然下移的机制
///   (PROJECT_STATE 2026-07-21 记录的 53pt 归因)。
/// - ScrollPosition 的底部锚定(initialOffset / scrollTo 底部锚点)在持续增长下
///   不可靠:欠账会逐拍累积。收缩能停在底部只是 UIKit clamp 的自然结果。
///   因此「删掉跟随、依赖天然锚定」不是可行简化,已被证伪并锁定。
/// - `.defaultScrollAnchor(.bottom, for: .sizeChanges)`(iOS 18+)在本文件的
///   合成谐成布局(Color.frame(height:) 代理流式气泡、单次干净布局 pass)下
///   同步钉住底部,33/120pt 突发增长与收缩逐帧零欠账。
///
/// ### 2026-07-26 撤锚更正
/// 上述 sizeChanges「零欠账」只在本文件的合成 harness 里成立。真机录屏
/// (15fps、逐帧亮度剖面对齐)在小说「写整章」实测到连续三次约
/// −391/−398/−385px 的**结构性跳变**(残差 10-16,远高于正常 1-3),证明生产
/// `ChatAssistantMarkdownView` → vendor `ParagraphUIView`(UITextView 增量
/// TextKit 布局 + 异步 invalidateIntrinsicContentSize)的真实异步增量路径下,
/// sizeChanges 并不能同步吸收增长——本文件的 `Color.frame(height:)` 代理从未
/// 覆盖过这条路径。生产已撤掉 sizeChanges 锚,把增长所有权交回
/// `onScrollGeometryChange` 的 measured-geometry 回调(经
/// `NovelSessionBottomFollowPolicy.reduce` 的 `.measuredStreamGrowth` 分支发出
/// `.followBottom(animated: false)`)。
///
/// 判据也随之修正:成功标准**不是**「零欠账」,而是「没有可见的大幅结构性跳变;
/// 几十 pt 级的瞬态欠账、且在同帧或下一帧被无动画修正,是可接受的」——上面
/// 「追赶引入瞬态欠账」的教训是「欠账被 0.08s/0.2s 动画放大成肉眼可见的跳动」,
/// 不是「存在任何非零瞬态欠账」。本文件下方 sizeChanges 相关的 4 条 canary
/// 现在只证明「sizeChanges 这个 SwiftUI 机制本身在单次布局 pass 内可以零欠账」
/// 这一孤立事实,**不再代表生产当前策略**,也不能作为「锚在生产下安全」的依据。
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

    /// 裁决:保留,契约仍成立且仍是活跃防回归锁。
    /// 旧生产算法(增长事件 → 0.08s 动画 scrollTo)在复刻中必然产生可采样的
    /// 瞬态欠账:每个突发先把锚点顶下去,动画再追回。这是真机截图中状态标签
    /// 突然下移的机制(PROJECT_STATE 2026-07-21 记录的 53pt 归因)。2026-07-26
    /// 撤锚更正后,生产恢复用 measured-geometry 回调驱动跟随,但命令显式
    /// `animated: false`(执行侧 `Transaction(animation: nil)`,不经过
    /// `startExplicitBottomAnimation()` 的 0.2s easeOut)——本用例锁的正是
    /// 「不可以给这条自动跟随包动画」这条契约,不能删。
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

    // MARK: - sizeChanges 钉住(2026-07-26 裁决:降级为机制事实记录,非生产安全依据)
    //
    // 裁决:保留断言、更新范围声明。这四条(含下方浏览历史兼容性的一半)守护的
    // 契约曾经是「锚必须存在且在生产下有效」——这个假设已被真机录屏(−390px 结构性
    // 跳变)推翻,不能再用它们的绿色证明锚在生产下安全。但断言本身描述的是
    // sizeChanges 这个 SwiftUI 机制在本文件合成 harness(Color.frame(height:) 单次
    // 布局 pass)下的真实、可复现的性质,并非虚假陈述,故不删除、不放宽数值,只降级
    // 用途说明并移除「生产采用」措辞。

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

    /// 裁决:保留。天然锚定(pinsSizeChanges=false)分支验证的是生产当前实际路径
    /// (浏览历史时 `NovelSessionBottomFollowPolicy.reduce` 对 `.browsingHistory`
    /// 模式下的 `.measuredStreamGrowth`/`.staticContentGrowth` 是 no-op,详见
    /// `NovelSessionPresentation.swift`),仍是有效契约。sizeChanges 分支
    /// (pinsSizeChanges=true)现在只是同一断言下的机制事实记录,不代表生产已撤锚
    /// 的现状——生产不再无条件挂载它,行锚定位置不被增长拽走已改由 policy 层的
    /// `.browsingHistory` 分支保证(见 NovelSessionReplayTests 对应 canary)。
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

    /// 裁决:保留为 sizeChanges 机制事实记录(见上方 MARK 说明),不再代表生产
    /// 现状。生产侧等价的「显式回底后恢复跟随」语义由
    /// `NovelSessionBottomFollowPolicy.reduce` 的 `.explicitBottomRequested` /
    /// `.userDragEnded(isAtBottom: true)` 分支覆盖,已有独立的 NovelSessionReplayTests
    /// 单测锁定(`testUserDragEndingNearBottomCommitsSemanticBottomFollow` 等)。
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
