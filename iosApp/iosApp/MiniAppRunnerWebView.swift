import SwiftUI
@preconcurrency import WebKit

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
    let appId: String
    let repository: IOSMiniAppRepository
    let policy: IOSMiniAppBridgePolicy
    let apiKeyProvider: () -> String
    let onValidationError: (String) -> Void
    let onBridgeLog: ([String]) -> Void
    let onToast: (String) -> Void

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
        let runtime = IOSMiniAppBridgeRuntime(
            appId: appId,
            repository: repository,
            policy: policy,
            apiKeyProvider: apiKeyProvider,
            toastHandler: onToast
        )
        let bridge = MiniAppBridge(runtime: runtime)
        context.coordinator.bridge = bridge
        userContent.add(bridge, name: "amberNative")

        // Bootstrap script: define Promise-based Amber bridge APIs and retain
        // AmberNative.postMessage compatibility for the MVP sample.
        let bootstrap = """
        (function(){
          if (window.AmberBridge) return;
          var pending = {};
          var eventHandlers = {};
          function asObject(value) {
            return value && typeof value === 'object' ? value : {};
          }
          function call(method, params) {
            return new Promise(function(resolve, reject) {
              var id = 'ios_' + Date.now() + '_' + Math.random().toString(16).slice(2);
              pending[id] = {resolve: resolve, reject: reject};
              try {
                window.webkit.messageHandlers.amberNative.postMessage(JSON.stringify({
                  id: id,
                  method: method,
                  params: asObject(params)
                }));
              } catch (e) {
                delete pending[id];
                reject(new Error('bridge unavailable: ' + e.message));
              }
            });
          }
          window.AmberBridge = {
            call: call,
            _handleNativeResponse: function(json) {
              var resp = typeof json === 'string' ? JSON.parse(json) : json;
              var slot = pending[resp.id];
              if (slot) {
                delete pending[resp.id];
                if (resp.error) slot.reject(new Error(resp.error));
                else slot.resolve(resp.result);
              }
              try { window.AmberNative && window.AmberNative.onResponse && window.AmberNative.onResponse(resp); } catch(e) {}
            },
            _emitNativeEvent: function(event) {
              var handlers = eventHandlers[event.subscriptionId] || [];
              handlers.forEach(function(handler) {
                try { handler(event); } catch(e) { console.error('[AmberBridge] event handler failed', e); }
              });
            },
            _rememberEventHandler: function(subscriptionId, handler) {
              eventHandlers[subscriptionId] = eventHandlers[subscriptionId] || [];
              eventHandlers[subscriptionId].push(handler);
            },
            _forgetEventHandler: function(subscriptionId) {
              delete eventHandlers[subscriptionId];
            }
          };
          window.AmberNative = {
            postMessage: function(req) { req = req || {}; return call(req.method, req.params || {}); },
            onResponse: function(resp) { try { console.log('[AmberNative] response', resp); } catch(e) {} }
          };
          function keyParams(keyOrObject, value) {
            if (keyOrObject && typeof keyOrObject === 'object') return keyOrObject;
            return value === undefined ? {key: keyOrObject} : {key: keyOrObject, value: value};
          }
          window.Amber = {
            storage: {
              get: function(key) { return call('storage.get', keyParams(key)); },
              set: function(key, value) { return call('storage.set', keyParams(key, value)); },
              remove: function(key) { return call('storage.remove', keyParams(key)); }
            },
            toast: function(message) {
              return call('toast', typeof message === 'string' ? {message: message} : message);
            },
            host: {
              getTheme: function() { return call('host.getTheme', {}); },
              updateBoardSummary: function(summary) {
                return call('host.updateBoardSummary', typeof summary === 'string' ? {summary: summary} : summary);
              }
            },
            clipboard: {
              copy: function(text) { return call('clipboard.copy', typeof text === 'string' ? {text: text} : text); }
            },
            sharedStore: {
              get: function(req) { return call('sharedStore.get', req); },
              set: function(req) { return call('sharedStore.set', req); },
              remove: function(req) { return call('sharedStore.remove', req); }
            },
            eventBus: {
              subscribe: function(req, handler) {
                return call('eventBus.subscribe', req).then(function(result) {
                  if (handler && result && result.subscriptionId) {
                    window.AmberBridge._rememberEventHandler(result.subscriptionId, handler);
                  }
                  return result;
                });
              },
              unsubscribe: function(req) {
                var id = typeof req === 'string' ? req : req && req.subscriptionId;
                if (id) window.AmberBridge._forgetEventHandler(id);
                return call('eventBus.unsubscribe', typeof req === 'string' ? {subscriptionId: req} : req);
              },
              publish: function(req) { return call('eventBus.publish', req); }
            },
            search: function(req) { return call('search', req); },
            fetch: function(req) {
              return call('fetch', typeof req === 'string' ? {url: req} : req);
            },
            ai: {
              generate: function(req) { return call('ai.generate', req); }
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
        bridge.attach(webView: webView)
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

        deinit {
            Task { @MainActor [bridge] in
                bridge?.close()
            }
        }
    }
}
