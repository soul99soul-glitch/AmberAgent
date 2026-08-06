import SwiftUI
@preconcurrency import WebKit

enum IOSMiniAppHTMLSandbox {
    static func enforceBridgeOnlyNetwork(_ html: String, allowExternalImages: Bool = false) -> String {
        let imageSources = allowExternalImages ? "data: blob: amber-miniapp-image:" : "data: blob:"
        let policy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src \(imageSources); font-src data:; connect-src 'none'; media-src data: blob:; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"
        let meta = #"<meta http-equiv="Content-Security-Policy" content="\#(policy)">"#
        let options: NSString.CompareOptions = [.caseInsensitive, .regularExpression]
        let fullRange = NSRange(location: 0, length: (html as NSString).length)
        let headRange = (html as NSString).range(of: #"<head\b[^>]*>"#, options: options, range: fullRange)
        if headRange.location != NSNotFound {
            let insertion = headRange.location + headRange.length
            return (html as NSString).replacingCharacters(in: NSRange(location: insertion, length: 0), with: meta)
        }
        let htmlRange = (html as NSString).range(of: #"<html\b[^>]*>"#, options: options, range: fullRange)
        if htmlRange.location != NSNotFound {
            let insertion = htmlRange.location + htmlRange.length
            return (html as NSString).replacingCharacters(
                in: NSRange(location: insertion, length: 0),
                with: "<head>\(meta)</head>"
            )
        }
        return "<head>\(meta)</head>\(html)"
    }
}

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
    let theme: IOSMiniAppThemePayload
    let aiGenerateHandler: IOSMiniAppBridgeRuntime.AIGenerateHandler?
    let hostHandler: IOSMiniAppBridgeRuntime.HostHandler?
    let launchHandler: IOSMiniAppBridgeRuntime.LaunchHandler?
    let locationHandler: IOSMiniAppBridgeRuntime.LocationHandler?
    let sensorSubscribeHandler: IOSMiniAppBridgeRuntime.SensorSubscribeHandler?
    let sensorUnsubscribeHandler: IOSMiniAppBridgeRuntime.SensorUnsubscribeHandler?
    let sensitiveConfirmationHandler: IOSMiniAppBridgeRuntime.SensitiveConfirmationHandler?
    var grantHandler: IOSMiniAppBridgeRuntime.GrantHandler? = nil
    let onValidationError: (String) -> Void
    let onBridgeLog: ([String]) -> Void
    let onToast: (String) -> Void
    let onClose: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            onValidationError: onValidationError,
            onBridgeLog: onBridgeLog,
            onClose: onClose,
            onBlockedNavigation: { url in
                onToast("已阻止小应用打开外部链接：\(url.host ?? url.absoluteString)")
            }
        )
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

        let externalImagesAllowed = allowsExternalImages
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        if externalImagesAllowed {
            config.setURLSchemeHandler(
                IOSMiniAppImageSchemeHandler(),
                forURLScheme: IOSMiniAppImageSchemeHandler.scheme
            )
        }
        let userContent = WKUserContentController()
        if externalImagesAllowed {
            userContent.addUserScript(WKUserScript(
                source: IOSMiniAppImageSchemeHandler.bootstrapScript,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: true
            ))
        }
        let runtime = IOSMiniAppBridgeRuntime(
            appId: appId,
            repository: repository,
            policy: policy,
            aiGenerateHandler: aiGenerateHandler,
            hostHandler: hostHandler,
            grantHandler: grantHandler,
            launchHandler: launchHandler,
            locationHandler: locationHandler,
            sensorSubscribeHandler: sensorSubscribeHandler,
            sensorUnsubscribeHandler: sensorUnsubscribeHandler,
            sensitiveConfirmationHandler: sensitiveConfirmationHandler,
            toastHandler: onToast,
            themeProvider: { theme }
        )
        let bridge = MiniAppBridge(
            runtime: runtime,
            onLogChanged: context.coordinator.onBridgeLog
        )
        context.coordinator.bridge = bridge
        userContent.add(bridge, name: "amberNative")

