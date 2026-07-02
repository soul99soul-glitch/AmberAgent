import SwiftUI
import UIKit
import Shared

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
        let hasInitialBodyText = !bodyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        self._isExpanded = State(initialValue: hasInitialBodyText && (isThinking ? true : !autoCloseThinking))
    }

    private var levelSuffix: String {
        guard let levelLabel, !levelLabel.isEmpty else { return "" }
        return " · \(levelLabel)"
    }

    private var hasBodyText: Bool {
        !bodyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
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
                    Image(systemName: "clock")
                        .font(.system(size: 12.5, weight: .semibold))
                        .foregroundStyle(AmberTheme.accentAmber)
                        .symbolEffect(.variableColor.iterative.reversing, isActive: isThinking && !reduceMotion)

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
                Text(bodyText)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .padding(.top, 2)
                    .padding(.bottom, 10)
                    .transaction { transaction in
                        transaction.animation = nil
                    }
                // Fade in place (no upward move) so the collapsing text doesn't slide up over
                // the header.
                .transition(.opacity)
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
        .onChange(of: bodyText) { _, newValue in
            guard isThinking, !userToggled else { return }
            if !newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                isExpanded = true
            }
        }
        .onChange(of: isThinking) { _, nowThinking in
            guard !userToggled else { return }
            if nowThinking {
                isExpanded = hasBodyText
            } else if autoCloseThinking {
                var transaction = Transaction()
                transaction.animation = nil
                withTransaction(transaction) {
                    isExpanded = false
                }
            }
        }
    }
}

struct ChatEmptyState: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "sparkles")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(AmberTheme.accent)

            Text("Amber")
                .font(.title3.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)

            Text("准备好了")
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 96)
        .padding(.bottom, 180)
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
