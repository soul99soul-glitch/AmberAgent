import XCTest
@preconcurrency import Shared
@testable import iosApp

private struct TestProviderRegistryKeyStore: ProviderRegistryKeyStore {
    let defaults: UserDefaults
    let prefix: String

    func loadKey(id: String) -> String? {
        defaults.string(forKey: prefix + id)
    }

    @discardableResult
    func saveKey(_ key: String, id: String) -> Bool {
        let storageKey = prefix + id
        if key.isEmpty {
            defaults.removeObject(forKey: storageKey)
        } else {
            defaults.set(key, forKey: storageKey)
        }
        return true
    }
}

private final class TestSettingsAPIKeyStore: SettingsAPIKeyStore {
    private var key = ""

    func loadApiKey() -> String? {
        key
    }

    @discardableResult
    func saveApiKey(_ key: String) -> Bool {
        self.key = key
        return true
    }
}

@MainActor
final class ProviderRegistryStoreTests: XCTestCase {

    func testAddedOpenAICompatibleProviderPersistsAndProjectsToSettingsStore() {
        let namespace = "ProviderRegistryTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        defaults.removePersistentDomain(forName: namespace)
        let settings = SettingsStore(
            userDefaults: defaults,
            storageKey: "\(namespace).settings",
            apiKeyStore: TestSettingsAPIKeyStore()
        )

        let keychainPrefix = "app.amber.ios.tests.provider.\(UUID().uuidString)."
        let keyStore = TestProviderRegistryKeyStore(defaults: defaults, prefix: keychainPrefix)
        let registry = ProviderRegistryStore(
            settingsStore: settings,
            userDefaults: defaults,
            keyNamespace: namespace,
            keychainPrefix: keychainPrefix,
            keyStore: keyStore
        )

        let activated = registry.addOpenAICompatibleProvider(
            name: "Local Compatible",
            baseUrl: "https://local.example/v1",
            apiKey: "sk-provider-registry-test",
            activate: true
        )

        XCTAssertTrue(activated, "a custom OpenAI-compatible provider with a key should become current")
        XCTAssertEqual(settings.baseUrl, "https://local.example/v1")
        XCTAssertEqual(settings.apiKey, "sk-provider-registry-test")
        let selectedId = registry.selectedProviderId
        XCTAssertFalse(selectedId.isEmpty)

        let restarted = ProviderRegistryStore(
            settingsStore: settings,
            userDefaults: defaults,
            keyNamespace: namespace,
            keychainPrefix: keychainPrefix,
            keyStore: keyStore
        )
        let persisted = restarted.providers.first { ProviderRegistryStore.id(of: $0) == selectedId }

        XCTAssertNotNil(persisted, "custom provider should survive a registry restart")
        XCTAssertEqual(persisted?.name, "Local Compatible")
        XCTAssertEqual(persisted.map(ProviderRegistryStore.baseURL(of:)), "https://local.example/v1")
        XCTAssertEqual(persisted.flatMap(restarted.storedKey(for:)), "sk-provider-registry-test")
        XCTAssertEqual(restarted.selectedProviderId, selectedId)
    }

    func testPresetKeySaveThenSelectProjectsAndSurvivesRestart() throws {
        let namespace = "ProviderRegistryPreset-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        defaults.removePersistentDomain(forName: namespace)
        let settingsKeyStore = TestSettingsAPIKeyStore()
        let settings = SettingsStore(
            userDefaults: defaults,
            storageKey: "\(namespace).settings",
            apiKeyStore: settingsKeyStore
        )
        let keychainPrefix = "app.amber.ios.tests.provider.\(UUID().uuidString)."
        let keyStore = TestProviderRegistryKeyStore(defaults: defaults, prefix: keychainPrefix)
        let registry = ProviderRegistryStore(
            settingsStore: settings,
            userDefaults: defaults,
            keyNamespace: namespace,
            keychainPrefix: keychainPrefix,
            keyStore: keyStore
        )
        let preset = try XCTUnwrap(registry.providers.first { registry.canActivate($0) })
        let presetId = ProviderRegistryStore.id(of: preset)

        XCTAssertFalse(registry.canSelect(preset))
        XCTAssertTrue(registry.saveKey("sk-preset-registry-test", for: preset))
        XCTAssertTrue(registry.canSelect(preset))

        registry.select(preset)

        XCTAssertEqual(registry.selectedProviderId, presetId)
        XCTAssertEqual(settings.baseUrl, ProviderRegistryStore.baseURL(of: preset))
        XCTAssertEqual(settings.apiKey, "sk-preset-registry-test")

        let restarted = ProviderRegistryStore(
            settingsStore: settings,
            userDefaults: defaults,
            keyNamespace: namespace,
            keychainPrefix: keychainPrefix,
            keyStore: keyStore
        )

        XCTAssertEqual(restarted.selectedProviderId, presetId)
        XCTAssertEqual(restarted.selectedProvider.map(ProviderRegistryStore.baseURL(of:)), ProviderRegistryStore.baseURL(of: preset))
        XCTAssertEqual(restarted.storedKey(for: preset), "sk-preset-registry-test")
    }

