import XCTest
import SwiftUI
import Shared
@testable import SwiftStreamingMarkdown
@testable import iosApp

/// 探针 B(app 层):默认 `NativeChatTimelineView` 路径下,
/// 长 CJK 前缀(≥2KB)+ 流式中段落回溯定型为表格 + 继续散文增长的结构性回放。
///
/// 对照 vendor 层探针(SwiftStreamingMarkdown 的
/// `StreamingBlockRetypingProbeTests`):同一种"段落 → 表格"回溯定型序列,
/// 这里换到真实 `NativeChatTimelineView`(含生产投影、真实
/// `MessageBubbleView.parseNow` 节流/缓存)上采样 `UIScrollView` 的
/// contentSize/offset,检验嫌疑 1(块级回溯定型)与嫌疑 2(测量瞬态帧)
/// 是否在这一层留下可测的滚动症状。红/绿同样是有效产出，不为了转绿调整
/// 断言口径或改动生产代码。
///
/// `ChatSwiftUIStreamReplayTests` 里的 Harness/Fixture/pump 是该文件私有
/// 类型,跨文件不可见,这里按需重建同构的最小子集,不修改原文件。
@MainActor
final class ChatStructuralRetypeReplayTests: XCTestCase {

    // MARK: - Harness(与 ChatSwiftUIStreamReplayTests 同构的最小子集)

    private final class HarnessModel: ObservableObject {
        @Published var signal = ChatMessageUpdateSignal(revision: 0, reason: .initialLoad)
        @Published var isGenerationActive = false
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
                followGeneration: true,
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

    /// `seedMessages`/`seedGenerationActive` populate the model *before* the
    /// hosting controller's first `layoutIfNeeded()`. That first layout pass is
    /// the one moment SwiftUI is forced to build the tree synchronously (no
    /// prior render to diff against) — any `.task(id:)` created while building
    /// it cannot have run yet, because a freshly created `Task` never resumes
    /// within its own creation call frame. That gives a race-free "before the
    /// async parse has had any chance to run" snapshot without guessing at a
    /// pump duration.
    private func makeFixture(seedMessages: [UIMessage] = [], seedGenerationActive: Bool = false) -> Fixture {
        let sharedSettings = IOSSharedSettingsStore(
            userDefaults: UserDefaults(suiteName: "ChatStructuralRetypeReplay-\(UUID().uuidString)")!
        )
        let workspaceStore = IOSWorkspaceStore(
            baseDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("ChatStructuralRetypeReplay-\(UUID().uuidString)", isDirectory: true)
        )
        let model = HarnessModel()
        model.messages = seedMessages
        model.isGenerationActive = seedGenerationActive
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

    // MARK: - Pumping

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

    private static func allParagraphUIViews(in root: UIView) -> [ParagraphUIView] {
        var result: [ParagraphUIView] = []
        func visit(_ view: UIView) {
            if let paragraph = view as? ParagraphUIView {
                result.append(paragraph)
            }
            view.subviews.forEach(visit)
        }
        visit(root)
        return result
    }

    /// 用"连续若干次采样身份集合不再变化"代替固定 sleep,确定性等待
    /// `ChatStableStreamingMarkdownController.scheduleParse` 的异步链
    /// (`.task(id:)` → `Task.detached` 解析 → 主 actor 发布)收敛到最终态,
    /// 不依赖猜测的 pump 时长。
    private func quiescedParagraphViewSet(
        in root: UIView,
        timeout: TimeInterval = 3.0,
        stableTicks: Int = 5,
        tick: TimeInterval = 0.02
    ) -> Set<ObjectIdentifier> {
        var previous: Set<ObjectIdentifier>?
        var stableCount = 0
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            pump(seconds: tick)
            let current = Set(Self.allParagraphUIViews(in: root).map(ObjectIdentifier.init))
            if let previous, previous == current {
                stableCount += 1
                if stableCount >= stableTicks { return current }
            } else {
                stableCount = 0
            }
            previous = current
        }
        return previous ?? []
    }

    // MARK: - 占位 → 解析落地时 Native 几何连续

    func testPlaceholderToParsedFirstLandingKeepsNativeGeometryContinuous() throws {
        ChatStableStreamingMarkdownCacheTestSupport.reset()

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

        let prefixParagraphs = (1...60).map {
            "第\($0)段已经闭合，正文内容围绕流式渲染的分层职责展开，本用例只验证占位到解析落地的首次交接，不含表格回溯定型。"
        }
        let settledPrefix = prefixParagraphs.joined(separator: "\n\n")
        XCTAssertGreaterThanOrEqual(settledPrefix.utf16.count, 2_048, "fixture 必须覆盖 ≥2KB 的已结算 CJK 前缀")

        let assistantID = KotlinUuid.companion.random()
        let seedMessages = [
            makeUserMessage("先讲清楚背景，再给出一组对比数据。"),
            makeAssistantMessage(id: assistantID, text: settledPrefix, finished: false)
        ]

        let fixture = makeFixture(seedMessages: seedMessages, seedGenerationActive: true)
        defer { fixture.tearDown() }

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }

        let heightBeforeParseLands = scrollView.contentSize.height
        let offsetBeforeParseLands = scrollView.contentOffset.y

