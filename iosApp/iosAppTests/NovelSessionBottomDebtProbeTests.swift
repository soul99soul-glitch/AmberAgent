import XCTest
import SwiftUI
import QuartzCore
@testable import SwiftStreamingMarkdown
@testable import iosApp

/// 小说创作页「生产等价」底部欠账探针。
///
/// 背景:`NovelSessionView.swift:317-324` 的 `NovelSessionSizeChangesPinModifier`
/// 自称是「流式底部锚点的唯一所有者」，其证据来自 `NovelSessionBottomAnchorProbeTests`。
/// 但那套探针用 `Color.blue.frame(height: model.tailHeight)` 当「流式气泡代理」——
/// 这只测到 SwiftUI 单次干净布局 pass 内的高度变化，从未测过生产
/// `ChatAssistantMarkdownView` → vendor `ParagraphUIView`(UITextView 增量 TextKit
/// 布局 + 异步 `invalidateIntrinsicContentSize`)的真实异步增量路径。标准 Chat 曾经
/// 用同一枚锚点在 24KB 长散文回放下实测出稳定 ~893pt 的底部欠账、并因此把锚撤走，
/// 详见 `ChatCollectionMessageList.swift:1510` 注释与
/// `ChatSwiftUIStreamReplayTests.testLongProseViewportFollowStaysLineSizedAtTwentyFourKB`。
///
/// 本探针把同一套「24KB 单段长 CJK 散文、48ms 节奏逐字追加」回放对齐到小说：
/// - 内容渲染直接实例化生产 `NovelSessionBubble`(assistant, isStreaming: true)，
///   与 `NovelSessionRowView`/`transcriptRow` 走同一条 `ChatAssistantMarkdownView`
///   渲染入口，不使用任何固定高度占位（不用 `Color.frame(height:)`）。
/// - 容器与锚点同构于 `NovelSessionView.transcript`：ScrollView + VStack（历史行在
///   LazyVStack、增长中的尾行渲染在其外，与生产的 `visibleHistoricalRows`/
///   `activeRunRows` 结构一致）+ `.defaultScrollAnchor(.bottom, for: .sizeChanges)`
///   （当前小说 `followGeneration=true` 且非原生滚动驱动时的挂载态）。
/// - 断言口径、24KB 量级、48ms 节奏、60 次追加、72pt 门槛均与 Chat 的
///   `testLongProseViewportFollowStaysLineSizedAtTwentyFourKB` 完全同构，数值可以
///   直接对比。
///
/// 为什么当初不接线 `NovelSessionView` 的 `onScrollGeometryChange` → `dispatchFollowEvent`：
/// 已用 `git blame` 核实，`NovelSessionBottomFollowPolicy.reduce`
/// （`NovelSessionPresentation.swift`）在 `.followingBottom` 模式下对
/// `.measuredStreamGrowth`（活跃流式尾行的增长事件）曾经是显式 no-op —— commit
/// `d8eda9f5b`「fix(ios): stabilize novel streaming bottom anchor」把这里原本的
/// `commands.append(.followBottom(animated: false))` 改成了 `break`，注释明写
/// 「The ScrollView's size-change anchor absorbs live growth in the layout
/// transaction. A second scroll command recreates the visible bottom debt.」。
/// 当时的结论是：在生产代码里，`.sizeChanges` 在真实流式阶段本来就是**唯一**在
/// 工作的写者；这个探针不接线 follow policy 更贴近生产当时的真实执行路径。
///
/// ### 2026-07-26 状态更新（重要更正，勿再据此判定锚安全）
/// 上面这个结论已被真机录屏推翻：小说「写整章」流式生成时,15fps 录屏 + 行亮度
/// 剖面实测到连续三次约 −391/−398/−385px 的**结构性跳变**（残差 10-16，远高于
/// 正常 1-3），证明 `.sizeChanges` 在生产的完整投影管线 + 上千段落负载下**不是**
/// 可靠的唯一写者。本探针虽然用了真实 `NovelSessionBubble` / `ParagraphUIView`
/// 渲染（不是 `Color.frame(height:)` 占位），但仍是精简 harness——裸 ScrollView +
/// 20 条历史行 + 单个尾行，没有完整 `NovelSessionView.transcript` 投影管线，也没有
/// 上千段落负载，没有复现真机的失败条件。**不要再用这个探针的绿色作为「锚在生产下
/// 安全」的依据。**
///
/// 生产已撤掉 `.sizeChanges` 锚（`NovelSessionView.swift` 不再挂载），
/// `NovelSessionBottomFollowPolicy.reduce` 的 `.followingBottom` 分支恢复为
/// `commands.append(.followBottom(animated: false))`，measured-geometry 回调
/// 重新成为唯一写者。本文件下面的 harness 仍保留独立挂载
/// `.defaultScrollAnchor(.bottom, for: .sizeChanges)` 不代表生产现状，只作为
/// 「sizeChanges 在真实 TextKit 增量布局路径下欠账表现」这一独立事实的记录保留；
/// 其 72pt 门槛断言与 Chat 同构可比，不需要因本次撤锚而改动。
@MainActor
final class NovelSessionBottomDebtProbeTests: XCTestCase {

