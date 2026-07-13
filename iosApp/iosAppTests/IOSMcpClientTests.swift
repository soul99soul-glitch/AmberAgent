import XCTest
@testable import iosApp

@MainActor
final class IOSMcpClientTests: XCTestCase {
    func testConnectSendsInitializeRequestAndMarksConnected() async throws {
        let transport = FakeMcpHTTPTransport(responses: [
            ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]]
        ])
        let client = IOSMcpClient(transport: transport)

        let connected = try await client.connect(config: .streamableHTTP(name: "docs", url: "https://example.com/mcp"))

        XCTAssertTrue(connected)
        XCTAssertEqual(transport.sentMethods, ["initialize", "notifications/initialized"])
        XCTAssertEqual(client.status, .connected)
    }

    func testConnectReusesExistingConnectionForSameConfig() async throws {
        let transport = FakeMcpHTTPTransport(responses: [
            ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]]
        ])
        let client = IOSMcpClient(transport: transport)
        let config = IOSMcpServerConfig.sse(name: "docs", url: "https://example.com/sse")

        _ = try await client.connect(config: config)
        _ = try await client.connect(config: config)

        XCTAssertEqual(transport.sentMethods, ["initialize", "notifications/initialized"])
        XCTAssertTrue(transport.disconnectedServers.isEmpty)
    }

    func testConnectDisconnectsPreviousConfigWhenServerChanges() async throws {
        let transport = FakeMcpHTTPTransport(responses: [
            ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]],
            ["jsonrpc": "2.0", "id": 2, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]]
        ])
        let client = IOSMcpClient(transport: transport)

        _ = try await client.connect(config: .sse(name: "docs", url: "https://example.com/sse"))
        _ = try await client.connect(config: .sse(name: "docs", url: "https://example.com/changed-sse"))

        XCTAssertEqual(transport.sentMethods, [
            "initialize",
            "notifications/initialized",
            "initialize",
            "notifications/initialized"
        ])
        XCTAssertEqual(transport.disconnectedServers, ["docs"])
    }

    func testListToolsMapsMcpToolResult() async throws {
        let transport = FakeMcpHTTPTransport(responses: [
            ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]],
            ["jsonrpc": "2.0", "id": 2, "result": ["tools": [["name": "search", "description": "Search docs"]]]]
        ])
        let client = IOSMcpClient(transport: transport)
        _ = try await client.connect(config: .streamableHTTP(name: "docs", url: "https://example.com/mcp"))

        let tools = try await client.listTools()

        XCTAssertEqual(transport.sentMethods, ["initialize", "notifications/initialized", "tools/list"])
        XCTAssertEqual(tools, [IOSMcpTool(name: "search", description: "Search docs")])
    }

    func testCallToolReturnsTextContent() async throws {
        let transport = FakeMcpHTTPTransport(responses: [
            ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]],
            ["jsonrpc": "2.0", "id": 2, "result": ["content": [["type": "text", "text": "hello"]]]]
        ])
        let client = IOSMcpClient(transport: transport)
        _ = try await client.connect(config: .streamableHTTP(name: "docs", url: "https://example.com/mcp"))

        let output = try await client.callTool(name: "echo", arguments: ["text": "hello"])

        XCTAssertEqual(transport.sentMethods, ["initialize", "notifications/initialized", "tools/call"])
        XCTAssertEqual(output, "hello")
    }

    func testCallToolSerializesNonTextContent() async throws {
        let transport = FakeMcpHTTPTransport(responses: [
            ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]],
            ["jsonrpc": "2.0", "id": 2, "result": ["content": [["type": "image", "mimeType": "image/png"]]]]
        ])
        let client = IOSMcpClient(transport: transport)
        _ = try await client.connect(config: .streamableHTTP(name: "docs", url: "https://example.com/mcp"))

        let output = try await client.callTool(name: "image", arguments: [:])

        XCTAssertTrue(output.contains(#""type":"image""#))
    }

    func testCallToolTimesOutWhenTransportNeverProducesTerminalResponse() async throws {
        let transport = FakeMcpHTTPTransport(
            responses: [
                ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]]
            ],
            hangingMethods: ["tools/call"]
        )
        let client = IOSMcpClient(transport: transport, requestTimeoutSeconds: 0.01)
        _ = try await client.connect(config: .streamableHTTP(name: "docs", url: "https://example.com/mcp"))

        do {
            _ = try await client.callTool(name: "slow", arguments: [:])
            XCTFail("Expected MCP call to time out")
        } catch let error as IOSMcpClientError {
            XCTAssertEqual(error, .requestTimedOut("MCP request tools/call timed out after 0.01s"))
        }
    }

    func testDisconnectClearsTransportSession() async throws {
        let transport = FakeMcpHTTPTransport(responses: [
            ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]]
        ])
        let client = IOSMcpClient(transport: transport)

        _ = try await client.connect(config: .sse(name: "docs", url: "https://example.com/sse"))
        client.disconnect()

        XCTAssertEqual(client.status, .idle)
        XCTAssertEqual(transport.disconnectedServers, ["docs"])
    }
}

private final class FakeMcpHTTPTransport: IOSMcpHTTPTransport {
    private var responses: [[String: Any]]
    private let hangingMethods: Set<String>
    private(set) var sentMethods: [String] = []
    private(set) var disconnectedServers: [String] = []

    init(responses: [[String: Any]], hangingMethods: Set<String> = []) {
        self.responses = responses
        self.hangingMethods = hangingMethods
    }

    func sendJSONRPC(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws -> [String: Any] {
        if let method = payload["method"] as? String {
            sentMethods.append(method)
            if hangingMethods.contains(method) {
                try await Task.sleep(nanoseconds: 1_000_000_000)
                return ["jsonrpc": "2.0", "id": payload["id"] as Any, "result": [:]]
            }
        }
        return responses.removeFirst()
    }

    func sendJSONRPCNotification(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws {
        if let method = payload["method"] as? String {
            sentMethods.append(method)
        }
    }

    func disconnect(config: IOSMcpServerConfig) {
        disconnectedServers.append(config.name)
    }
}
