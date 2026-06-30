import SwiftUI
import UIKit
import Shared
import PhotosUI

extension View {
    /// 原生 Liquid Glass 输入胶囊:`.regular` 提供半透折射,`.interactive()` 提供触控时的
    /// HDR 高光/透镜响应。低于 iOS 26 时回退到 `.thinMaterial`。
    /// 内部可见(非 private),以便模型议会等其他页面复用同一套原生输入胶囊样式。
    @ViewBuilder
    func composerDockGlass(cornerRadius: CGFloat) -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(.regular.interactive(), in: .rect(cornerRadius: cornerRadius))
        } else {
            background(.thinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .stroke(AmberTheme.border.opacity(0.42), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.06), radius: 12, y: 4)
        }
    }
}

struct ComposerIconButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var size: CGFloat = 34
    var symbolSize: CGFloat = 15
    var tint: Color = AmberTheme.foreground2
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: symbolSize, weight: .semibold))
                .foregroundStyle(prominent ? Color.white : tint)
                .frame(width: size, height: size)
                .contentShape(Circle())
                // 与输入条/发送键统一为原生 Liquid Glass:中性按钮用无色调玻璃,prominent 时染 tint。
                .modifier(ComposerDockCircleGlass(tint: prominent ? tint : nil))
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
                .contentShape(Circle())
                .modifier(ComposerDockCircleGlass(tint: nil))
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
        if textView.text != text {
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
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: symbolSize, weight: .semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: size, height: size)
                .contentShape(Circle())
                .background {
                    circleGlass
                }
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

    var body: some View {
        Button(action: action) {
            ZStack {
                if compactState.isActive {
                    Circle()
                        .stroke(Color.blue.opacity(0.16), lineWidth: 3)
                    Circle()
                        .trim(from: 0.05, to: 0.78)
                        .stroke(Color.blue, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(rotates ? 360 : 0))
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
            .contentShape(Circle())
            .animation(.easeOut(duration: 0.3), value: snapshot.contextFillFraction)
            .animation(.linear(duration: 1.0).repeatForever(autoreverses: false), value: rotates)
            .modifier(ComposerDockCircleGlass(tint: nil))
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.9, haptic: .selection))
        .onAppear { rotates = compactState.isActive }
        .onChange(of: compactState.isActive) { _, active in
            rotates = active
        }
        .accessibilityLabel("上下文统计")
        .accessibilityValue(compactState.isActive ? "正在压缩上下文" : "\(snapshot.messageCount) 条消息，\(snapshot.totalTokens) tokens")
    }
}

struct ComposerModelSheet: View {
    @Environment(\.dismiss) private var dismiss

    let sharedSettings: IOSSharedSettingsStore
    let currentModel: String
    let onPick: (ComposerModelOption) -> Void

    @State private var expandedProviderIDs: Set<String>

    private var providers: [ComposerProviderGroup] {
        _ = sharedSettings.revision
        return ComposerProviderGroup.currentConfiguration(sharedSettings: sharedSettings, currentModel: currentModel)
    }

    init(sharedSettings: IOSSharedSettingsStore, currentModel: String, onPick: @escaping (ComposerModelOption) -> Void) {
        self.sharedSettings = sharedSettings
        self.currentModel = currentModel
        self.onPick = onPick
        let selectedProviderID = Self.selectedProviderID(
            for: currentModel,
            providers: ComposerProviderGroup.currentConfiguration(sharedSettings: sharedSettings, currentModel: currentModel)
        )
        self._expandedProviderIDs = State(initialValue: Set([selectedProviderID]))
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(AmberTheme.border)
                .frame(width: 36, height: 5)
                .padding(.top, 10)
                .padding(.bottom, 6)

            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("选择模型")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text("选择当前配置要使用的 Model ID")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }

                Spacer()

                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .frame(width: 34, height: 34)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .amberGlass(cornerRadius: 17)
                .accessibilityLabel("关闭模型选择")
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 14)

            Divider()
                .overlay(AmberTheme.borderSoft)

            ScrollView {
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
                    .background(AmberTheme.background.opacity(0.72))
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
        .background(AmberTheme.background)
        .onAppear {
            expandedProviderIDs = Set([Self.selectedProviderID(for: currentModel, providers: providers)])
        }
    }

    private func toggleProvider(_ id: String) {
        withAnimation(.easeInOut(duration: 0.2)) {
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
                        .font(.subheadline.weight(providerContainsSelection ? .semibold : .regular))
                        .foregroundStyle(providerContainsSelection ? AmberTheme.accent : AmberTheme.foreground)

                    Spacer()

                    Image(systemName: "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(AmberTheme.muted)
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
                    .font(.subheadline.weight(isSelected ? .semibold : .regular))
                    .foregroundStyle(AmberTheme.foreground)
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
            }
            .padding(.leading, 36)
            .padding(.trailing, 16)
            .frame(minHeight: 46)
            .background(isSelected ? AmberTheme.accentTint : Color.clear)
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
        case .auto: "Auto"
        case .low: "Low"
        case .medium: "Medium"
        case .high: "High"
        case .xhigh: "X High"
        case .max: "Max"
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

    var body: some View {
        ComposerPopoverSurface(width: 248) {
            HStack(spacing: 14) {
                VStack {
                    ZStack {
                        Circle()
                            .stroke(AmberTheme.surface2, lineWidth: 8)
                        Circle()
                            // 用量环按已用/上限比例填充。上限按模型真实 contextWindow 计算,
                            // 模型未声明时回退 8K 视觉参考(见 snapshot.contextFillFraction)。
                            // 0 token 时环为空（诚实）。
                            .trim(from: 0, to: snapshot.contextFillFraction)
                            .stroke(AmberTheme.accent, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                    }
                    .frame(width: 52, height: 52)
                }
                .frame(width: 68)

                VStack(spacing: 8) {
                    ComposerContextCompactStatRow(label: "总消息数", value: "\(snapshot.messageCount)")
                    ComposerContextCompactStatRow(label: "总 token", value: "\(snapshot.totalTokens)")
                    ComposerContextCompactStatRow(label: "速度", value: speedText)
                    ComposerContextCompactStatRow(label: "缓存命中率", value: cacheHitRateText)
                }
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 18)
        }
    }

    private var cacheHitRateText: String {
        guard snapshot.promptTokens > 0 else {
            return "0%"
        }
        let rate = Double(snapshot.cachedTokens) / Double(snapshot.promptTokens)
        return "\(Int((rate * 100).rounded()))%"
    }

    private var speedText: String {
        guard let tokensPerSecond = snapshot.tokensPerSecond else {
            return "暂无"
        }
        return String(format: "%.1f token/s", tokensPerSecond)
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

struct ComposerPopoverHeader: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)

            Text(subtitle)
                .font(.caption2)
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
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
