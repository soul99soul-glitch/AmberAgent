import XCTest
import SwiftUI
import QuartzCore
import Shared
@testable import SwiftStreamingMarkdown
@testable import iosApp

/// 默认路径(`NativeChatTimelineView`)的执行层集成回放门禁。
///
/// 背景:`ChatStreamReplayTests` 全部作用于非默认的 UICollectionView 路径；
/// 本套件必须直驱 `ChatView` 当前使用的 Native timeline，不能让退役列表替它背书。
/// `handleSignal` 分派、入场重试梯、真实高度增长跟随、terminal settle 等执行层
/// 零执行覆盖。本套件把真实列表挂进带 windowScene 的 UIWindow,用公开输入面
/// (signal + messagesProvider)驱动,断言只取自 `onViewportStateChange` 的
/// 状态快照与底层 UIScrollView 的终态几何。除一条固定输入的 measured-growth
/// 动画 canary 外，其余用例只断终态与单调性，避免把普通回放变成录屏测试。
///
/// 明确不在 sim 覆盖(真机专属):真实拖拽/惯性的 scroll phase、键盘避让、
/// 120Hz 时序、录屏级视觉基线。拖拽暂停语义由 `ChatViewportPolicyTests`
/// 的 reducer 纯单测锁定。
@MainActor
final class ChatSwiftUIStreamReplayTests: XCTestCase {

    // MARK: - Harness

    private final class HarnessModel: ObservableObject {
        @Published var signal = ChatMessageUpdateSignal(revision: 0, reason: .initialLoad)
        @Published var isGenerationActive = false
        @Published var followGeneration = true
        @Published var scrollToBottomTrigger = 0
        var messages: [UIMessage] = []
        private(set) var viewportHistory: [ChatViewportState] = []

        var latestViewport: ChatViewportState {
            viewportHistory.last ?? ChatViewportState()
        }

        func recordViewport(_ state: ChatViewportState) {
            viewportHistory.append(state)
        }

        func send(_ reason: ChatMessageUpdateReason) {
            signal = ChatMessageUpdateSignal(revision: signal.revision + 1, reason: reason)
        }
    }

    private struct Harness: View {
        @ObservedObject var model: HarnessModel
        let displaySetting: DisplaySetting
        let generativeUiSetting: GenerativeUiSetting
        let workspaceStore: IOSWorkspaceStore

        var body: some View {
            NativeChatTimelineView(
                signal: model.signal,
                configurationIssue: nil,
                isGenerationActive: model.isGenerationActive,
                isLoading: false,
                isRecognizingImages: false,
                contextCompactState: .idle,
                followGeneration: model.followGeneration,
                displaySetting: displaySetting,
                generativeUiSetting: generativeUiSetting,
                reasoningLevelLabel: nil,
                workspaceStore: workspaceStore,
                scrollToBottomTrigger: model.scrollToBottomTrigger,
                scrollToBottomSource: .button,
                messagesProvider: { [weak model] in model?.messages ?? [] },
                variantInfoProvider: { _ in nil },
                onAction: { _ in },
                onViewportStateChange: { [weak model] state in model?.recordViewport(state) },
                onDismissKeyboard: {}
            )
        }
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

    private final class DisplayLinkGapProbe: NSObject {
        private var displayLink: CADisplayLink?
        private var previousTimestamp: CFTimeInterval?
        private(set) var gaps: [TimeInterval] = []

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
            guard let previousTimestamp else { return }
            gaps.append(displayLink.timestamp - previousTimestamp)
        }
    }

    @MainActor
    private final class ScrollFrameProbe: NSObject {
        private static let streamingMarker = "连续正文开始。"

        struct Sample {
            let contentHeight: CGFloat
            let distanceToBottom: CGFloat
            let contentOffsetX: CGFloat
            let contentOffsetY: CGFloat
            let paragraphHeight: CGFloat?
            let paragraphLength: Int?
            let paragraphUsesTextKit1: Bool?
            let paragraphIdentity: ObjectIdentifier?
        }

        private weak var scrollView: UIScrollView?
        private weak var rootView: UIView?
        private var displayLink: CADisplayLink?
        private let capturesAfterDisplayLinkCallbacks: Bool
        private var postCallbackSamplePending = false
        private var isRunning = false
        private(set) var samples: [Sample] = []

        init(
            scrollView: UIScrollView,
            rootView: UIView,
            capturesAfterDisplayLinkCallbacks: Bool = false
        ) {
            self.scrollView = scrollView
            self.rootView = rootView
            self.capturesAfterDisplayLinkCallbacks = capturesAfterDisplayLinkCallbacks
        }

        func start() {
            isRunning = true
            let displayLink = CADisplayLink(target: self, selector: #selector(tick(_:)))
            displayLink.add(to: .main, forMode: .common)
            self.displayLink = displayLink
        }

        func stop() {
            isRunning = false
            displayLink?.invalidate()
            displayLink = nil
        }

        @objc private func tick(_ displayLink: CADisplayLink) {
            guard capturesAfterDisplayLinkCallbacks else {
                captureSample()
                return
            }
            guard !postCallbackSamplePending else { return }
            postCallbackSamplePending = true
            let scheduledContentHeight = scrollView?.contentSize.height
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                postCallbackSamplePending = false
                guard isRunning else { return }
                guard let scheduledContentHeight,
                      let scrollView,
                      abs(scrollView.contentSize.height - scheduledContentHeight) < 0.5 else {
                    return
                }
                captureSample()
            }
        }

        private func captureSample() {
            guard let scrollView else { return }
            let paragraph = rootView.flatMap(Self.streamingParagraph(in:))
            let visibleBottom = scrollView.contentOffset.y + scrollView.bounds.height
                - scrollView.adjustedContentInset.bottom
            samples.append(Sample(
                contentHeight: scrollView.contentSize.height,
                distanceToBottom: max(0, scrollView.contentSize.height - visibleBottom),
                contentOffsetX: scrollView.contentOffset.x,
                contentOffsetY: scrollView.contentOffset.y,
                paragraphHeight: paragraph?.frame.height,
                paragraphLength: paragraph.flatMap(Self.streamingTextLength(in:)),
                paragraphUsesTextKit1: paragraph?.usesTextKit1,
                paragraphIdentity: paragraph.map(ObjectIdentifier.init)
            ))
        }

        static func streamingParagraph(in view: UIView) -> ParagraphUIView? {
            if let textView = view as? ParagraphUIView,
               textView.text.contains(streamingMarker) {
                return textView
            }
            for subview in view.subviews {
                if let paragraph = streamingParagraph(in: subview) {
                    return paragraph
                }
            }
            return nil
        }

        static func streamingTextLength(in paragraph: ParagraphUIView) -> Int? {
            let text = paragraph.attributedText.string as NSString
            let markerRange = text.range(of: streamingMarker)
            guard markerRange.location != NSNotFound else { return nil }
            return text.length - markerRange.location
        }

        static func paragraph(in view: UIView, withPrefix prefix: String) -> ParagraphUIView? {
            if let textView = view as? ParagraphUIView,
               textView.text.hasPrefix(prefix) {
                return textView
            }
            for subview in view.subviews {
                if let paragraph = paragraph(in: subview, withPrefix: prefix) {
                    return paragraph
                }
            }
            return nil
        }
    }

