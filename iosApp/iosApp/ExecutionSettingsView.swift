import SwiftUI

struct ExecutionSettingsView: View {
    @Environment(\.dismiss) private var dismiss

    @AppStorage("app.amber.ios.execution.previewMode") private var previewMode = ExecutionPreviewMode.automatic.rawValue
    @AppStorage("app.amber.ios.execution.toolLoopLimit") private var toolLoopLimit = 25
    @AppStorage("app.amber.ios.execution.generativeUI") private var generativeUI = true
    @AppStorage("app.amber.ios.execution.liveActivity") private var liveActivity = true
    @AppStorage("app.amber.ios.execution.hideSensitive") private var hideSensitive = false
    @AppStorage("app.amber.ios.execution.autoRetry") private var autoRetry = true
    @AppStorage("app.amber.ios.execution.retryLimit") private var retryLimit = 3
    @AppStorage("app.amber.ios.execution.backgroundKeepAlive") private var backgroundKeepAlive = true

    private var selectedPreviewMode: ExecutionPreviewMode {
        ExecutionPreviewMode(rawValue: previewMode) ?? .automatic
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    operationPreviewSection
                    runSection
                    liveActivitySection
                    stabilitySection
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

            Text("执行与任务")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var intro: some View {
        Text("控制 Agent 怎么跑工具、怎么显示进度，以及网络波动时如何稳住生成。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    private var operationPreviewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "操作预览")

            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 10) {
                        Image(systemName: "globe")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(AmberTheme.accentCyan)
                            .frame(width: 30, height: 30)
                            .background(AmberTheme.accentCyan.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                        VStack(alignment: .leading, spacing: 1) {
                            Text("正在搜索网页")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Text("google.com · 抓取 3 个来源")
                                .font(.system(.caption2, design: .monospaced))
                                .foregroundStyle(AmberTheme.muted)
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        RoundedRectangle(cornerRadius: 5, style: .continuous)
                            .fill(LinearGradient(colors: [AmberTheme.surface, AmberTheme.surface2], startPoint: .topLeading, endPoint: .bottomTrailing))
                            .frame(width: 32, height: 23)

                        Image(systemName: "chevron.up")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted2)
                    }
                    .padding(10)
                    .background(AmberTheme.background, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 13, style: .continuous)
                            .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                    }

                    Text("Agent 运行时浮在输入框上方，点开查看正在访问的页面。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineSpacing(2)
                        .padding(.horizontal, 3)
                }
                .padding(13)

                ExecutionDivider(leading: 0)

                ForEach(ExecutionPreviewMode.allCases) { mode in
                    ExecutionRadioRow(
                        title: mode.title,
                        subtitle: mode.subtitle,
                        isDefault: mode == .automatic,
                        isSelected: selectedPreviewMode == mode
                    ) {
                        previewMode = mode.rawValue
                    }

                    if mode != ExecutionPreviewMode.allCases.last {
                        ExecutionDivider(leading: 15)
                    }
                }
            }
            .background(AmberTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .padding(.horizontal, 16)
        }
    }

