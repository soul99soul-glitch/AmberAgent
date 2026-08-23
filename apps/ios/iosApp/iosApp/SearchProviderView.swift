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
                        enabledSection
                        draftPreviewSection
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
        SearchProviderNote("选择一个 iOS 已接入的搜索服务。需要 API Key 的服务会保存在本机设置中，并立即成为聊天默认搜索服务。")
            .padding(.top, 2)
    }

    private var providerFields: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                SearchProviderMenuRow(title: "服务类型", value: providerType.title) {
                    ForEach(SearchProviderType.executableCases) { type in
                        Button(type.title) {
                            applyProviderType(type)
                        }
                    }
                }

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
                        placeholder: "可选用户名",
                        monospace: true
                    )
                    SearchProviderDivider()
                    SearchProviderTextFieldRow(
                        title: "Password",
                        text: $searXNGPassword,
                        placeholder: "可选密码",
                        monospace: true,
                        isSecure: true
                    )
                }
            }
            .padding(.top, 12)

            SearchProviderNote(providerType.note)
        }
    }

    private var enabledSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "启用状态")
            AmberFormGroup {
                SearchProviderStatusRow(
                    title: "服务启用",
                    subtitle: "保存会写入真实搜索服务配置，并成为当前执行路由。",
                    value: canSave ? "可保存" : "缺少 Key",
                    valueColor: canSave ? AmberTheme.accentGreen : AmberTheme.accentAmber
                )
            }
        }
    }

    private var draftPreviewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "已保存搜索服务")
            AmberFormGroup {
                let providers = sharedSettings.savedSearchProviders
                if providers.isEmpty {
                    Text("暂无自定义搜索服务。可添加已接入的搜索服务作为聊天默认搜索。")
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
                            if let serviceId = provider["serviceId"] {
                                Toggle("", isOn: Binding(get: {
                                    sharedSettings.snapshot.searchEnabledServiceIds.contains { $0.description() == serviceId }
                                }, set: { enabled in
                                    sharedSettings.setSearchProviderEnabled(serviceId: serviceId, enabled: enabled)
                                }))
                                .labelsHidden()
                                .toggleStyle(.switch)
                                .tint(AmberTheme.accent)
                            }
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
                    sharedSettings.setEnableWebSearch(true)
                    apiKey = ""
                } label: {
                    Label(canSave ? "保存服务" : "填写 API Key 后保存", systemImage: "plus.circle.fill")
                        .font(.body.weight(.semibold)).foregroundStyle(canSave ? AmberTheme.accent : AmberTheme.muted2)
                }.buttonStyle(.plain).disabled(!canSave).frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14).padding(.vertical, 8)
            }

            SearchProviderNote("保存后重启仍然保留。删除单条请使用列表右侧按钮。")
        }
    }

    private var canSave: Bool {
        if providerType.requiresAPIKey {
            return !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return true
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
        case .bing, .searXNG, .jina:
            false
        default:
            true
        }
    }

    var note: String {
        switch self {
        case .bing:
            "无需 API Key，保存后即可用于聊天搜索。"
        case .tavily:
            "Tavily 使用官方 Search API，支持普通搜索和近期新闻查询。"
        case .exa:
            "Exa 使用官方 Search API，适合资料检索和网页内容摘要。"
        case .zhipu:
            "智谱使用 BigModel Web Search API。"
        case .brave:
            "Brave 使用 Brave Search API。"
        case .serper:
            "Serper 使用 Google Serper API，支持普通和新闻搜索。"
        case .serpAPI:
            "SerpAPI 使用 Google Search API，支持普通和新闻搜索。"
        case .jina:
            "Jina 可使用默认搜索端点，API Key 可选。"
        case .searXNG:
            "SearXNG 当前不可用。"
        default:
            "\(title) 当前不可用。"
        }
    }
}

private extension SearchProviderType {
    static let executableCases: [SearchProviderType] = [
        .bing,
        .tavily,
        .exa,
        .zhipu,
        .brave,
        .serper,
        .serpAPI,
        .jina
    ]
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
