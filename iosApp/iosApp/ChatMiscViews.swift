import SwiftUI
import UIKit
import Shared

/// UIKit 驱动的 variableColor SF Symbol 动画(等价 SwiftUI
/// `.symbolEffect(.variableColor.iterative.reversing, isActive:)`)。
/// 动画运行在 CA/UIKit 层,不占用 SwiftUI ViewGraph 的每帧更新预算——
/// 这是"隔离常驻指示动画"的标准做法,不是动画降级。
struct ChatUIKitVariableColorSymbol: UIViewRepresentable {
    let systemName: String
    let pointSize: CGFloat
    let weight: UIFont.Weight
    let tint: UIColor
    let isActive: Bool

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.preferredSymbolConfiguration = UIImage.SymbolConfiguration(
            pointSize: pointSize,
            weight: symbolWeight
        )
        view.image = UIImage(systemName: systemName)
        view.tintColor = tint
        view.setContentHuggingPriority(.required, for: .horizontal)
        view.setContentHuggingPriority(.required, for: .vertical)
        view.setContentCompressionResistancePriority(.required, for: .horizontal)
        view.setContentCompressionResistancePriority(.required, for: .vertical)
        applyEffect(to: view, active: isActive)
        context.coordinator.effectActive = isActive
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {
        view.tintColor = tint
        if context.coordinator.effectActive != isActive {
            applyEffect(to: view, active: isActive)
            context.coordinator.effectActive = isActive
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    final class Coordinator {
        var effectActive = false
    }

    private var symbolWeight: UIImage.SymbolWeight {
        switch weight {
        case .bold: return .bold
        case .medium: return .medium
        case .regular: return .regular
        default: return .semibold
        }
    }

    private func applyEffect(to view: UIImageView, active: Bool) {
        if active {
            view.addSymbolEffect(.variableColor.iterative.reversing)
        } else {
            view.removeAllSymbolEffects()
        }
    }
}

struct ChatReasoningCard: View {
    let bodyText: String
    var isThinking: Bool = false
    var startedAt: Date? = nil
    var finishedSeconds: Double? = nil
    var levelLabel: String? = nil
    var autoCloseThinking: Bool = true
    @State private var isExpanded: Bool
    @State private var userToggled = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(
        bodyText: String,
        isThinking: Bool = false,
        startedAt: Date? = nil,
        finishedSeconds: Double? = nil,
        levelLabel: String? = nil,
        autoCloseThinking: Bool = true
    ) {
        self.bodyText = bodyText
        self.isThinking = isThinking
        self.startedAt = startedAt
        self.finishedSeconds = finishedSeconds
        self.levelLabel = levelLabel
        self.autoCloseThinking = autoCloseThinking
        // Streaming reasoning should be visible: it reassures the user that the agent is working.
        // The body gets a fixed live height below, so visibility does not fight chat scrolling.
        let hasInitialBodyText = Self.hasVisibleText(bodyText)
        self._isExpanded = State(initialValue: hasInitialBodyText && (isThinking ? true : !autoCloseThinking))
    }

    /// 推理正文是否含可见字符。
    ///
    /// 不用 `trimmingCharacters(in:).isEmpty`:它的成本取决于首字符是否为空白。
    /// 首字符非空白时 Foundation 走零拷贝快路径(1M 字符实测 ~2µs);一旦正文以
    /// 空白或换行开头(模型 thinking 很常见),它会真的分配一份全文副本——同规模
    /// 实测 ~90µs/次。而 `hasBodyText` 经 `showsBody` 在一次 body 求值中被求值
    /// 约 10 次(圆角/chevron/高度/mask/animation 等处各一次),流式期间每 48ms
    /// 一轮,叠加后接近 0.9ms,是 120Hz 单帧预算(8.3ms)的一成以上。
    /// `contains` 在首个非空白字符处返回,与首字符形态无关,恒为亚微秒。
    static func hasVisibleText(_ text: String) -> Bool {
        text.contains { !$0.isWhitespace }
    }

    private var levelSuffix: String {
        guard let levelLabel, !levelLabel.isEmpty else { return "" }
        return " · \(levelLabel)"
    }

    private var hasBodyText: Bool {
        Self.hasVisibleText(bodyText)
    }

    private var showsBody: Bool {
        isExpanded && hasBodyText
    }

    private var capsuleFill: Color {
        AmberTheme.accent.opacity(isThinking ? 0.10 : 0.08)
    }

    private var capsuleStroke: Color {
        AmberTheme.accent.opacity(isThinking ? 0.20 : 0.16)
    }

    /// 思考内容顶部底部的渐变模糊 mask。
    /// 顶部 0→1(前 12pt 淡出),中间全不透明,底部 1→0(末 12pt 淡出)。
    private var reasoningFadeMask: some View {
        VStack(spacing: 0) {
            LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom)
                .frame(height: 12)
            Rectangle()
            LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 12)
        }
    }

