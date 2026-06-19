import Foundation
import Security

enum IOSSSHAuthMethod: String, Codable, CaseIterable, Identifiable, Sendable {
    case password

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .password: "Password"
        }
    }
}

struct IOSSSHProfile: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var name: String
    var host: String
    var port: Int
    var username: String
    var authMethod: IOSSSHAuthMethod
    var knownHostSHA256: String?
    var knownHostHost: String?
    var knownHostPort: Int?

    init(
        id: String = UUID().uuidString,
        name: String = "",
        host: String = "",
        port: Int = 22,
        username: String = "",
        authMethod: IOSSSHAuthMethod = .password,
        knownHostSHA256: String? = nil,
        knownHostHost: String? = nil,
        knownHostPort: Int? = nil
    ) {
        self.id = id
        self.name = name
        self.host = host
        self.port = port
        self.username = username
        self.authMethod = authMethod
        self.knownHostSHA256 = knownHostSHA256
        self.knownHostHost = knownHostHost
        self.knownHostPort = knownHostPort
    }

    var displayName: String {
        if !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return name
        }
        if !host.isEmpty {
            return "\(username)@\(host)"
        }
        return "New SSH Profile"
    }

    func validated() throws -> IOSSSHProfile {
        var copy = self
        copy.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.username = username.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.knownHostSHA256 = knownHostSHA256?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
        copy.knownHostHost = knownHostHost?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank

        guard !copy.host.isEmpty else { throw IOSSSHError.invalidProfile("Host is required.") }
        guard !copy.username.isEmpty else { throw IOSSSHError.invalidProfile("Username is required.") }
        guard (1...65535).contains(copy.port) else { throw IOSSSHError.invalidProfile("Port must be between 1 and 65535.") }
        let hasBoundKnownHostEndpoint = copy.knownHostHost != nil || copy.knownHostPort != nil
        if copy.knownHostSHA256 != nil,
            hasBoundKnownHostEndpoint,
            (copy.knownHostHost != copy.host || copy.knownHostPort != copy.port) {
            copy.knownHostSHA256 = nil
            copy.knownHostHost = nil
            copy.knownHostPort = nil
        }
        return copy
    }
}

enum IOSSSHTrustState: Equatable, Sendable {
    case trusted
    case needsTrust(fingerprint: String)
    case mismatch(expected: String, actual: String)
}

struct IOSSSHConnectionProbeResult: Equatable, Sendable {
    let fingerprint: String
    let trustState: IOSSSHTrustState
}

struct IOSSSHCommandResult: Equatable, Sendable {
    let output: String
    let exitCode: Int?
}

enum IOSSSHProbePolicy {
    static let passwordPlaceholder = "__amber_probe_unused__"
    static let abortsAfterHostKey = true

    static func passwordOffer(realPassword: String) -> String {
        passwordPlaceholder
    }
}

enum IOSSSHError: Error, LocalizedError, Equatable, Sendable {
    case invalidProfile(String)
    case missingPassword
    case noDefaultProfile
    case hostKeyNotTrusted(String)
    case hostKeyMismatch(expected: String, actual: String)
    case backendUnavailable
    case commandTimedOut
    case commandCancelled
    case commandFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidProfile(let message): message
        case .missingPassword: "SSH password is required for this profile."
        case .noDefaultProfile: "Create and select a default SSH profile before running remote SSH commands."
        case .hostKeyNotTrusted(let fingerprint): "Host fingerprint must be trusted first: \(fingerprint)"
        case .hostKeyMismatch(let expected, let actual): "Host fingerprint mismatch. Expected \(expected), got \(actual)."
        case .backendUnavailable: "SwiftNIO SSH is not linked in this build."
        case .commandTimedOut: "SSH command timed out."
        case .commandCancelled: "SSH command was cancelled."
        case .commandFailed(let message): message
        }
    }
}

enum IOSSSHSecretStore {
    private static let service = "app.amber.agent.ssh"

    static func passwordAccount(profileId: String) -> String {
        "ssh-password:\(profileId)"
    }

    static func savePassword(_ password: String, profileId: String) throws {
        let account = passwordAccount(profileId: profileId)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecAttrService as String: service,
        ]
        SecItemDelete(query as CFDictionary)
        guard !password.isEmpty else { return }
        var attributes = query
        attributes[kSecValueData as String] = Data(password.utf8)
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw IOSSSHError.commandFailed("Failed to save SSH password: \(status)")
        }
    }

    static func loadPassword(profileId: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: passwordAccount(profileId: profileId),
            kSecAttrService as String: service,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func deletePassword(profileId: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: passwordAccount(profileId: profileId),
            kSecAttrService as String: service,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

private extension String {
    var nilIfBlank: String? {
        isEmpty ? nil : self
    }
}
