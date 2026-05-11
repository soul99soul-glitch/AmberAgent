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

    companion object {
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
