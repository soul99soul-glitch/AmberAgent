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
    private var clientsByServer: [String: IOSMcpClienting] = [:]

    private(set) var servers: [IOSMcpServerConfig] = []
    private(set) var tools: [IOSMcpDiscoveredTool] = []
    private(set) var statusByServer: [String: IOSMcpConnectionStatus] = [:]

    init(
        serverProvider: @escaping () -> [IOSMcpServerConfig],
        clientFactory: @escaping (IOSMcpServerConfig) -> IOSMcpClienting = { _ in IOSMcpClient() }
    ) {
        self.serverProvider = serverProvider
        self.clientFactory = clientFactory
    }

    convenience init(sharedSettings: IOSSharedSettingsStore, configStore: IOSMcpConfigStore) {
        self.init(serverProvider: {
            sharedSettings.snapshot.mcpServers.compactMap(IOSMcpServerConfig.init) + configStore.servers
        })
    }

    func refreshServers() {
        servers = serverProvider()
        for server in servers where statusByServer[server.name] == nil {
            statusByServer[server.name] = .idle
        }
    }

    func syncAll() async {
        refreshServers()
        tools = []

        for server in servers {
            guard server.enabled else {
                statusByServer[server.name] = .idle
                continue
            }

            statusByServer[server.name] = .connecting
            let client = clientsByServer[server.name] ?? clientFactory(server)
            clientsByServer[server.name] = client

            do {
                _ = try await client.connect(config: server)
                let listedTools = try await client.listTools()
                tools.append(contentsOf: listedTools.map { IOSMcpDiscoveredTool(serverName: server.name, tool: $0) })
                statusByServer[server.name] = .connected
            } catch {
                statusByServer[server.name] = .error(error.localizedDescription)
            }
        }
    }

    func callTool(serverName: String, toolName: String, arguments: [String: Any]) async throws -> String {
        guard let client = clientsByServer[serverName] else {
            throw IOSMcpClientError.invalidResponse
        }
        return try await client.callTool(name: toolName, arguments: arguments)
    }

    func disconnectAll() {
        clientsByServer.values.forEach { $0.disconnect() }
        clientsByServer.removeAll()
        for server in servers {
            statusByServer[server.name] = .idle
        }
    }
}
