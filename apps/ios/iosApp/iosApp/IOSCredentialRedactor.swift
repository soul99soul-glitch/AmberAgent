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
    static func redact(
        _ jsonString: String,
        storeCredential: ((String, String) -> Void)? = nil
    ) -> String {
        guard let data = jsonString.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []) else {
            return jsonString
        }
        let redacted = redactValue(object, path: "root", storeCredential: storeCredential)
        guard let result = try? JSONSerialization.data(withJSONObject: redacted, options: []) else {
            return jsonString
        }
        return String(data: result, encoding: .utf8) ?? jsonString
    }

    /// Replaces mask sentinels with their Keychain value. Missing values become
    /// empty strings so the persistence placeholder can never enter runtime.
    static func rehydrate(
        _ jsonString: String,
        loadCredential: (String) -> String?
    ) -> String {
        guard let data = jsonString.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []) else {
            return jsonString
        }
        let hydrated = rehydrateValue(object, path: "root", loadCredential: loadCredential)
        guard let result = try? JSONSerialization.data(withJSONObject: hydrated, options: []) else {
            return jsonString
        }
        return String(data: result, encoding: .utf8) ?? jsonString
    }

    /// Returns the exact side-table paths represented by credential-bearing
    /// values in this Settings JSON. Stable item IDs/names are used instead of
    /// array offsets so deletion can remove only refs owned by the deleted item.
    static func activeCredentialPaths(in jsonString: String) -> Set<String> {
        guard let data = jsonString.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []) else {
            return []
        }
        var paths = Set<String>()
        collectCredentialPaths(object, path: "root", into: &paths)
        return paths
    }

    // MARK: - tree walk

    private static func redactValue(
        _ value: Any,
        path: String,
        storeCredential: ((String, String) -> Void)?
    ) -> Any {
        if let dict = value as? [String: Any] {
            var out: [String: Any] = [:]
            for (key, val) in dict {
                let lowerKey = key.lowercased()
                let childPath = "\(path).\(key)"
                if isSensitiveKey(key) {
                    out[key] = redactSensitiveValue(
                        val,
                        path: childPath,
                        storeCredential: storeCredential
                    )
                } else if lowerKey == "headers" || lowerKey == "customheaders" || lowerKey == "custombodies" {
                    // Header/body collections hold name/value (or key/value) pairs
                    // where the name can be a credential header (Authorization…).
                    out[key] = redactHeaderCollection(
                        val,
                        path: childPath,
                        storeCredential: storeCredential
                    )
                } else {
                    out[key] = redactValue(
                        val,
                        path: childPath,
                        storeCredential: storeCredential
                    )
                }
            }
            return out
        }
        if let array = value as? [Any] {
            return array.enumerated().map { index, item in
                redactValue(
                    item,
                    path: arrayItemPath(base: path, index: index, item: item),
                    storeCredential: storeCredential
                )
            }
        }
        return value
    }

    private static func redactHeaderCollection(
        _ value: Any,
        path: String,
        storeCredential: ((String, String) -> Void)?
    ) -> Any {
        if let array = value as? [Any] {
            return array.enumerated().map { index, item in
                redactHeaderEntry(
                    item,
                    path: arrayItemPath(base: path, index: index, item: item),
                    storeCredential: storeCredential
                )
            }
        }
        return redactValue(value, path: path, storeCredential: storeCredential)
    }

    private static func redactHeaderEntry(
        _ value: Any,
        path: String,
        storeCredential: ((String, String) -> Void)?
    ) -> Any {
        if let dict = value as? [String: Any] {
            let name = (dict["first"] as? String)
                ?? (dict["name"] as? String)
                ?? (dict["key"] as? String)
            if isSensitiveHeaderName(name) {
                var out = dict
                for key in ["second", "value"] where out[key] != nil {
                    out[key] = redactSensitiveValue(
                        out[key] as Any,
                        path: "\(path).\(key)",
                        storeCredential: storeCredential
                    )
                }
                return out
            }
        }
        return redactValue(value, path: path, storeCredential: storeCredential)
    }

    private static func redactSensitiveValue(
        _ value: Any,
        path: String,
        storeCredential: ((String, String) -> Void)?
    ) -> Any {
        guard let string = value as? String else { return mask }
        guard !string.isEmpty else { return string }
        guard string != mask else { return mask }
        storeCredential?(path, string)
        return mask
    }

    private static func rehydrateValue(
        _ value: Any,
        path: String,
        loadCredential: (String) -> String?
    ) -> Any {
        if let dict = value as? [String: Any] {
            var out: [String: Any] = [:]
            for (key, val) in dict {
                let lowerKey = key.lowercased()
                let childPath = "\(path).\(key)"
                if isSensitiveKey(key) {
                    out[key] = val as? String == mask ? (loadCredential(childPath) ?? "") : val
                } else if lowerKey == "headers" || lowerKey == "customheaders" || lowerKey == "custombodies" {
                    out[key] = rehydrateHeaderCollection(val, path: childPath, loadCredential: loadCredential)
                } else {
                    out[key] = rehydrateValue(val, path: childPath, loadCredential: loadCredential)
                }
            }
            return out
        }
        if let array = value as? [Any] {
            return array.enumerated().map { index, item in
                rehydrateValue(
                    item,
                    path: arrayItemPath(base: path, index: index, item: item),
                    loadCredential: loadCredential
                )
            }
        }
        return value
    }

    private static func rehydrateHeaderCollection(
        _ value: Any,
        path: String,
        loadCredential: (String) -> String?
    ) -> Any {
        guard let array = value as? [Any] else {
            return rehydrateValue(value, path: path, loadCredential: loadCredential)
        }
        return array.enumerated().map { index, item in
            let itemPath = arrayItemPath(base: path, index: index, item: item)
            guard let dict = item as? [String: Any] else {
                return rehydrateValue(item, path: itemPath, loadCredential: loadCredential)
            }
            let name = (dict["first"] as? String)
                ?? (dict["name"] as? String)
                ?? (dict["key"] as? String)
            guard isSensitiveHeaderName(name) else {
                return rehydrateValue(dict, path: itemPath, loadCredential: loadCredential)
            }
            var out = dict
            for key in ["second", "value"] where out[key] as? String == mask {
                out[key] = loadCredential("\(itemPath).\(key)") ?? ""
            }
            return out
        }
    }

    private static func arrayItemPath(base: String, index: Int, item: Any) -> String {
        guard let dict = item as? [String: Any] else { return "\(base)[\(index)]" }
        let identity = (dict["id"] as? String)
            ?? (dict["name"] as? String)
            ?? (dict["first"] as? String)
            ?? (dict["key"] as? String)
        // Include the index so two items sharing the same identity (e.g. two custom
        // headers both named "X-Api-Key") do NOT collapse onto one side-table path —
        // which would overwrite the first credential and cross-contaminate both
        // fields on rehydrate. JSONSerialization preserves array order, so the index
        // is stable across the redact→rehydrate round-trip on the same JSON.
        return "\(base)[\(identity ?? String(index))#\(index)]"
    }

    private static func collectCredentialPaths(_ value: Any, path: String, into paths: inout Set<String>) {
        if let dict = value as? [String: Any] {
            for (key, item) in dict {
                let lowerKey = key.lowercased()
                let childPath = "\(path).\(key)"
                if isSensitiveKey(key) {
                    if let string = item as? String, !string.isEmpty {
                        paths.insert(childPath)
                    }
                } else if lowerKey == "headers" || lowerKey == "customheaders" || lowerKey == "custombodies",
                          let array = item as? [Any] {
                    for (index, entry) in array.enumerated() {
                        let entryPath = arrayItemPath(base: childPath, index: index, item: entry)
                        guard let fields = entry as? [String: Any] else {
                            collectCredentialPaths(entry, path: entryPath, into: &paths)
                            continue
                        }
                        let name = (fields["first"] as? String)
                            ?? (fields["name"] as? String)
                            ?? (fields["key"] as? String)
                        if isSensitiveHeaderName(name) {
                            for valueKey in ["second", "value"] {
                                if let string = fields[valueKey] as? String, !string.isEmpty {
                                    paths.insert("\(entryPath).\(valueKey)")
                                }
                            }
                        } else {
                            collectCredentialPaths(fields, path: entryPath, into: &paths)
                        }
                    }
                } else {
                    collectCredentialPaths(item, path: childPath, into: &paths)
                }
            }
            return
        }
        if let array = value as? [Any] {
            for (index, item) in array.enumerated() {
                collectCredentialPaths(
                    item,
                    path: arrayItemPath(base: path, index: index, item: item),
                    into: &paths
                )
            }
        }
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
        guard !normalized.isEmpty else { return false }
        // Substring match over the same sensitive term set as isSensitiveKey, so
        // prefixed header/body names are covered too (X-Access-Token, Api-Token,
        // X-Secret), not just bare exact forms. Over-masking is harmless: a masked
        // value is stored in the Keychain side-table and rehydrated on load, so it
        // still round-trips — it is only kept out of plaintext persistence/backups.
        return Self.sensitiveHeaderMarkers.contains { normalized.contains($0) }
    }

    /// Substring markers for credential-bearing header/body names. Covers the old
    /// exact list (authorization/proxy-authorization, x-api-key/api-key, x-auth-key,
    /// x-auth-token via "token", cookie/set-cookie) plus the isSensitiveKey terms
    /// that were previously missing (token/secret/password/privatekey/credential).
    private static let sensitiveHeaderMarkers = [
        "authorization", "apikey", "xauthkey", "cookie",
        "password", "secret", "token", "privatekey", "credential",
    ]
}
