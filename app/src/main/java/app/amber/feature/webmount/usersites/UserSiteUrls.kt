package app.amber.feature.webmount.usersites

import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.profile.ProfileRegistry

/**
 * Single source of truth for "every URL this UserSite could have cookies
 * set on". Used by:
 *  - The settings UI's Sign-out / Delete handlers (clear cookies)
 *  - The settings UI's per-card `logged_in` probe
 *  - `wm_stations` agent tool (`logged_in` probe)
 *  - `wm_site_remove` agent tool (clear cookies)
 *
 * Before centralizing, the UI walked `homepage + adapter.endpoints` while
 * `wm_stations` walked `homepage + profile.origins` — so logged_in / clear
 * could diverge between the two views, and synthesized-profile extra
 * origins were silently leaking cookies on delete. This helper takes the
 * union of all three sources so the views stay consistent.
 */
internal fun collectSiteUrls(
    site: UserSite,
    manager: WebMountManager,
    profileRegistry: ProfileRegistry,
): List<String> {
    val adapter = site.nativeAdapterId?.let { manager.adapterOf(it) }
    // Profile lookup tries the native adapter id first (for built-in
    // sites), then falls back to the UserSite id (for synthesized profiles
    // attached to a user-added site like `user_weibo`).
    val profileLookupId = site.nativeAdapterId ?: site.id
    val profile = profileRegistry.byId(profileLookupId)?.profile
    return buildSet {
        add(site.homepageUrl)
        adapter?.endpoints?.forEach { endpoint ->
            add(endpoint.origin)
            add(endpoint.apiBase)
            add(endpoint.loginUrl)
            addAll(endpoint.cookieUrls)
        }
        profile?.origins?.let { addAll(it) }
        addAll(extraLoginProbeUrlsFor(site))
    }.filter { it.isNotBlank() }.distinct()
}
