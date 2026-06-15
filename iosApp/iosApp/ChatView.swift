import SwiftUI
import Shared

private enum ComposerPanel: String, Identifiable {
    case thinking
    case context

    var id: String { rawValue }
}

struct ChatView: View {

    let settingsStore: SettingsStore
    @State private var viewModel: ChatViewModel
    @State private var activeComposerPanel: ComposerPanel?
    @State private var isModelSheetPresented = false
    @State private var previewModel: String?
    @State private var selectedThinkingLevel = "关闭"
    @FocusState private var isInputFocused: Bool
    @Environment(\.dismiss) private var dismiss

    init(settingsStore: SettingsStore, localToolExecutor: IOSLocalToolExecutor? = nil) {
        self.settingsStore = settingsStore
        self._viewModel = State(
            initialValue: ChatViewModel(
                settingsStore: settingsStore,
                localToolExecutor: localToolExecutor
            )
        )
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                navBar
                messageList
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            inputBar
        }
        .sheet(isPresented: $isModelSheetPresented) {
            ComposerModelSheet(currentModel: composerModelLabel) { model in
                previewModel = model.name
                isModelSheetPresented = false
            }
            .presentationDetents([.fraction(0.72), .large])
            .presentationDragIndicator(.hidden)
            .presentationCornerRadius(28)
            .presentationBackground(AmberTheme.glassStrong)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var navBar: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 38, symbolSize: 18) {
                dismiss()
            }

            Spacer()

