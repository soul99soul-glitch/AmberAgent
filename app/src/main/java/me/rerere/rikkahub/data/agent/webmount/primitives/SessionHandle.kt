package me.rerere.rikkahub.data.agent.webmount.primitives

import android.annotation.SuppressLint
import android.os.Looper
import android.util.Log
import android.webkit.WebView
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
) {
    private val _loadState = MutableStateFlow(LoadState.idle())
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val loadSeq = AtomicLong(0L)
    internal val pendingLoad = AtomicReference<LoadCompletion?>(null)
    val lastActivityMs: AtomicLong = AtomicLong(System.currentTimeMillis())

    @Volatile
    var destroyed: Boolean = false
        private set

    /**
     * Load [url] and suspend until `onPageFinished` matches the new load id,
     * or [timeoutMs] elapses. Returns the latest [LoadState] either way.
     */
    suspend fun loadUrl(url: String, timeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS): LoadState {
        ensureAlive()
        val loadId = loadSeq.incrementAndGet()
        val completion = CompletableDeferred<Unit>()
        pendingLoad.set(LoadCompletion(loadId, completion))

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

        withContext(Dispatchers.Main) {
            webView.loadUrl(url)
        }

        withTimeoutOrNull(timeoutMs) { completion.await() }
        lastActivityMs.set(System.currentTimeMillis())
        return _loadState.value
    }

    /**
     * Fire-and-forget JS eval. Returns the raw string the JS engine produced
     * (the value passed back from `evaluateJavascript`). Caller is responsible
     * for parsing.
     */
    suspend fun evalRaw(script: String, timeoutMs: Long = DEFAULT_EVAL_TIMEOUT_MS): String? {
        ensureAlive()
        val deferred = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(script) { value -> deferred.complete(value) }
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

        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(script, null)
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        lastActivityMs.set(System.currentTimeMillis())
        return result ?: run {
            jsBridge.forget(requestId)
            throw JsBridge.JsBridgeException("bridge call '$method' timed out after ${timeoutMs}ms")
        }
    }

    /** Re-inject `bridge.js` after a navigation reset the JS realm. */
    @SuppressLint("SetJavaScriptEnabled")
    internal fun reinjectBridge() {
        if (destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            webView.post { reinjectBridge() }
            return
        }
        webView.evaluateJavascript(bridgeBootstrapJs, null)
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
        _loadState.value = _loadState.value.copy(
            currentUrl = url,
            title = title,
            status = LoadStatus.READY,
            progress = 100,
            updatedAtMs = System.currentTimeMillis(),
        )
        // Resolve any awaiting loadUrl call.
        pendingLoad.getAndSet(null)?.completion?.complete(Unit)
    }

    internal fun onReceivedError(code: Int, message: String) {
        _loadState.value = _loadState.value.copy(
            status = LoadStatus.FAILED,
            error = "$code: $message",
            updatedAtMs = System.currentTimeMillis(),
        )
        pendingLoad.getAndSet(null)?.completion?.complete(Unit)
    }

    /** Pool-only. Tears down the WebView; subsequent calls throw. */
    internal fun destroy(reason: String) {
        if (destroyed) return
        destroyed = true
        jsBridge.cancelAll(reason)
        pendingLoad.getAndSet(null)?.completion?.complete(Unit)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            webView.post { destroyInternalOnMain() }
        } else {
            destroyInternalOnMain()
        }
    }

    private fun destroyInternalOnMain() {
        runCatching {
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        }.onFailure { Log.w(TAG, "[$sessionId] destroy failed", it) }
    }

    private fun ensureAlive() {
        check(!destroyed) { "session $sessionId already destroyed" }
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

    companion object {
        private const val TAG = "WebMountSession"
        const val DEFAULT_LOAD_TIMEOUT_MS = 30_000L
        const val DEFAULT_EVAL_TIMEOUT_MS = 5_000L
        const val DEFAULT_BRIDGE_TIMEOUT_MS = 10_000L
        val EMPTY_ARGS: JsonObject = buildJsonObject { }
        private val JSON: Json = Json
    }
}

