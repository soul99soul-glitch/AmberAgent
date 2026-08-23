import Foundation
import Observation
import Security
@preconcurrency import Shared

private struct StoredProviderRecord: Codable, Equatable {
    var id: String
    var name: String
    var baseUrl: String
}

protocol ProviderRegistryKeyStore {
    func loadKey(id: String) -> String?
    @discardableResult
    func saveKey(_ key: String, id: String) -> Bool
}

private struct KeychainProviderRegistryKeyStore: ProviderRegistryKeyStore {
    let keychainPrefix: String

    func loadKey(id: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainPrefix,
            kSecAttrAccount as String: account(for: id),
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    func saveKey(_ key: String, id: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainPrefix,
            kSecAttrAccount as String: account(for: id),
        ]
        let deleteStatus = SecItemDelete(query as CFDictionary)
        guard deleteStatus == errSecSuccess || deleteStatus == errSecItemNotFound else { return false }
        guard !key.isEmpty else { return true }
        var attributes = query
        attributes[kSecValueData as String] = Data(key.utf8)
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    private func account(for id: String) -> String {
        keychainPrefix + id
    }
}

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

    /// Monotonic counter bumped whenever a per-provider Keychain key is written or
    /// cleared. The key itself never lives in an observable property (it stays in
    /// the Keychain), but UI status rows (`hasStoredKey` / `canSelect`) read this
    /// revision so SwiftUI tracks them as observable and re-renders after a key
    /// edit when the user navigates back. This keeps the registry honest: the only
    /// observable surface for keys is "did the set of stored keys change", never the
    /// key value itself.
    private(set) var keyRevision: Int = 0

    @ObservationIgnored private let settingsStore: SettingsStore
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let selectedKey: String
    @ObservationIgnored private let migratedKey: String
    @ObservationIgnored private let customProvidersKey: String
    @ObservationIgnored private let keyStore: any ProviderRegistryKeyStore

    private static let defaultKeyNamespace = "app.amber.ios.providerRegistry"
    private static let defaultKeychainPrefix = "app.amber.ios.provider."

    init(
        settingsStore: SettingsStore,
        userDefaults: UserDefaults = .standard,
        keyNamespace: String = ProviderRegistryStore.defaultKeyNamespace,
        keychainPrefix: String = ProviderRegistryStore.defaultKeychainPrefix,
        keyStore: (any ProviderRegistryKeyStore)? = nil
    ) {
        self.settingsStore = settingsStore
        self.defaults = userDefaults
        self.selectedKey = "\(keyNamespace).selectedId"
        self.migratedKey = "\(keyNamespace).migratedV2"
        self.customProvidersKey = "\(keyNamespace).customProviders"
        self.keyStore = keyStore ?? KeychainProviderRegistryKeyStore(keychainPrefix: keychainPrefix)

        // Always read the live KMP defaults. Decoding persisted ProviderSetting JSON
        // would need a safe KMP wrapper because Kotlin serialization failures can
        // cross the Swift boundary as process-fatal exceptions.
        let storedProviders = Self.loadStoredProviderRecords(defaults: userDefaults, key: customProvidersKey)
            .compactMap(Self.makeOpenAIProvider(record:))
        self.providers = storedProviders.map { $0 as ProviderSetting } + DefaultProvidersKt.DEFAULT_PROVIDERS
        self.selectedProviderId = defaults.string(forKey: selectedKey) ?? ""

        // One-time migration: represent the user's existing single-config values as
        // a real selected provider without changing what Chat currently reads.
        if !defaults.bool(forKey: migratedKey) {
            migrateExistingConfig()
            defaults.set(true, forKey: migratedKey)
        }

        // Ensure the selected row is both present and usable by the current chat chain.
        if let selected = selectedProvider, canSelect(selected) {
            selectedProviderId = Self.id(of: selected)
        } else if let match = providers.first(where: {
            canSelect($0) && Self.baseURL(of: $0).trimmingCharacters(in: .whitespacesAndNewlines) == settingsStore.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        }) {
            selectedProviderId = Self.id(of: match)
            defaults.set(selectedProviderId, forKey: selectedKey)
        } else {
            selectedProviderId = ""
            defaults.removeObject(forKey: selectedKey)
        }
    }

