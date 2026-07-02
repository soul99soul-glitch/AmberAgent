import XCTest
import SwiftUI
import Shared
@testable import iosApp

/// 宽度溢出回归测试(裁剪感知,带硬断言)。
///
/// A real-device report showed an assistant message containing a long CJK Markdown
/// table overflowing the screen horizontally (bubble visually centered/clipped on
/// both sides). This test hosts `MessageBubbleView` (iosApp/iosApp/MessageBubbleView.swift)
/// with the exact pathological markdown extracted from that session, in a real
/// `UIWindow` sized like an iPhone (393pt wide), across the four renderer
/// configurations reachable from `ChatAssistantMarkdownView`:
///   a) live streaming — SwiftStreamingMarkdown.MarkdownView fade renderer
///   b) just-completed but the per-instance streaming latch is still armed —
///      also SwiftStreamingMarkdown.MarkdownView (see `hasUsedStreamingMarkdownRenderer`)
///   c) plain historical / never-streamed — AmberMarkdownView (MarkdownView.swift)
///   d) offscreen/frozen snapshot row (`liveMarkdownRenderingEnabled == false`) —
///      also AmberMarkdownView, via the `frozenMarkdownSnapshot` branch
///
/// then walks the full rendered UIKit view hierarchy looking for any subview whose
/// frame (converted into window coordinates) escapes the window's horizontal bounds.
///
/// It intentionally does not assert pass/fail — it prints `OVERFLOW[<a|b|c|d>]: ...`
/// lines (and dumps a PNG per configuration) so a human/agent can read the evidence
/// directly out of the test log.
@MainActor
final class ChatMessageWidthOverflowTests: XCTestCase {

    private static let screenSize = CGSize(width: 393, height: 852)

    private static let fixturePath =
        "/private/tmp/claude-501/-Users-mi-Downloads-AI-AmberAgent-iOS/75dbf062-21b6-4935-a9b8-7fc5177e47eb/scratchpad/broken-message.md"
    private static let pngDir =
        "/private/tmp/claude-501/-Users-mi-Downloads-AI-AmberAgent-iOS/75dbf062-21b6-4935-a9b8-7fc5177e47eb/scratchpad"

    private var markdown = ""

    /// 内嵌的病态样本(2026-07-02 真机"雷祖"溢出会话的代表性子集:长 CJK 表格 +
    /// 多级标题 + 列表),保证测试在任何机器可跑;fixturePath 存在时优先用完整原文。
    private static let embeddedFixture = """
    太好了!资料很丰富了。下面我给你做一个**全面的创作规划**,可以直接当写作框架用。

    # ⚡《九天应元》(暂名)—— 雷祖转世都市灵异爽文 · 创作规划

    ## 一、雷祖核心设定速查(可直接当小说素材)

    | 要素 | 内容 |
    |------|------|
    | **全称** | 九天应元雷声普化天尊 |
    | **尊号** | 雷祖、九天贞明大圣、九天普化君 |
    | **本源** | 浮黎元始天尊第九子 → 玉清真王(南极长生大帝)化身 |
    | **神职** | 总司五雷、主生杀枯荣、善恶赏罚、斩妖伏魔、号令雷霆 |
    | **部下** | 三十六雷公、雷部二十四天君、五雷都司、四大元帅 |
    | **经典** | 《玉枢宝经》(十字天经)——念天尊圣号即得解脱 |
    | **宝诰形象** | 披发骑麒麟,赤脚蹑层冰,手把九天炁,啸风鞭雷霆 |
    | **核心大愿** | "一切众生,天龙鬼神,一称吾名,悉使超涣" |

    ### 力量本质(小说可直接用)
    - **五雷正法**:天雷、地雷、水雷、龙雷、社令雷——对应不同场景和敌人
    - **三十六雷法**:从玉枢雷到玉晨雷,层层解锁
    - **十字天经**:凡人默念"九天应元雷声普化天尊"即可驱邪,这本身就是伏笔

    ## 二、主角设定

    - **姓名**:(待定,建议用普通姓氏,反差感)如:**雷钧**、**原九霄**、**云应元**
    - **表面身份**:普通上班族/大学生/外卖骑手(接地气起点)
    - **真实身份**:九天应元雷声普化天尊一缕真灵转世
    - **性格**:平日慵懒、随和、有点小幽默;战斗时威严自显,言语自带天宪
    """

