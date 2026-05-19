package me.rerere.rikkahub.data.agent.webmount.core

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

object WebViewCompatibility {
    fun installDocumentStartScripts(
        webView: WebView,
        urls: List<String>,
    ): List<ScriptHandler> {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return emptyList()
        }
        val origins = allowedOriginsFor(urls)
        if (origins.isEmpty()) return emptyList()
        return buildList {
            runCatching {
                add(WebViewCompat.addDocumentStartJavaScript(webView, BASE_COMPATIBILITY_SCRIPT, origins))
            }
            val siteScript = urls.firstNotNullOfOrNull { siteCompatibilityScript(it) }
            if (siteScript != null) {
                runCatching {
                    add(WebViewCompat.addDocumentStartJavaScript(webView, siteScript, origins))
                }
            }
        }
    }

    fun injectFallback(
        webView: WebView?,
        url: String?,
        allowedOrigins: Set<String>? = null,
    ) {
        if (webView == null) return
        if (allowedOrigins != null && originFor(url.orEmpty()) !in allowedOrigins) return
        webView.evaluateJavascript(BASE_COMPATIBILITY_SCRIPT, null)
        siteCompatibilityScript(url.orEmpty())?.let { script ->
            webView.evaluateJavascript(script, null)
        }
    }

    fun allowedOriginsFor(urls: List<String>): Set<String> {
        return urls.mapNotNull(::originFor).toSet()
    }

    fun siteCompatibilityScript(url: String): String? {
        val host = hostOf(url) ?: return null
        return when {
            host.isFeishuOrLarkHost() -> FEISHU_COMPAT_SCRIPT
            host.matchesDomain("zhihu.com") -> cssHider(
                id = "amber-zhihu-compat-style",
                selectors = listOf(
                    ".OpenInAppButton",
                    ".CornerButtons",
                    ".MobileModal-wrapper",
                    ".AppBanner",
                    "[class*=\"OpenInApp\"]",
                    "[class*=\"AppBanner\"]",
                ),
            )
            host.matchesDomain("bilibili.com") -> cssHider(
                id = "amber-bilibili-compat-style",
                selectors = listOf(
                    ".open-app-btn",
                    ".launch-app-btn",
                    ".m-open-app",
                    ".bili-open-app",
                    "[class*=\"open-app\"]",
                    "[class*=\"launch-app\"]",
                ),
            )
            host.matchesDomain("weibo.com") || host.matchesDomain("weibo.cn") -> cssHider(
                id = "amber-weibo-compat-style",
                selectors = listOf(
                    ".card-download",
                    ".m-download-bar",
                    ".openapp-bar",
                    "[class*=\"open-app\"]",
                    "[class*=\"download\"]",
                ),
            )
            else -> null
        }
    }

    fun originFor(url: String): String? {
        return runCatching {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if ((scheme == "http" || scheme == "https") && !host.isNullOrBlank()) {
                "$scheme://$host"
            } else {
                null
            }
        }.getOrNull()
    }

    private fun hostOf(url: String): String? {
        return runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
    }

    private fun cssHider(id: String, selectors: List<String>): String {
        val css = selectors.joinToString(",") +
            "{display:none!important;visibility:hidden!important;pointer-events:none!important;}"
        return """
            (function() {
              if (window['$id']) return;
              window['$id'] = true;
              function install() {
                try {
                  if (!document.documentElement) return;
                  if (!document.getElementById('$id')) {
                    var style = document.createElement('style');
                    style.id = '$id';
                    style.textContent = '$css';
                    (document.head || document.documentElement).appendChild(style);
                  }
                } catch (e) {}
              }
              install();
              if (document.body) {
                try { new MutationObserver(install).observe(document.body, { childList: true, subtree: true }); } catch (e) {}
              } else {
                setTimeout(install, 80);
              }
            })();
        """.trimIndent()
    }

    private fun String.isFeishuOrLarkHost(): Boolean {
        return matchesDomain("feishu.cn") ||
            matchesDomain("feishu.net") ||
            matchesDomain("larksuite.com") ||
            matchesDomain("larksuitecdn.com")
    }

    private fun String.matchesDomain(domain: String): Boolean {
        return this == domain || endsWith(".$domain")
    }

    private const val BASE_COMPATIBILITY_SCRIPT = """
        (function() {
          try {
            if (navigator.webdriver === true) {
              try { delete Object.getPrototypeOf(navigator).webdriver; } catch (e) {}
            }
            ['__wv_if','__firefox__'].forEach(function(k) {
              if (k in window) {
                try { delete window[k]; } catch (e) {}
              }
            });
            if (!navigator.languages || navigator.languages.length === 0) {
              Object.defineProperty(navigator, 'languages', {
                get: function() { return ['zh-CN','zh','en-US','en']; }
              });
            }
          } catch (e) {}
        })();
    """

    private const val FEISHU_COMPAT_SCRIPT = """
        (function() {
          if (window.__amberFeishuCompatInstalled) return;
          window.__amberFeishuCompatInstalled = true;

          function hideNotCompatible() {
            try {
              var styleId = 'amber-feishu-compat-style';
              if (!document.getElementById(styleId)) {
                var style = document.createElement('style');
                style.id = styleId;
                style.textContent = [
                  '.not-compatible__announce',
                  '.not-compatible',
                  '[class*="not-compatible"]'
                ].join(',') + '{display:none!important;visibility:hidden!important;pointer-events:none!important;}';
                (document.head || document.documentElement).appendChild(style);
              }
              document.querySelectorAll('.not-compatible__announce,.not-compatible,[class*="not-compatible"]').forEach(function(node) {
                node.hidden = true;
                node.style.setProperty('display', 'none', 'important');
                node.style.setProperty('visibility', 'hidden', 'important');
                node.style.setProperty('pointer-events', 'none', 'important');
              });
            } catch (e) {}
          }

          function installObserver() {
            hideNotCompatible();
            if (!document.body) {
              setTimeout(installObserver, 80);
              return;
            }
            new MutationObserver(hideNotCompatible).observe(document.body, {
              childList: true,
              subtree: true
            });
          }

          installObserver();
        })();
    """
}