    // MARK: - Selection

    var selectedProvider: ProviderSetting? {
        providers.first { Self.id(of: $0) == selectedProviderId }
    }

    func isSelected(_ provider: ProviderSetting) -> Bool {
        Self.id(of: provider) == selectedProviderId
    }

    /// Whether this provider can be faithfully used by the current iOS chat chain.
    /// OpenAI-compatible/Responses API and Claude have KMP executors; Gemini has a
    /// native Swift executor (API Key / Antigravity OAuth); MiMo's bundled base is
    /// a placeholder.
    func canActivate(_ provider: ProviderSetting) -> Bool {
        if let openAI = provider as? ProviderSetting.OpenAI {
            if openAI.brand === OpenAIBrand.mimo { return false }
            return true
        }
        if provider is ProviderSetting.Claude {
            return true
        }
        if let google = provider as? ProviderSetting.Google {
            return IOSGeminiProviderResolver.supportsChat(google)
        }
        return false
    }

    /// A provider can become the active chat provider only when it is both
    /// representable by today's chat chain and has a stored key (or a signed-in
    /// Antigravity session for Gemini OAuth).
    /// This prevents a key-less preset tap from silently clearing the working chat key.
    func canSelect(_ provider: ProviderSetting) -> Bool {
        canActivate(provider) && hasUsableCredential(provider)
    }

    private func hasUsableCredential(_ provider: ProviderSetting) -> Bool {
        if let google = provider as? ProviderSetting.Google,
           IOSGeminiProviderResolver.isAntigravityOAuth(google) {
            return IOSGeminiProviderResolver.isSignedIn(provider)
        }
        return hasStoredKey(provider)
    }

    /// Select a provider as the active chat provider and project it into SettingsStore.
    /// No-op for providers the current chat chain cannot faithfully represent.
    func select(_ provider: ProviderSetting) {
        let id = Self.id(of: provider)
        guard canSelect(provider) else { return }
        guard providers.contains(where: { Self.id(of: $0) == id }) else { return }
        syncCurrentKeyToSelectedProvider()
        selectedProviderId = id
        defaults.set(id, forKey: selectedKey)
        project()
    }

    /// Persist a user-added OpenAI-compatible provider and optionally activate it.
    /// Returns true only when the new provider became the current chat provider.
    @discardableResult
    func addOpenAICompatibleProvider(
        name: String,
        baseUrl: String,
        apiKey: String,
        activate: Bool = true
    ) -> Bool {
        let trimmedBase = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedBase.isEmpty else { return false }

        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let record = StoredProviderRecord(
            id: UUID().uuidString.lowercased(),
            name: trimmedName.isEmpty ? "OpenAI-compatible" : trimmedName,
            baseUrl: trimmedBase
        )
        guard let provider = appendStoredProvider(record) else { return false }

        let trimmedKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedKey.isEmpty {
            guard saveKey(trimmedKey, for: provider) else { return false }
        }

        guard activate, canSelect(provider) else { return false }
        syncCurrentKeyToSelectedProvider()
        selectedProviderId = record.id
        defaults.set(record.id, forKey: selectedKey)
        project()
        return true
    }

    /// Whether this provider has a stored Keychain key (for honest UI status only).
    func hasStoredKey(_ provider: ProviderSetting) -> Bool {
        !(loadKey(id: Self.id(of: provider)) ?? "").isEmpty
    }

    /// The real per-provider Keychain key for `provider`, or nil if none is stored.
    /// Used only to seed the Key editor and to show honest status. Never returns the
    /// key for a provider that is not the current selection into SettingsStore.
    func storedKey(for provider: ProviderSetting) -> String? {
        loadKey(id: Self.id(of: provider))
    }

