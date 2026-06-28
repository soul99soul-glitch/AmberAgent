import Foundation
import Shared

/// Bridges the native Swift Codex OAuth client into the chat request path.
///
/// iOS chat requests are executed by the KMP `OpenAIKmpProvider`, which only
/// knows how to read `providerSetting.apiKey` as the bearer. For a provider in
/// `CODEX_OAUTH` mode there is no API key — the bearer is a short-lived OAuth
/// access token. This resolver, called right before a request is dispatched,
/// resolves a valid access token (refreshing if needed) and returns a
/// request-ready copy of the provider:
///   - `apiKey`        = the resolved OAuth access token (bearer)
///   - `useResponseApi`= true (codex speaks the Responses API)
///   - `baseUrl`       = the codex backend
/// Non-codex providers are returned unchanged.
enum IOSCodexProviderResolver {
    /// Stable per-provider credential id, matching the key the login flow and the
    /// Keychain token store use.
    static func providerKey(_ openAI: ProviderSetting.OpenAI) -> String {
        openAI.id.description()
    }

    static func isCodexProvider(_ provider: ProviderSetting) -> Bool {
        guard let openAI = provider as? ProviderSetting.OpenAI else { return false }
        return isCodexConfiguration(openAI)
    }

    static func isSignedIn(_ provider: ProviderSetting) -> Bool {
        guard let openAI = provider as? ProviderSetting.OpenAI else { return false }
        return IOSCodexAuthStore.load(providerId: providerKey(openAI)) != nil
    }

    /// Returns a request-ready provider. Throws if a codex provider isn't signed
    /// in or the token can't be refreshed.
    static func resolved(_ provider: ProviderSetting) async throws -> ProviderSetting {
        guard let openAI = provider as? ProviderSetting.OpenAI,
              isCodexConfiguration(openAI) else {
            return provider
        }
        let token = try await IOSCodexOAuthClient(providerId: providerKey(openAI)).getValidAccessToken()
        return ProviderSetting.OpenAI(
            id: openAI.id,
            enabled: openAI.enabled,
            name: openAI.name,
            models: openAI.models,
            balanceOption: openAI.balanceOption,
            builtIn: openAI.builtIn,
            descriptionText: openAI.descriptionText,
            shortDescriptionText: openAI.shortDescriptionText,
            apiKey: token,
            baseUrl: IOSCodexOAuthConstants.codexBackendBaseUrl,
            chatCompletionsPath: openAI.chatCompletionsPath,
            useResponseApi: true,
            authMode: OpenAIAuthMode.codexOauth,
            brand: openAI.brand
        )
    }

    /// Codex `/responses` requires extra headers (mirrors Android's codex chat
    /// path in OpenAIProvider) — most importantly `OpenAI-Beta: responses=experimental`,
    /// without which the backend returns 404 Not Found. They're injected via
    /// `customHeaders` so the KMP provider forwards them. No-op for non-codex.
    static func augmentParamsForCodex(_ params: TextGenerationParams, provider: ProviderSetting) -> TextGenerationParams {
        guard let openAI = provider as? ProviderSetting.OpenAI,
              isCodexConfiguration(openAI) else {
            return params
        }
        var headers = params.customHeaders
        headers.upsertCodexHeader(name: "OpenAI-Beta", value: "responses=experimental")
        headers.upsertCodexHeader(name: "originator", value: IOSCodexOAuthConstants.originator)
        if let accountId = IOSCodexAuthStore.load(providerId: providerKey(openAI))?.accountId,
           !accountId.isEmpty {
            headers.upsertCodexHeader(name: "ChatGPT-Account-Id", value: accountId)
        }
        return TextGenerationParams(
            model: params.model,
            temperature: params.temperature,
            topP: params.topP,
            maxTokens: params.maxTokens,
            tools: params.tools,
            reasoningLevel: params.reasoningLevel,
            customHeaders: headers,
            customBody: params.customBody
        )
    }

    private static func isCodexConfiguration(_ openAI: ProviderSetting.OpenAI) -> Bool {
        if openAI.authMode == OpenAIAuthMode.codexOauth { return true }
        return hasCodexBackendBaseUrl(openAI.baseUrl)
    }

    private static func hasCodexBackendBaseUrl(_ value: String) -> Bool {
        guard let components = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "https",
              components.host?.lowercased() == "chatgpt.com" else {
            return false
        }
        let path = components.path
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .lowercased()
        return path == "backend-api/codex" || path == "backend-api/codex/v1"
    }
}

private extension Array where Element == CustomHeader {
    mutating func upsertCodexHeader(name: String, value: String) {
        removeAll { $0.name.caseInsensitiveCompare(name) == .orderedSame }
        append(CustomHeader(name: name, value: value))
    }
}
