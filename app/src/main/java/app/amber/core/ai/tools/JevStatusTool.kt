package app.amber.core.ai.tools

import android.content.Context
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.agent.R
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.jev.JevApiMode
import app.amber.core.jev.JevDataScope
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevLimits
import app.amber.core.jev.JevPurpose
import app.amber.core.jev.jevEndpointFor
import app.amber.core.settings.Settings

/**
 * Factory for the `jev_status` agent tool — exposes the Jev quick-judgment
 * service configuration and runtime state to the model, the same way
 * `permissions_status` exposes permission capability state.
 *
 * Read-only and metadata-level only: never returns the API key, judged state
 * payloads, or question contents. Registered unconditionally (even when Jev is
 * disabled) so the model can discover that the capability exists and report
 * "configured but off" instead of being blind to it.
 */
fun createJevStatusTool(
    settingsProvider: () -> Settings,
    coordinator: JevDecisionCoordinator,
    displayContext: Context,
): Tool = Tool(
    name = "jev_status",
    description = "Report AmberAgent's Jev quick-judgment service status: master switch, API mode " +
        "(typesafe/vercel) and resolved endpoint, per-purpose off/shadow/active modes, allowed data " +
        "scopes, API key presence, daily budget, cooldown/auth-pause state, and recent decision " +
        "metrics. Use to answer 'what is jev', explain which internal pipelines it affects, or " +
        "diagnose why it is not acting. Read-only; never returns the API key or judged content.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    execute = {
        val jev = settingsProvider().jev
        val daily = coordinator.dailyUsage()
        val metrics = coordinator.metrics.summary()
        val status = coordinator.statusSnapshot()
        val payload = buildJsonObject {
            put(
                "about",
                "Jev is AmberAgent's quick-judgment service: it scores/selects " +
                    "among finite candidates for internal pipelines; filtering, budgets, execution " +
                    "and permissions stay in app code. It can call either the TypeSafe systemone " +
                    "endpoint natively (api_mode=typesafe) or the Vercel AI Gateway " +
                    "evaluation-model endpoint (api_mode=vercel, model via ai-model-id header). " +
                    "shadow = evaluates and " +
                    "records metrics but does not change behavior (still sends data externally); " +
                    "active = applies results; any failure or timeout falls back to the original " +
                    "code path.",
            )
            put("enabled", jev.enabled)
            put("api_mode", jev.apiMode.name.lowercase())
            put("endpoint", jevEndpointFor(jev.apiMode, jev.baseUrl))
            val effectiveModel = when (jev.apiMode) {
                JevApiMode.TYPESAFE -> jev.model ?: JevLimits.DEFAULT_MODEL
                JevApiMode.VERCEL -> jev.vercelModel?.takeIf { it.isNotBlank() }
                    ?: JevLimits.VERCEL_DEFAULT_MODEL
            }
            if (effectiveModel != null) put("model", effectiveModel) else put("model", JsonNull)
            put("api_key_configured", jev.apiKeyMask != null)
            jev.apiKeyMask?.let { put("api_key_mask", it) }
            put(
                "settings_path",
                displayContext.getString(R.string.setting_page_model_and_services) +
                    " → " + displayContext.getString(R.string.setting_page_jev),
            )
            put(
                "purposes",
                buildJsonObject {
                    JevPurpose.entries.forEach { purpose ->
                        put(
                            purpose.name,
                            buildJsonObject {
                                put("mode", jev.modeFor(purpose).name.lowercase())
                                put("description", purpose.description())
                            },
                        )
                    }
                },
            )
            put(
                "data_scopes_allowed",
                buildJsonArray { JevDataScope.entries.filter { it in jev.dataScopes }.forEach { add(it.name) } },
            )
            put(
                "data_scopes_supported",
                buildJsonArray { JevDataScope.entries.forEach { add(it.name) } },
            )
            put(
                "daily_usage",
                buildJsonObject {
                    put("requests", daily.requests)
                    put("body_bytes", daily.bodyBytes)
                    put("limit_requests", JevLimits.DAILY_MAX_REQUESTS)
                    put("limit_body_bytes", JevLimits.DAILY_MAX_BODY_BYTES)
                },
            )
            put(
                "runtime",
                buildJsonObject {
                    put("auth_paused", status.authPaused)
                    put("cooldown_remaining_ms", status.cooldownRemainingMs)
                    coordinator.metrics.lastErrorReason()?.let { put("last_error_reason", it) }
                },
            )
            put(
                "recent_metrics",
                buildJsonObject {
                    put("total", metrics.total)
                    put("applied", metrics.applied)
                    put("shadow", metrics.shadow)
                    put("cache_hits", metrics.cacheHits)
                    put("fallbacks", metrics.fallbacks)
                    put("failures", metrics.failures)
                    metrics.lastOutcome?.let { put("last_outcome", it) }
                },
            )
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** What the purpose touches, so the model can explain Jev's reach accurately. */
private fun JevPurpose.description(): String = when (this) {
    JevPurpose.TOOL_DISCOVERY -> "Semantic rerank of tool_search candidates (sends tool metadata + task text)."
    JevPurpose.MEMORY_RECALL -> "Semantic rerank of recalled memories before prompt injection."
    JevPurpose.CONTEXT_SELECTION -> "Relevance screening of long tool outputs in the prepared context."
    JevPurpose.MODEL_ROUTING -> "Task-fit ranking of council model-pool seats."
    JevPurpose.WEB_AUTOMATION -> "Bounded action decisions inside the wm_run_goal web loop."
}