    private func titleText(elapsed: Int?) -> String {
        if isThinking {
            if let elapsed { return "思考中 \(elapsed) 秒\(levelSuffix)" }
            return "思考中\(levelSuffix)"
        }
        if let finishedSeconds { return "思考了 \(Self.formatFinishedSeconds(finishedSeconds)) 秒\(levelSuffix)" }
        return "思考过程\(levelSuffix)"
    }

    /// 不足 1 秒按 0.1 精度显示(最小 0.1,避免「0 秒」/「0.0 秒」);≥1 秒显示整数。
    private static func formatFinishedSeconds(_ seconds: Double) -> String {
        let rounded = (seconds * 10).rounded() / 10
        if rounded >= 1 { return "\(Int(rounded.rounded()))" }
        return String(format: "%.1f", max(0.1, rounded))
    }

    @ViewBuilder
    private var titleLabel: some View {
        if isThinking, let startedAt {
            // Live ticking elapsed counter while the model is thinking.
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text(titleText(elapsed: Int(max(0, context.date.timeIntervalSince(startedAt)))))
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
            }
        } else {
            Text(titleText(elapsed: nil))
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
        }
    }

    // Compact cream pill: amber clock + "思考中 N 秒 · Auto" (live) / "思考了 N 秒 · Auto" (done) +
    // chevron. Expands to a height-capped, auto-scrolling view of the streaming reasoning text.
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                guard hasBodyText else { return }
                userToggled = true
                withAnimation(.easeInOut(duration: 0.22)) { isExpanded.toggle() }
            } label: {
                HStack(spacing: 7) {
                    // 思考中的时钟动画由 UIKit addSymbolEffect 驱动(视觉与 SwiftUI
                    // .symbolEffect 相同)。SwiftUI 的永动 symbolEffect 会把
                    // ViewGraphDisplayLink 钉在 60fps,每帧渲染器工作量 ∝ 整窗显示
                    // 列表——长表格/代码块在屏时实测 ~240ms CPU/秒(2026-07-10
                    // idleDecay 探针,裸挂 markdown 后降到 ~17ms/秒)。UIKit 版由
                    // CA 层驱动同一动画,SwiftUI 零每帧成本,屏幕内动画不降级。
                    ChatUIKitVariableColorSymbol(
                        systemName: "clock",
                        pointSize: 12.5,
                        weight: .semibold,
                        tint: UIColor(AmberTheme.accentAmber),
                        isActive: isThinking && !reduceMotion
                    )

                    titleLabel

                    // Collapsed: hug content (chevron sits right after the title). Expanded: push
                    // the chevron to the right edge, matching the full-width reading area below.
                    if showsBody { Spacer(minLength: 6) }

                    if hasBodyText {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(AmberTheme.muted)
                            .rotationEffect(.degrees(showsBody ? 180 : 0))
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(AmberPressFeedbackStyle(pressedScale: hasBodyText ? 0.98 : 1, haptic: hasBodyText ? .selection : nil))

            if showsBody {
                // 自适应高度 + maxHeight 上限 + 顶部底部渐变模糊。
                // 短文本:Text 高度 < maxHeight,ScrollView 不滚,整体高度 = 文本高度(不留白)。
                // 长文本:超过 maxHeight,ScrollView 可滚查看。
                ScrollViewReader { proxy in
                    ScrollView {
                        Text(bodyText)
                            .font(.caption2)
                            .foregroundStyle(AmberTheme.muted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12)
                            .padding(.top, 2)
                            .padding(.bottom, 10)

                        Color.clear
                            .frame(height: 1)
                            .id("reasoning-bottom")
                    }
                    .onAppear {
                        scrollReasoningToBottom(proxy)
                    }
                    .onChange(of: bodyText) { _, _ in
                        scrollReasoningToBottom(proxy)
                    }
                }
                .frame(maxHeight: isThinking ? 180 : 260)
                .mask(reasoningFadeMask)
                // 从底部滑入/滑出:展开时从下往上出现,收回时从上往下消失(底部先收)。
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .background(
            capsuleFill,
            in: RoundedRectangle(cornerRadius: showsBody ? AmberTheme.radiusLarge : 17, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: showsBody ? AmberTheme.radiusLarge : 17, style: .continuous)
                .stroke(capsuleStroke, lineWidth: 0.7)
        }
        // Clip to the capsule so the collapsing content can never render outside / through it.
        .clipShape(RoundedRectangle(cornerRadius: showsBody ? AmberTheme.radiusLarge : 17, style: .continuous))
        // 统一驱动所有依赖 showsBody/isExpanded 的视觉变化(圆角、chevron、高度增删),
        // 覆盖自动展开/收回路径(它们不经过 withAnimation)和用户 toggle 路径。
        .animation(.easeInOut(duration: 0.28), value: showsBody)
        .onChange(of: bodyText) { _, newValue in
            guard isThinking, !userToggled else { return }
            if Self.hasVisibleText(newValue) {
                withAnimation(.easeInOut(duration: 0.28)) { isExpanded = true }
            }
        }
        .onChange(of: isThinking) { _, nowThinking in
            guard !userToggled else { return }
            if nowThinking {
                withAnimation(.easeInOut(duration: 0.28)) { isExpanded = hasBodyText }
            } else if autoCloseThinking {
                withAnimation(.easeInOut(duration: 0.28)) {
                    isExpanded = false
                }
            }
        }
    }

    private func scrollReasoningToBottom(_ proxy: ScrollViewProxy) {
        guard isThinking, showsBody else { return }
        DispatchQueue.main.async {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            transaction.animation = nil
            withTransaction(transaction) {
                proxy.scrollTo("reasoning-bottom", anchor: .bottom)
            }
        }
    }
}

private struct ChatReasoningBodyTextView: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isSelectable = true
        // isScrollEnabled = true:保持可滚动(超长内容可查看),且不覆盖标题(VStack 布局正常)。
        // 短文本在固定 frame 内上方对齐(textContainerInset 控制留白)。
        textView.isScrollEnabled = true
        textView.showsVerticalScrollIndicator = false
        textView.textContainerInset = UIEdgeInsets(top: 2, left: 12, bottom: 10, right: 12)
        textView.textContainer.lineFragmentPadding = 0
        textView.font = UIFont.preferredFont(forTextStyle: .caption1)
        textView.textColor = UIColor(AmberTheme.muted)
        textView.adjustsFontForContentSizeCategory = true
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        if textView.text != text {
            let wasAtBottom = textView.contentSize.height <= textView.bounds.height + 1 ||
                textView.contentOffset.y >= textView.contentSize.height - textView.bounds.height - 8
            textView.text = text
            textView.font = UIFont.preferredFont(forTextStyle: .caption1)
            textView.textColor = UIColor(AmberTheme.muted)
            if wasAtBottom {
                DispatchQueue.main.async { [weak textView] in
                    guard let textView else { return }
                    let maxY = max(
                        -textView.adjustedContentInset.top,
                        textView.contentSize.height - textView.bounds.height + textView.adjustedContentInset.bottom
                    )
                    textView.setContentOffset(CGPoint(x: 0, y: maxY), animated: false)
                }
            }
        }
    }
}

