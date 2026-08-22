import Foundation
import Shared
import Darwin

enum IOSProviderEndpointPolicy {
    static func isValidBaseURL(_ value: String) -> Bool {
        guard let components = URLComponents(
            string: value.trimmingCharacters(in: .whitespacesAndNewlines)
        ), let scheme = components.scheme?.lowercased(),
           let host = components.host,
           !host.isEmpty else {
            return false
        }
        if scheme == "https" {
            return true
        }
        return scheme == "http" && isIPAddress(host)
    }

    private static func isIPAddress(_ host: String) -> Bool {
        let candidate = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        var ipv4 = in_addr()
        if candidate.withCString({ inet_pton(AF_INET, $0, &ipv4) }) == 1 {
            return true
        }
        var ipv6 = in6_addr()
        return candidate.withCString({ inet_pton(AF_INET6, $0, &ipv6) }) == 1
    }
}

enum ChatConfigurationIssue: Equatable {
    case missingAPIKey
    case invalidBaseURL
    case missingModel
    case missingProvider
    case providerDisabled
    case unsupportedProvider
    case codexNotSignedIn
    case grokNotSignedIn
    case geminiNotSignedIn

    var title: String {
        switch self {
        case .missingAPIKey:
            "还不能聊天"
        case .invalidBaseURL:
            "API 地址无效"
        case .missingModel:
            "还没有选择模型"
        case .missingProvider:
            "还没有配置服务商"
        case .providerDisabled:
            "服务商已停用"
        case .unsupportedProvider:
            "当前服务商暂不支持聊天"
        case .codexNotSignedIn:
            "还没有登录 Codex"
        case .grokNotSignedIn:
            "还没有登录 Grok"
        case .geminiNotSignedIn:
            "还没有登录 Antigravity"
        }
    }

    var message: String {
        switch self {
        case .missingAPIKey:
            "请先添加服务商 API Key，再发送第一条消息。"
        case .invalidBaseURL:
            "当前地址不是有效的 HTTPS 或 HTTP IP 地址，请修正后再试。"
        case .missingModel:
            "请选择当前服务商可用的聊天模型，或填写服务商文档中的 Model ID。"
        case .missingProvider:
            "请先在设置里添加一个服务商（并填写 API Key 与模型），再发送消息。"
        case .providerDisabled:
            "请先在服务商设置里启用当前服务商，再发送消息。"
        case .unsupportedProvider:
            "当前服务商类型的 iOS 聊天执行器尚未移植，请先切换到 OpenAI 兼容或 Anthropic 服务商。"
        case .codexNotSignedIn:
            "请先在服务商设置里用 ChatGPT 账号登录 Codex，再发送消息。"
        case .grokNotSignedIn:
            "请先在 xAI 服务商设置里用 Grok 账号登录，再发送消息。"
        case .geminiNotSignedIn:
            "请先在 Gemini 服务商设置里用 Google 账号登录 Antigravity，再发送消息。"
        }
    }
}

enum ChatProviderConfiguration {
    static func provider(for model: Model, providers: [ProviderSetting]) -> ProviderSetting? {
        model.findProvider(providers: providers, checkOverwrite: true)
    }

    static func configuredChatModels(in providers: [ProviderSetting]) -> [(provider: ProviderSetting, models: [Model])] {
        providers.compactMap { provider in
            guard provider.enabled, supportsChatStreaming(provider) else { return nil }
            let models = provider.models.filter { model in
                model.type == ModelType.chat && issue(for: model, provider: provider) == nil
            }
            guard !models.isEmpty else { return nil }
            return (provider, models)
        }
    }

