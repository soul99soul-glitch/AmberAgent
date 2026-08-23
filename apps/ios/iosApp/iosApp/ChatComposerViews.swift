import SwiftUI
import UIKit
import Shared
import PhotosUI

extension View {
    /// 原生 Liquid Glass 输入胶囊:`.regular` 提供半透折射,`.interactive()` 提供触控时的
    /// HDR 高光/透镜响应。低于 iOS 26 时回退到 `.thinMaterial`。
    /// 内部可见(非 private),以便模型议会等其他页面复用同一套原生输入胶囊样式。
    ///
    /// `glassChrome.quieter` / `.solid` 时垫一层与首页同源的弱底，避免 appWide 网格在
    /// 输入条下折射发脏；`.standard` 不垫，保持经典包体观感。
    @ViewBuilder
    func composerDockGlass(cornerRadius: CGFloat) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        // Matches home `HomeGlassControlModifier`; 0 keeps classic packs unchanged.
        let pad: Double = {
            switch AmberThemeRuntime.shared.glassChrome {
            case .standard: 0
            case .quieter: 0.18
            case .solid: 0.52
            }
        }()
        if #available(iOS 26.0, *) {
            if pad > 0 {
                background(AmberTheme.homeGlassTop.opacity(pad), in: shape)
                    .glassEffect(.regular.interactive(), in: .rect(cornerRadius: cornerRadius))
            } else {
                glassEffect(.regular.interactive(), in: .rect(cornerRadius: cornerRadius))
            }
        } else {
            background(.thinMaterial, in: shape)
                .overlay {
                    shape.stroke(AmberTheme.border.opacity(0.42), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.06), radius: 12, y: 4)
        }
    }
}

struct ComposerIconButton: View {
    enum Glyph {
        case system(String)
        case koboyo(ChatKoboyoMark)
    }

    let glyph: Glyph
    let accessibilityLabel: String
    var size: CGFloat = 34
    var symbolSize: CGFloat = 15
    var tint: Color = AmberTheme.foreground2
    var prominent = false
    let action: () -> Void

    init(
        systemImage: String,
        accessibilityLabel: String,
        size: CGFloat = 34,
        symbolSize: CGFloat = 15,
        tint: Color = AmberTheme.foreground2,
        prominent: Bool = false,
        action: @escaping () -> Void
    ) {
        self.glyph = .system(systemImage)
        self.accessibilityLabel = accessibilityLabel
        self.size = size
        self.symbolSize = symbolSize
        self.tint = tint
        self.prominent = prominent
        self.action = action
    }

    init(
        koboyo: ChatKoboyoMark,
        accessibilityLabel: String,
        size: CGFloat = 34,
        symbolSize: CGFloat = 15,
        tint: Color = AmberTheme.foreground2,
        prominent: Bool = false,
        action: @escaping () -> Void
    ) {
        self.glyph = .koboyo(koboyo)
        self.accessibilityLabel = accessibilityLabel
        self.size = size
        self.symbolSize = symbolSize
        self.tint = tint
        self.prominent = prominent
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Group {
                switch glyph {
                case .system(let name):
                    Image(systemName: name)
                        .font(.system(size: symbolSize, weight: .semibold))
                case .koboyo(let mark):
                    ChatKoboyoIcon(mark, size: symbolSize)
                }
            }
            .foregroundStyle(prominent ? Color.white : tint)
            .frame(width: size, height: size)
            // 与输入条/发送键统一为原生 Liquid Glass:中性按钮用无色调玻璃,prominent 时染 tint。
            .modifier(ComposerDockCircleGlass(tint: prominent ? tint : nil))
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Circle())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.9, haptic: .selection))
        .accessibilityLabel(accessibilityLabel)
    }
}

/// 「回到底部」悬浮玻璃圆键 —— 上滑看历史时浮现在输入框正上方,点击跳回最新消息。
/// 复用 composer 的原生 Liquid Glass 圆形样式,保持视觉统一。
struct ChatScrollToBottomButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "chevron.down")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: 38, height: 38)
                .modifier(ComposerDockCircleGlass(tint: nil))
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Circle())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.9, haptic: .selection))
        .accessibilityLabel("回到最新消息")
    }
}