    private var runSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "运行")
            AmberFormGroup {
                Menu {
                    ForEach([10, 15, 25, 40, 60], id: \.self) { value in
                        Button("\(value) 步") {
                            toolLoopLimit = value
                        }
                    }
                } label: {
                    ExecutionValueRow(
                        systemImage: "arrow.clockwise",
                        title: "工具循环上限",
                        subtitle: "单次回复最多调用多少轮工具",
                        value: "\(toolLoopLimit) 步"
                    )
                }

                ExecutionDivider(leading: 54)

                ExecutionToggleRow(
                    systemImage: "rectangle.inset.filled",
                    title: "生成式 UI",
                    subtitle: "允许模型在聊天中生成安全的可视化卡片",
                    isOn: generativeUI
                ) {
                    generativeUI.toggle()
                }
            }
        }
    }

    private var liveActivitySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "灵动岛")
            AmberFormGroup {
                ExecutionToggleRow(
                    systemImage: "capsule",
                    title: "灵动岛实时活动",
                    subtitle: "任务运行时在灵动岛与锁屏显示实时进度",
                    isOn: liveActivity
                ) {
                    liveActivity.toggle()
                }

                ExecutionDivider(leading: 54)

                ExecutionToggleRow(
                    systemImage: "lock",
                    title: "隐藏敏感内容",
                    subtitle: "灵动岛与锁屏只显示工具类型和状态，隐藏正文",
                    isOn: hideSensitive
                ) {
                    hideSensitive.toggle()
                }
            }

            ExecutionNote("实时活动通过系统灵动岛与锁屏展示任务进度，由 iOS 原生 ActivityKit 驱动；点按可回到对话。")
        }
    }

    private var stabilitySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "生成稳定性")
            AmberFormGroup {
                ExecutionToggleRow(
                    systemImage: "clock.arrow.circlepath",
                    title: "自动重试生成",
                    subtitle: "网络或服务临时中断时自动重试",
                    isOn: autoRetry
                ) {
                    autoRetry.toggle()
                }

                ExecutionDivider(leading: 54)

                HStack(spacing: 12) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(AmberTheme.foreground2)
                        .frame(width: 28, height: 28)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("重试上限")
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("单次模型调用最多重试次数")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    ExecutionStepper(value: $retryLimit, range: 0...8)
                }
                .frame(minHeight: 58)
                .padding(.horizontal, 14)
                .padding(.vertical, 4)

                ExecutionDivider(leading: 54)

                ExecutionToggleRow(
                    systemImage: "bell",
                    title: "后台生成保活",
                    subtitle: "生成时用前台通知降低后台中断概率",
                    isOn: backgroundKeepAlive
                ) {
                    backgroundKeepAlive.toggle()
                }
            }
        }
    }
}

private enum ExecutionPreviewMode: String, CaseIterable, Identifiable {
    case persistent
    case automatic
    case hidden

    var id: String { rawValue }

    var title: String {
        switch self {
        case .persistent: "常驻"
        case .automatic: "自动"
        case .hidden: "隐藏"
        }
    }

    var subtitle: String {
        switch self {
        case .persistent: "始终显示，空闲时也保留待命状态"
        case .automatic: "仅执行操作时浮出，结束后自动收起"
        case .hidden: "运行时不显示，保持安静"
        }
    }
}

private struct ExecutionRadioRow: View {
    let title: String
    let subtitle: String
    let isDefault: Bool
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Text(title)
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        if isDefault {
                            Text("默认")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(AmberTheme.accent)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 1)
                                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
                        }
                    }

                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.accent)
                }
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct ExecutionValueRow: View {
    let systemImage: String
    let title: String
    let subtitle: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct ExecutionToggleRow: View {
    let systemImage: String
    let title: String
    let subtitle: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                ExecutionSwitch(isOn: isOn)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct ExecutionSwitch: View {
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

private struct ExecutionStepper: View {
    @Binding var value: Int
    let range: ClosedRange<Int>

    var body: some View {
        HStack(spacing: 0) {
            Button {
                value = max(range.lowerBound, value - 1)
            } label: {
                Image(systemName: "minus")
                    .font(.caption.weight(.semibold))
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.plain)
            .disabled(value <= range.lowerBound)

            Text("\(value)")
                .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)
                .frame(width: 30, height: 30)

            Button {
                value = min(range.upperBound, value + 1)
            } label: {
                Image(systemName: "plus")
                    .font(.caption.weight(.semibold))
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.plain)
            .disabled(value >= range.upperBound)
        }
        .foregroundStyle(AmberTheme.accent)
        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct ExecutionDivider: View {
    var leading: CGFloat

    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, leading)
    }
}

private struct ExecutionNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}
