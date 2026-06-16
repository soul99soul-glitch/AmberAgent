import Foundation
import Observation
import Security
@preconcurrency import Shared

/// Real, persisted multi-provider registry for iOS.
///
/// Design (additive, Chat-safe):
/// - The source of truth is the real KMP `DEFAULT_PROVIDERS` list exported by the
///   Shared framework. Until iOS has a safe mutable `Settings.providers` bridge,
///   the registry does not decode or mutate a provider list at app startup.
/// - API keys are NEVER held inside the in-memory `ProviderSetting` or written to
///   UserDefaults: the in-memory providers stay key-less (apiKey == ""), and each
///   provider's key lives in the Keychain under a per-id account. This mirrors the
///   existing `SettingsStore` Keychain split.
/// - Selecting a provider PROJECTS its baseUrl + key into `SettingsStore.baseUrl` /
///   `.apiKey`, which `ChatViewModel` already reads. So Chat and every existing
///   consumer of `SettingsStore` keep working unchanged; `modelId` stays an
///   independent scalar (seeded image models must not become the chat model).
@Observable
final class ProviderRegistryStore {

    /// Key-less real provider settings (api keys live in the Keychain).
    private(set) var providers: [ProviderSetting]
    private(set) var selectedProviderId: String

    @ObservationIgnored private let settingsStore: SettingsStore

    private static let selectedKey = "app.amber.ios.providerRegistry.selectedId"
    private static let migratedKey = "app.amber.ios.providerRegistry.migratedV1"
    private static let keychainPrefix = "app.amber.ios.provider."

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore

        let defaults = UserDefaults.standard
        // Always read the live KMP defaults. Decoding persisted ProviderSetting JSON
        // would need a safe KMP wrapper because Kotlin serialization failures can
        // cross the Swift boundary as process-fatal exceptions.
        self.providers = DefaultProvidersKt.DEFAULT_PROVIDERS
        self.selectedProviderId = defaults.string(forKey: Self.selectedKey) ?? ""

        // One-time migration: represent the user's existing single-config values as
        // a real selected provider without changing what Chat currently reads.
        if !defaults.bool(forKey: Self.migratedKey) {
            migrateExistingConfig()
            defaults.set(true, forKey: Self.migratedKey)
        }

