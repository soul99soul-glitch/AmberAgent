import Foundation
import Observation
import Shared

@MainActor
@Observable
final class IOSMcpConfigStore {
    private let userDefaults: UserDefaults
    private let storageKey = "app.amber.ios.mcpServers"

    /// [Slice 3] Shared instance so ChatViewModel can build an IOSMcpManager
    /// that reads the same persisted MCP server config as McpServersView
    /// (both read the same UserDefaults key).
    static let shared = IOSMcpConfigStore()

    private(set) var servers: [IOSMcpServerConfig] = []

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
        self.servers = Self.loadServers(from: userDefaults, key: storageKey)
    }

    func add(_ server: IOSMcpServerConfig) {
        servers.removeAll { $0.name == server.name }
        servers.append(server)
        persist()
    }

    @discardableResult
    func importServers(json: String) -> Int {
        let parsed = McpImportParserKt.parseMcpServersFromJson(json: json).compactMap(IOSMcpServerConfig.init)
        for server in parsed {
            add(server)
        }
        return parsed.count
    }

    func remove(named name: String) {
        servers.removeAll { $0.name == name }
        persist()
    }

    private func persist() {
        let stored = servers.map(IOSMcpStoredServer.init)
        guard let data = try? JSONEncoder().encode(stored) else { return }
        userDefaults.set(data, forKey: storageKey)
    }

    private static func loadServers(from defaults: UserDefaults, key: String) -> [IOSMcpServerConfig] {
        guard let data = defaults.data(forKey: key),
              let stored = try? JSONDecoder().decode([IOSMcpStoredServer].self, from: data) else {
            return []
        }
        return stored.map(\.config)
    }
}