        let afterParseLands = quiescedParagraphViewSet(in: fixture.host.view)
        let heightAfterParseLands = scrollView.contentSize.height
        let offsetAfterParseLands = scrollView.contentOffset.y
        let heightDelta = heightAfterParseLands - heightBeforeParseLands

        XCTAssertFalse(afterParseLands.isEmpty, "解析落地后必须存在可见 Markdown 正文")
        XCTAssertLessThanOrEqual(
            abs(heightDelta),
            ChatLayout.bottomStickThreshold,
            "占位→解析落地不能产生可感知的 contentHeight 跳变：\(heightDelta)"
        )
        XCTAssertGreaterThanOrEqual(
            offsetAfterParseLands,
            offsetBeforeParseLands - ChatLayout.bottomStickThreshold,
            "占位→解析落地不能让 Native timeline 反向跳动"
        )
    }

    // MARK: - 探针 B:长 CJK 前缀 + 段落回溯定型为表格 + 继续散文增长

    func testLongCJKPrefixParagraphToTableRetypingHasNoStructuralScrollOscillation() throws {
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
        fixture.model.send(.initialLoad)
        XCTAssertTrue(pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom })

        fixture.model.messages.append(makeUserMessage("先讲清楚背景，再给出一组对比数据。"))
        fixture.model.isGenerationActive = true
        fixture.model.send(.userAppend)
        pump(seconds: 0.2)

        let assistantID = KotlinUuid.companion.random()

        // ≥2KB 的已结算 CJK 前缀:与流式尾部同属一条不断增长的 assistant 消息,
        // 用来验证表格回溯定型不会牵连前面已经上屏的段落。
        let prefixParagraphs = (1...60).map {
            "第\($0)段已经闭合，正文内容围绕流式渲染的分层职责展开，不应随中段的表格回溯定型重新布局。"
        }
        let settledPrefix = prefixParagraphs.joined(separator: "\n\n")
        XCTAssertGreaterThanOrEqual(settledPrefix.utf16.count, 2_048, "fixture 必须覆盖 ≥2KB 的已结算 CJK 前缀")

        // 与 vendor 探针 A(StreamingBlockRetypingProbeTests)同构的字符级增量:
        // 半截表格先长得像普通段落(以 "|" 起头),分隔符行补齐的瞬间才回溯定型为
        // 真实表格,随后继续散文增长。
        let deltas = [
            settledPrefix,
            "\n\n先看这组关键数据的对比：",
            "\n\n| 月份 ",
            "| 数据 |",
            "\n| --",
            "--",
            " | ---- |",
            "\n| 一月",
            " | 100 |",
            "\n| 二月",
            " | 205 |",
            "\n\n从上表可以看出",
            "，整体呈现稳步上升的趋势，",
            "后续几个月预计还会持续增长，",
            "团队计划据此调整下个季度的资源分配安排。"
        ]

        guard let scrollView = fixture.scrollView else {
            return XCTFail("Expected the default Native timeline scroll view")
        }

        var text = ""
        var heightSamples: [CGFloat] = []
        var offsetSamples: [CGFloat] = []

        for delta in deltas {
            text += delta
            let message = makeAssistantMessage(id: assistantID, text: text, finished: false)
            if fixture.model.messages.last?.role == MessageRole.assistant {
                fixture.model.messages[fixture.model.messages.count - 1] = message
            } else {
                fixture.model.messages.append(message)
            }
            fixture.model.send(.streamDelta)
            // 每条 delta 之间留出比默认 48ms 快照门稍宽的时间,降低真实
            // parseNow 节流/单飞把多条 delta 合并成一次发布、从而错过定型
            // 瞬间采样窗口的概率。
            pump(seconds: 0.08)
            heightSamples.append(scrollView.contentSize.height)
            offsetSamples.append(scrollView.contentOffset.y)
        }

        fixture.model.messages[fixture.model.messages.count - 1] = makeAssistantMessage(
            id: assistantID,
            text: text,
            finished: true
        )
        fixture.model.isGenerationActive = false
        fixture.model.send(.generationCompleted)
        XCTAssertTrue(pumpUntil(timeout: 4.0) { fixture.model.latestViewport.isAtBottom })

        print("[probeB-heights] \(heightSamples)")
        print("[probeB-offsets] \(offsetSamples)")

        var maxHeightCollapse: CGFloat = 0
        for index in 1..<heightSamples.count {
            maxHeightCollapse = max(
                maxHeightCollapse,
                heightSamples[index - 1] - heightSamples[index]
            )
        }
        XCTAssertLessThan(
            maxHeightCollapse,
            ChatLayout.bottomStickThreshold,
            "表格回溯定型不能产生可感知的 contentHeight 塌陷：\(heightSamples)"
        )

        // --- 断言 2:offset 无反向回跳超过既有语义阈值(与 maxBackjump 口径一致)---
        var maxBackjump: CGFloat = 0
        for index in 1..<offsetSamples.count {
            maxBackjump = max(maxBackjump, offsetSamples[index - 1] - offsetSamples[index])
        }
        print("[probeB-maxBackjump] \(maxBackjump)")
        XCTAssertLessThan(
            maxBackjump,
            ChatLayout.bottomStickThreshold,
            "表格回溯定型期间出现超过贴底语义阈值的 offset 回跳"
        )
    }
}
