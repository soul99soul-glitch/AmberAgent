package me.rerere.rikkahub.data.agent.webmount.adapters.zhihu

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.webmount.cookie.EndpointSpec
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAdapter
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAuthMethod
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCapability
import me.rerere.rikkahub.data.agent.webmount.core.WebMountProbeResult
import me.rerere.rikkahub.data.agent.webmount.core.WebMountToolHooks

/**
 * 知乎 station. Cookie-based via WebMount cookie provider after the user
 * signs into www.zhihu.com in AmberAgent's WebView.
 *
 * Phase 1 read-only. 知乎's modern endpoints require x-zse-93/96 signature
 * headers; the unsigned endpoints we hit here may intermittently return
 * 403 / 风控 challenges. Those map to DEGRADED so the panel shows
 * "logged in but rate-limited, retry later" instead of ERROR.
 * Write operations (点赞 / 评论) are deliberately out of scope to avoid
 * account-ban risk.
 */
class ZhihuAdapter(
    private val tools: ZhihuTools,
    private val cookieProvider: WebMountCookieProvider,
) : WebMountAdapter {

    override val id: String = "zhihu"
    override val displayName: String = "知乎"
    override val authMethods: Set<WebMountAuthMethod> = setOf(WebMountAuthMethod.COOKIE)
    override val capabilityHints: Set<WebMountCapability> = setOf(WebMountCapability.READ_ONLY)
    override val toolNamePrefix: String = "zhihu_"

    override val endpoints: List<EndpointSpec> = listOf(
        EndpointSpec(
            id = "main",
            displayName = "知乎",
            loginUrl = "https://www.zhihu.com/signin",
            apiBase = "https://www.zhihu.com",
            origin = "https://www.zhihu.com",
            cookieUrls = listOf("https://www.zhihu.com", "https://www.zhihu.com/signin"),
            requiredCookieNames = setOf("z_c0"),
        ),
    )

    override suspend fun probe(): WebMountProbeResult {
        val cookies = cookieProvider.getCookies(endpoints)
        if (cookies.isEmpty || !cookies.hasAll(setOf("z_c0"))) {
            return WebMountProbeResult.loginRequired("知乎 cookie 缺失 (z_c0) — 先在 AmberAgent 内置 WebView 里登录知乎")
        }
        return runCatching {
            val ok = tools.probe(cookies)
            if (ok) WebMountProbeResult.success(WebMountCapability.READ_ONLY, "知乎 feed reachable")
            else WebMountProbeResult.degraded("知乎 feed returned empty — possible 风控")
        }.getOrElse { error ->
            val msg = error.message.orEmpty()
            when {
                "403" in msg || "401" in msg -> WebMountProbeResult.degraded(
                    "知乎 拒绝请求 ($msg) — 可能 cookie 过期或触发了风控,刷新页面重新登录后再试"
                )
                "ANTI" in msg || "风控" in msg -> WebMountProbeResult.degraded("知乎 风控 challenge — 稍后再试")
                else -> WebMountProbeResult.failed("知乎 probe failed: $msg", error)
            }
        }
    }

    override fun tools(hooks: WebMountToolHooks): List<Tool> = tools.buildTools(hooks)
}
