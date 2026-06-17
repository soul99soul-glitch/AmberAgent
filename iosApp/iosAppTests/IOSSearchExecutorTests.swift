import XCTest
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
}