/// Apple Music dock 风格的独立圆形发送/停止键 —— 与输入胶囊分离的原生 Liquid Glass。
/// 启用时给玻璃染上 accent 色调,触控时由 `.interactive()` 产生 HDR 透镜高光。
/// 内部可见(非 private),以便模型议会等其他页面复用同一颗原生发送键。
struct ComposerDockSendButton: View {
    var isLoading: Bool
    var sendEnabled: Bool
    var diameter: CGFloat = 54
    let onSend: () -> Void
    let onStop: () -> Void

    private var isActionable: Bool { isLoading || sendEnabled }

    var body: some View {
        Button {
            if isLoading { onStop() } else { onSend() }
        } label: {
            Image(systemName: isLoading ? "stop.fill" : "arrow.up")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(iconColor)
                .frame(width: diameter, height: diameter)
                .contentShape(Circle())
                .modifier(ComposerDockCircleGlass(tint: glassTint))
                .contentTransition(.symbolEffect(.replace.downUp))
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.88, haptic: isLoading ? .mediumImpact : .lightImpact))
        .disabled(!isActionable)
        .animation(.easeOut(duration: 0.18), value: isLoading)
        .animation(.easeOut(duration: 0.18), value: sendEnabled)
        .accessibilityLabel(isLoading ? "停止生成" : "发送消息")
    }

    private var iconColor: Color {
        if isLoading { return .white }
        // 启用时白色箭头叠在 accent 玻璃上;禁用时用 muted(与左侧「+」同档),
        // 比更淡的 muted2 在深色玻璃上更清晰,不再暗淡。
        return sendEnabled ? .white : AmberTheme.muted
    }

    private var glassTint: Color? {
        if isLoading { return AmberTheme.accentRed }
        return sendEnabled ? AmberTheme.accent : nil
    }
}

@MainActor
final class ComposerInputController {
    weak var textView: UITextView?

    func currentText() -> String? {
        textView?.text
    }

    func committedText() -> String? {
        guard let textView else { return nil }
        textView.unmarkText()
        return textView.text
    }
}

struct ComposerInputTextView: UIViewRepresentable {
    @Binding var text: String
    @Binding var height: CGFloat
    var isFocused: Binding<Bool>
    var isEnabled: Bool
    var sendOnEnter: Bool
    var controller: ComposerInputController
    var onSubmit: () -> Void

    private let minHeight: CGFloat = 40
    private let maxLines: CGFloat = 5

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        controller.textView = textView
        textView.delegate = context.coordinator
        textView.backgroundColor = .clear
        textView.font = .preferredFont(forTextStyle: .body)
        textView.adjustsFontForContentSizeCategory = true
        textView.textColor = .label
        textView.tintColor = UIColor(AmberTheme.accent)
        textView.textContainerInset = UIEdgeInsets(top: 8, left: 0, bottom: 8, right: 0)
        textView.textContainer.lineFragmentPadding = 0
        textView.isScrollEnabled = false
        textView.keyboardDismissMode = .interactive
        textView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        context.coordinator.parent = self
        controller.textView = textView
        if textView.markedTextRange == nil, textView.text != text {
            textView.text = text
        }
        textView.isEditable = isEnabled
        textView.isSelectable = isEnabled
        textView.returnKeyType = sendOnEnter ? .send : .default
        if !isEnabled, textView.isFirstResponder {
            textView.resignFirstResponder()
        } else if isFocused.wrappedValue, !textView.isFirstResponder {
            textView.becomeFirstResponder()
        }
        context.coordinator.updateHeight(for: textView)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    static func dismantleUIView(_ uiView: UITextView, coordinator: Coordinator) {
        if coordinator.controller.textView === uiView {
            coordinator.controller.textView = nil
        }
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: ComposerInputTextView
        let controller: ComposerInputController

        init(parent: ComposerInputTextView) {
            self.parent = parent
            self.controller = parent.controller
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            parent.isFocused.wrappedValue = true
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            parent.isFocused.wrappedValue = false
        }

        func textViewDidChange(_ textView: UITextView) {
            if parent.text != textView.text {
                parent.text = textView.text
            }
            updateHeight(for: textView)
        }

        func textView(
            _ textView: UITextView,
            shouldChangeTextIn range: NSRange,
            replacementText replacement: String
        ) -> Bool {
            guard replacement == "\n", parent.sendOnEnter else { return true }
            if textView.markedTextRange != nil {
                return true
            }
            parent.onSubmit()
            return false
        }

        func updateHeight(for textView: UITextView) {
            let width = textView.bounds.width
            guard width > 0 else { return }
            let fittingSize = textView.sizeThatFits(
                CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)
            )
            let font = textView.font ?? .preferredFont(forTextStyle: .body)
            let maxHeight = ceil(font.lineHeight * parent.maxLines)
                + textView.textContainerInset.top
                + textView.textContainerInset.bottom
            let nextHeight = min(max(parent.minHeight, ceil(fittingSize.height)), maxHeight)
            let shouldScroll = fittingSize.height > maxHeight + 0.5
            DispatchQueue.main.async {
                if abs(self.parent.height - nextHeight) > 0.5 {
                    self.parent.height = nextHeight
                }
                if textView.isScrollEnabled != shouldScroll {
                    textView.isScrollEnabled = shouldScroll
                }
            }
        }
    }
}

