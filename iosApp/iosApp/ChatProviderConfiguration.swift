import Foundation
import Shared

enum ChatConfigurationIssue: Equatable {
    case missingAPIKey
    case invalidBaseURL
    case missingModel
    case missingProvider
    case unsupportedProvider

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
        case .unsupportedProvider:
            "当前服务商暂不支持聊天"
        }
    }

    var message: String {
        switch self {
        case .missingAPIKey:
            "请先添加服务商 API Key，再发送第一条消息。"
        case .invalidBaseURL:
            "当前服务商 API 地址不是有效的 http/https URL，请修正后再试。"
        case .missingModel:
            "请选择当前服务商可用的聊天模型，或填写服务商文档中的 Model ID。"
        case .missingProvider:
            "请先在设置里添加一个服务商（并填写 API Key 与模型），再发送消息。"
        case .unsupportedProvider:
            "当前服务商类型的 iOS 聊天执行器尚未移植，请先切换到 OpenAI 兼容或 Anthropic 服务商。"
        }
    }
}

enum ChatProviderConfiguration {
    static func provider(for model: Model, providers: [ProviderSetting]) -> ProviderSetting? {
        model.findProvider(providers: providers, checkOverwrite: true)
    }

    static func issue(for model: Model, provider: ProviderSetting?) -> ChatConfigurationIssue? {
        guard let provider else { return .missingProvider }
        guard supportsChatStreaming(provider) else { return .unsupportedProvider }
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
            return !openAI.useResponseApi
        }
        return provider is ProviderSetting.Claude
    }

    private static func isValidHTTPBaseURL(_ value: String) -> Bool {
        guard
            let components = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
            let scheme = components.scheme?.lowercased(),
            scheme == "http" || scheme == "https",
            let host = components.host,
            !host.isEmpty
        else {
            return false
        }
        return true
    }

    private static func apiKey(of provider: ProviderSetting) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI { return openAI.apiKey }
        if let claude = provider as? ProviderSetting.Claude { return claude.apiKey }
        if let google = provider as? ProviderSetting.Google { return google.apiKey }
        return ""
    }

    private static func baseURL(of provider: ProviderSetting) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI { return openAI.baseUrl }
        if let claude = provider as? ProviderSetting.Claude { return claude.baseUrl }
        if let google = provider as? ProviderSetting.Google { return google.baseUrl }
        return ""
    }
}
