package me.rerere.rikkahub.data.agent.webmount.core

import android.content.Context
import android.os.Build

/**
 * Site-specific User-Agent overrides for visible WebMount login flows.
 *
 * Android WebView's default UA usually contains WebView markers such as
 * `; wv` and `Version/4.0`. Some sites, notably Feishu/Lark, treat that as
 * an unsupported embedded browser even though the page works in Chrome.
 */
object WebMountUserAgents {
    const val MODERN_ANDROID_CHROME =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"

    fun loginUserAgent(context: Context, stationId: String?, url: String): String? {
        val normalizedUrl = url.lowercase()
        val normalizedStation = stationId?.lowercase().orEmpty()
        return when {
            normalizedStation == "feishu_docs" || normalizedUrl.isFeishuOrLarkUrl() -> mobileChromeUserAgent(context)
            else -> loginUserAgent(stationId, url)
        }
    }

    fun loginUserAgent(stationId: String?, url: String): String? {
        val normalizedUrl = url.lowercase()
        val normalizedStation = stationId?.lowercase().orEmpty()
        return when {
            normalizedStation == "feishu_docs" || normalizedUrl.isFeishuOrLarkUrl() -> MODERN_ANDROID_CHROME
            else -> null
        }
    }

    private fun mobileChromeUserAgent(context: Context): String {
        val chromeVersion = context.chromeVersionName() ?: "138.0.7204.179"
        val androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "14" }
        val model = Build.MODEL.orEmpty()
            .replace(Regex("[;()]"), "")
            .ifBlank { "Android" }
        val buildId = Build.ID.orEmpty().ifBlank { "AP3A" }
        return "Mozilla/5.0 (Linux; Android $androidVersion; $model Build/$buildId) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Mobile Safari/537.36"
    }

    @Suppress("DEPRECATION")
    private fun Context.chromeVersionName(): String? {
        val candidates = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.google.android.webview",
            "com.android.webview",
        )
        return candidates.firstNotNullOfOrNull { packageName ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    packageManager.getPackageInfo(packageName, 0)
                }.versionName
            }.getOrNull()
        }
    }

    private fun String.isFeishuOrLarkUrl(): Boolean =
        contains("feishu.cn") ||
            contains("feishu.net") ||
            contains("larksuite.com") ||
            contains("larksuitecdn.com")
}
