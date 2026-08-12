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
        let replaced = servers.filter { $0.name == server.name }
        servers.removeAll { $0.name == server.name }
        replaced.forEach(Self.deleteCredentialRefs)
        servers.append(server)
        persist()
    }

    func addBatch(_ incoming: [IOSMcpServerConfig]) throws {
        let existing = Set(servers.map(\.name))
        var seen = Set<String>()
        for server in incoming {
            if existing.contains(server.name) || !seen.insert(server.name).inserted {
                throw IOSMcpImportError.liveNameConflict(server.name)
            }
        }
        servers.append(contentsOf: incoming)
        persist()
    }

    func upsert(_ server: IOSMcpServerConfig, replacing originalName: String? = nil) {
        let replaced = servers.filter { existing in
            existing.name == server.name || (originalName != nil && existing.name == originalName)
        }
        servers.removeAll { existing in
            existing.name == server.name || (originalName != nil && existing.name == originalName)
        }
        replaced.forEach(Self.deleteCredentialRefs)
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
        let removed = servers.filter { $0.name == name }
        servers.removeAll { $0.name == name }
        removed.forEach(Self.deleteCredentialRefs)
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
                enabled: existing.enabled,
                inputSchema: discovered.inputSchema ?? existing.inputSchema
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
        // Scheme B: store credential header values in the Keychain side-table,
        // THEN redact them before persisting to UserDefaults. The in-memory
        // `servers` keep real headers (the running MCP client needs them); the
        // persisted form is credential-free. On load, redacted values are
        // rehydrated from the side-table (see loadServers).
        for server in servers {
            for (name, value) in server.headers where !value.isEmpty {
                if IOSCredentialRedactor.isHeaderSensitive(name) {
                    IOSCredentialSideTable.store(key: IOSCredentialSideTable.mcpHeader(serverName: server.name, headerName: name), value: value)
                }
            }
        }
        let stored = servers.map { server -> IOSMcpStoredServer in
            var entry = IOSMcpStoredServer(server)
            entry.redactSensitiveHeaders()
            return entry
        }
        guard let data = try? JSONEncoder().encode(stored) else { return }
        userDefaults.set(data, forKey: storageKey)
    }

    private static func loadServers(from defaults: UserDefaults, key: String) -> [IOSMcpServerConfig] {
        guard let data = defaults.data(forKey: key),
              let stored = try? JSONDecoder().decode([IOSMcpStoredServer].self, from: data) else {
            return []
        }
        // Scheme B: rehydrate credential header values from the Keychain
        // side-table so the reloaded MCP client can authenticate.
        return stored.map { storedServer in
            var config = storedServer.config
            var headers = config.headers
            for (name, value) in headers where value == IOSCredentialRedactor.mask {
                if let real = IOSCredentialSideTable.load(key: IOSCredentialSideTable.mcpHeader(serverName: storedServer.name, headerName: name)) {
                    headers[name] = real
                }
            }
            // Rebuild the config with rehydrated headers.
            switch config {
            case .streamableHTTP(let n, let u, _, let e, let t):
                config = .streamableHTTP(name: n, url: u, headers: headers, enabled: e, tools: t)
            case .sse(let n, let u, _, let e, let t):
                config = .sse(name: n, url: u, headers: headers, enabled: e, tools: t)
            }
            return config
        }
    }

    private static func deleteCredentialRefs(for server: IOSMcpServerConfig) {
        for name in server.headers.keys where IOSCredentialRedactor.isHeaderSensitive(name) {
            IOSCredentialSideTable.delete(
                key: IOSCredentialSideTable.mcpHeader(serverName: server.name, headerName: name)
            )
        }
    }
}
