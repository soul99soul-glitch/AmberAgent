import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
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

    func testSearchResultsCanBecomeDeepReadSources() async throws {
        let transport = MockSearchTransport(responses: [
            .html("""
            <html><body>
            <a rel="nofollow" class='result-link' href="/l/?kh=-1&amp;uddg=https%3A%2F%2Fexample.com%2Fdeep">Deep Result</a>
            <td class='result-snippet'>Snippet for deep reading.</td>
            </body></html>
            """)
        ])

        let execution = try await IOSSearchExecutor.searchResults(
            toolInput: #"{"query":"deep read source","max_results":1}"#,
            transport: transport
        )
        let sources = try IOSDeepReadSourceNormalizer.searchSources(
            query: execution.request.query,
            results: execution.results,
            now: 1_800_000_000_000
        )

        XCTAssertEqual(sources.count, 1)
        XCTAssertEqual(sources.first?.kind, .searchResult)
        XCTAssertEqual(sources.first?.metadata["query"], "deep read source")
        XCTAssertEqual(sources.first?.url, "https://example.com/deep")
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

    func testTavilyRouteUsesMockTransportAndAPIKey() async throws {
        let store = makeIsolatedStore()
        store.addSearchProvider(name: "Tavily", apiKey: "tvly-test", serviceType: "tavily")
        let transport = MockSearchTransport(responses: [
            .json("""
            {
              "results": [
                {
                  "title": "Tavily Result",
                  "url": "https://example.com/tavily",
                  "content": "Tavily snippet."
                }
              ]
            }
            """)
        ])

        let output = try await IOSSearchExecutor.execute(
            toolInput: #"{"query":"swift concurrency","max_results":1}"#,
            settings: store.snapshot,
            transport: transport
        )
        let request = try XCTUnwrap(transport.requests.first)
        let body = try jsonObject(try XCTUnwrap(request.httpBody))

        XCTAssertEqual(request.url?.host, "api.tavily.com")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer tvly-test")
        XCTAssertEqual(body["query"] as? String, "swift concurrency")
        XCTAssertEqual(body["max_results"] as? Int, 1)
        XCTAssertTrue(output.contains("来源：Tavily"))
        XCTAssertTrue(output.contains("https://example.com/tavily"))
    }

    func testBraveRoutePrefersOriginalThumbnailOverProxiedSrc() async throws {
        // Regression: Brave's proxied thumbnail `src` (imgs.search.brave.com) is
        // hot-link protected and 403s without a brave.com Referer → blank hero. The
        // editorial reader sources its hero from images.first, so `original` (the true
        // source image) must come first, `src` only as fallback (parity with Android).
        let store = makeIsolatedStore()
        store.addSearchProvider(name: "Brave", apiKey: "brave-test", serviceType: "brave")
        let transport = MockSearchTransport(responses: [
            .json("""
            {
              "web": {
                "results": [
                  {
                    "title": "Brave Result",
                    "url": "https://example.com/brave",
                    "description": "Brave snippet.",
                    "thumbnail": {
                      "src": "https://imgs.search.brave.com/sig/proxy.jpg",
                      "original": "https://cdn.example.com/real.jpg"
                    }
                  }
                ]
              }
            }
            """)
        ])

        let execution = try await IOSSearchExecutor.searchResults(
            toolInput: #"{"query":"embodied ai","max_results":1}"#,
            settings: store.snapshot,
            transport: transport
        )

        XCTAssertEqual(transport.requests.first?.url?.host, "api.search.brave.com")
        let result = try XCTUnwrap(execution.results.first)
        XCTAssertEqual(
            result.images.first,
            "https://cdn.example.com/real.jpg",
            "hero must prefer the true source image, not the 403-prone proxied src"
        )
        XCTAssertEqual(result.images, [
            "https://cdn.example.com/real.jpg",
            "https://imgs.search.brave.com/sig/proxy.jpg",
        ])
    }

    func testScrapeExtractsOgImageHeroBothAttributeOrders() {
        // The Deep Read editorial hero for hot-topic articles is sourced from the
        // scraped page's og:image / twitter:image (parity with Android).
        let html = """
        <html><head>
        <meta property="og:image" content="https://cdn.example.com/hero.jpg"/>
        <meta name="twitter:image" content="https://cdn.example.com/tw.jpg"/>
        <meta content="https://cdn.example.com/reversed.jpg" property="og:image:secure_url"/>
        <meta property="og:title" content="not an image"/>
        <title>x</title>
        </head><body><p>body</p></body></html>
        """
        let urls = IOSSearchExecutor.extractHeroImageURLs(from: html)
        XCTAssertEqual(urls.first, "https://cdn.example.com/hero.jpg", "og:image is the first hero candidate")
        XCTAssertTrue(urls.contains("https://cdn.example.com/tw.jpg"))
        XCTAssertTrue(urls.contains("https://cdn.example.com/reversed.jpg"), "content-before-property order must parse too")
        XCTAssertFalse(urls.contains("not an image"))
    }

    func testScrapeOgImageIgnoresNonHTTPAndDedupes() {
        let html = """
        <meta property="og:image" content="https://cdn.example.com/a.jpg"/>
        <meta property="og:image" content="https://cdn.example.com/a.jpg"/>
        <meta property="og:image" content="/relative/no-host.jpg"/>
        """
        let urls = IOSSearchExecutor.extractHeroImageURLs(from: html)
        XCTAssertEqual(urls, ["https://cdn.example.com/a.jpg"], "deduped + http(s)-only")
    }

    func testMissingAPIKeyProviderFallsBackToExecutableSearch() {
        let store = makeIsolatedStore()
        store.addSearchProvider(name: "Tavily Without Key", serviceType: "tavily")

        let selection = IOSSearchExecutor.searchProviderSelection(settings: store.snapshot)

        XCTAssertTrue([IOSSearchRoute.duckDuckGoLite, .bingHTML].contains(selection.route))
        XCTAssertNotEqual(selection.providerType, "tavily")
        XCTAssertTrue(selection.fallbackReason?.contains("no API key") == true)
    }

    func testJinaRouteAllowsMissingAPIKey() async throws {
        let store = makeIsolatedStore()
        store.addSearchProvider(name: "Jina", serviceType: "jina")
        let transport = MockSearchTransport(responses: [
            .json("""
            {
              "data": [
                {
                  "title": "Jina Result",
                  "url": "https://example.com/jina",
                  "description": "Jina snippet."
                }
              ]
            }
            """)
        ])

        let output = try await IOSSearchExecutor.execute(
            toolInput: #"{"query":"amber agent","max_results":1}"#,
            settings: store.snapshot,
            transport: transport
        )
        let request = try XCTUnwrap(transport.requests.first)

        XCTAssertEqual(request.url?.host, "s.jina.ai")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertTrue(output.contains("来源：Jina"))
        XCTAssertTrue(output.contains("Jina snippet."))
    }

    func testAPIHTTPFailureIncludesProviderName() async throws {
        let store = makeIsolatedStore()
        store.addSearchProvider(name: "Tavily", apiKey: "tvly-test", serviceType: "tavily")
        let transport = MockSearchTransport(responses: [.json("{}", status: 500)])

        do {
            _ = try await IOSSearchExecutor.execute(
                toolInput: #"{"query":"swift concurrency"}"#,
                settings: store.snapshot,
                transport: transport
            )
            XCTFail("Expected Tavily HTTP failure")
        } catch {
            XCTAssertEqual(error as? IOSSearchExecutorError, .httpStatus("Tavily", 500))
        }
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

    private func jsonObject(_ data: Data) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
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

        static func json(_ body: String, status: Int = 200) -> Response {
            Response(
                status: status,
                headers: ["Content-Type": "application/json"],
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
