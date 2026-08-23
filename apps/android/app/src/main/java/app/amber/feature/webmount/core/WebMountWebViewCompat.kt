package app.amber.feature.webmount.core

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

    @Deprecated(
        message = "Use WebViewCompatibility.injectFallback; kept for older WebMount call sites.",
        replaceWith = ReplaceWith("WebViewCompatibility.injectFallback(view, url)"),
    )
    fun injectFeishuCompatibility(view: WebView?, url: String?) {
        if (view == null || !url.orEmpty().isFeishuOrLarkUrl()) return
        WebViewCompatibility.injectFallback(view, url)
    }

    internal fun String.isFeishuOrLarkUrl(): Boolean {
        val normalized = lowercase()
        return normalized.contains("feishu.cn") ||
            normalized.contains("feishu.net") ||
            normalized.contains("larksuite.com") ||
            normalized.contains("larksuitecdn.com")
    }
}
