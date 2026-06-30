import SwiftUI

struct DisplayFontSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    @AppStorage(IOSDisplayPreferenceKeys.fontScale) private var fontScale = 1.0
    @AppStorage(IOSDisplayPreferenceKeys.chatFont) private var chatFont = IOSChatFont.default.rawValue
    @AppStorage(IOSDisplayPreferenceKeys.agentName) private var agentName = true
    @AppStorage(IOSDisplayPreferenceKeys.followGeneration) private var followGeneration = true
    @AppStorage(IOSDisplayPreferenceKeys.microsoftStreamingMarkdown) private var microsoftStreamingMarkdown = false
    @AppStorage(IOSDisplayPreferenceKeys.liyananStreamingMarkdown) private var liyananStreamingMarkdown = false

    private var selectedFont: IOSChatFont {
        IOSChatFont(rawValue: chatFont) ?? .default
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
                    ForEach(IOSChatFont.allCases) { font in
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
                DisplayToggleRow(title: "显示 Agent 名字", isOn: agentName) { agentName.toggle() }
            }
        }
    }

    private var interactionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "渲染与交互")
            AmberFormGroup {
                DisplayToggleRow(title: "生成时跟随滚动", isOn: followGeneration) { followGeneration.toggle() }
                DisplayDivider()
                DisplayToggleRow(
                    title: "使用微软流式 MD 渲染库",
                    subtitle: "仅用于生成中/刚生成的助手正文；历史消息仍回到稳定渲染器。",
                    isOn: microsoftStreamingMarkdown
                ) {
                    microsoftStreamingMarkdown.toggle()
                    if microsoftStreamingMarkdown {
                        liyananStreamingMarkdown = false
                    }
                }
                DisplayDivider()
                DisplayToggleRow(
                    title: "使用 MarkdownView 流式渲染库",
                    subtitle: "实验选项；同样只用于生成中/刚生成的助手正文。",
                    isOn: liyananStreamingMarkdown
                ) {
                    liyananStreamingMarkdown.toggle()
                    if liyananStreamingMarkdown {
                        microsoftStreamingMarkdown = false
                    }
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

private struct FontPreviewCard: View {
    let font: IOSChatFont
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

private struct DisplayStatusRow: View {
    let title: String
    let subtitle: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(AmberTheme.muted.opacity(0.12), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
        }
        .frame(minHeight: 64)
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
