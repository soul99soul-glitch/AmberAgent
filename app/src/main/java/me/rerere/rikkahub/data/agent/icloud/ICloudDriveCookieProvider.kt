package me.rerere.rikkahub.data.agent.icloud

import android.webkit.CookieManager

class ICloudDriveCookieProvider {
    fun getCookies(extraUrls: List<String> = emptyList()): ICloudDriveCookieBundle {
        val cookieManager = CookieManager.getInstance()
        val urls = (ICloudDriveWebEndpoints.ALL.flatMap { endpoint ->
            endpoint.cookieUrls + endpoint.loginUrl + endpoint.origin + endpoint.setupEndpoint
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
        return ICloudDriveCookieBundle(
            header = cookiesByName.values.joinToString("; "),
            sourceUrls = sourceUrls.distinct(),
        )
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