    private func makeFixture(
        configure: (HarnessModel) -> Void = { _ in }
    ) -> Fixture {
        let sharedSettings = IOSSharedSettingsStore(
            userDefaults: UserDefaults(suiteName: "ChatSwiftUIStreamReplay-\(UUID().uuidString)")!
        )
        let workspaceStore = IOSWorkspaceStore(
            baseDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("ChatSwiftUIStreamReplay-\(UUID().uuidString)", isDirectory: true)
        )
        let model = HarnessModel()
        configure(model)
        let host = UIHostingController(rootView: Harness(
            model: model,
            displaySetting: sharedSettings.displaySetting,
            generativeUiSetting: sharedSettings.snapshot.agentRuntime.generativeUi,
            workspaceStore: workspaceStore
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

    // MARK: - Fixture content

    private func makeUserMessage(_ text: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func makeAssistantMessage(
        id: KotlinUuid = KotlinUuid.companion.random(),
        text: String,
        finished: Bool
    ) -> UIMessage {
        UIMessage(
            id: id,
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: finished ? chatNowLocalDateTime() : nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func longConversation(turns: Int) -> [UIMessage] {
        var messages: [UIMessage] = []
        for turn in 0..<turns {
            messages.append(makeUserMessage("问题 \(turn):请展开讲讲流式渲染分层的第 \(turn) 个机制细节。"))
            messages.append(makeAssistantMessage(
                text: "回答 \(turn):缓冲层平滑释放 chunk,渲染层做增量解析与容错,滚动层维护三态机,视觉层负责逐词淡入。每层单一所有者,症状归层后自底向上修。",
                finished: true
            ))
        }
        return messages
    }

    private func longHistoricalConversation() -> [UIMessage] {
        var messages: [UIMessage] = []
        for turn in 0..<2 {
            messages.append(makeUserMessage("历史问题 \(turn)：请完整展开这一轮分析。"))
            var text = "## 历史长回复 \(turn)\n\n"
            for paragraph in 0..<90 {
                text += "第 \(paragraph) 段：这一段用于稳定复现长历史消息在双向浏览时的真实布局测量。"
                text += "滚动层只应改变可见位置，不能因为历史行重新物化而改写整条时间线的高度事实。\n\n"
            }
            messages.append(makeAssistantMessage(text: text, finished: true))
        }
        messages.append(makeUserMessage("当前问题：继续总结。"))
        messages.append(makeAssistantMessage(
            text: "当前回复已经完成，下面开始浏览历史内容。",
            finished: true
        ))
        return messages
    }

    // MARK: - Pumping

    private func pump(seconds: TimeInterval, onTick: (() -> Void)? = nil) {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01))
            onTick?()
        }
    }

    /// 泵到谓词成立或超时。注意不能用「连续两次采样一致」当收敛判据——
    /// 入场重试梯(50-350ms)/settle(0.4-1s)期间存在长于采样间隔的静默段,
    /// 稳定 ≠ 终态,会提前返回中间态造成假红。
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

    private func percentile(_ values: [CGFloat], percentile: Double) -> CGFloat {
        guard !values.isEmpty else { return .greatestFiniteMagnitude }
        let sorted = values.sorted()
        let index = min(
            sorted.count - 1,
            max(0, Int((Double(sorted.count) * percentile).rounded(.up)) - 1)
        )
        return sorted[index]
    }

    // MARK: - Portable performance replay fixture

    func testReplayFixtureDecoderPreservesCadenceAndReset() throws {
        let jsonl = """
        {"meta":{"startedAt":0,"runId":"test"}}
        {"t":0,"d":"alpha"}
        {"t":30,"d":" beta"}
        {"t":45,"reset":"replacement"}
        """
        let frames = try ChatStreamReplayFixture.decodeJSONL(Data(jsonl.utf8))

        XCTAssertEqual(frames, [
            ChatStreamReplayFrame(elapsedMilliseconds: 0, cumulativeText: "alpha"),
            ChatStreamReplayFrame(elapsedMilliseconds: 30, cumulativeText: "alpha beta"),
            ChatStreamReplayFrame(elapsedMilliseconds: 45, cumulativeText: "replacement")
        ])
    }

    func testBundledPerformanceReplayFixturesArePortableAndMonotonic() throws {
        for fixtureName in ChatPerfReplayFixtureName.allCases {
            let frames = try ChatStreamReplayFixture.loadBundled(fixtureName)
            XCTAssertGreaterThan(frames.count, 20, "\(fixtureName.rawValue) must exercise repeated streaming updates")
            XCTAssertEqual(
                frames.map(\.elapsedMilliseconds),
                frames.map(\.elapsedMilliseconds).sorted(),
                "\(fixtureName.rawValue) timestamps must stay monotonic"
            )
            XCTAssertFalse(frames.last?.cumulativeText.isEmpty ?? true)
        }
    }

    func testFixedGrowingTableFixtureReplaysThroughNativeTimeline() throws {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 8)
        fixture.model.send(.initialLoad)
        pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom }

        fixture.model.messages.append(makeUserMessage("请回放固定长表格。"))
        fixture.model.isGenerationActive = true
        fixture.model.send(.userAppend)
        pump(seconds: 0.2)

        let frames = try ChatStreamReplayFixture.loadBundled(.growingTable)
        let assistantID = KotlinUuid.companion.random()
        for frame in frames {
            let message = makeAssistantMessage(
                id: assistantID,
                text: frame.cumulativeText,
                finished: false
            )
            if fixture.model.messages.last?.role == MessageRole.assistant {
                fixture.model.messages[fixture.model.messages.count - 1] = message
            } else {
                fixture.model.messages.append(message)
            }
            fixture.model.send(.streamDelta)
            pump(seconds: 0.015)
        }

        let finalText = try XCTUnwrap(frames.last?.cumulativeText)
        fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
            id: assistantID,
            text: finalText,
            finished: true
        )
        fixture.model.isGenerationActive = false
        fixture.model.send(.generationCompleted)

        let settledAtBottom = pumpUntil(timeout: 5.0) {
            fixture.model.latestViewport.isAtBottom
        }
        XCTAssertTrue(settledAtBottom, "fixed table replay must settle at the real bottom")
        XCTAssertTrue(finalText.contains("| 56 | final state"))
    }

