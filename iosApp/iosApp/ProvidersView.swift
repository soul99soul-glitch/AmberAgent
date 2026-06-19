import SwiftUI
import Shared

enum ProviderRouteKind: String, Hashable {
    case current
    case openAICompatiblePreset
    case googleProviderPreset
    case responseAPIPreset
    case endpointConfirmationPreset
}

struct ProvidersView: View {
    @Bindable var settingsStore: SettingsStore
    let providerRegistry: ProviderRegistryStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    searchPill
                    providerSection(title: "当前聊天配置", rows: [currentProvider])
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

            Button {
                router.navigate(to: .providerAdd)
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: 22)
            .accessibilityLabel("添加服务商")
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

            Text("预置模板；可添加 OpenAI 兼容服务商")
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)

            Spacer()
        }
        .frame(height: 40)
        .padding(.horizontal, 13)
        .background(
            AmberTheme.surface2.opacity(0.82),
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

    private func providerSection(title: String, rows: [ProviderRowModel]) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: title)

            AmberFormGroup {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, provider in
                    ProviderRow(provider: provider) {
                        router.navigate(to: .providerDetail(name: provider.name, endpoint: provider.endpoint, kind: provider.kind))
                    }

                    if index < rows.count - 1 {
                        ProviderDivider()
                    }
                }
            }
        }
    }

    private var currentProvider: ProviderRowModel {
        .init(
            initial: "O",
            name: "OpenAI-compatible",
            endpoint: settingsStore.baseUrl,
            status: settingsStore.apiKey.isEmpty ? "API Key 未填写" : "API Key 已填写",
            statusColor: settingsStore.apiKey.isEmpty ? AmberTheme.muted2 : AmberTheme.accentGreen,
            kind: .current
        )
    }

    // Real KMP default provider templates plus per-provider Keychain status. Each
    // row reuses ProviderSetting-derived rendering (name/endpoint/initial). A row
    // can become active only when the current scalar chat chain can represent it
    // and a Keychain key already exists for that provider id.
    private var savedProvidersSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Provider 模板与密钥状态")

            AmberFormGroup {
                ForEach(Array(providerRegistry.providers.enumerated()), id: \.offset) { index, provider in
                    RegistryProviderRow(
                        model: ProviderRowModel(preset: provider),
                        isSelected: providerRegistry.isSelected(provider),
                        hasStoredKey: providerRegistry.hasStoredKey(provider),
                        canActivate: providerRegistry.canActivate(provider),
                        canSelect: providerRegistry.canSelect(provider)
                    ) {
                        providerRegistry.select(provider)
                    }

                    if index < providerRegistry.providers.count - 1 {
                        ProviderDivider()
                    }
                }
            }

            ProviderDraftNote("这些行来自 Android/KMP DEFAULT_PROVIDERS，并叠加本机 Keychain 是否已有该 provider id 的密钥。只有当前 iOS 聊天链路能表达、且已有 Key 的 OpenAI-compatible 模板可以设为当前；列表不含明文 API Key。Gemini、xAI、MiMo 等类型仍等待完整 ProviderSetting bridge。在服务商详情页可编辑某个 activatable 模板的 API Key，只写入该 provider 的 Keychain；保存后即可「设为当前」。")
        }
        // hasStoredKey/canSelect read the Keychain via a static helper (not an
        // observable property), so tying this section's identity to keyRevision
        // forces a rebuild whenever a per-provider key is written or cleared —
        // the row status (Key 已/未填写, 设为当前/需要 Key) then re-reads the
        // Keychain honestly on back-navigation.
        .id(providerRegistry.keyRevision)
    }
}

private struct RegistryProviderRow: View {
    let model: ProviderRowModel
    let isSelected: Bool
    let hasStoredKey: Bool
    let canActivate: Bool
    let canSelect: Bool
    let onSelect: () -> Void