struct ChatEmptyState: View {
    @State private var prompt = Self.randomPrompt()

    var body: some View {
        VStack(spacing: 18) {
            AmberEmptyStateMark()
                .padding(.bottom, 2)

            VStack(spacing: 7) {
                Text("今天想聊点什么？")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text(prompt)
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.muted)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 34)
        .padding(.top, 104)
        .padding(.bottom, 180)
    }

    private static func randomPrompt() -> String {
        [
            "先随便说一句也可以。",
            "有个念头的话，直接丢给我。",
            "想写、想查、想整理，都可以从一句话开始。",
            "要解决问题也行，只是聊聊也行。",
            "不知道从哪开始的话，先说现在卡在哪。"
        ].randomElement() ?? "先随便说一句也可以。"
    }
}

private struct AmberEmptyStateMark: View {
    private static let markDiameter: CGFloat = 56
    private static let orbitDiameter: CGFloat = 62
    private static let orbitLineWidth: CGFloat = 1
    private static let dotDiameter: CGFloat = 5

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
            let progress = reduceMotion ? 0 : orbitProgress(at: timeline.date)
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [
                                AmberTheme.surface.opacity(0.94),
                                AmberTheme.accent.opacity(0.08)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: Self.markDiameter, height: Self.markDiameter)
                    .overlay {
                        Circle()
                            .stroke(AmberTheme.borderSoft.opacity(0.9), lineWidth: 0.7)
                    }
                    .shadow(color: AmberTheme.accent.opacity(0.10), radius: 9, x: 0, y: 5)

