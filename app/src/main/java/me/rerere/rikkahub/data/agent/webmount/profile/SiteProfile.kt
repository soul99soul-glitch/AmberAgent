package me.rerere.rikkahub.data.agent.webmount.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 2 M2.1 — manifest-style Site Profile.
 *
 * A Profile enriches WebMount's generic `wm_*` primitives with declarative
 * site-specific hints (which cookie indicates login, what selectors point
 * at meaningful content, how to recognise rate-limit responses) and
 * optionally bridges page-defined JavaScript functions (e.g. WBI signing).
 *
 * **Critical: profiles are NOT arbitrary JS injection.** Any [scripts]
 * entry only declares the *name* of a function that lives in the page's
 * own bundle plus its argument tuple — the WebMount host wraps it in a
 * try/catch and JSON.stringify shell. See [WebMountAdapter] kdoc for the
 * full L1-L5 hardening rationale.
 *
 * JSON files use snake_case keys; the parser (see [profileJson]) applies
 * a SnakeCase naming strategy so Kotlin property names like
 * [loginCookie] map to `login_cookie` in the manifest.
 *
 * @property id Stable identifier; matches the `[a-z0-9_]+` adapter id format.
 * @property origins Allow-list of origins this profile applies to. The
 *   resolver matches the WebView's `location.origin` exactly (no
 *   wildcard / subdomain expansion).
 * @property permissions Typed capability claims; validated against the
 *   [ProfilePermission] grammar. The Registry refuses to load profiles
 *   whose [scripts] reference functions not declared via
 *   `call_page_fn:<name>` permissions.
 */
@Serializable
data class SiteProfile(
    val id: String,
    val name: String,
    val version: Int = 1,
    val origins: List<String>,
    val capabilities: List<String> = emptyList(),
    val hints: ProfileHints = ProfileHints(),
    val scripts: Map<String, ProfileScript> = emptyMap(),
    val permissions: List<String> = emptyList(),
    /** Per-second cap on [ProfileScript] bridge calls. Clamped to [1, 20]. */
    @SerialName("rate_limit_per_sec")
    val rateLimitPerSec: Int = DEFAULT_RATE_LIMIT_PER_SEC,
    /** Free-form note shown in the import audit UI. Optional. */
    val notes: String? = null,
) {

    /**
     * Throws [IllegalArgumentException] if the manifest violates a hard
     * invariant. Called by [ProfileRegistry] on every load — both built-in
     * and user-imported — so the registry can refuse malformed profiles
     * before they enter the live index.
     */
    fun validate() {
        require(id.matches(ID_PATTERN)) {
            "Profile id '$id' must match $ID_PATTERN"
        }
        require(name.isNotBlank()) { "Profile $id: name is blank" }
        require(version >= 1) { "Profile $id: version must be >= 1, got $version" }
        require(origins.isNotEmpty()) { "Profile $id: origins must be non-empty" }
        origins.forEach { origin ->
            require(origin.startsWith("https://") || origin.startsWith("http://")) {
                "Profile $id: origin '$origin' must start with http(s)://"
            }
            require(!origin.endsWith("/")) {
                "Profile $id: origin '$origin' must not end with '/'"
            }
        }
        require(rateLimitPerSec in 1..MAX_RATE_LIMIT_PER_SEC) {
            "Profile $id: rate_limit_per_sec must be in 1..$MAX_RATE_LIMIT_PER_SEC (got $rateLimitPerSec)"
        }
        // M2.1 review N-2: rate_limit.map_to must map to a known WebMountStatus
        // wire form so the M2.3 fault-mapping path can act on it.
        hints.rateLimit?.mapTo?.let { wire ->
            require(wire in VALID_RATE_LIMIT_MAP_TO) {
                "Profile $id: rate_limit.map_to '$wire' must be one of " +
                    VALID_RATE_LIMIT_MAP_TO.joinToString(", ")
            }
        }
        // Parse + validate permissions, collect the page-fn ones.
        val parsed = permissions.map { ProfilePermission.parse(it, id) }
        val declaredFns = parsed.mapNotNull { (it as? ProfilePermission.CallPageFn)?.fnName }.toSet()
        scripts.forEach { (key, script) ->
            require(script.callPageFn.isNotBlank()) {
                "Profile $id: scripts.$key.call_page_fn must be non-blank"
            }
            // Strip leading "window." for permission matching (the bridge
            // resolves both `window.foo` and `foo` to the same function).
            val fnLookup = script.callPageFn.removePrefix("window.")
            require(fnLookup in declaredFns) {
                "Profile $id: scripts.$key calls '$fnLookup' which is not declared " +
                    "in permissions (need 'call_page_fn:$fnLookup')"
            }
        }
        // Login cookie probe implies detect_login permission.
        if (hints.loginCookie != null) {
            require(parsed.any { it is ProfilePermission.DetectLogin }) {
                "Profile $id: hints.login_cookie set but permissions missing 'detect_login'"
            }
        }
    }

    /** Convenience: the agent-facing capability list, with sensible fallbacks. */
    fun effectiveCapabilities(): List<String> =
        capabilities.ifEmpty {
            buildList {
                add("read")
                if (scripts.any { (k, _) -> k == "sign_request" }) add("signing")
            }
        }

    companion object {
        const val DEFAULT_RATE_LIMIT_PER_SEC: Int = 10
        const val MAX_RATE_LIMIT_PER_SEC: Int = 20
        private val ID_PATTERN = Regex("[a-z0-9_]+")
        // Mirrors WebMountStatus wire names — kept as plain strings here to
        // avoid pulling the core module into the profile package.
        private val VALID_RATE_LIMIT_MAP_TO = setOf("DEGRADED", "ERROR", "LOGIN_REQUIRED")
    }
}

