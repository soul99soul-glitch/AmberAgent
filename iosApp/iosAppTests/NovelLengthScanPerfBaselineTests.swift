import XCTest
import SwiftUI
import QuartzCore
@testable import SwiftStreamingMarkdown
@testable import iosApp

/// 度量专用(不修复):小说「写整章」场景下 10/20/40/80KB 四档长度扫描基准。
///
/// 背景(真机录屏已量化的症状,详见任务书,不在此重新论证):`deepseek-v4-pro`
/// 写整章时,单条 assistant 正文长到几十 KB,录屏逐帧分析显示 0.5 秒级冻结 +
/// 推进极不均匀 + 结构性突变。本探针只取数、不修复,产出:
/// 1. 每次发布的成本分解(cmark 解析 / 转换为 `RenderableDocument` / 主线程排版);
/// 2. vendor `ParagraphUIView.setParagraphContents` 的 append 快路径命中率与回退
///    原因分布(用 `#if DEBUG` 计数钩子观测,零生产开销,见 `ParagraphUIView.swift`);
/// 3. 可见文本真正变化的间隔序列(对应用户看到的"冻结");
/// 4. 单次 contentHeight 增量分布(对应"位置快速变动"的幅度)。
///
/// 入口口径:直接实例化生产 `NovelSessionBubble`(assistant, isStreaming: true),
/// 与 `NovelSessionRowView`/`transcriptRow` 走同一条 `ChatAssistantMarkdownView` →
/// vendor 渲染入口,容器结构同构于 `NovelSessionView.transcript`
/// (ScrollView + VStack + LazyVStack 历史行 + 活跃尾行 + `.sizeChanges` 锚点),
/// 与 `NovelSessionBottomDebtProbeTests` 的 harness 同构(该文件的类型是私有的,
/// 跨文件不可见,这里按需重建最小子集,不修改原文件)。
///
/// 夹具口径:每档由「已闭合的 `\n\n` 分段前缀」+「一段没有空行分隔、持续增长的
/// 尾段落」拼成,总量对齐目标档位。前缀提供"正常段落分隔,模拟真实整章"的结构;
/// 尾段落保持单一连续段落增长,匹配真实生成中"当前正在写的这一段没有被打断"的
/// 情形(与 `NovelSessionBottomDebtProbeTests` 的 24KB 夹具同构,可直接对比量级)。
///
/// 成本分解口径:app 层 `ChatStreamingMarkdownBlockParser.blocks(in:)`(经既有
/// `#if DEBUG` 测试出口 `ChatStreamingMarkdownBlockParserTestSupport`)在本夹具下
/// 不含表格/代码块,因此恒定只产出一个 `.text` block,其 `text` 字段等于**全量
/// 累计文本**——这就是"已知事实"里"parseNow 每次发布对全量累计文本重新解析"的
/// 直接实测证据:app 层块级拆分完全不减少 cmark 重解析的输入规模,增量复用只发生
/// 在这之后的 `RenderableDocument.reusingUnchangedPrefix(from:)`。因此这里直接
/// 调用与生产 `ChatStableStreamingMarkdownController.parseNow` 完全相同的三个
/// vendor API(`MarkdownParserImpl.parse` / `RenderableDocument.init(document:config:)`
/// / `.reusingUnchangedPrefix(from:)`),对同一组累计文本计时,不额外修改任何
/// 生产代码。
///
/// 拆成两个测试方法的原因(取证坐实,记录下来供后续同类 harness 参考):
/// 用 `RunLoop.current.run(mode:before:)` 手动泵驱动 `@Published` 状态更新收敛的
/// harness(与 `NovelSessionBottomDebtProbeTests`/`ChatStructuralRetypeReplayTests`
/// 同构),**只在同步(非 `async`)test 方法里可靠**。把完全相同的 pump 循环放进
/// `async throws` test 方法(即使方法体内在调用 UIKit 部分之前不做任何
/// `await`),SwiftUI 发布的第一帧会正常落地,但此后所有 `model.tailContent`
/// 变更会被静默吞掉——`ParagraphUIView` 内容永久冻结在第一帧的值,`scrollView
/// .contentSize` 也不再变化,直到测试结束都不会恢复,且没有任何报错或超时。
/// 反复二分定位到:差异只在于「顶层 test 方法是否被标记为 `async`」,与是否
/// 真的发生了 `await` 悬挂无关——纯同步版本(哪怕通过私有 helper 方法调用)稳定
/// 复现良好,`async` 版本稳定复现冻结。因此:成本分解(纯 vendor API 直调,
/// 不接触 UIKit/SwiftUI)留在 async 方法里;端到端 harness(pump 驱动的真实
/// `UIHostingController`)放进同步方法。
@MainActor
final class NovelLengthScanPerfBaselineTests: XCTestCase {

