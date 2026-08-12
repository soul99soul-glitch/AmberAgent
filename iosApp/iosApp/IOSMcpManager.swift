import Foundation
import Observation
import Shared

struct IOSMcpDiscoveredTool: Equatable, Identifiable {
    let serverName: String
    let tool: IOSMcpTool

    var id: String { "\(serverName)::\(tool.name)" }
}

@MainActor
@Observable
final class IOSMcpManager {
    private let serverProvider: () -> [IOSMcpServerConfig]
    private let clientFactory: (IOSMcpServerConfig) -> IOSMcpClienting
    private let isEnabled: () -> Bool
    private let discoveredToolSink: (String, [IOSMcpTool]) -> [IOSMcpTool]?
    private var clientsByServer: [String: IOSMcpClienting] = [:]

    private(set) var servers: [IOSMcpServerConfig] = []
    private(set) var tools: [IOSMcpDiscoveredTool] = []
    private(set) var statusByServer: [String: IOSMcpConnectionStatus] = [:]

    init(
        serverProvider: @escaping () -> [IOSMcpServerConfig],
        isEnabled: @escaping () -> Bool = { true },
        discoveredToolSink: @escaping (String, [IOSMcpTool]) -> [IOSMcpTool]? = { _, _ in nil },
        clientFactory: @escaping (IOSMcpServerConfig) -> IOSMcpClienting = { _ in IOSMcpClient() }
    ) {
        self.serverProvider = serverProvider
        self.isEnabled = isEnabled
        self.discoveredToolSink = discoveredToolSink
        self.clientFactory = clientFactory
    }

    convenience init(
        sharedSettings: IOSSharedSettingsStore,
        configStore: IOSMcpConfigStore,
        isNetworkAllowed: @escaping () -> Bool = { true }
    ) {
        self.init(serverProvider: {
            sharedSettings.snapshot.mcpServers.compactMap(IOSMcpServerConfig.init) + configStore.servers
        }, isEnabled: {
            sharedSettings.isCapabilityGateEnabled(.mcp) && isNetworkAllowed()
        }, discoveredToolSink: { serverName, tools in
            configStore.mergeDiscoveredTools(named: serverName, tools: tools)
        })
    }

    func refreshServers() {
        servers = serverProvider()
        for server in servers where statusByServer[server.name] == nil {
            statusByServer[server.name] = .idle
        }
    }

    func syncAll() async {
        guard isEnabled() else {
            disconnectAll()
            servers = []
            tools = []
            statusByServer = [:]
            return
        }
        refreshServers()
        tools = []
        let currentServerNames = Set(servers.map(\.name))
        for staleServerName in Array(clientsByServer.keys) where !currentServerNames.contains(staleServerName) {
            clientsByServer[staleServerName]?.disconnect()
            clientsByServer.removeValue(forKey: staleServerName)
            statusByServer.removeValue(forKey: staleServerName)
        }

        for server in servers {
            await sync(server: server)
        }
    }

    /// Refreshes exactly one configured server. Management-tool tests must not
    /// contact every enabled MCP server as a side effect.
    func sync(serverName: String) async {
        guard isEnabled() else {
            disconnectAll()
            servers = []
            tools = []
            statusByServer = [:]
            return
        }
        refreshServers()
        guard let server = servers.first(where: { $0.name == serverName }) else { return }
        tools.removeAll { $0.serverName == serverName }
        await sync(server: server)
    }

    func callTool(serverName: String, toolName: String, arguments: [String: Any]) async throws -> String {
        guard isEnabled() else {
            throw IOSMcpClientError.invalidResponse
        }
        if servers.isEmpty || clientsByServer[serverName] == nil {
            await syncAll()
        }
        guard let server = servers.first(where: { $0.name == serverName }) else {
            throw IOSMcpClientError.serverNotFound(serverName)
        }
        guard server.enabled else {
            throw IOSMcpClientError.serverDisabled(serverName)
        }
        guard let knownTool = server.tools.first(where: { $0.name == toolName }) else {
            throw IOSMcpClientError.toolNotFound(server: serverName, tool: toolName)
        }
        guard knownTool.enabled else {
            throw IOSMcpClientError.toolDisabled(server: serverName, tool: toolName)
        }
        guard let client = clientsByServer[serverName] else {
            throw IOSMcpClientError.notConnected(serverName)
        }
        return try await client.callTool(name: toolName, arguments: arguments)
    }

    func refreshFromCurrentSettings() {
        refreshServers()
        tools = servers.flatMap { server in
            server.tools.map { IOSMcpDiscoveredTool(serverName: server.name, tool: $0) }
        }
    }

    func disconnectAll() {
        clientsByServer.values.forEach { $0.disconnect() }
        clientsByServer.removeAll()
        for server in servers {
            statusByServer[server.name] = .idle
        }
        reconnectAttempts.removeAll()
    }