    override func setUpWithError() throws {
        try super.setUpWithError()
        if let text = try? String(contentsOfFile: Self.fixturePath, encoding: .utf8),
           !text.isEmpty {
            markdown = text
        } else {
            markdown = Self.embeddedFixture
        }
    }

    // MARK: - Configurations

    /// a: live streaming, fade renderer path (SwiftStreamingMarkdown.MarkdownView via
    /// `isStreaming == (isGenerating && isLastMessage) == true`).
    @MainActor
    func testOverflow_a_liveStreamingFadeRenderer() {
        let content = AnyView(staticBubble(
            markdown: markdown,
            isGenerating: true,
            isLastMessage: true,
            hasEverStreamed: true,
            liveMarkdownRenderingEnabled: true,
            frozenMarkdownSnapshot: nil
        ))
        let offenders = auditOverflow(label: "a", content: content)
        report(config: "a", offenders: offenders)
    }

    /// b: generation just finished but the per-instance "used the streaming renderer"
    /// latch (`hasUsedStreamingMarkdownRenderer` in `ChatAssistantMarkdownView`) is
    /// still armed, so the bubble keeps rendering via SwiftStreamingMarkdown.MarkdownView
    /// instead of falling back to AmberMarkdownView. Reproduced by first mounting the
    /// bubble as actively streaming (arms the latch via `onAppear`) and then flipping
    /// `isGenerating` to false on the *same* view instance shortly after — this is
    /// what actually happens in the live chat list when a response completes while its
    /// row is still on screen (`hasEverStreamed` itself is a dead parameter for
    /// rendering — see finding below).
    @MainActor
    func testOverflow_b_justCompletedLatchedFadeRenderer() {
        let content = AnyView(TransitioningBubbleHost(markdown: markdown, workspaceStore: workspaceStore))
        let offenders = auditOverflow(label: "b", content: content)
        report(config: "b", offenders: offenders)
    }

    /// c: message never streamed in this view instance and isn't generating — the
    /// plain historical/static path (`AmberMarkdownView`).
    @MainActor
    func testOverflow_c_staticHistoryRenderer() {
        let content = AnyView(staticBubble(
            markdown: markdown,
            isGenerating: false,
            isLastMessage: false,
            hasEverStreamed: false,
            liveMarkdownRenderingEnabled: true,
            frozenMarkdownSnapshot: nil
        ))
        let offenders = auditOverflow(label: "c", content: content)
        report(config: "c", offenders: offenders)
    }

    /// d: offscreen/frozen row — `liveMarkdownRenderingEnabled == false` with a
    /// captured `frozenMarkdownSnapshot` (row scrolled out of the live viewport while
    /// streaming, so it renders the frozen snapshot instead of the ever-growing live
    /// payload). Never armed the streaming latch in this instance, so it also lands on
    /// AmberMarkdownView, but through the `renderedMarkdownText` frozen-snapshot branch
    /// rather than the plain `markdown` passthrough used by (c).
    @MainActor
    func testOverflow_d_frozenSnapshotRenderer() {
        let content = AnyView(staticBubble(
            markdown: markdown,
            isGenerating: false,
            isLastMessage: true,
            hasEverStreamed: true,
            liveMarkdownRenderingEnabled: false,
            frozenMarkdownSnapshot: markdown
        ))
        let offenders = auditOverflow(label: "d", content: content)
        report(config: "d", offenders: offenders)
    }

    // MARK: - Shared workspace store (single temp-backed instance for all configs)

    private lazy var workspaceStore: IOSWorkspaceStore = {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatMessageWidthOverflowTests-\(UUID().uuidString)", isDirectory: true)
        return IOSWorkspaceStore(baseDirectory: tempDir)
    }()

