import SwiftUI
import Shared

struct ProviderDetailView: View {
    @Bindable var settingsStore: SettingsStore
    let providerRegistry: ProviderRegistryStore
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    let providerName: String
    let endpoint: String
    let providerKind: ProviderRouteKind

    @State private var selectedTab: ProviderDetailTab = .config
    @State private var alert: ProviderDetailAlert?
    @State private var connectionStatus: ProviderConnectionStatus = .idle

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
                        Color.clear
                            .frame(width: 44, height: 44)
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
                    ProviderStaticRow(
                        title: "接口协议",
                        subtitle: "当前聊天使用 OpenAI 兼容接口。",
                        value: protocolLabel
                    )
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

                if isCurrentProvider {
                    connectionTestSection
                }

                AmberSectionLabel(text: "选项")
                AmberFormGroup {
                    ProviderStaticRow(
                        title: "Response API",
                        subtitle: "当前聊天使用 Chat Completions 路径。",
                        value: responseAPIValue
                    )
                    ProviderDetailDivider()
                    ProviderStaticRow(
                        title: "账户余额",
                        subtitle: "不会自动向服务商查询余额。",
                        value: "未查询"
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
                            summary: "当前聊天会默认使用这个模型"
                        ) {
                            router.navigate(to: .modelDefaults)
                        }
                    }