struct ComposerDockCircleGlass: ViewModifier {
    var tint: Color?

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            // 单一 glassEffect 调用 —— 只让可空的 tint 参数在 accent ↔ nil 间变化,保持视图身份
            // 不变。若按 tint 有无拆成两条分支,SwiftUI 会移除/插入两个不同身份的玻璃视图并做
            // 交叉淡入,删字回到清玻璃时会闪过一帧发白。
            content.glassEffect(.regular.tint(tint).interactive(), in: Circle())
        } else {
            content
                .background {
                    Circle().fill(tint.map { AnyShapeStyle($0) } ?? AnyShapeStyle(.thinMaterial))
                }
                .overlay {
                    Circle().stroke(AmberTheme.border.opacity(0.42), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.06), radius: 12, y: 4)
        }
    }
}

struct ChatToolbarIconButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var size: CGFloat
    var symbolSize: CGFloat
    /// Top-bar chrome stays theme ink, not accent — accent is for primary CTAs.
    var tint: Color = AmberTheme.foreground
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                circleGlass

                Image(systemName: systemImage)
                    .symbolRenderingMode(.monochrome)
                    .font(.system(size: symbolSize, weight: .semibold))
                    .foregroundStyle(tint)
            }
            .frame(width: size, height: size)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Circle())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.9, haptic: .lightImpact))
        .accessibilityLabel(accessibilityLabel)
    }

    @ViewBuilder
    private var circleGlass: some View {
        if #available(iOS 26.0, *) {
            Circle()
                .fill(AmberTheme.glass.opacity(0.16))
                .glassEffect(.regular.interactive(), in: Circle())
        } else {
            Circle()
                .fill(.ultraThinMaterial)
                .overlay {
                    Circle()
                        .stroke(AmberTheme.border.opacity(0.28), lineWidth: 0.5)
                }
        }
    }
}

