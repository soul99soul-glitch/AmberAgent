import XCTest
import SwiftUI
import QuartzCore
@testable import SwiftStreamingMarkdown
@testable import iosApp

/// 决定性实验(度量专用,不修复):已经稳定、内容完全没变的前缀,在每次流式
/// 发布中是否仍在产生主线程排版成本?
///
/// 背景:`NovelLengthScanPerfBaselineTests` 已经测得,总量 10/20/40/80KB 四档下
/// 主线程排版耗时随总量近似线性增长,但那组夹具把「前缀长度」和「总量」耦合在
/// 一起(前缀 = 总量 - 固定 1400 尾预算),无法单独回答"是前缀在变贵,还是别的
/// 什么东西在变贵"。本文件把变量拆开:固定每次发布追加的尾部增量完全相同,只
/// 改变前缀长度(0/10/20/40/80KB),单独观察前缀长度对单次发布主线程成本的影响。
///
/// 入口口径、pump harness、`mainThreadCPUNanos` 统计口径均与
/// `NovelLengthScanPerfBaselineTests` 保持一致(直接实例化生产 `NovelSessionBubble`
/// (assistant, isStreaming: true)，走同一条 `ChatAssistantMarkdownView` → vendor
/// 渲染入口),以便与上一轮四档数据横向对比。与该文件的差异:这里只保留一条活跃
/// 尾行,不叠加历史行(历史行与本实验要隔离的变量无关，去掉能显著缩短 80KB 档
/// 的挂载时间，且不影响活跃尾行内部段落的 SwiftUI 复用行为)。
///
/// 同步 test 方法的必要性、`async` 方法会静默冻结 `@Published` 更新的坑，均与
/// `NovelLengthScanPerfBaselineTests` 文件头部记录的取证结论相同，这里不重复
/// 论证，直接复用同一纪律：pump 驱动的端到端测量放在同步方法里。
@MainActor
final class NovelPrefixReuseCostExperimentTests: XCTestCase {

    // MARK: - Fixture (与 NovelLengthScanPerfBaselineTests 相同的段落生成口径)

    private static let closedParagraphTemplate =
        "第%d段已经闭合，围绕人物动机与章节走向的机制推演展开，本段模拟已经生成完毕的" +
        "正文内容，用于把整章长度撑到目标档位，不参与本次采样的逐 delta 测量。"

    private static let tailContinuationSentence =
        "这是本章正在持续生成的连续正文，没有空行分隔，用来复现真实生成中同一段落" +
        "随后续 token 持续变长的布局压力。"

    private static let tailSentinel = "本章正文续写开始。"

    /// 与 `NovelLengthScanPerfBaselineTests.chapterFixture` 的差异:预算直接是
    /// 「稳定前缀」的目标长度，不再从总量里反推——这是本实验要单独控制的变量。
    private static func chapterFixture(
        prefixTargetUTF16: Int,
        tailBudgetUTF16: Int = 1_400
    ) -> (settledPrefix: String, tail: String) {
        var settled = ""
        var index = 0
        while settled.utf16.count < prefixTargetUTF16 {
            index += 1
            let paragraph = String(format: closedParagraphTemplate, index)
            settled += settled.isEmpty ? paragraph : "\n\n" + paragraph
        }
        var tail = tailSentinel
        while tail.utf16.count < tailBudgetUTF16 {
            tail += tailContinuationSentence
        }
        return (settled, tail)
    }

    private static func fullText(_ fixture: (settledPrefix: String, tail: String), tail: String) -> String {
        fixture.settledPrefix.isEmpty ? tail : fixture.settledPrefix + "\n\n" + tail
    }

    private static let deltaChunk = String(repeating: "续", count: 16)
    /// 与既有 48ms 发布节流窗保持一致的采样节奏。
    private static let deltaInterval: TimeInterval = 0.048
    private static let deltaCount = 20

    // MARK: - Stats helpers (与 NovelLengthScanPerfBaselineTests 相同实现)

    private static func median(_ values: [Double]) -> Double { percentile(values, 0.5) }

    private static func percentile(_ values: [Double], _ p: Double) -> Double {
        guard !values.isEmpty else { return 0 }
        let sorted = values.sorted()
        guard sorted.count > 1 else { return sorted[0] }
        let rank = p * Double(sorted.count - 1)
        let lower = Int(rank.rounded(.down))
        let upper = Int(rank.rounded(.up))
        if lower == upper { return sorted[lower] }
        let fraction = rank - Double(lower)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    private static func mainThreadCPUNanos() -> UInt64 {
        clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID)
    }

    // MARK: - Harness(单条活跃尾行，最小复刻)

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

        static let tailMessageID = NovelMessageID()
        static let bottomAnchorID = "novel-prefix-reuse-bottom-anchor"
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

    private static func streamingParagraph(in view: UIView) -> ParagraphUIView? {
        if let textView = view as? ParagraphUIView,
           textView.text.hasPrefix(tailSentinel) {
            return textView
        }
        for subview in view.subviews {
            if let paragraph = streamingParagraph(in: subview) {
                return paragraph
            }
        }
        return nil
    }