                    Text("需要更换模型时，请到默认模型页选择。")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
                } else {
                    AmberFormGroup {
                        if presetSeededModels.isEmpty {
                            ProviderStaticRow(
                                title: "内置模型",
                                subtitle: "这个模板暂未提供模型列表",
                                value: "无"
                            )
                        } else {
                            ForEach(Array(presetSeededModels.enumerated()), id: \.offset) { index, model in
                                ProviderStaticRow(
                                    title: model.displayName,
                                    subtitle: "模板提供的模型",
                                    value: model.modelId,
                                    valueStyle: .mono
                                )
                                if index < presetSeededModels.count - 1 {
                                    ProviderDetailDivider()
                                }
                            }
                        }
                    }

                    Text("模板模型仅用于参考；当前聊天模型请在默认模型页选择。")
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

    /// The live provider entry from the persisted snapshot matching this preset's
    /// name. The API key lives here once the user fills it (Android model).
    private var matchedLiveProvider: ProviderSetting? {
        sharedSettings.snapshot.providers.first { ($0.name as String) == providerName }
    }

    /// True if the live snapshot's matching provider already has a non-empty
    /// API key in its ProviderSetting.
    private func presetHasApiKey(_ preset: ProviderSetting) -> Bool {
        guard let live = matchedLiveProvider else { return false }
        if let openAI = live as? ProviderSetting.OpenAI { return !openAI.apiKey.isEmpty }
        if let claude = live as? ProviderSetting.Claude { return !claude.apiKey.isEmpty }
        if let google = live as? ProviderSetting.Google { return !google.apiKey.isEmpty }
        return false
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
        requiresProviderBridge ? "Google 专用接口" : "OpenAI 兼容"
    }

    private var scopeSubtitle: String {
        if isCurrentProvider {
            return "当前聊天正在使用这组配置"
        }

        if requiresProviderBridge {
            return "这个服务商类型暂不支持直接设为当前"
        }

        if requiresEndpointConfirmation {
            return "模板地址需要你确认后再使用"
        }

        if requiresResponseAPIBridge {
            return "这个模板需要不同的接口模式"
        }

        return "保存 API Key 后可设为当前"
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
                ProviderInlineWarning("URL 格式无效，请修正后再发送消息。")
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
                subtitle: "服务商模板地址",
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
    /// Presets whose protocol iOS can actually run in chat — OpenAI-compatible
    /// and Anthropic/Claude (after the :ai-provider-claude bridge) — get a real
    /// Key editor that writes the key into the provider's own ProviderSetting in
    /// the persistent `Settings.providers` snapshot (Android model; the key no
    /// longer lives in a separate iOS per-provider Keychain slot). Protocols that
    /// genuinely can't run yet (Gemini Google type, xAI Response API, MiMo
    /// placeholder base) keep the static "未预置" state.
    @ViewBuilder
    private var presetAPIKeyRow: some View {
        if let preset = matchedPreset, ProviderRouteKind.isEditablePreset(preset) {
            let hasKey = presetHasApiKey(preset)
            ProviderValueRow(
                title: "API Key",
                value: hasKey ? "已保存" : "未保存",
                showsChevron: true
            ) {
                router.navigate(to: .providerKeyEditor(name: providerName))
            }
        } else {
            ProviderStaticRow(
                title: "API Key",
                subtitle: "模板不会自带你的密钥",
                value: "未预置"
            )
        }
    }

    private var presetApplyValue: String {
        if requiresProviderBridge {
            return "暂不支持"
        }

        if requiresEndpointConfirmation {
            return "需要确认"
        }

        if requiresResponseAPIBridge {
            return "暂不支持"
        }

        return settingsStore.baseUrl == endpoint ? "已是当前地址" : "写入当前配置"
    }

    private var presetPathSubtitle: String {
        if requiresProviderBridge {
            return "该服务商使用专用接口"
        }

        if requiresResponseAPIBridge {
            return "该模板使用不同接口模式"
        }

        return "当前聊天使用"
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
            return "这里编辑当前聊天服务商。新增服务商请从服务商列表右上角 + 创建。"
        }

        if requiresProviderBridge {
            return "这个模板当前仅供查看，暂不能设为聊天服务商。"
        }

        if requiresEndpointConfirmation {
            return "这个模板的地址需要确认，当前不会一键写入聊天配置。"
        }

        if requiresResponseAPIBridge {
            return "这个模板需要不同接口模式，当前不会只套用地址。"
        }

        return "API Key 会保存在本机钥匙串。保存后可在列表里设为当前。"
    }

    private var connectionTestSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "验证")
            AmberFormGroup {
                Button {
                    testCurrentConnection()
                } label: {
                    ProviderRowContent(
                        title: "测试连接",
                        subtitle: "调用模型列表接口，不发送聊天内容",
                        value: connectionStatus.buttonValue,
                        valueStyle: .normal,
                        showsChevron: false
                    )
                }
                .buttonStyle(.plain)
                .disabled(connectionStatus.isTesting)
                .opacity(connectionStatus.isTesting ? 0.65 : 1)

                if let message = connectionStatus.message {
                    ProviderDetailDivider()
                    ProviderConnectionResultRow(status: connectionStatus, message: message)
                }
            }

            ProviderDetailFooter("测试结果来自服务商真实响应；保存 API Key 不会被当作连接成功。")
        }
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

    private func testCurrentConnection() {
        let trimmedKey = settingsStore.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty else {
            connectionStatus = .failure(ChatConfigurationIssue.missingAPIKey.message)
            return
        }
        guard isBaseUrlValid else {
            connectionStatus = .failure(ChatConfigurationIssue.invalidBaseURL.message)
            return
        }

        connectionStatus = .testing
        let provider = OpenAIKmpProvider()
        let setting = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "OpenAI",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: trimmedKey,
            baseUrl: settingsStore.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines),
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )

        Task { @MainActor in
            do {
                let models = try await provider.listModels(providerSetting: setting)
                if models.isEmpty {
                    connectionStatus = .success("连接成功，但服务商没有返回可列出的模型。")
                } else {
                    connectionStatus = .success("连接成功，发现 \(models.count) 个模型。")
                }
            } catch {
                connectionStatus = .failure(
                    ChatViewModel.userFacingGenerationError(
                        error.localizedDescription,
                        modelId: settingsStore.modelId
                    )
                )
            }
        }
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
            "暂不能切换接口协议"
        case .presetApplied:
            "API 地址已套用"
        case .providerBridgeRequired:
            "暂不支持这个服务商类型"
        case .endpointConfirmationRequired:
            "Base URL 需要确认"
        case .responseAPIRequired:
            "暂不支持这个接口模式"
        }
    }

    var message: String {
        switch self {
        case .protocolPicker:
            "当前版本只支持 OpenAI 兼容接口。"
        case .presetApplied(let provider):
            "\(provider) 的预置 Base URL 已写入当前聊天配置。API Key 仍为空或保持你已有的 Keychain 值；本操作没有发起网络请求。"
        case .providerBridgeRequired(let provider):
            "\(provider) 使用专用接口，当前版本还不能直接设为聊天服务商。"
        case .endpointConfirmationRequired(let provider):
            "\(provider) 的模板地址需要确认，当前不会写入聊天配置。"
        case .responseAPIRequired(let provider):
            "\(provider) 使用不同接口模式，当前不能只套用 Base URL。"
        }
    }
}

private enum ProviderConnectionStatus: Equatable {
    case idle
    case testing
    case success(String)
    case failure(String)

    var isTesting: Bool {
        if case .testing = self { return true }
        return false
    }

    var buttonValue: String {
        switch self {
        case .idle:
            "开始"
        case .testing:
            "测试中"
        case .success:
            "成功"
        case .failure:
            "失败"
        }
    }

    var message: String? {
        switch self {
        case .idle, .testing:
            return nil
        case .success(let message), .failure(let message):
            return message
        }
    }

    var color: Color {
        switch self {
        case .success:
            AmberTheme.accentGreen
        case .failure:
            AmberTheme.accentRed
        case .idle, .testing:
            AmberTheme.muted
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

private struct ProviderConnectionResultRow: View {
    let status: ProviderConnectionStatus
    let message: String

    var body: some View {
        Text(message)
            .font(.caption)
            .foregroundStyle(status.color)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
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
            sharedSettings: IOSSharedSettingsStore(),
            providerName: "OpenAI-compatible",
            endpoint: "https://api.openai.com/v1",
            providerKind: .current
        )
            .environment(RouterPath())
    }
}
