package me.rerere.rikkahub.data.agent.webmount.adapters.hackernews

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.webmount.cookie.EndpointSpec
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAdapter
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAuthMethod
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCapability
import me.rerere.rikkahub.data.agent.webmount.core.WebMountProbeResult
import me.rerere.rikkahub.data.agent.webmount.core.WebMountToolHooks

/**
 * HackerNews station — the baseline anonymous adapter. No login, no
 * cookies, no rate limiting; serves as the smoke-test path that the
 * WebMount framework actually wires through to tools.
 */
class HnAdapter(private val tools: HnTools) : WebMountAdapter {

    override val id: String = "hackernews"
    override val displayName: String = "HackerNews"
    override val authMethods: Set<WebMountAuthMethod> = setOf(WebMountAuthMethod.ANONYMOUS)
    override val capabilityHints: Set<WebMountCapability> = setOf(WebMountCapability.READ_ONLY)
    override val toolNamePrefix: String = "hn_"

    override val endpoints: List<EndpointSpec> = listOf(
        EndpointSpec(
            id = "firebase",
            displayName = "HN Firebase API",
            loginUrl = "https://news.ycombinator.com",
            apiBase = "https://hacker-news.firebaseio.com/v0",
            origin = "https://news.ycombinator.com",
            cookieUrls = emptyList(), // anonymous
        ),
    )

    /**
     * Probe: cheap GET against `/topstories.json`. If it returns >0 ids,
     * the adapter is healthy and read-only.
     */
    override suspend fun probe(): WebMountProbeResult {
        return runCatching {
            val ok = tools.smokeProbe()
            if (ok) WebMountProbeResult.success(WebMountCapability.READ_ONLY, "HN Firebase API reachable")
            else WebMountProbeResult.degraded("Top stories returned empty list")
        }.getOrElse { WebMountProbeResult.failed("HN probe failed: ${it.message}", it) }
    }

    override fun tools(hooks: WebMountToolHooks): List<Tool> = tools.buildTools(hooks)
}
