import SwiftUI
import Shared

enum ProviderRouteKind: String, Hashable {
    case openAICompatiblePreset
    case claudePreset
    case googleProviderPreset
    case responseAPIPreset
    case endpointConfirmationPreset

    /// A preset provider whose protocol can actually run in the iOS chat chain
    /// today, so its API Key is worth editing and it can be set as current.
    /// OpenAI-compatible (non-Response-API, non-MiMo-placeholder) and Claude
    /// qualify. Gemini / Response-API / MiMo-placeholder do not.
    static func isEditablePreset(_ preset: ProviderSetting) -> Bool {
        if let openAI = preset as? ProviderSetting.OpenAI {
            if openAI.useResponseApi { return false }
            if openAI.brand === OpenAIBrand.mimo { return false }
            return true
        }
        if preset is ProviderSetting.Claude { return true }
        return false
    }
}

struct ProvidersView: View {
    @Bindable var settingsStore: SettingsStore
    let providerRegistry: ProviderRegistryStore
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    searchPill
                    savedProvidersSection
                }
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(
                systemImage: "chevron.left",
                accessibilityLabel: "返回设置",
                size: 44,
                symbolSize: 20
            ) {
                dismiss()
            }

            Spacer()

            Text("服务商")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassIconButton(
                systemImage: "plus",
                accessibilityLabel: "添加服务商",
                size: 44,
                symbolSize: 20,
                tint: AmberTheme.accent,
                prominent: true
            ) {
                router.navigate(to: .providerAdd)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var searchPill: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(AmberTheme.muted.opacity(0.72))

            Text("搜索服务商")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AmberTheme.muted)

            Spacer()
        }
        .frame(height: 42)
        .padding(.horizontal, 13)
        .background(
            AmberTheme.surface.opacity(0.76),
            in: RoundedRectangle(cornerRadius: AmberTheme.radiusPill, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusPill, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
        .padding(.horizontal, 16)
        .padding(.top, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("搜索服务商")
    }

    private var sharedProviders: [ProviderSetting] {
        _ = sharedSettings.revision
        return sharedSettings.snapshot.providers.filter(ChatProviderConfiguration.supportsChatStreaming)
    }

    private var currentProviderId: String? {
        _ = sharedSettings.revision
        let snapshot = sharedSettings.snapshot
        guard let currentModel = snapshot.getCurrentChatModel(),
              let provider = currentModel.findProvider(providers: snapshot.providers, checkOverwrite: true) else {
            return nil
        }
        return provider.id.description()
    }

    private var savedProvidersSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "服务商模板")

            AmberFormGroup {
                ForEach(Array(sharedProviders.enumerated()), id: \.offset) { index, provider in
                    let hasKey = ProviderRowModel.hasUsableKey(provider)
                    RegistryProviderRow(
                        model: ProviderRowModel(preset: provider),
                        isSelected: currentProviderId == provider.id.description(),
                        hasStoredKey: hasKey
                    ) {
                        router.navigate(to: .providerDetail(id: provider.id.description()))
                    }

                    if index < sharedProviders.count - 1 {
                        ProviderDivider()
                    }
                }
            }

        }
        .id(sharedSettings.revision)
    }
}

private struct RegistryProviderRow: View {
    let model: ProviderRowModel
    let isSelected: Bool
    let hasStoredKey: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 12) {
                ProviderAvatar(initial: model.initial, isSelected: isSelected, hasStoredKey: hasStoredKey)

                VStack(alignment: .leading, spacing: 3) {
                    Text(model.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)

                    Text(model.endpoint)
                        .font(.system(size: 11.5, weight: .regular, design: .monospaced))
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                trailing
            }
            .frame(minHeight: 62)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .background {
                if isSelected {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(AmberTheme.accent.opacity(0.06))
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(model.name)\(isSelected ? "，当前服务商" : "")")
    }

    @ViewBuilder private var trailing: some View {
        if isSelected {
            ProviderStatusBadge(title: "当前", systemImage: "checkmark.circle.fill", tint: AmberTheme.accentGreen)
        } else {
            ProviderStatusBadge(
                title: hasStoredKey ? "已配置" : "未填写",
                systemImage: hasStoredKey ? "checkmark" : "exclamationmark",
                tint: hasStoredKey ? AmberTheme.accentGreen : AmberTheme.muted2
            )
        }
    }
}

private struct ProviderAvatar: View {
    let initial: String
    let isSelected: Bool
    let hasStoredKey: Bool

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Text(initial)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(isSelected ? AmberTheme.accent : AmberTheme.foreground2)
                .frame(width: 38, height: 38)
                .background(
                    isSelected ? AmberTheme.accentTint : AmberTheme.surface2.opacity(0.86),
                    in: Circle()
                )
                .overlay {
                    Circle()
                        .stroke(isSelected ? AmberTheme.accent.opacity(0.14) : AmberTheme.borderSoft, lineWidth: 0.5)
                }

            if hasStoredKey || isSelected {
                Circle()
                    .fill(isSelected ? AmberTheme.accentGreen : AmberTheme.muted2)
                    .frame(width: 9, height: 9)
                    .overlay {
                        Circle()
                            .stroke(AmberTheme.surface, lineWidth: 1.5)
                    }
            }
        }
        .frame(width: 40, height: 40)
    }
}

