import XCTest
@testable import iosApp

@MainActor
final class IOSMcpConfigStoreTests: XCTestCase {
    func testAddAndReloadPersistsManualServer() {
        let defaults = isolatedDefaults()
        let store = IOSMcpConfigStore(userDefaults: defaults)

        store.add(.streamableHTTP(name: "docs", url: "https://example.com/mcp", headers: ["Authorization": "Bearer token"]))

        let reloaded = IOSMcpConfigStore(userDefaults: defaults)
        XCTAssertEqual(reloaded.servers, [.streamableHTTP(name: "docs", url: "https://example.com/mcp", headers: ["Authorization": "Bearer token"])])
    }

    func testImportJsonPersistsParsedServers() {
        let store = IOSMcpConfigStore(userDefaults: isolatedDefaults())

        let imported = store.importServers(json: """
        {
          "mcpServers": {
            "docs": { "type": "sse", "url": "https://example.com/sse" },
            "search": { "url": "https://example.com/mcp" }
          }
        }
        """)

        XCTAssertEqual(imported, 2)
        XCTAssertEqual(store.servers.map(\.name), ["docs", "search"])
    }

    func testRemoveDeletesStoredServer() {
        let store = IOSMcpConfigStore(userDefaults: isolatedDefaults())
        store.add(.streamableHTTP(name: "docs", url: "https://example.com/mcp"))
        store.add(.sse(name: "search", url: "https://example.com/sse"))

        store.remove(named: "docs")

        XCTAssertEqual(store.servers, [.sse(name: "search", url: "https://example.com/sse")])
    }

    func testSetEnabledPersistsServerToggle() {
        let defaults = isolatedDefaults()
        let store = IOSMcpConfigStore(userDefaults: defaults)
        store.add(.streamableHTTP(name: "docs", url: "https://example.com/mcp", headers: ["Authorization": "Bearer token"]))

        XCTAssertTrue(store.setEnabled(named: "docs", enabled: false))
        XCTAssertFalse(store.setEnabled(named: "missing", enabled: true))

        let expected = IOSMcpServerConfig.streamableHTTP(
            name: "docs",
            url: "https://example.com/mcp",
            headers: ["Authorization": "Bearer token"],
            enabled: false
        )
        XCTAssertEqual(store.servers, [expected])
        XCTAssertEqual(IOSMcpConfigStore(userDefaults: defaults).servers, [expected])
    }

    func testUpsertCanRenameExistingServer() {
        let store = IOSMcpConfigStore(userDefaults: isolatedDefaults())
        store.add(.streamableHTTP(name: "docs", url: "https://example.com/mcp"))

        store.upsert(.streamableHTTP(name: "docs-v2", url: "https://example.com/v2"), replacing: "docs")

        XCTAssertEqual(store.servers, [.streamableHTTP(name: "docs-v2", url: "https://example.com/v2")])
    }

    func testMergeDiscoveredToolsPersistsAndPreservesToolSwitch() {
        let defaults = isolatedDefaults()
        let store = IOSMcpConfigStore(userDefaults: defaults)
        store.add(.streamableHTTP(
            name: "docs",
            url: "https://example.com/mcp",
            tools: [IOSMcpTool(name: "search", description: "Old", enabled: false)]
        ))

        let merged = store.mergeDiscoveredTools(named: "docs", tools: [
            IOSMcpTool(name: "search", description: "New"),
            IOSMcpTool(name: "read", description: "Read docs")
        ])

        XCTAssertEqual(merged, [
            IOSMcpTool(name: "search", description: "New", enabled: false),
            IOSMcpTool(name: "read", description: "Read docs", enabled: true)
        ])
        XCTAssertTrue(store.setToolEnabled(serverName: "docs", toolName: "read", enabled: false))
        XCTAssertFalse(store.setToolEnabled(serverName: "docs", toolName: "missing", enabled: true))

        let reloaded = IOSMcpConfigStore(userDefaults: defaults)
        XCTAssertEqual(reloaded.servers.first?.tools, [
            IOSMcpTool(name: "search", description: "New", enabled: false),
            IOSMcpTool(name: "read", description: "Read docs", enabled: false)
        ])
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "IOSMcpConfigStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
