import SwiftUI
import Shared

struct ProviderDetailView: View {
    @Bindable var settingsStore: SettingsStore
    let providerRegistry: ProviderRegistryStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    let providerName: String
    let endpoint: String
    let providerKind: ProviderRouteKind

    @State private var selectedTab: ProviderDetailTab = .config
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
                        Text(isCurrentProvider ? "自动保存" : "模板")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted)
                            .frame(width: 58, height: 36)
                            .accessibilityLabel(isCurrentProvider ? "自动保存" : "预置模板")
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
                    ProviderValueRow(title: "接口协议", value: protocolLabel, showsChevron: true) {
                        alert = .protocolPicker
                    }
                    ProviderDetailDivider()
                    ProviderStaticRow(
                        title: "使用范围",
                        subtitle: scopeSubtitle,
                        value: scopeValue,
                        valueStyle: .normal
                    )
                }

                AmberSectionLabel(text: "连接")
                AmberFormGroup {
                    connectionRows
                }

                ProviderDetailFooter(connectionFooterText)

                AmberSectionLabel(text: "选项")
                AmberFormGroup {
                    ProviderStaticRow(
                        title: "Response API",
                        subtitle: "ChatViewModel 当前固定 useResponseApi = false；需要 Response API 的模板不会自动套用",
                        value: responseAPIValue
                    )
                    ProviderDetailDivider()
                    ProviderStaticRow(
                        title: "账户余额",
                        subtitle: "余额读取会触发外部请求，iOS 不做例行测试",
                        value: "未实现"
                    )
                }
            }
            .padding(.bottom, 36)
        }
        .scrollIndicators(.hidden)
    }

    @ViewBuilder
    private var modelsPanel: some View {
        ScrollView {
            VStack(spacing: 0) {
                if isCurrentProvider {
                    AmberFormGroup {
                        ProviderModelRow(
                            systemImage: "cpu",
                            name: currentModelID,
                            badge: "当前聊天模型",
                            summary: "SettingsStore.modelId · 当前唯一接入的模型"
                        ) {
                            router.navigate(to: .modelDefaults)
                        }
                    }

                    Text("当前只接入一个模型 ID 字符串。KMP Model 列表、能力、模态、上下文、custom headers/body 尚未桥接到 iOS。")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
                } else {
                    AmberFormGroup {
                        if presetSeededModels.isEmpty {
                            ProviderStaticRow(
                                title: "种子模型",
                                subtitle: "Android/KMP DEFAULT_PROVIDERS 中此预置不含种子模型",
                                value: "无"
                            )
                        } else {
                            ForEach(Array(presetSeededModels.enumerated()), id: \.offset) { index, model in
                                ProviderStaticRow(
                                    title: model.displayName,
                                    subtitle: "Android/KMP 预置种子模型 · 只读",
                                    value: model.modelId,
                                    valueStyle: .mono
                                )
                                if index < presetSeededModels.count - 1 {
                                    ProviderDetailDivider()
                                }
                            }
                        }
                    }

                    Text("这些是 Android/KMP 在该预置 Provider 上的种子模型，仅只读展示。iOS 当前不持久化模型列表、不创建模型配置、不拉取模型；预置不影响当前聊天模型。")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
                }
            }
            .padding(.top, 10)
            .padding(.bottom, 36)
        }
        .scrollIndicators(.hidden)
    }

    // Real seeded models the matched Android/KMP DEFAULT_PROVIDERS entry ships
    // for this preset (read-only; iOS does not persist a model list). Matched by
    // name — DEFAULT_PROVIDERS names are unique, and the "current" config row uses
    // a different name so it never reaches this preset-only path.
    private var presetSeededModels: [Model] {
        DefaultProvidersKt.DEFAULT_PROVIDERS.first { $0.name == providerName }?.models ?? []
    }

    /// The real KMP `ProviderSetting` for this preset, matched by unique name, or
    /// nil if this row is not a DEFAULT_PROVIDERS preset. The "current" config row
    /// uses name "OpenAI-compatible", which never matches a preset name.
    private var matchedPreset: ProviderSetting? {
        DefaultProvidersKt.DEFAULT_PROVIDERS.first { $0.name == providerName }
    }

    private var apiKeyRow: some View {
        ProviderEditableTextFieldRow(
            title: "API Key",
            text: $settingsStore.apiKey,
            placeholder: "sk-...",
            isSecure: true,
            monospace: true
        )
    }

    private var currentModelID: String {
        settingsStore.modelId.isEmpty ? "gpt-4o" : settingsStore.modelId
    }

    private var isBaseUrlValid: Bool {
        guard
            let components = URLComponents(string: settingsStore.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)),
            let scheme = components.scheme?.lowercased(),
            scheme == "http" || scheme == "https",
            let host = components.host,
            !host.isEmpty
        else {
            return false
        }

        return true
    }

    private var isCurrentProvider: Bool {
        providerKind == .current
    }

    private var requiresProviderBridge: Bool {
        providerKind == .googleProviderPreset
    }

    private var requiresEndpointConfirmation: Bool {
        providerKind == .endpointConfirmationPreset
    }

    private var requiresResponseAPIBridge: Bool {
        providerKind == .responseAPIPreset
    }

    private var protocolLabel: String {
        requiresProviderBridge ? "Google ProviderSetting" : "OpenAI-compatible"
    }

    private var scopeSubtitle: String {
        if isCurrentProvider {
            return "ChatViewModel.makeProviderSetting() 会读取此配置"
        }

        if requiresProviderBridge {
            return "来自 Android/KMP DEFAULT_PROVIDERS；iOS 尚未桥接该 Provider 类型"
        }

        if requiresEndpointConfirmation {
            return "来自 Android/KMP DEFAULT_PROVIDERS；该默认 Base URL 在源码中标记为可由用户覆盖"
        }

        if requiresResponseAPIBridge {
            return "来自 Android/KMP DEFAULT_PROVIDERS；需要 useResponseApi=true 才能完整表达"
        }

        return "来自 Android/KMP DEFAULT_PROVIDERS；可安全套用 Base URL"
    }

    private var scopeValue: String {
        isCurrentProvider ? "当前聊天" : "预置模板"
    }

    @ViewBuilder
    private var connectionRows: some View {
        ProviderStaticRow(title: "名称", subtitle: nil, value: providerName)
        ProviderDetailDivider()

        if isCurrentProvider {
            apiKeyRow
            ProviderDetailDivider()
            ProviderEditableTextFieldRow(
                title: "API 地址",
                text: $settingsStore.baseUrl,
                placeholder: "https://api.openai.com/v1",
                monospace: true
            )
            if !isBaseUrlValid {
                ProviderInlineWarning("URL 格式无效；ChatViewModel 会使用该值发起请求，发送前请修正。")
            }
            ProviderDetailDivider()
            ProviderStaticRow(
                title: "路径",
                subtitle: "由接口协议决定",
                value: "/chat/completions",
                valueStyle: .monoMuted
            )
        } else {
            presetAPIKeyRow
            ProviderDetailDivider()
            ProviderStaticRow(
                title: "预置 API 地址",
                subtitle: "来自 Android/KMP DEFAULT_PROVIDERS",
                value: endpoint,
                valueStyle: .mono
            )
            ProviderDetailDivider()
            ProviderValueRow(
                title: "套用 API 地址",
                value: presetApplyValue,
                showsChevron: true
            ) {
                applyPresetBaseURL()
            }
            ProviderDetailDivider()
            ProviderStaticRow(
                title: "路径",
                subtitle: presetPathSubtitle,
                value: presetPathValue,
                valueStyle: .monoMuted
            )
        }
    }

    /// API Key row for a preset provider.
    ///
    /// Only presets the current scalar chat chain can faithfully represent
    /// (`canActivate`: OpenAI-compatible, non-Response-API, non-MiMo) get a real
    /// Key editor that writes the per-provider Keychain slot. Providers the chain
    /// cannot represent (Gemini Google type, xAI Response API, MiMo placeholder)
    /// keep the static "未预置" state, because even with a key they could not be
    /// projected without faking provider identity.
    @ViewBuilder
    private var presetAPIKeyRow: some View {
        // Track keyRevision so the row re-renders after the Key editor writes a
        // key (hasStoredKey reads the Keychain via a static helper, not an
        // observable property, so the revision is the tracked dependency).
        let _ = providerRegistry.keyRevision
        if let preset = matchedPreset, providerRegistry.canActivate(preset) {
            let hasKey = providerRegistry.hasStoredKey(preset)
            ProviderValueRow(
                title: "API Key",
                value: hasKey ? "已保存（Keychain）" : "未保存",
                showsChevron: true
            ) {
                router.navigate(to: .providerKeyEditor(name: providerName))
            }
        } else {
            ProviderStaticRow(
                title: "API Key",
                subtitle: "DEFAULT_PROVIDERS 不包含凭据",
                value: "未预置"
            )
        }
    }

    private var presetApplyValue: String {
        if requiresProviderBridge {
            return "需桥接"
        }

        if requiresEndpointConfirmation {
            return "Base 待确认"
        }

        if requiresResponseAPIBridge {
            return "需 Response API"
        }

        return settingsStore.baseUrl == endpoint ? "已是当前地址" : "写入当前配置"
    }

    private var presetPathSubtitle: String {
        if requiresProviderBridge {
            return "需要 Google Provider bridge"
        }

        if requiresResponseAPIBridge {
            return "Android 默认 useResponseApi = true；iOS 未保存该开关"
        }

        return "当前 ChatViewModel 固定使用"
    }

    private var presetPathValue: String {
        if requiresProviderBridge {
            return "/models/{model}:generateContent"
        }

        if requiresResponseAPIBridge {
            return "/responses"
        }

        return "/chat/completions"
    }

    private var responseAPIValue: String {
        requiresResponseAPIBridge ? "模板需要" : "未启用"
    }

    private var connectionFooterText: String {
        if isCurrentProvider {
            return "API 地址写入 UserDefaults；API Key 字段绑定 SettingsStore.apiKey（既有 Keychain account）。当前不会创建多服务商 ProviderSetting。"
        }

        if requiresProviderBridge {
            return "此模板只展示 Android/KMP 预置 Provider 信息；iOS 当前不会把它保存为 Google ProviderSetting，也不会写入 API Key 或发起请求。"
        }

        if requiresEndpointConfirmation {
            return "此模板在 Android/KMP 中存在，但默认 Base URL 被源码注释标记为占位/可覆盖。iOS 当前不一键写入，避免把待确认地址当作可用配置。"
        }

        if requiresResponseAPIBridge {
            return "此模板在 Android/KMP 中要求 useResponseApi=true；iOS 当前无法保存该开关，所以不只套用 Base URL。"
        }

        return "API Key 行进入真实 Key 编辑页，只写入该 provider 的 Keychain account（不写入 UserDefaults、不改当前服务商、不发网络请求）。保存 Key 后该模板即可在列表里「设为当前」。套用地址只写入 SettingsStore.baseUrl。"
    }

    private func applyPresetBaseURL() {
        guard !requiresProviderBridge else {
            alert = .providerBridgeRequired(providerName)
            return
        }

        guard !requiresEndpointConfirmation else {
            alert = .endpointConfirmationRequired(providerName)
            return
        }

        guard !requiresResponseAPIBridge else {
            alert = .responseAPIRequired(providerName)
            return
        }

        settingsStore.baseUrl = endpoint
        alert = .presetApplied(providerName)
    }
}

