package me.rerere.rikkahub.data.agent.webmount.profile

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 M2.1 — Stub bridge for the M2.2 signing channel.
 *
 * The actual JS execution (call into the page's
 * [SiteProfile.scripts]-declared function) lands in M2.2 when
 * `SessionHandle.evalSilent` exists. M2.1 ships only the **gating logic**
 * around it so the registry + profile pack can already be deployed:
 *
 *  - [checkCallPageFn]: verifies the profile granted `call_page_fn:<x>`.
 *  - [acquireSlot]: token-bucket rate limit per profile.
 *  - [originAllowed]: enforce the L3 origin binding.
 *
 * M2.2 will call these from the actual `evalSilent` entry point.
 */
class ProfileBridge {

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
     */
    fun acquireSlot(entry: SiteProfileEntry): Boolean {
        val bucket = buckets.computeIfAbsent(entry.profile.id) {
            TokenBucket(entry.profile.rateLimitPerSec.coerceIn(1, SiteProfile.MAX_RATE_LIMIT_PER_SEC))
        }
        return bucket.tryAcquire()
    }

    /** For tests: clear bucket state for one profile. */
    internal fun resetBucket(profileId: String) {
        buckets.remove(profileId)
    }

    companion object {
        private const val TAG = "WebMountProfileBridge"
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
