package me.rerere.rikkahub.data.agent.webmount.adapters.juejin

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.webmount.cookie.EndpointSpec
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAdapter
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAuthMethod
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCapability
import me.rerere.rikkahub.data.agent.webmount.core.WebMountProbeResult
import me.rerere.rikkahub.data.agent.webmount.core.WebMountToolHooks

/**
 * 掘金 station. Cookie-based login (the user logs into juejin.cn through
 * AmberAgent's in-app WebView; we then pick up the `sid_tt` / `passport_csrf_token`
 * cookies via WebMountCookieProvider).
 *
 * Public listings (推荐文章 / 沸点) work without auth but are rate-limited by
 * IP — the probe returns DEGRADED in that case rather than ERROR.
 * Write operations (点赞 / 收藏) are NOT included in Phase 1 to avoid the
 * 风控 / account-ban risk that the M1.5 review flagged.
 */
class JuejinAdapter(
    private val tools: JuejinTools,
    private val cookieProvider: WebMountCookieProvider,
) : WebMountAdapter {

    override val id: String = "juejin"
    override val displayName: String = "掘金"
    override val authMethods: Set<WebMountAuthMethod> = setOf(WebMountAuthMethod.COOKIE, WebMountAuthMethod.ANONYMOUS)
    override val capabilityHints: Set<WebMountCapability> = setOf(WebMountCapability.READ_ONLY)
    override val toolNamePrefix: String = "juejin_"

    override val endpoints: List<EndpointSpec> = listOf(
        EndpointSpec(
            id = "main",
            displayName = "掘金",
            loginUrl = "https://juejin.cn/login",
            apiBase = "https://api.juejin.cn",
            origin = "https://juejin.cn",
            cookieUrls = listOf(
                "https://juejin.cn",
                "https://api.juejin.cn",
                "https://juejin.cn/login",
            ),
            requiredCookieNames = setOf("sessionid"),
        ),
    )

    override suspend fun probe(): WebMountProbeResult {
        return runCatching {
            val cookies = cookieProvider.getCookies(endpoints)
            val hasSession = cookies.hasAll(setOf("sessionid"))
            val ok = tools.smokeProbe(cookies)
            when {
                ok && hasSession -> WebMountProbeResult.success(
                    WebMountCapability.READ_ONLY,
                    "掘金 API reachable, logged in",
                )
                ok -> WebMountProbeResult.success(
                    WebMountCapability.READ_ONLY,
                    "掘金 API reachable (anonymous; some endpoints will need login)",
                )
                else -> WebMountProbeResult.degraded("掘金 feed returned empty — possibly rate-limited")
            }
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            if ("429" in message || "rate" in message.lowercase()) {
                WebMountProbeResult.degraded("掘金 rate-limited: $message")
            } else {
                WebMountProbeResult.failed("掘金 probe failed: $message", error)
            }
        }
    }

    override fun tools(hooks: WebMountToolHooks): List<Tool> = tools.buildTools(hooks, endpoints, cookieProvider)
}
