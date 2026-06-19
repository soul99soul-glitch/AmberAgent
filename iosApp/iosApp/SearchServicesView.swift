import SwiftUI
import Shared

struct SearchServicesView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        presetServicesSection
                        agentSearchSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("搜索服务")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassCircleButton(systemImage: "plus", accessibilityLabel: "添加搜索服务", size: 44, symbolSize: 17) {
                router.navigate(to: .searchProvider)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("开启网络搜索后，聊天可以在需要时搜索网页并读取公开页面。当前优先使用可直接执行的 Bing HTML，必要时使用 DuckDuckGo Lite。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 2)
    }

    private var presetServicesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "搜索状态")

            AmberFormGroup {
                SearchPresetToggleRow(title: "Agent 网络搜索", isOn: sharedSettings.enableWebSearch)
                SearchServicesDivider()
                SearchPresetToggleRow(title: "DuckDuckGo Lite", isOn: sharedSettings.searchBuiltinDuckDuckGoEnabled)
                SearchServicesDivider()
                SearchPresetToggleRow(title: "Bing HTML", isOn: sharedSettings.searchBuiltinBingEnabled)
                SearchServicesDivider()
                SearchStatusRow(
                    systemImage: "list.bullet.rectangle",
                    iconColor: AmberTheme.accentCyan,
                    title: "已保存服务",
                    subtitle: "当前可供聊天选择的搜索服务数量。",
                    value: "\(sharedSettings.searchServices.count) 条",
                    valueColor: AmberTheme.foreground2
                )
            }

            SearchServicesNote {
                Text("右上角 + 目前只开放可以直接执行的 Bing HTML 服务。")
            }
        }
    }

    private var agentSearchSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                SearchStatusRow(
                    systemImage: "magnifyingglass",
                    iconColor: AmberTheme.accentCyan,
                    title: "Agent 网络搜索",
                    subtitle: "开启后，模型可以在需要时搜索网页并继续回答。",
                    value: "可用",
                    valueColor: AmberTheme.accentGreen
                )
                SearchServicesDivider()
                SearchStatusRow(
                    systemImage: "doc.text.magnifyingglass",
                    iconColor: AmberTheme.accentGreen,
                    title: "网页读取",
                    subtitle: "可读取公开网页正文；需要登录或私有权限的页面不会自动访问。",
                    value: "可用",
                    valueColor: AmberTheme.accentGreen
                )
            }
            .padding(.top, 18)

            SearchServicesNote {
                Text("需要 API Key 的搜索服务会在原生执行器完成后再开放。")
            }
        }
    }
}

private struct SearchBuiltInSource: Identifiable {
    let id = UUID()
    let name: String
    let badge: String?
    let badgeStyle: SearchSourceBadgeStyle
    let detail: String
}

private struct SearchConfiguredProvider: Identifiable {
    let id = UUID()
    let name: String
    let detail: String
    let badge: String
    let badgeStyle: SearchSourceBadgeStyle
}

private enum SearchSourceBadgeStyle {
    case free
    case noKey
    case plain

    var foreground: Color {
        switch self {
        case .free:
            AmberTheme.accentGreen
        case .noKey:
            AmberTheme.accentCyan
        case .plain:
            AmberTheme.muted
        }
    }

    var background: Color {
        foreground.opacity(0.12)
    }
}

private struct SearchStatusRow: View {
    let systemImage: String?
    var iconColor: Color = AmberTheme.accent
    let title: String
    let subtitle: String?
    let value: String
    var valueColor: Color = AmberTheme.muted

    var body: some View {
        HStack(spacing: 12) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 32, height: 32)
                    .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(4)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(valueColor)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct SearchSourceStatusRow: View {
    let source: SearchBuiltInSource

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(source.name)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)

                    if let badge = source.badge {
                        Text(badge)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(source.badgeStyle.foreground)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(source.badgeStyle.background, in: Capsule())
                    }
                }

                Text(source.detail)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(source.badge == "已接" ? "可用" : "未开放")
                .font(.caption.weight(.semibold))
                .foregroundStyle(source.badge == "已接" ? AmberTheme.accentGreen : AmberTheme.accentAmber)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct SearchTypeRow: View {
    let provider: SearchConfiguredProvider

    var body: some View {
        let isAddable = provider.badge == "可新增"

        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(provider.name)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)

                    Text(provider.badge)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(provider.badgeStyle.foreground)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(provider.badgeStyle.background, in: Capsule())
                }

                Text(provider.detail)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(isAddable ? "可新增" : "不可编辑")
                .font(.caption.weight(.semibold))
                .foregroundStyle(isAddable ? AmberTheme.accentGreen : AmberTheme.accentAmber)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct SearchRecommendationRow: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
            Text(detail)
                .font(.caption)
                .foregroundStyle(AmberTheme.accent)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct SearchServicesDivider: View {
    var leading: CGFloat = 14

    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, leading)
    }
}

private struct SearchPresetToggleRow: View {
    let title: String
    let isOn: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(isOn ? "开启" : "关闭")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(isOn ? AmberTheme.accentGreen : AmberTheme.muted2)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .accessibilityLabel("\(title)\(isOn ? "，开启" : "，关闭")")
    }
}

private struct SearchServicesNote<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

#Preview {
    NavigationStack {
        SearchServicesView(sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
