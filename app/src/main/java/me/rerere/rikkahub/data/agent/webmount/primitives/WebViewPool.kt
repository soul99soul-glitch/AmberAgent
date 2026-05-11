package me.rerere.rikkahub.data.agent.webmount.primitives

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Headless WebView pool serving WebMount Browser Primitives.
 *
 * Each entry pairs a single Android [WebView] with a [JsBridge] and a
 * [SessionHandle]. WebViews live for the app process lifetime (Application
 * context); LRU evicts down to [maxSessions] when a new session is acquired
 * past the cap.
 *
 * Threading: every method that touches a WebView dispatches onto the main
 * thread, since WebView requires it. The pool internals (maps, atomic flags)
 * are thread-safe so multiple agent coroutines can call [acquire] / [release]
 * in parallel.
 */
class WebViewPool(
    private val appContext: Context,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val userAgent: String? = DEFAULT_USER_AGENT,
    private val webContentsDebugging: Boolean = false,
) {
    private val sessions = ConcurrentHashMap<String, SessionHandle>()
    private val lru = LinkedHashSet<String>()      // protected by [lruLock]
    private val lruLock = Any()
    private val bridgeBootstrapJs: String by lazy { loadBridgeBootstrap() }

    init {
        if (webContentsDebugging) {
            try {
                WebView.setWebContentsDebuggingEnabled(true)
            } catch (e: Throwable) {
                Log.w(TAG, "setWebContentsDebuggingEnabled failed", e)
            }
        }
    }

    /** Acquire by id (returns existing if present). */
    suspend fun acquire(sessionId: String): SessionHandle {
        sessions[sessionId]?.let {
            touch(sessionId)
            return it
        }
        return createSession(sessionId)
    }

    /** Acquire with a freshly generated id. */
    suspend fun acquireNew(): SessionHandle {
        val id = "wm_" + UUID.randomUUID().toString().substring(0, 12)
        return createSession(id)
    }

    /** Look up an existing session without creating one. */
    fun peek(sessionId: String): SessionHandle? = sessions[sessionId]?.also { touch(sessionId) }

    /** Destroy and remove a single session. */
    suspend fun release(sessionId: String, reason: String = "released") {
        val handle = sessions.remove(sessionId) ?: return
        synchronized(lruLock) { lru.remove(sessionId) }
        withContext(Dispatchers.Main) { handle.destroy(reason) }
    }

    /** Snapshot of currently live sessions. */
    fun listSessions(): List<SessionHandle> = sessions.values.toList()

    /** Destroy every live session. Safe to call multiple times. */
    suspend fun destroyAll(reason: String = "pool shutdown") {
        val ids = synchronized(lruLock) {
            val snapshot = lru.toList()
            lru.clear()
            snapshot
        }
        ids.forEach { release(it, reason) }
        // Sweep any stragglers (defensive).
        sessions.keys.toList().forEach { release(it, reason) }
    }

    // -------------------------------------------------- creation internals

    private suspend fun createSession(sessionId: String): SessionHandle {
        evictIfNeeded()
        val handle = withContext(Dispatchers.Main) { createOnMain(sessionId) }
        sessions[sessionId] = handle
        touch(sessionId)
        return handle
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createOnMain(sessionId: String): SessionHandle {
        require(Looper.myLooper() == Looper.getMainLooper()) { "WebView creation must be on main" }
        val webView = WebView(appContext).apply {
            settings.applyDefaults()
            userAgent?.let { settings.userAgentString = it }
            // Layout to a generic mobile viewport so DOM measurements like
            // getBoundingClientRect() are meaningful. The actual visible
            // size doesn't matter — the WebView is never attached to a window.
            layout(0, 0, VIRTUAL_VIEWPORT_W, VIRTUAL_VIEWPORT_H)
        }
        val jsBridge = JsBridge(sessionId)
        val handle = SessionHandle(
            sessionId = sessionId,
            webView = webView,
            jsBridge = jsBridge,
            bridgeBootstrapJs = bridgeBootstrapJs,
        )
        webView.addJavascriptInterface(jsBridge, "AmberWM")
        webView.webViewClient = WebMountWebViewClient(handle)
        webView.webChromeClient = WebMountWebChromeClient(handle)
        return handle
    }

    private fun WebSettings.applyDefaults() {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadsImagesAutomatically = true
        cacheMode = WebSettings.LOAD_DEFAULT
        useWideViewPort = true
        loadWithOverviewMode = true
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        @Suppress("DEPRECATION")
        allowFileAccess = false
        allowContentAccess = false
        // SDK 33+: setUserAgentString below will override.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeBrowsingEnabled = true
        }
    }

    private fun touch(sessionId: String) {
        synchronized(lruLock) {
            lru.remove(sessionId)
            lru.add(sessionId)
        }
    }

    private suspend fun evictIfNeeded() {
        val toEvict = synchronized(lruLock) {
            val excess = (lru.size - maxSessions + 1).coerceAtLeast(0)
            if (excess == 0) emptyList() else lru.take(excess).also { lru.removeAll(it.toSet()) }
        }
        toEvict.forEach { id ->
            val handle = sessions.remove(id) ?: return@forEach
            withContext(Dispatchers.Main) { handle.destroy("evicted (LRU cap=$maxSessions)") }
        }
    }

    private fun loadBridgeBootstrap(): String =
        runCatching {
            appContext.assets.open(BRIDGE_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to load $BRIDGE_ASSET", error)
            // Last-ditch inline fallback so the rest of the framework still
            // compiles/runs; bridge.js itself prints its own no-op when missing.
            "console.error('AmberWM bridge bootstrap missing');"
        }

    // ------------------------------------------------------ webview client

    private inner class WebMountWebViewClient(
        private val handle: SessionHandle,
    ) : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            url?.let { handle.onPageStarted(it) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            handle.reinjectBridge()
            handle.onPageFinished(url ?: "", view?.title)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            // Only treat main-frame errors as session-fatal.
            if (request?.isForMainFrame != true) return
            val code = error?.errorCode ?: 0
            val msg = error?.description?.toString() ?: "load failed"
            handle.onReceivedError(code, msg)
        }
    }

    private inner class WebMountWebChromeClient(
        private val handle: SessionHandle,
    ) : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            handle.onLoadProgress(newProgress)
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            consoleMessage ?: return true
            val level = when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "error"
                ConsoleMessage.MessageLevel.WARNING -> "warn"
                else -> "debug"
            }
            handle.jsBridge.log(level, "[chrome] ${consoleMessage.message()}")
            return true
        }
    }

    // -------------------------------------------------------------- closer

    /** Sync close, used from finalize/release paths. */
    fun shutdownBlocking(reason: String = "pool shutdown") {
        runBlocking { destroyAll(reason) }
    }

    companion object {
        private const val TAG = "WebMountPool"
        private const val BRIDGE_ASSET = "webmount/bridge.js"
        const val DEFAULT_MAX_SESSIONS = 4
        const val VIRTUAL_VIEWPORT_W = 412
        const val VIRTUAL_VIEWPORT_H = 915
        // Modern Chrome on Android. Adapters override per-station if needed.
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
    }
}