        // Bootstrap script: define Promise-based Amber bridge APIs and retain
        // AmberNative.postMessage compatibility for the MVP sample.
        let bootstrap = """
        (function(){
          if (window.AmberBridge) return;
          var pending = {};
          var eventHandlers = {};
          var sensorHandlers = {};
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
              var source = event && event.type === 'sensor' ? sensorHandlers : eventHandlers;
              var handlers = source[event.subscriptionId] || [];
              var payload = event.payload;
              var delivered;
              if (payload && typeof payload === 'object' && !Array.isArray(payload)) {
                delivered = Object.assign({}, payload, {
                  type: event.type,
                  subscriptionId: event.subscriptionId,
                  payload: payload
                });
              } else {
                delivered = {
                  type: event.type,
                  subscriptionId: event.subscriptionId,
                  payload: payload,
                  value: payload
                };
              }
              handlers.forEach(function(handler) {
                try { handler(delivered); } catch(e) { console.error('[AmberBridge] event handler failed', e); }
              });
            },
            _rememberEventHandler: function(subscriptionId, handler) {
              eventHandlers[subscriptionId] = eventHandlers[subscriptionId] || [];
              eventHandlers[subscriptionId].push(handler);
            },
            _forgetEventHandler: function(subscriptionId) {
              delete eventHandlers[subscriptionId];
            },
            _rememberSensorHandler: function(subscriptionId, handler) {
              sensorHandlers[subscriptionId] = sensorHandlers[subscriptionId] || [];
              sensorHandlers[subscriptionId].push(handler);
            },
            _forgetSensorHandler: function(subscriptionId) {
              delete sensorHandlers[subscriptionId];
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
              },
              getConversationContext: function(req) {
                return call('host.getConversationContext', req || {});
              },
              sendToConversation: function(req) {
                return call('host.sendToConversation', typeof req === 'string' ? {text: req} : req);
              },
              createArtifact: function(req) {
                return call('host.createArtifact', req);
              }
            },
            clipboard: {
              copy: function(text) { return call('clipboard.copy', typeof text === 'string' ? {text: text} : text); },
              read: function() { return call('clipboard.read', {}); }
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
            search: function(req) {
              return call('search', req).then(function(result) {
                var items = Array.isArray(result) ? result : result && Array.isArray(result.items) ? result.items : [];
                items.items = items;
                return items;
              });
            },
            fetch: function(req) {
              return call('fetch', typeof req === 'string' ? {url: req} : req);
            },
            ai: {
              generate: function(req) { return call('ai.generate', req); }
            },
            launch: function(req) { return call('launch', req || {}); },
            sensor: {
              subscribe: function(req, handler) {
                return call('sensor.subscribe', req || {}).then(function(result) {
                  if (handler && result && result.subscriptionId) {
                    window.AmberBridge._rememberSensorHandler(result.subscriptionId, handler);
                  }
                  return {
                    subscriptionId: result.subscriptionId,
                    unsubscribe: function() {
                      window.AmberBridge._forgetSensorHandler(result.subscriptionId);
                      return call('sensor.unsubscribe', {subscriptionId: result.subscriptionId});
                    }
                  };
                });
              }
            },
            location: {
              getCurrent: function(req) { return call('location.getCurrent', req || {}); }
            }
          };

          function headersToObject(headers) {
            var out = {};
            if (!headers) return out;
            if (typeof headers.forEach === 'function') {
              headers.forEach(function(value, key) { out[key] = String(value); });
            } else if (Array.isArray(headers)) {
              headers.forEach(function(pair) {
                if (Array.isArray(pair) && pair.length >= 2) out[String(pair[0])] = String(pair[1]);
              });
            } else if (typeof headers === 'object') {
              Object.keys(headers).forEach(function(key) { out[key] = String(headers[key]); });
            }
            return out;
          }
          function bridgeFetch(input, init) {
            var url = typeof input === 'string' ? input : input && input.url;
            var options = init || {};
            return call('fetch', {
              url: String(url || ''),
              method: options.method || 'GET',
              headers: headersToObject(options.headers),
              body: typeof options.body === 'string' ? options.body : undefined,
              responseType: 'text'
            }).then(function(result) {
              var body = String(result && (result.body !== undefined ? result.body : result.text) || '');
              return Object.freeze({
                ok: !!(result && result.ok),
                status: result && result.status || 0,
                url: result && result.url || String(url || ''),
                text: function() { return Promise.resolve(body); },
                json: function() { return Promise.resolve(JSON.parse(body || 'null')); }
              });
            });
          }
          try {
            Object.defineProperty(window, 'fetch', {value: bridgeFetch, writable: false, configurable: false});
          } catch (_) {}
        })();
        """
        let bootstrapUserScript = WKUserScript(source: bootstrap, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        userContent.addUserScript(bootstrapUserScript)

        config.userContentController = userContent
        // Restrict navigation and script/network channels. HTTPS images are only
        // enabled for apps that declare the capability and pass runtime policy.
        if #available(iOS 14.0, *) {
            // Keep default content rules; bridge + validator are the gates.
        }

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.isInspectable = policy.webViewDebugEnabled
        webView.navigationDelegate = context.coordinator
        bridge.attach(webView: webView)
        // Inject the validated HTML. baseURL nil = origin "null" (sandboxed).
        context.coordinator.loadedHTML = html
        context.coordinator.externalImagesAllowed = externalImagesAllowed
        webView.loadHTMLString(
            IOSMiniAppHTMLSandbox.enforceBridgeOnlyNetwork(html, allowExternalImages: externalImagesAllowed),
            baseURL: nil
        )
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        webView.isInspectable = policy.webViewDebugEnabled
        let externalImagesAllowed = allowsExternalImages
        if context.coordinator.loadedHTML != html ||
            context.coordinator.externalImagesAllowed != externalImagesAllowed {
            do {
                try MiniAppHtmlValidator.validate(html)
                context.coordinator.loadedHTML = html
                context.coordinator.externalImagesAllowed = externalImagesAllowed
                webView.loadHTMLString(
                    IOSMiniAppHTMLSandbox.enforceBridgeOnlyNetwork(
                        html,
                        allowExternalImages: externalImagesAllowed
                    ),
                    baseURL: nil
                )
            } catch {
                onValidationError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
            }
        }
    }

    private var allowsExternalImages: Bool {
        guard policy.miniAppEnabled,
              policy.externalImagesEnabled,
              let app = repository.get(appId),
              app.permissions.contains(IOSMiniAppPermission.externalImages.rawValue) else {
            return false
        }
        return repository.grantDecision(appId: appId, permission: IOSMiniAppPermission.externalImages.rawValue) != .deny
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.stopLoading()
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "amberNative")
        webView.navigationDelegate = nil
        coordinator.close()
    }

    private func makePlaceholderWebView(message: String) -> WKWebView {
        let webView = WKWebView()
        let page = "<!DOCTYPE html><html><head><meta name='color-scheme' content='light dark'></head><body style='font-family:-apple-system;padding:16px;background:transparent;color:CanvasText'>\(message)</body></html>"
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.loadHTMLString(page, baseURL: nil)
        return webView
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        let onValidationError: (String) -> Void
        let onBridgeLog: ([String]) -> Void
        let onClose: () -> Void
        let onBlockedNavigation: (URL) -> Void
        var bridge: MiniAppBridge?
        var loadedHTML = ""
        var externalImagesAllowed = false
        private var isClosed = false

        init(
            onValidationError: @escaping (String) -> Void,
            onBridgeLog: @escaping ([String]) -> Void,
            onClose: @escaping () -> Void,
            onBlockedNavigation: @escaping (URL) -> Void
        ) {
            self.onValidationError = onValidationError
            self.onBridgeLog = onBridgeLog
            self.onClose = onClose
            self.onBlockedNavigation = onBlockedNavigation
        }

        func close() {
            guard !isClosed else { return }
            isClosed = true
            bridge?.close()
            bridge = nil
            onClose()
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }
            let scheme = url.scheme?.lowercased() ?? ""
            if scheme == "about" || scheme == "data" || scheme == "blob" {
                decisionHandler(.allow)
                return
            }
            onBlockedNavigation(url)
            decisionHandler(.cancel)
        }

        deinit {
            Task { @MainActor [bridge] in
                bridge?.close()
            }
        }
    }
}

