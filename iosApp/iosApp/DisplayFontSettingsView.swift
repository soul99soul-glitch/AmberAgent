import SwiftUI

struct DisplayFontSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    @AppStorage(IOSDisplayPreferenceKeys.fontScale) private var fontScale = 1.0
    @AppStorage(IOSDisplayPreferenceKeys.chatFont) private var chatFont = IOSChatFont.default.rawValue
    @AppStorage(IOSDisplayPreferenceKeys.agentName) private var agentName = true
    @AppStorage(IOSDisplayPreferenceKeys.followGeneration) private var followGeneration = true

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
                DisplayDivider()
                DisplayStatusRow(
                    title: "显示助手消息气泡",
                    subtitle: "当前 Chat 渲染器没有助手气泡模式",
                    value: "未接线"
                )
                DisplayDivider()
                DisplayStatusRow(
                    title: "昵称下方显示日期",
                    subtitle: "消息模型/时间格式桥尚未接线",
                    value: "未接线"
                )
                DisplayDivider()
                DisplayStatusRow(
                    title: "助手状态点 / 思考折叠",
                    subtitle: "KMP 默认值（autoCloseThinking）；状态点与生成结束折叠策略尚未接到 Chat 状态机",
                    value: sharedSettings.displaySetting.autoCloseThinking ? "默认开" : "默认关"
                )
            }
        }
    }

    private var codeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "代码块")
            AmberFormGroup {
                DisplayStatusRow(
                    title: "自动换行",
                    subtitle: "KMP 默认值（codeBlockAutoWrap）；iOS 渲染器尚未消费",
                    value: sharedSettings.displaySetting.codeBlockAutoWrap ? "默认开" : "默认关"
                )
                DisplayDivider()
                DisplayStatusRow(
                    title: "自动折叠",
                    subtitle: "KMP 默认值（codeBlockAutoCollapse）；iOS 渲染器尚未消费",
                    value: sharedSettings.displaySetting.codeBlockAutoCollapse ? "默认开" : "默认关"
                )
                DisplayDivider()
                DisplayStatusRow(
                    title: "显示行号",
                    subtitle: "当前 iOS Markdown renderer 不生成行号",
                    value: "未接线"
                )
            }
        }
    }

    private var interactionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "渲染与交互")
            AmberFormGroup {
                DisplayStatusRow(
                    title: "启用 LaTeX 渲染",
                    subtitle: "KMP 默认值（enableLatexRendering）；iOS LaTeX renderer 尚未消费",
                    value: sharedSettings.displaySetting.enableLatexRendering ? "默认开" : "默认关"
                )
                DisplayDivider()
                DisplayToggleRow(title: "生成时跟随滚动", isOn: followGeneration) { followGeneration.toggle() }
                DisplayDivider()
                DisplayStatusRow(
                    title: "按 Enter 发送",
                    subtitle: "KMP 默认值（sendOnEnter）；移动端输入器尚未接硬件键盘提交策略",
                    value: sharedSettings.displaySetting.sendOnEnter ? "默认开" : "默认关"
                )
                DisplayDivider()
                DisplayStatusRow(
                    title: "粘贴长文本为文件",
                    subtitle: "KMP 默认值（pasteLongTextAsFile，阈值 \(sharedSettings.displaySetting.pasteLongTextThreshold)）；iOS 粘贴管线尚未接线",
                    value: sharedSettings.displaySetting.pasteLongTextAsFile ? "默认开" : "默认关"
                )
                DisplayDivider()
                DisplayStatusRow(
                    title: "启动入口",
                    subtitle: "App 启动路由目前固定进入 Home",
                    value: "未接线"
                )
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