    // MARK: - Auto-reconnect (Android McpManager parity)
    //
    // Android McpManager auto-reconnects with exponential backoff (up to
    // MAX_RECONNECT_ATTEMPTS=5). iOS had no reconnect — a dropped connection
    // stayed in `.error` until the user manually re-synced. This retries failed
    // servers when they're next needed (callTool) and via syncAll.

    private static let maxReconnectAttempts = 5
    private static let baseReconnectDelaySeconds: UInt64 = 2
    private var reconnectAttempts: [String: Int] = [:]
    private var lastReconnectAttemptByServer: [String: Date] = [:]

    /// Attempts to reconnect servers currently in `.error`. Returns the names
    /// that were retried. Uses exponential backoff (2^n seconds) capped at
    /// maxReconnectAttempts per server; a successful connection resets the
    /// counter. Safe to call repeatedly — it respects the backoff window.
    @discardableResult
    func reconnectFailedServers() async -> [String] {
        guard isEnabled() else { return [] }
        let failed = statusByServer.filter { _, status in
            if case .error = status { return true }
            return false
        }.map(\.key)
        guard !failed.isEmpty else { return [] }

        let now = Date()
        var retried: [String] = []
        for serverName in failed {
            let attempts = reconnectAttempts[serverName] ?? 0
            guard attempts < Self.maxReconnectAttempts else { continue }
            // Backoff: 2^n seconds since the last attempt. Skip if still within
            // the backoff window.
            let delay = Self.baseReconnectDelaySeconds * (1 << attempts)
            if let last = lastReconnectAttemptByServer[serverName],
               now.timeIntervalSince(last) < Double(delay) {
                continue
            }
            lastReconnectAttemptByServer[serverName] = now
            reconnectAttempts[serverName] = attempts + 1
            statusByServer[serverName] = .reconnecting

            guard let server = servers.first(where: { $0.name == serverName }), server.enabled else {
                statusByServer[serverName] = .error("server missing or disabled")
                continue
            }
            let client: IOSMcpClienting = clientsByServer[serverName] ?? clientFactory(server)
            clientsByServer[serverName] = client
            do {
                _ = try await client.connect(config: server)
                let listedTools = try await client.listTools()
                let merged = discoveredToolSink(server.name, listedTools) ?? Self.mergeDiscoveredTools(
                    discovered: listedTools,
                    existing: server.tools
                )
                if let index = servers.firstIndex(where: { $0.name == server.name }) {
                    servers[index] = server.withTools(merged)
                }
                tools.removeAll { $0.serverName == server.name }
                tools.append(contentsOf: merged.map { IOSMcpDiscoveredTool(serverName: server.name, tool: $0) })
                statusByServer[server.name] = .connected
                reconnectAttempts[server.name] = nil
                lastReconnectAttemptByServer[server.name] = nil
                retried.append(serverName)
            } catch {
                statusByServer[server.name] = .error(IOSWebMountRedactor.redactedText(error.localizedDescription))
            }
        }
        return retried
    }

    private static func mergeDiscoveredTools(discovered: [IOSMcpTool], existing: [IOSMcpTool]) -> [IOSMcpTool] {
        let existingByName = Dictionary(uniqueKeysWithValues: existing.map { ($0.name, $0) })
        return discovered.map { tool in
            guard let old = existingByName[tool.name] else { return tool }
            return IOSMcpTool(
                name: tool.name,
                description: tool.description ?? old.description,
                enabled: old.enabled,
                inputSchema: tool.inputSchema ?? old.inputSchema
            )
        }
    }

    private func sync(server: IOSMcpServerConfig) async {
        guard server.enabled else {
            clientsByServer[server.name]?.disconnect()
            clientsByServer.removeValue(forKey: server.name)
            statusByServer[server.name] = .idle
            return
        }

        statusByServer[server.name] = .connecting
        let client = clientsByServer[server.name] ?? clientFactory(server)
        clientsByServer[server.name] = client

        do {
            _ = try await client.connect(config: server)
            let listedTools = try await client.listTools()
            let mergedTools = discoveredToolSink(server.name, listedTools) ?? Self.mergeDiscoveredTools(
                discovered: listedTools,
                existing: server.tools
            )
            if let index = servers.firstIndex(where: { $0.name == server.name }) {
                servers[index] = server.withTools(mergedTools)
            }
            tools.append(contentsOf: mergedTools.map { IOSMcpDiscoveredTool(serverName: server.name, tool: $0) })
            statusByServer[server.name] = .connected
        } catch {
            statusByServer[server.name] = .error(IOSWebMountRedactor.redactedText(error.localizedDescription))
        }
    }

    #if DEBUG
    /// Test accessor: clears the reconnect backoff window so a test can retry
    /// immediately without waiting for the exponential delay.
    func clearReconnectBackoffForTesting(serverName: String) {
        lastReconnectAttemptByServer[serverName] = nil
        reconnectAttempts[serverName] = nil
    }
    #endif
}
