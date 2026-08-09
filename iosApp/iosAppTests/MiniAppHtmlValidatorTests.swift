import XCTest
@testable import iosApp

final class MiniAppHtmlValidatorTests: XCTestCase {
    func testAllowsSupportedOfflineAndRemoteResources() {
        let cases = [
            ("offline", "<!DOCTYPE html><html><body><h1>hi</h1></body></html>"),
            ("https and Amber API", #"<!DOCTYPE html><html><body><img src="https://example.com/a.png"><script>Amber.search({query:'AI'});</script></body></html>"#),
            ("data image and SVG", #"<!DOCTYPE html><html><body><svg viewBox="0 0 10 10"><rect width="10" height="10"/></svg><img src="data:image/png;base64,iVBORw0KGgo="></body></html>"#),
        ]

        for (name, html) in cases {
            XCTAssertNoThrow(try MiniAppHtmlValidator.validate(html), name)
        }
    }

    func testRejectsUnsafeHtmlCapabilities() {
        let cases = [
            ("external script", ##"<!DOCTYPE html><html><script src="https://example.com/a.js"></script></html>"##),
            ("http image", ##"<!DOCTYPE html><html><img src="http://example.com/a.png"></html>"##),
            ("iframe", ##"<!DOCTYPE html><html><iframe srcdoc="x"></iframe></html>"##),
            ("eval", #"<!DOCTYPE html><html><script>eval("1")</script></html>"#),
            ("XMLHttpRequest", #"<!DOCTYPE html><html><script>XMLHttpRequest</script></html>"#),
            ("localStorage", #"<!DOCTYPE html><html><script>localStorage.setItem('a','b')</script></html>"#),
            ("external CSS import", ##"<!DOCTYPE html><html><style>@import "https://example.com/a.css";</style></html>"##),
            ("form", "<!DOCTYPE html><html><form></form></html>"),
        ]

        for (name, html) in cases {
            XCTAssertThrowsError(try MiniAppHtmlValidator.validate(html), name)
        }
    }

    func testRejectsMissingHtmlTag() {
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate("just text, no html"))
    }

    func testRejectsOversizedHtml() {
        let padding = String(repeating: "x", count: MiniAppHtmlValidator.maxHtmlBytes + 100)
        let html = "<!DOCTYPE html><html><body>\(padding)</body></html>"
        XCTAssertThrowsError(try MiniAppHtmlValidator.validate(html))
    }

    @MainActor
    func testSampleRunnerHtmlPassesValidation() {
        XCTAssertNoThrow(try MiniAppHtmlValidator.validate(MiniAppRunnerView.sampleHtml))
    }
}