private enum ProviderDetailTab: String, CaseIterable, Identifiable {
    case config = "配置"
    case models = "模型"

    var id: String { rawValue }
}

private enum ProviderDetailAlert: Identifiable {
    case protocolPicker
    case presetApplied(String)
    case providerBridgeRequired(String)
    case endpointConfirmationRequired(String)
    case responseAPIRequired(String)

    var id: String {
        switch self {
        case .protocolPicker:
            "protocol-picker"
        case .presetApplied(let provider):
            "preset-applied-\(provider)"
        case .providerBridgeRequired(let provider):
            "provider-bridge-required-\(provider)"
        case .endpointConfirmationRequired(let provider):
            "endpoint-confirmation-required-\(provider)"
        case .responseAPIRequired(let provider):
            "response-api-required-\(provider)"
        }
    }

    var title: String {
        switch self {
        case .protocolPicker:
            "接口协议选择未实现"
        case .presetApplied:
            "API 地址已套用"
        case .providerBridgeRequired:
            "Provider 类型尚未桥接"
        case .endpointConfirmationRequired:
            "Base URL 需要确认"
        case .responseAPIRequired:
            "Response API 未实现"
        }
    }

    var message: String {
        switch self {
        case .protocolPicker:
            "iOS 当前聊天链路只构造 ProviderSetting.OpenAI，暂不切换 Google / Claude / 其他 ProviderSetting 类型。"
        case .presetApplied(let provider):
            "\(provider) 的预置 Base URL 已写入当前聊天配置。API Key 仍为空或保持你已有的 Keychain 值；本操作没有发起网络请求。"
        case .providerBridgeRequired(let provider):
            "\(provider) 使用的 ProviderSetting 类型还没有接到 iOS 当前聊天链路；这里只展示预置模板，不会保存或请求。"
        case .endpointConfirmationRequired(let provider):
            "\(provider) 的 Android 默认 Base URL 在源码中标记为占位/可覆盖。iOS 当前不会把它写入真实聊天配置。"
        case .responseAPIRequired(let provider):
            "\(provider) 的 Android 默认 Provider 使用 Response API。iOS 当前 ChatViewModel 固定 chat/completions，不能只套用 Base URL。"
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

private struct ProviderEditableTextFieldRow: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    var isSecure = false
    var monospace = false

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

private struct ProviderInlineWarning: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.accentAmber)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.bottom, 8)
    }
}

private struct ProviderDetailFooter: View {
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
    let settings = SettingsStore()
    return NavigationStack {
        ProviderDetailView(
            settingsStore: settings,
            providerRegistry: ProviderRegistryStore(settingsStore: settings),
            providerName: "OpenAI-compatible",
            endpoint: "https://api.openai.com/v1",
            providerKind: .current
        )
            .environment(RouterPath())
    }
}