                Circle()
                    .stroke(AmberTheme.accent.opacity(0.22), lineWidth: Self.orbitLineWidth)
                    .frame(width: Self.orbitDiameter, height: Self.orbitDiameter)
                    .opacity(reduceMotion ? 0.12 : 1)

                Text("A")
                    .font(.system(size: 30, weight: .semibold, design: .serif))
                    .foregroundStyle(AmberTheme.foreground)
                    .offset(y: -1)

                orbitDot(progress: progress)
            }
            .frame(width: 76, height: 76)
        }
    }

    private func orbitDot(progress: Double) -> some View {
        let angle = progress * 2 * .pi - .pi / 2
        let radius = Self.orbitDiameter / 2
        let x = CGFloat(cos(angle)) * radius
        let y = CGFloat(sin(angle)) * radius

        return Circle()
            .fill(AmberTheme.accent)
            .frame(width: Self.dotDiameter, height: Self.dotDiameter)
            .offset(x: x, y: y)
            .shadow(color: AmberTheme.accent.opacity(0.45), radius: 5, x: 0, y: 0)
            .opacity(0.9)
    }

    private func orbitProgress(at date: Date) -> Double {
        let cycle = 7.2
        return date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: cycle) / cycle
    }
}

struct OpenDesignChatSample: View {
    var body: some View {
        VStack(spacing: 14) {
            SampleUserTurn(
                text: "Thinking 的部分默认是折叠的,然后可以点击小三角展开",
                time: "09:38"
            )
            SampleAssistantTurn()
            SampleUserTurn(text: "让 UI 具有高级感，但不要把所有东西都变成玻璃。", time: "09:41")
            SampleAssistantBubble(
                text: [
                    "确实如此。Liquid Glass 材质仅适用于临时性的系统界面：如输入框辅助栏、",
                    "Sheet 弹窗以及工具栏组合。消息文本、代码块、",
                    "设置表单和列表应保持完全不透明，",
                    "以确保最高的阅读和交互可读性。"
                ].joined(),
                time: "09:41"
            )
        }
    }
}

