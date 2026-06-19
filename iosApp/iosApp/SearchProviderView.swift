import SwiftUI
import Shared

struct SearchProviderView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    @State private var providerType: SearchProviderType = .bing
    @State private var apiKey = ""
    @State private var searXNGURL = ""
    @State private var searXNGEngines = ""
    @State private var searXNGLanguage = ""
    @State private var searXNGUsername = ""
    @State private var searXNGPassword = ""

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        draftNotice
                        providerFields
                        capabilitiesSection
                        enabledSection
                        draftPreviewSection
                        deleteSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("搜索服务配置")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)

            Spacer()

            Button {
                dismiss()
            } label: {
                Text("关闭")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(height: 36)
                    .padding(.horizontal, 14)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .accessibilityLabel("关闭")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var draftNotice: some View {
        SearchProviderNote("本页只允许新增 iOS 当前能真实执行的搜索服务。API provider 模板仍可从 KMP snapshot 读取展示，但不会再从 iOS UI 新增为默认服务，避免保存后 search_web 返回 unsupported。")
            .padding(.top, 2)
    }

    private var providerFields: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                SearchProviderPreviewLine(label: "服务类型", value: providerType.title)

                if providerType.requiresAPIKey {
                    SearchProviderDivider()
                    SearchProviderTextFieldRow(
                        title: "API Key",
                        text: $apiKey,
                        placeholder: "可选 API Key（本机设置）",
                        monospace: true,
                        isSecure: true
                    )
                }

                if providerType == .searXNG {
                    SearchProviderDivider()
                    SearchProviderTextFieldRow(
                        title: "API URL",
                        text: $searXNGURL,
                        placeholder: "SearXNG 实例地址",
                        monospace: true
                    )
                    SearchProviderDivider()
                    SearchProviderTextFieldRow(
                        title: "Engines",
                        text: $searXNGEngines,
                        placeholder: "例如 google,bing",
                        monospace: true
                    )
                    SearchProviderDivider()
                    SearchProviderTextFieldRow(
                        title: "Language",
                        text: $searXNGLanguage,
                        placeholder: "例如 zh-CN",
                        monospace: true
                    )
                    SearchProviderDivider()
                    SearchProviderTextFieldRow(
                        title: "Username",
                        text: $searXNGUsername,
                        placeholder: "可选（暂不写入）",
                        monospace: true
                    )
                    SearchProviderDivider()
                    SearchProviderTextFieldRow(
                        title: "Password",
                        text: $searXNGPassword,
                        placeholder: "可选（暂不写入）",
                        monospace: true,
                        isSecure: true
                    )
                }
            }
            .padding(.top, 12)

            SearchProviderNote(providerType.note)
        }
    }

    private var capabilitiesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "能力链路")
            AmberFormGroup {
                SearchProviderStatusRow(
                    title: "Android/KMP 服务",
                    subtitle: "SearchService.getService(...) 能按 SearchServiceOptions 分派真实搜索实现。",
                    value: "存在",
                    valueColor: AmberTheme.accentGreen
                )
                SearchProviderDivider()
                SearchProviderStatusRow(
                    title: "iOS 工具调用",
                    subtitle: "ChatViewModel 会在 enableWebSearch 开启时声明 search_web/scrape_web，并把 Tool 输出回填后 resume。",
                    value: "工具已接",
                    valueColor: AmberTheme.accentGreen
                )
                SearchProviderDivider()
                SearchProviderStatusRow(
                    title: "抓取 / 多 provider",
                    subtitle: "IOSSearchExecutor 当前可真实执行 Bing HTML；DuckDuckGo Lite 作为内置 fallback。其它 API provider 不再从 iOS 添加页暴露。",
                    value: "收口",
                    valueColor: AmberTheme.accentGreen
                )
            }
        }
    }

    private var enabledSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "启用状态")
            AmberFormGroup {
                SearchProviderStatusRow(
                    title: "服务启用",
                    subtitle: "保存 Bing HTML 时会同步写入 searchEnabledServiceIds，并设为默认 selected provider。",
                    value: "Bing 可新增",
                    valueColor: AmberTheme.accentGreen
                )
            }
        }
    }

    private var draftPreviewSection: some View {
        VStack(spacing: 0) {
            // [Slice 4] add/remove now merge a real SearchServiceOptions subtype
            // into snapshot.searchServices via IosSettingsMutations +
            // restoreSnapshot — survives restart.
            AmberSectionLabel(text: "已保存搜索服务（持久化到 snapshot.searchServices）")
            AmberFormGroup {
                let providers = sharedSettings.savedSearchProviders
                if providers.isEmpty {
                    Text("暂无自定义搜索服务。当前只开放新增 Bing HTML。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                } else {
                    ForEach(Array(providers.enumerated()), id: \.offset) { index, provider in
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(provider["name"] ?? "?").font(.body.weight(.semibold))
                                Text("\(provider["serviceType"] ?? "?")")
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundStyle(AmberTheme.muted2)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                            Button { sharedSettings.removeSearchProvider(at: index) } label: {
                                Image(systemName: "minus.circle.fill")
                                    .font(.system(size: 18)).foregroundStyle(AmberTheme.accentRed)
                            }.buttonStyle(.plain)
                        }.frame(minHeight: 48).padding(.horizontal, 14).padding(.vertical, 4)
                        if index < providers.count - 1 {
                            SearchProviderDivider()
                        }
                    }
                }

                SearchProviderDivider()

                Button {
                    let trimmedName = providerType.title
                    sharedSettings.addSearchProvider(name: trimmedName, apiKey: apiKey, serviceType: providerType.serialName)
                    apiKey = ""
                } label: {
                    Label("保存服务", systemImage: "plus.circle.fill")
                        .font(.body.weight(.semibold)).foregroundStyle(AmberTheme.accent)
                }.buttonStyle(.plain).frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14).padding(.vertical, 8)
            }

            SearchProviderNote("搜索服务保存到 UserDefaults，重启后保留。这是真实的 iOS 本地持久化。")
        }
    }

    private var deleteSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                // [Slice 4] 删除单条服务已在上方"已保存搜索服务"区按行真删
                //（removeSearchProvider 现持久化到 snapshot.searchServices）。
                // 此底部入口为信息占位（未接批量删），按行删请用上方列表。
                Text("删除单条请用上方列表的 ⊖ 按钮")
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 52)
            }
            .padding(.top, 20)

            SearchProviderNote("按行删除已持久化到 snapshot.searchServices（重启生效）。批量清空入口暂未接。")
        }
    }

    private var credentialPreview: String {
        if providerType == .bing {
            return "无需 Key"
        }
        if providerType == .searXNG {
            let hasURL = !searXNGURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            return hasURL ? "URL 已填写，未保存" : "URL 待填写"
        }
        return apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "API Key 待填写" : "API Key 已填写，未保存"
    }

    private func applyProviderType(_ type: SearchProviderType) {
        providerType = type
        apiKey = ""
        searXNGURL = ""
        searXNGEngines = ""
        searXNGLanguage = ""
        searXNGUsername = ""
        searXNGPassword = ""
    }
}

