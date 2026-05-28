package app.amber.feature.webmount.adapters.feishudocs

import app.amber.ai.core.Tool
import app.amber.feature.webmount.cookie.EndpointSpec
import app.amber.feature.webmount.core.WebMountAdapter
import app.amber.feature.webmount.core.WebMountAuthMethod
import app.amber.feature.webmount.core.WebMountCapability
import app.amber.feature.webmount.core.WebMountProbeResult
import app.amber.feature.webmount.core.WebMountToolHooks
import app.amber.feature.webmount.oauth.WebMountOAuthClient

/**
 * 飞书云文档 station — first OAuth-backed station. Lives downstream of
 * [WebMountOAuthClient] which manages the 飞书 OAuth token (acquired via
 * the WebMount Stations settings page).
 *
 * Capability: READ_WRITE. Read is always safe; write tools (create / append)
 * are marked needsApproval=true so the agent can't silently mutate docs.
 */
class FeishuDocsAdapter(
    private val tools: FeishuDocsTools,
    private val oauthClient: WebMountOAuthClient,
) : WebMountAdapter {

    override val id: String = "feishu_docs"
    override val displayName: String = "飞书云文档"
    override val authMethods: Set<WebMountAuthMethod> = setOf(WebMountAuthMethod.OAUTH)
    override val capabilityHints: Set<WebMountCapability> = setOf(
        WebMountCapability.READ_ONLY,
        WebMountCapability.READ_WRITE,
    )
    override val toolNamePrefix: String = "feishu_docs_"

    override val endpoints: List<EndpointSpec> = listOf(
        EndpointSpec(
            id = "open",
            displayName = "飞书 OpenAPI",
            loginUrl = "https://accounts.feishu.cn",
            apiBase = "https://open.feishu.cn/open-apis",
            origin = "https://open.feishu.cn",
            cookieUrls = emptyList(), // OAuth-only, no cookies needed
        ),
    )

    override suspend fun probe(): WebMountProbeResult {
        // The OAuth provider id is "feishu" (see FeishuOAuthProvider.id),
        // which differs from this adapter's id "feishu_docs". The token
        // store keys by provider id, so we resolve via "feishu".
        val token = runCatching { oauthClient.getValidAccessToken("feishu") }
            .getOrElse { return WebMountProbeResult.failed("OAuth token lookup failed: ${it.message}", it) }
            ?: return WebMountProbeResult.loginRequired(
                "未连接飞书 OAuth —— 在 WebMount Stations 设置页找到「飞书云文档」一行,先点「编辑凭据」填好 App ID / Secret,再点「Connect」。"
            )
        return runCatching {
            val ok = tools.probe(token)
            if (ok) WebMountProbeResult.success(
                WebMountCapability.READ_WRITE,
                "飞书云文档 OpenAPI reachable, token valid",
            )
            else WebMountProbeResult.degraded("飞书 /drive/v1/files returned empty — token may lack scope")
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            if ("99991663" in message || "expired" in message.lowercase()) {
                WebMountProbeResult.loginRequired("飞书 token expired or invalid; please re-Connect")
            } else {
                WebMountProbeResult.failed("飞书 probe failed: $message", error)
            }
        }
    }

    override fun tools(hooks: WebMountToolHooks): List<Tool> = tools.buildTools(hooks, oauthClient)
}
