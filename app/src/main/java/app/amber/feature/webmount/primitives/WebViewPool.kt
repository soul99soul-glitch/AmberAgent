package app.amber.feature.webmount.primitives

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.amber.feature.webmount.core.WebMountWebViewCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val onSessionDestroyed: (String) -> Unit = {},
    private val onSessionCreated: (SessionHandle) -> Unit = {},
    private val onSessionStateChanged: (String, SessionHandle.LoadState) -> Unit = { _, _ -> },
) {
    private val sessions = ConcurrentHashMap<String, SessionHandle>()
    private val createLock = Mutex()
    private val lru = LinkedHashSet<String>()      // protected by [lruLock]
    private val pinnedSessions = mutableSetOf<String>() // protected by [lruLock]
    private val pinTokens = mutableMapOf<String, String>() // protected by [lruLock]
    /** Owner reservations protect a handle before the owner can pin it. */
    private val reservationTokens = mutableMapOf<String, Reservation>() // protected by [lruLock]
    /** Evicted ids are removed from sessions before their main-thread destroy runs. */
    private val retiringSessions = mutableSetOf<String>() // protected by [lruLock]
    private val lruLock = Any()
    private val bridgeBootstrapJs: String by lazy { loadBridgeBootstrap() }
    @Volatile
    private var bridgeBootstrapError: Throwable? = null

    init {
        if (webContentsDebugging) {
            try {
                WebView.setWebContentsDebuggingEnabled(true)
            } catch (e: Throwable) {
                Log.w(TAG, "setWebContentsDebuggingEnabled failed", e)
            }
        }
    }

    /**
     * Acquire by id (returns existing live session if present, otherwise
     * creates a fresh one). Defensively skips and re-creates if the cached
     * handle is in the middle of being evicted/destroyed — the LRU eviction
     * marks `destroyed = true` synchronously before the WebView actually
     * tears down, so a stale read here would return a handle whose next
     * call throws "session ... already destroyed".
     */
    suspend fun acquire(
        sessionId: String,
        reservationToken: String? = null,
    ): SessionHandle {
        assertBridgeReady()
        try {
            reservationToken?.let { reserve(sessionId, it) }
            val existing = synchronized(lruLock) {
                liveSessionLocked(sessionId)?.also {
                    // A reservation can cover either a newly-created handle
                    // or an existing one. Keep that distinction so a late
                    // owner cancellation only removes the reservation when
                    // this acquire reused the existing page.
                    markReservationReusedLocked(sessionId, reservationToken)
                }
            }
            if (existing != null) return existing

            val stale = removeStaleSession(sessionId)
            if (stale != null) {
                onSessionDestroyed(sessionId)
                withContext(Dispatchers.Main) { stale.destroy("stale pooled session") }
            }
            return createLock.withLock {
                val racedExisting = synchronized(lruLock) {
                    liveSessionLocked(sessionId)?.also {
                        markReservationReusedLocked(sessionId, reservationToken)
                    }
                }
                if (racedExisting != null) return@withLock racedExisting
                val racedStale = removeStaleSession(sessionId)
                if (racedStale != null) {
                    onSessionDestroyed(sessionId)
                    withContext(Dispatchers.Main) { racedStale.destroy("stale pooled session") }
                }
                createSessionLocked(sessionId, reservationToken)
            }
        } catch (cancel: CancellationException) {
            reservationToken?.let { finishReservation(sessionId, it) }
            throw cancel
        } catch (error: Throwable) {
            reservationToken?.let { finishReservation(sessionId, it) }
            throw error
        }
    }

    /** Acquire with a freshly generated id. */
    suspend fun acquireNew(): SessionHandle {
        assertBridgeReady()
        val id = "wm_" + UUID.randomUUID().toString().substring(0, 12)
        return createLock.withLock {
            createSessionLocked(id)
        }
    }

    /** Keep an owned session out of LRU eviction until its lease is released. */
    fun pin(sessionId: String, leaseId: String? = null) {
        synchronized(lruLock) {
            val reservation = reservationTokens[sessionId]
            if (leaseId != null && reservation != null) {
                if (reservation.token != leaseId || reservation.cancelled) return
                reservationTokens.remove(sessionId)
            }
            if (leaseId != null && reservation == null) {
                val currentPin = pinTokens[sessionId]
                if (currentPin != null && currentPin != leaseId) return
            }
            pinnedSessions += sessionId
            leaseId?.let { pinTokens[sessionId] = it }
        }
    }

    fun unpin(sessionId: String, leaseId: String? = null) {
        synchronized(lruLock) {
            if (leaseId != null) {
                when {
                    pinTokens[sessionId] == leaseId -> {
                        pinnedSessions -= sessionId
                        pinTokens.remove(sessionId)
                    }
                    reservationTokens[sessionId]?.token == leaseId -> {
                        // Keep the token as a cancelled reservation until the
                        // suspended acquire acknowledges it, so release(handle,
                        // expectedPinToken) can still close that new handle.
                        reservationTokens[sessionId]?.cancelled = true
                    }
                    else -> return
                }
            } else {
                pinnedSessions -= sessionId
                pinTokens.remove(sessionId)
                reservationTokens[sessionId]?.cancelled = true
            }
        }
    }

    /** Look up an existing live session without creating one. */
    fun peek(sessionId: String): SessionHandle? {
        val handle = synchronized(lruLock) { liveSessionLocked(sessionId) }
        if (handle != null) return handle
        val stale = removeStaleSession(sessionId)
        if (stale != null) {
            onSessionDestroyed(sessionId)
            withMain { stale.destroy("stale pooled session") }
        }
        return null
    }

    /** Destroy and remove a single session. */
    suspend fun release(sessionId: String, reason: String = "released") {
        val handle = synchronized(lruLock) {
            val removed = sessions.remove(sessionId)
            lru.remove(sessionId)
            pinnedSessions.remove(sessionId)
            pinTokens.remove(sessionId)
            if (removed != null) reservationTokens.remove(sessionId)
            else reservationTokens[sessionId]?.let { it.cancelled = true }
            removed
        } ?: return
        onSessionDestroyed(sessionId)
        withContext(Dispatchers.Main) { handle.destroy(reason) }
    }

    /**
     * Destroy only [handle] if it is still the current mapping for its id.
     * Used when an owner reservation is cancelled while a suspended acquire is
     * still constructing a WebView; it cannot tear down a newer replacement.
     */
    suspend fun release(
        handle: SessionHandle,
        reason: String = "released",
        expectedPinToken: String? = null,
    ) {
        val removed = synchronized(lruLock) {
            val reservation = reservationTokens[handle.sessionId]
            if (expectedPinToken != null &&
                reservation?.token == expectedPinToken &&
                reservation.reusedExisting
            ) {
                // This acquire borrowed an already-live page. The owner may
                // cancel before pinning; clear only its reservation and keep
                // the original WebView/session alive for its real owner.
                reservationTokens.remove(handle.sessionId)
                return@synchronized false
            }
            val pinMatches = expectedPinToken == null ||
                pinTokens[handle.sessionId] == expectedPinToken ||
                reservation?.token == expectedPinToken
            if (!pinMatches) {
                // The reservation that produced this handle has already been
                // rolled back. A newer owner may be using the same WebView;
                // never destroy it just because a stale acquire resumed late.
                return@synchronized false
            }
            if (!sessions.remove(handle.sessionId, handle)) return@synchronized false
            lru.remove(handle.sessionId)
            pinnedSessions.remove(handle.sessionId)
            pinTokens.remove(handle.sessionId)
            reservationTokens.remove(handle.sessionId)
            true
        }
        if (!removed) return
        onSessionDestroyed(handle.sessionId)
        withContext(Dispatchers.Main) { handle.destroy(reason) }
    }

    /** Snapshot of currently live sessions. */
    fun listSessions(): List<SessionHandle> = sessions.values.toList()

    /** Destroy every live session. Safe to call multiple times. */
    suspend fun destroyAll(reason: String = "pool shutdown") {
        val ids = synchronized(lruLock) {
            val snapshot = lru.toList()
            lru.clear()
            pinnedSessions.clear()
            pinTokens.clear()
            reservationTokens.values.forEach { it.cancelled = true }
            snapshot
        }
        ids.forEach { release(it, reason) }
        // Sweep any stragglers (defensive).
        sessions.keys.toList().forEach { release(it, reason) }
    }

    // -------------------------------------------------- creation internals

    private suspend fun createSessionLocked(
        sessionId: String,
        reservationToken: String? = null,
    ): SessionHandle {
        evictIfNeeded()
        val handle = withContext(Dispatchers.Main) { createOnMain(sessionId) }
        val accepted = synchronized(lruLock) {
            val reservation = reservationToken?.let { reservationTokens[sessionId] }
            if (reservationToken != null &&
                (reservation?.token != reservationToken || reservation.cancelled)
            ) {
                false
            } else {
                sessions[sessionId] = handle
                touchLocked(sessionId)
                true
            }
        }
        if (!accepted) {
            finishReservation(sessionId, reservationToken)
            withContext(Dispatchers.Main) { handle.destroy("acquire reservation cancelled") }
            throw ReservationCancelledException()
        }
        onSessionCreated(handle)
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
        // Phase 2 holistic review W-2 fix: enable third-party cookies on
        // the headless WebView. Android's per-WebView default is false on
        // Lollipop+, so SSO/redirect flows (www.bilibili.com →
        // passport.bilibili.com → back) silently drop their Set-Cookie
        // hops without this. The InlineLoginActivity's visible WebView
        // already enables it; symmetric treatment on headless prevents
        // cookies captured there from being unusable here.
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        val networkLog = NetworkLog()
        val jsBridge = JsBridge(sessionId, NetworkLogObserver(networkLog))
        val handle = SessionHandle(
            sessionId = sessionId,
            webView = webView,
            jsBridge = jsBridge,
            bridgeBootstrapJs = bridgeBootstrapJs,
            networkLog = networkLog,
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
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        loadsImagesAutomatically = true
        cacheMode = WebSettings.LOAD_DEFAULT
        useWideViewPort = true
        loadWithOverviewMode = true
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        WebMountWebViewCompat.applyBrowserLikeSettings(this)
        @Suppress("DEPRECATION")
        allowFileAccess = false
        allowContentAccess = false
        // SDK 33+: setUserAgentString below will override.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeBrowsingEnabled = true
        }
    }

    private fun touchLocked(sessionId: String) {
        lru.remove(sessionId)
        lru.add(sessionId)
    }

    private suspend fun evictIfNeeded() {
        val toEvict = synchronized(lruLock) {
            val excess = (lru.size - maxSessions + 1).coerceAtLeast(0)
            if (excess == 0) {
                emptyList()
            } else {
                lru.asSequence()
                    .filterNot { it in pinnedSessions || it in reservationTokens }
                    .take(excess)
                    .toList()
                    .mapNotNull { id ->
                        // Remove the mapping and its LRU entry under one lock;
                        // a racing acquire cannot return a handle already
                        // selected for destruction.
                        lru.remove(id)
                        val handle = sessions.remove(id) ?: return@mapNotNull null
                        pinnedSessions.remove(id)
                        pinTokens.remove(id)
                        retiringSessions += id
                        handle
                    }
            }
        }
        toEvict.forEach { handle ->
            val id = handle.sessionId
            onSessionDestroyed(id)
            try {
                withContext(Dispatchers.Main) { handle.destroy("evicted (LRU cap=$maxSessions)") }
            } finally {
                synchronized(lruLock) { retiringSessions.remove(id) }
            }
        }
    }

    private fun reserve(sessionId: String, reservationToken: String) {
        synchronized(lruLock) {
            val currentPin = pinTokens[sessionId]
            if (currentPin != null && currentPin != reservationToken) {
                throw IllegalStateException("session $sessionId is already pinned")
            }
            val current = reservationTokens[sessionId]
            if (current != null && current.token != reservationToken) {
                throw IllegalStateException("session $sessionId is already reserved")
            }
            if (current == null) {
                reservationTokens[sessionId] = Reservation(reservationToken)
            }
        }
    }

    private fun markReservationReusedLocked(sessionId: String, reservationToken: String?) {
        if (reservationToken == null) return
        reservationTokens[sessionId]
            ?.takeIf { it.token == reservationToken }
            ?.let { it.reusedExisting = true }
    }

    private fun finishReservation(sessionId: String, reservationToken: String?) {
        if (reservationToken == null) return
        synchronized(lruLock) {
            if (reservationTokens[sessionId]?.token == reservationToken) {
                reservationTokens.remove(sessionId)
            }
        }
    }

    private fun liveSessionLocked(sessionId: String): SessionHandle? {
        val handle = sessions[sessionId] ?: return null
        if (handle.destroyed || sessionId in retiringSessions) return null
        touchLocked(sessionId)
        return handle
    }

    private fun removeStaleSession(sessionId: String): SessionHandle? = synchronized(lruLock) {
        val handle = sessions[sessionId] ?: return@synchronized null
        if (!handle.destroyed && sessionId !in retiringSessions) return@synchronized null
        sessions.remove(sessionId, handle)
        lru.remove(sessionId)
        pinnedSessions.remove(sessionId)
        pinTokens.remove(sessionId)
        handle
    }

    private fun withMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else runBlocking { withContext(Dispatchers.Main) { block() } }
    }

    private data class Reservation(
        val token: String,
        var cancelled: Boolean = false,
        var reusedExisting: Boolean = false,
    )

    private class ReservationCancelledException : RuntimeException(
        "webmount pool reservation was cancelled before the handle was ready",
    )

    private fun loadBridgeBootstrap(): String =
        runCatching {
            appContext.assets.open(BRIDGE_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to load $BRIDGE_ASSET", error)
            bridgeBootstrapError = error
            // Force-fail any subsequent bridge call rather than silently
            // letting it time out. The JS we inject just throws.
            "throw new Error('AmberWM bridge bootstrap asset missing: $BRIDGE_ASSET');"
        }

    /** Throws if [BRIDGE_ASSET] failed to load — tools call this before issuing bridge RPCs. */
    fun assertBridgeReady() {
        // Trigger the lazy load so `bridgeBootstrapError` is populated.
        @Suppress("UNUSED_VARIABLE")
        val unused = bridgeBootstrapJs
        bridgeBootstrapError?.let {
            throw IllegalStateException(
                "WebMount bridge asset failed to load: $BRIDGE_ASSET. Agent bridge calls will fail.",
                it,
            )
        }
    }

    // ------------------------------------------------------ webview client

    private inner class WebMountWebViewClient(
        private val handle: SessionHandle,
    ) : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            handle.cancelPendingJsDialogsFor(view ?: handle.webView, "primary navigation")
            url?.let {
                handle.onPageStarted(it)
                onSessionStateChanged(handle.sessionId, handle.loadState.value)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            android.webkit.CookieManager.getInstance().flush()
            WebMountWebViewCompat.injectFeishuCompatibility(view, url)
            handle.reinjectBridge()
            handle.onPageFinished(url ?: "", view?.title)
            onSessionStateChanged(handle.sessionId, handle.loadState.value)
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
            onSessionStateChanged(handle.sessionId, handle.loadState.value)
        }
    }

    private inner class WebMountWebChromeClient(
        private val handle: SessionHandle,
    ) : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress >= 25) {
                WebMountWebViewCompat.injectFeishuCompatibility(view, view?.url)
            }
            if (view === handle.webView) {
                handle.onLoadProgress(newProgress)
            } else {
                handle.popups.value.firstOrNull { it.webView === view }?.let { popup ->
                    handle.updatePopup(popup.popupId, url = view?.url, title = view?.title)
                }
            }
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?,
        ): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val popupId = handle.nextPopupId()
            val popup = createPopupOnMain(handle, popupId)
            handle.registerPopup(popupId, popup, isDialog)
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            window?.let { handle.closePopup(it, "window closed") }
        }

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            val dialogResult = result ?: return false
            handle.enqueueJsDialog(
                type = SessionHandle.JsDialogType.ALERT,
                view = view,
                url = url,
                message = message.orEmpty(),
                defaultValue = null,
                result = dialogResult,
            )
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            val dialogResult = result ?: return false
            handle.enqueueJsDialog(
                type = SessionHandle.JsDialogType.CONFIRM,
                view = view,
                url = url,
                message = message.orEmpty(),
                defaultValue = null,
                result = dialogResult,
            )
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?,
        ): Boolean {
            val dialogResult = result ?: return false
            handle.enqueueJsDialog(
                type = SessionHandle.JsDialogType.PROMPT,
                view = view,
                url = url,
                message = message.orEmpty(),
                defaultValue = defaultValue,
                result = dialogResult,
            )
            return true
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopupOnMain(
        opener: SessionHandle,
        popupId: String,
    ): WebView {
        require(Looper.myLooper() == Looper.getMainLooper()) { "Popup creation must be on main" }
        return WebView(appContext).apply {
            settings.applyDefaults()
            userAgent?.let { settings.userAgentString = it }
            layout(0, 0, VIRTUAL_VIEWPORT_W, VIRTUAL_VIEWPORT_H)
            val popupView = this
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(popupView, true)
            }
            webViewClient = PopupWebViewClient(opener, popupId)
            webChromeClient = WebMountWebChromeClient(opener)
            addJavascriptInterface(opener.jsBridge, "AmberWM")
            opener.injectBridgeInto(this)
        }
    }

    private inner class PopupWebViewClient(
        private val opener: SessionHandle,
        private val popupId: String,
    ) : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            view?.let { opener.cancelPendingJsDialogsFor(it, "popup navigation") }
            opener.updatePopup(popupId, url = url, title = view?.title)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            android.webkit.CookieManager.getInstance().flush()
            WebMountWebViewCompat.injectFeishuCompatibility(view, url)
            view?.let(opener::injectBridgeInto)
            opener.updatePopup(popupId, url = url, title = view?.title)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame != true) return
            opener.updatePopup(popupId, url = view?.url, title = view?.title)
        }
    }

    // -------------------------------------------------------------- closer

    /** Sync close, used from finalize/release paths. Must NOT be called from main thread. */
    fun shutdownBlocking(reason: String = "pool shutdown") {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "shutdownBlocking must not be called from main thread (deadlock with Dispatchers.Main)"
        }
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
