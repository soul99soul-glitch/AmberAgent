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

    func upsert(_ server: IOSMcpServerConfig, replacing originalName: String? = nil) {
        servers.removeAll { existing in
            existing.name == server.name || (originalName != nil && existing.name == originalName)
        }
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

    @discardableResult
    func setEnabled(named name: String, enabled: Bool) -> Bool {
        guard let index = servers.firstIndex(where: { $0.name == name }) else { return false }
        servers[index] = servers[index].withEnabled(enabled)
        persist()
        return true
    }

    @discardableResult
    func mergeDiscoveredTools(named name: String, tools discoveredTools: [IOSMcpTool]) -> [IOSMcpTool]? {
        guard let index = servers.firstIndex(where: { $0.name == name }) else { return nil }
        let existingByName = Dictionary(uniqueKeysWithValues: servers[index].tools.map { ($0.name, $0) })
        let merged = discoveredTools.map { discovered in
            guard let existing = existingByName[discovered.name] else { return discovered }
            return IOSMcpTool(
                name: discovered.name,
                description: discovered.description ?? existing.description,
                enabled: existing.enabled
            )
        }
        servers[index] = servers[index].withTools(merged)
        persist()
        return merged
    }

    @discardableResult
    func setToolEnabled(serverName: String, toolName: String, enabled: Bool) -> Bool {
        guard let index = servers.firstIndex(where: { $0.name == serverName }) else { return false }
        var tools = servers[index].tools
        guard let toolIndex = tools.firstIndex(where: { $0.name == toolName }) else { return false }
        tools[toolIndex] = tools[toolIndex].withEnabled(enabled)
        servers[index] = servers[index].withTools(tools)
        persist()
        return true
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