            Text("问候")
                .font(.headline)
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassCircleButton(systemImage: "square.and.pencil", accessibilityLabel: "新建对话", size: 38, symbolSize: 16) {
                viewModel.cancelGeneration()
                viewModel.messages.removeAll()
                viewModel.inputText = ""
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 6)
    }

    // MARK: - Message List

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 14) {
                    if viewModel.messages.isEmpty {
                        OpenDesignChatSample()
                    } else {
                        ForEach(viewModel.messages, id: \.id) { message in
                            MessageBubbleView(message: message)
                                .id(message.id)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .padding(.bottom, 18)
            }
            .onChange(of: viewModel.messages.count) { _, newCount in
                guard newCount > 0 else { return }
                withAnimation {
                    if let lastId = viewModel.messages.last?.id {
                        proxy.scrollTo(lastId, anchor: .bottom)
                    }
                }
            }
        }
    }

    // MARK: - Input Bar

    private var inputBar: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let preview = viewModel.pendingSelectedFilePreview {
                HStack(spacing: 8) {
                    Label(preview.fileName, systemImage: "doc.text")
                        .font(.caption)
                        .lineLimit(1)
                    Text("\(preview.bytesRead) bytes")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button {
                        viewModel.clearPendingSelectedFilePreview()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(.thinMaterial, in: Capsule())
            }

            if let error = viewModel.selectedFileContextError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }

            VStack(spacing: 8) {
                HStack(alignment: .center, spacing: 8) {
                    Button {
                        Task {
                            await viewModel.attachSelectedFilePreviewToNextMessage()
                        }
                    } label: {
                        Image(systemName: viewModel.isAttachingSelectedFile ? "paperclip.circle.fill" : "plus")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(AmberTheme.muted)
                            .frame(width: 28, height: 28)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.isLoading || viewModel.isAttachingSelectedFile)

                    TextField("发消息给 Amber...", text: $viewModel.inputText, axis: .vertical)
                        .lineLimit(1...5)
                        .textFieldStyle(.plain)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .frame(minHeight: 38)
                        .focused($isInputFocused)

                    if viewModel.isLoading {
                        Button {
                            viewModel.cancelGeneration()
                        } label: {
                            Image(systemName: "stop.fill")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 32, height: 32)
                                .background(AmberTheme.accentRed, in: Circle())
                        }
                        .buttonStyle(.plain)
                    } else {
                        Button {
                            viewModel.sendMessage()
                        } label: {
                            Image(systemName: "arrow.up")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundStyle(sendEnabled ? .white : AmberTheme.muted2)
                                .frame(width: 32, height: 32)
                                .background(sendEnabled ? AmberTheme.accent : AmberTheme.surface2, in: Circle())
                        }
                        .buttonStyle(.plain)
                        .disabled(!sendEnabled)
                    }
                }
                .padding(.leading, 8)
                .padding(.trailing, 6)
                .padding(.vertical, 6)
                .overlay {
                    Capsule()
                        .stroke(
                            AmberTheme.border.opacity(0.58),
                            lineWidth: 0.5
                        )
                }
                .amberGlass(cornerRadius: 25)

                if showsComposerMeta {
                    HStack {
                        Button {
                            activeComposerPanel = nil
                            isModelSheetPresented = true
                        } label: {
                            Text(composerModelLabel)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground2)
                                .lineLimit(1)
                                .padding(.horizontal, 12)
                                .frame(height: 30)
                        }
                        .buttonStyle(.plain)
                        .amberGlass(cornerRadius: 15)
                        .accessibilityLabel("切换模型，当前 \(composerModelLabel)")

                        Spacer()

                        HStack(spacing: 8) {
                            AmberGlassCircleButton(
                                systemImage: "sparkles",
                                accessibilityLabel: "设置思考等级",
                                size: 34,
                                symbolSize: 15
                            ) {
                                toggleComposerPanel(.thinking)
                            }
                            .popover(isPresented: popoverBinding(for: .thinking), arrowEdge: .bottom) {
                                ComposerThinkingPanel(selectedLevel: $selectedThinkingLevel) {
                                    activeComposerPanel = nil
                                }
                                .presentationCompactAdaptation(.popover)
                            }

                            ContextRingButton {
                                toggleComposerPanel(.context)
                            }
                            .popover(isPresented: popoverBinding(for: .context), arrowEdge: .bottom) {
                                ComposerContextPanel()
                                    .presentationCompactAdaptation(.popover)
                            }
                        }
                    }
                    .padding(.horizontal, 2)
                    .padding(.top, 2)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background {
            LinearGradient(
                colors: [AmberTheme.background.opacity(0), AmberTheme.background.opacity(0.96), AmberTheme.background],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
        }
        .animation(.spring(response: 0.26, dampingFraction: 0.86), value: showsComposerMeta)
    }

    private var sendEnabled: Bool {
        !viewModel.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !viewModel.isAttachingSelectedFile
    }

    private var showsComposerMeta: Bool {
        isInputFocused ||
            activeComposerPanel != nil ||
            isModelSheetPresented
    }

    private var composerModelLabel: String {
        previewModel ?? (settingsStore.modelId.isEmpty ? "MiMo V2.5 Pro" : settingsStore.modelId)
    }

    private func toggleComposerPanel(_ panel: ComposerPanel) {
        activeComposerPanel = activeComposerPanel == panel ? nil : panel
    }

    private func popoverBinding(for panel: ComposerPanel) -> Binding<Bool> {
        Binding(
            get: { activeComposerPanel == panel },
            set: { isPresented in
                if isPresented {
                    activeComposerPanel = panel
                } else if activeComposerPanel == panel {
                    activeComposerPanel = nil
                }
            }
        )
    }
}

private struct ContextRingButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .stroke(AmberTheme.surface2, lineWidth: 3)
                Circle()
                    .trim(from: 0, to: 0.023)
                    .stroke(AmberTheme.accentAmber, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            .frame(width: 20, height: 20)
            .frame(width: 34, height: 34)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .amberGlass(cornerRadius: 17)
        .accessibilityLabel("上下文用量 2.3%")
    }
}

private struct ComposerModelSheet: View {
    @Environment(\.dismiss) private var dismiss

    let currentModel: String
    let onPick: (ComposerModelOption) -> Void

    @State private var expandedProviderIDs: Set<String>

    private var providers: [ComposerProviderGroup] {
        ComposerProviderGroup.defaults
    }

