package me.rerere.rikkahub.data.agent.webmount.core

import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Small compatibility shims for login WebViews.
 *
 * Keep this narrow: WebMount is still a normal Android WebView, but a few
 * sites treat embedded browsers as unsupported even when the underlying
 * Chromium engine can render the page. Feishu Docs is the canonical example.
 */
object WebMountWebViewCompat {
    fun applyBrowserLikeSettings(settings: WebSettings) {
        settings.setSupportMultipleWindows(true)
        // Best-effort privacy / compatibility tweak. Older WebView versions
        // exposed the embedding app via X-Requested-With. Newer WebViews no
        // longer honor this method, so reflection keeps this dependency-free.
        runCatching {
            val method = settings.javaClass.methods.firstOrNull { method ->
                method.name == "setRequestedWithHeaderOriginAllowList" &&
                    method.parameterTypes.size == 1
            } ?: return@runCatching
            method.invoke(settings, emptySet<String>())
        }
    }

    fun injectFeishuCompatibility(view: WebView?, url: String?) {
        if (view == null || !url.orEmpty().isFeishuOrLarkUrl()) return
        view.evaluateJavascript(FEISHU_COMPAT_SCRIPT, null)
    }

    internal fun String.isFeishuOrLarkUrl(): Boolean {
        val normalized = lowercase()
        return normalized.contains("feishu.cn") ||
            normalized.contains("feishu.net") ||
            normalized.contains("larksuite.com") ||
            normalized.contains("larksuitecdn.com")
    }

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
