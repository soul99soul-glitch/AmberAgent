import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class ProviderCredentialAndHeaderTests: XCTestCase {
    func testOpenCodeUserAgentIsVersioned() {
        let ua = OpenAICompatUserAgents.shared.OPENCODE
        XCTAssertTrue(ua.hasPrefix("opencode/"), ua)
        XCTAssertEqual(ua, "opencode/1.18.18")
        XCTAssertFalse(ua.hasSuffix("opencode/"), "User-Agent must include a version")
    }

    func testHeaderStorePersistsUserAgentAndDropsBlankRows() {
        let defaults = UserDefaults(suiteName: "ProviderHeaderTests-\(UUID().uuidString)")!
        let providerId = UUID().uuidString
        IOSProviderRequestHeaderStore.save(
            providerId: providerId,
            userAgent: "  \(OpenAICompatUserAgents.shared.OPENCODE)  ",
            extra: [
                .init(name: "X-Title", value: "AmberAgent"),
                .init(name: "  ", value: "skip"),
            ],
            defaults: defaults
        )

        let headers = IOSProviderRequestHeaderStore.headers(for: providerId, defaults: defaults)
        XCTAssertEqual(headers.map(\.name), ["User-Agent", "X-Title"])
        XCTAssertEqual(headers.map(\.value), [OpenAICompatUserAgents.shared.OPENCODE, "AmberAgent"])
        XCTAssertEqual(ProviderUserAgentPreset.matching(userAgent: OpenAICompatUserAgents.shared.OPENCODE), .opencode)
        XCTAssertEqual(ProviderUserAgentPreset.matching(userAgent: "MyAgent/1"), .custom)
        XCTAssertNil(ProviderUserAgentPreset.matching(userAgent: nil))
        XCTAssertNil(ProviderUserAgentPreset.matching(userAgent: "  "))
    }

    func testApiKeyProviderHasUsableCredentialAndCodexDoesNotWithoutLogin() {
        let keyed = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "keyed",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "sk-test",
            baseUrl: "https://api.openai.com/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.openai
        )
        XCTAssertTrue(ChatProviderConfiguration.hasUsableCredential(keyed))
        XCTAssertEqual(ChatProviderConfiguration.credentialStatusTitle(keyed), "已配置")

        let codex = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "codex",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "",
            baseUrl: "https://chatgpt.com/backend-api/codex",
            chatCompletionsPath: "/responses",
            useResponseApi: true,
            authMode: OpenAIAuthMode.codexOauth,
            brand: OpenAIBrand.openai
        )
        XCTAssertFalse(ChatProviderConfiguration.hasUsableCredential(codex))
        XCTAssertEqual(ChatProviderConfiguration.credentialStatusTitle(codex), "未填写")
    }
}
