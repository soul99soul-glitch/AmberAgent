import Foundation
@preconcurrency import WebKit

enum IOSMiniAppBridgeDocumentPolicy {
    static func allowsMessage(
        isTrustedDocument: Bool,
        isMainFrame: Bool,
        frameURL: URL?,
        mainDocumentURL: URL?
    ) -> Bool {
        guard isTrustedDocument, isMainFrame, frameURL != nil || mainDocumentURL != nil else { return false }
        if let frameURL, !isInitialDocumentURL(frameURL) { return false }
        if let mainDocumentURL, !isInitialDocumentURL(mainDocumentURL) { return false }
        return true
    }

    private static func isInitialDocumentURL(_ url: URL?) -> Bool {
        guard let url,
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return false
        }
        components.fragment = nil
        return components.string?.caseInsensitiveCompare("about:blank") == .orderedSame
    }
}

/// WKScriptMessageHandler for the iOS MiniApp runner.
/// Dispatches into IOSMiniAppBridgeRuntime, which owns grants, audit, storage,
/// shared store, event bus, and honest errors for unavailable capabilities.
@MainActor
final class MiniAppBridge: NSObject, WKScriptMessageHandler {

    /// Logged bridge messages (for the dev UI). Bounded to avoid unbounded growth.
    private(set) var log: [String] = []
    private let sessionId: String
    private let runtime: IOSMiniAppBridgeRuntime
    private let onLogChanged: ([String]) -> Void
    private weak var webView: WKWebView?
    private var isClosed = false
    private var isTrustedMainDocument = false

    init(
        runtime: IOSMiniAppBridgeRuntime,
        sessionId: String = UUID().uuidString,
        onLogChanged: @escaping ([String]) -> Void = { _ in }
    ) {
        self.sessionId = sessionId
        self.runtime = runtime
        self.onLogChanged = onLogChanged
        super.init()
    }

    func attach(webView: WKWebView) {
        self.webView = webView
        runtime.setEventEmitter { [weak webView, weak self] type, subscriptionId, payload in
            self?.sendEvent(webView: webView, type: type, subscriptionId: subscriptionId, payload: payload)
        }
    }

    func setTrustedMainDocument(_ trusted: Bool) {
        isTrustedMainDocument = trusted
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        webView = nil
        runtime.close()
    }

    /// WKScriptMessageHandler entry — called when the web page invokes
    /// `window.webkit.messageHandlers.amberNative.postMessage(...)`.
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard !isClosed else { return }
        guard IOSMiniAppBridgeDocumentPolicy.allowsMessage(
            isTrustedDocument: isTrustedMainDocument,
            isMainFrame: message.frameInfo.isMainFrame,
            frameURL: message.frameInfo.request.url,
            mainDocumentURL: message.webView?.url ?? webView?.url
        ) else {
            appendLog("Blocked bridge message from an untrusted document.")
            return
        }
        guard let raw = message.body as? String else { return }
        Task { @MainActor in
            await self.handle(raw, webView: message.webView ?? self.webView)
        }
    }

    private func handle(_ raw: String, webView: WKWebView?) async {
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
        let result = await runtime.dispatch(method: method, params: params)
        guard !isClosed else { return }
        sendResponse(webView: webView, id: id, result: result)
    }

    private func sendResponse(webView: WKWebView?, id: Any, result: IOSMiniAppBridgeDispatchResult) {
        var payload: [String: Any] = ["id": id]
        switch result {
        case .success(let value):
            payload["result"] = value.anyValue
        case .failure(let message):
            payload["error"] = message
        }
        payload["sessionId"] = sessionId
        guard let webView,
              let data = try? JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: payload)) as? [String: Any],
              let jsonString = stringValue(data) else { return }
        appendLog("▶ onResponse: \(jsonString.prefix(500))")
        webView.evaluateJavaScript("""
        window.AmberBridge && window.AmberBridge._handleNativeResponse && window.AmberBridge._handleNativeResponse(\(jsonString));
        """)
    }

    private func sendResponse(webView: WKWebView?, id: Any, error: String) {
        sendResponse(webView: webView, id: id, result: .failure(error))
    }

    private func sendEvent(webView: WKWebView?, type: String, subscriptionId: String?, payload: IOSMiniAppJSONValue) {
        var event: [String: Any] = [
            "type": type,
            "payload": payload.anyValue,
        ]
        if let subscriptionId {
            event["subscriptionId"] = subscriptionId
        }
        guard let webView,
              let jsonString = stringValue(event) else { return }
        appendLog("▶ event: \(jsonString.prefix(500))")
        webView.evaluateJavaScript("""
        window.AmberBridge && window.AmberBridge._emitNativeEvent && window.AmberBridge._emitNativeEvent(\(jsonString));
        """)
    }

    private func appendLog(_ line: String) {
        log.append(line)
        if log.count > 200 { log.removeFirst(log.count - 200) }
        onLogChanged(log)
    }

    /// Serialize a JSON object back to a string safe for evaluateJavaScript.
    private func stringValue(_ obj: [String: Any]) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: obj) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
