package app.amber.feature.webmount.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.core.WebMountUserAgents
import app.amber.feature.webmount.core.WebMountWebViewCompat
import app.amber.feature.webmount.core.WebViewCompatibility

data class WebMountLoginWebViewState(
    val currentUrl: String,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val blockedNavigation: String? = null,
    val renderProcessGone: Boolean = false,
    val webViewGeneration: Int = 0,
)

@SuppressLint("SetJavaScriptEnabled")
class WebMountLoginController(
    context: Context,
    private val target: WebMountLoginTarget,
    private val cookieProvider: WebMountCookieProvider,
    private val onStateChange: (WebMountLoginWebViewState) -> Unit,
    private val onLoginStatus: (WebMountLoginStatus) -> Unit,
) {
    private val webViewContext = context
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val scriptUrls = (target.urls + target.startUrl).distinct()
    private val allowedOrigins = WebViewCompatibility.allowedOriginsFor(scriptUrls)
    private val scriptHandlers = mutableMapOf<WebView, List<ScriptHandler>>()
    private val ownedWebViews = mutableListOf<WebView>()
    private var destroyed = false
    private var state = WebMountLoginWebViewState(currentUrl = target.startUrl)
    private val initialWebView = createWebView()
    private var rootWebView: WebView = initialWebView

    var webView: WebView = initialWebView
        private set

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            evaluateLogin(delayForSuccessUrl = false)
            handler.postDelayed(this, 500L)
        }
    }

    fun start() {
        onStateChange(state)
        webView.loadUrl(target.startUrl)
        handler.postDelayed(pollRunnable, 500L)
    }

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun goForward() {
        if (webView.canGoForward()) webView.goForward()
    }

    fun reload() {
        state = state.copy(renderProcessGone = false, blockedNavigation = null)
        onStateChange(state)
        val currentUrl = webView.url ?: state.currentUrl.takeIf { it.isNotBlank() } ?: target.startUrl
        if (webView.url == null || state.progress == 0) {
            webView.loadUrl(currentUrl)
        } else {
            webView.reload()
        }
    }

    fun clearSession() {
        webView.stopLoading()
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        webView.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null)
        webView.loadUrl(target.startUrl)
    }

    fun manualCheck(): WebMountLoginStatus {
        return evaluateLogin(delayForSuccessUrl = false)
    }

    fun importCookies(
        cookies: Map<String, String>,
        onComplete: (WebMountLoginStatus) -> Unit,
    ) {
        cookieProvider.injectCookiesAsync(
            urls = target.urls,
            cookies = cookies,
            fieldHints = target.manualCookieFields,
        ) {
            onComplete(manualCheck())
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        removeAllScriptHandlers()
        CookieManager.getInstance().flush()
        ownedWebViews.toList().forEach(::destroyManagedWebView)
        ownedWebViews.clear()
    }

    private fun client(): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return shouldBlockNavigation(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return url?.let { shouldBlockNavigation(it) } == true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                updateState(url = url, progress = 0)
                WebViewCompatibility.injectFallback(view, url, allowedOrigins)
                scheduleLoginCheck(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                updateState(url = url)
                scheduleLoginCheck(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                WebViewCompatibility.injectFallback(view, url, allowedOrigins)
                updateState(url = url, progress = 100)
                scheduleLoginCheck(url)
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                replaceCrashedWebView(view ?: webView)
                return true
            }
        }
    }

    private fun chromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                updateState(progress = newProgress)
                if (newProgress >= 25) {
                    WebViewCompatibility.injectFallback(view, view?.url, allowedOrigins)
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = createWebView()
                switchActiveWebView(popup)
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                val closing = window ?: return
                if (closing === rootWebView) return
                val wasActive = closing === webView
                destroyManagedWebView(closing)
                if (wasActive && !destroyed) {
                    switchActiveWebView(rootWebView)
                }
            }
        }
    }

    private fun shouldBlockNavigation(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        val allowed = scheme == "http" || scheme == "https" || scheme == "about" || scheme == null
        if (!allowed) {
            state = state.copy(blockedNavigation = url)
            onStateChange(state)
            return true
        }
        return false
    }

    private fun scheduleLoginCheck(url: String?) {
        val delay = if (url != null && WebMountLoginDetector.isSuccessUrl(target, url)) 1_500L else 0L
        handler.postDelayed({ evaluateLogin(delayForSuccessUrl = false) }, delay)
    }

    private fun evaluateLogin(delayForSuccessUrl: Boolean): WebMountLoginStatus {
        if (destroyed) return WebMountLoginStatus.Failed("Login WebView was destroyed")
        val currentUrl = webView.url ?: state.currentUrl
        if (delayForSuccessUrl && WebMountLoginDetector.isSuccessUrl(target, currentUrl)) {
            return WebMountLoginStatus.UrlMatched(currentUrl).also(onLoginStatus)
        }
        val snapshot = cookieProvider.snapshotCookies(target.urls)
        val status = WebMountLoginDetector.evaluate(target, currentUrl, snapshot)
        onLoginStatus(status)
        return status
    }

    private fun createWebView(): WebView {
        val view = WebView(webViewContext).apply {
            settings.applyLoginSettings(appContext, target)
            webChromeClient = chromeClient()
            webViewClient = client()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        scriptHandlers[view] = WebViewCompatibility.installDocumentStartScripts(
            webView = view,
            urls = scriptUrls,
        )
        ownedWebViews += view
        return view
    }

    private fun replaceCrashedWebView(crashedWebView: WebView) {
        val wasRoot = crashedWebView === rootWebView
        val wasActive = crashedWebView === webView
        val replacement = if (wasRoot || wasActive) createWebView() else null
        if (wasRoot && replacement != null) {
            rootWebView = replacement
        }
        destroyManagedWebView(crashedWebView)
        if (wasActive && replacement != null) {
            webView = replacement
            state = state.copy(
                currentUrl = replacement.url ?: state.currentUrl,
                renderProcessGone = true,
                progress = 0,
                canGoBack = false,
                canGoForward = false,
                webViewGeneration = state.webViewGeneration + 1,
            )
            onStateChange(state)
        }
    }

    private fun switchActiveWebView(next: WebView) {
        if (webView !== next) {
            detachFromParent(webView)
        }
        webView = next
        state = state.copy(
            currentUrl = next.url ?: state.currentUrl,
            progress = 0,
            canGoBack = next.canGoBack(),
            canGoForward = next.canGoForward(),
            renderProcessGone = false,
            webViewGeneration = state.webViewGeneration + 1,
        )
        onStateChange(state)
    }

    private fun destroyManagedWebView(view: WebView) {
        detachFromParent(view)
        removeScriptHandlers(view)
        ownedWebViews.remove(view)
        runCatching {
            view.stopLoading()
            view.settings.javaScriptEnabled = false
            view.destroy()
        }
    }

    private fun detachFromParent(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun removeScriptHandlers(view: WebView) {
        scriptHandlers.remove(view)?.forEach { scriptHandler -> runCatching { scriptHandler.remove() } }
    }

    private fun removeAllScriptHandlers() {
        scriptHandlers.values.flatten().forEach { scriptHandler -> runCatching { scriptHandler.remove() } }
        scriptHandlers.clear()
    }

    private fun updateState(
        url: String? = null,
        progress: Int? = null,
    ) {
        state = state.copy(
            currentUrl = url ?: webView.url ?: state.currentUrl,
            progress = progress ?: state.progress,
            canGoBack = webView.canGoBack(),
            canGoForward = webView.canGoForward(),
            renderProcessGone = false,
        )
        onStateChange(state)
    }

    @Suppress("DEPRECATION")
    private fun WebSettings.applyLoginSettings(
        context: Context,
        target: WebMountLoginTarget,
    ) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadsImagesAutomatically = true
        cacheMode = WebSettings.LOAD_DEFAULT
        useWideViewPort = true
        loadWithOverviewMode = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        builtInZoomControls = true
        displayZoomControls = false
        WebMountWebViewCompat.applyBrowserLikeSettings(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeBrowsingEnabled = true
        }
        userAgentString = WebMountUserAgents.loginUserAgent(context, target.stationId ?: target.id, target.startUrl)
    }

    companion object {
        fun parseCookieInput(raw: String): Map<String, String> {
            return raw.split(";")
                .map { it.trim() }
                .filter { it.contains("=") }
                .mapNotNull { pair ->
                    val name = pair.substringBefore("=").trim()
                    val value = pair.substringAfter("=").trim()
                    if (name.isBlank() || value.isBlank()) null else name to value
                }
                .toMap()
        }
    }
}
