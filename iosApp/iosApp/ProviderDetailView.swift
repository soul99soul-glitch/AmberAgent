import SwiftUI

struct ProviderDetailView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    let providerName: String
    let endpoint: String

    @State private var selectedTab: ProviderDetailTab = .config
    @State private var isEnabled = true
    @State private var responseAPI = false
    @State private var balanceRefresh = true
    @State private var apiKeyVisible = false
    @State private var alert: ProviderDetailAlert?

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                chrome

                Group {
                    switch selectedTab {
                    case .config:
                        configPanel
                    case .models:
                        modelsPanel
                    }
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $alert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    private var chrome: some View {
        VStack(spacing: 10) {
            HStack {
                AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回服务商", size: 44, symbolSize: 20) {
                    dismiss()
                }

                Spacer()

                Text(providerName)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)

                Spacer()

                Group {
                    switch selectedTab {
                    case .config:
                        Button {
                            alert = .save
                        } label: {
                            Text("保存")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(AmberTheme.accent)
                                .frame(width: 58, height: 36)
                                .contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .amberGlass(cornerRadius: 18)
                        .accessibilityLabel("保存")
                    case .models:
                        AmberGlassCircleButton(systemImage: "plus", accessibilityLabel: "添加模型", size: 44, symbolSize: 17) {
                            router.navigate(to: .modelAdd)
                        }
                    }
                }
                .frame(width: 58, alignment: .trailing)
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)

            ProviderSegmentedControl(selection: $selectedTab)
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
        }
    }

    private var configPanel: some View {
        ScrollView {
            VStack(spacing: 0) {
                AmberFormGroup {
                    ProviderValueRow(title: "接口协议", value: protocolName, showsChevron: true) {
                        alert = .protocolPicker
                    }
                    ProviderDetailDivider()
                    ProviderToggleRow(title: "启用此提供商", isOn: isEnabled) {
                        isEnabled.toggle()
                    }
                }

                AmberSectionLabel(text: "连接")
                AmberFormGroup {
                    ProviderValueRow(title: "名称", value: providerName, showsChevron: true) {
                        router.navigate(to: .providerSettings)
                    }
                    ProviderDetailDivider()
                    apiKeyRow
                    ProviderDetailDivider()
                    ProviderValueRow(title: "API 地址", value: endpoint, valueStyle: .mono, showsChevron: true) {
                        router.navigate(to: .providerSettings)
                    }
                    ProviderDetailDivider()
                    ProviderStaticRow(
                        title: "路径",
                        subtitle: "由接口协议决定",
                        value: "/chat/completions",
                        valueStyle: .monoMuted
                    )
                }

                AmberSectionLabel(text: "选项")
                AmberFormGroup {
                    ProviderToggleRow(
                        title: "Response API",
                        subtitle: "使用 /responses 端点（实验性）",
                        isOn: responseAPI
                    ) {
                        responseAPI.toggle()
                    }
                    ProviderDetailDivider()
                    ProviderToggleRow(
                        title: "账户余额",
                        subtitle: "余额 ¥48.20 · 每次启动后刷新",
                        isOn: balanceRefresh,
                        highlightsSubtitle: true
                    ) {
                        balanceRefresh.toggle()
                    }
                }
            }
            .padding(.bottom, 36)
        }
        .scrollIndicators(.hidden)
    }

    private var modelsPanel: some View {
        ScrollView {
            VStack(spacing: 0) {
                AmberFormGroup {
                    ProviderModelRow(
                        systemImage: "bolt",
                        name: "deepseek-v4-flash",
                        badge: "速度优先",
                        summary: "工具 · 推理 · 1M ctx"
                    ) {
                        router.navigate(to: .modelEdit)
                    }
                    ProviderDetailDivider()
                    ProviderModelRow(
                        systemImage: "star",
                        name: "deepseek-v4-pro",
                        badge: "质量优先",
                        summary: "工具 · 推理 · 1M ctx"
                    ) {
                        router.navigate(to: .modelEdit)
                    }
                }

                Text("点按模型可编辑其能力、模态与上下文；右上角 + 添加新模型。")
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.top, 10)
            }
            .padding(.top, 10)
            .padding(.bottom, 36)
        }
        .scrollIndicators(.hidden)
    }

    private var apiKeyRow: some View {
        HStack(spacing: 12) {
            Text("API Key")
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 8) {
                Text(apiKeyVisible ? "sk-cdd7f4a2e8b91c6e" : "sk-cdd·····8b91")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)

                Button {
                    apiKeyVisible.toggle()
                } label: {
                    Image(systemName: apiKeyVisible ? "eye.slash" : "eye")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(width: 28, height: 28)
                        .background(AmberTheme.surface2, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(apiKeyVisible ? "隐藏 API Key" : "显示 API Key")
            }
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }

    private var protocolName: String {
        switch providerName {
        case "Gemini", "Gemini OAuth":
            "Gemini"
        default:
            "OpenAI"
        }
    }
}

