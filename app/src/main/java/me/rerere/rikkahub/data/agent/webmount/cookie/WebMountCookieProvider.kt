package me.rerere.rikkahub.data.agent.webmount.cookie

import android.webkit.CookieManager
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCookieBundle

/**
 * Generalization of `ICloudDriveCookieProvider`. Walks one or more
 * [EndpointSpec]s, collects whatever cookies Android's process-global
 * [CookieManager] already has for them, and merges them into a single
 * Cookie header.
 *
 * Stateless and safe to inject as a singleton. The same provider serves every
 * adapter — each adapter passes its own [EndpointSpec]s.
 */
class WebMountCookieProvider {

    fun getCookies(
        endpoints: List<EndpointSpec>,
        extraUrls: List<String> = emptyList(),
    ): WebMountCookieBundle {
        val cookieManager = CookieManager.getInstance()
        val urls = (endpoints.flatMap { endpoint ->
            endpoint.cookieUrls + endpoint.loginUrl + endpoint.origin + endpoint.apiBase
        } + extraUrls).distinct()
        val sourceUrls = mutableListOf<String>()
        val cookiesByName = linkedMapOf<String, String>()
        urls.forEach { url ->
            cookieManager.getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    sourceUrls.add(url)
                    mergeCookieHeaderInto(raw, cookiesByName)
                }
        }
        return WebMountCookieBundle(
            header = cookiesByName.values.joinToString("; "),
            sourceUrls = sourceUrls.distinct(),
        )
    }

    /**
     * Order [endpoints] so that endpoints whose cookie URLs matched in the
     * supplied bundle come first — useful when an adapter has both global and
     * regional endpoints and we want to try the one we already have cookies
     * for.
     */
    fun preferredFor(
        bundle: WebMountCookieBundle,
        endpoints: List<EndpointSpec>,
    ): List<EndpointSpec> {
        val sourceSet = bundle.sourceUrls.toSet()
        val matched = endpoints.sortedByDescending { endpoint ->
            if (endpoint.cookieUrls.any { it in sourceSet }) 1 else 0
        }
        return matched.ifEmpty { endpoints }
    }

    /**
     * Snapshot the cookie name → value map across the given URLs. Used by
     * the post-WebView-login flow to diff cookies set during the dialog
     * lifetime and infer which one represents the session token.
     */
    fun snapshotCookieEntries(urls: List<String>): Map<String, String> {
        val cookieManager = CookieManager.getInstance()
        val entries = linkedMapOf<String, String>()
        urls.distinct().forEach { url ->
            cookieManager.getCookie(url)?.split(";")
                ?.map { it.trim() }
                ?.filter { it.contains("=") }
                ?.forEach { cookie ->
                    val name = cookie.substringBefore("=").trim()
                    if (name.isNotBlank()) {
                        entries[name] = cookie.substringAfter("=").trim()
                    }
                }
        }
        return entries
    }

    /**
     * Heuristic: given the new cookies set during login, pick the one most
     * likely to represent the session token. Preference order:
     *  1. Name matches `*sess*` / `*auth*` / `*token*` / `*user*` (case-insensitive)
     *  2. Longest value (session tokens are long, flags are short)
     *  3. Stable lexicographic tiebreak
     * Returns null if no candidate has a value >= 8 chars (anything shorter
     * is almost certainly a tracking flag, not a session).
     */
    fun guessSessionCookieName(
        newCookies: Map<String, String>,
        preferredNames: List<String> = emptyList(),
    ): String? {
        pickPresentCookieName(newCookies, preferredNames)?.let { return it }
        val candidates = newCookies.filter { (_, v) -> v.length >= 8 }
        if (candidates.isEmpty()) return null
        val nameHints = setOf("sess", "auth", "token", "user")
        return candidates.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, String>> { (name, _) ->
                    val lower = name.lowercase()
                    if (nameHints.any { lower.contains(it) }) 1 else 0
                }
                    .thenByDescending { it.value.length }
                    .thenBy { it.key }
            )
            .first()
            .key
    }

    fun pickPresentCookieName(
        cookies: Map<String, String>,
        preferredNames: List<String>,
    ): String? {
        preferredNames.forEach { name ->
            val value = cookies[name]?.takeIf { it.isUsableCookieValue() }
            if (value != null) return name
        }
        return null
    }

    /**
     * Clear every cookie the system has for the given URLs. Used by the
     * WebMount Stations panel's per-station "Sign out" action.
     *
     * Android's [CookieManager] has no per-origin removeAll; the standard
     * trick is to enumerate the cookies for each URL and overwrite each
     * one with an empty value + past expiry. Calls `flush()` at the end
     * so the changes are persisted to disk.
     *
     * Returns the count of cookies expired across all URLs.
     */
    fun clearCookiesFor(urls: List<String>): Int {
        val cookieManager = CookieManager.getInstance()
        var cleared = 0
        urls.distinct().forEach { url ->
            val raw = cookieManager.getCookie(url) ?: return@forEach
            raw.split(";").map { it.trim() }.forEach { cookie ->
                val name = cookie.substringBefore("=").trim()
                if (name.isNotBlank()) {
                    // Several Path variants — Android won't match a clear
                    // with the wrong path. We can't enumerate paths so we
                    // try Path=/ which covers the common case + the empty
                    // value form for cookies set without an explicit path.
                    cookieManager.setCookie(url, "$name=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    cookieManager.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    cleared++
                }
            }
        }
        cookieManager.flush()
        return cleared
    }

    companion object {
        private fun String.isUsableCookieValue(): Boolean {
            val normalized = trim().lowercase()
            return normalized.isNotBlank() &&
                normalized != "deleted" &&
                normalized != "expired" &&
                normalized != "null" &&
                normalized != "none"
        }

        internal fun mergeCookieHeaderInto(raw: String, target: MutableMap<String, String>) {
            raw.split(";")
                .map { it.trim() }
                .filter { it.contains("=") }
                .forEach { cookie ->
                    val name = cookie.substringBefore("=").trim()
                    if (name.isNotBlank()) {
                        target[name] = cookie
                    }
                }
        }
    }
}