    // MARK: - Fixture

    private static let closedParagraphTemplate =
        "第%d段已经闭合，围绕人物动机与章节走向的机制推演展开，本段模拟已经生成完毕的" +
        "正文内容，用于把整章长度撑到目标档位，不参与本次采样的逐 delta 测量。"

    private static let tailContinuationSentence =
        "这是本章正在持续生成的连续正文，没有空行分隔，用来复现真实生成中同一段落" +
        "随后续 token 持续变长的布局压力。"

    private static let tailSentinel = "本章正文续写开始。"

    /// - Returns: `settledPrefix`(多段 `\n\n` 分隔、已闭合的正文)与
    ///   `tail`(单一连续段落,无空行,作为流式增长的起点)。二者拼接后的总长度
    ///   接近 `targetUTF16`,`tail` 独占 `tailBudgetUTF16`。
    private static func chapterFixture(
        targetUTF16: Int,
        tailBudgetUTF16: Int = 1_400
    ) -> (settledPrefix: String, tail: String) {
        var settled = ""
        var index = 0
        let prefixBudget = max(0, targetUTF16 - tailBudgetUTF16)
        while settled.utf16.count < prefixBudget {
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
    /// 与 `NovelSessionBottomDebtProbeTests` 相同的 48ms 节奏(既有 48ms 发布节流窗)。
    private static let deltaInterval: TimeInterval = 0.048
    private static let deltaCount = 30

    // MARK: - Stats helpers

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

    // MARK: - Component 1/2: 解析 + 转换成本(直接调用与 parseNow 相同的 vendor API)

    private struct ComponentTimingResult {
        let blockScanMs: [Double]
        let cmarkParseMs: [Double]
        let convertMs: [Double]
    }

    private func measureComponentCosts(targetUTF16: Int) async throws -> ComponentTimingResult {
        let fixture = Self.chapterFixture(targetUTF16: targetUTF16)
        var tail = fixture.tail
        let config = SwiftStreamingMarkdown.MarkdownRenderConfig.default.withShouldAnimateText(value: true)
        var previousRenderable: SwiftStreamingMarkdown.RenderableDocument?
        var previousParsedText: String?

        var blockScanMs: [Double] = []
        var cmarkParseMs: [Double] = []
        var convertMs: [Double] = []

        for _ in 0..<Self.deltaCount {
            tail += Self.deltaChunk
            let text = Self.fullText(fixture, tail: tail)

            let blockScanStart = CACurrentMediaTime()
            let blocks = ChatStreamingMarkdownBlockParserTestSupport.blocks(
                in: text,
                includeTrailingPartialTableRow: false
            )
            blockScanMs.append((CACurrentMediaTime() - blockScanStart) * 1_000)
            XCTAssertEqual(blocks.count, 1, "prose 夹具不含表格/代码块,app 层应恒定只产出一个 text block")
            let parseTargetText = blocks.first?.text ?? text
            XCTAssertEqual(
                parseTargetText.utf16.count,
                text.utf16.count,
                "确认 app 层 block 未缩小 cmark 的重解析输入规模"
            )

            let parser = SwiftStreamingMarkdown.MarkdownParserImpl()
            let option = SwiftStreamingMarkdown.MarkdownParseOption(speculativeRewrite: true)
            let parseStart = CACurrentMediaTime()
            let parsed = await parser.parse(text: parseTargetText, option: option)
            cmarkParseMs.append((CACurrentMediaTime() - parseStart) * 1_000)

            let convertStart = CACurrentMediaTime()
            var converted = await SwiftStreamingMarkdown.RenderableDocument(document: parsed.document, config: config)
            if let previousRenderable, let previousParsedText,
               parseTargetText.utf16.count >= previousParsedText.utf16.count,
               parseTargetText.hasPrefix(previousParsedText) {
                converted = converted.reusingUnchangedPrefix(from: previousRenderable)
            }
            convertMs.append((CACurrentMediaTime() - convertStart) * 1_000)

            previousRenderable = converted
            previousParsedText = parseTargetText
        }

        return ComponentTimingResult(blockScanMs: blockScanMs, cmarkParseMs: cmarkParseMs, convertMs: convertMs)
    }

    // MARK: - Harness(与 NovelSessionBottomDebtProbeTests 同构的最小复刻)

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
                                polishTransactionStatus: nil,
                                committedChange: nil,
                                askUser: nil,
                                actions: [],
                                onAction: { _ in },
                                onAnswerAskUser: { _, _ in }
                            )
                        }
                    }

                    // 生产结构:活跃尾行(activeRunRows)渲染在 LazyVStack 之外,
                    // 与 NovelSessionView.transcript 一致。
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

        static let historyRowCount = 10
        static let tailMessageID = NovelMessageID()
        static let bottomAnchorID = "novel-length-scan-bottom-anchor"
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

    // MARK: - Component 3: 可见文本真正变化 + Component 4: contentHeight 增量

    @MainActor
    private final class VisibleUpdateProbe: NSObject {
        private weak var rootView: UIView?
        private weak var scrollView: UIScrollView?
        private var displayLink: CADisplayLink?
        private var lastObservedLength: Int?
        /// (timestamp, contentHeight) 采样点,只在段落 attributedText.length **真正变化**
        /// 时记录——对应用户"看到新字出现"的那一刻,而不是每帧都记。
        private(set) var visibleChangeEvents: [(timestamp: CFTimeInterval, contentHeight: CGFloat)] = []

        init(rootView: UIView, scrollView: UIScrollView) {
            self.rootView = rootView
            self.scrollView = scrollView
        }

        func start() {
            let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
            link.add(to: .main, forMode: .common)
            displayLink = link
        }

        func stop() {
            displayLink?.invalidate()
            displayLink = nil
        }

        @objc private func tick(_ displayLink: CADisplayLink) {
            guard let rootView, let scrollView else { return }
            guard let paragraph = Self.streamingParagraph(in: rootView) else { return }
            let length = paragraph.attributedText.length
            if lastObservedLength != length {
                lastObservedLength = length
                visibleChangeEvents.append((
                    timestamp: CACurrentMediaTime(),
                    contentHeight: scrollView.contentSize.height
                ))
            }
        }

        static func streamingParagraph(in view: UIView) -> ParagraphUIView? {
            if let textView = view as? ParagraphUIView,
               textView.text.hasPrefix(NovelLengthScanPerfBaselineTests.tailSentinel) {
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

    // MARK: - Component 5: 主线程排版/测量 + append 快路径命中率(全量端到端)

    private struct EndToEndTierResult {
        let mainThreadLayoutMsPerDelta: [Double]
        let visibleUpdateIntervalsMs: [Double]
        let contentHeightDeltas: [Double]
        let appendHitCount: Int
        let appendFallbackCount: Int
        let missReasonCounts: [String: Int]
        let finalParagraphLength: Int?
    }

    private func runEndToEndTier(targetUTF16: Int) -> EndToEndTierResult {
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

        let fixture = Self.chapterFixture(targetUTF16: targetUTF16)
        var tail = fixture.tail

        let hostFixture = makeFixture()
        defer { hostFixture.tearDown() }
        guard let scrollView = hostFixture.scrollView else {
            XCTFail("Expected the transcript scroll view")
            return EndToEndTierResult(
                mainThreadLayoutMsPerDelta: [], visibleUpdateIntervalsMs: [], contentHeightDeltas: [],
                appendHitCount: 0, appendFallbackCount: 0, missReasonCounts: [:], finalParagraphLength: nil
            )
        }

        hostFixture.model.tailContent = Self.fullText(fixture, tail: tail)
        hostFixture.model.scrollToBottomTrigger += 1
        XCTAssertTrue(
            pumpUntil(timeout: 8.0) { self.distanceToBottom(scrollView) <= 2 },
            "初始入场必须先锚定到底部"
        )
        XCTAssertTrue(pumpUntil(timeout: 8.0) {
            guard let paragraph = VisibleUpdateProbe.streamingParagraph(in: hostFixture.host.view) else { return false }
            return paragraph.usesTextKit1 && paragraph.attributedText.length == tail.utf16.count
        }, "必须先等真实 ParagraphUIView 完成首次解析落地,再开始采样增量")
        pump(seconds: 0.4)

        ParagraphUIViewAppendPathTestHook.reset()
        let probe = VisibleUpdateProbe(rootView: hostFixture.host.view, scrollView: scrollView)
        probe.start()

        var mainThreadLayoutMsPerDelta: [Double] = []
        for _ in 0..<Self.deltaCount {
            tail += Self.deltaChunk
            let start = Self.mainThreadCPUNanos()
            hostFixture.model.tailContent = Self.fullText(fixture, tail: tail)
            pump(seconds: Self.deltaInterval)
            let elapsedNanos = Self.mainThreadCPUNanos() - start
            mainThreadLayoutMsPerDelta.append(Double(elapsedNanos) / 1_000_000)
        }
        pump(seconds: 0.8)
        probe.stop()

        let hitCount = ParagraphUIViewAppendPathTestHook.appendHitCount
        let missCount = ParagraphUIViewAppendPathTestHook.fallbackMissCount
        let missReasons = ParagraphUIViewAppendPathTestHook.missReasonCounts

        var visibleUpdateIntervalsMs: [Double] = []
        var contentHeightDeltas: [Double] = []
        let events = probe.visibleChangeEvents
        for index in 1..<max(events.count, 1) where index < events.count {
            visibleUpdateIntervalsMs.append((events[index].timestamp - events[index - 1].timestamp) * 1_000)
            contentHeightDeltas.append(Double(events[index].contentHeight - events[index - 1].contentHeight))
        }

        let finalParagraph = VisibleUpdateProbe.streamingParagraph(in: hostFixture.host.view)

        return EndToEndTierResult(
            mainThreadLayoutMsPerDelta: mainThreadLayoutMsPerDelta,
            visibleUpdateIntervalsMs: visibleUpdateIntervalsMs,
            contentHeightDeltas: contentHeightDeltas,
            appendHitCount: hitCount,
            appendFallbackCount: missCount,
            missReasonCounts: missReasons,
            finalParagraphLength: finalParagraph?.attributedText.length
        )
    }

    // MARK: - 数据采集入口

    private static let tiers: [(label: String, utf16: Int)] = [
        ("10KB", 10_240),
        ("20KB", 20_480),
        ("40KB", 40_960),
        ("80KB", 81_920)
    ]

    /// 成本分解(cmark 解析 / 转换为 `RenderableDocument`)按 10/20/40/80KB 四档,
    /// 打印 `[LENGTHSCAN-COMPONENTS]` 行。纯 vendor API 直调,不接触 UIKit/
    /// SwiftUI,可以安全放在 `async` test 方法里(见文件头部说明)。
    func testLengthScanComponentCostsAcrossTiers() async throws {
        for tier in Self.tiers {
            let components = try await measureComponentCosts(targetUTF16: tier.utf16)

            let blockScanMedian = Self.median(components.blockScanMs)
            let blockScanP95 = Self.percentile(components.blockScanMs, 0.95)
            let parseMedian = Self.median(components.cmarkParseMs)
            let parseP95 = Self.percentile(components.cmarkParseMs, 0.95)
            let convertMedian = Self.median(components.convertMs)
            let convertP95 = Self.percentile(components.convertMs, 0.95)

            print(
                "[LENGTHSCAN-COMPONENTS] tier=\(tier.label) targetUTF16=\(tier.utf16) " +
                "blockScanMs(median/p95)=\(blockScanMedian)/\(blockScanP95) " +
                "cmarkParseMs(median/p95)=\(parseMedian)/\(parseP95) " +
                "convertMs(median/p95)=\(convertMedian)/\(convertP95)"
            )
        }
    }

    /// 端到端(主线程排版 + append 快路径命中率 + 可见更新间隔 + contentHeight
    /// 增量)按 10/20/40/80KB 四档,打印 `[LENGTHSCAN-E2E]` 行。驱动真实
    /// `UIHostingController` 的 pump harness,必须是同步 test 方法(见文件头部
    /// 说明),否则 `@Published` 更新会在第一帧后被静默冻结。
    ///
    /// 红灯断言(记账,不为修复而调阈值):锁住 80KB 档"可见更新间隔 p95"的当前
    /// 坏状态。阈值取自 2026-07-25 本机两次独立 xcodebuild test 复跑的较小值再
    /// 减去安全边际,保证此刻必红;修复后应转绿并收紧阈值,不应删除或放宽本断言。
    func testLengthScanEndToEndAcrossTiers() throws {
        var visibleIntervalP95ByTier: [String: Double] = [:]

        for tier in Self.tiers {
            let endToEnd = runEndToEndTier(targetUTF16: tier.utf16)

            let layoutMedian = Self.median(endToEnd.mainThreadLayoutMsPerDelta)
            let layoutP95 = Self.percentile(endToEnd.mainThreadLayoutMsPerDelta, 0.95)
            let intervalMedian = Self.median(endToEnd.visibleUpdateIntervalsMs)
            let intervalP95 = Self.percentile(endToEnd.visibleUpdateIntervalsMs, 0.95)
            let intervalMax = endToEnd.visibleUpdateIntervalsMs.max() ?? 0
            let heightDeltaMedian = Self.median(endToEnd.contentHeightDeltas)
            let heightDeltaP95 = Self.percentile(endToEnd.contentHeightDeltas, 0.95)
            let heightDeltaMax = endToEnd.contentHeightDeltas.max() ?? 0
            let totalPublishes = endToEnd.appendHitCount + endToEnd.appendFallbackCount
            let hitRate = totalPublishes > 0 ? Double(endToEnd.appendHitCount) / Double(totalPublishes) : -1

            visibleIntervalP95ByTier[tier.label] = intervalP95

            print(
                "[LENGTHSCAN-E2E] tier=\(tier.label) targetUTF16=\(tier.utf16) " +
                "mainThreadLayoutMs(median/p95)=\(layoutMedian)/\(layoutP95) " +
                "visibleUpdateIntervalMs(median/p95/max)=\(intervalMedian)/\(intervalP95)/\(intervalMax) " +
                "contentHeightDelta(median/p95/max)=\(heightDeltaMedian)/\(heightDeltaP95)/\(heightDeltaMax) " +
                "appendFastPath(hit/fallback/hitRate)=\(endToEnd.appendHitCount)/\(endToEnd.appendFallbackCount)/\(hitRate) " +
                "missReasons=\(endToEnd.missReasonCounts) " +
                "finalParagraphLength=\(endToEnd.finalParagraphLength.map(String.init) ?? "nil")"
            )
        }

        // --- 红灯记账断言(2026-07-25,当前坏状态,取数用,不为修复调整) ---
        //
        // 80KB 档「可见更新间隔 p95」锁在 260ms:2026-07-25 本机两次独立
        // xcodebuild test 复跑的实测值见运行时 [LENGTHSCAN-E2E] 行(本注释不
        // 重复贴出具体读数,避免与未来复跑的真实数字脱节;复跑记录见随批次
        // 提交时的 PR/报告)。260ms 低于两次实测的较小值,保证此刻必红——用户
        // 报告的冻结是「0.5 秒内一个新字都没出现」,260ms 门槛意在盖住这一级别
        // 的坏状态。修复后这里应当转绿并把阈值收紧到新的真实 p95 附近,而不是
        // 删除或继续放宽本断言。
        if let p95 = visibleIntervalP95ByTier["80KB"] {
            // 用 XCTExpectFailure 显式记账,而不是让门禁长期挂红:阈值本身保持
            // 不动(不放宽),修复后本断言转绿会让 XCTExpectFailure 自动报「未按
            // 预期失败」,提醒把阈值收紧到新的真实 p95 并移除这层包裹。
            XCTExpectFailure(
                "已知未修复:长文档下可见更新间隔远超阈值(前缀 O(段落数) 遍历/提交开销,见 NovelPrefixReuseCostExperimentTests)"
            ) {
                XCTAssertLessThanOrEqual(
                    p95,
                    260,
                    "80KB 档可见更新间隔 p95 记账:当前坏状态,应在修复内容发布/渲染链路后收紧此阈值"
                )
            }
        } else {
            XCTFail("未能采集到 80KB 档的可见更新间隔样本")
        }
    }
}
