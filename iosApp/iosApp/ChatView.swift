import SwiftUI
import Shared

struct ChatView: View {

    let settingsStore: SettingsStore
    @State private var viewModel: ChatViewModel
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
                HStack(alignment: .bottom, spacing: 8) {
                    Button {
                        Task {
                            await viewModel.attachSelectedFilePreviewToNextMessage()
                        }
                    } label: {
                        Image(systemName: viewModel.isAttachingSelectedFile ? "paperclip.circle.fill" : "plus")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(AmberTheme.foreground2)
                            .frame(width: 34, height: 34)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.isLoading || viewModel.isAttachingSelectedFile)

                    TextField("发消息给 Amber...", text: $viewModel.inputText, axis: .vertical)
                        .lineLimit(1...5)
                        .textFieldStyle(.plain)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .padding(.vertical, 7)
                        .focused($isInputFocused)

                    if viewModel.isLoading {
                        Button {
                            viewModel.cancelGeneration()
                        } label: {
                            Image(systemName: "stop.fill")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 28, height: 28)
                                .background(AmberTheme.accentRed, in: Circle())
                        }
                        .buttonStyle(.plain)
                    } else {
                        Button {
                            viewModel.sendMessage()
                        } label: {
                            Image(systemName: "arrow.up")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 28, height: 28)
                                .background(sendEnabled ? AmberTheme.accent : AmberTheme.muted2.opacity(0.45), in: Circle())
                        }
                        .buttonStyle(.plain)
                        .disabled(!sendEnabled)
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 8)
                .background(AmberTheme.glassStrong)
                .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
                .amberGlass(cornerRadius: 26)

                HStack {
                    Button {
                    } label: {
                        Text(settingsStore.modelId.isEmpty ? "MiMo V2.5 Pro" : settingsStore.modelId)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground2)
                            .lineLimit(1)
                            .padding(.horizontal, 12)
                            .frame(height: 30)
                            .background(AmberTheme.glass, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .amberGlass(cornerRadius: 15)

                    Spacer()

                    AmberGlassCircleButton(systemImage: "sparkles", accessibilityLabel: "设置思考等级", size: 30, symbolSize: 14) {}
                    ContextRingButton()
                }
            }
            .padding(8)
            .amberGlass(cornerRadius: 30)
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background {
            LinearGradient(
                colors: [AmberTheme.background.opacity(0), AmberTheme.background],
                startPoint: .top,
                endPoint: .center
            )
            .ignoresSafeArea()
        }
    }

    private var sendEnabled: Bool {
        !viewModel.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !viewModel.isAttachingSelectedFile
    }
}

private struct ContextRingButton: View {
    var body: some View {
        ZStack {
            Circle()
                .stroke(AmberTheme.surface2, lineWidth: 2.4)
            Circle()
                .trim(from: 0, to: 0.023)
                .stroke(AmberTheme.accent, style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 30, height: 30)
        .padding(0)
        .amberGlass(cornerRadius: 15)
        .accessibilityLabel("上下文用量 2.3%")
    }
}

private struct OpenDesignChatSample: View {
    var body: some View {
        VStack(spacing: 14) {
            SampleUserTurn(text: "Thinking 的部分默认是折叠的,然后可以点击小三角展开", time: "09:38")
            SampleAssistantTurn()
            SampleUserTurn(text: "让 UI 具有高级感，但不要把所有东西都变成玻璃。", time: "09:41")
            SampleAssistantBubble(text: "确实如此。Liquid Glass 材质仅适用于临时性的系统界面：如输入框辅助栏、Sheet 弹窗以及工具栏组合。消息文本、代码块、设置表单和列表应保持完全不透明，以确保最高的阅读和交互可读性。", time: "09:41")
        }
    }
}

private struct SampleUserTurn: View {
    let text: String
    let time: String

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            Text(text)
                .font(.body)
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(AmberTheme.accent, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .frame(maxWidth: 312, alignment: .trailing)
            Text(time)
                .font(.caption2)
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}

private struct SampleAssistantBubble: View {
    let text: String
    let time: String

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("Amber")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
            Text(text)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .frame(maxWidth: 320, alignment: .leading)
            Text(time)
                .font(.caption2)
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct SampleAssistantTurn: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Amber")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
            ToolTimelineSample()
            DisclosureGroup {
                Text("iOS 界面不应直接继承 Android 的 ViewModel。共享的 KMP 在消息模型、持久化基础、事件类型和 Rust 桥接包装器方面最为强大。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .padding(.top, 6)
            } label: {
                Text("思考了 3.0 秒 · auto")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
            }
            .padding(10)
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            Text("已经实现了。代码里 `mutableStateOf(false)` 就是默认折叠，点击箭头展开。")
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .frame(maxWidth: 320, alignment: .leading)
            Text("09:40")
                .font(.caption2)
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ToolTimelineSample: View {
    private let rows: [(String, String, Color)] = [
        ("magnifyingglass", "搜索 iOS 设计规范", AmberTheme.accentGreen),
        ("doc.text", "读取 DESIGN_SYSTEM.md", AmberTheme.accentGreen),
        ("chevron.left.forwardslash.chevron.right", "生成 SwiftUI 代码", AmberTheme.accent)
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                HStack(spacing: 8) {
                    Image(systemName: row.0)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(row.2)
                        .frame(width: 20, height: 20)
                        .background(row.2.opacity(0.12), in: Circle())
                    Text(row.1)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                    Spacer()
                    Image(systemName: index < 2 ? "checkmark" : "circle.fill")
                        .font(.system(size: index < 2 ? 11 : 7, weight: .bold))
                        .foregroundStyle(row.2)
                }
            }
        }
        .padding(10)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}
