import XCTest
@preconcurrency import Shared
@testable import iosApp

final class IOSSearchExecutorTests: XCTestCase {
    func testSearchRequestParsesToolJSON() throws {
        let request = try IOSSearchExecutor.searchRequest(
            from: #"{"query":"swift concurrency","max_results":3}"#,
            defaultMaxResults: 5
        )

        XCTAssertEqual(request.query, "swift concurrency")
        XCTAssertEqual(request.maxResults, 3)
    }

    func testSearchRequestUsesPlainTextFallback() throws {
        let request = try IOSSearchExecutor.searchRequest(from: "latest apple news", defaultMaxResults: 4)

        XCTAssertEqual(request.query, "latest apple news")
        XCTAssertEqual(request.maxResults, 4)
    }

    func testSearchRequestRejectsEmptyQuery() {
        XCTAssertThrowsError(try IOSSearchExecutor.searchRequest(from: #"{"query":"   "}"#))
    }

    func testProviderSelectionUsesSelectedSavedProvider() {
        let store = makeIsolatedStore()
        store.addSearchProvider(name: "Bing", serviceType: "bing_local")
        let selectedService = store.snapshot.searchServices[Int(store.snapshot.searchServiceSelected)]

        let selection = IOSSearchExecutor.searchProviderSelection(settings: store.snapshot)

        XCTAssertEqual(selection.route, .bingHTML)
        XCTAssertEqual(selection.providerType, "bing_local")
        XCTAssertEqual(selection.serviceId, selectedService.id.description())
        XCTAssertTrue(
            store.snapshot.searchEnabledServiceIds.contains { $0.description() == selectedService.id.description() },
            "added search provider should be enabled for execution"
        )
    }

    func testDisabledSelectedProviderFallsBackToDuckDuckGo() {
        let store = makeIsolatedStore()
        let selectedService = store.snapshot.searchServices[Int(store.snapshot.searchServiceSelected)]
        store.restoreSnapshot(
            IosSettingsMutations.shared.setSearchServiceEnabled(
                settings: store.snapshot,
                id: selectedService.id.description(),
                enabled: false
            )
        )

        let selection = IOSSearchExecutor.searchProviderSelection(settings: store.snapshot)

        XCTAssertEqual(selection.route, .duckDuckGoLite)
        XCTAssertEqual(selection.providerType, "duckduckgo_builtin")
        XCTAssertTrue(selection.fallbackReason?.contains("disabled") == true)
    }

    func testDuckDuckGoRouteUsesMockTransport() async throws {
        let transport = MockSearchTransport(responses: [
            .html("""
            <html><body>
            <a rel="nofollow" class='result-link' href="/l/?kh=-1&amp;uddg=https%3A%2F%2Fexample.com%2Fone">First &amp; Result</a>
            <td class='result-snippet'>First snippet.</td>
            </body></html>
            """)
        ])

        let output = try await IOSSearchExecutor.execute(
            toolInput: #"{"query":"swift concurrency","max_results":1}"#,
            transport: transport
        )

        XCTAssertEqual(transport.requests.first?.url?.host, "lite.duckduckgo.com")
        XCTAssertTrue(output.contains("来源：DuckDuckGo Lite"))
        XCTAssertTrue(output.contains("https://example.com/one"))
    }

    func testSelectedBingRouteUsesMockTransport() async throws {
        let store = makeIsolatedStore()
        let transport = MockSearchTransport(responses: [
            .html("""
            <html><body>
            <ol id="b_results">
              <li class="b_algo">
                <h2><a href="https://example.com/bing">Bing Result</a></h2>
                <p>Bing snippet with <strong>markup</strong>.</p>
              </li>
            </ol>
            </body></html>
            """)
        ])

        let output = try await IOSSearchExecutor.execute(
            toolInput: #"{"query":"amber agent","max_results":1}"#,
            settings: store.snapshot,
            transport: transport
        )

        XCTAssertEqual(transport.requests.first?.url?.host, "www.bing.com")
        XCTAssertTrue(output.contains("来源：Bing HTML"))
        XCTAssertTrue(output.contains("Bing snippet with markup."))
    }

    func testScrapeWebAllowsPublicHTTPAndExtractsReadableText() async throws {
        let transport = MockSearchTransport(responses: [
            .html("""
            <html>
              <head><title>Readable Page</title><script>ignoreMe()</script></head>
              <body><article><h1>Hello</h1><p>Body &amp; text.</p></article></body>
            </html>
            """)
        ])

        let output = try await IOSSearchExecutor.execute(
            toolName: "scrape_web",
            toolInput: #"{"url":"https://example.com/page","max_chars":2000}"#,
            transport: transport
        )
        let payload = try XCTUnwrap(jsonObject(output))

        XCTAssertEqual(transport.requests.first?.url?.absoluteString, "https://example.com/page")
        XCTAssertEqual(payload["status"] as? String, "ok")
        XCTAssertEqual(payload["title"] as? String, "Readable Page")
        XCTAssertTrue((payload["content"] as? String)?.contains("Hello") == true)
        XCTAssertTrue((payload["content"] as? String)?.contains("Body & text.") == true)
        XCTAssertFalse((payload["content"] as? String)?.contains("ignoreMe") == true)
    }

    func testScrapeWebDeniesLocalhost() {
        XCTAssertThrowsError(
            try IOSSearchExecutor.scrapeRequest(from: #"{"url":"http://127.0.0.1/admin"}"#)
        ) { error in
            XCTAssertEqual(
                error as? IOSSearchExecutorError,
                .disallowedURL("local, loopback, link-local, and private hosts are blocked")
            )
        }
    }

    func testScrapeWebRejectsInvalidURL() {
        XCTAssertThrowsError(try IOSSearchExecutor.scrapeRequest(from: #"{"url":"not a url"}"#)) { error in
            XCTAssertEqual(error as? IOSSearchExecutorError, .invalidURL)
        }
    }

    func testParseDuckDuckGoLiteResults() {
        let html = """
        <html><body>
        <a rel="nofollow" class='result-link' href="/l/?kh=-1&amp;uddg=https%3A%2F%2Fexample.com%2Fone">First &amp; Result</a>
        <td class='result-snippet'>First snippet with <b>markup</b>.</td>
        <a rel="nofollow" class="result-link" href="https://example.org/two">Second Result</a>
        <td class="result-snippet">Second&nbsp;snippet.</td>
        </body></html>
        """

        let results = IOSSearchExecutor.parseDuckDuckGoLite(html: html, maxResults: 10)

        XCTAssertEqual(results.count, 2)
        XCTAssertEqual(results[0].title, "First & Result")
        XCTAssertEqual(results[0].url, "https://example.com/one")
        XCTAssertEqual(results[0].snippet, "First snippet with markup.")
        XCTAssertEqual(results[1].url, "https://example.org/two")
        XCTAssertEqual(results[1].snippet, "Second snippet.")
    }

    private func makeIsolatedStore(suiteName: String = "SearchExecutor-\(UUID().uuidString)") -> IOSSharedSettingsStore {
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return IOSSharedSettingsStore(userDefaults: defaults)
    }

    private func jsonObject(_ string: String) -> [String: Any]? {
        guard let data = string.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}

@MainActor
final class ChatViewModelSearchToolDeclarationTests: XCTestCase {
    func testSearchToolDeclarationsFollowEnableWebSearchGate() {
        let enabledStore = makeSharedSettings(enableWebSearch: true)
        let enabledViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: enabledStore,
            autoGenerateResponses: false
        )

        let enabledNames = Set(enabledViewModel.currentToolDeclarationNames())
        XCTAssertTrue(enabledNames.contains("search_web"))
        XCTAssertTrue(enabledNames.contains("scrape_web"))

        let disabledStore = makeSharedSettings(enableWebSearch: false)
        let disabledViewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: disabledStore,
            autoGenerateResponses: false
        )

        let disabledNames = Set(disabledViewModel.currentToolDeclarationNames())
        XCTAssertFalse(disabledNames.contains("search_web"))
        XCTAssertFalse(disabledNames.contains("scrape_web"))
    }

    private func makeSharedSettings(enableWebSearch: Bool) -> IOSSharedSettingsStore {
        let suiteName = "ChatSearchTools-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        store.restoreSnapshot(
            IosSettingsMutations.shared.setEnableWebSearch(
                settings: store.snapshot,
                enabled: enableWebSearch
            )
        )
        return store
    }
}

private final class MockSearchTransport: IOSSearchHTTPTransport {
    struct Response {
        let status: Int
        let headers: [String: String]
        let body: Data

        static func html(_ body: String, status: Int = 200) -> Response {
            Response(
                status: status,
                headers: ["Content-Type": "text/html; charset=utf-8"],
                body: Data(body.utf8)
            )
        }
    }

    private var responses: [Response]
    private(set) var requests: [URLRequest] = []

    init(responses: [Response]) {
        self.responses = responses
    }

    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        requests.append(request)
        let response = responses.isEmpty ? .html("") : responses.removeFirst()
        let http = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: response.status,
            httpVersion: "HTTP/1.1",
            headerFields: response.headers
        )!
        return (http, response.body)
    }
}
