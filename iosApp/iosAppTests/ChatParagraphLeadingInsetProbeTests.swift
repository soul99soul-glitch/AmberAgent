import XCTest
import SwiftUI
import UIKit
import Shared
@testable import SwiftStreamingMarkdown
@testable import iosApp

/// 真机视觉回归（2026-08-08，iPhone Air / iPhone 17 Pro）：assistant 段落文本
/// 在聊天列里左缘贴边/被裁 —— 段落 leading ≈ 4pt，第三行左缘出现半个被裁的字符；
/// 卡片/胶囊行边距正常。触发物：长 CJK 无断句连续文本（如「"侠皇帝"的丰富历史
/// 形象……」段落）。
///
/// 生产路径（NativeChatTimelineView，ChatView.swift:787）：SwiftUI ScrollView +
/// VStack(alignment: .leading, spacing: 14) + `.padding(.horizontal, 16)`，行容器
/// `NativeTimelineMessageBubble`（.frame(maxWidth: .infinity, alignment: .topLeading)）
/// → `MessageBubbleView` → `ChatAssistantMarkdownView` → vendor block 渲染器
/// （SwiftStreamingMarkdown）→ `ParagraphUIView`（TextKit 1）。
///
/// 本探针直接驱动 `NativeChatTimelineView`，seed 长 CJK 消息（先流式后完成，
/// 与生产 latch 一致），然后逐像素核对：
///   1. ParagraphUIView 的 window 坐标 frame 左缘 == 16pt（列 padding 不被吃）；
///   2. TextKit1 全部 lineFragment 的最小 x（墨迹最左缘）== 16pt ± 1（文本不被左裁）；
///   3. 段落视图右缘不出列右缘（无双侧裁切）。
@MainActor
final class ChatParagraphLeadingInsetProbeTests: XCTestCase {

    private static let screenSize = CGSize(width: 402, height: 874)
    private static let pngDir = "/private/tmp/amber-cjk-probe"
    /// 生产 timeline 的 VStack 水平 padding（NativeChatTimelineView body）。
    private static let timelineInset: CGFloat = 16

    /// 长 CJK 无断句段落：引号开头 + 一长串连续汉字（无标点断句），
    /// 与真机截图里「"侠皇帝"的丰富历史形象……」同构。
    private static let longCJK = """
    「侠皇帝」的丰富历史形象源远流长其魅力经久不衰这个称谓背后凝聚了武侠叙事中最具辨识度的角色原型从金庸笔下的侠之大者到古龙笔下的浪子剑客再到梁羽生笔下的儒侠风骨每一代作家都在用自己的方式为侠这个字注入新的内涵而皇帝二字更让这个形象多了一层家国天下的厚重感他的登基之路从来不是简单的权力更迭而是对旧秩序的一次次叩问与重建每一次出手都牵动着庙堂与江湖的双重目光那些看似闲笔的侧写其实都在悄悄铺陈他性格中最锋利的部分所谓侠客行的真意从来不在武功的高低而在人心向背的取舍
    """

    // MARK: - Harness（与 ChatSwiftUIStreamReplayTests 同构，直驱 NativeChatTimelineView）

    @MainActor
    private final class HarnessModel: ObservableObject {
        @Published var signal = ChatMessageUpdateSignal(revision: 0, reason: .initialLoad)
        @Published var isGenerationActive = false
        @Published var followGeneration = true
        @Published var scrollToBottomTrigger = 0
        var messages: [UIMessage] = []

        func send(_ reason: ChatMessageUpdateReason) {
            signal = ChatMessageUpdateSignal(revision: signal.revision + 1, reason: reason)
        }
    }

