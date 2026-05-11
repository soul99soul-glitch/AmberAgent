package me.rerere.rikkahub.data.agent.webmount.core

import kotlinx.coroutines.flow.StateFlow
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.webmount.cookie.EndpointSpec

/**
 * One website/service plugged into the WebMount framework.
 *
 * Two adapter shapes are supported:
 *
 *  1. **Standalone adapters** (the typical case for M1.6 sites). The adapter
 *     owns its own state — [WebMountManager] holds per-station state in shared
 *     prefs, calls [probe] / [writeProbe] to mutate it, and the adapter's
 *     [tools] are surfaced into the agent's tool catalog.
 *
 *  2. **Wrapping adapters** (e.g. the iCloud prototype in M1.1). The adapter
 *     piggybacks on an existing manager (`ICloudDriveManager`) that already
 *     owns state and tools. The adapter overrides [externalStateFlow] to
 *     mirror that manager into [WebMountManager], and returns no tools — the
 *     existing `ICloudDriveTools` keeps doing its job. This makes the M1.1
 *     iCloud refactor non-destructive: the legacy code path stays intact while
 *     the new unified panel surfaces the same station.
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
