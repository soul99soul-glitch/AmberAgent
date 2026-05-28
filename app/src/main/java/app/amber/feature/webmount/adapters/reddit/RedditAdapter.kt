package app.amber.feature.webmount.adapters.reddit

import me.rerere.ai.core.Tool
import app.amber.feature.webmount.cookie.EndpointSpec
import app.amber.feature.webmount.core.WebMountAdapter
import app.amber.feature.webmount.core.WebMountAuthMethod
import app.amber.feature.webmount.core.WebMountCapability
import app.amber.feature.webmount.core.WebMountProbeResult
import app.amber.feature.webmount.core.WebMountToolHooks

/**
 * Reddit station. Anonymous public JSON only — write requires OAuth which
 * is non-trivial (Reddit's 2023 API tightening) and not part of Phase 1.
 * IP rate limits are surfaced as DEGRADED rather than ERROR so the panel
 * shows "rate-limited, try later" instead of "broken".
 */
class RedditAdapter(private val tools: RedditTools) : WebMountAdapter {

    override val id: String = "reddit"
    override val displayName: String = "Reddit"
    override val authMethods: Set<WebMountAuthMethod> = setOf(WebMountAuthMethod.ANONYMOUS)
    override val capabilityHints: Set<WebMountCapability> = setOf(WebMountCapability.READ_ONLY)
    override val toolNamePrefix: String = "reddit_"

    override val endpoints: List<EndpointSpec> = listOf(
        EndpointSpec(
            id = "public_json",
            displayName = "Reddit public JSON",
            loginUrl = "https://www.reddit.com",
            apiBase = "https://www.reddit.com",
            origin = "https://www.reddit.com",
            cookieUrls = emptyList(),
        ),
    )

    override suspend fun probe(): WebMountProbeResult {
        return runCatching { tools.smokeProbe() }
            .map { ok ->
                if (ok) WebMountProbeResult.success(WebMountCapability.READ_ONLY, "Reddit JSON reachable")
                else WebMountProbeResult.degraded("Reddit /r/all returned empty — possibly rate-limited")
            }.getOrElse { error ->
                val message = error.message.orEmpty()
                if ("429" in message || "rate" in message.lowercase()) {
                    WebMountProbeResult.degraded("Reddit rate-limited this IP: $message")
                } else {
                    WebMountProbeResult.failed("Reddit probe failed: $message", error)
                }
            }
    }

    override fun tools(hooks: WebMountToolHooks): List<Tool> = tools.buildTools(hooks)
}
