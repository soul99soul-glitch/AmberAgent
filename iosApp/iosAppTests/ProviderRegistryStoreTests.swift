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

    func testResponseApiOpenAIProviderIsSupportedAndActivatable() {
        let namespace = "ResponseApiProvider-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        defaults.removePersistentDomain(forName: namespace)
        let settings = SettingsStore(
            userDefaults: defaults,
            storageKey: "\(namespace).settings",
            apiKeyStore: TestSettingsAPIKeyStore()
        )
        let registry = ProviderRegistryStore(
            settingsStore: settings,
            userDefaults: defaults,
            keyNamespace: namespace,
            keychainPrefix: "app.amber.ios.tests.provider.\(UUID().uuidString).",
            keyStore: TestProviderRegistryKeyStore(defaults: defaults, prefix: namespace)
        )
        let provider = makeOpenAIProvider(useResponseApi: true, apiKey: "xai-test-key")
        let model = provider.models[0]

        XCTAssertTrue(ChatProviderConfiguration.supportsChatStreaming(provider))
        XCTAssertNil(ChatProviderConfiguration.issue(for: model, provider: provider))
        XCTAssertTrue(registry.canActivate(provider))
        XCTAssertTrue(ProviderRouteKind.isEditablePreset(provider))
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
        // Config source is the shared (KMP) provider, not the legacy SettingsStore.
        // A Claude provider with an empty apiKey must surface .missingAPIKey and
        // block the send (message not appended, input preserved).
        let settings = makeSettings(apiKey: "", modelId: "claude-sonnet-4-5")
        let sharedSettings = makeSharedSettingsWithClaudeProvider(apiKey: "")
        let viewModel = ChatViewModel(
            settingsStore: settings,
            sharedSettings: sharedSettings,
            autoGenerateResponses: true
        )
        viewModel.inputText = "Hello"

        viewModel.sendMessage()

        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertEqual(viewModel.inputText, "Hello")
        XCTAssertEqual(viewModel.configurationIssue, .missingAPIKey)
        XCTAssertTrue(viewModel.configurationError?.contains("API Key") == true)
    }

    func testWatchAnswerReportsFailureWhenProviderConfigurationBlocksSend() {
        let viewModel = ChatViewModel(
            settingsStore: makeSettings(apiKey: "", modelId: "claude-sonnet-4-5"),
            sharedSettings: makeSharedSettingsWithClaudeProvider(apiKey: ""),
            autoGenerateResponses: true
        )

        let accepted = viewModel.submitWatchUserAnswer(
            runId: "watch-run-without-approval",
            text: "Hello from Watch"
        )

        XCTAssertFalse(accepted)
        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertEqual(viewModel.inputText, "Hello from Watch")
        XCTAssertEqual(viewModel.configurationIssue, .missingAPIKey)
    }

    func testChatSendRequiresValidBaseURL() {
        // A Claude provider with an invalid baseUrl must surface .invalidBaseURL.
        let settings = makeSettings(baseUrl: "not a url", apiKey: "sk-test", modelId: "claude-sonnet-4-5")
        let sharedSettings = makeSharedSettingsWithClaudeProviderInvalidBaseUrl()
        let viewModel = ChatViewModel(
            settingsStore: settings,
            sharedSettings: sharedSettings,
            autoGenerateResponses: true
        )
        viewModel.inputText = "Hello"

        viewModel.sendMessage()

        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertEqual(viewModel.configurationIssue, .invalidBaseURL)
        XCTAssertEqual(viewModel.configurationError, ChatConfigurationIssue.invalidBaseURL.message)
    }

    func testChatSendRequiresModelId() {
        // No selected chat model + no configured chat providers must surface
        // .missingModel.
        let settings = makeSettings(apiKey: "sk-test", modelId: "")
        let sharedSettings = makeEmptySharedSettings()
        let viewModel = ChatViewModel(
            settingsStore: settings,
            sharedSettings: sharedSettings,
            autoGenerateResponses: true
        )
        viewModel.inputText = "Hello"

        viewModel.sendMessage()

        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertEqual(viewModel.configurationIssue, .missingModel)
        XCTAssertTrue(viewModel.configurationError?.contains("模型") == true)
    }

    func testChatConfigurationUsesSelectedSharedProviderWhenAvailable() {
        let settings = makeSettings(apiKey: "", modelId: "")
        let sharedSettings = makeSharedSettingsWithClaudeProvider(apiKey: "sk-ant-test")
        let viewModel = ChatViewModel(
            settingsStore: settings,
            sharedSettings: sharedSettings,
            autoGenerateResponses: false
        )

        XCTAssertNil(viewModel.configurationIssue)
        XCTAssertEqual(viewModel.contextSnapshot.modelId, "claude-sonnet-4-5")
        XCTAssertEqual(viewModel.textGenerationParamsForTesting().model.modelId, "claude-sonnet-4-5")
    }

    func testCurrentSharedProviderMissingKeyIsNotMaskedByLegacySettingsStore() {
        let settings = makeSettings(apiKey: "sk-legacy-test", modelId: "legacy-model")
        let sharedSettings = makeSharedSettingsWithClaudeProvider(apiKey: "")
        let viewModel = ChatViewModel(
            settingsStore: settings,
            sharedSettings: sharedSettings,
            autoGenerateResponses: false
        )

        XCTAssertEqual(viewModel.configurationIssue, .missingAPIKey)
    }

    func testDisabledSharedProviderCannotSend() throws {
        let settings = makeSettings(apiKey: "sk-legacy-test", modelId: "legacy-model")
        let sharedSettings = makeSharedSettingsWithClaudeProvider(apiKey: "sk-ant-test")
        let model = try XCTUnwrap(sharedSettings.snapshot.getCurrentChatModel())
        let provider = try XCTUnwrap(ChatProviderConfiguration.provider(
            for: model,
            providers: sharedSettings.snapshot.providers
        ))
        _ = sharedSettings.updateProviderBasics(
            providerId: provider.id.description(),
            name: provider.name,
            enabled: false
        )
        let viewModel = ChatViewModel(
            settingsStore: settings,
            sharedSettings: sharedSettings,
            autoGenerateResponses: true
        )
        viewModel.inputText = "Hello"

        XCTAssertEqual(viewModel.configurationIssue, .providerDisabled)

        viewModel.sendMessage()

        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertEqual(viewModel.inputText, "Hello")
        XCTAssertEqual(viewModel.configurationError, ChatConfigurationIssue.providerDisabled.message)
    }

    func testGeminiApiKeyProviderIsSupportedForSending() {
        // Contract update (Gemini iOS executor landed 2026-08-15): a Gemini
        // provider with an API key + chat model is no longer "unsupported".
        let settings = makeSettings(apiKey: "sk-legacy-test", modelId: "legacy-model")
        let sharedSettings = makeSharedSettingsWithGoogleChatProvider(apiKey: "AIza-test")
        let viewModel = ChatViewModel(settingsStore: settings, sharedSettings: sharedSettings)
        viewModel.inputText = "Hello"

        XCTAssertNil(viewModel.configurationIssue)

        viewModel.sendMessage()

        XCTAssertFalse(viewModel.messages.isEmpty)
    }

    func testUnsupportedSharedProviderIsReportedBeforeSending() {
        // The Android-only Gemini Code Assist OAuth mode has no iOS chat
        // executor yet, so it must still be reported before sending.
        let settings = makeSettings(apiKey: "sk-legacy-test", modelId: "legacy-model")
        let sharedSettings = makeSharedSettingsWithGoogleChatProvider(apiKey: "")
        _ = sharedSettings.setGoogleAuthMode(
            providerId: sharedSettings.snapshot.providers.first { $0 is ProviderSetting.Google }!.id.description(),
            authMode: GoogleAuthMode.geminiCodeAssistOauth
        )
        let viewModel = ChatViewModel(settingsStore: settings, sharedSettings: sharedSettings)
        viewModel.inputText = "Hello"

        XCTAssertEqual(viewModel.configurationIssue, .unsupportedProvider)

        viewModel.sendMessage()

        XCTAssertTrue(viewModel.messages.isEmpty)
        XCTAssertEqual(viewModel.configurationError, ChatConfigurationIssue.unsupportedProvider.message)
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

    private func makeSharedSettingsWithClaudeProvider(apiKey: String) -> IOSSharedSettingsStore {
        let namespace = "SharedClaudeProvider-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        defaults.removePersistentDomain(forName: namespace)
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        let provider = IosSettingsMutations.shared.buildClaudeProvider(
            name: "Claude Test",
            apiKey: apiKey,
            baseUrl: "https://api.anthropic.com/v1",
            modelName: "Claude Sonnet 4.5",
            modelId: "claude-sonnet-4-5"
        )
        let added = sharedSettings.addProvider(provider)
        let chatModel = added.models.first { $0.type == ModelType.chat }!
        sharedSettings.setCurrentChatModelId(chatModel.id.description())
        return sharedSettings
    }

    /// A Claude provider with a configured key but an INVALID base URL — drives
    /// the `.invalidBaseURL` configuration issue.
    private func makeSharedSettingsWithClaudeProviderInvalidBaseUrl() -> IOSSharedSettingsStore {
        let namespace = "SharedClaudeBadUrl-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        defaults.removePersistentDomain(forName: namespace)
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        let provider = IosSettingsMutations.shared.buildClaudeProvider(
            name: "Claude Bad URL",
            apiKey: "sk-ant-test",
            baseUrl: "not a url",
            modelName: "Claude Sonnet 4.5",
            modelId: "claude-sonnet-4-5"
        )
        let added = sharedSettings.addProvider(provider)
        let chatModel = added.models.first { $0.type == ModelType.chat }!
        sharedSettings.setCurrentChatModelId(chatModel.id.description())
        return sharedSettings
    }

    /// An empty shared-settings store (no providers, no selected model) — drives
    /// the `.missingModel` configuration issue.
    private func makeEmptySharedSettings() -> IOSSharedSettingsStore {
        let namespace = "EmptyShared-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        defaults.removePersistentDomain(forName: namespace)
        return IOSSharedSettingsStore(userDefaults: defaults)
    }

    private func makeSharedSettingsWithGoogleChatProvider(apiKey: String) -> IOSSharedSettingsStore {
        let namespace = "SharedGoogleProvider-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        defaults.removePersistentDomain(forName: namespace)
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        let google = sharedSettings.snapshot.providers.first { $0 is ProviderSetting.Google }!
        let providerId = google.id.description()
        sharedSettings.updateProviderApiKey(providerId: providerId, apiKey: apiKey)
        let updated = sharedSettings.updateProviderChatModels(
            providerId: providerId,
            models: [(modelId: "gemini-test-chat", displayName: "Gemini Test Chat")]
        )!
        let chatModel = updated.models.first { $0.type == ModelType.chat }!
        sharedSettings.setCurrentChatModelId(chatModel.id.description())
        return sharedSettings
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

    private func makeOpenAIProvider(useResponseApi: Bool, apiKey: String) -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: useResponseApi ? "xAI Responses" : "OpenAI Compatible",
            models: [makeChatModel("grok-4")],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: true,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: apiKey,
            baseUrl: "https://api.x.ai/v1",
            chatCompletionsPath: useResponseApi ? "/responses" : "/chat/completions",
            useResponseApi: useResponseApi,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }
}