        // Ensure the selected row is both present and usable by the current chat chain.
        if let selected = selectedProvider, canSelect(selected) {
            selectedProviderId = Self.id(of: selected)
        } else if let match = providers.first(where: {
            canSelect($0) && Self.baseURL(of: $0).trimmingCharacters(in: .whitespacesAndNewlines) == settingsStore.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        }) {
            selectedProviderId = Self.id(of: match)
            defaults.set(selectedProviderId, forKey: Self.selectedKey)
        } else {
            selectedProviderId = ""
            defaults.removeObject(forKey: Self.selectedKey)
        }
    }

    // MARK: - Selection

    var selectedProvider: ProviderSetting? {
        providers.first { Self.id(of: $0) == selectedProviderId }
    }

    func isSelected(_ provider: ProviderSetting) -> Bool {
        Self.id(of: provider) == selectedProviderId
    }

    /// Whether this provider can be faithfully projected into the current iOS chat
    /// chain, which only constructs `ProviderSetting.OpenAI` with `useResponseApi=false`.
    /// Google/Claude need a provider bridge; xAI needs the Response API; MiMo's bundled
    /// base is a source-marked placeholder — none can be silently activated yet.
    func canActivate(_ provider: ProviderSetting) -> Bool {
        guard let openAI = provider as? ProviderSetting.OpenAI else { return false }
        if openAI.useResponseApi { return false }
        if openAI.brand === OpenAIBrand.mimo { return false }
        return true
    }

    /// A provider can become the active chat provider only when it is both
    /// representable by today's scalar OpenAI-compatible chain and has a stored key.
    /// This prevents a key-less preset tap from silently clearing the working chat key.
    func canSelect(_ provider: ProviderSetting) -> Bool {
        canActivate(provider) && hasStoredKey(provider)
    }

    /// Select a provider as the active chat provider and project it into SettingsStore.
    /// No-op for providers the current chat chain cannot faithfully represent.
    func select(_ provider: ProviderSetting) {
        let id = Self.id(of: provider)
        guard canSelect(provider) else { return }
        guard providers.contains(where: { Self.id(of: $0) == id }) else { return }
        syncCurrentKeyToSelectedProvider()
        selectedProviderId = id
        UserDefaults.standard.set(id, forKey: Self.selectedKey)
        project()
    }

    /// Whether this provider has a stored Keychain key (for honest UI status only).
    func hasStoredKey(_ provider: ProviderSetting) -> Bool {
        !(Self.loadKey(id: Self.id(of: provider)) ?? "").isEmpty
    }

    // MARK: - Projection into SettingsStore (the surface Chat reads)

    /// Write the selected provider's baseUrl + Keychain key into SettingsStore so the
    /// existing ChatViewModel path uses it. `modelId` is intentionally left untouched.
    private func project() {
        guard let selected = selectedProvider else { return }
        settingsStore.baseUrl = Self.baseURL(of: selected)
        settingsStore.apiKey = Self.loadKey(id: selectedProviderId) ?? ""
    }

    /// Before switching away, preserve the currently active scalar key under the
    /// selected provider id. This keeps keys recoverable while the SettingsStore UI
    /// is still the only place that can edit provider credentials.
    private func syncCurrentKeyToSelectedProvider() {
        guard !selectedProviderId.isEmpty else { return }
        guard let selected = selectedProvider, canActivate(selected) else { return }
        Self.saveKey(settingsStore.apiKey, id: selectedProviderId)
    }

    // MARK: - Migration

    private func migrateExistingConfig() {
        let currentBase = settingsStore.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let currentKey = settingsStore.apiKey

        // If an existing ACTIVATABLE provider already matches the current Base URL, adopt
        // it as the selection and stash the user's current key under that provider.
        // Re-projecting then yields the exact same baseUrl/apiKey the app already uses (no
        // behavior change). Require canActivate so a current Base URL that happens to equal
        // a Google/xAI/MiMo seed does not strand the key on a provider the UI can never
        // project; such cases fall through to a real custom OpenAI-compatible provider.
        if let match = providers.first(where: {
            canActivate($0) && Self.baseURL(of: $0).trimmingCharacters(in: .whitespacesAndNewlines) == currentBase
        }) {
            selectedProviderId = Self.id(of: match)
        } else if !currentBase.isEmpty {
            // Otherwise create a real custom OpenAI-compatible provider carrying the
            // current Base URL (key-less) and select it.
            let custom = Self.makeOpenAIProvider(name: "当前配置", baseUrl: currentBase)
            providers.insert(custom, at: 0)
            selectedProviderId = Self.id(of: custom)
        }

        if !selectedProviderId.isEmpty {
            Self.saveKey(currentKey, id: selectedProviderId)
            UserDefaults.standard.set(selectedProviderId, forKey: Self.selectedKey)
        }
    }

    // MARK: - Helpers

    static func id(of provider: ProviderSetting) -> String {
        provider.id.toHexDashString()
    }

    static func baseURL(of provider: ProviderSetting) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI { return openAI.baseUrl }
        if let google = provider as? ProviderSetting.Google { return google.baseUrl }
        if let claude = provider as? ProviderSetting.Claude { return claude.baseUrl }
        return ""
    }

    private static func makeOpenAIProvider(name: String, baseUrl: String) -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: name,
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "",
            baseUrl: baseUrl,
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    // MARK: - Keychain (per-provider api key)

    private static func account(for id: String) -> String { keychainPrefix + id }

    static func loadKey(id: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account(for: id),
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func saveKey(_ key: String, id: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account(for: id),
        ]
        SecItemDelete(query as CFDictionary)
        guard !key.isEmpty else { return }
        var attributes = query
        attributes[kSecValueData as String] = Data(key.utf8)
        SecItemAdd(attributes as CFDictionary, nil)
    }
}