private enum SearchProviderType: String, CaseIterable, Identifiable {
    case bing = "Bing HTML 兜底"
    case amberAgent = "AmberAgent"
    case zhipu = "智谱"
    case tavily = "Tavily"
    case exa = "Exa"
    case searXNG = "SearXNG"
    case linkUp = "LinkUp"
    case brave = "Brave"
    case serper = "Serper"
    case serpAPI = "SerpAPI"
    case metaso = "秘塔"
    case ollama = "Ollama"
    case perplexity = "Perplexity"
    case firecrawl = "Firecrawl"
    case jina = "Jina"
    case bocha = "博查"
    case grok = "Grok"

    var id: String { rawValue }
    var title: String { rawValue }

    var serialName: String {
        switch self {
        case .bing: "bing_local"
        case .amberAgent: "amber_agent"
        case .zhipu: "zhipu"
        case .tavily: "tavily"
        case .exa: "exa"
        case .searXNG: "searxng"
        case .linkUp: "linkup"
        case .brave: "brave"
        case .serper: "serper"
        case .serpAPI: "serpapi"
        case .metaso: "metaso"
        case .ollama: "ollama"
        case .perplexity: "perplexity"
        case .firecrawl: "firecrawl"
        case .jina: "jina"
        case .bocha: "bocha"
        case .grok: "grok"
        }
    }

    var requiresAPIKey: Bool {
        switch self {
        case .bing, .searXNG:
            false
        default:
            true
        }
    }

    var note: String {
        switch self {
        case .bing:
            "Bing HTML 是 Android 默认 SearchServiceOptions.DEFAULT；iOS 保存后会选中并用 Bing HTML 执行 search_web。"
        case .searXNG:
            "SearXNG 在 Android/KMP 中保存 URL、引擎、语言、用户名和密码；iOS 当前不从添加页暴露，避免保存后返回 unsupported。"
        case .jina:
            "Jina 在 Android/KMP 中有 apiKey、searchUrl 和 scrapeUrl；iOS 当前不从添加页暴露，scrape_web 仍使用安全直抓 MVP。"
        default:
            "\(title) 在仓库里有 SearchServiceOptions 类型；iOS 当前不从添加页暴露，直到原生执行器接上。"
        }
    }
}

private extension SearchProviderType {
    static let executableCases: [SearchProviderType] = [.bing]
}

private struct SearchProviderMenuRow<MenuContent: View>: View {
    let title: String
    let value: String
    @ViewBuilder let menuContent: MenuContent

    var body: some View {
        Menu {
            menuContent
        } label: {
            HStack(spacing: 10) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 6) {
                    Text(value)
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.foreground)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted2)
                }
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 15)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
    }
}

private struct SearchProviderTextFieldRow: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    var monospace = false
    var isSecure = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)

            Group {
                if isSecure {
                    SecureField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text)
                }
            }
            .font(monospace ? .system(size: 14, weight: .regular, design: .monospaced) : .body)
            .foregroundStyle(AmberTheme.foreground)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 58)
        .padding(.horizontal, 15)
        .padding(.vertical, 8)
    }
}

private struct SearchProviderStatusRow: View {
    let title: String
    let subtitle: String?
    let value: String
    var valueColor: Color = AmberTheme.muted

    var body: some View {
        HStack(spacing: 12) {
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
    }
}

private struct SearchProviderPreviewLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 46)
        .padding(.horizontal, 15)
        .padding(.vertical, 6)
    }
}

private struct SearchProviderDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct SearchProviderNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
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
        SearchProviderView(sharedSettings: IOSSharedSettingsStore())
    }
}