struct ContextRingButton: View {
    let snapshot: ChatContextSnapshot
    let compactState: ChatContextCompactState
    let action: () -> Void
    @State private var rotates = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            ZStack {
                if compactState.isActive {
                    Circle()
                        .stroke(Color.blue.opacity(0.16), lineWidth: 3)
                    Circle()
                        .trim(from: 0.05, to: 0.78)
                        .stroke(Color.blue, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(reduceMotion ? 0 : (rotates ? 360 : 0)))
                    Image(systemName: "shippingbox")
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(Color.blue)
                } else {
                    // 轨道:强调色(用户可调的主题色,不一定是琥珀)的「很浅」版本,由 mix 混白得到。
                    // 不能用 accent.opacity(...):半透明强调色会和背后的玻璃混色,深色玻璃会把它压暗,
                    // 所以调透明度看着都一样。mix(with:.white) 才是真正把强调色调浅成不透明、背景无关的浅色。
                    Circle()
                        .stroke(AmberTheme.accent.mix(with: .white, by: 0.82), lineWidth: 3)
                    // 进度:随上下文增长用强调色覆盖填充,呈现增长效果。填充上限按模型真实
                    // contextWindow 计算(见 snapshot.contextFillFraction)。
                    Circle()
                        .trim(from: 0, to: snapshot.contextFillFraction)
                        .stroke(AmberTheme.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
            }
            .frame(width: 18, height: 18)
            .frame(width: 34, height: 34)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.3), value: snapshot.contextFillFraction)
            .animation(
                reduceMotion ? nil : .linear(duration: 1.0).repeatForever(autoreverses: false),
                value: rotates
            )
            .modifier(ComposerDockCircleGlass(tint: nil))
            .frame(width: 44, height: 44)
            .contentShape(Circle())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.9, haptic: .selection))
        .onAppear { rotates = compactState.isActive && !reduceMotion }
        .onChange(of: compactState.isActive) { _, active in
            rotates = active && !reduceMotion
        }
        .onChange(of: reduceMotion) { _, shouldReduceMotion in
            rotates = compactState.isActive && !shouldReduceMotion
        }
        .accessibilityLabel("上下文统计")
        .accessibilityValue(compactState.isActive ? "正在压缩上下文" : snapshot.occupancyText)
    }
}

struct ComposerModelSheet: View {
    @Environment(\.dismiss) private var dismiss

    let sharedSettings: IOSSharedSettingsStore
    let currentModel: String
    let title: String
    let fallbackTitle: String?
    let onFallback: (() -> Void)?
    let dismissesAfterFallback: Bool
    let onPick: (ComposerModelOption) -> Void

    @State private var expandedProviderIDs: Set<String>

    private var providers: [ComposerProviderGroup] {
        _ = sharedSettings.revision
        return ComposerProviderGroup.currentConfiguration(sharedSettings: sharedSettings, currentModel: currentModel)
    }

    init(
        sharedSettings: IOSSharedSettingsStore,
        currentModel: String,
        title: String = "选择模型",
        fallbackTitle: String? = nil,
        onFallback: (() -> Void)? = nil,
        dismissesAfterFallback: Bool = true,
        onPick: @escaping (ComposerModelOption) -> Void
    ) {
        self.sharedSettings = sharedSettings
        self.currentModel = currentModel
        self.title = title
        self.fallbackTitle = fallbackTitle
        self.onFallback = onFallback
        self.dismissesAfterFallback = dismissesAfterFallback
        self.onPick = onPick
        let selectedProviderID = Self.selectedProviderID(
            for: currentModel,
            providers: ComposerProviderGroup.currentConfiguration(sharedSettings: sharedSettings, currentModel: currentModel)
        )
        self._expandedProviderIDs = State(initialValue: Set([selectedProviderID]))
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(AmberTheme.foreground)

                Spacer()

                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .frame(width: 34, height: 34)
                        .contentShape(Circle())
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .accessibilityLabel("关闭模型选择")
            }
            .padding(.horizontal, 20)
            .padding(.top, 18)
            .padding(.bottom, 10)

            Divider()
                .overlay(AmberTheme.borderSoft)

            ScrollView {
                if let fallbackTitle, let onFallback {
                    Button {
                        onFallback()
                        if dismissesAfterFallback {
                            dismiss()
                        }
                    } label: {
                        Label(fallbackTitle, systemImage: "arrow.trianglehead.2.clockwise.rotate.90")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 16)
                    .padding(.top, 14)
                }

                if providers.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "cpu")
                            .font(.system(size: 24, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)
                        Text("还没有可用模型")
                            .font(.headline)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("请先在服务商详情自动获取或手动添加模型。")
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 38)
                    .padding(.horizontal, 16)
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(providers.enumerated()), id: \.element.id) { index, provider in
                            ComposerProviderGroupView(
                                provider: provider,
                                currentModel: currentModel,
                                isExpanded: expandedProviderIDs.contains(provider.id),
                                onToggle: {
                                    toggleProvider(provider.id)
                                },
                                onPick: { model in
                                    onPick(model)
                                }
                            )

                            if index < providers.count - 1 {
                                Divider()
                                    .overlay(AmberTheme.borderSoft)
                            }
                        }
                    }
                    .background(AmberTheme.glass)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 14)
                    .padding(.bottom, 28)
                }
            }
            .scrollIndicators(.hidden)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .onAppear {
            expandedProviderIDs = Set([Self.selectedProviderID(for: currentModel, providers: providers)])
        }
    }

    private func toggleProvider(_ id: String) {
        withAnimation(.snappy(duration: 0.25)) {
            if expandedProviderIDs.contains(id) {
                expandedProviderIDs.remove(id)
            } else {
                expandedProviderIDs.insert(id)
            }
        }
    }

    private static func selectedProviderID(for currentModel: String, providers: [ComposerProviderGroup]) -> String {
        providers.first { provider in
            provider.models.contains { $0.matches(currentModel) }
        }?.id ?? providers.first?.id ?? "current"
    }
}

