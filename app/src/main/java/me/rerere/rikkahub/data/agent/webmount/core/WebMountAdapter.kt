package me.rerere.rikkahub.data.agent.webmount.core

import kotlinx.coroutines.flow.StateFlow
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.webmount.cookie.EndpointSpec

/**
 * One website/service plugged into the WebMount framework.
 *
 * **Standalone adapters** (the M1.6 baseline shape, used by all 7 shipped sites):
 * the adapter owns its own state, [WebMountManager] holds per-station state in
 * shared prefs, calls [probe] / [writeProbe] to mutate it, and the adapter's
 * [tools] are surfaced into the agent's tool catalog.
 *
 * **Wrapping-adapter escape hatch**: the optional [externalStateFlow] override
 * lets an adapter mirror state from a manager that lives outside the framework
 * (so it shows up in the unified panel without owning its own probe state).
 * No shipped adapter uses this path today — it's kept available for future
 * integrations that need to surface an externally-managed station's state.
 * (Note: the iCloud experimental feature is intentionally **not** wrapped —
 * it stays as a separate standalone Settings entry per the user's directive
 * from Phase 1 M1.2.)
 *
 * ## Tool output shape convention
 *
 * Two shapes coexist in the catalog; adapter authors should match the
 * appropriate style and avoid mixing.
 *
 *  - **Browser Primitives (`wm_*`)**: RPC-style nested envelope
 *    `{session_id, ok?, result: {...}}` — `result` holds the bridge's reply.
 *    The wrap is there because the wm tools sit on top of an in-page JS RPC
 *    and the reply shape is bridge-defined.
 *
 *  - **Site adapter tools (`hn_*`, `reddit_*`, `feishu_docs_*`, etc.)**:
 *    flat query-style. List tools surface `{count, items|articles|videos: [...]}`
 *    with optional pagination cursors at the top level. Detail tools surface
 *    object fields flat at the top level (`{id, title, content, ...}`).
 *    Write tools return flat success fields (`{ok, id, ...}`) — no nested
 *    `response` wrapper around upstream payloads.
 *
 * Reasoning: primitives are inherently RPC (one in-page call, one reply);
 * adapter tools are query-style and the agent reasons over their fields
 * directly. Matching this distinction keeps tool outputs predictable.
 */
interface WebMountAdapter {
    /**
     * Stable wire id used as the SharedPreferences key prefix (`enabled.<id>` etc.)
     * and the tool name prefix. Must match `[a-z0-9_]+` — no dots, no spaces,
     * no uppercase. Dots in particular would collide with the prefs key
     * namespace separator.
     */
    val id: String
    val displayName: String
    val authMethods: Set<WebMountAuthMethod>
    val capabilityHints: Set<WebMountCapability>
    val endpoints: List<EndpointSpec>
    val toolNamePrefix: String
    val outputBudgetChars: Int get() = 80_000

    /** First endpoint's login URL — what the panel's "Connect" button opens. */
    fun primaryLoginUrl(): String? = endpoints.firstOrNull()?.loginUrl

    /**
     * If non-null, [WebMountManager] mirrors this flow into its unified state
     * map and skips its own probe-driven state machine for this adapter.
     * Used by wrapping adapters whose source-of-truth lives outside the
     * framework.
     */
    val externalStateFlow: StateFlow<WebMountStationState>? get() = null

    suspend fun probe(): WebMountProbeResult = WebMountProbeResult.notSupported()
    suspend fun writeProbe(): WebMountProbeResult = WebMountProbeResult.notSupported()

    /**
     * Tools this adapter contributes to the agent's tool catalog. Wrapping
     * adapters can return an empty list; [WebMountManager.allTools] skips them.
     */
    fun tools(hooks: WebMountToolHooks): List<Tool> = emptyList()

    fun describe(): WebMountAdapterDescriptor = WebMountAdapterDescriptor(
        id = id,
        displayName = displayName,
        authMethods = authMethods,
        capabilityHints = capabilityHints,
        endpoints = endpoints,
        toolNamePrefix = toolNamePrefix,
        outputBudgetChars = outputBudgetChars,
    )
}
