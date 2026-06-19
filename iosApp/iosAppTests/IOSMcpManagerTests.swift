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

    func testCallToolSyncsBeforeCallingWhenChatDidNotOpenMcpPage() async throws {
        let fakeClient = FakeIOSMcpClient(tools: [IOSMcpTool(name: "echo", description: nil)], callOutput: "hello")
        let manager = IOSMcpManager(
            serverProvider: { [.streamableHTTP(name: "docs", url: "https://example.com/mcp")] },
            clientFactory: { _ in fakeClient }
        )

        let output = try await manager.callTool(serverName: "docs", toolName: "echo", arguments: [:])

        XCTAssertEqual(output, "hello")
        XCTAssertTrue(fakeClient.didConnect)
        XCTAssertEqual(fakeClient.calledTools, ["echo"])
    }

    func testCallToolRejectsDisabledDiscoveredTool() async {
        let fakeClient = FakeIOSMcpClient(tools: [IOSMcpTool(name: "search", description: nil, enabled: false)])
        let manager = IOSMcpManager(
            serverProvider: { [.streamableHTTP(name: "docs", url: "https://example.com/mcp")] },
            clientFactory: { _ in fakeClient }
        )
        await manager.syncAll()

        do {
            _ = try await manager.callTool(serverName: "docs", toolName: "search", arguments: [:])
            XCTFail("Disabled MCP tools must not be callable.")
        } catch let error as IOSMcpClientError {
            XCTAssertEqual(error.localizedDescription, IOSMcpClientError.toolDisabled(server: "docs", tool: "search").localizedDescription)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testDisabledGateDoesNotConnectServers() async {
        let fakeClient = FakeIOSMcpClient(tools: [IOSMcpTool(name: "search", description: nil)])
        let manager = IOSMcpManager(
            serverProvider: { [.streamableHTTP(name: "docs", url: "https://example.com/mcp")] },
            isEnabled: { false },
            clientFactory: { _ in fakeClient }
        )

        await manager.syncAll()

        XCTAssertTrue(manager.servers.isEmpty)
        XCTAssertTrue(manager.tools.isEmpty)
        XCTAssertTrue(manager.statusByServer.isEmpty)
        XCTAssertFalse(fakeClient.didConnect)
    }

    func testSyncAllDisconnectsServerWhenConfigTurnsOff() async {
        var serverEnabled = true
        let fakeClient = FakeIOSMcpClient(tools: [IOSMcpTool(name: "search", description: nil)], callOutput: "hello")
        let manager = IOSMcpManager(
            serverProvider: { [.streamableHTTP(name: "docs", url: "https://example.com/mcp", enabled: serverEnabled)] },
            clientFactory: { _ in fakeClient }
        )
        await manager.syncAll()

        XCTAssertTrue(fakeClient.didConnect)
        XCTAssertEqual(manager.statusByServer["docs"], .connected)
        XCTAssertEqual(manager.tools, [IOSMcpDiscoveredTool(serverName: "docs", tool: IOSMcpTool(name: "search", description: nil))])

        serverEnabled = false
        await manager.syncAll()

        XCTAssertTrue(fakeClient.didDisconnect)
        XCTAssertEqual(manager.statusByServer["docs"], .idle)
        XCTAssertTrue(manager.tools.isEmpty)
        do {
            _ = try await manager.callTool(serverName: "docs", toolName: "search", arguments: [:])
            XCTFail("Disabled MCP servers must not accept tool calls.")
        } catch {
            XCTAssertTrue(error is IOSMcpClientError)
        }
    }
}

private final class FakeIOSMcpClient: IOSMcpClienting {
    let tools: [IOSMcpTool]
    let callOutput: String
    var calledTools: [String] = []
    var didConnect = false
    var didDisconnect = false

    init(tools: [IOSMcpTool], callOutput: String = "") {
        self.tools = tools
        self.callOutput = callOutput
    }

    func connect(config: IOSMcpServerConfig) async throws -> Bool {
        didConnect = true
        return true
    }

    func listTools() async throws -> [IOSMcpTool] { tools }

    func callTool(name: String, arguments: [String: Any]) async throws -> String {
        calledTools.append(name)
        return callOutput
    }

    func disconnect() {
        didDisconnect = true
    }
}
