import Foundation
import Security

private struct SettingsData: Codable {
    var baseUrl: String
    var apiKey: String  // kept for Codable compatibility; always empty now (real key lives in Keychain)
    var modelId: String
    var terminalDefaultRuntime: IOSTerminalRuntimeKind?
    var terminalExperimentalRuntimesEnabled: Bool?
    var sshProfiles: [IOSSSHProfile]?
    var sshDefaultProfileId: String?
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
    var terminalDefaultRuntime: IOSTerminalRuntimeKind {
        didSet { save() }
    }
    var terminalExperimentalRuntimesEnabled: Bool {
        didSet { save() }
    }
    var sshProfiles: [IOSSSHProfile] {
        didSet {
            if let defaultId = sshDefaultProfileId,
               !sshProfiles.contains(where: { $0.id == defaultId }) {
                sshDefaultProfileId = sshProfiles.first?.id
            }
            save()
        }
    }
    var sshDefaultProfileId: String? {
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
            terminalDefaultRuntime = IOSTerminalBuildPolicy.normalizedDefaultRuntime(
                decoded.terminalDefaultRuntime ?? .remoteSSH
            )
            terminalExperimentalRuntimesEnabled = IOSTerminalBuildPolicy.experimentalRuntimesLinked &&
                (decoded.terminalExperimentalRuntimesEnabled ?? false)
            sshProfiles = decoded.sshProfiles ?? []
            if let decodedDefault = decoded.sshDefaultProfileId,
               sshProfiles.contains(where: { $0.id == decodedDefault }) {
                sshDefaultProfileId = decodedDefault
            } else {
                sshDefaultProfileId = sshProfiles.first?.id
            }
        } else {
            baseUrl = "https://api.openai.com/v1"
            modelId = "gpt-4o"
            terminalDefaultRuntime = .remoteSSH
            terminalExperimentalRuntimesEnabled = false
            sshProfiles = []
            sshDefaultProfileId = nil
        }
        // Load the API key from Keychain (empty string if not found).
        apiKey = Self.loadApiKey() ?? ""
    }

    private func save() {
        let data = SettingsData(
            baseUrl: baseUrl,
            apiKey: "",  // never persist the key to UserDefaults
            modelId: modelId,
            terminalDefaultRuntime: terminalDefaultRuntime,
            terminalExperimentalRuntimesEnabled: terminalExperimentalRuntimesEnabled,
            sshProfiles: sshProfiles,
            sshDefaultProfileId: sshDefaultProfileId
        )
        if let encoded = try? JSONEncoder().encode(data) {
            UserDefaults.standard.set(encoded, forKey: Self.storageKey)
        }
    }

    private func saveApiKey() {
        Self.saveApiKey(apiKey)
    }

    var defaultSSHProfile: IOSSSHProfile? {
        guard let sshDefaultProfileId else { return nil }
        return sshProfiles.first { $0.id == sshDefaultProfileId }
    }

    func upsertSSHProfile(_ profile: IOSSSHProfile, password: String?) throws {
        let validated = try profile.validated()
        if let password {
            try IOSSSHSecretStore.savePassword(password, profileId: validated.id)
        }
        if let index = sshProfiles.firstIndex(where: { $0.id == validated.id }) {
            sshProfiles[index] = validated
        } else {
            sshProfiles.append(validated)
        }
        if sshDefaultProfileId == nil {
            sshDefaultProfileId = validated.id
        }
    }

    func deleteSSHProfile(id: String) {
        sshProfiles.removeAll { $0.id == id }
        IOSSSHSecretStore.deletePassword(profileId: id)
        if sshDefaultProfileId == id {
            sshDefaultProfileId = sshProfiles.first?.id
        }
    }

    func trustHost(profileId: String, fingerprint: String) throws {
        guard let index = sshProfiles.firstIndex(where: { $0.id == profileId }) else {
            throw IOSSSHError.invalidProfile("SSH profile was not found.")
        }
        var profile = sshProfiles[index]
        profile.knownHostSHA256 = fingerprint.trimmingCharacters(in: .whitespacesAndNewlines)
        profile.knownHostHost = profile.host.trimmingCharacters(in: .whitespacesAndNewlines)
        profile.knownHostPort = profile.port
        sshProfiles[index] = try profile.validated()
    }

    func passwordForSSHProfile(id: String) -> String? {
        IOSSSHSecretStore.loadPassword(profileId: id)
    }

    func clearSSHPassword(profileId: String) {
        IOSSSHSecretStore.deletePassword(profileId: profileId)
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