private struct ProviderStatusBadge: View {
    let title: String
    let systemImage: String
    let tint: Color

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: systemImage)
                .font(.system(size: 10, weight: .bold))
            Text(title)
                .font(.caption2.weight(.semibold))
        }
        .foregroundStyle(tint)
        .lineLimit(1)
        .padding(.horizontal, 8)
        .frame(height: 24)
        .background(tint.opacity(0.10), in: Capsule())
    }
}

private struct ProviderRowModel: Identifiable {
    let id: String
    let initial: String
    let name: String
    let endpoint: String

    // Build a no-key preset row from a real Android/KMP ProviderSetting.
    init(preset: ProviderSetting) {
        let providerName = preset.name
        let providerEndpoint = Self.endpoint(for: preset)
        let providerID = preset.id.description()
        self.id = providerID
        self.initial = Self.initial(for: providerName)
        self.name = providerName
        self.endpoint = providerEndpoint
    }

    fileprivate static func endpoint(for preset: ProviderSetting) -> String {
        if let openAI = preset as? ProviderSetting.OpenAI { return openAI.baseUrl }
        if let google = preset as? ProviderSetting.Google { return google.baseUrl }
        if let claude = preset as? ProviderSetting.Claude { return claude.baseUrl }
        return ""
    }

    private static func initial(for name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        return String(trimmed.prefix(1)).uppercased()
    }

