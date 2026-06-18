import Foundation
import WebKit

/// [MiniApp MVP] Minimal iOS JS bridge for the MiniApp WKWebView runner.
///
/// Android's `MiniAppBridge` (app/.../miniapp/bridge/MiniAppBridge.kt) is a
/// ~475-line JSON-RPC bridge exposing Amber.fetch / search / ai / host /
/// sharedStore / eventBus / sensor with permission/audit/sandbox. That full
/// surface is the "完整 Runner 待开发" piece.
///
/// This MVP implements the closed-loop protocol so generated HTML can talk to
/// native and get a response:
///   1. Web app calls `window.AmberNative.postMessage(JSON.stringify({id, method, params}))`.
///   2. Native parses it, dispatches by `method`, and calls
///      `window.AmberNative.onResponse(JSON)` back into the page.
///
/// Handled methods (honest):
///   - `log`           → appends to the in-memory log (debug aid)
///   - `echo`          → returns params verbatim (protocol proof)
///   - `app.info`      → returns {platform:"ios", bridgeVersion, sessionId}
/// Stubbed methods (return an honest "not implemented" error so the web app
/// can branch, never a fabricated success):
///   - `ai.*`, `search.*`, `host.*`, `fetch.*`, `clipboard.*`, `sharedStore.*`,
///     `eventBus.*`, `location.*`, `sensor.*`
///
/// The richer methods are deferred to the full Runner (Slice 9+).
@MainActor
final class MiniAppBridge: NSObject, WKScriptMessageHandler {

    /// Logged bridge messages (for the dev UI). Bounded to avoid unbounded growth.
    private(set) var log: [String] = []
    private let sessionId: String

    init(sessionId: String = UUID().uuidString) {
        self.sessionId = sessionId
        super.init()
    }

    /// WKScriptMessageHandler entry — called when the web page invokes
    /// `window.webkit.messageHandlers.amberNative.postMessage(...)`.
    nonisolated func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let raw = message.body as? String else { return }
        // Hop to MainActor for state mutation + response dispatch.
        Task { @MainActor in
            self.handle(raw, webView: message.webView)
        }
    }

    private func handle(_ raw: String, webView: WKWebView?) {
        appendLog("◀ postMessage: \(raw.prefix(500))")
        // Parse defensively; a malformed request still gets an honest error
        // response (never swallowed silently).
        let parsed: [String: Any]? = (raw.data(using: .utf8))
            .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        guard let request = parsed,
              let id = request["id"],
              let method = request["method"] as? String else {
            let fallbackId = parsed?["id"] ?? NSNull()
            sendResponse(webView: webView, id: fallbackId, error: "invalid request (need id+method)")
            return
        }
        let params = request["params"] as? [String: Any] ?? [:]
        let result = dispatch(method: method, params: params)
        sendResponse(webView: webView, id: id, result: result)
    }

    /// Dispatch by method name. Returns a JSON-serializable result or an error.
    private func dispatch(method: String, params: [String: Any]) -> BridgeResult {
        switch method {
        case "log":
            if let msg = params["message"] as? String { appendLog("page: \(msg)") }
            return .result(["ok": true])
        case "echo":
            return .result(params)
        case "app.info":
            return .result([
                "platform": "ios",
                "bridgeVersion": "0.1-mvp",
                "sessionId": sessionId,
            ])
        default:
            // Honest stub: full bridge methods (ai/search/host/fetch/clipboard/
            // sharedStore/eventBus/location/sensor) are not implemented in the
            // MVP. Return an explicit error so the web app can branch — never
            // fabricated success.
            return .error("method '\(method)' not implemented in MVP bridge (full Runner 待开发)")
        }
    }

    private enum BridgeResult {
        case result([String: Any])
        case error(String)
    }

    private func sendResponse(webView: WKWebView?, id: Any, result: BridgeResult) {
        var payload: [String: Any] = ["id": id]
        switch result {
        case .result(let value): payload["result"] = value
        case .error(let message): payload["error"] = message
        }
        guard let webView,
              let data = try? JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: payload)) as? [String: Any],
              let jsonString = stringValue(data) else { return }
        appendLog("▶ onResponse: \(jsonString.prefix(500))")
        webView.evaluateJavaScript("window.AmberNative && window.AmberNative.onResponse && window.AmberNative.onResponse(\(jsonString));")
    }

    private func sendResponse(webView: WKWebView?, id: Any, error: String) {
        sendResponse(webView: webView, id: id, result: .error(error))
    }

    private func appendLog(_ line: String) {
        log.append(line)
        if log.count > 200 { log.removeFirst(log.count - 200) }
    }

    /// Serialize a JSON object back to a string safe for evaluateJavaScript.
    private func stringValue(_ obj: [String: Any]) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: obj) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
