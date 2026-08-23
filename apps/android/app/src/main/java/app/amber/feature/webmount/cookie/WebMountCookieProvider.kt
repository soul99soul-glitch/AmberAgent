package app.amber.feature.webmount.cookie

import android.webkit.CookieManager
import app.amber.feature.webmount.core.WebMountCookieBundle

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
        val urls = (endpoints.flatMap { endpoint ->
            endpoint.cookieUrls + endpoint.loginUrl + endpoint.origin + endpoint.apiBase
        } + extraUrls).distinct()
        val snapshot = snapshotCookies(urls)
        return WebMountCookieBundle(
            header = snapshot.toHeader(),
            sourceUrls = snapshot.sourceUrls(),
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
    fun snapshotCookies(urls: List<String>): CookieSnapshot {
        val cookieManager = CookieManager.getInstance()
        val headersByUrl = linkedMapOf<String, String>()
        urls.distinct().forEach { url ->
            cookieManager.getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> headersByUrl[url] = raw }
        }
        return CookieSnapshot.fromRawHeaders(headersByUrl)
    }

    fun snapshotCookieEntries(urls: List<String>): Map<String, String> {
        return snapshotCookies(urls).asLastWinsMap()
    }

    fun injectCookies(
        urls: List<String>,
        cookies: Map<String, String>,
        fieldHints: List<CookieFieldHint>,
    ) {
        val cookieManager = CookieManager.getInstance()
        cookieWritesFor(urls = urls, cookies = cookies, fieldHints = fieldHints).forEach { write ->
            cookieManager.setCookie(write.url, write.cookie)
        }
        cookieManager.flush()
    }

    fun injectCookiesAsync(
        urls: List<String>,
        cookies: Map<String, String>,
        fieldHints: List<CookieFieldHint>,
        onComplete: () -> Unit,
    ) {
        val cookieManager = CookieManager.getInstance()
        val writes = cookieWritesFor(urls = urls, cookies = cookies, fieldHints = fieldHints)
        if (writes.isEmpty()) {
            cookieManager.flush()
            onComplete()
            return
        }
        var remaining = writes.size
        writes.forEach { write ->
            cookieManager.setCookie(write.url, write.cookie) {
                remaining--
                if (remaining == 0) {
                    cookieManager.flush()
                    onComplete()
                }
            }
        }
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
        val candidates = newCookies.filter { (_, v) -> v.length >= 8 && v.isUsableCookieValue() }
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
        internal fun mergeCookieHeaderInto(raw: String, target: MutableMap<String, String>) {
            CookieSnapshot.parseRawHeader(sourceUrl = "", raw = raw)
                .forEach { entry ->
                    target[entry.name] = entry.headerValue
                }
        }

        private fun targetUrlsFor(
            hint: CookieFieldHint,
            fallbackUrls: List<String>,
        ): List<CookieWriteTarget> {
            val hinted = hint.domainHints.mapNotNull { domain ->
                val host = domain.removePrefix(".").trim()
                if (host.isBlank()) return@mapNotNull null
                CookieWriteTarget(
                    url = "https://$host",
                    domain = domain.takeIf { it.startsWith(".") },
                )
            }
            val fallback = fallbackUrls.map { CookieWriteTarget(url = it, domain = null) }
            return (hinted + fallback).distinctBy { it.url + "|" + it.domain.orEmpty() }
        }

        private fun buildCookieString(
            name: String,
            value: String,
            hint: CookieFieldHint,
            target: CookieWriteTarget,
        ): String {
            return buildString {
                append(name)
                append("=")
                append(value)
                append("; Path=")
                append(hint.path.ifBlank { "/" })
                target.domain?.let { append("; Domain=").append(it) }
                append("; Secure")
                hint.sameSite?.takeIf { it.isNotBlank() }?.let { append("; SameSite=").append(it) }
                if (hint.httpOnly) append("; HttpOnly")
            }
        }

        private fun cookieWritesFor(
            urls: List<String>,
            cookies: Map<String, String>,
            fieldHints: List<CookieFieldHint>,
        ): List<CookieWrite> {
            val hintsByName = fieldHints.associateBy { it.name }
            val fallbackUrls = urls.filter { it.startsWith("https://") || it.startsWith("http://") }
            return cookies
                .filter { (name, value) -> name.isNotBlank() && value.isUsableCookieValue() }
                .flatMap { (name, value) ->
                    val hint = hintsByName[name] ?: CookieFieldHint(name = name, required = false)
                    targetUrlsFor(hint, fallbackUrls).map { target ->
                        CookieWrite(
                            url = target.url,
                            cookie = buildCookieString(name = name, value = value, hint = hint, target = target),
                        )
                    }
                }
        }
    }
}

private data class CookieWriteTarget(
    val url: String,
    val domain: String?,
)

private data class CookieWrite(
    val url: String,
    val cookie: String,
)
