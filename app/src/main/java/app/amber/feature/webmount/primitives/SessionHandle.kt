package app.amber.feature.webmount.primitives

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import app.amber.feature.webmount.core.WebViewCompatibility
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One pooled WebView wrapped with its JS bridge, load-state tracking, and an
 * RPC convenience API used by Browser Primitives tools.
 *
 * Lifecycle is owned by [WebViewPool]. The pool always constructs and
 * destroys handles on the main thread; methods below dispatch onto Main
 * whenever they touch [webView].
 *
 *  - [loadState]: source of truth for "what is the WebView doing right now".
 *  - [callBridge]: send a method call to bridge.js and await its reply.
 *  - [evalRaw]: fire-and-forget JS, returns the engine result string.
 */
class SessionHandle internal constructor(
    val sessionId: String,
    internal val webView: WebView,
    internal val jsBridge: JsBridge,
    private val bridgeBootstrapJs: String,
    val networkLog: NetworkLog = NetworkLog(),
) {
    private val _loadState = MutableStateFlow(LoadState.idle())
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val loadSeq = AtomicLong(0L)
    private val popupSeq = AtomicLong(0L)
    internal val pendingLoad = AtomicReference<LoadCompletion?>(null)
    val lastActivityMs: AtomicLong = AtomicLong(System.currentTimeMillis())
    private var documentStartOrigin: String? = null
    private val documentStartHandlers = mutableListOf<ScriptHandler>()
    private val popupLock = Any()
    /** WebView.post can remain queued forever for a detached/headless view. */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val popupViews = linkedMapOf<String, WebView>()
    private val pendingDialogs = linkedMapOf<String, PendingJsDialog>()
    /** The run that may currently create an agent-owned JS dialog. */
    private var dialogOwnerLeaseId: String? = null
    private var dialogOwnerRunId: String? = null

    private val _popups = MutableStateFlow<List<PopupInfo>>(emptyList())
    /** Popups opened by this session. The primary WebView remains the source of truth. */
    val popups: StateFlow<List<PopupInfo>> = _popups.asStateFlow()

    private val _jsDialogs = MutableStateFlow<List<JsDialogInfo>>(emptyList())
    /** Pending JavaScript dialogs; UI must resolve them through [resolveJsDialog]. */
    val jsDialogs: StateFlow<List<JsDialogInfo>> = _jsDialogs.asStateFlow()

    @Volatile
    var bridgeInjectionCoverage: String = "page_finished"
        private set

    @Volatile
    var destroyed: Boolean = false
        private set

    /**
     * Load [url] and suspend until `onPageFinished` matches the new load id,
     * or [timeoutMs] elapses. Returns the latest [LoadState] either way.
     */
    suspend fun loadUrl(
        url: String,
        timeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS,
        dispatchWithLease: ((() -> Unit) -> Boolean)? = null,
    ): LoadState {
        ensureAlive()
        val loadId = startLoad(url)
        val completion = pendingLoad.get()?.completion
            ?: error("startLoad failed to install completion")
        try {
            withContext(Dispatchers.Main) {
                dispatchOnMain(dispatchWithLease, "loadUrl") {
                    installDocumentStartBridge(url)
                    webView.loadUrl(url)
                }
            }
            withTimeoutOrNull(timeoutMs) { completion.await() }
        } finally {
            // Drop the completion if it's still ours; mark FAILED on timeout so
            // a stuck LOADING state doesn't linger forever.
            val current = pendingLoad.get()
            if (current?.loadId == loadId && !completion.isCompleted) {
                pendingLoad.compareAndSet(current, null)
                completion.completeExceptionally(JsBridge.JsBridgeException("loadUrl timed out / cancelled"))
                if (_loadState.value.loadId == loadId && _loadState.value.status == LoadStatus.LOADING) {
                    _loadState.value = _loadState.value.copy(
                        status = LoadStatus.FAILED,
                        error = "load timed out after ${timeoutMs}ms",
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
            }
        }
        lastActivityMs.set(System.currentTimeMillis())
        return _loadState.value
    }

    /**
     * Issue a load without waiting for it to finish, while still updating
     * `_loadState` so the next `wm_state` call sees the new requested URL.
     * Used by `wm_open` with `wait="none"`.
     */
    suspend fun loadUrlNoWait(
        url: String,
        dispatchWithLease: ((() -> Unit) -> Boolean)? = null,
    ): LoadState {
        ensureAlive()
        val loadId = startLoad(url)
        try {
            withContext(Dispatchers.Main) {
                dispatchOnMain(dispatchWithLease, "loadUrlNoWait") {
                    installDocumentStartBridge(url)
                    webView.loadUrl(url)
                }
            }
        } catch (error: Throwable) {
            failLoadIfCurrent(loadId, error)
            throw error
        }
        lastActivityMs.set(System.currentTimeMillis())
        return _loadState.value
    }

    /**
     * Synchronously bump the load sequence, install a fresh pendingLoad,
     * cancel any prior in-flight load, and seed [_loadState] with the
     * LOADING placeholder. Used by both [loadUrl] and [loadUrlNoWait] so
     * state updates always happen before the WebView actually starts loading.
     */
    private fun startLoad(url: String): Long {
        val loadId = loadSeq.incrementAndGet()
        val completion = CompletableDeferred<Unit>()
        val prior = pendingLoad.getAndSet(LoadCompletion(loadId, completion))
        prior?.completion?.completeExceptionally(
            JsBridge.JsBridgeException("superseded by load #$loadId")
        )
        _loadState.value = LoadState(
            loadId = loadId,
            requestedUrl = url,
            committedUrl = null,
            currentUrl = null,
            title = null,
            status = LoadStatus.LOADING,
            progress = 0,
            error = null,
            updatedAtMs = System.currentTimeMillis(),
        )
        return loadId
    }

    /**
     * Fire-and-forget JS eval. Returns the raw string the JS engine produced
     * (the value passed back from `evaluateJavascript`). Caller is responsible
     * for parsing.
     */
    suspend fun evalRaw(
        script: String,
        timeoutMs: Long = DEFAULT_EVAL_TIMEOUT_MS,
        dispatchWithLease: ((() -> Unit) -> Boolean)? = null,
    ): String? {
        ensureAlive()
        val deferred = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            dispatchOnMain(dispatchWithLease, "evalRaw") {
                webView.evaluateJavascript(script) { value -> deferred.complete(value) }
            }
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    /**
     * Invoke a bridge-defined method (registered in `assets/webmount/bridge.js`)
     * and await the JSON payload it resolves with. Times out per [timeoutMs].
     */
    suspend fun callBridge(
        method: String,
        args: JsonObject = EMPTY_ARGS,
        timeoutMs: Long = DEFAULT_BRIDGE_TIMEOUT_MS,
        dispatchWithLease: ((() -> Unit) -> Boolean)? = null,
    ): JsonElement {
        ensureAlive()
        val requestId = UUID.randomUUID().toString()
        val deferred = jsBridge.expect(requestId)
        val argsJson = JSON.encodeToString(JsonElement.serializer(), args)
        val methodLit = JSON.encodeToString(String.serializer(), method)
        val argsLit = JSON.encodeToString(String.serializer(), argsJson)
        val reqIdLit = JSON.encodeToString(String.serializer(), requestId)
        val script =
            "(function(){try{__amberWm_call($methodLit,$argsLit,$reqIdLit);}" +
                "catch(e){AmberWM.reject($reqIdLit,'host eval failed: '+(e&&e.message));}})();"

        try {
            withContext(Dispatchers.Main) {
                dispatchOnMain(dispatchWithLease, "callBridge") {
                    webView.evaluateJavascript(script, null)
                }
            }
        } catch (error: Throwable) {
            // The owner can reject the dispatch before WebView sees anything;
            // do not leave the request in JsBridge.pending in that case.
            jsBridge.forget(requestId)
            throw error
        }
        // try/finally so the pending entry is always cleaned up — including
        // when the caller's coroutine is structurally cancelled (e.g. agent
        // stops a tool turn). Without this, the entry leaks until the entire
        // session is destroyed.
        val result = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            jsBridge.forget(requestId)
            lastActivityMs.set(System.currentTimeMillis())
        }
        return result
            ?: throw JsBridge.JsBridgeException("bridge call '$method' timed out after ${timeoutMs}ms")
    }

    /** Re-inject `bridge.js` after a navigation reset the JS realm. */
    @SuppressLint("SetJavaScriptEnabled")
    internal fun reinjectBridge() {
        if (destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { reinjectBridge() }
            return
        }
        webView.evaluateJavascript(bridgeBootstrapJs, null)
    }

    /**
     * Inject an auxiliary host-defined script (e.g. a profile signing shim).
     * Idempotent at the JS level — shims are written to guard against
     * double-definition. Re-injected on every call because page navigation
     * clears the JS realm. Fire-and-forget; errors only surface via JS console.
     */
    @SuppressLint("SetJavaScriptEnabled")
    internal fun injectHostShim(source: String) {
        if (destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { injectHostShim(source) }
            return
        }
        webView.evaluateJavascript(source, null)
    }

    /**
     * Inject the shared bridge into a popup WebView. Popup windows use the
     * same [JsBridge] and cookie jar as their opener, but a navigation resets
     * their JavaScript realm, so [WebViewPool] calls this again on finish.
     */
    internal fun injectBridgeInto(view: WebView) {
        if (destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { injectBridgeInto(view) }
            return
        }
        view.evaluateJavascript(bridgeBootstrapJs, null)
    }

    // ------------------------------------------------------- popup / dialogs

    internal fun nextPopupId(): String = "$sessionId-popup-${popupSeq.incrementAndGet()}"

    internal fun registerPopup(
        popupId: String,
        popup: WebView,
        isDialogWindow: Boolean,
    ) {
        synchronized(popupLock) {
            popupViews[popupId] = popup
            _popups.value = popupViews.map { (id, view) ->
                PopupInfo(
                    popupId = id,
                    url = view.url,
                    title = view.title,
                    isDialogWindow = if (id == popupId) isDialogWindow else _popups.value
                        .firstOrNull { it.popupId == id }
                        ?.isDialogWindow == true,
                    createdAtMs = _popups.value.firstOrNull { it.popupId == id }?.createdAtMs
                        ?: System.currentTimeMillis(),
                    webView = view,
                )
            }
        }
    }

    internal fun updatePopup(popupId: String, url: String? = null, title: String? = null) {
        synchronized(popupLock) {
            val popup = popupViews[popupId] ?: return
            val previous = _popups.value.firstOrNull { it.popupId == popupId }
            _popups.value = _popups.value.map { info ->
                if (info.popupId == popupId) {
                    info.copy(
                        url = url ?: popup.url ?: info.url,
                        title = title ?: popup.title ?: info.title,
                    )
                } else {
                    info
                }
            }.ifEmpty {
                listOf(
                    PopupInfo(
                        popupId = popupId,
                        url = url ?: popup.url,
                        title = title ?: popup.title,
                        isDialogWindow = previous?.isDialogWindow == true,
                        createdAtMs = previous?.createdAtMs ?: System.currentTimeMillis(),
                        webView = popup,
                    )
                )
            }
        }
    }

    internal fun popupWebView(popupId: String): WebView? = synchronized(popupLock) {
        popupViews[popupId]
    }

    /** Close one popup and invalidate its view. Must be safe from any thread. */
    internal fun closePopup(popupId: String, reason: String = "popup closed") {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { closePopup(popupId, reason) }
            return
        }
        val popup = synchronized(popupLock) {
            val removed = popupViews.remove(popupId)
            _popups.value = _popups.value.filterNot { it.popupId == popupId }
            removed
        } ?: return
        cancelPendingJsDialogsFor(popup, reason)
        runCatching {
            popup.stopLoading()
            popup.destroy()
        }.onFailure { Log.w(TAG, "[$sessionId] popup close failed", it) }
    }

    internal fun closePopup(window: WebView, reason: String = "popup closed") {
        val popupId = synchronized(popupLock) {
            popupViews.entries.firstOrNull { it.value === window }?.key
        } ?: return
        closePopup(popupId, reason)
    }

    internal fun enqueueJsDialog(
        type: JsDialogType,
        view: WebView?,
        url: String?,
        message: String,
        defaultValue: String?,
        result: JsResult,
    ): String {
        val dialogId = "$sessionId-dialog-${UUID.randomUUID()}"
        val accepted = synchronized(popupLock) {
            // Page timers can outlive the run that scheduled them. An idle
            // session has nobody to answer a new dialog; existing explicit
            // handoffs stay in pendingDialogs until the human resolves them.
            if (dialogOwnerLeaseId == null) return@synchronized false
            pendingDialogs[dialogId] = PendingJsDialog(
                info = JsDialogInfo(
                    dialogId = dialogId,
                    windowId = windowIdFor(view),
                    type = type,
                    url = url,
                    message = message,
                    defaultValue = defaultValue,
                ),
                result = result,
                sourceView = view,
                leaseId = dialogOwnerLeaseId,
                runId = dialogOwnerRunId,
            )
            _jsDialogs.value = pendingDialogs.values.map { it.info }
            true
        }
        if (!accepted) result.cancel()
        return dialogId
    }

    /** Bind future dialogs to the current owner lease; null means idle. */
    internal fun setDialogLeaseContext(leaseId: String?, runId: String?) {
        synchronized(popupLock) {
            dialogOwnerLeaseId = leaseId
            dialogOwnerRunId = runId
        }
    }

    /** Clear an old run context without disturbing a newer human context. */
    internal fun clearDialogLeaseContext(runId: String) {
        synchronized(popupLock) {
            if (dialogOwnerRunId == runId) {
                dialogOwnerLeaseId = null
                dialogOwnerRunId = null
            }
        }
    }

    /**
     * Atomically clear an old run context and remove only dialogs tagged with
     * that run. The returned entries can be cancelled after the owner lock is
     * released, so a concurrent HUMAN transition cannot be swept accidentally.
     */
    internal fun takePendingJsDialogsForRun(runId: String): List<PendingJsDialog> {
        val normalizedRunId = runId.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        return synchronized(popupLock) {
            if (dialogOwnerRunId == normalizedRunId) {
                dialogOwnerLeaseId = null
                dialogOwnerRunId = null
            }
            val snapshot = pendingDialogs.values.filter { it.runId == normalizedRunId }
            if (snapshot.isNotEmpty()) {
                snapshot.forEach { pendingDialogs.remove(it.info.dialogId) }
                _jsDialogs.value = pendingDialogs.values.map { it.info }
            }
            snapshot
        }
    }

    /** Remove dialogs created by one lease while the owner transition is locked. */
    internal fun takePendingJsDialogsForLease(leaseId: String): List<PendingJsDialog> {
        val normalizedLeaseId = leaseId.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        return synchronized(popupLock) {
            if (dialogOwnerLeaseId == normalizedLeaseId) {
                dialogOwnerLeaseId = null
                dialogOwnerRunId = null
            }
            val snapshot = pendingDialogs.values.filter { it.leaseId == normalizedLeaseId }
            if (snapshot.isNotEmpty()) {
                snapshot.forEach { pendingDialogs.remove(it.info.dialogId) }
                _jsDialogs.value = pendingDialogs.values.map { it.info }
            }
            snapshot
        }
    }

    /** Atomically remove every pending dialog, used when a HUMAN lease leaves. */
    internal fun takeAllPendingJsDialogs(): List<PendingJsDialog> = synchronized(popupLock) {
        val snapshot = pendingDialogs.values.toList()
        pendingDialogs.clear()
        dialogOwnerLeaseId = null
        dialogOwnerRunId = null
        _jsDialogs.value = emptyList()
        snapshot
    }

    /** Resolve a pending alert/confirm/prompt. The WebChrome callbacks run on main. */
    fun resolveJsDialog(dialogId: String, confirmed: Boolean, value: String? = null) {
        val pending = synchronized(popupLock) {
            val removed = pendingDialogs.remove(dialogId)
            _jsDialogs.value = pendingDialogs.values.map { it.info }
            removed
        } ?: return
        dispatchJsDialogResult(pending, confirmed, value, "resolve")
    }

    /** Cancel unresolved dialogs when the current human/agent lease leaves. */
    internal fun cancelPendingJsDialogs(reason: String = "lease released") {
        val dialogs = synchronized(popupLock) {
            val snapshot = pendingDialogs.values.toList()
            pendingDialogs.clear()
            _jsDialogs.value = emptyList()
            snapshot
        }
        dispatchJsDialogCancellation(dialogs, reason)
    }

    /** Cancel dialogs owned by one window when it navigates, closes, or leaves. */
    internal fun cancelPendingJsDialogsFor(view: WebView, reason: String = "window left") {
        val dialogs = synchronized(popupLock) {
            val snapshot = pendingDialogs.values.filter { it.sourceView === view }
            if (snapshot.isNotEmpty()) {
                snapshot.forEach { pendingDialogs.remove(it.info.dialogId) }
                _jsDialogs.value = pendingDialogs.values.map { it.info }
            }
            snapshot
        }
        dispatchJsDialogCancellation(dialogs, reason)
    }

    private fun dispatchJsDialogResult(
        pending: PendingJsDialog,
        confirmed: Boolean,
        value: String?,
        operation: String,
    ) {
        val resolve = {
            runCatching {
                if (!confirmed) {
                    pending.result.cancel()
                } else if (pending.result is JsPromptResult) {
                    pending.result.confirm(value.orEmpty())
                } else {
                    pending.result.confirm()
                }
            }.onFailure { Log.w(TAG, "[$sessionId] JS dialog $operation failed", it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) resolve() else mainHandler.post { resolve() }
    }

    internal fun dispatchJsDialogCancellation(
        dialogs: List<PendingJsDialog>,
        reason: String,
    ) {
        if (dialogs.isEmpty()) return
        val cancel = {
            dialogs.forEach { pending ->
                runCatching { pending.result.cancel() }
                    .onFailure { Log.w(TAG, "[$sessionId] JS dialog cancellation failed: $reason", it) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cancel() else mainHandler.post { cancel() }
    }

    private fun windowIdFor(view: WebView?): String = synchronized(popupLock) {
        view?.let { candidate ->
            popupViews.entries.firstOrNull { it.value === candidate }?.key
        } ?: sessionId
    }

    /** Cancel dialogs and close popups when a session is destroyed. */
    private fun closeTransientOverlays(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { closeTransientOverlays(reason) }
            return
        }
        val dialogs = synchronized(popupLock) {
            val snapshot = pendingDialogs.values.toList()
            pendingDialogs.clear()
            dialogOwnerLeaseId = null
            dialogOwnerRunId = null
            _jsDialogs.value = emptyList()
            snapshot
        }
        dialogs.forEach { pending -> runCatching { pending.result.cancel() } }
        val popupIds = synchronized(popupLock) { popupViews.keys.toList() }
        popupIds.forEach { closePopup(it, reason) }
    }

    /**
     * Phase 2 M2.2 — silent eval channel for synchronous JS expressions.
     *
     * Runs [script] via [evalRaw] without going through the [Tool] approval
     * gate. The agent's `wm_eval` tool is gated by mandatoryApproval, with
     * only explicit high-risk auto-approval allowed to run it unattended; this
     * internal channel is reserved for **host-defined** helpers. Callers MUST
     * have validated the profile's permissions + origin first — see
     * [app.amber.feature.webmount.profile.ProfileBridge].
     *
     * **NOTE**: `evaluateJavascript` returns the JSON-stringified value of
     * the script's synchronous return. Promises are stringified as `"{}"`
     * — for async results, use [callPageFn] instead (which routes through
     * the bridge's `AmberWM.resolve` callback so async functions are
     * properly awaited).
     */
    internal suspend fun evalSilent(
        script: String,
        timeoutMs: Long = DEFAULT_EVAL_TIMEOUT_MS,
    ): String? = evalRaw(script, timeoutMs)

    /**
     * Phase 2 M2.2 — bridge-aware call to a host-defined or page-defined
     * function. Use this in place of [evalSilent] when the target function
     * is async or returns a Promise. The wrapper script delivers the
     * resolved value through the same `AmberWM.resolve(reqId, ...)`
     * channel that [callBridge] uses, so async functions are properly
     * awaited.
     *
     * Returns the parsed JSON envelope. If the target throws or rejects,
     * returns a [JsonObject] with a `__amberError` field. Times out per
     * [timeoutMs] — on timeout, throws [JsBridge.JsBridgeException].
     *
     * Like [evalSilent], no [Tool] approval gate is touched — the
     * [app.amber.feature.webmount.profile.ProfileBridge] is
     * responsible for the L2 / L3 / L5 checks before calling this.
     */
    internal suspend fun callPageFn(
        fnName: String,
        args: kotlinx.serialization.json.JsonArray,
        timeoutMs: Long = 10_000L,
        dispatchWithLease: ((() -> Unit) -> Boolean)? = null,
    ): JsonElement {
        ensureAlive()
        val requestId = UUID.randomUUID().toString()
        val deferred = jsBridge.expect(requestId)
        val fnLit = JSON.encodeToString(String.serializer(), fnName)
        val reqIdLit = JSON.encodeToString(String.serializer(), requestId)
        val argsLit = args.toString()
        // The wrapper:
        //  - Awaits the (possibly async) function call.
        //  - JSON.stringify the result and pass it through AmberWM.resolve.
        //  - On any throw, route via AmberWM.reject so the deferred fails
        //    with a clear message instead of the agent seeing "{}".
        val script =
            "(async function(){try{" +
                "if(typeof window[$fnLit] !== 'function'){" +
                "AmberWM.reject($reqIdLit,'shim function '+$fnLit+' not defined');return;}" +
                "var r = await window[$fnLit].apply(null, $argsLit);" +
                "AmberWM.resolve($reqIdLit, JSON.stringify(r));" +
                "}catch(e){AmberWM.reject($reqIdLit, String((e&&e.message)||e));}})();"

        try {
            withContext(Dispatchers.Main) {
                dispatchOnMain(dispatchWithLease, "callPageFn") {
                    webView.evaluateJavascript(script, null)
                }
            }
        } catch (error: Throwable) {
            jsBridge.forget(requestId)
            throw error
        }
        val result = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            jsBridge.forget(requestId)
            lastActivityMs.set(System.currentTimeMillis())
        }
        return result
            ?: throw JsBridge.JsBridgeException("callPageFn '$fnName' timed out after ${timeoutMs}ms")
    }

    internal fun onLoadProgress(progress: Int) {
        _loadState.value = _loadState.value.copy(
            progress = progress,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    internal fun onPageStarted(url: String) {
        _loadState.value = _loadState.value.copy(
            committedUrl = url,
            currentUrl = url,
            status = LoadStatus.LOADING,
            progress = 0,
            error = null,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    internal fun onPageFinished(url: String, title: String?) {
        val expectedLoadId = _loadState.value.loadId
        _loadState.value = _loadState.value.copy(
            currentUrl = url,
            title = title,
            status = LoadStatus.READY,
            progress = 100,
            updatedAtMs = System.currentTimeMillis(),
        )
        // Resolve the awaiting load only if it corresponds to the current
        // load id — guards against stale onPageFinished from a redirect chain
        // for a previous navigation.
        val pending = pendingLoad.get()
        if (pending != null && pending.loadId == expectedLoadId) {
            if (pendingLoad.compareAndSet(pending, null)) {
                pending.completion.complete(Unit)
            }
        }
    }

    internal fun onReceivedError(code: Int, message: String) {
        val expectedLoadId = _loadState.value.loadId
        _loadState.value = _loadState.value.copy(
            status = LoadStatus.FAILED,
            error = "$code: $message",
            updatedAtMs = System.currentTimeMillis(),
        )
        val pending = pendingLoad.get()
        if (pending != null && pending.loadId == expectedLoadId) {
            if (pendingLoad.compareAndSet(pending, null)) {
                pending.completion.complete(Unit)
            }
        }
    }

    /** Pool-only. Tears down the WebView; subsequent calls throw. */
    internal fun destroy(reason: String) {
        if (destroyed) return
        destroyed = true
        jsBridge.cancelAll(reason)
        pendingLoad.getAndSet(null)?.completion?.complete(Unit)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { destroyInternalOnMain() }
        } else {
            destroyInternalOnMain()
        }
    }

    private fun destroyInternalOnMain() {
        runCatching {
            closeTransientOverlays("session destroyed")
            documentStartHandlers.forEach { handler -> runCatching { handler.remove() } }
            documentStartHandlers.clear()
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        }.onFailure { Log.w(TAG, "[$sessionId] destroy failed", it) }
    }

    private fun ensureAlive() {
        check(!destroyed) { "session $sessionId already destroyed" }
    }

    private fun dispatchOnMain(
        dispatchWithLease: ((() -> Unit) -> Boolean)?,
        operation: String,
        action: () -> Unit,
    ) {
        if (dispatchWithLease == null) {
            action()
            return
        }
        if (dispatchWithLease(action)) return
        throw WebMountLeaseInvalidatedException("webmount lease invalidated before $operation dispatch")
    }

    private fun failLoadIfCurrent(loadId: Long, error: Throwable) {
        val current = pendingLoad.get()
        if (current?.loadId != loadId) return
        if (pendingLoad.compareAndSet(current, null)) {
            current.completion.completeExceptionally(error)
        }
        if (_loadState.value.loadId == loadId) {
            _loadState.value = _loadState.value.copy(
                status = LoadStatus.FAILED,
                error = error.message ?: error.toString(),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun installDocumentStartBridge(url: String) {
        val origin = WebViewCompatibility.originFor(url)
        if (origin == null || origin == documentStartOrigin) return
        documentStartHandlers.forEach { handler -> runCatching { handler.remove() } }
        documentStartHandlers.clear()
        documentStartOrigin = origin
        bridgeInjectionCoverage = "page_finished"
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            documentStartHandlers += WebViewCompat.addDocumentStartJavaScript(
                webView,
                bridgeBootstrapJs,
                setOf(origin),
            )
            bridgeInjectionCoverage = "document_start"
        }
    }

    // -------------------------------------------------------- nested types

    data class LoadState(
        val loadId: Long,
        val requestedUrl: String?,
        val committedUrl: String?,
        val currentUrl: String?,
        val title: String?,
        val status: LoadStatus,
        val progress: Int,
        val error: String?,
        val updatedAtMs: Long,
    ) {
        companion object {
            fun idle() = LoadState(
                loadId = 0L,
                requestedUrl = null,
                committedUrl = null,
                currentUrl = null,
                title = null,
                status = LoadStatus.IDLE,
                progress = 0,
                error = null,
                updatedAtMs = 0L,
            )
        }
    }

    enum class LoadStatus(val wireName: String) {
        IDLE("idle"),
        LOADING("loading"),
        READY("ready"),
        FAILED("failed"),
    }

    internal class LoadCompletion(
        val loadId: Long,
        val completion: CompletableDeferred<Unit>,
    )

    data class PopupInfo internal constructor(
        val popupId: String,
        val url: String?,
        val title: String?,
        val isDialogWindow: Boolean,
        val createdAtMs: Long,
        internal val webView: WebView,
    )

    enum class JsDialogType {
        ALERT,
        CONFIRM,
        PROMPT,
    }

    data class JsDialogInfo internal constructor(
        val dialogId: String,
        val windowId: String,
        val type: JsDialogType,
        val url: String?,
        val message: String,
        val defaultValue: String?,
    )

    internal data class PendingJsDialog(
        val info: JsDialogInfo,
        val result: JsResult,
        val sourceView: WebView?,
        val leaseId: String?,
        val runId: String?,
    )

    companion object {
        private const val TAG = "WebMountSession"
        const val DEFAULT_LOAD_TIMEOUT_MS = 30_000L
        const val DEFAULT_EVAL_TIMEOUT_MS = 5_000L
        const val DEFAULT_BRIDGE_TIMEOUT_MS = 10_000L
        val EMPTY_ARGS: JsonObject = buildJsonObject { }
        private val JSON: Json = Json
    }
}
