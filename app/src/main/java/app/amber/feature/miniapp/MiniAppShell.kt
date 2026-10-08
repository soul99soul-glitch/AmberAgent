package app.amber.feature.miniapp

import android.content.Context
import android.webkit.WebResourceResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.amber.agent.R
import app.amber.core.ai.generative.GuizangHtmlDeckValidator

object MiniAppShell {
    const val BASE_URL = "https://miniapp.amberagent.local/"

    /** Model-facing URL of the bundled three.js, shared with iOS so MiniApp HTML stays portable. */
    const val MODEL_LIB_THREE_URL = "amber-miniapp-lib://three.min.js"
    /** Android WebView won't reliably load custom-scheme scripts from an https page, so the shell rewrites to same-origin. */
    const val LIB_BASE_URL = "${BASE_URL}__amber-lib/"
    const val LIB_THREE_URL = "${LIB_BASE_URL}three.min.js"

    fun rewriteLibraryUrls(html: String): String =
        html.replace(MODEL_LIB_THREE_URL, LIB_THREE_URL, ignoreCase = true)

    fun libraryAssetPath(url: String): String? =
        if (url.equals(LIB_THREE_URL, ignoreCase = true)) GuizangHtmlDeckValidator.THREE_ASSET_PATH else null

    /** Serves app-bundled libraries from assets; returns null for any other URL. */
    fun libraryResponse(context: Context, url: String): WebResourceResponse? {
        val assetPath = libraryAssetPath(url) ?: return null
        return runCatching {
            WebResourceResponse("application/javascript", "utf-8", context.assets.open(assetPath)).apply {
                responseHeaders = mapOf("Cache-Control" to "no-store")
            }
        }.getOrNull()
    }

    fun inject(context: Context, html: String, bridgeScript: String, sessionToken: String): String {
        val tokenScript = """
            <script>
            window.__AMBER_MINIAPP_SESSION_TOKEN__ = ${Json.encodeToString(sessionToken)};
            </script>
        """.trimIndent()
        val offlineImageLabel = Json.encodeToString(
            context.getString(R.string.miniapp_image_requires_data_uri),
        )
        val guardScript = """
            <script>
            (function () {
              const block = function (name) {
                try { Object.defineProperty(window, name, { value: undefined, writable: false, configurable: false }); } catch (_) {}
              };
              block('XMLHttpRequest');
              block('WebSocket');
              block('EventSource');
              block('localStorage');
              block('sessionStorage');
              block('indexedDB');
              try { Object.defineProperty(navigator, 'geolocation', { value: undefined, writable: false, configurable: false }); } catch (_) {}
              try { Object.defineProperty(navigator, 'mediaDevices', { value: undefined, writable: false, configurable: false }); } catch (_) {}
              try { Object.defineProperty(navigator, 'clipboard', { value: undefined, writable: false, configurable: false }); } catch (_) {}
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
                    img.src = makeOfflineImage($offlineImageLabel);
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
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' $LIB_BASE_URL; style-src 'unsafe-inline'; img-src data: https:; connect-src 'none'; font-src data:;">
            $tokenScript
            $guardScript
            <script>
            $bridgeScript
            </script>
        """.trimIndent()
        return "$prefix\n${rewriteLibraryUrls(html)}"
    }
}
