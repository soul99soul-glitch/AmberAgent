import XCTest
import SwiftUI
import UIKit
import QuartzCore
@testable import iosApp

/// `ChatReasoningCard` 可见性判断的行为契约与热路径特征。
///
/// `hasBodyText` 经由 `showsBody` 在一次 body 求值中被求值约 10 次
/// (`showsBody` 出现在圆角/chevron/高度/mask/animation 等多处),流式期间
/// 每 48ms 一轮。因此这个判断必须是零分配、可提前退出的——它一旦按全文
/// 分配副本,上万字符的推理就会每秒产生 MB 级主线程临时分配。
@MainActor
final class ChatReasoningCardTests: XCTestCase {

    private final class StreamingReasoningModel: ObservableObject {
        @Published var text: String
        @Published var isThinking = true

        init(text: String) {
            self.text = text
        }
    }

    private struct StreamingReasoningHarness: View {
        @ObservedObject var model: StreamingReasoningModel

        var body: some View {
            VStack(spacing: 20) {
                ChatActivityIslandView(state: .activity(
                    kind: .thinking,
                    title: "正在思考",
                    systemImage: "brain.head.profile",
                    tint: .amber
                ))

                ChatReasoningCard(
                    bodyText: model.text,
                    isThinking: model.isThinking,
                    levelLabel: "高",
                    autoCloseThinking: false
                )

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.top, 80)
            .frame(width: 393, height: 852)
        }
    }

    @MainActor
    private final class DisplayLinkGapProbe: NSObject {
        private weak var scrollView: UIScrollView?
        private var displayLink: CADisplayLink?
        private var previousTimestamp: CFTimeInterval?
        private(set) var gaps: [TimeInterval] = []
        private(set) var contentOffsets: [CGFloat] = []

        init(scrollView: UIScrollView? = nil) {
            self.scrollView = scrollView
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
            defer { previousTimestamp = displayLink.timestamp }
            if let scrollView {
                contentOffsets.append(scrollView.contentOffset.y)
            }
            guard let previousTimestamp else { return }
            gaps.append(displayLink.timestamp - previousTimestamp)
        }
    }

    // MARK: - 行为契约

    func testVisibleTextRecognisesEmptyAndBlankBodies() {
        XCTAssertFalse(ChatReasoningCard.hasVisibleText(""))
        XCTAssertFalse(ChatReasoningCard.hasVisibleText(" "))
        XCTAssertFalse(ChatReasoningCard.hasVisibleText("\n\n"))
        XCTAssertFalse(ChatReasoningCard.hasVisibleText(" \t\r\n "))
    }

    func testVisibleTextRecognisesRealContent() {
        XCTAssertTrue(ChatReasoningCard.hasVisibleText("a"))
        XCTAssertTrue(ChatReasoningCard.hasVisibleText("推理"))
        XCTAssertTrue(ChatReasoningCard.hasVisibleText("  前导空白后有内容"))
        XCTAssertTrue(ChatReasoningCard.hasVisibleText("内容后有尾随空白  \n"))
    }

    func testReasoningBodyMotionOnlyRunsDuringActiveThinking() {
        XCTAssertTrue(ChatReasoningCard.animatesStreamingBody(
            isThinking: true,
            reduceMotion: false
        ))
        XCTAssertFalse(ChatReasoningCard.animatesStreamingBody(
            isThinking: false,
            reduceMotion: false
        ))
        XCTAssertFalse(ChatReasoningCard.animatesStreamingBody(
            isThinking: true,
            reduceMotion: true
        ))
    }

    /// 与被替换的 `trimmingCharacters(in: .whitespacesAndNewlines).isEmpty`
    /// 逐例等价——包含 CharacterSet 与 Unicode `White_Space` 都覆盖的边界字符。
    func testVisibleTextMatchesTrimmingSemantics() {
        let cases = [
            "", " ", "\n", "\t", "\r\n", "\u{000B}", "\u{000C}", "\u{0085}",
            "\u{00A0}", "\u{2028}", "\u{2029}", "\u{3000}",
            "x", " x ", "\u{00A0}x", "。", "\u{3000}。\u{3000}",
        ]
        for text in cases {
            XCTAssertEqual(
                ChatReasoningCard.hasVisibleText(text),
                !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                "可见性判断必须与旧的 trimming 语义一致:\(text.debugDescription)"
            )
        }
    }

    // MARK: - 热路径特征(红→绿判别)

