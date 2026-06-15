import SwiftUI

struct SearchProviderView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var providerType: SearchProviderType = .serper
    @State private var apiKey = "········"
    @State private var searXNGURL = "https://search.example.com"
    @State private var searXNGEngines = "google,bing"
    @State private var searXNGLanguage = "zh-CN"
    @State private var searXNGUsername = ""
    @State private var searXNGPassword = ""
    @State private var searchEnabled = true
    @State private var scrapeEnabled = true
    @State private var serviceEnabled = true

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        providerFields
                        capabilitiesSection
                        enabledSection
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

            Text("编辑搜索服务")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)

            Spacer()

            Button {
                dismiss()
            } label: {
                Text("完成")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(height: 36)
                    .padding(.horizontal, 14)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .accessibilityLabel("完成")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var providerFields: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                SearchProviderMenuRow(title: "服务类型", value: providerType.title) {
                    ForEach(SearchProviderType.allCases) { type in
                        Button(type.title) {
                            providerType = type
                        }
                    }
                }

                SearchProviderDivider()

                if providerType == .searXNG {
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
                        placeholder: "可选",
                        monospace: true
                    )
                    SearchProviderDivider()
                    SearchProviderTextFieldRow(
                        title: "Password",
                        text: $searXNGPassword,
                        placeholder: "可选",
                        monospace: true,
                        isSecure: true
                    )
                } else {
                    SearchProviderTextFieldRow(
                        title: "API Key",
                        text: $apiKey,
                        placeholder: "粘贴 API Key",
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
            AmberSectionLabel(text: "能力")
            AmberFormGroup {
                SearchProviderToggleRow(
                    title: "搜索",
                    subtitle: "用于 search_web 返回结果列表",
                    isOn: $searchEnabled
                )
                SearchProviderDivider()
                SearchProviderToggleRow(
                    title: "抓取",
                    subtitle: "用于 scrape_web 读取网页正文",
                    isOn: $scrapeEnabled
                )
            }
        }
    }

    private var enabledSection: some View {
        AmberFormGroup {
            SearchProviderToggleRow(
                title: "启用此服务",
                subtitle: nil,
                isOn: $serviceEnabled
            )
        }
        .padding(.top, 20)
    }

    private var deleteSection: some View {
        AmberFormGroup {
            Button {
                dismiss()
            } label: {
                Text("删除服务")
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 20)
    }
}

private enum SearchProviderType: String, CaseIterable, Identifiable {
    case serper = "Serper"
    case serpAPI = "SerpAPI"
    case tavily = "Tavily"
    case brave = "Brave"
    case searXNG = "SearXNG"

    var id: String { rawValue }
    var title: String { rawValue }

    var note: String {
        switch self {
        case .searXNG:
            "SearXNG 可使用自托管实例；用户名和密码留空时按匿名实例访问。"
        default:
            "API Key 存入 Keychain；留空保存不会覆盖已存 Key。"
        }
    }
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

private struct SearchProviderToggleRow: View {
    let title: String
    let subtitle: String?
    @Binding var isOn: Bool

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
        SearchProviderView()
    }
}
