package me.rerere.rikkahub.data.agent.webmount.primitives

import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin side of the WebView ↔ Kotlin RPC bridge.
 *
 * Attached to each pooled WebView via `addJavascriptInterface(this, "AmberWM")`.
 * Holds the pending [CompletableDeferred] map keyed by request id; the bridge
 * JS injected on every page (see `assets/webmount/bridge.js`) calls
 * [resolve] / [reject] from within the WebView's JS context.
 *
 * Async messages (page events, network log, console) are forwarded to
 * registered listeners on the [observer].
 */
class JsBridge(
    private val sessionId: String,
    private val observer: Observer = Observer.NoOp,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()

    /** Public API for [SessionHandle] / [WebViewPool] to await a roundtrip. */
    fun expect(requestId: String): CompletableDeferred<JsonElement> {
        val deferred = CompletableDeferred<JsonElement>()
        val prior = pending.putIfAbsent(requestId, deferred)
        if (prior != null) {
            // Same id already in flight — this shouldn't happen since ids are
            // UUIDs, but defend against it by failing the new request.
            deferred.completeExceptionally(IllegalStateException("duplicate requestId=$requestId"))
            return deferred
        }
        return deferred
    }

    /** Clean up a pending entry if the caller timed out. */
    fun forget(requestId: String) {
        pending.remove(requestId)
    }

    // ------------------------------------------------------------------ @JS

    @JavascriptInterface
    fun resolve(requestId: String, jsonPayload: String) {
        val deferred = pending.remove(requestId)
        if (deferred == null) {
            Log.w(TAG, "[$sessionId] resolve for unknown requestId=$requestId, payload chars=${jsonPayload.length}")
            return
        }
        val parsed = runCatching { JSON.parseToJsonElement(jsonPayload) }
            .getOrElse { error ->
                deferred.completeExceptionally(
                    JsBridgeException("Failed to parse resolve payload: ${error.message}", error)
                )
                return
            }
        deferred.complete(parsed)
    }

    @JavascriptInterface
    fun reject(requestId: String, errorMessage: String) {
        val deferred = pending.remove(requestId)
        if (deferred == null) {
            Log.w(TAG, "[$sessionId] reject for unknown requestId=$requestId: $errorMessage")
            return
        }
        deferred.completeExceptionally(JsBridgeException(errorMessage))
    }

    /** Async event — page-level or network. Routed to [observer]. */
    @JavascriptInterface
    fun onNetworkEvent(jsonPayload: String) {
        observer.onEvent(BridgeEvent.Network(parseOrNull(jsonPayload)))
    }

    @JavascriptInterface
    fun onDomMutation(jsonPayload: String) {
        observer.onEvent(BridgeEvent.DomMutation(parseOrNull(jsonPayload)))
    }

    @JavascriptInterface
    fun log(level: String, message: String) {
        when (level) {
            "error" -> Log.e(TAG, "[$sessionId][js] $message")
            "warn" -> Log.w(TAG, "[$sessionId][js] $message")
            else -> Log.d(TAG, "[$sessionId][js][$level] $message")
        }
        observer.onEvent(BridgeEvent.JsLog(level, message))
    }

    /** Force-fail every in-flight request. Called when the WebView is destroyed. */
    fun cancelAll(reason: String) {
        val snapshot = pending.toMap()
        pending.clear()
        snapshot.forEach { (_, deferred) ->
            deferred.completeExceptionally(JsBridgeException("session closed: $reason"))
        }
    }

    private fun parseOrNull(raw: String): JsonObject {
        return runCatching { JSON.parseToJsonElement(raw) as? JsonObject }
            .getOrNull()
            ?: buildJsonObject {
                put("__error", JsonPrimitive("invalid payload"))
                put("raw_chars", JsonPrimitive(raw.length))
            }
    }

    // ------------------------------------------------------------ types/api

    interface Observer {
        fun onEvent(event: BridgeEvent)
        object NoOp : Observer {
            override fun onEvent(event: BridgeEvent) = Unit
        }
    }

    sealed interface BridgeEvent {
        data class Network(val payload: JsonObject) : BridgeEvent
        data class DomMutation(val payload: JsonObject) : BridgeEvent
        data class JsLog(val level: String, val message: String) : BridgeEvent
    }

    class JsBridgeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    companion object {
        private const val TAG = "WebMountJsBridge"
        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** Empty JsonObject sentinel used by callers that don't care about a payload. */
        val EMPTY_OBJECT: JsonElement = JsonNull
    }
}