    /// 正文以换行开头时,判断的成本不得随正文长度增长。
    ///
    /// fixture 刻意以 `\n\n` 开头:这是模型 thinking 的常见形态,也是
    /// `trimmingCharacters` 唯一会真的分配全文副本的形态(首字符非空白时
    /// Foundation 走零拷贝快路径,掩盖问题)。规模对应约 1M 字符的推理正文
    /// × 5 轮 body 求值 × 10 处 `showsBody`/`hasBodyText` 引用。
    ///
    /// 实测(1M 字符,Debug):全文拷贝实现 ~90µs/次 → 50 次约 4.5ms;
    /// 提前退出实现 ~0.3µs/次 → 50 次约 0.02ms。阈值取 1.5ms,两侧各有
    /// 三倍以上余量,不依赖具体机器性能。
    ///
    /// 每次迭代取用独立实例,防止编译器把循环不变的调用外提。
    func testVisibleTextCostDoesNotScaleWithLeadingWhitespaceBody() {
        let iterations = 50
        let filler = String(repeating: "推理内容。", count: 200_000)
        let bodies = (0..<iterations).map { "\n\n" + filler + String($0) }

        let start = Self.mainThreadCPUNanos()
        var truthy = 0
        for body in bodies where ChatReasoningCard.hasVisibleText(body) {
            truthy += 1
        }
        let elapsedNanos = Self.mainThreadCPUNanos() - start

        XCTAssertEqual(truthy, iterations)
        let elapsedMillis = Double(elapsedNanos) / 1_000_000
        print(String(
            format: "[PERF] hasVisibleText chars=%d iterations=%d total=%.2fms",
            bodies[0].count, iterations, elapsedMillis
        ))
        XCTAssertLessThan(
            elapsedMillis,
            1.5,
            "以空白开头的推理正文不得让可见性判断按全文分配副本"
        )
    }

    func testStreamingReasoningKeepsThinkingOrbCadenceResponsive() throws {
        let model = StreamingReasoningModel(text: "先核对证据。")
        let fixture = mountHarness(model: model)
        defer {
            fixture.window.isHidden = true
            fixture.window.rootViewController = nil
        }

        pump(seconds: 0.3)
        let textView = try XCTUnwrap(firstSubview(of: UITextView.self, in: fixture.host.view))
        let shortBodyHeight = textView.bounds.height
        XCTAssertLessThan(
            shortBodyHeight,
            80,
            "短思考正文应保持内容自适应高度，不能留出大块空白。"
        )

        let base = model.text + String(
            repeating: "先分析约束、验证证据，再逐步收敛结论。\n",
            count: 240
        )
        model.text = base
        pump(seconds: 0.7)
        fixture.window.layoutIfNeeded()
        XCTAssertGreaterThan(textView.bounds.height, shortBodyHeight)
        XCTAssertLessThanOrEqual(
            textView.bounds.height,
            180.5,
            "流式思考正文应继续遵守 180pt 高度上限。"
        )

        let probe = DisplayLinkGapProbe(scrollView: textView)
        probe.start()
        pump(seconds: 0.15)
        for index in 0..<28 {
            let previousLength = (model.text as NSString).length
            model.text +=
                "第 \(index) 步继续核对状态所有权与终止路径，不能用额外兜底掩盖根因。\n"
            pump(seconds: 0.048)
            if index == 0 {
                let appendedRange = NSRange(
                    location: previousLength,
                    length: (model.text as NSString).length - previousLength
                )
                let newWordAlphas = foregroundAlphas(in: textView, range: appendedRange)
                XCTAssertTrue(
                    newWordAlphas.contains { $0 < 0.95 },
                    "新增思考词段应淡入，不能与旧前缀一起生硬跳出。"
                )
                XCTAssertGreaterThanOrEqual(
                    foregroundAlphas(
                        in: textView,
                        range: NSRange(location: 0, length: previousLength)
                    ).min() ?? 1,
                    0.99,
                    "逐词淡入只能作用于本次新增词段。"
                )
            }
        }
        pump(seconds: 0.7)
        probe.stop()

        let gapMilliseconds = probe.gaps.dropFirst(2).map { $0 * 1_000 }.sorted()
        let maxGap = try XCTUnwrap(gapMilliseconds.last)
        let p95Index = min(
            gapMilliseconds.count - 1,
            Int((Double(gapMilliseconds.count) * 0.95).rounded(.up)) - 1
        )
        let p95Gap = gapMilliseconds[max(0, p95Index)]
        print(String(
            format: "[PERF-HITCH] reasoningOrb samples=%d p95=%.2fms max=%.2fms",
            gapMilliseconds.count,
            p95Gap,
            maxGap
        ))
        let maximumOffsetStep = zip(
            probe.contentOffsets,
            probe.contentOffsets.dropFirst()
        ).map { abs($1 - $0) }.max() ?? 0
        print(String(format: "[PERF-SCROLL] reasoning maxStep=%.2fpt", maximumOffsetStep))

        XCTAssertLessThanOrEqual(
            p95Gap,
            40,
            "流式推理正文更新不能让顶部思考动画跟随 chunk 节奏掉帧。"
        )
        XCTAssertLessThanOrEqual(
            maxGap,
            80,
            "流式推理正文更新不能造成肉眼可见的思考动画停顿。"
        )
        XCTAssertLessThanOrEqual(
            maximumOffsetStep,
            10,
            "思考正文贴底应按帧连续推进，不能随每个 chunk 整行跳动。"
        )
        XCTAssertEqual(textView.text, model.text, "性能修复不能漏掉或截断流式思考正文。")
        XCTAssertGreaterThanOrEqual(
            foregroundAlphas(
                in: textView,
                range: NSRange(location: 0, length: textView.textStorage.length)
            ).min() ?? 1,
            0.99,
            "淡入完成后权威全文必须恢复为完全不透明。"
        )

        model.isThinking = false
        pump(seconds: 0.35)
        fixture.window.layoutIfNeeded()
        XCTAssertGreaterThan(textView.bounds.height, 180, "保留展开时，完成态应使用 260pt 阅读高度。")
        let completedDistanceToBottom = max(
            0,
            textView.contentSize.height - textView.bounds.height - textView.contentOffset.y
        )
        XCTAssertLessThanOrEqual(
            completedDistanceToBottom,
            12,
            "思考完成并扩大阅读高度后，原本贴底的正文仍应保持贴底。"
        )
    }

