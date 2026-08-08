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
        XCTAssertNil(tools.first?.inputSchema, "legacy servers without inputSchema decode as nil")
    }

    /// G2: tools/list inputSchema is serialized and persisted on IOSMcpTool so
    /// mcp_describe_tool can serve it later without re-connecting.
    func testListToolsPersistsCompleteInputSchema() async throws {
        let bigSchema: [String: Any] = [
            "type": "object",
            "properties": [
                "query": ["type": "string", "description": String(repeating: "x", count: 3_000)],
            ],
        ]
        let transport = FakeMcpHTTPTransport(responses: [
            ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:], "serverInfo": ["name": "fake", "version": "1"]]],
            ["jsonrpc": "2.0", "id": 2, "result": ["tools": [
                ["name": "search", "description": "Search docs", "inputSchema": ["type": "object", "properties": ["q": ["type": "string"]]]],
                ["name": "big", "description": "Big schema tool", "inputSchema": bigSchema],
            ]]]
        ])
        let client = IOSMcpClient(transport: transport)
        _ = try await client.connect(config: .streamableHTTP(name: "docs", url: "https://example.com/mcp"))

        let tools = try await client.listTools()

        XCTAssertEqual(tools.count, 2)
        XCTAssertEqual(
            tools[0].inputSchema,
            #"{"properties":{"q":{"type":"string"}},"type":"object"}"#
        )
        let persistedBigSchema = try XCTUnwrap(tools[1].inputSchema)
        XCTAssertGreaterThan(persistedBigSchema.count, 2_048)
        let persistedData = try XCTUnwrap(persistedBigSchema.data(using: .utf8))
        let decoded = try JSONSerialization.jsonObject(with: persistedData) as? [String: Any]
        XCTAssertNotNil(decoded?["properties"], "mcp_describe_tool must receive a complete JSON schema")
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

    func testCallerCancellationCancelsInFlightTransportWithoutWaitingForTimeout() async throws {
        let transport = FakeMcpHTTPTransport(
            responses: [
                ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:]]]
            ],
            hangingMethods: ["tools/call"]
        )
        let client = IOSMcpClient(transport: transport, requestTimeoutSeconds: 5)
        _ = try await client.connect(config: .streamableHTTP(name: "docs", url: "https://example.com/mcp"))

        let call = Task { try await client.callTool(name: "slow", arguments: [:]) }
        while !transport.sentMethods.contains("tools/call") {
            await Task.yield()
        }
        call.cancel()

        do {
            _ = try await call.value
            XCTFail("Expected caller cancellation")
        } catch is CancellationError {
            // Expected.
        }
        XCTAssertTrue(transport.cancelledMethods.contains("tools/call"))
    }

    func testConcurrentCallsAreSerializedPerClient() async throws {
        let transport = FakeMcpHTTPTransport(
            responses: [
                ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:]]],
                ["jsonrpc": "2.0", "id": 2, "result": ["content": [["type": "text", "text": "first"]]]],
                ["jsonrpc": "2.0", "id": 3, "result": ["content": [["type": "text", "text": "second"]]]],
            ],
            delayedMethods: ["tools/call": 20_000_000]
        )
        let client = IOSMcpClient(transport: transport)
        _ = try await client.connect(config: .sse(name: "legacy", url: "https://example.com/sse"))

        let first = Task { try await client.callTool(name: "one", arguments: [:]) }
        let second = Task { try await client.callTool(name: "two", arguments: [:]) }
        _ = try await (first.value, second.value)

        XCTAssertEqual(transport.maximumConcurrentRequests, 1)
    }

    func testConnectHandshakeCannotBeInterleavedByToolRequest() async throws {
        let transport = FakeMcpHTTPTransport(
            responses: [
                ["jsonrpc": "2.0", "id": 1, "result": ["protocolVersion": "2024-11-05", "capabilities": [:]]],
                ["jsonrpc": "2.0", "id": 2, "result": ["tools": []]],
            ],
            delayedMethods: ["initialize": 20_000_000]
        )
        let client = IOSMcpClient(transport: transport)
        let connect = Task {
            try await client.connect(config: .streamableHTTP(name: "docs", url: "https://example.com/mcp"))
        }
        while !transport.sentMethods.contains("initialize") {
            await Task.yield()
        }
        let list = Task { try await client.listTools() }

        _ = try await connect.value
        _ = try await list.value

        XCTAssertEqual(transport.sentMethods, ["initialize", "notifications/initialized", "tools/list"])
    }

    func testLegacyEndpointMustUseSecureSameOriginTransport() throws {
        XCTAssertEqual(
            try IOSMcpLegacyEndpointPolicy.validatedEndpoint("/messages?id=1", relativeTo: "https://example.com/sse").absoluteString,
            "https://example.com/messages?id=1"
        )
        XCTAssertEqual(
            try IOSMcpLegacyEndpointPolicy.validatedEndpoint("/messages", relativeTo: "http://127.0.0.1:8080/sse").absoluteString,
            "http://127.0.0.1:8080/messages"
        )
        XCTAssertEqual(
            try IOSMcpLegacyEndpointPolicy.validatedEndpoint("/messages", relativeTo: "http://192.168.1.2:8080/sse").absoluteString,
            "http://192.168.1.2:8080/messages"
        )
        XCTAssertThrowsError(
            try IOSMcpLegacyEndpointPolicy.validatedEndpoint("https://attacker.example/messages", relativeTo: "https://example.com/sse")
        )
        XCTAssertThrowsError(
            try IOSMcpLegacyEndpointPolicy.validatedEndpoint("file:///tmp/messages", relativeTo: "https://example.com/sse")
        )
        XCTAssertThrowsError(
            try IOSMcpLegacyEndpointPolicy.validatedEndpoint("http://example.com/messages", relativeTo: "https://example.com/sse")
        )
    }

    func testMcpToolbarButtonsMeetMinimumTouchTarget() throws {
        let sourceURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("iosApp/McpServersView.swift")
        let source = try String(contentsOf: sourceURL, encoding: .utf8)

        for label in ["刷新 MCP 工具", "导入服务器", "添加服务器"] {
            let line = try XCTUnwrap(source.components(separatedBy: .newlines).first(where: { $0.contains(#"accessibilityLabel: "\#(label)""#) }))
            XCTAssertTrue(line.contains("size: 44"), line)
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
    private let delayedMethods: [String: UInt64]
    private(set) var sentMethods: [String] = []
    private(set) var disconnectedServers: [String] = []
    private(set) var cancelledMethods: Set<String> = []
    private(set) var maximumConcurrentRequests = 0
    private var activeRequests = 0

    init(
        responses: [[String: Any]],
        hangingMethods: Set<String> = [],
        delayedMethods: [String: UInt64] = [:]
    ) {
        self.responses = responses
        self.hangingMethods = hangingMethods
        self.delayedMethods = delayedMethods
    }

    func sendJSONRPC(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws -> [String: Any] {
        if let method = payload["method"] as? String {
            sentMethods.append(method)
            activeRequests += 1
            maximumConcurrentRequests = max(maximumConcurrentRequests, activeRequests)
            defer { activeRequests -= 1 }
            do {
                if hangingMethods.contains(method) {
                    try await Task.sleep(nanoseconds: 1_000_000_000)
                    return ["jsonrpc": "2.0", "id": payload["id"] as Any, "result": [:]]
                }
                if let delay = delayedMethods[method] {
                    try await Task.sleep(nanoseconds: delay)
                }
            } catch is CancellationError {
                cancelledMethods.insert(method)
                throw CancellationError()
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
