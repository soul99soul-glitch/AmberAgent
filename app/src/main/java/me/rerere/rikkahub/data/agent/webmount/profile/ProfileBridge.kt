package me.rerere.rikkahub.data.agent.webmount.profile

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.agent.webmount.primitives.SessionHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 M2.2 — Profile signing bridge.
 *
 * Owns the gating logic around `SessionHandle.evalSilent`:
 *
 *  - [checkCallPageFn]: verifies the profile granted `call_page_fn:<x>`.
 *  - [acquireSlot]: token-bucket rate limit per profile.
 *  - [originAllowed]: enforce the L3 origin binding.
 *  - [callSign]: full end-to-end call — inject host shim if bundled,
 *    then evalSilent the function with the supplied args, parse the JSON
 *    result, return to the caller. Used by `wm_extract mode=signed_fetch`.
 */
class ProfileBridge(
    private val shimRegistry: HostShimRegistry,
) {

    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    /**
     * Verify the profile entry's effective permissions include
     * `call_page_fn:<fnName>`. Returns false (with a log) on any miss so
     * the caller can fall back to generic wm_* without surprising the
     * user with a thrown exception.
     */
    fun checkCallPageFn(entry: SiteProfileEntry, fnName: String): Boolean {
        val lookup = fnName.removePrefix("window.")
        if (!entry.effective.hasCallPageFn(lookup)) {
            Log.w(
                TAG,
                "Profile ${entry.profile.id}: call_page_fn:$lookup not in granted permissions " +
                    "(declared=${entry.profile.permissions}, granted=${entry.effective.granted.map { it.wire }})"
            )
            return false
        }
        return true
    }

    /**
     * Verify the current WebView's `location.origin` is in the profile's
     * declared [SiteProfile.origins] list. L3 origin-binding enforcement.
     */
    fun originAllowed(entry: SiteProfileEntry, currentOrigin: String): Boolean {
        if (currentOrigin !in entry.profile.origins) {
            Log.w(
                TAG,
                "Profile ${entry.profile.id}: current origin '$currentOrigin' not in " +
                    "allow-list (${entry.profile.origins})"
            )
            return false
        }
        return true
    }

    /**
     * Consume one slot from the profile's per-second token bucket.
     * Returns false if the bucket is empty (the caller should fall back
     * to generic wm_* and let the agent retry).
     *
     * M2.1 review W-2: user-imported profiles get an additional bridge-side
     * cap on top of the schema's [SiteProfile.MAX_RATE_LIMIT_PER_SEC]. A
     * malicious imported profile cannot claim 20/s; it's hard-capped at
     * [USER_IMPORTED_MAX_RATE_LIMIT_PER_SEC] regardless of manifest value.
     */
    fun acquireSlot(entry: SiteProfileEntry): Boolean {
        val declared = entry.profile.rateLimitPerSec.coerceIn(1, SiteProfile.MAX_RATE_LIMIT_PER_SEC)
        val effective = when (entry.trust) {
            ProfileTrustLevel.BUILTIN -> declared
            ProfileTrustLevel.USER_IMPORTED -> declared.coerceAtMost(USER_IMPORTED_MAX_RATE_LIMIT_PER_SEC)
        }
        val bucket = buckets.computeIfAbsent(entry.profile.id) { TokenBucket(effective) }
        return bucket.tryAcquire()
    }

    /** For tests: clear bucket state for one profile. */
    internal fun resetBucket(profileId: String) {
        buckets.remove(profileId)
    }

    /**
     * End-to-end signing call:
     *  1. Validate origin allow-list.
     *  2. Validate the profile granted `call_page_fn:<fnName>`.
     *  3. Acquire a rate-limit slot.
     *  4. Inject the host shim (idempotent JS) so the function exists.
     *  5. Build a host-defined wrapper `(async() => JSON.stringify(await fn(...args)))()`
     *     and run it via [SessionHandle.evalSilent].
     *  6. Parse the JSON envelope. The wrapper catches exceptions and
     *     returns `{__amberError: msg}` so failures don't throw across the
     *     JS bridge boundary.
     *
     * Returns the parsed result, or a structured error envelope. Never
     * throws on signing-side failures — `wm_extract signed_fetch` can
     * surface the error to the agent.
     */
    suspend fun callSign(
        handle: SessionHandle,
        entry: SiteProfileEntry,
        currentOrigin: String,
        scriptKey: String,
        args: List<JsonElement>,
        timeoutMs: Long = 8_000L,
    ): SignResult {
        val script = entry.profile.scripts[scriptKey]
            ?: return SignResult.Error("Profile ${entry.profile.id} has no script '$scriptKey'")
        val fnName = script.callPageFn.removePrefix("window.")

        if (!originAllowed(entry, currentOrigin)) {
            return SignResult.Error("Origin '$currentOrigin' not in profile allow-list")
        }
        if (!checkCallPageFn(entry, fnName)) {
            return SignResult.Error("Profile permissions do not grant call_page_fn:$fnName")
        }
        if (!acquireSlot(entry)) {
            return SignResult.RateLimited(
                "Profile ${entry.profile.id} signing rate limit exceeded " +
                    "(cap=${entry.profile.rateLimitPerSec}/s)"
            )
        }

        // Inject the host shim if one is bundled. Idempotent JS so re-running
        // after every navigation is safe.
        shimRegistry.shimSource(entry.profile.id)?.let { handle.injectHostShim(it) }

        // Build the wrapper. The shim's signing function may be async (returns
        // a Promise); the wrapper awaits it and JSON.stringifies the result so
        // the value travels across the WebView's JS-to-host string channel.
        val argsLiteral = JsonArray(args).toString()
        val fnRef = "window[" + JsonPrimitive(fnName).toString() + "]"
        val script_js = """
            (async function() {
              try {
                if (typeof $fnRef !== 'function') {
                  return JSON.stringify({__amberError: 'shim function ' + ${JsonPrimitive(fnName)} + ' not defined'});
                }
                var args = $argsLiteral;
                var result = await $fnRef.apply(null, args);
                return JSON.stringify(result);
              } catch (e) {
                return JSON.stringify({__amberError: String(e && e.message || e)});
              }
            })();
        """.trimIndent()

        val raw = handle.evalSilent(script_js, timeoutMs = timeoutMs)
            ?: return SignResult.Error("evalSilent returned null (timeout?)")

        // Android WebView wraps the return value in JSON.stringify of the
        // string itself when our wrapper already returned a string — so we
        // get a double-encoded JSON string. Parse once to unwrap, then again
        // to get the actual envelope.
        val unwrapped = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
        }.getOrElse { return SignResult.Error("Bridge return not JSON: $raw") }
        val inner = when (unwrapped) {
            is JsonPrimitive ->
                if (unwrapped.isString) unwrapped.content
                else return SignResult.Error("Bridge returned non-string primitive: $raw")
            JsonNull -> return SignResult.Error("Bridge returned null")
            else -> raw // already structured; treat as JSON envelope
        }
        val payload = runCatching {
            if (inner === raw) unwrapped else kotlinx.serialization.json.Json.parseToJsonElement(inner)
        }.getOrElse { return SignResult.Error("Inner not JSON: $inner") }

        val obj = payload as? JsonObject
            ?: return SignResult.Success(payload) // non-object payloads pass through
        obj["__amberError"]?.let {
            return SignResult.Error("Sign function threw: ${(it as? JsonPrimitive)?.content ?: it.toString()}")
        }
        return SignResult.Success(payload)
    }

    sealed class SignResult {
        data class Success(val value: JsonElement) : SignResult()
        data class Error(val message: String) : SignResult() {
            fun toJson(): JsonObject = buildJsonObject {
                put("ok", false)
                put("error", message)
            }
        }
        data class RateLimited(val message: String) : SignResult() {
            fun toJson(): JsonObject = buildJsonObject {
                put("ok", false)
                put("error", message)
                put("rate_limited", true)
            }
        }
    }

    companion object {
        private const val TAG = "WebMountProfileBridge"
        /**
         * Hard cap applied to user-imported profile rate limits regardless
         * of what their manifest declares. Built-in profiles can use the
         * full schema range up to [SiteProfile.MAX_RATE_LIMIT_PER_SEC].
         */
        const val USER_IMPORTED_MAX_RATE_LIMIT_PER_SEC: Int = 5
    }
}

/** Tiny token bucket: refills 1 slot every (1000/capacity) ms. */
internal class TokenBucket(
    private val capacity: Int,
    initialNowMs: Long = System.currentTimeMillis(),
) {
    private var available: Int = capacity
    private var lastRefillMs: Long = initialNowMs
    private val refillIntervalMs: Long = (1_000L / capacity).coerceAtLeast(1L)

    @Synchronized
    fun tryAcquire(now: Long = System.currentTimeMillis()): Boolean {
        val elapsed = now - lastRefillMs
        if (elapsed >= refillIntervalMs) {
            val refilled = (elapsed / refillIntervalMs).toInt().coerceAtMost(capacity - available)
            available = (available + refilled).coerceAtMost(capacity)
            lastRefillMs = now
        }
        return if (available > 0) {
            available--
            true
        } else {
            false
        }
    }
}
