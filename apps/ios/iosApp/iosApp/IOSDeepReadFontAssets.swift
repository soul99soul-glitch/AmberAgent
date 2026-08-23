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
    /// Absolute origin the @font-face `src` URLs use (`amberfont://deepread/<key>.<ext>`).
    static let fontOrigin = "amberfont://deepread/"
    /// The DOCUMENT is loaded from a different, handler-less origin so this handler is
    /// never asked to serve the main frame (a registered scheme used as the document
    /// base URL gets the main-frame request routed here → fail → blank page). The font
    /// requests are then cross-origin and rely on the `Access-Control-Allow-Origin: *`
    /// header below.
    static let documentBaseURL = "https://deepread.amber.local/"

    /// Path key (the `<key>` in `amberfont://deepread/<key>.<ext>`) → bundled font.
    private static let resources: [String: (name: String, ext: String, mime: String)] = [
        "serif": ("noto_serif_sc_vf", "otf", "font/otf"),
        "mono": ("jetbrains_mono", "ttf", "font/ttf"),
    ]

    // Touched only on the main thread (WKURLSchemeHandler callbacks are delivered on
    // main). `liveTasks` tracks tasks that haven't been stopped so we never message a
    // stopped task — doing so throws an uncatchable NSException ("already stopped").
    // `fontDataCache` avoids re-mapping the 11MB serif on every reload (dark toggle,
    // hero arrival) of the same web view.
    private var liveTasks = Set<ObjectIdentifier>()
    private var fontDataCache: [String: Data] = [:]

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        let id = ObjectIdentifier(task)
        liveTasks.insert(id)
        defer { liveTasks.remove(id) }

        guard let url = task.request.url else {
            if liveTasks.contains(id) { task.didFailWithError(URLError(.badURL)) }
            return
        }
        let key = url.deletingPathExtension().lastPathComponent
        guard let res = Self.resources[key], let data = fontData(key: key, res: res) else {
            if liveTasks.contains(id) { task.didFailWithError(URLError(.fileDoesNotExist)) }
            return
        }
        guard let response = HTTPURLResponse(
            url: url,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: [
                "Content-Type": res.mime,
                "Content-Length": "\(data.count)",
                "Access-Control-Allow-Origin": "*",
                "Cache-Control": "max-age=31536000",
            ]
        ) else {
            if liveTasks.contains(id) { task.didFailWithError(URLError(.cannotParseResponse)) }
            return
        }
        // Re-check liveness before each send: if `stop` fired during the file map, the
        // task is dead and messaging it would crash.
        guard liveTasks.contains(id) else { return }
        task.didReceive(response)
        guard liveTasks.contains(id) else { return }
        task.didReceive(data)
        guard liveTasks.contains(id) else { return }
        task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {
        liveTasks.remove(ObjectIdentifier(task))
    }

    private func fontData(key: String, res: (name: String, ext: String, mime: String)) -> Data? {
        if let cached = fontDataCache[key] { return cached }
        guard let fileURL = Bundle.main.url(forResource: res.name, withExtension: res.ext),
              let data = try? Data(contentsOf: fileURL, options: .mappedIfSafe) else {
            return nil
        }
        fontDataCache[key] = data
        return data
    }
}
#endif