    func testSettingsStorePersistsProviderConfigurationAcrossRestart() throws {
        let namespace = "SettingsStorePersistence-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        defaults.removePersistentDomain(forName: namespace)
        let keyStore = TestSettingsAPIKeyStore()

        let first = SettingsStore(
            userDefaults: defaults,
            storageKey: "\(namespace).settings",
            apiKeyStore: keyStore
        )
        first.baseUrl = "https://api.example.com/v1"
        first.apiKey = "sk-settings-persist"
        first.modelId = "example-chat-model"

        let restarted = SettingsStore(
            userDefaults: defaults,
            storageKey: "\(namespace).settings",
            apiKeyStore: keyStore
        )

        XCTAssertEqual(restarted.baseUrl, "https://api.example.com/v1")
        XCTAssertEqual(restarted.apiKey, "sk-settings-persist")
        XCTAssertEqual(restarted.currentApiKey, "sk-settings-persist")
        XCTAssertEqual(restarted.modelId, "example-chat-model")

        let rawSettings = try XCTUnwrap(defaults.data(forKey: "\(namespace).settings"))
        let rawJSON = String(data: rawSettings, encoding: .utf8) ?? ""
        XCTAssertFalse(rawJSON.contains("sk-settings-persist"), "API keys must stay out of UserDefaults JSON")
    }

    func testChatSendRequiresApiKeyBeforeAppendingUserMessage() {
        let settings = makeSettings(apiKey: "", modelId: "gpt-4o")
        let viewModel = ChatViewModel(settingsStore: settings)
        viewModel.inputText = "Hello"

        viewModel.sendMessage()

        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertEqual(viewModel.inputText, "Hello")
        XCTAssertEqual(viewModel.configurationIssue, .missingAPIKey)
        XCTAssertTrue(viewModel.configurationError?.contains("API Key") == true)
    }

    func testChatSendRequiresValidBaseURL() {
        let settings = makeSettings(baseUrl: "not a url", apiKey: "sk-test", modelId: "gpt-4o")
        let viewModel = ChatViewModel(settingsStore: settings)
        viewModel.inputText = "Hello"

        viewModel.sendMessage()

        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertEqual(viewModel.configurationIssue, .invalidBaseURL)
        XCTAssertTrue(viewModel.configurationError?.contains("API 地址") == true)
    }

    func testChatSendRequiresModelId() {
        let settings = makeSettings(apiKey: "sk-test", modelId: "")
        let viewModel = ChatViewModel(settingsStore: settings)
        viewModel.inputText = "Hello"

        viewModel.sendMessage()

        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertEqual(viewModel.configurationIssue, .missingModel)
        XCTAssertTrue(viewModel.configurationError?.contains("模型") == true)
    }

    func testUserFacingGenerationErrorsMapCommonProviderFailures() {
        XCTAssertTrue(
            ChatViewModel.userFacingGenerationError("401 Unauthorized: invalid API key")
                .contains("API Key 无效")
        )
        XCTAssertTrue(
            ChatViewModel.userFacingGenerationError("model_not_found: The model does not exist", modelId: "bad-model")
                .contains("模型不可用")
        )
        XCTAssertTrue(
            ChatViewModel.userFacingGenerationError("NSURLErrorDomain timed out")
                .contains("网络连接失败")
        )
    }

    func testModelDefaultsOnlyExposeCurrentProviderChatModels() {
        let provider = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Current Provider",
            models: [
                makeChatModel("current-chat-a"),
                makeChatModel("current-chat-b"),
                makeChatModel("current-chat-a"),
                makeChatModel(" ")
            ],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "",
            baseUrl: "https://api.example.com/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )

        XCTAssertEqual(
            ModelDefaultsChatModelSource.chatModelIds(for: provider),
            ["current-chat-a", "current-chat-b"]
        )
        XCTAssertEqual(ModelDefaultsChatModelSource.chatModelIds(for: nil), [])
    }

    private func makeSettings(
        baseUrl: String = "https://api.example.com/v1",
        apiKey: String,
        modelId: String
    ) -> SettingsStore {
        let namespace = "ChatConfiguration-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        defaults.removePersistentDomain(forName: namespace)
        let settings = SettingsStore(
            userDefaults: defaults,
            storageKey: "\(namespace).settings",
            apiKeyStore: TestSettingsAPIKeyStore()
        )
        settings.baseUrl = baseUrl
        settings.apiKey = apiKey
        settings.modelId = modelId
        return settings
    }

    private func makeChatModel(_ modelId: String) -> Model {
        Model(
            modelId: modelId,
            displayName: modelId,
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
    }
}
