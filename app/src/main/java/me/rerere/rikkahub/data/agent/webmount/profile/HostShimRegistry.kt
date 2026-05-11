package me.rerere.rikkahub.data.agent.webmount.profile

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 M2.2 — Loader for host-defined signing shims.
 *
 * Profiles can declare `scripts.sign_request.call_page_fn = "__amberXxx"`
 * but the function must actually exist in the page's JS realm. For sites
 * whose own bundle exposes a usable signing function (rare), the page has
 * it natively. For sites that don't (Bilibili — WBI computation is split
 * across the bundle and isn't reachable as a single global), AmberAgent
 * ships a **host-defined shim** that defines the function inside the
 * page's origin and computes the signature itself.
 *
 * SAFETY: shims are loaded from `assets/webmount/shims/<profile-id>.js`
 * — bundled with the APK, not user-supplied. A profile can only DECLARE
 * the function name; it cannot ship its own shim source. User-imported
 * profiles can reference an existing built-in shim only if their
 * `call_page_fn:<name>` permission matches an opted-in entry (see L4).
 *
 * Shim source is read once and cached. The Kotlin side passes the source
 * string to [me.rerere.rikkahub.data.agent.webmount.primitives.SessionHandle.injectHostShim],
 * which re-runs `evaluateJavascript` on the WebView's current page. Shims
 * are idempotent (each guards against re-definition) so re-injection
 * after navigation is safe.
 */
class HostShimRegistry(private val context: Context) {

    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Return the shim source for [profileId], or null if no shim is
     * bundled. Caches successful loads; failures are logged and retried
     * on subsequent calls.
     */
    fun shimSource(profileId: String): String? {
        cache[profileId]?.let { return it }
        val assetPath = "$ASSET_DIR/$profileId.js"
        return runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.fold(
            onSuccess = { source -> cache[profileId] = source; source },
            onFailure = { e ->
                // Profile may simply not have a shim — that's fine, the
                // signing call_page_fn may already exist in the page's
                // own bundle. Log at info, not warn.
                Log.i(TAG, "No host shim for profile '$profileId' at $assetPath (${e.javaClass.simpleName})")
                null
            },
        )
    }

    /** All profile ids that have a shim file bundled. Used by debug UI / wm_stations. */
    fun availableShims(): Set<String> {
        return runCatching {
            context.assets.list(ASSET_DIR)?.toList().orEmpty()
                .filter { it.endsWith(".js") }
                .map { it.removeSuffix(".js") }
                .toSet()
        }.getOrElse {
            Log.w(TAG, "Failed to list shim assets", it)
            emptySet()
        }
    }

    companion object {
        private const val TAG = "WebMountHostShimRegistry"
        private const val ASSET_DIR = "webmount/shims"
    }
}