    /// Write a per-provider API key into the real Keychain account for `provider`.
    ///
    /// This is the ONLY key-editing entry point for preset providers. It writes the
    /// real per-provider Keychain slot (account `app.amber.ios.provider.<id>`) and
    /// nothing else: it does NOT touch UserDefaults, does NOT mutate the in-memory
    /// key-less `ProviderSetting`, does NOT change the selected/current provider,
    /// does NOT project into `SettingsStore`, and does NOT make any network request.
    ///
    /// A provider becomes selectable as the current chat provider only after this
    /// stores a non-empty key AND the user taps "设为当前" (which runs `select()` and
    /// projects). Writing a key alone never activates the provider.
    @discardableResult
    func saveKey(_ key: String, for provider: ProviderSetting) -> Bool {
        let saved = saveKey(key, id: Self.id(of: provider))
        keyRevision &+= 1
        return saved
    }

    // MARK: - Projection into SettingsStore (the surface Chat reads)

    /// Write the selected provider's baseUrl + Keychain key into SettingsStore so the
    /// existing ChatViewModel path uses it. `modelId` is intentionally left untouched.
    private func project() {
        guard let selected = selectedProvider else { return }
        settingsStore.baseUrl = Self.baseURL(of: selected)
        settingsStore.apiKey = loadKey(id: selectedProviderId) ?? ""
    }

    /// Before switching away, preserve the currently active scalar key under the
    /// selected provider id. This keeps keys recoverable while the SettingsStore UI
    /// is still the only place that can edit provider credentials.
    private func syncCurrentKeyToSelectedProvider() {
        guard !selectedProviderId.isEmpty else { return }
        guard let selected = selectedProvider, canActivate(selected) else { return }
        saveKey(settingsStore.apiKey, id: selectedProviderId)
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
            // current Base URL (key-less), persist it, and select it.
            let record = StoredProviderRecord(
                id: UUID().uuidString.lowercased(),
                name: "当前配置",
                baseUrl: currentBase
            )
            if let custom = appendStoredProvider(record) {
                selectedProviderId = Self.id(of: custom)
            }
        }

        if !selectedProviderId.isEmpty {
            saveKey(currentKey, id: selectedProviderId)
            defaults.set(selectedProviderId, forKey: selectedKey)
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

    private func appendStoredProvider(_ record: StoredProviderRecord) -> ProviderSetting.OpenAI? {
        guard let provider = Self.makeOpenAIProvider(record: record) else { return nil }
        var records = Self.loadStoredProviderRecords(defaults: defaults, key: customProvidersKey)
        records.removeAll { $0.id == record.id }
        records.append(record)
        Self.saveStoredProviderRecords(records, defaults: defaults, key: customProvidersKey)
        providers.insert(provider, at: 0)
        return provider
    }

    private static func makeOpenAIProvider(record: StoredProviderRecord) -> ProviderSetting.OpenAI? {
        let trimmedName = record.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedBase = record.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard UUID(uuidString: record.id) != nil else { return nil }
        guard !trimmedName.isEmpty, !trimmedBase.isEmpty else { return nil }
        return IosSettingsMutations.shared.buildOpenAIProviderWithId(
            id: record.id,
            name: trimmedName,
            baseUrl: trimmedBase
        )
    }

    private static func loadStoredProviderRecords(defaults: UserDefaults, key: String) -> [StoredProviderRecord] {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode([StoredProviderRecord].self, from: data) else {
            return []
        }
        var seen = Set<String>()
        return decoded.compactMap { record in
            let trimmedId = record.id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let trimmedName = record.name.trimmingCharacters(in: .whitespacesAndNewlines)
            let trimmedBase = record.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            guard UUID(uuidString: trimmedId) != nil else { return nil }
            guard !trimmedName.isEmpty, !trimmedBase.isEmpty else { return nil }
            guard seen.insert(trimmedId).inserted else { return nil }
            return StoredProviderRecord(id: trimmedId, name: trimmedName, baseUrl: trimmedBase)
        }
    }

    private static func saveStoredProviderRecords(
        _ records: [StoredProviderRecord],
        defaults: UserDefaults,
        key: String
    ) {
        guard let data = try? JSONEncoder().encode(records) else { return }
        defaults.set(data, forKey: key)
    }

    // MARK: - Keychain (per-provider api key)

    private func loadKey(id: String) -> String? {
        keyStore.loadKey(id: id)
    }

    @discardableResult
    private func saveKey(_ key: String, id: String) -> Bool {
        keyStore.saveKey(key, id: id)
    }
}
