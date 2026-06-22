import Foundation

/// iOS-only scheme B credential redactor (parity with Android
/// `BackupSettingsRedactor`). Walks a Settings JSON tree and masks any value
/// whose key is credential-bearing (apiKey/password/secret/token/...), plus
/// header entries whose name is a credential header (Authorization/x-api-key/...).
///
/// Used on the iOS persist path (`IOSSharedSettingsStore.restoreSnapshot`) so
/// credentials never reach UserDefaults plaintext. The in-memory `Settings`
/// keeps real credentials (the running app needs them); only the persisted form
/// is redacted. Real values live in a Keychain side-table (`IOSCredentialSideTable`)
/// and are rehydrated on load. (locked_decision: iOS-only; shared
/// ProviderSetting is NOT modified.)
///
/// P0.5 ships this as the slice template for all credential classes; P2 extends
/// coverage to search/TTS/MCP/assistant-header classes.
enum IOSCredentialRedactor {
    static let mask = "__MASKED_BY_AMBERAGENT_IOS__"

    /// Returns a redacted copy of a Settings JSON string. Credentials are
    /// replaced with `mask`; all other content is preserved.
    static func redact(_ jsonString: String) -> String {
        guard let data = jsonString.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []) else {
            return jsonString
        }
        let redacted = redactValue(object)
        guard let result = try? JSONSerialization.data(withJSONObject: redacted, options: []) else {
            return jsonString
        }
        return String(data: result, encoding: .utf8) ?? jsonString
    }

    // MARK: - tree walk

    private static func redactValue(_ value: Any) -> Any {
        if let dict = value as? [String: Any] {
            var out: [String: Any] = [:]
            for (key, val) in dict {
                let lowerKey = key.lowercased()
                if isSensitiveKey(key) {
                    out[key] = mask
                } else if lowerKey == "headers" || lowerKey == "customheaders" || lowerKey == "custombodies" {
                    // Header/body collections hold name/value (or key/value) pairs
                    // where the name can be a credential header (Authorization…).
                    out[key] = redactHeaderCollection(val)
                } else {
                    out[key] = redactValue(val)
                }
            }
            return out
        }
        if let array = value as? [Any] {
            return array.map { redactValue($0) }
        }
        return value
    }

    private static func redactHeaderCollection(_ value: Any) -> Any {
        if let array = value as? [Any] {
            return array.map { redactHeaderEntry($0) }
        }
        return redactValue(value)
    }

    private static func redactHeaderEntry(_ value: Any) -> Any {
        if let dict = value as? [String: Any] {
            let name = (dict["first"] as? String)
                ?? (dict["name"] as? String)
                ?? (dict["key"] as? String)
            if isSensitiveHeaderName(name) {
                var out = dict
                for key in ["second", "value"] where out[key] != nil {
                    out[key] = mask
                }
                return out
            }
        }
        return redactValue(value)
    }

    /// Returns the masked sentinel if `headerName` is a credential-bearing
    /// header (Authorization/x-api-key/…), else the value unchanged. Used by the
    /// MCP config store to redact per-server header dicts before persisting.
    static func redactHeaderValue(headerName: String, value: String) -> String {
        isSensitiveHeaderName(headerName) ? mask : value
    }

    /// True if `headerName` is a credential-bearing header. Public so stores can
    /// decide whether to route a header value through the Keychain side-table.
    static func isHeaderSensitive(_ headerName: String) -> Bool {
        isSensitiveHeaderName(headerName)
    }

    // MARK: - key classification (parity with Android BackupSettingsRedactor)

    private static func isSensitiveKey(_ rawKey: String) -> Bool {
        let normalized = rawKey.lowercased()
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: "_", with: "")
        return [
            "apikey", "password", "secret", "secretaccesskey",
            "clientsecret", "accesstoken", "refreshtoken", "token",
            "bearertoken", "authorization", "privatekey",
        ].contains(normalized)
    }

    private static func isSensitiveHeaderName(_ rawName: String?) -> Bool {
        let normalized = (rawName ?? "").lowercased()
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: "_", with: "")
        return [
            "authorization", "proxyauthorization", "xapikey",
            "apikey", "xauthkey", "xauthtoken", "cookie", "setcookie",
        ].contains(normalized)
    }
}
