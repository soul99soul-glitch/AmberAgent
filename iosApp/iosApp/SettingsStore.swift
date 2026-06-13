import Foundation
import Security

private struct SettingsData: Codable {
    var baseUrl: String
    var apiKey: String  // kept for Codable compatibility; always empty now (real key lives in Keychain)
    var modelId: String
}

@Observable
final class SettingsStore {

    var baseUrl: String {
        didSet { save() }
    }
    var apiKey: String {
        didSet { saveApiKey() }
    }
    var modelId: String {
        didSet { save() }
    }

    private static let storageKey = "app.amber.ios.settings"
    private static let apiKeyKeychainAccount = "app.amber.ios.apiKey"

    init() {
        // Load non-sensitive settings from UserDefaults.
        let defaults = UserDefaults.standard
        if let data = defaults.data(forKey: Self.storageKey),
           let decoded = try? JSONDecoder().decode(SettingsData.self, from: data) {
            baseUrl = decoded.baseUrl
            modelId = decoded.modelId
        } else {
            baseUrl = "https://api.openai.com/v1"
            modelId = "gpt-4o"
        }
        // Load the API key from Keychain (empty string if not found).
        apiKey = Self.loadApiKey() ?? ""
    }

    private func save() {
        let data = SettingsData(
            baseUrl: baseUrl,
            apiKey: "",  // never persist the key to UserDefaults
            modelId: modelId
        )
        if let encoded = try? JSONEncoder().encode(data) {
            UserDefaults.standard.set(encoded, forKey: Self.storageKey)
        }
    }

    private func saveApiKey() {
        Self.saveApiKey(apiKey)
    }

    // MARK: - Keychain helpers

    private static func saveApiKey(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: apiKeyKeychainAccount,
        ]
        // Remove any existing item first (upsert semantics).
        SecItemDelete(query as CFDictionary)
        guard !key.isEmpty else { return }
        var attributes = query
        attributes[kSecValueData as String] = Data(key.utf8)
        SecItemAdd(attributes as CFDictionary, nil)
    }

    private static func loadApiKey() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: apiKeyKeychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
