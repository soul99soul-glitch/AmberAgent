package me.rerere.rikkahub.data.agent.webmount.adapters.github

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.webmount.cookie.EndpointSpec
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAdapter
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAuthMethod
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCapability
import me.rerere.rikkahub.data.agent.webmount.core.WebMountProbeResult
import me.rerere.rikkahub.data.agent.webmount.core.WebMountToolHooks
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthTokenStore

/**
 * GitHub station. Anonymous public read-only by default. If the user has
 * stored a GitHub Personal Access Token under the `github` provider's
 * `appSecret` field in OAuth credentials (see WebMount Stations → OAuth
 * providers), it'll be sent as a Bearer token to lift the 60/hr rate
 * limit to 5000/hr.
 *
 * Full OAuth App flow (write operations) is out of scope for Phase 1 —
 * GitHub OAuth requires a registered application with a fixed client
 * secret which a mobile app can't safely embed. Phase 2 may add Device
 * Flow + PAT entry UI for write.
 */
class GithubAdapter(
    private val tools: GithubTools,
    private val oauthStore: WebMountOAuthTokenStore,
) : WebMountAdapter {

    override val id: String = "github"
    override val displayName: String = "GitHub"
    override val authMethods: Set<WebMountAuthMethod> = setOf(
        WebMountAuthMethod.ANONYMOUS,
        WebMountAuthMethod.OAUTH,
    )
    override val capabilityHints: Set<WebMountCapability> = setOf(WebMountCapability.READ_ONLY)
    override val toolNamePrefix: String = "github_"

    override val endpoints: List<EndpointSpec> = listOf(
        EndpointSpec(
            id = "rest",
            displayName = "GitHub REST",
            loginUrl = "https://github.com/login",
            apiBase = "https://api.github.com",
            origin = "https://github.com",
            cookieUrls = emptyList(), // REST API uses Bearer, not cookies
        ),
    )

    /**
     * Pull a Bearer token from OAuth credentials, if the user stored a PAT.
     * Conventions:
     *  - OAuthAppCredentials.appSecret = the PAT itself (user pastes here)
     *  - appId can be left blank or set to the username for display.
     */
    private fun bearerToken(): String? =
        oauthStore.getCredentials("github")?.appSecret?.takeIf { it.isNotBlank() }

    override suspend fun probe(): WebMountProbeResult {
        return runCatching {
            val ok = tools.probe(bearerToken())
            if (ok) {
                val cap = if (bearerToken() != null) {
                    WebMountCapability.READ_ONLY // auth lifts rate limit but Phase 1 stays read-only
                } else WebMountCapability.READ_ONLY
                WebMountProbeResult.success(cap, "GitHub REST API reachable" + if (bearerToken() != null) " (authenticated)" else " (anonymous)")
            } else WebMountProbeResult.degraded("GitHub /rate_limit returned non-success")
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            if ("403" in message || "rate" in message.lowercase()) {
                WebMountProbeResult.degraded("GitHub rate-limited: $message")
            } else {
                WebMountProbeResult.failed("GitHub probe failed: $message", error)
            }
        }
    }

    override fun tools(hooks: WebMountToolHooks): List<Tool> =
        tools.buildTools(hooks) { bearerToken() }
}