@MainActor
final class IOSMiniAppImageSchemeHandler: NSObject, WKURLSchemeHandler {
    static let scheme = "amber-miniapp-image"
    static let maximumResponseBytes = 2 * 1_024 * 1_024

    static let bootstrapScript = #"""
    (function(){
      var scheme = 'amber-miniapp-image://load?url=';
      function proxy(value) {
        if (typeof value !== 'string') return value;
        try {
          var url = new URL(value, document.baseURI);
          return url.protocol === 'https:' ? scheme + encodeURIComponent(url.href) : value;
        } catch (_) { return value; }
      }
      function proxySrcset(value) {
        if (typeof value !== 'string') return value;
        return value.split(',').map(function(candidate) {
          var match = candidate.trim().match(/^(https:\/\/\S+)(\s+.+)?$/i);
          return match ? proxy(match[1]) + (match[2] || '') : candidate;
        }).join(', ');
      }
      function proxyCSS(value) {
        if (typeof value !== 'string') return value;
        return value.replace(/url\(\s*(['"]?)(https:\/\/[^)'"\s]+)\1\s*\)/gi, function(_, quote, url) {
          return 'url("' + proxy(url) + '")';
        });
      }
      var originalSetAttribute = Element.prototype.setAttribute;
      Element.prototype.setAttribute = function(name, value) {
        var lower = String(name).toLowerCase();
        if ((this instanceof HTMLImageElement || this instanceof HTMLSourceElement) && lower === 'src') value = proxy(value);
        if ((this instanceof HTMLImageElement || this instanceof HTMLSourceElement) && lower === 'srcset') value = proxySrcset(value);
        if (lower === 'style') value = proxyCSS(value);
        return originalSetAttribute.call(this, name, value);
      };
      function rewrite(root) {
        if (!root || root.nodeType !== 1) return;
        var elements = [root].concat(Array.prototype.slice.call(root.querySelectorAll ? root.querySelectorAll('img,source,[style],style') : []));
        elements.forEach(function(element) {
          if (element instanceof HTMLImageElement || element instanceof HTMLSourceElement) {
            if (element.hasAttribute('src')) originalSetAttribute.call(element, 'src', proxy(element.getAttribute('src')));
            if (element.hasAttribute('srcset')) originalSetAttribute.call(element, 'srcset', proxySrcset(element.getAttribute('srcset')));
          }
          if (element.hasAttribute && element.hasAttribute('style')) originalSetAttribute.call(element, 'style', proxyCSS(element.getAttribute('style')));
          if (element.tagName === 'STYLE' && element.textContent) element.textContent = proxyCSS(element.textContent);
        });
      }
      new MutationObserver(function(records) {
        records.forEach(function(record) {
          if (record.type === 'attributes') rewrite(record.target);
          record.addedNodes && record.addedNodes.forEach(rewrite);
        });
      }).observe(document.documentElement, {subtree:true, childList:true, attributes:true, attributeFilter:['src','srcset','style']});
      document.addEventListener('DOMContentLoaded', function(){ rewrite(document.documentElement); }, {once:true});
    })();
    """#

    private let transport: any IOSSearchHTTPTransport
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]

    init(transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()) {
        self.transport = transport
    }

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        let key = ObjectIdentifier(urlSchemeTask as AnyObject)
        guard let requestURL = urlSchemeTask.request.url,
              let originalURLString = URLComponents(url: requestURL, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "url" })?.value else {
            respondPlaceholder(to: urlSchemeTask)
            return
        }

        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let originalURL = try IOSSearchExecutor.allowedPublicHTTPURL(from: originalURLString)
                guard originalURL.scheme?.lowercased() == "https" else {
                    throw IOSSearchExecutorError.disallowedURL("MiniApp images require https")
                }
                var request = URLRequest(url: originalURL)
                request.timeoutInterval = 15
                request.cachePolicy = .returnCacheDataElseLoad
                request.setValue(
                    "image/avif,image/webp,image/png,image/jpeg,image/svg+xml,image/*;q=0.8",
                    forHTTPHeaderField: "Accept"
                )
                let (response, data) = try await transport.sendPublic(
                    request,
                    maximumResponseBytes: Self.maximumResponseBytes
                )
                guard (200...299).contains(response.statusCode),
                      response.mimeType?.lowercased().hasPrefix("image/") == true else {
                    throw IOSSearchExecutorError.invalidHTTPResponse
                }
                guard self.tasks.removeValue(forKey: key) != nil else { return }
                let schemeResponse = URLResponse(
                    url: requestURL,
                    mimeType: response.mimeType,
                    expectedContentLength: data.count,
                    textEncodingName: nil
                )
                urlSchemeTask.didReceive(schemeResponse)
                urlSchemeTask.didReceive(data)
                urlSchemeTask.didFinish()
            } catch is CancellationError {
                self.tasks.removeValue(forKey: key)
            } catch {
                guard self.tasks.removeValue(forKey: key) != nil else { return }
                self.respondPlaceholder(to: urlSchemeTask)
            }
        }
        tasks[key] = task
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        let key = ObjectIdentifier(urlSchemeTask as AnyObject)
        tasks.removeValue(forKey: key)?.cancel()
    }

    private func respondPlaceholder(to urlSchemeTask: WKURLSchemeTask) {
        let data = Data(Self.placeholderSVG.utf8)
        let url = urlSchemeTask.request.url ?? URL(string: "\(Self.scheme)://blocked")!
        urlSchemeTask.didReceive(URLResponse(
            url: url,
            mimeType: "image/svg+xml",
            expectedContentLength: data.count,
            textEncodingName: "utf-8"
        ))
        urlSchemeTask.didReceive(data)
        urlSchemeTask.didFinish()
    }

    private static let placeholderSVG = """
    <svg xmlns="http://www.w3.org/2000/svg" width="800" height="480" viewBox="0 0 800 480">
      <rect width="800" height="480" fill="#e5e7eb"/>
      <text x="50%" y="50%" text-anchor="middle" dominant-baseline="middle" fill="#6b7280" font-size="28" font-family="-apple-system,sans-serif">图片无法加载</text>
    </svg>
    """
}
