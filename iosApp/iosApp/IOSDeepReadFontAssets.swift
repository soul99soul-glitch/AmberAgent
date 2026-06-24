import Foundation
#if canImport(WebKit)
@preconcurrency import WebKit

/// Serves the app-bundled Deep Read fonts to the editorial WKWebView through a
/// custom URL scheme so `@font-face` can load them. This is the reliable way to use
/// bundled fonts in a WKWebView (its web-content process can't see app-registered
/// fonts), and it lets the 11MB CJK serif stream from the bundle instead of being
/// base64-inlined into every page. Mirrors Android, which serves the same fonts from
/// a local asset URL.
///
/// URLs look like `amberfont://deepread/serif.otf`; the document is loaded with the
/// same `amberfont://deepread/` base URL so the font requests are same-origin.
final class IOSDeepReadFontSchemeHandler: NSObject, WKURLSchemeHandler {
    static let scheme = "amberfont"
    static let baseURL = "amberfont://deepread/"

    /// Path key (the `<key>` in `amberfont://deepread/<key>.<ext>`) → bundled font.
    private static let resources: [String: (name: String, ext: String, mime: String)] = [
        "serif": ("noto_serif_sc", "otf", "font/otf"),
        "mono": ("jetbrains_mono", "ttf", "font/ttf"),
    ]

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url else {
            task.didFailWithError(URLError(.badURL))
            return
        }
        let key = url.deletingPathExtension().lastPathComponent
        guard let res = Self.resources[key],
              let fileURL = Bundle.main.url(forResource: res.name, withExtension: res.ext),
              let data = try? Data(contentsOf: fileURL, options: .mappedIfSafe) else {
            task.didFailWithError(URLError(.fileDoesNotExist))
            return
        }
        let response = HTTPURLResponse(
            url: url,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: [
                "Content-Type": res.mime,
                "Content-Length": "\(data.count)",
                // Document origin is opaque/same-origin depending on the WKWebView
                // version; `*` keeps the font load from being CORS-blocked either way.
                "Access-Control-Allow-Origin": "*",
                "Cache-Control": "max-age=31536000",
            ]
        )!
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {
        // Responses are produced synchronously in `start`; nothing to cancel.
    }
}
#endif
