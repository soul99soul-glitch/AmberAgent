import Foundation
import Shared

private struct SettingsData: Codable {
    var baseUrl: String
    var apiKey: String
    var modelId: String
}

@Observable
final class SettingsStore {

    var baseUrl: String {
        didSet { save() }
    }
    var apiKey: String {
        didSet { save() }
    }
    var modelId: String {
        didSet { save() }
    }

    private static let storageKey = "app.amber.ios.settings"

    init() {
        let defaults = UserDefaults.standard
        if let data = defaults.data(forKey: Self.storageKey),
           let decoded = try? JSONDecoder().decode(SettingsData.self, from: data) {
            baseUrl = decoded.baseUrl
            apiKey = decoded.apiKey
            modelId = decoded.modelId
        } else {
            baseUrl = "https://api.openai.com/v1"
            apiKey = ""
            modelId = "gpt-4o"
        }
    }

    private func save() {
        let data = SettingsData(
            baseUrl: baseUrl,
            apiKey: apiKey,
            modelId: modelId
        )
        if let encoded = try? JSONEncoder().encode(data) {
            UserDefaults.standard.set(encoded, forKey: Self.storageKey)
        }
    }
}