struct ComposerProviderGroupView: View {
    let provider: ComposerProviderGroup
    let currentModel: String
    let isExpanded: Bool
    let onToggle: () -> Void
    let onPick: (ComposerModelOption) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Button(action: onToggle) {
                HStack(spacing: 10) {
                    Text(provider.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(providerContainsSelection ? AmberTheme.accent : AmberTheme.foreground)

                    Spacer()

                    Image(systemName: "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AmberTheme.muted2)
                        .rotationEffect(.degrees(isExpanded ? 180 : 0))
                }
                .padding(.horizontal, 16)
                .frame(height: 50)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(provider.name) 模型分组")
            .accessibilityValue(isExpanded ? "已展开" : "已收起")

            if isExpanded {
                Divider()
                    .overlay(AmberTheme.borderSoft)
                    .padding(.leading, 16)

                VStack(spacing: 0) {
                    ForEach(Array(provider.models.enumerated()), id: \.element.id) { index, model in
                        if index > 0 {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 36)
                        }

                        ComposerModelRow(
                            model: model,
                            isSelected: model.matches(currentModel)
                        ) {
                            onPick(model)
                        }
                    }
                }
                .padding(.bottom, 6)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private var providerContainsSelection: Bool {
        provider.models.contains { $0.matches(currentModel) }
    }
}

struct ComposerModelRow: View {
    let model: ComposerModelOption
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Text(model.name)
                    .font(.subheadline.weight(isSelected ? .medium : .regular))
                    .foregroundStyle(isSelected ? AmberTheme.foreground : AmberTheme.foreground2)
                    .lineLimit(1)

                if let context = model.context {
                    Text(context)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AmberTheme.muted)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(AmberTheme.surface2.opacity(0.72), in: Capsule())
                        .layoutPriority(1)
                }

                Spacer(minLength: 8)

                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(AmberTheme.accent)
                }
            }
            .padding(.leading, 36)
            .padding(.trailing, 16)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("选择模型 \(model.name)")
        .accessibilityValue(isSelected ? "已选" : "未选")
    }
}

struct ComposerProviderGroup: Identifiable {
    let id: String
    let name: String
    let models: [ComposerModelOption]

    static func currentConfiguration(sharedSettings: IOSSharedSettingsStore, currentModel: String) -> [ComposerProviderGroup] {
        sharedSettings.snapshot.providers.compactMap { provider in
            guard provider.enabled, ChatProviderConfiguration.supportsChatStreaming(provider) else { return nil }
            let models = provider.models
                .filter { $0.type == ModelType.chat }
                .map { model in
                    ComposerModelOption(
                        id: model.id.description(),
                        name: displayName(for: model),
                        modelId: model.modelId,
                        context: contextLabel(for: model)
                    )
                }
            guard !models.isEmpty else { return nil }
            return ComposerProviderGroup(id: provider.id.description(), name: provider.name, models: models)
        }
    }

    private static func displayName(for model: Model) -> String {
        let name = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? model.modelId : name
    }

    private static func contextLabel(for model: Model) -> String? {
        guard let tokens = model.contextWindowTokens else { return nil }
        return formatContextWindow(Int(truncating: tokens))
    }

