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

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "IOSMcpConfigStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
