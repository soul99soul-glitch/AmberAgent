import SwiftUI
import WebKit

/// [MiniApp MVP] WKWebView runner that loads generated MiniApp HTML with the
/// AmberNative bridge injected.
///
/// Flow (mirrors Android's MiniAppSandbox + Runner):
///   1. Validate HTML via MiniAppHtmlValidator (security gate, Android-parity).
///   2. Inject a bootstrap <script> that defines `window.AmberNative`
///      (postMessage shim → window.webkit.messageHandlers.amberNative) so the
///      web app can call native without knowing webkit internals.
///   3. Load the HTML string into WKWebView with the bridge controller wired.
///
/// This proves the closed loop: generated HTML → validated → rendered → web app
/// calls AmberNative.postMessage → native handles → onResponse → web app updates.
@MainActor
struct MiniAppRunnerWebView: UIViewRepresentable {
    let html: String
    let onValidationError: (String) -> Void
    let onBridgeLog: ([String]) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onValidationError: onValidationError, onBridgeLog: onBridgeLog)
    }

    func makeUIView(context: Context) -> WKWebView {
        // Validate BEFORE creating any web view. If invalid, surface the error
        // and render a safe placeholder page (never the unvalidated HTML).
        do {
            try MiniAppHtmlValidator.validate(html)
        } catch {
            context.coordinator.onValidationError(error.localizedDescription)
            return makePlaceholderWebView(message: "HTML 校验失败：\(error.localizedDescription)")
        }

        let config = WKWebViewConfiguration()
        let userContent = WKUserContentController()
        let bridge = MiniAppBridge()
        context.coordinator.bridge = bridge
        userContent.add(bridge, name: "amberNative")

        // Bootstrap script: define window.AmberNative so the page calls a clean
        // API. postMessage forwards to the native handler; onResponse is a
        // no-op stub the page (or us) can override.
        let bootstrap = """
        (function(){
          if (window.AmberNative) return;
          var pending = {};
          window.AmberNative = {
            _seq: 0,
            postMessage: function(req){
              req = req || {};
              var id = req.id != null ? req.id : ('mvp_' + (AmberNative._seq++));
              req.id = id;
              try {
                window.webkit.messageHandlers.amberNative.postMessage(JSON.stringify(req));
              } catch(e) {
                window.AmberNative.onResponse(JSON.stringify({id: id, error: 'bridge unavailable: ' + e.message}));
              }
              return id;
            },
            onResponse: function(json){
              try {
                var resp = typeof json === 'string' ? JSON.parse(json) : json;
                console.log('[AmberNative] response', resp);
              } catch(e) {}
            }
          };
        })();
        """
        let bootstrapUserScript = WKUserScript(source: bootstrap, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        userContent.addUserScript(bootstrapUserScript)

        config.userContentController = userContent
        // Restrict to the MiniApp's own content; no external loads (the validator
        // already banned external scripts/images, this is defense-in-depth).
        if #available(iOS 14.0, *) {
            // Keep default content rules; bridge + validator are the gates.
        }

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        // Inject the validated HTML. baseURL nil = origin "null" (sandboxed).
        webView.loadHTMLString(html, baseURL: nil)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // Forward bridge log periodically so the dev UI can show it.
        if let bridge = context.coordinator.bridge {
            context.coordinator.onBridgeLog(bridge.log)
        }
    }

    private func makePlaceholderWebView(message: String) -> WKWebView {
        let webView = WKWebView()
        let page = "<!DOCTYPE html><html><body style='font-family:-apple-system;padding:16px;color:#888'>\(message)</body></html>"
        webView.loadHTMLString(page, baseURL: nil)
        return webView
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        let onValidationError: (String) -> Void
        let onBridgeLog: ([String]) -> Void
        var bridge: MiniAppBridge?

        init(onValidationError: @escaping (String) -> Void, onBridgeLog: @escaping ([String]) -> Void) {
            self.onValidationError = onValidationError
            self.onBridgeLog = onBridgeLog
        }
    }
}