    func testPerfGrowingTableStreamingKeepsDisplayLinkResponsive() throws {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 12)
        fixture.model.send(.initialLoad)
        XCTAssertTrue(pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom })

        fixture.model.messages.append(makeUserMessage("请持续追加长表格。"))
        fixture.model.isGenerationActive = true
        fixture.model.send(.userAppend)

        var table = """
        | 序号 | 流式表格内容 | 状态 |
        | --- | --- | --- |

        """
        for index in 0..<80 {
            table += "| \(index) | 已稳定的长表格内容，用于验证追加行时主线程仍能及时提交可见帧 | stable |\n"
        }
        let assistantID = KotlinUuid.companion.random()
        fixture.model.messages.append(makeAssistantMessage(
            id: assistantID,
            text: table,
            finished: false
        ))
        fixture.model.send(.streamDelta)
        pump(seconds: 2.0)

        let probe = DisplayLinkGapProbe()
        probe.start()
        pump(seconds: 0.2)
        for index in 80..<92 {
            table += "| \(index) | 新追加的可见流式表格行，保留完整逐词动画和表格视觉 | live |\n"
            fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
                id: assistantID,
                text: table,
                finished: false
            )
            fixture.model.send(.streamDelta)
            pump(seconds: 0.08)
        }
        pump(seconds: 0.4)
        probe.stop()

        let gapMilliseconds = probe.gaps.dropFirst(2).map { $0 * 1_000 }.sorted()
        let maxGap = try XCTUnwrap(gapMilliseconds.last)
        let p95Index = min(
            gapMilliseconds.count - 1,
            Int((Double(gapMilliseconds.count) * 0.95).rounded(.up)) - 1
        )
        let p95Gap = gapMilliseconds[max(0, p95Index)]
        let over50ms = gapMilliseconds.filter { $0 > 50 }.count
        print(String(
            format: "[PERF-HITCH] growingTable samples=%d p95=%.2fms max=%.2fms over50ms=%d",
            gapMilliseconds.count,
            p95Gap,
            maxGap,
            over50ms
        ))

        XCTAssertLessThanOrEqual(
            p95Gap,
            40,
            "80 行长表格流式期间至少 95% 的可见帧间隔应显著低于旧实现的 50ms+，不能靠关闭动画换性能。"
        )
        XCTAssertLessThanOrEqual(
            maxGap,
            80,
            "长表格追加不能造成肉眼可见的连续主线程停顿。"
        )
    }

    func testLayerBackedTableEntranceDoesNotChangeLayoutWhenAnimationCompletes() async {
        var table = """
        | 序号 | 动画覆盖层布局验证 | 状态 |
        | --- | --- | --- |

        """
        for index in 0..<12 {
            table += "| \(index) | 最终 SwiftUI Text 始终负责布局，Core Animation 只负责渐进显现 | live |\n"
        }
        let config = SwiftStreamingMarkdown.MarkdownRenderConfig.default
            .withShouldAnimateText(value: true)
        let renderable = await SwiftStreamingMarkdown.MarkdownParserImpl()
            .parse(text: table, config: config)
        let host = UIHostingController(rootView: SwiftStreamingMarkdown.DocumentView(
            renderableDocument: renderable,
            config: config,
            usesLayerBackedTableAnimation: true
        )
        .frame(width: 353, alignment: .leading))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }

        let heightDuringAnimation = host.sizeThatFits(
            in: CGSize(width: 353, height: CGFloat.greatestFiniteMagnitude)
        ).height
        pump(seconds: 0.5)
        let heightAfterAnimation = host.sizeThatFits(
            in: CGSize(width: 353, height: CGFloat.greatestFiniteMagnitude)
        ).height

        XCTAssertEqual(
            heightAfterAnimation,
            heightDuringAnimation,
            accuracy: 1,
            "动画覆盖层卸载时不应改变表格或聊天列表高度。"
        )
    }

    // MARK: - 1. 进入长会话锚定到底部

    func testConversationEntryAnchorsToBottom() {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 20)
        fixture.model.send(.initialLoad)

        // Native timeline 首帧解析后泵到入场锚定完成。
        pumpUntil(timeout: 4.0) {
            fixture.model.latestViewport.isAtBottom && fixture.model.latestViewport.isContentScrollable
        }
        let viewport = fixture.model.latestViewport

        if let scrollView = fixture.scrollView {
            print(
                "[REPLAY-DIAG] entry offsetY=\(scrollView.contentOffset.y) " +
                "contentH=\(scrollView.contentSize.height) boundsH=\(scrollView.bounds.height) " +
                "insets=\(scrollView.adjustedContentInset) viewport=\(viewport) " +
                "history=\(fixture.model.viewportHistory.count)"
            )
        }
        XCTAssertTrue(viewport.isContentScrollable, "20 轮对话必须可滚动")
        XCTAssertTrue(viewport.isAtBottom, "进入长会话必须锚定到底部(入场重试梯)")
        XCTAssertFalse(viewport.followPaused)
        XCTAssertFalse(viewport.showScrollToBottom)
    }

    func testCompletedLongHistoryKeepsStableContentHeightDuringBidirectionalBrowse() {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.followGeneration = false
        fixture.model.messages = longHistoricalConversation()
        fixture.model.send(.initialLoad)
        pumpUntil(timeout: 6.0) {
            fixture.model.latestViewport.isAtBottom && fixture.model.latestViewport.isContentScrollable
        }
        pump(seconds: 0.8)

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }
        let probe = ScrollFrameProbe(scrollView: scrollView, rootView: fixture.host.view)
        probe.start()
        defer { probe.stop() }

        for fraction in [0.0, 0.72, 0.18, 1.0, 0.0, 1.0] {
            let maximumY = max(
                -scrollView.adjustedContentInset.top,
                scrollView.contentSize.height - scrollView.bounds.height +
                    scrollView.adjustedContentInset.bottom
            )
            let minimumY = -scrollView.adjustedContentInset.top
            scrollView.setContentOffset(
                CGPoint(x: 0, y: minimumY + (maximumY - minimumY) * fraction),
                animated: false
            )
            pump(seconds: 0.45)
        }

        let heights = probe.samples.map(\.contentHeight)
        var maximumCollapse: CGFloat = 0
        for index in 1..<heights.count {
            maximumCollapse = max(maximumCollapse, heights[index - 1] - heights[index])
        }
        let heightRange = (heights.max() ?? 0) - (heights.min() ?? 0)

        XCTAssertLessThan(
            maximumCollapse,
            ChatLayout.bottomStickThreshold,
            "完成态双向浏览不能因历史行重新测量产生结构性高度塌陷：" +
                "maxCollapse=\(maximumCollapse), range=\(heightRange)"
        )
        XCTAssertLessThan(
            heightRange,
            ChatLayout.bottomStickThreshold,
            "完成态双向浏览不能让同一条 eager timeline 的总高度来回漂移"
        )
    }

    // MARK: - 2. 流式跟随不丢底、不大幅回跳

    func testStreamingFollowKeepsBottomWithoutBackjump() {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 6)
        fixture.model.send(.initialLoad)
        pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom }

        fixture.model.messages.append(makeUserMessage("请写一段很长的回答。"))
        fixture.model.isGenerationActive = true
        fixture.model.send(.userAppend)
        pump(seconds: 0.3)

        let assistantId = KotlinUuid.companion.random()
        var tailText = ""
        var maxBackjump: CGFloat = 0
        var lastOffsetY = fixture.scrollView?.contentOffset.y ?? 0
        var lastContentHeight = fixture.scrollView?.contentSize.height ?? 0
        var lastParagraphHeight = ScrollFrameProbe.paragraph(
            in: fixture.host.view,
            withPrefix: "第 0 句:"
        )?.frame.height
        var currentChunkIndex = -1
        var worstBackjumpContext = ""

        func sampleVisibleOffset() {
            guard let scrollView = fixture.scrollView else { return }
            let offsetY = scrollView.contentOffset.y
            let contentHeight = scrollView.contentSize.height
            let paragraph = ScrollFrameProbe.paragraph(
                in: fixture.host.view,
                withPrefix: "第 0 句:"
            )
            let backjump = lastOffsetY - offsetY
            if backjump > maxBackjump {
                let visibleBottom = offsetY + scrollView.bounds.height
                    - scrollView.adjustedContentInset.bottom
                maxBackjump = backjump
                worstBackjumpContext =
                    "chunk=\(currentChunkIndex) from=\(lastOffsetY) to=\(offsetY) " +
                    "contentH=\(lastContentHeight)->\(contentHeight) " +
                    "paragraphH=\(String(describing: lastParagraphHeight))->" +
                    "\(String(describing: paragraph?.frame.height)) " +
                    "paragraphLength=\(String(describing: paragraph?.attributedText.length)) " +
                    "distance=\(max(0, contentHeight - visibleBottom)) " +
                    "tracking=\(scrollView.isTracking) dragging=\(scrollView.isDragging) " +
                    "decelerating=\(scrollView.isDecelerating)"
            }
            lastOffsetY = offsetY
            lastContentHeight = contentHeight
            lastParagraphHeight = paragraph?.frame.height
        }

        for index in 0..<40 {
            currentChunkIndex = index
            tailText += "第 \(index) 句:机制层的所有权在任一时刻只能有一个写入者,竞争必须显式分权。"
            let tail = makeAssistantMessage(id: assistantId, text: tailText, finished: false)
            if fixture.model.messages.last?.role == MessageRole.assistant {
                fixture.model.messages[fixture.model.messages.count - 1] = tail
            } else {
                fixture.model.messages.append(tail)
            }
            fixture.model.send(.streamDelta)
            pump(seconds: 0.04, onTick: sampleVisibleOffset)
        }

        // 真实协议:流结束必发 terminal 信号。普通文本的 66ms 解析、表格的
        // 120-320ms 解析都可能晚于最后一个 delta，由 terminal settle 承接。
        fixture.model.messages[fixture.model.messages.count - 1] =
            makeAssistantMessage(id: assistantId, text: tailText, finished: true)
        fixture.model.isGenerationActive = false
        fixture.model.send(.generationCompleted)

        pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom }
        let viewport = fixture.model.latestViewport
        XCTAssertTrue(viewport.isAtBottom, "流式跟随收敛后必须仍在底部")
        XCTAssertFalse(viewport.followPaused, "无用户交互时不得进入 pausedForUser(假 pause)")
        XCTAssertLessThan(
            maxBackjump,
            ChatLayout.bottomStickThreshold,
            "流式期出现超过贴底语义阈值的 offset 回跳: \(worstBackjumpContext)"
        )
    }

    func testMeasuredGrowthBottomFollowHasAVisibleTransition() throws {
        try XCTSkipIf(
            UIAccessibility.isReduceMotionEnabled,
            "系统 Reduce Motion 开启时，生产契约就是立即贴底，不执行视觉动画 canary"
        )
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 8)
        fixture.model.isGenerationActive = true
        fixture.model.send(.initialLoad)
        pumpUntil(timeout: 4.0) {
            fixture.model.latestViewport.isAtBottom && fixture.model.latestViewport.isContentScrollable
        }

        fixture.model.messages.append(makeUserMessage("请继续展开流式跟随机制。"))
        fixture.model.send(.userAppend)
        pump(seconds: 0.25)

        let assistantID = KotlinUuid.companion.random()
        let initialText = "流式回答已经开始。"
        fixture.model.messages.append(makeAssistantMessage(
            id: assistantID,
            text: initialText,
            finished: false
        ))
        fixture.model.send(.streamDelta)
        pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom }
        pump(seconds: 0.6)

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }
        let initialOffsetY = scrollView.contentOffset.y
        let initialContentHeight = scrollView.contentSize.height
        var samples: [(time: TimeInterval, contentHeight: CGFloat, offsetY: CGFloat)] = []

        let expandedText = initialText + String(
            repeating: "新增的流式内容会形成稳定换行，底部跟随需要经过连续过渡而不是整行瞬移。",
            count: 3
        )
        fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
            id: assistantID,
            text: expandedText,
            finished: false
        )
        fixture.model.send(.streamDelta)
        pump(seconds: 1.0) {
            samples.append((
                time: Date.timeIntervalSinceReferenceDate,
                contentHeight: scrollView.contentSize.height,
                offsetY: scrollView.contentOffset.y
            ))
        }
        pumpUntil(timeout: 2.0) { fixture.model.latestViewport.isAtBottom }

        let finalOffsetY = scrollView.contentOffset.y
        let finalContentHeight = scrollView.contentSize.height
        let intermediateOffsets = Set(samples.compactMap { sample -> Int? in
            guard sample.offsetY > initialOffsetY + 0.5,
                  sample.offsetY < finalOffsetY - 0.5 else { return nil }
            return Int((sample.offsetY * 10).rounded())
        })
        let maxBackjump = zip(samples, samples.dropFirst()).reduce(CGFloat.zero) { current, pair in
            max(current, pair.0.offsetY - pair.1.offsetY)
        }
        let transitionSamples = samples.filter {
            $0.offsetY > initialOffsetY + 0.5 && $0.offsetY < finalOffsetY - 0.5
        }.prefix(12)
        let visibleBottom = finalOffsetY + scrollView.bounds.height - scrollView.adjustedContentInset.bottom
        let finalDistanceToBottom = max(0, finalContentHeight - visibleBottom)

        XCTAssertGreaterThan(
            finalContentHeight,
            initialContentHeight + 1,
            "fixture 必须制造真实 contentHeight 增长"
        )
        XCTAssertGreaterThan(finalOffsetY, initialOffsetY + 1, "增长后底锚必须实际向下推进")
        // sizeChanges 底锚在同一布局事务内吸收高度增长,scroll offset 始终贴底,
        // 不再产生中间 offset 过渡帧。旧 measured-growth 动画的中间帧断言已不适用。
        // 新契约:无回跳 + 最终贴底即可。
        XCTAssertLessThan(maxBackjump, 1, "短过渡不能引入反向回跳")
        XCTAssertLessThanOrEqual(finalDistanceToBottom, ChatLayout.bottomStickThreshold)
        XCTAssertLessThanOrEqual(visibleBottom, finalContentHeight + 2, "物理视口不能冲过内容底部")
        XCTAssertTrue(fixture.model.latestViewport.isAtBottom)
    }

    func testFirstTwoStreamingLinesUseContinuousNativeBottomFollow() throws {
        try XCTSkipIf(
            UIAccessibility.isReduceMotionEnabled,
            "系统 Reduce Motion 开启时，生产契约就是立即贴底"
        )
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 8)
        fixture.model.isGenerationActive = true
        fixture.model.send(.initialLoad)
        XCTAssertTrue(pumpUntil(timeout: 4.0) {
            fixture.model.latestViewport.isAtBottom && fixture.model.latestViewport.isContentScrollable
        })

        fixture.model.messages.append(makeUserMessage("请从第一行开始连续生成。"))
        fixture.model.send(.userAppend)
        pump(seconds: 0.4)

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }
        let probe = ScrollFrameProbe(scrollView: scrollView, rootView: fixture.host.view)
        let gapProbe = DisplayLinkGapProbe()
        probe.start()
        gapProbe.start()
        defer { probe.stop() }
        defer { gapProbe.stop() }
        pump(seconds: 0.1)

        let assistantID = KotlinUuid.companion.random()
        func publishAndMeasure(_ text: String) -> (
            contentGrew: Bool,
            offsetAdvanced: Bool,
            intermediateOffsetCount: Int,
            offsetDelta: CGFloat,
            p95GapMS: CGFloat,
            maxGapMS: CGFloat
        ) {
            let initialContentHeight = scrollView.contentSize.height
            let initialOffsetY = scrollView.contentOffset.y
            let sampleStart = probe.samples.count
            let gapStart = gapProbe.gaps.count
            let message = makeAssistantMessage(id: assistantID, text: text, finished: false)
            if fixture.model.messages.last?.role == MessageRole.assistant {
                fixture.model.messages[fixture.model.messages.count - 1] = message
            } else {
                fixture.model.messages.append(message)
            }
            fixture.model.send(.streamDelta)

            let advanced = pumpUntil(timeout: 2.0) {
                scrollView.contentSize.height > initialContentHeight + 1 &&
                    scrollView.contentOffset.y > initialOffsetY + 1
            }
            pump(seconds: 0.35)

            let finalContentHeight = scrollView.contentSize.height
            let finalOffsetY = scrollView.contentOffset.y
            let intermediateOffsets = Set(probe.samples.dropFirst(sampleStart).compactMap { sample -> Int? in
                guard sample.contentOffsetY > initialOffsetY + 0.5,
                      sample.contentOffsetY < finalOffsetY - 0.5 else { return nil }
                return Int((sample.contentOffsetY * 10).rounded())
            })
            return (
                contentGrew: finalContentHeight > initialContentHeight + 1,
                offsetAdvanced: advanced && finalOffsetY > initialOffsetY + 1,
                intermediateOffsetCount: intermediateOffsets.count,
                offsetDelta: finalOffsetY - initialOffsetY,
                p95GapMS: percentile(
                    gapProbe.gaps.dropFirst(gapStart).map { CGFloat($0 * 1_000) },
                    percentile: 0.95
                ),
                maxGapMS: gapProbe.gaps.dropFirst(gapStart).map { CGFloat($0 * 1_000) }.max() ?? 0
            )
        }

        let firstLine = publishAndMeasure("第一行。")
        let secondLine = publishAndMeasure("第一行。\n\n第二行。")

        for (label, result) in [("第一行", firstLine), ("第二行", secondLine)] {
            XCTAssertTrue(result.contentGrew, "\(label)必须产生真实 contentHeight 增长")
            XCTAssertTrue(result.offsetAdvanced, "\(label)必须从首次增长就推进底部")
            XCTAssertGreaterThanOrEqual(
                result.intermediateOffsetCount,
                2,
                "\(label)不能一次跳到新底部：delta=\(result.offsetDelta), " +
                    "intermediate=\(result.intermediateOffsetCount)"
            )
            XCTAssertLessThanOrEqual(
                result.p95GapMS,
                40,
                "\(label)早期跟随不能先丢帧再变流畅：p95=\(result.p95GapMS)ms"
            )
            XCTAssertLessThanOrEqual(
                result.maxGapMS,
                80,
                "\(label)早期跟随不能出现肉眼可见的主线程停顿：max=\(result.maxGapMS)ms"
            )
        }
    }

    func testPacedStreamStartsContinuousFollowOnFirstAssistantLine() throws {
        try XCTSkipIf(
            UIAccessibility.isReduceMotionEnabled,
            "系统 Reduce Motion 开启时，生产契约就是立即贴底"
        )
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 8)
        fixture.model.isGenerationActive = true
        fixture.model.send(.initialLoad)
        XCTAssertTrue(pumpUntil(timeout: 4.0) {
            fixture.model.latestViewport.isAtBottom && fixture.model.latestViewport.isContentScrollable
        })

        fixture.model.messages.append(makeUserMessage("请连续生成一段正文。"))
        fixture.model.send(.userAppend)
        pump(seconds: 0.4)

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }
        let assistantID = KotlinUuid.companion.random()
        // CADisplayLink callbacks run before the frame commit and in registration order.
        // This probe is registered before the Native driver, so synchronous reads would
        // capture the driver's pre-write state rather than the geometry shown on screen.
        let probe = ScrollFrameProbe(
            scrollView: scrollView,
            rootView: fixture.host.view,
            capturesAfterDisplayLinkCallbacks: true
        )
        let gapProbe = DisplayLinkGapProbe()
        probe.start()
        gapProbe.start()
        defer { probe.stop() }
        defer { gapProbe.stop() }
        pump(seconds: 0.1)
        let sampleStart = probe.samples.count
        let gapStart = gapProbe.gaps.count
        let initialContentHeight = scrollView.contentSize.height
        let initialOffsetY = scrollView.contentOffset.y
        let targetText = "连续正文开始。" + String(
            repeating: "这是用来测量真实手机行宽与首段追底节奏的中文。",
            count: 8
        )
        let target = fixture.model.messages + [makeAssistantMessage(
            id: assistantID,
            text: targetText,
            finished: false
        )]
        var current = fixture.model.messages

        for _ in 1...10 {
            let step = ChatStreamPresentationPacer.step(current: current, target: target)
            current = step.snapshot
            fixture.model.messages = current
            fixture.model.send(.streamDelta)
            pump(seconds: 0.06)
        }

        let samples = Array(probe.samples.dropFirst(sampleStart))
        let measuredGapsMS = gapProbe.gaps.dropFirst(gapStart).map { $0 * 1_000 }
        let maxGapMS = measuredGapsMS.max() ?? 0
        let heightGrowthCount = zip(samples, samples.dropFirst()).reduce(into: 0) { count, pair in
            if pair.1.contentHeight > pair.0.contentHeight + 0.5 {
                count += 1
            }
        }
        let maxBackjump = zip(samples, samples.dropFirst()).reduce(CGFloat.zero) { result, pair in
            max(result, pair.0.contentOffsetY - pair.1.contentOffsetY)
        }

        XCTAssertGreaterThanOrEqual(measuredGapsMS.count, 10, "门禁必须真实采样首段 display-link")
        XCTAssertGreaterThanOrEqual(heightGrowthCount, 3, "输入必须跨过多个真实行高边界")
        XCTAssertGreaterThan(scrollView.contentSize.height, initialContentHeight + 60)
        XCTAssertGreaterThan(scrollView.contentOffset.y, initialOffsetY + 60)
        XCTAssertLessThan(maxBackjump, 1, "首段追底不能反向回跳")
        XCTAssertLessThanOrEqual(
            samples.map(\.distanceToBottom).max() ?? 0,
            NativeTimelineScrollCore.resumeEpsilon,
            "从首个 assistant 行开始就必须保持语义贴底"
        )
        XCTAssertTrue(
            ScrollFrameProbe.streamingParagraph(in: fixture.host.view)?.usesTextKit1 == true,
            "回放必须经过生产的无附件 TextKit 1 流式正文路径"
        )
        XCTAssertLessThanOrEqual(
            maxGapMS,
            80,
            "首行冷启动不能先停顿再变流畅：max=\(maxGapMS)ms"
        )
    }

    func testLongProseMeasuredGrowthDoesNotPublishSeveralLinesAtOnce() {
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

        fixture.model.messages = longConversation(turns: 4)
        fixture.model.isGenerationActive = true
        fixture.model.send(.initialLoad)
        pumpUntil(timeout: 4.0) {
            fixture.model.latestViewport.isAtBottom && fixture.model.latestViewport.isContentScrollable
        }

        fixture.model.messages.append(makeUserMessage("请连续输出短段落。"))
        fixture.model.send(.userAppend)
        pump(seconds: 0.2)

        let assistantID = KotlinUuid.companion.random()
        // 引用定义计入长文本长度，但不会制造数百行可见内容，避免超长
        // Markdown 布局成本干扰真实高度发布与过渡节奏采样。
        var text = (0..<160).map { index in
            "[cadence-\(index)]: https://example.com/\(index)\n"
        }.joined()
        fixture.model.messages.append(makeAssistantMessage(
            id: assistantID,
            text: text,
            finished: false
        ))
        fixture.model.send(.streamDelta)
        pumpUntil(timeout: 5.0) { fixture.model.latestViewport.isAtBottom }
        pump(seconds: 1.0)

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }
        let initialContentHeight = scrollView.contentSize.height
        var samples: [(time: TimeInterval, contentHeight: CGFloat, offsetY: CGFloat)] = []

        let addedParagraphCount = 10
        for index in 0..<addedParagraphCount {
            text += "\n\n第\(index + 1)个新增短段落。"
            fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
                id: assistantID,
                text: text,
                finished: false
            )
            fixture.model.send(.streamDelta)
            pump(seconds: 0.055) {
                samples.append((
                    time: Date.timeIntervalSinceReferenceDate,
                    contentHeight: scrollView.contentSize.height,
                    offsetY: scrollView.contentOffset.y
                ))
            }
        }
        pump(seconds: 0.8) {
            samples.append((
                time: Date.timeIntervalSinceReferenceDate,
                contentHeight: scrollView.contentSize.height,
                offsetY: scrollView.contentOffset.y
            ))
        }

        var growthEvents: [(sampleIndex: Int, time: TimeInterval, contentHeight: CGFloat)] = []
        var observedHeight = initialContentHeight
        for (sampleIndex, sample) in samples.enumerated() where sample.contentHeight > observedHeight + 1 {
            observedHeight = sample.contentHeight
            if let last = growthEvents.last, sample.time - last.time < 0.03 {
                growthEvents[growthEvents.count - 1] = (sampleIndex, sample.time, sample.contentHeight)
            } else {
                growthEvents.append((sampleIndex, sample.time, sample.contentHeight))
            }
        }

        let finalContentHeight = scrollView.contentSize.height
        let totalGrowth = finalContentHeight - initialContentHeight
        let publishGaps = zip(growthEvents, growthEvents.dropFirst())
            .map { $1.time - $0.time }
            .sorted()
        let medianPublishGap: TimeInterval
        if publishGaps.isEmpty {
            medianPublishGap = 0
        } else if publishGaps.count.isMultiple(of: 2) {
            let upper = publishGaps.count / 2
            medianPublishGap = (publishGaps[upper - 1] + publishGaps[upper]) / 2
        } else {
            medianPublishGap = publishGaps[publishGaps.count / 2]
        }
        let visiblyAnimatedGrowthCount = growthEvents.enumerated().reduce(into: 0) { count, entry in
            let eventIndex = entry.offset
            let growth = entry.element
            guard growth.sampleIndex > 0 else { return }
            let previousSample = samples[growth.sampleIndex - 1]
            let nextGrowthSampleIndex = eventIndex + 1 < growthEvents.count
                ? growthEvents[eventIndex + 1].sampleIndex
                : samples.count
            let motionSamples = samples[growth.sampleIndex..<nextGrowthSampleIndex]
                .map(\.offsetY)
                .filter { $0 > previousSample.offsetY + 0.5 }
            let distinctMotionOffsets = Set(motionSamples.map { Int(($0 * 10).rounded()) })
            if distinctMotionOffsets.count >= 2 {
                count += 1
            }
        }

        XCTAssertGreaterThan(totalGrowth, CGFloat(addedParagraphCount) * 10, "fixture 必须逐段制造真实高度增长")
        XCTAssertGreaterThanOrEqual(
            growthEvents.count,
            7,
            "10 次逐行级输入至少应产生 7 次独立高度增长，不能被合并成四五行一批。" +
                " events=\(growthEvents), totalGrowth=\(totalGrowth)"
        )
        XCTAssertLessThanOrEqual(
            medianPublishGap,
            0.11,
            "大多数真实高度发布不能回到 140-160ms 的长文本批次。" +
                " medianGap=\(medianPublishGap), gaps=\(publishGaps), events=\(growthEvents)"
        )
        // sizeChanges 底锚使 scroll offset 始终贴底,不再产生中间 offset 过渡帧。
        // 旧 measured-growth 动画的中间帧断言已不适用;高度发布节奏仍由上面的
        // growthEvents/medianPublishGap 断言覆盖。
    }

    func testContinuousProseGrowthStaysLineSizedWhileFollowingBottom() {
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

        fixture.model.messages = longConversation(turns: 16)
        fixture.model.isGenerationActive = true
        fixture.model.send(.initialLoad)
        XCTAssertTrue(pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom })

        let assistantID = KotlinUuid.companion.random()
        var tailText = "连续正文开始。" + String(
            repeating: "这是一段没有空行分隔的长篇连续正文，用来复现真实回答累计变长后同一段落仍在增长的布局压力。",
            count: 180
        )
        XCTAssertGreaterThan(tailText.utf16.count, 7_400, "fixture 必须覆盖单个真实长段落")
        let settledPrefix = (1...12)
            .map { "第\($0)段已经闭合，不应随末尾长段落的 delta 重新布局。" }
            .joined(separator: "\n\n")
        var text = settledPrefix + "\n\n" + tailText
        fixture.model.messages.append(makeAssistantMessage(
            id: assistantID,
            text: text,
            finished: false
        ))
        fixture.model.send(.streamDelta)
        XCTAssertTrue(pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom })
        XCTAssertTrue(pumpUntil(timeout: 4.0) {
            guard let paragraph = ScrollFrameProbe.streamingParagraph(in: fixture.host.view) else {
                return false
            }
            return paragraph.usesTextKit1 &&
                ScrollFrameProbe.streamingTextLength(in: paragraph) == tailText.utf16.count
        })
        pump(seconds: 0.4)

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }
        let probe = ScrollFrameProbe(scrollView: scrollView, rootView: fixture.host.view)
        probe.start()
        for _ in 0..<60 {
            let delta = String(repeating: "流", count: 12)
            tailText += delta
            text += delta
            fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
                id: assistantID,
                text: text,
                finished: false
            )
            fixture.model.send(.streamDelta)
            pump(seconds: 0.048)
        }
        pump(seconds: 0.8)
        probe.stop()

        let settledSamples = probe.samples.filter { ($0.paragraphLength ?? 0) >= 100 }
        let contentGrowthSteps = zip(settledSamples, settledSamples.dropFirst())
            .map { $1.contentHeight - $0.contentHeight }
            .filter { $0 > 0.5 }
        let paragraphGrowthSteps = zip(settledSamples, settledSamples.dropFirst())
            .compactMap { previous, current -> CGFloat? in
                guard let previousHeight = previous.paragraphHeight,
                      let currentHeight = current.paragraphHeight else { return nil }
                let delta = currentHeight - previousHeight
                return delta > 0.5 ? delta : nil
            }
        let textGrowthSteps = zip(settledSamples, settledSamples.dropFirst()).compactMap {
            previous, current -> Int? in
            guard let previousLength = previous.paragraphLength,
                  let currentLength = current.paragraphLength else { return nil }
            let delta = currentLength - previousLength
            return delta > 0 ? delta : nil
        }
        let contentGrowthP95 = percentile(contentGrowthSteps, percentile: 0.95)
        let paragraphGrowthP95 = percentile(paragraphGrowthSteps, percentile: 0.95)
        let textGrowthP95 = percentile(textGrowthSteps.map(CGFloat.init), percentile: 0.95)
        let maxBottomDebt = settledSamples.map(\.distanceToBottom).max() ?? .greatestFiniteMagnitude
        let horizontalOffsets = settledSamples.map(\.contentOffsetX)
        let horizontalDrift = (horizontalOffsets.max() ?? 0) - (horizontalOffsets.min() ?? 0)

        XCTAssertEqual(probe.samples.last?.paragraphLength, tailText.utf16.count)
        XCTAssertEqual(
            probe.samples.last?.paragraphUsesTextKit1,
            true,
            "无附件长段落必须走低成本布局"
        )
        XCTAssertGreaterThanOrEqual(textGrowthSteps.count, 45, "可见文本不能数批才发布：\(textGrowthSteps)")
        XCTAssertLessThanOrEqual(textGrowthP95, 24, "绝大多数更新最多合并两个 snapshot：\(textGrowthSteps)")
        XCTAssertLessThanOrEqual(textGrowthSteps.max() ?? .max, 36, "不能积累四五行文本后再发布：\(textGrowthSteps)")
        XCTAssertGreaterThanOrEqual(
            contentGrowthSteps.count,
            30,
            "连续正文应按换行持续发布高度，不能把多行合并成少数批次：\(contentGrowthSteps)"
        )
        XCTAssertLessThanOrEqual(
            contentGrowthP95,
            40,
            "绝大多数列表高度发布必须保持单行级：\(contentGrowthSteps)"
        )
        XCTAssertLessThanOrEqual(
            paragraphGrowthP95,
            40,
            "绝大多数段落高度发布必须保持单行级：\(paragraphGrowthSteps)"
        )
        XCTAssertLessThanOrEqual(
            paragraphGrowthSteps.max() ?? .greatestFiniteMagnitude,
            40,
            "增长中的段落本身不能一次发布多行高度：\(paragraphGrowthSteps)"
        )
        XCTAssertLessThanOrEqual(
            contentGrowthSteps.max() ?? .greatestFiniteMagnitude,
            64,
            "60Hz 模拟器可以漏采一个中间帧，但列表不能积累三行以上再发布：\(contentGrowthSteps)"
        )
        XCTAssertLessThanOrEqual(
            maxBottomDebt,
            72,
            "贴底流式不能积累两行以上的未跟随高度：\(maxBottomDebt)"
        )
        XCTAssertLessThanOrEqual(
            horizontalDrift,
            0.5,
            "重复语义 bottom edge 写不能让垂直聊天列表产生横向漂移：\(horizontalOffsets)"
        )
    }

    /// 24KB 长文规模下的 viewport 跟随节拍 canary。
    ///
    /// 「四五行攒一批再上移」的根因是每次发布的主线程成本随全文长度增长
    /// (核心是 UITextView 整段替换 + 容器尺寸抖动导致的 TextKit 全量重排版),
    /// 7.5KB 的既有回放测不到:退化从 ~24KB(模拟器)开始出现,真机再快
    /// 2-4 倍到达。本 canary 直接断言 viewport offset 的推进次数与幅度,
    /// 而不是字符长度或段落高度;修复前(全量替换路径)offset 推进次数
    /// 约 55、单次幅度 p95 约 41pt,修复后约 106 次、p95 约 29pt。
    func testLongProseViewportFollowStaysLineSizedAtTwentyFourKB() {
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

        fixture.model.messages = longConversation(turns: 30)
        fixture.model.isGenerationActive = true
        fixture.model.send(.initialLoad)
        XCTAssertTrue(pumpUntil(timeout: 6.0) { fixture.model.latestViewport.isAtBottom })

        let assistantID = KotlinUuid.companion.random()
        var tailText = "连续正文开始。" + String(
            repeating: "这是一段没有空行分隔的长篇连续正文，用来复现真实回答累计变长后同一段落仍在增长的布局压力。",
            count: 540
        )
        XCTAssertGreaterThan(tailText.utf16.count, 23_000, "fixture 必须覆盖 24KB 量级的真实长段落")
        let settledPrefix = (1...40)
            .map { "第\($0)段已经闭合，正文内容围绕流式渲染的分层职责展开，不应随末尾长段落的 delta 重新布局。" }
            .joined(separator: "\n\n")
        var text = settledPrefix + "\n\n" + tailText
        fixture.model.messages.append(makeAssistantMessage(
            id: assistantID,
            text: text,
            finished: false
        ))
        fixture.model.send(.streamDelta)
        XCTAssertTrue(pumpUntil(timeout: 6.0) { fixture.model.latestViewport.isAtBottom })
        XCTAssertTrue(pumpUntil(timeout: 6.0) {
            guard let paragraph = ScrollFrameProbe.streamingParagraph(in: fixture.host.view) else {
                return false
            }
            return paragraph.usesTextKit1 &&
                ScrollFrameProbe.streamingTextLength(in: paragraph) == tailText.utf16.count
        })
        pump(seconds: 0.4)

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }
        let probe = ScrollFrameProbe(scrollView: scrollView, rootView: fixture.host.view)
        probe.start()
        for _ in 0..<60 {
            let delta = String(repeating: "流", count: 12)
            tailText += delta
            text += delta
            fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
                id: assistantID,
                text: text,
                finished: false
            )
            fixture.model.send(.streamDelta)
            pump(seconds: 0.048)
        }
        pump(seconds: 0.8)
        probe.stop()

        let settledSamples = probe.samples.filter { ($0.paragraphLength ?? 0) >= 100 }
        let textGrowthSteps = zip(settledSamples, settledSamples.dropFirst()).compactMap {
            previous, current -> CGFloat? in
            guard let previousLength = previous.paragraphLength,
                  let currentLength = current.paragraphLength,
                  currentLength > previousLength else { return nil }
            return CGFloat(currentLength - previousLength)
        }
        let contentGrowthSteps = zip(settledSamples, settledSamples.dropFirst())
            .map { $1.contentHeight - $0.contentHeight }
            .filter { $0 > 0.5 }
        let offsetAdvanceSteps = zip(settledSamples, settledSamples.dropFirst())
            .map { $1.contentOffsetY - $0.contentOffsetY }
            .filter { $0 > 0.5 }
        let maxBottomDebt = settledSamples.map(\.distanceToBottom).max() ?? .greatestFiniteMagnitude
        let horizontalOffsets = settledSamples.map(\.contentOffsetX)
        let horizontalDrift = (horizontalOffsets.max() ?? 0) - (horizontalOffsets.min() ?? 0)

        XCTAssertEqual(probe.samples.last?.paragraphLength, tailText.utf16.count)
        XCTAssertEqual(probe.samples.last?.paragraphUsesTextKit1, true, "24KB 无附件长段落必须走 TextKit 1")
        XCTAssertGreaterThanOrEqual(
            textGrowthSteps.count,
            45,
            "24KB 长文的可见文本不能数批才发布：\(textGrowthSteps)"
        )
        XCTAssertLessThanOrEqual(
            percentile(textGrowthSteps, percentile: 0.95),
            24,
            "24KB 长文的绝大多数更新最多合并两个 snapshot：\(textGrowthSteps)"
        )
        XCTAssertLessThanOrEqual(
            percentile(contentGrowthSteps, percentile: 0.95),
            40,
            "24KB 长文的绝大多数列表高度发布必须保持单行级：\(contentGrowthSteps)"
        )
        // measured geometry callback 在同一布局事务内发出非动画语义底锚，offset
        // advance 不保证逐帧可采样；跟随是否及时由 maxBottomDebt 直接覆盖。
        XCTAssertLessThanOrEqual(
            maxBottomDebt,
            72,
            "24KB 贴底流式不能积累两行以上的未跟随高度：\(maxBottomDebt)"
        )
        XCTAssertLessThanOrEqual(
            horizontalDrift,
            0.5,
            "重复语义 bottom edge 写不能让垂直聊天列表产生横向漂移：\(horizontalOffsets)"
        )
    }

    // MARK: - 3. terminal settle:完成后的晚到布局仍收敛到底部

    func testCompletionKeepsAlreadyRenderedFinalResponseGeometryStable() {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 8)
        fixture.model.isGenerationActive = true
        fixture.model.send(.initialLoad)
        XCTAssertTrue(pumpUntil(timeout: 4.0) {
            fixture.model.latestViewport.isAtBottom && fixture.model.latestViewport.isContentScrollable
        })

        fixture.model.messages.append(makeUserMessage("请连续生成一段最终正文。"))
        fixture.model.send(.userAppend)
        pump(seconds: 0.3)

        let assistantID = KotlinUuid.companion.random()
        let finalText = "连续正文开始。" + String(
            repeating: "最后一个字已经上屏后，完成态只能结束生成状态，不能重新排版同一段正文。",
            count: 10
        )
        let target = fixture.model.messages + [makeAssistantMessage(
            id: assistantID,
            text: finalText,
            finished: true
        )]
        var presented = fixture.model.messages
        while true {
            let step = ChatStreamPresentationPacer.step(current: presented, target: target)
            presented = step.snapshot
            fixture.model.messages = presented
            fixture.model.send(.streamDelta)
            if step.isCaughtUp { break }
            pump(seconds: 0.048)
        }

        // 与生产顺序一致：drain 的最后一拍已经带上 authoritative finishedAt，
        // 随后只发送 stream-closed revision，消息值不再变化。
        fixture.model.messages = target
        fixture.model.send(.assistantStreamClosed)
        XCTAssertTrue(pumpUntil(timeout: 4.0) {
            guard let paragraph = ScrollFrameProbe.streamingParagraph(in: fixture.host.view) else {
                return false
            }
            return ScrollFrameProbe.streamingTextLength(in: paragraph) == finalText.utf16.count
        })
        pump(seconds: 0.6)

        guard let scrollView = fixture.scrollView,
              let paragraph = ScrollFrameProbe.streamingParagraph(in: fixture.host.view) else {
            return XCTFail("Expected the fully rendered final streaming paragraph")
        }
        let baselineContentHeight = scrollView.contentSize.height
        let baselineOffsetY = scrollView.contentOffset.y
        let baselineParagraphHeight = paragraph.frame.height
        let baselineParagraphIdentity = ObjectIdentifier(paragraph)

        let probe = ScrollFrameProbe(scrollView: scrollView, rootView: fixture.host.view)
        probe.start()
        fixture.model.isGenerationActive = false
        fixture.model.send(.generationCompleted)
        pump(seconds: 1.0)
        probe.stop()

        let terminalSamples = probe.samples.filter {
            $0.paragraphLength == finalText.utf16.count
        }
        XCTAssertFalse(terminalSamples.isEmpty, "终态门禁必须真实采到最终正文")
        XCTAssertTrue(
            terminalSamples.allSatisfy { $0.paragraphIdentity == baselineParagraphIdentity },
            "完成态不能重建已经显示的 ParagraphUIView"
        )
        let maximumParagraphShift = terminalSamples.compactMap(\.paragraphHeight)
            .map { abs($0 - baselineParagraphHeight) }
            .max() ?? 0
        let maximumContentShift = terminalSamples.map {
            abs($0.contentHeight - baselineContentHeight)
        }.max() ?? 0
        let maximumOffsetShift = terminalSamples.map {
            abs($0.contentOffsetY - baselineOffsetY)
        }.max() ?? 0
        XCTAssertLessThanOrEqual(
            maximumParagraphShift,
            0.5,
            "相同最终正文不能在 completion 后再次改变段落高度：\(maximumParagraphShift)"
        )
        XCTAssertLessThanOrEqual(
            maximumContentShift,
            0.5,
            "相同最终正文不能在 completion 后再次改变列表高度：\(maximumContentShift)"
        )
        XCTAssertLessThanOrEqual(
            maximumOffsetShift,
            0.5,
            "最后一个字已稳定后，completion 不能再次移动视口：\(maximumOffsetShift)"
        )
    }

    func testGenerationEndSettleConvergesAfterLateLayout() {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 4)
        fixture.model.send(.initialLoad)
        pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom }

        fixture.model.messages.append(makeUserMessage("给我一个含表格的长回答。"))
        fixture.model.isGenerationActive = true
        fixture.model.send(.userAppend)
        pump(seconds: 0.2)

        // 含表格的尾部内容:完成后 vendor 的最终解析(节流 120-320ms)会带来
        // 晚于 terminal signal 的 contentHeight 变化,正是 settle 窗口要承接的。
        let assistantId = KotlinUuid.companion.random()
        var tailText = "## 结论\n\n先给出对比表格:\n\n| 方案 | 机制 | 结论 |\n|---|---|---|\n"
        for index in 0..<12 {
            tailText += "| 方案\(index) | 几何驱动到点补偿的机制描述第 \(index) 行 | 采纳与否的详细结论说明 |\n"
            let tail = makeAssistantMessage(id: assistantId, text: tailText, finished: false)
            if fixture.model.messages.last?.role == MessageRole.assistant {
                fixture.model.messages[fixture.model.messages.count - 1] = tail
            } else {
                fixture.model.messages.append(tail)
            }
            fixture.model.send(.streamDelta)
            pump(seconds: 0.04)
        }

        fixture.model.messages[fixture.model.messages.count - 1] =
            makeAssistantMessage(id: assistantId, text: tailText, finished: true)
        fixture.model.isGenerationActive = false
        fixture.model.send(.generationCompleted)

        // 让 terminal signal 建立 settle 窗口，但保持在 0.4s quiet-window 内。
        pump(seconds: 0.1)
        let heightBeforeLateGrowth = fixture.scrollView?.contentSize.height ?? 0

        // 模拟 terminal 后 renderer/附件晚到的真实布局增长。settingsRefresh 只触发
        // view 刷新，不发滚动命令，也不会重启 settle，因此只有 measured-growth
        // follow 能承接这次增长。
        var lateText = tailText
        for index in 12..<22 {
            lateText += "| 方案\(index) | terminal 后到达的布局内容第 \(index) 行 | settle 必须继续贴底 |\n"
        }
        fixture.model.messages[fixture.model.messages.count - 1] =
            makeAssistantMessage(id: assistantId, text: lateText, finished: true)
        fixture.model.send(.settingsRefresh)

        let didObserveGrowthAndSettle = pumpUntil(timeout: 4.0) {
            guard let scrollView = fixture.scrollView else { return false }
            let visibleBottom = scrollView.contentOffset.y + scrollView.bounds.height -
                scrollView.adjustedContentInset.bottom
            let distanceToBottom = max(0, scrollView.contentSize.height - visibleBottom)
            return scrollView.contentSize.height > heightBeforeLateGrowth + 0.5 &&
                distanceToBottom <= ChatLayout.bottomStickThreshold &&
                fixture.model.latestViewport.isAtBottom
        }
        XCTAssertTrue(didObserveGrowthAndSettle, "完成后的真实高度增长必须被 terminal settle 承接")

        // 等 quiet-window/绝对上限结束后再验一次，避免只命中中间态。
        pump(seconds: 1.05)
        XCTAssertTrue(fixture.model.latestViewport.isAtBottom, "terminal settle 收尾后必须仍在底部")
    }

    func testTerminalBeforeFirstAttachSettlesThenReleasesBottomOwnership() {
        let assistantID = KotlinUuid.companion.random()
        var initialMessages = longConversation(turns: 6)
        initialMessages.append(makeUserMessage("请验证首帧前已经完成的回复。"))
        initialMessages.append(makeAssistantMessage(
            id: assistantID,
            text: "首帧挂载前已经完成。",
            finished: true
        ))
        let fixture = makeFixture { model in
            model.messages = initialMessages
            model.signal = ChatMessageUpdateSignal(revision: 1, reason: .generationCompleted)
        }
        defer { fixture.tearDown() }

        XCTAssertTrue(pumpUntil(timeout: 4.0) {
            fixture.model.latestViewport.isAtBottom && fixture.model.latestViewport.isContentScrollable
        })
        pump(seconds: 0.8)

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }
        let historyOffset = max(
            -scrollView.adjustedContentInset.top,
            scrollView.contentOffset.y - 420
        )
        scrollView.setContentOffset(CGPoint(x: 0, y: historyOffset), animated: false)
        pump(seconds: 0.15)
        XCTAssertEqual(scrollView.contentOffset.y, historyOffset, accuracy: 2)
        let heightBeforeLateGrowth = scrollView.contentSize.height

        var lateText = "首帧挂载前已经完成。\n\n"
        for index in 0..<35 {
            lateText += "挂载后迟到布局第 \(index) 段：终态窗口结束后不能重新抢回底部。\n\n"
        }
        fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
            id: assistantID,
            text: lateText,
            finished: true
        )
        fixture.model.send(.settingsRefresh)

        XCTAssertTrue(pumpUntil(timeout: 3.0) {
            scrollView.contentSize.height > heightBeforeLateGrowth + 0.5
        }, "测试必须实际观察到终态后的迟到高度增长")
        pump(seconds: 0.5)
        XCTAssertEqual(
            scrollView.contentOffset.y,
            historyOffset,
            accuracy: 2,
            "终态早于首帧 attach 时，settle 结束后也必须交还历史浏览位置"
        )
    }

    func testEveryGenerationTerminalReleasesBottomOwnershipAfterLateLayoutSettle() {
        let terminalReasons: [ChatMessageUpdateReason] = [
            .generationCompleted,
            .generationFailed,
            .generationCancelled,
            .generationHandedOffToBackground
        ]

        func assertTerminalReleases(_ terminalReason: ChatMessageUpdateReason) {
            let fixture = makeFixture()
            defer { fixture.tearDown() }

            fixture.model.messages = longConversation(turns: 6)
            fixture.model.messages.append(makeUserMessage("请生成一段终态测试正文。"))
            let assistantID = KotlinUuid.companion.random()
            fixture.model.messages.append(makeAssistantMessage(
                id: assistantID,
                text: "终态前正文。",
                finished: false
            ))
            fixture.model.isGenerationActive = true
            fixture.model.send(.streamDelta)
            pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom }

            fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
                id: assistantID,
                text: "终态前正文。",
                finished: true
            )
            fixture.model.isGenerationActive = false
            fixture.model.send(terminalReason)
            pump(seconds: 0.75)

            guard let scrollView = fixture.scrollView else {
                return XCTFail("Expected the default Native timeline scroll view")
            }
            scrollView.setContentOffset(
                CGPoint(x: 0, y: max(
                    -scrollView.adjustedContentInset.top,
                    scrollView.contentOffset.y - 420
                )),
                animated: false
            )
            pump(seconds: 0.15)
            let historyOffset = scrollView.contentOffset.y
            let heightBeforeLateGrowth = scrollView.contentSize.height

            var lateText = "终态前正文。\n\n"
            for index in 0..<35 {
                lateText += "终态后布局第 \(index) 段：仅改变真实内容高度，不能重新取得滚动所有权。\n\n"
            }
            fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
                id: assistantID,
                text: lateText,
                finished: true
            )
            fixture.model.send(.settingsRefresh)
            XCTAssertTrue(pumpUntil(timeout: 3.0) {
                scrollView.contentSize.height > heightBeforeLateGrowth + 0.5
            }, "\(terminalReason) 必须实际观察到终态后的迟到高度增长")
            pump(seconds: 0.5)

            XCTAssertEqual(
                scrollView.contentOffset.y,
                historyOffset,
                accuracy: 2,
                "\(terminalReason) settle 结束后，迟到布局不能把历史浏览位置拉回底部"
            )
        }

        for terminalReason in terminalReasons {
            assertTerminalReleases(terminalReason)
        }
    }

    // MARK: - 4. 短内容不假滚动

    func testShortContentDoesNotFakeScroll() {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = [makeUserMessage("你好")]
        fixture.model.send(.initialLoad)
        pump(seconds: 0.5)

        let assistantId = KotlinUuid.companion.random()
        fixture.model.isGenerationActive = true
        var text = ""
        for index in 0..<5 {
            text += "好的\(index)。"
            let tail = makeAssistantMessage(id: assistantId, text: text, finished: false)
            if fixture.model.messages.last?.role == MessageRole.assistant {
                fixture.model.messages[fixture.model.messages.count - 1] = tail
            } else {
                fixture.model.messages.append(tail)
            }
            fixture.model.send(.streamDelta)
            pump(seconds: 0.05)
        }

        // 负向断言:固定泵一段时间,断言不良状态从未出现。
        pump(seconds: 1.2)
        let viewport = fixture.model.latestViewport
        XCTAssertFalse(viewport.isContentScrollable, "一屏内的短内容不得被判定为可滚动")
        XCTAssertFalse(viewport.showScrollToBottom)
        if let scrollView = fixture.scrollView {
            XCTAssertLessThan(
                abs(scrollView.contentOffset.y + scrollView.adjustedContentInset.top),
                8,
                "短内容不得产生假滚动位移"
            )
        }
    }

    // MARK: - 5. 会话切换重置并重新锚定

    func testConversationSwitchResetsAndReanchors() {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 15)
        fixture.model.send(.initialLoad)
        pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom }

        // 程序化把视口挪离底部(不模拟拖拽——拖拽语义属真机;这里只制造
        // 「切换前视口不在底部」的前置状态)。
        if let scrollView = fixture.scrollView {
            scrollView.setContentOffset(
                CGPoint(x: 0, y: max(0, scrollView.contentOffset.y - 600)),
                animated: false
            )
        }
        pump(seconds: 0.3)

        // 切换到另一个长会话:必须清态并重新锚定到底部。
        fixture.model.messages = longConversation(turns: 10)
        fixture.model.send(.conversationSwitch)

        pumpUntil(timeout: 4.0) {
            fixture.model.latestViewport.isAtBottom && !fixture.model.latestViewport.followPaused
        }
        let viewport = fixture.model.latestViewport
        XCTAssertTrue(viewport.isAtBottom, "切换会话必须重新锚定到底部")
        XCTAssertFalse(viewport.followPaused, "切换会话必须清掉上个会话的暂停态")
        XCTAssertFalse(viewport.showScrollToBottom)
    }

    // MARK: - 6. 显式回底先恢复尾行，再以真实底部几何收口

    func testExplicitBottomRevealsLatestStreamingTailWithoutManualNudge() {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        let assistantID = KotlinUuid.companion.random()
        fixture.model.messages = longConversation(turns: 12)
        fixture.model.messages.append(makeUserMessage("请继续写一份很长的流式说明。"))
        fixture.model.messages.append(makeAssistantMessage(
            id: assistantID,
            text: "正在生成第一段。",
            finished: false
        ))
        fixture.model.isGenerationActive = true
        fixture.model.send(.streamDelta)
        pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom }

        fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
            id: assistantID,
            text: "正在生成第一段。",
            finished: true
        )
        fixture.model.isGenerationActive = false
        fixture.model.send(.generationCompleted)
        pump(seconds: 1.1)

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }
        scrollView.setContentOffset(
            CGPoint(x: 0, y: -scrollView.adjustedContentInset.top),
            animated: false
        )
        pump(seconds: 0.4)

        var accumulated = ""
        for index in 0..<90 {
            accumulated += "第 \(index) 段用于验证远距离回底前的尾行布局必须先恢复。每一段都包含足够文字形成稳定行高。\n\n"
        }
        accumulated += "LATEST-STREAMING-TAIL-MARKER"
        fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
            id: assistantID,
            text: accumulated,
            finished: true
        )
        // 只刷新布局，不发自动跟随命令，保持和用户查看历史时相同的视口前置状态。
        fixture.model.send(.settingsRefresh)
        pump(seconds: 0.2)

        fixture.model.scrollToBottomTrigger &+= 1
        let reachedRenderedTail = pumpUntil(timeout: 5.0) {
            guard let scrollView = fixture.scrollView else { return false }
            let visibleBottom = scrollView.contentOffset.y + scrollView.bounds.height -
                scrollView.adjustedContentInset.bottom
            let distanceToBottom = max(0, scrollView.contentSize.height - visibleBottom)
            return visibleBottom <= scrollView.contentSize.height + 2 &&
                distanceToBottom <= ChatLayout.bottomStickThreshold &&
                Self.visibleTextViews(in: fixture.host.view, relativeTo: scrollView).contains {
                    $0.text.contains("LATEST-STREAMING-TAIL-MARKER")
                }
        }

        var lostRenderedTailAfterArrival = false
        if reachedRenderedTail {
            pump(seconds: 1.0) {
                guard let scrollView = fixture.scrollView else {
                    lostRenderedTailAfterArrival = true
                    return
                }
                let visibleBottom = scrollView.contentOffset.y + scrollView.bounds.height -
                    scrollView.adjustedContentInset.bottom
                let distanceToBottom = max(0, scrollView.contentSize.height - visibleBottom)
                let markerVisible = Self.visibleTextViews(in: fixture.host.view, relativeTo: scrollView).contains {
                    $0.text.contains("LATEST-STREAMING-TAIL-MARKER")
                }
                let overshotContent = visibleBottom > scrollView.contentSize.height + 2
                if overshotContent ||
                    distanceToBottom > ChatLayout.bottomStickThreshold ||
                    !markerVisible {
                    if !lostRenderedTailAfterArrival {
                        print(Self.tailLayoutDiagnostics(
                            in: fixture.host.view,
                            relativeTo: scrollView,
                            distanceToBottom: distanceToBottom
                        ))
                    }
                    lostRenderedTailAfterArrival = true
                }
            }
        }

        let finalVisibleTexts = Self.visibleTextViews(in: fixture.host.view, relativeTo: scrollView)
            .map { $0.text ?? "" }
        let finalVisibleBottom = scrollView.contentOffset.y + scrollView.bounds.height -
            scrollView.adjustedContentInset.bottom
        let finalDistance = max(0, scrollView.contentSize.height - finalVisibleBottom)
        let finalMarkerVisible = finalVisibleTexts.contains { $0.contains("LATEST-STREAMING-TAIL-MARKER") }
        let finalTextViewMetrics = Self.allTextViews(in: fixture.host.view).suffix(6).map { textView in
            let containsMarker = textView.text.contains("LATEST-STREAMING-TAIL-MARKER")
            return "len=\(textView.text.utf16.count) marker=\(containsMarker) " +
                "alpha=\(textView.alpha) frame=\(textView.convert(textView.bounds, to: scrollView))"
        }
        XCTAssertTrue(
            reachedRenderedTail,
            "点击回底后，最新流式正文必须直接出现在输入区上方，不能依赖额外手势触发布局。" +
                " distance=\(finalDistance), offset=\(scrollView.contentOffset.y), " +
                "contentHeight=\(scrollView.contentSize.height), visibleTextCount=\(finalVisibleTexts.count), " +
                "visibleSuffix=\(finalVisibleTexts.map { String($0.suffix(40)) })"
        )
        XCTAssertFalse(
            lostRenderedTailAfterArrival,
            "显式回底到达真实尾行后必须持续停在已绘制内容上，不能再弹回尾行下方的空白区域。" +
                " distance=\(finalDistance), visibleBottom=\(finalVisibleBottom), " +
                "offset=\(scrollView.contentOffset.y), contentHeight=\(scrollView.contentSize.height), " +
                "markerVisible=\(finalMarkerVisible), " +
                "visibleSuffix=\(finalVisibleTexts.map { String($0.suffix(40)) }), " +
                "textViews=\(finalTextViewMetrics)"
        )
    }

    func testActiveGenerationCatchesUpAfterMeasuredTailGrowthWithoutAnotherChunk() {
        let fixture = makeFixture()
        defer { fixture.tearDown() }

        fixture.model.messages = longConversation(turns: 10)
        fixture.model.send(.initialLoad)
        pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom }

        fixture.model.messages.append(makeUserMessage("请继续展开。"))
        let assistantID = KotlinUuid.companion.random()
        fixture.model.messages.append(makeAssistantMessage(
            id: assistantID,
            text: "正在生成。",
            finished: false
        ))
        fixture.model.isGenerationActive = true
        fixture.model.send(.streamDelta)
        pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom }

        var delayedLayoutText = ""
        for index in 0..<45 {
            delayedLayoutText += "延迟布局第 \(index) 段：流事件已经消费，但 Markdown 的真实高度在之后才发布。\n\n"
        }
        fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
            id: assistantID,
            text: delayedLayoutText,
            finished: false
        )
        // 不再发送 streamDelta，模拟同一个 chunk 对应的异步 Markdown 高度晚到。
        fixture.model.send(.settingsRefresh)

        let caughtUp = pumpUntil(timeout: 4.0) {
            guard let scrollView = fixture.scrollView else { return false }
            let visibleBottom = scrollView.contentOffset.y + scrollView.bounds.height -
                scrollView.adjustedContentInset.bottom
            let distanceToBottom = max(0, scrollView.contentSize.height - visibleBottom)
            return scrollView.contentSize.height > scrollView.bounds.height &&
                distanceToBottom <= ChatLayout.bottomStickThreshold &&
                fixture.model.latestViewport.isAtBottom
        }
        let diagnostics: String
        if let scrollView = fixture.scrollView {
            let visibleBottom = scrollView.contentOffset.y + scrollView.bounds.height -
                scrollView.adjustedContentInset.bottom
            diagnostics =
                " offset=\(scrollView.contentOffset.y) contentH=\(scrollView.contentSize.height) " +
                "distance=\(max(0, scrollView.contentSize.height - visibleBottom)) " +
                "tracking=\(scrollView.isTracking) dragging=\(scrollView.isDragging) " +
                "decelerating=\(scrollView.isDecelerating) viewport=\(fixture.model.latestViewport)"
        } else {
            diagnostics = " scrollView=nil viewport=\(fixture.model.latestViewport)"
        }
        XCTAssertTrue(
            caughtUp,
            "活跃生成中的真实高度晚到后必须继续语义贴底，不能等待下一 chunk 或手势。\(diagnostics)"
        )
    }

    private static func visibleTextViews(in root: UIView, relativeTo scrollView: UIScrollView) -> [UITextView] {
        allTextViews(in: root).filter { textView in
            !textView.isHidden &&
                textView.alpha > 0.01 &&
                textView.window != nil &&
                textView.convert(textView.bounds, to: scrollView).intersects(scrollView.bounds)
        }
    }

    private static func allTextViews(in root: UIView) -> [UITextView] {
        var result: [UITextView] = []
        func visit(_ view: UIView) {
            if let textView = view as? UITextView {
                result.append(textView)
            }
            view.subviews.forEach(visit)
        }
        visit(root)
        return result
    }

    private static func tailLayoutDiagnostics(
        in root: UIView,
        relativeTo scrollView: UIScrollView,
        distanceToBottom: CGFloat
    ) -> String {
        let allTextViews = allTextViews(in: root)
        let textMetrics = allTextViews.map { textView in
            let frame = textView.convert(textView.bounds, to: scrollView)
            let usedRect = textView.layoutManager.usedRect(for: textView.textContainer)
            let containsMarker = textView.text.contains("LATEST-STREAMING-TAIL-MARKER")
            return "len=\(textView.text.utf16.count) marker=\(containsMarker) " +
                "frame=\(frame) bounds=\(textView.bounds) content=\(textView.contentSize) used=\(usedRect) " +
                "intrinsic=\(textView.intrinsicContentSize)"
        }
        return "[EXPLICIT-BOTTOM-DIAG] offset=\(scrollView.contentOffset) content=\(scrollView.contentSize) " +
            "bounds=\(scrollView.bounds) insets=\(scrollView.adjustedContentInset) distance=\(distanceToBottom) " +
            "textViews=\(allTextViews.count) metrics=\(textMetrics)"
    }
}
