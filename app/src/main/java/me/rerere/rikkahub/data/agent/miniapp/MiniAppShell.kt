package me.rerere.rikkahub.data.agent.miniapp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MiniAppShell {
    const val BASE_URL = "https://miniapp.amberagent.local/"

    fun inject(html: String, bridgeScript: String, sessionToken: String): String {
        val tokenScript = """
            <script>
            window.__AMBER_MINIAPP_SESSION_TOKEN__ = ${Json.encodeToString(sessionToken)};
            </script>
        """.trimIndent()
        val guardScript = """
            <script>
            (function () {
              const block = function (name) {
                try { Object.defineProperty(window, name, { value: undefined, writable: false, configurable: false }); } catch (_) {}
              };
              block('fetch');
              block('XMLHttpRequest');
              block('WebSocket');
              block('EventSource');
              block('localStorage');
              block('sessionStorage');
              block('indexedDB');
              try { Object.defineProperty(navigator, 'geolocation', { value: undefined, writable: false, configurable: false }); } catch (_) {}
              try { Object.defineProperty(navigator, 'mediaDevices', { value: undefined, writable: false, configurable: false }); } catch (_) {}
              const makeOfflineImage = function (label) {
                const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="800" height="480" viewBox="0 0 800 480"><defs><linearGradient id="g" x1="0" x2="1" y1="0" y2="1"><stop stop-color="#f3f4f6"/><stop offset="1" stop-color="#e5e7eb"/></linearGradient></defs><rect width="800" height="480" fill="url(#g)"/><text x="50%" y="50%" text-anchor="middle" dominant-baseline="middle" fill="#6b7280" font-size="28" font-family="sans-serif">' + label + '</text></svg>';
                return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
              };
              const normalizeImages = function () {
                document.querySelectorAll('img').forEach(function (img) {
                  const raw = img.getAttribute('src') || '';
                  const normalized = raw.trim().toLowerCase();
                  if (!(normalized.startsWith('data:image/') || normalized.startsWith('https://'))) {
                    img.setAttribute('data-amber-original-src', raw);
                    img.src = makeOfflineImage('图片需使用内联 data URI');
                  }
                });
              };
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', normalizeImages, { once: true });
              } else {
                normalizeImages();
              }
              try {
                new MutationObserver(normalizeImages).observe(document.documentElement, {
                  childList: true,
                  subtree: true,
                  attributes: true,
                  attributeFilter: ['src', 'srcset']
                });
              } catch (_) {}
            })();
            </script>
        """.trimIndent()
        val prefix = """
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: https:; connect-src 'none'; font-src data:;">
            $tokenScript
            $guardScript
            <script>
            $bridgeScript
            </script>
        """.trimIndent()
        return "$prefix\n$html"
    }
}