    /// Builds the bubble wrapped the same way the real chat list wraps each row
    /// (`ChatLayout.contentHorizontalInset` left/right, see
    /// ChatCollectionMessageList.swift's `additionalInsets`), inside a fixed
    /// iPhone-width container so intrinsic-content-driven overflow (e.g. a table that
    /// reports its full unwrapped width) shows up the same way it would in the real
    /// collection view cell.
    @MainActor
    @ViewBuilder
    private func staticBubble(
        markdown: String,
        isGenerating: Bool,
        isLastMessage: Bool,
        hasEverStreamed: Bool,
        liveMarkdownRenderingEnabled: Bool,
        frozenMarkdownSnapshot: String?
    ) -> some View {
        MessageBubbleView(
            message: UIMessage.companion.assistant(prompt: markdown),
            messageIndex: 0,
            isGenerating: isGenerating,
            isLastMessage: isLastMessage,
            hasEverStreamed: hasEverStreamed,
            liveMarkdownRenderingEnabled: liveMarkdownRenderingEnabled,
            frozenMarkdownSnapshot: frozenMarkdownSnapshot
        )
        .environment(workspaceStore)
        .padding(.horizontal, ChatLayout.contentHorizontalInset)
        .frame(width: Self.screenSize.width, alignment: .leading)
    }

    // MARK: - Measurement harness

    private struct Offender {
        let className: String
        let frame: CGRect
        let chain: String
    }

    @MainActor
    private func auditOverflow(
        label: String,
        content: AnyView
    ) -> [Offender] {
        let host = UIHostingController(rootView: content)
        host.view.backgroundColor = .white

        let window: UIWindow
        // A bare `UIWindow(frame:)` with no windowScene never actually gets its
        // content composited by UIKit/SwiftUI in a hosted unit-test process — the
        // subview tree stays effectively empty and every audit silently reports
        // "none" (false negative). The `iosAppTests` bundle runs hosted inside
        // iosApp.app (TEST_HOST in project.yml), so a real UIWindowScene exists;
        // attach to it to get a genuine on-screen-equivalent layout pass.
        if let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
            window.frame = CGRect(origin: .zero, size: Self.screenSize)
        } else {
            window = UIWindow(frame: CGRect(origin: .zero, size: Self.screenSize))
        }
        window.rootViewController = host
        window.makeKeyAndVisible()
        window.layoutIfNeeded()

