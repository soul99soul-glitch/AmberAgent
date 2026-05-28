package app.amber.feature.webmount.core

import kotlinx.serialization.json.JsonElement
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.webmount.cookie.EndpointSpec
import app.amber.feature.webmount.cookie.WebMountCookieProvider

/**
 * Per-adapter wrapper around [AgentToolActivityStore]. Generalization of
 * `ICloudDriveTools.trackICloudTool` so every WebMount tool reports its run
 * (start / complete / fail) with consistent runtime metadata.
 *
 * Phase 2 M2.0.5 also hosts cookie fetching for cookie-auth adapters
 * (掘金 / B 站 / 知乎). Adapters used to thread `(endpoints, cookieProvider)`
 * through every tool function and call `cookieProvider.getCookies(endpoints)`
 * inline; the hooks now own that responsibility so the call site is
 * `hooks.cookies()` or `hooks.requireCookies("z_c0")`.
 *
 * For OAuth or anonymous adapters that don't need cookies, [cookieProvider]
 * is null and the cookie helpers always return [WebMountCookieBundle.EMPTY]
 * (or throw, for [requireCookies]).
 */
class WebMountToolHooks(
    private val activityStore: AgentToolActivityStore,
    val stationId: String,
    val runtimeLabel: String,
    val workspace: String,
    private val cookieProvider: WebMountCookieProvider? = null,
    private val endpoints: List<EndpointSpec> = emptyList(),
) {
    suspend fun track(
        toolName: String,
        title: String,
        input: JsonElement,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val toolCallId = activityStore.startTool(
            toolName = toolName,
            title = title,
            inputPreview = input.toString(),
            runtime = runtimeLabel,
            workspace = workspace,
        )
        return try {
            val result = block()
            activityStore.complete(toolCallId, previewText(result))
            result
        } catch (error: Throwable) {
            activityStore.fail(toolCallId, error)
            throw error
        }
    }

    /**
     * Fetch the adapter's cookie bundle. Returns [WebMountCookieBundle.EMPTY]
     * for adapters that don't carry cookies (the bundle's `isEmpty` is true
     * and `hasAll` returns false for any non-empty set).
     *
     * Non-suspend — Android's `CookieManager` is synchronous and we
     * intentionally keep callers free to invoke this from non-coroutine
     * contexts (tests, debug UI).
     */
    fun cookies(): WebMountCookieBundle =
        cookieProvider?.getCookies(endpoints) ?: WebMountCookieBundle.EMPTY

    /**
     * Like [cookies] but enforces the bundle contains every name in [required].
     * Throws with a uniform message when missing — adapters used to write
     * their own bespoke "请先在 WebMount 设置页登录" string and inconsistent
     * cookie checks; this centralizes both.
     */
    fun requireCookies(vararg required: String): WebMountCookieBundle {
        val bundle = cookies()
        val needed = required.toSet()
        require(bundle.hasAll(needed)) {
            val missing = needed.filter { bundle.value(it) == null }
            "$stationId 需要登录:缺少 cookie ${missing.joinToString(", ")}。请先在 WebMount 设置页登录。"
        }
        return bundle
    }

    private fun previewText(parts: List<UIMessagePart>): String =
        parts.joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)
}
