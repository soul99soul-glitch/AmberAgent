import Foundation
import Security

/// Keychain side-table for iOS-only scheme B credential storage.
///
/// Scheme B strips credentials from the persisted (UserDefaults/backup) form
/// (see `IOSCredentialRedactor`) and stores the real values here, in the
/// Keychain. On load, the masked values are rehydrated from this side-table.
///
/// The side-table is keyed by a stable credential ref (provider id + field, or
/// MCP server name + header name). It does NOT modify the shared
/// `ProviderSetting` KMP type (locked_decision). (P0.5 ships the slice pattern;
/// P2 extends coverage to all credential classes + migration of existing
/// plaintext values.)
///
/// This mirrors the existing per-provider Keychain pattern in
/// `KeychainProviderRegistryKeyStore` but is a generic store usable for any
/// credential class.
enum IOSCredentialSideTable {
    private static let service = "app.amber.ios.credentials"

    // MARK: - store / load

    /// Stores a credential value for a key. Returns true on success.
    @discardableResult
    static func store(key: String, value: String) -> Bool {
        guard let data = value.data(using: .utf8), !value.isEmpty else { return false }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        var addQuery = query
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        return status == errSecSuccess
    }

    /// Loads a credential value for a key, or nil if absent.
    static func load(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Deletes a credential value for a key.
    static func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - key builders (stable credential refs)
    //
    // NOTE: provider apiKeys are NOT routed through this side-table — they are
    // preserved by the separate, pre-existing `KeychainProviderRegistryKeyStore`
    // (per-provider Keychain). This side-table is used for credential classes
    // that have no dedicated Keychain wrapper (currently MCP server headers).

    /// MCP server header credential ref.
    static func mcpHeader(serverName: String, headerName: String) -> String {
        "mcp.\(serverName).header.\(headerName)"
    }
}