struct SampleUserTurn: View {
    let text: String
    let time: String

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            ChatUserBubble(text: text)
                .frame(maxWidth: ChatLayout.userMaxWidth, alignment: .trailing)
            ChatMetaLine(text: time, alignment: .trailing)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}

struct SampleAssistantBubble: View {
    let text: String
    let time: String

    var body: some View {
        ChatAssistantStack {
            ChatAgentName()
            ChatAssistantText {
                Text(text)
            }
            ChatMetaLine(text: time)
        }
    }
}

struct SampleAssistantTurn: View {
    var body: some View {
        ChatAssistantStack {
            ChatAgentName()
            ToolTimelineSample()
            ChatReasoningCard(
                bodyText: "我正在整理界面状态、消息记录和工具结果，确保这次回复能继续当前上下文。",
                finishedSeconds: 3
            )
            ChatAssistantText {
                Text("已经实现了。代码里 `mutableStateOf(false)` 就是默认折叠，点击箭头展开。")
            }
            ChatMetaLine(text: "09:40")
        }
    }
}

struct ToolTimelineSample: View {
    private let steps: [ChatToolStepModel] = [
        .init(systemImage: "magnifyingglass", title: "搜索 iOS 设计规范", state: .done),
        .init(systemImage: "doc.text", title: "读取 DESIGN_SYSTEM.md", state: .done),
        .init(systemImage: "chevron.left.forwardslash.chevron.right", title: "生成 SwiftUI 代码", state: .active)
    ]

    var body: some View {
        ChatToolTimeline(steps: steps)
    }
}

// MARK: - Image attachment helpers

/// Compresses an image into a self-contained `data:` URL (sent to the model) plus a small
/// JPEG used only for the composer thumbnail. Downscaling keeps the persisted payload small.
enum ChatImageEncoder {
    static let maxSendDimension: CGFloat = 1536
    static let maxThumbnailDimension: CGFloat = 160

    static func encode(_ image: UIImage) -> (dataUrl: String, previewData: Data)? {
        let sized = downscaled(image, maxDimension: maxSendDimension)
        guard let jpeg = sized.jpegData(compressionQuality: 0.7) else { return nil }
        let dataUrl = "data:image/jpeg;base64,\(jpeg.base64EncodedString())"
        let thumb = downscaled(image, maxDimension: maxThumbnailDimension)
        let previewData = thumb.jpegData(compressionQuality: 0.6) ?? jpeg
        return (dataUrl, previewData)
    }

    private static func downscaled(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > maxDimension, longest > 0 else { return image }
        let scale = maxDimension / longest
        let target = CGSize(width: size.width * scale, height: size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }
}

/// Right-aligned "visual recognition in progress" indicator shown on the user side while
/// the OCR-fallback vision model reads the image, with a breathing animation.
struct VisionRecognitionIndicator: View {
    @State private var pulse = false

    var body: some View {
        HStack {
            Spacer(minLength: 40)
            HStack(spacing: 7) {
                Image(systemName: "sparkles")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .scaleEffect(pulse ? 1.18 : 0.86)
                    .opacity(pulse ? 1.0 : 0.55)
                Text("视觉识别中…")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(AmberTheme.surface, in: Capsule())
            .overlay(Capsule().stroke(AmberTheme.borderSoft, lineWidth: 1))
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }
}

/// Thin SwiftUI wrapper over `UIImagePickerController` for the 拍照 (camera) path.
struct CameraPicker: UIViewControllerRepresentable {
    let onComplete: (UIImage?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onComplete: onComplete) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onComplete: (UIImage?) -> Void
        init(onComplete: @escaping (UIImage?) -> Void) { self.onComplete = onComplete }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            onComplete(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onComplete(nil)
        }
    }
}