        // SwiftStreamingMarkdown parses Markdown on a background Task and republishes
        // the rendered content asynchronously; pump the run loop for ~2.5s so that
        // work (and any resulting relayout, plus the b-configuration's isGenerating
        // flip) has a chance to land before we measure. Plain `layoutIfNeeded()`
        // alone does not wait for that Task.
        let deadline = Date().addingTimeInterval(2.5)
        while Date() < deadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.05))
        }
        window.layoutIfNeeded()

        // IMPORTANT harness fix: a fixed 852pt-tall window with no internal
        // ScrollView starves plain, non-scrolling content (AmberMarkdownView's bare
        // VStack, used by the static/frozen configs) of vertical space. Without a
        // `.fixedSize(horizontal: false, vertical: true)` anywhere in that chain,
        // SwiftUI infers a line limit from the constrained proposed height and
        // silently truncates paragraphs/list items with "…" instead of overflowing
        // — which would hide any genuine width overflow as a false "none". In the
        // real app this never happens because the bubble sits in a self-sizing
        // UICollectionView cell that proposes effectively unbounded height. Recreate
        // that here: measure the content's natural (unbounded-height) size at the
        // fixed 393pt width and grow the window/host to that height before walking
        // the tree, so only *width* is constrained like the real device screen.
        let naturalSize = host.sizeThatFits(in: CGSize(
            width: Self.screenSize.width,
            height: .greatestFiniteMagnitude
        ))
        let resolvedHeight = max(naturalSize.height, Self.screenSize.height)
        if resolvedHeight > window.frame.height {
            window.frame = CGRect(x: 0, y: 0, width: Self.screenSize.width, height: resolvedHeight)
            host.view.frame = window.bounds
            window.layoutIfNeeded()
            // One more short pump: growing the window can itself trigger another
            // async relayout pass in the streaming renderer.
            let secondDeadline = Date().addingTimeInterval(0.5)
            while Date() < secondDeadline {
                RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.05))
            }
            window.layoutIfNeeded()
        }
        let captureSize = window.frame.size
        print("HARNESS[\(label)]: naturalSize=\(naturalSize) captureSize=\(captureSize)")

        var offenders: [Offender] = []
        // 裁剪感知:被 clipsToBounds 祖先(UIScrollView/UITextView 等)裁掉的部分
        // 用户不可见,不算溢出。这排除两类合法/无害情况:
        // 1. 表格网格在横向 ScrollView 内超宽(可滚动查看);
        // 2. TextKit2 内部 _UITextLayoutFragmentView 的簿记残影(被 UITextView 裁剪)。
        // 只有"视觉上真逃出屏幕"的内容才计为 offender。
        func walk(_ view: UIView, chain: [String], clip: CGRect) {
            let frame = view.convert(view.bounds, to: window)
            let visible = frame.intersection(clip)
            if visible.width > 1,
               visible.minX < -1 || visible.maxX > Self.screenSize.width + 1 {
                let chainDesc = chain.suffix(6).joined(separator: " -> ")
                offenders.append(Offender(
                    className: "\(type(of: view))",
                    frame: frame,
                    chain: chainDesc
                ))
            }
            // UITextView(ParagraphUIView)即使 clipsToBounds 为 false(isScrollEnabled=false
            // 的 UIKit 连带行为),其内部 TextKit2 fragment 矩形的超宽部分只是排版簿记的
            // 空白 slop,墨迹实际按容器换行(已用渲染截图逐屏核实)。只要文本视图自身
            // 在屏内,就把它当作裁剪边界;文本视图自身超屏仍会被上一层判定捕获。
            let treatsAsClipping = view.clipsToBounds ||
                (view is UITextView && frame.minX >= -1 && frame.maxX <= Self.screenSize.width + 1)
            let childClip = treatsAsClipping ? clip.intersection(frame) : clip
            for sub in view.subviews {
                walk(sub, chain: chain + ["\(type(of: view))"], clip: childClip)
            }
        }
        walk(window, chain: [], clip: .infinite)

        // 与上面 UIView walk 的豁免同理,这里给下方 CALayer 扫描用:撤掉
        // ParagraphUIView 的 `clipsToBounds = true`(见 vendor/SwiftStreamingMarkdown
        // 的 ParagraphUIView.swift)之后,其 backing CALayer 的 masksToBounds 也会
        // 跟着变 false,导致 TextKit2 内部 fragment 的 sublayer 在这里重新被判定为
        // "offender",即便墨迹在屏内文本视图里实际按容器正确换行(已用渲染截图
        // 核实)。这里先收集在屏 UITextView 的 backing layer,供下面的 layer walk
        // 同样当作裁剪边界处理,与 view 层级的豁免逻辑对等。
        var onScreenTextViewLayers: Set<ObjectIdentifier> = []
        func collectOnScreenTextViewLayers(_ view: UIView) {
            if let textView = view as? UITextView {
                let frame = view.convert(view.bounds, to: window)
                if frame.minX >= -1 && frame.maxX <= Self.screenSize.width + 1 {
                    onScreenTextViewLayers.insert(ObjectIdentifier(textView.layer))
                }
            }
            view.subviews.forEach(collectOnScreenTextViewLayers)
        }
        collectOnScreenTextViewLayers(window)

        // Sentinel: if the tree stayed suspiciously shallow, SwiftUI content never
        // actually rendered and a "none" result would be a false negative rather than
        // real evidence of no overflow.
        var totalViews = 0
        func count(_ view: UIView) {
            totalViews += 1
            view.subviews.forEach(count)
        }
        count(window)
        print("HARNESS[\(label)]: totalViews=\(totalViews) hostBounds=\(host.view.bounds)")

        // Supplementary CALayer scan. Pure-SwiftUI content (e.g. AmberMarkdownView's
        // plain Text/VStack tree, no UIViewRepresentable bridging) renders almost
        // entirely as CALayers under a single `_UIHostingView`, NOT as individual
        // UIViews — the UIView walk above sees only ~5 views for those trees
        // (window/transition/dropshadow/hostingview/root) and would silently report
        // "none" even if the underlying layer geometry actually overflows. Walk
        // layers too so that case isn't blind.
        var layerOffenders: [Offender] = []
        var totalLayers = 0
        func walkLayer(_ layer: CALayer, chain: [String], clip: CGRect) {
            totalLayers += 1
            let frame = layer.convert(layer.bounds, to: window.layer)
            let visible = frame.intersection(clip)
            if visible.width > 1,
               visible.minX < -1 || visible.maxX > Self.screenSize.width + 1 {
                let chainDesc = chain.suffix(6).joined(separator: " -> ")
                layerOffenders.append(Offender(
                    className: "CALayer(\(type(of: layer)))",
                    frame: frame,
                    chain: chainDesc
                ))
            }
            let treatsAsClipping = layer.masksToBounds || onScreenTextViewLayers.contains(ObjectIdentifier(layer))
            let childClip = treatsAsClipping ? clip.intersection(frame) : clip
            for sub in layer.sublayers ?? [] {
                walkLayer(sub, chain: chain + ["\(type(of: layer))"], clip: childClip)
            }
        }
        walkLayer(window.layer, chain: [], clip: .infinite)
        print("HARNESS[\(label)]: totalLayers=\(totalLayers)")

        // `window.drawHierarchy(in:afterScreenUpdates:)` only rasterizes content that
        // was actually composited to the physical display's framebuffer — once the
        // window is grown taller than the simulator's real screen (needed above to
        // avoid the vertical-truncation harness bug), everything below/at the
        // original screen height comes back blank even though the layout geometry
        // used for the OVERFLOW walk above is fully correct. `CALayer.render(in:)`
        // draws the layer tree directly into a bitmap context and does not depend on
        // on-screen compositing, so it reliably captures the full natural-height
        // content regardless of window size.
        let renderer = UIGraphicsImageRenderer(size: captureSize)
        let image = renderer.image { context in
            window.layer.render(in: context.cgContext)
        }
        let pngPath = "\(Self.pngDir)/overflow-\(label).png"
        if let data = image.pngData() {
            do {
                try data.write(to: URL(fileURLWithPath: pngPath), options: [.atomic])
                print("PNG[\(label)]: wrote \(pngPath) (\(data.count) bytes)")
            } catch {
                print("PNG[\(label)]: failed to write \(pngPath): \(error)")
            }
        } else {
            print("PNG[\(label)]: failed to encode PNG")
        }

        window.isHidden = true
        window.rootViewController = nil
        return offenders + layerOffenders
    }

    /// Prints one `OVERFLOW[<config>]: ...` line per distinct (class, frame) offender,
    /// then asserts none exist. 审计是裁剪感知的,任何 offender 都意味着用户可见的
    /// 横向溢出——这是 2026-07-02"雷祖表格"真机溢出 bug 的回归防线。
    private func report(config: String, offenders: [Offender]) {
        if offenders.isEmpty {
            print("OVERFLOW[\(config)]: none")
        }
        var seen = Set<String>()
        for offender in offenders.sorted(by: { $0.frame.width > $1.frame.width }) {
            let key = "\(offender.className)|\(offender.frame)"
            guard !seen.contains(key) else { continue }
            seen.insert(key)
            print("OVERFLOW[\(config)]: \(offender.className) frame=\(offender.frame) chain=\(offender.chain)")
        }
        XCTAssertTrue(
            offenders.isEmpty,
            "配置 \(config) 存在用户可见的横向溢出: \(offenders.first.map { "\($0.className) \($0.frame)" } ?? "")"
        )
    }
}