    fileprivate static func hasUsableKey(_ provider: ProviderSetting) -> Bool {
        if let openAI = provider as? ProviderSetting.OpenAI {
            return !openAI.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        if let claude = provider as? ProviderSetting.Claude {
            return !claude.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return false
    }
}

struct ProviderAddView: View {
    let settingsStore: SettingsStore
    let providerRegistry: ProviderRegistryStore
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    @State private var name = "New Provider"
    @State private var protocolOption: ProviderProtocolOption = .openAI
    @State private var apiBase = "https://api.openai.com/v1"
    @State private var apiKey = ""
    @State private var modelName = ""
    @State private var modelId = ""
    @State private var alert: ProviderAddAlert?

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        connectionSection
                        credentialSection
                        modelSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
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

    private var header: some View {
        HStack {
            AmberGlassCircleButton(
                systemImage: "chevron.left",
                accessibilityLabel: "返回服务商",
                size: 44,
                symbolSize: 20
            ) {
                dismiss()
            }

            Spacer()

            Text("添加服务商")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button {
                save()
            } label: {
                Text("保存")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(height: 36)
                    .padding(.horizontal, 14)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .accessibilityLabel("保存服务商")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var connectionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "连接")
            AmberFormGroup {
                ProviderDraftTextFieldRow(
                    title: "名称",
                    text: $name,
                    placeholder: "例如 DeepSeek / Claude"
                )
                ProviderDivider()
                Menu {
                    ForEach(ProviderProtocolOption.addableCases, id: \.self) { option in
                        Button {
                            switchProtocol(to: option)
                        } label: {
                            if option == protocolOption {
                                Label(option.title, systemImage: "checkmark")
                            } else {
                                Text(option.title)
                            }
                        }
                    }
                } label: {
                    ProviderDraftValueRow(
                        title: "接口协议",
                        value: protocolOption.title,
                        showsChevron: true
                    )
                }
                ProviderDivider()
                ProviderDraftTextFieldRow(
                    title: "API 地址",
                    text: $apiBase,
                    placeholder: protocolOption.defaultBaseURLPlaceholder,
                    monospace: true
                )
            }
        }
    }

    private var modelSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "模型")
            AmberFormGroup {
                ProviderDraftTextFieldRow(
                    title: "模型 ID",
                    text: $modelId,
                    placeholder: protocolOption == .anthropic ? "claude-sonnet-4-5" : "deepseek-chat",
                    monospace: true
                )
                ProviderDivider()
                ProviderDraftTextFieldRow(
                    title: "显示名称",
                    text: $modelName,
                    placeholder: "例如 Claude Sonnet 4.5"
                )
            }
        }
    }

    private var credentialSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "凭据")
            AmberFormGroup {
                ProviderDraftTextFieldRow(
                    title: "API Key",
                    text: $apiKey,
                    placeholder: protocolOption == .anthropic ? "sk-ant-..." : "sk-...",
                    isSecure: true,
                    monospace: true
                )
            }
        }
    }

    private func switchProtocol(to option: ProviderProtocolOption) {
        // When the user switches protocol, reset the base URL to the protocol's
        // default IF the current value is still the previous protocol's default
        // (i.e. the user hasn't customized it). This keeps custom URLs intact.
        if apiBase == protocolOption.defaultBaseURL {
            apiBase = option.defaultBaseURL
        }
        protocolOption = option
    }

    private func save() {
        let normalizedBase = Self.normalizedBaseURL(apiBase)
        guard Self.isValidHTTPBaseURL(normalizedBase) else {
            alert = .invalidBaseURL
            return
        }

        let trimmedModelId = modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedModelName = modelName.trimmingCharacters(in: .whitespacesAndNewlines)

        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalName = trimmedName.isEmpty ? protocolOption.defaultName : trimmedName
        let trimmedKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)

        // Build the provider via KMP (OpenAI-compatible or Claude), add it to the
        // real Settings.providers snapshot, and persist.
        let provider: ProviderSetting
        switch protocolOption {
        case .openAI:
            if trimmedModelId.isEmpty {
                provider = IosSettingsMutations.shared.buildBlankOpenAIProvider(
                    name: finalName,
                    apiKey: trimmedKey,
                    baseUrl: normalizedBase
                )
            } else {
                provider = IosSettingsMutations.shared.buildOpenAIProvider(
                    name: finalName,
                    apiKey: trimmedKey,
                    baseUrl: normalizedBase,
                    modelName: trimmedModelName.isEmpty ? trimmedModelId : trimmedModelName,
                    modelId: trimmedModelId
                )
            }
        case .anthropic:
            if trimmedModelId.isEmpty {
                provider = IosSettingsMutations.shared.buildBlankClaudeProvider(
                    name: finalName,
                    apiKey: trimmedKey,
                    baseUrl: normalizedBase
                )
            } else {
                provider = IosSettingsMutations.shared.buildClaudeProvider(
                    name: finalName,
                    apiKey: trimmedKey,
                    baseUrl: normalizedBase,
                    modelName: trimmedModelName.isEmpty ? trimmedModelId : trimmedModelName,
                    modelId: trimmedModelId
                )
            }
        default:
            alert = .unsupportedProtocol(protocolOption.title)
            return
        }

        let added = sharedSettings.addProvider(provider)

        // When a key was provided, set this provider's first chat model as the
        // current chat model (mirrors Android: choosing a model resolves to its
        // provider). This makes the new provider immediately usable for chat.
        if !trimmedKey.isEmpty,
           let chatModel = added.models.first(where: { $0.type == ModelType.chat }) {
            sharedSettings.setCurrentChatModelId(chatModel.id.description())
            sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
        }
        let providerId = added.id.description()
        dismiss()
        DispatchQueue.main.async {
            router.navigate(to: .providerDetail(id: providerId))
        }
    }

    private static func normalizedBaseURL(_ value: String) -> String {
        var baseURL = value.trimmingCharacters(in: .whitespacesAndNewlines)
        while baseURL.hasSuffix("/") {
            baseURL.removeLast()
        }
        return baseURL
    }

    private static func isValidHTTPBaseURL(_ value: String) -> Bool {
        guard let components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host,
              !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }
        return true
    }
}

private enum ProviderAddAlert: Identifiable {
    case unsupportedProtocol(String)
    case unsupportedResponseAPI
    case unsupportedPath
    case invalidBaseURL
    case activationFailed
    case modelRequired

    var id: String {
        switch self {
        case .unsupportedProtocol(let name): "unsupported-\(name)"
        case .unsupportedResponseAPI: "response-api"
        case .unsupportedPath: "path"
        case .invalidBaseURL: "base-url"
        case .activationFailed: "activation"
        case .modelRequired: "model-required"
        }
    }

    var title: String {
        switch self {
        case .unsupportedProtocol: "暂不支持这个协议"
        case .unsupportedResponseAPI: "暂不支持 Response API"
        case .unsupportedPath: "暂不支持自定义路径"
        case .invalidBaseURL: "API 地址无效"
        case .activationFailed: "服务商未激活"
        case .modelRequired: "需要填写模型"
        }
    }

