import SwiftUI

struct SearchServicesView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    @State private var agentSearchEnabled = true
    @State private var resultSize = "10 条"
    @State private var builtInSources: [SearchBuiltInSource] = [
        .init(name: "Jina Search / Reader", badge: "免费", badgeStyle: .free, detail: "可无 Key 使用，尽量以 Markdown 搜索和读取网页", isEnabled: true),
        .init(name: "DuckDuckGo Lite", badge: "无 Key", badgeStyle: .noKey, detail: "无需 API Key 的免费公共召回源", isEnabled: true),
        .init(name: "Bing HTML", badge: "免费", badgeStyle: .free, detail: "免费公共兜底，遇到验证页会明确报错", isEnabled: true),
        .init(name: "Wikipedia", badge: "无 Key", badgeStyle: .noKey, detail: "实体和背景知识源，适合百科类问题", isEnabled: true),
        .init(name: "Hacker News", badge: "无 Key", badgeStyle: .noKey, detail: "技术、开源和 AI 工具生态讨论源", isEnabled: false),
        .init(name: "Google WebView 兜底", badge: nil, badgeStyle: .plain, detail: "普通搜索弱或深度搜索时，打开可见搜索页兜底", isEnabled: false)
    ]

    private let configuredProviders: [SearchConfiguredProvider] = [
        .init(name: "Serper", isEnabled: true),
        .init(name: "Tavily", isEnabled: false)
    ]

    private let resultSizeOptions = ["5 条", "10 条", "15 条", "20 条"]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        agentSearchSection
                        builtInSection
                        configuredSection
                        commonOptionsSection
                        recommendationSection
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
        Text("Agent 联网搜索采用多源召回：内置免费源无需 Key，外部提供商可补充 Google 结果或更高质量来源。靠前的源优先召回。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 2)
    }

    private var agentSearchSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                SearchToggleRow(
                    systemImage: "magnifyingglass",
                    iconColor: AmberTheme.accentCyan,
                    title: "Agent 网络搜索",
                    subtitle: "允许聊天调用 search_web 和 scrape_web，使用下方已启用的服务",
                    isOn: $agentSearchEnabled
                )
            }
            .padding(.top, 18)

            SearchServicesNote {
                HStack(spacing: 0) {
                    Text("\(enabledBuiltInCount) / \(builtInSources.count)")
                        .fontWeight(.bold)
                    Text(" 个服务已启用 · 多源召回")
                }
            }
        }
    }

    private var builtInSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "内置免费源")
            AmberFormGroup {
                ForEach(Array($builtInSources.enumerated()), id: \.element.id) { index, $source in
                    SearchSourceToggleRow(source: $source)

                    if index < builtInSources.count - 1 {
                        SearchServicesDivider()
                    }
                }
            }
        }
    }

    private var configuredSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "已配置")
            AmberFormGroup {
                ForEach(Array(configuredProviders.enumerated()), id: \.element.id) { index, provider in
                    Button {
                        router.navigate(to: .searchProvider)
                    } label: {
                        HStack(spacing: 12) {
                            Text(provider.name)
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                                .frame(maxWidth: .infinity, alignment: .leading)

                            Text(provider.isEnabled ? "已启用" : "已停用")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(provider.isEnabled ? AmberTheme.accentGreen : AmberTheme.muted)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(
                                    (provider.isEnabled ? AmberTheme.accentGreen : AmberTheme.surface2).opacity(provider.isEnabled ? 0.14 : 1),
                                    in: Capsule()
                                )

                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AmberTheme.muted2)
                        }
                        .frame(minHeight: 52)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    if index < configuredProviders.count - 1 {
                        SearchServicesDivider(leading: 14)
                    }
                }
            }

            SearchServicesNote {
                Text("点开可编辑能力、API Key 与启用状态。")
            }
        }
    }

    private var commonOptionsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "通用选项")
            AmberFormGroup {
                Menu {
                    ForEach(resultSizeOptions, id: \.self) { option in
                        Button(option) {
                            resultSize = option
                        }
                    }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "list.bullet")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 32, height: 32)
                            .background(AmberTheme.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                        VStack(alignment: .leading, spacing: 2) {
                            Text("结果数量")
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                            Text("每次搜索最多返回的结果条数")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        Text(resultSize)
                            .font(.subheadline)
                            .foregroundStyle(AmberTheme.muted)

                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted2)
                    }
                    .frame(minHeight: 58)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 5)
                    .contentShape(Rectangle())
                }
            }
        }
    }

    private var recommendationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "推荐组合")
            AmberFormGroup {
                SearchRecommendationRow(title: "零成本", detail: "Jina + DuckDuckGo + Bing")
                SearchServicesDivider(leading: 14)
                SearchRecommendationRow(title: "Google 结果", detail: "Serper / SerpAPI")
                SearchServicesDivider(leading: 14)
                SearchRecommendationRow(title: "高质量多源", detail: "Tavily 或 Brave")
            }

            SearchServicesNote {
                Text("按需求挑一组：零成本无需任何 Key；要 Google 结果或更高召回质量时再接入对应外部提供商。")
            }
        }
    }

    private var enabledBuiltInCount: Int {
        builtInSources.count { $0.isEnabled }
    }
}

private struct SearchBuiltInSource: Identifiable {
    let id = UUID()
    let name: String
    let badge: String?
    let badgeStyle: SearchSourceBadgeStyle
    let detail: String
    var isEnabled: Bool
}

private struct SearchConfiguredProvider: Identifiable {
    let id = UUID()
    let name: String
    let isEnabled: Bool
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

private struct SearchToggleRow: View {
    let systemImage: String?
    var iconColor: Color = AmberTheme.accent
    let title: String
    let subtitle: String?
    @Binding var isOn: Bool

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
                        .lineLimit(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(AmberTheme.accent)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct SearchSourceToggleRow: View {
    @Binding var source: SearchBuiltInSource

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
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: $source.isEnabled)
                .labelsHidden()
                .tint(AmberTheme.accent)
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
        SearchServicesView()
            .environment(RouterPath())
    }
}