    var body: some View {
        Button {
            if canSelect { onSelect() }
        } label: {
            HStack(spacing: 12) {
                Text(model.initial)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(width: 32, height: 32)
                    .background(AmberTheme.surface2, in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(model.name)
                        .font(.body)
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
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!canSelect)
        .opacity(canActivate ? 1 : 0.55)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(model.name)\(isSelected ? "，当前服务商" : "")")
    }

    @ViewBuilder private var trailing: some View {
        if !canActivate {
            Text("暂不支持")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        } else if isSelected {
            HStack(spacing: 4) {
                Image(systemName: "checkmark.circle.fill")
                Text("当前")
            }
            .font(.caption2.weight(.semibold))
            .foregroundStyle(AmberTheme.accentGreen)
        } else {
            VStack(alignment: .trailing, spacing: 2) {
                Text(hasStoredKey ? "Key 已填写" : "Key 未填写")
                    .font(.caption2)
                    .foregroundStyle(hasStoredKey ? AmberTheme.accentGreen : AmberTheme.muted2)
                Text(hasStoredKey ? "设为当前" : "需要 Key")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(hasStoredKey ? AmberTheme.accent : AmberTheme.muted2)
            }
        }
    }
}

private struct ProviderRowModel: Identifiable {
    let id: String
    let initial: String
    let name: String
    let endpoint: String
    var status: String
    var statusColor: Color
    var kind: ProviderRouteKind
    var isDimmed = false

    init(
        initial: String,
        name: String,
        endpoint: String,
        status: String,
        statusColor: Color,
        kind: ProviderRouteKind = .openAICompatiblePreset,
        isDimmed: Bool = false
    ) {
        self.id = "\(name)-\(endpoint)"
        self.initial = initial
        self.name = name
        self.endpoint = endpoint
        self.status = status
        self.statusColor = statusColor
        self.kind = kind
        self.isDimmed = isDimmed
    }

    // Build a no-key preset row from a real Android/KMP ProviderSetting.
    init(preset: ProviderSetting) {
        let providerName = preset.name
        let providerEndpoint = Self.endpoint(for: preset)
        let routeKind = Self.routeKind(for: preset)
        let descriptor = Self.statusDescriptor(for: routeKind)
        self.id = "preset-\(providerName)-\(providerEndpoint)"
        self.initial = Self.initial(for: providerName)
        self.name = providerName
        self.endpoint = providerEndpoint
        self.status = descriptor.text
        self.statusColor = descriptor.color
        self.kind = routeKind
        self.isDimmed = false
    }

    private static func endpoint(for preset: ProviderSetting) -> String {
        if let openAI = preset as? ProviderSetting.OpenAI { return openAI.baseUrl }
        if let google = preset as? ProviderSetting.Google { return google.baseUrl }
        if let claude = preset as? ProviderSetting.Claude { return claude.baseUrl }
        return ""
    }

    private static func routeKind(for preset: ProviderSetting) -> ProviderRouteKind {
        if let openAI = preset as? ProviderSetting.OpenAI {
            if openAI.useResponseApi { return .responseAPIPreset }
            // MiMo's bundled baseUrl is a source-marked placeholder ("user can override").
            // Identity compare the enum singleton to avoid relying on NSObject equality.
            if openAI.brand === OpenAIBrand.mimo { return .endpointConfirmationPreset }
            return .openAICompatiblePreset
        }
        if preset is ProviderSetting.Google {
            return .googleProviderPreset
        }
        // Only OpenAI and Google appear in DEFAULT_PROVIDERS today. Any other
        // ProviderSetting type (e.g. Claude) would currently inherit the Google
        // "待桥接" label/path, which is wrong — it must get its own route kind and
        // ProviderDetailView label before such a default is added. Until then, fall
        // back to the bridge-required state so it can never be presented as an
        // applyable OpenAI-compatible template.
        return .googleProviderPreset
    }

    private static func initial(for name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        return String(trimmed.prefix(1)).uppercased()
    }

    private static func statusDescriptor(for kind: ProviderRouteKind) -> (text: String, color: Color) {
        switch kind {
        case .openAICompatiblePreset:
            return ("预置 · 可套用", AmberTheme.accent)
        case .endpointConfirmationPreset:
            return ("预置 · Base 待确认", AmberTheme.muted)
        case .responseAPIPreset:
            return ("预置 · 待 Response API", AmberTheme.muted)
        case .googleProviderPreset:
            return ("预置 · 待桥接", AmberTheme.muted)
        case .current:
            return ("预置", AmberTheme.muted)
        }
    }
}

private struct ProviderRow: View {
    let provider: ProviderRowModel
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(provider.initial)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(width: 32, height: 32)
                    .background(AmberTheme.surface2, in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(provider.name)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)

                    Text(provider.endpoint)
                        .font(.system(size: 11.5, weight: .regular, design: .monospaced))
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Text(provider.status)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(provider.statusColor)
                    .lineLimit(1)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .opacity(provider.isDimmed ? 0.6 : 1)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(provider.name)，\(provider.endpoint)")
    }
}

private struct ProviderStatusRow: View {
    let title: String
    let subtitle: String
    let badge: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "server.rack")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 32, height: 32)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)

                Text(badge)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(minHeight: 78)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

struct ProviderAddView: View {
    let providerRegistry: ProviderRegistryStore

    @Environment(\.dismiss) private var dismiss

    @State private var name = "New Provider"
    @State private var protocolOption: ProviderProtocolOption = .openAI
    @State private var apiBase = "https://api.example.com/v1"
    @State private var apiKey = ""
    @State private var path = "/chat/completions"
    @State private var responseAPI = false
    @State private var balanceRefresh = false
    @State private var alert: ProviderAddAlert?

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        introSection
                        connectionSection
                        credentialSection
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