    // MARK: - Harness(与生产 `NovelSessionView.transcript` 同构的最小复刻)

    private final class HarnessModel: ObservableObject {
        @Published var tailContent: String = ""
        @Published var scrollToBottomTrigger = 0
    }

    private struct Harness: View {
        @ObservedObject var model: HarnessModel
        @State private var scrollPosition = ScrollPosition()

        var body: some View {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        ForEach(0..<Self.historyRowCount, id: \.self) { index in
                            NovelSessionBubble(
                                messageID: NovelMessageID(),
                                role: .assistant,
                                kind: .discussion,
                                granularity: nil,
                                content: "第\(index)轮讨论已经闭合，围绕人物动机与章节走向展开，" +
                                    "不应随末尾流式增长重新布局。",
                                isStreaming: false,
                                transientPhase: nil,
                                hasEverStreamed: true,
                                runStatus: nil,
                                candidateStatus: nil,
                                isRegeneration: false,
                                polishTransactionStatus: nil,
                        isAdoptingPolish: false,
                                committedChange: nil,
                                askUser: nil,
                                actions: [],
                                onAction: { _ in },
                                onAnswerAskUser: { _, _ in }
                            )
                        }
                    }

                    // 生产结构:活跃尾行(activeRunRows)渲染在 LazyVStack 之外,
                    // 与 NovelSessionView.transcript 一致,避免 Lazy 装卸干扰真实
                    // geometry 采样。
                    NovelSessionBubble(
                        messageID: Self.tailMessageID,
                        role: .assistant,
                        kind: .proseCandidate,
                        granularity: .continuation,
                        content: model.tailContent,
                        isStreaming: true,
                        transientPhase: .streaming,
                        hasEverStreamed: true,
                        runStatus: nil,
                        candidateStatus: nil,
                        isRegeneration: false,
                        polishTransactionStatus: nil,
                        isAdoptingPolish: false,
                        committedChange: nil,
                        askUser: nil,
                        actions: [],
                        onAction: { _ in },
                        onAnswerAskUser: { _, _ in }
                    )