    /// 紧凑显示上下文窗口:≥100万写 1M(必要时带一位小数),≥1000 写 XK,否则原数。
    static func formatContextWindow(_ tokens: Int) -> String {
        if tokens >= 1_000_000 {
            return trimmedDecimal(Double(tokens) / 1_000_000) + "M"
        }
        if tokens >= 1_000 {
            return "\(Int((Double(tokens) / 1_000).rounded()))K"
        }
        return "\(tokens)"
    }

    private static func trimmedDecimal(_ value: Double) -> String {
        let rounded = (value * 10).rounded() / 10
        if rounded == rounded.rounded() {
            return "\(Int(rounded))"
        }
        return String(format: "%.1f", rounded)
    }
}

struct ComposerModelOption: Identifiable, Hashable {
    let id: String
    let name: String
    let modelId: String
    let context: String?

    func matches(_ value: String) -> Bool {
        let normalizedValue = Self.normalize(value)
        return Self.normalize(id) == normalizedValue ||
            Self.normalize(name) == normalizedValue ||
            Self.normalize(modelId) == normalizedValue
    }

    private static func normalize(_ value: String) -> String {
        value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }
}

enum ComposerReasoningOption: String, CaseIterable, Identifiable {
    case off
    case auto
    case low
    case medium
    case high
    case xhigh
    case max

    var id: String { rawValue }

    var title: String {
        switch self {
        case .off: "关闭"
        case .auto: "自动"
        case .low: "低"
        case .medium: "中"
        case .high: "高"
        case .xhigh: "极高"
        case .max: "最高"
        }
    }

    var reasoningLevel: ReasoningLevel {
        switch self {
        case .off: .off
        case .auto: .auto_
        case .low: .low
        case .medium: .medium
        case .high: .high
        case .xhigh: .xhigh
        case .max: .max
        }
    }

    init(reasoningLevel: ReasoningLevel) {
        switch reasoningLevel.name.lowercased() {
        case "auto": self = .auto
        case "low": self = .low
        case "medium": self = .medium
        case "high": self = .high
        case "xhigh": self = .xhigh
        case "max": self = .max
        default: self = .off
        }
    }
}

struct ComposerThinkingPanel: View {
    @Binding var selectedOption: ComposerReasoningOption
    let options: [ComposerReasoningOption]
    let isAvailable: Bool
    let onPick: (ComposerReasoningOption) -> Void