    private var introSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "server.rack")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(AmberTheme.accent)
                        .frame(width: 34, height: 34)
                        .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                    VStack(alignment: .leading, spacing: 4) {
                        Text("OpenAI-compatible")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)

                        Text("保存后会加入服务商列表；API Key 写入本机 Keychain。填写 Key 时会立即设为当前聊天服务商，不发起网络测试。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 13)
            }
            .padding(.top, 4)
        }
    }

    private var connectionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "连接")
            AmberFormGroup {
                ProviderDraftTextFieldRow(
                    title: "名称",
                    text: $name,
                    placeholder: "例如 DeepSeek"
                )
                ProviderDivider()
                ProviderDraftValueRow(title: "接口协议", value: "OpenAI-compatible")
                ProviderDivider()
                ProviderDraftTextFieldRow(
                    title: "API 地址",
                    text: $apiBase,
                    placeholder: "https://api.example.com/v1",
                    monospace: true
                )
                ProviderDivider()
                ProviderDraftValueRow(
                    title: "路径",
                    value: "/chat/completions",
                    monospace: true
                )
            }

            ProviderDraftNote("当前 iOS 聊天链路只消费 OpenAI-compatible base URL，并固定使用 /chat/completions；其它协议、自定义路径和 Response API 不再作为可选项展示。")
        }
    }

    private var credentialSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "凭据")
            AmberFormGroup {
                ProviderDraftTextFieldRow(
                    title: "API Key",
                    text: $apiKey,
                    placeholder: "sk-...",
                    isSecure: true,
                    monospace: true
                )
            }

            ProviderDraftNote("API Key 留空时只保存服务商模板，不会设为当前；填写后会保存到该 provider id 对应的 Keychain 项。")
        }
    }

    private func save() {
        guard protocolOption == .openAI else {
            alert = .unsupportedProtocol(protocolOption.title)
            return
        }
        guard !responseAPI else {
            alert = .unsupportedResponseAPI
            return
        }

        let normalizedPath = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard normalizedPath == "/chat/completions" else {
            alert = .unsupportedPath
            return
        }

        let normalizedBase = Self.normalizedBaseURL(apiBase)
        guard Self.isValidHTTPBaseURL(normalizedBase) else {
            alert = .invalidBaseURL
            return
        }

        let activated = providerRegistry.addOpenAICompatibleProvider(
            name: name,
            baseUrl: normalizedBase,
            apiKey: apiKey,
            activate: true
        )
        guard activated || apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            alert = .activationFailed
            return
        }
        dismiss()
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

    var id: String {
        switch self {
        case .unsupportedProtocol(let name): "unsupported-\(name)"
        case .unsupportedResponseAPI: "response-api"
        case .unsupportedPath: "path"
        case .invalidBaseURL: "base-url"
        case .activationFailed: "activation"
        }
    }

    var title: String {
        switch self {
        case .unsupportedProtocol: "协议暂未桥接"
        case .unsupportedResponseAPI: "Response API 暂未接入"
        case .unsupportedPath: "路径暂未接入"
        case .invalidBaseURL: "API 地址无效"
        case .activationFailed: "服务商未激活"
        }
    }

    var message: String {
        switch self {
        case .unsupportedProtocol(let name):
            "\(name) 还没有进入 iOS ProviderSetting 执行桥。当前只能保存 OpenAI-compatible 服务商。"
        case .unsupportedResponseAPI:
            "当前 ChatViewModel 使用 Chat Completions 链路，不能把 Response API 配置伪装成可用。"
        case .unsupportedPath:
            "当前 iOS 请求构造固定使用 /chat/completions。自定义路径需要先接入 Provider bridge。"
        case .invalidBaseURL:
            "请填写包含 http 或 https scheme 且带 host 的 base URL，例如 https://api.openai.com/v1。"
        case .activationFailed:
            "API Key 没有成功写入本机 Keychain，当前聊天服务商未切换。请重新保存一次。"
        }
    }
}

private enum ProviderProtocolOption: String, CaseIterable, Identifiable {
    case openAI
    case google
    case anthropic
    case custom

    var id: String { rawValue }

    var title: String {
        switch self {
        case .openAI: "OpenAI Compatible"
        case .google: "Gemini"
        case .anthropic: "Anthropic"
        case .custom: "自定义"
        }
    }

    var defaultPath: String {
        switch self {
        case .openAI: "/chat/completions"
        case .google: "/models/{model}:generateContent"
        case .anthropic: "/messages"
        case .custom: "/chat/completions"
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

    var body: some View {
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
        ProvidersView(settingsStore: settings, providerRegistry: ProviderRegistryStore(settingsStore: settings))
            .environment(RouterPath())
    }
}