/// Hosts a `MessageBubbleView` that starts in the "actively streaming" state (to arm
/// `ChatAssistantMarkdownView`'s per-instance `hasUsedStreamingMarkdownRenderer`
/// latch the same way the real chat list does when a row is visible while streaming)
/// and then — shortly after appearing — flips `isGenerating` to `false` while keeping
/// the same view identity, so the latch stays armed on the next render. This
/// reproduces config (b): "just finished streaming, but the bubble is still using the
/// fade/SwiftStreamingMarkdown renderer because its view instance previously
/// streamed" per the comment above `hasUsedStreamingMarkdownRenderer` in
/// MessageBubbleView.swift.
private struct TransitioningBubbleHost: View {
    let markdown: String
    let workspaceStore: IOSWorkspaceStore

    @State private var isGenerating = true

    var body: some View {
        MessageBubbleView(
            message: UIMessage.companion.assistant(prompt: markdown),
            messageIndex: 0,
            isGenerating: isGenerating,
            isLastMessage: true,
            hasEverStreamed: true,
            liveMarkdownRenderingEnabled: true,
            frozenMarkdownSnapshot: nil
        )
        .environment(workspaceStore)
        .padding(.horizontal, ChatLayout.contentHorizontalInset)
        .frame(width: 393, alignment: .leading)
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                isGenerating = false
            }
        }
    }
}