    var body: some View {
        ComposerPopoverSurface(width: 180) {
            if isAvailable {
                VStack(spacing: 0) {
                    ForEach(Array(options.enumerated()), id: \.element.id) { index, option in
                        ComposerPopoverDivider(index: index)

                        Button {
                            selectedOption = option
                            onPick(option)
                        } label: {
                            HStack {
                                Text(option.title)
                                    .font(.subheadline.weight(option == selectedOption ? .semibold : .regular))
                                    .foregroundStyle(option == selectedOption ? AmberTheme.accent : AmberTheme.foreground)

                                Spacer()

                                if option == selectedOption {
                                    Image(systemName: "checkmark")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundStyle(AmberTheme.accent)
                                }
                            }
                            .padding(.horizontal, 16)
                            .frame(height: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Reasoning 未启用")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text("当前模型未标记支持 Reasoning")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(14)
            }
        }
    }
}

struct ComposerContextPanel: View {
    let snapshot: ChatContextSnapshot
    let novelInjection: NovelInjectionPanelModel?

    init(
        snapshot: ChatContextSnapshot,
        novelInjection: NovelInjectionPanelModel? = nil
    ) {
        self.snapshot = snapshot
        self.novelInjection = novelInjection
    }

    var body: some View {
        ComposerPopoverSurface(width: novelInjection == nil ? 248 : 300) {
            VStack(spacing: 14) {
                HStack(spacing: 14) {
                    VStack {
                        ZStack {
                            Circle()
                                .stroke(AmberTheme.surface2, lineWidth: 8)
                            Circle()
                                // 下一轮预计装载量 / 模型窗口。0 时空环。
                                .trim(from: 0, to: snapshot.contextFillFraction)
                                .stroke(
                                    AmberTheme.accent,
                                    style: StrokeStyle(lineWidth: 8, lineCap: .round)
                                )
                                .rotationEffect(.degrees(-90))
                        }
                        .frame(width: 52, height: 52)
                    }
                    .frame(width: 68)

                    VStack(spacing: 8) {
                        ComposerContextCompactStatRow(
                            label: "总消息数",
                            value: "\(snapshot.messageCount)"
                        )
                        if let novelInjection, novelInjection.hasReceipt {
                            ComposerContextCompactStatRow(
                                label: "本次注入",
                                value: "\(ChatContextSnapshot.formatTokenCount(novelInjection.estimatedInputTokens)) / \(ChatContextSnapshot.formatTokenCount(novelInjection.maxEstimatedInputTokens))"
                            )
                        }
                        ComposerContextCompactStatRow(
                            label: "上下文",
                            value: snapshot.occupancyText
                        )
                        ComposerContextCompactStatRow(label: "速度", value: snapshot.speedText)
                        ComposerContextCompactStatRow(label: "缓存命中率", value: snapshot.cacheHitRateText)
                    }
                    .frame(maxWidth: .infinity)
                }

                if let novelInjection {
                    Divider()
                    NovelInjectionPanelDetails(model: novelInjection)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 18)
        }
    }
}

private struct NovelInjectionPanelDetails: View {
    let model: NovelInjectionPanelModel

    var body: some View {
        if model.hasReceipt {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 7) {
                    Text("设定条目")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    if model.materials.isEmpty {
                        Text("本次未注入设定条目")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    } else {
                        ForEach(model.materials) { material in
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Image(systemName: "book.closed")
                                    .font(.caption2)
                                    .foregroundStyle(AmberTheme.accent)
                                Text(material.title)
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.foreground)
                                    .lineLimit(2)
                                Spacer(minLength: 8)
                                Text(material.kindTitle)
                                    .font(.caption2)
                                    .foregroundStyle(AmberTheme.muted)
                                    .lineLimit(1)
                            }
                        }
                    }
                }

                VStack(spacing: 8) {
                    ComposerContextCompactStatRow(
                        label: "剧情状态",
                        value: model.includesPlotState ? "已携带" : "未携带"
                    )
                    ComposerContextCompactStatRow(
                        label: "会话窗口",
                        value: "\(model.recentMessageRoundCount) 轮"
                    )
                    if model.budgetExcludedItemCount > 0 {
                        ComposerContextCompactStatRow(
                            label: "预算未纳入",
                            value: "\(model.budgetExcludedItemCount) 项"
                        )
                    }
                }
            }
        } else {
            Text("尚无生成上下文记录")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct ComposerContextCompactStatRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)

            Spacer(minLength: 10)

            Text(value)
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
    }
}

struct ComposerPopoverDivider: View {
    let index: Int

    var body: some View {
        if index > 0 {
            Divider()
                .overlay(AmberTheme.borderSoft)
                .padding(.leading, 44)
        }
    }
}

struct ComposerPopoverSurface<Content: View>: View {
    let width: CGFloat
    let content: Content

    init(width: CGFloat, @ViewBuilder content: () -> Content) {
        self.width = width
        self.content = content()
    }

    var body: some View {
        content
            .frame(width: width)
            .amberGlass(cornerRadius: 14, interactive: false)
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(AmberTheme.border.opacity(0.75), lineWidth: 0.5)
            }
            .shadow(color: .black.opacity(0.12), radius: 22, y: 5)
    }
}

// MARK: - Shared composer attachment controls (Chat + Council)

/// 输入胶囊左侧「+」：展开时旋转 45° 变 ×，解析中可换成 paperclip。
/// Chat / 模型议会共用同一触感与尺寸，避免两套 + 键。
struct ComposerAttachToggleButton: View {
    var isExpanded: Bool
    var isBusy: Bool = false
    var isDisabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: isBusy ? "paperclip.circle.fill" : "plus")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(AmberTheme.muted)
                .frame(width: 32, height: 32)
                .contentShape(Circle())
                .rotationEffect(.degrees(isExpanded ? 45 : 0))
                .contentTransition(.symbolEffect(.replace.downUp))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.88, haptic: .lightImpact))
        .disabled(isDisabled)
        .accessibilityLabel(isExpanded ? "关闭附件" : "添加附件")
    }
}