    init(currentModel: String, onPick: @escaping (ComposerModelOption) -> Void) {
        self.currentModel = currentModel
        self.onPick = onPick
        let selectedProviderID = Self.selectedProviderID(for: currentModel)
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

                    Text("按服务商选择本次对话使用的模型")
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
            .scrollIndicators(.hidden)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(AmberTheme.background)
        .onAppear {
            expandedProviderIDs = Set([Self.selectedProviderID(for: currentModel)])
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

    private static func selectedProviderID(for currentModel: String) -> String {
        ComposerProviderGroup.defaults.first { provider in
            provider.models.contains { $0.matches(currentModel) }
        }?.id ?? ComposerProviderGroup.defaults.first?.id ?? "mimo"
    }
}

private struct ComposerProviderGroupView: View {
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

private struct ComposerModelRow: View {
    let model: ComposerModelOption
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(model.name)
                    .font(.subheadline.weight(isSelected ? .semibold : .regular))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if let context = model.context {
                    Text(context)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AmberTheme.muted)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(AmberTheme.surface2.opacity(0.72), in: Capsule())
                }

                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 18)
                    .opacity(isSelected ? 1 : 0)
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

private struct ComposerProviderGroup: Identifiable {
    let id: String
    let name: String
    let models: [ComposerModelOption]

    static let defaults: [ComposerProviderGroup] = [
        ComposerProviderGroup(
            id: "mimo",
            name: "小米 MiMo",
            models: [
                ComposerModelOption(id: "mimo-v2.5-pro", name: "MiMo V2.5 Pro", context: "1M"),
                ComposerModelOption(id: "mimo-v2.5", name: "MiMo V2.5", context: "1M")
            ]
        ),
        ComposerProviderGroup(
            id: "minimax",
            name: "MiniMax",
            models: [
                ComposerModelOption(id: "MiniMax-M1", name: "MiniMax M1", context: nil)
            ]
        ),
        ComposerProviderGroup(
            id: "openai",
            name: "OpenAI",
            models: [
                ComposerModelOption(id: "gpt-4o", name: "gpt-4o", context: "128K"),
                ComposerModelOption(id: "gpt-5-codex", name: "GPT-5 Codex", context: nil)
            ]
        ),
        ComposerProviderGroup(
            id: "deepseek",
            name: "DeepSeek",
            models: [
                ComposerModelOption(id: "deepseek-reasoner", name: "DeepSeek R1", context: nil),
                ComposerModelOption(id: "deepseek-chat", name: "DeepSeek V3", context: nil),
                ComposerModelOption(id: "deepseek-v4-flash", name: "DeepSeek V4 Flash", context: "1M")
            ]
        ),
        ComposerProviderGroup(
            id: "kimi",
            name: "月之暗面（Kimi）",
            models: [
                ComposerModelOption(id: "kimi-k2", name: "Kimi K2", context: nil)
            ]
        ),
        ComposerProviderGroup(
            id: "glm",
            name: "智谱 GLM",
            models: [
                ComposerModelOption(id: "glm-4.6", name: "GLM 4.6", context: nil)
            ]
        )
    ]
}

private struct ComposerModelOption: Identifiable, Hashable {
    let id: String
    let name: String
    let context: String?

    func matches(_ value: String) -> Bool {
        let normalizedValue = Self.normalize(value)
        return Self.normalize(id) == normalizedValue || Self.normalize(name) == normalizedValue
    }

    private static func normalize(_ value: String) -> String {
        value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }
}

private struct ComposerThinkingPanel: View {
    @Binding var selectedLevel: String
    let onPick: () -> Void

    private let levels = ["关闭", "Low", "Medium", "High", "X High"]

    var body: some View {
        ComposerPopoverSurface(width: 180) {
            VStack(spacing: 0) {
                ForEach(Array(levels.enumerated()), id: \.element) { index, level in
                    ComposerPopoverDivider(index: index)

                    Button {
                        selectedLevel = level
                        onPick()
                    } label: {
                        HStack {
                            Text(level)
                                .font(.subheadline.weight(level == selectedLevel ? .semibold : .regular))
                                .foregroundStyle(level == selectedLevel ? AmberTheme.accent : AmberTheme.foreground)

                            Spacer()

                            if level == selectedLevel {
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
        }
    }
}

private struct ComposerContextPanel: View {
    var body: some View {
        ComposerPopoverSurface(width: 232) {
            VStack(spacing: 0) {
                VStack(spacing: 7) {
                    ZStack {
                        Circle()
                            .stroke(AmberTheme.surface2, lineWidth: 5)
                        Circle()
                            .trim(from: 0, to: 0.023)
                            .stroke(AmberTheme.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                            .rotationEffect(.degrees(-90))

                        Text("2.3%")
                            .font(.system(size: 14, weight: .bold, design: .rounded))
                            .foregroundStyle(AmberTheme.foreground)
                    }
                    .frame(width: 60, height: 60)

                    Text("23,450 / 1,000,000 tokens")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 16)
                .padding(.top, 18)
                .padding(.bottom, 12)

                Divider()
                    .overlay(AmberTheme.borderSoft)

                VStack(spacing: 0) {
                    ComposerContextStatRow(label: "本轮 Session", value: "18,200 tok")
                    ComposerContextStatRow(label: "Cache 命中率", value: "68%")
                    ComposerContextStatRow(label: "生成速度", value: "45 tok/s")
                }
                .padding(.vertical, 4)
            }
        }
    }
}

private struct ComposerContextStatRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)

            Spacer()

            Text(value)
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundStyle(AmberTheme.foreground)
        }
        .padding(.horizontal, 14)
        .frame(height: 32)
    }
}

private struct ComposerPopoverHeader: View {
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

private struct ComposerPopoverDivider: View {
    let index: Int

    var body: some View {
        if index > 0 {
            Divider()
                .overlay(AmberTheme.borderSoft)
                .padding(.leading, 44)
        }
    }
}

private struct ComposerPopoverSurface<Content: View>: View {
    let width: CGFloat
    let content: Content

    init(width: CGFloat, @ViewBuilder content: () -> Content) {
        self.width = width
        self.content = content()
    }

    var body: some View {
        content
            .frame(width: width)
            .background(AmberTheme.background)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(AmberTheme.border.opacity(0.75), lineWidth: 0.5)
            }
            .shadow(color: .black.opacity(0.12), radius: 22, y: 5)
    }
}

enum ChatLayout {
    static let assistantMaxWidth: CGFloat = 296
    static let userMaxWidth: CGFloat = 312
}

struct ChatAssistantStack<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            content
        }
        .frame(maxWidth: ChatLayout.assistantMaxWidth, alignment: .leading)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ChatAgentName: View {
    var body: some View {
        Text("Amber")
            .font(.caption.weight(.semibold))
            .foregroundStyle(AmberTheme.muted)
    }
}

struct ChatMetaLine: View {
    let text: String
    var alignment: Alignment = .leading

    var body: some View {
        Text(text)
            .font(.caption2.monospacedDigit())
            .foregroundStyle(AmberTheme.muted2)
            .frame(maxWidth: .infinity, alignment: alignment)
    }
}

struct ChatUserBubble: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.body)
            .foregroundStyle(.white)
            .lineSpacing(3)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(
                AmberTheme.accent,
                in: UnevenRoundedRectangle(
                    topLeadingRadius: 18,
                    bottomLeadingRadius: 18,
                    bottomTrailingRadius: 6,
                    topTrailingRadius: 18,
                    style: .continuous
                )
            )
            .frame(maxWidth: ChatLayout.userMaxWidth, alignment: .trailing)
    }
}