                    Color.clear
                        .frame(height: ChatLayout.followBottomGap)
                        .id(Self.bottomAnchorID)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, ChatLayout.contentHorizontalInset)
                .padding(.top, 12)
                .padding(.bottom, 8)
                .scrollTargetLayout()
            }
            .scrollPosition($scrollPosition)
            .defaultScrollAnchor(.bottom, for: .initialOffset)
            // 生产当前挂载态(NovelSessionView.swift:322-324,followGeneration=true
            // 且非原生滚动驱动时):.sizeChanges 是流式尾行增长的唯一写者。
            .defaultScrollAnchor(.bottom, for: .sizeChanges)
            .defaultScrollAnchor(.top, for: .alignment)
            .scrollIndicators(.hidden)
            .onChange(of: model.scrollToBottomTrigger) { _, _ in
                var transaction = Transaction()
                transaction.animation = nil
                withTransaction(transaction) {
                    scrollPosition.scrollTo(id: Self.bottomAnchorID, anchor: .bottom)
                }
            }
        }

        static let historyRowCount = 20
        static let tailMessageID = NovelMessageID()
        static let bottomAnchorID = "novel-debt-probe-bottom-anchor"
    }

    private struct Fixture {
        let model: HarnessModel
        let window: UIWindow
        let host: UIHostingController<Harness>

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

    @MainActor
    private final class DebtProbe: NSObject {
        struct Sample {
            let contentHeight: CGFloat
            let distanceToBottom: CGFloat
            let contentOffsetX: CGFloat
            let paragraphLength: Int?
            let paragraphUsesTextKit1: Bool?
        }

        private weak var scrollView: UIScrollView?
        private weak var rootView: UIView?
        private var displayLink: CADisplayLink?
        private(set) var samples: [Sample] = []

        init(scrollView: UIScrollView, rootView: UIView) {
            self.scrollView = scrollView
            self.rootView = rootView
        }

        func start() {
            let displayLink = CADisplayLink(target: self, selector: #selector(tick(_:)))
            displayLink.add(to: .main, forMode: .common)
            self.displayLink = displayLink
        }

        func stop() {
            displayLink?.invalidate()
            displayLink = nil
        }

        @objc private func tick(_ displayLink: CADisplayLink) {
            guard let scrollView else { return }
            let paragraph = rootView.flatMap(Self.streamingParagraph(in:))
            let visibleBottom = scrollView.contentOffset.y + scrollView.bounds.height
                - scrollView.adjustedContentInset.bottom
            samples.append(Sample(
                contentHeight: scrollView.contentSize.height,
                distanceToBottom: max(0, scrollView.contentSize.height - visibleBottom),
                contentOffsetX: scrollView.contentOffset.x,
                paragraphLength: paragraph?.attributedText.length,
                paragraphUsesTextKit1: paragraph?.usesTextKit1
            ))
        }

        static func streamingParagraph(in view: UIView) -> ParagraphUIView? {
            if let textView = view as? ParagraphUIView,
               textView.text.hasPrefix("连续正文开始。") {
                return textView
            }
            for subview in view.subviews {
                if let paragraph = streamingParagraph(in: subview) {
                    return paragraph
                }
            }
            return nil
        }
    }

    private func makeFixture() -> Fixture {
        let model = HarnessModel()
        let host = UIHostingController(rootView: Harness(model: model))
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

    // MARK: - Pumping(与 ChatSwiftUIStreamReplayTests/ChatStructuralRetypeReplayTests 同构)

    private func pump(seconds: TimeInterval, onTick: (() -> Void)? = nil) {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01))
            onTick?()
        }
    }

    @discardableResult
    private func pumpUntil(
        timeout: TimeInterval,
        _ predicate: () -> Bool
    ) -> Bool {
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

    // MARK: - 探针

    /// 24KB 长文规模下的小说流式尾行底部欠账 canary。数值口径与
    /// `ChatSwiftUIStreamReplayTests.testLongProseViewportFollowStaysLineSizedAtTwentyFourKB`
    /// 完全同构(24KB 单段、48ms 节奏、60 次追加、72pt 门槛),可以直接对比。
    func testProductionEquivalentLiveTailGrowthStaysWithinBottomDebtBudgetAtTwentyFourKB() throws {
        let blockKey = IOSDisplayPreferenceKeys.streamingBlockMarkdown
        let previousBlockSetting = UserDefaults.standard.object(forKey: blockKey)
        UserDefaults.standard.set(true, forKey: blockKey)
        defer {
            if let previousBlockSetting {
                UserDefaults.standard.set(previousBlockSetting, forKey: blockKey)
            } else {
                UserDefaults.standard.removeObject(forKey: blockKey)
            }
        }

        let fixture = makeFixture()
        defer { fixture.tearDown() }
        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the transcript scroll view")
        }

        var tailText = "连续正文开始。" + String(
            repeating: "这是一段没有空行分隔的长篇连续正文，用来复现真实回答累计变长后同一段落仍在增长的布局压力。",
            count: 540
        )
        XCTAssertGreaterThan(tailText.utf16.count, 23_000, "fixture 必须覆盖 24KB 量级的真实长段落")
        let settledPrefix = (1...40)
            .map { "第\($0)段已经闭合，正文内容围绕流式渲染的分层职责展开，不应随末尾长段落的 delta 重新布局。" }
            .joined(separator: "\n\n")

        fixture.model.tailContent = settledPrefix + "\n\n" + tailText
        fixture.model.scrollToBottomTrigger += 1
        XCTAssertTrue(
            pumpUntil(timeout: 6.0) { distanceToBottom(scrollView) <= 2 },
            "初始入场必须先锚定到底部,才能开始测增长期间是否维持贴底"
        )
        XCTAssertTrue(pumpUntil(timeout: 6.0) {
            guard let paragraph = DebtProbe.streamingParagraph(in: fixture.host.view) else { return false }
            return paragraph.usesTextKit1 && paragraph.attributedText.length == tailText.utf16.count
        }, "必须先等真实 ParagraphUIView 完成首次解析落地,再开始采样增量")
        pump(seconds: 0.4)

        let probe = DebtProbe(scrollView: scrollView, rootView: fixture.host.view)
        probe.start()
        for step in 0..<60 {
            let delta = String(repeating: "流", count: 12)
            tailText += delta
            fixture.model.tailContent = settledPrefix + "\n\n" + tailText
            pump(seconds: 0.048)
            let distance = distanceToBottom(scrollView)
            print("[novelDebt] step=\(step) distance=\(distance) contentHeight=\(scrollView.contentSize.height)")
        }
        pump(seconds: 0.8)
        probe.stop()

        let settledSamples = probe.samples.filter { ($0.paragraphLength ?? 0) >= 100 }
        XCTAssertGreaterThan(settledSamples.count, 10, "采样不足,无法评估欠账")
        let maxBottomDebt = settledSamples.map(\.distanceToBottom).max() ?? .greatestFiniteMagnitude
        let horizontalOffsets = settledSamples.map(\.contentOffsetX)
        let horizontalDrift = (horizontalOffsets.max() ?? 0) - (horizontalOffsets.min() ?? 0)

        print(
            "[novelDebt] summary samples=\(settledSamples.count) maxBottomDebt=\(maxBottomDebt) " +
            "finalParagraphLength=\(probe.samples.last?.paragraphLength ?? -1) " +
            "finalUsesTextKit1=\(String(describing: probe.samples.last?.paragraphUsesTextKit1))"
        )

        XCTAssertEqual(probe.samples.last?.paragraphLength, tailText.utf16.count)
        XCTAssertEqual(probe.samples.last?.paragraphUsesTextKit1, true, "24KB 无附件长段落必须走 TextKit 1")
        XCTAssertLessThanOrEqual(
            maxBottomDebt,
            72,
            "24KB 贴底流式不能积累两行以上的未跟随高度(与 Chat 同门槛):\(maxBottomDebt)"
        )
        XCTAssertLessThanOrEqual(
            horizontalDrift,
            0.5,
            "重复语义 bottom edge 写不能让垂直创作列表产生横向漂移:\(horizontalOffsets)"
        )
    }
}