    static func issue(for model: Model, provider: ProviderSetting?) -> ChatConfigurationIssue? {
        guard let provider else { return .missingProvider }
        guard provider.enabled else { return .providerDisabled }
        guard supportsChatStreaming(provider) else { return .unsupportedProvider }
        // Codex OAuth providers carry no apiKey — the bearer is an OAuth token
        // resolved at request time. Gate on sign-in state instead of apiKey.
        if IOSCodexProviderResolver.isCodexProvider(provider) {
            if !IOSCodexProviderResolver.isSignedIn(provider) { return .codexNotSignedIn }
            if model.modelId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .missingModel }
            return nil
        }
        if IOSGrokWebProviderResolver.isGrokWebProvider(provider) {
            if !IOSGrokWebProviderResolver.isSignedIn(provider) { return .grokNotSignedIn }
            if model.modelId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .missingModel }
            return nil
        }
        if let google = provider as? ProviderSetting.Google,
           IOSGeminiProviderResolver.isAntigravityOAuth(google) {
            if !IOSGeminiProviderResolver.isSignedIn(provider) { return .geminiNotSignedIn }
            if model.modelId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .missingModel }
            return nil
        }
        return issue(
            baseUrl: baseURL(of: provider),
            apiKey: apiKey(of: provider),
            modelId: model.modelId
        )
    }

    static func issue(baseUrl: String, apiKey: String, modelId: String) -> ChatConfigurationIssue? {
        if apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .missingAPIKey
        }
        if !isValidHTTPBaseURL(baseUrl) {
            return .invalidBaseURL
        }
        if modelId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .missingModel
        }
        return nil
    }

    static func supportsChatStreaming(_ provider: ProviderSetting) -> Bool {
        if let openAI = provider as? ProviderSetting.OpenAI {
            // Codex OAuth speaks the Responses API via an OAuth bearer; it's
            // supported through the codex request path (token injected at
            // request time) even though it implies useResponseApi.
            if IOSCodexProviderResolver.isCodexProvider(openAI) { return true }
            // KMP OpenAI provider owns both chat-completions and Responses API
            // routing via the provider setting. MiMo's seeded endpoint is still
            // a placeholder, so keep it out until it has a real runnable config.
            if openAI.brand === OpenAIBrand.mimo { return false }
            return true
        }
        if let google = provider as? ProviderSetting.Google {
            // Native Swift Gemini transport: API Key (non-Vertex) or Antigravity
            // OAuth. Vertex/service-account and the Android-only Code Assist
            // OAuth mode are not implemented on iOS.
            return IOSGeminiProviderResolver.supportsChat(google)
        }
        return provider is ProviderSetting.Claude
    }

    private static func isValidHTTPBaseURL(_ value: String) -> Bool {
        IOSProviderEndpointPolicy.isValidBaseURL(value)
    }

    static func apiKey(of provider: ProviderSetting) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI { return openAI.apiKey }
        if let claude = provider as? ProviderSetting.Claude { return claude.apiKey }
        if let google = provider as? ProviderSetting.Google { return google.apiKey }
        return ""
    }

    /// List-row / chat-ready credential: API key, or a signed-in OAuth/Web session.
    static func hasUsableCredential(_ provider: ProviderSetting) -> Bool {
        if IOSCodexProviderResolver.isCodexProvider(provider) {
            return IOSCodexProviderResolver.isSignedIn(provider)
        }
        if IOSGrokWebProviderResolver.isGrokWebProvider(provider) {
            return IOSGrokWebProviderResolver.isSignedIn(provider)
        }
        if let google = provider as? ProviderSetting.Google,
           IOSGeminiProviderResolver.isAntigravityOAuth(google) {
            return IOSGeminiProviderResolver.isSignedIn(provider)
        }
        return !apiKey(of: provider).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func credentialStatusTitle(_ provider: ProviderSetting) -> String {
        if IOSCodexProviderResolver.isCodexProvider(provider),
           IOSCodexProviderResolver.isSignedIn(provider) {
            return "已登录"
        }
        if IOSGrokWebProviderResolver.isGrokWebProvider(provider),
           IOSGrokWebProviderResolver.isSignedIn(provider) {
            return "已登录"
        }
        if let google = provider as? ProviderSetting.Google,
           IOSGeminiProviderResolver.isAntigravityOAuth(google),
           IOSGeminiProviderResolver.isSignedIn(provider) {
            return "已登录"
        }
        return hasUsableCredential(provider) ? "已配置" : "未填写"
    }

    static func requestHeaders(
        for provider: ProviderSetting,
        assistant: [CustomHeader] = [],
        model: [CustomHeader] = []
    ) -> [CustomHeader] {
        let providerId = provider.id.description() as String
        return IOSProviderRequestHeaderStore.headers(for: providerId) + assistant + model
    }

    static func baseURL(of provider: ProviderSetting) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI { return openAI.baseUrl }
        if let claude = provider as? ProviderSetting.Claude { return claude.baseUrl }
        if let google = provider as? ProviderSetting.Google { return google.baseUrl }
        return ""
    }
}

extension IOSSharedSettingsStore {
    @discardableResult
    func repairCurrentChatModelIfNeeded(_ settingsStore: SettingsStore? = nil) -> Bool {
        if let currentModel = snapshot.getCurrentChatModel(),
           let provider = ChatProviderConfiguration.provider(for: currentModel, providers: snapshot.providers),
           ChatProviderConfiguration.issue(for: currentModel, provider: provider) == nil {
            if let settingsStore {
                syncLegacySettingsStoreForCurrentChat(settingsStore)
            }
            return false
        }

        let configuredProviders = ChatProviderConfiguration.configuredChatModels(in: snapshot.providers)
        guard configuredProviders.count == 1,
              let model = configuredProviders[0].models.first else {
            return false
        }
        setCurrentChatModelId(model.id.description())
        if let settingsStore {
            syncLegacySettingsStoreForCurrentChat(settingsStore)
        }
        return true
    }
}