private enum ProviderDetailTab: String, CaseIterable, Identifiable {
    case config = "配置"
    case models = "模型"

    var id: String { rawValue }
}

private enum ProviderDetailAlert: Identifiable {
    case save
    case protocolPicker

    var id: String {
        switch self {
        case .save:
            "save"
        case .protocolPicker:
            "protocol-picker"
        }
    }

    var title: String {
        switch self {
        case .save:
            "服务商保存尚未接线"
        case .protocolPicker:
            "接口协议选择尚未接线"
        }
    }

    var message: String {
        switch self {
        case .save:
            "当前详情页先还原原型布局；真实 Base URL、API Key 和 Model ID 仍由旧配置页承接。"
        case .protocolPicker:
            "协议选择后续会接通通用选择器，现在暂不修改真实服务商配置。"
        }
    }
}

private struct ProviderSegmentedControl: View {
    @Binding var selection: ProviderDetailTab

    var body: some View {
        HStack(spacing: 0) {
            ForEach(ProviderDetailTab.allCases) { tab in
                Button {
                    selection = tab
                } label: {
                    Text(tab.rawValue)
                        .font(.subheadline.weight(selection == tab ? .semibold : .medium))
                        .foregroundStyle(selection == tab ? AmberTheme.foreground : AmberTheme.muted)
                        .frame(maxWidth: .infinity)
                        .frame(height: 31)
                        .background {
                            if selection == tab {
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .fill(AmberTheme.background)
                                    .shadow(color: .black.opacity(0.05), radius: 4, y: 1)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selection == tab ? .isSelected : [])
            }
        }
        .padding(3)
        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
    }
}

private enum ProviderValueStyle {
    case normal
    case mono
    case monoMuted
}

private struct ProviderDetailDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct ProviderValueRow: View {
    let title: String
    let value: String
    var valueStyle: ProviderValueStyle = .normal
    var showsChevron = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ProviderRowContent(
                title: title,
                subtitle: nil,
                value: value,
                valueStyle: valueStyle,
                showsChevron: showsChevron
            )
        }
        .buttonStyle(.plain)
    }
}

private struct ProviderStaticRow: View {
    let title: String
    let subtitle: String?
    let value: String
    var valueStyle: ProviderValueStyle = .normal

    var body: some View {
        ProviderRowContent(
            title: title,
            subtitle: subtitle,
            value: value,
            valueStyle: valueStyle,
            showsChevron: false
        )
    }
}

private struct ProviderRowContent: View {
    let title: String
    let subtitle: String?
    let value: String
    let valueStyle: ProviderValueStyle
    let showsChevron: Bool

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(subtitle == nil ? AmberTheme.foreground : AmberTheme.foreground2)

                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(valueFont)
                .foregroundStyle(valueColor)
                .lineLimit(1)
                .minimumScaleFactor(0.72)

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    private var valueFont: Font {
        switch valueStyle {
        case .normal:
            .subheadline
        case .mono, .monoMuted:
            .system(size: 11.5, weight: .regular, design: .monospaced)
        }
    }

    private var valueColor: Color {
        switch valueStyle {
        case .normal, .mono:
            AmberTheme.muted
        case .monoMuted:
            AmberTheme.muted2
        }
    }
}

private struct ProviderToggleRow: View {
    let title: String
    var subtitle: String?
    let isOn: Bool
    var highlightsSubtitle = false
    let action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)

                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(highlightsSubtitle ? AmberTheme.muted : AmberTheme.muted)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: action) {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(isOn ? AmberTheme.accent : AmberTheme.border)
                    .frame(width: 48, height: 30)
                    .overlay(alignment: isOn ? .trailing : .leading) {
                        Circle()
                            .fill(.white)
                            .frame(width: 26, height: 26)
                            .shadow(color: .black.opacity(0.14), radius: 3, y: 1)
                            .padding(2)
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(title)
            .accessibilityValue(isOn ? "开启" : "关闭")
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct ProviderModelRow: View {
    let systemImage: String
    let name: String
    let badge: String
    let summary: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AmberTheme.muted)
                    .frame(width: 36, height: 36)
                    .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                    }

                VStack(alignment: .leading, spacing: 6) {
                    Text(name)
                        .font(.system(size: 13.5, weight: .medium, design: .monospaced))
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)

                    HStack(spacing: 6) {
                        Text(badge)
                            .font(.system(size: 10.5, weight: .medium))
                            .foregroundStyle(AmberTheme.muted2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 5, style: .continuous))

                        Text(summary)
                            .font(.system(size: 10.5))
                            .foregroundStyle(AmberTheme.muted2)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    NavigationStack {
        ProviderDetailView(providerName: "DeepSeek", endpoint: "api.deepseek.com/v1")
            .environment(RouterPath())
    }
}
