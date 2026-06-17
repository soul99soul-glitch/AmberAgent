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
        XCTAssertEqual(transport.sentMethods, ["initialize"])
        XCTAssertEqual(client.status, .connected)
    }

    func testListToolsMapsMcpToolResult() async throws {
        let transport = FakeMcpHTTPTransport(responses: [
            ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]],
            ["jsonrpc": "2.0", "id": 2, "result": ["tools": [["name": "search", "description": "Search docs"]]]]
        ])
        let client = IOSMcpClient(transport: transport)
        _ = try await client.connect(config: .streamableHTTP(name: "docs", url: "https://example.com/mcp"))

        let tools = try await client.listTools()

        XCTAssertEqual(transport.sentMethods, ["initialize", "tools/list"])
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

        XCTAssertEqual(transport.sentMethods, ["initialize", "tools/call"])
        XCTAssertEqual(output, "hello")
    }
}

private final class FakeMcpHTTPTransport: IOSMcpHTTPTransport {
    private var responses: [[String: Any]]
    private(set) var sentMethods: [String] = []

    init(responses: [[String: Any]]) {
        self.responses = responses
    }

    func sendJSONRPC(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws -> [String: Any] {
        if let method = payload["method"] as? String {
            sentMethods.append(method)
        }
        return responses.removeFirst()
    }
}