    @MainActor
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
                scrollToBottomSource: .streamGrowth,
                messageAnchor: nil,
                currentConversationID: nil,
                messagesProvider: { model.messages },
                variantInfoProvider: { _ in nil },
                onAction: { _ in },
                onViewportStateChange: { _ in },
                onDismissKeyboard: {}
            )
            .background(Color.white)
        }
    }

    @MainActor
    private func makeFixture(messages: [UIMessage]) -> (model: HarnessModel, window: UIWindow, host: UIHostingController<Harness>) {
        let sharedSettings = IOSSharedSettingsStore(
            userDefaults: UserDefaults(suiteName: "CJKProbe-\(UUID().uuidString)")!
        )
        let workspaceStore = IOSWorkspaceStore(
            baseDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("CJKProbe-\(UUID().uuidString)", isDirectory: true)
        )
        let model = HarnessModel()
        model.messages = messages
        let host = UIHostingController(rootView: Harness(
            model: model,
            displaySetting: sharedSettings.displaySetting,
            generativeUiSetting: sharedSettings.snapshot.agentRuntime.generativeUi,
            workspaceStore: workspaceStore
        ))
        host.view.backgroundColor = .white
        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
        } else {
            window = UIWindow(frame: .zero)
        }
        window.frame = CGRect(origin: .zero, size: Self.screenSize)
        window.backgroundColor = .white
        window.rootViewController = host
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        return (model, window, host)
    }

    private func makeMessage(id: String, role: MessageRole, text: String, finished: Bool) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: role,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: finished ? chatNowLocalDateTime() : nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func pump(_ seconds: TimeInterval) {
        let deadline = Date().addingTimeInterval(seconds)
        repeat {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01))
        } while Date() < deadline
    }

    private func findAllParagraphViews(in view: UIView) -> [ParagraphUIView] {
        var result: [ParagraphUIView] = []
        if let paragraph = view as? ParagraphUIView {
            result.append(paragraph)
        }
        for subview in view.subviews {
            result.append(contentsOf: findAllParagraphViews(in: subview))
        }
        return result
    }

    /// TextKit1 全部 lineFragment 的最小 x（文本容器坐标系），换算到 window 坐标。
    private func textInkMinX(in view: ParagraphUIView, window: UIWindow) -> CGFloat? {
        guard view.usesTextKit1 else { return nil }
        let layoutManager = view.layoutManager
        _ = layoutManager.glyphRange(for: view.textContainer)
        let glyphCount = layoutManager.numberOfGlyphs
        guard glyphCount > 0 else { return nil }
        var minX: CGFloat = .greatestFiniteMagnitude
        var index = 0
        while index < glyphCount {
            var range = NSRange(location: 0, length: 0)
            let rect = layoutManager.lineFragmentRect(forGlyphAt: index, effectiveRange: &range)
            minX = min(minX, rect.minX)
            guard range.length > 0 else { break }
            index = range.location + range.length
        }
        guard minX.isFinite else { return nil }
        return view.convert(CGPoint(x: minX, y: 0), to: window).x
    }

    private func dumpPNG(window: UIWindow, host: UIHostingController<Harness>, name: String) {
        do {
            try FileManager.default.createDirectory(
                atPath: Self.pngDir,
                withIntermediateDirectories: true
            )
        } catch {}
        window.layoutIfNeeded()
        pump(0.15)
        let renderer = UIGraphicsImageRenderer(size: window.frame.size)
        let image = renderer.image { context in
            window.layer.render(in: context.cgContext)
        }
        let pngPath = "\(Self.pngDir)/\(name).png"
        if let data = image.pngData() {
            try? data.write(to: URL(fileURLWithPath: pngPath), options: [.atomic])
            print("PROBE[PNG]: wrote \(pngPath) (\(data.count) bytes)")
        }
    }

    // MARK: - 契约：长 CJK 段落文本左缘不被吃

    private static let wideTableMarkdown = """
    # 《九天应元》—— 雷祖核心设定速查

    | 要素 | 内容 |
    |------|------|
    | **全称** | 九天应元雷声普化天尊 |
    | **尊号** | 雷祖、九天贞明大圣、九天普化君 |
    | **本源** | 浮黎元始天尊第九子 → 玉清真王（南极长生大帝）化身 |
    | **神职** | 总司五雷、主生杀枯荣、善恶赏罚、斩妖伏魔、号令雷霆 |
    | **部下** | 三十六雷公、雷部二十四天君、五雷都司、四大元帅 |
    | **经典** | 《玉枢宝经》（十字天经）——念天尊圣号即得解脱 |
    | **宝诰形象** | 披发骑麒麟，赤脚蹑层冰，手把九天炁，啸风鞭雷霆 |
    | **核心大愿** | "一切众生，天龙鬼神，一称吾名，悉使超涣" |

    以上就是设定速查表。
    """

    /// 宽表先行 + 长 CJK：宽表（表格 cell 的 ParagraphView 可能按宽上下文测量）
    /// 渲染后，长 CJK 段落紧随其后 —— 覆盖 ParagraphUIView 跨上下文复用/宽窄
    /// 切换后的 inset 保持。两条消息都先流式后完成，保证走 vendor block 渲染器
    /// （与生产 latch 语义一致）。
    func testWideTableThenLongCJKParagraphLeadingStaysAtTimelineInset() {
        let tableMessage = makeMessage(id: "table-1", role: .assistant, text: Self.wideTableMarkdown, finished: false)
        let cjkTarget = makeMessage(id: "cjk-1", role: .assistant, text: Self.longCJK, finished: false)
        let fixture = makeFixture(messages: [tableMessage, cjkTarget])
        let model = fixture.model
        let window = fixture.window
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        model.isGenerationActive = true
        model.send(.initialLoad)
        pump(0.3)
        // 两条消息都完成（latch 置位 → vendor block 渲染器）。
        model.messages = [
            makeMessage(id: "table-1", role: .assistant, text: Self.wideTableMarkdown, finished: true),
            makeMessage(id: "cjk-1", role: .assistant, text: Self.longCJK, finished: true)
        ]
        model.isGenerationActive = false
        model.send(.generationCompleted)
        pump(1.2)
        window.layoutIfNeeded()
        pump(1.2)
        window.layoutIfNeeded()

        // 滚动把 CJK 段落带入可视区（宽表消息很高，CJK 段落初始在窗口下方）。
        if let scrollView = findScrollView(in: window) {
            scrollView.setContentOffset(CGPoint(x: 0, y: 600), animated: false)
            pump(0.5)
            window.layoutIfNeeded()
            pump(0.5)
            window.layoutIfNeeded()
        }

        let paragraphs = findAllParagraphViews(in: window)
        XCTAssertGreaterThan(paragraphs.count, 0, "ParagraphUIView 未渲染，测试无效")

        // 只审计 CJK 段落（文本以「侠皇帝」开头）。
        var inkMinX: CGFloat = .greatestFiniteMagnitude
        var frameMinX: CGFloat = .greatestFiniteMagnitude
        var frameMaxX: CGFloat = 0
        for view in paragraphs where view.text.contains("侠皇帝") {
            let frame = view.convert(view.bounds, to: window)
            frameMinX = min(frameMinX, frame.minX)
            frameMaxX = max(frameMaxX, frame.maxX)
            if let ink = textInkMinX(in: view, window: window) {
                inkMinX = min(inkMinX, ink)
            }
            print("PROBE[table-then-cjk]: frame=\(frame) container=\(view.textContainer.size.width)")
        }
        print("PROBE[table-then-cjk summary]: frameMinX=\(frameMinX) frameMaxX=\(frameMaxX) inkMinX=\(inkMinX)")
        dumpPNG(window: window, host: fixture.host, name: "cjk-after-table")

        let expectedLeading = Self.timelineInset
        let expectedTrailing = Self.screenSize.width - Self.timelineInset
        XCTAssertTrue(frameMinX.isFinite, "未找到 CJK 段落")
        XCTAssertEqual(
            frameMinX, expectedLeading, accuracy: 1.0,
            "宽表后的 CJK 段落 frame 左缘 \(frameMinX) != 列左缘 \(expectedLeading)"
        )
        XCTAssertLessThanOrEqual(frameMaxX, expectedTrailing + 1.0, "段落右缘超出列宽")
        XCTAssertEqual(
            inkMinX, expectedLeading, accuracy: 1.5,
            "宽表后的 CJK 段落墨迹左缘 \(inkMinX) != 列左缘 \(expectedLeading)"
        )
    }

    private func findScrollView(in view: UIView) -> UIScrollView? {
        if let scrollView = view as? UIScrollView {
            return scrollView
        }
        for subview in view.subviews {
            if let found = findScrollView(in: subview) {
                return found
            }
        }
        return nil
    }

    func testLongCJKParagraphLeadingStaysAtTimelineInset() throws {
        let target = makeMessage(id: "stream-1", role: .assistant, text: Self.longCJK, finished: false)
        let fixture = makeFixture(messages: [target])
        let model = fixture.model
        let window = fixture.window
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }

        model.isGenerationActive = true
        model.send(.initialLoad)
        pump(0.3)

        // 生产流式节奏：pacer 逐拍推进 + streamDelta 信号。
        var current = model.messages
        for tick in 1...40 {
            let step = ChatStreamPresentationPacer.step(current: current, target: [target])
            if step.isCaughtUp { break }
            current = step.snapshot
            model.messages = current
            model.send(.streamDelta)
            pump(0.03)
            if tick.isMultiple(of: 8) {
                window.layoutIfNeeded()
            }
        }

        // 完成（同生产：completion 后渲染器 latch 保持 block 渲染器）。
        model.messages = [makeMessage(id: "stream-1", role: .assistant, text: Self.longCJK, finished: true)]
        model.isGenerationActive = false
        model.send(.generationCompleted)
        pump(0.8)
        window.layoutIfNeeded()
        pump(0.8)
        window.layoutIfNeeded()

        let paragraphs = findAllParagraphViews(in: window)
        XCTAssertGreaterThan(paragraphs.count, 0, "ParagraphUIView 未渲染，测试无效")

        var inkMinX: CGFloat = .greatestFiniteMagnitude
        var frameMinX: CGFloat = .greatestFiniteMagnitude
        var frameMaxX: CGFloat = 0
        for view in paragraphs {
            let frame = view.convert(view.bounds, to: window)
            frameMinX = min(frameMinX, frame.minX)
            frameMaxX = max(frameMaxX, frame.maxX)
            if let ink = textInkMinX(in: view, window: window) {
                inkMinX = min(inkMinX, ink)
            }
            print("PROBE[para]: frame=\(frame) bounds=\(view.bounds.size) container=\(view.textContainer.size.width) offset=\(view.contentOffset.x) tk1=\(view.usesTextKit1)")
        }
        print("PROBE[summary]: count=\(paragraphs.count) frameMinX=\(frameMinX) frameMaxX=\(frameMaxX) inkMinX=\(inkMinX)")

        dumpPNG(window: window, host: fixture.host, name: "cjk-native-timeline")

        let expectedLeading = Self.timelineInset
        let expectedTrailing = Self.screenSize.width - Self.timelineInset

        XCTAssertEqual(
            frameMinX, expectedLeading, accuracy: 1.0,
            "段落视图 frame 左缘 \(frameMinX) != 列左缘 \(expectedLeading)"
        )
        XCTAssertLessThanOrEqual(
            frameMaxX, expectedTrailing + 1.0,
            "段落视图右缘 \(frameMaxX) 超出列右缘 \(expectedTrailing)"
        )
        XCTAssertTrue(inkMinX.isFinite, "未测得文本墨迹左缘")
        XCTAssertEqual(
            inkMinX, expectedLeading, accuracy: 1.5,
            "文本墨迹左缘 \(inkMinX) != 列左缘 \(expectedLeading)（长 CJK 段落左缘贴边/被裁）"
        )
    }
}