    func testReasoningTerminalFinishesInFlightFadeWithoutLosingText() throws {
        let model = StreamingReasoningModel(text: "先核对证据。")
        let fixture = mountHarness(model: model)
        defer {
            fixture.window.isHidden = true
            fixture.window.rootViewController = nil
        }

        pump(seconds: 0.65)
        let textView = try XCTUnwrap(firstSubview(of: UITextView.self, in: fixture.host.view))
        let previousLength = (model.text as NSString).length
        model.text += "最后一段思考正在淡入。"
        pump(seconds: 0.05)

        let appendedRange = NSRange(
            location: previousLength,
            length: (model.text as NSString).length - previousLength
        )
        XCTAssertTrue(
            foregroundAlphas(in: textView, range: appendedRange).contains { $0 < 0.95 }
        )

        model.isThinking = false
        pump(seconds: 0.05)

        XCTAssertEqual(textView.text, model.text)
        XCTAssertGreaterThanOrEqual(
            foregroundAlphas(
                in: textView,
                range: NSRange(location: 0, length: textView.textStorage.length)
            ).min() ?? 1,
            0.99,
            "完成、失败或取消统一退出 active 后，正文必须立即完整显示。"
        )
    }

    func testCompletedReasoningStartsAtTopOnFirstPresentation() throws {
        let model = StreamingReasoningModel(text: String(
            repeating: "已完成的历史推理应从开头进入阅读。\n",
            count: 120
        ))
        model.isThinking = false
        let fixture = mountHarness(model: model)
        defer {
            fixture.window.isHidden = true
            fixture.window.rootViewController = nil
        }

        pump(seconds: 0.35)
        fixture.window.layoutIfNeeded()
        let textView = try XCTUnwrap(firstSubview(of: UITextView.self, in: fixture.host.view))

        XCTAssertGreaterThan(textView.contentSize.height, textView.bounds.height)
        XCTAssertEqual(
            textView.contentOffset.y,
            -textView.adjustedContentInset.top,
            accuracy: 1,
            "首次展示已完成的长推理时应从正文开头开始，而不是直接跳到底部。"
        )
    }

    func testReasoningAppendDoesNotStealUserHistoryPosition() throws {
        let model = StreamingReasoningModel(text: String(
            repeating: "先保留用户正在查看的历史推理位置。\n",
            count: 120
        ))
        let fixture = mountHarness(model: model)
        defer {
            fixture.window.isHidden = true
            fixture.window.rootViewController = nil
        }

        pump(seconds: 0.75)
        let textView = try XCTUnwrap(firstSubview(of: UITextView.self, in: fixture.host.view))
        XCTAssertGreaterThan(textView.contentSize.height, textView.bounds.height)
        textView.delegate?.scrollViewWillBeginDragging?(textView)
        let historyOffset = max(
            -textView.adjustedContentInset.top,
            textView.contentOffset.y - 60
        )
        textView.setContentOffset(CGPoint(x: 0, y: historyOffset), animated: false)

        model.text += "流式更新不能把阅读位置重新拉到底部。"
        pump(seconds: 0.35)

        XCTAssertEqual(textView.contentOffset.y, historyOffset, accuracy: 1)
        XCTAssertEqual(textView.text, model.text)
    }

    private func mountHarness(model: StreamingReasoningModel) -> (
        window: UIWindow,
        host: UIHostingController<StreamingReasoningHarness>
    ) {
        let host = UIHostingController(rootView: StreamingReasoningHarness(model: model))
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
        return (window, host)
    }

    private func pump(seconds: TimeInterval) {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01))
        }
    }

    private func firstSubview<T: UIView>(of type: T.Type, in view: UIView) -> T? {
        if let match = view as? T { return match }
        for subview in view.subviews {
            if let match = firstSubview(of: type, in: subview) { return match }
        }
        return nil
    }

    private func foregroundAlphas(in textView: UITextView, range: NSRange) -> [CGFloat] {
        guard range.length > 0, NSMaxRange(range) <= textView.textStorage.length else { return [] }
        var result: [CGFloat] = []
        textView.textStorage.enumerateAttribute(.foregroundColor, in: range) { value, _, _ in
            let color = (value as? UIColor) ?? textView.textColor ?? .label
            result.append(color.cgColor.alpha)
        }
        return result
    }

    private static func mainThreadCPUNanos() -> UInt64 {
        clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID)
    }
}
