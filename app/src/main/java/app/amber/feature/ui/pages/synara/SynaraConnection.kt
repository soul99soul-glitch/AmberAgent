package app.amber.feature.ui.pages.synara

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Connection profile for the Synara desktop workbench exposed on LAN.
 *
 * Synara desktop binds loopback by default; a LAN bridge (or CLI `--host 0.0.0.0`)
 * makes the same HTTP/WebSocket surface reachable from the phone.
 *
 * Auth: the WS endpoint moved across desktop versions — current builds use
 * `/ws/bootstrap` (and no longer gate the upgrade on the token), older builds
 * used `/ws?token=<SYNARA_AUTH_TOKEN>`. We probe bootstrap first, legacy
 * second. The web UI also accepts `?token=` on the page URL.
 */
data class SynaraConnection(
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val token: String = "",
    val useHttps: Boolean = false,
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && port in 1..65535 && token.isNotBlank()

    fun httpBaseUrl(): String {
        val scheme = if (useHttps) "https" else "http"
        return "$scheme://${host.trim()}:$port"
    }

    /** HTTP page URL (token in query for SPA bootstrap). */
    fun workspaceUrl(): String {
        val base = httpBaseUrl().trimEnd('/')
        return "$base/?token=${token.trim().encodeUrlComponent()}"
    }

    /** WS endpoints to probe, newest first: current desktop `/ws/bootstrap`, legacy `/ws`. */
    fun wsProbeUrls(): List<String> {
        val scheme = if (useHttps) "wss" else "ws"
        val base = "$scheme://${host.trim()}:$port"
        val t = token.trim().encodeUrlComponent()
        return listOf("$base/ws/bootstrap?token=$t", "$base/ws?token=$t")
    }

    fun healthUrl(): String = httpBaseUrl().trimEnd('/') + "/health"

    fun validationError(): SynaraValidationError? {
        val h = host.trim()
        if (h.isEmpty()) return SynaraValidationError.MISSING_HOST
        if (port !in 1..65535) return SynaraValidationError.INVALID_PORT
        if (token.isBlank()) return SynaraValidationError.MISSING_TOKEN
        if (!useHttps && !isAllowedCleartextHost(h)) {
            return SynaraValidationError.CLEARTEXT_HOST_NOT_ALLOWED
        }
        return null
    }

    companion object {
        const val DEFAULT_PORT = 3773

        /**
         * Parse a pairing QR payload emitted by the desktop LAN bridge
         * (`http://<lan-ip>:<port>/?token=<SYNARA_AUTH_TOKEN>`). The WS bootstrap
         * form carries the same three fields, so accept it too.
         */
        fun fromQrPayload(raw: String): SynaraConnection? {
            val s = raw.trim()
            val normalized = when {
                s.startsWith("ws://", ignoreCase = true) -> "http://" + s.substring(5)
                s.startsWith("wss://", ignoreCase = true) -> "https://" + s.substring(6)
                else -> s
            }
            val url = normalized.toHttpUrlOrNull() ?: return null
            val token = url.queryParameter("token")?.trim().orEmpty()
            if (token.isEmpty()) return null
            // IPv6 literals lose their brackets in HttpUrl.host; re-add so httpBaseUrl() stays valid.
            val host = if (url.host.contains(':')) "[${url.host}]" else url.host
            return SynaraConnection(
                host = host,
                port = url.port,
                token = token,
                useHttps = url.scheme == "https",
            )
        }
    }
}

enum class SynaraValidationError {
    MISSING_HOST,
    INVALID_PORT,
    MISSING_TOKEN,
    CLEARTEXT_HOST_NOT_ALLOWED,
}

internal fun isAllowedCleartextHost(host: String): Boolean {
    val h = host.trim().lowercase()
    if (h == "localhost" || h == "127.0.0.1" || h == "10.0.2.2" || h == "[::1]" || h == "::1") {
        return true
    }
    val parts = h.split('.')
    if (parts.size != 4) return false
    val nums = parts.map { it.toIntOrNull() ?: return false }
    if (nums.any { it !in 0..255 }) return false
    // 10.0.0.0/8
    if (nums[0] == 10) return true
    // 172.16.0.0/12
    if (nums[0] == 172 && nums[1] in 16..31) return true
    // 192.168.0.0/16
    if (nums[0] == 192 && nums[1] == 168) return true
    // CGNAT 100.64.0.0/10 (Tailscale often uses this range on devices)
    if (nums[0] == 100 && nums[1] in 64..127) return true
    return false
}

private fun String.encodeUrlComponent(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
        .replace("+", "%20")
