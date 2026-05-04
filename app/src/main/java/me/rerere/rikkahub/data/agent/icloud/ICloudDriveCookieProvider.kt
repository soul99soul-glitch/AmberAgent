package me.rerere.rikkahub.data.agent.icloud

import android.webkit.CookieManager

class ICloudDriveCookieProvider {
    fun getCookies(extraUrls: List<String> = emptyList()): ICloudDriveCookieBundle {
        val cookieManager = CookieManager.getInstance()
        val urls = listOf(
            "https://www.icloud.com",
            "https://setup.icloud.com",
            ICLOUD_LOGIN_URL,
        ) + extraUrls
        val cookiesByName = linkedMapOf<String, String>()
        urls.forEach { url ->
            cookieManager.getCookie(url)
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.contains("=") }
                ?.forEach { cookie ->
                    val name = cookie.substringBefore("=").trim()
                    if (name.isNotBlank()) {
                        cookiesByName[name] = cookie
                    }
                }
        }
        return ICloudDriveCookieBundle(cookiesByName.values.joinToString("; "))
    }
}