struct ChatAssistantText<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .font(.body)
            .foregroundStyle(AmberTheme.foreground)
            .lineSpacing(4)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ChatReasoningCard: View {
    let title: String
    let bodyText: String
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.22)) {
                    isExpanded.toggle()
                }
            } label: {
                HStack {
                    Text(title)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(AmberTheme.muted)

                    Spacer()

                    Image(systemName: "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(AmberTheme.foreground)
                        .rotationEffect(.degrees(isExpanded ? 180 : -90))
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isExpanded {
                Text(bodyText)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineSpacing(3)
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                    .padding(.bottom, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(alignment: .top) {
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                    }
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
    }
}

enum ChatToolStepState {
    case done
    case active
    case failed

    var iconName: String {
        switch self {
        case .done:
            "checkmark"
        case .active:
            "circle.fill"
        case .failed:
            "exclamationmark"
        }
    }

    var iconSize: CGFloat {
        switch self {
        case .done, .failed:
            11
        case .active:
            7
        }
    }

    var color: Color {
        switch self {
        case .done:
            AmberTheme.accentGreen
        case .active:
            AmberTheme.accent
        case .failed:
            AmberTheme.accentRed
        }
    }

    var rowFill: Color {
        switch self {
        case .done:
            AmberTheme.surface
        case .active:
            AmberTheme.background
        case .failed:
            AmberTheme.background
        }
    }

    var iconFill: Color {
        switch self {
        case .done:
            AmberTheme.accentGreen.opacity(0.10)
        case .active:
            AmberTheme.accentTint
        case .failed:
            AmberTheme.accentRed.opacity(0.10)
        }
    }

    var stroke: Color {
        switch self {
        case .done:
            AmberTheme.borderSoft
        case .active:
            AmberTheme.accent.opacity(0.72)
        case .failed:
            AmberTheme.accentRed.opacity(0.72)
        }
    }
}

struct ChatToolStepModel: Identifiable {
    let id = UUID()
    let systemImage: String
    let title: String
    let state: ChatToolStepState

    init(systemImage: String, title: String, state: ChatToolStepState) {
        self.systemImage = systemImage
        self.title = title
        self.state = state
    }

    init(tool: UIMessagePart.Tool) {
        let title = tool.toolName.isEmpty ? "工具调用" : tool.toolName
        self.init(
            systemImage: Self.icon(for: title),
            title: title,
            state: tool.output.isEmpty ? .active : .done
        )
    }

    private static func icon(for title: String) -> String {
        let lowercased = title.lowercased()

        if lowercased.contains("search") || title.contains("搜索") {
            return "magnifyingglass"
        }
        if lowercased.contains("read") || lowercased.contains("file") || title.contains("读取") {
            return "doc.text"
        }
        if lowercased.contains("code") || lowercased.contains("swift") || title.contains("生成") {
            return "chevron.left.forwardslash.chevron.right"
        }
        return "wrench.and.screwdriver"
    }
}

struct ChatToolTimeline: View {
    let steps: [ChatToolStepModel]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(steps) { step in
                HStack(spacing: 8) {
                    Image(systemName: step.systemImage)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(step.state.color)
                        .frame(width: 20, height: 20)
                        .background(step.state.iconFill, in: RoundedRectangle(cornerRadius: 5, style: .continuous))

                    Text(step.title)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                        .lineLimit(1)

                    Spacer(minLength: 8)

                    Image(systemName: step.state.iconName)
                        .font(.system(size: step.state.iconSize, weight: .bold))
                        .foregroundStyle(step.state.color)
                        .frame(width: 16, height: 16)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    step.state.rowFill,
                    in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous)
                        .stroke(step.state.stroke, lineWidth: 0.5)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 2)
    }
}

private struct OpenDesignChatSample: View {
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

private struct SampleUserTurn: View {
    let text: String
    let time: String

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            ChatUserBubble(text: text)
            ChatMetaLine(text: time, alignment: .trailing)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}

private struct SampleAssistantBubble: View {
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

private struct SampleAssistantTurn: View {
    var body: some View {
        ChatAssistantStack {
            ChatAgentName()
            ToolTimelineSample()
            ChatReasoningCard(
                title: "思考了 3.0 秒 · auto",
                bodyText: "iOS 界面不应直接继承 Android 的 ViewModel。共享的 KMP 在消息模型、" +
                    "持久化基础、事件类型和 Rust 桥接包装器方面最为强大。"
            )
            ChatAssistantText {
                Text("已经实现了。代码里 `mutableStateOf(false)` 就是默认折叠，点击箭头展开。")
            }
            ChatMetaLine(text: "09:40")
        }
    }
}

private struct ToolTimelineSample: View {
    private let steps: [ChatToolStepModel] = [
        .init(systemImage: "magnifyingglass", title: "搜索 iOS 设计规范", state: .done),
        .init(systemImage: "doc.text", title: "读取 DESIGN_SYSTEM.md", state: .done),
        .init(systemImage: "chevron.left.forwardslash.chevron.right", title: "生成 SwiftUI 代码", state: .active)
    ]

    var body: some View {
        ChatToolTimeline(steps: steps)
    }
}
