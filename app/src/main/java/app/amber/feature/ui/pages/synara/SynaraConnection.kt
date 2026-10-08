package app.amber.feature.ui.pages.synara

/**
 * Connection profile for the Synara desktop workbench exposed on LAN.
 *
 * Synara desktop binds loopback by default; a LAN bridge (or CLI `--host 0.0.0.0`)
 * makes the same HTTP/WebSocket surface reachable from the phone.
 *
 * Auth: WebSocket expects `/ws?token=<SYNARA_AUTH_TOKEN>`. The web UI also
 * accepts `?token=` on the page URL so the client can store the token for the
 * subsequent WS upgrade.
 */
data class SynaraConnection(
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val token: String = "",
    val useHttps: Boolean = false,
    /** Paired External MCP credential (`syn_mcp_v1_...`). Not the desktop AUTH_TOKEN. */
    val mcpCredential: String = "",
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && port in 1..65535 && token.isNotBlank()

    val hasMcpCredential: Boolean
        get() = mcpCredential.trim().startsWith(MCP_CREDENTIAL_PREFIX)

    fun httpBaseUrl(): String {
        val scheme = if (useHttps) "https" else "http"
        return "$scheme://${host.trim()}:$port"
    }

    /** Streamable HTTP endpoint for Synara External MCP (task A2A API). */
    fun externalMcpUrl(): String = httpBaseUrl().trimEnd('/') + "/mcp/external"

    /** HTTP page URL (token in query for SPA bootstrap). */
    fun workspaceUrl(): String {
        val base = httpBaseUrl().trimEnd('/')
        return "$base/?token=${token.trim().encodeUrlComponent()}"
    }

    /**
     * Legacy WS auth: Synara accepts `/ws?token=<SYNARA_AUTH_TOKEN>`.
     * SPA code also forces pathname `/ws` via its Jo() helper; we emit the final form.
     */
    fun wsBootstrapUrl(): String {
        val scheme = if (useHttps) "wss" else "ws"
        return "$scheme://${host.trim()}:$port/ws?token=${token.trim().encodeUrlComponent()}"
    }

    /** Synara 0.7+ HTTP negotiate endpoint (plain HTTP, before feature WS). */
    fun negotiateUrl(
        clientBuild: String = "amber-companion",
        protocolEpoch: Int = 1,
        minRevision: Int = 1,
        maxRevision: Int = 1,
        requiredCapabilities: List<String> = DEFAULT_REQUIRED_CAPABILITIES,
    ): String {
        val base = httpBaseUrl().trimEnd('/') + "/ws/negotiate"
        val caps = requiredCapabilities.joinToString("&") {
            "x-synara-required-capability=${it.encodeUrlComponent()}"
        }
        return "$base?" +
            "x-synara-client-build=${clientBuild.encodeUrlComponent()}" +
            "&x-synara-protocol-epoch=$protocolEpoch" +
            "&x-synara-protocol-min-revision=$minRevision" +
            "&x-synara-protocol-max-revision=$maxRevision" +
            "&$caps"
    }

    /** Feature WS after HTTP negotiate (Synara 0.7+). */
    fun featureWsUrl(
        protocolEpoch: Int,
        protocolRevision: Int,
        serverInstanceId: String,
        clientBuild: String = "amber-companion",
    ): String {
        val scheme = if (useHttps) "wss" else "ws"
        return "$scheme://${host.trim()}:$port/ws" +
            "?token=${token.trim().encodeUrlComponent()}" +
            "&x-synara-client-build=${clientBuild.encodeUrlComponent()}" +
            "&x-synara-protocol-epoch=$protocolEpoch" +
            "&x-synara-protocol-revision=$protocolRevision" +
            "&x-synara-server-instance=${serverInstanceId.encodeUrlComponent()}"
    }

    fun healthUrl(): String = httpBaseUrl().trimEnd('/') + "/health"

    fun validationError(): String? {
        val h = host.trim()
        if (h.isEmpty()) return "请填写 Mac 的局域网 IP"
        if (port !in 1..65535) return "端口无效"
        if (token.isBlank()) return "请填写 Auth Token（桌面进程 SYNARA_AUTH_TOKEN）"
        if (!useHttps && !isAllowedCleartextHost(h)) {
            return "HTTP 仅允许私网地址（10/172.16-31/192.168/localhost）或请改用 HTTPS"
        }
        return null
    }

    companion object {
        const val DEFAULT_PORT = 3773
        const val MCP_CREDENTIAL_PREFIX = "syn_mcp_v1_"
        const val MCP_SERVER_NAME = "Synara"
        val DEFAULT_REQUIRED_CAPABILITIES = listOf(
            "orchestration.cursor-safe-streams",
            "orchestration.thread-detail-snapshot",
            "rpc.typed-errors",
        )
    }
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