    var message: String {
        switch self {
        case .unsupportedProtocol(let name):
            "\(name) 当前不能直接用于聊天。请先添加 OpenAI 兼容服务商。"
        case .unsupportedResponseAPI:
            "当前版本只支持 Chat Completions 路径。"
        case .unsupportedPath:
            "当前版本使用默认 /chat/completions 路径。"
        case .invalidBaseURL:
            "请填写包含 http 或 https scheme 且带 host 的 base URL，例如 https://api.openai.com/v1。"
        case .activationFailed:
            "API Key 没有成功保存到本机钥匙串，当前聊天服务商未切换。请重新保存一次。"
        case .modelRequired:
            "请至少填写一个模型 ID，例如 claude-sonnet-4-5 或 deepseek-chat。"
        }
    }
}

enum ProviderProtocolOption: String, CaseIterable, Identifiable {
    case openAI
    case codexOAuth
    case google
    case anthropic
    case custom

    var id: String { rawValue }

    var title: String {
        switch self {
        case .openAI: "OpenAI Compatible"
        case .codexOAuth: "Codex OAuth"
        case .google: "Gemini"
        case .anthropic: "Anthropic"
        case .custom: "自定义"
        }
    }

    var detailTitle: String {
        switch self {
        case .openAI: "OpenAI 兼容"
        case .codexOAuth: "Codex OAuth"
        case .google: "Gemini"
        case .anthropic: "Anthropic 兼容"
        case .custom: "自定义"
        }
    }

    var defaultPath: String {
        switch self {
        case .openAI: "/chat/completions"
        case .codexOAuth: "/responses"
        case .google: "/models/{model}:generateContent"
        case .anthropic: "/messages"
        case .custom: "/chat/completions"
        }
    }

    /// Protocols the "add provider" flow can actually create and run in the iOS
    /// chat chain today. Gemini/Custom stay out of the picker until they get a
    /// KMP executor bridge.
    static var addableCases: [ProviderProtocolOption] {
        [.openAI, .anthropic]
    }

    static var switchableCases: [ProviderProtocolOption] {
        [.openAI, .anthropic]
    }

    static func option(for provider: ProviderSetting?) -> ProviderProtocolOption? {
        guard let provider else { return nil }
        if let openAI = provider as? ProviderSetting.OpenAI {
            if IOSCodexProviderResolver.isCodexProvider(openAI) {
                return .codexOAuth
            }
            return openAI.useResponseApi ? nil : .openAI
        }
        if provider is ProviderSetting.Claude {
            return .anthropic
        }
        if provider is ProviderSetting.Google {
            return .google
        }
        return nil
    }

    /// The default base URL seeded when the user picks this protocol in the add
    /// flow (and the value `switchProtocol` resets to).
    var defaultBaseURL: String {
        switch self {
        case .openAI: "https://api.openai.com/v1"
        case .codexOAuth: IOSCodexOAuthConstants.codexBackendBaseUrl
        case .anthropic: "https://api.anthropic.com/v1"
        case .google: "https://generativelanguage.googleapis.com/v1beta"
        case .custom: "https://api.example.com/v1"
        }
    }

    var defaultBaseURLPlaceholder: String {
        defaultBaseURL
    }

    var defaultName: String {
        switch self {
        case .openAI: "OpenAI Compatible"
        case .codexOAuth: "Codex OAuth"
        case .anthropic: "Claude"
        case .google: "Gemini"
        case .custom: "Custom"
        }
    }
}

private struct ProviderDraftTextFieldRow: View {
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

private struct ProviderDraftPickerRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)

                Text(value)
                    .font(.body)
                    .foregroundStyle(AmberTheme.accent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 15)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

private struct ProviderDraftValueRow: View {
    let title: String
    let value: String
    var monospace = false
    var showsChevron = false

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)

                Text(value)
                    .font(monospace ? .system(size: 14, weight: .regular, design: .monospaced) : .body)
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if showsChevron {
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 58)
        .padding(.horizontal, 15)
        .padding(.vertical, 8)
    }
}

private struct ProviderDraftToggleRow: View {
    let title: String
    let subtitle: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                ProviderDraftSwitch(isOn: isOn)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityValue(isOn ? "开启" : "关闭")
    }
}

private struct ProviderDraftSwitch: View {
    let isOn: Bool

    var body: some View {
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
            .animation(.snappy(duration: 0.18), value: isOn)
    }
}

private struct ProviderDraftNote: View {
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

private struct ProviderDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 58)
    }
}

#Preview {
    let settings = SettingsStore()
    return NavigationStack {
        ProvidersView(settingsStore: settings, providerRegistry: ProviderRegistryStore(settingsStore: settings), sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