/// Liquid Glass 附件菜单：拍照 / 照片 / 文件（与 Chat 一致）。
struct ComposerAttachmentGlassPanel: View {
    var isDisabled: Bool = false
    let onCamera: () -> Void
    let onPhotos: () -> Void
    let onFiles: () -> Void
    /// 选中某一行后由父级收起展开态。
    var onDismiss: () -> Void = {}

    var body: some View {
        VStack(spacing: 0) {
            row(title: "拍照", icon: "camera", action: onCamera)
            divider
            row(title: "照片", icon: "photo.on.rectangle", action: onPhotos)
            divider
            row(title: "文件", icon: "doc", action: onFiles)
        }
        .frame(width: 220)
        .clipShape(.rect(cornerRadius: 22))
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.leading, 2)
        .padding(.bottom, 2)
    }

    private var divider: some View {
        Divider().overlay(AmberTheme.borderSoft).padding(.leading, 52)
    }

    private func row(title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button {
            withAnimation(.bouncy(duration: 0.36, extraBounce: 0.1)) { onDismiss() }
            action()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .regular))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 24)
                Text(title)
                    .font(.system(size: 16, weight: .regular))
                    .foregroundStyle(AmberTheme.foreground)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .frame(height: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
    }
}

/// 待发送图片：56pt 圆角缩略图 + 右上角删除（Chat 同款）。
struct ComposerPendingImageStrip: View {
    struct Item: Identifiable {
        let id: UUID
        let previewData: Data
    }

    let items: [Item]
    let onRemove: (UUID) -> Void
    /// Optional status under the strip (blocked / fallback / preparing).
    var status: ComposerAttachmentStatus? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(items) { item in
                        ZStack(alignment: .topTrailing) {
                            if let ui = UIImage(data: item.previewData) {
                                Image(uiImage: ui)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 56, height: 56)
                                    .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
                            }
                            Button {
                                onRemove(item.id)
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 16))
                                    .foregroundStyle(.white, .black.opacity(0.45))
                                    .padding(3)
                                    .frame(width: 44, height: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("移除图片")
                        }
                    }
                }
                .padding(.horizontal, 2)
            }
            if let status {
                ComposerAttachmentStatusLabel(status: status)
            }
        }
    }
}

/// 待发送文件卡片：文件名 + 字节摘要 + 可选脚注（Chat 同款 thinMaterial）。
struct ComposerPendingFileCard: View {
    let fileName: String
    var byteSummary: String? = nil
    var isTruncated: Bool = false
    var footnote: String? = nil
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 8) {
                Label(fileName, systemImage: "doc.text")
                    .font(.caption)
                    .lineLimit(1)
                if let byteSummary {
                    Text(isTruncated ? "\(byteSummary) · 已截断" : byteSummary)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Button(action: onRemove) {
                    Image(systemName: "xmark.circle.fill")
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("移除文件 \(fileName)")
            }
            if let footnote, !footnote.isEmpty {
                Text(footnote)
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.muted)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

enum ComposerAttachmentStatus: Equatable {
    case muted(String, systemImage: String = "info.circle")
    case warning(String, systemImage: String = "exclamationmark.triangle.fill")
    case error(String)
    case preparing(String)
}

struct ComposerAttachmentStatusLabel: View {
    let status: ComposerAttachmentStatus

    var body: some View {
        switch status {
        case let .muted(message, systemImage):
            Label(message, systemImage: systemImage)
                .font(.caption2)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(2)
                .padding(.horizontal, 2)
        case let .warning(message, systemImage):
            Label(message, systemImage: systemImage)
                .font(.caption2)
                .foregroundStyle(AmberTheme.accentAmber)
                .lineLimit(2)
                .padding(.horizontal, 2)
        case let .error(message):
            Text(message)
                .font(.caption)
                .foregroundStyle(.red)
                .lineLimit(2)
                .padding(.horizontal, 2)
        case let .preparing(message):
            HStack(spacing: 8) {
                ProgressView()
                    .controlSize(.small)
                Text(message)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            .padding(.horizontal, 2)
        }
    }
}
