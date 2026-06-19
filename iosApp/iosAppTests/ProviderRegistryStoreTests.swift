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

@MainActor
final class ProviderRegistryStoreTests: XCTestCase {

    func testAddedOpenAICompatibleProviderPersistsAndProjectsToSettingsStore() {
        let settings = SettingsStore()
        let originalBaseURL = settings.baseUrl
        let originalAPIKey = settings.apiKey
        let originalModelId = settings.modelId
        let originalRuntime = settings.terminalDefaultRuntime
        let originalExperimentalRuntimesEnabled = settings.terminalExperimentalRuntimesEnabled
        let originalSSHProfiles = settings.sshProfiles
        let originalSSHDefaultProfileId = settings.sshDefaultProfileId
        defer {
            settings.baseUrl = originalBaseURL
            settings.apiKey = originalAPIKey
            settings.modelId = originalModelId
            settings.terminalDefaultRuntime = originalRuntime
            settings.terminalExperimentalRuntimesEnabled = originalExperimentalRuntimesEnabled
            settings.sshProfiles = originalSSHProfiles
            settings.sshDefaultProfileId = originalSSHDefaultProfileId
        }

        let namespace = "ProviderRegistryTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        defaults.removePersistentDomain(forName: namespace)

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
}
