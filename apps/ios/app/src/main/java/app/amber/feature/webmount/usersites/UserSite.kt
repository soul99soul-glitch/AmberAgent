package app.amber.feature.webmount.usersites

import kotlinx.serialization.Serializable

/**
 * Phase 2 Plan v2 — single user-facing concept for "a website on the
 * WebMount Stations panel". Replaces the old triple of
 * (WebMountAdapter / OAuthProvider / SiteProfile) in the UI layer.
 *
 * The data layer keeps the three engineering concepts intact; this
 * class is the *projection* into the settings UI, where users only
 * deal with "I have a website, I want to log in / delete it / agent
 * use it".
 *
 * @property id Stable id. Native-adapter sites reuse the adapter id
 *   (`bilibili`, `feishu_docs`, ...) so the UI can join with the
 *   adapter's runtime state. User-added sites use `user_<slug>`.
 * @property displayName What the user sees in the list.
 * @property homepageUrl Where the "Sign in" button opens.
 * @property authKind How sign-in works (drives which button to show).
 * @property loginCookieName For COOKIE auth: the cookie whose presence
 *   means "logged in". Null for ANONYMOUS or OAUTH.
 * @property nativeAdapterId If non-null, this site is wired to a native
 *   adapter — the adapter's tools show up in the agent's catalog when
 *   the site is in the list. If null, only generic `wm_open` /
 *   `wm_extract` are available for the site.
 * @property iconKey Hint for the UI's icon lookup. Null → globe.
 * @property addedAtMs When the site landed in the user's list. Initial
 *   seeds use a fixed timestamp so listing order is stable across
 *   reinstalls.
 */
@Serializable
data class UserSite(
    val id: String,
    val displayName: String,
    val homepageUrl: String,
    val authKind: AuthKind,
    val loginCookieName: String? = null,
    val nativeAdapterId: String? = null,
    val iconKey: String? = null,
    val addedAtMs: Long = 0L,
    /**
     * For OAuth-backed sites, the id under which the OAuth provider is
     * registered with [app.amber.feature.webmount.oauth.WebMountOAuthClient].
     * Defaults to null (= same as [id]). 飞书 is the canonical case:
     * UserSite.id is "feishu_docs" (matches the adapter / station id) but
     * the OAuth provider registers as "feishu" — without this remap the
     * "Edit credentials" / "Connect" buttons silently look up a nonexistent
     * provider and the dialog closes itself in the same frame.
     */
    val oauthProviderId: String? = null,
)

@Serializable
enum class AuthKind {
    /** Public site — no sign-in required. */
    ANONYMOUS,
    /** Site uses a session cookie. Sign-in opens the homepage in a WebView. */
    COOKIE,
    /** Site uses OAuth (the user enters app credentials + Connect). */
    OAUTH,
}
