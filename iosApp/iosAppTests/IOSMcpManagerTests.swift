import XCTest
@testable import iosApp

@MainActor
final class IOSMcpManagerTests: XCTestCase {
    func testSyncAllConnectsEnabledServersAndPublishesTools() async {
        let fakeClient = FakeIOSMcpClient(tools: [IOSMcpTool(name: "search", description: "Search docs")])
        let manager = IOSMcpManager(
            serverProvider: {
                [
                    .streamableHTTP(name: "docs", url: "https://example.com/mcp"),
                    .sse(name: "disabled", url: "https://example.com/sse", enabled: false)
                ]
            },
            clientFactory: { _ in fakeClient }
        )

        await manager.syncAll()

        XCTAssertEqual(manager.servers.count, 2)
        XCTAssertEqual(manager.tools, [IOSMcpDiscoveredTool(serverName: "docs", tool: IOSMcpTool(name: "search", description: "Search docs"))])
        XCTAssertEqual(manager.statusByServer["docs"], .connected)
        XCTAssertEqual(manager.statusByServer["disabled"], .idle)
    }

    func testCallToolRoutesToOwningServer() async throws {
        let fakeClient = FakeIOSMcpClient(tools: [IOSMcpTool(name: "echo", description: nil)], callOutput: "hello")
        let manager = IOSMcpManager(
            serverProvider: { [.streamableHTTP(name: "docs", url: "https://example.com/mcp")] },
            clientFactory: { _ in fakeClient }
        )
        await manager.syncAll()

        let output = try await manager.callTool(serverName: "docs", toolName: "echo", arguments: ["text": "hello"])

        XCTAssertEqual(output, "hello")
        XCTAssertEqual(fakeClient.calledTools, ["echo"])
    }
}

private final class FakeIOSMcpClient: IOSMcpClienting {
    let tools: [IOSMcpTool]
    let callOutput: String
    var calledTools: [String] = []

    init(tools: [IOSMcpTool], callOutput: String = "") {
        self.tools = tools
        self.callOutput = callOutput
    }

    func connect(config: IOSMcpServerConfig) async throws -> Bool { true }

    func listTools() async throws -> [IOSMcpTool] { tools }

    func callTool(name: String, arguments: [String: Any]) async throws -> String {
        calledTools.append(name)
        return callOutput
    }

    func disconnect() {}
}
