import SwiftUI

struct BoardSettingsView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var boardEnabled = true
    @State private var selectedProvider = "小米 MiMo"
    @State private var providerPickerExpanded = false
    @State private var strategy: BoardBackgroundStrategy = .smart

    private let providers: [BoardProviderOption] = [
        .init(name: "小米 MiMo", endpoint: "api.xiaomi.com/v1"),
        .init(name: "Anthropic", endpoint: "api.anthropic.com/v1"),
        .init(name: "DeepSeek", endpoint: "api.deepseek.com/v1"),
        .init(name: "OpenRouter", endpoint: "openrouter.ai/api/v1")
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        generalSection
                        strategySection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回今日看板", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("通用设置")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var generalSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "通用")
            AmberFormGroup {
                BoardSettingsToggleRow(
                    systemImage: "rectangle.grid.2x2",
                    iconColor: AmberTheme.accentAmber,
                    title: "启用今日看板",
                    subtitle: "Agent 每日信号梳理",
                    isOn: $boardEnabled
                )

                BoardSettingsDivider(leading: 58)

                modelPicker
            }
        }
    }

    private var modelPicker: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.snappy(duration: 0.24)) {
                    providerPickerExpanded.toggle()
                }
            } label: {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("看板模型")
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("用于生成摘要的服务商")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    Text(selectedProvider)
                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                        .foregroundStyle(AmberTheme.accent)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)

                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted2)
                        .rotationEffect(.degrees(providerPickerExpanded ? 180 : 0))
                }
                .frame(minHeight: 58)
                .padding(.horizontal, 14)
                .padding(.vertical, 5)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if providerPickerExpanded {
                VStack(spacing: 0) {
                    ForEach(Array(providers.enumerated()), id: \.element.id) { index, provider in
                        Button {
                            selectedProvider = provider.name
                            withAnimation(.snappy(duration: 0.24)) {
                                providerPickerExpanded = false
                            }
                        } label: {
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(provider.name)
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundStyle(AmberTheme.foreground)
                                    Text(provider.endpoint)
                                        .font(.system(size: 11.5, weight: .regular, design: .monospaced))
                                        .foregroundStyle(AmberTheme.muted)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)

                                Text(selectedProvider == provider.name ? "已选" : "使用")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(selectedProvider == provider.name ? AmberTheme.accent : AmberTheme.muted)
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 11)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)

                        if index < providers.count - 1 {
                            BoardSettingsDivider(leading: 14)
                        }
                    }
                }
                .background(AmberTheme.surface2.opacity(0.38))
            }
        }
    }

    private var strategySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "后台策略")
            AmberFormGroup {
                ForEach(Array(BoardBackgroundStrategy.allCases.enumerated()), id: \.element.id) { index, option in
                    BoardStrategyRow(option: option, selected: strategy == option) {
                        strategy = option
                    }

                    if index < BoardBackgroundStrategy.allCases.count - 1 {
                        BoardSettingsDivider(leading: 46)
                    }
                }
            }
        }
    }
}

private struct BoardProviderOption: Identifiable {
    let id = UUID()
    let name: String
    let endpoint: String
}

private enum BoardBackgroundStrategy: String, CaseIterable, Identifiable {
    case smart
    case wifi
    case foreground

    var id: String { rawValue }

    var title: String {
        switch self {
        case .smart: "智能"
        case .wifi: "仅 Wi-Fi"
        case .foreground: "仅前台"
        }
    }

    var detail: String {
        switch self {
        case .smart: "按网络与电量自动调度刷新"
        case .wifi: "仅在 Wi-Fi 环境下后台刷新"
        case .foreground: "仅在 App 打开时刷新"
        }
    }
}

private struct BoardSettingsToggleRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(iconColor)
                .frame(width: 32, height: 32)
                .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(AmberTheme.accent)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
    }
}

private struct BoardStrategyRow: View {
    let option: BoardBackgroundStrategy
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .stroke(selected ? AmberTheme.accent : AmberTheme.border, lineWidth: 1.5)
                        .background(selected ? AmberTheme.accent : .clear, in: Circle())
                        .frame(width: 20, height: 20)

                    Circle()
                        .fill(selected ? .white : .clear)
                        .frame(width: 7, height: 7)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(option.title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    Text(option.detail)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct BoardSettingsDivider: View {
    var leading: CGFloat = 14

    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, leading)
    }
}

#Preview {
    NavigationStack {
        BoardSettingsView()
    }
}