/**
 * Declarative hints embedded in [SiteProfile.hints]. All fields optional
 * — a minimal profile only needs `origins`.
 *
 * - [loginCookie]: cookie name whose presence indicates a logged-in
 *   session. Used by `wm_state` / `wm_stations` to surface login status
 *   without any extra request.
 * - [interactiveSelectors]: friendly-name → CSS selector map. Surfaced
 *   to the agent so it can read `profile.hints.interactive_selectors.answer`
 *   and skip a brittle DOM search.
 * - [rateLimit]: shape of this site's rate-limit / anti-bot response so
 *   the manager can map the station to DEGRADED instead of ERROR.
 */
@Serializable
data class ProfileHints(
    @SerialName("login_cookie")
    val loginCookie: String? = null,
    @SerialName("interactive_selectors")
    val interactiveSelectors: Map<String, String> = emptyMap(),
    @SerialName("rate_limit")
    val rateLimit: RateLimitPattern? = null,
)

@Serializable
data class RateLimitPattern(
    /** HTTP status code that signals rate-limit (e.g. 412 for B 站). */
    @SerialName("http_status")
    val httpStatus: Int? = null,
    /**
     * Substring or simple `code:<n>` body-pattern signalling rate-limit
     * in a 2xx envelope. Matching is `contains` on the JSON text.
     */
    @SerialName("body_pattern")
    val bodyPattern: String? = null,
    @SerialName("map_to")
    val mapTo: String = "DEGRADED",
)

/**
 * Bridge to a page-defined JavaScript function. Profiles never inject new
 * code; they only point at functions the site's own bundle already
 * defined. See [WebMountAdapter] L1-L5 hardening rationale.
 *
 * @property callPageFn Fully-qualified function name (e.g. `window.__sign`).
 *   The bridge accepts both `window.X` and `X` forms.
 * @property args Names of the call arguments — purely documentation, the
 *   actual values come from the caller at execution time.
 */
@Serializable
data class ProfileScript(
    @SerialName("call_page_fn")
    val callPageFn: String,
    val args: List<String> = emptyList(),
)
