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

    convenience init(sharedSettings: IOSSharedSettingsStore, configStore: IOSMcpConfigStore) {
        self.init(serverProvider: {
            sharedSettings.snapshot.mcpServers.compactMap(IOSMcpServerConfig.init) + configStore.servers
        }, isEnabled: {
            sharedSettings.isCapabilityGateEnabled(.mcp)
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
            guard server.enabled else {
                clientsByServer[server.name]?.disconnect()
                clientsByServer.removeValue(forKey: server.name)
                statusByServer[server.name] = .idle
                continue
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
                statusByServer[server.name] = .error(error.localizedDescription)
            }
        }
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
    }

    private static func mergeDiscoveredTools(discovered: [IOSMcpTool], existing: [IOSMcpTool]) -> [IOSMcpTool] {
        let existingByName = Dictionary(uniqueKeysWithValues: existing.map { ($0.name, $0) })
        return discovered.map { tool in
            guard let old = existingByName[tool.name] else { return tool }
            return IOSMcpTool(name: tool.name, description: tool.description ?? old.description, enabled: old.enabled)
        }
    }
}
