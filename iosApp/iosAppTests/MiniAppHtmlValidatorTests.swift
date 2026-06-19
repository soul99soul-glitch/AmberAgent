import XCTest
@testable import iosApp

/// [MiniApp MVP] Verifies the iOS port of MiniAppHtmlValidator matches
/// Android's rule set (app/.../MiniAppHtmlValidatorTest.kt). The validator is
/// the load-bearing security gate for the MiniApp runner — this test ensures
/// generated HTML can't bypass the WKWebView sandbox via external scripts,
/// dangerous APIs, or non-https resources.
final class MiniAppHtmlValidatorTests: XCTestCase {

    // ---- allows (offline / https / inline Amber API) ----

    func testAllowsOfflineSingleFileHtml() throws {
        XCTAssertNoThrow(try MiniAppHtmlValidator.validate(
            "<!DOCTYPE html><html><body><h1>hi</h1></body></html>"
        ))
    }

    func testAllowsHttpsImageAndInlineAmberCalls() throws {
        XCTAssertNoThrow(try MiniAppHtmlValidator.validate(
            #"<!DOCTYPE html><html><body><img src="https://example.com/a.png"><script>Amber.search({query:'AI'});</script></body></html>"#
        ))
    }

    func testAllowsDataImageSvg() throws {
        XCTAssertNoThrow(try MiniAppHtmlValidator.validate(
            #"<!DOCTYPE html><html><body><svg viewBox="0 0 10 10"><rect width="10" height="10"/></svg><img src="data:image/png;base64,iVBORw0KGgo="></body></html>"#
        ))
    }

    // ---- rejects external / dangerous ----

    func testRejectsExternalScriptSrc() {
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate(
            ##"<!DOCTYPE html><html><script src="https://example.com/a.js"></script></html>"##
        ))
    }

    func testRejectsHttpImage() {
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate(
            ##"<!DOCTYPE html><html><img src="http://example.com/a.png"></html>"##
        ))
    }

    func testRejectsIframe() {
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate(
            ##"<!DOCTYPE html><html><iframe srcdoc="x"></iframe></html>"##
        ))
    }

    func testRejectsEval() {
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate(
            #"<!DOCTYPE html><html><script>eval("1")</script></html>"#
        ))
    }

    func testRejectsXmlHttpRequest() {
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate(
            #"<!DOCTYPE html><html><script>XMLHttpRequest</script></html>"#
        ))
    }

    func testRejectsLocalStorage() {
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate(
            #"<!DOCTYPE html><html><script>localStorage.setItem('a','b')</script></html>"#
        ))
    }

    func testRejectsExternalCssImport() {
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate(
            ##"<!DOCTYPE html><html><style>@import "https://example.com/a.css";</style></html>"##
        ))
    }

    func testRejectsFormElement() {
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate(
            "<!DOCTYPE html><html><form></form></html>"
        ))
    }

    // ---- structural ----

    func testRejectsMissingHtmlTag() {
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate("just text, no html"))
    }

    func testRejectsOversizedHtml() {
        // Build a doc just over the 1 MiB limit.
        let padding = String(repeating: "x", count: MiniAppHtmlValidator.maxHtmlBytes + 100)
        let html = "<!DOCTYPE html><html><body>\(padding)</body></html>"
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate(html))
    }

    // ---- the MVP sample HTML must pass ----

    @MainActor
    func testSampleRunnerHtmlPassesValidation() throws {
        // The sample shipped in MiniAppRunnerView must clear the validator
        // (otherwise the demo runner would refuse to load).
        XCTAssertNoThrow(try MiniAppHtmlValidator.validate(MiniAppRunnerView.sampleHtml))
    }
}
