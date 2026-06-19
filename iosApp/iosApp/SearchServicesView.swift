import SwiftUI
import Shared

struct SearchServicesView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    private let builtInSources: [SearchBuiltInSource] = [
        .init(name: "Jina Search / Reader", badge: "内置", badgeStyle: .noKey, detail: "Android 字段 searchBuiltinJinaEnabled；iOS search_web 当前未走 Jina/Reader。"),
        .init(name: "DuckDuckGo Lite", badge: "已接", badgeStyle: .noKey, detail: "iOS ChatViewModel 已声明 search_web，并由 IOSSearchExecutor 执行 DuckDuckGo Lite。"),
        .init(name: "Bing HTML", badge: "已接", badgeStyle: .free, detail: "Android 默认 SearchServiceOptions.DEFAULT；iOS 选中 BingLocalOptions 时执行 Bing HTML。"),
        .init(name: "Wikipedia", badge: "内置", badgeStyle: .noKey, detail: "Android 字段 searchBuiltinWikipediaEnabled；iOS 未接内置源开关。"),
        .init(name: "Hacker News", badge: "内置", badgeStyle: .noKey, detail: "Android 字段 searchBuiltinHackerNewsEnabled；iOS 未接内置源开关。"),
        .init(name: "Google WebView 兜底", badge: "兜底", badgeStyle: .plain, detail: "Android 字段 searchGoogleWebViewFallbackEnabled；iOS 未接 WebView 搜索工具。")
    ]

    private let repositorySearchTypes: [SearchConfiguredProvider] = [
        .init(name: "Bing HTML 兜底", detail: "SearchServiceOptions.DEFAULT；iOS 添加页仅开放这个可执行类型。", badge: "可新增", badgeStyle: .free),
        .init(name: "Jina", detail: "SearchServiceOptions.JinaOptions，含 Search / Reader URL；iOS 暂只读展示。", badge: "只读", badgeStyle: .noKey),
        .init(name: "Tavily / Exa / Brave", detail: "仓库已有对应 SearchService 与 API Key 字段；iOS 原生执行器未接。", badge: "未开放", badgeStyle: .plain),
        .init(name: "Serper / SerpAPI", detail: "仓库已有 Google 结果服务类型与编辑器；iOS 原生执行器未接。", badge: "未开放", badgeStyle: .plain),
        .init(name: "SearXNG", detail: "仓库已有自托管 URL、引擎、语言、用户名和密码字段；iOS 添加页不开放。", badge: "只读", badgeStyle: .free),
        .init(name: "Perplexity / Firecrawl / Grok", detail: "仓库已有服务类型；iOS 不再允许从添加页保存为默认服务。", badge: "未开放", badgeStyle: .plain)
    ]

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
                        builtInSection
                        seededServiceDetailSection
                        repositoryTypesSection
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
        Text("Android/KMP 已有搜索服务、设置字段和 search_web / scrape_web 工具链路；iOS 当前已把 search_web/scrape_web 接入 ChatViewModel + IOSSearchExecutor，并按 searchServices 选中/启用状态选择 Bing HTML 或 DuckDuckGo Lite fallback。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 2)
    }

    /// Read-only view of the REAL seeded search settings (built-in source toggles
    /// + the master enableWebSearch switch + count of seeded SearchServiceOptions),
    /// sourced from `IOSSharedSettingsStore` → KMP `IosSettingsDefaults`. Proves the
    /// real-settings read path is wired for this module. Add/remove happens in
    /// SearchProviderView and feeds the iOS search execution selection.
    private var presetServicesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "预置搜索设置（KMP 默认 · 只读）")

            AmberFormGroup {
                SearchPresetToggleRow(title: "Agent 网络搜索", isOn: sharedSettings.enableWebSearch)
                SearchServicesDivider()
                SearchPresetToggleRow(title: "Jina Search / Reader", isOn: sharedSettings.searchBuiltinJinaEnabled)
                SearchServicesDivider()
                SearchPresetToggleRow(title: "DuckDuckGo Lite", isOn: sharedSettings.searchBuiltinDuckDuckGoEnabled)
                SearchServicesDivider()
                SearchPresetToggleRow(title: "Bing HTML", isOn: sharedSettings.searchBuiltinBingEnabled)
                SearchServicesDivider()
                SearchPresetToggleRow(title: "Wikipedia", isOn: sharedSettings.searchBuiltinWikipediaEnabled)
                SearchServicesDivider()
                SearchPresetToggleRow(title: "Hacker News", isOn: sharedSettings.searchBuiltinHackerNewsEnabled)
                SearchServicesDivider()
                SearchPresetToggleRow(title: "Google WebView 兜底", isOn: sharedSettings.searchGoogleWebViewFallbackEnabled)
                SearchServicesDivider()
                SearchStatusRow(
                    systemImage: "list.bullet.rectangle",
                    iconColor: AmberTheme.accentCyan,
                    title: "预置服务条目",
                    subtitle: "Android/KMP DEFAULT_PROVIDERS 种子中的 SearchServiceOptions 条目，只读展示",
                    value: "\(sharedSettings.searchServices.count) 条",
                    valueColor: AmberTheme.foreground2
                )
            }

            SearchServicesNote {
                Text("这些开关与计数来自 KMP Settings 真实 seed（IosSettingsDefaults），只读展示当前默认状态。新增 provider 会保存并进入执行选择。")
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
                    subtitle: "iOS ChatViewModel 读取 snapshot.enableWebSearch；开启时声明 search_web/scrape_web，并在工具调用后用 IOSSearchExecutor 回填结果继续生成。",
                    value: "工具已接",
                    valueColor: AmberTheme.accentGreen
                )
                SearchServicesDivider()
                SearchStatusRow(
                    systemImage: "doc.text.magnifyingglass",
                    iconColor: AmberTheme.accentGreen,
                    title: "网页抓取与 provider 编排",
                    subtitle: "scrape_web 采用安全公网 URL 直抓正文 MVP；search_web 会消费 searchServiceSelected/searchEnabledServiceIds。API provider 原生执行器未实现时明确 fallback/unsupported。",
                    value: "MVP 已接",
                    valueColor: AmberTheme.accentGreen
                )
            }
            .padding(.top, 18)

            SearchServicesNote {
                Text("新增搜索服务当前只开放 Bing HTML：保存后写入 snapshot.searchServices，并成为默认选中/启用服务。其它 provider 只读展示，等原生执行器接上再开放。")
            }
        }
    }

    private var builtInSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Android 内置源")
            AmberFormGroup {
                ForEach(Array(builtInSources.enumerated()), id: \.element.id) { index, source in
                    SearchSourceStatusRow(source: source)

                    if index < builtInSources.count - 1 {
                        SearchServicesDivider()
                    }
                }
            }

            SearchServicesNote {
                Text("这些来源来自 Android Settings 默认字段；iOS 没有对应持久化字段或执行器时不显示启用数量。")
            }
        }
    }

    /// Read-only view of the REAL seeded search service instances + the KMP
    /// DEFAULT service type, from `sharedSettings` (IosSettingsDefaults seed).
    /// Shows the actual default service (BingLocalOptions) and each seeded
    /// SearchServiceOptions entry's type, proving the read path carries real
    /// service identities — not just toggle booleans. Read-only.
    private var seededServiceDetailSection: some View {
        let defaultService = SearchServiceOptions.companion.DEFAULT
        let defaultTypeName = String(describing: type(of: defaultService))
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "KMP 种子服务实例（只读）")
            AmberFormGroup {
                SearchStatusRow(
                    systemImage: "star.fill",
                    iconColor: AmberTheme.accentAmber,
                    title: "KMP 默认服务",
                    subtitle: "SearchServiceOptions.DEFAULT（companion 单例）",
                    value: defaultTypeName,
                    valueColor: AmberTheme.foreground2
                )

                ForEach(Array(sharedSettings.searchServices.enumerated()), id: \.offset) { index, service in
                    SearchServicesDivider()
                    SearchStatusRow(
                        systemImage: "doc.text",
                        iconColor: AmberTheme.accentCyan,
                        title: "种子服务 #\(index + 1)",
                        subtitle: "来自 Settings.searchServices 真实 seed",
                        value: String(describing: type(of: service)),
                        valueColor: AmberTheme.foreground2
                    )
                }
            }

            SearchServicesNote {
                Text("这些是 KMP Settings 真实 seed 的 SearchServiceOptions 实例（只读展示类型与 DEFAULT 单例）。右上角 + 目前只允许新增 iOS 能真实执行的 Bing HTML。")
            }
        }
    }

    private var repositoryTypesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "仓库已有服务类型")
            AmberFormGroup {
                ForEach(Array(repositorySearchTypes.enumerated()), id: \.element.id) { index, provider in
                    SearchTypeRow(provider: provider)

                    if index < repositorySearchTypes.count - 1 {
                        SearchServicesDivider(leading: 14)
                    }
                }
            }

            SearchServicesNote {
                Text("右上角 + 只开放 Bing HTML；其它类型等 iOS 原生执行器接上后再从添加页放开。")
            }
        }
    }

    private var commonOptionsSection: some View {
        let resultSize = sharedSettings.snapshot.searchCommonOptions.resultSize
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "通用选项")
            AmberFormGroup {
                SearchStatusRow(
                    systemImage: "list.bullet",
                    iconColor: AmberTheme.accent,
                    title: "结果数量",
                    subtitle: "KMP SearchCommonOptions 真实 seed 默认值（resultSize）；iOS 当前只读展示。",
                    value: "\(resultSize)",
                    valueColor: AmberTheme.foreground2
                )
            }
        }
    }

    private var recommendationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "组合建议")
            AmberFormGroup {
                SearchRecommendationRow(title: "零成本", detail: "Android 可用 Jina / DuckDuckGo / Bing 等内置路径")
                SearchServicesDivider(leading: 14)
                SearchRecommendationRow(title: "Google 结果", detail: "Android 可配置 Serper 或 SerpAPI")
                SearchServicesDivider(leading: 14)
                SearchRecommendationRow(title: "高质量多源", detail: "Android 可配置 Tavily、Exa、Brave 等服务")
            }

            SearchServicesNote {
                Text("这里是产品建议，不代表 iOS 已保存或启用这些服务。")
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

            Text(source.badge == "已接" ? "已接" : "缺口")
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

            Text(isAddable ? "可新增" : "只读")
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

/// Read-only toggle row for a real seeded search setting (from IOSSharedSettingsStore).
/// The switch is display-only and never mutates the KMP seed snapshot.
private struct SearchPresetToggleRow: View {
    let title: String
    let isOn: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

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
                .opacity(0.9)
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
