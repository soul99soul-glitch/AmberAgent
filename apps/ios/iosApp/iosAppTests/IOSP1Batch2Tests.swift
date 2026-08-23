import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P1 batch 2 tests: workspace file_edit/list/search/move + MCP auto-reconnect.
@MainActor
final class IOSP1Batch2Tests: XCTestCase {

    private func makeStore() throws -> (IOSWorkspaceStore, URL) {
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSP1Batch2Tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        return (IOSWorkspaceStore(baseDirectory: baseDirectory), baseDirectory)
    }

    // MARK: - Workspace tools

    func testFileEditReplacesSubstring() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        // Seed a file via workspace_file_write.
        _ = await store.executeTool(
            toolName: "workspace_file_write",
            input: "{\"path\":\"notes.md\",\"content\":\"hello world\",\"overwrite\":true}"
        )
        // Edit: replace "world" with "ios".
        let result = await store.executeTool(
            toolName: "workspace_file_edit",
            input: "{\"path\":\"notes.md\",\"find\":\"world\",\"replace\":\"ios\"}"
        )
        let payload = try XCTUnwrap(Self.jsonDict(result))
        XCTAssertEqual(payload["ok"] as? Bool, true)
        XCTAssertEqual(payload["replacements"] as? Int, 1)
        // Verify the content changed by reading it back.
        let read = await store.executeTool(toolName: "workspace_file_read", input: "{\"path\":\"notes.md\"}")
        let readPayload = try XCTUnwrap(Self.jsonDict(read))
        XCTAssertEqual(readPayload["text"] as? String, "hello ios")
    }

    func testFileListReturnsAllFiles() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = await store.executeTool(toolName: "workspace_file_write", input: "{\"path\":\"a.md\",\"content\":\"alpha\",\"overwrite\":true}")
        _ = await store.executeTool(toolName: "workspace_file_write", input: "{\"path\":\"b.md\",\"content\":\"beta\",\"overwrite\":true}")

        let result = await store.executeTool(toolName: "workspace_file_list", input: "{}")
        let payload = try XCTUnwrap(Self.jsonDict(result))
        XCTAssertEqual(payload["ok"] as? Bool, true)
        XCTAssertEqual(payload["count"] as? Int, 2)
        let files = try XCTUnwrap(payload["files"] as? [[String: Any]])
        XCTAssertEqual(files.count, 2)
    }

    func testFileSearchFindsMatchingContent() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = await store.executeTool(toolName: "workspace_file_write", input: "{\"path\":\"doc.md\",\"content\":\"The quick brown fox\",\"overwrite\":true}")

        let result = await store.executeTool(toolName: "workspace_file_search", input: "{\"query\":\"brown\"}")
        let payload = try XCTUnwrap(Self.jsonDict(result))
        XCTAssertEqual(payload["ok"] as? Bool, true)
        XCTAssertEqual(payload["matches"] as? Int, 1)
        let hits = try XCTUnwrap(payload["results"] as? [[String: Any]])
        XCTAssertTrue((hits.first?["snippet"] as? String ?? "").contains("brown"))
    }

    func testFileMoveRelocatesFile() async throws {
        let (store, dir) = try makeStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = await store.executeTool(toolName: "workspace_file_write", input: "{\"path\":\"old.md\",\"content\":\"move me\",\"overwrite\":true}")

        let result = await store.executeTool(toolName: "workspace_file_move", input: "{\"path\":\"old.md\",\"destination_path\":\"renamed.md\"}")
        let payload = try XCTUnwrap(Self.jsonDict(result))
        XCTAssertEqual(payload["ok"] as? Bool, true)
        XCTAssertEqual(payload["path"] as? String, "/workspace/renamed.md")
        // The old path should no longer resolve.
        let oldRead = await store.executeTool(toolName: "workspace_file_read", input: "{\"path\":\"old.md\"}")
        let oldPayload = try XCTUnwrap(Self.jsonDict(oldRead))
        XCTAssertEqual(oldPayload["ok"] as? Bool, false)
    }

    // MARK: - MCP auto-reconnect

    /// A scripted MCP client that fails the first connect then succeeds.
    final class FlakyMcpClient: IOSMcpClienting, @unchecked Sendable {
        var connectShouldFail = true
        private(set) var connectAttempts = 0
        func connect(config: IOSMcpServerConfig) async throws -> Bool {
            connectAttempts += 1
            if connectShouldFail {
                throw IOSMcpClientError.invalidResponse
            }
            return true
        }
        func listTools() async throws -> [IOSMcpTool] { [] }
        func callTool(name: String, arguments: [String: Any]) async throws -> String { "" }
        func disconnect() {}
    }

    func testMcpReconnectRecoversFailedServer() async throws {
        let client = FlakyMcpClient()
        let server = IOSMcpServerConfig.sse(name: "test-server", url: "https://example.test/sse", enabled: true)
        let manager = IOSMcpManager(
            serverProvider: { [server] },
            isEnabled: { true },
            clientFactory: { _ in client }
        )
        await manager.syncAll()
        // Initial connect fails → error status.
        if case .error = manager.statusByServer["test-server"] {
            // expected
        } else {
            XCTFail("expected .error status after failed connect, got \(String(describing: manager.statusByServer["test-server"]))")
        }
        XCTAssertEqual(client.connectAttempts, 1)

        // Simulate backoff elapsing, make connects succeed, then reconnect.
        client.connectShouldFail = false
        manager.clearReconnectBackoffForTesting(serverName: "test-server")
        let retried = await manager.reconnectFailedServers()
        XCTAssertTrue(retried.contains("test-server"))
        XCTAssertEqual(manager.statusByServer["test-server"], .connected)
    }

    private static func jsonDict(_ json: String) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any]
    }

    /// Clears the reconnect backoff window so a test can retry immediately.
    private static func clearReconnectBackoffForTesting(_ manager: IOSMcpManager, serverName: String) {
        manager.clearReconnectBackoffForTesting(serverName: serverName)
    }
}
