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
     * End-to-end signing call (Phase 2 holistic review B-1 + B-2 fix):
     *  1. Validate origin allow-list (L3 — current WebView page).
     *  2. Validate `send_signed:<host>` for the requested URL's host if
     *     supplied (so a profile can be scoped to e.g. api.bilibili.com
     *     even if the page is on www.bilibili.com).
     *  3. Validate the profile granted `call_page_fn:<fnName>`.
     *  4. Acquire a rate-limit slot (L5; user-imported capped tighter).
     *  5. Inject the host shim (idempotent JS) so the function exists.
     *  6. Call [SessionHandle.callPageFn] which routes through the
     *     `AmberWM.resolve` bridge channel so async/Promise-returning
     *     shim functions are properly awaited.
     *
     * Returns the parsed JSON envelope, or a structured error result.
     * Never throws on signing-side failures — `wm_signed_fetch` can
     * surface the error to the agent.
     */
    suspend fun callSign(
        handle: SessionHandle,
        entry: SiteProfileEntry,
        currentOrigin: String,
        scriptKey: String,
        args: List<JsonElement>,
        timeoutMs: Long = 8_000L,
        requestedUrlHost: String? = null,
    ): SignResult {
        val script = entry.profile.scripts[scriptKey]
            ?: return SignResult.Error("Profile ${entry.profile.id} has no script '$scriptKey'")
        val fnName = script.callPageFn.removePrefix("window.")

        // L3 origin check (page-side).
        if (!originAllowed(entry, currentOrigin)) {
            return SignResult.Error("Origin '$currentOrigin' not in profile allow-list")
        }
        // L2 permission check.
        if (!checkCallPageFn(entry, fnName)) {
            return SignResult.Error("Profile permissions do not grant call_page_fn:$fnName")
        }
        // Holistic review B-2 fix: validate the OUTBOUND host against
        // either send_signed:<host> permission or the profile origins.
        // Without this, an agent passing an arbitrary `url` to
        // wm_signed_fetch could direct the shim's in-page fetch
        // anywhere — the SOP would block credentialed requests but
        // the bridge would still expose the response shape.
        if (requestedUrlHost != null) {
            val sendSignedHosts = entry.effective.granted
                .filterIsInstance<ProfilePermission.SendSigned>()
                .map { it.host }
            val originAllowed = entry.profile.origins.any { it == requestedUrlHost }
            val signedAllowed = sendSignedHosts.any { it == requestedUrlHost }
            if (!originAllowed && !signedAllowed) {
                return SignResult.Error(
                    "Outbound host '$requestedUrlHost' not in profile origins " +
                        "or send_signed permissions"
                )
            }
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

        // Route through the bridge so async functions are awaited (Phase 2
        // holistic review B-1 fix — the old IIFE-returns-Promise pattern
        // produced "{}" for every call because evaluateJavascript doesn't
        // await Promises).
        val payload = try {
            handle.callPageFn(
                fnName = fnName,
                args = kotlinx.serialization.json.JsonArray(args),
                timeoutMs = timeoutMs,
            )
        } catch (error: Throwable) {
            return SignResult.Error("callPageFn '$fnName' failed: ${error.message ?: error.toString()}")
        }

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
