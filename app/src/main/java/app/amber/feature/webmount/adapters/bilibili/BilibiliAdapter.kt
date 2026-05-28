package app.amber.feature.webmount.adapters.bilibili

import me.rerere.ai.core.Tool
import app.amber.feature.webmount.cookie.EndpointSpec
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.core.WebMountAdapter
import app.amber.feature.webmount.core.WebMountAuthMethod
import app.amber.feature.webmount.core.WebMountCapability
import app.amber.feature.webmount.core.WebMountProbeResult
import app.amber.feature.webmount.core.WebMountToolHooks

/**
 * Bilibili station. Cookie-based via WebMount cookie provider after the
 * user logs into www.bilibili.com in the in-app WebView.
 *
 * Phase 1 surface: unsigned endpoints only (popular feed, video info,
 * search, user history). WBI-signed endpoints (user submissions, dynamic
 * feed) are documented as Phase 2; the signing dance is cheaper to keep
 * in-page via wm_eval than to port to Kotlin.
 *
 * Write operations (点赞 / 收藏 / 投币) explicitly out of scope — they
 * need WBI signing PLUS bili_jct CSRF tokens, and risk account flags.
 */
class BilibiliAdapter(
    private val tools: BilibiliTools,
    private val cookieProvider: WebMountCookieProvider,
) : WebMountAdapter {

    override val id: String = "bilibili"
    override val displayName: String = "Bilibili"
    override val authMethods: Set<WebMountAuthMethod> = setOf(
        WebMountAuthMethod.COOKIE,
        WebMountAuthMethod.ANONYMOUS,
    )
    override val capabilityHints: Set<WebMountCapability> = setOf(WebMountCapability.READ_ONLY)
    override val toolNamePrefix: String = "bilibili_"

    override val endpoints: List<EndpointSpec> = listOf(
        EndpointSpec(
            id = "main",
            displayName = "Bilibili",
            loginUrl = "https://passport.bilibili.com/login",
            apiBase = "https://api.bilibili.com",
            origin = "https://www.bilibili.com",
            cookieUrls = listOf(
                "https://www.bilibili.com",
                "https://api.bilibili.com",
                "https://passport.bilibili.com",
            ),
            requiredCookieNames = setOf("SESSDATA"),
        ),
    )

    override suspend fun probe(): WebMountProbeResult {
        return runCatching {
            val cookies = cookieProvider.getCookies(endpoints)
            val hasLogin = cookies.hasAll(setOf("SESSDATA"))
            val ok = tools.probe(cookies)
            when {
                ok && hasLogin -> WebMountProbeResult.success(
                    WebMountCapability.READ_ONLY, "Bilibili API reachable, logged in"
                )
                ok -> WebMountProbeResult.success(
                    WebMountCapability.READ_ONLY,
                    "Bilibili API reachable (anonymous; history/user endpoints need login)"
                )
                else -> WebMountProbeResult.degraded("Bilibili popular feed empty — geo-fence or rate-limit suspected")
            }
        }.getOrElse { error ->
            val msg = error.message.orEmpty()
            if ("-412" in msg || "-403" in msg || "412" in msg) {
                WebMountProbeResult.degraded("Bilibili 风控 triggered ($msg) — try again later or re-login")
            } else {
                WebMountProbeResult.failed("Bilibili probe failed: $msg", error)
            }
        }
    }

    override fun tools(hooks: WebMountToolHooks): List<Tool> = tools.buildTools(hooks)
}
