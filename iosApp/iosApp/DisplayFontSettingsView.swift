import SwiftUI

struct DisplayFontSettingsView: View {
    @Environment(\.dismiss) private var dismiss

    @AppStorage("app.amber.ios.display.fontScale") private var fontScale = 1.0
    @AppStorage("app.amber.ios.display.chatFont") private var chatFont = ChatFont.default.rawValue
    @AppStorage("app.amber.ios.display.assistantBubble") private var assistantBubble = true
    @AppStorage("app.amber.ios.display.agentName") private var agentName = true
    @AppStorage("app.amber.ios.display.nicknameDate") private var nicknameDate = false
    @AppStorage("app.amber.ios.display.assistantStatusDot") private var assistantStatusDot = true
    @AppStorage("app.amber.ios.display.autoCollapseThinking") private var autoCollapseThinking = true
    @AppStorage("app.amber.ios.display.codeWrap") private var codeWrap = false
    @AppStorage("app.amber.ios.display.codeCollapse") private var codeCollapse = true
    @AppStorage("app.amber.ios.display.codeLineNumbers") private var codeLineNumbers = true
    @AppStorage("app.amber.ios.display.latex") private var latex = true
    @AppStorage("app.amber.ios.display.followGeneration") private var followGeneration = true
    @AppStorage("app.amber.ios.display.sendOnEnter") private var sendOnEnter = false
    @AppStorage("app.amber.ios.display.pasteLongAsFile") private var pasteLongAsFile = true
    @AppStorage("app.amber.ios.display.launchEntry") private var launchEntry = LaunchEntry.home.rawValue

    private var selectedFont: ChatFont {
        ChatFont(rawValue: chatFont) ?? .default
    }

    private var selectedLaunchEntry: LaunchEntry {
        LaunchEntry(rawValue: launchEntry) ?? .home
    }

    private var fontScaleLabel: String {
        switch fontScale {
        case ..<0.95: "较小"
        case 0.95..<1.08: "标准"
        case 1.08..<1.20: "较大"
        default: "特大"
        }
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    fontSection
                    messageSection
                    codeSection
                    interactionSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("显示与字体")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 22)
    }

    private var fontSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "字体")
            AmberFormGroup {
                VStack(spacing: 12) {
                    HStack {
                        Text("聊天字体大小")
                            .font(.subheadline)
                            .foregroundStyle(AmberTheme.foreground)
                        Spacer()
                        Text(fontScaleLabel)
                            .font(.subheadline)
                            .foregroundStyle(AmberTheme.muted)
                    }

                    HStack(spacing: 12) {
                        Text("A")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted2)
                        Slider(value: $fontScale, in: 0.88...1.25, step: 0.01)
                            .tint(AmberTheme.accent)
                        Text("A")
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted)
                    }
                }
                .padding(.horizontal, 15)
                .padding(.vertical, 13)

                DisplayDivider()

                Menu {
                    ForEach(ChatFont.allCases) { font in
                        Button(font.title) {
                            chatFont = font.rawValue
                        }
                    }
                } label: {
                    DisplayValueRow(title: "聊天字体", value: selectedFont.title)
                }
            }

            FontPreviewCard(font: selectedFont, scale: fontScale)
                .padding(.top, 10)

            Text("预览展示当前字号与字体在聊天正文中的效果。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 7)
        }
    }

    private var messageSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "消息显示")
            AmberFormGroup {
                DisplayToggleRow(title: "显示助手消息气泡", isOn: assistantBubble) { assistantBubble.toggle() }
                DisplayDivider()
                DisplayToggleRow(title: "显示 Agent 名字", isOn: agentName) { agentName.toggle() }
                DisplayDivider()
                DisplayToggleRow(title: "昵称下方显示日期", isOn: nicknameDate) { nicknameDate.toggle() }
                DisplayDivider()
                DisplayToggleRow(title: "助手状态点", subtitle: "在消息旁显示在线 / 生成状态小圆点", isOn: assistantStatusDot) { assistantStatusDot.toggle() }
                DisplayDivider()
                DisplayToggleRow(title: "自动折叠思考", subtitle: "生成结束后自动收起推理过程", isOn: autoCollapseThinking) { autoCollapseThinking.toggle() }
            }
        }
    }

    private var codeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "代码块")
            AmberFormGroup {
                DisplayToggleRow(title: "自动换行", isOn: codeWrap) { codeWrap.toggle() }
                DisplayDivider()
                DisplayToggleRow(title: "自动折叠", subtitle: "较长代码块默认折叠", isOn: codeCollapse) { codeCollapse.toggle() }
                DisplayDivider()
                DisplayToggleRow(title: "显示行号", isOn: codeLineNumbers) { codeLineNumbers.toggle() }
            }
        }
    }

    private var interactionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "渲染与交互")
            AmberFormGroup {
                DisplayToggleRow(title: "启用 LaTeX 渲染", isOn: latex) { latex.toggle() }
                DisplayDivider()
                DisplayToggleRow(title: "生成时跟随滚动", isOn: followGeneration) { followGeneration.toggle() }
                DisplayDivider()
                DisplayToggleRow(title: "按 Enter 发送", isOn: sendOnEnter) { sendOnEnter.toggle() }
                DisplayDivider()
                DisplayToggleRow(title: "粘贴长文本为文件", isOn: pasteLongAsFile) { pasteLongAsFile.toggle() }
                DisplayDivider()
                Menu {
                    ForEach(LaunchEntry.allCases) { entry in
                        Button(entry.title) {
                            launchEntry = entry.rawValue
                        }
                    }
                } label: {
                    DisplayValueRow(title: "启动入口", value: selectedLaunchEntry.title)
                }
            }
        }
    }
}

private struct DisplayDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private enum ChatFont: String, CaseIterable, Identifiable {
    case `default`
    case serif
    case monospace

    var id: String { rawValue }

    var title: String {
        switch self {
        case .default: "默认"
        case .serif: "衬线体"
        case .monospace: "等宽字体"
        }
    }

    var design: Font.Design {
        switch self {
        case .default: .default
        case .serif: .serif
        case .monospace: .monospaced
        }
    }
}

private enum LaunchEntry: String, CaseIterable, Identifiable {
    case home
    case lastConversation
    case newChat
    case automatic

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: "主页"
        case .lastConversation: "上次会话"
        case .newChat: "新建对话"
        case .automatic: "自动"
        }
    }
}

private struct FontPreviewCard: View {
    let font: ChatFont
    let scale: Double

    var body: some View {
        Text("凌晨四点钟，看到海棠花未眠。我常常这样，在无人的夜里独自醒着，想，若有人此刻也在想我，那就好了。")
            .font(.system(size: 16 * scale, design: font.design))
            .foregroundStyle(AmberTheme.foreground2)
            .lineSpacing(3 * scale)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(15)
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .padding(.horizontal, 16)
    }
}

private struct DisplayValueRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct DisplayToggleRow: View {
    let title: String
    var subtitle: String?
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    if let subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                DisplaySwitch(isOn: isOn)
            }
            .frame(minHeight: subtitle == nil ? 52 : 64)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct DisplaySwitch: View {
    let isOn: Bool

    var body: some View {
        Capsule()
            .fill(isOn ? AmberTheme.accent : AmberTheme.surface2)
            .frame(width: 48, height: 28)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(Color.white)
                    .frame(width: 24, height: 24)
                    .shadow(color: .black.opacity(0.16), radius: 3, y: 1)
                    .padding(2)
            }
            .animation(.snappy(duration: 0.18), value: isOn)
    }
}