    // MARK: - 单档测量

    private struct TierResult {
        let mainThreadLayoutMsPerDelta: [Double]
        let sizeThatFitsCallCount: Int
        let sizeThatFitsCacheHitCount: Int
        let finalParagraphCount: Int
    }

    private func runTier(prefixTargetUTF16: Int) -> TierResult {
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

        let fixture = Self.chapterFixture(prefixTargetUTF16: prefixTargetUTF16)
        var tail = fixture.tail

        let hostFixture = makeFixture()
        defer { hostFixture.tearDown() }
        guard let scrollView = hostFixture.scrollView else {
            XCTFail("Expected the transcript scroll view")
            return TierResult(
                mainThreadLayoutMsPerDelta: [], sizeThatFitsCallCount: 0,
                sizeThatFitsCacheHitCount: 0, finalParagraphCount: 0
            )
        }

        hostFixture.model.tailContent = Self.fullText(fixture, tail: tail)
        hostFixture.model.scrollToBottomTrigger += 1
        XCTAssertTrue(
            pumpUntil(timeout: 12.0) { self.distanceToBottom(scrollView) <= 2 },
            "初始入场必须先锚定到底部(prefixTargetUTF16=\(prefixTargetUTF16))"
        )
        XCTAssertTrue(pumpUntil(timeout: 12.0) {
            guard let paragraph = Self.streamingParagraph(in: hostFixture.host.view) else { return false }
            return paragraph.usesTextKit1 && paragraph.attributedText.length == tail.utf16.count
        }, "必须先等真实 ParagraphUIView 完成首次解析落地,再开始采样增量")
        pump(seconds: 0.4)

        ParagraphViewSizeThatFitsTestHook.reset()

        var mainThreadLayoutMsPerDelta: [Double] = []
        for _ in 0..<Self.deltaCount {
            tail += Self.deltaChunk
            let start = Self.mainThreadCPUNanos()
            hostFixture.model.tailContent = Self.fullText(fixture, tail: tail)
            pump(seconds: Self.deltaInterval)
            let elapsedNanos = Self.mainThreadCPUNanos() - start
            mainThreadLayoutMsPerDelta.append(Double(elapsedNanos) / 1_000_000)
        }
        pump(seconds: 0.6)

        let callCount = ParagraphViewSizeThatFitsTestHook.callCount
        let hitCount = ParagraphViewSizeThatFitsTestHook.cacheHitCount

        // 前缀段落数 = settledPrefix 的 "\n\n" 段数 + 1 条尾段落。用来把
        // sizeThatFits 调用次数换算成"每段落每次发布"的粒度。
        let prefixParagraphCount = fixture.settledPrefix.isEmpty
            ? 0
            : fixture.settledPrefix.components(separatedBy: "\n\n").count

        return TierResult(
            mainThreadLayoutMsPerDelta: mainThreadLayoutMsPerDelta,
            sizeThatFitsCallCount: callCount,
            sizeThatFitsCacheHitCount: hitCount,
            finalParagraphCount: prefixParagraphCount + 1
        )
    }

    // MARK: - 数据采集入口

    private static let tiers: [(label: String, prefixUTF16: Int)] = [
        ("0KB", 0),
        ("10KB", 10_240),
        ("20KB", 20_480),
        ("40KB", 40_960),
        ("80KB", 81_920)
    ]

    /// 固定尾部增量(16 字/次 × 20 次),只改变前缀长度,打印
    /// `[PREFIXCOST]` 行,给出「前缀长度 → 单次发布主线程耗时」对照表,以及
    /// `ParagraphView.sizeThatFits` 的调用次数/缓存命中率。
    func testMainThreadLayoutCostAcrossPrefixLengths() throws {
        for tier in Self.tiers {
            let result = runTier(prefixTargetUTF16: tier.prefixUTF16)

            let layoutMedian = Self.median(result.mainThreadLayoutMsPerDelta)
            let layoutP95 = Self.percentile(result.mainThreadLayoutMsPerDelta, 0.95)
            let totalCalls = result.sizeThatFitsCallCount
            let totalHits = result.sizeThatFitsCacheHitCount
            let hitRate = totalCalls > 0 ? Double(totalHits) / Double(totalCalls) : -1
            let callsPerDelta = Double(totalCalls) / Double(Self.deltaCount)

            print(
                "[PREFIXCOST] tier=\(tier.label) prefixUTF16=\(tier.prefixUTF16) " +
                "paragraphCount=\(result.finalParagraphCount) " +
                "mainThreadLayoutMs(median/p95)=\(layoutMedian)/\(layoutP95) " +
                "sizeThatFits(totalCalls/totalHits/hitRate/callsPerDelta)=" +
                "\(totalCalls)/\(totalHits)/\(hitRate)/\(callsPerDelta)"
            )
        }
    }
}
